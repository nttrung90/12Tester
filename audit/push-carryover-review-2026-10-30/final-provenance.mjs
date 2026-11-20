import assert from 'node:assert/strict';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
import {writeFileSync} from 'node:fs';
const db=await createDb(),results=[];
const owner='11111111-1111-1111-1111-111111111111',other='22222222-2222-2222-2222-222222222222',camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',supply='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const one=async q=>(await db.query(q)).rows[0];
async function test(name,fn){await db.exec('begin');try{await fn();results.push({name,passed:true});}catch(e){results.push({name,passed:false,error:e.message});}finally{await db.exec('rollback');}console.log(JSON.stringify(results.at(-1)));}
try {
 for(const m of ['062_consolidated_baseline.sql','063_report_crypto_search_path.sql','064_explicit_report_context.sql','065_remove_dhash_hardblock.sql']) await db.exec(read('supabase/migrations/'+m));
 await db.exec(`insert into auth.users(id,email) values('${owner}','owner@finalprovenance.test'),('${other}','other@finalprovenance.test');
 update public.profiles set credits=1000,created_at=now()-interval '10 days' where id in('${owner}','${other}');
 insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number,created_at)
 values('${camp}','${owner}','Owner','finalprovenance.owner','open',1,now()-interval '4 days'),
 ('${supply}','${other}','Supply','finalprovenance.supply','open',1,now()-interval '4 days');`);
 await db.exec(read('supabase/migrations/066_push_and_carryover_hardening.sql'));
 const {today}=await one(`select (now() at time zone 'Asia/Ho_Chi_Minh')::date::text today`);
 await test('current_open_state_cannot_prove_pre_migration_history',async()=>{
  const s=(await one(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`)).s;
  assert.equal(s.history_ready,false,'Open observed today is not proof of yesterday');
 });
 await test('rerun_does_not_reset_provenance_coverage',async()=>{
  const before=await one(`select marker_time::text t,marker_date::text d from public.system_provenance_markers where marker_key='coverage_start'`);
  await db.exec(read('supabase/migrations/066_push_and_carryover_hardening.sql'));
  const after=await one(`select marker_time::text t,marker_date::text d from public.system_provenance_markers where marker_key='coverage_start'`);
  assert.deepEqual(after,before);
 });
 await test('promotion_today_cannot_exempt_unproven_yesterday',async()=>{
  await db.exec(`update public.profiles set role='admin' where id='${owner}'`);
  const s=(await one(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`)).s;
  assert.ok(s.history_ready===false || s.is_penalty_exempt===false);
 });
 await test('removing_same_day_direct_checkin_removes_its_credit_toward_goal',async()=>{
  await db.exec(`insert into public.checkins(user_id,campaign_id,checkin_date) values('${owner}','${supply}',date '${today}')`);
  assert.equal((await one(`select public.get_daily_task_status('${owner}',date '${today}') s`)).s.completed_tests,1);
  await db.exec(`delete from public.checkins where user_id='${owner}' and campaign_id='${supply}' and checkin_date=date '${today}'`);
  assert.equal((await one(`select public.get_daily_task_status('${owner}',date '${today}') s`)).s.completed_tests,0);
 });
 await test('any_qualifying_report_still_counts_after_campaign_delete',async()=>{
  await db.exec(`insert into public.tester_reports(id,campaign_id,user_id,content,status,created_at) values
   ('cccccccc-cccc-cccc-cccc-cccccccccccc','${supply}','${owner}','qualifying','pending',now()),
   ('dddddddd-dddd-dddd-dddd-dddddddddddd','${supply}','${owner}','rejected','rejected',now());`);
  assert.equal((await one(`select public.get_daily_task_status('${owner}',date '${today}') s`)).s.completed_tests,1);
  await db.exec(`delete from public.campaigns where id='${supply}'`);
  assert.equal((await one(`select public.get_daily_task_status('${owner}',date '${today}') s`)).s.completed_tests,1);
 });
 await test('trusted_legacy_checkpoint_can_be_written_and_audited_without_debit',async()=>{
  await db.exec(`update public.profiles set daily_missed_streak=7 where id='${owner}'`);
  await db.exec(`select public.reconcile_legacy_daily_task_checkpoint('${owner}',date '${today}'-1,'owner',1,8,8,'Independent verified fixture balance')`);
  const row=await one(`select carry_in_tests,carry_out_tests,missed_streak,finalized_at is not null sealed from public.user_daily_tasks
   where user_id='${owner}' and task_date=date '${today}'-1`);
  assert.equal(row.carry_out_tests,8);assert.equal(row.sealed,true);
  assert.equal(row.missed_streak,7,'A recovery anchor must not silently forgive the known existing missed streak');
  assert.equal((await one(`select credits from public.profiles where id='${owner}'`)).credits,1000);
  assert.ok(Number((await one(`select count(*) n from public.daily_task_carryover_policy_audit where new_operator_note like '%Independent verified fixture balance%'`)).n)>0);
 });
 await test('legacy_checkpoint_requires_explicit_values_and_audit_note',async()=>{
  await assert.rejects(db.exec(`select public.reconcile_legacy_daily_task_checkpoint('${owner}',date '${today}'-1,'owner',1,null,0,'')`),
   /INVALID|REQUIRED|CHECKPOINT|NOTE|NULL/i,'Null source debt must not silently turn into zero');
 });
}finally{await db.close();writeFileSync(new URL('final-provenance-results.json',import.meta.url),JSON.stringify(results,null,2));}
if(results.some(r=>!r.passed))process.exitCode=1;
