import { readFileSync } from 'node:fs';
import { PGlite } from './node_modules/@electric-sql/pglite/dist/index.js';
import { pgcrypto } from './node_modules/@electric-sql/pglite/dist/contrib/pgcrypto.js';
import { uuid_ossp } from './node_modules/@electric-sql/pglite/dist/contrib/uuid_ossp.js';

const owner = '11111111-1111-1111-1111-111111111111';
const tester = '22222222-2222-2222-2222-222222222222';
const campaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const mission1 = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const mission2 = 'cccccccc-cccc-cccc-cccc-cccccccccccc';

const createFreshDb = async () => {
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users (
      id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz, confirmation_token text,
      email_change text, email_change_token_new text, recovery_token text
    );
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    $$;
    grant usage on schema public, auth to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
  `);
  await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/full_schema_singapore.sql', import.meta.url), 'utf8'));
  await db.exec(`
    insert into auth.users(id, email) values ('${owner}', 'owner@recheck.test'), ('${tester}', 'tester@recheck.test');
    update public.profiles set credits = 10000 where id = '${owner}';
    insert into public.campaigns(id, owner_id, package_name, app_name, status, slot_number)
      values ('${campaign}', '${owner}', 'com.recheck.mission', 'Recheck App', 'open', 1);
    insert into public.missions(id, creator_id, campaign_id, title, max_participants,
      duration_days, reward_credits, status, ends_at)
      values ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '${owner}', '${campaign}', 'Recheck Mission 1', 5, 1, 20, 'expired', now() - interval '2 hours'),
             ('${mission2}', '${owner}', '${campaign}', 'Recheck Mission 2', 5, 1, 30, 'open', now() + interval '1 day');
  `);
  return db;
};

const asUser = async (db, uid, action) => {
  await db.exec(`begin; select set_config('request.jwt.claim.sub', '${uid}', false); set role authenticated;`);
  try {
    const result = await action();
    await db.exec('commit');
    return result;
  } catch (e) {
    await db.exec('rollback');
    throw e;
  } finally {
    await db.exec('reset role');
  }
};

const query = async (db, sql) => (await db.query(sql)).rows;

const submitHelper = (db, uid, cid) => (id, explicitMission = null, content = 'mission feedback', rating = 5, url = null, hash = null) =>
  asUser(db, uid, async () => {
    const effectiveUrl = url !== undefined && url !== null ? `'${url}'` : `'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${cid}%2F${uid}%2F${id}.jpg?alt=media&token=fixture'`;
    const effectiveHash = hash !== undefined && hash !== null ? `'${hash}'` : `'1234567890abcdef'`;
    return (await query(db, `select public.submit_tester_report(
      p_campaign_id => '${cid}',
      p_content => '${content}',
      p_screenshot_url => ${effectiveUrl},
      p_rating => ${rating},
      p_image_hash => ${effectiveHash},
      p_client_submit_id => '${id}'
      ${explicitMission ? `, p_mission_id => '${explicitMission}'` : ''}
    ) as result`))[0].result;
  });

console.log('=== BẮT ĐẦU KIỂM THỬ IDEMPOTENCY PROVENANCE & CANONICAL LEGACY RATING (C02 & C03) ===');

// Test 1: P01 - Migration 059/061 must preserve known exact receipts and reject changed payload
{
  const db = await createFreshDb();
  try {
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/057_report_idempotency_ledger_and_context.sql', import.meta.url), 'utf8'));
    const id = '44444444-4444-4444-4444-444444444444';
    const submit = submitHelper(db, tester, campaign);
    await submit(id, null, 'exact request');
    const prior = (await query(db, `select provenance from public.report_submission_receipts where client_submit_id='${id}'`))[0].provenance;
    if (prior !== 'exact_v1') throw new Error(`Fixture must start with exact_v1, got ${prior}`);

    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/059_report_idempotency_legacy_provenance.sql', import.meta.url), 'utf8'));
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/061_report_idempotency_provenance_and_canonical_fix.sql', import.meta.url), 'utf8'));
    const after = (await query(db, `select provenance from public.report_submission_receipts where client_submit_id='${id}'`))[0].provenance;
    if (after !== 'exact_v1') throw new Error(`Provenance was downgraded: prior=${prior}, after=${after}`);

    let error = null;
    try {
      await asUser(db, tester, () => query(db, `select public.submit_tester_report(
        p_campaign_id => '${campaign}', p_content => 'exact request', p_client_submit_id => '${id}'
      ) as result`));
    } catch (e) { error = e.message; }

    if (!error?.includes('CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH')) {
      throw new Error(`Known exact receipt allowed payload mismatch: ${error}`);
    }

    // Replay with identical payload must succeed
    const replayOk = await submit(id, null, 'exact request');
    if (!replayOk.success) throw new Error('Replay identical payload failed');

    console.log('[PASS] P01: Applying 059 & 061 preserves exact_v1 provenance and strictly validates exact receipts');
  } finally {
    await db.close();
  }
}

// Test 2: P02 - Legacy approved-comment replay must work when the frozen report rating is not five (Matrix 1..4)
for (const rating of [1, 2, 3, 4]) {
  const db = await createFreshDb();
  try {
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/049_mission_report_hardening.sql', import.meta.url), 'utf8'));
    const initialId = `44444444-4444-4444-4444-44444444444${rating}`;
    const submit = submitHelper(db, tester, campaign);
    const first = await submit(initialId, null, `initial rating ${rating}`, rating);
    await asUser(db, owner, () => query(db, `select public.approve_tester_report('${first.report_id}', 0)`));

    const commentId = `55555555-5555-5555-5555-55555555555${rating}`;
    const comment = async (overrideRating = null) => asUser(db, tester, async () => (await query(db, `select public.submit_tester_report(
      p_campaign_id => '${campaign}', p_content => 'extra comment', p_client_submit_id => '${commentId}'
      ${overrideRating !== null ? `, p_rating => ${overrideRating}` : ''}
    ) as result`))[0].result);

    const edited = await comment();
    if (edited.status !== 'approved') throw new Error('Fixture must commit approved comment');

    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/057_report_idempotency_ledger_and_context.sql', import.meta.url), 'utf8'));
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/059_report_idempotency_legacy_provenance.sql', import.meta.url), 'utf8'));
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/061_report_idempotency_provenance_and_canonical_fix.sql', import.meta.url), 'utf8'));

    // Replay with default rating (5)
    const replayRes = await comment();
    if (!replayRes.success) throw new Error(`Replay failed for rating ${rating}`);

    const rep = (await query(db, `select rating from public.tester_reports where id='${first.report_id}'`))[0];
    if (rep.rating !== rating) throw new Error(`Replay altered frozen rating: expected ${rating}, got ${rep.rating}`);

    // Replay with explicit matching rating
    const replayExplicit = await comment(rating);
    if (!replayExplicit.success) throw new Error(`Replay with explicit rating failed for rating ${rating}`);

    console.log(`[PASS] P02: Legacy approved-comment replay preserves locked rating ${rating} under default and explicit rating calls`);
  } finally {
    await db.close();
  }
}

// Test 3: Provenance recovery in 061 for databases that already ran buggy 059
{
  const db = await createFreshDb();
  try {
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/057_report_idempotency_ledger_and_context.sql', import.meta.url), 'utf8'));
    const id = '66666666-6666-6666-6666-666666666666';
    const submit = submitHelper(db, tester, campaign);
    await submit(id, null, 'live exact submission');

    // Simulate buggy 059 running and corrupting exact_v1 into legacy_backfill
    await db.exec(`
      update public.report_submission_receipts
      set provenance = 'legacy_backfill'
      where client_submit_id = '${id}';
    `);

    // Verify it is currently legacy_backfill
    const corrupted = (await query(db, `select provenance from public.report_submission_receipts where client_submit_id='${id}'`))[0].provenance;
    if (corrupted !== 'legacy_backfill') throw new Error(`Expected corrupted provenance to be legacy_backfill, got ${corrupted}`);

    // Apply migration 061
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/061_report_idempotency_provenance_and_canonical_fix.sql', import.meta.url), 'utf8'));

    // Verify 061 recovered exact_v1 using evidence (provisional_granted = true)
    const recovered = (await query(db, `select provenance from public.report_submission_receipts where client_submit_id='${id}'`))[0].provenance;
    if (recovered !== 'exact_v1') throw new Error(`061 failed to recover exact_v1 provenance: got ${recovered}`);

    console.log('[PASS] P03: Migration 061 successfully recovers overwritten exact_v1 receipts using evidence');
  } finally {
    await db.close();
  }
}

console.log('=== TẤT CẢ KIỂM THỬ IDEMPOTENCY PROVENANCE & CANONICAL LEGACY RATING ĐỀU PASS 100% ===');
