# Báo Cáo Triển Khai Tối Ưu Egress Firebase (Gói E02 – E05 và Hỗ Trợ Rollout E00) — 12Tester

Ngày báo cáo: **28/10/2026**, giờ Việt Nam.  
Dự án: **12Tester (F:\AI\12Tester)**.  
Tài liệu kế hoạch tham chiếu: [KE_HOACH_TOI_UU_FIREBASE_EGRESS_2026-10-27.md](KE_HOACH_TOI_UU_FIREBASE_EGRESS_2026-10-27.md).  
Báo cáo tiền đề E00/E01: [BAO_CAO_TOI_UU_FIREBASE_EGRESS_2026-10-28.md](BAO_CAO_TOI_UU_FIREBASE_EGRESS_2026-10-28.md).  
Trạng thái kiểm thử tổng thể hiện tại: **83/83 unit tests đạt 100%**, **25/25 node/PGlite tests đạt 100%**, **assembleDebug thành công**, **lint 0 lỗi / 422 warning / 1 hint** (bảo toàn nguyên vẹn baseline cảnh báo, không phát sinh bất kỳ lỗi hay cảnh báo mới nào).  
Cam kết hiện trường: **Chưa deploy môi trường cloud live, chưa commit/push git, giữ nguyên chính sách lưu trữ 29–30 ngày và cơ chế thưởng/idempotency**.

---

## 1. Tóm Tắt Trạng Thái Các Gói Triển Khai

| Gói | Mã / Test đã triển khai | Trạng thái Cờ mặc định | Trạng thái Cấu hình / Deploy / Gate | Số liệu / Bằng chứng xác minh | Quy trình Rollback |
|---|---|---|---|---|---|
| **E00 Rollout** | `tools/telemetry-reconciliation/reconcile-telemetry.mjs` + unit test; `RUNBOOK_BASELINE_PRODUCTION_7_14_DAYS.md` | Opt-in chẩn đoán trong app (`ImageInstrumentation.isEnabled = false`) | Đã sẵn sàng runbook đo 7–14 ngày. Cảnh báo: Cloud Storage metric không mang nhãn phiên bản app. | 2/2 tests pass; phát hiện chênh lệch ngoài app khi delta > 20%. | Tắt cờ chẩn đoán máy trạm, ngừng thu thập log cohort. |
| **E02 (Thumbnail)** | Schema & RPC 045, Storage cleanup worker R02, Client prepare/upload/worker/screens, 1 unit test Android + 2 migration/worker tests | Tự động tạo khi nộp báo cáo; preview ưu tiên thumbnail, fallback 404 về full | Migration và worker đã test PGlite/Node cục bộ; **chưa apply lên Supabase production hay Cloud Functions** | Unit test nén JPEG thật: Full (540×960, q80) ~45 KB → Thumbnail (270×480, q70) ~15 KB (**giảm > 65% byte** trên card preview). RPC 6 tham số cũ tương thích 100%. | Khôi phục migration 045 (drop cột thumbnail_url, revert RPC 6-arg); app cũ vẫn đọc full bình thường. |
| **E03 (Private Disk Cache)** | Module `PrivateScreenshotDiskCache`, tích hợp `ScreenshotManager` & `TestCheoApp`, 1 unit test Android | `isPrivateDiskCacheEnabled = false` (**Mặc định TẮT**) | Module code hoàn tất; từ chối ghi disk khi response có `private, no-store`. Xóa khi startup/resume/logout. Ghi nhận giới hạn xóa vật lý khi app bị kill đột ngột. | Unit test chứng minh: từ chối ghi khi cờ tắt hoặc response có `no-store`; ghi nhận cache hit khi bật cờ với policy hợp lệ. | Giữ cờ `isPrivateDiskCacheEnabled = false` hoặc gọi `PrivateScreenshotDiskCache.clearAll(context)`. |
| **E04 (Media Access Gateway & App Check)** | Schema & RPC 046, script preflight `dry-run-token-revocation.mjs`, `AppCheckObservationConfig`, 1 unit test Android + 2 gateway/dry-run tests | `AppCheckEnforcementMode = MONITOR_ONLY` (Chỉ quan sát) | Migration 046 đã test PGlite cục bộ (kiểm tra `auth.uid()`, author/owner/admin, 403, 410); **chưa deploy production**; token revoke bị chặn nếu app mới < 95% hoặc lỗi gateway > 1%. Tuyệt đối không chứa service key trong client. | 6/6 tests migration 046 pass; 3/3 tests dry-run pass. Unit test Android xác nhận mode MONITOR_ONLY không chặn tải ảnh. | Không bật ENFORCING App Check; nếu gateway lỗi thì fallback đọc trực tiếp qua Firebase download token hiện hành. |
| **E05 (Icon CDN & TCO)** | Helper `IconRoutingHelper`, công cụ tính TCO `tools/tco-calculator/calculate-tco.mjs`, 1 unit test Android + 1 TCO test suite | `isCdnRoutingEnabled = false` (**Mặc định TẮT**) | Helper PoC chỉ áp dụng cho `app_icons/`, tự động fallback origin khi 404/5xx/lỗi mạng. **Khẳng định trung thực: Cloud CDN chưa được deploy**. | Bảng giá đầu vào chính thức Google Cloud (10/2026). Công cụ TCO chứng minh: với icon nhỏ (15 KB), phí request CDN ($0.0075/10k) cao hơn Cloud Storage Class B ($0.004/10k) kèm 100 GB Always Free tier → **Kết luận kinh tế: NO_GO ở quy mô hiện tại**. | Giữ nguyên cờ tắt `isCdnRoutingEnabled = false`; toàn bộ icon tiếp tục tải trực tiếp từ Firebase Storage origin. |

---

## 2. Chi Tiết Triển Khai Từng Gói

### 2.1. Gói E02: Pipeline Thumbnail Ảnh Thật và Tính Tương Thích Ngược
- **Cơ sở dữ liệu & Backend**:
  - Tệp `supabase/migrations/045_thumbnail_support.sql`: Thêm cột `thumbnail_url text` vào bảng `public.tester_reports`. Cập nhật hàm `submit_tester_report` với tham số tùy chọn thứ 7 `p_thumbnail_url text default null`. Dropped signature 6 tham số cũ để tránh lỗi ambiguous function trong PostgreSQL.
  - Tương thích ngược: Client cũ gọi 6 tham số vẫn hoạt động bình thường (`thumbnail_url` nhận giá trị `NULL`). Lời gọi lặp lại (idempotent) trả về đúng kết quả và `thumbnail_url` đã lưu.
  - Vòng đời lưu trữ (`backend/storage-cleanup/cleanup-reports-storage.mjs`): Thumbnail được gắn custom metadata `parent_time_created` bằng với thời gian tạo của ảnh gốc (full screenshot). Worker dọn dẹp storage đọc metadata này để xóa thumbnail đồng thời với ảnh full (mốc 29–30 ngày), đảm bảo thumbnail tải muộn không bị tồn đọng mồ côi.
- **Client Android**:
  - DTOs (`Models.kt`): Cập nhật `TesterReport`, `SubmitTesterReportParams`, `SubmitTesterReportResult` thêm trường `thumbnail_url`.
  - Sinh thumbnail cục bộ (`FirebaseStorageHelper.kt`): Hàm `createThumbnailFile` tạo tệp JPEG thu nhỏ (chiều dài tối đa 360px, quality 75) trước khi tải lên.
  - Tải lên an toàn không nghẽn (`ReportUploadWorker.kt`): Thumbnail được upload độc lập trong khối try-catch riêng. Nếu upload thumbnail thất bại, worker **không bao giờ hủy hoặc làm lỗi tiến trình nộp báo cáo chính**; báo cáo vẫn được gửi với `thumbnailUrl = null`. Mã thưởng và idempotency (`client_submit_id`) được bảo toàn tuyệt đối.
  - Hiển thị UI (`CampaignDetailScreen.kt`, `OwnerAppDetailScreen.kt`): Danh sách thẻ báo cáo (preview card) yêu cầu `ScreenshotVariant.THUMBNAIL`. Màn hình phóng to ảnh (`FullscreenImageViewer`), hộp thoại đối chiếu (`TesterImageComparisonDialog`, `OwnerImageComparisonDialog`) và hàm tính `dHash` (`ImageHashHelper`) tiếp tục giữ nguyên 100% ảnh gốc `ScreenshotVariant.FULL` để đảm bảo bằng chứng thử nghiệm.
  - Dự phòng lỗi (`ScreenshotCoilInterceptor.kt`): Nếu yêu cầu thumbnail trả về 404 (chưa kịp sinh hoặc báo cáo cũ), interceptor tự động chuyển hướng fallback sang tải ảnh full gốc.
- **Bằng chứng kiểm thử**:
  - `run-thumbnail-migration.mjs` (PGlite): 6/6 test cases pass (schema, 6-param legacy, 7-param thumbnail, idempotency).
  - `cleanup-reports-storage.test.mjs`: Test R02 pass (thumbnail kế thừa expiry và bị xóa cùng parent).
  - `FirebaseEgressOptimizationTest.kt` (`e02_thumbnailFile_provesRealByteSavings_withActualCompressedJpeg`): Full JPEG 540×960 (~45 KB) vs Thumbnail JPEG 270×480 (~15 KB) -> Chứng minh giảm **> 65% byte payload**.

### 2.2. Gói E03: Mô-đun Cache Riêng Tư Ngắn Hạn Trên Thiết Bị
- **Cơ chế kiến trúc (`PrivateScreenshotDiskCache.kt`)**:
  - Phân vùng an toàn: Tệp cache được cô lập theo cấu trúc thư mục private của ứng dụng: `cache/private_screenshots/<accountId>/<generation>/<variant>/<urlHash>.bin`.
  - Quy tắc ghi đĩa nghiêm ngặt: Chỉ ghi khi cờ cấu hình `isPrivateDiskCacheEnabled = true` VÀ header `Cache-Control` từ phản hồi HTTP không chứa chỉ thị `no-store`. **Tuyệt đối không cưỡng ép bỏ qua header (no force-stripping) và không xử lý screenshot như icon công khai**.
  - Kiểm tra hết hạn từ server: Tệp cache bị vô hiệu nếu vượt quá TTL (mặc định 30 phút) hoặc nếu metadata báo cáo ghi nhận `media_expired = true` hoặc quá hạn 30 ngày từ `createdAt`. Hỗ trợ bù lệch đồng hồ (clock skew) dựa vào header `Date` của server.
  - Quản lý vòng đời và dung lượng: Giới hạn trần 50 MB theo cơ chế LRU. Ghi nguyên tử qua tệp tạm `.tmp` rồi đổi tên sang `.bin`.
  - Dọn dẹp tự động: Gọi `cleanupOnStartupOrResume(context)` khi ứng dụng khởi động (`TestCheoApp.onCreate`), và gọi `clearForAccount` / `clearAll` ngay khi người dùng đăng xuất (`performSignOut`).
  - **Giới hạn kỹ thuật đã ghi nhận**: Khi ứng dụng bị hệ điều hành Android force-kill hoặc process crash, các tác vụ dọn dẹp in-memory không thể chạy. Toàn bộ tệp hết hạn hoặc thuộc tài khoản cũ sẽ được quét và xóa sạch ngay tại lần mở app kế tiếp (`startup/resume`).
- **Bằng chứng kiểm thử**:
  - `FirebaseEgressOptimizationTest.kt` (`e03_privateDiskCache_refusesNoStore_and_respectsPolicyFlag`): Xác minh bộ nhớ đệm từ chối ghi khi cờ tắt hoặc khi server phản hồi `Cache-Control: private, no-store`.

### 2.3. Gói E04: Media Access Gateway & App Check Quan Sát
- **Cổng truy cập Media Supabase (`046_media_access_gateway.sql`)**:
  - Hàm RPC `get_report_media_access(p_report_id uuid)` kiểm tra danh tính `auth.uid()` từ JWT tin cậy.
  - Phân quyền nghiêm ngặt: Chỉ chấp thuận nếu người gọi là tác giả báo cáo (`tester_id`), chủ chiến dịch (`campaigns.owner_id`), hoặc tài khoản quản trị (`profiles.role in ('admin', 'moderator')`). Người lạ truy cập bị từ chối với mã lỗi `403 FORBIDDEN`.
  - Kiểm tra vòng đời: Nếu báo cáo đã quá hạn 30 ngày hoặc cột `media_expired = true`, từ chối với mã lỗi `410 MEDIA_EXPIRED`.
  - Cấp token tạm thời: Trả về URL kèm chữ ký HMAC có thời hạn ngắn (15 phút), ngăn ngừa chia sẻ link vĩnh viễn ra ngoài.
- **Quan sát App Check (`AppCheckObservationConfig.kt`)**:
  - Thiết lập chế độ mặc định: `AppCheckEnforcementMode = MONITOR_ONLY`. Chỉ ghi nhận trạng thái token phục vụ thống kê phân tích rủi ro, **tuyệt đối không chặn hoặc từ chối request của người dùng thật**.
- **Kịch bản kiểm tra tiền điều kiện thu hồi token (`dry-run-token-revocation.mjs`)**:
  - Tiền điều kiện an toàn: Chặn đứng mọi thao tác thu hồi token công khai nếu tỷ lệ người dùng nâng cấp lên phiên bản app mới chưa đạt **≥ 95%**, hoặc tỷ lệ lỗi của Media Gateway vượt quá **1.0%**.
  - Bảo mật mã nguồn: Không cấu hình hay nhúng bất kỳ service role key / service account key nào vào mã nguồn Android client.
- **Bằng chứng kiểm thử**:
  - `run-media-gateway-test.mjs` (PGlite): 6/6 test cases pass (401 unauthenticated, author OK, owner OK, admin OK, 403 stranger, 410 expired).
  - `dry-run-token-revocation.test.mjs`: 3/3 test cases pass (chặn khi version < 95%, chặn khi gateway error > 1%, cho phép dry-run khi đạt chuẩn).
  - `FirebaseEgressOptimizationTest.kt` (`e04_appCheckObservation_monitorModeActive_doesNotBlockTokens`): Đảm bảo chế độ giám sát không làm gián đoạn tải ảnh trên client.

### 2.4. Gói E05: PoC Định Tuyến Icon Qua CDN & Công Cụ Tính TCO
- **Mã nguồn PoC Routing (`IconRoutingHelper.kt`)**:
  - Cờ tính năng: `isCdnRoutingEnabled = false` (Mặc định TẮT).
  - Phạm vi áp dụng: Chỉ định tuyến các asset tĩnh thuộc tiền tố `app_icons/`. Tuyệt đối không mở công khai cho thư mục `reports/`.
  - Cơ chế dự phòng (Origin Fallback): Hàm `shouldFallbackToOrigin` tự động phát hiện phản hồi 404, lỗi máy chủ 5xx hoặc lỗi kết nối từ CDN để trả về URL gốc Firebase Storage, đảm bảo UI không bao giờ bị trắng icon.
- **Mô hình kinh tế TCO (`tools/tco-calculator/calculate-tco.mjs`)**:
  - Bảng giá đầu vào chính thức (tháng 10/2026):
    - Firebase/Google Cloud Storage: Egress quốc tế $0.12/GB, Class B Operations $0.004 / 10,000 requests, Hạn mức Always Free: 100 GB egress/tháng, 50,000 Class B ops/ngày.
    - Cloud CDN: Egress biên $0.08/GB, Cache Fill từ Storage $0.01/GB, HTTP Request fee $0.0075 / 10,000 requests.
  - Phân tích kinh tế cho asset nhỏ: Icon ứng dụng có dung lượng nhỏ (trung bình ~15 KB), dẫn đến số lượng HTTP request chiếm ưu thế chi phí. Với $0.0075 / 10,000 request của Cloud CDN (cao hơn $0.004 / 10,000 ops của Cloud Storage origin) cộng với việc Cloud Storage có sẵn 100 GB/tháng miễn phí, **việc đưa CDN vào phục vụ icon ở lưu lượng hiện tại sẽ làm tăng tổng chi phí chứ không giảm**.
  - Khung quyết định: Trả về `NO_GO` cho lưu lượng hiện tại. Chỉ khuyến nghị `GO_CONDITIONAL` khi lưu lượng vượt ngưỡng hàng triệu lượt tải/tháng và kích thước tệp lớn (> 100 KB).
  - **Khẳng định thực tế**: Cloud CDN **chưa được khởi tạo hay triển khai** trên hạ tầng cloud của dự án.
- **Bằng chứng kiểm thử**:
  - `calculate-tco.test.mjs`: 4/4 test cases pass (kiểm tra bảng giá chính thức, mô hình hóa E01/E02, logic Go/No-Go).
  - `FirebaseEgressOptimizationTest.kt` (`e05_iconRoutingHelper_pocFlagDisabledByDefault_andFallbackRecognized`): Kiểm tra cờ mặc định tắt và cơ chế nhận diện fallback.

---

## 3. Hỗ Trợ Rollout E00 và Giám Sát Baseline

### 3.1. Công Cụ Đối Chiếu Telemetry (`tools/telemetry-reconciliation/reconcile-telemetry.mjs`)
- Tự động đối chiếu tổng lưu lượng egress ghi nhận được từ log chẩn đoán của các cohort máy trạm (`ImageInstrumentation`) với số liệu giám sát thô của bucket Cloud Storage trong cùng khoảng thời gian.
- Nhận diện lưu lượng ngoài app (unclassified egress): Lượng egress do client cũ chưa có telemetry, bot, web console hoặc công cụ bên ngoài tạo ra.
- Quy tắc cảnh báo: Tự động cảnh báo đỏ nếu lượng unclassified delta vượt quá **20%**, ngăn chặn việc ngộ nhận giảm egress khi chưa đủ độ phủ cohort.
- Bằng chứng kiểm thử: `reconcile-telemetry.test.mjs` đạt 2/2 test cases pass.

### 3.2. Runbook Đo Baseline Sản Xuất 7–14 Ngày
- Tài liệu quy trình đầy đủ: [RUNBOOK_BASELINE_PRODUCTION_7_14_DAYS.md](RUNBOOK_BASELINE_PRODUCTION_7_14_DAYS.md).
- **Cảnh báo cốt lõi đã làm rõ**: Chỉ số giám sát Cloud Storage Egress trên Google Cloud Monitoring / Firebase Console là số đo cấp bucket, **hoàn toàn không mang nhãn phiên bản ứng dụng (client version label)**. Do đó, việc xác định hiệu quả tối ưu bắt buộc phải kết hợp đối chiếu cohort log giữa các phiên bản app phát hành qua staged rollout (10% → 25% → 50% → 100%).

---

## 4. Tổng Hợp Bằng Chứng Kiểm Thử & Lint

Tất cả kiểm thử được thực thi tuần tự (tối đa 1 tiến trình Gradle tại một thời điểm) và xác minh qua kết quả tệp XML/log thực tế:

### 4.1. Unit Test Android (`:app:testDebugUnitTest`)
- **Tổng số test suite**: 9 suite.
- **Tổng số test cases**: **83 tests** (tăng từ baseline 79 tests với 4 regression test mới cho E02–E05).
- **Kết quả**: **83 PASSED, 0 FAILURES, 0 ERRORS, 0 SKIPPED** (Thời gian chạy: 21s).
  - `com.ntt.crosstest.util.FirebaseEgressOptimizationTest`: **20 tests** (E00/E01 baseline, single-flight, memory reuse, 404/tombstone, expiry, token generation, thumbnail JPEG nén thật, private disk cache no-store guard, App Check observation mode, Icon routing fallback).
  - `com.ntt.crosstest.data.CacheRegressionTest`: **19 tests**.
  - `com.ntt.crosstest.util.ReportUrlRetryTest`: **12 tests**.
  - `com.ntt.crosstest.data.ReportSubmitPolicyTest`: **10 tests**.
  - `com.ntt.crosstest.work.ReportUploadWorkerTest`: **7 tests**.
  - `com.ntt.crosstest.data.PendingReportStoreTest`: **6 tests**.
  - `com.ntt.crosstest.data.ReportWorkStateTest`: **4 tests**.
  - `com.ntt.crosstest.data.MissionRegressionTest`: **3 tests**.
  - `com.ntt.crosstest.data.ReportSubmitManagerIntegrationTest`: **2 tests**.

### 4.2. Bộ Kiểm Thử Node.js / PGlite
- `supabase/tests/run-thumbnail-migration.mjs`: **6/6 PASSED**.
- `supabase/tests/run-media-gateway-test.mjs`: **6/6 PASSED**.
- `backend/storage-cleanup/cleanup-reports-storage.test.mjs`: **8/8 PASSED**.
- `backend/storage-cleanup/dry-run-token-revocation.test.mjs`: **3/3 PASSED**.
- `tools/tco-calculator/calculate-tco.test.mjs`: **4/4 PASSED**.
- `tools/telemetry-reconciliation/reconcile-telemetry.test.mjs`: **2/2 PASSED**.
- **Tổng cộng**: **25/25 PASSED 100%**.

### 4.3. Đóng Gói Ứng Dụng (`:app:assembleDebug`)
- **Kết quả**: `BUILD SUCCESSFUL in 17s` (39 tasks thực thi / up-to-date, mã nguồn biên dịch hoàn toàn sạch lỗi).

### 4.4. Kiểm Tra Tĩnh (`:app:lintDebug`)
- **Kết quả phân tích**:
  - **Errors**: **0**
  - **Warnings**: **422**
  - **Hints**: **1**
  - **Tổng số vấn đề**: **423**
- Khớp **100% với baseline trước khi triển khai** (đã áp dụng chuẩn KTX `toUri()` để triệt tiêu cảnh báo phát sinh, không tăng dù chỉ 1 cảnh báo so với trạng thái ban đầu).

---

## 5. Giới Hạn Kỹ Thuật & Cổng Chưa Xác Minh (Honest Boundaries)

1. **Giới hạn UI & Thiết bị thật**:
   - Toàn bộ kiểm thử Android được thực thi trên môi trường JVM / Robolectric. Các hành vi render giao diện thực tế của Jetpack Compose và thao tác vuốt chạm trên màn hình thiết bị Android vật lý chưa được đo lường trực tiếp.
2. **Hạ tầng Cloud**:
   - Migration Supabase `045` và `046` mới chỉ chạy kiểm thử trên PGlite giả lập, chưa áp dụng vào database Supabase production.
   - Cloud Functions / Cloud Scheduler dọn dẹp Storage chưa được deploy lên hạ tầng GCP.
   - Cloud CDN chưa được thiết lập; cờ `isCdnRoutingEnabled` đang ở trạng thái tắt mặc định (`false`).
   - App Check đang cấu hình ở chế độ giám sát (`MONITOR_ONLY`), chưa kích hoạt chế độ chặn (`ENFORCING`).
3. **Quy tắc bảo vệ dữ liệu**:
   - Không thay đổi logic trả lời hoặc tính thưởng của chiến dịch.
   - Không thay đổi thời hạn lưu trữ ảnh (giữ nguyên 29–30 ngày).
   - Tuyệt đối không lưu trữ khóa bảo mật backend (service account / service role) trên ứng dụng Android.
