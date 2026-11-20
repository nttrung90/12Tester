import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('./package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');

const read = p => readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8');

const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const scalar = async sql => (await db.query(sql)).rows[0];

const ownerA = '11111111-1111-1111-1111-111111111111';
const ownerB = '22222222-2222-2222-2222-222222222222';
const stranger = '33333333-3333-3333-3333-333333333333';
const lockedUser = '44444444-4444-4444-4444-444444444444';

const campA = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const campB = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const campClosed = 'cccccccc-cccc-cccc-cccc-cccccccccccc';

async function runTest(name, fn) {
  await db.exec('begin');
  try {
    await fn();
    console.log(`PASS: ${name}`);
  } catch (e) {
    console.error(`FAIL: ${name}`, e);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

try {
  console.log('=== KHỞI TẠO PGLITE VÀ NẠP FULL SCHEMA SINGAPORE HIỆN TẠI ===');
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users(
      id uuid primary key,
      email text,
      raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz,
      confirmation_token text,
      email_change text,
      email_change_token_new text,
      recovery_token text
    );
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    grant usage on schema public, auth to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
  `);

  await db.exec(read('supabase/migrations_archive/pre_baseline_062/full_schema_singapore.sql'));

  // Seed fixture data
  await db.exec(`
    insert into auth.users(id, email) values
      ('${ownerA}', 'ownerA@test.local'),
      ('${ownerB}', 'ownerB@test.local'),
      ('${stranger}', 'stranger@test.local'),
      ('${lockedUser}', 'locked@test.local');

    insert into public.profiles(id, email, display_name, credits, role, is_locked, created_at) values
      ('${ownerA}', 'ownerA@test.local', 'Owner A', 483, 'member', false, now() - interval '10 days'),
      ('${ownerB}', 'ownerB@test.local', 'Owner B', 200, 'member', false, now() - interval '10 days'),
      ('${stranger}', 'stranger@test.local', 'Stranger', 500, 'member', false, now() - interval '10 days'),
      ('${lockedUser}', 'locked@test.local', 'Locked', 500, 'member', true, now() - interval '10 days')
    on conflict(id) do update set credits = excluded.credits, is_locked = excluded.is_locked;

    insert into public.campaigns(id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at) values
      ('${campA}', '${ownerA}', 'App A', 'com.test.app.a', 'open', 12, 1, now() - interval '3 days'),
      ('${campB}', '${ownerB}', 'App B', 'com.test.app.b', 'open', 12, 1, now() - interval '3 days'),
      ('${campClosed}', '${ownerA}', 'App Closed', 'com.test.app.closed', 'closed', 12, 1, now() - interval '3 days');

    grant select, update, insert on public.campaigns to authenticated;
    grant select, update on public.profiles to authenticated;
  `);

  console.log('=== GIAI ĐOẠN 1: TÁI HIỆN LỖI TRÊN BASELINE HIỆN TẠI (MIGRATION 054) ===');
  // Thiết lập trigger theo đúng định nghĩa cũ của migration 054 để tái hiện lỗi
  await db.exec(`
    create or replace function public.trg_fn_protect_campaign_push_metadata()
    returns trigger
    language plpgsql
    set search_path = public
    as $$
    declare
        v_active_role text := coalesce(nullif(current_setting('role', true), ''), current_user);
    begin
        if (tg_op = 'INSERT' and (new.push_business_date is not null or new.push_expires_at is not null or new.push_order is not null))
           or (tg_op = 'UPDATE' and (new.push_business_date is distinct from old.push_business_date
                or new.push_expires_at is distinct from old.push_expires_at
                or new.push_order is distinct from old.push_order)) then
            if v_active_role in ('authenticated', 'anon', 'public') or current_user in ('authenticated', 'anon', 'public') or session_user in ('anon') then
                raise exception 'PUSH_METADATA_READ_ONLY: Client khong duoc phep thay doi metadata day app truc tiep';
            end if;
            if current_setting('app.allow_push_metadata_write', true) is distinct from 'true' then
                raise exception 'PUSH_METADATA_READ_ONLY: Metadata day app chi duoc cap nhat qua RPC push_campaign';
            end if;
        end if;
        return new;
    end;
    $$;
  `);

  const reproReq = '00000000-0000-0000-0000-000000000001';
  await runTest('Tái hiện: Authenticated role gọi RPC push_campaign bị PUSH_METADATA_READ_ONLY', async () => {
    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);

    let error = null;
    await db.exec('savepoint sp_repro');
    try {
      await scalar(`select public.push_campaign('${campA}', '${reproReq}') res`);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint sp_repro');
    } finally {
      await db.exec(`reset role; set request.jwt.claim.sub = '';`);
    }

    assert.ok(error != null, 'Phải có lỗi ném ra');
    assert.match(error.message, /PUSH_METADATA_READ_ONLY/, 'Thông báo lỗi phải là PUSH_METADATA_READ_ONLY');
    console.log(`  -> Đã tái hiện thành công lỗi: ${error.message}`);
  });

  console.log('=== GIAI ĐOẠN 2: ÁP DỤNG BẢN SỬA (MIGRATION 056) ===');
  // Đọc migration 056
  const migration056 = read('supabase/migrations_archive/pre_baseline_062/056_push_campaign_role_hardening.sql');
  await db.exec(migration056);
  console.log('  -> Đã nạp thành công migration 056_push_campaign_role_hardening.sql');

  console.log('=== GIAI ĐOẠN 3: KIỂM CHỨNG TOÀN DIỆN BẢN SỬA ===');

  // Test 1: Authenticated user với đúng 100 Credit mua hợp lệ thành công -> còn 0 Credit
  await runTest('Test 1: Authenticated role + đúng 100 Credit mua hợp lệ thành công (credits 100 -> 0)', async () => {
    await db.exec(`update public.profiles set credits = 100 where id = '${ownerA}'`);
    const req1 = '11111111-aaaa-1111-aaaa-111111111111';

    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);
    const res = await scalar(`select public.push_campaign('${campA}', '${req1}') res`);
    await db.exec(`reset role; set request.jwt.claim.sub = '';`);

    assert.equal(res.res.success, true);
    assert.equal(res.res.replayed, false);
    assert.equal(res.res.charged_credits, 100);
    assert.equal(res.res.balance_after, 0);

    const profile = await scalar(`select credits from public.profiles where id = '${ownerA}'`);
    assert.equal(profile.credits, 0, 'Credits sau mua phải bằng 0');

    const log = await scalar(`select amount, action, balance from public.credit_logs where user_id = '${ownerA}' and action = 'campaign_push'`);
    assert.equal(log.amount, -100);
    assert.equal(log.balance, 0);

    const receipt = await scalar(`select client_request_id, cost_credits, balance_after from public.campaign_push_receipts where client_request_id = '${req1}'`);
    assert.equal(receipt.cost_credits, 100);
    assert.equal(receipt.balance_after, 0);

    const camp = await scalar(`select push_order, push_business_date, push_expires_at from public.campaigns where id = '${campA}'`);
    assert.ok(camp.push_order != null, 'push_order phải được cập nhật');
    assert.ok(camp.push_business_date != null, 'push_business_date phải được cập nhật');
    assert.ok(camp.push_expires_at != null, 'push_expires_at phải được cập nhật');
  });

  // Test 2: Authenticated user với 483 Credit (kịch bản người dùng thật trong ảnh) -> còn 383 Credit
  await runTest('Test 2: Authenticated role + 483 Credit gọi RPC thành công (credits 483 -> 383, không còn lỗi)', async () => {
    await db.exec(`update public.profiles set credits = 483 where id = '${ownerA}'`);
    const req2 = '22222222-aaaa-2222-aaaa-222222222222';

    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);
    const res = await scalar(`select public.push_campaign('${campA}', '${req2}') res`);
    await db.exec(`reset role; set request.jwt.claim.sub = '';`);

    assert.equal(res.res.success, true);
    assert.equal(res.res.replayed, false);
    assert.equal(res.res.charged_credits, 100);
    assert.equal(res.res.balance_after, 383);

    const profile = await scalar(`select credits from public.profiles where id = '${ownerA}'`);
    assert.equal(profile.credits, 383, 'Credits sau mua phải là 383');
  });

  // Test 3: Authenticated ghi metadata trực tiếp qua UPDATE bị chặn
  await runTest('Test 3: Authenticated ghi trực tiếp metadata qua UPDATE bị chặn PUSH_METADATA_READ_ONLY', async () => {
    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);

    let error = null;
    await db.exec('savepoint sp_upd');
    try {
      await db.exec(`update public.campaigns set push_order = 888888 where id = '${campA}'`);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint sp_upd');
    } finally {
      await db.exec(`reset role; set request.jwt.claim.sub = '';`);
    }

    assert.ok(error != null, 'Phải chặn ghi metadata trực tiếp');
    assert.match(error.message, /PUSH_METADATA_READ_ONLY/);
  });

  // Test 4: Authenticated tự đặt GUC 'app.allow_push_metadata_write' rồi UPDATE vẫn bị chặn
  await runTest('Test 4: Authenticated tự đặt cờ GUC app.allow_push_metadata_write=true rồi UPDATE vẫn bị chặn', async () => {
    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);

    let error = null;
    await db.exec('savepoint sp_guc');
    try {
      await db.exec(`
        select set_config('app.allow_push_metadata_write', 'true', true);
        update public.campaigns set push_order = 999999 where id = '${campA}';
      `);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint sp_guc');
    } finally {
      await db.exec(`reset role; set request.jwt.claim.sub = '';`);
    }

    assert.ok(error != null, 'Không được phép bypass trigger bằng cách tự set GUC');
    assert.match(error.message, /PUSH_METADATA_READ_ONLY/);
  });

  // Test 5: Authenticated ghi metadata qua INSERT bị chặn
  await runTest('Test 5: Authenticated khởi tạo metadata qua INSERT bị chặn PUSH_METADATA_READ_ONLY', async () => {
    const freshOwner = '55555555-5555-5555-5555-555555555555';
    await db.exec(`
      insert into auth.users(id, email) values ('${freshOwner}', 'fresh@test.local') on conflict do nothing;
      insert into public.profiles(id, email, display_name, credits, role, created_at)
      values ('${freshOwner}', 'fresh@test.local', 'Fresh', 500, 'member', now())
      on conflict do nothing;
    `);

    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${freshOwner}';
    `);

    const newCampId = '99999999-aaaa-bbbb-cccc-dddddddddddd';
    let error = null;
    await db.exec('savepoint sp_ins');
    try {
      await db.exec(`
        insert into public.campaigns(id, owner_id, app_name, package_name, status, target_testers, slot_number, push_order)
        values ('${newCampId}', '${freshOwner}', 'Malicious App', 'com.test.bad', 'open', 12, 1, 999999);
      `);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint sp_ins');
    } finally {
      await db.exec(`reset role; set request.jwt.claim.sub = '';`);
    }

    assert.ok(error != null, 'Phải chặn khởi tạo metadata qua INSERT');
    assert.match(error.message, /PUSH_METADATA_READ_ONLY/);
  });

  // Test 6: Thiếu tiền (< 100 Credit) bị từ chối INSUFFICIENT_CREDITS và rollback toàn bộ
  await runTest('Test 6: Thiếu tiền (99 Credit) bị từ chối INSUFFICIENT_CREDITS, không trừ tiền, không receipt/log', async () => {
    await db.exec(`update public.profiles set credits = 99 where id = '${ownerA}'`);
    const reqFail = '33333333-aaaa-3333-aaaa-333333333333';

    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);

    let error = null;
    await db.exec('savepoint sp_credit');
    try {
      await scalar(`select public.push_campaign('${campA}', '${reqFail}') res`);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint sp_credit');
    } finally {
      await db.exec(`reset role; set request.jwt.claim.sub = '';`);
    }

    assert.ok(error != null);
    assert.match(error.message, /INSUFFICIENT_CREDITS/);

    const profile = await scalar(`select credits from public.profiles where id = '${ownerA}'`);
    assert.equal(profile.credits, 99, 'Credits không được thay đổi');

    const receiptCount = await scalar(`select count(*)::int c from public.campaign_push_receipts where client_request_id = '${reqFail}'`);
    assert.equal(receiptCount.c, 0, 'Không được tạo receipt');
  });

  // Test 7: Rollback toàn bộ khi bị lỗi
  await runTest('Test 7: Rollback toàn diện (nguyên tử): lỗi xảy ra thì tiền và log đều rollback', async () => {
    await db.exec(`update public.profiles set credits = 200 where id = '${ownerA}'`);
    const reqRollback = '44444444-aaaa-4444-aaaa-444444444444';

    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);

    let error = null;
    await db.exec('savepoint sp_rb');
    try {
      await scalar(`select public.push_campaign('${campClosed}', '${reqRollback}') res`);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint sp_rb');
    } finally {
      await db.exec(`reset role; set request.jwt.claim.sub = '';`);
    }

    assert.ok(error != null);
    assert.match(error.message, /CAMPAIGN_NOT_OPEN/);

    const profile = await scalar(`select credits from public.profiles where id = '${ownerA}'`);
    assert.equal(profile.credits, 200, 'Credits phải nguyên vẹn 200');

    const logCount = await scalar(`select count(*)::int c from public.credit_logs where user_id = '${ownerA}' and action = 'campaign_push'`);
    assert.equal(logCount.c, 0, 'Không được ghi log');
  });

  // Test 8: Idempotency và Replay: gọi lại cùng request_id không trừ lần hai, không tăng push_order
  await runTest('Test 8: Replay cùng request_id trả replayed=true, không trừ lần 2, push_order giữ nguyên', async () => {
    await db.exec(`update public.profiles set credits = 300 where id = '${ownerA}'`);
    const reqReplay = '55555555-aaaa-5555-aaaa-555555555555';

    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);
    const firstCall = await scalar(`select public.push_campaign('${campA}', '${reqReplay}') res`);
    const secondCall = await scalar(`select public.push_campaign('${campA}', '${reqReplay}') res`);
    await db.exec(`reset role; set request.jwt.claim.sub = '';`);

    assert.equal(firstCall.res.success, true);
    assert.equal(firstCall.res.replayed, false);
    assert.equal(firstCall.res.balance_after, 200);

    assert.equal(secondCall.res.success, true);
    assert.equal(secondCall.res.replayed, true);
    assert.equal(secondCall.res.balance_after, 200);
    assert.equal(secondCall.res.push_order, firstCall.res.push_order);

    const profile = await scalar(`select credits from public.profiles where id = '${ownerA}'`);
    assert.equal(profile.credits, 200, 'Không bị trừ tiền lần hai');

    const countReceipts = await scalar(`select count(*)::int c from public.campaign_push_receipts where client_request_id = '${reqReplay}'`);
    assert.equal(countReceipts.c, 1, 'Chỉ có 1 receipt');
  });

  // Test 9: Thứ tự lượt đẩy (App đẩy sau đứng trên; A -> B -> A)
  await runTest('Test 9: Thứ tự ưu tiên đẩy app: A đẩy trước, B đẩy sau -> B > A; A đẩy lại -> A > B', async () => {
    await db.exec(`
      update public.profiles set credits = 500 where id in ('${ownerA}', '${ownerB}');
    `);
    const reqA1 = '66666666-aaaa-6666-aaaa-666666666661';
    const reqB1 = '66666666-bbbb-6666-bbbb-666666666662';
    const reqA2 = '66666666-aaaa-6666-aaaa-666666666663';

    // 1. Owner A đẩy App A
    await db.exec(`set local role authenticated; set local request.jwt.claim.sub = '${ownerA}';`);
    const resA1 = await scalar(`select public.push_campaign('${campA}', '${reqA1}') res`);

    // 2. Owner B đẩy App B
    await db.exec(`set local role authenticated; set local request.jwt.claim.sub = '${ownerB}';`);
    const resB1 = await scalar(`select public.push_campaign('${campB}', '${reqB1}') res`);

    // Kiểm tra B1 > A1
    assert.ok(BigInt(resB1.res.push_order) > BigInt(resA1.res.push_order), 'App B đẩy sau phải có push_order lớn hơn App A');

    // 3. Owner A đẩy lại App A
    await db.exec(`set local role authenticated; set local request.jwt.claim.sub = '${ownerA}';`);
    const resA2 = await scalar(`select public.push_campaign('${campA}', '${reqA2}') res`);
    await db.exec(`reset role; set request.jwt.claim.sub = '';`);

    // Kiểm tra A2 > B1
    assert.ok(BigInt(resA2.res.push_order) > BigInt(resB1.res.push_order), 'App A đẩy lại phải có push_order lớn hơn App B');
  });

  // Test 10: Non-owner, Guest, Locked user bị từ chối đúng lỗi
  await runTest('Test 10: Non-owner, Guest, Locked user bị từ chối đúng nguyên nhân', async () => {
    const reqX = '77777777-aaaa-7777-aaaa-777777777777';
    const campLocked = '77777777-cccc-7777-cccc-777777777777';

    await db.exec(`
      insert into public.campaigns(id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values ('${campLocked}', '${lockedUser}', 'Locked App', 'com.test.locked', 'open', 12, 1, now() - interval '3 days')
      on conflict do nothing;
    `);

    // Non-owner
    await db.exec(`set local role authenticated; set local request.jwt.claim.sub = '${stranger}';`);
    let errNonOwner = null;
    await db.exec('savepoint sp_no');
    try {
      await scalar(`select public.push_campaign('${campA}', '${reqX}') res`);
    } catch (e) {
      errNonOwner = e;
      await db.exec('rollback to savepoint sp_no');
    }
    assert.match(errNonOwner?.message, /NOT_OWNER/);

    // Guest / role anon: Thậm chí không có quyền EXECUTE hàm
    await db.exec(`set local role anon; set local request.jwt.claim.sub = '';`);
    let errAnon = null;
    await db.exec('savepoint sp_anon');
    try {
      await scalar(`select public.push_campaign('${campA}', '${reqX}') res`);
    } catch (e) {
      errAnon = e;
      await db.exec('rollback to savepoint sp_anon');
    }
    assert.match(errAnon?.message, /permission denied/i);

    // Chưa đăng nhập (phiên thiếu sub / claim rỗng): Bị NOT_AUTHENTICATED
    await db.exec(`set local role authenticated; set local request.jwt.claim.sub = '';`);
    let errGuest = null;
    await db.exec('savepoint sp_g');
    try {
      await scalar(`select public.push_campaign('${campA}', '${reqX}') res`);
    } catch (e) {
      errGuest = e;
      await db.exec('rollback to savepoint sp_g');
    }
    assert.match(errGuest?.message, /NOT_AUTHENTICATED/);

    // Locked user gọi chính app của mình
    await db.exec(`set local role authenticated; set local request.jwt.claim.sub = '${lockedUser}';`);
    let errLocked = null;
    await db.exec('savepoint sp_l');
    try {
      await scalar(`select public.push_campaign('${campLocked}', '${reqX}') res`);
    } catch (e) {
      errLocked = e;
      await db.exec('rollback to savepoint sp_l');
    }
    assert.match(errLocked?.message, /ACCOUNT_LOCKED/);

    await db.exec(`reset role; set request.jwt.claim.sub = '';`);
  });

  // Test 11: Business date & Expiration time
  await runTest('Test 11: Business date tính theo ngày Việt Nam và hết hạn lúc 00:00 ngày hôm sau', async () => {
    await db.exec(`update public.profiles set credits = 300 where id = '${ownerA}'`);
    const reqTime = '88888888-aaaa-8888-aaaa-888888888888';

    await db.exec(`
      set local role authenticated;
      set local request.jwt.claim.sub = '${ownerA}';
    `);
    const res = await scalar(`select public.push_campaign('${campA}', '${reqTime}') res`);
    await db.exec(`reset role; set request.jwt.claim.sub = '';`);

    const expectedDate = await scalar(`select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date, 'YYYY-MM-DD') d`);
    assert.equal(res.res.business_date, expectedDate.d, 'business_date phải trùng ngày hiện tại giờ VN');

    const expectedExpires = await scalar(`select to_char(((date_trunc('day', now() at time zone 'Asia/Ho_Chi_Minh') + interval '1 day') at time zone 'Asia/Ho_Chi_Minh') at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') exp`);
    assert.equal(res.res.expires_at, expectedExpires.exp, 'expires_at phải là 00:00 ngày tiếp theo giờ VN');
  });

  console.log('=== TẤT CẢ 11 TEST CASES KIỂM CHỨNG BẢN SỬA ĐỀU PASS 100% ===');
} finally {
  await db.close();
}
