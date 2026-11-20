# Kế hoạch sửa lỗi không nộp được báo cáo test

## Mục tiêu và tình trạng hiện tại

Khôi phục việc nộp báo cáo, cho phép thử lại bài đang lỗi mà không mất ảnh/nội dung và không cộng Credit trùng. Khi thất bại, người dùng nhận được hướng xử lý phù hợp và người vận hành có đủ thông tin xác định chặng lỗi.

Đã tái hiện cục bộ lỗi SQL 42883: RPC `submit_tester_report` chỉ đặt `search_path = public`, trong khi `pgcrypto.digest()` có thể nằm ở `extensions`. Đã chuẩn bị migration 063 và test hồi quy. Chưa có log thiết bị/production để khẳng định đây là nguyên nhân của ảnh người dùng; chưa triển khai database hay phát hành APK. Kế hoạch này không coi các bước đó là đã hoàn tất.

## 1. Xác nhận nguyên nhân trên đúng môi trường — ưu tiên P0

1. Xác định project Supabase mà phiên bản ứng dụng đang lỗi sử dụng và phiên bản/build của ứng dụng.
2. Chạy `supabase/operations/check-report-crypto-schema.sql` trong SQL Editor. Lưu kết quả schema của pgcrypto, chữ ký và search_path của RPC, khả năng tìm thấy digest với đường dẫn cũ/mới.
3. Thu log lần gửi lỗi theo thời gian, mã yêu cầu và chặng xử lý: session → upload ảnh → RPC → lưu receipt → cập nhật UI. Với APK hiện tại có thể tìm tag `TestCheoError`; không mặc định log chi tiết Worker có ở release vì trace đang giới hạn DEBUG.
4. Xác nhận mã 42883 kèm lỗi digest trong log RPC nếu có. Nếu schema không mắc lỗi này hoặc log chỉ ra lỗi khác, chuyển sang điều tra lỗi thực tế (storage, phiên đăng nhập, chữ ký RPC, quy tắc nghiệp vụ hoặc deserialize); không tuyên bố migration 063 giải quyết toàn bộ sự cố.

**Đầu ra:** bằng chứng lỗi thực tế hoặc kết luận rõ rằng mới xác nhận được điều kiện cấu hình, chưa đối chiếu được lần gửi trong ảnh.

## 2. Hoàn thiện và triển khai bản sửa database — ưu tiên P0

1. Kiểm tra RPC trên môi trường đích có đúng chữ ký 8 tham số mà migration 063 nhắm tới. Nếu khác, kiểm tra phiên bản schema và soạn bản sửa tương ứng; không chạy lại toàn bộ baseline 062 để chữa lỗi này.
2. Lưu định nghĩa, owner, ACL và cấu hình hiện tại của RPC để đối chiếu và phục hồi nếu cần. Xác nhận schema public/extensions không cho vai trò không tin cậy tạo hàm che khuất các hàm được dùng bởi SECURITY DEFINER.
3. Giữ migration `063_report_crypto_search_path.sql` giới hạn ở việc đặt search_path thành `public, extensions` và yêu cầu PostgREST reload schema. Không di chuyển extension, sửa receipt, đổi clientSubmitId hay sửa số dư Credit.
4. Chạy test ở bước 4 trên môi trường thử nghiệm, sau đó áp migration trên đúng project theo quy trình triển khai của dự án.
5. Kiểm tra lại search_path sau triển khai và thử lại một báo cáo đang lỗi. Đối chiếu report, receipt và Credit trước/sau.

**Tiêu chí đạt:** RPC tìm thấy digest, báo cáo được lưu thành công và thử lại không tạo tác dụng phụ trùng. Riêng lỗi SQL này có thể sửa phía server mà không cần APK mới.

**Phục hồi khi có regression:** dựa vào định nghĩa/cấu hình đã lưu để phục hồi đúng RPC; không xóa report/receipt hay chạy lại baseline. Việc quay về search_path cũ có thể làm lỗi digest xuất hiện lại, nên phải ghi nhận rõ tác động và tiếp tục khắc phục.

## 3. Làm rõ lỗi trên Android và giữ khả năng thử lại — ưu tiên P1

Các vị trí cần sửa/kiểm tra:

- `work/ReportUploadWorker.kt`: truyền mã lỗi ổn định, chặng lỗi và mã yêu cầu qua WorkManager; vẫn đọc được lỗi dạng chuỗi của work cũ. Ghi thông tin chẩn đoán tối thiểu, không đưa token, URL ảnh có token, nội dung báo cáo hay thông tin đăng nhập vào log.
- `data/ReportSubmitManager.kt`: giữ thông tin lỗi có cấu trúc khi đọc WorkInfo; retry phải giữ ID yêu cầu và URL ảnh đã upload, không tạo submission mới để né lỗi.
- `ui/viewmodels/AuthViewModel.kt` và resources Việt/Anh: ánh xạ lỗi cấu hình server thành thông báo dễ hiểu, ví dụ “Máy chủ chưa xử lý được báo cáo. Vui lòng thử lại sau.” Phân biệt với lỗi mạng, phiên đăng nhập và tải ảnh. Không đưa nguyên văn SQL/stack trace lên giao diện; không gộp mọi lỗi 42883 thành thiếu digest nếu không có bằng chứng.
- `ui/viewmodels/CampaignDetailViewModel.kt` và màn chi tiết: chỉ hiện thành công/cộng thưởng sau receipt thành công; khi thất bại vẫn giữ nháp và nút Thử lại. Nếu hộp thoại gợi ý app tiếp theo vẫn xuất hiện, phải thể hiện rõ báo cáo trước chưa gửi xong, tránh hiểu nhầm là đã hoàn tất.

**Tiêu chí đạt:** lỗi có hướng xử lý rõ, truy được chặng lỗi qua mã yêu cầu, không mất nháp và không đổi danh tính bài nộp khi retry. Không thêm vòng tự retry vô hạn đối với lỗi cấu hình server.

## 4. Kiểm thử bắt buộc

### Database

Chạy từ `supabase/tests`: `npm run test:report-schema`.

Test đã có phải tiếp tục chứng minh:

- Trước sửa: pgcrypto ở extensions gây lỗi 42883 tại digest.
- Sau sửa: pgcrypto ở extensions hoặc public đều nộp được.
- Admin test app của người khác nộp được.
- Replay cùng ID/payload trả receipt cũ, không tăng Credit lần hai và chỉ có một report.
- Đổi payload nhưng giữ ID bị từ chối.
- Migration chạy lại không gây lỗi.

Bổ sung trước nghiệm thu toàn luồng: tester thường, báo cáo nhiệm vụ, cập nhật pending và nộp lại rejected với ảnh hợp lệ. Kiểm tra ACL RPC không đổi. Chạy các suite nghiệp vụ liên quan; nếu runner cũ tham chiếu file migration đã chuyển vào archive thì sửa đường dẫn fixture đúng lịch sử, không bỏ assertion để làm test xanh.

### Android và thiết bị

- Worker nhận lỗi RPC → FAILED, truyền đúng mã/chặng; lỗi cấu hình không tự retry như lỗi mạng.
- Thử lại sau sửa backend dùng lại request ID và ảnh đã upload; gửi thành công và không cộng thưởng trùng.
- Mất mạng sau khi server commit → retry nhận receipt cũ.
- Đóng/mở app khi đang gửi, đổi tài khoản và bấm Thử lại nhiều lần không gửi nhầm người hoặc mất nháp.
- Thông báo Việt/Anh đúng, không lộ dữ liệu nhạy cảm.
- Chạy test Worker/Manager và test ánh xạ lỗi tương ứng với phần Android đã sửa; build APK thử nghiệm nếu có sửa Android.
- Trên điện thoại tái hiện thao tác trong ảnh với Pool Training hoặc campaign thử nghiệm tương đương: chọn ảnh → gửi → theo dõi pending/failed/success → kiểm tra report phía chủ app và Credit phía tester.

## 5. Thứ tự bàn giao và điều kiện hoàn tất

1. Bàn giao bằng chứng xác nhận môi trường và bản sửa backend đã kiểm thử.
2. Triển khai backend, kiểm tra báo cáo cũ qua nút Thử lại; ghi rõ kết quả thực tế.
3. Hoàn thiện thông báo/chẩn đoán Android, kiểm thử và phát hành APK nếu có thay đổi client.
4. Theo dõi log sau triển khai để phát hiện lỗi digest còn lại hoặc lỗi khác bị che trước đó. Không dùng việc hết lỗi digest làm bằng chứng mọi loại báo cáo đều thành công.

Chỉ chốt hoàn tất khi đã nộp thành công trên môi trường đang lỗi, xác minh retry không cộng trùng, nháp được bảo toàn và thông báo thất bại có thể chẩn đoán. Nếu mới kiểm thử local, báo cáo rõ “đã sửa và kiểm thử local, chưa xác nhận production”.
