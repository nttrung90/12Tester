# Biên bản Bàn giao Hoàn tất Chặng C4 & Sửa Lỗi Tích hợp Token Báo giá (Business Policy C4 Final Backend Handover)

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-stage/business-c4-handover.md`  
**Chặng:** C4 Final — App Update Lifecycle Consumers & All-Quote Token Replay Integration Fix  
**Ngày:** 01/11/2026  
**Thực hiện:** OpenCode (`google/antigravity-gemini-3.8-flash`, `--variant high`)  
**Tình trạng kiểm thử:**
- Probe độc lập từ Root `audit/admin-expansion-2026-11-01/independent-slot-all-quote-replay-probe.mjs`: **PASS 100%** (`replayed: true, balance: 950, error: null`).
- Probe độc lập `audit/admin-expansion-2026-11-01/independent-business-quote-probe.mjs`: **PASS 100%** (`slotRevisionChanged: true, updateRevisionChanged: true`).
- Toàn bộ 21/21 ca kiểm thử PGlite trong `supabase/tests/run-business-policies-test.mjs`: **PASS 100%**.
- Suite kiểm thử tích hợp toàn bộ hệ thống `npm run test:admin-expansion` nạp chuỗi chính thức `062 -> 073`: **PASS 100%** (`test:business-policies`, `test:notification-campaigns`, `test:admin-action-audit`, `test:admin-expansion-integration`, `test:admin-direct-popup`).
- Toàn bộ các suite hồi quy (`run-app-update-reward-test.mjs` 28/28, `run-campaign-push-test.mjs` 19/19, `run-push-and-carryover-hardening-test.mjs` 10/10, `run-report-idempotency-ledger-test.mjs` 9/9, `run-owner-tester-reports-count-test.mjs` 9/9, `run-admin-daily-task-policy-test.mjs` 10/10, `run-daily-task-carryover.mjs` 10/10, `run-admin-management-test.mjs` 30/30): **PASS 100%**.

---

## 1. Chi tiết Lỗi Tích hợp Đã Khắc phục (All-Quote Token Replay Fix)

### 1.1 Hiện tượng và Nguyên nhân
- Trong Android `CampaignRepository.getMySlotsInfo()`, ứng dụng gọi `get_business_quote('all')` để lấy báo giá tổng hợp và gán `SlotInfo.policyRevision` bằng token `ALL` nhận được.
- Khi mua hoặc gia hạn slot, client gọi `unlock_or_renew_slot_v2(p_slot_number, client_request_id, expected_cost, expected_revision)`.
- Hàm `unlock_or_renew_slot_v2` cho phép chấp nhận cả token `slot` riêng biệt lẫn token `all` khi xác thực độ tươi báo giá lúc ghi mới.
- **Lỗ hổng:** Khi lưu biên lai vào `slot_purchase_receipts`, cột `quote_token` lại lưu `v_current_rev` (vốn là token `slot` sinh từ `_get_business_quote_token('slot', ...)`), thay vì lưu chính token đã được xác nhận (`p_expected_revision`).
- **Hậu quả:** Khi ứng dụng Android gửi lại request ID cũ cùng token `all` gốc (trong tình huống lost-reply / retry mạng), điều kiện replay kiểm tra `v_existing_receipt.quote_token <> p_expected_revision` bị kích hoạt và ném lỗi `REQUEST_ID_CONFLICT: Yêu cầu bị trùng với điều khoản báo giá khác`. Điều này làm phá vỡ cơ chế hồi phục giao dịch tự động của Android.

### 1.2 Giải pháp Triển khai Đồng bộ
1. **Lưu trữ Cặp Token trong `slot_purchase_receipts`:**
   - Bổ sung cột `canonical_token text` vào `slot_purchase_receipts` bên cạnh `quote_token text`.
   - Lưu `quote_token = v_confirmed_token` (token mà client thực sự gửi và đã được chấp nhận, ví dụ token `all`).
   - Lưu `canonical_token = v_current_rev` (token `slot` chuẩn tắc theo đúng thông số slot và ngày hiệu lực đó).
2. **Kiểm tra Replay Linh hoạt và Chặt chẽ:**
   - Trong nhánh replay (cả trước khi lấy khóa và dưới khóa), kiểm tra:
     ```sql
     if p_expected_revision is not null and v_existing_receipt.quote_token is not null then
         if p_expected_revision <> v_existing_receipt.quote_token and
            p_expected_revision is distinct from v_existing_receipt.canonical_token then
             raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng với điều khoản báo giá khác';
         end if;
     end if;
     ```
   - Cho phép replay thành công cả khi client gửi lại đúng token `all` gốc lẫn khi client gửi token `slot` tương đương của cùng thời điểm giao dịch.
   - Trả về `'quote_token', v_existing_receipt.quote_token` và `'renewal_days', coalesce(v_existing_receipt.renewal_days, 30)`.
   - Nếu client gửi cùng request ID nhưng mang token của một chính sách khác hoặc điều khoản đã thay đổi, backend lập tức chặn lại với `REQUEST_ID_CONFLICT` (không trừ thêm tiền, không thay đổi biên lai gốc).
3. **Bảo toàn Replay Sau khi Chính sách Lập lịch Kích hoạt:**
   - Ngay cả sau khi chính sách mới kích hoạt vào nửa đêm (ví dụ thời hạn rút ngắn xuống 15 ngày), request ID cũ re-send với token `all` ban đầu vẫn được xác nhận và trả về đúng thời hạn 30 ngày và đơn giá 50 ban đầu.

---

## 2. Hoàn tất Toàn bộ Consumer Vòng đời Cập nhật Ứng dụng (App Update C4)

1. **Ràng buộc Mức Động (Dynamic Constraints):**
   - Đã thay thế các ràng buộc cố định cũ trên `app_update_claims` và `app_update_releases` bằng ràng buộc động theo miền giá trị `1..1000`.
   - Bổ sung cột `business_policy_id bigint REFERENCES public.business_policies(id)` trên `app_update_releases`.
   - Bảo toàn 100% bất biến kế toán: `reserved_credits = (remaining_credits + paid_count * reward_per_claim + refunded_credits)`.
2. **Phát hành Cập nhật App (`publish_app_update` & `publish_app_update_v2`):**
   - Đặt khóa tuần tự hóa `app_update_op:actor_id:operation_id` ngay đầu hàm trước khi kiểm tra replay, ngăn ngừa hoàn toàn xung đột race condition giữa các package khác nhau.
   - `publish_app_update` (Legacy) từ chối `CLIENT_UPDATE_REQUIRED` nếu bất kỳ thông số nào khác mặc định (`reward_per_claim != 6` HOẶC `max_claims != 20`, kể cả trường hợp tích số vẫn bằng 120 như 4*30). Replay các đợt phát hành cũ thành công kể cả sau khi đổi chính sách.
   - `publish_app_update_v2` bắt buộc `p_expected_budget` và `p_expected_revision`.
   - Phân biệt chính xác `p_expected_current_release_id` là `NULL` (bắt buộc hiện chưa có đợt active nào) so với sentinel `ffffffff-ffff-ffff-ffff-ffffffffffff` (dành riêng cho caller legacy).
3. **Nhận Thưởng (`claim_app_update_reward`):**
   - Sử dụng snapshot `reward_per_claim` và `max_claims` của chính đợt phát hành đó, không dùng chính sách toàn cục tại thời điểm claim.
   - Ghi sự kiện tài chính `app_update_financial_events` với `event_type = 'reward'` và `owner_id = coalesce(owner_id, owner_id_snapshot)` (đạt ràng buộc NOT NULL).
   - Khôi phục đầy đủ biên lai receipt trả về chứa `claim_id`, `release_id`, `ordinal`, `reward_credits`, `confirmed_at` đúng chuẩn Migration 062.
4. **Đóng Đợt Sớm (`close_app_update_release`):**
   - Đọc số dư `remaining_credits` thực tế chưa chi và hoàn lại nguyên vẹn cho chủ app, trả về payload chứa `refund_amount`, `owner_credits`, `release_status = 'closed'`.
5. **Thu hồi Quyền Private Helpers:**
   - Thu hồi toàn bộ quyền EXECUTE trên 5 hàm private helper khỏi `PUBLIC`, `anon`, `authenticated`. Kiểm tra thực tế xác nhận member bị từ chối `permission denied`.

---

## 3. Khớp Hoàn toàn Mã Băm SHA256 (Canonical Mirror Verification)

Đã cập nhật đồng bộ cả hai tệp:
- `supabase/migrations/071_business_policies.sql` (bản active migration chính thức)
- `audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql` (bản staged mirror)

**Mã băm SHA256 đối chiếu (bit-for-bit identical):**
```text
supabase/migrations/071_business_policies.sql:
54bee66ac2094032d355579e0b1b615efc5311648bce8bd4c0710321f3fca515

audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql:
54bee66ac2094032d355579e0b1b615efc5311648bce8bd4c0710321f3fca515
```

---

## 4. Kết quả Kiểm thử Tổng hợp (Full Test Suites)

### 4.1 Root Independent Probe (`independent-slot-all-quote-replay-probe.mjs`)
```json
{
  "firstSuccess": true,
  "originalToken": "48581e95c8a5be947ab466e8fa157275",
  "receiptToken": "48581e95c8a5be947ab466e8fa157275",
  "replayed": true,
  "error": null,
  "balance": 950
}
```

### 4.2 Independent Quote Probe (`independent-business-quote-probe.mjs`)
```json
[
  {
    "name": "quote revision changes when scheduled policy activates without DB edit",
    "slotBefore": {
      "cost": 50,
      "revision": "a2be491af438b79e147a76d05a9ac8de",
      "renewal_days": 30
    },
    "slotAfter": {
      "cost": 50,
      "revision": "4e97a445fe823d51c98714978a9b4e4e",
      "renewal_days": 1
    },
    "slotRevisionChanged": true,
    "updateBefore": {
      "revision": "c338954fe58bed80285c68fa156cafa5",
      "max_claims": 20,
      "reward_per_claim": 6
    },
    "updateAfter": {
      "revision": "450f7ca1c68d2c6a1d4aef4adb0a8e12",
      "max_claims": 30,
      "reward_per_claim": 4
    },
    "updateRevisionChanged": true
  }
]
```

### 4.3 Behavioral Suite (`supabase/tests/run-business-policies-test.mjs` — 21/21 PASS)
```text
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
PASS: 19. Helper ACLs: Private helpers denied to authenticated & anon roles
PASS: 20. App Update Lifecycle: publish v2, claim snapshots, close/refund, legacy gate & replays
PASS: 21. Real get_business_quote(all) confirmation, immediate & post-activation replay, token mismatch
=== TẤT CẢ CÁC BƯỚC KIỂM THỬ FOUNDATION VÀ CONSUMERS ĐÃ VƯỢT QUA THÀNH CÔNG ===
```

### 4.4 Hệ thống Kiểm thử Toàn chuỗi Canonical (`npm run test:admin-expansion` — 100% PASS)
- `test:business-policies`: PASS (21/21 steps)
- `test:notification-campaigns`: PASS (18/18 steps)
- `test:admin-action-audit`: PASS (13/13 steps)
- `test:admin-expansion-integration`: PASS (5/5 steps)
- `test:admin-direct-popup`: PASS (11/11 steps)
- Toàn bộ các suite hồi quy kế thừa (`050/051`, `052`, `053/054`, `068`, `069`, `070`): PASS 100%.

---

## 5. Kết luận

Toàn bộ các yêu cầu của giai đoạn Business Backend (từ Foundation C1, Fees & Slots C2, Quote Activation Fix, Penalty & Reports C3, đến App Update & Token Integration Fix C4) đã hoàn thiện đầy đủ, đáp ứng mọi kiểm tra độc lập và sẵn sàng để quản trị hệ thống tiến hành các bước tiếp theo.
