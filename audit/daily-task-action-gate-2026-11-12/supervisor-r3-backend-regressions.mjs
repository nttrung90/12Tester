// Supervisor verification only. Each test uses a fresh local PGlite database.
// Run after the candidate source is stable. Never a native concurrency claim.
import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url));
const audit=fileURLToPath(new URL('./',import.meta.url));
const stamp=new Date().toISOString().replace(/[:.]/g,'-');
const output=new URL('./supervisor-runs/r3-backend-'+stamp+'/',import.meta.url);
mkdirSync(output,{recursive:true});
const hash=b=>createHash('sha256').update(b).digest('hex');
const migrations=()=>readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort().map(name=>({name,sha256:hash(readFileSync(new URL('../../supabase/migrations/'+name,import.meta.url)))}));
const before=migrations(),results=[];
const jobs=[
  ['acceptance90','audit/daily-task-action-gate-2026-11-12/supervisor-action-gate-acceptance.mjs',['--bonus','--payloads','--review','--roles','--r2']],
  ['receipt-table','audit/daily-task-action-gate-2026-11-12/supervisor-receipt-authorization-test.mjs',[]],
  ['receipt-helper','audit/daily-task-action-gate-2026-11-12/supervisor-receipt-helper-context-test.mjs',[]],
  ['receipt-attribution','audit/daily-task-action-gate-2026-11-12/supervisor-receipt-attribution-test.mjs',[]],
  ['admin-contract','audit/daily-task-action-gate-2026-11-12/supervisor-admin-resume090-test.mjs',[]],
  ['production090','audit/daily-task-action-gate-2026-11-12/supervisor-production090-regression.mjs',[]],
  ['auto-pause090','audit/daily-task-action-gate-2026-11-12/supervisor-auto-pause090-regression.mjs',[]],
  ['owner-removal','supabase/tests/run-owner-tester-bonus-removal-test.mjs',[]],
  ['immediate-quota','supabase/tests/run-immediate-daily-quota-acceptance-test.mjs',[]],
  ['carryover','supabase/tests/run-carryover-fix-acceptance-test.mjs',[]],
  ['egress','supabase/tests/run-egress-sync-v2-test.mjs',[]]
];
for(const [name,path,args] of jobs){
  const sourceHash=hash(readFileSync(new URL('../../'+path,import.meta.url)));
  const run=spawnSync(process.execPath,[path,...args],{cwd:root,encoding:'utf8',windowsHide:true,timeout:180000,maxBuffer:12*1024*1024});
  writeFileSync(new URL(name+'.log',output),(run.stdout??'')+(run.stderr??'')+(run.error?'\n'+run.error.message:''));
  const stable=JSON.stringify(before)===JSON.stringify(migrations());
  const result={name,path,args,sourceHash,status:run.status,signal:run.signal,error:run.error?.message,stable,pass:run.status===0&&stable};
  results.push(result);console.log(JSON.stringify(result));
  if(!stable){console.log('Candidate migrations changed during verification; stop to avoid stale evidence.');break;}
}
const evidence={at:new Date().toISOString(),singleConnection:true,concurrency:false,before,after:migrations(),results,pass:results.length===jobs.length&&results.every(r=>r.pass)};
writeFileSync(new URL('results.json',output),JSON.stringify(evidence,null,2));
writeFileSync(audit+'supervisor-r3-backend-latest.json',JSON.stringify({output:fileURLToPath(output),...evidence},null,2));
console.log((evidence.pass?'PASS':'FAIL')+' '+results.filter(r=>r.pass).length+'/'+jobs.length+' backend verification scripts; logs '+fileURLToPath(output));
if(!evidence.pass)process.exitCode=1;
