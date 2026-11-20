import assert from 'node:assert/strict';
import {readdirSync} from 'node:fs';
import {createDb,read} from '../../supabase/tools/baseline-lib.mjs';
const files=readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^\d{3}_.*\.sql$/.test(f)).sort();
const oldSql=files.filter(f=>+f.slice(0,3)>=62&&+f.slice(0,3)<=73).map(f=>read('supabase/migrations/'+f));
const newSql=files.filter(f=>+f.slice(0,3)>73).map(f=>read('supabase/migrations/'+f));
const owner='11111111-1111-1111-1111-111111111111',tester='22222222-2222-2222-2222-222222222222',campaign='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
for(const historicalCount of [1,2]){
 const db=await createDb({legacyGrants:true});
 try{
  for(const sql of oldSql)await db.exec(sql);
  await db.exec(`insert into auth.users(id,email) values('${owner}','owner@upgrade.invalid'),('${tester}','tester@upgrade.invalid');
   insert into public.campaigns(id,owner_id,package_name,app_name,status) values('${campaign}','${owner}','invalid.upgrade.app','Upgrade fixture','open');
   insert into public.tester_reports(campaign_id,user_id,submission_context,content,created_at)
   select '${campaign}','${tester}','app_test','Historical day '||n,now()-make_interval(days=>n) from generate_series(1,${historicalCount}) n;`);
  const before=(await db.query('select id,created_at,content from public.tester_reports order by created_at')).rows;
  for(const sql of newSql)await db.exec(sql);
  const rows=(await db.query("select id,content,report_date::text d,(created_at at time zone 'Asia/Ho_Chi_Minh')::date::text expected from public.tester_reports order by created_at")).rows;
  assert.equal(rows.length,historicalCount);
  assert.deepEqual(rows.map(r=>r.id),before.map(r=>r.id));
  for(const row of rows)assert.equal(row.d,row.expected,'historical report must retain its original Vietnamese reporting day');
  console.log('PASS independent migration upgrade: preserves',historicalCount,'historical reporting days');
 }catch(e){console.error('FAIL independent migration upgrade:',historicalCount,'historical reports',e.code??'',e.message);process.exitCode=1;}
 finally{await db.close();}
}
{
 const db=await createDb({legacyGrants:true});
 try{
  for(const sql of [...oldSql,...newSql])await db.exec(sql);
  const helpers=(await db.query(`select p.proname,pg_get_function_identity_arguments(p.oid) args,has_function_privilege('authenticated',p.oid,'EXECUTE') allowed from pg_proc p where p.pronamespace='public'::regnamespace and p.proname='_submit_tester_report_core'`)).rows;
  assert.equal(helpers.length,1);
  for(const helper of helpers)assert.equal(helper.allowed,false,'authenticated clients must not execute the private submission core directly');
  const writes=(await db.query("select has_table_privilege('authenticated','public.tester_report_attachments','INSERT') i,has_table_privilege('authenticated','public.tester_report_attachments','UPDATE') u,has_table_privilege('authenticated','public.tester_report_attachments','DELETE') d")).rows[0];
  assert.deepEqual(writes,{i:false,u:false,d:false},'attachment writes must be RPC-only under inherited Supabase grants');
  console.log('PASS independent private-helper and attachment write ACL');
 }catch(e){console.error('FAIL independent new ACL:',e.code??'',e.message);process.exitCode=1;}
 finally{await db.close();}
}
