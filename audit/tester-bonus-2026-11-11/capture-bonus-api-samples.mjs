import { writeFileSync } from 'node:fs';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
const db = new pg.Client({host:'127.0.0.1',port:55439,user:'bonus_test_admin',database:'tester_bonus_test',connectionTimeoutMillis:5000});
await db.connect();
try {
  const {rows:[round]}=await db.query("select r.* from public.campaign_tester_bonus_rounds r where r.campaign_id is not null order by r.created_at limit 1");
  if(!round)throw new Error('No isolated fixture round to sample');
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[round.owner_id]);
  await db.query('set role authenticated');
  const owner=(await db.query('select public.get_campaign_tester_bonus($1) as data',[round.campaign_id])).rows[0].data;
  const reconcile=(await db.query('select public.reconcile_campaign_tester_bonus($1,$2) as data',[round.campaign_id,round.client_request_id])).rows[0].data;
  const detail=(await db.query('select public.get_campaign_detail($1) as data',[round.campaign_id])).rows[0].data;
  await db.query('reset role');
  const {rows:[tester]}=await db.query('select user_id from public.tester_reports where campaign_id=$1 limit 1',[round.campaign_id]);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[tester.user_id]);
  await db.query('set role authenticated');
  const publicView=(await db.query('select public.get_campaign_tester_bonus($1) as data',[round.campaign_id])).rows[0].data;
  const samples={note:'All data here is isolated local fake fixture data; actual SQL API shape, not production data.',owner,reconcile,tester:publicView,report:detail.reports?.[0]??null};
  writeFileSync(new URL('./api-runtime-samples.json',import.meta.url),JSON.stringify(samples,null,2));
  console.log(JSON.stringify({ownerKeys:Object.keys(owner),reconcileKeys:Object.keys(reconcile),testerKeys:Object.keys(publicView),reportBonus:detail.reports?.map(r=>({id:r.id,bonus_awarded:r.bonus_awarded,tester_bonus_awarded:r.tester_bonus_awarded,tester_bonus_round_id:r.tester_bonus_round_id}))},null,2));
} finally {await db.end();}
