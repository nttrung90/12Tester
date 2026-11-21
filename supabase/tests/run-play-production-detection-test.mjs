import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();
const adminId = 'a1000000-0000-0000-0000-000000000001';
const lockedAdminId = 'a1000000-0000-0000-0000-000000000002';
const ownerId = 'c1000000-0000-0000-0000-000000000001';
const owner2Id = 'c1000000-0000-0000-0000-000000000003';
const testerId = 'c1000000-0000-0000-0000-000000000002';

const migration102 = 'supabase/migrations/102_play_production_detection.sql';
const q = async (sql, params = []) => (await db.query(sql, params)).rows[0];

let seq = 0;

async function asRole(role, uid, action) {
  await db.exec('begin');
  try {
    if (uid) {
      await db.exec(`set local "request.jwt.claim.sub" = '${uid}';`);
    } else {
      await db.exec(`set local "request.jwt.claim.sub" = '';`);
    }
    await db.exec(`set local role = ${role};`);
    const result = await action();
    await db.exec('commit');
    return result;
  } catch (err) {
    await db.exec('rollback');
    throw err;
  }
}

const asUser = (uid, action) => asRole('authenticated', uid, action);
const asAnon = (action) => asRole('anon', null, action);
const asService = (action) => asRole('service_role', null, action);

async function insertCampaign({ status = 'open', pkgPrefix = 'com.example.play' } = {}) {
  seq += 1;
  const targetOwner = seq <= 6 ? ownerId : owner2Id;
  const slot = ((seq - 1) % 6) + 1;
  const id = `d1000000-0000-0000-0000-${String(seq).padStart(12, '0')}`;
  await db.exec(`
    insert into public.user_slots(user_id, slot_number, expires_at)
    select '${targetOwner}'::uuid, ${slot}, now() + interval '30 days'
    where ${slot} > 1
    on conflict (user_id, slot_number) do nothing;

    insert into public.campaigns(
      id, owner_id, app_name, package_name, play_url,
      status, slot_number, target_testers, target_days, start_date
    ) values (
      '${id}', '${targetOwner}', 'Play Detect App ${seq}', '${pkgPrefix}.${seq}',
      'https://play.google.com/store/apps/details?id=${pkgPrefix}.${seq}',
      '${status}', ${slot}, 12, 14,
      (now() at time zone 'Asia/Ho_Chi_Minh')::date
    );
  `);
  return id;
}

try {
  // 1. Replay 062..101, then apply 102 twice (idempotency check)
  const preMigrations = readdirSync(new URL('../migrations/', import.meta.url))
    .filter((name) => /^\d{3}_.*\.sql$/.test(name) && Number(name.slice(0, 3)) <= 101)
    .sort();
  for (const name of preMigrations) {
    await db.exec(read(`supabase/migrations/${name}`));
  }

  await db.exec(read(migration102));
  await db.exec(read(migration102));
  console.log('PASS: 062 -> 102 upgrade & idempotent rerun succeeded');

  // Verify columns & partial queue index exist
  const cols = (
    await db.query(`
      select column_name
      from information_schema.columns
      where table_schema = 'public' and table_name = 'campaigns'
        and column_name like 'play_%'
      order by column_name
    `)
  ).rows.map((r) => r.column_name);
  assert.deepEqual(cols, [
    'play_check_evidence',
    'play_checked_at',
    'play_classification_locked',
    'play_last_prod_seen_at',
    'play_prod_confirm_streak',
    'play_production_detected',
    'play_released_date',
    'play_store_state',
    'play_url',
  ]);

  const idx = await q(`
    select indexname
    from pg_indexes
    where schemaname = 'public' and tablename = 'campaigns'
      and indexname = 'idx_campaigns_play_check_queue'
  `);
  assert.equal(idx.indexname, 'idx_campaigns_play_check_queue');

  // Seed users
  await db.exec(`
    insert into auth.users(id, email) values
      ('${adminId}', 'admin@test.local'),
      ('${lockedAdminId}', 'locked-admin@test.local'),
      ('${ownerId}', 'owner@test.local'),
      ('${owner2Id}', 'owner2@test.local'),
      ('${testerId}', 'tester@test.local');

    update public.profiles set role = 'admin', is_locked = false, credits = 10000 where id = '${adminId}';
    update public.profiles set role = 'admin', is_locked = true, credits = 10000 where id = '${lockedAdminId}';
    update public.profiles set role = 'member', is_locked = false, credits = 10000 where id in ('${ownerId}', '${owner2Id}', '${testerId}');
  `);

  // 2. Guard trigger: block client INSERT/UPDATE tampering on play_* while allowing normal edits
  const guardTargetId = await insertCampaign({ status: 'open' });

  await assert.rejects(
    asUser(ownerId, () =>
      db.query(
        `insert into public.campaigns(
          owner_id, app_name, package_name, play_url, play_production_detected, slot_number
        ) values ($1, 'Tamper', 'com.tamper.insert', 'https://play.google.com/store/apps/details?id=com.tamper.insert', true, 2)`,
        [ownerId],
      ),
    ),
    /PLAY_DETECTION_METADATA_IMMUTABLE/,
  );

  for (const [col, valSql] of [
    ['play_store_state', `'production'`],
    ['play_production_detected', 'true'],
    ['play_released_date', `'Oct 31, 2026'`],
    ['play_checked_at', 'now()'],
    ['play_prod_confirm_streak', '2'],
    ['play_last_prod_seen_at', 'now()'],
    ['play_classification_locked', 'true'],
    ['play_check_evidence', `'{"hack":true}'::jsonb`],
  ]) {
    await assert.rejects(
      asUser(ownerId, () =>
        db.query(`update public.campaigns set ${col} = ${valSql} where id = $1`, [guardTargetId]),
      ),
      /PLAY_DETECTION_METADATA_IMMUTABLE/,
    );
  }

  // Normal owner edit must still work
  await asUser(ownerId, () =>
    db.query(`update public.campaigns set description = 'Updated cleanly' where id = $1`, [guardTargetId]),
  );

  // Anon & authenticated must NOT be able to execute system_* RPCs
  await assert.rejects(
    asAnon(() => db.query(`select public.system_list_campaigns_for_play_check(10, 6, 24)`)),
    /permission denied/i,
  );
  await assert.rejects(
    asUser(ownerId, () =>
      db.query(`select public.system_record_play_store_check($1, 'production')`, [guardTargetId]),
    ),
    /permission denied/i,
  );
  console.log('PASS: guard trigger & system RPC ACLs verified');

  // 3. Hysteresis: 2 consecutive production hits >= 120 min apart, unknown preservation, reset
  const campHyst = await insertCampaign({ status: 'open' });

  // Sync V2 tokens before detection
  const tokensBefore = await asUser(testerId, async () => {
    const { sync } = await q(`select public.get_home_feed_sync_v2('{}'::jsonb) as sync`);
    return Object.fromEntries(Object.entries(sync.blocks).map(([k, b]) => [k, b.token]));
  });

  // Hit 1: production -> streak=1, detected=false, state stays 'unknown'
  const hit1 = (
    await asService(() =>
      q(
        `select public.system_record_play_store_check($1, 'production', 'Nov 02, 2026', '{"step":"GET","avail":2}'::jsonb) as res`,
        [campHyst],
      ),
    )
  ).res;
  assert.equal(hit1.play_prod_confirm_streak, 1);
  assert.equal(hit1.play_production_detected, false);
  assert.equal(hit1.play_store_state, 'unknown');
  assert.equal(hit1.play_released_date, 'Nov 02, 2026');

  // Unknown check during streak=1 must NOT reset streak!
  const unk1 = (
    await asService(() =>
      q(
        `select public.system_record_play_store_check($1, 'unknown', null, '{"step":"HEAD","status":429}'::jsonb) as res`,
        [campHyst],
      ),
    )
  ).res;
  assert.equal(unk1.play_prod_confirm_streak, 1);
  assert.equal(unk1.play_production_detected, false);

  // Hit 2 too soon (< 120 minutes) -> streak MUST NOT increment!
  await db.query(
    `update public.campaigns set play_last_prod_seen_at = clock_timestamp() - interval '60 minutes' where id = $1`,
    [campHyst],
  );
  const hitTooSoon = (
    await asService(() =>
      q(
        `select public.system_record_play_store_check($1, 'production', 'Nov 02, 2026', '{"step":"GET","too_soon":true}'::jsonb) as res`,
        [campHyst],
      ),
    )
  ).res;
  assert.equal(hitTooSoon.play_prod_confirm_streak, 1);
  assert.equal(hitTooSoon.play_production_detected, false);

  // Hit 2 after >= 120 minutes -> streak=2, detected=true, state='production', status STILL 'open'!
  await db.query(
    `update public.campaigns set play_last_prod_seen_at = clock_timestamp() - interval '125 minutes' where id = $1`,
    [campHyst],
  );
  const hit2 = (
    await asService(() =>
      q(
        `select public.system_record_play_store_check($1, 'production', 'Nov 02, 2026', '{"step":"GET","confirmed":true}'::jsonb) as res`,
        [campHyst],
      ),
    )
  ).res;
  assert.equal(hit2.play_prod_confirm_streak, 2);
  assert.equal(hit2.play_production_detected, true);
  assert.equal(hit2.play_store_state, 'production');

  const campRowAfterProd = await q(
    `select status, play_production_detected, play_store_state from public.campaigns where id = $1`,
    [campHyst],
  );
  assert.equal(campRowAfterProd.status, 'open');
  assert.equal(campRowAfterProd.play_production_detected, true);
  assert.equal(campRowAfterProd.play_store_state, 'production');

  // Unknown check after detection must NOT clear play_production_detected!
  const unk2 = (
    await asService(() =>
      q(
        `select public.system_record_play_store_check($1, 'unknown', null, '{"step":"HEAD","status":503}'::jsonb) as res`,
        [campHyst],
      ),
    )
  ).res;
  assert.equal(unk2.play_production_detected, true);
  assert.equal(unk2.play_prod_confirm_streak, 2);
  assert.equal(unk2.play_store_state, 'production');
  console.log('PASS: 2-hit >=120m hysteresis & unknown preservation verified');

  // 4. Verify _campaign_json, get_home_feed_sync_v2 block hash change, and get_next_campaign_candidates_v2
  await asUser(testerId, async () => {
    const { sync } = await q(`select public.get_home_feed_sync_v2($1::jsonb) as sync`, [
      JSON.stringify(tokensBefore),
    ]);
    assert.equal(sync.blocks.open.changed, true);
    assert.notEqual(sync.blocks.open.token, tokensBefore.open);
    const projected = sync.blocks.open.value.find((c) => c.id === campHyst);
    assert.ok(projected);
    assert.equal(projected.play_production_detected, true);
    assert.equal(projected.play_store_state, 'production');
    assert.equal(projected.play_released_date, 'Nov 02, 2026');
    assert.ok(projected.play_checked_at);
    assert.equal(projected.play_classification_locked, false);

    const { candidates } = await q(`select public.get_next_campaign_candidates_v2(null) as candidates`);
    const cand = candidates.find((item) => item.campaign.id === campHyst);
    assert.ok(cand);
    assert.equal(cand.campaign.play_production_detected, true);
    assert.equal(cand.campaign.play_store_state, 'production');
    assert.equal(typeof cand.today_tested_count, 'number');
    assert.ok(cand.stats_date);
  });
  console.log('PASS: _campaign_json, get_home_feed_sync_v2 hash invalidation & get_next_campaign_candidates_v2 verified');

  // 5. Verify reset on not_public / pre_registration / open_testing
  for (const nonProdState of ['not_public', 'pre_registration', 'open_testing']) {
    const resetRes = (
      await asService(() =>
        q(
          `select public.system_record_play_store_check($1, $2, null, '{"reset":true}'::jsonb) as res`,
          [campHyst, nonProdState],
        ),
      )
    ).res;
    assert.equal(resetRes.play_store_state, nonProdState);
    assert.equal(resetRes.play_production_detected, false);
    assert.equal(resetRes.play_prod_confirm_streak, 0);
  }
  assert.equal((await q(`select status from public.campaigns where id = $1`, [campHyst])).status, 'open');
  console.log('PASS: reset on not_public/pre_registration/open_testing verified');

  // 6. Verify queue priority order (bucket 1 -> 2 -> 3 -> 4) & exclusion of paused/locked
  const campNever = await insertCampaign({ status: 'open' });
  const campStreakReady = await insertCampaign({ status: 'open' });
  const campOldNonProd = await insertCampaign({ status: 'open' });
  const campOldProd = await insertCampaign({ status: 'open' });
  const campLocked = await insertCampaign({ status: 'open' });
  const campPaused = await insertCampaign({ status: 'paused' });

  await db.exec(`
    update public.campaigns
    set play_checked_at = clock_timestamp() - interval '3 hours',
        play_prod_confirm_streak = 1,
        play_last_prod_seen_at = clock_timestamp() - interval '3 hours',
        play_production_detected = false
    where id = '${campStreakReady}';

    update public.campaigns
    set play_checked_at = clock_timestamp() - interval '8 hours',
        play_store_state = 'not_public',
        play_production_detected = false
    where id = '${campOldNonProd}';

    update public.campaigns
    set play_checked_at = clock_timestamp() - interval '30 hours',
        play_store_state = 'production',
        play_production_detected = true,
        play_prod_confirm_streak = 2
    where id = '${campOldProd}';

    update public.campaigns
    set play_checked_at = null,
        play_classification_locked = true
    where id = '${campLocked}';

    update public.campaigns
    set play_checked_at = clock_timestamp() - interval '1 hour',
        play_store_state = 'not_public'
    where id in ('${guardTargetId}', '${campHyst}');
  `);

  const queue = (
    await asService(() => q(`select public.system_list_campaigns_for_play_check(25, 6, 24) as queue`))
  ).queue;
  const queuedIds = queue.map((item) => item.id);
  assert.equal(queuedIds.includes(campLocked), false);
  assert.equal(queuedIds.includes(campPaused), false);
  assert.equal(queuedIds.includes(guardTargetId), false);
  assert.equal(queuedIds.includes(campHyst), false);
  assert.deepEqual(queuedIds, [campNever, campStreakReady, campOldNonProd, campOldProd]);
  assert.deepEqual(
    queue.map((i) => i.priority_bucket),
    [1, 2, 3, 4],
  );
  console.log('PASS: system_list_campaigns_for_play_check priority ordering & filtering verified');

  // 7. Verify admin_override_campaign_play_production, classification lock, admin_action_events, & admin_search_campaigns
  await assert.rejects(
    asRole('authenticated', null, () =>
      db.query(`select public.admin_override_campaign_play_production($1, true, true, 'empty jwt sub')`, [campNever]),
    ),
    /NOT_AUTHENTICATED/,
  );
  await assert.rejects(
    asUser(ownerId, () =>
      db.query(`select public.admin_override_campaign_play_production($1, true, true, 'not admin')`, [campNever]),
    ),
    /PERMISSION_DENIED/,
  );
  await assert.rejects(
    asUser(lockedAdminId, () =>
      db.query(`select public.admin_override_campaign_play_production($1, true, true, 'locked admin')`, [campNever]),
    ),
    /PERMISSION_DENIED/,
  );

  const overrideRes = (
    await asUser(adminId, () =>
      q(
        `select public.admin_override_campaign_play_production($1, true, true, 'Confirmed public on Play') as res`,
        [campNever],
      ),
    )
  ).res;
  assert.equal(overrideRes.success, true);
  assert.equal(overrideRes.campaign_id, campNever);
  assert.equal(overrideRes.play_production_detected, true);
  assert.equal(overrideRes.play_store_state, 'production');
  assert.equal(overrideRes.play_classification_locked, true);

  // Worker must NOT overwrite locked classification
  const afterLockCheck = (
    await asService(() =>
      q(
        `select public.system_record_play_store_check($1, 'not_public', null, '{"attempt":"overwrite"}'::jsonb) as res`,
        [campNever],
      ),
    )
  ).res;
  assert.equal(afterLockCheck.locked, true);
  assert.equal(afterLockCheck.updated_classification, false);
  assert.equal(afterLockCheck.play_production_detected, true);
  assert.equal(afterLockCheck.play_store_state, 'production');

  // Audit event recorded in public.admin_action_events
  const auditRow = await q(
    `select actor_id, action, target_type, target_id, reason, new_state
     from public.admin_action_events
     where target_id = $1
     order by created_at desc
     limit 1`,
    [campNever],
  );
  assert.equal(auditRow.actor_id, adminId);
  assert.equal(auditRow.action, 'override_campaign_play_production');
  assert.equal(auditRow.target_type, 'campaign');
  assert.equal(auditRow.reason, 'Confirmed public on Play');
  assert.equal(auditRow.new_state.play_classification_locked, true);

  // admin_search_campaigns returns additive play_* fields
  const adminList = (
    await asUser(adminId, () =>
      q(`select public.admin_search_campaigns(null, 'open', null, 20, null, null) as res`),
    )
  ).res;
  const adminItem = adminList.items.find((it) => it.id === campNever);
  assert.ok(adminItem);
  assert.equal(adminItem.play_production_detected, true);
  assert.equal(adminItem.play_store_state, 'production');
  assert.equal(adminItem.play_classification_locked, true);

  // Unlock & clear via admin override
  const unlockRes = (
    await asUser(adminId, () =>
      q(`select public.admin_override_campaign_play_production($1, false, false, 'False alarm') as res`, [campNever]),
    )
  ).res;
  assert.equal(unlockRes.play_production_detected, false);
  assert.equal(unlockRes.play_store_state, 'not_public');
  assert.equal(unlockRes.play_classification_locked, false);
  assert.equal((await q(`select status from public.campaigns where id = $1`, [campNever])).status, 'open');

  console.log('PASS: admin override, classification lock, audit log & admin_search_campaigns verified');
  console.log('ALL PLAY PRODUCTION DETECTION SQL TESTS PASSED');
} finally {
  await db.close();
}
