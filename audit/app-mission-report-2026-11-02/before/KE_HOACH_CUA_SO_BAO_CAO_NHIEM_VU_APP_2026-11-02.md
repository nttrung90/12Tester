# Nghiên cứu và kế hoạch cửa sổ báo cáo “Nhiệm vụ app”

Ngày: 02/11/2026 — đối chiếu mã nguồn hiện có trong workspace.

Cập nhật yêu cầu: tối đa **3 ảnh/báo cáo nhiệm vụ**; tester đã tham gia được gửi thêm **01 báo cáo nhiệm vụ mỗi ngày** trong giới hạn thời gian của nhiệm vụ, độc lập với báo cáo thường. Ví dụ nhiệm vụ nhận báo cáo trong 3 ngày thì mỗi ngày trong 3 ngày đó tester có thêm một lượt báo cáo nhiệm vụ. Báo cáo này có nhãn riêng để chủ app phân biệt.

Đây là kế hoạch triển khai; chưa thay đổi code ứng dụng, database hoặc cấu hình production. Các kết luận hiện trạng bên dưới đến từ source local, chưa xác minh trạng thái backend đang triển khai.

## 1. Kết quả cần đạt

Khi tester bấm vào một nhiệm vụ trong tab **Nhiệm vụ app**, ứng dụng mở **cửa sổ riêng cho đúng nhiệm vụ đó**:

- Phía trên có hai nút cùng hàng: **Mở app** và **Link Play Store**.
- Bên dưới có phần **Báo cáo nhiệm vụ app**, cho chọn, xem lại, xóa và nộp **tối đa 3 ảnh trong một báo cáo**.
- Nén ảnh, thumbnail, hash, cache, gửi nền và retry dùng chung cơ chế của báo cáo nhiệm vụ thường.
- Tester trở về từ app được test hoặc Play Store vẫn thấy đúng cửa sổ và bản nháp đã nhập.
- Giữ hạn mức báo cáo thường 01 báo cáo/ngày theo phạm vi app của luồng hiện hành; khi tham gia nhiệm vụ, có thêm 01 báo cáo/ngày cho nhiệm vụ đó trong thời hạn cho phép. Hai loại lưu thành hai báo cáo riêng, có thể cùng tồn tại trong một ngày cho cùng app.
- Báo cáo thêm luôn có nhãn **Báo cáo nhiệm vụ app**, kèm tên nhiệm vụ và ngày báo cáo; chủ app nhận diện được ngay khi xem danh sách, chi tiết và thông báo.

Đề xuất mặc định để triển khai: báo cáo mới cần **1–3 ảnh**; cập nhật báo cáo đang chờ có thể giữ bộ ảnh hiện có; báo cáo đã duyệt tiếp tục chỉ bổ sung nhận xét theo quy tắc hiện tại. Yêu cầu đã chốt mức tối đa 3 ảnh, còn mức tối thiểu 1 là giả định kế thừa luồng báo cáo mới đang có.

“Cửa sổ riêng” được hiểu là dialog toàn màn hình trong app, có nút đóng và Back, được quản lý bởi Navigation. Không cần tạo Android Activity riêng. Phần báo cáo hiển thị trực tiếp trong cửa sổ, không bắt tester mở thêm một bottom sheet để nhập.

## 2. Hiện trạng đã đối chiếu

| Thành phần | Mã nguồn | Hiện trạng và tác động |
| --- | --- | --- |
| Điểm vào nhiệm vụ | `ui/screens/QuestsScreen.kt`, `ui/screens/HomeScreen.kt` | Card truyền `campaignId` và `mission.id`; CTA cho người đã nhận nhiệm vụ cũng mở app detail. Cần đổi đích điều hướng của nhiệm vụ. |
| Navigation | `ui/navigation/NavGraph.kt:36`, `:88`, `:122` | Nhiệm vụ đang đi vào `CampaignDetailScreen` qua `campaign/{campaignId}?missionId=...`, dùng chung màn chi tiết app. |
| Biểu mẫu | `ui/screens/CampaignDetailScreen.kt:2322` | `SubmitReportBottomSheet` nhận một URI và một `preparedScreenshot`; chưa có danh sách 3 ảnh. |
| Xử lý nộp | `ui/viewmodels/CampaignDetailViewModel.kt:1151` | Có kiểm tra chủ app, tham gia, ngữ cảnh nhiệm vụ, hạn và xung đột loại báo cáo. Giữ các kiểm tra quyền/hạn; thay kiểm tra xung đột cùng ngày để hai loại có thể tồn tại độc lập. |
| Nén ảnh | `util/FirebaseStorageHelper.kt:25` | JPEG 1080 px, quality 80; nếu quá 1.900.000 byte thì giảm quality 60, rồi 720 px/quality 70. Hash tính trên ảnh đã nén; thumbnail 360 px/quality 75. |
| Ảnh chuẩn bị | `util/PreparedScreenshot.kt` | File riêng trong `noBackupFilesDir/prepared_report_images`, có lease; chính sách dọn ảnh cũ 48 giờ. |
| Hàng đợi | `data/ReportSubmitManager.kt`, `data/PendingReportStore.kt`, `work/ReportUploadWorker.kt` | Mỗi request chỉ có một file ảnh, một thumbnail, một URL/hash và một SHA-256. Có lưu receipt và URL đã upload để phục hồi. |
| Dữ liệu/API | `data/Models.kt:245`, `:809`; `data/CampaignRepository.kt:873` | `TesterReport`, request và response đều có một `screenshot_url`, `thumbnail_url`, `image_hash`; RPC client gọi `submit_tester_report_v2`. |
| Database hiện hành trong repo | `supabase/migrations/064_explicit_report_context.sql`, `071_business_policies.sql:2492` | 064 khai báo endpoint v2; 071 ghi đè hàm xử lý nội bộ và bổ sung chính sách tài chính. Triển khai mới phải kế thừa 071, không chỉ lấy bản 062/064. |
| Số báo cáo/ngày | `071_business_policies.sql:2791` | Hiện hàm tìm một báo cáo theo tester + campaign + ngày Việt Nam, rồi chặn đổi context/mission. Phải thay cách tra cứu/khóa để nhận độc lập một báo cáo thường và một báo cáo nhiệm vụ/ngày theo yêu cầu mới. |
| Cache ảnh đã nộp | `util/ScreenshotManager.kt`, `util/ScreenshotRequestFactory.kt`, `util/PrivateScreenshotDiskCache.kt` | Có RAM cache 24 MiB, single-flight, ranh giới phiên/tài khoản và hết hạn 30 ngày. Private disk cache và media gateway mặc định tắt trong source. |
| Phân quyền media | `backend/media-gateway/adapters/supabase-authority-adapter.mjs` | Gateway hiện chỉ tra ảnh chính/thumbnail theo report; chưa chọn được ảnh số 2–3. |
| Storage/retention | `storage.rules`, `backend/storage-cleanup/cleanup-reports-storage.mjs` | Ảnh JPEG dưới 2 MiB, chỉ create; report đặt `private, no-store`, retention 30 ngày. Mỗi ảnh thêm phải có thumbnail/path tương ứng. |

Các đường dẫn Android trong bảng tính từ `app/src/main/java/com/ntt/crosstest/`. Số dòng là mốc tham khảo tại thời điểm nghiên cứu.

**Kết luận kỹ thuật:** đây là thay đổi cả luồng báo cáo, không thể hoàn tất chỉ bằng thêm bộ chọn 3 ảnh trên giao diện. Cần mở rộng model, queue, RPC, dữ liệu và bên đọc báo cáo.

## 3. Luồng giao diện đề xuất

```text
Tab Nhiệm vụ app
  → Bấm card/tên app/CTA nhiệm vụ
  → Cửa sổ nhiệm vụ cụ thể

┌──────────────────────────────────────┐
│ Nhiệm vụ app                    [×]  │
│ Icon · Tên app · Tên nhiệm vụ         │
│ [ Mở app ]  [ Link Play Store ]       │
│                                      │
│ Yêu cầu nhiệm vụ · Credit · Hạn nộp   │
│                                      │
│ Báo cáo nhiệm vụ app                  │
│ Đánh giá / Nhận xét                   │
│ Ảnh minh chứng                  2/3  │
│ [Ảnh 1 ×] [Ảnh 2 ×] [Thêm ảnh +]    │
│                                      │
│ [ Gửi báo cáo nhiệm vụ ]              │
│ Đang gửi 2/3 ảnh / Chờ duyệt / Lỗi    │
└──────────────────────────────────────┘
```

### 3.1. Điểm vào và danh tính nhiệm vụ

- Tạo route riêng, ví dụ `mission_report/{campaignId}/{missionId}`, bắt buộc có đủ hai ID hợp lệ.
- Card nhiệm vụ, dòng app và CTA “đã nhận” cùng mở route này; không biến thao tác chuyển sang tab “Nhiệm vụ app” thành tự mở một nhiệm vụ bất kỳ.
- Khôi phục route hoặc mở đường dẫn nhiệm vụ cũ có `missionId` phải chuyển đến cửa sổ mới với đúng ID. Điểm vào test hằng ngày tiếp tục dùng `CampaignDetailScreen`.
- Bản nháp có khóa `(userId, campaignId, context, missionId, reportDate)`. Ngày báo cáo theo giờ Việt Nam; không tìm “nhiệm vụ mới nhất” để thay cho ID đang mở. Qua ngày mới phải nhận diện nháp ngày cũ, không tự đổi ngày của request đã tiếp nhận.
- Có thể xem chi tiết khi chưa nhận nhiệm vụ; phần báo cáo hiển thị hành động “Nhận nhiệm vụ” trước khi cho gửi. Dùng `joinMission`, không coi `joinCampaign` là đã nhận nhiệm vụ.
- Nhiệm vụ đầy vẫn phải xét người đã tham gia; hết hạn/tạm dừng/dừng xử lý theo quy tắc server hiện hành.

### 3.2. Hai nút phía trên

| Nút | Hành vi |
| --- | --- |
| **Mở app** | Mở launcher theo đúng `packageName` của campaign. Chỉ ghi nhận bắt đầu test khi thao tác mở app thành công. Nếu chưa cài/không mở được, hiện thông báo và hướng tester dùng nút Play Store. |
| **Link Play Store** | Mở trang Google Play của app; ưu tiên ứng dụng Play Store và dự phòng trình duyệt. Nếu nhiệm vụ cần tham gia closed test, hiển thị đường hướng dẫn/testing URL hiện có trong nội dung nhiệm vụ, không làm mất hai nút yêu cầu. |

Giữ bộ đếm thời gian tối thiểu và kiểm tra điều kiện nộp như luồng hiện có; không xem việc mở Play Store là đã test app. Bắt lỗi không có ứng dụng xử lý intent, thiếu package hoặc link không hợp lệ. Manifest đã có query launcher; kiểm tra lại trên thiết bị Android 11+ trước khi thay đổi quyền.

### 3.3. Chọn ảnh và giữ nháp

- Cho chọn nhiều ảnh và bổ sung nhiều lần, tổng đang chọn + đang chuẩn bị không vượt 3. Chỉ nhận ảnh, không nhận video.
- Dùng Photo Picker hỗ trợ nhiều ảnh; trường hợp chỉ còn một chỗ có thể dùng picker một ảnh. ViewModel và server vẫn kiểm tra giới hạn độc lập.
- Hiện thumbnail, số `n/3`, trạng thái chuẩn bị/lỗi cho từng ảnh; có xóa, thay ảnh và xem toàn màn hình. Ảnh đầu là ảnh chính để tương thích bên đọc cũ.
- Với kết quả picker vượt chỗ trống, báo số ảnh tối đa có thể thêm; không âm thầm nhận rồi bỏ ảnh khi gửi.
- Chặn gửi khi chưa đủ ảnh hợp lệ, còn ảnh đang nén hoặc có ảnh lỗi chưa được xử lý. Hủy picker không làm mất ảnh đã chọn.
- Ảnh chuẩn bị xong được lưu file riêng, không chỉ giữ URI có quyền truy cập tạm thời. Ảnh đang chuẩn bị dở khi process chết phải được đánh dấu cần chọn lại nếu chưa có file bền vững.
- Lưu metadata nháp và nội dung theo đúng tài khoản/nhiệm vụ; đóng cửa sổ hoặc chuyển qua app khác không xóa nháp. Khi thành công chỉ xóa phiên bản nháp đã gửi; không xóa chỉnh sửa mới phát sinh sau snapshot.
- Giữ rating/nhận xét và điều kiện bắt buộc của luồng cũ; không thêm yêu cầu nghiệp vụ mới ngoài việc hỗ trợ bộ ảnh.

### 3.4. Trạng thái nộp

| Trạng thái | Giao diện/hành động |
| --- | --- |
| Chưa nhận nhiệm vụ | Xem hướng dẫn; nhận nhiệm vụ thành công mới được gửi. |
| Nháp | Thêm/xóa tối đa 3 ảnh, nhập nhận xét, xem trước. |
| Đang chuẩn bị | Hiển thị riêng từng ảnh; nút gửi chưa hoạt động. |
| Đã tiếp nhận gửi nền | Hiển thị đang gửi; có thể đóng cửa sổ. Không báo “đã nộp” trước khi có receipt server. |
| Mất mạng/lỗi tạm thời | Giữ nguyên request và các ảnh đã upload, hiện trạng thái chờ/thử lại. |
| Chờ duyệt | Hiện bộ ảnh đã lưu; cập nhật theo quyền và quy tắc hiện hành. |
| Bị từ chối | Hiện lý do; ảnh chính mới là bắt buộc như luồng hiện tại khi nộp lại. |
| Đã duyệt | Hiện bộ ảnh đã duyệt; chỉ bổ sung nhận xét theo hợp đồng cũ, không đổi ảnh hoặc cộng thưởng lần nữa. |
| Đã có báo cáo thường trong ngày | Vẫn cho nộp thêm báo cáo nhiệm vụ đúng quyền; không chuyển đổi hoặc ghi đè báo cáo thường. Chiều ngược lại cũng được phép. |
| Đã có báo cáo cho đúng nhiệm vụ trong ngày | Mở đúng report để xem/cập nhật/nộp lại theo trạng thái; không tạo report thứ hai của nhiệm vụ đó trong ngày. |
| Ngày tiếp theo còn trong hạn nhiệm vụ | Có một lượt báo cáo nhiệm vụ mới; không dùng trạng thái đã gửi/đã duyệt hôm trước để khóa lượt hôm nay. |
| Sửa report sai context/mission | Từ chối sửa nhầm report; chọn đúng report hoặc tạo report độc lập trong hạn mức tương ứng. |
| Nhiệm vụ không còn nhận bài | Hiện đúng lý do, giữ nháp; replay đã commit vẫn phải trả receipt trước đó. |

### 3.5. Hạn mức mỗi ngày và nhãn riêng — yêu cầu đã chốt

- Hai nhóm độc lập: **báo cáo thường** và **báo cáo nhiệm vụ app**. Có báo cáo pending/approved/rejected của nhóm này không làm mất lượt tạo báo cáo của nhóm kia.
- Với cùng tester và app, báo cáo thường giữ 01 report/ngày. Với mỗi nhiệm vụ đã tham gia, được thêm 01 report nhiệm vụ/ngày trong các ngày nhiệm vụ cho phép nhận bài. Cập nhật/nộp lại dùng cùng report của ngày đó, không tạo lượt mới.
- Ngày tính theo `Asia/Ho_Chi_Minh`. Khoảng được nộp là phần giao giữa thời gian nhiệm vụ mở nhận bài và thời điểm tester đã tham gia; không cấp lượt bù cho ngày trước khi tham gia hoặc tự kéo dài nhiệm vụ.
- Ví dụ nhiệm vụ mở nhận bài các ngày 02, 03 và 04/11/2026, tester đã tham gia đúng hạn: mỗi ngày có tối đa 01 báo cáo thường + 01 báo cáo nhiệm vụ, tổng tối đa 03 báo cáo nhiệm vụ trong ví dụ. Từ khi nhiệm vụ kết thúc, ngừng tạo báo cáo nhiệm vụ mới; báo cáo đã gửi vẫn được xem và duyệt.
- Xác định quyền bằng trạng thái/thời gian server khi nhận bài; màn hình kiểm tra lại khi qua nửa đêm hoặc trở về foreground. Request offline chưa commit không được tự lùi ngày, vượt hạn hoặc coi là đã nộp; replay đã commit giữ đúng report/ngày cũ.
- Badge bắt buộc ghi **Báo cáo nhiệm vụ app**, kèm tên nhiệm vụ và ngày. Chủ app có bộ lọc **Báo cáo thường / Báo cáo nhiệm vụ app**; thông báo báo cáo mới và màn chi tiết giữ cùng nhãn. Nhãn dựa trên `submission_context = mission` và `mission_id` do server lưu, không dựa trên chuỗi nhận xét tester nhập.
- Thống kê hiển thị số report của từng nhóm và tổng; nếu cả hai được nộp thì có hai report. Credit, check-in và hoàn tất nhiệm vụ vẫn phải qua chính sách server riêng, không suy từ tổng số report.

## 4. Dùng chung nén và cache

### 4.1. Xử lý mỗi ảnh

Tái sử dụng `FirebaseStorageHelper.prepareScreenshot()` cho từng item: decode lấy mẫu → scale → xử lý EXIF → JPEG → kiểm tra kích thước → hash → lưu file → thumbnail. Giữ nguyên thông số hiện hành trong bảng hiện trạng.

- Chuẩn bị tuần tự để tránh giữ cùng lúc 3 bitmap lớn; không decode toàn bộ danh sách song song.
- Mỗi ảnh có ID ổn định, file JPEG, thumbnail và SHA-256 của bytes upload. Preview, so sánh và upload dùng lại file này; retry không nén lại.
- Xóa/thay ảnh phải kiểm tra lease và quyền sở hữu file. Callback nén cũ không được đưa ảnh đã xóa trở lại danh sách hoặc ghi vào nhiệm vụ khác.
- Hash cảm nhận/dHash chỉ phục vụ cảnh báo/so sánh như luồng hiện hành; không khôi phục cơ chế chặn cứng vì ảnh gần giống đã được gỡ ở migration 065. Có thể loại chọn trùng URI/bytes trong cùng bộ ảnh, nhưng không dùng dHash để kết luận hai ảnh là một.
- Danh sách dùng thumbnail, chỉ tải full khi tester/chủ app/admin mở ảnh. So sánh chi tiết thực hiện cho cặp được chọn, không tự tải 9 cặp giữa hai bộ 3 ảnh. Baseline so sánh phải cùng context và đúng nhiệm vụ; không lấy báo cáo thường cùng ngày làm ảnh cũ của nhiệm vụ hoặc dùng ảnh nhiệm vụ khác.

### 4.2. Ba loại lưu ảnh phải được xử lý đúng

| Loại | Áp dụng cho nhiệm vụ app |
| --- | --- |
| File nháp/chuẩn bị | Dùng vùng private hiện có và lease theo từng ảnh; metadata nháp có user/mission scope. Cleanup 48 giờ phải bỏ qua ảnh của nháp còn hiệu lực hoặc request còn hoạt động. |
| File chờ upload | Copy/snapshot bộ ảnh vào pending store, ghi metadata nguyên tử và giữ đến khi request được giải quyết. Không đưa bytes ảnh vào WorkManager Data. |
| Ảnh đã nộp tải về xem | Dùng `ScreenshotManager`, request factory, single-flight, RAM cache và kiểm tra expiry hiện có. Mỗi ảnh/variant phải có định danh riêng, cùng account/generation guard. |

Giữ `Cache-Control: private, no-store` cho report và thumbnail. Không dùng chính sách immutable một năm của icon. Không tự bật private disk cache hoặc media gateway; nếu các cờ này được bật về sau thì toàn bộ 3 ảnh cũng phải tuân thủ no-store, thời hạn, scope tài khoản và grant hiện hành.

Giữ retention 30 ngày và `media_expired`; khi mở gallery phải kiểm tra tất cả item. Ngày hết hạn của report tiếp tục là giới hạn trên khi bổ sung/thay ảnh, không kéo dài chỉ vì ảnh mới upload. Khi logout/đổi tài khoản phải hủy request tải, xóa cache phiên và ngăn kết quả cũ được công bố vào phiên mới.

## 5. Model, lưu trữ và API đề xuất

### 5.1. Danh sách attachment có thứ tự

Thêm bảng `tester_report_attachments` với các trường dự kiến:

```text
id UUID                    -- attachmentId do client tạo, ổn định qua retry
report_id UUID             -- FK tester_reports, ON DELETE CASCADE
position INTEGER           -- 0..2, unique(report_id, position)
screenshot_url TEXT
thumbnail_url TEXT NULL
image_hash TEXT
sha256 TEXT NULL           -- fingerprint bytes client; không coi là bằng chứng server đã xác minh bytes
created_at TIMESTAMPTZ
```

- Check `position between 0 and 2` kết hợp unique bảo đảm tối đa 3 dòng/report; RPC yêu cầu thứ tự liền mạch và ít nhất một ảnh khi tạo mới.
- Bật RLS; quyền đọc theo quyền xem report hiện hành. Chỉ RPC được phép ghi attachment sau khi xác thực tester/campaign/mission; không mở đường insert trực tiếp để bỏ qua giới hạn.
- Các cột đơn `tester_reports.screenshot_url/thumbnail_url/image_hash` tiếp tục phản chiếu ảnh đầu tiên, cập nhật cùng transaction. Report cũ không có attachment rows được adapter đọc thành danh sách một ảnh. Không cần backfill hàng loạt để bắt đầu sử dụng.
- `TesterReport` và response mới có `attachments`; các endpoint đọc trả danh sách có thứ tự và số ảnh. Một report vẫn chỉ được tính một lần trong thống kê, duyệt, Credit và tiến độ.

### 5.2. RPC có phiên bản cho bộ ảnh

Đề xuất endpoint `submit_tester_report_v3` nhận `p_submission_context`, `p_campaign_id`, `p_mission_id`, nội dung/rating, `p_client_submit_id` và `p_attachments` có thứ tự. Thêm ngày dự kiến như precondition để phát hiện request nháp/pending qua ngày; server vẫn là nơi xác định ngày hợp lệ, không cho client tự tạo báo cáo lùi ngày. Phiên bản mới dành cho cửa sổ nhiệm vụ; test hằng ngày vẫn có thể dùng v2 một ảnh.

Quy ước bộ ảnh: tạo mới bắt buộc danh sách 1–3 item; cập nhật pending dùng `null` để giữ nguyên hoặc danh sách 1–3 item để thay toàn bộ; `[]` bị từ chối. Approved chỉ chấp nhận giữ ảnh (`null`) và cập nhật nhận xét theo hợp đồng hiện hành. Nộp lại rejected phải có bộ ảnh hợp lệ và ảnh chính mới. Mọi trường hợp giữ ảnh phải lấy bộ ảnh từ server, không dùng danh sách rỗng để diễn đạt “không đổi”.

1. Xác thực tài khoản, giới hạn/schemas bộ ảnh và quyền. Với `mission`, bắt buộc đúng missionId và campaign; không suy nhiệm vụ từ dữ liệu đã tham gia.
2. Kiểm tra từng URL theo bucket/path `reports/{campaignId}/{userId}/...` hiện có; thumbnail phải thuộc đúng ảnh, hash đúng định dạng. Không tin `userId` hoặc `role` do client gửi.
3. Fingerprint có phiên bản, bao gồm context, mission, nội dung/rating và toàn bộ attachment theo thứ tự. Canonical hóa payload trước khi hash; thêm/xóa/đổi thứ tự ảnh dưới cùng request ID phải bị từ chối.
4. Receipt lưu snapshot kết quả đầy đủ, gồm bộ ảnh và phiên bản hợp đồng. Replay trả đúng snapshot đã commit, không ghi đè bộ ảnh hiện tại và không phát sinh thưởng lại.
5. Dùng khóa và giao dịch chung cho report, attachments, participation, Credit và receipt. Tra đúng report theo context/mission/ngày và kiểm tra lại hạn mức, trạng thái report, quyền sau khi giữ khóa; cho hai loại report cùng ngày tồn tại như mục 3.5.
6. Không triển khai kiểu “gọi v2 ghi receipt một ảnh rồi gắn thêm hai ảnh sau”. Nếu tách core dùng chung, core phải biết payload/receipt của cả bộ ảnh trước khi commit.
7. Giữ chính sách tài chính mới nhất từ 071 và kiểm tra tương thích các migration sau nó. Không sao chép phần xử lý Credit từ một bản migration cũ.
8. Thay bộ ảnh của report pending/nộp lại là thao tác nguyên tử: thất bại giữ toàn bộ bộ cũ; approved chỉ sửa nhận xét. Không xóa object Storage cũ từ client; để chính sách cleanup xử lý.
9. Backend chưa hỗ trợ v3: báo cần cập nhật server, giữ nguyên nháp; không fallback bỏ bớt hai ảnh hoặc chuyển báo cáo thành test thường.

V2/client cũ vẫn được đọc và nộp báo cáo một ảnh như hợp đồng hiện có. Khi gặp report có nhiều ảnh, v2 phải từ chối cập nhật ảnh nếu không thể bảo toàn toàn bộ bộ ảnh; không cho client cũ ghi đè riêng ảnh chính làm lệch attachments. Replay receipt cũ và bổ sung nhận xét hợp lệ phải được bảo toàn.

Tạo migration mới sau số hiện có cao nhất khi bắt đầu triển khai (workspace hiện đến 073). Không sửa lịch sử migration đã có.

### 5.3. Tách danh tính báo cáo thường và báo cáo nhiệm vụ theo ngày

Đây là thay đổi bắt buộc của yêu cầu mới; thay cho quy tắc cũ chặn hai loại report cùng ngày.

| Loại | Khóa nghiệp vụ duy nhất |
| --- | --- |
| Thường (`app_test`) | `(user_id, campaign_id, report_date)` trong nhóm `app_test` |
| Nhiệm vụ (`mission`) | `(user_id, campaign_id, mission_id, report_date)` trong nhóm `mission` |

- Thêm `report_date` do server xác định theo ngày Việt Nam tại lần tạo report, không nhận ngày tùy ý từ client. Tạo partial unique indexes theo hai nhóm ở trên; khóa transaction tương ứng để chống hai request đồng thời vượt hạn mức.
- Trước khi backfill ngày/index, rà dữ liệu cũ và các report trùng khóa; lập danh sách đối soát nếu có, không tự xóa report hoặc sửa receipt/Credit đã lưu.
- Thay mọi nhánh “lấy report mới nhất của tester + app + hôm nay” trong create/update/resubmit/approved-comment bằng tra cứu đúng context, mission và ngày hoặc reportId có kiểm tra quyền/danh tính. Không tự chọn một trong hai report bằng `limit 1`.
- Sửa cả hàm dùng chung với v2: báo cáo thường một ảnh của client cũ vẫn phải tạo/cập nhật đúng `app_test` khi cùng ngày đã có report nhiệm vụ. V3 cũng không được từ chối chỉ vì đã tồn tại report thường. Cấm đổi context/mission của một report đã tạo vẫn có hiệu lực.
- RPC đọc/model trả report thường hôm nay và report nhiệm vụ hôm nay tách biệt. Giữ field legacy `myTodayReport` cho report thường nếu consumer cũ cần; bổ sung dữ liệu nhiệm vụ có ID/ngày rõ ràng, không nhét hai loại vào một biến rồi suy đoán bên UI.
- Không dùng chung `checkedInToday`, approved thường hoặc cờ đã gửi hôm qua để suy ra nhiệm vụ hôm nay đã nộp. Hạn mức thường, hạn mức nhiệm vụ, số ngày check-in và các sự kiện thưởng có điều kiện riêng.
- Rà logic `mission_participants.status`, `completed_at`, `hasCompleted`, duyệt báo cáo và settlement: gửi/duyệt ngày đầu không được làm tester mất quyền nộp các ngày tiếp theo trong thời hạn nhiệm vụ. Phân biệt trạng thái báo cáo từng ngày với trạng thái hoàn tất toàn nhiệm vụ; giữ đối soát thưởng và Credit nguyên tử theo cấu hình server.
- Receipt/fingerprint chứa ngày báo cáo đã xác định và danh tính nhóm/nhiệm vụ; pending snapshot giữ ngày dự kiến để kiểm tra precondition. Retry qua nửa đêm trả receipt cũ nếu đã commit; chưa commit mà ngày không còn khớp thì giữ dữ liệu và báo cần chuyển nháp sang ngày hợp lệ, không tự đổi ngày hoặc tạo cả hai ngày dưới một request ID. Yêu cầu mới sau khi xác nhận chưa commit dùng request ID mới theo cơ chế reconcile hiện có.

## 6. Hàng đợi gửi nền và phục hồi

Mở rộng `SubmitRequest`/`PendingReport` thành danh sách `PendingAttachment`, mỗi item có ID, position, file/thumbnail path, SHA-256, hash, URL đã upload và trạng thái. Thêm `schemaVersion` để đọc queue một ảnh cũ an toàn; request legacy thiếu context tiếp tục đi qua reconcile hiện có, không tự đoán loại.

Luồng gửi:

```text
Khóa snapshot nội dung + context + bộ ảnh
  → Copy file và ghi pending metadata bền vững
  → Enqueue một work theo requestId
  → Upload lần lượt các ảnh chưa có URL
  → Ghi checkpoint ngay sau từng ảnh đầy đủ
  → Thumbnail từng ảnh, lỗi thumbnail không làm hỏng ảnh chính
  → Gọi RPC một lần với đầy đủ bộ ảnh
  → Lưu receipt đầy đủ
  → WorkManager xác nhận thành công
  → Dọn file/nháp đúng ownership
```

- Một request ID cho một bộ ảnh bất biến; mỗi attachment có upload ID riêng để không ghi đè nhau. Path vẫn theo schema Storage hiện có, ví dụ `{attachmentId}.jpg` và `thumb_{attachmentId}.jpg`.
- Dùng cơ chế phục hồi object đã tồn tại khi chỉ-create như hiện tại. Checkpoint ảnh 1–2 phải còn sau khi ảnh 3 lỗi/process chết; lần sau chỉ tiếp tục phần chưa xong.
- File/SHA-256 của từng ảnh phải được kiểm tra trước upload. Ảnh thiếu hoặc bị đổi là lỗi rõ ràng, không tự nộp thiếu ảnh.
- Giữ cơ chế session/account check, hủy khi đăng xuất, retry lỗi tạm thời và backoff hiện có. Báo lỗi vị trí ảnh để tester xử lý được.
- Công việc còn sống theo request khi đóng cửa sổ; nháp chỉnh tiếp là snapshot khác, không được sửa work đang chạy. Đổi admission lock và state key từ chỉ `campaignId` sang `(userId, campaignId, context, missionId, reportDate)` để báo cáo thường và nhiệm vụ không chặn/ghi đè trạng thái của nhau. Retry/cancel/khôi phục sau restart phải chọn đúng request/nhóm/ngày.
- Rà `pendingCampaignIds()` và gợi ý app tiếp theo: request nhiệm vụ không được làm mất lượt báo cáo thường của app đó; lọc pending theo context/ngày khi áp hạn mức và gợi ý.
- Hiện tiến độ `đã upload x/y ảnh`; “đang gửi” và “đã nộp/chờ duyệt” là hai trạng thái khác nhau. Cửa sổ mission trở về danh sách nhiệm vụ hoặc giữ trạng thái chờ duyệt, không tự mở gợi ý test app tiếp theo của luồng test hằng ngày.
- WorkManager Data chỉ truyền ID và trạng thái ngắn. Receipt/bộ URL nằm trong pending store; consumer đọc receipt đó để tránh giới hạn kích thước Data ở output khi có 3 URL dài.

**Điểm cần chỉnh khi mở rộng:** pending store hiện có budget 20 request/20 MiB và dọn theo tuổi; prepared cleanup hiện xóa theo tuổi. Một bộ 3 ảnh có thể gần 5,7 MB full, còn có thumbnail và bản copy pending. Phải tính budget theo tổng bytes thật, bảo vệ file của work/nháp đang dùng và báo thiếu dung lượng trước khi tiếp nhận; không tự tăng budget hoặc xóa request còn sống để nhường chỗ. Kiểm thử cả cleanup khi đang upload, restart và receipt chưa được WorkManager xác nhận.

## 7. Bên đọc báo cáo và media

- Cửa sổ tester, lịch sử, màn chi tiết app, màn chủ app và hàng đợi admin đều đọc được 1–3 ảnh; xem bằng gallery có vị trí `1/3`, thumbnail và tải full theo nhu cầu. Danh sách chủ app gắn badge **Báo cáo nhiệm vụ app**, tên nhiệm vụ, ngày; có bộ lọc tách với báo cáo thường.
- Rà `get_campaign_detail` (bản định nghĩa mới nhất ở 070), `get_campaign_reports_for_owner`, `admin_get_tester_reports_queue` và các truy vấn/model đọc trực tiếp report. Tránh N+1 khi tải attachments cho danh sách.
- Duyệt/từ chối/thưởng vẫn thao tác theo report, không lặp qua từng ảnh để chạy nghiệp vụ. Bộ lọc “Báo cáo nhiệm vụ” và tổng report không bị nhân theo số attachment.
- Credit/thông báo thành công lấy từ receipt và chính sách server; không gán cứng mức thưởng của test thường cho cửa sổ nhiệm vụ.
- Mở rộng `ScreenshotDescriptor`, request factory và image viewer để nhận attachment cụ thể; không lấy thumbnail/full từ ảnh chính cho mọi vị trí trong gallery.
- Gateway và RPC cấp grant cần nhận attachmentId; chữ ký phải ràng buộc report, attachment, variant và phiên/quyền theo hợp đồng hiện có. Tra đúng attachment thuộc report trước khi tải, chống đổi tham số để dùng grant ảnh 1 lấy ảnh 2. Giữ đường cũ cho ảnh chính/legacy.
- Gateway hiện mặc định tắt nhưng vẫn phải nâng hợp đồng trước khi cờ được bật; không tuyên bố gallery 3 ảnh hoàn tất nếu nhánh này luôn trả ảnh đầu.
- `storage.rules` hiện cho phép đọc public; RLS và grant chỉ kiểm soát các đường truy cập tương ứng, không tự làm URL Storage cũ trở thành riêng tư. Kế hoạch này kế thừa mô hình truy cập hiện có, không coi `private, no-store` là cơ chế phân quyền.
- Cleanup và vòng đời thumbnail phải bao phủ từng cặp ảnh, kể cả upload trễ/lỗi giữa chừng; giữ prefix `reports/`, mốc retention và không đụng icon. Không coi việc xóa row attachment là đã xóa object Storage.

## 8. Các phần việc triển khai

| Bước | Thay đổi chính | Điều kiện hoàn tất |
| --- | --- | --- |
| 1. Chốt contract và regression | Payload tối đa 3 ảnh, định danh report theo context/nhiệm vụ/ngày, receipt, legacy mapping | Fixture hai loại report cùng ngày và nhiệm vụ 3 ngày; quy tắc có test. |
| 2. Backend | Migration mới, bảng/RLS, unique indexes theo nhóm/ngày, RPC v3 và v2/core, read APIs, participation | Nhận độc lập một report thường + một report nhiệm vụ/ngày trong hạn; transaction/replay không trùng dữ liệu/Credit. |
| 3. Pipeline dùng chung | Models, pending store, manager/worker với key nhóm/ngày, checkpoint/cleanup nhiều ảnh | Hai luồng gửi không chặn nhau; restart phục hồi đúng snapshot/ngày. |
| 4. Cửa sổ nhiệm vụ | Route/dialog, ViewModel riêng, hai nút, multi-picker, grid, nháp và trạng thái gửi | Tester hoàn tất luồng từ card tới chờ duyệt trong cửa sổ riêng. |
| 5. Hiển thị và media | Badge/bộ lọc report thường–nhiệm vụ, gallery tester/owner/admin, descriptors/cache/grant, cleanup | Nhãn nhiệm vụ rõ ràng; cả ba ảnh xem đúng quyền, đúng thứ tự; legacy vẫn xem được. |
| 6. Nghiệm thu | Regression, máy thật, staging, tài liệu triển khai | Đạt tiêu chí bên dưới trước khi bật cho người dùng. |

Tệp mới dự kiến: `MissionReportScreen.kt`, `MissionReportViewModel.kt`, component bộ ảnh dùng chung và store nháp nếu chưa có cơ chế phù hợp. Tệp phải mở rộng: `NavGraph.kt`, `QuestsScreen.kt`, `HomeScreen.kt`, `Models.kt`, `CampaignRepository.kt`, `ReportSubmitManager.kt`, `PendingReportStore.kt`, `ReportUploadWorker.kt`, utilities/media viewer, các màn owner/admin liên quan và strings Việt/Anh. Tận dụng helper nén hiện có thay vì tạo thuật toán nén thứ hai.

## 9. Kiểm thử và tiêu chí nghiệm thu

### Giao diện và điều hướng

- Bấm nhiệm vụ A mở đúng cửa sổ A; hai nhiệm vụ chung campaign không dùng nhầm nháp/trạng thái.
- Hai nút có đúng nhãn và vị trí; app đã cài/chưa cài, Play Store có/không có, link lỗi và package thiếu đều xử lý được.
- Chọn 1, 3, 4 ảnh; chọn nhiều đợt; còn một chỗ; fallback picker; hủy chọn; ảnh lỗi; xóa khi đang nén. Không bao giờ gửi hơn 3 hoặc âm thầm bỏ ảnh.
- Chuyển sang app khác, quay lại, xoay màn hình, đóng/mở cửa sổ, process restart giữ đúng nháp đã lưu. Logout/đổi tài khoản không hiện nháp/ảnh tài khoản trước.
- Nhãn/error Việt/Anh, ảnh lớn, màn nhỏ, bàn phím và font lớn vẫn thao tác được.

### Gửi nền và dữ liệu

- Ảnh 1–2 upload xong, ảnh 3 lỗi: retry không nén/upload lại hai ảnh đã có checkpoint.
- Process chết sau upload nhưng trước checkpoint; RPC commit nhưng mất response; kill sau lưu receipt nhưng trước WorkManager success: phục hồi một report và một lần tác dụng tài chính.
- Cùng request ID đổi ảnh, thứ tự, context hoặc mission bị từ chối; replay cũ không thay bộ ảnh của lần cập nhật mới.
- 0/4 ảnh bị server từ chối kể cả client bỏ qua UI; URL sai user/campaign/bucket, hash sai, thumbnail sai cặp bị từ chối.
- Client cũ, pending một ảnh cũ, receipt cũ và server thiếu v3: giữ dữ liệu hoặc báo lỗi cập nhật rõ ràng, không mất hai ảnh.
- Cùng ngày gửi thường → nhiệm vụ và nhiệm vụ → thường đều có hai report đúng nhãn, mỗi report có bộ ảnh/nội dung/trạng thái riêng. Có báo cáo thường pending/rejected/approved đều không chặn lượt nhiệm vụ và ngược lại.
- Gửi hai report mới cho cùng nhiệm vụ/ngày: chỉ một report tồn tại, request còn lại theo hợp đồng cập nhật hoặc bị từ chối đúng mã; không phát sinh lượt/thưởng trùng. Tạo report thường thứ hai trong ngày cũng không vượt hạn mức.
- Nhiệm vụ 3 ngày: mỗi ngày được thêm một report nhiệm vụ, ngày thứ tư/hết hạn không tạo mới. Tham gia từ ngày thứ hai chỉ có lượt trong thời gian còn lại, không có lượt bù ngày đầu. Duyệt ngày đầu không chặn các ngày còn lại.
- Hai request thường và nhiệm vụ đồng thời, hai request cùng nhiệm vụ/ngày, race cập nhật/duyệt và retry qua nửa đêm/hạn cuối: kiểm tra trên PostgreSQL nhiều kết nối. PGlite đơn kết nối không chứng minh được hành vi cạnh tranh.
- Giữ nguyên chặn chủ app, điều kiện nhận nhiệm vụ, người đã nhận khi full, hết hạn/tạm dừng và quyền cập nhật pending/rejected/approved; chặn đổi context/mission của report đã tồn tại, đồng thời cho hai loại tạo report độc lập.
- Mỗi lần test đối chiếu report, attachments, receipt, participation và Credit/logs; kiểm tra chính sách tài chính 071 còn nguyên.

### Nén, cache, bên xem và tài nguyên

- Mỗi ảnh chuẩn bị một lần; preview/retry dùng đúng bytes/hash; ảnh xoay EXIF, ảnh lớn/nhiễu không vượt ngưỡng Storage hoặc gây OOM.
- Danh sách dùng thumbnail, không tự tải cả ba ảnh full; single-flight hoạt động khi nhiều viewer cùng mở một ảnh.
- Grant/descriptor/cache không lẫn ảnh chính và ảnh phụ; thử đổi attachmentId và đổi tài khoản giữa lúc tải.
- No-store và cờ disk-cache/gateway hiện hành được giữ; media hết hạn/403/404 hiển thị đúng, không bypass qua URL khác trong gallery.
- Cleanup lúc gần đầy bộ nhớ, nháp quá tuổi, work đang chạy, receipt chờ xác nhận không làm mất file còn cần. Không tăng trần RAM theo số ảnh.
- Chủ app/admin thấy đủ ba ảnh, badge **Báo cáo nhiệm vụ app**, tên nhiệm vụ/ngày và bộ lọc. Một report chỉ duyệt một lần; khi có cả report thường và nhiệm vụ, danh sách/tổng hiển thị hai report và trạng thái duyệt độc lập. Credit/check-in đối chiếu chính sách server, không nhân theo số ảnh hoặc cộng lại khi retry.

Các bộ test hiện có có thể mở rộng: `PendingReportStoreTest`, `ReportUploadWorkerTest`, `ReportSubmitManagerIntegrationTest`, `ReportBackgroundSubmissionTest`, `MissionReportAndExpirationTest`, `ReportRpcCompatibilityTest`; phía SQL có scripts report-context, idempotency, mission-report, media-gateway, business-policies và admin integration. Thêm test hành vi nhiều ảnh thay vì chỉ kiểm tra chuỗi source.

Triển khai staging theo thứ tự: backend/read API và media contract → client có cửa sổ mới → thử trên máy thật → phát hành theo quy trình dự án. Không chạy migration production hoặc thay version/build trong giai đoạn lập kế hoạch này. Chỉ nghiệm thu khi cả gửi, phục hồi và bên nhận đều xử lý đủ ba ảnh.

## 10. Tài liệu Android đã đối chiếu

- [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker): hỗ trợ chọn nhiều ảnh; fallback `ACTION_OPEN_DOCUMENT` có thể bỏ qua giới hạn lựa chọn của picker, nên app phải kiểm tra lại số ảnh. Quyền đọc URI tạm thời cũng là lý do cần file chuẩn bị bền vững cho gửi nền.
- [Package visibility](https://developer.android.com/training/package-visibility/declaring): Android 11+ có lọc package, cần kiểm tra query launcher khi mở app đã cài.
- [WorkManager Data](https://developer.android.com/reference/androidx/work/Data): Data là container nhỏ có giới hạn kích thước, không phải nơi lưu bộ ảnh hoặc toàn bộ receipt nhiều URL.

Nghiên cứu này mới xác minh source và tài liệu nền tảng; chưa chạy test triển khai tính năng hoặc kiểm thử thiết bị vì chưa sửa code.
