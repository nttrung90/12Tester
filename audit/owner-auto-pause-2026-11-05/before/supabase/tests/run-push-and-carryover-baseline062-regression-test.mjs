import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();
const owner = '11111111-1111-1111-1111-111111111111';
const other = '22222222-2222-2222-2222-222222222222';
const absent = '33333333-3333-3333-3333-333333333333';
const camp1 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const camp2 = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const camp3 = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const req1 = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const req2 = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';

const one = async sql => (await db.query(sql)).rows[0];

async function test(name, fn) {
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
  console.log('=== LOADING ACTIVE MIGRATIONS (062 THROUGH 066) ===');
  const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(n => /^\d+.*\.sql$/.test(n))
    .sort();
  for (const name of migrationFiles) {
    await db.exec(read('supabase/migrations/' + name));
  }

  // Verify marker table and idempotency of 066
  const markerInitial = await one(`select marker_time, marker_date from public.system_provenance_markers where marker_key = 'coverage_start'`);
  assert.ok(markerInitial, 'Coverage start marker must exist');

  // Re-run migration 066: must not reset coverage marker (ON CONFLICT DO NOTHING)
  await db.exec(read('supabase/migrations/066_push_and_carryover_hardening.sql'));
  const markerAfterRerun = await one(`select marker_time, marker_date from public.system_provenance_markers where marker_key = 'coverage_start'`);
  assert.equal(markerAfterRerun.marker_time.toISOString(), markerInitial.marker_time.toISOString(), 'Rerunning 066 must NOT reset coverage start timestamp');

  await db.exec(`
    insert into auth.users(id, email) values
      ('${owner}', 'owner@test.local'),
      ('${other}', 'other@test.local'),
      ('${absent}', 'absent@test.local');

    -- These users must predate the covered October 25 fixture below, regardless
    -- of the real date on which this historical regression suite runs.
    update public.profiles set created_at = '2026-10-20 00:00:00+07', credits = 1000 where id in ('${owner}', '${other}', '${absent}');
  `);

  const { today } = await one(`select (now() at time zone 'Asia/Ho_Chi_Minh')::date::text today`);

  // Test 1: Unproven legacy day (day_start < coverage_start) safely defers history_ready = false
  await test('Unproven legacy day safely defers with history_ready = false (R02)', async () => {
    // Insert backdated campaign before coverage start
    await db.exec(`
      insert into public.campaigns(id, owner_id, app_name, package_name, status, slot_number, created_at)
      values ('${camp1}', '${owner}', 'OwnerApp', 'com.owner.app', 'open', 1, now() - interval '4 days');
    `);
    const s = (await one(`select public.get_daily_task_status('${owner}', date '${today}' - 1) s`)).s;
    assert.equal(s.history_ready, false, 'Unproven legacy date before coverage start must have history_ready=false');

    // Finalizer must refuse to seal or penalize missing proof
    await db.exec(`select public.finalize_daily_tasks_for_date(date '${today}' - 1)`);
    const row = await one(`select finalized_at from public.user_daily_tasks where user_id = '${owner}' and task_date = date '${today}' - 1`);
    assert.equal(row, undefined, 'Finalizer must not seal or finalize without reliable history');
  });

  // Setup genuinely proven covered history for testing
  // In a proven covered day: coverage_start <= day_start, journal entries exist prospectively
  const testCoveredDate = '2026-10-25';
  const testCoveredTs = "2026-10-25 00:00:00+07";
  const testPriorDate = '2026-10-24';

  await db.exec(`
    -- Explicitly set coverage_start marker to before testCoveredDate for proven fixture
    update public.system_provenance_markers
    set marker_time = '2026-10-20 00:00:00+07', marker_date = '2026-10-20'
    where marker_key = 'coverage_start';

    -- Seed prospective journals for covered dates
    insert into public.campaigns(id, owner_id, app_name, package_name, status, slot_number, created_at)
    values
      ('${camp1}', '${owner}', 'OwnerApp', 'com.owner.app', 'open', 1, '2026-10-21 00:00:00+07'),
      ('${camp2}', '${other}', 'SupplyApp1', 'com.supply.app1', 'open', 1, '2026-10-21 00:00:00+07'),
      ('${camp3}', '${absent}', 'AbsentApp', 'com.absent.app', 'open', 1, '2026-10-21 00:00:00+07')
    on conflict (id) do update set status = 'open', owner_id = excluded.owner_id;

    -- Update journal valid_from to reflect genuine prospective coverage
    update public.campaign_status_journal
    set valid_from = '2026-10-21 00:00:00+07'
    where campaign_id in ('${camp1}', '${camp2}', '${camp3}');

    -- User role journal
    update public.user_role_journal
    set valid_from = '2026-10-20 00:00:00+07'
    where user_id in ('${owner}', '${other}', '${absent}');
  `);

  // Test 2: Proven covered day preserves K and operates for ABSENT users
  await test('Fully covered day operates for ABSENT users without app-open requirement (R02/Absent)', async () => {
    // Absent user never logged in on testCoveredDate
    const s = (await one(`select public.get_daily_task_status('${absent}', date '${testCoveredDate}') s`)).s;
    assert.equal(s.history_ready, true, 'Fully covered day must have history_ready=true');
    assert.equal(s.mode, 'owner');
    assert.equal(s.active_app_count, 1);
    assert.equal(s.logged_in_today, false);
    assert.equal(s.met, false);

    // Finalize covered day
    await db.exec(`
      set test.allow_future_finalize = 'true';
      select public.finalize_daily_tasks_for_date(date '${testCoveredDate}');
      reset test.allow_future_finalize;
    `);

    const row = await one(`
      select mode, active_app_count, finalized_at is not null sealed, penalty_credits > 0 penalized
      from public.user_daily_tasks where user_id = '${absent}' and task_date = date '${testCoveredDate}'
    `);
    assert.equal(row.sealed, true, 'Absent user day must be sealed by finalizer on covered day');
    assert.equal(row.mode, 'owner');
    assert.equal(row.active_app_count, 1);
  });

  // Test 3: Disabled policy preserves debt=8 on a proven covered day
  await test('Disabled policy preserves debt=8 on a proven covered day (R04)', async () => {
    // Prior day (testPriorDate) finalized with carry_out = 8
    await db.exec(`
      insert into public.user_daily_tasks(user_id, task_date, carryover_version, carry_out_tests, finalized_at)
      values ('${owner}', date '${testPriorDate}', 1, 8, now())
      on conflict (user_id, task_date) do update set carry_out_tests = 8, finalized_at = now();

      set test.allow_policy_retroactive_write = 'true';
      select public.set_daily_task_carryover_policy(date '${testCoveredDate}', false, 'disabled policy');
      reset test.allow_policy_retroactive_write;

      set test.allow_future_finalize = 'true';
      select public.finalize_daily_tasks_for_date(date '${testCoveredDate}');
      reset test.allow_future_finalize;
    `);

    const row = await one(`
      select carry_in_tests, carry_out_tests, finalized_at is not null sealed
      from public.user_daily_tasks where user_id = '${owner}' and task_date = date '${testCoveredDate}'
    `);
    assert.equal(row.sealed, true, 'Must seal on proven covered day');
    assert.equal(row.carry_in_tests, 8);
    assert.equal(row.carry_out_tests, 8, 'Disabled policy must preserve outstanding debt of 8');
  });

  // Test 4: Append-only D events preserve deleted campaign tests and handle same-day/late rejects
  await test('D events preserve deleted campaign tests and handle same-day/late rejects (R02/D)', async () => {
    // 1. Two reports for same campaign on covered day -> distinct campaign counted ONCE
    const rep1 = '11111111-aaaa-1111-aaaa-111111111111';
    const rep2 = '22222222-aaaa-2222-aaaa-222222222222';
    await db.exec(`
      insert into public.daily_test_events_journal (source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at)
      values
        ('report', '${rep1}', '${owner}', '${camp2}', '${other}', date '${testCoveredDate}', 'approved', true, '${testCoveredDate} 10:00:00+07'),
        ('report', '${rep2}', '${owner}', '${camp2}', '${other}', date '${testCoveredDate}', 'pending', true, '${testCoveredDate} 11:00:00+07');
    `);

    const s1 = (await one(`select public.get_daily_task_status('${owner}', date '${testCoveredDate}') s`)).s;
    assert.equal(s1.completed_tests, 1, 'Two reports for same campaign must count as 1 distinct campaign');

    // 2. Same-day reject of rep1 before day boundary: rep2 is still pending, so D remains 1
    await db.exec(`
      insert into public.daily_test_events_journal (source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at)
      values ('report', '${rep1}', '${owner}', '${camp2}', '${other}', date '${testCoveredDate}', 'rejected', false, '${testCoveredDate} 15:00:00+07');
    `);
    const s2 = (await one(`select public.get_daily_task_status('${owner}', date '${testCoveredDate}') s`)).s;
    assert.equal(s2.completed_tests, 1, 'One report rejected but one still pending -> D remains 1');

    // 3. Deleting campaign today does NOT erase test completed on covered date
    await db.exec(`delete from public.campaigns where id = '${camp2}'`);
    const s3 = (await one(`select public.get_daily_task_status('${owner}', date '${testCoveredDate}') s`)).s;
    assert.equal(s3.completed_tests, 1, 'Deleting campaign must NOT erase qualifying test from audit journal');
  });

  // Test 5: Role journal protects past boundary from today's role changes
  await test('Role journal protects historical boundary from retroactive exemption (R02/Role)', async () => {
    // User was member on testCoveredDate
    const sBefore = (await one(`select public.get_daily_task_status('${owner}', date '${testCoveredDate}') s`)).s;
    assert.equal(sBefore.is_penalty_exempt, false, 'User was not exempt on testCoveredDate');

    // User becomes admin TODAY
    await db.exec(`update public.profiles set role = 'admin' where id = '${owner}'`);

    // Status on historical covered date must NOT be retroactively exempted
    const sAfter = (await one(`select public.get_daily_task_status('${owner}', date '${testCoveredDate}') s`)).s;
    assert.equal(sAfter.is_penalty_exempt, false, 'Historical date must derive role from journal boundary, not current profile');
  });

  // Test 6: Policy past-row move and retroactive scheduling rejected
  await test('Policy past-row moving and retroactive scheduling rejected (R04)', async () => {
    let rejected1 = false;
    await db.exec('savepoint sp_retro;');
    try {
      await db.exec(`select public.set_daily_task_carryover_policy(date '${today}' - 2, false, 'retroactive')`);
    } catch (e) {
      rejected1 = true;
      await db.exec('rollback to savepoint sp_retro;');
      assert.match(e.message, /CANNOT_MODIFY_HISTORICAL_POLICY/);
    }
    assert.ok(rejected1, 'Must reject retroactive scheduling');

    let rejected2 = false;
    await db.exec('savepoint sp_move;');
    try {
      await db.exec(`update public.daily_task_carryover_policies set effective_from = date '${today}' + 50 where effective_from <= date '${today}'`);
    } catch (e) {
      rejected2 = true;
      await db.exec('rollback to savepoint sp_move;');
      assert.match(e.message, /CANNOT_MODIFY_HISTORICAL_POLICY/);
    }
    assert.ok(rejected2, 'Must reject moving past policy to future date');
  });

  console.log('=== ALL BASELINE 062+ REGRESSION TESTS PASSED ===');
} finally {
  await db.close();
}
