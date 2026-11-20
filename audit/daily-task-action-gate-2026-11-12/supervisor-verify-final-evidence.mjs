// Associate the completed runs with the current source. Does not rerun tests.
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const audit=fileURLToPath(new URL('./',import.meta.url)),workspace=resolve(audit,'../..');
const read=name=>JSON.parse(readFileSync(resolve(audit,name),'utf8'));
const hash=path=>createHash('sha256').update(readFileSync(resolve(workspace,path))).digest('hex');
const backend=read('supervisor-runs/r3-backend-2026-11-13T18-35-13-677Z/results.json');
const native=read('supervisor-runs/native-r4-2026-11-13T19-01-31-648Z/results.json');
const before=read('supervisor-runs/native-r4-before-2026-11-13T19-11-58-612Z/results.json');
const android=read('supervisor-android-latest.json'),guard=read('supervisor-r2-current-changes.json');
assert.equal(backend.pass,true);assert.equal(native.pass,true);
assert.deepEqual(backend.before,backend.after);
for(const m of backend.before)assert.equal(hash('supabase/migrations/'+m.name),m.sha256,m.name);
assert.deepEqual(native.sourceBefore,native.sourceAfter);
for(const p of native.sourceBefore)assert.equal(hash(p.path),p.sha256,p.path);
assert.equal(native.nativeReview.cases.length,5);assert.ok(native.nativeReview.cases.every(c=>c.pass));
assert.equal(native.bonus.pass,true);assert.equal(native.bonus.scenarioPassLines.length,7);
assert.equal(native.cleanup.gracefulStop,true);assert.equal(native.cleanup.exit.code,0);
assert.equal(before.pass,false);assert.equal(before.expectedCounterexample,true);
assert.deepEqual(before.sourceBefore,before.sourceAfter);
for(const p of before.sourceBefore)assert.equal(hash(p.path),p.sha256,p.path);
assert.equal(before.nativeReview.cases[0].pass,true);assert.equal(before.nativeReview.cases[4].pass,true);
assert.equal(before.nativeReview.cases[1].pass,false);assert.equal(before.nativeReview.cases[2].pass,false);
assert.equal(before.cleanup.gracefulStop,true);assert.equal(before.cleanup.exit.code,0);
assert.deepEqual(android.totals,{suites:95,tests:750,failures:0,errors:0,skipped:0});
for(const s of android.sources)assert.equal(hash(s.path),s.sha256,s.path);
assert.equal(guard.packageFiles,7);
for(const key of ['changedPackages','newPackages','touchedPackages','protectedChanged'])assert.deepEqual(guard[key],[],key);
const frozen=read('supervisor-runs/r3-review-tests/manifest.json');
const preservedReview=frozen.filter(p=>!p.path.endsWith('supervisor-receipt-attribution-test.mjs'));
for(const p of preservedReview)assert.equal(hash(p.path),p.sha256,p.path);
assert.equal(hash('audit/daily-task-action-gate-2026-11-12/supervisor-receipt-attribution-test.mjs'),
  hash('audit/daily-task-action-gate-2026-11-12/supervisor-runs/r3-review-tests/supervisor-receipt-attribution-test-v3.mjs'));
assert.equal(hash('audit/daily-task-action-gate-2026-11-12/supervisor-native-r4-probes.mjs'),
  hash('audit/daily-task-action-gate-2026-11-12/supervisor-runs/r4-native-review-tests/supervisor-native-r4-probes.v3-five-cases.mjs'));
assert.equal(hash('audit/daily-task-action-gate-2026-11-12/supervisor-native-r4-runner.mjs'),
  hash('audit/daily-task-action-gate-2026-11-12/supervisor-runs/r4-native-review-tests/supervisor-native-r4-runner.original.mjs'));
const result={at:new Date().toISOString(),pass:true,note:'Integrity verification of completed runs; no new execution of Android/backend tests.',
  backend:{path:'supervisor-runs/r3-backend-2026-11-13T18-35-13-677Z/results.json',pass:true,scripts:11,concurrency:false},
  android:{path:android.output,totals:android.totals,fullRunObserved:'Antigravity testDebugUnitTest: BUILD SUCCESSFUL in 51s; recorded in SUPERVISOR_PROGRESS_R4.md',sourceMatches:true},
  native:{path:'supervisor-runs/native-r4-2026-11-13T19-01-31-648Z/results.json',reviewCases:5,bonusConcurrencyScenarios:7,
    pass:true,dataDirectory:native.server.directory,ownedProcessPid:native.server.ownedProcessPid,cleanup:native.cleanup},
  beforeAfter:{beforePath:'supervisor-runs/native-r4-before-2026-11-13T19-11-58-612Z/results.json',
    oldProductPass:false,expectedCounterexample:true,oldFailingCases:[before.nativeReview.cases[1].name,before.nativeReview.cases[2].name],
    dependentWorkerCase:'Not separately assessed on old source because its accepted-removal prerequisite failed',
    currentProductCasesPass:true,sourceStable:true,cleanup:before.cleanup},
  migrationsCurrentMatchBothRuns:true,reviewAssertionsPreserved:true,
  packages:{count:7,changed:[],created:[],touched:[]},protectedChanged:[],
  limitations:['Local verification only; migrations 089/090 not deployed to production.',
    'Native PASS applies to the recorded scenarios, not a proof of every possible interleaving or a load test.',
    'No new APK/AAB was built. No on-device end-to-end run was performed in this recheck.']};
writeFileSync(resolve(audit,'SUPERVISOR_FINAL_EVIDENCE_R4.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify(result,null,2));
