import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();
const tester = 'a1000000-0000-0000-0000-000000000001';
const owner = 'c3000000-0000-0000-0000-000000000001';
const moderator = 'd4000000-0000-0000-0000-000000000001';
const otherTester = 'e5000000-0000-0000-0000-000000000001';

const camp1 = 'b1000000-0000-0000-0000-000000000001';
const camp2 = 'b2000000-0000-0000-0000-000000000002';
const camp3 = 'b3000000-0000-0000-0000-000000000003';

async function asUser(uid, fn) {
  await db.exec('begin');
  try {
    if (uid) {
      await db.exec(`set local "request.jwt.claim.sub" = '${uid}'; set local "request.jwt.claims" = '{"sub": "${uid}", "role": "authenticated"}'; set local role = 'authenticated';`);
    } else {
      await db.exec(`set local "request.jwt.claim.sub" = ''; set local "request.jwt.claims" = '{"role": "anon"}'; set local role = 'anon';`);
    }
    const result = await fn();
    return result;
  } finally {
    await db.exec('rollback');
  }
}

console.log('=== LOADING ALL MIGRATIONS IN ORDER (INCLUDING 085) ===');
const migrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n))
  .sort();
for (const name of migrations) {
  await db.exec(read('supabase/migrations/' + name));
}
console.log('MIGRATIONS LOADED SUCCESSFULLY (' + migrations.length + ' migrations).');

// ============================================================================
// BASE FIXTURES SETUP (using reset role / postgres superuser)
// ============================================================================
await db.exec(`
  -- Users in auth.users
  insert into auth.users(id, email) values
    ('${owner}', 'owner@test.local'),
    ('${tester}', 'tester@test.local'),
    ('${moderator}', 'moderator@test.local'),
    ('${otherTester}', 'other@test.local');

  -- Update profiles with specific dates
  update public.profiles set
    display_name = 'Owner User',
    credits = 10000,
    role = 'member',
    last_login_date = (now() at time zone 'Asia/Ho_Chi_Minh')::date - interval '1 day'
  where id = '${owner}';

  update public.profiles set
    display_name = 'Tester User',
    credits = 1000,
    role = 'member',
    last_login_date = (now() at time zone 'Asia/Ho_Chi_Minh')::date
  where id = '${tester}';

  update public.profiles set
    display_name = 'Locked Moderator',
    credits = 500,
    role = 'moderator',
    is_locked = true,
    last_login_date = (now() at time zone 'Asia/Ho_Chi_Minh')::date
  where id = '${moderator}';

  update public.profiles set
    display_name = 'Other Tester',
    credits = 2000,
    role = 'member',
    last_login_date = (now() at time zone 'Asia/Ho_Chi_Minh')::date
  where id = '${otherTester}';

  -- User slots for owner
  insert into public.user_slots(user_id, slot_number, expires_at) values
    ('${owner}', 2, now() + interval '30 days'),
    ('${owner}', 3, now() + interval '30 days');

  -- Campaigns
  insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, target_testers, created_at) values
    ('${camp1}', '${owner}', 'com.app.one', 'App One', 'open', 1, 12, now() - interval '3 days'),
    ('${camp2}', '${owner}', 'com.app.two', 'App Two', 'open', 2, 15, now() - interval '2 days'),
    ('${camp3}', '${tester}', 'com.app.tester', 'Tester App', 'open', 1, 12, now() - interval '1 day');

  -- Membership
  insert into public.campaign_members(campaign_id, user_id, joined_at) values
    ('${camp1}', '${tester}', now() - interval '2 days'),
    ('${camp1}', '${otherTester}', now() - interval '2 days');

  -- Checkin: otherTester checked in camp1 today
  insert into public.checkins(campaign_id, user_id, checkin_date, created_at) values
    ('${camp1}', '${otherTester}', (now() at time zone 'Asia/Ho_Chi_Minh')::date, now());
`);

console.log('=== BASE FIXTURES SEEDED ===');

// ============================================================================
// TEST 1: HOME FEED SYNC V2 (Parity, Anon vs Authenticated, Unchanged/Changed)
// ============================================================================
console.log('--- TEST 1: Home Feed Sync V2 ---');

// Anon load parity
await asUser(null, async () => {
  const legacy = (await db.query(`select public.get_home_feed() as res`)).rows[0].res;
  const v2 = (await db.query(`select public.get_home_feed_sync_v2('{}'::jsonb) as res`)).rows[0].res;

  assert.equal(v2.schema_version, 2);
  assert.ok(v2.business_date);

  // Reconstruct full state from v2
  const reconstructed = {};
  for (const [k, b] of Object.entries(v2.blocks)) {
    assert.equal(b.changed, true, `Anon cold load block ${k} must be changed`);
    assert.ok(b.token, `Block ${k} must have sha256 token`);
    reconstructed[k] = b.value;
  }

  assert.deepEqual(reconstructed, legacy, 'Anon v2 reconstructed payload must exactly equal legacy get_home_feed()');
  assert.equal(reconstructed.paused.length, 0, 'Anon paused must be empty');
  assert.equal(reconstructed.completed.length, 0, 'Anon completed must be empty');
});

// Authenticated tester load, 304-style unchanged tokens, and partial invalidation
await asUser(tester, async () => {
  const legacy = (await db.query(`select public.get_home_feed() as res`)).rows[0].res;
  const v2_cold = (await db.query(`select public.get_home_feed_sync_v2('{}'::jsonb) as res`)).rows[0].res;

  const coldReconstructed = {};
  for (const [k, b] of Object.entries(v2_cold.blocks)) {
    assert.equal(b.changed, true);
    coldReconstructed[k] = b.value;
  }
  assert.deepEqual(coldReconstructed, legacy, 'Auth v2 cold payload must match legacy get_home_feed()');

  // Second load with tokens -> all unchanged
  const tokens = {};
  for (const [k, b] of Object.entries(v2_cold.blocks)) {
    tokens[k] = b.token;
  }
  const v2_cached = (await db.query(`select public.get_home_feed_sync_v2($1::jsonb) as res`, [JSON.stringify(tokens)])).rows[0].res;
  for (const [k, b] of Object.entries(v2_cached.blocks)) {
    assert.equal(b.changed, false, `Block ${k} must be unchanged`);
    assert.equal(b.value, undefined, `Unchanged block ${k} must not transmit value`);
    assert.equal(b.token, tokens[k], `Token ${k} must match`);
  }

  // Mutate: pause camp2 using reset role
  await db.exec(`reset role; update public.campaigns set status = 'paused', paused_reason = 'manual' where id = '${camp2}';`);
  // Restore tester role and claims
  await db.exec(`set local "request.jwt.claim.sub" = '${tester}'; set local "request.jwt.claims" = '{"sub": "${tester}", "role": "authenticated"}'; set local role = 'authenticated';`);

  const v2_after_mutation = (await db.query(`select public.get_home_feed_sync_v2($1::jsonb) as res`, [JSON.stringify(tokens)])).rows[0].res;
  assert.equal(v2_after_mutation.blocks.open.changed, true, 'open block must be changed after pause');
  assert.equal(v2_after_mutation.blocks.paused.changed, true, 'paused block must be changed after pause');
  assert.ok(v2_after_mutation.blocks.paused.value.length > 0, 'paused block must now contain the paused campaign');
  assert.equal(v2_after_mutation.blocks.completed.changed, false, 'completed block remains unchanged');

  // Verify merged state matches fresh legacy call
  const legacy_after = (await db.query(`select public.get_home_feed() as res`)).rows[0].res;
  const merged = { ...coldReconstructed };
  for (const [k, b] of Object.entries(v2_after_mutation.blocks)) {
    if (b.changed) merged[k] = b.value;
  }
  assert.deepEqual(merged, legacy_after, 'Merged sync state must exactly equal fresh legacy payload');
});

console.log('PASS: Home Feed Sync V2');

// ============================================================================
// TEST 2: DASHBOARD SYNC V2 (Side-Effects, Parity, Mutated Notifications & Logs)
// ============================================================================
console.log('--- TEST 2: Dashboard Sync V2 ---');

await asUser(owner, async () => {
  // Confirm owner last_login_date was yesterday before call
  const beforeProfile = (await db.query(`select last_login_date from public.profiles where id = '${owner}'`)).rows[0];
  const todayDateStr = (await db.query(`select (now() at time zone 'Asia/Ho_Chi_Minh')::date::text as td`)).rows[0].td;
  assert.notEqual(beforeProfile.last_login_date.toISOString().slice(0, 10), todayDateStr, 'Owner login date must start as yesterday');

  // Call sync v2
  const d1 = (await db.query(`select public.get_my_dashboard_sync_v2('{}'::jsonb, true) as res`)).rows[0].res;
  assert.equal(d1.schema_version, 2);

  // Verify daily login side-effect was triggered!
  const afterProfile = (await db.query(`select last_login_date from public.profiles where id = '${owner}'`)).rows[0];
  assert.equal(afterProfile.last_login_date.toISOString().slice(0, 10), todayDateStr, 'Daily login must be updated to today by get_my_dashboard');

  // Parity check against get_my_dashboard(true)
  const legacyDash = (await db.query(`select public.get_my_dashboard(true) as res`)).rows[0].res;
  const reconstructedDash = {};
  const tokens = {};
  for (const [k, b] of Object.entries(d1.blocks)) {
    assert.equal(b.changed, true, `Cold load block ${k} must be changed`);
    reconstructedDash[k] = b.value;
    tokens[k] = b.token;
  }
  assert.deepEqual(reconstructedDash, legacyDash, 'Reconstructed dashboard must match legacy dashboard');

  // Second load with tokens -> all 15 blocks unchanged
  const d2 = (await db.query(`select public.get_my_dashboard_sync_v2($1::jsonb, true) as res`, [JSON.stringify(tokens)])).rows[0].res;
  for (const [k, b] of Object.entries(d2.blocks)) {
    assert.equal(b.changed, false, `Dashboard block ${k} must be unchanged`);
    assert.equal(b.value, undefined);
  }

  // Mutate: insert credit log and notification for owner
  await db.exec(`reset role;
    insert into public.credit_logs(user_id, amount, action, note, balance) values ('${owner}', 100, 'topup', 'Test Topup', 10100);
    insert into public.notifications(user_id, title, message) values ('${owner}', 'Hello', 'Welcome');
  `);
  await db.exec(`set local "request.jwt.claim.sub" = '${owner}'; set local "request.jwt.claims" = '{"sub": "${owner}", "role": "authenticated"}'; set local role = 'authenticated';`);

  const d3 = (await db.query(`select public.get_my_dashboard_sync_v2($1::jsonb, true) as res`, [JSON.stringify(tokens)])).rows[0].res;
  assert.equal(d3.blocks.credit_logs.changed, true, 'credit_logs must be changed');
  assert.equal(d3.blocks.notifications.changed, true, 'notifications must be changed');
  assert.equal(d3.blocks.my_campaigns.changed, false, 'my_campaigns must remain unchanged');
  assert.equal(d3.blocks.slots.changed, false, 'slots must remain unchanged');

  // Notifications deleted to []
  await db.exec(`reset role; delete from public.notifications where user_id = '${owner}';`);
  await db.exec(`set local "request.jwt.claim.sub" = '${owner}'; set local "request.jwt.claims" = '{"sub": "${owner}", "role": "authenticated"}'; set local role = 'authenticated';`);

  const d4_tokens = { ...tokens, notifications: d3.blocks.notifications.token };
  const d4 = (await db.query(`select public.get_my_dashboard_sync_v2($1::jsonb, true) as res`, [JSON.stringify(d4_tokens)])).rows[0].res;
  assert.equal(d4.blocks.notifications.changed, true, 'notifications must be changed after delete');
  assert.deepEqual(d4.blocks.notifications.value, [], 'empty notifications must serialize as empty array []');
});

console.log('PASS: Dashboard Sync V2');

// ============================================================================
// TEST 3: CAMPAIGN DETAIL SYNC V2 (Auto-Accept Side-Effect, Parity, 200 Reports, Attachments)
// ============================================================================
console.log('--- TEST 3: Campaign Detail Sync V2 ---');

// Auto-accept side-effect and 200 reports test
await asUser(owner, async () => {
  // Seed an expired pending report (> 48 hours old) for auto-accept
  await db.exec(`reset role;
    insert into public.tester_reports(id, campaign_id, user_id, rating, content, status, submission_context, created_at) values
      ('f1000000-0000-0000-0000-000000000001', '${camp1}', '${tester}', 5, 'Old report auto accept test', 'pending', 'app_test', now() - interval '3 days');
  `);
  await db.exec(`set local "request.jwt.claim.sub" = '${owner}'; set local "request.jwt.claims" = '{"sub": "${owner}", "role": "authenticated"}'; set local role = 'authenticated';`);

  // Calling get_campaign_detail_sync_v2 must trigger auto-accept of expired reports
  const cd1 = (await db.query(`select public.get_campaign_detail_sync_v2($1, '{}'::jsonb) as res`, [camp1])).rows[0].res;
  assert.equal(cd1.schema_version, 2);

  // Check report was auto-accepted!
  const reportCheck = (await db.query(`select status from public.tester_reports where id = 'f1000000-0000-0000-0000-000000000001'`)).rows[0];
  assert.equal(reportCheck.status, 'approved', 'Old pending report must be auto-accepted');

  // Parity check
  const legacyDetail = (await db.query(`select public.get_campaign_detail($1) as res`, [camp1])).rows[0].res;
  const reconstructedDetail = {};
  const tokens = {};
  for (const [k, b] of Object.entries(cd1.blocks)) {
    assert.equal(b.changed, true);
    reconstructedDetail[k] = b.value;
    tokens[k] = b.token;
  }
  assert.deepEqual(reconstructedDetail, legacyDetail, 'Reconstructed detail must equal legacy get_campaign_detail()');

  // Second load with tokens -> all unchanged
  const cd2 = (await db.query(`select public.get_campaign_detail_sync_v2($1, $2::jsonb) as res`, [camp1, JSON.stringify(tokens)])).rows[0].res;
  for (const [k, b] of Object.entries(cd2.blocks)) {
    assert.equal(b.changed, false, `Detail block ${k} must be unchanged`);
    assert.equal(b.value, undefined);
  }
});

// Locked moderator test: verify permission & state parity
await asUser(moderator, async () => {
  const legacy = (await db.query(`select public.get_campaign_detail($1) as res`, [camp1])).rows[0].res;
  const v2 = (await db.query(`select public.get_campaign_detail_sync_v2($1, '{}'::jsonb) as res`, [camp1])).rows[0].res;

  assert.equal(legacy.can_manage, false, 'Locked moderator cannot manage in legacy');
  assert.equal(legacy.is_owner, false, 'Locked moderator is_owner is false in legacy');
  assert.equal(v2.blocks.can_manage.value, false, 'Locked moderator cannot manage in v2');
  assert.equal(v2.blocks.is_owner.value, false, 'Locked moderator is_owner is false in v2');

  const reconstructed = {};
  for (const [k, b] of Object.entries(v2.blocks)) {
    reconstructed[k] = b.value;
  }
  assert.deepEqual(reconstructed, legacy, 'Reconstructed locked moderator payload must match legacy');
});

// Non-existent campaign exception parity
const nonExistentCamp = 'ffffffff-ffff-ffff-ffff-ffffffffffff';
let legacyErr = null;
await asUser(tester, async () => {
  try {
    await db.query(`select public.get_campaign_detail($1) as res`, [nonExistentCamp]);
  } catch (e) {
    legacyErr = e.message;
  }
});

let v2Err = null;
await asUser(tester, async () => {
  try {
    await db.query(`select public.get_campaign_detail_sync_v2($1, '{}'::jsonb) as res`, [nonExistentCamp]);
  } catch (e) {
    v2Err = e.message;
  }
});
assert.ok(legacyErr && legacyErr.includes('CAMPAIGN_NOT_FOUND'), 'Legacy must throw CAMPAIGN_NOT_FOUND');
assert.ok(v2Err && v2Err.includes('CAMPAIGN_NOT_FOUND'), 'V2 must throw CAMPAIGN_NOT_FOUND');
assert.equal(v2Err, legacyErr, 'V2 must throw identical CAMPAIGN_NOT_FOUND exception');

console.log('PASS: Campaign Detail Sync V2');

// ============================================================================
// TEST 4: GET NEXT CAMPAIGN CANDIDATES V2 (Exact Candidate Universe & Order)
// ============================================================================
console.log('--- TEST 4: Next Campaign Candidates V2 ---');

await asUser(tester, async () => {
  // Candidate list from v2 (with additive migration 086 today_tested_count & stats_date)
  const v2_candidates = (await db.query(`select public.get_next_campaign_candidates_v2($1) as res`, [camp1])).rows[0].res;

  // Compute expected candidates using exact legacy get_home_feed filtering logic
  const feed = (await db.query(`select public.get_home_feed() as res`)).rows[0].res;
  const expectedCandidates = feed.open
    .filter(c => c.id !== camp1 && c.owner_id !== tester)
    .filter(c => {
      const stat = feed.stats[c.id];
      return stat && !stat.has_tested_today;
    })
    .map(c => ({
      campaign: c,
      installed_count: feed.stats[c.id]?.installed_count || 0,
      today_tested_count: feed.stats[c.id]?.today_tested_count || 0,
      stats_date: feed.stats[c.id]?.stats_date || ''
    }));

  assert.deepEqual(v2_candidates, expectedCandidates, 'Candidates v2 must match legacy get_home_feed candidate universe, order, and stats (including 086 additive fields)');
  console.log('Candidates count:', v2_candidates.length);
  assert.equal(v2_candidates.length, 1);
  assert.equal(v2_candidates[0].today_tested_count, 0);
  assert.ok(v2_candidates[0].stats_date);
});

// ============================================================================
// TEST 5: MIGRATION 086 DEEP BEHAVIORAL CHECKS
// (Full-Campaign Presence, Per-Day Distinct Counts, Regular vs Mission)
// ============================================================================
console.log('--- TEST 5: Migration 086 Deep Checks ---');

// 5A: Per-day distinct count behavior on camp2
const userA = '11111111-0000-0000-0000-000000000001';
const userB = '22222222-0000-0000-0000-000000000002';
const userC = '33333333-0000-0000-0000-000000000003';

await db.exec(`
  insert into auth.users(id, email) values
    ('${userA}', 'usera@test.local'),
    ('${userB}', 'userb@test.local'),
    ('${userC}', 'userc@test.local');

  -- Yesterday checkin on camp2 for userA: must NOT count today
  insert into public.checkins(campaign_id, user_id, checkin_date, created_at) values
    ('${camp2}', '${userA}', (now() at time zone 'Asia/Ho_Chi_Minh')::date - interval '1 day', now() - interval '1 day');

  -- Today checkin on camp2 for userA: counts as 1
  insert into public.checkins(campaign_id, user_id, checkin_date, created_at) values
    ('${camp2}', '${userA}', (now() at time zone 'Asia/Ho_Chi_Minh')::date, now());

  -- Today checkin on camp2 for userB: distinct count becomes 2
  insert into public.checkins(campaign_id, user_id, checkin_date, created_at) values
    ('${camp2}', '${userB}', (now() at time zone 'Asia/Ho_Chi_Minh')::date, now());
`);

await asUser(tester, async () => {
  const candidates = (await db.query(`select public.get_next_campaign_candidates_v2($1) as res`, [camp1])).rows[0].res;
  const camp2Entry = candidates.find(c => c.campaign.id === camp2);
  assert.ok(camp2Entry, 'camp2 must be present in candidates');
  assert.equal(camp2Entry.today_tested_count, 2, 'Distinct today checkins must be 2 (yesterday and duplicates ignored)');
});

// 5B: Full Campaign Presence (Reaching / Exceeding Daily Target)
// camp2 has target_testers = 15. We insert 13 more distinct user checkins today to reach 15/15.
await db.exec(`
  do $$
  declare
    v_new_uid uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
  begin
    for i in 1..13 loop
      v_new_uid := ('44444444-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid;
      insert into auth.users(id, email) values (v_new_uid, 'tester' || i || '@test.local');
      insert into public.checkins(campaign_id, user_id, checkin_date, created_at)
      values ('${camp2}', v_new_uid, v_today, clock_timestamp());
    end loop;
  end $$;
`);

await asUser(tester, async () => {
  const candidates = (await db.query(`select public.get_next_campaign_candidates_v2($1) as res`, [camp1])).rows[0].res;
  const camp2Entry = candidates.find(c => c.campaign.id === camp2);
  assert.ok(camp2Entry, 'Camp2 must STILL be in candidates even after reaching 15/15 target (full campaigns are not pruned by SQL)');
  assert.equal(camp2Entry.today_tested_count, 15, 'Camp2 today_tested_count must be 15');
});

// Exceeding target (16/15)
await db.exec(`
  insert into auth.users(id, email) values ('${userC}', 'userc2@test.local') on conflict do nothing;
  insert into public.checkins(campaign_id, user_id, checkin_date, created_at)
  values ('${camp2}', '${userC}', (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date, clock_timestamp());
`);

await asUser(tester, async () => {
  const candidates = (await db.query(`select public.get_next_campaign_candidates_v2($1) as res`, [camp1])).rows[0].res;
  const camp2Entry = candidates.find(c => c.campaign.id === camp2);
  assert.ok(camp2Entry, 'Camp2 must STILL be in candidates after exceeding target (16/15)');
  assert.equal(camp2Entry.today_tested_count, 16, 'Camp2 today_tested_count must be 16');
});

// 5C: Regular vs Mission exclusion behavior
// If a user has a mission report today, it does NOT exclude them from get_next_campaign_candidates_v2
const missionTester = '55555555-0000-0000-0000-000000000001';
const campMission = 'b4000000-0000-0000-0000-000000000004';
await db.exec(`
  insert into auth.users(id, email) values ('${missionTester}', 'missiontester@test.local');
  insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number, target_testers, created_at)
  values ('${campMission}', '${owner}', 'com.app.mission', 'App Mission', 'open', 3, 12, now());

  -- Mission report submitted today
  insert into public.tester_reports(id, campaign_id, user_id, submission_context, status, report_date, created_at)
  values ('f2000000-0000-0000-0000-000000000002', '${campMission}', '${missionTester}', 'mission', 'pending', (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date, clock_timestamp());
`);

await asUser(missionTester, async () => {
  const candidates = (await db.query(`select public.get_next_campaign_candidates_v2() as res`)).rows[0].res;
  const missionCamp = candidates.find(c => c.campaign.id === campMission);
  assert.ok(missionCamp, 'A user with only a mission report is not excluded from regular candidate suggestion');
});

// But a regular app_test report today DOES exclude them
await db.exec(`
  insert into public.tester_reports(id, campaign_id, user_id, submission_context, status, report_date, created_at)
  values ('f3000000-0000-0000-0000-000000000003', '${campMission}', '${missionTester}', 'app_test', 'pending', (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date, clock_timestamp());
`);

await asUser(missionTester, async () => {
  const candidates = (await db.query(`select public.get_next_campaign_candidates_v2() as res`)).rows[0].res;
  const missionCamp = candidates.find(c => c.campaign.id === campMission);
  assert.equal(missionCamp, undefined, 'A user with an app_test report today is excluded from candidates');
});

// 5D: Full Campaign Real Report Submission & Reward Acceptance
// Even when a campaign has reached/exceeded its daily target (camp2 is 16/15),
// it accepts new reports and grants provisional rewards according to policy.
const fullTester = '66666666-0000-0000-0000-000000000001';
await db.exec(`
  insert into auth.users(id, email) values ('${fullTester}', 'fulltester@test.local');
  update public.profiles set credits = 100, role = 'member' where id = '${fullTester}';
  insert into public.campaign_members(campaign_id, user_id, joined_at) values ('${camp2}', '${fullTester}', now());
`);

await asUser(fullTester, async () => {
  const submitResult = (await db.query(`
    select public.submit_tester_report_v2(
      'app_test',
      '${camp2}',
      'Tested full app',
      'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp2}%2F${fullTester}%2F77777777-0000-0000-0000-000000000001.jpg?alt=media&token=fixture',
      5,
      '0123456789abcdef',
      '77777777-0000-0000-0000-000000000001'::uuid
    ) as res
  `)).rows[0].res;

  assert.ok(submitResult, 'Submit result must be returned');
  assert.equal(submitResult.submission_context, 'app_test');
  assert.equal(submitResult.provisional_amount, 10, 'Full campaign must still grant provisional reward');

  // Verify checkin created for fullTester on camp2
  const checkinRow = (await db.query(`
    select count(*)::int as cnt from public.checkins
    where campaign_id = '${camp2}' and user_id = '${fullTester}'
  `)).rows[0].cnt;
  assert.equal(checkinRow, 1, 'Checkin must be recorded for fullTester');

  // Verify fullTester is now excluded from candidates for camp2 today
  const candidatesAfter = (await db.query(`select public.get_next_campaign_candidates_v2() as res`)).rows[0].res;
  const camp2Candidate = candidatesAfter.find(c => c.campaign.id === camp2);
  assert.equal(camp2Candidate, undefined, 'fullTester has now tested camp2 today, so excluded');
});

console.log('PASS: Migration 086 Deep Checks');

console.log('\n======================================================');
console.log('ALL EGRESS SYNC V2 DATABASE TESTS PASSED WITH COMPLETE PARITY!');
console.log('======================================================\n');
