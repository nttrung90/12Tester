# Triển khai phần mở rộng Bảng Quản Trị Viên

Đã hoàn tất triển khai mã và nghiệm thu cục bộ. Chưa áp dụng thay đổi lên Supabase production. Kết quả và APK nằm trong `KET_QUA_MO_RONG_ADMIN_OPENCODE_2026-11-01.md`.

## Thứ tự triển khai

1. Dùng quy trình sao lưu và phát hành hiện có của dự án; kiểm tra môi trường đích đã có migration 069.
2. Áp dụng lần lượt 070_admin_management.sql, 071_business_policies.sql, 072_admin_notification_campaigns.sql, 073_admin_action_audit.sql trong supabase/migrations. Migration 073 phải chạy sau 071 để bọc đúng các hàm thưởng đã dùng snapshot chính sách.
3. Cấp quyền bổ nhiệm/hạ quản trị viên cho tài khoản quản trị ban đầu bằng một lần bootstrap do database owner thực hiện:

   ```sql
   select public.bootstrap_admin_capability('THAY_BANG_UUID_ADMIN', 'admin_management');
   ```

   Tài khoản đích phải đã có role admin. Đây là bước cài đặt quyền ban đầu; các thao tác quản trị thường ngày sau đó thực hiện trong app. Không cấp quyền gọi hàm bootstrap cho người dùng authenticated.
4. Kiểm tra lịch xử lý thông báo. Migration 072 đăng ký lịch mỗi phút khi pg_cron khả dụng và có quyền. Nếu không có cron, triển khai backend/notification-worker theo README của module, dùng npm ci và một danh tính database được phép gọi service_process_notification_campaigns. Không cần chạy đồng thời cả hai cơ chế để ứng dụng hoạt động.
5. Phát hành bản Android mới sau khi backend đã sẵn sàng. Trong app, mở thẻ thông báo và kiểm tra trạng thái bộ lập lịch, gửi thử tới một nhóm được chọn, kiểm tra số đã tạo và đã đọc riêng biệt.

## Hành vi sau triển khai

- Năm thẻ đầu giữ thứ tự cũ. Thẻ thông báo thứ năm mở trình quản lý thông báo; các công cụ quản trị khác nằm phía dưới.
- Chính sách nghiệp vụ mới có ngày hiệu lực tương lai theo giờ Việt Nam. Các mức mặc định giữ nguyên đến khi chính sách mới có hiệu lực.
- Báo cáo và đợt thưởng cập nhật app dùng mức thưởng đã chốt khi phát sinh. Đổi chính sách không tính lại lịch sử, quỹ đã giữ, các khoản đã chi hoặc hoàn.
- Mua slot, đẩy app và công bố thưởng cập nhật dùng báo giá từ server. Khi điều khoản thay đổi, người dùng phải xem và xác nhận lại; yêu cầu chưa rõ kết quả có luồng thử lại theo mã yêu cầu cũ.
- Client cũ bị yêu cầu cập nhật khi điều khoản tài chính vượt mức mà client cũ biết cách xác nhận.
- Hủy cảnh báo và bồi hoàn credit có nhật ký; hủy cảnh báo không xóa bản ghi lịch sử gốc.

## Kiểm tra trong app

Kiểm tra tìm kiếm/phân trang thành viên, chi tiết nhiệm vụ và credit, chính sách cộng dồn, quản lý chiến dịch, hàng đợi báo cáo, hỗ trợ, chính sách nghiệp vụ, phân quyền, nhật ký, thông báo và giám sát. Tài khoản thường, moderator và admin bị khóa phải bị backend từ chối ở các RPC chỉ dành cho admin.

APK debug: `app/build/outputs/apk/debug/app-debug.apk` (1.0.9, versionCode 29). Toàn bộ 499 unit test Android đã đạt; các bộ backend và worker đã đạt. Cần áp dụng backend theo thứ tự trên trước khi dùng các RPC và màn quản trị mới. Kiểm tra đăng nhập/quyền admin và thao tác thử trên thiết bị thật trong môi trường phát hành trước khi đưa vào sử dụng chính thức.
