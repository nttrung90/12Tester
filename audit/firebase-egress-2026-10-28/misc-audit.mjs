import {writeFileSync} from 'node:fs';
import {cleanupStorage,ALLOWED_BUCKET} from '../../backend/storage-cleanup/cleanup-reports-storage.mjs';
import {calculateMonthlyEgressAndCost,OFFICIAL_PRICING_2026_10} from '../../tools/tco-calculator/calculate-tco.mjs';
import {reconcileCohortTelemetry} from '../../tools/telemetry-reconciliation/reconcile-telemetry.mjs';
const now=new Date('2026-10-28T12:00:00Z'),day=86400000,logger={info(){},warn(){},error(){}};
function file(name,age,metadata){return {name,metadata:{timeCreated:new Date(now-day*age).toISOString(),size:'50',generation:'1',metadata},deleted:false,async delete(){this.deleted=true;return [{}]}};}
async function cleanup(files){return cleanupStorage({storage:{bucket(){return {async getFiles(){return [files]}}}},bucketName:ALLOWED_BUCKET,now,dryRun:false,logger});}
const full=file('reports/c/u/full.jpg',30),thumb=file('reports/c/u/thumb_full.jpg',28);await cleanup([full,thumb]);
const forged=file('reports/c/u/old_full.jpg',35,{parent_time_created:new Date(+now+day).toISOString()});const forgedStats=await cleanup([forged]);
const p={...OFFICIAL_PRICING_2026_10};
const free0=calculateMonthlyEgressAndCost({pricing:{...p,gcsAlwaysFreeEgressGb:0}});
const free10000=calculateMonthlyEgressAndCost({pricing:{...p,gcsAlwaysFreeEgressGb:10000}});
const mismatched=reconcileCohortTelemetry({clientLogs:[{bodyBytesRead:200}],cloudStorageMetrics:{sentBytesCount:100}});
const noCloud=reconcileCohortTelemetry({clientLogs:[{bodyBytesRead:200}],cloudStorageMetrics:{sentBytesCount:0}});
const out={
 lateThumbnailWithoutParentMetadata:{fullDeleted:full.deleted,thumbnailDeleted:thumb.deleted},
 clientMetadataHidesOldFull:{deleted:forged.deleted,slaViolations:forgedStats.sla_violations_count,candidates:forgedStats.candidates_found},
 tcoFreeAllowanceUnused:{free0Cost:free0.baseline.monthlyCostUsd,free10000Cost:free10000.baseline.monthlyCostUsd,equal:free0.baseline.monthlyCostUsd===free10000.baseline.monthlyCostUsd},
 reconciliationClientExceedsCloud:mismatched.summary,
 reconciliationNoCloud:noCloud.summary,
};
writeFileSync(new URL('./misc-results.json',import.meta.url),JSON.stringify(out,null,2));console.log(JSON.stringify(out,null,2));
