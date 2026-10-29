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

const owner = '11111111-1111-1111-1111-111111111111';
const other = '22222222-2222-2222-2222-222222222222';
const camp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const req = 'cccccccc-cccc-cccc-cccc-cccccccccccc';

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
  console.log('=== KHỞI TẠO PGLITE VÀ NẠP FULL SCHEMA SINGAPORE (GỒM 052, 053, 054) ===');
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

  await db.exec(read('supabase/full_schema_singapore.sql'));

  await db.exec(`
    insert into auth.users(id, email) values ('${owner}','owner@test.local'),('${other}','other@test.local');
    insert into public.profiles(id, email, display_name, credits, role, created_at) values
      ('${owner}','owner@test.local','Owner',1000,'member',now()-interval '10 days'),
      ('${other}','other@test.local','Other',1000,'member',now()-interval '10 days')
    on conflict(id) do update set credits=1000, role='member', created_at=now()-interval '10 days';
    insert into public.campaigns(id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values ('${camp}','${owner}','Review','review.owner','open',12,1,now()-interval '3 days');
    insert into public.campaigns(owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values ('${other}','Supply','review.other','open',12,1,now()-interval '3 days');
    delete from public.daily_task_carryover_policies;
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_carryover_policy((now() at time zone 'Asia/Ho_Chi_Minh')::date-5, true, 'test init');
    reset test.allow_policy_retroactive_write;
  `);

  const { today } = await scalar(`select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date,'YYYY-MM-DD') today`);

  // R01: C01 Regression Test - Pending carryover does not seal row or penalize
  await runTest('R01: C01 - Finalizer hoãn chốt và không phạt khi carryover chưa ready', async () => {
    const before = await scalar(`select public.get_daily_task_status('${owner}', date '${today}'-1) s`);
    assert.equal(before.s.carryover_ready, false, 'Predecessor not finalized, so ready must be false');
    
    // Chạy finalizer
    await db.exec(`set test.allow_future_finalize = 'true'; select public._apply_daily_inactivity_penalty(date '${today}'-1); reset test.allow_future_finalize;`);
    
    const row = await scalar(`select carry_in_tests, carry_out_tests, met, penalty_credits, finalized_at is not null sealed
      from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-1`);
    
    // Phải KHÔNG được finalize, không phạt Credit
    assert.equal(row?.sealed ?? false, false, 'Pending user-day must not be sealed');
    assert.equal(row?.penalty_credits ?? 0, 0, 'Pending user-day must not be penalized');

    const profile = await scalar(`select credits from public.profiles where id='${owner}'`);
    assert.equal(profile.credits, 1000, 'Credits must remain intact');
  });

  // R03: C02 Regression Test - Auto pause does not trigger while pending
  await runTest('R03: C02 - pause_inactive_owner_campaigns không auto-pause app khi owner pending', async () => {
    await db.exec(`update public.profiles set daily_missed_streak=7 where id='${owner}'`);
    const status = await scalar(`select public.get_daily_task_status('${owner}', date '${today}') s`);
    assert.equal(status.s.carryover_ready, false, 'Today is pending because yesterday not sealed');

    const pausedCount = await scalar(`select public.pause_inactive_owner_campaigns() as count`);
    const row = await scalar(`select status, paused_reason from public.campaigns where id='${camp}'`);

    assert.equal(row.status, 'open', 'Campaign must remain open');
    assert.equal(row.paused_reason, null, 'Campaign must not be paused by inactive owner while pending');
  });

  // R03: C03 Regression Test - Resume is rejected with DAILY_TASK_PENDING when owner is pending
  await runTest('R03: C03 - resume_campaign bị từ chối khi owner pending', async () => {
    await db.exec(`update public.campaigns set status='paused', paused_reason='owner_inactive' where id='${camp}';
      update public.profiles set daily_missed_streak=0 where id='${owner}';
      set request.jwt.claim.sub='${owner}'`);
    
    await db.exec('savepoint resume_test');
    let error;
    try {
      await db.exec(`select public.resume_campaign('${camp}')`);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint resume_test');
    }
    assert.ok(error != null, 'Resume must throw error');
    assert.match(error.message, /DAILY_TASK_PENDING/, 'Error message must specify DAILY_TASK_PENDING');

    const row = await scalar(`select status from public.campaigns where id='${camp}'`);
    assert.equal(row.status, 'paused', 'Campaign must remain paused');
  });

  // R04: C04 Regression Test - Re-enabling policy preserves frozen debt
  await runTest('R04: C04 - Tắt rồi bật lại chính sách bảo lưu phần thiếu cũ (không bị reset về 0)', async () => {
    await db.exec(`
      insert into public.user_daily_tasks(user_id, task_date, carryover_version, carry_out_tests, finalized_at)
        values('${owner}', date '${today}'-1, 1, 8, now());
      set test.allow_policy_retroactive_write = 'true';
      select public.set_daily_task_carryover_policy(date '${today}'-1, false, 'disable');
      select public.set_daily_task_carryover_policy(date '${today}', true, 'reenable');
      reset test.allow_policy_retroactive_write;
    `);

    const r = await scalar(`select public.get_daily_task_status('${owner}', date '${today}') s`);
    assert.equal(r.s.carry_in_tests, 8, 'Re-enabled policy must preserve previous debt of 8');
  });

  // R02: C05 Regression Test - Past day retains owner obligations despite campaign completed at later time
  await runTest('R02: C05 - Campaign completed ở hiện tại không làm mất nghĩa vụ owner của ngày quá khứ', async () => {
    await db.exec(`
      insert into public.user_daily_tasks(user_id, task_date, carryover_version, carry_out_tests, finalized_at)
        values('${owner}', date '${today}'-3, 1, 2, now());
      insert into public.user_daily_tasks(user_id, task_date, logged_in)
        values('${owner}', date '${today}'-2, true);
    `);

    const before = await scalar(`select public.get_daily_task_status('${owner}', date '${today}'-2) s`);
    assert.equal(before.s.mode, 'owner');

    // Chuyển campaign sang completed ở hiện tại (completed_at = now())
    await db.exec(`update public.campaigns set status='completed' where id='${camp}'`);

    const after = await scalar(`select public.get_daily_task_status('${owner}', date '${today}'-2) s`);
    assert.equal(after.s.mode, 'owner', 'Past day must still recognize owner mode since campaign was completed later');

    // Finalize ngày quá khứ
    await db.exec(`set test.allow_future_finalize = 'true'; select public.finalize_daily_tasks_for_date(date '${today}'-2); reset test.allow_future_finalize;`);
    const sealed = await scalar(`select mode, met, penalty_credits, carry_in_tests, carry_out_tests
      from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-2`);

    assert.equal(sealed.mode, 'owner', 'Sealed row must be owner');
    assert.equal(sealed.met, false, 'Owner did not complete tests, met must be false');
    assert.ok(sealed.penalty_credits > 0, 'Owner must receive penalty credits');
  });

  // R04: C06 Regression Test - Cannot modify historical policy retroactively
  await runTest('R04: C06 - Chặn sửa đổi hồi tố chính sách quá khứ đã áp dụng', async () => {
    await db.exec('savepoint c06_test');
    let error;
    try {
      await db.exec(`select public.set_daily_task_carryover_policy(date '${today}'-5, false, 'rewrite past')`);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint c06_test');
    }
    assert.ok(error != null, 'Must throw error when trying to modify historical policy');
    assert.match(error.message, /CANNOT_MODIFY_HISTORICAL_POLICY/, 'Must reject with CANNOT_MODIFY_HISTORICAL_POLICY');

    const r = await scalar(`select is_enabled from public.daily_task_carryover_policies where effective_from=date '${today}'-5`);
    assert.equal(r.is_enabled, true, 'Policy must remain enabled');
  });

  // R07: P01 Regression Test - Account deletion succeeds and receipt user_id is set to null
  await runTest('R07: P01 - Xóa tài khoản chủ app thành công mà không bị receipt chặn', async () => {
    await db.exec(`set request.jwt.claim.sub='${owner}'; select public.push_campaign('${camp}', '${req}'); set request.jwt.claim.sub=''`);

    // Xóa user trong auth.users
    await db.exec(`delete from auth.users where id='${owner}'`);

    const receipt = await scalar(`select user_id, target_campaign_id, cost_credits from public.campaign_push_receipts where client_request_id='${req}'`);
    assert.equal(receipt.user_id, null, 'Receipt user_id must be set to null on profile deletion');
    assert.equal(receipt.target_campaign_id, camp, 'target_campaign_id must remain preserved');
    assert.equal(receipt.cost_credits, 100, 'Financial cost must remain preserved');
  });

  // R08: P02 Regression Test - Deleted campaign receipt can still be replayed with original target campaign id
  await runTest('R08: P02 - Xóa campaign vẫn replay được receipt cũ với campaign id gốc', async () => {
    await db.exec(`set request.jwt.claim.sub='${owner}'; select public.push_campaign('${camp}', '${req}'); set request.jwt.claim.sub=''`);
    
    // Xóa campaign
    await db.exec(`delete from public.campaigns where id='${camp}'`);

    // Replay lại đúng request id và campaign id ban đầu
    await db.exec(`set request.jwt.claim.sub='${owner}'`);
    const replay = await scalar(`select public.push_campaign('${camp}', '${req}') res`);

    assert.equal(replay.res.success, true, 'Replay must succeed');
    assert.equal(replay.res.replayed, true, 'Must indicate replayed=true');
    assert.equal(replay.res.campaign_id, camp, 'Must return original campaign id');
  });

  // R09: P03 Regression Test - Authenticated role cannot bypass push metadata guard with custom setting
  await runTest('R09: P03 - Role authenticated không thể vượt qua trigger bảo vệ metadata', async () => {
    await db.exec(`
      grant select, update on public.campaigns to authenticated;
      grant select on public.profiles to authenticated;
    `);

    await db.exec('savepoint p03_test');
    let error;
    try {
      await db.exec(`
        set role authenticated; set request.jwt.claim.sub='${owner}';
        select set_config('app.allow_push_metadata_write','true',true);
        update public.campaigns set push_business_date=date '${today}',
          push_expires_at=((date '${today}'+1)::timestamp at time zone 'Asia/Ho_Chi_Minh'), push_order=999999 where id='${camp}';
      `);
    } catch (e) {
      error = e;
      await db.exec('rollback to savepoint p03_test');
    } finally {
      await db.exec(`reset role; set request.jwt.claim.sub=''`);
    }

    assert.ok(error != null, 'Direct update to push metadata by authenticated role must fail');
    assert.match(error.message, /PUSH_METADATA_READ_ONLY/, 'Must reject with PUSH_METADATA_READ_ONLY');

    const campRow = await scalar(`select push_order from public.campaigns where id='${camp}'`);
    assert.equal(campRow.push_order, null, 'push_order must not have been modified');
  });

  // R10: C07 Regression Test - Signup day with existing login row correctly sealed as exempt and met=true
  await runTest('R10: C07 - Checkpoint ngày đăng ký có sẵn row login được chốt đúng is_exempt=true, met=true', async () => {
    await db.exec(`
      update public.profiles set created_at=(date '${today}'-1)::timestamp at time zone 'Asia/Ho_Chi_Minh' where id='${owner}';
      insert into public.user_daily_tasks(user_id, task_date, logged_in) values('${owner}', date '${today}'-1, true);
      set test.allow_future_finalize = 'true';
      select public._apply_daily_inactivity_penalty(date '${today}'-1);
      reset test.allow_future_finalize;
    `);

    const r = await scalar(`select is_exempt, met, carryover_version, finalized_at is not null sealed, penalty_credits
      from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-1`);

    assert.equal(r.sealed, true, 'Row must be sealed');
    assert.equal(r.is_exempt, true, 'Signup checkpoint must be exempt');
    assert.equal(r.met, true, 'Signup checkpoint must be met');
    assert.equal(r.carryover_version, 1, 'carryover_version must be 1');
    assert.equal(r.penalty_credits, 0, 'No penalty for signup day');
  });

  console.log('=== TẤT CẢ 10/10 REGRESSION TESTS SQL HARDENING (R01-R10) ĐỀU PASS 100% ===');
} finally {
  await db.close();
}
