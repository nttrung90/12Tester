// Supervisor acceptance: fresh in-memory PGlite only; no concurrency claim.
import assert from 'node:assert/strict';
import {readdirSync, writeFileSync, mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createDb, read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
const owner='ec000000-0000-0000-0000-000000000001';
const today="(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date";
const baselineOnly=process.argv.includes('--baseline');
const results=[];
const applied=[];
const candidateSources=[];
let actorContext=null;
const one=async sql=>(await db.query(sql)).rows[0];
async function actor(sql,{role='authenticated',uid=owner,spoof=false,internalSpoof=false,actualLogin=false}={}){
  assert.ok(['authenticated','service_role'].includes(role));
  if(actualLogin)await db.exec('set local session authorization supervisor_gate_authenticator');
  await db.exec(`set local role ${role};select set_config('request.jwt.claim.sub','${uid??''}',true)`);
  if(spoof)await db.query("select set_config('request.jwt.claim.role','service_role',true),set_config('request.jwt.claims',$1,true)",[JSON.stringify({role:'service_role',sub:uid})]);
  if(internalSpoof)await db.exec("select set_config('crosstest.internal_campaign_mutation','true',true)");
  if(actualLogin)actorContext=await one("select current_user caller_role,session_user login_role,current_setting('role') configured_role,auth.uid() uid");
  return db.query(sql);
}
async function fixture({completed=3,paused=false,debt=0,expired=false,locked=false,malformed=null,bonus=false,streak=0,changeObligation=false,persistedQuota=false}={}){
  const suppliers=Array.from({length:2},(_,i)=>`ed000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`);
  for(const [i,id] of [owner,...suppliers].entries()){
    await db.query('insert into auth.users(id,email) values($1,$2)',[id,`supervisor-gate-${i}@test.local`]);
    await db.exec(`update public.profiles set role='member',credits=10000,created_at=clock_timestamp()-interval '90 days',last_login_date=${today} where id='${id}';
      insert into public.user_slots(user_id,slot_number,expires_at) select '${id}',n,clock_timestamp()+interval '30 days' from generate_series(2,6)n;`);
  }
  for(const id of [owner,...suppliers])for(let n=1;n<=(id===owner?2:6);n++)
    await db.query(`insert into public.campaigns(owner_id,package_name,app_name,status,paused_reason,slot_number,created_at)
      values($1,$2,'Supervisor gate fixture',$3,$4,$5,clock_timestamp()-interval '2 days')`,
      [id,`supervisor.gate.${id.replaceAll('-','')}.${n}`,paused&&id===owner&&n===1?'paused':'open',paused&&id===owner&&n===1?'owner_manual':null,n]);
  await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,active_app_count,base_required_tests,required_tests,effective_required_tests,available_apps,completed_tests,logged_in,is_exempt,met,missed_streak,carry_in_tests,carry_out_tests,finalized_at)
    values('${owner}',${today}-1,'owner',2,6,6,6,12,${6-debt},true,false,${debt===0},0,0,${debt},clock_timestamp());
    insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,submission_context,reconciliation_status,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',c.id,c.owner_id,${today},'valid',true,'checkin','verified_checkin',clock_timestamp()
      from public.campaigns c where c.owner_id<>'${owner}' order by c.id limit ${completed};`);
  if(changeObligation)await db.exec(`set local test.allow_policy_retroactive_write='true';select public.set_daily_task_immediate_quota_policy(${today}-1,true,'Supervisor replay fixture');set local test.allow_policy_retroactive_write='false'`);
  const status=(await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
  if(persistedQuota)await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,active_app_count,base_required_tests,required_tests,effective_required_tests,available_apps,completed_tests,logged_in,is_exempt,met,carry_in_tests)
    values('${owner}',${today},'${status.mode}',${status.active_app_count},${status.base_required_tests},${status.required_tests},${status.effective_required_tests},${status.available_apps},${status.completed_tests},true,${status.is_penalty_exempt},${status.met},${status.carry_in_tests})`);
  const id=(await one(`select id from public.campaigns where owner_id='${owner}' order by slot_number limit 1`)).id;
  if(expired)await db.exec(`update public.campaigns set created_at=clock_timestamp()-interval '31 days' where id='${id}'`);
  if(locked)await db.exec(`update public.profiles set is_locked=true where id='${owner}'`);
  if(streak)await db.exec(`update public.profiles set daily_missed_streak=${streak} where id='${owner}'`);
  if(bonus){await actor(`select public.create_campaign_tester_bonus('${id}'::uuid,5,2,gen_random_uuid())`);await db.exec('reset role');}
  if(malformed){
    // Fault injection at the canonical status boundary, after valid fixture setup.
    // No gate or RLS is disabled; DDL rolls back with this case.
    const raw={...status};
    if(malformed.kind==='omit')delete raw[malformed.key];
    else raw[malformed.key]=malformed.value;
    const json=JSON.stringify(raw).replaceAll("'","''");
    await db.exec(`create or replace function public.get_daily_task_status(p_user_id uuid,p_date date)
      returns jsonb language sql security definer set search_path to public as $fault$ select '${json}'::jsonb $fault$`);
  }
  return {status,id};
}
async function snapshot(){
  return one(`select
    (select coalesce(jsonb_agg(to_jsonb(c) order by c.id),'[]') from public.campaigns c where owner_id='${owner}') campaigns,
    (select jsonb_build_object('credits',credits,'is_locked',is_locked) from public.profiles where id='${owner}') wallet,
    (select coalesce(jsonb_agg(to_jsonb(r) order by r.id),'[]') from public.campaign_tester_bonus_rounds r) rounds,
    (select coalesce(jsonb_agg(to_jsonb(e) order by e.id),'[]') from public.campaign_tester_bonus_events e) bonus_events`);
}
const sqlFor=(name,id)=>({
  owner_delete:`select public.delete_owned_campaign('${id}') r`,
  direct_delete:`delete from public.campaigns where id='${id}' returning id`,
  direct_production:`update public.campaigns set status='production',paused_reason=null where id='${id}' returning id,status`,
  direct_completed:`update public.campaigns set status='completed',paused_reason=null where id='${id}' returning id,status`,
  direct_closed:`update public.campaigns set status='closed',paused_reason=null where id='${id}' returning id,status`,
  manual_pause:`update public.campaigns set status='paused',paused_reason='owner_manual' where id='${id}' returning id,status`,
  forge_pending_delete:`update public.campaigns set status='closed',paused_reason='owner_deletion_pending' where id='${id}' returning id,status`,
  resume:`select public.resume_campaign('${id}') r`,
  production_rpc:`select public.confirm_campaign_production('${id}') r`,
  system_completed:`update public.campaigns set status='completed',paused_reason=null where id='${id}' returning id,status`,
  stale_server_day:`select public._assert_owner_daily_task_action_allowed('${owner}',${today}-1) r`,
}[name]);
async function check(name,options,allow){
  await db.exec('begin');
  const result={case:name,options,expected:allow?'allowed':'blocked'};
  actorContext=null;
  try{
    const f=await fixture(options);result.before=f.status;
    assert.equal(f.status.history_ready,true);assert.equal(f.status.carryover_ready,true);
    assert.equal(f.status.met,options.fixtureMet??(options.locked||options.malformed?true:allow),`unexpected fixture met`);
    const before=await snapshot();let error=null,reply;
    await db.exec('savepoint owner_action');
    try{
      if(name==='forge_pending_then_delete'){
        await actor(sqlFor('forge_pending_delete',f.id));
        reply=await actor(sqlFor('owner_delete',f.id));
      }else reply=await actor(sqlFor(name,f.id),{role:options.systemRole??'authenticated',uid:options.noUid?null:owner,spoof:options.spoof,internalSpoof:options.internalSpoof,actualLogin:options.actualLogin});
    }
    catch(e){error=e.message;await db.exec('rollback to owner_action');}
    if(actorContext)result.actorContext=actorContext;
    // Restore the original fixture administrator only after the actor call, for observation.
    await db.exec('set session authorization postgres; reset role');
    const after=await snapshot();result.error=error;result.reply=reply?.rows;
    if(!allow){
      assert.deepEqual(after,before,'blocked action changed campaign/credit/bonus state');
      if(name.startsWith('direct_')||name==='manual_pause'||name==='forge_pending_delete')
        assert.ok(error||reply?.rows?.length===0,'direct write must be rejected');
      else assert.ok(error||reply?.rows?.[0]?.r?.success===false||reply?.rows?.[0]?.r?.error,
        'RPC must communicate rejected action');
      if(options.malformed)assert.ok(error?.includes('DAILY_TASK_PENDING'),'unknown/malformed canonical data must report pending/retry rather than ordinary unmet work');
    }else{
      assert.equal(error,null,'eligible owner unexpectedly rejected');
      assert.notDeepEqual(after.campaigns,before.campaigns,'eligible action did not change app');
      if(options.bonus&&(name==='direct_production'||name==='production_rpc')){
        assert.equal(after.rounds[0].status,'closing','089 bonus lifecycle regression');
        assert.deepEqual(after.wallet,before.wallet,'production unexpectedly changed wallet');
      }
      result.after=(await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
      assert.equal(result.after.completed_tests,f.status.completed_tests,'completed count changed');
      if(options.persistedQuota){
        result.persistedDaily=(await one(`select to_jsonb(t) task from public.user_daily_tasks t where user_id='${owner}' and task_date=${today}`))?.task??null;
        assert.ok(result.persistedDaily,'existing current-day completion ledger must remain present');
        assert.equal(result.persistedDaily.base_required_tests,result.after.base_required_tests,'stored completion quota must match canonical post-action quota');
        assert.equal(result.persistedDaily.completed_tests,result.after.completed_tests,'stored completed tests must match canonical status');
      }
      if(options.replay){
        if(options.changeObligation){
          await db.exec(`insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at)
            select '${owner}','supervisor.replay.'||n,'Replay extra app','open',n,clock_timestamp() from generate_series(3,5)n`);
          result.beforeReplay=(await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
          assert.equal(result.beforeReplay.met,false,'replay fixture must change obligation to unmet');
        }
        const beforeReplay=await snapshot();await db.exec('savepoint retry_action');
        let replayError=null;try{result.replayReply=(await actor(sqlFor(name,f.id))).rows;}
        catch(e){replayError=e.message;await db.exec('rollback to retry_action');}
        await db.exec('set session authorization postgres; reset role');result.replayError=replayError;
        assert.equal(replayError,null,'retry of already performed action unexpectedly rejected');
        assert.deepEqual(await snapshot(),beforeReplay,'retry changed app/credit/bonus state again');
      }
    }
    result.pass=true;
  }catch(e){result.pass=false;result.failure=e.message;}
  finally{await db.exec('rollback');results.push(result);console.log(JSON.stringify(result));}
}
try{
  const migrations=readdirSync(new URL('../../supabase/migrations/',import.meta.url))
    .filter(n=>/^\d{3}_.*\.sql$/.test(n)&&(!baselineOnly||Number(n.slice(0,3))<=89)).sort();
  for(const m of migrations){const sql=read('supabase/migrations/'+m);await db.exec(sql);const sha256=createHash('sha256').update(sql).digest('hex');applied.push({name:m,sha256});if(Number(m.slice(0,3))>=90)candidateSources.push({name:m,sql,sha256});}
  await db.exec('create role supervisor_gate_authenticator noinherit; grant authenticated to supervisor_gate_authenticator');
  for(const name of ['owner_delete','direct_delete','direct_production','manual_pause'])await check(name,{completed:3},false);
  for(const name of ['direct_completed','direct_closed'])await check(name,{completed:3},false);
  await check('resume',{completed:0,paused:true},false);
  for(const name of ['owner_delete','direct_production','manual_pause'])await check(name,{completed:6},true);
  await check('resume',{completed:3,paused:true},true);
  for(const name of ['owner_delete','direct_production','manual_pause'])await check(name,{completed:6,debt:2},false);
  await check('resume',{completed:0,paused:true,expired:true},false);
  await check('resume',{completed:3,paused:true,expired:true,locked:true},false);
  if(process.argv.includes('--bonus')){
    for(const name of ['owner_delete','direct_production','manual_pause']){
      await check(name,{completed:3,bonus:true},false);
      await check(name,{completed:6,bonus:true},true);
    }
  }
  if(process.argv.includes('--review')){
    await check('direct_production',{completed:0,paused:true},false);
    await check('forge_pending_delete',{completed:0,paused:true},false);
    await check('forge_pending_then_delete',{completed:0,paused:true},false);
    await check('resume',{completed:3,paused:true,streak:7},true);
    await check('direct_production',{completed:6,malformed:{kind:'value',key:'carryover_remaining_tests',value:-1}},false);
    if(!baselineOnly){
      await check('production_rpc',{completed:3},false);
      await check('production_rpc',{completed:6,bonus:true},true);
    }
  }
  if(process.argv.includes('--roles')){
    await check('system_completed',{completed:3,systemRole:'service_role',fixtureMet:false},true);
    await check('system_completed',{completed:3,systemRole:'service_role',noUid:true,fixtureMet:false},true);
    await check('system_completed',{completed:6,systemRole:'service_role',locked:true},true);
    await check('direct_production',{completed:3,spoof:true},false);
    await check('production_rpc',{completed:3,spoof:true},false);
    await check('direct_production',{completed:3,noUid:true},false);
  }
  if(process.argv.includes('--r2')){
    await check('direct_delete',{completed:6},true);
    await check('direct_closed',{completed:3,internalSpoof:true},false);
    await check('forge_pending_delete',{completed:0,paused:true,internalSpoof:true},false);
    await check('owner_delete',{completed:6,replay:true,changeObligation:true},true);
    await check('resume',{completed:3,paused:true,replay:true},true);
    await check('production_rpc',{completed:6,replay:true,changeObligation:true},true);
    await check('production_rpc',{completed:6,noUid:true,spoof:true,actualLogin:true,fixtureMet:true},false);
    await check('stale_server_day',{completed:3},false);
    await check('direct_production',{completed:6,persistedQuota:true},true);
    await check('manual_pause',{completed:6,persistedQuota:true},true);
    await check('production_rpc',{completed:6,persistedQuota:true},true);
  }
  if(process.argv.includes('--payloads')){
    for(const name of ['owner_delete','direct_production','manual_pause','resume']){
      for(const key of ['history_ready','carryover_ready','carryover_remaining_tests','met','status_date']){
        await check(name,{completed:name==='resume'?3:6,paused:name==='resume',malformed:{kind:'omit',key}},false);
        await check(name,{completed:name==='resume'?3:6,paused:name==='resume',malformed:{kind:'value',key,value:null}},false);
      }
      await check(name,{completed:name==='resume'?3:6,paused:name==='resume',malformed:{kind:'value',key:'status_date',value:'2000-01-01'}},false);
    }
  }
}finally{
  await db.close();
  const suffix=['bonus','payloads','review','roles','r2'].filter(flag=>process.argv.includes('--'+flag)).map(flag=>'-'+flag).join('');
  const at=new Date().toISOString();const stem=(baselineOnly?'supervisor-baseline-acceptance':'supervisor-action-gate-acceptance')+suffix;
  const evidence=JSON.stringify({at,kind:baselineOnly?'baseline through 089':'current candidate',singleConnection:true,applied,results},null,2);
  writeFileSync(new URL('./'+stem+'.json',import.meta.url),evidence);
  mkdirSync(new URL('./supervisor-runs/',import.meta.url),{recursive:true});
  writeFileSync(new URL('./supervisor-runs/'+stem+'-'+at.replace(/[:.]/g,'-')+'.json',import.meta.url),evidence);
  for(const source of candidateSources)writeFileSync(new URL('./supervisor-runs/'+source.sha256+'-'+source.name,import.meta.url),source.sql);
}
assert.ok(results.every(r=>r.pass),`${results.filter(r=>!r.pass).length}/${results.length} acceptance failures`);
console.log(`PASS ${results.length}/${results.length}; behavioral tests only, not concurrency.`);
