import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
const db = await createDb();
const admin = '11111111-1111-1111-1111-111111111111';
async function actor(sql) {
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${admin}',true); set local role authenticated;`);
  try { const r = (await db.query(sql)).rows[0].r; await db.exec('commit'); return r; }
  catch(e) { await db.exec('rollback'); throw e; }
}
try {
  for (const f of readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(f=>/^0\d\d.*\.sql$/.test(f)&&+f.slice(0,3)<=69).sort()) await db.exec(read('supabase/migrations/'+f));
  await db.exec(read('audit/admin-expansion-2026-11-01/notification-stage/072_admin_notification_campaigns.sql'));
  await db.exec(`insert into auth.users(id,email) values ('${admin}','admin@fixture.invalid'); update public.profiles set role='admin' where id='${admin}';`);
  const draft = await actor("select public.admin_create_notification_campaign(p_request_id=>'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',p_title_vi=>'Lịch thử',p_title_en=>'Test schedule',p_body_vi=>'Nội dung',p_body_en=>'Body') as r");
  const scheduledFor = (await db.query("select to_char((clock_timestamp()+interval '1 second') at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as t")).rows[0].t;
  const sql = `select public.admin_schedule_notification_campaign('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','${draft.campaign.id}',${draft.campaign.revision},'${scheduledFor}'::timestamptz,'Replay test') as r`;
  const first = await actor(sql);
  await new Promise(resolve=>setTimeout(resolve,1200));
  let replay=null,error=null;
  try { replay=await actor(sql); } catch(e) { error=e.message; }
  console.log(JSON.stringify({firstSuccess:first.success,replaySuccess:replay?.success,replayed:replay?.replayed,error},null,2));
  assert.equal(replay?.replayed,true,'Committed schedule must replay after its scheduled time has passed');
} finally { await db.close(); }
