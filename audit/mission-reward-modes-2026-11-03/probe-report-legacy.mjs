import assert from 'node:assert/strict';
import {readdirSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const owner='11111111-1111-1111-1111-111111111111',tester='22222222-2222-2222-2222-222222222222',campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const id=n=>'dddddddd-dddd-dddd-dddd-'+String(n).padStart(12,'0');
const url=(n,thumb=false)=>'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F'+campaign+'%2F'+tester+'%2F'+(thumb?'thumb_':'')+id(n)+'.jpg?alt=media&token=fixture';
const images=[{id:id(1),position:0,screenshot_url:url(1),thumbnail_url:url(1,true),image_hash:'0123456789abcdef',sha256:'a'.repeat(64)}];
const db=await createDb({legacyGrants:true});
const row=async(sql,p)=>(await db.query(sql,p)).rows[0];
async function actor(uid,body){await db.exec("begin;select set_config('request.jwt.claim.sub','"+uid+"',true);set local role authenticated;");try{const v=await body();await db.exec('commit');return v}catch(e){await db.exec('rollback');throw e}}
const v3=async(n,rating)=>(await row("select public.submit_tester_report_v3(p_submission_context=>'mission',p_campaign_id=>'"+campaign+"',p_content=>'Fixture',p_rating=>"+rating+",p_client_submit_id=>'"+id(n)+"',p_mission_id=>'"+mission+"',p_attachments=>$1::jsonb) result",[JSON.stringify(images)])).result;
try{
for(const f of readdirSync('supabase/migrations').filter(f=>/^\d{3}_.*\.sql$/.test(f)&&+f.slice(0,3)>=62).sort())await db.exec(read('supabase/migrations/'+f));
await db.exec("insert into auth.users(id,email) values('"+owner+"','owner@local.invalid'),('"+tester+"','tester@local.invalid'); update public.profiles set credits=10000 where id='"+owner+"'; insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values('"+campaign+"','"+owner+"','invalid.fixture.app','Fixture','open',1); insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at) values('"+mission+"','"+owner+"','"+campaign+"','Legacy mission',10,3,20,'open',now()+interval '2 days'); insert into public.mission_participants(mission_id,user_id,status) values('"+mission+"','"+tester+"','joined');");
const original=await actor(tester,()=>v3(100,5));
const updated=await actor(tester,()=>v3(101,2));
const actual=(await row("select rating from public.tester_reports where id='"+original.report_id+"'")).rating;
assert.equal(actual,2);assert.equal(updated.report_id,original.report_id);
if(updated.rating!==actual){console.log('FAIL pending update immutable receipt: database rating '+actual+' but receipt rating '+updated.rating);process.exitCode=1}else console.log('PASS pending update receipt matches saved rating');
assert.deepEqual(await actor(tester,()=>v3(101,2)),updated);
const balance=async()=>(await row("select credits from public.profiles where id='"+tester+"'")).credits;
const before=await balance();
await db.exec("update public.missions set status='expired', created_at=now()-interval '3 days', ends_at=now()-interval '1 hour' where id='"+mission+"'; update public.mission_participants set joined_at=now()-interval '3 hours' where mission_id='"+mission+"'; update public.tester_reports set created_at=now()-interval '2 hours', mission_submitted_at=now()-interval '2 hours', mission_ends_at=now()-interval '1 hour' where id='"+original.report_id+"';");
await actor(owner,()=>row("select public.approve_tester_report('"+original.report_id+"',0) result"));
const paid=(await row("select coalesce(sum(amount),0)::int paid from public.credit_logs where user_id='"+tester+"' and action='mission_reward'")).paid;
if(paid!==0){console.log('FAIL legacy natural expiry: previously forfeited reward was paid '+paid+' (balance delta '+((await balance())-before)+')');process.exitCode=1}else console.log('PASS legacy natural expiry preserves old payout policy');
}finally{await db.close()}
