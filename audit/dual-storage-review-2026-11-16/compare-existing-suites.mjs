import {readFileSync,writeFileSync} from 'node:fs';
import {spawn} from 'node:child_process';
const results=[];
for(const name of ['mission-reward-modes','campaign-tester-bonus']){
 let source=readFileSync(new URL('../../supabase/tests/run-'+name+'-test.mjs',import.meta.url),'utf8');
 source=source.replace("'../tools/baseline-lib.mjs'","'../../supabase/tools/baseline-lib.mjs'");
 source=source.replace('.sort();',".filter(n=>Number(n.slice(0,3))<=Number(process.env.SUPERVISOR_THROUGH)).sort();");
 const file=new URL('baseline-'+name+'-generated.mjs',import.meta.url);
 writeFileSync(file,source);
 for(const max of [90,91]){
  let output='';
  const code=await new Promise(resolve=>{
   const child=spawn(process.execPath,[file.pathname.replace(/^\/([A-Za-z]:)/,'$1')],{env:{...process.env,SUPERVISOR_THROUGH:String(max)},stdio:['ignore','pipe','pipe']});
   child.stdout.on('data',x=>output+=x);child.stderr.on('data',x=>output+=x);child.on('close',resolve);
  });
  writeFileSync(new URL('baseline-'+name+'-'+max+'.log',import.meta.url),output);
  results.push({suite:name,through:max,exitCode:code,tail:output.slice(-1100)});
 }
}
writeFileSync(new URL('baseline-existing-suites.json',import.meta.url),JSON.stringify(results,null,2)+'\n');
console.log(JSON.stringify(results,null,2));
