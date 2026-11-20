# Báo cáo xử lý lỗi thiếu context RPC v2 (30/10/2026)

## 1. Nguyên nhân gốc rễ (Root Cause)

- **Lỗi serializer DTO (P0):** `SubmitTesterReportParams.submissionContext` trước đây khai báo có giá trị mặc định `= "app_test"`. Khi nộp test thường (daily test), caller truyền `"app_test"` trùng với default; `kotlinx.serialization` mặc định của Supabase PostgREST client (`encodeDefaults = false`) đã tự động lược bỏ trường `p_submission_context` khỏi payload JSON trên HTTP wire.
- **Phía server PostgREST:** RPC `public.submit_tester_report_v2` (migration 064) khai báo tham số `p_submission_context text` bắt buộc (không có default). Khi thiếu key này trên HTTP body, PostgREST không tìm thấy chữ ký hàm khớp trong schema cache và trả về HTTP 404 với mã `PGRST202`.
- **Hệ quả trên client:** `CampaignRepository` bắt `PGRST202` và bọc thành `IllegalStateException("server_update_required")`, dẫn đến UI hiển thị thông báo “Hàm xử lý chưa được cài đặt trên Supabase hoặc đang làm mới cache. Vui lòng kiểm tra lại SQL migration.” như ảnh người dùng, và báo cáo test thường không được gửi.

## 2. Các file đã chỉnh sửa và nội dung thực hiện

1. **`app/src/main/java/com/ntt/crosstest/data/Models.kt`:**
   - Xóa bỏ giá trị mặc định của `submissionContext` trong `SubmitTesterReportParams`: chuyển thành `val submissionContext: String`.
   - Vì thuộc tính không còn default value, Kotlinx Serialization bắt buộc đưa `"p_submission_context"` vào HTTP payload trong mọi trường hợp (cả `app_test` và `mission`).

2. **`app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`:**
   - Bổ sung log chẩn đoán có cấu trúc khi gặp `PGRST202` missing/mismatched RPC: ghi nhận tên hàm (`submit_tester_report_v2`), mã PostgREST, HTTP status, request ID và danh sách tên các keys thực tế có trong payload (trích xuất từ `.jsonObject.keys.sorted()`). Tuyệt đối không log raw values (không token, không URL ảnh, không nội dung test).
   - Tiếp tục bọc thành `IllegalStateException("server_update_required", e)` giữ nguyên `e` làm `cause` để bảo toàn chuỗi lỗi gốc.

3. **`app/src/main/java/com/ntt/crosstest/data/ReportSubmissionFailure.kt`:**
   - Bổ sung trường `val originalCode: String?` và `val httpStatus: Int?`.
   - Bổ sung helper `causes()`, `originalCodeFor()`, `httpStatusFor()` duyệt chuỗi cause chain để giữ mã lỗi gốc PostgREST (`PGRST202`, `P0001`,...) và HTTP status kể cả khi bị bọc qua exception trung gian.
   - Cập nhật `codeFor()` để nhận diện `PostgrestRestException` qua cause chain.

4. **`app/src/main/java/com/ntt/crosstest/work/ReportUploadWorker.kt`:**
   - Trích xuất `originalCode` và `httpStatus` từ cause chain của exception và ghi vào Worker failure `outputData` (`KEY_ORIGINAL_CODE`, `KEY_HTTP_STATUS`) cùng log chẩn đoán an toàn.

5. **`app/src/main/java/com/ntt/crosstest/data/ReportSubmitManager.kt`:**
   - Chuyển tiếp `originalCode` và `httpStatus` từ WorkManager `outputData` vào `ReportSubmissionFailure`.

6. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/AuthViewModel.kt`:**
   - Bổ sung ánh xạ trong `friendlyError` cho `ReportSubmissionFailure` có `originalCode == "PGRST202"` hoặc `code == "report_rpc_PGRST202"`.

7. **`app/src/test/java/com/ntt/crosstest/audit/ReportV2WirePayloadProbeTest.kt`:**
   - Chuyển đổi thành bộ test hồi quy hoàn chỉnh (6 test cases):
     - `inspectActualDailyAndMissionWirePayload`: assert `endpoints` gọi `/submit_tester_report_v2`, daily payload có `p_submission_context == "app_test"`, mission payload có `p_submission_context == "mission"` và `p_mission_id`.
     - `mockRejectsMissingSubmissionContextAndAcceptsWhenPresent`: kiểm tra mock từ chối HTTP 404 khi thiếu `p_submission_context`, chấp nhận khi có đủ context; kiểm tra `requestId` và `missionId`.
     - `wrappedPgrst202PreservesOriginalCodeAndMetadataInCauseChain`: kiểm tra bọc `server_update_required` giữ nguyên mã PostgREST `PGRST202`, HTTP 404 qua cause chain.
     - `businessP0001PreservesCodeAndIsNotRetryable`: kiểm tra lỗi nghiệp vụ `P0001` không retry và giữ nguyên mã.
     - `http503IsRetryableAndNotClassifiedAsMissingRpc`: kiểm tra lỗi 503 retry được và không bị nhầm thành `server_update_required`.
     - `cancellationPropagatesWithoutFallbackOrRetry`: kiểm tra coroutine cancellation lan truyền đúng, không fallback.

8. **`app/src/test/java/com/ntt/crosstest/data/ReportRpcFallbackAuditTest.kt`:**
   - Bổ sung assertion bắt buộc `p_submission_context == "app_test"` trên wire body của nhánh daily test.

---

## 3. Kết quả thực chạy kiểm thử (Verification Results)

- **Targeted Unit Tests:**
  - `ReportV2WirePayloadProbeTest`: **6/6 tests PASS**.
  - `ReportRpcFallbackAuditTest`: **6/6 tests PASS**.
- **Toàn bộ Android Unit Tests:**
  - **316/316 tests PASS** trên **42 test suites** (0 failures, 0 errors, 0 skipped).
  - Chi tiết lưu tại: `.tmp/codex-verify-gemini-android.log`.
- **Toàn bộ Database Tests (`npm test`):**
  - **18/18 test suites PASS 100%** (bao gồm baseline 062, ledger idempotency, context audit, mission hardening,...).
  - Chi tiết lưu tại: `.tmp/codex-verify-gemini-db.log`.
- **Build Release APK (`assembleRelease`):**
  - Trạng thái: **BUILD SUCCESSFUL** (exit code 0).
  - `versionName`: `1.0.5`.
  - `versionCode`: `25`.
  - Chữ ký: Đã xác thực APK Signature Scheme v2.
  - File APK: `.tmp/release/12Tester-1.0.5-25-rpc-v2-fixed.apk`.
  - **APK SHA-256:** `7C1419DAC56A97845A1666FB4B0581C868AF5668ECFB8575054783E11130BA2F`.

---

## 4. Xác nhận trạng thái Deployment & Giới hạn kiểm chứng

1. **Routing phía Production:**
   - Bằng chứng tại `audit/report-v2-wire-2026-10-30/live-routing-check.json` xác nhận endpoint `/rest/v1/rpc/submit_tester_report_v2` đã tồn tại trên dự án Supabase.
   - Khi gửi payload thiếu `p_submission_context`, server trả HTTP 404 `PGRST202`. Khi gửi payload có đủ `p_submission_context`, server chuyển tiếp đến tầng phân quyền và trả HTTP 401 cho anonymous caller (chứng minh chữ ký hàm v2 đã được PostgREST nhận diện đầy đủ).
   - Chạy lại migration 064 không sửa được việc client bỏ key. Bằng chứng chỉ xác nhận route trên project cấu hình nhận diện payload đủ context; chưa kiểm tra quyền authenticated, toàn bộ thân hàm hay project của APK đang cài trên điện thoại.

2. **Yêu cầu triển khai:**
   - Cần cài đặt bản APK mới (`version 1.0.5 build 25`, SHA-256 nêu trên) lên thiết bị của tester để khắc phục dứt điểm lỗi thiếu key khi gửi payload.

3. **Phần chưa xác minh thực tế:**
   - Chưa thực hiện authenticated submit thực tế bằng tài khoản người dùng trên điện thoại thật.
   - Không tuyên bố đã hết lỗi trên production ngoài thực tế cho tới khi người dùng cài APK mới và nộp báo cáo thành công trên thiết bị di động.
