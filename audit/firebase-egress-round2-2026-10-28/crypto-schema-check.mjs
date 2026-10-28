import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../../supabase/tests/package.json',import.meta.url));
const {PGlite}=require('@electric-sql/pglite');
const {pgcrypto}=require('@electric-sql/pglite/contrib/pgcrypto');
const {uuid_ossp}=require('@electric-sql/pglite/contrib/uuid_ossp');
const db=new PGlite({extensions:{pgcrypto,uuid_ossp}});
const owner='11111111-1111-4111-8111-111111111111',tester='22222222-2222-4222-8222-222222222222',campaign='33333333-3333-4333-8333-333333333333',report='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
let outcome;
try{
 await db.exec(`create role anon nologin;create role authenticated nologin;create role service_role nologin bypassrls;create schema auth;create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',email_confirmed_at timestamptz,confirmation_token text,email_change text,email_change_token_new text,recovery_token text);create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;grant usage on schema public,auth to anon,authenticated,service_role;grant execute on function auth.uid() to anon,authenticated,service_role;create schema extensions;create extension pgcrypto with schema extensions;`);
 for(const file of ['supabase/full_schema_singapore.sql','supabase/migrations/045_thumbnail_support.sql','supabase/migrations/046_media_access_gateway.sql'])await db.exec(readFileSync(new URL('../../'+file,import.meta.url),'utf8'));
 await db.exec(`insert into auth.users(id,email) values('${owner}','owner@fixture.invalid'),('${tester}','tester@fixture.invalid');insert into public.campaigns(id,owner_id,app_name,package_name,status,slot_number) values('${campaign}','${owner}','Fixture','audit.fixture','open',1);insert into public.tester_reports(id,campaign_id,user_id,screenshot_url,rating) values('${report}','${campaign}','${tester}','https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${report}.jpg?alt=media&token=fixture',5);set request.jwt.claim.sub='${tester}';`);
 await db.query("select set_config('app.settings.media_gateway_secret','round2_local_fixture_secret_only',false),set_config('app.settings.media_gateway_base_url','https://gateway.fixture.invalid/v1/media-gateway',false)");
 try{
  const grant=(await db.query('select public.get_report_media_access($1::uuid) result',[report])).rows[0].result;
  outcome={name:'configured_grant_with_pgcrypto_in_extensions_schema',success:grant.success===true};
 }catch(err){outcome={name:'configured_grant_with_pgcrypto_in_extensions_schema',success:false,error:err.message,code:err.code};}
}finally{await db.close();}
writeFileSync(new URL('./crypto-schema-results.json',import.meta.url),JSON.stringify(outcome,null,2));
console.log(JSON.stringify(outcome,null,2));
assert.equal(outcome.success,true,'Gateway grant must work with Supabase pgcrypto installed in extensions schema');
