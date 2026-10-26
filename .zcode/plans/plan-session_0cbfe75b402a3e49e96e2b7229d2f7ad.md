Kế hoạch sửa 4 lỗi trong luồng Credit (tất cả sửa client-side Kotlin, không đổi server)

## Bước 1 — Sửa lỗi cache singleton sau khi nộp báo cáo [Cao]
`app/src/main/java/com/ntt/crosstest/work/ReportUploadWorker.kt`
- Thay `val repository = CampaignRepository(SupabaseHolder.get(), Localizer(applicationContext))` bằng instance dùng chung từ Application:
  ```kotlin
  val repository = (applicationContext as TestCheoApp).campaignRepository
  ```
- An toàn vì worker đã return sớm khi `!SupabaseHolder.isInitialized()`, và `campaignRepository` chỉ được init cùng lúc với SupabaseHolder trong `TestCheoApp.onCreate()`.
- Hiệu quả: `submitFeedback()` giờ invalidate đúng cache của singleton → quay lại Home badge Credit cập nhật ngay (count-up chạy đúng), CampaignDetail refresh thấy báo cáo vừa nộp.

## Bước 2 — Xử lý đúng `status` của RPC topup [Trung bình]
`app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt` — hàm `processTopup`
- Sau khi có `res`, chỉ paint balance/overlay khi `res.status == "success"` (và/hoặc `res.creditsAwarded > 0`); ngược lại gọi `onResult(false, res.message)` và không đổi `userCredits`.
- Giữ nguyên mock purchase token (flag topup đang tắt) — không mở rộng phạm vi sang Billing.

## Bước 3 — Chống số dư 0 sau khi nhận thưởng quest [Trung bình]
`HomeViewModel.kt` — hàm `claimQuestReward`
- Đổi `userCredits = res.newBalance` thành:
  ```kotlin
  userCredits = res.newBalance.takeIf { it > 0 } ?: updatedProfile?.credits ?: state.userCredits
  ```
- Nhất quán với pattern guard đã dùng trong cùng hàm (reward card) và trong `processTopup`.

## Bước 4 — Thống nhất timezone VN cho các tính toán "hôm nay" [Thấp]
- Tạo helper dùng chung (ví dụ `object VnTime { fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")) }` — đặt trong `util/TimeFormat.kt` đã có).
- Áp dụng tại 2 chỗ đang lệch:
  - `CampaignDetailViewModel.refresh()` legacy path: `val today = LocalDate.now().toString()` → `VnTime.today().toString()`
  - `CampaignRepository.getQuestSummary()` fallback: `val today = LocalDate.now().toString()` → `VnTime.today().toString()`
  - (HomeViewModel có sẵn `vnToday()` riêng — có thể chuyển dùng helper chung nhưng không bắt buộc.)
- Không đụng timezone của server RPC (đã đúng theo migration 036).

## Kiểm chứng
- `./gradlew assembleDebug` build pass.
- Chạy unit test hiện có nếu có (`./gradlew test`).
- Kịch bản test thủ công (mô tả trong PR):
  1. Nộp báo cáo ở CampaignDetail → quay lại Home ngay → badge Credit tăng ngay với animation (trước đây chờ tối đa 3 phút).
  2. Claim quest reward → badge không bao giờ hiển thị 0.
  3. CampaignDetail sau khi nộp → refresh thấy báo cáo pending, status "Chờ duyệt" đúng.
  4. Build tiếng Việt/Anh vẫn hiển thị copy reward nhất quán.

## Ngoài phạm vi (ghi nhận, không sửa)
- Tích hợp Google Play Billing thật cho topup: giữ `IS_TOPUP_FEATURE_ENABLED = false` cho đến khi có Billing SDK + verify receipt server-side.
- Logic Credit server-side (RPC) đã nguyên tử và có idempotency qua `client_submit_id` — không cần đổi.