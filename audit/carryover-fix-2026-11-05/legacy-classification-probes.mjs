// Supervisor-owned upgrade probes: seed real old-schema sources, then upgrade.
import assert from 'node:assert/strict';
import {readdirSync,writeFileSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
const today=`(now() at time zone 'Asia/Ho_Chi_Minh')::date`;
const supplier='d1000000-0000-0000-0000-000000000001';
const ids=Array.from({length:5},(_,i)=>`d2000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`);
const q=async sql=>(await db.query(sql)).rows[0];
const results=[];
async function probe(name,fn){try{const evidence=await fn();results.push({name,result:'PASS',evidence});console.log('PASS:',name,JSON.stringify(evidence));}catch(e){results.push({name,result:'FAIL',error:e.message});console.log('FAIL:',name,e.message);process.exitCode=1;}}
try {
  const files=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort();
  for(const f of files.filter(n=>Number(n.slice(0,3))<=79)) await db.exec(read('supabase/migrations/'+f));
  for(const [i,id] of [supplier,...ids].entries()) {
    await db.exec(`insert into auth.users(id,email) values('${id}','legacy-carry-${i}@test.local');
      update public.profiles set created_at=now()-interval '60 days',credits=10000,last_login_date=${today} where id='${id}';
      insert into public.campaigns(owner_id,package_name,app_name,status,created_at)
      values('${id}','legacy.carry.${i}','Legacy ${i}','open',now()-interval '15 days')`);
  }
  await db.exec(`update public.profiles set role='admin' where id='${supplier}';
    update public.campaign_status_journal set valid_from=now()-interval '15 days';
    update public.user_role_journal set valid_from=now()-interval '60 days';
    update public.system_provenance_markers set marker_time=(${today}-60)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${today}-60;
    set test.allow_policy_retroactive_write='true';select public.set_daily_task_carryover_policy(${today},true,'Legacy upgrade audit');reset test.allow_policy_retroactive_write`);
  for(const id of ids) await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,carry_out_tests,finalized_at,met) values('${id}',${today}-1,'owner',2,now(),true)`);
  const campaign=(await q(`select id from public.campaigns where owner_id='${supplier}'`)).id;
  const missing='d3000000-0000-0000-0000-000000000001';
  const mission='d4000000-0000-0000-0000-000000000001';
  await db.exec(`insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
    values('report','${missing}','${ids[0]}','${campaign}','${supplier}',${today},'pending',true,now());
    insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
      values('${mission}','${supplier}','${campaign}','Legacy context',10,3,50,'open',now()+interval '3 days');
    insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${ids[1]}','joined');
    insert into public.tester_reports(user_id,campaign_id,submission_context,status,mission_id,mission_participant_id,content)
      select '${ids[1]}','${campaign}','mission','pending','${mission}',id,'Old mission report' from public.mission_participants where user_id='${ids[1]}' and mission_id='${mission}';
    insert into public.tester_reports(user_id,campaign_id,submission_context,status,content)
      values('${ids[2]}','${campaign}','app_test','pending','Old ordinary report'),
            ('${ids[3]}','${campaign}',null,'pending','Unclassified legacy report');
    insert into public.checkins(user_id,campaign_id,checkin_date,note)
      values('${ids[4]}','${campaign}',${today},'Test thường: đã thử tính năng mission trong app')`);
  const frozen=await q(`select row_to_json(t) r from public.user_daily_tasks t where user_id='${ids[3]}' and task_date=${today}-1`);
  const upgrades=files.filter(n=>Number(n.slice(0,3))>79);assert.ok(upgrades.length>0,'New migration is required');
  for(const f of upgrades) await db.exec(read('supabase/migrations/'+f));
  const status=async id=>(await q(`select public.get_daily_task_status('${id}',${today}) s`)).s;
  await probe('Missing legacy report source remains unresolved and cannot authorize resume',async()=>{
    const s=await status(ids[0]);assert.equal(s.history_ready,false);
    assert.equal(s.met,false);return s;
  });
  await probe('Known pre-upgrade mission source is excluded',async()=>{
    const s=await status(ids[1]);assert.equal(s.completed_tests,0);assert.equal(s.history_ready,true);return s;
  });
  await probe('Known pre-upgrade ordinary source still counts',async()=>{
    const s=await status(ids[2]);assert.equal(s.completed_tests,1);assert.equal(s.history_ready,true);return s;
  });
  await probe('Pre-upgrade report with unknown context cannot be inferred as ordinary',async()=>{
    const s=await status(ids[3]);assert.equal(s.history_ready,false);return s;
  });
  await probe('Ordinary legacy checkin remains valid when its freeform note mentions mission',async()=>{
    const s=await status(ids[4]);assert.equal(s.completed_tests,1);assert.equal(s.history_ready,true);
    const other=(await q(`select id from public.campaigns where owner_id='${ids[2]}'`)).id;
    await db.exec(`insert into public.checkins(user_id,campaign_id,checkin_date,note)
      values('${ids[4]}','${other}',${today},'Test thường: tính năng nhiệm vụ mission đã hoạt động')`);
    const live=await status(ids[4]);assert.equal(live.completed_tests,2,'Post-upgrade ordinary checkin notes must remain valid too');
    return {legacy:s,postUpgrade:live};
  });
  await probe('Upgrade leaves finalized historical row unchanged',async()=>{
    assert.deepEqual(await q(`select row_to_json(t) r from public.user_daily_tasks t where user_id='${ids[3]}' and task_date=${today}-1`),frozen);return {preserved:true};
  });
  await probe('Reapplying the upgrade leaves source qualification and frozen history stable',async()=>{
    const before=await Promise.all(ids.map(status));
    for(const f of upgrades) await db.exec(read('supabase/migrations/'+f));
    assert.deepEqual(await Promise.all(ids.map(status)),before);
    assert.deepEqual(await q(`select row_to_json(t) r from public.user_daily_tasks t where user_id='${ids[3]}' and task_date=${today}-1`),frozen);return {stable:true};
  });
  await probe('Known ordinary event survives campaign cascade deletion and upgrade rerun',async()=>{
    await db.exec(`delete from public.campaigns where id='${campaign}'`);
    assert.equal((await status(ids[2])).completed_tests,1);
    assert.equal((await status(ids[2])).history_ready,true);
    for(const f of upgrades) await db.exec(read('supabase/migrations/'+f));
    const s=await status(ids[2]);assert.equal(s.completed_tests,1);assert.equal(s.history_ready,true);return s;
  });
} finally {writeFileSync(new URL('./legacy-classification-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');await db.close();}
