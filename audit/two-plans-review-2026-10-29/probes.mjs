import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');
const read = p => readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8');
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const results = [];
const scalar = async sql => (await db.query(sql)).rows[0];
const owner = '11111111-1111-1111-1111-111111111111';
const other = '22222222-2222-2222-2222-222222222222';
const camp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const req = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
async function probe(name, fn) {
  await db.exec('begin');
  try {
    const evidence = await fn();
    results.push({ name, status: 'REPRODUCED', evidence });
    console.log(JSON.stringify(results.at(-1)));
  } catch (e) {
    results.push({ name, status: 'PROBE_ERROR', error: e.message });
    console.log(JSON.stringify(results.at(-1)));
  } finally { await db.exec('rollback'); }
}
try {
  await db.exec(`
    create role anon nologin; create role authenticated nologin; create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz, confirmation_token text, email_change text, email_change_token_new text, recovery_token text);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    grant usage on schema public, auth to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
  `);
  await db.exec(read('supabase/full_schema_singapore.sql'));
  await db.exec(`
    insert into auth.users(id,email) values ('${owner}','owner@review.local'),('${other}','other@review.local');
    insert into public.profiles(id,email,display_name,credits,role,created_at) values
      ('${owner}','owner@review.local','Owner',1000,'member',now()-interval '10 days'),
      ('${other}','other@review.local','Other',1000,'member',now()-interval '10 days')
    on conflict(id) do update set credits=1000, role='member', created_at=now()-interval '10 days';
    insert into public.campaigns(id,owner_id,app_name,package_name,status,target_testers,slot_number,created_at)
      values ('${camp}','${owner}','Review','review.owner','open',12,1,now()-interval '3 days');
    insert into public.campaigns(owner_id,app_name,package_name,status,target_testers,slot_number,created_at)
      values ('${other}','Supply','review.other','open',12,1,now()-interval '3 days');
    delete from public.daily_task_carryover_policies;
    select public.set_daily_task_carryover_policy((now() at time zone 'Asia/Ho_Chi_Minh')::date-5,true,'review');
  `);
  const { today } = await scalar(`select to_char((now() at time zone 'Asia/Ho_Chi_Minh')::date,'YYYY-MM-DD') today`);
  await probe('C01_finalizer_seals_pending_without_source', async () => {
    const before = await scalar(`select public.get_daily_task_status('${owner}',date '${today}'-1) s`);
    assert.equal(before.s.carryover_ready, false);
    await db.exec(`select public._apply_daily_inactivity_penalty(date '${today}'-1)`);
    const row = await scalar(`select carry_in_tests,carry_out_tests,met,penalty_credits,finalized_at is not null sealed
      from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-1`);
    assert.equal(row.sealed, true); assert.equal(row.carry_out_tests, 0);
    assert.ok(row.penalty_credits > 0);
    return { before: before.s, row };
  });
  await probe('C02_auto_pause_during_pending', async () => {
    await db.exec(`update public.profiles set daily_missed_streak=7 where id='${owner}'`);
    const status = await scalar(`select public.get_daily_task_status('${owner}',date '${today}') s`);
    assert.equal(status.s.carryover_ready, false);
    await db.exec('select public.pause_inactive_owner_campaigns()');
    const row = await scalar(`select status,paused_reason from public.campaigns where id='${camp}'`);
    assert.equal(row.status, 'paused'); return { ready: status.s.carryover_ready, row };
  });
  await probe('C03_resume_allowed_while_owner_pending', async () => {
    await db.exec(`update public.campaigns set status='paused',paused_reason='owner_inactive' where id='${camp}';
      update public.profiles set daily_missed_streak=0 where id='${owner}';
      set request.jwt.claim.sub='${owner}'`);
    const s = await scalar(`select public.get_daily_task_status('${owner}',date '${today}') s`);
    const r = await scalar(`select public.resume_campaign('${camp}') r`);
    assert.equal(s.s.carryover_ready, false); assert.equal(r.r.success, true);
    return { ready: s.s.carryover_ready, result: r.r };
  });
  await probe('C04_policy_reenable_discards_frozen_debt', async () => {
    await db.exec(`insert into public.user_daily_tasks(user_id,task_date,carryover_version,carry_out_tests,finalized_at)
      values('${owner}',date '${today}'-1,1,8,now());
      select public.set_daily_task_carryover_policy(date '${today}'-1,false,'disable');
      select public.set_daily_task_carryover_policy(date '${today}',true,'reenable')`);
    const r = await scalar(`select public.get_daily_task_status('${owner}',date '${today}') s`);
    assert.equal(r.s.carry_in_tests, 0); return { previous_debt: 8, status: r.s };
  });
  await probe('C05_past_day_recomputed_from_current_campaign_state', async () => {
    await db.exec(`insert into public.user_daily_tasks(user_id,task_date,carryover_version,carry_out_tests,finalized_at)
      values('${owner}',date '${today}'-3,1,2,now());
      insert into public.user_daily_tasks(user_id,task_date,logged_in)
      values('${owner}',date '${today}'-2,true)`);
    const before = await scalar(`select public.get_daily_task_status('${owner}',date '${today}'-2) s`);
    await db.exec(`update public.campaigns set status='completed' where id='${camp}'`);
    const after = await scalar(`select public.get_daily_task_status('${owner}',date '${today}'-2) s`);
    assert.equal(before.s.mode, 'owner'); assert.equal(after.s.mode, 'member');
    await db.exec(`select public.finalize_daily_tasks_for_date(date '${today}'-2)`);
    const sealed = await scalar(`select mode,met,penalty_credits,carry_in_tests,carry_out_tests
      from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-2`);
    assert.equal(sealed.met, true);
    return { same_past_date: today+' minus 2', before: before.s, after: after.s, sealed };
  });
  await probe('C06_policy_can_be_rewritten_retroactively', async () => {
    await db.exec(`select public.set_daily_task_carryover_policy(date '${today}'-5,false,'rewrite past')`);
    const r = await scalar(`select is_enabled from public.daily_task_carryover_policies where effective_from=date '${today}'-5`);
    assert.equal(r.is_enabled, false); return r;
  });
  await probe('P01_receipt_blocks_account_deletion', async () => {
    await db.exec(`set request.jwt.claim.sub='${owner}'; select public.push_campaign('${camp}','${req}');
      set request.jwt.claim.sub=''`);
    await db.exec('savepoint delete_test');
    let error;
    try { await db.exec(`delete from auth.users where id='${owner}'`); }
    catch(e) { error={code:e.code,message:e.message}; await db.exec('rollback to savepoint delete_test'); }
    assert.ok(['23503','23001'].includes(error?.code));
    assert.match(error.message,/campaign_push_receipts/); return error;
  });
  await probe('P02_deleted_campaign_receipt_cannot_replay', async () => {
    await db.exec(`set request.jwt.claim.sub='${owner}'; select public.push_campaign('${camp}','${req}');
      set request.jwt.claim.sub=''; delete from public.campaigns where id='${camp}';
      set request.jwt.claim.sub='${owner}'`);
    await db.exec('savepoint replay_test');
    let error;
    try { await db.exec(`select public.push_campaign('${camp}','${req}')`); }
    catch(e) { error={message:e.message}; await db.exec('rollback to savepoint replay_test'); }
    assert.match(error?.message??'',/REQUEST_ID_CONFLICT/); return error;
  });
  await probe('P03_authenticated_can_bypass_guard_with_custom_setting', async () => {
    // Explicit grants model the normal direct campaign-update capability. RLS remains enabled.
    await db.exec(`grant select,update on public.campaigns to authenticated;
      grant select on public.profiles to authenticated;
      set role authenticated; set request.jwt.claim.sub='${owner}';
      select set_config('app.allow_push_metadata_write','true',true);
      update public.campaigns set push_business_date=date '${today}',
        push_expires_at=((date '${today}'+1)::timestamp at time zone 'Asia/Ho_Chi_Minh'),push_order=999999 where id='${camp}';
      reset role; set request.jwt.claim.sub=''`);
    const r=await scalar(`select p.credits,c.push_order,(select count(*)::int from public.campaign_push_receipts) receipts
      from public.campaigns c join public.profiles p on p.id=c.owner_id where c.id='${camp}'`);
    assert.equal(r.credits,1000); assert.equal(r.receipts,0); assert.equal(Number(r.push_order),999999);
    return { sql_role_scope: 'authenticated SQL session; not a demonstrated PostgREST exploit', ...r };
  });
  await probe('C07_signup_existing_login_row_not_marked_exempt', async () => {
    await db.exec(`update public.profiles set created_at=(date '${today}'-1)::timestamp at time zone 'Asia/Ho_Chi_Minh' where id='${owner}';
      insert into public.user_daily_tasks(user_id,task_date,logged_in) values('${owner}',date '${today}'-1,true);
      select public._apply_daily_inactivity_penalty(date '${today}'-1)`);
    const r=await scalar(`select is_exempt,met,carryover_version,finalized_at is not null sealed
      from public.user_daily_tasks where user_id='${owner}' and task_date=date '${today}'-1`);
    assert.equal(r.sealed,true); assert.equal(r.is_exempt,false); assert.equal(r.met,false); return r;
  });
} finally {
  writeFileSync(fileURLToPath(new URL('results.json',import.meta.url)), JSON.stringify(results,null,2));
  await db.close();
}
if(results.some(r=>r.status==='PROBE_ERROR')) process.exitCode=1;
