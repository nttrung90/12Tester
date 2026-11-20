import assert from 'node:assert/strict';
import {createDb, read} from '../tools/baseline-lib.mjs';

const owner = '11111111-1111-1111-1111-111111111111';
const user1 = '22222222-2222-2222-2222-222222222222';
const user2 = '33333333-3333-3333-3333-333333333333';
const camp1 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const camp2 = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

const req1 = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const req2 = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const req3 = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
const req4 = 'ffffffff-ffff-ffff-ffff-ffffffffffff';

console.log('--- Testing Migration 065: Remove dHash Hard-Block ---');

const db = await createDb();
try {
  // Apply all migrations sequentially
  await db.exec(`create schema if not exists extensions; create extension pgcrypto with schema extensions;`);
  await db.exec(read('supabase/migrations/062_consolidated_baseline.sql'));
  await db.exec(read('supabase/migrations/063_report_crypto_search_path.sql'));
  await db.exec(read('supabase/migrations/064_explicit_report_context.sql'));
  await db.exec(read('supabase/migrations/065_remove_dhash_hardblock.sql'));

  // Setup seed data
  await db.exec(`
    insert into auth.users(id, email) values
      ('${owner}', 'owner@test.com'),
      ('${user1}', 'user1@test.com'),
      ('${user2}', 'user2@test.com');

    update public.profiles set credits = 10000 where id = '${owner}';
    update public.profiles set role = 'member' where id in ('${user1}', '${user2}');

    insert into public.user_slots(user_id, slot_number, expires_at)
    values ('${owner}', 2, now() + interval '30 days');

    insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number) values
      ('${camp1}', '${owner}', 'com.app.one', 'App One', 'open', 1),
      ('${camp2}', '${owner}', 'com.app.two', 'App Two', 'open', 2);
  `);

  const sharedDHash = '3a9c72f10b5d84e2';

  // 1. User 1 submits a report on Camp 1 with sharedDHash -> should succeed
  await db.exec(`select set_config('request.jwt.claim.sub', '${user1}', false); set role authenticated;`);
  const res1 = (await db.query(`
    select public.submit_tester_report_v2(
      p_submission_context => 'app_test',
      p_campaign_id => '${camp1}',
      p_content => 'Test 1 by User 1',
      p_client_submit_id => '${req1}',
      p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp1}%2F${user1}%2F1.jpg?alt=media&token=fixture1',
      p_image_hash => '${sharedDHash}'
    ) as result
  `)).rows[0].result;

  assert.equal(res1.success, true);
  assert.equal(res1.image_hash, sharedDHash);
  console.log('PASS: User 1 submitted report with dHash ' + sharedDHash);

  // 2. User 2 submits a report on Camp 1 with the exact SAME sharedDHash -> MUST SUCCEED (previously blocked with SAME_SCREENSHOT_REJECTED)
  await db.exec(`select set_config('request.jwt.claim.sub', '${user2}', false); set role authenticated;`);
  const res2 = (await db.query(`
    select public.submit_tester_report_v2(
      p_submission_context => 'app_test',
      p_campaign_id => '${camp1}',
      p_content => 'Test 1 by User 2',
      p_client_submit_id => '${req2}',
      p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp1}%2F${user2}%2F2.jpg?alt=media&token=fixture2',
      p_image_hash => '${sharedDHash}'
    ) as result
  `)).rows[0].result;

  assert.equal(res2.success, true);
  assert.equal(res2.image_hash, sharedDHash);
  assert.notEqual(res1.report_id, res2.report_id);
  console.log('PASS: User 2 successfully submitted report with identical dHash without SAME_SCREENSHOT_REJECTED');

  // 3. User 1 submits on Camp 2 with the SAME sharedDHash -> MUST SUCCEED
  await db.exec(`select set_config('request.jwt.claim.sub', '${user1}', false); set role authenticated;`);
  const res3 = (await db.query(`
    select public.submit_tester_report_v2(
      p_submission_context => 'app_test',
      p_campaign_id => '${camp2}',
      p_content => 'Test on Camp 2 by User 1',
      p_client_submit_id => '${req3}',
      p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp2}%2F${user1}%2F3.jpg?alt=media&token=fixture3',
      p_image_hash => '${sharedDHash}'
    ) as result
  `)).rows[0].result;

  assert.equal(res3.success, true);
  assert.equal(res3.image_hash, sharedDHash);
  console.log('PASS: User 1 submitted report on different campaign with same dHash');

  // 4. Test Rejected Report Resubmission behavior:
  // Reset role to reject User 1's report on Camp 1
  await db.exec(`
    reset role;
    update public.tester_reports set
      status = 'rejected',
      rejection_reason = 'Ảnh không rõ ràng',
      provisional_clawed = 10
    where id = '${res1.report_id}';
  `);

  // Switch back to User 1
  await db.exec(`select set_config('request.jwt.claim.sub', '${user1}', false); set role authenticated;`);

  // 4a. Resubmitting with exact SAME screenshot URL must be REJECTED with REPORT_RESUBMIT_NEW_PHOTO_REQUIRED
  await assert.rejects(
    db.query(`
      select public.submit_tester_report_v2(
        p_submission_context => 'app_test',
        p_campaign_id => '${camp1}',
        p_content => 'Resubmit same photo',
        p_client_submit_id => '${req4}',
        p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp1}%2F${user1}%2F1.jpg?alt=media&token=fixture1',
        p_image_hash => '${sharedDHash}'
      ) as result
    `),
    /REPORT_RESUBMIT_NEW_PHOTO_REQUIRED/
  );
  console.log('PASS: Resubmitting rejected report with exact same photo URL was correctly rejected with REPORT_RESUBMIT_NEW_PHOTO_REQUIRED');

  // 4b. Resubmitting with a NEW screenshot URL (even if dHash happens to match) MUST SUCCEED
  const res4 = (await db.query(`
    select public.submit_tester_report_v2(
      p_submission_context => 'app_test',
      p_campaign_id => '${camp1}',
      p_content => 'Resubmit with new photo',
      p_client_submit_id => '${req4}',
      p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp1}%2F${user1}%2F1_new.jpg?alt=media&token=fixture1new',
      p_image_hash => '${sharedDHash}'
    ) as result
  `)).rows[0].result;

  assert.equal(res4.success, true);
  assert.equal(res4.status, 'pending');
  assert.equal(res4.provisional_amount, 10);
  console.log('PASS: Resubmitting rejected report with new photo URL and same dHash succeeded');

  // 5. Replay idempotency: calling with same client_submit_id returns the exact same receipt
  const res4Replay = (await db.query(`
    select public.submit_tester_report_v2(
      p_submission_context => 'app_test',
      p_campaign_id => '${camp1}',
      p_content => 'Resubmit with new photo',
      p_client_submit_id => '${req4}',
      p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp1}%2F${user1}%2F1_new.jpg?alt=media&token=fixture1new',
      p_image_hash => '${sharedDHash}'
    ) as result
  `)).rows[0].result;

  assert.deepEqual(res4Replay, res4);
  console.log('PASS: Replay idempotency returns identical result');

  // 6. Rerun migration 065 idempotent check
  await db.exec(`reset role;`);
  await db.exec(read('supabase/migrations/065_remove_dhash_hardblock.sql'));
  console.log('PASS: Migration 065 is safe to re-run');

  console.log('ALL BACKEND MIGRATION 065 TESTS PASSED SUCCESSFULLY!');
} catch (e) {
  console.error('FAILED:', e.message, e.code);
  process.exitCode = 1;
} finally {
  await db.close();
}
