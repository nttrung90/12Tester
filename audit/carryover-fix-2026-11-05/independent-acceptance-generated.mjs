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

  await scenario('Four days: partial, partial, clears all debt, over-completion',async()=>{
    await policy(4,true); await checkpoint(5); const evidence=[];
    for(const [offset,done,expected] of [[4,4,2],[3,5,3],[2,9,0],[1,10,0]]) {
      await events(offset,done); const row=await seal(offset); assert.equal(row.carry_out_tests,expected); evidence.push(row);
    } return evidence;
  });
  await scenario('Seven unfinished days accumulate without a reset',async()=>{
    await policy(7,true); await checkpoint(8);
    // Keep enough supply so waived unavailable work does not affect this case.
    for(let offset=7;offset>=1;offset--) {await events(offset,4);const row=await seal(offset);assert.equal(row.carry_out_tests,2*(8-offset));}
    return {finalDebt:(await status()).carry_in_tests};
  });
  for(const [debt,a,d,left] of [[2,5,5,0],[8,5,5,3],[8,0,0,8],[0,20,0,6],[8,20,20,0]]) {
    await scenario(`Supply matrix C=${debt}, A=${a}, D=${d}`,async()=>{
      await policy(1,true);await checkpoint(2,debt);await available(a);await events(1,d);
      const s=await status(1);assert.equal(s.available_apps,a);assert.equal(s.carryover_remaining_tests,left);
      const row=await seal(1);assert.equal(row.carry_out_tests,left);return brief(s);
    });
  }
  await scenario('Disable, pay only voluntary excess, re-enable with remaining debt',async()=>{
    await policy(4,true);await policy(3,false);await policy(1,true);await checkpoint(5,8);
    const out=[];
    for(const [offset,done,left] of [[4,5,9],[3,8,7],[2,6,7],[1,13,0]]) {
      await events(offset,done);const row=await seal(offset);assert.equal(row.carry_out_tests,left);out.push(row);
    }return out;
  });
  await scenario('Member transition retains debt and voluntary tests reduce it',async()=>{
    await policy(1,true);await checkpoint(2,8);
    await db.exec(`update public.campaigns set status='paused',paused_reason='owner_manual' where owner_id='${owner}';
      update public.campaign_status_journal set valid_from=now()-interval '15 days';
      update public.profiles set last_login_date=${today}-1 where id='${owner}'`);
    await events(1,2);const s=await status(1);assert.equal(s.mode,'member');assert.equal(s.met,true);assert.equal(s.carryover_remaining_tests,6);
    assert.equal((await seal(1)).carry_out_tests,6);return brief(s);
  });
  await scenario('Duplicate checkins and reports for one app count once',async()=>{
    await policy(1,true);await checkpoint(2);await events(1,2);await events(1,2);
    await db.exec(`insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
      select 'report',gen_random_uuid(),'${owner}',id,owner_id,${today}-1,'pending',true,(${today}-1)::timestamp at time zone 'Asia/Ho_Chi_Minh'
      from public.campaigns where id='${supply[0]}'`);
    const s=await status(1);assert.equal(s.completed_tests,2);assert.equal((await seal(1)).carry_out_tests,4);return brief(s);
  });
  await scenario('Current report pending and approved count; rejected no longer counts',async()=>{
    await policy(0,true);await checkpoint(1,2);
    const id='c8000000-0000-0000-0000-000000000001';
    await db.exec(`insert into public.tester_reports(id,user_id,campaign_id,submission_context,status,content)
      values('${id}','${owner}','${supply[0]}','app_test','pending','Trusted source fixture')`);
    const pending=await status();assert.equal(pending.completed_tests,1);
    await db.exec(`update public.tester_reports set status='approved' where id='${id}'`);
    const approved=await status();assert.equal(approved.completed_tests,1);
    await db.exec(`update public.tester_reports set status='rejected' where id='${id}'`);
    const rejected=await status();assert.equal(rejected.completed_tests,0);
    return {pending:brief(pending),approved:brief(approved),rejected:brief(rejected)};
  });
  await scenario('Late source changes do not rewrite an already finalized historical day',async()=>{
    await policy(1,true);await checkpoint(2);await events(1,4);const frozen=await seal(1);
    await events(1,6);assert.deepEqual(await seal(1),frozen);
    assert.equal((await status(1)).carryover_remaining_tests,2);return frozen;
  });
  await scenario('Backfill three missing days and retry finalization preserve debt and credits',async()=>{
    await policy(3,true);await checkpoint(4);await events(3,4);await events(2,5);await events(1,6);
    const initialBalance=(await q(`select credits from public.profiles where id='${owner}'`)).credits;
    for(let attempt=0;attempt<5;attempt++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      if((await status()).carryover_ready) break;
    }
    const s=await status();assert.equal(s.carryover_ready,true);assert.equal(s.carry_in_tests,3);
    const billing=await q(`select coalesce(sum(penalty_credits) filter(where task_date>${today}-4),0)::int intended,
      count(*) filter(where task_date<${today}-4 and finalized_at is not null)::int before_anchor
      from public.user_daily_tasks where user_id='${owner}'`);
    const before=await q(`select credits from public.profiles where id='${owner}'`);
    assert.equal(billing.before_anchor,0,'An automatic catch-up must not invent/penalize history before a trusted checkpoint');
    assert.equal(initialBalance-before.credits,billing.intended,'Only post-checkpoint finalized dates may debit the current credit balance');
    await db.exec('select public.apply_daily_inactivity_penalty()');
    assert.deepEqual(await q(`select credits from public.profiles where id='${owner}'`),before);
    assert.equal((await status()).carry_in_tests,3);return {...brief(s),creditCheck:{initial:initialBalance,final:before.credits,penalty:billing.intended,preAnchorRows:billing.before_anchor}};
  });
  await scenario('Unfinalized yesterday stays pending and does not fabricate cleared debt',async()=>{
    await checkpoint(2,8);const s=await status();assert.equal(s.carryover_ready,false);return brief(s);
  });
  await scenario('Real mission submission cannot reduce regular debt or bypass owner resume',async()=>{
    await policy(0,true);await checkpoint(1,8);
    const campaign=supply[0];const supplier=(await q(`select owner_id from public.campaigns where id='${campaign}'`)).owner_id;
    const mission='c5000000-0000-0000-0000-000000000001';
    const request='c6000000-0000-0000-0000-000000000001';
    const image='c7000000-0000-0000-0000-000000000001';
    await db.exec(`update public.profiles set credits=10000 where id='${supplier}';
      insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
      values('${mission}','${supplier}','${campaign}','Carryover isolation',10,3,50,'open',now()+interval '3 days');
      insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${owner}','joined')`);
    const before=await status();
    const attachment=[{id:image,position:0,
      screenshot_url:`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2F${image}.jpg?alt=media&token=fixture`,
      thumbnail_url:`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${owner}%2Fthumb_${image}.jpg?alt=media&token=fixture`,
      image_hash:'1111222233334444',sha256:'carryover_isolation_fixture'}];
    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    const response=(await q(`select public.submit_tester_report_v3(p_submission_context=>'mission',p_campaign_id=>'${campaign}',
      p_content=>'Mission only, no regular daily test',p_rating=>5,p_client_submit_id=>'${request}',
      p_attachments=>'${JSON.stringify(attachment)}'::jsonb,p_mission_id=>'${mission}') r`)).r;
    await db.exec('reset role;reset request.jwt.claim.sub');
    const after=await status();
    const report=await q(`select submission_context,status from public.tester_reports where user_id='${owner}' and client_submit_id='${request}'`);
    assert.equal(report.submission_context,'mission');assert.equal(before.completed_tests,0);assert.equal(after.completed_tests,0);
    assert.equal(after.carryover_remaining_tests,before.carryover_remaining_tests);
    // One old task remains; only the mission report exists. This must not pay a regular test debt.
    await db.exec(`update public.user_daily_tasks set carry_out_tests=1 where user_id='${owner}' and task_date=${today}-1;
      update public.campaigns set status='paused',paused_reason='owner_manual' where owner_id='${owner}';update public.profiles set last_login_date=${today} where id='${owner}'`);
    const bypass=await status();assert.equal(bypass.met,true);assert.equal(bypass.mode,'member');assert.equal(bypass.carryover_remaining_tests,1);
    const owned=(await q(`select id from public.campaigns where owner_id='${owner}' order by slot_number limit 1`)).id;
    await db.exec(`set request.jwt.claim.sub='${owner}';set role authenticated`);
    let resumeFailure;
    await db.exec('savepoint rejected_resume');
    try { await q(`select public.resume_campaign('${owned}') r`); }
    catch(error) { resumeFailure=String(error.message);await db.exec('rollback to savepoint rejected_resume'); }
    await db.exec('release savepoint rejected_resume');
    assert.match(resumeFailure ?? '',/CARRYOVER|DEBT|OWNER_INACTIVE|DAILY_TASK/);
    await db.exec('reset role;reset request.jwt.claim.sub');assert.equal((await q(`select status from public.campaigns where id='${owned}'`)).status,'paused');
    return {before:brief(before),after:brief(after),report,response:{success:response.success,submission_context:response.submission_context},
      alternateFixtureWithOneOldDebt:brief(bypass),resumeRejected:resumeFailure};
  });
  await scenario('Completed daily target must still retain the unavailable old tasks',async()=>{
    await policy(0,true);await checkpoint(1,8);await available(5);await events(0,5);const s=await status();
    assert.equal(s.met,true);assert.equal(s.carryover_remaining_tests,3);
    return brief(s);
  });
  await scenario('Member login must not erase preserved debt',async()=>{
    await policy(0,true);await checkpoint(1,8);
    await db.exec(`update public.campaigns set status='paused',paused_reason='owner_manual' where owner_id='${owner}';update public.profiles set last_login_date=${today} where id='${owner}'`);
    const s=await status();assert.equal(s.mode,'member');assert.equal(s.met,true);assert.equal(s.carryover_remaining_tests,8);return brief(s);
  });

  await scenario('Covered 39-day gap recovers in bounded repeated automatic jobs without losing old debt',async()=>{
    await checkpoint(40,8);let prior=0;let runs=0;let s;
    for(;runs<20;runs++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      const count=(await q(`select count(*)::int n from public.user_daily_tasks where user_id='${owner}' and task_date>${today}-40 and finalized_at is not null`)).n;
      s=await status();
      assert.ok(count>prior,'Each automatic job must advance this fully covered chain');prior=count;
      if(s.carryover_ready) break;
    }
    assert.equal(s.carryover_ready,true,'Long gap must eventually reconcile');assert.equal(prior,39);
    assert.ok(s.carry_in_tests>=8,'Old debt must not be erased while crossing the gap');
    const before=await q(`select credits from public.profiles where id='${owner}'`);
    await db.exec('select public.apply_daily_inactivity_penalty()');
    assert.deepEqual(await q(`select credits from public.profiles where id='${owner}'`),before);
    assert.equal((await status()).carry_in_tests,s.carry_in_tests);
    return {runs:runs+1,sealed:prior,status:brief(s)};
  });

} finally {
  writeFileSync(new URL('./independent-results.json',import.meta.url),JSON.stringify({generatedAt:new Date().toISOString(),environment:'Disposable PGlite, active migrations in order, no live server',results},null,2)+'\n');
  await db.close();
}
