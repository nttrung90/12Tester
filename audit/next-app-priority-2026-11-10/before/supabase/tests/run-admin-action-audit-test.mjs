import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

// Seed IDs
const admin1 = '11111111-1111-1111-1111-111111111111'; // bootstrap admin with admin_management
const admin2 = '22222222-2222-2222-2222-222222222222'; // regular admin without admin_management
const member1 = '44444444-4444-4444-4444-444444444444';
const member2 = '55555555-5555-5555-5555-555555555555';
const lockedAdmin = '66666666-6666-6666-6666-666666666666';

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
      // ignore
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

// 1. Load active migrations 062 to 070
const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
  .filter(n => /^\d+.*\.sql$/.test(n) && Number(n.slice(0, 3)) <= 70)
  .sort();

console.log(`=== LOADING ACTIVE MIGRATIONS (062 TO 070) ===`);
for (const name of migrationFiles) {
  console.log(`Applying ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

// 2. Load staged 073 focus 1 migration
console.log(`=== APPLYING STAGED 073 (FOCUS 1 AUDIT & ROLES) ===`);
const staged073Sql = read('supabase/migrations/073_admin_action_audit.sql');
await db.exec(staged073Sql);

// 3. Seed test users
await db.exec(`
  insert into auth.users(id, email) values
    ('${admin1}', 'admin1@crosstest.local'),
    ('${admin2}', 'admin2@crosstest.local'),
    ('${member1}', 'member1@crosstest.local'),
    ('${member2}', 'member2@crosstest.local'),
    ('${lockedAdmin}', 'locked_admin@crosstest.local')
  on conflict (id) do nothing;

  update public.profiles set role = 'admin', is_locked = false, credits = 100, display_name = 'Admin Primary' where id = '${admin1}';
  update public.profiles set role = 'admin', is_locked = false, credits = 100, display_name = 'Admin Secondary' where id = '${admin2}';
  update public.profiles set role = 'member', is_locked = false, credits = 50, display_name = 'Member One' where id = '${member1}';
  update public.profiles set role = 'member', is_locked = false, credits = 50, display_name = 'Member Two' where id = '${member2}';
  update public.profiles set role = 'admin', is_locked = true, credits = 100, display_name = 'Locked Admin' where id = '${lockedAdmin}';

  select public.bootstrap_admin_capability('${admin1}', 'admin_management');
`);

console.log(`=== RUNNING ADMIN ACTION AUDIT & ROLE COUPLING TESTS (STAGED 073 FOCUS 1) ===`);

// ----------------------------------------------------------------------------
// Test 1: Legacy admin_adjust_credits records common audit event
// ----------------------------------------------------------------------------
await test('Legacy admin_adjust_credits records common audit event and clamps overdraft', async () => {
  const countBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;

  // 1. Positive adjustment
  const res1 = (await asActor(admin1, `select public.admin_adjust_credits('${member1}', 25, 'Bonus gift') as r`)).rows[0].r;
  assert.equal(res1.success, true);
  assert.equal(res1.amount, 25);
  assert.equal(res1.new_credits, 75);

  const countAfter1 = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  assert.equal(countAfter1, countBefore + 1, 'Exactly one audit event must be recorded');

  const ev1 = (await db.query(`select action, actor_id, target_id, reason, old_state, new_state from public.admin_action_events where action = 'admin_grant_credits' order by created_at desc limit 1`)).rows[0];
  assert.equal(ev1.action, 'admin_grant_credits');
  assert.equal(ev1.actor_id, admin1);
  assert.equal(ev1.target_id, member1);
  assert.equal(ev1.reason, 'Bonus gift');
  assert.equal(ev1.old_state.credits, 50);
  assert.equal(ev1.new_state.credits, 75);
  assert.equal(ev1.new_state.amount, 25);

  // 2. Overdraft clamp to 0
  const res2 = (await asActor(admin1, `select public.admin_adjust_credits('${member1}', -9999, 'Deduct all') as r`)).rows[0].r;
  assert.equal(res2.success, true);
  assert.equal(res2.new_credits, 0);
  assert.equal(res2.amount, -75);

  const countAfter2 = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  assert.equal(countAfter2, countBefore + 2);

  const ev2 = (await db.query(`select action, new_state from public.admin_action_events where action = 'admin_deduct_credits' order by created_at desc limit 1`)).rows[0];
  assert.equal(ev2.action, 'admin_deduct_credits');
  assert.equal(ev2.new_state.credits, 0);
  assert.equal(ev2.new_state.amount, -75);
});

// ----------------------------------------------------------------------------
// Test 2: admin_compensate_credits emits exactly ONE event (no duplicate adjust)
// ----------------------------------------------------------------------------
await test('admin_compensate_credits emits exactly one common intent event and supports replay', async () => {
  const countBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  const reqId = '77777777-1111-2222-3333-444444444444';

  const compRes = (await asActor(admin1, `select public.admin_compensate_credits('${reqId}', '${member2}', 15, 'Compensation reason') as r`)).rows[0].r;
  assert.equal(compRes.success, true);
  assert.equal(compRes.amount, 15);
  assert.equal(compRes.is_replay, false);

  const countAfter = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  assert.equal(countAfter, countBefore + 1, 'Compensation must emit exactly ONE audit event, not two!');

  const ev = (await db.query(`select action, actor_id, target_id from public.admin_action_events order by created_at desc limit 1`)).rows[0];
  assert.equal(ev.action, 'compensate_credits');
  assert.equal(ev.actor_id, admin1);
  assert.equal(ev.target_id, member2);

  // Replay returns original result without writing a new event
  const replayRes = (await asActor(admin1, `select public.admin_compensate_credits('${reqId}', '${member2}', 15, 'Compensation reason') as r`)).rows[0].r;
  assert.equal(replayRes.success, true);
  assert.equal(replayRes.is_replay, true);

  const countReplay = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  assert.equal(countReplay, countAfter, 'Replay must not record duplicate audit event');
});

// ----------------------------------------------------------------------------
// Test 3: Private calculation helper is not exposed to client roles
// ----------------------------------------------------------------------------
await test('Private helper _admin_adjust_credits_internal has execution revoked from client roles', async () => {
  const checkAnon = (await db.query(`select has_function_privilege('anon', 'public._admin_adjust_credits_internal(uuid, uuid, integer, text)', 'EXECUTE') as priv`)).rows[0].priv;
  assert.equal(checkAnon, false, 'anon must not have execute privilege on internal helper');

  const checkAuth = (await db.query(`select has_function_privilege('authenticated', 'public._admin_adjust_credits_internal(uuid, uuid, integer, text)', 'EXECUTE') as priv`)).rows[0].priv;
  assert.equal(checkAuth, false, 'authenticated must not have execute privilege on internal helper');

  // Direct call fails closed
  await assert.rejects(
    asActor(admin1, `select public._admin_adjust_credits_internal('${admin1}', '${member1}', 10, 'hack')`),
    /permission denied/i
  );
});

// ----------------------------------------------------------------------------
// Test 4: Coordinated admin_set_user_lock with last admin & capability checks
// ----------------------------------------------------------------------------
await test('admin_set_user_lock records audit, blocks self-lock and protects last unlocked admin', async () => {
  // 1. Normal user lock
  const countBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  const lockRes = (await asActor(admin1, `select public.admin_set_user_lock('${member1}', true, 'Spam violation') as r`)).rows[0].r;
  assert.equal(lockRes.success, true);
  assert.equal(lockRes.is_locked, true);

  const evLock = (await db.query(`select action, reason, old_state, new_state from public.admin_action_events order by created_at desc limit 1`)).rows[0];
  assert.equal(evLock.action, 'lock_user');
  assert.equal(evLock.reason, 'Spam violation');
  assert.equal(evLock.old_state.is_locked, false);
  assert.equal(evLock.new_state.is_locked, true);

  // 2. Cannot lock self
  await assert.rejects(
    asActor(admin1, `select public.admin_set_user_lock('${admin1}', true, 'Self lock')`),
    /CANNOT_LOCK_SELF/
  );

  // 3. Admin without admin_management cannot lock another admin
  await assert.rejects(
    asActor(admin2, `select public.admin_set_user_lock('${admin1}', true, 'Lock admin')`),
    /INSUFFICIENT_PRIVILEGE/
  );

  // 4. Admin with admin_management locking admin2 succeeds
  const lockAdmin2 = (await asActor(admin1, `select public.admin_set_user_lock('${admin2}', true, 'Lock secondary admin') as r`)).rows[0].r;
  assert.equal(lockAdmin2.success, true);
  assert.equal(lockAdmin2.is_locked, true);

  // 5. Admin1 is now the LAST unlocked admin -> locking admin1 must be blocked
  // (Even if another caller had capability, last admin is protected)
  await db.exec(`insert into public.admin_capabilities(admin_id, capability) values ('${admin2}', 'admin_management') on conflict do nothing;`);
  // Try to lock admin1: blocked because admin1 is the last unlocked admin
  await assert.rejects(
    asActor(admin1, `select public.admin_set_user_lock('${admin1}', true, 'Lock last admin')`),
    /CANNOT_LOCK_SELF/
  );

  // Unlock admin2
  const unlockRes = (await asActor(admin1, `select public.admin_set_user_lock('${admin2}', false, 'Re-activate admin') as r`)).rows[0].r;
  assert.equal(unlockRes.success, true);
  assert.equal(unlockRes.is_locked, false);

  const evUnlock = (await db.query(`select action from public.admin_action_events where target_id = '${admin2}' and action = 'unlock_user' order by created_at desc limit 1`)).rows[0];
  assert.equal(evUnlock.action, 'unlock_user');
});

// ----------------------------------------------------------------------------
// Test 5: Role demotion revokes capabilities permanently
// ----------------------------------------------------------------------------
await test('Role demotion clears admin_capabilities preventing dormant privilege reactivation', async () => {
  // Promote member2 to admin
  const resPromote = (await asActor(admin1, `select public.admin_set_user_role('${member2}', 'admin', 'Promote member2 to admin') as r`)).rows[0].r;
  assert.equal(resPromote.success, true);

  // Grant admin_management capability to member2
  await db.exec(`insert into public.admin_capabilities(admin_id, capability) values ('${member2}', 'admin_management') on conflict do nothing;`);

  const capsBefore = (await db.query(`select capability from public.admin_capabilities where admin_id = '${member2}'`)).rows;
  assert.equal(capsBefore.length, 1);
  assert.equal(capsBefore[0].capability, 'admin_management');

  // Demote member2 to moderator
  const resDemote = (await asActor(admin1, `select public.admin_set_user_role('${member2}', 'moderator', 'Demote member2 to mod') as r`)).rows[0].r;
  assert.equal(resDemote.success, true);

  // Capability MUST be deleted!
  const capsAfter = (await db.query(`select capability from public.admin_capabilities where admin_id = '${member2}'`)).rows;
  assert.equal(capsAfter.length, 0, 'Demoted admin must have capabilities purged!');

  // Re-promoting to admin later does NOT revive dormant capability
  await asActor(admin1, `select public.admin_set_user_role('${member2}', 'admin', 'Re-promote to admin')`);
  const capsRepromote = (await db.query(`select capability from public.admin_capabilities where admin_id = '${member2}'`)).rows;
  assert.equal(capsRepromote.length, 0, 'Re-promoted admin must NOT have dormant capabilities revived!');
});

// ----------------------------------------------------------------------------
// Test 6: Legacy admin_send_notification audits and preserves 067 popup registration
// ----------------------------------------------------------------------------
await test('Legacy admin_send_notification records audit and registers 067 popup state', async () => {
  const countBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;

  // 1. Direct notification to member1
  const directRes = (await asActor(admin1, `select public.admin_send_notification('${member1}', 'Important Notice', 'Please verify your report', 'admin') as r`)).rows[0].r;
  assert.equal(directRes.success, true);
  assert.equal(directRes.sent_count, 1);

  // Verify 067 popup state registered for the direct notification
  const popupState = (await db.query(`
    select ps.display_count, n.title
    from public.notification_popup_states ps
    join public.notifications n on n.id = ps.notification_id
    where n.user_id = '${member1}'
    order by n.created_at desc limit 1
  `)).rows[0];
  assert.ok(popupState, '067 popup state must be registered for direct notification');
  assert.equal(popupState.display_count, 0);
  assert.equal(popupState.title, 'Important Notice');

  // Audit event registered
  const evDirect = (await db.query(`select action, target_id, reason from public.admin_action_events order by created_at desc limit 1`)).rows[0];
  assert.equal(evDirect.action, 'send_notification');
  assert.equal(evDirect.target_id, member1);
  assert.equal(evDirect.reason, 'Important Notice');

  // 2. Broadcast notification
  const bcastRes = (await asActor(admin1, `select public.admin_send_notification(null, 'Broadcast title', 'System update at midnight', 'system') as r`)).rows[0].r;
  assert.equal(bcastRes.success, true);
  assert.ok(bcastRes.sent_count >= 5);
  assert.equal(bcastRes.target, 'all');

  const evBcast = (await db.query(`select action, target_id, new_state from public.admin_action_events where target_id = 'all' order by created_at desc limit 1`)).rows[0];
  assert.equal(evBcast.action, 'send_notification');
  assert.equal(evBcast.target_id, 'all');
  assert.equal(evBcast.new_state.broadcast, true);
});

// ----------------------------------------------------------------------------
// Test 8: App config, home stats cycle, daily task policy audits
// ----------------------------------------------------------------------------
await test('Audited app_config, home_stats_cycle, daily_task_policy mutations', async () => {
  // 1. app config update
  const cfgBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action = 'update_app_config'`)).rows[0].cnt;
  const cfgRes = (await asActor(admin1, `select public.admin_update_app_config(1, 28, '1.0.8', false) as r`)).rows[0].r;
  assert.equal(cfgRes.id, 'android_client');
  assert.equal(cfgRes.latest_version_code, 28);

  const cfgAfter = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action = 'update_app_config'`)).rows[0].cnt;
  assert.equal(cfgAfter, cfgBefore + 1);

  // 2. home stats cycle
  const statsBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action = 'set_home_stats_cycle'`)).rows[0].cnt;
  const homeCfg = (await asActor(admin1, `select public.admin_get_home_stats_config() as r`)).rows[0].r;
  const statsRes = (await asActor(admin1, `select public.admin_set_home_stats_cycle(2, '${homeCfg.revision}') as r`)).rows[0].r;
  assert.ok(statsRes.active_cycle_days !== undefined || statsRes.scheduled_cycle_days !== undefined);

  const statsAfter = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action = 'set_home_stats_cycle'`)).rows[0].cnt;
  assert.equal(statsAfter, statsBefore + 1);

  // 3. daily task schedule & cancel
  const schedBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action = 'schedule_daily_task_policy'`)).rows[0].cnt;
  const dCfg = (await asActor(admin1, `select public.admin_get_daily_task_config() as r`)).rows[0].r;
  const schedRes = (await asActor(admin1, `select public.admin_schedule_daily_task_policy(3, current_date + 4, 'Ratio test', '${dCfg.revision}') as r`)).rows[0].r;
  assert.equal(schedRes.success, true);

  const schedAfter = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action = 'schedule_daily_task_policy'`)).rows[0].cnt;
  assert.equal(schedAfter, schedBefore + 1);

  const dCfg2 = (await asActor(admin1, `select public.admin_get_daily_task_config() as r`)).rows[0].r;
  const cancRes = (await asActor(admin1, `select public.admin_cancel_daily_task_policy(current_date + 4, 'Cancel ratio test', '${dCfg2.revision}') as r`)).rows[0].r;
  assert.equal(cancRes.success, true);

  const cancAfter = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action = 'cancel_daily_task_policy'`)).rows[0].cnt;
  assert.ok(cancAfter >= 1);
});

// ----------------------------------------------------------------------------
// Test 9: Campaign pause, resume, delete audits
// ----------------------------------------------------------------------------
await test('Campaign pause, resume, delete mutations emit audit events', async () => {
  const campId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  await db.exec(`
    insert into public.campaigns(id, owner_id, app_name, package_name, status)
    values ('${campId}', '${member1}', 'Test Camp', 'com.test.camp', 'open')
    on conflict (id) do update set status = 'open';
  `);

  // Pause
  const pRes = (await asActor(admin1, `select public.admin_pause_campaign('${campId}', 'Review violation') as r`)).rows[0].r;
  assert.equal(pRes.success, true);
  const evPause = (await db.query(`select action, target_id, reason from public.admin_action_events where action = 'pause_campaign' and target_id = '${campId}' order by created_at desc limit 1`)).rows[0];
  assert.ok(evPause);
  assert.equal(evPause.reason, 'Review violation');

  // Resume
  const rRes = (await asActor(admin1, `select public.admin_resume_campaign('${campId}') as r`)).rows[0].r;
  assert.equal(rRes.success, true);
  const evResume = (await db.query(`select action, target_id from public.admin_action_events where action = 'resume_campaign' and target_id = '${campId}' order by created_at desc limit 1`)).rows[0];
  assert.ok(evResume);

  // Delete
  const dRes = (await asActor(admin1, `select public.admin_delete_campaign('${campId}', 'Malicious app') as r`)).rows[0].r;
  assert.equal(dRes.success, true);
  const evDelete = (await db.query(`select action, target_id, reason from public.admin_action_events where action = 'delete_campaign' and target_id = '${campId}' order by created_at desc limit 1`)).rows[0];
  assert.ok(evDelete);
  assert.equal(evDelete.reason, 'Malicious app');
});

// ----------------------------------------------------------------------------
// Test 10: Contact reports reply & update status audits
// ----------------------------------------------------------------------------
await test('Contact reports reply and update status emit audit events', async () => {
  const contactId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  await db.exec(`
    insert into public.contact_reports(id, user_id, category, message, status)
    values ('${contactId}', '${member1}', 'bug', 'App crashed on login', 'new')
    on conflict (id) do nothing;
  `);

  // Reply
  const repRes = (await asActor(admin1, `select public.admin_reply_contact_report('${contactId}', 'We are investigating') as r`)).rows[0].r;
  assert.equal(repRes.status, 'replied');
  const evReply = (await db.query(`select action, target_id, reason from public.admin_action_events where action = 'reply_contact_report' and target_id = '${contactId}' order by created_at desc limit 1`)).rows[0];
  assert.ok(evReply);
  assert.equal(evReply.reason, 'We are investigating');

  // Status update
  const stRes = (await asActor(admin1, `select public.admin_update_report_status('${contactId}', true) as r`)).rows[0].r;
  assert.equal(stRes.status, 'resolved');
  const evStatus = (await db.query(`select action, target_id from public.admin_action_events where action = 'update_report_status' and target_id = '${contactId}' order by created_at desc limit 1`)).rows[0];
  assert.ok(evStatus);
});

// ----------------------------------------------------------------------------
// Test 11: Staff vs Owner report moderation audit distinction
// ----------------------------------------------------------------------------
await test('Owner moderation retains normal review events; Staff moderation records in common audit', async () => {
  const modUser = '33333333-3333-3333-3333-333333333333';
  await db.exec(`
    insert into auth.users(id, email) values ('${modUser}', 'mod_user@fixture.invalid') on conflict do nothing;
    update public.profiles set role = 'moderator', is_locked = false where id = '${modUser}';
  `);

  const campMemberOwner = member1; // member owner
  const campAdminOwner = admin1;   // admin owner
  const campModOwner = modUser;    // moderator owner

  const camp1 = 'bbbbbbbb-aaaa-aaaa-aaaa-aaaaaaaaaaa1';
  const camp2 = 'bbbbbbbb-aaaa-aaaa-aaaa-aaaaaaaaaaa2';
  const camp3 = 'bbbbbbbb-aaaa-aaaa-aaaa-aaaaaaaaaaa3';

  const repMemberApprove = 'bbbbbbbb-1111-aaaa-aaaa-aaaaaaaaaaa1';
  const repMemberReject  = 'bbbbbbbb-1111-aaaa-aaaa-aaaaaaaaaaa2';
  const repAdminApprove   = 'bbbbbbbb-2222-aaaa-aaaa-aaaaaaaaaaa1';
  const repAdminReject    = 'bbbbbbbb-2222-aaaa-aaaa-aaaaaaaaaaa2';
  const repModApprove     = 'bbbbbbbb-3333-aaaa-aaaa-aaaaaaaaaaa1';
  const repModReject      = 'bbbbbbbb-3333-aaaa-aaaa-aaaaaaaaaaa2';
  const repStaffApprove   = 'bbbbbbbb-4444-aaaa-aaaa-aaaaaaaaaaa1';
  const repStaffReject    = 'bbbbbbbb-4444-aaaa-aaaa-aaaaaaaaaaa2';

  await db.exec(`
    insert into public.campaigns(id, owner_id, app_name, package_name, status) values
      ('${camp1}', '${campMemberOwner}', 'Camp Member Owner', 'com.test.member', 'open'),
      ('${camp2}', '${campAdminOwner}', 'Camp Admin Owner', 'com.test.admin', 'open'),
      ('${camp3}', '${campModOwner}', 'Camp Mod Owner', 'com.test.mod', 'open')
    on conflict (id) do nothing;

    insert into public.campaign_members(campaign_id, user_id) values
      ('${camp1}', '${member2}'),
      ('${camp2}', '${member2}'),
      ('${camp3}', '${member2}')
    on conflict do nothing;

    insert into public.tester_reports(id, campaign_id, user_id, status, content, provisional_granted_credits, created_at) values
      ('${repMemberApprove}', '${camp1}', '${member2}', 'pending', 'Content 1', 2, now()),
      ('${repMemberReject}',  '${camp1}', '${member2}', 'pending', 'Content 2', 2, now()),
      ('${repAdminApprove}',  '${camp2}', '${member2}', 'pending', 'Content 3', 2, now()),
      ('${repAdminReject}',   '${camp2}', '${member2}', 'pending', 'Content 4', 2, now()),
      ('${repModApprove}',    '${camp3}', '${member2}', 'pending', 'Content 5', 2, now()),
      ('${repModReject}',     '${camp3}', '${member2}', 'pending', 'Content 6', 2, now()),
      ('${repStaffApprove}',  '${camp1}', '${member2}', 'pending', 'Content 7', 2, now()),
      ('${repStaffReject}',   '${camp1}', '${member2}', 'pending', 'Content 8', 2, now())
    on conflict do nothing;
  `);

  const evStaffBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action like 'staff_%'`)).rows[0].cnt;

  // 1. Member owner approves & rejects on own campaign -> ZERO common audit events
  const mApp = (await asActor(campMemberOwner, `select public.approve_tester_report('${repMemberApprove}', 0) as r`)).rows[0].r;
  assert.equal(mApp.success, true);
  assert.equal(mApp.owner_credits_awarded, 2);

  const mRej = (await asActor(campMemberOwner, `select public.reject_tester_report('${repMemberReject}', 'Owner reject', 'BLURRY_SCREENSHOT') as r`)).rows[0].r;
  assert.equal(mRej.success, true);

  // 2. Admin owner approves & rejects on own campaign -> ZERO common audit events, owner bonus awarded
  const aApp = (await asActor(campAdminOwner, `select public.approve_tester_report('${repAdminApprove}', 0) as r`)).rows[0].r;
  assert.equal(aApp.success, true);
  assert.equal(aApp.owner_credits_awarded, 2);

  const aRej = (await asActor(campAdminOwner, `select public.reject_tester_report('${repAdminReject}', 'Admin owner reject', 'BLURRY_SCREENSHOT') as r`)).rows[0].r;
  assert.equal(aRej.success, true);

  // 3. Moderator owner approves & rejects on own campaign -> ZERO common audit events, owner bonus awarded
  const modApp = (await asActor(campModOwner, `select public.approve_tester_report('${repModApprove}', 0) as r`)).rows[0].r;
  assert.equal(modApp.success, true);
  assert.equal(modApp.owner_credits_awarded, 2);

  const modRej = (await asActor(campModOwner, `select public.reject_tester_report('${repModReject}', 'Mod owner reject', 'BLURRY_SCREENSHOT') as r`)).rows[0].r;
  assert.equal(modRej.success, true);

  const evStaffAfterOwner = (await db.query(`select count(*)::int as cnt from public.admin_action_events where action like 'staff_%'`)).rows[0].cnt;
  assert.equal(evStaffAfterOwner, evStaffBefore, 'All owner mode reviews must NOT emit staff common audit events');

  // 4. Non-owner staff (admin) approves on another user's campaign -> records staff_approve_tester_report, owner bonus 0
  const staffApp = (await asActor(admin1, `select public.approve_tester_report('${repStaffApprove}', 0) as r`)).rows[0].r;
  assert.equal(staffApp.success, true);
  assert.equal(staffApp.owner_credits_awarded, 0);

  const evStaffApp = (await db.query(`select action, target_id, actor_id from public.admin_action_events where action = 'staff_approve_tester_report' and target_id = '${repStaffApprove}'`)).rows[0];
  assert.ok(evStaffApp);
  assert.equal(evStaffApp.actor_id, admin1);

  // 5. Non-owner staff (moderator) rejects on another user's campaign -> records staff_reject_tester_report
  const staffRej = (await asActor(modUser, `select public.reject_tester_report('${repStaffReject}', 'Staff mod reject', 'INVALID_SCREENSHOT') as r`)).rows[0].r;
  assert.equal(staffRej.success, true);

  const evStaffRej = (await db.query(`select action, target_id, actor_id from public.admin_action_events where action = 'staff_reject_tester_report' and target_id = '${repStaffReject}'`)).rows[0];
  assert.ok(evStaffRej);
  assert.equal(evStaffRej.actor_id, modUser);

  // 6. Failed mutation rollback: calling approve on nonexistent report raises error and emits NO committed audit event
  const countBeforeFail = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  await assert.rejects(
    asActor(admin1, `select public.approve_tester_report('00000000-0000-0000-0000-000000000000', 0)`),
    /REPORT_NOT_FOUND/
  );
  const countAfterFail = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  assert.equal(countAfterFail, countBeforeFail, 'Failed mutations must not leave committed audit events');
});

// ----------------------------------------------------------------------------
// Test 12: Read-only GET RPCs emit zero audit events
// ----------------------------------------------------------------------------
await test('Read-only GET RPCs do not emit fake action audit events', async () => {
  const countBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;

  await asActor(admin1, `select public.admin_get_contact_reports()`);
  await asActor(admin1, `select public.admin_get_home_stats_config()`);
  await asActor(admin1, `select public.admin_get_daily_task_config()`);
  await asActor(admin1, `select public.admin_list_daily_task_policies()`);
  await asActor(admin1, `select public.admin_get_daily_task_policy_history()`);

  const countAfter = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  assert.equal(countAfter, countBefore, 'Read-only GET RPCs must NOT emit audit events!');
});

// ----------------------------------------------------------------------------
// Test 13: All _impl_ functions denied to client roles
// ----------------------------------------------------------------------------
await test('All _impl_ internal functions have execution revoked from authenticated and anon', async () => {
  const implFunctions = [
    '_impl_admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text)',
    '_impl_admin_set_home_stats_cycle(integer, text)',
    '_impl_admin_schedule_daily_task_policy(integer, date, text, text)',
    '_impl_admin_cancel_daily_task_policy(date, text, text)',
    '_impl_admin_pause_campaign(uuid, text)',
    '_impl_admin_resume_campaign(uuid)',
    '_impl_admin_delete_campaign(uuid, text)',
    '_impl_admin_reply_contact_report(uuid, text)',
    '_impl_admin_update_report_status(uuid, boolean)',
    '_impl_approve_tester_report(uuid, integer)',
    '_impl_reject_tester_report(uuid, text, text)',
    '_admin_adjust_credits_internal(uuid, uuid, integer, text)'
  ];

  for (const fn of implFunctions) {
    const privAuth = (await db.query(`select has_function_privilege('authenticated', 'public.${fn}', 'EXECUTE') as priv`)).rows[0].priv;
    assert.equal(privAuth, false, `authenticated must not have EXECUTE on ${fn}`);

    const privAnon = (await db.query(`select has_function_privilege('anon', 'public.${fn}', 'EXECUTE') as priv`)).rows[0].priv;
    assert.equal(privAnon, false, `anon must not have EXECUTE on ${fn}`);
  }
});

// ----------------------------------------------------------------------------
// Test 14: Idempotent re-application of staged 073 does not corrupt schema
// ----------------------------------------------------------------------------
await db.exec(staged073Sql);
await test('Re-application of 073 is completely idempotent', async () => {
  const check = (await asActor(admin1, `select public.admin_adjust_credits('${member1}', 1, 'Idempotent test') as r`)).rows[0].r;
  assert.equal(check.success, true);
});

console.log(`=== ALL ADMIN ACTION AUDIT & ROLE COUPLING TESTS PASSED (STAGED 073 COMPLETE) ===`);
