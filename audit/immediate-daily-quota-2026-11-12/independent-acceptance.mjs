// Supervisor-owned real RPC acceptance on isolated native PostgreSQL.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFileSync} from 'node:fs';
import {connect,setup,status,createApp,removeApp,events,asActor,one,OWNER,NEW_OWNER,ADMIN,TODAY} from './independent-fixture.mjs';
const db=await connect(process.env.QUOTA_PRIVATE_DATABASE||'immediate_quota_test');const results=[];
async function activate(date=TODAY+'-1') {
  // Privileged test seed only; immutable-history triggers are enabled for all actual actors/RPCs.
  await db.query('alter table public.daily_task_immediate_quota_policies disable trigger user');
  await db.query(`insert into public.daily_task_immediate_quota_policies(effective_from,is_enabled,operator_note)
    values(${date},true,'Supervisor isolated activation') on conflict(effective_from) do update set is_enabled=true`);
  await db.query('alter table public.daily_task_immediate_quota_policies enable trigger user');
}
async function test(name,fn,opts={}) {
  await db.query('begin');
  try {const f=await setup(db,opts);await activate();const evidence=await fn(f);results.push({name,result:'PASS',evidence});console.log('PASS:',name,JSON.stringify(evidence??{}));}
  catch(e){results.push({name,result:'FAIL',error:e.message});console.log('FAIL:',name,e.message);process.exitCode=1;}
  finally {await db.query('rollback');}
}
const compact=s=>Object.fromEntries(['mode','active_app_count','base_required_tests','required_tests','effective_required_tests','completed_tests','carry_in_tests','carryover_remaining_tests','met','carryover_ready','history_ready'].map(k=>[k,s[k]]));
try {
  await test('Existing owner create 12 -> 24 -> 36, remove -> 24 -> 12',async f=>{
    assert.equal((await status(db)).base_required_tests,12);
    const two=await createApp(db);assert.equal((await status(db)).base_required_tests,24);
    const three=await createApp(db,{slot:3});assert.equal((await status(db)).base_required_tests,36);
    await removeApp(db,f.old);assert.equal((await status(db)).base_required_tests,24);
    await removeApp(db,two.response.campaign_id);assert.equal((await status(db)).base_required_tests,12);
    return compact(await status(db));
  });
  await test('New registration-day owner gets 12 immediately without a nonexistent predecessor',async()=>{
    const p=await createApp(db,{uid:NEW_OWNER,slot:1});const s=await status(db,NEW_OWNER);
    assert.equal(s.base_required_tests,12);assert.equal(s.mode,'owner');assert.equal(s.carryover_ready,true);assert.equal(s.met,false);
    return {id:p.response.campaign_id,status:compact(s)};
  });
  await test('Registration-day owner finalizes quota/debt/penalty and carries it into next day',async()=>{
    await createApp(db,{uid:NEW_OWNER,slot:1});
    await db.query("select set_config('test.allow_future_finalize','true',true)");
    await db.query(`select public._apply_daily_inactivity_penalty(${TODAY},${TODAY})`);
    const row=await one(db,`select required_tests,base_required_tests,carry_out_tests,is_exempt,missed_streak,penalty_credits from public.user_daily_tasks where user_id=$1 and task_date=${TODAY}`,[NEW_OWNER]);
    assert.equal(row.required_tests,12);assert.equal(row.carry_out_tests,12);assert.equal(row.is_exempt,false);assert.equal(row.missed_streak,1);assert.ok(row.penalty_credits>0);
    assert.equal((await status(db,NEW_OWNER,TODAY+'+1')).carry_in_tests,12);return row;
  });
  await test('Registration-day pure member retains exemption and no debt',async()=>{
    await db.query("select set_config('test.allow_future_finalize','true',true)");await db.query(`select public._apply_daily_inactivity_penalty(${TODAY},${TODAY})`);
    const row=await one(db,`select required_tests,carry_out_tests,is_exempt,missed_streak,penalty_credits from public.user_daily_tasks where user_id=$1 and task_date=${TODAY}`,[NEW_OWNER]);
    assert.equal(row.required_tests,0);assert.equal(row.carry_out_tests,0);assert.equal(row.is_exempt,true);assert.equal(row.penalty_credits,0);return row;
  });
  await test('Create replay counts one app and preserves immutable receipt',async()=>{
    const a=await createApp(db);const before=await status(db);
    const r=(await asActor(db,a.uid,a.sql,a.args)).rows[0].r;assert.equal(r.replayed,true);assert.equal(r.campaign_id,a.response.campaign_id);
    assert.equal((await status(db)).base_required_tests,24);assert.equal((await status(db)).active_app_count,before.active_app_count);return {replayed:r.replayed,base:24};
  });
  await test('Create replay after removal does not resurrect a quota or campaign',async()=>{
    const a=await createApp(db);await removeApp(db,a.response.campaign_id);
    const r=(await asActor(db,a.uid,a.sql,a.args)).rows[0].r;assert.equal(r.replayed,true);
    assert.equal((await status(db)).base_required_tests,12);assert.equal((await one(db,'select count(*)::int n from public.campaigns where id=$1',[a.response.campaign_id])).n,0);return {replayed:true,base:12};
  });
  await test('Failed creation leaves quota, count and credit balance unchanged',async()=>{
    await db.query('update public.profiles set credits=0 where id=$1',[OWNER]);const before=await status(db);
    await assert.rejects(()=>createApp(db),/INSUFFICIENT_CREDITS/);
    assert.deepEqual(await status(db),before);assert.equal((await one(db,'select credits from public.profiles where id=$1',[OWNER])).credits,0);return compact(before);
  });
  await test('Configured q12 is independent of target_testers20',async()=>{
    const a=await createApp(db,{target:20});const s=await status(db);assert.equal(s.base_required_tests,24);assert.equal(a.response.target_testers,20);return {base:s.base_required_tests,paid:a.response.charged_credits};
  });
  await test('Completed D survives add/remove and over-completion never becomes negative',async()=>{
    await events(db,5);const a=await createApp(db);const s=await status(db);assert.equal(s.completed_tests,5);assert.equal(s.required_tests,24);assert.equal(s.carryover_remaining_tests,19);
    await removeApp(db,a.response.campaign_id);assert.equal((await status(db)).completed_tests,5);assert.equal((await status(db)).carryover_remaining_tests,7);
    await events(db,20);const done=await status(db);assert.equal(done.met,true);assert.equal(done.carryover_remaining_tests,0);return compact(done);
  });
  await test('Deletion alone cannot erase previously sealed debt, including last app',async f=>{
    const frozen=await one(db,`select row_to_json(t) r from public.user_daily_tasks t where user_id=$1 and task_date=${TODAY}-1`,[OWNER]);
    const a=await createApp(db);assert.equal((await status(db)).carry_in_tests,7);
    await removeApp(db,a.response.campaign_id);await removeApp(db,f.old);const s=await status(db);
    assert.equal(s.base_required_tests,0);assert.equal(s.carry_in_tests,7);assert.equal(s.carryover_remaining_tests,7);assert.equal(s.mode,'member');
    assert.deepEqual(await one(db,`select row_to_json(t) r from public.user_daily_tasks t where user_id=$1 and task_date=${TODAY}-1`,[OWNER]),frozen);return compact(s);
  },{debt:7});
  await test('Quota change refreshes todays mutable ledger without finalization or credit penalty',async()=>{
    await events(db,12);await db.query(`select public.refresh_daily_completion($1,${TODAY})`,[OWNER]);assert.equal((await status(db)).met,true);
    const balance=(await one(db,'select credits from public.profiles where id=$1',[OWNER])).credits;
    await createApp(db);const row=await one(db,`select required_tests,active_app_count,completed_tests,met,finalized_at from public.user_daily_tasks where user_id=$1 and task_date=${TODAY}`,[OWNER]);
    assert.equal(row.required_tests,24);assert.equal(row.active_app_count,2);assert.equal(row.completed_tests,12);assert.equal(row.met,false);assert.equal(row.finalized_at,null);
    assert.equal((await one(db,'select credits from public.profiles where id=$1',[OWNER])).credits,balance);return row;
  });
  await test('Logical deferred removal reduces once and final physical cleanup preserves quota',async f=>{
    await createApp(db);
    await db.query(`insert into public.tester_reports(user_id,campaign_id,submission_context,status,content) values($1,$2,'app_test','pending','Private pending removal fixture')`,[NEW_OWNER,f.old]);
    const r=await removeApp(db,f.old);assert.equal(r.status,'pending_removal');assert.equal((await status(db)).base_required_tests,12);
    await db.query("update public.tester_reports set status='approved' where campaign_id=$1",[f.old]);await db.query('select public._process_closing_tester_bonus_rounds()');
    assert.equal((await status(db)).base_required_tests,12);return {initial:r.status,after:compact(await status(db))};
  });
  await test('Admin removal applies base quota to actual owner without changing another users base',async f=>{
    await createApp(db);const other=await status(db,NEW_OWNER);await removeApp(db,f.old,{byAdmin:true});assert.equal((await status(db)).base_required_tests,12);
    const next=await status(db,NEW_OWNER);for(const key of ['active_app_count','base_required_tests','carry_in_tests','completed_tests'])assert.equal(next[key],other[key]);
    assert.equal(next.available_apps,other.available_apps-1,'Other users available source pool legitimately shrinks after removal');return compact(await status(db));
  });
  await test('Inactive-owner paused apps stay counted while removed apps do not',async f=>{
    await db.query("update public.campaigns set status='paused',paused_reason='owner_inactive' where id=$1",[f.old]);
    assert.equal((await status(db)).base_required_tests,12);await createApp(db);assert.equal((await status(db)).base_required_tests,24);return compact(await status(db));
  });
  await test('Source scarcity preserves base24 separately from effective20',async()=>{
    await db.query(`update public.campaigns set status='closed' where id in (select id from public.campaigns where package_name like 'supervisor.supply.%' order by package_name limit 10)`);
    await createApp(db);const s=await status(db);assert.equal(s.base_required_tests,24);assert.equal(s.available_apps,20);assert.equal(s.effective_required_tests,20);return compact(s);
  });
  await test('Future activation preserves old current-day counting until effective day',async()=>{
    await db.query(`delete from public.daily_task_immediate_quota_policies where effective_from=${TODAY}-1`);
    await activate(TODAY+'+1');await createApp(db);assert.equal((await status(db)).base_required_tests,12);return compact(await status(db));
  });
  await test('Historical reconstruction uses old-before-cutover and immediate-after-cutover rules',async()=>{
    const ids=[];
    for(const [slot,offset] of [[2,2],[3,1]]) {
      const id=(await one(db,`insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at)
        values($1,$2,'Historical source','open',$3,(((${TODAY})-$4::integer)::timestamp at time zone 'Asia/Ho_Chi_Minh')+interval '10 hours') returning id`,[OWNER,'supervisor.historical.'+slot,slot,offset])).id;ids.push(id);
    }
    await db.query('update public.campaign_status_journal set valid_from=campaign_created_at where campaign_id=any($1::uuid[])',[ids]);
    const old=await status(db,OWNER,TODAY+'-2');const active=await status(db,OWNER,TODAY+'-1');
    assert.equal(old.base_required_tests,12);assert.equal(active.base_required_tests,36);return {old:compact(old),active:compact(active)};
  },{seedCheckpoint:false});
  await test('Frozen prior-day status is immutable after todays create and delete',async f=>{
    const frozen=await status(db,OWNER,TODAY+'-1');const a=await createApp(db);await removeApp(db,f.old);await removeApp(db,a.response.campaign_id);
    assert.deepEqual(await status(db,OWNER,TODAY+'-1'),frozen);return {preserved:true};
  });
  await test('Carryover disabled preserves debt while todays base changes',async()=>{
    await db.query(`alter table public.daily_task_carryover_policies disable trigger user;
      insert into public.daily_task_carryover_policies(effective_from,is_enabled,operator_note) values(${TODAY},false,'Supervisor carryoff seed')
      on conflict(effective_from) do update set is_enabled=false;
      alter table public.daily_task_carryover_policies enable trigger user`);
    const a=await createApp(db);let s=await status(db);assert.equal(s.base_required_tests,24);assert.equal(s.carry_in_tests,7);assert.equal(s.carryover_remaining_tests,7);
    await removeApp(db,a.response.campaign_id);s=await status(db);assert.equal(s.base_required_tests,12);assert.equal(s.carryover_remaining_tests,7);return compact(s);
  },{debt:7});
  await test('Configured future q15 applies per date rather than hardcoded12',async()=>{
    await createApp(db);await db.query(`insert into public.daily_task_policies(effective_from,tests_per_active_app,operator_note) values(${TODAY}+1,15,'Supervisor future q15')`);
    assert.equal((await status(db)).base_required_tests,24);assert.equal((await status(db,OWNER,TODAY+'+1')).base_required_tests,30);return {today:24,tomorrow:30};
  });
  await test('Exact one-date trusted finalizer entry point remains callable',async()=>{
    await db.query(`select public._apply_daily_inactivity_penalty(${TODAY}-4)`);return {compatible:true};
  });
  await test('Unidentified authenticated client cannot schedule a global quota policy',async()=>{
    await assert.rejects(()=>asActor(db,'',`select public.set_daily_task_immediate_quota_policy(${TODAY}+3,true,'Unidentified fixture')`),/PERMISSION|AUTH|ADMIN|permission/);return {denied:true};
  });
  for(const offset of ['-4','+0'])await test('Admin cannot insert a new retroactive/current policy: '+offset,async()=>{
    await assert.rejects(()=>asActor(db,ADMIN,`select public.set_daily_task_immediate_quota_policy(${TODAY}${offset},true,'No historical rewrite fixture')`),/HISTORICAL|FUTURE|EFFECTIVE|RETRO/);return {denied:true};
  });
  await test('Policy audit records actual administrator identity',async()=>{
    await asActor(db,ADMIN,`select public.set_daily_task_immediate_quota_policy(${TODAY}+3,true,'Authorized future policy fixture')`);
    const audit=await one(db,`select * from public.daily_task_immediate_quota_policy_audit where effective_from=${TODAY}+3 order by id desc limit 1`);
    assert.equal(audit.changed_by,ADMIN);return {actor:audit.changed_by};
  });
  await test('Locked administrator cannot read private quota-policy audit',async()=>{
    await asActor(db,ADMIN,`select public.set_daily_task_immediate_quota_policy(${TODAY}+3,true,'Audit permission fixture')`);
    await db.query('update public.profiles set is_locked=true where id=$1',[ADMIN]);
    const rows=(await asActor(db,ADMIN,'select * from public.daily_task_immediate_quota_policy_audit')).rows;
    assert.equal(rows.length,0);return {private:true};
  });
}finally{writeFileSync(new URL('./independent-results.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),database:process.env.QUOTA_PRIVATE_DATABASE||'immediate_quota_test',results},null,2)+'\n');await db.end();}
