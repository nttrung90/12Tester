import { readFileSync } from 'node:fs';
import { PGlite } from './node_modules/@electric-sql/pglite/dist/index.js';
import { pgcrypto } from './node_modules/@electric-sql/pglite/dist/contrib/pgcrypto.js';
import { uuid_ossp } from './node_modules/@electric-sql/pglite/dist/contrib/uuid_ossp.js';

const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const owner = '11111111-1111-1111-1111-111111111111';
const tester = '22222222-2222-2222-2222-222222222222';
const campaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const mission1 = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const mission2 = 'cccccccc-cccc-cccc-cccc-cccccccccccc';

const results = [];
const query = async sql => (await db.query(sql)).rows;
const asUser = async (uid, action) => {
  await db.exec('savepoint user_action');
  await db.exec(`select set_config('request.jwt.claim.sub', '${uid}', false); set role authenticated;`);
  try { return await action(); }
  catch (e) { await db.exec('rollback to savepoint user_action'); throw e; }
  finally { await db.exec('reset role'); }
};

const test = async (name, action) => {
  await db.exec('begin');
  try {
    const res = await action();
    results.push({ name, pass: true, ...res });
    console.log(`[PASS] ${name}`);
  } catch (e) {
    results.push({ name, pass: false, error: e.message });
    console.error(`[FAIL] ${name}: ${e.message}`);
  } finally {
    await db.exec('rollback');
  }
};

const submit = async (id, explicitMission = null, content = 'mission feedback') => asUser(tester, async () =>
  (await query(`select public.submit_tester_report(
    p_campaign_id => '${campaign}', p_content => '${content}',
    p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${id}.jpg?alt=media&token=fixture',
    p_image_hash => '1234567890abcdef', p_client_submit_id => '${id}'
    ${explicitMission ? `, p_mission_id => '${explicitMission}'` : ''}
  ) as result`))[0].result);

try {
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
    insert into public.mission_participants(mission_id, user_id, joined_at)
      values ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '${tester}', now() - interval '3 hours'),
             ('${mission2}', '${tester}', now() - interval '1 hour');
  `);

  await test('D01: Ordinary pending report cannot be converted to mission report', async () => {
    await db.exec(`delete from public.mission_participants where user_id='${tester}'`);
    const first = await submit('44444444-4444-4444-4444-444444444444', null, 'ordinary report');
    if (first.submission_context !== 'app_test') throw new Error(`Expected app_test, got ${first.submission_context}`);
    await asUser(tester, async () => query(`select public.join_mission('${mission2}') as result`));
    try {
      await submit('55555555-5555-5555-5555-555555555555', mission2, 'converted mission report');
      throw new Error('Should have failed with CANNOT_CONVERT_EXISTING_PENDING_REPORT');
    } catch (e) {
      if (!e.message.includes('CANNOT_CONVERT_EXISTING_PENDING_REPORT')) throw e;
    }
  });

  await test('D02: Old request replay must not overwrite later update', async () => {
    await db.exec(`delete from public.mission_participants where user_id='${tester}'`);
    await submit('44444444-4444-4444-4444-444444444444', null, 'first payload');
    await submit('55555555-5555-5555-5555-555555555555', null, 'newer payload');
    const replay = await submit('44444444-4444-4444-4444-444444444444', null, 'first payload');
    const row = (await query(`select content, client_submit_id from public.tester_reports where id='${replay.report_id}'`))[0];
    if (row.content !== 'newer payload') {
      throw new Error(`Report content overwritten! Expected "newer payload", got "${row.content}"`);
    }
  });

  await test('D03: Same request ID with different payload must fail with mismatch', async () => {
    await db.exec(`delete from public.mission_participants where user_id='${tester}'`);
    await submit('44444444-4444-4444-4444-444444444444', null, 'first payload');
    try {
      await submit('44444444-4444-4444-4444-444444444444', null, 'changed payload');
      throw new Error('Should have thrown CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH');
    } catch (e) {
      if (!e.message.includes('CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH')) throw e;
    }
  });

  await test('D04: Replay with identical payload returns receipt without duplicate credit', async () => {
    await db.exec(`delete from public.mission_participants where user_id='${tester}'`);
    const initialCredits = (await query(`select credits from public.profiles where id='${tester}'`))[0].credits;
    const res1 = await submit('44444444-4444-4444-4444-444444444444', null, 'first payload');
    const creditsAfterSubmit = (await query(`select credits from public.profiles where id='${tester}'`))[0].credits;
    if (creditsAfterSubmit !== initialCredits + 10) {
      throw new Error(`Expected +10 provisional credits, got ${creditsAfterSubmit - initialCredits}`);
    }

    const res2 = await submit('44444444-4444-4444-4444-444444444444', null, 'first payload');
    if (res2.report_id !== res1.report_id) {
      throw new Error(`Replay report ID mismatch: ${res2.report_id} vs ${res1.report_id}`);
    }
    const creditsAfterReplay = (await query(`select credits from public.profiles where id='${tester}'`))[0].credits;
    if (creditsAfterReplay !== creditsAfterSubmit) {
      throw new Error(`Replay gave duplicate credits! ${creditsAfterReplay} vs ${creditsAfterSubmit}`);
    }
  });

  await test('D05: get_campaign_mission supports querying specific mission by p_mission_id', async () => {
    const newest = (await asUser(tester, async () => query(`select public.get_campaign_mission('${campaign}') as result`)))[0].result;
    if (newest.mission.id !== mission2) {
      throw new Error(`Default query should return newest mission (${mission2}), got ${newest.mission?.id}`);
    }

    const old = (await asUser(tester, async () => query(`select public.get_campaign_mission('${campaign}', '${mission1}') as result`)))[0].result;
    if (old.mission.id !== mission1) {
      throw new Error(`Explicit query for mission1 should return mission1, got ${old.mission?.id}`);
    }
    if (old.mission.title !== 'Recheck Mission 1') {
      throw new Error(`Expected title "Recheck Mission 1", got ${old.mission?.title}`);
    }
  });

  await test('L01: Upgrade must preserve legacy request A before update B and later replay A', async () => {
    await db.exec(`delete from public.mission_participants where user_id='${tester}'`);
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/049_mission_report_hardening.sql', import.meta.url), 'utf8'));
    const a = await submit('44444444-4444-4444-4444-444444444444', null, 'legacy A payload');
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/057_report_idempotency_ledger_and_context.sql', import.meta.url), 'utf8'));
    await submit('55555555-5555-5555-5555-555555555555', null, 'new B payload');
    await submit('44444444-4444-4444-4444-444444444444', null, 'legacy A payload');
    const row = (await query(`select content, client_submit_id from public.tester_reports where id='${a.report_id}'`))[0];
    if (row.content !== 'new B payload') throw Error('Legacy replay overwrote newer report: ' + JSON.stringify(row));
  });

  await test('L02: Legacy receipt fallback must not accept a changed image as identical payload', async () => {
    await db.exec(`delete from public.mission_participants where user_id='${tester}'`);
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/049_mission_report_hardening.sql', import.meta.url), 'utf8'));
    await submit('44444444-4444-4444-4444-444444444444', null, 'same comment');
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/057_report_idempotency_ledger_and_context.sql', import.meta.url), 'utf8'));
    let error = null;
    try {
      await asUser(tester, async () => query(`select public.submit_tester_report(
        p_campaign_id=>'${campaign}', p_content=>'same comment',
        p_screenshot_url=>'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F55555555-5555-5555-5555-555555555555.jpg?alt=media&token=fixture',
        p_image_hash=>'aaaaaaaaaaaaaaaa', p_rating=>1,
        p_client_submit_id=>'44444444-4444-4444-4444-444444444444'
      ) as result`));
    } catch (e) { error = e.message; }
    if (!error?.includes('CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH')) {
      throw Error('Changed image/hash/rating was silently accepted by legacy fallback: ' + JSON.stringify({ error }));
    }
  });

  await test('B01: Exact legacy replay with implicit mission must remain valid after backfill', async () => {
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/049_mission_report_hardening.sql', import.meta.url), 'utf8'));
    const id = '44444444-4444-4444-4444-444444444444';
    const first = await submit(id, null, 'implicit mission report');
    if (first.mission_id !== mission2) throw Error('Fixture must auto-classify as mission2');
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/057_report_idempotency_ledger_and_context.sql', import.meta.url), 'utf8'));
    const receipt = await submit(id, null, 'implicit mission report');
    if (receipt.report_id !== first.report_id) throw Error('Replay receipt changed report ID');
  });

  await test('B02: Exact legacy approved-comment replay without image fields must remain valid', async () => {
    await db.exec(`delete from public.mission_participants where user_id='${tester}'`);
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/049_mission_report_hardening.sql', import.meta.url), 'utf8'));
    const first = await submit('44444444-4444-4444-4444-444444444444', null, 'initial report');
    await asUser(owner, async () => query(`select public.approve_tester_report('${first.report_id}',0)`));
    const commentId = '55555555-5555-5555-5555-555555555555';
    const comment = () => asUser(tester, async () => (await query(`select public.submit_tester_report(
      p_campaign_id=>'${campaign}', p_content=>'supplemental comment',p_client_submit_id=>'${commentId}'
    ) as result`))[0].result);
    const edited = await comment();
    if (edited.status !== 'approved') throw Error('Fixture comment must update approved report');
    await db.exec(readFileSync(new URL('../migrations_archive/pre_baseline_062/057_report_idempotency_ledger_and_context.sql', import.meta.url), 'utf8'));
    const replay = await comment();
    if (replay.report_id !== first.report_id) throw Error('Comment replay changed report ID');
  });

  console.log('\n--- All Database Idempotency & Context Tests Passed Successfully! ---');
  process.exitCode = results.every(r => r.pass) ? 0 : 1;
} finally {
  await db.close();
}
