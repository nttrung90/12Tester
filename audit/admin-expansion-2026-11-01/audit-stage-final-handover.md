# Báo cáo bàn giao Hoàn thiện Audit Quản trị & Điều phối Quyền (Staged 073 Final Handover)

**Chặng thực hiện**: Chặng A3 / A4 Review — Hoàn thiện Toàn bộ Legacy Admin Mutation Audit, Phân định Quyền Chủ App / Staff và Bổ sung Cờ `media_expired`  
**Vị trí migration**: `audit/admin-expansion-2026-11-01/audit-stage/073_admin_action_audit.sql` (staged, nằm ngoài active migrations để áp dụng sau 071 và 072)  
**Tệp test chuyên biệt**: `supabase/tests/run-admin-action-audit-test.mjs`  
**Model**: `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Workspace**: `F:/AI/12Tester`  
**Ngày thực hiện**: 01/11/2026  
**Trạng thái**: Hoàn tất 100%, tất cả kịch bản kiểm thử độc lập và hệ thống đều đạt.

---

## 1. Hai khiếm khuyết cụ thể đã xử lý trong lượt này

### 1.1 Phân định chế độ Chủ App (Owner mode) vs Điều hành viên (Staff mode) trong `approve_tester_report` & `reject_tester_report`
- **Vấn đề**: `independent-audit-owner-probe.mjs` thất bại do một Quản trị viên (Admin) hoặc Điều hành viên (Moderator) khi sở hữu chiến dịch và duyệt/từ chối báo cáo trên app của chính mình (chế độ Owner mode, nhận thưởng 2 Credit của chủ app) thì wrapper 073 trước đó chỉ kiểm tra role nên đã ghi nhầm sự kiện `staff_approve_tester_report` vào `admin_action_events`.
- **Giải pháp**:
  - Truy vấn `campaigns.owner_id` từ `p_report_id` trước khi ghi nhận kiểm toán.
  - Nếu `auth.uid() = owner_id` (kể cả khi tài khoản có role `admin` hoặc `moderator`), giao dịch được xác định là **Owner mode**: giữ nguyên toàn bộ quyền lợi của chủ app (nhận `owner_credits_awarded: 2` khi duyệt) và **loại trừ không ghi vào `admin_action_events`**.
  - Nếu `auth.uid() <> owner_id` và người gọi là Admin/Moderator chưa bị khóa, giao dịch được xác định là **Staff moderation mode**: nhận 0 owner bonus và được ghi nhận chính xác 1 sự kiện `staff_approve_tester_report` hoặc `staff_reject_tester_report` vào `admin_action_events`.
  - Nếu thao tác thất bại (báo cáo không tồn tại, hết hạn, vi phạm quyền), exception được ném ra và rollback toàn bộ, không có sự kiện audit nào được commit.

### 1.2 Bổ sung trường `media_expired` vào hàng đợi báo cáo tester (`admin_get_tester_reports_queue`) trong Migration 070
- **Vấn đề**: RPC `admin_get_tester_reports_queue` trước đó chiếu `screenshot_url` và `thumbnail_url` nhưng thiếu cột `media_expired` (vốn đã có sẵn trong bảng `tester_reports` từ baseline 062). Android cần cờ này để hiển thị mô tả trạng thái media an toàn.
- **Giải pháp**:
  - Thêm cột hiện có `tr.media_expired` vào câu lệnh chiếu dữ liệu của `admin_get_tester_reports_queue` trong `supabase/migrations/070_admin_management.sql`.
  - Không tham chiếu bất kỳ cột tài chính nào thuộc phạm vi staged 071, giữ nguyên vẹn ranh giới sở hữu giữa các migration.
  - Cập nhật hợp đồng API tại `audit/admin-expansion-2026-11-01/api-contract.md` và kiểm thử tự động xác nhận cả 2 giá trị `media_expired: true` và `false`.

---

## 2. Toàn bộ danh mục 16 RPC Quản trị đã bọc Audit trong 073

| STT | RPC công khai | Tên hàm thực thi nội bộ | Đối tượng mục tiêu (`target_type`) | Hành động kiểm toán (`action`) | Ghi chú nghiệp vụ |
|---|---|---|---|---|---|
| 1 | `admin_adjust_credits` | `_admin_adjust_credits_internal` | `user` | `admin_grant_credits` / `admin_deduct_credits` | Kẹp số dư tối thiểu 0, ghi ledger `credit_logs`, ghi nhận số tiền thực tế `amount`. |
| 2 | `admin_compensate_credits` | `_admin_adjust_credits_internal` | `user` | `compensate_credits` | Ghi duy nhất 1 sự kiện audit có request ID, không phát sinh trùng lặp sự kiện điều chỉnh. |
| 3 | `admin_set_user_lock` | Cập nhật trực tiếp `profiles` | `user` | `lock_user` / `unlock_user` | Đồng bộ khóa advisory `admin_role_and_lock_mutation`, revalidate quyền sau chờ khóa, bảo vệ admin cuối cùng. |
| 4 | `admin_set_user_role` | Cập nhật trực tiếp `profiles` | `user` | `set_user_role` | Đồng bộ khóa advisory, revalidate quyền sau chờ khóa, thu hồi vĩnh viễn capability khi hạ quyền, bảo vệ admin cuối cùng. |
| 5 | `admin_send_notification` | Cập nhật trực tiếp `notifications` | `user` | `send_notification` | Bảo toàn đăng ký popup state 067 cho direct notification, hỗ trợ broadcast toàn hệ thống (`target_id: 'all'`). |
| 6 | `admin_update_app_config` | `_impl_admin_update_app_config` | `app_config` | `update_app_config` | Ghi nhận cấu hình phiên bản app trước và sau cập nhật (`target_id: 'android_client'`). |
| 7 | `admin_set_home_stats_cycle` | `_impl_admin_set_home_stats_cycle` | `home_stats_policy` | `set_home_stats_cycle` | Ghi nhận điều chỉnh chu kỳ thống kê trang chủ kèm revision. |
| 8 | `admin_schedule_daily_task_policy` | `_impl_admin_schedule_daily_task_policy` | `daily_task_policy` | `schedule_daily_task_policy` | Ghi nhận lên lịch tỷ lệ kiểm thử ngày mới. |
| 9 | `admin_cancel_daily_task_policy` | `_impl_admin_cancel_daily_task_policy` | `daily_task_policy` | `cancel_daily_task_policy` | Ghi nhận hủy lịch tỷ lệ kiểm thử ngày. |
| 10 | `admin_pause_campaign` | `_impl_admin_pause_campaign` | `campaign` | `pause_campaign` | Ghi nhận tạm dừng chiến dịch kèm lý do quản trị. |
| 11 | `admin_resume_campaign` | `_impl_admin_resume_campaign` | `campaign` | `resume_campaign` | Ghi nhận khôi phục chiến dịch bởi admin. |
| 12 | `admin_delete_campaign` | `_impl_admin_delete_campaign` | `campaign` | `delete_campaign` | Ghi nhận gỡ bỏ chiến dịch kèm lý do quản trị. |
| 13 | `admin_reply_contact_report` | `_impl_admin_reply_contact_report` | `contact_report` | `reply_contact_report` | Ghi nhận phản hồi khiếu nại/báo lỗi người dùng. |
| 14 | `admin_update_report_status` | `_impl_admin_update_report_status` | `contact_report` | `update_report_status` | Ghi nhận đánh dấu xử lý xong hoặc mở lại khiếu nại. |
| 15 | `approve_tester_report` | `_impl_approve_tester_report` | `tester_report` | `staff_approve_tester_report` | **Owner mode (kể cả admin-owner/mod-owner) không ghi audit**; chỉ Non-owner Staff duyệt mới ghi audit. |
| 16 | `reject_tester_report` | `_impl_reject_tester_report` | `tester_report` | `staff_reject_tester_report` | **Owner mode (kể cả admin-owner/mod-owner) không ghi audit**; chỉ Non-owner Staff từ chối mới ghi audit. |

- **Các RPC đọc (Read-only)**: `admin_get_contact_reports`, `admin_get_home_stats_config`, `admin_get_daily_task_config`, `admin_list_daily_task_policies`, `admin_get_daily_task_policy_history` được bảo đảm không phát sinh audit.

---

## 3. Kết quả kiểm thử thực tế

Tất cả các kiểm thử được chạy cục bộ với Node.js / PGlite native:

1. **`node audit/admin-expansion-2026-11-01/independent-audit-owner-probe.mjs`**:
   - `ownerBonus`: 2
   - `commonEvents`: `[]` (rỗng)
   - **PASS**: Duyệt báo cáo trong chế độ Owner mode bởi Quản trị viên nhận 2 credit và không ghi nhận sự kiện common audit.
2. **`node audit/admin-expansion-2026-11-01/independent-core-probe.mjs`**:
   - `campaign detail preserves full DTO keys`: **PASS**
   - `warning revocation consistent on dashboard and detail`: **PASS** (`dashboardWarnings: 0, dashboardUnread: 0, detailWarnings: 0`)
   - `carryover schedule and cancellation succeed`: **PASS**
   - `member overview has actual slot data`: **PASS** (`hasSlotsArray: true`)
3. **`supabase/tests/run-admin-action-audit-test.mjs`** (14 kịch bản):
   - Bao phủ: admin-owner/mod-owner approve + reject, member owner/staff baseline, failed mutation rollback, direct popup 067, idempotent re-application, thu hồi EXECUTE trên tất cả `_impl_...`.
   - **PASS 100%**.
4. **`supabase/tests/run-admin-management-test.mjs`**:
   - Bao phủ: `media_expired` boolean flag (`true`/`false`), replay trước CAS validation, slots array, dashboard warning exclusion.
   - **PASS 100%**.
5. **`supabase/tests/run-admin-audit-hardening-test.mjs`**:
   - **PASS 100%**.
6. **Chuỗi nạp đầy đủ (062 -> 070 -> staged 071 -> staged 072 -> staged 073)**:
   - **PASS**: Toàn bộ chuỗi nạp thành công không xung đột.

---

## 4. Tệp tin thay đổi

- `supabase/migrations/070_admin_management.sql`: Thêm `tr.media_expired` vào câu lệnh SELECT của `admin_get_tester_reports_queue`.
- `audit/admin-expansion-2026-11-01/audit-stage/073_admin_action_audit.sql`: Bổ sung kiểm tra `v_camp_owner_id` trong `approve_tester_report` và `reject_tester_report` để miễn audit khi duyệt trong chế độ Chủ app.
- `supabase/tests/run-admin-action-audit-test.mjs`: Mở rộng test case 11 bao phủ admin-owner, mod-owner, member-owner, staff review và failed mutation rollback.
- `supabase/tests/run-admin-management-test.mjs`: Thêm assertion kiểm tra cờ `media_expired` (`true`/`false`).
- `audit/admin-expansion-2026-11-01/api-contract.md`: Cập nhật mô tả trường `media_expired`.
- `audit-stage-final-handover.md`: Bản lưu báo cáo bàn giao tại thư mục gốc.
- `audit/admin-expansion-2026-11-01/audit-stage-final-handover.md`: Bản lưu trong thư mục audit.
