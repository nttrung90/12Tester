// Supervisor-owned probes for regressions introduced by the first migration draft.
import {readFileSync,writeFileSync} from 'node:fs';
const here=new URL('./',import.meta.url);
let source=readFileSync(new URL('before/audit/carryover-recheck-2026-11-05/recheck.mjs',here),'utf8');
source=source.slice(0,source.indexOf("  await scenario('Four days:"));
source=source.replace("new URL('./results.json',import.meta.url)","new URL('./safety-results.json',import.meta.url)");
source+=String.raw`
  async function asActor(uid,sql) {
    await db.exec('savepoint actor_call');
    await db.exec(\`set request.jwt.claim.sub='\${uid}';set role authenticated\`);
    try {const result=await db.query(sql);await db.exec('reset role;reset request.jwt.claim.sub;release savepoint actor_call');return result;}
    catch(e){await db.exec('rollback to savepoint actor_call;release savepoint actor_call');throw e;}
  }
  for(const sql of ['select public.apply_daily_inactivity_penalty()', 'select public.finalize_daily_tasks_for_date('+today+'-1)']) {
    await scenario('Ordinary members cannot execute privileged finalization: '+sql,async()=>{
      await assert.rejects(()=>asActor(owner,sql),/permission|PERMISSION|ADMIN|FORBIDDEN/);return {denied:true};
    });
  }
  for(const name of ['diagnose_daily_task_stuck_users','audit_carryover_journal_reconciliation']) {
    await scenario('Unlocked administrator can inspect private diagnostic: '+name,async()=>{
      const admin='c4000000-0000-0000-0000-000000000001';
      const result=await asActor(admin,'select * from public.'+name+'()');return {authorized:true,rows:result.rows.length};
    });
    await scenario('Trusted service can inspect private diagnostic: '+name,async()=>{
      await db.exec('set role service_role');
      const result=await db.query('select * from public.'+name+'()');await db.exec('reset role');return {authorized:true,rows:result.rows.length};
    });
    await scenario('Unidentified client cannot inspect all users: '+name,async()=>{
      await assert.rejects(()=>asActor('','select * from public.'+name+'()'),/permission|PERMISSION|ADMIN|FORBIDDEN|AUTH/);return {denied:true};
    });
    await scenario('Locked administrator cannot use private diagnostic: '+name,async()=>{
      const admin='c4000000-0000-0000-0000-000000000001';
      await db.exec(\`update public.profiles set is_locked=true where id='\${admin}'\`);
      await assert.rejects(()=>asActor(admin,'select * from public.'+name+'()'),/permission|PERMISSION|ADMIN|FORBIDDEN|LOCKED/);return {denied:true};
    });
  }
  await scenario('Trusted service retains access to scheduled and explicit finalization',async()=>{
    const exposed=(await db.query("select p.oid::regprocedure::text signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname like '_apply_daily_inactivity_penalty%' and has_function_privilege('authenticated',p.oid,'EXECUTE')")).rows;
    assert.deepEqual(exposed,[],'Internal financial helpers, including any new scoped overload, must not be executable by clients');
    await db.exec('set role service_role');
    await db.exec('select public.apply_daily_inactivity_penalty()');
    await db.exec('select public.finalize_daily_tasks_for_date('+today+'-1)');
    await db.exec('reset role');return {authorized:true};
  });
  await scenario('An unresolved old user cannot starve healthy users awaiting recent finalization',async()=>{
    await checkpoint(40,8);
    await db.exec(\`insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,activity_date,status,is_qualifying,observed_at,submission_context,reconciliation_status)
      select 'report',gen_random_uuid(),'\${owner}',id,owner_id,\${today}-39,'pending',true,
      (\${today}-39)::timestamp at time zone 'Asia/Ho_Chi_Minh',null,'unresolved_missing_source'
      from public.campaigns where id='\${supply[0]}'\`);
    const healthy='c4000000-0000-0000-0000-000000000001';let state;
    for(let i=0;i<6;i++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      state=(await q(\`select public.get_daily_task_status('\${healthy}',\${today}) s\`)).s;
      if(state.carryover_ready) break;
    }
    assert.equal(state.carryover_ready,true,'Healthy users must advance beyond another user\'s unresolved source date');
    assert.equal((await status()).carryover_ready,false,'The unknown history must remain unresolved');return state;
  });
  for(const partial of [false,true]) await scenario('Unprovable coverage boundary cannot starve healthy users: partial='+partial,async()=>{
    await db.exec(\`update public.profiles set created_at=now()-interval '90 days' where id='\${owner}'\`);
    if(partial) {
      await checkpoint(61,8);
      await db.exec(\`update public.system_provenance_markers set marker_time=(\${today}-60)::timestamp at time zone 'Asia/Ho_Chi_Minh'+interval '12 hours' where marker_key='coverage_start'\`);
    }
    const healthy='c4000000-0000-0000-0000-000000000001';let state;
    for(let i=0;i<6;i++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      state=(await q(\`select public.get_daily_task_status('\${healthy}',\${today}) s\`)).s;
      if(state.carryover_ready) break;
    }
    assert.equal(state.carryover_ready,true,'A legacy account lacking reliable boundary history must not block healthy accounts');
    assert.equal((await status()).carryover_ready,false,'Do not invent old boundary history or erase its debt');return {partial,healthyReady:state.carryover_ready};
  });
} finally {
  writeFileSync(new URL('./safety-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');await db.close();
}
`.replaceAll('\\`','`').replaceAll('\\${','${');
writeFileSync(new URL('safety-probes-generated.mjs',here),source);
await import(new URL('safety-probes-generated.mjs',here));
