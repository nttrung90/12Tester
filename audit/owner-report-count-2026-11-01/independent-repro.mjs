import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'a1000000-0000-0000-0000-000000000001';
const tester = 'a1000000-0000-0000-0000-000000000002';
const campaign = 'b1000000-0000-0000-0000-000000000001';
const files = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d+.*\.sql$/.test(n)).sort();
try {
  for (const name of files.filter(n => Number(n.slice(0, 3)) <= 67)) {
    await db.exec(read('supabase/migrations/' + name));
  }
  await db.exec(`
    insert into auth.users(id,email) values ('${owner}','owner@fixture.invalid'),('${tester}','tester@fixture.invalid');
    insert into public.campaigns(id,owner_id,app_name,package_name) values ('${campaign}','${owner}','Count fixture','invalid.fixture.count');
    insert into public.tester_reports(campaign_id,user_id,status,created_at) values ('${campaign}','${tester}','pending',now());
    select set_config('request.jwt.claim.sub','${owner}',false);
    set role authenticated;
  `);
  const detail = async () => (await db.query(`select public.get_campaign_detail('${campaign}') as detail`)).rows[0].detail;
  const before = await detail();
  assert.equal(before.reports.filter(r => r.status === 'pending').length, 1);
  assert.equal(Object.hasOwn(before, 'pending_reports_count'), false);
  assert.equal(before.reports_count, 0);
  console.log('REPRODUCED before fix: one pending tester report; pending_reports_count missing; reports_count=0.');
  await db.exec('reset role');
  const patches = files.filter(n => Number(n.slice(0, 3)) > 67);
  if (patches.length) {
    for (const name of patches) await db.exec(read('supabase/migrations/' + name));
    await db.exec('set role authenticated');
    const after = await detail();
    assert.equal(after.pending_reports_count, 1);
    assert.equal(after.tester_reports_count, 1);
    assert.equal(after.reports_count, 0);
    console.log('VERIFIED after fix: pending_reports_count=1; tester_reports_count=1; feedback reports_count remains 0.');
  }
} finally {
  await db.close();
}
