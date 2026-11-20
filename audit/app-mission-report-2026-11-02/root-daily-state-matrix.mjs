import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const owner='11111111-1111-1111-1111-111111111111', tester='22222222-2222-2222-2222-222222222222';
const campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const uuid=n=>'cccccccc-cccc-cccc-cccc-'+String(n).padStart(12,'0');
const migrationSql=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d{3}_.*\.sql$/.test(f)&&+f.slice(0,3)>=62).sort().map(f=>read('supabase/migrations/'+f));
async function fixture(){
  const db=await createDb({legacyGrants:true});
  for(const sql of migrationSql)await db.exec(sql);
  await db.exec(`insert into auth.users(id,email) values('${owner}','owner@matrix.invalid'),('${tester}','tester@matrix.invalid');
    update public.profiles set credits=10000 where id='${owner}';
    insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values('${campaign}','${owner}','invalid.matrix.app','Daily matrix','open',1);
    insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
    values('${mission}','${owner}','${campaign}','Daily matrix mission',10,3,20,'open',now()+interval '2 days');
    insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${tester}','joined');`);
  return db;
}
async function actor(db,uid,sql){
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${uid}',true); set local role authenticated;`);
  try{const rows=(await db.query(sql)).rows;await db.exec('commit');return rows;}
  catch(e){await db.exec('rollback');throw e;}
}
async function submit(db,context,n){
  return(await actor(db,tester,`select public.submit_tester_report_v2(p_submission_context=>'${context}',p_campaign_id=>'${campaign}',p_content=>'Matrix ${context} ${n}',p_rating=>5,p_client_submit_id=>'${uuid(n)}',p_screenshot_url=>'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${uuid(n)}.jpg?alt=media&token=fixture',p_image_hash=>'0123456789abcdef'${context==='mission'?`,p_mission_id=>'${mission}'`:''}) as result`))[0].result;
}
for(const order of [['app_test','mission'],['mission','app_test']])for(const status of ['pending','approved','rejected']){
  const db=await fixture();
  const label=order.join(' -> ')+' with first '+status;
  try{
    const first=await submit(db,order[0],1);
    if(status==='approved')await actor(db,owner,`select public.approve_tester_report('${first.report_id}',0)`);
    if(status==='rejected')await actor(db,owner,`select public.reject_tester_report('${first.report_id}','Fresh evidence required','INVALID_SCREENSHOT')`);
    const second=await submit(db,order[1],2);
    assert.notEqual(first.report_id,second.report_id);
    const before=(await db.query(`select id,submission_context,mission_id,status from public.tester_reports order by submission_context`)).rows;
    assert.equal(before.length,2);
    assert.equal(before.find(r=>r.id===first.report_id).status,status);
    assert.equal(before.find(r=>r.id===second.report_id).status,'pending');
    const updated=await submit(db,order[1],3);
    assert.equal(updated.report_id,second.report_id,'same-kind daily update must reuse the daily report');
    assert.equal((await db.query('select count(*)::int n from public.tester_reports')).rows[0].n,2);
    console.log('PASS independent daily state matrix:',label);
  }catch(error){console.error('FAIL independent daily state matrix:',label,error.code??'',error.message);process.exitCode=1;}
  finally{await db.close();}
}

{
  const db=await fixture();
  try{
    const dayOne=await submit(db,'mission',11);
    await actor(db,owner,`select public.approve_tester_report('${dayOne.report_id}',0)`);
    // Move the already-approved fixture into yesterday to simulate an elapsed reporting day.
    // Keep the real approval side effects (participant state, settlement, ledger) unchanged.
    const hasDate=(await db.query("select exists(select 1 from information_schema.columns where table_schema='public' and table_name='tester_reports' and column_name='report_date') present")).rows[0].present;
    assert.equal(hasDate,true,'new daily report identity requires a server reporting date');
    await db.exec(`update public.missions set created_at=now()-interval '1 day' where id='${mission}';
      update public.mission_participants set joined_at=now()-interval '1 day' where mission_id='${mission}' and user_id='${tester}';
      update public.tester_reports set created_at=now()-interval '1 day', report_date=(now() at time zone 'Asia/Ho_Chi_Minh')::date-1 where id='${dayOne.report_id}';`);
    const dayTwo=await submit(db,'mission',12);
    assert.notEqual(dayTwo.report_id,dayOne.report_id,'day-one approval must not consume the next daily mission report');
    assert.equal((await db.query("select count(*)::int n from public.tester_reports where submission_context='mission'")).rows[0].n,2);
    console.log('PASS independent later-day mission report after actual owner approval');
  }catch(error){console.error('FAIL independent later-day mission report:',error.code??'',error.message);process.exitCode=1;}
  finally{await db.close();}
}
