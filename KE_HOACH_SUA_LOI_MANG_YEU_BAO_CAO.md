# KẾ HOẠCH & BÁO CÁO PHÂN TÍCH: SỬA LỖI HIỂN THỊ "MẠNG YẾU, ĐANG THỬ LẠI LẦN..." KHI NỘP BÁO CÁO

> **Ngày lập:** 26/10/2026  
> **Trạng thái:** Đã tìm ra nguyên nhân gốc rễ (Root Cause Identified) - Lập kế hoạch, **chưa sửa code**.  
> **Mục tiêu:** Khắc phục triệt để hiện tượng mỗi khi người test gửi báo cáo (feedback + screenshot), màn hình gợi ý app tiếp theo luôn hiện thông báo: *"Mạng yếu, đang thử lại lần 2…"* dù sau đó báo cáo vẫn nộp thành công và cộng điểm bình thường.

---

## 1. MÔ TẢ HIỆN TƯỢNG (SYMPTOMS)

1. Người dùng kiểm thử bấm **"Nộp báo cáo"** (Submit feedback kèm ảnh chụp màn hình).
2. Hộp thoại hoặc màn hình gợi ý ứng dụng tiếp theo (`NextAppPrompt` trong `CampaignDetailScreen`) xuất hiện.
3. Thay vì hoàn tất nhanh chóng trong 1-3 giây, thanh trạng thái hoặc thông báo lập tức chuyển sang:
   > **"Mạng yếu, đang thử lại lần 2…"** (kèm vòng tròn xoay - progress spinner).
4. Khoảng 10 - 15 giây sau, trạng thái bất ngờ chuyển thành **"Nộp báo cáo thành công! +10 điểm"** và mở khóa ứng dụng tiếp theo bình thường.
5. Kiểm tra thực tế trên Database Supabase (`tester_campaign_reports`, `tester_campaign_checkins`) và Firebase Storage (`cross-testers.firebasestorage.app/reports/...`): Toàn bộ dữ liệu bài nộp và ảnh chụp màn hình **đều được tạo đầy đủ, không hề bị mất dữ liệu**.

---

## 2. NGUYÊN NHÂN GỐC RỄ (ROOT CAUSE ANALYSIS)

Sau khi đối chiếu mã nguồn giữa `CampaignDetailScreen.kt`, `ReportSubmitManager.kt`, `ReportUploadWorker.kt`, `FirebaseStorageHelper.kt` và `CampaignDetailViewModel.kt`, nguyên nhân được chia thành **3 tầng**:

### 🔴 Nguyên nhân 1 (Trọng tâm nhất): Lỗi xử lý bất đồng bộ trong `FirebaseStorageHelper.uploadReportScreenshot`
- **Vị trí code:** `app/src/main/java/com/ntt/crosstest/util/FirebaseStorageHelper.kt` (dòng 136 - 150):
  ```kotlin
  // Đoạn code hiện tại:
  try {
      storageRef.putFile(Uri.fromFile(prepared.file), metadata).await()
  } catch (e: CancellationException) {
      throw e
  } catch (e: Exception) {
      val existingUrl = runCatching { storageRef.downloadUrl.await().toString() }.getOrNull()
      if (existingUrl != null) return@withContext existingUrl
      throw e
  }

  // LỖI NẰM TẠI ĐÂY:
  storageRef.downloadUrl.await().toString()
  ```
- **Cơ chế phát sinh lỗi:**
  1. `storageRef.putFile(...).await()` tải các byte của ảnh lên Google Cloud Storage thành công.
  2. Tuy nhiên, metadata của file trên hạ tầng Firebase Storage / GCS đôi khi có **độ trễ lan truyền (propagation delay)** từ vài chục đến vài trăm mili-giây.
  3. Lời gọi `storageRef.downloadUrl.await()` được đặt **BÊN NGOÀI** khối `try-catch`! Khi Firebase ném ra ngoại lệ (ví dụ: `StorageException` do object chưa kịp index metadata, hoặc timeout tạm thời khi lấy token download), ngoại lệ này **thoát ra ngoài hàm mà không được bắt**.
  4. Trong `ReportUploadWorker.kt`:
     ```kotlin
     } catch (e: Exception) {
         if (ReportSubmitPolicy.isRetryable(e)) {
             return Result.retry() // <--- WORKER BỊ BUỘC PHẢI RETRY
         }
         return Result.failure(...)
     }
     ```
     Vì ngoại lệ Firebase Storage không thuộc danh sách đen vĩnh viễn (như 400, 401, 403 của Supabase), `ReportSubmitPolicy.isRetryable(e)` trả về `true`.
  5. WorkManager thiết lập thời gian backoff tối thiểu là **10 giây**:
     ```kotlin
     .setBackoffCriteria(
         BackoffPolicy.EXPONENTIAL,
         WorkRequest.MIN_BACKOFF_MILLIS.coerceAtLeast(10_000L),
         TimeUnit.MILLISECONDS
     )
     ```
     WorkManager đưa tác vụ vào hàng đợi chờ 10 giây sau mới chạy lại. Trong suốt 10 giây này, `runAttemptCount = 1`, `attempt = 2`.
  6. `ReportSubmitManager.kt` (dòng 291):
     ```kotlin
     val stage = when {
         attempt > 1 -> Stage.RETRYING // <--- GÁN STAGE RETRYING
         ...
     }
     ```
     Giao diện `CampaignDetailScreen.kt` lập tức hiển thị `R.string.campaign_submit_retrying_format`:
     `"Mạng yếu, đang thử lại lần 2…"` cho người dùng nhìn thấy!
  7. **Tại sao lần 2 lại thành công?**
     Sau 10 giây, Worker chạy lại lần 2. Do ảnh đã được upload ở lần 1, luật bảo mật Firebase (`storage.rules`) không cho phép ghi đè (`allow create;` nhưng không có `allow update;`). Lệnh `putFile` ở lần 2 sẽ văng vào khối `catch (e: Exception)`.
     Bên trong `catch`, hàm chạy dòng:
     ```kotlin
     val existingUrl = runCatching { storageRef.downloadUrl.await().toString() }.getOrNull()
     if (existingUrl != null) return@withContext existingUrl
     ```
     Vì đã qua 10 giây, file đã hoàn tất 100% trên Firebase, `downloadUrl.await()` chạy thành công và trả về URL ảnh! Worker tiếp tục gửi thông tin lên Supabase `submit_tester_report` và hoàn tất xuất sắc.
     $\rightarrow$ **Kết quả:** Lần nào cũng nộp thành công, nhưng lần nào người dùng cũng bị ép nhìn thấy thông báo *"Mạng yếu, đang thử lại lần 2..."* trong đúng 10 giây.

---

### 🟠 Nguyên nhân 2: Tranh chấp băng thông mạng khi nhấn Submit (`prefetchNextCampaign`)
- **Vị trí code:** `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt` (dòng 784 - 792):
  ```kotlin
  fun startSubmit(campaignId: String, localScreenshotPath: String, feedback: String) {
      ...
      reportSubmitManager.submit(campaignId, localScreenshotPath, feedback)
      prefetchNextCampaign(campaignId) // <--- CHẠY ĐỒNG THỜI NGAY LẬP TỨC
  }
  ```
- **Cơ chế phát sinh:**
  - Ngay thời điểm thiết bị đang cần dồn toàn bộ băng thông và kết nối HTTP/TLS để upload file ảnh JPEG (1 - 2 MB) lên Firebase Storage, hàm `prefetchNextCampaign()` lại bắn ra liên tiếp 5-7 truy vấn mạng song song:
    1. Đếm số checkin (`tester_campaign_checkins`).
    2. Đếm số báo cáo (`tester_campaign_reports`).
    3. Gọi RPC `get_home_feed` (RPC này hiện còn đang lỗi 404 trong migration cũ nên phải bắt fallback).
    4. Query danh sách `campaigns`.
    5. Query số lượng testers của các campaign khác.
  - Trên kết nối mạng 3G/4G di động hoặc wifi có ping cao, việc dồn 6-8 request đồng thời gây ra tình trạng **Socket Contention (nghẽn hàng đợi mạng)**, làm request upload ảnh hoặc lấy token Firebase dễ bị timeout ở những giây đầu tiên.

---

### 🟡 Nguyên nhân 3: Thông điệp UI gắn nhãn quá sớm và cứng nhắc
- **Vị trí code:** `ReportSubmitManager.kt` dòng 291 & `CampaignDetailScreen.kt` dòng 315.
- Cứ mỗi khi `attempt > 1` (kể cả lần thử thứ 2 chỉ do Worker đổi thread hoặc delay Firebase Storage vài mili-giây), hệ thống đã lập tức quy chụp là:
  `"Mạng yếu, đang thử lại lần %1$d…"`
- Trong thực tế, mạng của người dùng có thể đang rất mạnh (Wifi cáp quang 100Mbps), việc app báo *"Mạng yếu"* gây cảm giác app hoạt động không ổn định hoặc lỗi thời.

---

## 3. KẾ HOẠCH SỬA CHỮA CHI TIẾT (ACTION PLAN)

Khi bắt đầu tiến hành sửa code (ở phiên làm việc tiếp theo), hãy thực hiện lần lượt theo 4 bước sau:

### BƯỚC 1: Cải tiến `FirebaseStorageHelper.kt` (Xử lý dứt điểm lỗi `downloadUrl`)
1. **Bọc việc lấy `downloadUrl` trong vòng lặp thử lại cục bộ (Local Retry Loop):**
   - Không được để `storageRef.downloadUrl.await()` nằm trần ngoài khối try-catch.
   - Thêm hàm retry nội bộ với 3 lần thử, mỗi lần cách nhau 400ms - 600ms (dùng `delay` của coroutines).
   - Nếu `putFile` xong, gọi ngay retry loop này để lấy URL. Nếu lấy được URL ngay tại lần chạy đầu tiên, hàm sẽ hoàn tất trong chưa đầy 1 giây mà **không bao giờ để Worker phải văng ngoại lệ hay rơi vào `Result.retry()`**.
2. **Đoạn code dự kiến sửa tại `FirebaseStorageHelper.kt`:**
   ```kotlin
   // Đặt hàm helper nội bộ:
   private suspend fun StorageReference.awaitDownloadUrlWithRetry(maxAttempts: Int = 3, delayMs: Long = 500): String {
       var lastException: Exception? = null
       repeat(maxAttempts) { attempt ->
           try {
               return this.downloadUrl.await().toString()
           } catch (e: CancellationException) {
               throw e
           } catch (e: Exception) {
               lastException = e
               if (attempt < maxAttempts - 1) kotlinx.coroutines.delay(delayMs)
           }
       }
       throw lastException ?: IllegalStateException("Failed to get download url")
   }
   ```
   Và trong `uploadReportScreenshot`:
   ```kotlin
   try {
       storageRef.putFile(Uri.fromFile(prepared.file), metadata).await()
       return@withContext storageRef.awaitDownloadUrlWithRetry()
   } catch (e: CancellationException) {
       throw e
   } catch (e: Exception) {
       val existingUrl = runCatching { storageRef.awaitDownloadUrlWithRetry(maxAttempts = 2, delayMs = 300) }.getOrNull()
       if (existingUrl != null) return@withContext existingUrl
       throw e
   }
   ```

---

### BƯỚC 2: Tách biệt và hoãn `prefetchNextCampaign` trong `CampaignDetailViewModel.kt`
1. Không kích hoạt `prefetchNextCampaign(campaignId)` ngay cùng một tích tắc với `reportSubmitManager.submit(...)`.
2. **Chiến lược:**
   - Lắng nghe khi `reportSubmitManager` đạt trạng thái `UPLOADING` hoặc sau một khoảng trễ nhỏ (ví dụ `delay(1500)`), HOẶC chỉ prefetch khi `submitStage` đã hoàn thành upload ảnh và đang trong giai đoạn `SUBMITTING_REPORT` / `SUCCESS`.
   - Giúp dành toàn bộ đường truyền băng thông cho Worker upload file ảnh nhanh nhất có thể.

---

### BƯỚC 3: Tối ưu thời gian Backoff của WorkManager trong `ReportSubmitManager.kt`
1. Thay vì để thời gian backoff tối thiểu mặc định là `10_000L` (10 giây), nếu có thể hãy cho phép thử lại nhanh hơn đối với lần đầu tiên (hoặc xử lý trực tiếp bên trong Worker thay vì để WorkManager reschedule ra ngoài).
2. Khi Worker tự giải quyết được các lỗi mạng tạm thời trong vòng lặp nội bộ (exponential backoff 1s, 2s) trước khi trả về `Result.retry()`, WorkManager sẽ hầu như không bao giờ cần phải kích hoạt retry bên ngoài.

---

### BƯỚC 4: Tinh chỉnh thông điệp UI (`strings.xml` & `CampaignDetailScreen.kt`)
1. Điều chỉnh chuỗi thông báo và trạng thái:
   - Ở lần thử 2: Thay vì hiển thị *"Mạng yếu, đang thử lại lần 2…"*, hãy hiển thị thông điệp trung tính, thân thiện hơn:
     - `values-vi/strings.xml`:
       ```xml
       <string name="campaign_submit_retrying_format">Đang hoàn tất báo cáo (lần %1$d)…</string>
       <string name="campaign_submit_weak_network_format">Mạng không ổn định, đang thử lại lần %1$d…</string>
       ```
   - Chỉ khi `attempt >= 3` hoặc phát hiện lỗi mất kết nối Internet thực sự (`isNetworkError`), UI mới hiển thị *"Mạng không ổn định..."*.

---

## 4. DANH SÁCH TỆP SẼ ĐƯỢC CHỈNH SỬA KHI THI CÔNG

| Tệp | Vị trí / Mục đích |
|---|---|
| `app/src/main/java/com/ntt/crosstest/util/FirebaseStorageHelper.kt` | Thêm retry loop cho `downloadUrl.await()` và bọc an toàn trong try-catch |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt` | Hoãn `prefetchNextCampaign()` để tránh nghẽn băng thông lúc upload ảnh |
| `app/src/main/java/com/ntt/crosstest/data/ReportSubmitManager.kt` | Kiểm soát `Stage.RETRYING` và thời gian thử lại |
| `app/src/main/res/values-vi/strings.xml` (và `values-en`, `values`) | Tinh chỉnh thông điệp UI thân thiện, tránh gây hiểu nhầm cho người dùng |

---

## 5. KẾT LUẬN & BƯỚC TIẾP THEO

- Toàn bộ nguyên nhân gây ra việc báo *"Mạng yếu..."* đã được cô lập chính xác (chủ yếu do dòng `storageRef.downloadUrl.await()` văng exception trước khi metadata lan truyền xong, khiến Worker phải retry sau 10 giây).
- File tài liệu này đã được lưu tại thư mục gốc của dự án:  
  `KE_HOACH_SUA_LOI_MANG_YEU_BAO_CAO.md`
- **Mã nguồn ứng dụng hiện tại vẫn được giữ nguyên vẹn 100% theo đúng chỉ thị của bạn.**
- Bất cứ khi nào bạn sẵn sàng sửa code, chỉ cần yêu cầu: *"Hãy thực hiện theo kế hoạch trong file KE_HOACH_SUA_LOI_MANG_YEU_BAO_CAO.md"*, tôi sẽ áp dụng các thay đổi một cách chuẩn xác và an toàn nhất.
