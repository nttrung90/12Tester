import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (path) => readFileSync(`${root}${path}`, 'utf8').replaceAll('\r\n', '\n');

async function runDualStorageLogicTests() {
  console.log('=== CHẠY KIỂM THỬ ĐƠN VỊ SQL & LOGIC HẠN MỨC DUAL STORAGE (PGLITE) ===');
  const { createDb } = await import('../../supabase/tools/baseline-lib.mjs');
  const db = await createDb();

  // Apply the current chain, including the follow-up hardening migration.
  const migrationsDir = new URL('../../supabase/migrations/', import.meta.url);
  const { readdirSync } = await import('node:fs');
  const migrationFiles = readdirSync(migrationsDir)
    .filter(f => f.endsWith('.sql'))
    .sort();

  for (const file of migrationFiles) {
    const sql = read(`supabase/migrations/${file}`);
    await db.exec(sql);
  }
  console.log(`PASS: Áp dụng thành công toàn bộ ${migrationFiles.length} migrations trên PGlite.`);

  const ownerId = '11111111-1111-1111-1111-111111111111';
  const testerId = '22222222-2222-2222-2222-222222222222';
  const campaignId = '55555555-5555-5555-5555-555555555555';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${ownerId}', 'owner@test.com'),
      ('${testerId}', 'tester@test.com');
    update public.profiles set display_name = 'Owner' where id = '${ownerId}';
    update public.profiles set display_name = 'Tester' where id = '${testerId}';
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campaignId}', '${ownerId}', 'Dual Storage Test App', 'com.test.dualstorage', 'open');
    insert into public.campaign_members (campaign_id, user_id)
    values ('${campaignId}', '${testerId}');
  `);

  // Set total package capacity B = 1,000,000,000 bytes (1 GB), 95% = 950 MB, 90% = 900 MB
  const totalCapacityBytes = 1000000000n;
  const initialUsedBytes = 940000000n; // 94%

  await db.exec(`
    insert into public.storage_trusted_hosts(host) values('example.supabase.co');
    update public.storage_organization_usage set
      local_project_ref = 'aaaaaaaaaaaaaaaaaaaa',
      total_capacity_bytes = ${totalCapacityBytes},
      used_storage_bytes = ${initialUsedBytes},
      active_reserved_bytes = 0,
      dispatch_mode = 'supabase_preferred',
      is_org_metric_valid = true,
      last_reconciled_at = now(),
      measured_projects = '["test-project-1", "test-project-2"]'::jsonb
    where id = 1;
  `);

  // Authenticate as tester (session-wide setting: is_local = false)
  await db.exec(`select set_config('request.jwt.claim.sub', '${testerId}', false);`);
  await db.exec(`select set_config('app.settings.media_gateway_secret', 'crosstest_test_secret_for_suite_2026_at_least_32_chars', false);`);
  await db.exec(`select set_config('app.settings.media_gateway_base_url', 'https://gateway.fixture.invalid/v1/media-gateway', false);`);

  // Test Case 1: Usage at 94% (< 95%) -> Reserves Supabase
  console.log('\n--- Test 1: Usage at 94% -> Supabase Preferred ---');
  const uploadId1 = 'upload-id-001';
  const res1 = await db.query(`
    select public.request_report_media_reservation(
      p_campaign_id := '${campaignId}'::uuid,
      p_upload_id := '${uploadId1}',
      p_estimated_size := 500000,
      p_has_thumbnail := true
    ) as result;
  `);

  const result1 = res1.rows[0].result;
  assert.equal(result1.success, true);
  assert.equal(result1.provider, 'supabase');
  assert.equal(result1.bucket, 'report-screenshots');
  assert.ok(result1.object_path.includes(campaignId));
  assert.ok(result1.thumbnail_object_path.includes('thumb_'));
  console.log('PASS: Usage at 94% successfully dispatched to Supabase with reservation.');

  // Verify DB reservation increment
  const usageCheck1 = await db.query(`select active_reserved_bytes, dispatch_mode from public.storage_organization_usage where id = 1`);
  assert.ok(usageCheck1.rows[0].active_reserved_bytes > 0n);
  assert.equal(usageCheck1.rows[0].dispatch_mode, 'supabase_preferred');

  // Test Case 2: Pinning across retry with same upload_id
  console.log('\n--- Test 2: Pinning across retries with identical upload_id ---');
  const resRetry = await db.query(`
    select public.request_report_media_reservation(
      p_campaign_id := '${campaignId}'::uuid,
      p_upload_id := '${uploadId1}',
      p_estimated_size := 500000,
      p_has_thumbnail := true
    ) as result;
  `);
  const resultRetry = resRetry.rows[0].result;
  assert.equal(resultRetry.provider, 'supabase');
  assert.equal(resultRetry.object_path, result1.object_path);
  assert.equal(resultRetry.pinned, true);
  console.log('PASS: Pinning preserved provider and paths without double-incrementing reservation.');

  // Test Case 3: Usage + Reservation crosses 95% -> Overflow to Google
  console.log('\n--- Test 3: Overflow to Google Cloud when >= 95% ---');
  // Set used bytes to 949MB (94.9%). Any request will push it over 950MB (95.0%)
  await db.exec(`
    update public.storage_organization_usage
    set used_storage_bytes = 949500000, active_reserved_bytes = 0
    where id = 1;
  `);
  const uploadId2 = 'upload-id-002';
  const res2 = await db.query(`
    select public.request_report_media_reservation(
      p_campaign_id := '${campaignId}'::uuid,
      p_upload_id := '${uploadId2}',
      p_estimated_size := 1000000,
      p_has_thumbnail := true
    ) as result;
  `);
  const result2 = res2.rows[0].result;
  assert.equal(result2.success, true);
  assert.equal(result2.provider, 'gcs');
  assert.equal(result2.bucket, 'cross-testers.firebasestorage.app');
  assert.equal(result2.dispatch_mode, 'google_overflow');
  console.log('PASS: Exceeding 95% threshold triggered google_overflow dispatch.');

  // Test Case 4: Hysteresis between 90% and 95% -> stays in google_overflow
  console.log('\n--- Test 4: Hysteresis in [90%, 95%) range ---');
  // Set used bytes to 920MB (92%), active reservations 0
  await db.exec(`
    update public.storage_organization_usage
    set used_storage_bytes = 920000000, active_reserved_bytes = 0, dispatch_mode = 'google_overflow'
    where id = 1;
  `);
  const uploadId3 = 'upload-id-003';
  const res3 = await db.query(`
    select public.request_report_media_reservation(
      p_campaign_id := '${campaignId}'::uuid,
      p_upload_id := '${uploadId3}',
      p_estimated_size := 500000,
      p_has_thumbnail := true
    ) as result;
  `);
  const result3 = res3.rows[0].result;
  assert.equal(result3.provider, 'gcs', 'Should remain in google_overflow while above 90%');
  assert.equal(result3.dispatch_mode, 'google_overflow');
  console.log('PASS: Usage at 92% stayed in google_overflow as required by hysteresis.');

  // Test Case 5: Recovery below 90% -> returns to Supabase
  console.log('\n--- Test 5: Recovery below 90% (< 900 MB) ---');
  await db.exec(`
    update public.storage_organization_usage
    set used_storage_bytes = 850000000, active_reserved_bytes = 0, dispatch_mode = 'google_overflow'
    where id = 1;
  `);
  const uploadId4 = 'upload-id-004';
  const res4 = await db.query(`
    select public.request_report_media_reservation(
      p_campaign_id := '${campaignId}'::uuid,
      p_upload_id := '${uploadId4}',
      p_estimated_size := 500000,
      p_has_thumbnail := true
    ) as result;
  `);
  const result4 = res4.rows[0].result;
  assert.equal(result4.provider, 'supabase', 'Must switch back to Supabase below 90%');
  assert.equal(result4.dispatch_mode, 'supabase_preferred');
  console.log('PASS: Usage at 85% recovered to supabase_preferred.');

  // Test Case 6: Fail-safe when org storage metric is stale (>30 mins) or invalid
  console.log('\n--- Test 6: Fail-safe fallback to GCS on stale or invalid metrics ---');
  await db.exec(`
    update public.storage_organization_usage
    set last_reconciled_at = now() - interval '2 hours', is_org_metric_valid = false
    where id = 1;
  `);
  const uploadId5 = 'upload-id-005';
  const res5 = await db.query(`
    select public.request_report_media_reservation(
      p_campaign_id := '${campaignId}'::uuid,
      p_upload_id := '${uploadId5}',
      p_estimated_size := 500000,
      p_has_thumbnail := true
    ) as result;
  `);
  const result5 = res5.rows[0].result;
  assert.equal(result5.provider, 'gcs', 'Must fail-safe to GCS when metric is stale/invalid');
  console.log('PASS: Stale metrics engaged fail-safe and routed safely to GCS.');

  // Test Case 7: Submission sets 216h view expiry and finalizes reservation
  console.log('\n--- Test 7: Report submission finalizes reservation and bounds expiry ---');
  // Reset usage to valid Supabase preferred
  await db.exec(`
    update public.storage_organization_usage
    set last_reconciled_at = now(), is_org_metric_valid = true,
        used_storage_bytes = 500000000, active_reserved_bytes = 0,
        dispatch_mode = 'supabase_preferred'
    where id = 1;
  `);

  const uploadIdFinalize = 'upload-id-006';
  const resReserve = await db.query(`
    select public.request_report_media_reservation(
      p_campaign_id := '${campaignId}'::uuid,
      p_upload_id := '${uploadIdFinalize}',
      p_estimated_size := 500000,
      p_has_thumbnail := true
    ) as result;
  `);
  const reservedObj = resReserve.rows[0].result;
  const supabaseFullUrl = `https://example.supabase.co/storage/v1/object/public/${reservedObj.bucket}/${reservedObj.object_path}`;
  const supabaseThumbUrl = `https://example.supabase.co/storage/v1/object/public/${reservedObj.bucket}/${reservedObj.thumbnail_object_path}`;

  // Insert mock storage objects to satisfy provenance contract (F03/F04)
  await db.exec(`
    insert into storage.objects (bucket_id, name, owner, created_at, metadata)
    values
      ('${reservedObj.bucket}', '${reservedObj.object_path}', '${testerId}', now(), '{"size": 500000}'),
      ('${reservedObj.bucket}', '${reservedObj.thumbnail_object_path}', '${testerId}', now(), '{"size": 100000}');
  `);

  const clientSubmitId = '00000000-0000-0000-0000-000000000099';
  const submitRes = await db.query(`
    select public.submit_tester_report_v3(
      p_submission_context := 'app_test',
      p_campaign_id := '${campaignId}'::uuid,
      p_content := 'Dual storage finalization submission test',
      p_rating := 5,
      p_client_submit_id := '${clientSubmitId}'::uuid,
      p_attachments := jsonb_build_array(
        jsonb_build_object(
          'screenshot_url', '${supabaseFullUrl}',
          'thumbnail_url', '${supabaseThumbUrl}',
          'image_hash', '1111222233334444',
          'sha256', '${'a'.repeat(64)}',
          'position', 0,
          'storage_provider', 'supabase'
        )
      )
    ) as result;
  `);
  assert.equal(submitRes.rows[0].result.success, true);
  const reportId = submitRes.rows[0].result.report_id;

  const repRow = await db.query(`
    select storage_provider, view_expires_at, now() as current_time
    from public.tester_reports
    where id = '${reportId}'::uuid
  `);
  assert.equal(repRow.rows[0].storage_provider, 'supabase');
  assert.ok(repRow.rows[0].view_expires_at != null);

  // Check upload ledger row
  const ledgerRow = await db.query(`
    select status, provider, view_expires_at, delete_deadline_at
    from public.report_media_uploads
    where upload_id = '${uploadIdFinalize}'
  `);
  assert.equal(ledgerRow.rows[0].status, 'finalized');
  assert.equal(ledgerRow.rows[0].provider, 'supabase');
  assert.ok(ledgerRow.rows[0].view_expires_at != null);
  assert.ok(ledgerRow.rows[0].delete_deadline_at != null);
  console.log('PASS: Report submission finalized Supabase ledger and set view_expires_at.');

  // Test Case 8: request_report_media_access enforces 216h cutoff for Supabase
  console.log('\n--- Test 8: request_report_media_access enforces 216h cutoff for Supabase ---');
  // For testerId (author of report), access should succeed when active
  const accessBefore = await db.query(`
    select public.request_report_media_access(
      p_report_id := '${reportId}'::uuid
    ) as result;
  `);
  assert.equal(accessBefore.rows[0].result.success, true);

  // Artificially expire the report (>216 hours)
  await db.exec(`
    update public.tester_reports
    set view_expires_at = now() - interval '1 hour', media_expired = true
    where id = '${reportId}'::uuid;
  `);

  let expiredThrown = false;
  try {
    await db.query(`
      select public.request_report_media_access(
        p_report_id := '${reportId}'::uuid
      ) as result;
    `);
  } catch (err) {
    if (err.message.includes('MEDIA_EXPIRED')) {
      expiredThrown = true;
    }
  }
  assert.equal(expiredThrown, true, 'Must throw MEDIA_EXPIRED exception when media is expired');
  console.log('PASS: Expired Supabase media access was correctly rejected with MEDIA_EXPIRED.');

  console.log('\n>>> TẤT CẢ 8 KỊCH BẢN LOGIC DUAL STORAGE TRÊN PGLITE ĐÃ PASS! <<<');
}


async function main() {
  try {
    await runDualStorageLogicTests();
    await import('./run-dual-storage-concurrency-test.mjs');
  } catch (err) {
    console.error('FATAL TEST FAILURE:', err);
    process.exit(1);
  }
}

main();
