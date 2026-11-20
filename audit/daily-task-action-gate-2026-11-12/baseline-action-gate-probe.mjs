// Supervisor-owned fresh in-memory database. No URL, no old task cluster, no concurrency claim.
import assert from 'node:assert/strict';
import {readdirSync,writeFileSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();const owner='ea000000-0000-0000-0000-000000000001';
const today="(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date";
const results=[];
const one=async sql=>(await db.query(sql)).rows[0];
async function actor(sql){await db.exec(`set local role authenticated;select set_config('request.jwt.claim.sub','${owner}',true)`);return db.query(sql);}
async function fixture(){
  const suppliers=Array.from({length:2},(_,i)=>`eb000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`);
  for(const [i,id] of [owner,...suppliers].entries()){
    await db.query('insert into auth.users(id,email) values($1,$2)',[id,`gate-fixture-${i}@test.local`]);
    await db.exec(`update public.profiles set role='member',credits=10000,created_at=clock_timestamp()-interval '90 days',last_login_date=${today} where id='${id}';
      insert into public.user_slots(user_id,slot_number,expires_at) select '${id}',n,clock_timestamp()+interval '30 days' from generate_series(2,6)n;`);
  }
  for(const id of [owner,...suppliers])for(let n=1;n<= (id===owner?2:6);n++)await db.query(`insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at) values($1,$2,'Gate fixture','open',$3,clock_timestamp()-interval '2 days')`,[id,`gate.fixture.${id.replaceAll('-','')}.${n}`,n]);
  await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,active_app_count,base_required_tests,required_tests,effective_required_tests,available_apps,completed_tests,logged_in,is_exempt,met,missed_streak,carry_in_tests,carry_out_tests,finalized_at)
    values('${owner}',${today}-1,'owner',2,6,6,6,12,6,true,false,true,0,0,0,clock_timestamp());
    insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,submission_context,reconciliation_status,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',c.id,c.owner_id,${today},'valid',true,'checkin','verified_checkin',clock_timestamp() from public.campaigns c where c.owner_id<>'${owner}' order by c.id limit 3;`);
  const status=(await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
  assert.equal(status.met,false);assert.equal(status.history_ready,true);assert.equal(status.carryover_ready,true);assert.ok(status.base_required_tests>status.completed_tests);
  return {status,id:(await one(`select id from public.campaigns where owner_id='${owner}' order by slot_number limit 1`)).id};
}
try{
  const files=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)&&Number(n.slice(0,3))<=89).sort();
  for(const file of files)await db.exec(read('supabase/migrations/'+file));
  for(const name of ['owner_delete','direct_production','manual_pause']){
    await db.exec('begin');
    try{
      const f=await fixture();let error=null,result;
      try{result=await actor(name==='owner_delete'?`select public.delete_owned_campaign('${f.id}') r`:
        `update public.campaigns set status='${name==='direct_production'?'production':'paused'}',paused_reason=${name==='manual_pause'?"'owner_manual'":'null'} where id='${f.id}' returning id,status`);}
      catch(e){error=e.message;}
      const after=error?null:(await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
      const evidence={case:name,before:f.status,after,blocked:error!==null,error,rows:result?.rows};
      if(!error){assert.equal(after.base_required_tests,f.status.base_required_tests/2);assert.equal(after.completed_tests,3);assert.equal(after.met,true);}
      results.push(evidence);console.log(JSON.stringify(evidence));
    }finally{await db.exec('rollback');}
  }
}finally{await db.close();writeFileSync(new URL('./baseline-action-gate-results.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),kind:'baseline089-only, single-connection PGlite; not final acceptance',results},null,2));}
assert.equal(results.length,3);
assert.ok(results.every(r=>!r.blocked),'Baseline changed: inspect whether another implementation has already started');
console.log('BASELINE REPRODUCED: all three owner actions succeed although canonical task status is unmet.');
