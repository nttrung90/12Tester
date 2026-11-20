import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING MISSION COPY & PREVIOUS PARTICIPANT BLOCK TESTS (MIGRATION 100) ===');

const db = await createDb({ legacyGrants: true });

let passed = 0;
function pass(msg) {
  passed += 1;
  console.log(`PASS [${passed}]: ${msg}`);
}

async function asUser(userId, fn) {
  await db.exec(`select set_config('request.jwt.claim.sub', '${userId}', false); set role authenticated;`);
  try {
    return await fn();
  } finally {
    await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', false);`);
  }
}

async function asServiceRole(fn) {
  await db.exec(`select set_config('request.jwt.claim.sub', '', false); set role service_role;`);
  try {
    return await fn();
  } finally {
    await db.exec(`reset role;`);
  }
}

function makeScreenshotUrl(camp, user, fileId) {
  return `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp}%2F${user}%2F${fileId}.jpg?alt=media&token=fixture`;
}

try {
  // -------------------------------------------------------------------------
  // 1. Upgrade test: load 062..099 first, seed pre-existing mission data, then apply 100
  // -------------------------------------------------------------------------
  const allMigrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x) && Number(x.slice(0, 3)) >= 62)
    .sort();
  const pre100 = allMigrations.filter(x => Number(x.slice(0, 3)) < 100);
  const mig100 = allMigrations.find(x => Number(x.slice(0, 3)) === 100);
  assert.ok(mig100, 'Migration 100 must exist');

  for (const filename of pre100) {
    await db.exec(read('supabase/migrations/' + filename));
  }

  const adminId = '11111111-aaaa-4111-8aaa-111111111111';
  const ownerId = '22222222-bbbb-4222-8bbb-222222222222';
  const otherOwnerId = '22222222-cccc-4222-8ccc-222222222222';
  const tester1Id = '33333333-cccc-4333-8ccc-333333333333';
  const tester2Id = '44444444-dddd-4444-8ddd-444444444444';
  const tester3Id = '55555555-eeee-4555-8eee-555555555555';
  const lowCreditOwnerId = '66666666-ffff-4666-8fff-666666666666';

  const camp1Id = 'aaaaaaaa-1111-4aaa-8111-aaaaaaaaaaaa';
  const camp2Id = 'bbbbbbbb-2222-4bbb-8222-bbbbbbbbbbbb';
  const campPausedId = 'cccccccc-3333-4ccc-8333-cccccccccccc';
  const campOtherOwnerId = 'dddddddd-4444-4ddd-8444-dddddddddddd';
  const campLowCreditId = 'eeeeeeee-5555-4eee-8555-eeeeeeeeeeee';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${adminId}', 'admin@copy.test'),
      ('${ownerId}', 'owner@copy.test'),
      ('${otherOwnerId}', 'other@copy.test'),
      ('${tester1Id}', 'tester1@copy.test'),
      ('${tester2Id}', 'tester2@copy.test'),
      ('${tester3Id}', 'tester3@copy.test'),
      ('${lowCreditOwnerId}', 'low@copy.test');

    update public.profiles set credits = 5000, display_name = 'Admin', role = 'admin', is_locked = false where id = '${adminId}';
    update public.profiles set credits = 10000, display_name = 'Owner', role = 'member', is_locked = false where id = '${ownerId}';
    update public.profiles set credits = 5000, display_name = 'Other Owner', role = 'member', is_locked = false where id = '${otherOwnerId}';
    update public.profiles set credits = 500, display_name = 'Tester 1', role = 'member', is_locked = false where id = '${tester1Id}';
    update public.profiles set credits = 500, display_name = 'Tester 2', role = 'member', is_locked = false where id = '${tester2Id}';
    update public.profiles set credits = 500, display_name = 'Tester 3', role = 'member', is_locked = false where id = '${tester3Id}';
    update public.profiles set credits = 250, display_name = 'Low Credit', role = 'member', is_locked = false where id = '${lowCreditOwnerId}';

    insert into public.user_slots (user_id, slot_number, expires_at) values
      ('${ownerId}', 2, now() + interval '30 days'),
      ('${ownerId}', 3, now() + interval '30 days');

    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values
      ('${camp1Id}', '${ownerId}', 'com.copy.app1', 'Copy App 1', 'open', 1),
      ('${camp2Id}', '${ownerId}', 'com.copy.app2', 'Copy App 2', 'production', 2),
      ('${campPausedId}', '${ownerId}', 'com.copy.paused', 'Paused App', 'paused', 3),
      ('${campOtherOwnerId}', '${otherOwnerId}', 'com.copy.other', 'Other App', 'open', 1),
      ('${campLowCreditId}', '${lowCreditOwnerId}', 'com.copy.low', 'Low App', 'open', 1);
  `);

  // Seed pre-100 legacy mission before applying migration 100
  const legacyMissionId = '99000000-0000-4000-8000-000000000001';
  await db.exec(`
    insert into public.missions (
      id, creator_id, campaign_id, title, description,
      max_participants, duration_days, reward_credits, fee_credits,
      status, participant_count, ends_at, reward_mode, tester_announcement
    ) values (
      '${legacyMissionId}', '${ownerId}', '${camp2Id}', 'Legacy Pre-100', 'Legacy desc',
      4, 2, 15, 100,
      'expired', 1, now() - interval '1 day', 'legacy', 'Old private note'
    );
  `);

  // Now apply migration 100 on top of populated schema
  await db.exec(read('supabase/migrations/' + mig100));
  pass('Upgrade from 062..099 with pre-existing mission data to migration 100 succeeded');

  // Verify schema, constraints, indexes, and ACL
  const schemaCheck = await db.query(`
    select
      exists (select 1 from information_schema.columns where table_schema='public' and table_name='missions' and column_name='source_mission_id') as has_src,
      exists (select 1 from information_schema.columns where table_schema='public' and table_name='missions' and column_name='block_previous_participants') as has_blk,
      exists (select 1 from information_schema.columns where table_schema='public' and table_name='missions' and column_name='client_operation_id') as has_op,
      has_function_privilege('anon', 'public.get_mission_copy_draft(uuid, uuid)', 'EXECUTE') as anon_draft,
      has_function_privilege('authenticated', 'public.get_mission_copy_draft(uuid, uuid)', 'EXECUTE') as auth_draft,
      has_function_privilege('anon', 'public.create_mission_from_source(uuid, uuid, text, text, integer, integer, integer, text, boolean, text)', 'EXECUTE') as anon_create,
      has_function_privilege('authenticated', 'public.create_mission_from_source(uuid, uuid, text, text, integer, integer, integer, text, boolean, text)', 'EXECUTE') as auth_create,
      has_function_privilege('authenticated', 'public._is_mission_expired_for_copy(text, timestamptz, timestamptz)', 'EXECUTE') as auth_helper,
      has_table_privilege('anon', 'public.mission_copy_operations', 'SELECT') as anon_ops,
      has_table_privilege('authenticated', 'public.mission_copy_operations', 'SELECT') as auth_ops
  `);
  const sc = schemaCheck.rows[0];
  assert.equal(sc.has_src, true);
  assert.equal(sc.has_blk, true);
  assert.equal(sc.has_op, true);
  assert.equal(sc.anon_draft, false);
  assert.equal(sc.auth_draft, true);
  assert.equal(sc.anon_create, false);
  assert.equal(sc.auth_create, true);
  assert.equal(sc.auth_helper, false);
  assert.equal(sc.anon_ops, false);
  assert.equal(sc.auth_ops, false);

  await assert.rejects(
    () => db.exec(`
      insert into public.missions (creator_id, campaign_id, title, max_participants, duration_days, reward_credits, ends_at, block_previous_participants)
      values ('${ownerId}', '${camp1Id}', 'Bad', 5, 2, 10, now() + interval '2 days', true);
    `),
    /missions_block_requires_source_check/
  );
  pass('Schema columns, ACL grants, and block-requires-source constraint verified');

  // -------------------------------------------------------------------------
  // 2. Legacy draft normalization & old RPC compatibility
  // -------------------------------------------------------------------------
  await asUser(ownerId, async () => {
    const draftRes = await db.query(`select public.get_mission_copy_draft('${legacyMissionId}'::uuid, '${camp2Id}'::uuid) as r;`);
    const d = draftRes.rows[0].r;
    assert.equal(d.reward_mode, 'once', 'Legacy mode must default to once in copy draft');
    assert.equal(d.source_reward_mode, 'legacy');
    assert.equal(d.block_previous_participants, false);
    assert.equal(d.title, 'Legacy Pre-100');
    assert.equal(d.description, 'Legacy desc');
    assert.equal(d.max_participants, 4);
    assert.equal(d.duration_days, 2);
    assert.equal(d.reward_credits, 15);
    assert.equal(d.tester_announcement, undefined, 'tester_announcement must not be in copy draft');

    // Old create_mission_v2 signature still works untouched
    const v2Res = await db.query(`
      select public.create_mission_v2('${camp2Id}'::uuid, 'Normal V2', 'Desc V2', 3, 2, 10, 'once') as r;
    `);
    assert.equal(v2Res.rows[0].r.success, true);
  });
  pass('Legacy mission copy draft normalized to once mode without tester_announcement, and create_mission_v2 preserved');

  // -------------------------------------------------------------------------
  // 3. Seed Mission A on camp1, join tester1 (completed) & tester2 (no report),
  //    plus pending report from tester1 before expiration
  // -------------------------------------------------------------------------
  let missionAId;
  await asUser(ownerId, async () => {
    const res = await db.query(`
      select public.create_mission_v2('${camp1Id}'::uuid, 'Mission A', 'Desc A', 2, 3, 20, 'once') as r;
    `);
    missionAId = res.rows[0].r.mission_id;
  });

  // Cannot copy while A is open
  await asUser(ownerId, async () => {
    await assert.rejects(
      () => db.query(`select public.get_mission_copy_draft('${missionAId}'::uuid, '${camp1Id}'::uuid)`),
      /SOURCE_MISSION_NOT_EXPIRED/
    );
  });

  // Tester 1 joins A and submits a report; Tester 2 joins A WITHOUT submitting any report (A becomes 'full')
  await asUser(tester1Id, async () => {
    const j1 = await db.query(`select public.join_mission('${missionAId}'::uuid) as r;`);
    assert.equal(j1.rows[0].r.success, true);
  });
  await asUser(tester2Id, async () => {
    const j2 = await db.query(`select public.join_mission('${missionAId}'::uuid) as r;`);
    assert.equal(j2.rows[0].r.status, 'full');
  });

  let reportAId;
  await asUser(tester1Id, async () => {
    const sub = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${camp1Id}'::uuid, 'Report on A', 5,
        '99000000-1111-4111-8111-000000000001'::uuid,
        '${makeScreenshotUrl(camp1Id, tester1Id, 'a1')}', '1111222233334444',
        null, '${missionAId}'::uuid, null, null
      ) as r;
    `);
    reportAId = sub.rows[0].r.report_id;
  });

  // Cannot copy while A is 'full' and still unexpired (ends_at > now)
  await asUser(ownerId, async () => {
    await assert.rejects(
      () => db.query(`select public.get_mission_copy_draft('${missionAId}'::uuid, '${camp1Id}'::uuid)`),
      /SOURCE_MISSION_NOT_EXPIRED/
    );
  });
  pass('Active open and unexpired full source missions strictly rejected for copy');

  // Fast-forward A past ends_at WITHOUT running sync_expired_missions() (unsynced expiration!)
  await db.exec(`update public.missions set ends_at = now() - interval '1 hour' where id = '${missionAId}';`);

  // Check get_campaign_mission returns effective status 'expired' even before sync_expired_missions
  await asUser(ownerId, async () => {
    const cm = await db.query(`select public.get_campaign_mission('${camp1Id}'::uuid) as r;`);
    assert.equal(cm.rows[0].r.mission.status, 'expired');
    assert.ok(cm.rows[0].r.server_now);
  });
  pass('Unsynced expired mission reports effective status=expired in get_campaign_mission');

  // -------------------------------------------------------------------------
  // 4. Permission, ownership, campaign mismatch, stopped, deleted, credit checks
  // -------------------------------------------------------------------------
  await assert.rejects(
    () => db.query(`select public.get_mission_copy_draft('${missionAId}'::uuid)`),
    /NOT_AUTHENTICATED/
  );
  await asUser(tester1Id, async () => {
    await assert.rejects(
      () => db.query(`select public.get_mission_copy_draft('${missionAId}'::uuid)`),
      /PERMISSION_DENIED/
    );
    await assert.rejects(
      () => db.query(`select public.create_mission_from_source('${missionAId}'::uuid, '${camp1Id}'::uuid, 'Hack', 'Desc', 2, 2, 10, 'once', false, 'op-hack')`),
      /PERMISSION_DENIED/
    );
  });
  await asUser(ownerId, async () => {
    await assert.rejects(
      () => db.query(`select public.get_mission_copy_draft('${missionAId}'::uuid, '${camp2Id}'::uuid)`),
      /SOURCE_MISSION_CAMPAIGN_MISMATCH/
    );
    await assert.rejects(
      () => db.query(`select public.create_mission_from_source('${missionAId}'::uuid, '${camp2Id}'::uuid, 'Mismatch', 'Desc', 2, 2, 10, 'once', false, 'op-mismatch')`),
      /SOURCE_MISSION_CAMPAIGN_MISMATCH/
    );
  });

  // Stopped no credits & paused source checks
  const stoppedMissionId = '99000000-0000-4000-8000-000000000002';
  const pausedActiveMissionId = '99000000-0000-4000-8000-000000000003';
  const pausedExpiredMissionId = '99000000-0000-4000-8000-000000000004';
  const lowExpiredMissionId = '99000000-0000-4000-8000-000000000005';
  const campPaused2Id = 'cccccccc-4444-4ccc-8444-cccccccccccc';
  await db.exec(`
    insert into public.user_slots (user_id, slot_number, expires_at) values
      ('${ownerId}', 4, now() + interval '30 days');
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values
      ('${campPaused2Id}', '${ownerId}', 'com.copy.paused2', 'Paused App 2', 'open', 4);
    insert into public.missions (id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits, fee_credits, status, ends_at, reward_mode) values
      ('${stoppedMissionId}', '${ownerId}', '${camp1Id}', 'Stopped', 'D', 3, 2, 10, 100, 'stopped_no_credits', now() - interval '1 day', 'once'),
      ('${pausedActiveMissionId}', '${ownerId}', '${campPausedId}', 'PausedActive', 'D', 3, 2, 10, 100, 'paused_by_creator', now() + interval '2 days', 'once'),
      ('${pausedExpiredMissionId}', '${ownerId}', '${campPaused2Id}', 'PausedExpiredUnsynced', 'D', 3, 2, 10, 100, 'paused_by_creator', now() - interval '1 hour', 'once'),
      ('${lowExpiredMissionId}', '${lowCreditOwnerId}', '${campLowCreditId}', 'LowExpired', 'D', 3, 2, 10, 100, 'expired', now() - interval '1 hour', 'once');
  `);
  await asUser(ownerId, async () => {
    await assert.rejects(
      () => db.query(`select public.get_mission_copy_draft('${stoppedMissionId}'::uuid)`),
      /SOURCE_MISSION_STOPPED_NO_CREDITS/
    );
    await assert.rejects(
      () => db.query(`select public.get_mission_copy_draft('${pausedActiveMissionId}'::uuid)`),
      /CAMPAIGN_NOT_OPEN/
    );
    // Paused mission whose ends_at <= now() on an eligible open app CAN be copied!
    const pausedExpDraft = (await db.query(`select public.get_mission_copy_draft('${pausedExpiredMissionId}'::uuid, '${campPaused2Id}'::uuid) as r;`)).rows[0].r;
    assert.equal(pausedExpDraft.title, 'PausedExpiredUnsynced');
    const copyPausedExp = (await db.query(`
      select public.create_mission_from_source(
        '${pausedExpiredMissionId}'::uuid, '${campPaused2Id}'::uuid, 'From Paused Expired', 'D',
        2, 1, 10, 'once', false, 'op-paused-exp'
      ) as r;
    `)).rows[0].r;
    assert.equal(copyPausedExp.success, true);
    assert.equal((await db.query(`select status from public.missions where id = '${pausedExpiredMissionId}'`)).rows[0].status, 'expired');
  });
  await asUser(lowCreditOwnerId, async () => {
    const countBefore = Number((await db.query(`select count(*) c from public.missions where campaign_id = '${campLowCreditId}'`)).rows[0].c);
    await assert.rejects(
      () => db.query(`select public.create_mission_from_source('${lowExpiredMissionId}'::uuid, '${campLowCreditId}'::uuid, 'Low', 'D', 3, 2, 10, 'once', false, 'op-low-1')`),
      /INSUFFICIENT_MIN_CREDITS/
    );
    const countAfter = Number((await db.query(`select count(*) c from public.missions where campaign_id = '${campLowCreditId}'`)).rows[0].c);
    assert.equal(countAfter, countBefore);
  });
  // Top up to 310 (as admin/superuser), still less than cost (50*3 + 10*20 = 350)
  await db.exec(`update public.profiles set credits = 310 where id = '${lowCreditOwnerId}';`);
  await asUser(lowCreditOwnerId, async () => {
    await assert.rejects(
      () => db.query(`select public.create_mission_from_source('${lowExpiredMissionId}'::uuid, '${campLowCreditId}'::uuid, 'Low2', 'D', 10, 3, 20, 'once', false, 'op-low-2')`),
      /INSUFFICIENT_CREDITS/
    );
    const balAfter = Number((await db.query(`select credits from public.profiles where id = '${lowCreditOwnerId}'`)).rows[0].credits);
    assert.equal(balAfter, 310);
  });
  pass('Ownership, campaign mismatch, stopped_no_credits, paused app, and insufficient credit guards enforced without creating missions or deducting credits');

  // -------------------------------------------------------------------------
  // 5. Copy A -> B (block_previous_participants = true, daily mode) + idempotent retry
  // -------------------------------------------------------------------------
  let missionBId;
  const opB = 'op-copy-a-to-b-001';
  const ownerBalBeforeB = Number((await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits);
  // Daily: 3 participants, 2 days, 15/day -> fee = 100, escrow = 3*2*15 = 90, total = 190
  await asUser(ownerId, async () => {
    const res1 = await db.query(`
      select public.create_mission_from_source(
        '${missionAId}'::uuid, '${camp1Id}'::uuid, 'Mission B (Daily)', 'Desc B',
        3, 2, 15, 'daily', true, '${opB}'
      ) as r;
    `);
    const r1 = res1.rows[0].r;
    assert.equal(r1.success, true);
    assert.equal(r1.is_replay, false);
    assert.equal(r1.fee, 100);
    assert.equal(r1.escrow, 90);
    assert.equal(r1.total_cost, 190);
    assert.equal(r1.reward_mode, 'daily');
    assert.equal(r1.source_mission_id, missionAId);
    assert.equal(r1.block_previous_participants, true);
    missionBId = r1.mission_id;
    assert.notEqual(missionBId, missionAId);

    // Retry with exact same client_operation_id -> must replay without double debit!
    const res2 = await db.query(`
      select public.create_mission_from_source(
        '${missionAId}'::uuid, '${camp1Id}'::uuid, 'Mission B (Daily)', 'Desc B',
        3, 2, 15, 'daily', true, '${opB}'
      ) as r;
    `);
    const r2 = res2.rows[0].r;
    assert.equal(r2.is_replay, true);
    assert.equal(r2.mission_id, missionBId);

    // Same operation_id with altered payload -> must reject
    await assert.rejects(
      () => db.query(`
        select public.create_mission_from_source(
          '${missionAId}'::uuid, '${camp1Id}'::uuid, 'Mutated Title', 'Desc B',
          3, 2, 15, 'daily', true, '${opB}'
        )
      `),
      /CLIENT_OPERATION_ID_CONFLICT/
    );
  });

  const ownerBalAfterB = Number((await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits);
  assert.equal(ownerBalAfterB, ownerBalBeforeB - 190, 'Credits deducted strictly once across retry');

  // Edit mission B (changing title, description, participants, days, reward, mode) then replay original opB
  await asUser(ownerId, async () => {
    const preview = (await db.query(`
      select public.preview_edit_mission('${missionBId}'::uuid, 'once', 4, 3, 20) as r;
    `)).rows[0].r;
    assert.equal(preview.can_edit_terms, true);
    const editRes = (await db.query(`
      select public.edit_mission(
        '${missionBId}'::uuid,
        'Mission B Edited',
        'Edited Desc',
        'once',
        4,
        3,
        20,
        null,
        ${preview.version},
        ${preview.total_diff}
      ) as r;
    `)).rows[0].r;
    assert.equal(editRes.success, true);

    // Exact original payload replay with opB MUST still succeed using immutable request fingerprint
    const replayAfterEdit = (await db.query(`
      select public.create_mission_from_source(
        '${missionAId}'::uuid, '${camp1Id}'::uuid, 'Mission B (Daily)', 'Desc B',
        3, 2, 15, 'daily', true, '${opB}'
      ) as r;
    `)).rows[0].r;
    assert.equal(replayAfterEdit.is_replay, true);
    assert.equal(replayAfterEdit.mission_id, missionBId);

    // Different payload (even matching edited title) with same opB MUST still reject
    await assert.rejects(
      () => db.query(`
        select public.create_mission_from_source(
          '${missionAId}'::uuid, '${camp1Id}'::uuid, 'Mission B Edited', 'Edited Desc',
          4, 3, 20, 'once', true, '${opB}'
        )
      `),
      /CLIENT_OPERATION_ID_CONFLICT/
    );

    // Restore original B terms so downstream tests see expected 90 daily escrow
    const previewRestore = (await db.query(`
      select public.preview_edit_mission('${missionBId}'::uuid, 'daily', 3, 2, 15) as r;
    `)).rows[0].r;
    await db.query(`
      select public.edit_mission(
        '${missionBId}'::uuid,
        'Mission B (Daily)',
        'Desc B',
        'daily',
        3,
        2,
        15,
        null,
        ${previewRestore.version},
        ${previewRestore.total_diff}
      )
    `);
  });
  pass('Immutable request fingerprint: replay succeeds even after edit_mission mutates mission, while mutated payload with same opId still rejects');

  const missionBRow = (await db.query(`select * from public.missions where id = '${missionBId}'`)).rows[0];
  assert.equal(missionBRow.participant_count, 0);
  assert.equal(missionBRow.status, 'open');
  assert.equal((await db.query(`select status from public.missions where id = '${missionAId}'`)).rows[0].status, 'expired');
  pass('Copied A -> B in daily mode with idempotent retry (single deduction, single mission) and swept unsynced A to expired');

  // -------------------------------------------------------------------------
  // 6. Source pending report on A still approves and pays from A's escrow!
  // -------------------------------------------------------------------------
  const ownerBalBeforeApproveA = Number((await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits);
  await asUser(ownerId, async () => {
    const appA = await db.query(`select public.approve_tester_report('${reportAId}'::uuid, 0) as r;`);
    assert.equal(appA.rows[0].r.success, true);
  });
  const missionAAfterApprove = (await db.query(`select * from public.missions where id = '${missionAId}'`)).rows[0];
  const ownerBalAfterApproveA = Number((await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits);
  assert.equal(missionAAfterApprove.escrow_remaining_credits, 0, 'Expired A settled tester1 (20) and auto-refunded unused 20');
  assert.equal(missionAAfterApprove.escrow_refunded, true);
  assert.ok(ownerBalAfterApproveA >= ownerBalBeforeApproveA + 20, 'Owner received unused 20 escrow refund from A');
  assert.equal(missionBRow.escrow_remaining_credits, 90, 'B escrow untouched by A settlement');
  pass('Pending report on expired source A approved and paid from A escrow independently of B');

  // -------------------------------------------------------------------------
  // 7. Direct-source blocking checks in discovery, detail, and join_mission
  // -------------------------------------------------------------------------
  // Tester 1 (joined + rewarded on A) -> blocked on B
  await asUser(tester1Id, async () => {
    const list = (await db.query(`select public.get_open_missions() as r;`)).rows[0].r;
    const itemB = list.missions.find(m => m.id === missionBId);
    assert.ok(itemB);
    assert.equal(itemB.is_blocked_previous_participant, true);
    assert.equal(itemB.blocked_user_ids, undefined, 'Must never expose source user list');

    const detB = (await db.query(`select public.get_campaign_mission('${camp1Id}'::uuid, '${missionBId}'::uuid) as r;`)).rows[0].r.mission;
    assert.equal(detB.is_blocked_previous_participant, true);

    const notifBefore = Number((await db.query(`select count(*) c from public.notifications where user_id = '${ownerId}'`)).rows[0].c);
    await assert.rejects(
      () => db.query(`select public.join_mission('${missionBId}'::uuid)`),
      /MISSION_PREVIOUS_PARTICIPANT_BLOCKED/
    );
    const notifAfter = Number((await db.query(`select count(*) c from public.notifications where user_id = '${ownerId}'`)).rows[0].c);
    assert.equal(notifAfter, notifBefore, 'Blocked join must not send notification');
    assert.equal((await db.query(`select count(*) c from public.mission_participants where mission_id = '${missionBId}'`)).rows[0].c, 0);
  });

  // Tester 2 (joined A without report/reward) -> STILL blocked on B!
  await asUser(tester2Id, async () => {
    const detB = (await db.query(`select public.get_campaign_mission('${camp1Id}'::uuid, '${missionBId}'::uuid) as r;`)).rows[0].r.mission;
    assert.equal(detB.is_blocked_previous_participant, true);
    await assert.rejects(
      () => db.query(`select public.join_mission('${missionBId}'::uuid)`),
      /MISSION_PREVIOUS_PARTICIPANT_BLOCKED/
    );
  });

  // Tester 1 (blocked on B) can STILL submit ordinary app_test on camp1 AND join mission on camp2!
  await asUser(tester1Id, async () => {
    const ord = await db.query(`
      select public._submit_tester_report_core(
        'app_test', '${camp1Id}'::uuid, 'Ordinary test while blocked on mission B', 5,
        '99000000-1111-4111-8111-000000000002'::uuid,
        '${makeScreenshotUrl(camp1Id, tester1Id, 'ord1')}', '2222333344445555',
        null, null, null, null
      ) as r;
    `);
    assert.equal(ord.rows[0].r.success, true);

    const camp2MissionId = (await db.query(`select public.get_campaign_mission('${camp2Id}'::uuid) as r;`)).rows[0].r.mission.id;
    const joinOtherApp = await db.query(`select public.join_mission('${camp2MissionId}'::uuid) as r;`);
    assert.equal(joinOtherApp.rows[0].r.success, true);
  });
  pass('Tester 1 (rewarded) and Tester 2 (joined without report) blocked on B without writes/notifications, while ordinary test and other app mission remain independent');

  // Soft-delete A AFTER B is open -> Tester 1 & 2 STILL blocked on B!
  await asUser(ownerId, async () => {
    const delA = await db.query(`select public.delete_mission('${missionAId}'::uuid) as r;`);
    assert.equal(delA.rows[0].r.success, true);
  });
  await asUser(tester1Id, async () => {
    await assert.rejects(
      () => db.query(`select public.join_mission('${missionBId}'::uuid)`),
      /MISSION_PREVIOUS_PARTICIPANT_BLOCKED/
    );
  });
  // Deleted A cannot be copied again
  await asUser(ownerId, async () => {
    await assert.rejects(
      () => db.query(`select public.get_mission_copy_draft('${missionAId}'::uuid)`),
      /SOURCE_MISSION_DELETED/
    );
  });
  pass('Soft-deleting A preserves direct-source block on B and prevents copying deleted A');

  // Tester 3 (did NOT join A) joins B and submits report on B
  await asUser(tester3Id, async () => {
    const detB = (await db.query(`select public.get_campaign_mission('${camp1Id}'::uuid, '${missionBId}'::uuid) as r;`)).rows[0].r.mission;
    assert.equal(detB.is_blocked_previous_participant, false);
    const j3 = await db.query(`select public.join_mission('${missionBId}'::uuid) as r;`);
    assert.equal(j3.rows[0].r.success, true);

    const subB = await db.query(`
      select public._submit_tester_report_core(
        'mission', '${camp1Id}'::uuid, 'Report on B by tester3', 5,
        '99000000-1111-4111-8111-000000000003'::uuid,
        '${makeScreenshotUrl(camp1Id, tester3Id, 'b1')}', '3333444455556666',
        null, '${missionBId}'::uuid, null, null
      ) as r;
    `);
    assert.equal(subB.rows[0].r.mission_id, missionBId);
  });
  assert.equal((await db.query(`select participant_count from public.missions where id = '${missionBId}'`)).rows[0].participant_count, 1);
  pass('Tester 3 joined B and submitted report attached strictly to B');

  // -------------------------------------------------------------------------
  // 8. Chain A -> B -> C (block on C blocks B participants ONLY, NOT A-only!)
  // -------------------------------------------------------------------------
  // Expire B (while its report can be approved or rejected; let's approve B's report first)
  const reportBId = (await db.query(`select id from public.tester_reports where mission_id = '${missionBId}' limit 1`)).rows[0].id;
  await asUser(ownerId, async () => {
    await db.query(`select public.approve_tester_report('${reportBId}'::uuid, 0)`);
  });
  await db.exec(`update public.missions set status = 'expired', ends_at = now() - interval '1 hour' where id = '${missionBId}';`);
  await db.query(`select public.sync_expired_missions();`);

  let missionCId;
  await asUser(ownerId, async () => {
    const resC = await db.query(`
      select public.create_mission_from_source(
        '${missionBId}'::uuid, '${camp1Id}'::uuid, 'Mission C (Once)', 'Desc C',
        3, 2, 25, 'once', true, 'op-copy-b-to-c'
      ) as r;
    `);
    missionCId = resC.rows[0].r.mission_id;
    assert.equal(resC.rows[0].r.fee, 100);
    assert.equal(resC.rows[0].r.escrow, 75);
  });

  // Tester 3 (joined B) MUST be blocked on C
  await asUser(tester3Id, async () => {
    const detC = (await db.query(`select public.get_campaign_mission('${camp1Id}'::uuid, '${missionCId}'::uuid) as r;`)).rows[0].r.mission;
    assert.equal(detC.is_blocked_previous_participant, true);
    await assert.rejects(
      () => db.query(`select public.join_mission('${missionCId}'::uuid)`),
      /MISSION_PREVIOUS_PARTICIPANT_BLOCKED/
    );
  });

  // Tester 1 & Tester 2 (only joined A, NOT B) MUST be allowed to join C!
  await asUser(tester1Id, async () => {
    const detC = (await db.query(`select public.get_campaign_mission('${camp1Id}'::uuid, '${missionCId}'::uuid) as r;`)).rows[0].r.mission;
    assert.equal(detC.is_blocked_previous_participant, false);
    const jc1 = await db.query(`select public.join_mission('${missionCId}'::uuid) as r;`);
    assert.equal(jc1.rows[0].r.success, true);
  });
  await asUser(tester2Id, async () => {
    const jc2 = await db.query(`select public.join_mission('${missionCId}'::uuid) as r;`);
    assert.equal(jc2.rows[0].r.success, true);
  });
  pass('Chain A -> B -> C: C blocks direct B participant (tester3) while allowing A-only participants (tester1, tester2)');

  // -------------------------------------------------------------------------
  // 8b. Copying older expired A when another non-source mission B on same app is unsynced-expired
  // -------------------------------------------------------------------------
  const campSweepId = 'cccccccc-5555-4ccc-8555-cccccccccccc';
  const oldExpiredSrcId = '99000000-0000-4000-8000-000000000011';
  const unsyncedOtherBId = '99000000-0000-4000-8000-000000000012';
  await db.exec(`
    insert into public.user_slots (user_id, slot_number, expires_at) values
      ('${ownerId}', 5, now() + interval '30 days');
    insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values
      ('${campSweepId}', '${ownerId}', 'com.copy.sweep', 'Sweep App', 'open', 5);
    insert into public.missions (id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits, fee_credits, status, ends_at, reward_mode) values
      ('${oldExpiredSrcId}', '${ownerId}', '${campSweepId}', 'Old Expired A', 'D', 3, 2, 10, 100, 'expired', now() - interval '3 days', 'once'),
      ('${unsyncedOtherBId}', '${ownerId}', '${campSweepId}', 'Unsynced Paused B', 'D', 3, 2, 10, 100, 'paused_by_creator', now() - interval '5 minutes', 'once');
  `);
  await asUser(ownerId, async () => {
    const resSweep = (await db.query(`
      select public.create_mission_from_source(
        '${oldExpiredSrcId}'::uuid, '${campSweepId}'::uuid, 'Copy Old A', 'Desc',
        2, 1, 10, 'once', false, 'op-copy-sweep-other'
      ) as r;
    `)).rows[0].r;
    assert.equal(resSweep.success, true);
    assert.equal((await db.query(`select status from public.missions where id = '${unsyncedOtherBId}'`)).rows[0].status, 'expired');
  });
  pass('Copying older expired A swept another unsynced-expired non-source mission B on same app cleanly');

  // -------------------------------------------------------------------------
  // 9. Copy C -> D with block_previous_participants = false allows C participants
  // -------------------------------------------------------------------------
  await db.exec(`update public.missions set status = 'expired', ends_at = now() - interval '1 hour' where id = '${missionCId}';`);
  await db.query(`select public.sync_expired_missions();`);

  let missionDId;
  await asUser(ownerId, async () => {
    const resD = await db.query(`
      select public.create_mission_from_source(
        '${missionCId}'::uuid, '${camp1Id}'::uuid, 'Mission D (No Block)', 'Desc D',
        2, 1, 10, 'once', false, 'op-copy-c-to-d'
      ) as r;
    `);
    missionDId = resD.rows[0].r.mission_id;
  });
  await asUser(tester1Id, async () => {
    const detD = (await db.query(`select public.get_campaign_mission('${camp1Id}'::uuid, '${missionDId}'::uuid) as r;`)).rows[0].r.mission;
    assert.equal(detD.is_blocked_previous_participant, false);
    const jd1 = await db.query(`select public.join_mission('${missionDId}'::uuid) as r;`);
    assert.equal(jd1.rows[0].r.success, true);
  });
  pass('Copy with block_previous_participants=false allows previous source participants');

  // -------------------------------------------------------------------------
  // 10. Lifecycle: pause, resume, expire, refund on copied mission,
  //     deferred FK guard on standalone delete, physical campaign delete, and 099 purge
  // -------------------------------------------------------------------------
  await asUser(ownerId, async () => {
    const pD = await db.query(`select public.pause_mission('${missionDId}'::uuid) as r;`);
    assert.equal(pD.rows[0].r.status, 'paused_by_creator');
    const rD = await db.query(`select public.resume_mission('${missionDId}'::uuid) as r;`);
    assert.equal(rD.rows[0].r.status, 'open');
  });

  // Standalone physical delete of A while B still exists must fail deferred FK check at commit
  await assert.rejects(
    async () => {
      await db.exec('begin');
      try {
        await db.exec(`delete from public.missions where id = '${missionAId}';`);
        await db.exec('commit');
      } catch (e) {
        await db.exec('rollback');
        throw e;
      }
    },
    /missions_source_mission_id_fkey/
  );
  pass('Deferred FK blocks standalone physical deletion of referenced source mission');

  // Expire & delete D so all missions on camp1 are settled/deleted, then delete camp1 physically
  await db.exec(`update public.missions set status = 'expired', ends_at = now() - interval '1 hour' where id = '${missionDId}';`);
  await db.query(`select public.sync_expired_missions();`);
  await asUser(ownerId, async () => {
    await db.query(`select public.delete_mission('${missionBId}'::uuid)`);
    await db.query(`select public.delete_mission('${missionCId}'::uuid)`);
    await db.query(`select public.delete_mission('${missionDId}'::uuid)`);
  });

  // Physical campaign delete (cascade deletes A -> B -> C -> D together)
  await db.exec(`delete from public.campaigns where id = '${camp1Id}';`);
  const remainingCamp1Missions = Number((await db.query(`select count(*) c from public.missions where campaign_id = '${camp1Id}'`)).rows[0].c);
  assert.equal(remainingCamp1Missions, 0);
  pass('Physical deletion of campaign with A -> B -> C -> D chain succeeded via deferred FK');

  // Migration 099 purge_zero_credit_accounts with a chained mission copy A -> B
  const purgeOwnerId = '77777777-aaaa-4777-8aaa-777777777777';
  const purgeCampId = '77777777-bbbb-4777-8bbb-777777777777';
  const purgeM1Id = '77777777-cccc-4777-8ccc-777777777771';
  const purgeM2Id = '77777777-cccc-4777-8ccc-777777777772';
  await db.exec(`
    insert into auth.users (id, email) values ('${purgeOwnerId}', 'purge@copy.test');
    update public.credit_logs
    set created_at = now() - interval '45 days'
    where user_id = '${purgeOwnerId}';
    update public.profiles
    set credits = 0, zero_credits_since = now() - interval '35 days', created_at = now() - interval '45 days', display_name = 'Purge Owner', role = 'member'
    where id = '${purgeOwnerId}';
    insert into public.campaigns (id, owner_id, package_name, app_name, status)
    values ('${purgeCampId}', '${purgeOwnerId}', 'com.purge.chain', 'Purge Chain', 'open');
    insert into public.missions (
      id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits,
      fee_credits, status, ends_at, reward_mode, escrow_total_credits, escrow_remaining_credits, escrow_refunded
    ) values (
      '${purgeM1Id}', '${purgeOwnerId}', '${purgeCampId}', 'Purge M1', 'D', 2, 1, 10,
      50, 'expired', now() - interval '40 days', 'once', 20, 0, true
    );
    insert into public.missions (
      id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits,
      fee_credits, status, ends_at, reward_mode, escrow_total_credits, escrow_remaining_credits, escrow_refunded,
      source_mission_id, block_previous_participants
    ) values (
      '${purgeM2Id}', '${purgeOwnerId}', '${purgeCampId}', 'Purge M2', 'D', 2, 1, 10,
      50, 'expired', now() - interval '38 days', 'once', 20, 0, true,
      '${purgeM1Id}', true
    );
  `);

  const purgeRes = await asServiceRole(async () => {
    return (await db.query(`select public.purge_zero_credit_accounts(30, false, 10) as r;`)).rows[0].r;
  });
  assert.equal(purgeRes.deleted, 1, `Expected 1 purged account, got errors: ${JSON.stringify(purgeRes.errors)}`);
  assert.equal((await db.query(`select count(*) c from public.missions where id in ('${purgeM1Id}', '${purgeM2Id}')`)).rows[0].c, 0);
  pass('Migration 099 purge_zero_credit_accounts purged account with copied mission chain cleanly');

  console.log(`\n=== ALL ${passed} MISSION COPY & BLOCK TESTS PASSED 100% ===`);
} finally {
  await db.close();
}
