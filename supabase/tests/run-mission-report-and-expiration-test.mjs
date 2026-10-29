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
  console.log('=== BẮT ĐẦU KIỂM THỬ BACKEND MIGRATION 048 & 049 (BÁO CÁO NHIỆM VỤ & HARDENING) ===');
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });

  try {
    // 1. Áp dụng schema baseline và các migration liên tiếp
    await db.exec(bootstrap);
    await db.exec(fullSchema);
    await db.exec(migration045);
    await db.exec(migration046);
    await db.exec(migration047);
    await db.exec(migration048);
    await db.exec(migration049);
    // Kiểm tra tính idempotent khi chạy lại migration 048 và 049
    await db.exec(migration048);
    await db.exec(migration049);
    console.log('PASS 1: Nạp thành công full schema và migration 045 -> 049 (xác minh idempotent)');

    // 2. Tạo test users & campaigns
    const ownerId = '11111111-1111-1111-1111-111111111111';
    const testerId = '22222222-2222-2222-2222-222222222222';
    const otherTesterId = '33333333-3333-3333-3333-333333333333';
    const camp1 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
    const camp2 = 'aaaaaaaa-aaaa-aaaa-aaaa-bbbbbbbbbbbb';
    const missionActive = 'bbbbbbbb-bbbb-bbbb-bbbb-aaaaaaaaaaaa';
    const missionExpired = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

    await db.exec(`
      insert into auth.users (id, email) values
        ('${ownerId}', 'owner@test.com'),
        ('${testerId}', 'tester@test.com'),
        ('${otherTesterId}', 'othertester@test.com');

      update public.profiles set credits = 10000 where id = '${ownerId}';
      update public.profiles set credits = 100 where id = '${testerId}';
      update public.profiles set credits = 100 where id = '${otherTesterId}';

      insert into public.user_slots(user_id, slot_number, expires_at)
      select '${ownerId}'::uuid, n, now() + interval '30 days' from generate_series(2, 4) n
      on conflict do nothing;

      insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values
        ('${camp1}', '${ownerId}', 'com.app.one', 'App One', 'open', 1),
        ('${camp2}', '${ownerId}', 'com.app.two', 'App Two', 'open', 2);

      -- Mission 1: Active, ends in 2 days
      insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
      values ('${missionActive}', '${ownerId}', '${camp1}', 'Test Nhiem Vu 1', 10, 3, 20, 'open', now() + interval '2 days');

      -- Mission 2: Expired, ended 1 hour ago
      insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
      values ('${missionExpired}', '${ownerId}', '${camp2}', 'Test Nhiem Vu 2 Het Han', 5, 1, 15, 'open', now() - interval '1 hour');

      -- Tester join mission 1
      insert into public.mission_participants (mission_id, user_id, status, joined_at)
      values ('${missionActive}', '${testerId}', 'joined', now() - interval '30 minutes');
    `);

    // Helper đóng vai authenticated user
    const asUser = async (uid, fn) => {
      await db.exec(`select set_config('request.jwt.claim.sub', '${uid}', false); set role authenticated;`);
      try {
        return await fn();
      } finally {
        await db.exec('reset role;');
      }
    };

    // 3. Test get_open_missions: Lọc hết hạn và whitelist status
    await asUser(testerId, async () => {
      const res = (await db.query('select public.get_open_missions() as result')).rows[0].result;
      assert.ok(res.server_now, 'server_now phải có trong response');
      assert.equal(res.missions.length, 1, 'Chỉ 1 mission còn hạn được trả về');
      assert.equal(res.missions[0].id, missionActive);
      assert.equal(res.missions[0].has_joined, true);
    });
    console.log('PASS 2: get_open_missions lọc bỏ mission hết hạn và trả server_now');

    // 4. Test submit_tester_report với mission_id hợp lệ
    const clientSubmitId1 = '44444444-4444-4444-4444-444444444444';
    const fakeScreenshot = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp1}%2F${testerId}%2F${clientSubmitId1}.jpg?alt=media&token=fixture`;
    const fakeThumb = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp1}%2F${testerId}%2Fthumb_${clientSubmitId1}.jpg?alt=media&token=fixture`;
    const fakeHash = '1234567890abcdef';

    let submitResult;
    await asUser(testerId, async () => {
      submitResult = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp1}'::uuid,
          p_content => 'Báo cáo làm nhiệm vụ',
          p_screenshot_url => '${fakeScreenshot}',
          p_rating => 5,
          p_image_hash => '${fakeHash}',
          p_client_submit_id => '${clientSubmitId1}'::uuid,
          p_thumbnail_url => '${fakeThumb}',
          p_mission_id => '${missionActive}'::uuid
        ) as result
      `)).rows[0].result;
    });

    assert.equal(submitResult.success, true);
    assert.equal(submitResult.submission_context, 'mission');
    assert.equal(submitResult.mission_id, missionActive);
    assert.equal(submitResult.mission_title, 'Test Nhiem Vu 1');
    assert.equal(submitResult.mission_reward_credits, 20);
    console.log('PASS 3: Nộp báo cáo nhiệm vụ thành công với context mission và snapshot đầy đủ');

    // 5. Test RLS/Trigger/Privilege: Chặn can thiệp metadata trực tiếp qua SQL
    let directTamperBlocked = false;
    await asUser(testerId, async () => {
      try {
        await db.exec(`
          update public.tester_reports
          set mission_title = 'Hacked Title'
          where id = '${submitResult.report_id}'
        `);
      } catch (err) {
        directTamperBlocked = true;
        assert.match(err.message, /permission denied|CANNOT_MODIFY_MISSION_METADATA_DIRECTLY/);
      }
    });
    assert.equal(directTamperBlocked, true, 'Quyền bảng/Trigger phải chặn sửa metadata nhiệm vụ trực tiếp');

    // Thử insert trực tiếp với mission metadata bằng role authenticated -> trigger chặn
    await db.exec('grant insert on public.tester_reports to authenticated;');
    let insertTriggerBlocked = false;
    await asUser(testerId, async () => {
      try {
        await db.exec(`
          insert into public.tester_reports (campaign_id, user_id, report_type, submission_context)
          values ('${camp1}', '${testerId}', 'feedback', 'mission');
        `);
      } catch (err) {
        insertTriggerBlocked = true;
        assert.match(err.message, /CANNOT_SET_MISSION_METADATA_DIRECTLY/);
      }
    });
    await db.exec('revoke insert on public.tester_reports from authenticated;');
    assert.equal(insertTriggerBlocked, true, 'Trigger phải chặn chèn metadata mission trực tiếp');

    // Thử owner update metadata trực tiếp -> trigger chặn
    await db.exec('grant update on public.tester_reports to authenticated;');
    let ownerUpdateTriggerBlocked = false;
    await asUser(ownerId, async () => {
      try {
        await db.exec(`
          update public.tester_reports
          set mission_title = 'Owner Hacked Mission Title'
          where id = '${submitResult.report_id}';
        `);
      } catch (err) {
        ownerUpdateTriggerBlocked = true;
        assert.match(err.message, /CANNOT_MODIFY_MISSION_METADATA_DIRECTLY/);
      }
    });
    await db.exec('revoke update on public.tester_reports from authenticated;');
    assert.equal(ownerUpdateTriggerBlocked, true, 'Trigger phải chặn owner sửa metadata mission trực tiếp');
    console.log('PASS 4: Quyền bảng và trigger bảo vệ metadata nhiệm vụ khỏi việc chèn/sửa trực tiếp');

    // 6. Test Idempotency: Gửi lại cùng client_submit_id
    await asUser(testerId, async () => {
      const replay = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp1}'::uuid,
          p_content => 'Nội dung khác sẽ không đổi do replay receipt',
          p_screenshot_url => '${fakeScreenshot}',
          p_rating => 5,
          p_image_hash => '${fakeHash}',
          p_client_submit_id => '${clientSubmitId1}'::uuid,
          p_thumbnail_url => '${fakeThumb}',
          p_mission_id => '${missionActive}'::uuid
        ) as result
      `)).rows[0].result;

      assert.equal(replay.report_id, submitResult.report_id);
      assert.equal(replay.submission_context, 'mission');
      assert.equal(replay.mission_title, 'Test Nhiem Vu 1');
    });
    console.log('PASS 5: Idempotency replay trả lại đúng receipt và metadata đã commit');

    // 7. Test Đường vào khác: Tự động phân loại khi không truyền mission_id
    // Tester join mission ở campaign mới
    const camp3 = 'aaaaaaaa-aaaa-aaaa-aaaa-cccccccccccc';
    const mission3 = 'bbbbbbbb-bbbb-bbbb-bbbb-cccccccccccc';
    await db.exec(`
      insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values
        ('${camp3}', '${ownerId}', 'com.app.three', 'App Three', 'open', 3);
      insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
      values ('${mission3}', '${ownerId}', '${camp3}', 'Mission 3 Auto Attach', 10, 3, 25, 'open', now() + interval '2 days');
      insert into public.mission_participants (mission_id, user_id, status, joined_at)
      values ('${mission3}', '${testerId}', 'joined', now() - interval '10 minutes');
    `);

    const clientSubmitId3 = '55555555-5555-5555-5555-555555555555';
    const fakeScreenshot3 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp3}%2F${testerId}%2F${clientSubmitId3}.jpg?alt=media&token=fixture`;
    let autoAttachResult;
    await asUser(testerId, async () => {
      autoAttachResult = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp3}'::uuid,
          p_content => 'Vào từ Khám phá không truyền mission_id',
          p_screenshot_url => '${fakeScreenshot3}',
          p_rating => 5,
          p_image_hash => '${fakeHash}',
          p_client_submit_id => '${clientSubmitId3}'::uuid
        ) as result
      `)).rows[0].result;
    });

    assert.equal(autoAttachResult.success, true);
    assert.equal(autoAttachResult.submission_context, 'mission');
    assert.equal(autoAttachResult.mission_id, mission3);
    assert.equal(autoAttachResult.mission_title, 'Mission 3 Auto Attach');
    assert.equal(autoAttachResult.mission_reward_credits, 25);
    console.log('PASS 6: Tự động phân loại mission khi người dùng mở từ Khám phá');

    // 8. Test Báo cáo thường: Không có mission -> submission_context = app_test
    const camp4 = 'aaaaaaaa-aaaa-aaaa-aaaa-dddddddddddd';
    await db.exec(`
      insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values
        ('${camp4}', '${ownerId}', 'com.app.four', 'App Four', 'open', 4);
    `);
    const clientSubmitId4 = '66666666-6666-6666-6666-666666666666';
    const fakeScreenshot4 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp4}%2F${testerId}%2F${clientSubmitId4}.jpg?alt=media&token=fixture`;
    let normalResult;
    await asUser(testerId, async () => {
      normalResult = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp4}'::uuid,
          p_content => 'Báo cáo thường không có mission',
          p_screenshot_url => '${fakeScreenshot4}',
          p_rating => 5,
          p_image_hash => '${fakeHash}',
          p_client_submit_id => '${clientSubmitId4}'::uuid
        ) as result
      `)).rows[0].result;
    });

    assert.equal(normalResult.success, true);
    assert.equal(normalResult.submission_context, 'app_test');
    assert.equal(normalResult.mission_id, null);
    console.log('PASS 7: Báo cáo thường không có mission gắn context app_test chính xác');

    // 9. Test Chặn nộp nhiệm vụ đã hết hạn
    let expiredSubmitBlocked = false;
    await asUser(testerId, async () => {
      try {
        await db.query(`
          select public.submit_tester_report(
            p_campaign_id => '${camp2}'::uuid,
            p_content => 'Cố nộp nhiệm vụ hết hạn',
            p_screenshot_url => '${fakeScreenshot}',
            p_rating => 5,
            p_image_hash => '${fakeHash}',
            p_mission_id => '${missionExpired}'::uuid
          )
        `);
      } catch (err) {
        expiredSubmitBlocked = true;
        assert.match(err.message, /MISSION_EXPIRED/);
      }
    });
    assert.equal(expiredSubmitBlocked, true);
    console.log('PASS 8: Chặn nộp nhiệm vụ khi mission đã hết hạn');

    // 10. Test Chặn nộp khi chưa join mission
    let notJoinedBlocked = false;
    await asUser(otherTesterId, async () => {
      try {
        await db.query(`
          select public.submit_tester_report(
            p_campaign_id => '${camp1}'::uuid,
            p_content => 'Chưa join mà đòi nộp',
            p_screenshot_url => '${fakeScreenshot}',
            p_rating => 5,
            p_image_hash => '${fakeHash}',
            p_mission_id => '${missionActive}'::uuid
          )
        `);
      } catch (err) {
        notJoinedBlocked = true;
        assert.match(err.message, /MISSION_NOT_JOINED/);
      }
    });
    assert.equal(notJoinedBlocked, true);
    console.log('PASS 9: Chặn nộp nhiệm vụ khi tester chưa join');

    // 11. Test Settlement: Báo cáo thường không được trả thưởng nhiệm vụ
    await db.exec(`update public.tester_reports set status = 'approved' where id = '${normalResult.report_id}'`);
    const normalSettle = (await db.query(`select public._settle_mission_reward('${normalResult.report_id}'::uuid) as result`)).rows[0].result;
    assert.equal(normalSettle.settled, false);
    assert.equal(normalSettle.reason, 'NOT_A_MISSION_REPORT');
    console.log('PASS 10: _settle_mission_reward từ chối trả thưởng cho báo cáo thường');

    // 12. Test Settlement: Báo cáo nhiệm vụ được duyệt trong hạn -> trả thưởng chính xác 1 lần
    const ownerBalBefore = (await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits;
    const testerBalBefore = (await db.query(`select credits from public.profiles where id = '${testerId}'`)).rows[0].credits;

    await db.exec(`update public.tester_reports set status = 'approved' where id = '${submitResult.report_id}'`);
    const missionSettle = (await db.query(`select public._settle_mission_reward('${submitResult.report_id}'::uuid) as result`)).rows[0].result;

    assert.equal(missionSettle.settled, true);
    assert.equal(missionSettle.reward_credits, 20);

    const ownerBalAfter = (await db.query(`select credits from public.profiles where id = '${ownerId}'`)).rows[0].credits;
    const testerBalAfter = (await db.query(`select credits from public.profiles where id = '${testerId}'`)).rows[0].credits;
    assert.equal(ownerBalAfter, ownerBalBefore - 20, 'Chủ app bị trừ đúng 20 Credit');
    assert.equal(testerBalAfter, testerBalBefore + 20, 'Tester được cộng đúng 20 Credit');

    // Replay settle -> ALREADY_SETTLED
    const settleReplay = (await db.query(`select public._settle_mission_reward('${submitResult.report_id}'::uuid) as result`)).rows[0].result;
    assert.equal(settleReplay.settled, false);
    assert.equal(settleReplay.reason, 'ALREADY_SETTLED');
    console.log('PASS 11: _settle_mission_reward trả thưởng nguyên tử và idempotent');

    // 13. Test get_campaign_reports_for_owner trả đầy đủ metadata
    await asUser(ownerId, async () => {
      const ownerReports = (await db.query(`select public.get_campaign_reports_for_owner('${camp1}'::uuid) as result`)).rows[0].result;
      const r = ownerReports.find(x => x.id === submitResult.report_id);
      assert.ok(r);
      assert.equal(r.submission_context, 'mission');
      assert.equal(r.mission_id, missionActive);
      assert.equal(r.mission_title, 'Test Nhiem Vu 1');
      assert.equal(r.mission_reward_credits, 20);
      assert.ok(r.mission_ends_at);
      assert.ok(r.thumbnail_url);
    });
    console.log('PASS 12: get_campaign_reports_for_owner trả đủ metadata nhiệm vụ cho chủ app');

    // 14. Test Không hồi tố: Approved report trước khi join mission không thể đổi sang mission
    const camp5 = 'aaaaaaaa-aaaa-aaaa-aaaa-eeeeeeeeeeee';
    const mission5 = 'bbbbbbbb-bbbb-bbbb-bbbb-eeeeeeeeeeee';
    const clientSubmitId5 = '77777777-7777-7777-7777-777777777777';
    const fakeScreenshot5 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp5}%2F${testerId}%2F${clientSubmitId5}.jpg?alt=media&token=fixture`;

    await db.exec(`
      delete from public.campaigns where id = '${camp4}';
      insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number) values
        ('${camp5}', '${ownerId}', 'com.app.five', 'App Five', 'open', 4);
    `);

    // Tester nộp báo cáo thường trước
    await asUser(testerId, async () => {
      await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp5}'::uuid,
          p_content => 'Nộp trước khi có mission',
          p_screenshot_url => '${fakeScreenshot5}',
          p_rating => 5,
          p_image_hash => '${fakeHash}',
          p_client_submit_id => '${clientSubmitId5}'::uuid
        )
      `);
    });
    // Báo cáo được duyệt
    await db.exec(`update public.tester_reports set status = 'approved' where campaign_id = '${camp5}' and user_id = '${testerId}'`);

    // Sau đó chủ app mở mission và tester join
    await db.exec(`
      insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
      values ('${mission5}', '${ownerId}', '${camp5}', 'Mission 5 Late Join', 10, 3, 30, 'open', now() + interval '2 days');
      insert into public.mission_participants (mission_id, user_id, status, joined_at)
      values ('${mission5}', '${testerId}', 'joined', now());
    `);

    // Tester cố nộp/chỉnh sửa báo cáo đã approved thành mission report
    let convertApprovedBlocked = false;
    await asUser(testerId, async () => {
      try {
        await db.query(`
          select public.submit_tester_report(
            p_campaign_id => '${camp5}'::uuid,
            p_content => 'Cố gắng đổi thành mission report',
            p_mission_id => '${mission5}'::uuid
          )
        `);
      } catch (err) {
        convertApprovedBlocked = true;
        assert.match(err.message, /REPORT_ALREADY_APPROVED_AS_APP_TEST/);
      }
    });
    assert.equal(convertApprovedBlocked, true);
    console.log('PASS 13: Chặn hồi tố chuyển đổi báo cáo đã approved thành báo cáo nhiệm vụ');

    // 15. Test Nộp lại sau khi reject (Resubmit)
    // Tester nộp bài mới cho mission 5 nhưng bị reject
    const clientSubmitId6 = '88888888-8888-8888-8888-888888888888';
    const fakeScreenshot6 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp5}%2F${otherTesterId}%2F${clientSubmitId6}.jpg?alt=media&token=fixture`;
    const fakeHashA = 'aaaaaaaaaaaaaaaa';
    const fakeHashB = 'bbbbbbbbbbbbbbbb';

    await db.exec(`
      insert into public.mission_participants (mission_id, user_id, status, joined_at)
      values ('${mission5}', '${otherTesterId}', 'joined', now());
    `);

    let rep6;
    await asUser(otherTesterId, async () => {
      rep6 = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp5}'::uuid,
          p_content => 'Bài nộp lần đầu',
          p_screenshot_url => '${fakeScreenshot6}',
          p_rating => 5,
          p_image_hash => '${fakeHashA}',
          p_client_submit_id => '${clientSubmitId6}'::uuid,
          p_mission_id => '${mission5}'::uuid
        ) as result
      `)).rows[0].result;
    });

    // Chủ app từ chối
    await db.exec(`
      update public.tester_reports
      set status = 'rejected',
          rejection_reason = 'Ảnh chụp không rõ',
          reviewed_at = now()
      where id = '${rep6.report_id}'
    `);

    // Tester nộp lại với ảnh mới
    const clientSubmitId7 = '99999999-9999-9999-9999-999999999999';
    const fakeScreenshot7 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${camp5}%2F${otherTesterId}%2F${clientSubmitId7}.jpg?alt=media&token=fixture`;

    let resubmitResult;
    await asUser(otherTesterId, async () => {
      resubmitResult = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp5}'::uuid,
          p_content => 'Nộp lại ảnh rõ hơn',
          p_screenshot_url => '${fakeScreenshot7}',
          p_rating => 5,
          p_image_hash => '${fakeHashB}',
          p_client_submit_id => '${clientSubmitId7}'::uuid,
          p_mission_id => '${mission5}'::uuid
        ) as result
      `)).rows[0].result;
    });

    assert.equal(resubmitResult.report_id, rep6.report_id, 'Phải cập nhật đúng report id hiện có');
    assert.equal(resubmitResult.status, 'pending');
    assert.equal(resubmitResult.submission_context, 'mission');
    assert.equal(resubmitResult.mission_id, mission5);
    console.log('PASS 14: Nộp lại sau khi bị reject giữ nguyên identity nhiệm vụ và chuyển về pending');

    // 15. [R01] get_campaign_detail đồng bộ metadata nhiệm vụ với receipt và owner view
    let detailReports;
    await asUser(otherTesterId, async () => {
      const detail = (await db.query(`select public.get_campaign_detail('${camp5}') as result`)).rows[0].result;
      detailReports = detail.reports;
    });
    const foundReport = detailReports.find(r => r.id === rep6.report_id);
    assert(foundReport, 'Báo cáo phải có trong detail.reports');
    assert.equal(foundReport.submission_context, 'mission', 'detail.reports phải trả submission_context = mission');
    assert.equal(foundReport.mission_id, mission5, 'detail.reports phải trả mission_id đúng');
    assert.equal(foundReport.mission_title, 'Mission 5 Late Join', 'detail.reports phải trả mission_title đúng');
    assert.equal(foundReport.mission_reward_credits, 30, 'detail.reports phải trả mission_reward_credits đúng');
    console.log('PASS 15 (R01): get_campaign_detail trả đủ metadata nhiệm vụ đồng bộ với receipt và owner view');

    // 16. [R03 Case A] Bổ sung nhận xét cho báo cáo nhiệm vụ đã approved khi participant completed
    // Duyệt báo cáo rep6
    await asUser(ownerId, async () => {
      await db.query(`select public.approve_tester_report('${rep6.report_id}', 0)`);
    });
    // Participant đã hoàn thành
    const partStatus = (await db.query(`
      select status from public.mission_participants where mission_id = '${mission5}' and user_id = '${otherTesterId}'
    `)).rows[0].status;
    assert.equal(partStatus, 'completed', 'Participant phải có status completed sau khi duyệt');

    // Tester gửi thêm comment
    const extraCommentId = 'aaaaaaaa-1111-2222-3333-444444444444';
    let editApprovedResult;
    await asUser(otherTesterId, async () => {
      editApprovedResult = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${camp5}'::uuid,
          p_content => 'Nhận xét bổ sung sau khi đã duyệt',
          p_client_submit_id => '${extraCommentId}'::uuid,
          p_mission_id => '${mission5}'::uuid
        ) as result
      `)).rows[0].result;
    });
    assert.equal(editApprovedResult.success, true);
    assert.equal(editApprovedResult.status, 'approved');
    assert.equal(editApprovedResult.submission_context, 'mission');
    assert.equal(editApprovedResult.provisional_granted, false);
    assert.equal(editApprovedResult.provisional_amount, 0);
    console.log('PASS 16 (R03 Case A): Cho phép sửa comment báo cáo approved khi participant completed, không phát sinh thưởng');

    // 17. [R04] Sửa bằng chứng báo cáo pending mang identity mission sau khi hết hạn không truyền p_mission_id bị chặn
    // Tạo campaign và mission mới cho test R04
    const ownerR04 = '11111111-1111-1111-1111-111111111114';
    const campR04 = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
    const missionR04 = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
    await db.exec(`
      insert into auth.users (id, email) values ('${ownerR04}', 'owner_r04@test.com');
      update public.profiles set credits = 1000 where id = '${ownerR04}';
      insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number)
      values ('${campR04}', '${ownerR04}', 'com.test.r04', 'App R04', 'open', 1);
      insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
      values ('${missionR04}', '${ownerR04}', '${campR04}', 'Mission R04', 5, 1, 20, 'open', now() + interval '1 hour');
      insert into public.mission_participants (mission_id, user_id, joined_at, status)
      values ('${missionR04}', '${testerId}', now() - interval '10 minutes', 'joined');
    `);

    // Tester nộp mission report khi còn hạn
    const clientSubmitR04 = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
    const fakePhotoR04 = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campR04}%2F${testerId}%2F${clientSubmitR04}.jpg?alt=media&token=fixture`;
    let repR04;
    await asUser(testerId, async () => {
      repR04 = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${campR04}'::uuid,
          p_content => 'Báo cáo trước khi hết hạn',
          p_screenshot_url => '${fakePhotoR04}',
          p_rating => 5,
          p_image_hash => '1234567890abcde1',
          p_client_submit_id => '${clientSubmitR04}'::uuid,
          p_mission_id => '${missionR04}'::uuid
        ) as result
      `)).rows[0].result;
    });
    assert.equal(repR04.submission_context, 'mission');

    // Làm mission hết hạn
    await db.exec(`
      update public.missions
      set ends_at = now() - interval '10 minutes', status = 'expired'
      where id = '${missionR04}'
    `);

    // Tester cố sửa ảnh/minh chứng nhưng KHÔNG truyền p_mission_id (đường implicit)
    let lateEditError = null;
    const lateSubmitId = 'ffffffff-ffff-ffff-ffff-ffffffffffff';
    const latePhoto = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campR04}%2F${testerId}%2F${lateSubmitId}.jpg?alt=media&token=fixture`;
    try {
      await asUser(testerId, async () => {
        await db.query(`
          select public.submit_tester_report(
            p_campaign_id => '${campR04}'::uuid,
            p_content => 'Cố tình sửa ảnh sau hạn không truyền mission_id',
            p_screenshot_url => '${latePhoto}',
            p_rating => 4,
            p_image_hash => '1234567890abcde2',
            p_client_submit_id => '${lateSubmitId}'::uuid
          ) as result
        `);
      });
    } catch (err) {
      lateEditError = err.message;
    }
    assert(lateEditError && lateEditError.includes('MISSION_EXPIRED'), `Phải bị chặn với MISSION_EXPIRED; nhận được: ${lateEditError}`);
    // Kiểm tra minh chứng cũ không bị thay đổi
    const storedEvidence = (await db.query(`
      select content, image_hash from public.tester_reports where id = '${repR04.report_id}'
    `)).rows[0];
    assert.equal(storedEvidence.content, 'Báo cáo trước khi hết hạn');
    assert.equal(storedEvidence.image_hash, '1234567890abcde1');
    console.log('PASS 17 (R04): Sửa minh chứng báo cáo pending nhiệm vụ sau khi hết hạn không truyền mission_id bị chặn với MISSION_EXPIRED, dữ liệu cũ giữ nguyên');

    // 18. [R03 Case B] Báo cáo test thường đã approved sửa comment khi mới join mission mới không bị biến thành mission
    const ownerReg = '11111111-1111-1111-1111-111111111115';
    const campRegular = '12121212-1212-1212-1212-121212121212';
    const missionNew = '34343434-3434-3434-3434-343434343434';
    await db.exec(`
      insert into auth.users (id, email) values ('${ownerReg}', 'owner_reg@test.com');
      update public.profiles set credits = 1000 where id = '${ownerReg}';
      insert into public.campaigns (id, owner_id, package_name, app_name, status, slot_number)
      values ('${campRegular}', '${ownerReg}', 'com.test.regular', 'App Regular', 'open', 1);
    `);
    // Tester nộp báo cáo test thường
    const clientRegular = '56565656-5656-5656-5656-565656565656';
    const photoRegular = `https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campRegular}%2F${testerId}%2F${clientRegular}.jpg?alt=media&token=fixture`;
    let repReg;
    await asUser(testerId, async () => {
      repReg = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${campRegular}'::uuid,
          p_content => 'Báo cáo thường ban đầu',
          p_screenshot_url => '${photoRegular}',
          p_rating => 5,
          p_image_hash => '1234567890abcde3',
          p_client_submit_id => '${clientRegular}'::uuid
        ) as result
      `)).rows[0].result;
    });
    assert.equal(repReg.submission_context, 'app_test');

    // Chủ app duyệt báo cáo thường
    await asUser(ownerReg, async () => {
      await db.query(`select public.approve_tester_report('${repReg.report_id}', 0)`);
    });

    // Chủ app tạo mission mới, tester join mission
    await db.exec(`
      insert into public.missions (id, creator_id, campaign_id, title, max_participants, duration_days, reward_credits, status, ends_at)
      values ('${missionNew}', '${ownerReg}', '${campRegular}', 'Mission Mới', 5, 2, 20, 'open', now() + interval '2 days');
      insert into public.mission_participants (mission_id, user_id, joined_at, status)
      values ('${missionNew}', '${testerId}', now(), 'joined');
    `);

    // Tester gửi thêm comment cho báo cáo test thường đã approved (không truyền p_mission_id)
    const extraRegSubmitId = '78787878-7878-7878-7878-787878787878';
    let editRegResult;
    await asUser(testerId, async () => {
      editRegResult = (await db.query(`
        select public.submit_tester_report(
          p_campaign_id => '${campRegular}'::uuid,
          p_content => 'Bổ sung nhận xét cho báo cáo thường',
          p_client_submit_id => '${extraRegSubmitId}'::uuid
        ) as result
      `)).rows[0].result;
    });
    assert.equal(editRegResult.success, true);
    assert.equal(editRegResult.status, 'approved');
    assert.equal(editRegResult.submission_context, 'app_test');
    assert.equal(editRegResult.mission_id, null);
    assert.equal(editRegResult.provisional_granted, false);
    console.log('PASS 18 (R03 Case B): Sửa comment báo cáo test thường đã approved khi đã join mission giữ nguyên context app_test, không bị tự động gán mission');

    console.log('=== TẤT CẢ 18 KIỂM THỬ BACKEND MIGRATION 048 & 049 ĐỀU PASS 100% ===');
  } finally {
    await db.close();
  }
}

runTests().catch(err => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});
