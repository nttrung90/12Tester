# Kế hoạch hoàn thiện phong cách Material Design cho giao diện hiện tại

Cập nhật ngày 28/10/2026 theo yêu cầu của chủ ứng dụng. Đã triển khai bảng màu Material sáng/tối dùng chung và phần giao diện đã chốt trên Trang chủ/App của tôi: thu gọn banner Nhiệm vụ hôm nay và card app theo chiều dọc, giữ cỡ chữ và đầy đủ dữ liệu.

## 1. Mục tiêu đã chốt

Áp dụng nhất quán cách dùng màu, bề mặt, viền, bo góc và phản hồi tương tác của Material Design 3 trên giao diện 12 Tester hiện có. Chủ ứng dụng xác nhận chữ, tiêu đề đã rõ và khoảng cách gần chuẩn; coi đây là nền tảng cần giữ khi triển khai.

Ưu tiên mật độ thông tin: cùng một diện tích màn hình cần xem được ít nhất lượng thông tin hiện tại. Có thể tận dụng khoảng trống để thông tin hiện có dễ quét hơn, nhưng không đổi nội dung, thêm dữ liệu hay thay luồng chỉ để làm đẹp.

Kế hoạch này thay thế hướng thiết kế lại rộng trong bản trước. Hai bản phác thảo trước không dùng làm mẫu nghiệm thu cho phạm vi đã điều chỉnh.

## 2. Những yếu tố giữ nguyên

- Nội dung chữ, tiêu đề, nhãn nút, tên tab và nội dung hướng dẫn.
- Font, cỡ chữ, độ đậm, cách phân cấp chữ hiện tại.
- Bố cục tổng thể, thứ tự khu vực và vị trí các chức năng trên mỗi màn hình.
- Năm tab hiện tại, các bộ lọc, Credit, xóa cache, thông báo và avatar ở vị trí đang có.
- Hai nút nổi và chức năng hiện có trong tab Của tôi.
- Khoảng cách đã ổn ở các khu vực khác. Riêng banner Nhiệm vụ hôm nay và card app được giảm padding, gom hàng hợp lý để thấp hơn theo yêu cầu mới nhất; giữ nguyên cỡ chữ và nội dung.
- Các chỉ số, badge và thông tin đang hiển thị trên card; không giới hạn còn hai chỉ số hoặc đưa bớt vào chi tiết.
- Luồng mở app, đăng app, nộp báo cáo, duyệt báo cáo và các màn/sheet/dialog hiện tại.
- Quyền truy cập, điều kiện thao tác, dữ liệu, cache, ảnh và xử lý nền.

Điều chỉnh bố cục cục bộ cho banner/card gồm: trạng thái, thanh tiến độ và số đếm cùng hàng; tên/chủ app cùng hàng khi đủ chỗ; thống kê dùng toàn chiều rộng card; badge gọn hơn. Các khu vực khác chỉ sửa bố cục khi có lỗi tràn/cắt chữ hoặc vùng bấm chồng lấn, không thay thiết kế toàn màn hình.

## 3. Phần cần áp dụng Material Design

| Phần | Điều chỉnh trong phạm vi | Yếu tố cần giữ khi chỉnh |
|---|---|---|
| Theme | Hoàn thiện các vai trò màu sáng/tối, đặc biệt màu nội dung trên từng bề mặt | Xanh nhận diện và chế độ sáng/tối/hệ thống hiện có |
| Nền và card | Dùng sắc độ surface/container phù hợp để tách lớp nhẹ nhàng | Kích thước, padding, vị trí và lượng nội dung card |
| Viền và elevation | Viền rõ vừa đủ; elevation nhẹ ở thành phần cần phân lớp | Danh sách vẫn gọn, không thêm bóng đậm cho mọi card |
| Bo góc | Đồng bộ những thành phần cùng loại, ưu tiên bán kính đang dùng phù hợp | Không phóng mọi card thành khối lớn hoặc dạng viên thuốc |
| Nút | Đồng bộ màu filled/tonal/outlined/text theo mức nhấn hiện có | Nhãn, vị trí, kích thước và callback |
| Badge/chip | Cặp màu container/content nhất quán, đọc rõ ở sáng/tối | Toàn bộ trạng thái, số liệu và kiểu gọn hiện có |
| Tab/navigation | Indicator và trạng thái chọn dùng màu theo theme | Số tab, nhãn, thứ tự và cách điều hướng |
| Text field | Đồng bộ màu viền, focus, lỗi và disabled | Kiểu field, nội dung, chiều cao và bố cục form |
| Dialog/sheet | Đồng bộ bề mặt, shape, nút và scrim | Kiểu mở, nội dung, vị trí thao tác và hành vi |
| Phản hồi tương tác | Ripple, pressed, selected, disabled và loading rõ ràng | Thao tác cũ, không thêm bước hoặc hiệu ứng làm chậm |

Không cần chuyển sang Material 3 Expressive hoặc nâng thư viện để hoàn thành phạm vi này. Chọn API đang có trong dependency của ứng dụng; chỉ cân nhắc nâng cấp nếu gặp một yêu cầu kỹ thuật cụ thể.

## 4. Cơ sở từ mã nguồn

Các tệp sau thuộc `app/src/main/java/com/ntt/crosstest/` trong dự án `F:\AI\12Tester`.

- `ui/theme/Theme.kt` mới gán một phần ColorScheme. Đây là điểm có tác động rộng nhất để làm bề mặt và màu nội dung nhất quán.
- `ui/theme/Color.kt` có bảng xanh sáng/tối và một số màu nhiệm vụ; có thể mở rộng từ bảng đang có.
- `ui/theme/Type.kt` dùng Typography mặc định. Giữ nguyên trong đợt này theo yêu cầu của chủ ứng dụng; không áp một thang chữ mới lên các màn hình.
- `ui/components/CampaignCard.kt` đã có bố cục gọn: icon 52dp, padding 12dp, khoảng icon/nội dung 12dp, bo góc 14dp, elevation 0dp. Giữ các giá trị này làm baseline; tập trung vào màu bề mặt, viền và badge.
- `CampaignCard` đang hiển thị tên/chủ app, trạng thái cài/test, số test theo chỉ tiêu, số đã cài và ngày còn lại theo state. Bản hoàn thiện phải giữ đủ thông tin này.
- `ui/components/MyAppCard.kt` đã dùng FlowRow cho các pill số liệu. Giữ cấu trúc và các pill; thay cách dùng màu/viền nếu cần để bớt rối.
- `HomeScreen.kt`, `CampaignDetailScreen.kt`, `OwnerAppDetailScreen.kt` còn các màu gán trực tiếp. Chuyển các màu có cùng ý nghĩa sang vai trò theme hoặc cặp màu trạng thái dùng chung; không thay vị trí khu vực.

Màu gán trực tiếp là dấu hiệu cần rà soát tính nhất quán, không tự chứng minh giao diện đang sai. Chưa chạy giao diện Android trên thiết bị trong lượt nghiên cứu; cần ảnh baseline để kiểm chứng thay đổi nhìn thấy được.

## 5. Quy tắc màu và bề mặt

- Giữ xanh thương hiệu làm màu chính. Primary dành cho hành động/trạng thái chọn; tránh phủ màu đậm trên toàn bộ nội dung.
- Chọn surface/container để card khác nền vừa đủ. Khi bề mặt đã phân biệt rõ, dùng viền nhẹ phù hợp thay vì kết hợp viền đậm và bóng đậm.
- Dùng đúng cặp màu nội dung trên nền tương ứng: onPrimary/primary, onPrimaryContainer/primaryContainer, onSurface/surface, v.v.
- Tách success, warning và info thành cặp container/content sáng/tối khi cần. Đây là màu ngữ nghĩa bổ sung của ứng dụng, không phải mặc định mọi tên đều có sẵn trong Material ColorScheme.
- Giữ ý nghĩa trạng thái và nhãn hiện có; đồng bộ cùng một trạng thái giữa danh sách, chi tiết và báo cáo.
- Màu lỗi, focus, disabled và selected phải nhất quán trong field, nút và bộ lọc.
- Màu sắc hỗ trợ phân biệt; nhãn hiện có vẫn là căn cứ đọc trạng thái.

Tài liệu tham chiếu: [Material 3 trong Compose](https://developer.android.com/develop/ui/compose/designsystems/material3).

## 6. Hiển thị nhiều thông tin mà vẫn rõ

Đây là cải tiến cục bộ sau khi hoàn thiện màu và bề mặt, không phải thay cấu trúc màn hình.

1. **Giữ nội dung thay vì cắt giảm:** không ẩn số đã cài, số đã test, chỉ tiêu, ngày còn lại hoặc báo cáo chờ duyệt để card trông thoáng hơn.
2. **Tận dụng hàng số liệu hiện tại:** căn số/nhãn gọn và nhất quán; ở hàng đủ rộng, các thông tin có thể nằm cạnh nhau mà vẫn giữ nhãn và thứ tự đọc hiện tại.
3. **Phân cấp bằng sắc độ:** chữ chính giữ màu đọc rõ; metadata dùng onSurfaceVariant; trạng thái dùng nền tonal gọn. Không cần tăng cỡ tiêu đề hoặc thêm một khối nền cho từng số.
4. **Giữ badge dạng gọn:** badge chỉ hiển thị trạng thái không cần biến thành chip tương tác cao lớn. Vùng chạm tối thiểu dành cho thành phần thực sự bấm được.
5. **Hạn chế trang trí chiếm chỗ:** giữ icon ở kích thước hiện tại, tránh hero/banner mới hoặc padding lớn làm giảm số card nhìn thấy.
6. **Chỉ giảm khoảng trống có bằng chứng:** nếu có đoạn trống dư thực tế, có thể tinh chỉnh nhỏ tại đó; khoảng cách đang hợp lý giữ nguyên. Không đặt mục tiêu nén tất cả spacing.
7. **Cho phép xuống dòng khi cần:** với tên dài/font lớn, xử lý cục bộ bằng wrap/FlowRow phù hợp thay vì thu nhỏ toàn bộ chữ hoặc làm mất thông tin.

Nếu thông tin bổ sung đã có trong state nhưng chưa hiển thị, chỉ xem xét khi nó giúp quyết định trực tiếp và còn chỗ trong cấu trúc hiện tại. Không thêm request backend hoặc tự tạo chỉ số mới trong đợt hoàn thiện phong cách.

## 7. Phạm vi theo màn hình

| Khu vực | Công việc thực hiện |
|---|---|
| Trang chủ | Theme của app bar, navigation, tab, tìm kiếm, FAB và thông báo; giữ nguyên các vị trí |
| CampaignCard/MyAppCard | Màu nền, viền, bo góc theo cùng loại, badge và trạng thái; giữ bố cục gọn và đủ số liệu |
| DailyTaskCard/Quest | Màu tiến độ, nền trạng thái, nút và phần thưởng; giữ nội dung và kích thước hiện tại |
| Chi tiết tester/chủ app | Đồng bộ card, nút, field, divider, trạng thái; giữ thứ tự nội dung và hành động |
| Báo cáo và so ảnh | Đồng bộ chrome, bề mặt và nút; giữ công cụ ảnh, retry và quy tắc duyệt |
| Form đăng app/tạo nhiệm vụ | Đồng bộ field, focus, error, nút và dialog; giữ form và validation |
| Hồ sơ/xếp hạng/admin/màn phụ | Áp dụng cùng bảng màu và phong cách component; giữ typography, thông tin và hành vi |

## 8. Cách triển khai theo đợt nhỏ

### Đợt A — Baseline và theme

- Lưu ảnh hiện trạng của màn tiêu biểu ở sáng/tối, cùng thiết bị, dữ liệu và font scale để so sánh.
- Hoàn thiện ColorScheme và màu trạng thái trên nền xanh đang dùng.
- Giữ nguyên Type.kt và các thông số layout.
- Đối chiếu ảnh sau đổi theme để phát hiện những vùng bị đổi ngoài ý muốn.

### Đợt B — Mẫu nhỏ trên card hiện có

- Áp dụng cho CampaignCard và MyAppCard; sửa đúng phần màu/bề mặt/viền/shape cần đồng bộ.
- Giữ đầy đủ dữ liệu và bố cục hiện tại, kể cả các pill.
- So sánh cùng viewport: chiều cao card và số thông tin nhìn thấy không được kém baseline do trang trí.
- Dùng kết quả này làm quy chuẩn cho các component khác. Không dựng lại Trang chủ theo hai bản phác thảo trước.

### Đợt C — Đồng bộ phần còn lại

- Áp cùng quy tắc lên nhiệm vụ, nút, badge, field, navigation, sheet/dialog và các màn chi tiết.
- Ưu tiên thay màu dùng chung; chỉ tách helper/composable nhỏ khi thật sự giảm trùng lặp.
- Mỗi diff có phạm vi hẹp để dễ kiểm tra; không refactor các màn lớn như điều kiện tiên quyết.

### Đợt D — Kiểm tra và tinh chỉnh mật độ

- So sánh ảnh trước/sau và kiểm tra tương phản, trạng thái tương tác.
- Chỉ tinh chỉnh căn hàng/wrap/khoảng trống ở vị trí có lợi rõ ràng cho lượng thông tin và khả năng đọc.
- Chạy build/lint phù hợp; kiểm tra các luồng có component được sửa.

Không áp lại ước lượng 10–15 ngày của phạm vi thiết kế lại trước. Ước lượng cho công việc hoàn thiện phong cách cần dựa trên diff theme/card mẫu và số ngoại lệ màu thực tế phải xử lý.

## 9. Tiêu chí nghiệm thu

- Người dùng vẫn nhận ra ngay giao diện hiện tại: cùng chữ, tiêu đề, font, vị trí chức năng và thứ tự nội dung.
- Tất cả số liệu/trạng thái đang có vẫn xuất hiện và giữ nguyên ý nghĩa; không chuyển bớt vào màn chi tiết vì lý do thẩm mỹ.
- Cùng viewport/dữ liệu/font scale, số card và lượng thông tin nhìn thấy ít nhất tương đương baseline; nếu mẫu thấp hơn do trang trí, cần sửa lại trước khi nhân rộng.
- Các component cùng loại có màu, bề mặt, bo góc, viền và phản hồi nhất quán.
- Sáng/tối đều đọc rõ. Kiểm tra chữ tối thiểu 4.5:1 và thành phần phi văn bản cần nhận biết 3:1 theo [hướng dẫn Android](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility).
- Vùng bấm kiểm tra tối thiểu 48dp cho phần tử tương tác; không ép các badge chỉ hiển thị có chiều cao 48dp. Tham chiếu [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
- Kiểm tra màn 320/360/412dp, VI/EN, tên dài, số lớn và font scale 1.0/1.3/2.0; sửa lỗi cục bộ nếu phát hiện.
- Ripple, focus, selected, disabled và loading đúng trạng thái; không đổi quyền hoặc điều kiện gửi/duyệt.
- Không thêm request, tải ảnh đầy đủ, thay cache hoặc thay pipeline upload do sửa phong cách.
- Không cần thêm test chỉ để xác nhận màu/padding. Dùng ảnh đối chiếu cho phần hình thức và kiểm tra hành vi hiện có cho component được sửa.

## 10. Trạng thái bàn giao

Đã sửa theme, màu trạng thái, DailyTaskCard, CampaignCard, MyAppCard, CreditTopupBadge và các bề mặt/trạng thái liên quan trên HomeScreen theo mẫu đã chốt. Có sửa đồng bộ resource tiếng Anh trong quá trình xử lý lỗi lint; không nâng dependency hay thay logic dữ liệu/nghiệp vụ. Build APK debug thành công; 184 unit test qua; lint không còn lỗi, còn cảnh báo. Chưa chạy app Android để nghiệm thu hình ảnh trên thiết bị. Chi tiết ở `BAO_CAO_GIAO_DIEN_MATERIAL_3_2026-10-28.md`.
