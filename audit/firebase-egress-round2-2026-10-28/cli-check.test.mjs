import assert from 'node:assert/strict';
import test from 'node:test';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

test('reconciliation CLI accepts complete client and cloud metadata and yields a healthy match',()=>{
 const window={windowStartUtc:'2026-10-27T00:00:00Z',windowEndUtc:'2026-10-28T00:00:00Z',coveragePercent:100};
 const clientFile=new URL('./cli-client-fixture.json',import.meta.url);
 const cloudFile=new URL('./cli-cloud-fixture.json',import.meta.url);
 const outputFile=new URL('./cli-result.json',import.meta.url);
 writeFileSync(clientFile,JSON.stringify([{...window,schemaVersion:'1.0.0',clientVersion:'fixture',buildNumber:1,cohort:'audit',samplingRate:1,requestCount:1,bodyBytesRead:100,memoryHits:0,diskHits:0,ioFailures:0,cancellations:0}]));
 writeFileSync(cloudFile,JSON.stringify({...window,sentBytesCount:100,requestCount:1}));
 execFileSync(process.execPath,[fileURLToPath(new URL('../../tools/telemetry-reconciliation/reconcile-telemetry.mjs',import.meta.url)),
  '--client-file',fileURLToPath(clientFile),'--cloud-file',fileURLToPath(cloudFile),'--out',fileURLToPath(outputFile)],{windowsHide:true,stdio:'pipe'});
 const result=JSON.parse(readFileSync(outputFile,'utf8'));
 assert.equal(result.summary.status,'OK','The CLI must forward cloud window/coverage metadata rather than drop it');
 assert.equal(result.summary.cloudReportedSentBytes,100);
 assert.equal(result.summary.clientReportedBodyBytes,100);
});
