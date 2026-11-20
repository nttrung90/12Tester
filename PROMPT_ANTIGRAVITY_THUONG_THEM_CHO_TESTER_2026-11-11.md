# Triển khai và sửa hoàn chỉnh: Thưởng thêm cho tester

Bạn đang được người dùng yêu cầu trực tiếp thực hiện công việc trong workspace `F:\AI\12Tester` bằng Antigravity CLI, model Gemini 3.8 Flash và reasoning effort high. Không tự thay model; nếu không dùng được model này, báo rõ và dừng trước khi chỉnh sửa. Không gọi model/agent khác để thay thế lựa chọn của người dùng.

Đọc `KE_HOACH_THUONG_THEM_CHO_TESTER_2026-11-11.md` trong workspace. Người dùng đã yêu cầu chuyển từ nghiên cứu sang triển khai và tiếp tục sửa các lỗi phát hiện được cho đến khi hoàn tất. Dùng các quy tắc đề xuất trong kế hoạch làm mặc định triển khai; không dừng chỉ để hỏi lại ba đề xuất đã được mô tả nếu không phát hiện mâu thuẫn thực tế.

## Kết quả cần có

- Thêm “Thưởng thêm cho tester” ngay dưới “Đẩy app” ở trang quản lý app của chủ thật.
- Bấm mở trang riêng đúng app, nhập Credit cho mỗi tester và số người tối đa đầu tiên; có Đồng ý/Hủy, tổng ngân sách và số dư dự kiến.
- Sau thành công, thẻ app trên Trang chủ hiện icon Credit và +N màu cam bên phải, ví dụ +5. Bố cục không đè tên/nút gỡ, hỗ trợ màn hẹp/phông lớn/light/dark.
- Triển khai backend thực sự: giữ trước ngân sách N × Credit, mỗi tester một lần/đợt, trả khi báo cáo app_test mới được chấp nhận theo thứ tự cấp thưởng server; bao gồm auto-accept sau 8 giờ. Snapshot đợt lúc tạo báo cáo, không hồi tố báo cáo cũ, không lẫn mission/thưởng cập nhật/tip cũ.
- Không trả trùng, không vượt suất, không trừ tiền lại sau timeout; đầy đủ reconcile, logs, awards và các bất biến tài chính. Đóng/gỡ app phải kết toán báo cáo pending hợp lệ và hoàn phần chưa dùng đúng một lần.
- Tích hợp feed/sync tokens/legacy projections/cache/mutation version, navigation và toàn bộ strings mặc định/vi/en; người dùng khác thấy dữ liệu đúng theo refresh hiện hành. Không thêm RPC theo từng thẻ.

## Giới hạn thực hiện

1. Bảo toàn mọi thay đổi có sẵn. Đọc git status/diff trước khi sửa. Repository có nhiều file modified/untracked và migration cũ đã được consolidate; không reset, checkout, clean, stash toàn bộ, xóa hoặc ghi đè công việc khác.
2. Chỉ chỉnh file cần cho tính năng và sửa lỗi liên quan được kiểm chứng. Không nâng phiên bản ứng dụng ngoài yêu cầu; giữ phiên bản hiện tại. Không build APK/AAB, không chạy assemble/bundle. Được compile Kotlin và chạy unit test cần thiết nếu không sinh APK.
3. Không deploy production, chạy fixture ghi dữ liệu lên production, gửi thông báo thật, commit/push hoặc sửa tài khoản/cấu hình đăng nhập. Migration bổ sung được tạo để review và kiểm thử local/staging; báo rõ chưa áp production.
4. Không bỏ qua permission deny, không tự đổi chính sách bảo vệ hoặc đọc/in secrets. Nếu tác vụ bị chặn, báo đúng lệnh và nguyên nhân. Không kết luận “hết lỗi” khi chưa kiểm tra được.
5. Dùng migration số còn trống tại thời điểm sửa (snapshot kế hoạch mới nhất là 086), không sửa baseline 062 để áp lại. Kiểm tra định nghĩa hàm cuối cùng qua chuỗi migration thay vì sửa nhầm bản cũ.

## Cách hoàn tất

Đọc source hiện tại; triển khai từng phần; chạy kiểm tra phù hợp; đọc lỗi, sửa, rồi kiểm tra lại cho đến khi các kiểm tra liên quan qua hoặc có blocker bên ngoài được mô tả rõ. Không dừng ở kế hoạch, skeleton, TODO hoặc sau khi chỉ tạo UI.

Kiểm tra tối thiểu: input/overflow, tạo đợt và replay, đủ/thiếu tiền, nhiều request tranh cùng ví/cùng app, tester nhiều ngày, nhiều tester tranh suất cuối, approve/auto-accept đua cùng report, snapshot/resubmit/context, đóng/gỡ/pause/resume/hoàn dư, RLS và ghi trực tiếp, cache/sync/fallback và hồi quy Đẩy app/tip/mission/carryover. Concurrency tài chính cần PostgreSQL thật nếu môi trường có sẵn; PGlite/test đọc chuỗi không thay thế bằng chứng concurrent transaction. Không tuyên bố đã kiểm thử UI trên thiết bị nếu chỉ đọc code.

Sau lượt triển khai đầu, tự review diff tập trung vào lỗi tài chính, thứ tự khóa/deadlock, các đường không gọi helper thưởng mới và sai lệch cache; sửa lỗi tìm thấy và chạy lại kiểm tra cần thiết. Không lặp kiểm thử đã qua mà không có lý do.

Tạo báo cáo kết quả trong repository ghi: file đã sửa, quy tắc đã áp dụng, lệnh kiểm tra và kết quả, giới hạn chưa kiểm chứng, migration cần áp riêng, và xác nhận không build APK/không deploy. Chỉ kết luận hoàn tất khi có bằng chứng tương ứng; nếu hết quota hoặc gặp quyền/mạng bị chặn, nêu rõ phần đã làm và còn lại.
