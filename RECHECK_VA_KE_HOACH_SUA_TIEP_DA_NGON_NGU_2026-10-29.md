# Kiểm tra lại bản sửa đa ngôn ngữ và kế hoạch sửa tiếp cho Gemini

Ngày 29/10/2026. Kiểm tra working tree tại `F:\AI\12Tester`, HEAD `34f0ddb811e5397774105c2dd905d052511e59ec`, gồm bản sửa chưa commit và migration 055.

## 1. Kết luận

**Chưa sửa xong. Có lỗi còn sót và lỗi mới trong migration/backend contract.** Không thể nghiệm thu tuyên bố “hoàn thành triệt để L01–L09” trong `BAO_CAO_SUA_DA_NGON_NGU_2026-10-29.md`.

Các phần đã cải thiện: chuỗi cố định ở màn cập nhật và QuestSection, một số nhãn/trợ năng, fallback `friendlyError`, nguồn locale không còn dùng configuration đã bọc để resolve system. Ba bộ XML đều có **1.193 khóa**, không trùng/thiếu theo kiểm tra hiện tại, default giống EN; không thấy lệch chữ ký placeholder ở kiểm tra tĩnh.

Tuy nhiên, kiểm tra thực thi phát hiện:

- **12/12 test Gemini PASS**, nhưng **9/9 test đối chiếu độc lập FAIL**.
- Nạp nguyên migration **055 vào PGlite thất bại** với SQLSTATE **42725**: `function name "public.admin_update_app_config" is not unique`.
- Khi chỉ thay lệnh GRANT mơ hồ trong **bản chẩn đoán chạy trong RAM**, lời gọi RPC cũ của cả `admin_update_app_config` và `reject_tester_report` vẫn lỗi “is not unique”. Bản chẩn đoán không được ghi vào migration gốc hoặc triển khai.
- Notification thật được trigger sinh ra vẫn có câu tiếng Việt và mã `[CODE:...]`. Không chỉ là vấn đề dữ liệu lịch sử.

Không sửa production code, không chạy migration trên Supabase thật, không deploy. Chỉ thêm tài liệu và probe độc lập trong `audit/localization-recheck-2026-10-29/`. Không có bằng chứng kiểm thử giao diện APK trên thiết bị; ADB ở môi trường kiểm tra không khởi động được daemon. Lỗi SQL được tái hiện trên database dùng một lần, còn hành vi HTTP PostgREST và giao diện phải kiểm tra bổ sung.

## 2. Bằng chứng có thể chạy lại

Các file trong `audit/localization-recheck-2026-10-29/`:

- `summary.json`: kết quả hai suite và SHA256 các source chính.
- `resource-results.json`: parity và placeholder XML.
- `database-probe.mjs`, `database-results.json`: nạp full schema đến 054, kiểm tra nguyên migration 055 và các lỗi phía sau nó trên database RAM.
- `kotlin/com/ntt/crosstest/localization/LocalizationRecheckTest.kt`: 9 kiểm tra độc lập.
- `include-probes.gradle`: chỉ thêm nguồn Kotlin của probe khi được truyền vào lệnh; không sửa build.gradle hoặc mặc định thêm test đang fail vào suite của repo.
- `android-original-results.xml`, `android-recheck-results.xml`: kết quả thực thi được lưu riêng, không phụ thuộc report build bị ghi đè lần sau.

Chạy tại thư mục gốc:

```powershell
node audit/localization-recheck-2026-10-29/database-probe.mjs

$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat --init-script audit/localization-recheck-2026-10-29/include-probes.gradle :app:testDebugUnitTest --tests 'com.ntt.crosstest.localization.*' --console=plain
```

Lệnh Android hiện chạy **21 test, 9 fail**, đều là probe mới. Main Kotlin compile được Gradle xác nhận up-to-date. Không chạy lại assemble/lint trong lượt này vì không sửa ứng dụng; có đọc report lint hiện có, thấy 5 cảnh báo `StringFormatCount` liên quan cùng khóa `notif_daily_owner_big_text` và một `AppBundleLocaleChanges`, không coi đây là các lỗi mới đã xác nhận. Report lint cũ không chứng minh toàn bộ giao diện đúng ngôn ngữ.

**Ý nghĩa exit code:** database probe assert rằng lỗi hiện tại tái hiện được, nên exit 0 của probe không phải bằng chứng backend đã đúng. Sau sửa phải đổi sang assertion nghiệm thu hành vi đúng. Các test Android dùng kỳ vọng đúng và đang fail; tiếp tục dùng làm regression sau khi hoàn thiện code. Probe state chỉ kiểm tra mô hình String hiện tại; chưa thao tác UI thật.

## 3. Các vấn đề cần sửa

### R01 — P1: migration 055 lỗi thực thi và tạo RPC overload mơ hồ

**Bằng chứng thực thi database.**

`supabase/migrations/055_localization_hardening.sql:34` thêm hàm admin 11 tham số và `:141` thêm hàm reject 3 tham số. Chúng **không thay thế** hàm admin 7 tham số và reject 2 tham số đã tồn tại. Tham số mới có default nên cả cũ và mới cùng khớp lời gọi cũ.

1. Dòng `:381` dùng `grant execute on function public.admin_update_app_config` không kèm chữ ký → lỗi 42725 ngay khi nạp migration.
2. Chỉ sửa GRANT chưa đủ: lời gọi admin 7 tham số có tên vẫn lỗi 42725.
3. Lời gọi reject 2 tham số đúng như client hiện dùng cũng lỗi 42725; báo cáo vẫn `pending` sau lời gọi thử.

Client hiện tại `data/CampaignRepository.kt:1448–1453` vẫn gửi `RejectReportParams` chỉ có `p_report_id`, `p_reason`. Vì vậy không thể gọi bản mới một cách rõ ràng chỉ nhờ thêm tham số default. Ảnh hưởng HTTP tương ứng của PostgREST cần kiểm tra riêng, không giả định đã gọi production.

**Sửa:** chọn contract không mơ hồ. Có thể giữ RPC legacy đúng chữ ký cũ làm wrapper và tạo RPC v2 tên khác cho payload mới; hoặc thay thế có chủ đích các chữ ký theo thiết kế tương thích đã kiểm chứng. GRANT/REVOKE ghi đầy đủ chữ ký. Không chỉ bỏ overload cũ rồi tuyên bố tương thích mà chưa test client cũ, tham số thiếu/null và fallback. Không sửa SQL nghiệp vụ khác ngoài phạm vi.

`supabase/full_schema_singapore.sql` hiện chưa tích hợp 055; suite backend mặc định cũng chưa chạy 055. Cập nhật snapshot/test harness sau khi sửa. Trước khi quyết định sửa trực tiếp 055 hay thêm migration tiếp theo, xác định 055 đã được áp dụng/chạy dở ở môi trường đích hay chưa; lượt review này không có thông tin đó.

**Đạt khi:** full schema + migration chạy được; lời gọi old/new không mơ hồ; kiểm tra PostgREST với payload thực của app; giữ quyền truy cập như trước; test lại rejection và lưu cấu hình.

### R02 — P1: RPC reject mới đổi tên trường response, client đọc sai dữ liệu

**Bằng chứng database và test decode FAIL.**

Migration `055:355–367` trả `clawed_credits`, `new_balance`, `tester_locked`, `owner_locked`. Nhưng `data/Models.kt:506–521` vẫn đọc `credits_deducted`, `tester_balance`, `is_tester_locked`, `is_owner_locked`, cùng `status`, số lần từ chối và warning messages.

Probe gọi rõ overload mới thu hồi **10 Credit**, số dư thật **90**, nhưng decode payload mới bằng model hiện tại làm `creditsDeducted=0`, `testerBalance=0`; cờ khóa dùng tên mới cũng rơi về false. Response mới còn bỏ một số trường cũ. `OwnerAppDetailViewModel.kt:515–530` dựa vào các trường này để thông báo thu hồi và trạng thái khóa.

**Sửa:** bảo toàn toàn bộ response contract cũ và thêm trường mới một cách tương thích, hoặc version hóa RPC/model đồng bộ nhưng vẫn giữ wrapper cũ. Test JSON thật qua serializer client; không chỉ assert số dư trong SQL. Không thay số thưởng/thu hồi và không reset logic audit/cảnh báo.

**Đạt khi:** số Credit thu hồi, số dư, status, counts, flags và messages có nghĩa đúng ở client cũ/mới; trường `reason_code` được thêm mà không làm mất trường cũ.

### R03 — P1: thông báo hệ thống mới vẫn tiếng Việt; thông báo admin tự viết bị thay nội dung

**Bằng chứng database và ba test renderer FAIL.**

`util/NotificationRenderer.kt:15–66` ưu tiên `data.event_code` nhưng migration 055 **không cập nhật producer notification để phát trường này**. Nhánh fallback đoán bằng từ khóa rồi trả nguyên `title/message` nếu không khớp.

Các notification thật thu được từ trigger trên schema hiện tại + bản chẩn đoán 055:

- `Biến động số dư: +100 Credit 🪙` / `Thưởng 100 Credit khi mở tài khoản`.
- `Báo cáo kiểm thử mới 📋` / câu chủ app có 8 giờ duyệt.
- Biến động âm có note chứa `[CODE:REASON_DUPLICATE_OR_OLD]`.

Renderer không dịch các ca credit/new report trên. Có thể title được dịch nhưng message vẫn nguyên tiếng Việt, hoặc mất tên app/lý do/số liệu vì trả câu chung không dùng params.

Chiều ngược lại: notification `type=admin`, title tự soạn `Cảnh báo bảo trì: không gửi lại báo cáo trong 15 phút` bị thay thành tiêu đề cảnh báo tài khoản chỉ vì chứa “cảnh báo”. Probe đã tái hiện. Đây là lỗi làm sai nội dung người gửi, không phải dịch thiếu.

**Sửa:** inventory producer đang hiệu lực; phát code + params cho từng sự kiện hệ thống, đồng bộ metadata/model/renderer và cache. Nhận diện nguồn trước khi áp dụng template. Nội dung admin tự soạn giữ nguyên hoặc dùng trường VI/EN tường minh. Legacy chỉ đối chiếu mẫu chính xác có kiểm chứng, tránh substring rộng. Hỗ trợ event/version lạ bằng fallback hệ thống đã dịch và phần chi tiết bản gốc nếu cần, không để nguyên mã kỹ thuật ở UI chính.

**Đạt khi:** cùng notification thật sinh từ DB đổi VI/EN được, vẫn giữ thông tin cần thiết; thông báo admin tùy chỉnh không bị biến thành cảnh báo nghiệp vụ khác.

### R04 — P1: người kiểm thử thấy mã lý do; lời nhập tự do bị “dịch” thành lý do khác

**Bằng chứng source, database và test FAIL.**

- `OwnerAppDetailScreen.kt:2615–2621` gửi `[CODE:...]` cho lựa chọn mẫu. Nhưng `CampaignDetailScreen.kt:727–728` và `:1863–1865` vẫn hiển thị trực tiếp `rejectionReason`, chưa qua renderer. Người kiểm thử sẽ thấy marker thay vì câu đúng ngôn ngữ, kể cả trước khi áp dụng 055 vì backend cũ nhận nguyên chuỗi này.
- DB probe cũng thấy marker bị chèn vào notification/credit note. Phía owner đã gọi renderer nhưng phía tester chưa thống nhất.
- `RejectionReasonRenderer.kt:63–74` suy luận từ các từ như `admin`, `duplicate`, `không rõ`... Probe với câu tự nhập `Tôi đã hỏi admin, ảnh bị cắt mất nút xác nhận; hãy chụp toàn màn hình.` bị thay hoàn toàn thành “Removed by Admin”.

**Sửa:** dùng reason code/text tách biệt và reader chung tại mọi nơi hiển thị. Chỉ map legacy khi khớp chính xác toàn bộ một mẫu đã biết; không phân loại câu tự nhập bằng một từ. Code chưa biết phải có fallback phù hợp, không lộ marker. Kiểm tra lifecycle reason_code khi gửi lại báo cáo: xóa/reset cùng `rejection_reason` và đưa trường vào các RPC dựng JSON báo cáo khi cần. Giữ tương thích client cũ: đừng ghi marker làm nội dung hiển thị duy nhất mà client cũ không hiểu.

**Đạt khi:** owner/tester chọn locale khác nhau đều đọc đúng lý do mẫu; câu tự nhập được giữ nguyên; sau reject → resubmit → reject mới không dùng nhầm reason code cũ; không có `[CODE:...]` trong câu hiển thị chính.

### R05 — P2: lịch sử Credit thiếu action thật và có thể đổi sai ý nghĩa giao dịch

**Bằng chứng test FAIL và đối chiếu SQL.**

`CreditLogRenderer.kt:14–45` chưa phủ action thật như `welcome`, `mission_fee`, `app_update_reserve`. Trong khi code map `mission_create`/`app_update_deposit`, SQL hiện dùng tên khác (`044:291`, `051:352`). Note thật `Thưởng 100 Credit khi mở tài khoản` ở action welcome vẫn hiện nguyên trong English; đã tái hiện bằng test.

Fallback `detectLegacyNote()` kiểm tra “tạm nhận/tạm tính” ở dòng 58 **trước** “thu hồi” ở dòng 61. Test biên với action chưa biết và note `Thu hồi Credit tạm nhận do chủ app từ chối kết quả`, amount -10, lại render thành “Provisional test reward”. Đây là lỗi phân loại của fallback; không khẳng định mọi clawback hiện tại đều lỗi vì action `test_rejected_clawback` đã có nhánh riêng.

Migration thêm `metadata` cho credit logs/warnings nhưng Kotlin model chưa đọc các trường đó, và phần lớn producer chưa được bổ sung params. Thêm cột đơn thuần chưa hoàn tất contract.

**Sửa:** lấy danh mục action từ producer hiệu lực và fixture DB thật, mapping theo ngữ nghĩa. Ưu tiên structured action/metadata, không đoán bằng note nếu không chắc. Unknown action dùng mô tả trung tính theo loại biến động, tránh gọi mọi dòng âm/dương là phần thưởng. Không sửa amount/action/note lịch sử để “dịch”. Giữ số liệu và thông tin cần thiết như app/version/mốc.

### R06 — P2: còn nhiều đường bỏ qua mapper/renderer chung

**Xác nhận qua call site.**

| Nơi | Đường còn sót |
|---|---|
| `AppUpdateReleaseViewModel.kt:163` | Ưu tiên `result.message` khi công bố thất bại |
| `CampaignDetailViewModel.kt:1169` | Ưu tiên `result.message` khi nhận thưởng update thất bại |
| `OwnerAppDetailViewModel.kt:647` | Lấy `result.message` khi không có error code |
| `OwnerAppDetailViewModel.kt:518` → `OwnerAppDetailScreen.kt:222` | Hiện nguyên `ownerWarningMsg` từ server |
| `HomeScreen.kt:223` | Hiện nguyên `activeWarning.message`; màn chi tiết đã dùng WarningRenderer |
| `GoogleSignIn.kt:133–143` | Chèn `e.message`/tên class vào câu lỗi người dùng |

**Sửa:** render theo code/typed result; fallback đúng ngôn ngữ khi server cũ chỉ có câu không nhận diện được. Cảnh báo nghiệp vụ dùng warning type + params nhất quán ở Home/Owner/Detail; không chặn đường người dùng tự soạn. Chi tiết exception giữ trong log phù hợp. Test lỗi có message VI dưới locale EN và message EN dưới locale VI, không chỉ exception rỗng.

### R07 — P2: UiText chưa được tích hợp, state vẫn giữ câu của ngôn ngữ cũ

**Source + probe mô hình state FAIL; chưa kiểm thử UI trực tiếp.**

Tìm `UiText` trong `app/src/main/java` chỉ thấy chính khai báo `util/UiText.kt`, không có consumer trong ViewModel/screen. Các state `AppUpdateReleaseUiState.error/successMessage`, `CampaignDetailUiState.error`, `OwnerAppDetailUiState.error`... vẫn là String đã resolve bởi Localizer.

Probe tạo state lỗi mạng EN rồi chuyển locale VI: state vẫn là câu EN. Probe minh họa đúng giới hạn của việc lưu String, chưa chứng minh qua tương tác UI mọi màn hình đều giữ state đó. Báo cáo bàn giao hiện ghi L05 hoàn thành là chưa có cơ sở.

**Sửa:** đưa descriptor vào state/event thực tế và resolve tại render; phân biệt raw user text. Hoặc có phương án cập nhật state theo locale được kiểm chứng và không mất form. Đừng chỉ giữ helper không dùng. Đổi ngôn ngữ không phát lại RPC, thưởng, notification event hoặc upload; không recreate/reset toàn bộ form để né state cũ.

**Đạt khi:** thông điệp đang tồn tại đổi locale đúng khi quay lại màn, nội dung nhập và navigation giữ nguyên; test cả event mới và state cũ.

### R08 — P2: fallback cấu hình update đoán mọi legacy là tiếng Việt và có thể làm mất bản Anh

**Một lỗi tái hiện bằng test, các rủi ro lưu cần test repository.**

- `Models.kt:844–861` trả raw legacy cho VI, trả null cho EN. Config legacy `Security update required` vì vậy lọt nguyên vào màn VI. Test đã fail. `AdminUsersScreen.kt:819–822` còn đưa `config.title/message` vào ô VI khi thiếu titleVi/messageVi mà không biết ngôn ngữ gốc.
- `AppConfigRepository.kt:126` catch mọi Exception rồi gọi legacy, kể cả lỗi mạng/quyền/decode/cancellation. Legacy không có chỗ lưu EN; response có thể được báo là thành công mặc dù bản EN vừa nhập không được lưu. Chưa chạy mock HTTP cho ca này; đây là hành vi cần bổ sung kiểm thử trước nghiệm thu.
- Sau khi giải quyết overload R01, SQL mới `coalesce(excluded.title_vi,...)` và các trường khác cũng phải có quy ước rõ: thiếu/null là giữ, còn muốn xóa bản dịch thì dùng cách gì; client hiện gửi blank thành null có thể không xóa được cấu hình cũ.

**Sửa:** legacy không rõ ngôn ngữ dùng template hệ thống đúng locale, không giả định VI. Chỉ fallback RPC legacy khi xác định thiếu capability/chữ ký, rethrow cancellation và lỗi khác; giao diện phải nói rõ nếu backend chưa lưu được đủ hai bản, không báo lưu thành công đầy đủ. Định nghĩa thao tác giữ/xóa/đặt rỗng bản dịch, test reload cache từ server để chứng minh persistence.

### R09 — P2: resolver system mới vẫn bị remember chỉ theo languageSetting

**Rủi ro qua source, cần xác minh vòng đời Android trước khi kết luận lỗi UI.**

`MainActivity.kt:94` đổi thành `remember(languageSetting) { resolveLocale(...) }`. Nguồn system đã đúng hơn, nhưng nếu configuration đổi mà Activity không recreate thì kết quả vẫn bị cache do languageSetting vẫn là `system`. Worker/Localizer resolve ở thời điểm gọi có thể theo locale máy mới.

Manifest hiện không khai báo xử lý locale configChanges, nên Android thường recreate và có thể không lộ lỗi này trong luồng bình thường. **Chưa có bằng chứng tái hiện thiết bị; không đánh đồng với R01–R08 đã xác nhận.** Kiểm tra change configuration, background/restore và multi-window; nếu cần, phụ thuộc locale hệ thống được quan sát hoặc resolve lại khi configuration thay đổi. Giữ nguồn locale thật, đừng quay về configuration đã bọc cũ.

### R10 — P3: còn thuật ngữ trộn và helper nạp Credit chưa có consumer

- `values-vi/strings.xml`: `my_app_slot_default` vẫn “Slot 1 (Mặc định)”, các câu `topup_rate_banner_desc` còn Slot/Tip, nhiều câu vẫn Report/Tester/Admin/Moderator. Một số là lựa chọn thuật ngữ, nhưng không nên tuyên bố toàn bộ đã thuần Việt sau khi chỉ đổi bảy khóa.
- `TopupConfig.getLocalizedTitle/getLocalizedBadge` mới chưa được gọi; `CreditTopupBottomSheet.kt:365,369` vẫn đọc `pkg.title/badgeText`. Flag thực tế `IS_TOPUP_FEATURE_ENABLED=false`, nên **đây là tồn đọng tính năng tắt, không coi là lỗi đã thấy trên màn hiện hành**.

**Sửa:** thống nhất glossary, dịch nhãn hệ thống theo ngữ cảnh, giữ thương hiệu/tên ngôn ngữ/Credit/URL/email. Nối helper đúng nơi hiển thị nếu giữ tính năng nạp; không thay id gói hoặc số tiền.

## 4. Kế hoạch thực hiện và kiểm thử nghiệm thu

### Bước 1 — Sửa contract/migration trước

1. Đọc status/diff, bảo toàn công việc khác. Kiểm tra trạng thái áp dụng migration, không tự deploy.
2. Sửa R01 và R02 cùng nhau. Chạy database local từ schema đến 054 + bản sửa, test lại từ đầu và trạng thái có/không có cột mới. Không chỉ chạy file SQL rời trên database đã chuẩn bị riêng.
3. Test old/new payload và response, thu hồi thực tế, lock flags, RLS/quyền, audit counts, giới hạn 8 giờ, idempotency và resubmission. Cập nhật snapshot và script test backend để CI thực sự đi qua migration localization.

### Bước 2 — Hoàn thiện event/reason/credit contract

4. Sửa R03–R05: producer code + params, DTO/cache và renderer đi cùng nhau. Lấy fixture từ trigger/RPC thật thay vì bịa event code chỉ có trong test.
5. Nối renderer tại mọi call site phía owner/tester. Kiểm tra raw user/admin text có chứa từ `admin`, `duplicate`, `warning`, “cảnh báo”, “không rõ” vẫn nguyên vẹn.
6. Không mất số liệu/tên app/lý do khi dịch. Unknown template có fallback an toàn, không lộ marker/mã giao thức và không đoán sai loại giao dịch.

### Bước 3 — Sửa phần client còn sót

7. Sửa R06–R08; test repository lỗi RPC/mạng/quyền/cancellation và round-trip lưu hai bản config.
8. Thực hiện R09 trên thiết bị trước/13+ khi có điều kiện. R10 xử lý sau phần đang dùng. Giữ nguyên timezone nghiệp vụ, số Credit, retry và pipeline nộp báo cáo.

### Bước 4 — Kiểm thử thật, cập nhật báo cáo

9. Giữ 12 test cũ, làm 9 probe mới PASS bằng sửa implementation. Chuyển probe sang regression test chính thức sau khi thống nhất hợp đồng. Riêng probe state, thay/bổ sung test ViewModel/Compose thực thay vì chỉ dựng data class. DB probe hiện assert lỗi phải được đổi thành assertion đúng sau sửa.
10. Thắt assertion test cũ: `testCreditLogRenderer_legacyVietnameseNotes` hiện chấp nhận cả chuỗi còn “DemoApp” nên có thể pass khi nguyên câu Việt chưa dịch; `testXmlResourceParityAndFormatIntegrity` chỉ so set khóa, chưa kiểm tra duplicate hoặc format như tên test. Assert bản dịch mong đợi, params, locale thay đổi và bảo toàn raw text; XML parse thực thay vì chỉ regex lấy set.
11. Chạy build, unit test, lint và regression backend phù hợp. Đọc report lint, không dựa riêng exit 0 vì `abortOnError=false`.
12. Chạy ma trận hai ngôn ngữ trong kế hoạch gốc, bổ sung các ca ở R01–R10; lưu ảnh/bằng chứng thật. Nếu thiếu thiết bị thì ghi rõ chưa xác minh toàn bộ tương tác UI, không chỉ ba hộp thoại được nêu trong báo cáo hiện tại.
13. Cập nhật báo cáo bàn giao: mục đã sửa, bằng chứng nào là SQL/test/source/UI, migration đã/chưa áp dụng, phần còn thiếu. Không ghi “100% logic” chỉ từ 12 unit test.

**Điều kiện nghiệm thu:** migration thực thi được; RPC không mơ hồ và giữ contract cũ; thông báo/credit/reason thật đúng locale; raw user text không bị sửa nghĩa; state đang hiển thị đổi ngôn ngữ đúng; backend legacy không báo lưu đủ song ngữ khi thực tế mất dữ liệu; không thay đổi nghiệp vụ/tài chính.

## 5. Prompt gửi Gemini

```text
Đọc F:\AI\12Tester\RECHECK_VA_KE_HOACH_SUA_TIEP_DA_NGON_NGU_2026-10-29.md
và bằng chứng tại F:\AI\12Tester\audit\localization-recheck-2026-10-29\.

Bản sửa hiện tại chưa đạt: 12 test cũ pass nhưng 9 probe độc lập fail; migration 055 lỗi 42725,
RPC overload bị mơ hồ và response reject không tương thích client.

Sửa lần lượt R01–R08; xác minh R09 trên thiết bị và xử lý R10 theo phạm vi tính năng.
Ưu tiên migration/RPC contract, rồi producer + params + renderer và các call site thực tế.
Giữ nguyên thay đổi công việc khác, nội dung người dùng tự nhập, số Credit và nghiệp vụ.
Không chỉ sửa GRANT, không chỉ thêm helper chưa dùng, không đoán nội dung tự nhập bằng substring.
Chạy lại các probe và bổ sung regression thực, cập nhật full schema/test harness và báo cáo có bằng chứng.
Phân biệt source review, test mô hình state và UI thực tế; không tự deploy production.
Thực hiện sửa và kiểm thử, không chỉ lập thêm kế hoạch.
```
