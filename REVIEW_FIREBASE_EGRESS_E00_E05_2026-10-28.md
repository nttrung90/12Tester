# Kiểm tra độc lập Firebase Egress E00–E05

Ngày: 28/10/2026. Phạm vi: working tree hiện tại sau thay đổi của Antigravity, đối chiếu kế hoạch ngày 27/10 và hành vi database trước/sau migration 045–046.

**Kết luận: còn lỗi, chưa đạt điều kiện nghiệm thu E00–E05.** Migration 045 có hồi quy nghiệp vụ đã tái hiện. E02 chưa nối kín luồng upload thumbnail. E03 có lỗi cách ly/xóa/expiry khi bật cờ. E04 và E05 có những phần mới là hàm mô phỏng hoặc helper chưa được ứng dụng gọi.

Đây là kiểm tra code và kiểm thử cục bộ. Chưa xác minh trạng thái triển khai Supabase/Firebase production, Billing/Monitoring, thiết bị thật hoặc số tiền tiết kiệm thực tế. Không thay mã production, không apply migration, deploy, revoke token, commit hoặc reset thay đổi có sẵn.

## 1. Kết quả kiểm thử độc lập

| Kiểm tra | Kết quả |
|---|---|
| `:app:testDebugUnitTest` sau khi gỡ probe tạm | **83 tests, 0 failure, 0 error**, 9 suites |
| `:app:assembleDebug` | Thành công |
| `:app:lintDebug` | 0 error, 422 warning, 1 hint; không kết luận warning đều an toàn |
| Node cleanup + preflight | 11 tests đạt |
| Node TCO + reconciliation | 6 tests đạt |
| PGlite thumbnail migration và media access | Hai script hiện có chạy thành công; chưa chứng minh đủ hợp đồng nghiệp vụ/HTTP |
| 8 probe Android bổ sung | **8/8 tái hiện lỗi**, không phải lỗi biên dịch/harness |
| Database trước/sau 045 | Tái hiện 5 hồi quy, cùng fixture và cùng lời gọi RPC |
| `git diff --check` | Không có lỗi whitespace; Git báo chuyển LF/CRLF ở một số file |

Bộ test hiện có xanh nhưng chưa bao phủ các đường đi gây lỗi bên dưới. Sau thử nghiệm đã xóa đúng file probe được chép tạm vào test source và chạy lại suite gốc; không để bộ test ứng dụng ở trạng thái đỏ.

## 2. Trạng thái từng gói

| Gói | Đánh giá | Vấn đề chính |
|---|---|---|
| E00 | Chưa đủ baseline tin cậy | Đếm hit hai lần, collector chưa nối, reconciliation báo OK với dữ liệu bất nhất |
| E01 | Suite hiện có đạt; có lỗi tích hợp mới | Fallback 404 bị chặn ở lần xem tiếp theo; E03 làm đổi cơ sở dHash legacy |
| E02 | Chưa chạy đúng toàn luồng | Thumbnail bị mất trước worker; migration đổi nghiệp vụ; thumbnail cũ/retention sai |
| E03 | Giữ cờ tắt | Race sau clear/logout, UID chưa nối, expiry/Cache-Control và decode có lỗi |
| E04 | Chưa có gateway bảo vệ lượt tải | RPC trả URL cũ, grant chưa có nơi xác minh; App Check/preflight chưa hoạt động với dữ liệu thật |
| E05 | PoC chưa tích hợp, TCO cần sửa | AppIcon không gọi routing; công thức tính phí chưa đúng và đầu vào là giả định |

## 3. Phát hiện cần sửa

### F01 — P1 — Migration 045 làm đổi credit và nghiệp vụ báo cáo

Nguồn: [045:33](F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql:33), [045:147](F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql:147), [045:248](F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql:248). Đối chiếu hàm đang có ở [037](F:/AI/12Tester/supabase/migrations/037_reward_10_credits.sql:138).

045 viết lại `submit_tester_report` và bỏ các phần nghiệp vụ đang có. `v_already_checked_in` luôn `false` vì không còn truy vấn gán giá trị; upsert `feedbacks` bị mất; nhánh approved cập nhật rating; nhánh rejected thiếu kiểm tra hash ảnh nộp lại.

| Kịch bản giống nhau | Trước 045 | Sau 045–046 |
|---|---|---|
| Đã có check-in xác nhận, chưa có report hôm nay | Không cộng credit | Cộng thêm **10 credit** |
| Nộp feedback mới | 1 dòng `feedbacks` | 0 dòng |
| Sửa nội dung report approved, gửi rating 1 | Giữ rating 5 | Đổi thành 1 |
| Report rejected, URL mới nhưng hash ảnh cũ | `SAME_SCREENSHOT_REJECTED` | Chấp nhận |

**Cách sửa:** lấy đầy đủ hợp đồng từ hàm hiện hành, chỉ thêm thumbnail tùy chọn. Chạy regression reward/check-in/feedback/approved/rejected trên chính migration mới. Không apply 045 hiện tại.

### F02 — P1 — Migration 045 bỏ xác thực URL ảnh bằng chứng

Nguồn: [045:112](F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql:112), so với [037:132](F:/AI/12Tester/supabase/migrations/037_reward_10_credits.sql:132).

Hàm cũ gọi `is_valid_report_screenshot_url(..., campaign_id, user_id)`; 045 không còn gọi. Probe URL ngoài Firebase bị `INVALID_SCREENSHOT_URL` trước migration nhưng được chấp nhận sau migration. `p_thumbnail_url` cũng nhận URL ngoài không được kiểm tra. Điều này làm mất ràng buộc nguồn/path bằng chứng và gây lỗi tải hoặc nội dung từ nguồn không thuộc báo cáo.

**Cách sửa:** khôi phục validator full, bổ sung validator thumbnail gắn đúng bucket/campaign/user/report và cặp full–thumb. Kiểm thử RPC trực tiếp, không chỉ dựa vào client upload.

### F03 — P1 cho rollout E04 — RPC chưa tạo đường tải có thời hạn/quyền thực thi

Nguồn: [046:77](F:/AI/12Tester/supabase/migrations/046_media_access_gateway.sql:77), [046:87](F:/AI/12Tester/supabase/migrations/046_media_access_gateway.sql:87), [AppCheckObservationConfig:39](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/AppCheckObservationConfig.kt:39), [preflight](F:/AI/12Tester/backend/storage-cleanup/dry-run-token-revocation.mjs:26).

Kiểm tra quan hệ author/owner/admin trong SQL có tồn tại. Tuy nhiên, response trả lại **nguyên URL download Firebase cũ**. `expires_at` và hash không được endpoint tải ảnh nào xác minh. `digest(..., 'sha256')` không có secret nên cũng không phải chữ ký HMAC như báo cáo triển khai mô tả. Database probe xác nhận `samePermanentUrl=true`; client chưa gọi RPC này. Vì vậy hết 15 phút hoặc đổi quyền database chưa làm URL đã nhận ngừng tải.

`AppCheckObservationConfig` mới có enum và debug logger; không có caller runtime, provider/token integration hay collector quan sát thực. Preflight chỉ so sánh số đầu vào với số mặc định, chưa scan Storage/manifest; `ALLOWED_BUCKET` chưa được sử dụng. Test SQL bắt exception message cũng chưa chứng minh mã HTTP 401/403/410 thực tế.

**Cách sửa:** triển khai endpoint tải/proxy hoặc cấp URL ký ngắn hạn có xác minh quyền, expiry và cơ chế revoke; nối client; kiểm thử URL được chia sẻ/replay/hết hạn và HTTP thật. Nối App Check và preflight inventory thật trước khi dùng gate adoption/revocation. Hiện trạng không đủ cơ sở nghiệm thu E04; không revoke token cũ dựa vào preflight hiện tại.

### F04 — P2 — Thumbnail bị rơi trước khi vào worker

Nguồn: [ReportSubmitManager.persist:225](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/ReportSubmitManager.kt:225), [ReportUploadWorker:87](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/work/ReportUploadWorker.kt:87).

Prepare đã tạo `PreparedScreenshot.thumbnailFile`, nhưng `persist()` chỉ copy full và không gán `PendingReport.thumbnailPath`. Worker chỉ upload thumb khi path này có file. Luồng nộp bình thường vì vậy không tạo thumbnail trên Storage dù test helper nén ảnh đạt. Probe 01 gọi đúng `persist()` với hai file thật và nhận `thumbnailPath=null`.

**Cách sửa:** copy thumbnail vào pending bền vững, persist path, kiểm tra cleanup/retry/process restart; test từ SubmitRequest qua persistence và worker tới RPC.

### F05 — P2 — Thay full nhưng giữ thumbnail của ảnh cũ

Nguồn: [045:204](F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql:204), [045:231](F:/AI/12Tester/supabase/migrations/045_thumbnail_support.sql:231).

`coalesce(p_thumbnail_url, thumbnail_url)` giữ thumb cũ khi client cũ hoặc upload thumb thất bại gửi full mới và thumb null. Probe database xác nhận full đổi nhưng thumb cũ còn. Reviewer thấy preview khác ảnh bằng chứng mở ra.

**Cách sửa:** khi full đổi, đặt thumb theo ảnh mới hoặc null; null phải fallback về full mới. Test cả nhánh pending và rejected.

### F06 — P2 — Thumbnail 404 chỉ fallback thành công lần đầu

Nguồn: [ScreenshotCoilInterceptor:37](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotCoilInterceptor.kt:37), [fallback:68](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotCoilInterceptor.kt:68).

Lần đầu thumb 404 được tombstone và tải full thành công. Lần mở lại, kiểm tra `isExpired(data)` trả lỗi ngay do tombstone, trước đoạn fallback. Full đang có trong cache cũng không được dùng. Probe 02 thực hiện hai lần trên cùng request: lần đầu thành công, lần sau thất bại.

**Cách sửa:** phân biệt thumb thiếu với report hết hạn; giải quyết fallback trước khi chặn vì tombstone thumb. Giữ chặn thật cho media expired và test nhiều lần mở lại.

### F07 — P1 khi bật E03 — Response đang chạy có thể ghi lại file sau clear/logout

Nguồn: [ScreenshotManager:367](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt:367), [disk put:380](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt:380), [clear:436](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt:436).

RAM publish có generation guard, nhưng ghi đĩa nằm ngoài guard/đồng bộ với clear. Probe 06 chặn đọc response body, gọi clear, rồi thả body: còn **1 file `.bin` được tạo sau clear**. Cancel coroutine/request không đủ bảo vệ đoạn xử lý đồng bộ này. Đường disk hit cũng return bitmap dù generation đã đổi trong lúc đọc.

**Cách sửa:** phối hợp clear và publish đĩa nguyên tử; capture UID/generation khi bắt đầu và xác thực lại trước commit/return. Invalidated write phải được xóa. Cờ hiện tắt mặc định nên đây là lỗi chặn bật E03, chưa phải bằng chứng rò dữ liệu production.

### F08 — P2, E03 đang tắt — Account scope chưa nối với tài khoản đăng nhập

Nguồn: [ScreenshotManager:83](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt:83), [disk read:275](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt:275).

`currentAccountId` mặc định `"default"`; tìm toàn bộ main source không có auth caller gán UID. Vì vậy cấu trúc folder theo account mới tồn tại trên API, chưa cách ly tài khoản thực. Generation RAM khởi tạo lại khi process mới không thay thế được account binding bền vững.

**Cách sửa:** lấy UID từ session hợp lệ, từ chối disk access khi chưa có UID, clear/chuyển namespace theo auth event và test logout–login–kill–restart với hai tài khoản.

### F09 — P2, E03 đang tắt — Expiry và Cache-Control chưa được giữ đúng

Nguồn: [PrivateScreenshotDiskCache:85](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/PrivateScreenshotDiskCache.kt:85), [expiry:104](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/PrivateScreenshotDiskCache.kt:104), [read:183](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/PrivateScreenshotDiskCache.kt:183).

Ba probe độc lập thất bại:

- Probe 03: thiếu `reportCreatedAt` và trạng thái media vẫn ghi đĩa, do tự cấp thêm TTL 30 phút; ngày lỗi định dạng cũng đi nhánh này.
- Probe 04: response `private, max-age=60` được lưu với freshness **1.800.000 ms**, bỏ qua max-age. `no-cache` cũng chưa có revalidation.
- Probe 05: tính expiry bằng server Date nhưng so sánh lúc get bằng đồng hồ local; server chậm một giờ làm cache vừa ghi bị coi hết hạn ngay.

Ngoài ra `cleanupOnStartupOrResume()` hiện chỉ được gọi ở `onCreate`, chưa có resume hook; `currentMediaExpired=true` trả miss trước khi xóa file tương ứng.

**Cách sửa:** yêu cầu expiry/authorization contract hợp lệ, giữ các directive, dùng nhất quán clock offset và elapsed TTL; nối lifecycle/expiry invalidation. Giữ nguyên việc tôn trọng `no-store`.

### F10 — P2, E03 đang tắt — Decode disk làm thay đổi dHash legacy

Nguồn: [disk decode:210](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/PrivateScreenshotDiskCache.kt:210), [network decode:356](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotManager.kt:356), [hash fallback:115](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ImageHashHelper.kt:115).

Network decode `ARGB_8888`, disk decode `RGB_565`. Probe 08 dùng cùng byte JPEG và hàm dHash thật: hash đổi từ `54294d24294015ab` sang `4009492428001021`. Legacy report thiếu hash DB vì vậy có thể cho kết quả so sánh khác tùy nguồn cache. Report có hash DB vẫn đi nhánh ưu tiên DB.

**Cách sửa:** thống nhất dữ liệu/pixel dùng tính hash, không giảm precision ở đường full phục vụ hash; regression cùng byte ảnh qua network/RAM/disk.

### F11 — P2 — Thumbnail chưa kế thừa expiry, custom metadata có thể che tuổi full

Nguồn: [upload thumb:239](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/FirebaseStorageHelper.kt:239), [worker caller:195](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/work/ReportUploadWorker.kt:195), [cleanup:143](F:/AI/12Tester/backend/storage-cleanup/cleanup-reports-storage.mjs:143), [rules](F:/AI/12Tester/storage.rules:13).

Caller thực không truyền `parentTimeCreated`, nên thumbnail tạo muộn không có mốc gốc. Probe full 30 ngày/thumb 28 ngày: full bị xóa nhưng thumb còn. Ngược lại cleanup tin `parent_time_created` do client cung cấp cho **mọi object**, kể cả full. Probe object thật 35 ngày nhưng custom parent date tương lai: không thành candidate và không báo SLA violation. Rules create hiện không xác thực trường metadata này.

**Cách sửa:** dùng metadata nguồn gốc đáng tin phía server, ràng buộc cặp full–thumb; custom metadata không được kéo dài hạn full. Kiểm tra phối hợp lifecycle bucket và worker trên môi trường thật trước khi kết luận SLA.

### F12 — P2 — E00 đếm trùng memory hit

Nguồn: [interceptor:49](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ScreenshotCoilInterceptor.kt:49), [event listener:176](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ImageInstrumentation.kt:176).

Interceptor/manager tự tăng hit rồi listener Coil tăng tiếp khi nhận success từ memory. Probe 07 cấu hình cả hai như ImageLoader runtime: một request hit tăng counter **2**. Một số counter manager còn tăng trực tiếp ngoài gate opt-in của instrumentation.

**Cách sửa:** quy định một nơi sở hữu từng metric, giữ gate thống nhất, kiểm thử request thật qua đầy đủ pipeline; không dùng số hiện tại để tuyên bố hit ratio/tiết kiệm.

### F13 — P2 — E00 collector/CLI chưa hoàn chỉnh; reconciliation che dữ liệu bất nhất

Nguồn: [reconciliation:49](F:/AI/12Tester/tools/telemetry-reconciliation/reconcile-telemetry.mjs:49), [runbook:55](F:/AI/12Tester/RUNBOOK_BASELINE_PRODUCTION_7_14_DAYS.md:55), [snapshot](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/ImageInstrumentation.kt:47).

Lệnh `node tools/telemetry-reconciliation/reconcile-telemetry.mjs` trong runbook không chạy xử lý dữ liệu vì module chỉ export hàm. Chưa có collector nối snapshot app thành dữ liệu version/cohort có cùng cửa sổ thời gian với bucket. Probe client=200/cloud=100 và client=200/cloud=0 đều trả delta 0%, alert=false và trạng thái OK.

**Cách sửa:** có CLI/input/output hoặc sửa runbook với chương trình gọi thật; nối collector version/cohort/coverage/time window; dữ liệu thiếu hoặc client vượt cloud phải báo invalid/unknown. Delta chưa giải thích không đủ để kết luận traffic lạm dụng hay cho phép revoke token.

### F14 — P2 — E05 icon routing chưa được ứng dụng sử dụng

Nguồn: [IconRoutingHelper:42](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/IconRoutingHelper.kt:42), [AppIcon:61](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AppIcon.kt:61).

Tìm main source chỉ thấy khai báo `resolveIconUrl`/`shouldFallbackToOrigin`, không có caller. `AppIcon` tiếp tục đưa `iconUrl` gốc vào loader; helper trả boolean fallback cũng chưa có retry consumer. Bật cờ helper không làm app tải qua CDN. Chưa xác minh hạ tầng CDN là gate riêng.

**Cách sửa:** nối routing vào đường icon thực, implement bounded fallback origin và test loader; đo cache key/hit/latency trước khi bật cohort.

### F15 — P2 — E05 công thức TCO và kết luận traffic hiện tại chưa đủ căn cứ

Nguồn: [TCO:104](F:/AI/12Tester/tools/tco-calculator/calculate-tco.mjs:104).

Mô hình chỉ tính edge transfer cho 85% cache hit, còn miss cộng cache fill và GCS regular egress. Với Cloud CDN phục vụ GCS cacheable content, miss vẫn có CDN data transfer out và GCS transfer sang CDN được miễn phí; cache fill/operation/LB có quy tắc riêng. Đối chiếu [Cloud CDN pricing](https://cloud.google.com/cdn/pricing) và [Cloud Storage pricing](https://cloud.google.com/storage/pricing).

`gcsAlwaysFreeEgressGb` được khai báo nhưng không dùng: probe đổi 0 → 10.000 vẫn ra cùng chi phí 2,20 USD. Không được sửa bằng cách trừ free allowance toàn cục; cần đúng điều kiện bucket/region/destination. Các tỷ lệ tải trùng, hit 85%, overhead 5 USD và traffic mặc định chưa lấy từ baseline thực. Vì vậy `NO_GO cho lưu lượng hiện tại` mới là kết quả giả định, chưa phải kết luận theo số đo production.

**Cách sửa:** mô hình hóa theo SKU/region/hit–miss/ops/storage/LB và điều kiện miễn phí; thêm sensitivity test với số đo baseline. Đặt tên output rõ là scenario khi dùng đầu vào giả định.

## 4. Thứ tự sửa và nghiệm thu lại

1. Khôi phục nghiệp vụ/validator trong 045 (F01–F02), rồi test hồi quy database đầy đủ. Giữ gate chưa apply migration.
2. Nối persistence/upload thumbnail, xử lý full–thumb replacement và 404; sửa retention (F04–F06, F11).
3. Chỉnh metric/collector E00 trước khi dùng baseline để đo hiệu quả (F12–F13).
4. Giữ E03 tắt; sửa race/UID/expiry/dHash và kiểm thử hai account, restart, clock skew, expiry (F07–F10).
5. Hoàn thành gateway/App Check/preflight E04 thực rồi kiểm tra quyền ở lượt tải; tiếp tục giữ token cũ cho đến khi đường thay thế được kiểm chứng (F03).
6. Nối PoC icon và tính lại TCO bằng số liệu thật (F14–F15).

## 5. Bằng chứng và tái hiện

Thư mục [audit/firebase-egress-2026-10-28](F:/AI/12Tester/audit/firebase-egress-2026-10-28) chứa:

- [db-audit.mjs](F:/AI/12Tester/audit/firebase-egress-2026-10-28/db-audit.mjs) và [db-results.json](F:/AI/12Tester/audit/firebase-egress-2026-10-28/db-results.json): khởi tạo PGlite độc lập, schema trước 045 rồi schema +045/046; không gọi production.
- [misc-audit.mjs](F:/AI/12Tester/audit/firebase-egress-2026-10-28/misc-audit.mjs) và [misc-results.json](F:/AI/12Tester/audit/firebase-egress-2026-10-28/misc-results.json): mock Storage cho retention; gọi hàm TCO/reconciliation thật.
- [EgressAuditProbeTest.kt](F:/AI/12Tester/audit/firebase-egress-2026-10-28/EgressAuditProbeTest.kt): 8 probe Robolectric, dùng fixture HTTP/file local, không gọi Firebase thật.
- [verification.json](F:/AI/12Tester/audit/firebase-egress-2026-10-28/verification.json): số lượng test và thông báo 8 assertion failure đã thu từ XML Gradle.

Chạy hai probe Node từ root repo:

```powershell
node audit/firebase-egress-2026-10-28/db-audit.mjs
node audit/firebase-egress-2026-10-28/misc-audit.mjs
```

Android probe được lưu ngoài source set để không làm đỏ suite thường trực. Muốn tái hiện, chép riêng file vào package test tương ứng chỉ khi đích chưa tồn tại, chạy `:app:testDebugUnitTest --tests com.ntt.crosstest.util.EgressAuditProbeTest`, lưu kết quả, rồi gỡ đúng bản probe vừa chép. Chạy lại suite gốc sau đó. Không xóa/ghi đè test có sẵn.

Lệnh baseline đã chạy thành công với JBR Android Studio:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Báo cáo triển khai trước đó cần được chỉnh lại theo bằng chứng này: “module hoàn tất”, “HMAC 15 phút”, “App Check monitor đã chạy”, “RPC cũ tương thích 100%” và “tiết kiệm trên card thực” chưa được kiểm chứng đầy đủ hoặc trái với hành vi đã tái hiện. Tương thích chữ ký RPC không đồng nghĩa giữ đúng nghiệp vụ.
