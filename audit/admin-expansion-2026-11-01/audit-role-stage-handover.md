# Báo cáo bàn giao Audit Mutation Cũ & Điều phối Quyền/Khóa (Staged 073 Focus 1 Handover)

**Chặng thực hiện**: Chặng A3 (Focus 1) — Legacy Admin Mutation Audit & Role/Lock Coordination  
**Vị trí migration**: `audit/admin-expansion-2026-11-01/audit-stage/073_admin_action_audit.sql` (staged, nằm ngoài active migrations để tích hợp sau 071/072)  
**Tệp test chuyên biệt**: `supabase/tests/run-admin-action-audit-test.mjs`  
**Model**: `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Workspace**: `F:/AI/12Tester`  
**Ngày thực hiện**: 01/11/2026  
**Trạng thái**: Hoàn tất 100% Focus 1, tất cả 7 kịch bản kiểm thử PGlite chuyên biệt đều đạt.

---

## 1. Các Wrapper đã hoàn thiện trong Focus 1

| STT | Tên hàm / Wrapper | Vai trò & Kiến trúc | Sự kiện kiểm toán ghi nhận |
|---|---|---|---|
| 1 | `_admin_adjust_credits_internal` | Hàm nội bộ private (REVOKE ALL từ `authenticated` và `anon`, chỉ cấp cho `service_role`). Tính toán số dư an toàn, khóa hàng `profiles` và ghi sổ cái `credit_logs`. | Không tự ghi audit, trả kết quả tính toán cho hàm cha. |
| 2 | `admin_adjust_credits` | Wrapper công khai cho API điều chỉnh credit cũ. Ủy quyền tính toán cho `_admin_adjust_credits_internal`. | `admin_grant_credits` hoặc `admin_deduct_credits` kèm `credits` cũ/mới và số tiền điều chỉnh thực tế (`amount`). |
| 3 | `admin_compensate_credits` | Cập nhật hàm bồi hoàn gọi thẳng `_admin_adjust_credits_internal` thay vì gọi wrapper công khai, tránh việc phát sinh 2 sự kiện audit cho cùng 1 thao tác bồi hoàn. | `compensate_credits` (duy nhất 1 sự kiện kiểm toán có liên kết request ID và đối tượng khiếu nại). |
| 4 | `admin_set_user_lock` | Đồng bộ khóa tài khoản dưới cùng khóa advisory toàn cục `hashtext('admin_role_and_lock_mutation')`. Tái xác thực quyền admin của người gọi **sau khi chờ khóa**. Khóa admin yêu cầu capability `admin_management` và bảo vệ admin chưa bị khóa cuối cùng. | `lock_user` hoặc `unlock_user` kèm trạng thái `is_locked` trước/sau và lý do. |
| 5 | `admin_set_user_role` | Đồng bộ đổi vai trò dưới cùng khóa advisory toàn cục. Tái xác thực quyền admin sau khi chờ khóa. Khi hạ quyền một admin, tự động **thu hồi triệt để** mọi capability trong `admin_capabilities` để tránh quyền ngủ đông sống lại khi thăng cấp sau này. Bảo vệ admin cuối cùng. | `set_user_role` kèm `role` trước/sau và lý do. |
| 6 | `admin_send_notification` | Ghi nhận kiểm toán cho thông báo admin cũ, bảo toàn 100% cơ chế đăng ký popup direct notification từ Migration 067 và hỗ trợ gửi broadcast toàn hệ thống. | `send_notification` kèm `sent_count`, `target_id` (`user_id` hoặc `'all'`), tiêu đề và loại thông báo. |

---

## 2. Kết quả kiểm thử Focus 1 (`run-admin-action-audit-test.mjs`)

Môi trường kiểm thử: Node.js / PGlite native tải cô lập `062` -> `070` + staged `073_admin_action_audit.sql`:

- `Legacy admin_adjust_credits records common audit event and clamps overdraft`: **PASS**
- `admin_compensate_credits emits exactly one common intent event and supports replay`: **PASS**
- `Private helper _admin_adjust_credits_internal has execution revoked from client roles`: **PASS** (xác minh cả qua `has_function_privilege` và gọi trực tiếp bị từ chối)
- `admin_set_user_lock records audit, blocks self-lock and protects last unlocked admin`: **PASS**
- `Role demotion clears admin_capabilities preventing dormant privilege reactivation`: **PASS**
- `Legacy admin_send_notification records audit and registers 067 popup state`: **PASS**
- `Re-application of 073 is completely idempotent`: **PASS**

Kiểm tra hồi quy hệ thống hiện có:
- `npm run test:admin-management`: **PASS 100%**
- `npm run test:admin-audit`: **PASS 100%**
- `node audit/admin-expansion-2026-11-01/independent-core-probe.mjs`: **PASS**

---

## 3. Danh sách các RPC Mutation cũ còn lại cần bọc Audit (Focus 2)

Các RPC quản trị cũ còn lại sẽ được bổ sung vào staged 073 trong lượt tiếp theo (Focus 2) theo đúng mẫu thiết kế đã chuẩn hóa:

1. `public.admin_update_app_config(min_version, latest_version, name, force, ...)`: Ghi nhận sự kiện `update_app_config` trên đối tượng `app_config`.
2. `public.admin_set_home_stats_cycle(cycle_days, expected_revision)`: Ghi nhận sự kiện `set_home_stats_cycle` trên đối tượng `home_stats_policy`.
3. `public.admin_schedule_daily_task_policy(ratio, effective_from, note, expected_revision)` & `admin_cancel_daily_task_policy(effective_from, note, expected_revision)`: Ghi nhận sự kiện `schedule_daily_task_policy` và `cancel_daily_task_policy` trên đối tượng `daily_task_policy`.
4. `public.admin_pause_campaign(campaign_id, reason)`, `admin_resume_campaign(campaign_id)`, `admin_delete_campaign(campaign_id, reason)`: Ghi nhận sự kiện `pause_campaign`, `resume_campaign`, `delete_campaign` trên đối tượng `campaign`.
5. `public.admin_reply_contact_report(report_id, message)`, `admin_update_report_status(report_id, resolved)`: Ghi nhận sự kiện `reply_contact_report` và `update_report_status` trên đối tượng `contact_report`.
6. `public.approve_tester_report(report_id)` & `public.reject_tester_report(report_id, reason, reason_code)` khi được thực hiện bởi Admin/Moderator (Staff): Ghi nhận sự kiện kiểm toán kiểm duyệt báo cáo tester.
