#!/usr/bin/env node

/**
 * Công cụ thu thập và đối chiếu số liệu theo Cohort (E00 Rollout).
 *
 * CẢNH BÁO QUAN TRỌNG:
 * - Metric Google Cloud Storage `storage.googleapis.com/network/sent_bytes_count`
 *   KHÔNG TỰ CÓ NHÃN (label) `client_version` hay `app_build`.
 * - Do đó, không thể lọc trực tiếp Cloud Monitoring theo phiên bản app.
 * - Công cụ này thực hiện đối chiếu hai chiều:
 *   1. Dữ liệu từ App Instrumentation (ImageInstrumentation): Báo cáo theo cohort, version, bytes và hits.
 *   2. Dữ liệu từ Cloud Storage Metrics: Tổng sent_bytes_count và request count trên bucket.
 *   3. Tính toán chênh lệch (Delta): Xác định lưu lượng ngoài app (external/unclassified egress).
 *   4. F13 Fix: Xác thực tính toàn vẹn dữ liệu - dữ liệu thiếu, cloud = 0 khi client > 0,
 *      hoặc client > cloud phải được đánh dấu rõ ràng là INVALID/UNKNOWN kèm ALERT, không báo OK giả.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function reconcileCohortTelemetry({
    clientLogs = [],
    cloudStorageMetrics = { sentBytesCount: 0, requestCount: 0 },
    alertThresholdUnclassifiedPercent = 20,
} = {}) {
    // 1. Kiểm tra tính hợp lệ của cloudStorageMetrics
    if (
        !cloudStorageMetrics ||
        typeof cloudStorageMetrics !== 'object' ||
        typeof cloudStorageMetrics.sentBytesCount !== 'number' ||
        !Number.isFinite(cloudStorageMetrics.sentBytesCount) ||
        cloudStorageMetrics.sentBytesCount < 0 ||
        (cloudStorageMetrics.requestCount !== undefined && (!Number.isFinite(cloudStorageMetrics.requestCount) || cloudStorageMetrics.requestCount < 0))
    ) {
        return {
            summary: {
                status: 'INVALID',
                validationError: 'Cloud metrics không hợp lệ (NaN, Infinite hoặc số âm).',
                cloudReportedSentBytes: NaN,
                cloudReportedRequests: 0,
                clientReportedBodyBytes: 0,
                clientReportedRequests: 0,
                clientReportedMemoryHits: 0,
                unclassifiedExternalBytes: 0,
                unclassifiedPercent: 0,
                isAlertTriggered: true,
            },
            cohortBreakdown: [],
        };
    }

    // 2. Kiểm tra tính hợp lệ của clientLogs
    if (!Array.isArray(clientLogs)) {
        return {
            summary: {
                status: 'INVALID',
                validationError: 'clientLogs phải là mảng hợp lệ.',
                cloudReportedSentBytes: cloudStorageMetrics.sentBytesCount,
                cloudReportedRequests: cloudStorageMetrics.requestCount || 0,
                clientReportedBodyBytes: 0,
                clientReportedRequests: 0,
                clientReportedMemoryHits: 0,
                unclassifiedExternalBytes: 0,
                unclassifiedPercent: 0,
                isAlertTriggered: true,
            },
            cohortBreakdown: [],
        };
    }

    for (const log of clientLogs) {
        if (!log || typeof log !== 'object') {
            return {
                summary: {
                    status: 'INVALID',
                    validationError: 'Entry trong clientLogs không hợp lệ.',
                    cloudReportedSentBytes: cloudStorageMetrics.sentBytesCount,
                    cloudReportedRequests: cloudStorageMetrics.requestCount || 0,
                    clientReportedBodyBytes: 0,
                    clientReportedRequests: 0,
                    clientReportedMemoryHits: 0,
                    unclassifiedExternalBytes: 0,
                    unclassifiedPercent: 0,
                    isAlertTriggered: true,
                },
                cohortBreakdown: [],
            };
        }
        const bytes = Number(log.bodyBytesRead);
        const reqs = Number(log.requestCount ?? 0);
        if (!Number.isFinite(bytes) || bytes < 0 || !Number.isFinite(reqs) || reqs < 0) {
            return {
                summary: {
                    status: 'INVALID',
                    validationError: 'Client log chứa dữ liệu số không hợp lệ (NaN, Infinite hoặc số âm).',
                    cloudReportedSentBytes: cloudStorageMetrics.sentBytesCount,
                    cloudReportedRequests: cloudStorageMetrics.requestCount || 0,
                    clientReportedBodyBytes: 0,
                    clientReportedRequests: 0,
                    clientReportedMemoryHits: 0,
                    unclassifiedExternalBytes: 0,
                    unclassifiedPercent: 0,
                    isAlertTriggered: true,
                },
                cohortBreakdown: [],
            };
        }
    }

    let totalClientReportedBodyBytes = 0;
    let totalClientRequests = 0;
    let totalClientMemoryHits = 0;

    const cohortBreakdown = {};

    for (const log of clientLogs) {
        const cohortKey = `${log.clientVersion || 'unknown'}__${log.cohort || 'default'}`;
        if (!cohortBreakdown[cohortKey]) {
            cohortBreakdown[cohortKey] = {
                clientVersion: log.clientVersion || 'unknown',
                cohort: log.cohort || 'default',
                requestCount: 0,
                bodyBytesRead: 0,
                memoryHits: 0,
            };
        }
        const b = cohortBreakdown[cohortKey];
        b.requestCount += Number(log.requestCount || 0);
        b.bodyBytesRead += Number(log.bodyBytesRead || 0);
        b.memoryHits += Number(log.memoryHits || 0);

        totalClientReportedBodyBytes += Number(log.bodyBytesRead || 0);
        totalClientRequests += Number(log.requestCount || 0);
        totalClientMemoryHits += Number(log.memoryHits || 0);
    }

    const cloudBytes = Number(cloudStorageMetrics.sentBytesCount || 0);
    let unclassifiedBytes = 0;
    let unclassifiedPercent = 0;
    let isAlertTriggered = false;
    let status = 'OK';
    let validationError = null;

    // F13 & R2-08: Kiểm tra tính nhất quán, tính hợp lệ của cửa sổ đo lường (timestamp UTC) và tỷ lệ phủ
    let hasWindowMismatch = false;
    let hasInvalidDate = false;

    if (cloudStorageMetrics.windowStartUtc || cloudStorageMetrics.windowEndUtc) {
        const cStart = new Date(cloudStorageMetrics.windowStartUtc).getTime();
        const cEnd = new Date(cloudStorageMetrics.windowEndUtc).getTime();
        if (Number.isNaN(cStart) || Number.isNaN(cEnd) || cStart >= cEnd) {
            hasInvalidDate = true;
        }
    }

    for (const log of clientLogs) {
        if (log.windowStartUtc || log.windowEndUtc) {
            const lStart = new Date(log.windowStartUtc).getTime();
            const lEnd = new Date(log.windowEndUtc).getTime();
            if (Number.isNaN(lStart) || Number.isNaN(lEnd) || lStart >= lEnd) {
                hasInvalidDate = true;
            }
        }
        if (
            (log.windowStartUtc && cloudStorageMetrics.windowStartUtc && log.windowStartUtc !== cloudStorageMetrics.windowStartUtc) ||
            (log.windowEndUtc && cloudStorageMetrics.windowEndUtc && log.windowEndUtc !== cloudStorageMetrics.windowEndUtc)
        ) {
            hasWindowMismatch = true;
            break;
        }
    }

    const hasZeroOrNegativeCoverage = (Number.isFinite(cloudStorageMetrics.coveragePercent) && cloudStorageMetrics.coveragePercent <= 0) ||
        clientLogs.some(log => Number.isFinite(log.coveragePercent) && log.coveragePercent <= 0);

    const hasCloudWindow = Boolean(
        cloudStorageMetrics.windowStartUtc &&
        cloudStorageMetrics.windowEndUtc &&
        Number.isFinite(cloudStorageMetrics.coveragePercent) &&
        cloudStorageMetrics.coveragePercent > 0
    );
    const hasClientWindows = clientLogs.length > 0 && clientLogs.every(log =>
        Boolean(log.windowStartUtc && log.windowEndUtc && Number.isFinite(log.coveragePercent) && log.coveragePercent > 0)
    );

    // F13: Kiểm tra tính hợp lệ của dữ liệu trước khi kết luận
    if (hasInvalidDate) {
        status = 'INVALID';
        isAlertTriggered = true;
        validationError = 'Cửa sổ thời gian đo lường không hợp lệ (timestamp không parse được hoặc start >= end).';
    } else if (hasWindowMismatch) {
        status = 'INVALID';
        isAlertTriggered = true;
        validationError = 'Cửa sổ thời gian đo lường giữa Client và Cloud Storage không khớp nhau.';
    } else if (cloudBytes === 0 && totalClientReportedBodyBytes > 0) {
        // Dữ liệu Cloud Storage bị thiếu hoặc cửa sổ thời gian không khớp
        status = 'INVALID';
        isAlertTriggered = true;
        unclassifiedPercent = 100;
        validationError = 'Cloud Storage metrics = 0 trong khi Client báo cáo có lưu lượng. Cửa sổ thời gian hoặc cấu hình bucket bị lệch.';
    } else if (totalClientReportedBodyBytes > cloudBytes) {
        // Client báo cáo nhiều hơn Cloud (bất thường tổng hợp hoặc đếm trùng)
        status = 'INVALID';
        isAlertTriggered = true;
        unclassifiedBytes = 0;
        const overshootPercent = cloudBytes > 0
            ? Math.round(((totalClientReportedBodyBytes - cloudBytes) / cloudBytes) * 1000) / 10
            : 100;
        unclassifiedPercent = overshootPercent;
        validationError = `Client reported bytes (${totalClientReportedBodyBytes}) vượt quá Cloud sent_bytes (${cloudBytes}). Kiểm tra lại hiện tượng đếm trùng hoặc chồng lấn thời gian.`;
    } else {
        unclassifiedBytes = cloudBytes - totalClientReportedBodyBytes;
        unclassifiedPercent = cloudBytes > 0
            ? Math.round((unclassifiedBytes / cloudBytes) * 1000) / 10
            : 0;
        isAlertTriggered = unclassifiedPercent > alertThresholdUnclassifiedPercent;
        if (isAlertTriggered) {
            status = 'ALERT';
        } else if (hasZeroOrNegativeCoverage || !hasCloudWindow || !hasClientWindows) {
            status = 'UNKNOWN';
            validationError = 'Thiếu thông tin cửa sổ đo lường hoặc tỷ lệ phủ (coveragePercent <= 0). Chưa đủ bằng chứng để xác nhận trạng thái OK.';
        } else {
            status = 'OK';
        }
    }

    return {
        summary: {
            status,
            validationError,
            cloudReportedSentBytes: cloudBytes,
            cloudReportedRequests: cloudStorageMetrics.requestCount || 0,
            clientReportedBodyBytes: totalClientReportedBodyBytes,
            clientReportedRequests: totalClientRequests,
            clientReportedMemoryHits: totalClientMemoryHits,
            unclassifiedExternalBytes: unclassifiedBytes,
            unclassifiedPercent,
            isAlertTriggered,
        },
        cohortBreakdown: Object.values(cohortBreakdown),
        explanation: {
            status,
            causeOfDelta: [
                'GCS Network & HTTP response headers (không nằm trong response body client đo)',
                'Truy cập từ app phiên bản cũ chưa có ImageInstrumentation',
                'Truy cập trực tiếp qua browser hoặc công cụ bên ngoài bằng download token URL',
                'Worker cleanup hoặc backend task quét metadata',
            ],
            actionRequired: status === 'INVALID'
                ? `ERROR: Dữ liệu đối chiếu không hợp lệ: ${validationError}`
                : isAlertTriggered
                    ? 'ALERT: Lưu lượng ngoài app vượt quá ngưỡng cho phép (> 20%). Cần đối chiếu với App Check monitor log trước khi có bất kỳ quyết định nào.'
                    : status === 'UNKNOWN'
                        ? `UNKNOWN: ${validationError}`
                        : 'OK: Lưu lượng nằm trong biên độ cho phép.',
        },
    };
}

// CLI Execution support
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const args = process.argv.slice(2);
    let clientFile = null;
    let cloudFile = null;
    let cloudBytes = 0;
    let cloudReqs = 0;
    let threshold = 20;
    let outFile = null;
    let isDemo = false;

    for (let i = 0; i < args.length; i++) {
        if (args[i] === '--client-file' && args[i + 1]) clientFile = args[++i];
        else if (args[i] === '--cloud-file' && args[i + 1]) cloudFile = args[++i];
        else if (args[i] === '--cloud-bytes' && args[i + 1]) cloudBytes = Number(args[++i]);
        else if (args[i] === '--cloud-requests' && args[i + 1]) cloudReqs = Number(args[++i]);
        else if (args[i] === '--threshold' && args[i + 1]) threshold = Number(args[++i]);
        else if (args[i] === '--out' && args[i + 1]) outFile = args[++i];
        else if (args[i] === '--demo') isDemo = true;
    }

    let clientLogs = [];
    if (clientFile) {
        try {
            const raw = JSON.parse(readFileSync(clientFile, 'utf8'));
            clientLogs = Array.isArray(raw) ? raw : [raw];
        } catch (e) {
            console.error(`Lỗi đọc client-file ${clientFile}: ${e.message}`);
            process.exit(1);
        }
    } else if (isDemo) {
        // Chỉ chạy dữ liệu mẫu khi có cờ --demo tường minh (R2-08: cấm tự chế mẫu khi không có cờ)
        clientLogs = [
            { clientVersion: '2.1.0', cohort: 'canary_10', bodyBytesRead: 45000000, requestCount: 1200, memoryHits: 850 },
            { clientVersion: '2.0.9', cohort: 'legacy', bodyBytesRead: 120000000, requestCount: 3000, memoryHits: 200 },
        ];
        cloudBytes = cloudBytes || 190000000;
        cloudReqs = cloudReqs || 4500;
        console.log('[DEMO MODE: Dữ liệu giả lập để minh họa - không phải số liệu đo lường thực tế]');
    } else {
        console.error('LỖI: Thiếu tham số dữ liệu --client-file <path>. Nếu muốn chạy thử nghiệm giả lập, hãy truyền tường minh cờ --demo.');
        process.exit(1);
    }

    let cloudMetrics = { sentBytesCount: cloudBytes, requestCount: cloudReqs };
    if (cloudFile) {
        try {
            const rawCloud = JSON.parse(readFileSync(cloudFile, 'utf8'));
            cloudMetrics = { ...rawCloud };
            if (rawCloud.sentBytesCount !== undefined) cloudMetrics.sentBytesCount = Number(rawCloud.sentBytesCount);
            if (rawCloud.requestCount !== undefined) cloudMetrics.requestCount = Number(rawCloud.requestCount);
        } catch (e) {
            console.error(`Lỗi đọc cloud-file ${cloudFile}: ${e.message}`);
            process.exit(1);
        }
    }

    const result = reconcileCohortTelemetry({
        clientLogs,
        cloudStorageMetrics: cloudMetrics,
        alertThresholdUnclassifiedPercent: threshold,
    });

    if (isDemo && result.summary) {
        result.summary.mode = 'DEMO_SIMULATION';
    }

    const output = JSON.stringify(result, null, 2);
    if (outFile) {
        writeFileSync(outFile, output, 'utf8');
        console.log(`Đã lưu kết quả đối chiếu vào ${outFile}`);
    } else {
        console.log(output);
    }
}
