#!/usr/bin/env node

/**
 * Worker dọn dẹp screenshot báo cáo quá hạn trên Google Cloud Storage (Firebase Storage).
 *
 * Chính sách lưu trữ:
 * - Screenshot báo cáo chỉ lưu tối đa 30 ngày tính theo metadata timeCreated (UTC).
 * - Bắt đầu dọn dẹp từ ngày thứ 29 (age >= 29 days) nhằm tạo biên an toàn 1 ngày
 *   cho retry mạng hoặc worker failure trước khi chạm hạn chót 30 ngày.
 * - Mọi object >= 30 ngày được xem là vi phạm SLA và phát cảnh báo khẩn cấp.
 * - Tuyệt đối không xóa hoặc quét prefix khác ngoài reports/ (ví dụ app_icons/ hay toàn bucket).
 * - Sử dụng ifGenerationMatch để chống race condition khi file bị ghi đè.
 * - 404 được xử lý idempotent thành công.
 * - Hỗ trợ --dry-run (hoặc DRY_RUN=true).
 */

// Storage client được nạp động nếu không truyền mock (giúp test chạy nhẹ nhàng không phụ thuộc external network)
let StorageClass = null;
async function getDefaultStorage() {
    if (!StorageClass) {
        const mod = await import('@google-cloud/storage');
        StorageClass = mod.Storage;
    }
    return new StorageClass();
}

export const ALLOWED_BUCKET = 'cross-testers.firebasestorage.app';
export const TARGET_PREFIX = 'reports/';
export const DEFAULT_TARGET_AGE_DAYS = 29;
export const DEFAULT_SLA_THRESHOLD_DAYS = 30;

/**
 * Thực hiện delay bất đồng bộ cho exponential backoff.
 */
function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Xóa file an toàn kèm retry cho lỗi mạng tạm thời (429, 5xx).
 */
async function deleteWithRetry(file, generation, maxRetries = 3) {
    let attempt = 0;
    while (attempt < maxRetries) {
        attempt++;
        try {
            await file.delete({ ifGenerationMatch: generation });
            return { status: 'DELETED' };
        } catch (err) {
            const statusCode = err?.code || err?.status || (err?.response && err.response.status);
            // 404: Idempotent - file đã bị xóa bởi process khác hoặc GCS Lifecycle
            if (statusCode === 404) {
                return { status: 'ALREADY_DELETED' };
            }
            // 412: Precondition Failed - generation đã thay đổi (object bị ghi đè)
            if (statusCode === 412) {
                return { status: 'PRECONDITION_FAILED', error: err.message };
            }
            // 429 hoặc 5xx: Retry với exponential backoff
            if ((statusCode === 429 || (statusCode >= 500 && statusCode < 600)) && attempt < maxRetries) {
                const backoffMs = Math.pow(2, attempt) * 200 + Math.random() * 100;
                await sleep(backoffMs);
                continue;
            }
            throw err;
        }
    }
}

/**
 * Hàm cốt lõi thực hiện quét và dọn rác bucket.
 * Có thể nhận storage client được inject hoặc mock để kiểm thử độc lập.
 */
export async function cleanupStorage({
    storage = null,
    bucketName = process.env.STORAGE_BUCKET || ALLOWED_BUCKET,
    prefix = TARGET_PREFIX,
    targetAgeDays = Number(process.env.TARGET_AGE_DAYS) || DEFAULT_TARGET_AGE_DAYS,
    slaThresholdDays = Number(process.env.SLA_THRESHOLD_DAYS) || DEFAULT_SLA_THRESHOLD_DAYS,
    dryRun = process.env.DRY_RUN === 'true' || process.argv.includes('--dry-run'),
    now = new Date(),
    logger = console,
} = {}) {
    // 1. Kiểm tra Allowlist Bucket & Prefix
    if (bucketName !== ALLOWED_BUCKET && process.env.ALLOW_CUSTOM_BUCKET !== 'true') {
        throw new Error(`SECURITY ALERT: Bucket "${bucketName}" khong nam trong allowlist hop le ("${ALLOWED_BUCKET}").`);
    }

    if (!prefix.startsWith(TARGET_PREFIX)) {
        throw new Error(`SECURITY ALERT: Prefix "${prefix}" khong hop le. Worker chi duoc phep thao tac tren prefix "${TARGET_PREFIX}".`);
    }

    if (!storage) {
        storage = await getDefaultStorage();
    }

    const bucket = storage.bucket(bucketName);
    const nowTime = now.getTime();
    const oneDayMs = 24 * 60 * 60 * 1000;

    const stats = {
        bucket: bucketName,
        prefix,
        target_age_days: targetAgeDays,
        sla_threshold_days: slaThresholdDays,
        dry_run: dryRun,
        total_scanned: 0,
        candidates_found: 0,
        candidates_deleted: 0,
        sla_violations_count: 0,
        bytes_reclaimed: 0,
        oldest_object_age_days: 0,
        errors_count: 0,
        precondition_mismatches: 0,
        already_deleted_count: 0,
    };

    logger.info(JSON.stringify({
        event: 'CLEANUP_JOB_START',
        bucket: bucketName,
        prefix,
        dryRun,
        targetAgeDays,
        slaThresholdDays,
        timestamp: now.toISOString(),
    }));

    try {
        const [files] = await bucket.getFiles({ prefix, autoPaginate: true });

        for (const file of files) {
            stats.total_scanned++;

            // Phòng thủ: Bỏ qua nếu tên file nằm ngoài prefix được phép (ví dụ app_icons/)
            if (!file.name.startsWith(prefix)) {
                continue;
            }

            // Đọc metadata (nếu getFiles chưa populate đầy đủ thì fetch metadata)
            const metadata = file.metadata || (await file.getMetadata())[0];
            const timeCreatedStr = metadata.timeCreated;
            const generation = metadata.generation;
            const size = Number(metadata.size || 0);

            if (!timeCreatedStr) {
                logger.warn(JSON.stringify({
                    event: 'MISSING_METADATA_TIMECREATED',
                    file: file.name,
                }));
                continue;
            }

            const createdTime = new Date(timeCreatedStr).getTime();
            const ageDays = (nowTime - createdTime) / oneDayMs;

            if (ageDays > stats.oldest_object_age_days) {
                stats.oldest_object_age_days = Math.round(ageDays * 10) / 10;
            }

            // Phát hiện vi phạm SLA (quá 30 ngày mà chưa được dọn)
            if (ageDays >= slaThresholdDays) {
                stats.sla_violations_count++;
                logger.warn(JSON.stringify({
                    event: 'SLA_RETENTION_VIOLATION',
                    file: file.name,
                    age_days: Math.round(ageDays * 10) / 10,
                    time_created: timeCreatedStr,
                    sla_limit_days: slaThresholdDays,
                }));
            }

            // Ứng viên cần xóa (đã đạt từ ngày 29 trở đi)
            if (ageDays >= targetAgeDays) {
                stats.candidates_found++;
                stats.bytes_reclaimed += size;

                if (dryRun) {
                    logger.info(JSON.stringify({
                        event: 'DRY_RUN_CANDIDATE',
                        file: file.name,
                        age_days: Math.round(ageDays * 10) / 10,
                        size_bytes: size,
                    }));
                } else {
                    try {
                        const result = await deleteWithRetry(file, generation);
                        if (result.status === 'DELETED') {
                            stats.candidates_deleted++;
                        } else if (result.status === 'ALREADY_DELETED') {
                            stats.already_deleted_count++;
                        } else if (result.status === 'PRECONDITION_FAILED') {
                            stats.precondition_mismatches++;
                            logger.warn(JSON.stringify({
                                event: 'PRECONDITION_MISMATCH',
                                file: file.name,
                                generation,
                            }));
                        }
                    } catch (err) {
                        stats.errors_count++;
                        logger.error(JSON.stringify({
                            event: 'DELETE_FILE_ERROR',
                            file: file.name,
                            error: err.message,
                        }));
                    }
                }
            }
        }
    } catch (err) {
        stats.errors_count++;
        logger.error(JSON.stringify({
            event: 'SCAN_STORAGE_ERROR',
            error: err.message,
        }));
        throw err;
    }

    logger.info(JSON.stringify({
        event: 'CLEANUP_JOB_COMPLETED',
        metrics: stats,
        timestamp: new Date().toISOString(),
    }));

    return stats;
}

// Chạy trực tiếp khi được gọi qua CLI
const isDirectRun = process.argv[1] && (
    process.argv[1].endsWith('cleanup-reports-storage.mjs') ||
    process.argv[1].endsWith('cleanup-reports-storage.js')
);

if (isDirectRun || process.env.RUN_STANDALONE === 'true') {
    cleanupStorage()
        .then(stats => {
            if (stats.errors_count > 0) {
                process.exitCode = 1;
            }
        })
        .catch(err => {
            console.error('Fatal execution error:', err);
            process.exit(1);
        });
}
