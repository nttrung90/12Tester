# Kế hoạch Báo cáo nhiệm vụ và ẩn nhiệm vụ hết hạn — 29/10/2026

## 1. Mục tiêu và phạm vi

Theo yêu cầu: người test nhiệm vụ tiếp tục dùng luồng báo cáo test app hiện có, nhưng kết quả phải được nhận diện riêng là **“Báo cáo nhiệm vụ”**. Khi hết thời gian thực hiện, nhiệm vụ không xuất hiện trong tab **“Nhiệm vụ app”** nữa.

Tài liệu này là kết quả nghiên cứu source và kế hoạch triển khai, **chưa sửa tính năng, chưa deploy**. Đã chạy probe trên PostgreSQL nhúng PGlite với dữ liệu giả; chưa kiểm tra Android trên thiết bị hoặc trạng thái Supabase production. Các file Android đang có thay đổi chưa commit thuộc công việc gửi báo cáo phải được giữ nguyên.

Quy ước dùng để lập kế hoạch:

- Thời điểm kết thúc nhiệm vụ là `missions.ends_at`; từ `server_now >= ends_at` thì ẩn đối với mọi người, kể cả chủ và tester đã nhận.
- `full` nghĩa là đủ người nhận, vẫn cho tester đã nhận thực hiện trong thời hạn.
- Tester hoàn thành riêng (`has_completed`) khác với hết thời gian của cả nhiệm vụ. Trong phạm vi này, tester hoàn thành sớm vẫn có thể thấy thẻ “Đã hoàn thành” cho đến hạn; không tự kết thúc nhiệm vụ của những người còn lại.
- Nhiệm vụ tạm dừng còn hạn giữ cách hiển thị hiện hành và không cho nộp kết quả nhiệm vụ khi đang dừng. Nhiệm vụ đã xóa hoặc dừng do hết Credit không nằm trong danh sách thực hiện.
- Ẩn thẻ không xóa mission, participant, báo cáo, lịch sử Credit hoặc job thanh toán. Báo cáo đã nộp vẫn xem/duyệt qua màn quản lý app và lịch sử test.
- Tiếp tục một luồng upload, một báo cáo, một lần tính chỉ tiêu/thưởng test thường. Phần thưởng nhiệm vụ tiếp tục qua cơ chế settlement riêng hiện có.

## 2. Hiện trạng đã xác nhận

| Điểm | Bằng chứng trong source hiện tại | Hệ quả |
|---|---|---|
| Mở app từ thẻ nhiệm vụ | `QuestsScreen.kt:545` chỉ truyền `mission.campaignId`; `HomeScreen.kt:676` mở route campaign thường; `NavGraph.kt:27` chỉ có `campaignId` | Mất ngữ cảnh nhiệm vụ khi đến màn báo cáo |
| Dữ liệu báo cáo | `Models.kt:216` và schema `tester_reports` không có trường liên kết mission | Báo cáo chưa có căn cứ bền vững để hiện nhãn nhiệm vụ |
| Phân loại hiện có | `report_type` bị giới hạn `daily_test / bug_report / feedback` ở migration 004 | Không thể chỉ đổi thành `report_type=mission` mà không thay schema; trường này cũng khác ý nghĩa nguồn nhiệm vụ |
| Lưu và gửi nền | `ReportSubmitManager.SubmitRequest` → `PendingReport` → `ReportUploadWorker` → `CampaignRepository.submitFeedback` chưa mang mission identity | Thêm nhãn riêng ở UI chưa đủ, retry và khởi động lại app vẫn mất ngữ cảnh |
| Gắn thưởng | `_settle_mission_reward(report_id)` trong migration 044 tìm mission theo campaign/user, thời gian nộp, trạng thái và hạn **lúc duyệt** | Danh tính nhiệm vụ chỉ được suy ra khi xét thưởng, không lưu lúc nộp; không dùng kết quả nhận thưởng làm điều kiện nhận diện loại báo cáo |
| Nhiệm vụ hết hạn | `get_open_missions()` ở migration 044 cập nhật `expired`, nhưng query chỉ loại `deleted`, vẫn lấy các bản tạo trong 14 ngày | Hết hạn vẫn xuất hiện ở tab |
| UI và cache | `QuestsScreen` render danh sách nhận được; `Mission.isExpired` chỉ xét status; repository cache danh sách theo TTL | Thẻ tải trước hạn có thể tiếp tục hiện sau hạn nếu không refresh/tính lại theo thời gian |
| Màn chủ app | `get_campaign_reports_for_owner()` đang dựng từng trường JSON; client còn có đường select bảng trực tiếp | Thêm cột DB mà quên RPC sẽ làm chủ app không thấy metadata dù tester thấy |

### Probe đã chạy trong lượt nghiên cứu này

Lệnh từ thư mục gốc:

```powershell
node .tmp/mission-report-plan/probe.mjs
```

Probe nạp `supabase/full_schema_singapore.sql`, tạo bốn mission và gọi RPC dưới vai trò `authenticated` cho cả chủ và tester:

- Ba mission ban đầu `open`, `full`, `paused_by_creator` có `ends_at` trong quá khứ đều được chuyển `expired` nhưng **vẫn nằm trong response** cho cả hai tài khoản.
- Mission `full` còn hạn, tester đã completed vẫn được trả về với `has_completed=true`.
- Kiểm tra catalog xác nhận bảng `tester_reports` chưa có cột tên chứa `mission`.

Evidence: `.tmp/mission-report-plan/probe.mjs` và `results.json` (thư mục tạm Git-ignore). Probe đang assert hành vi hiện tại để tái hiện khoảng trống, **không phải kiểm thử PASS cho bản sửa**. Các nhận xét Android ở trên là đọc source, chưa phải kiểm thử giao diện.

## 3. Hành vi sản phẩm sau thay đổi

### 3.1. Người test gửi kết quả

Luồng: **Nhận nhiệm vụ → mở app liên kết → dùng form báo cáo hiện có → gửi → chờ duyệt**.

Form dùng tiêu đề **“Báo cáo nhiệm vụ”**, hiển thị tên nhiệm vụ, yêu cầu/mô tả và hạn nộp; giữ ảnh minh chứng, đánh giá, nhận xét, tiến trình gửi và nút thử lại hiện có. Khi màn chi tiết app được mở từ nơi khác, backend vẫn xác định nhiệm vụ tester đã nhận nếu đủ điều kiện; việc nhận diện không phụ thuộc duy nhất vào đường điều hướng.

Nhận được xác nhận server mới hiện “Đã gửi báo cáo nhiệm vụ — chờ duyệt”. Đang upload hoặc xếp hàng offline chưa được coi là nộp thành công. Nộp thành công cũng chưa đồng nghĩa nhiệm vụ đã được duyệt hoặc đã trả Credit.

Nếu mission đã hết hạn trong lúc mở form/upload: server trả lỗi nhiệm vụ hết hạn, UI thông báo và làm mới danh sách. Không âm thầm biến yêu cầu báo cáo nhiệm vụ thành báo cáo test thường. Nếu người dùng vẫn muốn gửi báo cáo test thường, đó là thao tác mới được chọn rõ ràng.

### 3.2. Nhận diện kết quả riêng

| Nơi hiển thị | Thay đổi |
|---|---|
| Form gửi và trạng thái đang gửi/thử lại | Tiêu đề “Báo cáo nhiệm vụ”, tên mission; giữ đúng ngữ cảnh khi gửi nền |
| Báo cáo hôm nay và lịch sử của tester trong chi tiết app | Nhãn **“Báo cáo nhiệm vụ”** + tên nhiệm vụ; trạng thái chờ duyệt/đã duyệt/từ chối hiển thị riêng |
| Danh sách báo cáo của chủ app và chi tiết từng báo cáo | Nhãn và tên mission; bộ lọc **Tất cả / Báo cáo test app / Báo cáo nhiệm vụ** trước các bộ lọc trạng thái đang có |
| Quản lý của admin/moderator dùng cùng báo cáo tester | Nhận metadata/nhãn giống chủ app; kiểm tra đúng màn dùng `TesterReport`, không mặc định `AdminReportsScreen` là màn này vì còn có báo cáo liên hệ/lỗi |
| Thông báo nhận báo cáo/duyệt/từ chối liên quan | Dùng tên “Báo cáo nhiệm vụ”, deep link về báo cáo/app còn truy cập được sau khi thẻ nhiệm vụ đã ẩn |

Một báo cáo nhiệm vụ xuất hiện một lần trong “Tất cả” và một lần khi lọc đúng nhóm; không tạo bản sao báo cáo. Nhãn loại báo cáo giữ nguyên kể cả bị từ chối, mission hết hạn hoặc không được trả thưởng. Nếu hiển thị tiền thưởng, tách thưởng test thường và thưởng nhiệm vụ thực nhận từ settlement/log; không gộp kỳ vọng thưởng vào số Credit đã nhận.

Không tạo tab báo cáo cấp cao mới trong đợt này. Chức năng riêng được thể hiện bằng nhãn, thông tin mission và bộ lọc trong luồng hiện có. Chuỗi Việt/Anh được bổ sung đồng thời (`Mission report`).

### 3.3. Quy tắc danh sách “Nhiệm vụ app”

| Trạng thái và thời gian | Hiển thị | Hành động |
|---|---|---|
| `open`, `ends_at > server_now` | Có | Người chưa nhận được nhận; tester đã nhận được tiếp tục |
| `full`, còn hạn | Có | Không nhận mới; tester đã nhận vẫn được đi test |
| `paused_by_creator`, còn hạn | Giữ theo hiện hành | Hiện đang tạm dừng, không tiếp tục nộp nhiệm vụ |
| Tester completed, mission vẫn còn hạn | Giữ thẻ đến hạn | Hiện “Đã hoàn thành”, không cho nhận thưởng lần nữa |
| Bất kỳ trạng thái nào có `ends_at <= server_now` | **Không** | Chặn nhận mới/nộp nhiệm vụ; có thể xem báo cáo đã gửi ở lịch sử |
| `expired`, `deleted`, `stopped_no_credits` dù timestamp còn hạn | Không | Theo dõi qua quản lý/lịch sử phù hợp |

Không dùng điều kiện “đủ người” để coi nhiệm vụ đã kết thúc. Không cho `is_mine` hoặc `has_joined` vượt qua bộ lọc hết hạn.

## 4. Thiết kế dữ liệu và backend đề xuất

### 4.1. Lưu liên kết khi nộp, tách khỏi `report_type`

Giữ bảng `tester_reports` và các loại nội dung `report_type` hiện có. Bổ sung metadata do server quyết định:

- `submission_context`: `app_test` hoặc `mission`; cột mới nullable cho dữ liệu lịch sử chưa đủ chứng cứ phân loại.
- `mission_id`, `mission_participant_id`: liên kết chính xác tới nhiệm vụ và lượt nhận.
- Snapshot tối thiểu: tên nhiệm vụ, hạn và mức thưởng nhiệm vụ tại lần nộp hợp lệ, để lịch sử không đổi theo lần chỉnh/sự kiện sau này.
- `mission_submitted_at`: timestamp server nhận lần nộp hợp lệ cho mission; không lấy giờ máy hoặc thời điểm người dùng bấm gửi offline.

Quy tắc ghi: báo cáo mới có context rõ ràng; context `mission` phải được server kiểm tra đúng mission/participant/campaign/user trước khi gắn. RLS/quyền cột hoặc trigger phải chặn client tự gắn/sửa metadata qua đường ghi bảng trực tiếp. Khi xóa cứng, không cascade từ FK mission để xóa riêng report ngoài chính sách retention hiện hành; dùng `ON DELETE SET NULL` cho liên kết phù hợp và giữ snapshot/context để không mất nhãn.

Context là loại báo cáo; `status` là kết quả duyệt; trạng thái settlement là kết quả trả thưởng. Ba thông tin này không thay thế nhau.

### 4.2. Contract nộp báo cáo

Mở rộng RPC submit bằng tham số mission tùy chọn hoặc RPC phiên bản mới rõ ràng; lựa chọn cuối cùng phải tránh overload mơ hồ của PostgREST. Giữ endpoint tương thích cho app cũ, cùng đi qua một hàm xử lý chung.

Thứ tự xử lý đề xuất:

1. Xác thực và tìm receipt theo `client_submit_id` trước. Với request đã commit, trả kết quả cũ cùng metadata, kể cả retry sau hạn; kiểm tra payload/campaign/context không bị đổi trái với receipt.
2. Với request mới, khóa theo quy ước hiện có, đọc report ngày hiện tại và khóa/kiểm tra mission + participant. Thống nhất thứ tự khóa giữa submit, join, pause/delete và settlement để tránh deadlock.
3. Nếu client chỉ rõ `mission_id`, xác minh đúng campaign, tester đã nhận, chưa completed, còn hạn và đang `open/full`. Sai điều kiện phải trả lỗi có mã, không bỏ mission rồi submit thường.
4. Nếu client không chỉ rõ mission, server tự phân loại **lần nộp mới hợp lệ** khi có đúng một nhiệm vụ tester đã nhận còn đủ điều kiện. Cách này giữ hành vi hiện tại khi tester vào từ Khám phá hoặc app cũ. Không chọn “mission mới nhất” nếu dữ liệu có xung đột; trả lỗi/ghi nhận để xử lý.
5. Ghi báo cáo và metadata trong cùng transaction với cơ chế check-in/credit hiện có. Trả về context, mission identity/snapshot trong receipt; phát thông báo dựa trên dữ liệu đã lưu.

Biên cần xử lý rõ, do RPC hiện dùng lại report cùng ngày:

- Report đã `approved` trước khi nhận nhiệm vụ: chỉ bổ sung nội dung theo luồng cũ; không đổi nó thành báo cáo nhiệm vụ và không tạo thưởng nhiệm vụ từ công việc cũ. UI thông báo chưa thể dùng báo cáo đó cho nhiệm vụ mới.
- Report đang pending từ trước lúc join: không gắn hồi tố chỉ vì tester nhận nhiệm vụ sau đó. Giữ `created_at`/nguồn ban đầu khi chỉnh pending, không dùng thời gian chỉnh comment để hợp thức hóa.
- Report bị từ chối rồi nộp lại: kiểm tra lại điều kiện lúc nộp lại, lưu thời gian nộp mới phù hợp; nếu đã gắn mission thì chỉ tiếp tục cùng identity trong đợt này. Không chuyển một report/job sang mission khác hoặc bỏ nhãn lịch sử; trường hợp xung đột báo rõ và không lách giới hạn báo cáo/ngày.
- Report đã dùng trả thưởng không bao giờ được gắn cho mission khác. Report nhiệm vụ cùng ngày vẫn tính một báo cáo/check-in; không nhân đôi thưởng test thông thường.

### 4.3. Settlement và chính sách thời hạn

Đổi `_settle_mission_reward` sang dùng identity đã lưu trên báo cáo cho dữ liệu mới. Outbox và retry phải giữ đúng report/mission/participant, không tìm nhiệm vụ khác sau khi mission cũ kết thúc. Giữ tính nguyên tử của trừ/cộng Credit, log hai bên và chuyển participant completed; giữ idempotency hiện có.

**Điểm cần nhìn thấy khi triển khai:** source hiện chỉ trả thưởng mission nếu còn hạn tại thời điểm duyệt/retry. Vì vậy báo cáo nộp sát hạn rồi được duyệt sau hạn có thể vẫn là **Báo cáo nhiệm vụ** nhưng không nhận thưởng nhiệm vụ. Kế hoạch này giữ quy tắc tiền thưởng hiện hành; việc ẩn thẻ không được tự đổi quy tắc đó. UI không được hứa chắc thưởng chỉ vì nộp thành công. Nếu muốn chuyển sang “nộp đúng hạn thì được xét thưởng dù duyệt sau hạn”, cần đặc tả thay đổi settlement/outbox và kiểm thử riêng trước triển khai; không đưa ngầm vào thay đổi nhãn/ẩn thẻ.

### 4.4. Truy vấn danh sách và truy vấn báo cáo

- Migration mới sau số cuối thực tế của repo (hiện là 047; dự kiến 048, phải kiểm tra lại trước tạo). Không sửa lại lịch sử migration 039/044/045.
- `get_open_missions`: lọc `ends_at > now()` và whitelist `open/full/paused_by_creator` **trước ORDER BY/LIMIT**. Đồng bộ expired vẫn chạy để quản lý trạng thái, nhưng kết quả đúng không phụ thuộc cron đã chạy.
- Bỏ việc dùng cửa sổ `created_at` 14 ngày làm tiêu chí sống/chết; dùng hạn và trạng thái. Giữ thứ tự xác định, ưu tiên cá nhân và giới hạn hiện hành; không tuyên bố đã giải quyết phân trang >100.
- Trả `server_now` kèm danh sách để Android căn thời gian. Thiết kế field mới phải tương thích decoder app cũ.
- `get_campaign_reports_for_owner` và mọi dashboard/JSON có nhúng báo cáo phải trả metadata mới. Đồng bộ cả đường select trực tiếp; giữ nguyên kiểm tra owner/admin/moderator.
- Cập nhật `supabase/full_schema_singapore.sql` và xác minh bootstrap/upgrade cùng contract.

### 4.5. Dữ liệu cũ

- Không gắn toàn bộ báo cáo cũ theo campaign hoặc tên app.
- Chỉ backfill khi có chứng cứ định danh đủ chắc, ưu tiên `mission_settlement_jobs` với kiểm tra report, campaign, user và thời điểm/lịch sử tương ứng. Không chỉ suy từ `has_completed=true`.
- Báo cáo chưa xác định để `submission_context=null`; giữ nhãn báo cáo cũ, không khẳng định lịch sử đó đều là test thường. Bộ lọc có thể đưa nhóm này vào báo cáo chung/test app với ghi chú phân loại cũ trong đặc tả kỹ thuật.
- Không trả lại thưởng, sửa số dư hoặc tự hoàn thành participant trong migration phân loại. Có preflight và thống kê số dòng backfill/không phân loại được.

## 5. Kế hoạch Android

| Nhóm file | Thay đổi dự kiến |
|---|---|
| `QuestsScreen.kt`, `HomeScreen.kt`, `NavGraph.kt` | Truyền cả campaignId và missionId qua callback/route tùy chọn; route thường vẫn hoạt động |
| `CampaignDetailViewModel.kt`, `CampaignDetailScreen.kt` | Tải ngữ cảnh nhiệm vụ đã được server xác minh; tiêu đề/form/receipt/lịch sử có nhãn; xử lý deadline và lỗi hợp lệ |
| `Models.kt`, `CampaignRepository.kt` | DTO/params/result mới, lấy metadata ở mọi đường đọc; xử lý khả năng backend hỗ trợ |
| `ReportSubmitManager.kt`, `PendingReportStore.kt`, `ReportUploadWorker.kt` | Lưu bền mission identity/context trong request, trạng thái/event và receipt; truyền nguyên vẹn qua upload, retry, restart và đổi phiên |
| `OwnerAppDetailScreen.kt` và ViewModel liên quan | Nhãn, tên mission, bộ lọc loại báo cáo kết hợp trạng thái; không tính trùng tổng số |
| `HomeViewModel.kt`, repository cache mission | Lọc theo deadline, invalidation khi submit/duyệt/mission mutation; chặn response cũ làm sống lại thẻ |
| `values/strings.xml`, `values-en/strings.xml`, thông báo liên quan | Chuỗi “Báo cáo nhiệm vụ”, hạn nộp, lỗi và trạng thái nhất quán |

Thời gian/cache:

1. Dùng `server_now` và mốc monotonic của thiết bị để ước lượng thời gian server cho snapshot hiện hành; không phụ thuộc duy nhất giờ tường trên máy.
2. Lọc deadline khi nhận response và khi đọc cache; snapshot quá hạn không được hiện lại do TTL chưa hết.
3. Khi tab đang mở, đặt một timer lifecycle-aware tới hạn gần nhất, cập nhật danh sách rồi tính hạn tiếp theo; không polling mạng từng giây. Khi app resume hoặc quay về tab, tính lại và refresh thích hợp.
4. Khi reboot/không có mốc thời gian đáng tin, tải lại server trước khi cho hành động nhiệm vụ; không coi dữ liệu offline là xác nhận còn hạn. Backend luôn kiểm tra cuối cùng.
5. Giữ sequence, mutation version, uid và session generation guards đang có. Sau submit/approve có liên quan, invalidate mission cache và refresh authoritative; deadline filtering vẫn áp dụng với response đang bay.
6. Request/receipt cũ chưa có field mới vẫn deserialize được. Với yêu cầu mission mới, nếu backend không hỗ trợ thì báo cần cập nhật hệ thống; **không fallback bỏ missionId**. Nhánh 7→6→5 tham số của báo cáo thường phải được giữ đúng phạm vi và các sửa latency/idempotency đang có.

## 6. Thứ tự triển khai và bàn giao

1. **Backend/data:** preflight, migration metadata và submit/receipt, RLS/ACL, liên kết settlement, query deadline, các endpoint đọc. Hoàn thành kiểm thử upgrade/bootstrap/idempotency.
2. **Android gửi/đọc:** DTO, route/context, lưu request nền, form và nhãn lịch sử/chủ app; bảo toàn báo cáo thường và các thay đổi gửi ảnh đang có.
3. **Android danh sách:** timer theo deadline, resume/cache filtering, invalidation và guard response cũ; hoàn thiện trạng thái rỗng/lỗi.
4. **Kiểm thử tích hợp:** các case ở mục 7, chạy lại bộ tests hiện hành; build Android và kiểm tra thiết bị. Chỉ ghi nhận những kiểm thử thực chạy.
5. **Triển khai:** backend tương thích trước, xác minh contract/ACL rồi phát hành Android. Theo dõi lỗi submit/context conflict và settlement; chưa triển khai trong lượt lập kế hoạch này.

Rollback nên tắt phần giao diện mới hoặc quay về app tương thích; không xóa metadata báo cáo đã ghi, không gỡ bảng/cột khi còn client mới và không đảo giao dịch Credit đã thực hiện.

## 7. Tiêu chí nghiệm thu

| Nhóm | Tình huống bắt buộc | Kết quả cần đạt |
|---|---|---|
| Nhận diện | Join A → mở từ thẻ → gửi | Một report lưu đúng mission/participant; tester và chủ thấy “Báo cáo nhiệm vụ” ngay khi pending |
| Đường vào khác | Join A → mở app từ Khám phá → gửi mới hợp lệ | Server vẫn phân loại A; form và receipt nhất quán |
| Báo cáo thường | Không có nhiệm vụ đã nhận → gửi | Luồng test thường, upload/thumbnail/check-in/thưởng giữ đúng |
| Không hồi tố | Nộp trước join, approved trước join, chỉnh comment/ảnh pending | Không gắn báo cáo cũ hoặc nhận thưởng nhiệm vụ sai |
| Nộp lại | Reject → sửa ảnh → submit trong hạn, sau hạn, cùng ngày | Giữ đúng identity/thời gian, lỗi sau hạn rõ ràng, không nhân đôi credit |
| Quyền | Mission/campaign/user không khớp, tester chưa nhận, giả mạo field qua bảng | Bị chặn; test dưới authenticated/anon/owner/admin/moderator phù hợp |
| Gửi nền | Kill process, mất response sau commit, retry qua deadline, đổi tài khoản | Giữ context; replay receipt thành công; không gửi bằng sai user hoặc trả thưởng hai lần |
| Deadline backend | `ends_at` trước, bằng, sau thời gian trong transaction kiểm thử | Trước/bằng hiện tại bị loại; open/full/paused đã quá hạn đều không xuất hiện |
| Deadline UI | Đứng ở tab qua hạn; cache còn TTL; nền → resume; response cũ về muộn | Thẻ biến mất, không cần kéo refresh và không hiện lại từ dữ liệu cũ |
| Phân biệt trạng thái | Full còn hạn, completed riêng, paused còn hạn | Full vẫn đi test cho người đã nhận; completed không kết thúc mission của người khác; paused theo chính sách |
| Bảo toàn lịch sử | Mission đã ẩn/hết hạn/xóa mềm | Báo cáo vẫn có nhãn/tên snapshot, xem và duyệt theo quyền; không mất lịch sử |
| Trả thưởng | Duyệt tay/auto/retry đua nhau, lỗi ghi log, duyệt sau hạn | Đúng một lần, đúng identity; sau hạn giữ chính sách đã đặc tả, không đổi nhãn báo cáo |
| Phân nhóm | Bộ lọc loại + pending/approved/rejected + tổng | Không lặp, không làm sai count, nhận đầy đủ metadata từ RPC và select trực tiếp |
| Nâng cấp | Baseline 047 có dữ liệu, bootstrap mới, app/request cũ | Không mất dữ liệu, không overload RPC mơ hồ, không fallback làm mất mission context |

Các kiểm thử dự kiến sau triển khai: runner DB mới cho tính năng + bộ `supabase/tests` hiện hành; unit tests qua ViewModel/repository/worker thực với fake clock và response điều khiển được; `:app:testDebugUnitTest`, `:app:assembleDebug` và kiểm tra UI trên thiết bị/emulator. Không dùng test tự sao chép nhánh production hoặc chỉ gán DTO rồi assert làm bằng chứng chính.

## 8. Kết quả mong đợi

Tester vẫn thao tác trên luồng báo cáo quen thuộc. Mỗi kết quả nhiệm vụ có danh tính lưu tại server và nhãn **“Báo cáo nhiệm vụ”** xuyên suốt gửi, duyệt, lịch sử. Tab **“Nhiệm vụ app”** chỉ còn nhiệm vụ phù hợp thời hạn; đến `ends_at` thẻ được ẩn nhưng báo cáo và lịch sử liên quan vẫn còn theo chính sách lưu trữ hiện hành.
