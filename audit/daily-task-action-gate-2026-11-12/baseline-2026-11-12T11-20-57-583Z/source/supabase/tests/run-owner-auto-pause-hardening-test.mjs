import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING COMPREHENSIVE OWNER AUTO-PAUSE & CARRYOVER DEBT HARDENING TESTS (MIGRATION 079) ===');

const db = await createDb({ legacyGrants: true });

try {
  const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x) && Number(x.slice(0, 3)) >= 62)
    .sort();

  for (const filename of migrationFiles) {
    await db.exec(read('supabase/migrations/' + filename));
  }
  console.log(`Loaded ${migrationFiles.length} migrations (from ${migrationFiles[0]} to ${migrationFiles.at(-1)}).`);

  const adminId = 'a1000000-0000-0000-0000-000000000001';
  const ownerId = 'b1000000-0000-0000-0000-000000000001';
  const supplierId = 'c1000000-0000-0000-0000-000000000001';
  const camp1 = 'd1000000-0000-0000-0000-000000000001';
  const camp2 = 'd1000000-0000-0000-0000-000000000002';
  const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;

  const scalar = async sql => (await db.query(sql)).rows[0];

  async function asActor(userId, sql) {
    await db.exec('savepoint actor_call');
    await db.exec(`set request.jwt.claim.sub = '${userId}'; set role authenticated;`);
    try {
      const result = await db.query(sql);
      await db.exec(`reset role; reset request.jwt.claim.sub; release savepoint actor_call;`);
      return result;
    } catch (error) {
      await db.exec('rollback to savepoint actor_call; release savepoint actor_call;');
      throw error;
    }
  }

  async function asOwner(sql) { return asActor(ownerId, sql); }
  async function asAdmin(sql) { return asActor(adminId, sql); }

  async function setThreshold(n) {
    const before = (await asAdmin('select public.admin_get_owner_auto_pause_config() r')).rows[0].r;
    const result = (await asAdmin(`select public.admin_set_owner_auto_pause_config(${n},'${before.revision}') r`)).rows[0].r;
    assert.equal(result.success, true);
    assert.equal(result.missed_days, n);
    return result;
  }

  async function checkpoint(offset = 1, streak = 0, carryOut = 0) {
    await db.exec(`insert into public.user_daily_tasks
      (user_id,task_date,mode,finalized_at,missed_streak,carry_out_tests,met)
      values ('${ownerId}', ${today}-${offset}, 'owner', now(), ${streak}, ${carryOut}, false)
      on conflict (user_id, task_date) do update set finalized_at = now(), missed_streak = ${streak}, carry_out_tests = ${carryOut};
      update public.profiles set daily_missed_streak=${streak} where id='${ownerId}';`);
  }

  async function qualifyingTest(count = 1, offset = 0) {
    await db.exec(`insert into public.daily_test_events_journal
      (source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
      select 'checkin',gen_random_uuid(),'${ownerId}',id,owner_id,${today}-${offset},'valid',true,
        (${today}-${offset})::timestamp at time zone 'Asia/Ho_Chi_Minh'
      from public.campaigns where owner_id <> '${ownerId}' and status='open'
      order by id limit ${count};`);
  }

  await db.exec(`
    insert into auth.users(id, email) values
      ('${adminId}', 'admin@test.local'),
      ('${ownerId}', 'owner@test.local'),
      ('${supplierId}', 'supplier@test.local');

    update public.profiles set role = 'admin', is_locked = false, created_at = now() - interval '20 days', last_login_date = ${today}-20 where id = '${adminId}';
    update public.profiles set role = 'member', is_locked = false, credits = 1000, created_at = now() - interval '20 days', last_login_date = ${today}-20 where id = '${ownerId}';
    update public.profiles set role = 'moderator', is_locked = false, credits = 1000, created_at = now() - interval '20 days', last_login_date = ${today}-20 where id = '${supplierId}';

    -- Slot 2 for owner
    insert into public.user_slots(user_id, slot_number, expires_at)
    values ('${ownerId}', 2, now() + interval '30 days');

    -- Two campaigns for owner (>15 days old)
    insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at) values
      ('${camp1}', '${ownerId}', 'com.test.app1', 'App 1', 'open', 1, now() - interval '15 days'),
      ('${camp2}', '${ownerId}', 'com.test.app2', 'App 2', 'open', 2, now() - interval '15 days');

    -- Supplier open apps (slots 1..6)
    insert into public.user_slots(user_id, slot_number, expires_at)
      select '${supplierId}', s, now() + interval '30 days' from generate_series(2, 6) s;
    insert into public.campaigns(owner_id, package_name, app_name, status, slot_number, created_at)
      select '${supplierId}', 'com.supply.app.' || s, 'Supply ' || s, 'open', s, now() - interval '10 days'
      from generate_series(1, 6) s;

    update public.campaign_status_journal set valid_from = now() - interval '15 days';
    update public.user_role_journal set valid_from = now() - interval '20 days';

    update public.system_provenance_markers set marker_time = (${today}-30)::timestamp at time zone 'Asia/Ho_Chi_Minh', marker_date = ${today}-30;
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_carryover_policy(${today}-10, true, 'Hardening test baseline policy');
    reset test.allow_policy_retroactive_write;
  `);

  async function scenario(name, fn) {
    await db.exec('begin');
    try {
      await fn();
      console.log(`PASS: ${name}`);
    } finally {
      await db.exec('rollback');
    }
  }

  // =========================================================================
  // TEST 1: THRESHOLDS 1 TO 7 ENFORCEMENT & REAL REPEATED SYNC IDEMPOTENCY
  // =========================================================================
  console.log('\n--- Test 1: Configurable Thresholds 1 to 7 via Authenticated Admin RPC ---');
  for (let n = 1; n <= 7; n++) {
    await scenario(`threshold ${n}: N-1 spares, N pauses both apps, carryover gate, repeated sync`, async () => {
      await setThreshold(n);
      await checkpoint(n + 1);
      for (let offset = n; offset >= 2; offset--) {
        await db.exec(`select public.finalize_daily_tasks_for_date(${today}-${offset});`);
      }
      assert.equal((await scalar(`select daily_missed_streak s from public.profiles where id='${ownerId}'`)).s, n - 1);
      assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n, 0, `Streak ${n-1} must not pause at threshold ${n}`);

      await db.exec(`select public.finalize_daily_tasks_for_date(${today}-1);`);
      assert.equal((await scalar(`select daily_missed_streak s from public.profiles where id='${ownerId}'`)).s, n);

      // Sync pauses both apps
      assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused, 2);
      // Repeated sync is idempotent
      assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused, 0);

      // Resume blocked before fulfilling requirements
      await assert.rejects(() => asOwner(`select public.resume_campaign('${camp1}')`), /OWNER_INACTIVE/);

      // If carryover debt exists (>1), partial testing still blocks resume
      const statusPre = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}) s`)).s;
      const carryIn = statusPre.carry_in_tests;
      if (carryIn > 1) {
        await qualifyingTest(1);
        const partialStatus = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}) s`)).s;
        if (partialStatus.carryover_remaining_tests > 0) {
          await assert.rejects(() => asOwner(`select public.resume_campaign('${camp1}')`), /OWNER_CARRYOVER_INCOMPLETE|OWNER_INACTIVE/);
        }
      }

      // Clear all debt and meet quota
      const needed = Math.max(statusPre.effective_required_tests, carryIn);
      await qualifyingTest(needed);
      const statusPost = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}) s`)).s;
      assert.equal(statusPost.met, true);
      assert.equal(statusPost.carryover_remaining_tests, 0);

      // Both apps can be resumed
      for (const cid of [camp1, camp2]) {
        const res = (await asOwner(`select public.resume_campaign('${cid}') r`)).rows[0].r;
        assert.equal(res.status, 'open');
      }
      assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n, 0);
    });
  }

  // =========================================================================
  // TEST 2: DEBT = 2 + MET = TRUE BLOCKED RESUME PATH
  // =========================================================================
  await scenario('Test 2: resume blocked when met=true but remaining prior debt > 0', async () => {
    await setThreshold(3);
    await checkpoint(4);
    for (let offset = 3; offset >= 1; offset--) {
      await db.exec(`select public.finalize_daily_tasks_for_date(${today}-${offset});`);
    }
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused, 2);

    // Restrict available apps to 1 today
    await db.exec(`update public.campaigns set status = 'paused', paused_reason = 'owner_manual' where owner_id in ('${adminId}','${supplierId}');`);
    await db.exec(`update public.campaigns set status = 'open', paused_reason = null where package_name = 'com.supply.app.1';`);

    // Complete 1 test: meets today's effective quota (capped at 1 available app), but debt remains > 0
    await qualifyingTest(1);
    const debtStatus = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}) s`)).s;
    assert.equal(debtStatus.met, true, 'Quota capped at available apps is met');
    assert.ok(debtStatus.carryover_remaining_tests > 0, 'Remaining carryover debt must be > 0');

    // Must be rejected with OWNER_CARRYOVER_INCOMPLETE
    await assert.rejects(
      () => asOwner(`select public.resume_campaign('${camp1}')`),
      /OWNER_CARRYOVER_INCOMPLETE/,
      'Resume must be blocked when remaining carryover debt is > 0'
    );

    // Open more supply apps and clear all debt
    await db.exec(`update public.campaigns set status = 'open', paused_reason = null where owner_id = '${supplierId}';`);
    const statusBeforeClear = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}) s`)).s;
    await qualifyingTest(statusBeforeClear.carryover_remaining_tests + statusBeforeClear.completed_tests);
    const clearedStatus = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}) s`)).s;
    assert.equal(clearedStatus.met, true);
    assert.equal(clearedStatus.carryover_remaining_tests, 0);

    // Resume succeeds now
    const clearedRes = (await asOwner(`select public.resume_campaign('${camp1}') r`)).rows[0].r;
    assert.equal(clearedRes.status, 'open');
  });

  // =========================================================================
  // TEST 3: DISABLED CARRYOVER WITH EXISTING DEBT
  // =========================================================================
  await scenario('Test 3: disabled carryover preserves prior debt and blocks resume', async () => {
    await setThreshold(3);
    await checkpoint(4);
    for (let offset = 3; offset >= 2; offset--) {
      await db.exec(`select public.finalize_daily_tasks_for_date(${today}-${offset});`);
    }

    // Disable carryover on yesterday
    await db.exec(`set test.allow_policy_retroactive_write = 'true';
      select public.set_daily_task_carryover_policy(${today}-1, false, 'Disabled carryover policy');
      reset test.allow_policy_retroactive_write;`);

    await db.exec(`select public.finalize_daily_tasks_for_date(${today}-1);`);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused, 2);

    // Today quota met
    await qualifyingTest(6);
    const offStatus = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}) s`)).s;
    assert.equal(offStatus.met, true);
    assert.ok(offStatus.carryover_remaining_tests > 0, 'Prior debt preserved even when carryover is off');

    await assert.rejects(
      () => asOwner(`select public.resume_campaign('${camp1}')`),
      /OWNER_CARRYOVER_INCOMPLETE/,
      'Resume blocked by preserved debt even when carryover policy is disabled'
    );
  });

  // =========================================================================
  // TEST 4: CHRONOLOGICAL FINALIZATION CUTS STREAK ON INTERMEDIATE MET DAY
  // =========================================================================
  await scenario('Test 4: chronological finalization across met gap day', async () => {
    await db.exec(`set test.allow_policy_retroactive_write = 'true';
      select public.set_daily_task_carryover_policy(${today}-1, false, 'Gap test policy');
      reset test.allow_policy_retroactive_write;`);
    await checkpoint(3, 6);
    // Intermediate day (today - 2) completes required tests
    const reqD2 = (await scalar(`select public.get_daily_task_status('${ownerId}',${today}-2) s`)).s.effective_required_tests;
    await qualifyingTest(reqD2, 2);

    // Run inactivity penalty cron runner
    await db.exec('select public.apply_daily_inactivity_penalty();');

    const gapCheck = await scalar(`select daily_missed_streak s,
      (select count(*)::int from public.user_daily_tasks where user_id='${ownerId}' and task_date=${today}-2 and finalized_at is not null) gap_rows
      from public.profiles where id='${ownerId}'`);

    assert.equal(gapCheck.gap_rows, 1, 'Intermediate day must be sequentially finalized');
    assert.equal(gapCheck.s, 1, 'Met intermediate day must cut streak; current streak is 1, not 7');
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n, 0, 'No campaigns paused');
  });

  // =========================================================================
  // TEST 5: CLIENT CREATED_AT TAMPER GUARD (INSERT & UPDATE)
  // =========================================================================
  await scenario('Test 5: creation timestamp tamper protection', async () => {
    await checkpoint(1, 7);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n, 2);

    // 1. Direct UPDATE of created_at by owner -> BLOCKED
    await assert.rejects(
      () => asOwner(`update public.campaigns set created_at = now() where owner_id = '${ownerId}'`),
      /PERMISSION_DENIED/,
      'Direct UPDATE on created_at must be rejected'
    );

    // 2. Direct INSERT with forged past/future timestamp by client -> BLOCKED
    await assert.rejects(
      () => asOwner(`insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number, created_at)
        values (gen_random_uuid(), '${ownerId}', 'com.tamper.forged', 'Forged App', 'open', 1, now() - interval '10 days')`),
      /PERMISSION_DENIED/,
      'Direct INSERT with forged timestamp >10s must be rejected'
    );

    // 3. Legitimate client metadata update while paused -> ALLOWED
    await asOwner(`update public.campaigns set app_name = 'Legitimate Rename' where id = '${camp1}'`);
    const renamed = (await scalar(`select app_name from public.campaigns where id = '${camp1}'`)).app_name;
    assert.equal(renamed, 'Legitimate Rename');

    // 4. Client cannot bypass pause without testing
    await asOwner('select public.record_daily_login()');
    await assert.rejects(() => asOwner(`select public.resume_campaign('${camp1}')`), /OWNER_INACTIVE/);
  });

  // =========================================================================
  // TEST 6: OWNER_MANUAL, SLOT_EXPIRED, AND ADMIN_MANUAL RESUME PATHS
  // =========================================================================
  await scenario('Test 6: owner resume paths (owner_manual, slot_expired) & admin paths', async () => {
    await checkpoint(1, 0);

    // A. owner_manual: requires met=true + debt=0
    await db.exec(`update public.campaigns set status = 'paused', paused_reason = 'owner_manual' where id = '${camp1}';`);
    await assert.rejects(() => asOwner(`select public.resume_campaign('${camp1}')`), /OWNER_INACTIVE/);

    // B. slot_expired: before renewal -> SLOT_EXPIRED
    await db.exec(`update public.user_slots set expires_at = now() - interval '1 hour' where user_id = '${ownerId}' and slot_number = 2;
      update public.campaigns set status = 'paused', paused_reason = 'slot_expired' where id = '${camp2}';`);
    await assert.rejects(() => asOwner(`select public.resume_campaign('${camp2}')`), /SLOT_EXPIRED/);

    // Renew slot
    await db.exec(`update public.user_slots set expires_at = now() + interval '30 days' where user_id = '${ownerId}' and slot_number = 2;`);
    // After renewal, owner still needs today's quota met
    await assert.rejects(() => asOwner(`select public.resume_campaign('${camp2}')`), /OWNER_INACTIVE/);

    // Complete tests to meet quota
    await qualifyingTest(6);
    assert.equal((await asOwner(`select public.resume_campaign('${camp1}') r`)).rows[0].r.status, 'open');
    assert.equal((await asOwner(`select public.resume_campaign('${camp2}') r`)).rows[0].r.status, 'open');

    // C. admin_manual: owner cannot resume
    await asAdmin(`select public.admin_pause_campaign('${camp1}', 'Violation');`);
    await assert.rejects(() => asOwner(`select public.resume_campaign('${camp1}')`), /PAUSED_BY_ADMIN/);

    // Admin resumes admin_manual -> admin_resumed = false
    const admRes = (await asAdmin(`select public.admin_resume_campaign('${camp1}') r`)).rows[0].r;
    assert.equal(admRes.status, 'open');
    assert.equal((await scalar(`select admin_resumed from public.campaigns where id = '${camp1}'`)).admin_resumed, false);

    // Admin resumes owner_inactive app -> admin_resumed = true
    await db.exec(`update public.campaigns set status = 'paused', paused_reason = 'owner_inactive' where id = '${camp1}';`);
    await asAdmin(`select public.admin_resume_campaign('${camp1}');`);
    assert.equal((await scalar(`select admin_resumed from public.campaigns where id = '${camp1}'`)).admin_resumed, true);

    // D. 30-day window completed upon resume
    await db.exec(`update public.campaigns set status = 'paused', paused_reason = 'owner_manual', created_at = now() - interval '31 days' where id = '${camp2}';`);
    const compRes = (await asOwner(`select public.resume_campaign('${camp2}') r`)).rows[0].r;
    assert.equal(compRes.status, 'completed');
    assert.equal((await scalar(`select status from public.campaigns where id = '${camp2}'`)).status, 'completed');
  });

  // =========================================================================
  // TEST 7: DIAGNOSTIC QUERY (audit_daily_task_ledger_gaps)
  // =========================================================================
  await scenario('Test 7: read-only audit function (audit_daily_task_ledger_gaps)', async () => {
    const auditRows = (await db.query('select * from public.audit_daily_task_ledger_gaps();')).rows;
    assert.ok(Array.isArray(auditRows));
  });

  // =========================================================================
  // TEST 8: CREATION EXEMPTION (SIGNUP DAY)
  // =========================================================================
  await scenario('Test 8: account created on evaluation date is exempt with streak 0', async () => {
    const newUserId = 'e1000000-0000-0000-0000-000000000001';
    await db.exec(`
      insert into auth.users (id, email) values ('${newUserId}', 'newuser@test.local');
      update public.profiles
      set role = 'member',
          created_at = (${today}-1)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '2 hours',
          daily_missed_streak = 0,
          credits = 500
      where id = '${newUserId}';
      select public.finalize_daily_tasks_for_date(${today}-1);
    `);
    const task = (await db.query(`select is_exempt, met, missed_streak, penalty_credits from public.user_daily_tasks where user_id = '${newUserId}' and task_date = ${today}-1;`)).rows[0];
    assert.ok(task, 'Creation task record must exist');
    assert.equal(task.is_exempt, true);
    assert.equal(task.met, true);
    assert.equal(task.missed_streak, 0);
    assert.equal(task.penalty_credits, 0);
  });

  console.log('\n=== ALL OWNER AUTO-PAUSE HARDENING TESTS PASSED SUCCESSFULLY ===');
} finally {
  await db.close();
}
