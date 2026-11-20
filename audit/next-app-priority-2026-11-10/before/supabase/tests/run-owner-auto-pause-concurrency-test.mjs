/**
 * Real multi-connection checks. Use only a dedicated migrated TEST_DATABASE_URL.
 * Fixture validation is separate and never claimed as a concurrent execution.
 */
import assert from 'node:assert/strict';
import { makeOwnerResumeFixture, prepareOwnerResumeFixture, cleanupOwnerResumeFixture } from './owner-auto-pause-concurrency-fixture.mjs';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (!databaseUrl) {
  console.log('UNRUN/SKIPPED: TEST_DATABASE_URL is absent; real PostgreSQL concurrency was not executed.');
  process.exit(0);
}
const { Pool } = await import('pg');
const pool = new Pool({
  connectionString: databaseUrl, max: 4, connectionTimeoutMillis: 10000,
  statement_timeout: 10000, query_timeout: 15000,
});
const fixture = makeOwnerResumeFixture();

async function withClient(fn) {
  const client = await pool.connect();
  try { return await fn(client); }
  finally { client.release(); }
}
async function runAsOwner(client, campaignId) {
  await client.query('begin');
  try {
    await client.query("select set_config('request.jwt.claim.sub',$1,true)", [fixture.ownerId]);
    await client.query('set local role authenticated');
    const result = await client.query('select public.resume_campaign($1) r', [campaignId]);
    await client.query('commit');
    return result.rows[0].r;
  } catch (error) {
    await client.query('rollback');
    throw error;
  }
}

try {
  await withClient(async client => {
    await client.query('begin');
    try {
      await prepareOwnerResumeFixture(client, fixture);
      await client.query('commit');
    } catch (error) {
      await client.query('rollback');
      throw error;
    }
  });

  const duplicate = await Promise.allSettled([
    withClient(client => runAsOwner(client, fixture.camp1)),
    withClient(client => runAsOwner(client, fixture.camp1)),
  ]);
  const won = duplicate.filter(r => r.status === 'fulfilled');
  const lost = duplicate.filter(r => r.status === 'rejected');
  assert.equal(won.length, 1, 'Exactly one duplicate resume must succeed');
  assert.equal(won[0].value.status, 'open');
  assert.equal(lost.length, 1);
  assert.match(lost[0].reason.message, /CAMPAIGN_NOT_PAUSED/);
  console.log('PASS: Duplicate requests produced one open and one CAMPAIGN_NOT_PAUSED.');

  await withClient(client => client.query(
    "update public.campaigns set status='paused',paused_reason='owner_inactive' where id in ($1,$2)",
    [fixture.camp1, fixture.camp2],
  ));
  const distinct = await Promise.allSettled([
    withClient(client => runAsOwner(client, fixture.camp1)),
    withClient(client => runAsOwner(client, fixture.camp2)),
  ]);
  assert.equal(distinct.filter(r => r.status === 'fulfilled').length, 2,
    'Both distinct campaigns must resume; deadlocks and all other errors fail the test');
  for (const result of distinct) assert.equal(result.value.status, 'open');
  await withClient(async client => {
    const { rows: [row] } = await client.query(
      "select public.get_daily_task_status($1,(now() at time zone 'Asia/Ho_Chi_Minh')::date) s",
      [fixture.ownerId],
    );
    assert.equal(row.s.carryover_remaining_tests, 0);
    assert.equal(row.s.completed_tests, fixture.supplyIds.length);
    const { rows: [profile] } = await client.query('select credits from public.profiles where id=$1', [fixture.ownerId]);
    assert.equal(profile.credits, 1000, 'Resume must not charge or double-subtract anything');
  });
  console.log('PASS: Distinct requests both opened, with unchanged test count, debt and credits.');
} finally {
  try { await withClient(client => cleanupOwnerResumeFixture(client, fixture)); }
  catch (error) {
    console.error('Fixture cleanup failed:', error.message);
    process.exitCode = 1;
  }
  await pool.end();
}
