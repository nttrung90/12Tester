import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

const db = await createDb();

// Seeded test IDs
const adminId = '11111111-1111-1111-1111-111111111111';
const modId = '22222222-2222-2222-2222-222222222222';
const memberAId = '33333333-3333-3333-3333-333333333333';
const memberBId = '44444444-4444-4444-4444-444444444444';
const lockedAdminId = '55555555-5555-5555-5555-555555555555';

const one = async sql => (await db.query(sql)).rows[0];

async function asUser(uid, role = 'authenticated', fn) {
  if (uid === null) {
    await db.exec(`savepoint sp_user; select set_config('request.jwt.claim.sub', '', true); set local role anon;`);
  } else {
    await db.exec(`savepoint sp_user; select set_config('request.jwt.claim.sub', '${uid}', true); set local role ${role};`);
  }
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
      // ignore
    }
  }
}

async function asServiceRole(fn) {
  await db.exec(`savepoint sp_service; set local role service_role;`);
  try {
    const result = await fn();
    await db.exec(`release savepoint sp_service;`);
    return result;
  } catch (err) {
    await db.exec(`rollback to savepoint sp_service;`);
    throw err;
  } finally {
    try {
      await db.exec(`reset role;`);
    } catch {
      // ignore
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
    try {
      await db.exec('rollback');
    } catch {
      // ignore
    }
  }
}

console.log('=== LOADING ACTIVE MIGRATIONS (062 THROUGH 069) EXCLUDING 070/071 ===');
const migrationDirUrl = new URL('../../supabase/migrations/', import.meta.url);
const migrationFiles = readdirSync(migrationDirUrl)
  .filter(n => {
    const match = n.match(/^(\d+).*\.sql$/);
    if (!match) return false;
    const num = Number(match[1]);
    return num >= 62 && num <= 69;
  })
  .sort();

for (const name of migrationFiles) {
  console.log(`Applying migration ${name}...`);
  await db.exec(read('supabase/migrations/' + name));
}

console.log('=== APPLYING STAGED 072 NOTIFICATION CAMPAIGNS MIGRATION ===');
const staged072Sql = read('supabase/migrations/072_admin_notification_campaigns.sql');
await db.exec(staged072Sql);

console.log('=== SEEDING BASE TEST PROFILES ===');
await db.exec(`
  insert into auth.users (id, email) values
    ('${adminId}', 'admin@test.local'),
    ('${modId}', 'mod@test.local'),
    ('${memberAId}', 'memberA@test.local'),
    ('${memberBId}', 'memberB@test.local'),
    ('${lockedAdminId}', 'locked_admin@test.local');

  insert into public.profiles (id, email, display_name, role, is_locked) values
    ('${adminId}', 'admin@test.local', 'Admin User', 'admin', false),
    ('${modId}', 'mod@test.local', 'Moderator User', 'moderator', false),
    ('${memberAId}', 'memberA@test.local', 'Member A', 'member', false),
    ('${memberBId}', 'memberB@test.local', 'Member B', 'member', false),
    ('${lockedAdminId}', 'locked_admin@test.local', 'Locked Admin', 'admin', true)
  on conflict (id) do update set
    role = excluded.role,
    is_locked = excluded.is_locked;
`);

console.log('=== RUNNING NOTIFICATION CAMPAIGNS BEHAVIORAL TESTS ===');

// 1. Verification of schema and tables
await test('Core tables and indexes created successfully', async () => {
  const tables = ['notification_campaigns', 'notification_campaign_recipients', 'notification_campaign_requests', 'notification_campaign_events', 'notification_worker_heartbeats'];
  for (const t of tables) {
    const reg = await one(`select to_regclass('public.${t}') as tbl`);
    assert.ok(reg.tbl, `Table public.${t} must exist`);
  }
});

// 2. Auth checks: Every admin RPC denies member, moderator, locked admin, anon
await test('All admin RPCs enforce unlocked admin and deny member/moderator/locked/anon', async () => {
  const unauthorizedUsers = [
    { uid: memberAId, role: 'authenticated', label: 'member' },
    { uid: modId, role: 'authenticated', label: 'moderator' },
    { uid: lockedAdminId, role: 'authenticated', label: 'locked_admin' },
    { uid: null, role: 'anon', label: 'anon' }
  ];

  const dummyReq = 'a0000000-0000-0000-0000-000000000001';
  const dummyCamp = 'c0000000-0000-0000-0000-000000000001';

  for (const u of unauthorizedUsers) {
    // admin_create_notification_campaign
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_create_notification_campaign('${dummyReq}', 'T', 'T', 'B', 'B');`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_create should deny ${u.label}`);

    // admin_update_notification_campaign
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_update_notification_campaign('${dummyReq}', '${dummyCamp}', 1, 'T', 'T', 'B', 'B');`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_update should deny ${u.label}`);

    // admin_preview_notification_campaign
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_preview_notification_campaign();`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_preview should deny ${u.label}`);

    // admin_schedule_notification_campaign
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_schedule_notification_campaign('${dummyReq}', '${dummyCamp}', 1, now() + interval '1 hour');`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_schedule should deny ${u.label}`);

    // admin_send_now_notification_campaign
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_send_now_notification_campaign('${dummyReq}', '${dummyCamp}', 1);`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_send_now should deny ${u.label}`);

    // admin_cancel_notification_campaign
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_cancel_notification_campaign('${dummyReq}', '${dummyCamp}', 1, 'reason');`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_cancel should deny ${u.label}`);

    // admin_process_due_notification_campaigns
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_process_due_notification_campaigns(10);`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_process_due should deny ${u.label}`);

    // admin_list_notification_campaigns
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_list_notification_campaigns();`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_list should deny ${u.label}`);

    // admin_get_notification_campaign_detail
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_get_notification_campaign_detail('${dummyCamp}');`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_detail should deny ${u.label}`);

    // admin_list_notification_campaign_recipients
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_list_notification_campaign_recipients('${dummyCamp}');`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_recipients should deny ${u.label}`);

    // admin_get_notification_scheduler_health
    await assert.rejects(async () => {
      await asUser(u.uid, u.role, async () => {
        await db.exec(`select public.admin_get_notification_scheduler_health();`);
      });
    }, /FORBIDDEN|UNAUTHORIZED|permission denied/i, `admin_health should deny ${u.label}`);
  }

  // service_process_notification_campaigns must be rejected for authenticated and anon
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.service_process_notification_campaigns();`);
    });
  }, /permission denied/, 'service_process should deny authenticated admin');

  await assert.rejects(async () => {
    await asUser(null, 'anon', async () => {
      await db.exec(`select public.service_process_notification_campaigns();`);
    });
  }, /permission denied/, 'service_process should deny anon');
});

// 3. Content, role, IDs, and boundaries validation
await test('Content boundaries, target_role, and selected_user_ids validation', async () => {
  const req = 'a0000000-0000-0000-0000-000000000002';

  // Empty title_vi
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_create_notification_campaign('${req}', '   ', 'En Title', 'B Vi', 'B En');`);
    });
  }, /INVALID_TITLE_VI/);

  // Title over 200 chars
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_create_notification_campaign('${req}', '${'A'.repeat(201)}', 'En Title', 'B Vi', 'B En');`);
    });
  }, /INVALID_TITLE_VI/);

  // Invalid locale
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_create_notification_campaign('${req}', 'Vi', 'En', 'B Vi', 'B En', 'fr');`);
    });
  }, /INVALID_LOCALE/);

  // Role audience with missing or invalid target_role
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_create_notification_campaign('${req}', 'Vi', 'En', 'B Vi', 'B En', 'vi', 'role', null);`);
    });
  }, /INVALID_TARGET_ROLE/);

  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_create_notification_campaign('${req}', 'Vi', 'En', 'B Vi', 'B En', 'vi', 'role', 'superman');`);
    });
  }, /INVALID_TARGET_ROLE/);

  // Selected users audience with non-existent user ID
  const fakeUserId = '99999999-9999-9999-9999-999999999999';
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_create_notification_campaign('${req}', 'Vi', 'En', 'B Vi', 'B En', 'vi', 'selected_users', null, array['${fakeUserId}'::uuid]);`);
    });
  }, /INVALID_RECIPIENT_USER/);

  // Selected users audience with empty array
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_create_notification_campaign('${req}', 'Vi', 'En', 'B Vi', 'B En', 'vi', 'selected_users', null, '{}'::uuid[]);`);
    });
  }, /EMPTY_SELECTED_USERS/);
});

// 4. Empty audience count preview
await test('Preview handles empty selection and role count accurately', async () => {
  const preview = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_preview_notification_campaign(
        null, 'role', 'admin', null, 'vi', 'Tiêu đề', 'Title', 'Nội dung', 'Body'
      ) as r;
    `)).r;
  });

  assert.equal(preview.success, true);
  // We have 2 admins seeded: adminId and lockedAdminId
  assert.equal(preview.recipient_count, 2);
  assert.equal(preview.preview_vi.title, 'Tiêu đề');
  assert.equal(preview.preview_en.title, 'Title');
  assert.equal(preview.preview_effective.title, 'Tiêu đề');

  // Preview non-existent selection returns 0
  const previewEmpty = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_preview_notification_campaign(
        null, 'selected_users', null, '{}'::uuid[], 'vi', 'T', 'T', 'B', 'B'
      ) as r;
    `)).r;
  });
  assert.equal(previewEmpty.recipient_count, 0);
});

// 5. Draft CAS Conflict check on revision
await test('Draft CAS conflict prevents stale updates', async () => {
  const req1 = 'a0000000-0000-0000-0000-000000000005';
  const req2 = 'a0000000-0000-0000-0000-000000000006';
  const req3 = 'a0000000-0000-0000-0000-000000000007';

  const created = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${req1}', 'Tiêu đề ban đầu', 'Initial Title', 'Nội dung', 'Body'
      ) as r;
    `)).r;
  });

  const campId = created.campaign.id;
  assert.equal(created.campaign.revision, 1);

  // Update with revision 1 succeeds
  const updated = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_update_notification_campaign(
        '${req2}', '${campId}', 1, 'Tiêu đề cập nhật', 'Updated Title', 'Nội dung mới', 'New Body'
      ) as r;
    `)).r;
  });

  assert.equal(updated.campaign.revision, 2);
  assert.equal(updated.campaign.title_vi, 'Tiêu đề cập nhật');

  // Stale update using old revision 1 must fail
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_update_notification_campaign(
          '${req3}', '${campId}', 1, 'Tiêu đề xung đột', 'Conflict Title', 'Nội dung', 'Body'
        );
      `);
    });
  }, /REVISION_CONFLICT/);
});

// 6. Future schedule / edit / cancel, past or in-flight cancel rejected
await test('Future schedule/edit/cancel lifecycle and rejection of past/in-flight cancellation', async () => {
  const reqCreate = 'a0000000-0000-0000-0000-000000000010';
  const reqSchedPast = 'a0000000-0000-0000-0000-000000000011';
  const reqSchedFuture = 'a0000000-0000-0000-0000-000000000012';
  const reqCancelFuture = 'a0000000-0000-0000-0000-000000000013';

  const camp = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqCreate}', 'Chiến dịch lịch', 'Sched Camp', 'Nội dung', 'Body'
      ) as r;
    `)).r;
  })).campaign;

  // Past schedule fails
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_schedule_notification_campaign(
          '${reqSchedPast}', '${camp.id}', ${camp.revision}, now() - interval '10 minutes'
        );
      `);
    });
  }, /SCHEDULE_TIME_MUST_BE_IN_FUTURE/);

  // Future schedule succeeds
  const scheduled = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_schedule_notification_campaign(
        '${reqSchedFuture}', '${camp.id}', ${camp.revision}, now() + interval '2 hours', 'Lên lịch bảo trì'
      ) as r;
    `)).r;
  })).campaign;

  assert.equal(scheduled.status, 'queued');
  assert.equal(scheduled.revision, 2);
  assert.ok(scheduled.total_recipients > 0, 'Recipient snapshot must be frozen');

  // Cancel future scheduled campaign succeeds
  const cancelled = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_cancel_notification_campaign(
        '${reqCancelFuture}', '${camp.id}', ${scheduled.revision}, 'Hoãn bảo trì'
      ) as r;
    `)).r;
  })).campaign;

  assert.equal(cancelled.status, 'cancelled');
  assert.equal(cancelled.cancel_reason, 'Hoãn bảo trì');

  // Trying to cancel again fails
  const reqCancelAgain = 'a0000000-0000-0000-0000-000000000014';
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_cancel_notification_campaign(
          '${reqCancelAgain}', '${camp.id}', ${cancelled.revision}, 'Hủy lần 2'
        );
      `);
    });
  }, /CANNOT_CANCEL_IN_FLIGHT_OR_PAST_CAMPAIGN/);
});

// 7. Send-now scheduling + worker create exactly once and restart
await test('Send-now scheduling and worker exactly-once fanout with idempotent restart', async () => {
  const reqCreate = 'a0000000-0000-0000-0000-000000000020';
  const reqSendNow = 'a0000000-0000-0000-0000-000000000021';

  // Create campaign targeting member role
  const camp = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqCreate}', 'Gửi ngay lập tức', 'Send Now Immediate',
        'Nội dung thông báo gửi ngay', 'Body of immediate notification',
        'vi', 'role', 'member'
      ) as r;
    `)).r;
  })).campaign;

  // Send now
  const sendNowRes = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_send_now_notification_campaign(
        '${reqSendNow}', '${camp.id}', ${camp.revision}, 'Test gửi ngay'
      ) as r;
    `)).r;
  })).campaign;

  assert.equal(sendNowRes.status, 'queued');
  assert.equal(sendNowRes.total_recipients, 2); // memberA, memberB

  // Execute worker (as service role)
  const workerRes1 = await asServiceRole(async () => {
    return (await one(`select public.service_process_notification_campaigns(500, 10, '${camp.id}') as r;`)).r;
  });

  assert.equal(workerRes1.success, true);
  assert.equal(workerRes1.notifications_sent, 2);

  // Verify campaign is now completed
  const campAfter = await one(`select status, sent_count, total_recipients from public.notification_campaigns where id = '${camp.id}'`);
  assert.equal(campAfter.status, 'completed');
  assert.equal(campAfter.sent_count, 2);

  // Verify notifications table has exactly 2 rows for this campaign
  const notifs = (await db.query(`
    select id, user_id, title, message, data
    from public.notifications
    where data->>'campaign_id' = '${camp.id}'
  `)).rows;
  assert.equal(notifs.length, 2);
  assert.equal(notifs[0].data.broadcast, true);
  assert.equal(notifs[0].data.sender, 'admin');

  // Verify restart / second worker run never duplicates notifications
  const workerRes2 = await asServiceRole(async () => {
    return (await one(`select public.service_process_notification_campaigns(500, 10, '${camp.id}') as r;`)).r;
  });
  assert.equal(workerRes2.notifications_sent, 0, 'Second worker iteration must send 0 notifications');

  const notifsAfterRerun = (await db.query(`
    select count(*) as cnt from public.notifications where data->>'campaign_id' = '${camp.id}'
  `)).rows[0].cnt;
  assert.equal(Number(notifsAfterRerun), 2, 'Total notifications must remain exactly 2');
});

// 8. Idempotency: replay identical payload returns previous result without duplicate audit, mismatched payload fails
await test('Mutation request ID replay succeeds idempotently and payload mismatch fails', async () => {
  const reqId = 'a0000000-0000-0000-0000-000000000030';

  const res1 = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqId}', 'Tiêu đề gốc', 'Original Title', 'Nội dung', 'Body'
      ) as r;
    `)).r;
  });

  assert.equal(res1.success, true);
  assert.equal(res1.replayed, false);
  const campId = res1.campaign.id;

  // Replay exact same payload with same request_id
  const res2 = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqId}', 'Tiêu đề gốc', 'Original Title', 'Nội dung', 'Body'
      ) as r;
    `)).r;
  });

  assert.equal(res2.success, true);
  assert.equal(res2.replayed, true);
  assert.equal(res2.campaign.id, campId);

  // Check audit events count: must be exactly 1
  const evCount = (await db.query(`
    select count(*) as cnt from public.notification_campaign_events where campaign_id = '${campId}'
  `)).rows[0].cnt;
  assert.equal(Number(evCount), 1, 'Replayed request must not create duplicate audit events');

  // Replay same request_id with different payload must fail with conflict
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_create_notification_campaign(
          '${reqId}', 'Tiêu đề khác', 'Different Title', 'Nội dung', 'Body'
        );
      `);
    });
  }, /REQUEST_ID_CONFLICT/);

  // Codex Round 1 Defect 1: Delimiter collision ('A|B', 'C' vs 'A', 'B|C') must NOT collide
  const reqCol = 'a0000000-0000-0000-0000-000000000031';
  await asUser(adminId, 'authenticated', async () => {
    await db.exec(`
      select public.admin_create_notification_campaign(
        '${reqCol}', 'A|B', 'C', 'Nội dung', 'Body'
      );
    `);
  });

  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_create_notification_campaign(
          '${reqCol}', 'A', 'B|C', 'Nội dung', 'Body'
        );
      `);
    });
  }, /REQUEST_ID_CONFLICT/, 'Delimiter permutation must result in different payload hash and reject replay');

  // Cross-admin request ID conflict: Another admin cannot reuse/claim the same request ID
  const secondAdminId = '77777777-7777-7777-7777-777777777777';
  await db.exec(`
    insert into auth.users (id, email) values ('${secondAdminId}', 'admin2@test.local') on conflict (id) do nothing;
    insert into public.profiles (id, email, display_name, role, is_locked)
    values ('${secondAdminId}', 'admin2@test.local', 'Admin 2', 'admin', false)
    on conflict (id) do update set role = 'admin', is_locked = false;
  `);

  await assert.rejects(async () => {
    await asUser(secondAdminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_create_notification_campaign(
          '${reqId}', 'Tiêu đề gốc', 'Original Title', 'Nội dung', 'Body'
        );
      `);
    });
  }, /REQUEST_ID_CONFLICT/, 'Request ID claimed by another admin must be rejected');
});

// 9. 500+ Recipient Pages and Bounded Worker Batches
await test('Fanout worker processes 550+ recipients in batches and cursor paginates cleanly', async () => {
  console.log('Seeding 550 test members for scale batch testing...');
  const totalBulk = 550;
  let authValues = [];
  let profileValues = [];
  for (let i = 1; i <= totalBulk; i++) {
    const hex = i.toString(16).padStart(12, '0');
    const uid = `80000000-0000-0000-0000-${hex}`;
    authValues.push(`('${uid}', 'bulk_${i}@scale.test')`);
    profileValues.push(`('${uid}', 'bulk_${i}@scale.test', 'Bulk ${i}', 'member', false)`);
  }

  // Insert in batches of 200 into auth.users and public.profiles
  for (let i = 0; i < totalBulk; i += 200) {
    const authChunk = authValues.slice(i, i + 200);
    const profChunk = profileValues.slice(i, i + 200);
    await db.exec(`
      insert into auth.users (id, email)
      values ${authChunk.join(', ')}
      on conflict (id) do nothing;

      insert into public.profiles (id, email, display_name, role, is_locked)
      values ${profChunk.join(', ')}
      on conflict (id) do nothing;
    `);
  }

  const reqCreateBulk = 'a0000000-0000-0000-0000-000000000040';
  const reqSendBulk = 'a0000000-0000-0000-0000-000000000041';

  const camp = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqCreateBulk}', 'Thông báo diện rộng 550+', 'Broadcast 550+',
        'Nội dung quy mô lớn', 'Scale body test',
        'bilingual', 'role', 'member'
      ) as r;
    `)).r;
  })).campaign;

  // Send now
  const sendRes = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_send_now_notification_campaign(
        '${reqSendBulk}', '${camp.id}', ${camp.revision}
      ) as r;
    `)).r;
  })).campaign;

  // Total members = 550 bulk + 2 seeded memberA, memberB = 552
  assert.equal(sendRes.total_recipients, 552);

  // Process worker with batch size 200
  // Batch 1: 200
  const b1 = await asServiceRole(async () => {
    return (await one(`select public.service_process_notification_campaigns(200, 10, '${camp.id}') as r;`)).r;
  });
  assert.equal(b1.notifications_sent, 200);
  let status1 = (await one(`select status, sent_count from public.notification_campaigns where id = '${camp.id}'`));
  assert.equal(status1.status, 'sending');
  assert.equal(status1.sent_count, 200);

  // Batch 2: 200
  const b2 = await asServiceRole(async () => {
    return (await one(`select public.service_process_notification_campaigns(200, 10, '${camp.id}') as r;`)).r;
  });
  assert.equal(b2.notifications_sent, 200);
  let status2 = (await one(`select status, sent_count from public.notification_campaigns where id = '${camp.id}'`));
  assert.equal(status2.status, 'sending');
  assert.equal(status2.sent_count, 400);

  // Batch 3: remaining 152
  const b3 = await asServiceRole(async () => {
    return (await one(`select public.service_process_notification_campaigns(200, 10, '${camp.id}') as r;`)).r;
  });
  assert.equal(b3.notifications_sent, 152);
  let status3 = (await one(`select status, sent_count from public.notification_campaigns where id = '${camp.id}'`));
  assert.equal(status3.status, 'completed');
  assert.equal(status3.sent_count, 552);

  // Keyset cursor pagination of recipients list
  let allCollectedRecipients = [];
  let cursor = null;
  let pageCount = 0;

  while (true) {
    pageCount++;
    const page = await asUser(adminId, 'authenticated', async () => {
      return (await one(`
        select public.admin_list_notification_campaign_recipients(
          '${camp.id}', null, 100, ${cursor ? `'${cursor}'` : 'null'}
        ) as r;
      `)).r;
    });

    allCollectedRecipients.push(...page.items);
    if (!page.has_more || !page.next_cursor) {
      break;
    }
    cursor = page.next_cursor;
    assert.ok(pageCount <= 10, 'Should not loop infinitely');
  }

  assert.equal(allCollectedRecipients.length, 552, 'All 552 recipients must be retrieved across cursor pages');
  const uniqueRecipientIds = new Set(allCollectedRecipients.map(r => r.recipient_id));
  assert.equal(uniqueRecipientIds.size, 552, 'Every recipient in paginated result must be distinct');
});

// 10. Read count based on notifications.is_read joined by notification_id, NOT popup ack
await test('Read count joined from notifications.is_read and no popup states registered', async () => {
  const reqCreate = 'a0000000-0000-0000-0000-000000000050';
  const reqSend = 'a0000000-0000-0000-0000-000000000051';

  const camp = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqCreate}', 'Test Đọc', 'Read Test', 'Nội dung', 'Body',
        'vi', 'selected_users', null, array['${memberAId}'::uuid, '${memberBId}'::uuid]
      ) as r;
    `)).r;
  })).campaign;

  await asUser(adminId, 'authenticated', async () => {
    await db.exec(`select public.admin_send_now_notification_campaign('${reqSend}', '${camp.id}', ${camp.revision});`);
  });

  // Run worker
  await asServiceRole(async () => {
    await db.exec(`select public.service_process_notification_campaigns(500, 10, '${camp.id}');`);
  });

  // Confirm NO rows registered in notification_popup_states
  const popupStatesCount = (await db.query(`
    select count(*) as cnt
    from public.notification_popup_states ps
    join public.notifications n on n.id = ps.notification_id
    where n.data->>'campaign_id' = '${camp.id}'
  `)).rows[0].cnt;
  assert.equal(Number(popupStatesCount), 0, 'Campaign broadcast notifications must NOT register popup states');

  // Verify initial read count is 0
  const detail1 = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`select public.admin_get_notification_campaign_detail('${camp.id}') as r;`)).r;
  })).campaign;
  assert.equal(detail1.read_count, 0);

  // User A marks their notification as read
  await db.exec(`
    update public.notifications
    set is_read = true
    where user_id = '${memberAId}' and data->>'campaign_id' = '${camp.id}';
  `);

  // Verify read count is now 1
  const detail2 = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`select public.admin_get_notification_campaign_detail('${camp.id}') as r;`)).r;
  })).campaign;
  assert.equal(detail2.read_count, 1);
});

// 11. Sent counts remain stable even after user deletes notification
await test('Deleted notification rows do not decrease sent_count', async () => {
  const reqCreate = 'a0000000-0000-0000-0000-000000000060';
  const reqSend = 'a0000000-0000-0000-0000-000000000061';

  const camp = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqCreate}', 'Test Xóa', 'Delete Test', 'Nội dung', 'Body',
        'vi', 'selected_users', null, array['${memberAId}'::uuid]
      ) as r;
    `)).r;
  })).campaign;

  await asUser(adminId, 'authenticated', async () => {
    await db.exec(`select public.admin_send_now_notification_campaign('${reqSend}', '${camp.id}', ${camp.revision});`);
  });

  await asServiceRole(async () => {
    await db.exec(`select public.service_process_notification_campaigns(500, 10, '${camp.id}');`);
  });

  const detailBefore = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`select public.admin_get_notification_campaign_detail('${camp.id}') as r;`)).r;
  })).campaign;
  assert.equal(detailBefore.sent_count, 1);

  // Delete notification row from public.notifications
  await db.exec(`
    delete from public.notifications where user_id = '${memberAId}' and data->>'campaign_id' = '${camp.id}';
  `);

  // Verify recipient row has notification_id = null but status remains 'sent'
  const recip = await one(`select status, notification_id from public.notification_campaign_recipients where campaign_id = '${camp.id}' and user_id = '${memberAId}'`);
  assert.equal(recip.status, 'sent');
  assert.equal(recip.notification_id, null);

  // Verify campaign sent_count remains 1
  const detailAfter = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`select public.admin_get_notification_campaign_detail('${camp.id}') as r;`)).r;
  })).campaign;
  assert.equal(detailAfter.sent_count, 1, 'sent_count must remain stable after notification row deleted');
});

// 12. Helper functions and internal tables direct client spoof denied
await test('Internal helpers and direct table modifications are denied to client roles', async () => {
  // Direct insert on notification_campaigns by authenticated client
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        insert into public.notification_campaigns (title_vi, title_en, body_vi, body_en, created_by)
        values ('T', 'T', 'B', 'B', '${adminId}');
      `);
    });
  }, /permission denied/, 'Client cannot directly insert into notification_campaigns table');

  // Direct update on notification_campaign_recipients
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        update public.notification_campaign_recipients set status = 'sent';
      `);
    });
  }, /permission denied/, 'Client cannot directly update notification_campaign_recipients');

  // Helper execution spoof denied
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public._notif_resolve_delivered_content('a', 'b', 'c', 'd', 'vi');`);
    });
  }, /permission denied/, 'Client cannot execute _notif_resolve_delivered_content');

  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public._notif_snapshot_recipients('${adminId}');`);
    });
  }, /permission denied/, 'Client cannot execute _notif_snapshot_recipients');
});

// 13. Compatibility with existing 067/069 direct notifications and broadcast
await test('Compatibility with existing direct notification popup registration and original broadcast', async () => {
  // Direct notification to Member A registers popup state
  const resDirect = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_send_notification(
        '${memberAId}',
        'Tin nhắn trực tiếp',
        'Nội dung trực tiếp',
        'warning'
      ) as r;
    `)).r;
  });

  assert.equal(resDirect.success, true);
  assert.equal(resDirect.sent_count, 1);

  const notifDirect = await one(`select id from public.notifications where user_id = '${memberAId}' and title = 'Tin nhắn trực tiếp'`);
  assert.ok(notifDirect);
  const popupState = await one(`select display_count from public.notification_popup_states where notification_id = '${notifDirect.id}'`);
  assert.ok(popupState, 'Direct notification must still register in notification_popup_states');
  assert.equal(popupState.display_count, 0);

  // Original broadcast without target_user_id
  const resBroadcast = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_send_notification(
        null,
        'Tin phát thanh cũ',
        'Nội dung phát thanh cũ',
        'info'
      ) as r;
    `)).r;
  });

  assert.equal(resBroadcast.success, true);
  assert.equal(resBroadcast.target, 'all');

  const notifBroadcast = await one(`select id from public.notifications where title = 'Tin phát thanh cũ' limit 1`);
  assert.ok(notifBroadcast);
  const popupStateBroadcast = await one(`select display_count from public.notification_popup_states where notification_id = '${notifBroadcast.id}'`);
  assert.equal(popupStateBroadcast, undefined, 'Old broadcast must not register popup state');
});

// 14. Scheduler health RPC verification
await test('Admin scheduler health exposes status and background queue counts', async () => {
  const health = await asUser(adminId, 'authenticated', async () => {
    return (await one(`select public.admin_get_notification_scheduler_health() as r;`)).r;
  });

  assert.equal(typeof health.pg_cron_available, 'boolean');
  assert.equal(typeof health.pg_cron_job_active, 'boolean');
  assert.equal(typeof health.scheduler_ready, 'boolean');
  assert.equal(typeof health.pending_due_campaigns_count, 'number');
  assert.equal(typeof health.in_flight_campaigns_count, 'number');
  assert.ok(['healthy', 'healthy_daemon_active', 'degraded_backlog_accumulating', 'idle', 'scheduler_manual_fallback_required'].includes(health.health_status));
});

// 15. Codex Round 1 Defect 2: Empty scheduled role audience freezes at 0 and does not grow
await test('Empty scheduled role audience freezes at 0 and does not grow when member changes role', async () => {
  const reqDraft = 'b0000000-0000-0000-0000-000000000001';
  const reqSend = 'b0000000-0000-0000-0000-000000000002';

  // Make sure no moderator exists initially
  await db.exec(`update public.profiles set role = 'member' where id = '${modId}';`);

  const draft = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqDraft}', 'Tiêu đề kiểm tra rỗng', 'Empty Test Title',
        'Nội dung', 'Body', 'vi', 'role', 'moderator'
      ) as r;
    `)).r;
  })).campaign;

  // Send now when 0 moderators exist
  const sent = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_send_now_notification_campaign(
        '${reqSend}', '${draft.id}', ${draft.revision}
      ) as r;
    `)).r;
  })).campaign;

  assert.equal(sent.total_recipients, 0);
  assert.equal(sent.recipient_snapshot_taken, true);

  const initialCount = Number((await db.query(`select count(*) as cnt from public.notification_campaign_recipients where campaign_id = '${draft.id}'`)).rows[0].cnt);
  assert.equal(initialCount, 0);

  // Now promote a member to moderator
  await db.exec(`update public.profiles set role = 'moderator' where id = '${memberAId}';`);

  // Run worker
  const workerRes = await asServiceRole(async () => {
    return (await one(`select public.service_process_notification_campaigns(500, 10, '${draft.id}') as r;`)).r;
  });

  assert.equal(workerRes.notifications_sent, 0);

  const afterCount = Number((await db.query(`select count(*) as cnt from public.notification_campaign_recipients where campaign_id = '${draft.id}'`)).rows[0].cnt);
  assert.equal(afterCount, 0, 'Recipient count must remain 0 and not grow after late role promotion');

  const finalCamp = await one(`select status, sent_count from public.notification_campaigns where id = '${draft.id}'`);
  assert.equal(finalCamp.status, 'completed');
  assert.equal(finalCamp.sent_count, 0);
});

// 16. Cursor format and filter whitelist validation
await test('Filter status whitelist and cursor malformed/infinity protection', async () => {
  // Invalid status filter in campaign list
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_list_notification_campaigns('invalid_status');`);
    });
  }, /INVALID_STATUS_FILTER/);

  // Invalid audience filter in campaign list
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_list_notification_campaigns(null, 'invalid_audience');`);
    });
  }, /INVALID_AUDIENCE_FILTER/);

  // Invalid recipient status filter
  const dummyCamp = 'c0000000-0000-0000-0000-000000000001';
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_list_notification_campaign_recipients('${dummyCamp}', 'invalid_status');`);
    });
  }, /INVALID_STATUS_FILTER/);

  // Malformed cursor without pipe
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_list_notification_campaigns(null, null, 20, 'garbagecursor');`);
    });
  }, /INVALID_CURSOR/);

  // Cursor containing infinity or NaN
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_list_notification_campaigns(null, null, 20, 'infinity|${dummyCamp}');`);
    });
  }, /INVALID_CURSOR/);

  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`select public.admin_list_notification_campaign_recipients('${dummyCamp}', null, 20, 'NaN|${dummyCamp}');`);
    });
  }, /INVALID_CURSOR/);
});

// 17. Campaign detail bounded events
await test('Campaign detail bounds events list and supports events_limit', async () => {
  const reqCamp = 'd0000000-0000-0000-0000-000000000001';
  const camp = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqCamp}', 'Chi tiết chiến dịch', 'Detail Campaign',
        'Nội dung', 'Body'
      ) as r;
    `)).r;
  })).campaign;

  // Insert 60 dummy events
  for (let i = 1; i <= 60; i++) {
    await db.exec(`
      insert into public.notification_campaign_events (campaign_id, admin_id, action, reason)
      values ('${camp.id}', '${adminId}', 'test_event_${i}', 'Audit test event');
    `);
  }

  // Fetch with default limit (50)
  const detailDef = await asUser(adminId, 'authenticated', async () => {
    return (await one(`select public.admin_get_notification_campaign_detail('${camp.id}') as r;`)).r;
  });
  assert.equal(detailDef.events.length, 50);

  // Fetch with explicit limit (15)
  const detail15 = await asUser(adminId, 'authenticated', async () => {
    return (await one(`select public.admin_get_notification_campaign_detail('${camp.id}', 15) as r;`)).r;
  });
  assert.equal(detail15.events.length, 15);
});

// 18. Integrated common audit test with 070 installed
await test('Integrated common audit records to admin_action_events exactly once when 070 installed', async () => {
  // Apply 070_admin_management.sql inside this test scope
  console.log('Applying 070_admin_management.sql for integrated common audit verification...');
  await db.exec(read('supabase/migrations/070_admin_management.sql'));
  await db.exec('begin');

  const reqInt1 = 'e0000000-0000-0000-0000-000000000001';
  const reqInt2 = 'e0000000-0000-0000-0000-000000000002';
  const reqInt3 = 'e0000000-0000-0000-0000-000000000003';

  // 1. Create campaign
  const camp = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqInt1}', 'Tích hợp Audit Chung', 'Common Audit Title',
        'Nội dung kiểm tra', 'Integrated audit test body',
        'vi', 'all', null, null, 'Lý do tạo chiến dịch'
      ) as r;
    `)).r;
  })).campaign;

  // Verify created event in admin_action_events
  const createEv = await one(`
    select * from public.admin_action_events
    where action = 'notification_campaign_create' and target_id = '${camp.id}'
  `);
  assert.ok(createEv, 'Common audit event for creation must be recorded');
  assert.equal(createEv.actor_id, adminId);
  assert.equal(createEv.request_id, reqInt1);
  assert.equal(createEv.reason, 'Lý do tạo chiến dịch');

  // Replay create: must NOT duplicate event in admin_action_events
  await asUser(adminId, 'authenticated', async () => {
    await db.exec(`
      select public.admin_create_notification_campaign(
        '${reqInt1}', 'Tích hợp Audit Chung', 'Common Audit Title',
        'Nội dung kiểm tra', 'Integrated audit test body',
        'vi', 'all', null, null, 'Lý do tạo chiến dịch'
      );
    `);
  });

  const createEvCount = Number((await db.query(`
    select count(*) as cnt from public.admin_action_events
    where action = 'notification_campaign_create' and target_id = '${camp.id}'
  `)).rows[0].cnt);
  assert.equal(createEvCount, 1, 'Replayed create request must not create duplicate common audit event');

  // 2. Schedule campaign
  await asUser(adminId, 'authenticated', async () => {
    await db.exec(`
      select public.admin_schedule_notification_campaign(
        '${reqInt2}', '${camp.id}', ${camp.revision}, now() + interval '5 hours', 'Lý do lên lịch'
      );
    `);
  });

  const schedEv = await one(`
    select * from public.admin_action_events
    where action = 'notification_campaign_schedule' and target_id = '${camp.id}'
  `);
  assert.ok(schedEv, 'Common audit event for scheduling must be recorded');
  assert.equal(schedEv.actor_id, adminId);
  assert.equal(schedEv.request_id, reqInt2);

  // 3. Cancel campaign
  await asUser(adminId, 'authenticated', async () => {
    await db.exec(`
      select public.admin_cancel_notification_campaign(
        '${reqInt3}', '${camp.id}', ${camp.revision + 1}, 'Lý do hủy chiến dịch'
      );
    `);
  });

  const cancelEv = await one(`
    select * from public.admin_action_events
    where action = 'notification_campaign_cancel' and target_id = '${camp.id}'
  `);
  assert.ok(cancelEv, 'Common audit event for cancellation must be recorded');
  assert.equal(cancelEv.actor_id, adminId);
  assert.equal(cancelEv.request_id, reqInt3);
  assert.equal(cancelEv.reason, 'Lý do hủy chiến dịch');

  // 4. Trigger process_due wrapper
  await asUser(adminId, 'authenticated', async () => {
    await db.exec(`select public.admin_process_due_notification_campaigns(50, '${camp.id}');`);
  });

  const processEv = await one(`
    select * from public.admin_action_events
    where action = 'notification_campaign_process_due' and target_id = '${camp.id}'
  `);
  assert.ok(processEv, 'Process due manual trigger must record common audit event');
  assert.equal(processEv.actor_id, adminId);

  // 5. Worker events: must NOT have fake actor in admin_action_events
  const workerEvCount = Number((await db.query(`
    select count(*) as cnt from public.admin_action_events
    where action in ('worker_started', 'worker_completed')
  `)).rows[0].cnt);
  assert.equal(workerEvCount, 0, 'Worker background events must not inject fake actor into common audit');
});

// 19. Schedule replay after scheduled time has passed
await test('Committed schedule replays successfully with same UUID after scheduled time passes', async () => {
  const reqDraft = 'f0000000-0000-0000-0000-000000000001';
  const reqSched = 'f0000000-0000-0000-0000-000000000002';

  const draft = (await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_create_notification_campaign(
        '${reqDraft}', 'Thử nghiệm replay lịch', 'Test Sched Replay',
        'Nội dung', 'Body'
      ) as r;
    `)).r;
  })).campaign;

  // Schedule for 1 second in future
  const schedTime = (await db.query("select to_char((clock_timestamp() + interval '1 second') at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as t")).rows[0].t;

  const first = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_schedule_notification_campaign(
        '${reqSched}', '${draft.id}', ${draft.revision}, '${schedTime}'::timestamptz, 'Lý do 1'
      ) as r;
    `)).r;
  });
  assert.equal(first.success, true);
  assert.equal(first.replayed, false);

  // Wait 1.2s so time passes into past
  await new Promise(resolve => setTimeout(resolve, 1200));

  // Replaying identical request must succeed with replayed: true even though time is now in past
  const replay = await asUser(adminId, 'authenticated', async () => {
    return (await one(`
      select public.admin_schedule_notification_campaign(
        '${reqSched}', '${draft.id}', ${draft.revision}, '${schedTime}'::timestamptz, 'Lý do 1'
      ) as r;
    `)).r;
  });
  assert.equal(replay.success, true);
  assert.equal(replay.replayed, true);

  // Same request ID with different payload must conflict even in past
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_schedule_notification_campaign(
          '${reqSched}', '${draft.id}', ${draft.revision}, '${schedTime}'::timestamptz, 'Lý do khác'
        );
      `);
    });
  }, /REQUEST_ID_CONFLICT/);

  // New past schedule with brand new request ID must be rejected
  const reqNewPast = 'f0000000-0000-0000-0000-000000000003';
  await assert.rejects(async () => {
    await asUser(adminId, 'authenticated', async () => {
      await db.exec(`
        select public.admin_schedule_notification_campaign(
          '${reqNewPast}', '${draft.id}', ${first.campaign.revision}, '${schedTime}'::timestamptz, 'New past'
        );
      `);
    });
  }, /SCHEDULE_TIME_MUST_BE_IN_FUTURE/);
});

console.log('=== ALL ADMIN NOTIFICATION CAMPAIGNS TESTS PASSED ===');
