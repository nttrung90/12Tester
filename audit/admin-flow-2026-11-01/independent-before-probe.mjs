import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
import { readdirSync } from 'node:fs';
const db = await createDb();
try {
  for (const name of readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(n => /^0\d\d.*\.sql$/.test(n) && Number(n.slice(0,3)) <= 68).sort()) await db.exec(read('supabase/migrations/' + name));
  const admin = '11111111-1111-1111-1111-111111111111';
  const user = '22222222-2222-2222-2222-222222222222';
  await db.exec(`insert into auth.users(id,email) values ('${admin}','admin@fixture.invalid'),('${user}','member@fixture.invalid');
    update public.profiles set role='admin' where id='${admin}';
    update public.profiles set credits=5 where id='${user}';`);
  async function probe(name, sql, role = 'authenticated') {
    await db.exec('begin');
    try {
      await db.exec(`select set_config('request.jwt.claim.sub','${admin}',true); set local role ${role};`);
      for (const statement of Array.isArray(sql) ? sql : [sql]) {
        console.log(name, JSON.stringify((await db.query(statement)).rows));
        await db.exec('reset role');
      }
    } catch(e) { console.log(name, 'ERROR', e.message); }
    finally { await db.exec('rollback'); }
  }
  await probe('BEFORE: overdraft ledger and actual change', [`select public.admin_adjust_credits('${user}',-50,'audit') as result`,
    `select amount,balance from public.credit_logs where user_id='${user}' and action='admin_deduct' order by created_at desc limit 1`]);
  await probe('BEFORE: lock absent target', `select public.admin_set_user_lock('99999999-9999-9999-9999-999999999999',true,'audit') as result`);
  await db.exec(`update public.profiles set is_locked=true where id='${admin}';`);
  await probe('BEFORE: locked admin can adjust credits', `select public.admin_adjust_credits('${user}',1,'audit') as result`);
  await probe('BEFORE: locked admin can list contact reports', `select public.admin_get_contact_reports() as result`);
  await probe('BEFORE: locked admin can bypass RPC with direct config update', `update public.app_configs set latest_version_name='locked-admin-wrote-this' where id='android_client' returning id, latest_version_name`);
  await probe('BEFORE: anon database role has TRUNCATE capability beyond RLS', ['truncate table public.app_configs', 'select count(*) as configs_remaining from public.app_configs'], 'anon');
} finally { await db.close(); }
