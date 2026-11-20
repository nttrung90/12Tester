# Kế hoạch sửa chồng lấn và kẹt gửi báo cáo — 29/10/2026

## 1. Kết luận và phạm vi

**Có chồng lấn giữa báo cáo test thường và báo cáo nhiệm vụ app ở phần chọn ngữ cảnh, trạng thái gửi và thao tác cập nhật.** Đã tái hiện bốn trường hợp Android và đối chiếu database cục bộ. Không đủ bằng chứng để kết luận mọi ca kẹt trên điện thoại đều do cùng nguyên nhân; cần tách lỗi nghiệp vụ, phản hồi bị mất và công việc đang chờ Android/mạng.

Lượt này chỉ rà mã, chạy unit test/probe với dữ liệu giả và lập kế hoạch. **Chưa sửa mã sản phẩm, chưa deploy, không build APK.** Giữ phiên bản `1.0.2`, `versionCode=22`. Workspace còn nhiều thay đổi của các công việc khác; khi triển khai phải giữ nguyên chúng.

Hai luồng dùng chung `tester_reports`, upload và WorkManager là thiết kế đã có. Backend khóa và tìm báo cáo theo `(user, campaign, ngày Việt Nam)`; metadata `submission_context`, `mission_id` phân biệt loại báo cáo. Báo cáo nhiệm vụ có thể đồng thời được ghi nhận một lần cho chỉ tiêu test thường; phần thưởng nhiệm vụ có settlement riêng. Không coi việc dùng chung đường truyền hoặc được tính chỉ tiêu một lần là lỗi.

**Không sửa bằng cách chỉ tách thành hai worker/bảng hoặc đổi khóa chống trùng thành `(campaign, mission)` rồi cho gửi đồng thời.** Cách đó không giải quyết hợp đồng một báo cáo/ngày của backend và có thể tạo race hoặc thưởng trùng.

## 2. Các phát hiện

### R01 — P1: Báo cáo thường đang pending bị client gắn sang nhiệm vụ app

- Vị trí: `CampaignDetailViewModel.kt:483–528`, `:911–974`; backend `049_mission_report_hardening.sql:489–494`.
- Tình huống: tester đã nộp báo cáo thường, sau đó nhận nhiệm vụ của cùng app, mở lại báo cáo để sửa.
- `resolveMissionContext()` thấy một mission đã nhận nên đặt `isMissionReport=true` và `intendedMissionId` theo mission, dù `myTodayReport` vẫn là `app_test`. `startSubmit()` vì thế gửi kèm mission ID.
- **Tái hiện Android:** request bị xếp hàng dưới loại mission thay vì giữ loại báo cáo cũ.
- **Đối chiếu DB qua RPC `join_mission` thật trên dữ liệu giả:** backend từ chối request chuyển loại bằng `CANNOT_CONVERT_EXISTING_PENDING_REPORT`. Nhận nhiệm vụ sau khi có báo cáo thường hiện vẫn được phép.
- Đây là lỗi hợp đồng client/backend. Thử lại cùng payload không giải quyết được.

### R02 — P1: Ý định gửi nhiệm vụ bị đổi thành sửa nhận xét thường khi hôm nay đã có báo cáo approved

- Vị trí: `CampaignDetailViewModel.kt:908`, `:963–975`.
- Tình huống: mở từ nhiệm vụ M, nhưng báo cáo hôm nay của app đã được duyệt với loại thường.
- `alreadyRewarded=true` làm `submitMissionId=null` và bỏ ảnh đang chọn; manager nhận request thường. UI vẫn có thể mang nhãn nhiệm vụ từ ngữ cảnh trước đó.
- **Tái hiện Android:** `initialMissionId` có giá trị nhưng `SubmitState.isMissionReport=false`.
- Backend có quy tắc chặn chuyển báo cáo approved thường thành mission nếu được gửi đúng ý định. Client hiện né quy tắc đó bằng cách đổi thành một thao tác khác.
- Cần báo rõ xung đột trước khi enqueue. Nếu người dùng muốn bổ sung nhận xét thường, đó phải là thao tác riêng được nhận diện rõ, không tự đổi yêu cầu nộp nhiệm vụ thành thao tác này.

### R03 — P1: Tải nhiệm vụ mới nhất của app thay cho đúng nhiệm vụ của báo cáo

- Vị trí: `CampaignRepository.kt:2456`; `040_mission_management.sql:212–275`; `CampaignDetailViewModel.kt:483–528`.
- `get_campaign_mission(p_campaign_id)` lấy mission mới nhất chưa xóa bằng `ORDER BY created_at DESC LIMIT 1`. API này ban đầu phục vụ màn quản lý app, không nhận mission ID.
- ViewModel giữ `initialMissionId`/mission ID của báo cáo cũ nhưng vẫn lấy tên, hạn và `hasJoined` từ API trên, không kiểm tra hai ID trùng nhau.
- **Tái hiện Android:** `intendedMissionId=mission-old` trong khi `activeMission.id=mission-new`.
- Trường hợp hợp lệ cần kiểm tra là báo cáo của nhiệm vụ cũ đã kết thúc và app có nhiệm vụ mới; không giả định có hai mission đang active cùng lúc, vì migration 044 đã có unique index ngăn điều đó.
- Nguy cơ: hiển thị sai tên, dùng hạn/trạng thái tham gia sai nhiệm vụ và chặn thao tác của báo cáo cũ.

### R04 — P1: Bổ sung nhận xét cho báo cáo mission đã duyệt vẫn phụ thuộc request tải mission hiện hành

- Vị trí: `CampaignDetailViewModel.kt:914–920`.
- Backend 049 cho phép sửa nhận xét của approved report, giữ nguyên ảnh/rating/mission và không phát sinh thưởng; không cần participant tiếp tục ở trạng thái joined.
- Client kiểm tra lỗi tải mission trước khi phân biệt thao tác bổ sung nhận xét. Khi API mission lỗi 500, không request nào được enqueue, dù báo cáo approved đã có đủ danh tính.
- **Tái hiện Android:** `manager.state == null`, UI có `submitError` sau khi gọi gửi nhận xét hợp lệ.
- Không bỏ kiểm tra hạn cho nộp minh chứng mới/pending/rejected. Chỉ tách đúng nhánh bổ sung comment approved theo hợp đồng backend hiện có.

### R05 — P1: Form đóng dù yêu cầu chưa được nhận; lỗi kiểm tra đầu vào không được hiển thị tại nơi người dùng đang thao tác

- Vị trí: `CampaignDetailScreen.kt:825–840`, `:301–386`, `:2854–2866`; `CampaignDetailViewModel.kt:900–948`.
- Callback hiện gọi `startSubmit()` rồi luôn `showReportDialog=false`.
- `startSubmit()` có nhiều nhánh trả về sớm: đang gửi, mission hết hạn, lỗi ngữ cảnh, chưa nhận mission, thiếu ảnh nộp lại. Một số nhánh chỉ đặt `submitError`; nhánh đang gửi chỉ return.
- `submitError` chỉ được render trong dialog gợi ý app kế tiếp khi stage là FAILED. Khi bị chặn trước enqueue, dialog đó chưa mở và stage chưa FAILED, nên lý do có thể không xuất hiện.
- Nút nộp trong form cũng chưa xét `submitStage` đang SENDING/RETRYING.
- **Xác nhận qua source**, chưa chạy kiểm thử Compose hay thiết bị cho thao tác đóng form. Kết quả phù hợp với cảm giác “bấm gửi nhưng không có gì xảy ra”; không được gọi đây là upload treo đã đo được.

### R06 — P1: Trạng thái theo campaign chưa phân biệt ý định gửi và chủ sở hữu bản nháp

- Vị trí: `ReportSubmitManager.kt:108–128`, `:201–242`; `CampaignDetailViewModel.kt:234–291`.
- Màn thường và màn mission có ViewModel key riêng, nhưng cùng subscribe `stateForCampaign(campaignId)`.
- Collector chưa đối chiếu request ID, mission ID hoặc loại thao tác trước khi áp SENDING/DONE. DONE của request trước có thể kích hoạt xóa ảnh và nội dung đang có trong ViewModel khác của cùng campaign.
- `submit()`/`retry()` trả `Unit`, bỏ qua khi đang có request của campaign đó. UI không biết request mới bị bỏ qua hay đang theo dõi một bài đã tồn tại.
- **Xác nhận đường mã; cần bổ sung probe hai ViewModel và kiểm thử UI trước khi kết luận mức độ tái hiện trên thiết bị.** Khóa chung campaign vẫn cần cho tuần tự hóa; vấn đề là thông báo và liên kết thao tác/bản nháp không đủ rõ.

### R07 — P1: Danh tính thao tác đang gắn với ảnh; dấu idempotency cũ bị ghi đè trên report

- Android: `SubmitRequest.clientSubmitId` mặc định lấy `preparedScreenshot.uploadId` (`ReportSubmitManager.kt:79`). Hai thao tác có nội dung khác nhưng dùng cùng prepared image có thể dùng chung mã yêu cầu.
- Backend: chỉ tìm `client_submit_id` hiện tại trên `tester_reports` (`049:296–326`); các nhánh sửa báo cáo ghi đè cột này (`:411`, `:654`, `:709`, `:744`). Không có ledger giữ mọi yêu cầu đã commit.
- **Probe D02 trên DB cục bộ:** gửi A với nội dung “first payload” → gửi B cập nhật “newer payload” → replay A. Nội dung bị lùi về “first payload” vì mã A không còn được lưu trên report.
- **Probe D03:** gửi cùng mã A với nội dung khác vẫn nhận `success=true`, nhưng DB giữ nội dung cũ. Riêng hành vi replay cùng ID là phù hợp với giả định payload bất biến của idempotency; lỗi phát sinh khi client không đảm bảo giả định đó. Chưa gọi đây là một lỗi server độc lập hay đã tái hiện toàn chuỗi sửa bản nháp trên UI.
- Cần tách ID upload khỏi ID thao tác, đóng băng payload của retry, và lưu receipt theo từng thao tác để request cũ không thể ghi đè cập nhật mới.

### R08 — P2: Chưa phân biệt đang chờ lịch/mạng với đang truyền dữ liệu; thiếu bằng chứng cho ca kẹt thực tế

- `ReportWorkState` gộp ENQUEUED/BLOCKED/RUNNING thành SENDING/RETRYING. UI không biết đang chờ mạng, backoff, Android hay upload/RPC.
- Giới hạn hiện có: session 30 giây, upload chính 120 giây, thumbnail 5 giây, RPC 45 giây **trong một lần worker chạy**. Chúng không đặt thời hạn cho việc chờ WorkManager bắt đầu hoặc chờ có mạng.
- Worker dùng `runAttemptCount` để giới hạn retry. Cần đo thêm các lần stop/restart khi đổi mạng/chạy nền trước khi quyết định có tách bộ đếm lỗi mạng thực sự khỏi số lần worker khởi chạy hay không.
- Chưa có log trên máy bị ảnh hưởng, số đo p50/p95 hoặc đối chiếu APK/backend đang chạy. Đây là khoảng trống quan sát, chưa chứng minh Android scheduler là nguyên nhân duy nhất.

## 3. Quy tắc cần giữ khi sửa

1. Vẫn một báo cáo hằng ngày cho cùng user/campaign theo hợp đồng hiện tại; không tự cho hai lần thưởng/check-in vì mở hai đường vào.
2. Báo cáo đã lưu là nguồn xác định loại và mission identity khi sửa pending/rejected/approved. Không lấy mission mới nhất để đổi danh tính lịch sử.
3. Với bài nộp mới, tiếp tục tự nhận diện mission duy nhất đủ điều kiện khi mở app từ nơi khác theo thiết kế trước. Chốt ngữ cảnh trước khi enqueue và hiển thị cho tester. Không dùng tự nhận diện này để chuyển loại báo cáo đã tồn tại.
4. Ý định mission tường minh không được tự hạ xuống thường. Nếu xung đột với báo cáo hôm nay, trả lý do rõ và giữ bản nháp.
5. `APPEND_COMMENT` cho approved giữ ảnh/rating/context, không kiểm tra lại điều kiện nộp mới và không phát sinh thưởng. `UPDATE_PENDING`/`RESUBMIT_REJECTED` vẫn kiểm tra đúng mission, trạng thái và hạn.
6. Retry cùng thao tác phải giữ nguyên ID và payload. Sửa payload là thao tác mới, dùng ID mới; mã ảnh có vòng đời riêng.
7. Replay đã commit không tạo mutation, thông báo, check-in hay thưởng lần hai; phải hoạt động cả sau khi hết hạn hoặc sang ngày mới.
8. Giữ các sửa expedited, timeout thumbnail, persist URL ảnh chính, cancellation, bảo vệ đổi tài khoản và fallback RPC idempotent đã có. Không tăng số lần retry để che lỗi ngữ cảnh.

## 4. Kế hoạch triển khai theo thứ tự

### Bước 1 — Giữ bằng chứng và thêm các ca thiếu vào bộ kiểm thử

- Đưa bốn probe Android vào test suite chính với cùng ý nghĩa assertions, chuẩn hóa fixture và cleanup.
- Thêm kiểm thử hai ViewModel cùng app nhưng khác đường vào: không lẫn request, không xóa bản nháp mới khi request cũ DONE, không phát thông báo thành công sai loại.
- Đưa replay A → cập nhật B → replay A vào DB suite. Thêm kiểm thử payload khác trên cùng request ID ở cả client và server.
- Ghi baseline APK thực tế trên máy lỗi, phiên bản schema/RPC, work ID, request ID, loại báo cáo và bước dừng. Không sửa khóa hay schema production chỉ để test xanh.

### Bước 2 — Chốt loại thao tác và tải đúng ngữ cảnh báo cáo

Các file chính: `CampaignDetailViewModel`, `CampaignRepository`, `Models`, `PendingReportStore` và RPC liên quan.

- Dùng model thao tác rõ ràng: `NEW_REPORT`, `UPDATE_PENDING`, `RESUBMIT_REJECTED`, `APPEND_COMMENT`; đi kèm `reportId`/phiên bản báo cáo nếu đang sửa, `submissionContext`, `missionId` và snapshot cần thiết.
- Ưu tiên dữ liệu báo cáo hiện có; nhánh nộp mới mới xét route/mission đủ điều kiện. Báo lỗi trước enqueue khi ý định mission xung đột với báo cáo thường đã tồn tại.
- Không dùng API “mission mới nhất của campaign” để xác minh mission cụ thể. Thêm RPC đọc ngữ cảnh tester theo mission/report ID, kiểm tra campaign và quyền; cho phép đọc trạng thái của mission cũ để giải thích lỗi, không dùng bộ lọc danh sách nhiệm vụ đang mở để xóa lịch sử.
- APPEND_COMMENT lấy danh tính từ report đã duyệt, không phụ thuộc một request đọc mission mới nhất. Các nhánh còn lại kiểm tra mission gốc ở server tại thời điểm ghi.
- Nếu mở rộng tham số RPC, giữ tương thích client cũ. Phân biệt rõ “thiếu trường vì legacy” với yêu cầu thường/mission tường minh; không fallback bỏ mission ID hoặc operation khi backend chưa hỗ trợ.

### Bước 3 — Sửa idempotency trước khi mở rộng retry/phục hồi

- Tạo `clientSubmitId` mới cho mỗi thao tác logic; `uploadId` chỉ định danh ảnh. Payload đã lưu xuống đĩa là snapshot bất biến của thao tác đó.
- Khi tester sửa bản nháp sau một lỗi, tạo thao tác mới. Nếu thao tác cũ có kết quả chưa chắc chắn, kiểm tra trạng thái/receipt đã commit trước khi cho mutation mới chạy; không tự REPLACE một bài có thể vừa được server ghi nhận.
- Thêm ledger receipt do server quản lý với khóa duy nhất `(user_id, client_submit_id)`, payload fingerprint và liên kết report/operation/context. Cùng ID cùng payload trả receipt; cùng ID khác payload trả lỗi danh tính rõ ràng.
- Giữ receipt của A sau khi B cập nhật report. Replay A chỉ đọc receipt, không ghi lại nội dung A vào report hiện tại. Xử lý idempotency và khóa trong giao dịch; kiểm thử nhiều kết nối trên PostgreSQL thật cho race cùng ID và hai cập nhật khác ID.
- Không cấp quyền client ghi trực tiếp ledger. Quy định retention và tương thích receipt cũ, không xóa dấu chống trùng khi vẫn còn khả năng client retry.

### Bước 4 — Sửa tiếp nhận yêu cầu, trạng thái theo request và giao diện khi bị chặn

- `startSubmit()`/manager trả kết quả có kiểu, ví dụ `Accepted(requestId)`, `AlreadyInFlight(existingRequestId)` hoặc `Blocked(code)`. Chỉ coi Accepted sau khi snapshot đã được lưu và việc enqueue đã được xác nhận; lỗi ghi đĩa/enqueue phải có đường xử lý rõ.
- Giữ form khi Blocked, hiển thị lỗi ngay trong form, giữ ảnh và nhận xét. Không đóng form vô điều kiện sau `startSubmit()`.
- Với AlreadyInFlight, mở trạng thái của bài đang có và thông báo rõ loại bài; không trình bày request mới như đã được nhận.
- Tách **khóa tuần tự theo tài nguyên** `(user,campaign,ngày)` khỏi **trạng thái thao tác** theo request ID và danh tính báo cáo/mission. Không nới khóa chỉ vì hai đường vào khác nhau.
- ViewModel chỉ cập nhật/xóa bản nháp do đúng request tạo ra; phiên bản bản nháp thay đổi trong lúc gửi không bị completion cũ xóa. DONE và nhãn nhiệm vụ phải dựa trên receipt server đã kiểm tra, không suy ra từ mission đang hiển thị.
- Nút thử lại chọn request ID cụ thể. Cùng thao tác retry giữ ảnh/ID/context; lỗi yêu cầu chọn ảnh mới hoặc sửa điều kiện phải đưa về form, không lặp retry payload không hợp lệ.

### Bước 5 — Làm rõ trạng thái chờ và đường phục hồi khi chạy nền

- Hiển thị các bước có ý nghĩa: chuẩn bị → chờ mạng/lịch → tải ảnh → nộp server → chờ thử lại → hoàn tất/cần xử lý. Dùng `WorkInfo`, progress theo bước và thời điểm bắt đầu/tiến triển, tránh spinner chung không có lý do.
- Lưu mã lỗi có cấu trúc: lỗi nghiệp vụ/ngữ cảnh, thiếu phiên đăng nhập, cần ảnh mới, lỗi cấu hình, lỗi mạng tạm thời. Ánh xạ đầy đủ các mã chuyển loại/danh tính nhiệm vụ sang tiếng Việt/Anh.
- Ghi duration và stop reason khi có, backoff, lần chạy, loại request và kết quả reconcile. Không log token, URL ảnh chứa token, nội dung nhận xét hoặc dữ liệu ảnh.
- Với trường hợp chưa biết server đã commit hay chưa, tra receipt theo đúng user/request thay vì tự tạo một bài mới. Phục hồi mọi request hợp lệ, không chỉ bài được chọn gần nhất; giữ đúng tài khoản khi logout/restart.
- Chỉ thay ngân sách timeout/retry sau khi có baseline. Mất mạng hoặc Android chưa cấp lịch phải được hiển thị là đang chờ, không tự báo thành công hay retry dồn.

### Bước 6 — Migration, nghiệm thu và phát hành

- Tạo migration mới sau số cao nhất lúc triển khai; hiện repo có tới **056**, nên không sửa riêng 049 rồi coi production đã nhận thay đổi. Đồng bộ full schema và test mirror theo migration mới.
- Kiểm tra backend hỗ trợ contract mới trước khi bật client dùng contract đó. Chỉ triển khai backend/đóng gói phát hành khi có yêu cầu tiếp theo; kế hoạch này không tự cấp lệnh deploy.
- Giữ version `1.0.2`/build `22` theo yêu cầu hiện tại. **Không chạy `assembleDebug`, `assembleRelease`, `bundleRelease` hoặc tạo APK trong đợt sửa nếu người dùng chưa thay đổi chỉ dẫn.** Kiểm thử unit/DB và kiểm tra mã không cần đóng gói APK.

## 5. Ma trận nghiệm thu bắt buộc

| Nhóm | Ca kiểm tra | Kết quả cần đạt |
|---|---|---|
| Phân loại | Chưa có báo cáo, nhận một mission hợp lệ, mở từ nhiệm vụ hoặc trang app | Chốt đúng mission theo thiết kế tự nhận diện, server receipt đúng danh tính |
| Báo cáo thường | Đã có pending/rejected thường rồi nhận mission cùng app | Sửa/nộp lại đúng loại thường; yêu cầu chuyển loại bị giải thích rõ, không loop retry |
| Báo cáo approved | Approved thường, mở từ mission để nộp | Không âm thầm biến thành comment thường; giữ draft và báo xung đột |
| Lịch sử nhiệm vụ | Báo cáo M1, app có nhiệm vụ mới M2 | Không lấy tên/hạn/participant M2 để xử lý report M1 |
| Bổ sung nhận xét | Approved mission, participant completed, API mission lỗi/mission kết thúc | Comment hợp lệ gửi được; ảnh/rating/context và thưởng không đổi |
| Hạn nộp | Pending/rejected, mission paused/deleted/expired hoặc vừa chạm hạn | Server chặn mutation không hợp lệ; replay đã commit vẫn được nhận diện |
| Chồng lấn UI | Hai ViewModel thường/mission cùng app; hai app khác nhau; gửi A rồi B rồi quay lại | Đúng request/nhãn; không xóa nháp khác; khóa/AlreadyInFlight có thông báo |
| Idempotency | Response A mất, B cập nhật, A retry; cùng ID khác payload; retry sang ngày VN mới | Không ghi đè B, không báo đã nhận payload chưa ghi, không cộng trùng |
| Chạy nền | Chuyển app, tắt màn hình, đổi mạng, offline dài, process restart | Có lý do chờ, giữ dữ liệu, thử lại đúng ID; không tạo bài mới để thoát kẹt |
| Phiên và lưu trữ | Cold start, refresh session lỗi, đổi tài khoản, thiếu file/lỗi ghi đĩa | Không gửi nhầm tài khoản; không đóng form mất draft; lỗi có hướng xử lý |
| Kinh tế và cache | Báo cáo thường/mission, duyệt/reject/auto duyệt | Một lần tính chỉ tiêu/thưởng test theo luật; settlement mission không nhân đôi; cache cập nhật |

Điều kiện hoàn tất: các probe phát hiện lỗi đổi sang PASS với ý nghĩa assertions được giữ; các test báo cáo hiện có vẫn qua; có kiểm thử Compose cho form bị chặn; có kiểm chứng PostgreSQL nhiều kết nối cho ledger và log ca thật trên thiết bị trước khi khẳng định hết kẹt. Nếu chưa có thiết bị thì ghi rõ phần chưa xác minh, không thay thế bằng số unit test.

## 6. Kiểm chứng đã chạy trong lượt lập kế hoạch

| Kiểm tra | Kết quả |
|---|---|
| Android `ReportOverlapProbeTest` mới | **4/4 FAIL theo bất biến mong muốn**, tái hiện R01–R04; 0 lỗi compiler/runtime fixture |
| DB D01: thường pending → `join_mission` → gửi có mission ID | Backend trả đúng `CANNOT_CONVERT_EXISTING_PENDING_REPORT`, xác nhận xung đột với client R01 |
| DB D02: A → B → replay A | FAIL, nội dung bị lùi về payload A |
| DB D03: cùng ID, payload khác | Xác nhận server trả success với payload cũ; minh họa rủi ro client tái sử dụng ID ảnh |
| Năm suite báo cáo Android hiện có | **26/26 PASS**, 0 fail/error |
| APK / deploy / sửa mã sản phẩm | Không thực hiện |

26 test hiện có gồm `ReportSubmitManagerIntegrationTest` (6), `ReportBackgroundSubmissionTest` (6), `MissionReportAndExpirationTest` (6), `ReportRpcCompatibilityTest` (5), `ReportSubmissionLatencyTest` (3). Chúng chưa phủ bốn tình huống audit mới, nên kết quả PASS trước đây không chứng minh hai luồng không chồng lấn.

Bằng chứng tại `.tmp/report-overlap-review-2026-10-29/`:

- `tests/com/ntt/crosstest/audit/ReportOverlapProbeTest.kt`, `review.init.gradle`, `android-probes.log`, `android-results.xml`.
- `database-probes.mjs`, `generate-db-probes.cjs`, `database-results.json`, `database-probes.log`: PostgreSQL nhúng PGlite, dữ liệu giả, không kết nối production.
- `existing-tests.log`, `existing-test-results/`: năm suite đối chiếu đã qua.

Lệnh audit Android, không build APK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat -I .tmp/report-overlap-review-2026-10-29/review.init.gradle :app:testDebugUnitTest --tests '*ReportOverlapProbeTest' --offline --no-daemon
node .tmp/report-overlap-review-2026-10-29/database-probes.mjs
```

Các probe audit còn lỗi sẽ trả exit code khác 0 theo chủ đích để giữ bất biến cần sửa. Không đổi expected sang hành vi đang sai chỉ để làm xanh. Bốn probe Android chạy ViewModel/repository/manager với HTTP giả và worker được giữ lại, chưa phải kiểm thử gửi mạng thật hoặc kiểm thử bấm giao diện.
