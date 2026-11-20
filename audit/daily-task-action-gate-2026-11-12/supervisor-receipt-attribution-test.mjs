// Fresh isolated SQL fixture. Prove real actor attribution, including inside SECURITY DEFINER.
import assert from 'node:assert/strict';
import {readdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb(),results=[],applied=[];
const owner='fa000000-0000-0000-0000-000000000020',admin='fa000000-0000-0000-0000-000000000021',campaign='fb000000-0000-0000-0000-000000000020';
async function trial(name,expected,{rpc=false,service=false,asAdmin=false,databaseAdmin=false,forwardedOwnerUid=false,fullAuthenticator=false,spoofClaimRole=false}={}){
  const result={case:name,expected};await db.exec('begin');
  try{
    await db.exec(`insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number) values('${campaign}','${owner}','Receipt actor fixture','fixture.receipt.actor','open',1)`);
    result.task=(await db.query(`select public.get_daily_task_status('${owner}',(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date) s`)).rows[0].s;
    assert.equal(result.task.met,true,'positive control must be eligible');
    assert.equal(result.task.carryover_remaining_tests,0);assert.equal(result.task.history_ready,true);assert.equal(result.task.carryover_ready,true);
    if(databaseAdmin){
      await db.exec("set local role postgres;select set_config('request.jwt.claim.sub','',true)");
    }else{
      await db.exec(`set local session authorization ${service?'supervisor_attribution_service_session':fullAuthenticator?'supervisor_attribution_full_session':'supervisor_attribution_owner_session'};set local role ${service?'service_role':'authenticated'};select set_config('request.jwt.claim.sub','${service?(forwardedOwnerUid?owner:''):asAdmin?admin:owner}',true)`);
    }
    if(spoofClaimRole)await db.exec("select set_config('request.jwt.claim.role','service_role',true)");
    result.actor=(await db.query("select current_user,session_user,auth.uid() uid,current_setting('role',true) active_role,current_setting('request.jwt.claim.role',true) claimed_role,pg_has_role(session_user,'service_role','MEMBER') session_member_service")).rows[0];
    result.reply=(await db.query(rpc?`select public.delete_owned_campaign('${campaign}') r`:`delete from public.campaigns where id='${campaign}' returning id`)).rows;
    await db.exec('set session authorization postgres;reset role');
    result.receipt=(await db.query(`select campaign_id,owner_id,removed_by,status,reason from public.campaign_removal_receipts where campaign_id='${campaign}'`)).rows[0];
    assert.ok(result.receipt,'committed physical deletion needs server proof');
    assert.equal(result.receipt.owner_id,owner);assert.equal(result.receipt.removed_by,expected);result.pass=true;
  }catch(e){result.pass=false;result.failure=e.message;}
  finally{await db.exec('rollback;set session authorization postgres;reset role');results.push(result);console.log(JSON.stringify(result));}
}
try{
  for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort()){
    const sql=read('supabase/migrations/'+name);await db.exec(sql);applied.push({name,sha256:createHash('sha256').update(sql).digest('hex')});
  }
  await db.exec(`create role supervisor_attribution_owner_session noinherit;grant authenticated to supervisor_attribution_owner_session;
    create role supervisor_attribution_service_session noinherit;grant service_role to supervisor_attribution_service_session;
    create role supervisor_attribution_full_session noinherit;grant authenticated,service_role to supervisor_attribution_full_session;
    insert into auth.users(id,email) values('${owner}','attribution-owner@fixture.invalid'),('${admin}','attribution-admin@fixture.invalid');
    update public.profiles set role='member',is_locked=false,last_login_date=(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date where id='${owner}';
    update public.profiles set role='admin',is_locked=false where id='${admin}'`);
  await trial('direct_owner_delete_attribution','owner');
  await trial('owner_rpc_delete_attribution','owner',{rpc:true});
  await trial('service_physical_cleanup_attribution','worker',{service:true});
  await trial('admin_other_owner_delete_attribution','admin',{asAdmin:true});
  await trial('service_cleanup_with_forwarded_owner_uid_still_worker','worker',{service:true,forwardedOwnerUid:true});
  await trial('real_database_admin_cleanup_is_not_inferred_from_membership','admin',{databaseAdmin:true});
  await trial('postgrest_authenticator_membership_is_not_active_service_role','owner',{fullAuthenticator:true});
  await trial('spoofed_jwt_role_cannot_change_actual_owner_attribution','owner',{spoofClaimRole:true});
}finally{
  await db.close();writeFileSync(new URL('./supervisor-receipt-attribution-results.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),singleConnection:true,applied,results},null,2));
}
assert.ok(results.length===8&&results.every(r=>r.pass),'receipt actor attribution mismatch');console.log('PASS 8/8 receipt actor attribution cases; not concurrency.');
