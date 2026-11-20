// EXPLAIN only: validates runner's static SQL against the migrated PGlite schema.
// Does not run the runner, execute its fixture DML, test locks, or connect to PG.
import assert from 'node:assert/strict';
import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {createDb} from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb(),results=[];
const source=readFileSync(new URL('../../supabase/tests/run-dual-storage-concurrency-test.mjs',import.meta.url),'utf8');
// Match only closed JS string literals directly passed to .query; excludes
// concatenated/dynamically generated SQL, which this check cannot validate.
const literals=/\.query\(\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')\s*(?=[,)])/g;
const seen=new Set();
try{
 for(const f of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d+_.*\.sql$/.test(f)).sort())await db.exec(readFileSync(new URL('../../supabase/migrations/'+f,import.meta.url),'utf8'));
 for(const match of source.matchAll(literals)){
  // The matched input is a quoted string literal only, with no interpolation.
  const sql=Function('"use strict";return ('+match[1]+');')();
  if(!/^(select|insert|update|delete)\b/i.test(sql.trim())||seen.has(sql))continue;
  seen.add(sql);
  const parameters=Math.max(0,...[...sql.matchAll(/\$(\d+)/g)].map(m=>Number(m[1])));
  try{await db.query('EXPLAIN '+sql,Array(parameters).fill(null));results.push({sql,status:'PASS'});}
  catch(e){results.push({sql,status:'FAIL',code:e.code,error:e.message});}
 }
 assert.ok(results.length>20,'Too few static SQL statements found for schema check');
}finally{await db.close();}
const report={at:new Date().toISOString(),scope:'PGlite EXPLAIN only, static SQL literals; dynamic SQL, actual PG, concurrency and provider execution NOT RUN',results};
writeFileSync(new URL('supervisor-runner-sql-schema-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({at:report.at,checked:results.length,failed:results.filter(x=>x.status==='FAIL')},null,2));process.exitCode=results.some(x=>x.status==='FAIL')?1:0;
