import {readFileSync,writeFileSync,readdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
const root=resolve(new URL('../..',import.meta.url).pathname.replace(/^\/([A-Z]:)/i,'$1'));
const baseline=JSON.parse(readFileSync(new URL('./before-manifest.json',import.meta.url),'utf8'));
const hash=p=>createHash('sha256').update(readFileSync(resolve(root,p))).digest('hex');
const changed=baseline.filter(x=>hash(x.path).toUpperCase()!==x.sha256.toUpperCase()).map(x=>({path:x.path,sha256:hash(x.path)}));
const added=[
 'supabase/migrations/088_immediate_daily_quota_on_campaign_changes.sql',
 'supabase/tests/run-immediate-daily-quota-acceptance-test.mjs',
 'app/src/main/java/com/ntt/crosstest/data/DailyTaskChangeEvent.kt',
 'app/src/test/java/com/ntt/crosstest/data/ImmediateDailyQuotaUnitTest.kt',
 'app/src/test/java/com/ntt/crosstest/data/SupervisorImmediateQuotaRaceTest.kt',
].map(path=>({path,sha256:hash(path)}));
writeFileSync(new URL('./verified-source-fingerprint.json',import.meta.url),JSON.stringify({at:new Date().toISOString(),changed,added},null,2)+'\n');
console.log('Fingerprinted',changed.length,'modified baseline files and',added.length,'known new implementation/test files');
