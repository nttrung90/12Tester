# Hoàn thiện giao diện Material 3 — 28/10/2026

Đã áp dụng phong cách Material và cách thu gọn được chốt ở bản xem trước vào code Android. Giữ typography, nội dung và chức năng; chỉ điều chỉnh bố cục cục bộ của banner/card theo yêu cầu.

## Thay đổi

- `ui/theme/Color.kt`, `Theme.kt`: hoàn thiện ColorScheme sáng/tối theo xanh nhận diện, có các mức surface/container, outline và cặp màu nội dung tương ứng. Typography giữ nguyên.
- `ui/theme/StatusColors.kt`: màu success/warning/info dùng chung, gồm container và content cho hai chế độ. Theo theme đang chọn của ứng dụng.
- `ui/components/DailyTaskCard.kt`: padding 12dp ngang/10dp dọc; icon đầu 28dp; gom trạng thái, tiến độ và số đếm cùng hàng; giảm khoảng trống giữa các phần. Font lớn chuyển thanh tiến độ xuống hàng riêng. Hàng nút cho phép xuống dòng để không cắt nhãn. Giữ đủ trạng thái guest, owner/member, miễn phạt, miễn chỉ tiêu, bỏ lỡ và hoàn tất.
- `ui/components/CampaignCard.kt`: padding 10dp ngang/8dp dọc, icon 44dp; tên/chủ app có thể cùng hàng và tự xuống dòng khi cần; thống kê dùng toàn chiều rộng. Giữ các số đã test, chỉ tiêu, đã cài, ngày còn lại và thông báo đủ chỉ tiêu. `displayTestedCount` và điều kiện xác định đủ chỉ tiêu giữ nguyên; giá trị chưa có tiếp tục là `--`.
- `ui/components/MyAppCard.kt`: padding 10dp, icon 44dp, gap giữa badge giảm còn 6dp ngang/4dp dọc, padding badge 6dp ngang/2dp dọc. Giữ slot, trạng thái app, ngày còn lại, đã cài, đã test, chờ duyệt và nhãn tester. Badge dùng bề mặt tonal sáng/tối; giữ cỡ chữ hiện có.
- `ui/components/CreditTopupBadge.kt`: dùng primaryContainer với cặp màu nội dung tương ứng.
- `ui/screens/HomeScreen.kt`: app bar/nền, navigation, avatar, số thông báo, badge nhiệm vụ, legend và banner chờ duyệt dùng màu theme. Khoảng cách danh sách Khám phá 6dp, Của tôi 8dp. Giữ các nút, năm tab và vị trí chức năng hiện có.
- `res/values-en/strings.xml`: sửa `%1$d` thành `%1$s` cho số thống kê có thể là `--`; đồng bộ 12 chuỗi tiếng Anh về chu kỳ thống kê từ resource mặc định hiện có, không viết lại nội dung. Đây là các lỗi lint phát hiện trong quá trình kiểm tra.

Các đường dẫn Kotlin ở trên thuộc `app/src/main/java/com/ntt/crosstest/`.

Không đổi repository, ViewModel, upload ảnh, cache, lịch thống kê, phần thưởng, quyền truy cập hay điều hướng nghiệp vụ. Không nâng dependency. Các sửa đổi có trước trong workspace được giữ lại; đối chiếu diff từ snapshot trước khi bắt đầu phần UI.

## Kiểm chứng

Đã chạy với Gradle wrapper hiện có và cache offline:

```text
:app:assembleDebug :app:lintDebug :app:testDebugUnitTest --offline --console=plain
BUILD SUCCESSFUL
```

- 184 unit test thuộc 24 suite: 0 failure, 0 error, 0 skipped.
- Lint: 0 Error/Fatal; còn 430 Warning và 2 Hint trên toàn dự án. Không coi kết quả này là dự án đã sạch mọi cảnh báo.
- `git diff --check` cho các tệp đã sửa không phát hiện lỗi whitespace.
- Đối chiếu các khóa string của ba card với snapshot: giữ nguyên toàn bộ (CampaignCard 11, MyAppCard 11, DailyTaskCard 26).
- Build Kotlin trước khi sửa cũng đã thành công.

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

## Giới hạn nghiệm thu hình ảnh

Chưa chạy UI trên thiết bị/emulator trong lượt triển khai; ADB không có thiết bị đang kết nối. Số phần trăm thu gọn đo ở bản xem trước không phải số đo trên Android thật.

Khi chạy bản debug, đối chiếu Trang chủ/App của tôi ở VI/EN và sáng/tối, tên dài, font lớn; kiểm tra banner ở trạng thái chưa đủ/hoàn tất/miễn phạt và các app có/không có báo cáo chờ duyệt. Bố cục cho phép xuống dòng thay vì cố định chiều cao, nên chiều cao thực tế tùy nội dung và cài đặt chữ.
