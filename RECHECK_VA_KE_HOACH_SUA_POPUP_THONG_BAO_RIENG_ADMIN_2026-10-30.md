# Kiểm tra và kế hoạch sửa popup thông báo riêng của admin

Ngày kiểm tra: **30/10/2026**. Kiểm tra source Gemini đã triển khai, chạy lại test/build và bổ sung test tái hiện. Chưa sửa code sản phẩm, chưa chạy migration lên database live.

## 1. Kết luận

**Còn 6 lỗi hành vi đã tái hiện được.** Chưa nên coi tính năng hoàn tất chỉ dựa vào build và bộ test ban đầu.

- Backend popup hiện tại: **11/11 bài test PASS** trên PGlite.
- Android hiện có: **23/23 test PASS**: 9 popup ViewModel, 2 pending store, 12 localization.
- `assembleDebug`: **PASS** trên source sản phẩm hiện tại.
- Bộ kiểm tra bổ sung: **6/6 FAIL do assertion hành vi**, không phải lỗi compile hoặc thiết lập môi trường. Mỗi bài tái hiện một lỗi dưới đây.

File kiểm tra mới:

`app/src/test/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupReviewRegressionTest.kt`

Bằng chứng được lưu ở `audit/admin-popup-review-2026-10-30/`: `backend-existing.log`, `android-existing.log`, `android-regression.log`, `regression-results.xml`.

## 2. Các lỗi đã xác nhận

### F1 — P1: Đã đọc lỗi mạng không được retry trong cùng phiên

**Vị trí:** `NotificationPopupViewModel.kt:187–197,249–294`.

Tái hiện:

1. Nhận popup trong phiên S1.
2. Bấm Đã đọc; lưu local thành công, request ack trả HTTP 503.
3. Mạng/server hoạt động lại và chạy tick tiếp trong S1.

**Thực tế:** `consumedSessions.contains(sessionId)` làm tick return trước `syncPendingAcksInternal`. Ack không được gửi lại dù người dùng tiếp tục dùng app. Thiết bị khác vẫn thấy chưa đọc và có thể tiếp tục nhắc. Chỉ chuyển phiên hoặc một tác nhân khác mới có cơ hội sync.

**Test:** `pending_read_must_retry_even_after_this_foreground_session_is_consumed`.

**Sửa:** tách worker đồng bộ pending ack khỏi điều kiện còn suất popup. Worker retry khi online/foreground dù phiên đã dùng lượt hoặc UI đang bận; chỉ nhánh claim mới chịu điều kiện suất/blocker. Chống sync trùng và giữ kiểm tra UID/generation xuyên suốt request.

### F2 — P1: Ack chưa đồng bộ vẫn gọi claim và tiêu một lượt không được hiển thị

**Vị trí:** `NotificationPopupViewModel.kt:196–216,297–313`.

Tái hiện: kho local có pending ack cho N, RPC ack trả 503 nhưng RPC claim vẫn hoạt động.

**Thực tế:** `syncPendingAcksInternal` nuốt lỗi rồi return Unit; caller vẫn gọi claim. Server cấp thêm lượt cho N, sau đó client mới thấy N đang pending và không hiện popup. Bộ đếm bị tiêu dù đây không phải khoảng crash được chấp nhận trong thiết kế.

**Test:** `unsynced_pending_read_must_prevent_new_claim_from_consuming_an_invisible_view`.

Kết quả assertion: `expected 0 claim calls, actual 1`.

**Sửa:** worker trả kết quả đồng bộ rõ ràng; kiểm tra lại pending sau sync. Nếu còn pending của tài khoản hiện tại, tạm ngừng claim và retry ack sau. Không chữa bằng hoàn lượt hoặc tăng giới hạn quá 5.

### F3 — P2: Để sau khi popup đi qua background/resume làm nó mở lại trong cùng phiên

**Vị trí:** `NotificationPopupViewModel.kt:173–189,243–246`.

Tái hiện:

1. S1 claim N; giữ popup đang mở.
2. App vào nền, quay lại với phiên S2; tiếp tục popup cũ đúng thiết kế.
3. Bấm Để sau, rồi tick tiếp trong S2.

**Thực tế:** state Showing vẫn mang S1; dismiss chỉ tiêu thụ S1. S2 chưa được ghi nhận đã có popup, nên tick claim N lần nữa và mở lại ngay trong S2.

**Test:** `closing_a_popup_carried_across_resume_must_consume_current_session_too`.

Kết quả assertion: `expected 1 claim call, actual 2`.

**Sửa:** phân biệt phiên claim trên server với phiên đang tiếp tục trình bày popup. Khi một phiên mới tiếp tục popup cũ, phiên đó cũng đã dùng suất hiển thị. Bao phủ cả Để sau, Đã đọc và việc đóng do đọc/xóa từ nguồn khác. Giữ receipt/claim ID gốc để không tăng đếm khi resume.

### F4 — P1: Blocker xuất hiện trong khi claim đang chạy không ngăn popup đè lên dialog ưu tiên

**Vị trí:** `NotificationPopupViewModel.kt:135–139,163–225`; `AdminNotificationPopupHost.kt:50–56`.

Tái hiện: khởi chạy claim khi không bị chặn, bật `systemWarning` trước khi response trở về, rồi trả response `claimed`.

**Thực tế:** ViewModel chỉ kiểm tra blocker trước network; sau await chỉ kiểm tra UID và foreground ID rồi publish Showing. Host chỉ quan sát Showing, không quan sát blocker. Popup vẫn được trình bày khi cảnh cáo hệ thống đang active. Nếu blocker bật sau lúc popup đã hiện, host cũng không có điều kiện để tạm ẩn.

**Test:** `a_blocker_appearing_during_claim_must_prevent_showing_the_returned_popup`.

**Sửa:**

- Tách trạng thái đã claim/đang giữ nội dung khỏi điều kiện thực sự được trình bày; kiểm tra lại điều kiện sau await và ở host khi render.
- Nếu response về khi đang bị chặn, giữ lượt đã claim và chờ; không bỏ nội dung rồi claim thêm lượt khi blocker được gỡ.
- Blocker bật khi popup đang mở: tạm ẩn, khôi phục cùng lượt khi đủ điều kiện.
- Điều phối thêm các dialog/modal thực sự đang mở, không chỉ 6 boolean hiện có. Hiện các modal khác như hộp thông báo/topup chưa được đăng ký với coordinator.
- Trạng thái khóa tài khoản hiện chỉ đặt ở Home và reset trong onDispose. Cần chặn theo route/profile ở cấp phù hợp, tránh mất blocker khi chuyển sang màn `account_locked`.
- Tránh claim quá sớm khi app config/profile/cảnh cáo còn đang tải mà mặc định blocker đang false.

Lưu ý kiểm thử: bài hiện tại đo Showing vì host hiện render mọi Showing. Nếu sửa bằng cách giữ Showing nhưng host có điều kiện render riêng, có thể chuyển test sang cờ presentation hoặc test UI tương đương; phải chứng minh popup không được trình bày khi bị chặn, không được bỏ kiểm tra hành vi này.

### F5 — P2: Callback lifecycle thật phát foreground trước khi cập nhật ID phiên

**Vị trí:** `util/AppForegroundTracker.kt:25–29`.

`onActivityStarted` đang ghi `_isForeground=true` rồi mới ghi `_foregroundSessionId=UUID mới`. Collector có thể chạy ngay khi biến foreground đổi và đọc ID null hoặc ID phiên cũ. Trên lần mở đầu, tick không có ID nên bỏ qua; trên lần quay lại có thể thấy phiên cũ đã consumed. Do ViewModel không quan sát thay đổi ID riêng, thường phải chờ lần poll sau mới thử lại.

**Test:** `real_activity_start_must_publish_new_session_before_foreground_event` gọi callback thật, không dùng simulateForeground.

Kết quả assertion: foreground đã active nhưng collector đọc ID **null**, trong khi tracker ngay sau đó có UUID mới.

**Sửa:** publish trạng thái foreground/ID một cách nhất quán, ưu tiên một StateFlow chứa cùng snapshot; tối thiểu tạo ID trước khi báo foreground. Test cả callback thật cho cold start, background/resume, configuration change. Hook simulateForeground hiện làm đúng thứ tự ngược với production nên bộ test cũ không phát hiện lỗi này.

### F6 — P2: Đã đọc từ popup không cập nhật danh sách/badge đang mở ở Home

**Vị trí:** `CampaignRepository.kt:2401–2410`; `HomeViewModel.kt:init`; `NotificationPopupViewModel.kt:249–278`.

Tái hiện: Home đã tải N chưa đọc, sau đó gọi thành công `acknowledgeNotification(N)` như khi đọc từ popup.

**Thực tế:** repository phát MarkedRead nhưng HomeViewModel không subscribe `notificationEvents`. Invalidate repository cache không cập nhật `HomeUiState` đang giữ trong RAM. N vẫn hiển thị chưa đọc và badge vẫn còn cho tới một lần refresh. Khi offline, nút Đã đọc còn chưa phát cập nhật local cho danh sách.

**Test:** `successful_popup_ack_must_update_the_already_loaded_home_notification_list`.

**Sửa:** Home subscribe sự kiện đã scope UID/generation hoặc dùng một nguồn trạng thái chung; cập nhật danh sách và badge. Áp pending ack bền vững lên dữ liệu UI/cache để fetch cũ không đưa bản tin về chưa đọc. Tránh sự kiện đọc tất cả từ tài khoản A trả muộn áp sang B. Không buộc refresh dashboard toàn phần để đánh dấu một bản tin.

## 3. Điểm còn thiếu trong kiểm chứng

Đây là các khoảng trống kiểm chứng, chưa tính thành lỗi mới đã tái hiện:

- Chưa chạy trên thiết bị/emulator thật, chưa chứng minh UI font lớn và dialog chồng nhau bằng ảnh/test Compose thực tế.
- Chưa chạy PostgreSQL nhiều connection để tranh lượt cuối; PGlite test tuần tự không đủ chứng minh concurrency production.
- Backend test có tên “beyond top 50” nhưng tạo filler **cũ hơn** popup; popup vẫn nằm trong top 50 mới nhất. Cần sửa fixture để có ít nhất 50 bản tin **mới hơn** popup rồi assert popup không nằm trong query limit 50 trước khi claim. Source RPC hiện lọc trực tiếp nên chưa thấy lỗi limit 50 trong triển khai.
- Test “anon forbidden” trong runner gọi không JWT bằng role mặc định của fixture, chưa thực sự SET ROLE anon. Cần kiểm tra quyền EXECUTE bằng anon thật; các REVOKE trong migration đã có.
- `notificationEvents` chưa mang UID/generation; các hàm popup repository chưa nhận context danh tính kỳ vọng. ViewModel cũng chưa kiểm tra session generation/request sequence như kế hoạch. Cần test request trả muộn và A→B→A, cùng ranh giới gửi ack; không chỉ so UID cuối request.
- Báo cáo Gemini nêu đã kiểm tra bằng bộ test Jetpack Compose nhưng các test popup mới đọc được là Robolectric ViewModel/store, không có kiểm tra render Compose. Cần ghi lại đúng phạm vi đã kiểm chứng.

## 4. Kế hoạch sửa cho Gemini

1. **F1 + F2:** tách outbox ack khỏi claim scheduler. Sync phải độc lập với consumed session; còn pending thì chưa claim. Chống concurrent retry, kiểm tra tài khoản trước/sau request và trước khi xóa pending.
2. **F3 + F5:** chuẩn hóa snapshot lifecycle; tách claim session và presentation session. Mọi phiên tiếp tục một popup cũng được tính là đã dùng suất local, nhưng không ghi thêm lượt server.
3. **F4:** bổ sung cổng presentation ở host/state machine, giữ claim qua blocker, chặn route khóa và các modal đang thao tác, giải quyết trạng thái readiness lúc khởi động.
4. **F6:** đồng bộ Home/badge/cache/pending ack qua state/event có scope; bảo vệ khỏi response cũ.
5. Bổ sung test tích hợp identity, lifecycle thật, nhiều blocker, late response, ack thất bại rồi thành công; sửa fixture backend bị kiểm chứng sai như mục 3.
6. Chạy lại test ban đầu + bộ regression mới + build. Nếu không cần thay đổi backend thì không tạo migration chỉ để đủ hình thức. Nếu cần sửa SQL và 067 có thể đã được áp dụng, tạo migration tiếp nối thay vì chỉ sửa 067.
7. Viết báo cáo vòng 2, phân biệt test đã pass với UI/concurrency/live chưa xác minh. Không thay đổi giới hạn 5 hoặc xóa pending để làm test xanh.

## 5. Lệnh kiểm chứng đã chạy

Backend:

```powershell
node supabase/tests/run-admin-direct-notification-popup-test.mjs
```

Android ban đầu và build:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests 'com.ntt.crosstest.ui.viewmodels.NotificationPopupViewModelTest' --tests 'com.ntt.crosstest.data.NotificationPendingAckStoreTest' --tests 'com.ntt.crosstest.localization.LocalizationHardeningTest' assembleDebug
```

Regression bổ sung (hiện **FAIL 6/6**, cần xanh sau khi sửa):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest --tests 'com.ntt.crosstest.ui.viewmodels.NotificationPopupReviewRegressionTest'
```

Gradle lần đầu bị giới hạn quyền ghi cache ngoài workspace; chạy lại với quyền thực thi phù hợp đã thành công. Đây không phải lỗi build của sản phẩm.

## 6. Phạm vi thay đổi của lần kiểm tra này

Chỉ bổ sung file regression test, log/XML bằng chứng, tài liệu này và prompt sửa vòng 2. Không sửa các file sản phẩm Gemini đã triển khai; các test mới đang đỏ có chủ đích để bàn giao lỗi có thể tái hiện.
