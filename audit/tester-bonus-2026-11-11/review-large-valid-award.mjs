import assert from 'node:assert/strict';
import {readdirSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
async function actor(uid,sql,args){
 await db.exec('begin');try{await db.query("select set_config('request.jwt.claim.sub',$1,true)",[uid]);await db.exec('set local role authenticated');const r=await db.query(sql,args);await db.exec('commit');return r.rows[0].result;}catch(e){await db.exec('rollback');throw e;}
}
try{
 for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort())await db.exec(read(`supabase/migrations/${name}`));
 const owner=randomUUID(),tester=randomUUID(),campaign=randomUUID(),request=randomUUID();
 await db.query('insert into auth.users(id,email) values($1,$3),($2,$4)',[owner,tester,`${owner}@fixture.local`,`${tester}@fixture.local`]);
 await db.query('update public.profiles set credits=2147483647 where id=$1',[owner]);
 await db.query('update public.profiles set credits=0 where id=$1',[tester]);
 await db.query("insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values($1,$2,$3,'Valid large award fixture','open',1)",[campaign,owner,`fixture.large.${campaign.replaceAll('-','')}`]);
 await actor(owner,'select public.create_campaign_tester_bonus($1,2147483640,1,$2) as result',[campaign,randomUUID()]);
 const url=`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${request}.jpg?alt=media&token=fixture`;
 const report=await actor(tester,`select public.submit_tester_report_v2(p_submission_context=>'app_test',p_campaign_id=>$1,p_content=>'Large valid reward must not overflow response total',p_client_submit_id=>$2,p_screenshot_url=>$3,p_image_hash=>'0123456789abcdef') as result`,[campaign,request,url]);
 // Isolated fixture models spending the provisional credits before review.
 // All wallet states remain valid/nonnegative; cumulative report earnings can exceed current wallet.
 await db.query('update public.profiles set credits=0 where id=$1',[tester]);
 const approved=await actor(owner,'select public.approve_tester_report($1) as result',[report.report_id]);
 assert.equal(approved.tester_bonus_awarded,2147483640);
 assert.ok(Number(approved.total_earned)>2147483647);
 const balance=(await db.query('select credits from public.profiles where id=$1',[tester])).rows[0].credits;
 assert.ok(balance<=2147483647&&balance>0);
 console.log(JSON.stringify({pass:true,earned:approved.total_earned,balance}));
}catch(error){console.error(JSON.stringify({error:error.message,code:error.code,where:error.where}));process.exitCode=1;}
finally{await db.close();}
