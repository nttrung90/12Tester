# Kiểm tra lại bố cục Bảng Quản Trị Viên — 01/11/2026

## Kết luận

Đúng thứ tự năm thẻ theo kế hoạch và build debug thành công. Có **một lỗi giao diện mức P2** đã tái hiện trong component thật: nhãn nút điều chỉnh credit bị cắt trên màn hình hẹp, biến mất khi tăng cỡ chữ. Chưa sửa mã production trong lượt kiểm tra này.

## P2 — Hàng thao tác thành viên ép nhãn credit về chiều rộng bằng 0

Vị trí: `app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt`, dòng 918–967.

Ba thao tác điều chỉnh credit, thông báo riêng và khóa/mở khóa vẫn cùng nằm trong một `Row`. Hai thao tác sau lấy chiều rộng trước; nút credit dùng `weight(1f)` nhận phần còn lại. Thẻ thành viên hiện còn có lề của dashboard, lề của danh sách và lề nội dung thẻ. Thêm `maxLines = 1` cùng `TextOverflow.Ellipsis` không giải quyết thiếu chỗ mà che nhãn thao tác.

Phép đo thực tế bằng Robolectric Android 34, native graphics, tiếng Việt, gọi đúng `AdminUserCard` đã biên dịch, với lề dashboard 16dp + lề danh sách 14dp mỗi bên:

| Chiều rộng màn hình | Cỡ chữ | Kết quả nhãn “Tặng / Trừ Credit” |
|---|---|---|
| 320dp | 100% | Bị ellipsis; vùng chữ rộng 31px ở mật độ 1 |
| 360dp | 100% | Bị ellipsis; vùng chữ rộng 71px |
| 320dp | 200% | Bounds chữ bằng 0; không còn nhãn nhìn thấy |
| 360dp | 200% | Bounds chữ bằng 0; không còn nhãn nhìn thấy |
| 411dp | 100% | Hiển thị đầy đủ |
| 411dp | 130% và 200% | Bị ellipsis |

Hệ quả: admin khó nhận diện thao tác credit trên màn hình hẹp hoặc khi dùng chữ lớn. Nên cho hàng thao tác tự chuyển thành hai hàng/hàng dọc khi thiếu chỗ, để nhãn và nút giữ kích thước sử dụng được. Cần kiểm tra lại cả trạng thái khóa/mở khóa và giao diện tiếng Anh sau sửa.

## Các phần đạt qua đối chiếu mã

- Năm thẻ đúng thứ tự: Quản lý thành viên → Nhiệm vụ ngày → Chu kỳ thống kê Trang chủ → Cấu hình cập nhật ứng dụng → Gửi thông báo toàn hệ thống.
- Tìm kiếm, bốn bộ lọc, danh sách và thao tác thành viên cùng nằm trong thẻ đầu.
- Phần thành viên có thu gọn/mở rộng; danh sách giữ `LazyListState` khi thu gọn và giới hạn chiều cao 360dp.
- Bốn thẻ chức năng vẫn nối vào sheet/dialog tương ứng. Thanh tiêu đề giữ quay lại/làm mới.
- Chuỗi mới có đủ mặc định/VI/EN; build tài nguyên thành công.
- Không thấy lỗi đo vô hạn trong cấu trúc cuộn qua rà soát mã: danh sách dọc bên trong đã được chặn chiều cao. Chưa xác nhận toàn bộ cử chỉ cuộn trên thiết bị.

## Kiểm thử và bằng chứng

- `:app:assembleDebug`: **BUILD SUCCESSFUL**; lượt kiểm tra dùng kết quả build hiện tại hợp lệ qua Gradle UP-TO-DATE.
- 23 kiểm thử logic admin đã được **thực thi lại**, đều đạt: `AdminFlowSessionRaceTest` (9), `AdminDailyTaskPolicyTest` (7), `AdminDailyTaskHardeningRegressionTest` (7).
- Một probe layout thực thi thêm, đo 9 cấu hình: chiều rộng 320/360/411dp × fontScale 1/1.3/2. Probe thu thập bounds và cờ ellipsis; probe đạt không đồng nghĩa bố cục không lỗi, vì đây là phép đo phát hiện vấn đề nêu trên.
- `git diff --check` của màn hình và tài nguyên: đạt.
- Log và XML: `audit/admin-panel-layout-recheck-2026-11-01/`.
- Dữ liệu đo: `audit/admin-panel-layout-recheck-2026-11-01/member-layout.txt`.
- Probe nằm riêng trong thư mục audit, chỉ được thêm vào source set test khi truyền `-I audit/admin-panel-layout-recheck-2026-11-01/review.init.gradle`; không thay cấu hình build hoặc source production.
- SHA-256 `AdminUsersScreen.kt` được kiểm tra: `4DA378D33F5E872E681D60C629883D621A7642E0380F8718718E99988D45E21D`.

Chưa đăng nhập admin thật hoặc kiểm tra tương tác toàn màn hình trên thiết bị/emulator. Phép đo layout dùng component thật với dữ liệu fixture và typography Material 3 mặc định giống cấu hình hiện tại của app; không gọi backend hoặc gửi thông báo thật.
