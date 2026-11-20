import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb({legacyGrants:true});
const failures=[];
async function actor(id,sql,args=[]){
  await db.exec('begin');
  try{
    await db.query("select set_config('request.jwt.claim.sub',$1,true)",[id]);
    await db.exec('set local role authenticated');
    const result=await db.query(sql,args);await db.exec('commit');return result;
  }catch(error){await db.exec('rollback');throw error;}
}
try{
  for(const file of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort())await db.exec(read(`supabase/migrations/${file}`));
  for(const recipients of [1,2]){
    try{
      const owner=randomUUID(),tester=randomUUID(),admin=randomUUID(),campaign=randomUUID(),request=randomUUID();
      await db.query('insert into auth.users(id,email) values($1,$4),($2,$5),($3,$6)',[owner,tester,admin,`${owner}@fixture.local`,`${tester}@fixture.local`,`${admin}@fixture.local`]);
      await db.query('update public.profiles set credits=10000 where id=any($1::uuid[])',[[owner,tester,admin]]);
      await db.query("update public.profiles set role='admin' where id=$1",[admin]);
      await db.query("insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values($1,$2,$3,'Deferred removal fixture','open',1)",[campaign,owner,`fixture.removal.${campaign.replaceAll('-','')}`]);
      const createRequest=randomUUID();
      const round=(await actor(owner,'select public.create_campaign_tester_bonus($1,5,$2,$3) as result',[campaign,recipients,createRequest])).rows[0].result.round_id;
      const url=`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${request}.jpg?alt=media&token=fixture`;
      const report=(await actor(tester,`select public.submit_tester_report_v2(p_submission_context=>'app_test',p_campaign_id=>$1,p_content=>'Deferred removal must preserve pending rewards',p_client_submit_id=>$2,p_screenshot_url=>$3,p_image_hash=>'0123456789abcdef') as result`,[campaign,request,url])).rows[0].result.report_id;
      const deletion=(await actor(admin,"select public.admin_delete_campaign($1,'Local fixture removal reason') as result",[campaign])).rows[0].result;
      assert.equal(deletion.status,'pending_removal');
      assert.equal((await db.query('select count(*)::int as n from public.tester_reports where id=$1',[report])).rows[0].n,1);
      const approval=(await actor(owner,'select public.approve_tester_report($1) as result',[report])).rows[0].result;
      assert.equal(approval.tester_bonus_awarded,5);
      // Execute the real integrated scheduled job twice; no production data/network.
      await db.query('select public.auto_accept_expired_reports()');
      await db.query('select public.auto_accept_expired_reports()');
      assert.equal((await db.query('select count(*)::int as n from public.campaigns where id=$1',[campaign])).rows[0].n,0,'pending-removal campaign must eventually be removed');
      const ledger=(await db.query('select * from public.campaign_tester_bonus_rounds where id=$1',[round])).rows[0];
      assert.equal(ledger.paid_count,1);assert.equal(ledger.spent_credits,5);
      assert.equal(ledger.refunded_credits,(recipients-1)*5);
      assert.equal(ledger.remaining_credits,0);
      assert.equal(ledger.reserved_credits,ledger.spent_credits+ledger.refunded_credits);
      assert.equal((await db.query('select count(*)::int as n from public.campaign_tester_bonus_awards where round_id=$1',[round])).rows[0].n,1,'award evidence must survive removal');
      console.log(`PASS deferred removal recipients=${recipients}`);
      const reconciled=(await actor(owner,'select public.reconcile_campaign_tester_bonus($1,$2) as result',[campaign,createRequest])).rows[0].result;
      assert.equal(reconciled.found,true,'original committed request must remain reconcilable after campaign removal');
      assert.equal(reconciled.campaign_id,campaign,'response must retain original validated request campaign identity');
      console.log(`PASS reconcile after removal recipients=${recipients}`);
    }catch(error){failures.push({recipients,error:error.message});console.log(`FAIL deferred removal recipients=${recipients}: ${error.message}`);}
  }
  console.log(JSON.stringify({failures},null,2));
  if(failures.length)process.exitCode=1;
}catch(error){console.error(JSON.stringify({error:error.message,code:error.code}));process.exitCode=1;}
finally{await db.close();}
