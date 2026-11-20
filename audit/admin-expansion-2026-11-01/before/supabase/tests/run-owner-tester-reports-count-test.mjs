import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

const ownerId = '11111111-0000-0000-0000-000000000001';
const adminId = '11111111-0000-0000-0000-000000000002';
const modId = '11111111-0000-0000-0000-000000000003';
const tester1Id = '22222222-0000-0000-0000-000000000001';
const tester2Id = '22222222-0000-0000-0000-000000000002';
const otherOwnerId = '33333333-0000-0000-0000-000000000001';

const campA = 'aaaa0000-0000-0000-0000-000000000001';
const campB = 'bbbb0000-0000-0000-0000-000000000001';

const one = async sql => (await db.query(sql)).rows[0];

async function asUser(uid, role = 'authenticated', fn) {
  await db.exec(`select set_config('request.jwt.claim.sub', '${uid}', false); set role ${role};`);
  try {
    return await fn();
  } finally {
    try {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', false);`);
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
    console.error(`FAIL: ${name}`, e);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

console.log('=== LOADING ACTIVE MIGRATIONS DYNAMICALLY (062 ONWARD) ===');
const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d+.*\.sql$/.test(n))
  .sort();

console.log(`Found ${migrationFiles.length} active migrations:`, migrationFiles);

// Phase 1: Demonstrate before-fix reproduction on migrations <= 067
const pre068Migrations = migrationFiles.filter(n => Number(n.slice(0, 3)) <= 67);
for (const name of pre068Migrations) {
  await db.exec(read('supabase/migrations/' + name));
}

// Seed baseline fixture data for reproduction
await db.exec(`
  insert into auth.users(id, email) values
    ('${ownerId}', 'owner@test.local'),
    ('${adminId}', 'admin@test.local'),
    ('${modId}', 'mod@test.local'),
    ('${tester1Id}', 'tester1@test.local'),
    ('${tester2Id}', 'tester2@test.local'),
    ('${otherOwnerId}', 'otherowner@test.local');

  insert into public.profiles(id, email, display_name, role, is_locked) values
    ('${ownerId}', 'owner@test.local', 'Owner User', 'member', false),
    ('${adminId}', 'admin@test.local', 'Admin User', 'admin', false),
    ('${modId}', 'mod@test.local', 'Mod User', 'moderator', false),
    ('${tester1Id}', 'tester1@test.local', 'Tester 1', 'member', false),
    ('${tester2Id}', 'tester2@test.local', 'Tester 2', 'member', false),
    ('${otherOwnerId}', 'otherowner@test.local', 'Other Owner', 'member', false)
  on conflict (id) do update set
    role = excluded.role,
    display_name = excluded.display_name,
    is_locked = excluded.is_locked;

  insert into public.campaigns(id, owner_id, app_name, package_name, status) values
    ('${campA}', '${ownerId}', 'App A', 'com.test.appa', 'open'),
    ('${campB}', '${otherOwnerId}', 'App B', 'com.test.appb', 'open');

  insert into public.campaign_members(campaign_id, user_id) values
    ('${campA}', '${tester1Id}'),
    ('${campA}', '${tester2Id}');
`);

// Insert 1 pending report to test before-fix state
await db.exec(`
  insert into public.tester_reports(campaign_id, user_id, status, content, created_at)
  values ('${campA}', '${tester1Id}', 'pending', 'Repro report', now());
`);

const getDetail = async (cId) => (await one(`select public.get_campaign_detail('${cId}') as d`)).d;

const reproBefore = await asUser(ownerId, 'authenticated', () => getDetail(campA));
assert.equal(reproBefore.reports.length, 1, 'Before fix: 1 report in array');
assert.equal(reproBefore.reports[0].status, 'pending', 'Before fix: status is pending');
assert.equal(Object.hasOwn(reproBefore, 'pending_reports_count'), false, 'Before fix: pending_reports_count missing');
assert.equal(Object.hasOwn(reproBefore, 'tester_reports_count'), false, 'Before fix: tester_reports_count missing');
assert.equal(reproBefore.reports_count, 0, 'Before fix: reports_count (feedbacks) is 0');
console.log('PASS: Successfully reproduced before-fix state (pending_reports_count and tester_reports_count missing)');

// Clean up repro report
await db.exec(`delete from public.tester_reports where campaign_id = '${campA}';`);

// Phase 2: Apply migration 068 onward
const post067Migrations = migrationFiles.filter(n => Number(n.slice(0, 3)) > 67);
for (const name of post067Migrations) {
  console.log(`Applying ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

console.log('=== RUNNING GET_CAMPAIGN_DETAIL REPORT COUNT TESTS ===');

// 1. Fresh zero counts
await test('Fresh campaign with no reports returns 0 for all report counts', async () => {
  const detail = await asUser(ownerId, 'authenticated', () => getDetail(campA));
  assert.equal(detail.tester_reports_count, 0);
  assert.equal(detail.pending_reports_count, 0);
  assert.equal(detail.reports_count, 0);
  assert.deepEqual(detail.reports, []);
});

// 2. Owner scope: mixed statuses (pending, approved, rejected)
await test('Owner sees total and pending reports across all testers', async () => {
  await db.exec(`
    insert into public.tester_reports(campaign_id, user_id, status, content, created_at) values
      ('${campA}', '${tester1Id}', 'pending', 'T1 pending', now()),
      ('${campA}', '${tester1Id}', 'approved', 'T1 approved', now() - interval '1 hour'),
      ('${campA}', '${tester2Id}', 'pending', 'T2 pending', now()),
      ('${campA}', '${tester2Id}', 'rejected', 'T2 rejected', now() - interval '2 hours');
  `);

  const detail = await asUser(ownerId, 'authenticated', () => getDetail(campA));
  assert.equal(detail.tester_reports_count, 4, 'Total tester reports should be 4');
  assert.equal(detail.pending_reports_count, 2, 'Pending reports should be 2');
  assert.equal(detail.reports_count, 0, 'Feedback reports_count remains 0');
  assert.equal(detail.reports.length, 4, 'Reports list has 4 items');
});

// 3. Admin and Moderator scopes
await test('Admin and Moderator see campaign totals matching owner', async () => {
  await db.exec(`
    insert into public.tester_reports(campaign_id, user_id, status, content, created_at) values
      ('${campA}', '${tester1Id}', 'pending', 'T1 pending', now()),
      ('${campA}', '${tester2Id}', 'approved', 'T2 approved', now());
  `);

  const adminDetail = await asUser(adminId, 'authenticated', () => getDetail(campA));
  assert.equal(adminDetail.can_manage, true);
  assert.equal(adminDetail.tester_reports_count, 2);
  assert.equal(adminDetail.pending_reports_count, 1);

  const modDetail = await asUser(modId, 'authenticated', () => getDetail(campA));
  assert.equal(modDetail.can_manage, true);
  assert.equal(modDetail.tester_reports_count, 2);
  assert.equal(modDetail.pending_reports_count, 1);
});

// 4. Regular tester scope (non-owner / privacy isolation)
await test('Regular tester only sees their own report totals and reports list', async () => {
  await db.exec(`
    insert into public.tester_reports(campaign_id, user_id, status, content, created_at) values
      ('${campA}', '${tester1Id}', 'pending', 'T1 pending', now()),
      ('${campA}', '${tester1Id}', 'approved', 'T1 approved', now()),
      ('${campA}', '${tester2Id}', 'pending', 'T2 pending', now());
  `);

  const t1Detail = await asUser(tester1Id, 'authenticated', () => getDetail(campA));
  assert.equal(t1Detail.can_manage, false);
  assert.equal(t1Detail.is_owner, false);
  // Tester 1 should only see 2 reports (their own), 1 pending
  assert.equal(t1Detail.tester_reports_count, 2, 'Tester 1 only counts own reports');
  assert.equal(t1Detail.pending_reports_count, 1, 'Tester 1 only counts own pending reports');
  assert.equal(t1Detail.reports.length, 2, 'Tester 1 reports array only contains own reports');
  assert.ok(t1Detail.reports.every(r => r.user_id === tester1Id));

  const t2Detail = await asUser(tester2Id, 'authenticated', () => getDetail(campA));
  assert.equal(t2Detail.tester_reports_count, 1, 'Tester 2 only counts own reports');
  assert.equal(t2Detail.pending_reports_count, 1, 'Tester 2 only counts own pending reports');
  assert.equal(t2Detail.reports.length, 1);
  assert.equal(t2Detail.reports[0].user_id, tester2Id);
});

// 5. Cross-campaign isolation
await test('Reports on Camp B do not leak into Camp A counts', async () => {
  await db.exec(`
    insert into public.tester_reports(campaign_id, user_id, status, content, created_at) values
      ('${campA}', '${tester1Id}', 'pending', 'A pending', now()),
      ('${campB}', '${tester1Id}', 'pending', 'B pending', now()),
      ('${campB}', '${tester2Id}', 'pending', 'B pending 2', now());
  `);

  const detailA = await asUser(ownerId, 'authenticated', () => getDetail(campA));
  assert.equal(detailA.tester_reports_count, 1, 'Camp A only has 1 report');
  assert.equal(detailA.pending_reports_count, 1, 'Camp A only has 1 pending');

  const detailB = await asUser(otherOwnerId, 'authenticated', () => getDetail(campB));
  assert.equal(detailB.tester_reports_count, 2, 'Camp B has 2 reports');
  assert.equal(detailB.pending_reports_count, 2, 'Camp B has 2 pending');
});

// 6. All approved or rejected results in pending = 0
await test('When all reports are approved or rejected, pending_reports_count is 0', async () => {
  await db.exec(`
    insert into public.tester_reports(campaign_id, user_id, status, content, created_at) values
      ('${campA}', '${tester1Id}', 'approved', 'App 1', now()),
      ('${campA}', '${tester2Id}', 'rejected', 'Rej 1', now());
  `);

  const detail = await asUser(ownerId, 'authenticated', () => getDetail(campA));
  assert.equal(detail.tester_reports_count, 2);
  assert.equal(detail.pending_reports_count, 0);
});

// 7. Expired pending auto-accept (> 8 hours)
await test('Pending reports older than 8 hours are auto-accepted and not counted as pending', async () => {
  await db.exec(`
    insert into public.tester_reports(campaign_id, user_id, status, content, created_at) values
      ('${campA}', '${tester1Id}', 'pending', 'Expired pending', now() - interval '9 hours'),
      ('${campA}', '${tester2Id}', 'pending', 'Fresh pending', now() - interval '1 hour');
  `);

  const detail = await asUser(ownerId, 'authenticated', () => getDetail(campA));
  // The expired report becomes approved automatically by auto_accept_expired_reports() inside get_campaign_detail
  assert.equal(detail.tester_reports_count, 2, 'Total reports is 2');
  assert.equal(detail.pending_reports_count, 1, 'Pending reports count is 1 because expired one was auto-accepted');
  const expiredReport = detail.reports.find(r => r.content === 'Expired pending');
  assert.equal(expiredReport.status, 'approved', 'Expired report status transitioned to approved');
});

// 8. Feedbacks vs tester_reports separation
await test('Feedbacks update reports_count and avg_rating without affecting tester_reports_count', async () => {
  await db.exec(`
    insert into public.feedbacks(campaign_id, user_id, rating, comment, created_at) values
      ('${campA}', '${tester1Id}', 5, 'Great app', now()),
      ('${campA}', '${tester2Id}', 3, 'Okay app', now());

    insert into public.tester_reports(campaign_id, user_id, status, content, created_at) values
      ('${campA}', '${tester1Id}', 'pending', 'Bug report', now());
  `);

  const detail = await asUser(ownerId, 'authenticated', () => getDetail(campA));
  assert.equal(detail.reports_count, 2, 'reports_count reflects the 2 feedbacks');
  assert.equal(detail.avg_rating, 4.0, 'avg_rating reflects feedbacks avg ((5+3)/2)');
  assert.equal(detail.tester_reports_count, 1, 'tester_reports_count reflects the 1 tester report');
  assert.equal(detail.pending_reports_count, 1, 'pending_reports_count reflects the 1 pending tester report');
});

// 9. Authoritative count beyond 200 limit of reports array
await test('Authoritative count counts >200 reports accurately while reports array caps at 200', async () => {
  // Generate 250 reports: 225 pending (recent first), 25 approved
  await db.exec(`
    insert into public.tester_reports(campaign_id, user_id, status, content, created_at)
    select
      '${campA}',
      '${tester1Id}',
      case when i <= 225 then 'pending' else 'approved' end,
      'Bulk report ' || i,
      now() - (i || ' seconds')::interval
    from generate_series(1, 250) as i;
  `);

  const detail = await asUser(ownerId, 'authenticated', () => getDetail(campA));
  assert.equal(detail.reports.length, 200, 'reports array is capped at 200');
  assert.equal(detail.reports.filter(r => r.status === 'pending').length, 200, 'array pending count is 200');
  assert.equal(detail.tester_reports_count, 250, 'tester_reports_count is authoritative 250');
  assert.equal(detail.pending_reports_count, 225, 'pending_reports_count is authoritative 225 (>200)');
});

await db.close();
console.log('ALL OWNER TESTER REPORT COUNT DATABASE TESTS PASSED');
