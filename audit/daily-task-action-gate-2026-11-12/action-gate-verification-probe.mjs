// Candidate verification probe: runs on fresh in-memory PGlite with migrations through 090.
// Compares before/after and proves the action gate prevents quota reduction when tasks are unmet.
import assert from 'node:assert/strict';
import { readdirSync, writeFileSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'ea000000-0000-0000-0000-000000000001';
const today = "(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date";
const results = [];
const one = async sql => (await db.query(sql)).rows[0];

async function actor(sql) {
  await db.exec(`set local role authenticated;select set_config('request.jwt.claim.sub','${owner}',true)`);
  return db.query(sql);
}

async function fixture(completedTests = 3) {
  const suppliers = Array.from({ length: 2 }, (_, i) => `eb000000-0000-0000-0000-${String(i + 1).padStart(12, '0')}`);
  for (const [i, id] of [owner, ...suppliers].entries()) {
    await db.query('insert into auth.users(id,email) values($1,$2)', [id, `gate-fixture-${i}@test.local`]);
    await db.exec(`update public.profiles set role='member',credits=10000,created_at=clock_timestamp()-interval '90 days',last_login_date=${today} where id='${id}';
      insert into public.user_slots(user_id,slot_number,expires_at) select '${id}',n,clock_timestamp()+interval '30 days' from generate_series(2,6)n;`);
  }
  for (const id of [owner, ...suppliers]) {
    for (let n = 1; n <= (id === owner ? 2 : 6); n++) {
      await db.query(
        `insert into public.campaigns(owner_id,package_name,app_name,status,slot_number,created_at) values($1,$2,'Gate fixture','open',$3,clock_timestamp()-interval '2 days')`,
        [id, `gate.fixture.${id.replaceAll('-', '')}.${n}`, n]
      );
    }
  }
  await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,active_app_count,base_required_tests,required_tests,effective_required_tests,available_apps,completed_tests,logged_in,is_exempt,met,missed_streak,carry_in_tests,carry_out_tests,finalized_at)
    values('${owner}',${today}-1,'owner',2,6,6,6,12,6,true,false,true,0,0,0,clock_timestamp());
    insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,submission_context,reconciliation_status,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',c.id,c.owner_id,${today},'valid',true,'checkin','verified_checkin',clock_timestamp() from public.campaigns c where c.owner_id<>'${owner}' order by c.id limit ${completedTests};`);

  const status = (await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
  return { status, id: (await one(`select id from public.campaigns where owner_id='${owner}' order by slot_number limit 1`)).id };
}

try {
  const files = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) <= 90)
    .sort();
  for (const file of files) await db.exec(read('supabase/migrations/' + file));

  // TEST SUITE 1: When completed = 3 (B=6, D=3, met=false) -> ALL ACTIONS MUST BE BLOCKED
  for (const name of ['owner_delete', 'direct_delete', 'direct_production', 'manual_pause', 'direct_completed', 'direct_closed']) {
    await db.exec('begin');
    try {
      const f = await fixture(3);
      assert.equal(f.status.met, false);
      assert.equal(f.status.base_required_tests, 6);
      assert.equal(f.status.completed_tests, 3);

      let error = null, result;
      await db.exec('savepoint sp_action');
      try {
        result = await actor(
          name === 'owner_delete' ? `select public.delete_owned_campaign('${f.id}') r` :
          name === 'direct_delete' ? `delete from public.campaigns where id='${f.id}' returning id` :
          name === 'direct_production' ? `update public.campaigns set status='production',paused_reason=null where id='${f.id}' returning id,status` :
          name === 'manual_pause' ? `update public.campaigns set status='paused',paused_reason='owner_manual' where id='${f.id}' returning id,status` :
          name === 'direct_completed' ? `update public.campaigns set status='completed',paused_reason=null where id='${f.id}' returning id,status` :
          `update public.campaigns set status='closed',paused_reason=null where id='${f.id}' returning id,status`
        );
      } catch (e) {
        error = e.message;
        await db.exec('rollback to sp_action');
      }

      const after = (await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
      const appCount = (await one(`select count(*)::int as c from public.campaigns where owner_id='${owner}'`)).c;
      const evidence = {
        case: name,
        expected: 'blocked',
        before: f.status,
        after,
        blocked: error !== null,
        error,
        appCountAfter: appCount,
        pass: error !== null && after.met === false && after.base_required_tests === 6 && appCount === 2
      };
      assert.ok(evidence.blocked, `Action ${name} should have been blocked`);
      assert.equal(after.met, false, `Task met should remain false for ${name}`);
      assert.equal(after.base_required_tests, 6, `Quota should NOT decrease for ${name}`);
      assert.equal(appCount, 2, `Campaign count should remain 2 for ${name}`);
      results.push(evidence);
      console.log(`BLOCKED UNMET: ${name} -> error: ${error}`);
    } finally {
      await db.exec('rollback');
    }
  }

  // TEST SUITE 2: When completed = 6 (B=6, D=6, met=true) -> ACTIONS MUST BE ALLOWED
  for (const name of ['owner_delete', 'direct_production', 'manual_pause']) {
    await db.exec('begin');
    try {
      const f = await fixture(6);
      assert.equal(f.status.met, true);
      assert.equal(f.status.base_required_tests, 6);
      assert.equal(f.status.completed_tests, 6);

      let error = null, result;
      await db.exec('savepoint sp_action');
      try {
        result = await actor(
          name === 'owner_delete' ? `select public.delete_owned_campaign('${f.id}') r` :
          name === 'direct_production' ? `update public.campaigns set status='production',paused_reason=null where id='${f.id}' returning id,status` :
          `update public.campaigns set status='paused',paused_reason='owner_manual' where id='${f.id}' returning id,status`
        );
      } catch (e) {
        error = e.message;
        await db.exec('rollback to sp_action');
      }

      const after = (await one(`select public.get_daily_task_status('${owner}',${today}) s`)).s;
      const evidence = {
        case: `${name}_met_allowed`,
        expected: 'allowed',
        before: f.status,
        after,
        blocked: error !== null,
        error,
        pass: error === null && after.met === true && after.base_required_tests === 3
      };
      assert.equal(error, null, `Action ${name} should be allowed when met=true`);
      assert.equal(after.met, true, `Task met should remain true for ${name}`);
      assert.equal(after.base_required_tests, 3, `Quota legitimately reduces to 3 after ${name}`);
      results.push(evidence);
      console.log(`ALLOWED MET: ${name} -> success`);
    } finally {
      await db.exec('rollback');
    }
  }
} finally {
  await db.close();
  writeFileSync(
    new URL('./candidate-action-gate-results.json', import.meta.url),
    JSON.stringify({ at: new Date().toISOString(), kind: 'candidate090, single-connection PGlite', results }, null, 2)
  );
}

assert.equal(results.length, 9);
assert.ok(results.every(r => r.pass), 'All candidate verification tests passed');
console.log('CANDIDATE VERIFICATION PASSED: All 9/9 action gate cases verified before/after.');
