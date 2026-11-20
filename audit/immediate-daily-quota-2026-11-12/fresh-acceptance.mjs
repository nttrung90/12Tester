import {freshNative} from './fresh-native.mjs';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const name='immediate_quota_native_accept_'+Date.now();
const db=await freshNative(name);
const sql=readFileSync(new URL('../../supabase/migrations/088_immediate_daily_quota_on_campaign_changes.sql',import.meta.url),'utf8');
// Verify applying the identical final migration a second time preserves configuration.
const before=(await db.query('select * from public.daily_task_immediate_quota_policies order by effective_from')).rows;
await db.query(sql);
const after=(await db.query('select * from public.daily_task_immediate_quota_policies order by effective_from')).rows;
await db.end();
if(JSON.stringify(before)!==JSON.stringify(after))throw Error('Migration replay changed activation history');
console.log('PASS: Fresh088 and replay preserve effective policy rows');
writeFileSync(new URL('./fresh-acceptance-snapshot.json',import.meta.url),JSON.stringify({database:name,sha256:createHash('sha256').update(sql).digest('hex'),at:new Date().toISOString()},null,2));
process.env.QUOTA_PRIVATE_DATABASE=name;
await import('./independent-acceptance.mjs');
