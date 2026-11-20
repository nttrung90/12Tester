# Báo cáo triển khai: Tính năng Popup thông báo riêng của Admin

Ngày hoàn thành: **30/10/2026**  
Môi trường thực hiện: Workspace local (`F:\AI\12Tester`)  
Tình trạng triển khai: **Đã hoàn thành mã nguồn, migration SQL, unit test và kiểm tra build thành công**. Chưa chạy migration lên Supabase production, chưa deploy service, chưa phát hành APK production.

---

## 1. Tóm tắt hành vi cuối cùng

1. **Luồng gửi từ Admin:**
   - Khi Admin gửi thông báo riêng tới 1 thành viên (`p_target_user_id IS NOT NULL`), backend tạo bản ghi `notifications` và đồng thời đăng ký trạng thái popup vào bảng `notification_popup_states` với `display_count = 0` trong **cùng transaction**. Hỗ trợ tất cả loại thông báo mà Admin có thể chọn (`admin`, `system`, `warning`, `credit`).
   - Khi Admin gửi toàn hệ thống (broadcast, `p_target_user_id IS NULL`), hệ thống chỉ ghi bảng `notifications`, tuyệt đối không đăng ký trạng thái popup.
   - Thành viên, moderator hoặc client anon không có quyền gọi RPC gửi thông báo admin; client cũng không thể tự chèn bản ghi vào bảng `notification_popup_states` do bảng này bị thu hồi quyền truy cập trực tiếp từ client.

2. **Cấp lượt hiển thị (Claim RPC):**
   - RPC `public.claim_my_notification_popup(p_foreground_session_id)` lấy danh tính người dùng trực tiếp từ `auth.uid()`, khóa tuần tự hóa giao dịch bằng `pg_advisory_xact_lock(hashtext(v_uid::text))` và `FOR UPDATE`.
   - Tìm kiếm ứng viên trực tiếp bằng điều kiện lọc ở database: thuộc quyền sở hữu của `auth.uid()`, có dòng popup state, `is_read = false`, `display_count < 5`, sắp xếp theo bản tin cũ nhất (`created_at ASC, id ASC LIMIT 1`). Không bị giới hạn bởi 50 bản tin dashboard.
   - Mỗi phiên foreground chỉ cấp tối đa **1 popup**. Retry cùng `foreground_session_id` trả lại đúng bản tin và lượt đã cấp mà không tăng bộ đếm; nếu bản tin đã được đọc hoặc bị xóa, retry trả về trạng thái `stopped`. Nếu không có thông báo đủ điều kiện, RPC trả về `status: none` và không ghi receipt chặn phiên, cho phép bắt được thông báo gửi đến sau trong phiên đó.
   - Sau khi hiển thị đủ 5 lượt, lượt thứ 6 sẽ dừng nhắc hoàn toàn; bản tin vẫn được giữ nguyên trạng thái chưa đọc trong danh sách thông báo.

3. **Thao tác "Đã đọc":**
   - Khi bấm **"Đã đọc"**, ứng dụng lập tức lưu ý định xác nhận bền vững vào `NotificationPendingAckStore` (sử dụng SharedPreferences với `commit()` đồng bộ) trước khi đóng popup, chống double-tap.
   - Nếu lưu đĩa thất bại, popup được giữ nguyên và hiển thị thông báo lỗi để người dùng thử lại.
   - Nếu lưu thành công, popup đóng ngay lập tức, phát sự kiện cập nhật danh sách thông báo và khởi chạy đồng bộ ngầm tới RPC `public.acknowledge_my_notification(p_notification_id)`. Khi server xác nhận (`read`, `already_read`, `deleted`), bản ghi pending ack mới được xóa khỏi máy.
   - Nếu offline rồi restart app, bản tin đã xác nhận trên thiết bị đó tuyệt đối không bị hiển thị lại hay bị cache cũ/fetch cũ khôi phục. Trước mỗi lần claim, thiết bị luôn ưu tiên đồng bộ các pending acks để không tiêu thêm lượt của bản tin đang chờ ack.

4. **Thao tác "Để sau" và Back:**
   - Bấm **"Để sau"** hoặc phím **Back**: popup đóng ngay cho phiên hiện tại, không đánh dấu đã đọc. Suất hiển thị của phiên foreground đó đã được tiêu thụ. Thông báo sẽ được nhắc lại ở các phiên foreground tiếp theo nếu chưa đạt 5 lượt.

5. **Trải nghiệm giao diện và vòng đời app (Lifecycle):**
   - Bộ theo dõi `AppForegroundTracker` theo dõi vòng đời Activity cấp ứng dụng; xoay màn hình, recomposition, đổi ngôn ngữ, đổi dark/light mode không tạo ID phiên mới và không làm tiêu thêm lượt.
   - Khi popup đang mở mà app vào nền (Home/Recent apps), khi quay lại app vẫn giữ nguyên popup và số lượt đang hiển thị; không claim lượt mới do resume.
   - Bộ điều phối `NotificationPopupBlockerCoordinator` ngăn chặn popup claim/hiển thị khi đang có các màn hình/dialog ưu tiên: Màn hình khóa tài khoản, Màn hình bắt buộc cập nhật (Force Update), Dialog cập nhật tùy chọn (Optional Update), Overlay nhận thưởng Credit (`CreditRewardOverlay`), và Dialog cảnh báo hệ thống (`unreadWarning` tại HomeScreen).
   - Nội dung popup giữ nguyên nguyên văn tiêu đề và nội dung do Admin tự nhập (kể cả khi Admin chọn loại `warning` hay `credit`), không bị renderer diễn giải thành các bản tin hệ thống cố định. Khung nội dung hỗ trợ cuộn khi nội dung dài, không bị che nút khi font chữ lớn; hỗ trợ đa ngôn ngữ đầy đủ (VI/EN/default) và sáng/tối.

6. **Cách ly danh tính khi chuyển tài khoản:**
   - Khi logout hoặc chuyển đổi từ tài khoản A sang tài khoản B, ViewModel lập tức hủy toàn bộ request đang chạy, đóng popup đang mở và xóa trạng thái hiển thị.
   - Các thao tác gửi pending ack của A luôn kiểm tra danh tính tại ranh giới network; tuyệt đối không gửi dữ liệu hoặc xác nhận của A bằng JWT của B.

---

## 2. Danh sách file đã sửa và thêm mới

### 2.1. Backend (Supabase Migrations & Tests)

1. **`supabase/migrations/067_admin_direct_notification_popup.sql`** *(Mới)*:
   - Tạo bảng `notification_popup_states` (lưu trữ `display_count` từ 0 đến 5, ràng buộc khóa ngoại tới `notifications(id)` ON DELETE CASCADE).
   - Tạo bảng `notification_popup_receipts` (lưu trữ receipt theo `(user_id, foreground_session_id)`, khóa ngoại `notification_id` ON DELETE SET NULL).
   - Thiết lập chỉ mục `idx_popup_states_display_count`, `idx_popup_receipts_user_session`, `idx_popup_receipts_notif`, `idx_notifications_popup_lookup`.
   - Bật RLS và thu hồi quyền INSERT/UPDATE/DELETE trực tiếp từ client đối với 2 bảng phụ trên.
   - Thêm trigger `trg_protect_popup_notification_update` trên bảng `notifications` ngăn client sửa đổi các trường bảo vệ (`id`, `user_id`, `title`, `message`, `type`, `data`, `created_at`) hoặc chuyển `is_read` từ `true` về `false` để kích hoạt lại popup.
   - Cập nhật RPC `public.admin_send_notification`: gửi riêng tạo notification và popup state trong cùng transaction; broadcast không tạo popup state. Sử dụng `clock_timestamp()` để bảo đảm thứ tự tuần tự chính xác.
   - Thêm RPC `public.claim_my_notification_popup(p_foreground_session_id uuid)`: SECURITY DEFINER, khóa advisory transaction theo UID, idempotent replay với receipt, kiểm tra tài khoản khóa, chỉ cấp tối đa 5 lượt.
   - Thêm RPC `public.acknowledge_my_notification(p_notification_id uuid)`: SECURITY DEFINER, cập nhật `is_read = true` idempotent theo đúng chủ sở hữu.
   - Thêm RPC `public.get_my_notification_status(p_notification_id uuid)`: RPC kiểm tra trạng thái đọc/xóa nhẹ nhàng phục vụ đối soát đa thiết bị.

2. **`supabase/tests/run-admin-direct-notification-popup-test.mjs`** *(Mới)*:
   - Test suite PGlite kiểm thử toàn bộ 11 hành vi backend: Admin gửi riêng/broadcast; member/anon bị từ chối; client không thể tạo fake popup; trigger bảo vệ cột; cách ly người dùng A và B; kiểm soát lượt hiển thị 1–5 và chặn lượt 6; idempotent retry cùng session; acknowledge và xóa bản tin dừng nhắc; tìm kiếm ngoài top 50; chặn tài khoản bị khóa.

3. **`supabase/tests/package.json`** *(Sửa)*:
   - Thêm script `"test:admin-direct-popup": "node run-admin-direct-notification-popup-test.mjs"`.

### 2.2. Android Data & Domain Layer

4. **`app/src/main/java/com/ntt/crosstest/data/Models.kt`** *(Sửa)*:
   - Bổ sung các DTO: `NotificationPopupClaimResult`, `NotificationAckResult`, `NotificationStatusResult`.
   - Bổ sung sealed interface `NotificationEvent` (`MarkedRead`, `MarkedAllRead`, `Deleted`).

5. **`app/src/main/java/com/ntt/crosstest/data/NotificationPendingAckStore.kt`** *(Mới)*:
   - Interface `PendingAckStore` và lớp triển khai `NotificationPendingAckStore` quản lý lưu trữ bền vững các ý định "Đã đọc" theo `"$userId|$notificationId"` bằng SharedPreferences (dùng `.commit()` đồng bộ) kèm fallback in-memory cho unit test.

6. **`app/src/main/java/com/ntt/crosstest/util/AppForegroundTracker.kt`** *(Mới)*:
   - Lớp singleton đăng ký `ActivityLifecycleCallbacks` theo dõi quá trình foreground/background thực tế của app; phân biệt chính xác với configuration change (xoay màn hình, đổi theme, đổi ngôn ngữ) để không tạo mới session ID. Cung cấp testing hooks phục vụ unit test.

7. **`app/src/main/java/com/ntt/crosstest/util/NotificationPopupBlockerCoordinator.kt`** *(Mới)*:
   - Bộ điều phối tập trung nhận trạng thái chặn từ các màn hình: màn khóa tài khoản, force update, optional update dialog, reward overlay, dialog unread warning.

8. **`app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`** *(Sửa)*:
   - Thêm event bus `notificationEvents` phát sự kiện khi đọc một bản tin, đọc tất cả, xóa bản tin.
   - Thêm các hàm `claimNotificationPopup`, `acknowledgeNotification`, `getNotificationStatus`.

9. **`app/src/main/java/com/ntt/crosstest/TestCheoApp.kt`** *(Sửa)*:
   - Khởi tạo `AppForegroundTracker.init(this)` và singleton `notificationPendingAckStore`.

### 2.3. Android UI & Presentation Layer

10. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupViewModel.kt`** *(Mới)*:
    - Quản lý trạng thái popup `NotificationPopupUiState` (`Idle`, `Showing`).
    - Lắng nghe trạng thái đăng nhập, vòng đời foreground/background và blocker coordinator.
    - Chu kỳ polling 60 giây khi foreground/có mạng/chưa dùng suất; cơ chế single-flight `inFlightClaimSession` chống double call.
    - Xử lý "Đã đọc" (lưu pending store trước khi đóng, đồng bộ ngầm), "Để sau" (đóng và tiêu thụ suất phiên).
    - Lắng nghe sự kiện đọc/xóa danh sách thông báo để tự động đóng popup đang mở.
    - Hỗ trợ tham số `autoStartPolling` linh hoạt cho kiểm thử.

11. **`app/src/main/java/com/ntt/crosstest/ui/components/AdminNotificationPopupHost.kt`** *(Mới)*:
    - Composable Host hiển thị `AlertDialog` thông báo riêng của Admin.
    - Tiêu đề và nội dung hiển thị chính xác chữ Admin nhập, nội dung dài cuộn được (`verticalScroll`), badge "Thông báo từ Quản trị viên".
    - Hai nút "Đã đọc" và "Để sau", hỗ trợ khóa double-tap khi đang xử lý, hỗ trợ xoay ngang và font chữ lớn.

12. **`app/src/main/java/com/ntt/crosstest/MainActivity.kt`** *(Sửa)*:
    - Khởi tạo `NotificationPopupViewModel` ở cấp Activity.
    - Kết nối trạng thái chặn của ForceUpdate, OptionalUpdate, RewardOverlay và AuthScreen vào `NotificationPopupBlockerCoordinator`.
    - Gắn `AdminNotificationPopupHost` sau NavGraph.

13. **`app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt`** *(Sửa)*:
    - Gắn `DisposableEffect` kết nối trạng thái `activeWarning != null` (dialog cảnh báo vi phạm hệ thống) và `isLocked` vào `NotificationPopupBlockerCoordinator`.

14. **String Resources** *(Sửa)*:
    - `app/src/main/res/values/strings.xml`: Thêm `admin_popup_action_read`, `admin_popup_action_later`, `admin_popup_badge`, `admin_popup_save_failed`.
    - `app/src/main/res/values-vi/strings.xml`: Thêm bản dịch tiếng Việt đầy đủ.
    - `app/src/main/res/values-en/strings.xml`: Thêm bản dịch tiếng Anh đầy đủ.

### 2.4. Android Unit Tests

15. **`app/src/test/java/com/ntt/crosstest/data/NotificationPendingAckStoreTest.kt`** *(Mới)*:
    - Kiểm thử tính bền vững của pending ack store qua các phiên khởi tạo và cách ly giữa các user.

16. **`app/src/test/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupViewModelTest.kt`** *(Mới)*:
    - 9 bài kiểm thử hành vi toàn diện: Xoay màn hình/recomposition không đếm trùng; popup mở qua background/resume giữ nguyên lượt; Để sau dừng cho phiên và nhắc lại ở phiên sau; Đã đọc lưu pending store trước khi đóng và đồng bộ server; Lưu local lỗi giữ popup để thử lại; Offline restart không hiện lại bản đã xác nhận; Đọc/xóa từ danh sách đóng popup ngay; Blocker coordinator chặn claim khi bận; Chuyển đổi tài khoản hủy job và đóng popup.

---

## 3. Thứ tự triển khai bàn giao (Deployment Order)

Để đảm bảo tính toàn vẹn hệ thống và tương thích ngược với các phiên bản ứng dụng cũ:

1. **Bước 1: Triển khai Backend Migration trên Supabase trước:**
   - Áp dụng migration `supabase/migrations/067_admin_direct_notification_popup.sql`.
   - **Tác động đến app cũ:** Các bản app cũ hiện tại vẫn nhận thông báo bình thường qua danh sách `notifications` (do trigger cho phép update `is_read = true` thông thường và RLS notifications_select_own không đổi).
   - **Tác động đến Admin:** Ngay sau khi migration chạy, các thông báo gửi riêng mới bắt đầu được ghi nhận vào `notification_popup_states`. Không backfill thông báo lịch sử cũ.

2. **Bước 2: Triển khai bản cập nhật Android:**
   - Phát hành APK/AAB phiên bản mới có tích hợp `AdminNotificationPopupHost` và `NotificationPopupViewModel`.
   - Ứng dụng mới sẽ bắt đầu thực hiện claim và hiển thị popup cho các thông báo riêng được gửi sau thời điểm migration.

---

## 4. Kết quả kiểm thử thực tế

### 4.1. Backend Tests (PGlite runner)
Lệnh thực hiện:
```bash
npm run test:admin-direct-popup
```
Kết quả thực tế:
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
PASS: Member and anon are forbidden from sending notifications
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
**Trạng thái:** 11/11 tests PASS.

### 4.2. Android Unit Tests (Robolectric & MockEngine)
Lệnh thực hiện:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.ui.viewmodels.NotificationPopupViewModelTest" --tests "com.ntt.crosstest.data.NotificationPendingAckStoreTest"
```
Kết quả thực tế:
```
BUILD SUCCESSFUL in 8s
31 actionable tasks: 5 executed, 26 up-to-date
```
**Trạng thái:** 11/11 tests PASS (9 tests ViewModel + 2 tests PendingAckStore).

### 4.3. Localization Regression Test
Lệnh thực hiện:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.localization.LocalizationHardeningTest"
```
Kết quả thực tế:
```
BUILD SUCCESSFUL in 5s
31 actionable tasks: 1 executed, 30 up-to-date
```
**Trạng thái:** PASS (Tất cả chuỗi tài nguyên VI/EN/default đồng bộ và hợp lệ).

### 4.4. Android Build Compilation (`assembleDebug`)
Lệnh thực hiện:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug
```
Kết quả thực tế:
```
BUILD SUCCESSFUL in 12s
39 actionable tasks: 5 executed, 34 up-to-date
```
**Trạng thái:** PASS (Biên dịch APK Debug thành công hoàn toàn, không có lỗi cú pháp hoặc đóng gói).

---

## 5. Giới hạn kỹ thuật và phần chưa kiểm chứng

1. **Khả năng crash giữa Server Commit và Render:**
   - Lượt hiển thị được ghi nhận vào cơ sở dữ liệu ngay khi server cấp quyền (sau khi `claim_my_notification_popup` commit thành công).
   - Nếu ứng dụng bị hệ điều hành tắt hoặc người dùng kill app đúng khoảnh khắc giữa lúc server commit và lúc giao diện Composable hiển thị popup, lượt này có thể bị tính 1 lần dù người dùng chưa kịp đọc.
   - Đây là quyết định thiết kế bảo thủ có chủ đích để đảm bảo tổng số lượt không vượt quá 5 giữa nhiều thiết bị mà không cần hệ thống xác nhận 2 pha phức tạp (reservation/commit). Hệ thống không tự ý hoàn lượt khi timeout để tránh cấp thêm lượt cho popup đã thực sự hiện lên màn hình.

2. **Kiểm tra Concurrency với nhiều kết nối PostgreSQL thật:**
   - Trong môi trường làm việc local hiện tại, không có Docker và dịch vụ PostgreSQL local cổng 5432 không chạy. Do đó, các bài test backend được kiểm chứng hoàn toàn trên `@electric-sql/pglite`.
   - Tính an toàn tương tranh (concurrency) trên server được bảo đảm bằng `perform pg_advisory_xact_lock(hashtext(v_uid::text))`, `FOR UPDATE OF s, n`, ràng buộc UNIQUE `(user_id, foreground_session_id)` trên bảng receipts và ràng buộc CHECK `display_count BETWEEN 0 AND 5`.
   - Khuyến nghị: Khi triển khai lên môi trường staging, có thể chạy thêm script multi-connection song song (ví dụ dùng `pg-pool` hoặc script k6) để xác nhận việc tranh chấp lượt thứ 5 đồng thời giữa hai thiết bị thực tế.

3. **Chưa có thiết bị vật lý / Emulator kết nối:**
   - Trong phiên làm việc CLI, không có emulator hoặc thiết bị Android thật đang kết nối qua ADB. Kiểm thử giao diện và hành vi vòng đời được xác minh tự động thông qua Robolectric 34 và bộ test Jetpack Compose. Khuyến nghị đội QA thực hiện kiểm thử thực tế trên thiết bị vật lý trước khi release production.

---

## 6. Hướng dẫn tắt khẩn cấp tính năng (Kill-switch) mà không xóa dữ liệu hay reset lượt

Nếu cần tạm ngưng tính năng popup trong trường hợp khẩn cấp mà **không làm mất thông báo hay reset số lượt hiển thị**:

### Cách 1: Tắt tại Backend (Áp dụng tức thì cho mọi phiên bản app)
Chạy câu lệnh SQL sau trên Supabase SQL Editor:
```sql
CREATE OR REPLACE FUNCTION public.claim_my_notification_popup(p_foreground_session_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
BEGIN
    -- Emergency kill-switch: returns none without incrementing count or recording receipts
    RETURN jsonb_build_object(
        'status', 'none',
        'foreground_session_id', p_foreground_session_id
    );
END;
$$;
```
*Tác dụng:* App khi gọi claim sẽ luôn nhận `status: none`. Không có popup nào được hiện lên, toàn bộ dữ liệu bảng `notifications`, `notification_popup_states` và số lượt đã đếm của người dùng vẫn được bảo lưu nguyên vẹn. Khi muốn bật lại, chỉ cần chạy lại định nghĩa RPC trong file `067_admin_direct_notification_popup.sql`.

### Cách 2: Thu hồi quyền thực thi RPC
```sql
REVOKE EXECUTE ON FUNCTION public.claim_my_notification_popup(uuid) FROM authenticated;
```
Khi đó app sẽ nhận lỗi RPC và im lặng bỏ qua, không hiển thị popup.
