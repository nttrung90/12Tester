import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../../supabase/tests/package.json', import.meta.url));
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const { uuid_ossp } = require('@electric-sql/pglite/contrib/uuid_ossp');
const read = p => readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8');
const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
const results = [];
const scalar = async sql => (await db.query(sql)).rows[0];
const owner = '11111111-1111-1111-1111-111111111111';
const tester = '22222222-2222-2222-2222-222222222222';
const camp = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const report = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
async function notificationCases(stage) {
  for (const kind of ['approve', 'reject', 'edit']) {
    await db.exec('begin');
    try {
      await db.exec(`insert into public.tester_reports(id,campaign_id,user_id,status,screenshot_url,provisional_granted_credits,created_at)
        values('${report}','${camp}','${tester}','pending','https://example.invalid/before',10,now()-interval '1 hour');
        delete from public.notifications;
        set request.jwt.claim.sub='${owner}';`);
      if (kind === 'approve') await db.exec(`update public.tester_reports set status='approved',bonus_awarded=2 where id='${report}'`);
      if (kind === 'reject') await db.exec(`select public.reject_tester_report('${report}'::uuid,'Ảnh không rõ ràng / không phải app này'::text)`);
      if (kind === 'edit') await db.exec(`update public.tester_reports set screenshot_url='https://example.invalid/after' where id='${report}'`);
      const rows = (await db.query(`select user_id,type,title,message,data from public.notifications where type='report'`)).rows;
      results.push({ stage, kind, count: rows.length, rows });
    } finally { await db.exec('rollback'); }
  }
}
try {
  await db.exec(`create role anon nologin; create role authenticated nologin; create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz,confirmation_token text,email_change text,email_change_token_new text,recovery_token text);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema public,auth to anon,authenticated,service_role;
    grant execute on function auth.uid() to anon,authenticated,service_role;`);
  const full = read('supabase/full_schema_singapore.sql');
  const marker = full.indexOf('-- 12 Tester - Supabase migration 055:');
  assert.ok(marker > 0, 'Must identify appended 055 to compare clean 054 vs 055');
  await db.exec(full.slice(0, marker));
  await db.exec(`insert into auth.users(id,email) values('${owner}','owner@test.local'),('${tester}','tester@test.local');
    insert into public.profiles(id,email,credits,role,created_at) values
      ('${owner}','owner@test.local',1000,'admin',now()-interval '10 days'),
      ('${tester}','tester@test.local',100,'member',now()-interval '10 days')
    on conflict(id) do update set credits=excluded.credits,role=excluded.role;
    insert into public.campaigns(id,owner_id,app_name,package_name,status,target_testers,slot_number,created_at)
      values('${camp}','${owner}','DemoApp','audit.localization.round2','open',12,1,now()-interval '3 days');
    update public.app_configs set title='Security update required',message='Install the security update' where id='android_client';`);
  await notificationCases('before_055');
  await db.exec(read('supabase/migrations/055_localization_hardening.sql'));
  await notificationCases('after_055');
  results.push({ kind: 'legacy_english_backfill', ...await scalar(`select title,title_vi,title_en,message,message_vi,message_en from public.app_configs where id='android_client'`) });
  await db.exec(`set request.jwt.claim.sub='${owner}';`);
  await db.query(`select public.admin_update_app_config(1,2,'0.0.2',false,null,null,null,'Thông báo VI','English title','Nội dung VI','English content')`);
  await db.query(`select public.admin_update_app_config(1,2,'0.0.2',false,null,null,null,null,null,null,null)`);
  results.push({ kind: 'clear_translation_with_ui_null_payload', ...await scalar(`select title_vi,title_en,message_vi,message_en from public.app_configs where id='android_client'`) });
  await db.query(`select public.admin_update_app_config(1,2,'0.0.2',false,'Legacy changed','Legacy changed content',null)`);
  results.push({ kind: 'legacy_client_update_after_bilingual_values', ...await scalar(`select title,title_vi,title_en,message,message_vi,message_en from public.app_configs where id='android_client'`) });
  writeFileSync(new URL('./database-results.json', import.meta.url), JSON.stringify(results, null, 2));
  results.forEach(r => console.log(JSON.stringify(r)));
  for (const kind of ['approve','reject','edit']) {
    assert.equal(results.find(r => r.stage==='after_055' && r.kind===kind).count,
      results.find(r => r.stage==='before_055' && r.kind===kind).count,
      `Migration must preserve report notification for ${kind}`);
  }
} finally { await db.close(); }
