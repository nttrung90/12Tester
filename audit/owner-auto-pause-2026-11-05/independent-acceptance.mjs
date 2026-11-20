// Codex supervisor checks derive from the pre-change reproductions, not the
// implementation agent's test edits. Execute against the complete current schema.
import { readFileSync, writeFileSync } from 'node:fs';
const auditDir = new URL('./',import.meta.url);
let source = readFileSync(new URL('before/supabase/tests/audit-owner-auto-pause-flow.mjs',auditDir),'utf8');
source = source.replace("from '../tools/baseline-lib.mjs'", "from '../../supabase/tools/baseline-lib.mjs'")
  .replace("new URL('../migrations/', import.meta.url)","new URL('../../supabase/migrations/', import.meta.url)");
function replaceScenario(name,nextName,replacement) {
  const start=source.indexOf(`  await scenario('${name}'`);
  const end=source.indexOf(`  await scenario('${nextName}'`,start);
  if(start<0 || end<0) throw new Error(`Missing original reproduction: ${name}`);
  source=source.slice(0,start)+replacement+'\n\n'+source.slice(end);
}
replaceScenario('timestamp mutation bypass observation','missing intermediate finalized day observation (carryover disabled)',String.raw`  await scenario('independent: client cannot alter timestamps to bypass owner debt', async () => {
    await checkpoint(1,7);
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,2);
    const dates=(await db.query("select id,created_at from public.campaigns where owner_id='"+owner+"' order by id")).rows;
    await assert.rejects(() => asOwner("update public.campaigns set created_at=now() where owner_id='"+owner+"'"));
    assert.deepEqual((await db.query("select id,created_at from public.campaigns where owner_id='"+owner+"' order by id")).rows,dates);
    await asOwner('select public.record_daily_login()');
    const status=(await scalar("select public.get_daily_task_status('"+owner+"',"+today+") s")).s;
    assert.equal(status.mode,'owner');
    assert.equal(status.completed_tests,0);
    assert.equal(status.met,false);
    await assert.rejects(() => asOwner("select public.resume_campaign('"+app1+"')"),/OWNER_INACTIVE|CARRYOVER/);
  });`);
source=source.replace("assert.equal(r.s,7,'Current defect reproduction: the six-day streak crosses a met day');",String.raw`assert.equal(r.s,1,'A met intermediate day must cut the previous streak');
    assert.equal(r.gap_rows,1,'The ready intermediate day must be finalized chronologically');
    assert.equal((await scalar('select public.pause_inactive_owner_campaigns() n')).n,0);`);
const oldDebt=[
  "    const result=(await asOwner(`select public.resume_campaign('${app1}') r`)).rows[0].r;",
  "    assert.equal(result.status,'open');",
  "    console.log('GAP requested carryover gate at threshold 3:',JSON.stringify({met:status.met,carryover_remaining_tests:status.carryover_remaining_tests,result:result.status}));"
].join('\n');
const newDebt=String.raw`    for (const reason of ['owner_inactive','owner_manual','slot_expired']) {
      await db.exec("update public.campaigns set paused_reason='"+reason+"' where owner_id='"+owner+"'");
      for (const id of [app1,app2]) {
        await assert.rejects(() => asOwner("select public.resume_campaign('"+id+"')"),/OWNER_CARRYOVER_INCOMPLETE|CARRYOVER/);
        assert.equal((await scalar("select status from public.campaigns where id='"+id+"'")).status,'paused');
      }
      const debt=(await scalar("select public.get_daily_task_status('"+owner+"',"+today+") s")).s;
      assert.equal(debt.carryover_remaining_tests,status.carryover_remaining_tests,'Resume attempts cannot subtract debt or reset it by changing pause reason');
    }
    console.log('Independent debt gate verified:',JSON.stringify({met:status.met,remaining:status.carryover_remaining_tests}));`;
if(!source.includes(oldDebt)) throw new Error('Missing original carryover reproduction');
source=source.replace(oldDebt,newDebt);
// The original reproduction documented a wrong ready=true before gap recovery.
// Under the repaired readiness rule, that same unfinalized predecessor is pending.
source=source.replace('assert.equal(before.carryover_ready,true);', "assert.equal(before.carryover_ready,false,'An unfinalized predecessor remains pending even when carryover is disabled');");
source=source.replace("admin threshold 3: current resume still allows outstanding carryover","independent: debt blocks all owner resume reasons at threshold 3");
source=source.replace("console.log('BUG non-contiguous streak:'", "console.log('Independent contiguous streak verified:'");
source=source.replace('// BUG observations document current defects; they are not acceptance assertions.','// Acceptance assertions for previously reproduced defects.');
writeFileSync(new URL('independent-acceptance-generated.mjs',auditDir),source);
await import(new URL('independent-acceptance-generated.mjs',auditDir));
