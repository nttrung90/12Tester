import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'c3000000-0000-0000-0000-000000000001';
const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;
const q = async sql => (await db.query(sql)).rows[0];
let supply = [];

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

async function checkpoint(offset, debt = 0) {
  await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,finalized_at,carry_out_tests,missed_streak,met)
    values('${owner}',${today}-${offset},'owner',now(),${debt},0,true)`);
}

async function policy(offset, enabled) {
  await db.exec(`set test.allow_policy_retroactive_write='true';
    select public.set_daily_task_carryover_policy(${today}-${offset},${enabled},'Carryover acceptance fixture');
    reset test.allow_policy_retroactive_write`);
}

async function events(offset, n) {
  for (const id of supply.slice(0, n)) {
    await db.exec(`insert into public.daily_test_events_journal
      (source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
      select 'checkin',gen_random_uuid(),'${owner}',id,owner_id,${today}-${offset},'valid',true,
        (${today}-${offset})::timestamp at time zone 'Asia/Ho_Chi_Minh' from public.campaigns where id='${id}'`);
  }
}

async function status(offset = 0) {
  return (await q(`select public.get_daily_task_status('${owner}',${today}-${offset}) s`)).s;
}

async function seal(offset) {
  await db.exec(`select public._apply_daily_inactivity_penalty(${today}-${offset})`);
  return await q(`select carry_in_tests,carry_out_tests,completed_tests,required_tests,met,penalty_credits
    from public.user_daily_tasks where user_id='${owner}' and task_date=${today}-${offset} and finalized_at is not null`);
}

try {
  console.log('=== LOADING ALL ACTIVE MIGRATIONS IN ORDER (062 TO 080) ===');
  const migrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(n => /^\d{3}_.*\.sql$/.test(n))
    .sort();
  for (const name of migrations) {
    await db.exec(read('supabase/migrations/' + name));
  }
  console.log('ACTIVE MIGRATIONS LOADED:', migrations.join(', '));

  await db.exec(`
    insert into auth.users(id,email) values('${owner}','carryover-owner@test.local');
    update public.profiles set created_at=now()-interval '70 days',credits=10000,last_login_date=${today}-70 where id='${owner}';
    insert into public.user_slots(user_id,slot_number,expires_at) values('${owner}',2,now()+interval '20 days');
    insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at) values
      ('${owner}','audit.carry.one','Carry One','open',1,now()-interval '25 days'),
      ('${owner}','audit.carry.two','Carry Two','open',2,now()-interval '25 days');
  `);

  for (let i = 1; i <= 20; i++) {
    const id = `c4000000-0000-0000-0000-${String(i).padStart(12, '0')}`;
    await db.exec(`
      insert into auth.users(id,email) values('${id}','carry-supply-${i}@test.local');
      update public.profiles set created_at=now()-interval '70 days',role='admin' where id='${id}';
      insert into public.campaigns(owner_id,package_name,app_name,status,created_at)
        values('${id}','audit.carry.supply.${i}','Supply ${i}','open',now()-interval '25 days');
    `);
  }

  supply = (await db.query(`select id from public.campaigns where owner_id<>'${owner}' order by package_name`)).rows.map(x => x.id);
  await db.exec(`
    update public.campaign_status_journal set valid_from=now()-interval '25 days';
    update public.user_role_journal set valid_from=now()-interval '70 days';
    update public.system_provenance_markers set marker_time=(${today}-70)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${today}-70;
  `);

  // ============================================================================
  // C01 ACCEPTANCE TESTS
  // ============================================================================

  await scenario('C01: Real mission submission RPC does NOT count towards regular D or reduce regular debt', async () => {
    await policy(0, true);
    await checkpoint(1, 8);
    const campaign = supply[0];
    const supplier = (await q(`select owner_id from public.campaigns where id='${campaign}'`)).owner_id;
    const mission = 'c5000000-0000-0000-0000-000000000001';
    const request = 'c6000000-0000-0000-0000-000000000001';
    const image = 'c7000000-0000-0000-0000-000000000001';

    await db.exec(`
      update public.profiles set credits=10000 where id='${supplier}';
      insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
        values('${mission}','${supplier}','${campaign}','Carryover isolation',10,3,50,'open',now()+interval '3 days');
      insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${owner}','joined');
    `);

    const before = await status();
    assert.equal(before.completed_tests, 0);
    assert.equal(before.carryover_remaining_tests, 14, 'Owner with B=6, C=8 has total remaining 14');

    const attachment = [{
      id: image,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2F${image}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2Fthumb_${image}.jpg?alt=media&token=fixture`,
      image_hash: '1111222233334444',
      sha256: 'carryover_isolation_fixture'
    }];

    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    const response = (await q(`
      select public.submit_tester_report_v3(
        p_submission_context=>'mission',
        p_campaign_id=>'${campaign}',
        p_content=>'Mission only, no regular daily test',
        p_rating=>5,
        p_client_submit_id=>'${request}',
        p_attachments=>'${JSON.stringify(attachment)}'::jsonb,
        p_mission_id=>'${mission}'
      ) r
    `)).r;
    await db.exec('reset role;reset request.jwt.claim.sub');

    assert.equal(response.success, true);
    assert.equal(response.submission_context, 'mission');

    const after = await status();
    // CRITICAL: D must NOT increase! Debt must NOT decrease!
    assert.equal(after.completed_tests, 0, 'Mission report must NOT count as completed regular test');
    assert.equal(after.carryover_remaining_tests, 14, 'Mission report must NOT reduce carryover remaining tests');

    // Verify journal record
    const journalEntry = await q(`
      select source_type, submission_context, is_qualifying, reconciliation_status
      from public.daily_test_events_journal
      where user_id = '${owner}' and source_id = '${response.report_id}'
      order by id desc limit 1
    `);
    assert.equal(journalEntry.source_type, 'report');
    assert.equal(journalEntry.submission_context, 'mission');
    assert.equal(journalEntry.is_qualifying, false, 'Journal must record mission as is_qualifying=false');

    return { beforeD: before.completed_tests, afterD: after.completed_tests, afterDebt: after.carryover_remaining_tests };
  });

  await scenario('C01: Mission report CANNOT bypass campaign resume when debt remains', async () => {
    await policy(0, true);
    // User had 1 carryover debt yesterday, all campaigns paused -> member mode
    await checkpoint(1, 1);
    await db.exec(`
      update public.campaigns set status='paused',paused_reason='owner_manual' where owner_id='${owner}';
      update public.campaign_status_journal set valid_from=now()-interval '15 days';
    `);

    const campaign = supply[0];
    const supplier = (await q(`select owner_id from public.campaigns where id='${campaign}'`)).owner_id;
    const mission = 'c5000000-0000-0000-0000-000000000002';
    const request = 'c6000000-0000-0000-0000-000000000002';
    const image = 'c7000000-0000-0000-0000-000000000002';

    await db.exec(`
      update public.profiles set credits=10000, last_login_date=${today} where id='${owner}';
      insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
        values('${mission}','${supplier}','${campaign}','Carryover resume barrier',10,3,50,'open',now()+interval '3 days');
      insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${owner}','joined');
    `);

    // Submit mission report
    const attachment = [{
      id: image,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2F${image}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2Fthumb_${image}.jpg?alt=media&token=fixture`,
      image_hash: '2222333344445555',
      sha256: 'barrier_fixture'
    }];
    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    await q(`
      select public.submit_tester_report_v3(
        p_submission_context=>'mission',
        p_campaign_id=>'${campaign}',
        p_content=>'Mission report trying to clear debt',
        p_rating=>5,
        p_client_submit_id=>'${request}',
        p_attachments=>'${JSON.stringify(attachment)}'::jsonb,
        p_mission_id=>'${mission}'
      )
    `);
    await db.exec('reset role;reset request.jwt.claim.sub');

    // Status: mode is member, met is true (because logged in), BUT carryoverRemainingTests must STILL be 1!
    const s = await status();
    assert.equal(s.mode, 'member');
    assert.equal(s.carryover_remaining_tests, 1, 'Preserved debt of 1 must remain');

    // Attempting to resume campaign must be REJECTED with OWNER_CARRYOVER_INCOMPLETE:1!
    const owned = (await q(`select id from public.campaigns where owner_id='${owner}' order by slot_number limit 1`)).id;
    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    let resumeFailed = false;
    let errorMessage = '';
    await db.exec('savepoint resume_sp;');
    try {
      await q(`select public.resume_campaign('${owned}')`);
    } catch (e) {
      resumeFailed = true;
      errorMessage = e.message;
      await db.exec('rollback to savepoint resume_sp;');
    }
    await db.exec('reset role;reset request.jwt.claim.sub');

    assert.equal(resumeFailed, true, 'resume_campaign must fail while debt remains');
    assert.match(errorMessage, /OWNER_CARRYOVER_INCOMPLETE:1/);

    return { blockedError: errorMessage };
  });

  await scenario('C01: Regular app_test report DOES qualify and reduce debt, while rejected does not', async () => {
    await policy(0, true);
    await checkpoint(1, 2);
    const id1 = 'c8000000-0000-0000-0000-000000000001';
    await db.exec(`insert into public.tester_reports(id,user_id,campaign_id,submission_context,status,content)
      values('${id1}','${owner}','${supply[0]}','app_test','pending','Regular report 1')`);

    const pending = await status();
    assert.equal(pending.completed_tests, 1);
    assert.equal(pending.carryover_remaining_tests, 7); // base=6, carry_in=2 => effective=8; completed=1 => remaining=7

    await db.exec(`update public.tester_reports set status='approved' where id='${id1}'`);
    const approved = await status();
    assert.equal(approved.completed_tests, 1);

    await db.exec(`update public.tester_reports set status='rejected' where id='${id1}'`);
    const rejected = await status();
    assert.equal(rejected.completed_tests, 0);
    assert.equal(rejected.carryover_remaining_tests, 8); // debt not reduced by rejected report

    return { pending: pending.completed_tests, approved: approved.completed_tests, rejected: rejected.completed_tests };
  });

  // ============================================================================
  // C03 ACCEPTANCE TESTS
  // ============================================================================

  await scenario('C03: 40-day covered gap recovers in bounded batches with persistent progress and idempotence', async () => {
    // Checkpoint 40 days ago with debt 8
    await policy(40, true);
    await checkpoint(40, 8);

    // Initial check: sealed records > day-40 is 0, carryover_ready is false
    const initialSealed = (await q(`select count(*)::int c from public.user_daily_tasks where user_id='${owner}' and task_date>${today}-40 and finalized_at is not null`)).c;
    assert.equal(initialSealed, 0);
    assert.equal((await status()).carryover_ready, false);

    let prior = 0;
    let runs = 0;
    let s;
    for (; runs < 10; runs++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      const count = (await q(`select count(*)::int n from public.user_daily_tasks where user_id='${owner}' and task_date>${today}-40 and finalized_at is not null`)).n;
      s = await status();
      if (count > prior) {
        prior = count;
      }
      if (s.carryover_ready) break;
    }

    assert.equal(s.carryover_ready, true, 'Long gap must eventually reconcile');
    assert.equal(prior, 39, 'All 39 days across the gap must be sealed');
    assert.ok(s.carry_in_tests >= 8, 'Old debt must not be erased while crossing the gap');

    // Repeat run idempotence check (must not duplicate penalty or deduct credits)
    const creditsBefore = (await q(`select credits from public.profiles where id='${owner}'`)).credits;
    await db.exec('select public.apply_daily_inactivity_penalty()');
    const creditsAfter = (await q(`select credits from public.profiles where id='${owner}'`)).credits;
    assert.equal(creditsAfter, creditsBefore, 'Repeat run must NOT deduct additional credits');
    assert.equal((await status()).carryover_ready, true, 'Repeat run must keep carryover_ready=true');

    return { runs: runs + 1, sealed: prior, finalCarryoverReady: s.carryover_ready };
  });

  await scenario('C03: 3-day recovery, exact billing, and repeat-run credit/debt idempotence', async () => {
    await policy(3, true);
    await checkpoint(4);
    await events(3, 4);
    await events(2, 5);
    await events(1, 6);

    const initialCredits = (await q(`select credits from public.profiles where id='${owner}'`)).credits;

    // Run until bounded batches complete and reach yesterday
    let s1;
    for (let i = 0; i < 10; i++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      s1 = await status();
      if (s1.carryover_ready) break;
    }
    assert.equal(s1.carry_in_tests, 3);
    assert.equal(s1.carryover_ready, true);

    const billing = await q(`
      select
        coalesce(sum(penalty_credits) filter (where task_date > ${today} - 4), 0)::int as intended,
        count(*) filter (where task_date < ${today} - 4 and finalized_at is not null)::int as before_anchor
      from public.user_daily_tasks
      where user_id = '${owner}'
    `);
    const creditsAfterFirst = (await q(`select credits from public.profiles where id='${owner}'`)).credits;

    // R08: No rows created before trusted checkpoint, only post-checkpoint penalties debited
    assert.equal(billing.before_anchor, 0, 'No rows created before trusted checkpoint');
    assert.equal(billing.intended, 274, 'Days -3, -2, -1 penalties sum to 34 + 75 + 165 = 274');
    assert.equal(initialCredits - creditsAfterFirst, billing.intended, 'Balance reduction must match intended penalties exactly');
    assert.equal(creditsAfterFirst, 9726, 'Final balance must be exactly 9726');

    // Run repeat: repeat run must not change debt or deduct credits
    await db.exec('select public.apply_daily_inactivity_penalty()');
    const s2 = await status();
    const creditsAfterSecond = (await q(`select credits from public.profiles where id='${owner}'`)).credits;

    assert.equal(s2.carry_in_tests, 3);
    assert.equal(creditsAfterSecond, creditsAfterFirst);

    return { carryIn: s2.carry_in_tests, creditsAfterSecond, beforeAnchor: billing.before_anchor, intendedDebit: billing.intended };
  });

  await scenario('C03 / R08: Global cross-user recovery preserves other users trusted checkpoints and balances', async () => {
    // User A (owner) has checkpoint at today - 4
    await policy(4, true);
    await checkpoint(4, 2);

    // Another user has an older creation day at today - 20 with no checkpoint
    const userB = 'c4000000-0000-0000-0000-000000000099';
    await db.exec(`
      insert into auth.users(id, email) values ('${userB}', 'userb@test.local') on conflict do nothing;
      insert into public.profiles(id, role, created_at, credits, last_login_date)
      values ('${userB}', 'member', (${today} - 20)::timestamp at time zone 'Asia/Ho_Chi_Minh', 5000, ${today} - 20)
      on conflict (id) do update set created_at = (${today} - 20)::timestamp at time zone 'Asia/Ho_Chi_Minh', credits = 5000;
    `);

    const ownerCreditsBefore = (await q(`select credits from public.profiles where id='${owner}'`)).credits;

    // Run catch-up
    for (let i = 0; i < 5; i++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
    }

    const ownerPreAnchor = (await q(`
      select count(*)::int c from public.user_daily_tasks
      where user_id = '${owner}' and task_date < ${today} - 4 and finalized_at is not null
    `)).c;
    assert.equal(ownerPreAnchor, 0, 'Cross-user older date must not create rows for owner before anchor');

    const ownerPostDebits = (await q(`
      select coalesce(sum(penalty_credits), 0)::int debits from public.user_daily_tasks
      where user_id = '${owner}' and task_date > ${today} - 4 and finalized_at is not null
    `)).debits;
    const ownerCreditsAfter = (await q(`select credits from public.profiles where id='${owner}'`)).credits;
    assert.equal(ownerCreditsBefore - ownerCreditsAfter, ownerPostDebits, 'Owner debits must equal post-anchor penalties only');

    return { ownerPreAnchor, ownerPostDebits };
  });

  await scenario('C03: 31-day recovery across bounded batches', async () => {
    await policy(31, true);
    await checkpoint(32, 0);

    const initialSealed = (await q(`select count(*)::int c from public.user_daily_tasks where user_id='${owner}' and task_date>${today}-32 and finalized_at is not null`)).c;
    assert.equal(initialSealed, 0);

    let s;
    for (let i = 0; i < 10; i++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      s = await status();
      if (s.carryover_ready) break;
    }

    const sealed = (await q(`select count(*)::int c from public.user_daily_tasks where user_id='${owner}' and task_date>${today}-32 and finalized_at is not null`)).c;
    assert.equal(sealed, 31, 'All 31 days must be sealed');
    assert.equal(s.carryover_ready, true);

    return { sealed31: sealed };
  });

  await scenario('Unresolved missing-source history preserves pending status and diagnostics', async () => {
    await policy(0, true);
    await checkpoint(1, 2);

    // Insert orphan journal record without source report in tester_reports
    const orphanId = 'c9000000-0000-0000-0000-000000000001';
    await db.exec(`
      insert into public.daily_test_events_journal (
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
      ) values (
        'report', '${orphanId}', '${owner}', '${supply[0]}', '${owner}',
        ${today}, 'pending', false, null, 'unresolved_missing_source', now()
      );
    `);

    const s = await status();
    assert.equal(s.history_ready, false, 'Unresolved missing source must keep history_ready=false');
    assert.equal(s.carryover_ready, false, 'Unresolved missing source must keep carryover_ready=false');

    // Run diagnostics
    const reconAudit = await q('select * from public.audit_carryover_journal_reconciliation() limit 1');
    assert.ok(reconAudit === undefined || reconAudit.audit_recommendation, 'Audit function must execute successfully');

    return { historyReady: s.history_ready, carryoverReady: s.carryover_ready };
  });

  // ============================================================================
  // MIGRATION 082 REGRESSION TESTS ([BUG-1], [BUG-2], [BUG-3], [BUG-5])
  // ============================================================================

  await scenario('BUG-1: Mission report does NOT mark campaign as tested in get_home_feed, get_campaigns_stats, get_next_untested_campaign, get_my_dashboard', async () => {
    await policy(0, true);
    await checkpoint(1, 2);

    // Pause all supply campaigns except supply[0] so supply[0] is the only open supply campaign
    await db.exec(`
      update public.campaigns set status = 'paused', paused_reason = 'owner_manual'
      where owner_id <> '${owner}' and id <> '${supply[0]}';
      insert into public.campaign_members(user_id, campaign_id)
      values ('${owner}', '${supply[0]}')
      on conflict do nothing;
    `);

    const campaign = supply[0];
    const supplier = (await q(`select owner_id from public.campaigns where id='${campaign}'`)).owner_id;
    const mission = 'c5000000-0000-0000-0000-000000000091';
    const request = 'c6000000-0000-0000-0000-000000000091';
    const image = 'c7000000-0000-0000-0000-000000000091';

    await db.exec(`
      update public.profiles set credits=10000 where id='${supplier}';
      insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
        values('${mission}','${supplier}','${campaign}','Mission isolation feed check',10,3,50,'open',now()+interval '3 days');
      insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${owner}','joined');
    `);

    const attachment = [{
      id: image,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2F${image}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2Fthumb_${image}.jpg?alt=media&token=fixture`,
      image_hash: '9111222233334444',
      sha256: 'bug1_mission_feed_fixture'
    }];

    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    const submitRes = (await q(`
      select public.submit_tester_report_v3(
        p_submission_context=>'mission',
        p_campaign_id=>'${campaign}',
        p_content=>'Mission report on supply[0]',
        p_rating=>5,
        p_client_submit_id=>'${request}',
        p_attachments=>'${JSON.stringify(attachment)}'::jsonb,
        p_mission_id=>'${mission}'
      ) r
    `)).r;
    assert.equal(submitRes.success, true);

    const feed = (await q(`select public.get_home_feed() f`)).f;
    const statsJson = (await q(`select public.get_campaigns_stats(ARRAY['${campaign}']::uuid[]) s`)).s;
    const nextUntested = (await q(`select public.get_next_untested_campaign(null::uuid) n`)).n;
    const dashboard = (await q(`select public.get_my_dashboard(false) d`)).d;
    await db.exec('reset role;reset request.jwt.claim.sub');

    const feedStat = feed.stats?.[campaign];
    assert.ok(feedStat, 'supply[0] stats must be present in get_home_feed');
    assert.equal(feedStat.has_tested_today, false, 'get_home_feed must NOT mark campaign tested after mission report');
    assert.equal(statsJson[campaign]?.has_tested_today, false, 'get_campaigns_stats must NOT mark campaign tested after mission report');
    assert.ok(nextUntested?.campaign && nextUntested.campaign.id === campaign, 'get_next_untested_campaign must still return supply[0]');
    assert.equal(dashboard.today_checkin_count, 0, 'get_my_dashboard today_checkin_count must remain 0 after mission report');

    return {
      feedHasTested: feedStat.has_tested_today,
      statsHasTested: statsJson[campaign]?.has_tested_today,
      nextUntestedId: nextUntested.campaign.id,
      dashboardTodayCheckins: dashboard.today_checkin_count
    };
  });

  await scenario('BUG-2: Rejecting app_test report restores daily_missed_streak and resubmitting (TH2) restores checkin & met', async () => {
    await policy(0, true);
    // Pause Carry Two so owner has 1 active app -> base_required = 3, and set yesterday carry_out = 0, missed_streak = 2
    await db.exec(`
      update public.campaigns set status = 'paused', paused_reason = 'owner_manual'
      where owner_id = '${owner}' and package_name = 'audit.carry.two';
      update public.campaign_status_journal set valid_from = now() - interval '25 days';
      insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
      values ('${owner}', ${today} - 1, 'owner', now(), 0, 2, false);
      update public.profiles set daily_missed_streak = 2 where id = '${owner}';
    `);

    // Complete 2 checkins on supply[0] and supply[1], then submit 3rd via submit_tester_report_v3 (app_test) on supply[2]
    await events(0, 2);
    const campaign = supply[2];
    const supplier = (await q(`select owner_id from public.campaigns where id='${campaign}'`)).owner_id;
    const req1 = 'c6000000-0000-0000-0000-000000000092';
    const img1 = 'c7000000-0000-0000-0000-000000000092';
    const attachment1 = [{
      id: img1,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2F${img1}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2Fthumb_${img1}.jpg?alt=media&token=fixture`,
      image_hash: '9211222233334444',
      sha256: 'bug2_app_test_fixture_1'
    }];

    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    const sub1 = (await q(`
      select public.submit_tester_report_v3(
        p_submission_context=>'app_test',
        p_campaign_id=>'${campaign}',
        p_content=>'Third qualifying app_test report today',
        p_rating=>5,
        p_client_submit_id=>'${req1}',
        p_attachments=>'${JSON.stringify(attachment1)}'::jsonb,
        p_mission_id=>null
      ) r
    `)).r;
    await db.exec('reset role;reset request.jwt.claim.sub');
    assert.equal(sub1.success, true);

    const statusAfterSub1 = await status();
    const streakAfterSub1 = (await q(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s;
    assert.equal(statusAfterSub1.met, true, 'Owner must meet daily goal (3/3) after 3rd report');
    assert.equal(streakAfterSub1, 0, 'daily_missed_streak must reset to 0 when met becomes true');

    // Supplier rejects the report -> met drops to false AND daily_missed_streak must restore to 2!
    await db.exec(`set request.jwt.claim.sub='${supplier}';set role authenticated`);
    const rejRes = (await q(`
      select public.reject_tester_report(
        p_report_id=>'${sub1.report_id}'::uuid,
        p_reason=>'Please upload a clearer screenshot',
        p_reason_code=>'blurry_screenshot'
      ) r
    `)).r;
    await db.exec('reset role;reset request.jwt.claim.sub');
    assert.equal(rejRes.success, true);

    const statusAfterReject = await status();
    const streakAfterReject = (await q(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s;
    assert.equal(statusAfterReject.completed_tests, 2, 'completed_tests must drop to 2 after rejection');
    assert.equal(statusAfterReject.met, false, 'met must drop to false after rejection');
    assert.equal(streakAfterReject, 2, 'daily_missed_streak must be restored to yesterday finalized streak (2) after rejection');

    // Tester resubmits the rejected report (TH2) -> provisional checkin & met=true & streak=0 must be restored!
    const req2 = 'c6000000-0000-0000-0000-000000000093';
    const img2 = 'c7000000-0000-0000-0000-000000000093';
    const attachment2 = [{
      id: img2,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2F${img2}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2Fthumb_${img2}.jpg?alt=media&token=fixture`,
      image_hash: '9311222233334444',
      sha256: 'bug2_app_test_fixture_2'
    }];

    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    const sub2 = (await q(`
      select public.submit_tester_report_v3(
        p_submission_context=>'app_test',
        p_campaign_id=>'${campaign}',
        p_content=>'Resubmitted clear app_test report today',
        p_rating=>5,
        p_client_submit_id=>'${req2}',
        p_attachments=>'${JSON.stringify(attachment2)}'::jsonb,
        p_mission_id=>null
      ) r
    `)).r;
    await db.exec('reset role;reset request.jwt.claim.sub');
    assert.equal(sub2.success, true);

    const statusAfterResub = await status();
    const streakAfterResub = (await q(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s;
    const checkinCount = (await q(`select count(*)::int c from public.checkins where user_id='${owner}' and campaign_id='${campaign}'`)).c;
    assert.equal(statusAfterResub.completed_tests, 3, 'completed_tests must be 3 after TH2 resubmit');
    assert.equal(statusAfterResub.met, true, 'met must be true after TH2 resubmit');
    assert.equal(streakAfterResub, 0, 'daily_missed_streak must reset to 0 after TH2 resubmit');
    assert.equal(checkinCount, 1, 'Provisional checkin row must be re-created on TH2 resubmit');

    return {
      streakAfterSub1,
      streakAfterReject,
      streakAfterResub,
      checkinCount
    };
  });

  await scenario('BUG-3: 00:00-00:05 AM race condition re-evaluates today completion after sealing yesterday', async () => {
    await policy(2, true);
    // Day -2 sealed with 0 debt, streak 0
    await checkpoint(2, 0);
    // Day -1: owner had 2 active apps (base=6), completed 4 -> missed day -1 with carry_out = 2, streak = 1
    await events(1, 4);
    // Today between 00:00 and 00:05 AM (before cron seals day -1), owner already completed 8 tests today (6 base + 2 carry_in)
    await events(0, 8);
    await db.exec(`update public.profiles set last_login_date = ${today} where id = '${owner}'`);

    // Now 00:05 AM cron seals day -1
    await seal(1);

    // Because owner already completed 8/8 tests today, BUG-3 fix in _apply_daily_inactivity_penalty must refresh today and keep daily_missed_streak = 0!
    const profileStreak = (await q(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s;
    const todayStatus = await status(0);
    assert.equal(todayStatus.carry_in_tests, 2);
    assert.equal(todayStatus.required_tests, 8);
    assert.equal(todayStatus.completed_tests, 8);
    assert.equal(todayStatus.met, true);
    assert.equal(profileStreak, 0, 'daily_missed_streak must be 0 when user already met today goal (including carry_in) before 00:05 cron');

    return {
      carryIn: todayStatus.carry_in_tests,
      required: todayStatus.required_tests,
      completed: todayStatus.completed_tests,
      profileStreak
    };
  });

  await scenario('BUG-5: Preserves known carry_in_tests in required_tests when predecessor is sealed but history_ready is false', async () => {
    await policy(0, true);
    await checkpoint(1, 3); // Yesterday is sealed with carry_out_tests = 3

    // Insert an unresolved journal event today so v_history_ready = false while v_carryover_ready = true
    const orphanId = 'c9000000-0000-0000-0000-000000000095';
    await db.exec(`
      insert into public.daily_test_events_journal (
        source_type, source_id, user_id, campaign_id, campaign_owner_id,
        activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
      ) values (
        'report', '${orphanId}', '${owner}', '${supply[0]}', '${owner}',
        ${today}, 'pending', false, null, 'unresolved_missing_source', now()
      );
    `);

    const s = await status();
    assert.equal(s.history_ready, false);
    assert.equal(s.carryover_ready, false);
    assert.equal(s.base_required_tests, 6);
    assert.equal(s.carry_in_tests, 3);
    assert.equal(s.required_tests, 9, 'required_tests must include known carry_in_tests (6 + 3 = 9)');
    assert.equal(s.carryover_remaining_tests, 9, 'carryover_remaining_tests must preserve known debt (9)');

    return {
      baseRequired: s.base_required_tests,
      carryIn: s.carry_in_tests,
      required: s.required_tests,
      remaining: s.carryover_remaining_tests
    };
  });

  await scenario('NEW-BUG-1: Direct INSERT and DELETE on public.checkins automatically updates daily_missed_streak and unfinalized user_daily_tasks row', async () => {
    await policy(0, true);
    // Pause Carry Two so owner has 1 active app -> base_required = 3; yesterday finalized with missed_streak = 2, carry_out = 0
    await db.exec(`
      update public.campaigns set status = 'paused', paused_reason = 'owner_manual'
      where owner_id = '${owner}' and package_name = 'audit.carry.two';
      update public.campaign_status_journal set valid_from = now() - interval '25 days';
      insert into public.user_daily_tasks(user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak, met)
      values ('${owner}', ${today} - 1, 'owner', now(), 0, 2, false);
      update public.profiles set daily_missed_streak = 2 where id = '${owner}';
      insert into public.user_daily_tasks(user_id, task_date, mode, active_app_count, required_tests, effective_required_tests, available_apps, completed_tests, logged_in, is_exempt, met, missed_streak)
      values ('${owner}', ${today}, 'owner', 1, 3, 3, 20, 0, true, false, false, 2);
    `);

    // Insert 3 real checkins directly into public.checkins (without manually calling refresh_daily_completion)
    for (let i = 0; i < 3; i++) {
      await db.exec(`
        insert into public.checkins(campaign_id, user_id, checkin_date, note)
        values ('${supply[i]}', '${owner}', ${today}, 'Checkin thực tế ${i + 1}');
      `);
    }

    const streakAfterCheckins = (await q(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s;
    const unfinalizedAfterCheckins = await q(`
      select completed_tests, met from public.user_daily_tasks
      where user_id='${owner}' and task_date=${today} and finalized_at is null
    `);
    assert.equal(streakAfterCheckins, 0, 'Direct INSERT into checkins must reset daily_missed_streak to 0 when goal is met');
    assert.equal(unfinalizedAfterCheckins.completed_tests, 3, 'Unfinalized user_daily_tasks row must reflect 3 completed_tests');
    assert.equal(unfinalizedAfterCheckins.met, true, 'Unfinalized user_daily_tasks row must have met=true');

    // Delete 1 checkin -> completed_tests drops to 2, met drops to false, and daily_missed_streak restores to 2!
    await db.exec(`
      delete from public.checkins
      where campaign_id = '${supply[2]}' and user_id = '${owner}' and checkin_date = ${today};
    `);

    const streakAfterDelete = (await q(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s;
    const unfinalizedAfterDelete = await q(`
      select completed_tests, met from public.user_daily_tasks
      where user_id='${owner}' and task_date=${today} and finalized_at is null
    `);
    assert.equal(streakAfterDelete, 2, 'Deleting checkin must restore daily_missed_streak to yesterday finalized streak (2)');
    assert.equal(unfinalizedAfterDelete.completed_tests, 2, 'Unfinalized user_daily_tasks row must drop to 2 completed_tests');
    assert.equal(unfinalizedAfterDelete.met, false, 'Unfinalized user_daily_tasks row must drop to met=false');

    return {
      streakAfterCheckins,
      streakAfterDelete,
      unfinalizedAfterDelete
    };
  });

  await scenario('NEW-BUG-2: Member mode resets daily_missed_streak = 0 upon login even when carryover_ready is false', async () => {
    await policy(0, true);
    // Pause both campaigns so user is in member mode today, and leave yesterday UNFINALIZED (carryover_ready = false)
    await db.exec(`
      update public.campaigns set status = 'paused', paused_reason = 'owner_manual'
      where owner_id = '${owner}';
      update public.campaign_status_journal set valid_from = now() - interval '25 days';
      update public.profiles set daily_missed_streak = 2, last_login_date = ${today} - 3 where id = '${owner}';
    `);

    // Member logs in today (e.g. at 00:01 AM before yesterday is finalized)
    await db.exec(`select public.record_daily_login_internal('${owner}'::uuid, ${today})`);

    const s = await status();
    const profileStreak = (await q(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s;
    assert.equal(s.mode, 'member');
    assert.equal(s.carryover_ready, false, 'Predecessor day is unfinalized so carryover_ready is false');
    assert.equal(s.met, true, 'Member daily requirement is met by logging in');
    assert.equal(profileStreak, 0, 'Member daily_missed_streak must reset to 0 when met=true even if carryover_ready=false');

    return {
      mode: s.mode,
      carryoverReady: s.carryover_ready,
      met: s.met,
      profileStreak
    };
  });

  console.log('=== ALL C01, C03, AND 082 ACCEPTANCE TESTS PASSED ===');
} finally {
  await db.close();
}
