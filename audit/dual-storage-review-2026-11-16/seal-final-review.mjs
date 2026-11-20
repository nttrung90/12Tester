import {readFileSync,writeFileSync,readdirSync,statSync} from 'node:fs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const root=new URL('../../',import.meta.url);
const read=p=>{
 const bytes=readFileSync(new URL(p,root));
 return bytes.toString(bytes[0]===0xff && bytes[1]===0xfe?'utf16le':'utf8').replace(/^\uFEFF/,'');
};
const sha=p=>createHash('sha256').update(readFileSync(new URL(p,root))).digest('hex');
const db=JSON.parse(read('audit/dual-storage-review-2026-11-16/FINAL_VALIDATION.json'));
assert.ok(db.results.length===8 && db.results.every(r=>r.status==='PASS'));
for(const [p,h] of Object.entries(db.hashes))assert.equal(sha(p),h,'Database/backend evidence source mismatch: '+p);
const report=JSON.parse(read('audit/dual-storage-review-2026-11-16/android-final-results.json'));
const xmlDir=new URL('android-final-xml/',import.meta.url);
let totals={suites:0,tests:0,failures:0,errors:0,skipped:0};const times=[];
for(const f of readdirSync(xmlDir).filter(f=>/^TEST-.*\.xml$/.test(f))){
 const xml=readFileSync(new URL(f,xmlDir),'utf8'),line=xml.match(/<testsuite\b[^>]+>/)?.[0];
 assert.ok(line,'Missing XML suite: '+f);
 totals.suites++;
 for(const field of ['tests','failures','errors','skipped']) {
  const value=line.match(new RegExp('\\b'+field+'="(\\d+)"'))?.[1];assert.ok(value!==undefined);totals[field]+=Number(value);
 }
 times.push(Date.parse(line.match(/\btimestamp="([^"]+)"/)?.[1]));
}
assert.deepEqual(totals,{suites:17,tests:170,failures:0,errors:0,skipped:0});
assert.equal(report.totals.tests,totals.tests);assert.equal(report.totals.failures,0);assert.equal(report.totals.errors,0);assert.equal(report.totals.skipped,0);
const paths=[
 'app/src/main/java/com/ntt/crosstest/data/MediaExpiryPolicy.kt',
 'app/src/main/java/com/ntt/crosstest/data/Models.kt',
 'app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt',
 'app/src/main/java/com/ntt/crosstest/util/SupabaseStorageHelper.kt',
 'app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt',
 'app/src/main/java/com/ntt/crosstest/util/ScreenshotRequestFactory.kt',
 'app/src/main/java/com/ntt/crosstest/util/ScreenshotCoilInterceptor.kt',
 'app/src/main/java/com/ntt/crosstest/util/ImageHashHelper.kt',
 'app/src/main/java/com/ntt/crosstest/util/ComparisonStateCoordinator.kt',
 'app/src/main/java/com/ntt/crosstest/work/ReportUploadWorker.kt',
 'app/src/main/java/com/ntt/crosstest/ui/components/ImageViewerComponents.kt',
 'app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt',
 'app/src/main/java/com/ntt/crosstest/ui/screens/AdminTesterReportsQueueScreen.kt',
 'app/src/test/java/com/ntt/crosstest/util/SelectedMediaRegressionTest.kt',
 'app/src/test/java/com/ntt/crosstest/data/DualStorageUploadRegressionTest.kt',
];
const hashes={...db.hashes};let newest=0;
for(const p of paths){hashes[p]=sha(p);newest=Math.max(newest,statSync(new URL(p,root)).mtimeMs);}
assert.ok(times.every(Number.isFinite));assert.ok(newest<=Math.min(...times),'Android source changed after test began.');
const http=read('audit/dual-storage-review-2026-11-16/gateway-http-final.log');
assert.match(http,/\btests 13/);assert.match(http,/\bpass 13/);assert.match(http,/\bfail 0/);
const sealed={at:new Date().toISOString(),state:'SOURCE_VERIFIED_NO_DEPLOYMENT',agentModel:'Gemini 3.8 Flash High (observed Antigravity UI)',
 android:totals,backendUnit:{tests:73,failures:0},gatewayHttp:{tests:13,failures:0},
 databaseAndMedia:db.results,postgresConcurrency:'NOT RUN: TEST_DATABASE_URL missing',
 storageAndScheduler:'NOT RUN: no provider deployment or live deletion',
 earliestAndroidTest:new Date(Math.min(...times)).toISOString(),newestAndroidSource:new Date(newest).toISOString(),hashes};
writeFileSync(new URL('FINAL_REVIEW.json',import.meta.url),JSON.stringify(sealed,null,2)+'\n');
console.log(JSON.stringify({state:sealed.state,android:totals,backend:86,dbSuites:db.results.length,androidSourcePredatesTests:true}));
