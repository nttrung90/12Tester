import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

const adminId = '11111111-1111-1111-1111-111111111111';
const lockedAdminId = '22222222-2222-2222-2222-222222222222';
const modId = '33333333-3333-3333-3333-333333333333';
const memberId = '44444444-4444-4444-4444-444444444444';

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

console.log('=== KHỞI TẠO PGLITE VÀ NẠP MIGRATIONS 062 - 070 ===');
const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
  .filter(n => /^\d+.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62 && Number(n.slice(0, 3)) <= 70)
  .sort();

for (const name of migrationFiles) {
  console.log(`Applying ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

console.log('=== NẠP STAGED MIGRATION 071 BUSINESS POLICIES FOUNDATION & CONSUMERS ===');
await db.exec(read('supabase/migrations/071_business_policies.sql'));

// Seed accounts
await db.exec(`
  insert into auth.users (id, email) values
    ('${adminId}', 'admin@test.local'),
    ('${lockedAdminId}', 'locked_admin@test.local'),
    ('${modId}', 'mod@test.local'),
    ('${memberId}', 'member@test.local')
  on conflict (id) do nothing;

  insert into public.profiles (id, email, display_name, credits, role, is_locked) values
    ('${adminId}', 'admin@test.local', 'Admin', 1000, 'admin', false),
    ('${lockedAdminId}', 'locked_admin@test.local', 'Locked Admin', 1000, 'admin', true),
    ('${modId}', 'mod@test.local', 'Moderator', 1000, 'moderator', false),
    ('${memberId}', 'member@test.local', 'Member', 1000, 'member', false)
  on conflict (id) do update set
    role = excluded.role,
    is_locked = excluded.is_locked;
`);

console.log('=== BẮT ĐẦU KIỂM THỬ BUSINESS POLICIES FOUNDATION ===');

let baselineConfig;

// 1. Baseline inspection
await runStep('1. Baseline configuration defaults & shape', async () => {
  const res = await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;');
  baselineConfig = res.rows[0].cfg;
  assert.ok(baselineConfig.today, 'Có today');
  assert.equal(baselineConfig.timezone, 'Asia/Ho_Chi_Minh');
  assert.ok(baselineConfig.revision, 'Có revision hash');
  assert.equal(baselineConfig.future_policies.length, 0, 'Chưa có lịch tương lai');

  const active = baselineConfig.active_policy;
  assert.ok(active, 'Có active_policy');
  assert.equal(active.is_baseline, true, 'is_baseline = true');
  assert.equal(active.effective_from, null, 'effective_from null for baseline');
  assert.equal(active.welcome_credits, 100, 'welcome_credits default 100');
  assert.deepEqual(active.daily_penalty_tiers, { tier1_pct: 1, tier2_pct: 2, tier3_pct: 5, tier4_pct: 10 });
  assert.equal(active.push_fee, 100, 'push_fee default 100');
  assert.deepEqual(active.slot_fees, { slot_2: 50, slot_3: 100, slot_4_plus: 200, renewal_days: 30 });
  assert.deepEqual(active.report_rewards, { provisional_reward: 10, review_tester_bonus: 2, review_owner_bonus: 2 });
  assert.deepEqual(active.app_update_rewards, { reward_per_claim: 6, max_claims: 20, reserved_budget: 120 });
});

// 2. Role guards
await runStep('2. Admin permission guards reject non-admin & locked admin', async () => {
  for (const [name, uid] of [['member', memberId], ['moderator', modId], ['lockedAdmin', lockedAdminId]]) {
    await assert.rejects(
      asActor(uid, 'select public.admin_get_business_policy_config();'),
      /FORBIDDEN/,
      `${name} must be rejected from admin_get_business_policy_config`
    );
    await assert.rejects(
      asActor(uid, `select public.admin_schedule_business_policy(current_date + 1, 'test', '${baselineConfig.revision}');`),
      /FORBIDDEN/,
      `${name} must be rejected from admin_schedule_business_policy`
    );
    await assert.rejects(
      asActor(uid, `select public.admin_cancel_business_policy(current_date + 1, 'test', '${baselineConfig.revision}');`),
      /FORBIDDEN/,
      `${name} must be rejected from admin_cancel_business_policy`
    );
    await assert.rejects(
      asActor(uid, 'select public.admin_get_business_policy_history();'),
      /FORBIDDEN/,
      `${name} must be rejected from admin_get_business_policy_history`
    );
  }

  // Anon rejected
  await assert.rejects(
    asActor(null, 'select public.admin_get_business_policy_config();', 'anon'),
    /(UNAUTHORIZED|permission denied)/,
    'anon must be rejected from admin RPC'
  );
});

// 3. Public authenticated quote & effective policy
await runStep('3. Public authenticated get_effective_business_policy and get_business_quote', async () => {
  const effRes = await asActor(memberId, 'select public.get_effective_business_policy() as pol;');
  const eff = effRes.rows[0].pol;
  assert.equal(eff.welcome_credits, 100);
  assert.equal(eff.push_fee, 100);
  assert.equal(eff.operator_note, undefined, 'operator_note must be excluded from public policy DTO');

  // Direct table select on business_policies denied for authenticated user
  await assert.rejects(
    asActor(memberId, 'select * from public.business_policies;'),
    /permission denied/,
    'Direct SELECT on business_policies must be denied for authenticated user'
  );

  // Quote push
  const qPush = (await asActor(memberId, "select public.get_business_quote('push') as q;")).rows[0].q;
  assert.equal(qPush.quote_type, 'push');
  assert.equal(qPush.cost, 100);
  assert.ok(qPush.revision, 'Quote has opaque revision token');
  assert.equal(qPush.revision, qPush.quote_token, 'Revision matches quote_token');

  // Quote slot 2
  const qSlot = (await asActor(memberId, "select public.get_business_quote('slot', '{\"slot_number\": 2}') as q;")).rows[0].q;
  assert.equal(qSlot.cost, 50);
  assert.equal(qSlot.renewal_days, 30);

  // Quote slot 4
  const qSlot4 = (await asActor(memberId, "select public.get_business_quote('slot', '{\"slot_number\": 4}') as q;")).rows[0].q;
  assert.equal(qSlot4.cost, 200);

  // Quote app_update
  const qUpdate = (await asActor(memberId, "select public.get_business_quote('app_update') as q;")).rows[0].q;
  assert.equal(qUpdate.reward_per_claim, 6);
  assert.equal(qUpdate.max_claims, 20);
  assert.equal(qUpdate.reserved_budget, 120);

  // Quote all
  const qAll = (await asActor(memberId, "select public.get_business_quote('all') as q;")).rows[0].q;
  assert.equal(qAll.push_fee, 100);
  assert.equal(qAll.slot_fees.slot_2, 50);

  // Anon rejected from get_business_quote
  await assert.rejects(
    asActor(null, "select public.get_business_quote('push');", 'anon'),
    /(permission denied|NOT_AUTHENTICATED)/
  );
});

// 4. Schema & Range validation
await runStep('4. Range, date and CAS validation', async () => {
  // Past date rejected
  await assert.rejects(
    asActor(adminId, `select public.admin_schedule_business_policy(current_date, 'note', '${baselineConfig.revision}');`),
    /BUSINESS_POLICY_FUTURE_DATE_REQUIRED/
  );

  // Wrong revision rejected
  await assert.rejects(
    asActor(adminId, `select public.admin_schedule_business_policy(current_date + 5, 'note', 'wrong_revision');`),
    /BUSINESS_POLICY_CONFLICT/
  );

  // Empty note rejected
  await assert.rejects(
    asActor(adminId, `select public.admin_schedule_business_policy(current_date + 5, '   ', '${baselineConfig.revision}');`),
    /BUSINESS_POLICY_NOTE_REQUIRED/
  );

  // Out of bound welcome credits
  await assert.rejects(
    asActor(adminId, `select public.admin_schedule_business_policy(current_date + 5, 'note', '${baselineConfig.revision}', -1);`),
    /BUSINESS_POLICY_INVALID_WELCOME_CREDITS/
  );

  // Unordered penalty tiers (e.g. tier1 = 5, tier2 = 2)
  await assert.rejects(
    asActor(adminId, `select public.admin_schedule_business_policy(current_date + 5, 'note', '${baselineConfig.revision}', 100, 5, 2, 5, 10);`),
    /BUSINESS_POLICY_INVALID_PENALTY_TIERS/
  );

  // Invalid push fee
  await assert.rejects(
    asActor(adminId, `select public.admin_schedule_business_policy(current_date + 5, 'note', '${baselineConfig.revision}', 100, 1, 2, 5, 10, 0);`),
    /BUSINESS_POLICY_INVALID_PUSH_FEE/
  );
});

// 5. Future schedule, update & CAS conflict
let scheduledConfig;
let targetDate;
await runStep('5. Schedule future policy and verify CAS & audit', async () => {
  const todayRes = await db.query("select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 5, 'YYYY-MM-DD') as target_date;");
  targetDate = todayRes.rows[0].target_date;

  const res = await asActor(
    adminId,
    `select public.admin_schedule_business_policy(
      '${targetDate}',
      'Tang phi push va slot thang sau',
      '${baselineConfig.revision}',
      150, -- welcome_credits
      2, 3, 6, 12, -- tiers
      120, -- push_fee
      60, 120, 250, 45, -- slot fees & renewal
      15, 3, 3, -- report rewards
      8, 25 -- app update
    ) as cfg;`
  );

  scheduledConfig = res.rows[0].cfg;
  assert.notEqual(scheduledConfig.revision, baselineConfig.revision, 'Revision must change on schedule');
  assert.equal(scheduledConfig.future_policies.length, 1, 'Co 1 chinh sach tuong lai');

  const fp = scheduledConfig.future_policies[0];
  assert.equal(fp.push_fee, 120);
  assert.equal(fp.welcome_credits, 150);
  assert.equal(fp.slot_fees.renewal_days, 45);
  assert.equal(fp.app_update_rewards.reserved_budget, 200); // 8 * 25 = 200

  // Active policy unchanged today
  assert.equal(scheduledConfig.active_policy.push_fee, 100);
});

// 6. Cancellation & History pagination
await runStep('6. Schedule second future date, cancel it, and verify history pagination', async () => {
  const date2Res = await db.query("select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 10, 'YYYY-MM-DD') as date2;");
  const date2 = date2Res.rows[0].date2;

  // Schedule date2
  const res2 = await asActor(
    adminId,
    `select public.admin_schedule_business_policy(
      '${date2}',
      'Chinh sach dot 2',
      '${scheduledConfig.revision}',
      100, 1, 2, 5, 10, 100, 50, 100, 200, 30, 10, 2, 2, 6, 20
    ) as cfg;`
  );
  const cfg2 = res2.rows[0].cfg;
  assert.equal(cfg2.future_policies.length, 2);

  // Cancel date2
  const cancelRes = await asActor(
    adminId,
    `select public.admin_cancel_business_policy(
      '${date2}',
      'Huy bo chinh sach dot 2',
      '${cfg2.revision}'
    ) as cfg;`
  );
  const cfgAfterCancel = cancelRes.rows[0].cfg;
  assert.equal(cfgAfterCancel.future_policies.length, 1, 'Only first future policy remains');
  assert.equal(cfgAfterCancel.future_policies[0].effective_from, targetDate);

  // Check history
  const histRes = await asActor(adminId, 'select public.admin_get_business_policy_history(2) as hist;');
  const hist = histRes.rows[0].hist;
  assert.ok(hist.items.length >= 2, 'History has items');
  assert.equal(hist.has_more, true, 'has_more is true with limit 2');
  assert.ok(hist.next_cursor, 'next_cursor returned');

  // Fetch next page
  const page2Res = await asActor(adminId, `select public.admin_get_business_policy_history(2, ${hist.next_cursor}) as hist;`);
  const page2 = page2Res.rows[0].hist;
  assert.ok(page2.items.length >= 1, 'Page 2 has items');
});

// 7. Anti-tamper triggers & RLS
await runStep('7. Anti-tamper triggers prevent history mutation and active policy delete', async () => {
  // History table update rejected
  await assert.rejects(
    db.query("update public.business_policy_changes set operator_note = 'hack';"),
    /BUSINESS_POLICY_HISTORY_READ_ONLY/
  );

  // History table delete rejected
  await assert.rejects(
    db.query('delete from public.business_policy_changes;'),
    /BUSINESS_POLICY_HISTORY_READ_ONLY/
  );

  // Active baseline policy delete rejected
  await assert.rejects(
    db.query("delete from public.business_policies where effective_from = date '-infinity';"),
    /BUSINESS_POLICY_CANNOT_DELETE_ACTIVE_OR_PAST/
  );

  // Slot purchase receipts user isolation
  await db.exec(`
    insert into public.slot_purchase_receipts (user_id, client_request_id, slot_number, cost_credits, balance_after, expires_at)
    values ('${memberId}', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 2, 50, 950, now() + interval '30 days');
  `);

  // Member can see own receipt
  const memberOwn = (await asActor(memberId, 'select count(*) as cnt from public.slot_purchase_receipts;')).rows[0].cnt;
  assert.equal(Number(memberOwn), 1);

  // Other user cannot see member's receipt
  const adminSee = (await asActor(adminId, 'select count(*) as cnt from public.slot_purchase_receipts;')).rows[0].cnt;
  assert.equal(Number(adminSee), 0);
});

// 8. Admin RequestID Replay, Mismatch, Finite Bounds & Bounded List
await runStep('8. Admin RequestID replay, mismatch detection, finite bounds & cursor list', async () => {
  const reqId1 = '88888888-8888-8888-8888-888888888881';
  const reqId2 = '88888888-8888-8888-8888-888888888882';
  const curCfg = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;')).rows[0].cfg;
  const d3 = (await db.query("select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 15, 'YYYY-MM-DD') as d;")).rows[0].d;

  // 8.1 Schedule with request_id
  const sched1 = (await asActor(
    adminId,
    `select public.admin_schedule_business_policy('${d3}', 'Note 8', '${curCfg.revision}', 100, 1, 2, 5, 10, 100, 50, 100, 200, 30, 10, 2, 2, 6, 20, '${reqId1}') as cfg;`
  )).rows[0].cfg;
  assert.ok(sched1.revision);

  // 8.2 Replay same request_id with same payload returns cached result (even with old revision!)
  const replaySched = (await asActor(
    adminId,
    `select public.admin_schedule_business_policy('${d3}', 'Note 8', 'stale_or_wrong_rev', 100, 1, 2, 5, 10, 100, 50, 100, 200, 30, 10, 2, 2, 6, 20, '${reqId1}') as cfg;`
  )).rows[0].cfg;
  assert.equal(replaySched.revision, sched1.revision, 'Replay must return exact same config');

  // 8.3 Mismatched payload with same request_id throws REQUEST_ID_CONFLICT
  await assert.rejects(
    asActor(
      adminId,
      `select public.admin_schedule_business_policy('${d3}', 'DIFFERENT NOTE', 'any_rev', 100, 1, 2, 5, 10, 100, 50, 100, 200, 30, 10, 2, 2, 6, 20, '${reqId1}');`
    ),
    /REQUEST_ID_CONFLICT/
  );

  // 8.4 Finite future bounds (> 3650 days) rejected
  await assert.rejects(
    asActor(
      adminId,
      `select public.admin_schedule_business_policy((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 4000, 'Too far', '${sched1.revision}');`
    ),
    /BUSINESS_POLICY_FUTURE_DATE_REQUIRED/
  );

  // 8.5 Cancel with request_id and replay
  const cancel1 = (await asActor(
    adminId,
    `select public.admin_cancel_business_policy('${d3}', 'Cancel Note 8', '${sched1.revision}', '${reqId2}') as cfg;`
  )).rows[0].cfg;

  // Replay cancel
  const replayCancel = (await asActor(
    adminId,
    `select public.admin_cancel_business_policy('${d3}', 'Cancel Note 8', 'wrong_rev', '${reqId2}') as cfg;`
  )).rows[0].cfg;
  assert.equal(replayCancel.revision, cancel1.revision);

  // Mismatched cancel payload
  await assert.rejects(
    asActor(
      adminId,
      `select public.admin_cancel_business_policy('${d3}', 'DIFFERENT CANCEL NOTE', 'any', '${reqId2}');`
    ),
    /REQUEST_ID_CONFLICT/
  );

  // 8.6 Bounded list with cursor
  const listP1 = (await asActor(adminId, 'select public.admin_list_business_policies(1) as lst;')).rows[0].lst;
  assert.ok(listP1.future_policies.length <= 1, 'Bounded to limit 1');
});

// 9. Consumer: Welcome Credits
await runStep('9. Consumer: handle_new_user with policy credits, ledger consistency & 0 credits', async () => {
  const newU1 = '77777777-7777-7777-7777-777777777771';
  const newU2 = '77777777-7777-7777-7777-777777777772';

  // 9.1 Default welcome credits (100)
  await db.exec(`insert into auth.users (id, email) values ('${newU1}', 'new1@test.local');`);
  const prof1 = (await db.query(`select credits from public.profiles where id = '${newU1}';`)).rows[0];
  assert.equal(prof1.credits, 100, 'New user receives 100 credits');

  const log1 = (await db.query(`select amount, note, balance from public.credit_logs where user_id = '${newU1}' and action = 'welcome';`)).rows[0];
  assert.equal(log1.amount, 100);
  assert.equal(log1.balance, 100);
  assert.equal(log1.note, 'Thưởng 100 Credit khi mở tài khoản');

  // 9.2 Existing user untouched
  await db.exec(`update public.profiles set credits = 555 where id = '${newU1}';`);
  const logCountBefore = (await db.query(`select count(*) as cnt from public.credit_logs where user_id = '${newU1}';`)).rows[0].cnt;

  await db.exec(`insert into auth.users (id, email) values ('${newU1}', 'new1@test.local') on conflict (id) do nothing;`);

  const prof1After = (await db.query(`select credits from public.profiles where id = '${newU1}';`)).rows[0];
  assert.equal(prof1After.credits, 555, 'Existing user balance remains untouched');
  const logCountAfter = (await db.query(`select count(*) as cnt from public.credit_logs where user_id = '${newU1}';`)).rows[0].cnt;
  assert.equal(logCountAfter, logCountBefore, 'No duplicate welcome log added for existing user');

  // 9.3 Policy with 0 welcome credits
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set welcome_credits = 0 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  await db.exec(`insert into auth.users (id, email) values ('${newU2}', 'new2@test.local');`);
  const prof2 = (await db.query(`select credits from public.profiles where id = '${newU2}';`)).rows[0];
  assert.equal(prof2.credits, 0, 'New user receives 0 credits when policy = 0');

  const log2 = (await db.query(`select amount, note, balance from public.credit_logs where user_id = '${newU2}' and action = 'welcome';`)).rows[0];
  assert.equal(log2.amount, 0);
  assert.equal(log2.balance, 0);
  assert.equal(log2.note, 'Thưởng 0 Credit khi mở tài khoản');

  // Reset baseline welcome_credits to 100
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set welcome_credits = 100 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);
});

// 10. Consumer: Push Campaign
await runStep('10. Consumer: push_campaign legacy & v2 with fee changes, replays and bounds', async () => {
  const campId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1';
  const campOther = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2';
  const pushReq1 = '99999999-9999-9999-9999-999999999991';
  const pushReq2 = '99999999-9999-9999-9999-999999999992';

  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
    values
      ('${campId}', '${memberId}', 'Test App 1', 'com.test.app1', 'open', 10, 1, now() - interval '2 days'),
      ('${campOther}', '${adminId}', 'Test App 2', 'com.test.app2', 'open', 10, 1, now() - interval '2 days')
    on conflict (id) do nothing;
    update public.profiles set credits = 1000 where id = '${memberId}';
  `);

  // 10.1 Legacy push with default fee (100)
  const pushRes1 = (await asActor(memberId, `select public.push_campaign('${campId}', '${pushReq1}') as res;`)).rows[0].res;
  assert.equal(pushRes1.success, true);
  assert.equal(pushRes1.charged_credits, 100);
  assert.equal(pushRes1.replayed, false);

  const bal1 = (await db.query(`select credits from public.profiles where id = '${memberId}';`)).rows[0].credits;
  assert.equal(bal1, 900);

  // 10.2 Replay same push_campaign returns cached receipt
  const pushReplay = (await asActor(memberId, `select public.push_campaign('${campId}', '${pushReq1}') as res;`)).rows[0].res;
  assert.equal(pushReplay.success, true);
  assert.equal(pushReplay.replayed, true);
  assert.equal(pushReplay.charged_credits, 100);
  assert.equal((await db.query(`select credits from public.profiles where id = '${memberId}';`)).rows[0].credits, 900);

  // 10.3 Mismatched campaign rejects
  await assert.rejects(
    asActor(memberId, `select public.push_campaign('${campOther}', '${pushReq1}');`),
    /REQUEST_ID_CONFLICT/
  );

  // 10.4 Policy changes push_fee to 150
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set push_fee = 150 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  // Legacy call without update fails-closed
  await assert.rejects(
    asActor(memberId, `select public.push_campaign('${campId}', '${pushReq2}');`),
    /CLIENT_UPDATE_REQUIRED/
  );

  // BUT prior receipt replay with pushReq1 across policy change STILL succeeds!
  const pushReplayOld = (await asActor(memberId, `select public.push_campaign('${campId}', '${pushReq1}') as res;`)).rows[0].res;
  assert.equal(pushReplayOld.replayed, true);
  assert.equal(pushReplayOld.charged_credits, 100);

  // v2 without expected cost/token for fresh purchase fails
  await assert.rejects(
    asActor(memberId, `select public.push_campaign_v2('${campId}', '${pushReq2}');`),
    /BUSINESS_POLICY_CONFLICT/
  );

  const quotePush150 = (await asActor(memberId, "select public.get_business_quote('push') as q;")).rows[0].q;

  // v2 with price mismatch (expecting 100) fails
  await assert.rejects(
    asActor(memberId, `select public.push_campaign_v2('${campId}', '${pushReq2}', 100, '${quotePush150.revision}');`),
    /BUSINESS_POLICY_PRICE_MISMATCH/
  );

  // v2 with correct price 150 succeeds
  const pushV2Res = (await asActor(memberId, `select public.push_campaign_v2('${campId}', '${pushReq2}', 150, '${quotePush150.revision}') as res;`)).rows[0].res;
  assert.equal(pushV2Res.success, true);
  assert.equal(pushV2Res.charged_credits, 150);
  assert.equal(pushV2Res.balance_after, 750);

  // Check relaxed check constraint stored 150 in receipt
  const rcpt = (await db.query(`select cost_credits from public.campaign_push_receipts where client_request_id = '${pushReq2}';`)).rows[0];
  assert.equal(rcpt.cost_credits, 150);

  // Replay v2
  const pushV2Replay = (await asActor(memberId, `select public.push_campaign_v2('${campId}', '${pushReq2}', 150, '${quotePush150.revision}') as res;`)).rows[0].res;
  assert.equal(pushV2Replay.replayed, true);

  // Reset push fee
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set push_fee = 100 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);
});

// 11. Consumer: User Slots
await runStep('11. Consumer: unlock_or_renew_slot legacy & v2 with fee/days changes, replays, prerequisites', async () => {
  const slotReq1 = 'aaaaaaaa-1111-1111-1111-111111111111';
  const slotReq2 = 'aaaaaaaa-2222-2222-2222-222222222222';
  const slotReq3 = 'aaaaaaaa-3333-3333-3333-333333333333';

  await db.exec(`
    delete from public.slot_purchase_receipts where user_id = '${memberId}';
    delete from public.user_slots where user_id = '${memberId}';
    update public.profiles set credits = 1000 where id = '${memberId}';
  `);

  // 11.1 Slot 1 is invalid
  await assert.rejects(
    asActor(memberId, 'select public.unlock_or_renew_slot(1);'),
    /INVALID_SLOT_NUMBER/
  );

  const quoteSlot3 = (await asActor(memberId, 'select public.get_business_quote(\'slot\', \'{"slot_number": 3}\') as q;')).rows[0].q;

  // 11.2 Slot 3 requires Slot 2 active first
  await assert.rejects(
    asActor(memberId, `select public.unlock_or_renew_slot_v2(3, '${slotReq1}', 100, '${quoteSlot3.revision}');`),
    /PREVIOUS_SLOT_NOT_ACTIVE/
  );

  // 11.3 Legacy unlock slot 2 under default policy (cost 50, 30 days)
  const slotRes1 = (await asActor(memberId, 'select public.unlock_or_renew_slot(2) as res;')).rows[0].res;
  assert.equal(slotRes1.success, true);
  assert.equal(slotRes1.slot_number, 2);
  assert.equal(slotRes1.cost, 50);
  assert.equal(slotRes1.balance, 950);

  const quoteSlot2 = (await asActor(memberId, 'select public.get_business_quote(\'slot\', \'{"slot_number": 2}\') as q;')).rows[0].q;

  // 11.4 v2 renew slot 2 cumulatively with request_id
  const slotRes2 = (await asActor(memberId, `select public.unlock_or_renew_slot_v2(2, '${slotReq2}', 50, '${quoteSlot2.revision}') as res;`)).rows[0].res;
  assert.equal(slotRes2.success, true);
  assert.equal(slotRes2.cost, 50);
  assert.equal(slotRes2.balance, 900);

  // Replay slotReq2
  const slotReplay = (await asActor(memberId, `select public.unlock_or_renew_slot_v2(2, '${slotReq2}', 50, '${quoteSlot2.revision}') as res;`)).rows[0].res;
  assert.equal(slotReplay.replayed, true);
  assert.equal(slotReplay.cost, 50);
  assert.equal(slotReplay.balance, 900);

  // Mismatched slot on same request_id rejected
  await assert.rejects(
    asActor(memberId, `select public.unlock_or_renew_slot_v2(3, '${slotReq2}', 50, '${quoteSlot2.revision}');`),
    /REQUEST_ID_CONFLICT/
  );

  // 11.5 Policy changes slot 2 fee to 75 and renewal_days to 45
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set slot_fee_2 = 75, slot_renewal_days = 45 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  // Stale legacy call fails-closed
  await assert.rejects(
    asActor(memberId, 'select public.unlock_or_renew_slot(2);'),
    /CLIENT_UPDATE_REQUIRED/
  );

  // Replay prior receipt slotReq2 across policy change still succeeds
  const priorReplay = (await asActor(memberId, `select public.unlock_or_renew_slot_v2(2, '${slotReq2}') as res;`)).rows[0].res;
  assert.equal(priorReplay.replayed, true);
  assert.equal(priorReplay.cost, 50);

  const quoteSlot2_75 = (await asActor(memberId, 'select public.get_business_quote(\'slot\', \'{"slot_number": 2}\') as q;')).rows[0].q;

  // v2 with price mismatch rejected
  await assert.rejects(
    asActor(memberId, `select public.unlock_or_renew_slot_v2(2, '${slotReq3}', 50, '${quoteSlot2_75.revision}');`),
    /BUSINESS_POLICY_PRICE_MISMATCH/
  );

  // v2 with new price 75 succeeds and applies 45 renewal days
  const slotRes3 = (await asActor(memberId, `select public.unlock_or_renew_slot_v2(2, '${slotReq3}', 75, '${quoteSlot2_75.revision}') as res;`)).rows[0].res;
  assert.equal(slotRes3.success, true);
  assert.equal(slotRes3.cost, 75);
  assert.equal(slotRes3.balance, 825);
  assert.equal(slotRes3.renewal_days, 45);

  // Reset slot policy
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set slot_fee_2 = 50, slot_renewal_days = 30 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  // Replay slotReq3 after policy was reverted back to 30: MUST still report snapshot 45 days!
  const replaySlot3Reverted = (await asActor(memberId, `select public.unlock_or_renew_slot_v2(2, '${slotReq3}') as res;`)).rows[0].res;
  assert.equal(replaySlot3Reverted.replayed, true);
  assert.equal(replaySlot3Reverted.renewal_days, 45, 'Replay must retain snapshot 45 days even after policy reverted to 30');
});

// 12. Compatibility with 070 and common admin audit integration
await runStep('12. 070 integration & record_admin_action_event audit integration', async () => {
  const auditReq1 = 'bbbbbbbb-1111-1111-1111-111111111111';
  const auditReq2 = 'bbbbbbbb-2222-2222-2222-222222222222';
  const curCfg = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;')).rows[0].cfg;
  const d4 = (await db.query("select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 20, 'YYYY-MM-DD') as d;")).rows[0].d;

  // Schedule with request_id under 070
  await asActor(
    adminId,
    `select public.admin_schedule_business_policy('${d4}', '070 Audit Integration Test', '${curCfg.revision}', 100, 1, 2, 5, 10, 100, 50, 100, 200, 30, 10, 2, 2, 6, 20, '${auditReq1}');`
  );

  // Check admin_action_events in 070
  const actionRes1 = (await db.query(`select * from public.admin_action_events where request_id = '${auditReq1}';`)).rows[0];
  assert.ok(actionRes1, 'Admin action event must be recorded in 070 admin_action_events');
  assert.equal(actionRes1.action, 'schedule_business_policy');
  assert.equal(actionRes1.target_type, 'business_policy');

  // Cancel with request_id under 070
  const curCfg2 = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;')).rows[0].cfg;
  await asActor(
    adminId,
    `select public.admin_cancel_business_policy('${d4}', 'Cancel 070 Audit Test', '${curCfg2.revision}', '${auditReq2}');`
  );

  const actionRes2 = (await db.query(`select * from public.admin_action_events where request_id = '${auditReq2}';`)).rows[0];
  assert.ok(actionRes2, 'Cancel admin action event must be recorded in 070 admin_action_events');
  assert.equal(actionRes2.action, 'cancel_business_policy');
});

// 13. Quote activation token rotation & stale quote rejection with date seam
await runStep('13. Quote activation token rotation & stale quote rejection with date seam', async () => {
  const slotReqOriginal = 'cccccccc-1111-1111-1111-111111111111';
  const slotReqFresh = 'cccccccc-2222-2222-2222-222222222222';

  // Balance before
  const balBefore = (await db.query(`select credits from public.profiles where id = '${memberId}';`)).rows[0].credits;

  // 13.1 Get quote before activation (slot 2, cost 50, 30 days)
  const quoteBefore = (await asActor(memberId, 'select public.get_business_quote(\'slot\', \'{"slot_number": 2}\') as q;')).rows[0].q;
  assert.equal(quoteBefore.cost, 50);
  assert.equal(quoteBefore.renewal_days, 30);
  assert.ok(quoteBefore.revision);

  // 13.2 Complete purchase under quoteBefore
  const origPurchase = (await asActor(
    memberId,
    `select public.unlock_or_renew_slot_v2(2, '${slotReqOriginal}', 50, '${quoteBefore.revision}') as res;`
  )).rows[0].res;
  assert.equal(origPurchase.success, true);
  assert.equal(origPurchase.replayed, false);
  const balAfterOrig = (await db.query(`select credits from public.profiles where id = '${memberId}';`)).rows[0].credits;
  assert.equal(balAfterOrig, balBefore - 50);

  // 13.3 Schedule future policy tomorrow with same cost 50 but changed terms: renewal_days = 1
  const curCfg = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;')).rows[0].cfg;
  const tomorrow = (await db.query("select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1, 'YYYY-MM-DD') as d;")).rows[0].d;

  await asActor(
    adminId,
    `select public.admin_schedule_business_policy(
      '${tomorrow}',
      'Same cost 50 but 1 day renewal',
      '${curCfg.revision}',
      100, 1, 2, 5, 10, 100, 50, 100, 200, 1, -- renewal_days = 1!
      10, 2, 2, 6, 20
    );`
  );

  // 13.4 Simulate midnight activation via date seam in _get_effective_business_policy_row
  await db.exec(`
    create or replace function public._get_effective_business_policy_row(p_date date default null::date)
    returns public.business_policies language sql stable security definer set search_path=''
    as $$
      select p.* from public.business_policies p
      where p.effective_from <= coalesce(p_date, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1)
      order by p.effective_from desc limit 1
    $$;
  `);

  // 13.5 Read new quote after activation: token MUST rotate even though admin CAS stayed same
  const quoteAfter = (await asActor(memberId, 'select public.get_business_quote(\'slot\', \'{"slot_number": 2}\') as q;')).rows[0].q;
  assert.equal(quoteAfter.cost, 50);
  assert.equal(quoteAfter.renewal_days, 1);
  assert.notEqual(quoteAfter.revision, quoteBefore.revision, 'Quote token MUST rotate at scheduled activation with changed terms');

  // 13.6 Fresh purchase attempt using STALE quote token quoteBefore.revision MUST REJECT
  await assert.rejects(
    asActor(
      memberId,
      `select public.unlock_or_renew_slot_v2(2, '${slotReqFresh}', 50, '${quoteBefore.revision}');`
    ),
    /BUSINESS_POLICY_CONFLICT/,
    'Stale quote actual slot must reject'
  );

  // Verify NO CHARGE occurred
  const balAfterReject = (await db.query(`select credits from public.profiles where id = '${memberId}';`)).rows[0].credits;
  assert.equal(balAfterReject, balAfterOrig, 'Stale quote rejection must cause NO charge');

  const freshReceiptCount = (await db.query(`select count(*) as cnt from public.slot_purchase_receipts where client_request_id = '${slotReqFresh}';`)).rows[0].cnt;
  assert.equal(Number(freshReceiptCount), 0, 'No receipt created on rejection');

  // 13.7 Prior receipt replay: slotReqOriginal still replays successfully after activation
  const replayAfterActivation = (await asActor(
    memberId,
    `select public.unlock_or_renew_slot_v2(2, '${slotReqOriginal}', 50, '${quoteBefore.revision}') as res;`
  )).rows[0].res;
  assert.equal(replayAfterActivation.replayed, true, 'Original receipt must still replay after activation');
  assert.equal(replayAfterActivation.cost, 50);

  // 13.8 Restore original _get_effective_business_policy_row and clean up
  await db.exec(`
    create or replace function public._get_effective_business_policy_row(p_date date default null::date)
    returns public.business_policies language plpgsql stable security definer set search_path=''
    as $$
    declare
        v_date date := coalesce(p_date, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date);
        v_policy public.business_policies;
    begin
        if not isfinite(v_date) then raise exception 'BUSINESS_POLICY_INVALID_DATE'; end if;
        select p.* into v_policy from public.business_policies p where p.effective_from <= v_date order by p.effective_from desc limit 1;
        if v_policy.id is null then raise exception 'BUSINESS_POLICY_NOT_FOUND'; end if;
        return v_policy;
    end;
    $$;
  `);

  const curCfg3 = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;')).rows[0].cfg;
  await asActor(adminId, `select public.admin_cancel_business_policy('${tomorrow}', 'Cleanup seam test', '${curCfg3.revision}');`);
});

// 14. Daily Inactivity Penalty Consumer (Tiers, 0% penalty, snapshots, immutability)
await runStep('14. Daily Inactivity Penalty: 0% tier, snapshot terms, and history immutability', async () => {
  const penaltyUser = '55555555-5555-5555-5555-555555555551';
  const evalD1 = (await db.query("select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date - 1, 'YYYY-MM-DD') as d;")).rows[0].d;

  await db.exec(`
    update public.system_provenance_markers
    set marker_time = now() - interval '30 days', marker_date = current_date - 30
    where marker_key = 'coverage_start';

    insert into auth.users (id, email) values ('${penaltyUser}', 'penalty@test.local') on conflict do nothing;
    insert into public.profiles (id, email, display_name, credits, role, last_login_date, daily_missed_streak, created_at)
    values ('${penaltyUser}', 'penalty@test.local', 'Penalty User', 1000, 'member', '2026-10-20', 0, now() - interval '10 days')
    on conflict (id) do update set credits = 1000, daily_missed_streak = 0, last_login_date = '2026-10-20', created_at = now() - interval '10 days';

    -- Predecessor day finalized so carryover_ready is true
    insert into public.user_daily_tasks (user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak)
    values ('${penaltyUser}', '${evalD1}'::date - 1, 'member', now() - interval '2 days', 0, 0)
    on conflict do nothing;
  `);

  // Finalize date D1 with baseline policy (tier 1 = 1%)
  await db.exec(`
    select public._apply_daily_inactivity_penalty('${evalD1}'::date);
  `);

  const taskD1 = (await db.query(`select * from public.user_daily_tasks where user_id = '${penaltyUser}' and task_date = '${evalD1}';`)).rows[0];
  assert.ok(taskD1, 'Task row for D1 must exist');
  assert.equal(taskD1.penalty_percent, 1, 'Tier 1 is 1% under baseline');
  assert.equal(taskD1.penalty_credits, 10, '1% of 1000 is 10');
  assert.ok(taskD1.finalized_at, 'Finalized at set');

  const creditsAfterD1 = (await db.query(`select credits from public.profiles where id = '${penaltyUser}';`)).rows[0].credits;
  assert.equal(creditsAfterD1, 990);

  // Now configure policy with 0% tier 1
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set penalty_tier1_pct = 0 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  // D1 was already finalized: re-running penalty MUST NOT change D1 (history immutability)
  await db.exec(`
    select public._apply_daily_inactivity_penalty('${evalD1}'::date);
  `);

  const taskD1After = (await db.query(`select * from public.user_daily_tasks where user_id = '${penaltyUser}' and task_date = '${evalD1}';`)).rows[0];
  assert.equal(taskD1After.penalty_credits, 10, 'D1 must remain immutable');

  // Now finalize a new user under 0% tier 1
  const zeroUser = '55555555-5555-5555-5555-555555555552';
  await db.exec(`
    insert into auth.users (id, email) values ('${zeroUser}', 'zero@test.local') on conflict do nothing;
    insert into public.profiles (id, email, display_name, credits, role, last_login_date, daily_missed_streak, created_at)
    values ('${zeroUser}', 'zero@test.local', 'Zero User', 500, 'member', '2026-10-20', 0, now() - interval '10 days')
    on conflict (id) do update set credits = 500, daily_missed_streak = 0, last_login_date = '2026-10-20', created_at = now() - interval '10 days';

    -- Predecessor day finalized
    insert into public.user_daily_tasks (user_id, task_date, mode, finalized_at, carry_out_tests, missed_streak)
    values ('${zeroUser}', '${evalD1}'::date - 1, 'member', now() - interval '2 days', 0, 0)
    on conflict do nothing;
  `);

  await db.exec(`
    select public._apply_daily_inactivity_penalty('${evalD1}'::date);
  `);

  const taskD2 = (await db.query(`select * from public.user_daily_tasks where user_id = '${zeroUser}' and task_date = '${evalD1}';`)).rows[0];
  assert.equal(taskD2.penalty_percent, 0, 'Tier 1 is 0%');
  assert.equal(taskD2.penalty_credits, 0, '0% tier must deduct exactly 0 (no min 1 penalty)');

  const creditsZero = (await db.query(`select credits from public.profiles where id = '${zeroUser}';`)).rows[0].credits;
  assert.equal(creditsZero, 500, 'Credits remain 500 with 0% penalty');

  // Reset baseline penalty tiers
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set penalty_tier1_pct = 1 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);
});

// 15. Financial metadata anti-tamper guard
await runStep('15. Financial metadata guard: direct client inserts/updates rejected', async () => {
  const fakeCamp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1';

  // Direct INSERT choosing review_tester_bonus = 100 rejected
  await assert.rejects(
    asActor(
      memberId,
      `insert into public.tester_reports (
        campaign_id, user_id, report_type, content, rating, status, bonus_awarded,
        review_tester_bonus
      ) values (
        '${fakeCamp}', '${memberId}', 'feedback', 'Hacked report', 5, 'pending', 0, 100
      );`
    ),
    /(permission denied|FORBIDDEN_REPORT_METADATA_WRITE)/
  );

  // Direct INSERT choosing provisional_granted_credits = 500 rejected
  await assert.rejects(
    asActor(
      memberId,
      `insert into public.tester_reports (
        campaign_id, user_id, report_type, content, rating, status, bonus_awarded,
        provisional_granted_credits
      ) values (
        '${fakeCamp}', '${memberId}', 'feedback', 'Hacked report', 5, 'pending', 0, 500
      );`
    ),
    /(permission denied|FORBIDDEN_REPORT_METADATA_WRITE)/
  );

  // Same transaction: RPC followed by direct forged UPDATE with SET LOCAL ROLE authenticated
  await assert.rejects(
    (async () => {
      await db.exec('begin;');
      try {
        await db.exec(`select set_config('request.jwt.claim.sub', '${memberId}', true); set local role authenticated;`);
        await db.exec(`update public.tester_reports set review_tester_bonus = 999 where user_id = '${memberId}';`);
        await db.exec('commit;');
      } catch (err) {
        await db.exec('rollback;');
        throw err;
      }
    })(),
    /(FORBIDDEN_REPORT_METADATA_UPDATE|permission denied)/
  );
});

// 16. Report Lifecycle with Snapshots & Non-Default Rewards
await runStep('16. Report Lifecycle: submission snapshots, owner vs staff approval, auto-accept', async () => {
  const campOwner = '66666666-6666-6666-6666-666666666661';
  const testerUser1 = '66666666-6666-6666-6666-666666666662';
  const testerUser2 = '66666666-6666-6666-6666-666666666663';
  const testerUser3 = '66666666-6666-6666-6666-666666666664';
  const testCamp = 'bbbbbbbb-aaaa-aaaa-aaaa-aaaaaaaaaaa1';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${campOwner}', 'owner@test.local'),
      ('${testerUser1}', 'tester1@test.local'),
      ('${testerUser2}', 'tester2@test.local'),
      ('${testerUser3}', 'tester3@test.local')
    on conflict do nothing;

    insert into public.profiles (id, email, display_name, credits, role, is_locked) values
      ('${campOwner}', 'owner@test.local', 'App Owner', 500, 'member', false),
      ('${testerUser1}', 'tester1@test.local', 'App Tester 1', 100, 'member', false),
      ('${testerUser2}', 'tester2@test.local', 'App Tester 2', 100, 'member', false),
      ('${testerUser3}', 'tester3@test.local', 'App Tester 3', 100, 'member', false)
    on conflict (id) do update set credits = 100, is_locked = false;

    update public.profiles set credits = 500 where id = '${campOwner}';
    update public.profiles set credits = 100 where id in ('${testerUser1}', '${testerUser2}', '${testerUser3}');

    insert into public.campaigns (id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
    values ('${testCamp}', '${campOwner}', 'Snapshot Test App', 'com.snapshot.app', 'open', 10, 1, now() - interval '2 days')
    on conflict (id) do nothing;
  `);

  // 16.1 Set policy with non-default rewards: provisional = 15, tester bonus = 5, owner bonus = 4
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set report_provisional_reward = 15,
        report_review_tester_bonus = 5,
        report_review_owner_bonus = 4
    where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  const submitReq1 = 'dddddddd-1111-1111-1111-111111111111';
  const submitRes1 = (await asActor(
    testerUser1,
    `select public.submit_tester_report_v2('app_test', '${testCamp}', 'Great app test', 'https://storage.googleapis.com/test-bucket/reports/${testCamp}/${testerUser1}/screen1.png', 5, '0123456789abcdef', '${submitReq1}') as r;`
  )).rows[0].r;

  assert.equal(submitRes1.success, true);
  assert.equal(submitRes1.provisional_granted, true);
  assert.equal(submitRes1.provisional_amount, 15);

  const reportId1 = submitRes1.report_id;
  const reportRow1 = (await db.query(`select * from public.tester_reports where id = '${reportId1}';`)).rows[0];
  assert.equal(reportRow1.provisional_granted_credits, 15);
  assert.equal(reportRow1.review_tester_bonus, 5);
  assert.equal(reportRow1.review_owner_bonus, 4);

  // Tester received 15 credits provisional
  const testerBal1 = (await db.query(`select credits from public.profiles where id = '${testerUser1}';`)).rows[0].credits;
  assert.equal(testerBal1, 115);

  // 16.2 Now change policy to tester bonus = 10, owner bonus = 8 BEFORE review
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set report_provisional_reward = 25,
        report_review_tester_bonus = 10,
        report_review_owner_bonus = 8
    where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  // True owner approves report with p_bonus = 999999: p_bonus MUST BE IGNORED (no caller minting)!
  // Tester receives OLD snapshot 5 (not 10 and not 999999+5), owner receives OLD snapshot 4 (not 8)
  const appRes1 = (await asActor(campOwner, `select public.approve_tester_report('${reportId1}', 999999) as r;`)).rows[0].r;
  assert.equal(appRes1.success, true);
  assert.equal(appRes1.credits_awarded, 5, 'Tester extra bonus must match old snapshot 5, ignoring p_bonus=999999');
  assert.equal(appRes1.owner_credits_awarded, 4, 'Owner bonus must match old snapshot 4');

  // Verify balances
  const testerBalAfterApp = (await db.query(`select credits from public.profiles where id = '${testerUser1}';`)).rows[0].credits;
  assert.equal(testerBalAfterApp, 120); // 115 + 5 = 120 (NO caller credit minting!)

  const ownerBalAfterApp = (await db.query(`select credits from public.profiles where id = '${campOwner}';`)).rows[0].credits;
  assert.equal(ownerBalAfterApp, 504); // 500 + 4 = 504

  // 16.3 Staff (admin/moderator) approval gives owner bonus = 0; p_bonus = -50 is also ignored
  const submitReq2 = 'dddddddd-2222-2222-2222-222222222222';
  const submitRes2 = (await asActor(
    testerUser2,
    `select public.submit_tester_report_v2('app_test', '${testCamp}', 'Report 2', 'https://storage.googleapis.com/test-bucket/reports/${testCamp}/${testerUser2}/screen2.png', 5, '0123456789abcde1', '${submitReq2}') as r;`
  )).rows[0].r;
  const reportId2 = submitRes2.report_id;

  const appRes2 = (await asActor(adminId, `select public.approve_tester_report('${reportId2}', -50) as r;`)).rows[0].r;
  assert.equal(appRes2.success, true);
  assert.equal(appRes2.credits_awarded, 10, 'Negative p_bonus ignored, snapshot 10 retained');
  assert.equal(appRes2.owner_credits_awarded, 0, 'Staff approval must yield owner bonus 0');

  // 16.4 Auto accept expired reports: grants provisional only + tip, no manual review bonus
  const submitReq3 = 'dddddddd-3333-3333-3333-333333333333';
  const submitRes3 = (await asActor(
    testerUser3,
    `select public.submit_tester_report_v2('app_test', '${testCamp}', 'Report 3', 'https://storage.googleapis.com/test-bucket/reports/${testCamp}/${testerUser3}/screen3.png', 5, '0123456789abcde2', '${submitReq3}') as r;`
  )).rows[0].r;
  const reportId3 = submitRes3.report_id;

  // Backdate report3 to 9 hours ago
  await db.exec(`update public.tester_reports set created_at = now() - interval '9 hours' where id = '${reportId3}';`);

  const autoCount = (await db.query('select public.auto_accept_expired_reports() as cnt;')).rows[0].cnt;
  assert.ok(autoCount >= 1, 'Auto accepted expired reports');

  const reportRow3 = (await db.query(`select * from public.tester_reports where id = '${reportId3}';`)).rows[0];
  assert.equal(reportRow3.status, 'approved');
  assert.equal(reportRow3.bonus_awarded, reportRow3.provisional_granted_credits, 'Auto accept gives no extra manual review bonus');

  // Reset baseline rewards
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set report_provisional_reward = 10,
        report_review_tester_bonus = 2,
        report_review_owner_bonus = 2
    where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);
});

// 17. Zero Provisional Reward, Legacy Clawback Bounds & 070 Warning Revocation
await runStep('17. Zero provisional reward, clawback bounds, and 070 warning revocation', async () => {
  const campOwner = '66666666-6666-6666-6666-666666666661';
  const testerUser4 = '66666666-6666-6666-6666-666666666665';
  const testCamp = 'bbbbbbbb-aaaa-aaaa-aaaa-aaaaaaaaaaa1';

  await db.exec(`
    insert into auth.users (id, email) values ('${testerUser4}', 'tester4@test.local') on conflict do nothing;
    insert into public.profiles (id, email, display_name, credits, role, is_locked)
    values ('${testerUser4}', 'tester4@test.local', 'App Tester 4', 100, 'member', false)
    on conflict (id) do update set credits = 100;
  `);

  // 17.1 Zero provisional reward: submit gives 0 credits, reject claws back 0 credits
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set report_provisional_reward = 0 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  const subZeroReq = 'eeeeeeee-0000-0000-0000-000000000000';
  const subZeroRes = (await asActor(
    testerUser4,
    `select public.submit_tester_report_v2('app_test', '${testCamp}', 'Zero reward test', 'https://storage.googleapis.com/test-bucket/reports/${testCamp}/${testerUser4}/zero.png', 5, '0123456789abcde0', '${subZeroReq}') as r;`
  )).rows[0].r;

  assert.equal(subZeroRes.provisional_granted, false);
  assert.equal(subZeroRes.provisional_amount, 0);

  const balBeforeRejZero = (await db.query(`select credits from public.profiles where id = '${testerUser4}';`)).rows[0].credits;

  // Reject zero-reward report: claws back 0
  const rejZero = (await asActor(campOwner, `select public.reject_tester_report('${subZeroRes.report_id}', 'Not good', 'NOT_GOOD') as r;`)).rows[0].r;
  assert.equal(rejZero.credits_deducted, 0, 'Zero reward report claws back exactly 0');
  const balAfterRejZero = (await db.query(`select credits from public.profiles where id = '${testerUser4}';`)).rows[0].credits;
  assert.equal(balAfterRejZero, balBeforeRejZero, 'No credit deducted');

  // Auto-accept genuine zero reward report: backdate and verify bonus_awarded is 0 (does not fall back to 2)
  const subZeroReq2 = 'eeeeeeee-0000-0000-0000-000000000002';
  const subZeroRes2 = (await asActor(
    testerUser4,
    `select public.submit_tester_report_v2('app_test', '${testCamp}', 'Zero reward auto test', 'https://storage.googleapis.com/test-bucket/reports/${testCamp}/${testerUser4}/zero2.png', 5, '0123456789abcde5', '${subZeroReq2}') as r;`
  )).rows[0].r;
  await db.exec(`update public.tester_reports set created_at = now() - interval '9 hours' where id = '${subZeroRes2.report_id}';`);
  await db.query('select public.auto_accept_expired_reports();');
  const zeroAutoRow = (await db.query(`select * from public.tester_reports where id = '${subZeroRes2.report_id}';`)).rows[0];
  assert.equal(zeroAutoRow.status, 'approved');
  assert.equal(zeroAutoRow.bonus_awarded, 0, 'Genuine zero reward does not fall back to legacy 2');

  // 17.2 Legacy report with provisional_granted_credits = 2: clawback is bounded by 2 (not hardcoded 10)
  const legacyRepId = 'ffffffff-2222-2222-2222-222222222222';
  await db.exec(`
    insert into public.tester_reports (
      id, campaign_id, user_id, report_type, content, rating, status, bonus_awarded,
      provisional_granted_credits, created_at
    ) values (
      '${legacyRepId}', '${testCamp}', '${testerUser4}', 'feedback', 'Legacy report', 5, 'pending', 2, 2, now()
    );
  `);

  const rejLegacy = (await asActor(campOwner, `select public.reject_tester_report('${legacyRepId}', 'Legacy reject', 'REJECT') as r;`)).rows[0].r;
  assert.equal(rejLegacy.credits_deducted, 2, 'Legacy provisional=2 report claws back at most 2, not 10');

  // 17.3 070 Warning Revocation consistency across rejection threshold
  await db.exec(`
    insert into public.user_warnings (id, user_id, warning_type, message, warning_date)
    values ('11111111-9999-9999-9999-999999999991', '${testerUser4}', 'tester_rejection_streak', 'Revoked warning', current_date);

    insert into public.user_warning_revocations (warning_id, revoked_by, reason)
    values ('11111111-9999-9999-9999-999999999991', '${adminId}', 'Mistaken warning');
  `);

  // Count active warnings: should be 0 because the warning is revoked
  const detailRes = (await asActor(adminId, `select public.get_campaign_detail('${testCamp}') as d;`)).rows[0].d;
  assert.ok(detailRes, 'get_campaign_detail succeeds with 070');

  // Reset baseline rewards
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies set report_provisional_reward = 10 where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);
});

// 18. Admin Reports Queue with Projections and Snapshots
await runStep('18. admin_get_tester_reports_queue 070 override with snapshot fields', async () => {
  const queueRes = (await asActor(adminId, 'select public.admin_get_tester_reports_queue(null) as q;')).rows[0].q;
  assert.ok(queueRes.items.length >= 1, 'Queue has items');
  const sample = queueRes.items[0];
  assert.ok('review_tester_bonus' in sample, 'Must project review_tester_bonus');
  assert.ok('review_owner_bonus' in sample, 'Must project review_owner_bonus');
  assert.ok('business_policy_id' in sample, 'Must project business_policy_id');
  assert.ok('provisional_granted_credits' in sample, 'Must project provisional_granted_credits');
  assert.ok('provisional_clawed' in sample, 'Must project provisional_clawed');
  assert.ok('bonus_awarded' in sample, 'Must project bonus_awarded');
});

// 19. Helper ACLs: Private financial helpers denied to authenticated & anon
await runStep('19. Helper ACLs: Private helpers denied to authenticated & anon roles', async () => {
  // 19.1 has_function_privilege is false
  const priv1 = (await db.query("select has_function_privilege('authenticated', 'public._get_effective_business_policy_row(date)', 'execute') as p;")).rows[0].p;
  assert.equal(priv1, false, '_get_effective_business_policy_row must not be executable by authenticated');

  const priv2 = (await db.query("select has_function_privilege('authenticated', 'public._get_business_policy_revision()', 'execute') as p;")).rows[0].p;
  assert.equal(priv2, false, '_get_business_policy_revision must not be executable by authenticated');

  const priv3 = (await db.query("select has_function_privilege('authenticated', 'public._get_business_quote_token(text, public.business_policies, jsonb)', 'execute') as p;")).rows[0].p;
  assert.equal(priv3, false, '_get_business_quote_token must not be executable by authenticated');

  // 19.2 Real direct call by member is rejected with permission denied
  await assert.rejects(
    asActor(memberId, "select public._get_effective_business_policy_row(current_date);"),
    /permission denied/,
    'Direct call to _get_effective_business_policy_row must fail'
  );
  await assert.rejects(
    asActor(memberId, "select public._get_business_policy_revision();"),
    /permission denied/,
    'Direct call to _get_business_policy_revision must fail'
  );
});

// 20. App Update Lifecycle: publish v2, claim snapshots, close/refund, legacy gate & replays
await runStep('20. App Update Lifecycle: publish v2, claim snapshots, close/refund, legacy gate & replays', async () => {
  const updateOwner = '88888888-8888-8888-8888-888888888881';
  const updateTester = '88888888-8888-8888-8888-888888888882';
  const updateTester2 = '88888888-8888-8888-8888-888888888883';
  const updateCamp = 'cccccccc-aaaa-aaaa-aaaa-aaaaaaaaaaa1';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${updateOwner}', 'up_owner@test.local'),
      ('${updateTester}', 'up_tester@test.local'),
      ('${updateTester2}', 'up_tester2@test.local')
    on conflict do nothing;

    insert into public.profiles (id, email, display_name, credits, role, is_locked) values
      ('${updateOwner}', 'up_owner@test.local', 'Update Owner', 1000, 'member', false),
      ('${updateTester}', 'up_tester@test.local', 'Update Tester', 100, 'member', false),
      ('${updateTester2}', 'up_tester2@test.local', 'Update Tester 2', 100, 'member', false)
    on conflict (id) do update set credits = 1000, is_locked = false;

    insert into public.campaigns (id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
    values ('${updateCamp}', '${updateOwner}', 'Update App', 'com.update.app', 'open', 10, 1, now() - interval '2 days')
    on conflict (id) do nothing;

    -- Add testers to campaign
    insert into public.campaign_members (campaign_id, user_id, joined_at)
    values
      ('${updateCamp}', '${updateTester}', now() - interval '1 day'),
      ('${updateCamp}', '${updateTester2}', now() - interval '1 day')
    on conflict do nothing;
  `);

  // 20.1 Default policy (reward = 6, claims = 20, budget = 120): legacy publish succeeds
  const opId1 = 'op-default-1';
  const pubRes1 = (await asActor(
    updateOwner,
    `select public.publish_app_update('${updateCamp}', '1.0.1', 101, 'First release', '${opId1}') as r;`
  )).rows[0].r;

  assert.equal(pubRes1.success, true);
  assert.equal(pubRes1.release.reward_per_claim, 6);
  assert.equal(pubRes1.release.max_claims, 20);
  assert.equal(pubRes1.release.reserved_credits, 120);

  const releaseId1 = pubRes1.release.id;
  const ownerBal1 = (await db.query(`select credits from public.profiles where id = '${updateOwner}';`)).rows[0].credits;
  assert.equal(ownerBal1, 880); // 1000 - 120 = 880

  // Tester claims reward from release 1
  const claimRes1 = (await asActor(
    updateTester,
    `select public.claim_app_update_reward('${releaseId1}', 101) as r;`
  )).rows[0].r;
  assert.equal(claimRes1.success, true);
  assert.equal(claimRes1.reward, 6);
  assert.equal(claimRes1.paid_count, 1);

  // 20.2 Non-default policy: reward = 8, claims = 25, budget = 200
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set app_update_reward_per_claim = 8,
        app_update_max_claims = 25
    where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  // Legacy publish MUST reject with CLIENT_UPDATE_REQUIRED when policy is non-default
  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update('${updateCamp}', '1.0.2', 102, 'Notes 2', 'op-stale-legacy');`
    ),
    /CLIENT_UPDATE_REQUIRED/
  );

  // BUT replaying already committed operation opId1 via legacy STILL succeeds across policy activation!
  const replayLegacy = (await asActor(
    updateOwner,
    `select public.publish_app_update('${updateCamp}', '1.0.1', 101, 'First release', '${opId1}') as r;`
  )).rows[0].r;
  assert.equal(replayLegacy.idempotent_replay, true);

  // 20.3 Even if 4*30 = 120 (same budget as default 120): legacy MUST STILL REJECT because terms changed
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set app_update_reward_per_claim = 4,
        app_update_max_claims = 30
    where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update('${updateCamp}', '1.0.2', 102, 'Notes 2', 'op-same-budget');`
    ),
    /CLIENT_UPDATE_REQUIRED/
  );

  // Set policy to 8 * 25 = 200
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set app_update_reward_per_claim = 8,
        app_update_max_claims = 25
    where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);

  const quoteUpdate200 = (await asActor(updateOwner, "select public.get_business_quote('app_update') as q;")).rows[0].q;
  assert.equal(quoteUpdate200.reserved_budget, 200);

  // 20.4 v2 validations: missing budget/token fails, price mismatch fails, stale revision fails
  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 102, 'Notes 2', 'op-v2-1', null, null);`
    ),
    /BUSINESS_POLICY_CONFLICT/
  );

  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 102, 'Notes 2', 'op-v2-1', 120, '${quoteUpdate200.revision}');`
    ),
    /BUSINESS_POLICY_PRICE_MISMATCH/
  );

  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 102, 'Notes 2', 'op-v2-1', 200, 'stale_token');`
    ),
    /BUSINESS_POLICY_CONFLICT/
  );

  // 20.5 v2 Concurrency CAS check with p_expected_current_release_id
  // Currently releaseId1 is active: passing NULL (expect none) MUST FAIL with CONCURRENT_UPDATE_CONFLICT!
  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 102, 'Notes 2', 'op-v2-1', 200, '${quoteUpdate200.revision}', null);`
    ),
    /CONCURRENT_UPDATE_CONFLICT/
  );

  // Passing wrong active release ID fails
  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 102, 'Notes 2', 'op-v2-1', 200, '${quoteUpdate200.revision}', '99999999-9999-9999-9999-999999999999');`
    ),
    /CONCURRENT_UPDATE_CONFLICT/
  );

  // 20.6 v2 publish with correct expected ID (releaseId1): succeeds!
  // Release 1 remaining was 114 (120 - 6). Old release is refunded 114 to owner!
  // Owner had 880 + 114 = 994. Then 200 is deducted for new release -> balance = 794!
  const opId2 = 'op-v2-success';
  const pubRes2 = (await asActor(
    updateOwner,
    `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 102, 'Notes 2', '${opId2}', 200, '${quoteUpdate200.revision}', '${releaseId1}') as r;`
  )).rows[0].r;

  assert.equal(pubRes2.success, true);
  assert.equal(pubRes2.release.reward_per_claim, 8);
  assert.equal(pubRes2.release.max_claims, 25);
  assert.equal(pubRes2.release.reserved_credits, 200);
  assert.equal(pubRes2.refund_amount, 114, 'Old release remaining refunded');
  assert.equal(pubRes2.owner_credits, 794);

  const releaseId2 = pubRes2.release.id;

  // Replay opId2 returns cached release
  const replayV2 = (await asActor(
    updateOwner,
    `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 102, 'Notes 2', '${opId2}', 200, '${quoteUpdate200.revision}') as r;`
  )).rows[0].r;
  assert.equal(replayV2.idempotent_replay, true);

  // Mismatched payload with same operation ID fails
  await assert.rejects(
    asActor(
      updateOwner,
      `select public.publish_app_update_v2('${updateCamp}', '1.0.2', 999, 'CHANGED', '${opId2}', 200, '${quoteUpdate200.revision}');`
    ),
    /OPERATION_PAYLOAD_CONFLICT/
  );

  // 20.7 Old release remains immutable: fresh claim on superseded releaseId1 denied
  await assert.rejects(
    asActor(updateTester2, `select public.claim_app_update_reward('${releaseId1}', 102);`),
    /RELEASE_REPLACED/
  );

  // But prior claim by updateTester replays successfully
  const claimReplayOld = (await asActor(updateTester, `select public.claim_app_update_reward('${releaseId1}', 101) as r;`)).rows[0].r;
  assert.equal(claimReplayOld.newly_awarded, false);
  assert.equal(claimReplayOld.reward, 6);

  // 20.8 Release 2 claims payout: tester claims release 2 (receives 8 credits, snapshot)
  const testerBalBeforeClaim2 = (await db.query(`select credits from public.profiles where id = '${updateTester}';`)).rows[0].credits;
  const claimRes2 = (await asActor(
    updateTester,
    `select public.claim_app_update_reward('${releaseId2}', 102) as r;`
  )).rows[0].r;
  assert.equal(claimRes2.success, true);
  assert.equal(claimRes2.reward, 8, 'Payout must use snapshot reward 8');

  const testerBalAfterClaim2 = (await db.query(`select credits from public.profiles where id = '${updateTester}';`)).rows[0].credits;
  assert.equal(testerBalAfterClaim2, testerBalBeforeClaim2 + 8);

  // 20.9 Close release 2: refunds actual remaining (200 - 8 = 192) to owner
  const closeRes = (await asActor(
    updateOwner,
    `select public.close_app_update_release('${releaseId2}') as r;`
  )).rows[0].r;
  assert.equal(closeRes.success, true);
  assert.equal(closeRes.refund_amount, 192);

  const ownerFinalBal = (await db.query(`select credits from public.profiles where id = '${updateOwner}';`)).rows[0].credits;
  assert.equal(ownerFinalBal, 794 + 192); // 986

  // Verify accounting invariant: reserved_credits = paid_count * reward_per_claim + remaining_credits + refunded_credits
  const rel2Final = (await db.query(`select * from public.app_update_releases where id = '${releaseId2}';`)).rows[0];
  assert.equal(rel2Final.reserved_credits, rel2Final.paid_count * rel2Final.reward_per_claim + rel2Final.remaining_credits + rel2Final.refunded_credits);

  // Reset baseline policy
  await db.exec(`
    set test.allow_policy_retroactive_write = 'true';
    update public.business_policies
    set app_update_reward_per_claim = 6,
        app_update_max_claims = 20
    where effective_from = date '-infinity';
    reset test.allow_policy_retroactive_write;
  `);
});

// 21. Real get_business_quote('all') confirmation, immediate and post-activation replay, token mismatch
await runStep('21. Real get_business_quote(all) confirmation, immediate & post-activation replay, token mismatch', async () => {
  const allUser = '99999999-8888-7777-6666-555555555551';
  const allReqId = '99999999-8888-7777-6666-555555555552';

  await db.exec(`
    insert into auth.users (id, email) values ('${allUser}', 'all_user@test.local') on conflict do nothing;
    insert into public.profiles (id, email, display_name, credits, role, is_locked)
    values ('${allUser}', 'all_user@test.local', 'All User', 1000, 'member', false)
    on conflict (id) do update set credits = 1000, is_locked = false;
  `);

  // 21.1 Read real get_business_quote('all')
  const quoteAll = (await asActor(allUser, "select public.get_business_quote('all') as q;")).rows[0].q;
  assert.ok(quoteAll.revision, 'Quote all has revision token');

  // 21.2 First purchase using ALL quote token
  const first = (await asActor(
    allUser,
    `select public.unlock_or_renew_slot_v2(2, '${allReqId}', 50, '${quoteAll.revision}') as r;`
  )).rows[0].r;

  assert.equal(first.success, true);
  assert.equal(first.replayed, false);
  assert.equal(first.cost, 50);
  assert.equal(first.renewal_days, 30);
  assert.equal(first.quote_token, quoteAll.revision, 'Receipt must store confirmed ALL token');

  const balAfterFirst = (await db.query(`select credits from public.profiles where id = '${allUser}';`)).rows[0].credits;
  assert.equal(balAfterFirst, 950);

  // 21.3 Immediate replay with same ALL token
  const immediateReplay = (await asActor(
    allUser,
    `select public.unlock_or_renew_slot_v2(2, '${allReqId}', 50, '${quoteAll.revision}') as r;`
  )).rows[0].r;

  assert.equal(immediateReplay.success, true);
  assert.equal(immediateReplay.replayed, true);
  assert.equal(immediateReplay.cost, 50);
  assert.equal(immediateReplay.renewal_days, 30);
  assert.equal(immediateReplay.expires_at, first.expires_at, 'Expiry unchanged on replay');

  const balAfterImmediate = (await db.query(`select credits from public.profiles where id = '${allUser}';`)).rows[0].credits;
  assert.equal(balAfterImmediate, 950, 'No double charge on immediate replay');

  // 21.4 Schedule future policy with changed duration (e.g. 15 days) and simulate activation via date seam
  const curCfg = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;')).rows[0].cfg;
  const tomorrow = (await db.query("select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1, 'YYYY-MM-DD') as d;")).rows[0].d;

  await asActor(
    adminId,
    `select public.admin_schedule_business_policy(
      '${tomorrow}',
      'Changed slot duration to 15 days',
      '${curCfg.revision}',
      100, 1, 2, 5, 10, 100, 50, 100, 200, 15,
      10, 2, 2, 6, 20
    );`
  );

  // Activate via date seam
  await db.exec(`
    create or replace function public._get_effective_business_policy_row(p_date date default null::date)
    returns public.business_policies language sql stable security definer set search_path=''
    as $$
      select p.* from public.business_policies p
      where p.effective_from <= coalesce(p_date, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 1)
      order by p.effective_from desc limit 1
    $$;
  `);

  // New quote has different token
  const quoteAllNew = (await asActor(allUser, "select public.get_business_quote('all') as q;")).rows[0].q;
  assert.notEqual(quoteAllNew.revision, quoteAll.revision, 'New quote token must rotate after activation');

  // 21.5 Post-activation replay with ORIGINAL allReqId and ORIGINAL token quoteAll.revision:
  // MUST STILL SUCCEED and return original receipt with original 30 days and cost 50!
  const postActivationReplay = (await asActor(
    allUser,
    `select public.unlock_or_renew_slot_v2(2, '${allReqId}', 50, '${quoteAll.revision}') as r;`
  )).rows[0].r;

  assert.equal(postActivationReplay.success, true);
  assert.equal(postActivationReplay.replayed, true);
  assert.equal(postActivationReplay.cost, 50);
  assert.equal(postActivationReplay.renewal_days, 30, 'Original receipt retains snapshot 30 days');
  assert.equal(postActivationReplay.expires_at, first.expires_at);

  const balAfterPostActivation = (await db.query(`select credits from public.profiles where id = '${allUser}';`)).rows[0].credits;
  assert.equal(balAfterPostActivation, 950, 'No extra charge on post-activation replay');

  // 21.6 Attempt replay with original allReqId but NEW / MISMATCHED quote token quoteAllNew.revision:
  // MUST CONFLICT! (Cannot reuse original UUID with different intent/terms)
  await assert.rejects(
    asActor(
      allUser,
      `select public.unlock_or_renew_slot_v2(2, '${allReqId}', 50, '${quoteAllNew.revision}');`
    ),
    /REQUEST_ID_CONFLICT/
  );

  // Verify original receipt in DB was NOT altered
  const receiptInDb = (await db.query(`select * from public.slot_purchase_receipts where client_request_id = '${allReqId}';`)).rows[0];
  assert.equal(receiptInDb.cost_credits, 50);
  assert.equal(receiptInDb.renewal_days, 30);
  assert.equal(receiptInDb.quote_token, quoteAll.revision);

  // Restore date seam and cleanup
  await db.exec(`
    create or replace function public._get_effective_business_policy_row(p_date date default null::date)
    returns public.business_policies language plpgsql stable security definer set search_path=''
    as $$
    declare
        v_date date := coalesce(p_date, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date);
        v_policy public.business_policies;
    begin
        if not isfinite(v_date) then raise exception 'BUSINESS_POLICY_INVALID_DATE'; end if;
        select p.* into v_policy from public.business_policies p where p.effective_from <= v_date order by p.effective_from desc limit 1;
        if v_policy.id is null then raise exception 'BUSINESS_POLICY_NOT_FOUND'; end if;
        return v_policy;
    end;
    $$;
  `);

  const curCfg4 = (await asActor(adminId, 'select public.admin_get_business_policy_config() as cfg;')).rows[0].cfg;
  await asActor(adminId, `select public.admin_cancel_business_policy('${tomorrow}', 'Cleanup step 21', '${curCfg4.revision}');`);
});

console.log('=== TẤT CẢ CÁC BƯỚC KIỂM THỬ FOUNDATION VÀ CONSUMERS ĐÃ VƯỢT QUA THÀNH CÔNG ===');
