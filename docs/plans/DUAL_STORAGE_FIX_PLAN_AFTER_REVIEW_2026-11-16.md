# Kiểm tra bản sửa Gemini và kế hoạch khắc phục Dual Storage

Ngày kiểm tra: 16/11/2026, giờ Việt Nam. Phạm vi: working tree tại `F:\AI\12Tester`, theo kế hoạch `docs/plans/DUAL_STORAGE_REPORT_SCREENSHOTS_PLAN_2026-11-15.md` và các lựa chọn người dùng đã chốt.

**Kết luận: chưa đạt nghiệm thu.** Có nền tảng upload hai nguồn, metadata, reservation và worker, nhưng vẫn có lỗi ảnh hưởng trực tiếp đến xem ảnh, điều phối 95%/90% và xóa vật lý tối đa 10 ngày. Báo cáo `audit/dual-storage-2026-11-15/HANDOVER.md` ghi “PRODUCTION READY” chưa được bằng chứng hiện tại hỗ trợ.

Đây là báo cáo kiểm tra và kế hoạch sửa tiếp. Lượt kiểm tra này chỉ thêm tài liệu, phép kiểm tra độc lập và kết quả trong `audit/dual-storage-review-2026-11-16/`; không sửa mã sản phẩm, migration, dữ liệu thật, phiên bản hoặc triển khai production. Không suy ra trạng thái database đang chạy từ source local.

## 1. Chính sách giữ nguyên

- Google Cloud: ảnh cũ và client cũ tiếp tục được hỗ trợ; giữ chính sách lưu trữ hiện có.
- Ảnh mới đã nén: ưu tiên Supabase riêng tư; mỗi ảnh ở một nguồn, không bắt buộc sao chép cả hai.
- Mẫu số: toàn bộ hạn mức Storage của gói trong tổ chức, tính đủ các project/bucket cùng chia sẻ hạn mức.
- Chuyển upload mới sang Google ở 95%, hoặc khi upload tiếp theo không còn đủ chỗ dưới ngưỡng. Sau khi chuyển, chỉ tự trở lại Supabase khi dưới 90%.
- Supabase ngừng xem và bắt đầu dọn từ tuổi 216 giờ; mục tiêu xóa vật lý trước tuổi 240 giờ, tính từ thời điểm object thực được Storage tạo. Thumbnail không sống lâu hơn ảnh chính.
- Giữ nội dung báo cáo, duyệt, hash, điểm, check-in, receipts và nhiệm vụ ngày. Không di chuyển ảnh hết hạn sang Google để kéo dài thời gian lưu.

## 2. Bằng chứng đã chạy

| Kiểm tra | Kết quả của lượt kiểm tra này | Giới hạn |
|---|---|---|
| `node supabase/tests/run-dual-storage-quota-test.mjs` | 8 kịch bản PGlite có sẵn đạt; nạp đủ 30 migrations | Các test không chứng minh upload/xóa Storage thật hoặc cạnh tranh PostgreSQL |
| Backend cleanup Supabase, đối soát dung lượng, cleanup Google và gateway theo lệnh bên dưới | Tổng runner báo 28 test, 16 đạt, 12 fail | 11 ca HTTP của gateway không kết nối được `127.0.0.1` do `EACCES`; số fail gồm cả suite tổng. Đây là rào cản môi trường, không kết luận 12 lỗi nghiệp vụ gateway |
| Kiểm tra độc lập `review-probes.mjs` | 14 assertions: 1 đạt, 13 fail theo hành vi yêu cầu | PGlite cô lập và Storage giả lập; 13 assertion fail là bằng chứng những tình huống chưa đáp ứng, không phải 13 sự cố production đã quan sát |
| `node tools/ai-sql-index.mjs check` | `fresh:true`, 30 migrations, 253 functions | Chỉ xác nhận chỉ mục khớp source |
| Android 3 lớp liên quan | Chưa chạy lại thành công | Ban đầu thiếu `JAVA_HOME`; sau khi chọn JBR có sẵn, Gradle bị từ chối ghi lock trong `C:\Users\nguye\.gradle` |
| PostgreSQL nhiều kết nối | NOT RUN | Không có `TEST_DATABASE_URL`; runner hiện tại còn chưa triển khai assertions cạnh tranh thật |
| Storage thật, scheduler, CDN, phiên bản Android đang phát hành | Chưa xác minh | Không có bằng chứng triển khai/smoke test production trong lượt này |

Lệnh backend đã chạy:

```powershell
node --test backend/storage-cleanup/cleanup-supabase-storage.test.mjs backend/storage-cleanup/reconcile-org-storage.test.mjs backend/storage-cleanup/cleanup-reports-storage.test.mjs backend/media-gateway/media-gateway-server.test.mjs
```

Bằng chứng độc lập có thể tái lập:

```powershell
node audit/dual-storage-review-2026-11-16/review-probes.mjs
```

Script dùng database PGlite mới trong bộ nhớ, tự rollback từng tình huống SQL và chỉ gọi Storage giả lập; không đọc URL database production. Hiện exit code 1 là có chủ đích vì assertions đúng yêu cầu vẫn thất bại. Kết quả chi tiết ở `review-probe-results.json`; fingerprint source được kiểm tra ở `source-snapshot.json`, cùng thư mục audit.

## 3. Phát hiện phải sửa

P0: chặn bật tính năng vì vi phạm chính sách truy cập, quota hoặc hạn xóa. P1: phải sửa trước nghiệm thu toàn bộ.

### F01 — P0: hai đường đọc chưa hoàn chỉnh

- `app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt:422` phân giải URL, nhưng `:445` chỉ gắn Bearer khi bật gateway. Cờ gateway mặc định `false` tại `util/PrivateScreenshotDiskCache.kt:51`; source không có nơi gán `customHttpClient` bổ sung xác thực. URL mới do `SupabaseStorageHelper` trả là `/storage/v1/object/authenticated/...`, vì vậy đường đọc trực tiếp hiện chưa có header phiên Supabase.
- `backend/media-gateway/server.mjs:18` import và khởi tạo `FirebaseStorageAdapter`; chưa nối `SupabaseStorageAdapter`/`DualStorageAdapter` vào server thật. Chỉ có class adapter mới không làm gateway đọc được Supabase.
- Adapter Supabase chưa parse nhất quán URL `/public/` dù SQL validator chấp nhận nó. Dispatch dùng tìm chuỗi `supabase` hoặc `report-screenshots`, không dựa trên provider/host đã xác thực.

Đây là kết luận từ đường gọi trong source; chưa phải đo lỗi trên điện thoại production. Cần kiểm tra cả bật/tắt gateway trước khi nói “ảnh mới hiển thị bình thường”.

### F02 — P0: quyền đọc trực tiếp bỏ qua hạn 9 ngày và trạng thái khóa

`supabase/migrations/091_dual_storage_report_screenshots.sql:161` cho `owner = auth.uid()` hoặc người có quan hệ đọc; không kiểm tra `view_expires_at`, trạng thái xóa hay tài khoản khóa. Chặn tại RPC gateway không bảo vệ đường download authenticated trực tiếp.

Probe `RLS-EXPIRY`: ở role `authenticated`, tác giả vẫn SELECT được object có hạn xem đã qua. Quyền schema/table tối thiểu chỉ được cấp trong fixture để kiểm tra đúng policy; không thay production.

### F03 — P0: vẫn nhận URL không thuộc dự án và ảnh chưa từng upload

- Validator `091:239` trở đi chấp nhận mọi hostname có đường dẫn Storage/bucket hợp mẫu, không khóa host/project thực.
- Finalize tại `091:1291` và `:1326` chỉ UPDATE ledger “nếu có”; không bắt buộc reservation hợp lệ, tồn tại object, kích thước thật, chủ sở hữu hay thumbnail đúng cặp. Ghép bằng hậu tố filename với `LIKE` có thể trúng nhiều path. Client 409 được coi thành công ngay ở `SupabaseStorageHelper.kt:112` mà chưa xác minh file cũ.

Probe `URL-HOST`: URL trên `untrusted.invalid` được chấp nhận. Probe `SUBMIT-PROVENANCE`: báo cáo Supabase thành công dù không có reservation và không có object. Đường Google cũ vẫn cần tương thích; không áp điều kiện reservation mới hàng loạt lên dữ liệu/client Google cũ.

### F04 — P0: tuổi file bị đặt lại khi nộp báo cáo

`091:1069`, `:1267`, `:1295` và `:1330` lấy thời điểm submit làm thời điểm tạo/hạn ảnh. Không lấy `storage.objects.created_at` thật. File đã tồn tại trước khi nộp có thể sống lâu hơn 240 giờ kể từ upload; thumbnail upload muộn cũng chưa được đối soát tuổi thật.

Probe `OBJECT-CLOCK`: object đã tạo 2 ngày trước nhưng ledger được đặt “mới tạo”, sai lệch 172.800 giây. Cần chặn hoặc xử lý rõ pending đã hết hạn; không gia hạn tuổi file khi retry/finalize.

### F05 — P0: dung lượng bị tính thiếu giữa hai lần đối soát

`091:1299`/`:1334` đưa `reserved_bytes` về 0, sau đó `:1349` giảm R; U không được ghi nhận object mới trong cùng quy trình. Trước lần reconcile sau, tổng U+R mất phần đã upload và có thể cấp thêm quá mốc 95%. Kích thước thumbnail còn bị ước lượng cố định 150.000 byte tại `:517`; không xác minh byte thật hoặc ngăn client báo thấp.

Probe `QUOTA-FINALIZE`: U ban đầu 100.000.000 byte; thêm object 500.000 byte và finalize xong, U+R vẫn 100.000.000 thay vì ít nhất 100.500.000. Đây là lỗi tuần tự đã tái hiện, không cần cạnh tranh mới xảy ra.

### F06 — P0: xóa lỗi hoặc chỉ dry-run có thể làm file không bao giờ được thử lại

`get_expired_report_media_for_cleanup` ở `091:2388` nhận `p_lease_seconds` nhưng không lưu thời hạn lease; đổi trạng thái sang `deleting` tại `:2417`, trong khi lần sau chỉ lấy `finalized` hoặc `reserved/uploaded`. Lỗi Storage, lỗi ghi DB sau xóa, crash worker đều làm bản ghi mất khỏi danh sách retry. Worker dry-run vẫn gọi chính RPC đổi trạng thái.

- `CLEANUP-RETRY`: lần lấy thứ hai sau khi mô phỏng quá hạn lease trả rỗng.
- `CLEANUP-DRY-RUN`: chỉ chạy thử nhưng trạng thái thực đã đổi từ `finalized` sang `deleting` trong fixture.
- `ORPHAN-EXPIRED`: allocator `091:501` đổi reservation thành `expired`, trong khi cleanup không chọn trạng thái đó; object mồ côi bị bỏ qua.

Chỉ lỗi này đã đủ ngăn kết luận bảo đảm mục tiêu xóa trước 10 ngày. Chưa có đường inventory Storage thật để tìm mọi object không còn ledger.

### F07 — P0: cleanup chưa tách nguồn và chưa xác nhận đủ từng file

- Nhánh orphan tại `091:2409` không giới hạn `provider='supabase'`; worker lại luôn dùng bucket Supabase cấu hình, bỏ qua bucket/provider ứng viên.
- `cleanup-supabase-storage.mjs:173` chuyển path từ ứng viên thẳng vào remove, chưa kiểm tra riêng từng path theo prefix/cặp ảnh hợp lệ.
- `:191`–`:204` coi remove không có `error` là xóa xong cả cặp, không kiểm tra kết quả từng object hoặc xác nhận file vắng. Không có bằng chứng thumbnail đã mất trong trường hợp trả kết quả một phần.

Các probe `CLEANUP-PROVIDER`, `DELETE-PARTIAL`, `DELETE-PATH` tái hiện các hành vi trên bằng SQL/ứng viên giả lập. Với `DELETE-PATH`, đầu vào ứng viên cố ý sai để kiểm tra lớp bảo vệ worker; không khẳng định hiện có avatar production nằm trong ledger.

### F08 — P1: xác nhận xóa chưa idempotent, định danh chưa đủ

`record_report_media_deleted` ở `091:2463` chỉ tìm theo `upload_id`, trong khi unique key là `(user_id, upload_id)`. Hàm không kiểm tra trạng thái `deleted` trước khi trừ dung lượng tại `:2478`.

Probe `DELETE-IDEMPOTENT`: U=1.000, xóa 100 và gửi xác nhận lại, U còn 800 thay vì 900. Hai tài khoản dùng cùng upload_id còn có nguy cơ cập nhật nhầm bản ghi. Dùng khóa media/ledger UUID duy nhất và token lease; không để worker tự cung cấp số byte giải phóng tùy ý.

### F09 — P0/P1: số liệu tổ chức và cấu hình chưa đủ tin cậy

- `reconcile-org-storage.mjs:52` mặc định 1 GiB dù chưa xác minh gói thực; handover dùng `SUPABASE_ORG_CAPACITY_BYTES` và `SUPABASE_MANAGEMENT_API_TOKEN`, còn code/runbook dùng `SUPABASE_TOTAL_CAPACITY_BYTES` và `SUPABASE_MANAGEMENT_TOKEN`.
- `:28` gọi `/v1/projects/{ref}/usage/storage`; chưa có bằng chứng hợp đồng endpoint/schema này hoạt động thực tế. Tài liệu API chính thức được kiểm tra chưa đủ để xác nhận endpoint đó; không kết luận API chắc chắn tồn tại chỉ vì test dùng `fetchProjectFn` giả.
- `:40` mặc định dữ liệu thiếu thành 0; `:120` có thể đánh dấu hợp lệ sau phép cộng byte âm/không hợp lệ. `METRICS-VALIDATION` chứng minh byte -1 vẫn được coi hợp lệ.
- Danh sách project cấu hình bằng tay, chưa đối chiếu danh mục tổ chức đầy đủ. Metric hợp lệ nhưng `last_reconciled_at=NULL` chưa được coi là không đáng tin ở `091:525`.
- `CampaignRepository.kt:1241` bắt mọi lỗi reservation rồi tự trả “success=true, gcs”, kể cả lỗi quyền/khóa hoặc mất kết nối. Điều này trái chính sách chỉ chuyển do dung lượng và quyết định có xác thực của server; không khẳng định Google Rules sẽ cho mọi upload đó thành công.

### F10 — P1: bằng chứng kiểm thử và sẵn sàng triển khai đang quá mức

`supabase/tests/run-dual-storage-quota-test.mjs` phần `runPostgresConcurrencyTestsIfAvailable` chỉ mở một connection rồi release, chưa chạy upload cạnh tranh, transaction chồng lấn hoặc assertion. Thậm chí nếu cung cấp TEST_DATABASE_URL, hiện vẫn chưa có kiểm thử cạnh tranh thực. Lỗi kết nối bị log rồi không ném lại, có thể kết thúc exit 0.

Các Android test mới chủ yếu giả lập upload; backend test mới chưa bao phủ retry/partial/delete-ack. Các test đạt vẫn hữu ích nhưng không chứng minh các mệnh đề “không thể vượt quota”, “xóa đúng SLA” và “production ready” trong handover.

## 4. Kế hoạch sửa theo thứ tự

### Bước 0 — Chốt baseline và giữ khả năng quay về Google

1. Đọc lại `AGENTS.md`, bản đồ mã, chỉ mục SQL; ghi fingerprint, thay đổi đang có và trạng thái migration thực tế đã triển khai.
2. Chưa bật Supabase upload rộng khi còn F01–F09. Nếu tính năng đã bật, chuẩn bị thay đổi cấu hình có kiểm chứng để chuyển upload mới về Google; giữ đường đọc và dọn Supabase hiện có. Lượt kiểm tra này không tự thực hiện rollout/rollback.
3. Chuẩn bị test database/bucket riêng; tránh mọi lệnh reset hay backfill trên database đang dùng.
4. Nếu 091 đã triển khai hoặc chưa xác định chắc chắn, dùng migration tiếp nối (số kế tiếp theo source tại thời điểm sửa), không sửa lịch sử 091 để thay production. Cập nhật chỉ mục sau thay đổi migrations.

### Bước 1 — Sửa sổ media, tuổi thật và quyền

1. Xác định object bằng provider, bucket, path chuẩn hóa và ledger UUID; host/project lấy từ cấu hình server allowlist, không dò chuỗi URL. Giữ Google legacy validator/overload còn dùng.
2. Với Supabase mới, finalize bắt buộc reservation đúng user/campaign/attachment, chưa tombstone, còn hợp lệ; xác minh object thực, metadata kích thước và MIME. Ghép chính xác khóa/path; bỏ suffix LIKE. Kiểm tra 409 bằng fingerprint/kích thước tin cậy trước coi thành công.
3. Đọc thời điểm Storage tạo ảnh chính; thiết lập duy nhất các mốc +216h/+240h và không đặt lại khi submit/retry. Thumbnail cùng hạn ảnh chính. Đối soát cả file chưa submit, file thiếu ledger và thumbnail mồ côi.
4. RLS SELECT và mọi đường cấp quyền đều kiểm tra đúng hạn của attachment, trạng thái xóa và khóa tài khoản. RLS INSERT chỉ đúng object đã cấp quyền, trong thời gian quyền upload còn sống; giới hạn thực tế không vượt phần giữ chỗ. Không hồi sinh path tombstone.
5. Kiểm tra chính sách từng attachment trong báo cáo trộn Google/Supabase; không ẩn cả báo cáo chỉ vì một attachment Supabase hết hạn.

Nghiệm thu: F02–F04 hết tái hiện; người ngoài không đọc được, tác giả/owner/admin hợp lệ đọc được trước hạn và bị từ chối tại đúng 216h. URL host giả và Supabase file không tồn tại bị từ chối. File đã upload 2 ngày không được nhận thêm 10 ngày mới.

### Bước 2 — Sửa kế toán dung lượng và quyết định nguồn

1. Xác minh gói, hạn mức byte thật và đủ project thuộc tổ chức. Cấu hình thiếu/sai phải fail-safe, không tự giả định gói 1 GiB. Thống nhất tên biến và lịch scheduler trong code/runbook/handover.
2. Chọn nguồn số liệu được hỗ trợ và kiểm chứng schema bằng staging. Có thể dùng truy vấn metadata read-only từng project với quyền server thích hợp; không dùng chỉ số trung bình kỳ thanh toán làm byte hiện tại. Đối chiếu inventory project, phát hiện project thêm/bớt.
3. Kiểm tra byte là số nguyên không âm và trong giới hạn biểu diễn; dữ liệu thiếu, âm, NaN, thiếu project, thời điểm NULL/quá cũ đều không hợp lệ. Không thay dữ liệu thiếu thành 0 rồi đánh dấu hợp lệ.
4. Giữ invariant không mất hoặc đếm trùng U+R khi reserve/upload/finalize/reconcile/delete. Một phương án: sau finalize vẫn giữ phần dung lượng “đã upload, chưa được snapshot đối soát ghi nhận”; chỉ giải phóng phần giữ đó khi snapshot xác nhận object tương ứng đã nằm trong U. Cập nhật snapshot/ack trong cùng transaction có khóa, không chỉ cộng U thủ công rồi reconcile đếm thêm lần nữa.
5. Cấp chỗ theo byte ảnh chính và thumbnail thực sau nén, có cơ chế hạn chế/xác minh server. Reservation hết hạn không được hoàn dung lượng nếu object vẫn còn hoặc quyền upload vẫn có thể tạo file muộn.
6. Quyết định provider/idempotency được khóa và kiểm tra lại sau chờ khóa; cùng upload ID không được đếm hai lần hoặc bị đổi provider bởi hai request cạnh tranh. Lưu lựa chọn bền vững trên client.
7. Android không tự đổi sang Google cho mọi exception. Quyền/khóa báo đúng lỗi; mạng/5xx retry hàng đợi. Chỉ dùng fallback Google theo quyết định server đã xác thực hoặc cơ chế tương thích legacy được giới hạn rõ.

Nghiệm thu: ngưỡng dưới/đúng/trên 95%, đúng 90% và dưới 90%, upload kế tiếp vượt mốc, snapshot thay đổi, số liệu thiếu và nhiều tester cùng gửi. `QUOTA-FINALIZE`, `DELETE-IDEMPOTENT`, `METRICS-VALIDATION` phải đạt. Không hứa ngăn mọi project ngoài bộ điều phối ghi vượt 95% giữa hai lần đo; báo rõ phạm vi kiểm soát.

### Bước 3 — Sửa cleanup trước khi bật upload

1. Lease phải có `lease_token`, `lease_expires_at`, số lần thử, thời điểm retry và lỗi cuối. `deleting` quá lease phải được lấy lại. Worker phải xác nhận đúng ledger UUID/token và xử lý ack idempotent.
2. Lấy ứng viên chỉ Supabase đúng bucket/prefix. Bao phủ `expired`, object không gắn báo cáo và inventory lệch ledger; không để thao tác expire của allocator làm file biến mất khỏi hàng dọn.
3. Dry-run dùng RPC/read path không mutate; không claim lease, không đổi trạng thái, không giảm quota và không gọi xóa.
4. Kiểm tra từng object path, bucket, provider trước Storage API. Xác nhận full và thumbnail riêng, retry phần chưa hoàn tất. 404/NotFound rõ ràng có thể coi vắng; 403/429/5xx và phản hồi thiếu không phải chứng cứ cả cặp đã xóa.
5. Chỉ ghi `deleted_at` và giải phóng dung lượng đã hạch toán đúng một lần sau xác nhận. Không DELETE metadata Storage thay việc xóa qua API.
6. Phân trang và drain nhiều batch trong ngân sách một lần chạy, tránh giới hạn 50 bản ghi/mỗi 5 phút làm backlog quá hạn. Worker lỗi trả trạng thái/exit code phù hợp cho scheduler phát hiện.
7. Quét mỗi 5 phút, có cảnh báo heartbeat/backlog/tuổi file gần 240h và worker dự phòng độc lập. Kiểm chứng scheduler và đường cảnh báo bằng tình huống lỗi có kiểm soát.

Nghiệm thu: mọi probe CLEANUP/ORPHAN/DELETE đạt; crash sau claim hoặc sau remove vẫn hồi phục được; dry-run không gây tác dụng phụ; xóa một phần không xác nhận toàn cặp; không đụng Google/icon/avatar. Thử thực tế trên bucket thử và xác minh file thật đã vắng trước hạn tăng tốc.

### Bước 4 — Nối upload và đọc/cache Android/backend

1. Khởi tạo dual adapter trong server thật. Authority trả provider/bucket/path đã xác thực; parser dùng chung hợp đồng, bao phủ URL cũ hợp lệ và URL mới. Không route theo chuỗi tên file.
2. Chốt một đường đọc riêng tư hoạt động được: gateway có auth và dual adapter, hoặc authenticated Storage với token chỉ gắn cho host Supabase đã allowlist. Nếu dùng signed URL, cấp phía server sau kiểm tra quyền, TTL giới hạn theo hạn ảnh, không lưu làm định danh lâu dài.
3. Kiểm tra cả chế độ gateway bật/tắt được hỗ trợ; cấu hình không đầy đủ phải có hành vi rõ ràng. Tuyệt đối không gắn token cho host do payload bên ngoài tự đặt.
4. Truyền expiry/provider theo đúng attachment qua preview, zoom, so sánh và hash. Kiểm tra trước cache, sau mạng và khi màn hình đang mở đi qua hạn; xóa cache phiên khi đổi tài khoản.
5. Retry không đổi path/provider, không nuốt coroutine cancellation và không coi 409 là bằng chứng bytes đúng. Kiểm thử phiên hết hạn, kill/restart app, thumbnail lỗi và receipt sau timeout.
6. Xác minh phiên bản app đang phát hành đọc được ảnh Supabase qua hợp đồng thực tế; nếu không, cần đường tương thích hoặc rollout/min-version phù hợp trước khi bật cho người dùng rộng.

Nghiệm thu: ảnh Google cũ và Supabase mới hiển thị thật trên tester/owner/admin; mixed attachments không ảnh hưởng lẫn nhau; người bị khóa và ảnh tới hạn bị từ chối ở mọi đường. Đo cache HIT/MISS và độ trễ sau khi đường đọc đúng, không mặc định mọi request là cached egress.

### Bước 5 — Kiểm thử và bàn giao lại

1. Chạy lại 14 assertions độc lập. Sau sửa, thay thế/di chuyển các probe thích hợp vào test suite chính và giữ kết quả trước/sau; không sửa test để hợp thức hóa lỗi.
2. Viết runner PostgreSQL nhiều connection thật: cùng ID, khác ID sát ngưỡng, finalize chồng reconcile, hai worker claim/ack, retry sau mất phản hồi. Assertions kiểm tra U/R, số hàng, provider và khóa. Thiếu TEST_DATABASE_URL là NOT RUN; lỗi kết nối khi được cấu hình phải exit nonzero.
3. Chạy SQL nâng cấp từ schema trước thay đổi, dữ liệu Google cũ và các RPC legacy/v2/v3; hồi quy điểm, nhiệm vụ ngày, duyệt báo cáo và receipts vì 091 đã sao chép nhiều hàm nghiệp vụ.
4. Chạy Android unit tests liên quan với JDK và cache Gradle có quyền ghi hợp lệ; môi trường chặn phải ghi BLOCKED/NOT RUN. Không thay bảo mật hệ thống chỉ để đổi trạng thái test.
5. Chạy backend unit và gateway integration ở môi trường cho phép HTTP localhost; thêm test bootstrap dual adapter, parser, thiếu quyền, hết hạn, lỗi Storage và retry.
6. Smoke test hai nhà cung cấp trong môi trường thử, có file thật; thử chu kỳ xóa tăng tốc và xác nhận vật lý. Cần bằng chứng cron/cảnh báo/rollback hoạt động trước rollout.
7. Cập nhật index bằng công cụ, runbook và handover theo kết quả thật; loại khẳng định “production ready” khi còn phép kiểm tra quan trọng NOT RUN. Không tự tăng phiên bản, build APK/AAB hoặc deploy khi nhiệm vụ chỉ là kiểm tra/lập kế hoạch.

## 5. Điều kiện kết thúc

Chỉ nghiệm thu khi lỗi P0/P1 trên đã sửa và có bằng chứng tương ứng; Google cũ vẫn xem được; ảnh Supabase mới có đường đọc riêng tư hoạt động; quota không mất byte khi finalize; quyền xem ngừng đúng mốc 216h; xóa vật lý được xác nhận, retry hoạt động và có giám sát trước mốc 240h. Khoảng dự phòng 1 ngày không thay thế được worker/cảnh báo, và không bảo đảm tuyệt đối khi nhà cung cấp gián đoạn kéo dài.

Chưa cần hỏi lại ba lựa chọn đã chốt. Những thông tin cần xác minh khi triển khai là hạn mức/gói thực, danh sách project đầy đủ, môi trường test và cấu hình scheduler/gateway đang dùng.

## 6. Nguồn kỹ thuật được kiểm tra khi lập kế hoạch sửa

- Supabase Storage size usage: https://supabase.com/docs/guides/platform/manage-your-usage/storage-size — metadata cho phép thống kê file/bucket; dashboard có số liệu cấp tổ chức. Phân biệt dung lượng thực hiện tại với chỉ số theo kỳ.
- Supabase Storage schema: https://supabase.com/docs/guides/storage/schema/design — metadata không phải file vật lý; dùng API cho thao tác object, hạn chế sửa schema Storage của nhà cung cấp.
- Management API: https://api.supabase.com/api/v1/ — dùng để xác minh endpoint/schema được hỗ trợ; chưa có đủ căn cứ từ tài liệu được kiểm tra để xác nhận endpoint `/usage/storage` đang dùng trong code.
- Read-only Management query: https://supabase.com/docs/reference/api/v1-read-only-query — là lựa chọn cần đánh giá; tài liệu đánh dấu experimental, không mặc định là hợp đồng ổn định. Có thể dùng kết nối server/adapter metadata khác được kiểm chứng.

Các nguồn trên chỉ hỗ trợ thiết kế nhà cung cấp; các kết luận lỗi cụ thể ở mục 3 được chứng minh bằng source và probe local, không suy từ tài liệu bên ngoài.
