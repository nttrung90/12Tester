import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { root, read, createDb, replay, catalog, dataSnapshot, ident } from '../tools/baseline-lib.mjs';

const baseline = read('supabase/migrations/062_consolidated_baseline.sql');
const results = [];
const owner = '11111111-1111-1111-1111-111111111111';
const tester = '22222222-2222-2222-2222-222222222222';
const campaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const report = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

async function test(name, fn) {
  try {
    await fn();
    results.push({ name, passed: true });
    console.log(`PASS: ${name}`);
  } catch (error) {
    results.push({ name, passed: false, error: error.message, code: error.code });
    console.error(`FAIL: ${name}: ${error.message}`);
    if (error.where) console.error(error.where.slice(0, 900));
    if (error.internalQuery && error.internalPosition) {
      const position = Number(error.internalPosition);
      console.error(error.internalQuery.slice(Math.max(0, position - 120), position + 120));
    }
    process.exitCode = 1;
  }
}
async function seedBusinessData(db) {
  await db.exec(`
    insert into auth.users(id,email) values ('${owner}','owner@baseline.test'),('${tester}','tester@baseline.test');
    update public.profiles set credits=12345,daily_missed_streak=3,missed_streak=2 where id='${owner}';
    insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number)
      values ('${campaign}','${owner}','com.baseline.test','Baseline preservation','open',1);
    insert into public.campaign_members(campaign_id,user_id) values ('${campaign}','${tester}');
    insert into public.tester_reports(id,campaign_id,user_id,content,rating,status,screenshot_url,image_hash,client_submit_id)
      values ('${report}','${campaign}','${tester}','Keep this report',3,'approved',
        'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2Fproof.jpg?alt=media&token=fixture',
        '0123456789abcdef','cccccccc-cccc-cccc-cccc-cccccccccccc');
    insert into public.report_submission_receipts(user_id,client_submit_id,campaign_id,report_id,payload_fingerprint,response_json,provenance)
      values ('${tester}','cccccccc-cccc-cccc-cccc-cccccccccccc','${campaign}','${report}',
        'keep-exact-fingerprint','{"success":true,"rating":3,"provisional_amount":10}', 'exact_v1');
    update public.app_configs set latest_version_code=999,latest_version_name='Keep customized version',title_vi='Giữ cấu hình đang dùng' where id='android_client';
    update public.topup_packages set price_vnd=77777,is_active=false where id='credit_pack_starter_20';
    insert into public.daily_task_policies(effective_from,tests_per_active_app,operator_note)
      values (((now() at time zone 'Asia/Ho_Chi_Minh')::date+10),7,'Keep scheduled policy');
    select nextval('public.campaign_push_order_seq');
    select nextval('public.campaign_push_order_seq');
    set "app.settings.media_gateway_secret"='baseline-test-only-secret';
    set "app.settings.media_gateway_base_url"='https://gateway.baseline.test/media';
  `);
}
async function sequences(db) {
  const names = (await db.query(`select relname from pg_class where relnamespace='public'::regnamespace and relkind='S' order by relname`)).rows;
  const result = {};
  for (const { relname } of names) result[relname] = (await db.query(`select last_value::text,is_called from public.${ident(relname)}`)).rows[0];
  return result;
}
function comparableCatalog(state) {
  // Physical attnum gaps left by historic DROP COLUMN are not schema behavior.
  return { ...state, columns: state.columns.map(({ position, ...c }) => c) };
}
function withoutSeedTimestamps(value) {
  if (Array.isArray(value)) return value.map(withoutSeedTimestamps);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .filter(([key]) => !['created_at','updated_at','changed_at','recorded_at'].includes(key))
    .map(([key, child]) => [key, withoutSeedTimestamps(child)]));
  return value;
}

await test('Fresh baseline has the same final schema, function bodies, ACLs, RLS, indexes and triggers as all 65 migrations', async () => {
  const reference = await createDb({ legacyGrants: true });
  const fresh = await createDb();
  try {
    await replay(reference);
    await fresh.exec(baseline);
    const expected = comparableCatalog(await catalog(reference));
    const actual = comparableCatalog(await catalog(fresh));
    for (const key of Object.keys(expected)) assert.deepEqual(actual[key], expected[key], `Catalog section: ${key}`);
    assert.deepEqual(withoutSeedTimestamps(await dataSnapshot(fresh)), withoutSeedTimestamps(await dataSnapshot(reference)), 'Fresh configuration matches the reviewed history');
    const before = await dataSnapshot(fresh);
    await fresh.exec(baseline);
    assert.deepEqual(await dataSnapshot(fresh), before, 'Fresh rerun does not duplicate configuration');
    await fresh.exec(`insert into auth.users(id,email) values ('${owner}','fresh@baseline.test');`);
    const profile = (await fresh.query(`select credits,role from public.profiles where id='${owner}'`)).rows[0];
    assert.deepEqual(profile, { credits: 100, role: 'member' }, 'New-user trigger remains operational');
  } finally {
    await reference.close();
    await fresh.close();
  }
});

for (const legacyGrants of [false, true]) {
  for (const omit046 of [true, false]) {
    await test(`Existing 061${omit046 ? ' without 046' : ' with 046'}, ${legacyGrants ? 'legacy' : 'restricted'} default grants: preserve all business data, ACLs, sequence values and settings`, async () => {
      const db = await createDb({ legacyGrants });
      try {
        await replay(db, { omit046 });
        await seedBusinessData(db);
        await db.exec(`grant select on public.app_configs to authenticated;`);
        const before = await dataSnapshot(db, { omitMediaExpired: true });
        const authBefore = (await db.query('select * from auth.users order by id')).rows;
        const seqBefore = await sequences(db);
        const beforeCatalog = await catalog(db);
        await db.exec(baseline);
        assert.deepEqual(await dataSnapshot(db, { omitMediaExpired: true }), before, 'Every existing public row is preserved');
        assert.deepEqual((await db.query('select * from auth.users order by id')).rows, authBefore, 'Auth accounts are unchanged');
        assert.deepEqual(await sequences(db), seqBefore, 'No sequence is reset or advanced');
        const afterCatalog = await catalog(db);
        assert.deepEqual(afterCatalog.relationGrants, beforeCatalog.relationGrants, 'Existing relation ACLs are preserved');
        assert.deepEqual(afterCatalog.columnGrants, beforeCatalog.columnGrants, 'Existing column ACLs are preserved');
        const mediaNames = new Set(['get_media_gateway_secret','get_report_media_access','verify_media_access_grant','verify_media_access_grant_internal']);
        assert.deepEqual(afterCatalog.functionGrants.filter(f => !mediaNames.has(f.name)), beforeCatalog.functionGrants.filter(f => !mediaNames.has(f.name)), 'Non-media function ACLs are preserved');
        assert.equal((await db.query(`select media_expired from public.tester_reports where id='${report}'`)).rows[0].media_expired, false);
        assert.equal((await db.query(`select public.get_media_gateway_secret() as secret`)).rows[0].secret, 'baseline-test-only-secret');
        assert.equal((await db.query(`select current_setting('app.settings.media_gateway_base_url') as url`)).rows[0].url, 'https://gateway.baseline.test/media');
        assert.equal((await db.query(`select has_function_privilege('authenticated','public.get_media_gateway_secret()','execute') as allowed`)).rows[0].allowed, false);
        const marker = (await db.query(`select version,existing_database from crosstest_migrations.baselines`)).rows;
        assert.deepEqual(marker, [{ version: '062', existing_database: true }]);
        // Simulate a later migration; rerunning 062 must not restore an older body.
        await db.exec(read('supabase/migrations/063_report_crypto_search_path.sql'));
        const after063 = (await db.query(`select proconfig from pg_proc where oid='public.submit_tester_report(uuid,text,text,integer,text,uuid,text,uuid)'::regprocedure`)).rows;
        await db.exec(`comment on function public.get_media_gateway_secret() is 'Changed by migration 063';`);
        await db.exec(baseline);
        assert.deepEqual((await db.query(`select proconfig from pg_proc where oid='public.submit_tester_report(uuid,text,text,integer,text,uuid,text,uuid)'::regprocedure`)).rows, after063, 'Rerunning 062 preserves the real 063 search_path fix');
        assert.equal((await db.query(`select obj_description('public.get_media_gateway_secret()'::regprocedure,'pg_proc') as comment`)).rows[0].comment, 'Changed by migration 063');
        assert.deepEqual(await dataSnapshot(db, { omitMediaExpired: true }), before, 'Rerun does not mutate business data');
      } finally {
        await db.close();
      }
    });
  }
}

await test('An older/partial schema fails atomically before business changes', async () => {
  const db = await createDb();
  try {
    await replay(db, { through: 54, omit046: true });
    const before = await dataSnapshot(db);
    await assert.rejects(db.exec(baseline), /BASELINE_062_SCHEMA_NOT_READY/);
    await db.exec('rollback');
    assert.deepEqual(await dataSnapshot(db), before);
    assert.equal((await db.query(`select to_regnamespace('crosstest_migrations') as namespace`)).rows[0].namespace, null);
  } finally { await db.close(); }
});

await test('Conflicting constraint definition causes a full rollback including the missing 046 column', async () => {
  const db = await createDb();
  try {
    await replay(db, { omit046: true });
    await db.exec(`alter table public.profiles drop constraint profiles_credits_check;
      alter table public.profiles add constraint profiles_credits_check check(credits>=1);`);
    await assert.rejects(db.exec(baseline), /BASELINE_062_CONSTRAINT_DRIFT/);
    await db.exec('rollback');
    assert.equal((await db.query(`select count(*)::integer as count from information_schema.columns where table_schema='public' and table_name='tester_reports' and column_name='media_expired'`)).rows[0].count, 0);
    assert.equal((await db.query(`select to_regnamespace('crosstest_migrations') as namespace`)).rows[0].namespace, null);
  } finally { await db.close(); }
});

await test('Archived history remains the exact reviewed 65 files', async () => {
  const { legacyFiles } = await import('../tools/baseline-lib.mjs');
  assert.equal(legacyFiles().length, 65);
  const manifest = JSON.parse(read('audit/migration-baseline-062-2026-10-30/manifest.json'));
  assert.deepEqual(legacyFiles().map(f => ({ name: f.name, sha256: createHash('sha256').update(f.sql).digest('hex') })), manifest.sourceFiles);
});

const out = new URL('audit/migration-baseline-062-2026-10-30/', root);
mkdirSync(out, { recursive: true });
writeFileSync(new URL('test-results.json', out), JSON.stringify({ passed: results.filter(r => r.passed).length, total: results.length, results }, null, 2) + '\n');
console.log(`${results.filter(r => r.passed).length}/${results.length} baseline checks passed`);
