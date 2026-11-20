import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db = await createDb();
try {
  for (const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n => /^\d{3}_/.test(n) && Number(n.slice(0,3)) <= 78).sort()) {
    await db.exec(read('supabase/migrations/'+name));
  }
  await db.exec("insert into auth.users(id,email) values ('ae100000-0000-0000-0000-000000000001','old@fixture.invalid'),('ae100000-0000-0000-0000-000000000002','new@fixture.invalid'); update public.profiles set created_at=now()-interval '4 days' where id='ae100000-0000-0000-0000-000000000001'");
  const result=await db.query(read('supabase/operations/owner_auto_pause_preflight.sql'));
  assert.equal(result.rows.length,1,'Include older account with missing predecessor outside coverage; exclude creation day');
  assert.equal(result.rows[0].required_review,'NEEDS_VERIFIED_LEGACY_CHECKPOINT');
  assert.equal(result.rows[0].last_known_carry_out,null,'Unknown debt must not become zero');
  console.log('PASS: Preflight works before 079, detects uncovered predecessor, and preserves unknown debt.');
} finally {await db.close();}
