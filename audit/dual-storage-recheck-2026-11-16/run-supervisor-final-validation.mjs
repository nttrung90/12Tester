// Reproducible local validation. No external credentials/provider endpoints used.
import {spawn} from 'node:child_process';
import {readFileSync,writeFileSync,readdirSync,existsSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const cwd=fileURLToPath(new URL('../../',import.meta.url));
const prefix=process.argv.find(x=>x.startsWith('--prefix='))?.slice(9)||'';
if(!/^[A-Za-z0-9_-]*$/.test(prefix))throw Error('Invalid report prefix');
for(const [source,dest,oldOutput,newOutput] of [
 ['storage-contract-probes.mjs','supervisor-original-contract-after.mjs','storage-contract-results.json','supervisor-original-contract-after-results.json'],
 ['reconcile-ack-probe.mjs','supervisor-original-ack-after.mjs','reconcile-ack-results.json','supervisor-original-ack-after-results.json'],
])writeFileSync(new URL(prefix+dest,import.meta.url),readFileSync(new URL(source,import.meta.url),'utf8').replaceAll(oldOutput,prefix+newOutput));
const allJobs=[
 ['backend',['--test','backend/storage-cleanup/cleanup-reports-storage.test.mjs','backend/storage-cleanup/cleanup-supabase-storage.test.mjs','backend/storage-cleanup/reconcile-org-storage.test.mjs','backend/storage-cleanup/dry-run-token-revocation.test.mjs','backend/media-gateway/dual-storage-adapter.test.mjs']],
 ['independent-followup',['audit/dual-storage-recheck-2026-11-16/supervisor-followup-probes.mjs']],
 ['ack-matrix',['audit/dual-storage-recheck-2026-11-16/supervisor-ack-matrix.mjs']],
 ['original-contract',['audit/dual-storage-recheck-2026-11-16/'+prefix+'supervisor-original-contract-after.mjs']],
 ['original-ack',['audit/dual-storage-recheck-2026-11-16/'+prefix+'supervisor-original-ack-after.mjs']],
 ['media-probes',['audit/dual-storage-review-2026-11-16/run-supervisor-media.mjs']],
 ['sql-hardening',['supabase/tests/run-dual-storage-hardening-test.mjs']],
 ['sql-quota',['supabase/tests/run-dual-storage-quota-test.mjs']],
 ['sql-upgrade',['supabase/tests/run-dual-storage-upgrade-test.mjs']],
 ['sql-upgrade-093',['audit/dual-storage-recheck-2026-11-16/supervisor-upgrade-093.mjs']],
 ['business-comparison',['audit/dual-storage-review-2026-11-16/supervisor-business-regression.mjs']],
 ['mission-rewards',['supabase/tests/run-mission-reward-modes-test.mjs']],
 ['tester-bonus',['supabase/tests/run-campaign-tester-bonus-test.mjs']],
];
const manifestUrl=new URL(prefix+'SUPERVISOR_FINAL_VALIDATION.json',import.meta.url);
const retryFailed=process.argv.includes('--failed');
const previous=retryFailed&&existsSync(manifestUrl)?JSON.parse(readFileSync(manifestUrl,'utf8')):null;
if(retryFailed&&!previous)throw Error('No previous validation manifest to resume');
if(previous)for(const [p,h] of Object.entries(previous.hashes)){
 const current=createHash('sha256').update(readFileSync(new URL('../../'+p,import.meta.url))).digest('hex');
 if(current!==h)throw Error('Source changed; run full validation instead of --failed: '+p);
}
const jobs=previous?allJobs.filter(([name])=>previous.results.some(r=>r.name===name&&r.status==='FAIL')):allJobs;
const results=previous?previous.results.filter(r=>r.status!=='FAIL'):[];
for(let i=0;i<jobs.length;i+=3)await Promise.all(jobs.slice(i,i+3).map(async([name,args])=>{
 const startedAt=new Date().toISOString();let output='';const env={...process.env};delete env.TEST_DATABASE_URL;
 const code=await new Promise((resolve,reject)=>{
  const c=spawn(process.execPath,args,{cwd,env,stdio:['ignore','pipe','pipe']});
  c.stdout.on('data',x=>output+=x);c.stderr.on('data',x=>output+=x);c.on('error',reject);c.on('close',resolve);
 });
 writeFileSync(new URL(prefix+'supervisor-final-'+name+'.log',import.meta.url),output);
 results.push({name,status:code===0?'PASS':'FAIL',exitCode:code,startedAt,endedAt:new Date().toISOString()});console.log(name+': '+(code===0?'PASS':'FAIL'));
}));
const hashes={};
for(const p of [
 ...readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(x=>/^09[1-9]_.*\.sql$/.test(x)).map(x=>'supabase/migrations/'+x),
 'backend/storage-cleanup/reconcile-org-storage.mjs','backend/storage-cleanup/reconcile-org-storage.test.mjs',
 'supabase/tests/run-dual-storage-hardening-test.mjs','supabase/tests/run-dual-storage-concurrency-test.mjs',
 'audit/dual-storage-recheck-2026-11-16/storage-contract-probes.mjs','audit/dual-storage-recheck-2026-11-16/reconcile-ack-probe.mjs',
 'audit/dual-storage-recheck-2026-11-16/supervisor-followup-probes.mjs','audit/dual-storage-recheck-2026-11-16/supervisor-ack-matrix.mjs',
])hashes[p]=createHash('sha256').update(readFileSync(new URL('../../'+p,import.meta.url))).digest('hex');
writeFileSync(manifestUrl,JSON.stringify({at:new Date().toISOString(),scope:'Local Node and isolated PGlite/fake Storage only',...(previous?{resumedFrom:previous.at,reranFailedJobsOnly:true}:{}),results,postgresConcurrency:'NOT RUN: TEST_DATABASE_URL absent/excluded',realStorageAndScheduler:'NOT RUN',hashes},null,2)+'\n');
if(results.some(r=>r.status==='FAIL'))process.exitCode=1;
