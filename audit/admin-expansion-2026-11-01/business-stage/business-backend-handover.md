# Biên bản Bàn giao Giai đoạn Nền tảng Chính sách Nghiệp vụ (Business Policy Foundation Handover)

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-stage/business-backend-handover.md`  
**Chặng:** C — Business Policy Backend (Foundation Stage)  
**Ngày:** 01/11/2026  
**Thực hiện:** OpenCode (`google/antigravity-gemini-3.8-flash`, `--variant high`)  
**Tình trạng kiểm thử:** Đạt 100% các ca kiểm thử nền tảng PGlite (`supabase/tests/run-business-policies-test.mjs`) nạp chuỗi migrations `062 -> 069` và staged `071_business_policies.sql`.

---

## 1. Tóm tắt kết quả Chặng Nền tảng (Foundation Turn)

Trong lượt này, hệ thống đã hoàn thiện toàn bộ tầng nền tảng lưu trữ, ràng buộc dữ liệu, CAS revision, quản trị lịch hiệu lực tương lai theo giờ Việt Nam, audit ghi chép nội bộ, và các RPC công khai/quản trị theo đúng đặc tả và lưu ý tích hợp từ Codex:

1. **Bảng lưu trữ chính sách `business_policies`:**
   - Các khóa cố định có kiểm tra miền giá trị chặt chẽ (không dùng JSON tùy ý): `welcome_credits` (0..10000), `penalty_tier1_pct`..`penalty_tier4_pct` (0..100, bảo đảm thứ tự `tier1 <= tier2 <= tier3 <= tier4`), `push_fee` (1..10000), `slot_fee_2`, `slot_fee_3`, `slot_fee_4_plus` (1..10000), `slot_renewal_days` (1..365), `report_provisional_reward`, `report_review_tester_bonus`, `report_review_owner_bonus` (0..1000), `app_update_reward_per_claim`, `app_update_max_claims` (1..1000).
   - Dòng baseline ban đầu được nạp sẵn với `effective_from = '-infinity'::date` mang đầy đủ giá trị mặc định lịch sử.

2. **Bảng lưu vết biến động `business_policy_changes`:**
   - Ghi lại mọi thao tác INSERT, UPDATE, DELETE kèm `actor_id` (auth.uid()), `old_row`, `new_row`, `operator_note`, `session_role`, `invoked_role`, `changed_at`.
   - Triggers chống sửa đổi (`BEFORE UPDATE OR DELETE`) và chống truncate (`BEFORE TRUNCATE`) với thông báo lỗi `BUSINESS_POLICY_HISTORY_READ_ONLY`.

3. **Bảng ledger replay giao dịch tài chính `slot_purchase_receipts`:**
   - Cung cấp idempotency và replay an toàn cho thao tác tài chính mở/gia hạn slot qua `(user_id, client_request_id)`.
   - Bật RLS và chính sách cách ly người dùng `slot_purchase_receipts_select_own`.

4. **Kiểm tra xung đột CAS và hiệu lực tương lai:**
   - `_get_business_policy_revision()`: Băm MD5 trạng thái toàn bảng chính sách.
   - Quản trị viên chỉ được phép lập lịch (`admin_schedule_business_policy`) hoặc hủy lịch (`admin_cancel_business_policy`) cho ngày tương lai (`effective_from > (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date`).
   - Trigger `guard_business_policy` ngăn chặn sửa/xóa chính sách đang hoạt động hoặc trong quá khứ.

5. **Bộ RPC Quản trị (Admin) & Công khai (Public):**
   - `admin_get_business_policy_config()`: Đọc cấu hình hôm nay, revision, active_policy và danh sách tương lai.
   - `admin_schedule_business_policy(...)`: Lập lịch tương lai hoặc cập nhật ngày tương lai có kiểm tra revision CAS.
   - `admin_cancel_business_policy(...)`: Hủy lịch tương lai có kiểm tra revision CAS.
   - `admin_list_business_policies()`: Liệt kê active policy và future policies.
   - `admin_get_business_policy_history(limit, cursor_id)`: Phân trang con trỏ an toàn với `has_more` và `next_cursor`.
   - `get_effective_business_policy(p_date)`: Đọc chính sách đang hiệu lực tại ngày chỉ định.
   - `get_business_quote(p_quote_type, p_params)`: Báo giá chuẩn xác cho client (push, slot, app_update, all) kèm policy revision.

---

## 2. Danh sách file và thay đổi

| Đường dẫn file | Trạng thái | Nội dung |
| :--- | :--- | :--- |
| `audit/admin-expansion-2026-11-01/business-stage/business-api-contract.md` | Đã cập nhật | Hợp đồng API chuẩn hóa, sửa đúng tên hàm legacy `publish_app_update` (062) và đặc tả `publish_app_update_v2` |
| `audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql` | Đã tạo (Staged) | Toàn bộ DDL tables, triggers, CAS revision, Admin & Public RPCs của tầng Foundation |
| `supabase/tests/run-business-policies-test.mjs` | Đã tạo | Test runner PGlite nạp migrations 062-069 + staged 071, kiểm thử 7 nhóm hành vi nền tảng |
| `audit/admin-expansion-2026-11-01/business-stage/business-backend-handover.md` | Đã tạo | Biên bản bàn giao và kế hoạch nối tiếp triển khai consumers |

---

## 3. Kết quả chạy kiểm thử thực tế (PGlite Output)

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
=== TẤT CẢ CÁC BƯỚC KIỂM THỬ FOUNDATION ĐÃ VƯỢT QUA THÀNH CÔNG ===
```

---

## 4. Kế hoạch triển khai các nhóm Consumers cho lượt kế tiếp (Continuation Plan)

Theo chỉ dẫn của Codex và yêu cầu từ người dùng, lượt kế tiếp sẽ triển khai các hàm tiêu thụ (consumers) theo từng nhóm nhỏ, kiểm thử nối tiếp vào `run-business-policies-test.mjs`:

### Nhóm 1: Welcome Credits & Push Campaign
- Cập nhật ràng buộc `campaign_push_receipts`: Đổi `cost_credits = 100` thành `cost_credits > 0`.
- Thay thế `handle_new_user()` tiêu thụ `v_policy.welcome_credits` và định dạng ghi chép ledger note đồng bộ.
- Thay thế `push_campaign(p_campaign_id, p_client_request_id)`: Nếu phí hiện hành là 100 thì giữ tương thích cũ; nếu khác 100 thì ném lỗi `CLIENT_UPDATE_REQUIRED`.
- Bổ sung `push_campaign_v2(p_campaign_id, p_client_request_id, p_expected_cost, p_expected_revision)` với kiểm tra CAS và replay.

### Nhóm 2: Slot Fees & App Update Releases
- Thay thế `unlock_or_renew_slot(p_slot_number)`: Nếu phí hoặc thời hạn khác mặc định thì ném lỗi `CLIENT_UPDATE_REQUIRED`.
- Bổ sung `unlock_or_renew_slot_v2(p_slot_number, p_client_request_id, p_expected_cost, p_expected_revision)` ghi ledger `slot_purchase_receipts`.
- Cập nhật ràng buộc `app_update_releases` (`max_claims > 0`, `reward_per_claim > 0`, `reserved_credits = max_claims * reward_per_claim`).
- Thay thế `publish_app_update`: Giữ tương thích 120 credit khi mặc định; ném lỗi `CLIENT_UPDATE_REQUIRED` khi ngân sách khác 120.
- Bổ sung `publish_app_update_v2(p_campaign_id, p_version_name, p_target_version_code, p_release_notes, p_client_operation_id, p_expected_budget, p_expected_revision, p_expected_current_release_id)`.

### Nhóm 3: Daily Penalty Tiers & Tester Reports Lifecycle
- Thay thế `_apply_daily_inactivity_penalty(p_eval)` và `get_daily_task_status`: Tiêu thụ tỷ lệ phân tầng động của ngày `p_eval`. Xử lý trường hợp tỷ lệ phạt 0% (không ép trừ 1 credit khi phạt 0%).
- Thêm cột snapshot vào `tester_reports`: `provisional_granted_credits` (đã có), `review_tester_bonus`, `review_owner_bonus`, `policy_id`.
- Thay thế `submit_tester_report_internal_v2`: Snapshot chính sách tại thời điểm nộp. Xử lý thưởng tạm tính 0 credit an toàn.
- Thay thế `approve_tester_report`: Dùng snapshot bonus tại thời điểm nộp (không đổi theo chính sách tương lai). Giữ bảo mật 069: staff duyệt thì owner bonus = 0.
- Thay thế `reject_tester_report`: Thu hồi theo đúng số provisional đã cấp thực tế (`coalesce(v_report.provisional_granted_credits, 2)`), không hardcode 10.
- Giữ nguyên cam kết thưởng của Mission ứng dụng (`_settle_mission_reward`).
- Tích hợp / rebase an toàn với Migration 070 (khi 070 hoàn tất từ session song song).
