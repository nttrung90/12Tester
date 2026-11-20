import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
import {writeFileSync} from 'node:fs';
const db=await createDb(),results=[];
const owner='11111111-1111-1111-1111-111111111111',other='22222222-2222-2222-2222-222222222222',camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',supply='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const one=async q=>(await db.query(q)).rows[0];
try {
 for(const m of ['062_consolidated_baseline.sql','063_report_crypto_search_path.sql','064_explicit_report_context.sql','065_remove_dhash_hardblock.sql']) await db.exec(read('supabase/migrations/'+m));
 await db.exec(`insert into auth.users(id,email) values('${owner}','owner@provenance.test'),('${other}','other@provenance.test');
 update public.profiles set credits=1000,created_at=now()-interval '10 days' where id in('${owner}','${other}');
 insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number,created_at)
 values('${camp}','${owner}','Owner','provenance.owner','paused',1,now()-interval '4 days'),
 ('${supply}','${other}','Supply','provenance.supply','open',1,now()-interval '4 days');`);
 await db.exec(read('supabase/migrations/066_push_and_carryover_hardening.sql'));
 const {today}=await one(`select (now() at time zone 'Asia/Ho_Chi_Minh')::date::text today`);
 const first=(await one(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`)).s;
 results.push({name:'migration_must_not_backdate_current_state_as_proven_history',passed:first.history_ready===false,evidence:first});
 await db.exec('begin');
 try {
  await db.exec(`insert into public.checkins(user_id,campaign_id,checkin_date,created_at) values('${owner}','${supply}',date '${today}'-1,now()-interval '1 day')`);
  const before=(await one(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`)).s;
  await db.exec(`delete from public.campaigns where id='${supply}'`);
  const after=(await one(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`)).s;
  results.push({name:'deleted_campaign_must_not_remove_proven_past_tests',passed:after.history_ready===false || after.completed_tests===before.completed_tests,beforeD:before.completed_tests,afterD:after.completed_tests,historyReady:after.history_ready});
 } finally {await db.exec('rollback');}
 await db.exec('begin');
 try {
  await db.exec(`set test.allow_policy_retroactive_write='true';
   insert into public.daily_task_carryover_policies(effective_from,is_enabled,operator_note)
   values(date '${today}'-100,false,'audit historical row') on conflict(effective_from) do nothing;
   reset test.allow_policy_retroactive_write; savepoint update_policy;`);
  let rejected=false;
  try {await db.exec(`update public.daily_task_carryover_policies set effective_from=date '${today}'+100 where effective_from=date '${today}'-100`);}
  catch {rejected=true;await db.exec('rollback to savepoint update_policy');}
  results.push({name:'moving_historical_policy_to_future_must_be_rejected',passed:rejected});
 } finally {await db.exec('rollback');}
} finally {await db.close();writeFileSync(new URL('provenance-results.json',import.meta.url),JSON.stringify(results,null,2));}
for(const r of results)console.log(JSON.stringify(r));
if(results.some(r=>!r.passed))process.exitCode=1;
