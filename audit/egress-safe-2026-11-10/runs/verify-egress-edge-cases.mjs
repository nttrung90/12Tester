import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../../supabase/tools/baseline-lib.mjs';

const db = await createDb();
const owner = 'f9000000-0000-0000-0000-000000000001';
const tester = 'f9000000-0000-0000-0000-000000000002';
const outsider = 'f9000000-0000-0000-0000-000000000003';
const campaign = 'f9000000-0000-0000-0000-000000000004';
const reportId = n => `f9100000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const val = async (sql, args = []) => (await db.query(sql, args)).rows[0].value;
const full = response => Object.fromEntries(Object.entries(response.blocks).map(([k, b]) => {
  assert.equal(b.changed, true);
  assert.equal(b.token.length, 64);
  assert.ok(Object.hasOwn(b, 'value'));
  return [k, b.value];
}));
const tokens = response => Object.fromEntries(Object.entries(response.blocks).map(([k, b]) => [k, b.token]));
async function login(uid) {
  await db.exec('reset role');
  await db.query(`select set_config('request.jwt.claim.sub', $1, false)`, [uid ?? '']);
  await db.query(`select set_config('request.jwt.claims', $1, false)`, [JSON.stringify(uid ? { sub: uid, role: 'authenticated' } : { role: 'anon' })]);
  await db.exec(uid ? 'set role authenticated' : 'set role anon');
}

try {
  for (const name of readdirSync(new URL('../../../supabase/migrations/', import.meta.url)).filter(n => /^\d{3}_.*\.sql$/.test(n)).sort()) {
    await db.exec(read(`supabase/migrations/${name}`));
  }
  await db.exec(`
    insert into auth.users(id,email) values
      ('${owner}','egress-owner@fixture.invalid'),
      ('${tester}','egress-tester@fixture.invalid'),
      ('${outsider}','egress-outsider@fixture.invalid');
    update public.profiles set credits=10000, last_login_date=(now() at time zone 'Asia/Ho_Chi_Minh')::date;
    insert into public.campaigns(id,owner_id,app_name,package_name,status,test_username,test_password,description)
      values('${campaign}','${owner}','Large campaign','fixture.egress.large','open','test-user','test-password','Keep full description');
    insert into public.campaign_members(campaign_id,user_id) values('${campaign}','${tester}');
  `);
  for (let n = 1; n <= 205; n++) {
    await db.query(`insert into public.tester_reports(id,campaign_id,user_id,content,rating,status,report_date,created_at,submission_context,screenshot_url)
      values($1,$2,$3,$4,5,$5,(now() at time zone 'Asia/Ho_Chi_Minh')::date-$6::int,now()-make_interval(secs=>$6::int),'app_test',$7)`,
    [reportId(n), campaign, tester, `Report ${n}`, n === 205 ? 'pending' : 'approved', n,
      `https://fixture.invalid/report-${n}.jpg`]);
  }
  for (let position = 0; position < 3; position++) {
    await db.query(`insert into public.tester_report_attachments(report_id,position,screenshot_url,thumbnail_url,image_hash,sha256)
      values($1,$2,$3,$4,'0123456789abcdef',$5)`,
    [reportId(1), position, `https://fixture.invalid/proof-${position}.jpg`, `https://fixture.invalid/thumb-${position}.jpg`, `fixture-sha-${position}`]);
  }

  await login(owner);
  const ownerSync = await val('select public.get_campaign_detail_sync_v2($1) as value', [campaign]);
  const ownerLegacy = await val('select public.get_campaign_detail($1) as value', [campaign]);
  const ownerState = full(ownerSync);
  assert.deepEqual(ownerState, ownerLegacy);
  assert.equal(ownerState.reports.length, 200);
  assert.equal(ownerState.tester_reports_count, 205);
  assert.equal(ownerState.pending_reports_count, 1);
  assert.ok(!ownerState.reports.some(r => r.id === reportId(205)), 'Old pending count must remain exact even outside legacy 200-item window');
  assert.equal(ownerState.reports[0].attachments.length, 3);
  assert.equal(ownerState.reports[0].attachments[2].sha256, 'fixture-sha-2');
  assert.equal(ownerState.campaign.test_password, 'test-password');
  console.log('PASS: 205 reports preserve legacy window=200, old pending aggregate, 3 attachments, credentials and all fields');
  const unchangedDetail = await val('select public.get_campaign_detail_sync_v2($1,$2::jsonb) as value', [campaign, JSON.stringify(tokens(ownerSync))]);
  assert.ok(Object.values(unchangedDetail.blocks).every(b => b.changed === false));
  const legacyDetailBytes = Buffer.byteLength(JSON.stringify(ownerLegacy));
  const unchangedDetailBytes = Buffer.byteLength(JSON.stringify(unchangedDetail));
  assert.ok(unchangedDetailBytes < legacyDetailBytes * 0.1, 'Large unchanged detail fixture should remove at least 90% of decoded response bytes');
  console.log(`MEASURE: large detail decoded JSON bytes legacy=${legacyDetailBytes}, unchanged=${unchangedDetailBytes}, reduction=${(100 * (1 - unchangedDetailBytes / legacyDetailBytes)).toFixed(2)}%; not production wire egress`);

  await login(tester);
  const testerSync = await val('select public.get_campaign_detail_sync_v2($1) as value', [campaign]);
  assert.deepEqual(full(testerSync), await val('select public.get_campaign_detail($1) as value', [campaign]));
  assert.equal(testerSync.blocks.can_manage.value, false);
  assert.ok(testerSync.blocks.reports.value.every(r => r.user_id === tester));
  console.log('PASS: Tester full response matches legacy and report visibility remains scoped');

  await login(outsider);
  const outsiderSync = await val('select public.get_campaign_detail_sync_v2($1,$2::jsonb) as value', [campaign, JSON.stringify(tokens(ownerSync))]);
  assert.equal(outsiderSync.blocks.can_manage.changed, true);
  assert.equal(outsiderSync.blocks.can_manage.value, false);
  assert.equal(outsiderSync.blocks.reports.changed, true);
  assert.deepEqual(outsiderSync.blocks.reports.value, []);
  console.log('PASS: Reusing another user token cannot retain owner privileges or report list');

  await login(owner);
  const oldTokens = tokens(ownerSync);
  await db.exec('reset role');
  await db.query(`update public.profiles set display_name='Updated remotely' where id=$1`, [tester]);
  await login(owner);
  const profileChange = await val('select public.get_campaign_detail_sync_v2($1,$2::jsonb) as value', [campaign, JSON.stringify(oldTokens)]);
  assert.equal(profileChange.blocks.profiles.changed, true);
  assert.equal(profileChange.blocks.profiles.value[tester].display_name, 'Updated remotely');
  assert.equal(profileChange.blocks.reports.changed, false);
  assert.ok(!Object.hasOwn(profileChange.blocks.reports, 'value'));
  console.log('PASS: Remote profile change updates profiles without retransmitting report body');

  const firstDash = await val('select public.get_my_dashboard_sync_v2() as value');
  const unchangedDash = await val('select public.get_my_dashboard_sync_v2($1::jsonb) as value', [JSON.stringify(tokens(firstDash))]);
  assert.ok(Object.values(unchangedDash.blocks).every(b => b.changed === false));
  const nullBlock = Object.values(ownerSync.blocks).find(b => b.value === null);
  assert.ok(nullBlock, 'Fixture must contain at least one explicit JSON-null block');
  const initialBytes = Buffer.byteLength(JSON.stringify(firstDash));
  const unchangedBytes = Buffer.byteLength(JSON.stringify(unchangedDash));
  console.log(`MEASURE: dashboard JSON decoded bytes full=${initialBytes}, unchanged=${unchangedBytes}; not production wire egress`);

  await db.exec('reset role');
  for (let n = 1; n <= 15; n++) {
    const supplier = `f9200000-0000-0000-0000-${String(n).padStart(12, '0')}`;
    await db.query(`insert into auth.users(id,email) values($1,$2)`, [supplier, `supplier-${n}@fixture.invalid`]);
    await db.query(`insert into public.campaigns(owner_id,app_name,package_name,status,created_at)
      values($1,$2,$3,'open',now()-make_interval(secs=>$4::int))`, [supplier, `Candidate ${n}`, `fixture.candidate.${n}`, n]);
  }
  await login(tester);
  const candidates = await val('select public.get_next_campaign_candidates_v2($1) as value', [campaign]);
  const feed = await val('select public.get_home_feed() as value');
  const expected = feed.open.filter(c => c.id !== campaign && c.owner_id !== tester && feed.stats[c.id] && !feed.stats[c.id].has_tested_today)
    .map(c => ({ campaign: c, installed_count: feed.stats[c.id].installed_count }));
  assert.equal(candidates.length, 15);
  assert.deepEqual(candidates, expected);
  console.log('PASS: Candidate universe >10 remains complete with legacy order and fields');

  await login(null);
  await assert.rejects(() => val('select public.get_my_dashboard_sync_v2() as value'), /permission denied|NOT_AUTHENTICATED/i);
  await assert.rejects(() => val('select public.get_campaign_detail_sync_v2($1) as value', [campaign]), /permission denied|NOT_AUTHENTICATED/i);
  console.log('PASS: Anonymous caller cannot execute authenticated dashboard/detail');
  console.log('ALL INDEPENDENT EDGE CASE CHECKS PASSED');
} finally {
  await db.close();
}
