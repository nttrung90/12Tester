import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db = await createDb();
try {
  for (const f of readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(f => /^0\d\d.*\.sql$/.test(f) && +f.slice(0,3) <= 70).sort()) await db.exec(read('supabase/migrations/' + f));
  await db.exec(read('audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql'));
  const owner='11111111-1111-1111-1111-111111111111', tester='22222222-2222-2222-2222-222222222222';
  const camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', rep='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  await db.exec(`insert into auth.users(id,email) values ('${owner}','owner@fixture.invalid'),('${tester}','tester@fixture.invalid');
  insert into public.campaigns(id,owner_id,app_name,package_name,status) values ('${camp}','${owner}','Bonus audit','invalid.audit.bonus','open');
  insert into public.campaign_members(campaign_id,user_id) values ('${camp}','${tester}');
  insert into public.tester_reports(id,campaign_id,user_id,status,content,provisional_granted_credits,created_at) values ('${rep}','${camp}','${tester}','pending','test',2,now());
  begin; select set_config('request.jwt.claim.sub','${owner}',true); set local role authenticated;`);
  const res=(await db.query(`select public.approve_tester_report('${rep}',1000) as r`)).rows[0].r;
  await db.exec('commit');
  console.log(JSON.stringify({requestedBonus:1000,awarded:res.credits_awarded,ownerAwarded:res.owner_credits_awarded},null,2));
  assert.equal(res.credits_awarded,2,'Compatibility p_bonus must retain original069 behavior and cannot override configured reviewer bonus');
} finally { await db.close(); }
