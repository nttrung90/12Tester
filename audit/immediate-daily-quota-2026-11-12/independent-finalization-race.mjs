import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {freshNative} from './fresh-native.mjs';
import {connect,setup,events,OWNER,ADMIN,TODAY,one} from './independent-fixture.mjs';
const results=[];
async function blocked(control,pid) {
  for(let i=0;i<100;i++) {
    const r=await one(control,'select pg_blocking_pids($1) b',[pid]);if(r.b.length)return r.b;
    await new Promise(r=>setTimeout(r,25));
  }
  throw Error('Expected real RPC to reach controlled gate');
}
for(const byAdmin of [false,true]) {
  const name='immediate_quota_native_finalize_'+Date.now()+(byAdmin?'a':'o');
  const control=await freshNative(name);let finalizer,remover;
  const label='yesterday finalization versus '+(byAdmin?'admin':'owner')+' removal';
  try {
    await control.query('begin');const {old}=await setup(control,{debt:12,streak:1});await events(control,1);
    await control.query(`update public.user_daily_tasks set task_date=${TODAY}-2 where user_id='${OWNER}' and task_date=${TODAY}-1;
      alter table public.daily_task_immediate_quota_policies disable trigger user;
      insert into public.daily_task_immediate_quota_policies(effective_from,is_enabled,operator_note) values(${TODAY}-3,true,'private finalization race seed') on conflict(effective_from) do update set is_enabled=true;
      alter table public.daily_task_immediate_quota_policies enable trigger user;
      create function public.supervisor_finalization_gate() returns trigger language plpgsql as $$begin
        if new.user_id='${OWNER}' and new.task_date=${TODAY}-1 then perform pg_advisory_xact_lock(88120002);end if;return new;end$$;
      create trigger supervisor_finalization_gate before insert on public.user_daily_tasks for each row execute function public.supervisor_finalization_gate();
      commit;select pg_advisory_lock(88120002)`);
    finalizer=await connect(name);remover=await connect(name);
    await finalizer.query('begin');await remover.query('begin;set local role authenticated');
    await remover.query("select set_config('request.jwt.claim.sub',$1,true)",[byAdmin?ADMIN:OWNER]);
    const a=finalizer.query(`select public._apply_daily_inactivity_penalty(${TODAY}-1,${TODAY}-1)`)
      .then(async()=>{await finalizer.query('commit');return {ok:true};})
      .catch(async e=>{await finalizer.query('rollback');return {ok:false,code:e.code,message:e.message};});
    await blocked(control,finalizer.processID);
    const b=remover.query(byAdmin?'select public.admin_delete_campaign($1) r':'select public.delete_owned_campaign($1) r',[old])
      .then(async r=>{await remover.query('commit');return {ok:true,result:r.rows[0].r};})
      .catch(async e=>{await remover.query('rollback');return {ok:false,code:e.code,message:e.message};});
    const blockers=await blocked(control,remover.processID);
    await control.query('select pg_advisory_unlock(88120002)');
    const [finalize,remove]=await Promise.all([a,b]);const row={label,blockers,finalize,remove};results.push(row);console.log(JSON.stringify(row));
    assert.equal(finalize.ok,true);assert.equal(remove.ok,true);assert.equal(remove.result.success,true);row.pass=true;
  }catch(e){console.error('FAIL',label,e.message);if(!results.some(x=>x.label===label))results.push({label,error:e.message});process.exitCode=1;}
  finally {await control.query('select pg_advisory_unlock_all()').catch(()=>{});await Promise.allSettled([finalizer?.end(),remover?.end()]);await control.end();}
}
writeFileSync(new URL('./independent-finalization-race-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');
