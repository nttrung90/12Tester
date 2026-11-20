# Hợp đồng API và Đặc tả Chính sách Nghiệp vụ (Business Policy Contract)

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-stage/business-api-contract.md`  
**Chặng:** C — Business Policy Backend  
**Ngày:** 01/11/2026  
**Mục tiêu:** Cung cấp hạ tầng chính sách nghiệp vụ có phiên bản (versioned), lập lịch hiệu lực tương lai theo giờ Việt Nam (Asia/Ho_Chi_Minh 00:00), kiểm tra xung đột CAS (revision), audit bất biến, chống xử lý lặp request ID và tiêu thụ toàn diện xuyên suốt mọi nhánh tính toán tài chính của backend.

---

## 1. Kiến trúc lưu trữ và Quản trị Chính sách

### 1.1 Bảng `business_policies`
Bảng lưu trữ chính sách với các khóa cố định đã được kiểm định kiểu và giới hạn hợp lệ (không dùng JSON tùy ý):

| Cột | Kiểu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `bigint` | Generated Always as Identity, PK | Định danh chính sách |
| `effective_from` | `date` | NOT NULL, UNIQUE | Ngày hiệu lực (giờ VN). Giá trị baseline là `'-infinity'::date` |
| `welcome_credits` | `integer` | NOT NULL, CHECK (0..10000) | Credit thưởng mở tài khoản mới (mặc định 100) |
| `penalty_tier1_pct` | `integer` | NOT NULL, CHECK (0..100) | Tỷ lệ phạt ngày 1 bỏ lỡ (mặc định 1%) |
| `penalty_tier2_pct` | `integer` | NOT NULL, CHECK (0..100) | Tỷ lệ phạt ngày 2 bỏ lỡ (mặc định 2%) |
| `penalty_tier3_pct` | `integer` | NOT NULL, CHECK (0..100) | Tỷ lệ phạt ngày 3 bỏ lỡ (mặc định 5%) |
| `penalty_tier4_pct` | `integer` | NOT NULL, CHECK (0..100) | Tỷ lệ phạt ngày >= 4 bỏ lỡ (mặc định 10%) |
| `push_fee` | `integer` | NOT NULL, CHECK (1..10000) | Phí đẩy ứng dụng lên đầu danh sách (mặc định 100) |
| `slot_fee_2` | `integer` | NOT NULL, CHECK (1..10000) | Phí mở/gia hạn Slot 2 (mặc định 50) |
| `slot_fee_3` | `integer` | NOT NULL, CHECK (1..10000) | Phí mở/gia hạn Slot 3 (mặc định 100) |
| `slot_fee_4_plus` | `integer` | NOT NULL, CHECK (1..10000) | Phí mở/gia hạn Slot 4, 5, 6 (mặc định 200) |
| `slot_renewal_days`| `integer` | NOT NULL, CHECK (1..365) | Thời hạn gia hạn mỗi lượt (mặc định 30 ngày) |
| `report_provisional_reward` | `integer` | NOT NULL, CHECK (0..1000) | Thưởng tạm tính khi nộp báo cáo kiểm thử (mặc định 10) |
| `report_review_tester_bonus` | `integer` | NOT NULL, CHECK (0..1000) | Thưởng duyệt thủ công cho tester (mặc định 2) |
| `report_review_owner_bonus` | `integer` | NOT NULL, CHECK (0..1000) | Thưởng duyệt thủ công cho chủ app thật (mặc định 2, staff duyệt = 0) |
| `app_update_reward_per_claim` | `integer` | NOT NULL, CHECK (1..1000) | Thưởng cập nhật ứng dụng mỗi lượt claim (mặc định 6) |
| `app_update_max_claims` | `integer` | NOT NULL, CHECK (1..1000) | Số lượt nhận thưởng tối đa mỗi đợt update (mặc định 20) |
| `operator_note` | `text` | NOT NULL | Ghi chú của quản trị viên khi đặt lịch |
| `created_at` | `timestamptz` | NOT NULL, DEFAULT clock_timestamp() | Thời điểm tạo |
| `updated_at` | `timestamptz` | NOT NULL, DEFAULT clock_timestamp() | Thời điểm cập nhật |

**Quy tắc phân tầng phạt:** Bắt buộc `0 <= tier1 <= tier2 <= tier3 <= tier4 <= 100`.  
**Ngân sách giữ trước của đợt cập nhật app:** Tự động dẫn xuất theo công thức tích `reserved_budget = reward_per_claim * max_claims` (mặc định `6 * 20 = 120`).

### 1.2 Bảng Audit `business_policy_changes`
Lưu vết toàn bộ biến động chính sách (INSERT, UPDATE, DELETE). Chỉ backend và trigger nội bộ được quyền ghi:
- RLS kích hoạt, thu hồi toàn bộ quyền từ `PUBLIC`, `anon`, `authenticated`.
- Trigger chống sửa (`BEFORE UPDATE OR DELETE`), chống xóa sạch (`BEFORE TRUNCATE`).
- Lưu trữ: `policy_id`, `operation`, `old_row`, `new_row`, `operator_note`, `actor_id` (auth.uid()), `session_role`, `invoked_role`, `changed_at`.

### 1.3 Quản lý xung đột CAS qua `revision`
Hàm `_get_business_policy_revision()` tạo mã băm MD5 duy nhất dựa trên toàn bộ các dòng chính sách trong bảng `business_policies`. Bất kỳ thao tác lập lịch hoặc hủy lịch từ phía Admin đều bắt buộc truyền `p_expected_revision`. Nếu revision không khớp, backend từ chối với lỗi `BUSINESS_POLICY_CONFLICT`.

---

### 1.4 Bảng Idempotency Request Ledgers
1. **`business_policy_admin_requests`**:
   Lưu vết idempotency cho các thao tác lập lịch và hủy lịch của Admin:
   - Cột: `id uuid`, `actor_id uuid`, `operation text` (schedule/cancel), `payload_hash text`, `result jsonb`, `created_at timestamptz`.
   - Khóa chính: `(actor_id, operation, id)`.
   - Kiểm tra replay: Xác thực quyền trước -> kiểm tra replay trước CAS. Nếu cùng `id` và cùng `payload_hash`, trả lại kết quả cũ an toàn (kể cả khi revision sau đó đã đổi). Nếu khác `payload_hash`, từ chối với lỗi `REQUEST_ID_CONFLICT`.
2. **`slot_purchase_receipts`**:
   Biên lai tài chính và sổ cái chống replay cho thao tác mở/gia hạn slot:
   - Cột: `id uuid`, `user_id uuid`, `client_request_id uuid`, `slot_number integer`, `cost_credits integer`, `balance_after integer`, `expires_at timestamptz`, `created_at timestamptz`.
   - Ràng buộc: `UNIQUE (user_id, client_request_id)`.
   - RLS: Người dùng chỉ được SELECT receipt của chính mình (`user_id = auth.uid()`).

---

## 2. API Quản trị (Admin RPCs)

Mọi RPC trong nhóm này được bảo vệ bởi hàm `public._assert_daily_task_admin()` (yêu cầu role `admin` và tài khoản không bị khóa).

### 2.1 `admin_get_business_policy_config(p_future_limit integer DEFAULT 20)`
Đọc trạng thái cấu hình hiện tại, bao gồm chính sách đang kích hoạt, các chính sách tương lai được phân trang và revision hiện thời.
- **Quyền:** Admin
- **Tham số:** `p_future_limit integer DEFAULT 20` (1..100)
- **Kết quả trả về:**
```json
{
  "today": "2026-11-01",
  "timezone": "Asia/Ho_Chi_Minh",
  "revision": "e5b8c73491f68...",
  "active_policy": {
    "id": 1,
    "effective_from": null,
    "is_baseline": true,
    "welcome_credits": 100,
    "daily_penalty_tiers": {
      "tier1_pct": 1,
      "tier2_pct": 2,
      "tier3_pct": 5,
      "tier4_pct": 10
    },
    "push_fee": 100,
    "slot_fees": {
      "slot_2": 50,
      "slot_3": 100,
      "slot_4_plus": 200,
      "renewal_days": 30
    },
    "report_rewards": {
      "provisional_reward": 10,
      "review_tester_bonus": 2,
      "review_owner_bonus": 2
    },
    "app_update_rewards": {
      "reward_per_claim": 6,
      "max_claims": 20,
      "reserved_budget": 120
    },
    "operator_note": "Baseline default business policy",
    "updated_at": "2026-11-01T00:00:00Z"
  },
  "future_policies": [],
  "future_has_more": false,
  "future_next_cursor": null
}
```

### 2.2 `admin_schedule_business_policy(...)`
Lập lịch chính sách mới cho một ngày trong tương lai (theo giờ Việt Nam: `current_date_vn < effective_from <= current_date_vn + 3650 days`).
- **Quyền:** Admin
- **Tham số:**
  - `p_effective_from date` (bắt buộc, hữu hạn trong vòng 10 năm)
  - `p_operator_note text` (1..1000 ký tự)
  - `p_expected_revision text` (bắt buộc để kiểm tra CAS khi ghi mới)
  - `p_welcome_credits integer DEFAULT 100`
  - `p_penalty_tier1_pct integer DEFAULT 1`
  - `p_penalty_tier2_pct integer DEFAULT 2`
  - `p_penalty_tier3_pct integer DEFAULT 5`
  - `p_penalty_tier4_pct integer DEFAULT 10`
  - `p_push_fee integer DEFAULT 100`
  - `p_slot_fee_2 integer DEFAULT 50`
  - `p_slot_fee_3 integer DEFAULT 100`
  - `p_slot_fee_4_plus integer DEFAULT 200`
  - `p_slot_renewal_days integer DEFAULT 30`
  - `p_report_provisional_reward integer DEFAULT 10`
  - `p_report_review_tester_bonus integer DEFAULT 2`
  - `p_report_review_owner_bonus integer DEFAULT 2`
  - `p_app_update_reward_per_claim integer DEFAULT 6`
  - `p_app_update_max_claims integer DEFAULT 20`
  - `p_request_id uuid DEFAULT NULL::uuid` (tùy chọn nhưng khuyến nghị để chống xử lý lặp)
- **Xử lý:**
  - Kiểm tra quyền admin.
  - Kiểm tra replay request ID trước khi kiểm tra CAS.
  - Lấy advisory lock xact `business_policy_schedule`.
  - Kiểm tra revision CAS.
  - Kiểm tra ngày tương lai hữu hạn.
  - Cập nhật dòng chính sách.
  - Tự động ghi audit log nội bộ và kích hoạt audit 070 (`record_admin_action_event`) nếu có.
- **Kết quả trả về:** DTO `admin_get_business_policy_config()`.

### 2.3 `admin_cancel_business_policy(p_effective_from date, p_operator_note text, p_expected_revision text, p_request_id uuid DEFAULT NULL::uuid)`
Hủy một chính sách tương lai đã lập lịch.
- **Quyền:** Admin
- **Quy tắc:** Chỉ được hủy chính sách có `effective_from > current_date_vn`. Hỗ trợ replay an toàn qua `p_request_id`.
- **Kết quả trả về:** DTO `admin_get_business_policy_config()`.

### 2.4 `admin_list_business_policies(p_limit integer DEFAULT 20, p_cursor_date date DEFAULT NULL::date)`
Liệt kê chính sách active và danh sách future policies có phân trang cursor:
- `active_policy`: jsonb
- `future_policies`: jsonb array
- `has_more`: boolean
- `next_cursor`: date string YYYY-MM-DD hoặc null

### 2.5 `admin_get_business_policy_history(p_limit integer DEFAULT 20, p_cursor_id bigint DEFAULT NULL)`
Phân trang lịch sử thay đổi chính sách theo con trỏ ID (`business_policy_changes.id`).
- **Quyền:** Admin
- **Kết quả trả về:**
```json
{
  "items": [
    {
      "id": 2,
      "policy_id": 2,
      "operation": "INSERT",
      "operator_note": "Dieu chinh phi va thuong thang 12",
      "actor_id": "11111111-1111-1111-1111-111111111111",
      "changed_at": "2026-11-01T04:00:00Z",
      "old_row": null,
      "new_row": { ... }
    }
  ],
  "next_cursor": null,
  "has_more": false
}
```

---

## 3. API Công khai & Báo giá (Public Authenticated RPCs)

Các hàm này dành cho mọi người dùng đã đăng nhập (authenticated role), không yêu cầu service key hay quyền admin.

### 3.1 `get_effective_business_policy(p_date date DEFAULT NULL)`
Đọc chính sách đang có hiệu lực tại ngày `p_date` (mặc định là ngày hôm nay theo múi giờ Việt Nam).
- **Kết quả:** Trả về đối tượng JSON mô tả chính sách chuẩn hóa.

### 3.2 `get_business_quote(p_quote_type text, p_params jsonb DEFAULT '{}')`
Lấy báo giá chính xác và mã xác thực báo giá (quote token) từ server trước khi client hiển thị giao diện thanh toán:
- `p_quote_type = 'push'`: Trả về phí đẩy app hiện hành, `policy_id`, `effective_from`, và `revision` (quote token gắn chặt với phí đẩy).
- `p_quote_type = 'slot'`: Nhận `{"slot_number": 2}` qua `p_params`, trả về đơn giá, số ngày gia hạn (`renewal_days`), `policy_id`, `effective_from`, và `revision` (quote token gắn chặt với đơn giá và thời hạn của slot).
- `p_quote_type = 'app_update'`: Trả về thưởng mỗi claim, số claim tối đa, tổng ngân sách cần giữ trước, `policy_id`, `effective_from`, và `revision` (quote token gắn chặt với bộ ba: thưởng/claim, max claims và tổng ngân sách).
- `p_quote_type = 'all'`: Trả về báo giá trọn gói cho toàn bộ các mục tài chính kèm quote token tổng hợp.

**Quy tắc mã băm báo giá (Opaque Quote Token):**
- Token báo giá `revision` (hoặc `quote_token`) được sinh bởi `public._get_business_quote_token(...)`. Token này gắn kết định danh chính sách hiệu lực (`id`, `effective_from`, `updated_at`) và toàn bộ điều khoản/thông số của báo giá đó.
- **Tự động xoay vòng khi kích hoạt lịch (Automatic Rotation on Activation):** Khi một chính sách đã lập lịch trước kích hoạt vào lúc 00:00 (giờ VN) mà không có thêm thao tác ghi bảng nào, token báo giá sẽ tự động thay đổi do dòng chính sách hiệu lực chuyển sang dòng mới.
- Client truyền nguyên vẹn chuỗi `revision` nhận được từ báo giá vào tham số `p_expected_revision` của các RPC v2 (`push_campaign_v2`, `unlock_or_renew_slot_v2`, `publish_app_update_v2`).
- Phân biệt với Admin CAS: Thao tác lập lịch/hủy lịch của quản trị viên tiếp tục dùng mã băm trạng thái toàn bảng `_get_business_policy_revision()`, tách biệt hoàn toàn với quote token của người dùng.

---

## 4. Giao dịch Tài chính & Chống xử lý lặp (Financial V2 RPCs)

Nhằm đảm bảo tương thích ngược và ngăn chặn việc client cũ xác nhận một giá nhưng backend lại tính một giá khác (khi admin kích hoạt chính sách khác mặc định):

### 4.1 Đẩy ứng dụng (`push_campaign` vs `push_campaign_v2`)
- **`push_campaign(p_campaign_id uuid, p_client_request_id uuid)` (Legacy):**
  - Kiểm tra replay trước: Nếu `p_client_request_id` đã từng được xử lý thành công trước đó, trả lại ngay biên lai cũ (kể cả sau khi chính sách đã đổi giá).
  - Nếu là giao dịch mới: Nếu chính sách hiệu lực có `push_fee = 100` (giá mặc định): Tự động lấy quote token mặc định và thực thi bình thường qua v2, trừ 100 Credit.
  - Nếu chính sách hiệu lực có `push_fee != 100`: Ném lỗi `CLIENT_UPDATE_REQUIRED: Phí đẩy ứng dụng đã thay đổi (%s Credit). Vui lòng cập nhật ứng dụng.` để tránh trừ tiền ngoài dự tính của người dùng trên client cũ.
- **`push_campaign_v2(p_campaign_id uuid, p_client_request_id uuid, p_expected_cost integer, p_expected_revision text)`:**
  - Bắt buộc `p_client_request_id`.
  - Replay idempotent trước kiểm tra độ tươi báo giá: Nếu cùng `client_request_id` và cùng `p_campaign_id`, trả về kết quả receipt đã lưu với `replayed: true`.
  - Đối với giao dịch mới: Bắt buộc truyền `p_expected_cost` và `p_expected_revision`. Nếu thiếu, ném lỗi `BUSINESS_POLICY_CONFLICT`.
  - Nếu `p_client_request_id` bị dùng với `p_campaign_id` khác hoặc giá khác: Ném lỗi `REQUEST_ID_CONFLICT`.
  - Nếu `p_expected_cost` khác với `push_fee` của chính sách: Ném lỗi `BUSINESS_POLICY_PRICE_MISMATCH`.
  - Nếu `p_expected_revision` không khớp với quote token hiện hành: Ném lỗi `BUSINESS_POLICY_CONFLICT: Báo giá đẩy ứng dụng đã hết hạn hoặc thay đổi. Vui lòng lấy lại báo giá.`.
  - Trừ đúng `push_fee` thực tế, ghi receipt vào `campaign_push_receipts`.

### 4.2 Mở khóa / Gia hạn Slot (`unlock_or_renew_slot` vs `unlock_or_renew_slot_v2`)
- **`unlock_or_renew_slot(p_slot_number integer)` (Legacy):**
  - Nếu phí slot hiện hành khớp với mặc định (Slot 2=50, Slot 3=100, Slot 4..6=200) VÀ thời hạn gia hạn bằng 30 ngày: Tự động lấy quote token mặc định và thực thi bình thường qua v2.
  - Nếu phí hoặc thời hạn đã thay đổi so với mặc định: Ném lỗi `CLIENT_UPDATE_REQUIRED: Phí hoặc thời hạn slot đã thay đổi. Vui lòng cập nhật ứng dụng.`
- **`unlock_or_renew_slot_v2(p_slot_number integer, p_client_request_id uuid, p_expected_cost integer, p_expected_revision text)`:**
  - Bắt buộc `p_client_request_id`.
  - Replay idempotent trước kiểm tra độ tươi báo giá qua bảng `slot_purchase_receipts`. Nếu đã xử lý, trả lại kết quả cũ kèm `replayed: true`, giữ nguyên `renewal_days` và `cost` snapshot gốc kể cả sau khi chính sách thay đổi.
  - Nếu `p_client_request_id` bị dùng lại cho `p_slot_number` khác, giá khác, hoặc quote token điều khoản khác: Ném `REQUEST_ID_CONFLICT`.
  - Đối với giao dịch mới: Bắt buộc truyền `p_expected_cost` và `p_expected_revision`. Nếu thiếu, ném `BUSINESS_POLICY_CONFLICT`.
  - Kiểm tra khớp giá `p_expected_cost` và khớp quote token `p_expected_revision`. Nếu token báo giá cũ (stale quote): Ném lỗi `BUSINESS_POLICY_CONFLICT`, không trừ bất kỳ credit nào và không ghi receipt.
  - Gia hạn cộng dồn theo `v_policy.slot_renewal_days`, lưu snapshot vào `slot_purchase_receipts`.

### 4.3 Đăng ký phiên bản mới (`publish_app_update` vs `publish_app_update_v2`)
- **`publish_app_update(...)` (Legacy):**
  - Khóa tuần tự hóa advisory lock theo actor + operation ID trước khi kiểm tra replay.
  - Replay idempotent trước cổng kiểm tra báo giá: Nếu operation ID đã hoàn tất trước đó, trả lại nguyên vẹn release cũ kèm `idempotent_replay: true` kể cả sau khi chính sách đổi.
  - Bắt buộc kiểm tra toàn bộ điều khoản mặc định: `reward_per_claim = 6` VÀ `max_claims = 20`. Nếu bất kỳ điều khoản nào thay đổi (kể cả khi tích vẫn bằng 120, ví dụ 4 * 30): Ném lỗi `CLIENT_UPDATE_REQUIRED: Chính sách ngân sách cập nhật ứng dụng đã thay đổi (%s Credit/claim, tối đa %s claim). Vui lòng cập nhật ứng dụng để xác nhận.`.
- **`publish_app_update_v2(p_campaign_id uuid, p_version_name text, p_target_version_code bigint, p_release_notes text, p_client_operation_id text, p_expected_budget integer, p_expected_revision text, p_expected_current_release_id uuid DEFAULT 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)`:**
  - Khóa tuần tự hóa advisory lock theo actor + operation ID chống double-claim / double-publish race giữa các package khác nhau.
  - Replay idempotent trước kiểm tra ngân sách/quote: Trả lại release đã lưu nếu trùng operation ID và payload. Khác payload ném `OPERATION_PAYLOAD_CONFLICT`.
  - Bắt buộc kiểm tra `p_expected_budget` khớp với `reward_per_claim * max_claims` và `p_expected_revision` khớp với quote token của đợt cập nhật app.
  - Concurrency CAS: Nếu `p_expected_current_release_id` là NULL, bắt buộc hiện tại KHÔNG có đợt active nào (`active_release_id IS NULL`), nếu có ném `CONCURRENT_UPDATE_CONFLICT`. Chỉ khi bằng sentinel `ffffffff-ffff-ffff-ffff-ffffffffffff` mới bỏ qua kiểm tra CAS (dành riêng cho caller legacy).
  - Tạo đợt release với snapshot `reward_per_claim`, `max_claims`, `reserved_credits`, `business_policy_id`.
  - Các đợt release cũ đang active được superseded và hoàn tiền đúng số `remaining_credits` chưa chi.

---

## 5. Quy tắc các nhánh tiêu thụ khác (Consumers)

1. **Khởi tạo tài khoản (`handle_new_user`):**
   - Đọc `welcome_credits` từ chính sách có hiệu lực tại ngày tạo tài khoản.
   - Thưởng credit và ghi ledger note với đúng số credit được cấp: `Thưởng %s Credit khi mở tài khoản`.

2. **Đánh giá phạt bỏ lỡ nhiệm vụ (`_apply_daily_inactivity_penalty` & `get_daily_task_status`):**
   - Đánh giá theo ngày giờ VN `p_eval` (không dùng ngày hiện tại nếu đánh giá ngày quá khứ).
   - Lấy chính sách hiệu lực tại ngày `p_eval`: `penalty_tier1_pct` .. `penalty_tier4_pct`.
   - Áp dụng tỷ lệ tương ứng theo chuỗi bỏ lỡ:
     - Streak = 1: `penalty_tier1_pct` (mặc định 1%)
     - Streak = 2: `penalty_tier2_pct` (mặc định 2%)
     - Streak = 3: `penalty_tier3_pct` (mặc định 5%)
     - Streak >= 4: `penalty_tier4_pct` (mặc định 10%)
   - **Xử lý tỷ lệ 0%:** Nếu tỷ lệ phạt là 0%, số tiền phạt là 0 (không áp dụng mức tối thiểu 1 credit khi tỷ lệ bằng 0).
   - Không tính toán lại các ngày đã finalized trong lịch sử (`user_daily_tasks.finalized_at is not null`).
   - Snapshot đầy đủ giá trị vào dòng finalized: `penalty_percent`, `business_policy_id`.
   - `get_daily_task_status`: Tính `next_penalty_percent` theo chính sách hiệu lực của ngày yêu cầu.

3. **Báo cáo kiểm thử (Tester Reports Lifecycle & Projections):**
   - **Cột snapshot tài chính trên `tester_reports`:**
     - `review_tester_bonus integer` (thưởng duyệt thủ công cho tester đã snapshot).
     - `review_owner_bonus integer` (thưởng duyệt thủ công cho chủ app đã snapshot).
     - `business_policy_id bigint` (định danh chính sách hiệu lực tại thời điểm nộp).
     - `provisional_granted_credits integer` (thưởng tạm tính thực tế đã cấp khi nộp).
     - `provisional_clawed integer` (số credit thực tế đã thu hồi khi từ chối).
   - **Chống can thiệp client (Anti-tamper Guard):**
     - Trigger `trg_guard_tester_report_financial_metadata` chặn toàn bộ thao tác INSERT / UPDATE trực tiếp từ client đối với các cột tài chính trên. Chỉ RPC nộp/duyệt của backend mới được ghi nhận.
   - **Nộp báo cáo (`submit_tester_report_internal_v2`):**
     - Đọc `report_provisional_reward` từ chính sách hiệu lực tại ngày nộp.
     - Cấp thưởng tạm tính và snapshot vào `provisional_granted_credits`, `review_tester_bonus`, `review_owner_bonus`, `business_policy_id`.
     - Hỗ trợ mức thưởng tạm tính = 0: không cộng credit giả và không tạo log credit.
   - **Duyệt thủ công (`approve_tester_report`):**
     - Đọc giá trị bonus đã snapshot từ báo cáo (không đổi theo chính sách mới sau thời điểm nộp; nếu báo cáo cũ fallback 2).
     - Tester nhận: `review_tester_bonus` (mặc định +2).
     - Nếu người duyệt là chủ app thật (`v_is_owner = true`): Chủ app nhận `review_owner_bonus` (mặc định +2).
     - Nếu người duyệt là Admin/Moderator (`v_is_owner = false`): Chủ app nhận `0` bonus.
   - **Tự động duyệt quá hạn (`auto_accept_expired_reports`):**
     - Tester đã nhận thưởng tạm tính, không nhận thêm bonus xét duyệt thủ công (`bonus_awarded = provisional_granted_credits + tip`).
     - Chủ app nhận `0` bonus.
   - **Từ chối báo cáo (`reject_tester_report`):**
     - Thu hồi khoản credit tạm tính dựa trên đúng số đã cấp thực tế (`coalesce(v_report.provisional_granted_credits, 2)`), không hardcode 10.
     - Giới hạn thu hồi không vượt quá số dư khả dụng của tester (không tạo số dư âm).
     - Báo cáo có provisional = 0: thu hồi đúng 0 credit.
     - Tích hợp loại trừ cảnh báo đã hủy (`user_warning_revocations`) từ Migration 070.
   - **Nhiệm vụ ứng dụng (App Missions):**
     - Giữ nguyên cơ chế cam kết thưởng riêng của từng nhiệm vụ từ trước đến nay (`_settle_mission_reward`).
   - **Đồng bộ DTO hiển thị (Projections):**
     - Cả `get_campaign_detail` và `get_campaign_reports_for_owner` đều trả về đầy đủ các trường snapshot mới: `review_tester_bonus`, `review_owner_bonus`, `business_policy_id`, `provisional_granted_credits`, `provisional_clawed`, đồng thời giữ nguyên 100% các khóa và cấu trúc DTO hiện có.
