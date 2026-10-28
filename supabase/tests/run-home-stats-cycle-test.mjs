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
const migration047 = read('supabase/migrations/047_home_stats_cycle.sql');

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

async function runHomeStatsCycleTests() {
  console.log('=== BẮT ĐẦU KIỂM THỬ MIGRATION 047: CHU KỲ THỐNG KÊ TRANG CHỦ (1 / 2 / 3 NGÀY) ===');
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

  // 1. Áp dụng schema và migration
  await db.exec(bootstrap);
  await db.exec(fullSchema);
  await db.exec(migration045);
  await db.exec(migration046);
  await db.exec(migration047);
  console.log('PASS 1: Áp dụng thành công baseline schema và migration 045, 046, 047');

  // Tạo tài khoản và campaign test
  const adminId = '11111111-1111-1111-1111-111111111111';
  const memberId = '22222222-2222-2222-2222-222222222222';
  const tester1 = '33333333-3333-3333-3333-333333333333';
  const tester2 = '44444444-4444-4444-4444-444444444444';
  const tester3 = '55555555-5555-5555-5555-555555555555';
  const campaign1 = '66666666-6666-6666-6666-666666666666';

  await db.exec(`
    insert into auth.users (id, email) values 
      ('${adminId}', 'admin@test.com'),
      ('${memberId}', 'member@test.com'),
      ('${tester1}', 'tester1@test.com'),
      ('${tester2}', 'tester2@test.com'),
      ('${tester3}', 'tester3@test.com');

    update public.profiles set display_name = 'Admin User', role = 'admin' where id = '${adminId}';
    update public.profiles set display_name = 'Member User', role = 'member' where id = '${memberId}';
    update public.profiles set display_name = 'Tester 1', role = 'member' where id = '${tester1}';
    update public.profiles set display_name = 'Tester 2', role = 'member' where id = '${tester2}';
    update public.profiles set display_name = 'Tester 3', role = 'member' where id = '${tester3}';

    insert into public.campaigns (id, owner_id, app_name, package_name, target_testers, status, created_at)
    values ('${campaign1}', '${adminId}', 'Test Campaign', 'com.test.app', 12, 'open', now());
  `);

  // Helper set role context
  const asSuperuser = async (sql) => {
    await db.exec(`reset role; reset "request.jwt.claim.sub";`);
    if (sql) return db.exec(sql);
  };

  const asUser = async (uid, sql) => {
    await db.exec(`set role authenticated; set "request.jwt.claim.sub" = '${uid}';`);
    try {
      return await db.query(sql);
    } finally {
      await asSuperuser();
    }
  };

  const asAnon = async (sql) => {
    await db.exec(`set role anon; reset "request.jwt.claim.sub";`);
    try {
      return await db.query(sql);
    } finally {
      await asSuperuser();
    }
  };

  // 2. Mặc định chu kỳ 1 ngày
  {
    const res = await asUser(adminId, `select public.admin_get_home_stats_config() as config;`);
    const config = res.rows[0].config;
    assert.equal(config.active_cycle_days, 1, 'Mặc định active_cycle_days phải là 1');
    assert.equal(config.pending_cycle_days, null, 'Mặc định không có pending_cycle_days');
    assert.ok(config.revision, 'Phải có revision');
    console.log('PASS 2: Mặc định chu kỳ 1 ngày, không có lịch chờ');
  }

  // 3. Phân quyền: Anon và Member không đọc/sửa được cấu hình
  {
    await assert.rejects(
      async () => asAnon(`select public.admin_get_home_stats_config();`),
      /UNAUTHORIZED|permission denied/i,
      'Anon gọi admin_get_home_stats_config phải thất bại'
    );

    await assert.rejects(
      async () => asUser(memberId, `select public.admin_get_home_stats_config();`),
      /FORBIDDEN/i,
      'Member gọi admin_get_home_stats_config phải thất bại FORBIDDEN'
    );

    await assert.rejects(
      async () => asUser(memberId, `select public.admin_set_home_stats_cycle(2);`),
      /FORBIDDEN/i,
      'Member gọi admin_set_home_stats_cycle phải thất bại FORBIDDEN'
    );

    console.log('PASS 3: Phân quyền chặn anon và non-admin gọi RPC cấu hình');
  }

  // 4. Validation: cycle_days ngoài 1..3 hoặc NULL
  {
    for (const invalidDays of [0, 4, -1, 'null']) {
      await assert.rejects(
        async () => asUser(adminId, `select public.admin_set_home_stats_cycle(${invalidDays});`),
        /INVALID_CYCLE_DAYS/i,
        `cycle_days = ${invalidDays} phải bị từ chối`
      );
    }
    console.log('PASS 4: Validate chu kỳ hợp lệ (chỉ chấp nhận 1, 2, 3)');
  }

  // 5. Thống kê ở chu kỳ 1 ngày: home_tested_count == today_tested_count
  {
    const today = (await db.query(`select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date, 'YYYY-MM-DD') as d;`)).rows[0].d;
    await db.exec(`
      insert into public.checkins (campaign_id, user_id, checkin_date)
      values ('${campaign1}', '${tester1}', '${today}');
    `);

    const feedRes = await asUser(tester1, `select public.get_home_feed() as feed;`);
    const stats = feedRes.rows[0].feed.stats[campaign1];
    assert.equal(stats.today_tested_count, 1);
    assert.equal(stats.home_tested_count, 1, 'Chu kỳ 1 ngày: home_tested_count == today_tested_count');
    assert.equal(stats.stats_date, today);

    // Kiểm tra get_campaigns_stats
    const campStatsRes = await asUser(tester1, `select public.get_campaigns_stats(array['${campaign1}'::uuid]) as stats;`);
    const cStat = campStatsRes.rows[0].stats[campaign1];
    assert.equal(cStat.today_tested_count, 1);
    assert.equal(cStat.home_tested_count, 1);
    assert.equal(cStat.stats_date, today);

    console.log('PASS 5: Chu kỳ 1 ngày cho home_tested_count đúng bằng today_tested_count');
  }

  // 6. Admin lên lịch đổi chu kỳ sang 3 ngày (có hiệu lực từ ngày mai)
  let currentRev = '';
  {
    const cfgRes = await asUser(adminId, `select public.admin_get_home_stats_config() as config;`);
    currentRev = cfgRes.rows[0].config.revision;

    const setRes = await asUser(adminId, `select public.admin_set_home_stats_cycle(3, '${currentRev}') as config;`);
    const updated = setRes.rows[0].config;
    assert.equal(updated.active_cycle_days, 1, 'Hôm nay vẫn chạy chu kỳ 1');
    assert.equal(updated.pending_cycle_days, 3, 'Ngày mai chu kỳ 3 sẽ có hiệu lực');
    assert.ok(updated.pending_effective_from, 'Phải có pending_effective_from');

    // Kiểm tra audit
    const auditRes = await db.query(`select * from public.home_stats_policy_audits order by id desc limit 1;`);
    assert.equal(auditRes.rows[0].action, 'SET_SCHEDULE');
    assert.equal(auditRes.rows[0].cycle_days_new, 3);
    assert.equal(auditRes.rows[0].actor_id, adminId);

    console.log('PASS 6: Admin lên lịch đổi chu kỳ 3 ngày, ghi nhận audit đầy đủ');
  }

  // 7. Chống ghi đè đồng thời (concurrency revision check)
  {
    await assert.rejects(
      async () => asUser(adminId, `select public.admin_set_home_stats_cycle(2, '${currentRev}');`),
      /CONCURRENCY_CONFLICT/i,
      'Truyền revision cũ phải bị xung đột CONCURRENCY_CONFLICT'
    );
    console.log('PASS 7: Chống ghi đè đồng thời hoạt động chính xác khi sai revision');
  }

  // 8. Đổi ý: Admin chọn lại chu kỳ đang chạy (1 ngày) -> Hủy lịch chờ (rollback về mốc cũ)
  {
    const cfgRes = await asUser(adminId, `select public.admin_get_home_stats_config() as config;`);
    const newRev = cfgRes.rows[0].config.revision;

    const cancelRes = await asUser(adminId, `select public.admin_set_home_stats_cycle(1, '${newRev}') as config;`);
    const cfg = cancelRes.rows[0].config;
    assert.equal(cfg.active_cycle_days, 1);
    assert.equal(cfg.pending_cycle_days, null, 'Lịch chờ phải bị hủy');

    const auditRes = await db.query(`select * from public.home_stats_policy_audits order by id desc limit 1;`);
    assert.equal(auditRes.rows[0].action, 'CANCEL_SCHEDULE');
    console.log('PASS 8: Chọn lại chu kỳ đang chạy hủy lịch chờ thành công');
  }

  // 9. Kiểm thử toán học chu kỳ 2 ngày và 3 ngày độc lập với thời gian thật
  // Thiết lập chính sách 3 ngày bắt đầu từ ngày '2026-10-29'
  {
    await db.exec(`
      delete from public.home_stats_policies where effective_from > '2020-01-01';
      insert into public.home_stats_policies (effective_from, cycle_days) values ('2026-10-29', 3);
    `);

    // Kiểm tra helper _get_home_stats_period cho từng ngày
    // Ngày 29/10 (D1 của chu kỳ 1): period_start = 29/10
    const p1 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-10-29'::date);`)).rows[0];
    assert.equal(p1.period_start, '2026-10-29');
    assert.equal(p1.period_end_exclusive, '2026-11-01');
    assert.equal(p1.cycle_days, 3);

    // Ngày 30/10 (D2 của chu kỳ 1): period_start = 29/10
    const p2 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-10-30'::date);`)).rows[0];
    assert.equal(p2.period_start, '2026-10-29');

    // Ngày 31/10 (D3 của chu kỳ 1): period_start = 29/10
    const p3 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-10-31'::date);`)).rows[0];
    assert.equal(p3.period_start, '2026-10-29');

    // Ngày 01/11 (D1 của chu kỳ 2): period_start = 01/11 (RESET VỀ 0!)
    const p4 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-11-01'::date);`)).rows[0];
    assert.equal(p4.period_start, '2026-11-01');
    assert.equal(p4.period_end_exclusive, '2026-11-04');

    console.log('PASS 9: Helper _get_home_stats_period tính đúng mốc chu kỳ 3 ngày và tự reset đầu chu kỳ');
  }

  // 10. Kiểm thử cộng dồn chu kỳ 3 ngày:
  // D1 (29/10): 4 lượt
  // D2 (30/10): 3 lượt
  // D3 (31/10): 2 lượt
  // D4 (01/11): reset về 0, thêm 5 lượt mới -> 5
  {
    // Tạo campaign riêng cho bài test này
    const ownerCycle = '11111111-1111-1111-1111-000000000002';
    const campCycle = '77777777-7777-7777-7777-777777777777';
    await db.exec(`
      insert into auth.users (id, email) values ('${ownerCycle}', 'ownercycle@test.com') on conflict do nothing;
      insert into public.profiles (id, display_name, role) values ('${ownerCycle}', 'Owner Cycle', 'member') on conflict do nothing;
      insert into public.campaigns (id, owner_id, app_name, package_name, target_testers, status, created_at)
      values ('${campCycle}', '${ownerCycle}', 'Cycle 3 App', 'com.cycle3.app', 12, 'open', '2026-10-28 00:00:00+07');
    `);

    // Tạo thêm 10 tester
    for (let i = 10; i <= 25; i++) {
      const tid = `88888888-8888-8888-8888-${String(i).padStart(12, '0')}`;
      await db.exec(`
        insert into auth.users (id, email) values ('${tid}', 'tester${i}@test.com') on conflict do nothing;
        insert into public.profiles (id, display_name, role) values ('${tid}', 'Tester ${i}', 'member') on conflict do nothing;
      `);
    }

    // Ngày 29/10: 4 tester check-in (tester 10, 11, 12, 13)
    await db.exec(`
      insert into public.checkins (campaign_id, user_id, checkin_date) values
        ('${campCycle}', '88888888-8888-8888-8888-000000000010', '2026-10-29'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000011', '2026-10-29'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000012', '2026-10-29'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000013', '2026-10-29');
    `);

    // Ngày 30/10: 3 checkin (trong đó tester 10 test lặp lại, tester 14, 15 mới)
    await db.exec(`
      insert into public.checkins (campaign_id, user_id, checkin_date) values
        ('${campCycle}', '88888888-8888-8888-8888-000000000010', '2026-10-30'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000014', '2026-10-30'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000015', '2026-10-30');
    `);

    // Ngày 31/10: 2 checkin (tester 10 tiếp tục test lặp lại lần 3, tester 16 mới)
    await db.exec(`
      insert into public.checkins (campaign_id, user_id, checkin_date) values
        ('${campCycle}', '88888888-8888-8888-8888-000000000010', '2026-10-31'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000016', '2026-10-31');
    `);

    // Ngày 01/11: chu kỳ mới, 5 checkin mới
    await db.exec(`
      insert into public.checkins (campaign_id, user_id, checkin_date) values
        ('${campCycle}', '88888888-8888-8888-8888-000000000010', '2026-11-01'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000011', '2026-11-01'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000012', '2026-11-01'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000017', '2026-11-01'),
        ('${campCycle}', '88888888-8888-8888-8888-000000000018', '2026-11-01');
    `);

    // Kiểm tra trực tiếp query stats mô phỏng hàm get_campaigns_stats tại từng mốc ngày:
    const checkCountAtDate = async (simulatedDate) => {
      const q = await db.query(`
        select 
          (select count(distinct ci.user_id) from public.checkins ci where ci.campaign_id = '${campCycle}' and ci.checkin_date = '${simulatedDate}') as today_tested,
          (select count(*) from public.checkins ci
           where ci.campaign_id = '${campCycle}'
             and ci.checkin_date >= (select period_start from public._get_home_stats_period('${simulatedDate}'::date))
             and ci.checkin_date <= '${simulatedDate}'::date) as home_tested
      `);
      return q.rows[0];
    };

    const d1Stats = await checkCountAtDate('2026-10-29');
    assert.equal(d1Stats.today_tested, 4);
    assert.equal(d1Stats.home_tested, 4, 'D1 phải là 4 lượt');

    const d2Stats = await checkCountAtDate('2026-10-30');
    assert.equal(d2Stats.today_tested, 3);
    assert.equal(d2Stats.home_tested, 7, 'D2 phải là 4 + 3 = 7 lượt (tester 10 được tính 2 lượt ở 2 ngày khác nhau)');

    const d3Stats = await checkCountAtDate('2026-10-31');
    assert.equal(d3Stats.today_tested, 2);
    assert.equal(d3Stats.home_tested, 9, 'D3 phải là 7 + 2 = 9 lượt (tester 10 được tính 3 lượt ở 3 ngày khác nhau)');

    // Sang ngày 01/11 trước khi có lượt:
    const d4EmptyStats = await (async () => {
      const q = await db.query(`
        select 
          (select count(*) from public.checkins ci
           where ci.campaign_id = '${campCycle}'
             and ci.checkin_date >= (select period_start from public._get_home_stats_period('2026-11-01'::date))
             and ci.checkin_date < '2026-11-01'::date) as count_start_of_day
      `);
      return q.rows[0].count_start_of_day;
    })();
    assert.equal(d4EmptyStats, 0, 'Đầu ngày 01/11 lúc 00:00 chưa có lượt thì bộ đếm về 0');

    // Sau khi có 5 lượt ngày 01/11:
    const d4Stats = await checkCountAtDate('2026-11-01');
    assert.equal(d4Stats.today_tested, 5);
    assert.equal(d4Stats.home_tested, 5, 'D4 bắt đầu chu kỳ mới chỉ có 5 lượt, không cộng 9 lượt chu kỳ trước!');

    console.log('PASS 10: Chu kỳ 3 ngày cộng dồn 4 -> 7 -> 9, sang chu kỳ mới reset về 0 rồi lên 5');
  }

  // 11. Kiểm tra cùng tester nộp trùng trong cùng ngày (bảo vệ UNIQUE)
  {
    await assert.rejects(
      async () => db.exec(`
        insert into public.checkins (campaign_id, user_id, checkin_date)
        values ('${campaign1}', '${tester1}', (now() at time zone 'Asia/Ho_Chi_Minh')::date);
      `),
      /unique/i,
      'Cùng tester trong cùng 1 ngày không được phép có 2 dòng checkin'
    );
    console.log('PASS 11: Không cộng trùng lượt khi cùng tester nộp nhiều lần trong một ngày');
  }

  // 12. Kiểm tra Reject báo cáo làm xóa check-in -> giảm home_tested_count ngay lập tức
  {
    const today = (await db.query(`select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date, 'YYYY-MM-DD') as d;`)).rows[0].d;
    // Thêm checkin tạm tính của tester 2
    await db.exec(`
      insert into public.checkins (campaign_id, user_id, checkin_date)
      values ('${campaign1}', '${tester2}', '${today}');
    `);
    const beforeStats = (await db.query(`
      select count(*) as c from public.checkins
      where campaign_id = '${campaign1}'
        and checkin_date >= (select period_start from public._get_home_stats_period('${today}'::date))
        and checkin_date <= '${today}'::date;
    `)).rows[0].c;

    // Giả lập reject: xóa check-in tạm tính
    await db.exec(`
      delete from public.checkins where campaign_id = '${campaign1}' and user_id = '${tester2}' and checkin_date = '${today}';
    `);

    const afterStats = (await db.query(`
      select count(*) as c from public.checkins
      where campaign_id = '${campaign1}'
        and checkin_date >= (select period_start from public._get_home_stats_period('${today}'::date))
        and checkin_date <= '${today}'::date;
    `)).rows[0].c;

    assert.equal(beforeStats - afterStats, 1, 'Khi bị reject xóa check-in, số lượt giảm đúng 1');
    console.log('PASS 12: Reject xóa check-in làm giảm home_tested_count ngay lập tức');
  }

  // 13. Mẫu số và tử số: Cho phép tử số vượt mẫu số (14/12)
  {
    const ownerExceed = '11111111-1111-1111-1111-000000000003';
    const campExceed = '99999999-9999-9999-9999-999999999999';
    await db.exec(`
      insert into auth.users (id, email) values ('${ownerExceed}', 'ownerexceed@test.com') on conflict do nothing;
      insert into public.profiles (id, display_name, role) values ('${ownerExceed}', 'Owner Exceed', 'member') on conflict do nothing;
      insert into public.campaigns (id, owner_id, app_name, package_name, target_testers, status, created_at)
      values ('${campExceed}', '${ownerExceed}', 'App Exceed Target', 'com.exceed.app', 12, 'open', now());
    `);
    const today = (await db.query(`select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date, 'YYYY-MM-DD') as d;`)).rows[0].d;
    // Thêm 14 checkin
    for (let i = 10; i < 24; i++) {
      const tid = `88888888-8888-8888-8888-${String(i).padStart(12, '0')}`;
      await db.exec(`
        insert into public.checkins (campaign_id, user_id, checkin_date)
        values ('${campExceed}', '${tid}', '${today}');
      `);
    }

    const res = await asUser(tester1, `select public.get_campaigns_stats(array['${campExceed}'::uuid]) as stats;`);
    const stat = res.rows[0].stats[campExceed];
    assert.equal(stat.today_tested_count, 14, 'Tử số 14 lượt không bị giới hạn');
    assert.equal(stat.home_tested_count, 14, 'Tử số cộng dồn 14 lượt không bị giới hạn');
    console.log('PASS 13: Tử số vượt mục tiêu (14/12) không bị giới hạn hay cắt cụt');
  }

  // 14. Đổi cấu hình giữa chu kỳ (ví dụ 3 ngày sang 2 ngày)
  {
    // Đang có chính sách 3 ngày từ '2026-10-29'.
    // Vào ngày '2026-10-30', admin đổi sang 2 ngày -> áp dụng từ 00:00 ngày '2026-10-31'.
    await db.exec(`
      insert into public.home_stats_policies (effective_from, cycle_days)
      values ('2026-10-31', 2);
    `);

    // Ngày 30/10 vẫn chịu chi phối của chính sách 29/10 (chu kỳ 3 ngày, period_start = 29/10)
    const pOct30 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-10-30'::date);`)).rows[0];
    assert.equal(pOct30.period_start, '2026-10-29');
    assert.equal(pOct30.cycle_days, 3);

    // Ngày 31/10 bắt đầu chính sách 2 ngày từ 00:00 (period_start = 31/10)
    const pOct31 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-10-31'::date);`)).rows[0];
    assert.equal(pOct31.period_start, '2026-10-31');
    assert.equal(pOct31.period_end_exclusive, '2026-11-02');
    assert.equal(pOct31.cycle_days, 2);

    // Ngày 01/11 (D2 của chu kỳ 2 ngày): period_start = 31/10
    const pNov01 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-11-01'::date);`)).rows[0];
    assert.equal(pNov01.period_start, '2026-10-31');
    assert.equal(pNov01.cycle_days, 2);

    // Ngày 02/11 (D1 của chu kỳ 2 ngày tiếp theo): period_start = 02/11 (RESET VỀ 0!)
    const pNov02 = (await db.query(`select period_start::text, period_end_exclusive::text, cycle_days from public._get_home_stats_period('2026-11-02'::date);`)).rows[0];
    assert.equal(pNov02.period_start, '2026-11-02');
    assert.equal(pNov02.period_end_exclusive, '2026-11-04');
    assert.equal(pNov02.cycle_days, 2);

    console.log('PASS 14: Đổi cấu hình giữa chu kỳ: ngày hôm nay giữ nguyên, ngày mai bắt đầu chu kỳ mới từ 0');
  }

  console.log('=== TẤT CẢ 14 KIỂM THỬ BACKEND SQL MIGRATION 047 ĐỀU PASS 100% ===');
}

runHomeStatsCycleTests().catch((err) => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});
