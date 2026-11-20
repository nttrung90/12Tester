import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db=await createDb();
const uid='11111111-1111-1111-1111-111111111111';
async function actor(sql) {
  await db.exec(`begin;select set_config('request.jwt.claim.sub','${uid}',true);set local role authenticated;`);
  try {const r=(await db.query(sql)).rows[0].r;await db.exec('commit');return r;}
  catch(e){await db.exec('rollback');throw e;}
}
try {
  for(const f of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(f=>/^0\d\d.*\.sql$/.test(f)&&+f.slice(0,3)<=71).sort())await db.exec(read('supabase/migrations/'+f));
  await db.exec(`insert into auth.users(id,email)values('${uid}','owner@fixture.invalid');update public.profiles set credits=1000 where id='${uid}';`);
  const quote=await actor("select public.get_business_quote('all') as r");
  const call=`select public.unlock_or_renew_slot_v2(2,'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',50,'${quote.revision}') as r`;
  const first=await actor(call);
  let replay=null,error=null;
  try {replay=await actor(call);}catch(e){error=e.message;}
  const balance=(await db.query(`select credits from public.profiles where id='${uid}'`)).rows[0].credits;
  console.log(JSON.stringify({firstSuccess:first.success,originalToken:quote.revision,receiptToken:first.quote_token,replayed:replay?.replayed,error,balance},null,2));
  assert.equal(replay?.replayed,true,'The actual Android all-quote token must replay with the exact originally confirmed token');
  assert.equal(balance,950,'An idempotent retry must not charge twice');
} finally {await db.close();}
