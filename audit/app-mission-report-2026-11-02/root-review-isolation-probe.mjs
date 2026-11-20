import assert from 'node:assert/strict';
import {readdirSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const owner='11111111-1111-1111-1111-111111111111',tester='22222222-2222-2222-2222-222222222222';
const campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const id=n=>'eeeeeeee-eeee-eeee-eeee-'+String(n).padStart(12,'0');
const migrations=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d{3}_.*\.sql$/.test(f)&&+f.slice(0,3)>=62).sort().map(f=>read('supabase/migrations/'+f));
async function fixture(){
 const db=await createDb({legacyGrants:true});for(const sql of migrations)await db.exec(sql);
 await db.exec(`insert into auth.users(id,email) values('${owner}','owner@review.invalid'),('${tester}','tester@review.invalid');
 update public.profiles set credits=10000 where id='${owner}';
 insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values('${campaign}','${owner}','invalid.review.app','Review probe','open',1);
 insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at) values('${mission}','${owner}','${campaign}','Review mission',10,3,20,'open',now()+interval '2 days');
 insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${tester}','joined');`);return db;
}
async function actor(db,uid,sql){
 await db.exec(`begin;select set_config('request.jwt.claim.sub','${uid}',true);set local role authenticated;`);
 try{const value=(await db.query(sql)).rows;await db.exec('commit');return value;}catch(e){await db.exec('rollback');throw e;}
}
async function submit(db,context,n){return(await actor(db,tester,`select public.submit_tester_report_v2(p_submission_context=>'${context}',p_campaign_id=>'${campaign}',p_content=>'Review isolation ${n}',p_rating=>5,p_client_submit_id=>'${id(n)}',p_screenshot_url=>'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${id(n)}.jpg?alt=media&token=fixture',p_image_hash=>'0123456789abcdef'${context==='mission'?`,p_mission_id=>'${mission}'`:''}) result`))[0].result;}
async function approve(db,report){return actor(db,owner,`select public.approve_tester_report('${report.report_id}',0)`);}
async function checkins(db){return(await db.query(`select campaign_id,user_id,checkin_date::text,note from public.checkins where campaign_id='${campaign}' and user_id='${tester}' order by checkin_date`)).rows;}
async function test(name,body){const db=await fixture();try{await body(db);console.log('PASS independent review isolation:',name);}catch(e){console.error('FAIL independent review isolation:',name,e.code??'',e.message);process.exitCode=1;}finally{await db.close();}}
await test('approving only a mission cannot consume ordinary daily checkin/quota',async db=>{
 const special=await submit(db,'mission',1);await approve(db,special);assert.equal((await checkins(db)).length,0,'mission approval must not fabricate an ordinary checkin');
});
await test('ordinary provisional policy reward is unchanged after mission approval',async db=>{
 const special=await submit(db,'mission',1);await approve(db,special);const ordinary=await submit(db,'app_test',2);
 assert.equal(ordinary.provisional_amount,10,'mission approval must not suppress ordinary policy provisional reward');
});
await test('rejecting mission preserves existing ordinary pending checkin and report',async db=>{
 const ordinary=await submit(db,'app_test',1);const special=await submit(db,'mission',2);const before=await checkins(db);
 await actor(db,owner,`select public.reject_tester_report('${special.report_id}','New mission screenshot required','INVALID_SCREENSHOT')`);
 assert.deepEqual(await checkins(db),before,'mission rejection must not delete the separate ordinary checkin');
 const report=(await db.query(`select status,provisional_granted_credits from public.tester_reports where id='${ordinary.report_id}'`)).rows[0];assert.equal(report.status,'pending');assert.equal(report.provisional_granted_credits,10);
});
await test('automatic acceptance of mission does not fabricate ordinary checkin',async db=>{
 const special=await submit(db,'mission',1);
 await db.query(`update public.tester_reports set created_at=now()-interval '9 hours',report_date=((now()-interval '9 hours') at time zone 'Asia/Ho_Chi_Minh')::date where id='${special.report_id}'`);
 await db.query('select public.auto_accept_expired_reports()');
 const report=(await db.query(`select status from public.tester_reports where id='${special.report_id}'`)).rows[0];assert.equal(report.status,'approved');assert.equal((await checkins(db)).length,0,'auto-approved mission must keep ordinary slot untouched');
});
await test('later-day mission can be approved without paying participant escrow twice',async db=>{
 const first=await submit(db,'mission',1);await approve(db,first);
 const afterFirst=(await db.query(`select count(*)::int n from public.credit_logs where user_id='${tester}' and action='mission_reward'`)).rows[0].n;
 assert.equal(afterFirst,1,'fixture must really settle the first one-time participant reward');
 await db.exec(`update public.missions set created_at=now()-interval '1 day' where id='${mission}';update public.mission_participants set joined_at=now()-interval '1 day' where mission_id='${mission}' and user_id='${tester}';update public.tester_reports set created_at=now()-interval '1 day',report_date=(now() at time zone 'Asia/Ho_Chi_Minh')::date-1 where id='${first.report_id}';`);
 const second=await submit(db,'mission',2);await approve(db,second);
 assert.equal((await db.query(`select status from public.tester_reports where id='${second.report_id}'`)).rows[0].status,'approved');
 assert.equal((await db.query(`select count(*)::int n from public.credit_logs where user_id='${tester}' and action='mission_reward'`)).rows[0].n,afterFirst,'one-time reserved mission reward cannot be paid twice');
});
