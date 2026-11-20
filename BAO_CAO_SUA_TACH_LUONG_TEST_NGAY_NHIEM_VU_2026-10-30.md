# Sửa tách luồng test hằng ngày và nhiệm vụ app

## Mã đã sửa

- Android không suy ra nhiệm vụ từ hasJoined khi mở màn test thường. Báo cáo có sẵn khác loại được báo xung đột, không đổi mục đích của màn hình.
- SubmitRequest lưu submissionContext; PendingReport lưu context vào đĩa. Bản JSON cũ thiếu trường này giữ giá trị null để nhận diện yêu cầu legacy.
- Repository gọi submit_tester_report_v2 với loại bài rõ ràng. Không fallback sang chữ ký cũ khi thiếu v2, kể cả caller trước đây cho phép fallback.
- Migration 064_explicit_report_context.sql cung cấp v2, core nội bộ không tự gán mission, wrapper tương thích API cũ và reconcile_legacy_report. Core bị thu hồi quyền gọi của client.
- API cũ không tự gán nhiệm vụ khi thiếu ID; bài mission mới qua API cũ bị yêu cầu cập nhật ứng dụng. Receipt đã commit được bảo toàn.
- Loại app_test bắt buộc không có missionId, loại mission bắt buộc có ID. Theo bất biến này, fingerprint hiện có chứa missionId/null đã phân biệt hai loại; không đổi fingerprint/receipt lịch sử. V2 không dùng payload khác dưới cùng request ID để sửa loại.
- Pending/rejected/approved cùng ngày khác loại hoặc khác mission bị chặn bằng REPORT_CONTEXT_CONFLICT. Quy tắc một báo cáo theo app/ngày vẫn giữ nguyên.
- Đã tham gia mission full vẫn nộp được. Trạng thái tạm dừng, hết Credit, hết hạn và bị xóa có mã lỗi riêng. MISSION_NOT_OPEN cũ được hiển thị trung tính.

## Hàng đợi cũ

Worker reconcile trước khi tải ảnh hoặc gửi payload cũ. Có receipt thì dùng lại và lưu completion local. Nếu chưa có receipt/report, server tạo tombstone theo user/request ID để chặn bài cũ commit muộn; người dùng được yêu cầu đóng thông báo, chọn đúng luồng và nộp lại từ biểu mẫu với ID mới. Không tự chọn thay người dùng.

Reconcile và gửi bài đều khóa transaction theo user/request ID. Khi đã có report legacy nhưng chưa có ledger, giữ dữ liệu và báo cần kiểm tra; không tự đổi loại hoặc cấp thưởng. Ảnh/nội dung pending được giữ để phục hồi. Khi offline hoặc reconcile không thành công, không tự gửi bài mới.

## Kiểm chứng

- `npm test`: toàn bộ chuỗi runner database qua; log tại audit/report-context-fix-2026-10-30/database-tests.log. Sau đó bổ sung guard loại/mission của receipt legacy trên endpoint v2 và chạy lại riêng toàn bộ ma trận context.
- APK release đã xác minh chữ ký; SHA-256: `8EB3A2FD3AA2E43834D3032287791F1428236FCC808C5A3F8BCB2BB57A1D2922`.

- Android: 307 test/40 suites qua, không lỗi. Summary: audit/report-context-fix-2026-10-30/android-tests.json.
- Bổ sung test mở màn test thường khi đã join mission và chưa có báo cáo: không thành mission; test legacy reconcile không upload lại và không nộp lại receipt cũ.
- Các test kỳ vọng fallback chữ ký cũ đã được đổi theo hợp đồng mới: thiếu v2 phải fail closed, giữ context và ID, một lần gọi; vẫn kiểm tra cancellation, lỗi nghiệp vụ và lỗi server không làm mất ngữ cảnh.
- Một lượt chạy song song R8 có timeout ở test khởi tạo ViewModel; lượt chạy lại toàn bộ trên mã cuối qua 307/307, không bỏ assertion hay tăng timeout.
- Test database mới: tám tổ hợp pgcrypto public/extensions × member/admin × app_test/mission qua. Kiểm tra 10 Credit lần đầu, receipt replay, xung đột context, ID mismatch, ACL core, reconcile receipt/tombstone, migration rerun, nhiệm vụ full và test thường khi nhiệm vụ stopped_no_credits.
- assembleDebug và assembleRelease thành công. APK: app/build/outputs/apk/release/app-release.apk và app/build/outputs/apk/debug/app-debug.apk.
- Giữ version 1.0.5/build 25 theo cấu hình hiện tại; cần build number mới nếu phát hành lên kênh đã nhận build 25.

## Triển khai và phần còn cần môi trường thật

1. Trên đúng project: chạy 063 nếu chưa áp, rồi 064. Không chạy lại baseline 062. File generator chỉ phục vụ soạn migration; SQL 064 là artifact triển khai.
2. Cài APK mới sau khi backend có v2. Client cũ có thể bị yêu cầu cập nhật khi nộp nhiệm vụ mới, nhằm ngăn tiếp tục gửi nhầm.
3. Kiểm tra thực tế test thường và mission trên cùng tài khoản, đối chiếu report/receipt/checkin/credit_logs.
4. Dùng supabase/operations/report-context-audit.sql để xuất ứng viên gắn nhầm. Nhãn mission không đủ chứng minh lỗi; cần xác minh từng trường hợp trước khi sửa dữ liệu hoặc điều chỉnh Credit.

Chưa triển khai production, chưa có thiết bị kết nối để nghiệm thu. Chưa có PostgreSQL nhiều kết nối để kiểm chứng race reconcile/submit trên môi trường thật; test PGlite chứng minh hành vi tuần tự và khóa đã được đưa vào mã, không thay thế kiểm thử cạnh tranh thực tế. Chưa sửa lịch sử báo cáo/Credit production vì chưa có dữ liệu đối soát. Các phần này cần hoàn thành trước khi chốt khôi phục hệ thống thực tế.
