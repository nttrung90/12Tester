import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');
const read = p => readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8');
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const results = [];
const capture = async (name, sql) => {
  await db.exec('savepoint probe');
  try {
    const result = await db.query(sql);
    results.push({ name, rows: result.rows });
    return result.rows;
  } catch (e) {
    results.push({ name, error: e.message, code: e.code });
    await db.exec('rollback to savepoint probe');
    return null;
  } finally { await db.exec('release savepoint probe'); }
};

try {
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz, confirmation_token text, email_change text,
      email_change_token_new text, recovery_token text);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    grant usage on schema public, auth to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
  `);
  await db.exec(read('supabase/full_schema_singapore.sql'));
  const migration = read('supabase/migrations/055_localization_hardening.sql');
  
  // 1. Verify exact migration 055 executes cleanly without SQLSTATE 42725
  await db.exec('begin');
  let exactError;
  try {
    await db.exec(migration);
    results.push({ name: 'exact_migration_055', success: true });
  } catch (e) {
    exactError = e;
    results.push({ name: 'exact_migration_055', error: e.message, code: e.code });
  }

  assert.equal(exactError, undefined, 'Migration 055 must execute cleanly without SQLSTATE 42725 or any syntax/GRANT errors.');

  await capture('function_overloads_after_migration', `select proname,
    pg_get_function_identity_arguments(oid) args from pg_proc
    where pronamespace='public'::regnamespace
      and proname in ('reject_tester_report','admin_update_app_config') order by proname,pronargs`);

  const owner = '11111111-1111-1111-1111-111111111111';
  const tester = '22222222-2222-2222-2222-222222222222';
  const camp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  const report = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  await db.exec(`
    insert into auth.users(id,email) values('${owner}','owner@test.local'),('${tester}','tester@test.local');
    insert into public.profiles(id,email,display_name,credits,role,created_at) values
      ('${owner}','owner@test.local','Owner',1000,'admin',now()-interval '10 days'),
      ('${tester}','tester@test.local','Tester',100,'member',now()-interval '10 days')
    on conflict(id) do update set credits=excluded.credits,role=excluded.role;
    insert into public.campaigns(id,owner_id,app_name,package_name,status,target_testers,slot_number,created_at)
      values('${camp}','${owner}','DemoApp','audit.localization','open',12,1,now()-interval '3 days');
    insert into public.tester_reports(id,campaign_id,user_id,status,screenshot_url,provisional_granted_credits,created_at)
      values('${report}','${camp}','${tester}','pending','https://example.invalid/screenshot',10,now()-interval '1 hour');
    set request.jwt.claim.sub='${owner}';
  `);

  // 2. Legacy admin update call (7 parameters) must succeed with zero overload ambiguity
  const adminRes = await capture('legacy_admin_call', `select public.admin_update_app_config(
    p_min_version_code=>1,p_latest_version_code=>2,p_latest_version_name=>'0.0.2',p_force_update=>false,
    p_title=>'Legacy title',p_message=>'Legacy message',p_store_url=>null) result`);
  assert.ok(adminRes && adminRes.length > 0 && adminRes[0].result, 'Legacy admin call must succeed without SQLSTATE 42725 ambiguity.');

  // 3. Legacy reject call (2 parameters) used by current client must succeed and update report status
  const legacyRejectRes = await capture('legacy_reject_call_used_by_current_client', `select public.reject_tester_report(
    p_report_id=>'${report}'::uuid,p_reason=>'[CODE:REASON_DUPLICATE_OR_OLD]'::text) result`);
  assert.ok(legacyRejectRes && legacyRejectRes.length > 0 && legacyRejectRes[0].result, 'Legacy reject call must succeed without SQLSTATE 42725 ambiguity.');
  
  const reportRow = await capture('report_after_legacy_call', `select status,rejection_reason,reason_code from public.tester_reports where id='${report}'`);
  assert.equal(reportRow?.[0]?.status, 'rejected', 'Report status must be updated to rejected.');
  assert.equal(reportRow?.[0]?.reason_code, 'REASON_DUPLICATE_OR_OLD', 'reason_code must be automatically extracted from [CODE:...]');

  // 4. Reset to pending and test new reject overload with explicit p_reason_code
  await db.exec(`update public.tester_reports set status='pending' where id='${report}'`);
  await db.exec(`update public.profiles set credits=100 where id='${tester}'`);
  const explicitRejectRes = await capture('explicit_new_reject_overload', `select public.reject_tester_report(
    p_report_id=>'${report}'::uuid,p_reason=>'[CODE:REASON_DUPLICATE_OR_OLD]'::text,
    p_reason_code=>'REASON_DUPLICATE_OR_OLD'::text) result`);
  assert.ok(explicitRejectRes && explicitRejectRes.length > 0, 'Explicit new reject overload must succeed.');
  
  const rejectPayload = explicitRejectRes[0].result;
  assert.equal(rejectPayload.success, true, 'Reject payload success must be true.');
  assert.equal(rejectPayload.credits_deducted, 10, 'credits_deducted legacy key must be 10.');
  assert.equal(rejectPayload.clawed_credits, 10, 'clawed_credits key must be 10.');
  assert.equal(rejectPayload.tester_balance, 90, 'tester_balance legacy key must be 90.');
  assert.equal(rejectPayload.new_balance, 90, 'new_balance key must be 90.');

  // 5. Verify notifications and credit logs
  const notifs = await capture('newly_generated_notifications', `select title,message,type,data from public.notifications
    where user_id in ('${owner}','${tester}') order by created_at,id`);
  const creditLogs = await capture('newly_generated_credit_logs', `select action,note,amount,metadata from public.credit_logs
    where user_id in ('${owner}','${tester}') order by created_at,id`);

  await db.exec('rollback');

  writeFileSync(new URL('./database-results.json', import.meta.url), JSON.stringify(results, null, 2));
  for (const r of results) console.log(JSON.stringify(r));
  console.log('Database regression probe PASSED cleanly: all overloads, contracts and triggers verified.');
} finally { await db.close(); }
