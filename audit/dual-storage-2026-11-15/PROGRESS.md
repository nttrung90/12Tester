# Tiến độ triển khai Dual Storage Report Screenshots (15/11/2026)

## Kế hoạch và mục tiêu
- Kế hoạch: `docs/plans/DUAL_STORAGE_REPORT_SCREENSHOTS_PLAN_2026-11-15.md`
- Mục tiêu: Lưu ảnh báo cáo song song giữa Supabase Private Storage và Google Cloud Storage; ưu tiên Supabase, điều phối theo mốc 95%/90% toàn bộ hạn mức Storage của tổ chức, kèm reservation chống upload đồng thời; vòng đời Supabase 9 ngày (216h UTC) ngừng xem, tối đa 10 ngày (240h UTC) xóa vật lý bằng Storage API; giữ nguyên chính sách Google Cloud hiện hành (30 ngày); giữ nguyên logic điểm thưởng/duyệt/idempotency/receipts.

## Trạng thái các giai đoạn

| Giai đoạn | Nội dung | Trạng thái |
|---|---|---|
| Giai đoạn 1 | Thiết kế chi tiết & Khởi tạo tài liệu kiểm soát | HOÀN TẤT |
| Giai đoạn 2 | Migration SQL (091), Ledger, Quota & Dispatch RPCs, Allowlist | HOÀN TẤT |
| Giai đoạn 3 | Backend Workers (Supabase Cleanup, Org Storage Reconciler, Quota Scraper) | HOÀN TẤT |
| Giai đoạn 4 | Android Upload Adapter, Pinning, PendingStore & ReportUploadWorker | HOÀN TẤT |
| Giai đoạn 5 | Android Image Viewer, Expiry từng Attachment, Cache & Session Boundary | HOÀN TẤT |
| Giai đoạn 6 | Bộ kiểm thử toàn diện (Unit tests, SQL tests, Concurrency tests) | HOÀN TẤT |
| Giai đoạn 7 | Cập nhật SQL Index, Runbook vận hành & Hoàn tất Handover | HOÀN TẤT |

## Nhật ký thay đổi và kiểm thử
- **2026-11-15 23:30**: Đã đọc và rà soát kỹ `AGENTS.md`, `docs/AI_NAVIGATION.md`, `docs/plans/DUAL_STORAGE_REPORT_SCREENSHOTS_PLAN_2026-11-15.md`.
- Đã kiểm tra trạng thái workspace và chỉ mục SQL hiện tại (`tools/ai-sql-index.mjs check`: fresh, 29 migrations 062-090).
- Đã xác định migration tiếp nối là `091_dual_storage_report_screenshots.sql`.
- **2026-11-15 23:42**: Đã hoàn thành `supabase/migrations/091_dual_storage_report_screenshots.sql`:
  - Tạo bucket `report-screenshots` (private) và bảng `storage.objects` bảo vệ RLS.
  - Tạo bảng `storage_organization_usage` lưu trữ quota tổ chức và trạng thái điều phối (`dispatch_mode`).
  - Tạo bảng `report_media_uploads` quản lý reservation, trạng thái upload và vòng đời media.
  - Bổ sung `storage_provider`, `view_expires_at`, `media_expired` cho `tester_reports` và `tester_report_attachments`.
  - Cập nhật hàm validator `is_valid_report_screenshot_url` và `is_valid_report_thumbnail_url` hỗ trợ hai nguồn Google & Supabase (đầy đủ các định dạng URL `public/`, `authenticated/`, raw path).
  - Tạo RPC `request_report_media_reservation` điều phối mốc 95%/90% có tính reservation chống upload đồng thời, pinning provider/path qua upload_id, fail-safe về Google khi thiếu số liệu tổ chức.
  - Cập nhật `_submit_tester_report_core` quản lý vòng đời Supabase 9 ngày (216h) / 10 ngày (240h) và Google (30 ngày), đồng bộ ghi `public.credit_logs`.
  - Cập nhật `request_report_media_access` và `verify_media_access_grant_internal` áp dụng hạn xem theo provider / attachment.
  - Cập nhật các RPC đọc: `get_campaign_detail`, `get_campaign_reports_for_owner`, `admin_get_tester_reports_queue`.
  - Tạo các RPC cho worker: `get_expired_report_media_for_cleanup`, `record_report_media_deleted`, `reconcile_storage_organization_usage`.
  - Đã chạy `node tools/ai-sql-index.mjs build` thành công, index fresh 30 migrations, 253 functions.
  - Đã chạy `node --test tools/ai-navigation.test.mjs` thành công 7/7 tests.
- **2026-11-15 23:46**: Đã hoàn thành Giai đoạn 3 (Backend Workers):
  - Viết worker `backend/storage-cleanup/cleanup-supabase-storage.mjs`: quét mỗi 5 phút, chặn xem từ 216h UTC, xóa Storage API tối đa 240h UTC, xử lý 404 idempotent, retry 429/5xx, cảnh báo vi phạm SLA và dọn orphan.
  - Viết worker `backend/storage-cleanup/reconcile-org-storage.mjs`: đối soát dung lượng toàn bộ dự án trong tổ chức, bảo đảm không giả quota, áp dụng mốc 95%/90% và fail-safe về Google khi thiếu số liệu.
  - Cập nhật `backend/media-gateway/adapters/storage-adapter.mjs` bổ sung `SupabaseStorageAdapter` và `DualStorageAdapter`.
  - Viết và chạy thành công các bộ unit test:
    - `cleanup-supabase-storage.test.mjs`: 4/4 PASS.
    - `reconcile-org-storage.test.mjs`: 3/3 PASS.
    - `cleanup-reports-storage.test.mjs`: 8/8 PASS.
    - `media-gateway-server.test.mjs`: 13/13 PASS.
  - Tạo tài liệu vận hành chi tiết `backend/storage-cleanup/RUNBOOK_DUAL_STORAGE.md`.
- **2026-11-16 00:00**: Đã hoàn thành Giai đoạn 4 (Android Upload Adapter & Pinning):
  - Viết `app/src/main/java/com/ntt/crosstest/util/SupabaseStorageHelper.kt`: OkHttp client upload trực tiếp Supabase Storage REST API với token auth, xử lý 409 conflict idempotent, upload thumbnail song song.
  - Cập nhật `app/src/main/java/com/ntt/crosstest/data/Models.kt`: thêm `storageProvider`, `viewExpiresAt`, `mediaExpired` cho `TesterReport` và `ReportAttachment`; tính toán hạn xem 216h (Supabase) vs 30 ngày (Google); thêm models `RequestReportMediaReservationParams`, `MediaReservationResult`.
  - Cập nhật `app/src/main/java/com/ntt/crosstest/data/PendingReportStore.kt`: lưu `targetProvider`, `bucket`, `storagePath`, `thumbnailStoragePath`, `reservationId` cho `PendingAttachment` và `PendingReport`; hỗ trợ `markAttachmentReservation` và `markReportReservation` bảo vệ pinning qua process death.
  - Cập nhật `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`: thêm hàm `requestMediaReservation` gọi RPC `request_report_media_reservation` với fallback Google an toàn.
  - Cập nhật `app/src/main/java/com/ntt/crosstest/work/ReportUploadWorker.kt`: điều phối upload theo reservation được pin, hỗ trợ upload cả đơn và đa attachment lên Supabase hoặc Google, giữ nguyên receipts idempotency.
- **2026-11-16 00:05**: Đã hoàn thành Giai đoạn 5 (Android Image & Cache Expiry Handling):
  - Cập nhật `app/src/main/java/com/ntt/crosstest/util/ScreenshotRequestFactory.kt`: trích xuất `provider`, `viewExpiresAt` và hạn từng attachment vào `ScreenshotDescriptor`.
  - Cập nhật `app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt`: phân biệt URL Supabase (`report-screenshots`) và Google (`/reports/`), kiểm tra hạn 216h (9 ngày) cho Supabase vs 30 ngày cho Google, hỗ trợ `viewExpiresAt` và tombstone 404/410.
  - Cập nhật `app/src/main/java/com/ntt/crosstest/util/PrivateScreenshotDiskCache.kt`: tính TTL và retention cutoff trên đĩa theo provider (216h vs 30d), cô lập theo accountId, xóa ngay file khi phát hiện quá hạn hoặc tài khoản thay đổi.
- **2026-11-16 00:12**: Đã hoàn thành Giai đoạn 6 (Kiểm thử thực nghiệm):
  - Viết test suite `app/src/test/java/com/ntt/crosstest/data/DualStorageUploadRegressionTest.kt`:
    - `testSupabaseVsGoogleRetentionCutoffCalculation`: PASS
    - `testIndependentPerAttachmentExpiry`: PASS
    - `testPendingReportStorePinningAcrossProcessDeath`: PASS
    - `testScreenshotManagerRecognizesSupabasePathsAndExpiry`: PASS
    - `testScreenshotRequestFactoryPopulatesProviderAndViewExpiresAt`: PASS
    - `testReportUploadWorkerDispatchesToSupabaseWhenReserved`: PASS
  - Chạy `ReportUploadWorkerTest`: PASS (13/13 tests).
  - Chạy `CacheRegressionTest`: PASS.
  - Viết test suite `supabase/tests/run-dual-storage-quota-test.mjs` kiểm thử toàn diện trên PGlite:
    - Test 1 (94% usage -> Supabase Preferred): PASS
    - Test 2 (Pinning qua retry không cộng trùng): PASS
    - Test 3 (>=95% overflow sang Google Cloud): PASS
    - Test 4 (Hysteresis trong khoảng [90%, 95%)): PASS
    - Test 5 (Hồi phục dưới 90% về Supabase): PASS
    - Test 6 (Fail-safe khi số liệu quota lỗi/cũ >30p): PASS
    - Test 7 (Nộp báo cáo chốt reservation & chặn 216h): PASS
    - Test 8 (request_report_media_access chặn 216h Supabase): PASS
  - Kiểm tra multi-connection concurrency:
    - `TEST_DATABASE_URL` không được cung cấp trong môi trường test này.
    - Ghi nhận rõ: `[NOT RUN]` (theo đúng chỉ thị bắt buộc trong `AGENTS.md`).
- **2026-11-16 00:14**: Giai đoạn 7:
  - Cập nhật và kiểm tra SQL index: `{"fresh":true,"migrations":30,"functions":253}`.
  - Tạo `audit/dual-storage-2026-11-15/HANDOVER.md`.
