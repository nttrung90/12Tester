import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHmac } from 'node:crypto';
import { createMediaGatewayHandler } from './media-gateway-handler.mjs';

const TEST_SECRET = 'crosstest_test_gateway_signing_secret_2026_at_least_32_chars';

function signGrant(reportId, userId, role, variant, expiresEpoch) {
    return createHmac('sha256', TEST_SECRET)
        .update(`${reportId}:${userId}:${role}:${variant}:${expiresEpoch}`)
        .digest('hex');
}

// Giả lập cơ sở dữ liệu và phân quyền
function createMockAuthority() {
    const users = {
        'token-author': { id: 'u_author', role: 'member' },
        'token-owner': { id: 'u_owner', role: 'member' },
        'token-admin': { id: 'u_admin', role: 'admin' },
        'token-stranger': { id: 'u_stranger', role: 'member' },
    };

    const reports = {
        'rep_valid': {
            id: 'rep_valid',
            campaignId: 'camp_1',
            authorId: 'u_author',
            ownerId: 'u_owner',
            isExpired: false,
            fullPath: 'reports/camp_1/u_author/full.jpg',
            thumbPath: 'reports/camp_1/u_author/thumb_full.jpg',
        },
        'rep_expired': {
            id: 'rep_expired',
            campaignId: 'camp_1',
            authorId: 'u_author',
            ownerId: 'u_owner',
            isExpired: true,
            fullPath: 'reports/camp_1/u_author/old.jpg',
            thumbPath: 'reports/camp_1/u_author/thumb_old.jpg',
        },
    };

    return {
        async resolveUserFromToken(token) {
            return users[token] || null;
        },
        async verifyGrant({ reportId, userId, variant, expiresAtEpoch, grantToken }) {
            const report = reports[reportId];
            if (!report) return { isValid: false, message: 'Report not found' };
            if (report.isExpired) return { isValid: false, reason: 'MEDIA_EXPIRED' };

            let role = null;
            if (report.authorId === userId) role = 'author';
            else if (report.ownerId === userId) role = 'owner';
            else if (users[`token-admin`]?.id === userId) role = 'admin';
            else return { isValid: false, message: 'Forbidden' };

            const expectedSig = signGrant(reportId, userId, role, variant, expiresAtEpoch);
            if (expectedSig !== grantToken) {
                return { isValid: false, message: 'HMAC signature mismatch' };
            }

            return {
                isValid: true,
                storagePath: variant === 'thumb' ? report.thumbPath : report.fullPath,
            };
        },
    };
}

function createMockStorage() {
    const files = {
        'reports/camp_1/u_author/full.jpg': Buffer.from('FIXTURE_JPEG_FULL_SCREENSHOT_BYTES'),
        'reports/camp_1/u_author/thumb_full.jpg': Buffer.from('FIXTURE_JPEG_THUMBNAIL_BYTES'),
    };

    return {
        async getObject({ storagePath }) {
            const bytes = files[storagePath];
            if (!bytes) return null;
            return {
                bytes,
                contentType: 'image/jpeg',
            };
        },
    };
}

test('Media Access Gateway Integration Tests (E04 & R2-02)', async (t) => {
    const authority = createMockAuthority();
    const storage = createMockStorage();
    const handler = createMediaGatewayHandler({
        authorityAdapter: authority,
        storageAdapter: storage,
        gatewaySecret: TEST_SECRET,
    });

    const server = http.createServer(handler);
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port;
    const baseUrl = `http://127.0.0.1:${port}/v1/media-gateway`;

    t.after(() => {
        server.close();
    });

    const futureEpoch = Math.floor(Date.now() / 1000) + 900; // +15 phút
    const pastEpoch = Math.floor(Date.now() / 1000) - 60; // -1 phút

    await t.test('1. Bị từ chối 401 khi thiếu Bearer token', async () => {
        const res = await fetch(`${baseUrl}?report_id=rep_valid&grant_token=abc&expires_at=${futureEpoch}`);
        assert.equal(res.status, 401);
        const data = await res.json();
        assert.equal(data.error, 'NOT_AUTHENTICATED');
    });

    await t.test('2. Bị từ chối 400 khi thiếu tham số query hoặc variant sai', async () => {
        const res = await fetch(`${baseUrl}?report_id=rep_valid`, {
            headers: { Authorization: 'Bearer token-author' },
        });
        assert.equal(res.status, 400);
        const data = await res.json();
        assert.equal(data.error, 'BAD_REQUEST');

        const resBadVariant = await fetch(`${baseUrl}?report_id=rep_valid&grant_token=abc&expires_at=${futureEpoch}&variant=raw_admin`, {
            headers: { Authorization: 'Bearer token-author' },
        });
        assert.equal(resBadVariant.status, 400);
    });

    await t.test('3. Bị từ chối 410 khi grant token đã hết hạn', async () => {
        const grant = signGrant('rep_valid', 'u_author', 'author', 'full', pastEpoch);
        const res = await fetch(`${baseUrl}?report_id=rep_valid&variant=full&expires_at=${pastEpoch}&grant_token=${grant}`, {
            headers: { Authorization: 'Bearer token-author' },
        });
        assert.equal(res.status, 410);
        const data = await res.json();
        assert.equal(data.error, 'MEDIA_EXPIRED');
    });

    await t.test('4. Bị từ chối 410 khi báo cáo quá hạn 30 ngày (DB SLA)', async () => {
        const grant = signGrant('rep_expired', 'u_author', 'author', 'full', futureEpoch);
        const res = await fetch(`${baseUrl}?report_id=rep_expired&variant=full&expires_at=${futureEpoch}&grant_token=${grant}`, {
            headers: { Authorization: 'Bearer token-author' },
        });
        assert.equal(res.status, 410);
    });

    await t.test('5. Bị từ chối 403 khi chữ ký HMAC bị sửa đổi (tampered token)', async () => {
        const badGrant = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
        const res = await fetch(`${baseUrl}?report_id=rep_valid&variant=full&expires_at=${futureEpoch}&grant_token=${badGrant}`, {
            headers: { Authorization: 'Bearer token-author' },
        });
        assert.equal(res.status, 403);
        const data = await res.json();
        assert.equal(data.error, 'FORBIDDEN');
    });

    await t.test('6. Bị từ chối 403 khi người lạ dùng trộm token của tác giả (replay/token sharing)', async () => {
        const authorGrant = signGrant('rep_valid', 'u_author', 'author', 'full', futureEpoch);
        // Stranger dùng bearer của mình nhưng gửi kèm grant của tác giả
        const res = await fetch(`${baseUrl}?report_id=rep_valid&variant=full&expires_at=${futureEpoch}&grant_token=${authorGrant}`, {
            headers: { Authorization: 'Bearer token-stranger' },
        });
        assert.equal(res.status, 403);
    });

    await t.test('7. Thành công 200: Tác giả tải ảnh full và nhận bytes trực tiếp (Không redirect 302)', async () => {
        const authorGrant = signGrant('rep_valid', 'u_author', 'author', 'full', futureEpoch);
        const res = await fetch(`${baseUrl}?report_id=rep_valid&variant=full&expires_at=${futureEpoch}&grant_token=${authorGrant}`, {
            headers: { Authorization: 'Bearer token-author' },
            redirect: 'manual', // Đảm bảo không redirect âm thầm
        });

        assert.equal(res.status, 200);
        assert.equal(res.headers.get('location'), null, 'TUYỆT ĐỐI không redirect hoặc để lộ download token');
        assert.equal(res.headers.get('content-type'), 'image/jpeg');
        assert.equal(res.headers.get('cache-control'), 'private, no-store');
        assert.equal(res.headers.get('x-content-type-options'), 'nosniff');

        const buffer = await res.arrayBuffer();
        const text = Buffer.from(buffer).toString();
        assert.equal(text, 'FIXTURE_JPEG_FULL_SCREENSHOT_BYTES');
    });

    await t.test('8. Thành công 200: Tác giả tải ảnh thumbnail', async () => {
        const thumbGrant = signGrant('rep_valid', 'u_author', 'author', 'thumb', futureEpoch);
        const res = await fetch(`${baseUrl}?report_id=rep_valid&variant=thumb&expires_at=${futureEpoch}&grant_token=${thumbGrant}`, {
            headers: { Authorization: 'Bearer token-author' },
            redirect: 'manual',
        });

        assert.equal(res.status, 200);
        const buffer = await res.arrayBuffer();
        assert.equal(Buffer.from(buffer).toString(), 'FIXTURE_JPEG_THUMBNAIL_BYTES');
    });

    await t.test('9. Thành công 200: Campaign Owner tải ảnh báo cáo của tester', async () => {
        const ownerGrant = signGrant('rep_valid', 'u_owner', 'owner', 'full', futureEpoch);
        const res = await fetch(`${baseUrl}?report_id=rep_valid&variant=full&expires_at=${futureEpoch}&grant_token=${ownerGrant}`, {
            headers: { Authorization: 'Bearer token-owner' },
            redirect: 'manual',
        });
        assert.equal(res.status, 200);
    });

    await t.test('10. Thành công 200: System Admin tải ảnh báo cáo', async () => {
        const adminGrant = signGrant('rep_valid', 'u_admin', 'admin', 'full', futureEpoch);
        const res = await fetch(`${baseUrl}?report_id=rep_valid&variant=full&expires_at=${futureEpoch}&grant_token=${adminGrant}`, {
            headers: { Authorization: 'Bearer token-admin' },
            redirect: 'manual',
        });
        assert.equal(res.status, 200);
    });

    await t.test('11. Smoke test: server.mjs bootstrap fail-closed khi thiếu cấu hình môi trường', async () => {
        const { spawnSync } = await import('node:child_process');
        const { fileURLToPath } = await import('node:url');
        const serverPath = fileURLToPath(new URL('./server.mjs', import.meta.url));

        // Thiếu toàn bộ env
        const res1 = spawnSync(process.execPath, [serverPath], { env: {} });
        assert.equal(res1.status, 1, 'Server phải exit 1 khi thiếu env');
        assert.match(res1.stderr.toString(), /MEDIA_GATEWAY_SECRET chưa được cấu hình/);

        // Secret ngắn hơn 32 ký tự
        const res2 = spawnSync(process.execPath, [serverPath], { env: { MEDIA_GATEWAY_SECRET: 'short_secret' } });
        assert.equal(res2.status, 1, 'Server phải exit 1 khi secret ngắn hơn 32 ký tự');

        // Thiếu SUPABASE_URL
        const res3 = spawnSync(process.execPath, [serverPath], {
            env: { MEDIA_GATEWAY_SECRET: 'a_very_long_secret_that_is_at_least_32_characters_long' }
        });
        assert.equal(res3.status, 1, 'Server phải exit 1 khi thiếu SUPABASE_URL');
        assert.match(res3.stderr.toString(), /Thiếu cấu hình SUPABASE_URL/);
    });
});
