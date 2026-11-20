// Acceptance test of owner auto-pause, creation timestamp protection, chronological finalization,
// and carryover debt gate across all migrations (062-079) in a disposable local database.
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'a1000000-0000-0000-0000-000000000001';
const admin = 'a1000000-0000-0000-0000-000000000002';
const supplier = 'a1000000-0000-0000-0000-000000000003';
const app1 = 'b1000000-0000-0000-0000-000000000001';
const app2 = 'b1000000-0000-0000-0000-000000000002';
const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;
const scalar = async sql => (await db.query(sql)).rows[0];
async function asActor(userId, sql) {
  await db.exec('savepoint actor_call');
  await db.exec(`set request.jwt.claim.sub = '${userId}'; set role authenticated;`);
  try {
    const result = await db.query(sql);
    await db.exec(`reset role; reset request.jwt.claim.sub; release savepoint actor_call;`);
    return result;
  } catch (error) {
    await db.exec('rollback to savepoint actor_call; release savepoint actor_call;');
    throw error;
  }
}
const asOwner = sql => asActor(owner,sql);
async function setThreshold(n) {
  const before=(await asActor(admin,'select public.admin_get_owner_auto_pause_config() r')).rows[0].r;
  const result=(await asActor(admin,`select public.admin_set_owner_auto_pause_config(${n},'${before.revision}') r`)).rows[0].r;
  assert.equal(result.success,true);
  assert.equal(result.missed_days,n);
  const after=(await asActor(admin,'select public.admin_get_owner_auto_pause_config() r')).rows[0].r;
  assert.equal(after.missed_days,n);
  assert.equal(after.revision,result.revision);
  return {before,result,after};
}
async function scenario(name, fn) {
  await db.exec('begin');
  try { await fn(); console.log(`PASS: ${name}`); }
  finally { await db.exec('rollback'); }
}
async function checkpoint(offset = 1, streak = 0) {
  await db.exec(`insert into public.user_daily_tasks
    (user_id,task_date,mode,finalized_at,missed_streak,carry_out_tests,met)
    values ('${owner}', ${today}-${offset}, 'owner', now(), ${streak}, 0, false);
    update public.profiles set daily_missed_streak=${streak} where id='${owner}';`);
}
async function qualifyingTest(count = 1, offset = 0) {
  // Historical fixture represents qualifying events on distinct supply apps observed on that day.
  await db.exec(`insert into public.daily_test_events_journal
    (source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',id,owner_id,${today}-${offset},'valid',true,
      (${today}-${offset})::timestamp at time zone 'Asia/Ho_Chi_Minh'
    from public.campaigns where owner_id <> '${owner}' and status='open'
    order by id limit ${count};`);
}
try {
  const files = readdirSync(new URL('../migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x)).sort();
  for (const file of files) await db.exec(read(`supabase/migrations/${file}`));
  console.log(`Loaded ${files[0]} through ${files.at(-1)}; actual SQL functions, no function stubs.`);
  await db.exec(`
    insert into auth.users(id,email) values
      ('${owner}','audit-owner@test.local'),
      ('${admin}','audit-admin@test.local'),
      ('${supplier}','audit-supplier@test.local');
    update public.profiles set created_at=now()-interval '20 days',credits=1000,last_login_date=${today}-20;
    update public.profiles set role='admin' where id='${admin}';
    update public.profiles set role='moderator' where id='${supplier}';
    insert into public.user_slots(user_id,slot_number,expires_at) values ('${owner}',2,now()+interval '20 days');
    insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number,created_at) values
      ('${app1}','${owner}','audit.owner.one','Owner One','open',1,now()-interval '15 days'),
      ('${app2}','${owner}','audit.owner.two','Owner Two','open',2,now()-interval '15 days');

    insert into public.user_slots(user_id,slot_number,expires_at)
      select '${admin}', s, now()+interval '30 days' from generate_series(2, 6) s;
    insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at)
      select '${admin}','audit.supply.' || s,'Supply ' || s,'open', s, now()-interval '15 days'
      from generate_series(1, 6) s;

    insert into public.user_slots(user_id,slot_number,expires_at)
      select '${supplier}', s, now()+interval '30 days' from generate_series(2, 6) s;
    insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at)
      select '${supplier}','audit.extra.' || s,'Extra ' || s,'open', s, now()-interval '15 days'
      from generate_series(1, 6) s;

    update public.campaign_status_journal set valid_from=now()-interval '15 days';
    update public.user_role_journal set valid_from=now()-interval '20 days';
    update public.system_provenance_markers set marker_time=(${today}-30)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${today}-30;
    set test.allow_policy_retroactive_write='true';
    select public.set_daily_task_carryover_policy(${today}-10,true,'Disposable audit fixture');
    reset test.allow_policy_retroactive_write;
  `);

  for (let n=1;n<=7;n++) await scenario(`threshold ${n}: real finalization N-1 / N, both apps, carryover gate, repeated sync`, async () => {
    await setThreshold(n);
    await checkpoint(n+1);
    for (let offset=n;offset>=2;offset--) {
      await db.exec(`select public.finalize_daily_tasks_for_date(${today}-${offset});`);
    }
    assert.equal((await scalar(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s,n-1);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,0);
    await db.exec(`select public.finalize_daily_tasks_for_date(${today}-1);`);
    assert.equal((await scalar(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s,n);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,2);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,0);
    assert.equal((await scalar(`select count(*)::int n from public.notifications where user_id='${owner}' and data->>'reason'='owner_inactive'`)).n,2);
    await assert.rejects(() => asOwner(`select public.resume_campaign('${app1}')`), /OWNER_INACTIVE/);
    await assert.rejects(() => asOwner(`update public.campaigns set status='open' where id='${app1}'`), /DIRECT_RESUME_NOT_ALLOWED/);

    const statusPre = (await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    const carryIn = statusPre.carry_in_tests;

    if (carryIn > 1) {
      // Test only 1 app: met may be false or remaining carryover debt > 0
      await qualifyingTest(1);
      const partialStatus = (await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
      if (partialStatus.carryover_remaining_tests > 0) {
        await assert.rejects(() => asOwner(`select public.resume_campaign('${app1}')`), /OWNER_CARRYOVER_INCOMPLETE|OWNER_INACTIVE/);
      }
    }

    // Now test all required tests to clear both today's target and all prior carryover debt
    const needed = Math.max(statusPre.effective_required_tests, carryIn);
    await qualifyingTest(needed);
    const statusPost = (await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    assert.equal(statusPost.met, true);
    assert.equal(statusPost.carryover_remaining_tests, 0);

    for (const id of [app1,app2]) {
      assert.equal((await asOwner(`select public.resume_campaign('${id}') r`)).rows[0].r.status,'open');
    }
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,0);
  });

  await scenario('admin applies 7 -> 3 to existing streak; next sync, persisted setting, audit and notification', async () => {
    await checkpoint(1,3);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,0);
    const {before,result}=await setThreshold(3);
    assert.equal(before.missed_days,7);
    assert.equal(result.previous_missed_days,7);
    // Saving changes the setting; the setter itself does not scan campaigns.
    assert.equal((await scalar(`select count(*)::int n from public.campaigns where owner_id='${owner}' and status='open'`)).n,2);
    assert.equal((await scalar(`select daily_missed_streak s from public.profiles where id='${owner}'`)).s,3);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,2);
    const notes=(await db.query(`select data,message from public.notifications where user_id='${owner}' and data->>'reason'='owner_inactive'`)).rows;
    assert.equal(notes.length,2);
    for (const note of notes) {
      assert.equal(note.data.threshold_days,3);
      assert.ok(note.message.includes('3 ngày liên tục'));
    }
    const event=await scalar(`select actor_id,old_state,new_state from public.admin_action_events where action='set_owner_auto_pause_config' order by created_at desc limit 1`);
    assert.equal(event.actor_id,admin);
    assert.equal(event.old_state.missed_days,7);
    assert.equal(event.new_state.missed_days,3);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,0);
  });

  await scenario('raising 3 -> 7 preserves already-paused apps and historical threshold 3', async () => {
    await checkpoint(1,3);
    await setThreshold(3);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,2);
    await setThreshold(7);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,0);
    assert.equal((await scalar(`select count(*)::int n from public.campaigns where owner_id='${owner}' and status='paused'`)).n,2);
    assert.equal((await scalar(`select count(*)::int n from public.notifications where user_id='${owner}' and (data->>'threshold_days')::int=3`)).n,2);
    await assert.rejects(() => asOwner(`select public.resume_campaign('${app1}')`), /OWNER_INACTIVE/);
  });

  await scenario('raising 3 -> 7 before sync spares an open app with streak 3', async () => {
    await checkpoint(1,3);
    await setThreshold(3);
    await setThreshold(7);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,0);
  });

  await scenario('admin threshold 3: carryover debt blocks resume even when met=true', async () => {
    await setThreshold(3);
    // Prior days had multiple supply apps; only one is available today.
    await checkpoint(4);
    for (let offset=3;offset>=1;offset--) await db.exec(`select public.finalize_daily_tasks_for_date(${today}-${offset})`);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,2);
    await db.exec(`update public.campaigns set status='paused',paused_reason='owner_manual' where owner_id in ('${admin}','${supplier}');`);
    await db.exec(`update public.campaigns set status='open',paused_reason=null where package_name='audit.supply.1';`);
    await qualifyingTest(1);
    const status=(await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    assert.equal(status.met,true);
    assert.ok(status.carryover_remaining_tests>0);
    await assert.rejects(() => asOwner(`select public.resume_campaign('${app1}')`), /OWNER_CARRYOVER_INCOMPLETE/);
    console.log('ACCEPTANCE: carryover gate blocks resume with remaining debt:',JSON.stringify({met:status.met,carryover_remaining_tests:status.carryover_remaining_tests}));
  });

  await scenario('24-hour boundary and grace period', async () => {
    await checkpoint(1,7);
    await db.exec(`update public.campaigns set created_at=now()-interval '24 hours' where id='${app1}';
      update public.campaigns set created_at=now()-interval '24 hours'+interval '1 second' where id='${app2}';`);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,1);
    assert.equal((await scalar(`select status from public.campaigns where id='${app2}'`)).status,'open');
  });

  await scenario('today already met is spared', async () => {
    await checkpoint(1,7);
    const req = (await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s.effective_required_tests;
    await qualifyingTest(req);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,0);
  });

  await scenario('timestamp mutation blocked by guard, cannot bypass auto-pause', async () => {
    await checkpoint(1,7);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,2);
    await assert.rejects(
      () => asOwner(`update public.campaigns set created_at=now() where owner_id='${owner}'`),
      /PERMISSION_DENIED/
    );
    const campAge = await scalar(`select created_at <= now() - interval '24 hours' as is_old from public.campaigns where id='${app1}'`);
    assert.equal(campAge.is_old, true);
    await asOwner('select public.record_daily_login()');
    const status=(await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    assert.equal(status.completed_tests,0);
    assert.equal(status.mode, 'owner');
    await assert.rejects(() => asOwner(`select public.resume_campaign('${app1}')`), /OWNER_INACTIVE/);
  });

  await scenario('missing intermediate finalized day (carryover disabled) cuts streak correctly', async () => {
    await db.exec(`set test.allow_policy_retroactive_write='true';
      select public.set_daily_task_carryover_policy(${today}-1,false,'Disposable gap audit');
      reset test.allow_policy_retroactive_write;`);
    await checkpoint(3,6);
    const req = (await scalar(`select public.get_daily_task_status('${owner}',${today}-2) s`)).s.effective_required_tests;
    await qualifyingTest(req, 2);
    const gapStatus=(await scalar(`select public.get_daily_task_status('${owner}',${today}-2) s`)).s;
    assert.equal(gapStatus.met,true,'The intermediate day actually met its quota');
    const before=(await scalar(`select public.get_daily_task_status('${owner}',${today}-1) s`)).s;
    await db.exec('select public.apply_daily_inactivity_penalty();');
    const r=await scalar(`select daily_missed_streak s,
      (select count(*)::int from public.user_daily_tasks where user_id='${owner}' and task_date=${today}-2 and finalized_at is not null) gap_rows
      from public.profiles where id='${owner}'`);
    assert.equal(before.carryover_ready, false, 'An unfinalized predecessor remains pending even when carryover is disabled');
    assert.equal(r.gap_rows, 1, 'Intermediate day was finalized sequentially');
    assert.equal(r.s, 1, 'Intermediate met day cut the streak; today missed streak is 1, not 7');
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n, 0, 'No apps paused because streak is 1 < 7');
  });

  await scenario('new unsealed history with carryover enabled observation', async () => {
    // A realistic upgrade boundary: yesterday is outside trusted coverage and
    // has no finalized checkpoint; older rows must never be fabricated.
    await db.exec(`update public.system_provenance_markers set marker_time=now(),marker_date=${today};
      update public.profiles set daily_missed_streak=7 where id='${owner}';
      select public.apply_daily_inactivity_penalty();`);
    const s=(await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    assert.equal(s.carryover_ready, false);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n, 0);
    await db.exec(`update public.campaigns set status='paused', paused_reason='owner_inactive' where id='${app1}';`);
    await assert.rejects(() => asOwner(`select public.resume_campaign('${app1}')`), /DAILY_TASK_PENDING/);
  });
} finally { await db.close(); }

