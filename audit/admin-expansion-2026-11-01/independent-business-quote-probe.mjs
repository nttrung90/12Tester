import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
import { readdirSync, writeFileSync, existsSync } from 'node:fs';
const db=await createDb(), output=[];
const admin='11111111-1111-1111-1111-111111111111', user='22222222-2222-2222-2222-222222222222';
async function actor(uid,sql){await db.exec(`begin;select set_config('request.jwt.claim.sub','${uid}',true);set local role authenticated;`);try{const r=(await db.query(sql)).rows;await db.exec('commit');return r;}catch(e){await db.exec('rollback');throw e;}}
try {
  for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^0\d\d.*\.sql$/.test(n)&&+n.slice(0,3)<=69).sort())await db.exec(read('supabase/migrations/'+name));
  await db.exec(read('audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql'));
  await db.exec(`insert into auth.users(id,email)values('${admin}','admin@fixture.invalid'),('${user}','user@fixture.invalid');update public.profiles set role='admin'where id='${admin}';`);
  const cfg=(await actor(admin,'select public.admin_get_business_policy_config() as r'))[0].r;
  await actor(admin,`select public.admin_schedule_business_policy(p_effective_from=>(clock_timestamp()at time zone'Asia/Ho_Chi_Minh')::date+1,p_operator_note=>'Quote activation test',p_expected_revision=>'${cfg.revision}',p_slot_renewal_days=>1,p_app_update_reward_per_claim=>4,p_app_update_max_claims=>30)`);
  const slotBefore=(await actor(user,`select public.get_business_quote('slot','{"slot_number":2}')as r`))[0].r;
  const updateBefore=(await actor(user,`select public.get_business_quote('app_update')as r`))[0].r;
  // Test-only date seam: exercise the instant a pre-scheduled row becomes active,
  // without editing policies or their administrator-CAS revision between quotes.
  await db.exec(`create or replace function public._get_effective_business_policy_row(p_date date default null::date)returns public.business_policies language sql stable security definer set search_path=''as $$select p.*from public.business_policies p where p.effective_from<=coalesce(p_date,(clock_timestamp()at time zone'Asia/Ho_Chi_Minh')::date+1)order by p.effective_from desc limit1$$;` .replace('limit1','limit 1'));
  const slotAfter=(await actor(user,`select public.get_business_quote('slot','{"slot_number":2}')as r`))[0].r;
  const updateAfter=(await actor(user,`select public.get_business_quote('app_update')as r`))[0].r;
  output.push({name:'quote revision changes when scheduled policy activates without DB edit',slotBefore,slotAfter,slotRevisionChanged:slotBefore.revision!==slotAfter.revision,updateBefore,updateAfter,updateRevisionChanged:updateBefore.revision!==updateAfter.revision});
}catch(e){output.push({fatal:e.message});}finally{await db.close();}
const file=new URL('./independent-business-quote-probe-results.json',import.meta.url);
writeFileSync(file,JSON.stringify(output,null,2));
const initial=new URL('./independent-business-quote-initial.json',import.meta.url);if(!existsSync(initial))writeFileSync(initial,JSON.stringify(output,null,2));
console.log(JSON.stringify(output,null,2));
