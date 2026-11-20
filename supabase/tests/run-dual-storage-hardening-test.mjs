import assert from 'node:assert/strict';
import {readdirSync,readFileSync,writeFileSync} from 'node:fs';
import {createDb} from '../tools/baseline-lib.mjs';
const db=await createDb();
const user='22222222-2222-2222-2222-222222222222',owner='11111111-1111-1111-1111-111111111111',camp='55555555-5555-5555-5555-555555555555';
const ref='aaaaaaaaaaaaaaaaaaaa',other='bbbbbbbbbbbbbbbbbbbb';
const results=[];
const one=async(q,p)=>(await db.query(q,p)).rows[0];
const reserve=async(id='photo',thumb=false)=>(await one("select public.request_report_media_reservation($1,$2,100,$3) r",[camp,id,thumb])).r;
const object=async(path,bytes=100,age='0 hours')=>db.query("insert into storage.objects(bucket_id,name,owner,metadata,created_at) values('report-screenshots',$1,$2,jsonb_build_object('size',$3::int),now()-$4::interval)",[path,user,bytes,age]);
const reconcile=async(local,remote=200,valid=true)=>(await one("select public.reconcile_storage_organization_usage(1000000,$1,$2::jsonb,$3) r",[local+remote,JSON.stringify([{project_ref:ref,bytes:String(local),status:'SUCCESS',observed_at:new Date().toISOString()},{project_ref:other,bytes:String(remote),status:'SUCCESS',observed_at:new Date().toISOString()}]),valid])).r;
const usage=()=>one('select used_storage_bytes::int u,active_reserved_bytes::int r,is_org_metric_valid valid from public.storage_organization_usage where id=1');
const finalize=async(r)=> {
 const url='https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/'+r.object_path;
 const data=(await one("select public._prepare_report_media($1,null,$2,$3,null) r",[url,camp,user])).r;
 await db.query("select public._finalize_report_media($1::jsonb,null,null)",[JSON.stringify(data)]);
 // Public core performs this aggregate after the private finalize helper.
 await db.exec("update public.storage_organization_usage set active_reserved_bytes=(select coalesce(sum(reserved_bytes),0) from public.report_media_uploads where provider='supabase' and status<>'deleted')");
};
const submit=async(url)=> (await one("select public.submit_tester_report_v3(p_submission_context:='app_test',p_campaign_id:=$1,p_content:='media fixture',p_rating:=5,p_client_submit_id:='00000000-0000-0000-0000-000000000099',p_attachments:=jsonb_build_array(jsonb_build_object('screenshot_url',$2::text,'image_hash','1111222233334444','position',0))) r",[camp,url])).r;
const configureGateway=()=>db.exec("select set_config('app.settings.media_gateway_secret','supervisor_fixture_secret_longer_than_32_chars',false);select set_config('app.settings.media_gateway_base_url','https://gateway.fixture.invalid/media',false)");
const test=async(name,fn)=>{
 await db.exec('begin');
 try {await fn();results.push({name,status:'PASS'});}
 catch(e){results.push({name,status:'FAIL',error:e.message});}
 finally{await db.exec('rollback');}
};
try {
 for(const f of readdirSync(new URL('../migrations/',import.meta.url)).filter(f=>/^\d+_.*\.sql$/.test(f)).sort())
  await db.exec(readFileSync(new URL('../migrations/'+f,import.meta.url),'utf8'));
 await db.exec("insert into auth.users(id,email) values('"+owner+"','owner@hardening.invalid'),('"+user+"','tester@hardening.invalid');insert into public.campaigns(id,owner_id,app_name,package_name,status) values('"+camp+"','"+owner+"','Hardening','test.hardening','open');insert into public.campaign_members(campaign_id,user_id) values('"+camp+"','"+user+"');select set_config('request.jwt.claim.sub','"+user+"',false);insert into public.storage_trusted_hosts(host) values('example.supabase.co');update public.storage_organization_usage set local_project_ref='"+ref+"',total_capacity_bytes=1000000,used_storage_bytes=0,active_reserved_bytes=0,is_org_metric_valid=true,last_reconciled_at=now(),dispatch_mode='supabase_preferred' where id=1;");
 await test('reconcile then finalize does not double count',async()=>{
  const r=await reserve();await object(r.object_path);await reconcile(100);assert.deepEqual(await usage(),{u:300,r:0,valid:true});
  await finalize(r);assert.deepEqual(await usage(),{u:300,r:0,valid:true});
 });
 await test('finalize then reconcile preserves unobserved bytes',async()=>{
  const r=await reserve();await object(r.object_path);await finalize(r);assert.deepEqual(await usage(),{u:0,r:100,valid:true});
  await reconcile(0);assert.deepEqual(await usage(),{u:300,r:0,valid:true});
  await reconcile(100);assert.deepEqual(await usage(),{u:300,r:0,valid:true});
 });
 await test('partial pair snapshot holds remaining thumbnail capacity',async()=>{
  const r=await reserve('pair',true);await object(r.object_path);await reconcile(100);
  assert.deepEqual(await usage(),{u:300,r:150000,valid:true});
  await object(r.thumbnail_object_path,50);await reconcile(150);
  // 093 monotonic per-path completion design:
  // When thumbnail (50) is also committed and counted in U (350), both paths are complete: hold is released to R = 0.
  assert.deepEqual(await usage(),{u:350,r:0,valid:true});
  const flags=await one('select main_committed, thumbnail_committed from public.report_media_uploads where id=$1',[r.reservation_id]);
  assert.equal(flags.main_committed,true);
  assert.equal(flags.thumbnail_committed,true);
  // Repeated reconcile preserves R = 0 without resurrecting phantom holds.
  await reconcile(150);
  assert.deepEqual(await usage(),{u:350,r:0,valid:true});
 });
 await test('stale local metric replaced by current metadata',async()=>{
  const r=await reserve();await object(r.object_path);await reconcile(999);
  assert.equal((await usage()).u,300);
 });
 await test('invalid reconcile preserves prior measured U and B',async()=>{
  await reconcile(0);const before=await usage();
  await db.query("select public.reconcile_storage_organization_usage(null,null,'[]',false)");
  const after=await usage();assert.equal(after.u,before.u);assert.equal(after.valid,false);
  assert.equal((await reserve()).provider,'gcs');
 });
 await test('expired pinned reservation never renewed or moved',async()=>{
  const a=await reserve();await db.exec("update public.report_media_uploads set reservation_expires_at=now()-interval '1 second'");
  const b=await reserve();assert.equal(b.upload_expired,true);assert.equal(b.provider,a.provider);assert.equal(b.object_path,a.object_path);
  assert.equal((await one("select public._can_insert_report_object('report-screenshots',$1,'{\"size\":100}') v",[a.object_path])).v,false);
 });
 await test('exact 95 percent enters overflow; exact 90 does not recover',async()=>{
  await db.exec("update public.storage_organization_usage set used_storage_bytes=950000,dispatch_mode='supabase_preferred'");
  assert.equal((await reserve('ninety-five')).provider,'gcs');
  await db.exec('update public.storage_organization_usage set used_storage_bytes=900000');assert.equal((await reserve('ninety')).provider,'gcs');
  await db.exec('update public.storage_organization_usage set used_storage_bytes=899999');assert.equal((await reserve('below-ninety')).provider,'supabase');
 });
 await test('reservation reaching threshold remembers overflow until below ninety',async()=>{
  await db.exec("update public.storage_organization_usage set used_storage_bytes=949900,dispatch_mode='supabase_preferred'");
  const r=await reserve();assert.equal(r.provider,'supabase');assert.equal(r.dispatch_mode,'google_overflow');
  await db.exec("update public.report_media_uploads set reserved_bytes=0,status='deleted';update public.storage_organization_usage set used_storage_bytes=920000,active_reserved_bytes=0");
  assert.equal((await reserve('after-threshold')).provider,'gcs');
 });
 await test('oversized storage insert rejected by exact reservation cap',async()=>{
  const r=await reserve();
  assert.equal((await one("select public._can_insert_report_object('report-screenshots',$1,'{\"size\":101}') v",[r.object_path])).v,false);
  assert.equal((await one("select public._can_insert_report_object('report-screenshots',$1,'{\"size\":100}') v",[r.object_path])).v,true);
 });
 await test('orphan inventory recovered with actual original clock',async()=>{
  const path='reports/'+camp+'/'+user+'/untracked.jpg';await object(path,100,'217 hours');
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(10,300,false) r')).r;
  assert.equal(jobs.length,1);assert.equal(jobs[0].object_path,path);assert.ok(jobs[0].lease_token);
  assert.equal(jobs[0].status,'deleting');
  assert.ok(new Date(jobs[0].delete_deadline_at).getTime()-Date.now()<24*3600000);
 });
 await test('dry run is read only even with an untracked object',async()=>{
  await object('reports/'+camp+'/'+user+'/orphan.jpg',100,'217 hours');
  await db.exec('set transaction read only');await one('select public.get_expired_report_media_for_cleanup(10,300,true) r');
  assert.equal(Number((await one('select count(*) n from public.report_media_uploads')).n),0);
 });
 await test('untracked old thumbnail cannot hide behind a younger main file',async()=>{
  const prefix='reports/'+camp+'/'+user+'/';await object(prefix+'pair.jpg',100);await object(prefix+'thumb_pair.jpg',50,'217 hours');
  const jobs=(await one('select public.get_expired_report_media_for_cleanup(10,300,false) r')).r;
  assert.equal(jobs.length,1);assert.equal(jobs[0].object_path,prefix+'pair.jpg');assert.equal(jobs[0].thumbnail_object_path,prefix+'thumb_pair.jpg');
  assert.ok(new Date(jobs[0].view_expires_at).getTime()<=Date.now());
 });
 await test('legacy acknowledgement never mutates Google ledger',async()=>{
  await db.exec('update public.storage_organization_usage set is_org_metric_valid=false');const r=await reserve('google');assert.equal(r.provider,'gcs');
  const ack=(await one("select public.record_report_media_deleted('google',999) r")).r;assert.equal(ack.success,false);
  assert.equal((await one('select status from public.report_media_uploads where id=$1',[r.reservation_id])).status,'reserved');
 });
 await test('ack rejects stale token and provider objects still present',async()=>{
  const r=await reserve();await object(r.object_path);await db.exec("update public.report_media_uploads set status='expired'");
  const job=(await one('select public.get_expired_report_media_for_cleanup(1,300,false) r')).r[0];
  const ack=async(token)=>(await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[job.id,token,[job.object_path]])).r;
  assert.equal((await ack('00000000-0000-0000-0000-000000000001')).reason,'LEASE_MISMATCH');
  assert.equal((await ack(job.lease_token)).reason,'OBJECTS_STILL_PRESENT');
 });
 await test('service worker RPCs inaccessible to regular users',async()=>{
  for(const signature of ['get_expired_report_media_for_cleanup(integer,integer,boolean)','record_report_media_deleted(uuid,uuid,text[])','reconcile_storage_organization_usage(bigint,bigint,jsonb,boolean)'])
   assert.equal((await one('select has_function_privilege($1,$2,$3) v',['authenticated','public.'+signature,'execute'])).v,false);
 });
 await test('deleted owner does not destroy cleanup ledger',async()=>{
  await reserve();await db.query('delete from auth.users where id=$1',[user]);
  const row=await one('select user_id,status from public.report_media_uploads');assert.equal(row.user_id,null);assert.equal(row.status,'reserved');
 });
 await test('legacy Supabase clock cannot extend gateway grant',async()=>{
  const r=await reserve();await object(r.object_path);const report=await submit('https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/'+r.object_path);
  await db.query("update storage.objects set created_at=now()-interval '217 hours' where name=$1",[r.object_path]);
  await configureGateway();
  await assert.rejects(db.query('select public.request_report_media_access($1)',[report.report_id]),/MEDIA_EXPIRED/);
 });
 await test('Google name containing supabase keeps thirty day grant',async()=>{
  const report=await submit('https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/'+camp+'/'+user+'/supabase.jpg');
  await db.query("update public.tester_reports set created_at=now()-interval '10 days',view_expires_at=now()+interval '20 days' where id=$1",[report.report_id]);
  await configureGateway();const grant=(await one('select public.request_report_media_access($1) r',[report.report_id])).r;assert.equal(grant.success,true);
 });
 await test('admin author signs and verifies the same role',async()=>{
  const report=await submit('https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/'+camp+'/'+user+'/photo.jpg');
  await db.query("update public.profiles set role='admin' where id=$1",[user]);await configureGateway();
  const g=(await one('select public.request_report_media_access($1) r',[report.report_id])).r;
  const token=new URL(g.screenshot_url).searchParams.get('grant_token');
  assert.equal((await one("select public.verify_media_access_grant_internal($1,$2,$3,'full',$4,null) r",[report.report_id,user,token,g.expires_epoch])).r,true);
 });
 await test('cleanup of old file cannot expire a replacement attachment',async()=>{
  const r=await reserve();await object(r.object_path);const report=await submit('https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/'+r.object_path);
  await db.query("update public.tester_report_attachments set screenshot_url=$1,storage_provider='gcs',media_expired=false where report_id=$2",['https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/'+camp+'/'+user+'/replacement.jpg',report.report_id]);
  await db.exec("update public.report_media_uploads set status='expired'");
  const job=(await one('select public.get_expired_report_media_for_cleanup(1,300,false) r')).r[0];
  await db.query('delete from storage.objects where name=$1',[r.object_path]);
  assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[job.id,job.lease_token,[job.object_path]])).r.success,true);
  assert.equal((await one('select media_expired from public.tester_report_attachments where report_id=$1',[report.report_id])).media_expired,false);
 });
} finally {await db.close();}
writeFileSync(new URL('../../audit/dual-storage-review-2026-11-16/supervisor-sql-results.json',import.meta.url),JSON.stringify({scope:'isolated PGlite, metadata fixture; no provider or PostgreSQL concurrency',results},null,2)+'\n');
console.log(JSON.stringify(results,null,2));if(results.some(r=>r.status!=='PASS')) process.exitCode=1;
