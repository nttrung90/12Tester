import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');

const read = p => readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8');
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const scalar = async sql => (await db.query(sql)).rows[0];

const owner = '11111111-1111-1111-1111-111111111111';
const other = '22222222-2222-2222-2222-222222222222';
const camp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const req = 'cccccccc-cccc-cccc-cccc-cccccccccccc';

async function runTest(name, fn) {
  await db.exec('begin');
  try {
    await fn();
    console.log(`PASS: ${name}`);
  } catch (e) {
    console.error(`FAIL: ${name}`, e);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

try {
  console.log('=== KHỞI TẠO PGLITE VÀ NẠP FULL SCHEMA SINGAPORE (GỒM 052, 053, 054) ===');
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users(
      id uuid primary key,
      email text,
      raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz,
      confirmation_token text,
      email_change text,
      email_change_token_new text,
      recovery_token text
    );
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    grant usage on schema public, auth to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
  `);

  await db.exec(read('supabase/full_schema_singapore.sql'));

  await db.exec(`
    insert into auth.users(id, email) values ('${owner}','owner@test.local'),('${other}','other@test.local');
    insert into public.profiles(id, email, display_name, credits, role, created_at) values
      ('${owner}','owner@test.local','Owner',1000,'member',now()-interval '10 days'),
      ('${other}','other@test.local','Other',1000,'member',now()-interval '10 days')
    on conflict(id) do update set credits=1000, role='member', created_at=now()-interval '10 days';
    insert into public.campaigns(id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values ('${camp}','${owner}','Review','review.owner','open',12,1,now()-interval '3 days');
    insert into public.campaigns(owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values ('${other}','Supply','review.other','open',12,1,now()-interval '3 days');
    delete from public.daily_task_carryover_policies;
    set test.allow_policy_retroactive_write = 'true';
    select public.set_daily_task_carryover_policy((now() at time zone 'Asia/Ho_Chi_Minh')::date-5, true, 'test init');
    reset test.allow_policy_retroactive_write;
  `);

  const { today } = await scalar(`select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date,'YYYY-MM-DD') today`);


  const results = [];
  for (const authenticated of [false, true]) {
    await db.exec('begin');
    const before = await scalar(`select credits from public.profiles where id='${owner}'`);
    await db.exec(`set local request.jwt.claim.sub='${owner}';`);
    if (authenticated) await db.exec('set local role authenticated');
    const role = await scalar("select current_user, session_user, current_setting('role', true) active_role");
    await db.exec('savepoint purchase');
    let outcome;
    try {
      const row = await scalar(`select public.push_campaign('${camp}', '${req}') result`);
      outcome = { success: row.result.success, replayed: row.result.replayed };
    } catch (error) {
      outcome = { success: false, code: error.code, message: error.message };
      await db.exec('rollback to savepoint purchase');
    }
    await db.exec('reset role');
    const after = await scalar(`select credits,
      (select count(*)::int from public.campaign_push_receipts where client_request_id='${req}') receipt_count,
      (select count(*)::int from public.credit_logs where user_id='${owner}' and action='campaign_push') log_count,
      (select push_order from public.campaigns where id='${camp}') push_order
      from public.profiles where id='${owner}'`);
    results.push({ authenticated, role, before, outcome, after });
    await db.exec('rollback');
  }
  console.log(JSON.stringify(results, null, 2));
  writeFileSync(new URL('./results.json', import.meta.url), JSON.stringify(results, null, 2));
  assert.equal(results[0].outcome.success, true, 'Privileged fixture baseline should succeed');
  assert.equal(results[1].outcome.success, false, 'Current authenticated RPC should reproduce rejection');
  assert.match(results[1].outcome.message, /PUSH_METADATA_READ_ONLY/);
  assert.equal(results[1].after.credits, results[1].before.credits, 'Rejected transaction must roll back money');
  assert.equal(results[1].after.receipt_count, 0);
  assert.equal(results[1].after.log_count, 0);
  assert.equal(results[1].after.push_order, null);
  console.log('REPRODUCED: authenticated RPC is rejected; money/log/receipt/metadata rolled back.');
} finally {
  await db.close();
}
