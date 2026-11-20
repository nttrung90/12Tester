// Actual authenticated RPCs overlap only after their canonical locks.
import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {freshNative} from './fresh-native.mjs';
import {connect,setup,OWNER,ADMIN,TODAY,status,one} from './independent-fixture.mjs';
const results=[];
async function waitBlocked(observer,pid) {
  for(let i=0;i<100;i++) {
    const r=await one(observer,'select pg_blocking_pids($1) blockers',[pid]);
    if(r.blockers.length)return r.blockers;
    await new Promise(r=>setTimeout(r,25));
  }
  throw new Error('Expected RPC did not reach controlled lock gate');
}
for(const adminRemoval of [false,true]) {
  const name='immediate_quota_native_race_'+Date.now()+(adminRemoval?'a':'o');
  const control=await freshNative(name);let creator,remover;
  const label='create versus '+(adminRemoval?'admin':'owner')+' removal';
  try {
    await control.query('begin');const {old}=await setup(control,{debt:24,streak:2});
    await control.query(`alter table public.daily_task_immediate_quota_policies disable trigger user;
      insert into public.daily_task_immediate_quota_policies(effective_from,is_enabled,operator_note) values(${TODAY}-1,true,'private native race fixture') on conflict(effective_from) do update set is_enabled=true;
      alter table public.daily_task_immediate_quota_policies enable trigger user;
      create function public.supervisor_creation_gate() returns trigger language plpgsql as $$begin
        if new.package_name='supervisor.race.creation' then perform pg_advisory_xact_lock(88120001);end if;return new;end$$;
      create trigger supervisor_creation_gate before insert on public.campaigns for each row execute function public.supervisor_creation_gate();
      commit;select pg_advisory_lock(88120001)`);
    creator=await connect(name);remover=await connect(name);
    for(const [db,uid] of [[creator,OWNER],[remover,adminRemoval?ADMIN:OWNER]]) {
      await db.query('begin;set local role authenticated');await db.query("select set_config('request.jwt.claim.sub',$1,true)",[uid]);
    }
    const quote=(await one(creator,"select public.get_business_quote('all') q")).q;
    const creation=creator.query(`select public.create_campaign_v2('Race app','supervisor.race.creation','','','Race fixture',12,'','','',2,$1,$2,$3,$4) r`,
      [randomUUID(),quote.target_fees.target_12,quote.min_credits_to_post_app,quote.revision])
      .then(async r=>{await creator.query('commit');return {ok:true,result:r.rows[0].r};})
      .catch(async e=>{await creator.query('rollback');return {ok:false,code:e.code,message:e.message};});
    await waitBlocked(control,creator.processID);
    const removal=remover.query(adminRemoval?'select public.admin_delete_campaign($1) r':'select public.delete_owned_campaign($1) r',[old])
      .then(async r=>{await remover.query('commit');return {ok:true,result:r.rows[0].r};})
      .catch(async e=>{await remover.query('rollback');return {ok:false,code:e.code,message:e.message};});
    const blockers=await waitBlocked(control,remover.processID);
    await control.query('select pg_advisory_unlock(88120001)');
    const [a,b]=await Promise.all([creation,removal]);
    const s=await status(control);const row={label,blockers,a,b,base:s.base_required_tests,carry:s.carry_in_tests};results.push(row);
    console.log(JSON.stringify(row));
    assert.equal(a.ok,true,label+' creation SQL must succeed');assert.equal(a.result.success,true);
    assert.equal(b.ok,true,label+' removal SQL must succeed');assert.equal(b.result.success,true);
    assert.equal(s.base_required_tests,12);assert.equal(s.carry_in_tests,24);
    row.pass=true;
  }catch(e){console.error('FAIL',label,e.message);if(!results.some(x=>x.label===label))results.push({label,error:e.message});process.exitCode=1;}
  finally {await control.query('select pg_advisory_unlock_all()').catch(()=>{});await Promise.allSettled([creator?.end(),remover?.end()]);await control.end();}
}
writeFileSync(new URL('./independent-concurrency-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');
