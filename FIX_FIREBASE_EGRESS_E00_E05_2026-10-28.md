# BÁO CÁO TRIỂN KHAI & KHẮC PHỤC TOÀN DIỆN FIREBASE EGRESS (F01–F15)
**Dự án:** 12Tester (TestCheo)  
**Thời gian hoàn thành:** 2026-10-28  
**Tài liệu tham chiếu:**
- `REVIEW_FIREBASE_EGRESS_E00_E05_2026-10-28.md`
- `KE_HOACH_TOI_UU_FIREBASE_EGRESS_2026-10-27.md`
- `BAO_CAO_TOI_UU_FIREBASE_EGRESS_2026-10-28.md`
- Toàn bộ audit probes trong `audit/firebase-egress-2026-10-28/`

---

## 1. BẢNG TỔNG HỢP TRẠNG THÁI 15 NHÓM VẤN ĐỀ (F01–F15)

| Mã | Tên vấn đề | Trạng thái | Tập tin & Vị trí dòng | Bằng chứng kiểm thử trước/sau |
|---|---|---|---|---|
| **F01** | Migration 045 ghi đè `submit_tester_report` làm rơi bảo vệ nghiệp vụ | **FIXED** | `supabase/migrations/045_thumbnail_support.sql` (L1-165) | `db-audit.mjs`: Check-in creditDelta=0, feedbackRows=1, rating 5 immutable; `run-thumbnail-migration.mjs`: 6/6 PASS |
| **F02** | Migration 045 nới lỏng URL validator và bỏ kiểm tra ảnh trùng | **FIXED** | `supabase/migrations/045_thumbnail_support.sql` (L12-40, L125-145) | `db-audit.mjs`: `SAME_SCREENSHOT_REJECTED`, `INVALID_SCREENSHOT_URL`, `INVALID_THUMBNAIL_URL` hoạt động chính xác |
| **F03** | E04 Media Gateway ký giả lập `token_mock` | **FIXED** | `supabase/migrations/046_media_access_gateway.sql` (L1-140) | `run-media-gateway-test.mjs`: HMAC-SHA256 thật với `hmac(...)`, author/owner/admin PASS, stranger FORBIDDEN, 31 ngày MEDIA_EXPIRED (6/6 PASS) |
| **F04** | Pipeline Thumbnail E02 làm mất file thumbnail khi pending | **FIXED** | `PendingReportStore.kt` (L30-58), `ReportSubmitManager.kt` (L85-115) | `EgressAuditProbeTest.kt#probe01`: File thumbnail trong cache chuẩn bị được copy sang storage bền vững của pending report (PASS) |
| **F05** | F05: Cập nhật ảnh full không đồng bộ dọn thumbnail cũ trong DB | **FIXED** | `supabase/migrations/045_thumbnail_support.sql` (L130-145) | `db-audit.mjs`: `replacement_full_without_thumbnail` xác nhận `oldThumbnailRetained: false` (PASS) |
| **F06** | Fallback thumbnail 404 không hoạt động khi mở lại màn hình | **FIXED** | `ScreenshotCoilInterceptor.kt` (L58-85, L98-115), `ScreenshotManager.kt` (L245-255) | `EgressAuditProbeTest.kt#probe02` & `FirebaseEgressOptimizationTest#e02`: Thumbnail 404 fallback full thành công cả lần 1 và lần 2 (PASS) |
| **F07** | Race condition ghi đĩa sau khi đã xoá phiên đăng xuất | **FIXED** | `PrivateScreenshotDiskCache.kt` (L160-195), `ScreenshotManager.kt` (L360-420) | `EgressAuditProbeTest.kt#probe06`: Response đang đọc dở khi clear() bị hủy, không để lại file rác trên đĩa (PASS) |
| **F08** | Cache đĩa dùng chung tài khoản mặc định `default` | **FIXED** | `PrivateScreenshotDiskCache.kt` (L110-140), `ScreenshotManager.kt` (L83-85, L450-465) | Bác bỏ ghi/đọc khi `accountId == "default"` hoặc rỗng; đồng bộ `currentAccountId` theo Auth session và dọn sạch khi đăng xuất |
| **F09** | Cache đĩa biến `max-age=60s` thành 30 phút và thiếu tin cậy expiry | **FIXED** | `PrivateScreenshotDiskCache.kt` (L115-180, L200-245) | `EgressAuditProbeTest.kt#probe03`, `probe04`, `probe05`: Fail-closed nếu thiếu expiry; TTL tối đa 60s; bù trừ clock skew Date của server (PASS) |
| **F10** | Giải mã cache đĩa dùng RGB_565 làm lệch hash so sánh ảnh | **FIXED** | `PrivateScreenshotDiskCache.kt` (L235-245) | `EgressAuditProbeTest.kt#probe08`: Giải mã đĩa dùng `Bitmap.Config.ARGB_8888`, giữ đồng nhất 100% kết quả dHash so với ảnh gốc qua 25 mẫu thử |
| **F11** | Metadata `parent_time_created` của client có thể che giấu ảnh full cũ | **FIXED** | `backend/storage-cleanup/cleanup-reports-storage.mjs` (L145-195), `storage.rules` (L45-65) | `misc-audit.mjs`: `clientMetadataHidesOldFull` (`deleted: true`, SLA violations = 1), `lateThumbnailWithoutParentMetadata` (`fullDeleted: true`, `thumbnailDeleted: true`) |
| **F12** | Công cụ đối chiếu Telemetry E00 chấp nhận số liệu phi lý | **FIXED** | `tools/telemetry-reconciliation/reconcile-telemetry.mjs` (L60-110) | `misc-audit.mjs` & `reconcile-telemetry.test.mjs`: Báo `status = INVALID` và kích hoạt `isAlertTriggered = true` khi Cloud = 0 hoặc Client > Cloud |
| **F13** | Runbook E00 hướng dẫn sai lệnh và vội vã kết luận lạm dụng | **FIXED** | `RUNBOOK_BASELINE_PRODUCTION_7_14_DAYS.md` (L45-95) | Cập nhật đúng CLI `--client-file` và `--cloud-bytes`; phân tích đầy đủ các nguyên nhân kỹ thuật hợp lệ trước khi kết luận |
| **F14** | E05 App Check và CDN Icon dùng domain giả định và thiếu fallback | **FIXED** | `IconRoutingHelper.kt` (L10-45), `AppIcon.kt` (L40-65), `AppCheckObservationConfig.kt` (L1-85) | `cdnHost` mặc định rỗng `""`; cờ mặc định `false`; fallback về Origin Storage khi CDN lỗi; App Check ở chế độ `MONITOR_ONLY` an toàn |
| **F15** | Bảng tính TCO tính sai Cloud CDN và Always Free tier | **FIXED** | `tools/tco-calculator/calculate-tco.mjs` (L25-215) | `calculate-tco.test.mjs` & `misc-audit.mjs`: Trừ đúng 100GB Always Free; tính đúng chi phí CDN miss ($0.08+$0.01/GB, GCS $0.00); kết luận `NO_GO` cho icon nhỏ |

---

## 2. CHI TIẾT CÁC THAY ĐỔI & BẰNG CHỨNG KIỂM THỬ TỪNG MÃ (F01–F15)

### 2.1. F01 & F02: Khôi phục toàn vẹn nghiệp vụ Migration 045 (`submit_tester_report`)
- **Vấn đề trước sửa:** Migration 045 bản nháp cũ copy đè toàn bộ hàm RPC `submit_tester_report`, làm mất đoạn kiểm tra `v_already_checked_in`, xoá mất việc ghi nhận vào bảng `feedbacks`, cho phép cập nhật lại rating của báo cáo đã được duyệt (`approved`), bỏ đoạn kiểm tra `SAME_SCREENSHOT_REJECTED` (Hamming distance <= 5), và cho phép URL ngoài domain Firebase Storage.
- **Giải pháp triển khai:**
  - Viết lại [045_thumbnail_support.sql](file:///F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql) bảo toàn đầy đủ các logic nghiệp vụ từ migration gốc 030/035:
    1. Kiểm tra check-in hàng ngày: Nếu đã check-in hôm nay thì không cộng thêm credit (`creditDelta = 0`).
    2. Ghi nhận `feedbacks` khi nộp báo cáo.
    3. Đảm bảo rating của báo cáo đã duyệt là bất biến (`rating` giữ nguyên 5 sao).
    4. Khôi phục kiểm tra trùng ảnh: Bác bỏ nộp báo cáo với lỗi `SAME_SCREENSHOT_REJECTED` nếu URL trùng với ngày hôm trước hoặc khoảng cách dHash Hamming $\le 5$.
    5. Khôi phục hàm xác thực định dạng URL ảnh gốc `is_valid_report_screenshot_url` và bổ sung hàm `is_valid_report_thumbnail_url`.
- **Kết quả kiểm thử:**
  - `node audit/firebase-egress-2026-10-28/db-audit.mjs`: 13 probes đều đạt chuẩn:
    ```json
    {"phase": "after_045_046", "name": "external_screenshot_url", "error": "INVALID_SCREENSHOT_URL"},
    {"phase": "after_045_046", "name": "confirmed_checkin_additional_reward", "creditDelta": 0},
    {"phase": "after_045_046", "name": "feedback_written_on_submission", "feedbackRows": 1},
    {"phase": "after_045_046", "name": "approved_rating_immutable", "rating": 5},
    {"phase": "after_045_046", "name": "rejected_same_hash_new_url", "error": "SAME_SCREENSHOT_REJECTED"}
    ```
  - `node supabase/tests/run-thumbnail-migration.mjs`: **6/6 PASS 100%**.

---

### 2.2. F03: Media Access Gateway RPC sử dụng HMAC-SHA256 thật (E04)
- **Vấn đề trước sửa:** Migration 046 trả về token giả lập chuỗi tĩnh `token_mock`, không xác thực chữ ký số HMAC, không kiểm tra quyền truy cập thực tế.
- **Giải pháp triển khai:**
  - Tạo [046_media_access_gateway.sql](file:///F:/AI/12Tester/supabase/migrations/046_media_access_gateway.sql) với hàm PostgreSQL `extensions.hmac(message, secret, 'sha256')`.
  - Cấp URL gateway có chữ ký và thời hạn 30 phút: `/v1/media-gateway?report_id=...&grant_token=...&expires_at=...`.
  - Hàm `verify_media_access_grant` giải mã và kiểm tra chữ ký HMAC hợp lệ.
  - Kiểm tra phân quyền: Tác giả báo cáo (`author`), chủ chiến dịch (`owner`), và quản trị viên (`admin`) được cấp quyền; người ngoài (`stranger`) bị từ chối với lỗi `FORBIDDEN`.
  - Báo cáo quá hạn 30 ngày bị từ chối với mã lỗi `MEDIA_EXPIRED`.
- **Kết quả kiểm thử:**
  - `node supabase/tests/run-media-gateway-test.mjs`: **6/6 PASS 100%**:
    - Unauthenticated: lỗi `NOT_AUTHENTICATED`.
    - Author: role `author`, grant hợp lệ.
    - Campaign Owner: role `owner`, grant hợp lệ.
    - Admin: role `admin`, grant hợp lệ.
    - Stranger: lỗi `FORBIDDEN`.
    - Quá hạn 31 ngày: lỗi `MEDIA_EXPIRED`.

---

### 2.3. F04: Bảo vệ tập tin Thumbnail trong hàng đợi Pending Report (E02)
- **Vấn đề trước sửa:** `ReportSubmitManager.kt` chỉ copy `fullFile` vào thư mục bền vững của `PendingReportStore`, bỏ quên `prepared.thumbnailFile`. Khi Worker kích hoạt, `request.thumbnailPath` bị null, dẫn đến việc không upload thumbnail lên Storage.
- **Giải pháp triển khai:**
  - Trong [PendingReportStore.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/PendingReportStore.kt): Thêm hàm `newThumbnailFile(context, id)` và bổ sung `report.thumbnailPath` vào chu trình xoá (`delete`) và dọn dẹp định kỳ (`cleanup`).
  - Trong [ReportSubmitManager.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/ReportSubmitManager.kt): Trong hàm `persist()`, copy `prepared.thumbnailFile` sang file pending bền vững và gán đường dẫn vào `pending.thumbnailPath`.
- **Kết quả kiểm thử:**
  - Chạy probe `probe01_preparedThumbnailMustSurviveDurablePendingPersistence`:
    ```
    assertNotNull("Worker only uploads request.thumbnailPath; persistence must populate it", pending.thumbnailPath)
    assertTrue(File(pending.thumbnailPath!!).isFile) -> PASS
    ```

---

### 2.4. F05: Tự động dọn Thumbnail cũ khi thay thế ảnh Full trong DB
- **Vấn đề trước sửa:** Nếu người dùng nộp đè ảnh full mới cho cùng một báo cáo nhưng không có thumbnail kèm theo, database giữ nguyên `thumbnail_url` cũ, dẫn đến việc thumbnail trỏ vào ảnh phiên bản cũ.
- **Giải pháp triển khai:**
  - Trong [045_thumbnail_support.sql](file:///F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql):
    ```sql
    thumbnail_url = p_thumbnail_url, -- Luôn đồng bộ theo giá trị mới (nếu nộp full mới không thumb thì reset về NULL)
    ```
- **Kết quả kiểm thử:**
  - `node audit/firebase-egress-2026-10-28/db-audit.mjs`:
    ```json
    {"phase": "after_045_046", "name": "replacement_full_without_thumbnail", "fullReplaced": true, "oldThumbnailRetained": false}
    ```

---

### 2.5. F06: Tự động Fallback Full khi Thumbnail bị 404 trên mọi lượt mở màn hình
- **Vấn đề trước sửa:** Khi thumbnail bị 404, `ScreenshotCoilInterceptor` đánh dấu tombstone URL thumbnail. Ở lần mở màn hình tiếp theo, do URL thumbnail đã bị tombstone nên request trả về `ErrorResult` ngay lập tức mà không chuyển hướng sang ảnh full.
- **Giải pháp triển khai:**
  - Trong [ScreenshotCoilInterceptor.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotCoilInterceptor.kt):
    - Khi URL thumbnail bị tombstone 404: Kiểm tra RAM cache của `descriptor.fallbackUrl` (ảnh full). Nếu có, trả về ngay từ RAM.
    - Nếu không có trong RAM, tự động chuyển hướng request tải `descriptor.fallbackUrl`.
  - Trong [ScreenshotManager.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt):
    - Đặt kiểm tra `if (is404Tombstoned(url)) return null` ngay đầu `getOrFetchBitmap` để chặn triệt để vòng lặp retry đối với URL thật sự không tồn tại.
- **Kết quả kiểm thử:**
  - `probe02_thumbnail404FallbackMustWorkOnSecondDisplay`: **PASS**.
  - `FirebaseEgressOptimizationTest#e02`: **PASS**.

---

### 2.6. F07, F08, F09, F10: Sửa toàn diện bộ nhớ đệm đĩa riêng tư (E03 Private Disk Cache)
- **Vấn đề trước sửa:**
  - F07: Race condition ghi file đĩa sau khi đã xoá phiên hoặc đăng xuất.
  - F08: Dùng chung tài khoản `default`, vi phạm bảo mật đa tài khoản trên cùng thiết bị.
  - F09: Tự ý chuyển đổi `max-age=60s` thành 30 phút; không fail-closed khi thiếu metadata hạn dùng (`createdAt`, `mediaExpired`); lỗi clock skew khi đồng hồ server lệch.
  - F10: Decode đĩa bằng `RGB_565` làm thay đổi bảng màu, khiến hàm dHash tính ra chuỗi khác biệt so với ảnh gốc giải mã trên RAM/Network (`ARGB_8888`).
- **Giải pháp triển khai:**
  - Trong [PrivateScreenshotDiskCache.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/PrivateScreenshotDiskCache.kt):
    - **F08:** Từ chối `put` và `get` nếu `accountId` rỗng hoặc là `"default"`.
    - **F09:** Yêu cầu bắt buộc phải có `reportCreatedAt` hợp lệ và `mediaExpired != null`. Nếu thiếu, trả về `false` (fail-closed).
    - **F09:** Parse chính xác directive `max-age=<giây>`, tuân thủ hạn mức server (ví dụ 60s); từ chối tuyệt đối nếu có `no-store` hoặc `no-cache`.
    - **F09:** Bù trừ clock skew bằng header `Date` của server HTTP response.
    - **F10:** Giải mã với `Bitmap.Config.ARGB_8888` để giữ nguyên 100% cơ sở tính dHash.
    - **F07:** Thêm hàm `deleteSpecificFile` để dọn file ngay lập tức nếu thế hệ phiên (`generation`) bị thay đổi trong lúc ghi file.
  - Trong [ScreenshotManager.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt):
    - Đồng bộ `currentAccountId` với `AuthRepository` khi đăng nhập và đăng xuất.
    - Kiểm tra `sessionGeneration` nguyên tử trước và sau khi đọc/ghi bộ nhớ đệm đĩa.
- **Kết quả kiểm thử:**
  - `probe03_diskCacheMustRequireTrustedExpiry`: **PASS**.
  - `probe04_diskCacheMustRespectServerMaxAge`: **PASS** (`ttl <= 62_000ms`).
  - `probe05_serverClockBehindMustNotImmediatelyInvalidateValidCache`: **PASS**.
  - `probe06_clearDuringBodyReadMustNotReviveDiskFiles`: **PASS** (0 file rác trên đĩa).
  - `probe08_diskDecodeMustPreserveDHashBasis`: **PASS** (100% khớp dHash qua 25 vòng lặp).

---

### 2.7. F11: Chặn giả mạo ngày tạo trong Storage Rules và Cleanup Worker (E02 Retention)
- **Vấn đề trước sửa:** Worker dọn dẹp ưu tiên đọc custom metadata `parent_time_created` của client gửi lên trước cả `metadata.timeCreated` của server GCS, cho phép client ghi ngày trong tương lai để che giấu ảnh full cũ không bị xoá theo hạn mức 30 ngày.
- **Giải pháp triển khai:**
  - Trong [storage.rules](file:///F:/AI/12Tester/storage.rules): Cấm client gửi metadata `parent_time_created` lên các ảnh full screenshot. Chỉ cho phép gửi trên file thumbnail `thumb_*`.
  - Trong [cleanup-reports-storage.mjs](file:///F:/AI/12Tester/backend/storage-cleanup/cleanup-reports-storage.mjs):
    - Ảnh full screenshot: **Chỉ sử dụng duy nhất** `metadata.timeCreated` do Google Cloud Storage cấp phát trên server.
    - File thumbnail: Tra cứu thời gian của ảnh cha trong danh sách quét; nếu đọc từ metadata `parent_time_created` thì phải bị chặn trên bởi chính `metadata.timeCreated` của thumbnail.
- **Kết quả kiểm thử:**
  - `misc-audit.mjs`:
    ```json
    "clientMetadataHidesOldFull": { "deleted": true, "slaViolations": 1, "candidates": 1 },
    "lateThumbnailWithoutParentMetadata": { "fullDeleted": true, "thumbnailDeleted": true }
    ```
  - `node backend/storage-cleanup/cleanup-reports-storage.test.mjs`: **8/8 PASS 100%**.

---

### 2.8. F12 & F13: Chuẩn hoá công cụ đối chiếu Telemetry & Runbook E00
- **Vấn đề trước sửa:**
  - F12: Công cụ đối chiếu trả về `status = "OK"` ngay cả khi Cloud Storage = 0 byte hoặc số liệu client lớn hơn số liệu cloud, đồng thời thiếu CLI nhận tham số thực tế.
  - F13: Runbook hướng dẫn chạy lệnh `node tools/telemetry-reconciliation/reconcile-telemetry.mjs --period 14d` (tham số không có thật) và vội vã quy kết độ lệch là do lạm dụng.
- **Giải pháp triển khai:**
  - Trong [reconcile-telemetry.mjs](file:///F:/AI/12Tester/tools/telemetry-reconciliation/reconcile-telemetry.mjs):
    - Bắt buộc kiểm tra logic: Nếu `cloudSentBytes == 0 && clientBodyBytes > 0` hoặc `clientBodyBytes > cloudSentBytes` thì lập tức trả về `status = "INVALID"` và bật cờ `isAlertTriggered = true`.
    - Hỗ trợ CLI chuẩn: `node reconcile-telemetry.mjs --client-file <path> --cloud-bytes <bytes> [--cloud-requests <count>]`.
  - Trong [RUNBOOK_BASELINE_PRODUCTION_7_14_DAYS.md](file:///F:/AI/12Tester/RUNBOOK_BASELINE_PRODUCTION_7_14_DAYS.md):
    - Cập nhật cú pháp CLI chính xác.
    - Bổ sung bảng hướng dẫn phân tích độ lệch: Giải thích rõ ràng nguyên nhân chênh lệch từ GCS network/headers, client phiên bản cũ, worker dọn dẹp, và tải trực tiếp trước khi đưa ra nhận định lạm dụng.
- **Kết quả kiểm thử:**
  - `misc-audit.mjs` & `reconcile-telemetry.test.mjs`: **PASS 100%**.

---

### 2.9. F14: An toàn hoá CDN Icon Routing và App Check Observation (E04 & E05)
- **Vấn đề trước sửa:** Cấu hình domain CDN giả định `https://cdn.crosstest.example.com` bị đưa vào production code; không có cơ chế fallback về Origin Storage khi CDN gặp sự cố; App Check can thiệp chặn request khi chưa kiểm chứng thực tế.
- **Giải pháp triển khai:**
  - Trong [IconRoutingHelper.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/IconRoutingHelper.kt):
    - Đặt `cdnHost` mặc định là rỗng `""`.
    - Đặt `isCdnRoutingEnabled` mặc định là `false`.
    - Chỉ kích hoạt định tuyến nếu cả hai điều kiện được thoả mãn: cờ bật và domain hợp lệ.
    - Cung cấp hàm `shouldFallbackToOrigin(url, throwable)`.
  - Trong [AppIcon.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AppIcon.kt): Bọc trạng thái hình ảnh, tự động fallback về URL Origin Storage khi có lỗi mạng từ CDN.
  - Trong [AppCheckObservationConfig.kt](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/AppCheckObservationConfig.kt): Triển khai chế độ `MONITOR_ONLY` an toàn; đếm số lượng token hợp lệ/không hợp lệ và không chặn bất kỳ request nào của người dùng.
- **Kết quả kiểm thử:**
  - Kiểm tra compile & unit tests đạt 100%.

---

### 2.10. F15: Hiệu chỉnh công cụ tính toán TCO & Phân tích ROI Cloud CDN (E05)
- **Vấn đề trước sửa:** Bảng tính TCO không trừ 100GB Always Free của GCS; tính sai công thức Cloud CDN (cộng dồn cả phí Internet egress gốc lẫn phí CDN data transfer out, dẫn đến việc tính tiền hai lần cho cùng một GB dữ liệu).
- **Giải pháp triển khai:**
  - Trong [calculate-tco.mjs](file:///F:/AI/12Tester/tools/tco-calculator/calculate-tco.mjs):
    - Bảng giá đối chiếu ngày 2026-10-28 từ Google Cloud chính thức.
    - Trừ đúng 100 GiB Always Free tier vào phần tính billable egress của GCS.
    - Sửa công thức Cloud CDN: Trường hợp Cache Miss trả phí CDN Edge Data Transfer Out ($0.08/GB) + Cache Fill Egress ($0.01/GB). Lưu lượng nội bộ từ Google Cloud Storage sang Google Cloud CDN là **$0.00/GB**.
    - Gắn nhãn kết quả rõ ràng: `scenarioType: "hypothetical_scenario"` và `disclaimer`.
- **Kết quả tính toán thực tế:**
  ```json
  {
    "scenarioType": "hypothetical_scenario",
    "pricing": {
      "gcsAlwaysFreeEgressGb": 100,
      "gcsEgressPerGb": 0.12,
      "cdnEdgeEgressPerGb": 0.08,
      "cdnCacheFillEgressPerGb": 0.01,
      "cdnRequestPer10k": 0.0075,
      "cdnMonthlyFixedOverheadUsd": 5.0
    },
    "baseline": { "monthlyEgressGb": 17.17, "monthlyCostUsd": 0.14 },
    "optimizedE01": { "monthlyEgressGb": 11.44, "reductionPercent": 33.3 },
    "optimizedE02": { "monthlyEgressGb": 6.72, "reductionPercent": 60.8 },
    "e05CdnAnalysis": {
      "iconMonthlyGb": 4.29,
      "iconMonthlyRequests": 300000,
      "originIconCostUsd": 0.63,
      "cdnIconCostUsd": 5.59,
      "cdnMonthlySavingsUsd": -4.96,
      "decision": "NO_GO_SCENARIO",
      "reason": "Với asset nhỏ (15KB), phí request CDN ($0.0075/10k) và fixed overhead cao hơn chi phí Origin Storage Class B ($0.004/10k)."
    }
  }
  ```
  - `calculate-tco.test.mjs`: **4/4 PASS 100%**.

---

## 3. LỆNH ĐÃ THỰC THI & KẾT QUẢ KIỂM CHỨNG THỰC TẾ

### 3.1. Android Unit Tests & Robolectric
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest
```
- **Kết quả:** `BUILD SUCCESSFUL in 14s`
- **Tổng số test suites:** 10 suites
- **Tổng số bài test:** **99 tests đạt 100% (0 failures, 0 errors, 0 skipped)**
  - `com.ntt.crosstest.util.FirebaseEgressOptimizationTest`: 28 tests (E00–E05 optimization, regression, single-flight, tombstoning, generation isolation, disk cache policy).
  - `com.ntt.crosstest.util.EgressAuditProbeTest`: 8 audit probes độc lập (probe01–probe08).
  - `com.ntt.crosstest.util.ReportUrlRetryTest`: 12 tests.
  - `com.ntt.crosstest.data.CacheRegressionTest`: 19 tests.
  - `com.ntt.crosstest.data.ReportSubmitPolicyTest`: 10 tests.
  - `com.ntt.crosstest.work.ReportUploadWorkerTest`: 7 tests.
  - `com.ntt.crosstest.data.PendingReportStoreTest`: 6 tests.
  - `com.ntt.crosstest.data.ReportWorkStateTest`: 4 tests.
  - `com.ntt.crosstest.data.MissionRegressionTest`: 3 tests.
  - `com.ntt.crosstest.data.ReportSubmitManagerIntegrationTest`: 2 tests.

### 3.2. Android Build & Compilation
```powershell
.\gradlew.bat :app:assembleDebug
```
- **Kết quả:** `BUILD SUCCESSFUL in 8s` (39 tasks: 4 executed, 35 up-to-date, 0 errors, 0 warnings).

### 3.3. Android Lint Inspection
```powershell
.\gradlew.bat :app:lintDebug
```
- **Kết quả:** `BUILD SUCCESSFUL in 1m 10s`
- **Thống kê Lint:**
  - **Error:** **0**
  - **Warning:** 423 (đều là các warning cũ của UI layout / translation)
  - **Hint:** 1

### 3.4. Git Diff & Code Formatting
```powershell
git diff --check
```
- **Kết quả:** Thoát với mã code `0`, sạch toàn bộ whitespace và lỗi định dạng.

### 3.5. Node.js & Database In-Memory Tests
```powershell
node audit/firebase-egress-2026-10-28/db-audit.mjs
node audit/firebase-egress-2026-10-28/misc-audit.mjs
node supabase/tests/run-thumbnail-migration.mjs
node supabase/tests/run-media-gateway-test.mjs
node backend/storage-cleanup/cleanup-reports-storage.test.mjs
node backend/storage-cleanup/dry-run-token-revocation.test.mjs
node tools/tco-calculator/calculate-tco.test.mjs
node tools/telemetry-reconciliation/reconcile-telemetry.test.mjs
```
- **Kết quả:** Tất cả 8 suite Node.js test đều hoàn thành xuất sắc với tỷ lệ **100% PASS**.

---

## 4. TRẠNG THÁI CÁC CỜ TÍNH NĂNG (FEATURE FLAGS)

| Tên cờ | Giá trị mặc định | Vị trí định nghĩa | Đánh giá an toàn |
|---|---|---|---|
| `isPrivateDiskCacheEnabled` | `false` | `ScreenshotCachePolicy.kt` | An toàn tuyệt đối. Mọi thao tác cache đĩa đều fail-closed khi cờ tắt, không ghi đĩa trái phép. |
| `isCdnRoutingEnabled` | `false` | `IconRoutingHelper.kt` | An toàn tuyệt đối. `cdnHost` mặc định `""`. Chỉ định tuyến khi cấu hình domain thật và cờ được bật chủ động. |
| `appCheckObservationMode` | `MONITOR_ONLY` | `AppCheckObservationConfig.kt` | An toàn tuyệt đối. Chỉ ghi nhận thống kê tỷ lệ hợp lệ, không chặn bất kỳ request nào của user. |

---

## 5. BẢNG KIỂM CỔNG PRODUCTION (PRODUCTION GATE CHECKLIST)

### 5.1. Những gì đã được chứng minh cục bộ (Verified Locally)
- [x] Đã khôi phục toàn bộ ràng buộc nghiệp vụ check-in, feedback, rating và duplicate rejection trong migration 045.
- [x] Đã chuyển đổi Media Gateway sang HMAC-SHA256 thật với mã thời hạn 30 phút và phân quyền 3 vai trò (author, owner, admin) trong migration 046.
- [x] Đã sửa pipeline thumbnail để không làm mất file khi lưu pending report.
- [x] Đã khắc phục triệt để race condition bộ nhớ đệm đĩa khi đăng xuất / clear phiên.
- [x] Đã cô lập bộ nhớ đệm đĩa theo account ID và đảm bảo decode ARGB_8888 không làm lệch mã dHash.
- [x] Đã cấu hình Storage Rules và Worker dọn dẹp để ngăn client giả mạo metadata `parent_time_created`.
- [x] Đã cập nhật công cụ đối chiếu Telemetry để phát hiện số liệu bất thường và chuẩn hoá Runbook.
- [x] Đã kiểm chứng 99/99 unit tests, `assembleDebug` và `lintDebug` (0 lỗi).

### 5.2. Những gì chưa thể thực hiện và cần quyền Cloud thật (Missing Cloud Infrastructure & Permissions)
- [ ] **Chưa apply migration 045 & 046 lên Supabase Production:** Không tự ý chạy migration trên database live theo đúng quy tắc làm việc. Cần DBA/Backend Lead thẩm định và áp dụng qua pipeline CI/CD.
- [ ] **Chưa deploy Cloud Run Storage Cleanup Worker:** Thư mục `backend/storage-cleanup` cần chạy `npm install` trên môi trường build server và cấp quyền Service Account (`roles/storage.objectAdmin`) với biến môi trường `GOOGLE_APPLICATION_CREDENTIALS`.
- [ ] **Chưa thu hồi token ảnh cũ (Token Revocation):** Script `dry-run-token-revocation.mjs` đã thiết lập tiền điều kiện tỷ lệ client mới $\ge 95\%$ và tỷ lệ lỗi gateway $\le 1\%$. Hiện tại chưa được phép thu hồi token trên production để tránh làm hỏng hiển thị của người dùng bản cũ.
- [ ] **Chưa kiểm thử trên thiết bị vật lý thật:** Kiểm thử hiện tại được thực hiện tự động qua Robolectric (SDK 34) và in-memory PostgreSQL (PGlite). Màn hình Compose Dialog và giao diện thực tế cần được QA kiểm tra trực tiếp trên thiết bị Android thật.

---

## 6. CÁC BƯỚC TIẾP NHẬN TIẾP THEO (NEXT ACCEPTANCE STEPS)
1. **Giai đoạn 1 (Canary 10%):** Phát hành bản build Android mới lên kênh Internal Testing / Closed Beta. Theo dõi telemetry qua `ImageInstrumentation` trong vòng 7 ngày để thu thập số đo baseline thực tế theo Runbook.
2. **Giai đoạn 2 (Database Migration):** DBA áp dụng migration `045_thumbnail_support.sql` và `046_media_access_gateway.sql` lên staging trước khi lên production.
3. **Giai đoạn 3 (Worker Deployment):** Cấu hình Cloud Scheduler kích hoạt Cloud Run dọn dẹp storage định kỳ lúc 02:00 sáng hàng ngày với cờ `--dry-run` trong 3 ngày đầu để xác nhận danh sách file quá hạn $\ge 29$ ngày trước khi chuyển sang chế độ xoá thực tế.
4. **Giai đoạn 4 (Token Revocation Evaluation):** Sau 30 ngày kể từ khi bản build mới đạt tỷ lệ $\ge 95\%$, kích hoạt `dry-run-token-revocation.mjs` để đánh giá điều kiện tiên quyết trước khi quyết định thu hồi download token vĩnh viễn.