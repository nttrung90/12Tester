// No network, secrets, or database writes: injected acknowledgement contract.
import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {reconcileOrganizationStorage} from '../../backend/storage-cleanup/reconcile-org-storage.mjs';
const ref='a'.repeat(20),results=[];
for (const [name,valid,collectorValid=true,dryRun=false] of [
 ['valid ACK',true],['SQL rejects metric',false],['missing ACK flag',undefined],
 ['null ACK flag',null],['string false ACK','false'],['string true ACK','true'],
 ['numeric zero ACK',0],['numeric one ACK',1],['array ACK flag',[]],
 ['object ACK flag',{}],['collector invalid cannot become valid',true,false],
 ['dry run remains read only',undefined,true,true],
]) {
 const expected=dryRun || (valid===true && collectorValid);let calls=0;
 try {
  let summary,thrown;
  try {summary=await reconcileOrganizationStorage({
   supabaseClient:{rpc:async()=>{calls++;return {data:{success:true,is_org_metric_valid:valid,dispatch_mode:valid===true?'supabase_preferred':'google_overflow'},error:null};}},
   totalCapacityBytes:'1000000',managementToken:'test-placeholder',organizationSlug:'fixture',
   inventoryFn:async()=>collectorValid?[ref]:[],
   fetchProjectFn:async()=>({bytes:'100',objects:[],observed_at:new Date().toISOString()}),
   logger:{info(){},warn(){},error(){}},dryRun,
  });} catch(e){thrown=e.message;}
  if(expected){assert.equal(thrown,undefined);assert.equal(summary.is_metric_valid,true);}
  else if(!thrown){assert.equal(summary.is_metric_valid,false);assert.equal(summary.fail_safe_triggered,true);}
  if(dryRun)assert.equal(calls,0);
  results.push({name,status:'PASS',observed:thrown?{error:thrown}:{valid:summary.is_metric_valid,failSafe:summary.fail_safe_triggered,calls}});
 }catch(e){results.push({name,status:'FAIL',error:e.message});}
}
const report={at:new Date().toISOString(),scope:'Injected RPC only, no network',results};
writeFileSync(new URL('supervisor-ack-matrix-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));process.exitCode=results.some(x=>x.status==='FAIL')?1:0;
