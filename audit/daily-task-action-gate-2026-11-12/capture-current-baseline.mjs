import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,readdirSync,mkdirSync,copyFileSync,existsSync,statSync} from 'node:fs';
import {resolve,join,relative,dirname,extname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
const audit=fileURLToPath(new URL('./',import.meta.url));
const root=resolve(audit,'../..');
const stamp=new Date().toISOString().replace(/[:.]/g,'-');
const dest=join(audit,'baseline-'+stamp);
assert.ok(!existsSync(dest));mkdirSync(dest,{recursive:true});
const hash=p=>createHash('sha256').update(readFileSync(p)).digest('hex');
const skip=/^(?:node_modules|build|\.git|\.gradle|audit)$/i;
const sensitive=/^(?:\.env(?:\..*)?|local\.properties|google-services\.json|.*\.(?:jks|keystore)|.*credentials.*|plugin-secrets\.json)$/i;
const extensions=new Set(['.kt','.kts','.sql','.mjs','.js','.ts','.json','.xml','.md','.toml','.sh']);
function walk(dir,fn) {if(!existsSync(dir))return;for(const e of readdirSync(dir,{withFileTypes:true})){
  if(sensitive.test(e.name))continue;const p=join(dir,e.name);
  if(e.isDirectory()){if(!skip.test(e.name))walk(p,fn);}else if(e.isFile())fn(p);
}}
const files=[];
for(const base of ['app/src','supabase/migrations','supabase/tests','supabase/tools','backend'])walk(join(root,base),p=>{if(extensions.has(extname(p)))files.push(p);});
for(const name of ['app/build.gradle.kts','gradle/libs.versions.toml','LOCK_ORDER.md','KE_HOACH_CHAN_THAO_TAC_APP_KHI_CHUA_XONG_NHIEM_VU_2026-11-12.md','PROMPT_ANTIGRAVITY_CHAN_THAO_TAC_APP_2026-11-12.md','tools/Start-DailyTaskActionGateAntigravity.ps1','audit/daily-task-action-gate-2026-11-12/SESSION_SETTINGS.json'])files.push(join(root,name));
const manifest=[];
for(const p of [...new Set(files)].sort()) {const name=relative(root,p).replaceAll('\\','/');const out=join(dest,'source',name);mkdirSync(dirname(out),{recursive:true});copyFileSync(p,out);manifest.push({path:name,sha256:hash(p)});}
writeFileSync(join(dest,'source-manifest.json'),JSON.stringify(manifest,null,2));
writeFileSync(join(dest,'git-status.txt'),execFileSync('git',['status','--short','--untracked-files=normal'],{cwd:root,encoding:'utf8',windowsHide:true}));
const packages=[];
for(const base of ['app/build/outputs','app/release','.tmp/release','app/build/intermediates/intermediary_bundle'])walk(join(root,base),p=>{if(/\.(?:apk|aab)$/i.test(p)){const s=statSync(p);packages.push({path:relative(root,p).replaceAll('\\','/'),sha256:hash(p),bytes:s.size,mtime:s.mtime.toISOString()});}});
writeFileSync(join(dest,'packages.json'),JSON.stringify(packages,null,2));
writeFileSync(join(audit,'CURRENT_BASELINE.txt'),relative(root,dest).replaceAll('\\','/')+'\n');
console.log(JSON.stringify({baseline:relative(root,dest),sourceFiles:manifest.length,packages:packages.length,permissionSettingsChanged:false}));
