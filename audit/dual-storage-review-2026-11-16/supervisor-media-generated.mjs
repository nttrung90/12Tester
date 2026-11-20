// Independent review: isolated PGlite and fake Storage only; no production access.
// Assertions describe required behavior. Nonzero exit means unresolved findings.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createDb } from '../../supabase/tools/baseline-lib.mjs';
import { cleanupSupabaseStorage } from '../../backend/storage-cleanup/cleanup-supabase-storage.mjs';
import { reconcileOrganizationStorage } from '../../backend/storage-cleanup/reconcile-org-storage.mjs';

const db = await createDb();
const results = [];
const silent = { info() {}, warn() {}, error() {} };
const owner = '11111111-1111-1111-1111-111111111111';
const user = '22222222-2222-2222-2222-222222222222';
const camp = '55555555-5555-5555-5555-555555555555';
const sql = text => db.exec(text);
const one = async text => (await db.query(text)).rows[0];
for (const file of readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(f => f.endsWith('.sql')).sort()) {
  await sql(readFileSync(new URL(`../../supabase/migrations/${file}`, import.meta.url), 'utf8'));
}
await sql(`
  insert into auth.users(id,email) values ('${owner}','owner@fixture.invalid'),('${user}','tester@fixture.invalid');
  insert into public.campaigns(id,owner_id,app_name,package_name,status)
    values('${camp}','${owner}','Review fixture','review.fixture','open');
  insert into public.campaign_members(campaign_id,user_id) values('${camp}','${user}');
  select set_config('request.jwt.claim.sub','${user}',false);
  update public.storage_organization_usage set total_capacity_bytes=1000000000,
    used_storage_bytes=100000000,active_reserved_bytes=0,is_org_metric_valid=true,
    last_reconciled_at=now(),dispatch_mode='supabase_preferred' where id=1;
`);

await sql("insert into public.storage_trusted_hosts(host) values('example.supabase.co'); update public.storage_organization_usage set local_project_ref='aaaaaaaaaaaaaaaaaaaa';");
async function check(id, title, fn, database = true) {
  if (database) await sql('begin');
  try { await fn(); results.push({ id, title, status: 'PASS' }); }
  catch (e) { results.push({ id, title, status: 'FAIL', evidence: e.message }); }
  finally { if (database) await sql('rollback'); }
}

const reserve = async (id = 'review-upload') => (await one(`select public.request_report_media_reservation('${camp}','${id}',500000,true) as r`)).r;
async function submit(path) {
  return (await one(`select public.submit_tester_report_v3(
    p_submission_context:='app_test',p_campaign_id:='${camp}'::uuid,
    p_content:='Review isolated submission',p_rating:=5,
    p_client_submit_id:='00000000-0000-0000-0000-000000000099'::uuid,
    p_attachments:=jsonb_build_array(jsonb_build_object(
      'screenshot_url','https://example.supabase.co/storage/v1/object/authenticated/report-screenshots/${path}',
      'image_hash','1111222233334444','sha256','${'a'.repeat(64)}','position',0))) as r`)).r;
}
async function insertMedia(id, { provider = 'supabase', status = 'finalized', bytes = 100 } = {}) {
  await sql(`insert into public.report_media_uploads(upload_id,user_id,campaign_id,provider,bucket,
    object_path,status,size_bytes,server_created_at,cleanup_due_at,delete_deadline_at,reservation_expires_at)
    values('${id}','${user}','${camp}','${provider}',
    '${provider === 'supabase' ? 'report-screenshots' : 'cross-testers.firebasestorage.app'}',
    'reports/${camp}/${user}/${id}.jpg','${status}',${bytes},now()-interval '10 days',
    now()-interval '1 day',now(),now()-interval '1 hour')`);
}

await check('URL-HOST', 'Reject foreign host despite matching bucket/path', async () => {
  const r = await one(`select public.is_valid_report_screenshot_url(
    'https://untrusted.invalid/storage/v1/object/authenticated/report-screenshots/reports/${camp}/${user}/fake.jpg',
    '${camp}','${user}') as accepted`);
  assert.equal(r.accepted, false, 'Foreign host was accepted');
});
await check('CONTROL-GOOGLE', 'Existing Google screenshot URL remains accepted', async () => {
  const r = await one(`select public.is_valid_report_screenshot_url(
    'https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/${camp}/${user}/old.jpg',
    '${camp}','${user}') as accepted`);
  assert.equal(r.accepted, true);
});
await check('SUBMIT-PROVENANCE', 'Reject Supabase screenshot without reservation or object', async () => {
  let accepted = false;
  try { accepted = (await submit(`reports/${camp}/${user}/missing.jpg`)).success === true; }
  catch { /* rejection is required */ }
  assert.equal(accepted, false, 'Report succeeded with no reservation and no Storage object');
});
await check('OBJECT-CLOCK', 'Keep expiry based on actual Storage creation time', async () => {
  const r = await reserve();
  await sql(`insert into storage.objects(bucket_id,name,owner,created_at,metadata)
    values('report-screenshots','${r.object_path}','${user}',now()-interval '2 days','{"size":500000}')`);
  assert.equal((await submit(r.object_path)).success, true);
  const row = await one(`select extract(epoch from(m.server_created_at-o.created_at))::float8 as drift_seconds
    from public.report_media_uploads m join storage.objects o on o.name=m.object_path where m.upload_id='review-upload'`);
  assert.equal(row.drift_seconds, 0, 'Ledger clock was reset at submit instead of Storage creation');
});
await check('QUOTA-FINALIZE', 'Retain uploaded bytes in U+R before next reconciliation', async () => {
  const r = await reserve();
  await sql(`insert into storage.objects(bucket_id,name,owner,metadata)
    values('report-screenshots','${r.object_path}','${user}','{"size":500000}')`);
  assert.equal((await submit(r.object_path)).success, true);
  const row = await one('select (used_storage_bytes+active_reserved_bytes)::text as effective from public.storage_organization_usage');
  assert.ok(BigInt(row.effective) >= 100500000n, `Effective usage lost upload: ${row.effective}`);
});
await check('RLS-EXPIRY', 'Deny direct Storage read after expiry even for uploader', async () => {
  await insertMedia('expired');
  await sql(`update public.report_media_uploads set view_expires_at=now()-interval '1 day';
    insert into storage.objects(bucket_id,name,owner) values('report-screenshots','reports/${camp}/${user}/expired.jpg','${user}');
    grant usage on schema storage to authenticated;
    grant select on storage.objects,public.report_media_uploads,public.profiles,public.campaigns to authenticated;
    set local role authenticated;`);
  const row = await one('select count(*)::int as visible from storage.objects');
  assert.equal(row.visible, 0, 'Uploader can still SELECT expired Storage object');
});
await check('CLEANUP-RETRY', 'Reclaim cleanup job after failed worker/expired lease', async () => {
  await insertMedia('retry');
  assert.equal((await one('select public.get_expired_report_media_for_cleanup(50,1) as r')).r.length, 1);
  await sql(`update public.report_media_uploads set updated_at=now()-interval '1 hour',lease_expires_at=now()-interval '1 second' where upload_id='retry'`);
  const again = (await one('select public.get_expired_report_media_for_cleanup(50,1) as r')).r;
  assert.equal(again.length, 1, 'Job stuck forever in deleting after failed deletion');
});
await check('CLEANUP-DRY-RUN', 'Dry run leaves cleanup state unchanged', async () => {
  await insertMedia('dry-run');
  await cleanupSupabaseStorage({ dryRun: true, logger: silent, supabaseClient: {
    rpc: async name => {
      assert.equal(name, 'get_expired_report_media_for_cleanup');
      return { data: (await one('select public.get_expired_report_media_for_cleanup(50,300,true) as r')).r, error: null };
    },
    storage: { from: () => ({ remove: async () => { throw Error('unexpected physical deletion'); } }) },
  } });
  assert.equal((await one(`select status from public.report_media_uploads where upload_id='dry-run'`)).status,
    'finalized', 'Dry run mutated job to deleting');
});
await check('ORPHAN-EXPIRED', 'Cleanup discovers reservation marked expired by allocator', async () => {
  await insertMedia('orphan', { status: 'reserved' });
  await reserve('next'); // allocator marks old reservation expired
  const rows = (await one('select public.get_expired_report_media_for_cleanup(50,300) as r')).r;
  assert.ok(rows.some(r => r.upload_id === 'orphan'), 'Expired reservation vanished from cleanup selection');
});
await check('CLEANUP-PROVIDER', 'Supabase cleanup must not claim GCS orphan', async () => {
  await insertMedia('gcs-orphan', { provider: 'gcs', status: 'reserved' });
  const rows = (await one('select public.get_expired_report_media_for_cleanup(50,300) as r')).r;
  assert.equal(rows.length, 0, 'Supabase cleanup claimed Google orphan');
});
await check('DELETE-IDEMPOTENT', 'Retry acknowledgement does not decrement quota twice', async () => {
  await insertMedia('deleted');
  await sql(`update public.storage_organization_usage set used_storage_bytes=1000; update public.report_media_uploads set quota_accounted_bytes=100 where upload_id='deleted';
    select public.record_report_media_deleted('deleted',100);
    select public.record_report_media_deleted('deleted',100);`);
  assert.equal((await one('select used_storage_bytes::text as n from public.storage_organization_usage')).n, '900', 'Delete retry decremented twice');
});
await check('METRICS-VALIDATION', 'Invalid project byte count fails safe', async () => {
  const summary = await reconcileOrganizationStorage({
    supabaseClient: { rpc: async () => ({ data: { success: true }, error: null }) },
    projectRefs: ['fixture'], totalCapacityBytes: 1000,
    fetchProjectFn: async () => ({ bytes: -1 }), dryRun: false, logger: silent,
  });
  assert.equal(summary.is_metric_valid, false, 'Negative metric marked valid');
}, false);
await check('DELETE-PARTIAL', 'Partial deletion response must not finalize whole pair', async () => {
  let acknowledgements = 0;
  await cleanupSupabaseStorage({ dryRun: false, logger: silent, supabaseClient: {
    rpc: async name => name === 'get_expired_report_media_for_cleanup'
      ? { data: [{ upload_id: 'partial', object_path: 'reports/fixture/full.jpg', thumbnail_object_path: 'reports/fixture/thumb.jpg' }], error: null }
      : (acknowledgements++, { data: { success: true }, error: null }),
    storage: { from: () => ({ remove: async () => ({ data: [{ name: 'reports/fixture/full.jpg' }], error: null }) }) },
  } });
  assert.equal(acknowledgements, 0, 'Pair finalized despite no confirmation thumbnail absent');
}, false);
await check('DELETE-PATH', 'Reject deletion candidate outside allowed prefix', async () => {
  let removals = 0;
  await cleanupSupabaseStorage({ dryRun: false, logger: silent, supabaseClient: {
    rpc: async name => name === 'get_expired_report_media_for_cleanup'
      ? { data: [{ upload_id: 'badpath', object_path: 'avatars/keep.jpg' }], error: null }
      : { data: {}, error: null },
    storage: { from: () => ({ remove: async () => (removals++, { data: [], error: null }) }) },
  } });
  assert.equal(removals, 0, 'Worker removed candidate outside reports/');
}, false);

const summary = { scope: 'PGlite + injected fake Storage; no production', results,
  passed: results.filter(r => r.status === 'PASS').length,
  failed: results.filter(r => r.status === 'FAIL').length };
writeFileSync(new URL('supervisor-media-results.json', import.meta.url), JSON.stringify(summary, null, 2) + '\n');
const paths = [
  'supabase/migrations/091_dual_storage_report_screenshots.sql',
  'backend/storage-cleanup/cleanup-supabase-storage.mjs',
  'backend/storage-cleanup/reconcile-org-storage.mjs',
  'backend/media-gateway/server.mjs',
  'backend/media-gateway/adapters/storage-adapter.mjs',
  'app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt',
  'app/src/main/java/com/ntt/crosstest/util/SupabaseStorageHelper.kt',
  'app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt',
];
writeFileSync(new URL('supervisor-media-snapshot.json', import.meta.url), JSON.stringify({
  checked_at: new Date().toISOString(),
  files: paths.map(path => ({ path, sha256: createHash('sha256').update(readFileSync(new URL(`../../${path}`, import.meta.url))).digest('hex') })),
}, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
await db.close();
process.exitCode = results.some(r => r.status === 'FAIL') ? 1 : 0;
