import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

const admin = '11111111-1111-1111-1111-111111111111';
const user = '22222222-2222-2222-2222-222222222222';
const moderator = '33333333-3333-3333-3333-333333333333';
const missing = '99999999-9999-9999-9999-999999999999';
const tester = '44444444-4444-4444-4444-444444444444';
const campaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const report = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

const calls = [
  ['adjust credits', `select public.admin_adjust_credits('${user}',1,'audit')`],
  ['lock user', `select public.admin_set_user_lock('${user}',true,'audit')`],
  ['contact list', `select public.admin_get_contact_reports()`],
  ['contact reply', `select public.admin_reply_contact_report('${missing}','audit')`],
  ['contact status', `select public.admin_update_report_status('${missing}',true)`],
  ['notification', `select public.admin_send_notification('${user}','audit','audit','admin')`],
  ['app configuration', `select public.admin_update_app_config(1,28,'1.0.8',false)`],
  ['home stats read', `select public.admin_get_home_stats_config()`],
  ['home stats write', `select public.admin_set_home_stats_cycle(2,null)`],
  ['daily task configuration', `select public.admin_get_daily_task_config()`],
  ['daily task list', `select public.admin_list_daily_task_policies()`],
  ['daily task history', `select public.admin_get_daily_task_policy_history()`],
  ['daily task schedule', `select public.admin_schedule_daily_task_policy(3,current_date+1,'audit','invalid')`],
  ['daily task cancellation', `select public.admin_cancel_daily_task_policy(current_date+1,'audit','invalid')`],
  ['campaign pause', `select public.admin_pause_campaign('${missing}','audit')`],
  ['campaign resume', `select public.admin_resume_campaign('${missing}')`],
  ['campaign delete', `select public.admin_delete_campaign('${missing}','audit')`],
];

async function asActor(uid, sql, role = 'authenticated') {
  await db.exec(`savepoint sp_actor; select set_config('request.jwt.claim.sub','${uid ?? ''}',true); set local role ${role};`);
  try {
    const res = await db.query(sql);
    await db.exec(`release savepoint sp_actor;`);
    return res;
  } catch (err) {
    await db.exec(`rollback to savepoint sp_actor;`);
    throw err;
  } finally {
    try {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub','',true);`);
    } catch {
      // ignore if aborted
    }
  }
}

async function test(name, fn) {
  await db.exec('begin');
  try {
    await fn();
    console.log(`PASS: ${name}`);
  } catch (e) {
    console.error(`FAIL: ${name}`, e.message);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

const targetMigration = process.env.UP_TO_MIGRATION ? Number(process.env.UP_TO_MIGRATION) : 999;
const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
  .filter(n => /^\d+.*\.sql$/.test(n) && Number(n.slice(0, 3)) <= targetMigration)
  .sort();

console.log(`=== LOADING ACTIVE MIGRATIONS (UP TO ${targetMigration}) ===`);
for (const name of migrationFiles) {
  console.log(`Applying ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

// Seed baseline test data
await db.exec(`
  insert into auth.users(id, email) values
    ('${admin}', 'admin@fixture.invalid'),
    ('${user}', 'member@fixture.invalid'),
    ('${moderator}', 'mod@fixture.invalid'),
    ('${tester}', 'tester@fixture.invalid')
  on conflict (id) do nothing;

  update public.profiles set role = 'admin', is_locked = false where id = '${admin}';
  update public.profiles set role = 'moderator', is_locked = false where id = '${moderator}';
  update public.profiles set role = 'member', is_locked = false, credits = 5 where id = '${user}';
  update public.profiles set role = 'member', is_locked = false where id = '${tester}';

  insert into public.campaigns(id, owner_id, app_name, package_name, status)
  values ('${campaign}', '${user}', 'Admin audit fixture', 'invalid.fixture.admin', 'open')
  on conflict (id) do nothing;

  insert into public.campaign_members(campaign_id, user_id)
  values ('${campaign}', '${tester}')
  on conflict (campaign_id, user_id) do nothing;

  insert into public.tester_reports(id, campaign_id, user_id, status, content, created_at)
  values ('${report}', '${campaign}', '${tester}', 'pending', 'Audit fixture', now())
  on conflict (id) do nothing;
`);

const forbidden = /FORBIDDEN|PERMISSION_DENIED|NOT_ADMIN|ACCOUNT_LOCKED|NOT_AUTHENTICATED|UNAUTHORIZED|permission denied/i;

console.log('=== RUNNING BEHAVIORAL VERIFICATION ===');

// 1. Credit ledger & return amount match effective delta
await test('Effective credit delta: -50 on balance 5 clamps to 0 with delta -5 in result and ledger', async () => {
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${admin}',true); set local role authenticated;`);
  try {
    const result = (await db.query(`select public.admin_adjust_credits('${user}', -50, 'audit deduction') as r`)).rows[0].r;
    assert.equal(result.new_credits, 0);
    assert.equal(result.previous_credits, 5);
    assert.equal(result.amount, -5, `Returned amount must be effective delta -5, got ${result.amount}`);

    await db.exec('reset role');
    const log = (await db.query(`select amount, balance, action from public.credit_logs where user_id='${user}' and action='admin_deduct' order by created_at desc limit 1`)).rows[0];
    assert.ok(log, 'Credit log must exist');
    assert.equal(log.amount, -5, `Ledger amount must be effective delta -5, got ${log.amount}`);
    assert.equal(log.balance, 0);
  } finally {
    await db.exec('rollback');
  }
});

// 2. Input validation and overflow on adjust credits
await test('Credit adjustment input validation: null target, null amount, 0 amount, overflow', async () => {
  await assert.rejects(asActor(admin, `select public.admin_adjust_credits(null, 10, 'audit')`), /TARGET_USER_REQUIRED|USER_NOT_FOUND|null/i);
  await assert.rejects(asActor(admin, `select public.admin_adjust_credits('${user}', 0, 'audit')`), /INVALID_AMOUNT|AMOUNT_CANNOT_BE_ZERO/i);
  await assert.rejects(asActor(admin, `select public.admin_adjust_credits('${user}', null, 'audit')`), /INVALID_AMOUNT|null/i);
  await assert.rejects(asActor(admin, `select public.admin_adjust_credits('${missing}', 10, 'audit')`), /USER_NOT_FOUND/);
  await assert.rejects(asActor(admin, `select public.admin_adjust_credits('${user}', 2147483647, 'audit')`), /OVERFLOW|INTEGER_OVERFLOW|CREDIT_OVERFLOW/i);
});

// 3. User lock target existence
await test('Absent lock target raises error', async () => {
  await assert.rejects(asActor(admin, `select public.admin_set_user_lock('${missing}', true, 'audit')`), /USER_NOT_FOUND|TARGET_USER_NOT_FOUND/);
});

// 4. Unlocked admin & moderator report review
await test('Unlocked admin and moderator can approve reports without owner reward', async () => {
  for (const [name, uid] of [['admin', admin], ['moderator', moderator]]) {
    await db.exec(`update public.tester_reports set status = 'pending' where id = '${report}';`);
    const r = (await asActor(uid, `select public.approve_tester_report('${report}') as r`)).rows[0].r;
    assert.equal(r.success, true);
    assert.equal(r.owner_credits_awarded, 0);
    assert.equal(r.credits_awarded, 2);
  }
});

await test('Unlocked admin and moderator can reject reports', async () => {
  for (const [name, uid] of [['admin', admin], ['moderator', moderator]]) {
    await db.exec(`update public.tester_reports set status = 'pending' where id = '${report}';`);
    const r = (await asActor(uid, `select public.reject_tester_report('${report}', 'Audit rejection', 'INVALID_SCREENSHOT') as r`)).rows[0].r;
    assert.equal(r.success, true);
  }
});

// 5. Non-admin / locked callers denied across all 17 admin RPCs
for (const [actor, uid] of [['member', user], ['moderator', moderator], ['missing-profile', missing], ['anonymous', null]]) {
  for (const [name, sql] of calls) {
    await test(`${actor} denied: ${name}`, async () => {
      await assert.rejects(asActor(uid, sql, uid === null ? 'anon' : 'authenticated'), forbidden);
    });
  }
}

// 6. Locked admin denied across all 17 admin RPCs
await db.exec(`update public.profiles set is_locked = true where id = '${admin}';`);
for (const [name, sql] of calls) {
  await test(`locked admin denied: ${name}`, async () => {
    await assert.rejects(asActor(admin, sql), forbidden);
  });
}

// 7. Locked admin and locked moderator denied report review
await db.exec(`update public.profiles set is_locked = true where id = '${moderator}';`);
for (const [name, uid] of [['admin', admin], ['moderator', moderator]]) {
  await test(`locked ${name} cannot approve reports`, async () => {
    await db.exec(`update public.tester_reports set status = 'pending' where id = '${report}';`);
    await assert.rejects(asActor(uid, `select public.approve_tester_report('${report}')`), forbidden);
  });
  await test(`locked ${name} cannot reject reports`, async () => {
    await db.exec(`update public.tester_reports set status = 'pending' where id = '${report}';`);
    await assert.rejects(asActor(uid, `select public.reject_tester_report('${report}', 'Audit rejection', 'INVALID_SCREENSHOT')`), forbidden);
  });
}

// 8. Direct table access / RLS bypass
await test('Locked admin cannot update app_configs directly', async () => {
  const before = (await db.query(`select * from public.app_configs where id = 'android_client'`)).rows;
  try {
    const r = await asActor(admin, `update public.app_configs set latest_version_name = 'unauthorized-write' where id = 'android_client' returning id`);
    assert.equal(r.rows.length, 0);
  } catch (e) {
    if (e.code !== '42501' && !forbidden.test(e.message)) throw e;
  }
  const after = (await db.query(`select * from public.app_configs where id = 'android_client'`)).rows;
  assert.deepEqual(after, before);
});

await test('Locked admin cannot delete campaigns directly', async () => {
  try {
    const r = await asActor(admin, `delete from public.campaigns where id = '${campaign}' returning id`);
    assert.equal(r.rows.length, 0);
  } catch (e) {
    if (e.code !== '42501' && !forbidden.test(e.message)) throw e;
  }
  const c = (await db.query(`select id from public.campaigns where id = '${campaign}'`)).rows;
  assert.equal(c.length, 1, 'Campaign must not have been deleted');
});

// 9. TRUNCATE and TRIGGER revoked from client roles
for (const role of ['anon', 'authenticated']) {
  for (const privilege of ['TRUNCATE', 'TRIGGER']) {
    await test(`${role} has no ${privilege} capability on public tables`, async () => {
      const rows = (await db.query(`
        select c.relname
        from pg_class c
        where c.relnamespace = 'public'::regnamespace
          and c.relkind = 'r'
          and has_table_privilege('${role}', c.oid, '${privilege}')
      `)).rows;
      assert.deepEqual(rows, []);
    });
  }
}

// 10. Direct notification popup registration for locked recipient
await db.exec(`update public.profiles set is_locked = false where id = '${admin}';`);
await db.exec(`update public.profiles set is_locked = true where id = '${user}';`);
await test('Unlocked admin can send direct notification to locked recipient and popup state is registered', async () => {
  await db.exec(`savepoint sp_notif; select set_config('request.jwt.claim.sub','${admin}',true); set local role authenticated;`);
  try {
    const r = (await db.query(`
      select public.admin_send_notification('${user}', 'Important Alert', 'Account review', 'warning') as res;
    `)).rows[0].res;
    assert.equal(r.success, true);
    assert.equal(r.sent_count, 1);
  } finally {
    await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', true);`);
  }

  const notif = (await db.query(`select id, user_id from public.notifications where user_id = '${user}' and title = 'Important Alert'`)).rows[0];
  assert.ok(notif, 'Notification must exist for recipient');
  const popupState = (await db.query(`select notification_id, display_count from public.notification_popup_states where notification_id = '${notif.id}'`)).rows[0];
  assert.ok(popupState, 'Popup state must be registered even for locked recipient');
  assert.equal(popupState.display_count, 0);
});

// 11. get_campaign_detail contract: exact DTO JSON keys, locked vs unlocked staff/owner, app_update
await test('get_campaign_detail preserves exact DTO keys and excludes locked callers from can_manage', async () => {
  await db.exec(`
    update public.profiles set is_locked = false where id in ('${admin}', '${moderator}', '${user}', '${tester}');
    insert into public.app_update_releases(id, campaign_id, owner_id, package_name, app_name, version_name, target_version_code, release_notes, status, reward_per_claim, max_claims, reserved_credits, paid_count, remaining_credits, published_at)
    values ('dddddddd-dddd-dddd-dddd-dddddddddddd', '${campaign}', '${user}', 'invalid.fixture.admin', 'Admin audit fixture', '2.0.0', 200, 'Notes', 'active', 6, 20, 120, 0, 120, now() - interval '1 hour')
    on conflict (id) do nothing;
  `);

  // Unlocked owner view
  const ownerDetail = (await asActor(user, `select public.get_campaign_detail('${campaign}') as r`)).rows[0].r;
  const expectedKeys = [
    'campaign', 'is_owner', 'is_actual_owner', 'can_manage', 'is_member',
    'checked_in_confirmed_today', 'testers', 'feedbacks', 'reports', 'profiles',
    'today_tested_count', 'reports_count', 'pending_reports_count', 'tester_reports_count',
    'avg_rating', 'my_checkin_dates', 'my_profile', 'unread_warnings', 'weekly_warnings_count',
    'app_update'
  ];
  for (const k of expectedKeys) {
    assert.ok(k in ownerDetail, `Missing key in get_campaign_detail: ${k}`);
  }
  assert.equal(ownerDetail.is_owner, true);
  assert.equal(ownerDetail.is_actual_owner, true);
  assert.equal(ownerDetail.can_manage, true);
  assert.ok(ownerDetail.app_update, 'app_update must be present for owner');
  assert.equal(ownerDetail.app_update.version_name, '2.0.0');
  assert.equal(ownerDetail.app_update.spent_credits, 0);

  // Unlocked admin and moderator can_manage
  const adminDetail = (await asActor(admin, `select public.get_campaign_detail('${campaign}') as r`)).rows[0].r;
  assert.equal(adminDetail.can_manage, true);
  assert.equal(adminDetail.is_actual_owner, false);

  const modDetail = (await asActor(moderator, `select public.get_campaign_detail('${campaign}') as r`)).rows[0].r;
  assert.equal(modDetail.can_manage, true);
  assert.equal(modDetail.is_actual_owner, false);

  // Locked owner: can_manage and is_owner must be FALSE
  await db.exec(`update public.profiles set is_locked = true where id = '${user}';`);
  const lockedOwnerDetail = (await asActor(user, `select public.get_campaign_detail('${campaign}') as r`)).rows[0].r;
  assert.equal(lockedOwnerDetail.can_manage, false);
  assert.equal(lockedOwnerDetail.is_owner, false);
  assert.equal(lockedOwnerDetail.is_actual_owner, true);

  // Locked admin / moderator: can_manage and is_owner must be FALSE
  await db.exec(`update public.profiles set is_locked = true where id = '${admin}';`);
  const lockedAdminDetail = (await asActor(admin, `select public.get_campaign_detail('${campaign}') as r`)).rows[0].r;
  assert.equal(lockedAdminDetail.can_manage, false);
  assert.equal(lockedAdminDetail.is_owner, false);

  await db.exec(`update public.profiles set is_locked = true where id = '${moderator}';`);
  const lockedModDetail = (await asActor(moderator, `select public.get_campaign_detail('${campaign}') as r`)).rows[0].r;
  assert.equal(lockedModDetail.can_manage, false);
  assert.equal(lockedModDetail.is_owner, false);

  await db.exec(`update public.profiles set is_locked = false where id in ('${admin}', '${moderator}', '${user}', '${tester}');`);
});

// 12. reject_tester_report: money clawback, metadata, checkin preservation, warnings
await test('reject_tester_report: bounded clawback, ledger metadata, checkin note preservation, warning rules', async () => {
  const rep1 = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  const rep2 = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
  const checkinDate = (await db.query("select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date, 'YYYY-MM-DD') as d")).rows[0].d;

  await db.exec(`
    update public.profiles set credits = 1 where id = '${tester}';
    insert into public.tester_reports(id, campaign_id, user_id, status, content, provisional_granted_credits, created_at)
    values ('${rep1}', '${campaign}', '${tester}', 'pending', 'Low balance report', 10, now())
    on conflict (id) do update set status = 'pending', provisional_granted_credits = 10;

    -- One temporary checkin and one confirmed checkin for the same date
    insert into public.checkins(campaign_id, user_id, checkin_date, note)
    values
      ('${campaign}', '${tester}', '${checkinDate}', 'Check-in tạm tính')
    on conflict (campaign_id, user_id, checkin_date) do update set note = 'Check-in tạm tính';
  `);

  // Reject rep1: tester only has 1 credit, provisional was 10 -> deducts 1 (bounded), new balance 0
  const r1 = (await asActor(admin, `select public.reject_tester_report('${rep1}', 'Ảnh không hợp lệ', 'INVALID_SCREENSHOT') as r`)).rows[0].r;
  assert.equal(r1.success, true);
  assert.equal(r1.credits_deducted, 1);
  assert.equal(r1.clawed_credits, 1);
  assert.equal(r1.new_balance, 0);
  assert.equal(r1.reason_code, 'INVALID_SCREENSHOT');

  // Verify credit log metadata
  const clawbackLog = (await db.query(`select amount, action, metadata from public.credit_logs where user_id = '${tester}' and action = 'test_rejected_clawback' order by created_at desc limit 1`)).rows[0];
  assert.ok(clawbackLog);
  assert.equal(clawbackLog.amount, -1);
  assert.equal(clawbackLog.metadata.reason_code, 'INVALID_SCREENSHOT');

  // Verify temporary checkin was deleted
  const tempCheckin = (await db.query(`select 1 from public.checkins where campaign_id = '${campaign}' and user_id = '${tester}' and checkin_date = '${checkinDate}'`)).rows;
  assert.equal(tempCheckin.length, 0, 'Temporary checkin with tạm tính must be deleted');

  // Now create a confirmed checkin (without tạm tính) and another report
  await db.exec(`
    delete from public.tester_reports where id = '${rep1}';
    update public.profiles set credits = 20 where id = '${tester}';
    insert into public.checkins(campaign_id, user_id, checkin_date, note)
    values ('${campaign}', '${tester}', '${checkinDate}', 'Đã xác nhận kiểm thử')
    on conflict (campaign_id, user_id, checkin_date) do update set note = 'Đã xác nhận kiểm thử';

    insert into public.tester_reports(id, campaign_id, user_id, status, content, provisional_granted_credits, created_at)
    values ('${rep2}', '${campaign}', '${tester}', 'pending', 'Full clawback report', 10, now())
    on conflict (id) do update set status = 'pending', provisional_granted_credits = 10;
  `);

  const testerBefore = (await db.query(`select credits from public.profiles where id = '${tester}'`)).rows[0].credits;
  const r2 = (await asActor(admin, `select public.reject_tester_report('${rep2}', 'Lỗi ảnh', 'BLURRY_SCREENSHOT') as r`)).rows[0].r;
  assert.equal(r2.success, true);
  assert.equal(r2.credits_deducted, 10);
  assert.equal(r2.clawed_credits, 10);
  assert.equal(r2.new_balance, testerBefore - 10);
  assert.equal(r2.reason_code, 'BLURRY_SCREENSHOT');

  // Confirmed checkin must be PRESERVED
  const confirmedCheckin = (await db.query(`select 1 from public.checkins where campaign_id = '${campaign}' and user_id = '${tester}' and checkin_date = '${checkinDate}'`)).rows;
  assert.equal(confirmedCheckin.length, 1, 'Preexisting confirmed checkin must NOT be deleted');
});

await test('Locked admin cannot bypass moderation through UPDATE on their own campaign', async () => {
  const ownedCampaign = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  await db.exec(`
    reset role;
    select set_config('request.jwt.claim.sub', '', true);
    update public.profiles set is_locked = false where id = '${admin}';
    insert into public.campaigns(id,owner_id,app_name,package_name,status,paused_reason,admin_note)
    values('${ownedCampaign}','${admin}','Owned audit app','invalid.fixture.owned','paused','admin_manual','Review required');
    select set_config('request.jwt.claim.sub', '${admin}', true);
    set local role authenticated;
  `);
  const allowed = await db.query(`update public.campaigns set app_name='Owned audit edit' where id='${ownedCampaign}' returning id`);
  assert.equal(allowed.rows.length, 1, 'Unlocked owner must retain campaign editing');
  await db.exec(`reset role;update public.profiles set is_locked=true where id='${admin}';set local role authenticated;`);
  const denied = await db.query(`update public.campaigns set status='open',paused_reason=null,admin_note=null,admin_resumed=true where id='${ownedCampaign}' returning id`);
  assert.equal(denied.rows.length, 0, 'Locked owner/admin must not reopen a moderated campaign directly');
  await db.exec('reset role');
  assert.equal((await db.query(`select status from public.campaigns where id='${ownedCampaign}'`)).rows[0].status, 'paused');
});

console.log('=== ALL ADMIN AUDIT HARDENING TESTS PASSED ===');
await db.close();
