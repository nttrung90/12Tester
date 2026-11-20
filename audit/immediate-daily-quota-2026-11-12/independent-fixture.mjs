// Supervisor-owned fixture. Only connects to this task's private native cluster.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
export const OWNER='e1000000-0000-0000-0000-000000000001';
export const NEW_OWNER='e1000000-0000-0000-0000-000000000002';
export const ADMIN='e2000000-0000-0000-0000-000000000001';
export const TODAY=`(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date`;
export const options={host:'127.0.0.1',port:55446,user:'quota_test_admin',database:'immediate_quota_test',connectionTimeoutMillis:5000,statement_timeout:15000};
export async function connect(database=options.database) {
  assert.match(database,/^immediate_quota_(test|native_[a-z0-9_]+)$/);
  const db=new pg.Client({...options,database});await db.connect();
  const dir=(await db.query("select current_setting('data_directory') d")).rows[0].d;
  assert.equal(dir.replaceAll('\\','/').toLowerCase(),'f:/ai/12tester/audit/immediate-daily-quota-2026-11-12/pg-data');return db;
}
export const one=async(db,sql,params)=>(await db.query(sql,params)).rows[0];
export async function asActor(db,uid,sql,params=[]) {
  await db.query('savepoint actor_call');
  try {
    await db.query('set local role authenticated');
    await db.query("select set_config('request.jwt.claim.sub',$1,true)",[uid]);
    const result=await db.query(sql,params);
    await db.query("reset role;select set_config('request.jwt.claim.sub','',true);release savepoint actor_call");return result;
  }catch(e){await db.query('rollback to savepoint actor_call;release savepoint actor_call');throw e;}
}
export async function status(db,uid=OWNER,date=TODAY) {
  return (await one(db,`select public.get_daily_task_status($1,${date}) s`,[uid])).s;
}
export async function createApp(db,{uid=OWNER,slot=2,target=12,request=randomUUID(),pkg='supervisor.quota.app'+randomUUID().replaceAll('-','')}={}) {
  const quote=(await asActor(db,uid,"select public.get_business_quote('all') q")).rows[0].q;
  const cost=(slot===1?quote.slot_fees.slot_1:0)+quote.target_fees['target_'+target];
  const sql=`select public.create_campaign_v2($1,$2,'','','Quota fixture',$3,'','','',$4,$5,$6,$7,$8) r`;
  const args=['Quota '+slot,pkg,target,slot,request,cost,quote.min_credits_to_post_app,quote.revision];
  const response=(await asActor(db,uid,sql,args)).rows[0].r;
  assert.equal(response.success,true);return {response,sql,args,uid};
}
export async function removeApp(db,id,{byAdmin=false}={}) {
  return (await asActor(db,byAdmin?ADMIN:OWNER,byAdmin?'select public.admin_delete_campaign($1) r':'select public.delete_owned_campaign($1) r',[id])).rows[0].r;
}
export async function events(db,n,uid=OWNER) {
  await db.query(`insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,
      activity_date,status,is_qualifying,submission_context,reconciliation_status,observed_at)
    select 'checkin',gen_random_uuid(),$1,c.id,c.owner_id,${TODAY},'valid',true,'checkin','verified_checkin',clock_timestamp()
    from public.campaigns c where c.owner_id<>$1 and package_name like 'supervisor.supply.%' order by package_name limit $2`,[uid,n]);
}
export async function setup(db,{debt=0,streak=0,seedCheckpoint=true}={}) {
  // Privileged isolated seed only: production guard is restored before any RPC.
  const guards=(await db.query(`select t.tgname from pg_trigger t where t.tgrelid='public.daily_task_policies'::regclass
    and t.tgfoid='public.guard_daily_task_policy()'::regprocedure and not t.tgisinternal`)).rows;
  for(const {tgname} of guards)await db.query('alter table public.daily_task_policies disable trigger "'+tgname.replaceAll('"','""')+'"');
  await db.query(`insert into public.daily_task_policies(effective_from,tests_per_active_app,operator_note)
    values(${TODAY}-10,12,'Supervisor private fixture q12') on conflict(effective_from) do update set tests_per_active_app=12`);
  for(const {tgname} of guards)await db.query('alter table public.daily_task_policies enable trigger "'+tgname.replaceAll('"','""')+'"');
  const suppliers=Array.from({length:5},(_,i)=>`e2000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`);
  for(const [i,id] of [OWNER,NEW_OWNER,...suppliers].entries()) {
    await db.query('insert into auth.users(id,email) values($1,$2)',[id,'quota-fixture-'+i+'@test.local']);
    await db.query(`update public.profiles set credits=10000,created_at=case when id=$2 then clock_timestamp() else clock_timestamp()-interval '90 days' end,
      last_login_date=${TODAY},role=case when id=any($3::uuid[]) then 'admin' else 'member' end,daily_missed_streak=$4 where id=$1`,
      [id,NEW_OWNER,suppliers,id===OWNER?streak:0]);
    for(let slot=2;slot<=6;slot++)await db.query(`insert into public.user_slots(user_id,slot_number,expires_at)
      values($1,$2,clock_timestamp()+interval '30 days') on conflict(user_id,slot_number) do update set expires_at=excluded.expires_at`,[id,slot]);
  }
  for(const [i,id] of suppliers.entries())for(let slot=1;slot<=6;slot++)await db.query(`insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at)
    values($1,$2,$2,'open',$3,clock_timestamp()-interval '15 days')`,[id,'supervisor.supply.'+i+'.'+slot,slot]);
  const old=(await db.query(`insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at)
    values($1,'supervisor.owner.old','Old owner app','open',1,clock_timestamp()-interval '15 days') returning id`,[OWNER])).rows[0].id;
  await db.query(`update public.campaign_status_journal set valid_from=clock_timestamp()-interval '15 days';
    update public.user_role_journal set valid_from=clock_timestamp()-interval '90 days';
    update public.system_provenance_markers set marker_time=(${TODAY}-60)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${TODAY}-60 where marker_key='coverage_start';`);
  if(seedCheckpoint) await db.query(`insert into public.user_daily_tasks(user_id,task_date,mode,active_app_count,required_tests,base_required_tests,effective_required_tests,
    available_apps,completed_tests,logged_in,is_exempt,met,missed_streak,carry_in_tests,carry_out_tests,finalized_at)
    values($1,${TODAY}-1,'owner',1,12,12,12,30,0,true,false,$4,$2,0,$3,clock_timestamp())`,[OWNER,streak,debt,streak===0]);
  await db.query(`insert into public.user_daily_tasks(user_id,task_date,mode,logged_in,met,finalized_at)
    values($1,${TODAY},'owner',true,false,null) on conflict(user_id,task_date) do update set logged_in=true,finalized_at=null`,[OWNER]);
  await db.query(`select public.refresh_daily_completion($1,${TODAY})`,[OWNER]);
  return {old,suppliers};
}
