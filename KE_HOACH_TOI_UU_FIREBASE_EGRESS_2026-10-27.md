# Kế hoạch tối ưu Firebase egress — 12 Tester

Ngày nghiên cứu: **27/10/2026**, múi giờ Việt Nam. Phạm vi: đọc mã hiện tại trong working tree và đối chiếu tài liệu chính thức; **chưa sửa code, chưa thay cấu hình hoặc deploy Firebase/Supabase**.

## 1. Kết luận và hướng ưu tiên

Firebase trong dự án hiện phục vụ **ảnh screenshot báo cáo và icon ứng dụng qua Cloud Storage**. Backend nghiệp vụ, xác thực và database nằm ở Supabase; không thấy sử dụng Firestore/Realtime Database trong source Android đã kiểm tra. `firebase.json` hiện chỉ cấu hình Storage rules. Vì vậy, hướng tối ưu đầu tiên là **giảm số lần và số byte tải ảnh**, chưa có cơ sở để tối ưu listener Firestore hoặc cache Firebase Hosting.

Thứ tự đề xuất:

1. **E00: Đo đúng chi phí và nguồn tải**, phân biệt screenshot/icon, lượt xem mới/lượt tải lại và traffic ngoài app.
2. **E01: Giảm tải trùng trong một phiên xem**, hạn chế fallback tải ảnh để tính hash, chặn tải ảnh hết hạn ở mọi đường đi.
3. **E02: Thử thumbnail thật cho danh sách**, chỉ tải ảnh đầy đủ khi mở xem/so sánh; giữ nguyên ảnh bằng chứng và hash đang dùng.
4. **E03: Chỉ xem xét cache screenshot trên đĩa sau khi thống nhất thời hạn lưu và cơ chế xóa**. Mặc định giai đoạn đầu giữ `private, no-store` đang có.
5. **E04: Kiểm tra tải trái mục đích, App Check và quyền truy cập**; ưu tiên sớm hơn nếu baseline cho thấy traffic ngoài app đáng kể.
6. **E05: Chỉ cân nhắc CDN hoặc chuyển nơi lưu khi có tính toán tổng chi phí**.

Chưa có hóa đơn, region bucket, phân bố dung lượng ảnh, DAU và số lượt tải thực tế nên **chưa kết luận nguồn nào chiếm nhiều tiền nhất hoặc cam kết phần trăm tiết kiệm**. Những điểm dưới đây là bằng chứng từ source và giả thuyết cần đo riêng.

## 2. Hiện trạng đã xác minh

| Hạng mục | Bằng chứng trong repo | Ý nghĩa đối với egress |
|---|---|---|
| Bucket | `README.md`, worker cleanup: `cross-testers.firebasestorage.app` | Bucket thuộc nhóm tên mới; chưa xác nhận region, storage class hay billing thực tế trên cloud. |
| Screenshot đã nén | `FirebaseStorageHelper.kt:28–36`, `prepareScreenshot`: cạnh dài tối đa 1080, JPEG quality 80; hạ chất lượng/kích thước nếu vượt 1.900.000 byte | Không đề xuất “bắt đầu nén ảnh” như một việc chưa làm. Cần đo phân bố kích thước và độ đọc rõ trước khi nén thêm. |
| Cache screenshot | `REPORT_CACHE_CONTROL = "private, no-store"`, gán metadata khi upload | Nếu HTTP response thực tế trả header này và Coil giữ mặc định, disk cache không lưu response. RAM có thể vẫn tái sử dụng bitmap; không đồng nghĩa mỗi recomposition đều tải mạng. |
| Icon đã tối ưu cơ bản | WebP, cạnh tối đa 256, quality 90; tên object mới mỗi upload; `public, max-age=31536000, immutable` | Giữ URL ổn định cho cùng một phiên bản icon. Chưa có lý do tăng TTL thêm. |
| ImageLoader dùng chung | `TestCheoApp.kt:47`: RAM tối đa 25% bộ nhớ ứng dụng; disk cache 50 MiB | Có nền tảng cache sẵn. Tăng 50 MiB không giúp response bị `no-store`; tăng RAM cũng có thể gây áp lực bộ nhớ. |
| Đăng xuất xóa cache | `TestCheoApp.kt:148`, `performSignOut()` | Giữ cách ly tài khoản; nếu thêm cache phải kiểm tra cả request đang chạy hoàn tất sau clear. |
| UI dùng URL ảnh đầy đủ | `CampaignDetailScreen.kt:704,1659`; `OwnerAppDetailScreen.kt:2123`; viewer so sánh dùng cùng URL | Ô ảnh nhỏ vẫn trỏ về screenshot đầy đủ. Kích thước Compose/Coil chủ yếu điều khiển decode, không tạo file nhỏ trên server. |
| Hash đã được tái sử dụng | `ImageViewerComponents.kt:277–297`; `OwnerAppDetailScreen.kt:1887` | Đã ưu tiên `image_hash` từ DB. Chỉ fallback khi hash trống mới có thể phát sinh tải ảnh thêm. |
| Helper tính hash từ URL | `ImageHashHelper.kt:85–109`: Coil request software bitmap; RAM LRU 128 hash | Có thể khác request hiển thị ở bitmap config/kích thước; cần đo tải mạng trùng, không giả định cache luôn dùng chung. |
| Nhiều chế độ so sánh | `ImageViewerComponents.kt`: xem cạnh nhau, trượt, nhấp nháy, fullscreen | Đổi chế độ có thể tạo lại request. Cần trace để phân biệt cache hit, tải đồng thời và tải lại. |
| Đã có retention | Worker/lifecycle dưới `backend/storage-cleanup/`: bắt đầu xóa từ tuổi object 29 ngày, cảnh báo từ 30 ngày | Chủ yếu giảm dữ liệu lưu và đảm bảo retention; không hoàn lại egress đã phát sinh. Chưa xác nhận worker/lifecycle đã deploy. |
| Mốc hết hạn khác nhau | Worker dựa vào `timeCreated` của object; `Models.kt:244` dùng `mediaExpired` hoặc tuổi report 30 ngày | Có thể có khoảng thời gian object đã bị xóa nhưng UI chưa đánh dấu hết hạn. Không dùng việc kéo dài cache để che lệch mốc. |
| Read công khai | `storage.rules`: `allow read: if true`; create chỉ kiểm tra path/size/type, cấm update/delete | Có nguy cơ tải ngoài app và tạo object ngoài luồng nghiệp vụ. Đây là khả năng từ cấu hình, chưa có bằng chứng đã bị lạm dụng. `no-store` không phải kiểm soát truy cập. |

Các số dòng là mốc tham chiếu ở thời điểm nghiên cứu, có thể dịch khi source thay đổi. Các báo cáo tối ưu cache trước đó không thay thế phép đo egress ảnh.

## 3. Cách hiểu billing để chọn đúng giải pháp

- Phân biệt **byte được tải ra**, **số thao tác đọc/ghi**, **dung lượng lưu**, chi phí xử lý ảnh và các dịch vụ trung gian. Giảm GET hoặc xóa object chưa chắc giảm cùng tỷ lệ số tiền.
- Bucket `*.firebasestorage.app` dùng bảng giá Cloud Storage. Không áp mức giá bucket legacy `*.appspot.com` cho bucket hiện tại. Xác nhận location và SKU trước khi tính. [Firebase Pricing](https://firebase.google.com/pricing).
- Không mặc định bucket nào cũng được miễn 100 GB/tháng. Cloud Storage mô tả Always Free kèm điều kiện khu vực và đích truyền; xác minh mức miễn thực tế với billing của project. [Cloud Storage pricing](https://cloud.google.com/storage/pricing).
- Cache thiết bị phục vụ được ảnh mà không gọi network sẽ tránh lượt tải đó. Cache trên CDN vẫn có thể phát sinh phí truyền đến người dùng và phí fill/request. Firebase Hosting cũng tính data transfer cho cả CDN hit và miss. [Hosting usage](https://firebase.google.com/docs/hosting/usage-quotas-pricing).
- `private` có thể cho phép cache phía client; `no-store` chặn lưu response. Chỉ đặt `public` không chứng minh endpoint Firebase download token đã được Cloud Storage built-in cache phục vụ; phải kiểm tra điều kiện IAM, endpoint và response thực tế. Không mở public IAM cho screenshot chỉ để lấy cache. [Cloud Storage caching](https://cloud.google.com/storage/docs/caching).
- Repo dùng **Coil 2.7.0**, cần đối chiếu đúng version. Nhánh fetch HTTP kiểm tra header khi đọc/ghi disk cache; không áp mặc định Coil 3 vào dự án này. [Coil 2.7 HttpUriFetcher](https://github.com/coil-kt/coil/blob/2.7.0/coil-base/src/main/java/coil/fetch/HttpUriFetcher.kt).

## 4. E00 — Baseline, dashboard và phân rã nguyên nhân (P0)

**Đầu ra:** một bảng số liệu 7–14 ngày, cấu hình cloud đã xác nhận và trace các luồng xem ảnh. Có thể làm tối ưu cục bộ song song sau khi đã chụp baseline tái hiện.

### 4.1. Xác nhận cấu hình thực tế

Người có quyền cloud thu thập: project/bucket, location, storage class, billing plan, IAM/public access, App Check enforcement nếu có, lifecycle, soft delete/versioning, trạng thái worker cleanup. Đây là các dữ kiện chưa đọc được từ workspace.

Trong Billing Reports hoặc billing export: lọc project và dịch vụ Storage, tách SKU data transfer, operations, storage, retrieval và dịch vụ trung gian. Xem usage quantity/unit trước và sau credits; không chỉ nhìn tổng tiền. Nếu bật export mới, kiểm tra thời gian dữ liệu có sẵn và chi phí BigQuery.

Trong Monitoring: theo dõi `storage.googleapis.com/network/sent_bytes_count`, request count và lỗi theo các dimension có sẵn. Số byte Monitoring **không mặc định bằng byte bị tính phí**; đối chiếu billing. [Bandwidth monitoring](https://cloud.google.com/storage/docs/bandwidth-usage).

### 4.2. Đo tại app

Thêm instrumentation được bật có kiểm soát cho ImageLoader dùng chung và các đường tải ngoài Coil nếu phát hiện. Ghi:

| Trường | Mục đích |
|---|---|
| Build/cohort, màn hình, mục đích `icon/preview/full/compare/hash` | Quy nguồn phát sinh tải |
| Object ID đã băm, variant, request ID, phiên xem tạm thời | Phát hiện cùng ảnh bị yêu cầu lại |
| Data source `MEMORY_CACHE/MEMORY/DISK/NETWORK` theo API thực tế | Phân biệt cache với network; không gộp các loại khi phân tích |
| HTTP status, số byte body thực đọc, canceled/error, thời gian | Đo cả response dở dang; không chỉ dùng Content-Length |
| Response Cache-Control, ETag, Age nếu endpoint có trả | Kiểm tra metadata có ra tới client hay không |

Không log URL chứa download token, JWT, email hoặc ảnh. Không dùng object/user ID làm label Monitoring có cardinality không giới hạn. Client trace phân loại prefix; **billing không tự cung cấp báo cáo chi phí theo `reports/` và `app_icons/`**. Muốn phân rã server theo object cần kiểm tra loại access logging hỗ trợ endpoint, độ bao phủ và chi phí trước khi bật.

HTTP trace chỉ là phép đo chẩn đoán: traffic thiết bị, server bytes và billable bytes có thể khác nhau. Kiểm tra request có token/redirect có bị che khỏi logging hoặc đi qua client khác không.

### 4.3. Kịch bản baseline

1. Cold start, cuộn feed icon, quay lại feed; repeat sau kill/relaunch.
2. Mở lịch sử screenshot và danh sách duyệt; cuộn xuống rồi quay lại.
3. Mở một ảnh nhỏ → fullscreen → đóng → mở lại; so sánh hai ảnh qua mọi chế độ.
4. Report có đủ hash DB và report legacy thiếu hash; cùng ảnh được UI và hash helper gọi đồng thời.
5. Mạng chậm, request hủy giữa chừng, ảnh 404/403, offline/reconnect.
6. Đăng xuất A → đăng nhập B khi ảnh A đang tải; report hết hạn khi dialog còn mở.

Giữ cùng dataset, thiết bị, build type và trình tự thao tác khi so trước/sau. Lặp tối thiểu 20 lượt cho luồng chính; thu số byte, GET, p50/p95 latency, heap và cache hit. Tách cold/warm, không trộn kết quả.

**Gate E00:** biết phần nào là traffic hợp lệ trong app; lượng tải lặp; phân bố size p50/p95; tỷ lệ mở ảnh đầy đủ sau preview; baseline chi phí theo SKU. Nếu traffic ngoài app chưa quy nguồn được, ghi rõ phần chưa giải thích.

## 5. Các gói triển khai đề xuất

| Gói | Ưu tiên | Thay đổi và phạm vi | Tiêu chí nghiệm thu |
|---|---|---|---|
| E01a — Luồng tải ảnh thống nhất | P1 | Tạo component/request factory cho screenshot trong `ImageViewerComponents`, `CampaignDetailScreen`, `OwnerAppDetailScreen`; dùng ImageLoader hiện có, URL ổn định, variant rõ ràng; hủy request ngoài màn hình. | Không thêm cache-busting timestamp; không tạo loader cho từng card; trace xác nhận số GET/byte giảm trong tình huống tải trùng đã tái hiện. |
| E01b — Tái sử dụng trong phiên so sánh | P1 | Giữ kết quả decode hợp lệ cho cặp ảnh khi đổi chế độ, giới hạn RAM; chỉ bổ sung cơ chế gộp request nếu trace chứng minh tải đồng thời cùng object. | Đổi cạnh nhau/trượt/nhấp nháy không tải lại ảnh đã có trong phiên khi còn trong budget. Không giữ toàn bộ lịch sử ảnh trong RAM. |
| E01c — Hash legacy | P1 | Bảo toàn việc ưu tiên hash DB. Đo tỷ lệ thiếu hash; giới hạn fallback cho ảnh đang cần so sánh, cache kết quả đúng phiên và hủy theo coroutine. Nếu cần backfill, làm batch server có giới hạn và ước tính phí trước. | Có hash hợp lệ thì không có network request chỉ để tính hash. Thiếu hash không tạo nhiều request trùng cùng ảnh. Không lấy hash thumbnail thay cho hash ảnh bằng chứng. |
| E01d — Hết hạn và lỗi | P1 | Áp một chính sách media hết hạn cho mọi preview/full/compare/hash; xử lý 404 sau cleanup như media không còn sẵn có, không retry vòng lặp; đối chiếu tuổi object/report và cờ server. | Ảnh đã biết hết hạn không bị tải hoặc hiện lại từ cache; dialog mở lâu cũng ngừng hiển thị khi hết hạn. 403 không bị đổi thành “hết hạn” giả. |
| E02 — Thumbnail thật | P2, nâng P1 nếu preview chiếm nhiều byte | Thêm variant thumbnail được lưu thật; schema/API có trường tùy chọn và app cũ vẫn chạy. UI danh sách dùng thumb; thao tác mở/so sánh dùng full. | Byte mỗi lượt xem danh sách giảm; thumbnail vẫn đủ để chọn ảnh; ảnh full giữ rõ chữ; fallback legacy không gây tải cả hai variant hàng loạt. |
| E03 — Cache screenshot trên đĩa | P2, có điều kiện | Tách thiết kế retention/access như mục 6. Trong giai đoạn đầu giữ `no-store`, tối ưu theo E01/E02. | Chỉ bật nếu kiểm soát được xóa/hết hạn và mọi test cách ly tài khoản đều đạt. |
| E04 — Traffic ngoài luồng | P1 nếu baseline có bất thường | Đánh giá read/create công khai; thiết kế auth hoặc gateway tương thích Supabase; thử App Check theo SDK/endpoint thực tế, quan sát trước enforcement. | Client hợp lệ và phiên bản cũ có lộ trình; đo được request bị chặn; kiểm thử URL/token bị chia sẻ, không chỉ SDK upload. |
| E05 — CDN/nhà cung cấp khác | P3 | So TCO sau E00–E04, có PoC và kế hoạch thu hồi URL/xóa ảnh. | Tổng chi phí giảm ở traffic đo được và không làm hỏng retention, quyền, URL cũ hoặc luồng nộp. |

### Chi tiết E02: thumbnail và ảnh bằng chứng

- Thử cạnh dài thumbnail **320–480 px**, WebP/JPEG; mục tiêu ban đầu **20–60 KiB** là tham số thử nghiệm, không phải giới hạn bắt buộc cho mọi ảnh. Cần kiểm tra chữ, ảnh tối, ảnh nhiễu, màn hình dài và thông tin quan trọng khi duyệt.
- Giữ screenshot đầy đủ theo pipeline hiện tại, hash tính trên bytes ảnh full đã nén. Chưa đổi chất lượng full cho đến khi có bộ mẫu và kết quả so hash/độ đọc rõ.
- Chỉ `.size()` trong Coil, giảm kích thước Compose hoặc thêm `?w=...` vào URL Firebase không tự tạo thumbnail. Phải có artifact nhỏ thực sự.
- Có thể tạo thumb trên client từ ảnh đã chuẩn bị hoặc server. Chọn qua PoC: client tránh bước server đọc full nhưng cần xử lý upload phụ/idempotency; server dễ chuẩn hóa nhưng có compute, operations và đường truyền nội bộ cần tính.
- Đường dẫn thumb nên nằm trong phạm vi retention `reports/`, gắn được với đúng ảnh full bất biến. Thiết kế parser/path/rules rõ ràng, không làm backend nhận thumb là bằng chứng hợp lệ.
- Thumb có cùng hạn hết hiệu lực với full, kể cả tạo muộn cho ảnh cũ. Không cho thumbnail mới kéo dài tuổi dữ liệu. Cần xóa theo quan hệ parent/expiry, không chỉ dựa vào tuổi của object thumbnail.
- Upload hoặc tạo thumbnail thất bại không làm nộp report thành công bị retry thành report mới. Giữ nguyên `client_submit_id`, pending receipt, hash, reward và RPC hiện tại.
- Chưa backfill toàn bộ ảnh cũ. Ưu tiên ảnh mới, đo tỷ lệ dùng; backfill chỉ tập đang hoạt động nếu lợi ích vượt phí đọc/tạo/ghi/xóa.
- Nếu tỷ lệ mở full sau preview là `p`, size full `F`, thumb `T`, thì một lượt xem cold có chi phí byte xấp xỉ `T + p×F`. Chỉ có lợi về byte so với tải full ngay khi `p < 1 − T/F`, chưa tính cache và tải lại.

## 6. Quyết định cache screenshot và retention

**Phương án mặc định đề xuất cho đợt đầu:** giữ HTTP `private, no-store`, không ép disk cache; tập trung tránh tải trùng trong phiên và giảm byte preview. Icon tiếp tục cache dài theo phiên bản.

**Phương án có điều kiện cho đợt sau:** cache riêng tư ngắn hạn cho screenshot chỉ khi yêu cầu sản phẩm cho phép lưu trên thiết bị. Có thể thử TTL 15–60 phút, nhưng TTL phải được chặn bởi `media_expires_at` đáng tin cậy; không gán lại thời hạn từ mỗi lần tải.

Thiết kế bắt buộc nếu chọn phương án này:

1. Server/API cung cấp expiry và trạng thái thu hồi nhất quán. `created_at` report chỉ là fallback hiện có; cần đối chiếu với object có thể upload trước khi report commit, worker bắt đầu xóa ngày 29 và cờ `media_expired`.
2. Cache screenshot có scope tài khoản, object/version, variant. Mọi lần phục vụ cache phải kiểm tra expiry/quyền theo thiết kế; response cache không tự đảm bảo việc này.
3. Clear trên sign-out, đổi tài khoản, xóa/thu hồi media; hủy request cũ hoặc dùng generation guard để request hoàn tất muộn không ghi/phát ảnh A trong phiên B.
4. Có cơ chế dọn file hết hạn khi khởi động/resume/chạy nền phù hợp và giới hạn dung lượng. Nếu yêu cầu là xóa vật lý đúng hạn ngay cả khi app không chạy, cache persistent thông thường không đáp ứng; khi đó giữ `no-store`.
5. Kiểm thử đồng hồ thiết bị lệch, offline, app không chạy nhiều ngày, dialog đang mở khi hết hạn và ảnh bị xóa sớm. `Cache-Control` không tự giải quyết quyền thu hồi hoặc việc xóa vật lý.
6. Thay metadata chỉ tác động object được cập nhật. Ảnh cũ cần migration riêng có manifest, dry-run và tiền điều kiện generation/metageneration; không chạy mass update để “thử cache”.

Không bật `respectCacheHeaders(false)` toàn cục để ép lưu screenshot. Không đổi screenshot thành `public, max-age=31536000, immutable` như icon.

Lưu ý tài liệu: `PRIVACY_POLICY.md` hiện mô tả lưu dữ liệu đến khi yêu cầu xóa/ngừng dịch vụ, trong khi code ảnh báo cáo áp mốc 30 ngày. Trước khi thay chính sách cache/retention cần đồng bộ cách mô tả thời hạn thực tế và giữ nguyên xử lý xóa tài khoản; kế hoạch này chưa chỉnh nội dung đó.

## 7. Kiểm soát truy cập và chi phí hạ tầng

### Traffic ngoài app

`getDownloadUrl()` trả URL token sống lâu và có thể thu hồi. Không xem URL đó như một signed URL tự hết hạn. [StorageReference](https://firebase.google.com/docs/reference/android/com/google/firebase/storage/StorageReference).

App Check giúp bảo vệ tài nguyên trước client không được xác thực tính hợp lệ, nhưng không thay cho quyền của người dùng. Repo chưa có Firebase Auth/App Check dependency trong phần đã rà soát; Supabase session không tự trở thành `request.auth` trong Firebase rules. [App Check](https://firebase.google.com/docs/app-check).

Nếu cần ảnh chỉ người có quyền report được đọc, lập thiết kế cụ thể: bridge danh tính Firebase đáng tin cậy, hoặc backend kiểm tra Supabase JWT rồi cấp quyền tải ngắn hạn/stream. Tính cả compute, requests, truyền chéo vùng và độ trễ. Kiểm thử trực tiếp download URL cũ sau khi bật bảo vệ; không giả định App Check tự chặn mọi đường token URL.

Không đổi `allow read` hoặc enforce App Check đột ngột khi các app cũ đang đọc URL trực tiếp. Không mở bucket IAM public để thuận tiện CDN. Nếu làm gateway, cần ngăn đường origin/token cũ tiếp tục bỏ qua gateway sau giai đoạn chuyển tiếp.

### CDN, region và storage class

- CDN chỉ đưa vào PoC khi có lượng nội dung được nhiều thiết bị cùng tải đủ lớn, đặc biệt icon. Screenshot ít người xem, vòng đời ngắn có thể không bù chi phí.
- Tính origin/fill, edge egress, request, load balancer, compute, logging và vận hành. Cache CDN không làm egress đến người dùng biến mất. [Cloud Storage caching](https://cloud.google.com/storage/docs/caching).
- Chưa chuyển bucket region. Xác nhận location hiện tại, nguồn người dùng, backend tạo thumb và điều kiện free tier; “gần Việt Nam hơn” không tự chứng minh rẻ hơn. Tính cả phí migration, URL compatibility và latency.
- Không chuyển ảnh thường xuyên đọc và xóa sớm sang Nearline/Coldline chỉ để giảm tiền lưu: có retrieval và minimum storage duration cần tính. [Cloud Storage pricing](https://cloud.google.com/storage/pricing).
- Giữ URL icon theo version; deduplicate icon bằng content digest nếu có bằng chứng upload lặp, kèm kiểm tra tham chiếu trước cleanup. Không dùng dHash làm content digest, không deduplicate ảnh report theo cách phá yêu cầu ảnh mới/idempotency.

Đặt budget alerts theo mức chi tiêu đã thống nhất, ví dụ 50/80/100% và forecast. **Alerts-only budget không phải hard cap**, có độ trễ báo cáo. Nếu cần spend cap, xác minh dịch vụ/SKU và tài khoản có hỗ trợ; không giả định Cloud Storage được hỗ trợ hoặc giới hạn có hiệu lực tức thì. [Budget alerts](https://cloud.google.com/billing/docs/how-to/budgets), [Firebase spend caps](https://firebase.google.com/docs/projects/billing/spend-caps).

## 8. Mô hình tính hiệu quả

Đo riêng từng variant và trường hợp cold/warm:

```text
Egress xấp xỉ = tổng số lượt tải mạng × số byte trung bình của mỗi loại ảnh
              + bytes của lượt tải bị hủy/lỗi và traffic ngoài app

Chi phí tổng = data transfer theo SKU/region/destination thực tế
             + operations + storage + retrieval nếu có
             + xử lý thumbnail + CDN/gateway/logging nếu có

Tiết kiệm ròng = tổng chi phí baseline đã chuẩn hóa − tổng chi phí phương án mới
```

Chuẩn hóa theo **GiB/1.000 lượt duyệt report**, **MiB/lượt mở so sánh**, **GiB/1.000 phiên feed**, build/cohort và cùng tỷ lệ cold/warm. Theo dõi thêm chi phí/1.000 report; chỉ số này bị ảnh hưởng bởi số người xem lại nên cần ghi nhận cả số lượt xem.

Ví dụ minh họa, không phải số đo của app: 1.000 lượt xem cold đang tải ảnh 250 KiB/lượt dùng khoảng **244,1 MiB**. Nếu dùng thumb 30 KiB và chỉ 20% mở full, tổng là **78,1 MiB**, giảm khoảng **68% số byte cho đúng nhóm lượt xem này**. Không suy ra giảm 68% toàn hóa đơn; còn cache hit, operations, thumbnail generation và traffic khác.

## 9. Kiểm thử, phát hành và rollback

### Kiểm thử cần có khi triển khai

- Dùng HTTP fixture/MockWebServer với bytes và header xác định: icon cache dài; report `no-store`; request đồng thời; ảnh lỗi và hủy. Assert số request và số byte thực đọc, không chỉ assert UI có ảnh.
- Một phiên xem cùng cặp ảnh qua các chế độ không tải lại ngoài nhu cầu hợp lệ khi còn trong budget RAM. Kiểm tra full/preview/software bitmap để không dùng kết quả decode thiếu độ phân giải hoặc sai basis hash.
- Thumbnail optional hoạt động với dữ liệu mới/cũ; lỗi thumbnail không gây vòng lặp thumb→full→thumb; không mở full ngầm cho mọi row.
- Hết hạn/thu hồi/logout: không phục vụ ảnh cũ từ RAM/disk, không ghi cache lại từ request cũ. Mốc 29–30 ngày, thiếu `created_at`, offline và đồng hồ lệch được kiểm tra rõ.
- Hash vẫn trên ảnh full đã upload, threshold chống trùng giữ nguyên; không thay reward, RPC idempotency hoặc retry submission. Chạy regression report/cache hiện có khi thay code liên quan.
- Nếu thêm variant/rules: kiểm thử Storage Rules bằng emulator, chỉ cho create hợp lệ; thumb không giả làm bằng chứng; cleanup xóa đủ variant và không đụng icon. Bảo toàn test worker generation precondition, allowlist, dry-run và ngưỡng tuổi.
- Với thay đổi Android: chạy unit/regression liên quan, `:app:assembleDebug`, `:app:lintDebug`; đo trên thiết bị release-like cho byte/latency/heap. Build pass không chứng minh Firebase đã cấu hình đúng.

### Phát hành

1. E00 instrumentation và baseline; E01 trong bản thử nội bộ.
2. E02 với schema tương thích trước, rồi client có flag/tùy chọn tắt. Dùng cohort nhỏ → 25% → 100% nếu cơ chế phân phối hiện có cho phép; nếu chưa có, chọn nhóm tester và staged release phù hợp.
3. Theo dõi tối thiểu vài ngày đủ mẫu và ít nhất một chu kỳ hoạt động so sánh được; billing có độ trễ nên chờ dữ liệu ổn định trước khi kết luận tiền tiết kiệm.
4. Dừng mở rộng nếu lỗi tải/duyệt tăng, ảnh khó đọc, heap/crash tăng hoặc retention thất bại. Rollback UI về full URL; giữ schema optional tương thích. Tắt thử nghiệm cache riêng, xóa cache phát sinh nếu cần; không xóa báo cáo hay ảnh full để rollback.

**Gate chấp nhận:** tất cả kiểm thử tính đúng/quyền/retention đạt; workload cố định giảm byte; dữ liệu cohort giảm chi phí chuẩn hóa sau khi cộng phí phụ; latency và chất lượng duyệt không xấu đi đáng kể theo ngưỡng chốt từ baseline. Mục tiêu thử nghiệm có thể đặt giảm ít nhất 30% byte nhóm preview nếu E00 cho thấy đủ dư địa; đây là tiêu chí đề xuất, chưa phải dự báo kết quả.

## 10. Lịch thực hiện và bàn giao

| Giai đoạn | Thời lượng ước tính | Phụ thuộc/đầu ra |
|---|---|---|
| E00 thiết lập và trace | 1–2 ngày công | Quyền đọc Billing/Monitoring, mẫu ảnh đại diện; dashboard và trace |
| Baseline production | 7–14 ngày lịch | Có thể chạy song song PoC; đủ mẫu và chu kỳ sử dụng |
| E01 | 1–3 ngày công | Tái hiện được tải trùng; diff, test, before/after bytes |
| E02 PoC + tương thích | 2–4 ngày công | Tỷ lệ mở full, thiết kế upload/schema/rules/cleanup; quyết định go/no-go |
| E03/E04 | Ước lượng riêng sau thiết kế | E03 cần retention contract; E04 cần thống kê abuse và lộ trình client cũ |
| E05 | Chỉ sau khi tính TCO | Không mặc định cần CDN hoặc migration |

Thời lượng là ước tính công việc, chưa bao gồm chờ release hoặc quyền cloud. Nếu E00 cho thấy icon/traffic ngoài app mới là phần lớn chi phí, đổi thứ tự theo số đo, không cố bám thumbnail.

**Chỉ dẫn cho người triển khai:** đọc git status/diff và giữ nguyên thay đổi có sẵn; thực hiện E00→E01 trước, báo evidence và chi phí trước/sau từng gói. Chưa triển khai E03/E04/E05 khi chưa có thiết kế cụ thể tương ứng. Không tự reset/clean/commit/push/deploy, không thay reward/idempotency hoặc thời hạn lưu để đạt chỉ số đẹp. Không tuyên bố giảm hóa đơn chỉ dựa vào tăng cache hoặc build pass.

## 11. Nguồn đối chiếu chính

Các liên kết được kiểm tra ngày 27/10/2026; giá và tính năng phải kiểm tra lại khi triển khai:

1. [Firebase Pricing](https://firebase.google.com/pricing) — phân biệt nhóm bucket và dịch vụ.
2. [Cloud Storage pricing](https://cloud.google.com/storage/pricing) — egress, operations, retrieval và điều kiện Always Free.
3. [Cloud Storage caching](https://cloud.google.com/storage/docs/caching) — Cache-Control, điều kiện built-in cache và Cloud CDN.
4. [Firebase Storage metadata trên Android](https://firebase.google.com/docs/storage/android/file-metadata) — metadata object.
5. [Coil 2.7.0 HttpUriFetcher](https://github.com/coil-kt/coil/blob/2.7.0/coil-base/src/main/java/coil/fetch/HttpUriFetcher.kt) và [ImageLoader](https://github.com/coil-kt/coil/blob/2.7.0/coil-base/src/main/java/coil/ImageLoader.kt) — đúng version repo đang dùng.
6. [Cloud Storage bandwidth monitoring](https://cloud.google.com/storage/docs/bandwidth-usage) — metrics theo dõi byte.
7. [Firebase App Check](https://firebase.google.com/docs/app-check) và [StorageReference Android](https://firebase.google.com/docs/reference/android/com/google/firebase/storage/StorageReference) — bảo vệ ứng dụng và URL token sống lâu.
8. [Budget alerts](https://cloud.google.com/billing/docs/how-to/budgets) và [Firebase spend caps](https://firebase.google.com/docs/projects/billing/spend-caps) — phân biệt cảnh báo với giới hạn chi tiêu.

Trạng thái hoàn thành của nghiên cứu: **đã có phân tích source và kế hoạch có thể bàn giao**. Đo lường production, cấu hình cloud thực tế, PoC và triển khai vẫn là công việc tiếp theo.
