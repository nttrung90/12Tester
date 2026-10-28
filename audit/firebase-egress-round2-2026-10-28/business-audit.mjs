import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');
const root = fileURLToPath(new URL('../../', import.meta.url));
const read = p => readFileSync(root + p, 'utf8');
const owner = '11111111-1111-4111-8111-111111111111';
const tester = '22222222-2222-4222-8222-222222222222';
const campaign = '33333333-3333-4333-8333-333333333333';
const id1 = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const id2 = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const full = id => `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${id}.jpg?alt=media&token=fixture`;
const thumb = full('thumb_' + id1);
const bootstrap = `create role anon nologin; create role authenticated nologin; create role service_role nologin bypassrls;
create schema auth; create table auth.users (id uuid primary key,email text,raw_user_meta_data jsonb default '{}',email_confirmed_at timestamptz,confirmation_token text,email_change text,email_change_token_new text,recovery_token text);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema public,auth to anon,authenticated,service_role; grant execute on function auth.uid() to anon,authenticated,service_role;`;
const results = [];
async function runPhase(upgraded) {
 const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
 try {
  await db.exec(bootstrap); await db.exec(read('supabase/full_schema_singapore.sql'));
  if(upgraded) {await db.exec(read('supabase/migrations/045_thumbnail_support.sql'));await db.exec(read('supabase/migrations/046_media_access_gateway.sql'));}
  await db.exec(`insert into auth.users(id,email) values('${owner}','owner@fixture.invalid'),('${tester}','tester@fixture.invalid'); update public.profiles set credits=100 where id in('${owner}','${tester}'); insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number) values('${campaign}','${owner}','Audit','audit.fixture','open',1); set request.jwt.claim.sub='${tester}';`);
  const call = async ({url=full(id1),hash='0123456789abcdef',submit=id1,rating=5,thumbnail}={}) => (await db.query(`select public.submit_tester_report($1::uuid,$2::text,$3::text,$4::int,$5::text,$6::uuid${thumbnail !== undefined ? ',$7::text' : ''}) as result`, [campaign,'audit fixture',url,rating,hash,submit,...(thumbnail !== undefined ? [thumbnail] : [])])).rows[0].result;
  async function probe(name,action) {
   await db.exec('begin');
   try {results.push({phase:upgraded?'after_045_046':'before_045',name,...await action()});}
   catch(e){results.push({phase:upgraded?'after_045_046':'before_045',name,error:e.message});}
   finally{await db.exec('rollback');}
  }
  await probe('external_screenshot_url', async()=>({accepted:(await call({url:'https://untrusted.fixture.invalid/not-a-firebase-image.jpg'})).success}));
  await probe('confirmed_checkin_additional_reward',async()=>{
   await db.exec(`insert into public.checkins(campaign_id,user_id,checkin_date,note) values('${campaign}','${tester}',(now() at time zone 'Asia/Ho_Chi_Minh')::date,'confirmed fixture');`);
   const before=(await db.query(`select credits from profiles where id=$1`,[tester])).rows[0].credits;
   const response=await call();
   const after=(await db.query(`select credits from profiles where id=$1`,[tester])).rows[0].credits;
   return {creditDelta:Number(after)-Number(before),provisionalGranted:response.provisional_granted};
  });
  await probe('feedback_written_on_submission',async()=>{await call();return {feedbackRows:(await db.query(`select count(*)::int as n from feedbacks where campaign_id=$1 and user_id=$2`,[campaign,tester])).rows[0].n};});
  await probe('approved_rating_immutable',async()=>{
   const report=await call();await db.query(`update tester_reports set status='approved',reviewed_at=now() where id=$1`,[report.report_id]);
   await call({url:null,hash:null,submit:id2,rating:1});
   return {rating:(await db.query(`select rating from tester_reports where id=$1`,[report.report_id])).rows[0].rating};
  });
  await probe('rejected_same_hash_new_url',async()=>{
   const report=await call();await db.query(`update tester_reports set status='rejected',reviewed_at=now(),provisional_clawed=10 where id=$1`,[report.report_id]);
   return {accepted:(await call({url:full(id2),submit:id2})).success};
  });
  if(upgraded){
   await probe('replacement_full_without_thumbnail',async()=>{
    const report=await call({thumbnail:thumb});await call({url:full(id2),submit:id2,hash:'fedcba9876543210',thumbnail:null});
    const row=(await db.query(`select screenshot_url,thumbnail_url from tester_reports where id=$1`,[report.report_id])).rows[0];
    return {fullReplaced:row.screenshot_url===full(id2),oldThumbnailRetained:row.thumbnail_url===thumb};
   });
   await probe('untrusted_thumbnail_url',async()=>({thumbnailAccepted:(await call({thumbnail:'https://untrusted.fixture.invalid/thumbnail.jpg'})).thumbnail_url}));
   await probe('media_access_returns_original_download_url',async()=>{
    const report=await call();const grant=(await db.query(`select get_report_media_access($1::uuid) as grant`,[report.report_id])).rows[0].grant;
    return {samePermanentUrl:grant.screenshot_url===full(id1),hasGrant:!!grant.access_grant_token,expiresAtPresent:!!grant.expires_at};
   });
  }
 }finally{await db.close();}
}
await runPhase(false);await runPhase(true);
writeFileSync(new URL('./db-results.json',import.meta.url),JSON.stringify(results,null,2));
console.log(JSON.stringify(results,null,2));
