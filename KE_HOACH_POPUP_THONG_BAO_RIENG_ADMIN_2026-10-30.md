# Kế hoạch popup cho thông báo riêng của admin

Ngày khảo sát: **30/10/2026**. Phạm vi: nghiên cứu source trong workspace và lập kế hoạch; chưa triển khai tính năng, chưa kiểm tra database live, chưa chạy migration hoặc phát hành app.

## 1. Phương án đề xuất

Giữ nguyên luồng **Admin → chọn thành viên → gửi thông báo → bảng `notifications` → hộp thông báo của người nhận**. Bổ sung quyền hiển thị popup và bộ đếm ở backend, cùng một popup host ở cấp ứng dụng.

- Admin gửi riêng cho một người: người đó nhận popup trong app khi có mạng và đang ở màn hình phù hợp.
- Bấm **“Đã đọc”**: popup đóng ngay, thông báo được đánh dấu đã đọc và dừng nhắc.
- Bấm **“Để sau”** hoặc Back: đóng lần hiện tại, không đánh dấu đã đọc; có thể nhắc ở lần vào app tiếp theo.
- Mỗi thông báo được cấp tối đa **5 lượt hiển thị**, tính theo tài khoản và dùng chung giữa các thiết bị. Hết lượt thì dừng popup; bản tin vẫn nằm trong danh sách và vẫn chưa đọc nếu người nhận chưa xác nhận.
- Thông báo gửi toàn bộ và thông báo tự động khác giữ luồng hiện tại.

Đây là popup **bên trong app**. Khi app đóng hoặc chạy nền, chờ lần mở/quay lại app; yêu cầu này không cần thêm FCM, quyền vẽ đè ứng dụng khác hoặc thay thông báo Android hiện có.

## 2. Kết quả khảo sát mã nguồn

Các vị trí dưới đây là mốc của source tại thời điểm khảo sát, có thể đổi khi các thay đổi khác trong workspace tiếp tục được cập nhật.

| Vị trí | Hiện trạng / ý nghĩa |
|---|---|
| `ui/screens/AdminUsersScreen.kt:399` | Đã có dialog gửi riêng, gọi `sendNotification(target.id, ...)`. Gửi toàn bộ dùng cùng luồng nhưng target là `null`. |
| `ui/screens/AdminUsersScreen.kt:818` | Admin có thể chọn loại `admin`, `system`, `warning`, `credit`. Không thể dùng riêng `type == admin` để xác định thông báo gửi riêng. |
| `ui/viewmodels/AdminUsersViewModel.kt:241` | `sendNotification` đã gọi repository và hiển thị kết quả gửi. |
| `data/CampaignRepository.kt:2387` | `adminSendNotification` gọi RPC `admin_send_notification`; tận dụng API và tham số hiện tại. |
| `supabase/migrations/062_consolidated_baseline.sql:2772` | RPC kiểm tra người gọi là admin. Nhánh gửi riêng ghi `data.sender = admin`, `data.broadcast = false`; gửi toàn bộ ghi `broadcast = true`. |
| `data/Models.kt:638` | `UserNotification` đã có ID, người nhận, tiêu đề, nội dung, loại, `data`, `isRead`; chưa có trạng thái lượt popup. |
| `data/CampaignRepository.kt:2311` | Danh sách mặc định chỉ lấy 50 bản tin mới nhất, cache 60 giây. |
| `supabase/migrations/062_consolidated_baseline.sql:5572` | `get_my_dashboard` cũng chỉ lấy 50 bản tin mới nhất. Popup không nên chỉ lọc từ danh sách này vì thông báo riêng có thể bị trôi khỏi 50 bản tin. |
| `data/CampaignRepository.kt:2346–2383` | Đã có đánh dấu đọc một bản tin, đọc tất cả, xóa; cập nhật server rồi vô hiệu hóa cache notifications/dashboard. |
| `ui/viewmodels/HomeViewModel.kt:1087` | Màn Home cập nhật đã đọc theo kiểu lạc quan, hoàn tác khi lỗi. Popup cần thêm cơ chế giữ ý định “Đã đọc” nếu mất mạng. |
| `ui/components/NotificationBottomSheet.kt:260` | Bấm bản tin chưa đọc đang gọi đánh dấu đã đọc. Trạng thái này cũng phải dừng popup. |
| `ui/screens/HomeScreen.kt:177,201` | Refresh khi resume; đã có popup cảnh cáo hệ thống phải xác nhận. Cần điều phối để không chồng popup. |
| `MainActivity.kt:250–261` | Có NavGraph, reward overlay toàn cục và dialog cập nhật; phù hợp gắn popup host dùng chung cho nhiều màn hình. |
| `data/SessionCacheManager.kt` | Có UID, session generation và snapshot thông báo; tận dụng kiểm tra phiên, không dùng snapshot làm nguồn cấp lượt popup. |

**Điểm về tính xác thực:** baseline hiện có policy `notifications_insert_all` cho phép `authenticated` INSERT với `WITH CHECK (true)`; policy UPDATE của chủ bản tin cũng không giới hạn riêng cột `is_read`. Vì vậy, chỉ kiểm tra JSON `sender=admin, broadcast=false` không đủ chứng minh một bản tin thực sự do admin gửi. Cần trạng thái popup riêng chỉ được tạo bởi RPC admin, không tin nhãn do client tự ghi. Chưa xác minh policy trên database live.

Workspace đang có nhiều thay đổi chưa commit. Khi triển khai cần bổ sung trên source hiện tại, giữ các thay đổi đang có.

## 3. Quy ước nghiệp vụ để triển khai

Các quy ước bổ sung dưới đây là đề xuất nhằm làm rõ “một lần hiển thị”:

1. Một phiên foreground là lúc mở app hoặc quay lại app sau khi app thực sự vào nền. Đổi tab, điều hướng, refresh, recompose, đổi ngôn ngữ và xoay màn hình không tạo phiên mới.
2. Mỗi phiên foreground hiển thị tối đa **một popup thông báo riêng**. Nếu có nhiều thông báo, ưu tiên bản cũ nhất còn đủ điều kiện, sắp theo `created_at, id`. Các bản còn lại chờ phiên sau. Cách này có thể khiến thông báo mới chờ nếu còn nhiều bản cũ.
3. Nếu chưa hiện popup nào trong phiên, một thông báo mới được phát hiện khi đang dùng app có thể hiện ngay khi giao diện rảnh. Nếu đã hiện một popup thì chờ phiên sau.
4. Lần thứ 5 vẫn hiện bình thường và vẫn có thể bấm “Đã đọc”; sau khi đóng thì không mở lại lần thứ 6. Không tự đóng popup đang mở ngay lúc bộ đếm đạt 5.
5. Chỉ bản tin gửi riêng **sau khi migration mới được áp dụng** được đăng ký popup. Không tự bật popup hàng loạt cho lịch sử cũ.
6. Áp dụng cho mọi loại được chọn trong màn gửi riêng của admin; thông báo `warning` ở đây không tự trở thành bản ghi `user_warnings` hay thay luồng xử phạt.
7. Đọc từ danh sách, “đọc tất cả” hoặc xóa bản tin đều dừng popup tương ứng. Hết 5 lượt không tự đặt `is_read = true`.
8. Không hiện khi chưa xác thực xong, guest, tài khoản bị khóa, màn đăng nhập, màn bắt buộc cập nhật hoặc đang có một dialog cần ưu tiên.

## 4. Backend: mở rộng luồng cũ

### 4.1. Migration mới

Tên dự kiến: **`067_admin_direct_notification_popup.sql`**, vì thư mục hiện có đến 066. Kiểm tra lại số trống lúc triển khai. Không sửa trực tiếp baseline 062 hoặc chạy lại migration trong archive.

Giữ nguyên chữ ký bốn tham số của `admin_send_notification`. Trong nhánh target khác NULL, dùng `INSERT ... RETURNING id`, rồi tạo trạng thái popup cho đúng ID vừa sinh trong **cùng transaction**. Nhánh broadcast không tạo trạng thái này. Nếu một trong hai thao tác lỗi thì rollback cả hai, tránh báo gửi thành công khi bản tin hoặc trạng thái còn thiếu.

### 4.2. Dữ liệu bổ sung

Đề xuất hai bảng phụ, không sao chép tiêu đề/nội dung:

| Bảng | Trường chính / nhiệm vụ |
|---|---|
| `notification_popup_states` | `notification_id` khóa chính/FK tới `notifications` với `ON DELETE CASCADE`; `display_count smallint NOT NULL DEFAULT 0 CHECK (display_count BETWEEN 0 AND 5)`; `last_display_at timestamptz`. Có dòng ở bảng này nghĩa là bản tin được luồng admin đăng ký popup. |
| `notification_popup_receipts` | `user_id`, `foreground_session_id uuid`, `notification_id` nullable, `display_number`, `claimed_at`; UNIQUE `(user_id, foreground_session_id)`. Lưu kết quả cấp lượt để request retry không tăng bộ đếm hoặc chọn một bản tin khác. FK profile xóa cascade; FK bản tin dùng `ON DELETE SET NULL` để vẫn nhớ phiên đã dùng lượt khi bản tin bị xóa. |

Chỉ hàm backend có quyền tạo/sửa các bảng phụ. App không được INSERT/UPDATE/DELETE trực tiếp; không cấp truy cập cho anon/PUBLIC. RPC xác định người nhận qua `auth.uid()` và join bảng `notifications`, không nhận UID người nhận từ client.

Chỉ cần `notifications.is_read` để dừng nhắc; không thêm một cờ đã đọc độc lập có thể lệch nhau. Nếu sau này cần chứng minh người dùng đã bấm đúng nút popup, đó là yêu cầu audit riêng.

### 4.3. RPC cấp lượt có tính idempotent

Thêm `claim_my_notification_popup(p_foreground_session_id uuid)`:

1. Kiểm tra đăng nhập, profile và trạng thái tài khoản. Dùng khóa transaction theo người nhận để tuần tự hóa các lần claim đồng thời.
2. Nếu đã có receipt cho tài khoản/phiên này, trả lại đúng kết quả cấp lượt trước đó, không tăng đếm. Nếu bản tin đã đọc/xóa thì trả trạng thái dừng thay vì hiển thị lại.
3. Nếu chưa có receipt, tìm bản tin thuộc `auth.uid()`, có dòng popup state, `is_read=false`, `display_count<5`; lấy bản cũ nhất. Lọc điều kiện ở server trước khi giới hạn kết quả, không dựa trên 50 bản tin dashboard.
4. Khóa bản tin và trạng thái tương ứng theo thứ tự nhất quán; kiểm tra lại điều kiện sau khi khóa, tăng đếm một đơn vị và ghi receipt trong cùng transaction.
5. Trả bản tin, số lượt vừa cấp và ID phiên; nếu chưa có bản phù hợp thì trả `none`, không tạo receipt để vẫn phát hiện bản mới trong phiên đó.

Không tách “đọc số lượt” và “ghi số lượt mới” thành hai request. Ràng buộc 0–5, khóa, unique receipt và transaction phải chịu được retry, gọi trùng và hai thiết bị cùng tranh lượt cuối.

**Giới hạn cần ghi rõ:** lượt được ghi nhận khi server cấp quyền ngay trước khi vẽ popup. Nếu app bị kill đúng khoảng giữa server commit và lúc popup được vẽ, có thể mất một lượt dù người dùng chưa nhìn thấy. Đây là lựa chọn bảo thủ để bảo đảm không cấp quá 5 lượt giữa nhiều thiết bị. Không thể bảo đảm tuyệt đối vừa đếm đúng số lần mắt người dùng nhìn thấy vừa không vượt 5 trong mọi tình huống crash/mạng chỉ bằng một RPC. Không tự hoàn lượt khi timeout vì có thể cấp lại lượt đã thực sự hiện. Nếu cần độ chính xác cao hơn phải bổ sung cơ chế reservation/confirm và thống nhất lại định nghĩa lượt.

### 4.4. Đã đọc và quyền truy cập

- Tận dụng trạng thái `is_read` và các thao tác đọc/xóa hiện có. Có thể thêm RPC `acknowledge_my_notification(p_notification_id)` để trả kết quả rõ ràng `read/already_read/deleted`, xác nhận đúng chủ bản tin, cập nhật idempotent.
- RPC mới dùng `SECURITY DEFINER`, search path cố định an toàn, tên bảng đầy đủ schema; thu hồi EXECUTE mặc định của PUBLIC/anon, chỉ grant cho authenticated và kiểm tra chủ sở hữu bên trong.
- Không mở quyền client sửa số lượt, receipt hoặc chuyển bản tin sang người khác. Đánh giá query bằng EXPLAIN với dữ liệu lớn; bổ sung index theo bộ lọc thực tế nếu cần.
- Bảng phụ ngăn client tự tạo bản tin rồi ép thành popup, nhưng policy INSERT/UPDATE rộng của bảng thông báo vẫn là điểm cần xử lý. Rà toàn bộ luồng tạo thông báo trước khi thu hẹp quyền; tối thiểu phải ngăn client sửa nội dung/người nhận/ID của bản tin đã đăng ký popup và ngăn đổi `is_read` từ true về false để kích hoạt nhắc lại. Có thể dùng trigger bảo vệ cột cho bản popup để giữ tương thích thao tác UPDATE `is_read=true` của app cũ. Kiểm thử cả ghi trực tiếp và RPC; không coi kiểm tra nhãn JSON là biện pháp thay thế.

## 5. Android: popup dùng chung và đồng bộ trạng thái

### 5.1. Thành phần dự kiến

- Thêm DTO kết quả claim/ack và các hàm repository trong nhóm notifications, hoặc tách `NotificationPopupRepository` nhưng dùng chung cơ chế phiên và invalidation.
- Thêm `NotificationPopupViewModel` ở cấp Activity, giữ `foreground_session_id`, trạng thái popup và request đang chạy; không tạo theo từng destination.
- Thêm `AdminNotificationPopupHost` và dialog nội dung cuộn được, tiêu đề/nội dung giữ nguyên lời admin. Hai nút **“Đã đọc”**, **“Để sau”**; Back tương đương “Để sau”, chạm bên ngoài không vô tình xác nhận.
- Thêm kho bền vững cho pending acknowledgment theo `(user_id, notification_id)` để nhớ thao tác đã đọc khi mạng lỗi; không chỉ lưu trong RAM.
- Chuỗi giao diện có đủ `values`, `values-vi`, `values-en`. Tái sử dụng cách render nội dung admin hiện có, kiểm tra tất cả loại gửi riêng để không dịch sai nội dung tự nhập.

### 5.2. Thời điểm tải

- Khi phiên đăng nhập sẵn sàng và app foreground, chờ giao diện rảnh rồi gọi claim một lần. Không claim sớm lúc còn dialog khác vì sẽ tiêu lượt trước khi có thể hiện.
- Nếu chưa có bản tin và phiên chưa hiển thị popup, kiểm tra lại tối đa mỗi **60 giây** khi foreground/có mạng; ngừng khi background, logout hoặc đã dùng suất popup của phiên. Đây là ngưỡng đề xuất, không phải bảo đảm nhận tức thời.
- Không gọi lại dashboard nặng mỗi 60 giây; gọi RPC popup nhỏ. Source hiện chưa thấy tích hợp Supabase Realtime cho notifications, nên giai đoạn đầu dùng foreground polling; chưa cần thêm kênh realtime/FCM.
- Không lấy popup từ cache 60 giây hoặc snapshot cũ. Request claim đang timeout phải retry **cùng ID phiên**, không tạo ID mới để thử lại.
- Tách việc app thực sự vào nền khỏi Activity recreation; xoay máy/đổi ngôn ngữ/đổi màn hình không tạo ID phiên mới. Chọn cơ chế lifecycle cấp process phù hợp, kiểm tra dependency hiện tại trước khi thêm.
- Nếu một popup đang mở lúc app vào nền, giữ cùng lượt đang hiển thị để khôi phục khi quay lại; không claim thêm chỉ vì resume. Popup đã đóng thì chỉ phiên foreground tiếp theo mới được nhắc.

### 5.3. Khi bấm “Đã đọc”

1. Ghi ý định đã đọc vào kho local bền vững trước khi đóng; khóa double tap. Nếu ghi local lỗi thì giữ popup và cho thử lại.
2. Đóng popup, loại khỏi hàng chờ local; cập nhật trạng thái đã đọc trong danh sách bằng cùng cơ chế sự kiện/cache.
3. Đồng bộ server ngay, retry khi mạng phục hồi hoặc mở app lại. Chỉ xóa pending sau khi server xác nhận đã đọc/đã xóa.
4. Dữ liệu cache hoặc fetch trả về cũ không được làm popup hiện lại trong khi pending ack còn tồn tại.

Client cần báo các ID đang chờ ack cho bộ điều phối local và ưu tiên sync ack trước claim. Khi chưa đồng bộ xong, không tiếp tục claim có thể tiêu thêm lượt cho chính bản tin pending. Không dùng nhánh hoàn tác đọc của Home để khôi phục popup đã được người dùng xác nhận.

Khi thao tác “Đã đọc” xảy ra lúc offline, thiết bị vừa bấm phải nhớ không nhắc lại kể cả sau restart. Thiết bị khác chỉ biết sau khi server nhận ack; không hứa đồng bộ tức thì giữa các thiết bị khi không có mạng. Nếu app bị xóa dữ liệu trước khi ack được đồng bộ, ý định local có thể mất.

### 5.4. Đồng bộ các màn hình và chống lộ sai tài khoản

- Repository phát sự kiện khi đọc một bản tin/đọc tất cả/xóa; Home và popup host cùng cập nhật. Kiểm tra các fetch đang chạy để không ghi đè bằng trạng thái cũ.
- Mọi request giữ UID, session generation, foreground ID và request sequence lúc bắt đầu; chỉ publish khi vẫn khớp. Logout/chuyển tài khoản hủy job, đóng popup, xóa state hiển thị; pending ack của A không được gửi bằng JWT của B.
- Khi đọc/xóa từ thiết bị khác, đóng popup sau lần đối soát server tiếp theo; dùng kiểm tra nhẹ khi popup đang mở nếu cần, dừng polling khi background. Không cấp lượt mới để đối soát.
- Quy định ưu tiên: màn khóa/bắt buộc cập nhật → cảnh cáo hệ thống hiện có → dialog đang thao tác/cập nhật tùy chọn → popup admin. Tránh chồng với reward overlay. Host toàn cục cần nhận trạng thái chặn từ màn con, đặc biệt `unreadWarning` hiện nằm ở Home; chỉ đặt host sau NavGraph chưa đủ giải quyết việc này.

## 6. Trình tự triển khai

1. Viết kiểm thử SQL cho nhánh gửi riêng, quyền, claim và giới hạn; thêm migration mới, giữ nguyên chữ ký RPC gửi hiện có.
2. Thêm DTO/repository, receipt idempotent, kiểm tra phiên, kho pending ack và sự kiện cập nhật notifications.
3. Thêm ViewModel cấp Activity, lifecycle cấp app và điều phối popup với cảnh cáo/cập nhật hiện có.
4. Thêm dialog, bản dịch; nối đọc/đọc tất cả/xóa để đóng popup đúng.
5. Chạy kiểm thử backend, kiểm thử trạng thái Android và build; kiểm tra UI trên thiết bị/emulator.
6. Triển khai backend trước, Android sau. App cũ tiếp tục nhận thông báo trong danh sách; bản tin gửi riêng sau migration có thể chờ app mới hiển thị. Hướng dẫn admin rõ hành vi này.
7. Nếu cần tắt tính năng, ngừng cấp claim/ẩn host; giữ dữ liệu thông báo và trạng thái đã đọc. Không reset số lượt để rollback.

## 7. Tiêu chí nghiệm thu

| Tình huống | Kết quả bắt buộc |
|---|---|
| Admin gửi riêng cho A | Chỉ A có popup; B không đọc/claim được. Các loại gửi riêng đều được hỗ trợ. |
| Admin gửi toàn bộ | Vẫn vào danh sách, không đăng ký popup. |
| Bản tin tự động hoặc client tự giả `sender=admin` | Không được cấp popup. Client không sửa được state/receipt hoặc nội dung bản popup được bảo vệ. |
| A bấm Đã đọc ở lượt 1–5 | Đóng ngay sau khi lưu ý định local; server được đồng bộ; mở lại/đổi màn hình không hiện lại. |
| A chọn Để sau 5 phiên | Lần 1–5 được phép hiện; lần 6 không; bản tin chưa đọc vẫn còn trong danh sách. |
| Refresh/recompose/xoay máy/đổi ngôn ngữ | Không tăng lượt hoặc mở thêm bản tin trong cùng phiên. |
| Request timeout, retry hoặc double call | Cùng ID phiên chỉ tạo một receipt và tăng tối đa một lượt. |
| Hai thiết bị tranh lượt cuối | Tổng số lượt cấp không vượt 5; không có lần tăng thứ 6. Kiểm tra concurrency bằng PostgreSQL nhiều connection, không chỉ test tuần tự trong PGlite. |
| App kill sau server commit | Retry cùng ID khi còn khôi phục được trạng thái; không tự hoàn lượt. Ghi nhận giới hạn có thể mất lượt đã nêu. |
| Mất mạng khi bấm Đã đọc rồi restart | Thiết bị này không hiện lại; pending ack được retry đúng tài khoản. |
| Đọc từ danh sách/đọc tất cả/xóa | Đóng hoặc loại popup tương ứng; fetch cũ không đưa nó trở lại. |
| Đọc từ thiết bị khác | Dừng sau khi nhận trạng thái server mới; không hứa đồng bộ khi offline. |
| Thông báo riêng đã trôi khỏi 50 bản tin | Vẫn được tìm bởi RPC chuyên biệt. |
| Có nhiều bản tin | Theo thứ tự đã định, một popup mỗi phiên foreground; không bật liên tiếp hàng loạt. |
| Guest/logout/chuyển A sang B/request A trả về muộn | Không hiện popup của A cho B; không gửi ack A bằng phiên B. |
| Đang khóa tài khoản/cập nhật/cảnh cáo hệ thống | Popup admin chờ; chưa cấp lượt trong khi bị chặn. |
| Nội dung dài, VI/EN, font lớn, sáng/tối | Nội dung cuộn được, nút không bị che và giữ nguyên lời admin. |

Kiểm thử mới có thể theo runner `@electric-sql/pglite` trong `supabase/tests`, bổ sung bài test state machine Android cho retry, lifecycle, pending ack và đổi tài khoản. Chạy các test notification/localization liên quan, kiểm thử tương thích dashboard và RPC admin, cùng task compile/test thích hợp từ Gradle wrapper. Chưa chạy các kiểm thử này vì hiện mới lập kế hoạch.

## 8. Những lựa chọn đã nêu rõ để bàn giao

- Lưu lượt ở server thay vì chỉ dùng DataStore, nhằm giữ giới hạn qua cài lại app/đổi thiết bị.
- Dùng bảng phụ do backend quản lý để xác thực bản tin đủ điều kiện popup, thay vì tin `type` hoặc metadata có thể bị client giả.
- Mỗi phiên foreground tối đa một popup, có “Để sau”, ưu tiên bản cũ; đây là quy ước UX đề xuất.
- Polling foreground 60 giây; chưa có cam kết nhận ngay khi admin bấm gửi.
- Giới hạn 5 tính theo lượt server đã cấp, có khả năng tiêu lượt trong khoảng crash trước khi render; không tự đồng nhất với 5 lần người dùng thực sự nhìn thấy.
- “Đã đọc” offline được ghi bền trên máy và đồng bộ sau; bảo đảm giữa các thiết bị phụ thuộc kết nối.

Kế hoạch này chỉ thêm tài liệu nghiên cứu. Các tên bảng/RPC/file mới ở trên là đề xuất triển khai, chưa tồn tại trong sản phẩm.
