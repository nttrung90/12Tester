import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb({ legacyGrants: true });

const adminId = '11111111-1111-1111-1111-111111111111';
const lockedAdminId = '22222222-2222-2222-2222-222222222222';
const modId = '33333333-3333-3333-3333-333333333333';
const ownerA = '44444444-4444-4444-4444-444444444444';
const ownerB = '55555555-5555-5555-5555-555555555555';

async function asActor(uid, sql, role = 'authenticated') {
  await db.exec('begin;');
  try {
    await db.exec(`select set_config('request.jwt.claim.sub','${uid ?? ''}',true); set local role ${role};`);
    const res = await db.query(sql);
    await db.exec('commit;');
    return res;
  } catch (err) {
    await db.exec('rollback;');
    throw err;
  }
}

async function runStep(name, fn) {
  try {
    await fn();
    console.log(`PASS: ${name}`);
  } catch (err) {
    console.error(`FAIL: ${name}`, err);
    throw err;
  }
}

console.log('=== APPLYING ALL DEPLOYABLE MIGRATIONS (062..latest) ===');
const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62)
  .sort();

assert.ok(
  migrationFiles.some(n => n.startsWith('084_')),
  '084_campaign_posting_business_policies.sql must be present'
);

for (const name of migrationFiles) {
  await db.exec(read('supabase/migrations/' + name));
  console.log(`Applied ${name}`);
}

await db.exec(`
  insert into auth.users (id, email) values
    ('${adminId}', 'admin@test.local'),
    ('${lockedAdminId}', 'locked_admin@test.local'),
    ('${modId}', 'mod@test.local'),
    ('${ownerA}', 'owner_a@test.local'),
    ('${ownerB}', 'owner_b@test.local')
  on conflict (id) do nothing;

  insert into public.profiles (id, email, display_name, credits, role, is_locked) values
    ('${adminId}', 'admin@test.local', 'Admin', 1000, 'admin', false),
    ('${lockedAdminId}', 'locked_admin@test.local', 'Locked Admin', 1000, 'admin', true),
    ('${modId}', 'mod@test.local', 'Moderator', 1000, 'moderator', false),
    ('${ownerA}', 'owner_a@test.local', 'Owner A', 10, 'member', false),
    ('${ownerB}', 'owner_b@test.local', 'Owner B', 100, 'member', false)
  on conflict (id) do update set
    credits = excluded.credits,
    role = excluded.role,
    is_locked = excluded.is_locked;
`);

await runStep('1. Single PostgREST signature & least-privilege grants', async () => {
  const overloadRes = await db.query(`
    select count(*)::int as cnt
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'admin_schedule_business_policy'
  `);
  assert.equal(overloadRes.rows[0].cnt, 1, 'Must have exactly 1 admin_schedule_business_policy signature');

  for (const fnSig of [
    '_get_business_policy_revision()',
    '_format_business_policy_json(public.business_policies)',
    '_format_business_policy_public_json(public.business_policies)',
    '_get_business_quote_token(text,public.business_policies,jsonb)',
  ]) {
    const priv = (await db.query(`select has_function_privilege('authenticated', 'public.${fnSig}', 'EXECUTE') as ok`)).rows[0].ok;
    assert.equal(priv, false, `authenticated must NOT execute internal helper ${fnSig}`);
  }

  const anonExecCreate = (await db.query(
    "select has_function_privilege('anon', 'public.create_campaign_v2(text,text,text,text,text,integer,text,text,text,integer,uuid,integer,integer,text)', 'EXECUTE') as ok"
  )).rows[0].ok;
  assert.equal(anonExecCreate, false, 'anon must NOT execute create_campaign_v2');

  const authInsertReceipts = (await db.query(
    "select has_table_privilege('authenticated', 'public.campaign_creation_receipts', 'INSERT') as ok"
  )).rows[0].ok;
  assert.equal(authInsertReceipts, false, 'authenticated must NOT have direct INSERT on campaign_creation_receipts');
});

let baseAdminCfg;
await runStep('2. Baseline policy & quote shapes include posting threshold, slot_1, and target_fees', async () => {
  baseAdminCfg = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg')).rows[0].cfg;
  const active = baseAdminCfg.active_policy;
  assert.equal(active.min_credits_to_post_app, 10);
  assert.equal(active.slot_fees.slot_1, 0);
  assert.equal(active.slot_fees.slot_2, 50);
  assert.deepEqual(active.target_fees, { target_12: 0, target_15: 10, target_20: 20 });

  const qAll = (await asActor(ownerA, "select public.get_business_quote('all') as q")).rows[0].q;
  assert.equal(qAll.min_credits_to_post_app, 10);
  assert.equal(qAll.slot_fees.slot_1, 0);
  assert.deepEqual(qAll.target_fees, { target_12: 0, target_15: 10, target_20: 20 });

  const qCreate = (await asActor(
    ownerA,
    `select public.get_business_quote('campaign_create', '{"slot_number": 1, "target_testers": 15}') as q`
  )).rows[0].q;
  assert.equal(qCreate.slot_1_cost, 0);
  assert.equal(qCreate.target_cost, 10);
  assert.equal(qCreate.total_cost, 10);
  assert.equal(qCreate.min_credits_to_post_app, 10);
  assert.equal(qCreate.required_credits, 20);
  assert.ok(qCreate.revision);
});

await runStep('3. Standardized >= minimum threshold on baseline (9 fails, 10 succeeds on 12 Test)', async () => {
  const qAll = (await asActor(ownerA, "select public.get_business_quote('all') as q")).rows[0].q;

  // 9 Credits -> rejected
  await db.exec(`update public.profiles set credits = 9 where id = '${ownerA}'`);
  await assert.rejects(
    asActor(ownerA, `
      select public.create_campaign_v2(
        'App A1', 'com.owner.a1', '', '', 'Desc', 12, '', '', '', 1,
        '00000000-0000-0000-0000-000000000001'::uuid, 0, 10, '${qAll.revision}'
      )
    `),
    /INSUFFICIENT_CREDITS/
  );

  // 10 Credits -> succeeds on Target 12, Slot 1 (0 deducted, 10 remaining)
  await db.exec(`update public.profiles set credits = 10 where id = '${ownerA}'`);
  const createRes = (await asActor(ownerA, `
    select public.create_campaign_v2(
      'App A1', 'com.owner.a1', '', '', 'Desc', 12, '', '', '', 1,
      '00000000-0000-0000-0000-000000000002'::uuid, 0, 10, '${qAll.revision}'
    ) as r
  `)).rows[0].r;
  assert.equal(createRes.success, true);
  assert.equal(createRes.charged_credits, 0);
  assert.equal(createRes.balance_after, 10);
  assert.equal(createRes.replayed, false);

  // Replay with same client_request_id -> idempotent, replayed = true
  const replayRes = (await asActor(ownerA, `
    select public.create_campaign_v2(
      'App A1', 'com.owner.a1', '', '', 'Desc', 12, '', '', '', 1,
      '00000000-0000-0000-0000-000000000002'::uuid, 0, 10, '${qAll.revision}'
    ) as r
  `)).rows[0].r;
  assert.equal(replayRes.replayed, true);
  assert.equal(replayRes.campaign_id, createRes.campaign_id);

  // Reuse same client_request_id with different package -> REQUEST_ID_CONFLICT
  await assert.rejects(
    asActor(ownerA, `
      select public.create_campaign_v2(
        'App A2', 'com.owner.a2', '', '', 'Desc', 12, '', '', '', 1,
        '00000000-0000-0000-0000-000000000002'::uuid, 0, 10, '${qAll.revision}'
      )
    `),
    /REQUEST_ID_CONFLICT/
  );

  // Clean up campaign while verifying receipt survives deletion (ON DELETE SET NULL)
  await db.exec(`delete from public.campaigns where id = '${createRes.campaign_id}'`);
  const receiptRow = (await asActor(
    ownerA,
    `select campaign_id, created_campaign_id from public.campaign_creation_receipts where client_request_id = '00000000-0000-0000-0000-000000000002'`
  )).rows[0];
  assert.equal(receiptRow.campaign_id, null);
  assert.equal(receiptRow.created_campaign_id, createRes.campaign_id);
});

await runStep('4. Admin validation, role checks, and future scheduling of new posting parameters', async () => {
  for (const uid of [ownerA, modId, lockedAdminId]) {
    await assert.rejects(
      asActor(uid, `select public.admin_schedule_business_policy(current_date + 3, 'note', '${baseAdminCfg.revision}')`),
      /FORBIDDEN/
    );
  }

  // target_15_fee > target_20_fee rejected
  await assert.rejects(
    asActor(adminId, `
      select public.admin_schedule_business_policy(
        p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 3,
        p_operator_note => 'Invalid target ordering',
        p_expected_revision => '${baseAdminCfg.revision}',
        p_min_credits_to_post_app => 20,
        p_slot_fee_1 => 5,
        p_target_15_fee => 30,
        p_target_20_fee => 25
      )
    `),
    /BUSINESS_POLICY_INVALID_TARGET_FEES/
  );

  // Negative slot_fee_1 rejected
  await assert.rejects(
    asActor(adminId, `
      select public.admin_schedule_business_policy(
        p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 3,
        p_operator_note => 'Negative slot 1',
        p_expected_revision => '${baseAdminCfg.revision}',
        p_slot_fee_1 => -1
      )
    `),
    /BUSINESS_POLICY_INVALID_SLOT_FEES/
  );

  // Negative min_credits_to_post_app rejected
  await assert.rejects(
    asActor(adminId, `
      select public.admin_schedule_business_policy(
        p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 3,
        p_operator_note => 'Negative min credits',
        p_expected_revision => '${baseAdminCfg.revision}',
        p_min_credits_to_post_app => -5
      )
    `),
    /BUSINESS_POLICY_INVALID_MIN_CREDITS_TO_POST/
  );

  const reqId = '99999999-9999-9999-9999-999999999901';
  const schedRes = (await asActor(adminId, `
    select public.admin_schedule_business_policy(
      p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 3,
      p_operator_note => 'Cap nhat phi dang app va muc tieu test',
      p_expected_revision => '${baseAdminCfg.revision}',
      p_request_id => '${reqId}'::uuid,
      p_min_credits_to_post_app => 25,
      p_slot_fee_1 => 15,
      p_target_15_fee => 20,
      p_target_20_fee => 45
    ) as cfg
  `)).rows[0].cfg;

  assert.equal(schedRes.future_policies.length, 1);
  const fp = schedRes.future_policies[0];
  assert.equal(fp.min_credits_to_post_app, 25);
  assert.equal(fp.slot_fees.slot_1, 15);
  assert.deepEqual(fp.target_fees, { target_12: 0, target_15: 20, target_20: 45 });

  // Active policy today is STILL baseline (10, 0, 10, 20)
  assert.equal(schedRes.active_policy.min_credits_to_post_app, 10);
  assert.equal(schedRes.active_policy.slot_fees.slot_1, 0);

  // Admin request-ID replay returns identical result
  const replaySched = (await asActor(adminId, `
    select public.admin_schedule_business_policy(
      p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 3,
      p_operator_note => 'Cap nhat phi dang app va muc tieu test',
      p_expected_revision => 'stale_revision_ignored_on_exact_replay',
      p_request_id => '${reqId}'::uuid,
      p_min_credits_to_post_app => 25,
      p_slot_fee_1 => 15,
      p_target_15_fee => 20,
      p_target_20_fee => 45
    ) as cfg
  `)).rows[0].cfg;
  assert.equal(replaySched.revision, schedRes.revision);
});

await runStep('5. Active custom policy enforcement: Slot 1 fee, Target fees, CAS mismatch, Legacy fail-closed, Slot 1 permanence', async () => {
  const staleQuote = (await asActor(ownerB, "select public.get_business_quote('all') as q")).rows[0].q;

  // Activate new policy terms on baseline row via test flag to simulate effective_from arrival
  await db.exec(`
    begin;
    set local test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set min_credits_to_post_app = 25,
        slot_fee_1 = 15,
        target_15_fee = 20,
        target_20_fee = 45,
        updated_at = clock_timestamp()
    where effective_from = date '-infinity';
    commit;
  `);

  // Stale quote / old expected cost rejected
  await assert.rejects(
    asActor(ownerB, `
      select public.create_campaign_v2(
        'App B1', 'com.owner.b1', '', '', 'Desc', 15, '', '', '', 1,
        '00000000-0000-0000-0000-000000000010'::uuid, 10, 10, '${staleQuote.revision}'
      )
    `),
    /BUSINESS_POLICY_PRICE_MISMATCH/
  );

  // Legacy direct INSERT on Slot 1 blocked fail-closed with CLIENT_UPDATE_REQUIRED
  await assert.rejects(
    asActor(ownerB, `
      insert into public.campaigns (owner_id, app_name, package_name, slot_number, target_testers)
      values ('${ownerB}', 'Legacy App', 'com.legacy.app', 1, 12)
    `),
    /CLIENT_UPDATE_REQUIRED/
  );

  // Fetch fresh quote: Slot 1 (15) + Target 15 (20) = 35 total_cost; + 25 min_credits = 60 required_credits
  const freshQuote = (await asActor(
    ownerB,
    `select public.get_business_quote('campaign_create', '{"slot_number": 1, "target_testers": 15}') as q`
  )).rows[0].q;
  assert.equal(freshQuote.slot_1_cost, 15);
  assert.equal(freshQuote.target_cost, 20);
  assert.equal(freshQuote.total_cost, 35);
  assert.equal(freshQuote.min_credits_to_post_app, 25);
  assert.equal(freshQuote.required_credits, 60);

  // 59 Credits -> rejected (needs 60)
  await db.exec(`update public.profiles set credits = 59 where id = '${ownerB}'`);
  await assert.rejects(
    asActor(ownerB, `
      select public.create_campaign_v2(
        'App B1', 'com.owner.b1', '', '', 'Desc', 15, '', '', '', 1,
        '00000000-0000-0000-0000-000000000011'::uuid, 35, 25, '${freshQuote.revision}'
      )
    `),
    /INSUFFICIENT_CREDITS_FOR_TARGET/
  );

  // 60 Credits -> succeeds, charges 35, leaves 25
  await db.exec(`update public.profiles set credits = 60 where id = '${ownerB}'`);
  const created = (await asActor(ownerB, `
    select public.create_campaign_v2(
      'App B1', 'com.owner.b1', '', '', 'Desc', 15, '', '', '', 1,
      '00000000-0000-0000-0000-000000000012'::uuid, 35, 25, '${freshQuote.revision}'
    ) as r
  `)).rows[0].r;

  assert.equal(created.slot_1_cost, 15);
  assert.equal(created.target_cost, 20);
  assert.equal(created.charged_credits, 35);
  assert.equal(created.balance_after, 25);

  const logs = (await db.query(`
    select action, amount, balance
    from public.credit_logs
    where user_id = '${ownerB}'
      and action in ('post_app', 'target_upgrade')
    order by created_at asc, id asc
  `)).rows;
  assert.equal(logs.length, 2);
  assert.equal(logs[0].action, 'post_app');
  assert.equal(logs[0].amount, -15);
  assert.equal(logs[1].action, 'target_upgrade');
  assert.equal(logs[1].amount, -20);
  assert.equal(logs[1].balance, 25);

  // Verify snapshot columns & client tamper protection
  const campSnap = (await db.query(
    `select slot_1_fee_paid, target_fee_paid from public.campaigns where id = '${created.campaign_id}'`
  )).rows[0];
  assert.equal(campSnap.slot_1_fee_paid, 15);
  assert.equal(campSnap.target_fee_paid, 20);

  await assert.rejects(
    asActor(ownerB, `update public.campaigns set target_fee_paid = 999 where id = '${created.campaign_id}'`),
    /PERMISSION_DENIED/
  );

  // Verify Slot 1 permanence: pause_expired_slot_campaigns() NEVER pauses Slot 1 campaign
  await db.query('select public.pause_expired_slot_campaigns()');
  const campStatus = (await db.query(
    `select status, paused_reason from public.campaigns where id = '${created.campaign_id}'`
  )).rows[0];
  assert.equal(campStatus.status, 'open');
  assert.equal(campStatus.paused_reason, null);

  // Verify Slot 2 campaign does NOT pay slot_1_cost, only pays target_cost (45 for Target 20)
  await db.exec(`
    update public.profiles set credits = 70 where id = '${ownerB}';
    insert into public.user_slots (user_id, slot_number, expires_at)
    values ('${ownerB}', 2, now() + interval '30 days');
  `);
  const allQuote = (await asActor(ownerB, "select public.get_business_quote('all') as q")).rows[0].q;
  const createdSlot2 = (await asActor(ownerB, `
    select public.create_campaign_v2(
      'App B2', 'com.owner.b2', '', '', 'Desc', 20, '', '', '', 2,
      '00000000-0000-0000-0000-000000000013'::uuid, 45, 25, '${allQuote.revision}'
    ) as r
  `)).rows[0].r;
  assert.equal(createdSlot2.slot_1_cost, 0);
  assert.equal(createdSlot2.target_cost, 45);
  assert.equal(createdSlot2.charged_credits, 45);
  assert.equal(createdSlot2.balance_after, 25);
});

await runStep('6. Adversarial direct-client UPDATE/INSERT guards, bypass GUC immunity, package regex, and metadata replay hash', async () => {
  // Setup: Owner B has Slot 3 unlocked and creates a Target 12 campaign in Slot 3 (0 upfront fee)
  await db.exec(`
    delete from public.campaigns where owner_id = '${ownerB}';
    update public.profiles set credits = 25 where id = '${ownerB}';
    insert into public.user_slots (user_id, slot_number, expires_at)
    values ('${ownerB}', 3, now() + interval '30 days')
    on conflict do nothing;
  `);

  const qSlot3 = (await asActor(
    ownerB,
    `select public.get_business_quote('campaign_create', '{"slot_number": 3, "target_testers": 12}') as q`
  )).rows[0].q;

  const slot3Camp = (await asActor(ownerB, `
    select public.create_campaign_v2(
      'App Slot3', 'com.owner.slotthree', '', '', 'Initial desc', 12, '', 'tester1', 'pass1', 3,
      '00000000-0000-0000-0000-000000000020'::uuid, 0, 25, '${qSlot3.revision}'
    ) as r
  `)).rows[0].r;

  // Exploit 1: Direct PATCH slot_number = 1 to evade slot_fee_1 (15) & Slot 3 expiry
  await assert.rejects(
    asActor(ownerB, `update public.campaigns set slot_number = 1 where id = '${slot3Camp.campaign_id}'`),
    /SLOT_NUMBER_IMMUTABLE/
  );

  // Exploit 2: Direct PATCH target_testers = 20 to bypass min_credits_to_post_app reserve
  await assert.rejects(
    asActor(ownerB, `update public.campaigns set target_testers = 20 where id = '${slot3Camp.campaign_id}'`),
    /TARGET_TESTERS_IMMUTABLE/
  );

  // Exploit 3: Setting session GUC app.campaign_v2_bypass = 'true' as authenticated still blocked
  await assert.rejects(
    asActor(ownerB, `
      with guc as (select set_config('app.campaign_v2_bypass', 'true', true))
      update public.campaigns set slot_number = 1
      from guc
      where id = '${slot3Camp.campaign_id}'
    `),
    /SLOT_NUMBER_IMMUTABLE/
  );

  // Exploit 4: Direct PATCH of package_name, tip_credits, is_boosted, target_days, owner_id blocked
  for (const [sqlSet, expectedErr] of [
    [`package_name = 'com.owner.hijack'`, /PACKAGE_NAME_IMMUTABLE/],
    [`tip_credits = 10`, /CAMPAIGN_CORE_CONFIG_IMMUTABLE/],
    [`is_boosted = true`, /CAMPAIGN_CORE_CONFIG_IMMUTABLE/],
    [`target_days = 1`, /CAMPAIGN_CORE_CONFIG_IMMUTABLE/],
    [`owner_id = '${ownerA}'`, /OWNER_ID_IMMUTABLE|row-level security/i],
  ]) {
    await assert.rejects(
      asActor(ownerB, `update public.campaigns set ${sqlSet} where id = '${slot3Camp.campaign_id}'`),
      expectedErr
    );
  }

  // Legitimate owner PATCH of descriptive metadata & manual pause still works
  await asActor(ownerB, `
    update public.campaigns
    set app_name = 'App Slot3 Updated',
        description = 'Updated description',
        play_url = 'https://play.google.com/store/apps/details?id=com.owner.slotthree',
        testing_url = 'https://play.google.com/apps/testing/com.owner.slotthree',
        test_username = 'tester2',
        test_password = 'pass2',
        icon_url = 'https://example.com/icon.png',
        status = 'paused',
        paused_reason = 'owner_manual'
    where id = '${slot3Camp.campaign_id}'
  `);
  const updatedCamp = (await db.query(
    `select app_name, description, status, paused_reason from public.campaigns where id = '${slot3Camp.campaign_id}'`
  )).rows[0];
  assert.equal(updatedCamp.app_name, 'App Slot3 Updated');
  assert.equal(updatedCamp.status, 'paused');
  assert.equal(updatedCamp.paused_reason, 'owner_manual');

  // Reusing same client_request_id with edited description or different expected_revision -> rejected
  await assert.rejects(
    asActor(ownerB, `
      select public.create_campaign_v2(
        'App Slot3', 'com.owner.slotthree', '', '', 'Edited desc!', 12, '', 'tester1', 'pass1', 3,
        '00000000-0000-0000-0000-000000000020'::uuid, 0, 25, '${qSlot3.revision}'
      )
    `),
    /IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS/
  );
  await assert.rejects(
    asActor(ownerB, `
      select public.create_campaign_v2(
        'App Slot3', 'com.owner.slotthree', '', '', 'Initial desc', 12, '', 'tester1', 'pass1', 3,
        '00000000-0000-0000-0000-000000000020'::uuid, 0, 25, 'different_revision_token'
      )
    `),
    /IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS/
  );

  // Strict Android package_name validation rejects malformed / URL-injecting strings
  for (const badPkg of ['nopackagedot', 'com.bad..pkg', 'com.1bad.pkg', 'com.bad.pkg?utm=evil#frag']) {
    await assert.rejects(
      asActor(ownerB, `
        select public.create_campaign_v2(
          'Bad Pkg', '${badPkg}', '', '', 'Desc', 12, '', '', '', 2,
          gen_random_uuid(), 0, 25, '${qSlot3.revision}'
        )
      `),
      /INVALID_PACKAGE_NAME/
    );
  }
});

await runStep('7. Promotional 0-Credit policy, revision-only BUSINESS_POLICY_CONFLICT, and accurate promo target_fee_paid accounting', async () => {
  const prevQuote = (await asActor(
    ownerA,
    `select public.get_business_quote('campaign_create', '{"slot_number": 1, "target_testers": 15}') as q`
  )).rows[0].q;

  // Activate 0-Credit promotional policy
  await db.exec(`
    begin;
    set local test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set min_credits_to_post_app = 0,
        slot_fee_1 = 0,
        target_15_fee = 0,
        target_20_fee = 0,
        updated_at = clock_timestamp() + interval '5 seconds'
    where effective_from = date '-infinity';
    commit;
  `);

  // Revision-only mismatch (expected cost = 0, min = 0 matches, but old revision token) -> BUSINESS_POLICY_CONFLICT
  await db.exec(`delete from public.campaigns where owner_id = '${ownerA}'; update public.profiles set credits = 0 where id = '${ownerA}';`);
  await assert.rejects(
    asActor(ownerA, `
      select public.create_campaign_v2(
        'Promo App', 'com.promo.zero', '', '', 'Promo', 15, '', '', '', 1,
        '00000000-0000-0000-0000-000000000030'::uuid, 0, 0, '${prevQuote.revision}'
      )
    `),
    /BUSINESS_POLICY_CONFLICT/
  );

  const promoQuote = (await asActor(
    ownerA,
    `select public.get_business_quote('campaign_create', '{"slot_number": 1, "target_testers": 15}') as q`
  )).rows[0].q;
  assert.equal(promoQuote.required_credits, 0);

  const promoCreated = (await asActor(ownerA, `
    select public.create_campaign_v2(
      'Promo App', 'com.promo.zero', '', '', 'Promo', 15, '', '', '', 1,
      '00000000-0000-0000-0000-000000000031'::uuid, 0, 0, '${promoQuote.revision}'
    ) as r
  `)).rows[0].r;
  assert.equal(promoCreated.charged_credits, 0);
  assert.equal(promoCreated.balance_after, 0);

  const zeroLogsCnt = (await db.query(
    `select count(*)::int as cnt from public.credit_logs where user_id = '${ownerA}' and action in ('post_app', 'target_upgrade')`
  )).rows[0].cnt;
  assert.equal(zeroLogsCnt, 0, 'Must not write 0-amount credit_logs rows');

  // If policy later sets target_20_fee = 30 and internal service role upgrades promo target=15 (paid 0) -> 20,
  // full 30 Credits must be charged (no phantom 10-Credit credit from legacy default)
  await db.exec(`
    begin;
    set local test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set target_15_fee = 10,
        target_20_fee = 30,
        updated_at = clock_timestamp() + interval '10 seconds'
    where effective_from = date '-infinity';
    commit;
    update public.profiles set credits = 40 where id = '${ownerA}';
    update public.campaigns set target_testers = 20 where id = '${promoCreated.campaign_id}';
  `);
  const afterInternalUpgrade = (await db.query(
    `select p.credits, c.target_fee_paid from public.profiles p join public.campaigns c on c.owner_id = p.id where c.id = '${promoCreated.campaign_id}'`
  )).rows[0];
  assert.equal(afterInternalUpgrade.credits, 10, 'Service-role upgrade from 0-paid promo 15 -> 20 must deduct full 30 Credits');
  assert.equal(afterInternalUpgrade.target_fee_paid, 30);
});

await runStep('8. Public/Admin read RPCs & trimmed operator_note revision consistency', async () => {
  const effPub = (await asActor(ownerA, 'select public.get_effective_business_policy() as p')).rows[0].p;
  assert.equal(effPub.min_credits_to_post_app, 0);
  assert.equal(effPub.slot_fees.slot_1, 0);
  assert.deepEqual(effPub.target_fees, { target_12: 0, target_15: 10, target_20: 30 });

  const cfgNow = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg')).rows[0].cfg;
  const trimReqId = '99999999-9999-9999-9999-999999999902';
  const schedTrimmed = (await asActor(adminId, `
    select public.admin_schedule_business_policy(
      p_effective_from => (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 7,
      p_operator_note => '   Ghi chu co khoang trang   ',
      p_expected_revision => '${cfgNow.revision}',
      p_request_id => '${trimReqId}'::uuid,
      p_min_credits_to_post_app => 15,
      p_slot_fee_1 => 5,
      p_target_15_fee => 12,
      p_target_20_fee => 24
    ) as cfg
  `)).rows[0].cfg;

  const listRes = (await asActor(adminId, 'select public.admin_list_business_policies() as l')).rows[0].l;
  assert.ok(listRes.active_policy && Array.isArray(listRes.future_policies) && listRes.future_policies.length >= 2);
  const scheduledItem = listRes.future_policies.find(i => i.operator_note === 'Ghi chu co khoang trang');
  assert.ok(scheduledItem, 'Stored operator_note must be trimmed');
  assert.equal(scheduledItem.min_credits_to_post_app, 15);
  assert.equal(scheduledItem.slot_fees.slot_1, 5);

  const histRes = (await asActor(adminId, 'select public.admin_get_business_policy_history(20, null) as h')).rows[0].h;
  assert.ok(Array.isArray(histRes.items) && histRes.items.length >= 2);
  assert.equal(histRes.items[0].new_row.min_credits_to_post_app, 15);
  assert.equal(histRes.items[0].new_row.slot_fee_1, 5);
  assert.equal(histRes.items[0].new_row.target_15_fee, 12);
  assert.equal(histRes.items[0].new_row.target_20_fee, 24);
  assert.ok(schedTrimmed.revision);
});

console.log('ALL CAMPAIGN POSTING BUSINESS POLICY TESTS PASSED');
