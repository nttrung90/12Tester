import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING MISSION FOR FINALIZED CAMPAIGNS TESTS (MIGRATION 076) ===');

const db = await createDb({ legacyGrants: true });

try {
  // 1. Load migrations 062..076
  const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x) && Number(x.slice(0, 3)) >= 62)
    .sort();

  console.log(`Loading ${migrationFiles.length} migrations from 062 to 076...`);
  for (const filename of migrationFiles) {
    await db.exec(read('supabase/migrations/' + filename));
  }

  // Setup test actors
  const ownerId = '11111111-1111-1111-1111-111111111111';
  const testerProdId = '22222222-2222-2222-2222-222222222222';
  const testerCompId = '33333333-3333-3333-3333-333333333333';
  const testerOpenId = '44444444-4444-4444-4444-444444444444';
  const otherUserId = '55555555-5555-5555-5555-555555555555';

  const campProdId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const campCompId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  const campOpenId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
  const campPausedId = 'dddddddd-dddd-dddd-dddd-dddddddddddd';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${ownerId}', 'owner@test.local'),
      ('${testerProdId}', 'tester_prod@test.local'),
      ('${testerCompId}', 'tester_comp@test.local'),
      ('${testerOpenId}', 'tester_open@test.local'),
      ('${otherUserId}', 'other@test.local');

    update public.profiles set credits = 10000, display_name = 'App Owner' where id = '${ownerId}';
    update public.profiles set credits = 500, display_name = 'Tester Prod' where id = '${testerProdId}';
    update public.profiles set credits = 500, display_name = 'Tester Comp' where id = '${testerCompId}';
    update public.profiles set credits = 500, display_name = 'Tester Open' where id = '${testerOpenId}';
    update public.profiles set credits = 500, display_name = 'Other User' where id = '${otherUserId}';

    insert into public.user_slots (user_id, slot_number, expires_at)
    values
      ('${ownerId}', 2, now() + interval '30 days'),
      ('${ownerId}', 3, now() + interval '30 days'),
      ('${ownerId}', 4, now() + interval '30 days');

    -- Campaigns with different statuses
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number)
    values
      ('${campProdId}', '${ownerId}', 'com.test.prod', 'Production App', 'production', 1),
      ('${campCompId}', '${ownerId}', 'com.test.comp', 'Completed App', 'completed', 2),
      ('${campOpenId}', '${ownerId}', 'com.test.open', 'Open App', 'open', 3),
      ('${campPausedId}', '${ownerId}', 'com.test.paused', 'Paused App', 'paused', 4);
  `);

  async function asUser(userId, fn) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${userId}', false); set role authenticated;`);
    try {
      return await fn();
    } finally {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', false);`);
    }
  }

  // Helper to query single json result from an RPC call
  async function rpc(sql) {
    const res = await db.query(sql);
    return res.rows[0];
  }

  console.log('\n--- Test 1: Production Campaign Mission Flow ---');
  let missionProdId;
  await asUser(ownerId, async () => {
    const initialBalRes = await db.query(`select credits from public.profiles where id = '${ownerId}'`);
    const initialBal = initialBalRes.rows[0].credits;

    const res = await rpc(`select public.create_mission(
      '${campProdId}'::uuid,
      'Mission for Production App',
      'Test production features',
      5,
      3,
      15
    ) as result;`);

    const result = res.result;
    assert.equal(result.success, true);
    assert.equal(result.fee, 150); // 3 days * 50
    missionProdId = result.mission_id;

    // Verify balance deduction
    const finalBalRes = await db.query(`select credits from public.profiles where id = '${ownerId}'`);
    assert.equal(finalBalRes.rows[0].credits, initialBal - 150);

    // Verify credit_logs
    const logRes = await db.query(`select * from public.credit_logs where user_id = '${ownerId}' order by created_at desc limit 1`);
    assert.equal(logRes.rows[0].action, 'mission_fee');
    assert.equal(logRes.rows[0].amount, -150);
  });
  console.log('PASS: Owner created mission for production campaign, fee correctly deducted');

  // Assert campaign status remains unchanged
  const campProdCheck = await db.query(`select status from public.campaigns where id = '${campProdId}'`);
  assert.equal(campProdCheck.rows[0].status, 'production');
  console.log('PASS: Campaign status remained "production"');

  // Discoverability
  await asUser(testerProdId, async () => {
    const discRes = await rpc(`select public.get_open_missions() as result;`);
    const missions = discRes.result.missions;
    const found = missions.find(m => m.id === missionProdId);
    assert.ok(found, 'Production mission must be discoverable');
    assert.equal(found.app_name, 'Production App');
    assert.equal(found.package_name, 'com.test.prod');
  });
  console.log('PASS: Tester discovered production campaign mission via get_open_missions');

  // Tester joins mission
  await asUser(testerProdId, async () => {
    const joinRes = await rpc(`select public.join_mission('${missionProdId}'::uuid) as result;`);
    assert.equal(joinRes.result.success, true);
    assert.equal(joinRes.result.status, 'open');
  });
  console.log('PASS: Tester joined production mission');

  // Tester submits v3 report
  const clientReqProd = '10000000-0000-0000-0000-000000000001';
  let reportProdId;
  const attachmentsProd = [
    {
      id: '20000000-0000-0000-0000-000000000001',
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campProdId}%2F${testerProdId}%2F20000000-0000-0000-0000-000000000001.jpg?alt=media&token=tok`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campProdId}%2F${testerProdId}%2Fthumb_20000000-0000-0000-0000-000000000001.jpg?alt=media&token=tok`,
      image_hash: '1111222233334444',
      sha256: 'sha256_prod_1'
    }
  ];

  await asUser(testerProdId, async () => {
    const repRes = await rpc(`select public.submit_tester_report_v3(
      'mission',
      '${campProdId}'::uuid,
      'Production app works great!',
      5,
      '${clientReqProd}'::uuid,
      '${JSON.stringify(attachmentsProd)}'::jsonb,
      '${missionProdId}'::uuid,
      null
    ) as result;`);

    assert.equal(repRes.result.success, true);
    assert.equal(repRes.result.status, 'pending');
    assert.equal(repRes.result.provisional_granted, false);
    assert.equal(repRes.result.provisional_amount, 0);
    reportProdId = repRes.result.report_id;
  });
  console.log('PASS: Tester submitted v3 report for production mission');

  // Check no ordinary check-in was created
  const checkinProd = await db.query(`select * from public.checkins where campaign_id = '${campProdId}' and user_id = '${testerProdId}'`);
  assert.equal(checkinProd.rows.length, 0, 'Mission report must NOT create ordinary check-in');
  console.log('PASS: No ordinary check-in created for finalized mission report');

  // Owner reviews and approves report
  await asUser(ownerId, async () => {
    const approveRes = await rpc(`select public.approve_tester_report('${reportProdId}'::uuid) as result;`);
    assert.equal(approveRes.result.success, true);
    assert.equal(approveRes.result.status, 'approved');
  });
  console.log('PASS: Owner approved production mission report');

  // Verify settlement and rewards
  const testerBalProd = await db.query(`select credits from public.profiles where id = '${testerProdId}'`);
  assert.equal(testerBalProd.rows[0].credits, 500 + 2 + 15, 'Tester received review bonus + 15 mission reward');
  const campProdFinal = await db.query(`select status from public.campaigns where id = '${campProdId}'`);
  assert.equal(campProdFinal.rows[0].status, 'production', 'Campaign remains production');
  console.log('PASS: Production mission reward settled, campaign status remained "production"');

  // Ordinary report on production campaign must be rejected
  await asUser(testerProdId, async () => {
    await assert.rejects(
      async () => {
        await rpc(`select public.submit_tester_report_v3(
          'app_test',
          '${campProdId}'::uuid,
          'Normal test attempt',
          5,
          '10000000-0000-0000-0000-000000000099'::uuid,
          '${JSON.stringify(attachmentsProd)}'::jsonb,
          null,
          null
        ) as result;`);
      },
      /CAMPAIGN_NOT_OPEN/
    );
  });
  console.log('PASS: Ordinary report (app_test) on production campaign strictly rejected');


  console.log('\n--- Test 2: Completed Campaign Mission Flow ---');
  let missionCompId;
  await asUser(ownerId, async () => {
    const res = await rpc(`select public.create_mission(
      '${campCompId}'::uuid,
      'Mission for Completed App',
      'Test completed app features',
      3,
      2,
      20
    ) as result;`);

    assert.equal(res.result.success, true);
    assert.equal(res.result.fee, 100); // 2 days * 50
    missionCompId = res.result.mission_id;
  });
  console.log('PASS: Owner created mission for completed campaign');

  const campCompCheck = await db.query(`select status from public.campaigns where id = '${campCompId}'`);
  assert.equal(campCompCheck.rows[0].status, 'completed');
  console.log('PASS: Campaign status remained "completed"');

  // Tester joins completed mission
  await asUser(testerCompId, async () => {
    const joinRes = await rpc(`select public.join_mission('${missionCompId}'::uuid) as result;`);
    assert.equal(joinRes.result.success, true);
  });
  console.log('PASS: Tester joined completed campaign mission');

  // Tester submits v3 report
  const clientReqComp = '30000000-0000-0000-0000-000000000001';
  let reportCompId;
  const attachmentsComp = [
    {
      id: '30000000-0000-0000-0000-000000000002',
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campCompId}%2F${testerCompId}%2F30000000-0000-0000-0000-000000000002.jpg?alt=media&token=tok`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campCompId}%2F${testerCompId}%2Fthumb_30000000-0000-0000-0000-000000000002.jpg?alt=media&token=tok`,
      image_hash: '5555666677778888',
      sha256: 'sha256_comp_1'
    }
  ];

  await asUser(testerCompId, async () => {
    const repRes = await rpc(`select public.submit_tester_report_v3(
      'mission',
      '${campCompId}'::uuid,
      'Completed app feedback',
      5,
      '${clientReqComp}'::uuid,
      '${JSON.stringify(attachmentsComp)}'::jsonb,
      '${missionCompId}'::uuid,
      null
    ) as result;`);

    assert.equal(repRes.result.success, true);
    reportCompId = repRes.result.report_id;
  });

  const checkinComp = await db.query(`select * from public.checkins where campaign_id = '${campCompId}' and user_id = '${testerCompId}'`);
  assert.equal(checkinComp.rows.length, 0);

  // Owner reviews and settles
  await asUser(ownerId, async () => {
    const approveRes = await rpc(`select public.approve_tester_report('${reportCompId}'::uuid) as result;`);
    assert.equal(approveRes.result.success, true);
  });

  const testerBalComp = await db.query(`select credits from public.profiles where id = '${testerCompId}'`);
  assert.equal(testerBalComp.rows[0].credits, 500 + 2 + 20, 'Tester received review bonus + 20 mission reward');
  const campCompFinal = await db.query(`select status from public.campaigns where id = '${campCompId}'`);
  assert.equal(campCompFinal.rows[0].status, 'completed');
  console.log('PASS: Completed campaign mission report approved & settled, campaign status remained "completed"');

  // Ordinary report on completed campaign rejected
  await asUser(testerCompId, async () => {
    await assert.rejects(
      async () => {
        await rpc(`select public.submit_tester_report_v3(
          'app_test',
          '${campCompId}'::uuid,
          'Normal test attempt',
          5,
          '30000000-0000-0000-0000-000000000099'::uuid,
          '${JSON.stringify(attachmentsComp)}'::jsonb,
          null,
          null
        ) as result;`);
      },
      /CAMPAIGN_NOT_OPEN/
    );
  });
  console.log('PASS: Ordinary report (app_test) on completed campaign strictly rejected');


  console.log('\n--- Test 3: Open Campaign Regression Flow ---');
  let missionOpenId;
  await asUser(ownerId, async () => {
    const res = await rpc(`select public.create_mission(
      '${campOpenId}'::uuid,
      'Mission for Open App',
      'Open test',
      4,
      1,
      10
    ) as result;`);
    assert.equal(res.result.success, true);
    missionOpenId = res.result.mission_id;
  });

  await asUser(testerOpenId, async () => {
    const joinRes = await rpc(`select public.join_mission('${missionOpenId}'::uuid) as result;`);
    assert.equal(joinRes.result.success, true);

    const clientReqOpen = '40000000-0000-0000-0000-000000000001';
    const attachmentsOpen = [
      {
        id: '40000000-0000-0000-0000-000000000002',
        position: 0,
        screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campOpenId}%2F${testerOpenId}%2F40000000-0000-0000-0000-000000000002.jpg?alt=media&token=tok`,
        thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campOpenId}%2F${testerOpenId}%2Fthumb_40000000-0000-0000-0000-000000000002.jpg?alt=media&token=tok`,
        image_hash: '9999000011112222',
        sha256: 'sha256_open_1'
      }
    ];

    const repRes = await rpc(`select public.submit_tester_report_v3(
      'mission',
      '${campOpenId}'::uuid,
      'Open mission report',
      5,
      '${clientReqOpen}'::uuid,
      '${JSON.stringify(attachmentsOpen)}'::jsonb,
      '${missionOpenId}'::uuid,
      null
    ) as result;`);
    assert.equal(repRes.result.success, true);

    await asUser(ownerId, async () => {
      const approveRes = await rpc(`select public.approve_tester_report('${repRes.result.report_id}'::uuid) as result;`);
      assert.equal(approveRes.result.success, true);
    });
  });

  const testerBalOpen = await db.query(`select credits from public.profiles where id = '${testerOpenId}'`);
  assert.equal(testerBalOpen.rows[0].credits, 500 + 2 + 10, 'Tester received review bonus + 10 mission reward');
  console.log('PASS: Open campaign mission regression passed completely');


  console.log('\n--- Test 4: Exclusion of Paused & Unsupported States ---');
  await asUser(ownerId, async () => {
    await assert.rejects(
      async () => {
        await rpc(`select public.create_mission(
          '${campPausedId}'::uuid,
          'Mission for Paused App',
          'Should fail',
          5, 3, 10
        ) as result;`);
      },
      /CAMPAIGN_NOT_OPEN/
    );
  });
  console.log('PASS: create_mission rejected for paused campaign');

  // Verify joined mission cannot be joined if campaign was paused
  const dummyMissionPausedCamp = '50000000-0000-0000-0000-000000000001';
  await db.exec(`
    insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
    values ('${dummyMissionPausedCamp}', '${ownerId}', '${campPausedId}', 'Paused Camp Mission', 5, 3, 10, 'open', now() + interval '3 days');
  `);

  await asUser(otherUserId, async () => {
    await assert.rejects(
      async () => {
        await rpc(`select public.join_mission('${dummyMissionPausedCamp}'::uuid) as result;`);
      },
      /CAMPAIGN_NOT_OPEN/
    );
  });
  console.log('PASS: join_mission rejected when campaign is paused');

  // Verify submit rejected when campaign is paused
  const attachmentsPaused = [
    {
      id: '50000000-0000-0000-0000-000000000003',
      position: 0,
      screenshot_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campPausedId}%2F${otherUserId}%2F50000000-0000-0000-0000-000000000003.jpg?alt=media&token=tok`,
      thumbnail_url: `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campPausedId}%2F${otherUserId}%2Fthumb_50000000-0000-0000-0000-000000000003.jpg?alt=media&token=tok`,
      image_hash: '9999000011112222',
      sha256: 'sha256_paused_1'
    }
  ];
  await db.exec(`
    insert into public.mission_participants (mission_id, user_id, status)
    values ('${dummyMissionPausedCamp}', '${otherUserId}', 'joined');
  `);
  await asUser(otherUserId, async () => {
    await assert.rejects(
      async () => {
        await rpc(`select public.submit_tester_report_v3(
          'mission',
          '${campPausedId}'::uuid,
          'Test on paused app',
          5,
          '50000000-0000-0000-0000-000000000002'::uuid,
          '${JSON.stringify(attachmentsPaused)}'::jsonb,
          '${dummyMissionPausedCamp}'::uuid,
          null
        ) as result;`);
      },
      /CAMPAIGN_NOT_OPEN/
    );
  });
  console.log('PASS: submit_tester_report_v3 rejected for mission on paused campaign');


  console.log('\n--- Test 5: Single Live Mission Slot & Busy IDs Endpoint ---');
  // Both campProdId and campCompId have active missions now
  await asUser(ownerId, async () => {
    const busyRes = await rpc(`select public.get_my_active_mission_campaign_ids() as result;`);
    const busyIds = busyRes.result;
    assert.ok(busyIds.includes(campProdId), 'campProdId must be marked busy');
    assert.ok(busyIds.includes(campCompId), 'campCompId must be marked busy');
    assert.ok(busyIds.includes(campOpenId), 'campOpenId must be marked busy');

    // Attempting to create duplicate live mission fails
    await assert.rejects(
      async () => {
        await rpc(`select public.create_mission(
          '${campProdId}'::uuid,
          'Duplicate Mission',
          'Should fail',
          5, 3, 10
        ) as result;`);
      },
      /MISSION_ALREADY_EXISTS/
    );
  });
  console.log('PASS: Exactly one live mission slot enforced per campaign; busy endpoint reports active IDs');


  console.log('\n--- Test 6: Mission Pause & Resume Regression on Finalized Campaign ---');
  // Pause the completed mission
  await asUser(ownerId, async () => {
    const pauseRes = await rpc(`select public.pause_mission('${missionCompId}'::uuid) as result;`);
    assert.equal(pauseRes.result.success, true);
    assert.equal(pauseRes.result.status, 'paused_by_creator');

    // Resume the completed mission
    const resumeRes = await rpc(`select public.resume_mission('${missionCompId}'::uuid) as result;`);
    assert.equal(resumeRes.result.success, true);
    assert.equal(resumeRes.result.status, 'open');
  });
  console.log('PASS: Owner paused and resumed mission on completed campaign successfully');

  // Resume fails if campaign status was moved to paused
  await db.exec(`update public.campaigns set status = 'paused' where id = '${campCompId}';`);
  await asUser(ownerId, async () => {
    await rpc(`select public.pause_mission('${missionCompId}'::uuid) as result;`);
    await assert.rejects(
      async () => {
        await rpc(`select public.resume_mission('${missionCompId}'::uuid) as result;`);
      },
      /CAMPAIGN_NOT_OPEN/
    );
  });
  // Restore campaign status
  await db.exec(`update public.campaigns set status = 'completed' where id = '${campCompId}';`);
  console.log('PASS: resume_mission rejected if campaign becomes paused');


  console.log('\n--- Test 7: Direct RPC Security & Validation ---');
  // Unauthenticated
  await assert.rejects(
    async () => {
      await rpc(`select public.create_mission('${campProdId}'::uuid, 'Test', '', 5, 3, 10);`);
    },
    /NOT_AUTHENTICATED/
  );

  // Non-owner cannot create mission
  await asUser(testerProdId, async () => {
    await assert.rejects(
      async () => {
        await rpc(`select public.create_mission('${campProdId}'::uuid, 'Test', '', 5, 3, 10);`);
      },
      /PERMISSION_DENIED/
    );
  });

  // Owner cannot join own mission
  await asUser(ownerId, async () => {
    await assert.rejects(
      async () => {
        await rpc(`select public.join_mission('${missionProdId}'::uuid);`);
      },
      /CANNOT_JOIN_OWN_MISSION/
    );
  });

  // Invalid params do not deduct credits
  await asUser(ownerId, async () => {
    const balBefore = (await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits;
    await assert.rejects(
      async () => {
        await rpc(`select public.create_mission('${campProdId}'::uuid, '', '', 5, 3, 10);`);
      },
      /MISSION_TITLE_REQUIRED/
    );
    await assert.rejects(
      async () => {
        await rpc(`select public.create_mission('${campProdId}'::uuid, 'Title', '', 0, 3, 10);`);
      },
      /INVALID_PARTICIPANTS/
    );
    await assert.rejects(
      async () => {
        await rpc(`select public.create_mission('${campProdId}'::uuid, 'Title', '', 5, 10, 10);`);
      },
      /INVALID_DURATION/
    );
    await assert.rejects(
      async () => {
        await rpc(`select public.create_mission('${campProdId}'::uuid, 'Title', '', 5, 3, 5);`);
      },
      /INVALID_REWARD/
    );
    const balAfter = (await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits;
    assert.equal(balBefore, balAfter, 'Credits must not be deducted on invalid parameters');
  });
  console.log('PASS: Security definer, auth, permissions, and invalid parameter guards enforced without debiting fees');

  console.log('\n--- Test 8: Trigger trg_reports_require_open_campaign Hardening ---');
  await asUser(testerProdId, async () => {
    // Attempting direct INSERT without marker on finalized campaign
    await assert.rejects(
      async () => {
        await db.exec(`
          insert into public.tester_reports (campaign_id, user_id, report_type, content, rating, status, submission_context)
          values ('${campProdId}', '${testerProdId}', 'feedback', 'Direct bypass test', 5, 'pending', 'app_test');
        `);
      },
      /CAMPAIGN_NOT_OPEN|permission denied|CANNOT_SET_MISSION_METADATA_DIRECTLY/
    );

    // Attempting direct INSERT on finalized campaign even if claiming mission context
    await assert.rejects(
      async () => {
        await db.exec(`
          insert into public.tester_reports (campaign_id, user_id, report_type, content, rating, status, submission_context, mission_id)
          values ('${campProdId}', '${testerProdId}', 'feedback', 'Direct bypass test', 5, 'pending', 'mission', '${missionProdId}');
        `);
      },
      /CAMPAIGN_NOT_OPEN|permission denied|CANNOT_SET_MISSION_METADATA_DIRECTLY/
    );
  });
  console.log('PASS: Direct INSERT attempts on finalized campaigns strictly blocked by triggers and RLS');

  console.log('\n=== ALL MISSION FOR FINALIZED CAMPAIGNS TESTS PASSED 100% ===');
} finally {
  // Cleanup if needed
}
