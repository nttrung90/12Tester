import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { uuid_ossp } from '@electric-sql/pglite/contrib/uuid_ossp';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = path => readFileSync(`${root}${path}`, 'utf8').replaceAll('\r\n', '\n');

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

async function runRegressionTests() {
  console.log('--- KHỞI TẠO PGLITE ĐỂ KIỂM THỬ REGRESSION 044 (M01-M09 & R01-R07) ---');
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

  try {
    await db.exec(bootstrap);
    const fullSql = read('supabase/full_schema_singapore.sql');
    const marker044 = '-- ===== 044_mission_flow_hardening.sql =====\n';
    const schema043 = fullSql.split(marker044)[0];
    await db.exec(schema043);
    console.log('PASS: Nạp thành công schema baseline 043 (trước 044)');

    const owner = '11111111-1111-1111-1111-111111111111';
    const tester = '22222222-2222-2222-2222-222222222222';
    const camp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    const camp2 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab';
    const dupCamp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaad';
    const dupMissionOld = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
    const dupMissionNew = 'dddddddd-dddd-dddd-dddd-ddddddddddde';

    await db.exec(`
      insert into auth.users(id, email) values
        ('${owner}', 'owner@mission.test'),
        ('${tester}', 'tester@mission.test');
      update public.profiles set credits = 10000, display_name = 'Owner' where id = '${owner}';
      update public.profiles set credits = 100, display_name = 'Tester' where id = '${tester}';
      insert into public.user_slots(user_id, slot_number, expires_at)
      values ('${owner}', 2, now() + interval '30 days'),
             ('${owner}', 4, now() + interval '30 days')
      on conflict do nothing;
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number)
      values
        ('${camp}', '${owner}', 'com.audit.mission', 'Audit App', 'open', 1),
        ('${camp2}', '${owner}', 'com.audit.mission2', 'Audit App 2', 'open', 2),
        ('${dupCamp}', '${owner}', 'com.audit.dup', 'Audit Dup App', 'open', 4);

      -- Tạo 2 mission active trùng lặp trên cùng dupCamp trước 044
      insert into public.missions(id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, ends_at, created_at, status)
      values
        ('${dupMissionOld}', '${owner}', '${dupCamp}', 'Old Dup Mission', 5, 1, 10, now() + interval '1 day', now() - interval '1 hour', 'open'),
        ('${dupMissionNew}', '${owner}', '${dupCamp}', 'New Dup Mission', 5, 1, 10, now() + interval '1 day', now(), 'open');

      -- Mission cũ có tester đang tham gia
      insert into public.mission_participants(mission_id, user_id, joined_at)
      values ('${dupMissionOld}', '${tester}', now() - interval '30 minutes');
    `);

    const query = async sql => (await db.query(sql)).rows;

    // Chạy migration 044 trên schema 043 có dữ liệu trùng thật (R04)
    await db.exec(read('supabase/migrations/044_mission_flow_hardening.sql'));
    console.log('PASS: Áp dụng thành công migration 044_mission_flow_hardening.sql trên nền 043 với dữ liệu trùng');

    // Xác minh hòa giải R04:
    const dupOldStatus = (await query(`select status from public.missions where id='${dupMissionOld}'`))[0].status;
    const dupNewStatus = (await query(`select status from public.missions where id='${dupMissionNew}'`))[0].status;
    const ownerCredits = (await query(`select credits from public.profiles where id='${owner}'`))[0].credits;
    const auditCount = (await query('select count(*)::int as c from public.mission_reconciliation_events'))[0].c;

    assert.equal(dupOldStatus, 'open', 'R04: Mission có tester được giữ nguyên open');
    assert.equal(dupNewStatus, 'expired', 'R04: Mission trùng lặp không có tester chuyển sang expired');
    assert.notEqual(dupNewStatus, 'stopped_no_credits', 'R04: Mission trùng lặp không bị gắn stopped_no_credits');
    assert.equal(ownerCredits, 10000, 'R04: Số dư credit của chủ không bị thay đổi');
    assert.ok(auditCount >= 1, 'R04: Audit reconciliation event được ghi nhận');
    console.log('PASS: R04 - Hòa giải dữ liệu trùng có audit, bảo toàn số dư và giữ mission có tester');

    // Chạy lại migration 044 để kiểm tra tính Idempotency
    await db.exec(read('supabase/migrations/044_mission_flow_hardening.sql'));
    console.log('PASS: Áp dụng lại migration 044 an toàn (idempotency)');

    await db.exec(`select set_config('request.jwt.claim.sub', '${owner}', false);`);
    const createMission = (campId, title) =>
      `select public.create_mission('${campId}', '${title}', 'Description', 5, 1, 10) as result`;

    // =========================================================================
    // REGRESSION M02: Paused mission blocks another mission on same campaign
    // =========================================================================
    console.log('--- Kiểm tra M02 ---');
    const firstRes = (await query(createMission(camp, 'First')))[0].result;
    assert.equal(firstRes.success, true);
    await query(`select public.pause_mission('${firstRes.mission_id}')`);

    // Tạo mission thứ 2 trên cùng campaign khi mission 1 đang pause -> PHẢI BỊ TỪ CHỐI
    await assert.rejects(
      db.query(createMission(camp, 'Second')),
      /MISSION_ALREADY_EXISTS/,
      'M02: Tạo mission thứ 2 trên cùng campaign khi mission 1 đang pause phải bị chặn với MISSION_ALREADY_EXISTS'
    );

    // Nhưng tạo trên campaign khác (camp2) -> PHẢI THÀNH CÔNG
    const camp2Res = (await query(createMission(camp2, 'Camp2 Mission')))[0].result;
    assert.equal(camp2Res.success, true, 'M02: Campaign khác vẫn tạo được bình thường');

    // Resume lại mission 1 -> thành công
    const resumeRes = (await query(`select public.resume_mission('${firstRes.mission_id}') as res`))[0].res;
    assert.equal(resumeRes.success, true);
    assert.equal(resumeRes.status, 'open');

    // Kiểm tra số mission trên camp
    const campMissions = await query(`select id, title, status from public.missions where campaign_id='${camp}'`);
    assert.equal(campMissions.length, 1, 'M02: Chỉ có duy nhất 1 mission tồn tại trên campaign');
    console.log('PASS: M02 - Pause mission chiếm giữ slot, chặn tạo trùng mission trên cùng campaign');

    // =========================================================================
    // REGRESSION M06: Expired open status is auto-expired upon creation without list read
    // =========================================================================
    console.log('--- Kiểm tra M06 ---');
    // Làm cho mission đầu tiên hết hạn nhưng vẫn mang status 'open' trong DB
    await db.exec(`update public.missions set ends_at = now() - interval '2 hours' where id = '${firstRes.mission_id}';`);

    // Tạo mission thay thế trực tiếp mà KHÔNG gọi get_open_missions() hay cron
    const replacementRes = (await query(createMission(camp, 'Replacement')))[0].result;
    assert.equal(replacementRes.success, true, 'M06: Tạo mission mới thành công ngay lần đầu khi mission cũ đã qua ends_at');

    const oldMission = (await query(`select status from public.missions where id = '${firstRes.mission_id}';`))[0];
    assert.equal(oldMission.status, 'expired', 'M06: Mission cũ tự động được chuyển thành expired');
    console.log('PASS: M06 - Tự động đồng bộ mission hết hạn dưới khóa khi tạo mới');

    // =========================================================================
    // REGRESSION M03: Report submitted before joining does NOT earn mission reward
    // =========================================================================
    console.log('--- Kiểm tra M03 ---');
    const activeMissionId = replacementRes.mission_id;
    const oldReportId = '10101010-1010-1010-1010-101010101010';
    const validReportId = '20202020-2020-2020-2020-202020202020';

    // Tester nộp report 1 trước khi tham gia mission
    await db.exec(`
      insert into public.tester_reports(id, campaign_id, user_id, report_type, content, rating, status, created_at, bonus_awarded, provisional_granted_credits)
      values ('${oldReportId}', '${camp}', '${tester}', 'daily_test', 'Old report before join', 5, 'pending', now() - interval '30 minutes', 10, 10);
    `);

    // Tester nhận mission sau khi đã nộp report 1
    await db.exec(`set request.jwt.claim.sub = '${tester}';`);
    await query(`select public.join_mission('${activeMissionId}')`);

    // Chủ app duyệt report 1 (nộp trước khi join)
    await db.exec(`set request.jwt.claim.sub = '${owner}';`);
    const approveOldRes = (await query(`select public.approve_tester_report('${oldReportId}', 0) as res`))[0].res;
    assert.equal(approveOldRes.success, true);

    // Kiểm tra: participant KHÔNG được hoàn thành, tester KHÔNG nhận mission_reward từ report cũ
    const partStatusAfterOld = (await query(`
      select mp.status as participant_status,
        (select coalesce(sum(amount), 0) from public.credit_logs where user_id='${tester}' and action='mission_reward') as mission_reward
      from public.mission_participants mp
      where mp.mission_id='${activeMissionId}' and mp.user_id='${tester}'
    `))[0];
    assert.equal(partStatusAfterOld.participant_status, 'joined', 'M03: Participant vẫn giữ status joined');
    assert.equal(Number(partStatusAfterOld.mission_reward), 0, 'M03: Tester không nhận mission_reward từ report nộp trước khi nhận');

    // Giờ tester nộp report 2 sau khi đã tham gia mission
    await db.exec(`
      insert into public.tester_reports(id, campaign_id, user_id, report_type, content, rating, status, created_at, bonus_awarded, provisional_granted_credits)
      values ('${validReportId}', '${camp}', '${tester}', 'daily_test', 'Valid report after join', 5, 'pending', now(), 10, 10);
    `);

    // Duyệt report 2 hợp lệ
    const approveValidRes = (await query(`select public.approve_tester_report('${validReportId}', 0) as res`))[0].res;
    assert.equal(approveValidRes.success, true);

    const partStatusAfterValid = (await query(`
      select mp.status as participant_status,
        (select coalesce(sum(amount), 0) from public.credit_logs where user_id='${tester}' and action='mission_reward') as mission_reward
      from public.mission_participants mp
      where mp.mission_id='${activeMissionId}' and mp.user_id='${tester}'
    `))[0];
    assert.equal(partStatusAfterValid.participant_status, 'completed', 'M03: Participant chuyển sang completed');
    assert.equal(Number(partStatusAfterValid.mission_reward), 10, 'M03: Tester nhận đúng 10 credit mission reward');
    console.log('PASS: M03 - Báo cáo trước join không được tính thưởng; báo cáo sau join được thưởng đúng 1 lần');

    // =========================================================================
    // REGRESSION M05 & R05: Settlement failure outbox, retry, and anti-starvation
    // =========================================================================
    console.log('--- Kiểm tra M05 & R05 ---');
    // Tạo mission mới trên camp2 và tester join
    await db.exec(`set request.jwt.claim.sub = '${tester}';`);
    await query(`select public.join_mission('${camp2Res.mission_id}')`);

    const failReportId = '30303030-3030-3030-3030-303030303030';
    await db.exec(`
      insert into public.tester_reports(id, campaign_id, user_id, report_type, content, rating, status, created_at, bonus_awarded, provisional_granted_credits)
      values ('${failReportId}', '${camp2}', '${tester}', 'daily_test', 'Report for fail test', 5, 'pending', now(), 10, 10);
    `);

    // Giả lập lỗi trigger khi ghi credit_logs action 'mission_reward'
    await db.exec(`
      create function public.audit_fail_mission_log() returns trigger language plpgsql as $$
      begin
        if new.action = 'mission_reward' then
          raise exception 'AUDIT_SIMULATED_FAILURE';
        end if;
        return new;
      end $$;
      create trigger audit_mission_log before insert on public.credit_logs
      for each row execute function public.audit_fail_mission_log();
    `);

    // Duyệt report khi settlement gặp lỗi kỹ thuật
    await db.exec(`set request.jwt.claim.sub = '${owner}';`);
    const approveFailRes = (await query(`select public.approve_tester_report('${failReportId}', 0) as res`))[0].res;
    assert.equal(approveFailRes.success, true, 'M05: Duyệt report vẫn thành công');

    // Kiểm tra outbox job được ghi nhận trạng thái 'failed'
    const jobAfterFail = (await query(`select status, last_error, attempt_count from public.mission_settlement_jobs where report_id='${failReportId}'`))[0];
    assert.ok(jobAfterFail, 'M05: Outbox job phải tồn tại');
    assert.equal(jobAfterFail.status, 'failed', 'M05: Job status là failed');
    assert.match(jobAfterFail.last_error, /AUDIT_SIMULATED_FAILURE/);

    // Gỡ fault injection trigger
    await db.exec('drop trigger audit_mission_log on public.credit_logs;');

    // Chạy auto_accept_expired_reports() để tự động quét retry
    const retryCount = (await query('select public.auto_accept_expired_reports() as count'))[0].count;
    assert.ok(retryCount >= 1, 'M05: Auto accept / retry phải xử lý được ít nhất 1 failed settlement');

    // Sau khi retry thành công:
    const jobAfterRetry = (await query(`select status from public.mission_settlement_jobs where report_id='${failReportId}'`))[0];
    assert.equal(jobAfterRetry.status, 'completed', 'M05: Job đã được thanh toán completed');

    const partAfterRetry = (await query(`
      select status from public.mission_participants where mission_id='${camp2Res.mission_id}' and user_id='${tester}'
    `))[0];
    assert.equal(partAfterRetry.status, 'completed', 'M05: Participant hoàn thành sau khi retry');

    // Chạy retry lần nữa: không được trả thưởng trùng lặp
    const doubleRetry = (await query('select public.retry_failed_mission_settlements() as count'))[0].count;
    assert.equal(doubleRetry, 0, 'M05: Retry lần hai không thanh toán trùng lặp');
    console.log('PASS: M05 - Settlement failure được lưu vào outbox và retry bền vững, idempotent');

    // =========================================================================
    // R05: Chống starvation retry khi có 50 job retry tạm dừng
    // =========================================================================
    console.log('--- Kiểm tra R05: Anti-starvation với 50 job tạm dừng ---');
    const pausedMissionId = '77777777-7777-7777-7777-777777777777';
    const campStarve = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaae';
    await db.exec(`
      insert into public.user_slots(user_id, slot_number, expires_at)
      values ('${owner}', 5, now() + interval '30 days') on conflict do nothing;
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number)
      values ('${campStarve}', '${owner}', 'com.audit.starve', 'Audit Starve', 'open', 5);
      insert into public.missions(id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, ends_at, status)
      values ('${pausedMissionId}', '${owner}', '${campStarve}', 'Paused Starve Mission', 50, 1, 10, now() + interval '1 day', 'paused_by_creator');

      -- 50 reports & jobs cho paused mission
      insert into auth.users(id, email)
      select ('e0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid, 'starve' || i || '@test.local'
      from generate_series(1, 50) i;

      insert into public.mission_participants(id, mission_id, user_id, status)
      select
        ('f0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        '${pausedMissionId}',
        ('e0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        'joined'
      from generate_series(1, 50) i;

      insert into public.tester_reports(id, campaign_id, user_id, report_type, content, rating, status, created_at)
      select
        ('d0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        '${campStarve}',
        ('e0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        'daily_test', 'Report', 5, 'approved', now() - interval '2 hours'
      from generate_series(1, 50) i;

      insert into public.mission_settlement_jobs(report_id, mission_id, participant_id, campaign_id, tester_id, creator_id, reward_credits, status, attempt_count, next_attempt_at, created_at)
      select
        ('d0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        '${pausedMissionId}',
        ('f0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        '${campStarve}',
        ('e0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        '${owner}', 10, 'failed', 1, now() - interval '10 seconds', now() - (interval '1 hour' + (i * interval '1 second'))
      from generate_series(1, 50) i;
    `);

    // Thêm job thứ 51 cho mission hợp lệ
    const camp51 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaf';
    const mission51 = '88888888-8888-8888-8888-888888888888';
    const rep51 = '99999999-9999-9999-9999-999999999999';
    const part51 = '99999999-9999-9999-9999-999999999998';
    await db.exec(`
      insert into public.user_slots(user_id, slot_number, expires_at)
      values ('${owner}', 6, now() + interval '30 days') on conflict do nothing;
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number)
      values ('${camp51}', '${owner}', 'com.audit.job51', 'Audit Job 51', 'open', 6);
      insert into public.missions(id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, ends_at, status)
      values ('${mission51}', '${owner}', '${camp51}', 'Job 51 Mission', 5, 1, 10, now() + interval '1 day', 'open');
      insert into public.mission_participants(id, mission_id, user_id, status)
      values ('${part51}', '${mission51}', '${tester}', 'joined');
      insert into public.tester_reports(id, campaign_id, user_id, report_type, content, rating, status, created_at)
      values ('${rep51}', '${camp51}', '${tester}', 'daily_test', 'Rep 51', 5, 'approved', now());
      insert into public.mission_settlement_jobs(report_id, mission_id, participant_id, campaign_id, tester_id, creator_id, reward_credits, status, attempt_count, next_attempt_at, created_at)
      values ('${rep51}', '${mission51}', '${part51}', '${camp51}', '${tester}', '${owner}', 10, 'failed', 1, now() - interval '1 second', now());
    `);

    // Lần 1: Quét batch 50 -> 50 job của mission paused bị backoff 5 phút
    const starveBatch1 = (await query('select public.retry_failed_mission_settlements(50) as count'))[0].count;
    assert.equal(starveBatch1, 0, '50 jobs paused bị lùi lịch retry');

    // Lần 2: Quét tiếp -> Job 51 không bị chặn và được thanh toán ngay!
    const starveBatch2 = (await query('select public.retry_failed_mission_settlements(50) as count'))[0].count;
    assert.equal(starveBatch2, 1, 'Job thứ 51 được thanh toán thành công, không bị 50 job trước làm nghẽn');
    console.log('PASS: R05 - Cơ chế backoff ngăn 50 job tạm dừng làm nghẽn batch retry');

    // =========================================================================
    // REGRESSION R03: Quyền auto_accept_expired_reports cho authenticated
    // =========================================================================
    console.log('--- Kiểm tra R03: Quyền RPC auto_accept_expired_reports ---');
    const priv = (await query("select has_function_privilege('authenticated','public.auto_accept_expired_reports()','execute') as allowed"))[0];
    assert.equal(priv.allowed, true, 'R03: authenticated role phải có quyền gọi auto_accept_expired_reports');
    await db.exec('begin; set local role authenticated;');
    const authCallRes = (await query('select public.auto_accept_expired_reports() as c'))[0].c;
    assert.ok(typeof authCallRes === 'number', 'R03: Gọi auto_accept_expired_reports dưới role authenticated thành công');
    await db.exec('rollback;');
    console.log('PASS: R03 - Role authenticated gọi được auto_accept_expired_reports');

    // =========================================================================
    // REGRESSION R06: Cờ suppress notification được reset
    // =========================================================================
    console.log('--- Kiểm tra R06: Reset suppress notification flag ---');
    await db.exec('begin;');
    await query('select public.auto_accept_expired_reports()');
    const suppRow = (await query("select current_setting('app.suppress_report_notify', true) as s"))[0];
    await db.exec('rollback;');
    assert.equal(suppRow.s, '0', 'R06: Cờ suppress_report_notify phải được reset về 0');
    console.log('PASS: R06 - Cờ suppress_report_notify được reset về 0 sau khi chạy');

    // =========================================================================
    // REGRESSION M07: Limit 100 with proper ordering does NOT drop newest item
    // =========================================================================
    console.log('--- Kiểm tra M07 ---');
    // Tạo 101 users và 101 campaigns để mỗi campaign thuộc 1 user khác nhau, mỗi campaign 1 mission
    await db.exec(`
      insert into auth.users(id, email)
      select
        ('c0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        'user' || i || '@test.local'
      from generate_series(1, 101) i;

      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number)
      select
        ('a0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        ('c0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        'com.test.app' || i,
        'Test App ' || i,
        'open',
        1
      from generate_series(1, 101) i;

      insert into public.missions(creator_id, campaign_id, title, max_participants, duration_days, reward_credits, ends_at, created_at)
      select
        ('c0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        ('a0000000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid,
        'M07 Item ' || i,
        5, 1, 10, now() + interval '1 day', now() + (i * interval '1 second')
      from generate_series(1, 101) i;
    `);

    // Đăng nhập với tư cách user 101 (người tạo ra Item 101)
    await db.exec(`select set_config('request.jwt.claim.sub', ('c0000000-0000-0000-0000-' || lpad(to_hex(101), 12, '0'))::text, false);`);

    const missionsList = (await query('select public.get_open_missions() as res'))[0].res.missions;
    assert.equal(missionsList.length, 100, 'M07: Danh sách giới hạn 100 bản ghi');
    const hasNewest = missionsList.some(m => m.title === 'M07 Item 101');
    assert.equal(hasNewest, true, 'M07: Item mới nhất (Item 101) PHẢI nằm trong danh sách');
    assert.equal(missionsList[0].title, 'M07 Item 101', 'M07: Item 101 là item đầu tiên của user 101');
    console.log('PASS: M07 - Sắp xếp trước khi Limit 100 bảo toàn nhiệm vụ mới nhất');

    // =========================================================================
    // REGRESSION RPC get_my_active_mission_campaign_ids
    // =========================================================================
    console.log('--- Kiểm tra get_my_active_mission_campaign_ids ---');
    await db.exec(`set request.jwt.claim.sub = '${owner}';`);
    const activeCampIds = (await query('select public.get_my_active_mission_campaign_ids() as res'))[0].res;
    assert.ok(Array.isArray(activeCampIds));
    assert.ok(activeCampIds.includes(camp));
    console.log('PASS: get_my_active_mission_campaign_ids trả đúng danh sách campaign có mission');

    // =========================================================================
    // REGRESSION 043 Review Point 6: Pause/Resume on expired mission
    // =========================================================================
    console.log('--- Kiểm tra bảo toàn 043 review point 6 ---');
    const camp3 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaac';
    const expMissionId = '55555555-5555-5555-5555-555555555555';
    await db.exec(`
      insert into public.user_slots(user_id, slot_number, expires_at)
      values ('${owner}', 3, now() + interval '30 days')
      on conflict do nothing;

      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number)
      values ('${camp3}', '${owner}', 'com.audit.mission3', 'Audit App 3', 'open', 3);

      insert into public.missions(id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, ends_at, status)
      values ('${expMissionId}', '${owner}', '${camp3}', 'Expired Pause Test', 5, 1, 10, now() - interval '1 hour', 'open');

      select set_config('request.jwt.claim.sub', '${owner}', false);
    `);
    const pauseExpRes = (await query(`select public.pause_mission('${expMissionId}') as res`))[0].res;
    assert.equal(pauseExpRes.success, false);
    assert.equal(pauseExpRes.code, 'MISSION_EXPIRED');

    const expDbStatus = (await query(`select status from public.missions where id='${expMissionId}'`))[0];
    assert.equal(expDbStatus.status, 'expired', '043: Status được lưu là expired vào DB');
    console.log('PASS: Bảo toàn sửa đổi 043 cho pause/resume expired');

    console.log('=== TẤT CẢ CÁC REGRESSION TESTS BACKEND 044 ĐỀU PASS 100% ===');
  } finally {
    await db.close();
  }
}

runRegressionTests().catch(err => {
  console.error('FAIL REGRESSION TEST:', err);
  process.exit(1);
});
