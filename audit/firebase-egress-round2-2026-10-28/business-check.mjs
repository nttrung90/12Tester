import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
await import('./business-audit.mjs');
const rows=JSON.parse(readFileSync(new URL('./db-results.json',import.meta.url),'utf8'));
const checks=[];
for(const phase of ['before_045','after_045_046']){
 for(const [name,expected] of [
  ['external_screenshot_url',{error:'INVALID_SCREENSHOT_URL'}],
  ['confirmed_checkin_additional_reward',{creditDelta:0,provisionalGranted:false}],
  ['feedback_written_on_submission',{feedbackRows:1}],
  ['approved_rating_immutable',{rating:5}],
  ['rejected_same_hash_new_url',{error:'SAME_SCREENSHOT_REJECTED'}],
 ]){
  const row=rows.find(x=>x.phase===phase&&x.name===name);
  assert.ok(row,`Missing business scenario ${phase}/${name}`);
  for(const [key,value] of Object.entries(expected))assert.deepEqual(row[key],value,`${phase}/${name}/${key}`);
  checks.push({phase,name,pass:true});
 }
}
writeFileSync(new URL('./business-check-results.json',import.meta.url),JSON.stringify(checks,null,2));
console.log('Business regressions: 5 scenarios verified before and after migration, 10/10 checks passed.');
