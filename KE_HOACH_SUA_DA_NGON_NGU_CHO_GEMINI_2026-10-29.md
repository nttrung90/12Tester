# Kiểm tra đa ngôn ngữ Việt/Anh và kế hoạch sửa cho Gemini

Ngày: 29/10/2026. Phạm vi: working tree tại `F:\AI\12Tester`, HEAD lúc kiểm tra `34f0ddb811e5397774105c2dd905d052511e59ec`, bao gồm thay đổi chưa commit.

## 1. Kết luận và giới hạn kiểm tra

**Có lỗi trộn ngôn ngữ trong mã hiện tại, ở cả hai chiều.** Nguyên nhân gồm chuỗi Kotlin viết cố định, bản dịch Việt còn nhãn tiếng Anh, hiển thị nguyên thông báo máy chủ, và lưu câu đã dịch vào dữ liệu dùng chung. Cần kiểm tra thêm cơ chế đổi locale và chuỗi đã giữ trong ViewModel khi đổi ngôn ngữ.

Đã đọc source Android, tài nguyên XML, các model/RPC liên quan và SQL trong repo. Chưa chạy ứng dụng trên thiết bị/emulator, chưa kiểm tra APK đang cài, chưa truy cập Supabase/Firebase production. Các dòng dẫn chứng là vị trí trong working tree lúc kiểm tra, có thể dịch chuyển khi Gemini sửa.

Trong lượt này chỉ bổ sung tài liệu và bằng chứng kiểm tra. Không sửa logic ứng dụng, không chạy migration hoặc deploy. Repo đã có thay đổi của các công việc khác: giữ nguyên, không reset/checkout đè.

### Kết quả kiểm tra tài nguyên

| Kiểm tra | Kết quả |
|---|---|
| `values/strings.xml` | 1.127 khóa string, không trùng |
| `values-en/strings.xml` | 1.127 khóa string, không trùng |
| `values-vi/strings.xml` | 1.127 khóa string, không trùng |
| Thiếu khóa VI/EN so với default | 0 |
| Nội dung default khác explicit EN | 0 |
| Chữ ký placeholder giữa các bản dịch | Không thấy lệch trong kiểm tra tĩnh, có bỏ qua `%%` |
| Khóa EN/VI có nội dung giống hệt | 28; cần phân loại, không phải cả 28 đều lỗi |

Bằng chứng có số lượng, danh sách khóa giống nhau và SHA256 source tại `audit/localization-2026-10-29/resources-and-source.json`. Kiểm tra placeholder tĩnh không thay cho việc gọi formatter Android với tham số thực tế.

`LOCALIZATION_PLAN.md` và `LOCALIZATION_AUDIT_REPORT.md` là tài liệu ngày 16/10. Kế hoạch cũ ghi đã hoàn tất client và **chủ động giữ nội dung máy chủ bằng tiếng Việt**. Quyết định đó không đáp ứng yêu cầu hiện tại; không dùng các dấu tick cũ để kết luận bản hiện nay đã hỗ trợ đầy đủ hai ngôn ngữ.

## 2. Danh sách vấn đề và bằng chứng

### L01 — P1: phần thưởng cập nhật phiên bản còn câu tiếng Việt cố định

**Xác nhận qua mã nguồn, có luồng hiển thị.**

- `ui/screens/AppUpdateReleaseScreen.kt:413`: `Phiên bản: ... (mã: ...)`.
- Cùng file `:749`: dòng lịch sử `... nhận • Đã chi: ... • Hoàn: ...`, trong đó có nhánh hoàn tiền lồng trong chuỗi.
- `ui/viewmodels/AppUpdateReleaseViewModel.kt:115,155,162,171,196,203,212`: thông báo tải, áp dụng phiên bản, kết thúc đợt và lỗi vẫn viết tiếng Việt; một số ưu tiên `result.message` hoặc `e.localizedMessage`.
- `ui/viewmodels/CampaignDetailViewModel.kt:1160,1169,1178`: thông báo nhận thưởng cập nhật và lỗi xác nhận.

**Tái hiện cần thực hiện:** chọn English → App của tôi → công bố phiên bản → xem đợt/lịch sử, thử công bố/kết thúc/nhận thưởng, lỗi mạng và lỗi nghiệp vụ. Kiểm tra cả trường hợp có/không có Credit hoàn.

**Sửa:** đưa toàn bộ câu vào resource ba bộ; dùng template có placeholder, tách biến thể có/không hoàn tiền hoặc ghép các phần đã dịch đúng cấu trúc. Tái sử dụng `Localizer` đã được tiêm vào ViewModel. Không đổi số Credit, điều kiện thưởng hoặc idempotency của RPC.

### L02 — P1: lỗi và kết quả RPC hiển thị nguyên ngôn ngữ backend

**Xác nhận qua mã nguồn.**

- `ui/viewmodels/AuthViewModel.kt:213` trở đi: `friendlyError()` map được một số mã, nhưng nhánh cuối vẫn trả `msg...take(200)`. `Exception.localizedMessage` cũng không tự dịch theo ngôn ngữ app.
- `ui/viewmodels/HomeViewModel.kt:1169,1188,1232,1235`: ưu tiên `res.message` khi nhận thưởng/nạp Credit. Các fallback đã dịch chỉ chạy khi server không có message.
- `ui/viewmodels/OwnerAppDetailViewModel.kt:365,379,392,421,646`: dùng lỗi hoặc message thô.
- `ui/screens/AccountLockedScreen.kt:97,154`: ưu tiên `e.localizedMessage`.
- L01 cũng có các đường thoát lỗi tương tự.

**Sửa:** dùng mã lỗi/kết quả ổn định và tham số để dựng câu ở client. Bổ sung mapper chung cho lỗi mạng, xác thực, quyền, nhiệm vụ, slot, báo cáo, cập nhật và đẩy app. Với success, lấy trạng thái + reward/refund/version từ response để dựng thông báo. Lỗi không nhận diện phải có thông báo dự phòng đúng locale; ghi chi tiết kỹ thuật vào log phù hợp, không đẩy nguyên exception vào toast/snackbar. Không đổi mã giao thức thành câu đã dịch.

Backend cũ chưa có code: hỗ trợ adapter tương thích giới hạn cho các mã/biến thể đã biết; fallback an toàn nếu không nhận diện. Không xây cơ chế dịch tùy tiện bằng thay từ hoặc đoán ngôn ngữ của exception.

### L03 — P1: thông báo, cảnh báo và lịch sử Credit bị cố định theo ngôn ngữ lúc tạo

**Xác nhận qua mã nguồn và schema, chưa kiểm tra dữ liệu production.**

- `ui/components/NotificationBottomSheet.kt:329,352`: hiển thị `notification.title/message` trực tiếp.
- `ui/screens/ProfileScreen.kt:1026`: `log.note ?: log.action`; khi thiếu note còn có thể lộ mã action tiếng Anh.
- `ui/screens/CampaignDetailScreen.kt:263`: `activeWarning.message` trực tiếp.
- `data/Models.kt:616` trở đi: `UserNotification` có `title`, `message`, `type`, `data`; `type` chỉ là nhóm rộng như credit/report/warning, chưa đủ xác định câu cụ thể.
- `supabase/migrations/009_notifications_system.sql:137` trở đi: trigger tạo tiêu đề/nội dung từ câu cố định và note. Cần tra định nghĩa hiệu lực sau toàn bộ migration, không chỉ sửa migration 009.
- Kế hoạch cũ cũng cho phép client ghi credit-log note theo locale lúc phát sinh: đổi locale về sau không thể cập nhật câu đã lưu.

**Thiết kế đề xuất:** sự kiện hệ thống lưu `event_code` hoặc `template_key` ổn định + `params` có kiểu (số Credit, tên app, phiên bản, mốc, thời gian...) và version của template nếu cần. Dựng tiêu đề/nội dung bằng resource tại lúc hiển thị. Có thể mở rộng JSON `data` của notification; credit logs/warnings cần trường metadata tương ứng. Giữ trường cũ để tương thích app cũ.

Với dữ liệu cũ: lập bảng ánh xạ action/event/template và backfill khi xác định chắc chắn từ trường hiện hữu. `type=credit` hoặc `amount` đơn lẻ không đủ phân biệt thưởng, hoàn, boa, thu hồi. Không được tự suy ra sai nghiệp vụ. Dữ liệu hệ thống không thể khôi phục đủ tham số: hiển thị mô tả tổng quát đã dịch và số liệu thật; bản gốc nếu cần xem phải nằm trong phần chi tiết có nhãn rõ, không trộn vào câu giao diện chính. Nội dung do người dùng/admin tự soạn được phân loại riêng theo mục 4.

**Đạt khi:** cùng một bản ghi hệ thống đổi VI → EN → VI hiển thị lại đúng ngôn ngữ, không tạo giao dịch/thông báo mới, không đổi số dư hoặc trạng thái đã đọc.

### L04 — P1: nhánh “Mặc định hệ thống” có hai nguồn locale khác nhau

**Xác nhận sự khác nhau trong code; hành vi trên thiết bị cần kiểm thử.**

- `MainActivity.kt:65` bọc base context theo ngôn ngữ đã lưu.
- `MainActivity.kt:94–109` dùng `LocalConfiguration.current` bên ngoài provider để resolve chế độ system, đồng thời gọi `Locale.setDefault(currentLocale)`.
- `data/SettingsRepository.kt:149–166` lại lấy `Resources.getSystem().configuration` cho system; `Localizer` và worker theo đường này.
- `ProfileViewModel.kt:338` chỉ lưu lựa chọn; không recreate Activity. Không dựa vào nhận định trong kế hoạch cũ rằng đổi ngôn ngữ luôn recreate.

**Ca rủi ro cụ thể:** thiết bị EN, lưu VI rồi khởi động lạnh → base context VI → chọn system. Configuration của Activity có thể vẫn là VI trong khi `Localizer` resolve EN. Thử cả chiều ngược lại.

**Sửa:** thống nhất một hàm resolve locale nhận lựa chọn + locale hệ thống thật; UI, `wrap()`, VM và worker cùng quy tắc. Không dùng locale đã override hoặc process default do chính app đổi làm nguồn hệ thống. Bảo đảm thay ngôn ngữ thiết bị khi app ở system được phản ánh khi quay lại app. Giữ cơ chế mirror DataStore/SharedPreferences nhất quán và chuẩn hóa giá trị ngoài `system/vi/en`.

Ưu tiên sửa nhỏ trên hạ tầng hiện có. Nếu chọn chuyển sang API ngôn ngữ ứng dụng của Android/AppCompat, phải chứng minh tương thích `ComponentActivity`, Compose, worker, bootstrap và các phiên bản Android; không đổi kiến trúc chỉ để thay API.

Đã đọc source Compose UI 1.12.1 có trong cache: `LocalResources` là computed local phụ thuộc `LocalConfiguration` và `LocalContext`; `stringResource` đọc từ đó. **Chưa có bằng chứng rằng thiếu một provider `LocalResources` riêng là lỗi.** Không thêm provider mù quáng. Giữ `LocalActivity` và `LocalActivityResultRegistryOwner` cho các API cần Activity thật.

### L05 — P2: câu đã dịch được giữ trong ViewModel/state qua lần đổi ngôn ngữ

**Rủi ro có cơ sở từ mô hình state; cần tái hiện trước/sau sửa.**

`Localizer.get()` resolve tại lúc gọi, nhưng nhiều `UiState` giữ `error`, `message`, `successMessage` kiểu `String`. Khi locale thay đổi, chuỗi cũ không tự dịch lại. Đổi context Compose không thay nội dung String đã giữ.

**Sửa:** state của thông điệp hệ thống nên giữ descriptor (`resource key/id + args`, hoặc `event/code + params`) và resolve lúc render. Phân biệt raw text người dùng nhập với thông điệp hệ thống. Nếu dùng phương án cập nhật state theo locale, phải áp dụng nhất quán và tránh lưu thêm hai nguồn nội dung. Không cần refactor mọi model một lần; bắt đầu ở các state có thể còn hiển thị khi quay về màn hình sau đổi ngôn ngữ.

Toast/snackbar mới sau khi đổi phải theo locale mới. Không phát lại thưởng, gọi lại RPC, upload lại ảnh hoặc làm mất form chỉ để dịch chữ. Không bắt buộc đổi câu của toast đã được hệ điều hành hiển thị trước thao tác đổi ngôn ngữ.

### L06 — P2: bản dịch Việt và trợ năng còn nhãn tiếng Anh

**Xác nhận qua XML và call site.**

Các khóa đang dùng có giá trị EN/VI giống hệt và cần dịch phần nhãn:

| Khóa | Hiện tại | Gợi ý tiếng Việt |
|---|---|---|
| `my_app_status_production` | 🚀 Production | 🚀 Đã phát hành |
| `slot_number_title` | Slot %1$d | Vị trí %1$d |
| `credit_reward_tester_amount_format` | Tester +%1$d Credit | Người kiểm thử +%1$d Credit |
| `credit_reward_tip_amount_format` | Tip +%1$d Credit | Tiền boa +%1$d Credit |
| `leaderboard_tab_credit` | Top Credit | Xếp hạng Credit |
| `leaderboard_tab_test` | Top Test | Xếp hạng kiểm thử |
| `img_compare_tester_format` | Tester: %1$s | Người kiểm thử: %1$s |

Rà các câu khác chứa Slot/Tester/Admin/Moderator/Closed Test/Report trong `values-vi`, thống nhất thuật ngữ theo ngữ cảnh; không chỉ xử lý 28 khóa giống nhau. Có thể giữ “Credit” là tên đơn vị điểm. Với slot, nên thống nhất “vị trí đăng ứng dụng” ở câu giải thích và “Vị trí” ở nhãn ngắn.

- `ui/screens/CreateCampaignScreen.kt:404`: `contentDescription = "Info"`.
- `ui/screens/AccountLockedScreen.kt:247`: `contentDescription = "Locked"`.

Dùng resource cho nhãn trợ năng có ý nghĩa. Icon trang trí thuần túy có thể dùng null nếu đã có nội dung thay thế phù hợp, không xóa nhãn khỏi nút cần TalkBack. Tên ngôn ngữ tự gọi `Tiếng Việt`/`English`, tên thương hiệu, URL, email không phải lỗi cần dịch.

### L07 — P2: lý do từ chối mặc định lưu câu đã dịch của người gửi

**Xác nhận qua mã nguồn.**

- `ui/screens/OwnerAppDetailScreen.kt:2540–2546`: dựng `predefinedReasons` bằng resource.
- Cùng file `:2608–2613`: gửi nguyên chuỗi lựa chọn vào `onConfirm`.
- Cùng file `:2490`: người nhận xem `report.rejectionReason` trực tiếp, nên không theo locale của người nhận.
- `ui/screens/CampaignDetailScreen.kt:993`: lý do admin xóa campaign vẫn cố định `Gỡ bỏ bởi Admin`.

**Sửa:** tách `reason_code` ổn định cho lựa chọn hệ thống, `reason_text` cho lời nhập tự do; render code theo locale người nhận. Giữ nguyên nội dung tự nhập. Hỗ trợ đọc dữ liệu cũ bằng danh sách đối chiếu chính xác các câu mặc định VI/EN đã biết; nếu không chắc là câu mặc định, coi là raw text. Cập nhật model/RPC và định nghĩa SQL hiệu lực với tương thích app cũ. Không dùng bản dịch làm khóa phân nhánh hoặc thay đổi kiểm tra quyền.

### L08 — P2: cấu hình thông báo cập nhật chỉ có một bản ngôn ngữ

**Xác nhận qua mã nguồn.** `ui/screens/UpdateScreens.kt:129,190,267,298` ưu tiên `config.title/message`, chỉ dùng resource khi null. Vì vậy cấu hình tiếng Việt sẽ che bản dịch Anh và ngược lại.

**Sửa:** phân biệt thông điệp chuẩn của hệ thống với nội dung tùy chỉnh. Cấu hình chuẩn dùng code + params; nếu admin tùy chỉnh thì hỗ trợ rõ trường VI/EN hoặc map localized, cập nhật cả form quản trị trong `AdminUsersScreen.kt` và model/RPC/cache. Chuỗi trắng coi như chưa có bản dịch. Thiếu bản dịch: dùng nội dung chuẩn đúng locale; không tự lấy nội dung ngôn ngữ kia rồi báo là đã hỗ trợ hai ngôn ngữ đầy đủ. Giữ các trường legacy cho client cũ.

### L09 — P3: mã ít dùng vẫn còn tiếng Việt cố định

- `ui/components/QuestSection.kt:140,150,262,337,473,475`: còn câu Việt. Tìm toàn `app/src/main/java` hiện chỉ thấy khai báo `QuestSection(`, chưa thấy call site; **không coi đây là nguyên nhân đã xác nhận trên màn Nhiệm vụ hiện tại**. Nếu giữ component thì localize, hoặc xác minh dead code rồi dọn riêng.
- `util/TopupConfig.kt:27–76`: tên gói và badge tiếng Việt; feature topup đang bị khóa theo tài liệu cũ, Gemini kiểm tra flag thực tế trước khi xếp ưu tiên. Nếu giữ tính năng thì dùng descriptor tên gói theo id hoặc trường bản dịch, không dùng tên dịch làm id giao dịch.
- `CampaignDetailViewModel.kt:493` có `Failed to load mission` trong context lỗi nội bộ. Luồng submit `:915` hiện map sang resource; chưa thấy câu này hiển thị trực tiếp, không ghi nhận là lỗi UI đã tái hiện. Chuẩn hóa code nếu sửa nhánh liên quan.

## 3. Thứ tự triển khai và đầu ra

### Giai đoạn A — Chốt baseline và sửa client dễ xác nhận

1. Đọc status/diff, tài liệu này và bằng chứng JSON; ghi lại source đang làm việc, không đè thay đổi khác.
2. Lập danh mục chuỗi theo luồng đang dùng: Auth, Home, Nhiệm vụ, chi tiết app, nộp báo cáo nền, App của tôi, quản lý nhiệm vụ, cập nhật phiên bản, hồ sơ/Credit, admin và màn cập nhật bắt buộc.
3. Sửa L01, L06; xử lý L09 theo mức dùng thực tế. Giữ đồng bộ ba XML, placeholder có vị trí `%1$s`, `%2$d`; tránh nối các mảnh câu cứng. Dùng plurals khi tiếng Anh cần số ít/nhiều.
4. Bổ sung mapper và thay các đường lộ raw message ở L02. Không hạ điều kiện lỗi nghiệp vụ thành thành công.

**Đầu ra:** diff client, bảng khóa đã đổi, test resource và mapper. Có thể bàn giao phần client trước nhưng chưa đánh dấu hoàn tất toàn bộ khi L03/L07/L08 còn tồn tại.

### Giai đoạn B — Nhất quán chuyển ngôn ngữ

5. Tái hiện L04 bằng khởi động lạnh và chọn system; hợp nhất nguồn locale.
6. Xử lý L05 ở thông điệp đang lưu trong state và cache. Cache nên giữ dữ liệu/event, dựng bản dịch khi render. Nếu cache chứa bản dịch thì loại bỏ bản dịch cũ hoặc có khóa locale đúng; không xóa dữ liệu giao dịch/người dùng.
7. Worker/notification mới dùng locale mới. Kiểm tra tên/mô tả channel sau đổi ngôn ngữ; giữ channel id để không mất tùy chọn thông báo. Notification đã đăng trước đó chỉ cần refresh nếu đặc tả sản phẩm yêu cầu; notification mới và tác vụ nền đang cập nhật phải dùng locale hiện hành.

**Đầu ra:** test chuyển locale, test giữ form/navigation, bằng chứng UI và notification.

### Giai đoạn C — Nội dung hệ thống từ server và lịch sử

8. Kiểm kê toàn bộ producer notification/credit log/warning và RPC trả message từ định nghĩa SQL hiệu lực. Repo hiện đến migration 054, có cả `supabase/full_schema_singapore.sql`; chọn số migration tiếp theo còn trống tại lúc làm, không sửa lịch sử migration đã triển khai.
9. Triển khai contract tương thích cho L03, L07, L08. Cập nhật snapshot schema theo quy ước repo, model Kotlin, repository, serializer/cache và form admin liên quan.
10. Viết migration/backfill có thể chạy lại theo thiết kế, bảo toàn id, số dư, amount, action, trạng thái đã đọc, quyền/RLS, chức năng RPC và dữ liệu người dùng. Giữ raw legacy khi cần tương thích; không ghi đè note hàng loạt bằng bản dịch.
11. Lập danh sách dữ liệu lịch sử map được/không map được và hành vi fallback cụ thể; tránh hứa mọi câu lịch sử đều dịch đầy đủ khi thiếu tham số gốc.

**Đầu ra:** migration và code có thể review, kiểm thử database cục bộ, ma trận tương thích client/backend cũ và mới. Việc triển khai production là bước riêng, không tự coi file migration trong repo là đã áp dụng lên server thật.

### Giai đoạn D — Kiểm thử, chống tái phát và bàn giao

12. Thực hiện ma trận mục 5 và các kiểm tra tự động mục 6.
13. Cập nhật trạng thái `LOCALIZATION_PLAN.md` để người sau không dùng quyết định cũ “giữ mọi message server bằng tiếng Việt”; giữ rõ bối cảnh lịch sử của audit cũ.
14. Bàn giao báo cáo tiếng Việt: file đã sửa, lỗi nào đóng, lệnh/test đã chạy, kết quả, ảnh UI VI/EN, migration đã/chưa áp dụng và giới hạn còn lại. Chỉ tuyên bố đã kiểm thử UI nếu thực sự chạy app.

## 4. Quy tắc nội dung và những phần phải bảo toàn

- **Phải theo ngôn ngữ app:** nhãn, nút, trợ năng, hướng dẫn hệ thống, trạng thái nghiệp vụ, mẫu lý do mặc định, toast/snackbar/dialog, notification tự động, mô tả lịch sử hệ thống và fallback.
- **Giữ nguyên bản gốc:** tên app/người dùng, nội dung nhiệm vụ và hướng dẫn do người dùng nhập, phản hồi báo cáo, ghi chú phát hành tự soạn, lời giải trình/lý do tự nhập. Không dịch tự động hoặc thay nội dung người dùng trong database.
- Thông báo admin tự viết là nội dung tùy chỉnh; nếu muốn phát song ngữ phải có trường VI/EN và giao diện nhập rõ ràng. Không giả định mọi `title/message` đều là văn bản hệ thống.
- Giữ nguyên package name, URL, email, versionCode, id, enum/mã lỗi giao thức, tên thương hiệu và “Credit” nếu dùng làm tên đơn vị điểm.
- Không đổi quy tắc ngày, deadline hoặc timezone nghiệp vụ `Asia/Ho_Chi_Minh` chỉ vì đổi ngôn ngữ. Format ngày/số hiển thị có thể theo locale nhưng mốc thời gian và phép tính phải giữ nguyên.
- Đặc biệt `CampaignRepository.kt:807` và SQL còn so khớp `tạm tính`: đây là dữ liệu/logic legacy, không thay bằng câu dịch trong lượt localize. Muốn thay phải có migration trạng thái riêng và regression test.
- Không thay số thưởng/phí, quota, thời hạn, cache đồng bộ phiên đăng nhập, retry, idempotency hoặc pipeline nộp ảnh. Không gọi lại giao dịch khi đổi ngôn ngữ.

## 5. Ma trận kiểm thử bắt buộc

### Chuyển ngôn ngữ và vòng đời

| Ngôn ngữ máy | Ngôn ngữ lưu trước khi mở | Thao tác | Kỳ vọng |
|---|---|---|---|
| EN | VI | Mở lạnh → system | UI/VM/worker cùng EN |
| VI | EN | Mở lạnh → system | UI/VM/worker cùng VI |
| EN hoặc VI | system | VI → EN → VI nhiều lần | Câu hệ thống đổi ngay, không cần đăng nhập lại |
| VI | system | Đổi ngôn ngữ máy sang EN, quay lại app | App theo EN |
| EN | VI | Restart/rotate/background/restore | Vẫn VI |
| VI | EN | Restart/rotate/background/restore | Vẫn EN |
| Ngôn ngữ khác VI | system | Mở app | Fallback EN theo quy ước hiện tại |

Thử tối thiểu một thiết bị Android trước 13 và một thiết bị 13+ trong dải app hỗ trợ. Nếu chưa có thiết bị, ghi thiếu kiểm chứng, không thay bằng việc chỉ grep source.

### Luồng và tình huống

| Nhóm | Ca cần thử ở cả VI/EN |
|---|---|
| Auth/khóa tài khoản | Sai mật khẩu, mạng lỗi, đăng ký, reset mật khẩu, tài khoản khóa |
| Home/Nhiệm vụ | Các tab, xếp hạng, chỉ tiêu ngày, nhận thưởng ngày/tuần, rỗng/lỗi tải |
| Nhiệm vụ App | Tạo/nhận/đủ người/hết hạn/dừng/tiếp tục/xóa, thông báo lỗi |
| Nộp báo cáo | Chọn ảnh, đang gửi, retry, lỗi cuối, thành công, rời màn hình, foreground notification |
| Chủ app | Slot/vị trí, đăng app, duyệt/từ chối với lý do mẫu và tự nhập, tiền boa |
| Cập nhật phiên bản | Công bố/thay đợt/kết thúc/hoàn tiền, lịch sử, nhận thưởng, nhận lặp, lỗi quyền/phiên bản |
| Hồ sơ/Credit | Lịch sử cũ/mới, thiếu note, action chưa biết, đổi locale rồi mở lại |
| Thông báo | Cùng bản ghi ở hai locale, dữ liệu cũ, sự kiện mới, admin tự soạn, chưa đọc/đã đọc |
| Cấu hình cập nhật | Bắt buộc/tùy chọn, có/thiếu/rỗng bản VI/EN, backend legacy |
| Admin | Quản lý người dùng/báo cáo, thống kê chu kỳ, cấu hình update và thông báo |
| Trợ năng/bố cục | TalkBack, font lớn, màn hình hẹp, câu dài, placeholder và phần trăm |

Trong mỗi nhóm có state lỗi/thành công đang lưu: quay về Hồ sơ đổi ngôn ngữ rồi quay lại; xác nhận câu cũ không bị giữ, form còn nguyên, không phát thưởng/gửi báo cáo lần hai. Với backend mới: thử client cũ/backend mới và client mới/backend cũ, bao gồm payload cache cũ thiếu trường mới.

## 6. Kiểm tra tự động và tiêu chí hoàn tất

Gemini bổ sung kiểm tra có ý nghĩa:

1. Parse XML: parity khóa, duplicate, default = EN, placeholder/kiểu/index, escaped `%%`, thuộc tính formatted; thực thi formatter Android với tham số đại diện cho các câu mới.
2. Test mapper: cùng code + args trả đúng VI/EN; code chưa biết/exception bất kỳ dùng fallback đúng ngôn ngữ và không lộ raw text lên UI.
3. Test locale resolver: `vi/en/system`, cold-start lựa chọn trái ngôn ngữ máy, đổi ngôn ngữ máy; UI/localizer/worker nhất quán.
4. Test renderer notification/credit log/reason: cùng payload đổi ngôn ngữ, metadata thiếu, version/code chưa biết, legacy và raw text người dùng.
5. Với migration: test trigger/RPC phát code + params đủ; bảo toàn sổ Credit, RLS, idempotency và dữ liệu cũ. Dùng database cục bộ theo test harness của repo; không gọi production để chạy test.
6. Thêm kiểm tra chống chuỗi UI hardcode cho cả VI lẫn EN, có allowlist rõ cho protocol, log, thương hiệu, URL và nội dung fixture. Grep tiếng Việt đơn thuần không phát hiện được câu EN còn sót trong VI; không dùng số match bằng 0 làm bằng chứng duy nhất.

Các lệnh kiểm tra Android dự kiến, chạy ở thư mục gốc sau khi sửa:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Chọn bộ regression backend phù hợp nếu có sửa SQL. Project đặt `lint.abortOnError=false`, vì vậy exit 0 của lint **không tự chứng minh không có lỗi**; đọc report, đối chiếu baseline và xử lý lỗi localization mới (`MissingTranslation`, `ExtraTranslation`, `StringFormatInvalid`, `StringFormatMatches` và hardcoded text khi detector có hỗ trợ).

**Hoàn tất khi:** câu hệ thống trên các luồng đang dùng đúng ngôn ngữ được chọn ở cả hai chiều; system nhất quán; dữ liệu lịch sử có cách render/fallback rõ; nội dung tự nhập được bảo toàn; không lộ mã lỗi/raw exception ra UI chính; số Credit và luồng giao dịch không đổi; có bằng chứng test thực tế và công khai ca chưa kiểm chứng. Nếu chỉ sửa XML/client thì ghi rõ phần server và lịch sử chưa xong.

## 7. Prompt giao việc cho Gemini

> Đọc `KE_HOACH_SUA_DA_NGON_NGU_CHO_GEMINI_2026-10-29.md` và `audit/localization-2026-10-29/resources-and-source.json`. Kiểm tra working tree hiện tại và giữ nguyên thay đổi của công việc khác. Thực hiện các giai đoạn A–D để sửa triệt để việc lẫn Việt/Anh, gồm UI, trợ năng, locale system, ViewModel state, lỗi RPC, thông báo, lịch sử Credit, lý do mẫu và cấu hình update. Đừng chỉ dịch strings.xml hoặc fallback trong khi vẫn ưu tiên raw server message. Phân biệt nội dung hệ thống với nội dung người dùng tự nhập. Bổ sung contract backend tương thích và migration khi cần, chạy kiểm thử cục bộ cùng ma trận hai ngôn ngữ, bảo toàn nghiệp vụ và dữ liệu. Không tự deploy production. Bàn giao báo cáo file đã sửa, kết quả test, ảnh UI nếu thực sự chạy được, và phần chưa xác minh; không đánh dấu hoàn tất chỉ từ build thành công.
