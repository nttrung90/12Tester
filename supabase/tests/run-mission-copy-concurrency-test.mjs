/**
 * Real PostgreSQL multi-connection concurrency checks for Mission Copy (Migration 100).
 * Requires a dedicated migrated TEST_DATABASE_URL. Never reports skipped as PASS.
 */
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (!databaseUrl) {
  console.log('UNRUN/SKIPPED: TEST_DATABASE_URL is absent; real PostgreSQL concurrency was not executed.');
  process.exit(0);
}

const { Pool } = await import('pg');
const pool = new Pool({
  connectionString: databaseUrl,
  max: 6,
  connectionTimeoutMillis: 10000,
  statement_timeout: 10000,
  query_timeout: 15000,
});

const ownerId = randomUUID();
const campRaceId = randomUUID();
const campRetryId = randomUUID();
const srcRaceId = randomUUID();
const srcRetryId = randomUUID();

async function withClient(fn) {
  const client = await pool.connect();
  try {
    return await fn(client);
  } finally {
    client.release();
  }
}

async function holdMissionPastDeadline(client, missionId) {
  // Take the same row lock as resume_mission with the fixture role. Authenticated
  // RLS permits reads but filters out rows when locking them for update directly.
  const held = await client.query(
    'select now() < ends_at as started_before_deadline from public.missions where id = $1 for update',
    [missionId]
  );
  assert.equal(held.rowCount, 1, 'The fixture must lock the paused mission');
  assert.equal(held.rows[0].started_before_deadline, true, 'The resume transaction must start before the deadline');
  await client.query(
    `select pg_sleep(greatest(0, extract(epoch from ends_at - clock_timestamp()) + 0.025)::double precision)
     from public.missions where id = $1`,
    [missionId]
  );
}

async function callCreateFromSource(client, { sourceId, campaignId, title, opId }) {
  await client.query('begin');
  try {
    await client.query("select set_config('request.jwt.claim.sub', $1, true)", [ownerId]);
    await client.query('set local role authenticated');
    const res = await client.query(
      'select public.create_mission_from_source($1, $2, $3, $4, 3, 2, 15, $5, true, $6) r',
      [sourceId, campaignId, title, 'Concurrent test', 'once', opId]
    );
    await client.query('commit');
    return res.rows[0].r;
  } catch (error) {
    await client.query('rollback');
    throw error;
  }
}

try {
  await withClient(async client => {
    await client.query('begin');
    try {
      await client.query(
        'insert into auth.users (id, email) values ($1, $2)',
        [ownerId, `mission_copy_conc_${ownerId}@test.local`]
      );
      await client.query(
        "update public.profiles set credits = 5000, display_name = 'Conc Owner', role = 'member', is_locked = false where id = $1",
        [ownerId]
      );
      await client.query(
        'insert into public.user_slots (user_id, slot_number, expires_at) values ($1, 2, now() + interval \'30 days\')',
        [ownerId]
      );
      await client.query(
        "insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values ($1, $2, $3, 'Race App', 'open', 1), ($4, $2, $5, 'Retry App', 'open', 2)",
        [campRaceId, ownerId, `com.conc.race.${ownerId.slice(0, 8)}`, campRetryId, `com.conc.retry.${ownerId.slice(0, 8)}`]
      );
      await client.query(
        "insert into public.missions (id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits, fee_credits, status, ends_at, reward_mode) values ($1, $2, $3, 'Src Race', 'D', 3, 2, 15, 100, 'expired', now() - interval '1 hour', 'once'), ($4, $2, $5, 'Src Retry', 'D', 3, 2, 15, 100, 'expired', now() - interval '1 hour', 'once')",
        [srcRaceId, ownerId, campRaceId, srcRetryId, ownerId, campRetryId]
      );
      await client.query('commit');
    } catch (e) {
      await client.query('rollback');
      throw e;
    }
  });

  // 1. Two concurrent copy requests with different operation IDs on same campaign -> strictly 1 succeeds
  const raceResults = await Promise.allSettled([
    withClient(c => callCreateFromSource(c, { sourceId: srcRaceId, campaignId: campRaceId, title: 'Copy Race 1', opId: `op-race-1-${ownerId}` })),
    withClient(c => callCreateFromSource(c, { sourceId: srcRaceId, campaignId: campRaceId, title: 'Copy Race 2', opId: `op-race-2-${ownerId}` })),
  ]);
  const raceWon = raceResults.filter(r => r.status === 'fulfilled');
  const raceLost = raceResults.filter(r => r.status === 'rejected');
  assert.equal(raceWon.length, 1, 'Exactly one concurrent copy on same app must succeed');
  assert.equal(raceLost.length, 1);
  assert.match(raceLost[0].reason.message, /MISSION_ALREADY_EXISTS/);

  // 2. Two concurrent retries with SAME client_operation_id -> both return same mission_id, 1 replay, single deduction
  const sameOpId = `op-retry-${ownerId}`;
  const balBefore = Number((await pool.query('select credits from public.profiles where id = $1', [ownerId])).rows[0].credits);
  const retryResults = await Promise.all([
    withClient(c => callCreateFromSource(c, { sourceId: srcRetryId, campaignId: campRetryId, title: 'Copy Retry', opId: sameOpId })),
    withClient(c => callCreateFromSource(c, { sourceId: srcRetryId, campaignId: campRetryId, title: 'Copy Retry', opId: sameOpId })),
  ]);
  assert.equal(retryResults[0].mission_id, retryResults[1].mission_id, 'Concurrent retries must return identical mission_id');
  const replayFlags = retryResults.map(r => r.is_replay).sort();
  assert.deepEqual(replayFlags, [false, true], 'One execution creates, one replays');
  const balAfter = Number((await pool.query('select credits from public.profiles where id = $1', [ownerId])).rows[0].credits);
  assert.equal(balAfter, balBefore - 145, 'Fee (100) + Escrow (45) must be deducted strictly once');

  // 3. Replay after edit_mission still returns original mission_id without conflict
  const copiedId = retryResults[0].mission_id;
  await withClient(async c => {
    await c.query('begin');
    try {
      await c.query("select set_config('request.jwt.claim.sub', $1, true)", [ownerId]);
      await c.query('set local role authenticated');
      const prev = (await c.query('select public.preview_edit_mission($1, $2, 4, 3, 20) r', [copiedId, 'once'])).rows[0].r;
      await c.query('select public.edit_mission($1, $2, $3, $4, 4, 3, 20, null, $5, $6)', [
        copiedId, 'Edited After Copy', 'Edited Desc', 'once', prev.version, prev.total_diff,
      ]);
      await c.query('commit');
    } catch (e) {
      await c.query('rollback');
      throw e;
    }
  });
  const replayAfterEdit = await withClient(c =>
    callCreateFromSource(c, { sourceId: srcRetryId, campaignId: campRetryId, title: 'Copy Retry', opId: sameOpId })
  );
  assert.equal(replayAfterEdit.is_replay, true);
  assert.equal(replayAfterEdit.mission_id, copiedId);

  // 4. Lock-order safety: concurrent resume_mission(paused) vs stale create_mission_from_source(paused) -> no deadlock
  const pausedMissionId = randomUUID();
  const campPausedId = randomUUID();
  await withClient(async c => {
    await c.query('begin');
    try {
      await c.query(
        'insert into public.user_slots (user_id, slot_number, expires_at) values ($1, 3, now() + interval \'30 days\')',
        [ownerId]
      );
      await c.query(
        "insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values ($1, $2, $3, 'Paused Lock App', 'open', 3)",
        [campPausedId, ownerId, `com.conc.paused.${ownerId.slice(0, 8)}`]
      );
      await c.query(
        "insert into public.missions (id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits, fee_credits, status, ends_at, reward_mode) values ($1, $2, $3, 'Paused Unexpired', 'D', 3, 2, 15, 100, 'paused_by_creator', now() + interval '2 days', 'once')",
        [pausedMissionId, ownerId, campPausedId]
      );
      await c.query('commit');
    } catch (e) {
      await c.query('rollback');
      throw e;
    }
  });

  const lockRace = await Promise.allSettled([
    withClient(async c => {
      await c.query('begin');
      try {
        await c.query("select set_config('request.jwt.claim.sub', $1, true)", [ownerId]);
        await c.query('set local role authenticated');
        const r = (await c.query('select public.resume_mission($1) r', [pausedMissionId])).rows[0].r;
        await c.query('commit');
        return r;
      } catch (e) {
        await c.query('rollback');
        throw e;
      }
    }),
    withClient(c =>
      callCreateFromSource(c, {
        sourceId: pausedMissionId,
        campaignId: campPausedId,
        title: 'Stale Copy Paused',
        opId: `op-stale-paused-${ownerId}`,
      })
    ),
  ]);
  assert.equal(lockRace[0].status, 'fulfilled');
  assert.equal(lockRace[0].value.status, 'open');
  assert.equal(lockRace[1].status, 'rejected');
  assert.match(lockRace[1].reason.message, /SOURCE_MISSION_NOT_EXPIRED/);

  // 5. Boundary clock deadlock test (A): resume tx starts BEFORE P.ends_at and holds row lock on P;
  //    copy tx starts AFTER P.ends_at -> NOWAIT returns MISSION_BUSY_RETRY without deadlock or debit
  const campBoundaryA = randomUUID();
  const missionBoundaryP = randomUUID();
  await withClient(async c => {
    await c.query('begin');
    try {
      await c.query(
        'insert into public.user_slots (user_id, slot_number, expires_at) values ($1, 4, now() + interval \'30 days\')',
        [ownerId]
      );
      await c.query(
        "insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values ($1, $2, $3, 'Boundary A', 'open', 4)",
        [campBoundaryA, ownerId, `com.conc.bounda.${ownerId.slice(0, 8)}`]
      );
      await c.query(
        "insert into public.missions (id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits, fee_credits, status, ends_at, reward_mode) values ($1, $2, $3, 'Boundary P', 'D', 3, 2, 15, 100, 'paused_by_creator', clock_timestamp() + interval '2 seconds', 'once')",
        [missionBoundaryP, ownerId, campBoundaryA]
      );
      await c.query('commit');
    } catch (e) {
      await c.query('rollback');
      throw e;
    }
  });

  const balBeforeBoundaryA = Number((await pool.query('select credits from public.profiles where id = $1', [ownerId])).rows[0].credits);
  const c1A = await pool.connect();
  try {
    await c1A.query('begin');
    await c1A.query("select set_config('request.jwt.claim.sub', $1, true)", [ownerId]);
    await holdMissionPastDeadline(c1A, missionBoundaryP);
    await c1A.query('set local role authenticated');

    const copyAfterDeadline = await withClient(async c2 => {
      try {
        await callCreateFromSource(c2, {
          sourceId: missionBoundaryP,
          campaignId: campBoundaryA,
          title: 'Boundary Copy P',
          opId: `op-bound-a-${ownerId}`,
        });
        return { ok: true };
      } catch (e) {
        return { ok: false, message: e.message };
      }
    });
    assert.equal(copyAfterDeadline.ok, false);
    assert.match(copyAfterDeadline.message, /MISSION_BUSY_RETRY/);

    const resumeA = (await c1A.query('select public.resume_mission($1) r', [missionBoundaryP])).rows[0].r;
    await c1A.query('commit');
    assert.equal(resumeA.status, 'open');
  } catch (e) {
    try { await c1A.query('rollback'); } catch {}
    throw e;
  } finally {
    c1A.release();
  }
  const balAfterBoundaryA = Number((await pool.query('select credits from public.profiles where id = $1', [ownerId])).rows[0].credits);
  assert.equal(balAfterBoundaryA, balBeforeBoundaryA, 'MISSION_BUSY_RETRY must not deduct credits');

  // 6. Boundary clock deadlock test (B): non-source mission B on same campaign is held by resume tx
  //    that started BEFORE B.ends_at, while copy tx starts AFTER B.ends_at targeting older expired A -> NOWAIT returns MISSION_BUSY_RETRY
  const campBoundaryB = randomUUID();
  const missionOldExpiredA = randomUUID();
  const missionJustExpiredB = randomUUID();
  await withClient(async c => {
    await c.query('begin');
    try {
      await c.query(
        'insert into public.user_slots (user_id, slot_number, expires_at) values ($1, 5, now() + interval \'30 days\')',
        [ownerId]
      );
      await c.query(
        "insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values ($1, $2, $3, 'Boundary B', 'open', 5)",
        [campBoundaryB, ownerId, `com.conc.boundb.${ownerId.slice(0, 8)}`]
      );
      await c.query(
        "insert into public.missions (id, creator_id, campaign_id, title, description, max_participants, duration_days, reward_credits, fee_credits, status, ends_at, reward_mode) values ($1, $2, $3, 'Old Expired A', 'D', 3, 2, 15, 100, 'expired', now() - interval '2 days', 'once'), ($4, $2, $3, 'Just Expired B', 'D', 3, 2, 15, 100, 'paused_by_creator', clock_timestamp() + interval '2 seconds', 'once')",
        [missionOldExpiredA, ownerId, campBoundaryB, missionJustExpiredB]
      );
      await c.query('commit');
    } catch (e) {
      await c.query('rollback');
      throw e;
    }
  });

  const c1B = await pool.connect();
  try {
    await c1B.query('begin');
    await c1B.query("select set_config('request.jwt.claim.sub', $1, true)", [ownerId]);
    await holdMissionPastDeadline(c1B, missionJustExpiredB);
    await c1B.query('set local role authenticated');

    const copyOlderAWhileBHeld = await withClient(async c2 => {
      try {
        await callCreateFromSource(c2, {
          sourceId: missionOldExpiredA,
          campaignId: campBoundaryB,
          title: 'Copy A While B Held',
          opId: `op-bound-b-${ownerId}`,
        });
        return { ok: true };
      } catch (e) {
        return { ok: false, message: e.message };
      }
    });
    assert.equal(copyOlderAWhileBHeld.ok, false);
    assert.match(copyOlderAWhileBHeld.message, /MISSION_BUSY_RETRY/);

    const resumeB = (await c1B.query('select public.resume_mission($1) r', [missionJustExpiredB])).rows[0].r;
    await c1B.query('commit');
    assert.equal(resumeB.status, 'open');
  } catch (e) {
    try { await c1B.query('rollback'); } catch {}
    throw e;
  } finally {
    c1B.release();
  }
  const balAfterBoundaryB = Number((await pool.query('select credits from public.profiles where id = $1', [ownerId])).rows[0].credits);
  assert.equal(balAfterBoundaryB, balBeforeBoundaryA, 'MISSION_BUSY_RETRY on non-source sweep must not deduct credits');

  console.log('PASS: PostgreSQL multi-connection concurrency checks passed (6/6)');
} finally {
  try {
    await pool.query("update public.missions set status = 'deleted' where creator_id = $1", [ownerId]);
    await pool.query(
      'select public._check_and_refund_mission_escrow(id) from public.missions where creator_id = $1',
      [ownerId]
    );
    await pool.query('delete from public.campaigns where owner_id = $1', [ownerId]);
    await pool.query('delete from public.credit_logs where user_id = $1', [ownerId]);
    await pool.query('delete from public.user_slots where user_id = $1', [ownerId]);
    await pool.query('delete from auth.users where id = $1', [ownerId]);
    await pool.query('delete from public.profiles where id = $1', [ownerId]);
  } catch {
    // best-effort cleanup on disposable test DB
  }
  await pool.end();
}
