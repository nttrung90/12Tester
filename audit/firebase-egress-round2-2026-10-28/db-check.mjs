import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');
const db = new PGlite({extensions:{pgcrypto,uuid_ossp}});
const rows=[];
const owner='11111111-1111-4111-8111-111111111111';
const tester='22222222-2222-4222-8222-222222222222';
const campaign='33333333-3333-4333-8333-333333333333';
const id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const other='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const objectPath=leaf=>`reports%2F${campaign}%2F${tester}%2F${leaf}.jpg`;
const url=leaf=>`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/${objectPath(leaf)}?alt=media&token=fixture`;
function check(name,actual,expected){rows.push({name,actual,expected,pass:actual===expected});}
try {
 await db.exec(`create role anon nologin;create role authenticated nologin;create role service_role nologin bypassrls;create schema auth;create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',email_confirmed_at timestamptz,confirmation_token text,email_change text,email_change_token_new text,recovery_token text);create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;grant usage on schema public,auth to anon,authenticated,service_role;grant execute on function auth.uid() to anon,authenticated,service_role;`);
 for(const file of ['supabase/full_schema_singapore.sql','supabase/migrations/045_thumbnail_support.sql','supabase/migrations/046_media_access_gateway.sql']) await db.exec(readFileSync(new URL('../../'+file,import.meta.url),'utf8'));
 for(const [name,thumb,expected] of [
  ['valid_paired_thumbnail',url('thumb_'+id),true],
  ['reject_wrong_bucket',url('thumb_'+id).replace('/b/cross-testers.firebasestorage.app/','/b/untrusted-fixture/'),false],
  ['reject_bucket_with_trusted_name_prefix',url('thumb_'+id).replace('/b/cross-testers.firebasestorage.app/','/b/cross-testers-attacker/'),false],
  ['reject_test_bucket_in_production_validator',url('thumb_'+id).replace('/b/cross-testers.firebasestorage.app/','/b/test-bucket/'),false],
  ['reject_query_path_spoof',`https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/irrelevant.jpg?path=${objectPath('thumb_'+id)}`,false],
  ['reject_other_full_upload_id',url('thumb_'+other),false],
 ]) {
  const {ok}= (await db.query('select public.is_valid_report_thumbnail_url($1,$2::uuid,$3::uuid,$4) ok',[thumb,campaign,tester,url(id)])).rows[0];check(name,ok,expected);
 }
 const helper=(await db.query("select to_regprocedure('public.get_media_gateway_secret()') is not null present")).rows[0].present;
 if(helper){
  const privilege=(await db.query("select has_function_privilege('anon','public.get_media_gateway_secret()','execute') ok")).rows[0].ok;
  check('anon_cannot_execute_secret_reader',privilege,false);
  let fallback=false;
  try { fallback=(await db.query("select length(coalesce(public.get_media_gateway_secret(),''))>0 ok")).rows[0].ok; }
  catch { /* Raising for missing server configuration is also a valid fail-closed result. */ }
  check('unconfigured_secret_has_no_fallback',fallback,false);
  await db.query("select set_config('app.settings.media_hmac_secret','round2_local_fixture_secret_only',false),set_config('app.settings.media_gateway_secret','round2_local_fixture_secret_only',false)");
  const modernVerifier=(await db.query("select to_regprocedure('public.verify_media_access_grant(uuid,text,text,bigint)') is not null present")).rows[0].present;
  const forged = modernVerifier
   ? (await db.query("select public.verify_media_access_grant($1::uuid,'fixture-invalid-grant','full',(extract(epoch from now())::bigint+86400)) ok",[id])).rows[0].ok
   : (await db.query(`select public.verify_media_access_grant($1::uuid,$2::uuid,'admin',(extract(epoch from now())::bigint+86400),encode(hmac(format('%s:%s:%s:%s',$1::uuid,$2::uuid,'admin',(extract(epoch from now())::bigint+86400)),public.get_media_gateway_secret(),'sha256'),'hex')) ok`,[id,owner])).rows[0].ok;
  check('reject_unbound_nonexistent_report_grant',forged,false);
 }else{
  check('secret_reader_removed',helper,false);
 }
 await db.exec(`insert into auth.users(id,email) values('${owner}','owner@fixture.invalid'),('${tester}','tester@fixture.invalid');insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number) values('${campaign}','${owner}','Fixture','audit.fixture','open',1);set request.jwt.claim.sub='${tester}';`);
 for(const [name,existingFull,thumb,expected] of [
  ['rpc_rejects_thumbnail_without_any_full',false,url('thumb_'+id),false],
  ['rpc_rejects_other_thumbnail_when_full_parameter_omitted',true,url('thumb_'+other),false],
  ['rpc_allows_matching_thumbnail_backfill_when_full_parameter_omitted',true,url('thumb_'+id),true],
 ]){
  await db.exec('begin');
  try{
   if(existingFull)await db.query('select public.submit_tester_report($1::uuid,$2::text,$3::text,5,$4::text,$5::uuid)',[campaign,'fixture',url(id),'0123456789abcdef',id]);
   let accepted=false;
   try{await db.query('select public.submit_tester_report($1::uuid,$2::text,null,5,null,$3::uuid,$4::text)',[campaign,'fixture',existingFull?other:id,thumb]);accepted=true;}catch{}
   check(name,accepted,expected);
  }finally{await db.exec('rollback');}
 }
}finally{await db.close();}
writeFileSync(new URL('./db-check-results.json',import.meta.url),JSON.stringify(rows,null,2));
console.log(JSON.stringify(rows,null,2));
assert.equal(rows.filter(x=>!x.pass).length,0,'Round 2 database regressions remain; see db-check-results.json');
