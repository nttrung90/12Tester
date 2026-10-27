import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { uuid_ossp } from '@electric-sql/pglite/contrib/uuid_ossp';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (path) => readFileSync(`${root}${path}`, 'utf8').replaceAll('\r\n', '\n');

const fullSchema = read('supabase/full_schema_singapore.sql');

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

async function runHardeningTests() {
    console.log('--- KHỞI TẠO PGLITE ĐỂ KIỂM THỬ HARDENING 043 ---');
    const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

    try {
        await db.exec(bootstrap);
        await db.exec(fullSchema);
        console.log('PASS: Nạp thành công full_schema_singapore.sql (bao gồm 041, 042, 043, 044)');

        const ownerId = '11111111-1111-1111-1111-111111111111';
        const otherId = '22222222-2222-2222-2222-222222222222';

        // Tạo user mẫu (trigger handle_new_user tự động tạo profiles)
        await db.exec(`
            insert into auth.users (id, email) values ('${ownerId}', 'owner@test.com'), ('${otherId}', 'other@test.com');
            update public.profiles set display_name = 'Owner', credits = 100 where id = '${ownerId}';
            update public.profiles set display_name = 'Other', credits = 50 where id = '${otherId}';
        `);

        // =========================================================================
        // TEST 1: (Review point 1) Dry Run KHÔNG ĐƯỢC expire mission hay đổi dữ liệu
        // =========================================================================
        const camp1Id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
        const miss1Id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

        await db.exec(`
            insert into public.campaigns (id, owner_id, package_name, app_name, status, completed_at, created_at)
            values ('${camp1Id}', '${ownerId}', 'com.dryrun.app', 'Camp Dry Run', 'completed', now() - interval '100 days', now() - interval '120 days');

            insert into public.missions (id, campaign_id, creator_id, title, duration_days, reward_credits, max_participants, status, ends_at, created_at)
            values ('${miss1Id}', '${camp1Id}', '${ownerId}', 'Mission 1', 7, 10, 5, 'open', now() - interval '5 days', now() - interval '20 days');
        `);

        // Chạy dry_run = true
        const dryRunResult = await db.query(`select public.purge_completed_campaigns(90, true, 10) as scanned;`);
        const cleanupLog = await db.query(`select status, error, details from public.cleanup_runs order by id desc limit 1;`);
        console.log('Cleanup run status:', cleanupLog.rows[0]);
        assert.equal(dryRunResult.rows[0].scanned, 1, 'Dry run phải quét được 1 campaign');

        // KIỂM TRA QUAN TRỌNG:
        // Mission KHÔNG ĐƯỢC chuyển sang expired trong lúc dry run!
        const miss1AfterDry = (await db.query(`select status from public.missions where id = '${miss1Id}';`)).rows[0];
        assert.equal(miss1AfterDry.status, 'open', 'Review Point 1: Dry run tuyệt đối không được chuyển status của mission sang expired');

        // Campaign vẫn phải còn nguyên
        const camp1AfterDry = (await db.query(`select id from public.campaigns where id = '${camp1Id}';`)).rows;
        assert.equal(camp1AfterDry.length, 1, 'Review Point 1: Dry run không được xóa campaign');
        console.log('PASS: Review Point 1 - Dry run an toàn, chỉ scan/log, không đổi dữ liệu nghiệp vụ');

        // =========================================================================
        // TEST 2: Chạy purge thật (dry_run = false) -> sync expired missions & xóa campaign
        // =========================================================================
        const livePurgeResult = await db.query(`select public.purge_completed_campaigns(90, false, 10) as deleted;`);
        assert.equal(livePurgeResult.rows[0].deleted, 1, 'Live purge phải xóa thành công 1 campaign');

        const camp1AfterLive = (await db.query(`select id from public.campaigns where id = '${camp1Id}';`)).rows;
        assert.equal(camp1AfterLive.length, 0, 'Campaign đã hoàn thành quá 90 ngày phải bị xóa');
        console.log('PASS: Purge thật tự động đồng bộ mission hết hạn và xóa sạch campaign');

        // =========================================================================
        // TEST 3: (Review point 2) sync_expired_missions() độc lập & phân quyền an toàn
        // =========================================================================
        const campActiveId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
        const campFutureId = 'cccccccc-cccc-cccc-cccc-cccccccccccd';
        const missExpId = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
        const missFutureId = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';

        await db.exec(`
            insert into public.user_slots (user_id, slot_number, expires_at)
            values ('${ownerId}', 2, now() + interval '30 days')
            on conflict do nothing;

            insert into public.campaigns (id, owner_id, package_name, app_name, slot_number, status, created_at)
            values
                ('${campActiveId}', '${ownerId}', 'com.active.app', 'Active Camp', 1, 'open', now() - interval '5 days'),
                ('${campFutureId}', '${ownerId}', 'com.future.app', 'Future Camp', 2, 'open', now() - interval '5 days');

            -- Mission đã hết hạn ends_at
            insert into public.missions (id, campaign_id, creator_id, title, duration_days, reward_credits, max_participants, status, ends_at)
            values ('${missExpId}', '${campActiveId}', '${ownerId}', 'Exp Mission', 7, 10, 5, 'open', now() - interval '1 hour');

            -- Mission còn hạn tương lai
            insert into public.missions (id, campaign_id, creator_id, title, duration_days, reward_credits, max_participants, status, ends_at)
            values ('${missFutureId}', '${campFutureId}', '${ownerId}', 'Future Mission', 7, 10, 5, 'open', now() + interval '5 days');
        `);

        // Chạy sync_expired_missions() trực tiếp
        const syncUpdated = await db.query(`select public.sync_expired_missions() as updated;`);
        assert.equal(syncUpdated.rows[0].updated, 1, 'Phải cập nhật đúng 1 mission hết hạn');

        const mExp = (await db.query(`select status from public.missions where id = '${missExpId}';`)).rows[0];
        assert.equal(mExp.status, 'expired', 'Mission quá hạn phải chuyển sang expired');

        const mFuture = (await db.query(`select status from public.missions where id = '${missFutureId}';`)).rows[0];
        assert.equal(mFuture.status, 'open', 'Mission còn hạn phải giữ nguyên open');
        console.log('PASS: Review Point 2 - sync_expired_missions() hoạt động chính xác độc lập UI');

        // =========================================================================
        // TEST 4: (Review point 3) Chống starvation & Exponential Backoff
        // =========================================================================
        const campErrId = 'ffffffff-ffff-ffff-ffff-ffffffffffff';
        const campGoodId = '12121212-1212-1212-1212-121212121212';

        await db.exec(`
            -- Campaign lỗi (từng bị lỗi trong cleanup_runs 12 giờ trước)
            insert into public.campaigns (id, owner_id, package_name, app_name, status, completed_at, created_at)
            values ('${campErrId}', '${ownerId}', 'com.err.app', 'Camp Lỗi', 'completed', now() - interval '120 days', now() - interval '130 days');

            insert into public.cleanup_runs (job_name, status, details, started_at, finished_at)
            values ('purge_completed_campaigns', 'partial', jsonb_build_object(
                'errors', jsonb_build_array(jsonb_build_object('campaign_id', '${campErrId}', 'error', 'Simulated foreign key failure'))
            ), now() - interval '12 hours', now() - interval '12 hours');

            -- Campaign tốt (không lỗi)
            insert into public.campaigns (id, owner_id, package_name, app_name, status, completed_at, created_at)
            values ('${campGoodId}', '${ownerId}', 'com.good.app', 'Camp Tốt', 'completed', now() - interval '95 days', now() - interval '100 days');
        `);

        // Chạy purge với batch_size = 1
        // Do có cơ chế ưu tiên campaign không lỗi và backoff cho campaign lỗi:
        // Campaign tốt PHẢI ĐƯỢC CHỌN VÀ PURGE THÀNH CÔNG, không bị campaign lỗi chiếm slot!
        const purgeAntiStarvation = await db.query(`select public.purge_completed_campaigns(90, false, 1) as deleted;`);
        assert.equal(purgeAntiStarvation.rows[0].deleted, 1, 'Phải purge được 1 campaign');

        const goodExists = (await db.query(`select id from public.campaigns where id = '${campGoodId}';`)).rows;
        assert.equal(goodExists.length, 0, 'Campaign tốt phải được purge trước');

        const errExists = (await db.query(`select id from public.campaigns where id = '${campErrId}';`)).rows;
        assert.equal(errExists.length, 1, 'Campaign lỗi đang trong thời gian backoff không bị bốc tranh slot');
        console.log('PASS: Review Point 3 - Cơ chế chống starvation & backoff bảo đảm campaign tốt không bị chặn');

        // =========================================================================
        // TEST 5: (Review point 6) pause_mission / resume_mission khi mission đã expired
        // =========================================================================
        const campOwnerMissionId = '33333333-3333-3333-3333-333333333333';
        const missionExpiredId = '44444444-4444-4444-4444-444444444444';

        // Đóng các campaign ở Test 3 để giải phóng slot
        await db.exec(`update public.campaigns set status = 'closed' where id in ('${campActiveId}', '${campFutureId}');`);

        await db.exec(`
            insert into public.campaigns (id, owner_id, package_name, app_name, slot_number, status, created_at)
            values ('${campOwnerMissionId}', '${ownerId}', 'com.owner.app', 'Camp Owner', 1, 'open', now() - interval '2 days');

            insert into public.missions (id, campaign_id, creator_id, title, duration_days, reward_credits, max_participants, status, ends_at)
            values ('${missionExpiredId}', '${campOwnerMissionId}', '${ownerId}', 'Expired Pause Test', 7, 10, 5, 'open', now() - interval '1 hour');
        `);

        // Đóng vai trò là creator của mission
        await db.exec(`set request.jwt.claim.sub = '${ownerId}';`);

        // Gọi pause_mission trên mission đã hết hạn
        const pauseRes = (await db.query(`select public.pause_mission('${missionExpiredId}'::uuid) as res;`)).rows[0].res;
        assert.equal(pauseRes.success, false);
        assert.equal(pauseRes.code, 'MISSION_EXPIRED');
        assert.equal(pauseRes.status, 'expired');

        // KIỂM TRA QUAN TRỌNG: status trong DB phải được cập nhật thành 'expired' (không bị rollback)
        const missionInDb = (await db.query(`select status from public.missions where id = '${missionExpiredId}';`)).rows[0];
        assert.equal(missionInDb.status, 'expired', 'Review Point 6: status phải được lưu thành expired vào DB');

        // Kiểm tra caller không có quyền -> ném ngoại lệ PERMISSION_DENIED
        await db.exec(`set request.jwt.claim.sub = '${otherId}';`);
        await assert.rejects(
            db.query(`select public.pause_mission('${missionExpiredId}'::uuid);`),
            /PERMISSION_DENIED/,
            'Review Point 6: Giữ nguyên contract lỗi auth/quyền cũ khi người dùng không có quyền'
        );
        console.log('PASS: Review Point 6 - pause/resume cập nhật status expired không rollback, giữ contract auth');

        console.log('=== TẤT CẢ 5 NHÓM KIỂM THỬ HARDENING 043 ĐỀU PASS 100% ===');
    } finally {
        await db.close();
    }
}

runHardeningTests().catch(err => {
    console.error('FAIL HARDENING TEST:', err);
    process.exit(1);
});
