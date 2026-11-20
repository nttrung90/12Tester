# Biên bản Bàn giao Sửa lỗi Kích hoạt Báo giá (Business Quote Activation Fix Handover)

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-stage/business-quote-fix-handover.md`  
**Chặng:** C2 Fix — Quote Activation Token Rotation & Stale Quote Rejection  
**Ngày:** 01/11/2026  
**Thực hiện:** OpenCode (`google/antigravity-gemini-3.8-flash`, `--variant high`)  
**Kết quả kiểm thử:**
- `audit/admin-expansion-2026-11-01/independent-business-quote-probe.mjs`: Đạt cả 2 ca kiểm thử độc lập (`slotRevisionChanged: true`, `updateRevisionChanged: true`).
- `supabase/tests/run-business-policies-test.mjs`: Đạt 100% 13/13 ca kiểm thử PGlite, bao gồm regression date seam kiểm tra stale quote bị từ chối không trừ tiền và replay receipt cũ thành công sau kích hoạt.
- Toàn bộ regression suites `run-campaign-push-test.mjs` (19/19), `run-push-and-carryover-hardening-test.mjs` (10/10) và `run-admin-management-test.mjs` (30/30) đạt 100%.

---

## 1. Vấn đề phát hiện và Nguyên nhân gốc rễ

Trước khi sửa đổi:
- Mã xác thực báo giá trong `get_business_quote(...)` trả về hàm băm CAS của toàn bảng chính sách `_get_business_policy_revision()`.
- Khi một chính sách đã được quản trị viên lập lịch trước bước qua thời điểm 00:00 (giờ VN) để trở thành chính sách kích hoạt, bảng `business_policies` không có bất kỳ thao tác ghi (INSERT/UPDATE/DELETE) nào.
- Do đó, mã băm CAS của toàn bảng giữ nguyên hoàn toàn.
- Hậu quả: Một báo giá Slot 2 với giá 50 Credit và thời hạn 30 ngày hôm trước có `revision` y hệt như báo giá hôm sau khi thời hạn đã bị rút xuống còn 1 ngày (dù đơn giá vẫn là 50 Credit). Tương tự, đợt cập nhật app chuyển từ 6 credit * 20 claims (120) sang 4 credit * 30 claims (120) vẫn giữ nguyên revision.
- Người dùng có thể xác nhận một điều khoản nhưng bị áp dụng điều khoản khác mà không phát hiện được sự thay đổi.

---

## 2. Giải pháp đã thực hiện trong Staged `071_business_policies.sql`

1. **Hàm sinh Opaque Quote Token riêng biệt (`_get_business_quote_token`):**
   - Tách biệt hoàn toàn mã băm CAS quản trị (`_get_business_policy_revision`) khỏi token báo giá giao dịch người dùng.
   - Hàm `public._get_business_quote_token(p_quote_type, p_policy, p_params)` sinh mã băm MD5 gắn kết chặt chẽ:
     - Định danh chính sách hiệu lực: `policy.id`, `policy.effective_from`, `policy.updated_at`.
     - Điều khoản chi tiết theo từng loại quote:
       - Push: `push_fee`.
       - Slot: `slot_number`, đơn giá cụ thể của slot đó, và `slot_renewal_days`.
       - App update: `reward_per_claim`, `max_claims`, và `reserved_budget`.
       - All: Toàn bộ bảng giá và hạn mức tài chính.
   - Khi chính sách tương lai kích hoạt (hoặc qua date seam), `p_policy` chuyển sang dòng mới -> **Quote token tự động xoay vòng (rotates automatically) ngay lập tức mà không cần thao tác ghi cơ sở dữ liệu**.

2. **Cập nhật RPC Báo giá (`get_business_quote`):**
   - Trả về `revision` (và `quote_token`) chứa opaque token đã băm theo điều khoản hiệu lực.
   - Bổ sung `policy_id` và `effective_from` vào payload JSON trả về cho client.

3. **Cập nhật Kiểm tra Độ tươi Báo giá trong các Financial Consumers (`push_campaign_v2`, `unlock_or_renew_slot_v2`):**
   - **Replay trước kiểm tra độ tươi:** Nếu `client_request_id` đã từng được thực hiện thành công trong quá khứ, trả lại ngay biên lai cũ với đúng số tiền và thời hạn đã ghi nhận, không bị chặn bởi quote token mới.
   - **Xác thực Quote Token khi ghi mới:** So sánh `p_expected_revision` với quote token cụ thể của giao dịch (hoặc token của quote tổng hợp 'all'). Nếu client gửi token cũ đã hết hạn do chính sách mới kích hoạt:
     - Ném lỗi: `BUSINESS_POLICY_CONFLICT: Báo giá slot đã hết hạn hoặc điều khoản đã thay đổi. Vui lòng lấy lại báo giá.` (hoặc thông báo tương ứng cho push).
     - **Không trừ credit của người dùng, không tạo credit log và không tạo receipt mới**.

4. **Lưu ý Cổng kiểm tra Legacy App Update cho chặng sau:**
   - Trong chặng kế tiếp, cổng `publish_app_update` (legacy) sẽ kiểm tra chặt chẽ cả 2 điều khoản `reward_per_claim = 6` VÀ `max_claims = 20`, không chỉ kiểm tra tích số bằng 120.

---

## 3. Kết quả Kiểm thử Độc lập và Regression

### 3.1 Probe kiểm tra độc lập (`independent-business-quote-probe.mjs`)
```json
[
  {
    "name": "quote revision changes when scheduled policy activates without DB edit",
    "slotBefore": {
      "cost": 50,
      "revision": "dd7dbfaf33af46d8459ec216e656789b",
      "policy_id": 1,
      "quote_type": "slot",
      "quote_token": "dd7dbfaf33af46d8459ec216e656789b",
      "slot_number": 2,
      "renewal_days": 30,
      "effective_from": null
    },
    "slotAfter": {
      "cost": 50,
      "revision": "d55330f3a1f91a934dd4a2e763818de0",
      "policy_id": 2,
      "quote_type": "slot",
      "quote_token": "d55330f3a1f91a934dd4a2e763818de0",
      "slot_number": 2,
      "renewal_days": 1,
      "effective_from": "2026-11-02"
    },
    "slotRevisionChanged": true,
    "updateBefore": {
      "revision": "e7e56a08235e82acb3775065a05e42b9",
      "policy_id": 1,
      "max_claims": 20,
      "quote_type": "app_update",
      "quote_token": "e7e56a08235e82acb3775065a05e42b9",
      "effective_from": null,
      "reserved_budget": 120,
      "reward_per_claim": 6
    },
    "updateAfter": {
      "revision": "8af36b7d0082136709745f9af34da105",
      "policy_id": 2,
      "max_claims": 30,
      "quote_type": "app_update",
      "quote_token": "8af36b7d0082136709745f9af34da105",
      "effective_from": "2026-11-02",
      "reserved_budget": 120,
      "reward_per_claim": 4
    },
    "updateRevisionChanged": true
  }
]
```

### 3.2 Bộ kiểm thử toàn diện (`run-business-policies-test.mjs`)
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
PASS: 13. Quote activation token rotation & stale quote rejection with date seam
=== TẤT CẢ CÁC BƯỚC KIỂM THỬ FOUNDATION VÀ FEES CONSUMERS ĐÃ VƯỢT QUA THÀNH CÔNG ===
```

---

## 4. Trạng thái và Kế hoạch tiếp theo

- Đã hoàn tất việc sửa lỗi quote activation token, cập nhật hợp đồng và kiểm thử.
- Đúng theo chỉ đạo, **không triển khai các consumer Report / Release / Penalty trong lượt này**.
- Lượt kế tiếp sẽ bắt đầu triển khai nhóm Daily Penalty Tiers, Tester Reports Lifecycle, và App Update Releases.
