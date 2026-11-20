// Preserve the original assertions; adapt only setup/transport fixtures to the
// explicit host configuration, real lease expiry, UUID ack and read-only dry run.
import {readFileSync,writeFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
let source=readFileSync(new URL('supervisor-baseline/review-probes.mjs',import.meta.url),'utf8');
const replacements=[];
function change(old,value){if(source.split(old).length!==2)throw Error('Fixture anchor not unique: '+old);source=source.replace(old,value);replacements.push({old,value});}
change('async function check(id, title, fn, database = true) {',`await sql("insert into public.storage_trusted_hosts(host) values('example.supabase.co'); update public.storage_organization_usage set local_project_ref='aaaaaaaaaaaaaaaaaaaa';");
async function check(id, title, fn, database = true) {`);
change("set updated_at=now()-interval '1 hour' where upload_id='retry'", "set updated_at=now()-interval '1 hour',lease_expires_at=now()-interval '1 second' where upload_id='retry'");
change("return { data: (await one('select public.get_expired_report_media_for_cleanup(50,300) as r')).r, error: null };", "return { data: (await one('select public.get_expired_report_media_for_cleanup(50,300,true) as r')).r, error: null };");
change('update public.storage_organization_usage set used_storage_bytes=1000;',"update public.storage_organization_usage set used_storage_bytes=1000; update public.report_media_uploads set quota_accounted_bytes=100 where upload_id='deleted';");
change('supabaseClient: { rpc: async () => ({ data: {}, error: null }) },','supabaseClient: { rpc: async () => ({ data: { success: true }, error: null }) },');
// Outputs remain separate from original evidence.
source=source.replaceAll('review-probe-results.json','supervisor-media-results.json').replaceAll('source-snapshot.json','supervisor-media-snapshot.json');
const output=new URL('supervisor-media-generated.mjs',import.meta.url);
writeFileSync(output,source);
writeFileSync(new URL('supervisor-fixture-changes.json',import.meta.url),JSON.stringify(replacements,null,2)+'\n');
const result=spawnSync(process.execPath,[fileURLToPath(output)],{stdio:'inherit'});
process.exitCode=result.status??1;
