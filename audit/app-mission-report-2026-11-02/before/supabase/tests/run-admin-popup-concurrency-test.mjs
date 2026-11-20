/**
 * Concurrency verification runner for Admin Direct Notification Popup.
 * Tests race condition on the final allowed claim (5th view) across multiple concurrent PostgreSQL connections.
 *
 * Usage:
 *   $env:TEST_DATABASE_URL="postgresql://postgres:postgres@localhost:5432/postgres"
 *   node supabase/tests/run-admin-popup-concurrency-test.mjs
 */

import crypto from 'node:crypto';
import assert from 'node:assert/strict';

// Strict requirement T1: only accept TEST_DATABASE_URL, do NOT fallback to production DATABASE_URL
const databaseUrl = process.env.TEST_DATABASE_URL;

if (!databaseUrl) {
  console.log('========================================================================');
  console.log('NOTICE: TEST_DATABASE_URL is not set.');
  console.log('Skipping real multi-connection PostgreSQL concurrency test.');
  console.log('To run this test against a real PostgreSQL/Supabase test instance:');
  console.log('  export TEST_DATABASE_URL="postgresql://postgres:postgres@localhost:5432/postgres"');
  console.log('  node supabase/tests/run-admin-popup-concurrency-test.mjs');
  console.log('========================================================================');
  process.exit(0);
}

const { default: pg } = await import('pg');
const { Pool } = pg;
const pool = new Pool({ connectionString: databaseUrl, max: 10 });

const runId = crypto.randomUUID().replace(/-/g, '').substring(0, 8);
const adminId = crypto.randomUUID();
const targetUser = crypto.randomUUID();

console.log(`=== RUNNING MULTI-CONNECTION CONCURRENCY TEST (Run: ${runId}) ===`);

let notifId = null;

try {
  // 1. Setup notification with display_count = 4 using administrative connection
  const setupClient = await pool.connect();
  try {
    // Seed isolated users as administrative connection (superuser / service_role)
    await setupClient.query(`
      insert into auth.users(id, email) values
        ('${adminId}', 'runner-admin-${runId}@test.local'),
        ('${targetUser}', 'runner-target-${runId}@test.local');
      insert into public.profiles(id, email, display_name, role, is_locked) values
        ('${adminId}', 'runner-admin-${runId}@test.local', 'Runner Admin', 'admin', false),
        ('${targetUser}', 'runner-target-${runId}@test.local', 'Runner Target', 'member', false)
      on conflict (id) do update set role = excluded.role, is_locked = excluded.is_locked;
    `);

    // Send direct notification as authenticated admin
    await setupClient.query(`
      select set_config('request.jwt.claim.sub', '${adminId}', false);
      set role authenticated;
      select public.admin_send_notification('${targetUser}', 'Concurrency Test Notif ${runId}', 'Test msg', 'admin');
      reset role;
      reset "request.jwt.claim.sub";
    `);

    // Retrieve notification ID as administrative connection (not blocked by target RLS)
    const notifRow = (await setupClient.query(`
      select id from public.notifications where user_id = '${targetUser}' and title = 'Concurrency Test Notif ${runId}'
    `)).rows[0];
    assert.ok(notifRow, 'Created notification must be retrieved by administrative query');
    notifId = notifRow.id;

    // Fast-forward display_count to 4 as administrative connection (not blocked by client RLS/privileges)
    await setupClient.query(`
      update public.notification_popup_states set display_count = 4 where notification_id = '${notifId}'
    `);
  } finally {
    setupClient.release();
  }

  // 2. Launch 8 concurrent connections claiming simultaneously using role authenticated for target user
  const concurrentClients = 8;
  const promises = [];

  for (let i = 1; i <= concurrentClients; i++) {
    const sessionId = crypto.randomUUID();
    promises.push((async () => {
      const c = await pool.connect();
      try {
        await c.query(`select set_config('request.jwt.claim.sub', '${targetUser}', false); set role authenticated;`);
        const claimRes = (await c.query(`
          select public.claim_my_notification_popup('${sessionId}') as r
        `)).rows[0].r;
        return claimRes;
      } finally {
        await c.query('reset role; reset "request.jwt.claim.sub";');
        c.release();
      }
    })());
  }

  const results = await Promise.all(promises);

  const claimedResults = results.filter(r => r.status === 'claimed');
  const noneResults = results.filter(r => r.status === 'none');

  console.log(`Results: ${claimedResults.length} claimed, ${noneResults.length} none.`);

  assert.equal(claimedResults.length, 1, 'Exactly one concurrent connection must receive the 5th claim');
  assert.equal(claimedResults[0].display_count, 5, 'Claimed display_count must be 5');
  assert.equal(noneResults.length, concurrentClients - 1, 'All other concurrent connections must receive status none');

  // 3. Verify DB state as administrative connection
  const checkClient = await pool.connect();
  try {
    const finalState = (await checkClient.query(`
      select display_count from public.notification_popup_states where notification_id = '${notifId}'
    `)).rows[0];
    assert.equal(finalState.display_count, 5, 'Final DB display_count must never exceed 5');
    console.log('PASS: Concurrency test on 5th claim passed cleanly without race condition.');
  } finally {
    checkClient.release();
  }
} catch (e) {
  console.error('FAIL: Concurrency test failed', e);
  process.exit(1);
} finally {
  // Cleanup test fixtures
  try {
    const cleanupClient = await pool.connect();
    try {
      if (notifId) {
        await cleanupClient.query(`delete from public.notifications where id = '${notifId}'`);
      }
      await cleanupClient.query(`delete from auth.users where id in ('${adminId}', '${targetUser}')`);
    } finally {
      cleanupClient.release();
    }
  } catch (_cleanupErr) {}
  await pool.end();
}
