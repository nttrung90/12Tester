import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

// Independent acceptance: never edit product logic or use live credentials.
const owner='11111111-1111-1111-1111-111111111111';
const tester='22222222-2222-2222-2222-222222222222';
const campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const requestIds=['cccccccc-cccc-cccc-cccc-cccccccccccc','dddddddd-dddd-dddd-dddd-dddddddddddd'];
for (const order of [['app_test','mission'],['mission','app_test']]) {
  const db=await createDb({legacyGrants:true});
  try {
    for (const filename of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(x=>/^\d{3}_.*\.sql$/.test(x)&&Number(x.slice(0,3))>=62).sort()) {
      await db.exec(read('supabase/migrations/'+filename));
    }
    await db.exec(`insert into auth.users(id,email) values ('${owner}','owner@independent.invalid'),('${tester}','tester@independent.invalid');
      update public.profiles set credits=10000 where id='${owner}';
      insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values ('${campaign}','${owner}','invalid.independent.mission','Independent mission','open',1);
      insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
        values ('${mission}','${owner}','${campaign}','Three day mission',10,3,20,'open',now()+interval '3 days');
      insert into public.mission_participants(mission_id,user_id,status) values ('${mission}','${tester}','joined');
      select set_config('request.jwt.claim.sub','${tester}',false); set role authenticated;`);
    const results=[];
    for (let n=0;n<order.length;n++) {
      const context=order[n];
      const params=`p_submission_context=>'${context}',p_campaign_id=>'${campaign}',p_content=>'Independent ${context}',p_rating=>5,p_client_submit_id=>'${requestIds[n]}',p_screenshot_url=>'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${requestIds[n]}.jpg?alt=media&token=fixture',p_image_hash=>'0123456789abcdef'${context==='mission'?`,p_mission_id=>'${mission}'`:''}`;
      results.push((await db.query(`select public.submit_tester_report_v2(${params}) as result`)).rows[0].result);
    }
    assert.notEqual(results[0].report_id,results[1].report_id,'ordinary and mission must be distinct reports');
    assert.deepEqual(results.map(x=>x.submission_context),order);
    const rows=(await db.query(`select id,submission_context,mission_id from public.tester_reports where user_id='${tester}' and campaign_id='${campaign}'`)).rows;
    assert.equal(rows.length,2,'one ordinary + one mission report coexist the same Vietnamese day');
    assert.equal(rows.filter(x=>x.submission_context==='mission'&&x.mission_id===mission).length,1);
    console.log('PASS independent daily coexistence:',order.join(' -> '));
  } catch(error) {
    console.error('FAIL independent daily coexistence:',order.join(' -> '),error.code??'',error.message);
    process.exitCode=1;
  } finally {await db.close();}
}
