import assert from 'node:assert/strict';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
import { readdirSync } from 'node:fs';
const db = await createDb();
const admin = '11111111-1111-1111-1111-111111111111';
const user = '22222222-2222-2222-2222-222222222222';
const moderator = '33333333-3333-3333-3333-333333333333';
const missing = '99999999-9999-9999-9999-999999999999';
const tester = '44444444-4444-4444-4444-444444444444';
const campaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const report = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const calls = [
  ['adjust credits', `select public.admin_adjust_credits('${user}',1,'audit')`],
  ['lock user', `select public.admin_set_user_lock('${user}',true,'audit')`],
  ['contact list', `select public.admin_get_contact_reports()`],
  ['contact reply', `select public.admin_reply_contact_report('${missing}','audit')`],
  ['contact status', `select public.admin_update_report_status('${missing}',true)`],
  ['notification', `select public.admin_send_notification('${user}','audit','audit','admin')`],
  ['app configuration', `select public.admin_update_app_config(1,28,'1.0.8',false)`],
  ['home stats read', `select public.admin_get_home_stats_config()`],
  ['home stats write', `select public.admin_set_home_stats_cycle(2,null)`],
  ['daily task configuration', `select public.admin_get_daily_task_config()`],
  ['daily task list', `select public.admin_list_daily_task_policies()`],
  ['daily task history', `select public.admin_get_daily_task_policy_history()`],
  ['daily task schedule', `select public.admin_schedule_daily_task_policy(3,current_date+1,'audit','invalid')`],
  ['daily task cancellation', `select public.admin_cancel_daily_task_policy(current_date+1,'audit','invalid')`],
  ['campaign pause', `select public.admin_pause_campaign('${missing}','audit')`],
  ['campaign resume', `select public.admin_resume_campaign('${missing}')`],
  ['campaign delete', `select public.admin_delete_campaign('${missing}','audit')`],
];
let passed=0;
async function asActor(uid, sql, role='authenticated') {
  await db.exec('begin');
  try {
    await db.exec(`select set_config('request.jwt.claim.sub','${uid ?? ''}',true); set local role ${role};`);
    return await db.query(sql);
  } finally { await db.exec('rollback'); }
}
async function check(name, fn) { await fn(); passed++; console.log('PASS:',name); }
try {
  const files = readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(n=>/^\d+.*\.sql$/.test(n)).sort();
  assert.ok(files.some(n=>Number(n.slice(0,3))>=69), 'Admin fix migration must exist');
  for(const name of files) await db.exec(read('supabase/migrations/'+name));
  await db.exec(`insert into auth.users(id,email) values ('${admin}','admin@fixture.invalid'),('${user}','member@fixture.invalid'),('${moderator}','mod@fixture.invalid'),('${tester}','tester@fixture.invalid');
    update public.profiles set role='admin',is_locked=false where id='${admin}';
    update public.profiles set role='moderator' where id='${moderator}';
    update public.profiles set credits=5 where id='${user}';
    insert into public.campaigns(id,owner_id,app_name,package_name,status) values('${campaign}','${user}','Admin audit fixture','invalid.fixture.admin','open');
    insert into public.campaign_members(campaign_id,user_id) values('${campaign}','${tester}');
    insert into public.tester_reports(id,campaign_id,user_id,status,content,created_at) values('${report}','${campaign}','${tester}','pending','Audit fixture',now());`);
  await check('effective delta, return amount, and ledger match', async()=>{
    await db.exec(`begin;select set_config('request.jwt.claim.sub','${admin}',true);set local role authenticated;`);
    try {
      const result=(await db.query(`select public.admin_adjust_credits('${user}',-50,'audit') as r`)).rows[0].r;
      assert.equal(result.new_credits,0);assert.equal(result.previous_credits,5);assert.equal(result.amount,-5);
      await db.exec('reset role');
      const log=(await db.query(`select amount,balance from public.credit_logs where user_id='${user}' and action='admin_deduct' order by created_at desc limit 1`)).rows[0];
      assert.equal(log.amount,-5);assert.equal(log.balance,0);
    } finally { await db.exec('rollback'); }
  });
  await check('absent lock target does not report success',()=>assert.rejects(asActor(admin,`select public.admin_set_user_lock('${missing}',true,'audit')`),/USER_NOT_FOUND|TARGET_USER_NOT_FOUND/));
  await check('campaign detail preserves current DTO keys and report counts',async()=>{
    const d=(await asActor(user,`select public.get_campaign_detail('${campaign}') as d`)).rows[0].d;
    for(const key of ['is_actual_owner','can_manage','checked_in_confirmed_today','my_checkin_dates','weekly_warnings_count','app_update','tester_reports_count','pending_reports_count'])assert.ok(Object.hasOwn(d,key),`missing DTO field ${key}`);
    assert.equal(d.can_manage,true);assert.equal(d.tester_reports_count,1);assert.equal(d.pending_reports_count,1);
  });
  await check('reject zero provisional grant preserves tester credit and reason contract',async()=>{
    await db.exec('begin');
    try{
      await db.exec(`update public.profiles set credits=1 where id='${tester}';update public.tester_reports set provisional_granted_credits=0,bonus_awarded=0 where id='${report}';select set_config('request.jwt.claim.sub','${admin}',true);set local role authenticated;`);
      const r=(await db.query(`select public.reject_tester_report('${report}','Audit rejection','INVALID_SCREENSHOT') as r`)).rows[0].r;
      assert.equal(r.clawed_credits,0);assert.equal(r.tester_balance,1);
      await db.exec('reset role');
      const row=(await db.query(`select reason_code,provisional_clawed,bonus_awarded from public.tester_reports where id='${report}'`)).rows[0];
      assert.equal(row.reason_code,'INVALID_SCREENSHOT');assert.equal(row.provisional_clawed,0);assert.equal(row.bonus_awarded,0);
    }finally{await db.exec('rollback');}
  });
  await check('reject clawback clamps to actual low balance and records ledger metadata',async()=>{
    await db.exec('begin');
    try{
      await db.exec(`update public.profiles set credits=1 where id='${tester}';update public.tester_reports set provisional_granted_credits=10,bonus_awarded=10 where id='${report}';select set_config('request.jwt.claim.sub','${admin}',true);set local role authenticated;`);
      const r=(await db.query(`select public.reject_tester_report('${report}','Audit rejection','INVALID_SCREENSHOT') as r`)).rows[0].r;
      assert.equal(r.clawed_credits,1);assert.equal(r.tester_balance,0);
      await db.exec('reset role');
      const log=(await db.query(`select amount,metadata from public.credit_logs where user_id='${tester}' and action='test_rejected_clawback' order by created_at desc limit 1`)).rows[0];
      assert.equal(log.amount,-1);assert.equal(log.metadata.report_id,report);
    }finally{await db.exec('rollback');}
  });
  const forbidden=/FORBIDDEN|PERMISSION_DENIED|NOT_ADMIN|ACCOUNT_LOCKED|NOT_AUTHENTICATED|UNAUTHORIZED|permission denied/i;
  for(const [name,uid] of [['admin',admin],['moderator',moderator]]) {
    await check(`unlocked ${name} can approve reports without owner reward`,async()=>{
      const r=(await asActor(uid,`select public.approve_tester_report('${report}') as r`)).rows[0].r;
      assert.equal(r.success,true);assert.equal(r.owner_credits_awarded,0);assert.equal(r.credits_awarded,2);
    });
    await check(`unlocked ${name} can reject reports`,async()=>{
      const r=(await asActor(uid,`select public.reject_tester_report('${report}','Audit rejection','INVALID_SCREENSHOT') as r`)).rows[0].r;
      assert.equal(r.success,true);
    });
  }
  for(const [actor,uid] of [['member',user],['moderator',moderator],['missing-profile',missing],['anonymous',null]]) {
    for(const [name,sql] of calls) await check(`${actor} denied: ${name}`,()=>assert.rejects(asActor(uid,sql,uid===null?'anon':'authenticated'),forbidden));
  }
  await db.exec(`update public.profiles set is_locked=true where id='${admin}';`);
  for(const [name,sql] of calls) await check(`locked admin denied: ${name}`,()=>assert.rejects(asActor(admin,sql),forbidden));
  await db.exec(`update public.profiles set is_locked=true where id='${moderator}';`);
  for(const [name,uid] of [['admin',admin],['moderator',moderator]]) {
    await check(`locked ${name} cannot approve reports`,()=>assert.rejects(asActor(uid,`select public.approve_tester_report('${report}')`),forbidden));
    await check(`locked ${name} cannot reject reports`,()=>assert.rejects(asActor(uid,`select public.reject_tester_report('${report}','Audit rejection','INVALID_SCREENSHOT')`),forbidden));
  }
  await check('locked admin cannot update configuration directly',async()=>{
    const before=(await db.query(`select * from public.app_configs where id='android_client'`)).rows;
    try { const r=await asActor(admin,`update public.app_configs set latest_version_name='unauthorized-write' where id='android_client' returning id`);assert.equal(r.rows.length,0); }
    catch(e){if(e.code!=='42501'&&!forbidden.test(e.message))throw e;}
    assert.deepEqual((await db.query(`select * from public.app_configs where id='android_client'`)).rows,before);
  });
  for(const role of ['anon','authenticated']) for(const privilege of ['TRUNCATE','TRIGGER']) {
    await check(`${role} has no ${privilege} capability on public tables`,async()=>{
      const rows=(await db.query(`select c.relname from pg_class c where c.relnamespace='public'::regnamespace and c.relkind='r' and has_table_privilege('${role}',c.oid,'${privilege}')`)).rows;
      assert.deepEqual(rows,[]);
    });
  }
  console.log(`ALL ${passed} INDEPENDENT CHECKS PASSED`);
} finally {await db.close();}
