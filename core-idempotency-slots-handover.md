# Báo cáo bàn giao Idempotency, Slot Details & Bounded Carryover History
(Core Idempotency & Slots Handover — Stage A)

**Chặng thực hiện**: Chặng A Review — Idempotency Replay trước CAS, Chi tiết Slot thực tế và Phân trang Carryover  
**Model**: `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Workspace**: `F:/AI/12Tester`  
**Ngày thực hiện**: 01/11/2026  
**Trạng thái**: Hoàn tất 100%, tất cả test suite PGlite và probe độc lập vượt qua.

---

## 1. Tóm tắt các hạng mục đã hoàn thiện

### 1.1 Chuẩn hóa Request-ID Idempotency & Replay trước CAS Validation
- **Các hàm áp dụng**:
  - `public.admin_schedule_carryover_policy`
  - `public.admin_cancel_future_carryover_policy`
  - `public.admin_revoke_warning`
  - `public.admin_retry_mission_settlement_jobs`
  - `public.admin_set_user_role`
  - `public.admin_compensate_credits`
- **Nguyên lý kiến trúc chuẩn**:
  1. Kiểm tra xác thực admin (`assert_unlocked_admin`) đầu tiên.
  2. Lấy khóa advisory tuần tự hóa phạm vi yêu cầu: `hashtext(v_caller_id::text || ':' || v_operation || ':' || p_request_id::text)`.
  3. Băm payload canonical bằng JSONB có cấu trúc (`md5(jsonb_build_object(...)::text)`), loại bỏ hoàn toàn việc nối chuỗi thô dễ xung đột.
  4. **Kiểm tra replay TRƯỚC kiểm tra CAS revision**: Nếu cùng `p_request_id` và cùng payload, lập tức trả về kết quả ban đầu kèm `"is_replay": true`. Nhờ vậy, client retry với cùng `p_expected_revision` ban đầu vẫn thành công mà không bị lỗi `REVISION_CONFLICT`.
  5. Nếu `p_request_id` bị tái sử dụng với tham số khác: Báo lỗi fail-closed `REQUEST_ID_PAYLOAD_MISMATCH`.

### 1.2 Bounded Carryover Policies & Lịch sử sự kiện Hủy chính sách
- **`public.admin_get_carryover_policies`**:
  - Giới hạn mảng `future` tối đa 50 bản ghi và `history` tối đa 50 bản ghi, chống quá tải khi dữ liệu lớn.
  - Giữ nguyên cấu trúc trả về 100% tương thích cho Android: `{ active, future, history, revision }`.
- **`public.admin_get_carryover_policy_history(p_limit, p_cursor_id, p_include_cancelled)` (Mới)**:
  - RPC phân trang keyset theo `id DESC` từ bảng `daily_task_carryover_policy_audit`.
  - Phân biệt rõ sự kiện hủy chính sách (`is_cancelled: true`) và trích xuất lý do hủy sạch (`clean_note`).

### 1.3 Chi tiết Slot thực tế cho Thành viên (`max 6 slots`)
- **`public.admin_get_member_overview`**:
  - Bổ sung trường `'slots'` dạng mảng đầy đủ thông tin thực tế của các slot thay vì chỉ có số đếm:
    - `slot_number`: 1..6.
    - `is_active`: Slot 1 luôn true; slot 2..6 dựa trên `expires_at > now()`.
    - `unlocked_at`, `expires_at`.
    - `current_campaign`: Thông tin chiến dịch đang gán vào slot (`id`, `app_name`, `package_name`, `status`) hoặc `null`.
- **`public.admin_get_member_slots(p_user_id)` (Mới)**:
  - RPC chuyên biệt trả về danh sách slot thực tế và số lượng cho màn hình chi tiết thành viên.
  - Đã xác minh qua `independent-core-probe.mjs` đạt `"hasSlotsArray": true`.

---

## 2. Kết quả kiểm thử & xác minh

Tất cả kiểm thử được chạy cục bộ native Node.js / PGlite:

1. **`node audit/admin-expansion-2026-11-01/independent-core-probe.mjs`**:
   - `campaign detail preserves full DTO keys`: **PASS** (`missing: [], added: []`)
   - `warning revocation consistent on dashboard and detail`: **PASS** (`dashboardWarnings: 0, dashboardUnread: 0, detailWarnings: 0`)
   - `carryover schedule and cancellation succeed`: **PASS** (`scheduled: { success: true }, cancelled: { success: true }`)
   - `member overview has actual slot data`: **PASS** (`hasSlotsArray: true`)

2. **`npm run test:admin-management`**: **PASS 100%**
   - Xác minh replay trước CAS validation cho carryover schedule/cancel, warning revoke, credit compensation và retry jobs.
   - Xác minh bắt lỗi `REQUEST_ID_PAYLOAD_MISMATCH` khi đổi payload.
   - Xác minh chi tiết mảng slots trong overview và RPC `admin_get_member_slots`.
   - Xác minh phân trang `admin_get_carryover_policy_history` với cờ `is_cancelled`.

3. **`npm run test:admin-audit`**: **PASS 100%** (zero regression).

---

## 3. Tệp tin đã chỉnh sửa trong lượt này

- `supabase/migrations/070_admin_management.sql`:
  - Cập nhật `admin_get_member_overview` bổ sung mảng `slots`.
  - Thêm RPC `admin_get_member_slots`.
  - Giới hạn 50 bản ghi trong `admin_get_carryover_policies` và thêm RPC `admin_get_carryover_policy_history`.
  - Chuẩn hóa request scope lock, canonical jsonb hash và replay trước CAS cho `admin_schedule_carryover_policy`, `admin_cancel_future_carryover_policy`, `admin_revoke_warning`, `admin_retry_mission_settlement_jobs`, `admin_compensate_credits`, `admin_set_user_role`.
- `supabase/tests/run-admin-management-test.mjs`: Thêm test hồi quy cho request-id replay trước CAS, payload mismatch, slot array, và carryover history pagination.
- `audit/admin-expansion-2026-11-01/api-contract.md`: Cập nhật đặc tả DTO slot array, RPC `admin_get_member_slots`, `admin_get_carryover_policy_history` và cơ chế replay.
- `core-idempotency-slots-handover.md`: Báo cáo bàn giao tại thư mục gốc workspace.
- `audit/admin-expansion-2026-11-01/core-idempotency-slots-handover.md`: Bản lưu báo cáo bàn giao.

*Phạm vi giữ nguyên*: Mã Android trong `app/`, migration `071`, `072` hoàn toàn không bị đụng tới. Lượt tiếp theo sẽ xử lý kiểm toán các mutation admin cũ và khóa điều phối vai trò/khóa tài khoản.
