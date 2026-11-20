// Real unprivileged session/role on a new isolated PGlite. No real notifications.
import assert from 'node:assert/strict';
import {readdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb(),results=[],applied=[];
const baseline=process.argv.includes('--baseline');
const owner='fa000000-0000-0000-0000-000000000010';
const admin='fa000000-0000-0000-0000-000000000011';
const campaign='fb000000-0000-0000-0000-000000000010';
async function trial(name,expect,{locked=false,reason='owner_inactive',old=false}={}){
  const result={case:name};await db.exec('begin');
  try{
    await db.exec(`update public.profiles set is_locked=${locked} where id='${admin}';
      insert into public.campaigns(id,owner_id,app_name,package_name,status,paused_reason,slot_number,created_at,admin_note,admin_pause_reason_code)
      values('${campaign}','${owner}','Admin resume probe','fixture.admin.resume','paused','${reason}',1,clock_timestamp()-interval '${old?31:10} days','Previous admin note',${reason==='admin_manual'?"'policy_violation'":'null'})`);
    await db.exec('savepoint actor');
    try{
      await db.exec(`set local session authorization supervisor_admin_authenticator;set local role authenticated;select set_config('request.jwt.claim.sub','${admin}',true)`);
      result.actor=(await db.query('select current_user,session_user,auth.uid() uid')).rows[0];
      result.reply=(await db.query(`select public.admin_resume_campaign('${campaign}') r`)).rows[0].r;
    }catch(e){result.error=e.message;await db.exec('rollback to actor');}
    await db.exec('set session authorization postgres;reset role');
    result.campaign=(await db.query(`select status,paused_reason,admin_resumed,admin_note,admin_pause_reason_code,completed_at from public.campaigns where id='${campaign}'`)).rows[0];
    result.audits=(await db.query(`select count(*)::int n from public.admin_action_events where action='resume_campaign' and target_id='${campaign}'`)).rows[0].n;
    result.notifications=(await db.query(`select data->>'reason' reason from public.notifications where user_id='${owner}' and data->>'campaign_id'='${campaign}'`)).rows;
    await expect(result);result.pass=true;
  }catch(e){result.pass=false;result.failure=e.message;}
  finally{await db.exec('rollback;set session authorization postgres;reset role');results.push(result);console.log(JSON.stringify(result));}
}
try{
  for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)&&(!baseline||Number(n.slice(0,3))<=89)).sort()){
    const sql=read('supabase/migrations/'+name);await db.exec(sql);applied.push({name,sha256:createHash('sha256').update(sql).digest('hex')});
  }
  await db.exec(`create role supervisor_admin_authenticator noinherit;grant authenticated to supervisor_admin_authenticator;
    insert into auth.users(id,email) values('${owner}','admin-probe-owner@fixture.invalid'),('${admin}','admin-probe@fixture.invalid');
    update public.profiles set role='member',is_locked=false where id='${owner}';update public.profiles set role='admin',is_locked=false where id='${admin}'`);
  await trial('admin_inactive_resume_holds_open',r=>{assert.equal(r.error,undefined);assert.equal(r.campaign.status,'open');assert.equal(r.campaign.admin_resumed,true);});
  await trial('admin_resume_clears_pause_metadata',r=>{assert.equal(r.error,undefined);assert.equal(r.campaign.admin_note,null);assert.equal(r.campaign.admin_pause_reason_code,null);assert.equal(r.campaign.admin_resumed,false);},{reason:'admin_manual'});
  await trial('admin_resume_keeps_audit_and_notification',r=>{assert.equal(r.error,undefined);assert.equal(r.audits,1);assert.equal(r.notifications.filter(n=>n.reason==='admin_resume').length,1);assert.equal(r.reply.owner_id,owner);});
  await trial('locked_admin_cannot_resume',r=>{assert.match(r.error??'',/PERMISSION_DENIED|NOT_ADMIN|ACCOUNT_LOCKED|ADMIN_LOCKED/);assert.equal(r.campaign.status,'paused');assert.equal(r.audits,0);},{locked:true});
  await trial('admin_expired_resume_preserves_completion_contract',r=>{assert.equal(r.error,undefined);assert.equal(r.campaign.status,'completed');assert.ok(r.campaign.completed_at);assert.equal(r.campaign.admin_note,null);assert.equal(r.campaign.admin_pause_reason_code,null);assert.equal(r.campaign.admin_resumed,false);assert.equal(r.audits,1);assert.equal(r.notifications.filter(n=>n.reason==='window_expired').length,1);},{reason:'admin_manual',old:true});
}finally{
  await db.close();writeFileSync(new URL('./supervisor-admin-resume090-'+(baseline?'baseline':'candidate')+'-results.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),baseline,singleConnection:true,applied,results},null,2));
}
assert.ok(results.length===5&&results.every(r=>r.pass),'admin resume regression');console.log('PASS 5/5 admin resume contract cases; not concurrency.');

