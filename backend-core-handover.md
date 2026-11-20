# Báo cáo bàn giao Backend Quản Trị Viên (Admin Core Handover — Stage A)

**Chặng thực hiện**: Chặng A — Backend Quản trị Cốt lõi (Task A Core)  
**Model**: `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Workspace**: `F:/AI/12Tester`  
**Ngày thực hiện**: 01/11/2026  
**Trạng thái**: Hoàn tất 100%, tất cả test suite PGlite vượt qua không lỗi.

---

## 1. Danh sách tệp tin đã tạo và cập nhật

| STT | Đường dẫn tệp tin | Trạng thái | Mô tả chi tiết |
|---|---|---|---|
| 1 | `audit/admin-expansion-2026-11-01/api-contract.md` | Tạo mới | Hợp đồng giao diện RPC, JSON request/response DTO, cấu trúc con trỏ phân trang, mã lỗi và ràng buộc bảo mật cho toàn bộ 8 nhóm chức năng cốt lõi. |
| 2 | `supabase/migrations/070_admin_management.sql` | Tạo mới | Migration chứa các bảng kiểm toán, quyền hạn đặc thù, idempotency request, thu hồi cảnh báo append-only, helper bảo mật `assert_unlocked_admin`, toàn bộ RPC quản trị và override các hàm đếm cảnh báo. |
| 3 | `supabase/tests/run-admin-management-test.mjs` | Tạo mới | Bộ test hành vi tự động bằng PGlite kiểm thử nạp active migrations (062–070), phân quyền, phân trang >500 hồ sơ, idempotency, revision conflict, role guard và warning consistency. |
| 4 | `supabase/tests/package.json` | Cập nhật | Thêm script `"test:admin-management"` và tích hợp vào chuỗi test tổng hợp `"test"`. |
| 5 | `audit/admin-expansion-2026-11-01/backend-core-handover.md` | Tạo mới | Tài liệu bàn giao chi tiết cho Codex và chặng Android kế tiếp. |
| 6 | `backend-core-handover.md` | Tạo mới | Bản sao bàn giao tại thư mục gốc workspace để dễ dàng truy cập. |

---

## 2. Tóm tắt 8 nhóm chức năng backend đã hoàn thiện

### Nhóm 1: Helper kiểm tra Quản trị viên & Nhật ký kiểm toán thao tác (`admin_action_events`)
- **`public.assert_unlocked_admin(p_required_capability)`**: Helper dùng chung kiểm tra `auth.uid() IS NOT NULL`, `role = 'admin'` và `is_locked = false`. Nếu truyền `p_required_capability`, kiểm tra quyền hạn tương ứng trong bảng `admin_capabilities`.
- **`public.admin_action_events`**: Bảng append-only ghi nhận: `actor_id`, `action`, `target_type`, `target_id`, `reason`, `old_state`, `new_state`, `request_id`, `created_at`.
- **`public.admin_get_action_events`**: RPC đọc nhật ký kiểm toán có phân trang keyset `(created_at DESC, id DESC)`, lọc theo `action`, `target_type`, `actor_id`. Chặn client spoofing thông qua RLS và revoke quyền ghi từ client.

### Nhóm 2: Tìm kiếm thành viên phía Server & Hồ sơ tổng quan
- **`public.admin_search_members`**: Tìm kiếm trên toàn bộ cơ sở dữ liệu (vượt qua giới hạn 500 hồ sơ cũ) theo tên, email, UUID; lọc vai trò (`member`, `moderator`, `admin`) và trạng thái khóa; phân trang keyset ổn định `(created_at, id)` và giới hạn 1..100; escape ký tự đại diện (`%`, `_`, `\`).
- **`public.admin_get_member_overview`**: Gom chiến dịch (`total`, `open`), slot (`total`, `active`), cảnh báo (`active`, `revoked`), và số lượng báo cáo (`tester_reports`, `contact_reports`).
- **`public.admin_get_member_credit_history`**: Lịch sử credit có phân trang con trỏ keyset.
- **`public.admin_get_member_daily_tasks`**: Lấy lịch sử và giải thích chi tiết trạng thái nhiệm vụ ngày qua hàm nội bộ `get_daily_task_status`, bảo toàn dữ liệu snapshot bất biến đã chốt và chỉ rõ ngày chưa chốt hoặc `incomplete_history`.

### Nhóm 3: Quản lý chính sách cộng dồn nhiệm vụ (`carryover`)
- **`public.admin_get_carryover_policies`**: Đọc chính sách active (hỗ trợ trạng thái rỗng mặc định `default_active`), danh sách tương lai (`future`), lịch sử (`history`) và `revision`.
- **`public.admin_schedule_carryover_policy`**: Wrapper kiểm tra admin, lock ghi đồng thời, kiểm tra `expected_revision`, chỉ cho phép lên lịch ngày tương lai (`> VN today`), gọi hàm nội bộ và ghi nhật ký kiểm toán.
- **`public.admin_cancel_future_carryover_policy`**: Hủy lịch tương lai với lý do rõ ràng, ghi nhận lịch sử hủy vào cả `daily_task_carryover_policy_audit` và `admin_action_events`.

### Nhóm 4: Quản lý chiến dịch tập trung & Hàng đợi báo cáo tester
- **`public.admin_search_campaigns`**: Tìm kiếm chiến dịch mọi trạng thái (`open`, `paused`, `completed`, v.v.) theo tên app, package, UUID, chủ app; join tên/email chủ app và số lượng báo cáo chờ.
- **`public.admin_get_tester_reports_queue`**: Hàng đợi duyệt báo cáo tester toàn hệ thống gom từ nhiều chiến dịch với bộ lọc `status`, `campaign_id`, `tester_id`, `owner_id`, `submission_context`; join thông tin hiển thị của chiến dịch, chủ app và tester. Các RPC duyệt/từ chối hiện có vẫn là nguồn kiểm soát vòng đời thẩm quyền.

### Nhóm 5: Bồi hoàn Credit an toàn & Chống xử lý lặp
- **`public.admin_compensate_credits`**:
  - Nhận UUID `request_id`, `target_user_id`, `amount`, `reason`, cùng liên kết tùy chọn tới `original_credit_log_id` hoặc `contact_report_id`.
  - Bảng quản lý yêu cầu `public.admin_operation_requests` phạm vi `(actor_id, operation, id)`.
  - Khóa hàng và khóa giao dịch chống race condition.
  - Tái sử dụng `admin_adjust_credits` để tính toán số dư thực tế (effective delta), không sửa trực tiếp sổ cái quá khứ.
  - Gửi lại cùng `request_id` và payload: trả về kết quả ban đầu kèm `"is_replay": true`.
  - Gửi lại cùng `request_id` nhưng khác payload: từ chối với lỗi `REQUEST_ID_PAYLOAD_MISMATCH`.

### Nhóm 6: Phân quyền vai trò & Bảo vệ Quản trị viên cuối cùng
- **`public.admin_get_my_capabilities`**: Trả về quyền hạn của admin đang đăng nhập (`can_manage_admins`, `can_manage_moderators`).
- **`public.admin_set_user_role`**:
  - Quản lý vai trò `member`, `moderator` thông qua admin chưa bị khóa.
  - Bổ nhiệm hoặc hạ quyền `admin` chỉ cho phép khi người gọi sở hữu capability `admin_management` (quản lý bootstrap qua `bootstrap_admin_capability`).
  - Chặn người dùng tự thay đổi vai trò hoặc tự leo thang quyền hạn (`CANNOT_CHANGE_OWN_ROLE`).
  - Bảo vệ Quản trị viên chưa bị khóa cuối cùng trong hệ thống dưới điều kiện đồng thời (`CANNOT_REMOVE_LAST_ADMIN`).
  - Không dùng thay đổi vai trò để xóa phạt quá khứ.

### Nhóm 7: Xử lý & Thu hồi cảnh báo (Warning Revocation & Consistency)
- **`public.user_warning_revocations`**: Bảng append-only lưu các sự kiện thu hồi cảnh báo (không xóa dòng cảnh báo gốc).
- **`public.admin_revoke_warning`**: Thu hồi cảnh báo idempotent kèm lý do, request ID và kiểm toán.
- **`public.admin_get_member_warnings`**: Đọc danh sách cảnh báo kèm trạng thái `is_revoked` và chi tiết thu hồi.
- **Cập nhật đồng bộ toàn bộ hàm đếm cảnh báo hiệu lực**:
  - `public.get_unread_warnings()`: Loại trừ các cảnh báo đã bị thu hồi.
  - `public.get_campaign_detail()`: Loại trừ các cảnh báo đã bị thu hồi khỏi `unread_warnings` và `weekly_warnings_count`.
  - `public.reject_tester_report()`: Loại trừ cảnh báo đã bị thu hồi khỏi ngưỡng tính khóa tài khoản theo tuần và streak từ chối trong ngày.
  - Mở khóa tài khoản là thao tác riêng biệt (`admin_set_user_lock`), không tự ý xóa lịch sử phạt nhiệm vụ ngày đã chốt.

### Nhóm 8: Giám sát vận hành thời gian thực & Retry chi trả nhiệm vụ
- **`public.admin_get_system_monitor_summary`**: Dữ liệu thực từ database tổng hợp số lượng thành viên (tổng, khóa, admin, mod), chiến dịch (mở, tạm dừng, hoàn tất), báo cáo tester (chờ, duyệt, từ chối), báo cáo khiếu nại liên hệ, hàng đợi công việc chi trả (`mission_settlement_jobs`), và lần dọn dẹp storage gần nhất (`cleanup_runs`). Không tạo dữ liệu giả trên thiết bị, không gọi trực tiếp GCS.
- **`public.admin_get_job_runs_queue`**: Hàng đợi công việc nền có phân trang (`mission_settlement` hoặc `cleanup`).
- **`public.admin_retry_mission_settlement_jobs`**: Wrapper retry công việc chi trả theo ID hoặc theo lô giới hạn (tối đa 50) có kiểm tra quyền admin, audit và request ID.

---

## 3. Lệnh kiểm thử và kết quả xác minh

Tất cả kiểm thử được chạy cục bộ trên môi trường Node.js / PGlite native (không sử dụng network live, không đụng tới secret production):

```bash
# 1. Chạy bộ kiểm thử hành vi quản trị viên cốt lõi mới (Stage A)
npm run test:admin-management

# 2. Chạy hồi quy các bộ kiểm thử liên quan
npm run test:admin-audit
node run-admin-daily-task-policy-test.mjs
npm run test:owner-tester-reports-count
npm run test:hardening
npm run test:carryover
node run-push-and-carryover-baseline062-regression-test.mjs
```

### Kết quả chạy kiểm thử thực tế:
- `test:admin-management`: **PASS 100%** (10 nhóm test bao phủ: từ chối quyền non-admin/anon/locked trên 18 RPC; capabilities; phân quyền admin_management và last admin lock; search >500 profiles; member overview; carryover conflict & cancel; campaign queue; credit compensation replay/mismatch; warning revocation consistency; monitor & retry audit).
- `test:admin-audit`: **PASS 100%**.
- `test:admin-daily-task-policy`: **PASS 100%**.
- `test:owner-tester-reports-count`: **PASS 100%**.
- `test:hardening`: **PASS 100%**.
- `test:carryover`: **PASS 100%**.
- `test:baseline062-regression`: **PASS 100%**.

---

## 4. Giới hạn hiện tại và hướng dẫn cho chặng kế tiếp (Stage B)

1. **Phạm vi hoàn tất**:
   - Chặng này tập trung và hoàn thành toàn bộ **Backend Quản Trị Cốt Lõi (Stage A Core)**.
   - Các worker thông báo (`notification campaigns`) và chính sách kinh doanh chi tiết (`business-policy`) là các chặng độc lập tiếp theo.
2. **Không sửa code Android trong lượt này**:
   - Không có thay đổi nào được thực hiện trong thư mục `app/` để giữ phạm vi chặng A hoàn toàn biệt lập và dễ review.
3. **Sẵn sàng cho Chặng B (Android UI & Integration)**:
   - Các ViewModel và Repository của Android có thể tích hợp trực tiếp theo hợp đồng đã định nghĩa chi tiết tại `audit/admin-expansion-2026-11-01/api-contract.md`.
   - Client nên gọi `admin_get_my_capabilities` khi mở màn hình quản trị để quyết định hiển thị/disable các nút thao tác đặc thù (như bổ nhiệm Quản trị viên).
   - Mọi thao tác điều chỉnh credit và đổi vai trò từ Android cần sinh UUIDv4 làm `p_request_id` để tránh lỗi gửi trùng khi mạng chập chờn.
