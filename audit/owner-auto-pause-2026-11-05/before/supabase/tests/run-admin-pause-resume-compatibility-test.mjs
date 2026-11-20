import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read, quote } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING ADMIN PAUSE / RESUME COMPATIBILITY REGRESSION TEST ===');

const db = await createDb({ legacyGrants: true });

async function asActor(userId, sql, role = 'authenticated') {
  if (userId) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${userId}', true);`);
  } else {
    await db.exec(`select set_config('request.jwt.claim.sub', '', true);`);
  }
  await db.exec(`set role ${role}; savepoint sp_actor;`);
  try {
    const res = await db.query(sql);
    await db.exec('release savepoint sp_actor;');
    return res;
  } catch (err) {
    await db.exec('rollback to savepoint sp_actor;');
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
    console.error(`FAIL: ${name}`, e.message);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

// 1. Load active migration chain from 062 through 078
const migrationFiles = readdirSync(new URL('../migrations/', import.meta.url))
  .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62)
  .sort();

console.log('=== LOADING MIGRATIONS 062 - 078 ===');
for (const name of migrationFiles) {
  await db.exec(read('supabase/migrations/' + name));
}

// 2. Setup baseline test users
const adminUid = 'c1111111-1111-4111-8111-111111111111';
const ownerUid = 'c2222222-2222-4222-8222-222222222222';
const appUid = 'caaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const expiredAppUid = 'cbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

await db.exec(`
  insert into auth.users (id, email) values
    ('${adminUid}', 'admin-compat@test.invalid'),
    ('${ownerUid}', 'owner-compat@test.invalid')
  on conflict (id) do nothing;

  update public.profiles set role = 'admin', is_locked = false where id = '${adminUid}';
  update public.profiles set role = 'member', is_locked = false where id = '${ownerUid}';

  -- Stub refresh_daily_completion to keep missed streaks deterministic in tests
  create or replace function public.refresh_daily_completion(p_user_id uuid, p_date date default null)
  returns void language plpgsql security definer set search_path to public as $$
  begin
    return;
  end;
  $$;
`);

// ----------------------------------------------------------------------------
// Test 1: Finding 1 - Owner resume threshold reads owner_auto_pause_configs with fallback 7
// ----------------------------------------------------------------------------
await test('Owner resume respects configured missed_days threshold (7 allows streak 4, 1 rejects streak 1)', async () => {
  await db.exec(`
    update public.owner_auto_pause_configs set missed_days = 7 where id = 'default';
    update public.profiles set daily_missed_streak = 4 where id = '${ownerUid}';
    insert into public.campaigns (id, owner_id, app_name, package_name, status, paused_reason, admin_resumed)
    values ('${appUid}', '${ownerUid}', 'Compat App', 'com.compat.app', 'paused', 'owner_manual', false)
    on conflict (id) do update set status = 'paused', paused_reason = 'owner_manual', admin_resumed = false;
  `);

  // Streak 4 < threshold 7: resume must succeed
  const res1 = (await asActor(ownerUid, `select public.resume_campaign('${appUid}') as r;`)).rows[0].r;
  assert.equal(res1.status, 'open');

  // Change config to threshold 1: streak 1 must be rejected
  await db.exec(`
    update public.owner_auto_pause_configs set missed_days = 1 where id = 'default';
    update public.profiles set daily_missed_streak = 1 where id = '${ownerUid}';
    update public.campaigns set status = 'paused', paused_reason = 'owner_manual' where id = '${appUid}';
  `);
  await assert.rejects(
    async () => asActor(ownerUid, `select public.resume_campaign('${appUid}') as r;`),
    /OWNER_INACTIVE/
  );
});

// ----------------------------------------------------------------------------
// Test 2: Finding 2 - Admin resume return JSON contract includes paused_reason=null and admin_resumed
// ----------------------------------------------------------------------------
await test('Admin resume returns paused_reason=null and admin_resumed flag', async () => {
  // A: Resuming owner_inactive -> admin_resumed must be true
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status, paused_reason)
    values ('${appUid}', '${ownerUid}', 'Compat App', 'com.compat.app', 'paused', 'owner_inactive')
    on conflict (id) do update set status = 'paused', paused_reason = 'owner_inactive';
  `);

  const resInactive = (await asActor(adminUid, `select public.admin_resume_campaign('${appUid}') as r;`)).rows[0].r;
  assert.equal(resInactive.success, true);
  assert.equal(resInactive.status, 'open');
  assert.equal(resInactive.paused_reason, null);
  assert.equal(resInactive.admin_resumed, true);

  // B: Resuming admin_manual -> admin_resumed must be false
  await db.exec(`
    update public.campaigns set status = 'paused', paused_reason = 'admin_manual', admin_pause_reason_code = 'policy_violation'
    where id = '${appUid}';
  `);

  const resManual = (await asActor(adminUid, `select public.admin_resume_campaign('${appUid}') as r;`)).rows[0].r;
  assert.equal(resManual.success, true);
  assert.equal(resManual.status, 'open');
  assert.equal(resManual.paused_reason, null);
  assert.equal(resManual.admin_resumed, false);

  // C: Resuming 30-day expired campaign -> completed branch contract preserved
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status, paused_reason, created_at)
    values ('${expiredAppUid}', '${ownerUid}', 'Expired App', 'com.expired.app', 'paused', 'admin_manual', now() - interval '31 days')
    on conflict (id) do update set status = 'paused', paused_reason = 'admin_manual', created_at = now() - interval '31 days';
  `);

  const resExpired = (await asActor(adminUid, `select public.admin_resume_campaign('${expiredAppUid}') as r;`)).rows[0].r;
  assert.equal(resExpired.success, true);
  assert.equal(resExpired.status, 'completed');
  assert.equal(resExpired.campaign_id, expiredAppUid);
});

// ----------------------------------------------------------------------------
// Test 3: Finding 3 - Admin resume records original audit action reason
// ----------------------------------------------------------------------------
await test('Admin resume audit event records original reason Khôi phục chiến dịch bởi Admin', async () => {
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status, paused_reason)
    values ('${appUid}', '${ownerUid}', 'Compat App', 'com.compat.app', 'paused', 'admin_manual')
    on conflict (id) do update set status = 'paused', paused_reason = 'admin_manual';
  `);

  await asActor(adminUid, `select public.admin_resume_campaign('${appUid}');`);

  const event = (await db.query(`
    select action, target_id, reason, old_state, new_state
    from public.admin_action_events
    where action = 'resume_campaign' and target_id = '${appUid}'
    order by created_at desc limit 1;
  `)).rows[0];

  assert.ok(event, 'Audit event must exist');
  assert.equal(event.action, 'resume_campaign');
  assert.equal(event.reason, 'Khôi phục chiến dịch bởi Admin');
  assert.equal(event.old_state.status, 'paused');
  assert.equal(event.old_state.paused_reason, 'admin_manual');
  assert.equal(event.new_state.status, 'open');
  assert.equal(event.new_state.paused_reason, null);
});

// ----------------------------------------------------------------------------
// Test 4: Finding 4 - Lifecycle trigger clears admin_pause_reason_code on reopen
// ----------------------------------------------------------------------------
await test('trg_campaign_status_lifecycle clears admin_pause_reason_code on reopen and guards client tamper', async () => {
  // A: Paused with policy violation -> trusted update status='open' clears metadata
  await db.exec(`
    insert into public.campaigns (id, owner_id, app_name, package_name, status, paused_reason, admin_pause_reason_code, admin_note)
    values ('${appUid}', '${ownerUid}', 'Compat App', 'com.compat.app', 'paused', 'admin_manual', 'policy_violation', 'Vi phạm')
    on conflict (id) do update set status = 'paused', paused_reason = 'admin_manual', admin_pause_reason_code = 'policy_violation', admin_note = 'Vi phạm';
    update public.campaigns set status = 'open' where id = '${appUid}';
  `);

  const state = (await db.query(`
    select status, paused_reason, admin_note, admin_pause_reason_code
    from public.campaigns where id = '${appUid}';
  `)).rows[0];

  assert.deepEqual(state, {
    status: 'open',
    paused_reason: null,
    admin_note: null,
    admin_pause_reason_code: null
  });

  // B: Direct client insert with admin_pause_reason_code is rejected
  await assert.rejects(
    async () => asActor(ownerUid, `
      insert into public.campaigns (id, owner_id, app_name, package_name, admin_pause_reason_code)
      values (gen_random_uuid(), '${ownerUid}', 'Tampered', 'com.tamper.app', 'policy_violation');
    `),
    /PERMISSION_DENIED/
  );

  // C: Direct client update of admin_pause_reason_code is rejected
  await assert.rejects(
    async () => asActor(ownerUid, `
      update public.campaigns set admin_pause_reason_code = 'missing_google_group' where id = '${appUid}';
    `),
    /PERMISSION_DENIED/
  );
});

console.log('=== ALL COMPATIBILITY REGRESSION TESTS PASSED SUCCESSFULLY ===');
await db.close();
