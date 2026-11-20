# Bàn Giao Triển Khai Dual Storage — Phân Hệ Android (Bản Chốt Nghiệm Thu)

**Thời gian:** 2026-11-16T16:25:00+07:00 (09:25:00Z)  
**Thực hiện:** Gemini (Agent Phân hệ Android)  
**Tiếp nhận & Giám sát:** Kỹ sư giám sát (sở hữu SQL migration 092, SQL index/tests và backend)  
**Tài liệu tham chiếu:** `AGENTS.md`, `docs/AI_NAVIGATION.md`, `audit/dual-storage-review-2026-11-16/RPC_CONTRACT.md`

---

## 1. Trạng Thái Tổng Thể (Status Matrix)

| Hạng mục | Trạng thái | Chi tiết kỹ thuật |
| :--- | :---: | :--- |
| **Decode `upload_expired` từ RPC 092** | `PASS` | Thêm `@SerialName("upload_expired") val uploadExpired: Boolean = false` vào `MediaReservationResult`. |
| **Xử lý `upload_expired: true` (Supabase)** | `PASS` | Worker không POST tạo mới; chỉ GET authenticated xác minh SHA-256 object tồn tại (main & thumb) rồi submit. Nếu thiếu main/đã xóa -> terminal failure `media_upload_expired_missing_remote`, giữ nguyên id/path, không tự đổi provider. Áp dụng cho cả single và V3 multi-attachment. |
| **Xử lý GCS quá 30 phút** | `PASS` | Server 092 chỉ bật `upload_expired` cho Supabase; GCS quá 30 phút giữ nguyên path/provider và upload Google bình thường (`testGoogleOverflowBeyond30MinutesUploadsNormally` PASS). |
| **Chính Sách Hạn Expiry Thuần `MediaExpiryPolicy`** | `PASS` | Loại bỏ toàn bộ wildcard `.supabase.co`, `.supabase.in` và localhost tùy ý. So khớp exact origin (scheme + host + effectivePort) với `BuildConfig.SUPABASE_URL`, path bắt buộc `storage/v1/object/(authenticated\|public)/report-screenshots`. URL foreign trả về `false`. Provider explicit (gcs) tuyệt đối không bị ghi đè bởi filename có chữ "supabase". So sánh cutoff chính xác `>=`. |
| **Xử Lý Hạn Authoritative `viewExpiresAt`** | `PASS` | `viewExpiresAt` hợp lệ trong tương lai là hạn authoritative: trả về `false` (còn hạn) ngay lập tức, không xét `createdAt` cũ 216h làm ảnh thay mới/gia hạn bị hết hạn sai. |
| **Tách Rời Vòng Đời Parent & Child Attachments** | `PASS` | Khi `matchingAtt != null`, dùng metadata riêng của child (`att.createdAt`, `att.viewExpiresAt`, provider riêng). Parent hết hạn không chặn child còn sống. Provider của child không kế thừa parent để tránh lỗi mixed provider (infer theo URL nếu rỗng, áp dụng hạn 30 ngày cho GCS child). |
| **Sửa Toàn Bộ UI Callers & Pipeline Selected Media** | `PASS` | `FullscreenImageViewer`: resolve child descriptor không kế thừa viewExpiresAt/provider của cha; `OwnerAppDetailScreen`: gallery tính `allExpired` từ attachments, không ẩn cả gallery khi còn child sống, click preview truyền đúng `att.isExpired`; `remember` model key theo `att` data class đầy đủ; `TesterImageComparisonDialog`: Giám sát đã chuẩn hóa `curViewExpiresAt = currentDescriptor?.viewExpiresAt` và `prevViewExpiresAt = previousDescriptor?.viewExpiresAt` (bỏ fallback cha); `ScreenshotManager.getOrFetchBitmap`, `ImageHashHelper`, `ComparisonStateCoordinator` dùng descriptor URL được chọn. |
| **Phân biệt Lỗi Tạm Thời vs Vĩnh Viễn khi Verify Remote** | `PASS` | `verifyRemoteObjectSha256` phân biệt rõ: lỗi vĩnh viễn (HTTP 404/410, SHA-256 mismatch, length mismatch) -> trả về `false` (terminal); lỗi tạm thời/mạng (HTTP 429, 500..599, SocketTimeout, IO) -> ném `IOException` để worker kích hoạt `Result.retry()`, không POST mới và không đổi sang Google. |
| **Rethrow CancellationException ở Thumbnail** | `PASS` | Toàn bộ các khối upload thumbnail và verify thumbnail (cả Single và V3 multi-attachment) đều rethrow `CancellationException` tường minh. |
| **Bảo Toàn Hợp Đồng `hasThumbnail`** | `PASS` | Khi gọi `reserve` pinned, `hasThumbnail` giữ nguyên hợp đồng đã pin: `(thumbFile != null) || !thumbnailStoragePath.isNullOrBlank()` ngay cả khi file thumbnail local bị xóa/dọn dẹp. |
| **Bộ Kiểm Thử Android Kết Hợp (17 Suites)** | `PASS` | **17/17 test suites (170 tests)** hoàn thành `BUILD SUCCESSFUL` (100% PASS, 0 failures, 0 errors, 0 skipped), bao gồm suite `SelectedMediaRegressionTest` (4 tests) mới được bổ sung. |
| **Suite HTTP Media Gateway (Native Execution)** | `PASS` | `node --test backend/media-gateway/media-gateway-server.test.mjs`: **13/13 tests PASS** (0 failures, 0 skipped, thời gian 1.14s), log lưu tại `gateway-http-final.log`. |
| **PostgreSQL Nhiều Kết Nối (PG)** | `NOT RUN` | Thiếu `TEST_DATABASE_URL` trong môi trường hiện tại (ghi nhận chuẩn theo AGENTS.md, không giả mạo PASS). |

---

## 2. Bằng Chứng Thực Thi Kiểm Thử Thực Tế

### 2.1. Invocation Android Kết Hợp Duy Nhất (17 Suites / 170 Tests PASS)
Lệnh thực thi:
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew testDebugUnitTest `
  --tests com.ntt.crosstest.data.DualStorageUploadRegressionTest `
  --tests com.ntt.crosstest.data.PendingReportStoreTest `
  --tests com.ntt.crosstest.data.ReportSubmitPolicyTest `
  --tests com.ntt.crosstest.data.ReportSubmissionFailureTest `
  --tests com.ntt.crosstest.work.ReportUploadWorkerTest `
  --tests "com.ntt.crosstest.util.*"
```

Log thực thi được lưu tại: [android-final-gradle.log](file:///f:/AI/12Tester/audit/dual-storage-review-2026-11-16/android-final-gradle.log)  
Kết thúc: `BUILD SUCCESSFUL in 39s`.

Bảng tổng hợp từ file JSON [android-final-results.json](file:///f:/AI/12Tester/audit/dual-storage-review-2026-11-16/android-final-results.json):

| STT | Test Suite | Số test | Failures | Errors | Skipped | Kết quả |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: |
| 1 | `com.ntt.crosstest.data.DualStorageUploadRegressionTest` | 23 | 0 | 0 | 0 | **PASS** |
| 2 | `com.ntt.crosstest.data.PendingReportStoreTest` | 7 | 0 | 0 | 0 | **PASS** |
| 3 | `com.ntt.crosstest.data.ReportSubmissionFailureTest` | 4 | 0 | 0 | 0 | **PASS** |
| 4 | `com.ntt.crosstest.data.ReportSubmitPolicyTest` | 10 | 0 | 0 | 0 | **PASS** |
| 5 | `com.ntt.crosstest.util.ComparisonStateCoordinatorTest` | 6 | 0 | 0 | 0 | **PASS** |
| 6 | `com.ntt.crosstest.util.DetailedImageComparatorTest` | 7 | 0 | 0 | 0 | **PASS** |
| 7 | `com.ntt.crosstest.util.EgressAuditProbeTest` | 8 | 0 | 0 | 0 | **PASS** |
| 8 | `com.ntt.crosstest.util.EgressFinalPolicyAuditTest` | 3 | 0 | 0 | 0 | **PASS** |
| 9 | `com.ntt.crosstest.util.EgressGatewayClientAuditTest` | 5 | 0 | 0 | 0 | **PASS** |
| 10 | `com.ntt.crosstest.util.EgressRound2OptInTest` | 8 | 0 | 0 | 0 | **PASS** |
| 11 | `com.ntt.crosstest.util.FirebaseEgressOptimizationTest` | 30 | 0 | 0 | 0 | **PASS** |
| 12 | `com.ntt.crosstest.util.NetworkRecoveryTest` | 4 | 0 | 0 | 0 | **PASS** |
| 13 | `com.ntt.crosstest.util.NextCampaignSelectorTest` | 15 | 0 | 0 | 0 | **PASS** |
| 14 | `com.ntt.crosstest.util.ReportComparisonSelectorTest` | 11 | 0 | 0 | 0 | **PASS** |
| 15 | `com.ntt.crosstest.util.ReportUrlRetryTest` | 12 | 0 | 0 | 0 | **PASS** |
| 16 | `com.ntt.crosstest.util.SelectedMediaRegressionTest` | 4 | 0 | 0 | 0 | **PASS** |
| 17 | `com.ntt.crosstest.work.ReportUploadWorkerTest` | 13 | 0 | 0 | 0 | **PASS** |
| **Tổng** | **17 Suites** | **170** | **0** | **0** | **0** | **100% PASS** |

Toàn bộ 17 file XML thực tế được lưu tại: [android-final-xml/](file:///f:/AI/12Tester/audit/dual-storage-review-2026-11-16/android-final-xml/)

### 2.2. Media Gateway Server HTTP Integration Test (13/13 PASS)
Lệnh thực thi:
```powershell
node --test backend/media-gateway/media-gateway-server.test.mjs
```

Log thực thi được lưu tại: [gateway-http-final.log](file:///f:/AI/12Tester/audit/dual-storage-review-2026-11-16/gateway-http-final.log)  
Kết quả:
```text
▶ Media Access Gateway Integration Tests (E04 & R2-02)
  ✔ 1. Bị từ chối 401 khi thiếu Bearer token (18.826ms)
  ✔ 2. Bị từ chối 400 khi thiếu tham số query hoặc variant sai (6.2591ms)
  ✔ 3. Bị từ chối 410 khi grant token đã hết hạn (2.863ms)
  ✔ 4. Bị từ chối 410 khi báo cáo quá hạn 30 ngày (DB SLA) (2.1802ms)
  ✔ 5. Bị từ chối 403 khi chữ ký HMAC bị sửa đổi (tampered token) (2.0966ms)
  ✔ 6. Bị từ chối 403 khi người lạ dùng trộm token của tác giả (replay/token sharing) (1.5971ms)
  ✔ 7. Thành công 200: Tác giả tải ảnh full và nhận bytes trực tiếp (Không redirect 302) (2.2467ms)
  ✔ 8. Thành công 200: Tác giả tải ảnh thumbnail (2.8651ms)
  ✔ 9. Thành công 200: Campaign Owner tải ảnh báo cáo của tester (1.4433ms)
  ✔ 10. Thành công 200: System Admin tải ảnh báo cáo (1.3654ms)
  ✔ 11. Thành công 200: Tải ảnh attachment phụ cụ thể qua attachment_id (1.3189ms)
  ✔ 12. Smoke test: server.mjs bootstrap fail-closed khi thiếu cấu hình môi trường (985.08ms)
✔ Media Access Gateway Integration Tests (E04 & R2-02) (1042.8164ms)
ℹ tests 13
ℹ suites 0
ℹ pass 13
ℹ fail 0
ℹ duration_ms 1139.1374
```

---

## 3. Kết Luận Hoàn Tất Bàn Giao

1. **Tuân thủ phân công:** Gemini chỉ can thiệp mã nguồn Android (`app/src/main/java`) và unit test Android (`app/src/test/java`). Không sửa SQL migration 092 hay backend code.
2. **Không vi phạm quy trình:** Không tăng version code/name, không build APK/AAB, không commit/push/deploy.
3. **Hoàn tất 100%:** Toàn bộ 170 Android unit tests và 13 Gateway HTTP integration tests đều GREEN (100% PASS). Toàn bộ log và bằng chứng XML, JSON đã được cập nhật hoàn chỉnh tại thư mục `audit/dual-storage-review-2026-11-16/`. Kính chuyển Kỹ sư Giám sát chốt kết quả nghiệm thu tích hợp.
