# Bàn Giao Triển Khai Sửa Lỗi Cộng Dồn Nhiệm Vụ (Carryover Fix Handover) — 05/11/2026

## 1. Danh sách tệp thay đổi và tệp mới

### Backend & Cơ sở dữ liệu (Supabase)
- **Tạo mới**: `supabase/migrations/080_report_context_carryover_and_gap_recovery.sql`
  - Migration kế thừa sau 079 (giữ nguyên vẹn tuyệt đối migrations 062–079).
  - Tách bạch báo cáo nhiệm vụ (`mission`) khỏi báo cáo thường (`app_test`) trong `daily_test_events_journal` và trigger.
  - Phân loại đối soát dữ liệu cũ:
    - Báo cáo `mission` -> `is_qualifying = false`, `reconciled_mission`.
    - Báo cáo `app_test` -> `is_qualifying = true` (nếu pending/approved), `verified_app_test`.
    - Báo cáo ngữ cảnh không xác định (`NULL` hoặc không rõ) -> `is_qualifying = false`, `unresolved_unknown_context`.
    - Báo cáo mất nguồn -> `is_qualifying = false`, `unresolved_missing_source`.
    - Checkin thường: giữ nguyên tính hợp lệ đối với ghi chú tự do; không làm phương hại checkin thực tế.
  - Snapshot lịch sử bất biến (R01): khôi phục kiểm tra `p_date < v_today` trong `get_daily_task_status`, trả về bản ghi snapshot từ `user_daily_tasks` đối với ngày đã chốt, ngăn chặn sự kiện muộn ghi đè quá khứ.
  - Bảo mật tài chính & phân quyền (R02 & R03):
    - Thu hồi quyền thực thi `apply_daily_inactivity_penalty`, `finalize_daily_tasks_for_date`, và `_apply_daily_inactivity_penalty` khỏi `PUBLIC`, `anon`, `authenticated`, chỉ cấp cho `service_role`. Đặt guard chặn client `authenticated` trong thân hàm.
    - Bảo vệ các hàm chẩn đoán `diagnose_daily_task_stuck_users()` và `audit_carryover_journal_reconciliation()`: bắt buộc xác thực danh tính (`auth.uid() IS NOT NULL`) và kiểm tra quyền admin không bị khóa (`assert_unlocked_admin()`).
  - Phục hồi khoảng thiếu dài lũy tiến và bảo vệ số dư trước checkpoint (C03, R05, R08):
    - Bổ sung hàm quyết toán nội bộ có chân trời kiểm tra: `_apply_daily_inactivity_penalty(p_eval date, p_horizon_scope date)`. Nếu `p_eval < v_checkpoint_boundary` (ranh giới chốt gần nhất tại/trước mốc chân trời), hàm lập tức bỏ qua, không tạo dòng và không phạt trừ tiền trước checkpoint.
    - Cung cấp entry-point tương thích `_apply_daily_inactivity_penalty(p_eval date)` gọi `_apply(p_eval, p_eval)` để bảo toàn các bài kiểm thử backfill mục tiêu cũ.
    - Truy vấn ứng viên trong `apply_daily_inactivity_penalty` và `finalize_daily_tasks_for_date`: loại bỏ ngày nằm trước checkpoint (`cp.task_date <= target and cp.task_date > d`), đồng thời loại trừ ngày có sự kiện chưa đối soát (`unresolved_missing_source`, `unresolved_unknown_context`), giúp tài khoản bình thường tiến dần đến ngày hôm qua mà không bị kẹt vĩnh viễn và không bị phạt oan trước mốc chốt.
- **Tạo mới**: `supabase/tests/run-carryover-fix-acceptance-test.mjs`
  - Bộ kiểm thử nghiệm thu toàn diện độc lập cho C01, C03 và các kịch bản đối soát, idempotence, phục hồi khoảng thiếu 3 ngày (khẳng định số dư chính xác 9726, intended debit 274, pre-anchor rows = 0), cross-user date processing không xâm phạm checkpoint người khác, 31 ngày, 40 ngày, thử gọi thật `submit_tester_report_v3` và `resume_campaign`.
- **Cập nhật**: `supabase/tests/run-push-and-carryover-baseline062-regression-test.mjs`
  - Sửa C04: tách kiểm tra re-run idempotency của 066 vào cơ sở dữ liệu cô lập `isoDb`, đảm bảo `db` nạp tuần tự 062–080 và hàm getter/finalizer luôn là phiên bản mới nhất.
- **Cập nhật**: `supabase/tests/package.json`
  - Đăng ký script `"test:carryover-acceptance": "node run-carryover-fix-acceptance-test.mjs"` và đưa vào chuỗi kiểm thử mặc định `"test"`.

### Frontend & Ứng dụng di động (Android)
- **Cập nhật**: `app/src/main/java/com/ntt/crosstest/data/Models.kt`
  - Cập nhật `DailyTaskStatus`:
    - `isPendingCarryover: Boolean get() = isOwner && (!carryoverReady || !historyReady)` (giữ riêng cho owner, đảm bảo member login độc lập với chốt nợ theo R06).
    - `isCarryoverHistoryPending: Boolean get() = (!carryoverReady || !historyReady)` (thuộc tính dùng chung kiểm tra tính sẵn sàng của lịch sử nợ).
    - `hasRemainingCarryover: Boolean get() = carryoverReady && historyReady && carryoverRemainingTests > 0`.
- **Cập nhật**: `app/src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt`
  - Nhánh thẻ gọn (`isAuthenticated && met && !exempt && !waived`):
    - Đặt tiêu đề hoàn thành ở dòng 1 và số nợ bảo lưu (`daily_task_preserved_debt_notice`) trên dòng riêng ở dòng 2 bằng cấu trúc `Column`, màu hổ phách đậm, không bị cắt cụt bởi ellipsis ở màn hình hẹp hay cỡ chữ lớn (R07).
    - Khi lịch sử chưa sẵn sàng (`isHistoryPending`), hiển thị thông báo đang cập nhật trên dòng 2 (`daily_task_carryover_updating_notice`), không bao giờ nhận định là 0 nợ.
  - Nhánh thành viên (`memberMode`):
    - Thành viên đăng nhập được ghi nhận hoàn thành mục tiêu ngày (`met = true`) độc lập với trạng thái chốt nợ hôm qua (R06).
    - Giải thích rõ ràng nợ bảo lưu cũ và hướng dẫn test tự nguyện, hoặc thông báo nợ đang cập nhật nếu hôm qua chưa chốt.
- **Cập nhật tài nguyên chuỗi đa ngôn ngữ**:
  - `app/src/main/res/values/strings.xml` (Tiếng Anh - mặc định)
  - `app/src/main/res/values-en/strings.xml` (Tiếng Anh)
  - `app/src/main/res/values-vi/strings.xml` (Tiếng Việt)
  - Bổ sung:
    - `daily_task_completed_with_carryover`
    - `daily_login_completed_with_carryover`
    - `daily_member_goal_with_carryover`
    - `daily_task_preserved_debt_notice`
    - `daily_task_carryover_updating_notice`
- **Cập nhật kiểm thử Unit Test Android**:
  - `app/src/test/java/com/ntt/crosstest/data/DailyTaskCarryoverUnitTest.kt`: kiểm thử member login `met=true` độc lập với nợ chưa ready, owner đạt chỉ tiêu nhưng còn nợ, kiểm tra tĩnh hợp đồng UI thẻ gọn đảm bảo hiển thị `daily_task_preserved_debt_notice`.

---

## 2. Các quyết định thiết kế chính (Design Decisions)

1. **Bảo vệ ranh giới Checkpoint và Tính đúng số dư Tài chính (R08)**:
   - Checkpoint đã chốt trong `user_daily_tasks` là một mốc bất biến đại diện cho toàn bộ lịch sử trước đó đã được giải quyết xong.
   - Khi tiến hành chốt bù tự động cho nhiều người dùng hoặc khi chạy bù một chuỗi dài, hàm quyết toán `_apply_daily_inactivity_penalty` tiếp nhận chân trời đích (`v_scope`). Đối với từng người dùng, ranh giới checkpoint `v_checkpoint_boundary` được xác định là ngày chốt lớn nhất tại hoặc trước `v_scope`.
   - Nếu ngày đang duyệt `p_eval < v_checkpoint_boundary`, người dùng đó tuyệt đối không bị xử lý lại: không chèn dòng creation-day, không tính chỉ tiêu, không trừ phạt Credit.
   - Truy vấn tìm kiếm công việc loại trừ các ngày trước checkpoint, bảo đảm không lãng phí chu kỳ xử lý và không phát sinh lỗi tài chính.
   - Nhờ đó, tài khoản có số dư 10000 chỉ bị trừ đúng 274 Credit của 3 ngày phát sinh sau checkpoint, số dư cuối cùng là 9726, không có bất kỳ dòng rác nào phát sinh trước checkpoint (`preAnchorRows = 0`).

2. **Tách biệt ngữ cảnh báo cáo & đối soát dữ liệu (C01 & R04)**:
   - Trong `tester_reports`, cột `submission_context` đã phân loại rõ `'app_test'` và `'mission'`.
   - Tại trigger `trg_journal_report_event()`, trường `is_qualifying` chỉ được đặt thành `true` khi `submission_context = 'app_test'` và trạng thái là `pending` hoặc `approved`.
   - Đối với `submission_context = 'mission'`, `is_qualifying` luôn bằng `false` và được lưu vết `verified_mission`.
   - Báo cáo thiếu ngữ cảnh hoặc NULL được đánh dấu `unresolved_unknown_context` với `is_qualifying = false`.
   - Báo cáo nhiệm vụ không tham gia vào chỉ tiêu test thường hôm nay (`completed_tests` / `v_d`), không làm giảm nợ chuyển tiếp (`carryover_remaining_tests`), và RPC `resume_campaign` sẽ chặn với lỗi `OWNER_CARRYOVER_INCOMPLETE` nếu chủ app còn nợ test thường mà chỉ nộp báo cáo mission.

3. **Snapshot lịch sử bất biến cho ngày đã chốt (R01)**:
   - Trong `get_daily_task_status`, nếu `p_date < v_today` và dòng `user_daily_tasks` tương ứng đã có `finalized_at IS NOT NULL`, hệ thống trả về ngay bản ghi snapshot lịch sử bất biến.
   - Các sự kiện muộn hoặc thay đổi sau ngày chốt không làm thay đổi các chỉ số lịch sử đã được quyết toán.

4. **Phân quyền và bảo mật tài chính (R02 & R03)**:
   - Thu hồi quyền gọi các hàm quyết toán tài chính `apply_daily_inactivity_penalty`, `finalize_daily_tasks_for_date`, và `_apply_daily_inactivity_penalty` khỏi client `authenticated`, bảo vệ các cài đặt đặc quyền (như `test.allow_future_finalize`) khỏi nguy cơ client giả mạo.
   - Khóa các hàm chẩn đoán nội bộ `diagnose_daily_task_stuck_users` và `audit_carryover_journal_reconciliation`: yêu cầu có `auth.uid()` hợp lệ và người gọi phải là admin không bị khóa thông qua `assert_unlocked_admin()`.

5. **Phục hồi khoảng thiếu dài lũy tiến không gây đói (C03 & R05)**:
   - Tìm ngày thiếu đầu tiên sau checkpoint đáng tin cậy của từng người dùng (`max(task_date) + 1`).
   - Loại trừ người dùng đang bị nghẽn bởi bản ghi chưa đối soát (`unresolved`) hoặc thiếu mốc tiền nhiệm/coverage khỏi việc kéo lùi mốc thời gian của toàn hệ thống.
   - Tài khoản bình thường tiếp tục được chốt ngày lũy tiến đến hôm qua (khoảng 39 ngày chốt xong trong 2 đợt batch 31 ngày), trong khi người dùng có lịch sử chưa rõ ràng vẫn giữ nguyên trạng thái đang chờ để quản trị viên kiểm tra qua hàm chẩn đoán.

6. **Hiển thị nợ bảo lưu rõ ràng trên giao diện (C02, R06, R07)**:
   - Thành viên đăng nhập được ghi nhận hoàn thành mục tiêu ngày (`met = true`) độc lập với trạng thái chốt nợ hôm qua (R06).
   - Thẻ hoàn thành gọn (compact card) hiển thị tiêu đề hoàn thành ở dòng 1 và số nợ bảo lưu trên dòng 2 riêng biệt, không có ellipsis cắt chữ, bảo đảm số nợ luôn hiển thị rõ ràng trên màn hình nhỏ hoặc cỡ chữ lớn (R07).
   - Trạng thái chưa sẵn sàng (`isCarryoverHistoryPending`): hiển thị `"—"` và badge "Đang cập nhật chỉ tiêu", không hiển thị số 0 để tránh người dùng hiểu nhầm là đã hết nợ.

7. **Bộ nạp kiểm thử và kiểm chứng hợp đồng (C04)**:
   - Kiểm tra idempotent của 066 chạy trong môi trường cô lập, nạp tuần tự 062 đến 080.
   - Kiểm tra trực tiếp định nghĩa PostgreSQL Function (`pg_get_functiondef`) đảm bảo getter và finalizer hoạt động là phiên bản 080 mới nhất.

---

## 3. Bằng chứng chạy lệnh và kiểm thử

### A. Supervisor Probes
- `node audit/carryover-fix-2026-11-05/independent-acceptance.mjs`: **PASS (18/18 scenarios)**
  - `creditCheck`: `{ initial: 10000, final: 9726, penalty: 274, preAnchorRows: 0 }`
- `node audit/carryover-fix-2026-11-05/legacy-classification-probes.mjs`: **PASS (8/8 scenarios)**
- `node audit/carryover-fix-2026-11-05/safety-probes.mjs`: **PASS (14/14 scenarios)**
- `node audit/owner-auto-pause-2026-11-05/independent-acceptance.mjs`: **PASS (all scenarios)**
- `node audit/owner-auto-pause-2026-11-05/supervisor-edge-probes.mjs`: **PASS (0 defects)**

### B. Kiểm thử cơ sở dữ liệu Supabase (`supabase/tests`)
- Lệnh chạy: `npm test`
- Kết quả: **PASS 100%**
- Bằng chứng chi tiết:
  - `run-carryover-fix-acceptance-test.mjs`: PASS (bao gồm ca kiểm tra billing 3 ngày chính xác 9726, pre-anchor rows = 0, và ca cross-user recovery).
  - `run-push-and-carryover-baseline062-regression-test.mjs`: PASS
  - Toàn bộ 23 suites baseline regressions, admin expansion, mission window, owner auto-pause flow/hardening/concurrency fixture đều PASS.

### C. Kiểm thử ứng dụng Android (`app`)
- JDK sử dụng: `C:\Users\nguye\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2`
- Lệnh chạy: `.\gradlew.bat testDebugUnitTest`
  - Kết quả: **BUILD SUCCESSFUL** in 32s (567 tests, 0 failures, 0 skipped).
  - Báo cáo HTML JUnit: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Lệnh chạy: `.\gradlew.bat assembleDebug`
  - Kết quả: **BUILD SUCCESSFUL** in 4s (APK biên dịch thành công).

---

## 4. Báo cáo trung thực về các giới hạn và trạng thái tồn đọng

1. **Cạnh tranh đa kết nối PostgreSQL (Multi-connection Concurrency)**:
   - Toàn bộ kiểm thử cục bộ chạy trên môi trường PGlite (WebAssembly PostgreSQL) trong tiến trình đơn (single process), xác minh tính đúng đắn logic của các khóa giao dịch và tuần tự hóa RPC.
   - Việc kiểm chứng tranh chấp thực tế trên nhiều kết nối TCP song song và độ trễ mạng đòi hỏi máy chủ PostgreSQL/Supabase thật trước khi triển khai production.
2. **Dữ liệu lịch sử đã chốt thực tế**:
   - Migration 080 không tự ý sửa đổi các bản ghi `user_daily_tasks` đã chốt trước ngày ban hành để bảo vệ số dư tài chính và tính bất biến kế toán.
   - Hàm `public.audit_carryover_journal_reconciliation()` được cung cấp để đội ngũ vận hành đối soát và lên kế hoạch xử lý thủ công có kiểm toán đối với các tài khoản từng có báo cáo mission trong quá khứ nếu cần.
