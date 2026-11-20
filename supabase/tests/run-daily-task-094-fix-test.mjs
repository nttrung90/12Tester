import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const owner1 = 'f4000000-0000-0000-0000-000000000001';
const owner2 = 'f4000000-0000-0000-0000-000000000002';
const checkpointUser = 'f4000000-0000-0000-0000-000000000003';
const member1 = 'f4000000-0000-0000-0000-000000000004';
const nullLoginUser = 'f4000000-0000-0000-0000-000000000005';
const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;

const allMigrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n))
  .sort();
const pre094Migrations = allMigrations.filter(n => Number(n.slice(0, 3)) <= 93);
const migration094 = read('supabase/migrations/094_fix_daily_task_completion_and_carryover.sql');

async function seedBase(db) {
  await db.exec(`
    insert into auth.users(id, email) values
      ('${owner1}', 'owner1-094@test.local'),
      ('${owner2}', 'owner2-094@test.local'),
      ('${checkpointUser}', 'cp-094@test.local'),
      ('${member1}', 'member1-094@test.local'),
      ('${nullLoginUser}', 'nulllogin-094@test.local');

    update public.profiles
    set created_at = (${today} - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh',
        credits = 10000,
        last_login_date = ${today} - 30,
        daily_missed_streak = 0
    where id in ('${owner1}', '${owner2}', '${checkpointUser}', '${member1}', '${nullLoginUser}');

    -- Configure ratio 12:1 and enable carryover
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_carryover_policy(${today} - 20, true, '094 carryover test');
    reset test.allow_policy_retroactive_write;

    set session_replication_role = 'replica';
    update public.daily_task_policies set tests_per_active_app = 12 where effective_from = '-infinity'::date;
    set session_replication_role = 'origin';

    insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at)
    values
      ('fa000000-0000-0000-0000-000000000001', '${owner1}', 'com.test094.owner1', 'Owner1 App', 'open', 1, (${today} - 2)::timestamp at time zone 'Asia/Ho_Chi_Minh'),
      ('fa000000-0000-0000-0000-000000000002', '${owner2}', 'com.test094.owner2', 'Owner2 App', 'open', 1, (${today} - 20)::timestamp at time zone 'Asia/Ho_Chi_Minh');
  `);

  const supplyIds = [];
  for (let i = 1; i <= 30; i++) {
    const sid = `f5000000-0000-0000-0000-${String(i).padStart(12, '0')}`;
    const cid = `fb000000-0000-0000-0000-${String(i).padStart(12, '0')}`;
    supplyIds.push({ sid, cid });
    await db.exec(`
      insert into auth.users(id, email) values ('${sid}', 'supply094-${i}@test.local');
      update public.profiles
      set created_at = (${today} - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh',
          role = 'admin',
          last_login_date = ${today} - 30
      where id = '${sid}';
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at)
      values ('${cid}', '${sid}', 'com.supply094.${i}', 'Supply094 ${i}', 'open', 1, (${today} - 20)::timestamp at time zone 'Asia/Ho_Chi_Minh');
    `);
  }

  await db.exec(`
    update public.campaign_status_journal
    set valid_from = case
      when campaign_id = 'fa000000-0000-0000-0000-000000000001' then (${today} - 2)::timestamp at time zone 'Asia/Ho_Chi_Minh'
      else (${today} - 20)::timestamp at time zone 'Asia/Ho_Chi_Minh'
    end;
    update public.user_role_journal set valid_from = (${today} - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh';
  `);

  return supplyIds;
}

async function inSession(db, uid, fn) {
  await db.exec(`
    set "request.jwt.claim.sub" = '${uid}';
    set "request.jwt.claims" = '{"sub":"${uid}","role":"authenticated"}';
    set role = 'authenticated';
  `);
  try {
    return await fn();
  } finally {
    await db.exec(`
      reset role;
      set "request.jwt.claim.sub" = '';
      set "request.jwt.claims" = '{}';
    `);
  }
}

async function submitAppTests(db, uid, supplyIds, count, offset = 0) {
  const results = [];
  for (let i = 0; i < count; i++) {
    const idx = offset + i;
    const { cid } = supplyIds[idx];
    const reqId = `fc${String(idx + 1).padStart(6, '0')}-0000-0000-0000-000000000001`;
    const imgId = `fd${String(idx + 1).padStart(6, '0')}-0000-0000-0000-000000000001`;
    const attachment = [{
      id: imgId,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${cid}%2F${uid}%2F${imgId}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${cid}%2F${uid}%2Fthumb_${imgId}.jpg?alt=media&token=fixture`,
      image_hash: `a11122223333${String(idx).padStart(4, '0')}`,
      sha256: `sha_094_${uid}_${idx}`
    }];
    const res = await inSession(db, uid, async () => {
      return (await db.query(`
        select public.submit_tester_report_v3(
          p_submission_context => 'app_test',
          p_campaign_id => '${cid}',
          p_content => 'Regular test ${i + 1}',
          p_rating => 5,
          p_client_submit_id => '${reqId}',
          p_attachments => '${JSON.stringify(attachment)}'::jsonb,
          p_mission_id => null
        ) as r
      `)).rows[0].r;
    });
    assert.equal(res.success, true);
    results.push(res);
  }
  return results;
}

const q = async (db, sql) => (await db.query(sql)).rows[0];

console.log('=== RUNNING MIGRATION 094 ACCEPTANCE & REGRESSION TESTS ===');

// ============================================================================
// TEST 1: Upgrade from 093 -> 094 with mid-day coverage_start marker_time &
//         pre-coverage user recovery + 12/12 completion + R08 preservation
// ============================================================================
{
  const db = await createDb();
  try {
    for (const m of pre094Migrations) {
      await db.exec(read(`supabase/migrations/${m}`));
    }
    const supplyIds = await seedBase(db);

    // Simulate production 066 mid-day coverage_start on (today - 5) at 14:30:00+07
    await db.exec(`
      update public.system_provenance_markers
      set marker_date = ${today} - 5,
          marker_time = (${today} - 5)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '14 hours 30 minutes'
      where marker_key = 'coverage_start';

      -- checkpointUser already has a trusted checkpoint at (today - 2) -> R08 must preserve this!
      insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
      values ('${checkpointUser}', ${today} - 2, 'member', now(), 0, 0, true);
    `);

    // BEFORE 094: owner1 is permanently stuck (marker_date history_ready=false, no finalized row prior to coverage_start)
    const beforeCov = (await q(db, `select public.get_daily_task_status('${owner1}', ${today} - 5) as s`)).s;
    assert.equal(beforeCov.history_ready, false, 'Before 094, mid-day marker_time causes marker_date history_ready=false');

    await db.exec('select public.apply_daily_inactivity_penalty()');
    const sealedBefore094 = (await q(db, `
      select count(*)::int c from public.user_daily_tasks
      where user_id = '${owner1}' and finalized_at is not null
    `)).c;
    assert.equal(sealedBefore094, 0, 'Before 094, owner1 cannot finalize any day from coverage_start onward');

    // Also submit 12/12 before 094 -> met is STILL false because carryover_ready=false!
    await submitAppTests(db, owner1, supplyIds, 12);
    const status12Before094 = (await q(db, `select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
    assert.equal(status12Before094.completed_tests, 12);
    assert.equal(status12Before094.carryover_ready, false);
    assert.equal(status12Before094.met, false, 'Before 094, 12/12 shows met=false when stuck on unfinalized yesterday');

    // APPLY 094 (and re-run to verify idempotency)
    await db.exec(migration094);
    await db.exec(migration094);

    // 1a. marker_time must now be normalized to 00:00:00+07 of marker_date
    const marker = await q(db, `
      select marker_date, marker_time = (marker_date::timestamp at time zone 'Asia/Ho_Chi_Minh') as is_midnight
      from public.system_provenance_markers where marker_key = 'coverage_start'
    `);
    assert.equal(marker.is_midnight, true, '094 must normalize mid-day coverage_start marker_time to 00:00 VN');

    // 1b. diagnose_daily_task_stuck_users must NOT false-flag owner1 as MISSING_PREDECESSOR_FINALIZATION
    const stuckReasons = (await db.query(`
      select user_id, stuck_reason from public.diagnose_daily_task_stuck_users()
      where user_id = '${owner1}'
    `)).rows;
    assert.equal(stuckReasons.length, 0, 'Pre-coverage user recoverable at coverage_start_date must not be flagged stuck');

    // 1c. After 094, apply_daily_inactivity_penalty seals today-5 .. today-1 for owner1
    //     while R08 protects checkpointUser before today-2!
    const cpCreditsBefore = (await q(db, `select credits from public.profiles where id = '${checkpointUser}'`)).credits;
    await db.exec('select public.apply_daily_inactivity_penalty()');

    const owner1Sealed = (await q(db, `
      select count(*)::int c from public.user_daily_tasks
      where user_id = '${owner1}' and task_date between (${today} - 5) and (${today} - 1) and finalized_at is not null
    `)).c;
    assert.equal(owner1Sealed, 5, 'All 5 days from coverage_start_date through yesterday must be sealed for owner1');

    const owner2Sealed = (await q(db, `
      select count(*)::int c from public.user_daily_tasks
      where user_id = '${owner2}' and task_date between (${today} - 5) and (${today} - 1) and finalized_at is not null and mode = 'owner'
    `)).c;
    assert.equal(owner2Sealed, 5, 'All 5 days from coverage_start_date through yesterday must be sealed as owner for owner2');

    // R08 check: checkpointUser must have NO finalized rows before today-2
    const cpPreAnchor = (await q(db, `
      select count(*)::int c from public.user_daily_tasks
      where user_id = '${checkpointUser}' and task_date < ${today} - 2 and finalized_at is not null
    `)).c;
    assert.equal(cpPreAnchor, 0, 'R08: pre-checkpoint days must NOT be sealed for checkpointUser');
    const cpCreditsAfter = (await q(db, `select credits from public.profiles where id = '${checkpointUser}'`)).credits;
    assert.equal(cpCreditsAfter, cpCreditsBefore, 'R08: checkpointUser credits untouched before checkpoint');

    // 1d. Give owner1 finalized yesterday with 0 carry_out so we verify clean 12/12 today
    //     (Remember owner1 missed days -5..-1 in fixture so has carry_out > 0; test 12+carry_out clearing AND 0-debt 12/12!)
    const owner1TodayStatus = (await q(db, `select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
    assert.equal(owner1TodayStatus.carryover_ready, true, 'owner1 carryover_ready must now be true');
    assert.equal(owner1TodayStatus.history_ready, true);
    assert.equal(owner1TodayStatus.completed_tests, 12);
    assert.ok(owner1TodayStatus.carry_in_tests > 0, 'Yesterday unpaid debt carries into today');

    // Complete remaining carry_in tests on supplyIds[12..] -> met becomes true & 090 action gate allows!
    const remNeeded = owner1TodayStatus.effective_required_tests - owner1TodayStatus.completed_tests;
    assert.ok(remNeeded > 0 && 12 + remNeeded <= supplyIds.length);
    for (let i = 12; i < 12 + remNeeded; i++) {
      const { cid } = supplyIds[i];
      const reqId = `fe${String(i + 1).padStart(6, '0')}-0000-0000-0000-000000000001`;
      const imgId = `ff${String(i + 1).padStart(6, '0')}-0000-0000-0000-000000000001`;
      const attachment = [{
        id: imgId,
        position: 0,
        screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${cid}%2F${owner1}%2F${imgId}.jpg?alt=media&token=fixture`,
        thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${cid}%2F${owner1}%2Fthumb_${imgId}.jpg?alt=media&token=fixture`,
        image_hash: `b11122223333${String(i).padStart(4, '0')}`,
        sha256: `sha_094_rem_${i}`
      }];
      await inSession(db, owner1, async () => {
        await db.query(`
          select public.submit_tester_report_v3(
            p_submission_context => 'app_test',
            p_campaign_id => '${cid}',
            p_content => 'Debt paydown test ${i}',
            p_rating => 5,
            p_client_submit_id => '${reqId}',
            p_attachments => '${JSON.stringify(attachment)}'::jsonb,
            p_mission_id => null
          )
        `);
      });
    }

    const owner1AfterDebt = (await q(db, `select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
    assert.equal(owner1AfterDebt.met, true, 'Owner must be met=true after completing base + carry_in');
    assert.equal(owner1AfterDebt.carryover_remaining_tests, 0);
    assert.equal((await q(db, `select daily_missed_streak s from public.profiles where id = '${owner1}'`)).s, 0);

    // 090 Action Gate must allow owner to pause their campaign once met=true and carryover_remaining=0
    await inSession(db, owner1, async () => {
      await db.exec(`update public.campaigns set status = 'paused', paused_reason = 'owner_manual' where id = 'fa000000-0000-0000-0000-000000000001'`);
    });

    console.log('PASS: Test 1 - Mid-day coverage_start normalization, pre-coverage recovery, R08 protection, debt clearance & 090 action gate');
  } finally {
    await db.close();
  }
}

// ============================================================================
// TEST 2: Same-day coverage_start (marker_date = today) + 12/12 completion +
//         pre-coverage unproven day stays history_ready = false + NULL login
// ============================================================================
{
  const db = await createDb();
  try {
    for (const m of allMigrations) {
      await db.exec(read(`supabase/migrations/${m}`));
    }
    const supplyIds = await seedBase(db);

    // Simulate 066 deployed TODAY mid-day, then 094 normalized it to today 00:00:00+07
    await db.exec(`
      update public.system_provenance_markers
      set marker_date = ${today},
          marker_time = (${today})::timestamp at time zone 'Asia/Ho_Chi_Minh'
      where marker_key = 'coverage_start';
    `);

    // Yesterday (< coverage_start) remains unproven history_ready = false
    const yestStatus = (await q(db, `select public.get_daily_task_status('${owner1}', ${today} - 1) as s`)).s;
    assert.equal(yestStatus.history_ready, false, 'Pre-coverage day remains history_ready=false');

    // Today (= coverage_start) for pre-coverage user with NO row on yesterday:
    // carryover_ready must be true, carry_in_tests = 0
    const todayBefore = (await q(db, `select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
    assert.equal(todayBefore.history_ready, true);
    assert.equal(todayBefore.carryover_ready, true, 'Same-day coverage_start must anchor with carryover_ready=true');
    assert.equal(todayBefore.carry_in_tests, 0);
    assert.equal(todayBefore.required_tests, 12);
    assert.equal(todayBefore.met, false);

    // Open Home (get_my_dashboard) -> creates unfinalized user_daily_tasks row, then complete 12/12
    await inSession(db, owner1, async () => {
      await db.query(`select public.get_my_dashboard(false)`);
    });
    await submitAppTests(db, owner1, supplyIds, 12);

    const dashAfter = await inSession(db, owner1, async () => {
      return (await q(db, `select public.get_my_dashboard(false) as d`)).d;
    });
    assert.equal(dashAfter.daily_task.completed_tests, 12);
    assert.equal(dashAfter.daily_task.required_tests, 12);
    assert.equal(dashAfter.daily_task.met, true, '12/12 on same-day coverage_start must show met=true');
    assert.equal(dashAfter.daily_task.carryover_ready, true);

    const udtToday = await q(db, `
      select completed_tests, met, logged_in from public.user_daily_tasks
      where user_id = '${owner1}' and task_date = ${today}
    `);
    assert.equal(udtToday.completed_tests, 12);
    assert.equal(udtToday.met, true);
    assert.equal(udtToday.logged_in, true);

    // NULL last_login_date user must return boolean false (not JSON null) for logged_in_today & met
    const nullLoginStatus = (await q(db, `select public.get_daily_task_status('${nullLoginUser}', ${today}) as s`)).s;
    assert.strictEqual(nullLoginStatus.logged_in_today, false, 'logged_in_today must be boolean false when last_login_date is null');
    assert.strictEqual(nullLoginStatus.met, false, 'met must be boolean false when last_login_date is null');

    // Marker earlier than 00:00 must be preserved by least()
    await db.exec(`
      update public.system_provenance_markers
      set marker_date = ${today},
          marker_time = (${today} - 1)::timestamp at time zone 'Asia/Ho_Chi_Minh'
      where marker_key = 'coverage_start';
    `);
    await db.exec(migration094);
    const preservedMarker = await q(db, `
      select marker_time = (${today} - 1)::timestamp at time zone 'Asia/Ho_Chi_Minh' as preserved
      from public.system_provenance_markers where marker_key = 'coverage_start'
    `);
    assert.equal(preservedMarker.preserved, true, '094 must preserve marker_time earlier than marker_date 00:00');

    console.log('PASS: Test 2 - Same-day coverage_start 12/12 completion, NULL last_login_date safety, and least() marker preservation');
  } finally {
    await db.close();
  }
}

// ============================================================================
// TEST 3: 00:00-00:05 AM login-only race condition (restored 082 refresh check)
//         + 12/12 vs 14/14 when yesterday had 2 carry_out + unresolved diagnostic
// ============================================================================
{
  const db = await createDb();
  try {
    for (const m of allMigrations) {
      await db.exec(read(`supabase/migrations/${m}`));
    }
    const supplyIds = await seedBase(db);

    await db.exec(`
      update public.system_provenance_markers
      set marker_date = ${today} - 10,
          marker_time = (${today} - 10)::timestamp at time zone 'Asia/Ho_Chi_Minh'
      where marker_key = 'coverage_start';

      -- Seal today - 2 for owner1 and member1 so only today - 1 (yesterday) is unsealed
      insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
      values
        ('${owner1}', ${today} - 2, 'owner', now(), 0, 0, true),
        ('${member1}', ${today} - 2, 'member', now(), 0, 0, true);

      -- Yesterday (today - 1): owner1 completed 10/12 -> will seal with carry_out = 2, missed_streak = 1
      insert into public.daily_test_events_journal(
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
      )
      select 'checkin', gen_random_uuid(), '${owner1}', id, owner_id, ${today} - 1, 'valid', true, 'checkin', 'verified_checkin',
        (${today} - 1)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '10 hours'
      from public.campaigns
      where owner_id <> '${owner1}' and owner_id <> '${owner2}'
      order by id limit 10;
    `);

    // At 00:01 AM (before cron seals yesterday), both owner1 and member1 open Home (login only, NO test events today!)
    await inSession(db, owner1, async () => {
      await db.query(`select public.get_my_dashboard(false)`);
    });
    await inSession(db, member1, async () => {
      await db.query(`select public.get_my_dashboard(false)`);
    });

    const ownerBeforeCron = await q(db, `
      select carry_in_tests, required_tests, met from public.user_daily_tasks
      where user_id = '${owner1}' and task_date = ${today}
    `);
    assert.equal(ownerBeforeCron.carry_in_tests, 0, 'Before cron, carry_in_tests is 0');

    // Simulate 00:05 AM cron sealing yesterday (today - 1)
    await db.exec(`select public._apply_daily_inactivity_penalty(${today} - 1)`);

    // CRITICAL CHECK (Bug 3A): Even with ZERO daily_test_events_journal rows today,
    // owner1 and member1 must have their today row and streak refreshed!
    const ownerAfterCron = await q(db, `
      select t.carry_in_tests, t.base_required_tests, t.required_tests, t.met, p.daily_missed_streak
      from public.user_daily_tasks t
      join public.profiles p on p.id = t.user_id
      where t.user_id = '${owner1}' and t.task_date = ${today}
    `);
    assert.equal(ownerAfterCron.carry_in_tests, 2, 'Owner today row must immediately reflect carry_in=2 after 00:05 cron');
    assert.equal(ownerAfterCron.base_required_tests, 12);
    assert.equal(ownerAfterCron.required_tests, 14);
    assert.equal(ownerAfterCron.met, false);
    assert.equal(ownerAfterCron.daily_missed_streak, 1);

    const memberAfterCron = await q(db, `
      select t.logged_in, t.met, p.daily_missed_streak
      from public.user_daily_tasks t
      join public.profiles p on p.id = t.user_id
      where t.user_id = '${member1}' and t.task_date = ${today}
    `);
    assert.equal(memberAfterCron.logged_in, true);
    assert.equal(memberAfterCron.met, true, 'Member today row must have met=true after 00:05 cron');
    assert.equal(memberAfterCron.daily_missed_streak, 0, 'Member daily_missed_streak must reset to 0 after 00:05 cron');

    // Now owner1 completes 12/14 -> NOT yet met (still owes 2 carryover), 090 gate blocks with OWNER_CARRYOVER_INCOMPLETE:2
    await submitAppTests(db, owner1, supplyIds, 12);
    const s12 = (await q(db, `select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
    assert.equal(s12.completed_tests, 12);
    assert.equal(s12.required_tests, 14);
    assert.equal(s12.carryover_remaining_tests, 2);
    assert.equal(s12.met, false);

    await assert.rejects(
      () => inSession(db, owner1, () => db.query(`select public.delete_owned_campaign('fa000000-0000-0000-0000-000000000001')`)),
      /OWNER_CARRYOVER_INCOMPLETE:2/
    );

    // Owner1 completes remaining 2 (14/14) -> met=true, streak=0, user_daily_tasks.met=true, 090 gate allows!
    await submitAppTests(db, owner1, supplyIds, 2, 12);
    const s14 = (await q(db, `select public.get_daily_task_status('${owner1}', ${today}) as s`)).s;
    assert.equal(s14.completed_tests, 14);
    assert.equal(s14.carryover_remaining_tests, 0);
    assert.equal(s14.met, true);
    assert.equal((await q(db, `select daily_missed_streak s from public.profiles where id = '${owner1}'`)).s, 0);
    assert.equal((await q(db, `select met from public.user_daily_tasks where user_id = '${owner1}' and task_date = ${today}`)).met, true);

    // Also verify diagnose_daily_task_stuck_users STILL catches UNRESOLVED_EVENT_SOURCE at coverage_start_date
    await db.exec(`
      insert into public.daily_test_events_journal(
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
      ) values (
        'report', gen_random_uuid(), '${nullLoginUser}', '${supplyIds[0].cid}', '${supplyIds[0].sid}',
        ${today} - 10, 'pending', false, null, 'unresolved_missing_source', now()
      );
    `);
    const unresolvedDiag = await q(db, `
      select stuck_reason, first_stuck_date = (${today} - 10) as at_cov
      from public.diagnose_daily_task_stuck_users()
      where user_id = '${nullLoginUser}'
    `);
    assert.equal(unresolvedDiag.stuck_reason, 'UNRESOLVED_EVENT_SOURCE');
    assert.equal(unresolvedDiag.at_cov, true);

    console.log('PASS: Test 3 - 00:00-00:05 login-only refresh, 12/14 carryover gate vs 14/14 completion, and unresolved diagnostic preserved');
  } finally {
    await db.close();
  }
}

console.log('=== ALL MIGRATION 094 FIX TESTS PASSED ===');
