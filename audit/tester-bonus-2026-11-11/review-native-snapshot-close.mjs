import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
const config={host:'127.0.0.1',port:55439,user:'bonus_test_admin',database:'tester_bonus_test',connectionTimeoutMillis:5000,statement_timeout:12000};
const root=new pg.Client(config),gate=new pg.Client(config),submit=new pg.Client(config),close=new pg.Client(config);
const clients=[root,gate,submit,close];
async function actor(client,id,sql,args){
 await client.query('begin');
 try{await client.query("select set_config('request.jwt.claim.sub',$1,true)",[id]);await client.query('set local role authenticated');const r=await client.query(sql,args);await client.query('commit');return r.rows[0].result;}
 catch(e){await client.query('rollback');throw e;}
}
try{
 await Promise.all(clients.map(c=>c.connect()));
 const server=(await root.query("select current_setting('data_directory') as dir")).rows[0];
 assert.equal(server.dir.replaceAll('\\','/').toLowerCase(),'f:/ai/12tester/audit/tester-bonus-2026-11-11/pg-data');
 const owner=randomUUID(),tester=randomUUID(),campaign=randomUUID(),request=randomUUID();
 await root.query('insert into auth.users(id,email) values($1,$3),($2,$4)',[owner,tester,`${owner}@fixture.local`,`${tester}@fixture.local`]);
 await root.query('update public.profiles set credits=10000 where id=any($1::uuid[])',[[owner,tester]]);
 await root.query("insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values($1,$2,$3,'Snapshot close fixture','open',1)",[campaign,owner,`fixture.snapshot.${campaign.replaceAll('-','')}`]);
 const round=await actor(root,owner,'select public.create_campaign_tester_bonus($1,5,2,$2) as result',[campaign,randomUUID()]);
 await root.query("insert into public.feedbacks(campaign_id,user_id,rating,comment) values($1,$2,5,'Existing fixture feedback')",[campaign,tester]);
 await gate.query('begin');
 await gate.query('select id from public.feedbacks where campaign_id=$1 and user_id=$2 for update',[campaign,tester]);
 const url=`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${request}.jpg?alt=media&token=fixture`;
 const submission=actor(submit,tester,`select public.submit_tester_report_v2(p_submission_context=>'app_test',p_campaign_id=>$1,p_content=>'Pending snapshot must retain budget during close',p_client_submit_id=>$2,p_screenshot_url=>$3,p_image_hash=>'0123456789abcdef') as result`,[campaign,request,url]).then(value=>({ok:true,value}),error=>({ok:false,error:error.message}));
 let gated=false;
 const until=Date.now()+5000;
 while(Date.now()<until){const r=(await root.query('select $1::int=any(pg_blocking_pids($2)) as blocked',[gate.processID,submit.processID])).rows[0];if(r.blocked){gated=true;break;}await new Promise(r=>setTimeout(r,20));}
 assert.equal(gated,true,'Submission must reach controlled feedback-write gate');
 let closedBeforeRelease=false;
 const closing=actor(close,owner,'select public.close_campaign_tester_bonus($1) as result',[campaign]).then(value=>{closedBeforeRelease=true;return {ok:true,value};},error=>({ok:false,error:error.message}));
 // Wait for either closing completion or its actual campaign/round wait on submission.
 const deadline=Date.now()+3000;
 let waitingOnSubmission=false;
 while(Date.now()<deadline&&!closedBeforeRelease){const r=(await root.query('select $1::int=any(pg_blocking_pids($2)) as blocked',[submit.processID,close.processID])).rows[0];if(r.blocked){waitingOnSubmission=true;break;}await new Promise(r=>setTimeout(r,20));}
 const finishedBeforeRelease=closedBeforeRelease;
 await gate.query('commit');
 const [submitted,closed]=await Promise.all([submission,closing]);
 assert.equal(submitted.ok,true,submitted.error);assert.equal(closed.ok,true,closed.error);
 const report=(await root.query('select tester_bonus_round_id from public.tester_reports where id=$1',[submitted.value.report_id])).rows[0];
 const ledger=(await root.query('select status,remaining_credits,refunded_credits from public.campaign_tester_bonus_rounds where id=$1',[round.round_id])).rows[0];
 console.log(JSON.stringify({closedBeforeGateRelease:finishedBeforeRelease,waitingOnSubmission,report,ledger}));
 if(report.tester_bonus_round_id){assert.equal(ledger.refunded_credits,0,'Cannot refund budget before an in-flight eligible report becomes visible');assert.equal(ledger.remaining_credits,10);assert.equal(ledger.status,'closing');}
 console.log('PASS snapshot submission versus close preserves accepted pending obligation');
}catch(error){console.error(JSON.stringify({error:error.message,code:error.code}));process.exitCode=1;}
finally{await gate.query('rollback').catch(()=>{});await Promise.all(clients.map(c=>c.end().catch(()=>{})));}
