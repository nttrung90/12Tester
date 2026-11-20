/**
 * Real multi-connection concurrency test for Campaign Tester Bonus Rounds.
 * Requirements:
 * - Dedicated local/staging test database via TEST_DATABASE_URL.
 * - Must NOT run against non-test or production endpoints.
 * - 7 Scenarios, each executed with genuine fresh isolated fixture per scenario:
 *   1. Two apps of same owner reserving wallet concurrently (wallet contention -> no overdraft).
 *   2. Last slot race (2 testers competing for 1 slot -> exactly 1 bonus, no budget breach, no deadlock).
 *   3. Manual approve vs 8h auto-accept on same report (zero duplicate awards / credits, expired report rejects manual).
 *   4. Close round vs approve report race (lock order consistency -> zero deadlocks, conservation held).
 *   5. Report submission vs Close round race (gated actual submit vs close, escrow retention, conservation).
 *   6. Batch closing worker vs Campaign lifecycle close race (zero 40P01 deadlocks).
 *   7. Resubmit rejected report under active round (preserves snapshot = null, no slot stealing).
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {
  makeBonusConcurrencyFixture,
  prepareBonusConcurrencyFixture,
  cleanupBonusConcurrencyFixture,
  makePhotoUrl,
  makeThumbUrl,
} from './campaign-tester-bonus-concurrency-fixture.mjs';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (!databaseUrl) {
  console.log('UNRUN/SKIPPED: TEST_DATABASE_URL is absent; real PostgreSQL concurrency was not executed.');
  process.exit(0);
}

// Security & isolation validation: strictly verify test database
try {
  const parsed = new URL(databaseUrl);
  const host = parsed.hostname.toLowerCase();
  const dbName = parsed.pathname.toLowerCase();

  const isLocalHost = host === 'localhost' || host === '127.0.0.1' || host === '::1';
  const hasTestInName = dbName.includes('test') || parsed.username.toLowerCase().includes('test');

  if (!isLocalHost && !hasTestInName) {
    console.error('REFUSED: TEST_DATABASE_URL must target a local host or explicit test database.');
    process.exit(1);
  }

  const forbiddenDomains = ['supabase.co', 'amazonaws.com', 'azure.com', 'neon.tech', 'cockroachlabs.cloud'];
  if (forbiddenDomains.some(d => host.endsWith(d))) {
    console.error(`REFUSED: TEST_DATABASE_URL points to remote cloud domain (${host}), which is strictly forbidden.`);
    process.exit(1);
  }
} catch (e) {
  console.error('REFUSED: Invalid TEST_DATABASE_URL format:', e.message);
  process.exit(1);
}

const { Pool } = await import('pg');
const pool = new Pool({
  connectionString: databaseUrl,
  max: 6,
  connectionTimeoutMillis: 10000,
  statement_timeout: 10000,
  query_timeout: 15000,
});

async function withClient(fn) {
  const client = await pool.connect();
  try {
    return await fn(client);
  } finally {
    client.release();
  }
}

async function asUser(client, userId, fn) {
  await client.query('begin');
  try {
    await client.query("select set_config('request.jwt.claim.sub', $1, true)", [userId]);
    await client.query('set local role authenticated');
    const res = await fn(client);
    await client.query('commit');
    return res;
  } catch (error) {
    await client.query('rollback');
    throw error;
  }
}

async function runScenario(scenarioName, scenarioFn) {
  console.log(`\n--- ${scenarioName} ---`);
  const fixture = makeBonusConcurrencyFixture();
  await withClient(async client => {
    await client.query('begin');
    try {
      await prepareBonusConcurrencyFixture(client, fixture);
      await client.query('commit');
    } catch (e) {
      await client.query('rollback');
      throw e;
    }
  });

  try {
    await scenarioFn(fixture);
    console.log(`PASS: ${scenarioName}`);
  } finally {
    try {
      await withClient(client => cleanupBonusConcurrencyFixture(client, fixture));
    } catch (e) {
      console.error(`Cleanup error in ${scenarioName}:`, e);
    }
  }
}

try {
  // --------------------------------------------------------------------------
  // SCENARIO 1: Two apps of same owner reserving wallet concurrently
  // --------------------------------------------------------------------------
  await runScenario('SCENARIO 1: Two apps of same owner reserving wallet concurrently', async fixture => {
    // Owner starts with 150 credits.
    // App 1 requests 10 * 10 = 100 credits.
    // App 2 requests 10 * 10 = 100 credits.
    // Total 200 credits > 150 credits. Exactly 1 must succeed, 1 must fail with INSUFFICIENT_CREDITS.
    const req1 = crypto.randomUUID();
    const req2 = crypto.randomUUID();

    const reserveResults = await Promise.allSettled([
      withClient(client => asUser(client, fixture.ownerId, async c => {
        const res = await c.query(
          `select public.create_campaign_tester_bonus(
            p_campaign_id => $1,
            p_reward_credits => 10,
            p_max_recipients => 10,
            p_client_request_id => $2
          ) as r`,
          [fixture.camp1Id, req1]
        );
        return res.rows[0].r;
      })),
      withClient(client => asUser(client, fixture.ownerId, async c => {
        const res = await c.query(
          `select public.create_campaign_tester_bonus(
            p_campaign_id => $1,
            p_reward_credits => 10,
            p_max_recipients => 10,
            p_client_request_id => $2
          ) as r`,
          [fixture.camp2Id, req2]
        );
        return res.rows[0].r;
      })),
    ]);

    const reserveWon = reserveResults.filter(r => r.status === 'fulfilled');
    const reserveLost = reserveResults.filter(r => r.status === 'rejected');

    assert.equal(reserveWon.length, 1, 'Exactly one concurrent creation must succeed when budget is tight');
    assert.equal(reserveLost.length, 1, 'Exactly one concurrent creation must fail due to insufficient funds');
    assert.match(reserveLost[0].reason.message, /INSUFFICIENT_CREDITS/);

    await withClient(async client => {
      const { rows: [p] } = await client.query('select credits from public.profiles where id = $1', [fixture.ownerId]);
      assert.equal(p.credits, 50, 'Owner wallet balance must be exactly 50 (150 - 100), zero overdraft');
    });
  });

  // --------------------------------------------------------------------------
  // SCENARIO 2: Last slot race (1 slot, 2 concurrent approvals)
  // --------------------------------------------------------------------------
  await runScenario('SCENARIO 2: Last slot race (1 slot, 2 concurrent approvals)', async fixture => {
    // Top up owner credits by 50 to create a 1-slot round on camp1 (reward 10 * 1 = 10)
    await withClient(client => client.query('update public.profiles set credits = 200 where id = $1', [fixture.ownerId]));
    const roundCamp1 = await withClient(async client => {
      return (await asUser(client, fixture.ownerId, async c => {
        return (await c.query(
          `select public.create_campaign_tester_bonus(
            p_campaign_id => $1,
            p_reward_credits => 10,
            p_max_recipients => 1,
            p_client_request_id => $2
          ) as r`,
          [fixture.camp1Id, crypto.randomUUID()]
        )).rows[0].r;
      }));
    });
    assert.equal(roundCamp1.remaining_slots, 1);

    // Both tester1 and tester2 submit reports on camp1 while round is active
    const rep1Camp1 = await withClient(client => asUser(client, fixture.tester1Id, async c => {
      return (await c.query(
        `select public.submit_tester_report_v2(
          p_submission_context => 'app_test',
          p_campaign_id => $1::uuid,
          p_content => 'Tester 1 slot race',
          p_screenshot_url => $3,
          p_rating => 5,
          p_image_hash => '0123456789abcdef',
          p_client_submit_id => $2::uuid,
          p_thumbnail_url => $4
        ) as r`,
        [
          fixture.camp1Id,
          fixture.report1Id,
          makePhotoUrl(fixture.camp1Id, fixture.tester1Id, 'slot1'),
          makeThumbUrl(fixture.camp1Id, fixture.tester1Id, 'slot1'),
        ]
      )).rows[0].r;
    }));

    const rep2Camp1 = await withClient(client => asUser(client, fixture.tester2Id, async c => {
      return (await c.query(
        `select public.submit_tester_report_v2(
          p_submission_context => 'app_test',
          p_campaign_id => $1::uuid,
          p_content => 'Tester 2 slot race',
          p_screenshot_url => $3,
          p_rating => 5,
          p_image_hash => '0123456789abcdef',
          p_client_submit_id => $2::uuid,
          p_thumbnail_url => $4
        ) as r`,
        [
          fixture.camp1Id,
          fixture.report2Id,
          makePhotoUrl(fixture.camp1Id, fixture.tester2Id, 'slot2'),
          makeThumbUrl(fixture.camp1Id, fixture.tester2Id, 'slot2'),
        ]
      )).rows[0].r;
    }));

    assert.equal(rep1Camp1.tester_bonus_round_id, roundCamp1.round_id);
    assert.equal(rep2Camp1.tester_bonus_round_id, roundCamp1.round_id);

    // Concurrently approve rep1 and rep2
    const slotRaceResults = await Promise.allSettled([
      withClient(client => asUser(client, fixture.ownerId, async c => {
        return (await c.query('select public.approve_tester_report($1, 0) as r', [rep1Camp1.report_id])).rows[0].r;
      })),
      withClient(client => asUser(client, fixture.ownerId, async c => {
        return (await c.query('select public.approve_tester_report($1, 0) as r', [rep2Camp1.report_id])).rows[0].r;
      })),
    ]);

    assert.equal(slotRaceResults.filter(r => r.status === 'fulfilled').length, 2, 'Both approvals must succeed without deadlock');

    const awards = slotRaceResults.map(r => r.value.tester_bonus_awarded);
    assert.ok(awards.includes(10), 'One tester must win the 10 credit bonus');
    assert.ok(awards.includes(0), 'One tester must receive 0 bonus due to capacity exhaustion');

    // Verify round state
    await withClient(async client => {
      const { rows: [rd] } = await client.query('select * from public.campaign_tester_bonus_rounds where id = $1', [roundCamp1.round_id]);
      assert.equal(rd.paid_count, 1);
      assert.equal(rd.max_recipients - rd.paid_count, 0);
      assert.equal(rd.status, 'exhausted');
      assert.equal(rd.spent_credits, 10);
      assert.equal(rd.reserved_credits, rd.spent_credits + rd.remaining_credits + rd.refunded_credits);
    });

    // Verify duplicate approval is rejected
    await withClient(async client => {
      await assert.rejects(
        () => asUser(client, fixture.ownerId, async c => {
          await c.query('select public.approve_tester_report($1, 0)', [rep1Camp1.report_id]);
        }),
        /REPORT_ALREADY_APPROVED|REPORT_ALREADY_REVIEWED/
      );
    });
  });

  // --------------------------------------------------------------------------
  // SCENARIO 3: Manual approve vs 8h auto-accept on the SAME report
  // --------------------------------------------------------------------------
  await runScenario('SCENARIO 3: Manual approve vs 8h auto-accept race on same report', async fixture => {
    // Top up owner credits and create 1-slot round on camp1
    await withClient(client => client.query('update public.profiles set credits = 100 where id = $1', [fixture.ownerId]));
    const roundS3 = await withClient(client => asUser(client, fixture.ownerId, async c => {
      return (await c.query(
        `select public.create_campaign_tester_bonus(
          p_campaign_id => $1,
          p_reward_credits => 10,
          p_max_recipients => 1,
          p_client_request_id => $2
        ) as r`,
        [fixture.camp1Id, crypto.randomUUID()]
      )).rows[0].r;
    }));

    // Tester 3 submits report on camp1
    const rep3 = await withClient(client => asUser(client, fixture.tester3Id, async c => {
      return (await c.query(
        `select public.submit_tester_report_v2(
          p_submission_context => 'app_test',
          p_campaign_id => $1::uuid,
          p_content => 'Tester 3 race report',
          p_screenshot_url => $3,
          p_rating => 5,
          p_image_hash => '0123456789abcdef',
          p_client_submit_id => $2::uuid,
          p_thumbnail_url => $4
        ) as r`,
        [
          fixture.camp1Id,
          fixture.report3Id,
          makePhotoUrl(fixture.camp1Id, fixture.tester3Id, 'race3'),
          makeThumbUrl(fixture.camp1Id, fixture.tester3Id, 'race3'),
        ]
      )).rows[0].r;
    }));
    assert.equal(rep3.tester_bonus_round_id, roundS3.round_id);

    // Backdate report created_at to 9 hours ago so auto-accept considers it expired (> 8h deadline)
    await withClient(client => client.query(
      "update public.tester_reports set created_at = now() - interval '9 hours' where id = $1",
      [rep3.report_id]
    ));

    // Run manual approve and auto_accept_expired_reports concurrently
    const raceAutoResults = await Promise.allSettled([
      withClient(client => asUser(client, fixture.ownerId, async c => {
        return (await c.query('select public.approve_tester_report($1, 0) as r', [rep3.report_id])).rows[0].r;
      })),
      withClient(client => client.query('select public.auto_accept_expired_reports() as r')),
    ]);

    // Auto-accept RPC must fulfill
    assert.equal(raceAutoResults[1].status, 'fulfilled', 'auto_accept_expired_reports must fulfill');

    // Manual approve on an expired report (>8h) must NOT legitimately succeed; must reject with REPORT_EXPIRED or REPORT_ALREADY_REVIEWED
    assert.equal(raceAutoResults[0].status, 'rejected', 'Manual approve on 9h expired report must reject');
    assert.match(
      raceAutoResults[0].reason.message,
      /REPORT_EXPIRED|REPORT_ALREADY_REVIEWED|REPORT_ALREADY_APPROVED/
    );

    // Run another auto_accept sweep in case the concurrent manual query held a brief row lock (SKIP LOCKED)
    await withClient(client => client.query('select public.auto_accept_expired_reports()'));

    // Assertions: exactly ONE bonus award, exactly ONE payout, tester wallet exactly 30
    await withClient(async client => {
      const { rows: [awardRow] } = await client.query(
        'select count(*)::int as c, coalesce(sum(amount), 0)::int as sum from public.campaign_tester_bonus_awards where report_id = $1',
        [rep3.report_id]
      );
      assert.equal(awardRow.c, 1, 'Exactly 1 award must be granted, no duplicate awards');
      assert.equal(awardRow.sum, 10, 'Bonus award must be 10 credits');

      const { rows: [repRow] } = await client.query(
        `select r.status,
          (select count(*)::int from public.report_review_events e
           where e.report_id=r.id and e.event_type='auto_accept') as auto_review_count,
          (select count(*)::int from public.report_review_events e
           where e.report_id=r.id and e.event_type='approve') as manual_review_count
         from public.tester_reports r where r.id=$1`,
        [rep3.report_id]
      );
      assert.equal(repRow.status, 'approved');
      assert.equal(repRow.auto_review_count, 1, 'Exactly one automatic review event must record settlement');
      assert.equal(repRow.manual_review_count, 0, 'An expired report must not receive a manual review award');

      const { rows: [t3Profile] } = await client.query(
        'select credits from public.profiles where id = $1',
        [fixture.tester3Id]
      );
      // Initial 10 + provisional 10 + auto-accept bonus 0 (no manual +2) + extra bonus 10 = exactly 30
      assert.equal(t3Profile.credits, 30, 'Tester wallet must be exactly 30 credits (no manual review +2 bonus on auto-accept)');
    });
  });

  // --------------------------------------------------------------------------
  // SCENARIO 4: Close round vs approve report race
  // --------------------------------------------------------------------------
  await runScenario('SCENARIO 4: Actual pending report approval vs close round race', async fixture => {
    // Dedicated round with 2 slots @ 15 credits = 30 credits reserved
    await withClient(client => client.query('update public.profiles set credits = 100 where id = $1', [fixture.ownerId]));
    const roundS4 = await withClient(client => asUser(client, fixture.ownerId, async c => {
      return (await c.query(
        `select public.create_campaign_tester_bonus(
          p_campaign_id => $1,
          p_reward_credits => 15,
          p_max_recipients => 2,
          p_client_request_id => $2
        ) as r`,
        [fixture.camp1Id, crypto.randomUUID()]
      )).rows[0].r;
    }));

    // Tester 1 submits report on camp1 -> status is 'pending' with round snapshot attached
    const s4ReportId = crypto.randomUUID();
    const repS4 = await withClient(client => asUser(client, fixture.tester1Id, async c => {
      return (await c.query(
        `select public.submit_tester_report_v2(
          p_submission_context => 'app_test',
          p_campaign_id => $1::uuid,
          p_content => 'Pending report for close race',
          p_screenshot_url => $3,
          p_rating => 5,
          p_image_hash => '0123456789abcdef',
          p_client_submit_id => $2::uuid,
          p_thumbnail_url => $4
        ) as r`,
        [
          fixture.camp1Id,
          s4ReportId,
          makePhotoUrl(fixture.camp1Id, fixture.tester1Id, 's4_rep'),
          makeThumbUrl(fixture.camp1Id, fixture.tester1Id, 's4_rep'),
        ]
      )).rows[0].r;
    }));
    assert.equal(repS4.tester_bonus_round_id, roundS4.round_id, 'Report must attach round snapshot');

    // Concurrently run: owner closes round AND owner approves pending report
    const s4RaceResults = await Promise.allSettled([
      withClient(client => asUser(client, fixture.ownerId, async c => {
        return (await c.query('select public.close_campaign_tester_bonus($1) as r', [fixture.camp1Id])).rows[0].r;
      })),
      withClient(client => asUser(client, fixture.ownerId, async c => {
        return (await c.query('select public.approve_tester_report($1, 0) as r', [repS4.report_id])).rows[0].r;
      })),
    ]);

    // Both operations must finish without deadlock (40P01)
    assert.equal(s4RaceResults[0].status, 'fulfilled', 'Close round must succeed');
    assert.equal(s4RaceResults[1].status, 'fulfilled', 'Approve report must succeed');

    // Verify pending obligation was paid exactly once
    await withClient(async client => {
      const { rows: [awardCount] } = await client.query(
        'select count(*)::int as c, coalesce(sum(amount), 0)::int as sum from public.campaign_tester_bonus_awards where report_id = $1',
        [repS4.report_id]
      );
      assert.equal(awardCount.c, 1, 'Exactly 1 award must be granted');
      assert.equal(awardCount.sum, 15, 'Award amount must be 15 credits');

      // Round check: status must be closed or closing, strict conservation holds
      const { rows: [rd] } = await client.query('select * from public.campaign_tester_bonus_rounds where id = $1', [roundS4.round_id]);
      assert.ok(['closed', 'closing'].includes(rd.status));
      assert.equal(rd.reserved_credits, rd.spent_credits + rd.remaining_credits + rd.refunded_credits);

      // If round transitioned to closing because pending report was unresolved at close time,
      // now that report is approved (0 pending remain), running close again must cleanly refund unused 15 credits
      if (rd.status === 'closing') {
        const res = await asUser(client, fixture.ownerId, async c => {
          return (await c.query('select public.close_campaign_tester_bonus($1) as r', [fixture.camp1Id])).rows[0].r;
        });
        assert.equal(res.closed, true);
      }

      const { rows: [rdFinal] } = await client.query('select * from public.campaign_tester_bonus_rounds where id = $1', [roundS4.round_id]);
      assert.equal(rdFinal.status, 'closed');
      assert.equal(rdFinal.spent_credits, 15);
      assert.equal(rdFinal.refunded_credits, 15);
      assert.equal(rdFinal.reserved_credits, 30);
    });
  });

  // --------------------------------------------------------------------------
  // SCENARIO 5: Report submission vs Close round race (linearization & snapshot)
  // --------------------------------------------------------------------------
  await runScenario('SCENARIO 5: Report submission vs Close round race (gated actual submit vs close)', async fixture => {
    // 1 slot @ 15 credits = 15 credits reserved
    await withClient(client => client.query('update public.profiles set credits = 100 where id = $1', [fixture.ownerId]));
    const roundS5 = await withClient(client => asUser(client, fixture.ownerId, async c => {
      return (await c.query(
        `select public.create_campaign_tester_bonus(
          p_campaign_id => $1,
          p_reward_credits => 15,
          p_max_recipients => 1,
          p_client_request_id => $2
        ) as r`,
        [fixture.camp1Id, crypto.randomUUID()]
      )).rows[0].r;
    }));

    const s5SubId = crypto.randomUUID();
    const subVsCloseResults = await Promise.allSettled([
      withClient(client => asUser(client, fixture.tester2Id, async c => {
        return (await c.query(
          `select public.submit_tester_report_v2(
            p_submission_context => 'app_test',
            p_campaign_id => $1::uuid,
            p_content => 'Race submission vs close',
            p_screenshot_url => $3,
            p_rating => 5,
            p_image_hash => '0123456789abcdef',
            p_client_submit_id => $2::uuid,
            p_thumbnail_url => $4
          ) as r`,
          [
            fixture.camp1Id,
            s5SubId,
            makePhotoUrl(fixture.camp1Id, fixture.tester2Id, 's5_sub_close'),
            makeThumbUrl(fixture.camp1Id, fixture.tester2Id, 's5_sub_close'),
          ]
        )).rows[0].r;
      })),
      withClient(client => asUser(client, fixture.ownerId, async c => {
        return (await c.query('select public.close_campaign_tester_bonus($1) as r', [fixture.camp1Id])).rows[0].r;
      })),
    ]);

    assert.equal(subVsCloseResults[0].status, 'fulfilled', 'Submission must succeed without deadlock');
    assert.equal(subVsCloseResults[1].status, 'fulfilled', 'Close must succeed without deadlock');

    const subReport = subVsCloseResults[0].value;
    await withClient(async client => {
      const { rows: [rd] } = await client.query('select * from public.campaign_tester_bonus_rounds where id = $1', [roundS5.round_id]);
      assert.equal(rd.reserved_credits, rd.spent_credits + rd.remaining_credits + rd.refunded_credits);

      if (subReport.tester_bonus_round_id !== null) {
        // Snapshot was attached before close finalized! Escrow retained in closing status.
        assert.equal(rd.status, 'closing', 'Round must stay closing while pending report holds snapshot');
        // Approve the report -> awards 15 credits
        const appRes = await asUser(client, fixture.ownerId, async c => {
          return (await c.query('select public.approve_tester_report($1, 0) as r', [subReport.report_id])).rows[0].r;
        });
        assert.equal(appRes.tester_bonus_awarded, 15, 'Attached report must receive bonus');
        // Now close round completely
        await client.query('select public._process_closing_tester_bonus_rounds()');
        const { rows: [rdAfter] } = await client.query('select * from public.campaign_tester_bonus_rounds where id = $1', [roundS5.round_id]);
        assert.equal(rdAfter.status, 'closed');
        assert.equal(rdAfter.spent_credits, 15);
        assert.equal(rdAfter.refunded_credits, 0);
      } else {
        // Close finalized before snapshot could attach -> round is closed with 15 credits refunded
        assert.equal(rd.status, 'closed');
        assert.equal(rd.refunded_credits, 15);
        // Approving report yields 0 bonus
        const appRes = await asUser(client, fixture.ownerId, async c => {
          return (await c.query('select public.approve_tester_report($1, 0) as r', [subReport.report_id])).rows[0].r;
        });
        assert.equal(appRes.tester_bonus_awarded, 0, 'No bonus when round was closed before submission');
      }
    });
  });

  // --------------------------------------------------------------------------
  // SCENARIO 6: Batch closing worker vs Campaign lifecycle close race
  // --------------------------------------------------------------------------
  await runScenario('SCENARIO 6: Batch closing worker vs Campaign lifecycle close race', async fixture => {
    // 1 slot @ 10 credits
    await withClient(client => client.query('update public.profiles set credits = 100 where id = $1', [fixture.ownerId]));
    const roundS6 = await withClient(client => asUser(client, fixture.ownerId, async c => {
      return (await c.query(
        `select public.create_campaign_tester_bonus(
          p_campaign_id => $1,
          p_reward_credits => 10,
          p_max_recipients => 1,
          p_client_request_id => $2
        ) as r`,
        [fixture.camp1Id, crypto.randomUUID()]
      )).rows[0].r;
    }));

    // Transition round to closing status so worker has work to do
    await withClient(async client => {
      await client.query(
        "update public.campaign_tester_bonus_rounds set status = 'closing' where id = $1",
        [roundS6.round_id]
      );
    });

    const batchVsLifeResults = await Promise.allSettled([
      withClient(client => client.query('select public._process_closing_tester_bonus_rounds() as r')),
      withClient(client => client.query(
        "update public.campaigns set status = 'completed' where id = $1 returning id",
        [fixture.camp1Id]
      )),
    ]);

    assert.equal(batchVsLifeResults[0].status, 'fulfilled', 'Batch worker must succeed without 40P01 deadlock');
    assert.equal(batchVsLifeResults[1].status, 'fulfilled', 'Campaign update must succeed without 40P01 deadlock');

    // Verify round is closed and unused budget refunded
    await withClient(async client => {
      const { rows: [rd] } = await client.query('select * from public.campaign_tester_bonus_rounds where id = $1', [roundS6.round_id]);
      assert.equal(rd.status, 'closed');
      assert.equal(rd.refunded_credits, 10);
      assert.equal(rd.reserved_credits, rd.spent_credits + rd.remaining_credits + rd.refunded_credits);
    });
  });

  // --------------------------------------------------------------------------
  // SCENARIO 7: Resubmit rejected report under active round preserves snapshot
  // --------------------------------------------------------------------------
  await runScenario('SCENARIO 7: Resubmit rejected report under active round preserves snapshot', async fixture => {
    // Dedicated fresh fixture: report submitted before round must preserve snapshot = null on resubmission
    const s7RepId = crypto.randomUUID();

    // Tester 1 submits report before bonus round exists
    const preRoundReport = await withClient(client => asUser(client, fixture.tester1Id, async c => {
      return (await c.query(
        `select public.submit_tester_report_v2(
          p_submission_context => 'app_test',
          p_campaign_id => $1::uuid,
          p_content => 'Pre-round submission',
          p_screenshot_url => $3,
          p_rating => 5,
          p_image_hash => '0123456789abcdef',
          p_client_submit_id => $2::uuid,
          p_thumbnail_url => $4
        ) as r`,
        [
          fixture.camp1Id,
          s7RepId,
          makePhotoUrl(fixture.camp1Id, fixture.tester1Id, 's7_pre_round'),
          makeThumbUrl(fixture.camp1Id, fixture.tester1Id, 's7_pre_round'),
        ]
      )).rows[0].r;
    }));
    assert.equal(preRoundReport.tester_bonus_round_id, null, 'Pre-round report must have null snapshot');

    // Owner rejects the report
    await withClient(client => client.query(
      "update public.tester_reports set status = 'rejected', rejection_reason = 'Needs clearer screenshot' where id = $1",
      [preRoundReport.report_id]
    ));

    // Now owner creates a bonus round on camp1
    await withClient(client => client.query('update public.profiles set credits = 100 where id = $1', [fixture.ownerId]));
    const roundS7 = await withClient(client => asUser(client, fixture.ownerId, async c => {
      return (await c.query(
        `select public.create_campaign_tester_bonus(
          p_campaign_id => $1,
          p_reward_credits => 20,
          p_max_recipients => 1,
          p_client_request_id => $2
        ) as r`,
        [fixture.camp1Id, crypto.randomUUID()]
      )).rows[0].r;
    }));

    // Tester 1 resubmits the rejected report (with new photo URL, same campaign, new client submit id)
    const resubmitReport = await withClient(client => asUser(client, fixture.tester1Id, async c => {
      return (await c.query(
        `select public.submit_tester_report_v2(
          p_submission_context => 'app_test',
          p_campaign_id => $1::uuid,
          p_content => 'Resubmitted with clearer photo',
          p_screenshot_url => $3,
          p_rating => 5,
          p_image_hash => 'fedcba9876543210',
          p_client_submit_id => $2::uuid,
          p_thumbnail_url => $4
        ) as r`,
        [
          fixture.camp1Id,
          crypto.randomUUID(),
          makePhotoUrl(fixture.camp1Id, fixture.tester1Id, 's7_resubmit_photo'),
          makeThumbUrl(fixture.camp1Id, fixture.tester1Id, 's7_resubmit_photo'),
        ]
      )).rows[0].r;
    }));

    // Resubmitted report must preserve its original snapshot (null) and NOT steal slots from the new round
    assert.equal(resubmitReport.tester_bonus_round_id, null, 'Resubmitted report must preserve original snapshot null');

    // Verify round still has 1 remaining slot
    await withClient(async client => {
      const { rows: [rd] } = await client.query('select * from public.campaign_tester_bonus_rounds where id = $1', [roundS7.round_id]);
      assert.equal(rd.paid_count, 0);
      assert.equal(rd.max_recipients - rd.paid_count, 1, 'Round must still have 1 remaining slot');
    });
  });

  console.log('\n======================================================');
  console.log('ALL REAL POSTGRESQL CONCURRENCY SCENARIOS PASSED!');
  console.log('======================================================\n');
} finally {
  await pool.end();
}
