// Foreground, isolated native test lifecycle. Execute through the already-authorized
// Antigravity session. This does not consume any existing DATABASE_URL or reset a DB.
import assert from 'node:assert/strict';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdirSync,readFileSync,readdirSync,writeFileSync,openSync,closeSync,realpathSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
import {runNativeReviewProbes} from './supervisor-native-r4-probes.mjs';

const exec=promisify(execFile),audit=fileURLToPath(new URL('./',import.meta.url)),workspace=resolve(audit,'../..');
const data=resolve(audit,'pg-data'),bin=resolve(workspace,'audit/tester-bonus-2026-11-11/pg-runtime/node_modules/@embedded-postgres/windows-x64/native/bin');
const normalized=s=>s.replaceAll('\\','/').toLowerCase();
assert.equal(normalized(realpathSync(data)),normalized(data),'Refuse redirected data directory');
const config={host:'127.0.0.1',port:55444,user:'postgres',database:'postgres',connectionTimeoutMillis:2500,statement_timeout:25000};
const out=join(audit,'supervisor-runs','native-r4-'+new Date().toISOString().replace(/[:.]/g,'-'));
mkdirSync(out,{recursive:true});
const paths=readdirSync(join(workspace,'supabase/migrations')).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort().map(n=>'supabase/migrations/'+n);
paths.push('audit/daily-task-action-gate-2026-11-12/supervisor-native-r4-probes.mjs',
  'audit/daily-task-action-gate-2026-11-12/supervisor-native-r4-runner.mjs',
  'supabase/tests/run-campaign-tester-bonus-concurrency-test.mjs','supabase/tests/campaign-tester-bonus-concurrency-fixture.mjs');
const hashes=()=>paths.map(path=>({path,sha256:createHash('sha256').update(readFileSync(join(workspace,path))).digest('hex')}));
const result={at:new Date().toISOString(),output:out,pass:false,sourceBefore:hashes(),nativeReview:null,bonus:null,error:null,cleanup:null};
writeFileSync(join(out,'source-before.json'),JSON.stringify(result.sourceBefore,null,2));
const pause=ms=>new Promise(r=>setTimeout(r,ms));
let server=null,serverExit=null,serverError=null,admin=null,test=null,verified=false,serverFd=null;
async function connect(database='postgres'){
  const c=new pg.Client({...config,database});c.on('error',()=>{});
  try{await c.connect();const s=(await c.query('select current_setting(\'data_directory\') directory,pg_backend_pid() pid,version() version')).rows[0];
    assert.equal(normalized(s.directory),normalized(data),'Refuse foreign native cluster');return {client:c,server:s};
  }catch(e){await c.end().catch(()=>{});throw e;}
}
try{
  // Never stop or adopt a server started by another driver.
  let active=null;
  try{active=await connect();}catch(e){if(e.code!=='ECONNREFUSED')throw e;}
  if(active){await active.client.end();throw new Error('Port already active; refusing to interfere with another running driver');}
  serverFd=openSync(join(out,'postgres-server.log'),'a');
  server=spawn(join(bin,'postgres.exe'),['-D',data,'-h',config.host,'-p',String(config.port)],
    {cwd:workspace,windowsHide:true,stdio:['ignore',serverFd,serverFd]});
  serverExit=new Promise(r=>{server.once('exit',(code,signal)=>r({code,signal}));server.once('error',error=>{serverError=error;r({error:{code:error.code,message:error.message}});});});
  let connection=null,lastError=null;
  const deadline=Date.now()+20000;
  while(Date.now()<deadline){
    if(serverError)throw serverError;
    if(server.exitCode!==null)throw new Error(`Owned postgres exited early (${server.exitCode})`);
    try{connection=await connect();break;}catch(e){lastError=e;if(!['ECONNREFUSED','57P03'].includes(e.code))throw e;await pause(150);}
  }
  if(!connection)throw lastError??new Error('Native server readiness deadline');
  admin=connection.client;
  assert.equal(Number(readFileSync(join(data,'postmaster.pid'),'utf8').split(/\r?\n/)[0]),server.pid,'Connected server must be this driver\'s owned process');
  verified=true;result.server={...connection.server,ownedProcessPid:server.pid};
  result.database='action_gate_native_test_r4_'+Date.now();
  await admin.query('create database '+result.database);
  await admin.end();admin=null;
  test=(await connect(result.database)).client;
  await test.query(`create extension if not exists pgcrypto;create extension if not exists "uuid-ossp";
    do $$ begin
      if not exists(select 1 from pg_roles where rolname='anon') then create role anon nologin; end if;
      if not exists(select 1 from pg_roles where rolname='authenticated') then create role authenticated nologin; end if;
      if not exists(select 1 from pg_roles where rolname='service_role') then create role service_role nologin bypassrls; end if;
    end $$;
    create schema auth;create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz,confirmation_token text,email_change text,email_change_token_new text,recovery_token text);
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    grant usage on schema public,auth to anon,authenticated,service_role;
    grant execute on function auth.uid() to anon,authenticated,service_role;`);
  for(const path of paths.filter(p=>p.startsWith('supabase/migrations/'))){
    await test.query('begin');
    try{await test.query(readFileSync(join(workspace,path),'utf8'));await test.query('commit');console.log('LOADED',path);}
    catch(e){await test.query('rollback');throw e;}
  }
  await test.end();test=null;
  result.nativeReview=await runNativeReviewProbes({...config,database:result.database});
  writeFileSync(join(out,'review-probes.json'),JSON.stringify(result.nativeReview,null,2));
  assert.equal(result.nativeReview.pass,true,'Independent native review cases failed; inspect case evidence');
  // The unchanged seven-scenario financial test runs only in this verified, newly
  // created local database, while this foreground parent owns the server lifecycle.
  const bonusFile=join(out,'bonus-concurrency.log');
  const bonusFd=openSync(bonusFile,'a');
  try{
    const child=spawn(process.execPath,[join(workspace,'supabase/tests/run-campaign-tester-bonus-concurrency-test.mjs')],
      {cwd:workspace,windowsHide:true,env:{...process.env,TEST_DATABASE_URL:`postgresql://postgres@127.0.0.1:55444/${result.database}`},stdio:['ignore',bonusFd,bonusFd]});
    const completed=await new Promise((resolveChild,reject)=>{
      const timer=setTimeout(()=>{child.kill();reject(new Error('Owned bonus concurrency child exceeded 120 seconds'));},120000);
      child.once('error',e=>{clearTimeout(timer);reject(e);});child.once('exit',(code,signal)=>{clearTimeout(timer);resolveChild({code,signal});});
    });
    const log=readFileSync(bonusFile,'utf8');
    result.bonus={...completed,pass:completed.code===0&&/ALL REAL POSTGRESQL CONCURRENCY SCENARIOS PASSED!/.test(log),log:bonusFile,
      scenarioPassLines:log.split(/\r?\n/).filter(s=>s.startsWith('PASS:'))};
    assert.equal(result.bonus.pass,true,'Native bonus concurrency regression failed');
    assert.equal(result.bonus.scenarioPassLines.length,7,'Need all seven real financial scenarios');
  }finally{closeSync(bonusFd);}
  result.sourceAfter=hashes();assert.deepEqual(result.sourceAfter,result.sourceBefore,'Source changed during native verification');
  result.pass=true;
}catch(error){result.error={code:error.code??null,message:error.message,stack:error.stack};console.error(error);}
finally{
  await test?.end().catch(()=>{});await admin?.end().catch(()=>{});
  if(server){
    if(verified){
      try{await exec(join(bin,'pg_ctl.exe'),['-D',data,'-m','fast','-w','-t','10','stop'],{cwd:workspace,windowsHide:true,timeout:15000});result.cleanup={gracefulStop:true};}
      catch(error){result.cleanup={gracefulStop:false,error:{code:error.code,message:error.message}};}
    }
    if(server.exitCode===null&&!serverError){server.kill();result.cleanup={...result.cleanup,ownedProcessKillRequested:true};}
    const exited=await Promise.race([serverExit,pause(3000).then(()=>({unconfirmed:true}))]);
    result.cleanup={...result.cleanup,exit:exited};
    if(exited?.unconfirmed){result.pass=false;result.cleanup.warning='Owned native process exit not confirmed';}
  }
  if(serverFd!==null)closeSync(serverFd);
  writeFileSync(join(out,'results.json'),JSON.stringify(result,null,2));
  writeFileSync(join(audit,'supervisor-native-r4-latest.json'),JSON.stringify({output:out,...result},null,2));
  console.log(JSON.stringify({pass:result.pass,error:result.error,reviewCases:result.nativeReview?.cases?.map(c=>({name:c.name,pass:c.pass,error:c.error})),bonus:result.bonus,cleanup:result.cleanup,output:out}));
  process.exitCode=result.pass?0:1;
}
