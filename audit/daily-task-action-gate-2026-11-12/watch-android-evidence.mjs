// Temporary supervisor helper for this live task only. No builds, network or source writes.
import {readdirSync,readFileSync,writeFileSync,appendFileSync,mkdirSync,statSync} from 'node:fs';
import {createHash} from 'node:crypto';
const dir=new URL('../../app/build/test-results/testDebugUnitTest/',import.meta.url);
const run=new Date().toISOString().replace(/[:.]/g,'-');
const out=new URL('./supervisor-runs/android-live-'+run+'/',import.meta.url);
mkdirSync(out,{recursive:true});
const seen=new Map();let busy=false;
function scan(){
  if(busy)return;busy=true;
  try{
    let names;try{names=readdirSync(dir).filter(n=>/^TEST-.*\.xml$/.test(n));}catch{return;}
    for(const name of names){
      try{
        const file=new URL(name,dir),meta=statSync(file),key=meta.mtimeMs+':'+meta.size;
        if(seen.get(name)===key)continue;
        const bytes=readFileSync(file),xml=bytes.toString('utf8');
        if(!xml.trimEnd().endsWith('</testsuite>'))continue;
        const head=xml.match(/<testsuite\b[^>]*>/)?.[0];if(!head)continue;
        const attrs=Object.fromEntries([...head.matchAll(/([\w.-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
        const hash=createHash('sha256').update(bytes).digest('hex');
        const saved=hash.slice(0,16)+'-'+name;writeFileSync(new URL(saved,out),bytes);
        const record={observedAt:new Date().toISOString(),file:name,saved,mtime:meta.mtime.toISOString(),sha256:hash,name:attrs.name,tests:Number(attrs.tests),failures:Number(attrs.failures??0),errors:Number(attrs.errors??0),timestamp:attrs.timestamp};
        appendFileSync(new URL('manifest.jsonl',out),JSON.stringify(record)+'\n');seen.set(name,key);
        if(record.failures||record.errors||/Supervisor|ActionGate/.test(record.name))console.log(JSON.stringify(record));
      }catch{/* File replaced/deleted by Gradle: retry the next snapshot. */}
    }
  }finally{busy=false;}
}
console.log('Watching existing JUnit XML only; evidence '+out.href+'; send stop to finish.');
scan();const timer=setInterval(scan,750);
const deadline=setTimeout(finish,2*60*60*1000);
function finish(){clearInterval(timer);clearTimeout(deadline);scan();console.log('Stopped Android evidence watcher.');process.exit(0);}
process.stdin.setEncoding('utf8');process.stdin.on('data',data=>{if(data.includes('stop'))finish();});
