// Read-only audit of application behavior in a disposable local database.
// BUG observations document current defects; they are not acceptance assertions.
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'a1000000-0000-0000-0000-000000000001';
const admin = 'a1000000-0000-0000-0000-000000000002';
const app1 = 'b1000000-0000-0000-0000-000000000001';
const app2 = 'b1000000-0000-0000-0000-000000000002';
const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;
const scalar = async sql => (await db.query(sql)).rows[0];
async function asActor(userId, sql) {
  await db.exec('savepoint actor_call');
  await db.exec(`set request.jwt.claim.sub = '${userId}'; set role authenticated;`);
  try {
    const result = await db.query(sql);
    await db.exec(`reset role; reset request.jwt.claim.sub; release savepoint actor_call;`);
    return result;
  } catch (error) {
    await db.exec('rollback to savepoint actor_call; release savepoint actor_call;');
    throw error;
  }
}
const asOwner = sql => asActor(owner,sql);
async function setThreshold(n) {
  const before=(await asActor(admin,'select public.admin_get_owner_auto_pause_config() r')).rows[0].r;
  const result=(await asActor(admin,`select public.admin_set_owner_auto_pause_config(${n},'${before.revision}') r`)).rows[0].r;
  assert.equal(result.success,true);
  assert.equal(result.missed_days,n);
  const after=(await asActor(admin,'select public.admin_get_owner_auto_pause_config() r')).rows[0].r;
  assert.equal(after.missed_days,n);
  assert.equal(after.revision,result.revision);
  return {before,result,after};
}
async function scenario(name, fn) {
  await db.exec('begin');
  try { await fn(); console.log(`PASS: ${name}`); }
  finally { await db.exec('rollback'); }
}
async function checkpoint(offset = 1, streak = 0) {
  await db.exec(`insert into public.user_daily_tasks
    (user_id,task_date,mode,finalized_at,missed_streak,carry_out_tests,met)
    values ('${owner}', ${today}-${offset}, 'owner', now(), ${streak}, 0, false);
    update public.profiles set daily_missed_streak=${streak} where id='${owner}';`);
}
async function qualifyingTest(offset = 0) {
  // Historical fixture represents a qualifying event observed on that day.
  await db.exec(`insert into public.daily_test_events_journal
    (source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',id,'${admin}',${today}-${offset},'valid',true,
      (${today}-${offset})::timestamp at time zone 'Asia/Ho_Chi_Minh'
    from public.campaigns where owner_id='${admin}' and status='open';`);
}
try {
  const files = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x)).sort();
  for (const file of files) await db.exec(read(`supabase/migrations/${file}`));
  console.log(`Loaded ${files[0]} through ${files.at(-1)}; actual SQL functions, no function stubs.`);
  await db.exec(`
    insert into auth.users(id,email) values ('${owner}','audit-owner@test.local'),('${admin}','audit-admin@test.local');
    update public.profiles set created_at=now()-interval '20 days',credits=1000,last_login_date=${today}-20;
    update public.profiles set role='admin' where id='${admin}';
    insert into public.user_slots(user_id,slot_number,expires_at) values ('${owner}',2,now()+interval '20 days');
    insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number,created_at) values
      ('${app1}','${owner}','audit.owner.one','Owner One','open',1,now()-interval '15 days'),
      ('${app2}','${owner}','audit.owner.two','Owner Two','open',2,now()-interval '15 days');
    insert into public.campaigns(owner_id,package_name,app_name,status,created_at)
      values ('${admin}','audit.supply','Supply','open',now()-interval '15 days');
    update public.campaign_status_journal set valid_from=now()-interval '15 days';
    update public.user_role_journal set valid_from=now()-interval '20 days';
    update public.system_provenance_markers set marker_time=(${today}-30)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${today}-30;
    set test.allow_policy_retroactive_write='true';
    select public.set_daily_task_carryover_policy(${today}-10,true,'Disposable audit fixture');
    reset test.allow_policy_retroactive_write;
  `);


  let defects=0;
  async function probe(name,fn) {
    try {await scenario(name,fn);}
    catch(error) {defects++;console.log('DEFECT:',name, String(error.message));}
  }
  await probe('private ledger diagnostics deny ordinary members',async()=>{
    await assert.rejects(()=>asOwner('select * from public.audit_daily_task_ledger_gaps()'),/ADMIN|PERMISSION|permission|FORBIDDEN/);
  });
  await probe('private ledger diagnostics deny authenticated sessions without an identity',async()=>{
    await assert.rejects(()=>asActor('','select * from public.audit_daily_task_ledger_gaps()'),/ADMIN|AUTHENTICATED|PERMISSION|permission|FORBIDDEN/);
  });
  await probe('client cannot authorize timestamp mutation through custom settings',async()=>{
    await asOwner("select set_config('app.allow_campaign_created_at_write','true',false),set_config('test.allow_campaign_created_at_write','true',false)");
    await assert.rejects(()=>asOwner("update public.campaigns set created_at=now() where id='"+app1+"'"));
  });
  await probe('disabled carryover cannot treat unknown yesterday as debt-free ready history',async()=>{
    await checkpoint(3,0);
    await db.exec("set test.allow_policy_retroactive_write='true'; select public.set_daily_task_carryover_policy("+today+",false,'Supervisor gap probe'); reset test.allow_policy_retroactive_write;");
    await db.exec("update public.campaigns set status='paused',paused_reason='owner_inactive' where owner_id='"+owner+"'");
    await qualifyingTest();
    await assert.rejects(()=>asOwner("select public.resume_campaign('"+app1+"')"),/DAILY_TASK_PENDING/);
  });
  await probe('locked owner cannot resume through SECURITY DEFINER RPC',async()=>{
    await checkpoint(1,0);
    await qualifyingTest();
    await db.exec("update public.campaigns set status='paused',paused_reason='owner_inactive' where id='"+app1+"'; update public.profiles set is_locked=true where id='"+owner+"'");
    await assert.rejects(()=>asOwner("select public.resume_campaign('"+app1+"')"),/LOCKED|PERMISSION|FORBIDDEN/);
  });
  await probe('new account creation-day exemption does not require nonexistent yesterday history',async()=>{
    await db.exec("update public.profiles set created_at=now(),daily_missed_streak=0 where id='"+owner+"'; update public.campaigns set created_at=now(),status='paused',paused_reason='owner_manual' where owner_id='"+owner+"'");
    await asOwner('select public.record_daily_login()');
    const result=(await asOwner("select public.resume_campaign('"+app1+"') r")).rows[0].r;
    assert.equal(result.status,'open','A newly created account has no prior obligations to reconcile');
  });
  await probe('backfilling an older gap cannot overwrite profile with an older-day streak',async()=>{
    await checkpoint(3,6);
    await checkpoint(1,7);
    await qualifyingTest(2);
    await db.exec("select public.finalize_daily_tasks_for_date("+today+"-2)");
    const state=await scalar("select daily_missed_streak s, (select missed_streak from public.user_daily_tasks where user_id='"+owner+"' and finalized_at is not null order by task_date desc limit 1) latest from public.profiles where id='"+owner+"'");
    assert.equal(state.s,state.latest,'Current summary must match the latest immutable finalized ledger, not an older backfilled day');
  });
  await probe('daily penalty credit log action retains its existing contract',async()=>{
    await checkpoint(2,0);
    await db.exec("select public._apply_daily_inactivity_penalty("+today+"-1)");
    const logs=await scalar("select count(*) filter(where action='daily_missed_penalty')::int correct,count(*) filter(where action<>'daily_missed_penalty')::int unexpected from public.credit_logs where user_id='"+owner+"' and amount<0");
    assert.ok(logs.correct>0,'Nonzero daily penalty must retain action=daily_missed_penalty from migration 071');
    assert.equal(logs.unexpected,0,'Do not introduce an unrelated penalty action while fixing finalization');
  });
  console.log('Supervisor edge defects:',defects);
  if(defects) process.exitCode=1;
} finally {await db.close();}
