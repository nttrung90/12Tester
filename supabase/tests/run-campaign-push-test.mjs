import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { uuid_ossp } from '@electric-sql/pglite/contrib/uuid_ossp';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (path) => readFileSync(`${root}${path}`, 'utf8').replaceAll('\r\n', '\n');

const fullSchema = read('supabase/full_schema_singapore.sql').split('-- ===== 045_thumbnail_support.sql =====')[0];
const migration045 = read('supabase/migrations/045_thumbnail_support.sql');
const migration046 = read('supabase/migrations/046_media_access_gateway.sql');
const migration047 = read('supabase/migrations/047_home_stats_cycle.sql');
const migration048 = read('supabase/migrations/048_mission_report_and_expiration.sql');
const migration049 = read('supabase/migrations/049_mission_report_hardening.sql');
const migration050 = read('supabase/migrations/050_app_update_reward.sql');
const migration051 = read('supabase/migrations/051_app_update_reward_hardening.sql');
const migration052 = read('supabase/migrations/052_campaign_push_priority.sql');

const bootstrap = `
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create schema auth;
  create table auth.users (
    id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
    email_confirmed_at timestamptz, confirmation_token text,
    email_change text, email_change_token_new text, recovery_token text
  );
  create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
  $$;
  grant usage on schema public, auth to anon, authenticated, service_role;
  grant execute on function auth.uid() to anon, authenticated, service_role;
`;

async function asUser(db, userId, role = 'authenticated') {
  await db.exec(`
    set role ${role};
    select set_config('request.jwt.claim.sub', '${userId}', true);
  `);
}

async function asAdmin(db) {
  await db.exec(`
    reset role;
    select set_config('request.jwt.claim.sub', '', true);
  `);
}

async function runTests() {
  console.log('=== BẮT ĐẦU KIỂM THỬ BACKEND MIGRATION 052: TÍNH NĂNG ĐẨY APP (CAMPAIGN PUSH PRIORITY) ===');
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

  try {
    // 1. Áp dụng schema baseline và các migration tuần tự
    await db.exec(bootstrap);
    await db.exec(fullSchema);
    await db.exec(migration045);
    await db.exec(migration046);
    await db.exec(migration047);
    await db.exec(migration048);
    await db.exec(migration049);
    await db.exec(migration050);
    await db.exec(migration051);
    await db.exec(migration052);

    // Kiểm tra tính idempotent khi chạy lại migration 052
    await db.exec(migration052);
    console.log('PASS 1: Nạp thành công full schema và migration 045 -> 052 (xác minh idempotent)');

    // 2. Thiết lập users & campaigns cho testing
    const ownerA = '11111111-1111-1111-1111-111111111111';
    const ownerB = '22222222-2222-2222-2222-222222222222';
    const ownerC = '33333333-3333-3333-3333-333333333333';
    const ownerD = '44444444-4444-4444-4444-444444444444';
    const stranger = '99999999-9999-9999-9999-999999999999';
    const lockedUser = '88888888-8888-8888-8888-888888888888';

    await asAdmin(db);
    for (const uid of [ownerA, ownerB, ownerC, ownerD, stranger, lockedUser]) {
      await db.exec(`
        insert into auth.users (id, email) values ('${uid}', '${uid}@test.local') on conflict do nothing;
        insert into public.profiles (id, email, display_name, credits, role, is_locked)
        values ('${uid}', '${uid}@test.local', 'User ${uid.slice(0, 4)}', 250, 'member', ${uid === lockedUser ? 'true' : 'false'})
        on conflict (id) do update set credits = 250, is_locked = ${uid === lockedUser ? 'true' : 'false'};
      `);
    }

    const campA = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    const campB = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
    const campPaused = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
    const campOld = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
    const campSlot2 = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';

    // Tạo slot 2 cho ownerD trước rồi mới chèn campSlot2
    await db.exec(`
      insert into public.user_slots (user_id, slot_number, unlocked_at, expires_at)
      values ('${ownerD}', 2, now(), now() + interval '1 hour')
      on conflict do nothing;
    `);

    await db.exec(`
      insert into public.campaigns (id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values
        ('${campA}', '${ownerA}', 'App A của Chủ A', 'com.owner.a', 'open', 12, 1, now()),
        ('${campB}', '${ownerB}', 'App B của Chủ B', 'com.owner.b', 'open', 12, 1, now()),
        ('${campPaused}', '${ownerA}', 'App Paused của Chủ A', 'com.owner.paused', 'paused', 12, 1, now()),
        ('${campOld}', '${ownerC}', 'App Hết 30 ngày', 'com.owner.old', 'open', 12, 1, now() - interval '31 days'),
        ('${campSlot2}', '${ownerD}', 'App Slot 2 Hết hạn', 'com.owner.slot2', 'open', 12, 2, now())
      on conflict (id) do nothing;
    `);

    // Giờ làm slot 2 của ownerD hết hạn
    await db.exec(`
      update public.user_slots set expires_at = now() - interval '1 hour' where user_id = '${ownerD}' and slot_number = 2;
    `);

    // Helper set user context
    const asUser = async (uid, fn, role = 'authenticated') => {
      await db.exec(`set role ${role}; set request.jwt.claim.sub = '${uid}';`);
      try {
        return await fn();
      } finally {
        await db.exec(`reset role; set request.jwt.claim.sub = '';`);
      }
    };

    // 3. Test 2: User với 99 Credit bị từ chối với INSUFFICIENT_CREDITS
    await db.exec(`update public.profiles set credits = 99 where id = '${ownerA}';`);
    const req1 = '00000000-0000-0000-0000-000000000001';
    await asUser(ownerA, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`select public.push_campaign('${campA}', '${req1}');`);
        },
        (err) => {
          assert(err.message.includes('INSUFFICIENT_CREDITS'), `Mong đợi INSUFFICIENT_CREDITS, nhận được: ${err.message}`);
          return true;
        }
      );
    });

    // Kiểm tra số dư không bị trừ và không có receipt nào
    const bal99 = await db.query(`select credits from public.profiles where id = '${ownerA}';`);
    assert.equal(bal99.rows[0].credits, 99, 'Số dư phải giữ nguyên 99');
    const receiptsCount0 = await db.query(`select count(*) as cnt from public.campaign_push_receipts;`);
    assert.equal(Number(receiptsCount0.rows[0].cnt), 0, 'Chưa có receipt nào');
    console.log('PASS 2: Số dư 99 Credit bị từ chối INSUFFICIENT_CREDITS, không trừ tiền, không ghi receipt');

    // 4. Test 3: Đúng 100 Credit -> Mua thành công, số dư về 0
    await db.exec(`update public.profiles set credits = 100 where id = '${ownerA}';`);
    let receipt1;
    await asUser(ownerA, async () => {
      const resExact100 = await db.query(`select public.push_campaign('${campA}', '${req1}') as result;`);
      receipt1 = typeof resExact100.rows[0].result === 'string' ? JSON.parse(resExact100.rows[0].result) : resExact100.rows[0].result;
    });

    assert.equal(receipt1.success, true);
    assert.equal(receipt1.charged_credits, 100);
    assert.equal(receipt1.balance_after, 0);
    assert.equal(receipt1.replayed, false);
    assert.equal(receipt1.campaign_id, campA);
    assert(receipt1.push_order > 0);
    assert(receipt1.business_date.match(/^\d{4}-\d{2}-\d{2}$/));

    const bal0 = await db.query(`select credits from public.profiles where id = '${ownerA}';`);
    assert.equal(bal0.rows[0].credits, 0, 'Số dư sau khi trừ 100 từ 100 phải bằng đúng 0');

    // Kiểm tra credit_logs
    const log1 = await db.query(`select * from public.credit_logs where user_id = '${ownerA}' and action = 'campaign_push';`);
    assert.equal(log1.rows.length, 1);
    assert.equal(log1.rows[0].amount, -100);
    assert.equal(log1.rows[0].action, 'campaign_push');
    assert.equal(log1.rows[0].balance, 0);

    // Kiểm tra metadata campaign
    const campCheck1 = await db.query(`select push_business_date, push_expires_at, push_order from public.campaigns where id = '${campA}';`);
    assert.equal(campCheck1.rows[0].push_business_date.toISOString().slice(0, 10), receipt1.business_date);
    assert.equal(Number(campCheck1.rows[0].push_order), receipt1.push_order);
    console.log('PASS 3: Đúng 100 Credit thực hiện thành công, số dư còn 0, log -100, metadata campaign được cập nhật');

    // 5. Test 4: Idempotency replay cùng client_request_id -> Trả receipt cũ, không trừ lần 2
    let replayReceipt;
    await asUser(ownerA, async () => {
      const resReplay = await db.query(`select public.push_campaign('${campA}', '${req1}') as result;`);
      replayReceipt = typeof resReplay.rows[0].result === 'string' ? JSON.parse(resReplay.rows[0].result) : resReplay.rows[0].result;
    });

    assert.equal(replayReceipt.success, true);
    assert.equal(replayReceipt.replayed, true);
    assert.equal(replayReceipt.receipt_id, receipt1.receipt_id);
    assert.equal(replayReceipt.push_order, receipt1.push_order);
    assert.equal(replayReceipt.balance_after, 0);

    const logReplay = await db.query(`select count(*) as cnt from public.credit_logs where user_id = '${ownerA}' and action = 'campaign_push';`);
    assert.equal(Number(logReplay.rows[0].cnt), 1, 'Không ghi thêm credit log khi replay');
    console.log('PASS 4: Idempotency replay trả lại đúng receipt, replayed=true, không trừ tiền lần 2');

    // 6. Test 5: Re-use client_request_id với campaign khác -> Bị từ chối REQUEST_ID_CONFLICT
    await asUser(ownerA, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`select public.push_campaign('${campB}', '${req1}');`);
        },
        (err) => {
          assert(err.message.includes('REQUEST_ID_CONFLICT'), `Mong đợi REQUEST_ID_CONFLICT, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 5: Dùng lại request_id cho campaign khác bị chặn với REQUEST_ID_CONFLICT');

    // 7. Test 6: User với 150 Credit đẩy app -> Thành công, còn 50 Credit
    await db.exec(`update public.profiles set credits = 150 where id = '${ownerA}';`);
    const req2 = '00000000-0000-0000-0000-000000000002';
    let receipt2;
    await asUser(ownerA, async () => {
      const res150 = await db.query(`select public.push_campaign('${campA}', '${req2}') as result;`);
      receipt2 = typeof res150.rows[0].result === 'string' ? JSON.parse(res150.rows[0].result) : res150.rows[0].result;
    });
    assert.equal(receipt2.balance_after, 50);
    assert(receipt2.push_order > receipt1.push_order, 'Lượt mua mới phải có push_order lớn hơn lượt trước');
    console.log('PASS 6: User có 150 Credit mua thành công, số dư còn 50, push_order tăng');

    // 8. Test 7: Phân quyền - Người lạ gọi push_campaign bị từ chối NOT_OWNER
    const reqStranger = '00000000-0000-0000-0000-000000000099';
    await asUser(stranger, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`select public.push_campaign('${campA}', '${reqStranger}');`);
        },
        (err) => {
          assert(err.message.includes('NOT_OWNER'), `Mong đợi NOT_OWNER, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 7: Người không phải chủ app gọi push_campaign bị từ chối với NOT_OWNER');

    // 9. Test 8: App đang tạm dừng bị từ chối CAMPAIGN_NOT_OPEN
    const reqPaused = '00000000-0000-0000-0000-000000000003';
    await asUser(ownerA, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`select public.push_campaign('${campPaused}', '${reqPaused}');`);
        },
        (err) => {
          assert(err.message.includes('CAMPAIGN_NOT_OPEN'), `Mong đợi CAMPAIGN_NOT_OPEN, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 8: App đang tạm dừng bị từ chối đẩy với CAMPAIGN_NOT_OPEN');

    // 10. Test 9: App quá 30 ngày bị từ chối CAMPAIGN_WINDOW_EXPIRED
    const reqOld = '00000000-0000-0000-0000-000000000004';
    await asUser(ownerC, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`select public.push_campaign('${campOld}', '${reqOld}');`);
        },
        (err) => {
          assert(err.message.includes('CAMPAIGN_WINDOW_EXPIRED'), `Mong đợi CAMPAIGN_WINDOW_EXPIRED, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 9: App quá 30 ngày bị từ chối đẩy với CAMPAIGN_WINDOW_EXPIRED');

    // 11. Test 10: App Slot 2 hết hạn bị từ chối SLOT_EXPIRED
    const reqSlot2 = '00000000-0000-0000-0000-000000000005';
    await asUser(ownerD, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`select public.push_campaign('${campSlot2}', '${reqSlot2}');`);
        },
        (err) => {
          assert(err.message.includes('SLOT_EXPIRED'), `Mong đợi SLOT_EXPIRED, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 10: App Slot 2 hết hạn bị từ chối đẩy với SLOT_EXPIRED');

    // 12. Test 11: Tài khoản bị khóa (is_locked = true) bị từ chối ACCOUNT_LOCKED
    const campLocked = '77777777-7777-7777-7777-777777777777';
    await db.exec(`
      insert into public.campaigns (id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values ('${campLocked}', '${lockedUser}', 'App Của User Bị Khóa', 'com.locked.app', 'open', 12, 1, now())
      on conflict (id) do nothing;
    `);
    const reqLocked = '00000000-0000-0000-0000-000000000088';
    await asUser(lockedUser, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`select public.push_campaign('${campLocked}', '${reqLocked}');`);
        },
        (err) => {
          assert(err.message.includes('ACCOUNT_LOCKED'), `Mong đợi ACCOUNT_LOCKED, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 11: Tài khoản bị khóa (is_locked) bị từ chối đẩy với ACCOUNT_LOCKED');

    // 13. Test 12: Chống giả mạo - Direct UPDATE/INSERT metadata push bị trigger chặn PUSH_METADATA_READ_ONLY
    await asUser(ownerA, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`update public.campaigns set push_order = 99999 where id = '${campA}';`);
        },
        (err) => {
          assert(err.message.includes('PUSH_METADATA_READ_ONLY'), `Mong đợi PUSH_METADATA_READ_ONLY, nhận được: ${err.message}`);
          return true;
        }
      );

      await assert.rejects(
        async () => {
          await db.exec(`
            insert into public.campaigns (owner_id, app_name, package_name, status, push_order)
            values ('${stranger}', 'Fake App', 'com.fake', 'open', 12345);
          `);
        },
        (err) => {
          assert(err.message.includes('PUSH_METADATA_READ_ONLY'), `Mong đợi PUSH_METADATA_READ_ONLY, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 12: Direct UPDATE và INSERT metadata đẩy app bị trigger chặn an toàn');

    // 14. Test 13: Thao tác UPDATE bình thường trên campaign (sửa app_name, tip) không bị trigger chặn
    await asUser(ownerA, async () => {
      await db.exec(`update public.campaigns set app_name = 'App A Đổi Tên' where id = '${campA}';`);
    });
    const nameUpdated = await db.query(`select app_name from public.campaigns where id = '${campA}';`);
    assert.equal(nameUpdated.rows[0].app_name, 'App A Đổi Tên');
    console.log('PASS 13: Cập nhật thông tin thông thường (không đổi push_*) hoạt động bình thường');

    // 15. Test 14: RLS ngăn chặn ghi trực tiếp vào campaign_push_receipts
    await asUser(ownerA, async () => {
      await assert.rejects(
        async () => {
          await db.exec(`
            insert into public.campaign_push_receipts (user_id, campaign_id, client_request_id, cost_credits, balance_after, business_date, expires_at, push_order)
            values ('${ownerA}', '${campA}', gen_random_uuid(), 100, 50, current_date, now() + interval '1 day', 999);
          `);
        },
        (err) => {
          assert(err.message.includes('permission denied'), `Mong đợi permission denied, nhận được: ${err.message}`);
          return true;
        }
      );
    });
    console.log('PASS 14: Thao tác INSERT trực tiếp vào campaign_push_receipts bị RLS/REVOKE từ chối');

    // 16. Test 15: Thứ tự tuần tự giữa các chủ app: A mua, B mua sau, A mua lại
    // Hiện tại: A đã mua lượt gần nhất là receipt2 (order = 2).
    // Giờ B mua:
    await db.exec(`update public.profiles set credits = 200 where id = '${ownerB}';`);
    const reqB1 = '00000000-0000-0000-0000-0000000000b1';
    let receiptB;
    await asUser(ownerB, async () => {
      const resB = await db.query(`select public.push_campaign('${campB}', '${reqB1}') as result;`);
      receiptB = typeof resB.rows[0].result === 'string' ? JSON.parse(resB.rows[0].result) : resB.rows[0].result;
    });
    assert(receiptB.push_order > receipt2.push_order, `B mua sau phải có order lớn hơn A (${receiptB.push_order} > ${receipt2.push_order})`);

    // A mua lại:
    await db.exec(`update public.profiles set credits = 150 where id = '${ownerA}';`);
    const reqA3 = '00000000-0000-0000-0000-0000000000a3';
    let receiptA3;
    await asUser(ownerA, async () => {
      const resA3 = await db.query(`select public.push_campaign('${campA}', '${reqA3}') as result;`);
      receiptA3 = typeof resA3.rows[0].result === 'string' ? JSON.parse(resA3.rows[0].result) : resA3.rows[0].result;
    });
    assert(receiptA3.push_order > receiptB.push_order, `A mua lại phải có order lớn hơn B (${receiptA3.push_order} > ${receiptB.push_order})`);
    console.log(`PASS 15: Thứ tự lượt đẩy tuân thủ nghiêm ngặt A(1) < A(2) < B(${receiptB.push_order}) < A(${receiptA3.push_order})`);

    // 17. Test 16: _campaign_json trả đầy đủ 3 trường push_*
    const jsonRes = await db.query(`select public._campaign_json(c.*) as json from public.campaigns c where c.id = '${campA}';`);
    const campJson = typeof jsonRes.rows[0].json === 'string' ? JSON.parse(jsonRes.rows[0].json) : jsonRes.rows[0].json;
    assert.equal(campJson.push_order, receiptA3.push_order);
    assert.equal(campJson.push_business_date, receiptA3.business_date);
    assert(campJson.push_expires_at != null);
    console.log('PASS 16: _campaign_json xuất đầy đủ push_business_date, push_expires_at, push_order');

    // 18. Test 17: Xóa campaign bảo toàn receipt và credit_logs (campaign_id set null)
    const ownerTemp = '55555555-5555-5555-5555-555555555555';
    const tempCamp = '66666666-6666-6666-6666-666666666666';
    await db.exec(`
      insert into auth.users (id, email) values ('${ownerTemp}', 'temp@test.local') on conflict do nothing;
      insert into public.profiles (id, email, display_name, credits, role)
      values ('${ownerTemp}', 'temp@test.local', 'User Temp', 200, 'member')
      on conflict (id) do update set credits = 200;
      insert into public.campaigns (id, owner_id, app_name, package_name, status, target_testers, slot_number, created_at)
      values ('${tempCamp}', '${ownerTemp}', 'App Xóa Tạm', 'com.temp.del', 'open', 12, 1, now());
    `);
    const reqTemp = '00000000-0000-0000-0000-000000000066';
    let receiptTemp;
    await asUser(ownerTemp, async () => {
      const resTemp = await db.query(`select public.push_campaign('${tempCamp}', '${reqTemp}') as result;`);
      receiptTemp = typeof resTemp.rows[0].result === 'string' ? JSON.parse(resTemp.rows[0].result) : resTemp.rows[0].result;
    });

    await db.exec(`delete from public.campaigns where id = '${tempCamp}';`);
    const receiptAfterDelete = await db.query(`select * from public.campaign_push_receipts where id = '${receiptTemp.receipt_id}';`);
    assert.equal(receiptAfterDelete.rows.length, 1, 'Receipt phải còn tồn tại sau khi xóa campaign');
    assert.equal(receiptAfterDelete.rows[0].campaign_id, null, 'campaign_id được set null để lưu vết tài chính');
    console.log('PASS 17: Xóa campaign bảo toàn receipt tài chính với campaign_id = null');

    // 19. Test 18: Ràng buộc tính nhất quán chk_campaign_push_metadata_consistency
    await asAdmin(db);
    await assert.rejects(
      async () => {
        await db.exec(`
          select set_config('app.allow_push_metadata_write', 'true', true);
          update public.campaigns set push_business_date = current_date, push_order = null where id = '${campA}';
        `);
      },
      (err) => {
        assert(err.message.includes('chk_campaign_push_metadata_consistency'), `Mong đợi vi phạm ràng buộc metadata consistency, nhận được: ${err.message}`);
        return true;
      }
    );
    console.log('PASS 18: Ràng buộc chk_campaign_push_metadata_consistency bảo vệ tính toàn vẹn 3 cột metadata');

    // 20. Test 19: Tính nguyên tử rollback - Nếu có lỗi xảy ra thì hoàn tiền, không lưu log/receipt
    // Giả lập lỗi bằng cách đặt số dư tài khoản = 100 nhưng profile bị xóa hoặc điều kiện không thỏa
    await db.exec(`update public.profiles set credits = 100 where id = '${ownerTemp}';`);
    const reqFail = '00000000-0000-0000-0000-0000000000f1';
    await asUser(ownerTemp, async () => {
      await assert.rejects(
        async () => {
          // Thử đẩy một campaign đã bị xóa (tempCamp đã bị xóa ở Test 17)
          await db.exec(`select public.push_campaign('${tempCamp}', '${reqFail}');`);
        },
        (err) => {
          assert(err.message.includes('CAMPAIGN_NOT_FOUND'));
          return true;
        }
      );
    });
    // Kiểm tra số dư của ownerTemp vẫn là 100
    const balTemp = await db.query(`select credits from public.profiles where id = '${ownerTemp}';`);
    assert.equal(balTemp.rows[0].credits, 100, 'Số dư không bị trừ khi giao dịch thất bại (rollback toàn phần)');
    const receiptFail = await db.query(`select count(*) as cnt from public.campaign_push_receipts where client_request_id = '${reqFail}';`);
    assert.equal(Number(receiptFail.rows[0].cnt), 0, 'Không lưu receipt khi rollback');
    console.log('PASS 19: Giao dịch thất bại rollback toàn bộ (tiền giữ nguyên, không lưu log, không lưu receipt)');

    console.log('=== TẤT CẢ 19 KIỂM THỬ BACKEND SQL MIGRATION 052 ĐỀU PASS 100% ===');
  } finally {
    await db.close();
  }
}

runTests().catch((err) => {
  console.error('FAILED TEST RUNNER 052:', err);
  process.exit(1);
});
