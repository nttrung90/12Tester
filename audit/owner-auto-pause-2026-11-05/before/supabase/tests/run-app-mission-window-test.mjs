import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING APP MISSION WINDOW & MULTI-ATTACHMENT TESTS (MIGRATION 074) ===');

const db = await createDb({ legacyGrants: true });

try {
  // Load all migrations from 062 to 074
  const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x) && Number(x.slice(0, 3)) >= 62)
    .sort();

  for (const filename of migrationFiles) {
    await db.exec(read('supabase/migrations/' + filename));
  }

  const ownerId = '11111111-1111-1111-1111-111111111111';
  const testerId = '22222222-2222-2222-2222-222222222222';
  const campId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const mission3Days = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${ownerId}', 'owner@test.local'),
      ('${testerId}', 'tester@test.local');
    update public.profiles set credits = 10000 where id = '${ownerId}';
    update public.profiles set credits = 500 where id = '${testerId}';
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number)
    values ('${campId}', '${ownerId}', 'com.mission.window.test', 'Window Test App', 'open', 1);

    insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
    values ('${mission3Days}', '${ownerId}', '${campId}', '3-Day Quest', 10, 3, 50, 'open', now() + interval '3 days');

    insert into public.mission_participants (mission_id, user_id, status)
    values ('${mission3Days}', '${testerId}', 'joined');
  `);

  async function asTester(fn) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${testerId}', false); set role authenticated;`);
    try {
      return await fn();
    } finally {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', false);`);
    }
  }

  async function asOwner(fn) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${ownerId}', false); set role authenticated;`);
    try {
      return await fn();
    } finally {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', false);`);
    }
  }

  // 1. Submit v3 with 3 attachments for mission report
  const att1Id = 'cccccccc-0000-0000-0000-000000000001';
  const att2Id = 'cccccccc-0000-0000-0000-000000000002';
  const att3Id = 'cccccccc-0000-0000-0000-000000000003';
  const clientReqMissionDay1 = 'dddddddd-0000-0000-0000-000000000001';

  const attachments3 = [
    {
      id: att1Id,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2F${att1Id}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2Fthumb_${att1Id}.jpg?alt=media&token=fixture`,
      image_hash: '1111222233334444',
      sha256: 'sha256_mock_photo_1'
    },
    {
      id: att2Id,
      position: 1,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2F${att2Id}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2Fthumb_${att2Id}.jpg?alt=media&token=fixture`,
      image_hash: '2222333344445555',
      sha256: 'sha256_mock_photo_2'
    },
    {
      id: att3Id,
      position: 2,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2F${att3Id}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2Fthumb_${att3Id}.jpg?alt=media&token=fixture`,
      image_hash: '3333444455556666',
      sha256: 'sha256_mock_photo_3'
    }
  ];

  let resMissionDay1;
  await asTester(async () => {
    resMissionDay1 = (await db.query(`
      select public.submit_tester_report_v3(
        p_submission_context => 'mission',
        p_campaign_id => '${campId}',
        p_content => 'Mission report with 3 photos',
        p_rating => 5,
        p_client_submit_id => '${clientReqMissionDay1}',
        p_attachments => '${JSON.stringify(attachments3)}'::jsonb,
        p_mission_id => '${mission3Days}'
      ) as r
    `)).rows[0].r;
  });

  assert.equal(resMissionDay1.success, true);
  assert.equal(resMissionDay1.submission_context, 'mission');
  assert.equal(resMissionDay1.attachments.length, 3);
  assert.equal(resMissionDay1.attachments[0].id, att1Id);
  assert.equal(resMissionDay1.attachments[1].id, att2Id);
  assert.equal(resMissionDay1.attachments[2].id, att3Id);
  console.log('PASS: 1. submit_tester_report_v3 successfully submits 3 attachments');

  // 2. Reject >3 attachments
  const attachments4 = [...attachments3, {
    id: 'cccccccc-0000-0000-0000-000000000004',
    position: 3,
    screenshot_url: 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports/fake.jpg',
    image_hash: '4444555566667777'
  }];

  await assert.rejects(
    asTester(async () => {
      await db.query(`
        select public.submit_tester_report_v3(
          p_submission_context => 'mission',
          p_campaign_id => '${campId}',
          p_content => 'Too many attachments',
          p_rating => 5,
          p_client_submit_id => 'dddddddd-0000-0000-0000-000000000002',
          p_attachments => '${JSON.stringify(attachments4)}'::jsonb,
          p_mission_id => '${mission3Days}'
        )
      `);
    }),
    /TOO_MANY_ATTACHMENTS/
  );
  console.log('PASS: 2. >3 attachments is rejected with TOO_MANY_ATTACHMENTS');

  // 3. Replay returns identical snapshot with attachments
  let replayMissionDay1;
  await asTester(async () => {
    replayMissionDay1 = (await db.query(`
      select public.submit_tester_report_v3(
        p_submission_context => 'mission',
        p_campaign_id => '${campId}',
        p_content => 'Mission report with 3 photos',
        p_rating => 5,
        p_client_submit_id => '${clientReqMissionDay1}',
        p_attachments => '${JSON.stringify(attachments3)}'::jsonb,
        p_mission_id => '${mission3Days}'
      ) as r
    `)).rows[0].r;
  });
  assert.equal(replayMissionDay1.report_id, resMissionDay1.report_id);
  assert.equal(replayMissionDay1.attachments.length, 3);
  console.log('PASS: 3. Idempotent replay returns identical response with 3 attachments');

  // 4. Submit ordinary app_test on the same day -> succeeds independently!
  const clientReqAppTest = 'dddddddd-0000-0000-0000-000000000003';
  let resAppTest;
  await asTester(async () => {
    resAppTest = (await db.query(`
      select public.submit_tester_report_v2(
        p_submission_context => 'app_test',
        p_campaign_id => '${campId}',
        p_content => 'Ordinary app test report today',
        p_rating => 5,
        p_client_submit_id => '${clientReqAppTest}',
        p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2Fordinary.jpg?alt=media&token=fixture',
        p_image_hash => 'abcdef0123456789'
      ) as r
    `)).rows[0].r;
  });
  assert.equal(resAppTest.success, true);
  assert.equal(resAppTest.submission_context, 'app_test');
  assert.notEqual(resAppTest.report_id, resMissionDay1.report_id);
  console.log('PASS: 4. Ordinary app_test succeeds independently on same day after mission report');

  // Verify both reports exist in database
  const countReports = (await db.query(`select count(*)::int as c from public.tester_reports where user_id = '${testerId}' and campaign_id = '${campId}'`)).rows[0].c;
  assert.equal(countReports, 2, 'Exactly 2 reports must exist for tester today on this campaign');
  console.log('PASS: 5. 2 distinct reports coexist in database for same user, same campaign, same day');

  // 6. Approve day 1 mission report
  await asOwner(async () => {
    await db.query(`select public.approve_tester_report('${resMissionDay1.report_id}', 0)`);
  });
  console.log('PASS: 6. Owner approved day 1 mission report');

  // 7. Verify Day 2 mission report allowed in mission window
  // Simulate next calendar day by updating report_date of day 1 reports to yesterday
  await db.exec(`
    update public.tester_reports
    set report_date = report_date - interval '1 day',
        created_at = created_at - interval '1 day'
    where id in ('${resMissionDay1.report_id}', '${resAppTest.report_id}');
  `);

  const clientReqMissionDay2 = 'dddddddd-0000-0000-0000-000000000004';
  const attDay2_1 = 'cccccccc-0000-0000-0000-000000000011';
  const attDay2_2 = 'cccccccc-0000-0000-0000-000000000012';
  const attachmentsDay2 = [
    {
      id: attDay2_1,
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2F${attDay2_1}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2Fthumb_${attDay2_1}.jpg?alt=media&token=fixture`,
      image_hash: '5555666677778888',
      sha256: 'sha256_mock_photo_day2_1'
    },
    {
      id: attDay2_2,
      position: 1,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2F${attDay2_2}.jpg?alt=media&token=fixture`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${testerId}%2Fthumb_${attDay2_2}.jpg?alt=media&token=fixture`,
      image_hash: '6666777788889999',
      sha256: 'sha256_mock_photo_day2_2'
    }
  ];

  let resMissionDay2;
  await asTester(async () => {
    resMissionDay2 = (await db.query(`
      select public.submit_tester_report_v3(
        p_submission_context => 'mission',
        p_campaign_id => '${campId}',
        p_content => 'Day 2 mission report',
        p_rating => 5,
        p_client_submit_id => '${clientReqMissionDay2}',
        p_attachments => '${JSON.stringify(attachmentsDay2)}'::jsonb,
        p_mission_id => '${mission3Days}'
      ) as r
    `)).rows[0].r;
  });
  assert.equal(resMissionDay2.success, true);
  assert.notEqual(resMissionDay2.report_id, resMissionDay1.report_id);
  console.log('PASS: 7. Day 2 mission report succeeds independently after day 1 approval');

  // 8. Test get_campaign_detail returns 3 attachments in reports
  const detail = (await asTester(async () => {
    return (await db.query(`select public.get_campaign_detail('${campId}') as d`)).rows[0].d;
  }));
  const missionRep = detail.reports.find(r => r.id === resMissionDay1.report_id);
  assert.ok(missionRep, 'detail must include day 1 report');
  assert.equal(missionRep.attachments.length, 3, 'Day 1 report must have 3 attachments in get_campaign_detail');
  console.log('PASS: 8. get_campaign_detail returns attachments list with all 3 items');

  // 9. Test get_campaign_reports_for_owner returns attachments and labels
  const ownerReports = (await asOwner(async () => {
    return (await db.query(`select public.get_campaign_reports_for_owner('${campId}') as r`)).rows[0].r;
  }));
  const ownerRep = ownerReports.find(r => r.id === resMissionDay1.report_id);
  assert.ok(ownerRep);
  assert.equal(ownerRep.submission_context, 'mission');
  assert.equal(ownerRep.mission_title, '3-Day Quest');
  assert.equal(ownerRep.attachments.length, 3);
  console.log('PASS: 9. get_campaign_reports_for_owner includes mission metadata and 3 attachments');

  console.log('=== ALL APP MISSION WINDOW & MULTI-ATTACHMENT TESTS PASSED 100% ===');
} finally {
  await db.close();
}
