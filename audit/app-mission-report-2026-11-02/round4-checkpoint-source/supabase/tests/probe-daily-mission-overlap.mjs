import assert from 'node:assert/strict';
import {createDb, read} from '../tools/baseline-lib.mjs';
const owner='11111111-1111-1111-1111-111111111111', user='22222222-2222-2222-2222-222222222222', camp='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
for (const explicit of [false,true]) {
 const db=await createDb();
 try {
  await db.exec(read('supabase/migrations/062_consolidated_baseline.sql'));
  await db.exec(read('supabase/migrations/063_report_crypto_search_path.sql'));
  await db.exec(`insert into auth.users(id,email) values ('${owner}','owner@probe.test'),('${user}','user@probe.test');
    update public.profiles set credits=10000 where id='${owner}';
    insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values ('${camp}','${owner}','com.probe.overlap','Overlap','open',1);
    insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at)
    values ('${mission}','${owner}','${camp}','Mission',10,3,20,'open',now()+interval '2 days');
    insert into public.mission_participants(mission_id,user_id,status,joined_at) values ('${mission}','${user}','joined',now()-interval '30 minutes');
    ${explicit ? `update public.missions set status='paused_by_creator' where id='${mission}';` : ''}
    select set_config('request.jwt.claim.sub','${user}',false); set role authenticated;`);
  const call=()=>db.query(`select public.submit_tester_report(p_campaign_id=>'${camp}',p_content=>'Daily test',
    p_screenshot_url=>'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp}%2F${user}%2Fcccccccc-cccc-cccc-cccc-cccccccccccc.jpg?alt=media&token=fixture',
    p_image_hash=>'0123456789abcdef',p_client_submit_id=>'cccccccc-cccc-cccc-cccc-cccccccccccc'
    ${explicit ? `,p_mission_id=>'${mission}'` : ''}) as result`);
  if(explicit) {
   await assert.rejects(call(),/MISSION_NOT_OPEN/);
   console.log('REPRODUCED: explicit paused mission blocks submission with MISSION_NOT_OPEN (UI says stopped/out of credit).');
  } else {
   const result=(await call()).rows[0].result;
   assert.equal(result.submission_context,'mission');
   console.log('REPRODUCED: no mission ID sent, backend assigns joined mission. provisional_amount='+result.provisional_amount);
  }
 } finally {await db.close();}
}

