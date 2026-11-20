import {readFileSync,writeFileSync} from 'node:fs';
const dir=new URL('./',import.meta.url);
let source=readFileSync(new URL('before/supabase/tests/audit-owner-auto-pause-flow.mjs',dir),'utf8');
source=source.slice(0,source.indexOf('  for (let n=1;n<=7;n++)'));
source=source.replace("from '../tools/baseline-lib.mjs'","from '../../supabase/tools/baseline-lib.mjs'")
  .replace("new URL('../migrations/', import.meta.url)","new URL('../../supabase/migrations/', import.meta.url)");
source+=String.raw`
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
`;
writeFileSync(new URL('supervisor-edge-probes-generated.mjs',dir),source);
await import(new URL('supervisor-edge-probes-generated.mjs',dir));
