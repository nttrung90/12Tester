# Báo cáo Mismatch và Backend Gaps — Android Business Policy & Financial Consumers

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-android-backend-gaps.md`  
**Chặng:** C — Android Business Policy Editor & Financial Consumers  
**Ngày:** 01/11/2026  
**Thực hiện:** OpenCode (`google/antigravity-gemini-3.8-flash`, `--variant high`)  

---

## 1. Tách biệt Revision Token: Báo giá (Quote Token) vs Quản trị CAS (Admin CAS Revision)

### Tình trạng & Phát hiện độc lập từ Codex
- **Phát hiện:** Trước khi sửa, hàm `get_business_quote` trả về mã băm toàn bảng `_get_business_policy_revision()`. Khi một chính sách tương lai đã lập lịch kích hoạt lúc 00:00 (giờ VN), dữ liệu bảng không đổi nên mã băm không đổi. Do đó, client xác nhận giá/thời hạn cũ nhưng backend lại tính theo chính sách mới đã kích hoạt mà không phát hiện được xung đột.
- **Giải pháp Backend:** Backend C chặng C2/quote fix đã phân tách rõ:
  1. `admin_get_business_policy_config().revision`: Token CAS dùng cho thao tác lập lịch (`admin_schedule_business_policy`) và hủy lịch (`admin_cancel_business_policy`).
  2. `get_business_quote().revision`: Opaque quote token ràng buộc chặt với danh tính chính sách hiệu lực, ngày hiệu lực và các điều khoản cụ thể của báo giá (`cost`, `renewal_days`, `reward_per_claim`, `max_claims`, `reserved_budget`).
- **Phía Android:** 
  - `AdminBusinessPolicyViewModel` dùng riêng `config.revision` cho form lập lịch/hủy lịch CAS.
  - Các consumer tài chính (`ProfileViewModel`, `CreateCampaignViewModel`, `OwnerAppDetailViewModel`, `AppUpdateReleaseViewModel`) nhận `quote.revision` như một chuỗi opaque và truyền nguyên vẹn sang các RPC v2 (`push_campaign_v2`, `unlock_or_renew_slot_v2`, `publish_app_update_v2`). Tuyệt đối không hoán đổi giữa 2 loại token.

---

## 2. Trạng thái các Financial Consumers V2

### 2.1 Mở khóa / Gia hạn Slot (`unlock_or_renew_slot_v2`)
- **Backend contract:** Yêu cầu `(p_slot_number, p_client_request_id, p_expected_cost, p_expected_revision)`.
- **Android client:**
  - `CampaignRepository.getMySlotsInfo`: Đọc báo giá động từ server qua `get_business_quote("all")` hoặc `get_effective_business_policy()`, gán đúng `cost`, `renewalDays` và `policyRevision`.
  - `ProfileViewModel` & `CreateCampaignViewModel`: Kiểm tra số dư theo giá báo giá thực tế (không dùng `SlotInfo.costForSlot` cứng); bắt buộc truyền `expectedCost` và `expectedRevision` sang `unlock_or_renew_slot_v2`.
  - **Chống trừ tiền ngầm:** Khi gặp lỗi `BUSINESS_POLICY_CONFLICT` hoặc `BUSINESS_POLICY_PRICE_MISMATCH`, ViewModel làm mới lại danh sách/báo giá, hiển thị giá mới và bắt buộc người dùng xác nhận lại trong dialog; không bao giờ tự ý lấy giá mới rồi trừ tiền ngầm.
  - **Idempotency:** Request ID được lưu giữ qua cùng một confirmation intent; khi người dùng xác nhận intent mới (hoặc sau khi giá đổi), một UUID mới được sinh ra.

### 2.2 Đẩy ứng dụng lên đầu (`push_campaign_v2`)
- **Backend contract:** Yêu cầu `(p_campaign_id, p_client_request_id, p_expected_cost, p_expected_revision)`.
- **Android client:**
  - `OwnerAppDetailViewModel`: Đọc báo giá đẩy app động từ server (`pushCost`, `pushRevision`), loại bỏ kiểm tra cứng `100 Credit`.
  - Hộp thoại xác nhận hiển thị đúng `pushCost` và số dư dự kiến sau khi trừ.
  - Bảo toàn tuyệt đối cơ chế `CampaignPushStore` durable pending request ID từ migration 066.
  - Khi có xung đột giá (`BUSINESS_POLICY_PRICE_MISMATCH`), xóa pending request ID cũ, tải báo giá mới và yêu cầu xác nhận lại.

### 2.3 Đăng ký phiên bản mới (`publish_app_update_v2`)
- **Backend contract:** Yêu cầu `(p_campaign_id, p_version_name, p_target_version_code, p_release_notes, p_client_operation_id, p_expected_budget, p_expected_revision, p_expected_current_release_id)`.
- **Android client:**
  - `AppUpdateReleaseViewModel`: Đọc `reward_per_claim`, `max_claims`, `reserved_budget` và `revision` từ `getAppUpdateQuote()`.
  - Thuộc tính `projectedRemaining` và `hasEnoughCredits` tính toán dựa trên `reservedBudget` động thay vì hardcode 120.
  - Giao diện hiển thị quy tắc ngân sách động theo chính sách hiện hành (`Giữ trước X Credit • Y Credit/người • Tối đa Z người`).
  - Đợt release cũ trong lịch sử bảo toàn nguyên vẹn snapshot của chính bản ghi đó (`release.rewardPerClaim`, `release.maxClaims`, `release.reservedCredits`), không bị biến đổi theo chính sách hiện tại.

---

## 3. Khoảng trống dữ liệu Tester Reports Lifecycle

- **Snapshot trường thưởng duyệt:**
  - Backend đang bổ sung các cột `review_tester_bonus`, `review_owner_bonus` và `policy_id` vào bảng `tester_reports` tại thời điểm nộp báo cáo.
  - Android DTO (`TesterReport`) đã được thêm các trường tùy chọn:
    ```kotlin
    @SerialName("review_tester_bonus") val reviewTesterBonus: Int? = null,
    @SerialName("review_owner_bonus") val reviewOwnerBonus: Int? = null,
    @SerialName("policy_id") val policyId: Long? = null,
    ```
    kèm các helper getters an toàn (`effectiveTesterBonus = reviewTesterBonus ?: 2`, `effectiveOwnerBonus = reviewOwnerBonus ?: 2`, `effectiveProvisionalReward = if (provisionalGrantedCredits > 0) provisionalGrantedCredits else 10`).
  - Khi backend staged 071 hoàn tất đưa vào hoạt động, client sẽ tự động ánh xạ đúng giá trị snapshot mà không cần sửa đổi thêm code DTO.

---

## 4. Tóm tắt tương thích ngược

| Thao tác | Hành vi Client cũ | Hành vi Client Mới (Phiên bản này) |
| :--- | :--- | :--- |
| Đẩy app (`push`) | Gọi `push_campaign` (bị lỗi `CLIENT_UPDATE_REQUIRED` nếu phí != 100) | Đọc báo giá trước, gọi `push_campaign_v2` với expectedCost & expectedRevision |
| Mở/Gia hạn Slot | Gọi `unlock_or_renew_slot` (bị lỗi `CLIENT_UPDATE_REQUIRED` nếu phí/ngày đổi) | Đọc báo giá trước, gọi `unlock_or_renew_slot_v2` với expectedCost & expectedRevision |
| Cập nhật app | Gọi `publish_app_update` (bị lỗi `CLIENT_UPDATE_REQUIRED` nếu budget != 120) | Đọc báo giá trước, gọi `publish_app_update_v2` với expectedBudget & expectedRevision |
