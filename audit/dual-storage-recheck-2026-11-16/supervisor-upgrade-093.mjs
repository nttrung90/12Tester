// Upgrade regression with pre-existing 092 rows, isolated PGlite only.
import assert from 'node:assert/strict';
import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {createDb} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb(),results=[];
const user='22222222-2222-2222-2222-222222222222',owner='11111111-1111-1111-1111-111111111111',camp='55555555-5555-5555-5555-555555555555',ref='aaaaaaaaaaaaaaaaaaaa';
const one=async(q,p)=>(await db.query(q,p)).rows[0];
const reserve=async(id,thumb)=>(await one('select public.request_report_media_reservation($1,$2,100,$3) r',[camp,id,thumb])).r;
const object=(path,size)=>db.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,jsonb_build_object('size',$3::int))",[path,user,size]);
try{
 const files=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d+_.*\.sql$/.test(f)).sort();
 for(const f of files.filter(f=>parseInt(f)<=92))await db.exec(readFileSync(new URL('../../supabase/migrations/'+f,import.meta.url),'utf8'));
 await db.exec(`insert into auth.users(id,email) values('${owner}','owner@upgrade093.invalid'),('${user}','tester@upgrade093.invalid');insert into public.campaigns(id,owner_id,app_name,package_name,status) values('${camp}','${owner}','Upgrade093','test.upgrade093','open');insert into public.campaign_members(campaign_id,user_id) values('${camp}','${user}');select set_config('request.jwt.claim.sub','${user}',false);insert into public.storage_trusted_hosts(host) values('example.supabase.co');update public.storage_organization_usage set local_project_ref='${ref}',total_capacity_bytes=1000000,used_storage_bytes=0,active_reserved_bytes=0,is_org_metric_valid=true,last_reconciled_at=now(),dispatch_mode='supabase_preferred' where id=1;`);
 const partial=await reserve('legacy-finalized',true),expired=await reserve('legacy-expired',false),deleted=await reserve('legacy-deleted',true),complete=await reserve('legacy-complete',false);
 await object(partial.object_path,100);
 const data=(await one('select public._prepare_report_media($1,null,$2,$3,null) r',['https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/'+partial.object_path,camp,user])).r;
 await db.query('select public._finalize_report_media($1::jsonb,null,null)',[JSON.stringify(data)]);
 await object(complete.object_path,100);
 const completeData=(await one('select public._prepare_report_media($1,null,$2,$3,null) r',['https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/'+complete.object_path,camp,user])).r;
 await db.query('select public._finalize_report_media($1::jsonb,null,null)',[JSON.stringify(completeData)]);
 await db.query("update public.report_media_uploads set status='expired',reservation_expires_at=now()-interval '1 hour' where id=any($1::uuid[])",[[expired.reservation_id,deleted.reservation_id]]);
 const jobs=(await one('select public.get_expired_report_media_for_cleanup(100,300,false) r')).r;
 const j=jobs.find(x=>x.id===deleted.reservation_id);assert.ok(j);
 assert.equal((await one('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r',[j.id,j.lease_token,[j.object_path,j.thumbnail_object_path].filter(Boolean)])).r.success,true);
 const newer=files.filter(f=>parseInt(f)>92);assert.ok(newer.length,'No follow-up migration after 092');
 for(const f of newer)await db.exec(readFileSync(new URL('../../supabase/migrations/'+f,import.meta.url),'utf8'));
 const immediatelyAfterMigration=await one('select used_storage_bytes::text u,active_reserved_bytes::text r,is_org_metric_valid valid from public.storage_organization_usage');
 // Existing complete/unmeasured files must not lose their old hold during backfill.
 // Invalidating the metric until first reconcile is also an acceptable safe upgrade.
 if(immediatelyAfterMigration.valid)assert.ok(BigInt(immediatelyAfterMigration.u)+BigInt(immediatelyAfterMigration.r)>=300400n,'Migration exposes valid quota before unmeasured existing bytes/pending writes are covered: '+JSON.stringify(immediatelyAfterMigration));
 else assert.equal((await reserve('pre-reconcile-safe',false)).provider,'gcs');
 const metric=[{project_ref:ref,bytes:'200',status:'SUCCESS',observed_at:new Date().toISOString()}];
 const ack=(await one('select public.reconcile_storage_organization_usage(1000000,200,$1::jsonb,true) r',[JSON.stringify(metric)])).r;
 assert.equal(ack.is_org_metric_valid,true);
 const before=await one('select used_storage_bytes::text u,active_reserved_bytes::text r from public.storage_organization_usage');
 await object(partial.thumbnail_object_path,150000);await object(expired.object_path,100);await object(deleted.object_path,100);await object(deleted.thumbnail_object_path,150000);
 const after=await one("select used_storage_bytes::text u,active_reserved_bytes::text r,(select sum((metadata->>'size')::bigint)::text from storage.objects) actual from public.storage_organization_usage");
 assert.ok(BigInt(after.u)+BigInt(after.r)>=BigInt(after.actual),'Upgrade lost legacy pending-write capacity: '+JSON.stringify(after));
 results.push({name:'092 to latest retains legacy finalized/expired/deleted pending writes',status:'PASS',migrations:newer,immediatelyAfterMigration,before,after});
}catch(e){results.push({name:'092 to latest upgrade',status:'FAIL',error:e.message});}
finally{await db.close();}
const report={at:new Date().toISOString(),scope:'Isolated PGlite, pre-existing metadata rows only',results};
writeFileSync(new URL('supervisor-upgrade-093-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));process.exitCode=results.some(x=>x.status==='FAIL')?1:0;
