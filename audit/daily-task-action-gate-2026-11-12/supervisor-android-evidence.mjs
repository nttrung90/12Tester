// Snapshot actual JUnit XML so subsequent filtered Gradle runs cannot replace evidence.
// This reads existing results only; it does not run/build/package the application.
import {readdirSync,readFileSync,writeFileSync,mkdirSync,statSync} from 'node:fs';
import {createHash} from 'node:crypto';
const dir=new URL('../../app/build/test-results/testDebugUnitTest/',import.meta.url);
const stamp=new Date().toISOString().replace(/[:.]/g,'-');
const out=new URL('./supervisor-runs/android-'+stamp+'/',import.meta.url);
mkdirSync(out,{recursive:true});
const suites=[];
for(const name of readdirSync(dir).filter(n=>/^TEST-.*\.xml$/.test(n)).sort()){
  const bytes=readFileSync(new URL(name,dir));
  const xml=bytes.toString('utf8'),head=xml.match(/<testsuite\b[^>]*>/)?.[0];
  if(!head)throw new Error('Missing testsuite: '+name);
  const attrs=Object.fromEntries([...head.matchAll(/([\w.-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
  const failures=[...xml.matchAll(/<testcase\b([^>]*)>([\s\S]*?)<\/testcase>/g)].filter(m=>/<failure\b|<error\b/.test(m[2])).map(m=>({name:m[1].match(/\bname="([^"]*)"/)?.[1],message:m[2].match(/<(?:failure|error)\b[^>]*message="([^"]*)"/)?.[1]}));
  suites.push({file:name,name:attrs.name,tests:Number(attrs.tests),failures:Number(attrs.failures??0),errors:Number(attrs.errors??0),skipped:Number(attrs.skipped??0),timestamp:attrs.timestamp,mtime:statSync(new URL(name,dir)).mtime.toISOString(),sha256:createHash('sha256').update(bytes).digest('hex'),failureCases:failures});
  writeFileSync(new URL(name,out),bytes);
}
const totals=suites.reduce((a,s)=>({suites:a.suites+1,tests:a.tests+s.tests,failures:a.failures+s.failures,errors:a.errors+s.errors,skipped:a.skipped+s.skipped}),{suites:0,tests:0,failures:0,errors:0,skipped:0});
const required=['SupervisorOwnerActionGateReviewTest','SupervisorDailyTaskStatusCacheReviewTest','OwnerAppDetailActionGateBehavioralTest'];
const requiredEvidence=required.map(name=>({name,suite:suites.find(s=>s.name.endsWith('.'+name))??null}));
const sourcePaths=['app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt','app/src/main/java/com/ntt/crosstest/data/DailyTaskActionGate.kt','app/src/main/java/com/ntt/crosstest/data/Models.kt','app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt','app/build.gradle.kts'];
const sources=sourcePaths.map(path=>{const url=new URL('../../'+path,import.meta.url);return {path,sha256:createHash('sha256').update(readFileSync(url)).digest('hex'),mtime:statSync(url).mtime.toISOString()};});
const evidence={at:new Date().toISOString(),note:'Existing JUnit files only. Full-suite claim additionally requires a matching completed Gradle log and current source; old/filtered files alone are insufficient.',totals,requiredEvidence,sources,suites};
writeFileSync(new URL('results.json',out),JSON.stringify(evidence,null,2));
writeFileSync(new URL('./supervisor-android-latest.json',import.meta.url),JSON.stringify({output:out.href,...evidence},null,2));
console.log(JSON.stringify({totals,requiredEvidence:requiredEvidence.map(r=>({name:r.name,tests:r.suite?.tests,failures:r.suite?.failures,errors:r.suite?.errors})),sources,output:out.href},null,2));
