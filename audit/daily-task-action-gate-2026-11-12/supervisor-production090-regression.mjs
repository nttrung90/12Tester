// Run the existing 089 before/after regression, then exercise its after assertions
// with the actual 090 migration additionally installed. No assertions are removed.
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const original=readFileSync(new URL('../../supabase/tests/run-production-lifecycle-test.mjs',import.meta.url),'utf8');
const gate=readFileSync(new URL('../../supabase/migrations/090_daily_task_action_gate.sql',import.meta.url),'utf8');
const marker='await db.exec(read(migration)); // Migration is safe to rerun.';
if(original.split(marker).length!==2)throw new Error('Original regression integration marker changed');
const transformed=original
  .replace("from '../tools/baseline-lib.mjs'","from '../../supabase/tools/baseline-lib.mjs'")
  .replace("new URL('../migrations/', import.meta.url)","new URL('../../supabase/migrations/', import.meta.url)")
  .replace(marker,marker+"\n  await db.exec(read('supabase/migrations/090_daily_task_action_gate.sql'));\n  await db.exec(read('supabase/migrations/090_daily_task_action_gate.sql'));\n");
const generated=new URL('./supervisor-production090-generated.mjs',import.meta.url);
writeFileSync(generated,transformed);
const evidence={at:new Date().toISOString(),originalSha256:createHash('sha256').update(original).digest('hex'),migration090Sha256:createHash('sha256').update(gate).digest('hex'),changes:['relative import/file URLs only','apply actual 090 twice after installing 089 and before after-fix assertions'],pass:false};
try{await import(generated.href+'?run='+Date.now());evidence.pass=true;}
finally{writeFileSync(new URL('./supervisor-production090-results.json',import.meta.url),JSON.stringify(evidence,null,2));}
