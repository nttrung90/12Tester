# Kiểm tra lại lỗi không nộp được báo cáo — 29/10/2026

## Người dùng đã làm rõ triệu chứng

**Một số máy gửi báo cáo rất lâu mới được ghi nhận đã gửi**, không phải mọi bài đều bị từ chối. Ưu tiên đường chờ upload/retry; lỗi RPC bên dưới là regression riêng, chưa chứng minh là nguyên nhân độ trễ thực tế.

Người dùng xác nhận thêm: **vẫn mở TestCheo ở màn hình gửi**, không phải chỉ chậm sau khi chuyển app/tắt màn hình.

### Hai regression độ trễ đã tái hiện: 2/2 fail

`ReportSubmissionLatencyTest.kt`, đồng hồ ảo, không sleep/network:

- Full screenshot đã upload thành công nhưng optional thumbnail treo thì worker không gọi RPC sau 10 giây. `uploadReportThumbnail()` chờ Firebase task không có deadline tổng riêng; nhãn non-fatal chỉ xử lý sau khi SDK trả lỗi, không giới hạn thời gian chờ.
- Full URL/hash chỉ được persist **sau** thumbnail. Nếu coroutine bị hủy trong lúc thumbnail chậm, retry chưa biết full đã upload xong, có thể upload/recover full lại.

Yêu cầu bổ sung cho Antigravity: thumbnail có deadline ngắn (đề xuất 5 giây, dưới ngưỡng regression 10 giây), timeout chỉ bỏ thumbnail và tiếp tục nộp ảnh full. Persist full URL/hash trước khi chờ optional thumbnail. Cancel Firebase upload task khi coroutine bị hủy, bảo toàn cancellation do logout/WorkManager (không coi cancellation ngoài là timeout tùy chọn). Không để timeout thumbnail làm cả worker retry. Review deadline SDK upload full, URL/recovery, join/RPC và ghi rõ phần chưa đo mạng thật. Không tự đổi thông số retry rộng để che nguyên nhân; test task cancellation và stage timing cần thật.

Review bổ sung: `ReportSubmissionLatencyTest` có ca thứ 3 dùng deadline **bên ngoài** khi thumbnail đang chờ; không được bắt nhầm timeout này rồi gọi `join`. Dùng `withTimeoutOrNull` cho budget riêng, để timeout của parent thoát theo cancellation. Tổng verifier độc lập cuối cùng: 5 compatibility + 3 latency; hash ở `.tmp/report-submit-2026-10-29/final-verifier-sha256.csv`.

Fixture success của compatibility đã được Codex bổ sung session giả (giữ nguyên assertions); không cần làm nullable `uid()` của production để chữa fixture. Chỉ siết `isMissingSubmitRpc` theo HTTP404/PGRST202 thật; tránh thay helper missing-RPC chung của các luồng cache ngoài phạm vi báo cáo.

## Phát hiện từ source

- `SubmitTesterReportParams` gửi thêm `p_thumbnail_url` (migration 045), kể cả khi worker vẫn bắt buộc idempotency.
- `CampaignRepository.submitFeedback()` khi nhận missing RPC lập tức ném `report_idempotency_unavailable` nếu `requireIdempotency=true`. Chưa thử chữ ký **6 tham số có `p_client_submit_id`** đang tồn tại trước migration 045.
- `SubmitTesterReportLegacyParams` cũng chứa `p_thumbnail_url`, không còn đúng hợp đồng fallback 5 tham số ghi trong chú thích.
- Báo cáo E02–E05 hiện ghi migration 045 chỉ được thử trên PGlite, chưa apply production. Vì vậy app mới → backend cũ là một đường lỗi có cơ sở; chưa được coi là đã xác minh schema production.

## Regression độc lập

`app/src/test/java/com/ntt/crosstest/data/ReportRpcCompatibilityTest.kt`:

1. Backend từ chối tham số thumbnail bằng PGRST202 nhưng chấp nhận 6 tham số: phải nộp thành công, giữ nguyên client submit ID và mọi nội dung; chỉ bỏ thumbnail.
2. Không có RPC idempotent: vẫn fail closed, không hạ xuống RPC 5 tham số khi worker yêu cầu idempotency.
3. Lỗi nghiệp vụ (campaign_not_open): không gửi lại bằng chữ ký khác.

Codex bổ sung 2 ca review an toàn sau khi đọc nhánh fallback: HTTP503/PGRST002 chứa chữ `schema cache` và HTTP403 chứa tên hàm vẫn phải giữ nguyên lỗi, chỉ một request. `isMissingSubmitRpc` hiện dùng helper dò chuỗi quá rộng; không được đổi chữ ký cho các lỗi này. Tổng file compatibility hiện có 5 cases; 3 cases ban đầu giữ nguyên assertions.

## Yêu cầu sửa cho Antigravity

- Thử 7 → 6 tham số **chỉ khi xác định đúng thiếu chữ ký RPC**. Giữ nguyên `p_client_submit_id`, URL full, hash, nội dung và rating.
- Chỉ khi cả chữ ký idempotent không có mới trả lỗi cấu hình. Không bỏ idempotency, không tăng retry để che lỗi, không fallback với lỗi quyền/mạng/nghiệp vụ.
- Nếu giữ nhánh legacy 5 tham số, DTO phải đúng 5 tham số; nhánh này chỉ được dùng khi caller cho phép.
- Không sửa/giảm assertions của regression độc lập. Bổ sung kiểm thử 7 tham số hoạt động, lỗi HTTP500/cancellation không fallback, và legacy DTO.
- Chạy test tuần tự, không thay rules/quyền và không deploy/commit/push.

## Giới hạn kiểm tra thực tế

- ADB hiện không có thiết bị kết nối, chưa lấy log bài nộp lỗi thật.
- GET schema Supabase bằng cấu hình `local.properties` trả HTTP401 `Invalid API key` (không in hay lưu khóa). Chưa xác định cấu hình này có trùng APK tester đang dùng. Không thay khóa tự động.
- Cần phân biệt regression đã tái hiện cục bộ với nguyên nhân thực tế trên APK/backend production.
