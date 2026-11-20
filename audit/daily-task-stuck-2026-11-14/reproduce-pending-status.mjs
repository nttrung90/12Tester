import assert from 'node:assert/strict';
import { readdirSync, writeFileSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

// Isolated, in-memory research fixture. Does not connect to production.
const db = await createDb();
const owner = 'af140000-0000-0000-0000-000000000001';
const supplier = 'af140000-0000-0000-0000-000000000002';
const today = `(now() at time zone 'Asia/Ho_Chi_Minh')::date`;
const evidence = { environment: 'isolated PGlite', migrations: [], cases: [] };
const task = async () => (await db.query(`select public.get_daily_task_status('${owner}',${today}) as task`)).rows[0].task;
const summarize = s => Object.fromEntries(['status_date','mode','base_required_tests','effective_required_tests','completed_tests','met','history_ready','carryover_ready','carryover_remaining_tests'].map(k => [k, s[k]]));

try {
  evidence.migrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(n => /^\d{3}_.*\.sql$/.test(n)).sort();
  for (const name of evidence.migrations) await db.exec(read('supabase/migrations/' + name));
  await db.exec(`
    insert into auth.users(id,email) values ('${owner}','pending-owner@fixture.invalid'),('${supplier}','pending-supplier@fixture.invalid');
    update public.profiles set created_at=now()-interval '60 days',credits=10000,last_login_date=${today} where id='${owner}';
    update public.profiles set created_at=now()-interval '60 days',role='admin' where id='${supplier}';
    insert into public.campaigns(owner_id,package_name,app_name,status,created_at)
      values ('${owner}','fixture.pending.owner','Pending Owner','open',now()-interval '10 days'),
             ('${supplier}','fixture.pending.supply','Pending Supply','open',now()-interval '10 days');
    update public.system_provenance_markers set marker_time=(${today}-60)::timestamp at time zone 'Asia/Ho_Chi_Minh',marker_date=${today}-60;
    insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
      select 'checkin',gen_random_uuid(),'${owner}',id,owner_id,${today},'valid',true,now()
      from public.campaigns where owner_id='${supplier}';
  `);
  const missing = await task();
  assert.equal(missing.mode, 'owner');
  assert.ok(missing.effective_required_tests > 0);
  assert.ok(missing.completed_tests >= missing.effective_required_tests);
  assert.equal(missing.history_ready, true);
  assert.equal(missing.carryover_ready, false);
  assert.equal(missing.met, false);
  evidence.cases.push({ name: 'enough_tests_but_missing_predecessor', result: summarize(missing) });

  // Control: trusted fixture checkpoint; this is NOT a proposed production repair.
  await db.exec(`insert into public.user_daily_tasks(user_id,task_date,mode,finalized_at,carry_out_tests,missed_streak,met)
    values('${owner}',${today}-1,'owner',now(),0,0,true)`);
  const ready = await task();
  assert.equal(ready.completed_tests, missing.completed_tests);
  assert.equal(ready.effective_required_tests, missing.effective_required_tests);
  assert.equal(ready.history_ready, true);
  assert.equal(ready.carryover_ready, true);
  assert.equal(ready.met, true);
  evidence.cases.push({ name: 'same_tests_with_trusted_predecessor', result: summarize(ready) });

  await db.exec(`insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,reconciliation_status,observed_at)
    select 'checkin',gen_random_uuid(),'${owner}',id,owner_id,${today},'valid',false,'unresolved_missing_source',now()
    from public.campaigns where owner_id='${supplier}'`);
  const unresolved = await task();
  assert.ok(unresolved.completed_tests >= unresolved.effective_required_tests);
  assert.equal(unresolved.history_ready, false);
  assert.equal(unresolved.carryover_ready, false);
  assert.equal(unresolved.met, false);
  evidence.cases.push({ name: 'enough_tests_but_unresolved_provenance', result: summarize(unresolved) });
  evidence.pass = true;
  writeFileSync(new URL('./reproduction-result.json', import.meta.url), JSON.stringify(evidence, null, 2) + '\n');
  console.log(JSON.stringify(evidence, null, 2));
} finally {
  await db.close();
}
