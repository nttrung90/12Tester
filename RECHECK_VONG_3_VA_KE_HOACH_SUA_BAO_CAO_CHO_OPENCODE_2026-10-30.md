# Kiểm tra vòng 3 và kế hoạch sửa tiếp báo cáo — 30/10/2026

## 1. Kết luận

**Vẫn còn ba vấn đề trong luồng gửi/hủy và tương thích receipt.** Đã tái hiện bằng một probe WorkManager và hai probe DB độc lập. Ngoài ra, mã giao diện thay đổi trong lúc audit và lượt biên dịch sau cùng bị chặn bởi import sai ở `NotificationBottomSheet.kt`.

Bản source đầu lượt kiểm tra qua **296/296 unit test, 39 suites**, và `npm test` qua, gồm **9/9 ca ledger**. Không được diễn giải kết quả này thành “source mới nhất đang xanh”: sau khi giao diện tiếp tục thay đổi, lệnh chạy lại dừng ở `compileDebugKotlin`, chưa chạy được test.

Lượt này không sửa mã sản phẩm, không deploy, không build APK/AAB. Giữ version `1.0.2`, build `22`. Chưa thử trên điện thoại hoặc PostgreSQL nhiều kết nối.

## 2. Những phần của vòng trước đã được xử lý

- Admission chuyển thành suspend và persist/enqueue chạy trong `withContext(Dispatchers.IO)`; ca I/O trên luồng gọi của vòng trước qua trong suite chính thức.
- Completion giữ toàn bộ nháp nếu comment/rating/ảnh đã khác snapshot; prepared image có lease và các đường markCompleted/delete kiểm tra lease. Ca giữ ảnh dùng lại của vòng trước qua.
- Migration 059 thêm provenance và nhánh tương thích cho một số receipt backfill; các ca B01/B02 trước đây qua trong runner hiện tại.
- Runner ledger đã nằm trong `npm test`; giữ các khóa request/campaign, kiểm tra lại ledger, guard mission và Accepted sau persist/enqueue.

Cần bảo toàn những phần này. Ba finding dưới đây là phần còn thiếu hoặc lỗi do cách triển khai mới, không phải yêu cầu quay lại phiên bản cũ.

## 3. Cổng biên dịch hiện bị chặn

### C00 — P1: Import `NotificationItem` không tồn tại, thiếu import `UserNotification`

**Vị trí:** `app/src/main/java/com/ntt/crosstest/ui/components/NotificationBottomSheet.kt:71`, `:90`, `:280`.

Lượt chạy lại báo `Unresolved reference 'NotificationItem'`, sau đó là các lỗi `UserNotification` và lỗi suy luận kiểu dây chuyền. File dùng `List<UserNotification>`/tham số `UserNotification`, trong khi model hiện có là `UserNotification` ở `data/Models.kt:622`.

File giao diện này thay đổi trong lúc audit; đồng thời hash `CampaignDetailScreen.kt` khác snapshot đầu lượt. Không quy lỗi import này cho bản sửa báo cáo hay hoàn nguyên phần giao diện của công việc khác.

**Sửa:** đồng bộ import với model thực tế, bỏ import `NotificationItem` sai và dùng đúng `UserNotification`. Không tạo model thứ hai chỉ để hết lỗi. Chạy compile/unit test lại trên snapshot ổn định trước khi chốt nghiệm thu.

Evidence: `.tmp/report-overlap-recheck-round3-2026-10-30/android-cancellation.log`. Lệnh này dừng ở compile; không có kết quả test mới từ lượt này và không được dùng XML còn lại từ lượt trước để báo PASS/FAIL mới.

## 4. Ba finding trong luồng báo cáo

### C01 — P1: Hủy toàn bộ xong nhưng admission đang chạy vẫn enqueue lại bài nộp

**Vị trí:** `ReportSubmitManager.kt:208–281`, `:332–341`; đường gọi từ coroutine của màn báo cáo.

`submit()` dùng `withContext(IO)` nhưng vẫn là con của coroutine gọi nó. Bước persist/enqueue chưa phải admission job do scope manager quản lý. `cancelAllPendingWork()` chỉ hủy các child của scope manager rồi hủy work theo tag và xóa state.

**Tái hiện có điều khiển thứ tự, không dựa vào tốc độ mạng:**

1. Bắt đầu submit, giữ lại tại bước persist trước enqueue.
2. Gọi `cancelAllPendingWork()` và chờ nó hoàn tất như đường logout.
3. Cho persist chạy tiếp.
4. Submit vẫn trả `Accepted(requestId=cancel-admission)` và để lại work **ENQUEUED** sau khi đã hủy toàn bộ.

Đây là lỗi lifecycle đã tái hiện. Worker vẫn có kiểm tra user; probe chưa gửi lên backend và không chứng minh bypass kiểm tra tài khoản. Vấn đề đã thấy là work/state/request cũ có thể xuất hiện lại sau bước hủy, bao gồm thời điểm logout đang dọn pending files.

**Yêu cầu sửa:**

- Quản lý admission job trong scope/session lifecycle của manager; caller chỉ chờ kết quả. Hủy coroutine chờ của UI và hủy admission do logout là hai thao tác khác nhau, cần quy định rõ.
- Dùng session generation/cancellation barrier để không thể claim/persist/enqueue/publish state của phiên đã bị hủy sau khi `cancelAllPendingWork()` hoàn tất.
- Hủy và drain admission đang chạy trước khi chốt cancel work/clear store; không chỉ hủy các observer đã được tạo sau enqueue.
- Kiểm tra cancellation/generation sau I/O và ngay trước enqueue. Giữ claim cleanup trong finally; không bắt cancellation rồi trả Blocked/phát FAILED của phiên cũ lên phiên mới.
- Giữ I/O ngoài Main và giữ Accepted sau persist/enqueue. Không chữa bằng cách trở lại enqueue đồng bộ trên UI hoặc bỏ bảo đảm lưu bền vững.
- Rà trạng thái PREPARING/bấm trùng: hiện stage chỉ được publish sau enqueue, nên UI có khoảng thời gian gọi submit lần hai trước khi lần một được nhận. Yêu cầu bị AlreadyInFlight không được xóa ownership của bài đầu.

**Nghiệm thu:** probe giữ persist → cancel → thả persist không còn tạo work hoạt động hoặc state của phiên cũ. Thêm logout trước/sau copy metadata, trước/sau enqueue, đổi user, rời màn khi đang tiếp nhận và caller cancellation. CancellationException là kết quả hợp lệ khi hủy; test phải giữ bất biến “không có late enqueue/state” thay vì bắt buộc trả một kiểu thành công.

### C02 — P2: Migration 059 đổi receipt chính xác thành legacy và nới sai kiểm tra payload

**Vị trí:** `059_report_idempotency_legacy_provenance.sql:15–21`, nhánh xử lý ledger `:107–143` và đoạn tương ứng sau khóa.

```sql
update public.report_submission_receipts
set provenance = 'legacy_backfill'
where provenance is null or provenance = 'exact_v1';
```

Điều kiện này đổi mọi receipt đã được xác nhận `exact_v1`, không chỉ receipt dựng từ snapshot.

**Probe DB dùng migration thật:**

1. Tạo request bằng implementation 057, xác nhận receipt là `exact_v1`.
2. Áp migration 059.
3. Receipt thành `legacy_backfill`.
4. Replay cùng ID nhưng bỏ ảnh/hash đã có trong payload gốc vẫn nhận `success=true`, thay vì mismatch theo hợp đồng receipt chính xác.

Probe không cho thấy report bị mutation hoặc Credit bị cộng trùng. Lỗi là làm mất provenance đã biết và chấp nhận payload khác dưới cùng ID, trái với mục tiêu phân biệt exact/legacy.

**Yêu cầu sửa:**

- Giữ receipt có provenance chính xác đã biết. Không hạ toàn bộ `exact_v1` xuống legacy khi nâng cấp hoặc chạy lại migration.
- Với dữ liệu cũ chưa có provenance, không thể giả định tất cả là exact hoặc tất cả là backfill. Tách `unknown`/đường reconcile an toàn hoặc quy tắc phân loại có bằng chứng; không giả tạo sự chắc chắn.
- Receipt exact luôn kiểm tra raw payload theo đúng fingerprint version. Chỉ áp phép tương thích legacy cho receipt được xác nhận thuộc loại đó.
- Kiểm tra mọi nhánh insert receipt trong 057/059 khai báo provenance/version phù hợp, kể cả approved comment, fallback legacy và bảo toàn ID trước cập nhật.
- Nếu 059 đã triển khai và provenance đã bị ghi đè, cần kế hoạch phục hồi có giới hạn rõ; không suy ngược lịch sử không còn dữ liệu để chứng minh và không xóa ledger.

**Nghiệm thu:** tạo exact trước upgrade và sau upgrade, áp/rerun migration, vẫn giữ strict payload; các ca legacy hợp lệ vẫn replay được; mismatch thật về content/ảnh/hash/rating/thumbnail/mission không bị hợp thức hóa.

### C03 — P2: Replay comment legacy của report approved khác 5 sao vẫn bị mismatch sai

**Vị trí:** nhánh `legacy_backfill` của migration 059, đặc biệt `coalesce(p_rating,5)` trong fingerprint tại `:133` và `:267`; bản tương ứng ở migration 057.

**Tái hiện:** tạo report 3 sao bằng RPC 049 → duyệt report → gửi comment bổ sung chỉ có content/client ID (RPC mặc định `p_rating=5`, nhưng nhánh approved giữ rating thực tế là 3) → áp 057 và 059 → replay y hệt yêu cầu comment đã commit. Server trả `CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH`.

Nhánh tương thích đã thay trường ảnh/hash/mission bị bỏ trống bằng dữ liệu snapshot, nhưng vẫn dùng rating đầu vào 5. Snapshot backfill dùng rating đã khóa 3. Đây vẫn là trường hợp “payload gốc khác kết quả được giữ lại”, không phải tester sửa rating trong một retry.

**Yêu cầu sửa:**

- Xử lý legacy theo semantics thao tác: bổ sung comment approved không sửa rating/ảnh/mission. Lưu đủ dữ liệu canonical cần thiết của receipt legacy hoặc có đường reconcile rõ khi không tái dựng được.
- Không đổi payload client trong lần retry để né mismatch và không nới điều kiện của receipt exact. Không dùng logic này cho nộp mới/sửa pending/nộp lại rejected vốn có thể thay rating/ảnh.
- Sửa cả nhánh trước và sau khóa, cùng các hàm/migration mirror liên quan.
- Bổ sung ma trận rating 1–5, tham số rating bị bỏ trống hoặc tường minh, ảnh/hash/thumbnail thiếu, comment-only, report đã cập nhật B và receipt replay sau hết hạn.

**Nghiệm thu:** probe report 3 sao replay comment qua, rating và ảnh giữ nguyên, không phát sinh thưởng/notification lần hai; mismatch thật vẫn bị chặn và A → B → replay A không ghi đè B.

## 5. Ca thăm dò đã loại khỏi kết luận lỗi sản phẩm

Có chạy thêm test gọi `PreparedScreenshotCache.cleanup()` trên file quá 48 giờ đang có lease; API này xóa file và test thất bại. Tuy nhiên, đối chiếu call site hiện tại cho thấy cleanup prepared cache chỉ được gọi trong `Application.onCreate`, trước khi form trong process mới giữ lease.

Vì chưa chứng minh được đường sản phẩm gọi cleanup khi lease đang sống, **không xếp ca này thành lỗi đang xảy ra hay mục bắt buộc sửa của vòng này**. Giữ log thăm dò riêng để không nhầm một assertion API với một lỗi người dùng có thể gặp. Probe C01 được phân tích riêng, không lấy số test đỏ làm số lỗi sản phẩm.

## 6. Kế hoạch triển khai

1. **C00:** sửa cổng compile trên source giao diện hiện tại, phối hợp với thay đổi đang có; không hoàn nguyên file của công việc khác.
2. Chuyển probe C01/P01/P02 thành regression chính thức. Nếu sửa API, cập nhật fixture nhưng giữ bất biến chống late enqueue, strict provenance và replay hợp lệ.
3. **C01:** hoàn thiện scope admission, cancellation/session barrier và ownership khi PREPARING. Giữ các regression ảnh nháp và I/O Main đã qua.
4. **C02/C03:** sửa receipt provenance và canonical legacy comment; kiểm tra upgrade 057 → bản mới, 059 → bản mới và rerun. Hiện repo có đến migration **060**; kiểm tra số tiếp theo thực tế và dùng migration mới nếu 059 đã từng áp dụng. Đồng bộ full schema.
5. Đăng ký các ca mới vào `npm test`. Runner hiện tại B01/B02 áp 049 rồi 057; chúng không thay thế việc áp migration 059 thật trong các ca mới. Với migration chứa BEGIN/COMMIT, chạy ngoài transaction cô lập mỗi test hoặc dùng DB mới cho từng ca để không làm sai rollback fixture.
6. Chạy compile/unit test → probe lỗi → toàn bộ unit test và npm test trên source ổn định. Snapshot hash trước/sau để không báo kết quả của một phiên bản khác với mã bàn giao.
7. Tiếp tục phần progress/reconcile cho tình huống chờ mạng/lịch Android theo kế hoạch gốc; chưa có số đo thiết bị nên không tự tăng retry hoặc khẳng định hết kẹt thực tế.
8. Giữ version `1.0.2`, build `22`, bảo toàn thay đổi chưa commit. Không tự deploy, commit/push hay tạo APK/AAB trong lượt sửa nếu chưa có yêu cầu mới.

## 7. Kiểm chứng và giới hạn

| Kiểm tra | Kết quả |
|---|---|
| Unit test ở snapshot đầu lượt | **296/296**, 39 suites, 0 fail/error/skipped |
| npm test | Thành công, gồm **9/9 ca ledger** |
| Probe C01 Android | FAIL assertion: hủy xong vẫn Accepted và ENQUEUED |
| Probe P01 DB | FAIL: exact_v1 → legacy_backfill, payload khác được replay success |
| Probe P02 DB | FAIL: comment legacy approved 3 sao bị mismatch |
| Probe cache thăm dò | FAIL nhưng loại khỏi finding sản phẩm vì chưa có call path tương ứng |
| Lượt chạy Android sau cùng | **Dừng trước test**: lỗi import NotificationBottomSheet |
| Hàm submit trong migration 059 và full schema | Khớp sau chuẩn hóa comment/khoảng trắng |
| Mã report manager/VM/store/lease và SQL được theo dõi | Hash không đổi; CampaignDetailScreen đổi do công việc khác trong lúc audit |
| Sửa mã sản phẩm / APK / production | Không thực hiện |

Không khẳng định source mới nhất qua 296 test. Baseline xanh, probe logic và compile fail cuối lượt là ba loại bằng chứng khác nhau. Không có phép thử điện thoại, p50/p95 hoặc race nhiều kết nối PostgreSQL trong lượt này.

Evidence tại `.tmp/report-overlap-recheck-round3-2026-10-30/`:

- `android-baseline.log`, `baseline-results/`, `npm-test.log`.
- `generate-android-probes.cjs`, `tests/.../ReportRoundThreeProbeTest.kt`, `review.init.gradle`, `android-probes.log`, `android-probes.xml`: lượt có probe C01 và ca cache thăm dò chạy thật.
- `android-exploratory.log/xml`: bản sao giữ nguyên lượt probe đầu.
- `android-cancellation.log`: lần thử chạy riêng sau đó, dừng ở compile; không có XML mới.
- `generate-database-probes.cjs`, `database-provenance-probe.mjs`, `database-rating-probe.mjs`, các log và `*-results.json`.
- `source-before.csv`, `source-after.csv`, `schema-mirror.json`, `summary.json`.

Lệnh sau khi sửa cổng compile, không build APK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
node .tmp/report-overlap-recheck-round3-2026-10-30/generate-android-probes.cjs
.\gradlew.bat -I .tmp/report-overlap-recheck-round3-2026-10-30/review.init.gradle :app:testDebugUnitTest --tests '*ReportRoundThreeProbeTest.round3CancelAllMustPreventLateEnqueueFromAdmission' --offline --no-daemon
node .tmp/report-overlap-recheck-round3-2026-10-30/generate-database-probes.cjs
node .tmp/report-overlap-recheck-round3-2026-10-30/database-provenance-probe.mjs
node .tmp/report-overlap-recheck-round3-2026-10-30/database-rating-probe.mjs
```

Generator dùng fixture hiện tại của repository. Khi chuyển test thành chính thức hoặc đổi API, cập nhật fixture/handling cancellation có chủ đích; không chạy generator ghi đè bản test đã chuyển đổi và không hạ assertions để làm xanh.
