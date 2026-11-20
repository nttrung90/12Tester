// Independent supervisor checks. Isolated PGlite only; no provider/production.
import assert from 'node:assert/strict';
import {readdirSync, readFileSync, writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {createDb} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb(), results=[];
const user='22222222-2222-2222-2222-222222222222',owner='11111111-1111-1111-1111-111111111111',camp='55555555-5555-5555-5555-555555555555',ref='aaaaaaaaaaaaaaaaaaaa';
const one=async(q,p)=>(await db.query(q,p)).rows[0];
const test=async(name,fn)=>{
 await db.exec('begin');
 try {const evidence=await fn();results.push({name,status:'PASS',evidence});}
 catch(e){results.push({name,status:'FAIL',error:e.message});}
 finally {await db.exec('rollback');}
};
const reserve=async(id,thumb=false)=>(await one('select public.request_report_media_reservation($1,$2,100,$3) r',[camp,id,thumb])).r;
const object=(path,size)=>db.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,jsonb_build_object('size',$3::int))",[path,user,size]);
const allowed=async(path,metadata)=>(await one("select public._can_insert_report_object('report-screenshots',$1,$2::jsonb) ok",[path,JSON.stringify(metadata)])).ok;
const reconcile=async()=>{
 const r=(await one('select public.reconcile_storage_organization_usage(1000000,0,$1::jsonb,true) r',[JSON.stringify([{project_ref:ref,bytes:'0',status:'SUCCESS',observed_at:new Date().toISOString()}])])).r;
 assert.equal(r.is_org_metric_valid,true);return r;
};
const accounting=async()=>{
 const a=await one("select used_storage_bytes::bigint::text u,active_reserved_bytes::bigint::text r,(select coalesce(sum((metadata->>'size')::bigint),0)::text from storage.objects) actual from public.storage_organization_usage where id=1");
 assert.ok(BigInt(a.u)+BigInt(a.r)>=BigInt(a.actual),'U+R undercounts live bytes: '+JSON.stringify(a));return a;
};
const probeRollback=async(path,size)=>{
 await db.exec('savepoint provider_permission');assert.equal(await allowed(path,{size}),true);await db.exec('rollback to savepoint provider_permission');
};
const submit=async(r)=>{
 const reply=(await one("select public.submit_tester_report_v3(p_submission_context:='app_test',p_campaign_id:=$1,p_content:='independent follow-up',p_rating:=5,p_client_submit_id:=$2::uuid,p_attachments:=jsonb_build_array(jsonb_build_object('screenshot_url',$3::text,'image_hash','1111222233334444','position',0))) r",[camp,randomUUID(),'https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/'+r.object_path])).r;
 assert.equal(reply.success,true,JSON.stringify(reply));return reply;
};
try {
 for(const f of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d+_.*\.sql$/.test(f)).sort())await db.exec(readFileSync(new URL('../../supabase/migrations/'+f,import.meta.url),'utf8'));
 await db.exec(`insert into auth.users(id,email) values('${owner}','owner@followup.invalid'),('${user}','tester@followup.invalid');insert into public.campaigns(id,owner_id,app_name,package_name,status) values('${camp}','${owner}','Followup','test.followup','open');insert into public.campaign_members(campaign_id,user_id) values('${camp}','${user}');select set_config('request.jwt.claim.sub','${user}',false);insert into public.storage_trusted_hosts(host) values('example.supabase.co');update public.storage_organization_usage set local_project_ref='${ref}',total_capacity_bytes=1000000,used_storage_bytes=0,active_reserved_bytes=0,is_org_metric_valid=true,last_reconciled_at=now(),dispatch_mode='supabase_preferred' where id=1;grant usage on schema storage to authenticated;grant insert,select on storage.objects to authenticated;`);
 for(const [name,metadata,expected] of [
  ['contentLength cap',{contentLength:100,mimetype:'image/jpeg'},true],
  ['size cap',{size:100},true],
  ['zero length',{contentLength:0},false],
  ['negative length',{contentLength:-1},false],
  ['fractional length',{contentLength:99.5},false],
  ['boolean length',{contentLength:true},false],
  ['null length',{contentLength:null},false],
  ['empty length',{contentLength:''},false],
  ['oversize length',{contentLength:101},false],
  ['huge length',{contentLength:'999999999999999999999999999999999999999999999999'},false],
  ['unknown length',{mimetype:'image/jpeg'},false],
 ])await test(name,async()=>{const r=await reserve('metadata');assert.equal(await allowed(r.object_path,metadata),expected);});
 await test('contentLength reaches authenticated RLS INSERT',async()=>{
  const r=await reserve('actual-rls');await db.exec('set local role authenticated');
  await db.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"contentLength\":100,\"mimetype\":\"image/jpeg\"}')",[r.object_path,user]);
  await db.exec('reset role');
 });
 await test('contentLength does not bypass owner or exact path',async()=>{
  const r=await reserve('owner-path',true);
  assert.equal(await allowed(r.object_path.replace('/owner-path.jpg','/unreserved.jpg'),{contentLength:100}),false);
  await db.query("select set_config('request.jwt.claim.sub',$1,true)",[owner]);
  assert.equal(await allowed(r.object_path,{contentLength:100}),false);
 });
 await test('contentLength does not bypass expiry or bucket',async()=>{
  const r=await reserve('expiry');
  assert.equal((await one("select public._can_insert_report_object('avatar',$1,'{\"contentLength\":100}') ok",[r.object_path])).ok,false);
  await db.exec("update public.report_media_uploads set reservation_expires_at=now()-interval '1 second'");
  assert.equal(await allowed(r.object_path,{contentLength:100}),false);
 });
 await test('thumbnail contentLength uses its own strict cap',async()=>{
  const r=await reserve('thumb-cap',true);
  assert.equal(await allowed(r.thumbnail_object_path,{contentLength:150000}),true);
  assert.equal(await allowed(r.thumbnail_object_path,{contentLength:150001}),false);
 });
 await test('authenticated client retains reservation RPC permission',async()=>{
  assert.equal((await one("select has_function_privilege('authenticated','public.request_report_media_reservation(uuid,text,bigint,boolean,uuid)','execute') ok")).ok,true);
 });
 await test('reconcile global R equals newly updated ledger holds',async()=>{
  const r=await reserve('cte-snapshot',true);await object(r.object_path,100);await reconcile();
  const q=await one("select active_reserved_bytes::text actual_r,(select coalesce(sum(reserved_bytes),0)::text from public.report_media_uploads where provider='supabase') expected_r from public.storage_organization_usage");
  assert.equal(q.actual_r,q.expected_r);return q;
 });
 await test('preflight contentLength is not accepted as final authoritative object size',async()=>{
  const r=await reserve('not-final-size');
  await db.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"contentLength\":100}')",[r.object_path,user]);
  const metric=[{project_ref:ref,bytes:'100',status:'SUCCESS',observed_at:new Date().toISOString()}];
  const ack=(await one('select public.reconcile_storage_organization_usage(1000000,100,$1::jsonb,true) r',[JSON.stringify(metric)])).r;
  assert.equal(ack.is_org_metric_valid,false,'missing actual size must fail safe, not use declared preflight length');
 });
 await test('finalized missing thumbnail remains covered after repeated reconciliation and expiry',async()=>{
  const r=await reserve('finalize-delayed',true);await object(r.object_path,100);await probeRollback(r.thumbnail_object_path,50);
  await submit(r);const afterSubmit=await accounting();await reconcile();const beforeExpiry=await accounting();
  await db.exec("update public.report_media_uploads set reservation_expires_at=now()-interval '1 hour'");
  await reconcile();await reconcile();const beforeLateWrite=await accounting();await object(r.thumbnail_object_path,50);const afterLateWrite=await accounting();await reconcile();const afterReconcile=await accounting();
  return {afterSubmit,beforeExpiry,beforeLateWrite,afterLateWrite,afterReconcile};
 });
 await test('maximum-size delayed thumbnail before first reconcile retains unmeasured main bytes',async()=>{
  const r=await reserve('max-before-reconcile',true);await object(r.object_path,100);await probeRollback(r.thumbnail_object_path,150000);
  await submit(r);await object(r.thumbnail_object_path,150000);return accounting();
 });
 await test('cleanup absence ACK cannot erase capacity for an already authorized delayed write',async()=>{
  const r=await reserve('after-delete',true);await probeRollback(r.thumbnail_object_path,50);
  await db.exec("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour'");
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;
  const j=jobs.find(x=>x.id===r.reservation_id);assert.ok(j,'missing cleanup lease');
  const ack=(await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path].filter(Boolean)])).r;
  assert.equal(ack.success,true,JSON.stringify(ack));
  const beforeWrite=await accounting();await object(r.thumbnail_object_path,50);const afterWrite=await accounting();
  await reconcile();const afterReconcile=await accounting();
  const retry=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;
  assert.ok(retry.some(x=>x.object_path===r.object_path || x.thumbnail_object_path===r.thumbnail_object_path),'cleanup cannot rediscover object after tombstone');
  return {beforeWrite,afterWrite,afterReconcile};
 });
 await test('accounted main deletion cannot erase pending thumbnail hold',async()=>{
  const r=await reserve('accounted-delete',true);await object(r.object_path,100);await reconcile();await probeRollback(r.thumbnail_object_path,50);
  await db.exec("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour'");
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;const j=jobs.find(x=>x.id===r.reservation_id);assert.ok(j);
  await db.query('delete from storage.objects where name=$1',[r.object_path]);
  assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path]])).r.success,true);
  await object(r.thumbnail_object_path,50);const afterLateWrite=await accounting();await reconcile();const afterReconcile=await accounting();return {afterLateWrite,afterReconcile};
 });
 await test('confirmed complete pair releases quota after physical deletion and stays released',async()=>{
  const r=await reserve('complete-release',true);await object(r.object_path,100);await object(r.thumbnail_object_path,50);await reconcile();
  await db.exec("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour'");
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;const j=jobs.find(x=>x.id===r.reservation_id);assert.ok(j);
  await db.query('delete from storage.objects where name=any($1::text[])',[[r.object_path,r.thumbnail_object_path]]);
  assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path]])).r.success,true);
  const afterAck=await accounting();assert.equal(BigInt(afterAck.u)+BigInt(afterAck.r),0n);
  await reconcile();const afterReconcile=await accounting();assert.equal(BigInt(afterReconcile.u)+BigInt(afterReconcile.r),0n);return {afterAck,afterReconcile};
 });
 await test('cleanup observes complete pair before first reconcile and releases its hold after deletion',async()=>{
  const r=await reserve('cleanup-before-reconcile',true);await object(r.object_path,100);await object(r.thumbnail_object_path,50);
  await db.exec("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour'");
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;const j=jobs.find(x=>x.id===r.reservation_id);assert.ok(j);
  await db.query('delete from storage.objects where name=any($1::text[])',[[r.object_path,r.thumbnail_object_path]]);
  assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path]])).r.success,true);
  const afterAck=await accounting();assert.equal(BigInt(afterAck.u)+BigInt(afterAck.r),0n,'actual complete pair was deleted, budget must be reclaimed');
  await reconcile();const afterReconcile=await accounting();assert.equal(BigInt(afterReconcile.u)+BigInt(afterReconcile.r),0n);return {afterAck,afterReconcile};
 });
 await test('resurrected cleanup does not count the previously deleted main bytes again',async()=>{
  const r=await reserve('delete-byte-counter',true);await object(r.object_path,100);await reconcile();
  await db.exec("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour'");
  const claim=async()=>(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r.find(x=>x.id===r.reservation_id);
  const ack=async(j)=>(await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path]])).r;
  const first=await claim();assert.ok(first);await db.query('delete from storage.objects where name=$1',[r.object_path]);
  const firstAck=await ack(first);assert.equal(firstAck.success,true);assert.equal(firstAck.bytes_freed,100);
  await object(r.thumbnail_object_path,50);await reconcile();
  const second=await claim();assert.ok(second);await db.query('delete from storage.objects where name=$1',[r.thumbnail_object_path]);
  const secondAck=await ack(second);assert.equal(secondAck.success,true);
  assert.equal(secondAck.bytes_freed,50,'bytes_freed must describe current deletion, not include main bytes from previous lease');
  return {firstBytes:firstAck.bytes_freed,secondBytes:secondAck.bytes_freed};
 });
 await test('thumbnail-first cleanup retains the larger pending main capacity',async()=>{
  await db.exec('update public.storage_organization_usage set total_capacity_bytes=10000000');
  const r=(await one('select public.request_report_media_reservation($1,$2,2000000,true) r',[camp,'thumbnail-first'])).r;assert.equal(r.provider,'supabase');
  await object(r.thumbnail_object_path,50);
  const metric=[{project_ref:ref,bytes:'50',status:'SUCCESS',observed_at:new Date().toISOString()}];
  assert.equal((await one('select public.reconcile_storage_organization_usage(10000000,50,$1::jsonb,true) r',[JSON.stringify(metric)])).r.is_org_metric_valid,true);
  await probeRollback(r.object_path,2000000);
  await db.exec("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour'");
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;const j=jobs.find(x=>x.id===r.reservation_id);assert.ok(j);
  await db.query('delete from storage.objects where name=$1',[r.thumbnail_object_path]);
  assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path]])).r.success,true);
  await object(r.object_path,2000000);return accounting();
 });
 await test('unrelated report submission preserves tombstone hold in global R',async()=>{
  const pending=await reserve('retired-pending',true);await probeRollback(pending.thumbnail_object_path,50);
  await db.query("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour' where id=$1",[pending.reservation_id]);
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;const j=jobs.find(x=>x.id===pending.reservation_id);assert.ok(j);
  assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path]])).r.success,true);
  const unrelated=await reserve('unrelated');await object(unrelated.object_path,100);await submit(unrelated);
  await object(pending.thumbnail_object_path,50);return accounting();
 });
 await test('resurrected old object never receives a later physical deletion deadline',async()=>{
  const r=await reserve('deadline-pending',true);
  await db.query("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour',delete_deadline_at=now()+interval '2 hours' where id=$1",[r.reservation_id]);
  const original=(await one('select delete_deadline_at from public.report_media_uploads where id=$1',[r.reservation_id])).delete_deadline_at;
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;const j=jobs.find(x=>x.id===r.reservation_id);assert.ok(j);
  assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path]])).r.success,true);
  await object(r.thumbnail_object_path,50);await db.query("update storage.objects set created_at=now()-interval '238 hours' where name=$1",[r.thumbnail_object_path]);
  const retry=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;
  const revived=retry.find(x=>x.id===r.reservation_id);assert.ok(revived);
  assert.ok(new Date(revived.delete_deadline_at)<=new Date(original),'cleanup extended original deletion deadline');
  assert.ok(new Date(revived.delete_deadline_at).getTime()<=Date.now()+2*3600000+1000,'cleanup extended real object 240h deadline');
 });
} finally {await db.close();}
const report={at:new Date().toISOString(),scope:'Independent isolated PGlite metadata/event-order checks, not actual provider or concurrent sessions',results};
writeFileSync(new URL('supervisor-followup-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));process.exitCode=results.some(x=>x.status!=='PASS')?1:0;
