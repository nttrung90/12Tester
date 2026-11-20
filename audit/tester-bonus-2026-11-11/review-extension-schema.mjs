import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb({legacyGrants:true});
try{
  for(const file of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort())await db.exec(read(`supabase/migrations/${file}`));
  await db.exec('create schema if not exists extensions; alter extension pgcrypto set schema extensions; grant usage on schema extensions to authenticated,service_role;');
  const owner=randomUUID(),campaign=randomUUID();
  await db.query('insert into auth.users(id,email) values($1,$2)',[owner,`${owner}@fixture.local`]);
  await db.query('update public.profiles set credits=1000 where id=$1',[owner]);
  await db.query("insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values($1,$2,$3,'Extensions fixture','open',1)",[campaign,owner,`fixture.extensions.${campaign.replaceAll('-','')}`]);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[owner]);
  await db.exec('set role authenticated');
  const result=(await db.query('select public.create_campaign_tester_bonus($1,5,2,$2) as result',[campaign,randomUUID()])).rows[0].result;
  assert.equal(result.success,true);
  console.log('PASS: create bonus RPC works with pgcrypto in extensions schema');
}finally{await db.close();}
