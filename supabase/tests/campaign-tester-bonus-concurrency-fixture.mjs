import crypto from 'node:crypto';
import assert from 'node:assert/strict';

export function makePhotoUrl(campId, userId, name) {
  return `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${userId}%2F${name}.jpg?alt=media&token=fixture`;
}

export function makeThumbUrl(campId, userId, name = 'photo') {
  return `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campId}%2F${userId}%2Fthumb_${name}.jpg?alt=media&token=fixture`;
}

export function makeBonusConcurrencyFixture() {
  const runId = crypto.randomBytes(6).toString('hex');
  return {
    runId,
    ownerId: crypto.randomUUID(),
    adminId: crypto.randomUUID(),
    tester1Id: crypto.randomUUID(),
    tester2Id: crypto.randomUUID(),
    tester3Id: crypto.randomUUID(),
    camp1Id: crypto.randomUUID(),
    camp2Id: crypto.randomUUID(),
    camp3Id: crypto.randomUUID(),
    report1Id: crypto.randomUUID(),
    report2Id: crypto.randomUUID(),
    report3Id: crypto.randomUUID(),
  };
}

export async function prepareBonusConcurrencyFixture(client, f) {
  // 1. Create auth users & profiles
  for (const [id, role, credits] of [
    [f.ownerId, 'member', 150], // Initial 150 credits for wallet contention test (2 apps x 100 credits = 200 > 150)
    [f.adminId, 'admin', 1000],
    [f.tester1Id, 'member', 10],
    [f.tester2Id, 'member', 10],
    [f.tester3Id, 'member', 10],
  ]) {
    await client.query('insert into auth.users (id, email) values ($1, $2)', [id, `${role}-${f.runId}-${id.slice(0, 8)}@test.invalid`]);
    await client.query(`update public.profiles set role = $2, is_locked = false, credits = $3,
      created_at = now() - interval '30 days' where id = $1`, [id, role, credits]);
  }

  // 2. User slots for owner: slot 1 is free; paid slots 2 and 3 ONLY
  for (let s = 2; s <= 3; s++) {
    await client.query(`insert into public.user_slots (user_id, slot_number, expires_at)
      values ($1, $2, now() + interval '30 days')
      on conflict (user_id, slot_number) do update set expires_at = now() + interval '30 days'`, [f.ownerId, s]);
  }

  // 3. Campaigns (max 3 campaigns per owner: slot 1, 2, 3)
  for (const [idx, id] of [f.camp1Id, f.camp2Id, f.camp3Id].entries()) {
    await client.query(`insert into public.campaigns (id, owner_id, app_name, package_name, status, slot_number, created_at)
      values ($1, $2, $3, $4, 'open', $5, now() - interval '10 days')`,
    [id, f.ownerId, `Bonus App ${idx + 1}`, `com.concurrency.bonus.${f.runId}.app${idx + 1}`, idx + 1]);
  }
}

export async function cleanupBonusConcurrencyFixture(client, f) {
  const failures = [];
  const remove = async (sql, params) => {
    try { await client.query(sql, params); } catch (error) { failures.push(error); }
  };

  const campIds = [f.camp1Id, f.camp2Id, f.camp3Id];
  const userIds = [f.ownerId, f.adminId, f.tester1Id, f.tester2Id, f.tester3Id];

  for (const cId of campIds) {
    await remove('delete from public.tester_reports where campaign_id = $1', [cId]);
    await remove('delete from public.campaign_tester_bonus_awards where campaign_id = $1', [cId]);
    await remove('delete from public.campaign_tester_bonus_events where campaign_id = $1', [cId]);
    await remove('delete from public.campaign_tester_bonus_rounds where campaign_id = $1', [cId]);
    await remove('delete from public.campaign_status_journal where campaign_id = $1', [cId]);
    await remove('delete from public.campaign_archive where campaign_id = $1', [cId]);
    await remove('delete from public.campaigns where id = $1', [cId]);
  }

  for (const uId of userIds) {
    for (const table of ['daily_test_events_journal', 'user_daily_tasks', 'user_slots', 'notifications', 'credit_logs']) {
      await remove(`delete from public.${table} where user_id = $1`, [uId]);
    }
    await remove('delete from public.profiles where id = $1', [uId]);
    await remove('delete from auth.users where id = $1', [uId]);
    await remove('delete from public.user_role_journal where user_id = $1', [uId]);
  }

  if (failures.length > 0) {
    throw new AggregateError(failures, 'Could not completely clean up bonus concurrency test fixtures');
  }
}
