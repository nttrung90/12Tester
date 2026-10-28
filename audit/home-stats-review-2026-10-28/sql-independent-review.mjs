import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = await import(pathToFileURL(require.resolve('@electric-sql/pglite')).href);
const { pgcrypto } = await import(pathToFileURL(require.resolve('@electric-sql/pglite/contrib/pgcrypto')).href);
const { uuid_ossp } = await import(pathToFileURL(require.resolve('@electric-sql/pglite/contrib/uuid_ossp')).href);
const read = p => readFileSync(new URL('../../' + p, import.meta.url), 'utf8');
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const results = [];
const admin='11111111-1111-1111-1111-111111111111';
const member='22222222-2222-2222-2222-222222222222';
const moderator='33333333-3333-3333-3333-333333333333';
const app='44444444-4444-4444-4444-444444444444';
async function asRole(role, uid, sql) {
  await db.exec('set role '+role+';');
  await db.query("select set_config('request.jwt.claim.sub', $1, false)", [uid || '']);
  try { return await db.query(sql); }
  finally { await db.exec('reset role; reset "request.jwt.claim.sub";'); }
}
async function test(name, fn) {
  try { const detail=await fn(); results.push({name,pass:true,detail}); console.log('PASS '+name); }
  catch(e) { results.push({name,pass:false,error:String(e.message)}); console.log('FAIL '+name+': '+e.message); }
}
try {
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',email_confirmed_at timestamptz,confirmation_token text,email_change text,email_change_token_new text,recovery_token text);
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    grant usage on schema public,auth to anon,authenticated,service_role;
    grant execute on function auth.uid() to anon,authenticated,service_role;
  `);
  for(const p of ['supabase/full_schema_singapore.sql','supabase/migrations/045_thumbnail_support.sql','supabase/migrations/046_media_access_gateway.sql','supabase/migrations/047_home_stats_cycle.sql']) await db.exec(read(p));
  await db.exec(`
    insert into auth.users(id,email) values ('${admin}','review.admin@fixture.invalid'),('${member}','review.member@fixture.invalid'),('${moderator}','review.moderator@fixture.invalid');
    update public.profiles set role='admin' where id='${admin}';
    update public.profiles set role='moderator' where id='${moderator}';
    insert into public.campaigns(id,owner_id,app_name,package_name,target_testers,status) values('${app}','${admin}','Review app','fixture.app',12,'open');
  `);
  for(const cycle of [2,3]) await test('actual_RPC_'+cycle+'_day_accumulation', async()=>{
    await db.exec(`delete from public.checkins where campaign_id='${app}'; delete from public.home_stats_policies where effective_from>'2020-01-01';
      insert into public.home_stats_policies(effective_from,cycle_days) values((now() at time zone 'Asia/Ho_Chi_Minh')::date-${cycle-1},${cycle});`);
    // Same user tests on every day; a second user tests today.
    for(let offset=0;offset<cycle;offset++) await db.exec(`insert into public.checkins(campaign_id,user_id,checkin_date) values('${app}','${member}',(now() at time zone 'Asia/Ho_Chi_Minh')::date-${offset});`);
    await db.exec(`insert into public.checkins(campaign_id,user_id,checkin_date) values('${app}','${moderator}',(now() at time zone 'Asia/Ho_Chi_Minh')::date);`);
    const feed=(await asRole('authenticated',member,'select public.get_home_feed() as data')).rows[0].data.stats[app];
    const batch=(await asRole('authenticated',member,`select public.get_campaigns_stats(array['${app}'::uuid]) as data`)).rows[0].data[app];
    assert.equal(feed.home_tested_count,cycle+1); assert.equal(batch.home_tested_count,cycle+1);
    assert.equal(feed.today_tested_count,2); assert.equal(batch.today_tested_count,2);
    return {feed,batch};
  });
  for(const cycle of [2,3]) await test('actual_RPC_'+cycle+'_day_boundary_excludes_previous_cycle',async()=>{
    await db.exec(`delete from public.checkins where campaign_id='${app}'; delete from public.home_stats_policies where effective_from>'2020-01-01';
      insert into public.home_stats_policies(effective_from,cycle_days) values((now() at time zone 'Asia/Ho_Chi_Minh')::date-${cycle},${cycle});`);
    for(let offset=1;offset<=cycle;offset++) await db.exec(`insert into public.checkins(campaign_id,user_id,checkin_date) values('${app}','${member}',(now() at time zone 'Asia/Ho_Chi_Minh')::date-${offset});`);
    const feed=(await asRole('authenticated',member,'select public.get_home_feed() as data')).rows[0].data.stats[app];
    const batch=(await asRole('authenticated',member,`select public.get_campaigns_stats(array['${app}'::uuid]) as data`)).rows[0].data[app];
    assert.equal(feed.home_tested_count,0); assert.equal(batch.home_tested_count,0);
    assert.equal(feed.today_tested_count,0); assert.equal(batch.today_tested_count,0);
  });
  await test('non_admin_direct_access_is_denied',async()=>{
    for(const [role,uid] of [['anon',null],['authenticated',member],['authenticated',moderator]]) {
      for(const sql of ['select * from public.home_stats_policies','select * from public.home_stats_policy_audits',"select * from public._get_home_stats_period(current_date)"])
        await assert.rejects(()=>asRole(role,uid,sql),/permission denied/i);
      await assert.rejects(()=>asRole(role,uid,'select public.admin_get_home_stats_config()'),/UNAUTHORIZED|FORBIDDEN|permission denied/i);
    }
  });
  await test('stale_admin_cannot_overwrite_without_revision',async()=>{
    const cfg=(await asRole('authenticated',admin,'select public.admin_get_home_stats_config() as data')).rows[0].data;
    const next=cfg.active_cycle_days===2?3:2;
    await asRole('authenticated',admin,`select public.admin_set_home_stats_cycle(${next},'${cfg.revision}')`);
    await assert.rejects(()=>asRole('authenticated',admin,`select public.admin_set_home_stats_cycle(1,null)`),/REVISION|CONCURRENCY|CONFLICT/i,
      'A second caller bypassed the expected revision by sending NULL and overwrote the first admin schedule');
  });
  await test('active_policy_must_be_immutable_for_operational_DML',async()=>{
    await assert.rejects(()=>asRole('service_role',null,`update public.home_stats_policies set cycle_days=1 where effective_from=(select max(effective_from) from public.home_stats_policies where effective_from<=(now() at time zone 'Asia/Ho_Chi_Minh')::date)`),
      /IMMUTABLE|ACTIVE|PAST|permission denied/i,'Already active policy can be changed directly; no immutability guard exists');
  });
  await test('same_revision_is_independent_of_session_TimeZone',async()=>{
    await db.exec("set timezone='UTC'");
    const utc=(await asRole('authenticated',admin,'select public.admin_get_home_stats_config() as data')).rows[0].data.revision;
    await db.exec("set timezone='Asia/Ho_Chi_Minh'");
    const vn=(await asRole('authenticated',admin,'select public.admin_get_home_stats_config() as data')).rows[0].data.revision;
    assert.equal(utc,vn,'Revision hashes timestamp text using the session TimeZone');
  });
  console.log(JSON.stringify({results},null,2));
} finally { await db.close(); }
process.exitCode=results.some(x=>!x.pass)?1:0;
