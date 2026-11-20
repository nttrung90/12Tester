import {writeFileSync} from 'node:fs';
import {reconcileOrganizationStorage} from '../../backend/storage-cleanup/reconcile-org-storage.mjs';
const ref='aaaaaaaaaaaaaaaaaaaa';
const acknowledgement={success:true,is_org_metric_valid:false,dispatch_mode:'google_overflow'};
const summary=await reconcileOrganizationStorage({
 supabaseClient:{rpc:async()=>({data:acknowledgement,error:null})},
 totalCapacityBytes:'1000000',managementToken:'unit-fixture-not-a-secret',organizationSlug:'test-fixture',
 inventoryFn:async()=>[ref],
 fetchProjectFn:async()=>({bytes:'100',observed_at:new Date().toISOString(),objects:[]}),
 logger:{info(){},warn(){},error(){}},dryRun:false,
});
const report={
 at:new Date().toISOString(),scope:'Injected RPC; no network or database writes',
 status:summary.is_metric_valid===false?'PASS':'FAIL',
 expected:{is_metric_valid:false,cliExitCode:1},
 observed:{is_metric_valid:summary.is_metric_valid,cliExitCode:summary.is_metric_valid?0:1,dispatch_mode_result:summary.dispatch_mode_result,fail_safe_triggered:summary.fail_safe_triggered},
 databaseAcknowledgement:acknowledgement,
};
writeFileSync(new URL('supervisor-original-ack-after-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));process.exitCode=report.status==='FAIL'?1:0;
