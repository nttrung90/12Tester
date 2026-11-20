# Hợp đồng API và DTO Quản Trị Viên (Admin Management Stage A)
Ngày lập: 01/11/2026
Phiên bản: 1.0.0-stage-a
Cơ sở dữ liệu đích: Supabase PostgreSQL (Migration `070_admin_management.sql`)

---

## 1. Quy ước chung
1. **Xác thực và phân quyền (Authorization)**:
   - Mọi RPC quản trị yêu cầu phiên xác thực (`auth.uid() IS NOT NULL`).
   - Yêu cầu tài khoản có `role = 'admin'` và `is_locked = false`. Bất kỳ vi phạm nào đều bị từ chối với lỗi `PERMISSION_DENIED` hoặc `NOT_AUTHENTICATED`.
   - Các mutation quan trọng yêu cầu `p_request_id` (UUIDv4) để chống double-submit và hỗ trợ replay idempotency.
   - Thao tác quản lý quyền Admin (`admin_set_user_role` thành `admin` hoặc từ `admin` thành vai trò khác) yêu cầu capability riêng `admin_management` trong bảng `admin_capabilities`.
2. **Quy ước phân trang (Cursor Pagination)**:
   - Các API danh sách trả về định dạng chuẩn:
     ```json
     {
       "items": [ ... ],
       "has_more": true,
       "next_cursor": {
         "created_at": "2026-11-01T10:00:00Z",
         "id": "11111111-1111-1111-1111-111111111111"
       }
     }
     ```
   - Khi `has_more` là `false`, `next_cursor` luôn là `null`.
   - Giới hạn trang `limit`: mặc định 50, giới hạn trong khoảng `1 <= limit <= 100` (đối với danh sách ngày nhiệm vụ: `1 <= limit <= 60`, mặc định 14).
   - Con trỏ sắp xếp cố định theo `(created_at DESC, id DESC)` hoặc `(task_date DESC)`.
3. **An toàn kiểu dữ liệu**:
   - Khóa chính, ID người dùng, ID chiến dịch, ID báo cáo dùng chuỗi UUID định dạng chuẩn.
   - Các ID bigint (như policy ID, revision) được chuyển đổi thành chuỗi hoặc số nguyên an toàn đối với Android / Kotlin / kotlinx.serialization.

---

## 2. Nhóm 1: Admin Action Audit & Capabilities

### 2.1 `admin_get_action_events`
Lấy danh sách nhật ký kiểm toán hành động quản trị viên có phân trang.

- **RPC Name**: `public.admin_get_action_events`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_cursor_created_at` (`timestamptz`, optional): Con trỏ thời gian tạo
  - `p_cursor_id` (`uuid`, optional): Con trỏ ID sự kiện
  - `p_limit` (`integer`, optional, default 50): Số lượng bản ghi (1..100)
  - `p_action` (`text`, optional): Lọc theo tên hành động (vd: `compensate_credits`, `set_user_role`, `revoke_warning`, v.v.)
  - `p_target_type` (`text`, optional): Lọc theo đối tượng (vd: `user`, `campaign`, `warning`, `policy`)
  - `p_actor_id` (`uuid`, optional): Lọc theo admin thực hiện
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "c1f7a0b3-...",
        "actor_id": "11111111-...",
        "actor_email": "admin@crosstest.local",
        "actor_name": "Quản trị viên 1",
        "action": "compensate_credits",
        "target_type": "user",
        "target_id": "22222222-...",
        "reason": "Bồi hoàn nhiệm vụ ngày 01/11",
        "old_state": { "credits": 50 },
        "new_state": { "credits": 60, "amount": 10 },
        "request_id": "99999999-...",
        "created_at": "2026-11-01T08:30:00Z"
      }
    ],
    "has_more": false,
    "next_cursor": null
  }
  ```

### 2.2 `admin_get_my_capabilities`
Kiểm tra danh sách quyền hạn quản trị đặc thù của phiên admin hiện tại.

- **RPC Name**: `public.admin_get_my_capabilities`
- **Caller Guard**: Unlocked Admin
- **Tham số**: Không có
- **Trả về**:
  ```json
  {
    "caller_id": "11111111-...",
    "role": "admin",
    "is_locked": false,
    "capabilities": ["admin_management"],
    "can_manage_admins": true,
    "can_manage_moderators": true
  }
  ```

---

## 3. Nhóm 2: Quản lý thành viên nâng cao & Tra cứu

### 3.1 `admin_search_members`
Tìm kiếm thành viên trên toàn bộ hệ thống (vượt giới hạn cũ 500 hồ sơ) với lọc vai trò và trạng thái khóa.

- **RPC Name**: `public.admin_search_members`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_query` (`text`, optional): Chuỗi tìm kiếm theo tên, email hoặc UUID
  - `p_role` (`text`, optional): Lọc vai trò (`member`, `moderator`, `admin`)
  - `p_is_locked` (`boolean`, optional): Lọc tài khoản bị khóa (`true`/`false`)
  - `p_limit` (`integer`, optional, default 50): Giới hạn bản ghi (1..100)
  - `p_cursor_created_at` (`timestamptz`, optional): Con trỏ ngày tạo
  - `p_cursor_id` (`uuid`, optional): Con trỏ ID
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "22222222-...",
        "email": "user@example.com",
        "display_name": "Nguyễn Văn A",
        "avatar_url": "https://...",
        "credits": 150,
        "role": "member",
        "is_locked": false,
        "created_at": "2026-10-15T02:00:00Z",
        "last_login_date": "2026-11-01",
        "daily_missed_streak": 0
      }
    ],
    "has_more": true,
    "next_cursor": {
      "created_at": "2026-10-15T02:00:00Z",
      "id": "22222222-..."
    }
  }
  ```

### 3.2 `admin_get_member_overview`
Lấy thông tin tổng hợp của một thành viên gồm chiến dịch, slot thực tế (max 6), cảnh báo và số lượng báo cáo.

- **RPC Name**: `public.admin_get_member_overview`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_user_id` (`uuid`, required): ID người dùng cần xem
- **Trả về**:
  ```json
  {
    "id": "22222222-...",
    "email": "user@example.com",
    "display_name": "Nguyễn Văn A",
    "avatar_url": "https://...",
    "credits": 150,
    "role": "member",
    "is_locked": false,
    "created_at": "2026-10-15T02:00:00Z",
    "last_login_date": "2026-11-01",
    "daily_missed_streak": 0,
    "campaigns_total": 3,
    "campaigns_open": 1,
    "slots_total": 2,
    "slots_active": 1,
    "slots": [
      {
        "slot_number": 1,
        "is_active": true,
        "unlocked_at": "2026-10-15T02:00:00Z",
        "expires_at": null,
        "current_campaign": {
          "id": "aaaaaaaa-...",
          "app_name": "Game Đua Xe",
          "package_name": "com.example.racing",
          "status": "open"
        }
      },
      {
        "slot_number": 2,
        "is_active": true,
        "unlocked_at": "2026-10-20T08:00:00Z",
        "expires_at": "2026-11-19T08:00:00Z",
        "current_campaign": null
      }
    ],
    "warnings_active": 1,
    "warnings_revoked": 0,
    "tester_reports_count": 14,
    "contact_reports_count": 2
  }
  ```

### 3.2b `admin_get_member_slots`
API chuyên biệt lấy chi tiết danh sách slot (tối đa 6 slot) và trạng thái chiến dịch đang gán vào từng slot của thành viên.

- **RPC Name**: `public.admin_get_member_slots`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_user_id` (`uuid`, required): ID người dùng
- **Trả về**:
  ```json
  {
    "user_id": "22222222-...",
    "slots": [ ... ],
    "slots_total": 2,
    "slots_active": 1
  }
  ```

### 3.3 `admin_get_member_credit_history`
Lấy lịch sử giao dịch credit của thành viên có phân trang.

- **RPC Name**: `public.admin_get_member_credit_history`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_user_id` (`uuid`, required): ID người dùng
  - `p_limit` (`integer`, optional, default 50): Giới hạn (1..100)
  - `p_cursor_created_at` (`timestamptz`, optional): Con trỏ thời gian
  - `p_cursor_id` (`uuid`, optional): Con trỏ ID
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "f5a2b1...",
        "user_id": "22222222-...",
        "amount": 2,
        "action": "report_provisional_reward",
        "note": "Tạm cấp 2 Credit khi gửi báo cáo",
        "balance": 152,
        "created_at": "2026-11-01T07:15:00Z",
        "metadata": null
      }
    ],
    "has_more": false,
    "next_cursor": null
  }
  ```

### 3.4 `admin_get_member_daily_tasks`
Lấy lịch sử và giải thích chi tiết trạng thái nhiệm vụ ngày của người dùng (tái sử dụng công thức nội bộ `get_daily_task_status`).

- **RPC Name**: `public.admin_get_member_daily_tasks`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_user_id` (`uuid`, required): ID người dùng
  - `p_limit` (`integer`, optional, default 14): Số ngày cần truy vấn (1..60)
  - `p_cursor_date` (`date`, optional): Con trỏ ngày bắt đầu truy vấn lùi về trước
- **Trả về**:
  ```json
  {
    "items": [
      {
        "task_date": "2026-11-01",
        "is_finalized": false,
        "finalized_at": null,
        "penalty_credits": 0,
        "incomplete_history": false,
        "status": {
          "status_date": "2026-11-01",
          "mode": "member",
          "active_app_count": 2,
          "required_tests": 6,
          "effective_required_tests": 6,
          "completed_tests": 4,
          "available_apps": 5,
          "logged_in_today": true,
          "is_penalty_exempt": false,
          "missed_streak": 0,
          "met": false,
          "carry_in_tests": 0,
          "carryover_remaining_tests": 2,
          "carryover_ready": true,
          "history_ready": true
        }
      }
    ],
    "has_more": true,
    "next_cursor": {
      "date": "2026-10-18"
    }
  }
  ```

---

## 4. Nhóm 3: Quản lý chính sách cộng dồn nhiệm vụ (Carryover)

### 4.1 `admin_get_carryover_policies`
Lấy trạng thái chính sách cộng dồn hiện hành, các chính sách tương lai đã lên lịch (giới hạn 50) và lịch sử chính sách quá khứ (giới hạn 50).

- **RPC Name**: `public.admin_get_carryover_policies`
- **Caller Guard**: Unlocked Admin
- **Tham số**: Không có
- **Trả về**:
  ```json
  {
    "active": {
      "id": "1",
      "effective_from": "2026-10-01",
      "is_enabled": true,
      "operator_note": "Chính sách ban đầu",
      "created_at": "2026-10-01T00:00:00Z"
    },
    "future": [
      {
        "id": "2",
        "effective_from": "2026-11-05",
        "is_enabled": false,
        "operator_note": "Tạm tắt cộng dồn bảo trì",
        "created_at": "2026-11-01T09:00:00Z"
      }
    ],
    "history": [
      {
        "id": "1",
        "effective_from": "2026-10-01",
        "is_enabled": true,
        "operator_note": "Chính sách ban đầu",
        "created_at": "2026-10-01T00:00:00Z"
      }
    ],
    "revision": "4"
  }
  ```

### 4.1b `admin_get_carryover_policy_history`
API phân trang sâu cho toàn bộ nhật ký thay đổi chính sách cộng dồn, bao gồm cờ phân biệt sự kiện hủy lịch (`is_cancelled: true`).

- **RPC Name**: `public.admin_get_carryover_policy_history`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_limit` (`integer`, optional, default 50): Giới hạn bản ghi (1..100)
  - `p_cursor_id` (`bigint`, optional): Con trỏ ID kiểm toán lùi về trước
  - `p_include_cancelled` (`boolean`, optional, default true): Có bao gồm các sự kiện hủy hay không
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": 5,
        "policy_id": 2,
        "effective_from": "2026-11-05",
        "old_is_enabled": false,
        "new_is_enabled": false,
        "old_operator_note": "Tạm tắt cộng dồn bảo trì",
        "new_operator_note": "CANCELLED: Không bảo trì nữa",
        "is_cancelled": true,
        "clean_note": "Không bảo trì nữa",
        "changed_by": "admin:11111111-...",
        "changed_at": "2026-11-01T10:00:00Z"
      }
    ],
    "has_more": false,
    "next_cursor": null
  }
  ```

### 4.1c `admin_list_future_carryover_policies`
API phân trang chuyên biệt cho danh sách chính sách cộng dồn trong tương lai (`> VN today`), giải quyết trường hợp có trên 50 lịch hẹn tương lai.

- **RPC Name**: `public.admin_list_future_carryover_policies`
- **Caller Guard**: Unlocked Admin (Read-only, không ghi audit)
- **Tham số**:
  - `p_limit` (`integer`, optional, default 50): Giới hạn bản ghi (1..100)
  - `p_cursor_date` (`date`, optional): Con trỏ ngày hiệu lực (`> cursor_date`), sắp xếp `effective_from ASC, id ASC`
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "101",
        "effective_from": "2026-11-05",
        "is_enabled": false,
        "operator_note": "Bảo trì",
        "created_at": "2026-11-01T10:00:00Z"
      }
    ],
    "has_more": true,
    "next_cursor": {
      "date": "2026-11-05"
    }
  }
  ```

### 4.2 `admin_schedule_carryover_policy`
Lên lịch chính sách cộng dồn từ một ngày tương lai (phải lớn hơn ngày hôm nay theo giờ Việt Nam).
*Lưu ý Idempotency*: Khi truyền `p_request_id`, kiểm tra replay được thực hiện **TRƯỚC** kiểm tra CAS revision, đảm bảo retry với cùng `p_expected_revision` gốc vẫn thành công trả về kết quả ban đầu kèm `"is_replay": true`.

- **RPC Name**: `public.admin_schedule_carryover_policy`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_effective_from` (`date`, required): Ngày bắt đầu áp dụng (`> VN today`)
  - `p_is_enabled` (`boolean`, required): Bật (`true`) hoặc tắt (`false`)
  - `p_operator_note` (`text`, required): Ghi chú lý do của quản trị viên (1..1000 ký tự)
  - `p_expected_revision` (`bigint`, optional): Phiên bản revision hiện tại để chống xung đột ghi đè
  - `p_request_id` (`uuid`, optional): Mã định danh yêu cầu chống trùng

### 4.3 `admin_cancel_future_carryover_policy`
Hủy một chính sách tương lai đã lên lịch trước đó. Hỗ trợ replay idempotency trước CAS check và ghi nhận nhật ký hủy rõ ràng.

- **RPC Name**: `public.admin_cancel_future_carryover_policy`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_effective_from` (`date`, required): Ngày hiệu lực cần hủy (`> VN today`)
  - `p_reason` (`text`, required): Lý do hủy bỏ (1..1000 ký tự)
  - `p_expected_revision` (`bigint`, optional): Revision dự kiến
  - `p_request_id` (`uuid`, optional): Mã định danh yêu cầu
- **Trả về**:
  ```json
  {
    "success": true,
    "cancelled_date": "2026-11-05",
    "reason": "Hủy bỏ lịch tắt bảo trì",
    "revision": "6"
  }
  ```
- **Lỗi có thể xảy ra**:
  - `POLICY_NOT_FOUND`: Không tìm thấy chính sách tương lai ứng với ngày đã cho
  - `CANNOT_CANCEL_NON_FUTURE_POLICY`: Không được hủy chính sách hiện hành hoặc quá khứ

---

## 5. Nhóm 4: Quản lý chiến dịch tập trung & Hàng đợi báo cáo

### 5.1 `admin_search_campaigns`
Tìm kiếm và duyệt danh sách chiến dịch mọi trạng thái (`open`, `paused`, `completed`, v.v.).

- **RPC Name**: `public.admin_search_campaigns`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_query` (`text`, optional): Tìm theo tên ứng dụng, package name hoặc UUID
  - `p_status` (`text`, optional): Lọc theo trạng thái
  - `p_owner_id` (`uuid`, optional): Lọc theo chủ ứng dụng
  - `p_limit` (`integer`, optional, default 50): Giới hạn bản ghi (1..100)
  - `p_cursor_created_at` (`timestamptz`, optional): Con trỏ ngày tạo
  - `p_cursor_id` (`uuid`, optional): Con trỏ ID
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "aaaaaaaa-...",
        "owner_id": "22222222-...",
        "owner_name": "Nguyễn Văn A",
        "owner_email": "user@example.com",
        "app_name": "Game Đua Xe",
        "package_name": "com.example.racing",
        "status": "open",
        "target_testers": 12,
        "current_testers": 8,
        "paused_reason": null,
        "created_at": "2026-10-20T05:00:00Z",
        "pending_reports_count": 3
      }
    ],
    "has_more": false,
    "next_cursor": null
  }
  ```

### 5.2 `admin_get_tester_reports_queue`
Hàng đợi duyệt báo cáo kiểm thử toàn hệ thống gom từ nhiều chiến dịch với bộ lọc đa chiều.

- **RPC Name**: `public.admin_get_tester_reports_queue`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_status` (`text`, optional, default `'pending'`): Trạng thái (`pending`, `approved`, `rejected` hoặc null cho tất cả)
  - `p_campaign_id` (`uuid`, optional): Lọc theo chiến dịch
  - `p_tester_id` (`uuid`, optional): Lọc theo tester gửi
  - `p_owner_id` (`uuid`, optional): Lọc theo chủ sở hữu app
  - `p_submission_context` (`text`, optional): Lọc theo ngữ cảnh (`app_test`, `mission`, ...)
  - `p_limit` (`integer`, optional, default 50): Giới hạn (1..100)
  - `p_cursor_created_at` (`timestamptz`, optional): Con trỏ thời gian
  - `p_cursor_id` (`uuid`, optional): Con trỏ ID
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "bbbbbbbb-...",
        "campaign_id": "aaaaaaaa-...",
        "campaign_name": "Game Đua Xe",
        "package_name": "com.example.racing",
        "owner_id": "22222222-...",
        "owner_name": "Nguyễn Văn A",
        "tester_id": "44444444-...",
        "tester_name": "Tester B",
        "tester_email": "tester@example.com",
        "report_type": "feedback",
        "content": "Ứng dụng mượt mà, không gặp lỗi",
        "screenshot_url": "https://...",
        "thumbnail_url": "https://...",
        "media_expired": false,
        "rating": 5,
        "status": "pending",
        "submission_context": "app_test",
        "mission_title": null,
        "created_at": "2026-11-01T06:00:00Z",
        "reviewed_at": null,
        "rejection_reason": null
      }
    ],
    "has_more": true,
    "next_cursor": {
      "created_at": "2026-11-01T06:00:00Z",
      "id": "bbbbbbbb-..."
    }
  }
  ```

---

## 6. Nhóm 5: Bồi hoàn Credit an toàn (Credit Compensation)

### 6.1 `admin_compensate_credits`
Thực hiện bồi hoàn hoặc điều chỉnh credit kèm Request ID chống trùng lặp và liên kết giao dịch/khiếu nại gốc.

- **RPC Name**: `public.admin_compensate_credits`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_request_id` (`uuid`, required): Mã UUID yêu cầu duy nhất từ client
  - `p_target_user_id` (`uuid`, required): ID người dùng nhận bồi hoàn
  - `p_amount` (`integer`, required): Số credit điều chỉnh (khác 0)
  - `p_reason` (`text`, required): Lý do bồi hoàn cụ thể
  - `p_original_credit_log_id` (`uuid`, optional): ID giao dịch credit gốc liên quan
  - `p_contact_report_id` (`uuid`, optional): ID báo cáo khiếu nại liên quan
- **Trả về**:
  ```json
  {
    "success": true,
    "request_id": "99999999-...",
    "user_id": "22222222-...",
    "previous_credits": 100,
    "new_credits": 120,
    "amount": 20,
    "reason": "Bồi hoàn báo cáo bị trừ sai ngày 30/10",
    "original_credit_log_id": null,
    "contact_report_id": null,
    "is_replay": false
  }
  ```
- **Hành vi Idempotency**:
  - Nếu gửi lại cùng `p_request_id` và cùng bộ tham số payload: Trả về kết quả ban đầu với `is_replay: true`.
  - Nếu gửi lại cùng `p_request_id` nhưng khác tham số: Báo lỗi `REQUEST_ID_PAYLOAD_MISMATCH`.

---

## 7. Nhóm 6: Phân quyền & Quản lý vai trò (Role Management)

### 7.1 `admin_set_user_role`
Thay đổi vai trò người dùng (`member`, `moderator`, `admin`).

- **RPC Name**: `public.admin_set_user_role`
- **Caller Guard**: Unlocked Admin (và capability `admin_management` nếu liên quan tới vai trò `admin`)
- **Tham số**:
  - `p_target_user_id` (`uuid`, required): ID người dùng cần đổi vai trò
  - `p_new_role` (`text`, required): Vai trò mới (`member`, `moderator`, `admin`)
  - `p_reason` (`text`, required): Lý do thay đổi vai trò
  - `p_request_id` (`uuid`, optional): Mã yêu cầu chống trùng
- **Trả về**:
  ```json
  {
    "success": true,
    "user_id": "33333333-...",
    "previous_role": "member",
    "new_role": "moderator",
    "reason": "Bổ nhiệm điều hành viên hỗ trợ duyệt báo cáo"
  }
  ```
- **Ràng buộc an toàn**:
  - `CANNOT_CHANGE_OWN_ROLE`: Admin không được tự đổi vai trò của chính mình.
  - `INSUFFICIENT_PRIVILEGE`: Thao tác bổ nhiệm hoặc hạ quyền `admin` đòi hỏi capability `admin_management`.
  - `CANNOT_REMOVE_LAST_ADMIN`: Không thể hạ quyền Admin nếu đây là Admin chưa bị khóa cuối cùng của hệ thống.
  - `USER_NOT_FOUND`: Người dùng mục tiêu không tồn tại.

---

## 8. Nhóm 7: Xử lý & Thu hồi cảnh báo (Warning Management)

### 8.1 `admin_get_member_warnings`
Lấy danh sách cảnh báo của thành viên, kèm trạng thái đã bị thu hồi hay chưa.

- **RPC Name**: `public.admin_get_member_warnings`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_user_id` (`uuid`, required): ID người dùng
  - `p_limit` (`integer`, optional, default 50): Giới hạn (1..100)
  - `p_cursor_created_at` (`timestamptz`, optional): Con trỏ thời gian
  - `p_cursor_id` (`uuid`, optional): Con trỏ ID
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "e4d3c2...",
        "user_id": "22222222-...",
        "warning_type": "tester_rejection_streak",
        "message": "Bạn có 3 báo cáo bị từ chối liên tiếp",
        "warning_date": "2026-10-31",
        "created_at": "2026-10-31T14:00:00Z",
        "acknowledged": false,
        "is_revoked": true,
        "revocation": {
          "revoked_at": "2026-11-01T08:00:00Z",
          "revoked_by": "11111111-...",
          "reason": "Báo cáo bị từ chối oan do lỗi ứng dụng chủ app"
        }
      }
    ],
    "has_more": false,
    "next_cursor": null
  }
  ```

### 8.2 `admin_revoke_warning`
Thu hồi một cảnh báo (ghi nhận vào bảng append-only `user_warning_revocations`, không xóa bản ghi gốc).

- **RPC Name**: `public.admin_revoke_warning`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_warning_id` (`uuid`, required): ID cảnh báo cần thu hồi
  - `p_reason` (`text`, required): Lý do thu hồi
  - `p_request_id` (`uuid`, optional): Mã yêu cầu
- **Trả về**:
  ```json
  {
    "success": true,
    "warning_id": "e4d3c2...",
    "revoked": true,
    "reason": "Báo cáo bị từ chối oan do lỗi ứng dụng chủ app"
  }
  ```
- **Ràng buộc nhất quán**:
  - Khi một cảnh báo bị thu hồi, mọi phép đếm cảnh báo hiệu lực (`get_unread_warnings`, đếm hàng tuần trong `get_campaign_detail`, và đếm ngưỡng khóa tài khoản trong `reject_tester_report`) lập tức loại trừ cảnh báo đó.
  - Việc thu hồi cảnh báo không tự động mở khóa tài khoản; việc mở khóa phải là một thao tác rõ ràng riêng biệt qua `admin_set_user_lock`.

---

## 9. Nhóm 8: Giám sát vận hành & Xử lý công việc lỗi (System Monitor)

### 9.1 `admin_get_system_monitor_summary`
Tổng quan vận hành thời gian thực từ dữ liệu bền vững trên cơ sở dữ liệu.

- **RPC Name**: `public.admin_get_system_monitor_summary`
- **Caller Guard**: Unlocked Admin
- **Tham số**: Không có
- **Trả về**:
  ```json
  {
    "members": {
      "total": 1250,
      "locked": 12,
      "admins": 3,
      "moderators": 5
    },
    "campaigns": {
      "open": 45,
      "paused": 6,
      "completed": 120,
      "total": 171
    },
    "tester_reports": {
      "pending": 28,
      "approved": 3400,
      "rejected": 112
    },
    "contact_reports": {
      "pending": 4,
      "resolved": 89
    },
    "mission_settlement_jobs": {
      "pending": 2,
      "failed": 1,
      "completed": 450,
      "skipped": 5
    },
    "cleanup_runs": {
      "last_run": {
        "id": "11223344-...",
        "job_name": "storage_cleanup",
        "status": "success",
        "started_at": "2026-11-01T03:00:00Z",
        "finished_at": "2026-11-01T03:05:00Z",
        "scanned": 120,
        "deleted": 15,
        "error": null
      }
    },
    "timestamp": "2026-11-01T10:00:00Z"
  }
  ```

### 9.2 `admin_get_job_runs_queue`
Lấy danh sách các công việc nền có phân trang (chỉ hỗ trợ các loại công việc có thực thể trong database: `mission_settlement` hoặc `cleanup`).

- **RPC Name**: `public.admin_get_job_runs_queue`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_job_type` (`text`, optional, default `'mission_settlement'`): Loại tác vụ (`mission_settlement` hoặc `cleanup`)
  - `p_status` (`text`, optional): Lọc trạng thái (`pending`, `failed`, `completed`, `skipped`)
  - `p_limit` (`integer`, optional, default 50): Giới hạn (1..100)
  - `p_cursor_created_at` (`timestamptz`, optional): Con trỏ thời gian
  - `p_cursor_id` (`uuid`, optional): Con trỏ ID
- **Trả về**:
  ```json
  {
    "items": [
      {
        "id": "77889900-...",
        "report_id": "bbbbbbbb-...",
        "mission_id": "55667788-...",
        "campaign_id": "aaaaaaaa-...",
        "tester_id": "44444444-...",
        "reward_credits": 10,
        "status": "failed",
        "attempt_count": 3,
        "next_attempt_at": "2026-11-01T10:30:00Z",
        "last_error": "CREDIT_OVERFLOW",
        "created_at": "2026-11-01T08:00:00Z"
      }
    ],
    "has_more": false,
    "next_cursor": null
  }
  ```

### 9.3 `admin_retry_mission_settlement_jobs`
Thử lại việc chi trả thưởng nhiệm vụ bị lỗi theo ID cụ thể hoặc theo lô giới hạn.

- **RPC Name**: `public.admin_retry_mission_settlement_jobs`
- **Caller Guard**: Unlocked Admin
- **Tham số**:
  - `p_job_id` (`uuid`, optional): ID công việc cụ thể cần retry (nếu null, retry theo lô)
  - `p_batch_limit` (`integer`, optional, default 20): Số lượng tối đa trong lô retry (1..50)
  - `p_request_id` (`uuid`, optional): Mã yêu cầu chống lặp
- **Trả về**:
  ```json
  {
    "success": true,
    "job_id": "77889900-...",
    "processed_count": 1,
    "success_count": 1
  }
  ```
