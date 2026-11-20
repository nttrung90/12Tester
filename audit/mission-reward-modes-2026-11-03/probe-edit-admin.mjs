import assert from 'node:assert/strict'
import { readdirSync } from 'node:fs'
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs'
const db = await createDb({ legacyGrants: true })
const owner = '11111111-1111-1111-1111-111111111111'
const admin = '22222222-2222-2222-2222-222222222222'
const tester = '33333333-3333-3333-3333-333333333333'
const camp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const row = async(sql)=>(await db.query(sql)).rows[0]
async function call(user,sql){await db.exec("select set_config('request.jwt.claim.sub', '"+user+"', false); set role authenticated;");try{return (await row(sql)).result}finally{await db.exec("reset role; select set_config('request.jwt.claim.sub', '', false);")}}
const balance=async()=> (await row("select credits from public.profiles where id='"+owner+"'")).credits
try {
 for(const f of readdirSync('supabase/migrations').filter(x=>/^\d{3}_.*\.sql$/.test(x)&&+x.slice(0,3)>=62).sort())await db.exec(read('supabase/migrations/'+f))
 await db.exec("insert into auth.users(id,email) values ('"+owner+"','owner@local.invalid'),('"+admin+"','admin@local.invalid'),('"+tester+"','tester@local.invalid'); update public.profiles set credits=1000 where id='"+owner+"'; update public.profiles set role='admin' where id='"+admin+"'; insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values ('"+camp+"','"+owner+"','test.mission.edit','Example','completed',1);")
 const m=await call(owner,"select public.create_mission_v2('"+camp+"','Original','fixture',5,1,10,'once') result")
 assert.equal(await balance(),900)
 const q=await call(owner,"select public.preview_edit_mission('"+m.mission_id+"','once',2,2,10) result")
 assert.equal(q.total_diff,20);assert.equal(q.escrow_diff,-30);assert.equal(q.fee_diff,50)
 const sumBefore=(await row("select coalesce(sum(amount),0)::int n from public.credit_logs where user_id='"+owner+"'")).n
 const edit="select public.edit_mission('"+m.mission_id+"','Edited','fixture','once',2,2,10,null,"+q.version+","+q.total_diff+") result"
 await call(owner,edit);assert.equal(await balance(),880)
 const sumAfter=(await row("select coalesce(sum(amount),0)::int n from public.credit_logs where user_id='"+owner+"'")).n
 if(sumAfter-sumBefore!==-20){console.log('FAIL mixed edit ledger: wallet delta -20, ledger delta '+(sumAfter-sumBefore));process.exitCode=1}
 else console.log('PASS mixed edit ledger reconciles fee increase and escrow refund')
 await assert.rejects(call(owner,edit),/CONCURRENT_MODIFICATION/);assert.equal(await balance(),880)
 console.log('PASS stale repeated edit cannot debit twice')
 const del="select public.admin_delete_mission('"+m.mission_id+"','fixture closure') result"
 await assert.rejects(call(tester,del),/PERMISSION_DENIED/)
 const d=await call(admin,del);assert.equal(d.status,'deleted')
 assert.equal(await balance(),900)
 const replay=await call(admin,del);assert.equal(replay.is_replay,true);assert.equal(await balance(),900)
 const audit=(await row("select count(*)::int n from public.admin_action_events where action='admin_delete_mission' and target_id='"+m.mission_id+"'")).n
 assert.equal(audit,1)
 console.log('PASS Admin closure authorizes, audits and refunds once')

 const held=await call(owner,"select public.create_mission_v2('"+camp+"','Escrow still held','fixture',2,1,10,'once') result");
 assert.equal(await balance(),830);
 try {
   const removed=await call(owner,"delete from public.campaigns where id='"+camp+"' returning id as result");
   const leftover=(await row("select count(*)::int n from public.missions where id='"+held.mission_id+"'")).n;
   if(removed===camp&&leftover===0&&await balance()===830){console.log('FAIL campaign deletion cascaded away a 20-Credit unrefunded mission escrow');process.exitCode=1}
   else throw new Error('Unexpected campaign deletion outcome');
 }catch(e){if(/MISSION.*ESCROW|UNSETTLED.*MISSION|MISSION.*UNSETTLED/i.test(e.message)){assert.equal(await balance(),830);console.log('PASS app deletion blocks while mission escrow is held')}else throw e}
}finally{await db.close()}
