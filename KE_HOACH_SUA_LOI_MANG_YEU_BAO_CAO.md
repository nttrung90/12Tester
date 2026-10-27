# KẾ HOẠCH & BÁO CÁO PHÂN TÍCH: THÔNG BÁO “MẠNG YẾU…” KHI NỘP BÁO CÁO

> **Ngày lập:** 26/10/2026
> **Rà soát kế hoạch ban đầu:** 27/10/2026, baseline `a115bba`; tái kiểm tra triển khai tại `d2da711`.
> **Trạng thái hiện tại:** Đã sửa F01–F04 và bổ sung regression, **41/41 test cục bộ đạt**, build debug thành công; lint **0 errors, 420 warnings, 1 hint**. Nghiệm thu thiết bị/backend thực vẫn chưa chạy. Xem [báo cáo sửa và kết quả kiểm tra](BAO_CAO_SUA_LOI_NOP_BAO_CAO_2026-10-27.md); [RECHECK](RECHECK_MANG_YEU_BAO_CAO_2026-10-27.md) giữ bằng chứng trước sửa.
> **Mục tiêu:** Hiển thị đúng tiến trình và số lần nộp; giảm retry không cần thiết nếu có; giữ khả năng gửi nền, khôi phục sau process death và không cộng Credit trùng.

> **Cách đọc:** Mục 1–6 là phân tích và yêu cầu tại baseline trước triển khai; các mô tả “hiện tại/chưa có test” trong đó không phải toàn bộ trạng thái checkout mới. Mục 7–8 giữ lịch sử triển khai và tái kiểm tra trước lần sửa này. Báo cáo BAO_CAO_SUA_LOI_NOP_BAO_CAO_2026-10-27.md và trạng thái ở đầu tài liệu là kết quả mới nhất.

## 1. Hiện tượng và giới hạn bằng chứng

Theo báo cáo ban đầu: người dùng nộp feedback kèm ảnh, hộp thoại gợi ý app kế tiếp sớm hiện “Mạng yếu, đang thử lại lần 2…”, sau khoảng 10–15 giây vẫn thành công và nhận Credit.

**Chưa tái hiện trên thiết bị trong lần rà soát này.** Thông tin dữ liệu đã có trên Supabase/Firebase là mô tả của báo cáo cũ, chưa được kiểm tra lại bằng Console hay truy vấn live. Không suy ra “đã retry thật” hoặc “đã chờ backoff 10 giây” chỉ từ nội dung UI và tổng thời gian nộp.

Đã đọc đường đi màn hình → ViewModel → manager → pending store → Worker → Storage/RPC, chuỗi Việt/Anh, Storage rules và migrations liên quan. Chưa chạy build/test ứng dụng trong bước sửa tài liệu.

## 2. Các kết luận cần sửa so với kế hoạch ban đầu

| Nội dung cũ | Kết quả đối chiếu / quyết định mới |
|---|---|
| Đã cô lập chính xác nguyên nhân do metadata Firebase chậm lan truyền | **Chưa được chứng minh.** Cloud Storage bảo đảm đọc object/metadata sau khi ghi thành công; không dùng propagation delay để kết luận lỗi Firebase. Lỗi request lấy URL vẫn có thể xảy ra và cần log riêng. |
| UI báo lần 2 nghĩa là Worker đã trả `Result.retry()` | **Không đủ căn cứ.** Manager cộng 1 vào `WorkInfo.runAttemptCount` cả lúc RUNNING; có thể báo lần 2 ngay lần chạy đầu. |
| Worker đổi thread làm tăng số lần thử | Đổi thread coroutine không tự tạo lần chạy Worker mới. Phân biệt OS dừng/chạy lại, retry do lỗi và retry nội bộ. |
| Có thể giảm backoff WorkManager dưới 10 giây | Không thể; 10 giây là tối thiểu. Checkout đã đặt `EXPONENTIAL, 10, SECONDS`. Lịch chạy thực tế còn phụ thuộc constraints và OS. |
| Policy hiện blacklist HTTP 400/401/403 | Không đúng checkout: `ReportSubmitPolicy` chỉ dò chuỗi message, mặc định retry lỗi còn lại; chưa kiểm tra kiểu exception/HTTP status. |
| Prefetch chắc chắn bắn 5–7 request song song gây nghẽn | Chưa có trace. Prefetch bắt đầu cả khi mở form và submit; nhánh cache/feed/fallback quyết định số request. |
| Dùng `attempt >= 3` để kết luận mạng yếu | Không dùng số lần chạy để chẩn đoán chất lượng mạng; chỉ báo chờ kết nối khi có bằng chứng phù hợp. |

Nguồn: [Cloud Storage consistency](https://docs.cloud.google.com/storage/docs/consistency), [WorkManager retry/backoff](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## 3. Phân tích theo mức độ chắc chắn

### 3.1. Ưu tiên cao nhất: số lần chạy và trạng thái UI có thể bị lệch

`ReportSubmitManager.observeWork()` đang tính:

```kotlin
val attempt = (info.runAttemptCount + 1).coerceAtLeast(1)
// ENQUEUED, BLOCKED và RUNNING đều gọi updateRunningState(...)
// updateRunningState: attempt > 1 => RETRYING
```

Không được coi `WorkInfo.runAttemptCount` và `CoroutineWorker.runAttemptCount` là hai bộ đếm có cùng thời điểm cập nhật. Nguồn AndroidX tham khảo tạo `WorkerParameters` bằng giá trị trước lần chạy, sau đó tăng bộ đếm database khi chuyển sang RUNNING; `WorkInfo` phản ánh giá trị database. Worker lần đầu có thể thấy `0`, còn observer thấy `1`; phép cộng hiện tại biến lần đầu thành lần 2.

**Mức bằng chứng:** xác nhận phép cộng và nhánh UI trong checkout; cơ chế thư viện đã đối chiếu nguồn AndroidX `androidx-main`, chưa xác minh artifact WorkManager `2.11.2` đang pin hoặc APK trên thiết bị. Đây là ứng viên mạnh, cần R00 trước khi chốt nguyên nhân gốc.

Nguồn: [WorkerWrapper](https://github.com/androidx/androidx/blob/androidx-main/work/work-runtime/src/main/java/androidx/work/impl/WorkerWrapper.kt), [WorkSpec → WorkInfo](https://github.com/androidx/androidx/blob/androidx-main/work/work-runtime/src/main/java/androidx/work/impl/model/WorkSpec.kt).

### 3.2. Chắc chắn: nhãn “Mạng yếu” không dựa trên chẩn đoán mạng

`Stage.RETRYING` chỉ phụ thuộc `attempt > 1`; UI dùng `campaign_submit_retrying_format` trong `values`, `values-en`, `values-vi`. Không có mã lỗi hay bằng chứng mất Internet gắn vào quyết định. Ngay cả retry thật cũng có thể do Supabase chưa khởi tạo xong, server, quyền hoặc lưu file.

`NetworkMonitor` hiện chỉ kiểm tra capability INTERNET, chưa kiểm tra VALIDATED; không dùng riêng giá trị đó để kết luận Internet hoạt động hay mạng yếu.

### 3.3. Chắc chắn có điểm cần gia cố: Storage và phân loại retry

- `putFile(...).await()` và lấy `downloadUrl` là hai request riêng. Lỗi URL có thể đi tới catch của Worker, **không có nghĩa ứng dụng bỏ sót mọi xử lý exception**. Cần phân loại trước khi retry.
- Rules trong repo cho đọc/create, cấm update/delete. Upload đã commit nhưng mất response thì `putFile` lại có thể bị từ chối update; fallback đọc object đúng path cũ là hành vi cần giữ.
- `runCatching { ...await() }.getOrNull()` ở fallback có thể bắt cả `CancellationException`. Mẫu đề xuất cũ cũng mắc lỗi này. Worker còn có `runCatching` quanh `joinCampaign`; cần bảo toàn cancellation trên các đường liên quan.
- Policy dò message không đủ phân biệt lỗi mạng, cấu hình, quyền và dữ liệu. Lỗi chưa biết nguyên nhân không được tự đổi thành “mạng yếu”.

Tham khảo: [Firebase Storage error codes](https://firebase.google.com/docs/storage/android/handle-errors), [Task.await và cancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-play-services/kotlinx.coroutines.tasks/await.html).

### 3.4. Prefetch là giả thuyết hiệu năng, chưa phải nguyên nhân đã xác nhận

Call site thực tế: Screen khi mở form và `ViewModel.startSubmit()` trước manager. Có guard `nextSuggestionLoading`/`nextCampaignToTest`, nên lần gọi lúc submit không luôn tạo mạng mới. Job bắt đầu lúc mở form vẫn có thể chạy trùng upload; thêm `delay(1500)` ở một call site không giải quyết đầy đủ.

`getNextCampaignCandidates()` ưu tiên `getHomeFeed(forceRefresh = true)`, chỉ fallback khi không có feed. `firstTimeTester` khởi động từ ViewModel, không nhất thiết bắt đầu lúc submit. Chưa xác nhận RPC `get_home_feed` trên server đang 404.

### 3.5. Các ràng buộc dữ liệu cần bảo vệ

- Giữ cùng `PendingReport.id` / `clientSubmitId` / Storage path qua retry tự động, thủ công và khôi phục; không sinh object mới để né quyền update.
- Worker `markUploaded()` trước RPC; lần chạy lại dùng URL đã lưu, không upload lại. Không xóa pending/ảnh khi lỗi tạm thời.
- Bảng trong checkout là `tester_reports` và `checkins`, không phải `tester_campaign_reports` / `tester_campaign_checkins` như báo cáo cũ.
- Migrations `033_media_integrity.sql`, `037_reward_10_credits.sql` có idempotency qua `p_client_submit_id`. Repository còn fallback RPC legacy khi thiếu function/parameter. Phải xác nhận signature mới trên server trước khi tuyên bố retry an toàn về Credit.
- Worker xóa pending sau RPC nhưng trước `Result.success()`: process death ở cửa sổ này có thể làm lần chạy lại thiếu request. Đây là rủi ro riêng cần probe; có WorkManager không chứng minh mọi cửa sổ khôi phục đều an toàn.

## 4. Kế hoạch triển khai theo gói và điểm dừng

Thứ tự: **R00 → R01 → R02 → R03 (có điều kiện) → R04**. Mỗi gói báo cáo file đổi, bằng chứng, test đã chạy và gate chưa chạy. Việc bổ sung tài liệu chưa phải yêu cầu triển khai code.

### R00 — Tái hiện: retry thật hay hiển thị sai

**Phạm vi:** instrumentation debug/test tối thiểu trong manager, Worker, Storage và đường RPC; chưa đổi hành vi.

1. Timeline liên kết bằng request/work ID đã che bớt: `WorkInfo.state`, raw `WorkInfo.runAttemptCount`, `Worker.runAttemptCount`, worker start, upload/URL start-end, markUploaded, join/RPC start-end, quyết định retry/failure/success. Đo từng bước bằng đồng hồ monotonic.
2. Lỗi ghi kiểu exception, Firebase errorCode/HTTP status nếu có, mã PostgREST và bước lỗi. Không log URL ảnh có download token, token đăng nhập, feedback hay thông tin tài khoản.
3. Kiểm tra dependency thực sự resolve và semantics WorkManager. Chạy Worker thành công ngay, không mạng, đủ lâu để observer đọc RUNNING. Nếu UI RETRYING mà chưa có `Result.retry()`, xác nhận lỗi ánh xạ.
4. Tái hiện gửi thật trên Wi-Fi ổn định và mạng chậm có kiểm soát; tách chờ scheduler, upload, URL, join, RPC và backoff. Không coi 10–15 giây là bằng chứng riêng của retry.
5. Ghi artifact APK; đối chiếu rules đã deploy và signature RPC có `p_client_submit_id`. Thiếu quyền/môi trường live phải ghi rõ.

**Đầu ra/gate:** timeline một lần đầu thành công, một retry thật bằng fault injection và bảng bộ đếm theo trạng thái trên dependency thực tế. Chưa có log lỗi URL thì không kết luận propagation delay. Nếu UI sai ngay lần đầu, sửa R01 và đo lại trước.

### R01 — Sửa ánh xạ trạng thái và thông điệp

**Phạm vi:** manager, ba file strings; ViewModel/Screen nếu cần truyền state.

1. Bỏ phép cộng 1 dùng chung mọi trạng thái. Ánh xạ dự kiến dưới đây phải được R00 kiểm chứng:

| WorkInfo quan sát | Ý nghĩa dự kiến | UI |
|---|---|---|
| ENQUEUED, count 0 | Chờ chạy lần đầu | Chờ gửi, không báo lần 2 |
| RUNNING, count 1 | Lần chạy đầu | SENDING, attempt 1 |
| ENQUEUED sau lần đầu trả retry, count 1 | Đợi chạy tiếp | Chờ thử lại lần 2 |
| RUNNING, count 2 | Lần chạy thứ hai | Thử lại lần 2 |
| SUCCEEDED/FAILED | Đã kết thúc | Số lần đã chạy, không cộng sang lần tiếp theo |

2. Không suy lý do retry từ count: mất constraints, OS dừng Worker, Supabase chưa sẵn sàng và lỗi request là các trường hợp khác nhau. Nếu chưa có lý do đáng tin cậy, dùng trạng thái chờ/gửi trung tính. BLOCKED không tự đồng nghĩa lỗi mạng.
3. Chuỗi đề xuất: `Đang thử gửi lại báo cáo (lần %1$d)…` / `Retrying report submission (attempt %1$d)…`; nếu tách trạng thái chờ: `Đang chờ thử gửi lại báo cáo…`. Không dùng `attempt >= 3` để chuyển thành “mạng yếu”.
4. Nếu cần phase upload/URL/RPC, bổ sung progress rõ ràng. Enum hiện chỉ có SENDING, RETRYING, DONE, FAILED; UPLOADING/SUBMITTING_REPORT trong kế hoạch cũ chưa tồn tại.
5. Giữ guard requestId; kết quả app trước không ghi đè app hiện tại. Giữ gửi nền khi chuyển màn, không khóa điều hướng để che lỗi.

**Nghiệm thu:** lần đầu thành công không từng hiện RETRYING; retry thật đúng lần; trạng thái cuối không lệch số; Việt/Anh đồng bộ; Credit theo kết quả server.

### R02 — Retry Storage/Worker có giới hạn, không nuốt cancellation

**Phạm vi:** Storage helper, policy, Worker; pending store chỉ khi probe chứng minh cần sửa.

1. Tách lỗi upload và URL. Upload xong mà URL lỗi tạm thời thì chỉ retry URL. Không bọc upload + URL bằng một catch rồi chạy thêm một vòng URL retry chồng lên vòng vừa thất bại.
2. Upload lỗi/không rõ đã commit: recovery bằng đọc URL **đúng path cũ**, ngân sách hữu hạn. Chỉ công nhận đã upload khi đọc được object. Upload bị cấm nhưng object cũ đọc được vẫn có thể recovery; object không tồn tại thì trả lỗi gốc, không lặp upload vô hạn.
3. Phân loại theo kiểu/cause/error code, thay vì chỉ message:

| Lỗi | Chính sách |
|---|---|
| Cancellation của Worker/coroutine | Ném lại ngay, không đổi thành retry/failure/success |
| Timeout/kết nối, HTTP 408/429/5xx có thể khôi phục | Retry hữu hạn đúng bước; hết ngân sách mới về WorkManager; tôn trọng Retry-After nếu áp dụng |
| Firebase unauthorized/unauthenticated, bucket/project sai, dữ liệu/ảnh sai | Fail rõ nguyên nhân sau recovery hợp lệ nếu có; không lặp thao tác sai |
| Object not found khi recovery | Chưa chứng minh đã upload; giữ ngữ cảnh lỗi gốc. Upload báo thành công mà not found thì ghi bằng chứng, không mặc định metadata chậm |
| Supabase 400/403 hoặc lỗi nghiệp vụ vĩnh viễn | Không retry, trừ recovery cụ thể đã kiểm thử |
| Supabase 401 | Chỉ dùng cơ chế refresh phiên hợp lệ hiện có; thất bại thì yêu cầu đăng nhập, không lặp vô ích |
| Chưa phân loại | Ghi nguyên nhân; không local retry; fallback Worker hữu hạn theo policy đã chốt, UI trung tính |

4. Nếu bổ sung local retry URL: tối đa **3 lần gọi tổng cộng** trong một lượt resolve; delay 500 ms rồi 1.000 ms chỉ giữa lỗi tạm thời, không delay happy path. Đây là ngân sách thử ban đầu, không bảo đảm hoàn tất trong 1 giây. Trước khi triển khai phải chốt timeout từng request/tổng lượt theo SDK và số đo R00; SDK có retry riêng nên tránh nhân request/thời gian ngoài dự kiến.
5. Catch có suspend phải bắt và ném lại `CancellationException` trước; thay `runCatching` trên các đường liên quan. Hủy `Task.await()` không mặc nhiên hủy UploadTask bên dưới: nếu cần, quản lý handle/cancel rõ ràng và kiểm thử, tránh hai upload chồng nhau.
6. Nếu dùng `withTimeout`, phân biệt timeout ngân sách nội bộ với cancellation bên ngoài; không nuốt hủy Worker thành lỗi mạng. Không thêm vòng retry toàn bộ doWork/RPC để chữa riêng lỗi URL.
7. Giữ outer backoff 10 giây và `MAX_ATTEMPTS = 3`; tách số lần Worker với số request URL. Giữ ID/bytes/hash/path, lưu URL trước RPC; retry RPC dùng URL đã lưu.
8. Giữ lỗi chính và lỗi recovery để chẩn đoán, không lộ token. Sửa comment “ghi đè object cũ” vì rules chỉ cho create. Không nới update/delete.

**Nghiệm thu:** URL lỗi tạm thời khôi phục hữu hạn, không upload lại; lỗi quyền thật fail đúng; cancellation thoát ngay; không tăng object/report/Credit do retry.

### R03 — Điều phối prefetch khi có bằng chứng ảnh hưởng

**Gate:** chỉ triển khai khi trace R00/sau R01 cho thấy ảnh hưởng đáng kể. Nếu gửi đã đúng và nhanh, ghi “không cần thay đổi” cùng số đo.

1. Kiểm soát cả call site mở form, submit và job đã chạy; không chỉ xóa một lời gọi hoặc thêm delay cố định. Giữ kết quả sẵn có để hộp thoại hiện ngay.
2. Khi cần hoãn: không khởi tạo prefetch mới trong lúc upload/resolve URL; chạy lại sau khi URL lưu thành công, hoặc sau DONE nếu chọn phương án đơn giản. Muốn dùng mốc upload xong phải thêm Worker progress, không đoán thời gian.
3. Nếu cần hủy job prefetch, giữ Job riêng và finally dọn `nextSuggestionLoading`; không ảnh hưởng Worker. Đặt guard single-flight trước launch để tránh hai job trùng.
4. Giữ snapshot tester lần đầu trước submit, ưu tiên app đã cài, loại app hiện tại/app chủ sở hữu/app đã test hôm nay/app pending. DONE/refresh không làm sai tiêu chí của cùng lần nộp.
5. Prefetch lỗi không làm báo cáo fail, không kẹt spinner; vẫn đóng/chuyển màn được. ViewModel hủy không hủy Worker gửi nền.

**Nghiệm thu:** không có request gợi ý mới trong giai đoạn được bảo vệ; không trùng job/mất gợi ý/đổi tiêu chí; có so sánh latency trước-sau.

### R04 — Kiểm thử hồi quy và nghiệm thu thiết bị/backend

Repo chưa có `app/src/test`, `app/src/androidTest` và test dependencies trong app build file. Khi triển khai, thêm hạ tầng tối thiểu phù hợp phiên bản pin: unit test, coroutine virtual time và WorkManager test harness/instrumentation. Fake Storage/RPC phải điều khiển được lỗi. Cần ít nhất một integration test qua observer WorkManager thật để bắt lệch bộ đếm, không chỉ test hàm mapping tự viết.

| Ca kiểm tra | Tiêu chí |
|---|---|
| Lần đầu thành công, không trả retry | SENDING → DONE; không thoáng hiện lần 2 khi observer bắt RUNNING |
| Một Result.retry rồi thành công | Chờ/gửi lại đúng lần 2, không lần 3; DONE không lệch số |
| URL timeout một lần rồi được | Một upload; URL retry hữu hạn; không reschedule nếu khôi phục được |
| URL lỗi liên tục | Đúng tổng request/delay; outer retry hữu hạn, không vòng lặp vô tận |
| Object cũ đã có, putFile bị cấm update | Dùng URL cũ; không tạo path mới/nới rules |
| Object chưa có, upload bị từ chối quyền | Fail quyền; không thành công giả/báo mạng yếu |
| Offline, Wi-Fi không Internet, mất mạng giữa upload | Chờ/thử lại trung tính; giữ pending; phục hồi khi đủ điều kiện; không dựa riêng capability INTERNET |
| Hủy ở upload, URL, delay, join, RPC | Không nuốt cancellation hoặc phát success/Credit từ fallback bị hủy |
| RPC commit rồi mất response | Giữ ID; dùng URL đã lưu; server có report/checkin/Credit không tăng lần hai |
| Process death trước/sau markUploaded, sau RPC commit, sau xóa pending trước success | Probe từng cửa sổ; thiếu request sau commit không được báo kết quả sai. Nếu fail, chặn tuyên bố phục hồi đầy đủ và lập gói sửa riêng rõ phạm vi |
| Double tap, retry thủ công, hai campaign liên tiếp | Không request trùng/ghi đè state mới/nhân Credit |
| Đổi màn, xoay máy, mở lại; logout/đổi tài khoản | Đúng vòng đời gửi nền; không hiển thị kết quả tài khoản cũ |
| Prefetch đang chạy/chưa chạy/đã có kết quả/lỗi | Không kẹt spinner/chọn sai app/làm Worker fail |
| Báo cáo mới, sửa pending/rejected, approved/đã nhận thưởng | Giữ validation/ảnh/hash/thưởng server; không mặc định mọi lần +10 |
| Việt/Anh, lỗi vĩnh viễn | Đúng format; không quy lỗi quyền/server thành mạng yếu |

Dùng emulator hoặc staging, tài khoản/campaign kiểm thử riêng. Xác minh rules và RPC đang deploy độc lập với file trong repo. Fallback RPC legacy còn chạy thì gate idempotency chưa đạt; không tuyên bố an toàn chỉ dựa vào migration. Không sửa dữ liệu production để tạo lỗi.

Lệnh dự kiến sau khi triển khai và cấu hình môi trường hợp lệ:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Lệnh connected chỉ chạy sau khi có tests và thiết bị/emulator. Ghi rõ thiếu SDK/Firebase/Supabase/thiết bị; không đưa secret vào báo cáo. Đọc cả lint report vì `abortOnError = false` hiện tại có thể cho task xanh dù còn lỗi.

## 5. Danh sách tệp dự kiến

| Tệp / nhóm | Mục đích |
|---|---|
| `app/src/main/java/com/ntt/crosstest/data/ReportSubmitManager.kt` | WorkInfo/state/attempt/progress đúng |
| `app/src/main/java/com/ntt/crosstest/work/ReportUploadWorker.kt` | Timeline, retry, cancellation, progress |
| `app/src/main/java/com/ntt/crosstest/data/ReportSubmitPolicy.kt` | Phân loại kiểu/mã lỗi và unit tests |
| `app/src/main/java/com/ntt/crosstest/util/FirebaseStorageHelper.kt` | URL retry chọn lọc, recovery object cũ, cancellation, comment |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt` | Truyền state; prefetch có điều kiện |
| `app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt` | Hiển thị state và call site prefetch nếu cần |
| `app/src/main/res/values/strings.xml`, `values-en/strings.xml`, `values-vi/strings.xml` | Chuỗi trung tính, placeholder nhất quán |
| `app/src/test/…`, `app/src/androidTest/…`, `app/build.gradle.kts`, version catalog nếu cần | Hạ tầng test và hồi quy có ý nghĩa |
| `PendingReportStore.kt` / `CampaignRepository.kt` | Chỉ mở rộng sửa khi probe chứng minh cần thiết |

Rules/migrations là tài liệu đối chiếu, chưa có căn cứ thay đổi/deploy. Không nâng dependency, đổi schema hoặc thay toàn bộ kiến trúc nộp để chữa thông báo.

## 6. Điều kiện hoàn tất và phát hành

1. Có timeline phân biệt lỗi hiển thị và retry thật; không khẳng định propagation delay khi chưa có bằng chứng.
2. Tối thiểu 20 lần nộp hợp lệ trên kết nối ổn định: lần nào Worker thành công ngay thì không hiện retry giả; ghi số lần chạy thật, lỗi nếu có và thời lượng từng bước. Không cam kết 1–3 giây cho mọi mạng/ảnh.
3. So sánh p50/p95 trước-sau trên cùng loại thiết bị, tập ảnh và điều kiện mạng. Báo riêng cải thiện UI và latency. Nếu còn 10–15 giây, chỉ đúng bước chiếm thời gian; đổi chữ không chứng minh đã sửa tốc độ.
4. Failure injection trọng yếu đạt: retry hữu hạn, không upload thừa/nuốt cancellation/cộng Credit trùng/regression gửi nền. Gate thiết bị/backend chưa chạy ghi **CHƯA XÁC MINH**, không thay bằng JVM/build xanh.
5. Chạy nội bộ/staging trước; thử cả request pending từ phiên bản cũ. Báo cáo artifact, diff, test output, timeline đã che dữ liệu nhạy cảm và gate còn mở trước phát hành.
6. Rollback app bằng artifact đã kiểm chứng; giữ pending tương thích hoặc kiểm thử migration nếu buộc đổi format. Không xóa pending để che lỗi, không deploy schema/rules ngoài phạm vi.

**Kết luận rà soát:** kế hoạch cũ chưa đủ tin cậy để triển khai nguyên trạng. Bản hiệu chỉnh ưu tiên kiểm chứng lệch `WorkInfo.runAttemptCount`, sửa nhãn UI không có căn cứ, sau đó gia cố retry chọn lọc và chỉ tối ưu prefetch khi có số đo. Chỉnh tài liệu không đồng nghĩa ứng dụng đã được sửa hoặc sự cố đã được khắc phục trên thiết bị.

---

## 7. Báo cáo triển khai trước tái kiểm tra (27/10/2026)

**Chú thích kiểm tra độc lập:** Giữ nội dung dưới đây làm lịch sử. Khẳng định “lần chạy đầu luôn SENDING/1” chưa đúng mã nguồn; test policy/build thành công không chứng minh hoàn thành R04. Kết luận nghiệm thu được thay bằng mục 8 và báo cáo RECHECK.

1. **Gói R01 (Ánh xạ trạng thái & UI):**
   - Đã sửa `ReportSubmitManager.kt`: dùng `isRetry = runAttemptCount > 0`. Báo cáo triển khai cho rằng lần đầu luôn `SENDING/1`; **tái kiểm tra F01 xác nhận điều kiện này vẫn tương đương logic cũ và chưa đạt**.
   - Đã đồng bộ chuỗi thông báo trung tính trong cả 3 tệp `strings.xml`:
     - Tiếng Việt: `Đang thử gửi lại báo cáo (lần %1$d)…`
     - Tiếng Anh & mặc định: `Retrying report submission (attempt %1$d)…`
2. **Gói R02 (Storage retry & Phân loại lỗi):**
   - Đã sửa `FirebaseStorageHelper.kt`: thêm hàm `awaitDownloadUrlWithRetry` với tối đa 3 lần thử cục bộ (delay 500ms, 1000ms). Tái kiểm tra F02: chưa phân loại lỗi vĩnh viễn ngay trong vòng local retry.
   - Bảo toàn `CancellationException`, loại bỏ hoàn toàn `runCatching` nuốt cancellation ở nhánh recovery.
   - Đã sửa `ReportSubmitPolicy.kt`: phân loại theo `StorageException.errorCode` và `RestException.statusCode`; tái kiểm tra F03 phát hiện checksum mismatch bị xếp vĩnh viễn chưa phù hợp.
   - Đã sửa `ReportUploadWorker.kt`: bảo toàn cancellation trong `joinCampaign`, thêm log che ID (`req.take(8)`). Chưa có đủ timeline elapsed theo R00; F04 về xóa pending trước commit WorkManager còn tồn tại từ baseline.
3. **Gói R04 (Kiểm thử & Build):**
   - Đã bổ sung `ReportSubmitPolicyTest.kt` gồm 6 test methods cho một số nhánh policy; chưa bao phủ đầy đủ manager, Worker, Storage retry, HTTP status, process death hoặc backend idempotency.
   - Chạy lệnh: `gradlew :app:testDebugUnitTest` -> **PASSED 100%**.
   - Chạy lệnh: `gradlew :app:assembleDebug` -> **BUILD SUCCESSFUL**.

## 8. Kết luận tái kiểm tra và kế hoạch sửa tiếp

Đối chiếu commit `d2da711` và sources artifact WorkManager đúng phiên bản 2.11.2: bản sửa **chưa hoàn thành** kế hoạch.

| Mã | Ưu tiên | Kết quả |
|---|---|---|
| F01 | P1 | Lần RUNNING đầu vẫn RETRYING/2; lần hai RETRYING/3; terminal còn cộng dư |
| F02 | P2 | Local retry URL thử cả lỗi quyền/bucket vĩnh viễn; chưa chốt deadline tổng |
| F03 | P2 | Upload checksum mismatch bị policy kết luận không retry |
| F04 | P1 | Process death sau xóa pending/trước commit WorkManager làm lần chạy lại báo thiếu request; lỗi có từ trước, chưa xử lý |

Đã chạy 14 probe độc lập trên các lớp production/đoạn retry nguyên văn với Android/Storage/RPC fixtures: **7 đạt, 7 không đạt**. Đây là probe cục bộ, không thay thế Android integration hay kiểm tra Firebase/Supabase live.

Kế hoạch sửa tiếp: **S00** chỉnh bằng chứng/báo cáo → **S01** sửa mapping và test WorkManager → **S02** sửa retry theo thao tác/checksum → **S03** hoàn tất bền vững trước cleanup → **S04** nghiệm thu thiết bị/staging và đo latency. Chi tiết phạm vi, regression, nghiệm thu, điểm dừng và prompt bàn giao ở [RECHECK_MANG_YEU_BAO_CAO_2026-10-27.md](RECHECK_MANG_YEU_BAO_CAO_2026-10-27.md).

Kiểm tra độc lập: **6/6 unit tests gốc đạt; assembleDebug thành công; lintDebug hoàn tất nhưng report có 21 errors, 419 warnings và 1 hint** (`abortOnError = false`). Các file có lint Error không thay đổi trong diff bản sửa mạng yếu, được ghi thành gói S05 riêng. Chưa có thiết bị kết nối và chưa kiểm tra backend live.

Lần tái kiểm tra chỉ sửa tài liệu, chưa sửa code ứng dụng. Chi tiết bằng chứng và gate chưa chạy được ghi trong báo cáo RECHECK.
