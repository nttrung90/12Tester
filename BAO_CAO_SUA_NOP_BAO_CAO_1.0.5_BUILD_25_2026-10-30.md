# Sửa luồng nộp báo cáo — phiên bản 1.0.5, build 25

## Thay đổi

- Backend: migration `supabase/migrations/063_report_crypto_search_path.sql` cho RPC `submit_tester_report` tìm thấy pgcrypto ở public hoặc extensions. Giữ nguyên thân hàm, owner, quyền gọi và dữ liệu nghiệp vụ.
- Android: thêm `ReportSubmissionFailure`, truyền mã lỗi, chặng xử lý và request ID qua WorkManager. Manager đọc metadata mới đồng thời hỗ trợ work cũ chỉ có message.
- Lỗi thiếu digest được phân loại là lỗi cấu hình máy chủ, không tự retry như lỗi mạng. Nút Thử lại vẫn cho phép gửi cùng yêu cầu sau khi backend được sửa; ảnh đã upload được tái sử dụng.
- Thông báo Việt/Anh riêng cho lỗi cấu hình server và tải ảnh. Hộp thoại app tiếp theo hiện rõ báo cáo trước chưa gửi thành công. Lỗi nghiệp vụ và lỗi mạng vẫn giữ ánh xạ phù hợp.
- Log lỗi terminal của Worker có mã yêu cầu/chặng/mã lỗi trên release, không ghi raw server payload hay URL ảnh. UI xử lý lỗi có cấu trúc cũng chỉ log metadata đó.
- `app/build.gradle.kts`: versionName `1.0.5`, versionCode `25`.

## APK đã tạo

- Release: `app/build/outputs/apk/release/app-release.apk`.
- Debug: `app/build/outputs/apk/debug/app-debug.apk`.
- Lệnh `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`: BUILD SUCCESSFUL.
- Đã đọc manifest APK release bằng aapt: `com.ntt.crosstest`, versionName `1.0.5`, versionCode `25`; apksigner verify thành công, chữ ký v2.
- SHA-256 APK release: `B279B152791B9911430F5CD470DE18B9DCCE6AA3897419213D2777871B77E270`.

## Kiểm chứng

- `npm test` trong supabase/tests: PASS toàn bộ chuỗi runner, bao gồm baseline, nhiệm vụ, ledger/idempotency và test 063.
- Test 063 chạy ma trận pgcrypto ở extensions/public × admin/member. Tái hiện 42883 trước sửa; sau sửa gửi thành công, replay không cộng Credit trùng, chỉ một report, payload khác bị từ chối. Migration chạy lại được; owner/ACL/thân hàm giữ nguyên.
- `:app:testDebugUnitTest`: 304 test, 40 suites, 0 failures, 0 errors. Chi tiết và XML các suite liên quan nằm tại `audit/report-submit-fix-2026-10-30/`.
- Test Worker mới mô phỏng lỗi digest ở chặng RPC: FAILED ngay, giữ ID/nội dung/URL ảnh; thử lại thành công mà không upload ảnh lần hai.
- Test thông báo kiểm tra cả Việt/Anh, tương thích message cũ, phân biệt lỗi khác của PostgreSQL với thiếu digest và giữ ánh xạ lỗi nghiệp vụ/mạng.
- Lượt chạy ban đầu có hai timeout trong ReportOverlapTest. Đã sửa fixture để bơm main looper khi chờ ViewModel và dùng cổng persist xác định thời điểm admission bắt đầu trước khi hủy caller. Không tăng timeout hoặc bỏ assertion; lượt toàn bộ sau sửa qua 304/304.

## Phần cần thực hiện trên môi trường đích

Chưa áp migration lên production: công cụ hiện có không có kết nối quản trị Supabase/SQL; local.properties chỉ cung cấp URL và anon key cho client. ADB đã chạy được nhưng danh sách thiết bị rỗng, nên chưa xác minh thao tác trên điện thoại.

1. Chạy `supabase/operations/check-report-crypto-schema.sql` trên đúng project, đối chiếu lỗi digest/42883 trong log lần gửi.
2. Lưu cấu hình RPC hiện tại rồi áp migration 063 nếu chữ ký RPC phù hợp. Không chạy lại baseline 062 để chữa lỗi này.
3. Thử lại bài đang lỗi trên thiết bị, xác minh report/receipt và Credit trước/sau. Không xóa hàng đợi hay đổi clientSubmitId.
4. Nếu log là lỗi khác, tiếp tục điều tra theo mã lỗi/chặng xử lý mới.

Bản sửa mã nguồn và kiểm thử cục bộ không đồng nghĩa ứng dụng production đã được khôi phục.
