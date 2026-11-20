import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
import { readdirSync, writeFileSync } from 'node:fs';
const db=await createDb();
const report=[];
const admin='11111111-1111-1111-1111-111111111111', owner='22222222-2222-2222-2222-222222222222', tester='33333333-3333-3333-3333-333333333333';
const campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', warning='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
async function actor(uid,sql) {
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${uid}',true); set local role authenticated;`);
  try {const rows=(await db.query(sql)).rows;await db.exec('commit');return rows;}
  catch(e){await db.exec('rollback');throw e;}
}
async function check(name,fn) {try{report.push({name,...await fn()});}catch(e){report.push({name,error:e.message});}}
try {
  const migrations=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^0\d\d.*\.sql$/.test(n)&&Number(n.slice(0,3))<=69).sort();
  for(const f of migrations)await db.exec(read('supabase/migrations/'+f));
  await db.exec(`insert into auth.users(id,email) values ('${admin}','admin@fixture.invalid'),('${owner}','owner@fixture.invalid'),('${tester}','tester@fixture.invalid');
  update public.profiles set role='admin' where id='${admin}';
  insert into public.campaigns(id,owner_id,app_name,package_name,status) values ('${campaign}','${owner}','Oracle','invalid.fixture.oracle','open');
  insert into public.campaign_members(campaign_id,user_id) values ('${campaign}','${tester}');
  insert into public.user_warnings(id,user_id,warning_type,message) values ('${warning}','${tester}','admin_manual','Independent fixture warning');`);
  const beforeDetail=(await actor(tester,`select public.get_campaign_detail('${campaign}') as r`))[0].r;
  const beforeDashboard=(await actor(tester,`select public.get_my_dashboard(false) as r`))[0].r;
  await db.exec(read('supabase/migrations/070_admin_management.sql'));
  await check('campaign detail preserves full DTO keys',async()=>{
    const after=(await actor(tester,`select public.get_campaign_detail('${campaign}') as r`))[0].r;
    return {beforeKeys:Object.keys(beforeDetail).sort(),afterKeys:Object.keys(after).sort(),missing:Object.keys(beforeDetail).filter(k=>!(k in after)),added:Object.keys(after).filter(k=>!(k in beforeDetail))};
  });
  await check('warning revocation consistent on dashboard and detail',async()=>{
    await actor(admin,`select public.admin_revoke_warning('${warning}','Independent review','dddddddd-dddd-dddd-dddd-dddddddddddd')`);
    const detail=(await actor(tester,`select public.get_campaign_detail('${campaign}') as r`))[0].r;
    const dash=(await actor(tester,`select public.get_my_dashboard(false) as r`))[0].r;
    return {beforeDashboardKeys:Object.keys(beforeDashboard),beforeDashboardWarnings:beforeDashboard.weekly_warnings_count,detailWarnings:detail.weekly_warnings_count,dashboardWarnings:dash.weekly_warnings_count,dashboardUnread:dash.unread_warnings?.length};
  });
  await check('carryover schedule and cancellation succeed',async()=>{
    const cfg=(await actor(admin,'select public.admin_get_carryover_policies() as r'))[0].r;
    const scheduled=(await actor(admin,`select public.admin_schedule_carryover_policy((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date+1,false,'Independent schedule',${cfg.revision},'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee') as r`))[0].r;
    return {scheduled,cancelled:(await actor(admin,`select public.admin_cancel_future_carryover_policy((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date+1,'Independent cancel',${scheduled.revision},'ffffffff-ffff-ffff-ffff-ffffffffffff') as r`))[0].r};
  });
  await check('existing admin adjustment is audited',async()=>{
    const before=(await db.query('select count(*)::int as n from public.admin_action_events')).rows[0].n;
    await actor(admin,`select public.admin_adjust_credits('${owner}',1,'Independent old API')`);
    const after=(await db.query('select count(*)::int as n from public.admin_action_events')).rows[0].n;
    return {newAuditEvents:after-before};
  });
  await check('member overview has actual slot data',async()=>{
    const info=(await actor(admin,`select public.admin_get_member_overview('${owner}') as r`))[0].r;
    return {keys:Object.keys(info),hasSlotsArray:Array.isArray(info.slots)};
  });
} catch(e){report.push({fatal:e.message});}
finally {await db.close();}
writeFileSync(new URL('./independent-core-probe-results.json',import.meta.url),JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));
