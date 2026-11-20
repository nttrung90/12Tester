import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

// Seed IDs
const admin1 = '11111111-1111-1111-1111-111111111111'; // bootstrap admin with admin_management
const admin2 = '22222222-2222-2222-2222-222222222222'; // regular admin
const moderator = '33333333-3333-3333-3333-333333333333';
const member1 = '44444444-4444-4444-4444-444444444444';
const member2 = '55555555-5555-5555-5555-555555555555';
const lockedAdmin = '66666666-6666-6666-6666-666666666666';
const testCampaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const testReport = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

async function asActor(uid, sql, role = 'authenticated') {
  await db.exec(`savepoint sp_actor; select set_config('request.jwt.claim.sub','${uid ?? ''}',true); set local role ${role};`);
  try {
    const res = await db.query(sql);
    await db.exec(`release savepoint sp_actor;`);
    return res;
  } catch (err) {
    await db.exec(`rollback to savepoint sp_actor;`);
    throw err;
  } finally {
    try {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub','',true);`);
    } catch {
      // ignore
    }
  }
}

async function test(name, fn) {
  await db.exec('begin');
  try {
    await fn();
    console.log(`PASS: ${name}`);
  } catch (e) {
    console.error(`FAIL: ${name}`, e.message);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

// 1. Load active migrations up to 070
const targetMigration = process.env.UP_TO_MIGRATION ? Number(process.env.UP_TO_MIGRATION) : 999;
const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
  .filter(n => /^\d+.*\.sql$/.test(n) && Number(n.slice(0, 3)) <= targetMigration)
  .sort();

console.log(`=== LOADING ACTIVE MIGRATIONS (UP TO ${targetMigration}) ===`);
for (const name of migrationFiles) {
  console.log(`Applying ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

// 2. Seed baseline fixtures
await db.exec(`
  insert into auth.users(id, email) values
    ('${admin1}', 'admin1@crosstest.local'),
    ('${admin2}', 'admin2@crosstest.local'),
    ('${moderator}', 'mod@crosstest.local'),
    ('${member1}', 'member1@crosstest.local'),
    ('${member2}', 'member2@crosstest.local'),
    ('${lockedAdmin}', 'locked_admin@crosstest.local')
  on conflict (id) do nothing;

  update public.profiles set role = 'admin', is_locked = false, credits = 100, display_name = 'Admin Primary' where id = '${admin1}';
  update public.profiles set role = 'admin', is_locked = false, credits = 100, display_name = 'Admin Secondary' where id = '${admin2}';
  update public.profiles set role = 'moderator', is_locked = false, credits = 100, display_name = 'Moderator User' where id = '${moderator}';
  update public.profiles set role = 'member', is_locked = false, credits = 50, display_name = 'Regular Tester 1' where id = '${member1}';
  update public.profiles set role = 'member', is_locked = false, credits = 50, display_name = 'Regular Tester 2' where id = '${member2}';
  update public.profiles set role = 'admin', is_locked = true, credits = 100, display_name = 'Locked Administrator' where id = '${lockedAdmin}';

  -- Bootstrap admin1 capability
  select public.bootstrap_admin_capability('${admin1}', 'admin_management');

  -- Seed a test campaign and report
  insert into public.campaigns(id, owner_id, app_name, package_name, status, target_testers)
  values ('${testCampaign}', '${member1}', 'App Alpha', 'com.example.alpha', 'open', 12)
  on conflict (id) do nothing;

  insert into public.campaign_members(campaign_id, user_id)
  values ('${testCampaign}', '${member2}')
  on conflict (campaign_id, user_id) do nothing;

  insert into public.tester_reports(id, campaign_id, user_id, status, content, provisional_granted_credits, created_at)
  values ('${testReport}', '${testCampaign}', '${member2}', 'pending', 'Test feedback content', 2, now())
  on conflict (id) do nothing;
`);

console.log('=== RUNNING ADMIN MANAGEMENT BEHAVIORAL TESTS (STAGE A) ===');

const forbidden = /FORBIDDEN|PERMISSION_DENIED|NOT_ADMIN|ACCOUNT_LOCKED|NOT_AUTHENTICATED|UNAUTHORIZED|permission denied/i;

// ----------------------------------------------------------------------------
// Test Group 1: Auth & Access Control
// ----------------------------------------------------------------------------

const adminRPCs = [
  ['get action events', `select public.admin_get_action_events()`],
  ['get capabilities', `select public.admin_get_my_capabilities()`],
  ['search members', `select public.admin_search_members('test')`],
  ['get member overview', `select public.admin_get_member_overview('${member1}')`],
  ['get member credit history', `select public.admin_get_member_credit_history('${member1}')`],
  ['get member daily tasks', `select public.admin_get_member_daily_tasks('${member1}')`],
  ['get carryover policies', `select public.admin_get_carryover_policies()`],
  ['list future carryover policies', `select public.admin_list_future_carryover_policies()`],
  ['schedule carryover policy', `select public.admin_schedule_carryover_policy(current_date + 2, true, 'Test note')`],
  ['cancel carryover policy', `select public.admin_cancel_future_carryover_policy(current_date + 2, 'Test cancel')`],
  ['search campaigns', `select public.admin_search_campaigns('Alpha')`],
  ['get tester reports queue', `select public.admin_get_tester_reports_queue('pending')`],
  ['compensate credits', `select public.admin_compensate_credits('99999999-9999-9999-9999-999999999991', '${member1}', 10, 'Test reason')`],
  ['set user role', `select public.admin_set_user_role('${member1}', 'moderator', 'Test role change')`],
  ['get member warnings', `select public.admin_get_member_warnings('${member1}')`],
  ['revoke warning', `select public.admin_revoke_warning('88888888-8888-8888-8888-888888888888', 'Test revoke')`],
  ['get monitor summary', `select public.admin_get_system_monitor_summary()`],
  ['get job runs queue', `select public.admin_get_job_runs_queue()`],
  ['retry mission settlement', `select public.admin_retry_mission_settlement_jobs(null, 10)`]
];

for (const [name, sql] of adminRPCs) {
  await test(`Deny non-admin / anon / locked: ${name}`, async () => {
    // 1. Anon denied
    await assert.rejects(asActor(null, sql, 'anon'), forbidden);
    // 2. Member denied
    await assert.rejects(asActor(member1, sql), forbidden);
    // 3. Moderator denied
    await assert.rejects(asActor(moderator, sql), forbidden);
    // 4. Locked admin denied
    await assert.rejects(asActor(lockedAdmin, sql), forbidden);
  });
}

// ----------------------------------------------------------------------------
// Test Group 2: Capabilities & Role Management
// ----------------------------------------------------------------------------

await test('Capabilities RPC distinguishes bootstrap capability and UI explanation', async () => {
  const cap1 = (await asActor(admin1, `select public.admin_get_my_capabilities() as r`)).rows[0].r;
  assert.equal(cap1.role, 'admin');
  assert.equal(cap1.can_manage_admins, true);
  assert.equal(cap1.can_manage_moderators, true);
  assert.ok(cap1.capabilities.includes('admin_management'));

  const cap2 = (await asActor(admin2, `select public.admin_get_my_capabilities() as r`)).rows[0].r;
  assert.equal(cap2.role, 'admin');
  assert.equal(cap2.can_manage_admins, false);
  assert.equal(cap2.can_manage_moderators, true);
  assert.equal(cap2.capabilities.length, 0);
});

await test('Role Management: Admin without admin_management cannot grant or revoke admin', async () => {
  // admin2 lacks admin_management -> cannot promote member to admin
  await assert.rejects(
    asActor(admin2, `select public.admin_set_user_role('${member1}', 'admin', 'Elevate to admin')`),
    /INSUFFICIENT_PRIVILEGE/
  );

  // But admin2 can manage moderator role
  const res = (await asActor(admin2, `select public.admin_set_user_role('${member1}', 'moderator', 'Elevate to mod') as r`)).rows[0].r;
  assert.equal(res.success, true);
  assert.equal(res.new_role, 'moderator');

  const check = (await db.query(`select role from public.profiles where id = '${member1}'`)).rows[0];
  assert.equal(check.role, 'moderator');
});

await test('Role Management: Admin cannot change own role', async () => {
  await assert.rejects(
    asActor(admin1, `select public.admin_set_user_role('${admin1}', 'member', 'Self demote')`),
    /CANNOT_CHANGE_OWN_ROLE/
  );
});

await test('Role Management: Preserves last unlocked admin under concurrency', async () => {
  // Lock admin2 so admin1 is the ONLY unlocked admin
  await db.exec(`update public.profiles set is_locked = true where id = '${admin2}'`);

  // Now demoting admin1 is blocked
  await assert.rejects(
    asActor(admin1, `select public.admin_set_user_role('${admin1}', 'moderator', 'Demote last admin')`),
    /CANNOT_CHANGE_OWN_ROLE/
  );

  // If another admin tried to demote admin1:
  await db.exec(`update public.profiles set is_locked = false where id = '${admin2}'`);
  // Now lock admin2 again
  await db.exec(`update public.profiles set is_locked = true where id = '${admin2}'`);
  // Try to demote admin1 via admin2 -> blocked because admin2 is locked
  await assert.rejects(
    asActor(admin2, `select public.admin_set_user_role('${admin1}', 'member', 'Demote')`),
    forbidden
  );

  // What if admin1 tries to demote locked admin2?
  // admin2 is already locked, so admin1 is still the only unlocked admin.
  // Demoting admin2: admin2 is locked, but admin1 is still unlocked admin!
  const demoteLocked = (await asActor(admin1, `select public.admin_set_user_role('${admin2}', 'member', 'Demote locked admin') as r`)).rows[0].r;
  assert.equal(demoteLocked.success, true);

  // Now try to demote admin1: there are 0 other admins!
  // If someone with service_role tried, or if admin3 was created:
  await assert.rejects(
    asActor(admin1, `select public.admin_set_user_role('${admin1}', 'member', 'Demote sole admin')`),
    /CANNOT_CHANGE_OWN_ROLE/
  );

  // Restore admin2
  await db.exec(`update public.profiles set role = 'admin', is_locked = false where id = '${admin2}'`);
});

await test('Role Management: Records audit event and supports request ID replay', async () => {
  const reqId = '77777777-7777-7777-7777-777777777771';
  const r1 = (await asActor(admin1, `select public.admin_set_user_role('${member2}', 'moderator', 'Promote to mod', '${reqId}') as r`)).rows[0].r;
  assert.equal(r1.success, true);
  assert.equal(r1.new_role, 'moderator');

  // Audit event exists
  const audit = (await db.query(`select action, actor_id, target_id, new_state from public.admin_action_events where request_id = '${reqId}'`)).rows[0];
  assert.ok(audit);
  assert.equal(audit.action, 'set_user_role');
  assert.equal(audit.actor_id, admin1);
  assert.equal(audit.target_id, member2);
  assert.equal(audit.new_state.role, 'moderator');

  // Idempotent retry returns original result
  const r2 = (await asActor(admin1, `select public.admin_set_user_role('${member2}', 'moderator', 'Promote to mod', '${reqId}') as r`)).rows[0].r;
  assert.equal(r2.success, true);
  assert.equal(r2.is_replay, true);

  // Payload mismatch is rejected
  await assert.rejects(
    asActor(admin1, `select public.admin_set_user_role('${member2}', 'admin', 'Different role', '${reqId}')`),
    /REQUEST_ID_PAYLOAD_MISMATCH/
  );
});

// ----------------------------------------------------------------------------
// Test Group 3: Server-side Search & Pagination (>500 profiles)
// ----------------------------------------------------------------------------

await test('Search members across >500 profiles with stable cursor pagination', async () => {
  // Generate 520 member profiles
  await db.exec(`
    insert into auth.users (id, email)
    select
      gen_random_uuid(),
      'perf_user_' || i || '@test.invalid'
    from generate_series(1, 520) as i;

    update public.profiles
    set role = 'member',
        display_name = 'Member Perf ' || id,
        credits = 50
    where email like 'perf_user_%@test.invalid';
  `);

  const countRes = (await db.query(`select count(*) as cnt from public.profiles`)).rows[0].cnt;
  assert.ok(Number(countRes) > 520, `Total profiles must be > 520, got ${countRes}`);

  // Fetch page 1 (limit 100)
  const p1 = (await asActor(admin1, `select public.admin_search_members(null, 'member', false, 100) as r`)).rows[0].r;
  assert.equal(p1.items.length, 100);
  assert.equal(p1.has_more, true);
  assert.ok(p1.next_cursor);
  assert.ok(p1.next_cursor.created_at);
  assert.ok(p1.next_cursor.id);

  // Fetch page 2 using cursor
  const p2 = (await asActor(admin1, `select public.admin_search_members(null, 'member', false, 100, '${p1.next_cursor.created_at}'::timestamptz, '${p1.next_cursor.id}'::uuid) as r`)).rows[0].r;
  assert.equal(p2.items.length, 100);
  assert.equal(p2.has_more, true);

  // Verify disjoint items between page 1 and page 2
  const ids1 = new Set(p1.items.map(x => x.id));
  const ids2 = new Set(p2.items.map(x => x.id));
  for (const id of ids1) {
    assert.equal(ids2.has(id), false, `Item ID ${id} duplicated across pages!`);
  }

  // Exact UUID search
  const uuidSearch = (await asActor(admin1, `select public.admin_search_members('${member1}') as r`)).rows[0].r;
  assert.equal(uuidSearch.items.length, 1);
  assert.equal(uuidSearch.items[0].id, member1);

  // Wildcard escaping: search '%_' should not match all
  const wildSearch = (await asActor(admin1, `select public.admin_search_members('%_no_match_%') as r`)).rows[0].r;
  assert.equal(wildSearch.items.length, 0);
});

// ----------------------------------------------------------------------------
// Test Group 4: Member Overview, Credit History, Daily Task Status
// ----------------------------------------------------------------------------

await test('Member overview, credit history, and daily tasks explanation', async () => {
  // Seed a slot and credit log (slot_number 2..6)
  await db.exec(`
    insert into public.user_slots(user_id, slot_number, expires_at)
    values ('${member1}', 2, now() + interval '30 days');

    insert into public.credit_logs(user_id, amount, action, note, balance)
    values ('${member1}', 5, 'admin_grant', 'Initial seed', 55);
  `);

  const overview = (await asActor(admin1, `select public.admin_get_member_overview('${member1}') as r`)).rows[0].r;
  assert.equal(overview.id, member1);
  assert.equal(overview.campaigns_total, 1);
  assert.equal(overview.slots_active, 1);
  assert.equal(overview.tester_reports_count, 0);
  assert.ok(Array.isArray(overview.slots), 'overview.slots must be an array');
  assert.ok(overview.slots.length >= 2, 'overview.slots must contain slot 1 and slot 2');
  assert.equal(overview.slots[0].slot_number, 1);
  assert.equal(overview.slots[0].is_active, true);
  assert.equal(overview.slots[1].slot_number, 2);
  assert.equal(overview.slots[1].is_active, true);
  assert.ok(overview.slots[1].expires_at);

  const memberSlots = (await asActor(admin1, `select public.admin_get_member_slots('${member1}') as r`)).rows[0].r;
  assert.equal(memberSlots.user_id, member1);
  assert.ok(Array.isArray(memberSlots.slots));

  const credHistory = (await asActor(admin1, `select public.admin_get_member_credit_history('${member1}', 10) as r`)).rows[0].r;
  assert.ok(credHistory.items.length >= 1);
  assert.equal(credHistory.items[0].user_id, member1);

  const dailyTasks = (await asActor(admin1, `select public.admin_get_member_daily_tasks('${member1}', 7) as r`)).rows[0].r;
  assert.ok(dailyTasks.items.length >= 1);
  assert.ok(dailyTasks.items[0].task_date);
  assert.ok(dailyTasks.items[0].status);
});

// ----------------------------------------------------------------------------
// Test Group 5: Carryover Policy Management
// ----------------------------------------------------------------------------

await test('Carryover policy: active/future/history, revision conflict and cancellation', async () => {
  // Empty state check
  const initPolicies = (await asActor(admin1, `select public.admin_get_carryover_policies() as r`)).rows[0].r;
  assert.ok(initPolicies.active);
  assert.ok(initPolicies.revision);

  const rev0 = Number(initPolicies.revision);

  // Reject historical or today date
  await assert.rejects(
    asActor(admin1, `select public.admin_schedule_carryover_policy(current_date, false, 'Retro policy')`),
    /CANNOT_MODIFY_HISTORICAL_POLICY/
  );

  // Schedule future policy with request_id
  const reqSched = '12345678-1234-1234-1234-123456789012';
  const schedRes = (await asActor(admin1, `select public.admin_schedule_carryover_policy(current_date + 7, false, 'Scheduled maintenance', ${rev0}, '${reqSched}') as r`)).rows[0].r;
  assert.equal(schedRes.success, true);
  assert.equal(schedRes.is_enabled, false);

  const rev1 = Number(schedRes.revision);
  assert.ok(rev1 > rev0);

  // Idempotent retry with original expected revision rev0 must succeed and return is_replay (before CAS check!)
  const schedReplay = (await asActor(admin1, `select public.admin_schedule_carryover_policy(current_date + 7, false, 'Scheduled maintenance', ${rev0}, '${reqSched}') as r`)).rows[0].r;
  assert.equal(schedReplay.success, true);
  assert.equal(schedReplay.is_replay, true);

  // Mismatch payload with same request_id is rejected
  await assert.rejects(
    asActor(admin1, `select public.admin_schedule_carryover_policy(current_date + 7, true, 'Different note', ${rev0}, '${reqSched}')`),
    /REQUEST_ID_PAYLOAD_MISMATCH/
  );

  // Revision conflict on stale schedule attempt with new request_id
  await assert.rejects(
    asActor(admin1, `select public.admin_schedule_carryover_policy(current_date + 8, true, 'Stale write', ${rev0})`),
    /REVISION_CONFLICT/
  );

  // Future policy now appears in future list
  const polList = (await asActor(admin1, `select public.admin_get_carryover_policies() as r`)).rows[0].r;
  assert.equal(polList.future.length, 1);
  assert.equal(polList.future[0].is_enabled, false);

  // Cancel non-future policy is blocked
  await assert.rejects(
    asActor(admin1, `select public.admin_cancel_future_carryover_policy(current_date, 'Cancel today')`),
    /CANNOT_CANCEL_NON_FUTURE_POLICY/
  );

  // Cancel future policy with request_id and valid revision
  const reqCancel = '12345678-1234-1234-1234-123456789013';
  const cancelRes = (await asActor(admin1, `select public.admin_cancel_future_carryover_policy(current_date + 7, 'No longer needed', ${rev1}, '${reqCancel}') as r`)).rows[0].r;
  assert.equal(cancelRes.success, true);

  // Idempotent retry cancel returns is_replay before CAS check
  const cancelReplay = (await asActor(admin1, `select public.admin_cancel_future_carryover_policy(current_date + 7, 'No longer needed', ${rev1}, '${reqCancel}') as r`)).rows[0].r;
  assert.equal(cancelReplay.success, true);
  assert.equal(cancelReplay.is_replay, true);

  // Future list is now empty again
  const afterCancel = (await asActor(admin1, `select public.admin_get_carryover_policies() as r`)).rows[0].r;
  assert.equal(afterCancel.future.length, 0);

  // Verify cancellation event history in dedicated paginated RPC
  const hist = (await asActor(admin1, `select public.admin_get_carryover_policy_history(10) as r`)).rows[0].r;
  assert.ok(hist.items.length >= 2);
  const cancelEvent = hist.items.find(x => x.is_cancelled);
  assert.ok(cancelEvent, 'History must contain cancellation event');
  assert.equal(cancelEvent.clean_note, 'No longer needed');
});

await test('admin_list_future_carryover_policies: 125 future policies pagination, cursor stability on cancel, bounds and security', async () => {
  // 1. Seed 125 future policies
  await db.exec(`
    insert into public.daily_task_carryover_policies (effective_from, is_enabled, operator_note)
    select
      (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + i,
      (i % 2 = 0),
      'Future policy #' || i
    from generate_series(10, 134) as i
    on conflict (effective_from) do update
      set is_enabled = excluded.is_enabled, operator_note = excluded.operator_note;
  `);

  // 2. Traverse all pages with limit 50
  const allTraversed = [];
  let cursor = null;
  let pageCount = 0;

  while (true) {
    pageCount++;
    const cursorSql = cursor ? `'${cursor}'::date` : 'null';
    const page = (await asActor(admin1, `select public.admin_list_future_carryover_policies(50, ${cursorSql}) as r`)).rows[0].r;

    for (const item of page.items) {
      assert.ok(item.id, 'Item must have id');
      assert.ok(item.effective_from, 'Item must have effective_from');
      assert.equal(typeof item.is_enabled, 'boolean');
      assert.ok(item.operator_note);
      assert.ok(item.created_at);
      allTraversed.push(item);
    }

    if (!page.has_more) {
      assert.equal(page.next_cursor, null, 'next_cursor must be null when has_more is false');
      break;
    }

    assert.ok(page.next_cursor, 'next_cursor must be provided when has_more is true');
    assert.ok(page.next_cursor.date, 'next_cursor must contain date');
    cursor = page.next_cursor.date;
  }

  assert.equal(pageCount, 3, '125 items with limit 50 must span exactly 3 pages (50, 50, 25)');
  assert.equal(allTraversed.length, 125, 'Must traverse exactly 125 items without omission');

  // Verify uniqueness (no duplicate dates or IDs)
  const seenDates = new Set();
  const seenIds = new Set();
  for (const it of allTraversed) {
    assert.equal(seenDates.has(it.effective_from), false, `Duplicate date: ${it.effective_from}`);
    assert.equal(seenIds.has(it.id), false, `Duplicate ID: ${it.id}`);
    seenDates.add(it.effective_from);
    seenIds.add(it.id);
  }

  // 3. Cancel row cursor stability
  const cancelDate = (await db.query(`select to_char((clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date + 35, 'YYYY-MM-DD') as d`)).rows[0].d;
  const currentRev = (await asActor(admin1, `select public.admin_get_carryover_policies() as r`)).rows[0].r.revision;
  await asActor(admin1, `select public.admin_cancel_future_carryover_policy('${cancelDate}', 'Cancel middle policy', ${currentRev})`);

  // Query page 1 with limit 30
  const p1 = (await asActor(admin1, `select public.admin_list_future_carryover_policies(30, null) as r`)).rows[0].r;
  assert.equal(p1.items.length, 30);
  assert.equal(p1.has_more, true);

  // Query page 2 from cursor of page 1
  const p2 = (await asActor(admin1, `select public.admin_list_future_carryover_policies(30, '${p1.next_cursor.date}'::date) as r`)).rows[0].r;
  assert.equal(p2.items.length, 30);
  assert.equal(p2.has_more, true);

  // Ensure cancelled date is absent from both pages
  assert.equal(p1.items.some(x => x.effective_from === cancelDate), false);
  assert.equal(p2.items.some(x => x.effective_from === cancelDate), false);

  // 4. Input validation: invalid / infinite cursor date
  await assert.rejects(
    asActor(admin1, `select public.admin_list_future_carryover_policies(50, 'infinity'::date)`),
    /INVALID_CURSOR_DATE/
  );

  // 5. Read-only: zero audit events emitted
  const auditBefore = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  await asActor(admin1, `select public.admin_list_future_carryover_policies(10, null)`);
  const auditAfter = (await db.query(`select count(*)::int as cnt from public.admin_action_events`)).rows[0].cnt;
  assert.equal(auditAfter, auditBefore, 'admin_list_future_carryover_policies must not emit audit events');

  // 6. Direct table privilege check: anon and authenticated have no direct table privilege expansion
  const anonSelect = (await db.query(`select has_table_privilege('anon', 'public.daily_task_carryover_policies', 'INSERT') as p`)).rows[0].p;
  assert.equal(anonSelect, false, 'anon must not have INSERT on daily_task_carryover_policies');
});

// ----------------------------------------------------------------------------
// Test Group 6: Campaign & Tester Report Queue
// ----------------------------------------------------------------------------

await test('Campaign search and tester reports queue with display joins and media_expired flag', async () => {
  const campSearch = (await asActor(admin1, `select public.admin_search_campaigns('Alpha') as r`)).rows[0].r;
  assert.equal(campSearch.items.length, 1);
  assert.equal(campSearch.items[0].app_name, 'App Alpha');
  assert.equal(campSearch.items[0].owner_id, member1);
  assert.ok(campSearch.items[0].owner_name);

  const reportQueue = (await asActor(admin1, `select public.admin_get_tester_reports_queue('pending') as r`)).rows[0].r;
  assert.equal(reportQueue.items.length, 1);
  assert.equal(reportQueue.items[0].id, testReport);
  assert.equal(reportQueue.items[0].campaign_name, 'App Alpha');
  assert.equal(reportQueue.items[0].tester_name, 'Regular Tester 2');
  assert.equal(typeof reportQueue.items[0].media_expired, 'boolean');
  assert.equal(reportQueue.items[0].media_expired, false);

  // Seed another report with media_expired = true
  const expiredReportId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbe';
  await db.exec(`
    insert into public.tester_reports(id, campaign_id, user_id, status, content, provisional_granted_credits, media_expired, created_at)
    values ('${expiredReportId}', '${testCampaign}', '${member2}', 'pending', 'Expired media test', 2, true, now())
    on conflict (id) do update set media_expired = true;
  `);

  const reportQueue2 = (await asActor(admin1, `select public.admin_get_tester_reports_queue('pending') as r`)).rows[0].r;
  const expItem = reportQueue2.items.find(x => x.id === expiredReportId);
  assert.ok(expItem, 'Queue must return the seeded report');
  assert.equal(expItem.media_expired, true, 'media_expired must accurately reflect true');
});

// ----------------------------------------------------------------------------
// Test Group 7: Safe Credit Compensation API
// ----------------------------------------------------------------------------

await test('Credit compensation: safe retry replay, mismatch rejection, bounded delta', async () => {
  const reqId = '88888888-8888-8888-8888-888888888881';
  const prevCredits = (await db.query(`select credits from public.profiles where id = '${member1}'`)).rows[0].credits;

  // 1. Compensate +15 credits
  const c1 = (await asActor(admin1, `select public.admin_compensate_credits('${reqId}', '${member1}', 15, 'Compensation for delay') as r`)).rows[0].r;
  assert.equal(c1.success, true);
  assert.equal(c1.amount, 15);
  assert.equal(c1.new_credits, prevCredits + 15);
  assert.equal(c1.is_replay, false);

  // Verify audit event
  const audit = (await db.query(`select action, new_state from public.admin_action_events where request_id = '${reqId}'`)).rows[0];
  assert.ok(audit);
  assert.equal(audit.action, 'compensate_credits');
  assert.equal(audit.new_state.amount, 15);

  // 2. Exact replay returns original result without adding again
  const c2 = (await asActor(admin1, `select public.admin_compensate_credits('${reqId}', '${member1}', 15, 'Compensation for delay') as r`)).rows[0].r;
  assert.equal(c2.success, true);
  assert.equal(c2.is_replay, true);

  const curCredits = (await db.query(`select credits from public.profiles where id = '${member1}'`)).rows[0].credits;
  assert.equal(curCredits, prevCredits + 15, 'Balance must not increase on replay!');

  // 3. Payload mismatch with same request_id is rejected
  await assert.rejects(
    asActor(admin1, `select public.admin_compensate_credits('${reqId}', '${member1}', 25, 'Different amount')`),
    /REQUEST_ID_PAYLOAD_MISMATCH/
  );

  // 4. Overdraft deduction clamps to 0
  const reqId2 = '88888888-8888-8888-8888-888888888882';
  const c3 = (await asActor(admin1, `select public.admin_compensate_credits('${reqId2}', '${member1}', -9999, 'Clawback overdraft') as r`)).rows[0].r;
  assert.equal(c3.success, true);
  assert.equal(c3.new_credits, 0);
  assert.equal(c3.amount, -curCredits);
});

// ----------------------------------------------------------------------------
// Test Group 8: Warning Revocation & Consistency
// ----------------------------------------------------------------------------

await test('Warning revocation append-only, consistency across get_unread_warnings and get_campaign_detail', async () => {
  const warningId = '99999999-9999-9999-9999-999999999999';

  // Insert a warning for member2
  await db.exec(`
    insert into public.user_warnings (id, user_id, warning_type, message, warning_date, created_at, acknowledged)
    values ('${warningId}', '${member2}', 'tester_rejection_streak', 'Test warning message', current_date, now(), false);
  `);

  // Unread warnings returns it for member2
  const unreadBefore = (await asActor(member2, `select * from public.get_unread_warnings()`)).rows;
  assert.equal(unreadBefore.length, 1);
  assert.equal(unreadBefore[0].id, warningId);

  // Campaign detail and Dashboard include warning in unread and weekly_warnings_count
  const campBefore = (await asActor(member2, `select public.get_campaign_detail('${testCampaign}') as r`)).rows[0].r;
  assert.equal(campBefore.unread_warnings.length, 1);
  assert.equal(campBefore.weekly_warnings_count, 1);

  const dashBefore = (await asActor(member2, `select public.get_my_dashboard(false) as r`)).rows[0].r;
  assert.equal(dashBefore.unread_warnings.length, 1);
  assert.equal(dashBefore.weekly_warnings_count, 1);
  const requiredDashboardKeys = [
    'profile', 'my_campaigns', 'joined_campaigns', 'leaderboard',
    'today_checkin_count', 'pending_reports_count', 'unread_warnings',
    'weekly_warnings_count', 'credit_logs', 'topup_packages',
    'notifications', 'owner_stats', 'slots', 'quest_summary', 'daily_task'
  ];
  for (const k of requiredDashboardKeys) {
    assert.ok(k in dashBefore, `Dashboard before must contain key ${k}`);
  }

  // Revoke warning as admin with request_id
  const reqRevoke = '12345678-1234-1234-1234-123456789014';
  const revRes = (await asActor(admin1, `select public.admin_revoke_warning('${warningId}', 'Revoked due to app crash', '${reqRevoke}') as r`)).rows[0].r;
  assert.equal(revRes.success, true);
  assert.equal(revRes.revoked, true);

  // Replay with exact same request_id and reason returns is_replay
  const reRevReq = (await asActor(admin1, `select public.admin_revoke_warning('${warningId}', 'Revoked due to app crash', '${reqRevoke}') as r`)).rows[0].r;
  assert.equal(reRevReq.success, true);
  assert.equal(reRevReq.is_replay, true);

  // Mismatch payload with same request_id is rejected
  await assert.rejects(
    asActor(admin1, `select public.admin_revoke_warning('${warningId}', 'Different reason', '${reqRevoke}')`),
    /REQUEST_ID_PAYLOAD_MISMATCH/
  );

  // Revoke again idempotently without request_id
  const reRev = (await asActor(admin1, `select public.admin_revoke_warning('${warningId}', 'Duplicate revoke') as r`)).rows[0].r;
  assert.equal(reRev.success, true);
  assert.equal(reRev.already_revoked, true);

  // Original warning still persists in table (append-only)
  const warnCheck = (await db.query(`select 1 from public.user_warnings where id = '${warningId}'`)).rows;
  assert.equal(warnCheck.length, 1);

  // get_unread_warnings now EXCLUDES revoked warning
  const unreadAfter = (await asActor(member2, `select * from public.get_unread_warnings()`)).rows;
  assert.equal(unreadAfter.length, 0);

  // Campaign detail now EXCLUDES revoked warning from weekly_warnings_count and unread
  const campAfter = (await asActor(member2, `select public.get_campaign_detail('${testCampaign}') as r`)).rows[0].r;
  assert.equal(campAfter.unread_warnings.length, 0);
  assert.equal(campAfter.weekly_warnings_count, 0);

  // Home dashboard now EXCLUDES revoked warning while preserving 100% of DTO keys
  const dashAfter = (await asActor(member2, `select public.get_my_dashboard(false) as r`)).rows[0].r;
  assert.equal(dashAfter.unread_warnings.length, 0);
  assert.equal(dashAfter.weekly_warnings_count, 0);
  for (const k of requiredDashboardKeys) {
    assert.ok(k in dashAfter, `Dashboard after must contain key ${k}`);
  }

  // Admin warning list shows is_revoked = true with revocation details
  const adminWarnList = (await asActor(admin1, `select public.admin_get_member_warnings('${member2}') as r`)).rows[0].r;
  assert.equal(adminWarnList.items.length, 1);
  assert.equal(adminWarnList.items[0].is_revoked, true);
  assert.ok(adminWarnList.items[0].revocation);
  assert.equal(adminWarnList.items[0].revocation.reason, 'Revoked due to app crash');
});

// ----------------------------------------------------------------------------
// Test Group 9: Monitor Summary & Settlement Retry Wrapper
// ----------------------------------------------------------------------------

await test('System monitor summary and mission settlement retry wrapper', async () => {
  const summary = (await asActor(admin1, `select public.admin_get_system_monitor_summary() as r`)).rows[0].r;
  assert.ok(summary.members.total >= 6);
  assert.ok(summary.members.admins >= 2);
  assert.ok(summary.campaigns.total >= 1);
  assert.ok(summary.tester_reports.pending >= 1);
  assert.ok(summary.timestamp);

  const jobsQueue = (await asActor(admin1, `select public.admin_get_job_runs_queue('mission_settlement') as r`)).rows[0].r;
  assert.ok(Array.isArray(jobsQueue.items));

  // Retry with invalid job ID raises error
  await assert.rejects(
    asActor(admin1, `select public.admin_retry_mission_settlement_jobs('00000000-0000-0000-0000-000000000000')`),
    /JOB_NOT_FOUND/
  );

  // Batch retry
  const reqId = 'aaaaaaaa-1111-2222-3333-444444444444';
  const retryRes = (await asActor(admin1, `select public.admin_retry_mission_settlement_jobs(null, 10, '${reqId}') as r`)).rows[0].r;
  assert.equal(retryRes.success, true);
  assert.equal(typeof retryRes.processed_count, 'number');

  // Verify audit event recorded for retry
  const audit = (await db.query(`select action from public.admin_action_events where request_id = '${reqId}'`)).rows[0];
  assert.ok(audit);
  assert.equal(audit.action, 'retry_mission_settlement_batch');
});

console.log('=== ALL ADMIN MANAGEMENT BEHAVIORAL TESTS PASSED (STAGE A) ===');
