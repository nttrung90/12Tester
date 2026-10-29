# BÁO CÁO BÀN GIAO TÍNH NĂNG "ĐẨY APP" (CAMPAIGN PUSH PRIORITY)

- **Ngày bàn giao:** 29/10/2026
- **Dự án:** 12Tester (Android & Supabase Backend)
- **Tài liệu căn cứ:** `KE_HOACH_DAY_APP_2026-10-29.md`
- **Trạng thái:** Hoàn thành triển khai code, migration, kiểm thử backend và unit test client, build debug thành công. Sẵn sàng nghiệm thu trên môi trường kiểm thử (staging).

---

## 1. TỔNG QUAN TÍNH NĂNG & MỨC ĐỘ ĐÁP ỨNG YÊU CẦU

| STT | Yêu cầu nghiệp vụ bắt buộc | Trạng thái | Chi tiết triển khai |
| :--- | :--- | :---: | :--- |
| 1 | Thêm nút "Đẩy app" ngay phía trên "Tạm dừng nhận Tester", mô tả chính xác: *"Trả 100 Credit để đưa ứng dụng của bạn lên đầu danh sách kiểm thử"* | **ĐẠT** | Bổ sung item `OwnerActionItem` trong `OwnerAppDetailScreen.kt`, hiển thị khi là chủ sở hữu thực sự và app chưa hoàn tất (`!isFinalized`). |
| 2 | Hộp thoại xác nhận chi phí 100 Credit, hạn 00:00 ngày kế tiếp (giờ VN), hiển thị số dư hiện tại & sau đẩy, nút "Hủy" và "Xác nhận đẩy app". Hủy không trừ tiền. Mỗi lượt mua phải xác nhận lại. | **ĐẠT** | Dialog xác nhận chuẩn Material 3 với đầy đủ cảnh báo, tính toán `balanceAfterPreview`, kiểm tra số dư đủ điều kiện, ngăn double-tap, đóng form reset toàn bộ state. |
| 3 | Backend trừ đúng 100 Credit và ghi nhận lượt đẩy trong cùng 1 transaction. Số dư < 100 từ chối (`INSUFFICIENT_CREDITS`), đúng 100 được thực hiện (về 0). | **ĐẠT** | RPC `push_campaign` trên PostgreSQL khóa row `profiles` và `campaigns`, kiểm tra số dư nghiêm ngặt, trừ 100 Credit, ghi `credit_logs` (action = `campaign_push`), tạo receipt và gán sequence trong 1 atomic transaction. |
| 4 | App được đẩy đứng trước app thông thường (kể cả đã đạt chỉ tiêu ngày). Lượt đẩy sau đứng trên lượt trước (`push_order DESC`). Cho phép mua lại trong ngày. | **ĐẠT** | Cập nhật bộ sắp xếp `DailyHomeOrder.sortOpenCampaigns`, tách nhóm `isPushActiveToday` lên hàng ưu tiên số 1, sort theo `pushOrder DESC`. Sequence toàn cục `campaign_push_order_seq` đảm bảo lượt mua sau luôn có thứ tự cao hơn lượt mua trước. |
| 5 | Hiệu lực kết thúc lúc 00:00 ngày kế tiếp theo `Asia/Ho_Chi_Minh`. Sau đó tự trở lại thuật toán cũ. Không sửa cơ chế Boost gắn với tip. | **ĐẠT** | Ngày kinh doanh xác định bằng `(now() at time zone 'Asia/Ho_Chi_Minh')::date`. Hạn chót là 00:00 ngày tiếp theo. Client so sánh `pushBusinessDate == VnTime.today()`. Khi hết ngày, app tự động quay về phân loại thông thường (vẫn giữ nguyên boost/tip sorting). |
| 6 | Chống tính phí trùng (double tap, timeout, retry, process death) bằng Request ID và Receipt phía server. Retry cùng giao dịch không trừ thêm, không đổi thứ tự. | **ĐẠT** | Client sinh `client_request_id` (UUIDv4) bền vững qua `CampaignPushStore` (SharedPreferences) trước khi phát request. Server lưu bảng `campaign_push_receipts` unique constraint trên `client_request_id`. Khi trùng lặp, trả về receipt cũ với cờ `replayed = true`. Khác payload/campaign báo lỗi `REQUEST_ID_CONFLICT`. |
| 7 | Bảo vệ quyền mua và metadata trên backend; cập nhật đầy đủ model, RPC, fallback, cache, số dư, lịch sử Credit và bản dịch Việt/Anh. | **ĐẠT** | Trigger `trg_protect_campaign_push_metadata` chặn client sửa trực tiếp metadata. RLS table `campaign_push_receipts`. Invalidate profile cache, clear credit logs cache, cập nhật cache session và dashboard. Đầy đủ strings tiếng Việt, tiếng Anh và default. |

---

## 2. DANH SÁCH FILE THAY ĐỔI VÀ TẠO MỚI

### A. Backend & Migrations (Supabase)
1. **`supabase/migrations/052_campaign_push_priority.sql`** *(Tạo mới)*:
   - Sequence: `public.campaign_push_order_seq`.
   - Cột bổ sung trong bảng `campaigns`: `push_business_date` (DATE), `push_expires_at` (TIMESTAMPTZ), `push_order` (BIGINT).
   - Ràng buộc: `chk_campaign_push_metadata_consistency` đảm bảo 3 cột cùng NULL hoặc cùng có giá trị hợp lệ.
   - Bảng receipt: `public.campaign_push_receipts` lưu vết giao dịch tài chính, RLS chỉ cho chủ sở hữu đọc receipt của mình.
   - Trigger: `trg_protect_campaign_push_metadata` chặn người dùng trực tiếp sửa cột `push_*` qua postgrest.
   - RPC: `public.push_campaign(p_campaign_id UUID, p_client_request_id UUID)` thực thi nguyên tử trừ 100 Credit, ghi log, cập nhật metadata và sinh receipt.
   - Cập nhật hàm `_campaign_json` để xuất đầy đủ 3 trường metadata.
2. **`supabase/full_schema_singapore.sql`** *(Cập nhật)*:
   - Ghép toàn bộ định nghĩa từ migration 052 vào schema tổng thể.
3. **`supabase/tests/run-campaign-push-test.mjs`** *(Tạo mới)*:
   - Bộ test runner gồm 19 ca kiểm thử chuyên sâu cho migration 052 trên PGlite WASM.

### B. Client Android (Kotlin / Jetpack Compose)
1. **`app/src/main/res/values-vi/strings.xml`** *(Cập nhật)*: Bổ sung chuỗi tiếng Việt cho nút, mô tả, dialog xác nhận, thông báo thành công và các mã lỗi đẩy app.
2. **`app/src/main/res/values-en/strings.xml`** *(Cập nhật)*: Bổ sung chuỗi tiếng Anh tương ứng.
3. **`app/src/main/res/values/strings.xml`** *(Cập nhật)*: Bổ sung chuỗi mặc định (fallback).
4. **`app/src/main/java/com/ntt/crosstest/data/Models.kt`** *(Cập nhật)*:
   - Thêm `pushBusinessDate`, `pushExpiresAt`, `pushOrder` và helper `isPushActive(currentDate)` vào `Campaign`.
   - Thêm data class `@Serializable data class PushCampaignResult`.
5. **`app/src/main/java/com/ntt/crosstest/data/CampaignPushStore.kt`** *(Tạo mới)*:
   - Quản lý và lưu trữ `client_request_id` bền vững theo cặp `(userId, campaignId)` vào SharedPreferences.
6. **`app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`** *(Cập nhật)*:
   - Thêm `push_business_date`, `push_expires_at`, `push_order` vào `CAMPAIGN_COLUMNS`.
   - Cập nhật `invalidateProfile()` để xóa đồng thời `cachedCreditLogs = null`.
   - Thêm hàm `suspend fun pushCampaign(campaignId, clientRequestId): PushCampaignResult` xử lý gọi RPC, xử lý lỗi nghiệp vụ, xóa cache và đồng bộ in-memory cache qua `SessionCacheManager`.
7. **`app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt`** *(Cập nhật)*:
   - Bổ sung helper `updateCachedData(transform: (CachedSessionData) -> CachedSessionData)` hỗ trợ cập nhật dữ liệu session tức thời.
8. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/DailyHomeOrder.kt`** *(Cập nhật)*:
   - Sửa hàm `sortOpenCampaigns`: phân tách các app có `isPushActiveToday == true` đưa lên đầu danh sách, sắp xếp thứ tự giảm dần theo `pushOrder DESC`, giải quyết hòa bằng `stableId ASC`. Sau đó mới đến danh sách các app thông thường sắp xếp theo thuật toán gốc.
9. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt`** *(Cập nhật)*:
   - Thêm `isPushLoading` và `pushError` vào `OwnerAppDetailUiState`.
   - Thêm khóa kiểm soát đồng thời `AtomicBoolean(false)` chống double-tap tại tầng ViewModel.
   - Thêm hàm `pushApp(context, onSuccess)` kiểm tra số dư, lấy/tạo idempotent request ID, gọi repository và xử lý callback.
10. **`app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt`** *(Cập nhật)*:
    - Thêm tham số `helperText`, `enabled`, `isLoading` vào component `OwnerActionItem`.
    - Chèn nút "Đẩy app" nằm ngay phía trên "Tạm dừng nhận Tester" khi app chưa ở trạng thái hoàn tất (`!campaign.isFinalized`).
    - Thêm hộp thoại xác nhận `AlertDialog` hiển thị chi phí 100 Credit, thời hạn đến 00:00 ngày mai (giờ VN), số dư hiện tại và số dư dự kiến sau khi đẩy, cùng nút "Hủy" và "Xác nhận đẩy app".

### C. Client Unit Tests
1. **`app/src/test/java/com/ntt/crosstest/ui/viewmodels/DailyHomeOrderTest.kt`** *(Cập nhật)*:
   - Bổ sung 5 ca kiểm thử bao phủ toàn bộ logic sắp xếp ưu tiên đẩy app:
     - App được đẩy vượt lên trên app thường có tip/boost lớn.
     - App được đẩy sau đứng trên app được đẩy trước (`push_order DESC`).
     - App đẩy ngày hôm trước tự động hết hạn vào ngày hôm sau và trở về thuật toán cũ.
     - App thường chưa đẩy vẫn giữ nguyên thứ tự sắp xếp gốc.
     - Tie-breaking ổn định theo `stableId`.
2. **`app/src/test/java/com/ntt/crosstest/data/CampaignPushUnitTest.kt`** *(Tạo mới)*:
   - Kiểm tra logic `Campaign.isPushActive` qua các mốc thời gian (hôm nay, hôm qua, ngày mai).
   - Kiểm tra serialization / deserialization JSON của `PushCampaignResult` cho cả trường hợp mua mới và idempotent replay.
   - Kiểm tra bảo vệ concurrency bằng `AtomicBoolean` CAS lock ngăn chặn double-tap song song.

---

## 3. KẾT QUẢ KIỂM THỬ VÀ BUILD

### 3.1. Kiểm thử Backend SQL (Supabase Migration 052)
- Runner: `node supabase/tests/run-campaign-push-test.mjs` (PGlite WASM engine).
- **Kết quả: 19 / 19 ca kiểm thử PASS 100%:**
  1. `PASS 1`: Nạp thành công full schema và migration 045 -> 052 (xác minh idempotent rerun).
  2. `PASS 2`: Số dư 99 Credit bị từ chối `INSUFFICIENT_CREDITS`, không trừ tiền, không ghi receipt.
  3. `PASS 3`: Đúng 100 Credit thực hiện thành công, số dư về 0, log -100, metadata campaign được cập nhật.
  4. `PASS 4`: Idempotency replay trả lại đúng receipt, `replayed = true`, không trừ tiền lần 2, không đổi `push_order`.
  5. `PASS 5`: Dùng lại cùng `client_request_id` cho campaign khác bị chặn với lỗi `REQUEST_ID_CONFLICT`.
  6. `PASS 6`: User có 150 Credit mua thành công, số dư còn 50, `push_order` tăng đơn điệu.
  7. `PASS 7`: Người không phải chủ app gọi `push_campaign` bị từ chối với lỗi `NOT_OWNER`.
  8. `PASS 8`: App đang tạm dừng (`status = 'paused'`) bị từ chối với lỗi `CAMPAIGN_NOT_OPEN`.
  9. `PASS 9`: App quá 30 ngày hiển thị bị từ chối với lỗi `CAMPAIGN_WINDOW_EXPIRED`.
  10. `PASS 10`: App trên Slot 2 đã hết hạn bị từ chối với lỗi `SLOT_EXPIRED`.
  11. `PASS 11`: Tài khoản bị khóa (`is_locked = true`) bị từ chối với lỗi `ACCOUNT_LOCKED`.
  12. `PASS 12`: Direct UPDATE và INSERT metadata đẩy app bị trigger `trg_protect_campaign_push_metadata` chặn an toàn.
  13. `PASS 13`: Cập nhật thông tin thông thường (không đổi `push_*`) vẫn diễn ra bình thường.
  14. `PASS 14`: Thao tác INSERT trực tiếp vào `campaign_push_receipts` bị RLS/REVOKE từ chối hoàn toàn.
  15. `PASS 15`: Thứ tự lượt đẩy tuân thủ nghiêm ngặt theo thời gian: Lượt đẩy sau luôn đứng trên lượt trước ($A_1 < A_2 < B_3 < A_4$).
  16. `PASS 16`: `_campaign_json` xuất đầy đủ `push_business_date`, `push_expires_at`, `push_order`.
  17. `PASS 17`: Xóa campaign bảo toàn receipt tài chính với `campaign_id = NULL` (không bị xóa mất lịch sử giao dịch).
  18. `PASS 18`: Ràng buộc `chk_campaign_push_metadata_consistency` bảo vệ tính toàn vẹn 3 cột metadata.
  19. `PASS 19`: Giao dịch rollback toàn bộ khi có lỗi phát sinh (tiền giữ nguyên, không lưu log, không ghi receipt).

### 3.2. Kiểm thử Unit Test Client Android
- Lệnh chạy: `./gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.data.CampaignPushUnitTest" --tests "com.ntt.crosstest.ui.viewmodels.DailyHomeOrderTest"`
- **Kết quả: PASS 100% (BUILD SUCCESSFUL).**

### 3.3. Build Debug APK
- Lệnh chạy: `./gradlew.bat :app:assembleDebug`
- **Kết quả: BUILD SUCCESSFUL in 12s**, sinh thành công file APK debug. Không có lỗi biên dịch tài nguyên hay mã nguồn.

---

## 4. MIGRATION CẦN ÁP DỤNG & THỨ TỰ TRIỂN KHAI

### 4.1. Migration cần áp dụng
- File migration: `supabase/migrations/052_campaign_push_priority.sql`.
- Ghi chú: Số hiệu migration mới nhất trước đó là `051_app_update_reward_hardening.sql`. File `052_campaign_push_priority.sql` hoàn toàn tương thích và không trùng số hiệu với các migration đang có trong kho lưu trữ.

### 4.2. Thứ tự triển khai khuyến nghị
1. **Bước 1 (Staging Database):**
   - Áp dụng migration `052_campaign_push_priority.sql` trên Supabase Staging.
   - Kiểm tra `campaign_push_order_seq`, bảng `campaign_push_receipts`, trigger và RPC `push_campaign`.
2. **Bước 2 (Kiểm chứng Concurrency thực tế trên PostgreSQL đa kết nối):**
   - Chạy script kiểm thử bắn 2 kết nối song song cùng 1 `client_request_id` để xác nhận advisory lock và sequence hoạt động không deadlock và chỉ trừ tiền đúng 1 lần.
3. **Bước 3 (Kiểm thử chức năng Client trên thiết bị thật/giả lập):**
   - Cài đặt bản debug APK lên thiết bị.
   - Thử nghiệm tài khoản chủ app có 99 Credit (phải báo không đủ tiền).
   - Thử nghiệm tài khoản có 100 Credit (đẩy thành công, số dư về 0).
   - Kiểm tra Trang chủ: App vừa đẩy xuất hiện ở vị trí đầu tiên.
   - Đổi giờ hệ thống hoặc đợi qua 00:00: App tự động rời nhóm ưu tiên về lại vị trí theo thuật toán thông thường.
4. **Bước 4 (Production Database):**
   - Áp dụng `052_campaign_push_priority.sql` trên Production.
5. **Bước 5 (Release Client):**
   - Phát hành bản cập nhật ứng dụng Android.

---

## 5. CÁC ĐIỂM CHƯA KIỂM CHỨNG TRÊN MÔI TRƯỜNG THỰC TẾ & KHUYẾN NGHỊ

1. **Kiểm thử Concurrency đa kết nối mạng TCP thật:**
   - *Hiện trạng:* Bộ test `run-campaign-push-test.mjs` chạy trên nền tảng PGlite WASM in-memory trong Node.js (đơn tiến trình). Mặc dù cơ chế bảo vệ backend đã sử dụng `pg_advisory_xact_lock` và sequence nguyên tử của PostgreSQL, việc kiểm thử nhiều socket TCP đồng thời ở cấp độ mạng vật lý chưa được thực hiện trên Supabase live.
   - *Khuyến nghị:* Chạy thử nghiệm đồng thời với công cụ wrk / k6 hoặc kịch bản Node đa kết nối trên Supabase Staging trước khi mở tính năng rộng rãi.
2. **Tương thích khi kết hợp với tính năng Cộng dồn nhiệm vụ (Migration 053):**
   - *Hiện trạng:* File `supabase/migrations/053_daily_task_carryover.sql` và kế hoạch cộng dồn nhiệm vụ đang được phát triển song song ở các file chưa commit. Như đã phân tích trong tài liệu `REVIEW_KET_HOP_CONG_DON_VA_DAY_APP_2026-10-29.md`, nếu người dùng bị phạt vào thời điểm 00:00 và đồng thời mua đẩy app, thứ tự giao dịch sẽ quyết định số dư Credit còn lại.
   - *Khuyến nghị:* Khi ghép migration 053 vào hệ thống, đảm bảo thứ tự khóa `profiles -> campaigns` được tuân thủ thống nhất như đã thiết kế trong migration 052.
3. **Tình huống App bị tạm dừng tự động sau khi đã mua Đẩy app:**
   - *Quy tắc áp dụng:* Nếu app bị chuyển sang `paused` (do người dùng chủ động hoặc do hệ thống auto-pause vì nợ nhiệm vụ 7 ngày), app sẽ không hiển thị trên danh sách nhận tester. Khi mở lại (`resume`) trong cùng ngày, thứ tự ưu tiên đẩy app cũ vẫn được giữ nguyên cho đến 00:00 cùng ngày mà không tốn thêm Credit.
