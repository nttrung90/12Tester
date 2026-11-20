# Kế hoạch chặn thao tác app khi chưa hoàn thành nhiệm vụ ngày

Ngày lập: 12/11/2026. Workspace: `F:\AI\12Tester`.
Người thực hiện được yêu cầu: Antigravity CLI, model `gemini-3.8-flash-high`, effort `high`.
Trạng thái ban đầu: đã khảo sát mã; chưa triển khai tính năng này.

## 1. Mục tiêu và phạm vi

Chủ app chưa hoàn thành nhiệm vụ ngày không được:

1. Xóa/gỡ app, bao gồm yêu cầu chuyển app sang chờ xóa.
2. Xác nhận “App đã lên production”.
3. “Tiếp tục nhận Tester”, với mọi nguyên nhân tạm dừng do chủ app được phép tiếp tục.

Điều kiện quyết định nằm ở máy chủ, được kiểm tra trước khi thao tác làm thay đổi
chỉ tiêu, vòng đời app hoặc nghĩa vụ tài chính. Giao diện giải thích cùng một quy tắc.

Không đổi phiên bản đang có trong `app/build.gradle.kts`. Không build APK/AAB.
Không triển khai migration hoặc fixture lên production, không commit/push.

## 2. Hiện trạng đã xác minh

| Luồng | Vị trí | Khoảng trống |
|---|---|---|
| Xóa app | `OwnerAppDetailViewModel.deleteCampaign`, `CampaignRepository.deleteCampaign`, `_request_campaign_removal` bản cuối tại migration 088 | Chưa kiểm tra nhiệm vụ trước khi đóng mission, hoàn thưởng, chuyển closed hoặc xóa |
| Production | `OwnerAppDetailViewModel.markProduction`, `CampaignRepository.updateCampaignStatus` | PATCH trực tiếp; chưa có điều kiện nhiệm vụ ngày |
| Tiếp tục | `togglePauseResume`, `resume_campaign` bản cuối tại migration 079 | Đã có met/history/carryover nhưng UI còn đọc cache; cần đồng nhất thông báo, xử lý null/lỗi/ngày cũ; kiểm tra nhánh hết 30 ngày |
| Xóa trực tiếp | RLS campaigns và fallback cũ trong repository | Phải chặn đường DELETE trực tiếp, không chỉ RPC mới |
| Tạm dừng thủ công | `updateCampaignStatus(..., "paused", "owner_manual")` và `get_daily_task_status` bản 088 | Có thể loại app khỏi chỉ tiêu trước khi xóa/production, làm mất tác dụng điều kiện |
| Hoàn thành production | Migration 089 | Đã sửa thứ tự bonus trigger; phải giữ nguyên kết quả sửa và kiểm thử hồi quy |

Đọc định nghĩa cuối cùng qua toàn bộ chuỗi migration trước khi sửa. Không sửa
nhầm bản baseline/phiên bản hàm cũ. Các migration đã có tới 089 là nền được bảo toàn.

## 3. Quy tắc nghiệp vụ

### 3.1. Điều kiện chung cho thao tác của chủ app

Sử dụng trạng thái nhiệm vụ canonical của máy chủ, ngày Việt Nam tại thời điểm
xác nhận, không tin cờ `met` hay ngày do client gửi. Điều kiện:

- `history_ready = true` và `carryover_ready = true`.
- `met = true`.
- `carryover_remaining_tests = 0`.
- Xác thực, chủ sở hữu và tình trạng khóa tài khoản hợp lệ.

Không có dữ liệu, dữ liệu thiếu, lỗi tải hoặc sai ngày: chưa đủ điều kiện cho phép;
báo đang kiểm tra/cần thử lại, không tự coi là đã hoàn thành.

Không hard-code số lượt test trên mỗi app. Giữ cách giới hạn theo số app khả dụng,
miễn nhiệm vụ và cách xác định báo cáo hợp lệ của hệ thống hiện có.

### 3.2. Kiểm tra trước thay đổi

Ví dụ q=3, đang có hai app và phải test 6 app khác, mới hoàn thành 3:
không được xóa một app hoặc chuyển production để giảm chỉ tiêu rồi vượt điều kiện.
Khi đã hoàn thành, thao tác thành công vẫn cập nhật chỉ tiêu ngay theo quy tắc 088.
Không trừ nợ cũ khi xóa, không sửa snapshot đã chốt, không tính giảm hai lần khi
worker hoàn tất xóa vật lý.

### 3.3. Chặn đường vòng qua tạm dừng

Phương án triển khai mặc định: thao tác tạm dừng thủ công của chủ app cũng phải
qua điều kiện trên nếu nó làm giảm nghĩa vụ ngày. Đây là kiểm soát cần thiết để
không thể tạm dừng trước rồi xóa/production. Giữ nguyên công thức nhiệm vụ 088;
không tạo một cơ chế nợ hoặc quota thứ hai chỉ cho ba nút.

Tạm dừng do Admin/hệ thống, hết slot hoặc hết vòng đời vẫn theo quy tắc hiện có.
App đã tạm dừng từ trước sử dụng nghĩa vụ thực tế hiện tại của hệ thống; không
tự hồi tố tạo nợ mới. Ghi rõ giới hạn này trong báo cáo.

### 3.4. Luồng hệ thống, Admin, gọi lại

- Admin xử lý app người khác và worker/cron hợp lệ không bị chặn bởi nhiệm vụ
  của chủ app. Xác minh quyền từ caller thật và luồng được phép; không tin cờ client
  hoặc dùng việc SECURITY DEFINER đổi current_user làm bằng chứng đủ cho bypass.
- Giữ chính sách miễn nhiệm vụ hiện hành theo vai trò; không tự đổi quyền.
- Worker tiếp tục yêu cầu xóa đã được chấp nhận trước đó, dù ngày đã đổi.
- Replay/reconcile thao tác đã commit không tạo tác dụng phụ mới và không bị
  báo thất bại chỉ vì nghĩa vụ hiện tại khác. Chỉ bỏ qua gate nếu có bằng chứng
  thao tác đã thực hiện, không dựa trên lời khai client.
- Bấm tiếp tục app hết 30 ngày: áp dụng gate trước phần thay đổi do người dùng
  khởi phát; cron hoàn thành hết hạn độc lập không bị chặn.

## 4. Thiết kế thực hiện

### 4.1. Backend

1. Migration mới tiếp theo sau 089, không sửa các migration nền có sẵn.
2. Hàm nội bộ dùng chung kiểm tra điều kiện nhiệm vụ; không mở helper thay đổi
   trạng thái cho client gọi tùy ý. Thiết kế mã lỗi ổn định cho unmet/pending/debt.
3. Áp dụng vào owner deletion trước mọi side effect, owner resume và production.
   Ưu tiên RPC production có kiểm tra quyền, khóa và kết quả xác nhận rõ ràng.
4. Chặn trực tiếp PATCH production, DELETE và tạm dừng thủ công gây giảm nghĩa vụ,
   bao gồm client cũ/fallback. Giữ bảo vệ dữ liệu tài chính của 087 và sửa lỗi 089.
5. Thiết kế khóa và tính nguyên tử sau khi đọc `LOCK_ORDER.md`: không để thao tác
   khác hạ quota giữa lúc kiểm tra và cập nhật; không thêm vòng chờ khóa profile,
   campaign, round, report và finalizer. Không coi PGlite là chứng minh concurrency.
6. Sau thành công đồng bộ daily completion và các projection/feed/cache cần thiết.
   Nếu hành động chính đã commit nhưng refresh phụ lỗi, báo trạng thái cần tải lại;
   không hoàn tác giả hoặc gửi lại thao tác tài chính một cách mù quáng.

### 4.2. Android

1. Dùng chung logic đánh giá và thông báo cho ba thao tác, cùng tạm dừng thủ công
   cần bảo vệ; không nhân bản các điều kiện khác nhau giữa dialog và ViewModel.
2. Giữ mục thao tác hiển thị kèm lý do bị chặn, thông tin hoàn thành/còn thiếu;
   không mở dialog xác nhận có thể thực hiện khi đã biết chưa đủ điều kiện.
3. Tải lại khi xác nhận và sau khi quay về từ test. Chặn null/pending/ngày cũ/lỗi mạng;
   không lấy cache cũ làm quyết định cuối. Backend luôn kiểm tra lại.
4. Chống bấm lặp; guard response theo UID, generation, business date và thứ tự
   request. Response của phiên cũ không được ghi sang phiên mới.
5. Đồng bộ Home, Quản lý app, daily task, tab Đã hoàn thành sau thành công bằng
   cơ chế invalidation/events hiện có. Không thêm truy vấn theo từng thẻ app.
6. Strings đủ default/vi/en; thông báo có hành động và số liệu phù hợp. Thông báo
   cho lỗi nhiệm vụ không dùng câu nói sai rằng chủ app đã vắng mặt nhiều ngày.

## 5. Kiểm thử bắt buộc

| Nhóm | Ca nghiệm thu |
|---|---|
| Ba thao tác | unmet bị chặn; met và hết nợ cho phép; không phát sinh side effect khi chặn |
| Dữ liệu chưa sẵn sàng | thiếu history, carryover pending, null, timeout, ngày cũ đều không được tự cho qua |
| Nợ và giới hạn nguồn | met nhưng còn nợ vẫn chặn; thiếu nguồn test theo chính sách hiện có; exemption hợp lệ |
| Chỉ tiêu thay đổi | chống xóa/production hạ quota trước kiểm tra; manual pause không lách; giữ D/C; cập nhật ngay sau thành công |
| Quyền | không phải owner, tài khoản khóa, client cũ/direct PATCH/DELETE, không giả được admin/system bypass |
| App và thời gian | active/paused/completed, slot hết hạn, admin pause, app hết 30 ngày, app mới đăng/ngày đăng ký, qua 00:00 VN |
| Gọi lại | bấm kép, timeout sau commit, pending_removal, worker hôm sau, production lại, không hoàn Credit/trả thưởng hai lần |
| Đồng thời | hai thao tác cùng owner, đăng app xen giữa gate/mutation, duyệt báo cáo, đóng thưởng, finalizer; PostgreSQL thật nếu khả dụng |
| Android | ViewModel hành vi, loading/dialog/message, fetch fresh, account switch/day rollover/stale response, Home events và cache |
| Hồi quy | production 089, owner deletion/bonus, pause/resume, immediate quota 088, carryover, mission và egress feed sync v2 |

Kiểm thử có fixture riêng, không sửa số dư/dữ liệu người dùng thật. Nếu cần thích
nghi kiểm thử cũ vì quy tắc mới, seed trạng thái hoàn thành hợp lệ thay vì vô hiệu
hóa gate hoặc xóa assertion. Chứng minh cặp before/after cho lỗi chính.

## 6. Quy trình giao và giám sát

1. Xác minh model từ `agy models` trong phiên đăng nhập hoạt động; không đổi model.
2. Lưu git status và fingerprint trước sửa; giao prompt cùng kế hoạch này qua CLI.
3. Agent triển khai, test và tự review; supervisor xem diff và tự chạy lại kiểm tra
   quan trọng. Gửi lỗi kèm ca tái hiện về đúng conversation để sửa tiếp.
4. Kết thúc khi kiểm tra phù hợp qua, không còn lỗi đã biết trong phạm vi và các
   giới hạn được ghi rõ. Không tuyên bố chắc chắn phần chưa kiểm tra là hết lỗi.
5. Nếu CLI bị chặn xác thực/quyền/quota, giữ nguyên công việc, lưu log và báo đúng
   blocker. Không tự thay model, không giả giao việc thành công, không lấy secret
   từ kho khác hoặc đi vòng hạn chế quyền.

## 7. Sản phẩm bàn giao

- Migration mới, thay đổi Android/localization tối thiểu cần thiết.
- Các bài test thực thi, log và danh sách file thay đổi so với snapshot đầu.
- Báo cáo tại `audit/daily-task-action-gate-2026-11-12/IMPLEMENTATION_RESULT.md`.
- Xác nhận phiên bản không đổi, không tạo APK/AAB, không deploy production.
- Hướng dẫn thứ tự áp dụng migration và kiểm tra sau triển khai, nêu riêng trạng
  thái 089 vốn chưa được áp dụng production trong phiên làm việc này.
