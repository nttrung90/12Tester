import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';

const through = Number(process.argv[2] ?? 999);
const db = await createDb({ legacyGrants: true });
const admin = 'e1111111-1111-4111-8111-111111111111';
const owner = 'e2222222-2222-4222-8222-222222222222';
const app = 'eaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
let failures = 0;
async function actor(uid, query) {
  await db.exec(`begin; select set_config('request.jwt.claim.sub','${uid}',true); set local role authenticated;`);
  try {
    const result = (await db.query(query)).rows[0]?.r;
    await db.exec('commit');
    return result;
  } catch (error) {
    await db.exec('rollback');
    throw error;
  }
}
async function check(name, fn) {
  try { await fn(); console.log(`PASS through ${through}: ${name}`); }
  catch (error) { failures++; console.log(`FAIL through ${through}: ${name}: ${error.message}`); }
}
try {
  for (const file of readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(name => /^\d{3}_.*\.sql$/.test(name) && +name.slice(0, 3) >= 62 && +name.slice(0, 3) <= through).sort()) {
    await db.exec(read('supabase/migrations/' + file));
  }
  await db.exec(`insert into auth.users(id,email) values ('${admin}','contract-admin@fixture.invalid'),('${owner}','contract-owner@fixture.invalid');
    update public.profiles set role='admin' where id='${admin}';
    insert into public.campaigns(id,owner_id,app_name,package_name,status) values ('${app}','${owner}','Contract','invalid.review.resume','paused');
    update public.campaigns set paused_reason='owner_inactive' where id='${app}';`);
  const resumed = await actor(admin, `select public.admin_resume_campaign('${app}') as r`);
  await check('admin resume preserves JSON fields and owner_inactive flag', async () => {
    assert.equal(resumed.status, 'open');
    assert.equal(resumed.paused_reason, null);
    assert.equal(resumed.admin_resumed, true);
  });
  await check('admin resume audit preserves original reason', async () => {
    const event = (await db.query(`select reason from public.admin_action_events where action='resume_campaign' and target_id='${app}' order by created_at desc limit 1`)).rows[0];
    assert.equal(event.reason, 'Khôi phục chiến dịch bởi Admin');
  });
  await db.exec(`create or replace function public.refresh_daily_completion(p_user_id uuid,p_date date default null)
    returns void language plpgsql security definer set search_path to public as $$ begin return; end $$;`);
  await check('owner resume allows streak 4 with configured threshold 7', async () => {
    await db.exec(`update public.owner_auto_pause_configs set missed_days=7 where id='default';
      update public.profiles set daily_missed_streak=4 where id='${owner}';
      update public.campaigns set status='paused',paused_reason='owner_manual',admin_resumed=false where id='${app}';`);
    const result = await actor(owner, `select public.resume_campaign('${app}') as r`);
    assert.equal(result.status, 'open');
  });
  await check('owner resume blocks streak 1 with configured threshold 1', async () => {
    await db.exec(`update public.owner_auto_pause_configs set missed_days=1 where id='default';
      update public.profiles set daily_missed_streak=1 where id='${owner}';
      update public.campaigns set status='paused',paused_reason='owner_manual',admin_resumed=false where id='${app}';`);
    await assert.rejects(() => actor(owner, `select public.resume_campaign('${app}') as r`), /OWNER_INACTIVE/);
  });
  console.log(`Resume contract failures: ${failures}`);
  process.exitCode = failures ? 1 : 0;
} finally {
  await db.close();
}
