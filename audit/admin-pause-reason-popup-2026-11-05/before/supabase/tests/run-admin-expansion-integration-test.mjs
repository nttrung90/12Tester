import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

// Verify the final deployable chain, including inherited Supabase default grants.
// This suite deliberately never loads staged audit copies.
const db = await createDb({ legacyGrants: true });
const admin = '11111111-1111-1111-1111-111111111111';
const owner = '22222222-2222-2222-2222-222222222222';
const tester = '33333333-3333-3333-3333-333333333333';
const campaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const reports = [1, 2, 3].map(n => 'bbbbbbbb-bbbb-bbbb-bbbb-' + String(n).padStart(12, '0'));
async function actor(uid, sql) {
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${uid}',true); set local role authenticated;`);
  try { const r = (await db.query(sql)).rows; await db.exec('commit'); return r; }
  catch (e) { await db.exec('rollback'); throw e; }
}
try {
  const migrations = readdirSync(new URL('../migrations/', import.meta.url))
    .filter(f => /^\d{3}_.*\.sql$/.test(f) && +f.slice(0, 3) >= 62).sort();
  assert.ok([70, 71, 72, 73].every(n => migrations.some(f => +f.slice(0, 3) === n)), 'All four admin migrations must be in the deployable directory');
  for (const f of migrations) { await db.exec(read('supabase/migrations/' + f)); console.log('APPLIED:', f); }
  await db.exec(`
    insert into auth.users(id,email) values
      ('${admin}','admin@fixture.invalid'),
      ('${owner}','owner@fixture.invalid'),
      ('${tester}','tester@fixture.invalid'),
      ('33333333-3333-3333-3333-333333333332','tester2@fixture.invalid'),
      ('33333333-3333-3333-3333-333333333331','tester1@fixture.invalid');
    update public.profiles set role='admin',credits=500 where id in ('${admin}','${owner}');
    update public.profiles set credits=500 where id in ('${tester}','33333333-3333-3333-3333-333333333332','33333333-3333-3333-3333-333333333331');
    insert into public.campaigns(id,owner_id,app_name,package_name,status) values ('${campaign}','${owner}','Integrated test','invalid.fixture.integration','open');
    insert into public.campaign_members(campaign_id,user_id) values
      ('${campaign}','${tester}'),
      ('${campaign}','33333333-3333-3333-3333-333333333332'),
      ('${campaign}','33333333-3333-3333-3333-333333333331');
    insert into public.tester_reports(id,campaign_id,user_id,status,content,provisional_granted_credits,review_tester_bonus,review_owner_bonus,business_policy_id,created_at,media_expired)
    values
      ('${reports[0]}','${campaign}','33333333-3333-3333-3333-333333333331','pending','Integration fixture',17,7,3,(select id from public.business_policies order by effective_from limit 1),now(),true),
      ('${reports[1]}','${campaign}','33333333-3333-3333-3333-333333333332','pending','Integration fixture',17,7,3,(select id from public.business_policies order by effective_from limit 1),now(),true),
      ('${reports[2]}','${campaign}','${tester}','pending','Integration fixture',17,7,3,(select id from public.business_policies order by effective_from limit 1),now(),true);
  `);
  const own = (await actor(owner, `select public.approve_tester_report('${reports[0]}',1000) as r`))[0].r;
  assert.equal(own.credits_awarded, 7, '073 must wrap effective071 snapshot implementation and ignore compatibility p_bonus');
  assert.equal(own.owner_credits_awarded, 3);
  assert.equal((await db.query(`select count(*)::int n from public.admin_action_events where target_id='${reports[0]}'`)).rows[0].n, 0);
  console.log('PASS: Admin-owner review preserves071 snapshot bonuses and owner-mode audit semantics');

  const staff = (await actor(admin, `select public.approve_tester_report('${reports[1]}',-1000) as r`))[0].r;
  assert.equal(staff.credits_awarded, 7);
  assert.equal(staff.owner_credits_awarded, 0);
  const staffEvents = (await db.query(`select * from public.admin_action_events where target_id='${reports[1]}' and action='staff_approve_tester_report'`)).rows;
  assert.equal(staffEvents.length, 1);
  assert.equal(staffEvents[0].actor_id, admin);
  console.log('PASS: Non-owner staff review retains snapshot and emits one common audit event');

  const before = (await db.query(`select credits from public.profiles where id='${tester}'`)).rows[0].credits;
  await actor(admin, `select public.reject_tester_report('${reports[2]}','Invalid fixture screenshot','INVALID_SCREENSHOT')`);
  const after = (await db.query(`select credits from public.profiles where id='${tester}'`)).rows[0].credits;
  assert.equal(before - after, 17, '073 must not overwrite071 actual clawback with old fixed amount');
  assert.equal((await db.query(`select count(*)::int n from public.admin_action_events where target_id='${reports[2]}' and action='staff_reject_tester_report'`)).rows[0].n, 1);
  console.log('PASS: Staff rejection retains071 exact provisional clawback and common audit');

  const queue = (await actor(admin, "select public.admin_get_tester_reports_queue(p_status=>null) as r"))[0].r;
  const row = queue.items.find(r => r.id === reports[0]);
  assert.ok(row);
  assert.equal(row.media_expired, true);
  assert.equal(row.review_tester_bonus, 7);
  assert.equal(row.review_owner_bonus, 3);
  assert.equal(row.provisional_granted_credits, 17);
  console.log('PASS: Final queue combines070 media expiry and071 reward snapshots');

  const policy = (await actor(tester, 'select public.get_effective_business_policy() as r'))[0].r;
  assert.ok(!('operator_note' in policy), 'Public policy must not leak internal admin rationale');
  for (const f of ['_get_effective_business_policy_row(date)', '_impl_approve_tester_report(uuid,integer)', '_impl_reject_tester_report(uuid,text,text)']) {
    const priv = (await db.query(`select has_function_privilege('authenticated','public.${f}','EXECUTE') as allowed`)).rows[0].allowed;
    assert.equal(priv, false, 'Client must not execute private helper ' + f);
  }
  console.log('PASS: Final-chain private helpers and public policy metadata remain restricted');
  console.log('ALL ADMIN EXPANSION INTEGRATION TESTS PASSED');
} finally { await db.close(); }
