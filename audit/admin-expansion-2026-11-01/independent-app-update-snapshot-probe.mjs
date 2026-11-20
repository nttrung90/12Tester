import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
const admin='11111111-1111-1111-1111-111111111111', owner='22222222-2222-2222-2222-222222222222', camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const testers=Array.from({length:26},(_,i)=>'33333333-3333-3333-3333-'+String(i+1).padStart(12,'0'));
async function actor(uid,sql) {
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${uid}',true); set local role authenticated;`);
  try {const r=(await db.query(sql)).rows[0].r;await db.exec('commit');return r;}
  catch(e){await db.exec('rollback');throw e;}
}
try {
  for(const f of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^0\d\d.*\.sql$/.test(f)&&+f.slice(0,3)<=70).sort())await db.exec(read('supabase/migrations/'+f));
  await db.exec(read('audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql'));
  await db.exec(`insert into auth.users(id,email) values ('${admin}','admin@fixture.invalid'),('${owner}','owner@fixture.invalid'),${testers.map((id,i)=>"('"+id+"','tester"+i+"@fixture.invalid')").join(',')};
    update public.profiles set role='admin' where id='${admin}';
    update public.profiles set credits=10000 where id='${owner}';
    update public.profiles set credits=100 where id=any(array[${testers.map(id=>"'"+id+"'::uuid").join(',')}]);
    insert into public.campaigns(id,owner_id,app_name,package_name,status) values ('${camp}','${owner}','Snapshot probe','invalid.fixture.snapshot','open');
    insert into public.campaign_members(campaign_id,user_id,joined_at) select '${camp}',id,now()-interval '1 day' from public.profiles where id=any(array[${testers.map(id=>"'"+id+"'::uuid").join(',')}]);`);
  let cfg=await actor(admin,'select public.admin_get_business_policy_config() as r');
  const today=cfg.today;
  cfg=await actor(admin,`select public.admin_schedule_business_policy(p_effective_from=>'${today}'::date+1,p_operator_note=>'Fixture8x25',p_expected_revision=>'${cfg.revision}',p_app_update_reward_per_claim=>8,p_app_update_max_claims=>25,p_request_id=>'dddddddd-dddd-dddd-dddd-ddddddddddd1') as r`);
  await actor(admin,`select public.admin_schedule_business_policy(p_effective_from=>'${today}'::date+2,p_operator_note=>'Fixture4x30',p_expected_revision=>'${cfg.revision}',p_app_update_reward_per_claim=>4,p_app_update_max_claims=>30,p_request_id=>'dddddddd-dddd-dddd-dddd-ddddddddddd2') as r`);
  // Isolated clock seam: activate real scheduled rows without editing any policy row.
  await db.exec(`create or replace function public._get_effective_business_policy_row(p_date date default null) returns public.business_policies language plpgsql stable security definer set search_path='' as $fixture$
    declare v public.business_policies; d date:=coalesce(nullif(current_setting('fixture.business_date',true),'')::date,p_date,(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date);
    begin select * into v from public.business_policies where effective_from<=d order by effective_from desc limit 1;return v;end;$fixture$;`);
  const old=await actor(owner,`select public.publish_app_update('${camp}','v1',1,'Default6x20','independent-old',null) as r`);
  await db.exec(`select set_config('fixture.business_date',('${today}'::date+1)::text,false)`);
  const oldClaim=await actor(testers[0],`select public.claim_app_update_reward('${old.release.id}',1) as r`);
  assert.equal(oldClaim.reward,6,'A fresh claim on an existing release must retain6 after policy changed to8');
  const quote=await actor(owner,"select public.get_business_quote('app_update') as r");
  const fresh=await actor(owner,`select public.publish_app_update_v2('${camp}','v2',2,'Snapshot8x25','independent-new',200,'${quote.revision}','${old.release.id}') as r`);
  assert.equal(fresh.refund_amount,114);
  for(let i=0;i<25;i++) {
    if(i===12)await db.exec(`select set_config('fixture.business_date',('${today}'::date+2)::text,false)`);
    const claim=await actor(testers[i],`select public.claim_app_update_reward('${fresh.release.id}',2) as r`);
    assert.equal(claim.reward,8,'Claim'+(i+1)+' must use release8 snapshot');
    assert.ok(claim.receipt.claim_id && claim.receipt.release_id===fresh.release.id,'Fresh receipt retains full identity DTO');
  }
  await assert.rejects(actor(testers[25],`select public.claim_app_update_reward('${fresh.release.id}',2) as r`),/RELEASE_EXHAUSTED/);
  const releases=(await db.query('select reserved_credits,paid_count,reward_per_claim,remaining_credits,refunded_credits from public.app_update_releases')).rows;
  for(const r of releases)assert.equal(r.reserved_credits,r.paid_count*r.reward_per_claim+r.remaining_credits+r.refunded_credits);
  const payouts=(await db.query("select sum(amount)::int n from public.app_update_financial_events where event_type='reward'")).rows[0].n;
  assert.equal(payouts,206);
  console.log(JSON.stringify({oldReleaseRewardAfterActivation:oldClaim.reward,nonDefaultClaims:25,rewardEach:8,rejected26th:true,paidAcrossReleases:payouts,escrowBalanced:true},null,2));
} finally {await db.close();}
