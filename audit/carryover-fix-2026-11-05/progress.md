# Tiến độ sửa lỗi cộng dồn nhiệm vụ chưa hoàn thành (Carryover Fix) — 05/11/2026

## 1. Tóm tắt tiến độ

Toàn bộ 4 nhóm lỗi ban đầu (C01, C02, C03, C04) cùng 8 điểm đánh giá độc lập của Supervisor (R01 - R08) đã được xử lý triệt để và kiểm chứng đạt 100%:
- **C01 / R04**: Phân loại ngữ cảnh báo cáo chặt chẽ: báo cáo mission không tham gia chỉ tiêu thường (`is_qualifying = false`), báo cáo không rõ ngữ cảnh (`NULL`) bị đánh dấu `unresolved_unknown_context`, giữ nguyên giá trị hợp lệ của checkin thông thường.
- **C02 / R06 / R07**: Tách bạch logic đăng nhập hoàn thành của thành viên (`met = true`) với trạng thái nợ bảo lưu; hiển thị số nợ bảo lưu trên dòng riêng (`daily_task_preserved_debt_notice`) trong thẻ gọn không bị cắt cụt; hiển thị trạng thái đang cập nhật `"—"` không nhận định nợ bằng 0.
- **C03 / R01 / R02 / R03 / R05 / R08**:
  - Khôi phục snapshot lịch sử bất biến cho các ngày đã chốt (R01).
  - Thu hồi quyền thực thi tài chính công khai khỏi role `authenticated`, chỉ cấp quyền cho `service_role` (R02).
  - Khóa bảo vệ các hàm chẩn đoán: chặn caller ẩn danh, caller không có danh tính, và admin bị khóa (`assert_unlocked_admin`) (R03).
  - Tối ưu truy vấn tìm kiếm công việc: loại bỏ các ngày chưa đủ điều kiện/chưa đối soát khỏi việc chặn hàng đợi, ngăn chặn hiện tượng kẹt cron làm đói tài khoản bình thường (R05).
  - **Bảo vệ tài chính trước checkpoint (R08)**:
    - Bổ sung tham số chân trời checkpoint `p_horizon_scope` cho `_apply_daily_inactivity_penalty(p_eval, p_horizon_scope)` (và giữ nguyên entry-point tương thích `_apply_daily_inactivity_penalty(p_eval)`).
    - Quá trình chốt bù tự động tôn trọng ranh giới checkpoint đáng tin cậy gần nhất tại hoặc trước mốc đích (`v_scope`), không bao giờ chốt bù hay phạt trừ Credit các ngày nằm trước checkpoint (`p_eval < v_checkpoint_boundary`).
    - Lọc ứng viên tự động loại trừ mọi ngày nằm trước checkpoint (`d < cp.task_date`).
    - Số dư 10000 trong bài toán 3 ngày được bảo toàn tuyệt đối, chỉ trừ đúng 274 Credit tiền phạt của 3 ngày phát sinh sau checkpoint, số dư cuối cùng đạt đúng 9726, số dòng tạo trước checkpoint bằng đúng 0 (`preAnchorRows = 0`).
- **C04**: Cô lập kiểm thử re-run 066 trên `isoDb`, đảm bảo chuỗi migration 062–080 luôn được nạp tuần tự và getter/finalizer đạt chuẩn phiên bản mới nhất.

---

## 2. Nhật ký công việc thực hiện

1. **Khảo sát phản hồi Supervisor**:
   - Đọc kỹ `audit/carryover-fix-2026-11-05/supervisor-review-notes.md` và `task-checkpoint-billing.txt`.
   - Phân tích và tái hiện lỗi trừ tiền trước mốc checkpoint (R08).

2. **Cập nhật Migration 080 (`080_report_context_carryover_and_gap_recovery.sql`)**:
   - **R01**: Thêm đoạn kiểm tra snapshot lịch sử `if p_date < v_today` trong `get_daily_task_status`, trả về dữ liệu nguyên bản từ `user_daily_tasks` nếu ngày đó đã chốt.
   - **R02**: Đặt guard chặn `authenticated` và `anon` trong `apply_daily_inactivity_penalty` và `finalize_daily_tasks_for_date`, thu hồi quyền thực thi khỏi `PUBLIC`, `anon`, `authenticated`, chỉ cấp cho `service_role`.
   - **R03**: Cập nhật `diagnose_daily_task_stuck_users` và `audit_carryover_journal_reconciliation` gọi `assert_unlocked_admin()`, kiểm tra bắt buộc có `auth.uid()`.
   - **R04**: Cập nhật đối soát `daily_test_events_journal`: báo cáo có context `NULL` được gán `unresolved_unknown_context` và `is_qualifying = false`; giữ nguyên tính hợp lệ của checkin thường có ghi chú tự do.
   - **R05 & R08**:
     - Thêm hàm `public._apply_daily_inactivity_penalty(p_eval date, p_horizon_scope date)` có kiểm tra `v_checkpoint_boundary`: nếu `p_eval < v_checkpoint_boundary` thì `continue` bỏ qua, không tạo dòng và không trừ phạt trước mốc chốt tin cậy.
     - Cung cấp hàm tương thích `public._apply_daily_inactivity_penalty(p_eval date)` gọi `_apply_daily_inactivity_penalty(p_eval, p_eval)`.
     - Cập nhật điều kiện lọc ứng viên trong `apply_daily_inactivity_penalty` và `finalize_daily_tasks_for_date`: không chọn ngày nằm trước checkpoint (`not exists cp where cp.task_date <= target and cp.task_date > d`), đồng thời loại trừ ngày có sự kiện chưa đối soát (`unresolved_missing_source`, `unresolved_unknown_context`).

3. **Cập nhật Android UI & Models**:
   - `Models.kt`: Giữ `isPendingCarryover` riêng cho owner để không ảnh hưởng đăng nhập member; bổ sung `isCarryoverHistoryPending` cho kiểm tra cross-mode; giữ `hasRemainingCarryover` chuẩn xác.
   - `DailyTaskCard.kt`:
     - Nhánh thẻ gọn (`met=true`): dùng `Column` hiển thị tiêu đề hoàn thành ở dòng 1 và dòng 2 riêng cho số nợ bảo lưu (`daily_task_preserved_debt_notice`) màu hổ phách, không bị giới hạn cắt chuỗi (R07).
     - Nhánh `memberMode`: hiển thị rõ ràng nợ bảo lưu cũ và hướng dẫn test tự nguyện, hoặc thông báo nợ đang cập nhật nếu hôm qua chưa chốt (R06).
   - Đa ngôn ngữ: Bổ sung `daily_task_preserved_debt_notice` và `daily_task_carryover_updating_notice` trên `values`, `values-en`, `values-vi`.
   - `DailyTaskCarryoverUnitTest.kt`: Bổ sung test case kiểm tra member login `met=true` độc lập với nợ chưa ready, contract thẻ gọn.

4. **Xác minh kiểm thử toàn diện**:
   - Chạy toàn bộ 5 supervisor probes: PASS 100%.
   - Chạy toàn bộ `npm test` trong `supabase/tests`: PASS 100%.
   - Chạy Android `testDebugUnitTest` (567 tests, 0 failures) và `assembleDebug`: PASS 100%.

---

## 3. Bằng chứng chạy lệnh và kiểm thử

### A. Supervisor Probes
- `node audit/carryover-fix-2026-11-05/independent-acceptance.mjs`: **PASS** (18/18 scenarios, `creditCheck: { initial: 10000, final: 9726, penalty: 274, preAnchorRows: 0 }`).
- `node audit/carryover-fix-2026-11-05/legacy-classification-probes.mjs`: **PASS** (8/8 scenarios).
- `node audit/carryover-fix-2026-11-05/safety-probes.mjs`: **PASS** (14/14 scenarios).
- `node audit/owner-auto-pause-2026-11-05/independent-acceptance.mjs`: **PASS** (all scenarios).
- `node audit/owner-auto-pause-2026-11-05/supervisor-edge-probes.mjs`: **PASS** (0 defects).

### B. Supabase Full SQL Test Suite
- `npm test`: **PASS 100%** (23 baseline regression suites, admin expansion, mission window, owner auto-pause flow/hardening/concurrency fixture, carryover acceptance).

### C. Android Unit Tests & Build
- `.\gradlew.bat testDebugUnitTest`: **BUILD SUCCESSFUL** (567 tests, 0 failures, 0 skipped, thời gian 32s).
- `.\gradlew.bat assembleDebug`: **BUILD SUCCESSFUL** in 4s.
