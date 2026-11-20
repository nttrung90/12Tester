// Local verification only. Never calls provider APIs or uses production credentials.
import {spawn} from 'node:child_process';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const cwd=fileURLToPath(new URL('../../',import.meta.url));
const jobs=[
 ['backend',['--test','backend/storage-cleanup/cleanup-reports-storage.test.mjs','backend/storage-cleanup/cleanup-supabase-storage.test.mjs','backend/storage-cleanup/reconcile-org-storage.test.mjs','backend/storage-cleanup/dry-run-token-revocation.test.mjs','backend/media-gateway/dual-storage-adapter.test.mjs']],
 ['media-probes',['audit/dual-storage-review-2026-11-16/run-supervisor-media.mjs']],
 ['sql-hardening',['supabase/tests/run-dual-storage-hardening-test.mjs']],
 ['sql-quota',['supabase/tests/run-dual-storage-quota-test.mjs']],
 ['sql-upgrade',['supabase/tests/run-dual-storage-upgrade-test.mjs']],
 ['business-comparison',['audit/dual-storage-review-2026-11-16/supervisor-business-regression.mjs']],
 ['mission-rewards',['supabase/tests/run-mission-reward-modes-test.mjs']],
 ['tester-bonus',['supabase/tests/run-campaign-tester-bonus-test.mjs']],
];
const results=[];
for(let i=0;i<jobs.length;i+=3) await Promise.all(jobs.slice(i,i+3).map(async([name,args])=>{
 let output='';
 // Remove TEST_DATABASE_URL: production is never a verification target of this runner.
 const env={...process.env};delete env.TEST_DATABASE_URL;
 const code=await new Promise((resolve,reject)=>{
  const c=spawn(process.execPath,args,{cwd,env,stdio:['ignore','pipe','pipe']});
  c.stdout.on('data',x=>output+=x);c.stderr.on('data',x=>output+=x);
  c.on('error',reject);c.on('close',resolve);
 });
 writeFileSync(new URL('final-'+name+'.log',import.meta.url),output);
 results.push({name,status:code===0?'PASS':'FAIL',exitCode:code});
 console.log(name+': '+(code===0?'PASS':'FAIL'));
}));
const hashes={};
for(const p of ['supabase/migrations/091_dual_storage_report_screenshots.sql','supabase/migrations/092_dual_storage_hardening.sql','audit/dual-storage-review-2026-11-16/review-probes.mjs'])
 hashes[p]=createHash('sha256').update(readFileSync(new URL('../../'+p,import.meta.url))).digest('hex');
writeFileSync(new URL('FINAL_VALIDATION.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),scope:'Local source and isolated PGlite/fake Storage only',results,postgresConcurrency:'NOT RUN: TEST_DATABASE_URL absent/excluded',hashes},null,2)+'\n');
if(results.some(r=>r.status==='FAIL'))process.exitCode=1;
