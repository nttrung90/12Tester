// Disposable local audit. Product code is not changed by this script.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'c3000000-0000-0000-0000-000000000001';
const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;
const q = async sql => (await db.query(sql)).rows[0];
const results = [];
let supply = [];
async function scenario(name, fn) {
  await db.exec('begin');
  try { const evidence = await fn(); results.push({name, result:'PASS', evidence}); console.log('PASS:',name,JSON.stringify(evidence ?? {})); }
  catch(e) { results.push({name,result:'FAIL',error:e.message}); console.log('FAIL:',name,e.message); process.exitCode=1; }
  finally { await db.exec('rollback'); }
}
async function checkpoint(offset, debt=0) {
  await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,finalized_at,carry_out_tests,missed_streak,met)
    values('${owner}',${today}-${offset},'owner',now(),${debt},0,true)`);
}
async function policy(offset, enabled) {
  await db.exec(`set test.allow_policy_retroactive_write='true';
    select public.set_daily_task_carryover_policy(${today}-${offset},${enabled},'Carryover recheck fixture');
    reset test.allow_policy_retroactive_write`);
}
async function events(offset,n) {
  for(const id of supply.slice(0,n)) await db.exec(`insert into public.daily_test_events_journal
    (source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',id,owner_id,${today}-${offset},'valid',true,
      (${today}-${offset})::timestamp at time zone 'Asia/Ho_Chi_Minh' from public.campaigns where id='${id}'`);
}
async function status(offset=0) {return (await q(`select public.get_daily_task_status('${owner}',${today}-${offset}) s`)).s;}
async function seal(offset) {
  await db.exec(`select public._apply_daily_inactivity_penalty(${today}-${offset})`);
  return await q(`select carry_in_tests,carry_out_tests,completed_tests,required_tests,met,penalty_credits
    from public.user_daily_tasks where user_id='${owner}' and task_date=${today}-${offset} and finalized_at is not null`);
}
async function available(n) {
  await db.exec(`update public.campaigns set status='paused',paused_reason='admin_manual' where owner_id<>'${owner}'
    and id not in (${supply.slice(0,n).map(x=>`'${x}'`).join(',') || "'00000000-0000-0000-0000-000000000000'"});
    update public.campaign_status_journal set valid_from=now()-interval '15 days'`);
}
function brief(s) {return Object.fromEntries(['mode','base_required_tests','carry_in_tests','available_apps','completed_tests','effective_required_tests','carryover_remaining_tests','met','carryover_ready','history_ready'].map(k=>[k,s[k]]));}
try {
  const migrations=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort();
  for(const name of migrations) await db.exec(read('supabase/migrations/'+name));
  console.log('ACTIVE MIGRATIONS:',migrations.join(', '));
  await db.exec(`insert into auth.users(id,email) values('${owner}','carryover-owner@test.local');
    update public.profiles set created_at=now()-interval '60 days',credits=10000,last_login_date=${today}-60 where id='${owner}';
    insert into public.user_slots(user_id,slot_number,expires_at) values('${owner}',2,now()+interval '20 days');
    insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at) values
    ('${owner}','audit.carry.one','Carry One','open',1,now()-interval '15 days'),
    ('${owner}','audit.carry.two','Carry Two','open',2,now()-interval '15 days')`);
  for(let i=1;i<=20;i++) {
    const id=`c4000000-0000-0000-0000-${String(i).padStart(12,'0')}`;
    await db.exec(`insert into auth.users(id,email) values('${id}','carry-supply-${i}@test.local');
      update public.profiles set created_at=now()-interval '60 days',role='admin' where id='${id}';
      insert into public.campaigns(owner_id,package_name,app_name,status,created_at)
      values('${id}','audit.carry.supply.${i}','Supply ${i}','open',now()-interval '15 days')`);
  }
  supply=(await db.query(`select id from public.campaigns where owner_id<>'${owner}' order by package_name`)).rows.map(x=>x.id);
  await db.exec(`update public.campaign_status_journal set valid_from=now()-interval '15 days';
    update public.user_role_journal set valid_from=now()-interval '60 days';
    update public.system_provenance_markers set marker_time=(${today}-60)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${today}-60`);


  async function asActor(uid,sql) {
    await db.exec('savepoint actor_call');
    await db.exec(`set request.jwt.claim.sub='${uid}';set role authenticated`);
    try {const result=await db.query(sql);await db.exec('reset role;reset request.jwt.claim.sub;release savepoint actor_call');return result;}
    catch(e){await db.exec('rollback to savepoint actor_call;release savepoint actor_call');throw e;}
  }
  for(const sql of ['select public.apply_daily_inactivity_penalty()', 'select public.finalize_daily_tasks_for_date('+today+'-1)']) {
    await scenario('Ordinary members cannot execute privileged finalization: '+sql,async()=>{
      await assert.rejects(()=>asActor(owner,sql),/permission|PERMISSION|ADMIN|FORBIDDEN/);return {denied:true};
    });
  }
  for(const name of ['diagnose_daily_task_stuck_users','audit_carryover_journal_reconciliation']) {
    await scenario('Unlocked administrator can inspect private diagnostic: '+name,async()=>{
      const admin='c4000000-0000-0000-0000-000000000001';
      const result=await asActor(admin,'select * from public.'+name+'()');return {authorized:true,rows:result.rows.length};
    });
    await scenario('Trusted service can inspect private diagnostic: '+name,async()=>{
      await db.exec('set role service_role');
      const result=await db.query('select * from public.'+name+'()');await db.exec('reset role');return {authorized:true,rows:result.rows.length};
    });
    await scenario('Unidentified client cannot inspect all users: '+name,async()=>{
      await assert.rejects(()=>asActor('','select * from public.'+name+'()'),/permission|PERMISSION|ADMIN|FORBIDDEN|AUTH/);return {denied:true};
    });
    await scenario('Locked administrator cannot use private diagnostic: '+name,async()=>{
      const admin='c4000000-0000-0000-0000-000000000001';
      await db.exec(`update public.profiles set is_locked=true where id='${admin}'`);
      await assert.rejects(()=>asActor(admin,'select * from public.'+name+'()'),/permission|PERMISSION|ADMIN|FORBIDDEN|LOCKED/);return {denied:true};
    });
  }
  await scenario('Trusted service retains access to scheduled and explicit finalization',async()=>{
    const exposed=(await db.query("select p.oid::regprocedure::text signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname like '_apply_daily_inactivity_penalty%' and has_function_privilege('authenticated',p.oid,'EXECUTE')")).rows;
    assert.deepEqual(exposed,[],'Internal financial helpers, including any new scoped overload, must not be executable by clients');
    await db.exec('set role service_role');
    await db.exec('select public.apply_daily_inactivity_penalty()');
    await db.exec('select public.finalize_daily_tasks_for_date('+today+'-1)');
    await db.exec('reset role');return {authorized:true};
  });
  await scenario('An unresolved old user cannot starve healthy users awaiting recent finalization',async()=>{
    await checkpoint(40,8);
    await db.exec(`insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at,submission_context,reconciliation_status)
      select 'report',gen_random_uuid(),'${owner}',id,owner_id,${today}-39,'pending',true,
      (${today}-39)::timestamp at time zone 'Asia/Ho_Chi_Minh',null,'unresolved_missing_source'
      from public.campaigns where id='${supply[0]}'`);
    const healthy='c4000000-0000-0000-0000-000000000001';let state;
    for(let i=0;i<6;i++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      state=(await q(`select public.get_daily_task_status('${healthy}',${today}) s`)).s;
      if(state.carryover_ready) break;
    }
    assert.equal(state.carryover_ready,true,'Healthy users must advance beyond another user\'s unresolved source date');
    assert.equal((await status()).carryover_ready,false,'The unknown history must remain unresolved');return state;
  });
  for(const partial of [false,true]) await scenario('Unprovable coverage boundary cannot starve healthy users: partial='+partial,async()=>{
    await db.exec(`update public.profiles set created_at=now()-interval '90 days' where id='${owner}'`);
    if(partial) {
      await checkpoint(61,8);
      await db.exec(`update public.system_provenance_markers set marker_time=(${today}-60)::timestamp at time zone 'Asia/Ho_Chi_Minh'+interval '12 hours' where marker_key='coverage_start'`);
    }
    const healthy='c4000000-0000-0000-0000-000000000001';let state;
    for(let i=0;i<6;i++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      state=(await q(`select public.get_daily_task_status('${healthy}',${today}) s`)).s;
      if(state.carryover_ready) break;
    }
    assert.equal(state.carryover_ready,true,'A legacy account lacking reliable boundary history must not block healthy accounts');
    assert.equal((await status()).carryover_ready,false,'Do not invent old boundary history or erase its debt');return {partial,healthyReady:state.carryover_ready};
  });
} finally {
  writeFileSync(new URL('./safety-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');await db.close();
}
