import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { uuid_ossp } from '@electric-sql/pglite/contrib/uuid_ossp';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (path) => readFileSync(`${root}${path}`, 'utf8').replaceAll('\r\n', '\n');

const fullSchema = read('supabase/full_schema_singapore.sql');
const migration045 = read('supabase/migrations/045_thumbnail_support.sql');
const migration046 = read('supabase/migrations/046_media_access_gateway.sql');

const bootstrap = `
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create schema auth;
  create table auth.users (
    id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
    email_confirmed_at timestamptz, confirmation_token text,
    email_change text, email_change_token_new text, recovery_token text
  );
  create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
  $$;
  grant usage on schema public, auth to anon, authenticated, service_role;
  grant execute on function auth.uid() to anon, authenticated, service_role;
`;

async function runMediaGatewayTests() {
    console.log('--- KHỞI TẠO PGLITE ĐỂ KIỂM THỬ MIGRATION 046 (E04 MEDIA GATEWAY) ---');
    const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

    try {
        await db.exec(bootstrap);
        await db.exec(fullSchema);
        await db.exec(migration045);
        await db.exec(migration046);
        console.log('PASS: Áp dụng thành công migration 045 và 046');

        const ownerId = '11111111-1111-1111-1111-111111111111';
        const testerId = '22222222-2222-2222-2222-222222222222';
        const adminId = '33333333-3333-3333-3333-333333333333';
        const strangerId = '44444444-4444-4444-4444-444444444444';
        const campaignId = '55555555-5555-5555-5555-555555555555';
        const validReportId = '66666666-6666-6666-6666-666666666666';
        const expiredReportId = '77777777-7777-7777-7777-777777777777';

        await db.exec(`
            insert into auth.users (id, email) values 
                ('${ownerId}', 'owner@test.com'),
                ('${testerId}', 'tester@test.com'),
                ('${adminId}', 'admin@test.com'),
                ('${strangerId}', 'stranger@test.com');
            update public.profiles set display_name = 'Owner' where id = '${ownerId}';
            update public.profiles set display_name = 'Tester' where id = '${testerId}';
            update public.profiles set display_name = 'Admin', role = 'admin' where id = '${adminId}';
            update public.profiles set display_name = 'Stranger', role = 'member' where id = '${strangerId}';

            insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number)
            values ('${campaignId}', '${ownerId}', 'Secure App', 'com.sec.app', 'open', 1);

            -- Báo cáo hợp lệ (1 ngày tuổi)
            insert into public.tester_reports (
                id, campaign_id, user_id, report_type, content,
                screenshot_url, thumbnail_url, rating, status, created_at
            ) values (
                '${validReportId}', '${campaignId}', '${testerId}', 'feedback', 'Good test',
                'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${testerId}%2Ffull_valid.jpg',
                'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${testerId}%2Fthumb_full_valid.jpg',
                5, 'pending', now() - interval '1 day'
            );

            -- Báo cáo quá hạn (31 ngày tuổi)
            insert into public.tester_reports (
                id, campaign_id, user_id, report_type, content,
                screenshot_url, thumbnail_url, rating, status, created_at
            ) values (
                '${expiredReportId}', '${campaignId}', '${testerId}', 'feedback', 'Old test',
                'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${testerId}%2Ffull_old.jpg',
                'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${testerId}%2Fthumb_full_old.jpg',
                5, 'approved', now() - interval '31 days'
            );
        `);

        // TEST 1: Khi chưa cấu hình secret -> Fail closed với MEDIA_GATEWAY_SECRET_NOT_CONFIGURED
        await db.exec(`set request.jwt.claim.sub = '${testerId}';`);
        await assert.rejects(
            async () => {
                await db.query(`select public.get_report_media_access('${validReportId}'::uuid) as res;`);
            },
            { message: /MEDIA_GATEWAY_SECRET_NOT_CONFIGURED/ }
        );
        console.log('PASS: Thiếu cấu hình secret fail-closed với MEDIA_GATEWAY_SECRET_NOT_CONFIGURED');

        // Cấu hình secret cho phiên kiểm thử
        const testSecret = 'crosstest_test_secret_for_suite_2026_at_least_32_chars';
        await db.exec(`set app.settings.media_gateway_secret = '${testSecret}';`);

        // TEST 1b: Khi chưa cấu hình base URL -> Fail closed với MEDIA_GATEWAY_BASE_URL_NOT_CONFIGURED
        await assert.rejects(
            async () => {
                await db.query(`select public.get_report_media_access('${validReportId}'::uuid) as res;`);
            },
            { message: /MEDIA_GATEWAY_BASE_URL_NOT_CONFIGURED/ }
        );
        console.log('PASS: Thiếu cấu hình base URL fail-closed với MEDIA_GATEWAY_BASE_URL_NOT_CONFIGURED');

        // Cấu hình base URL cho phiên kiểm thử
        await db.exec(`set app.settings.media_gateway_base_url = 'https://gateway.fixture.invalid/v1/media-gateway';`);

        // TEST 2: Unauthenticated -> NOT_AUTHENTICATED
        await db.exec(`set request.jwt.claim.sub = '';`);
        await assert.rejects(
            async () => {
                await db.query(`select public.get_report_media_access('${validReportId}'::uuid) as res;`);
            },
            { message: /NOT_AUTHENTICATED/ }
        );
        console.log('PASS: Unauthenticated bị từ chối với NOT_AUTHENTICATED');

        // TEST 3: Author (Tester) truy cập báo cáo của chính mình -> OK
        await db.exec(`set request.jwt.claim.sub = '${testerId}';`);
        const authorCall = await db.query(`select public.get_report_media_access('${validReportId}'::uuid) as res;`);
        const authorRes = authorCall.rows[0].res;
        assert.equal(authorRes.success, true);
        assert.equal(authorRes.role, 'author');
        // R2-01: Không được leak raw_storage_url trong response
        assert.equal(authorRes.raw_storage_url, undefined, 'raw_storage_url không được để lộ cho client');
        assert.ok(authorRes.screenshot_url.includes('variant=full'));
        assert.ok(authorRes.thumbnail_url.includes('variant=thumb'));
        assert.ok(authorRes.screenshot_url.includes('grant_token='));
        assert.ok(authorRes.access_grant_token);

        const fullToken = authorRes.access_grant_token;
        const expiresEpoch = authorRes.expires_at_epoch || Number.parseInt(new URL(authorRes.screenshot_url).searchParams.get('expires_at'), 10);

        // TEST 4: Xác thực chữ ký HMAC với chính danh tác giả (identity binding)
        const verifyCallAuthor = await db.query(`
            select public.verify_media_access_grant(
                '${validReportId}'::uuid,
                '${fullToken}',
                'full',
                ${expiresEpoch}
            ) as is_valid;
        `);
        assert.equal(verifyCallAuthor.rows[0].is_valid, true, 'HMAC token của author phải hợp lệ');
        console.log('PASS: Author được cấp quyền truy cập media với role=author và HMAC hợp lệ');

        // TEST 5: Identity Binding: Người lạ (Stranger) lấy token của Author đem dùng -> REJECTED (false)
        await db.exec(`set request.jwt.claim.sub = '${strangerId}';`);
        const verifyStranger = await db.query(`
            select public.verify_media_access_grant(
                '${validReportId}'::uuid,
                '${fullToken}',
                'full',
                ${expiresEpoch}
            ) as is_valid;
        `);
        assert.equal(verifyStranger.rows[0].is_valid, false, 'Stranger không thể tái sử dụng token của author');
        console.log('PASS: Grant token bị từ chối khi consumer khác người được cấp (identity binding)');

        // TEST 6: Variant Binding: Token cấp cho thumb đem dùng cho full -> REJECTED (false)
        await db.exec(`set request.jwt.claim.sub = '${testerId}';`);
        const thumbUrl = new URL(authorRes.thumbnail_url);
        const thumbToken = thumbUrl.searchParams.get('grant_token');
        const verifyCrossVariant = await db.query(`
            select public.verify_media_access_grant(
                '${validReportId}'::uuid,
                '${thumbToken}',
                'full',
                ${expiresEpoch}
            ) as is_valid;
        `);
        assert.equal(verifyCrossVariant.rows[0].is_valid, false, 'Token thumbnail không được dùng để xem full');
        console.log('PASS: Grant token ràng buộc chặt chẽ với variant (full vs thumb)');

        // TEST 7: Expired Token: Quá thời hạn expires_at -> REJECTED (false)
        const pastEpoch = Math.floor(Date.now() / 1000) - 60; // 1 phút trước
        const verifyPast = await db.query(`
            select public.verify_media_access_grant(
                '${validReportId}'::uuid,
                '${fullToken}',
                'full',
                ${pastEpoch}
            ) as is_valid;
        `);
        assert.equal(verifyPast.rows[0].is_valid, false, 'Token hết hạn phải bị từ chối');
        console.log('PASS: Token hết hạn bị từ chối');

        // TEST 8: Campaign Owner truy cập báo cáo của tester nộp vào app của mình -> OK
        await db.exec(`set request.jwt.claim.sub = '${ownerId}';`);
        const ownerRes = await db.query(`select public.get_report_media_access('${validReportId}'::uuid) as res;`);
        assert.equal(ownerRes.rows[0].res.success, true);
        assert.equal(ownerRes.rows[0].res.role, 'owner');
        console.log('PASS: Campaign Owner được cấp quyền truy cập media với role=owner');

        // TEST 9: Admin hệ thống truy cập -> OK
        await db.exec(`set request.jwt.claim.sub = '${adminId}';`);
        const adminRes = await db.query(`select public.get_report_media_access('${validReportId}'::uuid) as res;`);
        assert.equal(adminRes.rows[0].res.success, true);
        assert.equal(adminRes.rows[0].res.role, 'admin');
        console.log('PASS: Admin hệ thống được cấp quyền truy cập media với role=admin');

        // TEST 10: Stranger (người dùng thứ 3 không liên quan) -> FORBIDDEN (403)
        await db.exec(`set request.jwt.claim.sub = '${strangerId}';`);
        await assert.rejects(
            async () => {
                await db.query(`select public.get_report_media_access('${validReportId}'::uuid) as res;`);
            },
            { message: /FORBIDDEN/ }
        );
        console.log('PASS: Stranger bị từ chối truy cập với lỗi FORBIDDEN');

        // TEST 11: Báo cáo quá hạn 31 ngày -> MEDIA_EXPIRED (410)
        await db.exec(`set request.jwt.claim.sub = '${ownerId}';`);
        await assert.rejects(
            async () => {
                await db.query(`select public.get_report_media_access('${expiredReportId}'::uuid) as res;`);
            },
            { message: /MEDIA_EXPIRED/ }
        );
        console.log('PASS: Báo cáo quá hạn 31 ngày bị từ chối với lỗi MEDIA_EXPIRED');

        console.log('=== TẤT CẢ KIỂM THỬ MIGRATION 046 MEDIA GATEWAY ĐỀU PASS 100% ===');
    } finally {
        await db.close();
    }
}

runMediaGatewayTests().catch(err => {
    console.error('FAILED:', err);
    process.exit(1);
});