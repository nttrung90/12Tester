import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {connect} from './independent-fixture.mjs';
const file=new URL('../../supabase/migrations/088_immediate_daily_quota_on_campaign_changes.sql',import.meta.url);
const sql=readFileSync(file,'utf8');assert.match(sql,/COMMIT;\s*$/i,'Refuse an incomplete migration draft');
const db=await connect();
try {
  await db.query(sql);
  writeFileSync(new URL('./native-applied-088.sql',import.meta.url),sql);
  const hash=createHash('sha256').update(sql).digest('hex');
  writeFileSync(new URL('./native-applied-088.json',import.meta.url),JSON.stringify({hash,appliedAt:new Date().toISOString(),database:'immediate_quota_test',environment:'Private task-local native PostgreSQL'},null,2)+'\n');
  console.log('Applied isolated 088 snapshot',hash);
}catch(e){await db.query('rollback');throw e;}finally{await db.end();}
