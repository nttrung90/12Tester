import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

// Identities
const owner = 'c3000000-0000-0000-0000-000000000001';
const owner2 = 'c3000000-0000-0000-0000-000000000002';
const tester1 = 'a1000000-0000-0000-0000-000000000001';
const tester2 = 'a1000000-0000-0000-0000-000000000002';
const admin = 'd4000000-0000-0000-0000-000000000001';

// Campaign UUIDs
const campClean = 'b1000000-0000-0000-0000-000000000011';
const campImm = 'b1000000-0000-0000-0000-000000000012';
const campPending = 'b1000000-0000-0000-0000-000000000013';
const campFull = 'b1000000-0000-0000-0000-000000000014';
const campOv = 'b1000000-0000-0000-0000-000000000015';

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
  .sort();
for (const name of migrations) {
  await db.exec(read('supabase/migrations/' + name));
}
console.log(`MIGRATIONS LOADED SUCCESSFULLY (${migrations.length} migrations).`);

// Base fixtures seeding
await db.exec(`
  insert into auth.users (id, email) values
    ('${owner}', 'owner@test.local'),
    ('${owner2}', 'owner2@test.local'),
    ('${tester1}', 'tester1@test.local'),
    ('${tester2}', 'tester2@test.local'),
    ('${admin}', 'admin@test.local')
  on conflict (id) do nothing;

  update public.profiles set display_name = 'Owner 1', credits = 1000, role = 'member', is_locked = false where id = '${owner}';
  update public.profiles set display_name = 'Owner 2', credits = 500, role = 'member', is_locked = false where id = '${owner2}';
  update public.profiles set display_name = 'Tester 1', credits = 100, role = 'member', is_locked = false where id = '${tester1}';
  update public.profiles set display_name = 'Tester 2', credits = 100, role = 'member', is_locked = false where id = '${tester2}';
  update public.profiles set display_name = 'Admin User', credits = 5000, role = 'admin', is_locked = false where id = '${admin}';

  insert into public.user_slots (user_id, slot_number, expires_at) values
    ('${owner}', 2, now() + interval '30 days'),
    ('${owner}', 3, now() + interval '30 days')
  on conflict (user_id, slot_number) do update set expires_at = now() + interval '30 days';
`);

console.log('=== BASE FIXTURES SEEDED ===\n');

// ============================================================================
// TEST 1: Authorization, Ownership & Denial Checks
// ============================================================================
console.log('--- TEST 1: Authorization, Ownership & Denial Checks ---');

await db.exec(`
  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${campClean}', '${owner}', 'Clean App', 'com.test.clean', 'open', 1, now())
  on conflict (id) do nothing;
`);

// 1a. Anonymous caller denied (ACL denies anon role directly)
await inSession(null, async () => {
  await assert.rejects(
    () => db.query(`select public.delete_owned_campaign('${campClean}')`),
    /permission denied/i
  );
});

// 1b. Locked owner denied
await db.query(`update public.profiles set is_locked = true where id = '${owner}'`);
await inSession(owner, async () => {
  await assert.rejects(
    () => db.query(`select public.delete_owned_campaign('${campClean}')`),
    /ACCOUNT_LOCKED/
  );
});
await db.query(`update public.profiles set is_locked = false where id = '${owner}'`);

// 1c. Owner cannot delete another owner's campaign
await inSession(owner2, async () => {
  await assert.rejects(
    () => db.query(`select public.delete_owned_campaign('${campClean}')`),
    /PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng/
  );
});

// 1d. Admin CANNOT delete another owner's campaign via delete_owned_campaign (owner RPC)
await inSession(admin, async () => {
  await assert.rejects(
    () => db.query(`select public.delete_owned_campaign('${campClean}')`),
    /PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng/
  );
});

// 1e. Private helper _request_campaign_removal cannot be executed by authenticated caller directly
await inSession(owner, async () => {
  await assert.rejects(
    () => db.query(`select public._request_campaign_removal('${campClean}', 'test', false)`),
    /permission denied/i
  );
});

console.log('PASS: Authorization, ownership and denial guards verified.');

// ============================================================================
// TEST 2: Ordinary Campaign Removal (No Financial Obligations / No Bonus)
// ============================================================================
console.log('--- TEST 2: Ordinary Campaign Removal (No Financial Obligations) ---');

const delCleanRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.delete_owned_campaign('${campClean}') as res`);
  return rows[0].res;
});

assert.equal(delCleanRes.success, true);
assert.equal(delCleanRes.status, 'removed');
assert.equal(delCleanRes.campaign_id, campClean);
assert.equal(delCleanRes.app_name, 'Clean App');
assert.equal(delCleanRes.owner_id, owner);
assert.equal(delCleanRes.reason, 'Gỡ bỏ bởi Chủ sở hữu');
assert.equal(delCleanRes.message, 'Ứng dụng đã được gỡ bỏ hoàn toàn');

// Check physical row deleted from campaigns
const { rows: campCleanCheck } = await db.query(`select * from public.campaigns where id = '${campClean}'`);
assert.equal(campCleanCheck.length, 0, 'Campaign row must be physically deleted');

console.log('PASS: Ordinary campaign removal without financial obligations verified.');

// ============================================================================
// TEST 3: No-Pending Bonus Round Immediate Refund & Deletion
// ============================================================================
console.log('--- TEST 3: No-Pending Immediate Refund & Deletion ---');

const reqImm = 'f1000000-0000-0000-0000-000000000012';

await db.exec(`
  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${campImm}', '${owner}', 'Immediate Refund App', 'com.test.imm', 'open', 1, now())
  on conflict (id) do nothing;
`);

// Create bonus round: 25 credits x 2 recipients = 50 credits reserved
await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${campImm}', 25, 2, '${reqImm}')`);
});

const { rows: [ownerBalBeforeImm] } = await db.query(`select credits from public.profiles where id = '${owner}'`);

// Delete owned campaign with no pending reports
const delImmRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.delete_owned_campaign('${campImm}') as res`);
  return rows[0].res;
});

assert.equal(delImmRes.success, true);
assert.equal(delImmRes.status, 'removed');
assert.equal(delImmRes.campaign_id, campImm);
assert.equal(delImmRes.app_name, 'Immediate Refund App');
assert.equal(delImmRes.owner_id, owner);
assert.equal(delImmRes.message, 'Ứng dụng đã được gỡ bỏ hoàn toàn');

// Campaign row must be physically deleted
const { rows: campImmCheck } = await db.query(`select * from public.campaigns where id = '${campImm}'`);
assert.equal(campImmCheck.length, 0, 'Campaign row must be physically deleted');

// Owner balance must be refunded exactly 50 credits
const { rows: [ownerBalAfterImm] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
assert.equal(ownerBalAfterImm.credits, ownerBalBeforeImm.credits + 50);

// Round record preserved for audit, marked closed with refunded_credits = 50
const { rows: [roundImm] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqImm}'`);
assert.equal(roundImm.status, 'closed');
assert.equal(roundImm.remaining_credits, 0);
assert.equal(roundImm.refunded_credits, 50);

console.log('PASS: No-pending immediate refund and deletion verified.');

// ============================================================================
// TEST 4: Pending Bonus Manual Approval -> Worker Pays/Refunds/Deletes Exactly Once
// ============================================================================
console.log('--- TEST 4: Pending Bonus Manual Approval -> Worker Pays/Refunds/Deletes Exactly Once ---');

const reqPending = 'f1000000-0000-0000-0000-000000000013';

await db.exec(`
  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${campPending}', '${owner}', 'Pending Removal App', 'com.test.pending', 'open', 1, now())
  on conflict (id) do nothing;
`);

// Create bonus round: 20 credits x 2 recipients = 40 credits reserved
await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${campPending}', 20, 2, '${reqPending}')`);
});

// Tester 1 submits report
const repPending = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${campPending}', 'Report 1', 5,
      'd0000000-0000-0000-0000-000000000013',
      '${makePhotoUrl(campPending, tester1, 'p1')}',
      '0123456789abcdef',
      '${makeThumbUrl(campPending, tester1, 'p1')}',
      null
    ) as res
  `);
  return rows[0].res;
});
assert.ok(repPending.tester_bonus_round_id);

// Owner requests campaign removal -> transitions to pending_removal because report is pending
const delPendingRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.delete_owned_campaign('${campPending}') as res`);
  return rows[0].res;
});
assert.equal(delPendingRes.success, true);
assert.equal(delPendingRes.status, 'pending_removal');
assert.equal(delPendingRes.campaign_id, campPending);
assert.equal(delPendingRes.reason, 'Gỡ bỏ bởi Chủ sở hữu');
assert.equal(delPendingRes.message, 'Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất gỡ bỏ');

// Campaign row must still exist in 'closed' status with 'owner_deletion_pending'
const { rows: [campPendingRow] } = await db.query(`select * from public.campaigns where id = '${campPending}'`);
assert.equal(campPendingRow.status, 'closed');
assert.equal(campPendingRow.paused_reason, 'owner_deletion_pending');

// Direct physical DELETE must still be BLOCKED by guard trigger while unsettled funds exist
await inSession(owner, async () => {
  await assert.rejects(
    () => db.query(`delete from public.campaigns where id = '${campPending}'`),
    /CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_BONUS_ROUND/
  );
});

// Owner balance before approval
const { rows: [ownerBalBeforeApprove] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
const { rows: [testerBalBeforeApprove] } = await db.query(`select credits from public.profiles where id = '${tester1}'`);

// Owner approves the pending report -> tester receives award from escrow
await inSession(owner, async () => {
  await db.query(`select public.approve_tester_report('${repPending.report_id}', 0)`);
});

// Tester balance increased by 2 (base) + 20 (bonus) = 22
const { rows: [testerBalAfterApprove] } = await db.query(`select credits from public.profiles where id = '${tester1}'`);
assert.equal(testerBalAfterApprove.credits, testerBalBeforeApprove.credits + 22);

const { rows: [ownerBalBeforeWorker] } = await db.query(`select credits from public.profiles where id = '${owner}'`);

// Run batch closing processor: settles remaining unused 20 escrow credits and durably deletes campaign row
const { rows: [{ res: closedCount }] } = await db.query(`select public._process_closing_tester_bonus_rounds() as res`);
assert.equal(closedCount, 1, 'Batch processor must settle and close the round');

// Campaign row must be physically deleted now!
const { rows: campPendingAfterCheck } = await db.query(`select * from public.campaigns where id = '${campPending}'`);
assert.equal(campPendingAfterCheck.length, 0, 'Campaign must be physically removed after batch processor');

// Owner balance must receive exactly remaining 20 credits refund
const { rows: [ownerBalAfterWorker] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
assert.equal(ownerBalAfterWorker.credits, ownerBalBeforeWorker.credits + 20);

// Bonus round record preserved: 20 spent, 20 refunded, 1 paid
const { rows: [roundPendingCheck] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqPending}'`);
assert.equal(roundPendingCheck.status, 'closed');
assert.equal(roundPendingCheck.spent_credits, 20);
assert.equal(roundPendingCheck.refunded_credits, 20);
assert.equal(roundPendingCheck.paid_count, 1);

// Running worker a second time produces 0 (idempotent, exact once)
const { rows: [{ res: closedSecond }] } = await db.query(`select public._process_closing_tester_bonus_rounds() as res`);
assert.equal(closedSecond, 0);

console.log('PASS: Pending bonus manual approval and worker exact settlement verified.');

// ============================================================================
// TEST 5: Fully Consumed Last Slot Case
// ============================================================================
console.log('--- TEST 5: Fully Consumed Last Slot Case ---');

const reqFull = 'f1000000-0000-0000-0000-000000000014';

await db.exec(`
  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${campFull}', '${owner}', 'Full Slot App', 'com.test.full', 'open', 1, now())
  on conflict (id) do nothing;
`);

// 1 slot only: 30 credits x 1 recipient
await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${campFull}', 30, 1, '${reqFull}')`);
});

// Tester 1 submits report
const repFull = await inSession(tester1, async () => {
  const { rows } = await db.query(`
    select public._submit_tester_report_core(
      'app_test', '${campFull}', 'Report Full', 5,
      'd0000000-0000-0000-0000-000000000014',
      '${makePhotoUrl(campFull, tester1, 'f1')}',
      '0123456789abcdef',
      '${makeThumbUrl(campFull, tester1, 'f1')}',
      null
    ) as res
  `);
  return rows[0].res;
});

// Owner requests removal -> transitions to pending_removal
const delFullRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.delete_owned_campaign('${campFull}') as res`);
  return rows[0].res;
});
assert.equal(delFullRes.status, 'pending_removal');

// Owner approves report -> this consumes the last slot, round status becomes 'closed' immediately!
await inSession(owner, async () => {
  await db.query(`select public.approve_tester_report('${repFull.report_id}', 0)`);
});

const { rows: [roundFullClosed] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqFull}'`);
assert.equal(roundFullClosed.status, 'closed');
assert.equal(roundFullClosed.remaining_credits, 0);

// Worker runs -> even though round status was already 'closed', campaign deletion MUST complete!
await db.query(`select public._process_closing_tester_bonus_rounds()`);

const { rows: campFullCheck } = await db.query(`select * from public.campaigns where id = '${campFull}'`);
assert.equal(campFullCheck.length, 0, 'Campaign must be durably deleted when round was fully consumed');

console.log('PASS: Fully consumed last slot deferred deletion verified.');

// ============================================================================
// TEST 6: Replay Old Create & Reconcile After Physical Removal
// ============================================================================
console.log('--- TEST 6: Replay Old Create & Reconcile After Physical Removal ---');

// Reconcile physically deleted campPending
const reconRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('${campPending}', '${reqPending}') as res`);
  return rows[0].res;
});
assert.equal(reconRes.found, true);
assert.equal(reconRes.replayed, true);
assert.equal(reconRes.campaign_id, campPending);
assert.equal(reconRes.status, 'closed');
assert.equal(reconRes.spent_credits, 20);
assert.equal(reconRes.refunded_credits, 20);

// Replay create RPC after physical removal
const { rows: [ownerBalBeforeReplay] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
const replayRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.create_campaign_tester_bonus('${campPending}', 20, 2, '${reqPending}') as res`);
  return rows[0].res;
});
assert.equal(replayRes.success, true);
assert.equal(replayRes.replayed, true);
assert.equal(replayRes.campaign_id, campPending);
assert.equal(replayRes.status, 'closed');
const { rows: [ownerBalAfterReplay] } = await db.query(`select credits from public.profiles where id = '${owner}'`);
assert.equal(ownerBalAfterReplay.credits, ownerBalBeforeReplay.credits, 'Replay must not change balance');

// Wrong campaign ID reconcile must return found=false
const reconWrong = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('b9999999-9999-9999-9999-999999999999', '${reqPending}') as res`);
  return rows[0].res;
});
assert.equal(reconWrong.found, false);

// Non-owner reconcile must return found=false
const reconNonOwner = await inSession(tester1, async () => {
  const { rows } = await db.query(`select public.reconcile_campaign_tester_bonus('${campPending}', '${reqPending}') as res`);
  return rows[0].res;
});
assert.equal(reconNonOwner.found, false);

console.log('PASS: Replay and reconcile after physical removal verified.');

// ============================================================================
// TEST 7: Overflow Retry & Recovery
// ============================================================================
console.log('--- TEST 7: Overflow Retry & Recovery ---');

const reqOv = 'f1000000-0000-0000-0000-000000000015';

await db.exec(`
  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${campOv}', '${owner}', 'Overflow App', 'com.test.ov', 'open', 1, now())
  on conflict (id) do nothing;
`);

// 50 credits reserved
await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${campOv}', 50, 1, '${reqOv}')`);
});

// Artificially set owner balance near INT_MAX so refund would overflow
await db.query(`update public.profiles set credits = 2147483640 where id = '${owner}'`);

// Owner attempts to delete campaign -> wallet overflow keeps campaign in pending_removal
const delOvRes = await inSession(owner, async () => {
  const { rows } = await db.query(`select public.delete_owned_campaign('${campOv}') as res`);
  return rows[0].res;
});
assert.equal(delOvRes.success, true);
assert.equal(delOvRes.status, 'pending_removal');

// Campaign must still exist and round must be 'closing' with 50 credits remaining
const { rows: [campOvRow] } = await db.query(`select * from public.campaigns where id = '${campOv}'`);
assert.equal(campOvRow.status, 'closed');
assert.equal(campOvRow.paused_reason, 'owner_deletion_pending');

const { rows: [roundOvRow] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqOv}'`);
assert.equal(roundOvRow.status, 'closing');
assert.equal(roundOvRow.remaining_credits, 50);

// Reset owner balance to valid level and run worker -> settlements and deletions complete!
await db.query(`update public.profiles set credits = 500 where id = '${owner}'`);
await db.query(`select public._process_closing_tester_bonus_rounds()`);

const { rows: campOvAfterCheck } = await db.query(`select * from public.campaigns where id = '${campOv}'`);
assert.equal(campOvAfterCheck.length, 0, 'Campaign must be deleted after overflow recovery');

const { rows: [roundOvRecovered] } = await db.query(`select * from public.campaign_tester_bonus_rounds where client_request_id = '${reqOv}'`);
assert.equal(roundOvRecovered.status, 'closed');
assert.equal(roundOvRecovered.refunded_credits, 50);

console.log('PASS: Overflow retry and recovery verified.');

// ============================================================================
// TEST 8: Admin Delete Path Preserved & Distinct
// ============================================================================
console.log('--- TEST 8: Admin Delete Path Preserved & Distinct ---');

const campAdmin = 'b1000000-0000-0000-0000-000000000016';
const reqAdmin = 'f1000000-0000-0000-0000-000000000016';

await db.exec(`
  insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
  values ('${campAdmin}', '${owner}', 'Admin Del App', 'com.test.admdel', 'open', 1, now())
  on conflict (id) do nothing;
`);

await inSession(owner, async () => {
  await db.query(`select public.create_campaign_tester_bonus('${campAdmin}', 15, 1, '${reqAdmin}')`);
});

// Admin can delete owner's campaign via admin_delete_campaign
const adminDelRes = await inSession(admin, async () => {
  const { rows } = await db.query(`select public.admin_delete_campaign('${campAdmin}', 'Admin policy') as res`);
  return rows[0].res;
});
assert.equal(adminDelRes.status, 'deleted'); // No pending reports -> immediate delete

const { rows: campAdminCheck } = await db.query(`select * from public.campaigns where id = '${campAdmin}'`);
assert.equal(campAdminCheck.length, 0);

console.log('PASS: Admin delete path preserved and distinct.');

console.log('\n================================================================');
console.log('ALL OWNER TESTER BONUS REMOVAL TESTS PASSED SUCCESSFULLY!');
console.log('================================================================\n');

await db.close();
