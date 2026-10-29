# Kiểm tra và sửa luồng gửi báo cáo — 29/10/2026

Người dùng xác nhận: một số máy **vẫn mở TestCheo ở màn hình gửi** nhưng rất lâu mới được ghi nhận đã gửi. Chưa có log từ máy bị ảnh hưởng, vì vậy các lỗi source dưới đây đã tái hiện được cục bộ nhưng chưa chứng minh là toàn bộ nguyên nhân trên thiết bị thật.

## Các điểm đã sửa

### 1. Thumbnail tùy chọn giữ báo cáo ở trạng thái chờ

Trước sửa, worker upload ảnh chính xong rồi đợi upload/resolve URL thumbnail, sau đó mới gọi RPC nộp báo cáo. Nhánh thumbnail được gọi là non-fatal nhưng không có deadline riêng; chỉ bỏ qua khi SDK trả lỗi. Thumbnail chậm có thể giữ lại cả báo cáo.

Sau sửa, `ReportUploadWorker` dùng `withTimeoutOrNull(5_000)` cho toàn bộ bước thumbnail. Hết ngân sách thì tiếp tục nộp bằng ảnh chính. Đây là giới hạn **thumbnail**, không phải cam kết toàn bộ bài nộp xong trong 5 giây.

### 2. Chưa lưu kết quả upload ảnh chính trước bước thumbnail

Trước sửa, full URL/hash chỉ được lưu sau thumbnail. Hủy tác vụ trong lúc chờ thumbnail khiến lần retry chưa biết ảnh chính đã upload xong, phải thực hiện lại upload/recovery.

Sau sửa, `PendingReportStore.markUploaded` lưu bền vững URL/hash ngay khi ảnh chính hoàn tất, trước thumbnail. Retry dùng lại kết quả này. Khi coroutine bị hủy, `FirebaseStorageHelper` gọi `UploadTask.cancel()`; cancellation từ parent vẫn thoát, không được coi là timeout tùy chọn rồi tiếp tục join/RPC.

### 3. Tương thích RPC trước migration 045

Client có thumbnail gửi tham số `p_thumbnail_url`, trong khi backend cũ chỉ có chữ ký idempotent 6 tham số. Trước sửa, worker yêu cầu idempotency nên trả `report_idempotency_unavailable` ngay khi chữ ký mới không tồn tại.

Sau sửa, thử DTO 7 → 6 tham số khi nhận đúng missing signature, giữ nguyên `p_client_submit_id`, URL ảnh chính, hash, nội dung và rating. Chỉ cho nhánh legacy 5 tham số khi caller không bắt buộc idempotency. DTO legacy đã bỏ tham số thumbnail.

Lỗi PostgREST được nhận diện bằng HTTP404 và code `PGRST202`; 403/500/503 và lỗi nghiệp vụ không được coi là thiếu chữ ký. Serializer HTTP có thể bỏ các giá trị mặc định; “7/6/5” ở đây là hợp đồng hàm/DTO, không phải số field JSON luôn hiện trên dây.

## Kiểm chứng độc lập của Codex

Antigravity thực hiện sửa source; Codex review và tự chạy lại sau khi Antigravity dừng Gradle. Các file source giữ nguyên SHA256 trong suốt lượt verify.

| Kiểm tra | Kết quả |
|---|---:|
| Toàn bộ unit test Android | **198/198**, 27 suites, 0 fail/error/skipped |
| ReportRpcCompatibilityTest | 5/5 |
| ReportSubmissionLatencyTest | **3/3** |
| ReportRpcFallbackAuditTest | 6/6 |
| ReportUploadWorkerTest | 7/7 |
| ReportSubmitManagerIntegrationTest | 2/2 |
| ReportSubmitPolicyTest | 10/10 |
| ReportUrlRetryTest | 12/12 |
| EgressThumbnailPipelineAuditTest | 1/1 |
| assembleDebug | Thành công |
| lintDebug, đọc XML | 0 Error/Fatal; còn 430 Warning và 2 Hint |
| git diff --check | Không có lỗi whitespace |

Các hàng test riêng là tập con của 198, không cộng thêm vào tổng.

Trước sửa: 2/2 ca latency thất bại (thumbnail treo; full URL chưa bền vững). Ca tương thích backend 6 tham số thất bại với `report_idempotency_unavailable`. Fixture thành công của kiểm thử được bổ sung session giả; assertions về payload/idempotency được giữ nguyên. Ca thứ ba của latency xác nhận timeout bên ngoài không tiếp tục gọi join/RPC.

Lệnh verify:

```text
.\gradlew.bat -I .tmp/report-submit-2026-10-29/verify.init.gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon
```

Bằng chứng: `.tmp/report-submit-2026-10-29/verified-gradle.log`, `verified-results/`, `verified-test-summary.json`, `verified-test-suites.json`, `verified-lint.xml`, `verified-source-sha256.csv`, `final-verifier-sha256.csv`; baseline ở `before.xml` và `latency-before.xml`.

## Phần chưa xác minh trên thực tế

- ADB vẫn không có thiết bị kết nối; chưa đo thời gian từng bước hoặc p50/p95 trên máy gửi chậm. Không khẳng định đã hết mọi độ trễ thiết bị/mạng.
- Upload ảnh chính, resolve URL, lịch WorkManager, join và phản hồi RPC vẫn có thời gian chờ riêng. Không tự thay budget upload chính khi chưa có số đo từ ca thật.
- GET schema Supabase bằng cấu hình `local.properties` tại thời điểm kiểm tra trả **HTTP401 Invalid API key**. Chưa xác định cấu hình này có trùng APK tester đang dùng. Không in, đổi hoặc xoay khóa; chưa xác minh schema production và chưa gửi bài nộp thật.
- Bản sửa hiện ở worktree, chưa commit/push/phát hành hay deploy migration/rules/cloud. Build debug chỉ xác nhận biên dịch, chưa xác nhận đăng nhập bằng cấu hình backend hiện tại.

Để xác nhận đúng nguyên nhân trên máy thật, cần một thiết bị bị chậm có USB debugging và log `ReportSubmitManager`/`ReportUploadWorker`/`FirebaseStorageHelper`: tách thời gian chờ worker, upload full, URL, thumbnail, join, RPC và backoff. Không cần cung cấp mật khẩu hoặc token đăng nhập.
