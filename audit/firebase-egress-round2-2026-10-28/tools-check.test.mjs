import assert from 'node:assert/strict';
import test from 'node:test';
import {runTokenRevocationPreflight} from '../../backend/storage-cleanup/dry-run-token-revocation.mjs';
import {reconcileCohortTelemetry} from '../../tools/telemetry-reconciliation/reconcile-telemetry.mjs';
import {calculateMonthlyEgressAndCost,OFFICIAL_PRICING_2026_10} from '../../tools/tco-calculator/calculate-tco.mjs';
const logger={info(){},warn(){},error(){}};

test('preflight cannot be safe without an inventory',async()=>{
 const actual=await runTokenRevocationPreflight({currentNewVersionAdoptionPercent:100,gatewayErrorRatePercent:0,logger});
 assert.equal(actual.safeToRevoke,false,'Synthetic default counts must never authorize revocation readiness');
});
test('NaN cloud metrics cannot become a healthy empty cohort',()=>{
 const actual=reconcileCohortTelemetry({clientLogs:[],cloudStorageMetrics:{sentBytesCount:NaN,requestCount:0}});
 assert.equal(actual.summary.status,'INVALID');
});
test('negative client bytes are invalid',()=>{
 const actual=reconcileCohortTelemetry({clientLogs:[{bodyBytesRead:-1,requestCount:0}],cloudStorageMetrics:{sentBytesCount:100,requestCount:0}});
 assert.equal(actual.summary.status,'INVALID');
});
test('unconfigured bucket has no implicit Always Free eligibility',()=>{
 assert.equal(calculateMonthlyEgressAndCost().baseline.monthlyCostUsd,calculateMonthlyEgressAndCost({applyAlwaysFree:false}).baseline.monthlyCostUsd);
});
test('negative traffic must be rejected',()=>{
 assert.throws(()=>calculateMonthlyEgressAndCost({reportsPerDay:-1}));
});
test('cache hit ratio over 100 percent must be rejected',()=>{
 assert.throws(()=>calculateMonthlyEgressAndCost({cdnHitRatio:1.1}));
});
test('unused eligible free allowance applies to marginal origin icon cost',()=>{
 const result=calculateMonthlyEgressAndCost({reportsPerDay:0,iconViewsPerDay:10000,applyAlwaysFree:true,pricing:{...OFFICIAL_PRICING_2026_10,gcsAlwaysFreeEgressGb:10000}});
 const operationCost=Math.round((10000*30/10000)*OFFICIAL_PRICING_2026_10.gcsClassBPer10k*100)/100;
 assert.equal(result.e05CdnAnalysis.originIconCostUsd,operationCost,'An unused allowance cannot be assumed exhausted by reports');
});
test('missing measurement windows and coverage cannot be declared healthy',()=>{
 const result=reconcileCohortTelemetry({clientLogs:[{bodyBytesRead:100,requestCount:1}],cloudStorageMetrics:{sentBytesCount:100,requestCount:1}});
 assert.ok(['UNKNOWN','INVALID'].includes(result.summary.status),'Incomplete coverage/window evidence must not return OK');
});
test('different client and bucket measurement windows are invalid',()=>{
 const result=reconcileCohortTelemetry({clientLogs:[{clientVersion:'fixture',cohort:'fixture',windowStartUtc:'2026-10-26T00:00:00Z',windowEndUtc:'2026-10-27T00:00:00Z',samplingRate:1,coveragePercent:100,bodyBytesRead:100,requestCount:1}],cloudStorageMetrics:{sentBytesCount:100,requestCount:1,windowStartUtc:'2026-10-27T00:00:00Z',windowEndUtc:'2026-10-28T00:00:00Z',coveragePercent:100}});
 assert.equal(result.summary.status,'INVALID');
});
test('manifest entries require object identity and generation',async()=>{
 const result=await runTokenRevocationPreflight({currentNewVersionAdoptionPercent:100,gatewayErrorRatePercent:0,manifestData:[{hasDownloadToken:true,isExpired:true}],logger});
 assert.equal(result.safeToRevoke,false,'An inventory entry without bucket, object name or generation is not evidence');
});
test('stale inventory cannot authorize revocation readiness',async()=>{
 const result=await runTokenRevocationPreflight({currentNewVersionAdoptionPercent:100,gatewayErrorRatePercent:0,manifestData:{generatedAt:'2020-01-01T00:00:00Z',bucket:'cross-testers.firebasestorage.app',items:[{bucket:'cross-testers.firebasestorage.app',name:'reports/campaign/tester/full.jpg',generation:'123',hasDownloadToken:true,isExpired:true}]},logger});
 assert.equal(result.safeToRevoke,false,'A years-old manifest cannot justify a current safety gate');
});
test('inventory without a capture timestamp is unknown rather than safe',async()=>{
 const result=await runTokenRevocationPreflight({currentNewVersionAdoptionPercent:100,gatewayErrorRatePercent:0,manifestData:{items:[{bucket:'cross-testers.firebasestorage.app',name:'reports/campaign/tester/full.jpg',generation:'123',hasDownloadToken:true,isExpired:true}]},logger});
 assert.equal(result.safeToRevoke,false,'Missing generatedAt must not bypass freshness validation');
});
test('non-numeric object generation is not a valid inventory identity',async()=>{
 const result=await runTokenRevocationPreflight({currentNewVersionAdoptionPercent:100,gatewayErrorRatePercent:0,manifestData:{generatedAt:new Date().toISOString(),items:[{bucket:'cross-testers.firebasestorage.app',name:'reports/campaign/tester/full.jpg',generation:'not-a-generation',hasDownloadToken:true,isExpired:true}]},logger});
 assert.equal(result.safeToRevoke,false);
});
test('zero measurement coverage cannot be declared healthy',()=>{
 const window={windowStartUtc:'2026-10-27T00:00:00Z',windowEndUtc:'2026-10-28T00:00:00Z',coveragePercent:0};
 const result=reconcileCohortTelemetry({clientLogs:[{...window,bodyBytesRead:100,requestCount:1}],cloudStorageMetrics:{...window,sentBytesCount:100,requestCount:1}});
 assert.ok(['UNKNOWN','INVALID'].includes(result.summary.status));
});
test('malformed measurement timestamps are invalid even when strings match',()=>{
 const window={windowStartUtc:'not-a-date',windowEndUtc:'not-a-date',coveragePercent:100};
 const result=reconcileCohortTelemetry({clientLogs:[{...window,bodyBytesRead:100,requestCount:1}],cloudStorageMetrics:{...window,sentBytesCount:100,requestCount:1}});
 assert.equal(result.summary.status,'INVALID');
});
