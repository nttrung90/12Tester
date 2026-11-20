import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING OWNER AUTO-PAUSE CONFIG & ENFORCEMENT TESTS (MIGRATION 075) ===');

const db = await createDb({ legacyGrants: true });

try {
  // Load migrations 062 to 075 (historical baseline for Migration 075 suite)
  const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x) && Number(x.slice(0, 3)) >= 62 && Number(x.slice(0, 3)) <= 75)
    .sort();

  for (const filename of migrationFiles) {
    await db.exec(read('supabase/migrations/' + filename));
  }

  const adminId = '11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const admin2Id = '11111111-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  const modId = '22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const lockedAdminId = '33333333-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const ownerId = '44444444-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const owner2Id = '44444444-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  const owner3Id = '44444444-eeee-eeee-eeee-eeeeeeeeeeee';
  const ownerYoungId = '44444444-cccc-cccc-cccc-cccccccccccc';
  const ownerResumedId = '44444444-dddd-dddd-dddd-dddddddddddd';
  const testerId = '55555555-aaaa-aaaa-aaaa-aaaaaaaaaaaa';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${adminId}', 'admin@test.local'),
      ('${admin2Id}', 'admin2@test.local'),
      ('${modId}', 'mod@test.local'),
      ('${lockedAdminId}', 'lockedadmin@test.local'),
      ('${ownerId}', 'owner@test.local'),
      ('${owner2Id}', 'owner2@test.local'),
      ('${owner3Id}', 'owner3@test.local'),
      ('${ownerYoungId}', 'owneryoung@test.local'),
      ('${ownerResumedId}', 'ownerresumed@test.local'),
      ('${testerId}', 'tester@test.local');

    update public.profiles set role = 'admin', is_locked = false where id = '${adminId}';
    update public.profiles set role = 'admin', is_locked = false where id = '${admin2Id}';
    update public.profiles set role = 'moderator', is_locked = false where id = '${modId}';
    update public.profiles set role = 'admin', is_locked = true where id = '${lockedAdminId}';
    update public.profiles set role = 'member', is_locked = false, credits = 1000 where id in ('${ownerId}', '${owner2Id}', '${owner3Id}', '${ownerYoungId}', '${ownerResumedId}');
    update public.profiles set role = 'member', is_locked = false, credits = 500 where id = '${testerId}';

    -- Set coverage start to 10 days ago so history_ready is true
    update public.system_provenance_markers
    set marker_time = now() - interval '10 days', marker_date = (now() at time zone 'Asia/Ho_Chi_Minh')::date - 10
    where marker_key = 'coverage_start';

    -- Seal yesterday in user_daily_tasks so carryover_ready is true
    insert into public.user_daily_tasks (
      user_id, task_date, mode, active_app_count, required_tests, effective_required_tests,
      available_apps, completed_tests, logged_in, is_exempt, met, missed_streak,
      penalty_credits, finalized_at, base_required_tests, carry_in_tests, carry_out_tests
    )
    values
      ('${ownerId}', (now() at time zone 'Asia/Ho_Chi_Minh')::date - 1, 'owner', 1, 1, 1, 1, 0, true, false, false, 6, 0, now(), 1, 0, 1),
      ('${owner2Id}', (now() at time zone 'Asia/Ho_Chi_Minh')::date - 1, 'owner', 1, 1, 1, 1, 0, true, false, false, 0, 0, now(), 1, 0, 1),
      ('${ownerYoungId}', (now() at time zone 'Asia/Ho_Chi_Minh')::date - 1, 'owner', 1, 1, 1, 1, 0, true, false, false, 7, 0, now(), 1, 0, 1),
      ('${ownerResumedId}', (now() at time zone 'Asia/Ho_Chi_Minh')::date - 1, 'owner', 1, 1, 1, 1, 0, true, false, false, 7, 0, now(), 1, 0, 1)
    on conflict (user_id, task_date) do update set finalized_at = now();
  `);

  async function asUser(userId, fn) {
    if (userId) {
      await db.exec(`select set_config('request.jwt.claim.sub', '${userId}', false); set role authenticated;`);
    } else {
      await db.exec(`select set_config('request.jwt.claim.sub', '', false); set role anon;`);
    }
    try {
      return await fn();
    } finally {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', false);`);
    }
  }

  // =========================================================================
  // 1. SQL PERMISSIONS & CONFIG VALIDATION
  // =========================================================================
  console.log('\n--- Test 1: Config RPCs, Validations, and Permissions ---');

  // Initial read by admin
  let cfg;
  await asUser(adminId, async () => {
    const res = await db.query(`select public.admin_get_owner_auto_pause_config() as cfg;`);
    cfg = res.rows[0].cfg;
  });
  assert.equal(cfg.missed_days, 7, 'Default threshold must be 7');
  assert.ok(cfg.revision, 'Revision must be present');
  console.log('PASS: Default config reads 7 missed_days with revision');

  // Read permission checks
  for (const [name, u] of [['Member', ownerId], ['Moderator', modId], ['Locked Admin', lockedAdminId], ['Anon', null]]) {
    await asUser(u, async () => {
      await assert.rejects(
        () => db.query(`select public.admin_get_owner_auto_pause_config();`),
        /PERMISSION_DENIED|NOT_ADMIN|FORBIDDEN|UNAUTHORIZED|ACCOUNT_LOCKED|permission denied/i,
        `${name} must not be able to read owner auto-pause config`
      );
    });
  }
  console.log('PASS: Non-admin users cannot call admin_get_owner_auto_pause_config');

  // Direct table writes denied to client
  await asUser(ownerId, async () => {
    await assert.rejects(
      () => db.query(`update public.owner_auto_pause_configs set missed_days = 3 where id = 'default';`),
      /permission denied/i,
      'Direct UPDATE on owner_auto_pause_configs must be denied to authenticated'
    );
  });
  console.log('PASS: Direct table writes denied to authenticated client');

  // Validation checks: 0, 8, null, missing revision
  for (const badDays of [0, 8, -1, 10, null]) {
    await asUser(adminId, async () => {
      await assert.rejects(
        () => db.query(`select public.admin_set_owner_auto_pause_config(${badDays === null ? 'null' : badDays}, '${cfg.revision}');`),
        /INVALID_MISSED_DAYS/i,
        `missed_days=${badDays} must be rejected`
      );
    });
  }
  await asUser(adminId, async () => {
    await assert.rejects(
      () => db.query(`select public.admin_set_owner_auto_pause_config(3, null);`),
      /MISSING_REVISION/i,
      'Null revision must be rejected'
    );
  });
  console.log('PASS: Input validation correctly rejects invalid threshold & revision');

  // Concurrency conflict test
  await asUser(adminId, async () => {
    await assert.rejects(
      () => db.query(`select public.admin_set_owner_auto_pause_config(3, 'wrong-rev');`),
      /CONCURRENCY_CONFLICT/i,
      'Stale revision must trigger concurrency conflict'
    );
  });
  console.log('PASS: Stale revision rejected with CONCURRENCY_CONFLICT');

  // Successful update to 3
  let updateRes;
  await asUser(adminId, async () => {
    const res = await db.query(`select public.admin_set_owner_auto_pause_config(3, '${cfg.revision}') as res;`);
    updateRes = res.rows[0].res;
  });
  assert.equal(updateRes.success, true);
  assert.equal(updateRes.missed_days, 3);
  assert.equal(updateRes.previous_missed_days, 7);
  assert.notEqual(updateRes.revision, cfg.revision);
  console.log('PASS: Admin successfully updated threshold to 3');

  // Audit event recorded
  const auditRes = await db.query(`
    select * from public.admin_action_events
    where action = 'set_owner_auto_pause_config'
    order by created_at desc limit 1;
  `);
  assert.equal(auditRes.rows.length, 1);
  assert.equal(auditRes.rows[0].actor_id, adminId);
  assert.equal(auditRes.rows[0].old_state.missed_days, 7);
  assert.equal(auditRes.rows[0].new_state.missed_days, 3);
  console.log('PASS: Audit event logged with correct actor, old_state, and new_state');

  // Reset back to 7 for subsequent tests
  await asUser(adminId, async () => {
    await db.query(`select public.admin_set_owner_auto_pause_config(7, '${updateRes.revision}');`);
  });

  // =========================================================================
  // 2. PATH 1: owner_inactive PAUSE & THRESHOLD BEHAVIOR
  // =========================================================================
  console.log('\n--- Test 2: Path 1 (owner_inactive) and Threshold Checks ---');

  const camp1 = 'c1111111-1111-1111-1111-111111111111';
  const camp1Slot2 = 'c1111111-2222-2222-2222-222222222222';
  const campYoung = 'c2222222-2222-2222-2222-222222222222';
  const campAdmin = 'c3333333-3333-3333-3333-333333333333';
  const campAdminResumed = 'c4444444-4444-4444-4444-444444444444';

  await db.exec(`
    -- camp1: > 24 hours old (ownerId, slot 1)
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${camp1}', '${ownerId}', 'com.test.app1', 'App 1', 'open', 1, now() - interval '25 hours');

    -- camp1Slot2: ownerId has a 2nd app on slot 2 (valid slot)
    insert into public.user_slots (user_id, slot_number, expires_at)
    values ('${ownerId}', 2, now() + interval '30 days');

    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${camp1Slot2}', '${ownerId}', 'com.test.app1.slot2', 'App 1 Slot 2', 'open', 2, now() - interval '25 hours');

    -- campYoung: < 24 hours old
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${campYoung}', '${ownerYoungId}', 'com.test.young', 'Young App', 'open', 1, now() - interval '10 hours');

    -- campAdmin: owned by admin
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${campAdmin}', '${adminId}', 'com.test.adminapp', 'Admin App', 'open', 1, now() - interval '25 hours');

    -- campAdminResumed: flag admin_resumed = true
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at, admin_resumed)
    values ('${campAdminResumed}', '${ownerResumedId}', 'com.test.adminresumed', 'Resumed App', 'open', 1, now() - interval '25 hours', true);
  `);

  // Owner streak is 6, threshold is 7 -> NOT paused
  await db.exec(`
    update public.profiles set daily_missed_streak = 6 where id = '${ownerId}';
    update public.profiles set daily_missed_streak = 7 where id in ('${ownerYoungId}', '${adminId}', '${ownerResumedId}');
  `);
  let pCount = await db.query(`select public.pause_inactive_owner_campaigns() as count;`);
  assert.equal(pCount.rows[0].count, 0, 'Streak 6 < 7 should not pause');
  let c1Check = await db.query(`select status from public.campaigns where id = '${camp1}';`);
  assert.equal(c1Check.rows[0].status, 'open');
  console.log('PASS: Streak 6 < threshold 7 does not pause');

  // Owner streak is 7 -> BOTH camp1 and camp1Slot2 of ownerId are paused!
  // campYoung (< 24h), campAdmin (admin), and campAdminResumed (admin_resumed) are spared!
  await db.exec(`update public.profiles set daily_missed_streak = 7 where id = '${ownerId}';`);
  pCount = await db.query(`select public.pause_inactive_owner_campaigns() as count;`);
  assert.equal(pCount.rows[0].count, 2, 'Both apps of ownerId should be paused');

  c1Check = await db.query(`select status, paused_reason from public.campaigns where id = '${camp1}';`);
  assert.equal(c1Check.rows[0].status, 'paused');
  assert.equal(c1Check.rows[0].paused_reason, 'owner_inactive');

  const c1Slot2Check = await db.query(`select status, paused_reason from public.campaigns where id = '${camp1Slot2}';`);
  assert.equal(c1Slot2Check.rows[0].status, 'paused');
  assert.equal(c1Slot2Check.rows[0].paused_reason, 'owner_inactive');

  const youngCheck = await db.query(`select status from public.campaigns where id = '${campYoung}';`);
  assert.equal(youngCheck.rows[0].status, 'open', '<24h app must remain open');

  const adminAppCheck = await db.query(`select status from public.campaigns where id = '${campAdmin}';`);
  assert.equal(adminAppCheck.rows[0].status, 'open', 'Admin app must remain open');

  const resumedAppCheck = await db.query(`select status from public.campaigns where id = '${campAdminResumed}';`);
  assert.equal(resumedAppCheck.rows[0].status, 'open', 'admin_resumed app must remain open');

  // Verify notification contains threshold_days = 7
  const notifRes = await db.query(`
    select * from public.notifications
    where user_id = '${ownerId}' and type = 'campaign'
    order by created_at desc limit 1;
  `);
  assert.equal(notifRes.rows.length, 1);
  assert.equal(notifRes.rows[0].data.reason, 'owner_inactive');
  assert.equal(notifRes.rows[0].data.threshold_days, 7);
  assert.ok(notifRes.rows[0].message.includes('7 ngày liên tục'));
  console.log('PASS: Notification saved with threshold_days = 7 and formatted message');

  // Second sync run does not produce duplicate notification or duplicate count
  pCount = await db.query(`select public.pause_inactive_owner_campaigns() as count;`);
  assert.equal(pCount.rows[0].count, 0, 'Second run should pause 0 campaigns');
  const notifCount = await db.query(`select count(*) as c from public.notifications where user_id = '${ownerId}' and type = 'campaign';`);
  assert.equal(notifCount.rows[0].c, 2, 'No duplicate notification generated on 2nd sync');
  console.log('PASS: Idempotent sync produces no duplicate notifications');

  // Test dynamic threshold 3:
  // Reset camp1 to open with streak 3
  await db.exec(`
    update public.campaigns set status = 'open', paused_reason = null where id = '${camp1}';
    update public.profiles set daily_missed_streak = 3 where id = '${ownerId}';
  `);
  // Get current revision and set threshold to 3
  let curCfg;
  await asUser(adminId, async () => {
    curCfg = (await db.query(`select public.admin_get_owner_auto_pause_config() as c;`)).rows[0].c;
    await db.query(`select public.admin_set_owner_auto_pause_config(3, '${curCfg.revision}');`);
  });

  pCount = await db.query(`select public.pause_inactive_owner_campaigns() as count;`);
  assert.equal(pCount.rows[0].count, 1, 'Should pause at new threshold 3');
  const notif3 = (await db.query(`select * from public.notifications order by created_at desc limit 1;`)).rows[0];
  assert.equal(notif3.data.threshold_days, 3);
  assert.ok(notif3.message.includes('3 ngày liên tục'));
  console.log('PASS: Configurable threshold 3 pauses streak 3 and stores threshold_days = 3');

  const atThree = await asUser(adminId, async () =>
    (await db.query('select public.admin_get_owner_auto_pause_config() as config')).rows[0].config
  );
  const atOne = await asUser(adminId, async () =>
    (await db.query('select public.admin_set_owner_auto_pause_config(1, $1) as config', [atThree.revision])).rows[0].config
  );
  assert.equal(atOne.missed_days, 1);
  await asUser(admin2Id, async () => {
    await assert.rejects(
      () => db.query('select public.admin_set_owner_auto_pause_config(2, $1)', [atThree.revision]),
      /CONCURRENCY_CONFLICT/
    );
  });

  await db.exec(`
    update public.campaigns set status = 'open' where id = '${camp1}';
    update public.profiles set daily_missed_streak = 0 where id = '${ownerId}';
  `);
  assert.equal((await db.query('select public.pause_inactive_owner_campaigns() as count')).rows[0].count, 0);
  await db.exec(`update public.profiles set daily_missed_streak = 1 where id = '${ownerId}';`);
  assert.equal((await db.query('select public.pause_inactive_owner_campaigns() as count')).rows[0].count, 1);
  await asUser(adminId, async () => {
    await db.query('select public.admin_set_owner_auto_pause_config(3, $1)', [atOne.revision]);
  });
  console.log('PASS: Exact threshold 1 applies and a second admin cannot overwrite a stale revision');

  // =========================================================================
  // 3. PATH 2: slot_expired PAUSE & RULES
  // =========================================================================
  console.log('\n--- Test 3: Path 2 (slot_expired) ---');

  const campSlot2 = 'c5555555-5555-5555-5555-555555555555';
  const campSlot3Missing = 'c6666666-6666-6666-6666-666666666666';

  await db.exec(`
    -- Seed active slot 2 and slot 3 first so initial campaign creation succeeds
    insert into public.user_slots (user_id, slot_number, expires_at)
    values
      ('${owner2Id}', 2, now() + interval '1 day'),
      ('${owner2Id}', 3, now() + interval '1 day');

    -- campSlot2 on slot 2
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${campSlot2}', '${owner2Id}', 'com.test.slot2', 'Slot 2 App', 'open', 2, now() - interval '2 days');

    -- campSlot3 on slot 3
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${campSlot3Missing}', '${owner2Id}', 'com.test.slot3', 'Slot 3 App', 'open', 3, now() - interval '2 days');

    -- Now simulate expiration of slot 2 and deletion (missing record) of slot 3
    update public.user_slots set expires_at = now() - interval '1 hour' where user_id = '${owner2Id}' and slot_number = 2;
    delete from public.user_slots where user_id = '${owner2Id}' and slot_number = 3;
  `);

  const slotPauseCount = await db.query(`select public.pause_expired_slot_campaigns() as count;`);
  assert.equal(slotPauseCount.rows[0].count, 1, 'Only slot with expired record should be paused');

  const s2Check = (await db.query(`select status, paused_reason from public.campaigns where id = '${campSlot2}';`)).rows[0];
  assert.equal(s2Check.status, 'paused');
  assert.equal(s2Check.paused_reason, 'slot_expired');

  const s3Check = (await db.query(`select status from public.campaigns where id = '${campSlot3Missing}';`)).rows[0];
  assert.equal(s3Check.status, 'open', 'Missing slot record should be skipped');
  console.log('PASS: Expired slot pauses correctly; missing slot record is skipped');

  // =========================================================================
  // 4. PATH 3 & 4: owner_manual & admin_manual
  // =========================================================================
  console.log('\n--- Test 4: Path 3 (owner_manual) & Path 4 (admin_manual) ---');

  const campManual = 'c7777777-7777-7777-7777-777777777777';
  await db.exec(`
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${campManual}', '${owner2Id}', 'com.test.manual', 'Manual App', 'open', 1, now() - interval '2 days');
  `);

  // Owner manual pause via UPDATE
  await asUser(owner2Id, async () => {
    await db.query(`update public.campaigns set status = 'paused', paused_reason = 'owner_manual' where id = '${campManual}';`);
  });
  const mCheck = (await db.query(`select status, paused_reason from public.campaigns where id = '${campManual}';`)).rows[0];
  assert.equal(mCheck.status, 'paused');
  assert.equal(mCheck.paused_reason, 'owner_manual');
  console.log('PASS: Owner manual pause succeeds');

  // Admin pauses an already paused app with reason
  await asUser(adminId, async () => {
    await db.query(`select public.admin_pause_campaign('${campManual}', 'Test violation note');`);
  });
  const aCheck = (await db.query(`select status, paused_reason, admin_note from public.campaigns where id = '${campManual}';`)).rows[0];
  assert.equal(aCheck.status, 'paused');
  assert.equal(aCheck.paused_reason, 'admin_manual');
  assert.equal(aCheck.admin_note, 'Test violation note');
  console.log('PASS: Admin pauses and overrides paused_reason to admin_manual with note');

  // =========================================================================
  // 5. RESUME PATHS & RULES
  // =========================================================================
  console.log('\n--- Test 5: Resume Paths & Constraints ---');

  // Owner cannot resume admin_manual app
  await asUser(owner2Id, async () => {
    await assert.rejects(
      () => db.query(`select public.resume_campaign('${campManual}');`),
      /PAUSED_BY_ADMIN/i,
      'Owner cannot resume app paused by admin'
    );
  });
  console.log('PASS: Owner rejected with PAUSED_BY_ADMIN on admin_manual');

  // Admin resumes admin_manual app
  await asUser(adminId, async () => {
    await db.query(`select public.admin_resume_campaign('${campManual}');`);
  });
  const admResCheck = (await db.query(`select status, paused_reason from public.campaigns where id = '${campManual}';`)).rows[0];
  assert.equal(admResCheck.status, 'open');
  assert.equal(admResCheck.paused_reason, null);
  console.log('PASS: Admin resume succeeds');

  // Admin resuming owner_inactive app sets admin_resumed = true
  await asUser(adminId, async () => {
    await db.query(`select public.admin_resume_campaign('${camp1}');`);
  });
  const admResCheck1 = (await db.query(`select status, admin_resumed from public.campaigns where id = '${camp1}';`)).rows[0];
  assert.equal(admResCheck1.status, 'open');
  assert.equal(admResCheck1.admin_resumed, true);
  console.log('PASS: Admin resuming owner_inactive sets admin_resumed = true');

  // Owner resuming slot_expired before slot renewal -> SLOT_EXPIRED
  await asUser(owner2Id, async () => {
    await assert.rejects(
      () => db.query(`select public.resume_campaign('${campSlot2}');`),
      /SLOT_EXPIRED/i,
      'Owner cannot resume expired slot before renewal'
    );
  });
  console.log('PASS: Owner resume blocked when slot is expired');

  // Renew slot 2 and resume
  await db.exec(`update public.user_slots set expires_at = now() + interval '30 days' where user_id = '${owner2Id}' and slot_number = 2;`);
  await asUser(owner2Id, async () => {
    const res = (await db.query(`select public.resume_campaign('${campSlot2}') as r;`)).rows[0].r;
    assert.equal(res.status, 'open');
  });
  const s2ResCheck = (await db.query(`select status, paused_reason from public.campaigns where id = '${campSlot2}';`)).rows[0];
  assert.equal(s2ResCheck.status, 'open');
  assert.equal(s2ResCheck.paused_reason, null);
  console.log('PASS: Owner resume succeeds after renewing slot');

  // 30-day window check on resume: app >= 30 days completed
  const campOld = 'c8888888-8888-8888-8888-888888888888';
  await db.exec(`
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at, paused_reason)
    values ('${campOld}', '${owner2Id}', 'com.test.old', 'Old App', 'paused', 1, now() - interval '31 days', 'owner_manual');
  `);
  await asUser(owner2Id, async () => {
    const res = (await db.query(`select public.resume_campaign('${campOld}') as r;`)).rows[0].r;
    assert.equal(res.status, 'completed');
  });
  const oldCheck = (await db.query(`select status from public.campaigns where id = '${campOld}';`)).rows[0];
  assert.equal(oldCheck.status, 'completed');
  console.log('PASS: Campaign >= 30 days transitions to completed upon resume');

  // =========================================================================
  // 6. DIRECT UPDATE BYPASS HARDENING ENFORCEMENT
  // =========================================================================
  console.log('\n--- Test 6: Direct UPDATE Bypass Hardening Enforcement ---');

  const campBypass = 'c9999999-9999-9999-9999-999999999999';
  await db.exec(`
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at, paused_reason)
    values ('${campBypass}', '${owner2Id}', 'com.test.bypass', 'Bypass App', 'paused', 1, now() - interval '2 days', 'owner_inactive');
  `);

  // Direct UPDATE paused to open -> BLOCKED
  await asUser(owner2Id, async () => {
    await assert.rejects(
      () => db.query(`update public.campaigns set status = 'open' where id = '${campBypass}';`),
      /DIRECT_RESUME_NOT_ALLOWED/i,
      'Direct paused-to-open update must be rejected'
    );
  });
  console.log('PASS: Direct UPDATE paused-to-open blocked with DIRECT_RESUME_NOT_ALLOWED');

  await asUser(owner2Id, async () => {
    await assert.rejects(
      () => db.query(`update public.campaigns set status = 'closed' where id = '${campBypass}';`),
      /CANNOT_CHANGE_PAUSED_REASON/
    );
  });

  // Disguise system pause reason (changing owner_inactive to owner_manual) -> BLOCKED
  await asUser(owner2Id, async () => {
    await assert.rejects(
      () => db.query(`update public.campaigns set paused_reason = 'owner_manual' where id = '${campBypass}';`),
      /CANNOT_CHANGE_PAUSED_REASON/i,
      'Modifying system paused_reason directly must be rejected'
    );
  });
  console.log('PASS: Disguising system pause reason blocked with CANNOT_CHANGE_PAUSED_REASON');

  // Direct fake system pause reason on open app -> BLOCKED
  const campOpen = 'caaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  await db.exec(`
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
    values ('${campOpen}', '${owner3Id}', 'com.test.open', 'Open App', 'open', 1, now() - interval '2 days');
  `);
  await asUser(owner3Id, async () => {
    await assert.rejects(
      () => db.query(`update public.campaigns set status = 'paused', paused_reason = 'owner_inactive' where id = '${campOpen}';`),
      /PERMISSION_DENIED/i,
      'Client cannot set system pause reasons directly'
    );
  });
  console.log('PASS: Direct fake system pause reason blocked with PERMISSION_DENIED');

  // Legitimate client updates:
  // 1. Updating app metadata while paused
  await asUser(owner2Id, async () => {
    await db.query(`update public.campaigns set app_name = 'Renamed App', description = 'New desc' where id = '${campBypass}';`);
  });
  const metaCheck = (await db.query(`select app_name, description from public.campaigns where id = '${campBypass}';`)).rows[0];
  assert.equal(metaCheck.app_name, 'Renamed App');
  assert.equal(metaCheck.description, 'New desc');
  console.log('PASS: Legitimate app metadata update while paused succeeds');

  // 2. Marking app production while paused
  await asUser(owner2Id, async () => {
    await db.query(`update public.campaigns set status = 'production', paused_reason = null where id = '${campBypass}';`);
  });
  const prodCheck = (await db.query(`select status from public.campaigns where id = '${campBypass}';`)).rows[0];
  assert.equal(prodCheck.status, 'production');
  console.log('PASS: Legitimate mark production flow succeeds');

  await asUser(owner2Id, async () => {
    await assert.rejects(
      () => db.query(`update public.campaigns set status = 'open' where id = '${campBypass}';`),
      /DIRECT_RESUME_NOT_ALLOWED/
    );
  });
  console.log('PASS: Client cannot reopen through closed or Production intermediate states');

  console.log('\n=== ALL MIGRATION 075 TESTS PASSED SUCCESSFULLY ===');
} finally {
  await db.close();
}
