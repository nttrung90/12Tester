# Báo cáo sửa lỗi Popup thông báo riêng của Admin — Vòng 3

Ngày thực hiện: **30/10/2026**  
Môi trường thực hiện: Workspace local (`F:\AI\12Tester`)  
Tình trạng nghiệm thu: **Đã sửa hoàn chỉnh 4 lỗi sản phẩm (G1–G4) và sửa runner kiểm thử concurrency (T1). Toàn bộ 37/37 Android tests và 11/11 Backend tests PASS từ báo cáo XML/máy thực tế; `assembleDebug` BUILD SUCCESSFUL.**  
Không tự ý chạy migration lên Supabase production, không deploy service, không phát hành APK.

---

## 1. Bảng chi tiết khắc phục 4 lỗi sản phẩm (G1–G4) và runner concurrency (T1)

| Mã lỗi | Mức độ | Nguyên nhân gốc rễ | Giải pháp kỹ thuật đã triển khai | File sửa | Test chứng minh |
|---|---|---|---|---|---|
| **G1** | **P2** | Trước đây `onMarkAsRead` chỉ ghi pending store và đóng popup; việc phát event `MarkedRead` và đưa vào `localReadNotificationIds` bị hoãn lại cho tới khi RPC `acknowledge_my_notification` thành công. Khi gặp lỗi mạng (HTTP 503 hoặc offline), danh sách Home và badge không được cập nhật dù người dùng đã bấm "Đã đọc". | Ngay sau khi ghi ý định đọc bền vững vào `NotificationPendingAckStore` thành công (trước khi RPC network chạy), gọi ngay `campaignRepository.notifyNotificationLocallyRead(uid, notifId)` để lập tức ghi nhận vào tập đọc cục bộ và phát sự kiện `NotificationEvent.MarkedRead`. Home nhận sự kiện và cập nhật giao diện/badge ngay lập tức, không phụ thuộc vào kết nối mạng. Nếu ghi local thất bại thì không đổi UI. | `NotificationPopupViewModel.kt`, `CampaignRepository.kt`, `HomeViewModel.kt` | `offline_popup_read_must_update_the_existing_home_list_before_server_sync` (PASS) |
| **G2** | **P2** | Lớp phủ đọc cục bộ trước đây chỉ được áp dụng trong `getMyNotifications`. Hàm `getMyDashboard` trả về raw `MyDashboardResult` từ server; khi response dashboard được gửi đi trước lúc bấm đọc nhưng trả về sau đó, nó mang trạng thái `is_read = false` cũ và ghi đè lên danh sách Home, làm bản tin đã đọc bị hiển thị unread trở lại. | Áp dụng quy tắc phủ đọc cục bộ (`isNotificationLocallyRead`) đồng nhất cho toàn bộ các đường nhận dữ liệu thông báo: `getMyDashboard` (cả kết quả decode mạng lẫn fast path RAM cache), `getMyNotifications` (cả network lẫn cache slice), và khi khôi phục snapshot đĩa `loadSync` trong `HomeViewModel`. Mọi response trả về muộn đều được chuẩn hóa trạng thái `isRead = true` trước khi chuyển giao cho caller. | `CampaignRepository.kt`, `HomeViewModel.kt` | `a_dashboard_started_before_ack_must_not_return_unread_after_ack_succeeds` (PASS) |
| **G3** | **P1** | Sự kiện `NotificationEvent.MarkedAllRead` không mang ngữ cảnh người dùng (`ownerId`, `generation`). Khi User A gửi request `markAllNotificationsAsRead` bị giữ response, sau đó người dùng đăng xuất và đăng nhập User B; response cũ của A quay về và phát `MarkedAllRead` không ngữ cảnh, làm Home của User B bị đánh dấu đọc toàn bộ và badge về 0. Đồng thời `localReadNotificationIds` trước đây dùng chung một tập cho mọi user. | 1. Bổ sung `ownerId: String` và `generation: Long` vào toàn bộ `NotificationEvent` (`MarkedRead`, `MarkedAllRead`, `Deleted`).<br>2. Chụp context `requestUid` và `requestGen` trước request; sau await, nếu tài khoản đã đổi (`currentUid != requestUid || currentGen != requestGen`), lập tức hủy bỏ việc ghi cache và không phát event.<br>3. `HomeViewModel` và `NotificationPopupViewModel` kiểm tra `event.ownerId == currentUid && event.generation == currentGen`, lập tức loại bỏ sự kiện từ tài khoản khác.<br>4. Quản lý `localReadNotificationIds` theo từng `userId` riêng biệt (`ConcurrentHashMap<String, MutableSet<String>>`), xóa sạch khi `clearMemoryCache()`. | `Models.kt`, `CampaignRepository.kt`, `HomeViewModel.kt`, `NotificationPopupViewModel.kt` | `a_late_mark_all_read_from_user_a_must_not_mark_user_b_home_list_as_read` (PASS) |
| **G4** | **P1** | Khi RPC `claim_my_notification_popup` của bản tin N đang bay, người dùng đọc N trực tiếp từ danh sách (`markNotificationAsRead`). Lúc này popup đang ở trạng thái `Idle` nên bỏ qua event `MarkedRead`. Khi response claim cũ trả về `claimed`, ViewModel chỉ kiểm tra `pendingAckStore` (mà đọc từ danh sách thì không ghi vào pending ack store), dẫn đến việc vẫn hiển thị popup cho bản tin vừa được đọc trong danh sách. | 1. Khi claim response trả về `claimed`, kiểm tra cả `campaignRepository.isNotificationLocallyRead(currentUid, notif.id)` và `pendingAckStore.isPendingAck(currentUid, notif.id)`. Nếu bản tin đã được đọc cục bộ (từ danh sách, bottom sheet hoặc ack), lập tức hủy bỏ việc trình bày (`_uiState = Idle`), tiêu thụ session hiện tại theo semantics receipt server và kích hoạt sync.<br>2. Khi chuyển từ `Held` sang `Showing` (sau khi gỡ blocker), kiểm tra lại `isNotificationLocallyRead`; nếu đã đọc trong thời gian chờ blocker thì chuyển sang `Idle`. | `NotificationPopupViewModel.kt`, `CampaignRepository.kt` | `a_late_claim_must_not_show_a_notification_already_read_from_the_list` (PASS) |
| **T1** | **P2** | File `run-admin-popup-concurrency-test.mjs` có 2 lỗi SQL nghiêm trọng ở bước setup: gọi SELECT notification của target dưới role `authenticated` của admin nên bị RLS chặn trả về 0 dòng (`notifRow.id` lỗi undefined); và UPDATE bảng `notification_popup_states` bằng role `authenticated` nên bị PostgreSQL báo lỗi `permission denied`. Ngoài ra còn fallback `DATABASE_URL` có thể nhầm database, thiếu dependency `pg`, và dùng UUID cứng không cleanup. | 1. Cấu hình runner chỉ chấp nhận `process.env.TEST_DATABASE_URL`, không fallback `DATABASE_URL`.<br>2. Tách rõ vai trò kết nối: setup người dùng, truy vấn ID thông báo và fast-forward `display_count = 4` bằng kết nối quản trị (administrative superuser / service_role); chỉ bước gửi thông báo admin và các connection gọi `claim_my_notification_popup` mới chạy dưới role `authenticated` với JWT tương ứng để kiểm chứng bảo mật thật sự.<br>3. Tạo dynamic UUID cho users/notification/session (`crypto.randomUUID()`), cleanup toàn bộ fixtures trong khối `finally`.<br>4. Khai báo dependency `pg` trong `supabase/tests/package.json` và bổ sung test probe xác minh chuỗi SQL setup thành công 100% trên PGlite. | `run-admin-popup-concurrency-test.mjs`, `package.json`, `run-verify-concurrency-runner-setup.mjs` | `run-verify-concurrency-runner-setup.mjs` (PASS) |

---

## 2. Danh sách file đã sửa đổi và thêm mới

1. **`app/src/main/java/com/ntt/crosstest/data/Models.kt`** *(Sửa)*:
   - Gắn `ownerId: String` và `generation: Long` vào interface `NotificationEvent` và các lớp con (`MarkedRead`, `MarkedAllRead`, `Deleted`).
2. **`app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`** *(Sửa)*:
   - Chuyển `localReadNotificationIds` sang quản lý theo `ConcurrentHashMap<String, MutableSet<String>>` theo `userId`.
   - Thêm các hàm `recordLocalReadNotification`, `isNotificationLocallyRead`, `notifyNotificationLocallyRead`.
   - Bổ sung lớp phủ đọc cục bộ cho `getMyDashboard` (kết quả mạng và RAM cache).
   - Kiểm tra boundary UID/generation trước khi emit event hoặc ghi cache trong `markNotificationAsRead`, `markAllNotificationsAsRead`, `deleteNotification`, `acknowledgeNotification`.
3. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`** *(Sửa)*:
   - Trong `observeNotificationEvents()`: kiểm tra `event.ownerId == currentUid && event.generation == currentGen`.
   - Khi nạp snapshot từ đĩa: áp dụng lớp phủ `isNotificationLocallyRead` lên danh sách thông báo và số lượng badge.
4. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupViewModel.kt`** *(Sửa)*:
   - Trong `onMarkAsRead()`: gọi `campaignRepository.notifyNotificationLocallyRead` ngay sau khi lưu đĩa thành công (G1).
   - Trong `tick()`: khi nhận kết quả claim, kiểm tra `campaignRepository.isNotificationLocallyRead` để ngăn chặn stale response mở lại bản tin đã đọc (G4).
   - Trong `observeBlockers()`: kiểm tra lại trạng thái đọc cục bộ khi chuyển từ `Held` sang `Showing`.
   - Trong `observeExternalNotificationEvents()`: bỏ qua sự kiện không thuộc về `activeUserId`.
5. **`supabase/tests/run-admin-popup-concurrency-test.mjs`** *(Sửa)*:
   - Loại bỏ fallback `DATABASE_URL`, chỉ nhận `TEST_DATABASE_URL`.
   - Tách bước setup/fast-forward quyền quản trị khỏi bước claim quyền authenticated; dùng dynamic UUID và cleanup fixtures trong `finally`.
6. **`supabase/tests/package.json`** *(Sửa)*:
   - Thêm `pg` vào `devDependencies`.
7. **`supabase/tests/run-verify-concurrency-runner-setup.mjs`** *(Mới)*:
   - Script kiểm chứng tính đúng đắn của chuỗi SQL setup trong runner concurrency trên PGlite.

---

## 3. Kết quả kiểm thử thực tế từ XML và Terminal

### 3.1. Kết quả Unit Tests Android (Đọc trực tiếp từ file XML)

Đường dẫn thư mục kết quả: `app/build/test-results/testDebugUnitTest/`

| File báo cáo XML | Số test | Pass | Fail | Skipped | Thời gian |
|---|---|---|---|---|---|
| `TEST-com.ntt.crosstest.ui.viewmodels.NotificationPopupViewModelTest.xml` | 13 | 13 | 0 | 0 | 0.428s |
| `TEST-com.ntt.crosstest.ui.viewmodels.NotificationPopupReviewRegressionTest.xml` | 6 | 6 | 0 | 0 | 1.424s |
| `TEST-com.ntt.crosstest.ui.viewmodels.NotificationPopupRound3RegressionTest.xml` | 4 | 4 | 0 | 0 | 0.171s |
| `TEST-com.ntt.crosstest.data.NotificationPendingAckStoreTest.xml` | 2 | 2 | 0 | 0 | 1.914s |
| `TEST-com.ntt.crosstest.localization.LocalizationHardeningTest.xml` | 12 | 12 | 0 | 0 | 0.650s |
| **Tổng cộng Android** | **37** | **37** | **0** | **0** | **4.587s** |

*Chi tiết 4 bài regression mới của vòng 3:*
- `offline_popup_read_must_update_the_existing_home_list_before_server_sync`: **PASS** (G1)
- `a_dashboard_started_before_ack_must_not_return_unread_after_ack_succeeds`: **PASS** (G2)
- `a_late_mark_all_read_from_user_a_must_not_mark_user_b_home_list_as_read`: **PASS** (G3)
- `a_late_claim_must_not_show_a_notification_already_read_from_the_list`: **PASS** (G4)

### 3.2. Kết quả Backend Tests (PGlite Runner)
Lệnh thực hiện:
```bash
node supabase/tests/run-admin-direct-notification-popup-test.mjs
```
Kết quả:
```
=== LOADING ACTIVE MIGRATIONS (062 THROUGH 067) ===
Applying 062_consolidated_baseline.sql...
Applying 063_report_crypto_search_path.sql...
Applying 064_explicit_report_context.sql...
Applying 065_remove_dhash_hardblock.sql...
Applying 066_push_and_carryover_hardening.sql...
Applying 067_admin_direct_notification_popup.sql...
=== RUNNING ADMIN DIRECT NOTIFICATION POPUP TESTS ===
PASS: Admin direct send registers notification and popup state with count 0
PASS: Broadcast does NOT register popup state
PASS: Member and anon are forbidden from sending notifications and executing popup RPCs
PASS: Client directly inserting fake notification cannot get popup
PASS: Trigger prevents client from modifying protected fields and unmarking read
PASS: User B cannot claim or acknowledge User A notification
PASS: Claim quotas 1-5, retry idempotency, and session 6 blocked
PASS: Acknowledge stops popup and subsequent claim/session retry returns stopped
PASS: Deleting notification sets receipt notification_id null and stops replay without picking another
PASS: Finds eligible notification beyond top 50 items and preserves created_at order
PASS: Locked account cannot claim popup
=== ALL ADMIN DIRECT NOTIFICATION POPUP TESTS PASSED ===
```
**Trạng thái:** **11/11 tests PASS**.

### 3.3. Xác minh tính đúng đắn của chuỗi SQL Setup Runner Concurrency
Lệnh thực hiện:
```bash
node supabase/tests/run-verify-concurrency-runner-setup.mjs
```
Kết quả:
```
PASS: Concurrency runner setup SQL verified successfully on PGlite.
```
**Trạng thái:** Cả bước truy vấn notification ID và bước fast-forward display count = 4 đều thành công 100%, không còn lỗi 0-row RLS hay permission denied.

### 3.4. Kiểm tra biên dịch ứng dụng (`assembleDebug`)
Lệnh thực hiện:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```
Kết quả:
```
BUILD SUCCESSFUL in 11s
39 actionable tasks: 4 executed, 35 up-to-date
```
**Trạng thái:** Biên dịch và đóng gói APK thành công 100%.

---

## 4. Giới hạn kỹ thuật và các điểm chưa kiểm chứng

1. **Kiểm thử Concurrency đa kết nối PostgreSQL thật:**
   - Script `supabase/tests/run-admin-popup-concurrency-test.mjs` đã được sửa hoàn chỉnh cấu trúc và logic phân quyền setup/claim.
   - Do môi trường local không có sẵn service PostgreSQL cổng 5432 và không có biến môi trường `TEST_DATABASE_URL`, runner in thông báo hướng dẫn và thoát với mã 0:
     ```
     ========================================================================
     NOTICE: TEST_DATABASE_URL is not set.
     Skipping real multi-connection PostgreSQL concurrency test.
     ========================================================================
     ```
   - **Xác nhận rõ ràng:** Chưa chạy kiểm thử tranh chấp đồng thời trên PostgreSQL nhiều kết nối thật; tính an toàn tương tranh hiện được kiểm chứng gián tiếp thông qua ràng buộc logic SQL (`pg_advisory_xact_lock`, `FOR UPDATE`, CHECK `display_count <= 5`, UNIQUE `(user_id, foreground_session_id)`) và probe PGlite.
2. **Kiểm thử giao diện Jetpack Compose trên Emulator/Thiết bị vật lý thật:**
   - Toàn bộ 37 bài test Android đã chạy và pass là kiểm thử trạng thái logic (Robolectric JVM Unit Tests / MockEngine).
   - Chưa kiểm thử render giao diện thực tế (độ cuộn nội dung, hiển thị font lớn, hiển thị trên thiết bị thật có tai thỏ/đục lỗ) do không có emulator/thiết bị ADB kết nối trong phiên CLI.
   - Khuyến nghị đội QA thực hiện kiểm thử trên thiết bị vật lý trước khi phát hành production.
3. **Trạng thái Database Live:**
   - Không có migration nào được áp dụng lên cơ sở dữ liệu Supabase live; migration `067_admin_direct_notification_popup.sql` đang lưu trữ an toàn trong repository.
