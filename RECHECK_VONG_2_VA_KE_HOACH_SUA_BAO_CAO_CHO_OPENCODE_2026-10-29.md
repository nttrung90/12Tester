# Kiểm tra vòng 2 và kế hoạch sửa báo cáo cho OpenCode — 29/10/2026

## 1. Kết luận

**Các lỗi của vòng trước đã được xử lý trong những ca kiểm thử tương ứng, nhưng còn ba nhóm lỗi cần sửa.** Hai probe Android mới và hai probe database mới đều tái hiện được hành vi không đúng.

Đây là lượt kiểm tra độc lập, không sửa mã sản phẩm, không deploy và không tạo APK/AAB. Giữ version `1.0.2`, build `22`. Chưa đo hành vi trên điện thoại bị ảnh hưởng; không khẳng định ba nhóm lỗi này giải thích toàn bộ ca kẹt thực tế.

## 2. Những phần đã tiến bộ

- ViewModel không còn coi request ID null là quyền xóa mọi bản nháp cùng app; comment mới khác snapshot gửi được giữ lại.
- Ca completion thật và bốn probe Android của vòng trước đã được đưa vào test chính thức.
- Request thiếu ảnh không còn nhận Accepted; persist và enqueue được kiểm tra trước khi trả kết quả.
- Route mission khác report cũ đã bị chặn, bao gồm report thường rejected.
- Backend đã thêm backfill/bảo toàn mã legacy trước khi cập nhật, kiểm tra thêm ảnh/hash/rating và đọc lại ledger sau khi giành khóa request/campaign.
- Runner ledger đã được đăng ký trong `npm test`; bảy ca D01–D05, L01–L02 đều qua.

Không hồi quy các điểm này khi xử lý lỗi mới. Khóa mới và lần đọc lại ledger đã hiện diện trong source, nhưng lượt này chưa có kiểm chứng race PostgreSQL nhiều kết nối.

## 3. Các lỗi còn lại đã tái hiện

### V2-01 — P1: Sửa admission bền vững bằng cách chặn luồng giao diện

**Vị trí:** `ReportSubmitManager.kt:207–276`, `:422–440`; đường gọi `CampaignDetailViewModel.startSubmit()` → callback nộp của `CampaignDetailScreen`.

`submit()` hiện là hàm đồng bộ, gọi `persist(request)` trực tiếp rồi `enqueueWorkSync()`. Trong đó có copy ảnh, đọc để tính SHA256, ghi metadata/fsync và chờ `enqueueUniqueWork(...).result.get()`.

**Probe:** bọc Context để ghi nhận luồng truy cập vùng lưu snapshot trong đúng lời gọi `submit()`. Test nhận Accepted nhưng xác nhận I/O chạy ngay trên:

```text
SDK 34 Main Thread @coroutine#150
```

Assertion bảo vệ việc không chạy I/O trên luồng gọi thất bại. Đây là bằng chứng luồng thực thi trong Robolectric, không phải số đo ANR trên điện thoại. Tuy vậy, đường bấm gửi Compose cũng gọi hàm này trực tiếp, nên bộ nhớ chậm hoặc WorkManager DB bận có thể giữ giao diện trong lúc tiếp nhận. Thêm kiểm tra bền vững đã đúng mục tiêu nhưng cách thực hiện này đưa công việc chậm lên UI.

**Yêu cầu sửa:**

1. Chuyển persist/copy/hash/fsync và chờ enqueue sang IO thuộc scope manager. Không chỉ thêm annotation hoặc bọc lời gọi blocking bằng coroutine vẫn chạy Main.
2. Giữ admission bất đồng bộ: suspend/Deferred hoặc sự kiện theo request ID. Chỉ trả/phát Accepted sau khi snapshot và enqueue thực sự được xác nhận.
3. Form giữ trạng thái đang chuẩn bị cho đến admission; chặn bấm trùng và hiển thị lỗi tại form khi bị Blocked. Không quay lại cách trả Accepted trước persist của vòng cũ.
4. UI chỉ chờ kết quả tiếp nhận; rời màn không tự hủy job mà manager đã nhận quyền sở hữu. Xử lý logout/cancellation và việc ảnh nháp có thể bị dọn trong lúc handoff.
5. Đăng ký snapshot/request ownership trước khi completion có thể về. Worker nhanh không được hoàn tất trước khi ViewModel biết request nào thuộc mình.

**Nghiệm thu:** không có disk I/O/blocking wait trên Main; có test gate cho lưu/enqueue chậm để xác nhận UI vẫn phản hồi. Các test missing screenshot, lỗi ghi đĩa/enqueue và chống double tap vẫn đúng; không dùng ngưỡng sleep để giả định đã bất đồng bộ.

### V2-02 — P1: Giữ comment nháp mới nhưng vẫn xóa ảnh đang dùng cho nháp đó

**Vị trí:** `CampaignDetailViewModel.kt:273–315`, đặc biệt `photoMatches` tại `:288`; `PendingReportStore.kt:118–128` và các đường delete/cleanup.

Code mới so sánh từng trường với snapshot. Nếu comment đổi thì giữ comment; nếu đường dẫn ảnh vẫn giống ảnh gửi trước thì vẫn xóa file và đặt prepared/URI thành null. Trong khi đó, nháp mới có thể chủ ý dùng lại ảnh đang chọn. Ngoài ra, `markCompleted()` vẫn xóa `current.preparedPath`, tức file prepared thuộc form, trước cả khi UI xử lý completion.

**Probe có thực thi bước cleanup thật của worker:**

1. Chọn ảnh P và gửi comment A.
2. Trong lúc worker còn chờ, đổi sang comment B, tiếp tục dùng ảnh P.
3. Gọi `PendingReportStore.markCompleted()` như worker thực tế rồi cho WorkManager commit success.
4. Comment B vẫn còn, nhưng `fileExists=false`, `prepared=null`.

Đây là hai điểm xóa độc lập. Chỉ sửa điều kiện trong ViewModel hoặc chỉ sửa `markCompleted()` đều chưa đủ.

**Yêu cầu sửa:**

1. Xác định một revision/ownership của toàn bộ bản nháp được gửi, không chỉ so sánh giá trị từng trường. Completion chỉ được tiêu thụ tài nguyên nếu nháp vẫn đúng snapshot đó.
2. Nếu người dùng đã có nháp mới, giữ các tài nguyên nháp đang dùng, kể cả khi file ảnh giống file của request cũ. Refresh không được tái điền dữ liệu server lên bản nháp còn dirty.
3. Worker/receipt cleanup chỉ dọn file pending do request sở hữu. File prepared của form cần được form/lease quản lý và được cache dọn orphan có giới hạn; không để `markCompleted()` hoặc `delete()` xóa file mà một nháp còn tham chiếu.
4. Rà đồng thời `markCompleted`, `delete`, cleanup khi startup, `onCleared` và đổi ảnh; không chữa một nhánh rồi để nhánh khác xóa file đó.
5. Kiểm tra trường hợp `snapshotPhotoPath=null` và ảnh mới đang chuẩn bị: `null == null` không chứng minh quyền xóa URI/ảnh đang được chọn. Không ép `isPreparingScreenshot=false` từ completion của request khác revision.

**Nghiệm thu:** probe ảnh P qua; thêm nháp mới dùng ảnh khác, ảnh đang chuẩn bị, đổi rating, retry request cũ, completion từ ViewModel khác và restart. Không rò file vô hạn, không làm mất ảnh chưa gửi.

### V2-03 — P1: Fingerprint backfill lấy kết quả lưu trong report thay cho payload thực sự đã gửi

**Vị trí:** migration 057 `:36–81` (backfill), `:224–231` (fingerprint request) và đoạn bảo toàn legacy trước khi thay `client_submit_id`.

Fingerprint mới của request được tính từ tham số đầu vào. Fingerprint legacy/backfill lại tính từ các trường đã được server phân loại, chuẩn hóa hoặc giữ lại trên report. Hai bộ dữ liệu không nhất thiết bằng nhau ngay cả với một lần gửi lại hợp lệ.

**Hai ca đã tái hiện bằng RPC 049 thật rồi áp migration 057 thật:**

- **B01 — mission được tự nhận diện:** request cũ không truyền `p_mission_id`; backend tự gắn M2 vào report. Sau backfill, receipt chứa fingerprint có M2. Gửi lại y hệt request cũ, vẫn không truyền mission ID, bị `CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH`.
- **B02 — bổ sung comment approved:** request cũ chỉ truyền content và client ID, không truyền screenshot/hash; backend giữ ảnh/hash cũ của report. Sau backfill, fingerprint chứa ảnh/hash được giữ lại. Replay y hệt request comment cũ cũng bị `CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH`.

Cả hai request đã commit trước đó; replay không phải một yêu cầu sửa payload. Lỗi này có thể biến retry hợp lệ thành thất bại sau nâng cấp, dù các probe có đủ tham số của vòng trước đều qua.

**Yêu cầu sửa:**

1. Phân biệt receipt được tạo từ request thật với receipt legacy được dựng từ snapshot. Lưu provenance/fingerprint version hoặc contract tương đương; không khẳng định có thể tái dựng raw payload từ report.
2. Với legacy, kiểm tra theo semantics an toàn đã biết: mission được tự gắn khác với mission tường minh; trường bị bỏ trống để giữ ảnh cũ khác với gửi ảnh mới. Không nới lỏng receipt request thật hoặc bỏ hết kiểm tra mismatch.
3. Replay đã commit chỉ trả kết quả/reconcile, không chạy lại mutation, không đổi danh tính nhiệm vụ, không phát sinh Credit/check-in/thông báo. Giữ cả tính chất A → B → replay A không ghi đè B.
4. Áp dụng nhất quán cho backfill lúc migration và backfill trong transaction trước cập nhật. Rà rating mặc định/được giữ nguyên, thumbnail tùy chọn, hash chuẩn hóa và trường hợp comment rỗng.
5. Có phương án cho receipt đã bị backfill sai nếu bản hiện tại từng được deploy. Không đổi hàng loạt fingerprint của receipt thật, không xóa ledger, không sinh ID mới rồi mutation lại để né lỗi.
6. Khi chưa đủ bằng chứng xác minh một legacy payload, dùng trạng thái reconcile có hướng xử lý rõ; không tự chứng thực payload mới, cũng không gắn nhãn mismatch cho mọi retry hợp lệ bị thiếu trường.

**Nghiệm thu:** B01/B02 qua và bảy ca ledger hiện có vẫn qua. Thêm replay sau mission hết hạn/participant completed, sau cập nhật B, sau duyệt và qua ranh giới ngày VN; ảnh/hash/rating mới thực sự khác vẫn bị chặn khi dùng lại ID cũ.

## 4. Phần còn thiếu để kết luận về hiện tượng kẹt trên điện thoại

Phân loại trạng thái chờ mạng/lịch Android so với upload/RPC vẫn cần hoàn thiện theo kế hoạch gốc. Không suy từ các timeout bên trong worker rằng thời gian xếp lịch cũng được giới hạn. Không tự tăng retry để bù lỗi fingerprint hoặc I/O Main.

Chưa có đo p50/p95, kiểm chứng race PostgreSQL nhiều kết nối, log trên thiết bị gặp lỗi hoặc phép thử chuyển app/tắt màn hình trong lượt này. Các mục đó phải được ghi rõ trong nghiệm thu; unit test/DB nhúng không thay thế thử APK mới trên thiết bị.

## 5. Thứ tự sửa cho OpenCode

1. Giữ hai probe Android và hai probe DB mới, chuyển thành regression chính thức với cùng ý nghĩa assertions.
2. Sửa V2-01 và V2-02 cùng thiết kế ownership/admission bất đồng bộ; tránh tạo race completion sớm khi chuyển lại sang IO.
3. Sửa V2-03 bằng thiết kế legacy receipt rõ ràng, bao cả tương thích dữ liệu đã nâng cấp. Giữ khóa theo request và đọc lại ledger đã thêm.
4. Chạy probe mới → các regression của hai vòng trước → toàn bộ unit test và npm test. Kiểm tra migration riêng lẻ và full-schema mirror.
5. Nếu 057 đã được áp dụng ở bất kỳ môi trường nào, tạo migration tiếp theo để chuyển đổi dữ liệu/hàm; sửa riêng file 057 không cập nhật server đã chạy nó. Đồng bộ full schema/test runner.
6. Bảo toàn các thay đổi khác trong workspace. Giữ version `1.0.2`/build `22`; không tự deploy/commit/push hay build APK/AAB khi chưa có chỉ dẫn mới.

## 6. Kết quả và bằng chứng

| Kiểm tra | Kết quả |
|---|---|
| Unit test Android hiện có | **280/280**, 37 suites, 0 failure/error/skipped |
| `npm --prefix supabase/tests test` | Thành công, đã bao runner ledger |
| Các ca ledger D01–D05, L01–L02 | **7/7 PASS** |
| Hai probe Android mới | **2/2 FAIL assertions**, không phải lỗi fixture/compile |
| Hai probe DB B01/B02 mới | **2/2 FAIL**, cùng lỗi `CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH` |
| Migration 057 và full-schema mirror | Khớp sau chuẩn hóa comment/khoảng trắng |
| Hash sáu file sản phẩm trước/sau audit | Không thay đổi |
| Mã sản phẩm được sửa trong lượt audit | Không |
| APK/AAB hoặc production deployment | Không thực hiện |

Các probe mới chạy riêng, không nằm trong tổng 280. Không lấy 280 test xanh để phủ định bốn ca tái hiện mới.

Evidence tại `.tmp/report-overlap-recheck-round2-2026-10-29/`:

- `android-baseline.log`, `baseline-results/`, `npm-test.log`.
- `generate-android-probes.cjs`, `tests/com/ntt/crosstest/audit/ReportRoundTwoProbeTest.kt`, `review.init.gradle`, `android-probes.log`, `android-probes.xml`.
- `generate-database-probes.cjs`, `database-probes.mjs`, `database-probes.log`, `database-results.json`.
- `source-before.csv`, `source-after.csv`, `summary.json`, `schema-mirror.json`.

Lệnh kiểm tra, không đóng gói APK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
node .tmp/report-overlap-recheck-round2-2026-10-29/generate-android-probes.cjs
.\gradlew.bat -I .tmp/report-overlap-recheck-round2-2026-10-29/review.init.gradle :app:testDebugUnitTest --tests '*ReportRoundTwoProbeTest.round2*' --offline --no-daemon
node .tmp/report-overlap-recheck-round2-2026-10-29/generate-database-probes.cjs
node .tmp/report-overlap-recheck-round2-2026-10-29/database-probes.mjs
```

Generator audit dùng fixture hiện tại trong `ReportOverlapTest`/runner ledger; khi thay API, cập nhật fixture hoặc chuyển probe thành test chính thức. Không chạy generator để ghi đè các test đã được chỉnh theo API mới, và không hạ assertions để chấp nhận hành vi lỗi.
