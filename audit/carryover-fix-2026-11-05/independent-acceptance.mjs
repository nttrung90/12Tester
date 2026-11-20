// Supervisor-owned acceptance, generated from the pre-fix audit without changing it.
import {readFileSync,writeFileSync} from 'node:fs';
const here=new URL('./',import.meta.url);
let source=readFileSync(new URL('before/audit/carryover-recheck-2026-11-05/recheck.mjs',here),'utf8');
function replaceOnce(before,after) {
  if(!source.includes(before)) throw new Error('Missing frozen audit fragment: '+before.slice(0,100));
  source=source.replace(before,after);
}
replaceOnce("new URL('./results.json',import.meta.url)","new URL('./independent-results.json',import.meta.url)");
replaceOnce("'Report-context defect reproduced through real mission submission RPC'","'Real mission submission cannot reduce regular debt or bypass owner resume'");
replaceOnce("assert.equal(after.completed_tests,1)","assert.equal(after.completed_tests,0)");
replaceOnce("assert.equal(after.carryover_remaining_tests,before.carryover_remaining_tests-1)","assert.equal(after.carryover_remaining_tests,before.carryover_remaining_tests)");
replaceOnce("assert.equal(bypass.carryover_remaining_tests,0)","assert.equal(bypass.carryover_remaining_tests,1)");
replaceOnce("where owner_id='${owner}'`);\n    const bypass=await status();", "where owner_id='${owner}';update public.profiles set last_login_date=${today} where id='${owner}'`);\n    const bypass=await status();assert.equal(bypass.met,true);");
replaceOnce("const resumed=(await q(`select public.resume_campaign('${owned}') r`)).r;",String.raw`let resumeFailure;
    await db.exec('savepoint rejected_resume');
    try { await q(\`select public.resume_campaign('\${owned}') r\`); }
    catch(error) { resumeFailure=String(error.message);await db.exec('rollback to savepoint rejected_resume'); }
    await db.exec('release savepoint rejected_resume');
    assert.match(resumeFailure ?? '',/CARRYOVER|DEBT|OWNER_INACTIVE|DAILY_TASK/);`.replaceAll('\\`','`').replaceAll('\\${','${'));
replaceOnce("assert.equal(resumed.status,'open')","assert.equal((await q(`select status from public.campaigns where id='${owned}'`)).status,'paused')");
replaceOnce("resumedStatus:resumed.status","resumeRejected:resumeFailure");
replaceOnce("await db.exec('select public.apply_daily_inactivity_penalty()');\n    const s=await status();assert.equal(s.carry_in_tests,3);",`for(let attempt=0;attempt<5;attempt++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      if((await status()).carryover_ready) break;
    }
    const s=await status();assert.equal(s.carryover_ready,true);assert.equal(s.carry_in_tests,3);`);
replaceOnce("await policy(3,true);await checkpoint(4);await events(3,4);await events(2,5);await events(1,6);",`await policy(3,true);await checkpoint(4);await events(3,4);await events(2,5);await events(1,6);
    const initialBalance=(await q(\`select credits from public.profiles where id='\${owner}'\`)).credits;`);
replaceOnce("const before=await q(`select credits from public.profiles where id='${owner}'`);",`const billing=await q(\`select coalesce(sum(penalty_credits) filter(where task_date>\${today}-4),0)::int intended,
      count(*) filter(where task_date<\${today}-4 and finalized_at is not null)::int before_anchor
      from public.user_daily_tasks where user_id='\${owner}'\`);
    const before=await q(\`select credits from public.profiles where id='\${owner}'\`);
    assert.equal(billing.before_anchor,0,'An automatic catch-up must not invent/penalize history before a trusted checkpoint');
    assert.equal(initialBalance-before.credits,billing.intended,'Only post-checkpoint finalized dates may debit the current credit balance');`);
replaceOnce("assert.equal((await status()).carry_in_tests,3);return brief(s);", "assert.equal((await status()).carry_in_tests,3);return {...brief(s),creditCheck:{initial:initialBalance,final:before.credits,penalty:billing.intended,preAnchorRows:billing.before_anchor}};");

// Rendering is reviewed independently after the implementer changes the Compose code.
const cardStart=source.indexOf("    const card=readFileSync(new URL('../../app/src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt'");
const cardEnd=source.indexOf('\n  });',cardStart);
if(cardStart<0 || cardEnd<cardStart) throw new Error('Frozen compact-card scenario not found');
source=source.slice(0,cardStart)+'    return brief(s);'+source.slice(cardEnd);
replaceOnce("'UI defect reproduced: completed compact card hides remaining debt'","'Completed daily target must still retain the unavailable old tasks'");
replaceOnce("'UI defect reproduced: member card hides preserved old debt'","'Member login must not erase preserved debt'");

const gapStart=source.indexOf("  await scenario('Long-gap defect reproduced:");
const coverageStart=source.indexOf("  await scenario('Coverage defect reproduced:",gapStart);
const finalBlock=source.indexOf('\n} finally {',coverageStart);
if(gapStart<0 || coverageStart<gapStart || finalBlock<coverageStart) throw new Error('Frozen gap/coverage scenarios not found');
source=source.slice(0,gapStart)+String.raw`
  await scenario('Covered 39-day gap recovers in bounded repeated automatic jobs without losing old debt',async()=>{
    await checkpoint(40,8);let prior=0;let runs=0;let s;
    for(;runs<20;runs++) {
      await db.exec('select public.apply_daily_inactivity_penalty()');
      const count=(await q(\`select count(*)::int n from public.user_daily_tasks where user_id='\${owner}' and task_date>\${today}-40 and finalized_at is not null\`)).n;
      s=await status();
      assert.ok(count>prior,'Each automatic job must advance this fully covered chain');prior=count;
      if(s.carryover_ready) break;
    }
    assert.equal(s.carryover_ready,true,'Long gap must eventually reconcile');assert.equal(prior,39);
    assert.ok(s.carry_in_tests>=8,'Old debt must not be erased while crossing the gap');
    const before=await q(\`select credits from public.profiles where id='\${owner}'\`);
    await db.exec('select public.apply_daily_inactivity_penalty()');
    assert.deepEqual(await q(\`select credits from public.profiles where id='\${owner}'\`),before);
    assert.equal((await status()).carry_in_tests,s.carry_in_tests);
    return {runs:runs+1,sealed:prior,status:brief(s)};
  });
`.replaceAll('\\`','`').replaceAll('\\${','${')+source.slice(finalBlock);
writeFileSync(new URL('independent-acceptance-generated.mjs',here),source);
await import(new URL('independent-acceptance-generated.mjs',here));
