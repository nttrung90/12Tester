import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'c1000000-0000-0000-0000-000000000001';
const other = 'c1000000-0000-0000-0000-000000000002';
const migration = 'supabase/migrations/089_fix_production_bonus_lifecycle.sql';
const q = async sql => (await db.query(sql)).rows[0];
let sequence = 0;

async function asUser(uid, action) {
  await db.exec('begin');
  try {
    await db.exec(`set local "request.jwt.claim.sub" = '${uid}'; set local role = authenticated;`);
    const result = await action();
    await db.exec('commit');
    return result;
  } catch (error) {
    await db.exec('rollback');
    throw error;
  }
}

async function campaign(status = 'open', reason = null, bonus = false) {
  sequence += 1;
  // Reuse finalized slots within the supported six-slot range. The slot
  // uniqueness guard remains enabled throughout the regression.
  const slot = (sequence % 6) + 1;
  const id = `b1000000-0000-0000-0000-${String(sequence).padStart(12, '0')}`;
  await db.exec(`
    insert into public.user_slots(user_id, slot_number, expires_at)
    select '${owner}'::uuid, ${slot}, now() + interval '30 days' where ${slot} > 1
    on conflict (user_id, slot_number) do nothing;
    insert into public.campaigns(id, owner_id, app_name, package_name, play_url,
        status, paused_reason, slot_number, target_testers, target_days, start_date)
    values ('${id}', '${owner}', 'Production regression ${sequence}', 'test.production.${sequence}',
        'https://play.google.com/store/apps/details?id=test.production.${sequence}',
        '${status}', ${reason ? `'${reason}'` : 'null'}, ${slot}, 12, 14,
        (now() at time zone 'Asia/Ho_Chi_Minh')::date);
  `);
  if (bonus) {
    await asUser(owner, () => db.query(`select public.create_campaign_tester_bonus(
      '${id}'::uuid, 5, 2, gen_random_uuid())`));
  }
  return id;
}

async function markProduction(id, uid = owner, extra = '') {
  return asUser(uid, () => db.query(`update public.campaigns
    set status = 'production', paused_reason = null ${extra}
    where id = '${id}' returning id, status, completed_at,
      tester_bonus_status, tester_bonus_remaining_slots`));
}

async function assertCompletedFeed(id) {
  await asUser(owner, async () => {
    const { feed } = await q('select public.get_home_feed() as feed');
    assert.equal(feed.completed.filter(c => c.id === id).length, 1);
    assert.equal(feed.open.some(c => c.id === id), false);
    assert.equal(feed.paused.some(c => c.id === id), false);
    const { sync } = await q("select public.get_home_feed_sync_v2('{}'::jsonb) as sync");
    assert.equal(sync.blocks.completed.value.filter(c => c.id === id).length, 1);
    assert.equal(sync.blocks.open.value.some(c => c.id === id), false);
    assert.equal(sync.blocks.paused.value.some(c => c.id === id), false);
  });
}

try {
  const migrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(name => /^\d{3}_.*\.sql$/.test(name) && Number(name.slice(0, 3)) <= 88).sort();
  for (const name of migrations) await db.exec(read(`supabase/migrations/${name}`));
  await db.exec(`
    insert into auth.users(id, email) values
      ('${owner}', 'production-owner@test.local'), ('${other}', 'production-other@test.local');
    update public.profiles set credits = 10000, role = 'member', is_locked = false
      where id in ('${owner}', '${other}');
  `);

  // Reproduce the exact Android PATCH using a real authenticated role, not a
  // definer/service role. No round is needed to trigger the original failure.
  const plain = await campaign();
  await assert.rejects(markProduction(plain), /TESTER_BONUS_SUMMARY_IMMUTABLE/);
  assert.equal((await q(`select status from public.campaigns where id = '${plain}'`)).status, 'open');
  console.log('PASS: reproduced pre-fix production failure without a bonus round');

  await db.exec(read(migration));
  await db.exec(read(migration)); // Migration is safe to rerun.
  await db.exec(read('supabase/migrations/090_daily_task_action_gate.sql'));
  await db.exec(read('supabase/migrations/090_daily_task_action_gate.sql'));


  const oldTokens = await asUser(owner, async () => {
    const { sync } = await q("select public.get_home_feed_sync_v2('{}'::jsonb) as sync");
    return Object.fromEntries(Object.entries(sync.blocks).map(([key, block]) => [key, block.token]));
  });
  const result = (await markProduction(plain)).rows[0];
  assert.equal(result.status, 'production');
  assert.ok(result.completed_at);
  assert.equal(result.tester_bonus_status, null);
  await assertCompletedFeed(plain);
  await asUser(owner, async () => {
    const { sync } = (await db.query('select public.get_home_feed_sync_v2($1::jsonb) as sync', [JSON.stringify(oldTokens)])).rows[0];
    assert.equal(sync.blocks.open.changed, true);
    assert.equal(sync.blocks.completed.changed, true);
    assert.equal(sync.blocks.open.value.some(c => c.id === plain), false);
    assert.equal(sync.blocks.completed.value.filter(c => c.id === plain).length, 1);
  });
  const replay = (await markProduction(plain)).rows[0];
  assert.deepEqual(replay, result);
  console.log('PASS: production succeeds, preserves no-bonus summary, moves to Completed, and replays safely');

  for (const reason of ['owner_manual', 'owner_inactive', 'slot_expired']) {
    const id = await campaign('paused', reason);
    assert.equal((await markProduction(id)).rows[0].status, 'production');
    await assertCompletedFeed(id);
  }
  console.log('PASS: owner can finalize manually/automatically/slot-expiry paused apps');

  const adminPaused = await campaign('paused', 'admin_manual');
  await assert.rejects(markProduction(adminPaused), /PAUSED_BY_ADMIN/);
  const foreign = await campaign();
  assert.equal((await markProduction(foreign, other)).rows.length, 0);
  assert.equal((await q(`select status from public.campaigns where id = '${foreign}'`)).status, 'open');
  console.log('PASS: admin pause and ownership restrictions remain enforced');

  const rewarded = await campaign('open', null, true);
  const before = await q(`select tester_bonus_round_id as round_id from public.campaigns where id = '${rewarded}'`);
  const balanceBefore = (await q(`select credits from public.profiles where id = '${owner}'`)).credits;
  await assert.rejects(markProduction(rewarded, owner, ", tester_bonus_credits = 999"), /TESTER_BONUS_SUMMARY_IMMUTABLE/);
  assert.equal((await q(`select status from public.campaign_tester_bonus_rounds where id = '${before.round_id}'`)).status, 'active');
  const finished = (await markProduction(rewarded)).rows[0];
  assert.equal(finished.tester_bonus_status, 'closing');
  assert.equal(finished.tester_bonus_remaining_slots, 0);
  const round = await q(`select * from public.campaign_tester_bonus_rounds where id = '${before.round_id}'`);
  assert.equal(round.status, 'closing');
  assert.ok(round.stopped_at);
  assert.equal(round.reserved_credits, round.remaining_credits + round.spent_credits + round.refunded_credits);
  assert.equal((await q(`select credits from public.profiles where id = '${owner}'`)).credits, balanceBefore);
  await assertCompletedFeed(rewarded);
  await markProduction(rewarded);
  assert.equal((await q(`select count(*)::int as n from public.campaign_tester_bonus_events where round_id = '${before.round_id}'`)).n, 1);
  console.log('PASS: bonus closes once, wallet is unchanged, and forged bonus summary is rejected');

  const automatic = await campaign('open', null, true);
  await db.exec(`update public.campaigns set status = 'completed' where id = '${automatic}'`);
  assert.equal((await q(`select tester_bonus_status from public.campaigns where id = '${automatic}'`)).tester_bonus_status, 'closing');
  await assertCompletedFeed(automatic);
  console.log('PASS: system completion retains bonus lifecycle and Completed feed');
} finally {
  await db.close();
}
