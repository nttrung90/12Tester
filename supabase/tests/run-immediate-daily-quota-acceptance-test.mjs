import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

// Identities
const owner1 = 'c1000000-0000-0000-0000-000000000001';
const owner2 = 'c1000000-0000-0000-0000-000000000002';
const newOwner = 'c2000000-0000-0000-0000-000000000001';
const newMember = 'c2000000-0000-0000-0000-000000000002';
const tester1 = 'a1000000-0000-0000-0000-000000000001';
const adminUser = 'd4000000-0000-0000-0000-000000000001';

const today = `(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date`;
const q = async sql => (await db.query(sql)).rows[0];

async function inSession(uid, fn) {
  await db.exec('savepoint sp_session');
  if (uid) {
    await db.exec(`
      set "request.jwt.claim.sub" = '${uid}';
      set "request.jwt.claims" = '{"sub": "${uid}", "role": "authenticated"}';
      set role = 'authenticated';
    `);
  } else {
    await db.exec(`
      set "request.jwt.claim.sub" = '';
      set "request.jwt.claims" = '{"role": "anon"}';
      set role = 'anon';
    `);
  }
  try {
    const res = await fn();
    await db.exec(`
      reset role;
      set "request.jwt.claim.sub" = '';
      set "request.jwt.claims" = '{}';
      release savepoint sp_session;
    `);
    return res;
  } catch (e) {
    await db.exec(`
      rollback to savepoint sp_session;
      reset role;
      set "request.jwt.claim.sub" = '';
      set "request.jwt.claims" = '{}';
    `);
    throw e;
  }
}

async function scenario(name, fn) {
  await db.exec('begin');
  try {
    const evidence = await fn();
    console.log(`PASS: ${name}`, JSON.stringify(evidence ?? {}));
  } catch (e) {
    console.error(`FAIL: ${name}`, e);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

console.log('=== LOADING ALL MIGRATIONS IN ORDER (INCLUDING 088) ===');
const migrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n))
  .sort();
for (const name of migrations) {
  await db.exec(read('supabase/migrations/' + name));
}
console.log('ACTIVE MIGRATIONS LOADED:', migrations.join(', '));

// Seed base users and supply campaigns
await db.exec(`
  insert into auth.users(id, email) values
    ('${owner1}', 'owner1@test.local'),
    ('${owner2}', 'owner2@test.local'),
    ('${tester1}', 'tester1@test.local'),
    ('${adminUser}', 'admin@test.local');

  update public.profiles set created_at = now() - interval '60 days', credits = 5000, last_login_date = ${today} - 60 where id = '${owner1}';
  update public.profiles set created_at = now() - interval '60 days', credits = 5000, last_login_date = ${today} - 60 where id = '${owner2}';
  update public.profiles set created_at = now() - interval '60 days', credits = 1000, last_login_date = ${today} - 60 where id = '${tester1}';
  update public.profiles set created_at = now() - interval '60 days', credits = 1000, role = 'admin', last_login_date = ${today} - 60 where id = '${adminUser}';

  insert into public.user_slots(user_id, slot_number, expires_at) values
    ('${owner1}', 2, now() + interval '30 days'),
    ('${owner1}', 3, now() + interval '30 days'),
    ('${owner1}', 4, now() + interval '30 days');

  -- Initial app for owner1 created 10 days ago (Slot 1)
  insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at, start_date)
  values ('b1000000-0000-0000-0000-000000000001', '${owner1}', 'com.owner1.app1', 'Owner1 App 1', 'open', 1, now() - interval '10 days', ${today} - 10);
`);

// Supply campaigns from distinct users so tester pool A >= 25
for (let i = 1; i <= 25; i++) {
  const suppId = `c4000000-0000-0000-0000-${String(i).padStart(12, '0')}`;
  await db.exec(`
    insert into auth.users(id, email) values ('${suppId}', 'supply${i}@test.local');
    update public.profiles set created_at = now() - interval '60 days', role = 'admin' where id = '${suppId}';
    insert into public.campaigns(owner_id, package_name, app_name, status, created_at, start_date)
    values ('${suppId}', 'com.supply.app${i}', 'Supply App ${i}', 'open', now() - interval '10 days', ${today} - 10);
  `);
}

await db.exec(`
  update public.campaign_status_journal set valid_from = now() - interval '10 days';
  update public.user_role_journal set valid_from = now() - interval '60 days';
  update public.system_provenance_markers set marker_time = (${today} - 60)::timestamp at time zone 'Asia/Ho_Chi_Minh', marker_date = ${today} - 60;
`);

// Enable carryover policy for tests and set tests_per_active_app = 12 (standard plan ratio)
await db.exec(`
  set test.allow_policy_retroactive_write = 'true';
  select public.set_daily_task_carryover_policy(${today} - 30, true, 'Acceptance fixture carryover');
  reset test.allow_policy_retroactive_write;

  set session_replication_role = 'replica';
  update public.daily_task_policies set tests_per_active_app = 12 where effective_from = '-infinity'::date;
  set session_replication_role = 'origin';
`);

// ============================================================================
// TEST SCENARIOS
// ============================================================================

await scenario('SCENARIO 1: Policy Governance - Default enabled after 095, audited disable/re-enable & non-admin guard', async () => {
  // 1. After migration 095, immediate quota is enabled by default (-infinity -> true)
  const defaultActive = (await q(`select public.is_immediate_daily_quota_active() as act`)).act;
  assert.equal(defaultActive, true, 'Immediate quota should be active by default today after 095');

  // 2. Non-admin cannot set policy
  await assert.rejects(async () => {
    await inSession(owner1, async () => {
      await db.exec(`select public.set_daily_task_immediate_quota_policy(${today}, false, 'Unauthorized attempt')`);
    });
  }, /FORBIDDEN|PERMISSION_DENIED/, 'Non-admin must be rejected');

  // 3. Admin / test harness disables policy with audit
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today} - 1, false, 'Disabled for acceptance test');
    reset test.allow_policy_retroactive_write;
  `);

  const disabledNow = (await q(`select public.is_immediate_daily_quota_active() as act`)).act;
  assert.equal(disabledNow, false, 'Immediate quota should be inactive after audited disable');

  const disableAuditRow = await q(`select * from public.daily_task_immediate_quota_policy_audit order by id desc limit 1`);
  assert.equal(disableAuditRow.new_is_enabled, false);
  assert.equal(disableAuditRow.new_operator_note, 'Disabled for acceptance test');

  // 4. Admin / test harness re-enables policy with audit
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today}, true, 'Re-enabled for acceptance test');
    reset test.allow_policy_retroactive_write;
  `);

  const activeNow = (await q(`select public.is_immediate_daily_quota_active() as act`)).act;
  assert.equal(activeNow, true, 'Immediate quota should be active after audited re-enable');

  const enableAuditRow = await q(`select * from public.daily_task_immediate_quota_policy_audit order by id desc limit 1`);
  assert.equal(enableAuditRow.new_is_enabled, true);
  assert.equal(enableAuditRow.new_operator_note, 'Re-enabled for acceptance test');

  return { defaultActive, disabledNow, activeNow, auditRowId: enableAuditRow.id };
});

await scenario('SCENARIO 2: Immediate Quota Increase on CreateCampaignV2 (1 app -> 2 apps -> 3 apps)', async () => {
  // Activate immediate quota
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today} - 1, true, 'Immediate quota active');
    reset test.allow_policy_retroactive_write;
  `);

  // Check initial status: 1 app from 10 days ago, q=12 -> K=1, B=12
  const initialStatus = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(initialStatus.active_app_count, 1, 'Initial K should be 1');
  assert.equal(initialStatus.base_required_tests, 12, 'Initial B should be 12');
  assert.equal(initialStatus.mode, 'owner');

  // Create 2nd app in Slot 2 via create_campaign_v2
  const reqId1 = 'e1000000-0000-0000-0000-000000000001';

  const createRes1 = await inSession(owner1, async () => {
    const quote = (await q(`select public.get_business_quote('campaign_create', '{"slot_number": 2, "target_testers": 12}'::jsonb) as q`)).q;
    return (await q(`
      select public.create_campaign_v2(
        p_app_name => 'Owner1 App 2',
        p_package_name => 'com.owner1.app2',
        p_slot_number => 2,
        p_target_testers => 12,
        p_client_request_id => '${reqId1}',
        p_expected_total_cost => ${quote.total_cost},
        p_expected_min_credits => ${quote.min_credits_to_post_app},
        p_expected_revision => '${quote.quote_token}'
      ) as res
    `)).res;
  });

  assert.equal(createRes1.success, true);
  assert.equal(createRes1.replayed, false);

  // Status must be immediately updated: K=2, B=24
  const statusAfterApp2 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(statusAfterApp2.active_app_count, 2, 'K must immediately become 2 after 2nd app created today');
  assert.equal(statusAfterApp2.base_required_tests, 24, 'Base required B must immediately become 24');

  // Create 3rd app in Slot 3 via create_campaign_v2
  const reqId2 = 'e1000000-0000-0000-0000-000000000002';

  const createRes2 = await inSession(owner1, async () => {
    const quote3 = (await q(`select public.get_business_quote('campaign_create', '{"slot_number": 3, "target_testers": 12}'::jsonb) as q`)).q;
    return (await q(`
      select public.create_campaign_v2(
        p_app_name => 'Owner1 App 3',
        p_package_name => 'com.owner1.app3',
        p_slot_number => 3,
        p_target_testers => 12,
        p_client_request_id => '${reqId2}',
        p_expected_total_cost => ${quote3.total_cost},
        p_expected_min_credits => ${quote3.min_credits_to_post_app},
        p_expected_revision => '${quote3.quote_token}'
      ) as res
    `)).res;
  });
  assert.equal(createRes2.success, true);

  // Status must be immediately updated: K=3, B=36
  const statusAfterApp3 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(statusAfterApp3.active_app_count, 3, 'K must immediately become 3 after 3rd app created today');
  assert.equal(statusAfterApp3.base_required_tests, 36, 'Base required B must immediately become 36');

  return { initial: initialStatus.base_required_tests, afterApp2: statusAfterApp2.base_required_tests, afterApp3: statusAfterApp3.base_required_tests };
});

await scenario('SCENARIO 3: Replay Protection & Immutable Receipts Isolation', async () => {
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today} - 1, true, 'Immediate quota active');
    reset test.allow_policy_retroactive_write;
  `);

  const reqId = 'e2000000-0000-0000-0000-000000000001';
  let quote;

  // 1. Initial creation
  const firstRes = await inSession(owner1, async () => {
    quote = (await q(`select public.get_business_quote('campaign_create', '{"slot_number": 2, "target_testers": 12}'::jsonb) as q`)).q;
    return (await q(`
      select public.create_campaign_v2(
        p_app_name => 'Owner1 Replay App',
        p_package_name => 'com.owner1.replay',
        p_slot_number => 2,
        p_target_testers => 12,
        p_client_request_id => '${reqId}',
        p_expected_total_cost => ${quote.total_cost},
        p_expected_min_credits => ${quote.min_credits_to_post_app},
        p_expected_revision => '${quote.quote_token}'
      ) as res
    `)).res;
  });
  assert.equal(firstRes.replayed, false);

  const status1 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(status1.active_app_count, 2);
  assert.equal(status1.base_required_tests, 24);

  // 2. Replay with identical client_request_id
  const replayRes = await inSession(owner1, async () => {
    return (await q(`
      select public.create_campaign_v2(
        p_app_name => 'Owner1 Replay App',
        p_package_name => 'com.owner1.replay',
        p_slot_number => 2,
        p_target_testers => 12,
        p_client_request_id => '${reqId}',
        p_expected_total_cost => ${quote.total_cost},
        p_expected_min_credits => ${quote.min_credits_to_post_app},
        p_expected_revision => '${quote.quote_token}'
      ) as res
    `)).res;
  });
  assert.equal(replayRes.replayed, true, 'Receipt must return replayed=true');
  assert.equal(replayRes.campaign_id, firstRes.campaign_id);

  // Quota must NOT increase again
  const status2 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(status2.active_app_count, 2, 'K must remain 2 upon replay');
  assert.equal(status2.base_required_tests, 24, 'B must remain 24 upon replay');

  return { firstId: firstRes.receipt_id, replayId: replayRes.receipt_id };
});

await scenario('SCENARIO 4: Preservation of Completed Tests (D) & Yesterday Carry-In (C)', async () => {
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today} - 1, true, 'Immediate quota active');
    reset test.allow_policy_retroactive_write;
  `);

  // Sealed yesterday with debt C = 8
  await db.exec(`
    insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
    values ('${owner1}', ${today} - 1, 'owner', now(), 8, 0, true);
  `);

  // Verify status before activity: K=1, B=12, C=8 -> required = 20
  const initial = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(initial.base_required_tests, 12);
  assert.equal(initial.carry_in_tests, 8);
  assert.equal(initial.required_tests, 20);

  // Owner does 5 distinct qualifying tests today (D=5)
  const supplyRows = (await db.query(`select id, owner_id from public.campaigns where owner_id <> '${owner1}' limit 5`)).rows;
  for (const row of supplyRows) {
    await db.exec(`
      insert into public.daily_test_events_journal (
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, observed_at
      ) values (
        'checkin', gen_random_uuid(), '${owner1}', '${row.id}', '${row.owner_id}',
        ${today}, 'valid', true, clock_timestamp()
      );
    `);
  }

  const statusWithD5 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(statusWithD5.completed_tests, 5, 'D must be 5');
  assert.equal(statusWithD5.base_required_tests, 12);
  assert.equal(statusWithD5.required_tests, 20);

  // Now create 2nd app today: B increases to 24, total required B + C = 24 + 8 = 32
  await inSession(owner1, async () => {
    const quote = (await q(`select public.get_business_quote('campaign_create', '{"slot_number": 2, "target_testers": 12}'::jsonb) as q`)).q;
    await db.exec(`
      select public.create_campaign_v2(
        p_app_name => 'Owner1 App 2',
        p_package_name => 'com.owner1.app2',
        p_slot_number => 2,
        p_target_testers => 12,
        p_client_request_id => 'e3000000-0000-0000-0000-000000000001',
        p_expected_total_cost => ${quote.total_cost},
        p_expected_min_credits => ${quote.min_credits_to_post_app},
        p_expected_revision => '${quote.quote_token}'
      );
    `);
  });

  const statusAfterAdd = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(statusAfterAdd.completed_tests, 5, 'Completed tests D must be preserved at 5');
  assert.equal(statusAfterAdd.carry_in_tests, 8, 'Carry-in C must be preserved at 8');
  assert.equal(statusAfterAdd.base_required_tests, 24, 'Base B must become 24');
  assert.equal(statusAfterAdd.required_tests, 32, 'Total required must be 32 (24 + 8)');

  return { d: statusAfterAdd.completed_tests, c: statusAfterAdd.carry_in_tests, b: statusAfterAdd.base_required_tests, req: statusAfterAdd.required_tests };
});

await scenario('SCENARIO 5: Immediate Quota Reduction on Campaign Removal & Pending Removal (36 -> 24 -> 12 -> 0)', async () => {
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today} - 1, true, 'Immediate quota active');
    reset test.allow_policy_retroactive_write;

    insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
    values ('${owner1}', ${today} - 1, 'owner', now(), 0, 0, true)
    on conflict (user_id, task_date) do update set finalized_at = now(), carry_out_tests = 0, met = true;
    update public.profiles set last_login_date = ${today} where id = '${owner1}';
  `);

  // Seed qualifying tests so owner1 is eligible under action gate
  const supplyRows = (await db.query(`select id, owner_id from public.campaigns where owner_id <> '${owner1}'`)).rows;
  for (const row of supplyRows) {
    await db.exec(`
      insert into public.daily_test_events_journal (
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, observed_at
      ) values (
        'checkin', gen_random_uuid(), '${owner1}', '${row.id}', '${row.owner_id}',
        ${today}, 'valid', true, clock_timestamp()
      );
    `);
  }

  // Start with 3 apps for owner1:
  // App 1 (Slot 1, existing)
  // App 2 (Slot 2, created today)
  // App 3 (Slot 3, created today)
  const app2Id = 'b2000000-0000-0000-0000-000000000002';
  const app3Id = 'b2000000-0000-0000-0000-000000000003';
  await db.exec(`
    insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at, start_date) values
      ('${app2Id}', '${owner1}', 'com.owner1.app2', 'App 2', 'open', 2, clock_timestamp(), ${today}),
      ('${app3Id}', '${owner1}', 'com.owner1.app3', 'App 3', 'open', 3, clock_timestamp(), ${today});
  `);

  const s36 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(s36.active_app_count, 3);
  assert.equal(s36.base_required_tests, 36);

  // 1. Delete App 3 (immediate removal, no pending reports)
  const rem3 = await inSession(owner1, async () => {
    return (await q(`select public.delete_owned_campaign('${app3Id}') as r`)).r;
  });
  assert.equal(rem3.status, 'removed');

  const s24 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(s24.active_app_count, 2, 'K must immediately decrease to 2');
  assert.equal(s24.base_required_tests, 24, 'Base B must immediately decrease to 24');

  // 2. Delete App 2 with a pending tester report -> accepted as pending_removal with status='closed'
  const repId = 'a2000000-0000-0000-0000-000000000001';
  await db.exec(`
    insert into public.tester_reports (id, campaign_id, user_id, rating, content, status, created_at)
    values ('${repId}', '${app2Id}', '${tester1}', 5, 'Great app', 'pending', clock_timestamp());
  `);

  const rem2 = await inSession(owner1, async () => {
    return (await q(`select public.delete_owned_campaign('${app2Id}') as r`)).r;
  });
  assert.equal(rem2.status, 'pending_removal');

  // Quota must reduce immediately upon pending_removal closure!
  const s12 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(s12.active_app_count, 1, 'K must immediately decrease to 1 upon pending_removal');
  assert.equal(s12.base_required_tests, 12, 'Base B must immediately decrease to 12');

  // Eventual physical cleanup of App 2: auto-accept pending report, then run process closing rounds
  await db.exec(`
    update public.tester_reports set status = 'approved', reviewed_at = clock_timestamp() where id = '${repId}';
    select public._process_closing_tester_bonus_rounds();
  `);

  // Verify App 2 is physically deleted from campaigns
  const app2Exists = (await q(`select exists(select 1 from public.campaigns where id = '${app2Id}') as e`)).e;
  assert.equal(app2Exists, false, 'App 2 should now be physically deleted');

  // Eventual physical cleanup must NOT subtract again! (K must stay 1, B must stay 12)
  const s12AfterCleanup = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(s12AfterCleanup.active_app_count, 1, 'K must remain 1 after physical cleanup');
  assert.equal(s12AfterCleanup.base_required_tests, 12, 'B must remain 12 after physical cleanup');

  // 3. Delete last app (App 1)
  const rem1 = await inSession(owner1, async () => {
    return (await q(`select public.delete_owned_campaign('b1000000-0000-0000-0000-000000000001') as r`)).r;
  });
  assert.equal(rem1.status, 'removed');

  const s0 = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  assert.equal(s0.active_app_count, 0, 'K must become 0');
  assert.equal(s0.base_required_tests, 0, 'Base B must become 0');

  return { s36: s36.base_required_tests, s24: s24.base_required_tests, s12: s12.base_required_tests, s0: s0.base_required_tests };
});

await scenario('SCENARIO 6: Registration Day Owner Finalization & Member Exemption Boundary', async () => {
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today} - 1, true, 'Immediate quota active');
    reset test.allow_policy_retroactive_write;
  `);

  // 1. Account newOwner registers on today
  await db.exec(`
    insert into auth.users(id, email) values ('${newOwner}', 'newowner@test.local');
    update public.profiles set created_at = clock_timestamp(), credits = 500, last_login_date = ${today} where id = '${newOwner}';

    -- Post an app today (Slot 1)
    insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at, start_date)
    values ('b3000000-0000-0000-0000-000000000001', '${newOwner}', 'com.newowner.app', 'NewOwner App', 'open', 1, clock_timestamp(), ${today});
  `);

  // Getter checks: K=1, B=12, C=0, history_ready=true, carryover_ready=true
  const regOwnerStatus = (await q(`select public.get_daily_task_status('${newOwner}', ${today}) as s`)).s;
  assert.equal(regOwnerStatus.mode, 'owner');
  assert.equal(regOwnerStatus.active_app_count, 1);
  assert.equal(regOwnerStatus.base_required_tests, 12);
  assert.equal(regOwnerStatus.carry_in_tests, 0);
  assert.equal(regOwnerStatus.required_tests, 12);
  assert.equal(regOwnerStatus.history_ready, true);
  assert.equal(regOwnerStatus.carryover_ready, true);

  // Finalizer on registration day: owner with uncompleted tests should be penalized and debt carried out
  // (Using test.allow_future_finalize to simulate end-of-day sealing for today)
  await db.exec(`
    set test.allow_future_finalize = 'true';
    select public._apply_daily_inactivity_penalty(${today});
    reset test.allow_future_finalize;
  `);

  const finalizedOwnerRow = await q(`select * from public.user_daily_tasks where user_id = '${newOwner}' and task_date = ${today}`);
  assert.ok(finalizedOwnerRow, 'User daily tasks row must exist');
  assert.equal(finalizedOwnerRow.mode, 'owner');
  assert.equal(finalizedOwnerRow.active_app_count, 1);
  assert.equal(finalizedOwnerRow.missed_streak, 1, 'Owner failing registration day task gets streak 1');
  assert.equal(finalizedOwnerRow.carry_out_tests, 12, '12 unmet tests must be carried out to tomorrow');
  assert.ok(finalizedOwnerRow.penalty_credits > 0, 'Inactivity penalty must be applied');

  // 2. Account newMember registers on today, DOES NOT post an app
  await db.exec(`
    insert into auth.users(id, email) values ('${newMember}', 'newmember@test.local');
    update public.profiles set created_at = clock_timestamp(), credits = 100, last_login_date = ${today} where id = '${newMember}';
  `);

  const regMemberStatus = (await q(`select public.get_daily_task_status('${newMember}', ${today}) as s`)).s;
  assert.equal(regMemberStatus.mode, 'member');
  assert.equal(regMemberStatus.active_app_count, 0);
  assert.equal(regMemberStatus.base_required_tests, 0);
  assert.equal(regMemberStatus.required_tests, 0);

  await db.exec(`
    set test.allow_future_finalize = 'true';
    select public._apply_daily_inactivity_penalty(${today});
    reset test.allow_future_finalize;
  `);

  const finalizedMemberRow = await q(`select * from public.user_daily_tasks where user_id = '${newMember}' and task_date = ${today}`);
  assert.ok(finalizedMemberRow);
  assert.equal(finalizedMemberRow.mode, 'member');
  assert.equal(finalizedMemberRow.active_app_count, 0);
  assert.equal(finalizedMemberRow.is_exempt, true, 'Pure member retains registration day exemption');
  assert.equal(finalizedMemberRow.missed_streak, 0, 'Pure member gets streak 0');
  assert.equal(finalizedMemberRow.penalty_credits, 0, 'Pure member gets 0 penalty');

  return { ownerFinalized: finalizedOwnerRow.missed_streak, memberFinalized: finalizedMemberRow.missed_streak };
});

await scenario('SCENARIO 7: Historical Cutover Preserves Old Schema Before Activation Date', async () => {
  // Disable today and schedule activation for tomorrow
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_immediate_quota_policy(${today} - 1, false, 'Disabled before cutover');
    select public.set_daily_task_immediate_quota_policy(${today} + 1, true, 'Activates tomorrow');
    reset test.allow_policy_retroactive_write;
  `);

  // Today is before activation date
  const isActToday = (await q(`select public.is_immediate_daily_quota_active(${today}) as act`)).act;
  assert.equal(isActToday, false, 'Should be inactive today');

  // App created today should NOT be counted today under historical semantics (created_at < v_day_start rule)
  const appTodayId = 'b4000000-0000-0000-0000-000000000001';
  await db.exec(`
    insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at, start_date)
    values ('${appTodayId}', '${owner1}', 'com.owner1.apptoday', 'App Today', 'open', 2, clock_timestamp(), ${today});
  `);

  const statusHist = (await q(`select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
  // Owner1 had 1 app from 10 days ago. The new app created today is NOT counted today under old rule!
  assert.equal(statusHist.active_app_count, 1, 'Under old semantics before activation, app created today is not counted today');
  assert.equal(statusHist.base_required_tests, 12, 'Base required remains 12');

  return { active_app_count: statusHist.active_app_count, base_required_tests: statusHist.base_required_tests };
});

console.log('=== ALL IMMEDIATE DAILY QUOTA ACCEPTANCE SCENARIOS PASSED ===');
