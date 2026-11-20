import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

const adminId = '11111111-1111-4111-8111-111111111111';
const userA = '22222222-2222-4222-8222-222222222222';
const userB = '33333333-3333-4333-8333-333333333333';
const campId = '44444444-4444-4444-8444-444444444444';

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

console.log('=== LOADING ALL ACTIVE MIGRATIONS (062 THROUGH 098) ===');
const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62)
  .sort();

for (const name of migrationFiles) {
  await db.exec(read('supabase/migrations/' + name));
}
console.log(`Loaded ${migrationFiles.length} migrations up to ${migrationFiles.at(-1)}.`);

await db.exec(`
  insert into auth.users (id, email) values
    ('${adminId}', 'admin@op-cleanup.test'),
    ('${userA}', 'usera@op-cleanup.test'),
    ('${userB}', 'userb@op-cleanup.test');

  insert into public.profiles (id, email, display_name, role, is_locked) values
    ('${adminId}', 'admin@op-cleanup.test', 'Admin', 'admin', false),
    ('${userA}', 'usera@op-cleanup.test', 'User A', 'member', false),
    ('${userB}', 'userb@op-cleanup.test', 'User B', 'member', false)
  on conflict (id) do update set
    role = excluded.role,
    is_locked = excluded.is_locked;

  insert into public.campaigns (id, owner_id, app_name, package_name, status) values
    ('${campId}', '${adminId}', 'Cleanup Test App', 'com.test.cleanup', 'open');

  insert into public.campaign_members (campaign_id, user_id) values
    ('${campId}', '${userA}'),
    ('${campId}', '${userB}');
`);

console.log('=== RUNNING OPERATIONAL CLEANUP TESTS ===');

// ---------------------------------------------------------------------------
// 1. Indexes, SECURITY DEFINER, search_path, and ACLs
// ---------------------------------------------------------------------------
await test('Indexes, SECURITY DEFINER, and ACL grants are properly configured', async () => {
  const indexes = await rows(`
    select indexname
    from pg_indexes
    where schemaname = 'public'
      and indexname in (
        'idx_report_submission_receipts_created_at',
        'idx_report_cancelled_requests_created_at'
      )
    order by indexname
  `);
  assert.deepEqual(
    indexes.map(r => r.indexname),
    [
      'idx_report_cancelled_requests_created_at',
      'idx_report_submission_receipts_created_at'
    ]
  );

  const fnMeta = await one(`
    select prosecdef, proconfig
    from pg_proc
    where pronamespace = 'public'::regnamespace
      and proname = 'purge_expired_operational_data'
  `);
  assert.equal(fnMeta.prosecdef, true, 'purge_expired_operational_data must be SECURITY DEFINER');
  assert.ok(fnMeta.proconfig?.includes('search_path=public'), 'search_path must be fixed to public');

  const sig = 'public.purge_expired_operational_data(integer, integer, integer, integer, integer, integer, boolean, integer)';
  const privs = await one(`
    select
      has_function_privilege('anon', '${sig}', 'EXECUTE') as anon_exec,
      has_function_privilege('authenticated', '${sig}', 'EXECUTE') as auth_exec,
      has_function_privilege('service_role', '${sig}', 'EXECUTE') as service_exec,
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
    () => asRole('anon', null, () => db.query(`select public.purge_expired_operational_data()`)),
    /permission denied/i
  );
  await assert.rejects(
    () => asRole('authenticated', userA, () => db.query(`select public.purge_expired_operational_data()`)),
    /permission denied/i
  );
});

// ---------------------------------------------------------------------------
// 2. Group A: report_submission_receipts (16d) & report_cancelled_requests (16d)
// ---------------------------------------------------------------------------
await test('Purges expired report_submission_receipts & report_cancelled_requests (>16d) while protecting pending reports', async () => {
  const repApproved = 'a1000000-0000-4000-8000-000000000001';
  const repPending = 'a1000000-0000-4000-8000-000000000002';

  const recExpNull = 'b1000000-0000-4000-8000-000000000001';
  const recExpApproved = 'b1000000-0000-4000-8000-000000000002';
  const recKeepPending = 'b1000000-0000-4000-8000-000000000003';
  const recKeepFresh = 'b1000000-0000-4000-8000-000000000004';

  const cancelExp = 'c1000000-0000-4000-8000-000000000001';
  const cancelKeep = 'c1000000-0000-4000-8000-000000000002';

  await db.exec(`
    insert into public.tester_reports (id, campaign_id, user_id, report_type, content, status, created_at) values
      ('${repApproved}', '${campId}', '${userA}', 'feedback', 'ok', 'approved', clock_timestamp() - interval '20 days'),
      ('${repPending}', '${campId}', '${userB}', 'feedback', 'still pending', 'pending', clock_timestamp() - interval '20 days');

    insert into public.report_submission_receipts (id, user_id, client_submit_id, campaign_id, report_id, payload_fingerprint, response_json, created_at) values
      ('${recExpNull}', '${userA}', gen_random_uuid(), '${campId}', null, 'fp1', '{}'::jsonb, clock_timestamp() - interval '17 days'),
      ('${recExpApproved}', '${userA}', gen_random_uuid(), '${campId}', '${repApproved}', 'fp2', '{}'::jsonb, clock_timestamp() - interval '17 days'),
      ('${recKeepPending}', '${userB}', gen_random_uuid(), '${campId}', '${repPending}', 'fp3', '{}'::jsonb, clock_timestamp() - interval '25 days'),
      ('${recKeepFresh}', '${userA}', gen_random_uuid(), '${campId}', '${repApproved}', 'fp4', '{}'::jsonb, clock_timestamp() - interval '15 days');

    insert into public.report_cancelled_requests (user_id, client_submit_id, created_at) values
      ('${userA}', '${cancelExp}', clock_timestamp() - interval '17 days'),
      ('${userB}', '${cancelKeep}', clock_timestamp() - interval '15 days');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_operational_data() as r`)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.deleted_report_submission_receipts, 2);
  assert.equal(res.deleted_report_cancelled_requests, 1);

  const remReceipts = new Set((await rows(`select id from public.report_submission_receipts`)).map(r => r.id));
  assert.ok(!remReceipts.has(recExpNull));
  assert.ok(!remReceipts.has(recExpApproved));
  assert.ok(remReceipts.has(recKeepPending), 'Receipt linked to pending report must be kept');
  assert.ok(remReceipts.has(recKeepFresh), 'Receipt < 16d must be kept');

  const remCancelled = new Set((await rows(`select client_submit_id from public.report_cancelled_requests`)).map(r => r.client_submit_id));
  assert.ok(!remCancelled.has(cancelExp));
  assert.ok(remCancelled.has(cancelKeep), 'Cancelled request < 16d must be kept');
});

// ---------------------------------------------------------------------------
// 3. Group A (30d): admin_operation_requests, business_policy_admin_requests,
//    notification_campaign_requests, app_update_operations
// ---------------------------------------------------------------------------
await test('Purges expired 30d operation ledgers while preserving active campaign/release operations', async () => {
  const adminOpExp = 'd1000000-0000-4000-8000-000000000001';
  const adminOpKeep = 'd1000000-0000-4000-8000-000000000002';
  const polOpExp = 'd2000000-0000-4000-8000-000000000001';
  const polOpKeep = 'd2000000-0000-4000-8000-000000000002';

  const campDone = 'e1000000-0000-4000-8000-000000000001';
  const campCancelled = 'e1000000-0000-4000-8000-000000000002';
  const campFailed = 'e1000000-0000-4000-8000-000000000003';
  const campSending = 'e1000000-0000-4000-8000-000000000004';
  const campQueued = 'e1000000-0000-4000-8000-000000000005';

  const nreqExpNull = 'f1000000-0000-4000-8000-000000000001';
  const nreqExpDone = 'f1000000-0000-4000-8000-000000000002';
  const nreqExpCanc = 'f1000000-0000-4000-8000-000000000003';
  const nreqExpFail = 'f1000000-0000-4000-8000-000000000004';
  const nreqKeepSending = 'f1000000-0000-4000-8000-000000000005';
  const nreqKeepQueued = 'f1000000-0000-4000-8000-000000000006';
  const nreqKeepFresh = 'f1000000-0000-4000-8000-000000000007';

  const relExhausted = 'e2000000-0000-4000-8000-000000000001';
  const relSuperseded = 'e2000000-0000-4000-8000-000000000002';
  const relClosed = 'e2000000-0000-4000-8000-000000000003';
  const relActive = 'e2000000-0000-4000-8000-000000000004';

  await db.exec(`
    insert into public.admin_operation_requests (id, actor_id, operation, payload_hash, result, created_at) values
      ('${adminOpExp}', '${adminId}', 'warn_user', 'h1', '{}'::jsonb, clock_timestamp() - interval '31 days'),
      ('${adminOpKeep}', '${adminId}', 'warn_user', 'h2', '{}'::jsonb, clock_timestamp() - interval '29 days');

    insert into public.business_policy_admin_requests (id, actor_id, operation, payload_hash, result, created_at) values
      ('${polOpExp}', '${adminId}', 'schedule', 'h1', '{}'::jsonb, clock_timestamp() - interval '31 days'),
      ('${polOpKeep}', '${adminId}', 'cancel', 'h2', '{}'::jsonb, clock_timestamp() - interval '29 days');

    insert into public.notification_campaigns (
      id, title_vi, title_en, body_vi, body_en, status, created_by, created_at, updated_at
    ) values
      ('${campDone}', 'T', 'T', 'B', 'B', 'completed', '${adminId}', clock_timestamp() - interval '40 days', clock_timestamp() - interval '40 days'),
      ('${campCancelled}', 'T', 'T', 'B', 'B', 'cancelled', '${adminId}', clock_timestamp() - interval '40 days', clock_timestamp() - interval '40 days'),
      ('${campFailed}', 'T', 'T', 'B', 'B', 'failed', '${adminId}', clock_timestamp() - interval '40 days', clock_timestamp() - interval '40 days'),
      ('${campSending}', 'T', 'T', 'B', 'B', 'sending', '${adminId}', clock_timestamp() - interval '40 days', clock_timestamp() - interval '40 days'),
      ('${campQueued}', 'T', 'T', 'B', 'B', 'queued', '${adminId}', clock_timestamp() - interval '40 days', clock_timestamp() - interval '40 days');

    insert into public.notification_campaign_requests (
      request_id, admin_id, action, campaign_id, payload_hash, response_json, created_at
    ) values
      ('${nreqExpNull}', '${adminId}', 'create', null, 'h', '{}'::jsonb, clock_timestamp() - interval '31 days'),
      ('${nreqExpDone}', '${adminId}', 'send', '${campDone}', 'h', '{}'::jsonb, clock_timestamp() - interval '31 days'),
      ('${nreqExpCanc}', '${adminId}', 'cancel', '${campCancelled}', 'h', '{}'::jsonb, clock_timestamp() - interval '31 days'),
      ('${nreqExpFail}', '${adminId}', 'send', '${campFailed}', 'h', '{}'::jsonb, clock_timestamp() - interval '31 days'),
      ('${nreqKeepSending}', '${adminId}', 'send', '${campSending}', 'h', '{}'::jsonb, clock_timestamp() - interval '40 days'),
      ('${nreqKeepQueued}', '${adminId}', 'queue', '${campQueued}', 'h', '{}'::jsonb, clock_timestamp() - interval '40 days'),
      ('${nreqKeepFresh}', '${adminId}', 'send', '${campDone}', 'h', '{}'::jsonb, clock_timestamp() - interval '29 days');

    insert into public.app_update_releases (
      id, campaign_id, owner_id, package_name, version_name, target_version_code, status
    ) values
      ('${relExhausted}', '${campId}', '${adminId}', 'com.test.cleanup', '1.0', 1, 'exhausted'),
      ('${relSuperseded}', '${campId}', '${adminId}', 'com.test.cleanup', '1.1', 2, 'superseded'),
      ('${relClosed}', '${campId}', '${adminId}', 'com.test.cleanup', '1.2', 3, 'closed'),
      ('${relActive}', '${campId}', '${adminId}', 'com.test.cleanup', '1.3', 4, 'active');

    insert into public.app_update_operations (
      operation_id, owner_id, release_id, status, created_at
    ) values
      ('op-exp-null', '${adminId}', null, 'completed', clock_timestamp() - interval '31 days'),
      ('op-exp-exhausted', '${adminId}', '${relExhausted}', 'completed', clock_timestamp() - interval '31 days'),
      ('op-exp-superseded', '${adminId}', '${relSuperseded}', 'completed', clock_timestamp() - interval '31 days'),
      ('op-exp-closed', '${adminId}', '${relClosed}', 'completed', clock_timestamp() - interval '31 days'),
      ('op-keep-active', '${adminId}', '${relActive}', 'completed', clock_timestamp() - interval '45 days'),
      ('op-keep-fresh', '${adminId}', '${relClosed}', 'completed', clock_timestamp() - interval '29 days');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_operational_data() as r`)).r;
  });

  assert.equal(res.deleted_admin_operation_requests, 1);
  assert.equal(res.deleted_business_policy_admin_requests, 1);
  assert.equal(res.deleted_notification_campaign_requests, 4);
  assert.equal(res.deleted_app_update_operations, 4);

  const remAdmin = new Set((await rows(`select id from public.admin_operation_requests`)).map(r => r.id));
  assert.ok(!remAdmin.has(adminOpExp));
  assert.ok(remAdmin.has(adminOpKeep));

  const remPol = new Set((await rows(`select id from public.business_policy_admin_requests`)).map(r => r.id));
  assert.ok(!remPol.has(polOpExp));
  assert.ok(remPol.has(polOpKeep));

  const remNotifReq = new Set((await rows(`select request_id from public.notification_campaign_requests`)).map(r => r.request_id));
  assert.ok(!remNotifReq.has(nreqExpNull));
  assert.ok(!remNotifReq.has(nreqExpDone));
  assert.ok(!remNotifReq.has(nreqExpCanc));
  assert.ok(!remNotifReq.has(nreqExpFail));
  assert.ok(remNotifReq.has(nreqKeepSending), 'sending campaign request must be kept');
  assert.ok(remNotifReq.has(nreqKeepQueued), 'queued campaign request must be kept');
  assert.ok(remNotifReq.has(nreqKeepFresh), '<30d completed campaign request must be kept');

  const remAppOps = new Set((await rows(`select operation_id from public.app_update_operations`)).map(r => r.operation_id));
  assert.ok(!remAppOps.has('op-exp-null'));
  assert.ok(!remAppOps.has('op-exp-exhausted'));
  assert.ok(!remAppOps.has('op-exp-superseded'));
  assert.ok(!remAppOps.has('op-exp-closed'));
  assert.ok(remAppOps.has('op-keep-active'), 'active release operation must be kept');
  assert.ok(remAppOps.has('op-keep-fresh'), '<30d closed release operation must be kept');
});

// ---------------------------------------------------------------------------
// 4. Group B: report_media_uploads (16d) + tombstone & storage.objects guards
// ---------------------------------------------------------------------------
await test('Purges expired report_media_uploads (16d) while strictly enforcing 093 tombstone & pending-report guards', async () => {
  const repApproved = 'a2000000-0000-4000-8000-000000000001';
  const repPending = 'a2000000-0000-4000-8000-000000000002';

  const mSupaExpNoThumb = 'b2000000-0000-4000-8000-000000000001';
  const mSupaExpWithThumb = 'b2000000-0000-4000-8000-000000000002';
  const mSupaTombThumbUncomm = 'b2000000-0000-4000-8000-000000000003';
  const mSupaTombMainUncomm = 'b2000000-0000-4000-8000-000000000004';
  const mSupaReservedBytes = 'b2000000-0000-4000-8000-000000000005';
  const mSupaQuotaBytes = 'b2000000-0000-4000-8000-000000000006';
  const mSupaStillInStorage = 'b2000000-0000-4000-8000-000000000007';
  const mSupaFinalized = 'b2000000-0000-4000-8000-000000000008';
  const mSupaFresh = 'b2000000-0000-4000-8000-000000000009';

  const mGcsExpNull = 'b2000000-0000-4000-8000-000000000010';
  const mGcsExpApproved = 'b2000000-0000-4000-8000-000000000011';
  const mGcsKeepPending = 'b2000000-0000-4000-8000-000000000012';
  const mGcsKeepFresh = 'b2000000-0000-4000-8000-000000000013';

  await db.exec(`
    insert into public.tester_reports (id, campaign_id, user_id, report_type, content, status, created_at) values
      ('${repApproved}', '${campId}', '${userA}', 'feedback', 'ok', 'approved', clock_timestamp() - interval '20 days'),
      ('${repPending}', '${campId}', '${userB}', 'feedback', 'pending', 'pending', clock_timestamp() - interval '20 days');

    insert into storage.objects (bucket_id, name, owner, metadata) values
      ('report-screenshots', 'reports/${campId}/${userA}/still_there.jpg', '${userA}', '{"size": 100}'::jsonb);

    insert into public.report_media_uploads (
      id, upload_id, user_id, campaign_id, report_id, provider, bucket, object_path, thumbnail_object_path,
      reserved_bytes, quota_accounted_bytes, status, main_committed, thumbnail_committed,
      deleted_at, updated_at, created_at
    ) values
      ('${mSupaExpNoThumb}', 'u1', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u1.jpg', null,
       0, 0, 'deleted', true, false, clock_timestamp() - interval '17 days', clock_timestamp() - interval '17 days', clock_timestamp() - interval '27 days'),

      ('${mSupaExpWithThumb}', 'u2', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u2.jpg', 'reports/${campId}/${userA}/thumb_u2.jpg',
       0, 0, 'deleted', true, true, clock_timestamp() - interval '17 days', clock_timestamp() - interval '17 days', clock_timestamp() - interval '27 days'),

      ('${mSupaTombThumbUncomm}', 'u3', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u3.jpg', 'reports/${campId}/${userA}/thumb_u3.jpg',
       0, 0, 'deleted', true, false, clock_timestamp() - interval '30 days', clock_timestamp() - interval '30 days', clock_timestamp() - interval '40 days'),

      ('${mSupaTombMainUncomm}', 'u4', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u4.jpg', null,
       0, 0, 'deleted', false, false, clock_timestamp() - interval '30 days', clock_timestamp() - interval '30 days', clock_timestamp() - interval '40 days'),

      ('${mSupaReservedBytes}', 'u5', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u5.jpg', null,
       150000, 0, 'deleted', true, false, clock_timestamp() - interval '30 days', clock_timestamp() - interval '30 days', clock_timestamp() - interval '40 days'),

      ('${mSupaQuotaBytes}', 'u6', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u6.jpg', null,
       0, 1024, 'deleted', true, false, clock_timestamp() - interval '30 days', clock_timestamp() - interval '30 days', clock_timestamp() - interval '40 days'),

      ('${mSupaStillInStorage}', 'u7', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/still_there.jpg', null,
       0, 0, 'deleted', true, false, clock_timestamp() - interval '30 days', clock_timestamp() - interval '30 days', clock_timestamp() - interval '40 days'),

      ('${mSupaFinalized}', 'u8', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u8.jpg', null,
       0, 0, 'finalized', true, false, null, clock_timestamp() - interval '30 days', clock_timestamp() - interval '40 days'),

      ('${mSupaFresh}', 'u9', '${userA}', '${campId}', '${repApproved}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/u9.jpg', null,
       0, 0, 'deleted', true, false, clock_timestamp() - interval '15 days', clock_timestamp() - interval '15 days', clock_timestamp() - interval '25 days'),

      ('${mGcsExpNull}', 'g1', '${userA}', '${campId}', null, 'gcs', 'cross-testers.firebasestorage.app', 'reports/${campId}/${userA}/g1.jpg', null,
       0, 0, 'finalized', false, false, null, clock_timestamp() - interval '17 days', clock_timestamp() - interval '17 days'),

      ('${mGcsExpApproved}', 'g2', '${userA}', '${campId}', '${repApproved}', 'gcs', 'cross-testers.firebasestorage.app', 'reports/${campId}/${userA}/g2.jpg', null,
       0, 0, 'finalized', false, false, null, clock_timestamp() - interval '17 days', clock_timestamp() - interval '17 days'),

      ('${mGcsKeepPending}', 'g3', '${userB}', '${campId}', '${repPending}', 'gcs', 'cross-testers.firebasestorage.app', 'reports/${campId}/${userB}/g3.jpg', null,
       0, 0, 'finalized', false, false, null, clock_timestamp() - interval '30 days', clock_timestamp() - interval '30 days'),

      ('${mGcsKeepFresh}', 'g4', '${userA}', '${campId}', '${repApproved}', 'gcs', 'cross-testers.firebasestorage.app', 'reports/${campId}/${userA}/g4.jpg', null,
       0, 0, 'finalized', false, false, null, clock_timestamp() - interval '15 days', clock_timestamp() - interval '15 days');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_operational_data() as r`)).r;
  });

  assert.equal(res.deleted_report_media_uploads, 4);

  const remMedia = new Set((await rows(`select id from public.report_media_uploads`)).map(r => r.id));
  assert.ok(!remMedia.has(mSupaExpNoThumb));
  assert.ok(!remMedia.has(mSupaExpWithThumb));
  assert.ok(!remMedia.has(mGcsExpNull));
  assert.ok(!remMedia.has(mGcsExpApproved));

  assert.ok(remMedia.has(mSupaTombThumbUncomm), 'Uncommitted thumbnail tombstone must be kept');
  assert.ok(remMedia.has(mSupaTombMainUncomm), 'Uncommitted main tombstone must be kept');
  assert.ok(remMedia.has(mSupaReservedBytes), 'Row with reserved_bytes > 0 must be kept');
  assert.ok(remMedia.has(mSupaQuotaBytes), 'Row with quota_accounted_bytes > 0 must be kept');
  assert.ok(remMedia.has(mSupaStillInStorage), 'Row with storage.objects still present must be kept');
  assert.ok(remMedia.has(mSupaFinalized), 'Non-deleted supabase row must be kept');
  assert.ok(remMedia.has(mSupaFresh), 'Deleted supabase row < 16d must be kept');
  assert.ok(remMedia.has(mGcsKeepPending), 'GCS row linked to pending report must be kept');
  assert.ok(remMedia.has(mGcsKeepFresh), 'GCS row < 16d must be kept');
});

// ---------------------------------------------------------------------------
// 5. Group B & C: cleanup_runs (30d), user_quest_claims (30d HCMC), contact_reports (60d)
// ---------------------------------------------------------------------------
await test('Purges expired cleanup_runs (30d), user_quest_claims (30d HCMC), and contact_reports (60d closed only)', async () => {
  const crExpSuccess = 'c2000000-0000-4000-8000-000000000001';
  const crExpDryRun = 'c2000000-0000-4000-8000-000000000002';
  const crKeepRunning = 'c2000000-0000-4000-8000-000000000003';
  const crKeepWithin30d = 'c2000000-0000-4000-8000-000000000004';

  const qcExp = 'c3000000-0000-4000-8000-000000000001';
  const qcKeepBoundary = 'c3000000-0000-4000-8000-000000000002';
  const qcKeepToday = 'c3000000-0000-4000-8000-000000000003';

  const ctExpResolved = 'c4000000-0000-4000-8000-000000000001';
  const ctExpReplied = 'c4000000-0000-4000-8000-000000000002';
  const ctKeepNew = 'c4000000-0000-4000-8000-000000000003';
  const ctKeepRecentReply = 'c4000000-0000-4000-8000-000000000004';
  const ctKeep59d = 'c4000000-0000-4000-8000-000000000005';

  await db.exec(`
    insert into public.cleanup_runs (id, job_name, status, started_at, finished_at) values
      ('${crExpSuccess}', 'old_job', 'success', clock_timestamp() - interval '32 days', clock_timestamp() - interval '31 days'),
      ('${crExpDryRun}', 'old_job', 'dry_run', clock_timestamp() - interval '35 days', null),
      ('${crKeepRunning}', 'stuck_job', 'running', clock_timestamp() - interval '45 days', null),
      ('${crKeepWithin30d}', 'purge_completed_campaigns', 'success', clock_timestamp() - interval '29 days', clock_timestamp() - interval '29 days');

    insert into public.user_quest_claims (id, user_id, quest_type, milestone, reward_credits, period_key) values
      ('${qcExp}', '${userA}', 'daily', 10, 5, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date - 31),
      ('${qcKeepBoundary}', '${userA}', 'daily', 20, 15, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date - 30),
      ('${qcKeepToday}', '${userA}', 'weekly', 10, 5, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date);

    insert into public.contact_reports (id, user_id, category, message, status, admin_reply, replied_at, created_at) values
      ('${ctExpResolved}', '${userA}', 'bug', 'old bug', 'resolved', 'fixed', clock_timestamp() - interval '61 days', clock_timestamp() - interval '65 days'),
      ('${ctExpReplied}', '${userA}', 'other', 'old q', 'replied', null, null, clock_timestamp() - interval '61 days'),
      ('${ctKeepNew}', '${userA}', 'bug', 'open ticket', 'new', null, null, clock_timestamp() - interval '120 days'),
      ('${ctKeepRecentReply}', '${userA}', 'bug', 'replied recently', 'replied', 'hi', clock_timestamp() - interval '10 days', clock_timestamp() - interval '90 days'),
      ('${ctKeep59d}', '${userA}', 'feedback', '59d', 'resolved', 'thx', clock_timestamp() - interval '59 days', clock_timestamp() - interval '59 days');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_operational_data() as r`)).r;
  });

  assert.equal(res.deleted_cleanup_runs, 2);
  assert.equal(res.deleted_user_quest_claims, 1);
  assert.equal(res.deleted_contact_reports, 2);

  const remRuns = new Set((await rows(`select id from public.cleanup_runs`)).map(r => r.id));
  assert.ok(!remRuns.has(crExpSuccess));
  assert.ok(!remRuns.has(crExpDryRun));
  assert.ok(remRuns.has(crKeepRunning), 'running cleanup_runs row must not be deleted');
  assert.ok(remRuns.has(crKeepWithin30d), 'cleanup_runs < 30d must be kept');
  assert.ok(remRuns.has(res.run_id), 'current cleanup_runs row must not be deleted');

  const remQuests = new Set((await rows(`select id from public.user_quest_claims`)).map(r => r.id));
  assert.ok(!remQuests.has(qcExp));
  assert.ok(remQuests.has(qcKeepBoundary), 'quest claim at exact -30d boundary must be kept');
  assert.ok(remQuests.has(qcKeepToday));

  const remContacts = new Set((await rows(`select id from public.contact_reports`)).map(r => r.id));
  assert.ok(!remContacts.has(ctExpResolved));
  assert.ok(!remContacts.has(ctExpReplied));
  assert.ok(remContacts.has(ctKeepNew), 'new contact report must never be deleted');
  assert.ok(remContacts.has(ctKeepRecentReply), 'contact report with recent replied_at must be kept');
  assert.ok(remContacts.has(ctKeep59d), 'contact report < 60d must be kept');
});

// ---------------------------------------------------------------------------
// 6. Dry run mode
// ---------------------------------------------------------------------------
await test('Dry run logs status=dry_run with per-table scanned counts and leaves all 10 tables untouched', async () => {
  const recId = 'e5000000-0000-4000-8000-000000000001';
  const cancelSubId = 'e5000000-0000-4000-8000-000000000002';
  const adminOpId = 'e5000000-0000-4000-8000-000000000003';
  const polOpId = 'e5000000-0000-4000-8000-000000000004';
  const notifReqId = 'e5000000-0000-4000-8000-000000000005';
  const mediaId = 'e5000000-0000-4000-8000-000000000006';
  const crId = 'e5000000-0000-4000-8000-000000000007';
  const qcId = 'e5000000-0000-4000-8000-000000000008';
  const ctId = 'e5000000-0000-4000-8000-000000000009';

  await db.exec(`
    insert into public.report_submission_receipts (id, user_id, client_submit_id, campaign_id, report_id, payload_fingerprint, response_json, created_at)
    values ('${recId}', '${userA}', gen_random_uuid(), '${campId}', null, 'fp', '{}'::jsonb, clock_timestamp() - interval '20 days');

    insert into public.report_cancelled_requests (user_id, client_submit_id, created_at)
    values ('${userA}', '${cancelSubId}', clock_timestamp() - interval '20 days');

    insert into public.admin_operation_requests (id, actor_id, operation, payload_hash, result, created_at)
    values ('${adminOpId}', '${adminId}', 'op', 'h', '{}'::jsonb, clock_timestamp() - interval '40 days');

    insert into public.business_policy_admin_requests (id, actor_id, operation, payload_hash, result, created_at)
    values ('${polOpId}', '${adminId}', 'schedule', 'h', '{}'::jsonb, clock_timestamp() - interval '40 days');

    insert into public.notification_campaign_requests (request_id, admin_id, action, campaign_id, payload_hash, response_json, created_at)
    values ('${notifReqId}', '${adminId}', 'create', null, 'h', '{}'::jsonb, clock_timestamp() - interval '40 days');

    insert into public.app_update_operations (operation_id, owner_id, release_id, status, created_at)
    values ('dry-op-1', '${adminId}', null, 'completed', clock_timestamp() - interval '40 days');

    insert into public.report_media_uploads (
      id, upload_id, user_id, campaign_id, provider, bucket, object_path, reserved_bytes, quota_accounted_bytes, status, main_committed, deleted_at, created_at
    ) values (
      '${mediaId}', 'dry-m1', '${userA}', '${campId}', 'supabase', 'report-screenshots', 'reports/${campId}/${userA}/dry.jpg', 0, 0, 'deleted', true, clock_timestamp() - interval '20 days', clock_timestamp() - interval '30 days'
    );

    insert into public.cleanup_runs (id, job_name, status, started_at, finished_at)
    values ('${crId}', 'old', 'success', clock_timestamp() - interval '40 days', clock_timestamp() - interval '40 days');

    insert into public.user_quest_claims (id, user_id, quest_type, milestone, reward_credits, period_key)
    values ('${qcId}', '${userA}', 'daily', 10, 5, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date - 40);

    insert into public.contact_reports (id, user_id, category, message, status, replied_at, created_at)
    values ('${ctId}', '${userA}', 'bug', 'dry', 'resolved', clock_timestamp() - interval '70 days', clock_timestamp() - interval '70 days');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_operational_data(16, 30, 16, 30, 30, 60, true, 5000) as r`)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.status, 'dry_run');
  assert.equal(res.dry_run, true);
  assert.equal(res.scanned, 10);
  assert.equal(res.deleted, 0);
  assert.equal(res.scanned_report_submission_receipts, 1);
  assert.equal(res.scanned_report_cancelled_requests, 1);
  assert.equal(res.scanned_admin_operation_requests, 1);
  assert.equal(res.scanned_business_policy_admin_requests, 1);
  assert.equal(res.scanned_notification_campaign_requests, 1);
  assert.equal(res.scanned_app_update_operations, 1);
  assert.equal(res.scanned_report_media_uploads, 1);
  assert.equal(res.scanned_cleanup_runs, 1);
  assert.equal(res.scanned_user_quest_claims, 1);
  assert.equal(res.scanned_contact_reports, 1);
  assert.equal(res.deleted_report_submission_receipts, 0);
  assert.equal(res.deleted_contact_reports, 0);

  const runLog = await one(`select * from public.cleanup_runs where id = '${res.run_id}'`);
  assert.equal(runLog.job_name, 'purge_expired_operational_data');
  assert.equal(runLog.status, 'dry_run');
  assert.equal(runLog.scanned, 10);
  assert.equal(runLog.deleted, 0);

  assert.equal((await one(`select count(*)::int as c from public.report_submission_receipts where id = '${recId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.report_cancelled_requests where client_submit_id = '${cancelSubId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.admin_operation_requests where id = '${adminOpId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.business_policy_admin_requests where id = '${polOpId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.notification_campaign_requests where request_id = '${notifReqId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.app_update_operations where operation_id = 'dry-op-1'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.report_media_uploads where id = '${mediaId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.cleanup_runs where id = '${crId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.user_quest_claims where id = '${qcId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.contact_reports where id = '${ctId}'`)).c, 1);
});

// ---------------------------------------------------------------------------
// 7. Batching oldest-first & parameter clamping
// ---------------------------------------------------------------------------
await test('Respects p_batch_size oldest-first and clamps retention/batch bounds', async () => {
  const cOldest = 'f3000000-0000-4000-8000-000000000001';
  const cMiddle = 'f3000000-0000-4000-8000-000000000002';
  const cNewest = 'f3000000-0000-4000-8000-000000000003';
  const cFresh12h = 'f3000000-0000-4000-8000-000000000004';

  await db.exec(`
    insert into public.report_cancelled_requests (user_id, client_submit_id, created_at) values
      ('${userA}', '${cOldest}', clock_timestamp() - interval '10 days'),
      ('${userA}', '${cMiddle}', clock_timestamp() - interval '5 days'),
      ('${userA}', '${cNewest}', clock_timestamp() - interval '2 days'),
      ('${userA}', '${cFresh12h}', clock_timestamp() - interval '12 hours');
  `);

  // Passing 0 for retention clamps to 1 day; batch_size = 2 deletes 2 oldest rows first.
  const b1 = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_operational_data(0, 0, 0, 0, 0, 0, false, 2) as r`)).r;
  });

  assert.equal(b1.receipt_retention_days, 1);
  assert.equal(b1.operation_retention_days, 1);
  assert.equal(b1.media_retention_days, 1);
  assert.equal(b1.cleanup_log_retention_days, 1);
  assert.equal(b1.quest_retention_days, 1);
  assert.equal(b1.contact_retention_days, 1);
  assert.equal(b1.batch_size, 2);
  assert.equal(b1.deleted_report_cancelled_requests, 2);

  const after1 = new Set((await rows(`select client_submit_id from public.report_cancelled_requests`)).map(r => r.client_submit_id));
  assert.ok(!after1.has(cOldest));
  assert.ok(!after1.has(cMiddle));
  assert.ok(after1.has(cNewest), 'Newest expired row must wait for second batch');
  assert.ok(after1.has(cFresh12h), '12h row must stay when clamped retention is 1d');

  const b2 = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_expired_operational_data(0, 0, 0, 0, 0, 0, false, 999999) as r`)).r;
  });
  assert.equal(b2.batch_size, 50000, 'batch_size must clamp at 50000');
  assert.equal(b2.deleted_report_cancelled_requests, 1);

  const after2 = new Set((await rows(`select client_submit_id from public.report_cancelled_requests`)).map(r => r.client_submit_id));
  assert.deepEqual([...after2], [cFresh12h]);
});

// ---------------------------------------------------------------------------
// 8. sync_campaign_lifecycle() 6h notification + 24h operational fallbacks
// ---------------------------------------------------------------------------
await test('sync_campaign_lifecycle() preserves 6h notification fallback and runs 24h operational fallback independently', async () => {
  const expNotif1 = 'f4000000-0000-4000-8000-000000000001';
  const expCancel1 = 'f4000000-0000-4000-8000-000000000002';
  const expCancel2 = 'f4000000-0000-4000-8000-000000000003';
  const expNotif2 = 'f4000000-0000-4000-8000-000000000004';

  // Old success runs (>6h for notif, >24h for operational) + recent dry_run (must NOT block fallback)
  await db.exec(`
    insert into public.cleanup_runs (job_name, status, started_at, finished_at) values
      ('purge_expired_notifications', 'success', clock_timestamp() - interval '7 hours', clock_timestamp() - interval '7 hours'),
      ('purge_expired_operational_data', 'success', clock_timestamp() - interval '25 hours', clock_timestamp() - interval '25 hours'),
      ('purge_expired_operational_data', 'dry_run', clock_timestamp() - interval '1 hour', clock_timestamp() - interval '1 hour');

    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${expNotif1}', '${userA}', 'Expired', 'Msg', 'general', clock_timestamp() - interval '9 days');

    insert into public.report_cancelled_requests (user_id, client_submit_id, created_at) values
      ('${userA}', '${expCancel1}', clock_timestamp() - interval '20 days');
  `);

  const syncRes1 = await asRole('authenticated', userA, async () => {
    return (await one(`select public.sync_campaign_lifecycle() as r`)).r;
  });

  assert.ok('completed' in syncRes1);
  assert.ok('paused' in syncRes1);
  assert.ok('slot_paused' in syncRes1);

  assert.equal((await one(`select count(*)::int as c from public.notifications where id = '${expNotif1}'`)).c, 0);
  assert.equal((await one(`select count(*)::int as c from public.report_cancelled_requests where client_submit_id = '${expCancel1}'`)).c, 0);

  // Within 6h/24h throttle window: neither purge should re-run
  await db.exec(`
    insert into public.notifications (id, user_id, title, message, type, created_at) values
      ('${expNotif2}', '${userA}', 'Expired 2', 'Msg', 'general', clock_timestamp() - interval '9 days');

    insert into public.report_cancelled_requests (user_id, client_submit_id, created_at) values
      ('${userA}', '${expCancel2}', clock_timestamp() - interval '20 days');
  `);

  await asRole('anon', null, async () => {
    return (await one(`select public.sync_campaign_lifecycle() as r`)).r;
  });

  assert.equal((await one(`select count(*)::int as c from public.notifications where id = '${expNotif2}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from public.report_cancelled_requests where client_submit_id = '${expCancel2}'`)).c, 1);

  // Backdate ONLY operational success run beyond 24h while keeping notification run within 6h:
  // operational fallback runs independently without re-running notification purge.
  await db.exec(`
    update public.cleanup_runs
    set started_at = clock_timestamp() - interval '26 hours',
        finished_at = clock_timestamp() - interval '26 hours'
    where job_name = 'purge_expired_operational_data'
      and status = 'success';
  `);

  await asRole('authenticated', userA, async () => {
    return (await one(`select public.sync_campaign_lifecycle() as r`)).r;
  });

  assert.equal((await one(`select count(*)::int as c from public.notifications where id = '${expNotif2}'`)).c, 1, 'Notification purge must remain throttled inside 6h');
  assert.equal((await one(`select count(*)::int as c from public.report_cancelled_requests where client_submit_id = '${expCancel2}'`)).c, 0, 'Operational purge must run when >24h since last success');
});

console.log('=== ALL OPERATIONAL CLEANUP TESTS PASSED ===');
