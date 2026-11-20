// Reproduce only the setup SQL used by the real PostgreSQL runner, using its
// existing PGlite fixture. This does NOT test real multi-connection concurrency.
import { createDb, read } from '../../supabase/tools/baseline-lib.mjs';
import { readdirSync } from 'node:fs';

const db = await createDb();
for (const migration of readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(n => /^\d+.*\.sql$/.test(n)).sort()) {
  await db.exec(read('supabase/migrations/' + migration));
}
const admin = '11111111-1111-1111-1111-111111111111';
const target = '22222222-2222-2222-2222-222222222222';
await db.exec(`
  insert into auth.users(id, email) values ('${admin}','runner-admin@test.local'),('${target}','runner-target@test.local');
  update public.profiles set role='admin' where id='${admin}';
  begin;
  select set_config('request.jwt.claim.sub','${admin}',true);
  set local role authenticated;
  select public.admin_send_notification('${target}','Concurrency Test Notif','Test msg','admin');
`);
const lookup = await db.query(`select id from public.notifications where user_id='${target}' and title='Concurrency Test Notif'`);
console.log(`RUNNER SETUP PROBE 1: rows visible to authenticated admin sender = ${lookup.rows.length}`);
console.log('The runner reads rows[0].id here; zero rows means it fails before starting any concurrent claims.');
await db.exec('reset role');
const notification = (await db.query(`select id from public.notifications where user_id='${target}' and title='Concurrency Test Notif'`)).rows[0];
await db.exec('savepoint setup_probe; set local role authenticated');
try {
  await db.query(`update public.notification_popup_states set display_count=4 where notification_id='${notification.id}'`);
  console.log('RUNNER SETUP PROBE 2: authenticated role can fast-forward state (unexpected).');
} catch (err) {
  console.log(`RUNNER SETUP PROBE 2: ${err.message}`);
  await db.exec('rollback to savepoint setup_probe');
}
await db.exec('rollback');
await db.close();
