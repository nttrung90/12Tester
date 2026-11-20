import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

try {
  console.log('=== LOADING ALL MIGRATIONS IN ORDER ===');
  const migrations = readdirSync(new URL('../migrations/', import.meta.url))
    .filter(n => /^\d{3}_.*\.sql$/.test(n))
    .sort();
  for (const name of migrations) {
    await db.exec(read('supabase/migrations/' + name));
  }
  console.log(`Loaded ${migrations.length} migrations successfully.\n`);

  // Helper to query function defs and grants
  async function getFunctionMetadata(funcNames) {
    const res = await db.query(`
      select
        p.proname as name,
        pg_get_function_identity_arguments(p.oid) as args,
        pg_get_functiondef(p.oid) as definition,
        array_agg(distinct (case when x.grantee = 0 then 'PUBLIC' else pg_get_userbyid(x.grantee) end) || ':' || x.privilege_type order by (case when x.grantee = 0 then 'PUBLIC' else pg_get_userbyid(x.grantee) end) || ':' || x.privilege_type) as grants
      from pg_proc p
      cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) x
      where p.pronamespace = 'public'::regnamespace
        and p.proname = any($1::text[])
        and (x.grantee = 0 or pg_get_userbyid(x.grantee) in ('anon', 'authenticated', 'service_role'))
      group by p.oid, p.proname
      order by p.proname, args;
    `, [funcNames]);
    return res.rows;
  }

  // --------------------------------------------------------------------------
  // TEST 1: All four flags default to false for android_client
  // --------------------------------------------------------------------------
  console.log('--- TEST 1: Default Flag Values ---');
  const defaultRow = (await db.query(`
    select
      id,
      egress_sync_home_feed_v2_enabled,
      egress_sync_dashboard_v2_enabled,
      egress_sync_campaign_detail_v2_enabled,
      egress_candidates_v2_enabled
    from public.app_configs
    where id = 'android_client'
  `)).rows[0];

  assert.ok(defaultRow, 'android_client row must exist');
  assert.equal(defaultRow.egress_sync_home_feed_v2_enabled, false, 'egress_sync_home_feed_v2_enabled must default false');
  assert.equal(defaultRow.egress_sync_dashboard_v2_enabled, false, 'egress_sync_dashboard_v2_enabled must default false');
  assert.equal(defaultRow.egress_sync_campaign_detail_v2_enabled, false, 'egress_sync_campaign_detail_v2_enabled must default false');
  assert.equal(defaultRow.egress_candidates_v2_enabled, false, 'egress_candidates_v2_enabled must default false');
  console.log('PASS: All four flags default false for android_client');

  // --------------------------------------------------------------------------
  // TEST 2: Enable one flag, rerun migration 085, verify true preserved
  // --------------------------------------------------------------------------
  console.log('--- TEST 2: Reapply Migration Idempotency & Value Preservation ---');
  await db.query(`
    update public.app_configs
    set egress_sync_home_feed_v2_enabled = true
    where id = 'android_client'
  `);

  // Snapshot function definitions and grants prior to rerun
  const legacyFuncs = ['get_home_feed', 'get_my_dashboard', 'get_campaign_detail'];
  const allTargetFuncs = [...legacyFuncs, 'get_home_feed_sync_v2', 'get_my_dashboard_sync_v2', 'get_campaign_detail_sync_v2', 'get_next_campaign_candidates_v2', '_sync_block'];
  const beforeMetadata = await getFunctionMetadata(allTargetFuncs);

  // Rerun migration 085
  await db.exec(read('supabase/migrations/085_egress_optimization_sync_v2.sql'));

  const rowAfterReapply = (await db.query(`
    select
      egress_sync_home_feed_v2_enabled,
      egress_sync_dashboard_v2_enabled,
      egress_sync_campaign_detail_v2_enabled,
      egress_candidates_v2_enabled
    from public.app_configs
    where id = 'android_client'
  `)).rows[0];

  assert.equal(rowAfterReapply.egress_sync_home_feed_v2_enabled, true, 'egress_sync_home_feed_v2_enabled must preserve true after reapply');
  assert.equal(rowAfterReapply.egress_sync_dashboard_v2_enabled, false, 'egress_sync_dashboard_v2_enabled must remain false');
  assert.equal(rowAfterReapply.egress_sync_campaign_detail_v2_enabled, false, 'egress_sync_campaign_detail_v2_enabled must remain false');
  assert.equal(rowAfterReapply.egress_candidates_v2_enabled, false, 'egress_candidates_v2_enabled must remain false');
  console.log('PASS: True flag value preserved after migration 085 reapplication');

  // --------------------------------------------------------------------------
  // TEST 3: Individual toggles are independent
  // --------------------------------------------------------------------------
  console.log('--- TEST 3: Flag Independence ---');
  // Enable dashboard flag
  await db.query(`update public.app_configs set egress_sync_dashboard_v2_enabled = true where id = 'android_client'`);
  let check = (await db.query(`select * from public.app_configs where id = 'android_client'`)).rows[0];
  assert.equal(check.egress_sync_home_feed_v2_enabled, true);
  assert.equal(check.egress_sync_dashboard_v2_enabled, true);
  assert.equal(check.egress_sync_campaign_detail_v2_enabled, false);
  assert.equal(check.egress_candidates_v2_enabled, false);

  // Enable campaign detail flag, disable home feed flag
  await db.query(`
    update public.app_configs
    set egress_sync_campaign_detail_v2_enabled = true,
        egress_sync_home_feed_v2_enabled = false
    where id = 'android_client'
  `);
  check = (await db.query(`select * from public.app_configs where id = 'android_client'`)).rows[0];
  assert.equal(check.egress_sync_home_feed_v2_enabled, false);
  assert.equal(check.egress_sync_dashboard_v2_enabled, true);
  assert.equal(check.egress_sync_campaign_detail_v2_enabled, true);
  assert.equal(check.egress_candidates_v2_enabled, false);

  // Enable candidates flag
  await db.query(`update public.app_configs set egress_candidates_v2_enabled = true where id = 'android_client'`);
  check = (await db.query(`select * from public.app_configs where id = 'android_client'`)).rows[0];
  assert.equal(check.egress_sync_home_feed_v2_enabled, false);
  assert.equal(check.egress_sync_dashboard_v2_enabled, true);
  assert.equal(check.egress_sync_campaign_detail_v2_enabled, true);
  assert.equal(check.egress_candidates_v2_enabled, true);
  console.log('PASS: Individual flags toggle independently without cross-interference');

  // --------------------------------------------------------------------------
  // TEST 4: Disable all rollback
  // --------------------------------------------------------------------------
  console.log('--- TEST 4: Disable All Rollback ---');
  await db.query(`
    update public.app_configs
    set egress_sync_home_feed_v2_enabled = false,
        egress_sync_dashboard_v2_enabled = false,
        egress_sync_campaign_detail_v2_enabled = false,
        egress_candidates_v2_enabled = false
    where id = 'android_client'
  `);

  const rollbackRow = (await db.query(`select * from public.app_configs where id = 'android_client'`)).rows[0];
  assert.equal(rollbackRow.egress_sync_home_feed_v2_enabled, false);
  assert.equal(rollbackRow.egress_sync_dashboard_v2_enabled, false);
  assert.equal(rollbackRow.egress_sync_campaign_detail_v2_enabled, false);
  assert.equal(rollbackRow.egress_candidates_v2_enabled, false);
  console.log('PASS: Rollback resets all 4 flags to false');

  // --------------------------------------------------------------------------
  // TEST 5: Legacy function signatures/definitions and execute grants unchanged
  // --------------------------------------------------------------------------
  console.log('--- TEST 5: Legacy RPC & Function Metadata Stability ---');
  const afterMetadata = await getFunctionMetadata(allTargetFuncs);
  assert.deepEqual(afterMetadata, beforeMetadata, 'Function signatures, definitions, and grants must remain completely unchanged across migration reapplications');

  // Verify helper function _sync_block access
  const syncBlockMeta = afterMetadata.find(m => m.name === '_sync_block');
  assert.ok(syncBlockMeta, '_sync_block must exist');
  assert.ok(!syncBlockMeta.grants.some(g => g.startsWith('PUBLIC:')), '_sync_block must have NO grants to PUBLIC');
  assert.ok(!syncBlockMeta.grants.some(g => g.startsWith('anon:')), '_sync_block must have NO grants to anon');
  assert.ok(syncBlockMeta.grants.some(g => g.startsWith('authenticated:EXECUTE')), '_sync_block granted to authenticated');
  assert.ok(syncBlockMeta.grants.some(g => g.startsWith('service_role:EXECUTE')), '_sync_block granted to service_role');
  console.log('PASS: Legacy functions and grants unchanged; _sync_block protected');

  // --------------------------------------------------------------------------
  // TEST 6: Anon read of public app configuration respects existing policies
  // --------------------------------------------------------------------------
  console.log('--- TEST 6: Anon Read & RLS Policies on app_configs ---');
  await db.exec('begin');
  try {
    await db.exec(`
      set local "request.jwt.claim.sub" = '';
      set local "request.jwt.claims" = '{"role": "anon"}';
      set local role = 'anon';
    `);

    const anonRead = (await db.query(`
      select
        id,
        min_version_code,
        latest_version_code,
        egress_sync_home_feed_v2_enabled,
        egress_sync_dashboard_v2_enabled,
        egress_sync_campaign_detail_v2_enabled,
        egress_candidates_v2_enabled
      from public.app_configs
      where id = 'android_client'
    `)).rows[0];

    assert.ok(anonRead, 'Anon must be able to read app_configs');
    assert.equal(anonRead.id, 'android_client');
    assert.equal(anonRead.egress_sync_home_feed_v2_enabled, false);
    assert.equal(anonRead.egress_sync_dashboard_v2_enabled, false);
    assert.equal(anonRead.egress_sync_campaign_detail_v2_enabled, false);
    assert.equal(anonRead.egress_candidates_v2_enabled, false);

    // Verify anon cannot mutate flags (RLS / permission violation)
    let anonMutationFailed = false;
    try {
      await db.query(`
        update public.app_configs
        set egress_sync_home_feed_v2_enabled = true
        where id = 'android_client'
      `);
      const affected = (await db.query(`select egress_sync_home_feed_v2_enabled from public.app_configs where id = 'android_client'`)).rows[0];
      if (affected.egress_sync_home_feed_v2_enabled !== true) {
        anonMutationFailed = true;
      }
    } catch {
      anonMutationFailed = true;
    }
    assert.ok(anonMutationFailed, 'Anon must NOT be permitted to modify app_configs');
  } finally {
    await db.exec('rollback');
  }
  console.log('PASS: Anon read succeeds and respects security policies');

  console.log('\n======================================================');
  console.log('ALL BACKEND FLAGS V2 TESTS PASSED SUCCESSFULLY!');
  console.log('======================================================\n');
} finally {
  await db.close();
}
