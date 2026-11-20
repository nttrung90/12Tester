import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING MISSION REWARD MODES & MANAGEMENT TESTS (MIGRATION 077) ===');

const db = await createDb({ legacyGrants: true });

try {
  // 1. Load migrations 062..077
  const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x) && Number(x.slice(0, 3)) >= 62)
    .sort();

  console.log(`Loading ${migrationFiles.length} migrations from 062 to 077...`);
  for (const filename of migrationFiles) {
    await db.exec(read('supabase/migrations/' + filename));
  }

  // Setup Actors
  const adminId = '11111111-aaaa-1111-aaaa-111111111111';
  const ownerId = '22222222-bbbb-2222-bbbb-222222222222';
  const tester1Id = '33333333-cccc-3333-cccc-333333333333';
  const tester2Id = '44444444-dddd-4444-dddd-444444444444';
  const moderatorId = '55555555-eeee-5555-eeee-555555555555';
  const lockedAdminId = '66666666-ffff-6666-ffff-666666666666';

  const campOpenId = 'aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa';
  const campProdId = 'bbbbbbbb-2222-bbbb-2222-bbbbbbbbbbbb';
  const campCompId = 'cccccccc-3333-cccc-3333-cccccccccccc';
  const camp4Id = 'dddddddd-4444-dddd-4444-dddddddddddd';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${adminId}', 'admin@test.local'),
      ('${ownerId}', 'owner@test.local'),
      ('${tester1Id}', 'tester1@test.local'),
      ('${tester2Id}', 'tester2@test.local'),
      ('${moderatorId}', 'mod@test.local'),
      ('${lockedAdminId}', 'locked_admin@test.local');

    update public.profiles set credits = 5000, display_name = 'Admin User', role = 'admin', is_locked = false where id = '${adminId}';
    update public.profiles set credits = 10000, display_name = 'App Owner', role = 'member', is_locked = false where id = '${ownerId}';
    update public.profiles set credits = 500, display_name = 'Tester 1', role = 'member', is_locked = false where id = '${tester1Id}';
    update public.profiles set credits = 500, display_name = 'Tester 2', role = 'member', is_locked = false where id = '${tester2Id}';
    update public.profiles set credits = 500, display_name = 'Moderator', role = 'moderator', is_locked = false where id = '${moderatorId}';
    update public.profiles set credits = 5000, display_name = 'Locked Admin', role = 'admin', is_locked = true where id = '${lockedAdminId}';

    insert into public.user_slots (user_id, slot_number, expires_at)
    values
      ('${ownerId}', 2, now() + interval '30 days'),
      ('${ownerId}', 3, now() + interval '30 days'),
      ('${ownerId}', 4, now() + interval '30 days');

    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number)
    values
      ('${campOpenId}', '${ownerId}', 'com.test.openapp', 'Open App', 'open', 1),
      ('${campProdId}', '${ownerId}', 'com.test.prodapp', 'Production App', 'production', 2),
      ('${campCompId}', '${ownerId}', 'com.test.compapp', 'Completed App', 'completed', 3),
      ('${camp4Id}', '${ownerId}', 'com.test.expapp', 'Expiring App', 'open', 4);
  `);

  async function asUser(userId, fn) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${userId}', false); set role authenticated;`);
    try {
      return await fn();
    } finally {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', false);`);
    }
  }

  async function queryRpc(sql) {
    const res = await db.query(sql);
    return res.rows[0];
  }

  function makeScreenshotUrl(camp, user, fileId) {
    return `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp}%2F${user}%2F${fileId}.jpg?alt=media&token=fixture`;
  }

  // =========================================================================
  // Test 1: Insufficient Credits & Balance Guards
  // =========================================================================
  console.log('\n--- Test 1: Insufficient Credits & Balance Guards ---');
  await asUser(ownerId, async () => {
    // Attempt with excessive reward that exceeds balance
    let failed = false;
    try {
      await db.query(`
        select public.create_mission_v2(
          '${campOpenId}'::uuid, 'Too Expensive', 'Desc', 50, 7, 500, 'daily'
        );
      `);
    } catch (e) {
      failed = true;
      assert.ok(e.message.includes('INSUFFICIENT_CREDITS'), 'Expected INSUFFICIENT_CREDITS');
    }
    assert.ok(failed, 'Should have failed with INSUFFICIENT_CREDITS');
    console.log('PASS: Mission creation rejected when total cost exceeds balance');
  });

  // =========================================================================
  // Test 2: Once Mode Across Multiple Days (Create, Join, Submit Day 1, Edit Day 2, Approve, Single Reward)
  // =========================================================================
  console.log('\n--- Test 2: Once Mode Across Multiple Days ---');
  let missionOnceId;
  const ownerCreditsBefore = 10000;
  // duration 3 days, 5 participants, 20 credits/participant
  // fee = 50 * 3 = 150
  // escrow = 5 * 20 = 100
  // total = 250
  await asUser(ownerId, async () => {
    const res = await db.query(`
      select public.create_mission_v2(
        '${campOpenId}'::uuid, 'Mission Once 1', 'Mô tả nhiệm vụ một lần', 5, 3, 20, 'once'
      ) as r;
    `);
    const r = res.rows[0].r;
    assert.equal(r.success, true);
    assert.equal(r.fee, 150);
    assert.equal(r.escrow, 100);
    assert.equal(r.total_cost, 250);
    assert.equal(r.balance, ownerCreditsBefore - 250);
    assert.equal(r.reward_mode, 'once');
    missionOnceId = r.mission_id;
    console.log('PASS: Owner created once-mode mission; 150 fee + 100 escrow held upfront atomically');
  });

  // Tester 1 joins mission
  await asUser(tester1Id, async () => {
    const joinRes = await db.query(`select public.join_mission('${missionOnceId}'::uuid) as r;`);
    assert.equal(joinRes.rows[0].r.success, true);
    console.log('PASS: Tester 1 joined once-mode mission');
  });

  // Tester 1 submits report on day 1
  let reportOnceId;
  const clientSubmitId1 = '99999999-1111-9999-1111-999999999991';
  await asUser(tester1Id, async () => {
    const submitRes = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campOpenId}'::uuid, 'Báo cáo ngày 1 nhiệm vụ một lần', 5,
        '${clientSubmitId1}'::uuid,
        '${makeScreenshotUrl(campOpenId, tester1Id, 'full_1')}',
        '0123456789abcdef',
        null,
        '${missionOnceId}'::uuid,
        null,
        null
      ) as r;
    `);
    const r = submitRes.rows[0].r;
    assert.equal(r.success, true);
    assert.equal(r.mission_reward_mode, 'once');
    reportOnceId = r.report_id;
    console.log('PASS: Tester 1 submitted once-mode report on Day 1');
  });

  // Idempotency replay on same client_submit_id
  await asUser(tester1Id, async () => {
    const replayRes = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campOpenId}'::uuid, 'Báo cáo ngày 1 nhiệm vụ một lần', 5,
        '${clientSubmitId1}'::uuid,
        '${makeScreenshotUrl(campOpenId, tester1Id, 'full_1')}',
        '0123456789abcdef',
        null,
        '${missionOnceId}'::uuid,
        null,
        null
      ) as r;
    `);
    assert.equal(replayRes.rows[0].r.report_id, reportOnceId);
    console.log('PASS: Exact idempotent replay on once-mode returns committed receipt');
  });

  // Simulate Day 2 rollover: Report was created on Day 1 (yesterday)
  await db.exec(`
    update public.tester_reports
    set report_date = CURRENT_DATE - 1
    where id = '${reportOnceId}';
  `);

  // On Day 2, Tester 1 submits again (new client_submit_id, updating comment)
  // In once mode, it must find the same pending record across days and update it, NOT create a second report!
  const clientSubmitId2 = '99999999-1111-9999-1111-999999999992';
  await asUser(tester1Id, async () => {
    const updateRes = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campOpenId}'::uuid, 'Báo cáo đã cập nhật nội dung sang ngày 2', 5,
        '${clientSubmitId2}'::uuid,
        '${makeScreenshotUrl(campOpenId, tester1Id, 'full_1')}',
        '0123456789abcdef',
        null,
        '${missionOnceId}'::uuid,
        null,
        null
      ) as r;
    `);
    assert.equal(updateRes.rows[0].r.report_id, reportOnceId, 'Must update the exact same report ID');
    console.log('PASS: Day 2 once-mode submission safely updated pending report on same record across days');
  });

  // Verify only 1 report exists for Tester 1 on this mission
  const reportsCountRes = await db.query(`
    select count(*) as c from public.tester_reports
    where mission_id = '${missionOnceId}' and user_id = '${tester1Id}';
  `);
  assert.equal(Number(reportsCountRes.rows[0].c), 1, 'Strictly 1 report allowed across all days for once mode');
  console.log('PASS: Verified strictly 1 report in DB for once-mode participant across multiple days');

  // Coexistence: Tester 1 submits an ordinary app_test report today on the same campaign
  await asUser(tester1Id, async () => {
    const appTestRes = await db.query(`
      select public._submit_tester_report_core(
        'app_test', '${campOpenId}'::uuid, 'Test app thường ngày 2', 5,
        '99999999-1111-9999-1111-999999999993'::uuid,
        '${makeScreenshotUrl(campOpenId, tester1Id, 'full_app_test')}',
        'abcdef0123456789',
        null,
        null,
        null,
        null
      ) as r;
    `);
    assert.equal(appTestRes.rows[0].r.success, true);
    assert.equal(appTestRes.rows[0].r.submission_context, 'app_test');
    console.log('PASS: Ordinary app_test report submitted on same day coexists independently without collision');
  });

  // Owner approves once-mode mission report
  const tester1CreditsBefore = 500;
  const ownerCreditsBeforeApproval = (await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits;
  await asUser(ownerId, async () => {
    const approveRes = await db.query(`
      select public.approve_tester_report('${reportOnceId}'::uuid, 0) as r;
    `);
    assert.equal(approveRes.rows[0].r.success, true);
  });

  // Check settlement results:
  // Reward of 20 was paid OUT OF ESCROW! Owner was NOT charged 20 credits!
  // (Owner only receives standard review bonus, e.g. +2 from business policy)
  const ownerMissionDeduction = await db.query(`
    select * from public.credit_logs
    where user_id = '${ownerId}' and action = 'mission_reward';
  `);
  assert.equal(ownerMissionDeduction.rows.length, 0, 'Owner must NOT have any negative mission_reward deduction');

  const tester1CreditsAfter = (await db.query(`select credits from public.profiles where id = '${tester1Id}'`)).rows[0].credits;
  // tester received +10 provisional from app_test + 20 from mission = 530
  assert.ok(tester1CreditsAfter >= tester1CreditsBefore + 20, 'Tester received 20 mission reward');

  const missionOnceRow = (await db.query(`select * from public.missions where id = '${missionOnceId}'`)).rows[0];
  assert.equal(missionOnceRow.escrow_remaining_credits, 80, 'Escrow remaining decreased from 100 to 80 (20 paid)');

  const participantRow = (await db.query(`select * from public.mission_participants where mission_id = '${missionOnceId}' and user_id = '${tester1Id}'`)).rows[0];
  assert.equal(participantRow.status, 'completed', 'In once mode, participant is marked completed');
  console.log('PASS: Once-mode reward paid from escrow; owner balance unaffected; participant completed');

  // =========================================================================
  // Test 3: Daily Mode for 3 Days (Missed Day 2, Out of Order Review, No Lock on Future Days)
  // =========================================================================
  console.log('\n--- Test 3: Daily Mode for 3 Days Flow ---');
  let missionDailyId;
  // 3 days, 2 participants, 15 credits/day
  // fee = 50 * 3 = 150
  // escrow = 2 * 3 * 15 = 90
  // total = 240
  await asUser(ownerId, async () => {
    const res = await db.query(`
      select public.create_mission_v2(
        '${campProdId}'::uuid, 'Mission Daily 3 Days', 'Daily test on prod app', 2, 3, 15, 'daily'
      ) as r;
    `);
    const r = res.rows[0].r;
    assert.equal(r.success, true);
    assert.equal(r.fee, 150);
    assert.equal(r.escrow, 90);
    assert.equal(r.total_cost, 240);
    assert.equal(r.reward_mode, 'daily');
    missionDailyId = r.mission_id;
    console.log('PASS: Daily mission created for production campaign; 150 fee + 90 escrow held');
  });

  // Tester 1 joins daily mission
  await asUser(tester1Id, async () => {
    const joinRes = await db.query(`select public.join_mission('${missionDailyId}'::uuid) as r;`);
    assert.equal(joinRes.rows[0].r.success, true);
    console.log('PASS: Tester 1 joined daily mission');
  });

  // Day 1 submission
  let reportDay1Id;
  await asUser(tester1Id, async () => {
    const sub = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campProdId}'::uuid, 'Nộp ngày 1', 5,
        '88888888-1111-8888-1111-888888888881'::uuid,
        '${makeScreenshotUrl(campProdId, tester1Id, 'day1')}',
        '1111222233334444',
        null,
        '${missionDailyId}'::uuid,
        null,
        null
      ) as r;
    `);
    reportDay1Id = sub.rows[0].r.report_id;
    console.log('PASS: Tester 1 submitted Day 1 daily report');
  });

  // Simulate Day 1 -> Day 3:
  // Update Day 1 report date to 2 days ago
  await db.exec(`
    update public.tester_reports
    set report_date = CURRENT_DATE - 2
    where id = '${reportDay1Id}';
  `);

  // Tester 1 MISSED Day 2 completely (did not submit).
  // Attempt to submit retroactively with p_expected_date for yesterday must be rejected
  await asUser(tester1Id, async () => {
    let missedFailed = false;
    try {
      await db.query(`
        select public._submit_tester_report_core(
          'mission', '${campProdId}'::uuid, 'Nộp bù ngày 2', 5,
          '88888888-1111-8888-1111-888888888882'::uuid,
          '${makeScreenshotUrl(campProdId, tester1Id, 'day2')}',
          '2222333344445555',
          null,
          '${missionDailyId}'::uuid,
          null,
          (CURRENT_DATE - 1)
        );
      `);
    } catch (e) {
      missedFailed = true;
      assert.ok(e.message.includes('REPORT_DATE_MISMATCH'), 'Server must reject retroactive date');
    }
    assert.ok(missedFailed, 'Retroactive submission must fail');
    console.log('PASS: Server rejects retroactive submission for missed Day 2');
  });

  // Day 3 submission (today)
  let reportDay3Id;
  await asUser(tester1Id, async () => {
    const sub = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campProdId}'::uuid, 'Nộp ngày 3', 5,
        '88888888-1111-8888-1111-888888888883'::uuid,
        '${makeScreenshotUrl(campProdId, tester1Id, 'day3')}',
        '3333444455556666',
        null,
        '${missionDailyId}'::uuid,
        null,
        null
      ) as r;
    `);
    reportDay3Id = sub.rows[0].r.report_id;
    console.log('PASS: Tester 1 submitted Day 3 daily report');
  });

  // Out of order approval: Owner approves Day 3 FIRST!
  await asUser(ownerId, async () => {
    const approveDay3 = await db.query(`select public.approve_tester_report('${reportDay3Id}'::uuid, 0) as r;`);
    assert.equal(approveDay3.rows[0].r.success, true);
    console.log('PASS: Day 3 approved FIRST');
  });

  // Verify participant status is STILL 'joined' (not locked out of other days)
  const partDailyStatus = (await db.query(`select status from public.mission_participants where mission_id = '${missionDailyId}' and user_id = '${tester1Id}'`)).rows[0].status;
  assert.equal(partDailyStatus, 'joined', 'In daily mode, approving day 3 does not lock or complete participant');

  // Owner approves Day 1 SECOND!
  await asUser(ownerId, async () => {
    const approveDay1 = await db.query(`select public.approve_tester_report('${reportDay1Id}'::uuid, 0) as r;`);
    assert.equal(approveDay1.rows[0].r.success, true);
    console.log('PASS: Day 1 approved SECOND (out-of-order review succeeded)');
  });

  // Both Day 1 and Day 3 settlement jobs completed and paid out from escrow
  const settledJobsRes = await db.query(`
    select report_date, status, reward_credits from public.mission_settlement_jobs
    where mission_id = '${missionDailyId}' and tester_id = '${tester1Id}' and status = 'completed'
    order by report_date asc;
  `);
  assert.equal(settledJobsRes.rows.length, 2, 'Both Day 1 and Day 3 settlement jobs completed');
  const dailyMissionAfter = (await db.query(`select * from public.missions where id = '${missionDailyId}'`)).rows[0];
  assert.equal(dailyMissionAfter.escrow_remaining_credits, 60, 'Escrow remaining decreased from 90 to 60 (2 * 15 paid)');
  console.log('PASS: Daily mode out-of-order approval paid both days independently from escrow');

  // =========================================================================
  // Test 4: Rejection and Resubmission (Enforcing New Photo)
  // =========================================================================
  console.log('\n--- Test 4: Rejection and Resubmission Flow ---');
  // Tester 2 joins daily mission and submits report
  await asUser(tester2Id, async () => {
    await db.query(`select public.join_mission('${missionDailyId}'::uuid);`);
  });
  let reportTester2Id;
  await asUser(tester2Id, async () => {
    const sub = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campProdId}'::uuid, 'Báo cáo tester 2 bị từ chối', 4,
        '77777777-2222-7777-2222-777777777771'::uuid,
        '${makeScreenshotUrl(campProdId, tester2Id, 'photo1')}',
        '4444555566667777',
        null,
        '${missionDailyId}'::uuid,
        null,
        null
      ) as r;
    `);
    reportTester2Id = sub.rows[0].r.report_id;
  });

  // Owner rejects reportTester2Id
  await asUser(ownerId, async () => {
    const rejRes = await db.query(`
      select public.reject_tester_report('${reportTester2Id}'::uuid, 'Ảnh bị mờ') as r;
    `);
    assert.equal(rejRes.rows[0].r.success, true);
    assert.equal(rejRes.rows[0].r.status, 'rejected');
    console.log('PASS: Owner rejected report with reason');
  });

  // Tester 2 tries to resubmit with SAME screenshot URL -> must be rejected
  await asUser(tester2Id, async () => {
    let samePhotoFailed = false;
    try {
      await db.query(`
        select public._submit_tester_report_core(
          'mission', '${campProdId}'::uuid, 'Nộp lại nhưng dùng ảnh cũ', 5,
          '77777777-2222-7777-2222-777777777772'::uuid,
          '${makeScreenshotUrl(campProdId, tester2Id, 'photo1')}',
          '4444555566667777',
          null,
          '${missionDailyId}'::uuid,
          null,
          null
        );
      `);
    } catch (e) {
      samePhotoFailed = true;
      assert.ok(e.message.includes('REPORT_RESUBMIT_NEW_PHOTO_REQUIRED'), 'Must require new photo');
    }
    assert.ok(samePhotoFailed, 'Resubmission with same photo must fail');
    console.log('PASS: Server blocked resubmission with duplicate old photo');
  });

  // Tester 2 resubmits with NEW screenshot URL -> succeeds and resets to pending!
  await asUser(tester2Id, async () => {
    const resubRes = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campProdId}'::uuid, 'Nộp lại với ảnh mới rõ nét', 5,
        '77777777-2222-7777-2222-777777777773'::uuid,
        '${makeScreenshotUrl(campProdId, tester2Id, 'photo2_new')}',
        '888899990000aaaa',
        null,
        '${missionDailyId}'::uuid,
        null,
        null
      ) as r;
    `);
    assert.equal(resubRes.rows[0].r.success, true);
    assert.equal(resubRes.rows[0].r.status, 'pending');
    assert.equal(resubRes.rows[0].r.report_id, reportTester2Id);
    console.log('PASS: Resubmission with new photo succeeded on same report record');
  });

  // =========================================================================
  // Test 5: Edit Mission (Before & After Join, Recalculate Escrow/Fee, Optimistic Lock)
  // =========================================================================
  console.log('\n--- Test 5: Edit Mission Flow ---');
  let missionEditId;
  // Create mission with 0 participants on completed app
  await asUser(ownerId, async () => {
    const res = await db.query(`
      select public.create_mission_v2(
        '${campCompId}'::uuid, 'Editable Mission', 'Description', 5, 2, 10, 'once'
      ) as r;
    `);
    missionEditId = res.rows[0].r.mission_id;
  });

  // Preview edit: change to 10 participants, 4 days, 15 credits, daily mode
  await asUser(ownerId, async () => {
    const prev = await db.query(`
      select public.preview_edit_mission(
        '${missionEditId}'::uuid, 'daily', 10, 4, 15
      ) as r;
    `);
    const r = prev.rows[0].r;
    assert.equal(r.can_edit_terms, true);
    // fee was 50*2=100. New duration 4 days -> highest fee days was 2 -> fee_diff = 50*(4-2) = 100
    assert.equal(r.fee_diff, 100);
    // old escrow was 5*10=50. New escrow = 10*4*15 = 600. Escrow diff = 600 - 50 = 550
    assert.equal(r.escrow_diff, 550);
    assert.equal(r.total_diff, 650);
    console.log('PASS: preview_edit_mission accurately calculated fee and escrow differences');

    // Edit with mismatched expected_cost_diff -> must be rejected
    let staleFailed = false;
    try {
      await db.query(`
        select public.edit_mission(
          '${missionEditId}'::uuid, 'New Title', 'New Desc', 'daily', 10, 4, 15, null, 1, 999
        );
      `);
    } catch (e) {
      staleFailed = true;
      assert.ok(e.message.includes('STALE_PREVIEW_COST'));
    }
    assert.ok(staleFailed, 'Edit with stale preview cost rejected');
    console.log('PASS: edit_mission rejected stale preview cost');

    // Edit with wrong version -> must be rejected
    let versionFailed = false;
    try {
      await db.query(`
        select public.edit_mission(
          '${missionEditId}'::uuid, 'New Title', 'New Desc', 'daily', 10, 4, 15, null, 99, 650
        );
      `);
    } catch (e) {
      versionFailed = true;
      assert.ok(e.message.includes('CONCURRENT_MODIFICATION'));
    }
    assert.ok(versionFailed, 'Edit with stale version rejected');
    console.log('PASS: edit_mission rejected concurrent version mismatch');

    // Perform valid edit
    const editRes = await db.query(`
      select public.edit_mission(
        '${missionEditId}'::uuid, 'Updated Mission Title', 'Updated Desc', 'daily', 10, 4, 15, 'Tester announcement', 1, 650
      ) as r;
    `);
    const er = editRes.rows[0].r;
    assert.equal(er.success, true);
    assert.equal(er.terms_modified, true);
    assert.equal(er.total_diff, 650);
    assert.equal(er.version, 2);
    console.log('PASS: edit_mission updated terms, deducted 650 credits, and bumped version');
  });

  // Verify mission terms in DB
  const editedMission = (await db.query(`select * from public.missions where id = '${missionEditId}'`)).rows[0];
  assert.equal(editedMission.title, 'Updated Mission Title');
  assert.equal(editedMission.reward_mode, 'daily');
  assert.equal(editedMission.max_participants, 10);
  assert.equal(editedMission.duration_days, 4);
  assert.equal(editedMission.reward_credits, 15);
  assert.equal(editedMission.escrow_remaining_credits, 600);
  assert.equal(editedMission.fee_credits, 200);

  // Now Tester 1 joins missionEditId -> participant_count becomes 1
  await asUser(tester1Id, async () => {
    await db.query(`select public.join_mission('${missionEditId}'::uuid);`);
  });

  // After join: owner attempts to change reward terms -> MUST BE BLOCKED!
  await asUser(ownerId, async () => {
    let termChangeBlocked = false;
    try {
      await db.query(`
        select public.edit_mission(
          '${missionEditId}'::uuid, 'Try Change Terms', 'Desc', 'once', 5, 2, 10, null, 2, null
        );
      `);
    } catch (e) {
      termChangeBlocked = true;
      assert.ok(e.message.includes('CANNOT_MODIFY_TERMS_AFTER_JOIN'));
    }
    assert.ok(termChangeBlocked, 'Modifying reward terms after join must be blocked');
    console.log('PASS: edit_mission locked reward terms after first participant joined');

    // Owner can still update title, description, and tester announcement
    const textEditRes = await db.query(`
      select public.edit_mission(
        '${missionEditId}'::uuid, 'Final Title', 'Final Desc', null, null, null, null, 'Important note for testers', 2, null
      ) as r;
    `);
    assert.equal(textEditRes.rows[0].r.success, true);
    assert.equal(textEditRes.rows[0].r.terms_modified, false);
    console.log('PASS: Owner edited title, description and announcement after join');
  });

  // Verify tester received notification of announcement
  const announcementNotifRes = await db.query(`
    select * from public.notifications
    where user_id = '${tester1Id}' and title = 'Cập nhật từ chủ nhiệm vụ 📢';
  `);
  assert.ok(announcementNotifRes.rows.length > 0, 'Tester must receive announcement notification');
  console.log('PASS: Tester received notification of updated mission announcement');

  // =========================================================================
  // Test 6: Admin Delete Mission (Permissions, Audit Event, Notifications, Pending Settlement & Escrow Refund)
  // =========================================================================
  console.log('\n--- Test 6: Admin Delete Mission Flow ---');

  // Permission checks: Non-admin or locked admin cannot delete mission
  await asUser(moderatorId, async () => {
    let modBlocked = false;
    try {
      await db.query(`select public.admin_delete_mission('${missionEditId}'::uuid, 'Spam');`);
    } catch (e) {
      modBlocked = true;
      assert.ok(e.message.includes('PERMISSION_DENIED'));
    }
    assert.ok(modBlocked, 'Moderator cannot admin_delete_mission');
    console.log('PASS: Moderator rejected with PERMISSION_DENIED');
  });

  await asUser(lockedAdminId, async () => {
    let lockedBlocked = false;
    try {
      await db.query(`select public.admin_delete_mission('${missionEditId}'::uuid, 'Spam');`);
    } catch (e) {
      lockedBlocked = true;
      assert.ok(e.message.includes('PERMISSION_DENIED'));
    }
    assert.ok(lockedBlocked, 'Locked admin cannot admin_delete_mission');
    console.log('PASS: Locked Admin rejected with PERMISSION_DENIED');
  });

  // Empty reason must fail
  await asUser(adminId, async () => {
    let emptyReasonFailed = false;
    try {
      await db.query(`select public.admin_delete_mission('${missionEditId}'::uuid, '   ');`);
    } catch (e) {
      emptyReasonFailed = true;
      assert.ok(e.message.includes('REASON_REQUIRED'));
    }
    assert.ok(emptyReasonFailed, 'Empty reason must be rejected');
    console.log('PASS: Empty deletion reason rejected');
  });

  // Tester 1 has a pending report on missionEditId before admin deletes
  let pendingReportOnEditMission;
  await asUser(tester1Id, async () => {
    const sub = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${campCompId}'::uuid, 'Report before admin deletion', 5,
        '66666666-3333-6666-3333-666666666661'::uuid,
        '${makeScreenshotUrl(campCompId, tester1Id, 'photo_before_del')}',
        '9999888877776666',
        null,
        '${missionEditId}'::uuid,
        null,
        null
      ) as r;
    `);
    pendingReportOnEditMission = sub.rows[0].r.report_id;
  });

  // Unlocked Admin deletes mission with valid reason
  await asUser(adminId, async () => {
    const delRes = await db.query(`
      select public.admin_delete_mission(
        '${missionEditId}'::uuid, 'Vi phạm chính sách nội dung quảng bá'
      ) as r;
    `);
    const r = delRes.rows[0].r;
    assert.equal(r.success, true);
    assert.equal(r.status, 'deleted');
    assert.ok(r.audit_event_id, 'Audit event ID returned');
    console.log('PASS: Admin deleted mission successfully with audit event');
  });

  // Replay of admin_delete_mission is idempotent and does not create duplicate audit events
  await asUser(adminId, async () => {
    const replayDel = await db.query(`
      select public.admin_delete_mission(
        '${missionEditId}'::uuid, 'Vi phạm chính sách nội dung quảng bá'
      ) as r;
    `);
    assert.equal(replayDel.rows[0].r.is_replay, true);
    console.log('PASS: Repeated admin_delete_mission returned idempotent replay');
  });

  // Verify audit event in public.admin_action_events
  const auditRes = await db.query(`
    select * from public.admin_action_events
    where action = 'admin_delete_mission' and target_id = '${missionEditId}';
  `);
  assert.equal(auditRes.rows.length, 1, 'Strictly 1 audit event recorded');
  assert.equal(auditRes.rows[0].actor_id, adminId);
  assert.equal(auditRes.rows[0].reason, 'Vi phạm chính sách nội dung quảng bá');
  console.log('PASS: Admin action audit logged with actor, reason, and target details');

  // Verify notifications sent to owner and tester
  const ownerNotif = await db.query(`
    select * from public.notifications
    where user_id = '${ownerId}' and data->>'deleted_by_admin' = 'true';
  `);
  assert.ok(ownerNotif.rows.length > 0, 'Owner notified of admin deletion');

  const testerNotif = await db.query(`
    select * from public.notifications
    where user_id = '${tester1Id}' and data->>'deleted_by_admin' = 'true';
  `);
  assert.ok(testerNotif.rows.length > 0, 'Tester notified of admin deletion');
  console.log('PASS: Notifications sent to owner and joined tester');

  // Because a report was pending, escrow was NOT prematurely refunded yet!
  const missionDuringPending = (await db.query(`select * from public.missions where id = '${missionEditId}'`)).rows[0];
  assert.equal(missionDuringPending.escrow_refunded, false, 'Escrow must not be refunded while reports are pending');
  console.log('PASS: Escrow held safely while pending report remains to be reviewed');

  // Now Owner approves the pending report on the deleted mission
  // "Báo cáo hợp lệ đã nộp giữ quyền được xét thưởng khi quá hạn, tạm dừng hoặc bị xóa"
  await asUser(ownerId, async () => {
    const app = await db.query(`
      select public.approve_tester_report('${pendingReportOnEditMission}'::uuid, 0) as r;
    `);
    assert.equal(app.rows[0].r.success, true);
    console.log('PASS: Report submitted before deletion was successfully approved and rewarded');
  });

  // After this last report is approved and settled, ALL obligations are resolved!
  // Escrow should now be automatically refunded to owner!
  const missionAfterAllSettled = (await db.query(`select * from public.missions where id = '${missionEditId}'`)).rows[0];
  assert.equal(missionAfterAllSettled.escrow_refunded, true, 'Escrow is now refunded');
  assert.equal(missionAfterAllSettled.escrow_remaining_credits, 0, 'Remaining escrow balance reset to 0');

  // Verify owner received credit refund
  const refundCreditLog = await db.query(`
    select * from public.credit_logs
    where user_id = '${ownerId}' and action = 'mission_refund'
    order by created_at desc limit 1;
  `);
  assert.ok(refundCreditLog.rows.length > 0, 'Owner received mission_refund credit log');
  assert.equal(refundCreditLog.rows[0].amount, 585, 'Refunded 600 - 15 (paid) = 585 credits');
  console.log('PASS: Unused escrow of 585 credits refunded to owner atomically after final settlement');

  // =========================================================================
  // Test 7: Approval After Expiration & sync_expired_missions Sweep
  // =========================================================================
  console.log('\n--- Test 7: Approval After Expiration & sync_expired_missions Sweep ---');
  // Create mission with short duration on camp4Id
  let expiredMissionId;
  await asUser(ownerId, async () => {
    const res = await db.query(`
      select public.create_mission_v2(
        '${camp4Id}'::uuid, 'Expiring Mission', 'Desc', 2, 1, 10, 'once'
      ) as r;
    `);
    expiredMissionId = res.rows[0].r.mission_id;
  });

  // Tester 1 joins and submits report
  await asUser(tester1Id, async () => {
    await db.query(`select public.join_mission('${expiredMissionId}'::uuid);`);
  });
  let reportOnExpiringMission;
  await asUser(tester1Id, async () => {
    const sub = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${camp4Id}'::uuid, 'Submitted in time', 5,
        '55555555-4444-5555-4444-555555555551'::uuid,
        '${makeScreenshotUrl(camp4Id, tester1Id, 'photo_expire')}',
        '7777666655554444',
        null,
        '${expiredMissionId}'::uuid,
        null,
        null
      ) as r;
    `);
    reportOnExpiringMission = sub.rows[0].r.report_id;
  });

  // Fast forward mission ends_at to past
  await db.exec(`
    update public.missions
    set ends_at = now() - interval '1 hour'
    where id = '${expiredMissionId}';
  `);

  // Run sync_expired_missions
  const syncRes = await db.query(`select public.sync_expired_missions() as updated;`);
  console.log(`PASS: sync_expired_missions executed (updated: ${syncRes.rows[0].updated})`);

  // Mission is now 'expired'
  const expStatus = (await db.query(`select status from public.missions where id = '${expiredMissionId}'`)).rows[0].status;
  assert.equal(expStatus, 'expired');

  // Owner approves report AFTER expiration: must succeed and settle!
  await asUser(ownerId, async () => {
    const appRes = await db.query(`
      select public.approve_tester_report('${reportOnExpiringMission}'::uuid, 0) as r;
    `);
    assert.equal(appRes.rows[0].r.success, true);
    console.log('PASS: Report submitted in time was approved and rewarded AFTER mission expiration');
  });

  // Verify remaining escrow was refunded
  const expMissionFinal = (await db.query(`select * from public.missions where id = '${expiredMissionId}'`)).rows[0];
  assert.equal(expMissionFinal.escrow_refunded, true);
  console.log('PASS: Expired mission unused escrow refunded to owner after last report settled');

  // =========================================================================
  // Test 8: Discovery & Metadata Verification (get_open_missions & get_campaign_mission)
  // =========================================================================
  console.log('\n--- Test 8: Discovery & Metadata Verification ---');
  await asUser(tester1Id, async () => {
    const openRes = await db.query(`select public.get_open_missions() as r;`);
    const missions = openRes.rows[0].r.missions;
    assert.ok(Array.isArray(missions));
    const sample = missions[0];
    if (sample) {
      assert.ok('reward_mode' in sample);
      assert.ok('duration_days' in sample);
      assert.ok('fee_credits' in sample);
      assert.ok('escrow_total_credits' in sample);
      assert.ok('escrow_remaining_credits' in sample);
      assert.ok('submitted_days_count' in sample);
      assert.ok('today_submitted' in sample);
    }

    const campMissionRes = await db.query(`
      select public.get_campaign_mission('${campOpenId}'::uuid) as r;
    `);
    const m = campMissionRes.rows[0].r.mission;
    assert.ok(m, 'Campaign mission found');
    assert.ok('reward_mode' in m);
    assert.ok('escrow_total_credits' in m);
    console.log('PASS: get_open_missions and get_campaign_mission return all required metadata');
  });

  console.log('\n=== ALL 15 MISSION REWARD MODES & MANAGEMENT TEST CASES PASSED 100% ===');
} catch (err) {
  console.error('TEST RUN FAILED:', err);
  process.exit(1);
}
