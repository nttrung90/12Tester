# Tái kiểm tra bản sửa thông báo mạng yếu — 27/10/2026

> **Cập nhật sau khi được yêu cầu sửa code:** F01–F04 và các lint Error đã được triển khai sửa trong working tree. Bộ regression hiện có 41 test đạt. Nội dung bên dưới giữ làm bằng chứng **trước sửa**; kết quả bàn giao mới nhất xem [BAO_CAO_SUA_LOI_NOP_BAO_CAO_2026-10-27.md](BAO_CAO_SUA_LOI_NOP_BAO_CAO_2026-10-27.md). Gate thiết bị/staging vẫn được ghi riêng, không suy ra từ build/JVM.

## 1. Phạm vi và kết luận

- Checkout kiểm tra: `d2da711089f31eb1128d27c6cf499baea1306af8`; working tree sạch trước kiểm tra. Diff triển khai so với `792ca9f`.
- Đã đọc đầy đủ manager, Worker, policy, helper, pending store, đường RPC, UI/strings, test mới và báo cáo triển khai.
- **Chưa đạt nghiệm thu.** R01 chưa sửa được logic lệch bộ đếm; R02 có cải thiện cancellation nhưng còn retry sai loại lỗi; R04 chưa có các test đường nộp thực tế theo kế hoạch.
- Chỉ sửa tài liệu trong lần tái kiểm tra này. Harness/probe nằm trong `.tmp/report-recheck` đã được git-ignore; không sửa production code hoặc test gốc.

## 2. Các lỗi xác nhận

### F01 — P1: R01 vẫn hiển thị lần 2 ngay lần RUNNING đầu

**Vị trí:** `ReportSubmitManager.kt:248–253, 289–296`.

Trước sửa: `attempt = count + 1`, `attempt > 1` thì RETRYING. Sau sửa: `isRetry = count > 0`, `attempt = count + 1`. Với count không âm, hai cách tính tương đương. ENQUEUED/BLOCKED/RUNNING tiếp tục dùng cùng hàm, không biết trạng thái cụ thể. Terminal cũng còn cộng 1.

Đã tải và đọc **sources artifact đúng phiên bản WorkManager 2.11.2** từ Google Maven, không chỉ dùng nhánh AndroidX mới nhất:

- `WorkerWrapper.kt:211–216`: truyền giá trị cũ vào WorkerParameters.
- `WorkerWrapper.kt:410–416`: chuyển RUNNING và tăng count trong database.
- `WorkSpec.kt:358–366`: WorkInfo nhận count database đó.

Hệ quả: lần chạy đầu Worker thấy 0, WorkInfo RUNNING thấy 1; UI hiện RETRYING/2. Lần chạy hai hiện RETRYING/3. Dòng báo cáo “lần đầu luôn SENDING/1” trong mục 7 tài liệu cũ chưa đúng mã nguồn.

Nguồn artifact: [WorkManager 2.11.2 sources](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.11.2/work-runtime-2.11.2-sources.jar). Bản trích được lưu cục bộ trong `.tmp/report-recheck/WorkerWrapper.kt` và `WorkSpec.kt`.

**Tác động:** đổi chữ đã bỏ nhãn “Mạng yếu”, nhưng retry giả/số lần sai vẫn tồn tại. Đây là blocker trực tiếp của sự cố ban đầu.

### F02 — P2: vòng local retry URL vẫn thử lại lỗi quyền/cấu hình

**Vị trí:** `FirebaseStorageHelper.kt:130–149, 178–193`.

Catch mọi `Exception` trừ cancellation rồi delay/retry, không dùng phân loại tạm thời/vĩnh viễn. Probe cấp lỗi unauthorized hoặc bucket-not-found vào hàm retry nguyên văn: đều gọi lấy URL 3 lần thay vì dừng sau 1. Policy ở Worker chỉ được áp dụng sau khi vòng này kết thúc, nên không bảo vệ được local retry.

Chưa có deadline tổng của lượt resolve; delay 500/1.000 ms không giới hạn thời gian SDK xử lý từng request/retry bên trong. Nhánh recovery còn nuốt lỗi URL và ném lại lỗi upload ban đầu, làm mất ngữ cảnh khi lỗi recovery khác loại.

**Tác động:** trì hoãn lỗi vĩnh viễn, thêm request không hữu ích và chưa đáp ứng R02. Comment “metadata chưa index kịp” ở dòng 126 vẫn khẳng định giả thuyết chưa được chứng minh.

### F03 — P2: checksum mismatch bị coi là lỗi vĩnh viễn

**Vị trí:** `ReportSubmitPolicy.kt:40–45`.

`ERROR_INVALID_CHECKSUM` bị xếp chung với lỗi quyền/project/bucket và trả false. Firebase xác định đây là dữ liệu nhận ở server không khớp checksum file client và hướng dẫn thử upload lại; không tự đồng nghĩa ảnh local không hợp lệ.

**Tác động:** lỗi truyền dữ liệu có khả năng khôi phục khiến Worker dừng FAILED sau lượt đầu; người dùng phải thử thủ công. Probe policy nguyên bản xác nhận trả false cho checksum mismatch.

Hướng sửa phải giữ **cùng file/hash/ID/path**, retry hữu hạn khi file local còn đúng; lỗi file local thay đổi/hỏng thật cần báo riêng. Không retry riêng thao tác lấy URL để chữa lỗi upload checksum.

Nguồn: [Firebase Android error handling](https://firebase.google.com/docs/storage/android/handle-errors), [StorageException source](https://github.com/firebase/firebase-android-sdk/blob/master/firebase-storage/src/main/java/com/google/firebase/storage/StorageException.java).

### F04 — P1: xóa pending trước khi WorkManager lưu thành công làm lần chạy lại thất bại giả

**Vị trí:** `ReportUploadWorker.kt:34–35, 101–125`; `PendingReportStore.delete()` xóa cả JSON và ảnh.

Trình tự hiện tại: RPC thành công → xóa pending → tạo/return Result.success → WorkManager mới có thể commit trạng thái SUCCEEDED. Nếu process chết sau xóa pending nhưng trước commit của WorkManager, Worker được chạy lại sẽ dừng ngay `pending_request_not_found`, dù server đã nhận bài/Credit đã được xử lý. Retry thủ công cũng không còn pending để đọc.

Probe chạy **Worker production nguyên bản** với store/RPC giả: lượt đầu success và một server commit; bỏ kết quả đó để mô phỏng chưa commit WorkManager; gọi lại cùng ID → failure. Đây là tái nhập có kiểm soát, chưa phải kill-process test trên Android thật.

**Phân loại:** lỗi có từ trước commit sửa, đã nằm trong R04 của kế hoạch; chưa được xử lý hoặc nghiệm thu. Không phải lỗi mới do thay strings/retry. Vẫn chặn tuyên bố “khôi phục sau process death đầy đủ”.

## 3. Phần đã cải thiện và giới hạn kiểm thử

- Ba bộ strings đã dùng thông điệp retry trung tính, placeholder nhất quán.
- Fallback Storage và joinCampaign đã ném lại CancellationException; probe URL cancellation thoát ngay, không thử lần tiếp.
- Upload và resolve URL tách nhau, không có vòng URL retry chồng qua catch upload như mẫu cũ.
- Local retry tạm thời có giới hạn số lần; timeout một lần rồi thành công khôi phục được; policy nhận lỗi quyền và REST 403 là vĩnh viễn.
- Worker vẫn giữ clientSubmitId, markUploaded trước RPC, outer backoff 10 giây và 3 lần chạy. Chưa thay rules/migrations.
- **R03 chưa làm không tự là lỗi:** gói này có điều kiện. Cần số đo trước khi quyết định có hoãn prefetch; chưa đủ bằng chứng yêu cầu thay đổi.

Bộ test gốc có **6 test methods**, chỉ trong ReportSubmitPolicyTest. Chưa có manager/Worker/URL/coroutine/WorkManager integration tests. Hai import RestException/HttpResponse không được sử dụng nên không phải test HTTP status. Test tên retryLimitExceeded tạo exception bằng HTTP 503; theo source Firebase, mã đó đi vào ERROR_UNKNOWN, không chứng minh đã cover ERROR_RETRY_LIMIT_EXCEEDED.

Instrumentation mới có start/end rời rạc, chưa có đủ mốc upload/URL/RPC start-end, elapsed monotonic và mã lỗi có cấu trúc theo R00. Không thể từ các log hiện tại kết luận đoạn nào chiếm 10–15 giây.

### Kết quả chạy độc lập

Lệnh độc lập: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon`, dùng cache `C:\Users\nguye\.gradle` và SDK của máy qua biến môi trường process, không sửa cấu hình source. Lần đầu sandbox không ghi được cache; chạy lại với quyền được cấp để hoàn thành kiểm tra.

- **Unit tests gốc: PASS 6/6**, failures/errors/skipped đều 0. Evidence: `app/build/test-results/testDebugUnitTest/TEST-com.ntt.crosstest.data.ReportSubmitPolicyTest.xml`.
- **assembleDebug: PASS**; artifact debug sinh được. Đây không phải kiểm thử cài/chạy hoặc bản release đã ký.
- **lintDebug task hoàn tất nhưng lint chưa sạch:** **21 errors, 419 warnings, 1 hint**. 20 errors thuộc `LocalContextGetResourceValueCall`, 1 thuộc `UnusedBoxWithConstraintsScope`. `abortOnError = false` nên cả lệnh vẫn BUILD SUCCESSFUL, không chứng minh lint sạch.
- Cả lệnh kết thúc **BUILD SUCCESSFUL, 54 tasks executed**. Các cảnh báo Kotlin/deprecation không làm build fail.
- **Probe bổ sung: 7 PASS, 7 FAIL, tổng 14.** Compile nguyên bản ba lớp manager/policy/Worker và trích nguyên văn hàm URL retry; Android, WorkManager API, pending store và Storage/RPC là fixtures. Compiler/coroutine của harness là cache Kotlin 2.2.10/coroutines 1.9.0; không dùng kết quả đó thay thế dependency hoặc integration runtime của app. Semantics bộ đếm đối chiếu riêng bằng source WorkManager 2.11.2.

| Probe | Kỳ vọng | Thực tế | Kết quả |
|---|---|---|---|
| RUNNING đầu, raw count 1 | SENDING/1 | RETRYING/2 | FAIL |
| RUNNING hai, raw count 2 | RETRYING/2 | RETRYING/3 | FAIL |
| SUCCEEDED sau lần đầu | DONE/1 | DONE/2 | FAIL |
| URL unauthorized | 1 call | 3 calls | FAIL |
| URL bucket-not-found | 1 call | 3 calls | FAIL |
| URL transient liên tục | 3 calls tối đa | 3 calls | PASS |
| Cancellation khi lấy URL | Ném lại, 1 call | Đúng | PASS |
| URL transient rồi thành công | URL, 2 calls | Đúng | PASS |
| Policy upload checksum mismatch | Cho retry hữu hạn | false | FAIL |
| Policy quyền upload | false | false | PASS |
| Policy IOException timeout | true | true | PASS |
| Policy REST 403 | false | false | PASS |
| Worker happy path | success, 1 commit | Đúng | PASS |
| Chạy lại sau commit + xóa pending, chưa commit WorkManager | Phục hồi thành công đã biết | failure | FAIL |

Harness có tại `.tmp/report-recheck/run-probes.ps1`, output tại `.tmp/report-recheck/probe-results.txt`; cả hai git-ignore. Các probe cố ý đo acceptance chưa đạt, không sửa production để làm xanh chúng. Probe ánh xạ gọi private method bằng reflection; terminal đi qua observer production với WorkInfo fixture; probe process death là tái nhập Worker, không phải bằng chứng kill-process Android.

`adb devices` chạy được nhưng danh sách trống. **CHƯA CHẠY:** WorkManager integration trên Android, kill-process, 20 lần nộp/latency, Firebase/Supabase staging và xác nhận cấu hình đã deploy. Không truy cập backend production hoặc tạo bài nộp live trong lần audit này.

### Lint ngoài phạm vi bản sửa mạng yếu

21 diagnostics mức Error nằm ở các file **không thay đổi trong diff `792ca9f..d2da711`**, nên không quy chúng là regression của bản sửa này. Đây là kết quả công cụ; chưa đồng nghĩa đã tái hiện 21 lỗi runtime khác nhau.

| File | Diagnostics |
|---|---|
| `ui/screens/AccountLockedScreen.kt` | 8 lỗi đọc resource qua LocalContext, dòng 95, 146, 149, 152, 154, 430, 431, 442 |
| `ui/screens/CampaignDetailScreen.kt` | 2 lỗi cùng loại, dòng 1773, 2056 |
| `ui/screens/HomeScreen.kt` | 2 lỗi cùng loại, dòng 401, 659 |
| `ui/screens/OwnerAppDetailScreen.kt` | 8 lỗi cùng loại, dòng 591, 613, 858, 1426, 1441, 1460, 1475, 2285 |
| `ui/components/ImageViewerComponents.kt` | 1 BoxWithConstraints không dùng constraints, dòng 713 |

Evidence: `app/build/reports/lint-results-debug.xml`, `.txt`, `.html`. Giải quyết trong S05 riêng; không dùng việc task xanh để đóng gate chất lượng và không tự mở rộng sửa code UI trong audit này.

## 4. Kế hoạch sửa tiếp — gói S00–S04

Chỉ triển khai khi có yêu cầu sửa code. Mỗi gói giữ working tree của người dùng, không gom thêm thay đổi tỷ lệ test/Supabase ngoài phạm vi.

### S00 — Khóa bằng chứng và sửa trạng thái báo cáo

- Owner: tài liệu, test evidence; không sửa ứng dụng.
- Ghi đúng commit, kết quả từng task và test count, phiên bản artifact WorkManager, ca chưa chạy; bỏ kết luận R01/R04 đã hoàn thành.
- Phân biệt R01 lỗi xác nhận, R02 đạt một phần, R03 có điều kiện, R00/R04 gate còn mở. Giữ mục triển khai cũ làm lịch sử có chú thích bị thay thế.
- Nghiệm thu: không còn câu “100% hoàn tất” bị hiểu thành đã cover thiết bị, process death, Storage/RPC.

### S01 — Sửa F01 trước, thêm regression bắt lỗi thực

- Owner: manager, test manager/WorkManager; strings/UI chỉ khi cần phân biệt chờ với đang chạy.
- Truyền WorkInfo.State vào mapping; RUNNING dùng số lần đã bắt đầu (`count.coerceAtLeast(1)`), không cộng 1; RETRYING khi RUNNING count >= 2. Terminal dùng số lần đã chạy.
- ENQUEUED ban đầu count 0 là chờ lần đầu; sau retry count 1 có thể hiển thị chờ lần 2. Không chẩn đoán lỗi mạng từ count; OS dừng vì constraints dùng thông điệp trung tính.
- Giữ guard requestId, result/event của campaign trước và gửi nền khi chuyển màn.
- Test bắt buộc: RUNNING 1 => SENDING/1; RUNNING 2 => RETRYING/2; SUCCEEDED 1 => DONE/1; ENQUEUED 0 và sau retry 1; FAILED/CANCELLED không cộng dư.
- Có ít nhất một test qua **WorkManager test harness thực**, đi qua observer, không chỉ unit test hàm mapping hoặc SDK giả.
- Điểm dừng: chạy test trên commit hiện tại phải đỏ F01, sau sửa xanh; không đụng Firebase/prefetch để làm xanh test trạng thái.

### S02 — Sửa F02/F03: policy theo thao tác, retry có ngân sách

- Owner: Storage helper, policy, Worker/tests liên quan.
- Phân biệt retry quyết định cho UPLOAD, RESOLVE_URL và recovery object cũ; không áp một boolean chung thiếu ngữ cảnh.
- URL lỗi quyền/cấu hình dừng local retry ngay; chỉ retry lỗi tạm thời đúng mã/cause. Không coi object-not-found sau upload là metadata delay mặc định.
- Checksum mismatch ở upload được phép thử lại hữu hạn với bytes/hash/path cũ khi file vẫn đúng; local file hỏng/thay đổi thì fail riêng. Giữ cap outer 3 lần, không thêm retry upload chồng tầng.
- Recovery vẫn cho phép đọc object đúng path khi putFile bị cấm update do object đã tồn tại. Nếu recovery lỗi, giữ lỗi gốc và lỗi recovery có cấu trúc; cancellation luôn ném lại.
- Chốt deadline thực cho mỗi request/tổng lượt theo cấu hình SDK và số đo; không coi 1.500 ms delay là timeout tổng. Timeout nội bộ không được nuốt hủy Worker bên ngoài. Xác định việc cancel UploadTask trước khi cho phép lượt upload khác.
- Test: unauthorized/bucket lỗi URL đúng 1 call; transient đúng cap; cancellation/delay; recovery object có/không có; checksum transient phục hồi không đổi bytes/hash/ID; exhausted budget về outer retry.
- Bổ sung test tạo **đúng errorCode** và assert mã fixture trước khi kiểm tra policy; HTTP 503 và ERROR_RETRY_LIMIT_EXCEEDED là hai ca riêng.

### S03 — Sửa F04: hoàn tất bền vững trước cleanup

- Owner: Worker, pending store, manager/reconciliation, tests; repository chỉ khi cần đối soát kết quả theo cùng ID.
- Không xóa request trong doWork trước khi WorkManager commit SUCCEEDED. Lưu receipt/result hoàn tất bền vững theo requestId để lần chạy lại có thể trả kết quả đã biết, không gửi lại bài mới.
- Cleanup khi đã xác nhận SUCCEEDED; có cơ chế reconcile sau app restart khi không còn observer trong RAM. Tránh để completed record bị coi là pending campaign vĩnh viễn.
- Giữ định dạng đọc request cũ tương thích; nếu thêm field phải có default và test legacy JSON. Ghi receipt nguyên tử, không dùng marker chỉ tồn tại trong RAM.
- Cửa sổ RPC commit trước khi lưu receipt: retry/đối soát **cùng clientSubmitId**, không sinh ID mới. Server replay có thể trả provisionalGranted=false; không tự suy ra được thưởng mới hoặc tự dựng +10.
- Bắt buộc kiểm tra signature RPC hiện đại và Credit/checkin idempotency trên staging. Nếu đang dùng legacy fallback thì giữ gate backend mở, không tuyên bố exactly-once.
- Test/fault injection: sau RPC commit trước receipt; sau receipt trước Result.success; sau success trước cleanup; process death/reopen; hai request liên tiếp; logout/đổi user. Server chỉ ghi/thưởng một lần; client không báo thiếu request sau một success đã biết; không phát overlay Credit trùng.
- Điểm dừng: cần một Android kill-process test và một staging test; host probes không thay thế hai gate này.

### S04 — R00/R04 thực tế và quyết định prefetch

- Owner: instrumentation debug, test harness, báo cáo nghiệm thu; không sửa schema/rules hay tự phát hành.
- Thêm timeline có elapsed từng bước, raw WorkInfo/Worker count và quyết định retry đã phân loại; che token/URL/feedback/tài khoản.
- Chạy unit tests + assemble + lint; đọc lint report thay vì chỉ exit code. Sau đó WorkManager integration, thiết bị thật/emulator và staging theo ma trận R04.
- 20 lần nộp mạng ổn định: không retry giả ở lần đầu; đo p50/p95 từng bước và tổng thời gian. Nếu còn 10–15 giây, chỉ ra bước chậm. Chỉ mở R03 khi có bằng chứng prefetch ảnh hưởng.
- Báo cáo rõ từng gate PASS/FAIL/CHƯA CHẠY; chỉ kết luận hoàn thành sau khi các blocker S01–S03 và gate liên quan đạt.

### S05 — Rà soát lint hiện hữu, tách khỏi bản sửa retry

- Owner: 5 file UI trong bảng lint; không sửa logic thưởng, nộp hoặc tỷ lệ test.
- Với 20 resource diagnostics: kiểm tra đúng ngữ cảnh Compose/callback/coroutine. Dùng resource state theo configuration tại composable rồi truyền vào callback khi phù hợp; coroutine ngoài composable dùng localizer/context được cập nhật đúng vòng đời. Không thay cơ học getString bằng stringResource ở nơi không gọi được composable.
- Với BoxWithConstraints dòng 713: nếu thật sự không dùng constraints, thay bằng Box và giữ modifier, alignment, alpha overlay, nội dung ảnh. Nếu cần constraints, dùng đúng mục đích layout và thêm ca kiểm tra đó; không suppression chỉ để đếm lỗi về 0.
- Test đổi Việt/Anh khi màn đang mở, recreation và thao tác copy/thông báo; kiểm tra ảnh so sánh ở nhiều kích thước màn hình. Rerun lint một lần sau sửa và đọc XML; mục tiêu giải quyết hoặc chứng minh rõ từng Error. 419 warnings phân loại riêng theo ảnh hưởng, không tự biến thành một đợt refactor lớn.
- Gói này là phát hiện bổ sung của full lint; không thay các gate manager/Storage/process-death của S01–S04.

## 5. Prompt bàn giao

> Đọc RECHECK_MANG_YEU_BAO_CAO_2026-10-27.md và kế hoạch gốc. Triển khai tuần tự S01, S02, S03, S04; không sửa đồng thời các gói ngoài phạm vi. Bắt đầu bằng regression test đỏ cho lỗi được giao, rồi sửa production tối thiểu để test xanh. Giữ clientSubmitId, bytes/hash/path, backoff ngoài và idempotency; không nới Storage rules hoặc đổi schema để né lỗi. Mỗi gói báo cáo diff, lệnh/kết quả, test count, bằng chứng và gate chưa chạy. Build/unit xanh không được thay thế WorkManager integration, kill-process hoặc staging. Không commit/push/deploy nếu chưa có yêu cầu.
