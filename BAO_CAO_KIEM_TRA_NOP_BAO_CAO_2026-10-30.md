# Kiểm tra lỗi nộp báo cáo ngày 30/10/2026

## Kết quả

Đã tái hiện lỗi backend trong mã hiện tại: `submit_tester_report` dùng `digest()` để tính fingerprint nhưng chỉ có `search_path = public`. Khi pgcrypto được cài trong `extensions`, PostgreSQL trả `42883: function digest(text, unknown) does not exist` trước khi ghi báo cáo. Migration 062 có `CREATE EXTENSION IF NOT EXISTS ... WITH SCHEMA public`, nhưng câu này không chuyển extension đã tồn tại sang public.

Đây là lỗi đã xác nhận bằng database cục bộ, **chưa xác nhận là lỗi thực tế của điện thoại trong ảnh**. Chưa có log thiết bị hoặc truy cập database production. ADB tại môi trường này không khởi động được daemon.

## Đối chiếu luồng

1. ReportSubmitManager lưu yêu cầu, enqueue WorkManager.
2. ReportUploadWorker xác minh phiên/tài khoản, upload ảnh Firebase, lưu URL rồi gọi CampaignRepository.submitFeedback.
3. Repository gọi RPC submit_tester_report với ID yêu cầu để chống nộp trùng.
4. RPC tính fingerprint bằng digest ngay sau xác minh auth. Lỗi phân giải digest khiến giao dịch dừng; thử lại không khắc phục cấu hình schema.
5. Worker chuyển message lỗi về UI. friendlyError trong AuthViewModel không có ánh xạ lỗi digest, trả error_generic: “Đã có lỗi xảy ra.” Thông báo trong ảnh vì vậy chưa đủ phân biệt lỗi SQL, storage hay lỗi khác.

## Bản sửa và kiểm chứng

- Thêm migration `supabase/migrations/063_report_crypto_search_path.sql`: đặt search_path của đúng RPC thành `public, extensions`. Không thay thân hàm, quyền hay dữ liệu/Credit; không sửa migration lịch sử.
- Thêm test `supabase/tests/run-report-extension-schema-test.mjs` và lệnh `npm run test:report-schema` trong supabase/tests.
- Test tái hiện lỗi 42883 trước sửa khi pgcrypto trong extensions.
- Sau sửa: cả pgcrypto trong extensions và public đều gửi thành công bằng tài khoản admin test app của người khác; replay giữ nguyên receipt/Credit, chỉ một report; payload khác cùng ID bị từ chối; migration chạy lại được.
- Chỉ chạy test database cho thay đổi SQL này. Không build APK, không triển khai production.

## Xác nhận tại hệ thống đang lỗi

Chạy file chỉ đọc `supabase/operations/check-report-crypto-schema.sql` trong SQL Editor của dự án đang dùng. Nếu pgcrypto nằm ở extensions, RPC chỉ có public, digest_visible_with_old_path là NULL và digest_visible_with_fixed_path có giá trị thì cấu hình đang mắc lỗi đã tái hiện. Đối chiếu log RPC/Android với mã 42883 và thông báo digest để xác nhận lần gửi cụ thể.

Áp migration 063 vào database sau khi xác nhận đúng dự án, rồi bấm Thử lại cho báo cáo cũ. Không cần đổi ID yêu cầu, xóa hàng đợi hay cài APK mới để khắc phục lỗi SQL này. Nếu log chỉ ra lỗi khác, cần điều tra theo lỗi đó; không coi bản sửa local là bằng chứng ứng dụng production đã hoạt động trở lại.
