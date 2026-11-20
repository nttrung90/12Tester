// Existing threshold 1..7 regression, keeping all state/permission assertions.
// 090 intentionally reports outstanding carryover before generic unmet-today.
// Determine the exact expected error from canonical state; do not accept arbitrary errors.
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const original=readFileSync(new URL('../../supabase/tests/run-owner-auto-pause-hardening-test.mjs',import.meta.url),'utf8');
const gate=readFileSync(new URL('../../supabase/migrations/090_daily_task_action_gate.sql',import.meta.url),'utf8');
const marker='const scalar = async sql => (await db.query(sql)).rows[0];';
const helper=`
  async function expectedTaskBlock() {
    const s=(await scalar(\`select public.get_daily_task_status('\${ownerId}',\${today}) s\`)).s;
    assert.equal(s.history_ready,true,'blocking fixture must have known history');
    assert.equal(s.carryover_ready,true,'blocking fixture must have known carryover');
    if(s.carryover_remaining_tests>0)return new RegExp('OWNER_CARRYOVER_INCOMPLETE:'+s.carryover_remaining_tests+'(?:\\\\s|$)');
    assert.equal(s.met,false,'fixture must still be unmet');
    return /OWNER_INACTIVE/;
  }
`;
if(original.split(marker).length!==2)throw new Error('Regression helper integration marker changed');
const changedCodes=original.split('/OWNER_INACTIVE/').length-1;
if(changedCodes!==4)throw new Error('Expected exactly four legacy generic-error assertions');
const transformed=original
  .replace("from '../tools/baseline-lib.mjs'","from '../../supabase/tools/baseline-lib.mjs'")
  .replace("new URL('../migrations/', import.meta.url)","new URL('../../supabase/migrations/', import.meta.url)")
  .replaceAll('/OWNER_INACTIVE/','await expectedTaskBlock()')
  .replace(marker,marker+helper);
const generated=new URL('./supervisor-auto-pause090-generated.mjs',import.meta.url);
writeFileSync(generated,transformed);
const evidence={at:new Date().toISOString(),originalSha256:createHash('sha256').update(original).digest('hex'),migration090Sha256:createHash('sha256').update(gate).digest('hex'),changedCodes,changes:['relative file/import URLs','four legacy generic error checks use exact canonical debt/unmet error; all other assertions preserved'],pass:false};
try{await import(generated.href+'?run='+Date.now());evidence.pass=true;}
finally{writeFileSync(new URL('./supervisor-auto-pause090-results.json',import.meta.url),JSON.stringify(evidence,null,2));}
