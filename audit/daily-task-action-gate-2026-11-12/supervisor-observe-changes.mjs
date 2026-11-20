import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,existsSync,readdirSync,statSync} from 'node:fs';
import {resolve,join,relative} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const audit=fileURLToPath(new URL('./',import.meta.url));const root=resolve(audit,'../..');
const baseline=resolve(root,readFileSync(new URL('./CURRENT_BASELINE.txt',import.meta.url),'utf8').trim());
assert.ok(baseline.startsWith(audit.replace(/[\\/]$/,'')));
const hash=p=>createHash('sha256').update(readFileSync(p)).digest('hex');
const r2=process.argv.includes('--r2')?JSON.parse(readFileSync(new URL('./SUPERVISOR_R2_START.json',import.meta.url),'utf8')):null;
const sources=r2?.sources??JSON.parse(readFileSync(resolve(baseline,'source-manifest.json'),'utf8'));
const changed=sources.filter(r=>!existsSync(resolve(root,r.path))||hash(resolve(root,r.path))!==r.sha256).map(r=>r.path);
const packages=r2?.packages??JSON.parse(readFileSync(resolve(baseline,'packages.json'),'utf8'));
const changedPackages=packages.filter(r=>!existsSync(resolve(root,r.path))||hash(resolve(root,r.path))!==r.sha256).map(r=>r.path);
function walk(dir,out=[],select=/\.(apk|aab)$/i){if(!existsSync(dir))return out;for(const e of readdirSync(dir,{withFileTypes:true})){
  const p=join(dir,e.name);if(e.isDirectory()){if(!/^(node_modules|audit|\.git|\.gradle)$/i.test(e.name))walk(p,out,select);}
  else if(e.isFile()&&select.test(e.name)&&!/^(google-services\.json|.*credentials.*|plugin-secrets\.json)$/i.test(e.name))out.push(relative(root,p).replaceAll('\\','/'));
}return out;}
const allPackages=['app/build/outputs','app/release','.tmp/release','app/build/intermediates/intermediary_bundle'].flatMap(p=>walk(resolve(root,p)));
const newPackages=allPackages.filter(p=>!packages.some(r=>r.path===p));
const touchedPackages=packages.filter(r=>existsSync(resolve(root,r.path))&&statSync(resolve(root,r.path)).mtime.toISOString()!==r.mtime).map(r=>r.path);
const sourcePaths=new Set(sources.map(r=>r.path));
const newSource=['app/src','supabase/migrations','supabase/tests','supabase/tools','backend'].flatMap(p=>walk(resolve(root,p),[],/\.(kt|kts|sql|mjs|js|ts|json|xml|md|toml|sh)$/i)).filter(p=>!sourcePaths.has(p)).sort();
const newMigrations=readdirSync(resolve(root,'supabase/migrations')).filter(n=>/^\d{3}_/.test(n)&&Number(n.slice(0,3))>89);
const protectedChanged=changed.filter(p=>/^supabase[\\/]migrations[\\/]0(6[2-9]|[78]\d)_/.test(p)||['app/build.gradle.kts','tools/Start-DailyTaskActionGateAntigravity.ps1','audit/daily-task-action-gate-2026-11-12/SESSION_SETTINGS.json'].includes(p));
const result={at:new Date().toISOString(),sourceFiles:sources.length,changedSource:changed,newSource,packageFiles:packages.length,changedPackages,newPackages,touchedPackages,protectedChanged,newMigrations};
writeFileSync(new URL(r2?'./supervisor-r2-current-changes.json':'./supervisor-current-changes.json',import.meta.url),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
assert.equal(changedPackages.length,0,'existing packages changed');
assert.equal(newPackages.length,0,'new packages created');
assert.equal(protectedChanged.length,0,'protected migrations/version changed');
