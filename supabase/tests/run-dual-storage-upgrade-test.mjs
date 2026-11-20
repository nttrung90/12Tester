import assert from 'node:assert/strict';
import {readFileSync,readdirSync} from 'node:fs';
import {createDb} from '../tools/baseline-lib.mjs';
const db=await createDb();
const user='22222222-2222-2222-2222-222222222222',owner='11111111-1111-1111-1111-111111111111',camp='55555555-5555-5555-5555-555555555555';
try {
 const files=readdirSync(new URL('../migrations/',import.meta.url)).filter(f=>/^\d+_.*\.sql$/.test(f)).sort();
 for(const f of files.filter(f=>Number(f.slice(0,3))<=91))await db.exec(readFileSync(new URL('../migrations/'+f,import.meta.url),'utf8'));
 await db.exec("insert into auth.users(id,email) values('"+owner+"','owner@upgrade.invalid'),('"+user+"','tester@upgrade.invalid');insert into public.campaigns(id,owner_id,app_name,package_name,status) values('"+camp+"','"+owner+"','Upgrade','test.upgrade','open');insert into public.campaign_members(campaign_id,user_id) values('"+camp+"','"+user+"');select set_config('request.jwt.claim.sub','"+user+"',false);update public.storage_organization_usage set total_capacity_bytes=1000000,used_storage_bytes=0,is_org_metric_valid=true,last_reconciled_at=now()");
 const reservation=(await db.query("select public.request_report_media_reservation($1,'upgrade-image',100,false) r",[camp])).rows[0].r;
 assert.equal(reservation.provider,'supabase');
 await db.query("insert into storage.objects(bucket_id,name,owner,metadata,created_at) values($1,$2,$3,'{\"size\":100}',now()-interval '217 hours')",[reservation.bucket,reservation.object_path,user]);
 const report=(await db.query("select public.submit_tester_report_v3(p_submission_context:='app_test',p_campaign_id:=$1,p_rating:=5,p_content:='upgrade',p_client_submit_id:='00000000-0000-0000-0000-000000000099',p_attachments:=jsonb_build_array(jsonb_build_object('screenshot_url',$2::text,'image_hash','1111222233334444','position',0))) r",[camp,'https://example.supabase.co/storage/v1/object/authenticated/'+reservation.bucket+'/'+reservation.object_path])).rows[0].r;
 assert.equal(report.success,true);
 const before=(await db.query("select view_expires_at>now() incorrectly_active from public.report_media_uploads where id=$1",[reservation.reservation_id])).rows[0];
 assert.equal(before.incorrectly_active,true,'091 fixture must reproduce wrong clock.');
 await db.exec(readFileSync(new URL('../migrations/092_dual_storage_hardening.sql',import.meta.url),'utf8'));
 const after=(await db.query("select m.server_created_at=o.created_at correct_clock,m.view_expires_at<=now() expired,m.delete_deadline_at=o.created_at+interval '240 hours' correct_deadline from public.report_media_uploads m join storage.objects o on o.name=m.object_path and o.bucket_id=m.bucket where m.id=$1",[reservation.reservation_id])).rows[0];
 assert.deepEqual(after,{correct_clock:true,expired:true,correct_deadline:true});
 assert.equal((await db.query('select media_expired from public.tester_report_attachments where report_id=$1',[report.report_id])).rows[0].media_expired,true);
 assert.equal((await db.query('select is_org_metric_valid from public.storage_organization_usage')).rows[0].is_org_metric_valid,false);
 console.log('PASS: upgrade from defective 091 repairs existing object clock/expiry/deadline and invalidates guessed quota, without deleting provider metadata.');
}finally{await db.close();}
