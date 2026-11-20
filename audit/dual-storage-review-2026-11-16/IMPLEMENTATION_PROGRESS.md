# Tiến độ khắc phục Dual Storage sau Review độc lập (16/11/2026)

> Cập nhật chốt: đã hoàn tất vòng giám sát sửa mã. Android 170/170, backend unit 73/73, HTTP gateway 13/13 và các suite SQL/media/nghiệp vụ đạt. Xem `IMPLEMENTATION_HANDOVER.md` và `FINAL_REVIEW.json` để dùng kết quả cuối; nhật ký bên dưới giữ lịch sử các vòng sửa. PostgreSQL nhiều kết nối và kiểm thử vận hành Storage/scheduler thực còn NOT RUN; chưa deploy.

## 1. Thông tin chung
- Căn cứ kế hoạch: `docs/plans/DUAL_STORAGE_FIX_PLAN_AFTER_REVIEW_2026-11-16.md`
- Quy chuẩn bắt buộc: `AGENTS.md` và `docs/AI_NAVIGATION.md`
- Bộ kiểm tra độc lập: `audit/dual-storage-review-2026-11-16/review-probes.mjs` (14 assertions)
- Kết quả baseline ban đầu: **1 PASS, 13 FAIL**
- Mục tiêu: Khắc phục triệt để F01–F10, đưa toàn bộ 14 assertions đạt PASS mà không sửa/hạ chuẩn assertion; tạo migration tiếp nối 092; hoàn thiện mã Android, Backend, SQL và tài liệu bàn giao.

## 2. Trạng thái các bước thực hiện

| Bước | Nội dung | Trạng thái |
|---|---|---|
| Bước 0 | Chốt baseline, kiểm tra probe ban đầu & khởi tạo tài liệu theo dõi | HOÀN TẤT |
| Bước 1 | Tạo Migration 092: Sổ media, provenance, tuổi thật Storage, kế toán U+R, RLS (Giám sát tiếp quản) | CHUYỂN GIAO GIÁM SÁT |
| Bước 2 | Sửa Backend: Cleanup lease/retry/dry-run/partial/path, Reconciler validation, Gateway dual adapter (Giám sát tiếp quản) | CHUYỂN GIAO GIÁM SÁT |
| Bước 3 | Sửa Android: Exact host auth token isolation, bỏ fake GCS fallback, 409 conflict HEAD verification, retry pinned quá hạn | HOÀN TẤT (10/10 PASS) |
| Bước 4 | Chạy lại toàn bộ kiểm thử Android: DualStorageUploadRegressionTest, utils, report tests | HOÀN TẤT (100% PASS) |
| Bước 5 | Hoàn thiện tài liệu bàn giao IMPLEMENTATION_ANDROID_HANDOVER.md | HOÀN TẤT |

## 3. Nhật ký tiến độ chi tiết
- **2026-11-16 10:10**: Đọc kỹ `DUAL_STORAGE_FIX_PLAN_AFTER_REVIEW_2026-11-16.md`, `SUPERVISOR_STATUS.md` và `review-probes.mjs`.
- Chạy baseline probe: 1 PASS (CONTROL-GOOGLE), 13 FAIL (`URL-HOST`, `SUBMIT-PROVENANCE`, `OBJECT-CLOCK`, `QUOTA-FINALIZE`, `RLS-EXPIRY`, `CLEANUP-RETRY`, `CLEANUP-DRY-RUN`, `ORPHAN-EXPIRED`, `CLEANUP-PROVIDER`, `DELETE-IDEMPOTENT`, `METRICS-VALIDATION`, `DELETE-PARTIAL`, `DELETE-PATH`).
- Xác định nguyên nhân gốc của 13 fail assertions và các điểm F01–F10.
- Khởi tạo `IMPLEMENTATION_PROGRESS.md`.
- **2026-11-16 10:40**: Nhận chỉ thị phân công dứt điểm từ người dùng: Giám sát tiếp quản toàn bộ SQL (migration 092, SQL tests, SQL index) và toàn bộ Backend (`backend/media-gateway/`, `backend/storage-cleanup/`). Gemini chỉ thực hiện phân hệ Android và test Android.
- **2026-11-16 11:00**: Hoàn thành phân hệ Android:
  1. `ScreenshotManager.kt`: Thêm `isTrustedAuthHost`, cô lập token JWT và `apikey` chỉ gửi tới exact host Supabase từ `BuildConfig.SUPABASE_URL` hoặc Gateway cấu hình; không gửi sang GCS hay URL ngoài.
  2. `CampaignRepository.kt`: Xóa bỏ fake fallback GCS trong `requestMediaReservation`, rethrow lỗi để WorkManager/UI xử lý.
  3. `SupabaseStorageHelper.kt`: Xử lý HTTP 409 Conflict bằng HEAD request đối soát `Content-Length` với `file.length()`.
  4. `ReportUploadWorker.kt`: Tái xác nhận pinned destination quá 20 phút qua server RPC để tuân thủ server contract, không hồi sinh ảnh đã quá hạn.
  5. `DualStorageUploadRegressionTest.kt`: Bổ sung 4 test case mới bao phủ toàn diện 4 yêu cầu trên, nâng tổng số test lên 10.
- **2026-11-16 11:05**: Chạy test bộ `DualStorageUploadRegressionTest`: đạt **10/10 PASS**. Chạy suite hồi quy `util.*` và `data.Report*`: `BUILD SUCCESSFUL`.
- **2026-11-16 11:06**: Viết và xuất bản `audit/dual-storage-review-2026-11-16/IMPLEMENTATION_ANDROID_HANDOVER.md`. Bàn giao lại cho Giám sát.
