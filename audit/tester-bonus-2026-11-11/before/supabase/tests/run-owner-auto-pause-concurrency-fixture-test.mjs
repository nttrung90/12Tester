import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';
import { makeOwnerResumeFixture, prepareOwnerResumeFixture, cleanupOwnerResumeFixture } from './owner-auto-pause-concurrency-fixture.mjs';

const db = await createDb();
const fixture = makeOwnerResumeFixture();
try {
  for (const name of readdirSync(new URL('../migrations/', import.meta.url)).filter(n => /^\d{3}_.*\.sql$/.test(n)).sort()) {
    await db.exec(read(`supabase/migrations/${name}`));
  }
  await prepareOwnerResumeFixture(db, fixture);
  const age = (await db.query(`select created_at <
    (((now() at time zone 'Asia/Ho_Chi_Minh')::date)::timestamp at time zone 'Asia/Ho_Chi_Minh')
    as old_account from public.profiles where id=$1`, [fixture.ownerId])).rows[0];
  assert.equal(age.old_account, true, 'The fixture must verify predecessor readiness, not use creation-day exemption');
  const asOwner = async id => {
    await db.exec(`set role authenticated; select set_config('request.jwt.claim.sub','${fixture.ownerId}',false)`);
    try { return (await db.query('select public.resume_campaign($1) r', [id])).rows[0].r; }
    finally { await db.exec("reset role; select set_config('request.jwt.claim.sub','',false)"); }
  };
  assert.equal((await asOwner(fixture.camp1)).status, 'open');
  await assert.rejects(() => asOwner(fixture.camp1), /CAMPAIGN_NOT_PAUSED/);
  assert.equal((await asOwner(fixture.camp2)).status, 'open');
  await cleanupOwnerResumeFixture(db, fixture);
  const remaining = (await db.query('select count(*)::int n from auth.users where id in ($1,$2,$3)',
    [fixture.ownerId, fixture.adminId, fixture.supplierId])).rows[0].n;
  assert.equal(remaining, 0);
  console.log('PASS: Exact PostgreSQL concurrency fixtures, ready status, sequential RPC contracts and cleanup verified locally.');
  console.log('UNRUN: This fixture check does not execute multiple PostgreSQL connections.');
} finally { await db.close(); }
