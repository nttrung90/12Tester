import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';
import {
  makeBonusConcurrencyFixture,
  prepareBonusConcurrencyFixture,
  cleanupBonusConcurrencyFixture,
  makePhotoUrl,
  makeThumbUrl,
} from './campaign-tester-bonus-concurrency-fixture.mjs';

const db = await createDb();
const fixture = makeBonusConcurrencyFixture();

try {
  console.log('=== LOADING MIGRATIONS ===');
  for (const name of readdirSync(new URL('../migrations/', import.meta.url)).filter(n => /^\d{3}_.*\.sql$/.test(n)).sort()) {
    await db.exec(read(`supabase/migrations/${name}`));
  }

  console.log('=== PREPARING CONCURRENCY FIXTURE ===');
  await prepareBonusConcurrencyFixture(db, fixture);

  const asUser = async (userId, fn) => {
    await db.exec(`set role authenticated; select set_config('request.jwt.claim.sub', '${userId}', false)`);
    try {
      return await fn();
    } finally {
      await db.exec("reset role; select set_config('request.jwt.claim.sub', '', false)");
    }
  };

  // Test Sequential Wallet Bound Check
  // Owner has 150 credits. Round 1 costs 10 * 10 = 100 credits.
  const reqId1 = 'c0000001-0000-0000-0000-000000000001';
  const reqId2 = 'c0000001-0000-0000-0000-000000000002';

  const r1 = await asUser(fixture.ownerId, async () => {
    const res = await db.query(
      `select public.create_campaign_tester_bonus(
        p_campaign_id => $1,
        p_reward_credits => 10,
        p_max_recipients => 10,
        p_client_request_id => $2
      ) as r`,
      [fixture.camp1Id, reqId1]
    );
    return res.rows[0].r;
  });
  assert.equal(r1.status, 'active');
  assert.equal(r1.remaining_slots, 10);

  // Check owner credits decremented to 50
  const ownerProfile = (await db.query('select credits from public.profiles where id = $1', [fixture.ownerId])).rows[0];
  assert.equal(ownerProfile.credits, 50);

  // Attempting another round of 10 * 10 = 100 credits must fail with INSUFFICIENT_CREDITS
  await assert.rejects(
    () => asUser(fixture.ownerId, async () => {
      await db.query(
        `select public.create_campaign_tester_bonus(
          p_campaign_id => $1,
          p_reward_credits => 10,
          p_max_recipients => 10,
          p_client_request_id => $2
        ) as r`,
        [fixture.camp2Id, reqId2]
      );
    }),
    /INSUFFICIENT_CREDITS/
  );

  // Submit report as tester1
  const report1 = await asUser(fixture.tester1Id, async () => {
    const res = await db.query(
      `select public._submit_tester_report_core(
        'app_test',
        $1::uuid,
        'Sequential test feedback',
        5,
        $2::uuid,
        $3,
        '0123456789abcdef',
        $4,
        null
      ) as r`,
      [
        fixture.camp1Id,
        fixture.report1Id,
        makePhotoUrl(fixture.camp1Id, fixture.tester1Id, 'inst1'),
        makeThumbUrl(fixture.camp1Id, fixture.tester1Id, 'inst1'),
      ]
    );
    return res.rows[0].r;
  });

  const reportId = report1.report_id;
  const repRow = (await db.query('select id, tester_bonus_round_id from public.tester_reports where id = $1', [reportId])).rows[0];
  assert.equal(repRow.tester_bonus_round_id, r1.round_id);

  // Owner approves report
  await asUser(fixture.ownerId, async () => {
    await db.query('select public.approve_tester_report($1, 0)', [reportId]);
  });

  // Verify tester1 credits: initial 10 + 10 (provisional) + 2 (base manual review) + 10 (tester bonus) = 32
  const tester1Profile = (await db.query('select credits from public.profiles where id = $1', [fixture.tester1Id])).rows[0];
  assert.equal(tester1Profile.credits, 32);

  console.log('=== CLEANING UP CONCURRENCY FIXTURE ===');
  await cleanupBonusConcurrencyFixture(db, fixture);

  const remainingUsers = (await db.query(
    'select count(*)::int as c from auth.users where id in ($1, $2, $3, $4, $5)',
    [fixture.ownerId, fixture.adminId, fixture.tester1Id, fixture.tester2Id, fixture.tester3Id]
  )).rows[0].c;
  assert.equal(remainingUsers, 0);

  console.log('PASS: Concurrency fixture setup, sequential contracts, and full cleanup verified on PGlite.');
  console.log('UNRUN: Multi-connection concurrency requires real PostgreSQL via run-campaign-tester-bonus-concurrency-test.mjs.');
} finally {
  await db.close();
}
