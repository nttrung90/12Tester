// Fresh PGlite only. Receipts must remain server-authored evidence, not client input.
import assert from 'node:assert/strict';
import {readdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();const results=[],applied=[];
const owner='fa000000-0000-0000-0000-000000000001';
const removed='fb000000-0000-0000-0000-000000000001';
const unknown='fb000000-0000-0000-0000-000000000099';
async function trial(name,sql,seed=false){
  await db.exec('begin');const result={case:name};
  try{
    if(seed)await db.exec(`insert into public.campaign_removal_receipts(campaign_id,owner_id,app_name,removed_by,status,reason) values('${removed}','${owner}','Removed app','owner','removed','Server original proof')`);
    await db.exec('savepoint client_attempt');
    let error=null;
    try{
      await db.exec(`set local role authenticated;select set_config('request.jwt.claim.sub','${owner}',true)`);
      result.actor=(await db.query('select current_user,session_user,auth.uid() uid')).rows[0];
      result.reply=(await db.query(sql)).rows;
    }catch(e){error=e.message;await db.exec('rollback to client_attempt');}
    await db.exec('reset role');result.error=error;
    assert.ok(error,'client attempt unexpectedly succeeded');
    if(name==='unknown_id_has_no_replay_proof')assert.match(error,/CAMPAIGN_NOT_FOUND/);
    const receipts=(await db.query('select campaign_id,reason from public.campaign_removal_receipts order by campaign_id')).rows;
    assert.equal(receipts.length,seed?1:0,'client changed receipt ledger');
    if(seed)assert.equal(receipts[0].reason,'Server original proof');
    result.pass=true;
  }catch(e){result.pass=false;result.failure=e.message;}
  finally{await db.exec('rollback');results.push(result);console.log(JSON.stringify(result));}
}
try{
  for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort()){
    const sql=read('supabase/migrations/'+name);await db.exec(sql);applied.push({name,sha256:createHash('sha256').update(sql).digest('hex')});
  }
  await db.exec(`insert into auth.users(id,email) values('${owner}','receipt-owner@fixture.invalid');update public.profiles set role='member',is_locked=false,last_login_date=(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date where id='${owner}'`);
  await trial('client_cannot_forge_receipt',`insert into public.campaign_removal_receipts(campaign_id,owner_id,app_name,removed_by,status,reason) values('${removed}','${owner}','Fake app','owner','removed','Client forged proof') returning campaign_id`);
  await trial('client_cannot_rewrite_server_receipt',`update public.campaign_removal_receipts set reason='Client rewrote proof' where campaign_id='${removed}' returning campaign_id`,true);
  await trial('unknown_id_has_no_replay_proof',`select public.delete_owned_campaign('${unknown}') r`);
}finally{
  await db.close();writeFileSync(new URL('./supervisor-receipt-authorization-results.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),singleConnection:true,applied,results},null,2));
}
assert.equal(results.length,3);assert.ok(results.every(r=>r.pass));console.log('PASS 3/3 receipt authorization cases; not concurrency.');
