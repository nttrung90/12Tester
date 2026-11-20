import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

const adminId = '11111111-1111-1111-1111-111111111111';
const userA = '22222222-2222-2222-2222-222222222222';
const userB = '33333333-3333-3333-3333-333333333333';
const lockedUser = '44444444-4444-4444-4444-444444444444';

const one = async sql => (await db.query(sql)).rows[0];

async function asUser(uid, role = 'authenticated', fn) {
  await db.exec(`savepoint sp_user; select set_config('request.jwt.claim.sub', '${uid}', true); set local role ${role};`);
  try {
    const result = await fn();
    await db.exec(`release savepoint sp_user;`);
    return result;
  } catch (err) {
    await db.exec(`rollback to savepoint sp_user;`);
    throw err;
  } finally {
    try {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', true);`);
    } catch {
      // ignore if transaction aborted
    }
  }
}

async function test(name, fn) {
  await db.exec('begin');
  try {
    await fn();
    console.log(`PASS: ${name}`);
  } catch (e) {
    console.error(`FAIL: ${name}`, e);
    throw e;
  } finally {
    await db.exec('rollback');
  }
}

console.log('=== LOADING ACTIVE MIGRATIONS (062 THROUGH 067) ===');
const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
  .filter(n => /^\d+.*\.sql$/.test(n))
  .sort();

for (const name of migrationFiles) {
  console.log(`Applying ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

// Seed users
await db.exec(`
  insert into auth.users(id, email) values
    ('${adminId}', 'admin@test.local'),
    ('${userA}', 'userA@test.local'),
    ('${userB}', 'userB@test.local'),
    ('${lockedUser}', 'locked@test.local');

  insert into public.profiles(id, email, display_name, role, is_locked) values
    ('${adminId}', 'admin@test.local', 'Admin User', 'admin', false),
    ('${userA}', 'userA@test.local', 'User A', 'member', false),
    ('${userB}', 'userB@test.local', 'User B', 'member', false),
    ('${lockedUser}', 'locked@test.local', 'Locked User', 'member', true)
  on conflict (id) do update set
    role = excluded.role,
    is_locked = excluded.is_locked;
`);

console.log('=== RUNNING ADMIN DIRECT NOTIFICATION POPUP TESTS ===');

// 1. Admin direct send registers notification and popup state in same transaction
await test('Admin direct send registers notification and popup state with count 0', async () => {
  const res = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_send_notification(
        '${userA}',
        'Tin riêng từ Admin',
        'Nội dung tin nhắn riêng',
        'warning'
      ) as r;
    `)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.sent_count, 1);
  assert.equal(res.target, userA);

  const notif = await one(`select id, user_id, title, message, type, is_read, data from public.notifications where user_id = '${userA}' and title = 'Tin riêng từ Admin'`);
  assert.ok(notif, 'Notification must exist in notifications table');
  assert.equal(notif.title, 'Tin riêng từ Admin');
  assert.equal(notif.type, 'warning', 'Custom type must be preserved');
  assert.equal(notif.is_read, false);

  const state = await one(`select notification_id, display_count from public.notification_popup_states where notification_id = '${notif.id}'`);
  assert.ok(state, 'Popup state must be registered');
  assert.equal(state.display_count, 0);
});

// 2. Broadcast does not register popup state
await test('Broadcast does NOT register popup state', async () => {
  const res = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_send_notification(
        null,
        'Thông báo chung',
        'Nội dung toàn hệ thống',
        'system'
      ) as r;
    `)).r;
  });

  assert.equal(res.success, true);
  assert.equal(res.target, 'all');

  // Check popup states for broadcast notifications
  const popupStates = (await db.query(`
    select s.* from public.notification_popup_states s
    join public.notifications n on n.id = s.notification_id
    where n.title = 'Thông báo chung'
  `)).rows;

  assert.equal(popupStates.length, 0, 'Broadcast notifications must NOT have popup states');
});

// 3. Member / Anon cannot send admin notifications or execute popup RPCs without authentication
await test('Member and anon are forbidden from sending notifications and executing popup RPCs', async () => {
  await assert.rejects(async () => {
    await asUser(userA, 'authenticated', async () => {
      await db.query(`select public.admin_send_notification('${userB}', 'Hack', 'Hack', 'admin')`);
    });
  }, /PERMISSION_DENIED/);

  // Anon calling admin_send_notification -> permission denied
  await assert.rejects(async () => {
    await asUser(userA, 'anon', async () => {
      await db.query(`select public.admin_send_notification('${userB}', 'Hack', 'Hack', 'admin')`);
    });
  }, /permission denied/i);

  // Anon calling claim_my_notification_popup -> permission denied
  await assert.rejects(async () => {
    await asUser(userA, 'anon', async () => {
      await db.query(`select public.claim_my_notification_popup('55555555-5555-5555-5555-555555555551')`);
    });
  }, /permission denied/i);

  // Anon calling acknowledge_my_notification -> permission denied
  await assert.rejects(async () => {
    await asUser(userA, 'anon', async () => {
      await db.query(`select public.acknowledge_my_notification('55555555-5555-5555-5555-555555555551')`);
    });
  }, /permission denied/i);
});

// 4. Client cannot fake popup by inserting notifications directly
await test('Client directly inserting fake notification cannot get popup', async () => {
  // User A inserts directly into notifications (permissive insert policy)
  await asUser(userA, 'authenticated', async () => {
    await db.query(`
      insert into public.notifications(user_id, title, message, type, data)
      values ('${userA}', 'Fake Admin', 'Fake Msg', 'admin', '{"sender":"admin","broadcast":false}'::jsonb);
    `);
  });

  // User A tries to insert into notification_popup_states -> should fail (permission denied)
  await assert.rejects(async () => {
    await asUser(userA, 'authenticated', async () => {
      await db.query(`
        insert into public.notification_popup_states(notification_id, display_count)
        select id, 0 from public.notifications where title = 'Fake Admin';
      `);
    });
  }, /permission denied/i);

  // Claiming should return none
  const session1 = '55555555-5555-5555-5555-555555555551';
  const claim = await asUser(userA, 'authenticated', async () => {
    return (await one(`select public.claim_my_notification_popup('${session1}') as r`)).r;
  });
  assert.equal(claim.status, 'none', 'Directly inserted notification must never be claimed as popup');
});

// 5. Trigger prevents modifying protected fields & unreading
await test('Trigger prevents client from modifying protected fields and unmarking read', async () => {
  // Admin creates direct notification
  await asUser(adminId, 'authenticated', async () => {
    await db.query(`select public.admin_send_notification('${userA}', 'Gốc', 'Nội dung gốc', 'admin')`);
  });

  const notif = await one(`select id from public.notifications where user_id = '${userA}' and title = 'Gốc'`);

  // Client A tries to modify title
  await assert.rejects(async () => {
    await asUser(userA, 'authenticated', async () => {
      await db.query(`update public.notifications set title = 'Sửa lậu' where id = '${notif.id}'`);
    });
  }, /CANNOT_MODIFY_PROTECTED_NOTIFICATION/);

  // Client A marks read via standard update (compatible with legacy app)
  await asUser(userA, 'authenticated', async () => {
    await db.query(`update public.notifications set is_read = true where id = '${notif.id}'`);
  });
  const afterRead = await one(`select is_read from public.notifications where id = '${notif.id}'`);
  assert.equal(afterRead.is_read, true, 'User must be able to mark their own notification as read');

  // Client A tries to revert is_read back to false to reactivate popup
  await assert.rejects(async () => {
    await asUser(userA, 'authenticated', async () => {
      await db.query(`update public.notifications set is_read = false where id = '${notif.id}'`);
    });
  }, /CANNOT_UNREAD_POPUP_NOTIFICATION/);
});

// 6. User B cannot claim or acknowledge User A's notification
await test('User B cannot claim or acknowledge User A notification', async () => {
  await asUser(adminId, 'authenticated', async () => {
    await db.query(`select public.admin_send_notification('${userA}', 'Tin cho A', 'Riêng', 'admin')`);
  });
  const notifA = await one(`select id from public.notifications where user_id = '${userA}' and title = 'Tin cho A'`);

  // User B claims
  const sessionB = '55555555-5555-5555-5555-555555555552';
  const claimB = await asUser(userB, 'authenticated', async () => {
    return (await one(`select public.claim_my_notification_popup('${sessionB}') as r`)).r;
  });
  assert.equal(claimB.status, 'none', 'User B must not receive User A notification');

  // User B attempts to acknowledge User A's notification
  const ackB = await asUser(userB, 'authenticated', async () => {
    return (await one(`select public.acknowledge_my_notification('${notifA.id}') as r`)).r;
  });
  assert.equal(ackB.status, 'deleted', 'User B acknowledging A notification must return deleted/not found');

  const checkA = await one(`select is_read from public.notifications where id = '${notifA.id}'`);
  assert.equal(checkA.is_read, false, 'User A notification must remain unread');
});

// 7. Claim quotas 1 to 5, retry idempotency, and 6th session blocked
await test('Claim quotas 1-5, retry idempotency, and session 6 blocked', async () => {
  await asUser(adminId, 'authenticated', async () => {
    await db.query(`select public.admin_send_notification('${userA}', 'Thông báo 5 lượt', 'Nội dung', 'admin')`);
  });
  const notif = await one(`select id from public.notifications where user_id = '${userA}' and title = 'Thông báo 5 lượt'`);

  const s1 = '60000000-0000-0000-0000-000000000001';
  const s2 = '60000000-0000-0000-0000-000000000002';
  const s3 = '60000000-0000-0000-0000-000000000003';
  const s4 = '60000000-0000-0000-0000-000000000004';
  const s5 = '60000000-0000-0000-0000-000000000005';
  const s6 = '60000000-0000-0000-0000-000000000006';

  // Session 1: Claim 1
  const c1 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r);
  assert.equal(c1.status, 'claimed');
  assert.equal(c1.display_count, 1);
  assert.equal(c1.notification.id, notif.id);

  // Retry Session 1: Idempotent replay, does not increment
  const c1Retry = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r);
  assert.equal(c1Retry.status, 'claimed');
  assert.equal(c1Retry.display_count, 1, 'Retry must not increment display count');

  // Session 2: Claim 2
  const c2 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s2}') as r`)).r);
  assert.equal(c2.display_count, 2);

  // Session 3: Claim 3
  const c3 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s3}') as r`)).r);
  assert.equal(c3.display_count, 3);

  // Session 4: Claim 4
  const c4 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s4}') as r`)).r);
  assert.equal(c4.display_count, 4);

  // Session 5: Claim 5 (final allowed count)
  const c5 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s5}') as r`)).r);
  assert.equal(c5.display_count, 5);

  // Retry Session 5: Replays count 5 even though count reached 5
  const c5Retry = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s5}') as r`)).r);
  assert.equal(c5Retry.status, 'claimed');
  assert.equal(c5Retry.display_count, 5);

  // Session 6: Quota exceeded -> status: none
  const c6 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s6}') as r`)).r);
  assert.equal(c6.status, 'none', '6th session must not be granted a popup');

  // Verify DB state
  const state = await one(`select display_count from public.notification_popup_states where notification_id = '${notif.id}'`);
  assert.equal(state.display_count, 5);
  const stillUnread = await one(`select is_read from public.notifications where id = '${notif.id}'`);
  assert.equal(stillUnread.is_read, false, 'Running out of display quota must not auto mark as read');
});

// 8. Acknowledge stops popup and session replay returns stopped: read
await test('Acknowledge stops popup and subsequent claim/session retry returns stopped', async () => {
  await asUser(adminId, 'authenticated', async () => {
    await db.query(`select public.admin_send_notification('${userA}', 'Tin xác nhận', 'Nội dung', 'admin')`);
  });
  const notif = await one(`select id from public.notifications where user_id = '${userA}' and title = 'Tin xác nhận'`);

  const s1 = '70000000-0000-0000-0000-000000000001';
  const c1 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r);
  assert.equal(c1.status, 'claimed');

  // User A acknowledges
  const ack = await asUser(userA, 'authenticated', async () => (await one(`select public.acknowledge_my_notification('${notif.id}') as r`)).r);
  assert.equal(ack.success, true);
  assert.equal(ack.status, 'read');

  // Idempotent ack
  const ack2 = await asUser(userA, 'authenticated', async () => (await one(`select public.acknowledge_my_notification('${notif.id}') as r`)).r);
  assert.equal(ack2.success, true);
  assert.equal(ack2.status, 'already_read');

  // Same session s1 retry after read -> stopped: read
  const c1AfterRead = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r);
  assert.equal(c1AfterRead.status, 'stopped');
  assert.equal(c1AfterRead.reason, 'read');

  // New session s2 -> status: none
  const s2 = '70000000-0000-0000-0000-000000000002';
  const c2 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s2}') as r`)).r);
  assert.equal(c2.status, 'none');
});

// 9. Deleting notification stops popup and retains receipt
await test('Deleting notification sets receipt notification_id null and stops replay without picking another', async () => {
  await asUser(adminId, 'authenticated', async () => {
    await db.query(`select public.admin_send_notification('${userA}', 'Tin 1', 'Nội dung 1', 'admin')`);
    await db.query(`select public.admin_send_notification('${userA}', 'Tin 2', 'Nội dung 2', 'admin')`);
  });
  const notif1 = await one(`select id from public.notifications where user_id = '${userA}' and title = 'Tin 1'`);

  const s1 = '80000000-0000-0000-0000-000000000001';
  const c1 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r);
  assert.equal(c1.notification.id, notif1.id);

  // User A deletes notification 1
  await asUser(userA, 'authenticated', async () => {
    await db.query(`delete from public.notifications where id = '${notif1.id}'`);
  });

  // Re-calling claim with same session s1 must return stopped: deleted and MUST NOT pick Tin 2
  const c1Retry = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r);
  assert.equal(c1Retry.status, 'stopped');
  assert.equal(c1Retry.reason, 'deleted');

  // New session s2 will pick Tin 2
  const s2 = '80000000-0000-0000-0000-000000000002';
  const c2 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s2}') as r`)).r);
  assert.equal(c2.status, 'claimed');
  assert.equal(c2.notification.title, 'Tin 2');
});

// 10. Ordering and search beyond top 50
await test('Finds eligible notification beyond top 50 items and preserves created_at order', async () => {
  // 1. Admin sends an eligible popup notification first (older)
  await asUser(adminId, 'authenticated', async () => {
    await db.query(`select public.admin_send_notification('${userA}', 'Popup Cũ Ngoài Top 50', 'Nội dung', 'admin')`);
  });

  // 2. Insert 55 newer filler notifications for userA so the popup notification is pushed outside the top 50 newest
  await asUser(userA, 'authenticated', async () => {
    for (let i = 1; i <= 55; i++) {
      await db.query(`
        insert into public.notifications(user_id, title, message, type, is_read, created_at)
        values ('${userA}', 'Newer Filler ${i}', 'Msg', 'general', false, clock_timestamp() + interval '${i} milliseconds');
      `);
    }
  });

  // 3. Verify that the standard top 50 query does NOT contain 'Popup Cũ Ngoài Top 50'
  const top50Titles = (await db.query(`
    select title from public.notifications where user_id = '${userA}' order by created_at desc limit 50
  `)).rows.map(r => r.title);
  assert.equal(top50Titles.includes('Popup Cũ Ngoài Top 50'), false, 'Older popup notification must NOT be in the top 50 newest query');

  // 4. Claim must still successfully find and claim the oldest eligible popup notification
  const s1 = '90000000-0000-0000-0000-000000000001';
  const c1 = await asUser(userA, 'authenticated', async () => (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r);
  assert.equal(c1.status, 'claimed');
  assert.equal(c1.notification.title, 'Popup Cũ Ngoài Top 50', 'Must find and pick the oldest eligible notification even outside top 50');
});

// 11. Locked account blocked
await test('Locked account cannot claim popup', async () => {
  await asUser(adminId, 'authenticated', async () => {
    await db.query(`select public.admin_send_notification('${lockedUser}', 'Tin Cho Khóa', 'Nội dung', 'admin')`);
  });

  const s1 = 'a0000000-0000-0000-0000-000000000001';
  const claim = await asUser(lockedUser, 'authenticated', async () => {
    return (await one(`select public.claim_my_notification_popup('${s1}') as r`)).r;
  });
  assert.equal(claim.status, 'blocked');
  assert.equal(claim.reason, 'account_locked');
});

console.log('=== ALL ADMIN DIRECT NOTIFICATION POPUP TESTS PASSED ===');
