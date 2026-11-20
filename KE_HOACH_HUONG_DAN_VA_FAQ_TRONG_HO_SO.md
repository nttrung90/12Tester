# Kế hoạch thêm Hướng dẫn sử dụng & Câu hỏi thường gặp

## Luồng sử dụng

1. Người dùng đã đăng nhập mở **Hồ sơ → Cài đặt**. Mục **Hướng dẫn sử dụng & Câu hỏi thường gặp** nằm ngay dưới **Đổi mật khẩu**, trước **Liên hệ & Báo cáo lỗi**. Mục mới dùng cùng kiểu hàng cài đặt với biểu tượng, nhãn và mũi tên.
2. Chạm vào mục mới để mở màn hình **Hướng dẫn sử dụng & Câu hỏi thường gặp**. Màn hình có nút quay lại và hai tab nhỏ.

   | Tab trái | Tab phải |
   | --- | --- |
   | **Hướng dẫn sử dụng**. Mở mặc định, hiển thị đầy đủ 6 phần của bộ hướng dẫn trong vùng cuộn dọc. | **Câu hỏi thường gặp**. Hiển thị đủ 27 câu hỏi theo 5 nhóm, với câu trả lời thu gọn khi mở màn hình. |

3. Trong tab FAQ, chạm vào toàn bộ hàng câu hỏi để hiện câu trả lời ngay bên dưới. Chạm lại để đóng. Khi mở câu hỏi khác, câu trả lời trước tự đóng, vì vậy mỗi thời điểm có tối đa một câu trả lời đang mở. Mũi tên thể hiện trạng thái mở hoặc đóng.
4. Nút quay lại trên thanh tiêu đề và nút Back của Android đưa người dùng về **Hồ sơ**. Giữ tab đang chọn, vị trí cuộn từng tab và câu hỏi đang mở khi đổi tab hoặc xoay màn hình. Khi rời màn hình hỗ trợ rồi mở lại, bắt đầu ở **Hướng dẫn sử dụng**.

Đề xuất hiển thị mục mới trong nhánh **Hồ sơ đã đăng nhập**, vì đây là nhánh hiện có mục **Đổi mật khẩu**. Nội dung hai tab dùng nguyên văn tiếng Việt đã soạn. Nhãn điều hướng có bản tiếng Việt và tiếng Anh để phù hợp cài đặt ngôn ngữ hiện có.

## Tích hợp Android

| Thành phần | Thay đổi dự kiến |
| --- | --- |
| [ProfileScreen.kt](app/src/main/java/com/ntt/crosstest/ui/screens/ProfileScreen.kt) | Thêm callback mở màn hình hỗ trợ. Truyền callback vào `SettingsSection` của nhánh đã đăng nhập và đặt hàng mới sau khối **Đổi mật khẩu**. |
| [HomeScreen.kt](app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt) | Truyền callback điều hướng qua màn hình **Hồ sơ** đang nằm trong pager. |
| [NavGraph.kt](app/src/main/java/com/ntt/crosstest/ui/navigation/NavGraph.kt) | Thêm một đích điều hướng cho màn hình hỗ trợ. Dùng `popBackStack()` để quay về **Hồ sơ** và kiểm tra pager phục hồi đúng tab. Hai tab nhỏ dùng trạng thái cục bộ của màn hình. |
| `HelpScreen.kt` | Thêm màn hình Compose Material 3 với thanh tiêu đề, hai tab và danh sách cuộn. Dùng trạng thái lưu được cho tab, vị trí cuộn và mã câu hỏi mở. Chỉ đưa câu trả lời của câu đang mở vào giao diện. |
| Tài nguyên Android | Thêm tiêu đề màn hình và nhãn hai tab vào tài nguyên mặc định, tiếng Việt và tiếng Anh. Đóng gói nội dung hướng dẫn và FAQ trong ứng dụng để đọc được khi mất mạng. |

### Nguồn nội dung

- Dùng [Hướng dẫn sử dụng](HUONG_DAN_SU_DUNG_12_CROSS_TESTERS.md) và [Câu hỏi thường gặp](CAU_HOI_THUONG_GAP_12_CROSS_TESTERS.md) làm nguồn duy nhất. Khi build Android, sao chép đúng hai tệp vào thư mục assets sinh tự động để nội dung trong app đồng bộ với tài liệu trong repo.
- Đọc nội dung cục bộ và hiển thị bằng Compose. Phạm vi định dạng gồm tiêu đề, đoạn văn, chữ đậm, danh sách, bảng, mã ngắn và liên kết có trong hai tệp. Giữ đầy đủ chữ và thứ tự. Trên màn hình hẹp, trình bày từng hàng bảng theo cặp nhãn và nội dung để tránh phải cuộn ngang.
- Với FAQ, dùng tiêu đề cấp 2 làm nhóm. Dùng tiêu đề cấp 3 đánh số làm câu hỏi và phần nội dung tiếp theo làm câu trả lời. Dùng số câu làm mã ổn định trong phiên đọc. Liên kết đến hướng dẫn trong FAQ chuyển sang tab trái. Liên kết HTTPS như Google Group và Play Console mở bằng trình duyệt.

## Trình tự triển khai

1. Cấu hình đóng gói hai tài liệu và kiểm tra nội dung đọc được từ assets.
2. Dựng màn hình hai tab, hiển thị hướng dẫn và thêm hành vi mở hoặc đóng câu trả lời.
3. Nối màn hình với **Hồ sơ → Cài đặt**, bổ sung tài nguyên ngôn ngữ và kiểm tra hành vi quay lại.
4. Build bản debug và chạy các kiểm tra bên dưới trên thiết bị hoặc emulator.

## Tiêu chí nghiệm thu

| Kiểm tra | Kết quả cần đạt |
| --- | --- |
| Vị trí nút và điều hướng | Nút nằm ngay dưới **Đổi mật khẩu**. Chạm mở đúng màn hình. Nút quay lại và Back đưa người dùng về **Hồ sơ**. |
| Tab hướng dẫn | Tab trái được chọn mặc định. Đọc được cả 6 phần, gồm danh sách, bảng và liên kết. |
| Tab FAQ | Có đủ 27 câu hỏi theo đúng thứ tự. Mặc định thu gọn. Chạm một câu hiện đúng câu trả lời ngay bên dưới. Chạm lại đóng câu đó. Mở câu khác đóng câu trước. |
| Trạng thái đọc | Đổi tab rồi quay lại hoặc xoay màn hình vẫn giữ vị trí đọc và câu FAQ đang mở. Mở lại từ Hồ sơ bắt đầu ở tab trái với FAQ thu gọn. |
| Mạng và liên kết | Cả hai tab đọc được khi thiết bị mất mạng. Liên kết đến hướng dẫn đổi tab. Liên kết web mở đúng trang khi có kết nối. |
| Khả năng đọc và truy cập | Thử trên màn hình hẹp, với cỡ chữ lớn và giao diện sáng hoặc tối. Hàng câu hỏi nhận thao tác trên toàn bộ chiều rộng. TalkBack đọc được tên câu hỏi và trạng thái mở hoặc đóng. |
