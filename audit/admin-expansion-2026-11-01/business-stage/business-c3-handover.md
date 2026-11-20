# Biên bản Bàn giao Chặng C3 Đã Sửa Đổi (Business Policy C3 Penalty & Reports Handover - Reviewed)

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-stage/business-c3-handover.md`  
**Chặng:** C3 — Daily Inactivity Penalty & Tester Reports Lifecycle Consumers (Fixed & Hardened)  
**Ngày:** 01/11/2026  
**Thực hiện:** OpenCode (`google/antigravity-gemini-3.8-flash`, `--variant high`)  
**Tình trạng kiểm thử:** Đạt 100% 18/18 ca kiểm thử PGlite trong `supabase/tests/run-business-policies-test.mjs` nạp chuỗi `062 -> 070 + staged 071`. Toàn bộ các suite kiểm thử liên quan (`run-admin-management-test.mjs` 30/30, `run-campaign-push-test.mjs` 19/19, `run-push-and-carryover-hardening-test.mjs` 10/10, `run-report-idempotency-ledger-test.mjs` 9/9, `run-owner-tester-reports-count-test.mjs` 9/9, `run-admin-daily-task-policy-test.mjs` 10/10, `run-daily-task-carryover.mjs` 10/10, và probe `independent-business-quote-probe.mjs`) đều đạt 100%.

---

## 1. Các vấn đề nghiêm trọng đã khắc phục theo Đánh giá Độc lập

### 1.1 Khắc phục lỗ hổng đúc tiền tuỳ ý qua `p_bonus` trong `approve_tester_report`
- **Nguyên nhân gốc rễ:** Hàm `approve_tester_report(p_report_id, p_bonus)` trong migration 069 khai báo tham số `p_bonus` chỉ để tương thích với chữ ký gọi cũ của client nhưng thực tế bỏ qua tham số này (`v_extra_reward` được gán cứng = 2). Khi chuyển sang snapshot chính sách, việc gán `v_extra_reward := snapshot + coalesce(p_bonus, 0)` đã vô tình mở ra lỗ hổng cho phép bất kỳ caller nào truyền `p_bonus` tùy ý (thậm chí `INT_MAX`) để tự đúc credit cho tester mà không trừ tiền của chủ app, hoặc truyền số âm gây sai lệch số dư.
- **Khắc phục triệt để:** Khôi phục đúng ngữ nghĩa của Migration 069: tham số `p_bonus` tiếp tục được chấp nhận để giữ tương thích chữ ký, nhưng **hoàn toàn bị bỏ qua**. Phần thưởng bổ sung của tester **chỉ lấy từ giá trị snapshot `review_tester_bonus`** của chính sách hiệu lực tại thời điểm nộp (mặc định 2).
- **Kiểm thử xác minh:** Đã kiểm thử `p_bonus = 999999`, `p_bonus = -50`, `p_bonus = null`: tất cả đều trả về đúng phần thưởng đã snapshot (ví dụ 5 credit) mà không sinh credit ngoài ý muốn, bảo đảm toàn vẹn số dư và log credit.
- **Khuyến nghị cho Android / Core Queue UI:** Giao diện duyệt báo cáo trong Admin Queue hiện tại hiển thị ô nhập bonus có thể gây hiểu nhầm cho quản trị viên; nên cập nhật UI hiển thị đúng số credit thưởng được cấu hình từ server thay vì hứa hẹn hiệu lực của tham số custom này.

### 1.2 Khắc phục Trigger Bảo vệ Metadata Tài chính (`guard_tester_report_financial_metadata`)
- **Nguyên nhân gốc rễ:** Hàm trigger ban đầu khai báo `SECURITY DEFINER` và kiểm tra `session_user in ('anon', 'authenticated')`. Trong môi trường PostgREST thực tế, `session_user` là `authenticator`, kết hợp với `SET ROLE authenticated`, khiến nhánh kiểm tra `session_user` không bao giờ được kích hoạt.
- **Khắc phục triệt để:**
  - Chuyển hàm trigger thành **SECURITY INVOKER** (không có cờ `SECURITY DEFINER`). Khi chạy dưới SECURITY INVOKER, biến `current_user` phản ánh trực tiếp quyền hạn tức thời của câu lệnh DML:
    - Khi client gọi DML trực tiếp với `SET ROLE authenticated`, `current_user` là `'authenticated'`, trigger phát hiện và lập tức ném lỗi `FORBIDDEN_REPORT_METADATA_WRITE` hoặc `FORBIDDEN_REPORT_METADATA_UPDATE`.
    - Khi RPC nội bộ tin cậy (`submit_tester_report_internal_v2`, `approve_tester_report`, `reject_tester_report`, `auto_accept_expired_reports`) chạy dưới quyền `SECURITY DEFINER` của `postgres`, `current_user` chuyển sang `postgres`, trigger cho phép thực thi hợp lệ.
    - Kể cả khi trong cùng một transaction đã gọi RPC trước đó, câu lệnh DML trực tiếp tiếp theo của client vẫn bị trigger ngăn chặn an toàn.
  - Bổ sung ràng buộc CHECK mức database engine trên `tester_reports`:
    - `review_tester_bonus IS NULL OR review_tester_bonus >= 0`
    - `review_owner_bonus IS NULL OR review_owner_bonus >= 0`
    - `provisional_granted_credits >= 0`
    - `provisional_clawed >= 0`
    - `bonus_awarded >= 0`

### 1.3 Khắc phục Thu hồi và Tự động duyệt đối với Báo cáo Thưởng 0 Credit
- **Nguyên nhân gốc rễ:** Trong `auto_accept_expired_reports`, biểu thức `coalesce(nullif(v_report.provisional_granted_credits, 0), nullif(v_report.bonus_awarded, 0), 0)` khiến báo cáo có mức thưởng tạm tính bằng 0 thật sự (`provisional = 0`) bị biến thành `null` và rơi về giá trị `bonus_awarded` cũ.
- **Khắc phục triệt để:** Đổi thành `v_base := coalesce(v_report.provisional_granted_credits, coalesce(nullif(v_report.bonus_awarded, 0), 2))`. Nếu `provisional_granted_credits` là 0 thật sự, `v_base` giữ nguyên là 0, không rơi về giá trị mặc định legacy 2.
- **Kiểm thử xác minh:** Đã kiểm thử báo cáo 0 credit được nộp, quá hạn 8h và tự động duyệt: `bonus_awarded` được chốt đúng bằng 0, không cấp nhầm 2 credit.

### 1.4 Bảo vệ Ghi chú Quản trị Nội bộ (`operator_note`)
- **Nguyên nhân gốc rễ:** Hàm format public `_format_business_policy_json` trước đó xuất trực tiếp `operator_note`, khiến các ghi chú điều hành nội bộ của admin bị lộ qua endpoint public `get_effective_business_policy`. Ngoài ra quyền `GRANT SELECT ON TABLE business_policies TO authenticated` cho phép client truy vấn trực tiếp bảng chính sách.
- **Khắc phục triệt để:**
  - Tách hàm format thành 2 hàm chuyên biệt:
    - `_format_business_policy_public_json`: Chỉ xuất các thông số tài chính cần thiết cho người dùng, **loại bỏ hoàn toàn `operator_note` và metadata actor**.
    - `_format_business_policy_json`: Hàm format nội bộ giữ nguyên `operator_note`, `created_at`, `updated_at` phục vụ các RPC của Quản trị viên (`admin_get_business_policy_config`, `admin_list_business_policies`).
  - Hàm public `get_effective_business_policy(p_date)` chuyển sang sử dụng `_format_business_policy_public_json`.
  - Thu hồi quyền `SELECT` trực tiếp trên bảng `public.business_policies` từ `authenticated` và `anon` (`REVOKE ALL ON TABLE business_policies FROM PUBLIC, anon, authenticated`). Người dùng chỉ được đọc qua SECURITY DEFINER RPC.

### 1.5 Bổ sung Override cho Hàng đợi Báo cáo Quản trị Toàn cục (`admin_get_tester_reports_queue`)
- Override hàm `admin_get_tester_reports_queue` từ Migration 070 để bổ sung đầy đủ các cột snapshot tài chính mới: `review_tester_bonus`, `review_owner_bonus`, `business_policy_id`, `provisional_granted_credits`, `provisional_clawed`, `bonus_awarded`.
- Bảo toàn nguyên vẹn 100% các cột hiện có của 070 (bao gồm `media_expired`, join thông tin owner/tester, phân trang cursor và bộ lọc trạng thái).
- Hỗ trợ linh hoạt cả hai hàm guard: `assert_unlocked_admin` (nếu có 070) và `_assert_daily_task_admin` (khi chạy cô lập).

---

## 2. Kết quả Chạy Kiểm thử Toàn diện (PGlite 18/18 PASS)

```text
=== KHỞI TẠO PGLITE VÀ NẠP MIGRATIONS 062 - 070 ===
Applying 062_consolidated_baseline.sql...
Applying 063_report_crypto_search_path.sql...
Applying 064_explicit_report_context.sql...
Applying 065_remove_dhash_hardblock.sql...
Applying 066_push_and_carryover_hardening.sql...
Applying 067_admin_direct_notification_popup.sql...
Applying 068_owner_tester_reports_count.sql...
Applying 069_admin_audit_hardening.sql...
Applying 070_admin_management.sql...
=== NẠP STAGED MIGRATION 071 BUSINESS POLICIES FOUNDATION & CONSUMERS ===
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
PASS: 12. 070 integration & record_admin_action_event audit integration
PASS: 13. Quote activation token rotation & stale quote rejection with date seam
PASS: 14. Daily Inactivity Penalty: 0% tier, snapshot terms, and history immutability
PASS: 15. Financial metadata guard: direct client inserts/updates rejected
PASS: 16. Report Lifecycle: submission snapshots, owner vs staff approval, auto-accept
PASS: 17. Zero provisional reward, clawback bounds, and 070 warning revocation
PASS: 18. admin_get_tester_reports_queue 070 override with snapshot fields
=== TẤT CẢ CÁC BƯỚC KIỂM THỬ FOUNDATION VÀ FEES CONSUMERS ĐÃ VƯỢT QUA THÀNH CÔNG ===
```

---

## 3. Kế hoạch cho Chặng tiếp theo (Next Turn: C4 App Update Lifecycle)

Sau khi hoàn tất đánh giá và xác nhận chặng C3, chặng C4 sẽ hoàn thiện nhóm consumer cuối cùng:
1. **Ràng buộc bảng `app_update_releases`:**
   - Thay thế các check constraint cứng bằng ràng buộc động: `reward_per_claim > 0`, `max_claims > 0`, `reserved_credits = max_claims * reward_per_claim`.
2. **Cổng kiểm tra `publish_app_update` (Legacy):**
   - Kiểm tra cả 2 điều khoản: `reward_per_claim = 6` VÀ `max_claims = 20`. Nếu bất kỳ điều khoản nào thay đổi, ném lỗi `CLIENT_UPDATE_REQUIRED` (kể cả khi tích vẫn bằng 120, ví dụ 4 * 30).
3. **RPC V2 `publish_app_update_v2`:**
   - Bắt buộc kiểm tra `p_expected_budget` khớp với `reward_per_claim * max_claims`.
   - Kiểm tra `p_expected_revision` khớp với quote token của đợt cập nhật app.
   - Hỗ trợ replay an toàn qua `app_update_operations`.
   - Bảo toàn số dư và khả năng hoàn tiền của các đợt cập nhật cũ đang hoạt động (`claim_app_update_reward`, `close_app_update_release`).
