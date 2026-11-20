// Compare business behavior through 090 (before defective dual storage) against current.
// Both databases are isolated in-memory PGlite. No production connections.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createDb } from '../../supabase/tools/baseline-lib.mjs';

const owner = '11111111-1111-1111-1111-111111111111';
const user = '22222222-2222-2222-2222-222222222222';
const camp = '55555555-5555-5555-5555-555555555555';
const requestId = '00000000-0000-0000-0000-000000000099';
const quote = s => `'${String(s).replaceAll("'", "''")}'`;
const migrations = readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(f => /^\d+_.*\.sql$/.test(f)).sort();
async function setup(through) {
  const db = await createDb();
  for (const file of migrations.filter(f => Number(f.split('_')[0]) <= through)) {
    await db.exec(readFileSync(new URL(`../../supabase/migrations/${file}`, import.meta.url), 'utf8'));
  }
  await db.exec(`
    insert into auth.users(id,email) values ('${owner}','owner@regression.invalid'),('${user}','tester@regression.invalid');
    update public.profiles set credits=1000 where id in('${owner}','${user}');
    insert into public.campaigns(id,owner_id,app_name,package_name,status)
      values('${camp}','${owner}','Regression fixture','regression.fixture','open');
    insert into public.campaign_members(campaign_id,user_id) values('${camp}','${user}');
    select set_config('request.jwt.claim.sub','${user}',false);
  `);
  return db;
}
async function submit(db, { context = 'app_test', hash = '1111222233334444', content = 'Business regression', expectedDate, missionId } = {}) {
  const extra = [expectedDate ? `p_expected_date:=${expectedDate}` : '', missionId ? `p_mission_id:='${missionId}'::uuid` : ''].filter(Boolean).join(',');
  return (await db.query(`select public.submit_tester_report_v3(
    p_submission_context:=${quote(context)},p_campaign_id:='${camp}'::uuid,
    p_content:=${quote(content)},p_rating:=5,p_client_submit_id:='${requestId}'::uuid,
    p_attachments:=jsonb_build_array(jsonb_build_object(
      'screenshot_url','https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/${camp}/${user}/google.jpg',
      'image_hash',${quote(hash)},'sha256','${'a'.repeat(64)}','position',0))
    ${extra ? ',' + extra : ''}) as r`)).rows[0].r;
}
async function outcome(fn) {
  try { return { type: 'success', value: await fn() }; }
  catch (e) { return { type: 'error', code: String(e.message).split(':')[0] }; }
}
const cases = [
  ['regular_report_credit', async db => {
    const r = await submit(db);
    const p = (await db.query(`select credits from public.profiles where id='${user}'`)).rows[0];
    const c = (await db.query(`select count(*)::int as n from public.checkins where user_id='${user}'`)).rows[0];
    return { success:r.success, provisional:r.provisional_amount, credits:p.credits, checkins:c.n };
  }],
  ['locked_account', async db => {
    await db.exec(`update public.profiles set is_locked=true where id='${user}'`);
    return submit(db);
  }],
  ['wrong_expected_date', db => submit(db, { expectedDate: '(now() at time zone \'Asia/Ho_Chi_Minh\')::date - 2' })],
  ['invalid_image_hash', db => submit(db, { hash:'bad' })],
  ['invalid_submission_context', db => submit(db, { context:'unknown_context' })],
  ['missing_mission', db => submit(db, { context:'mission', missionId:'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' })],
  ['app_test_with_mission', db => submit(db, { context:'app_test', missionId:'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' })],
  ['same_request_changed_payload', async db => {
    await submit(db);
    return submit(db, { content:'Different canonical payload' });
  }],
  ['same_request_retry_once', async db => {
    const a=await submit(db), b=await submit(db);
    const p=(await db.query(`select credits from public.profiles where id='${user}'`)).rows[0];
    return { sameReport:a.report_id===b.report_id, credits:p.credits };
  }],
];
const baseline=await setup(90);
let current;
const results=[];
try {
  current=await setup(Infinity);
  for (const [name,fn] of cases) {
    const observed=[];
    for (const db of [baseline,current]) {
      await db.exec('begin');
      try { observed.push(await outcome(() => fn(db))); }
      finally { await db.exec('rollback'); }
    }
    // Positive cases return only business values. Negative cases should reject.
    for (const o of observed) if(o.type==='success' && o.value?.report_id) o.value={ unexpectedAccepted:true };
    try { assert.deepEqual(observed[1],observed[0]); results.push({name,status:'PASS'}); }
    catch { results.push({name,status:'FAIL',baseline:observed[0],current:observed[1]}); }
  }
  const report={scope:'Business behavior, isolated PGlite through 090 vs current',results};
  writeFileSync(new URL('supervisor-business-results.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report,null,2));
  process.exitCode=results.some(r=>r.status==='FAIL')?1:0;
} catch(e) { console.error('BLOCKED: current migration chain cannot load:',e.message); process.exitCode=1; }
finally { await baseline.close(); if(current) await current.close(); }
