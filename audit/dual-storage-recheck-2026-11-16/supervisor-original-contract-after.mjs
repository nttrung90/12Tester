// Independent read-only review of product source. All DB work is isolated PGlite.
import {readdirSync,readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {createDb} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
const user='22222222-2222-2222-2222-222222222222',owner='11111111-1111-1111-1111-111111111111',camp='55555555-5555-5555-5555-555555555555';
const results=[];
const one=async(q,p)=>(await db.query(q,p)).rows[0];
const reserve=async(id,thumb=false)=>(await one('select public.request_report_media_reservation($1,$2,100,$3) r',[camp,id,thumb])).r;
const check=async(id,title,fn)=>{
 await db.exec('begin');
 try{const observed=await fn();results.push({id,title,...observed});}
 catch(e){results.push({id,title,status:'ERROR',error:e.message});}
 finally{await db.exec('rollback');}
};
try{
 for(const file of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d+_.*\.sql$/.test(f)).sort())
  await db.exec(readFileSync(new URL('../../supabase/migrations/'+file,import.meta.url),'utf8'));
 await db.exec("insert into auth.users(id,email) values('"+owner+"','owner@review.invalid'),('"+user+"','tester@review.invalid');insert into public.campaigns(id,owner_id,app_name,package_name,status) values('"+camp+"','"+owner+"','Review','test.review','open');insert into public.campaign_members(campaign_id,user_id) values('"+camp+"','"+user+"');select set_config('request.jwt.claim.sub','"+user+"',false);insert into public.storage_trusted_hosts(host) values('example.supabase.co');update public.storage_organization_usage set local_project_ref='aaaaaaaaaaaaaaaaaaaa',total_capacity_bytes=1000000,used_storage_bytes=0,active_reserved_bytes=0,is_org_metric_valid=true,last_reconciled_at=now(),dispatch_mode='supabase_preferred' where id=1;grant usage on schema storage to authenticated;grant insert,select on storage.objects to authenticated;");
 await check('CONTROL-SIZE','Existing test metadata fixture is accepted',async()=>{
  const r=await reserve('control');
  const v=(await one("select public._can_insert_report_object('report-screenshots',$1,'{\"size\":100,\"mimetype\":\"image/jpeg\"}') ok",[r.object_path])).ok;
  assert.equal(v,true);return{status:'PASS',accepted:v};
 });
 await check('STORAGE-PREFLIGHT','Actual Storage binary preflight contentLength metadata must pass',async()=>{
  const r=await reserve('preflight');
  const metadata={mimetype:'image/jpeg',contentLength:100};
  const v=(await one("select public._can_insert_report_object('report-screenshots',$1,$2::jsonb) ok",[r.object_path,JSON.stringify(metadata)])).ok;
  await db.exec('set local role authenticated');
  let inserted=true,error;
  try{await db.query('insert into storage.objects(bucket_id,name,owner,metadata) values($1,$2,$3,$4::jsonb)',['report-screenshots',r.object_path,user,JSON.stringify(metadata)]);}
  catch(e){inserted=false;error={code:e.code,message:e.message};}
  return {status:v && inserted?'PASS':'FAIL',expected:{helper:true,inserted:true},observed:{helper:v,inserted,error},input:metadata};
 });
 await check('STORAGE-LEGACY-PREFLIGHT','Unknown-length/older Storage preflight metadata is handled intentionally',async()=>{
  const r=await reserve('missing-preflight-size');
  const v=(await one("select public._can_insert_report_object('report-screenshots',$1,'{\"mimetype\":\"image/jpeg\"}') ok",[r.object_path])).ok;
  return {status:'OBSERVED',observed:v,scope:'Unknown-length compatibility observation only. Rejecting unknown length can be intentional; the deployed version was not queried. Not counted as a separate defect.'};
 });
 await check('QUOTA-LATE-THUMB','Rolled-back policy probe is not equivalent to holding its lock until final commit',async()=>{
  const r=await reserve('late-thumb',true);
  await db.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"size\":100}')",[r.object_path,user]);
  // End the allocator transaction so its own quota lock is not inherited by
  // the permission-probe savepoint. Everything remains in the disposable DB.
  await db.exec('commit;begin');
  // Model the provider's final caller-policy probe: execute and roll back its
  // savepoint, then allow an application finalize before the privileged write.
  await db.exec('savepoint storage_permission_probe');
  assert.equal((await one("select public._can_insert_report_object('report-screenshots',$1,'{\"size\":50}') ok",[r.thumbnail_object_path])).ok,true);
  await db.exec('rollback to savepoint storage_permission_probe');
  const data=(await one('select public._prepare_report_media($1,null,$2,$3,null) r',['https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/'+r.object_path,camp,user])).r;
  await db.query('select public._finalize_report_media($1::jsonb,null,null)',[JSON.stringify(data)]);
  await db.exec("update public.storage_organization_usage set active_reserved_bytes=(select coalesce(sum(reserved_bytes),0) from public.report_media_uploads where provider='supabase' and status<>'deleted')");
  // Storage completeUpload writes the real metadata with privileged authority.
  await db.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"size\":50}')",[r.thumbnail_object_path,user]);
  const accounting=await one("select used_storage_bytes::int u,active_reserved_bytes::int r,(select sum((metadata->>'size')::int) from storage.objects)::int actual from public.storage_organization_usage");
  return {status:accounting.u+accounting.r>=accounting.actual?'PASS':'FAIL',observed:accounting,unaccountedBytes:accounting.actual-accounting.u-accounting.r,scope:'Sequential event-order model, not a real Storage API or multi-connection execution. Final-probe size metadata isolates this from the earlier preflight denial.'};
 });
}finally{await db.close();}
const report={at:new Date().toISOString(),scope:'PGlite contract probes; no production/no provider calls; product source unchanged',results};
writeFileSync(new URL('supervisor-original-contract-after-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
process.exitCode=results.some(x=>x.status==='FAIL'||x.status==='ERROR')?1:0;
