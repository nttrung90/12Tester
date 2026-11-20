# Bàn giao Phần Backend (Migration 069 & Test Suites) — 2026-11-01

## 1. Tóm tắt các lỗi đã phát hiện và khắc phục

### P1: Credit ledger không khớp số dư thực tế & input/overflow validation
- **Nguyên nhân**: `admin_adjust_credits` chặn số dư không âm ở 0 nhưng ghi `credit_logs.amount` và trả kết quả `amount` theo tham số `p_amount`. Khi số dư là 5 và trừ 50, số dư về 0 nhưng ledger và kết quả ghi `-50`. Ngoài ra thiếu kiểm tra `NULL`, `p_amount = 0`, mục tiêu không tồn tại và overflow 32-bit integer.
- **Khắc phục**: Tính toán `v_effective_amount := v_new_balance - v_current_credits` và ghi chính xác delta thực tế vào `credit_logs` và JSON trả về. Bổ sung kiểm tra `p_target_user_id IS NULL`, `p_amount IS NULL OR p_amount = 0`, overflow `> 2147483647` và khóa dòng `FOR UPDATE` với lỗi `USER_NOT_FOUND` nếu user không tồn tại.

### P1: Caller bị khóa hoặc không đủ quyền gọi RPC quản trị
- **Nguyên nhân**: Các RPC quản trị (`admin_adjust_credits`, `admin_set_user_lock`, `admin_get_contact_reports`, `admin_reply_contact_report`, `admin_update_report_status`, `admin_send_notification`, `admin_update_app_config`, `admin_get_home_stats_config`, `admin_set_home_stats_cycle`, `admin_pause_campaign`, `admin_resume_campaign`, `admin_delete_campaign`) chỉ kiểm tra `role = 'admin'` mà không kiểm tra `is_locked`. Admin bị khóa vẫn thao tác được toàn bộ quyền quản trị.
- **Khắc phục**: Kiểm tra fail-closed `coalesce(is_locked, false) = false` trên profile người gọi cho tất cả 17 RPC quản trị.

### P2: Khóa user không tồn tại vẫn báo thành công
- **Nguyên nhân**: `admin_set_user_lock` không kiểm tra số dòng cập nhật. Nếu `p_target_user_id` không tồn tại, hàm vẫn trả `{ success: true, is_locked: ... }`.
- **Khắc phục**: Kiểm tra `IF NOT FOUND THEN RAISE EXCEPTION 'USER_NOT_FOUND'; END IF;` và kiểm tra tham số `NULL`.

### P1/P2: Duyệt báo cáo và quản lý chiến dịch bởi actor bị khóa
- **Nguyên nhân**: `approve_tester_report` và `reject_tester_report` chỉ kiểm tra role `in ('admin', 'moderator')` mà không kiểm tra cờ khóa của caller. Nếu chủ app, admin hoặc moderator bị khóa, họ vẫn duyệt/từ chối được báo cáo. Ngoài ra, `get_campaign_detail` cấp quyền `can_manage` mà không loại trừ tài khoản bị khóa.
- **Khắc phục**:
  - `approve_tester_report` & `reject_tester_report`: Kiểm tra sớm `IF v_caller_locked = true THEN RAISE EXCEPTION 'ACCOUNT_LOCKED'; END IF;`. Giữ nguyên 100% thuật toán gốc của baseline 062: mức hoàn trả bounded clawback, cấu trúc metadata `test_rejected_clawback`, bảo lưu check-in đã xác nhận (chỉ xóa checkin có `%tạm tính%`), cảnh báo vi phạm liên tiếp và tỷ lệ thưởng chủ app = 0 khi admin/moderator duyệt.
  - `get_campaign_detail`: Giữ nguyên toàn bộ cấu trúc DTO, JSON keys và hợp đồng tài chính của migration 068, chỉ bổ sung điều kiện `can_manage` loại trừ profile có `is_locked = true`.

### P2: Lỗ hổng RLS và quyền TRUNCATE/TRIGGER của client role
- **Nguyên nhân**:
  - Policy `Allow update app_configs to admin` và `campaigns_delete_owner_or_admin` không kiểm tra `is_locked`, cho phép admin bị khóa UPDATE trực tiếp bảng config hoặc DELETE campaign.
  - Baseline 062 cấp quyền `TRUNCATE` và `TRIGGER` trên toàn bộ bảng public cho database roles `anon` và `authenticated`. Lệnh `TRUNCATE` vượt qua RLS hoàn toàn.
- **Khắc phục**:
  - Siết chặt policy UPDATE trên `app_configs` và DELETE/SELECT trên `campaigns` yêu cầu `coalesce(p.is_locked, false) = false`.
  - Thu hồi toàn bộ quyền `TRUNCATE` và `TRIGGER` trên tất cả bảng thuộc schema `public` đối với `anon`, `authenticated`, `PUBLIC`.

---

## 2. Danh sách file thay đổi

1. `supabase/migrations/069_admin_audit_hardening.sql` (file mới):
   - Migration bổ sung duy nhất cho toàn bộ hardening backend.
   - Giữ nguyên baseline 062 và migrations 063–068.
2. `supabase/tests/run-admin-audit-hardening-test.mjs` (file mới):
   - Bộ kiểm thử hành vi độc lập kiểm chứng 103 trường hợp (ledger delta, input validation, overflow, target existence, 17 RPCs x 5 loại caller, direct RLS bypass, TRUNCATE/TRIGGER capability, popup registration for locked recipient, campaign detail DTO keys & app update release, reject clawback & warning streak).
3. `supabase/tests/run-admin-direct-notification-popup-test.mjs`:
   - Bổ sung 10ms delay giữa 2 lệnh insert thông báo trong fixture test 9 để đảm bảo `clock_timestamp()` có timestamp tăng dần trên môi trường PGlite.
4. `audit/admin-flow-2026-11-01/069_initial_faulty_backup.sql`:
   - Bản lưu vết migration 069 ban đầu trước khi sửa 2 regression theo yêu cầu của reviewer.

---

## 3. Bằng chứng chạy kiểm thử

### A. Independent After Verification (`audit/admin-flow-2026-11-01/independent-after-verification.mjs`)
- Lệnh: `node audit/admin-flow-2026-11-01/independent-after-verification.mjs`
- Kết quả: **ALL 103 INDEPENDENT CHECKS PASSED**

### B. New Behavioral Test Suite (`supabase/tests/run-admin-audit-hardening-test.mjs`)
- Lệnh: `node supabase/tests/run-admin-audit-hardening-test.mjs`
- Kết quả: **ALL 103 ADMIN AUDIT HARDENING TESTS PASSED**

### C. Admin Direct Notification Popup Test (`supabase/tests/run-admin-direct-notification-popup-test.mjs`)
- Lệnh: `node supabase/tests/run-admin-direct-notification-popup-test.mjs`
- Kết quả: **ALL ADMIN DIRECT NOTIFICATION POPUP TESTS PASSED** (11/11 tests pass)

### D. Owner Tester Reports Count Test (`supabase/tests/run-owner-tester-reports-count-test.mjs`)
- Lệnh: `node supabase/tests/run-owner-tester-reports-count-test.mjs`
- Kết quả: **ALL OWNER TESTER REPORT COUNT DATABASE TESTS PASSED** (9/9 tests pass)

### E. Admin Daily Task Policy Test (`supabase/tests/run-admin-daily-task-policy-test.mjs`)
- Lệnh: `node supabase/tests/run-admin-daily-task-policy-test.mjs`
- Kết quả: **TOÀN BỘ CÁC BƯỚC TEST BACKEND CHO MIGRATION 058 ĐÃ THÀNH CÔNG** (10/10 tests pass)

### F. Toàn bộ `npm test` (`supabase/tests/package.json`)
- Lệnh: `npm test` trong thư mục `supabase/tests`
- Chạy qua toàn bộ 21 test scripts của dự án:
  1. `run-consolidated-baseline-test.mjs`: PASS (8/8)
  2. `run-daily-task-ratio.mjs`: PASS (2016 SQL assertions)
  3. `run-backend-cleanup-hardening.mjs`: PASS (5 nhóm)
  4. `run-mission-flow-hardening.mjs`: PASS (M01-M09 & R01-R07)
  5. `run-thumbnail-migration.mjs`: PASS
  6. `run-media-gateway-test.mjs`: PASS
  7. `run-home-stats-cycle-test.mjs`: PASS (14/14)
  8. `run-mission-report-and-expiration-test.mjs`: PASS (18/18)
  9. `run-app-update-reward-test.mjs`: PASS (28/28)
  10. `run-campaign-push-test.mjs`: PASS (19/19)
  11. `run-daily-task-carryover.mjs`: PASS (10/10)
  12. `run-push-and-carryover-hardening-test.mjs`: PASS (10/10)
  13. `run-report-idempotency-ledger-test.mjs`: PASS
  14. `run-admin-daily-task-policy-test.mjs`: PASS (10/10)
  15. `run-report-idempotency-provenance-and-rating-test.mjs`: PASS
  16. `run-push-campaign-role-hardening-test.mjs`: PASS (11/11)
  17. `run-report-extension-schema-test.mjs`: PASS
  18. `run-explicit-report-context-test.mjs`: PASS
  19. `run-remove-dhash-hardblock-test.mjs`: PASS
  20. `run-push-and-carryover-baseline062-regression-test.mjs`: PASS
  21. `run-owner-tester-reports-count-test.mjs`: PASS (9/9)
- Kết quả: **100% TOÀN BỘ CÁC BỘ TEST DATABASE ĐỀU PASS**.

---

## 4. Ghi chú và lưu ý cho phần Android tiếp theo
- Phần Backend đã hoàn thành trọn vẹn và đạt chuẩn, không có lỗi tồn đọng.
- Workspace giữ nguyên trạng thái dirty và các file checkpoint.
- Sẵn sàng chuyển giao cho pha Android tiếp theo theo chỉ dẫn của Reviewer.
