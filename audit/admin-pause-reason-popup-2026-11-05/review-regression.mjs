import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read, quote } from '../../supabase/tools/baseline-lib.mjs';

// Independent acceptance probes; do not replace the implementation's own tests.
const db = await createDb({ legacyGrants: true });
const admin = 'f1111111-1111-4111-8111-111111111111';
const owner = 'f2222222-2222-4222-8222-222222222222';
const other = 'f3333333-3333-4333-8333-333333333333';
const locked = 'f4444444-4444-4444-8444-444444444444';
const app = 'faaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const appRollback = 'fbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const appName = 'Kiểm thử "Google" & nhóm – Ứng dụng';
const sql = async query => (await db.query(query)).rows;
async function actor(uid, query, role = 'authenticated') {
  await db.exec('begin');
  try {
    await db.exec(`select set_config('request.jwt.claim.sub',${quote(uid ?? '')},true); set local role ${role};`);
    const rows = await sql(query);
    await db.exec('commit');
    return rows;
  } catch (error) {
    await db.exec('rollback');
    throw error;
  }
}
const pause = (code, id = app, uid = admin) => actor(uid,
  `select public.admin_pause_campaign_with_reason('${id}',${quote(code)}) as r`);
let passed = 0;
async function check(name, fn) {
  await fn();
  passed++;
  console.log(`PASS independent ${passed}: ${name}`);
}
try {
  for (const file of readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(name => /^\d{3}_.*\.sql$/.test(name) && Number(name.slice(0, 3)) >= 62).sort()) {
    await db.exec(read('supabase/migrations/' + file));
  }
  await db.exec(`
    insert into auth.users(id,email) values
      ('${admin}','review-admin@fixture.invalid'),('${owner}','review-owner@fixture.invalid'),
      ('${other}','review-other@fixture.invalid'),('${locked}','review-locked@fixture.invalid');
    update public.profiles set role='admin',is_locked=false where id='${admin}';
    update public.profiles set role='admin',is_locked=true where id='${locked}';
    insert into public.campaigns(id,owner_id,app_name,package_name,status) values
      ('${app}','${owner}',${quote(appName)},'invalid.review.pause','open'),
      ('${appRollback}','${other}','Rollback','invalid.review.rollback','open');
  `);
  await check('invalid/null code and non-admin/locked/anonymous calls cannot pause', async () => {
    for (const code of ['', 'custom', 'unknown']) await assert.rejects(() => pause(code));
    await assert.rejects(() => actor(admin, `select public.admin_pause_campaign_with_reason('${app}',null)`));
    await assert.rejects(() => pause('missing_google_group', app, owner));
    await assert.rejects(() => pause('missing_google_group', app, locked));
    await assert.rejects(() => actor(null, `select public.admin_pause_campaign_with_reason('${app}','policy_violation')`, 'anon'));
    assert.equal((await sql(`select status from public.campaigns where id='${app}'`))[0].status, 'open');
  });
  let first;
  await check('Google Group body uses authoritative app name and one shared popup notification', async () => {
    await pause('missing_google_group');
    const rows = await sql(`select n.*,s.display_count from public.notifications n
      join public.notification_popup_states s on s.notification_id=n.id where n.data->>'campaign_id'='${app}'`);
    assert.equal(rows.length, 1);
    first = rows[0];
    assert.equal(first.user_id, owner);
    assert.equal(first.type, 'campaign');
    assert.equal(first.display_count, 0);
    assert.equal(first.data.reason, 'admin_pause');
    assert.equal(first.data.reason_code, 'missing_google_group');
    assert.equal(first.data.sender, 'admin');
    assert.equal(first.data.broadcast, false);
    assert.equal(first.message, `Ứng dụng "${appName}" của bạn đã bị tạm dừng do chưa thêm Google Group của nhóm khiến các Tester không tải được ứng dụng của bạn để test. Vui lòng thêm Google Group của nhóm (theo hướng dẫn trong app) để tiếp tục tham gia Test.`);
  });
  await check('owner cannot forge reason, reopen admin pause, or read another account popup', async () => {
    await assert.rejects(() => actor(owner, `update public.campaigns set admin_pause_reason_code='policy_violation' where id='${app}' returning id`));
    await assert.rejects(() => actor(owner, `select public.resume_campaign('${app}')`));
    const claim = (await actor(other, `select public.claim_my_notification_popup('f5555555-5555-4555-8555-555555555555') as r`))[0].r;
    assert.equal(claim.status, 'none');
    const status = (await actor(other, `select public.get_my_notification_status('${first.id}') as r`))[0].r;
    assert.equal(status.exists, false);
  });
  await check('retry does not duplicate before or after notification deletion', async () => {
    await pause('missing_google_group');
    assert.equal((await sql(`select count(*)::int as n from public.notifications where data->>'campaign_id'='${app}'`))[0].n, 1);
    await actor(owner, `delete from public.notifications where id='${first.id}' returning id`);
    await pause('missing_google_group');
    assert.equal((await sql(`select count(*)::int as n from public.notifications where data->>'campaign_id'='${app}'`))[0].n, 0);
  });
  await check('changing reason creates policy body and only one more audit event', async () => {
    const before = (await sql(`select count(*)::int as n from public.admin_action_events where action='pause_campaign' and target_id='${app}'`))[0].n;
    await pause('policy_violation');
    const rows = await sql(`select n.*,s.display_count from public.notifications n join public.notification_popup_states s on s.notification_id=n.id where n.data->>'campaign_id'='${app}'`);
    assert.equal(rows.length, 1);
    assert.equal(rows[0].message, 'Ứng dụng của bạn đã bị tạm dừng do vi phạm chính sách, vui lòng liên hệ Admin để được mở lại Test.');
    assert.equal(rows[0].data.reason_code, 'policy_violation');
    assert.equal((await sql(`select count(*)::int as n from public.admin_action_events where action='pause_campaign' and target_id='${app}'`))[0].n, before + 1);
  });
  await check('legacy custom reason equal to label stays distinct and does not create popup', async () => {
    const label = (await sql(`select admin_note from public.campaigns where id='${app}'`))[0].admin_note;
    await actor(admin, `select public.admin_pause_campaign('${app}',${quote(label)})`);
    assert.equal((await sql(`select admin_pause_reason_code from public.campaigns where id='${app}'`))[0].admin_pause_reason_code, null);
    const rows = await sql(`select n.id,s.notification_id from public.notifications n left join public.notification_popup_states s on s.notification_id=n.id where n.data->>'campaign_id'='${app}'`);
    assert.equal(rows.length, 2);
    assert.equal(rows.filter(row => row.notification_id !== null).length, 1);
    await pause('policy_violation');
    assert.equal((await sql(`select count(*)::int as n from public.notifications n join public.notification_popup_states s on s.notification_id=n.id where n.data->>'campaign_id'='${app}'`))[0].n, 2);
  });
  await check('reopen clears structured reason without deleting historical notifications', async () => {
    await actor(admin, `select public.admin_resume_campaign('${app}')`);
    const state = (await sql(`select status,paused_reason,admin_note,admin_pause_reason_code from public.campaigns where id='${app}'`))[0];
    assert.deepEqual(state, { status: 'open', paused_reason: null, admin_note: null, admin_pause_reason_code: null });
    assert.equal((await sql(`select count(*)::int as n from public.notifications n join public.notification_popup_states s on s.notification_id=n.id where n.data->>'campaign_id'='${app}'`))[0].n, 2);
  });
  await check('campaign-type popup uses the existing queue, five-claim limit, replay and acknowledgment', async () => {
    let oldest;
    for (let index = 1; index <= 5; index++) {
      const session = `f6666666-6666-4666-8666-${String(index).padStart(12, '0')}`;
      const result = (await actor(owner, `select public.claim_my_notification_popup('${session}') as r`))[0].r;
      assert.equal(result.status, 'claimed');
      oldest ??= result.notification.id;
      assert.equal(result.notification.id, oldest);
      assert.equal(result.display_count, index);
      const replay = (await actor(owner, `select public.claim_my_notification_popup('${session}') as r`))[0].r;
      assert.equal(replay.notification.id, oldest);
      assert.equal(replay.display_count, index);
    }
    const sixth = (await actor(owner, `select public.claim_my_notification_popup('f6666666-6666-4666-8666-000000000006') as r`))[0].r;
    assert.equal(sixth.status, 'claimed');
    assert.notEqual(sixth.notification.id, oldest);
    assert.equal(sixth.display_count, 1);
    await actor(owner, `select public.acknowledge_my_notification('${sixth.notification.id}')`);
    const replay = (await actor(owner, `select public.claim_my_notification_popup('f6666666-6666-4666-8666-000000000006') as r`))[0].r;
    assert.equal(replay.status, 'stopped');
    assert.equal(replay.reason, 'read');
    assert.equal((await sql(`select display_count from public.notification_popup_states where notification_id='${oldest}'`))[0].display_count, 5);
  });
  await check('audit insert failure rolls back campaign, notification, and popup together', async () => {
    await db.exec(`create function public.review_fail_pause_audit() returns trigger language plpgsql as $$ begin
      if new.action='pause_campaign' then raise exception 'REVIEW_AUDIT_FAILURE'; end if; return new; end $$;
      create trigger review_fail_pause_audit before insert on public.admin_action_events for each row execute function public.review_fail_pause_audit();`);
    await assert.rejects(() => pause('missing_google_group', appRollback), /REVIEW_AUDIT_FAILURE/);
    assert.equal((await sql(`select status from public.campaigns where id='${appRollback}'`))[0].status, 'open');
    assert.equal((await sql(`select count(*)::int as n from public.notifications where data->>'campaign_id'='${appRollback}'`))[0].n, 0);
    await db.exec('drop trigger review_fail_pause_audit on public.admin_action_events; drop function public.review_fail_pause_audit();');
  });
  await check('trusted lifecycle reopening clears reason metadata alongside the existing admin note', async () => {
    await db.exec('begin');
    try {
      await db.exec(`select set_config('request.jwt.claim.sub','${admin}',true);
        select public.admin_pause_campaign_with_reason('${app}','policy_violation');
        select set_config('request.jwt.claim.sub','',true);
        update public.campaigns set status='open' where id='${app}';`);
      const state = (await sql(`select status,paused_reason,admin_note,admin_pause_reason_code from public.campaigns where id='${app}'`))[0];
      assert.deepEqual(state, { status: 'open', paused_reason: null, admin_note: null, admin_pause_reason_code: null });
    } finally {
      await db.exec('rollback');
    }
  });
  await check('owner resume still reads the configured missed-day threshold instead of a fixed value', async () => {
    // Fix only the daily refresh dependency, so the preselected missed streak
    // remains deterministic; exercise the real resume RPC and config table.
    await db.exec(`create or replace function public.refresh_daily_completion(p_user_id uuid,p_date date default null)
      returns void language plpgsql security definer set search_path to public as $$ begin return; end $$;
      update public.owner_auto_pause_configs set missed_days=7 where id='default';
      update public.profiles set daily_missed_streak=4 where id='${other}';
      update public.campaigns set status='paused',paused_reason='owner_manual' where id='${appRollback}';`);
    const result = (await actor(other, `select public.resume_campaign('${appRollback}') as r`))[0].r;
    assert.equal(result.status, 'open', 'streak 4 must be allowed when the configured threshold is 7');
    await db.exec(`update public.owner_auto_pause_configs set missed_days=1 where id='default';
      update public.profiles set daily_missed_streak=1 where id='${other}';
      update public.campaigns set status='paused',paused_reason='owner_manual' where id='${appRollback}';`);
    await assert.rejects(() => actor(other, `select public.resume_campaign('${appRollback}')`), /OWNER_INACTIVE/);
  });
  console.log(`INDEPENDENT REVIEW PASSED: ${passed} behavioral groups.`);
} finally {
  await db.close();
}
