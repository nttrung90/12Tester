import assert from 'node:assert/strict';
import {readFileSync,readdirSync,mkdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createDb,queries} from '../tools/baseline-lib.mjs';
const root=new URL('../../',import.meta.url),read=p=>readFileSync(new URL(p,root),'utf8');
const original=read('supabase/migrations/091_dual_storage_report_screenshots.sql');
const bundle=read('supabase/operations/091_093_dual_storage_hosted_install.sql');
const files=readdirSync(new URL('../migrations/',import.meta.url)).filter(f=>/^\d+_.*\.sql$/.test(f)).sort();
const db=await createDb(),reference=await createDb(),results=[];
const one=async(q,p)=>(await db.query(q,p)).rows[0];
const test=async(name,fn)=>{try{const evidence=await fn();results.push({name,status:'PASS',evidence});}catch(e){results.push({name,status:'FAIL',error:e.message});throw e;}};
const migrationRole=()=>db.exec('set role hosted_migration_test');
const admin=()=>db.exec('reset role');
const absent=async()=>assert.equal((await one("select to_regclass('public.report_media_uploads') is null absent")).absent,true);
const rejected=async(sql,pattern)=>{
 let error;try{await db.exec(sql);}catch(e){error=e;}
 await db.exec('rollback');assert.ok(error,'Expected a refusal');assert.match(error.message,pattern);return {code:error.code,message:error.message};
};
const snapshot=async()=>({
 buckets:(await db.query('select to_jsonb(b) b from storage.buckets b order by id')).rows,
 objects:(await db.query('select to_jsonb(o) o from storage.objects o order by id')).rows,
 ownership:(await db.query("select n.nspname,c.relname,pg_get_userbyid(c.relowner) owner from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='storage' and c.relkind='r' order by c.relname")).rows,
});
try{
 for(const f of files.filter(f=>parseInt(f)<=90))await db.exec(read('supabase/migrations/'+f));
 // Test fixture only. Hosted Supabase already supplies these platform tables.
 const start=original.indexOf('CREATE SCHEMA IF NOT EXISTS storage;');
 const end=original.indexOf('INSERT INTO storage.buckets',start);
 await db.exec(original.slice(start,end));
 await db.exec(`
  create role hosted_storage_platform nologin;
  create role hosted_migration_test nologin nosuperuser inherit;
  grant postgres to hosted_migration_test;
  alter schema storage owner to hosted_storage_platform;
  grant usage on schema storage to postgres,authenticated,service_role;
  revoke create on schema storage from public,postgres,hosted_migration_test;
  grant usage,create on schema public to hosted_migration_test;
  alter table storage.objects enable row level security;
 `);
 await migrationRole();
 await test('original 091 reproduces schema permission error with existing Storage tables',async()=>{
  const capabilities=await one("select current_user role,current_setting('is_superuser') superuser,has_schema_privilege(current_user,'storage','CREATE') can_create");
  assert.equal(capabilities.superuser,'off');assert.equal(capabilities.can_create,false);
  const error=await rejected(original,/permission denied for schema storage/);assert.equal(error.code,'42501');await absent();return {capabilities,error};
 });
 await test('missing bucket refuses before application mutations',async()=>{
  const e=await rejected(bundle,/Create private bucket report-screenshots/);await absent();return e;
 });
 await admin();await db.exec("insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('report-screenshots','report-screenshots',true,2097152,ARRAY['image/jpeg','image/png','image/webp'])");await migrationRole();
 await test('public bucket refuses without silently changing its configuration',async()=>{
  const e=await rejected(bundle,/must be private/);await absent();assert.equal((await one("select public from storage.buckets where id='report-screenshots'")).public,true);return e;
 });
 await admin();await db.exec("update storage.buckets set public=false where id='report-screenshots';alter table storage.objects disable row level security");await migrationRole();
 await test('disabled managed RLS refuses instead of altering platform table',async()=>{
  const e=await rejected(bundle,/RLS is disabled/);await absent();return e;
 });
 await admin();await db.exec("alter table storage.objects enable row level security;create policy storage_objects_read_policy on storage.objects for select to authenticated using(false)");await migrationRole();
 await test('existing policy name conflict is preserved and refused',async()=>{
  const e=await rejected(bundle,/policy name conflict/);await absent();assert.equal((await one("select count(*)::int n from pg_policy where polname='storage_objects_read_policy'")).n,1);return e;
 });
 await admin();await db.exec("drop policy storage_objects_read_policy on storage.objects;insert into storage.buckets(id,name,public) values('avatars','avatars',false);insert into storage.objects(id,bucket_id,name,metadata) values('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','avatars','unchanged.jpg','{\"size\":123}');create policy unrelated_avatar_policy on storage.objects for select to authenticated using(bucket_id='avatars')");
 const before=await snapshot();await migrationRole();
 await test('failure at end rolls back the entire 091-093 installation',async()=>{
  const functionsBefore=(await db.query(queries.functions)).rows;
  const interrupted=bundle.replace(/COMMIT;\s*$/,"DO $forced_failure$ BEGIN RAISE EXCEPTION 'FORCED_INSTALL_FAILURE'; END $forced_failure$;\nCOMMIT;");
  await rejected(interrupted,/FORCED_INSTALL_FAILURE/);
  await absent();assert.deepEqual(await snapshot(),before);
  assert.deepEqual((await db.query(queries.functions)).rows,functionsBefore);
  assert.equal((await one("select count(*)::int n from pg_policy where polname IN('storage_objects_read_policy','storage_objects_insert_policy')")).n,0);
 });
 await test('hosted bundle installs full 091-093 without schema CREATE permission',async()=>{
  await db.exec(bundle);
  assert.equal((await one("select has_schema_privilege(current_user,'storage','CREATE') value")).value,false);
  assert.deepEqual(await snapshot(),before);
  assert.equal((await one("select count(*)::int n from pg_policy where polname='unrelated_avatar_policy'")).n,1);
  assert.deepEqual(await one("select is_org_metric_valid valid,dispatch_mode mode from public.storage_organization_usage where id=1"),{valid:false,mode:'google_overflow'});
  assert.equal((await one("select has_function_privilege('authenticated','public.request_report_media_reservation(uuid,text,bigint,boolean,uuid)','execute') allowed")).allowed,true);
  assert.equal((await one("select has_function_privilege('authenticated','public.record_report_media_deleted(uuid,uuid,text[])','execute') allowed")).allowed,false);
 });
 await test('installed database refuses replay of bootstrap bundle',async()=>{
  const e=await rejected(bundle,/already exists or was partially installed/);assert.deepEqual(await snapshot(),before);return e;
 });
 await test('all final public function definitions match canonical 091-093 chain',async()=>{
  for(const f of files.filter(f=>parseInt(f)<=93))await reference.exec(read('supabase/migrations/'+f));
  const defs=async(conn)=>(await conn.query(queries.functions)).rows.map(r=>({name:r.name,args:r.args,definition:r.definition}));
  const actual=await defs(db),expected=await defs(reference);assert.deepEqual(actual,expected);return {functions:actual.length};
 });
 await test('094 applies after the hosted 091-093 bundle',async()=>{
  const f=files.find(f=>f.startsWith('094_'));assert.ok(f);await db.exec(read('supabase/migrations/'+f));return {migration:f};
 });
}catch(e){console.error(e.message);process.exitCode=1;}
finally{await db.close();await reference.close();}
const folder=new URL('audit/dual-storage-hosted-install-2026-11-17/',root);mkdirSync(folder,{recursive:true});
const report={at:new Date().toISOString(),scope:'PGlite restricted-CREATE simulation; not live Supabase or actual Storage API',bundleSha256:createHash('sha256').update(bundle).digest('hex'),results};
writeFileSync(new URL('results.json',folder),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));if(results.some(r=>r.status!=='PASS'))process.exitCode=1;
