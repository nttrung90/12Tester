# Kế hoạch bổ sung lý do Admin tạm dừng app và thông báo nổi cho chủ app

Ngày nghiên cứu: 05/11/2026. Phạm vi: nghiên cứu mã nguồn và lập kế hoạch triển khai; chưa sửa chức năng, chạy migration hoặc thay đổi dữ liệu máy chủ.

## 1. Kết luận nghiên cứu

Có thể tận dụng cơ chế thông báo riêng của Admin hiện có. Mỗi lần tạm dừng với một trong hai lý do mới chỉ cần tạo **một bản tin** trong `notifications`, đồng thời đăng ký ID bản tin đó vào `notification_popup_states`. Danh sách thông báo và popup sử dụng cùng nội dung, cùng trạng thái đã đọc. Không cần thêm hệ thống gửi, worker hoặc popup riêng.

Giữ `paused_reason = 'admin_manual'` cho cả hai lựa chọn. Đây là cờ nghiệp vụ hiện đang chặn chủ app tự mở lại, không phải nơi lưu chi tiết nguyên nhân. Không thay cờ này bằng mã Google Group hoặc vi phạm chính sách.

Chỉ hai lựa chọn mới được bổ sung popup. Lý do tự nhập hoặc để trống tiếp tục theo hành vi cũ. Thông báo gửi riêng thủ công, gửi hàng loạt, tự tạm dừng vì thiếu nhiệm vụ, hết slot, thông báo mở lại và các thao tác đọc/xóa giữ nguyên.

## 2. Hiện trạng đã đối chiếu trong source

| Thành phần | Hành vi hiện có và ý nghĩa với thay đổi |
| --- | --- |
| `app/src/main/java/com/ntt/crosstest/ui/screens/AdminCampaignsScreen.kt` | Danh sách app mở sang màn chi tiết; không có một hộp thoại tạm dừng riêng cần làm song song. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt:877` | Hộp thoại Admin tạm dừng chỉ có ô lý do tự nhập, tối đa 200 ký tự. Nút xác nhận đóng hộp thoại và xóa nội dung ngay khi gọi ViewModel. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt:900` | `adminPauseCampaign(reason)` đã có cơ chế chặn thao tác đồng thời, kiểm tra phiên/tài khoản, bỏ phản hồi cũ và tải lại dữ liệu sau thành công. Cần giữ các bảo vệ này. |
| `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt:1920` và `data/Models.kt:815` | Client gọi `admin_pause_campaign(p_campaign_id, p_reason)`, sau đó vô hiệu hóa cache app. |
| `supabase/migrations/069_admin_audit_hardening.sql:658` | Thực thi tạm dừng: kiểm tra Admin không bị khóa, khóa dòng app, chặn app đã kết thúc, ghi `admin_manual`, `admin_note`, `admin_resumed=false`, rồi tạo thông báo `type='campaign'` cho `owner_id`. Gọi lại khi trạng thái và ghi chú giống nhau không tạo thông báo mới. |
| `supabase/migrations/073_admin_action_audit.sql:706` và `:915` | Hàm thực thi trên đã được đổi tên thành `_impl_admin_pause_campaign(uuid,text)`. RPC công khai hiện là wrapper ghi nhật ký `pause_campaign`. Không được sửa riêng bản định nghĩa cũ trong 069 rồi coi là đã cập nhật luồng đang chạy. |
| `supabase/migrations/073_admin_action_audit.sql:582` | Gửi riêng Admin tạo bản tin và `notification_popup_states(display_count=0)` trong cùng giao dịch; gửi toàn bộ không đăng ký popup. |
| `supabase/migrations/067_admin_direct_notification_popup.sql:181` | RPC claim tìm thông báo chưa đọc đã đăng ký popup; giới hạn 5 lượt/bản tin, một receipt/người dùng/phiên foreground; ưu tiên bản tin cũ trước. Không bắt buộc bản tin phải có `type='admin'`. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupViewModel.kt:60` | Nhịp kiểm tra mặc định 60 giây khi đủ điều kiện; có xử lý pending acknowledgment, đổi tài khoản và màn hình đang chặn popup. |
| `app/src/main/java/com/ntt/crosstest/ui/components/AdminNotificationPopupHost.kt` | Hiện nguyên văn `title` và `message`, nội dung cuộn được; dùng hai nút Đã đọc/Để sau. |
| `app/src/main/java/com/ntt/crosstest/ui/components/NotificationBottomSheet.kt` | `type='campaign'` nằm trong nhóm thông báo báo cáo/app. Giữ loại này để không đổi cách phân loại thông báo tạm dừng. |
| `supabase/migrations/075_owner_auto_pause_config.sql:318` | Có guard chặn client tự đổi vòng đời app, bổ sung cho guard Admin trong baseline 062. Mọi dữ liệu nguyên nhân mới cũng cần được bảo vệ. |

Các kết luận trên phản ánh mã nguồn trong workspace, chưa xác nhận định nghĩa hàm hoặc phiên bản Android đã triển khai thực tế.

## 3. Hành vi và nội dung đề xuất

Trong phần lý do của hộp thoại, bổ sung hai lựa chọn, đồng thời giữ lựa chọn **Lý do khác / không bắt buộc** và ô nhập cũ. Chọn duy nhất một mục; mặc định là lựa chọn cũ để không tự gán một nguyên nhân khi Admin chưa chọn.

| Lựa chọn | Mã gửi máy chủ | Cách xử lý |
| --- | --- | --- |
| Tạm dừng do chưa thêm Google Group | `missing_google_group` | Tạo thông báo đúng chủ app và đăng ký popup. |
| Tạm dừng do vi phạm chính sách | `policy_violation` | Tạo thông báo đúng chủ app và đăng ký popup. |
| Lý do khác / không bắt buộc | `custom` | Giữ lý do tự nhập/để trống và thông báo danh sách như hiện tại. |

Với hai mục có sẵn, ẩn ô tự nhập để không có hai nội dung mâu thuẫn. Hiển thị nội dung thông báo dự kiến trước khi xác nhận. Tiêu đề có thể giữ như hiện tại: **Ứng dụng đã bị tạm dừng bởi Admin ⏸**.

**Thiếu Google Group:**

> Ứng dụng "{tên ứng dụng}" của bạn đã bị tạm dừng do chưa thêm Google Group của nhóm khiến các Tester không tải được ứng dụng của bạn để test. Vui lòng thêm Google Group của nhóm (theo hướng dẫn trong app) để tiếp tục tham gia Test.

**Vi phạm chính sách:**

> Ứng dụng của bạn đã bị tạm dừng do vi phạm chính sách, vui lòng liên hệ Admin để được mở lại Test.

Đã hiệu chỉnh lỗi gõ “hêm” thành “thêm”, “mở tại Test” thành “mở lại Test”. Nội dung thứ hai giữ theo mẫu yêu cầu, không tự thêm tên app; ID app vẫn lưu trong dữ liệu bản tin. Nội dung thứ nhất lấy tên app từ dữ liệu máy chủ tại thời điểm tạm dừng, không dùng tên do client gửi lên.

Thông báo được lưu ngay khi thao tác tạm dừng thành công. Popup xuất hiện khi chủ app dùng ứng dụng và luồng hiện có cho phép: không bị màn hình khác chặn, không còn pending ack chưa đồng bộ, chưa dùng suất popup của phiên và bản tin đến lượt trong hàng chờ. Không thêm ưu tiên hoặc hứa hiển thị tức thì, không gửi push hệ điều hành mới.

Việc thêm Google Group chưa tự động mở lại app. Chủ app vẫn cần được Admin mở lại theo cơ chế đang có. Nếu sau này muốn xác minh Google Group rồi tự mở lại, cần một yêu cầu nghiệp vụ riêng.

## 4. Thiết kế backend và tương thích

### 4.1. Migration và hợp đồng RPC

- Thêm migration mới sau `077` hiện có, dự kiến `078_admin_pause_reason_popup.sql`; kiểm tra lại số trống trước khi triển khai. Không sửa các migration đã có hoặc baseline/archive để triển khai thay đổi này.
- Giữ nguyên RPC `admin_pause_campaign(uuid,text)` cho app cũ và nhánh lý do khác.
- Bổ sung RPC tên riêng `admin_pause_campaign_with_reason(p_campaign_id uuid, p_reason_code text)`, chỉ nhận `missing_google_group` hoặc `policy_violation`. Tên riêng tránh chồng overload có tham số mặc định và tránh nhập nhằng khi PostgREST phân giải RPC.
- Backend chưa có RPC mới: báo lỗi rõ để Admin thử lại sau khi cập nhật backend. Không âm thầm gọi RPC cũ với chuỗi nhãn, vì thao tác đó không bảo đảm tạo popup theo yêu cầu.
- Tách phần thực thi dùng chung thành helper nội bộ nhận ID app, mã nguyên nhân và ghi chú. `_impl_admin_pause_campaign(uuid,text)` chuyển tiếp với mã `custom`; wrapper audit 073 của RPC cũ tiếp tục hoạt động. Cập nhật chỗ lấy snapshot của wrapper cũ để khóa dòng trước khi chụp trạng thái cũ, thay cho SELECT không khóa hiện tại; giữ hợp đồng RPC và hành vi ghi audit. RPC mới gọi helper rồi ghi audit. Không gọi wrapper cũ từ RPC mới để tránh audit hai lần.
- Helper không được mở EXECUTE cho PUBLIC/anon/authenticated; chỉ các RPC được kiểm quyền mới gọi được. RPC mới dùng `SECURITY DEFINER`, search path cố định, tên đối tượng đầy đủ schema và `assert_unlocked_admin()`; giữ nguyên mức quyền Admin hiện tại.

### 4.2. Lưu nguyên nhân có cấu trúc

Thêm `campaigns.admin_pause_reason_code text NULL`, chỉ cho phép `missing_google_group` hoặc `policy_violation` khi khác null. Null đại diện dữ liệu cũ/nhánh custom. Không suy luận mã bằng cách so khớp chuỗi tiếng Việt.

- Cả hai lý do vẫn ghi `status='paused'`, `paused_reason='admin_manual'`, `admin_resumed=false`.
- `admin_note` ghi nhãn nguyên nhân dễ đọc do backend tạo để màn chi tiết cũ vẫn hiển thị được. Nhánh custom ghi ghi chú như cũ và đặt mã mới về null.
- Model Android thêm thuộc tính nullable mặc định null. Phản hồi RPC mới giữ các trường của `AdminCampaignControlResult`, có thể bổ sung `admin_pause_reason_code`, `notification_id`, `changed`; các trường mới phải có mặc định ở client.
- Mở lại app xóa mã mới cùng `admin_note`. Rà cả `_impl_admin_resume_campaign` và trigger vòng đời. Với hoàn thành do hết hạn, giữ nguyên quy tắc đang có đối với dấu vết Admin; không thêm ràng buộc cứng “có mã thì status phải paused” làm hỏng luồng tự hoàn thành.
- Bổ sung bảo vệ cột mới trên INSERT/UPDATE trực tiếp của client, kể cả trường hợp UPDATE chỉ sửa mã mà không sửa status/paused_reason. Có thể mở rộng guard `SECURITY INVOKER` trong 075 để phân biệt ghi từ RPC definer với client trực tiếp. Giữ các kiểm tra quyền sở hữu và vòng đời đang có.
- Không backfill popup hoặc tự gán nguyên nhân cho app/thông báo lịch sử.

### 4.3. Giao dịch thực thi

1. Kiểm tra Admin chưa khóa, kiểm tra mã nguyên nhân, khóa dòng app `FOR UPDATE`, rồi kiểm tra lại quyền sau khi chờ khóa.
2. Giữ điều kiện app tồn tại, trạng thái được phép tạm dừng và các quy tắc hiện có. Lấy `owner_id`, `app_name` từ dòng đã khóa.
3. So sánh trạng thái, `paused_reason`, mã nguyên nhân và ghi chú chuẩn hóa. Nếu đã giống hệt, trả thành công không đổi; không tạo bản tin mới hoặc reset số lượt popup.
4. Cập nhật trạng thái/nguyên nhân. Tạo **một** dòng `notifications`, lấy ID bằng `INSERT ... RETURNING id`, giữ `type='campaign'` và khóa `data.reason='admin_pause'` cho tương thích.
5. Với hai mã mới, bổ sung `data.sender='admin'`, `data.broadcast=false`, `campaign_id`, `app_name`, `reason_code`, `admin_note`; đăng ký cùng ID vào `notification_popup_states` với `display_count=0`. Nhánh custom giữ payload và hành vi cũ.
6. Ghi audit `pause_campaign`, gồm trạng thái/nguyên nhân cũ và mới, người thực hiện, app và ID thông báo nếu có. Chụp dữ liệu trước thay đổi sau khi khóa dòng để audit không lấy trạng thái cũ từ trước lúc chờ khóa. RPC cũ vẫn giữ hợp đồng audit hiện hành; RPC mới chỉ ghi sự kiện thay đổi khi thực sự đổi trạng thái/nguyên nhân.
7. Chỉ commit khi toàn bộ thao tác thành công. Lỗi tạo bản tin, đăng ký popup hoặc audit phải rollback cả lần tạm dừng.

Không gọi `admin_send_notification()` sau khi đã tạo bản tin tạm dừng: cách đó tạo hai thông báo. Không để Android thực hiện riêng “tạm dừng” rồi “gửi thông báo”, vì mất mạng giữa hai lệnh sẽ làm thiếu thông báo.

Đổi từ Google Group sang chính sách, hoặc ngược lại khi app đang tạm dừng, được coi là một lần thay đổi nguyên nhân và tạo một bản tin mới. Gọi lặp cùng nguyên nhân khi trạng thái chưa đổi là no-op. Mở lại rồi tạm dừng lần nữa tạo bản tin cho lần mới. Không xóa hoặc đánh dấu đã đọc bản tin cũ khi đổi lý do/mở lại; các bản tin lịch sử tiếp tục theo cơ chế thông báo hiện có.

Khóa dòng và so sánh trạng thái xử lý double tap/retry liên tiếp cùng nguyên nhân. Đây không phải bảo đảm exactly-once cho một yêu cầu cũ được gửi lại sau nhiều thay đổi nguyên nhân xen kẽ; client không tự phát lại thao tác Admin sau khi đổi phiên hoặc sau khi người dùng đã thực hiện thao tác khác.

## 5. Thay đổi Android

| File/nhóm file | Công việc |
| --- | --- |
| `CampaignDetailScreen.kt` | Thêm hai lựa chọn, giữ nhánh cũ, nội dung xem trước và vùng cuộn phù hợp màn hình nhỏ/font lớn. Giữ lựa chọn/lý do khi mạng lỗi; chỉ đóng và xóa sau khi ViewModel xác nhận thành công. Khóa xác nhận và chỉnh lựa chọn khi đang gửi. |
| `CampaignDetailViewModel.kt` | Nhận lựa chọn có kiểu rõ ràng; gọi nhánh RPC tương ứng, giữ action token, UID, session generation và cơ chế refresh chống phản hồi cũ. Trả tín hiệu thành công gắn với đúng lần gửi để đóng hộp thoại. Hủy hộp thoại và xóa dữ liệu khi đổi tài khoản. |
| `CampaignRepository.kt` | Thêm lời gọi RPC mới, tiếp tục invalidate campaign sau thành công. Không tự gửi thêm notification, không tự chuyển sang RPC cũ nếu thiếu backend. |
| `Models.kt` hoặc DTO riêng | Enum/mã nguyên nhân, params RPC mới và trường kết quả nullable. Giữ DTO/hợp đồng RPC cũ. |
| `res/values/strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml` | Nhãn lựa chọn, phần xem trước, trạng thái/lỗi cần thiết; giữ đủ bộ tài nguyên VI/EN. |
| `CampaignDetailScreen.kt`, `OwnerAppDetailScreen.kt` | Tận dụng `admin_note` hiện có để chủ app thấy nguyên nhân; nếu render nhãn theo mã thì luôn có fallback về ghi chú cũ. |

Hai mẫu thông báo được backend tạo bằng tiếng Việt theo yêu cầu và lưu nguyên văn. Nhãn giao diện có bản dịch VI/EN. Popup hiện đọc trực tiếp title/message; `NotificationRenderer` hiện chưa có nhánh biến đổi hai mẫu này nên không cần thay thuật toán render. Kiểm thử để bảo đảm danh sách và popup giữ cùng nội dung, kể cả khi đổi ngôn ngữ. Phần xem trước phía client phải khớp mẫu backend; bản tin được lưu ở server là kết quả cuối cùng.

Không cần chỉnh `NotificationPopupViewModel`, `AdminNotificationPopupHost`, pending ack, RPC claim/ack, nhịp polling hoặc worker. Chỉ sửa những thành phần này nếu kiểm thử tìm thấy lỗi liên quan trực tiếp, đồng thời phải chứng minh không làm đổi hành vi cũ.

## 6. Kiểm thử và tiêu chí nghiệm thu

Thêm runner chuyên biệt, dự kiến `supabase/tests/run-admin-pause-reason-popup-test.mjs`, nạp toàn bộ chuỗi migration đang hoạt động theo thứ tự. Các bài hồi quy cũng phải thực sự nạp migration mới: `run-admin-action-audit-test.mjs` hiện chỉ nạp đến 070 rồi nạp riêng 073, vì vậy chạy bài này nguyên trạng chưa kiểm chứng được thay đổi mới.

| Nhóm | Kết quả cần đạt |
| --- | --- |
| Hai lựa chọn | Mỗi mã tạo đúng một bản tin và một popup state count=0; đúng câu chữ, tên app ở mẫu Google Group, đúng owner và app ID. |
| Tên app | Tiếng Việt, dấu nháy, xuống dòng, ký tự đặc biệt được xử lý như dữ liệu; không nội suy vào SQL động và không cắt mất nội dung. Có fallback khi tên rỗng. |
| Phân quyền | Member, Moderator, Admin bị khóa, chưa đăng nhập bị từ chối; owner không claim/ack thông báo của người khác hoặc tự đổi mã nguyên nhân. Kiểm tra ghi trực tiếp chỉ sửa cột mới. |
| Trạng thái | Open và paused theo luật cũ; app không tồn tại hoặc production/completed bị từ chối; owner không tự mở `admin_manual`. Giữ luật slot, hết hạn và tự hoàn thành. |
| Không trùng | Retry/double tap/hai Admin chọn cùng nguyên nhân khi trạng thái không đổi chỉ tạo một bản tin; số lượt không reset sau khi người dùng đọc/xóa bản tin rồi Admin gọi lại cùng nguyên nhân. |
| Đổi nguyên nhân | Google Group → chính sách tạo bản mới đúng mẫu; hai mã có thể phân biệt dù ghi chú tự nhập trùng nhãn. Mở lại xóa mã mới, rồi tạm dừng lại tạo lần mới. |
| Nguyên tử/audit | Ép lỗi ở insert popup state hoặc audit không để lại app đã dừng hay thông báo dở dang. Nhật ký đúng trước/sau khi có thao tác đồng thời. |
| Hợp đồng cũ | RPC hai tham số, lý do tự nhập/để trống và payload cũ hoạt động như trước, không tự đăng ký popup. Không đổi gửi riêng/broadcast hoặc thông báo tự tạm dừng. |
| Luồng popup | Chủ app nhận qua claim sẵn có; thứ tự cũ trước mới sau, tối đa một popup/phiên và 5 lượt/bản tin. Không nhận lượt thứ 6; Đã đọc/đọc tất cả/xóa dừng nhắc theo cơ chế hiện có. |
| Phiên và mạng | Timeout dùng lại phiên claim; pending ack offline còn hiệu lực sau restart; chuyển A → B không lộ popup hoặc publish kết quả thao tác của A. Lỗi gửi của Admin giữ lựa chọn để thử lại. |
| Giao diện | VI/EN, sáng/tối, font lớn, màn nhỏ; nội dung dài cuộn được; mẫu xem trước khớp bản tin thực nhận; không chồng popup đang bị blocker chặn. |

Chạy bài mới cùng các suite hiện có phù hợp: `test:admin-direct-popup`, `test:admin-audit`, `test:admin-action-audit` sau khi bổ sung kiểm tra trên schema mới, `test:admin-expansion-integration`, `test:owner-auto-pause`. Android chạy `AdminCampaignActionRaceTest`, các suite `NotificationPopup*`, `NotificationPendingAckStoreTest`, kiểm tra localization và build debug bằng Gradle wrapper. Bổ sung assertion về payload và kết quả hai nguyên nhân mới; không chỉ kiểm tra enum hoặc chuỗi hằng.

Kiểm thử đồng thời cần PostgreSQL nhiều connection trên cơ sở dữ liệu test: hai lệnh pause cùng app/cùng nguyên nhân và hai thiết bị tranh lượt popup thứ 5. Mở rộng runner hiện có `run-admin-popup-concurrency-test.mjs` với bản tin tạo qua RPC mới. Runner bỏ qua khi thiếu `TEST_DATABASE_URL` không được tính là đã kiểm thử concurrency thành công.

## 7. Trình tự triển khai và giới hạn phạm vi

1. Đối chiếu schema thực tế trên môi trường test: 067, 069, 073, 075 và chuỗi đến migration mới nhất. Xác nhận RPC công khai đang đi qua wrapper audit và helper đúng phiên bản.
2. Triển khai migration mới, kiểm thử hợp đồng cũ và mới, atomicity, quyền và guard cột mới.
3. Cập nhật Android và chạy các kiểm thử trên. Kiểm tra end-to-end với một Admin và hai thành viên để chứng minh chỉ chủ app nhận được thông báo.
4. Phát hành backend trước, Android sau. Thành viên đang dùng bản đã có popup 067 có thể nhận bản tin mới mà không cần một popup host mới; bản chưa hỗ trợ popup vẫn có bản tin trong danh sách. Admin cần bản Android mới để chọn hai lý do.
5. Nếu cần tắt phần mở rộng, dừng sử dụng RPC mới/ẩn hai lựa chọn; giữ dữ liệu thông báo, receipt, trạng thái đọc và các RPC cũ. Không reset count hoặc backfill popup khi bật lại.

Phần đã thực hiện trong lần này: đọc và đối chiếu các luồng liên quan, xác định điểm tích hợp, lập tài liệu triển khai và nghiệm thu. Chưa chạy kiểm thử chức năng vì chưa có thay đổi triển khai; chưa truy cập hoặc chỉnh sửa backend đang hoạt động.
