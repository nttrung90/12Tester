# Bố cục Bảng Quản Trị Viên — 01/11/2026

## Khảo sát

- Màn hình Android Compose: `app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt`.
- Nhiệm vụ ngày đã có thẻ riêng; tìm kiếm, bộ lọc và danh sách thành viên đang nằm rời nhau.
- Chu kỳ thống kê, cấu hình cập nhật và gửi thông báo toàn hệ thống đang là ba nút biểu tượng trên thanh tiêu đề.
- Các hộp thoại, sheet và callback hiện có đã xử lý lưu cấu hình, quyền admin và thay đổi phiên đăng nhập; bố cục mới sử dụng lại các luồng này.

## Thiết kế và triển khai

Màn hình có một danh sách cuộn dọc với đúng thứ tự:

1. **Quản lý thành viên**: gộp tiêu đề, tìm kiếm, bộ lọc, trạng thái tải/rỗng và danh sách thành viên. Vùng danh sách cao tối đa 360dp, cuộn riêng và chỉ dựng các hàng cần hiển thị. Các thẻ tiếp theo vẫn có thể tiếp cận khi có nhiều thành viên.
2. **Nhiệm vụ ngày**: hiển thị tỷ lệ hiện hành nếu đã tải được; chạm thẻ mở sheet cấu hình hiện có.
3. **Chu kỳ thống kê Trang chủ**: chạm thẻ mở hộp thoại chu kỳ hiện có.
4. **Cấu hình cập nhật ứng dụng**: chạm thẻ mở hộp thoại phiên bản hiện có.
5. **Gửi thông báo toàn hệ thống**: chạm thẻ mở hộp thoại soạn thông báo hiện có.

Các thẻ thống nhất góc bo 14dp, khoảng cách 12dp và lề màn hình 16dp. Bốn thẻ mở chức năng dùng biểu tượng, tiêu đề, mô tả ngắn và mũi tên; chữ được xuống dòng khi cần. Thẻ quản lý thành viên chứa đầy đủ thao tác khóa/mở khóa, điều chỉnh credit và gửi thông báo riêng.

Thanh tiêu đề giữ nút quay lại và làm mới. Kéo xuống làm mới được áp dụng cho danh sách ngoài. Bổ sung chuỗi tiếng Việt/Anh cho tiêu đề quản lý thành viên, mô tả cập nhật và mô tả thông báo; dùng lại các chuỗi hiện có cho chức năng khác.

## Kiểm tra nghiệm thu

- Build APK debug để xác nhận Kotlin/Compose và tài nguyên VI/EN biên dịch được.
- Chạy các bộ regression admin hiện có phù hợp với những callback được giữ lại.
- Đối chiếu thay đổi với bản sao ngay trước lần chỉnh bố cục để bảo toàn các sửa đổi đang có trong workspace.
- Kiểm tra thủ công trên thiết bị: thứ tự năm thẻ; cuộn trang/danh sách; tìm kiếm và bốn bộ lọc; danh sách rỗng/đang tải; mở từng sheet/dialog; thao tác quản lý thành viên; màn hình nhỏ, chữ lớn và giao diện VI/EN.

## Kết quả

- Đã triển khai đúng năm thẻ theo thứ tự trên trong `AdminUsersScreen.kt`, bổ sung bốn chuỗi mới cho mỗi bộ tài nguyên mặc định/EN/VI.
- `:app:assembleDebug`: **BUILD SUCCESSFUL**; APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `:app:testDebugUnitTest`: **23 tests đạt**, 0 failures/errors/skips: `AdminFlowSessionRaceTest` (9), `AdminDailyTaskPolicyTest` (7), `AdminDailyTaskHardeningRegressionTest` (7). Các bộ này kiểm tra logic admin hiện có, không phải kiểm thử tương tác giao diện.
- Log build/test: `audit/admin-panel-layout-2026-11-01/gradle-validation.log`; XML kết quả được lưu trong cùng thư mục audit.
- `git diff --check` cho bốn file production đã sửa: đạt. Diff so với bản sao ngay trước chỉnh sửa xác nhận chỉ thay phần bố cục/import và bổ sung chuỗi; các dialog, callback, ViewModel và repository được giữ nguyên.
- Chưa chạy kiểm tra UI trực tiếp trên thiết bị/emulator; danh sách kiểm tra thủ công ở trên là các bước còn cần xác nhận khi nghiệm thu giao diện thực tế.
