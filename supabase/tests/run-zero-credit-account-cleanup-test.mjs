import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

const adminId = '11111111-1111-4111-8111-111111111111';
const modId = '12121212-1212-4212-8212-121212121212';
const activeUserId = '22222222-2222-4222-8222-222222222222';
const activeCampId = '44444444-4444-4444-8444-444444444444';

const one = async (sql, params = []) => (await db.query(sql, params)).rows[0];
const rows = async (sql, params = []) => (await db.query(sql, params)).rows;

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

console.log('=== LOADING ALL ACTIVE MIGRATIONS (062 THROUGH 099) ===');
const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62)
  .sort();

for (const name of migrationFiles) {
  await db.exec(read('supabase/migrations/' + name));
}
console.log(`Loaded ${migrationFiles.length} migrations up to ${migrationFiles.at(-1)}.`);

await db.exec(`
  insert into auth.users (id, email) values
    ('${adminId}', 'admin@zero-cleanup.test'),
    ('${modId}', 'mod@zero-cleanup.test'),
    ('${activeUserId}', 'active@zero-cleanup.test');

  update public.profiles
  set role = 'admin', display_name = 'System Admin', credits = 500
  where id = '${adminId}';

  update public.profiles
  set role = 'moderator', display_name = 'System Mod', credits = 200
  where id = '${modId}';

  update public.profiles
  set role = 'member', display_name = 'Active Member', credits = 150
  where id = '${activeUserId}';

  insert into public.campaigns (id, owner_id, app_name, package_name, status) values
    ('${activeCampId}', '${activeUserId}', 'Active Owner App', 'com.active.owner', 'open');
`);

console.log('=== RUNNING ZERO-CREDIT ACCOUNT AUTO-PURGE TESTS ===');

// ---------------------------------------------------------------------------
// 1. Schema, index, constraint, SECURITY DEFINER, and ACL grants
// ---------------------------------------------------------------------------
await test('Schema column, constraint, index, SECURITY DEFINER, and ACL grants are properly configured', async () => {
  const col = await one(`
    select column_name, data_type, is_nullable
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'profiles'
      and column_name = 'zero_credits_since'
  `);
  assert.ok(col, 'zero_credits_since column must exist on public.profiles');
  assert.equal(col.data_type, 'timestamp with time zone');
  assert.equal(col.is_nullable, 'YES');

  const idx = await one(`
    select indexname
    from pg_indexes
    where schemaname = 'public'
      and indexname = 'idx_profiles_zero_credits_since'
  `);
  assert.ok(idx, 'idx_profiles_zero_credits_since must exist');

  const chk = await one(`
    select conname
    from pg_constraint
    where conrelid = 'public.profiles'::regclass
      and conname = 'chk_profiles_zero_credits_since'
  `);
  assert.ok(chk, 'chk_profiles_zero_credits_since constraint must exist');

  const fnMeta = await one(`
    select prosecdef, proconfig
    from pg_proc
    where pronamespace = 'public'::regnamespace
      and proname = 'purge_zero_credit_accounts'
  `);
  assert.equal(fnMeta.prosecdef, true, 'purge_zero_credit_accounts must be SECURITY DEFINER');

  const privs = await one(`
    select
      has_function_privilege('anon', 'public.purge_zero_credit_accounts(integer, boolean, integer)', 'EXECUTE') as anon_exec,
      has_function_privilege('authenticated', 'public.purge_zero_credit_accounts(integer, boolean, integer)', 'EXECUTE') as auth_exec,
      has_function_privilege('service_role', 'public.purge_zero_credit_accounts(integer, boolean, integer)', 'EXECUTE') as service_exec,
      has_function_privilege('anon', 'public.backfill_profiles_zero_credits_since()', 'EXECUTE') as bf_anon_exec,
      has_function_privilege('authenticated', 'public.backfill_profiles_zero_credits_since()', 'EXECUTE') as bf_auth_exec,
      has_function_privilege('service_role', 'public.backfill_profiles_zero_credits_since()', 'EXECUTE') as bf_service_exec
  `);
  assert.equal(privs.anon_exec, false);
  assert.equal(privs.auth_exec, false);
  assert.equal(privs.service_exec, true);
  assert.equal(privs.bf_anon_exec, false);
  assert.equal(privs.bf_auth_exec, false);
  assert.equal(privs.bf_service_exec, true);

  await assert.rejects(
    () => asRole('anon', null, () => db.query(`select public.purge_zero_credit_accounts()`)),
    /permission denied/i
  );
  await assert.rejects(
    () => asRole('authenticated', activeUserId, () => db.query(`select public.purge_zero_credit_accounts()`)),
    /permission denied/i
  );
});

// ---------------------------------------------------------------------------
// 2. Trigger tracking of zero_credits_since on INSERT and UPDATE
// ---------------------------------------------------------------------------
await test('trg_profiles_track_zero_credits accurately tracks transitions to/from 0 credits', async () => {
  const uId = 'a1000000-0000-4000-8000-000000000001';
  await db.exec(`insert into auth.users (id, email) values ('${uId}', 'track@zero.test');`);

  let p = await one(`select credits, zero_credits_since from public.profiles where id = '${uId}'`);
  assert.ok(p.credits > 0, 'New user receives welcome credits > 0');
  assert.equal(p.zero_credits_since, null, 'zero_credits_since must be NULL when credits > 0');

  // Even if someone tries to set zero_credits_since while credits > 0, trigger keeps it NULL
  await db.exec(`update public.profiles set zero_credits_since = clock_timestamp() - interval '40 days' where id = '${uId}'`);
  p = await one(`select credits, zero_credits_since from public.profiles where id = '${uId}'`);
  assert.equal(p.zero_credits_since, null);

  // Transition from > 0 to 0 sets zero_credits_since
  await db.exec(`update public.profiles set credits = 0 where id = '${uId}'`);
  p = await one(`select credits, zero_credits_since from public.profiles where id = '${uId}'`);
  assert.equal(p.credits, 0);
  assert.ok(p.zero_credits_since !== null, 'zero_credits_since must be set when credits drops to 0');

  // Explicitly setting a historical timestamp while credits = 0 is respected
  const customTs = '2026-09-01T00:00:00.000Z';
  await db.exec(`update public.profiles set zero_credits_since = '${customTs}'::timestamptz where id = '${uId}'`);
  p = await one(`select zero_credits_since from public.profiles where id = '${uId}'`);
  assert.equal(new Date(p.zero_credits_since).toISOString(), customTs);

  // Updating other profile columns (or setting credits = 0 again) preserves existing zero_credits_since
  await db.exec(`update public.profiles set display_name = 'Still Zero', credits = 0 where id = '${uId}'`);
  p = await one(`select zero_credits_since from public.profiles where id = '${uId}'`);
  assert.equal(new Date(p.zero_credits_since).toISOString(), customTs);

  // Attempting to set zero_credits_since = NULL while credits = 0 preserves old timestamp
  await db.exec(`update public.profiles set zero_credits_since = null where id = '${uId}'`);
  p = await one(`select zero_credits_since from public.profiles where id = '${uId}'`);
  assert.equal(new Date(p.zero_credits_since).toISOString(), customTs);

  // Earning credits (> 0) immediately resets zero_credits_since to NULL
  await db.exec(`update public.profiles set credits = 10 where id = '${uId}'`);
  p = await one(`select credits, zero_credits_since from public.profiles where id = '${uId}'`);
  assert.equal(p.credits, 10);
  assert.equal(p.zero_credits_since, null);
});

// ---------------------------------------------------------------------------
// 3. Option A Historical Backfill from public.credit_logs
// ---------------------------------------------------------------------------
await test('Option A backfill (backfill_profiles_zero_credits_since) derives exact earliest zero timestamp after last positive balance', async () => {
  const uHist = 'a2000000-0000-4000-8000-000000000001';
  const uNoLogs = 'a2000000-0000-4000-8000-000000000002';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${uHist}', 'hist@zero.test'),
      ('${uNoLogs}', 'nologs@zero.test');

    -- Clear automatic welcome log so we can construct an exact ledger history
    delete from public.credit_logs where user_id in ('${uHist}', '${uNoLogs}');

    -- Ledger history for uHist:
    -- 50 days ago: balance 100
    -- 45 days ago: balance 0 (first drop to 0)
    -- 38 days ago: balance 20 (recovered to positive!)
    -- 33 days ago: balance 0 (dropped to 0 again after last positive)
    -- 31 days ago: balance 0 (subsequent zero log)
    insert into public.credit_logs (user_id, amount, action, note, balance, created_at) values
      ('${uHist}', 100, 'welcome', 'init', 100, '2026-09-20T00:00:00Z'::timestamptz),
      ('${uHist}', -100, 'penalty', 'drop 1', 0, '2026-09-25T00:00:00Z'::timestamptz),
      ('${uHist}', 20, 'reward', 'recover', 20, '2026-10-02T00:00:00Z'::timestamptz),
      ('${uHist}', -20, 'penalty', 'drop 2 (expected anchor)', 0, '2026-10-07T12:30:00Z'::timestamptz),
      ('${uHist}', 0, 'penalty', 'still 0', 0, '2026-10-09T00:00:00Z'::timestamptz);

    update public.profiles
    set credits = 0,
        created_at = '2026-09-20T00:00:00Z'::timestamptz
    where id = '${uHist}';

    update public.profiles
    set credits = 0,
        created_at = '2026-09-15T08:00:00Z'::timestamptz
    where id = '${uNoLogs}';
  `);

  await db.query(`select public.backfill_profiles_zero_credits_since()`);

  const pHist = await one(`select zero_credits_since from public.profiles where id = '${uHist}'`);
  assert.equal(
    new Date(pHist.zero_credits_since).toISOString(),
    '2026-10-07T12:30:00.000Z',
    'Must pick earliest zero balance AFTER last positive balance (2026-10-07T12:30:00Z)'
  );

  const pNoLogs = await one(`select zero_credits_since from public.profiles where id = '${uNoLogs}'`);
  assert.equal(
    new Date(pNoLogs.zero_credits_since).toISOString(),
    '2026-09-15T08:00:00.000Z',
    'Account with 0 credits and no credit_logs must fall back to profile.created_at'
  );
});

// ---------------------------------------------------------------------------
// 4. 30-day continuous boundary & credit_logs defense-in-depth guard
// ---------------------------------------------------------------------------
await test('Purges only accounts with 0 credits continuously for >= 30 days and no positive credit_logs within 30 days', async () => {
  const u29Days = 'a3000000-0000-4000-8000-000000000001';
  const u30DaysWithRecentPositiveLog = 'a3000000-0000-4000-8000-000000000002';
  const u31DaysClean = 'a3000000-0000-4000-8000-000000000003';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${u29Days}', 'd29@zero.test'),
      ('${u30DaysWithRecentPositiveLog}', 'recentlog@zero.test'),
      ('${u31DaysClean}', 'd31clean@zero.test');

    delete from public.credit_logs where user_id in ('${u29Days}', '${u30DaysWithRecentPositiveLog}', '${u31DaysClean}');

    -- 1. u29Days: 0 credits for 29 days 12 hours (< 30 days) -> KEEP
    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '29 days 12 hours'
    where id = '${u29Days}';

    -- 2. u30DaysWithRecentPositiveLog: zero_credits_since says 35 days ago,
    --    but credit_logs has a positive balance 10 days ago -> KEEP (defense-in-depth)
    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '35 days'
    where id = '${u30DaysWithRecentPositiveLog}';

    insert into public.credit_logs (user_id, amount, action, note, balance, created_at) values
      ('${u30DaysWithRecentPositiveLog}', 10, 'reward', 'recent gain', 10, clock_timestamp() - interval '10 days'),
      ('${u30DaysWithRecentPositiveLog}', -10, 'penalty', 'back to zero', 0, clock_timestamp() - interval '9 days');

    -- 3. u31DaysClean: 0 credits for 31 days, only old logs (> 30 days ago) -> PURGE
    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '31 days'
    where id = '${u31DaysClean}';

    insert into public.credit_logs (user_id, amount, action, note, balance, created_at) values
      ('${u31DaysClean}', 100, 'welcome', 'old welcome', 100, clock_timestamp() - interval '45 days'),
      ('${u31DaysClean}', -100, 'penalty', 'old zero', 0, clock_timestamp() - interval '31 days');
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_zero_credit_accounts(30, false, 200) as r`)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.status, 'success');
  assert.equal(res.scanned, 1);
  assert.equal(res.deleted, 1);
  assert.deepEqual(res.deleted_user_ids, [u31DaysClean]);

  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${u29Days}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${u30DaysWithRecentPositiveLog}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${u31DaysClean}'`)).c, 0);
  assert.equal((await one(`select count(*)::int as c from public.profiles where id = '${u31DaysClean}'`)).c, 0);
});

// ---------------------------------------------------------------------------
// 5. Protection for Admin, Moderator, Admin Capabilities & Unsettled Escrows
// ---------------------------------------------------------------------------
await test('Protects admin/moderator accounts and accounts with positive unsettled escrow (missions, bonus rounds, app update releases)', async () => {
  const uCapMember = 'a4000000-0000-4000-8000-000000000001';
  const uMissionEscrow = 'a4000000-0000-4000-8000-000000000002';
  const uBonusEscrow = 'a4000000-0000-4000-8000-000000000003';
  const uReleaseEscrow = 'a4000000-0000-4000-8000-000000000004';

  const campMission = 'c4000000-0000-4000-8000-000000000002';
  const campBonus = 'c4000000-0000-4000-8000-000000000003';
  const campRelease = 'c4000000-0000-4000-8000-000000000004';

  const missionId = 'd4000000-0000-4000-8000-000000000002';
  const bonusRoundId = 'd4000000-0000-4000-8000-000000000003';
  const releaseId = 'd4000000-0000-4000-8000-000000000004';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${uCapMember}', 'cap@zero.test'),
      ('${uMissionEscrow}', 'mission@zero.test'),
      ('${uBonusEscrow}', 'bonus@zero.test'),
      ('${uReleaseEscrow}', 'release@zero.test');

    delete from public.credit_logs where user_id in (
      '${adminId}', '${modId}', '${uCapMember}', '${uMissionEscrow}', '${uBonusEscrow}', '${uReleaseEscrow}'
    );

    -- Give admin, mod, and all 4 test users 0 credits for 45 days
    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '45 days'
    where id in ('${adminId}', '${modId}', '${uCapMember}', '${uMissionEscrow}', '${uBonusEscrow}', '${uReleaseEscrow}');

    -- 1. uCapMember has explicit admin capability
    insert into public.admin_capabilities (admin_id, capability, granted_by)
    values ('${uCapMember}', 'manage_roles', '${adminId}');

    -- 2. uMissionEscrow has unsettled mission escrow
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campMission}', '${uMissionEscrow}', 'Mission App', 'com.test.mission', 'paused');

    insert into public.missions (
      id, creator_id, campaign_id, title, description, max_participants, duration_days,
      reward_credits, fee_credits, status, escrow_total_credits, escrow_remaining_credits, escrow_refunded, ends_at
    ) values (
      '${missionId}', '${uMissionEscrow}', '${campMission}', 'Test Mission', 'Desc', 5, 3,
      10, 0, 'open', 50, 50, false, clock_timestamp() + interval '2 days'
    );

    -- 3. uBonusEscrow has active tester bonus round with remaining_credits > 0
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campBonus}', '${uBonusEscrow}', 'Bonus App', 'com.test.bonus', 'paused');

    insert into public.campaign_tester_bonus_rounds (
      id, campaign_id, original_campaign_id, owner_id, app_name,
      reward_credits, max_recipients, paid_count, reserved_credits, remaining_credits, spent_credits, refunded_credits,
      status, client_request_id, request_fingerprint
    ) values (
      '${bonusRoundId}', '${campBonus}', '${campBonus}', '${uBonusEscrow}', 'Bonus App',
      10, 5, 1, 50, 40, 10, 0,
      'active', gen_random_uuid(), 'fp-bonus'
    );

    -- 4. uReleaseEscrow has active app update release with remaining_credits > 0
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${campRelease}', '${uReleaseEscrow}', 'Release App', 'com.test.release', 'paused');

    insert into public.app_update_releases (
      id, campaign_id, owner_id, owner_id_snapshot, package_name, app_name, version_name,
      target_version_code, release_notes, status, reward_per_claim, max_claims,
      reserved_credits, paid_count, remaining_credits, refunded_credits
    ) values (
      '${releaseId}', '${campRelease}', '${uReleaseEscrow}', '${uReleaseEscrow}', 'com.test.release', 'Release App', '1.1',
      2, 'notes', 'active', 6, 10,
      60, 0, 60, 0
    );
  `);

  const resProtected = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_zero_credit_accounts(30, false, 200) as r`)).r;
  });

  assert.equal(resProtected.scanned, 0, 'All 6 accounts must be protected from purge');
  assert.equal(resProtected.deleted, 0);

  // Now settle/close the escrows (remaining = 0) while accounts stay at 0 credits for 45 days
  await db.exec(`
    update public.missions
    set escrow_remaining_credits = 0, escrow_refunded = true, status = 'expired'
    where id = '${missionId}';

    update public.campaign_tester_bonus_rounds
    set remaining_credits = 0, refunded_credits = 40, status = 'closed'
    where id = '${bonusRoundId}';

    update public.app_update_releases
    set remaining_credits = 0, refunded_credits = 60, status = 'closed'
    where id = '${releaseId}';
  `);

  const resAfterSettle = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_zero_credit_accounts(30, false, 200) as r`)).r;
  });

  assert.equal(resAfterSettle.scanned, 3, '3 settled member accounts must now be eligible');
  assert.equal(resAfterSettle.deleted, 3);

  // Admin, Moderator, and Capability-holder must still exist
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${adminId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${modId}'`)).c, 1);
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${uCapMember}'`)).c, 1);

  // The 3 settled escrow accounts must be completely removed
  assert.equal((await one(`select count(*)::int as c from auth.users where id in ('${uMissionEscrow}', '${uBonusEscrow}', '${uReleaseEscrow}')`)).c, 0);
  assert.equal((await one(`select count(*)::int as c from public.profiles where id in ('${uMissionEscrow}', '${uBonusEscrow}', '${uReleaseEscrow}')`)).c, 0);
});

// ---------------------------------------------------------------------------
// 6. Comprehensive multi-table profile, campaign, bonus, media & journal cleanup
// ---------------------------------------------------------------------------
await test('Deletes account and all associated records across every related table, including NO ACTION FKs and trigger-populated journals', async () => {
  const victimId = 'a5000000-0000-4000-8000-000000000001';
  const victimCampId = 'c5000000-0000-4000-8000-000000000001';
  const victimMissionId = 'd5000000-0000-4000-8000-000000000001';
  const victimRoundId = 'e5000000-0000-4000-8000-000000000001';
  const activeOwnerRoundId = 'e5000000-0000-4000-8000-000000000002';
  const victimReportOnActiveCamp = 'f5000000-0000-4000-8000-000000000001';
  const activeReportOnVictimCamp = 'f5000000-0000-4000-8000-000000000002';
  const victimReleaseId = 'b5000000-0000-4000-8000-000000000001';
  const gcsMediaId = '95000000-0000-4000-8000-000000000001';
  const supaActiveMediaId = '95000000-0000-4000-8000-000000000002';
  const notifId = '85000000-0000-4000-8000-000000000001';
  const warnId = '75000000-0000-4000-8000-000000000001';

  await db.exec(`
    insert into auth.users (id, email) values ('${victimId}', 'victim@zero.test');
    delete from public.credit_logs where user_id = '${victimId}';

    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '35 days'
    where id = '${victimId}';

    -- Victim owns a campaign (insert as open so tester_reports trigger allows report insertion, then complete it)
    insert into public.campaigns (id, owner_id, app_name, package_name, status)
    values ('${victimCampId}', '${victimId}', 'Victim App', 'com.victim.app', 'open');

    -- Memberships, checkins, feedbacks, reports in both directions
    insert into public.campaign_members (campaign_id, user_id) values
      ('${victimCampId}', '${activeUserId}'),
      ('${activeCampId}', '${victimId}');

    insert into public.checkins (campaign_id, user_id, checkin_date) values
      ('${victimCampId}', '${activeUserId}', current_date - 40),
      ('${activeCampId}', '${victimId}', current_date - 40);

    insert into public.feedbacks (campaign_id, user_id, rating, comment) values
      ('${victimCampId}', '${activeUserId}', 5, 'good'),
      ('${activeCampId}', '${victimId}', 4, 'nice');

    insert into public.tester_reports (id, campaign_id, user_id, report_type, content, status, submission_context, created_at) values
      ('${victimReportOnActiveCamp}', '${activeCampId}', '${victimId}', 'feedback', 'Victim report', 'approved', 'app_test', clock_timestamp() - interval '40 days'),
      ('${activeReportOnVictimCamp}', '${victimCampId}', '${activeUserId}', 'feedback', 'Active user report', 'approved', 'app_test', clock_timestamp() - interval '40 days');

    update public.campaigns set status = 'completed' where id = '${victimCampId}';

    insert into public.tester_report_attachments (report_id, position, screenshot_url, storage_provider) values
      ('${victimReportOnActiveCamp}', 0, 'https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/1.jpg', 'gcs');

    -- Settled mission on victim's campaign
    insert into public.missions (
      id, creator_id, campaign_id, title, description, max_participants, duration_days,
      reward_credits, fee_credits, status, escrow_total_credits, escrow_remaining_credits, escrow_refunded, ends_at
    ) values (
      '${victimMissionId}', '${victimId}', '${victimCampId}', 'Victim Mission', 'Desc', 2, 2,
      10, 0, 'expired', 20, 0, true, clock_timestamp() - interval '35 days'
    );

    -- Closed bonus round owned by victim + award where victim was owner AND award where victim was tester on activeUserId's round
    insert into public.campaign_tester_bonus_rounds (
      id, campaign_id, original_campaign_id, owner_id, app_name,
      reward_credits, max_recipients, paid_count, reserved_credits, remaining_credits, spent_credits, refunded_credits,
      status, client_request_id, request_fingerprint
    ) values
      ('${victimRoundId}', '${victimCampId}', '${victimCampId}', '${victimId}', 'Victim App', 10, 2, 1, 20, 0, 10, 10, 'closed', gen_random_uuid(), 'fp-v1'),
      ('${activeOwnerRoundId}', '${activeCampId}', '${activeCampId}', '${activeUserId}', 'Active App', 10, 2, 1, 20, 10, 10, 0, 'active', gen_random_uuid(), 'fp-a1');

    insert into public.campaign_tester_bonus_awards (
      round_id, report_id, campaign_id, original_campaign_id, owner_id, tester_id, amount, award_order
    ) values
      ('${victimRoundId}', '${activeReportOnVictimCamp}', '${victimCampId}', '${victimCampId}', '${victimId}', '${activeUserId}', 10, 1),
      ('${activeOwnerRoundId}', '${victimReportOnActiveCamp}', '${activeCampId}', '${activeCampId}', '${activeUserId}', '${victimId}', 10, 1);

    insert into public.campaign_tester_bonus_events (
      round_id, campaign_id, original_campaign_id, owner_id, event_type, amount, actor_id
    ) values
      ('${victimRoundId}', '${victimCampId}', '${victimCampId}', '${victimId}', 'reserve', 20, '${victimId}');

    -- App update controls, closed release, claims, operations, financial events
    insert into public.app_update_releases (
      id, campaign_id, owner_id, owner_id_snapshot, package_name, app_name, version_name,
      target_version_code, release_notes, status, reward_per_claim, max_claims,
      reserved_credits, paid_count, remaining_credits, refunded_credits
    ) values (
      '${victimReleaseId}', '${victimCampId}', '${victimId}', '${victimId}', 'com.victim.app', 'Victim App', '1.0',
      1, 'init', 'closed', 6, 5, 30, 0, 0, 30
    );

    insert into public.app_update_controls (owner_id, package_name, highest_version_code, active_release_id)
    values ('${victimId}', 'com.victim.app', 1, null);

    insert into public.app_update_operations (operation_id, owner_id, release_id, status, campaign_id)
    values ('op-victim-1', '${victimId}', '${victimReleaseId}', 'completed', '${victimCampId}');

    insert into public.app_update_financial_events (release_id, event_type, amount, owner_id, tester_id)
    values ('${victimReleaseId}', 'refund', 30, '${victimId}', null);

    -- Notifications, popup states, popup receipts, daily tasks, quest claims, slots, warnings, contact reports, topup orders, receipts
    insert into public.notifications (id, user_id, title, message, type)
    values ('${notifId}', '${victimId}', 'Admin Notice', 'Hello', 'admin');

    insert into public.notification_popup_states (notification_id)
    values ('${notifId}');

    insert into public.notification_popup_receipts (user_id, foreground_session_id, notification_id, display_number)
    values ('${victimId}', gen_random_uuid(), '${notifId}', 1);

    insert into public.user_daily_tasks (user_id, task_date, mode)
    values ('${victimId}', current_date - 35, 'member');

    insert into public.user_quest_claims (user_id, quest_type, milestone, reward_credits, period_key)
    values ('${victimId}', 'daily', 3, 5, current_date - 40);

    insert into public.user_slots (user_id, slot_number, expires_at)
    values ('${victimId}', 2, clock_timestamp() - interval '10 days');

    insert into public.slot_purchase_receipts (user_id, client_request_id, slot_number, cost_credits, balance_after, expires_at)
    values ('${victimId}', gen_random_uuid(), 2, 50, 0, clock_timestamp() - interval '10 days');

    insert into public.campaign_creation_receipts (
      user_id, client_request_id, campaign_id, created_campaign_id, package_name,
      slot_number, target_testers, slot_1_cost, target_cost, total_cost, min_credits_required, balance_after, payload_hash
    ) values (
      '${victimId}', gen_random_uuid(), '${victimCampId}', '${victimCampId}', 'com.victim.app',
      1, 12, 0, 0, 0, 10, 0, 'hash-1'
    );

    insert into public.campaign_push_receipts (user_id, campaign_id, target_campaign_id, client_request_id, cost_credits, balance_after, business_date, expires_at, push_order)
    values ('${victimId}', '${victimCampId}', '${victimCampId}', gen_random_uuid(), 50, 0, current_date - 40, clock_timestamp() - interval '39 days', 1);

    insert into public.user_warnings (id, user_id, warning_type, message)
    values ('${warnId}', '${victimId}', 'admin_manual', 'Warn');

    insert into public.contact_reports (user_id, category, message, status)
    values ('${victimId}', 'bug', 'Help', 'new');

    insert into public.topup_orders (user_id, credits_awarded, price_usd, price_vnd, payment_status)
    values ('${victimId}', 100, 1.00, 25000, 'pending');

    insert into public.report_submission_receipts (user_id, client_submit_id, campaign_id, report_id, payload_fingerprint, response_json)
    values ('${victimId}', gen_random_uuid(), '${activeCampId}', '${victimReportOnActiveCamp}', 'fp', '{}'::jsonb);

    insert into public.report_cancelled_requests (user_id, client_submit_id)
    values ('${victimId}', gen_random_uuid());

    insert into public.home_stats_policies (effective_from, cycle_days, created_by, updated_by)
    values (current_date - 40, 2, '${victimId}', '${victimId}');

    -- Dual-storage report_media_uploads: one GCS row (0 reserved/quota) and one active Supabase row holding quota
    insert into public.report_media_uploads (
      id, upload_id, user_id, campaign_id, report_id, provider, bucket, object_path,
      reserved_bytes, quota_accounted_bytes, status
    ) values
      ('${gcsMediaId}', 'up-gcs', '${victimId}', '${activeCampId}', '${victimReportOnActiveCamp}', 'gcs', 'cross-testers.firebasestorage.app', 'reports/${activeCampId}/${victimId}/1.jpg', 0, 0, 'finalized'),
      ('${supaActiveMediaId}', 'up-supa', '${victimId}', '${activeCampId}', '${victimReportOnActiveCamp}', 'supabase', 'report-screenshots', 'reports/${activeCampId}/${victimId}/2.jpg', 0, 4096, 'finalized');

    -- Age all credit_logs triggered by checkin/report insertion to 40 days ago and set victim to 0 credits since 35 days ago
    update public.credit_logs
    set created_at = clock_timestamp() - interval '40 days'
    where user_id = '${victimId}';

    insert into public.credit_logs (user_id, amount, action, note, balance, created_at)
    values ('${victimId}', -10, 'penalty', 'dropped to 0', 0, clock_timestamp() - interval '35 days');

    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '35 days'
    where id = '${victimId}';
  `);

  const res = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_zero_credit_accounts(30, false, 200) as r`)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.status, 'success');
  assert.equal(res.deleted, 1);
  assert.equal(res.deleted_campaigns, 1);
  assert.equal(res.deleted_report_media_uploads, 1);
  assert.equal(res.expired_report_media_uploads, 1);

  // Verify every table has 0 records remaining for victimId
  const tablesByUserId = [
    ['auth.users', 'id'],
    ['public.profiles', 'id'],
    ['public.campaigns', 'owner_id'],
    ['public.campaign_members', 'user_id'],
    ['public.checkins', 'user_id'],
    ['public.feedbacks', 'user_id'],
    ['public.tester_reports', 'user_id'],
    ['public.missions', 'creator_id'],
    ['public.campaign_tester_bonus_rounds', 'owner_id'],
    ['public.campaign_tester_bonus_awards', 'owner_id'],
    ['public.campaign_tester_bonus_awards', 'tester_id'],
    ['public.campaign_tester_bonus_events', 'owner_id'],
    ['public.app_update_releases', 'owner_id'],
    ['public.app_update_controls', 'owner_id'],
    ['public.app_update_operations', 'owner_id'],
    ['public.app_update_financial_events', 'owner_id'],
    ['public.credit_logs', 'user_id'],
    ['public.notifications', 'user_id'],
    ['public.notification_popup_receipts', 'user_id'],
    ['public.user_daily_tasks', 'user_id'],
    ['public.user_quest_claims', 'user_id'],
    ['public.user_slots', 'user_id'],
    ['public.slot_purchase_receipts', 'user_id'],
    ['public.campaign_creation_receipts', 'user_id'],
    ['public.campaign_push_receipts', 'user_id'],
    ['public.user_warnings', 'user_id'],
    ['public.contact_reports', 'user_id'],
    ['public.topup_orders', 'user_id'],
    ['public.report_submission_receipts', 'user_id'],
    ['public.report_cancelled_requests', 'user_id'],
    ['public.daily_test_events_journal', 'user_id'],
    ['public.campaign_status_journal', 'owner_id'],
    ['public.user_role_journal', 'user_id'],
    ['public.campaign_archive', 'owner_id'],
    ['public.campaign_removal_receipts', 'owner_id'],
  ];

  for (const [tbl, col] of tablesByUserId) {
    const cnt = (await one(`select count(*)::int as c from ${tbl} where ${col} = '${victimId}'`)).c;
    assert.equal(cnt, 0, `Table ${tbl}.${col} must have 0 rows for purged user ${victimId}`);
  }
  assert.equal((await one(`select count(*)::int as c from public.notification_popup_states where notification_id = '${notifId}'`)).c, 0);
  assert.equal((await one(`select count(*)::int as c from public.home_stats_policies where created_by = '${victimId}' or updated_by = '${victimId}'`)).c, 0);

  // GCS media row deleted; active Supabase media row detached and expired for storage worker cleanup
  assert.equal((await one(`select count(*)::int as c from public.report_media_uploads where id = '${gcsMediaId}'`)).c, 0);
  const supaMedia = await one(`select user_id, campaign_id, report_id, status, quota_accounted_bytes from public.report_media_uploads where id = '${supaActiveMediaId}'`);
  assert.ok(supaMedia, 'Active Supabase media ledger holding quota must be retained for storage worker');
  assert.equal(supaMedia.user_id, null);
  assert.equal(supaMedia.campaign_id, null);
  assert.equal(supaMedia.report_id, null);
  assert.equal(supaMedia.status, 'expired');
  assert.equal(Number(supaMedia.quota_accounted_bytes), 4096);

  // Active user's qualifying test event in daily_test_events_journal is preserved!
  const activeJournalCount = (await one(`
    select count(*)::int as c
    from public.daily_test_events_journal
    where user_id = '${activeUserId}'
      and is_qualifying = true
  `)).c;
  assert.ok(activeJournalCount >= 1, 'Active user qualifying test journal entries must remain intact');
});

// ---------------------------------------------------------------------------
// 7. Google Re-Login & Manual Email/Password Re-Registration after Purge
// ---------------------------------------------------------------------------
await test('Re-login with Google creates a brand new account & profile; manual Email/Password account requires re-creating account', async () => {
  const googleOldId = 'a6000000-0000-4000-8000-000000000001';
  const googleNewId = 'a6000000-0000-4000-8000-000000000011';
  const googleEmail = 'google.user@gmail.com';

  const manualOldId = 'a6000000-0000-4000-8000-000000000002';
  const manualNewId = 'a6000000-0000-4000-8000-000000000022';
  const manualEmail = 'manual.user@example.com';

  // 1. Seed old Google account and old manual Email/Password account, both at 0 credits for 32 days
  await db.exec(`
    insert into auth.users (id, email, raw_user_meta_data) values
      ('${googleOldId}', '${googleEmail}', '{"full_name":"Old Google Name","avatar_url":"https://example.com/old.png"}'::jsonb),
      ('${manualOldId}', '${manualEmail}', '{"full_name":"Old Manual Name"}'::jsonb);

    delete from public.credit_logs where user_id in ('${googleOldId}', '${manualOldId}');

    update public.profiles
    set credits = 0,
        missed_streak = 5,
        daily_missed_streak = 5,
        zero_credits_since = clock_timestamp() - interval '32 days'
    where id in ('${googleOldId}', '${manualOldId}');
  `);

  // 2. Execute purge
  const purgeRes = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_zero_credit_accounts(30, false, 200) as r`)).r;
  });
  assert.equal(purgeRes.deleted, 2);

  // Verify neither old account exists in auth.users or public.profiles
  assert.equal((await one(`select count(*)::int as c from auth.users where email in ('${googleEmail}', '${manualEmail}')`)).c, 0);
  assert.equal((await one(`select count(*)::int as c from public.profiles where email in ('${googleEmail}', '${manualEmail}')`)).c, 0);

  // 3. Google user signs in again with Google OAuth -> GoTrue inserts new auth.users row with new UUID
  await db.exec(`
    insert into auth.users (id, email, raw_user_meta_data)
    values ('${googleNewId}', '${googleEmail}', '{"full_name":"Fresh Google User","picture":"https://example.com/new.png"}'::jsonb);
  `);

  const freshGoogleProfile = await one(`select * from public.profiles where id = '${googleNewId}'`);
  assert.ok(freshGoogleProfile, 'Fresh profile must be created automatically on Google sign-in');
  assert.equal(freshGoogleProfile.email, googleEmail);
  assert.equal(freshGoogleProfile.display_name, 'Fresh Google User');
  assert.equal(freshGoogleProfile.avatar_url, 'https://example.com/new.png');
  assert.ok(freshGoogleProfile.credits > 0, 'Fresh Google profile must receive welcome credits');
  assert.equal(freshGoogleProfile.zero_credits_since, null);
  assert.equal(freshGoogleProfile.missed_streak, 0);
  assert.equal(freshGoogleProfile.daily_missed_streak, 0);

  const googleWelcomeLog = await one(`select * from public.credit_logs where user_id = '${googleNewId}' and action = 'welcome'`);
  assert.ok(googleWelcomeLog, 'Fresh welcome credit_log must be created for Google user');

  // 4. Manual Email/Password user tries to look up existing account -> not found, must sign up again
  const manualLookupBeforeSignUp = await one(`select id from auth.users where email = '${manualEmail}'`);
  assert.equal(manualLookupBeforeSignUp, undefined, 'Manual user cannot log in before re-creating the account');

  // Manual user registers again with the same email
  await db.exec(`
    insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
    values ('${manualNewId}', '${manualEmail}', null, '{"full_name":"Re-created Manual User"}'::jsonb);
  `);

  const manualAuthUser = await one(`select id, email_confirmed_at from auth.users where id = '${manualNewId}'`);
  assert.ok(manualAuthUser.email_confirmed_at !== null, 'on_auth_user_created_auto_confirm must auto-confirm re-created manual account');

  const freshManualProfile = await one(`select * from public.profiles where id = '${manualNewId}'`);
  assert.ok(freshManualProfile, 'Fresh profile must be created when manual user registers again');
  assert.equal(freshManualProfile.email, manualEmail);
  assert.equal(freshManualProfile.display_name, 'Re-created Manual User');
  assert.ok(freshManualProfile.credits > 0, 'Re-created manual profile must receive welcome credits');
  assert.equal(freshManualProfile.zero_credits_since, null);
});

// ---------------------------------------------------------------------------
// 8. Dry-run mode, batch_size ordering, and sync_campaign_lifecycle() 24h fallback
// ---------------------------------------------------------------------------
await test('Dry-run mode, oldest-first batching, and sync_campaign_lifecycle() 24h fallback work accurately', async () => {
  const uOldest = 'a7000000-0000-4000-8000-000000000001';
  const uNewer = 'a7000000-0000-4000-8000-000000000002';

  await db.exec(`
    insert into auth.users (id, email) values
      ('${uOldest}', 'oldest@zero.test'),
      ('${uNewer}', 'newer@zero.test');

    delete from public.credit_logs where user_id in ('${uOldest}', '${uNewer}');

    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '40 days'
    where id = '${uOldest}';

    update public.profiles
    set credits = 0,
        zero_credits_since = clock_timestamp() - interval '33 days'
    where id = '${uNewer}';
  `);

  // 1. Dry-run scans both but deletes neither
  const dryRes = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_zero_credit_accounts(30, true, 10) as r`)).r;
  });
  assert.equal(dryRes.status, 'dry_run');
  assert.equal(dryRes.scanned, 2);
  assert.equal(dryRes.deleted, 0);
  assert.deepEqual(dryRes.candidate_user_ids, [uOldest, uNewer]);
  assert.equal((await one(`select count(*)::int as c from auth.users where id in ('${uOldest}', '${uNewer}')`)).c, 2);

  // 2. Batch size = 1 deletes oldest zero-credit account first
  const batch1 = await asRole('service_role', null, async () => {
    return (await one(`select public.purge_zero_credit_accounts(30, false, 1) as r`)).r;
  });
  assert.equal(batch1.scanned, 1);
  assert.equal(batch1.deleted, 1);
  assert.deepEqual(batch1.deleted_user_ids, [uOldest]);
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${uOldest}'`)).c, 0);
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${uNewer}'`)).c, 1);

  // 3. sync_campaign_lifecycle() 24h fallback:
  // Because batch1 just recorded a 'success' run in cleanup_runs (< 24h ago), calling sync_campaign_lifecycle() now will NOT re-run purge
  await asRole('authenticated', activeUserId, async () => {
    await one(`select public.sync_campaign_lifecycle()`);
  });
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${uNewer}'`)).c, 1);

  // Age all cleanup_runs for purge_zero_credit_accounts beyond 24h -> sync_campaign_lifecycle() fallback triggers and purges uNewer!
  await db.exec(`
    update public.cleanup_runs
    set started_at = clock_timestamp() - interval '25 hours',
        finished_at = clock_timestamp() - interval '25 hours'
    where job_name = 'purge_zero_credit_accounts';
  `);

  await asRole('authenticated', activeUserId, async () => {
    await one(`select public.sync_campaign_lifecycle()`);
  });
  assert.equal((await one(`select count(*)::int as c from auth.users where id = '${uNewer}'`)).c, 0);
});

await db.close();
console.log('=== ALL ZERO-CREDIT ACCOUNT AUTO-PURGE TESTS PASSED ===');
