import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const db=await createDb({legacyGrants:true});
const owner=randomUUID(),tester=randomUUID();
const cases=[];
const failures=[];
async function actor(id,sql,args=[]){
  await db.exec('begin');
  try {
    await db.query("select set_config('request.jwt.claim.sub',$1,true)",[id]);
    await db.exec('set local role authenticated');
    const out=await db.query(sql,args);
    await db.exec('commit');
    return out;
  } catch(error){await db.exec('rollback');throw error;}
}
async function check(name,fn){
  try{await fn();console.log(`PASS ${name}`);}catch(error){failures.push({name,error:error.message});console.log(`FAIL ${name}: ${error.message}`);}
}
const singleSql=`select public.submit_tester_report_v2(p_submission_context=>'app_test',p_campaign_id=>$1,p_content=>'Existing submission must replay after migration',p_rating=>5,p_client_submit_id=>$2,p_screenshot_url=>$3,p_image_hash=>'0123456789abcdef') as result`;
const attachmentSql=`select public.submit_tester_report_v3(p_submission_context=>'app_test',p_campaign_id=>$1,p_content=>'Existing submission must replay after migration',p_rating=>5,p_client_submit_id=>$2,p_attachments=>$3::jsonb) as result`;
try{
  const files=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort();
  for(const file of files.filter(n=>Number(n.slice(0,3))<=86)) await db.exec(read(`supabase/migrations/${file}`));
  await db.query('insert into auth.users(id,email) values($1,$3),($2,$4)',[owner,tester,`${owner}@fixture.local`,`${tester}@fixture.local`]);
  await db.query('update public.profiles set credits=10000 where id=any($1::uuid[])',[[owner,tester]]);
  for(const mode of ['single-open','attachments-open','single-completed','attachments-completed']){
    const campaign=randomUUID(),request=randomUUID(),caseOwner=randomUUID();
    await db.query('insert into auth.users(id,email) values($1,$2)',[caseOwner,`${caseOwner}@fixture.local`]);
    await db.query("insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values($1,$2,$3,'Migration replay fixture','open',1)",[campaign,caseOwner,`fixture.replay.${campaign.replaceAll('-','')}`]);
    const url=`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${request}.jpg?alt=media&token=fixture`;
    const attachments=mode.startsWith('attachments')?JSON.stringify([{position:0,screenshot_url:url,image_hash:'0123456789abcdef'}]):null;
    const params=[campaign,request,attachments??url];
    const submitSql=attachments?attachmentSql:singleSql;
    const original=(await actor(tester,submitSql,params)).rows[0].result;
    const replay=(await actor(tester,submitSql,params)).rows[0].result;
    assert.deepEqual(replay,original,`baseline replay must work: ${mode}`);
    cases.push({mode,campaign,params,original,submitSql});
  }
  await db.exec(read('supabase/migrations/087_campaign_tester_bonus_rounds.sql'));
  for(const c of cases){
    if(c.mode.endsWith('completed'))await db.query("update public.campaigns set status='completed' where id=$1",[c.campaign]);
    await check(`086->087 stored receipt replay ${c.mode}`,async()=>{
      const current=(await actor(tester,c.submitSql,c.params)).rows[0].result;
      assert.deepEqual(current,c.original);
      const report=(await db.query('select tester_bonus_round_id,tester_bonus_awarded from public.tester_reports where id=$1',[c.original.report_id])).rows[0];
      assert.equal(report.tester_bonus_round_id,null);
      assert.equal(report.tester_bonus_awarded,0);
    });
  }
  console.log(JSON.stringify({cases:cases.length,failures},null,2));
  if(failures.length)process.exitCode=1;
}finally{await db.close();}
