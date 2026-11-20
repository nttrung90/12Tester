// Generate a first-install SQL Editor bundle without rewriting migration history.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const root=new URL('../../',import.meta.url);
const files=['091_dual_storage_report_screenshots.sql','092_dual_storage_hardening.sql','093_dual_storage_runtime_recheck.sql'];
const parts=[],hashes={};
for(const name of files){
 const bytes=readFileSync(new URL('supabase/migrations/'+name,root));
 hashes[name]=createHash('sha256').update(bytes).digest('hex');
 let sql=bytes.toString('utf8').replaceAll('\r\n','\n');
 if(name.startsWith('091_')){
  const start=sql.indexOf('CREATE SCHEMA IF NOT EXISTS storage;');
  const end=sql.indexOf('-- ----------------------------------------------------------------------------\n-- 2. Organization Usage',start);
  assert.ok(start>=0&&end>start,'091 Storage bootstrap boundary changed; review generator');
  const bootstrap=sql.slice(start,end);
  assert.equal((bootstrap.match(/CREATE TABLE IF NOT EXISTS storage\./g)||[]).length,2);
  assert.ok(bootstrap.includes('INSERT INTO storage.buckets'));
  sql=sql.slice(0,start)+'-- Hosted Storage already exists; bucket configuration was checked by preflight.\n\n'+sql.slice(end);
  const rls='        ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;';
  assert.equal(sql.split(rls).length,2,'091 Storage RLS statement changed');
  sql=sql.replace(rls,'        -- Managed objects RLS must already be enabled; checked before any changes.');
 }
 // Only standalone transaction delimiters are removed; function BEGIN/END bodies
 // are untouched. Reject source layout changes instead of guessing new boundaries.
 const begins=[...sql.matchAll(/^BEGIN;$/gm)];
 const commits=[...sql.matchAll(/^COMMIT;$/gm)];
 assert.equal(begins.length,name.startsWith('091_')?0:1,name+' transaction layout changed');
 assert.equal(commits.length,1,name+' transaction layout changed');
 assert.ok(sql.trimEnd().endsWith('COMMIT;'));
 sql=sql.replace(/^BEGIN;\n/m,'').replace(/COMMIT;\s*$/,'');
 parts.push('-- Source: '+name+'\n'+sql.trim());
}

const preflight=String.raw`
DO $hosted_install_preflight$
DECLARE b record; required_table text;
BEGIN
 IF current_user IN ('anon','authenticated','service_role','authenticator') THEN
  RAISE EXCEPTION 'Use the SQL Editor migration role (postgres), not an API role';
 END IF;
 IF to_regnamespace('storage') IS NULL OR to_regclass('storage.buckets') IS NULL OR to_regclass('storage.objects') IS NULL THEN
  RAISE EXCEPTION 'Managed Storage is not initialized. Initialize Storage through Supabase; do not create its tables manually';
 END IF;
 IF NOT has_schema_privilege(current_user,'storage','USAGE') THEN
  RAISE EXCEPTION 'Migration role lacks Storage USAGE. Select the postgres role in SQL Editor';
 END IF;
 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='storage.objects'::regclass) THEN
  RAISE EXCEPTION 'Managed storage.objects RLS is disabled; check the Storage service before installing';
 END IF;
 IF to_regclass('public.storage_organization_usage') IS NOT NULL OR to_regclass('public.report_media_uploads') IS NOT NULL THEN
  RAISE EXCEPTION 'Dual Storage already exists or was partially installed. Do not replay 091; inspect installed state and use a forward repair';
 END IF;
 FOREACH required_table IN ARRAY ARRAY['profiles','campaigns','campaign_members','tester_reports','tester_report_attachments','report_submission_receipts','business_policies'] LOOP
  IF to_regclass('public.'||required_table) IS NULL THEN
   RAISE EXCEPTION 'Missing public.%: install the existing application migrations through 090 first',required_table;
  END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_policy WHERE polrelid='storage.objects'::regclass AND polname IN('storage_objects_read_policy','storage_objects_insert_policy')) THEN
  RAISE EXCEPTION 'Storage policy name conflict. Review existing policies before installing; this bundle will not overwrite an unrelated policy';
 END IF;
 SELECT public,file_size_limit,allowed_mime_types INTO b FROM storage.buckets WHERE id='report-screenshots';
 IF NOT FOUND THEN
  RAISE EXCEPTION 'Create private bucket report-screenshots through Storage Dashboard/API first (2 MB; image/jpeg, image/png, image/webp)';
 END IF;
 IF b.public IS DISTINCT FROM false OR b.file_size_limit IS NULL OR b.file_size_limit NOT BETWEEN 2000000 AND 2097152
    OR b.allowed_mime_types IS NULL
    OR NOT (b.allowed_mime_types @> ARRAY['image/jpeg','image/png','image/webp']::text[]
            AND b.allowed_mime_types <@ ARRAY['image/jpeg','image/png','image/webp']::text[]) THEN
  RAISE EXCEPTION 'Bucket report-screenshots must be private, limit 2 MB (2000000..2097152 bytes), MIME image/jpeg,image/png,image/webp only. Configure it via Dashboard/API';
 END IF;
END
$hosted_install_preflight$;
`;
const output=`-- GENERATED: node supabase/tools/build-dual-storage-hosted-install.mjs
-- FIRST INSTALL ONLY: existing app through 090; 091 has not been installed.
-- Includes 091 -> 092 -> 093 in one transaction. Do not run those originals afterward.
-- Initialize/configure private report-screenshots bucket through Dashboard/API first.
-- No Storage table creation, schema alterations, ownership changes or metadata DML.
-- RLS policy management remains part of the installation.
${Object.entries(hashes).map(([n,h])=>'-- SHA256 '+n+': '+h).join('\n')}

BEGIN;
${preflight}

${parts.join('\n\n')}

COMMIT;
`;
assert.doesNotMatch(output,/\b(?:CREATE\s+(?:TABLE|SCHEMA)|ALTER\s+TABLE|DROP\s+TABLE)\s+(?:IF\s+NOT\s+EXISTS\s+)?storage(?:\.|\s*;)/i);
assert.doesNotMatch(output,/\b(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM|TRUNCATE(?:\s+TABLE)?)\s+storage\./i);
const target=new URL('supabase/operations/091_093_dual_storage_hosted_install.sql',root);
writeFileSync(target,output);
console.log(JSON.stringify({output:fileURLToPath(target),bytes:Buffer.byteLength(output),sources:hashes},null,2));
