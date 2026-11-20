# Rà soát Material Design và kế hoạch hoàn thiện giao diện gọn

Ngày rà soát: 30/10/2026. Phạm vi: mã nguồn hiện có trong workspace, bao gồm các thay đổi chưa commit. Đây là báo cáo nghiên cứu và kế hoạch; chưa sửa giao diện.

## 1. Kết luận

**Ứng dụng đã có nền tảng và các màn hình dùng Material Design 3, nhưng cách dùng màu, viền và nền chưa đồng bộ toàn dự án.** Không cần chuyển framework hoặc thiết kế lại các trang.

- Android dùng Jetpack Compose Material 3: dependency tại `app/build.gradle.kts:113`, theme sáng/tối tại `ui/theme/Theme.kt:11–100`, bảng màu trạng thái tại `ui/theme/StatusColors.kt`.
- `MainActivity.kt:128` bọc nội dung bằng `TestCheoTheme`; các màn trong navigation và các màn chặn cập nhật/thiếu cấu hình cùng nằm trong phạm vi theme này.
- Trang chủ, CampaignCard, MyAppCard và DailyTaskCard đã được làm gọn ở đợt 28/10. Cần giữ thành quả đó, không áp lại spacing lớn hơn.
- Các màn chi tiết, nhiệm vụ, phát hành cập nhật, tài khoản bị khóa và một số sheet/dialog vẫn phối màu trạng thái cục bộ, có chỗ chưa phù hợp chế độ tối hoặc tương phản chưa đủ.
- `privacy_policy.html` là trang HTML tự định kiểu, dùng bảng màu xanh dương riêng; chưa thống nhất với ngôn ngữ màu của app Android.

Không thể kết luận “toàn bộ giao diện đã đạt chuẩn” chỉ từ việc có import Material 3. Ngược lại, màu viết trực tiếp, gradient hoặc component tùy biến cũng không tự động là sai Material. Cần đánh giá vai trò màu, tương phản và trạng thái thực tế.

## 2. Phạm vi đã kiểm tra và giới hạn

Đã lập danh mục toàn bộ 16 tệp trong `ui/screens` (15 tệp chứa giao diện và `GoogleSignIn.kt` là helper), 14 tệp trong `ui/components`, 11 route trong `NavGraph.kt`, năm tab Trang chủ, theme và các màn khởi động trong `MainActivity.kt`. Các đường dẫn Kotlin rút gọn trong báo cáo nằm dưới `app/src/main/java/com/ntt/crosstest/`.

Đồng thời rà soát các trang HTML trong repo và phần backend/tools để xác định phạm vi giao diện. Không thấy frontend quản trị web riêng; quản trị hiện nằm trong Android.

Phân loại HTML:

| Tệp/nhóm | Vai trò | Cách xử lý |
|---|---|---|
| `privacy_policy.html` | Trang chính sách của dự án | Có trong kế hoạch đồng bộ màu và giảm khoảng trắng phù hợp |
| `testing_page.html` | Bản HTML lưu từ trang đăng nhập Google; có base URL `accounts.google.com/v3/signin/` | Không coi là trang sản phẩm cần thiết kế lại; không tìm thấy tham chiếu sử dụng trong Android/README/backend đã tìm |
| `googled7d0c32e53e51571.html` | Tệp xác minh Google | Giữ nguyên |
| HTML/CSS dưới `audit/`, `build/`, `app/build/` | Báo cáo/kết quả công cụ | Không đưa vào phạm vi thay phong cách sản phẩm |

Đây là rà soát tĩnh mã nguồn và đối chiếu tài liệu chính thức. Chưa chạy app, chụp baseline hoặc đo chiều cao hàng trên thiết bị trong lượt này; chưa xác nhận trang HTML nào đang được triển khai công khai. Các mức spacing đề xuất bên dưới là ứng viên cần kiểm chứng, không phải kết quả đo hình ảnh. Không chạy build/test vì lượt này chỉ thêm tài liệu.

Báo cáo ngày 28/10 được dùng để hiểu lịch sử, không thay cho source hiện tại. Ví dụ báo cáo cũ ghi CreditTopupBadge đã dùng primaryContainer, nhưng source hiện tại còn nền vàng cố định ở `CreditTopupBadge.kt:78`.

## 3. Ràng buộc bắt buộc khi sửa

1. **Khoảng cách giữa các hàng chỉ được giảm hoặc giữ nguyên.** Nếu không thể giảm mà vẫn đọc rõ và thao tác đúng, giữ nguyên và chỉ đổi màu sắc, viền, nền.
2. Giữ font, cỡ chữ, độ đậm, line-height, nội dung, số liệu, nhãn, thứ tự khu vực, điều hướng và hành vi hiện tại. Không ẩn dữ liệu để làm hàng thấp hơn.
3. Giữ padding ngang, kích thước icon/ảnh và cấu trúc hàng. Đợt này ưu tiên giảm `verticalArrangement`, spacer dọc hoặc padding dọc dư; không gom lại nội dung thành bố cục khác.
4. Không thêm hero, tiêu đề, wrapper có padding, chip tương tác lớn hoặc khoảng trống mới. Không nâng thư viện hay chuyển sang Material 3 Expressive chỉ để đổi diện mạo.
5. Không giảm chiều cao nút/field, vùng chạm, insets bàn phím, thanh hệ thống hoặc khoảng tránh FAB. Không tắt cơ chế bảo đảm vùng chạm của Material để đạt độ gọn.
6. So cùng dữ liệu, chiều rộng, ngôn ngữ và font scale: hàng/card không được cao hơn baseline, lượng thông tin nhìn thấy không được giảm. Nếu ứng viên không đạt, trả spacing về baseline và giữ riêng phần sửa màu/viền/nền.
7. Giữ nguyên hình học bo góc, elevation và hiệu ứng hiện tại trong đợt này. Phân lớp bằng bề mặt và viền mảnh có sẵn, không thêm trang trí chiếm chỗ.

## 4. Các phát hiện cần ưu tiên

### P1 — Màu trạng thái chưa dùng chung trên các màn

- `CampaignDetailScreen.kt:1213–1233`: nền xanh nhạt và chữ xanh đậm cố định; `:1331–1441`: các thẻ cam/xanh dùng bộ màu riêng; `:1476–1483`: trạng thái production/completed/pending có bảng màu cục bộ. Sheet nộp báo cáo cũng còn bộ màu tương tự tại `:2479–2502`, `:2604–2611`.
- `OwnerAppDetailScreen.kt:370–443`: banner xanh/vàng cố định; `:2261–2326`: badge duyệt/từ chối/trùng ảnh dùng các cặp màu riêng.
- `AccountLockedScreen.kt:244–338`: lỗi và cảnh báo dùng đỏ/vàng/nâu cố định. `AppUpdateReleaseScreen.kt:403–465`, `:610–625`, `:746–747`: trạng thái cập nhật và Credit cũng còn màu cục bộ.
- `NotificationBottomSheet.kt:405–436`: bảy loại thông báo dùng màu icon/nền cố định. Không cần gộp bảy loại thành ba loại; ánh xạ từng loại sang cặp màu theo theme và giữ nhãn/icon hiện tại.
- `QuestSection.kt:62–65`, `CreditTopupBottomSheet.kt:256–285`, `CreditRewardOverlay.kt:181–195`: còn bảng màu riêng. Chỉ hợp nhất các màu cùng ý nghĩa; màu nhận diện có chủ đích được giữ khi tương phản đạt.

Hướng sửa: dùng `MaterialTheme.statusColors` cho success/warning/info, `errorContainer/onErrorContainer` cho lỗi, `surfaceContainer*/onSurface` cho nội dung trung tính. Đặt cặp container/content và màu viền cùng nơi; không chỉ đổi nền rồi giữ chữ cũ. Không ánh xạ màu theo mã hex một cách máy móc.

### P1 — Có cặp màu chữ cần sửa tương phản

`Color.kt:42–43` định nghĩa `QuestGoldTextLight = #F57F17`, `QuestGoldBgLight = #FFF8E1`. `QuestsScreen.kt:610–619` chọn chúng và `:673–685` dùng màu vàng/cam cho chữ tiến độ khi có thể nhận thưởng.

Tính tương phản sRGB của hai mã màu đục này được khoảng **2,49:1**. Đây là phép tính từ source, chưa phải phép đo screenshot. Đề xuất giữ nền vàng nhẹ, đổi màu chữ sang vai trò warning đủ đậm; bộ warning sáng hiện có (`#795000` trên `#FFF0D4`) là ứng viên phù hợp để kiểm tra. Không đổi cỡ chữ hoặc chiều cao hàng.

Rà soát thêm chữ/spinner `Color.White` trong các nút tùy biến: chọn màu nội dung theo container và trạng thái enabled/disabled/loading; ưu tiên `LocalContentColor.current` khi phù hợp. Không thay toàn bộ màu trắng, vì trình xem ảnh và lớp phủ trên ảnh có nền tối chủ đích.

### P2 — Nền startup và các bề mặt còn nhiều cách pha alpha

- `MissingConfigScreen.kt:22–40`, `MainActivity.kt:291–295`: nội dung không tự vẽ một Surface nền. Nền cửa sổ XML có màu riêng (`res/values/colors.xml`, `res/values-night/colors.xml`), hơi khác bảng Compose.
- Nhiều card dùng `surfaceVariant.copy(alpha = ...)` với các mức khác nhau. Đây không phải lỗi tự thân, nhưng làm kết quả phụ thuộc nền bên dưới.

Hướng sửa: dùng nền `surface`/`background` và cặp nội dung tương ứng một cách rõ ràng cho màn startup, không thêm padding. Chọn vai trò `surfaceContainerLow`/`surfaceContainer` phù hợp cho nhóm card trung tính sau khi so ảnh; chỉ thay các trường hợp thực sự cần đồng bộ. Không đổi hàng loạt toàn bộ theme đã hoàn thiện.

### Đã kiểm tra và không xác nhận là lỗi

Các lệnh `isSystemInDarkTheme()` trong QuestHeroBanner, QuestsScreen, CreateCampaignScreen và CreditRewardOverlay **không phải bằng chứng app bỏ qua lựa chọn theme**. `MainActivity.kt:98–107` đã cập nhật `uiMode` theo lựa chọn, rồi cung cấp `LocalConfiguration` tại `:119–128`. Giữ cơ chế đang hoạt động; có thể chuyển các bộ màu trùng nghĩa vào theme cho dễ bảo trì, nhưng không tạo hạng mục “sửa lỗi chọn sai dark mode” khi chưa có bằng chứng.

`themes.xml` kế thừa `android:Theme.Material.NoActionBar` chỉ là theme cửa sổ của Activity; nội dung app là Compose Material 3. Không cần đổi parent XML như một bước chuyển toàn app sang M3.

## 5. Danh mục màn hình và ứng viên thu gọn

Mỗi giá trị A → B bên dưới chỉ áp dụng tại vị trí nêu, không phải quy tắc thay thế toàn dự án. Nếu kiểm chứng không đạt ràng buộc mục 3 thì giữ A.

| Màn/khu vực | Đánh giá từ source | Đề xuất khoảng cách dọc | Màu/viền/nền cần xem |
|---|---|---|---|
| Trang chủ / Khám phá | Đã dùng M3 và đã gọn | Giữ gap danh sách 6dp (`HomeScreen:829`) | Chỉ đồng bộ màu dialog cảnh báo còn cố định ở `:209–246` |
| Của tôi | Đã dùng M3 và đã gọn | Giữ gap 8dp (`HomeScreen:1088`) | Giữ nền tonal hiện có; sửa ngoại lệ nếu so ảnh thấy cần |
| Xếp hạng | Đã dùng theme | Giữ gap 8dp (`HomeScreen:1316`) | Kiểm tra selected/current-user và viền ở sáng/tối; không thiết kế lại |
| Nhiệm vụ | M3 với nhiều màu riêng | Gap trang 14 → 10–12dp (`QuestsScreen:120`); padding dọc QuestItemCard 12 → 8–10dp (`:639`) | Ưu tiên chữ tiến độ, badge và trạng thái nhiệm vụ; giữ khoảng nội dung 2–4dp đã nhỏ |
| Hồ sơ thành viên/khách | Phần lớn dùng theme | Gap chính 12 → 8–10dp (`ProfileScreen:629`), nhánh khách 16 → 12dp (`:534`); SettingsSection gap 10 → 8dp (`:1090`) nếu vùng chạm vẫn đạt | Màu slot thành công/hết hạn; giữ màu nhận diện Zalo/Facebook |
| Chi tiết app cho tester | M3 nhưng trạng thái chưa đồng bộ | Gap trang 14 → 10–12dp (`CampaignDetailScreen:1111`); giữ nội dung nội bộ 2–8dp | Banner, Credit, báo cáo, trạng thái cập nhật và nút theo mục 4 |
| Chi tiết app cho chủ | M3 nhưng trạng thái chưa đồng bộ | Gap trang 12 → 10dp (`OwnerAppDetailScreen:320`); danh sách báo cáo 14 → 10–12dp (`:2093`) | Banner và badge duyệt/từ chối/trùng ảnh |
| Phát hành cập nhật app | M3, màu trạng thái cục bộ | Gap trang 16 → 12dp (`AppUpdateReleaseScreen:168`); gap form 12 → 10dp (`:521`) | Trạng thái release, ngân sách giữ lại/hoàn tiền; giữ các dòng ngân sách 4–6dp |
| Đăng app | Phần lớn M3 | Gap chính 16 → 12dp (`CreateCampaignScreen:295`); giữ gap nhãn/field 4dp | Hướng dẫn, slot, success/warning; giữ vùng ảnh và trường nhập |
| Tạo nhiệm vụ | Phần lớn M3 | Gap chính 14 → 10–12dp (`MissionCreateScreen:126`); giữ gap nội bộ 6–8dp | Màu loading theo nút; giữ nút 50dp và không thu nhỏ stepper |
| Hướng dẫn đăng app | Đã dùng theme | Gap trang 16 → 12dp (`PostAppGuideScreen:147`), nhóm 12 → 10dp nếu cần | Chỉ chuẩn hóa bề mặt/viền khi cần; không cắt đoạn hướng dẫn |
| Đăng nhập | Đã dùng theme | Thử spacer 24 → 16dp (`AuthScreen:211`); giữ khoảng hàng form và nút | Giữ cách phối màu đã theo theme |
| Quản trị người dùng | Đã dùng theme; không thấy literal màu trực tiếp trong tệp | Gap danh sách 10 → 8dp (`AdminUsersScreen:588`), gap trong card 10 → 8dp (`:626`) | Kiểm tra nền tài khoản khóa, admin, selected; không đổi cấu trúc quản trị |
| Quản trị báo cáo liên hệ | Đã dùng theme; không thấy literal màu trực tiếp trong tệp | Gap danh sách 10 → 8dp (`AdminReportsScreen:196`), gap card 10 → 8dp (`:235`) | Giữ phân biệt mới/đã trả lời/đã xử lý; kiểm tra tương phản |
| Tài khoản bị khóa | M3 với nhiều màu riêng | Gap trang 18 → 12–14dp (`AccountLockedScreen:237`); giữ các nhóm 10dp nếu nút chiếm chiều cao | Error/warning/info và hành động hỗ trợ |
| Bắt buộc cập nhật / dialog cập nhật | Đã dùng M3, còn success cục bộ | Giữ spacing trước; không bắt buộc phải nén màn chặn cập nhật | Đồng bộ trạng thái thưởng ở `UpdateScreens:168–175` và cặp nội dung/nền |
| Thiếu cấu hình / loading | Có theme; cần nền rõ ràng | Giữ nguyên | Đặt nền và màu nội dung phù hợp theme, không thêm wrapper có padding |

## 6. Thành phần dùng chung, sheet và dialog

| Tệp/nhóm | Kế hoạch |
|---|---|
| `CampaignCard.kt` | Giữ padding ngang/dọc 10/8dp, gap 2dp, badge 6/2dp; không nén thêm ở đợt này |
| `MyAppCard.kt` | Giữ padding 10dp, khoảng badge 6dp ngang/4dp dọc; giữ đủ số liệu |
| `DailyTaskCard.kt` | Giữ cấu trúc gọn và padding dọc 8/10dp theo nhánh; không giảm vùng nút hay nhánh hỗ trợ font lớn |
| `CreditTopupBadge.kt` | Giữ kích thước hiện có; kiểm tra cặp vàng/chữ và vùng chạm trước, không giảm badge đang cao 34dp |
| `QuestHeroBanner.kt` | Thử padding dọc 18 → 12–14dp (`:117`), spacer 16 → 10–12dp (`:161`); giữ padding ngang và icon; đồng bộ màu/gradient theo vai trò, không thêm trang trí |
| `NotificationBottomSheet.kt` | Giữ gap danh sách 8dp và nội dung 4dp; nếu baseline còn dư, thử padding dọc item 12 → 10dp (`:302`), giữ ngang 12dp; đồng bộ màu từng loại thông báo |
| `CreditTopupBottomSheet.kt` | Giữ kích thước field/nút; thử padding dọc gói 12 → 10dp (`:359`) nếu không ảnh hưởng vùng chạm; sửa cặp màu cho selected, popular và loading |
| `AdminDailyTaskPolicySheet.kt` | Đã dùng M3; gap chính 16 → 12dp (`:167`); giữ khoảng 4–8dp trong thông tin/lịch sử và mọi navigationBarsPadding/IME insets |
| `CreditRewardOverlay.kt` | Giữ kích thước/nhịp hiển thị; đồng bộ màu thưởng/nạp và viền, không thay hiệu ứng hoặc thời gian |
| `ImageViewerComponents.kt` | Giữ toàn bộ diện tích ảnh, zoom, slider, chế độ so sánh và nền tối của vùng xem ảnh; chỉ chuẩn hóa nền/viền/chữ badge kết quả ở `:659–687` |
| Sheet nộp báo cáo trong `CampaignDetailScreen.kt` | Gap chính 14 → 10–12dp (`:2402`); giữ vùng ảnh, picker, field, nút gửi, bàn phím và retry; đồng bộ trạng thái success/warning/error |
| Dialog tester/duyệt/từ chối trong `OwnerAppDetailScreen.kt` | Giữ bảng ảnh và hành động; giảm riêng gap danh sách như mục 5; sửa màu badge/cảnh báo |
| Dialog Credit, slot, ngôn ngữ, theme, mật khẩu và liên hệ trong `ProfileScreen.kt` | Giữ gap danh sách 4–8dp đã nhỏ; ưu tiên màu slot. Nút slot cao 34dp (`:1942`, `:1958`) phải kiểm tra vùng chạm thực tế; không giảm thêm |
| `AnimatedCreditBalance.kt`, `AppIcon.kt` | Không thấy nhu cầu thay bố cục; giữ nguyên, chỉ đối chiếu khi theme đổi |
| `QuestSection.kt`, `ProgressSection.kt` | Không tìm thấy call site trong `app/src/main` ngoài định nghĩa. Không tốn công nén thành phần chưa được gọi; đồng bộ bảng màu nếu tiếp tục giữ để tái sử dụng, hoặc quyết định dọn source trong công việc riêng |

## 7. Trang chính sách HTML

`privacy_policy.html:12–29` có CSS variables riêng, thuận tiện đổi màu mà không thêm thư viện. Hướng đề xuất:

- Ánh xạ primary/container/surface/onSurface/outline sang bảng xanh của Android; sửa màu nền header bảng đang gán trực tiếp tại `:239–241`.
- Giữ font, line-height 1.7, cỡ chữ, nội dung song ngữ và chức năng đổi ngôn ngữ.
- Thử padding dọc ô bảng 12 → 8px (`:235`), giữ ngang 14px; margin giữa đoạn 14 → 10–12px (`:150`), giữa mục 36 → 24–28px (`:134`). Không tăng bất kỳ khoảng hàng nào.
- Giữ responsive hiện có. Nếu bổ sung màu tối, chỉ thêm token màu theo chế độ và kiểm tra tương phản; không thêm công tắc hay luồng mới chỉ cho mục đích phong cách.
- Nếu nội dung dài mất nhịp đọc sau khi nén, giữ spacing cũ và chỉ dùng màu, viền, nền mới. Việc đồng bộ này không cần chuyển trang thành ứng dụng Material Web.

## 8. Thứ tự triển khai

1. **Chốt baseline:** lưu ảnh cùng dữ liệu cho năm tab, các màn chi tiết, form, quản trị và sheet/dialog liên quan; ghi gap/padding hiện tại. Dùng snapshot của workspace hiện tại vì đang có nhiều sửa đổi chưa commit.
2. **Sửa màu có bằng chứng:** cặp màu vàng ở Nhiệm vụ; success/warning/info/error ở chi tiết, báo cáo, khóa tài khoản và cập nhật. Tận dụng `StatusColors.kt` hiện có; mở rộng token khi thực sự có vai trò mới.
3. **Sửa các bề mặt dùng chung:** thông báo, nạp Credit, thưởng, badge so ảnh và nền startup. Giữ cấu trúc, padding và luồng.
4. **Thu gọn theo từng nhóm nhỏ:** ưu tiên Nhiệm vụ → hai màn chi tiết/báo cáo → form → Hồ sơ/quản trị. Áp các ứng viên mục 5–6, so ảnh ngay từng nhóm. Không tự động thay mọi 16dp thành 12dp.
5. **Đồng bộ HTML chính sách:** thực hiện sau Android, theo cùng token và quy tắc không tăng khoảng cách.
6. **Nghiệm thu:** hoàn thành bảng đo trước/sau, xác nhận các luồng liên quan và cập nhật báo cáo phạm vi đã làm; giữ rõ hạng mục chưa có ảnh kiểm chứng.

Không sửa repository/ViewModel, upload/cache ảnh, RPC, phần thưởng, điều kiện kiểm duyệt hoặc resource nội dung trong đợt phong cách này. Không triển khai website hoặc phát hành APK chỉ từ kế hoạch này.

## 9. Kiểm chứng và điều kiện hoàn tất

- So ảnh trước/sau ở sáng/tối, chế độ app khác chế độ hệ thống, VI/EN; màn rộng 320/360/412dp, font scale 1.0/1.3/2.0, tên dài, số lớn, danh sách rỗng/lỗi/loading.
- Với cùng điều kiện, ghi chiều cao các item đại diện, gap giữa item và lượng nội dung nhìn thấy. Mọi gap phải nhỏ hơn hoặc bằng baseline; card/hàng không cao hơn do thay đổi thiết kế. Không chấp nhận chữ bị cắt thêm để đạt số đo.
- Giữ nguyên cỡ chữ/line-height. Nội dung được phép wrap theo cơ chế sẵn có; nếu ứng viên spacing gây lỗi thì hoàn nguyên ứng viên đó.
- Kiểm tra tương phản chữ thường tối thiểu 4.5:1, thành phần phi văn bản cần nhận biết 3:1 theo [hướng dẫn accessibility Android](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility). Đo màu sau compositing với alpha, không chỉ so token gốc.
- Kiểm tra vùng chạm thực tế tối thiểu 48dp cho điều khiển; badge thuần hiển thị không cần cao 48dp. Thành phần hiển thị 34/44dp chưa đủ căn cứ kết luận vùng chạm nhỏ, vì Compose có thể mở rộng vùng tương tác. Nếu không đủ chỗ bảo đảm thao tác, giữ mật độ hiện tại và ghi vấn đề riêng, không nén thêm. Tham chiếu [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
- Kiểm tra selected, focused, disabled, pressed, loading và lỗi; chủ app/tester/guest/admin; pending/approved/rejected, app chưa cài/đã cài/cập nhật và các nhánh miễn chỉ tiêu.
- Sau khi có sửa Kotlin/resource, chạy `:app:assembleDebug :app:lintDebug`; dùng kiểm thử hành vi hiện có phù hợp với phần chạm tới. Không thêm unit test chỉ để chép lại mã màu/padding. Không dùng kết quả test/build ngày 28/10 thay cho kiểm chứng source mới.
- HTML: kiểm tra VI/EN, mobile/desktop, bảng và đổi ngôn ngữ; nội dung chính sách không thay đổi.

Cơ sở thiết kế: [Material Design 3 trong Compose](https://developer.android.com/develop/ui/compose/designsystems/material3). Material cho phép tùy biến theo nhận diện; kế hoạch chọn hoàn thiện bảng màu và bề mặt hiện có, đồng thời giữ hoặc tăng mật độ thông tin theo yêu cầu của chủ dự án.
