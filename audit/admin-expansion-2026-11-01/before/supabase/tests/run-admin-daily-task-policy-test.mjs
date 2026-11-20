import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('./package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');

const read = p => readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8');
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

const adminId = '11111111-1111-1111-1111-111111111111';
const admin2Id = '22222222-2222-2222-2222-222222222222';
const modId = '33333333-3333-3333-3333-333333333333';
const memberId = '44444444-4444-4444-4444-444444444444';
const lockedAdminId = '55555555-5555-5555-5555-555555555555';

async function asUser(uid, fn) {
  await db.exec('begin');
  await db.exec(`set local role authenticated; set local "request.jwt.claim.sub" = '${uid}';`);
  try {
    const res = await fn();
    await db.exec('commit');
    return res;
  } catch (e) {
    await db.exec('rollback');
    throw e;
  }
}

async function asAnon(fn) {
  await db.exec('begin');
  await db.exec(`set local role anon; reset "request.jwt.claim.sub";`);
  try {
    const res = await fn();
    await db.exec('commit');
    return res;
  } catch (e) {
    await db.exec('rollback');
    throw e;
  }
}

try {
  console.log('=== KHỞI TẠO PGLITE VÀ NẠP SCHEMA CHO TEST ADMIN DAILY TASK POLICY ===');
  await db.exec(`
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
  `);

  await db.exec(read('supabase/migrations_archive/pre_baseline_062/full_schema_singapore.sql'));

  // Tạo các tài khoản test
  await db.exec(`
    insert into auth.users (id, email) values
      ('${adminId}', 'admin1@crosstest.local'),
      ('${admin2Id}', 'admin2@crosstest.local'),
      ('${modId}', 'mod@crosstest.local'),
      ('${memberId}', 'member@crosstest.local'),
      ('${lockedAdminId}', 'locked_admin@crosstest.local');

    update public.profiles set display_name = 'Admin Một', role = 'admin', is_locked = false where id = '${adminId}';
    update public.profiles set display_name = 'Admin Hai', role = 'admin', is_locked = false where id = '${admin2Id}';
    update public.profiles set display_name = 'Moderator', role = 'moderator', is_locked = false where id = '${modId}';
    update public.profiles set display_name = 'Thành viên', role = 'member', is_locked = false where id = '${memberId}';
    update public.profiles set display_name = 'Admin Bị Khóa', role = 'admin', is_locked = true where id = '${lockedAdminId}';
  `);
  console.log('PASS: Khởi tạo database và seed dữ liệu thành công.');

  // Test 1: Admin đọc cấu hình ban đầu
  let initialConfig;
  await asUser(adminId, async () => {
    const res = await db.query('select public.admin_get_daily_task_config() as config;');
    initialConfig = res.rows[0].config;
    assert.ok(initialConfig.today, 'Có thông tin today');
    assert.equal(initialConfig.timezone, 'Asia/Ho_Chi_Minh');
    assert.ok(initialConfig.revision, 'Có revision hợp lệ');
    assert.ok(initialConfig.active_policy, 'Có active_policy');
    assert.equal(initialConfig.active_policy.is_baseline, true, 'Row nền ban đầu là baseline');
    assert.equal(initialConfig.active_policy.effective_from, null, 'Ngày baseline chuẩn hóa thành null');
    assert.equal(initialConfig.future_policies.length, 0, 'Chưa có lịch tương lai');
  });
  console.log('PASS 1: Admin đọc cấu hình ban đầu thành công.');

  // Test 2: Quyền bảo vệ - Member, Moderator, Locked Admin, Anon bị từ chối
  await assert.rejects(
    asUser(memberId, async () => {
      await db.query('select public.admin_get_daily_task_config();');
    }),
    /FORBIDDEN/,
    'Member phải bị từ chối truy cập cấu hình'
  );

  await assert.rejects(
    asUser(modId, async () => {
      await db.query('select public.admin_get_daily_task_config();');
    }),
    /FORBIDDEN/,
    'Moderator phải bị từ chối truy cập cấu hình'
  );

  await assert.rejects(
    asUser(lockedAdminId, async () => {
      await db.query('select public.admin_get_daily_task_config();');
    }),
    /FORBIDDEN/,
    'Locked Admin phải bị từ chối'
  );

  await assert.rejects(
    asAnon(async () => {
      await db.query('select public.admin_get_daily_task_config();');
    }),
    /permission denied|UNAUTHORIZED/,
    'Anon phải bị từ chối'
  );
  console.log('PASS 2: Các tài khoản không đủ quyền đều bị chặn gọi RPC.');

  // Test 3: Cấm ghi trực tiếp vào bảng hoặc gọi hàm nội bộ
  await assert.rejects(
    asUser(adminId, async () => {
      await db.query(`insert into public.daily_task_policies (effective_from, tests_per_active_app, operator_note) values ('2030-01-01', 5, 'hacked');`);
    }),
    /permission denied/,
    'Cấm ghi trực tiếp vào daily_task_policies'
  );

  await assert.rejects(
    asUser(adminId, async () => {
      await db.query(`select public.schedule_daily_task_policy(5, date '2030-01-01', 'hacked');`);
    }),
    /permission denied/,
    'Cấm gọi trực tiếp hàm nội bộ schedule_daily_task_policy'
  );
  console.log('PASS 3: Quyền ghi bảng trực tiếp và gọi hàm nội bộ bị từ chối đúng thiết kế.');

  // Test 4: Validation đầu vào
  const tomorrow = (await db.query(`select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1, 'YYYY-MM-DD') as tm;`)).rows[0].tm;
  const todayStr = initialConfig.today;

  await assert.rejects(
    asUser(adminId, async () => {
      await db.query(`select public.admin_schedule_daily_task_policy(0, date '${tomorrow}', 'note', '${initialConfig.revision}');`);
    }),
    /DAILY_TASK_POLICY_INVALID_RATIO/,
    'Tỷ lệ = 0 phải bị từ chối'
  );

  await assert.rejects(
    asUser(adminId, async () => {
      await db.query(`select public.admin_schedule_daily_task_policy(31, date '${tomorrow}', 'note', '${initialConfig.revision}');`);
    }),
    /DAILY_TASK_POLICY_INVALID_RATIO/,
    'Tỷ lệ = 31 phải bị từ chối'
  );

  await assert.rejects(
    asUser(adminId, async () => {
      await db.query(`select public.admin_schedule_daily_task_policy(5, date '${todayStr}', 'note', '${initialConfig.revision}');`);
    }),
    /DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED/,
    'Ngày hôm nay không được phép lên lịch'
  );

  await assert.rejects(
    asUser(adminId, async () => {
      await db.query(`select public.admin_schedule_daily_task_policy(5, date '${tomorrow}', '   ', '${initialConfig.revision}');`);
    }),
    /DAILY_TASK_POLICY_NOTE_REQUIRED/,
    'Ghi chú rỗng phải bị từ chối'
  );
  console.log('PASS 4: Validation các tham số đầu vào hoạt động chính xác.');

  // Test 5: Lên lịch tỷ lệ 6 cho ngày mai thành công
  let scheduleRes;
  await asUser(adminId, async () => {
    const res = await db.query(`
      select public.admin_schedule_daily_task_policy(6, date '${tomorrow}', 'Chuyển sang 6:1 từ ngày mai', '${initialConfig.revision}') as res;
    `);
    scheduleRes = res.rows[0].res;
    assert.equal(scheduleRes.success, true);
    assert.notEqual(scheduleRes.revision, initialConfig.revision, 'Revision phải thay đổi sau khi lưu');
    assert.equal(scheduleRes.config.future_policies.length, 1);
    assert.equal(scheduleRes.config.future_policies[0].tests_per_active_app, 6);
    assert.equal(scheduleRes.config.future_policies[0].effective_from, tomorrow);
  });
  console.log('PASS 5: Admin 1 lên lịch tỷ lệ 6 cho ngày mai thành công.');

  // Test 6: Chống ghi đè đồng thời (Conflict check)
  await assert.rejects(
    asUser(admin2Id, async () => {
      // Admin 2 cố tình gửi revision cũ (initialConfig.revision)
      await db.query(`
        select public.admin_schedule_daily_task_policy(4, date '${tomorrow}', 'Admin 2 ghi đè', '${initialConfig.revision}');
      `);
    }),
    /DAILY_TASK_POLICY_CONFLICT/,
    'Phải phát hiện xung đột revision và từ chối ghi đè'
  );
  console.log('PASS 6: Chống xung đột revision hoạt động chính xác.');

  // Test 7: Cập nhật lịch ngày mai sang tỷ lệ 4 bằng revision mới
  const dayAfterTomorrow = (await db.query(`select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 2, 'YYYY-MM-DD') as dat;`)).rows[0].dat;
  let updateRes;
  await asUser(admin2Id, async () => {
    const res = await db.query(`
      select public.admin_schedule_daily_task_policy(4, date '${tomorrow}', 'Admin 2 điều chỉnh sang 4:1', '${scheduleRes.revision}') as res;
    `);
    updateRes = res.rows[0].res;
    assert.equal(updateRes.success, true);
    assert.equal(updateRes.config.future_policies[0].tests_per_active_app, 4);
  });
  console.log('PASS 7: Cập nhật lại lịch cùng ngày thành công.');

  // Test 8: Thêm một lịch độc lập cho ngày mốt (tỷ lệ 5)
  let schedule2Res;
  await asUser(adminId, async () => {
    const res = await db.query(`
      select public.admin_schedule_daily_task_policy(5, date '${dayAfterTomorrow}', 'Lên lịch 5:1 cho ngày mốt', '${updateRes.revision}') as res;
    `);
    schedule2Res = res.rows[0].res;
    assert.equal(schedule2Res.config.future_policies.length, 2, 'Phải có đúng 2 lịch tương lai');
    assert.equal(schedule2Res.config.future_policies[0].effective_from, tomorrow);
    assert.equal(schedule2Res.config.future_policies[0].tests_per_active_app, 4);
    assert.equal(schedule2Res.config.future_policies[1].effective_from, dayAfterTomorrow);
    assert.equal(schedule2Res.config.future_policies[1].tests_per_active_app, 5);
  });
  console.log('PASS 8: Thêm lịch ngày khác không xóa hoặc ảnh hưởng lịch đã có.');

  // Test 9: Hủy lịch ngày mai -> chỉ ngày mai bị xóa, ngày mốt vẫn giữ nguyên
  let cancelRes;
  await asUser(adminId, async () => {
    const res = await db.query(`
      select public.admin_cancel_daily_task_policy(date '${tomorrow}', 'Hủy lịch ngày mai', '${schedule2Res.revision}') as res;
    `);
    cancelRes = res.rows[0].res;
    assert.equal(cancelRes.success, true);
    assert.equal(cancelRes.config.future_policies.length, 1, 'Còn lại đúng 1 lịch');
    assert.equal(cancelRes.config.future_policies[0].effective_from, dayAfterTomorrow, 'Lịch ngày mốt vẫn còn nguyên');
    assert.equal(cancelRes.config.future_policies[0].tests_per_active_app, 5);
  });
  console.log('PASS 9: Hủy đúng ngày đã chọn, giữ nguyên lịch các ngày khác.');

  // Test 10: Kiểm tra lịch sử Audit log có lưu actor_id và thông tin profile
  await asUser(adminId, async () => {
    const res = await db.query('select public.admin_get_daily_task_policy_history(10) as history;');
    const history = res.rows[0].history;
    assert.ok(history.items.length >= 4, 'Phải có ít nhất 4 bản ghi audit mới');

    // Các bản ghi audit gần nhất:
    // - DELETE của ngày mai (từ cancel)
    // - UPDATE của ngày mai (từ cancel lưu note)
    // - INSERT của ngày mốt
    // - UPDATE của ngày mai (từ admin 2 đổi sang 4)
    // - INSERT của ngày mai (từ admin 1 đổi sang 6)
    const latest = history.items[0];
    assert.equal(latest.operation, 'DELETE');
    assert.equal(latest.actor_id, adminId);
    assert.equal(latest.actor_name, 'Admin Một');

    const updateItem = history.items.find(h => h.operator_note === 'Admin 2 điều chỉnh sang 4:1');
    assert.ok(updateItem, 'Có audit của Admin 2');
    assert.equal(updateItem.actor_id, admin2Id);
    assert.equal(updateItem.actor_name, 'Admin Hai');
  });
  console.log('PASS 10: Audit log ghi nhận chính xác actor_id từ auth.uid() và profile tương ứng.');

  console.log('=== TOÀN BỘ CÁC BƯỚC TEST BACKEND CHO MIGRATION 058 ĐÃ THÀNH CÔNG ===');
} catch (err) {
  console.error('LỖI TEST BACKEND:', err);
  process.exit(1);
}
