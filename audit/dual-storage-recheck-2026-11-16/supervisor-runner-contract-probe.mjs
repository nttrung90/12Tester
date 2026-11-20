// Only syntax, missing-environment and refusal paths. Never connects to a database.
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const cwd=fileURLToPath(new URL('../../',import.meta.url));
const runner='supabase/tests/run-dual-storage-concurrency-test.mjs';
const cleanEnv={...process.env};delete cleanEnv.TEST_DATABASE_URL;delete cleanEnv.DUAL_STORAGE_TEST_DISPOSABLE;
const results=[];
const run=(args,extra={})=>spawnSync(process.execPath,args,{cwd,env:{...cleanEnv,...extra},encoding:'utf8',timeout:15000});
const test=(name,fn)=>{try{const observed=fn();results.push({name,status:'PASS',observed});}catch(e){results.push({name,status:'FAIL',error:e.message});}};
test('runner parses',()=>{const r=run(['--check',runner]);assert.equal(r.status,0,r.stderr);});
test('missing environment is explicitly NOT RUN',()=>{
 const r=run([runner]);assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/\[NOT RUN\].*TEST_DATABASE_URL/);return {exitCode:r.status,output:r.stdout.trim()};
});
test('import without URL preserves caller exit code and continuation',()=>{
 const r=run(['--input-type=module','-e',"process.exitCode=7;await import('./"+runner+"');console.log('CALLER_CONTINUED:'+process.exitCode);"]);
 assert.equal(r.status,7,r.stderr);assert.match(r.stdout,/CALLER_CONTINUED:7/);return {exitCode:r.status,output:r.stdout.trim()};
});
for(const [name,url,flag] of [
 ['refuses non-test database','postgres://fixture:fixture@127.0.0.1:1/production','yes'],
 ['requires explicit disposable flag','postgres://fixture:fixture@127.0.0.1:1/12tester_test_fixture',undefined],
])test(name,()=>{
 const r=run([runner],{TEST_DATABASE_URL:url,...(flag?{DUAL_STORAGE_TEST_DISPOSABLE:flag}:{})});
 assert.equal(r.status,1);assert.match(r.stderr,/Requires disposable database named 12tester_test_/);return {exitCode:r.status,refusedBeforeConnection:true};
});
const report={at:new Date().toISOString(),scope:'Child-process syntax/skip/refusal contracts only; PostgreSQL execution NOT RUN',results};
writeFileSync(new URL('supervisor-runner-contract-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));process.exitCode=results.some(x=>x.status==='FAIL')?1:0;
