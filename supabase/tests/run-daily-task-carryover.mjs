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
const migration046 = read('supabase/migrations/046_media_access_gateway.sql');
const migration047 = read('supabase/migrations/047_home_stats_cycle.sql');
const migration048 = read('supabase/migrations/048_mission_report_and_expiration.sql');
const migration049 = read('supabase/migrations/049_mission_report_hardening.sql');
const migration050 = read('supabase/migrations/050_app_update_reward.sql');
const migration051 = read('supabase/migrations/051_app_update_reward_hardening.sql');
const migration052 = read('supabase/migrations/052_campaign_push_priority.sql');
const migration053 = read('supabase/migrations/053_daily_task_carryover.sql');

const bootstrap = `
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create schema if not exists auth;
  create table if not exists auth.users (
    id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
    email_confirmed_at timestamptz, confirmation_token text,
    email_change text, email_change_token_new text, recovery_token text
  );
  create or replace function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
  $$;
  grant usage on schema public, auth to anon, authenticated, service_role;
  grant execute on function auth.uid() to anon, authenticated, service_role;
`;

async function asUser(db, userId, fn) {
  await db.exec(`set request.jwt.claim.sub = '${userId}'; set role authenticated;`);
  try {
    return await fn();
  } finally {
    await db.exec(`reset request.jwt.claim.sub; set role service_role;`);
  }
}

async function runTests() {
  console.log('=== BẮT ĐẦU KIỂM THỬ BACKEND MIGRATION 053: CỘNG DỒN APP TEST CÒN THIẾU ===');
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

  try {
    // 1. Nạp baseline và các migration tuần tự đến 053
    await db.exec(bootstrap);
    await db.exec(fullSchema);
    await db.exec(migration045);
    await db.exec(migration046);
    await db.exec(migration047);
    await db.exec(migration048);
    await db.exec(migration049);
    await db.exec(migration050);
    await db.exec(migration051);
    await db.exec(migration052);
    await db.exec(migration053);

    // Xác minh tính idempotent khi nạp lại 053
    await db.exec(migration053);
    console.log('PASS 1: Nạp thành công full schema và migrations 045 -> 053 (xác minh idempotent)');

    // 2. Thiết lập users
    const ownerA = '11111111-1111-1111-1111-111111111111'; // Chủ app A
    const ownerB = '22222222-2222-2222-2222-222222222222'; // Chủ app B (cung cấp app để test)
    const memberC = '33333333-3333-3333-3333-333333333333'; // Tester thuần không đăng app
    const adminU = '44444444-4444-4444-4444-444444444444'; // Admin được miễn

    for (const [uid, email, role, credits] of [
      [ownerA, 'ownera@test.com', 'member', 500],
      [ownerB, 'ownerb@test.com', 'member', 500],
      [memberC, 'memberc@test.com', 'member', 200],
      [adminU, 'admin@test.com', 'admin', 1000],
    ]) {
      await db.query(`
        insert into auth.users (id, email) values ($1, $2)
        on conflict (id) do nothing;
      `, [uid, email]);

      await db.query(`
        insert into public.profiles (id, email, display_name, role, credits, daily_missed_streak, created_at)
        values ($1, $2, $2, $3, $4, 0, '2026-10-01 00:00:00+07')
        on conflict (id) do update
        set role = excluded.role, credits = excluded.credits, daily_missed_streak = 0, created_at = excluded.created_at;
      `, [uid, email, role, credits]);
    }

    // 3. Cấu hình chính sách:
    // - Tỷ lệ 3:1 (tests_per_active_app = 3)
    // - Carryover có hiệu lực từ ngày 2026-10-10
    await db.exec(`
      select public.set_daily_task_carryover_policy('2026-10-10'::date, true, 'Test carryover effective from 2026-10-10');
    `);

    // Mở slot 2 cho ownerA để có 2 app open đồng thời
    await db.query(`
      insert into public.user_slots (user_id, slot_number, expires_at)
      values ($1, 2, '2027-01-01 00:00:00+07')
      on conflict (user_id, slot_number) do nothing;
    `, [ownerA]);

    // Tạo 2 app cho ownerA -> cơ bản B = 2 * 3 = 6
    const campA1 = 'a1111111-1111-1111-1111-111111111111';
    const campA2 = 'a2222222-2222-2222-2222-222222222222';
    await db.query(`
      insert into public.campaigns (id, owner_id, app_name, package_name, slot_number, status, created_at)
      values
        ($1, $2, 'App A1', 'com.test.a1', 1, 'open', '2026-10-05 10:00:00+07'),
        ($3, $2, 'App A2', 'com.test.a2', 2, 'open', '2026-10-05 11:00:00+07')
      on conflict (id) do nothing;
    `, [campA1, ownerA, campA2]);

    // Tạo 20 chủ app khác nhau để cung cấp 20 app open (A >= 20)
    const bAppIds = [];
    for (let i = 1; i <= 20; i++) {
      const bOwnerId = `b0000000-0000-0000-0001-${String(i).padStart(12, '0')}`;
      const cid = `b0000000-0000-0000-0000-${String(i).padStart(12, '0')}`;
      bAppIds.push(cid);
      await db.query(`insert into auth.users (id, email) values ($1, $2) on conflict (id) do nothing;`, [bOwnerId, `ownerb_${i}@test.com`]);
      await db.query(`insert into public.profiles (id, email, display_name, role, credits, created_at) values ($1, $2, $2, 'member', 100, '2026-10-01 00:00:00+07') on conflict (id) do nothing;`, [bOwnerId, `ownerb_${i}@test.com`]);
      await db.query(`insert into public.campaigns (id, owner_id, app_name, package_name, slot_number, status, created_at) values ($1, $2, $3, $4, 1, 'open', '2026-10-05 12:00:00+07') on conflict (id) do nothing;`, [cid, bOwnerId, `App B${i}`, `com.test.b${i}`]);
    }

    // =========================================================================
    // CA 1: NGÀY ĐẦU HIỆU LỰC (2026-10-10) - KHÔNG TRUY THU QUÁ KHỨ (C = 0)
    // =========================================================================
    {
      const res = await db.query(`select public.get_daily_task_status($1, '2026-10-10'::date) as s;`, [ownerA]);
      const s = res.rows[0].s;
      assert.equal(s.mode, 'owner');
      assert.equal(s.base_required_tests, 6);
      assert.equal(s.carry_in_tests, 0, 'Ngày đầu carry_in phải bằng 0');
      assert.equal(s.required_tests, 6);
      assert.equal(s.effective_required_tests, 6);
      assert.equal(s.carryover_ready, true);

      // ownerA chỉ test 4 app (bAppIds 0, 1, 2, 3)
      for (let i = 0; i < 4; i++) {
        await db.query(`
          insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
          values ($1, $2, '2026-10-10', '2026-10-10 14:00:00+07');
        `, [ownerA, bAppIds[i]]);
      }

      // Check lại status trước chốt
      const resBefore = await db.query(`select public.get_daily_task_status($1, '2026-10-10'::date) as s;`, [ownerA]);
      assert.equal(resBefore.rows[0].s.completed_tests, 4);
      assert.equal(resBefore.rows[0].s.met, false);
      assert.equal(resBefore.rows[0].s.carryover_remaining_tests, 2, 'Dự kiến thiếu 2');

      // Chốt ngày 2026-10-10
      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-10'::date);`);

      // Kiểm tra bản ghi user_daily_tasks ngày 2026-10-10
      const taskRow = await db.query(`
        select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-10';
      `, [ownerA]);
      assert.equal(taskRow.rows.length, 1);
      assert.equal(taskRow.rows[0].carry_in_tests, 0);
      assert.equal(taskRow.rows[0].carry_out_tests, 2, 'carry_out phải chuyển 2 sang ngày sau');
      assert.equal(taskRow.rows[0].met, false);
      assert.equal(taskRow.rows[0].missed_streak, 1);
      assert.equal(taskRow.rows[0].penalty_credits > 0, true, 'Phải chịu phạt Credit');

      console.log('PASS 2: Ngày đầu hiệu lực C=0; làm 4/6 -> carry_out = 2, phạt Credit và streak tăng');
    }

    // =========================================================================
    // CA 2: NGÀY 2 (2026-10-11) - NHẬN PHẦN THIẾU C=2, TỔNG B+C = 8
    // =========================================================================
    {
      const res = await db.query(`select public.get_daily_task_status($1, '2026-10-11'::date) as s;`, [ownerA]);
      const s = res.rows[0].s;
      assert.equal(s.carryover_ready, true);
      assert.equal(s.base_required_tests, 6);
      assert.equal(s.carry_in_tests, 2, 'carry_in nhận đúng 2 từ hôm qua');
      assert.equal(s.required_tests, 8, 'Tổng nghĩa vụ phải là 8 (6 + 2)');
      assert.equal(s.effective_required_tests, 8);

      // ownerA test 5 app
      for (let i = 0; i < 5; i++) {
        await db.query(`
          insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
          values ($1, $2, '2026-10-11', '2026-10-11 15:00:00+07');
        `, [ownerA, bAppIds[i]]);
      }

      // Chốt ngày 2026-10-11
      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-11'::date);`);

      const taskRow = await db.query(`
        select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-11';
      `, [ownerA]);
      assert.equal(taskRow.rows[0].carry_in_tests, 2);
      assert.equal(taskRow.rows[0].carry_out_tests, 3, 'Làm 5/8 -> còn thiếu 3 chuyển tiếp');
      assert.equal(taskRow.rows[0].missed_streak, 2);

      console.log('PASS 3: Ngày 2 nhận 6+2=8, làm 5 -> carry_out = 3, streak tăng lên 2');
    }

    // =========================================================================
    // CA 3: NGÀY 3 (2026-10-12) - NHẬN C=3, LÀM ĐỦ 9/9 -> PHẦN THIẾU VỀ 0
    // =========================================================================
    {
      const res = await db.query(`select public.get_daily_task_status($1, '2026-10-12'::date) as s;`, [ownerA]);
      const s = res.rows[0].s;
      assert.equal(s.carry_in_tests, 3);
      assert.equal(s.required_tests, 9, '6 + 3 = 9');
      assert.equal(s.effective_required_tests, 9);

      // ownerA test đủ 9 app
      for (let i = 0; i < 9; i++) {
        await db.query(`
          insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
          values ($1, $2, '2026-10-12', '2026-10-12 16:00:00+07');
        `, [ownerA, bAppIds[i]]);
      }

      const resAfter = await db.query(`select public.get_daily_task_status($1, '2026-10-12'::date) as s;`, [ownerA]);
      assert.equal(resAfter.rows[0].s.met, true);
      assert.equal(resAfter.rows[0].s.carryover_remaining_tests, 0);

      // Chốt ngày 2026-10-12
      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-12'::date);`);

      const taskRow = await db.query(`
        select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-12';
      `, [ownerA]);
      assert.equal(taskRow.rows[0].carry_out_tests, 0, 'Đã làm hết -> carry_out = 0');
      assert.equal(taskRow.rows[0].met, true);
      assert.equal(taskRow.rows[0].missed_streak, 0, 'Đạt chỉ tiêu -> reset streak về 0');
      assert.equal(taskRow.rows[0].penalty_credits, 0);

      console.log('PASS 4: Ngày 3 nhận 6+3=9, làm đủ 9/9 -> carry_out = 0, streak reset về 0, không phạt');
    }

    // =========================================================================
    // CA 4: VƯỢT CHỈ TIÊU (TEST 12/6) - KHÔNG TẠO SỐ ÂM HOẶC TÍN DỤNG TƯƠNG LAI
    // =========================================================================
    {
      const res = await db.query(`select public.get_daily_task_status($1, '2026-10-13'::date) as s;`, [ownerA]);
      assert.equal(res.rows[0].s.carry_in_tests, 0);
      assert.equal(res.rows[0].s.required_tests, 6);

      // Test 10 app (vượt 6)
      for (let i = 0; i < 10; i++) {
        await db.query(`
          insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
          values ($1, $2, '2026-10-13', '2026-10-13 14:00:00+07');
        `, [ownerA, bAppIds[i]]);
      }

      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-13'::date);`);

      const taskRow = await db.query(`
        select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-13';
      `, [ownerA]);
      assert.equal(taskRow.rows[0].carry_out_tests, 0, 'Vượt chỉ tiêu không làm carry_out âm');

      // Ngày 2026-10-14 vẫn bắt đầu với B=6, C=0
      const nextDay = await db.query(`select public.get_daily_task_status($1, '2026-10-14'::date) as s;`, [ownerA]);
      assert.equal(nextDay.rows[0].s.carry_in_tests, 0, 'Không chuyển tín dụng sang ngày sau');
      assert.equal(nextDay.rows[0].s.required_tests, 6);

      console.log('PASS 5: Vượt chỉ tiêu không tạo số âm và không chuyển tín dụng sang ngày sau');
    }

    // =========================================================================
    // CA 5: MA TRẬN NGUỒN APP THIẾU (MỤC 3.2 TRONG KẾ HOẠCH)
    // =========================================================================
    {
      // Tạo user mới ownerX để kiểm thử các trường hợp thiếu nguồn app
      const ownerX = '55555555-5555-5555-5555-555555555555';
      await db.query(`insert into auth.users (id, email) values ($1, 'ownerx@test.com') on conflict do nothing;`, [ownerX]);
      await db.query(`
        insert into public.profiles (id, email, display_name, role, credits, daily_missed_streak, created_at)
        values ($1, 'ownerx@test.com', 'Owner X', 'member', 500, 0, '2026-10-01 00:00:00+07')
        on conflict (id) do update set created_at = excluded.created_at;
      `, [ownerX]);

      // Mở slot 2 cho ownerX
      await db.query(`
        insert into public.user_slots (user_id, slot_number, expires_at)
        values ($1, 2, '2027-01-01 00:00:00+07')
        on conflict (user_id, slot_number) do nothing;
      `, [ownerX]);

      // 2 app -> B = 6
      await db.query(`
        insert into public.campaigns (id, owner_id, app_name, package_name, slot_number, status, created_at)
        values
          ('e1111111-1111-1111-1111-111111111111', $1, 'App X1', 'com.test.x1', 1, 'open', '2026-10-05 10:00:00+07'),
          ('e2222222-2222-2222-2222-222222222222', $1, 'App X2', 'com.test.x2', 2, 'open', '2026-10-05 11:00:00+07');
      `, [ownerX]);

      // Tạm dừng app của ownerA để chỉ tính nguồn app của B
      await db.query(`update public.campaigns set status = 'paused' where owner_id = $1;`, [ownerA]);

      // Tạm dừng 15 app của B để chỉ còn lại đúng 5 app open của người khác -> A = 5
      for (let i = 5; i < 20; i++) {
        await db.query(`update public.campaigns set status = 'paused' where id = $1;`, [bAppIds[i]]);
      }

      // Giả lập ngày hôm trước (2026-10-19) ownerX chốt thiếu carry_out = 2
      await db.query(`
        insert into public.user_daily_tasks (
          user_id, task_date, mode, active_app_count, required_tests,
          effective_required_tests, available_apps, completed_tests,
          logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
          carryover_version, base_required_tests, carry_in_tests, carry_out_tests, carryover_effective_from
        ) values (
          $1, '2026-10-19', 'owner', 2, 6, 6, 20, 4,
          true, false, false, 1, 5, '2026-10-20 00:05:00+07',
          1, 6, 0, 2, '2026-10-10'
        );
      `, [ownerX]);

      // B=6, C=2, A=5 -> R=8, T=min(8, 5)=5. Q=max(2, 5)=5.
      // D=5 -> carry_out = max(0, 5 - 5) = 0, met = true
      // (Ví dụ dòng 2 bảng 3.2: 6, 2, 5 -> T=5, D=5 -> carry_out=0)
      for (let i = 0; i < 5; i++) {
        await db.query(`
          insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
          values ($1, $2, '2026-10-20', '2026-10-20 10:00:00+07');
        `, [ownerX, bAppIds[i]]);
      }

      const resX1 = await db.query(`select public.get_daily_task_status($1, '2026-10-20'::date) as s;`, [ownerX]);
      const sx1 = resX1.rows[0].s;
      assert.equal(sx1.base_required_tests, 6);
      assert.equal(sx1.carry_in_tests, 2);
      assert.equal(sx1.available_apps, 5);
      assert.equal(sx1.effective_required_tests, 5, 'T phải giới hạn bởi A=5');
      assert.equal(sx1.completed_tests, 5);
      assert.equal(sx1.met, true);
      assert.equal(sx1.carryover_remaining_tests, 0);

      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-20'::date);`);
      const rowX1 = await db.query(`select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-20';`, [ownerX]);
      assert.equal(rowX1.rows[0].carry_out_tests, 0);
      assert.equal(rowX1.rows[0].penalty_credits, 0);

      // Bây giờ kiểm tra ca: B=6, C=8, A=5, D=5 -> T=5, Q=max(8,5)=8 -> carry_out = max(0, 8-5)=3!
      // (Ví dụ dòng 3 bảng 3.2)
      await db.query(`
        insert into public.user_daily_tasks (
          user_id, task_date, mode, active_app_count, required_tests,
          effective_required_tests, available_apps, completed_tests,
          logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
          carryover_version, base_required_tests, carry_in_tests, carry_out_tests, carryover_effective_from
        ) values (
          $1, '2026-10-21', 'owner', 2, 14, 5, 5, 0,
          true, false, false, 2, 10, '2026-10-22 00:05:00+07',
          1, 6, 8, 8, '2026-10-10'
        );
      `, [ownerX]);

      // Ngày 2026-10-22: C=8, A=5. Test đủ 5 app
      for (let i = 0; i < 5; i++) {
        await db.query(`
          insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
          values ($1, $2, '2026-10-22', '2026-10-22 10:00:00+07');
        `, [ownerX, bAppIds[i]]);
      }

      const resX2 = await db.query(`select public.get_daily_task_status($1, '2026-10-22'::date) as s;`, [ownerX]);
      const sx2 = resX2.rows[0].s;
      assert.equal(sx2.effective_required_tests, 5);
      assert.equal(sx2.met, true, 'Test đủ 5/5 app khả dụng -> met = true');
      assert.equal(sx2.carryover_remaining_tests, 3, 'Trả được 5 trong 8 phần thiếu cũ; giữ lại 3');

      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-22'::date);`);
      const rowX2 = await db.query(`select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-22';`, [ownerX]);
      assert.equal(rowX2.rows[0].carry_out_tests, 3);
      assert.equal(rowX2.rows[0].met, true);
      assert.equal(rowX2.rows[0].penalty_credits, 0, 'Đạt chỉ tiêu thực tế nên không phạt');

      // Bây giờ kiểm tra ca: B=6, C=8, A=0, D=0 -> T=0, Q=8 -> carry_out = 8, met = true, KHÔNG PHẠT, KHÔNG TĂNG STREAK
      // (Ví dụ dòng 4 bảng 3.2)
      // Tạm dừng hết tất cả app của B -> A = 0
      for (let i = 0; i < 5; i++) {
        await db.query(`update public.campaigns set status = 'paused' where id = $1;`, [bAppIds[i]]);
      }

      // Giả lập ngày 2026-10-23 có C=8
      await db.query(`
        insert into public.user_daily_tasks (
          user_id, task_date, mode, active_app_count, required_tests,
          effective_required_tests, available_apps, completed_tests,
          logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
          carryover_version, base_required_tests, carry_in_tests, carry_out_tests, carryover_effective_from
        ) values (
          $1, '2026-10-23', 'owner', 2, 14, 5, 5, 0,
          true, false, false, 0, 0, '2026-10-24 00:05:00+07',
          1, 6, 8, 8, '2026-10-10'
        );
      `, [ownerX]);

      const resX3 = await db.query(`select public.get_daily_task_status($1, '2026-10-24'::date) as s;`, [ownerX]);
      const sx3 = resX3.rows[0].s;
      assert.equal(sx3.available_apps, 0);
      assert.equal(sx3.effective_required_tests, 0, 'T = 0 do không có app');
      assert.equal(sx3.met, true, 'T = 0 -> met = true');
      assert.equal(sx3.carryover_remaining_tests, 8, 'Phần thiếu cũ 8 tiếp tục được giữ nguyên');

      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-24'::date);`);
      const rowX3 = await db.query(`select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-24';`, [ownerX]);
      assert.equal(rowX3.rows[0].carry_out_tests, 8);
      assert.equal(rowX3.rows[0].penalty_credits, 0, 'T=0 tuyệt đối không phạt');
      assert.equal(rowX3.rows[0].missed_streak, 0, 'T=0 không tăng streak');

      // Mở lại các app của B và A
      for (let i = 0; i < 20; i++) {
        await db.query(`update public.campaigns set status = 'open' where id = $1;`, [bAppIds[i]]);
      }
      await db.query(`update public.campaigns set status = 'open' where owner_id = $1;`, [ownerA]);

      console.log('PASS 6: Ma trận nguồn app thiếu: Q=max(C, T), carry_out=max(0, Q-D), T=0 không phạt và giữ nguyên phần thiếu');
    }

    // =========================================================================
    // CA 6: TESTER KHÔNG ĐĂNG APP (MEMBER) - ĐĂNG NHẬP HOÀN THÀNH, TEST GIẢM NỢ
    // =========================================================================
    {
      // memberC chưa đăng app bao giờ
      const resM1 = await db.query(`select public.get_daily_task_status($1, '2026-10-15'::date) as s;`, [memberC]);
      const sm1 = resM1.rows[0].s;
      assert.equal(sm1.mode, 'member');
      assert.equal(sm1.required_tests, 0);
      assert.equal(sm1.effective_required_tests, 0);
      assert.equal(sm1.carry_in_tests, 0);
      assert.equal(sm1.carryover_remaining_tests, 0);

      // Ghi nhận đăng nhập cho memberC
      await db.query(`update public.profiles set last_login_date = '2026-10-15' where id = $1;`, [memberC]);
      const resM1Login = await db.query(`select public.get_daily_task_status($1, '2026-10-15'::date) as s;`, [memberC]);
      assert.equal(resM1Login.rows[0].s.met, true, 'Member chỉ cần login là met=true');

      // Bây giờ giả lập: memberC trước đây từng là owner và còn phần thiếu carry_out = 4
      await db.query(`
        insert into public.user_daily_tasks (
          user_id, task_date, mode, active_app_count, required_tests,
          effective_required_tests, available_apps, completed_tests,
          logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
          carryover_version, base_required_tests, carry_in_tests, carry_out_tests, carryover_effective_from
        ) values (
          $1, '2026-10-15', 'owner', 1, 6, 6, 20, 2,
          true, false, false, 1, 5, '2026-10-16 00:05:00+07',
          1, 3, 3, 4, '2026-10-10'
        ) on conflict (user_id, task_date) do update set carry_out_tests = 4, finalized_at = '2026-10-16 00:05:00+07';
      `, [memberC]);

      // Sang ngày 2026-10-16: memberC không có active app (mode = member)
      await db.query(`update public.profiles set last_login_date = '2026-10-16' where id = $1;`, [memberC]);
      const resM2 = await db.query(`select public.get_daily_task_status($1, '2026-10-16'::date) as s;`, [memberC]);
      const sm2 = resM2.rows[0].s;
      assert.equal(sm2.mode, 'member');
      assert.equal(sm2.required_tests, 0, 'Member không bị ép chỉ tiêu');
      assert.equal(sm2.carry_in_tests, 4, 'Phần thiếu cũ 4 được giữ lại');
      assert.equal(sm2.met, true, 'Login vẫn làm met = true');

      // Member test tự nguyện 1 app -> carry_out giảm từ 4 còn 3
      await db.query(`
        insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
        values ($1, $2, '2026-10-16', '2026-10-16 11:00:00+07');
      `, [memberC, bAppIds[0]]);

      const resM2Tested = await db.query(`select public.get_daily_task_status($1, '2026-10-16'::date) as s;`, [memberC]);
      assert.equal(resM2Tested.rows[0].s.completed_tests, 1);
      assert.equal(resM2Tested.rows[0].s.carryover_remaining_tests, 3, 'Test tự nguyện giảm phần thiếu xuống 3');

      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-16'::date);`);
      const rowM2 = await db.query(`select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-16';`, [memberC]);
      assert.equal(rowM2.rows[0].carry_out_tests, 3);
      assert.equal(rowM2.rows[0].penalty_credits, 0, 'Member không bị phạt vì chưa trả hết phần thiếu cũ');
      assert.equal(rowM2.rows[0].met, true);

      console.log('PASS 7: Member chỉ cần đăng nhập là hoàn thành; phần thiếu cũ được tạm giữ và giảm khi test tự nguyện');
    }

    // =========================================================================
    // CA 7: TRẠNG THÁI 00:00 - 00:05: HÔM QUA CHƯA FINALIZED -> CARRYOVER_READY = FALSE
    // =========================================================================
    {
      // Ngày 2026-10-25 chưa finalize
      // Kiểm tra ngày 2026-10-26: ownerA gọi get_daily_task_status
      const resPending = await db.query(`select public.get_daily_task_status($1, '2026-10-26'::date) as s;`, [ownerA]);
      const sp = resPending.rows[0].s;
      assert.equal(sp.carryover_ready, false, 'Hôm qua chưa chốt -> carryover_ready phải là false');
      assert.equal(sp.met, false, 'Khi chưa ready, owner tuyệt đối không kết luận met=true');

      // Test thử trong lúc pending: vẫn tính vào completed_tests
      await db.query(`
        insert into public.checkins (user_id, campaign_id, checkin_date, created_at)
        values ($1, $2, '2026-10-26', '2026-10-26 00:02:00+07');
      `, [ownerA, bAppIds[0]]);

      const resAfterCheckin = await db.query(`select public.get_daily_task_status($1, '2026-10-26'::date) as s;`, [ownerA]);
      assert.equal(resAfterCheckin.rows[0].s.completed_tests, 1, 'Lượt test sau 00:00 vẫn tính cho hôm nay');
      assert.equal(resAfterCheckin.rows[0].s.carryover_ready, false);

      // Chốt ngày 2026-10-25
      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-25'::date);`);

      // Sau khi 2026-10-25 chốt xong -> 2026-10-26 lập tức ready!
      const resReady = await db.query(`select public.get_daily_task_status($1, '2026-10-26'::date) as s;`, [ownerA]);
      assert.equal(resReady.rows[0].s.carryover_ready, true, 'Sau khi chốt hôm qua, hôm nay chuyển sang ready=true');
      assert.equal(resReady.rows[0].s.completed_tests, 1, 'Lượt test lúc pending được giữ nguyên');

      console.log('PASS 8: 00:00 - 00:05 chưa chốt -> carryover_ready = false, test vẫn đếm, sau khi chốt chuyển ready');
    }

    // =========================================================================
    // CA 8: IDEMPOTENCY & CHỐNG CHẠY TRÙNG FINALIZER
    // =========================================================================
    {
      const balanceBefore = (await db.query(`select credits from public.profiles where id = $1;`, [ownerA])).rows[0].credits;

      // Chạy finalize ngày 2026-10-26 lần 1
      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-26'::date);`);
      const balanceAfter1 = (await db.query(`select credits from public.profiles where id = $1;`, [ownerA])).rows[0].credits;
      const task1 = (await db.query(`select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-26';`, [ownerA])).rows[0];

      // Chạy finalize ngày 2026-10-26 lần 2 (retry)
      await db.query(`select public.finalize_daily_tasks_for_date('2026-10-26'::date);`);
      const balanceAfter2 = (await db.query(`select credits from public.profiles where id = $1;`, [ownerA])).rows[0].credits;
      const task2 = (await db.query(`select * from public.user_daily_tasks where user_id = $1 and task_date = '2026-10-26';`, [ownerA])).rows[0];

      assert.equal(balanceAfter1, balanceAfter2, 'Retry finalizer không được trừ Credit lần hai');
      assert.equal(task1.carry_out_tests, task2.carry_out_tests, 'Retry không thay đổi phần thiếu');
      assert.equal(task1.penalty_credits, task2.penalty_credits);

      console.log('PASS 9: Idempotency: retry finalizer không nhân đôi phạt hay nhân đôi phần thiếu');
    }

    // =========================================================================
    // CA 9: TƯƠNG THÍCH VỚI TÍNH NĂNG ĐẨY APP (CAMPAIGN PUSH PRIORITY)
    // =========================================================================
    {
      // 1. Mua đẩy không ảnh hưởng đến chỉ tiêu hoặc phần thiếu của chủ app
      const reqId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
      const balanceBeforePush = (await db.query(`select credits from public.profiles where id = $1;`, [ownerA])).rows[0].credits;

      // ownerA mua đẩy cho campA1
      const pushRes = await asUser(db, ownerA, async () => {
        return (await db.query(`select public.push_campaign($1, $2) as r;`, [campA1, reqId])).rows[0].r;
      });
      assert.equal(pushRes.success, true);
      assert.equal(pushRes.charged_credits, 100);

      const balanceAfterPush = (await db.query(`select credits from public.profiles where id = $1;`, [ownerA])).rows[0].credits;
      assert.equal(balanceBeforePush - balanceAfterPush, 100, 'Mua đẩy trừ đúng 100 Credit');

      // Kiểm tra trạng thái nhiệm vụ ngày của ownerA: không đổi B, C, D hay T vì mua đẩy
      const taskStatusAfterPush = (await db.query(`select public.get_daily_task_status($1, '2026-10-27'::date) as s;`, [ownerA])).rows[0].s;
      assert.equal(taskStatusAfterPush.base_required_tests, 6);
      assert.equal(taskStatusAfterPush.completed_tests, 0);

      // 2. Mua đẩy không giúp hoàn thành nhiệm vụ và không reset streak
      const profileAfterPush = (await db.query(`select daily_missed_streak from public.profiles where id = $1;`, [ownerA])).rows[0];
      assert.equal(profileAfterPush.daily_missed_streak > 0, true, 'Mua đẩy không reset streak');

      // 3. App bị paused (ví dụ do owner inactive) không được mua đẩy
      await db.query(`update public.campaigns set status = 'paused', paused_reason = 'owner_inactive' where id = $1;`, [campA2]);
      await assert.rejects(
        asUser(db, ownerA, async () => {
          await db.query(`select public.push_campaign($1, $2);`, [campA2, 'dddddddd-dddd-dddd-dddd-dddddddddddd']);
        }),
        (err) => err.message.includes('CAMPAIGN_NOT_OPEN'),
        'App đang paused không được mua đẩy'
      );

      console.log('PASS 10: Tương thích Đẩy app: Mua đẩy trừ 100 Credit, không đổi chỉ tiêu test, không reset streak');
    }

    console.log('=== TẤT CẢ 10 KIỂM THỬ BACKEND MIGRATION 053 CỘNG DỒN ĐỀU PASS 100% ===');
  } catch (err) {
    console.error('FAILED TEST RUNNER 053:', err);
    process.exit(1);
  }
}

runTests();
