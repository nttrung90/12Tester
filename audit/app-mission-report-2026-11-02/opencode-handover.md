# Báo Cáo Bàn Giao Triển Khai Toàn Diện: Cửa Sổ Báo Cáo Nhiệm Vụ App

Ngày: 02/11/2026  
Mô hình thực hiện: `google/antigravity-gemini-3.8-flash --variant medium`  
Mã nguồn workspace: `F:/AI/12Tester`  
Tài liệu kế hoạch gốc: `KE_HOACH_CUA_SO_BAO_CAO_NHIEM_VU_APP_2026-11-02.md` (Trạng thái: Implemented & Verified)

---

## 1. Tóm tắt các tính năng đã hoàn thành (Exact Features Completed)

### 1.1 Cửa sổ báo cáo nhiệm vụ riêng (Dedicated Full-Screen Mission Window)
- **Điểm vào và điều hướng**:
  - Route riêng `mission_report/{campaignId}/{missionId}` trong `NavGraph.kt`.
  - Bấm vào mission card, dòng app hay CTA trong `QuestsScreen` và `HomeScreen` đều điều hướng trực tiếp tới cửa sổ nhiệm vụ riêng với đúng `campaignId` và `missionId`.
  - Khôi phục route cũ có `missionId` trong `campaign/{campaignId}?missionId={missionId}` tự động chuyển tiếp tới cửa sổ nhiệm vụ riêng. Điểm vào test app hằng ngày thông thường (`campaign/{campaignId}`) giữ nguyên độc lập.
- **Hai nút trên cùng (Side by side)**:
  - **Mở app**: Sử dụng intent launcher package của campaign (`packageManager.getLaunchIntentForPackage`). Ghi nhận mốc thời gian bắt đầu test khi mở thành công. Nếu thiết bị chưa cài đặt, hiển thị thông báo hướng dẫn người dùng bấm nút "Link Play Store".
  - **Link Play Store**: Mở Google Play Store thông qua `market://details?id=...` (ưu tiên package `com.android.vending`), có cơ chế dự phòng an toàn mở link testing / play store qua trình duyệt.
- **Ràng buộc thời gian mở app 10 giây (Test Duration Guard)**:
  - Ràng buộc chuẩn 10 giây (`MIN_TEST_DURATION_MS = 10_000L`).
  - Nút "Mở app" kích hoạt đếm giờ; `submitReport()` ở ViewModel chặn nộp nếu chưa mở app hoặc chưa đủ 10 giây trải nghiệm thực tế. Nút "Link Play Store" giữ độc lập và không ghi nhận thời gian test.
  - Báo cáo đã duyệt (`todayReport.isApproved == true`) được miễn trừ quy tắc này khi bổ sung nhận xét.
- **Biểu mẫu "Báo cáo nhiệm vụ app" inline**:
  - Nhập nhận xét, đánh giá chất lượng (1–5 sao).
  - Tích hợp sẵn nút "Nhận nhiệm vụ" nếu tester chưa tham gia nhiệm vụ trước khi cho phép gửi.

### 1.2 Hỗ trợ tối đa 3 ảnh minh chứng & Sửa lỗi Photo Picker Contract
- **Contract Activity 1.13.0**: Tách biệt thành hai launcher độc lập trong `MissionReportScreen.kt`:
  - `singlePhotoPickerLauncher`: Dùng `ActivityResultContracts.PickVisualMedia()` khi còn đúng 1 slot trống (`remainingSlots == 1`).
  - `multiplePhotoPickerLauncher`: Dùng `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 3)` khi còn $\ge 2$ slot trống (`remainingSlots >= 2`).
  - Khi đã đủ 3 ảnh: ẩn hoàn toàn nút "Thêm ảnh" và không khởi tạo launcher không hợp lệ. Không bao giờ tạo `PickMultipleVisualMedia(1)`.
  - Truyền danh sách ảnh người dùng chọn trực tiếp vào ViewModel để xử lý giới hạn và hiện thông báo cắt giảm ảnh nếu chọn thừa, không âm thầm nuốt ảnh ở tầng Screen.
- **Nén và lưu trữ**:
  - Tái sử dụng cơ chế nén ảnh, xử lý EXIF, kiểm tra kích thước (< 1.9MB / 2MB Storage cap), sinh thumbnail và hash dHash của `FirebaseStorageHelper.prepareScreenshot()`. Quá trình nén được thực hiện tuần tự để bảo vệ tài nguyên bộ nhớ.
  - Hiển thị danh sách ảnh kèm số lượng `n/3`, trạng thái nén, nút xóa ảnh (x) và xem ảnh toàn màn hình (preview dialog).
  - Chặn gửi khi chưa có ảnh hợp lệ hoặc khi đang nén dở dang. Chặn vượt quá 3 ảnh ở cả tầng UI, Worker, và Backend DB.

### 1.3 Kho lưu trữ bản nháp bền vững (Durable File-Backed Draft Store)
- Triển khai `MissionReportDraftStore.kt`:
  - Lưu trữ metadata bản nháp dưới dạng file JSON bền vững tại vùng `context.noBackupFilesDir / mission_report_drafts`.
  - Khóa định danh chuẩn xác: `"${userId}_${campaignId}_${missionId}_${reportDate}.json"`.
  - Bảo lưu nội dung comment (kể cả xóa trắng có chủ ý), rating, mốc thời gian bắt đầu test `testStartedAtMs` và danh sách các file ảnh đính kèm `DraftAttachment` (kèm uploadId, filePath, thumbnailPath, imageHash, sizeBytes).
  - Khôi phục an toàn và tái kích hoạt lease `PreparedScreenshotCache.acquireLease` khi mở lại cửa sổ nhiệm vụ.
  - Phân lập hoàn toàn theo tài khoản: người dùng khác đăng nhập trên cùng thiết bị không đọc nháp của người dùng trước.
  - **Acknowledge snapshot bền vững**: Chỉ xóa bản nháp khi request đã hoàn tất thành công (`Stage.DONE`) và nội dung/ảnh đính kèm khớp trọn vẹn với snapshot đã gửi. Nếu người dùng chỉnh sửa thêm ảnh hoặc nhận xét mới trong khi bài cũ đang gửi nền, bản nháp mới hơn được bảo toàn nguyên vẹn. Xóa sạch khi route đóng và xử lý cả trường hợp không còn UI mở.

### 1.4 Độc lập hạn mức báo cáo theo ngày (1 Ordinary Report + 1 Mission Report / Day)
- **Database Schema**:
  - Migration mới `supabase/migrations/074_app_mission_window_and_multi_attachments.sql`.
  - Thêm cột `report_date date NOT NULL DEFAULT ((now() at time zone 'Asia/Ho_Chi_Minh')::date)` vào bảng `public.tester_reports`.
  - Partial Unique Indexes chống trùng lặp theo ngày Việt Nam:
    - Báo cáo thường: `(user_id, campaign_id, report_date) WHERE submission_context = 'app_test'`
    - Báo cáo nhiệm vụ: `(user_id, campaign_id, mission_id, report_date) WHERE submission_context = 'mission'`
  - Bảng `public.tester_report_attachments`: lưu danh sách 1..3 ảnh có thứ tự (`position 0..2`), ràng buộc khóa ngoại `report_id` và RLS kế thừa phân quyền đọc của `tester_reports`.
- **Cùng tồn tại độc lập trong ngày**:
  - Tester có thể gửi 1 báo cáo thường và 1 báo cáo nhiệm vụ cho cùng 1 app trong cùng 1 ngày theo bất kỳ thứ tự nào (`app_test -> mission` hoặc `mission -> app_test`).
  - Hai báo cáo lưu thành hai dòng riêng biệt, có ID riêng, tiến trình và trạng thái duyệt (pending/rejected/approved) hoàn toàn độc lập.
  - Nhiệm vụ kéo dài nhiều ngày (ví dụ 3 ngày): mỗi ngày tester được gửi thêm 01 báo cáo nhiệm vụ. Việc duyệt báo cáo ngày 1 (`has_completed = true`) không làm mất quyền gửi báo cáo nhiệm vụ của các ngày tiếp theo trong thời hạn nhiệm vụ (`isJoined = true`).

### 1.5 Hàng đợi gửi nền bền vững & Quản lý dung lượng nguyên tử (Atomic Queue Capacity & Recovery)
- Worker `ReportUploadWorker` tải ảnh lên Firebase Storage tuần tự và ghi checkpoint `markAttachmentUploaded` ngay sau từng ảnh thành công. Nếu gặp sự cố mạng hoặc process bị kill, lần thử tiếp theo sẽ bỏ qua ảnh đã tải xong và tiếp tục phần chưa hoàn tất mà không cần nén hay tải lại từ đầu.
- Gọi RPC nguyên tử `submit_tester_report_v3` với đầy đủ bộ ảnh và receipt snapshot bất biến.
- **Kiểm tra và giữ chỗ dung lượng nguyên tử**: Thực hiện kiểm tra dung lượng (`canAdmit`) và ghi file pending nguyên tử dưới `admissionMutex.withLock` trên `Dispatchers.IO`, hoàn toàn không gây I/O trên caller thread (bảo toàn assertion `round2AdmissionMustNotPerformStorageIoOnCallingThread`). Nếu bị chặn dung lượng (`Blocked("capacity_exceeded")`), nhả ngay khóa admission để request có thể retry khi bộ nhớ được giải phóng.
- **Bảo toàn Receipt Snapshot đầy đủ**: `ReportSubmitManager.handleSuccess()` ưu tiên đọc `completedResult` đầy đủ từ store trước khi xóa metadata nền, bảo toàn trọn vẹn danh sách `attachments` và `reportDate`.
- **Phân lập trạng thái gửi thường và nhiệm vụ**: `ReportSubmitManager` không ghi đè trạng thái của nhiệm vụ vào `campaignStates` của app thường; `CampaignDetailViewModel` bỏ qua các trạng thái `isMissionReport`. Cung cấp các luồng riêng `stateForOrdinary` và `stateForMission`. Đăng xuất dọn dẹp sạch `scopedStates`.
- **Bảo vệ file đính kèm**: `PendingReportStore.cleanup()` không xóa các công việc WorkManager còn đang chạy (`isWorkUnresolved`) và không tính trùng lặp dung lượng ảnh chính; `PreparedScreenshotCache.cleanup()` không xóa các file đang được tham chiếu bởi các bản nháp còn hiệu lực.

### 1.6 Nhãn nhận diện, bộ lọc và thư viện ảnh phía Chủ app & Quản trị viên
- Badge hiển thị: Bắt buộc nhãn **Báo cáo nhiệm vụ app**, kèm tên nhiệm vụ và ngày báo cáo.
- Bộ lọc chủ app (`OwnerAppDetailScreen`): Phân loại rõ ràng "Tất cả", "Báo cáo thường", "Báo cáo nhiệm vụ app". Thư viện hiển thị đủ cả 3 ảnh của từng báo cáo với thumbnail chính xác và mở phóng to toàn màn hình với đúng ID attachment.
- Quản trị viên (`AdminTesterReportsQueueScreen`): hiển thị toàn bộ attachments (tối đa 3 ảnh), mở đúng ảnh được chọn và gắn nhãn nhiệm vụ + ngày báo cáo rõ ràng.
- Media Access Gateway (`media-gateway-handler.mjs` & `supabase-authority-adapter.mjs`): Mở rộng hợp đồng tiếp nhận tham số `attachment_id`, kiểm tra grant token và trả về đúng storage path của attachment được yêu cầu. Fallback giữ nguyên đường dẫn ảnh chính cho legacy.

---

## 2. Tệp Database Migration

- **Tên file migration mới**: `supabase/migrations/074_app_mission_window_and_multi_attachments.sql`
- **Thời điểm tạo**: Sau migration cao nhất hiện có (073). Không sửa đổi lịch sử các migration trước đó.
- **Nội dung chính**:
  1. Thêm cột `report_date` và partial unique indexes trên `tester_reports`.
  2. Tạo bảng `tester_report_attachments` với RLS và kiểm tra vị trí `0..2`.
  3. Hàm cốt lõi `_submit_tester_report_core` quản lý locking ngữ cảnh theo ngày và bộ ảnh nguyên tử.
  4. Hàm RPC `submit_tester_report_v3` phục vụ client đa ảnh mới.
  5. Cập nhật hàm RPC `submit_tester_report_v2` và `submit_tester_report` kế thừa core an toàn tương thích ngược 100%.
  6. Nâng cấp các API đọc: `get_campaign_detail`, `get_campaign_reports_for_owner`, `admin_get_tester_reports_queue`.
  7. Nâng cấp `request_report_media_access` và `verify_media_access_grant_internal` hỗ trợ `p_attachment_id`.

---

## 3. Lệnh và Kết Quả Kiểm Thử (Tests, Commands, Counts, Logs)

### 3.1 Bộ kiểm thử độc lập (Independent Test Suite qua `independent.init.gradle`)
Lệnh thực thi:
`& "audit\app-mission-report-2026-11-02\run-gradle-logged.ps1" -LogName "indep-all-6-classes-round8-test2" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest", "--tests", "com.ntt.crosstest.audit.MissionWindowPersistenceIndependentTest", "--tests", "com.ntt.crosstest.work.MissionWindowWorkerIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowManagerIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowDraftIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowMediaIndependentTest", "--tests", "com.ntt.crosstest.audit.MissionWindowDayRolloverIndependentTest")`
- `MissionWindowDayRolloverIndependentTest`: **1/1 PASS** (xử lý đổi ngày, giải phóng lease ảnh cũ, giữ nháp ngày cũ, ngăn receipt cũ đè UI ngày mới, không chuyển giao exemption cũ, và vô hiệu hóa timer hồi phục từ boot khác)
- `MissionWindowDraftIndependentTest`: **4/4 PASS** (bao gồm unsubmitted draft across reopen, multi-day retention, và dHash equality allowance on resubmit)
- `MissionWindowManagerIndependentTest`: **8/8 PASS** (bao gồm atomic capacity check, photo-only newer draft, dynamic actual full JSON metadata budgeting, và cô lập trạng thái hàng đợi theo ngày)
- `MissionWindowMediaIndependentTest`: **4/4 PASS** (bao gồm secondary thumbnail fallback, gateway request exact attachment, legacy synthetic attachment, và fullscreen viewer contract)
- `MissionWindowPersistenceIndependentTest`: **5/5 PASS**
- `MissionWindowWorkerIndependentTest`: **2/2 PASS**
- **Tổng cộng: 24/24 tests PASS (0 failures, 0 errors, 0 skipped)**. Snapshot: `indep-all-6-classes-round8-test2-20261102-114945-248`.

### 3.2 Bộ kiểm thử tổng hợp toàn bộ Android (Combined All-Suites Test Run)
Lệnh thực thi:
`& "audit\app-mission-report-2026-11-02\run-gradle-logged.ps1" -LogName "full-android-round8-all" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest")`
- **Tổng cộng: 542 tests, 0 failures, 0 errors, 0 skipped** (Bao gồm toàn bộ standard suite, audit suites cũ, và 24 independent tests mới qua 6 lớp kiểm thử).
- Snapshot JUnit XML: `full-android-round8-all-20261102-115056-034`.

### 3.3 Build hoàn chỉnh ứng dụng Android
Lệnh thực thi:
`& "audit\app-mission-report-2026-11-02\run-gradle-logged.ps1" -LogName "assemble-debug-round8-final" -GradleArguments @(":app:assembleDebug")`
- Kết quả: **BUILD SUCCESSFUL in 13s**.
- Vị trí APK: `app/build/outputs/apk/debug/app-debug.apk`.

### 3.4 Kiểm thử Backend & Database
- `audit/app-mission-report-2026-11-02/root-daily-coexistence-probe.mjs`:
  ```text
  PASS independent daily coexistence: app_test -> mission
  PASS independent daily coexistence: mission -> app_test
  ```
- `backend/media-gateway`: `npm test` -> **13/13 PASS**.
- `backend/storage-cleanup`: `npm test` -> **8/8 PASS**.
- `supabase/tests`: `npm test` -> **PASS 100%** (toàn bộ baseline regressions, admin expansion và mission window).

### 3.5 Bổ sung xác nhận nguyên tử (Atomic Acknowledgment Verification)
Lệnh thực thi:
`& "audit\app-mission-report-2026-11-02\run-gradle-logged.ps1" -LogName "full-android-atomic-ack-final" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest")`
- Khóa monitor `synchronized(MissionReportDraftStore)` bao bọc toàn bộ chu trình tải bản nháp, so sánh khớp snapshot (`comment`, `rating`, danh sách thứ tự uploadId) và xóa có điều kiện trong `ReportSubmitManager.acknowledgeMatchingDraft` (đồng thời bổ sung API store-level `clearDraftIfMatching`). Đảm bảo đồng bộ nguyên tử 100% với các thao tác `saveDraft` của UI trên Main thread.
- **Toàn bộ bộ kiểm thử Android**: **542 tests, 0 failures, 0 errors, 0 skipped** (Bao gồm đầy đủ 24 root independent tests qua 6 lớp kiểm thử, snapshot: `full-android-atomic-ack-final-20261102-115451-556`).
- **Build APK Android (`:app:assembleDebug`)**: **BUILD SUCCESSFUL in 14s** (log: `assemble-debug-atomic-ack-final.log`).

### 3.6 Sửa lỗi mục tiêu thử lại chính xác theo ngày (Exact Daily Scope Retry)
Lệnh thực thi:
`& "audit\app-mission-report-2026-11-02\run-gradle-logged.ps1" -LogName "full-android-exact-retry-final" -GradleArguments @("-I", "audit/app-mission-report-2026-11-02/independent.init.gradle", ":app:testDebugUnitTest")`
- `MissionReportViewModel.retrySubmit()` thực hiện kiểm tra đổi ngày (`checkAndRefreshDay`) và truyền chính xác khóa phạm vi theo ngày (`scopedKey = userId:campaignId:mission:missionId:boundDate`) sang `ReportSubmitManager.retry`.
- `ReportSubmitManager.retry` định vị chính xác yêu cầu từ `scopedKey`, không chọn nhầm bản ghi cũ giữa các ngày, và chuẩn hóa việc tính khóa hàng đợi đưa lại qua `effectiveReportDate(report)`.
- **Toàn bộ bộ kiểm thử Android**: **542 tests, 0 failures, 0 errors, 0 skipped** (Snapshot: `full-android-exact-retry-final-20261102-120057-492`, log: `full-android-exact-retry-final.log`).
- **Build APK Android (`:app:assembleDebug`)**: **BUILD SUCCESSFUL in 35s** (log: `assemble-debug-exact-retry-final.log`, vị trí: `app/build/outputs/apk/debug/app-debug.apk`).

---

## 4. Giới Hạn Thực Tế Còn Lại (Remaining Material Limits)

1. **Môi trường PostgreSQL đa kết nối**:
   - Tất cả các bài kiểm thử database cục bộ chạy trên thư viện `@electric-sql/pglite` (WebAssembly đơn tiến trình). Các cơ chế locking `pg_advisory_xact_lock` và ràng buộc unique đã được xác minh đúng về mặt cú pháp và ngữ nghĩa, nhưng không thể kiểm thử race condition đa tiến trình đồng thời thực tế trên môi trường phân tán nếu không có cụm PostgreSQL trực tiếp.
2. **Kiểm thử môi trường người dùng cuối**:
   - Chưa đẩy migration 074 lên server production (tuân thủ nghiêm ngặt nguyên tắc an toàn).
   - Quyền truy vấn launcher intent `packageManager.getLaunchIntentForPackage` và thao tác mở Google Play Store thật cần được xác nhận trên thiết bị vật lý trong quy trình QA tiếp theo.
ROOT FINAL VERIFICATION: Đã đối chiếu trực tiếp XML542/75 không failure/error/skip, APK cuối SHA D8E3F369F2BD2513E2911F5FBBB33032FBAAEA0B2E37A373220BAAFA32F4EE15 và metadata818+166 assistant messages đều đúng Google/3.8Flash/medium. Xem ROOT_CHECKPOINT_2026-11-02.md và root-final-verification.json. Các phiên CLI đã kết thúc.
