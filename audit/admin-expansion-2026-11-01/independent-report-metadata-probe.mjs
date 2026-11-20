import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
// Supabase inherited table grants matter: a fixture with no grants masks trigger bugs.
const db = await createDb({ legacyGrants: true });
try {
  for (const f of readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(f => /^0\d\d.*\.sql$/.test(f) && +f.slice(0,3) <= 70).sort()) await db.exec(read('supabase/migrations/' + f));
  await db.exec(read('audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql'));
  const owner='11111111-1111-1111-1111-111111111111', tester='22222222-2222-2222-2222-222222222222';
  const camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', rep='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  await db.exec(`insert into auth.users(id,email) values ('${owner}','owner@fixture.invalid'),('${tester}','tester@fixture.invalid');
  insert into public.campaigns(id,owner_id,app_name,package_name,status) values ('${camp}','${owner}','Metadata audit','invalid.audit.metadata','open');
  insert into public.campaign_members(campaign_id,user_id) values ('${camp}','${tester}');
  insert into public.tester_reports(id,campaign_id,user_id,status,content,provisional_granted_credits,created_at) values ('${rep}','${camp}','${tester}','pending','test',2,now());`);
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${tester}',true); set local role authenticated;`);
  const roles=(await db.query('select session_user, current_user')).rows[0];
  let rejected=false, error=null;
  try { await db.query(`update public.tester_reports set review_tester_bonus=999 where id='${rep}' returning id`); }
  catch(e) { rejected=true; error=e.message; }
  await db.exec('rollback');
  console.log(JSON.stringify({roles,rejected,error},null,2));
  assert.ok(rejected,'Authenticated direct DML must not forge a reviewer reward snapshot under inherited Supabase table grants');
} finally { await db.close(); }
