/**
 * Backend Media Access Gateway Handler (E04).
 *
 * Chức năng:
 * - Bảo vệ screenshot báo cáo không cho truy cập tự do qua URL vĩnh viễn.
 * - Xác thực phiên người dùng qua Bearer JWT header.
 * - Kiểm tra chữ ký số HMAC-SHA256 gắn với định danh user, report ID, variant và expiration.
 * - Kiểm tra quyền thời gian thực (author, owner, admin) và SLA 30 ngày qua Authority Adapter.
 * - Trực tiếp proxy/stream ảnh về client, TUYỆT ĐỐI KHÔNG redirect 302 lộ download token.
 */

import { createHmac } from 'node:crypto';

export const ALLOWED_VARIANTS = new Set(['full', 'thumb']);

export function createMediaGatewayHandler({
    authorityAdapter,
    storageAdapter,
    gatewaySecret,
    logger = console,
}) {
    if (!gatewaySecret || typeof gatewaySecret !== 'string') {
        throw new Error('GATEWAY_SECRET_REQUIRED: Media Gateway requires a non-empty gatewaySecret.');
    }

    return async function handleMediaRequest(req, res) {
        // Chỉ chấp nhận GET và HEAD
        if (req.method !== 'GET' && req.method !== 'HEAD') {
            res.writeHead(405, { 'Content-Type': 'application/json', 'Allow': 'GET, HEAD' });
            res.end(JSON.stringify({ error: 'METHOD_NOT_ALLOWED' }));
            return;
        }

        const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
        if (url.pathname !== '/v1/media-gateway') {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'NOT_FOUND' }));
            return;
        }

        // 1. Xác thực Bearer JWT Header
        const authHeader = req.headers.authorization || req.headers.Authorization;
        if (!authHeader || !authHeader.startsWith('Bearer ')) {
            res.writeHead(401, {
                'Content-Type': 'application/json',
                'WWW-Authenticate': 'Bearer error="invalid_token", error_description="Missing or invalid Bearer token"',
            });
            res.end(JSON.stringify({ error: 'NOT_AUTHENTICATED', message: 'Yêu cầu Bearer JWT token hợp lệ.' }));
            return;
        }

        const token = authHeader.slice(7).trim();
        let user;
        try {
            user = await authorityAdapter.resolveUserFromToken(token);
            if (!user || !user.id) {
                res.writeHead(401, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'NOT_AUTHENTICATED', message: 'Token không hợp lệ hoặc đã hết hạn.' }));
                return;
            }
        } catch (e) {
            res.writeHead(401, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'NOT_AUTHENTICATED', message: e.message }));
            return;
        }

        // 2. Parse & kiểm tra tham số request
        const reportId = url.searchParams.get('report_id');
        const variant = url.searchParams.get('variant') || 'full';
        const expiresAtRaw = url.searchParams.get('expires_at');
        const grantToken = url.searchParams.get('grant_token');

        if (!reportId || !grantToken || !expiresAtRaw) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'BAD_REQUEST', message: 'Thiếu tham số bắt buộc: report_id, grant_token, expires_at.' }));
            return;
        }

        if (!ALLOWED_VARIANTS.has(variant)) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'BAD_REQUEST', message: 'Variant không hợp lệ, chỉ chấp nhận full hoặc thumb.' }));
            return;
        }

        const expiresAtEpoch = Number.parseInt(expiresAtRaw, 10);
        if (Number.isNaN(expiresAtEpoch) || !Number.isFinite(expiresAtEpoch)) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'BAD_REQUEST', message: 'expires_at phải là số nguyên epoch seconds.' }));
            return;
        }

        const nowEpoch = Math.floor(Date.now() / 1000);
        if (nowEpoch > expiresAtEpoch) {
            res.writeHead(410, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'MEDIA_EXPIRED', message: 'Grant token đã hết hạn truy cập.' }));
            return;
        }

        // 3. Xác thực quyền và tính hợp lệ của Grant qua Authority Adapter
        try {
            const verification = await authorityAdapter.verifyGrant({
                reportId,
                userId: user.id,
                variant,
                expiresAtEpoch,
                grantToken,
                gatewaySecret,
            });

            if (!verification.isValid) {
                if (verification.reason === 'MEDIA_EXPIRED') {
                    res.writeHead(410, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ error: 'MEDIA_EXPIRED', message: 'Ảnh báo cáo đã quá hạn SLA 30 ngày.' }));
                    return;
                }
                res.writeHead(403, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'FORBIDDEN', message: verification.message || 'Không có quyền truy cập media này.' }));
                return;
            }

            // 4. Lấy nội dung từ Storage Adapter và truyền trực tiếp về client (No Redirect)
            const objectData = await storageAdapter.getObject({
                reportId,
                variant,
                storagePath: verification.storagePath,
            });

            if (!objectData || !objectData.bytes) {
                res.writeHead(404, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'MEDIA_NOT_FOUND', message: 'Không tìm thấy tập tin media trên storage.' }));
                return;
            }

            // R2-02: Phục vụ bytes trực tiếp, không đặt Location header, cấm lưu cache lâu
            res.writeHead(200, {
                'Content-Type': objectData.contentType || 'image/jpeg',
                'Content-Length': objectData.bytes.length,
                'Cache-Control': 'private, no-store',
                'X-Content-Type-Options': 'nosniff',
            });

            if (req.method === 'HEAD') {
                res.end();
            } else {
                res.end(objectData.bytes);
            }
        } catch (err) {
            logger.error(`Media gateway internal error: ${err.message}`);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'INTERNAL_ERROR' }));
        }
    };
}
