import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const db = await createDb();

// Identities
const owner = 'c3000000-0000-0000-0000-000000000001';
const owner2 = 'c3000000-0000-0000-0000-000000000002';
const tester1 = 'a1000000-0000-0000-0000-000000000001';
const tester2 = 'a1000000-0000-0000-0000-000000000002';
const tester3 = 'a1000000-0000-0000-0000-000000000003';
const admin = 'd4000000-0000-0000-0000-000000000001';

// Campaigns
const camp1 = 'b1000000-0000-0000-0000-000000000001';
const camp2 = 'b2000000-0000-0000-0000-000000000002';
const campOther = 'b3000000-0000-0000-0000-000000000003';

async function asUser(uid, fn) {
  await db.exec('begin');
  try {
    if (uid) {
      await db.exec(`
        set local "request.jwt.claim.sub" = '${uid}';
        set local "request.jwt.claims" = '{"sub": "${uid}", "role": "authenticated"}';
        set local role = 'authenticated';
      `);
    } else {
      await db.exec(`
        set local "request.jwt.claim.sub" = '';
        set local "request.jwt.claims" = '{"role": "anon"}';
        set local role = 'anon';
      `);
    }
    const result = await fn();
    return result;
  } finally {
    await db.exec('rollback');
  }
}

// Session execution helper (persisting commits across steps)
async function inSession(uid, fn) {
  if (uid) {
    await db.exec(`
      set "request.jwt.claim.sub" = '${uid}';
      set "request.jwt.claims" = '{"sub": "${uid}", "role": "authenticated"}';
      set role = 'authenticated';
    `);
  } else {
    await db.exec(`
      set "request.jwt.claim.sub" = '';
      set "request.jwt.claims" = '{"role": "anon"}';
      set role = 'anon';
    `);
  }
  try {
    return await fn();
  } finally {
    await db.exec(`
      reset role;
      set "request.jwt.claim.sub" = '';
      set "request.jwt.claims" = '{}';
    `);
  }
}

function makePhotoUrl(campId, userId, name = 'photo') {
  return `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${userId}%2F${name}.jpg?alt=media&token=fixture`;
}
function makeThumbUrl(campId, userId, name = 'photo') {
  return `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${userId}%2Fthumb_${name}.jpg?alt=media&token=fixture`;
}

console.log('=== LOADING ALL MIGRATIONS IN ORDER (INCLUDING 087) ===');
const migrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n))
  .filter(n=>Number(n.slice(0,3))<=Number(process.env.SUPERVISOR_THROUGH)).sort();
for (const name of migrations) {
  await db.exec(read('supabase/migrations/' + name));
}
console.log(`MIGRATIONS LOADED SUCCESSFULLY (${migrations.length} migrations).`);

// ============================================================================
// BASE FIXTURES SEEDING
// ============================================================================
await db.exec(`
  insert into auth.users (id, email) values
    ('${owner}', 'owner@test.local'),
    ('${owner2}', 'owner2@test.local'),
    ('${tester1}', 'tester1@test.local'),
    ('${tester2}', 'tester2@test.local'),
    ('${tester3}', 'tester3@test.local'),
    ('${admin}', 'admin@test.local')
  on conflict (id) do nothing;

  update public.profiles set
    display_name = 'Owner 1',
    credits = 1000,
    role = 'member',
    is_locked = false
  where id = '${owner}';

  update public.profiles set
    display_name = 'Owner 2',
    credits = 500,
    role = 'member',
    is_locked = false
  where id = '${owner2}';

  update public.profiles set
    display_name = 'Tester 1',
    credits = 100,
    role = 'member',
    is_locked = false
  where id = '${tester1}';

  update public.profiles set
    display_name = 'Tester 2',
    credits = 100,
    role = 'member',
    is_locked = false
  where id = '${tester2}';

  update public.profiles set
    display_name = 'Tester 3',
    credits = 100,
    role = 'member',
    is_locked = false
  where id = '${tester3}';

  update public.profiles set
    display_name = 'Admin User',
    credits = 5000,
    role = 'admin',
    is_locked = false
  where id = '${admin}';

  -- User slots for owner
  insert into public.user_slots (user_id, slot_number, expires_at) values
    ('${owner}', 2, now() + interval '30 days')
  on conflict (user_id, slot_number) do nothing;

  -- Campaigns
  insert into public.campaigns (
    id, owner_id, app_name, package_name, play_url, status, target_testers, target_days, start_date, slot_number
  ) values
    ('${camp1}', '${owner}', 'App One Bonus', 'com.test.bonusone', 'https://play.google.com/test1', 'open', 12, 14, (now() at time zone 'Asia/Ho_Chi_Minh')::date, 1),
    ('${camp2}', '${owner}', 'App Two Bonus', 'com.test.bonustwo', 'https://play.google.com/test2', 'open', 12, 14, (now() at time zone 'Asia/Ho_Chi_Minh')::date, 2),
    ('${campOther}', '${owner2}', 'Other Owner App', 'com.test.other', 'https://play.google.com/other', 'open', 12, 14, (now() at time zone 'Asia/Ho_Chi_Minh')::date, 1)
  on conflict (id) do nothing;
`);

console.log('=== BASE FIXTURES SEEDED ===');

// ============================================================================
// TEST 1: Exact Balance, Insufficient Balance, Negative Inputs & Overflow Check
// ============================================================================
console.log('--- TEST 1: Validation, Balance Bounds & Overflow Prevention ---');

// 1.1 Negative / Zero Inputs
await assert.rejects(
  () => inSession(owner, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 0, 10, 'e1000000-0000-0000-0000-000000000001')`);
  }),
  /INVALID_INPUT/,
  'Zero reward must be rejected'
);

await assert.rejects(
  () => inSession(owner, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 5, -1, 'e1000000-0000-0000-0000-000000000002')`);
  }),
  /INVALID_INPUT/,
  'Negative max_recipients must be rejected'
);

// 1.2 Integer multiplication overflow (e.g. 500,000 * 5,000 > 2^31 - 1)
await assert.rejects(
  () => inSession(owner, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 1000000, 3000, 'e1000000-0000-0000-0000-000000000003')`);
  }),
  /INPUT_OVERFLOW/,
  'Multiplication overflow beyond integer max must be safely rejected'
);

// 1.3 Insufficient Credits (Owner has 1000 credits, needs 1001 credits)
await assert.rejects(
  () => inSession(owner, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 1001, 1, 'e1000000-0000-0000-0000-000000000004')`);
  }),
  /INSUFFICIENT_CREDITS/,
  'Insufficient credits must be rejected'
);

console.log('PASS: Input validation & overflow protection verified.');

// ============================================================================
// TEST 2: Authorization & Ownership Guards
// ============================================================================
console.log('--- TEST 2: Authorization & Ownership Guards ---');

// 2.1 Non-owner cannot create bonus round on someone else's campaign
await assert.rejects(
  () => inSession(owner2, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 5, 20, 'e1000000-0000-0000-0000-000000000005')`);
  }),
  /PERMISSION_DENIED/,
  'Non-owner must not create bonus on another owners app'
);

// 2.2 Admin who is not actual owner cannot spend owner credits through create
await assert.rejects(
  () => inSession(admin, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 5, 20, 'e1000000-0000-0000-0000-000000000006')`);
  }),
  /PERMISSION_DENIED/,
  'Admin cannot spend another owners credits'
);

console.log('PASS: Ownership & authorization guards verified.');

// ============================================================================
// TEST 3: Successful Creation, Atomic Reserve & Idempotency
// ============================================================================
console.log('--- TEST 3: Creation, Atomic Budget Reserve & Idempotency ---');

const reqId1 = 'f1000000-0000-0000-0000-000000000001';
// Reward: 5, Recipients: 20 -> Budget: 100 Credits.
// Owner initial balance: 1000 -> Expected after: 900.
const createRes = await inSession(owner, async () => {
  const { rows } = await db.query(
    `select public.create_campaign_tester_bonus('${camp1}', 5, 20, '${reqId1}') as res`
  );
  return rows[0].res;
});

assert.equal(createRes.success, true);
assert.equal(createRes.replayed, false);
assert.equal(createRes.reward_credits, 5);
assert.equal(createRes.max_recipients, 20);
assert.equal(createRes.remaining_slots, 20);
assert.equal(createRes.reserved_credits, 100);
assert.equal(createRes.remaining_credits, 100);
assert.equal(createRes.spent_credits, 0);
assert.equal(createRes.refunded_credits, 0);
assert.equal(createRes.status, 'active');
assert.equal(createRes.owner_balance, 900);

const round1Id = createRes.round_id;

// Check owner profile balance
const { rows: [ownerProf] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
assert.equal(ownerProf.credits, 900);

// Check credit logs
const { rows: [logRow] } = await db.query(
  `select * from public.credit_logs where user_id = '${owner}' and action = 'tester_bonus_reserve'`
);
assert.equal(logRow.amount, -100);
assert.equal(logRow.balance, 900);

// Check campaigns summary columns
const { rows: [campRow] } = await db.query(`select * from public.campaigns where id = '${camp1}'`);
assert.equal(campRow.tester_bonus_round_id, round1Id);
assert.equal(campRow.tester_bonus_credits, 5);
assert.equal(campRow.tester_bonus_remaining_slots, 20);
assert.equal(campRow.tester_bonus_paid_count, 0);
assert.equal(campRow.tester_bonus_status, 'active');

// 3.2 Idempotent Replay with exact same request ID and payload
const replayRes = await inSession(owner, async () => {
  const { rows } = await db.query(
    `select public.create_campaign_tester_bonus('${camp1}', 5, 20, '${reqId1}') as res`
  );
  return rows[0].res;
});
assert.equal(replayRes.success, true);
assert.equal(replayRes.replayed, true);
assert.equal(replayRes.round_id, round1Id);
assert.equal(replayRes.owner_balance, 900, 'Replay must not deduct wallet a second time');

// 3.3 Conflict: Same request ID with different payload
await assert.rejects(
  () => inSession(owner, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 10, 10, '${reqId1}')`);
  }),
  /CLIENT_REQUEST_ID_CONFLICT/,
  'Conflicting payload on same request ID must be rejected'
);

// 3.4 Second round on same campaign while active -> rejected
await assert.rejects(
  () => inSession(owner, async () => {
    await db.query(`select public.create_campaign_tester_bonus('${camp1}', 5, 10, 'f1000000-0000-0000-0000-000000000002')`);
  }),
  /ACTIVE_ROUND_ALREADY_EXISTS/,
  'Only one active/closing round per campaign is allowed'
);

console.log('PASS: Successful creation, atomic budget reservation and idempotency verified.');

// ============================================================================
// TEST 4: Reconcile RPC
// ============================================================================
console.log('--- TEST 4: Reconcile RPC ---');

// 4.1 Found existing round
const reconcileFound = await inSession(owner, async () => {
  const { rows } = await db.query(
    `select public.reconcile_campaign_tester_bonus('${camp1}', '${reqId1}') as res`
  );
  return rows[0].res;
});
assert.equal(reconcileFound.found, true);
assert.equal(reconcileFound.replayed, true);
assert.equal(reconcileFound.round_id, round1Id);
assert.equal(reconcileFound.reserved_credits, 100);

// 4.2 Not found request ID -> returns found=false, never creates
const reconcileNotFound = await inSession(owner, async () => {
  const { rows } = await db.query(
    `select public.reconcile_campaign_tester_bonus('${camp1}', '00000000-0000-0000-0000-000000000000') as res`
  );
  return rows[0].res;
});
assert.equal(reconcileNotFound.found, false);
assert.equal(reconcileNotFound.client_request_id, '00000000-0000-0000-0000-000000000000');

console.log('PASS: Reconcile RPC verified.');

// ============================================================================
// TEST 5: Dual Read API (get_campaign_tester_bonus)
// ============================================================================
console.log('--- TEST 5: Dual Read API (Owner vs Tester/Public) ---');

// 5.1 Owner view: contains full financial rows
const ownerView = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.get_campaign_tester_bonus('${camp1}') as res`);
  return rows[0].res;
});
assert.equal(ownerView.has_round, true);
assert.equal(ownerView.is_owner, true);
assert.equal(ownerView.reserved_credits, 100);
assert.equal(ownerView.remaining_credits, 100);
assert.equal(ownerView.spent_credits, 0);
assert.equal(ownerView.refunded_credits, 0);
assert.equal(ownerView.status, 'active');

// 5.2 Tester view: public summary & own status only (no owner wallet or private ledger rows)
const testerView = await inSession(tester1, async () => {
  const { rows } = await db.query(`select public.get_campaign_tester_bonus('${camp1}') as res`);
  return rows[0].res;
});
assert.equal(testerView.has_round, true);
assert.equal(testerView.is_owner, false);
assert.equal(testerView.reward_credits, 5);
assert.equal(testerView.remaining_slots, 20);
assert.equal(testerView.status, 'active');
assert.equal(testerView.my_awarded, false);
assert.equal(testerView.my_pending_eligible, false);
assert.equal(testerView.reserved_credits, undefined, 'Tester view must not leak reserved_credits');
assert.equal(testerView.remaining_credits, undefined, 'Tester view must not leak remaining_credits');

console.log('PASS: Dual read API verified.');

// ============================================================================
// TEST 6: Report Snapshot Attachment Rules & Immutability
// ============================================================================
console.log('--- TEST 6: Report Snapshot Attachment Rules & Immutability ---');

// 6.1 Own-app test report: strictly rejected by baseline CANNOT_TEST_OWN_APP
await assert.rejects(
  () => inSession(owner, async () => {
    return await db.query(`
      select public._submit_tester_report_core(
        'app_test', '${camp1}', 'Owner testing own app', 5,
        'a0000000-0000-0000-0000-000000000001',
        '${makePhotoUrl(camp1, owner, 'owner')}',
        '0123456789abcdef',
        '${makeThumbUrl(camp1, owner, 'owner')}',
        null
      ) as res
    `);
  }),
  /CANNOT_TEST_OWN_APP/,
  'Owner testing own app must be strictly blocked'
);

// 6.2 Legitimate tester report on open app with active round -> attaches bonus snapshot
const submitTester1Res = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp1}', 'Tester 1 report day 1', 5,
      'a0000000-0000-0000-0000-000000000002',
      '${makePhotoUrl(camp1, tester1, 't1_d1')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp1, tester1, 't1_d1')}',
      null
    ) as res
  `);
  return rows[0].res;
});
assert.equal(submitTester1Res.success, true);
assert.equal(submitTester1Res.tester_bonus_round_id, round1Id, 'Legitimate tester report must attach round snapshot');
const rep1Id = submitTester1Res.report_id;

// Check testerView now shows my_pending_eligible = true
const testerViewPending = await inSession(tester1, async () => {
  const { rows } = await db.query(`select public.get_campaign_tester_bonus('${camp1}') as res`);
  return rows[0].res;
});
assert.equal(testerViewPending.my_pending_eligible, true);

// 6.3 Direct client write tamper protection
await assert.rejects(
  () => inSession(tester1, async () => {
    await db.query(`insert into public.tester_reports (
      campaign_id, user_id, report_type, content, status, tester_bonus_round_id
    ) values (
      '${camp1}', '${tester1}', 'feedback', 'tamper', 'pending', '${round1Id}'
    )`);
  }),
  /permission denied|FORBIDDEN_REPORT_METADATA_WRITE/,
  'Direct client insert of tester_bonus_round_id must be rejected'
);

await assert.rejects(
  () => inSession(tester1, async () => {
    await db.query(`update public.tester_reports set tester_bonus_round_id = null where id = '${rep1Id}'`);
  }),
  /permission denied|FORBIDDEN_REPORT_METADATA_UPDATE/,
  'Direct client update of tester_bonus_round_id must be rejected'
);

await assert.rejects(
  () => inSession(owner, async () => {
    await db.query(`update public.campaigns set tester_bonus_remaining_slots = 999 where id = '${camp1}'`);
  }),
  /TESTER_BONUS_SUMMARY_IMMUTABLE/,
  'Direct client update of campaign summary columns must be rejected'
);

console.log('PASS: Report snapshot attachment rules & immutability verified.');

// ============================================================================
// TEST 7: Manual Settlement (approve_tester_report) & Single Award per Tester
// ============================================================================
console.log('--- TEST 7: Manual Settlement & Single Award per Tester ---');

// Tester1 balance before approval: 100 provisional + 10 = 110
const { rows: [t1Before] } = await db.query(`select credits from public.profiles where id = '${tester1}'`);

const approveRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.approve_tester_report('${rep1Id}', 0) as res`);
  return rows[0].res;
});

assert.equal(approveRes.success, true);
assert.equal(approveRes.status, 'approved');
assert.equal(approveRes.tester_bonus_awarded, 5, 'Tester must receive +5 extra bonus');
assert.equal(approveRes.credits_awarded, 2, 'Tester must receive ordinary manual review bonus (+2)');
// Total earned = base 10 + manual 2 + bonus 5 = 17
assert.equal(approveRes.total_earned, 17);

// Check tester balance: 110 + 2 (review bonus) + 5 (tester bonus) = 117
const { rows: [t1After] } = await db.query(`select credits from public.profiles where id = '${tester1}'`);
assert.equal(t1After.credits, t1Before.credits + 2 + 5);

// Check awards ledger
const { rows: [awardRow] } = await db.query(
  `select * from public.campaign_tester_bonus_awards where round_id = '${round1Id}' and tester_id = '${tester1}'`
);
assert.equal(awardRow.amount, 5);
assert.equal(awardRow.award_order, 1);
assert.equal(awardRow.report_id, rep1Id);

// Check round counters
const { rows: [roundAfter1] } = await db.query(
  `select * from public.campaign_tester_bonus_rounds where id = '${round1Id}'`
);
assert.equal(roundAfter1.paid_count, 1);
assert.equal(roundAfter1.spent_credits, 5);
assert.equal(roundAfter1.remaining_credits, 95);
assert.equal(roundAfter1.reserved_credits, 100);
assert.equal(roundAfter1.reserved_credits, roundAfter1.remaining_credits + roundAfter1.spent_credits + roundAfter1.refunded_credits);

// Check campaigns summary counters
const { rows: [campAfter1] } = await db.query(`select * from public.campaigns where id = '${camp1}'`);
assert.equal(campAfter1.tester_bonus_paid_count, 1);
assert.equal(campAfter1.tester_bonus_remaining_slots, 19);

// Check tester view now shows my_awarded = true
const testerViewAwarded = await inSession(tester1, async () => {
  const { rows } = await db.query(`select public.get_campaign_tester_bonus('${camp1}') as res`);
  return rows[0].res;
});
assert.equal(testerViewAwarded.my_awarded, true);
assert.equal(testerViewAwarded.my_award_amount, 5);
assert.equal(testerViewAwarded.remaining_slots, 19);

// 7.2 Same tester submits report on next day for SAME round:
// Must be approved normally with base reward, but NO extra bonus awarded!
await db.exec(`
  update public.tester_reports set report_date = report_date - interval '1 day' where id = '${rep1Id}';
  update public.checkins set checkin_date = checkin_date - interval '1 day' where campaign_id = '${camp1}' and user_id = '${tester1}';
`);

const submitTester1Day2Res = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp1}', 'Tester 1 report day 2', 5,
      'a0000000-0000-0000-0000-000000000003',
      '${makePhotoUrl(camp1, tester1, 't1_d2')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp1, tester1, 't1_d2')}',
      null
    ) as res
  `);
  return rows[0].res;
});
const rep1Day2Id = submitTester1Day2Res.report_id;
assert.equal(submitTester1Day2Res.tester_bonus_round_id, round1Id);

const approveDay2Res = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.approve_tester_report('${rep1Day2Id}', 0) as res`);
  return rows[0].res;
});
assert.equal(approveDay2Res.success, true);
assert.equal(approveDay2Res.credits_awarded, 2);
assert.equal(approveDay2Res.tester_bonus_awarded, 0, 'Same tester cannot receive bonus a 2nd time in same round');

// Paid count remains 1, remaining remains 95
const { rows: [roundAfterDay2] } = await db.query(`select * from public.campaign_tester_bonus_rounds where id = '${round1Id}'`);
assert.equal(roundAfterDay2.paid_count, 1);
assert.equal(roundAfterDay2.remaining_credits, 95);

console.log('PASS: Manual settlement & strict 1 award per tester per round verified.');

// ============================================================================
// TEST 8: 8h Auto-Accept Settlement (auto_accept_expired_reports)
// ============================================================================
console.log('--- TEST 8: 8h Auto-Accept Settlement ---');

// Tester 2 submits report
const submitTester2Res = await inSession(tester2, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp1}', 'Tester 2 report', 5,
      'a0000000-0000-0000-0000-000000000004',
      '${makePhotoUrl(camp1, tester2, 't2_rep')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp1, tester2, 't2_rep')}',
      null
    ) as res
  `);
  return rows[0].res;
});
const rep2Id = submitTester2Res.report_id;
assert.equal(submitTester2Res.tester_bonus_round_id, round1Id);

// Age report by 9 hours to trigger auto-accept
await db.exec(`
  update public.tester_reports
  set created_at = now() - interval '9 hours'
  where id = '${rep2Id}';
`);

const { rows: [t2Before] } = await db.query(`select credits from public.profiles where id = '${tester2}'`);

// Run auto-accept
const autoAcceptCount = (await db.query(`select public.auto_accept_expired_reports() as cnt`)).rows[0].cnt;
assert.ok(autoAcceptCount >= 1);

// Verify report 2 approved and awarded
const { rows: [rep2Row] } = await db.query(`select * from public.tester_reports where id = '${rep2Id}'`);
assert.equal(rep2Row.status, 'approved');
assert.equal(rep2Row.tester_bonus_awarded, 5);

// Verify tester 2 received +5 bonus
const { rows: [t2After] } = await db.query(`select credits from public.profiles where id = '${tester2}'`);
assert.equal(t2After.credits, t2Before.credits + 5);

// Verify round counters updated (paid_count = 2, award_order = 2)
const { rows: [roundAfterAuto] } = await db.query(`select * from public.campaign_tester_bonus_rounds where id = '${round1Id}'`);
assert.equal(roundAfterAuto.paid_count, 2);
assert.equal(roundAfterAuto.spent_credits, 10);
assert.equal(roundAfterAuto.remaining_credits, 90);

console.log('PASS: 8h auto-accept settlement verified.');

// ============================================================================
// TEST 9: Round Capacity Exhaustion & N+1 Base Rewards
// ============================================================================
console.log('--- TEST 9: Capacity Exhaustion & N+1 Base Reward ---');

// Create small round: 10 credits, 2 recipients (Budget = 20) on camp2
const reqId2 = 'f1000000-0000-0000-0000-000000000010';
const createRes2 = await inSession(owner, async () => {
  const { rows } = await db.query(
    `select public.create_campaign_tester_bonus('${camp2}', 10, 2, '${reqId2}') as res`
  );
  return rows[0].res;
});
const round2Id = createRes2.round_id;
assert.equal(createRes2.max_recipients, 2);

// Tester 1 takes slot 1
const subT1Camp2 = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp2}', 'T1 on camp2', 5,
      'b0000000-0000-0000-0000-000000000001',
      '${makePhotoUrl(camp2, tester1, 't1c2')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp2, tester1, 't1c2')}',
      null
    ) as res
  `);
  return rows[0].res;
});
await inSession(owner, () => db.query(`select public.approve_tester_report('${subT1Camp2.report_id}', 0)`));

// Tester 2 takes slot 2 (final slot)
const subT2Camp2 = await inSession(tester2, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp2}', 'T2 on camp2', 5,
      'b0000000-0000-0000-0000-000000000002',
      '${makePhotoUrl(camp2, tester2, 't2c2')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp2, tester2, 't2c2')}',
      null
    ) as res
  `);
  return rows[0].res;
});
await inSession(owner, () => db.query(`select public.approve_tester_report('${subT2Camp2.report_id}', 0)`));

// Check round is now exhausted
const { rows: [round2Exhausted] } = await db.query(`select * from public.campaign_tester_bonus_rounds where id = '${round2Id}'`);
assert.equal(round2Exhausted.paid_count, 2);
assert.equal(round2Exhausted.remaining_credits, 0);
assert.equal(round2Exhausted.spent_credits, 20);
assert.equal(round2Exhausted.status, 'exhausted');

// Check campaigns summary shows remaining_slots = 0, status = 'exhausted'
const { rows: [camp2Exhausted] } = await db.query(`select * from public.campaigns where id = '${camp2}'`);
assert.equal(camp2Exhausted.tester_bonus_remaining_slots, 0);
assert.equal(camp2Exhausted.tester_bonus_status, 'exhausted');

// Tester 3 submits on exhausted round
const subT3Camp2 = await inSession(tester3, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp2}', 'T3 on camp2', 5,
      'b0000000-0000-0000-0000-000000000003',
      '${makePhotoUrl(camp2, tester3, 't3c2')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp2, tester3, 't3c2')}',
      null
    ) as res
  `);
  return rows[0].res;
});
// When round is exhausted, new report does NOT attach round snapshot
assert.equal(subT3Camp2.tester_bonus_round_id, null, 'Exhausted round must not be attached to new report');

// Tester 3 gets approved with ordinary reward
const approveT3Res = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.approve_tester_report('${subT3Camp2.report_id}', 0) as res`);
  return rows[0].res;
});
assert.equal(approveT3Res.success, true);
assert.equal(approveT3Res.credits_awarded, 2);
assert.equal(approveT3Res.tester_bonus_awarded, 0);

console.log('PASS: Capacity exhaustion & N+1 base rewards verified.');

// ============================================================================
// TEST 10: Closing, Escrow Refund & Obligation Preservation
// ============================================================================
console.log('--- TEST 10: Closing, Escrow Refund & Obligation Preservation ---');

// In round1: 20 max recipients, 2 paid (spent 10, remaining 90)
// Now tester3 submits a report -> it attaches round1 snapshot and is PENDING
const subT3Round1 = await inSession(tester3, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp1}', 'Tester 3 pending report', 5,
      'c0000000-0000-0000-0000-000000000001',
      '${makePhotoUrl(camp1, tester3, 't3c1')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp1, tester3, 't3c1')}',
      null
    ) as res
  `);
  return rows[0].res;
});
assert.equal(subT3Round1.tester_bonus_round_id, round1Id);

// Owner calls close_campaign_tester_bonus while tester 3 report is pending:
// Must transition round to 'closing', stopped_at set, remaining credits NOT refunded yet!
const closePendingRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.close_campaign_tester_bonus('${camp1}') as res`);
  return rows[0].res;
});
assert.equal(closePendingRes.success, true);
assert.equal(closePendingRes.closed, false, 'Must not finalize closing while pending reports exist');
assert.equal(closePendingRes.status, 'closing');

const { rows: [round1Closing] } = await db.query(`select * from public.campaign_tester_bonus_rounds where id = '${round1Id}'`);
assert.equal(round1Closing.status, 'closing');
assert.ok(round1Closing.stopped_at !== null);
assert.equal(round1Closing.remaining_credits, 90, 'Credits remain in escrow for pending obligation');

// A new tester attempts to submit report while round is 'closing':
// Must NOT attach the closing round!
const subT2NewRes = await inSession(owner2, async () => {
  // owner2 as tester on camp1
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp1}', 'New tester after closing', 5,
      'c0000000-0000-0000-0000-000000000002',
      '${makePhotoUrl(camp1, owner2, 'new_close')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp1, owner2, 'new_close')}',
      null
    ) as res
  `);
  return rows[0].res;
});
assert.equal(subT2NewRes.tester_bonus_round_id, null, 'Closing round must not accept new submissions');

// Now approve tester 3's pending report:
// It WAS submitted before closing, so it MUST be settled!
const approveT3Round1 = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.approve_tester_report('${subT3Round1.report_id}', 0) as res`);
  return rows[0].res;
});
assert.equal(approveT3Round1.success, true);
assert.equal(approveT3Round1.tester_bonus_awarded, 5, 'Pending report submitted before close must be settled');

// Now round1 has 3 paid (spent 15, remaining 85), and 0 pending reports remaining!
// Run periodic auto-accept (or direct close helper) -> it must close and refund exactly 85 credits to owner!
const { rows: [ownerBeforeRefund] } = await db.query(`select credits from public.profiles where id = '${owner}'`);

await db.query(`select public.auto_accept_expired_reports()`);

const { rows: [round1Closed] } = await db.query(`select * from public.campaign_tester_bonus_rounds where id = '${round1Id}'`);
assert.equal(round1Closed.status, 'closed');
assert.equal(round1Closed.paid_count, 3);
assert.equal(round1Closed.spent_credits, 15);
assert.equal(round1Closed.remaining_credits, 0);
assert.equal(round1Closed.refunded_credits, 85);
assert.ok(round1Closed.closed_at !== null);

// Invariant: reserved = remaining + spent + refunded (100 = 0 + 15 + 85)
assert.equal(round1Closed.reserved_credits, round1Closed.remaining_credits + round1Closed.spent_credits + round1Closed.refunded_credits);

// Owner balance must have increased by exactly 85
const { rows: [ownerAfterRefund] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
assert.equal(ownerAfterRefund.credits, ownerBeforeRefund.credits + 85);

// Check credit logs contains refund
const { rows: [refundLog] } = await db.query(
  `select * from public.credit_logs where user_id = '${owner}' and action = 'tester_bonus_refund'`
);
assert.equal(refundLog.amount, 85);

// Repeated refund call is idempotent and does not refund again
const repeatCloseRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.close_campaign_tester_bonus('${camp1}') as res`);
  return rows[0].res;
});
assert.equal(repeatCloseRes.success, true);
const { rows: [ownerAfterRepeat] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
assert.equal(ownerAfterRepeat.credits, ownerAfterRefund.credits, 'Repeated close must not double-refund');

console.log('PASS: Closing, obligation preservation and single exact refund verified.');

// ============================================================================
// TEST 11: Hard Delete Campaign Guard
// ============================================================================
console.log('--- TEST 11: Hard Delete Campaign Guard ---');

// Create round on campOther
const reqIdOther = 'f1000000-0000-0000-0000-000000000099';
await inSession(owner2, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${campOther}', 5, 10, '${reqIdOther}')`);
});

// Tester submits report on campOther -> pending
const subOther = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${campOther}', 'Tester 1 on other', 5,
      'c0000000-0000-0000-0000-000000000099',
      '${makePhotoUrl(campOther, tester1, 'other_t1')}',
      '0123456789abcdef',
      '${makeThumbUrl(campOther, tester1, 'other_t1')}',
      null
    ) as res
  `);
  return rows[0].res;
});

// Hard delete directly on campaigns table is rejected by trigger
// while bonus round is active/closing with remaining credits:
await assert.rejects(
  () => db.query(`delete from public.campaigns where id = '${campOther}'`),
  /CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_BONUS_ROUND/,
  'Direct campaign deletion must be blocked by trigger while pending obligations exist'
);

// Admin deletion sets status to pending_removal to protect pending obligations
const delPendingRes = await inSession(admin, async () => {
  const { rows } = await db.query(`select public.admin_delete_campaign('${campOther}', 'test delete') as res`);
  return rows[0].res;
});
assert.equal(delPendingRes.status, 'pending_removal');

// Resolve pending report (approve)
await inSession(owner2, () => db.query(`select public.approve_tester_report('${subOther.report_id}', 0)`));

// Batch processor finalizes closing and removes the pending_removal campaign
await db.query(`select public._process_closing_tester_bonus_rounds()`);

// Verify campaign is deleted
const { rows: campCheck } = await db.query(`select * from public.campaigns where id = '${campOther}'`);
assert.equal(campCheck.length, 0);

// Verify financial records (round & awards) are PRESERVED (not cascade deleted!)
const { rows: roundCheck } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqIdOther}'`);
assert.equal(roundCheck.length, 1, 'Bonus round record must be preserved for financial audit retention');
assert.equal(roundCheck[0].status, 'closed');

console.log('PASS: Hard delete campaign guard & audit retention verified.');

// ============================================================================
// TEST 12: Feed Sync V2 Token Invalidation
// ============================================================================
console.log('--- TEST 12: Feed Sync V2 Token Invalidation ---');

const tester4 = 'a1000000-0000-0000-0000-000000000004';
await db.exec(`
  insert into auth.users (id, email) values ('${tester4}', 'tester4@test.local') on conflict (id) do nothing;
  update public.profiles set display_name = 'Tester 4', credits = 100, role = 'member', is_locked = false where id = '${tester4}';
`);

// Re-create an active round on camp1
const reqIdSync = 'f1000000-0000-0000-0000-000000000088';
await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${camp1}', 5, 10, '${reqIdSync}')`);
});

// Get home feed sync v2 with empty tokens
const feed1 = (await db.query(`select public.get_home_feed_sync_v2('{}'::jsonb) as res`)).rows[0].res;
assert.ok(feed1.blocks.open.token);
const token1 = feed1.blocks.open.token;
assert.equal(feed1.blocks.open.changed, true);

// Call again with token1 -> changed must be false
const feed2 = (await db.query(`select public.get_home_feed_sync_v2(jsonb_build_object('open', '${token1}')) as res`)).rows[0].res;
assert.equal(feed2.blocks.open.changed, false);

// Now simulate an award deduction: tester4 submits new report on camp1 and gets approved
const subSync = await inSession(tester4, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${camp1}', 'Sync test report', 5,
      'd0000000-0000-0000-0000-000000000001',
      '${makePhotoUrl(camp1, tester4, 'sync')}',
      '0123456789abcdef',
      '${makeThumbUrl(camp1, tester4, 'sync')}',
      null
    ) as res
  `);
  return rows[0].res;
});
assert.ok(subSync.tester_bonus_round_id !== null, 'New report on camp1 must attach active round');

await inSession(owner, () => db.query(`select public.approve_tester_report('${subSync.report_id}', 0)`));

const { rows: [camp1AfterSync] } = await db.query(`select * from public.campaigns where id = '${camp1}'`);
assert.equal(camp1AfterSync.tester_bonus_remaining_slots, 9);
assert.equal(camp1AfterSync.tester_bonus_paid_count, 1);

// Remaining slots changed from 10 to 9 on camp1.
// Calling get_home_feed_sync_v2 with token1 MUST report changed = true and a new token!
const feed3 = (await db.query(`select public.get_home_feed_sync_v2(jsonb_build_object('open', '${token1}')) as res`)).rows[0].res;
assert.equal(feed3.blocks.open.changed, true, 'Remaining slot change must invalidate sync token');
assert.notEqual(feed3.blocks.open.token, token1);

console.log('PASS: Feed sync v2 token invalidation verified.');

// ============================================================================
// TEST 13: Report Read Projection APIs Include Bonus Fields
// ============================================================================
console.log('--- TEST 13: Report Read Projection APIs Include Bonus Fields ---');

// Check get_campaign_detail for owner
const campDetail = (await inSession(owner, async () => {
  const { rows } = await db.query(`select public.get_campaign_detail('${camp1}') as res`);
  return rows[0].res;
}));
assert.ok(Array.isArray(campDetail.reports));
const repInDetail = campDetail.reports.find(r => r.id === subSync.report_id);
assert.ok(repInDetail, 'Report must be found in campaign detail');
assert.equal(repInDetail.tester_bonus_awarded, 5, 'Detail must include tester_bonus_awarded');
assert.ok(repInDetail.tester_bonus_round_id, 'Detail must include immutable tester_bonus_round_id');

// Check get_campaign_reports_for_owner
const ownerReports = (await inSession(owner, async () => {
  const { rows } = await db.query(`select public.get_campaign_reports_for_owner('${camp1}') as res`);
  return rows[0].res;
}));
assert.ok(Array.isArray(ownerReports));
const repInOwnerList = ownerReports.find(r => r.id === subSync.report_id);
assert.ok(repInOwnerList, 'Report must be found in owner report list');
assert.equal(repInOwnerList.tester_bonus_awarded, 5, 'Owner list must include tester_bonus_awarded');
assert.ok(repInOwnerList.tester_bonus_round_id, 'Owner list must include immutable tester_bonus_round_id');

// Check admin_get_tester_reports_queue
const adminQueue = (await inSession(admin, async () => {
  const { rows } = await db.query(`select public.admin_get_tester_reports_queue('approved', '${camp1}') as res`);
  return rows[0].res;
}));
assert.ok(Array.isArray(adminQueue.items));
const repInAdminQueue = adminQueue.items.find(r => r.id === subSync.report_id);
assert.ok(repInAdminQueue, 'Report must be found in admin queue');
assert.equal(repInAdminQueue.tester_bonus_awarded, 5, 'Admin queue must include tester_bonus_awarded');
assert.ok(repInAdminQueue.tester_bonus_round_id, 'Admin queue must include immutable tester_bonus_round_id');

console.log('PASS: Report read projection APIs verified with tester_bonus_awarded and tester_bonus_round_id.');

// ============================================================================
// TEST 14: Valid Large Bonus Approval & Cumulative Response Arithmetic
// ============================================================================
console.log('--- TEST 14: Valid Large Bonus Approval & Cumulative Response Arithmetic ---');

const largeOwner = 'c3000000-0000-0000-0000-000000000099';
const largeTester = 'a1000000-0000-0000-0000-000000000099';
const largeCamp = 'b4000000-0000-0000-0000-000000000099';

await db.exec(`
  insert into auth.users (id, email) values
    ('${largeOwner}', 'large_owner@test.local'),
    ('${largeTester}', 'large_tester@test.local')
  on conflict (id) do nothing;

  update public.profiles set credits = 2147483647, role = 'member', is_locked = false where id = '${largeOwner}';
  update public.profiles set credits = 0, role = 'member', is_locked = false where id = '${largeTester}';

  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${largeCamp}', '${largeOwner}', 'Large Bonus App', 'com.test.large', 'open', 1, now())
  on conflict (id) do nothing;
`);

// Create valid large bonus round: 2147483640 reward x 1 recipient
const reqLarge = 'f1000000-0000-0000-0000-000000000099';
await inSession(largeOwner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${largeCamp}', 2147483640, 1, '${reqLarge}')`);
});

// Large tester submits report
const largeRep = await inSession(largeTester, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${largeCamp}', 'Large award report', 5,
      'd0000000-0000-0000-0000-000000000099',
      '${makePhotoUrl(largeCamp, largeTester, 'large')}',
      '0123456789abcdef',
      '${makeThumbUrl(largeCamp, largeTester, 'large')}',
      null
    ) as res
  `);
  return rows[0].res;
});
assert.ok(largeRep.tester_bonus_round_id, 'Must attach large bonus round snapshot');

// Tester spends provisional credits (credits reset to 0 in fixture to test wallet capacity)
await db.query(`update public.profiles set credits = 0 where id = '${largeTester}'`);

// Approve report: Base 2 + extra 2147483640 fits wallet (2147483642 <= 2147483647)
// Cumulative total_earned = 10 (provisional) + 2 (base) + 2147483640 (extra) = 2147483652 (> int max)
const approvedLarge = await inSession(largeOwner, async () => {
  const { rows } = await db.query(`select public.approve_tester_report('${largeRep.report_id}', 0) as res`);
  return rows[0].res;
});

assert.equal(approvedLarge.tester_bonus_awarded, 2147483640);
assert.ok(Number(approvedLarge.total_earned) > 2147483647, 'total_earned must handle 64-bit bigint');
assert.equal(approvedLarge.tester_balance, 2147483642);

console.log('PASS: Valid large bonus response arithmetic verified without 22003 overflow.');

// ============================================================================
// TEST 15: Overflow Worker Retry & Isolation
// ============================================================================
console.log('--- TEST 15: Overflow Worker Retry & Isolation ---');

// Set owner balance near INT_MAX so refund would overflow INT_MAX
await db.query(`update public.profiles set credits = 2147483640 where id = '${largeOwner}'`);

// Create another round on camp2 with 100 credits reserved
const ovCamp = camp2;
const reqOv = 'f1000000-0000-0000-0000-000000000077';
await db.query(`update public.profiles set credits = 500 where id = '${owner}'`);
await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${ovCamp}', 50, 2, '${reqOv}')`);
});

// Now close the round when owner wallet is artificially set to 2147483640
await db.query(`update public.profiles set credits = 2147483640 where id = '${owner}'`);
// Direct close attempt on overflow should return retryable failure without falsely marking closed
const { rows: [ovCloseRes] } = await db.query(`
  select public._close_and_refund_tester_bonus_round(id) as res
  from public.campaign_tester_bonus_rounds
  where client_request_id = '${reqOv}'
`);
assert.equal(ovCloseRes.res.closed, false, 'Overflow must not falsely mark closed');
assert.equal(ovCloseRes.res.reason, 'WALLET_BALANCE_OVERFLOW');

// Verify escrow credits remain strictly preserved in the round
const { rows: [ovRound] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqOv}'`);
assert.equal(ovRound.status, 'closing');
assert.equal(ovRound.reserved_credits, 100);
assert.equal(ovRound.refunded_credits, 0);

// Reset owner wallet to valid level and run worker -> must succeed cleanly
await db.query(`update public.profiles set credits = 100 where id = '${owner}'`);
await db.query(`select public._process_closing_tester_bonus_rounds()`);
const { rows: [ovRoundRecovered] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqOv}'`);
assert.equal(ovRoundRecovered.status, 'closed');
assert.equal(ovRoundRecovered.refunded_credits, 100);

console.log('PASS: Overflow worker retry and escrow isolation verified.');

// ============================================================================
// TEST 16: Deferred Admin Deletion when Fully Consumed (Recipients = 1)
// ============================================================================
console.log('--- TEST 16: Deferred Admin Deletion when Fully Consumed ---');

const delCamp = 'b5000000-0000-0000-0000-000000000055';
const reqDel = 'f1000000-0000-0000-0000-000000000055';

await db.exec(`
  insert into public.user_slots (user_id, slot_number, expires_at)
  values ('${owner}', 3, now() + interval '30 days')
  on conflict (user_id, slot_number) do update set expires_at = now() + interval '30 days';

  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${delCamp}', '${owner}', 'Deferred Delete App', 'com.test.del', 'open', 3, now())
  on conflict (id) do nothing;
`);

// Create bonus round with exactly 1 recipient
await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${delCamp}', 25, 1, '${reqDel}')`);
});

// Tester 1 submits report
const delRep = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${delCamp}', 'Del report', 5,
      'd0000000-0000-0000-0000-000000000055',
      '${makePhotoUrl(delCamp, tester1, 'del')}',
      '0123456789abcdef',
      '${makeThumbUrl(delCamp, tester1, 'del')}',
      null
    ) as res
  `);
  return rows[0].res;
});
assert.ok(delRep.tester_bonus_round_id);

// Admin requests campaign deletion -> transitions to pending_removal
const adminDelRes = await inSession(admin, async () => {
  const { rows } = await db.query(`select public.admin_delete_campaign('${delCamp}', 'Violates policy') as res`);
  return rows[0].res;
});
assert.equal(adminDelRes.status, 'pending_removal');

// Owner approves the only report -> this consumes the last slot (recipients=1 -> paid_count=1)
// and transitions round status to 'closed'!
await inSession(owner, async () => {
  await db.query(`select public.approve_tester_report('${delRep.report_id}', 0)`);
});

// Run batch worker: Even though round status became 'closed', worker MUST complete the durable removal
await db.query(`select public._process_closing_tester_bonus_rounds()`);

// Verify campaign is durably deleted from campaigns table
const { rows: delCampCheck } = await db.query(`select * from public.campaigns where id = '${delCamp}'`);
assert.equal(delCampCheck.length, 0, 'Campaign must be durably deleted even when round was fully consumed to closed');

// Verify bonus round record preserved for audit
const { rows: [delRoundPreserved] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqDel}'`);
assert.equal(delRoundPreserved.status, 'closed');
assert.equal(delRoundPreserved.paid_count, 1);

console.log('PASS: Deferred admin deletion when fully consumed verified.');

// ============================================================================
// TEST 17: Post-Physical-Deletion Durable Reconcile & Idempotent Replay
// ============================================================================
console.log('--- TEST 17: Post-Physical-Deletion Reconcile & Replay ---');

// 1. Owner reconcile after physical campaign deletion (delCamp is deleted from campaigns)
const reconAfterDel = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('${delCamp}', '${reqDel}') as res`);
  return rows[0].res;
});
assert.equal(reconAfterDel.found, true, 'Reconcile must find round even after campaign is physically deleted');
assert.equal(reconAfterDel.replayed, true);
assert.equal(reconAfterDel.campaign_id, delCamp, 'Reconcile must return original campaign identity');
assert.equal(reconAfterDel.status, 'closed');
assert.equal(reconAfterDel.paid_count, 1);
assert.ok(typeof reconAfterDel.owner_balance === 'number');

// 2. Owner create replay with same payload after physical deletion
const ownerBalBeforeReplay = reconAfterDel.owner_balance;
const createReplayAfterDel = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.create_campaign_tester_bonus('${delCamp}', 25, 1, '${reqDel}') as res`);
  return rows[0].res;
});
assert.equal(createReplayAfterDel.success, true);
assert.equal(createReplayAfterDel.replayed, true, 'Create must return replayed=true without charging again');
assert.equal(createReplayAfterDel.campaign_id, delCamp, 'Create replay must return original campaign identity');
assert.equal(createReplayAfterDel.status, 'closed');
assert.equal(createReplayAfterDel.owner_balance, ownerBalBeforeReplay, 'Owner wallet must not change on replay');

// 3. Negative tests:
// 3a. Reconcile with wrong campaign ID must return found=false
const reconWrongCamp = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('b9999999-9999-9999-9999-999999999999', '${reqDel}') as res`);
  return rows[0].res;
});
assert.equal(reconWrongCamp.found, false, 'Reconcile with wrong campaign must return found=false');

// 3b. Non-owner (tester1) reconcile must return found=false
const reconOtherUser = await inSession(tester1, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('${delCamp}', '${reqDel}') as res`);
  return rows[0].res;
});
assert.equal(reconOtherUser.found, false, 'Non-owner reconcile must return found=false');

// 3c. Create replay with wrong campaign ID must fail with conflict
await inSession(owner, async () => {
  await assert.rejects(
    () => db.query(`select public.create_campaign_tester_bonus('${camp1}', 25, 1, '${reqDel}')`),
    /CLIENT_REQUEST_ID_CONFLICT/
  );
});

// 3d. Locked account can still reconcile and read committed create receipt
await db.query(`update public.profiles set is_locked = true where id = '${owner}'`);
const reconLocked = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('${delCamp}', '${reqDel}') as res`);
  return rows[0].res;
});
assert.equal(reconLocked.found, true, 'Locked account must be able to reconcile committed request');

const replayLocked = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.create_campaign_tester_bonus('${delCamp}', 25, 1, '${reqDel}') as res`);
  return rows[0].res;
});
assert.equal(replayLocked.replayed, true, 'Locked account must receive cached receipt for committed request');
await db.query(`update public.profiles set is_locked = false where id = '${owner}'`);

// 4. Test partial refund scenario with physical campaign deletion
const delCamp2 = 'b5000000-0000-0000-0000-000000000056';
const reqDel2 = 'f1000000-0000-0000-0000-000000000056';
await db.exec(`
  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${delCamp2}', '${owner}', 'Partial Delete App', 'com.test.del2', 'open', 3, now())
  on conflict (id) do nothing;
`);

await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${delCamp2}', 20, 2, '${reqDel2}')`);
});

// Tester 1 submits and owner approves 1 report (1 of 2 slots used)
const delRep2 = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${delCamp2}', 'Del2 report', 5,
      'd0000000-0000-0000-0000-000000000056',
      '${makePhotoUrl(delCamp2, tester1, 'del2')}',
      '0123456789abcdef',
      '${makeThumbUrl(delCamp2, tester1, 'del2')}',
      null
    ) as res
  `);
  return rows[0].res;
});
await inSession(owner, async () => {
  await db.query(`select public.approve_tester_report('${delRep2.report_id}', 0)`);
});

// Admin deletes campaign
await inSession(admin, async () => {
  await db.query(`select public.admin_delete_campaign('${delCamp2}', 'Policy removal')`);
});

// Worker processes -> closing round refunds unused 20 credits and removes campaign row
await db.query(`select public._process_closing_tester_bonus_rounds()`);

// Verify physical campaign row deleted
const { rows: delCamp2Check } = await db.query(`select * from public.campaigns where id = '${delCamp2}'`);
assert.equal(delCamp2Check.length, 0);

// Owner reconciles partial refund round
const reconDel2 = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('${delCamp2}', '${reqDel2}') as res`);
  return rows[0].res;
});
assert.equal(reconDel2.found, true);
assert.equal(reconDel2.campaign_id, delCamp2);
assert.equal(reconDel2.status, 'closed');
assert.equal(reconDel2.spent_credits, 20);
assert.equal(reconDel2.refunded_credits, 20);

// Verify original_campaign_id in awards and events tables
const { rows: awardsOrig } = await db.query(`select original_campaign_id from public.campaign_tester_bonus_awards where round_id = '${reconDel2.round_id}'`);
assert.equal(awardsOrig[0].original_campaign_id, delCamp2);

const { rows: eventsOrig } = await db.query(`select original_campaign_id from public.campaign_tester_bonus_events where round_id = '${reconDel2.round_id}'`);
assert.ok(eventsOrig.length >= 3); // reserve, payout, refund
assert.ok(eventsOrig.every(e => e.original_campaign_id === delCamp2));

console.log('PASS: Post-physical-deletion durable reconcile & replay verified.');

console.log('\n======================================================');
console.log('ALL CAMPAIGN TESTER BONUS BEHAVIORAL & RLS TESTS PASSED!');
console.log('======================================================\n');
await db.close();
