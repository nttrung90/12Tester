import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
import {readdirSync,writeFileSync,mkdirSync} from 'node:fs';
const db=await createDb();
const folder=new URL('./sql-review/',import.meta.url);mkdirSync(folder,{recursive:true});
const definitions=async()=>new Map((await db.query(`select p.proname||'('||oidvectortypes(p.proargtypes)||')' as name,pg_get_functiondef(p.oid) as def from pg_proc p where p.pronamespace='public'::regnamespace and p.prokind='f'`)).rows.map(r=>[r.name,r.def]));
const normalized=s=>s.split('\n').map(l=>l.replace(/--.*$/,'').trim()).filter(Boolean).join('\n')+'\n';
try{
 const files=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d+.*\.sql$/.test(n)).sort();
 for(const file of files.filter(n=>Number(n.slice(0,3))<=68))await db.exec(read('supabase/migrations/'+file));
 const before=await definitions();
 for(const file of files.filter(n=>Number(n.slice(0,3))>68))await db.exec(read('supabase/migrations/'+file));
 const after=await definitions();
 for(const [name,def]of after){if(def!==before.get(name)){
  const label=name.replace(/[^a-zA-Z0-9]/g,'_');
  writeFileSync(new URL(label+'.before.sql',folder),normalized(before.get(name)??''));
  writeFileSync(new URL(label+'.after.sql',folder),normalized(def));
  console.log(name);
 }}
}finally{await db.close();}
