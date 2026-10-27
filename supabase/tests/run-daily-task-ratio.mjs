import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { uuid_ossp } from '@electric-sql/pglite/contrib/uuid_ossp';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = path => readFileSync(`${root}${path}`, 'utf8').replaceAll('\r\n', '\n');
const migration = read('supabase/migrations/042_configurable_daily_task_ratio.sql');
const full = read('supabase/full_schema_singapore.sql');
const marker = '-- ===== 042_configurable_daily_task_ratio.sql =====\n';
const nextMarker = '-- ===== 043_backend_cleanup_hardening.sql =====\n';
assert.equal(full.split(marker).length, 2, 'exactly one 042 mirror');
const section042 = full.split(marker)[1].split(nextMarker)[0].trimEnd() + '\n';
assert.equal(section042, migration.trimEnd() + '\n', 'mirror equals migration 042 byte for byte');
const marker044 = '-- ===== 044_mission_flow_hardening.sql =====\n';
if (full.includes(nextMarker)) {
  const migration043 = read('supabase/migrations/043_backend_cleanup_hardening.sql');
  const section043 = (full.includes(marker044) ? full.split(nextMarker)[1].split(marker044)[0] : full.split(nextMarker)[1]).trim();
  assert.equal(section043, migration043.trim(), 'mirror equals migration 043 byte for byte');
}
if (full.includes(marker044)) {
  const migration044 = read('supabase/migrations/044_mission_flow_hardening.sql');
  const section044 = full.split(marker044)[1].trim();
  assert.equal(section044, migration044.trim(), 'mirror equals migration 044 byte for byte');
}
const bootstrap = `
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create schema auth;
  create table auth.users (
    id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
    email_confirmed_at timestamptz, confirmation_token text,
    email_change text, email_change_token_new text, recovery_token text
  );
  create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
  $$;
  grant usage on schema public, auth to anon, authenticated, service_role;
  grant execute on function auth.uid() to anon, authenticated, service_role;
`;

let passed = 0;
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const fixture = read('supabase/tests/daily_task_ratio_fixture.sql');
async function baselineSnapshot() {
  await db.exec(`begin; ${fixture}`);
  try {
    const statuses = (await db.query(`
      select p.email, public.get_daily_task_status(p.id,
        (now() at time zone 'Asia/Ho_Chi_Minh')::date) as status
      from public.profiles p order by p.email
    `)).rows;
    await db.query(`select public._apply_daily_inactivity_penalty(
      (now() at time zone 'Asia/Ho_Chi_Minh')::date - 1)`);
    const finalized = (await db.query(`
      select p.email, t.required_tests, t.effective_required_tests, t.completed_tests,
        t.met, t.missed_streak, t.penalty_credits, p.credits
      from public.user_daily_tasks t join public.profiles p on p.id=t.user_id
      where t.finalized_at is not null order by p.email
    `)).rows;
    const logs = (await db.query(`
      select p.email, l.amount, l.action, l.note, l.balance
      from public.credit_logs l join public.profiles p on p.id=l.user_id
      order by p.email, l.action, l.note
    `)).rows;
    return { statuses, finalized, logs };
  } finally {
    await db.exec('rollback');
  }
}
try {
  await db.exec(bootstrap);
  await db.exec(full.split(marker)[0]);
  console.log('PASS: existing full schema through its current pre-042 content loads');
  const preflight = read('supabase/operations/daily_task_ratio_preflight.sql');
  const preflightRows = (await db.exec(preflight)).flatMap(r => r.rows ?? [])
    .filter(r => r.deployment_check !== undefined);
  assert.equal(preflightRows.length, 2);
  assert.ok(preflightRows.every(r => r.deployment_check === 'BASELINE_036_OK'));
  const baseline = await baselineSnapshot();
  await db.exec(migration);
  console.log('PASS: migration 042 upgrades the existing schema');
  assert.deepEqual(await baselineSnapshot(), baseline);
  console.log('PASS: default 3:1 status, penalties, balances and logs match pre-042');
  passed += 3;
  const installedPreflight = (await db.exec(preflight)).flatMap(r => r.rows ?? [])
    .filter(r => r.deployment_check !== undefined);
  assert.equal(installedPreflight.length, 2);
  assert.ok(installedPreflight.every(r => r.deployment_check === 'ALREADY_042_OK'));
  console.log('PASS: read-only preflight recognizes baseline and installed definitions');
  passed++;
  // The test SQL owns a transaction and rolls every fixture back.
  const results = await db.exec(`begin; ${fixture}\n${read('supabase/tests/daily_task_ratio.sql')}`);
  const sqlResults = results.flatMap(r => r.rows ?? []).filter(r => r.sql_assertions !== undefined);
  assert.equal(sqlResults.length, 1);
  console.log(`PASS: ${sqlResults[0].sql_assertions} SQL assertions (matrix, dates, audit, penalties, RPCs, roles)`);
  // This is an in-memory disposable DB: commit fixture rows to also verify a
  // rerun preserves real finalized ledger rows, balances and credit logs.
  await db.exec(`begin; ${fixture}\ncommit;`);
  await db.query(`select public._apply_daily_inactivity_penalty(
    (now() at time zone 'Asia/Ho_Chi_Minh')::date - 1)`);
  await db.query(`select public.schedule_daily_task_policy(4,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date + 10, 'rerun preservation test')`);
  const before = await db.query('select to_jsonb(p) as p from public.daily_task_policies p order by id');
  const auditBefore = await db.query('select to_jsonb(c) as c from public.daily_task_policy_changes c order by id');
  const ledgerQuery = `select jsonb_build_object(
    'tasks', (select jsonb_agg(to_jsonb(t) order by user_id,task_date) from public.user_daily_tasks t),
    'profiles', (select jsonb_agg(to_jsonb(p) order by id) from public.profiles p),
    'logs', (select jsonb_agg(to_jsonb(l) order by id) from public.credit_logs l)
  ) as snapshot`;
  const ledgerBefore = (await db.query(ledgerQuery)).rows;
  await db.exec(migration);
  assert.deepEqual((await db.query('select to_jsonb(p) as p from public.daily_task_policies p order by id')).rows, before.rows);
  assert.deepEqual((await db.query('select to_jsonb(c) as c from public.daily_task_policy_changes c order by id')).rows, auditBefore.rows);
  assert.deepEqual((await db.query(ledgerQuery)).rows, ledgerBefore);
  console.log('PASS: rerunning 042 preserves baseline, future schedules and audit');
  passed++;
  // A corrupted database must fail closed, not invent a default or reseed history.
  await db.exec(`begin;
    update public.user_daily_tasks set policy_id=null;
    alter table public.daily_task_policies disable trigger trg_guard_daily_task_policy;
    alter table public.daily_task_policies disable trigger trg_daily_task_policy_commit;
    delete from public.daily_task_policies where effective_from=date '-infinity';
    alter table public.daily_task_policies enable trigger trg_guard_daily_task_policy;
    alter table public.daily_task_policies enable trigger trg_daily_task_policy_commit;`);
  await assert.rejects(db.query('select public.get_daily_task_policy(current_date)'), /DAILY_TASK_POLICY_BASELINE_MISSING/);
  await db.exec('rollback');
  console.log('PASS: missing baseline fails closed');
  passed++;
  const definitionsQuery = `select p.proname, pg_get_functiondef(p.oid) as definition, p.proacl::text as acl
    from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public' and p.proname in (
      'get_daily_task_status','_apply_daily_inactivity_penalty','get_daily_task_policy',
      'schedule_daily_task_policy','cancel_daily_task_policy') order by p.proname`;
  const upgradedDefinitions = (await db.query(definitionsQuery)).rows;
  const fresh = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
  try {
    await fresh.exec(bootstrap);
    await fresh.exec(full);
    assert.deepEqual((await fresh.query(definitionsQuery)).rows, upgradedDefinitions);
    console.log('PASS: fresh full-schema install matches upgraded definitions and ACLs');
    passed++;
  } finally {
    await fresh.close();
  }
  // Drift guard must reject custom live logic, atomically, before any schema edit.
  await db.exec(`create or replace function public.get_daily_task_status(p_user_id uuid, p_date date)
    returns jsonb language sql as $$ select '{"custom":true}'::jsonb $$`);
  await assert.rejects(db.exec(migration), /DAILY_TASK_FUNCTION_DRIFT/);
  await db.exec('rollback');
  assert.deepEqual((await db.query('select to_jsonb(p) as p from public.daily_task_policies p order by id')).rows, before.rows);
  console.log('PASS: customized live function is refused without partial migration');
  passed++;
  console.log(`PASS: ${passed} runner checks plus SQL assertions`);
} catch (error) {
  console.error(`FAIL: ${error.message}`);
  if (error.detail) console.error(error.detail);
  if (error.hint) console.error(error.hint);
  if (error.where) console.error(error.where);
  process.exitCode = 1;
} finally {
  await db.close();
}
