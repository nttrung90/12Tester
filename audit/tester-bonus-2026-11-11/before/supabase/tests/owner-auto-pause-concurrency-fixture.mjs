import crypto from 'node:crypto';
import assert from 'node:assert/strict';

export function makeOwnerResumeFixture() {
  return {
    runId: crypto.randomBytes(6).toString('hex'),
    ownerId: crypto.randomUUID(), adminId: crypto.randomUUID(), supplierId: crypto.randomUUID(),
    camp1: crypto.randomUUID(), camp2: crypto.randomUUID(),
    supplyIds: Array.from({ length: 6 }, () => crypto.randomUUID()),
  };
}

// Each query is one prepared statement, shared by real-PG and local fixture checks.
export async function prepareOwnerResumeFixture(client, f) {
  for (const [id, role] of [[f.ownerId, 'member'], [f.adminId, 'admin'], [f.supplierId, 'moderator']]) {
    await client.query('insert into auth.users(id,email) values ($1,$2)', [id, `${role}-${f.runId}@test.invalid`]);
    await client.query(`update public.profiles set role=$2,is_locked=false,credits=1000,
      created_at=now()-interval '20 days' where id=$1`, [id, role]);
  }
  await client.query(`insert into public.user_slots(user_id,slot_number,expires_at)
    values ($1,2,now()+interval '30 days')`, [f.ownerId]);
  for (let i = 0; i < f.supplyIds.length; i++) {
    if (i > 0) await client.query(`insert into public.user_slots(user_id,slot_number,expires_at)
      values ($1,$2,now()+interval '30 days')`, [f.supplierId, i + 1]);
    await client.query(`insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number,created_at)
      values ($1,$2,'Concurrency supply',$3,'open',$4,now()-interval '15 days')`,
    [f.supplyIds[i], f.supplierId, `com.ownerresume.r${f.runId}.s${i}`, i + 1]);
  }
  for (const [i, id] of [f.camp1, f.camp2].entries()) {
    await client.query(`insert into public.campaigns(id,owner_id,app_name,package_name,status,paused_reason,slot_number,created_at)
      values ($1,$2,'Concurrency owner app',$3,'paused','owner_inactive',$4,now()-interval '15 days')`,
    [id, f.ownerId, `com.ownerresume.r${f.runId}.o${i}`, i + 1]);
  }
  await client.query(`insert into public.user_daily_tasks
    (user_id,task_date,mode,finalized_at,missed_streak,carry_out_tests,met,base_required_tests,carry_in_tests)
    values ($1,(now() at time zone 'Asia/Ho_Chi_Minh')::date-1,'owner',now(),0,0,true,6,0)`, [f.ownerId]);
  for (const id of f.supplyIds) {
    await client.query(`insert into public.daily_test_events_journal
      (source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at)
      values ('checkin',gen_random_uuid(),$1,$2,$3,(now() at time zone 'Asia/Ho_Chi_Minh')::date,'valid',true,now())`,
    [f.ownerId, id, f.supplierId]);
  }
  const { rows: [status] } = await client.query(`select public.get_daily_task_status
    ($1,(now() at time zone 'Asia/Ho_Chi_Minh')::date) s`, [f.ownerId]);
  assert.equal(status.s.mode, 'owner');
  assert.equal(status.s.history_ready, true);
  assert.equal(status.s.carryover_ready, true);
  assert.equal(status.s.met, true, 'Use a dedicated migrated test database with an unmodified daily policy');
  assert.equal(status.s.carryover_remaining_tests, 0);
  assert.equal(status.s.completed_tests, f.supplyIds.length);
  return status.s;
}

export async function cleanupOwnerResumeFixture(client, f) {
  const failures = [];
  const remove = async (sql, params) => {
    try { await client.query(sql, params); } catch (error) { failures.push(error); }
  };
  for (const id of [f.camp1, f.camp2, ...f.supplyIds]) {
    await remove('delete from public.campaigns where id=$1', [id]);
    await remove('delete from public.campaign_archive where campaign_id=$1', [id]);
    await remove('delete from public.campaign_status_journal where campaign_id=$1', [id]);
  }
  for (const id of [f.ownerId, f.adminId, f.supplierId]) {
    for (const table of ['daily_test_events_journal', 'user_daily_tasks', 'user_slots', 'notifications', 'credit_logs']) {
      await remove(`delete from public.${table} where user_id=$1`, [id]);
    }
    await remove('delete from public.profiles where id=$1', [id]);
    await remove('delete from auth.users where id=$1', [id]);
    await remove('delete from public.user_role_journal where user_id=$1', [id]);
  }
  if (failures.length) throw new AggregateError(failures, 'Could not completely clean up owner-resume test fixtures');
}
