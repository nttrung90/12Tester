# Kiểm tra lại và kế hoạch sửa tiếp báo cáo cho OpenCode — 29/10/2026

## 1. Kết luận

**Bản sửa đã giải quyết một phần, nhưng vẫn còn lỗi cần sửa trước khi nghiệm thu.** Toàn bộ 277 unit test hiện có qua, runner mới về ledger qua 5/5. Tuy nhiên, bốn ca audit bổ sung Android và hai ca database về nâng cấp dữ liệu đều tái hiện hành vi chưa đúng.

Lượt kiểm tra này chỉ đọc mã, chạy unit test/DB cục bộ và lập kế hoạch. Không sửa mã sản phẩm, không deploy, không build APK. Version vẫn `1.0.2`, build `22`. Hash sáu file sản phẩm được kiểm tra giữ nguyên giữa đầu và cuối lượt audit.

Unit test có biên dịch mã cần dùng nhưng không cần đóng gói APK. Kiểm tra thao tác thật như chuyển app, khóa màn hình, Android thu hồi process cần cài APK mới trên thiết bị; phần đó chưa thực hiện trong lượt này.

## 2. Những phần đã sửa đúng và cần bảo toàn

- Báo cáo thường pending không còn tự bị gán mission chỉ vì tester vừa nhận nhiệm vụ.
- Ý định mission khi hôm nay đã có báo cáo thường approved được chặn bằng lỗi rõ ràng.
- Repository/API hỗ trợ đọc mission theo ID; nhánh approved mission cho phép gửi comment khi tải mission bị lỗi.
- Form chỉ đóng khi `startSubmit()` trả true; lỗi preflight được hiển thị trong form và nút nộp xét trạng thái đang gửi.
- ID yêu cầu mới dùng UUID riêng, không mặc định dùng ID ảnh.
- Ledger 057 giữ được receipt cho các yêu cầu tạo sau khi đã có ledger; ca A → B → replay A trên dữ liệu mới không ghi đè B, cùng ID khác payload bị chặn, không cộng trùng Credit trong các ca tuần tự hiện có.
- Full schema và migration 057 khớp nhau sau khi bỏ comment/khoảng trắng.

Không bỏ các sửa expedited, lưu URL ảnh trước thumbnail, giới hạn timeout, cancellation và bảo vệ tài khoản đã có.

## 3. Các lỗi còn lại đã tái hiện

### N01 — P1: Completion vẫn xóa nháp của ViewModel khác và nháp mới trong cùng ViewModel

**Vị trí:** `CampaignDetailViewModel.kt:234–294`, đặc biệt `:270`.

```kotlin
val isMyRequest = lastSubmittedRequestId == null || submitState.requestId == lastSubmittedRequestId
```

ViewModel chưa gửi bài có `lastSubmittedRequestId=null`, nên bị coi là chủ mọi completion cùng campaign. Ngoài ra, dù request ID trùng, người dùng có thể đã sửa nội dung sau lúc chụp snapshot gửi; code chưa có revision của bản nháp.

**Probe Android qua WorkManager thật với worker được điều khiển hoàn tất:**

1. VM1 gửi, VM2 có nháp “unsent draft owned by VM2”. Hoàn tất VM1. Nháp VM2 bị thay bằng “old comment” từ server sau khi bị clear và refresh.
2. Một VM gửi “submitted draft”, sau đó sửa thành “new draft typed while previous request sends”. Hoàn tất request cũ. Nháp mới cũng bị thay bằng “old comment”.

Test hiện có `twoViewModelsForSameCampaignDoNotClearEachOthersDraftOnCompletion` chỉ gọi `startSubmit()` rồi assert; `HeldWorker` không bao giờ hoàn tất. Vì vậy tên test nói về completion nhưng test chưa đi qua nhánh lỗi.

**Yêu cầu sửa:**

- Completion chỉ được xóa nháp khi có request thuộc ViewModel đó, request ID khớp và revision nháp vẫn đúng snapshot đã gửi. `null` không phải wildcard nhận quyền xóa.
- Chỉ ghi nhận request ID đã thực sự được nhận; không để ID của yêu cầu bị `AlreadyInFlight`/Blocked thay thế chủ sở hữu hiện tại.
- Refresh sau completion vẫn cập nhật dữ liệu server nhưng không ghi đè các trường người dùng đã sửa trong nháp mới.
- Rà cả ảnh: `PendingReportStore.markCompleted()` hiện có thể xóa `preparedPath`. Nếu nháp mới còn dùng cùng ảnh prepared, chỉ sửa điều kiện clear comment là chưa đủ. Phân định quyền sở hữu ảnh pending và ảnh nháp; completion chỉ dọn tài nguyên của snapshot gửi, không làm nháp khác trỏ vào file đã xóa.

**Đạt khi:** hai probe completion qua với nguyên ý nghĩa assertions; thêm ca ảnh mới/ảnh dùng chung và sửa rating trong lúc gửi.

### N02 — P1: Trả Accepted trước khi lưu yêu cầu hoặc enqueue thành công

**Vị trí:** `ReportSubmitManager.kt:207–232`, `CampaignDetailViewModel.kt:1062–1119`.

Manager chạy `scope.launch { prepareAndEnqueue(request) }` rồi trả `Accepted` ngay. Kiểu kết quả đã được thêm nhưng chưa phản ánh kết quả tiếp nhận bền vững như kế hoạch yêu cầu.

**Probe:** dùng prepared screenshot trỏ vào file không tồn tại. `submit()` vẫn trả `Accepted(requestId=failed-persist)`; sau đó state FAILED và `PendingReportStore.read()` là null. Không có snapshot để worker hoặc nút retry đọc lại. UI đã được phép đóng form và mở dialog gửi trước khi biết lỗi.

**Yêu cầu sửa:**

- Có giai đoạn PREPARING/tiếp nhận, chụp snapshot và lưu bền vững trên scope manager; chỉ phát Accepted sau khi persist và xác nhận enqueue.
- Có thể dùng API suspend/Deferred hoặc admission event theo request; giữ upload/RPC thuộc WorkManager, không đưa việc gửi về scope bị hủy theo màn hình.
- Lỗi đọc ảnh/ghi đĩa/enqueue trả kết quả có kiểu và hướng xử lý đúng; giữ form và draft khi chưa tiếp nhận. Không đưa người dùng vào vòng “retry → pending_report_expired” với request chưa từng được lưu.
- Trong lúc tiếp nhận, chặn bấm trùng và xử lý việc rời màn mà không xóa ảnh trước khi manager nắm quyền sở hữu snapshot.

**Đạt khi:** probe thiếu ảnh không nhận Accepted; thêm lỗi ghi metadata, lỗi enqueue, logout/rời màn trong lúc persist và restart giữa persist/enqueue.

### N03 — P1: Mở mission mới nhưng âm thầm gửi cập nhật vào mission cũ của báo cáo

**Vị trí:** `CampaignDetailViewModel.kt:492–516`, `:1038–1056`.

Khi báo cáo hôm nay thuộc M1 nhưng route chỉ định M2, resolver ưu tiên `initialMissionId` để tải ngữ cảnh M2, trong khi `submitMissionId` lại ưu tiên mission ID M1 đã lưu trên report. Hai phần của request vẫn không cùng ý định.

**Probe:** `reportMission=mission-old`, `initialMission=mission-new`. `startSubmit()` trả true thay vì giải thích xung đột. Code payload sẽ gửi vào mission cũ. Nếu M1 đã hết hạn còn M2 đang mở, backend có thể từ chối request dù UI vừa kiểm tra trạng thái M2.

Không giả định M1 và M2 đồng thời active: ca hợp lệ cần xét là report M1 còn pending/approved trong ngày, M1 đã kết thúc, app có M2 mới.

**Yêu cầu sửa:**

- Khi route mission tường minh khác mission của report hiện có, chặn và giải thích xung đột trước enqueue. Không tự chuyển sang cập nhật M1.
- Nếu tester chọn xem/sửa report cũ, chuyển rõ sang thao tác gắn M1; mọi tên, hạn, participant và payload phải cùng ID đó.
- Rà đối xứng report thường **rejected** + route mission mới: hiện guard chuyển loại mới bao pending/approved. Không được âm thầm đổi yêu cầu mission thành nộp lại report thường sau khi chọn ảnh mới.
- Giữ chính sách một báo cáo/ngày; không giải quyết bằng cách nới khóa hoặc tạo hai lần thưởng.

**Đạt khi:** probe M1/M2 qua; ma trận pending/approved/rejected và app_test/mission không có trường hợp ý định UI khác loại/mission gửi server.

### N04 — P1: Nâng cấp từ báo cáo cũ sang ledger vẫn cho replay cũ ghi đè bản cập nhật mới

**Vị trí:** migration 057 `:195–257`, `:280`, các nhánh ghi `client_submit_id` và receipt.

**Probe DB dùng implementation thật:** tạo report A bằng RPC migration 049, xác nhận chưa có receipt → áp migration 057 → request B cập nhật → replay A. Kết quả report bị lùi về “legacy A payload”.

Ledger hiện chỉ bảo vệ A nếu A được tạo sau 057 hoặc A đã được replay/backfill trước B. Migration chưa bảo toàn mã A trên report cũ trước khi B ghi đè `client_submit_id`; nhánh legacy chỉ tìm mã hiện tại nên không nhận ra A nữa.

**Yêu cầu sửa:**

- Bảo toàn dấu yêu cầu legacy còn biết trước khi lần cập nhật mới thay cột `client_submit_id`, trong quá trình migration và/hoặc trong transaction mutation.
- Receipt legacy phải là đường đọc/reconcile, không cho request replay trở thành cập nhật mới chỉ vì report đã mang mã khác.
- Không giả định có thể khôi phục fingerprint gốc đầy đủ từ kết quả report: mặc định, chuẩn hóa và thao tác comment-only có thể đã làm mất thông tin đầu vào. Thiết kế rõ dạng receipt legacy và trường hợp không xác minh được payload.
- Với các ID đã bị ghi đè từ trước và không còn dữ liệu để nhận diện, ghi rõ giới hạn; dùng contract thao tác/report revision và hướng xử lý yêu cầu cũ không chắc chắn, không tự ghi lại payload cũ. Không tạo dữ liệu lịch sử giả hoặc chỉ sửa probe sang chấp nhận overwrite.
- Thêm test nâng cấp 049/056 → 057/bản sửa, không chỉ test database tạo mới từ full schema.

**Đạt khi:** probe legacy A → nâng cấp → B → replay A giữ nguyên B, không nhân đôi thưởng/check-in/thông báo; retry hợp lệ của client cũ có kết quả rõ và không làm mất dữ liệu.

### N05 — P2: Legacy fallback kiểm tra content nhưng bỏ qua ảnh/hash/rating

**Vị trí:** migration 057 `:213–255`, đặc biệt `:227`.

**Probe DB:** report A được tạo bằng RPC 049; áp 057; gửi lại mã A, giữ comment nhưng đổi screenshot URL, hash và rating. Không có lỗi mismatch, server trả success cho kết quả cũ và lưu fingerprint của payload mới vào ledger.

Điều này không sửa được bằng kiểm tra content đơn lẻ; người dùng/client có thể hiểu nhầm payload mới đã được nhận. Ledger còn bị gắn với fingerprint không mô tả thao tác đã thực hiện ban đầu.

**Yêu cầu sửa:**

- Xác minh các trường có thể xác minh của legacy request theo semantics đã chuẩn hóa; phần không đủ thông tin phải được đánh dấu legacy/không chắc chắn và xử lý rõ.
- Không chứng thực fingerprint tùy ý của một retry vào receipt legacy. Không báo đã ghi minh chứng mới khi chỉ trả kết quả cũ.
- Thêm biến thể URL/hash/rating/thumbnail/mission ID; kiểm tra các retry hợp lệ có trường tùy chọn bị bỏ qua bởi client cũ.

**Đạt khi:** probe payload legacy khác không còn silent success; đường retry hợp lệ vẫn không tạo mutation mới.

## 4. Khoảng trống còn lại cần xử lý hoặc chứng minh

### N06 — P2: Kiểm tra ledger chưa nguyên tử với cạnh tranh cùng request ID

Ledger được đọc trước khóa advisory theo user/campaign/ngày. Sau khi chờ khóa, code không kiểm tra lại receipt; cuối mutation dùng `ON CONFLICT ... DO NOTHING` rồi trả kết quả vừa tự tính.

Phân tích mã cho thấy hai request cùng ID có thể cùng vượt lần kiểm tra đầu; request đến sau có thể mutation rồi bỏ qua xung đột receipt. **Đây là rủi ro race từ source, chưa tái hiện bằng PostgreSQL nhiều kết nối trong lượt audit. PGlite tuần tự không thay thế phép kiểm tra này.**

Yêu cầu: khóa/đặt chỗ theo `(user_id, client_submit_id)` trước phần quyết định mutation, kiểm tra fingerprint sau khi giành quyền, chuẩn hóa thứ tự khóa với khóa campaign/day. Không dùng DO NOTHING như bằng chứng idempotency. Bổ sung test hai kết nối có barrier cho cùng ID/cùng payload, cùng ID/khác payload và khác campaign.

### N07 — P2: Bộ kiểm thử chuẩn chưa gọi runner ledger; test completion chưa thực sự kiểm tra completion

- `supabase/tests/package.json` vẫn không đăng ký `run-report-idempotency-ledger-test.mjs` trong `npm test`.
- Runner ledger mới chỉ có năm ca và bootstrap từ full schema, chưa bao upgrade legacy hay race.
- Test Android về completion đang giữ worker vô hạn và assert trước completion; test giữ form mới chỉ xác minh ViewModel, chưa xác minh Compose hiển thị/đóng form.

Yêu cầu: đăng ký runner mới và các ca nâng cấp vào cổng chuẩn; sửa fixture để thực sự phát completion, đợi hậu xử lý/refresh hoàn tất rồi assert; bổ sung kiểm thử UI có ý nghĩa. Không chỉ đổi tên test hoặc chèn sleep để làm xanh.

### N08 — P2: Phần quan sát ca kẹt nền chưa được hoàn thiện

`ReportWorkState` vẫn gộp ENQUEUED/BLOCKED/RUNNING vào SENDING/RETRYING. Worker chưa cung cấp progress theo bước cho UI; chưa có đường reconcile receipt server trong client. Model thao tác/report revision chưa được bổ sung vào request lưu bền vững.

Yêu cầu: tiếp tục mục R08/bước 5 của kế hoạch trước — phân biệt chuẩn bị, chờ mạng/lịch, upload, RPC, backoff và lỗi cần xử lý; ghi thời gian/stop reason có thể lấy được, không log token/URL ảnh/nội dung riêng tư. Dùng receipt để phân biệt timeout phản hồi với chưa commit. Không tăng retry hoặc kết luận hết kẹt chỉ vì 277 unit test qua.

## 5. Thứ tự sửa tiếp cho OpenCode

1. Giữ các probe audit còn đỏ và đăng ký đúng test: N01–N05. Bảo toàn mã của các công việc khác trong workspace.
2. Sửa N01/N02/N03 ở Android: ownership + draft revision, admission bền vững, guard ý định mission. Kiểm tra cả cleanup file và refresh.
3. Sửa N04/N05/N06 ở backend theo một thiết kế ledger tương thích nâng cấp; thêm contract version/revision nếu cần để không nhầm request cũ với mutation mới.
4. Hoàn thiện N07/N08: cổng test chuẩn, progress/reconcile và lỗi có hướng xử lý. Không gắn nhãn hoàn tất R01–R08 khi các mục này chưa được thực hiện hoặc chưa được loại trừ bằng bằng chứng.
5. Chạy probe lỗi → test báo cáo liên quan → toàn bộ unit test Android và npm test. Nếu có PostgreSQL thử nghiệm nhiều kết nối, chạy race test; nếu chưa có, ghi rõ phần chưa xác minh.
6. Tạo migration mới theo số tiếp theo khi triển khai nếu 057 đã được áp dụng ở môi trường nào; không chỉ sửa file 057 và coi server tự cập nhật. Đồng bộ full schema và giữ compatibility.
7. Giữ `versionName=1.0.2`, `versionCode=22`. Không tự build APK/AAB, deploy, commit/push. Bước cài APK mới và thử trên điện thoại thực hiện khi được yêu cầu; audit hiện tại chưa thực hiện bước đó.

## 6. Kết quả kiểm tra độc lập

| Kiểm tra | Kết quả |
|---|---|
| Toàn bộ unit test Android hiện có | **277/277**, 37 suites, 0 failure/error/skipped |
| `ReportOverlapTest` của bản sửa | **7/7** (tập con của 277), nhưng ca completion chưa hoàn tất worker |
| Runner ledger mới | **5/5** |
| `npm --prefix supabase/tests test` | Thành công; chưa bao runner ledger mới |
| Bốn probe Android bổ sung | **4/4 FAIL bằng assertions**, 0 lỗi compiler/fixture |
| Hai probe DB upgrade legacy bổ sung | **2/2 FAIL**, tái hiện overwrite và silent payload mismatch |
| Mirror 057 và full schema | Khớp sau chuẩn hóa comment/khoảng trắng |
| Hash source trước/sau audit | Không đổi ở sáu file được snapshot |
| Sửa mã sản phẩm / APK / production | Không thực hiện |

Các tập test trên chạy riêng, không cộng 4 probe lỗi vào tổng 277 rồi báo toàn bộ xanh. Không đo p50/p95, kiểm tra trên thiết bị hoặc xác minh schema production trong lượt này.

Evidence tại `.tmp/report-overlap-recheck-2026-10-29/`:

- `android-baseline.log`, `baseline-results/`: 277 test hiện có.
- `database-baseline.log`, `npm-test.log`: các runner backend đã chạy.
- `generate-android-probes.cjs`, `tests/.../ReportRemainingProbeTest.kt`, `review.init.gradle`, `android-probes.log`, `android-probes.xml`: bốn ca audit bổ sung.
- `generate-database-probes.cjs`, `database-probes.mjs`, `database-probes.log`, `database-results.json`: hai ca dùng RPC 049 thật rồi áp migration 057 thật.
- `source-before.csv`, `source-after.csv`, `schema-mirror.json`.

Lệnh chạy lại audit, không tạo APK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
node .tmp/report-overlap-recheck-2026-10-29/generate-android-probes.cjs
.\gradlew.bat -I .tmp/report-overlap-recheck-2026-10-29/review.init.gradle :app:testDebugUnitTest --tests '*ReportRemainingProbeTest.remaining*' --offline --no-daemon
node .tmp/report-overlap-recheck-2026-10-29/generate-database-probes.cjs
node .tmp/report-overlap-recheck-2026-10-29/database-probes.mjs
```

**Lưu ý khi sửa fixture audit:** generator hiện lấy phần setup từ `ReportOverlapTest` của repository. Nếu OpenCode sửa API/fixture gốc, phải cập nhật generator tương ứng hoặc chuyển các probe thành test chính thức. Không chạy generator để vô tình ghi đè bản test audit đã được chuyển đổi. Giữ nguyên ý nghĩa bảo vệ dữ liệu, không chấp nhận expected sai để làm xanh.
