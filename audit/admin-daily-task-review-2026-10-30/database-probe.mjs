import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const results = [];
const admin = '11111111-1111-1111-1111-111111111111';
async function asAdmin(sql, params = []) {
  await db.exec(`begin; set local role authenticated; set local "request.jwt.claim.sub" = '${admin}';`);
  try { const r = await db.query(sql, params); await db.exec('commit'); return r.rows[0]?.result; }
  catch (e) { await db.exec('rollback'); throw e; }
}
async function probe(name, fn) {
  try { const detail = await fn(); results.push({ name, ...detail }); console.log(name, JSON.stringify(detail)); }
  catch (e) { results.push({ name, error: e.message }); console.log(name, e.message); }
}
try {
  await db.exec(`
    create role anon nologin; create role authenticated nologin; create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users (id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz, confirmation_token text, email_change text, email_change_token_new text, recovery_token text);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    grant usage on schema public, auth to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
  `);
  await db.exec(readFileSync(new URL('../../supabase/full_schema_singapore.sql', import.meta.url), 'utf8'));
  await db.exec(`insert into auth.users(id,email) values('${admin}','audit@fixture.invalid');
    update public.profiles set role='admin',is_locked=false where id='${admin}';
    select public.schedule_daily_task_policy(6,(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date+i,'audit '||i)
    from generate_series(1,55) i;`);
  await probe('futurePagination', async () => {
    const first = await asAdmin('select public.admin_list_daily_task_policies(2) result');
    const second = await asAdmin('select public.admin_list_daily_task_policies(2,$1::date,$2::bigint) result', [first.next_cursor_date, first.next_cursor_id]);
    const expected = (await db.query(`select id,effective_from::text d from public.daily_task_policies
      where effective_from > (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date order by effective_from,id limit 4`)).rows;
    const observed = [...first.items,...second.items].map(x=>x.id);
    return { expected: expected.map(x=>x.id), observed, cursor:first.next_cursor_id, bug:JSON.stringify(observed)!==JSON.stringify(expected.map(x=>x.id)) };
  });
  await probe('historyPagination', async () => {
    const first = await asAdmin('select public.admin_get_daily_task_policy_history(2) result');
    const second = await asAdmin('select public.admin_get_daily_task_policy_history(2,$1::bigint) result',[first.next_cursor_id]);
    const expected = (await db.query('select id from public.daily_task_policy_changes order by id desc limit 4')).rows.map(x=>x.id);
    const observed=[...first.items,...second.items].map(x=>x.id);
    return { expected, observed, cursor:first.next_cursor_id, bug:JSON.stringify(observed)!==JSON.stringify(expected) };
  });
  await probe('configTruncation',async()=> {
    const c=await asAdmin('select public.admin_get_daily_task_config() result');
    return { totalFuture:55, returned:c.future_policies.length, keys:Object.keys(c), bug:c.future_policies.length===50 && !('next_cursor_id' in c) };
  });
  await probe('sameTransactionHistory',async()=> {
    try { await asAdmin('select public.admin_get_daily_task_policy_history(2),public.admin_get_daily_task_policy_history(2) result');
      return {bug:false}; } catch(e) { return {bug:true,error:e.message}; }
  });
  await probe('sameTransactionFutureList',async()=> {
    try { await asAdmin('select public.admin_list_daily_task_policies(2),public.admin_list_daily_task_policies(2) result');
      return {bug:false}; } catch(e) { return {bug:true,error:e.message}; }
  });
  const migration = readFileSync(new URL('../../supabase/migrations/058_admin_daily_task_policy.sql',import.meta.url),'utf8');
  await probe('standaloneMigrationPreservesData',async()=> {
    const snap=async()=> (await db.query(`select
      (select jsonb_agg(to_jsonb(p) order by id) from public.daily_task_policies p) policies,
      (select jsonb_agg(to_jsonb(a) order by id) from public.daily_task_policy_changes a) audits`)).rows[0];
    const before=await snap();
    const definitions=async()=> (await db.query(`select proname,prosrc from pg_proc p join pg_namespace n on n.oid=p.pronamespace
      where n.nspname='public' and (proname like 'admin_%daily_task%' or proname in ('_get_daily_task_policy_revision','_assert_daily_task_admin','_format_daily_task_policy_json','audit_daily_task_policy')) order by proname`)).rows.map(x=>({...x,prosrc:x.prosrc.replaceAll('\r\n','\n')}));
    const bodies=await definitions();
    await db.exec(migration);
    assert.deepEqual(await snap(),before);
    assert.deepEqual(await definitions(),bodies);
    return {preservesData:true,functionBodiesMatchMirror:true};
  });
  await probe('documentedOldPreflightOnCurrentSchema',async()=> {
    const r=await db.exec(readFileSync(new URL('../../supabase/operations/daily_task_ratio_preflight.sql',import.meta.url),'utf8'));
    return {checks:r.flatMap(x=>x.rows??[]).filter(x=>'deployment_check' in x).map(x=>({signature:x.signature,status:x.deployment_check}))};
  });
} finally {
  writeFileSync(new URL('./database-results.json',import.meta.url),JSON.stringify(results,null,2));
  await db.close();
}
