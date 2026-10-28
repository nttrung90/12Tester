#!/usr/bin/env node

/**
 * Script kiểm tra tiền điều kiện và mô phỏng thu hồi (Dry-run Revocation)
 * URL Download Token của Firebase Storage (E04).
 *
 * Lưu ý kiến trúc & an toàn (F03):
 * 1. Firebase App Check bảo vệ các API call của SDK, nhưng KHÔNG tự động
 *    thu hồi hoặc vô hiệu hóa các URL download token (`?token=...`) đã phát hành.
 * 2. Để ngăn chặn hoàn toàn việc truy cập trái phép ngoài ứng dụng, các URL cũ
 *    cần được thu hồi metadata download token sau khi toàn bộ client đã chuyển
 *    sang đường dẫn xác thực quyền qua Media Gateway (migration 046).
 * 3. Script này chạy ở chế độ DRY-RUN mặc định để kiểm tra điều kiện an toàn:
 *    - Không chạy nếu bucket name không khớp ALLOWED_BUCKET.
 *    - Không chạy nếu prefix không bắt đầu bằng reports/.
 *    - Không chạy nếu tỷ lệ client app phiên bản mới chưa đạt ngưỡng an toàn (>= 95%).
 *    - Không chạy nếu tỷ lệ lỗi gateway cao (> 1%).
 *    - Đọc dữ liệu inventory/manifest thật khi được cung cấp.
 *    - TUYỆT ĐỐI KHÔNG thực hiện thu hồi trực tiếp trên production.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const ALLOWED_BUCKET = 'cross-testers.firebasestorage.app';
export const ALLOWED_PREFIX = 'reports/';

export async function runTokenRevocationPreflight({
    bucketName = ALLOWED_BUCKET,
    prefix = ALLOWED_PREFIX,
    inventoryFile = null,
    manifestData = null,
    minNewVersionAdoptionPercent = 95,
    currentNewVersionAdoptionPercent = 90,
    gatewayErrorRatePercent = 0.2,
    maxAllowedErrorRatePercent = 1.0,
    dryRun = true,
    logger = console,
} = {}) {
    logger.info(JSON.stringify({
        event: 'PREFLIGHT_START',
        dryRun,
        bucketName,
        prefix,
        minNewVersionAdoptionPercent,
        currentNewVersionAdoptionPercent,
        gatewayErrorRatePercent,
    }));

    // Tiền điều kiện 0: Kiểm tra tính hợp lệ của tham số số học (chống bypass NaN)
    if (
        !Number.isFinite(currentNewVersionAdoptionPercent) ||
        currentNewVersionAdoptionPercent < 0 ||
        currentNewVersionAdoptionPercent > 100
    ) {
        const msg = `INVALID_INPUT: currentNewVersionAdoptionPercent không hợp lệ (${currentNewVersionAdoptionPercent}).`;
        logger.error(JSON.stringify({ event: 'INVALID_INPUT', reason: msg }));
        return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
    }

    if (
        !Number.isFinite(gatewayErrorRatePercent) ||
        gatewayErrorRatePercent < 0 ||
        gatewayErrorRatePercent > 100
    ) {
        const msg = `INVALID_INPUT: gatewayErrorRatePercent không hợp lệ (${gatewayErrorRatePercent}).`;
        logger.error(JSON.stringify({ event: 'INVALID_INPUT', reason: msg }));
        return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
    }

    // Tiền điều kiện 1: Kiểm tra bucket allowlist
    if (bucketName !== ALLOWED_BUCKET) {
        const msg = `SECURITY_VIOLATION: Bucket "${bucketName}" không nằm trong allowlist "${ALLOWED_BUCKET}".`;
        logger.error(JSON.stringify({ event: 'SECURITY_VIOLATION', reason: msg }));
        return {
            safeToRevoke: false,
            reason: msg,
            tokensToRevoke: 0,
            scannedTokensCount: 0,
        };
    }

    // Tiền điều kiện 2: Kiểm tra prefix
    if (!prefix.startsWith(ALLOWED_PREFIX)) {
        const msg = `SECURITY_VIOLATION: Prefix "${prefix}" không hợp lệ, chỉ cho phép thao tác trên prefix "${ALLOWED_PREFIX}".`;
        logger.error(JSON.stringify({ event: 'SECURITY_VIOLATION', reason: msg }));
        return {
            safeToRevoke: false,
            reason: msg,
            tokensToRevoke: 0,
            scannedTokensCount: 0,
        };
    }

    // Tiền điều kiện 3: Độ phủ phiên bản app mới (>= 95%)
    if (currentNewVersionAdoptionPercent < minNewVersionAdoptionPercent) {
        const msg = `PRECONDITION_FAILED: Ty le client phien ban moi (${currentNewVersionAdoptionPercent}%) chua dat nguong toi thieu (${minNewVersionAdoptionPercent}%).`;
        logger.warn(JSON.stringify({ event: 'PRECONDITION_FAILED', reason: msg }));
        return {
            safeToRevoke: false,
            reason: msg,
            tokensToRevoke: 0,
            scannedTokensCount: 0,
        };
    }

    // Tiền điều kiện 4: Tỷ lệ lỗi của Media Gateway (<= 1.0%)
    if (gatewayErrorRatePercent > maxAllowedErrorRatePercent) {
        const msg = `PRECONDITION_FAILED: Ty le loi cua Media Gateway (${gatewayErrorRatePercent}%) vuot qua nguong cho phep (${maxAllowedErrorRatePercent}%).`;
        logger.warn(JSON.stringify({ event: 'PRECONDITION_FAILED', reason: msg }));
        return {
            safeToRevoke: false,
            reason: msg,
            tokensToRevoke: 0,
            scannedTokensCount: 0,
        };
    }

    // Tiền điều kiện 5: Bắt buộc phải có inventory/manifest thật (R2-11: cấm dùng số mặc định giả để trả safeToRevoke)
    if (!inventoryFile && !manifestData) {
        const msg = 'MISSING_INVENTORY: Cần inventory/manifest thật để thực hiện preflight dry-run. Không dùng số giả định.';
        logger.warn(JSON.stringify({ event: 'MISSING_INVENTORY', reason: msg }));
        return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
    }

    // Đọc và thẩm định từng entry trong inventory/manifest thật
    let rawItems = [];
    let manifestGeneratedAt = null;
    try {
        if (inventoryFile) {
            const raw = JSON.parse(readFileSync(inventoryFile, 'utf8'));
            rawItems = Array.isArray(raw) ? raw : (raw.items || []);
            manifestGeneratedAt = raw.generatedAt || null;
        } else if (manifestData) {
            rawItems = Array.isArray(manifestData) ? manifestData : (manifestData.items || []);
            manifestGeneratedAt = manifestData.generatedAt || null;
        }
    } catch (e) {
        const msg = `INVENTORY_READ_ERROR: Không thể đọc dữ liệu manifest: ${e.message}`;
        logger.error(JSON.stringify({ event: 'INVENTORY_READ_ERROR', reason: msg }));
        return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
    }

    if (!Array.isArray(rawItems) || rawItems.length === 0) {
        const msg = 'EMPTY_INVENTORY: Manifest không chứa bất kỳ đối tượng nào.';
        logger.warn(JSON.stringify({ event: 'EMPTY_INVENTORY', reason: msg }));
        return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
    }

    // R2-11: Bắt buộc phải có thông tin thời gian tạo manifest (generatedAt) và không được quá hạn
    if (!manifestGeneratedAt) {
        const msg = 'MISSING_GENERATED_AT: Manifest thiếu thông tin thời gian tạo (generatedAt). Không thể xác minh độ tươi mới.';
        logger.error(JSON.stringify({ event: 'MISSING_GENERATED_AT', reason: msg }));
        return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
    }

    const genTime = new Date(manifestGeneratedAt).getTime();
    const MAX_MANIFEST_AGE_MS = 7 * 24 * 60 * 60 * 1000; // 7 ngày
    if (isNaN(genTime) || (Date.now() - genTime) > MAX_MANIFEST_AGE_MS || genTime > (Date.now() + 3600000)) {
        const msg = `STALE_INVENTORY: Manifest đã quá hạn (tạo lúc ${manifestGeneratedAt}). Không thể dùng làm bằng chứng an toàn.`;
        logger.error(JSON.stringify({ event: 'STALE_INVENTORY', reason: msg }));
        return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
    }

    // R2-11: Kiểm tra tính hợp lệ của từng entry: bucket allowlist, prefix, object name và generation danh định (số nguyên dương)
    for (const item of rawItems) {
        if (!item || typeof item !== 'object') {
            const msg = 'MANIFEST_INTEGRITY_VIOLATION: Entry trong manifest không hợp lệ.';
            logger.error(JSON.stringify({ event: 'MANIFEST_INTEGRITY_VIOLATION', reason: msg }));
            return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
        }
        if (!item.bucket || typeof item.bucket !== 'string' || item.bucket !== ALLOWED_BUCKET) {
            const msg = `MANIFEST_INTEGRITY_VIOLATION: Object thiếu bucket hoặc bucket không khớp allowlist "${ALLOWED_BUCKET}": "${item.bucket}".`;
            logger.error(JSON.stringify({ event: 'MANIFEST_INTEGRITY_VIOLATION', reason: msg }));
            return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
        }
        if (!item.name || typeof item.name !== 'string' || !item.name.startsWith(ALLOWED_PREFIX)) {
            const msg = `MANIFEST_INTEGRITY_VIOLATION: Object thiếu name hoặc không thuộc prefix "${ALLOWED_PREFIX}": "${item.name}".`;
            logger.error(JSON.stringify({ event: 'MANIFEST_INTEGRITY_VIOLATION', reason: msg }));
            return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
        }
        const genStr = String(item.generation ?? '').trim();
        if (!/^[1-9]\d*$/.test(genStr)) {
            const msg = `MANIFEST_INTEGRITY_VIOLATION: Object "${item.name}" có generation "${item.generation}" không phải là định danh số nguyên dương hợp lệ.`;
            logger.error(JSON.stringify({ event: 'MANIFEST_INTEGRITY_VIOLATION', reason: msg }));
            return { safeToRevoke: false, reason: msg, tokensToRevoke: 0, scannedTokensCount: 0 };
        }
    }

    const actualScanned = rawItems.length;
    const actualToRevoke = rawItems.filter(it => it.hasDownloadToken && (it.isExpired || it.mediaExpired)).length;

    logger.info(JSON.stringify({
        event: 'PREFLIGHT_PASSED',
        scannedTokensCount: actualScanned,
        tokensToRevoke: actualToRevoke,
        dryRun: true,
    }));

    return {
        safeToRevoke: true,
        dryRun: true,
        scannedTokensCount: actualScanned,
        tokensToRevoke: actualToRevoke,
        note: 'DRY-RUN THÀNH CÔNG: Không có bất kỳ token URL nào bị thu hồi trên production.',
    };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    runTokenRevocationPreflight({ dryRun: true })
        .then(res => console.log(JSON.stringify(res, null, 2)))
        .catch(err => console.error(err));
}
