import assert from 'node:assert/strict';
import {writeFileSync,readdirSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
const results=[];
const owner='11111111-1111-1111-1111-111111111111',other='22222222-2222-2222-2222-222222222222',camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const one=async sql=>(await db.query(sql)).rows[0];
async function probe(name,fn){
 await db.exec('begin');
 try {const evidence=await fn();results.push({name,...evidence});console.log(JSON.stringify(results.at(-1)));}
 catch(e){results.push({name,passed:false,error:e.message});console.log(JSON.stringify(results.at(-1)));}
 finally{await db.exec('rollback');}
}
try{
 for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d+.*\.sql$/.test(n)).sort()) await db.exec(read('supabase/migrations/'+name));
 await db.exec(`insert into auth.users(id,email)values('${owner}','owner@carry.audit'),('${other}','other@carry.audit');
 update public.profiles set created_at=now()-interval '10 days',credits=1000 where id in('${owner}','${other}');
 insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number,created_at)
 values('${camp}','${owner}','Owner','audit.carry.owner','open',1,now()-interval '4 days');
 insert into public.campaigns(owner_id,app_name,package_name,status,slot_number,created_at)
 values('${other}','Supply','audit.carry.supply','open',1,now()-interval '4 days');
 set test.allow_policy_retroactive_write='true';
 delete from public.daily_task_carryover_policies;
 select public.set_daily_task_carryover_policy((now() at time zone 'Asia/Ho_Chi_Minh')::date-6,true,'fixture');
 reset test.allow_policy_retroactive_write;`);
 const {today}=await one(`select (now() at time zone 'Asia/Ho_Chi_Minh')::date::text today`);
 await probe('history_manual_pause_must_not_rewrite_past_owner_day',async()=>{
   await db.exec(`insert into public.user_daily_tasks(user_id,task_date,carryover_version,carry_out_tests,finalized_at)
   values('${owner}',date '${today}'-2,1,8,now());`);
   const before=(await one(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`)).s;
   await db.exec(`update public.campaigns set status='paused',paused_reason='owner_manual' where id='${camp}'`);
   const after=(await one(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`)).s;
   // A safe implementation may defer an unprovable historical day instead of returning a guessed owner/member result.
   const passed=after.history_ready===false || (after.mode===before.mode && after.active_app_count===before.active_app_count);
   return {passed,before:{mode:before.mode,k:before.active_app_count},after:{mode:after.mode,k:after.active_app_count,history_ready:after.history_ready}};
 });
 await probe('disabled_policy_must_preserve_existing_debt',async()=>{
   // This case tests debt arithmetic on PROVEN history, independently of the
   // separate missing-provenance cases. Seed trusted synthetic observation
   // intervals for the fixture instead of asking production to invent history.
   await db.exec(`update public.system_provenance_markers
     set marker_time=((date '${today}'-3)::timestamp at time zone 'Asia/Ho_Chi_Minh'),marker_date=date '${today}'-3
     where marker_key='coverage_start';
     update public.campaign_status_journal set valid_from=((date '${today}'-3)::timestamp at time zone 'Asia/Ho_Chi_Minh') where valid_to is null;
     update public.user_role_journal set valid_from=((date '${today}'-3)::timestamp at time zone 'Asia/Ho_Chi_Minh') where valid_to is null;`);
   await db.exec(`insert into public.user_daily_tasks(user_id,task_date,carryover_version,carry_out_tests,finalized_at)
   values('${owner}',date '${today}'-2,1,8,now());
   set test.allow_policy_retroactive_write='true';
   select public.set_daily_task_carryover_policy(date '${today}'-1,false,'disable fixture');
   reset test.allow_policy_retroactive_write;
   select public.finalize_daily_tasks_for_date(date '${today}'-1);`);
   const row=await one(`select carry_in_tests,carry_out_tests,carryover_version,finalized_at is not null sealed
   from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-1`);
   return {passed:row?.sealed===true && row?.carry_out_tests===8,row,expectedCarryOut:8};
 });
 await probe('new_retroactive_policy_must_be_rejected',async()=>{
   await db.exec('savepoint policy_test');let error;
   try{await db.exec(`select public.set_daily_task_carryover_policy(date '${today}'-3,false,'new backdate')`);}
   catch(e){error=e.message;await db.exec('rollback to savepoint policy_test');}
   return {passed:!!error,error:error??null};
 });
 await probe('pending_source_does_not_seal_or_penalize',async()=>{
   await db.exec(`select public.finalize_daily_tasks_for_date(date '${today}'-1)`);
   const row=await one(`select finalized_at from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-1`);
   const p=await one(`select credits from public.profiles where id='${owner}'`);
   return {passed:!row?.finalized_at && p.credits===1000,row:row??null,credits:p.credits};
 });
}finally{await db.close();writeFileSync(new URL('probe-results.json',import.meta.url),JSON.stringify(results,null,2));}
if(process.argv.includes('--expect-fixed')) assert.ok(results.every(r=>r.passed),'Independent carryover probes have failures');
