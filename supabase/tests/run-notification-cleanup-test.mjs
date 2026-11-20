import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

const adminId = '11111111-1111-4111-8111-111111111111';
const userA = '22222222-2222-4222-8222-222222222222';
const userB = '33333333-3333-4333-8333-333333333333';

const one = async sql => (await db.query(sql)).rows[0];
const rows = async sql => (await db.query(sql)).rows;

async function asRole(role, uid, fn) {
  await db.exec('savepoint sp_role');
  if (uid) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${uid}', true);`);
  } else {
    await db.exec(`select set_config('request.jwt.claim.sub', '', true);`);
  }
  await db.exec(`set local role ${role};`);
  try {
    const result = await fn();
    await db.exec('release savepoint sp_role');
    return result;
  } catch (err) {
    await db.exec('rollback to savepoint sp_role');
    throw err;
  } finally {
    try {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', true);`);
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
    try {
      await db.exec('rollback');
    } catch {
      // ignore
    }
  }
}

console.log('=== LOADING ALL ACTIVE MIGRATIONS (062 THROUGH 097) ===');
const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62)
  .sort();

for (const name of migrationFiles) {
  await db.exec(read('supabase/migrations/' + name));
}
console.log(`Loaded ${migrationFiles.length} migrations up to ${migrationFiles.at(-1)}.`);

await db.exec(`
  insert into auth.users (id, email) values
    ('${adminId}', 'admin@cleanup.test'),
    ('${userA}', 'usera@cleanup.test'),
    ('${userB}', 'userb@cleanup.test');

  insert into public.profiles (id, email, display_name, role, is_locked) values
    ('${adminId}', 'admin@cleanup.test', 'Admin', 'admin', false),
    ('${userA}', 'usera@cleanup.test', 'User A', 'member', false),
    ('${userB}', 'userb@cleanup.test', 'User B', 'member', false)
  on conflict (id) do update set
    role = excluded.role,
    is_locked = excluded.is_locked;
`);

console.log('=== RUNNING NOTIFICATION CLEANUP TESTS ===');

// ---------------------------------------------------------------------------
// 1. Indexes, function metadata, and ACLs
// ---------------------------------------------------------------------------
await test('Indexes, SECURITY DEFINER, and ACL grants are properly configured', async () => {
  const indexes = await rows(`
    select indexname
    from pg_indexes
    where schemaname = 'public'
      and indexname in ('idx_notifications_created_at', 'idx_notification_popup_receipts_claimed_at')
    order by indexname
  `);
  assert.deepEqual(
    indexes.map(r => r.indexname),
    ['idx_notification_popup_receipts_claimed_at', 'idx_notifications_created_at']
  );

  const fnMeta = await one(`
    select prosecdef, proconfig
    from pg_proc
    where pronamespace = 'public'::regnamespace
      and proname = 'purge_expired_notifications'
  `);
  assert.equal(fnMeta.prosecdef, true, 'purge_expired_notifications must be SECURITY DEFINER');
  assert.ok(fnMeta.proconfig?.includes('search_path=public'), 'search_path must be fixed to public');

  const privs = await one(`
    select
      has_function_privilege('anon', 'public.purge_expired_notifications(integer, integer, boolean, integer)', 'EXECUTE') as anon_exec,
      has_function_privilege('authenticated', 'public.purge_expired_notifications(integer, integer, boolean, integer)', 'EXECUTE') as auth_exec,
      has_function_privilege('service_role', 'public.purge_expired_notifications(integer, integer, boolean, integer)', 'EXECUTE') as service_exec,
      has_function_privilege('anon', 'public.sync_campaign_lifecycle()', 'EXECUTE') as sync_anon_exec,
      has_function_privilege('authenticated', 'public.sync_campaign_lifecycle()', 'EXECUTE') as sync_auth_exec,
      has_function_privilege('service_role', 'public.sync_campaign_lifecycle()', 'EXECUTE') as sync_service_exec
  `);
  assert.equal(privs.anon_exec, false);
  assert.equal(privs.auth_exec, false);
  assert.equal(privs.service_exec, true);
  assert.equal(privs.sync_anon_exec, true);
  assert.equal(privs.sync_auth_exec, true);
  assert.equal(privs.sync_service_exec, true);

  await assert.rejects(
    () => asRole('anon', null, () => db.query(`select public.purge_expired_notifications()`)),
    /permission denied/i
  );
  await assert.rejects(
    () => asRole('authenticated', userA, () => db.query(`select public.purge_expired_notifications()`)),
    /permission denied/i
  );
});

// ---------------------------------------------------------------------------
// 2. Admin vs non-admin notification retention + popup state cascade
// ---------------------------------------------------------------------------
await test('Admin notifications kept 60 days; all other notifications purged after 7 days with popup state cascade', async () => {
  const idAdmin8 = 'a0000000-0000-4000-8000-000000000001';
  const idAdmin59 = 'a0000000-0000-4000-8000-000000000002';
  const idAdmin61 = 'a0000000-0000-4000-8000-000000000003';
  const idAdminCase61 = 'a0000000-0000-4000-8000-000000000004';

  const keepOtherIds = [
    { id: 'b0000000-0000-4000-8000-000000000001', type: 'credit', data: '{}' },
    { id: 'b0000000-0000-4000-8000-000000000002', type: 'report', data: '{}' },
    { id: 'b0000000-0000-4000-8000-000000000003', type: 'campaign', data: '{"reason":"admin_pause","sender":"admin"}' },
    { id: 'b0000000-0000-4000-8000-000000000004', type: 'warning', data: '{}' },
    { id: 'b0000000-0000-4000-8000-000000000005', type: 'mission', data: '{}' },
    { id: 'b0000000-0000-4000-8000-000000000006', type: 'penalty', data: '{}' },
    { id: 'b0000000-0000-4000-8000-000000000007', type: 'tester_bonus', data: '{}' }
  ];

  const expOtherIds = [
    { id: 'c0000000-0000-4000-8000-000000000001', type: 'credit', data: '{}' },
    { id: 'c0000000-0000-4000-8000-000000000002', type: 'report', data: '{}' },
    { id: 'c0000000-0000-4000-8000-000000000003', type: 'campaign', data: '{"reason":"admin_pause","sender":"admin"}' },
    { id: 'c0000000-0000-4000-8000-000000000004', type: 'warning', data: '{}' },
    { id: 'c0000000-0000-4000-8000-000000000005', type: 'mission', data: '{}' },
    { id: 'c0000000-0000-4000-8000-000000000006', type: 'penalty', data: '{}' },
    { id: 'c0000000-0000-4000-8000-000000000007', type: 'tester_bonus', data: '{}' },
    { id: 'c0000000-0000-4000-8000-000000000008', type: '', data: '{}' }
  ];

  await db.exec(`
    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${idAdmin8}', '${userA}', 'Admin 8d', 'Msg', 'admin', clock_timestamp() - interval '8 days'),
      ('${idAdmin59}', '${userA}', 'Admin 59d', 'Msg', 'admin', clock_timestamp() - interval '59 days'),
      ('${idAdmin61}', '${userA}', 'Admin 61d', 'Msg', 'admin', clock_timestamp() - interval '61 days'),
      ('${idAdminCase61}', '${userA}', 'Admin trim case 61d', 'Msg', ' ADMIN ', clock_timestamp() - interval '61 days');

    insert into public.notification_popup_states (notification_id, display_count) values
      ('${idAdmin59}', 1),
      ('${idAdmin61}', 2);
  `);

  for (const item of keepOtherIds) {
    await db.query(
      `insert into public.notifications (id, user_id, title, message, type, data, created_at)
       values ($1, $2, $3, 'Msg', $4, $5::jsonb, clock_timestamp() - interval '6 days')`,
      [item.id, userA, `Keep ${item.type}`, item.type, item.data]
    );
  }
  await db.exec(`
    insert into public.notification_popup_states (notification_id, display_count)
    values ('${keepOtherIds[2].id}', 1);
  `);

  for (const item of expOtherIds) {
    await db.query(
      `insert into public.notifications (id, user_id, title, message, type, data, created_at)
       values ($1, $2, $3, 'Msg', $4, $5::jsonb, clock_timestamp() - interval '8 days')`,
      [item.id, userA, `Exp ${item.type}`, item.type, item.data]
    );
  }
  await db.exec(`
    insert into public.notification_popup_states (notification_id, display_count) values
      ('${expOtherIds[2].id}', 1),
      ('${expOtherIds[3].id}', 2);
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_notifications() as r`)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.status, 'success');
  assert.equal(res.deleted_admin_notifications, 2);
  assert.equal(res.deleted_other_notifications, expOtherIds.length);
  assert.equal(res.deleted, 2 + expOtherIds.length);

  const remainingIds = new Set((await rows(`select id from public.notifications`)).map(r => r.id));
  assert.ok(remainingIds.has(idAdmin8), 'admin notification at 8d must be kept');
  assert.ok(remainingIds.has(idAdmin59), 'admin notification at 59d must be kept');
  assert.ok(!remainingIds.has(idAdmin61), 'admin notification at 61d must be deleted');
  assert.ok(!remainingIds.has(idAdminCase61), 'trimmed/case-insensitive admin notification at 61d must be deleted');

  for (const item of keepOtherIds) {
    assert.ok(remainingIds.has(item.id), `6d notification of type ${item.type} must be kept`);
  }
  for (const item of expOtherIds) {
    assert.ok(!remainingIds.has(item.id), `8d notification of type "${item.type}" must be deleted`);
  }

  const popupStateIds = new Set((await rows(`select notification_id from public.notification_popup_states`)).map(r => r.notification_id));
  assert.ok(popupStateIds.has(idAdmin59), 'popup state of kept admin 59d notification must remain');
  assert.ok(popupStateIds.has(keepOtherIds[2].id), 'popup state of kept 6d campaign admin_pause notification must remain');
  assert.ok(!popupStateIds.has(idAdmin61), 'popup state of deleted admin 61d must cascade');
  assert.ok(!popupStateIds.has(expOtherIds[2].id), 'popup state of deleted 8d campaign admin_pause must cascade');
  assert.ok(!popupStateIds.has(expOtherIds[3].id), 'popup state of deleted 8d warning must cascade');
});

// ---------------------------------------------------------------------------
// 3. Campaign recipients cleanup & summary preservation
// ---------------------------------------------------------------------------
await test('Purges campaign recipients > 60d for finalized campaigns while preserving campaign summaries and keeping recent/active recipients', async () => {
  const campCompletedOld = 'd1000000-0000-4000-8000-000000000001';
  const campCancelledOld = 'd1000000-0000-4000-8000-000000000002';
  const campFailedOld = 'd1000000-0000-4000-8000-000000000003';
  const campCompletedRecent = 'd1000000-0000-4000-8000-000000000004';
  const campQueuedOld = 'd1000000-0000-4000-8000-000000000005';
  const campDraftOld = 'd1000000-0000-4000-8000-000000000006';

  await db.exec(`
    insert into public.notification_campaigns (
      id, title_vi, title_en, body_vi, body_en, status,
      total_recipients, sent_count, failed_count,
      completed_at, cancelled_at, updated_at, created_at, created_by
    ) values
      ('${campCompletedOld}', 'T1', 'T1', 'B1', 'B1', 'completed', 2, 2, 0, clock_timestamp() - interval '61 days', null, clock_timestamp() - interval '61 days', clock_timestamp() - interval '62 days', '${adminId}'),
      ('${campCancelledOld}', 'T2', 'T2', 'B2', 'B2', 'cancelled', 1, 0, 0, null, clock_timestamp() - interval '65 days', clock_timestamp() - interval '65 days', clock_timestamp() - interval '70 days', '${adminId}'),
      ('${campFailedOld}', 'T3', 'T3', 'B3', 'B3', 'failed', 1, 0, 1, null, null, clock_timestamp() - interval '62 days', clock_timestamp() - interval '63 days', '${adminId}'),
      ('${campCompletedRecent}', 'T4', 'T4', 'B4', 'B4', 'completed', 1, 1, 0, clock_timestamp() - interval '59 days', null, clock_timestamp() - interval '59 days', clock_timestamp() - interval '60 days', '${adminId}'),
      ('${campQueuedOld}', 'T5', 'T5', 'B5', 'B5', 'queued', 1, 0, 0, null, null, clock_timestamp() - interval '90 days', clock_timestamp() - interval '90 days', '${adminId}'),
      ('${campDraftOld}', 'T6', 'T6', 'B6', 'B6', 'draft', 1, 0, 0, null, null, clock_timestamp() - interval '90 days', clock_timestamp() - interval '90 days', '${adminId}');

    insert into public.notification_campaign_recipients (campaign_id, user_id, status) values
      ('${campCompletedOld}', '${userA}', 'sent'),
      ('${campCompletedOld}', '${userB}', 'sent'),
      ('${campCancelledOld}', '${userA}', 'pending'),
      ('${campFailedOld}', '${userA}', 'failed'),
      ('${campCompletedRecent}', '${userA}', 'sent'),
      ('${campQueuedOld}', '${userA}', 'pending'),
      ('${campDraftOld}', '${userA}', 'pending');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_notifications() as r`)).r;
  });

  assert.equal(res.deleted_campaign_recipients, 4, 'Must delete 4 recipient rows from finalized campaigns > 60d');

  const remainingRecipients = await rows(`select campaign_id from public.notification_campaign_recipients`);
  const remCampSet = new Set(remainingRecipients.map(r => r.campaign_id));
  assert.equal(remainingRecipients.length, 3);
  assert.ok(!remCampSet.has(campCompletedOld));
  assert.ok(!remCampSet.has(campCancelledOld));
  assert.ok(!remCampSet.has(campFailedOld));
  assert.ok(remCampSet.has(campCompletedRecent), 'Recent < 60d completed campaign recipients must stay');
  assert.ok(remCampSet.has(campQueuedOld), 'Queued campaign recipients must stay');
  assert.ok(remCampSet.has(campDraftOld), 'Draft campaign recipients must stay');

  const parentRow = await one(`
    select id, status, total_recipients, sent_count, failed_count
    from public.notification_campaigns
    where id = '${campCompletedOld}'
  `);
  assert.ok(parentRow, 'Parent campaign summary row must remain intact');
  assert.equal(parentRow.total_recipients, 2);
  assert.equal(parentRow.sent_count, 2);
  assert.equal(parentRow.failed_count, 0);
});

// ---------------------------------------------------------------------------
// 4. Popup receipts cleanup
// ---------------------------------------------------------------------------
await test('Purges expired popup receipts (>60d any, >7d orphaned) and preserves recent ones', async () => {
  const notifAdmin50 = 'e1000000-0000-4000-8000-000000000001';
  const notifAdmin2 = 'e1000000-0000-4000-8000-000000000002';

  const recOld61 = 'f1000000-0000-4000-8000-000000000001';
  const recOrphan8 = 'f1000000-0000-4000-8000-000000000002';
  const recOrphan6 = 'f1000000-0000-4000-8000-000000000003';
  const recLinked10 = 'f1000000-0000-4000-8000-000000000004';

  await db.exec(`
    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${notifAdmin50}', '${userA}', 'Admin 50d', 'Msg', 'admin', clock_timestamp() - interval '50 days'),
      ('${notifAdmin2}', '${userB}', 'Admin 2d', 'Msg', 'admin', clock_timestamp() - interval '2 days');

    insert into public.notification_popup_receipts (id, user_id, foreground_session_id, notification_id, display_number, claimed_at) values
      ('${recOld61}', '${userA}', gen_random_uuid(), '${notifAdmin50}', 1, clock_timestamp() - interval '61 days'),
      ('${recOrphan8}', '${userA}', gen_random_uuid(), null, 1, clock_timestamp() - interval '8 days'),
      ('${recOrphan6}', '${userA}', gen_random_uuid(), null, 1, clock_timestamp() - interval '6 days'),
      ('${recLinked10}', '${userB}', gen_random_uuid(), '${notifAdmin2}', 1, clock_timestamp() - interval '10 days');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_notifications() as r`)).r;
  });

  assert.equal(res.deleted_popup_receipts, 2);

  const remReceipts = new Set((await rows(`select id from public.notification_popup_receipts`)).map(r => r.id));
  assert.ok(!remReceipts.has(recOld61), 'Receipt > 60d must be deleted');
  assert.ok(!remReceipts.has(recOrphan8), 'Orphaned receipt > 7d must be deleted');
  assert.ok(remReceipts.has(recOrphan6), 'Orphaned receipt < 7d must be kept');
  assert.ok(remReceipts.has(recLinked10), 'Linked receipt at 10d (< 60d) must be kept');
});

// ---------------------------------------------------------------------------
// 5. Dry run mode
// ---------------------------------------------------------------------------
await test('Dry run logs status=dry_run with scanned counts and leaves all data intact', async () => {
  const expNotif = 'e2000000-0000-4000-8000-000000000001';
  const expOrphanReceipt = 'f2000000-0000-4000-8000-000000000001';
  const campOld = 'd2000000-0000-4000-8000-000000000001';

  await db.exec(`
    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${expNotif}', '${userA}', 'Old Warning', 'Msg', 'warning', clock_timestamp() - interval '10 days');
    insert into public.notification_popup_states (notification_id, display_count) values
      ('${expNotif}', 1);
    insert into public.notification_popup_receipts (id, user_id, foreground_session_id, notification_id, display_number, claimed_at) values
      ('${expOrphanReceipt}', '${userA}', gen_random_uuid(), null, 1, clock_timestamp() - interval '10 days');
    insert into public.notification_campaigns (
      id, title_vi, title_en, body_vi, body_en, status,
      total_recipients, sent_count, failed_count,
      completed_at, updated_at, created_at, created_by
    ) values (
      '${campOld}', 'T', 'T', 'B', 'B', 'completed', 1, 1, 0,
      clock_timestamp() - interval '70 days', clock_timestamp() - interval '70 days', clock_timestamp() - interval '70 days', '${adminId}'
    );
    insert into public.notification_campaign_recipients (campaign_id, user_id, status) values
      ('${campOld}', '${userA}', 'sent');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_notifications(7, 60, true, 5000) as r`)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.status, 'dry_run');
  assert.equal(res.dry_run, true);
  assert.equal(res.scanned, 3);
  assert.equal(res.deleted, 0);
  assert.equal(res.scanned_other_notifications, 1);
  assert.equal(res.scanned_campaign_recipients, 1);
  assert.equal(res.scanned_popup_receipts, 1);

  const runLog = await one(`select * from public.cleanup_runs where id = '${res.run_id}'`);
  assert.equal(runLog.job_name, 'purge_expired_notifications');
  assert.equal(runLog.status, 'dry_run');
  assert.equal(runLog.scanned, 3);
  assert.equal(runLog.deleted, 0);
  assert.equal(runLog.details.deleted_other_notifications, 0);

  assert.equal((await one(`select count(*)::int as c from public.notifications where id = '${expNotif}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.notification_popup_states where notification_id = '${expNotif}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.notification_popup_receipts where id = '${expOrphanReceipt}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.notification_campaign_recipients where campaign_id = '${campOld}'`)).c, 1);
});

// ---------------------------------------------------------------------------
// 6. Batching oldest-first & parameter clamping
// ---------------------------------------------------------------------------
await test('Respects p_batch_size oldest-first and clamps retention bounds', async () => {
  const idOldest = 'e3000000-0000-4000-8000-000000000001';
  const idMiddle = 'e3000000-0000-4000-8000-000000000002';
  const idNewest = 'e3000000-0000-4000-8000-000000000003';
  const idAdmin4d = 'e3000000-0000-4000-8000-000000000004';

  await db.exec(`
    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${idOldest}', '${userA}', '1', 'Msg', 'general', clock_timestamp() - interval '20 days'),
      ('${idMiddle}', '${userA}', '2', 'Msg', 'general', clock_timestamp() - interval '15 days'),
      ('${idNewest}', '${userA}', '3', 'Msg', 'general', clock_timestamp() - interval '10 days'),
      ('${idAdmin4d}', '${userA}', '4', 'Msg', 'admin', clock_timestamp() - interval '4 days');
  `);

  // Passing p_other_retention_days = 0 -> clamped to 1; p_admin_retention_days = 2 -> clamped to >= 1 (= 2),
  // so idAdmin4d (4 days old) IS deleted on second pass, while batch_size = 2 deletes oldest 2 first!
  const firstBatch = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_notifications(5, 2, false, 2) as r`)).r;
  });

  // p_other=5, p_admin=2 -> clamped admin_retention_days = greatest(5, 2) = 5, so 4d admin is NOT expired.
  assert.equal(firstBatch.other_retention_days, 5);
  assert.equal(firstBatch.admin_retention_days, 5);
  assert.equal(firstBatch.batch_size, 2);
  assert.equal(firstBatch.deleted, 2);

  const afterFirst = new Set((await rows(`select id from public.notifications`)).map(r => r.id));
  assert.ok(!afterFirst.has(idOldest));
  assert.ok(!afterFirst.has(idMiddle));
  assert.ok(afterFirst.has(idNewest), 'Newest expired row should wait for next batch');
  assert.ok(afterFirst.has(idAdmin4d), '4d admin notification must be kept when clamped admin retention is 5d');

  const secondBatch = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_notifications(5, 2, false, 2) as r`)).r;
  });
  assert.equal(secondBatch.deleted, 1);
  assert.equal(
    (await one(`
      select count(*)::int as c
      from public.notifications
      where id in ('${idOldest}', '${idMiddle}', '${idNewest}', '${idAdmin4d}')
    `)).c,
    1
  );
  assert.equal(
    (await one(`select count(*)::int as c from public.notifications where id = '${idAdmin4d}'`)).c,
    1
  );
});

// ---------------------------------------------------------------------------
// 7. sync_campaign_lifecycle() fallback trigger & 6-hour throttle
// ---------------------------------------------------------------------------
await test('sync_campaign_lifecycle() triggers fallback purge when no recent success run exists and skips when one exists within 6h', async () => {
  const expNotif1 = 'e4000000-0000-4000-8000-000000000001';
  const expNotif2 = 'e4000000-0000-4000-8000-000000000002';

  // Insert an old (> 6h) success run and a recent dry_run (should NOT block fallback)
  await db.exec(`
    insert into public.cleanup_runs (job_name, status, started_at, finished_at) values
      ('purge_expired_notifications', 'success', clock_timestamp() - interval '7 hours', clock_timestamp() - interval '7 hours'),
      ('purge_expired_notifications', 'dry_run', clock_timestamp() - interval '1 hour', clock_timestamp() - interval '1 hour');

    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${expNotif1}', '${userA}', 'Expired 1', 'Msg', 'general', clock_timestamp() - interval '9 days');
  `);

  const syncRes1 = await asRole('authenticated', userA, async () => {
    return (await one(`select public.sync_campaign_lifecycle() as r`)).r;
  });

  assert.ok('completed' in syncRes1);
  assert.ok('paused' in syncRes1);
  assert.ok('slot_paused' in syncRes1);

  assert.equal(
    (await one(`select count(*)::int as c from public.notifications where id = '${expNotif1}'`)).c,
    0,
    'sync_campaign_lifecycle fallback must purge expired notifications when last success was > 6h ago'
  );

  const successRuns = await rows(`
    select status, deleted
    from public.cleanup_runs
    where job_name = 'purge_expired_notifications'
      and status = 'success'
      and started_at > clock_timestamp() - interval '6 hours'
  `);
  assert.equal(successRuns.length, 1);
  assert.equal(successRuns[0].deleted, 1);

  // Now insert another expired notification and call sync_campaign_lifecycle() again within 6h:
  // It should skip running purge_expired_notifications again.
  await db.exec(`
    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${expNotif2}', '${userA}', 'Expired 2', 'Msg', 'general', clock_timestamp() - interval '9 days');
  `);

  const syncRes2 = await asRole('anon', null, async () => {
    return (await one(`select public.sync_campaign_lifecycle() as r`)).r;
  });
  assert.ok('completed' in syncRes2);

  assert.equal(
    (await one(`select count(*)::int as c from public.notifications where id = '${expNotif2}'`)).c,
    1,
    'sync_campaign_lifecycle must not re-run purge within 6 hours of a successful run'
  );
});

console.log('=== ALL NOTIFICATION CLEANUP TESTS PASSED ===');
