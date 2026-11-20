import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
import { readdirSync, writeFileSync } from 'node:fs';
const db=await createDb(), output=[];
const admin='11111111-1111-1111-1111-111111111111', member='22222222-2222-2222-2222-222222222222';
async function actor(uid,sql,role='authenticated'){
  await db.exec(`begin;select set_config('request.jwt.claim.sub','${uid}',true);set local role ${role};`);
  try{const rows=(await db.query(sql)).rows;await db.exec('commit');return rows;}catch(e){await db.exec('rollback');throw e;}
}
async function check(name,fn){try{output.push({name,...await fn()});}catch(e){output.push({name,error:e.message});}}
try{
  for(const name of readdirSync(new URL('../../supabase/migrations/',import.meta.url)).filter(n=>/^0\d\d.*\.sql$/.test(n)&&+n.slice(0,3)<=69).sort())await db.exec(read('supabase/migrations/'+name));
  await db.exec(read('audit/admin-expansion-2026-11-01/notification-stage/072_admin_notification_campaigns.sql'));
  await db.exec(`insert into auth.users(id,email)values('${admin}','admin@fixture.invalid'),('${member}','member@fixture.invalid');update public.profiles set role='admin' where id='${admin}';`);
  await check('different delimiter-containing payload must reject replay',async()=>{
    const a=(await actor(admin,`select public.admin_create_notification_campaign('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','A|B','C','Body VI','Body EN','vi','all',null,null,'Probe')as r`))[0].r;
    try{
      const b=(await actor(admin,`select public.admin_create_notification_campaign('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','A','B|C','Body VI','Body EN','vi','all',null,null,'Probe')as r`))[0].r;
      return{firstTitle:a.campaign.title_vi,secondTitle:b.campaign.title_vi,replayed:b.replayed,payloadMismatchRejected:false};
    }catch(e){return{payloadMismatchRejected:true,error:e.message};}
  });
  await check('empty scheduled role audience must not grow when worker starts',async()=>{
    const draft=(await actor(admin,`select public.admin_create_notification_campaign('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','Tiêu đề','Title','Nội dung','Content','vi','role','moderator',null,'Probe')as r`))[0].r;
    const sent=(await actor(admin,`select public.admin_send_now_notification_campaign('cccccccc-cccc-cccc-cccc-cccccccccccc','${draft.campaign.id}',${draft.campaign.revision},'Probe')as r`))[0].r;
    const initialCount=(await db.query(`select count(*)::int n from public.notification_campaign_recipients where campaign_id='${draft.campaign.id}'`)).rows[0].n;
    await db.exec(`update public.profiles set role='moderator' where id='${member}';`);
    const worker=(await actor('',`select public.service_process_notification_campaigns(500,10,'${draft.campaign.id}')as r`,'service_role'))[0].r;
    const afterCount=(await db.query(`select count(*)::int n from public.notification_campaign_recipients where campaign_id='${draft.campaign.id}'`)).rows[0].n;
    return{initialCount,afterCount,worker,scheduledCount:sent.campaign.total_recipients};
  });
}catch(e){output.push({fatal:e.message});}finally{await db.close();}
writeFileSync(new URL('./independent-notification-probe-results.json',import.meta.url),JSON.stringify(output,null,2));
console.log(JSON.stringify(output,null,2));
