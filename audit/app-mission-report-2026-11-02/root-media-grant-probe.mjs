import assert from 'node:assert/strict';
import {readdirSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const owner='11111111-1111-1111-1111-111111111111',tester='22222222-2222-2222-2222-222222222222',stranger='33333333-3333-3333-3333-333333333333';
const campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',mission='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const id=n=>'dddddddd-dddd-dddd-dddd-'+String(n).padStart(12,'0');
const url=(n,thumb=false)=>`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${thumb?'thumb_':''}${id(n)}.jpg?alt=media&token=fixture`;
const images=[0,1,2].map(position=>({id:id(position+1),position,screenshot_url:url(position+1),thumbnail_url:url(position+1,true),image_hash:'0123456789abcdef',sha256:'a'.repeat(64)}));
const db=await createDb({legacyGrants:true});
for(const f of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d{3}_.*\.sql$/.test(f)&&+f.slice(0,3)>=62).sort())await db.exec(read('supabase/migrations/'+f));
await db.exec(`select set_config('app.settings.media_gateway_secret','independent-test-only-secret-never-production',false);
 select set_config('app.settings.media_gateway_base_url','https://gateway.fixture.invalid/media',false);
 insert into auth.users(id,email) values('${owner}','owner@media.invalid'),('${tester}','tester@media.invalid'),('${stranger}','stranger@media.invalid');
 update public.profiles set credits=10000 where id='${owner}';
 insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values('${campaign}','${owner}','invalid.media.app','Media probe','open',1);
 insert into public.missions(id,creator_id,campaign_id,title,max_participants,duration_days,reward_credits,status,ends_at) values('${mission}','${owner}','${campaign}','Media mission',10,3,20,'open',now()+interval '2 days');
 insert into public.mission_participants(mission_id,user_id,status) values('${mission}','${tester}','joined');`);
async function actor(uid,body){
 await db.exec(`begin;select set_config('request.jwt.claim.sub','${uid}',true);set local role authenticated;`);
 try{const result=await body();await db.exec('commit');return result;}catch(e){await db.exec('rollback');throw e;}
}
const receipt=await actor(tester,async()=>(await db.query(`select public.submit_tester_report_v3(p_submission_context=>'mission',p_campaign_id=>'${campaign}',p_content=>'Media snapshot',p_rating=>5,p_client_submit_id=>'${id(100)}',p_mission_id=>'${mission}',p_attachments=>$1::jsonb) result`,[JSON.stringify(images)])).rows[0].result);
const reportId=receipt.report_id;
async function grant(uid,attachment=null){return actor(uid,async()=>(await db.query('select public.request_report_media_access($1::uuid,$2::uuid) result',[reportId,attachment])).rows[0].result);}
const signed=(g,variant='full')=>new URL(variant==='full'?(g.screenshot_url??g.signed_full_url):(g.thumbnail_url??g.signed_thumbnail_url));
async function verify(g,{uid=tester,attachment=null,variant='full',legacy=false}={}){
 const u=signed(g,variant);
 await db.exec('begin;set local role service_role;');
 try{
  const args=[reportId,uid,u.searchParams.get('grant_token'),variant,+u.searchParams.get('expires_at')];
  const query=legacy?'select public.verify_media_access_grant_internal($1::uuid,$2::uuid,$3::text,$4::text,$5::bigint) valid':'select public.verify_media_access_grant_internal($1::uuid,$2::uuid,$3::text,$4::text,$5::bigint,$6::uuid) valid';
  if(!legacy)args.push(attachment);
  const value=(await db.query(query,args)).rows[0].valid;await db.exec('commit');return value;
 }catch(e){await db.exec('rollback');throw e;}
}
async function test(name,body){try{await body();console.log('PASS independent media grant:',name);}catch(e){console.error('FAIL independent media grant:',name,e.code??'',e.message);process.exitCode=1;}}
await test('primary issued grant verifies through both published legacy and attachment-capable paths',async()=>{
 const g=await grant(tester);assert.equal(await verify(g),true,'new six-argument verifier must accept newly issued primary grant');assert.equal(await verify(g,{legacy:true}),true,'published five-argument verifier must also accept primary grant');
});
await test('selected secondary grant binds image and cannot be replayed as primary, another image or another viewer',async()=>{
 const g=await grant(tester,images[1].id);assert.equal(signed(g).searchParams.get('attachment_id'),images[1].id);
 assert.equal(await verify(g,{attachment:images[1].id}),true);assert.equal(await verify(g,{attachment:images[1].id,variant:'thumb'}),true);
 assert.equal(await verify(g),false);assert.equal(await verify(g,{attachment:images[2].id}),false);assert.equal(await verify(g,{attachment:images[1].id,uid:owner}),false);
});
await test('owner can receive and use an exact third-image grant',async()=>{
 const g=await grant(owner,images[2].id);assert.equal(g.role,'owner');assert.equal(await verify(g,{uid:owner,attachment:images[2].id}),true);
});
await test('RPC JSON matches actual Android MediaAccessGrantResult fields',async()=>{
 const g=await grant(tester,images[1].id);
 assert.equal(typeof g.screenshot_url,'string','Android decodes screenshot_url');assert.equal(typeof g.thumbnail_url,'string','Android decodes thumbnail_url');
 assert.equal(typeof g.expires_at_epoch,'number','Android decodes expires_at_epoch');assert.equal(g.expires_at_epoch,+signed(g).searchParams.get('expires_at'));
});
await test('unrelated authenticated tester cannot request report media',async()=>{await assert.rejects(grant(stranger,images[1].id),/FORBIDDEN/);});
await test('expired report denies issuance and revokes already issued secondary grant',async()=>{
 const g=await grant(tester,images[1].id);await db.query('update public.tester_reports set media_expired=true where id=$1::uuid',[reportId]);
 await assert.rejects(grant(tester,images[1].id),/MEDIA_EXPIRED/);assert.equal(await verify(g,{attachment:images[1].id}),false);
});
await db.close();
