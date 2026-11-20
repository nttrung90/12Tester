# Biên bản Bàn giao Giai đoạn Phí và Thưởng Cơ bản (Business Policy C2 Fees & Welcome Handover)

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-stage/business-c2-handover.md`  
**Chặng:** C2 — Bounded Foundation Fixes + Welcome / Push / Slot Consumers  
**Ngày:** 01/11/2026  
**Thực hiện:** OpenCode (`google/antigravity-gemini-3.8-flash`, `--variant high`)  
**Tình trạng kiểm thử:** Đạt 100% 12/12 ca kiểm thử PGlite trong `supabase/tests/run-business-policies-test.mjs` bao gồm nạp độc lập `062 -> 069 + staged 071` cũng như kiểm tra tích hợp có `070_admin_management.sql`. Toàn bộ regression suites `052` (push priority) và `054` (push & carryover hardening) đều đạt 100%.

---

## 1. Các thành phần đã hoàn tất trong lượt C2

### 1.1 Khắc phục lỗ hổng Tầng Nền tảng (Foundation Gaps)
1. **Sổ cái Idempotency Request ID cho Quản trị viên (`business_policy_admin_requests`):**
   - Đã thêm bảng `business_policy_admin_requests` với khóa chính `(actor_id, operation, id)` và kiểm tra payload hash canonical MD5.
   - Thao tác `admin_schedule_business_policy` và `admin_cancel_business_policy` nhận thêm tham số tùy chọn `p_request_id uuid DEFAULT NULL::uuid`.
   - **Xác thực trước -> Replay trước CAS:** Khi client gửi lại `p_request_id` đã từng thành công (ví dụ retry sau khi mất mạng), backend trả lại kết quả cũ ngay lập tức mà không bị từ chối bởi kiểm tra CAS revision. Nếu payload hash khác nhau, ném lỗi `REQUEST_ID_CONFLICT`.
2. **Giới hạn thời gian tương lai hữu hạn (Finite Day Bounds):**
   - Chặn lập lịch chính sách quá xa trong tương lai (`p_effective_from > current_date_vn + 3650 days`), tránh tràn số hoặc rác dữ liệu.
3. **Phân trang danh sách lịch tương lai (Bounded Future List with Cursor):**
   - `admin_get_business_policy_config(p_future_limit integer DEFAULT 20)`: Giới hạn số lượng future policies trả về ở trang đầu, cung cấp `future_has_more` và `future_next_cursor`.
   - `admin_list_business_policies(p_limit integer DEFAULT 20, p_cursor_date date DEFAULT NULL::date)`: Hỗ trợ nạp các trang tiếp theo theo con trỏ ngày.
4. **Tích hợp Audit chung với Migration 070:**
   - Trong `admin_schedule_business_policy` và `admin_cancel_business_policy`, tự động kiểm tra sự tồn tại của hàm `public.record_admin_action_event(...)` từ Migration 070. Nếu có, ghi thêm sự kiện vào `admin_action_events` với `request_id`, bảo toàn nguyên vẹn bảng audit riêng `business_policy_changes`. Không nuốt lỗi audit trong toàn chuỗi.

---

### 1.2 Triển khai các Consumer Nghiệp vụ thực tế (Active Consumers)

1. **Welcome Credits Consumer (`handle_new_user`):**
   - Tự động đọc `v_policy.welcome_credits` từ chính sách có hiệu lực tại ngày tạo tài khoản theo giờ Việt Nam.
   - Khi tài khoản mới được tạo, cấp đúng số credit từ chính sách (hỗ trợ cả trường hợp chính sách đặt `welcome_credits = 0`).
   - Ghi log `credit_logs` với `action = 'welcome'`, số tiền và ghi chú hoàn toàn đồng nhất: `format('Thưởng %s Credit khi mở tài khoản', v_welcome_credits)`.
   - Người dùng đã tồn tại (`ON CONFLICT (id) DO NOTHING`) được bảo toàn tuyệt đối, không bị ghi đè số dư hoặc ghi thêm log thừa.

2. **Push Campaign Consumer (`push_campaign` & `push_campaign_v2`):**
   - **Nới lỏng ràng buộc biên lai:** Thay thế `campaign_push_receipts_cost_credits_check` từ cứng `cost_credits = 100` thành `CHECK (cost_credits >= 1 AND cost_credits <= 10000)`.
   - **Tương thích ngược an toàn (Fail-closed):** Khi chính sách đang kích hoạt có `push_fee = 100`, hàm legacy `push_campaign(p_campaign_id, p_client_request_id)` tiếp tục hoạt động bình thường. Khi chính sách đổi sang phí khác 100, hàm legacy từ chối với lỗi rõ ràng: `CLIENT_UPDATE_REQUIRED: Phí đẩy ứng dụng đã thay đổi (%s Credit). Vui lòng cập nhật ứng dụng.`.
   - **Replay qua thời điểm đổi chính sách:** Nếu một request ID đã từng thanh toán trước khi đổi chính sách, việc replay lại `push_campaign` hoặc `push_campaign_v2` vẫn trả về đúng receipt cũ với đúng số tiền đã trừ lịch sử, không bị chặn bởi kiểm tra giá mới.
   - **RPC V2 (`push_campaign_v2`):**
     - Bắt buộc `p_client_request_id`.
     - Nhận `p_expected_cost` và `p_expected_revision` để bảo đảm client không bao giờ bị trừ tiền sai so với báo giá hiển thị.
     - Kiểm tra xung đột request ID: nếu dùng lại `p_client_request_id` cho campaign khác hoặc giá khác, ném lỗi `REQUEST_ID_CONFLICT`.
     - Giữ nguyên toàn bộ cơ chế bảo vệ từ 066 (push order sequence, lock ordering campaigns -> profiles, 30-day window, slot 2 active check, account locked guard).

3. **User Slots Consumer (`unlock_or_renew_slot` & `unlock_or_renew_slot_v2`):**
   - **Tương thích ngược an toàn (Fail-closed):** Khi biểu phí slot (Slot 2=50, Slot 3=100, Slot 4..6=200) và thời hạn (30 ngày) giữ nguyên mặc định, hàm legacy `unlock_or_renew_slot(p_slot_number)` hoạt động bình thường. Khi có bất kỳ thay đổi nào, từ chối với `CLIENT_UPDATE_REQUIRED: Phí hoặc thời hạn slot đã thay đổi. Vui lòng cập nhật ứng dụng.`.
   - **RPC V2 (`unlock_or_renew_slot_v2`):**
     - Bắt buộc `p_client_request_id`.
     - Nhận `p_expected_cost` và `p_expected_revision`.
     - Kiểm tra replay trước CAS qua sổ cái `slot_purchase_receipts`. Nếu replay đúng slot, trả lại biên lai cũ kèm `replayed: true`. Nếu sai slot hoặc sai giá, ném `REQUEST_ID_CONFLICT`.
     - Khóa theo thứ tự: `profiles`, kiểm tra tài khoản không bị khóa, kiểm tra điều kiện slot trước đó (Slot N > 2 bắt buộc Slot N-1 còn hạn).
     - Gia hạn cộng dồn (`cumulative`): Nếu slot đang còn hạn, cộng dồn thêm số ngày gia hạn (`slot_renewal_days`) vào hạn cũ; nếu đã hết hạn, tính từ `now()`.
     - Ghi nhận `credit_logs` với số tiền âm chính xác và ghi chép biên lai vào `slot_purchase_receipts`.

---

## 2. Danh sách API và Chữ ký cập nhật

### 2.1 Admin RPCs
- `admin_get_business_policy_config(p_future_limit integer DEFAULT 20)`
- `admin_schedule_business_policy(p_effective_from date, p_operator_note text, p_expected_revision text, p_welcome_credits integer DEFAULT 100, p_penalty_tier1_pct integer DEFAULT 1, p_penalty_tier2_pct integer DEFAULT 2, p_penalty_tier3_pct integer DEFAULT 5, p_penalty_tier4_pct integer DEFAULT 10, p_push_fee integer DEFAULT 100, p_slot_fee_2 integer DEFAULT 50, p_slot_fee_3 integer DEFAULT 100, p_slot_fee_4_plus integer DEFAULT 200, p_slot_renewal_days integer DEFAULT 30, p_report_provisional_reward integer DEFAULT 10, p_report_review_tester_bonus integer DEFAULT 2, p_report_review_owner_bonus integer DEFAULT 2, p_app_update_reward_per_claim integer DEFAULT 6, p_app_update_max_claims integer DEFAULT 20, p_request_id uuid DEFAULT NULL::uuid)`
- `admin_cancel_business_policy(p_effective_from date, p_operator_note text, p_expected_revision text, p_request_id uuid DEFAULT NULL::uuid)`
- `admin_list_business_policies(p_limit integer DEFAULT 20, p_cursor_date date DEFAULT NULL::date)`
- `admin_get_business_policy_history(p_limit integer DEFAULT 20, p_cursor_id bigint DEFAULT NULL::bigint)`

### 2.2 Public / Financial RPCs
- `get_effective_business_policy(p_date date DEFAULT NULL::date)`
- `get_business_quote(p_quote_type text, p_params jsonb DEFAULT '{}'::jsonb)`
- `push_campaign(p_campaign_id uuid, p_client_request_id uuid)`
- `push_campaign_v2(p_campaign_id uuid, p_client_request_id uuid, p_expected_cost integer DEFAULT NULL::integer, p_expected_revision text DEFAULT NULL::text)`
- `unlock_or_renew_slot(p_slot_number integer)`
- `unlock_or_renew_slot_v2(p_slot_number integer, p_client_request_id uuid, p_expected_cost integer DEFAULT NULL::integer, p_expected_revision text DEFAULT NULL::text)`

---

## 3. Kết quả Kiểm thử (Test Results)

Tất cả 12 bước trong `supabase/tests/run-business-policies-test.mjs` đều chạy thành công trên PGlite:

```text
=== KHỞI TẠO PGLITE VÀ NẠP MIGRATIONS 062 - 069 ===
Applying 062_consolidated_baseline.sql...
Applying 063_report_crypto_search_path.sql...
Applying 064_explicit_report_context.sql...
Applying 065_remove_dhash_hardblock.sql...
Applying 066_push_and_carryover_hardening.sql...
Applying 067_admin_direct_notification_popup.sql...
Applying 068_owner_tester_reports_count.sql...
Applying 069_admin_audit_hardening.sql...
=== NẠP STAGED MIGRATION 071 BUSINESS POLICIES FOUNDATION ===
=== BẮT ĐẦU KIỂM THỬ BUSINESS POLICIES FOUNDATION ===
PASS: 1. Baseline configuration defaults & shape
PASS: 2. Admin permission guards reject non-admin & locked admin
PASS: 3. Public authenticated get_effective_business_policy and get_business_quote
PASS: 4. Range, date and CAS validation
PASS: 5. Schedule future policy and verify CAS & audit
PASS: 6. Schedule second future date, cancel it, and verify history pagination
PASS: 7. Anti-tamper triggers prevent history mutation and active policy delete
PASS: 8. Admin RequestID replay, mismatch detection, finite bounds & cursor list
PASS: 9. Consumer: handle_new_user with policy credits, ledger consistency & 0 credits
PASS: 10. Consumer: push_campaign legacy & v2 with fee changes, replays and bounds
PASS: 11. Consumer: unlock_or_renew_slot legacy & v2 with fee/days changes, replays, prerequisites
--- Applying 070_admin_management.sql for integration check ---
PASS: 12. 070 integration & record_admin_action_event audit integration
=== TẤT CẢ CÁC BƯỚC KIỂM THỬ FOUNDATION VÀ FEES CONSUMERS ĐÃ VƯỢT QUA THÀNH CÔNG ===
```

---

## 4. Công việc còn lại cho lượt tiếp theo (Next Continuation)

Dừng phiên này theo đúng yêu cầu sau khi chặng Welcome/Push/Slot + Foundation hoàn tất. Các consumer còn lại sẽ được triển khai trong lượt kế tiếp:
1. **Daily Inactivity Penalty Tiers Consumer:**
   - Thay thế `_apply_daily_inactivity_penalty(p_eval date)` và `get_daily_task_status(p_user_id, p_date)` tiêu thụ các mức phạt động 1%, 2%, 5%, 10% theo ngày giờ VN `p_eval`.
   - Xử lý biên trường hợp tỷ lệ phạt 0% (không bắt buộc trừ 1 credit khi phạt 0%).
2. **Tester Reports Lifecycle Consumer:**
   - Cập nhật `submit_tester_report_internal_v2` snapshot chính sách và phần thưởng tại thời điểm nộp (hỗ trợ thưởng tạm tính 0 credit).
   - Cập nhật `approve_tester_report` đọc snapshot từ báo cáo; chủ duyệt thưởng bonus, staff duyệt bonus chủ = 0; auto-accept không cấp extra bonus.
   - Cập nhật `reject_tester_report` thu hồi đúng số provisional thực tế đã cấp (`coalesce(provisional_granted_credits, 2)`), không hardcode 10.
3. **App Update Release Consumer:**
   - Cập nhật các ràng buộc trên `app_update_releases` (`max_claims > 0`, `reward_per_claim > 0`, `reserved_credits = max_claims * reward_per_claim`).
   - Cập nhật `publish_app_update` (legacy) và thêm `publish_app_update_v2` với ngân sách giữ trước dẫn xuất `reward * max_claims`.
