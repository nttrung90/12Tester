// Read-only audit of application behavior in a disposable local database.
// BUG observations document current defects; they are not acceptance assertions.
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'a1000000-0000-0000-0000-000000000001';
const admin = 'a1000000-0000-0000-0000-000000000002';
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
async function qualifyingTest(offset = 0) {
  // Historical fixture represents a qualifying event observed on that day.
  await db.exec(`insert into public.daily_test_events_journal
    (source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',id,'${admin}',${today}-${offset},'valid',true,
      (${today}-${offset})::timestamp at time zone 'Asia/Ho_Chi_Minh'
    from public.campaigns where owner_id='${admin}' and status='open';`);
}
try {
  const files = readdirSync(new URL('../migrations/', import.meta.url))
    .filter(x => /^\d{3}_.*\.sql$/.test(x)).sort();
  for (const file of files) await db.exec(read(`supabase/migrations/${file}`));
  console.log(`Loaded ${files[0]} through ${files.at(-1)}; actual SQL functions, no function stubs.`);
  await db.exec(`
    insert into auth.users(id,email) values ('${owner}','audit-owner@test.local'),('${admin}','audit-admin@test.local');
    update public.profiles set created_at=now()-interval '20 days',credits=1000,last_login_date=${today}-20;
    update public.profiles set role='admin' where id='${admin}';
    insert into public.user_slots(user_id,slot_number,expires_at) values ('${owner}',2,now()+interval '20 days');
    insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number,created_at) values
      ('${app1}','${owner}','audit.owner.one','Owner One','open',1,now()-interval '15 days'),
      ('${app2}','${owner}','audit.owner.two','Owner Two','open',2,now()-interval '15 days');
    insert into public.campaigns(owner_id,package_name,app_name,status,created_at)
      values ('${admin}','audit.supply','Supply','open',now()-interval '15 days');
    update public.campaign_status_journal set valid_from=now()-interval '15 days';
    update public.user_role_journal set valid_from=now()-interval '20 days';
    update public.system_provenance_markers set marker_time=(${today}-30)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${today}-30;
    set test.allow_policy_retroactive_write='true';
    select public.set_daily_task_carryover_policy(${today}-10,true,'Disposable audit fixture');
    reset test.allow_policy_retroactive_write;
  `);

  for (let n=1;n<=7;n++) await scenario(`threshold ${n}: real finalization N-1 / N, both apps, repeated sync`, async () => {
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
    await qualifyingTest();
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

  await scenario('admin threshold 3: current resume still allows outstanding carryover', async () => {
    await setThreshold(3);
    // Three apps were available on prior days; only one is available today.
    await db.exec(`insert into public.user_slots(user_id,slot_number,expires_at)
      values ('${admin}',2,now()+interval '20 days'),('${admin}',3,now()+interval '20 days');
      insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at)
      values ('${admin}','audit.extra.2','Extra supply 2','open',2,now()-interval '15 days'),
             ('${admin}','audit.extra.3','Extra supply 3','open',3,now()-interval '15 days');
      update public.campaign_status_journal set valid_from=now()-interval '15 days' where owner_id='${admin}';`);
    await checkpoint(4);
    for (let offset=3;offset>=1;offset--) await db.exec(`select public.finalize_daily_tasks_for_date(${today}-${offset})`);
    assert.equal((await scalar('select public.sync_campaign_lifecycle() r')).r.paused,2);
    await db.exec(`update public.campaigns set status='paused',paused_reason='owner_manual' where package_name in ('audit.extra.2','audit.extra.3');`);
    await qualifyingTest();
    const status=(await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    assert.equal(status.met,true);
    assert.ok(status.carryover_remaining_tests>0);
    const result=(await asOwner(`select public.resume_campaign('${app1}') r`)).rows[0].r;
    assert.equal(result.status,'open');
    console.log('GAP requested carryover gate at threshold 3:',JSON.stringify({met:status.met,carryover_remaining_tests:status.carryover_remaining_tests,result:result.status}));
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
    await qualifyingTest();
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,0);
  });

  await scenario('timestamp mutation bypass observation', async () => {
    await checkpoint(1,7);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,2);
    await asOwner(`update public.campaigns set created_at=now() where owner_id='${owner}'`);
    await asOwner('select public.record_daily_login()');
    const status=(await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    const result=(await asOwner(`select public.resume_campaign('${app1}') r`)).rows[0].r;
    assert.equal(status.completed_tests,0);
    assert.equal(result.status,'open');
    console.log('BUG timestamp bypass:',JSON.stringify({mode:status.mode,completed:status.completed_tests,met:status.met,result}));
  });

  await scenario('missing intermediate finalized day observation (carryover disabled)', async () => {
    await db.exec(`set test.allow_policy_retroactive_write='true';
      select public.set_daily_task_carryover_policy(${today}-1,false,'Disposable gap audit');
      reset test.allow_policy_retroactive_write;`);
    await checkpoint(3,6);
    await qualifyingTest(2);
    const gapStatus=(await scalar(`select public.get_daily_task_status('${owner}',${today}-2) s`)).s;
    assert.equal(gapStatus.met,true,'The intermediate day actually met its quota');
    const before=(await scalar(`select public.get_daily_task_status('${owner}',${today}-1) s`)).s;
    await db.exec('select public.apply_daily_inactivity_penalty();');
    const r=await scalar(`select daily_missed_streak s,
      (select count(*)::int from public.user_daily_tasks where user_id='${owner}' and task_date=${today}-2 and finalized_at is not null) gap_rows
      from public.profiles where id='${owner}'`);
    assert.equal(before.carryover_ready,true);
    assert.equal(r.s,7,'Current defect reproduction: the six-day streak crosses a met day');
    console.log('BUG non-contiguous streak:',JSON.stringify({intermediate_day_met:gapStatus.met,streak:r.s,expected_streak:1,missing_day_rows:r.gap_rows,paused:(await scalar('select public.pause_inactive_owner_campaigns() n')).n}));
  });

  await scenario('new unsealed history with carryover enabled observation', async () => {
    // A realistic upgrade boundary: yesterday is outside trusted coverage and
    // has no finalized checkpoint; older rows must never be fabricated.
    await db.exec(`update public.system_provenance_markers set marker_time=now(),marker_date=${today};
      update public.profiles set daily_missed_streak=7 where id='${owner}';
      select public.apply_daily_inactivity_penalty();`);
    const s=(await scalar(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
    console.log('RISK pending requires reconciliation:',JSON.stringify({carryover_ready:s.carryover_ready,history_ready:s.history_ready,paused:(await scalar('select public.pause_inactive_owner_campaigns() n')).n}));
  });
} finally { await db.close(); }
