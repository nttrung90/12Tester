import assert from 'node:assert/strict';
import {createDb,read} from '../tools/baseline-lib.mjs';
const owner='11111111-1111-1111-1111-111111111111',user='22222222-2222-2222-2222-222222222222',camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',id='cccccccc-cccc-cccc-cccc-cccccccccccc',id2='dddddddd-dddd-dddd-dddd-dddddddddddd';
for(const schema of ['public','extensions']) for(const role of ['member','admin']) for(const context of ['app_test','mission']) {
 const db=await createDb();
 try {
  await db.exec(`create schema if not exists ${schema}; create extension pgcrypto with schema ${schema}`);
  await db.exec(read('supabase/migrations/062_consolidated_baseline.sql'));
  await db.exec(read('supabase/migrations/063_report_crypto_search_path.sql'));
  await db.exec(read('supabase/migrations/064_explicit_report_context.sql'));
  await db.exec(read('supabase/migrations/065_remove_dhash_hardblock.sql'));
  await db.exec(`insert into auth.users(id,email) values ('${owner}','owner@context.test'),('${user}','user@context.test');
   update public.profiles set credits=10000 where id='${owner}'; update public.profiles set role='${role}' where id='${user}';
   insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values('${camp}','${owner}','com.context.test','Context','open',1);
   insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
   values('${mission}','${owner}','${camp}','Mission',10,3,20,'open',now()+interval '2 days');
   insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${user}','joined');
   select set_config('request.jwt.claim.sub','${user}',false);set role authenticated;`);
  const call=async(ctx=context,request=id,legacy=false)=> (await db.query(`select public.${legacy?'submit_tester_report':'submit_tester_report_v2'}(
   ${legacy?'':`p_submission_context=>'${ctx}',`}p_campaign_id=>'${camp}',p_content=>'Daily test',p_client_submit_id=>'${request}',
   p_screenshot_url=>'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp}%2F${user}%2F${id}.jpg?alt=media&token=fixture',
   p_image_hash=>'0123456789abcdef'${ctx==='mission'?`,p_mission_id=>'${mission}'`:''}) as result`)).rows[0].result;
  await assert.rejects(call('mission',id,true),/REPORT_CLIENT_UPDATE_REQUIRED/);
  await assert.rejects(db.query(`select public.submit_tester_report_internal_v2('${camp}')`),/permission denied/);
  await assert.rejects(db.query(`select public.submit_tester_report_v2('mission','${camp}',p_client_submit_id=>'${id}')`),/INVALID_REPORT_CONTEXT/);
  if(context==='app_test' && role==='admin') await db.exec(`reset role;update public.missions set status='stopped_no_credits' where id='${mission}';set role authenticated;`);
  if(context==='mission') await db.exec(`reset role;update public.missions set status='full' where id='${mission}';set role authenticated;`);
  const result=await call();assert.equal(result.submission_context,context);assert.equal(result.provisional_amount,10);
  assert.deepEqual(await call(),result);
  await assert.rejects(call(context==='mission'?'app_test':'mission',id2),/REPORT_CONTEXT_CONFLICT/);
  await assert.rejects(call(context==='mission'?'app_test':'mission'),/CLIENT_SUBMIT_ID/);
  const receipt=(await db.query(`select public.reconcile_legacy_report('${id}') as result`)).rows[0].result;
  assert.deepEqual(receipt,result);
  const cancelled=(await db.query(`select public.reconcile_legacy_report('${id2}') as result`)).rows[0].result;assert.equal(cancelled.success,false);
  await assert.rejects(call(context,id2),/REPORT_REQUEST_REQUIRES_REVIEW/);
  await db.exec(`reset role;update public.missions set status='paused_by_creator' where id='${mission}';set role authenticated;`);
  assert.deepEqual(await call(),result,'Replay survives mission pause');
  assert.equal((await db.query(`select count(*)::int as n from public.tester_reports`)).rows[0].n,1);
  console.log(`PASS ${schema}/${role}/${context}: context, credits, replay, conflict, ACL, reconcile and tombstone`);
 } catch(e){console.error(e.message,e.code);process.exitCode=1;break;} finally {await db.close();}
}
