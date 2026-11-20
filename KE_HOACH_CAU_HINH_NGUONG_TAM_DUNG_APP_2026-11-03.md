# Cấu hình số ngày tự tạm dừng app trong Bảng Quản Trị Viên

## Các luồng đưa app sang trạng thái tạm dừng

| Lý do lưu trong `paused_reason` | Khi nào chuyển sang `paused` | Điều kiện bật lại qua luồng hiện tại |
| --- | --- | --- |
| `owner_inactive` · Thiếu nhiệm vụ ngày | `pause_inactive_owner_campaigns()` xét app `open` đã đăng ít nhất **24 giờ**, chủ app ngoài nhóm Admin/Moderator, app chưa có cờ `admin_resumed`, chuỗi thiếu của chủ app đạt ngưỡng **7** và trạng thái nhiệm vụ hôm nay đã sẵn sàng nhưng chưa đạt. Hệ thống ghi lý do và gửi thông báo cho chủ app. | Chủ app cần có dữ liệu nhiệm vụ hôm nay sẵn sàng và đạt chỉ tiêu hôm nay. Admin chưa bị khóa có thể mở qua RPC riêng và đặt `admin_resumed = true`, miễn cho app khỏi lần xét thiếu nhiệm vụ tiếp theo trong thời gian cờ còn hiệu lực. |
| `slot_expired` · Hết hạn slot bổ sung | `pause_expired_slot_campaigns()` xét app `open` ở slot số 2 trở lên khi có bản ghi slot của đúng chủ và `expires_at <= now()`. App slot 1 được miễn. Nếu bản ghi slot bị thiếu, lượt quét hiện tại bỏ qua. Cờ `admin_resumed` và vai trò Admin/Moderator không tạo ngoại lệ cho lượt quét slot. | Slot phải được gia hạn hoặc có lại và còn hiệu lực. Gia hạn và bật lại là hai thao tác riêng. Luồng chủ app còn kiểm tra chuỗi thiếu chung. |
| `owner_manual` · Chủ app tự tạm dừng | Chủ app bấm tạm dừng trên màn hình quản lý app. Android cập nhật trực tiếp dòng `campaigns` của chính chủ. Chính sách RLS yêu cầu chủ app chưa bị khóa. UI thực hiện trên app đang mở, chưa ở trạng thái kết thúc. | Chủ app dùng `resume_campaign()`. RPC kiểm tra thời hạn app, slot và chuỗi thiếu hiện tại. |
| `admin_manual` · Admin tạm dừng | Admin chưa bị khóa gọi `admin_pause_campaign()` cho app đang `open` hoặc `paused`. Thao tác có thể đổi lý do của app đã tạm dừng sang `admin_manual`, lưu ghi chú tùy chọn, gửi thông báo và ghi nhật ký quản trị. | Admin chưa bị khóa dùng `admin_resume_campaign()`. RPC của chủ app trả `PAUSED_BY_ADMIN`. Trigger cũng chặn chủ app thông thường mở trực tiếp hoặc sửa lý do và ghi chú quản trị. |

`sync_campaign_lifecycle()` chạy theo thứ tự **kết thúc app đủ 30 ngày → xét thiếu nhiệm vụ → xét hết slot**. Nếu app đồng thời đủ điều kiện thiếu nhiệm vụ và hết slot, lượt đồng bộ này lưu `owner_inactive` vì nhánh hết slot chỉ xét app `open`. Khi bật lại, hệ thống vẫn kiểm tra slot. Lịch trong baseline gọi đồng bộ mỗi giờ và Trang chủ Android cũng gọi đồng bộ định kỳ khi đã đăng nhập. Cần đối chiếu lịch và định nghĩa hàm trên Supabase production trước khi triển khai.

### Chuỗi thiếu nhiệm vụ được tính thế nào

- Khi xét tự tạm dừng, ngày hiện tại tính theo `Asia/Ho_Chi_Minh`. Hàm bỏ qua app nếu `carryover_ready` hoặc `history_ready` là `false`, hoặc nếu `met` là `true`.
- `profiles.daily_missed_streak` thuộc **tài khoản**. Cùng một chuỗi được dùng để xét các app đủ điều kiện của chủ đó. Finalizer tăng chuỗi sau ngày thiếu và đặt lại về 0 khi ngày đạt hoặc được miễn. Khi đạt chỉ tiêu hôm nay, `refresh_daily_completion()` cũng có thể đặt lại chuỗi. Ngày chưa đủ dữ liệu để chốt được hoãn xử lý.
- `get_daily_task_status()` tính chỉ tiêu từ số app đủ điều kiện của chủ, tỷ lệ test mỗi app và phần thiếu chuyển sang nếu chính sách cộng dồn đang áp dụng. Chỉ tiêu thực tế được giới hạn theo số app khả dụng. Hệ thống đếm các app khác nhau có hoạt động kiểm thử đủ điều kiện của chủ app. App đang tạm dừng vì `owner_inactive` vẫn được tính vào số app dùng để xác định chỉ tiêu trong cửa sổ hiện hành. Các lý do tạm dừng khác không có ngoại lệ này.
- Cùng bộ đếm có thể chứa ngày thiếu ở chế độ thành viên, khi người dùng chưa có app đủ điều kiện tính chỉ tiêu chủ app. Khi đó nhiệm vụ ngày dựa trên đăng nhập hoặc hoạt động kiểm thử đủ điều kiện. Sau khi app đạt mốc 24 giờ, hệ thống có thể xét chuỗi đã tích lũy trước đó. Ngưỡng 1–7 giữ cách tính hiện tại và chỉ thay mốc so sánh.

### Quy tắc bật lại và các trạng thái liên quan

- Với chủ app, `resume_campaign()` kiểm tra quyền sở hữu và trạng thái `paused`, rồi chặn `admin_manual`. App đã đủ 30 ngày được chuyển sang `completed`. App còn hạn phải có slot hợp lệ nếu dùng slot bổ sung. Với `owner_inactive`, dữ liệu hôm nay chưa sẵn sàng trả `DAILY_TASK_PENDING` và chưa đạt trả `OWNER_INACTIVE`. Sau bước làm mới nhiệm vụ, hàm áp dụng mốc chuỗi thiếu chung cho mọi lý do tạm dừng mà chủ app được phép bật lại, gồm cả `owner_manual` và `slot_expired`.
- RPC của admin cũng kiểm tra mốc 30 ngày và slot hợp lệ. Trigger kiểm tra thêm slot chưa bị app `open` khác của cùng chủ sử dụng. Khi admin mở app bị `owner_inactive`, cờ `admin_resumed` giúp app vượt lượt xét thiếu nhiệm vụ, còn lượt xét hết slot vẫn áp dụng. Cờ được đặt lại khi app chuyển sang `paused`, `production` hoặc `completed`.
- App `open` hoặc `paused` đủ 30 ngày chuyển sang `completed` khi đồng bộ. Việc chủ app đánh dấu đã lên Production chuyển app sang `production`. Khóa tài khoản cập nhật `profiles.is_locked`. Nhiệm vụ App do người tạo tạm dừng dùng trạng thái riêng `missions.status = 'paused_by_creator'`. Các trường hợp này được theo dõi riêng với `campaigns.status = 'paused'`.

### Kiểm tra đường ghi trực tiếp trước khi phát hành

Đã tái hiện cục bộ bằng PGlite với các migration hiện hành. Chủ app chưa bị khóa có thể cập nhật trực tiếp `campaigns.status` từ `paused` sang `open` đối với `owner_inactive` và `owner_manual`, bỏ qua kiểm tra chuỗi thiếu trong `resume_campaign()`. `admin_manual` bị trigger chặn với cùng tài khoản thử. Phép thử cũng xác nhận admin mở app `owner_inactive` đặt cờ `admin_resumed`, và lượt quét slot tạm dừng tại mốc hết hạn nhưng bỏ qua khi thiếu bản ghi slot.

Đưa việc chặn đường mở lại trực tiếp vào tiêu chí phát hành nếu ngưỡng phải được thực thi qua mọi yêu cầu client. Khi triển khai, giới hạn chuyển `paused` sang `open` qua RPC được kiểm tra, đồng thời bảo vệ lý do do hệ thống ghi để client không đổi lý do trước khi gọi RPC. Kiểm thử lại cập nhật thông tin app, chủ app tự tạm dừng, đánh dấu Production, đồng bộ vòng đời và thao tác admin. Xác minh riêng trường hợp thiếu bản ghi slot trên staging trước khi cân nhắc sửa quy tắc hết slot.

Nguồn trong repo gồm các hàm hết slot, đồng bộ và trigger trong `062_consolidated_baseline.sql`, luồng thiếu nhiệm vụ và bật lại của chủ app trong `066_push_and_carryover_hardening.sql`, quyền chủ app và luồng admin trong `069_admin_audit_hardening.sql`, tính chỉ tiêu trong `071_business_policies.sql`, nhật ký admin trong `073_admin_action_audit.sql` và thao tác Android trong `OwnerAppDetailViewModel.kt`.

## Hành vi đề xuất

Thêm nút **Tạm dừng do thiếu nhiệm vụ** ngay dưới thẻ **Nhiệm vụ ngày**. Nút hiển thị giá trị máy chủ, ví dụ **7 ngày thiếu liên tiếp**. Hộp thoại cho chọn một số nguyên từ **1 đến 7**, có **Hủy** và **Lưu**. Chỉ cho lưu sau khi tải thành công và có thay đổi. Ngưỡng chi phối lý do `owner_inactive` và bước kiểm tra chuỗi chung khi chủ app bật lại. Việc tạm dừng do hết slot hoặc thao tác thủ công tiếp tục theo điều kiện riêng ở bảng trên.

Mặc định là **7 ngày**. Sau khi lưu, lượt xét kế tiếp dùng ngưỡng mới và chuỗi thiếu đã tích lũy. Ví dụ, đổi từ 7 xuống 3 có thể khiến app của chủ đã thiếu 3 ngày bị tạm dừng ngay ở lượt đồng bộ kế tiếp, kể cả trước lần cron tiếp theo. Hiển thị cảnh báo này trước khi xác nhận hạ ngưỡng. Khi tăng ngưỡng, chủ các app đã tự tạm dừng vẫn cần hoàn thành chỉ tiêu hôm nay và bật lại theo luồng hiện hành.

Giữ cách chốt nhiệm vụ ngày, các ngoại lệ, mốc tối thiểu 24 giờ, cách cộng dồn và mức phạt Credit hiện có. Ngưỡng này áp dụng chung cho các app đủ điều kiện của chủ app. Giá trị 1 nghĩa là một ngày thiếu đã được tính vào chuỗi hiện tại, theo cách chốt ngày đang dùng.

## Các bước triển khai

1. **Supabase.** Thêm một migration sau `074` với cấu hình riêng một dòng, `NOT NULL CHECK (missed_days BETWEEN 1 AND 7)`, khởi tạo bằng 7 và có `revision` để phát hiện chỉnh sửa đồng thời. Tạo RPC `admin_get_owner_auto_pause_config()` và `admin_set_owner_auto_pause_config(p_missed_days, p_expected_revision)`. Kiểm quyền bằng `assert_unlocked_admin()`, khóa dòng khi lưu và kiểm tra lại quyền sau khi chờ khóa. Thu hồi quyền ghi bảng trực tiếp từ các vai trò client. Khi cập nhật thành công, dùng `record_admin_action_event()` ghi người thực hiện, giá trị cũ và mới trong cùng giao dịch. Nếu phiên bản đã đổi, trả lỗi để UI tải lại.
2. **Áp dụng ngưỡng.** Cho `pause_inactive_owner_campaigns()` và `resume_campaign()` đọc cùng một cấu hình, một lần mỗi lượt gọi. Giữ nguyên chữ ký RPC và hai đường gọi đồng bộ hiện có. Thay điều kiện `>= 7` ở cả hai hàm. Lưu `threshold_days` vào dữ liệu thông báo cùng nội dung được tạo theo ngưỡng lúc tạm dừng. Nhờ vậy thông báo lịch sử vẫn phản ánh đúng ngưỡng lúc xảy ra sự kiện.
3. **Android.** Thêm nút trong `AdminUsersScreen.kt`, trạng thái tải/lưu trong `AdminUsersViewModel.kt` và lời gọi RPC trong `CampaignRepository.kt`, theo mẫu cấu hình Nhiệm vụ ngày. Giữ giá trị do máy chủ xác nhận, khóa nút khi đang lưu, bỏ phản hồi của phiên cũ và tải lại khi có xung đột. Rà lại câu chữ ghi cứng 7 ngày trong Trang chủ, thẻ Nhiệm vụ ngày, nhắc nhiệm vụ, lỗi bật lại và màn hình chi tiết app. Với các câu hướng dẫn chung, dùng mô tả theo chỉ tiêu và chuỗi thiếu. Hiển thị số ngày cụ thể trên bảng admin và thông báo lúc tạm dừng. Cập nhật cả tài nguyên mặc định, tiếng Việt và tiếng Anh.

## Nghiệm thu và triển khai

| Hạng mục | Kết quả cần đạt |
| --- | --- |
| Ngưỡng và quyền | Mặc định 7. Lưu được 1 và 7. Từ chối 0, 8, `null` và giá trị sai kiểu. Thành viên, moderator, admin bị khóa và người chưa đăng nhập không chỉnh được. Hai admin lưu cùng phiên bản chỉ có một thay đổi thành công. Nhật ký ghi đúng giá trị trước và sau. |
| Tự tạm dừng do nhiệm vụ | Với ngưỡng 1, 3 và 7, kiểm tra chuỗi ngay dưới và đúng ngưỡng. Kiểm tra ngay trước và đúng mốc 24 giờ, chủ có nhiều app, chuỗi tích lũy từ chế độ thành viên, chủ đã đạt hôm nay, trạng thái nhiệm vụ chưa sẵn sàng, Admin/Moderator và app có `admin_resumed`. Hai lượt đồng bộ chỉ tạo một thông báo cho cùng lần tạm dừng. |
| Hết slot và tạm dừng thủ công | Kiểm tra slot 1, slot bổ sung còn hạn, đúng lúc hết hạn và thiếu bản ghi. Kiểm tra chủ app tạm dừng app của mình và admin tạm dừng app đang mở hoặc đổi lý do của app đã tạm dừng. Khi đồng thời hết slot và thiếu nhiệm vụ, kiểm tra ưu tiên lý do. Khi đủ 30 ngày, kiểm tra app chuyển sang `completed`. |
| Bật lại | Kiểm tra `owner_inactive` trước và sau khi đạt chỉ tiêu hôm nay, cả khi ngưỡng đã đổi. Kiểm tra app chủ tự tạm dừng và app hết slot sau khi gia hạn ở hai phía của ngưỡng mới. Thử slot đã bị app khác dùng, chủ app mở `admin_manual`, và admin mở `owner_inactive` để xác minh cờ `admin_resumed`. Kiểm tra đường ghi trực tiếp theo tiêu chí phát hành ở trên. |
| Android | Chọn và lưu 1–7, mở lại thấy giá trị đã lưu. Thử lỗi mạng, xung đột, đổi phiên trong lúc lưu và máy chủ chưa có RPC. Build debug và kiểm tra giao diện VI/EN trên thiết bị hoặc emulator. |

Chạy kiểm thử trên chuỗi migration hiện hành, bao gồm hồi quy carryover và nhật ký admin. Trước khi đưa lên production, đối chiếu định nghĩa các hàm, lịch đang chạy và phân bố `paused_reason`, kể cả dòng trống hoặc lý do ngoài danh sách trên nếu có. Thử trên staging, triển khai migration với giá trị 7 rồi phát hành Android có câu chữ cập nhật. Trước lần đầu hạ ngưỡng, chạy truy vấn chỉ đọc để đếm app đủ điều kiện theo ngưỡng dự kiến. Các bản Android cũ vẫn có thể hiển thị mốc 7 trong nội dung cố định cho đến khi cập nhật.

Nếu cần quay lại chính sách cũ, đặt ngưỡng về 7. Các app đã tạm dừng trong thời gian áp dụng ngưỡng thấp hơn tiếp tục theo luồng bật lại hiện có.
