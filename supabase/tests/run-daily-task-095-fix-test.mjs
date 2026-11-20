import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;
const q = async (db, sql) => (await db.query(sql)).rows[0];

const allMigrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n))
  .sort();
const pre095Migrations = allMigrations.filter(n => Number(n.slice(0, 3)) <= 94);
const migration095 = read('supabase/migrations/095_enable_immediate_quota_and_catchup_carryover.sql');

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

async function seedSupply(db, count = 25, campaignDaysAgo = 20, userDaysAgo = 60) {
  const supplyIds = [];
  for (let i = 1; i <= count; i++) {
    const sid = `e5000000-0000-0000-0000-${String(i).padStart(12, '0')}`;
    const cid = `eb000000-0000-0000-0000-${String(i).padStart(12, '0')}`;
    supplyIds.push({ sid, cid });
    await db.exec(`
      insert into auth.users(id, email) values ('${sid}', 'supply095-${i}@test.local');
      update public.profiles
      set created_at = (${today} - ${userDaysAgo})::timestamp at time zone 'Asia/Ho_Chi_Minh',
          role = 'admin',
          last_login_date = ${today} - ${userDaysAgo}
      where id = '${sid}';
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at)
      values ('${cid}', '${sid}', 'com.supply095.${i}', 'Supply095 ${i}', 'open', 1, (${today} - ${campaignDaysAgo})::timestamp at time zone 'Asia/Ho_Chi_Minh');
    `);
  }
  return supplyIds;
}

async function submitAppTests(db, uid, supplyIds, count, offset = 0) {
  for (let i = 0; i < count; i++) {
    const idx = offset + i;
    const { cid } = supplyIds[idx];
    const reqId = `ec${String(idx + 1).padStart(6, '0')}-0000-0000-0000-000000000001`;
    const imgId = `ed${String(idx + 1).padStart(6, '0')}-0000-0000-0000-000000000001`;
    const attachment = [{
      id: imgId,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${cid}%2F${uid}%2F${imgId}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${cid}%2F${uid}%2Fthumb_${imgId}.jpg?alt=media&token=fixture`,
      image_hash: `e11122223333${String(idx).padStart(4, '0')}`,
      sha256: `sha_095_${uid}_${idx}`
    }];
    const res = await inSession(db, uid, async () => {
      return (await db.query(`
        select public.submit_tester_report_v3(
          p_submission_context => 'app_test',
          p_campaign_id => '${cid}',
          p_content => 'Regular test 095 ${i + 1}',
          p_rating => 5,
          p_client_submit_id => '${reqId}',
          p_attachments => '${JSON.stringify(attachment)}'::jsonb,
          p_mission_id => null
        ) as r
      `)).rows[0].r;
    });
    assert.equal(res.success, true);
  }
}

console.log('=== RUNNING MIGRATION 095 ACCEPTANCE & CATCH-UP TESTS ===');

// ============================================================================
// TEST 1 & 2 & 3 (UPGRADE FROM 094 -> 095):
// - Immediate quota enabled by default (campaign created yesterday w/ 4/12)
// - Historical unresolved_missing_source / unresolved_unknown_context reconciled
// - Automatic catch-up across >31 days inside 095 without waiting for 00:05 cron
// - Pre-coverage finalized row preserves carry_out_tests & missed_streak
// - Pre-coverage signup day owner falls back to registration-day exemption
// - Idempotent re-run of 095
// ============================================================================
{
  const db = await createDb();
  try {
    for (const m of pre095Migrations) {
      await db.exec(read(`supabase/migrations/${m}`));
    }

    const ownerYest = 'a1000000-0000-0000-0000-000000000001';
    const orphanUser = 'a1000000-0000-0000-0000-000000000002';
    const preCovUser = 'a1000000-0000-0000-0000-000000000003';
    const preCovRegOwner = 'a1000000-0000-0000-0000-000000000004';
    const deletedCamp = 'ab000000-0000-0000-0000-000000000099';

    // Confirm before 095 immediate quota is inactive
    const beforeImmediate = (await q(db, `select public.is_immediate_daily_quota_active() as act`)).act;
    assert.equal(beforeImmediate, false, 'Before 095, immediate quota is disabled by default');

    const supplyIds = await seedSupply(db, 25, 20, 60);

    await db.exec(`
      insert into auth.users(id, email) values
        ('${ownerYest}', 'owneryest-095@test.local'),
        ('${orphanUser}', 'orphan-095@test.local'),
        ('${preCovUser}', 'precov-095@test.local'),
        ('${preCovRegOwner}', 'precovreg-095@test.local');

      update public.profiles
      set created_at = (${today} - 60)::timestamp at time zone 'Asia/Ho_Chi_Minh',
          credits = 10000,
          last_login_date = ${today} - 60,
          daily_missed_streak = 0
      where id in ('${ownerYest}', '${orphanUser}', '${preCovUser}');

      -- preCovRegOwner registered BEFORE coverage_start (today - 40) and posted a campaign on signup day
      update public.profiles
      set created_at = (${today} - 40)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '9 hours',
          credits = 10000,
          last_login_date = ${today} - 40,
          daily_missed_streak = 0
      where id = '${preCovRegOwner}';

      -- Configure ratio 12:1 and carryover active since today - 50
      set test.allow_policy_retroactive_write = 'true';
      select public.set_daily_task_carryover_policy(${today} - 50, true, '095 upgrade test');
      reset test.allow_policy_retroactive_write;

      set session_replication_role = 'replica';
      update public.daily_task_policies set tests_per_active_app = 12 where effective_from = '-infinity'::date;
      set session_replication_role = 'origin';

      -- Coverage start is 35 days ago (> 31 days to prove multi-batch catch-up inside 095!)
      update public.system_provenance_markers
      set marker_date = ${today} - 35,
          marker_time = (${today} - 35)::timestamp at time zone 'Asia/Ho_Chi_Minh'
      where marker_key = 'coverage_start';

      -- ownerYest created a campaign YESTERDAY afternoon and only completed 4/12 tests yesterday
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at)
      values ('ab000000-0000-0000-0000-000000000001', '${ownerYest}', 'com.test095.yest', 'Yest App', 'open', 1,
              (${today} - 1)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '14 hours');

      -- orphanUser had an active app since 5 days ago, plus an unresolved_missing_source row 3 days ago
      -- (plus a matching checkin on supplyIds[0] and an orphan report on a deleted campaign with no checkin)
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at)
      values ('ab000000-0000-0000-0000-000000000002', '${orphanUser}', 'com.test095.orphan', 'Orphan App', 'open', 1,
              (${today} - 5)::timestamp at time zone 'Asia/Ho_Chi_Minh');

      -- preCovRegOwner created an app on pre-coverage signup day (today - 40)
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at)
      values ('ab000000-0000-0000-0000-000000000004', '${preCovRegOwner}', 'com.test095.precovreg', 'PreCovReg App', 'open', 1,
              (${today} - 40)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '10 hours');
    `);

    // Give ownerYest 4/12 checkins yesterday
    for (let i = 0; i < 4; i++) {
      await db.exec(`
        insert into public.daily_test_events_journal(
          source_type, source_id, user_id, campaign_id, campaign_owner_id,
          activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
        ) values (
          'checkin', gen_random_uuid(), '${ownerYest}', '${supplyIds[i].cid}', '${supplyIds[i].sid}',
          ${today} - 1, 'created', true, 'checkin', 'verified_checkin',
          (${today} - 1)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '16 hours'
        );
      `);
    }

    // Seal ownerYest up to today - 2 so only yesterday (today - 1) was left unfinalized for ownerYest
    // (Wait: leave ownerYest unfinalized since coverage_start or seal today - 2; either way 095 seals through yesterday!)
    await db.exec(`
      insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
      values ('${ownerYest}', ${today} - 2, 'member', now(), 0, 0, true);

      -- Give ownerYest a pre-created unfinalized today row (e.g. logged in after 00:00 before 095)
      update public.profiles set last_login_date = ${today} where id = '${ownerYest}';
      insert into public.user_daily_tasks(user_id, task_date, logged_in)
      values ('${ownerYest}', ${today}, true);

      -- Give preCovUser a finalized row BEFORE coverage_start (today - 38) with carry_out_tests = 5, missed_streak = 2
      insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met, carryover_version)
      values ('${preCovUser}', ${today} - 38, 'owner', now(), 5, 2, false, 1);

      -- Give orphanUser:
      --   (a) a matching checkin + unresolved_missing_source report on supplyIds[0] at today - 3
      --   (b) an unresolved_unknown_context report on deletedCamp (no checkin) at today - 3
      insert into public.checkins(id, campaign_id, user_id, checkin_date, note)
      values ('ac000000-0000-0000-0000-000000000001', '${supplyIds[0].cid}', '${orphanUser}', ${today} - 3, 'Real test');

      insert into public.daily_test_events_journal(
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
      ) values
      (
        'report', gen_random_uuid(), '${orphanUser}', '${supplyIds[0].cid}', '${supplyIds[0].sid}',
        ${today} - 3, 'pending', false, null, 'unresolved_missing_source',
        (${today} - 3)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '11 hours'
      ),
      (
        'report', gen_random_uuid(), '${orphanUser}', '${deletedCamp}', '00000000-0000-0000-0000-000000000000'::uuid,
        ${today} - 3, 'pending', false, null, 'unresolved_unknown_context',
        (${today} - 3)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '12 hours'
      );

      -- Normalize journal timestamps to match created_at for fixture consistency
      update public.campaign_status_journal j
      set valid_from = c.created_at
      from public.campaigns c
      where j.campaign_id = c.id;
      update public.user_role_journal set valid_from = (${today} - 60)::timestamp at time zone 'Asia/Ho_Chi_Minh';
    `);

    // Delete campaign_status_journal for ownerYest's campaign & shift supply valid_from forward to test 095 backfill/normalization
    await db.exec(`
      delete from public.campaign_status_journal where campaign_id = 'ab000000-0000-0000-0000-000000000001';
      update public.campaign_status_journal
      set valid_from = now()
      where campaign_id = '${supplyIds[0].cid}';
    `);

    // APPLY MIGRATION 095 (NO manual cron call afterward!)
    await db.exec(migration095);

    // 1. Immediate quota is now active by default
    const afterImmediate = (await q(db, `select public.is_immediate_daily_quota_active() as act`)).act;
    assert.equal(afterImmediate, true, '095 must enable immediate daily quota by default');

    const policyRow = await q(db, `
      select is_enabled, operator_note
      from public.daily_task_immediate_quota_policies
      where effective_from = '-infinity'::date
    `);
    assert.equal(policyRow.is_enabled, true);
    assert.equal(policyRow.operator_note, 'Migration 095: Enable immediate daily quota on campaign creation');

    const auditCount = (await q(db, `
      select count(*)::int c from public.daily_task_immediate_quota_policy_audit
      where effective_from = '-infinity'::date and new_is_enabled = true
    `)).c;
    assert.equal(auditCount, 1, '095 must record audit row for enabling immediate quota');

    // 2. campaign_status_journal backfilled and normalized
    const backfilledCount = (await q(db, `
      select count(*)::int c from public.campaign_status_journal
      where campaign_id = 'ab000000-0000-0000-0000-000000000001'
    `)).c;
    assert.equal(backfilledCount, 1, '095 must backfill missing campaign_status_journal rows');

    const normalizedSupply = await q(db, `
      select valid_from <= greatest(
          campaign_created_at,
          (select marker_time from public.system_provenance_markers where marker_key = 'coverage_start')
      ) as ok
      from public.campaign_status_journal
      where campaign_id = '${supplyIds[0].cid}'
      order by id asc limit 1
    `);
    assert.equal(normalizedSupply.ok, true, '095 must normalize earliest campaign_status_journal valid_from');

    // 3. ownerYest: campaign created yesterday with 4/12 tests -> sealed yesterday with carry_out=8, missed_streak=1
    //    AND today's user_daily_tasks + profiles.daily_missed_streak immediately synced!
    const yestRow = await q(db, `
      select mode, base_required_tests, completed_tests, carry_out_tests, missed_streak, met, finalized_at is not null as sealed
      from public.user_daily_tasks
      where user_id = '${ownerYest}' and task_date = ${today} - 1
    `);
    assert.equal(yestRow.sealed, true, 'Yesterday must be sealed automatically by 095');
    assert.equal(yestRow.mode, 'owner');
    assert.equal(yestRow.base_required_tests, 12);
    assert.equal(yestRow.completed_tests, 4);
    assert.equal(yestRow.carry_out_tests, 8, 'Yesterday unmet 4/12 must carry out 8');
    assert.equal(yestRow.missed_streak, 1, 'Yesterday unmet must record missed_streak = 1');
    assert.equal(yestRow.met, false);

    const ownerYestTodayStatus = (await q(db, `select public.get_daily_task_status('${ownerYest}', ${today}) as s`)).s;
    assert.equal(ownerYestTodayStatus.carry_in_tests, 8);
    assert.equal(ownerYestTodayStatus.base_required_tests, 12);
    assert.equal(ownerYestTodayStatus.required_tests, 20);
    assert.equal(ownerYestTodayStatus.missed_streak, 1);

    const ownerYestTodayUdt = await q(db, `
      select carry_in_tests, required_tests, met
      from public.user_daily_tasks
      where user_id = '${ownerYest}' and task_date = ${today}
    `);
    assert.equal(ownerYestTodayUdt.carry_in_tests, 8, '095 catch-up must refresh today user_daily_tasks row');
    assert.equal(ownerYestTodayUdt.required_tests, 20);
    assert.equal(ownerYestTodayUdt.met, false);

    // 4. orphanUser: unresolved_missing_source / unresolved_unknown_context reconciled -> yesterday finalized!
    const recMatched = await q(db, `
      select submission_context, reconciliation_status, is_qualifying
      from public.daily_test_events_journal
      where user_id = '${orphanUser}' and campaign_id = '${supplyIds[0].cid}' and source_type = 'report'
    `);
    assert.equal(recMatched.submission_context, 'app_test');
    assert.equal(recMatched.reconciliation_status, 'verified_app_test');
    assert.equal(recMatched.is_qualifying, true);

    const recOrphan = await q(db, `
      select reconciliation_status, is_qualifying
      from public.daily_test_events_journal
      where user_id = '${orphanUser}' and campaign_id = '${deletedCamp}'
    `);
    assert.equal(recOrphan.reconciliation_status, 'verified_deletion');
    assert.equal(recOrphan.is_qualifying, false);

    const orphanSealedDays = (await q(db, `
      select count(*)::int c from public.user_daily_tasks
      where user_id = '${orphanUser}' and task_date between (${today} - 35) and (${today} - 1) and finalized_at is not null
    `)).c;
    assert.equal(orphanSealedDays, 35, 'All 35 days through yesterday must seal for orphanUser once reconciled');

    const orphanYest = await q(db, `
      select carry_out_tests, missed_streak, finalized_at is not null as sealed
      from public.user_daily_tasks
      where user_id = '${orphanUser}' and task_date = ${today} - 1
    `);
    assert.equal(orphanYest.sealed, true);
    assert.ok(orphanYest.carry_out_tests > 0, 'orphanUser must carry out unmet tests');
    assert.equal(orphanYest.missed_streak, 35, 'orphanUser missed 35 consecutive days (30 member no-login + 5 owner) -> streak 35');
    assert.equal((await q(db, `select daily_missed_streak s from public.profiles where id = '${orphanUser}'`)).s, 35);

    // 5. preCovUser: pre-coverage finalized row at (today - 38) preserves carry_out_tests=5 & missed_streak=2 at coverage_start_date
    const preCovAnchorRow = await q(db, `
      select carry_in_tests, carry_out_tests, missed_streak
      from public.user_daily_tasks
      where user_id = '${preCovUser}' and task_date = ${today} - 35
    `);
    assert.equal(preCovAnchorRow.carry_in_tests, 5, 'Pre-coverage carry_out=5 must be preserved at coverage_start_date');
    assert.equal(preCovAnchorRow.carry_out_tests, 5);
    assert.equal(preCovAnchorRow.missed_streak, 3, 'Pre-coverage missed_streak=2 must increment to 3 if missed at coverage_start_date');

    // 6. preCovRegOwner: pre-coverage signup day (today - 40 < coverage_start) falls back to exempt when evaluated directly
    await db.exec(`
      set test.allow_future_finalize = 'true';
      select public._apply_daily_inactivity_penalty(${today} - 40, ${today} - 40);
      reset test.allow_future_finalize;
    `);
    const preCovRegRow = await q(db, `
      select is_exempt, met, penalty_credits, finalized_at is not null as sealed
      from public.user_daily_tasks
      where user_id = '${preCovRegOwner}' and task_date = ${today} - 40
    `);
    assert.equal(preCovRegRow.sealed, true, 'Pre-coverage signup day must seal cleanly');
    assert.equal(preCovRegRow.is_exempt, true, 'Pre-coverage signup day must fall back to registration-day exemption');
    assert.equal(preCovRegRow.penalty_credits, 0);

    // 7. Re-running 095 must be idempotent (no duplicate audit rows, no double penalties)
    const creditsBeforeRerun = (await q(db, `select credits from public.profiles where id = '${ownerYest}'`)).credits;
    await db.exec(migration095);
    const creditsAfterRerun = (await q(db, `select credits from public.profiles where id = '${ownerYest}'`)).credits;
    assert.equal(creditsAfterRerun, creditsBeforeRerun, 'Re-running 095 must not double-penalize');
    const auditCountAfterRerun = (await q(db, `
      select count(*)::int c from public.daily_task_immediate_quota_policy_audit
      where effective_from = '-infinity'::date and new_is_enabled = true
    `)).c;
    assert.equal(auditCountAfterRerun, 1, 'Re-running 095 must not insert duplicate audit rows');

    console.log('PASS: Tests 1-3 - Default immediate quota, orphan reconciliation, 35-day catch-up, pre-coverage anchor & idempotency');
  } finally {
    await db.close();
  }
}

// ============================================================================
// TEST 4: Member with unpaid carryover_remaining_tests > 0 from yesterday
//         preserves daily_missed_streak on login until debt is cleared,
//         AND unresolved reg-day owner does not pin v_earliest_unfinalized
// ============================================================================
{
  const db = await createDb();
  try {
    for (const m of allMigrations) {
      await db.exec(read(`supabase/migrations/${m}`));
    }

    const pausedOwner = 'a2000000-0000-0000-0000-000000000001';
    const regUnresolvedOwner = 'a2000000-0000-0000-0000-000000000002';
    const normalUser = 'a2000000-0000-0000-0000-000000000003';
    const supplyIds = await seedSupply(db, 20, 15, 30);

    await db.exec(`
      insert into auth.users(id, email) values
        ('${pausedOwner}', 'paused-095@test.local'),
        ('${regUnresolvedOwner}', 'regunres-095@test.local'),
        ('${normalUser}', 'normal-095@test.local');

      update public.profiles
      set created_at = (${today} - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh',
          credits = 5000,
          last_login_date = ${today} - 30,
          daily_missed_streak = 0
      where id in ('${pausedOwner}', '${normalUser}');

      -- regUnresolvedOwner signed up 3 days ago and posted an app on signup day, plus has an unresolved event on signup day
      update public.profiles
      set created_at = (${today} - 3)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '8 hours',
          credits = 5000,
          last_login_date = ${today} - 3
      where id = '${regUnresolvedOwner}';

      set test.allow_policy_retroactive_write = 'true';
      select public.set_daily_task_carryover_policy(${today} - 20, true, '095 member debt test');
      reset test.allow_policy_retroactive_write;

      set session_replication_role = 'replica';
      update public.daily_task_policies set tests_per_active_app = 12 where effective_from = '-infinity'::date;
      set session_replication_role = 'origin';

      update public.system_provenance_markers
      set marker_date = ${today} - 10,
          marker_time = (${today} - 10)::timestamp at time zone 'Asia/Ho_Chi_Minh'
      where marker_key = 'coverage_start';

      -- pausedOwner had an open campaign yesterday (missed 4/12 -> carry_out = 8, missed_streak = 2),
      -- and today the campaign is paused (owner_manual) -> mode = 'member'
      insert into public.campaigns(id, owner_id, package_name, app_name, status, paused_reason, slot_number, created_at)
      values ('ac000000-0000-0000-0000-000000000001', '${pausedOwner}', 'com.test095.paused', 'Paused App', 'paused', 'owner_manual', 1,
              (${today} - 10)::timestamp at time zone 'Asia/Ho_Chi_Minh');

      insert into public.user_daily_tasks(user_id, task_date, mode, active_app_count, required_tests, effective_required_tests, completed_tests, carry_out_tests, missed_streak, met, finalized_at)
      values
        ('${pausedOwner}', ${today} - 1, 'owner', 1, 12, 12, 4, 8, 2, false, now()),
        ('${normalUser}', ${today} - 4, 'member', 0, 0, 0, 0, 0, 0, true, now());

      -- Seal supplyIds at today - 4 so only today - 3 .. today - 1 remain unfinalized in the system
      insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
      select id, ${today} - 4, 'owner', now(), 0, 0, true
      from public.profiles
      where id::text like 'e5000000-%';

      update public.profiles set daily_missed_streak = 2 where id = '${pausedOwner}';

      -- regUnresolvedOwner has an active app on signup day (today - 3) and an unresolved event on today - 3
      insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, created_at)
      values ('ac000000-0000-0000-0000-000000000002', '${regUnresolvedOwner}', 'com.test095.regunres', 'RegUnres App', 'open', 1,
              (${today} - 3)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '10 hours');

      insert into public.daily_test_events_journal(
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
      ) values (
        'report', gen_random_uuid(), '${regUnresolvedOwner}', '${supplyIds[0].cid}', '${supplyIds[0].sid}',
        ${today} - 3, 'pending', false, null, 'unresolved_unknown_context',
        (${today} - 3)::timestamp at time zone 'Asia/Ho_Chi_Minh' + interval '12 hours'
      );

      update public.campaign_status_journal j
      set valid_from = c.created_at
      from public.campaigns c
      where j.campaign_id = c.id;
      update public.user_role_journal set valid_from = (${today} - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh';
    `);

    // 4a. pausedOwner logs in today (mode = 'member', carryover_remaining_tests = 8 > 0)
    await db.exec(`select public.record_daily_login_internal('${pausedOwner}'::uuid, ${today})`);

    const pausedStatus = (await q(db, `select public.get_daily_task_status('${pausedOwner}', ${today}) as s`)).s;
    assert.equal(pausedStatus.mode, 'member');
    assert.equal(pausedStatus.met, true);
    assert.equal(pausedStatus.carryover_remaining_tests, 8);

    const streakAfterLogin = (await q(db, `select daily_missed_streak s from public.profiles where id = '${pausedOwner}'`)).s;
    assert.equal(streakAfterLogin, 2, 'Member with unpaid carryover_remaining_tests > 0 must NOT wipe daily_missed_streak on login');

    // Clear the 8 carryover tests -> streak must now reset to 0!
    await submitAppTests(db, pausedOwner, supplyIds, 8);
    const pausedAfterPaydown = (await q(db, `select public.get_daily_task_status('${pausedOwner}', ${today}) as s`)).s;
    assert.equal(pausedAfterPaydown.carryover_remaining_tests, 0);
    const streakAfterPaydown = (await q(db, `select daily_missed_streak s from public.profiles where id = '${pausedOwner}'`)).s;
    assert.equal(streakAfterPaydown, 0, 'Once carryover debt reaches 0, daily_missed_streak must reset to 0');

    // 4b. regUnresolvedOwner at (today - 3) must NOT pin v_earliest_unfinalized or block normalUser from finalizing through yesterday
    // Run apply_daily_inactivity_penalty with batch_limit = 1 multiple times:
    // If regUnresolvedOwner pinned v_earliest_unfinalized at today - 3, batch_limit = 1 would only ever process today - 3!
    await db.exec(`select public.apply_daily_inactivity_penalty(1)`);
    await db.exec(`select public.apply_daily_inactivity_penalty(1)`);
    await db.exec(`select public.apply_daily_inactivity_penalty(1)`);
    await db.exec(`select public.apply_daily_inactivity_penalty(1)`);

    const normalUserYest = await q(db, `
      select finalized_at is not null as sealed
      from public.user_daily_tasks
      where user_id = '${normalUser}' and task_date = ${today} - 1
    `);
    assert.equal(normalUserYest.sealed, true, 'Unresolved reg-day owner must not pin v_earliest_unfinalized and block normalUser');

    console.log('PASS: Test 4 - Member debt streak preservation & unresolved reg-day owner non-pinning');
  } finally {
    await db.close();
  }
}

console.log('=== ALL MIGRATION 095 FIX TESTS PASSED ===');
