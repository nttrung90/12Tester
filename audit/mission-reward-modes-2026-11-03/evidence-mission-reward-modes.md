# Audit & Verification Evidence: Hai chế độ thưởng, sửa nhiệm vụ và quyền xóa của Admin

**Ngày:** 03/11/2026  
**Mô hình thực hiện:** `google/antigravity-gemini-3.8-flash --variant high`  
**Phiên làm việc:** CLI OpenCode `ses_e5ed102c1ffediwFVUjt6t0y8L`  
**Thư mục làm việc:** `F:/AI/12Tester`  
**Tài liệu kế hoạch:** `audit/mission-reward-modes-2026-11-03/KE_HOACH.md`  

---

## 1. Tóm tắt nội dung triển khai (Summary of Implementation)

### A. Cơ sở dữ liệu và RPCs Backend (`077_mission_reward_modes_and_management.sql`)
- **Tập tin migration:** `supabase/migrations/077_mission_reward_modes_and_management.sql`  
  - **SHA256:** `44A9D69467C3B190B860E367AD8F041B739EEDAB34A1B8E7D3A0BF1B328FE7F0`
- **Mở rộng lược đồ (`public.missions`, `public.tester_reports`, `public.mission_settlement_jobs`)**:
  - `public.missions`: Bổ sung `reward_mode text DEFAULT 'legacy'` (ràng buộc `CHECK (reward_mode IN ('legacy', 'once', 'daily'))`), `escrow_total_credits`, `escrow_remaining_credits`, `escrow_refunded`, `max_fee_duration_days`, `tester_announcement`, `version`, `admin_closed_at`.
  - `public.tester_reports`: Bổ sung `mission_reward_mode text`. Tạo partial unique index `idx_tester_reports_once_mission` trên `(user_id, campaign_id, mission_id)` khi `mission_reward_mode = 'once'`.
  - `public.mission_settlement_jobs`: Bổ sung `report_date date`. Tạo partial unique index `idx_settlement_jobs_daily_date_completed` trên `(mission_id, participant_id, report_date)` khi `status = 'completed'`.
  - Trigger `trg_campaigns_guard_mission_escrow_before_delete`: Chặn xóa campaign nếu còn nhiệm vụ chưa hoàn quỹ (`escrow_remaining_credits > 0 AND NOT escrow_refunded`).
- **RPCs tạo nhiệm vụ (`create_mission` & `create_mission_v2`)**:
  - `create_mission`: Giữ nguyên tương thích chữ ký cũ cho client và bài test cũ; gán `reward_mode = 'legacy'`, escrow = 0, thu phí 50 Credit × số ngày.
  - `create_mission_v2`: Hỗ trợ rõ ràng `p_reward_mode` (`once` hoặc `daily`). Tính quỹ thưởng giữ trước nguyên tử:
    - Một lần (`once`): `max_participants × reward_credits`. Hạn chót `ends_at := now() + interval`.
    - Hàng ngày (`daily`): `max_participants × duration_days × reward_credits`. Hạn chót `ends_at := 00:00:00` đầu ngày sau cửa sổ N ngày theo giờ Việt Nam (`Asia/Ho_Chi_Minh`).
    - Thu phí nền tảng 50 Credit × ngày và giữ quỹ nguyên tử. Ghi riêng `mission_fee` và `mission_escrow`.
- **RPCs sửa nhiệm vụ (`preview_edit_mission` & `edit_mission`)**:
  - Trước khi có người nhận (`participant_count = 0`): cho phép sửa `title`, `description`, `reward_mode`, `max_participants`, `duration_days`, `reward_credits`. Tính lại hạn chót từ `created_at` gốc; từ chối nếu hạn mới đã qua.
  - Phí theo số ngày đã thu là mốc cao nhất: tăng thu phần chênh, giảm không hoàn phí đã thu. Quỹ thưởng chênh lệch được giữ thêm hoặc hoàn lại nguyên tử. Ghi đủ cả hai chân nhật ký (`mission_fee` và `mission_refund`/`mission_escrow`), số dư nối tiếp chính xác.
  - Bắt buộc kiểm tra `p_expected_version` và `p_expected_cost_diff` khi sửa điều khoản tài chính để chống ghi đè phiên bản và giá trị xem trước lỗi thời.
  - Sau khi có người nhận (`participant_count > 0`): khóa điều khoản thưởng, thời hạn, số chỗ. Chỉ cho sửa `title`, `description`, `tester_announcement`. Gửi thông báo đến toàn bộ tester đã tham gia khi có cập nhật.
- **RPCs xóa nhiệm vụ (`admin_delete_mission` & `delete_mission`)**:
  - `admin_delete_mission`: Chỉ Quản trị viên chưa bị khóa (`assert_unlocked_admin()`). Bắt buộc lý do xóa `p_reason`. Ghi audit `record_admin_action_event`, thông báo chủ app và các tester đã nhận. Đặt `status = 'deleted'`, `admin_closed_at = now()`. Idempotent replay không phát trùng audit/thông báo/hoàn quỹ.
  - `delete_mission`: Cập nhật luồng xóa của chủ app, soft-delete, thông báo người tham gia và gọi kiểm tra hoàn quỹ.
- **Hoàn quỹ tự động (`_check_and_refund_mission_escrow`)**:
  - Khi nhiệm vụ đã kết thúc (`ends_at <= now() OR status IN ('expired', 'deleted')`), nếu không còn báo cáo pending và không còn settlement jobs pending/failed -> hoàn toàn bộ `escrow_remaining_credits` cho chủ app, ghi `credit_logs` (`mission_refund`), ghi `mission_reconciliation_events`, thông báo chủ app, và đặt `escrow_refunded = true, escrow_remaining_credits = 0`.
- **Cơ chế nộp và quyết toán (`_submit_tester_report_core` & `_settle_mission_reward`)**:
  - `_submit_tester_report_core`:
    - Replay receipt đã cam kết thực hiện đầu tiên trước mọi chặn.
    - Tìm và khóa báo cáo cũ trước theo thứ tự tương thích (`tester_reports` -> `missions`), sau đó khóa hàng `missions` bằng `FOR UPDATE` trước khi thực hiện ghi/sửa, loại trừ hoàn toàn race condition với đóng/xóa/sửa/hoàn quỹ.
    - Chế độ `once`: tìm báo cáo trên mọi ngày; cho phép sửa comment báo cáo approved hoặc nộp lại báo cáo rejected trên cùng bản ghi; qua nửa đêm vẫn nộp tiếp được; nộp hàng đợi qua nửa đêm được chấp nhận nếu nhiệm vụ còn hạn.
    - Chế độ `daily`: tìm báo cáo theo ngày máy chủ (`v_today`), không nộp bù ngày chưa gửi, sửa/nộp lại trong ngày.
  - `_settle_mission_reward`:
    - Chi trả cho nhiệm vụ mới (`once`, `daily`) lấy trực tiếp từ quỹ đã giữ `escrow_remaining_credits`, không trừ lại tài khoản chủ app.
    - Báo cáo hợp lệ đã nộp giữ quyền được xét thưởng khi quá hạn hoặc bị xóa.
    - Nhiệm vụ `daily`: duyệt ngày đầu không khóa ngày sau (không đặt cờ `completed` cho participant).
    - Nhiệm vụ `once`: trả thưởng 1 lần và hoàn thành participant.
    - Ràng buộc chống trả trùng cho `once` (theo participant) và `daily` (theo participant + report_date).
    - Nhiệm vụ `legacy`: bảo lưu quy tắc chi trả cũ; ngoại lệ cho phép duyệt các báo cáo nộp trước thời điểm Admin đóng. Fallback báo cáo không có `mission_id` chỉ tìm nhiệm vụ legacy còn hạn, không trừ nhầm quỹ nhiệm vụ mới.
  - `_impl_reject_tester_report`:
    - Khôi phục nguyên văn cơ chế hiệu lực từ 074 (kiểm tra quyền moderator/admin, hạn 8h, cảnh báo tester/owner, hoàn tác checkin). Bổ sung lời gọi kiểm tra hoàn quỹ ở cuối nhánh thành công.

---

### B. Ứng dụng Android (Client Layer)
1. **Dữ liệu & Mô hình (`Models.kt`, `CampaignRepository.kt`)**:
   - `Mission`: Bổ sung `rewardMode`, `durationDays`, `feeCredits`, `escrowTotalCredits`, `escrowRemainingCredits`, `testerAnnouncement`, `version`, `submittedDaysCount`, `todaySubmitted`, `todayReportStatus`, `reportStatus`.
   - `TesterReport`: Bổ sung `missionRewardMode`, `isOnceMission`, `isDailyMission`.
   - Thêm các params/results: `CreateMissionV2Params`, `CreateMissionV2Result`, `PreviewEditMissionParams`, `PreviewEditMissionResult`, `EditMissionParams`, `EditMissionResult`, `AdminDeleteMissionParams`, `AdminDeleteMissionResult`.
   - `CampaignRepository`: Bổ sung `createMissionV2`, `previewEditMission`, `editMission`, `adminDeleteMission`, `getMyMissionReport(campaignId, missionId, isOnceMode, reportDate)`.
2. **Quản lý hàng đợi & bản nháp (`ReportSubmitManager.kt`, `PendingReportStore.kt`, `MissionReportDraftStore.kt`)**:
   - `PendingReport`: Bổ sung `isOnceMode` và `scopedKey`, tính toán chính xác dung lượng metadata JSON vào `canAdmit`.
   - `ReportSubmitManager`: Chuẩn hóa `effectiveReportDate`: đối với nhiệm vụ `once`, khóa phạm vi hàng đợi là `"$userId:$campaignId:mission:$missionId:once"` ổn định xuyên suốt trước khi nộp, trong hàng đợi, qua nửa đêm, và sau khi tiến trình khởi động lại.
   - `MissionReportDraftStore`: Lưu và nạp bản nháp nhiệm vụ `once` dưới định danh ngày `"once"`, bảo toàn nguyên vẹn bản nháp và ảnh qua nửa đêm.
3. **Màn hình Mở nhiệm vụ (`MissionCreateViewModel.kt`, `MissionCreateScreen.kt`)**:
   - Lựa chọn đơn: **Trả thưởng một lần** (mặc định) hoặc **Trả thưởng hàng ngày**.
   - Hiển thị đơn vị: Credit/lượt (`once`) hoặc Credit/ngày (`daily`).
   - Thẻ tổng kết: Phí nền tảng, Quỹ thưởng tối đa, Tổng Credit cần có, Thời điểm bắt đầu và Thời điểm hết hạn thực tế (00:00 đầu ngày sau đối với daily).
   - Kiểm tra số dư >= 300 và >= tổng Credit cần có. Gọi `createMissionV2`.
4. **Màn hình Quản lý của Chủ app (`OwnerAppDetailViewModel.kt`, `OwnerAppDetailScreen.kt`)**:
   - Dialog Quản lý nhiệm vụ: hiển thị badge chế độ, đơn vị thưởng, số dư quỹ thưởng còn lại, thông báo tester.
   - Bổ sung nút **Sửa nhiệm vụ**: mở dialog chỉnh sửa điền sẵn.
   - Trước khi có người nhận: cho phép sửa chế độ, số chỗ, số ngày, mức thưởng; hiển thị chênh lệch phí và quỹ thưởng theo thời gian thực; gọi `editMission`.
   - Sau khi có người nhận: khóa các điều khoản thưởng; cho phép sửa tiêu đề, mô tả và nhập thông báo tester.
5. **Màn hình Chi tiết Chiến dịch & Quản trị (`CampaignDetailViewModel.kt`, `CampaignDetailScreen.kt`)**:
   - Tuyến đường bình thường từ `AdminCampaignsScreen` không có `missionId`: nạp độc lập `adminManageableMission` cho Admin mà không làm biến đổi ngữ cảnh nộp báo cáo thường của tester.
   - Khung Quản trị viên: hiển thị nút **Xóa nhiệm vụ (Admin)** khi app có nhiệm vụ. Hộp thoại yêu cầu nhập lý do bắt buộc và ghim `missionId` cụ thể khi gửi yêu cầu.
6. **Màn hình Danh sách & Báo cáo Nhiệm vụ (`QuestsScreen.kt`, `MissionReportScreen.kt`, `MissionReportViewModel.kt`)**:
   - `QuestsScreen`: Hiển thị badge chế độ ("Một lần" / "Hàng ngày"), đơn vị thưởng tương ứng, và tiến độ nộp hàng ngày ("Đã nộp: X/Y ngày • Hôm nay: Đã nộp/Chưa nộp").
   - `MissionReportViewModel`: Đăng ký lắng nghe chính xác phạm vi `(account, campaign, mission, "once")` cho once và `(account, campaign, mission, boundDate)` cho daily. Tái liên kết khi chế độ nhiệm vụ được giải quyết bất đồng bộ; không lặp vòng khi nhận trạng thái `DONE`. Qua nửa đêm đối với `once` chỉ cập nhật ngày metadata mà không xóa ảnh, nháp hoặc timer.

---

## 2. Bằng chứng kiểm thử (Verification Evidence)

### A. Kiểm thử PGlite Backend (15/15 Ca Kiểm Thử Đạt 100%)
Lệnh thực thi: `node supabase/tests/run-mission-reward-modes-test.mjs`

```text
=== RUNNING MISSION REWARD MODES & MANAGEMENT TESTS (MIGRATION 077) ===
Loading 16 migrations from 062 to 077...

--- Test 1: Insufficient Credits & Balance Guards ---
PASS: Mission creation rejected when total cost exceeds balance

--- Test 2: Once Mode Across Multiple Days ---
PASS: Owner created once-mode mission; 150 fee + 100 escrow held upfront atomically
PASS: Tester 1 joined once-mode mission
PASS: Tester 1 submitted once-mode report on Day 1
PASS: Exact idempotent replay on once-mode returns committed receipt
PASS: Day 2 once-mode submission safely updated pending report on same record across days
PASS: Verified strictly 1 report in DB for once-mode participant across multiple days
PASS: Ordinary app_test report submitted on same day coexists independently without collision
PASS: Once-mode reward paid from escrow; owner balance unaffected; participant completed

--- Test 3: Daily Mode for 3 Days Flow ---
PASS: Daily mission created for production campaign; 150 fee + 90 escrow held
PASS: Tester 1 joined daily mission
PASS: Tester 1 submitted Day 1 daily report
PASS: Server rejects retroactive submission for missed Day 2
PASS: Tester 1 submitted Day 3 daily report
PASS: Day 3 approved FIRST
PASS: Day 1 approved SECOND (out-of-order review succeeded)
PASS: Daily mode out-of-order approval paid both days independently from escrow

--- Test 4: Rejection and Resubmission Flow ---
PASS: Owner rejected report with reason
PASS: Server blocked resubmission with duplicate old photo
PASS: Resubmission with new photo succeeded on same report record

--- Test 5: Edit Mission Flow ---
PASS: preview_edit_mission accurately calculated fee and escrow differences
PASS: edit_mission rejected stale preview cost
PASS: edit_mission rejected concurrent version mismatch
PASS: edit_mission updated terms, deducted 650 credits, and bumped version
PASS: edit_mission locked reward terms after first participant joined
PASS: Owner edited title, description and announcement after join
PASS: Tester received notification of updated mission announcement

--- Test 6: Admin Delete Mission Flow ---
PASS: Moderator rejected with PERMISSION_DENIED
PASS: Locked Admin rejected with PERMISSION_DENIED
PASS: Empty deletion reason rejected
PASS: Admin deleted mission successfully with audit event
PASS: Repeated admin_delete_mission returned idempotent replay
PASS: Admin action audit logged with actor, reason, and target details
PASS: Notifications sent to owner and joined tester
PASS: Escrow held safely while pending report remains to be reviewed
PASS: Report submitted before deletion was successfully approved and rewarded
PASS: Unused escrow of 585 credits refunded to owner atomically after final settlement

--- Test 7: Approval After Expiration & sync_expired_missions Sweep ---
PASS: sync_expired_missions executed (updated: 1)
PASS: Report submitted in time was approved and rewarded AFTER mission expiration
PASS: Expired mission unused escrow refunded to owner after last report settled

--- Test 8: Discovery & Metadata Verification ---
PASS: get_open_missions and get_campaign_mission return all required metadata

=== ALL 15 MISSION REWARD MODES & MANAGEMENT TEST CASES PASSED 100% ===
```

### B. Kiểm thử Probe Độc Lập (Independent Probes)
1. **Probe Edit & Admin Closure (`audit/mission-reward-modes-2026-11-03/probe-edit-admin.mjs`)**:
   - `PASS mixed edit ledger reconciles fee increase and escrow refund`
   - `PASS stale repeated edit cannot debit twice`
   - `PASS Admin closure authorizes, audits and refunds once`
   - `PASS app deletion blocks while mission escrow is held`
2. **Probe Legacy Policy & Receipt Rating (`audit/mission-reward-modes-2026-11-03/probe-report-legacy.mjs`)**:
   - `PASS pending update receipt matches saved rating`
   - `PASS legacy natural expiry preserves old payout policy`
3. **Các Probe Hồi Quy Độc Lập Trước (`audit/app-mission-report-2026-11-02/`)**:
   - `root-three-image-probe.mjs`: **13/13 PASSED**
   - `root-daily-coexistence-probe.mjs`: **2/2 PASSED**
   - `root-review-isolation-probe.mjs`: **5/5 PASSED**

### C. Biên Dịch và Kiểm Thử Android (Android Compilation & Unit Tests)
1. **Biên dịch mã nguồn Android**:
   - Lệnh: `powershell -ExecutionPolicy Bypass -File audit/mission-reward-modes-2026-11-03/run-gradle-logged.ps1 -LogName compile-check -GradleArguments ':app:compileDebugKotlin'`
   - Kết quả: **BUILD SUCCESSFUL in 1m 1s** (0 lỗi, log: `compile-check.log`).
   - Phiên bản: **1.1.2 / build 32** được giữ nguyên hoàn toàn.
2. **Bộ kiểm thử đơn vị chế độ thưởng (`MissionRewardModesUnitTest`)**:
   - Lệnh: `& "audit\mission-reward-modes-2026-11-03\run-gradle-logged.ps1" -LogName "test-modes-unit-pass2" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest", "--tests", "com.ntt.crosstest.data.MissionRewardModesUnitTest")`
   - Kết quả: **7/7 PASSED (0 failures, 0 errors, 0 skipped)**. Snapshot: `test-modes-unit-pass2-20261103-131905-676`.
   - Bao gồm:
     - `testModelSerialization`: Khớp tuần tự hóa params và results mới.
     - `testDraftStoreOnceModeDurableDate`: Lưu và nạp bản nháp once bền vững qua các ngày.
     - `testTesterReportRewardModeProperties`: Phân biệt chính xác context và mode.
     - `testMissionRewardModeProperties`: Kiểm tra các thuộc tính logic model Mission.
     - `testOnceModeQueueAndMidnightRolloverStability`: Khóa hàng đợi ổn định qua nửa đêm và nạp lại từ bộ nhớ đĩa.
     - `testMissionCreateUiStateBudgetCalculations`: Tính toán chính xác phí, quỹ thưởng và tổng chi phí cho cả hai chế độ.
     - `testAdminNormalRouteLoadsManageableMission`: Tuyến đường Admin không truyền missionId vẫn tải được nhiệm vụ để xóa và ghim missionId trong RPC.
3. **Bộ kiểm thử đổi ngày độc lập (`MissionWindowDayRolloverIndependentTest`)**:
   - Lệnh: `& "audit\mission-reward-modes-2026-11-03\run-gradle-logged.ps1" -LogName "indep-day-rollover" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest", "--tests", "com.ntt.crosstest.audit.MissionWindowDayRolloverIndependentTest")`
   - Kết quả: **1/1 PASSED (0 failures, 0 errors, 0 skipped)**. Snapshot: `indep-day-rollover-20261103-131511-179`.
   - Xác nhận khẳng định tích cực ngày hôm sau: báo cáo hôm nay hiển thị đúng ngay cả khi lưu giữ scope ngày hôm qua.
4. **Bộ kiểm thử tích hợp toàn bộ các lớp nhiệm vụ và độc lập**:
   - Lệnh: `& "audit\mission-reward-modes-2026-11-03\run-gradle-logged.ps1" -LogName "test-all-mission-suites" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest", "--tests", "com.ntt.crosstest.audit.MissionWindowDayRolloverIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowDraftIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowManagerIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowMediaIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowPersistenceIndependentTest", "--tests", "com.ntt.crosstest.work.MissionWindowWorkerIndependentTest", "--tests", "com.ntt.crosstest.data.MissionRewardModesUnitTest", "--tests", "com.ntt.crosstest.data.MissionRegressionTest", "--tests", "com.ntt.crosstest.data.MissionReportMultiAttachmentTest", "--tests", "com.ntt.crosstest.data.MissionReportAndExpirationTest", "--tests", "com.ntt.crosstest.data.MissionReportRecheckProbeTest")`
   - Kết quả: **11/11 lớp kiểm thử PASSED (0 failures, 0 errors, 0 skipped)**. Snapshot: `test-all-mission-suites-20261103-131951-476`.
5. **Bộ kiểm thử tổng hợp toàn bộ Android (`:app:testDebugUnitTest`)**:
   - Lệnh: `& "audit\mission-reward-modes-2026-11-03\run-gradle-logged.ps1" -LogName "full-android-all-tests" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest")`
   - Kết quả: **BUILD SUCCESSFUL in 46s, 79 XML test suites PASSED, 0 failures, 0 errors, 0 skipped**. Snapshot: `full-android-all-tests-20261103-132122-617`.

---

## 3. Giới hạn thực tế còn lại (Remaining Material Limits)

1. **Môi trường Database cục bộ**:
   - Tất cả các kiểm thử SQL/RPC được thực thi trên `@electric-sql/pglite` (WebAssembly đơn tiến trình cục bộ). Khóa `FOR UPDATE` và `pg_advisory_xact_lock` đã được xác minh đúng cú pháp, thứ tự khóa và nghiệp vụ. Kiểm chứng race condition đa luồng đồng thời thực tế cần thực hiện trên môi trường PostgreSQL kết nối mạng đầy đủ.
2. **Môi trường Android**:
   - Tuân thủ nghiêm ngặt chỉ thị không tạo gói APK/AAB, không deploy hoặc commit/push; tất cả các kiểm thử Android được thực thi hoàn toàn trong môi trường Robolectric JVM thông qua gradle task `:app:testDebugUnitTest`.

## Xác minh cuối của coordinator

- Đọc trực tiếp snapshot full-android-all-tests-20261103-132122-617. Tổng 567 kiểm thử trong 79 bộ, 0 lỗi, 0 thất bại, 0 bỏ qua. Exit 0 và lệnh test không lọc lớp.
- SHA-256 migration 077 là 44A9D69467C3B190B860E367AD8F041B739EEDAB34A1B8E7D3A0BF1B328FE7F0. Hash khớp bản đã rà soát khóa giao dịch và chạy lại hồi quy SQL.
- Metadata phiên ses_e5ed102c1ffediwFVUjt6t0y8L cho thấy cả 424 lượt assistant dùng google/antigravity-gemini-3.8-flash/high. Tiến trình triển khai đã kết thúc exit 0.
- Đối chiếu SHA-256 của 17 tệp bảo vệ gồm migration 062–076, cấu hình phiên bản và APK có sẵn. Tất cả khớp bản chụp trước khi triển khai.
- Kiểm chứng cục bộ bằng PGlite và Robolectric. Việc kiểm tra race trên PostgreSQL đa kết nối và thử trên thiết bị thật nằm ngoài kết quả này.
