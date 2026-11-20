import assert from 'node:assert/strict';
import {readdirSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const owner='11111111-1111-1111-1111-111111111111',tester='22222222-2222-2222-2222-222222222222';
const campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const id=n=>'dddddddd-dddd-dddd-dddd-'+String(n).padStart(12,'0');
const url=(n,thumb=false)=>`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${thumb?'thumb_':''}${id(n)}.jpg?alt=media&token=fixture`;
const images=[0,1,2].map(position=>({id:id(position+1),position,screenshot_url:url(position+1),thumbnail_url:url(position+1,true),image_hash:'0123456789abcdef',sha256:'a'.repeat(64)}));
const db=await createDb({legacyGrants:true});
for(const f of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d{3}_.*\.sql$/.test(f)&&+f.slice(0,3)>=62).sort())await db.exec(read('supabase/migrations/'+f));
await db.exec(`insert into auth.users(id,email) values('${owner}','owner@images.invalid'),('${tester}','tester@images.invalid');
 update public.profiles set credits=10000 where id='${owner}';
 insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values('${campaign}','${owner}','invalid.images.app','Images probe','open',1);
 insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at) values('${mission}','${owner}','${campaign}','Images mission',10,3,20,'open',now()+interval '2 days');
 insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${tester}','joined');`);
async function actor(body,rollback=false){
 await db.exec(`begin;select set_config('request.jwt.claim.sub','${tester}',true);set local role authenticated;`);
 try{const result=await body();await db.exec(rollback?'rollback':'commit');return result;}
 catch(e){await db.exec('rollback');throw e;}
}
async function v3(n,attachments=images){
 return(await db.query(`select public.submit_tester_report_v3(p_submission_context=>'mission',p_campaign_id=>'${campaign}',p_content=>'Three image snapshot',p_rating=>5,p_client_submit_id=>'${id(n)}',p_mission_id=>'${mission}',p_attachments=>$1::jsonb) result`,[JSON.stringify(attachments)])).rows[0].result;
}
async function test(name,body){try{await body();console.log('PASS independent three-image contract:',name);}catch(e){console.error('FAIL independent three-image contract:',name,e.code??'',e.message);process.exitCode=1;}}
let original;
await test('create three ordered attachments and immutable exact replay',async()=>{
 original=await actor(()=>v3(100));
 assert.equal(original.attachments.length,3);
 assert.deepEqual(original.attachments.map(x=>x.id),images.map(x=>x.id));
 assert.equal(original.screenshot_url,images[0].screenshot_url);
 assert.deepEqual(await actor(()=>v3(100)),original);
 assert.equal((await db.query('select count(*)::int n from public.tester_reports')).rows[0].n,1);
});
await test('four images rejected server-side',async()=>{
 await assert.rejects(actor(()=>v3(101,[...images,{...images[0],id:id(4),position:3,screenshot_url:url(4),thumbnail_url:url(4,true)}]),true));
});
await test('client cannot move report_date to regain the daily slot',async()=>{
 try{
  await actor(async()=>{
   await db.query(`update public.tester_reports set report_date=report_date-1 where id='${original.report_id}'`);
   const row=(await db.query(`select report_date::text actual,(created_at at time zone 'Asia/Ho_Chi_Minh')::date::text expected from public.tester_reports where id='${original.report_id}'`)).rows[0];
   assert.equal(row.actual,row.expected,'reporting day must remain server-controlled');
  },true);
 }catch(e){if(e.code==='ERR_ASSERTION')throw e;if(!/PERMISSION|permission|DENIED|PROTECT|REPORT|policy/i.test(e.message))throw e;}
});
await test('v3 rejects missing client request identity',async()=>{
 await assert.rejects(actor(()=>db.query(`select public.submit_tester_report_v3(p_submission_context=>'mission',p_campaign_id=>'${campaign}',p_content=>'Missing identity',p_rating=>5,p_mission_id=>'${mission}',p_attachments=>$1::jsonb) result`,[JSON.stringify(images)]),true));
});
await test('v3 rejects null context instead of inferring intent from mission ID',async()=>{
 await assert.rejects(actor(()=>db.query(`select public.submit_tester_report_v3(p_submission_context=>null::text,p_campaign_id=>'${campaign}',p_content=>'Three image snapshot',p_rating=>5,p_client_submit_id=>'${id(109)}',p_mission_id=>'${mission}',p_attachments=>$1::jsonb) result`,[JSON.stringify(images)]),true));
});
await test('explicit v2 also rejects null context',async()=>{
 await assert.rejects(actor(()=>db.query(`select public.submit_tester_report_v2(p_submission_context=>null::text,p_campaign_id=>'${campaign}',p_content=>'Null explicit context',p_rating=>5,p_client_submit_id=>'${id(110)}',p_mission_id=>'${mission}',p_screenshot_url=>'${url(1)}',p_thumbnail_url=>'${url(1,true)}',p_image_hash=>'0123456789abcdef') result`),true));
});
await test('foreign secondary image URL rejected',async()=>{
 const bad=structuredClone(images);bad[1].screenshot_url='https://attacker.invalid/not-a-report.jpg';
 await assert.rejects(actor(()=>v3(102,bad),true));
});
await test('secondary hash format validated',async()=>{
 const bad=structuredClone(images);bad[1].image_hash='not-a-valid-hash';
 await assert.rejects(actor(()=>v3(103,bad),true));
});
await test('secondary thumbnail bound to its own full image',async()=>{
 const bad=structuredClone(images);bad[1].thumbnail_url=images[0].thumbnail_url;
 await assert.rejects(actor(()=>v3(104,bad),true));
});
await test('empty array cannot silently mean keep attachments',async()=>{
 await assert.rejects(actor(()=>v3(105,[]),true));
});
await test('changed order under same request ID rejected',async()=>{
 const reordered=[images[1],images[0],images[2]].map((item,position)=>({...item,position}));
 await assert.rejects(actor(()=>v3(100,reordered),true));
});
await test('v2 cover update either rejected or retains coherent entire set',async()=>{
 let response;
 try{
  await actor(async()=>{
   response=(await db.query(`select public.submit_tester_report_v2(p_submission_context=>'mission',p_campaign_id=>'${campaign}',p_content=>'Legacy cover edit',p_rating=>5,p_client_submit_id=>'${id(106)}',p_mission_id=>'${mission}',p_screenshot_url=>'${url(106)}',p_thumbnail_url=>'${url(106,true)}',p_image_hash=>'fedcba9876543210') result`)).rows[0].result;
   const report=(await db.query(`select screenshot_url from public.tester_reports where id='${original.report_id}'`)).rows[0];
   const rows=(await db.query(`select position,screenshot_url,image_hash,sha256 from public.tester_report_attachments where report_id='${original.report_id}' order by position`)).rows;
   assert.equal(rows.length,3,'legacy cover update must retain every secondary attachment');
   assert.equal(rows[0].screenshot_url,report.screenshot_url,'primary report and first attachment must match');
   assert.equal(rows[0].image_hash,'fedcba9876543210','cover hash must describe the new image');
   assert.equal(rows[0].sha256,null,'v2 provides no new byte hash, so the old cover SHA must be cleared');
   assert.deepEqual(rows.slice(1).map(r=>r.screenshot_url),images.slice(1).map(r=>r.screenshot_url));
  },true);
 }catch(e){if(e.code==='ERR_ASSERTION')throw e;if(!/REPORT|ATTACHMENT|CLIENT|VERSION|UPDATE_REQUIRED/.test(e.message))throw e;}
});
await test('distinct valid URL fields cannot collide in a delimiter-based fingerprint',async()=>{
 const first=structuredClone(images),second=structuredClone(images);
 const full=images[1].screenshot_url,thumb=images[1].thumbnail_url;
 first[1].screenshot_url=full+':'+thumb;
 second[1].thumbnail_url=thumb+':'+thumb;
 await actor(()=>v3(108,first));
 await assert.rejects(actor(()=>v3(108,second),true),'different full/thumbnail fields under same ID must be rejected');
});
await db.close();
