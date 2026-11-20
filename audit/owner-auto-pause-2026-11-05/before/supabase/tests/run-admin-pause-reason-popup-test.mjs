import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING FULL-CHAIN ADMIN PAUSE REASON POPUP TESTS (MIGRATION 078) ===');

const db = await createDb({ legacyGrants: true });

async function asActor(userId, sql, role = 'authenticated') {
  if (userId) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${userId}', true);`);
  } else {
    await db.exec(`select set_config('request.jwt.claim.sub', '', true);`);
  }
  await db.exec(`set role ${role}; savepoint sp_actor;`);
  try {
    const res = await db.query(sql);
    await db.exec(`release savepoint sp_actor;`);
    return res;
  } catch (err) {
    await db.exec(`rollback to savepoint sp_actor;`);
    throw err;
  } finally {
    try {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', true);`);
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

// 1. Load entire migration chain from 062 to 078 in order
const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62)
  .sort();

console.log(`=== LOADING ACTIVE MIGRATIONS (062 TO 078) ===`);
for (const name of migrationFiles) {
  console.log(`Applying ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

// 2. Set up test actors
const admin1 = '11111111-1111-4111-8111-111111111111';
const admin2 = '11111111-2222-4111-8111-222222222222';
const lockedAdmin = '11111111-3333-4111-8111-333333333333';
const mod1 = '22222222-1111-4222-8222-111111111111';
const owner1 = '33333333-1111-4333-8333-111111111111';
const member1 = '44444444-1111-4444-8444-111111111111';

await db.exec(`
  insert into auth.users (id, email) values
    ('${admin1}', 'admin1@test.local'),
    ('${admin2}', 'admin2@test.local'),
    ('${lockedAdmin}', 'locked@test.local'),
    ('${mod1}', 'mod1@test.local'),
    ('${owner1}', 'owner1@test.local'),
    ('${member1}', 'member1@test.local')
  on conflict (id) do nothing;

  update public.profiles set role = 'admin', is_locked = false where id in ('${admin1}', '${admin2}');
  update public.profiles set role = 'admin', is_locked = true where id = '${lockedAdmin}';
  update public.profiles set role = 'moderator', is_locked = false where id = '${mod1}';
  update public.profiles set role = 'member', is_locked = false, credits = 1000 where id in ('${owner1}', '${member1}');
`);

// ----------------------------------------------------------------------------
// Test 1: missing_google_group creates atomic notification + popup state + audit
// ----------------------------------------------------------------------------
await test('missing_google_group pauses campaign, registers popup state, and logs audit', async () => {
  const campId = 'aaaaaaaa-1111-4aaa-8aaa-111111111111';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Super App', 'com.super.app', 'open')
    on conflict (id) do update set status = 'open', paused_reason = null, admin_pause_reason_code = null, admin_note = null;
  `);

  const res = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group') as r;
  `)).rows[0].r;

  assert.equal(res.success, true);
  assert.equal(res.changed, true);
  assert.equal(res.status, 'paused');
  assert.equal(res.paused_reason, 'admin_manual');
  assert.equal(res.admin_pause_reason_code, 'missing_google_group');
  assert.equal(res.admin_note, 'Chưa thêm Google Group');
  assert.ok(res.notification_id, 'Notification ID must be returned');

  // Verify campaign row
  const campRow = (await db.query(`select * from public.campaigns where id = '${campId}'`)).rows[0];
  assert.equal(campRow.status, 'paused');
  assert.equal(campRow.paused_reason, 'admin_manual');
  assert.equal(campRow.admin_pause_reason_code, 'missing_google_group');
  assert.equal(campRow.admin_note, 'Chưa thêm Google Group');
  assert.equal(campRow.admin_resumed, false);

  // Verify notification
  const notifRow = (await db.query(`select * from public.notifications where id = '${res.notification_id}'`)).rows[0];
  assert.ok(notifRow);
  assert.equal(notifRow.user_id, owner1);
  assert.equal(notifRow.type, 'campaign');
  assert.equal(notifRow.title, 'Ứng dụng đã bị tạm dừng bởi Admin ⏸');
  assert.equal(
    notifRow.message,
    'Ứng dụng "Super App" của bạn đã bị tạm dừng do chưa thêm Google Group của nhóm khiến các Tester không tải được ứng dụng của bạn để test. Vui lòng thêm Google Group của nhóm (theo hướng dẫn trong app) để tiếp tục tham gia Test.'
  );
  assert.equal(notifRow.data.reason, 'admin_pause');
  assert.equal(notifRow.data.sender, 'admin');
  assert.equal(notifRow.data.broadcast, false);
  assert.equal(notifRow.data.campaign_id, campId);
  assert.equal(notifRow.data.reason_code, 'missing_google_group');

  // Verify notification popup state
  const popupRow = (await db.query(`select * from public.notification_popup_states where notification_id = '${res.notification_id}'`)).rows[0];
  assert.ok(popupRow, 'Popup state must be registered');
  assert.equal(popupRow.display_count, 0);

  // Verify audit event
  const auditRow = (await db.query(`
    select * from public.admin_action_events
    where action = 'pause_campaign' and target_id = '${campId}'
    order by created_at desc limit 1
  `)).rows[0];
  assert.ok(auditRow);
  assert.equal(auditRow.actor_id, admin1);
  assert.equal(auditRow.reason, 'missing_google_group');
  assert.equal(auditRow.old_state.status, 'open');
  assert.equal(auditRow.new_state.admin_pause_reason_code, 'missing_google_group');
});

// ----------------------------------------------------------------------------
// Test 2: policy_violation creates atomic notification + popup state + audit
// ----------------------------------------------------------------------------
await test('policy_violation pauses campaign, sets exact message, registers popup state', async () => {
  const campId = 'aaaaaaaa-2222-4aaa-8aaa-222222222222';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Policy App', 'com.policy.app', 'open')
    on conflict (id) do update set status = 'open', paused_reason = null, admin_pause_reason_code = null, admin_note = null;
  `);

  const res = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'policy_violation') as r;
  `)).rows[0].r;

  assert.equal(res.success, true);
  assert.equal(res.changed, true);
  assert.equal(res.status, 'paused');
  assert.equal(res.paused_reason, 'admin_manual');
  assert.equal(res.admin_pause_reason_code, 'policy_violation');
  assert.equal(res.admin_note, 'Vi phạm chính sách');

  // Verify exact message for policy_violation (does not include app name in body)
  const notifRow = (await db.query(`select * from public.notifications where id = '${res.notification_id}'`)).rows[0];
  assert.ok(notifRow);
  assert.equal(notifRow.title, 'Ứng dụng đã bị tạm dừng bởi Admin ⏸');
  assert.equal(
    notifRow.message,
    'Ứng dụng của bạn đã bị tạm dừng do vi phạm chính sách, vui lòng liên hệ Admin để được mở lại Test.'
  );
  assert.equal(notifRow.data.reason_code, 'policy_violation');

  const popupRow = (await db.query(`select * from public.notification_popup_states where notification_id = '${res.notification_id}'`)).rows[0];
  assert.ok(popupRow);
  assert.equal(popupRow.display_count, 0);
});

// ----------------------------------------------------------------------------
// Test 3: Special characters, quotes, and empty app_name fallback
// ----------------------------------------------------------------------------
await test('App name special characters and fallback when app_name is empty', async () => {
  // A: Special characters and quotes
  const campSpecialId = 'aaaaaaaa-3333-4aaa-8aaa-333333333333';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campSpecialId}', '${owner1}', 'App "Quotes" & ''Symbols'' <Vn: Tiếng Việt 100%>', 'com.quotes.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  const resSpecial = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campSpecialId}', 'missing_google_group') as r;
  `)).rows[0].r;
  assert.equal(resSpecial.success, true);

  const notifSpecial = (await db.query(`select message from public.notifications where id = '${resSpecial.notification_id}'`)).rows[0];
  assert.equal(
    notifSpecial.message,
    'Ứng dụng "App "Quotes" & \'Symbols\' <Vn: Tiếng Việt 100%>" của bạn đã bị tạm dừng do chưa thêm Google Group của nhóm khiến các Tester không tải được ứng dụng của bạn để test. Vui lòng thêm Google Group của nhóm (theo hướng dẫn trong app) để tiếp tục tham gia Test.'
  );

  // B: Empty app name fallback
  const campEmptyId = 'aaaaaaaa-4444-4aaa-8aaa-444444444444';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campEmptyId}', '${owner1}', '   ', 'com.empty.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  const resEmpty = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campEmptyId}', 'missing_google_group') as r;
  `)).rows[0].r;
  assert.equal(resEmpty.success, true);

  const notifEmpty = (await db.query(`select message from public.notifications where id = '${resEmpty.notification_id}'`)).rows[0];
  assert.equal(
    notifEmpty.message,
    'Ứng dụng của bạn đã bị tạm dừng do chưa thêm Google Group của nhóm khiến các Tester không tải được ứng dụng của bạn để test. Vui lòng thêm Google Group của nhóm (theo hướng dẫn trong app) để tiếp tục tham gia Test.'
  );
});

// ----------------------------------------------------------------------------
// Test 4: Authorization and validation guards
// ----------------------------------------------------------------------------
await test('Permissions check: member, mod, locked admin, invalid reason code rejected', async () => {
  const campId = 'aaaaaaaa-5555-4aaa-8aaa-555555555555';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Guard App', 'com.guard.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  // Member
  await assert.rejects(
    async () => asActor(member1, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`),
    /PERMISSION_DENIED/
  );

  // Moderator
  await assert.rejects(
    async () => asActor(mod1, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`),
    /PERMISSION_DENIED/
  );

  // Locked Admin
  await assert.rejects(
    async () => asActor(lockedAdmin, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`),
    /PERMISSION_DENIED/
  );

  // Anon role: execution denied at SQL privilege level
  await assert.rejects(
    async () => asActor(null, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`, 'anon'),
    /permission denied for function/
  );

  // Authenticated without uid
  await assert.rejects(
    async () => asActor(null, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`, 'authenticated'),
    /NOT_AUTHENTICATED/
  );

  // Invalid reason code
  await assert.rejects(
    async () => asActor(admin1, `select public.admin_pause_campaign_with_reason('${campId}', 'invalid_code')`),
    /INVALID_REASON_CODE/
  );

  // Non-existent campaign
  await assert.rejects(
    async () => asActor(admin1, `select public.admin_pause_campaign_with_reason('00000000-0000-0000-0000-000000000000', 'missing_google_group')`),
    /CAMPAIGN_NOT_FOUND/
  );

  // Finalized (completed or production) campaign
  await db.exec(`update public.campaigns set status = 'completed' where id = '${campId}'`);
  await assert.rejects(
    async () => asActor(admin1, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`),
    /CAMPAIGN_FINALIZED/
  );
});

// ----------------------------------------------------------------------------
// Test 5: Client direct modification of admin_pause_reason_code is blocked
// ----------------------------------------------------------------------------
await test('Direct client modification of admin_pause_reason_code is prevented', async () => {
  const campId = 'aaaaaaaa-6666-4aaa-8aaa-666666666666';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Direct Write App', 'com.direct.app', 'open')
    on conflict (id) do update set status = 'open', admin_pause_reason_code = null;
  `);

  // Direct INSERT by client with admin_pause_reason_code
  await assert.rejects(
    async () => asActor(owner1, `
      insert into public.campaigns (id, owner_id, app_name, package_name, admin_pause_reason_code)
      values (gen_random_uuid(), '${owner1}', 'Tampered', 'com.tamper.app', 'missing_google_group');
    `),
    /PERMISSION_DENIED/
  );

  // Direct UPDATE by client modifying admin_pause_reason_code
  await assert.rejects(
    async () => asActor(owner1, `
      update public.campaigns
      set admin_pause_reason_code = 'policy_violation'
      where id = '${campId}';
    `),
    /PERMISSION_DENIED/
  );
});

// ----------------------------------------------------------------------------
// Test 6: Idempotency / No-op retry handling
// ----------------------------------------------------------------------------
await test('Calling admin_pause_campaign_with_reason with same reason code is no-op', async () => {
  const campId = 'aaaaaaaa-7777-4aaa-8aaa-777777777777';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Idempotent App', 'com.idem.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  // First call
  const firstRes = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group') as r;
  `)).rows[0].r;
  assert.equal(firstRes.changed, true);
  assert.ok(firstRes.notification_id);

  const notifCount1 = (await db.query(`select count(*) as c from public.notifications where data->>'campaign_id' = '${campId}'`)).rows[0].c;
  assert.equal(Number(notifCount1), 1);

  const auditCount1 = (await db.query(`select count(*) as c from public.admin_action_events where target_id = '${campId}'`)).rows[0].c;
  assert.equal(Number(auditCount1), 1);

  // Second call with same reason code (retry/double-tap)
  const secondRes = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group') as r;
  `)).rows[0].r;
  assert.equal(secondRes.success, true);
  assert.equal(secondRes.changed, false);
  assert.equal(secondRes.notification_id, null);

  // Ensure notification count and audit count did NOT increase
  const notifCount2 = (await db.query(`select count(*) as c from public.notifications where data->>'campaign_id' = '${campId}'`)).rows[0].c;
  assert.equal(Number(notifCount2), 1);

  const auditCount2 = (await db.query(`select count(*) as c from public.admin_action_events where target_id = '${campId}'`)).rows[0].c;
  assert.equal(Number(auditCount2), 1);

  // Delete notification or mark read, retry same reason -> still no-op!
  await db.exec(`delete from public.notifications where id = '${firstRes.notification_id}'`);
  const thirdRes = (await asActor(admin2, `
    select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group') as r;
  `)).rows[0].r;
  assert.equal(thirdRes.success, true);
  assert.equal(thirdRes.changed, false);
  const notifCount3 = (await db.query(`select count(*) as c from public.notifications where data->>'campaign_id' = '${campId}'`)).rows[0].c;
  assert.equal(Number(notifCount3), 0);
});

// ----------------------------------------------------------------------------
// Test 7: Switching reason creates a new notification and updates campaign
// ----------------------------------------------------------------------------
await test('Switching from missing_google_group to policy_violation creates new notification', async () => {
  const campId = 'aaaaaaaa-8888-4aaa-8aaa-888888888888';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Switch App', 'com.switch.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  // First: missing_google_group
  const res1 = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group') as r;
  `)).rows[0].r;
  assert.equal(res1.changed, true);

  // Second: switch to policy_violation
  const res2 = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'policy_violation') as r;
  `)).rows[0].r;
  assert.equal(res2.changed, true);
  assert.equal(res2.admin_pause_reason_code, 'policy_violation');
  assert.notEqual(res2.notification_id, res1.notification_id);

  // Both notifications exist
  const notifs = (await db.query(`
    select id, data->>'reason_code' as code from public.notifications
    where data->>'campaign_id' = '${campId}'
    order by created_at asc
  `)).rows;
  assert.equal(notifs.length, 2);
  assert.equal(notifs[0].code, 'missing_google_group');
  assert.equal(notifs[1].code, 'policy_violation');

  // Both registered in popup states
  const popups = (await db.query(`
    select notification_id from public.notification_popup_states
    where notification_id in ('${notifs[0].id}', '${notifs[1].id}')
  `)).rows;
  assert.equal(popups.length, 2);
});

// ----------------------------------------------------------------------------
// Test 8: Reopen / Resume clears reason code and allows subsequent pause
// ----------------------------------------------------------------------------
await test('admin_resume_campaign clears reason code, subsequent pause creates new notification', async () => {
  const campId = 'aaaaaaaa-9999-4aaa-8aaa-999999999999';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Reopen App', 'com.reopen.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  // Pause
  await asActor(admin1, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`);

  // Resume
  const resumeRes = (await asActor(admin1, `select public.admin_resume_campaign('${campId}') as r`)).rows[0].r;
  assert.equal(resumeRes.success, true);
  assert.equal(resumeRes.status, 'open');

  const campAfterResume = (await db.query(`select * from public.campaigns where id = '${campId}'`)).rows[0];
  assert.equal(campAfterResume.status, 'open');
  assert.equal(campAfterResume.paused_reason, null);
  assert.equal(campAfterResume.admin_pause_reason_code, null);
  assert.equal(campAfterResume.admin_note, null);

  // Pause again -> creates new notification
  const pauseAgainRes = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group') as r;
  `)).rows[0].r;
  assert.equal(pauseAgainRes.success, true);
  assert.equal(pauseAgainRes.changed, true);
  assert.ok(pauseAgainRes.notification_id);
});

// ----------------------------------------------------------------------------
// Test 9: Legacy RPC admin_pause_campaign(uuid, text) compatibility
// ----------------------------------------------------------------------------
await test('Legacy admin_pause_campaign(uuid, text) sets custom note and does not register popup state', async () => {
  const campId = 'aaaaaaaa-baaa-4aaa-8aaa-baaaaaaaaaaa';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Legacy App', 'com.legacy.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  const res = (await asActor(admin1, `
    select public.admin_pause_campaign('${campId}', 'Custom legacy reason note') as r;
  `)).rows[0].r;

  assert.equal(res.success, true);
  assert.equal(res.status, 'paused');
  assert.equal(res.paused_reason, 'admin_manual');
  assert.equal(res.admin_note, 'Custom legacy reason note');

  const camp = (await db.query(`select * from public.campaigns where id = '${campId}'`)).rows[0];
  assert.equal(camp.admin_pause_reason_code, null);
  assert.equal(camp.admin_note, 'Custom legacy reason note');

  // Verify notification created
  const notif = (await db.query(`select * from public.notifications where data->>'campaign_id' = '${campId}'`)).rows[0];
  assert.ok(notif);
  assert.equal(notif.message.includes('Lý do: Custom legacy reason note'), true);

  // Popup state must NOT be registered for legacy custom pause!
  const popup = (await db.query(`select * from public.notification_popup_states where notification_id = '${notif.id}'`)).rows[0];
  assert.equal(popup, undefined, 'Custom reason must NOT register popup state');
});

// ----------------------------------------------------------------------------
// Test 10: 30-day automatic completion works on paused campaign with reason code
// ----------------------------------------------------------------------------
await test('Automatic 30-day completion succeeds without constraint error on paused campaign', async () => {
  const campId = 'aaaaaaaa-caaa-4aaa-8aaa-caaaaaaaaaaa';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status, created_at)
    values ('${campId}', '${owner1}', 'Old App', 'com.old.app', 'open', now() - interval '31 days')
    on conflict (id) do update set status = 'open', created_at = now() - interval '31 days';
  `);

  // Pause with missing_google_group
  await asActor(admin1, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`);

  // Run complete_expired_campaigns
  const completedCount = (await db.query(`select public.complete_expired_campaigns() as count`)).rows[0].count;
  assert.ok(completedCount >= 1);

  const camp = (await db.query(`select * from public.campaigns where id = '${campId}'`)).rows[0];
  assert.equal(camp.status, 'completed');
  // Constraint does NOT require status = 'paused' when admin_pause_reason_code is present!
  assert.equal(camp.admin_pause_reason_code, 'missing_google_group');
});

// ----------------------------------------------------------------------------
// Test 11: Popup claim flow integration
// ----------------------------------------------------------------------------
await test('claim_my_notification_popup claims paused campaign popup for owner, stops on mark read', async () => {
  const campId = 'aaaaaaaa-daaa-4aaa-8aaa-daaaaaaaaaaa';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Popup Claim App', 'com.claim.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  const pauseRes = (await asActor(admin1, `
    select public.admin_pause_campaign_with_reason('${campId}', 'policy_violation') as r;
  `)).rows[0].r;

  const notifId = pauseRes.notification_id;

  // Session 1: owner claims popup
  const session1 = '55555555-1111-4555-8555-111111111111';
  const claimRes1 = (await asActor(owner1, `
    select public.claim_my_notification_popup('${session1}') as r;
  `)).rows[0].r;

  assert.equal(claimRes1.status, 'claimed');
  assert.equal(claimRes1.notification.id, notifId);
  assert.equal(claimRes1.display_count, 1);
  assert.equal(claimRes1.notification.title, 'Ứng dụng đã bị tạm dừng bởi Admin ⏸');

  // Non-owner member claims popup -> none
  const memberClaim = (await asActor(member1, `
    select public.claim_my_notification_popup(gen_random_uuid()) as r;
  `)).rows[0].r;
  assert.equal(memberClaim.status, 'none');

  // Same session reclaims -> idempotently returns claimed
  const reClaim = (await asActor(owner1, `
    select public.claim_my_notification_popup('${session1}') as r;
  `)).rows[0].r;
  assert.equal(reClaim.status, 'claimed');
  assert.equal(reClaim.display_count, 1);

  // Subsequent sessions increment display_count
  for (let d = 2; d <= 5; d++) {
    const sId = genSessionId(d);
    const cRes = (await asActor(owner1, `
      select public.claim_my_notification_popup('${sId}') as r;
    `)).rows[0].r;
    assert.equal(cRes.status, 'claimed');
    assert.equal(cRes.display_count, d);
  }

  // 6th view attempt -> max reached, status is none
  const session6 = genSessionId(6);
  const claimRes6 = (await asActor(owner1, `
    select public.claim_my_notification_popup('${session6}') as r;
  `)).rows[0].r;
  assert.equal(claimRes6.status, 'none');
});

function genSessionId(n) {
  return `66666666-${String(n).padStart(4, '0')}-4666-8666-666666666666`;
}

// ----------------------------------------------------------------------------
// Test 12: Internal function privilege revocation
// ----------------------------------------------------------------------------
await test('Internal functions _impl_admin_pause_campaign_core & _impl_admin_pause_campaign denied to client roles', async () => {
  const funcs = [
    '_impl_admin_pause_campaign_core(uuid, text, text)',
    '_impl_admin_pause_campaign(uuid, text)',
    '_impl_admin_resume_campaign(uuid)'
  ];

  for (const fn of funcs) {
    const privAuth = (await db.query(`select has_function_privilege('authenticated', 'public.${fn}', 'EXECUTE') as priv`)).rows[0].priv;
    assert.equal(privAuth, false, `authenticated must not have EXECUTE on ${fn}`);

    const privAnon = (await db.query(`select has_function_privilege('anon', 'public.${fn}', 'EXECUTE') as priv`)).rows[0].priv;
    assert.equal(privAnon, false, `anon must not have EXECUTE on ${fn}`);
  }
});

// ----------------------------------------------------------------------------
// Test 13: Atomicity: failure during popup state insert rolls back entire pause
// ----------------------------------------------------------------------------
await test('Atomicity: failure during popup creation rolls back campaign update & notification', async () => {
  const campId = 'aaaaaaaa-eaee-4aaa-8aaa-eaeeeeeeeeee';
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campId}', '${owner1}', 'Atomic App', 'com.atomic.app', 'open')
    on conflict (id) do update set status = 'open';
  `);

  await db.exec(`
    create or replace function public.fail_popup_insert()
    returns trigger language plpgsql as $$
    begin
      if new.notification_id in (select id from public.notifications where data->>'campaign_id' = '${campId}') then
        raise exception 'SIMULATED_POPUP_FAILURE';
      end if;
      return new;
    end;
    $$;
    drop trigger if exists trg_simulated_popup_fail on public.notification_popup_states;
    create trigger trg_simulated_popup_fail
    before insert on public.notification_popup_states
    for each row execute function public.fail_popup_insert();
  `);

  try {
    await assert.rejects(
      async () => asActor(admin1, `select public.admin_pause_campaign_with_reason('${campId}', 'missing_google_group')`),
      /SIMULATED_POPUP_FAILURE/
    );

    const camp = (await db.query(`select status, admin_pause_reason_code from public.campaigns where id = '${campId}'`)).rows[0];
    assert.equal(camp.status, 'open', 'Campaign must NOT remain paused if popup state creation failed');
    assert.equal(camp.admin_pause_reason_code, null);

    const notifs = (await db.query(`select count(*) as c from public.notifications where data->>'campaign_id' = '${campId}'`)).rows[0].c;
    assert.equal(Number(notifs), 0, 'No notification should exist on rollback');
  } finally {
    await db.exec(`
      drop trigger if exists trg_simulated_popup_fail on public.notification_popup_states;
      drop function if exists public.fail_popup_insert();
    `);
  }
});

console.log('=== ALL ADMIN PAUSE REASON POPUP TESTS PASSED ===');
