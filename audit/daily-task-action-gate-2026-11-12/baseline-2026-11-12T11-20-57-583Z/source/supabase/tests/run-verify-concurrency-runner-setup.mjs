import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

for (const migration of readdirSync(new URL('../../supabase/migrations/', import.meta.url)).filter(n => /^\d+.*\.sql$/.test(n)).sort()) {
  await db.exec(read('supabase/migrations/' + migration));
}

const adminId = '11111111-1111-1111-1111-111111111111';
const targetUser = '22222222-2222-2222-2222-222222222222';

// Administrative setup (service_role / superuser)
await db.exec(`
  insert into auth.users(id, email) values ('${adminId}', 'runner-admin@test.local'), ('${targetUser}', 'runner-target@test.local');
  insert into public.profiles(id, email, display_name, role, is_locked) values
    ('${adminId}', 'runner-admin@test.local', 'Runner Admin', 'admin', false),
    ('${targetUser}', 'runner-target@test.local', 'Runner Target', 'member', false)
  on conflict (id) do update set role = excluded.role, is_locked = excluded.is_locked;
`);

// Send notification as authenticated admin
await db.exec(`
  select set_config('request.jwt.claim.sub', '${adminId}', false);
  set role authenticated;
  select public.admin_send_notification('${targetUser}', 'Concurrency Test Notif', 'Test msg', 'admin');
  reset role;
  reset "request.jwt.claim.sub";
`);

// Retrieve notification ID as administrative connection (outside target RLS)
const notifRow = (await db.query(`
  select id from public.notifications where user_id = '${targetUser}' and title = 'Concurrency Test Notif'
`)).rows[0];

assert.ok(notifRow, 'Step 1: notifRow must be found by administrative query');
assert.ok(notifRow.id, 'Step 1: notifRow.id must not be undefined');

// Fast-forward display_count to 4 as administrative connection (outside client privileges)
await db.query(`
  update public.notification_popup_states set display_count = 4 where notification_id = '${notifRow.id}'
`);

const finalState = (await db.query(`
  select display_count from public.notification_popup_states where notification_id = '${notifRow.id}'
`)).rows[0];

assert.equal(finalState.display_count, 4, 'Step 2: display_count must be updated to 4');

console.log('PASS: Concurrency runner setup SQL verified successfully on PGlite.');
await db.close();
