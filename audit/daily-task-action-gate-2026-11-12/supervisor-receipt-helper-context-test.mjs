// Isolated fresh PGlite: permission/trigger-context tests, NOT concurrency evidence.
import assert from 'node:assert/strict';
import {readdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
const applied=[],results=[];
const owner='fa000000-0000-0000-0000-000000000002';
const fake='fb000000-0000-0000-0000-000000000002';
const call=`select public._record_campaign_removal_receipt('${fake}','${owner}','Never existed','owner','removed','Untrusted client proof')`;
async function trial(nested){
  const result={case:nested?'untrusted_temp_trigger_cannot_author_receipt':'actual_authenticated_session_cannot_author_receipt'};
  await db.exec('begin');
  try{
    await db.exec(`set local session authorization supervisor_receipt_authenticator;set local role authenticated;select set_config('request.jwt.claim.sub','${owner}',true)`);
    result.actor=(await db.query(`select current_user,session_user,auth.uid() uid,has_database_privilege(current_user,current_database(),'TEMP') can_temp`)).rows[0];
    if(nested){
      // All objects below are created by the actual unprivileged actor, not by the fixture owner.
      await db.exec(`create temp table supervisor_receipt_probe(n int);
        create function pg_temp.supervisor_receipt_probe_fn() returns trigger language plpgsql as $probe$
        begin perform public._record_campaign_removal_receipt('${fake}','${owner}','Never existed','owner','removed','Untrusted client proof');return new;end;$probe$;
        create trigger supervisor_receipt_probe_trigger before insert on supervisor_receipt_probe for each row execute function pg_temp.supervisor_receipt_probe_fn()`);
    }
    await db.exec('savepoint invocation');
    try{result.reply=(await db.query(nested?'insert into supervisor_receipt_probe values(1) returning n':call)).rows;}
    catch(e){result.error=e.message;await db.exec('rollback to invocation');}
    await db.exec('set session authorization postgres;reset role');
    result.receipts=(await db.query(`select campaign_id,owner_id,reason from public.campaign_removal_receipts where campaign_id='${fake}'`)).rows;
    assert.equal(result.receipts.length,0,'untrusted actor authored a server removal receipt');
    assert.ok(result.error,'public receipt writer unexpectedly accepted arbitrary client arguments');
    result.pass=true;
  }catch(e){result.pass=false;result.failure=e.message;}
  finally{await db.exec('rollback;set session authorization postgres;reset role');results.push(result);console.log(JSON.stringify(result));}
}
try{
  for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort()){
    const sql=read('supabase/migrations/'+name);await db.exec(sql);applied.push({name,sha256:createHash('sha256').update(sql).digest('hex')});
  }
  await db.exec(`create role supervisor_receipt_authenticator noinherit;grant authenticated to supervisor_receipt_authenticator;
    insert into auth.users(id,email) values('${owner}','receipt-context@fixture.invalid');
    update public.profiles set role='member',is_locked=false where id='${owner}'`);
  await trial(false);await trial(true);
}finally{
  await db.close();
  const output={at:new Date().toISOString(),singleConnection:true,applied,results};
  writeFileSync(new URL('./supervisor-receipt-helper-context-results.json',import.meta.url),JSON.stringify(output,null,2));
}
assert.ok(results.length===2&&results.every(r=>r.pass),'receipt helper context authorization failed');
console.log('PASS 2/2 real caller/helper-context authorization cases; not concurrency.');
