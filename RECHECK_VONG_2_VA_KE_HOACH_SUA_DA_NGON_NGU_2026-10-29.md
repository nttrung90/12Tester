# Kiểm tra vòng 2 và kế hoạch sửa đa ngôn ngữ cho Gemini

Ngày 29/10/2026. Kiểm tra working tree tại `F:\AI\12Tester`, HEAD `34f0ddb811e5397774105c2dd905d052511e59ec`, gồm các thay đổi chưa commit.

## 1. Kết luận

**Chưa thể nghiệm thu.** Bản mới đã làm **21/21 test trước PASS** và migration 055 chạy được, không còn lỗi overload 42725 hay mất các trường response reject đã kiểm tra trước. Nhưng kiểm tra sâu hơn phát hiện **7 nhóm vấn đề**, gồm một regression backend làm mất thông báo kết quả báo cáo và một regression nguồn locale system.

Chạy chung **32 test Android: 21 PASS, 11 FAIL**. Các test FAIL đều là kiểm tra bổ sung trong lượt này. Database độc lập so schema trước/sau 055 xác nhận ba nhánh thông báo đã bị mất. Tài nguyên ba ngôn ngữ cùng có **1.202 khóa**, không duplicate/thiếu khóa hoặc lệch chữ ký placeholder theo kiểm tra tĩnh; parity XML không phải nguyên nhân chính còn lại.

Chỉ thêm probe và tài liệu; không sửa mã ứng dụng, migration hoặc full schema. Không gọi Supabase/Firebase production, không deploy. Kiểm tra gồm source, Robolectric, Ktor MockEngine và PGlite trong RAM. Chưa chạy giao diện APK trên thiết bị/emulator. Riêng kiểm tra locale system tái hiện nguồn context/hàm resolve đúng theo MainActivity, chưa khởi chạy Activity có đăng nhập thật; các kiểm tra state là kiểm tra model, chưa có tương tác Compose.

## 2. Bằng chứng và lệnh chạy lại

Thư mục `audit/localization-recheck-round2-2026-10-29/` gồm:

- `summary.json`: SHA256 source, kết quả ba suite, thống kê tài nguyên và lỗi test.
- `database-probe.mjs`, `database-results.json`: tách baseline đến 054 khỏi full schema hiện tại, so với áp dụng 055 lần đầu.
- `kotlin/com/ntt/crosstest/localization/LocalizationRound2Test.kt`: 11 kiểm tra bổ sung.
- `include-probes.gradle`: chỉ thêm test khi truyền init script, không thay build.gradle.
- `LocalizationHardeningTest.xml`, `LocalizationRecheckTest.xml`, `LocalizationRound2Test.xml`: kết quả thực thi được lưu riêng.

Chạy tại thư mục gốc:

```powershell
node audit/localization-recheck-round2-2026-10-29/database-probe.mjs

$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat --init-script audit/localization-recheck-2026-10-29/include-probes.gradle --init-script audit/localization-recheck-round2-2026-10-29/include-probes.gradle :app:testDebugUnitTest --tests 'com.ntt.crosstest.localization.*' --console=plain
```

Cả hai lệnh vòng 2 hiện exit 1 vì assertion nghiệp vụ đúng bị vi phạm. Database probe đã lưu toàn bộ kết quả trước assertion; không chỉnh migration để chạy chẩn đoán. Script database cũ trong thư mục vòng trước hiện PASS, nhưng chỉ đọc danh sách notifications, chưa assert đầy đủ sự kiện approved/rejected/edited; vì vậy chưa phát hiện S01.

## 3. Vấn đề còn lại và cách sửa

### S01 — P1: migration 055 làm mất thông báo duyệt, từ chối và sửa báo cáo

**Tái hiện database trước/sau migration.**

`supabase/migrations/055_localization_hardening.sql:407–470` thay toàn bộ `trg_notify_tester_report()` nhưng chỉ giữ hai nhánh: INSERT pending và rejected → pending. Bỏ các nhánh đang có trong migration 037: pending → approved/rejected, và pending → pending khi đổi ảnh/hash/content. Full schema đã đồng bộ cùng regression.

| Tình huống | Schema đến 054 | Sau 055 |
|---|---:|---:|
| Báo cáo được duyệt (UPDATE approved) | 1 notification report cho tester | 0 |
| Gọi RPC reject hiện tại | 1 notification report cho tester | 0 |
| Sửa screenshot khi đang pending | 1 notification report cho owner | 0 |

Thông báo biến động Credit riêng có thể vẫn tồn tại; nó không thay thế thông báo report có điều hướng/lý do. Ca reject dùng RPC thật trên database RAM; approve/edit dùng cập nhật trạng thái/ảnh để kiểm tra trigger như baseline, chưa chạy HTTP hay UI.

**Sửa:** lấy toàn bộ nhánh hiệu lực trước 055 làm baseline, bổ sung code + params cho từng nhánh thay vì viết lại chỉ hai nhánh. Giữ `app.suppress_report_notify`, phần tăng thêm Credit, số thu hồi, reason, report/campaign id và các cờ edited/resubmission. Renderer phải hỗ trợ đúng event mới. Không phục hồi bằng cách gửi thêm thông báo tại client rồi gây trùng với trigger.

**Đạt khi:** giữ đủ năm nhóm sự kiện new/resubmit/edit/approve/reject, thông báo đúng người và đúng một lần; kiểm tra duyệt tay, tự duyệt và các nhánh suppression hiện hữu.

### S02 — P1: nguồn locale system quay lại configuration đã được bọc

**Tái hiện hàm/context trong Robolectric + đối chiếu MainActivity; chưa test UI thật.**

`MainActivity.kt:61–62` bọc base context theo lựa chọn đã lưu. Nhưng `:94–96` nay lại lấy `configuration.locales[0]` của Activity làm `systemLocale`, truyền vào `resolveLocale(languageSetting, systemLocale)`. Đây chính là nguồn có thể đã bị override trong lần mở app, khác với nguồn thật mà Localizer/worker dùng.

Probe: máy EN, lưu VI, tạo context giống attachBaseContext → configuration VI. Dùng nguồn này với setting system trả **VI**, kỳ vọng **EN**. Thêm systemLocale vào remember xử lý việc cập nhật cache, nhưng không sửa được nguồn locale sai.

**Sửa:** lấy locale hệ thống thật từ nguồn không bị bọc (`SettingsRepository.getSystemLocale()` theo thiết kế hiện có), quan sát thay đổi configuration/lifecycle để resolve lại. Không dùng locale của base context đã localize làm giá trị hệ thống. UI và worker dùng cùng quy tắc.

**Đạt khi:** máy EN/lưu VI → cold start → system ra EN; máy VI/lưu EN → cold start → system ra VI; đổi ngôn ngữ máy khi đang system được phản ánh. Bổ sung test Activity/thiết bị thực, không chỉ test hàm với locale truyền vào tùy ý.

### S03 — P2: thông báo lịch sử đã duyệt bị biến thành “báo cáo mới”, title còn tiếng Việt

**Hai test renderer FAIL với mẫu từ trigger thật.**

`NotificationRenderer.kt:133–137` coi mọi notification type report có chữ “duyệt” hoặc “8 giờ” là báo cáo mới. Mẫu cũ thực tế:

> Báo cáo của bạn cho ứng dụng "DemoApp" đã được duyệt thành công! Bạn nhận thêm 2 Credit cho lượt test này. Cảm ơn bạn!

Bị render thành câu “received 1 new test report ... Please review it.” Dữ liệu cũ đã có `data.status=approved`, nhưng renderer không dùng để phân biệt. `renderTitle()` chỉ đối chiếu các câu không trùng title cũ `Báo cáo kiểm thử được phê duyệt! 🎉`, nên title vẫn Việt trong English. Một notification vừa sai ngôn ngữ vừa sai trạng thái và vai trò người nhận.

**Sửa:** ưu tiên structured data hiện có (status/is_edited/is_resubmission và nguồn sự kiện), sau đó bảng mẫu legacy chính xác. Không dùng “duyệt” làm tiêu chí chung cho new report. Lấy fixture thật từ baseline trigger/RPC, giữ tên app, số thưởng và lý do khi có đủ dữ liệu. Kiểm tra title và message cùng lúc.

**Đạt khi:** legacy approved/rejected/pending/edited/resubmitted được phân loại đúng ở hai locale; approved không yêu cầu người kiểm thử đi duyệt báo cáo.

### S04 — P2: lý do mặc định cũ chưa dịch và câu tự nhập vẫn bị thay nhầm

**Hai test FAIL.**

`RejectionReasonRenderer.kt:72–100` ghi chú “exact known legacy” nhưng còn dùng startsWith ở các dòng 79–90. Danh sách exact cũng không khớp các resource đã thực sự dùng trước đó.

- Mẫu thật `Ảnh trùng lặp với ngày hôm trước` từ `oad_reject_reason_1` giữ nguyên trong English, không thành `Photo duplicated from the previous day`.
- Câu tự nhập `Ảnh không đúng ứng dụng ở bước thanh toán; riêng màn đăng nhập đã đạt yêu cầu, hãy giữ lại.` bị thay toàn bộ thành `Unclear photo / not this app`, làm mất hướng dẫn cụ thể.

**Sửa:** lập bảng exact match từ toàn bộ VI/EN resource lịch sử cho từng reason code, gồm cả fallback chuẩn. Bỏ startsWith cho văn bản không có code rõ ràng. Text không khớp toàn bộ mẫu phải giữ nguyên. Chuỗi có code hợp lệ dùng code; không suy code từ phần đầu lời tự nhập.

**Đạt khi:** cả ba lý do mẫu và fallback lịch sử dịch được hai chiều; câu mở đầu giống mẫu nhưng có nội dung riêng không bị thay đổi. Giữ test bảo toàn câu chứa admin đã PASS.

### S05 — P2: state chỉ “dịch ngược chuỗi” cho một danh sách nhỏ

**Ba test model FAIL; test mạng cố định cũ PASS.**

`UiStateMessageResolver.kt:17–58,78–91` đối chiếu raw String với bản VI/EN của khoảng vài chục resource cố định, rồi dịch lại. Đây chưa phải state chứa resource + args. `UiText` vẫn chưa được dùng ở các state thực. Các field động chỉ mới áp dụng ở AppUpdateReleaseUiState, các ViewModel khác vẫn String.

Ca EN → VI còn lỗi:

- Success có tham số `Closed update round and refunded 42 unused Credits!` không đổi.
- Error cố định `Unable to close update round.` không có trong allowlist nên không đổi.
- `OwnerAppDetailUiState.error` giữ nguyên câu lỗi mạng EN.

**Sửa:** giữ identity thông điệp và params ngay khi phát sinh, resolve ở render theo locale. Phân biệt raw user text với thông điệp hệ thống. Không mở rộng allowlist hoặc regex để cố đoán lại toàn bộ resource id từ câu đã format; cách đó dễ sai khi hai key trùng nội dung hoặc có tham số. Không cần sửa tài chính/navigation để đổi chữ.

Getter state đang phụ thuộc global context/SharedPreferences cũng không tạo ra tín hiệu Compose tự quan sát. Bổ sung test ViewModel/Compose/lifecycle thực để chứng minh đổi locale làm UI cập nhật, form không mất và không phát lại thao tác. Probe hiện chỉ chứng minh các giá trị model không đổi đúng.

**Đạt khi:** thông báo cố định và có params trong các màn đang dùng đổi VI/EN/VI được; giữ số 42 và các tham số; không gửi lại RPC/upload/thưởng.

### S06 — P2: cấu hình update vẫn gán sai ngôn ngữ, không xóa được bản dịch, fallback làm mất EN

**Database trước/sau và hai test repository MockEngine FAIL.**

1. **Backfill sai nguồn.** `055:25–28` sao chép raw legacy vào title_vi/message_vi dù không biết ngôn ngữ. Database có title `Security update required` trước migration → sau migration `title_vi` cũng chính câu EN này. Helper AppConfig ưu tiên field cụ thể nên heuristic nhận diện raw bằng dấu tiếng Việt không ngăn được lỗi. Không thể suy chắc ngôn ngữ chỉ từ có/không có dấu.
2. **Xóa ô dịch không có hiệu lực.** UI chuyển chuỗi trắng thành null (`AdminUsersScreen.kt:945–948`); RPC dùng coalesce giữ field cũ (`055:115–118`). Database probe ghi hai bản, gửi payload null như UI khi xóa, cả hai bản cũ vẫn giữ nguyên. Cần phân biệt bỏ qua field và chủ động xóa.
3. **Client legacy cập nhật bị che.** Sau khi có title_vi/title_en, gọi RPC chỉ p_title/p_message cập nhật raw legacy nhưng hai bản cụ thể vẫn cũ. UI mới ưu tiên hai bản cụ thể nên không hiển thị nội dung mới từ client cũ. Probe đã ghi nhận cả hai phiên bản dữ liệu trong cùng hàng.
4. **Fallback quá rộng chưa sửa.** `AppConfigRepository.kt:126` vẫn catch mọi Exception rồi gọi lại mutation legacy. Mock trả 403 lần đầu, 200 lần sau: repository gọi **hai lần và báo thành công**, che lỗi quyền ban đầu. Đây là bằng chứng hành vi client; không có nghĩa đã vượt qua kiểm tra quyền thật của server.
5. **Mất bản Anh khi fallback.** Mock backend thiếu RPC mới (PGRST202), legacy chỉ lưu VI: repository vẫn trả success dù titleEn/messageEn vừa nhập không được lưu; ViewModel tiếp tục phát thông báo “đã lưu”. Test fail xác nhận response không có EN. Không phải chỉ rủi ro từ source như lượt trước.

**Sửa:** dùng schema/contract tường minh cho ngôn ngữ nguồn legacy; không tự sao chép vào VI khi không biết. Định nghĩa rõ set/keep/clear và hành vi client cũ; tránh bản dịch cũ che cập nhật mới. Chỉ fallback khi lỗi capability/chữ ký đã nhận diện chắc chắn; giữ cancellation và lỗi mạng/quyền/decode. Nếu backend legacy không lưu đủ song ngữ thì báo khả năng giới hạn hoặc lỗi rõ ràng, không báo thành công đầy đủ. Kiểm tra reload từ server/cache để chứng minh đã lưu.

**Đạt khi:** ghi/sửa/xóa bản VI/EN có hiệu lực thật; legacy EN không tự trở thành VI; legacy client không cập nhật ngầm vào field UI không dùng; lỗi 403 không thử mutation khác; không âm thầm bỏ bản EN.

### S07 — P3: tên app có dấu ngoặc kép bị hiển thị escape JSON và mất ký tự

**Test renderer FAIL.** `NotificationRenderer.kt:102–111` đọc `data.app_name` bằng `.toString().trim('"')` thay vì decode JsonPrimitive. Tên `Demo "A"` xuất hiện dấu backslash escape trên UI và có thể mất dấu ngoặc kép ở cuối vì trim không phải JSON decoder.

**Sửa:** dùng API JSON đúng kiểu (`jsonPrimitive.contentOrNull`/content với kiểm tra null/kiểu), rồi truyền nguyên tên vào resource. Áp dụng cho các params text; không parse bằng bỏ dấu quote ngoài. Legacy thiếu appName dùng fallback resource, không dùng từ App cố định khi locale VI.

**Đạt khi:** tên có quote, backslash, Unicode, xuống dòng hiển thị theo dữ liệu thật; không lộ escape và không biến đổi nội dung tự nhập.

## 4. Thứ tự sửa và tiêu chí nghiệm thu

1. **S01 + S02 trước:** phục hồi regression trigger và nguồn system. Kiểm tra cả full schema mới lẫn nâng cấp từ 054; không chỉ chạy lại 055 trên snapshot đã có 055.
2. **S03 + S04 + S07:** fixture legacy lấy từ dữ liệu trigger/resource thật; renderer giữ trạng thái đúng và không biến đổi lời nhập tự do.
3. **S05:** tích hợp descriptor resource/code + params vào state đang dùng. Không làm thêm bảng đoán raw text để chỉ vượt ca test.
4. **S06:** sửa contract config, migration/backfill, repository và form admin đồng bộ. Kiểm tra khả năng tương thích client/backend cũ–mới, lỗi quyền/mạng/cancellation, lưu và xóa các trường.
5. Giữ 21 test đã PASS. Làm 11 test mới PASS bằng sửa hành vi, không thay fixture/giảm assertion. Nâng các test model/context thành test Activity/Compose khi có điều kiện.
6. Bổ sung kiểm thử producer end-to-end: tạo dữ liệu qua SQL/RPC → đọc notification JSON → decode model → render VI/EN. Assert số lượng, người nhận, trạng thái, nội dung và params, không chỉ assert câu có thay đổi.
7. Chạy build, unit test, regression backend và lint; đọc report vì lint có `abortOnError=false`. Kiểm thử thiết bị trước Android 13 và 13+ cho chuyển system, restart/background, notifications và font lớn. Nếu không có thiết bị thì ghi đúng giới hạn chưa test UI.
8. Cập nhật báo cáo bàn giao: 21 test trước đã qua nhưng không bao phủ các regression này. Không ghi “đã xử lý triệt để R01–R10” khi chỉ có PASS các fixture cũ.

Không đổi số Credit, mốc 8 giờ, timezone, điều kiện trả thưởng/thu hồi, quyền/RLS, retry/idempotency hoặc pipeline upload. Giữ nguyên thay đổi của công việc khác. Không triển khai production trong nhiệm vụ sửa/review này; xác minh trạng thái áp dụng 055 trước khi chọn sửa file chưa triển khai hay tạo migration kế tiếp.

## 5. Prompt gửi Gemini

```text
Đọc F:\AI\12Tester\RECHECK_VONG_2_VA_KE_HOACH_SUA_DA_NGON_NGU_2026-10-29.md
và bằng chứng tại F:\AI\12Tester\audit\localization-recheck-round2-2026-10-29\.

21 test trước đã PASS, nhưng 11 ca mới FAIL và database so trước/sau 055 xác nhận regression.
Sửa S01–S07 theo kế hoạch: ưu tiên khôi phục thông báo approve/reject/edit bị mất và nguồn locale system.
Sau đó sửa phân loại thông báo lịch sử, mapping lý do chính xác, state có resource/code + params,
contract cấu hình song ngữ và decode params JSON đúng kiểu.

Giữ 21 test đã qua, làm 11 test mới qua bằng sửa implementation. Không thu hẹp fixture/assertion.
Không chỉ thêm allowlist để đoán chuỗi state, không dùng startsWith để sửa lời người dùng tự nhập,
không catch mọi lỗi rồi gọi mutation legacy và không báo lưu đủ VI/EN khi đã bỏ mất bản Anh.
Kiểm thử database từ baseline 054, payload thật của producer và client cũ–mới; cập nhật full schema.
Giữ nguyên nghiệp vụ, số Credit, deadline, quyền, upload/retry/idempotency và thay đổi công việc khác.
Không tự deploy production. Bàn giao báo cáo test/source/database/UI rõ ràng và phần còn chưa xác minh.
Thực hiện sửa và kiểm thử, không chỉ lập kế hoạch mới.
```
