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

async function runTests() {
  console.log('=== BẮT ĐẦU KIỂM THỬ BACKEND MIGRATION 050: THƯỞNG CẬP NHẬT PHIÊN BẢN APP ===');
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

    // Kiểm tra tính idempotent khi chạy lại migration 050 và 051
    await db.exec(migration050);
    await db.exec(migration051);
    console.log('PASS 1: Nạp thành công full schema và migration 045 -> 051 (xác minh idempotent)');

    // 2. Thiết lập users & campaign cho testing
    const ownerId = '11111111-1111-1111-1111-111111111111';
    const strangerId = '99999999-9999-9999-9999-999999999999';
    const campId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    const pkgName = 'com.example.testapp';

    // Tạo 25 testers cho bài test 20 suất
    const testers = [];
    for (let i = 1; i <= 25; i++) {
      const hex = i.toString().padStart(2, '0');
      testers.push(`22222222-2222-2222-2222-2222222222${hex}`);
    }

    let insertUsersSql = `insert into auth.users (id, email) values ('${ownerId}', 'owner@test.com'), ('${strangerId}', 'stranger@test.com')`;
    for (const tId of testers) {
      insertUsersSql += `, ('${tId}', 'tester_${tId.slice(-2)}@test.com')`;
    }
    insertUsersSql += ';';
    await db.exec(insertUsersSql);

    // Cấp credits cho owner: 119 Credit trước để test chặn thiếu tiền
    await db.exec(`update public.profiles set credits = 119 where id = '${ownerId}';`);
    for (const tId of testers) {
      await db.exec(`update public.profiles set credits = 100 where id = '${tId}';`);
    }

    // Tạo campaign cho owner
    await db.exec(`
      insert into public.campaigns (id, owner_id, app_name, package_name, status, target_days)
      values ('${campId}', '${ownerId}', 'My Test App', '${pkgName}', 'open', 14);
    `);

    // Add 22 testers vào campaign_members tại thời điểm này
    for (let i = 0; i < 22; i++) {
      await db.exec(`
        insert into public.campaign_members (campaign_id, user_id, joined_at)
        values ('${campId}', '${testers[i]}', now() - interval '1 hour');
      `);
    }

    // Helper set user context
    const asUser = async (uid, fn) => {
      await db.exec(`set request.jwt.claim.sub = '${uid}';`);
      try {
        return await fn();
      } finally {
        await db.exec(`set request.jwt.claim.sub = '';`);
      }
    };

    // PASS 2: Chủ app có 119 Credit bị chặn công bố với INSUFFICIENT_CREDITS
    await asUser(ownerId, async () => {
      await assert.rejects(async () => {
        await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
          campId, '1.0.0', 10, 'Tính năng mới ban đầu'
        ]);
      }, (err) => {
        assert.match(err.message, /INSUFFICIENT_CREDITS/);
        return true;
      });
    });
    console.log('PASS 2: Chặn công bố khi ví chủ < 120 Credit (INSUFFICIENT_CREDITS)');

    // PASS 3: Không phải chủ thật gọi publish bị từ chối với NOT_ACTUAL_OWNER
    await asUser(strangerId, async () => {
      await assert.rejects(async () => {
        await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
          campId, '1.0.0', 10, 'Hack publish'
        ]);
      }, (err) => {
        assert.match(err.message, /NOT_ACTUAL_OWNER/);
        return true;
      });
    });
    console.log('PASS 3: Chặn non-owner gọi publish_app_update (NOT_ACTUAL_OWNER)');

    // Nạp đủ 120 Credit cho owner
    await db.exec(`update public.profiles set credits = 120 where id = '${ownerId}';`);

    // PASS 4: Công bố phiên bản v1.0.0 (versionCode 10) thành công, giữ 120 Credit
    let pubRes1;
    await asUser(ownerId, async () => {
      const res = await db.query(`select public.publish_app_update($1, $2, $3, $4, $5)`, [
        campId, '1.0.0', 10, 'Bản phát hành đầu tiên: fix lag, dark mode', 'op-release-1'
      ]);
      pubRes1 = res.rows[0].publish_app_update;
    });

    assert.equal(pubRes1.success, true);
    assert.equal(pubRes1.release.version_name, '1.0.0');
    assert.equal(pubRes1.release.target_version_code, 10);
    assert.equal(pubRes1.release.reserved_credits, 120);
    assert.equal(pubRes1.release.remaining_credits, 120);
    assert.equal(pubRes1.release.paid_count, 0);
    assert.equal(pubRes1.owner_credits, 0); // 120 - 120 = 0

    // Kiểm tra log credit của chủ: app_update_reserve -120
    const ownerLogs1 = (await db.query(`select * from public.credit_logs where user_id = $1 order by created_at desc`, [ownerId])).rows;
    assert.equal(ownerLogs1[0].action, 'app_update_reserve');
    assert.equal(ownerLogs1[0].amount, -120);
    assert.equal(ownerLogs1[0].balance, 0);
    console.log('PASS 4: Công bố v1.0.0 thành công, trừ 120 Credit ví chủ, khởi tạo quỹ 120');

    const release1Id = pubRes1.release.id;

    // PASS 5: Idempotency publish với cùng client_operation_id trả lại release cũ, không trừ thêm tiền
    await asUser(ownerId, async () => {
      const replay = await db.query(`select public.publish_app_update($1, $2, $3, $4, $5)`, [
        campId, '1.0.0', 10, 'Bản phát hành đầu tiên: fix lag, dark mode', 'op-release-1'
      ]);
      const repData = replay.rows[0].publish_app_update;
      assert.equal(repData.success, true);
      assert.equal(repData.idempotent_replay, true);
      assert.equal(repData.release.id, release1Id);
    });
    const ownerBalAfterReplay = (await db.query(`select credits from public.profiles where id = $1`, [ownerId])).rows[0].credits;
    assert.equal(ownerBalAfterReplay, 0);
    console.log('PASS 5: Idempotency publish: Replay cùng operation_id không trừ tiền lần hai');

    // PASS 6: Chủ không được công bố lại cùng versionCode hoặc versionCode nhỏ hơn
    await asUser(ownerId, async () => {
      await assert.rejects(async () => {
        await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
          campId, '1.0.1', 10, 'Trùng version code 10'
        ]);
      }, (err) => {
        assert.match(err.message, /VERSION_CODE_NOT_GREATER/);
        return true;
      });
      await assert.rejects(async () => {
        await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
          campId, '0.9.0', 8, 'Version code 8 nhỏ hơn 10'
        ]);
      }, (err) => {
        assert.match(err.message, /VERSION_CODE_NOT_GREATER/);
        return true;
      });
    });
    console.log('PASS 6: Chặn công bố versionCode không lớn hơn watermark (VERSION_CODE_NOT_GREATER)');

    // PASS 7: Chủ app không thể tự nhận thưởng của chính app mình
    await asUser(ownerId, async () => {
      await assert.rejects(async () => {
        await db.query(`select public.claim_app_update_reward($1, $2)`, [release1Id, 10]);
      }, (err) => {
        assert.match(err.message, /OWNER_CANNOT_CLAIM/);
        return true;
      });
    });
    console.log('PASS 7: Chủ app bị chặn nhận thưởng của chính app mình (OWNER_CANNOT_CLAIM)');

    // PASS 8: Tester chưa đạt target_version_code bị từ chối
    await asUser(testers[0], async () => {
      await assert.rejects(async () => {
        await db.query(`select public.claim_app_update_reward($1, $2)`, [release1Id, 9]); // 9 < 10
      }, (err) => {
        assert.match(err.message, /VERSION_NOT_SATISFIED/);
        return true;
      });
    });
    console.log('PASS 8: Tester cài bản cũ (< targetVersionCode) bị từ chối (VERSION_NOT_SATISFIED)');

    // PASS 9: Tester 1 đạt bản mới (versionCode 10) nhận thành công +6 Credit
    let claimRes1;
    await asUser(testers[0], async () => {
      const res = await db.query(`select public.claim_app_update_reward($1, $2)`, [release1Id, 10]);
      claimRes1 = res.rows[0].claim_app_update_reward;
    });
    assert.equal(claimRes1.success, true);
    assert.equal(claimRes1.newly_awarded, true);
    assert.equal(claimRes1.reward, 6);
    assert.equal(claimRes1.tester_balance, 106);
    assert.equal(claimRes1.paid_count, 1);
    assert.equal(claimRes1.receipt.ordinal, 1);

    // Kiểm tra ví chủ app: TUYỆT ĐỐI KHÔNG BỊ TRỪ THÊM 6 CREDIT!
    const ownerBalAfterClaim1 = (await db.query(`select credits from public.profiles where id = $1`, [ownerId])).rows[0].credits;
    assert.equal(ownerBalAfterClaim1, 0, 'Ví chủ app không được trừ lại 6 Credit khi chi từ quỹ giữ');

    // Kiểm tra log của tester 1
    const t1Logs = (await db.query(`select * from public.credit_logs where user_id = $1 order by created_at desc`, [testers[0]])).rows;
    assert.equal(t1Logs[0].action, 'app_update_reward');
    assert.equal(t1Logs[0].amount, 6);
    assert.equal(t1Logs[0].balance, 106);

    // Kiểm tra release: remaining_credits = 114, paid_count = 1
    const relAfterClaim1 = (await db.query(`select * from public.app_update_releases where id = $1`, [release1Id])).rows[0];
    assert.equal(relAfterClaim1.remaining_credits, 114);
    assert.equal(relAfterClaim1.paid_count, 1);
    console.log('PASS 9: Tester 1 nhận +6 Credit thành công; quỹ giữ giảm còn 114, ví chủ KHÔNG trừ lại');

    // PASS 10: Tester 1 gọi lại claim -> Idempotent, newly_awarded = false, không cộng thêm tiền
    await asUser(testers[0], async () => {
      const res = await db.query(`select public.claim_app_update_reward($1, $2)`, [release1Id, 10]);
      const data = res.rows[0].claim_app_update_reward;
      assert.equal(data.success, true);
      assert.equal(data.newly_awarded, false);
      assert.equal(data.reward, 6);
      assert.equal(data.tester_balance, 106);
      assert.equal(data.receipt.ordinal, 1);
    });
    const relAfterReclaim = (await db.query(`select * from public.app_update_releases where id = $1`, [release1Id])).rows[0];
    assert.equal(relAfterReclaim.paid_count, 1);
    assert.equal(relAfterReclaim.remaining_credits, 114);
    console.log('PASS 10: Tester 1 gọi lại claim: Idempotent trả receipt cũ, không nhân đôi thưởng');

    // PASS 11: Thành viên mới tham gia SAU khi công bố release không được nhận thưởng
    const lateJoinerId = testers[24]; // Chưa join
    await db.exec(`
      insert into public.campaign_members (campaign_id, user_id, joined_at)
      values ('${campId}', '${lateJoinerId}', now() + interval '5 minutes');
    `);
    await asUser(lateJoinerId, async () => {
      await assert.rejects(async () => {
        await db.query(`select public.claim_app_update_reward($1, $2)`, [release1Id, 10]);
      }, (err) => {
        assert.match(err.message, /MEMBER_JOINED_AFTER_RELEASE/);
        return true;
      });
    });
    console.log('PASS 11: Tester tham gia sau thời điểm công bố bị từ chối (MEMBER_JOINED_AFTER_RELEASE)');

    // PASS 12: Đợt đủ 20 người nhận thưởng (từ ordinal 2 đến 20)
    for (let i = 1; i < 20; i++) {
      const tId = testers[i];
      await asUser(tId, async () => {
        const res = await db.query(`select public.claim_app_update_reward($1, $2)`, [release1Id, 12]);
        const data = res.rows[0].claim_app_update_reward;
        assert.equal(data.success, true);
        assert.equal(data.newly_awarded, true);
        assert.equal(data.receipt.ordinal, i + 1);
      });
    }

    const relExhausted = (await db.query(`select * from public.app_update_releases where id = $1`, [release1Id])).rows[0];
    assert.equal(relExhausted.paid_count, 20);
    assert.equal(relExhausted.remaining_credits, 0);
    assert.equal(relExhausted.status, 'exhausted');
    console.log('PASS 12: Đủ 20 người nhận thưởng: paid_count = 20, remaining = 0, status = exhausted');

    // PASS 13: Người thứ 21 claim bị từ chối với RELEASE_EXHAUSTED
    await asUser(testers[20], async () => {
      await assert.rejects(async () => {
        await db.query(`select public.claim_app_update_reward($1, $2)`, [release1Id, 10]);
      }, (err) => {
        assert.match(err.message, /RELEASE_EXHAUSTED/);
        return true;
      });
    });
    console.log('PASS 13: Người thứ 21 claim bị từ chối (RELEASE_EXHAUSTED)');

    // PASS 14: Khi đợt đã exhausted, get_campaign_detail ẩn khối cập nhật đối với tester
    await asUser(testers[20], async () => {
      const detail = await db.query(`select public.get_campaign_detail($1)`, [campId]);
      const payload = detail.rows[0].get_campaign_detail;
      assert.equal(payload.app_update, null, 'Khi đủ 20 suất, tester không thấy khối cập nhật nữa');
    });

    // Nhưng chủ app vẫn xem được chi tiết tiến độ đợt đã hoàn tất
    await asUser(ownerId, async () => {
      const detail = await db.query(`select public.get_campaign_detail($1)`, [campId]);
      const payload = detail.rows[0].get_campaign_detail;
      assert.notEqual(payload.app_update, null);
      assert.equal(payload.app_update.paid_count, 20);
      assert.equal(payload.app_update.spent_credits, 120);
    });
    console.log('PASS 14: get_campaign_detail ẩn khối cập nhật cho tester khi đủ 20 suất, chủ vẫn thấy');

    // PASS 15: Thay đợt khi chưa đủ 20 người: hoàn tiền chưa dùng và giữ 120 đợt mới trong cùng transaction
    // Cấp cho owner 200 Credit
    await db.exec(`update public.profiles set credits = 200 where id = '${ownerId}';`);

    // Công bố đợt 2 (versionCode 20)
    let pubRes2;
    await asUser(ownerId, async () => {
      const res = await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
        campId, '2.0.0', 20, 'Phiên bản 2.0 lớn'
      ]);
      pubRes2 = res.rows[0].publish_app_update;
    });
    assert.equal(pubRes2.success, true);
    assert.equal(pubRes2.owner_credits, 80); // 200 - 120 = 80
    const release2Id = pubRes2.release.id;

    // Cho 7 tester nhận thưởng đợt 2: đã chi 7 * 6 = 42, còn 120 - 42 = 78
    for (let i = 0; i < 7; i++) {
      await asUser(testers[i], async () => {
        await db.query(`select public.claim_app_update_reward($1, $2)`, [release2Id, 20]);
      });
    }

    const rel2Mid = (await db.query(`select * from public.app_update_releases where id = $1`, [release2Id])).rows[0];
    assert.equal(rel2Mid.paid_count, 7);
    assert.equal(rel2Mid.remaining_credits, 78);

    // Bây giờ chủ app có số dư khả dụng = 41. (41 + 78 hoàn lại = 119 < 120) -> publish bản 3 thất bại
    await db.exec(`update public.profiles set credits = 41 where id = '${ownerId}';`);
    await asUser(ownerId, async () => {
      await assert.rejects(async () => {
        await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
          campId, '3.0.0', 30, 'Bản 3 thiếu tiền'
        ]);
      }, (err) => {
        assert.match(err.message, /INSUFFICIENT_CREDITS/);
        return true;
      });
    });
    // Xác minh đợt 2 vẫn an toàn, không bị hoàn nửa vời
    const rel2StillActive = (await db.query(`select * from public.app_update_releases where id = $1`, [release2Id])).rows[0];
    assert.equal(rel2StillActive.status, 'active');
    assert.equal(rel2StillActive.remaining_credits, 78);

    // Chủ có 42 Credit (42 + 78 hoàn lại = 120) -> publish bản 3 thành công!
    await db.exec(`update public.profiles set credits = 42 where id = '${ownerId}';`);
    let pubRes3;
    await asUser(ownerId, async () => {
      const res = await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
        campId, '3.0.0', 30, 'Bản 3 đủ tiền sau hoàn'
      ]);
      pubRes3 = res.rows[0].publish_app_update;
    });

    assert.equal(pubRes3.success, true);
    assert.equal(pubRes3.refund_amount, 78);
    assert.equal(pubRes3.owner_credits, 0); // 42 + 78 - 120 = 0

    // Đợt 2 chuyển sang superseded, refunded_credits = 78, remaining = 0
    const rel2Closed = (await db.query(`select * from public.app_update_releases where id = $1`, [release2Id])).rows[0];
    assert.equal(rel2Closed.status, 'superseded');
    assert.equal(rel2Closed.refunded_credits, 78);
    assert.equal(rel2Closed.remaining_credits, 0);

    // Kiểm tra log của chủ app: có log refund +78 và log reserve -120
    const ownerLogsLatest = (await db.query(`select * from public.credit_logs where user_id = $1 order by created_at desc limit 2`, [ownerId])).rows;
    const actions = ownerLogsLatest.map(l => ({ action: l.action, amount: l.amount })).sort((a, b) => a.amount - b.amount);
    assert.deepEqual(actions, [
      { action: 'app_update_reserve', amount: -120 },
      { action: 'app_update_refund', amount: 78 }
    ]);

    console.log('PASS 15: Thay đợt nguyên tử: Hoàn 78 Credit đợt cũ và giữ 120 đợt mới trong cùng transaction');

    // PASS 16: Claim vào đợt cũ đã superseded bị từ chối với RELEASE_REPLACED
    await asUser(testers[10], async () => {
      await assert.rejects(async () => {
        await db.query(`select public.claim_app_update_reward($1, $2)`, [release2Id, 20]);
      }, (err) => {
        assert.match(err.message, /RELEASE_REPLACED/);
        return true;
      });
    });
    console.log('PASS 16: Claim vào release đã superseded bị từ chối (RELEASE_REPLACED)');

    const release3Id = pubRes3.release.id;

    // PASS 17: Chủ app kết thúc sớm đợt (close_app_update_release) và hoàn tiền
    let closeRes;
    await asUser(ownerId, async () => {
      const res = await db.query(`select public.close_app_update_release($1)`, [release3Id]);
      closeRes = res.rows[0].close_app_update_release;
    });
    assert.equal(closeRes.success, true);
    assert.equal(closeRes.refund_amount, 120);
    assert.equal(closeRes.owner_credits, 120);

    const rel3Closed = (await db.query(`select * from public.app_update_releases where id = $1`, [release3Id])).rows[0];
    assert.equal(rel3Closed.status, 'closed');
    assert.equal(rel3Closed.remaining_credits, 0);
    assert.equal(rel3Closed.refunded_credits, 120);
    console.log('PASS 17: Chủ kết thúc sớm đợt, hoàn trả toàn bộ 120 Credit chưa dùng (close_app_update_release)');

    // PASS 18: Trigger tự động hoàn tiền khi campaign completed/production hoặc delete
    // Tạo owner 2 và campaign 2
    const owner2Id = '44444444-4444-4444-4444-444444444444';
    await db.exec(`insert into auth.users (id, email) values ('${owner2Id}', 'owner2@test.com');`);
    await db.exec(`update public.profiles set credits = 120 where id = '${owner2Id}';`);

    const camp2Id = 'aaaaaaaa-aaaa-aaaa-aaaa-cccccccccccc';
    await db.exec(`
      insert into public.campaigns (id, owner_id, app_name, package_name, status, target_days)
      values ('${camp2Id}', '${owner2Id}', 'App Clean Test', 'com.clean.app', 'open', 14);
    `);
    let pubCamp2;
    await asUser(owner2Id, async () => {
      const res = await db.query(`select public.publish_app_update($1, $2, $3, $4)`, [
        camp2Id, '1.0', 1, 'Clean test'
      ]);
      pubCamp2 = res.rows[0].publish_app_update;
    });
    assert.equal(pubCamp2.owner_credits, 0); // 120 - 120 = 0

    // Update campaign 2 sang completed -> trigger kích hoạt hoàn tiền
    await db.exec(`update public.campaigns set status = 'completed' where id = '${camp2Id}';`);
    const ownerBalAfterCompleted = (await db.query(`select credits from public.profiles where id = $1`, [owner2Id])).rows[0].credits;
    assert.equal(ownerBalAfterCompleted, 120, 'Trigger hoàn 120 Credit về ví chủ khi campaign completed');

    const relCamp2 = (await db.query(`select * from public.app_update_releases where id = $1`, [pubCamp2.release.id])).rows[0];
    assert.equal(relCamp2.status, 'closed');
    assert.equal(relCamp2.refunded_credits, 120);

    // Xóa campaign 2 -> trigger BEFORE DELETE chạy êm đẹp, không crash và không gây quỹ mồ côi
    await db.exec(`delete from public.campaigns where id = '${camp2Id}';`);
    console.log('PASS 18: Trigger hoàn tiền an toàn khi campaign completed hoặc deleted');

    // PASS 19: Lấy lịch sử phiên bản cho chủ app (get_campaign_update_history)
    await asUser(ownerId, async () => {
      const hist = await db.query(`select public.get_campaign_update_history($1)`, [campId]);
      const historyList = hist.rows[0].get_campaign_update_history;
      assert.equal(historyList.length, 3); // 3 đợt đã tạo cho campId (v1.0.0, v2.0.0, v3.0.0)
      assert.equal(historyList[0].version_name, '3.0.0');
      assert.equal(historyList[1].version_name, '2.0.0');
      assert.equal(historyList[2].version_name, '1.0.0');
    });
    console.log('PASS 19: get_campaign_update_history trả đầy đủ lịch sử các đợt cập nhật cho chủ app');

    // PASS 20: Kiểm tra bất biến kế toán trên toàn bộ bảng app_update_releases
    const allReleases = (await db.query(`select * from public.app_update_releases`)).rows;
    for (const r of allReleases) {
      const sum = r.remaining_credits + (r.paid_count * r.reward_per_claim) + r.refunded_credits;
      assert.equal(sum, r.reserved_credits, `Bất biến kế toán thất bại trên release ${r.id}: ${sum} != ${r.reserved_credits}`);
      assert.equal(r.reserved_credits, 120, 'Reserved credits luôn là 120');
      assert.equal(r.reward_per_claim, 6, 'Reward per claim luôn là 6');
      assert.ok(r.paid_count >= 0 && r.paid_count <= 20, 'Paid count trong khoảng 0..20');
    }
    console.log('PASS 20: Bất biến kế toán: reserved_total (120) = remaining + (paid_count * 6) + refunded khớp 100% trên mọi release');

    // =========================================================================
    // MIGRATION 051 HARDENING REGRESSIONS (DB01 - DB08 / R01 - R10)
    // =========================================================================

    // PASS 21 (R01 / DB01): Thu hồi quyền thực thi _settle_campaign_app_update khỏi authenticated role
    let threw = false;
    await db.exec('begin; set local role authenticated;');
    try {
      await db.query("select set_config('request.jwt.claim.sub', $1, true)", [strangerId]);
      await db.query(`select public._settle_campaign_app_update($1)`, [campId]);
      await db.exec('commit;');
    } catch (err) {
      await db.exec('rollback;');
      threw = true;
      assert.ok(err.message.includes('permission denied'), 'Người lạ gọi helper quyết toán phải bị permission denied');
    }
    assert.ok(threw, 'Phải ném exception permission denied khi stranger gọi _settle_campaign_app_update');
    console.log('PASS 21 (R01): Thu hồi quyền gọi helper quyết toán khỏi client/authenticated');

    // PASS 22 (R02 / DB02): Chuyển trạng thái từ paused sang completed/production tự hoàn tiền
    const ownerPass22 = '55555555-5555-5555-5555-555555555522';
    await db.query(`insert into auth.users(id, email) values($1, 'owner22@test.com')`, [ownerPass22]);
    await db.query(`update public.profiles set credits = 1000 where id = $1`, [ownerPass22]);
    const pausedCampId = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
    await db.query(`insert into public.campaigns(id, owner_id, app_name, package_name, status, target_days) values($1, $2, 'Paused App', 'com.example.paused', 'open', 14)`, [pausedCampId, ownerPass22]);
    let pubPaused;
    await asUser(ownerPass22, async () => {
      const res = await db.query(`select public.publish_app_update($1, 'v1.0.0', 10, 'Paused test notes')`, [pausedCampId]);
      pubPaused = res.rows[0].publish_app_update;
    });
    // Chuyển sang paused rồi sang completed
    await db.query(`update public.campaigns set status = 'paused' where id = $1`, [pausedCampId]);
    await db.query(`update public.campaigns set status = 'completed' where id = $1`, [pausedCampId]);
    const pausedRelState = (await db.query(`select * from public.app_update_releases where id = $1`, [pubPaused.release.id])).rows[0];
    assert.equal(pausedRelState.status, 'closed', 'Release phải closed sau khi paused -> completed');
    assert.equal(pausedRelState.refunded_credits, 120, 'Phải hoàn 120 Credit');
    console.log('PASS 22 (R02): Tạm dừng rồi hoàn tất (paused -> completed) tự động hoàn Credit');

    // PASS 23 (R04 / DB03): Publish cùng operation_id nhưng khác payload bị chặn với OPERATION_PAYLOAD_CONFLICT
    const ownerPass23 = '55555555-5555-5555-5555-555555555523';
    await db.query(`insert into auth.users(id, email) values($1, 'owner23@test.com')`, [ownerPass23]);
    await db.query(`update public.profiles set credits = 1000 where id = $1`, [ownerPass23]);
    const conflictCampA = 'dddddddd-dddd-dddd-dddd-dddddddddd01';
    const conflictCampB = 'dddddddd-dddd-dddd-dddd-dddddddddd02';
    await db.query(`insert into public.campaigns(id, owner_id, app_name, package_name, status, target_days) values($1, $2, 'App A', 'com.example.appa', 'open', 14)`, [conflictCampA, ownerPass23]);
    await db.query(`insert into public.campaigns(id, owner_id, app_name, package_name, status, target_days) values($1, $2, 'App B', 'com.example.appb', 'paused', 14)`, [conflictCampB, ownerPass23]);
    const sharedOpId = 'shared-op-test-123';
    await asUser(ownerPass23, async () => {
      await db.query(`select public.publish_app_update($1, 'v1.0.0', 10, 'Notes A', $2)`, [conflictCampA, sharedOpId]);
      // Pause camp A rồi open camp B để tuân thủ 1 slot
      await db.query(`update public.campaigns set status = 'paused' where id = $1`, [conflictCampA]);
      await db.query(`update public.campaigns set status = 'open' where id = $1`, [conflictCampB]);
      let threwConflict = false;
      try {
        await db.query(`select public.publish_app_update($1, 'v1.0.0', 10, 'Notes B', $2)`, [conflictCampB, sharedOpId]);
      } catch (err) {
        threwConflict = true;
        assert.ok(err.message.includes('OPERATION_PAYLOAD_CONFLICT'), 'Cùng operation_id khác payload phải ném OPERATION_PAYLOAD_CONFLICT');
      }
      assert.ok(threwConflict, 'Phải ném exception OPERATION_PAYLOAD_CONFLICT');
    });
    console.log('PASS 23 (R04): Publish cùng operation_id khác payload bị chặn với OPERATION_PAYLOAD_CONFLICT');

    // PASS 24 (R05 / DB04): Form mong đợi không có đợt (expected=null) bị từ chối nếu có đợt phát hành giữa chừng
    const ownerPass24 = '55555555-5555-5555-5555-555555555524';
    await db.query(`insert into auth.users(id, email) values($1, 'owner24@test.com')`, [ownerPass24]);
    await db.query(`update public.profiles set credits = 1000 where id = $1`, [ownerPass24]);
    const casCampId = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
    await db.query(`insert into public.campaigns(id, owner_id, app_name, package_name, status, target_days) values($1, $2, 'CAS App', 'com.example.cas', 'open', 14)`, [casCampId, ownerPass24]);
    let firstCasRel;
    await asUser(ownerPass24, async () => {
      const res = await db.query(`select public.publish_app_update($1, 'v1.0', 10, 'First CAS', 'op-cas-1', null)`, [casCampId]);
      firstCasRel = res.rows[0].publish_app_update;
      let threwCas = false;
      try {
        // Form cũ tải khi chưa có release, gửi expected = null, nhưng hiện đã có firstCasRel
        await db.query(`select public.publish_app_update($1, 'v1.1', 11, 'Second CAS', 'op-cas-2', null)`, [casCampId]);
      } catch (err) {
        threwCas = true;
        assert.ok(err.message.includes('CONCURRENT_UPDATE_CONFLICT'), 'Form cũ mong đợi null phải bị CONCURRENT_UPDATE_CONFLICT');
      }
      assert.ok(threwCas, 'Phải ném exception CONCURRENT_UPDATE_CONFLICT');
    });
    console.log('PASS 24 (R05): Concurrency CAS: Form cũ mong đợi không có đợt bị từ chối nếu có đợt phát hành');

    // PASS 25 (R10 / DB05): Chủ app bị khóa (is_locked = true) bị chặn công bố đợt mới với USER_LOCKED
    await db.query(`update public.profiles set is_locked = true where id = $1`, [ownerPass24]);
    await asUser(ownerPass24, async () => {
      let threwLocked = false;
      try {
        await db.query(`select public.publish_app_update($1, 'v2.0', 20, 'Locked notes')`, [casCampId]);
      } catch (err) {
        threwLocked = true;
        assert.ok(err.message.includes('USER_LOCKED'), 'Chủ app bị khóa phải nhận USER_LOCKED');
      }
      assert.ok(threwLocked, 'Phải ném exception USER_LOCKED');
    });
    await db.query(`update public.profiles set is_locked = false where id = $1`, [ownerPass24]);
    console.log('PASS 25 (R10): Chủ app bị khóa bị chặn công bố đợt mới với USER_LOCKED');

    // PASS 26 (R03 / DB06): Xóa tài khoản chủ app sau khi dùng tính năng không bị lỗi FK
    const disposableOwner = '44444444-4444-4444-4444-444444444426';
    await db.query(`insert into auth.users(id, email) values($1, 'disposable26@test.com')`, [disposableOwner]);
    await db.query(`update public.profiles set credits = 120 where id = $1`, [disposableOwner]);
    const dispCampId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb26';
    await db.query(`insert into public.campaigns(id, owner_id, app_name, package_name, status, target_days) values($1, $2, 'Disposable App', 'com.example.disp', 'open', 14)`, [dispCampId, disposableOwner]);
    let dispPub;
    await asUser(disposableOwner, async () => {
      const res = await db.query(`select public.publish_app_update($1, 'v1.0', 1, 'Notes')`, [dispCampId]);
      dispPub = res.rows[0].publish_app_update;
      await db.query(`select public.close_app_update_release($1)`, [dispPub.release.id]);
    });
    // Xóa auth.users -> cascade profiles -> app_update_releases.owner_id sets null, không vi phạm FK
    await db.query(`delete from auth.users where id = $1`, [disposableOwner]);
    console.log('PASS 26 (R03): Xóa tài khoản chủ app thành công, giải phóng FK và giữ lịch sử tài chính');

    // PASS 27 (R07 / DB07): Tester tham gia sau thời điểm công bố nhận is_eligible = false
    const ownerPass27 = '55555555-5555-5555-5555-555555555527';
    await db.query(`insert into auth.users(id, email) values($1, 'owner27@test.com')`, [ownerPass27]);
    await db.query(`update public.profiles set credits = 1000 where id = $1`, [ownerPass27]);
    const campPass27 = 'cccccccc-cccc-cccc-cccc-cccccccccc27';
    await db.query(`insert into public.campaigns(id, owner_id, app_name, package_name, status, target_days) values($1, $2, 'R07 App', 'com.example.r07', 'open', 14)`, [campPass27, ownerPass27]);
    await db.query(`insert into public.campaign_members(campaign_id, user_id, joined_at) values($1, $2, now() + interval '1 hour')`, [campPass27, testers[0]]);
    await asUser(ownerPass27, async () => {
      await db.query(`select public.publish_app_update($1, 'v1.0', 10, 'Release for R07')`, [campPass27]);
    });
    await asUser(testers[0], async () => {
      const detail = (await db.query(`select public.get_campaign_detail($1) as res`, [campPass27])).rows[0].res;
      assert.equal(detail.app_update?.is_eligible, false, 'Late joiner phải có is_eligible = false');
      assert.equal(detail.app_update?.ineligibility_reason, 'joined_after_release');
    });
    console.log('PASS 27 (R07): Read contract trả is_eligible = false cho tester tham gia sau ngày phát hành');

    // PASS 28 (R09 / DB08): Đóng đợt mới nhất không phục hồi đợt exhausted cũ
    const ownerPass28 = '55555555-5555-5555-5555-555555555528';
    await db.query(`insert into auth.users(id, email) values($1, 'owner28@test.com')`, [ownerPass28]);
    await db.query(`update public.profiles set credits = 1000 where id = $1`, [ownerPass28]);
    const r09CampId = 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    await db.query(`insert into public.campaigns(id, owner_id, app_name, package_name, status, target_days) values($1, $2, 'R09 App', 'com.example.r09', 'open', 14)`, [r09CampId, ownerPass28]);
    for (const t of testers) {
      await db.query(`insert into public.campaign_members(campaign_id, user_id, joined_at) values($1, $2, now() - interval '1 day') on conflict do nothing`, [r09CampId, t]);
    }
    let r09PubA, r09PubB;
    await asUser(ownerPass28, async () => {
      const resA = await db.query(`select public.publish_app_update($1, 'v1.0', 10, 'Round 1')`, [r09CampId]);
      r09PubA = resA.rows[0].publish_app_update;
    });
    // 20 tester claim hết round 1
    for (let i = 0; i < 20; i++) {
      await asUser(testers[i], async () => {
        await db.query(`select public.claim_app_update_reward($1, 10)`, [r09PubA.release.id]);
      });
    }
    // Owner publish round 2 (code 20) rồi đóng round 2
    await asUser(ownerPass28, async () => {
      const resB = await db.query(`select public.publish_app_update($1, 'v2.0', 20, 'Round 2')`, [r09CampId]);
      r09PubB = resB.rows[0].publish_app_update;
      await db.query(`select public.close_app_update_release($1)`, [r09PubB.release.id]);
      const detail = (await db.query(`select public.get_campaign_detail($1) as res`, [r09CampId])).rows[0].res;
      assert.ok(detail.app_update === null || detail.app_update?.id === r09PubB.release.id, 'Sau khi đóng đợt 2, không được phục hồi đợt 1 exhausted');
    });
    console.log('PASS 28 (R09): Đóng đợt mới nhất không phục hồi đợt exhausted cũ trên màn hình chủ');

    console.log('=== TẤT CẢ 28 KIỂM THỬ BACKEND MIGRATION 050 & 051 ĐỀU PASS 100% ===');
  } finally {
    await db.close();
  }
}

runTests().catch((err) => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});
