import assert from 'node:assert/strict';
import {freshNative} from './fresh-native.mjs';
import {setup,OWNER,ADMIN,TODAY} from './independent-fixture.mjs';
const db=await freshNative('immediate_quota_native_govern_'+Date.now());
try {
  await db.query('begin');await setup(db);
  for(const [label,role,uid,allowed] of [
    ['ordinary member','authenticated',OWNER,false],
    ['unidentified authenticated','authenticated','',false],
    ['authorized admin','authenticated',ADMIN,true],
    ['trusted service role','service_role','',true],
  ]) {
    await db.query('savepoint actor');await db.query('set local role '+role);
    await db.query("select set_config('request.jwt.claim.sub',$1,true)",[uid]);
    let success,error;
    try {await db.query(`select public.set_daily_task_immediate_quota_policy(${TODAY}+10,true,'governance verification')`);success=true;}
    catch(e){success=false;error=e.message;}
    await db.query('rollback to savepoint actor;release savepoint actor');
    console.log(JSON.stringify({label,success,error,expected:allowed}));
    if(success!==allowed)process.exitCode=1;
  }
  await db.query('rollback');
}finally{await db.end();}
