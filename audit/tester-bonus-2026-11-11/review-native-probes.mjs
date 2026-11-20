import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
const config = { host: '127.0.0.1', port: 55439, user: 'bonus_test_admin', database: 'tester_bonus_test', connectionTimeoutMillis: 5000, statement_timeout: 12000 };
const root = new pg.Client(config), a = new pg.Client(config), b = new pg.Client(config);
await Promise.all([root.connect(), a.connect(), b.connect()]);
const owner = randomUUID(), tester = randomUUID(), outsider = randomUUID(), campaign = randomUUID(), request = randomUUID();
const roundCloseProbe = process.argv.includes('--round-close');
const ownerDeleteProbe = process.argv.includes('--owner-delete');
const batchCloseProbe = process.argv.includes('--batch-close') || ownerDeleteProbe;
async function asActor(client, uid, query, values = []) {
  await client.query('begin');
  try {
    await client.query("select set_config('request.jwt.claim.sub',$1,true)", [uid]);
    await client.query('set local role authenticated');
    const result = await client.query(query, values);
    await client.query('commit');
    return result;
  } catch (error) { await client.query('rollback'); throw error; }
}
try {
  const { rows: [server] } = await root.query("select current_setting('data_directory') as directory");
  assert.equal(server.directory.replaceAll('\\','/').toLowerCase(), 'f:/ai/12tester/audit/tester-bonus-2026-11-11/pg-data');
  await root.query('insert into auth.users(id,email) values($1,$4),($2,$5),($3,$6)', [owner,tester,outsider,`${owner}@fixture.local`,`${tester}@fixture.local`,`${outsider}@fixture.local`]);
  await root.query('update public.profiles set credits=10000 where id=any($1::uuid[])', [[owner,tester,outsider]]);
  await root.query("insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values($1,$2,$3,'Native concurrency probe','open',1)", [campaign,owner,`fixture.native.${campaign.replaceAll('-','')}`]);
  const created = await asActor(root, owner, 'select public.create_campaign_tester_bonus($1,5,4,$2) as result', [campaign,randomUUID()]);
  const round = created.rows[0].result.round_id;
  assert.ok(round);
  const privacy = await asActor(root, outsider, 'select reserved_credits,spent_credits,refunded_credits,client_request_id from public.campaign_tester_bonus_rounds where id=$1', [round]).catch(error => ({ rows: [], denied: error.code }));
  console.log(JSON.stringify({ probe:'unrelated-tester-financial-round-read', expected:'no rows or permission denied', actualRows:privacy.rows.length, denied:privacy.denied ?? null }));
  const screenshot = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${request}.jpg?alt=media&token=fixture`;
  const submitted = await asActor(root, tester, `select public.submit_tester_report_v2(p_submission_context=>'app_test',p_campaign_id=>$1,p_content=>'Native PostgreSQL review of tester bonus race and budget preservation',p_client_submit_id=>$2,p_screenshot_url=>$3,p_image_hash=>'0123456789abcdef') as result`, [campaign,request,screenshot]);
  const report = submitted.rows[0].result.report_id;
  assert.ok(report);
  if(batchCloseProbe){
    await root.query("update public.tester_reports set status='rejected' where id=$1",[report]);
    await root.query("update public.campaign_tester_bonus_rounds set status='closing',stopped_at=clock_timestamp() where id=$1",[round]);
    await root.query("update public.profiles set role='admin' where id=$1",[outsider]);
  }
  await a.query('begin');
  // Lifecycle operations necessarily hold a campaign row lock before firing row triggers.
  await a.query('select id from public.campaigns where id=$1 for update', [campaign]);
  if(batchCloseProbe){
    await a.query("select set_config('request.jwt.claim.sub',$1,true)",[ownerDeleteProbe?owner:outsider]);
    if(ownerDeleteProbe)await a.query('set local role authenticated');
  }
  await b.query('begin');
  await b.query("select set_config('request.jwt.claim.sub',$1,true)", [owner]);
  await b.query(batchCloseProbe?'set local role service_role':'set local role authenticated');
  const bPid = b.processID;
  let otherFinished=false;
  const approval = b.query(batchCloseProbe?'select public._process_closing_tester_bonus_rounds() as result':roundCloseProbe ? 'select public.close_campaign_tester_bonus($1) as result' : 'select public.approve_tester_report($1) as result',batchCloseProbe?[]:[roundCloseProbe ? campaign : report]).then(value => {otherFinished=true;return {ok:true,value};},error => {otherFinished=true;return {ok:false,code:error.code,message:error.message};});
  let blocked = false;
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if(batchCloseProbe&&otherFinished)break;
    const { rows: [state] } = await root.query('select $1::int=any(pg_blocking_pids($2)) as blocked', [a.processID,bPid]);
    if (state.blocked) { blocked=true; break; }
    await new Promise(resolve => setTimeout(resolve,20));
  }
  assert.ok(blocked||(batchCloseProbe&&otherFinished),'RPC must reach campaign-row wait, or a SKIP LOCKED worker may finish safely before release');
  const lifecycle = await a.query(ownerDeleteProbe?'select public.delete_owned_campaign($1)':batchCloseProbe?"select public._impl_admin_delete_campaign($1,'Native fake-fixture removal')":"update public.campaigns set status='completed' where id=$1", [campaign]).then(value => ({ ok:true, value }), error => ({ ok:false, code:error.code, message:error.message }));
  if (lifecycle.ok) await a.query('commit'); else await a.query('rollback');
  const approved = await approval;
  if (approved.ok) await b.query('commit'); else await b.query('rollback');
  console.log(JSON.stringify({ probe:ownerDeleteProbe?'owner-delete-versus-batch-close':batchCloseProbe?'admin-delete-versus-batch-close':roundCloseProbe ? 'lifecycle-versus-round-close' : 'lifecycle-versus-approval', lifecycle:{ ok:lifecycle.ok,code:lifecycle.code,message:lifecycle.message }, other:{ ok:approved.ok,code:approved.code,message:approved.message } }));
  if (!lifecycle.ok || !approved.ok) process.exitCode = 1;
  if(batchCloseProbe&&lifecycle.ok&&approved.ok){
    const ledger=(await root.query('select * from public.campaign_tester_bonus_rounds where id=$1',[round])).rows[0];
    assert.ok(ledger,'Financial round evidence survives removal');
    assert.equal(ledger.remaining_credits,0);assert.equal(ledger.status,'closed');
    assert.equal(ledger.reserved_credits,ledger.spent_credits+ledger.refunded_credits);
    assert.equal((await root.query('select count(*)::int as n from public.campaigns where id=$1',[campaign])).rows[0].n,0);
    if(ownerDeleteProbe){
      const reconciled=await asActor(root,owner,'select public.reconcile_campaign_tester_bonus($1,$2) as result',[campaign,created.rows[0].result.client_request_id]);
      assert.equal(reconciled.rows[0].result.found,true);
      assert.equal(reconciled.rows[0].result.campaign_id,campaign);
      console.log('PASS owner request remains reconcilable after native concurrent removal');
    }
  }
} finally {
  await a.query('rollback').catch(()=>{}); await b.query('rollback').catch(()=>{});
  await Promise.all([root.end(),a.end(),b.end()]);
}
