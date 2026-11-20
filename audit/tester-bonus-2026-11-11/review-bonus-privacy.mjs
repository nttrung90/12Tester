import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db = await createDb({ legacyGrants: true });
const owner = randomUUID(), outsider = randomUUID(), campaign = randomUUID();
try {
  for (const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort()) await db.exec(read('supabase/migrations/'+name));
  await db.query('insert into auth.users(id,email) values($1,$3),($2,$4)',[owner,outsider,`${owner}@fixture.local`,`${outsider}@fixture.local`]);
  await db.query('update public.profiles set credits=1000 where id=$1',[owner]);
  await db.query("insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number) values($1,$2,$3,'Privacy probe','open',1)",[campaign,owner,`fixture.privacy.${campaign.replaceAll('-','')}`]);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[owner]); await db.exec('set role authenticated');
  const {rows:[created]}=await db.query('select public.create_campaign_tester_bonus($1,5,2,$2) as result',[campaign,randomUUID()]);
  await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub',$1,false)",[outsider]); await db.exec('set role authenticated');
  let privateRows=[];
  try { privateRows=(await db.query('select reserved_credits,remaining_credits,spent_credits,refunded_credits,client_request_id,request_fingerprint from public.campaign_tester_bonus_rounds where id=$1',[created.result.round_id])).rows; }
  catch(error){if(error.code!=='42501')throw error;}
  console.log(`Unrelated tester financial rows under legacy default grants: ${privateRows.length}`);
  assert.equal(privateRows.length,0,'Unrelated tester must not read private round financial ledger or request identity, even with pre-existing public schema default grants');
  console.log('PASS: Bonus ledger read privacy with legacy default grants.');
} finally { await db.close(); }
