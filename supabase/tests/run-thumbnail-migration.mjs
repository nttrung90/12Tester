import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { uuid_ossp } from '@electric-sql/pglite/contrib/uuid_ossp';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (path) => readFileSync(`${root}${path}`, 'utf8').replaceAll('\r\n', '\n');

const fullSchema = read('supabase/full_schema_singapore.sql').split('-- ===== 045_thumbnail_support.sql =====')[0];
const migration045 = read('supabase/migrations/045_thumbnail_support.sql');

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

async function runThumbnailMigrationTests() {
    console.log('--- KHỞI TẠO PGLITE ĐỂ KIỂM THỬ MIGRATION 045 (E02 THUMBNAIL) ---');
    const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

    try {
        await db.exec(bootstrap);
        await db.exec(fullSchema);
        console.log('PASS: Nạp schema baseline');

        await db.exec(migration045);
        console.log('PASS: Áp dụng thành công migration 045_thumbnail_support.sql');

        // Kiểm tra cột thumbnail_url đã xuất hiện
        const colRes = await db.query(`
            select column_name, data_type 
            from information_schema.columns 
            where table_schema = 'public' and table_name = 'tester_reports' and column_name = 'thumbnail_url';
        `);
        assert.equal(colRes.rows.length, 1, 'Cột thumbnail_url phải tồn tại');
        assert.equal(colRes.rows[0].data_type, 'text');
        console.log('PASS: Cột thumbnail_url đã tồn tại trong public.tester_reports');

        const ownerId = '11111111-1111-1111-1111-111111111111';
        const testerId = '22222222-2222-2222-2222-222222222222';
        const campaignId = '33333333-3333-3333-3333-333333333333';

        await db.exec(`
            insert into auth.users (id, email) values ('${ownerId}', 'owner@test.com'), ('${testerId}', 'tester@test.com');
            update public.profiles set display_name = 'Owner', credits = 100 where id = '${ownerId}';
            update public.profiles set display_name = 'Tester', credits = 50 where id = '${testerId}';
            insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number)
            values ('${campaignId}', '${ownerId}', 'Test App', 'com.test.app', 'open', 1);
        `);

        // TEST 1: App cũ gọi 6 tham số (không truyền p_thumbnail_url)
        await db.exec(`set request.jwt.claim.sub = '${testerId}';`);
        const validFull1 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${testerId}%2Ffull1.jpg?alt=media&token=fixture`;
        const legacyCall = await db.query(`
            select public.submit_tester_report(
                '${campaignId}'::uuid,
                'Feedback cũ',
                '${validFull1}',
                5,
                '0123456789abcdef',
                'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid
            ) as res;
        `);
        const legacyRes = legacyCall.rows[0].res;
        assert.equal(legacyRes.success, true);
        assert.equal(legacyRes.thumbnail_url, null, 'Không truyền thumbnail thì thumbnail_url là null');
        console.log('PASS: Legacy 6-argument call tương thích ngược 100%');

        // TEST 2: App mới gọi 7 tham số (có p_thumbnail_url hợp lệ)
        const tester2Id = '44444444-4444-4444-4444-444444444444';
        await db.exec(`
            insert into auth.users (id, email) values ('${tester2Id}', 'tester2@test.com');
            update public.profiles set display_name = 'Tester 2', credits = 50 where id = '${tester2Id}';
        `);
        await db.exec(`set request.jwt.claim.sub = '${tester2Id}';`);
        const validFull2 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${tester2Id}%2Ffull2.jpg?alt=media&token=fixture`;
        const validThumb2 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${tester2Id}%2Fthumb_full2.jpg?alt=media&token=fixture`;
        const newCall = await db.query(`
            select public.submit_tester_report(
                '${campaignId}'::uuid,
                'Feedback có thumbnail',
                '${validFull2}',
                5,
                'fedcba9876543210',
                'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid,
                '${validThumb2}'
            ) as res;
        `);
        const newRes = newCall.rows[0].res;
        assert.equal(newRes.success, true);
        assert.equal(newRes.thumbnail_url, validThumb2);
        console.log('PASS: 7-argument call lưu và trả thumbnail_url chính xác');

        // TEST 3: Idempotency với client_submit_id trả lại đúng thumbnail_url
        const retryCall = await db.query(`
            select public.submit_tester_report(
                '${campaignId}'::uuid,
                'Feedback retry',
                '${validFull2}',
                5,
                'fedcba9876543210',
                'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid,
                '${validThumb2}'
            ) as res;
        `);
        const retryRes = retryCall.rows[0].res;
        assert.equal(retryRes.success, true);
        assert.equal(retryRes.report_id, newRes.report_id);
        assert.equal(retryRes.thumbnail_url, validThumb2);
        console.log('PASS: Idempotency trả lại đúng thumbnail_url đã lưu');

        // TEST 4 (R2-09): Chặn Thumbnail từ Bucket ngoài allowlist
        const badBucketThumb = `https://firebasestorage.googleapis.com/v0/b/attacker-bucket/o/reports%2F${campaignId}%2F${tester2Id}%2Fthumb_full2.jpg`;
        await assert.rejects(
            async () => {
                await db.query(`
                    select public.submit_tester_report(
                        '${campaignId}'::uuid,
                        'Bad bucket thumb',
                        '${validFull2}',
                        5,
                        '1111222233334444',
                        'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid,
                        '${badBucketThumb}'
                    );
                `);
            },
            { message: /INVALID_THUMBNAIL_URL/ }
        );
        console.log('PASS: Chặn thumbnail từ bucket ngoài allowlist');

        // TEST 5 (R2-09): Chặn Query-String Spoofing (domain lạ giả mạo path trong query param)
        const spoofedThumb = `https://attacker.com/evil.jpg?fake=reports%2F${campaignId}%2F${tester2Id}%2Fthumb_full2.jpg`;
        await assert.rejects(
            async () => {
                await db.query(`
                    select public.submit_tester_report(
                        '${campaignId}'::uuid,
                        'Spoofed thumb',
                        '${validFull2}',
                        5,
                        '2222333344445555',
                        'dddddddd-dddd-dddd-dddd-dddddddddddd'::uuid,
                        '${spoofedThumb}'
                    );
                `);
            },
            { message: /INVALID_THUMBNAIL_URL/ }
        );
        console.log('PASS: Chặn query-string spoofing trên URL thumbnail');

        // TEST 6 (R2-09): Chặn mismatch cặp Full - Thumbnail (full2.jpg nhưng truyền thumb_other.jpg)
        const mismatchedThumb = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${tester2Id}%2Fthumb_other_different_id.jpg?alt=media`;
        await assert.rejects(
            async () => {
                await db.query(`
                    select public.submit_tester_report(
                        '${campaignId}'::uuid,
                        'Mismatched pair',
                        '${validFull2}',
                        5,
                        '3333444455556666',
                        'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'::uuid,
                        '${mismatchedThumb}'
                    );
                `);
            },
            { message: /INVALID_THUMBNAIL_URL/ }
        );
        console.log('PASS: Chặn không cho ghép cặp thumbnail sai lệch ID với ảnh full');

        // TEST 7 (R2-09): Chặn campaign hoặc user path không khớp với người nộp
        const otherUserThumb = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaignId}%2F${testerId}%2Fthumb_full2.jpg`;
        await assert.rejects(
            async () => {
                await db.query(`
                    select public.submit_tester_report(
                        '${campaignId}'::uuid,
                        'Wrong user path',
                        '${validFull2}',
                        5,
                        '4444555566667777',
                        'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid,
                        '${otherUserThumb}'
                    );
                `);
            },
            { message: /INVALID_THUMBNAIL_URL/ }
        );
        console.log('PASS: Chặn thumbnail có user_id không khớp với session');

        console.log('=== TẤT CẢ KIỂM THỬ MIGRATION 045 ĐỀU PASS 100% ===');
    } finally {
        await db.close();
    }
}

runThumbnailMigrationTests().catch(err => {
    console.error('FAILED:', err);
    process.exit(1);
});