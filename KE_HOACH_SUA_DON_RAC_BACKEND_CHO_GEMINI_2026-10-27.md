# Rà soát dọn rác backend và kế hoạch sửa cho Gemini — 27/10/2026

## 1. Kết luận và phạm vi

**Chưa đạt. Luồng hiện tại chỉ có phần dọn campaign trên PostgreSQL; chưa có cơ chế được quản lý trong repo để bảo đảm ảnh báo cáo Firebase được lưu tối đa 30 ngày.** Có lỗi thiếu migration trong schema gộp và lỗi phụ thuộc người dùng mở màn hình để mission hết hạn được cập nhật.

Yêu cầu của chủ dự án: kiểm tra luồng dọn rác, lập kế hoạch để Gemini sửa; ảnh do tester gửi trong báo cáo chỉ được lưu trên Firebase tối đa 30 ngày. Tài liệu này là bàn giao triển khai; chưa thay đổi code production, chưa triển khai hoặc xóa dữ liệu cloud.

Rà soát source tại HEAD `020e550e0f3fdd63f11d1c72419409e499365879`, cùng working tree hiện có. Các thay đổi cache Android đang có của người dùng không thuộc lần chỉnh tài liệu này.

Đã đối chiếu migration 041, schema gộp, FK/cascade, vòng đời campaign/mission/report, upload worker, Firebase helper/rules, model và UI ảnh. Chưa chạy SQL trên PostgreSQL, cron thật, Firebase/GCS hoặc Android trong lượt rà soát này. Không có bằng chứng bucket live chưa được cấu hình thủ công; kết luận thiếu cơ chế Firebase áp dụng cho repo, không suy đoán trạng thái cloud.

## 2. Phần đã làm đúng

- Migration `041_campaign_retention.sql` archive trong trigger `BEFORE DELETE`, rồi xóa campaign cha để FK cascade xử lý bảng con. Không cần tự xóa từng bảng rồi mới archive.
- Hàm purge mặc định giữ campaign `completed/production` 90 ngày, có batch giới hạn, `FOR UPDATE SKIP LOCKED`, dry-run, log và savepoint riêng cho từng campaign lỗi.
- Purge chặn campaign còn report pending hoặc mission được xem là chưa kết thúc; archive/log/purge bị thu hồi quyền client, chỉ service role/backend được gọi.
- Đường upload mới dùng `reports/<campaignId>/<userId>/<uploadId>.jpg`, retry tái sử dụng object. Rules cấm client ghi đè/xóa, giảm nguy cơ đổi bằng chứng sau khi nộp.

Giữ những cơ chế này khi sửa. Thời hạn campaign 90 ngày và thời hạn ảnh 30 ngày là hai chính sách khác nhau.

## 3. Các vấn đề có bằng chứng

| Mã | Mức | Bằng chứng source | Hệ quả / yêu cầu sửa |
| --- | --- | --- | --- |
| R01 | P1 | `041_campaign_retention.sql:24–25` ghi rõ chưa dọn Firebase. `firebase.json:1–5` chỉ triển khai rules; không có worker/lifecycle/scheduler Firebase trong repo. | Upload thành công nhưng RPC thất bại, ảnh bị thay, campaign/account bị xóa đều có thể để lại object. Chưa có hạn 30 ngày được triển khai bằnesource. |
| R02 | P1 | `full_schema_singapore.sql` không chứa `purge_completed_campaigns`, `archive_campaign_before_delete`, `campaign_archive`, `cleanup_runs` hoặc trigger archive của 041. | Dựng backend bằng schema gộp sẽ thiếu toàn bộ retention 041. `check_mirror.sh` có kiểm tra object thiếu nhưng cần thực sự chạy và bổ sung xác nhận định nghĩa. |
| R03 | P1 | `041:250–254` chặn theo `missions.status`, không xét `ends_at`; `040_mission_management.sql:282–296` cập nhật expired trong `get_open_missions()`. | Mission `open/full/paused_by_creator` hết hạn vẫn chặn purge nếu không ai mở màn hình. Phải expire bằng backend độc lập hoặc xét hạn trong predicate đúng nghiệp vụ. |
| R04 | P2 | `040:55–60` và `040:118–122`: cập nhật expired rồi `RAISE EXCEPTION`. | Trong RPC lỗi, UPDATE bị rollback; bấm pause/resume không phải cơ chế đáng tin để đồng bộ hết hạn. Sửa trả kết quả lỗi nghiệp vụ có cấu trúc nếu cần lưu trạng thái, hoặc tách đồng bộ khỏi transaction bị rollback. |
| R05 | P1 | `FirebaseStorageHelper.kt:34,154–156` đặt screenshot `public, max-age=31536000, immutable`; cùng hằng số dùng cho icon. | Cache có thể tiếp tục phục vụ ảnh cũ dù origin đã xóa. Tách chính sách cache screenshot khỏi icon; không đặt cache một năm cho ảnh có hạn ngắn. |
| R06 | P1 | `Models.kt:215–235` chỉ có URL/hash và thời gian report, không có thời gian object hết hạn; `ImageViewerComponents.kt:241–268` đưa URL hiện tại/cũ vào so sánh ảnh. | Sau khi xóa ảnh, giao diện và so sánh có thể tải URL chết. Cần trạng thái media đã hết hạn, giữ nội dung báo cáo và ngừng tải ảnh cũ. Không dùng ngày tạo report làm tuổi object. |
| R07 | P2 | `041:244–248` bỏ qua pending; job không tự giải quyết pending. `039_missions.sql:646+` có hàm auto-accept riêng. `041:369–386` chỉ NOTICE nếu không đăng ký được cron. | Khi job auto-accept/cron bị thiếu hoặc lỗi, dữ liệu có thể bị giữ mãi. Cần kiểm tra trạng thái job thực, backlog và cảnh báo; không coi migration chạy thành công là cron đã hoạt động. |
| R08 | P2 | `041:256–258,277–289,377–380`: xét batch 100 cũ nhất mỗi ngày; bản ghi lỗi được chọn lại vào lượt sau. | Tốc độ tối đa 100 campaign/ngày, có nguy cơ nghẽn và lỗi lặp ở đầu hàng đợi. Cần theo dõi backlog, xử lý nhiều batch có giới hạn và backoff cho lỗi lặp. |
| R09 | P2 | `storage.rules:9–14,21–25` cho create theo size/type, không yêu cầu auth; app đang dùng Supabase Auth. `033_media_integrity.sql:57–79` chỉ kiểm tra URL bằng LIKE với bucket bất kỳ. | Người ngoài có thể tạo nhiều ảnh rác; URL được nhận chưa chứng minh object thuộc bucket thật. Cần giới hạn upload đáng tin và xác thực host/bucket/object chính xác. Đặc biệt worker xóa không được tin URL client. |
| R10 | P2 | `041` chỉ dọn các bảng cascade từ campaign. `notifications.data` chứa ID trong JSON, không có FK campaign; archive/log có thể tăng mãi. | Cần inventory và chính sách riêng cho thông báo, log dọn rác, archive, dữ liệu nhiệm vụ ngày. Không tự áp hạn 30 ngày cho sổ credit hoặc dữ liệu nghiệp vụ khác. |

Lưu ý về archive: chính `041:27–30` đã thừa nhận snapshot lúc xóa không bảo toàn toàn bộ số liệu lịch sử nếu tester bị xóa trước đó. Không quảng bá archive này là thống kê hoàn thành bất biến. Nếu cần, thêm snapshot lúc hoàn thành trong giai đoạn tiếp theo; kiểm thử riêng đường xóa tài khoản do có nhiều cascade cùng lúc.

## 4. Chính sách ảnh cần triển khai

1. Chỉ áp dụng cho screenshot báo cáo ở prefix `reports/`, gồm cả path mới và path cũ `reports/<campaignId>/<userId>_<timestamp>.jpg`. **Không áp lifecycle 30 ngày lên toàn bucket hoặc `app_icons/`.**
2. Mốc gốc là `timeCreated` do GCS cấp khi upload hoàn tất. Deadline = mốc này + 30 × 24 giờ, tính UTC. Không tin timestamp trong tên file, metadata do client tự khai hoặc đồng hồ điện thoại.
3. Hạn áp dụng cho mọi object thuộc phạm vi: có report, orphan, report bị từ chối/thay ảnh, campaign còn mở/đã hoàn thành/đã xóa. Pending không được kéo dài hạn ảnh. Khi report chưa xử lý gần deadline, cảnh báo và giải quyết nghiệp vụ riêng, không giữ ảnh vượt hạn.
4. Không đổi thời hạn ảnh khi report được sửa/duyệt hoặc khi cập nhật metadata. `created_at` của report hiện có thể được đặt lại khi nộp lại (`037_reward_10_credits.sql:218,280`); vì vậy không thể dùng cột đó để dọn ảnh.
5. “Tối đa 30 ngày” cho phép xóa sớm. Phương án vận hành: bắt đầu xóa từ tuổi **29 ngày**, worker chạy mỗi giờ, có lifecycle dự phòng cùng tuổi 29 và cảnh báo trước deadline. Một ngày còn lại là khoảng dự phòng retry, không phải thời gian được phép vượt quá 30 ngày.
6. Mọi object còn tồn tại từ 30 ngày trở lên là vi phạm cần cảnh báo và xử lý, không báo job thành công chung chung. Chưa có bằng chứng vận hành thì không tuyên bố đã đáp ứng hạn trên cloud.

Google xác nhận lifecycle thực thi bất đồng bộ và ứng dụng không nên dựa vào một thời gian xóa cố định sau khi điều kiện đạt. Vì vậy riêng rule `age: 30` không chứng minh được trần 30 ngày. Worker chạy sớm cũng không tạo bảo đảm tuyệt đối khi hạ tầng ngừng hoạt động kéo dài; cần công bố giới hạn này, đo vi phạm và có quy trình xử lý. Nguồn: [Object Lifecycle Management](https://docs.cloud.google.com/storage/docs/lifecycle).

Soft delete có thể giữ thêm bản sao sau lệnh xóa; thay đổi policy không rút ngắn hạn của bản đã soft-delete. Phải kiểm tra và xử lý trước khi cam kết giới hạn lưu trữ. Nguồn: [Soft delete](https://docs.cloud.google.com/storage/docs/soft-delete).

Nếu Object Versioning từng bật, phải kiểm kê cả generation noncurrent; tắt versioning không xóa bản cũ. Nguồn: [Object Versioning](https://docs.cloud.google.com/storage/docs/object-versioning).

## 5. Thứ tự triển khai cho Gemini

### Bước A — Preflight và xác nhận hiện trạng live, chỉ đọc

- Đọc source mới nhất và giữ các sửa cache/nộp báo cáo đang có; không reset working tree.
- Kiểm tra Supabase đã có migration 041 hay chưa; so định nghĩa hàm, trigger, quyền, FK, index. Không chỉ dựa vào lịch sử migration khi dự án đang hướng dẫn chạy SQL bằng tay.
- Kiểm tra `pg_cron`, job lifecycle campaign, auto-accept 8h và purge; lịch, `active`, lần chạy gần nhất và lỗi. Không gọi hàm purge để “kiểm tra chỉ đọc”: ngay cả `p_dry_run=true` hiện vẫn ghi `cleanup_runs` và khóa campaign.
- Kiểm kê campaign đủ tuổi bị chặn vì pending/mission; tuổi pending; mission hết hạn nhưng status chưa đổi; backlog campaign.
- Đọc bucket cấu hình từ `app/google-services.json`; source hiện trỏ `cross-testers.firebasestorage.app`. Xác nhận project/bucket thực, policy lifecycle hiện có, soft delete, versioning, hold, bucket/object retention lock và quyền service account. Không ghi secret vào báo cáo.
- Inventory `reports/` phân trang: số object/bytes, tuổi lớn nhất, số >=29 ngày, số >=30 ngày, generation cũ, soft-deleted và cache metadata. Chỉ log thống kê; manifest xóa hạn chế quyền, không chứa download token.
- Xác nhận không có job ngoài repo đã làm cùng việc trước khi thêm scheduler mới.

Các truy vấn đầu tiên có thể chạy độc lập:

```sql
select to_regprocedure('public.purge_completed_campaigns(integer,boolean,integer)') as purge,
       to_regprocedure('public.archive_campaign_before_delete()') as archive_trigger_fn,
       to_regclass('public.campaign_archive') as archive_table,
       to_regclass('public.cleanup_runs') as cleanup_log;

select extname from pg_extension where extname = 'pg_cron';

select tgname, pg_get_triggerdef(oid)
from pg_trigger
where tgrelid = 'public.campaigns'::regclass and not tgisinternal;
```

Chỉ truy vấn `cron.job`, `cron.job_run_details` hoặc `cleanup_runs` sau khi xác nhận các object tồn tại. Lưu báo cáo preflight riêng.

### Bước B — Sửa retention PostgreSQL bằng migration bổ sung

- Tạo migration kế tiếp còn trống, dự kiến `043_backend_cleanup_hardening.sql`; không sửa lịch sử 041/042 đã phát hành. Kiểm tra lại số migration khi bắt đầu làm.
- Đồng bộ đầy đủ 041 bị thiếu vào schema gộp **trước** nội dung hardening phụ thuộc vào nó. Mirror migration mới và kiểm tra cả cài mới bằng full schema lẫn nâng cấp tuần tự. Không được chỉ thêm tên hàm để vượt script mirror.
- Có hàm đồng bộ mission hết hạn dành cho backend, chạy định kỳ độc lập màn hình và trước purge. Chuyển `open/full/paused_by_creator` có `ends_at <= now()` sang expired; không ghi đè trạng thái kết thúc khác. Bảo toàn thưởng mission/credit và kiểm tra settlement trước khi xóa campaign.
- Giữ điều kiện không xóa campaign pending hoặc mission còn thật sự hoạt động; nếu đổi predicate phải dùng trạng thái **và thời gian**, không nới bỏ điều kiện bảo vệ tùy tiện. Khóa campaign và kiểm tra lại điều kiện trước DELETE; kiểm thử đua với thao tác tạo mission/nộp report.
- Bổ sung phát hiện pending quá hạn và cảnh báo job auto-accept lỗi. Không gộp auto-accept toàn hệ thống không giới hạn vào mỗi batch purge; tránh job dọn rác trở thành transaction lớn xử lý credit.
- Giữ archive-before-delete, savepoint từng campaign, RLS/EXECUTE restriction, mặc định campaign 90 ngày, batch clamp và dry-run. Phân biệt dry-run có ghi log/khóa với báo cáo preflight chỉ đọc.
- Lưu lỗi theo campaign với retry/backoff có hạn; không để 100 campaign lỗi chặn campaign khỏe ở các lượt sau. Worker điều phối nhiều batch có time budget, giới hạn transaction và metrics backlog.
- Kiểm tra cron đăng ký thực sự thành công ở bước deploy. Nếu migration vẫn dùng NOTICE vì cần hỗ trợ môi trường không có pg_cron, readiness phải báo chưa sẵn sàng thay vì kết luận hoàn tất.
- Đặt lịch tránh giao dịch dài chồng job phạt/auto-accept; khi không dùng pg_cron phải có scheduler backend thay thế được cấu hình bằng source.

### Bước C — Dọn ảnh bằng GCS độc lập PostgreSQL

Chọn một implementation backend duy nhất, ưu tiên Cloud Run Job/Function + Cloud Scheduler vì thao tác trực tiếp GCS; có thể dùng Edge Function nếu đã có hạ tầng phù hợp. Không thêm hai scheduler cạnh tranh. Repo hiện chưa có runtime này, cần thêm source, dependency lock, cấu hình triển khai và runbook đầy đủ.

- Worker chỉ nhận bucket/prefix từ cấu hình allowlist, chỉ quét `reports/`; không nhận URL hoặc bucket tùy ý từ client. Không dùng API Firebase client để xóa.
- Dùng server identity với quyền list/get/delete cần thiết và phạm vi hẹp nhất có thể; credential chỉ trên backend. Scheduler endpoint cần xác thực, không tạo HTTP endpoint xóa công khai.
- List phân trang toàn bộ object/generation trong phạm vi, tiếp tục bằng cursor bền vững hoặc thiết kế nhiều shard. Dọn theo tuổi GCS, không chỉ join từ bảng report: DB có thể đã cascade mất hàng hoặc chưa từng nhận báo cáo.
- Không duyệt đi duyệt lại trang đầu rồi bỏ sót trang sau; không nạp toàn bucket vào RAM. Giới hạn concurrency, retries exponential backoff/jitter và thời gian mỗi lượt. Mọi shard phải được quét kịp trong khoảng dự phòng 29→30 ngày.
- Re-read metadata cần thiết, xóa đúng generation bằng precondition. Not-found của generation đích là thành công idempotent; precondition conflict phải đọc lại, không xóa generation mới mù quáng. Phân biệt 403/429/5xx/hold lỗi cần xử lý.
- Có dry-run thật cho worker storage, manifest dự kiến và log số lượng/bytes/thời gian/lỗi/tuổi lớn nhất, không log token hoặc nội dung ảnh. Worker vẫn dọn được khi Supabase ngừng hoạt động; DB cập nhật trạng thái media được retry riêng.
- Sau xóa, kiểm tra object/generation không còn; dùng trạng thái rõ `deleted`, `already_missing`, `failed`, không chỉ đếm số request đã gửi. Theo dõi orphan và ảnh bị thay; trước deadline có thể giữ orphan tới ngày 29 để không đua retry upload→RPC (pending local đang cho 48 giờ).
- Lifecycle là lớp dự phòng, merge với policy hiện hữu; không ghi đè các rule không liên quan. Rule đề xuất cho phạm vi này:

```json
{
  "rule": [
    {
      "action": { "type": "Delete" },
      "condition": { "age": 29, "matchesPrefix": ["reports/"] }
    }
  ]
}
```

Đây là nội dung lifecycle minh họa để merge, **không phải** lệnh thay toàn bộ policy bucket và không phải SLA xóa chính xác.

**Điều kiện bắt buộc trước rollout:** không còn soft delete/versioning/hold/retention policy khiến ảnh thuộc phạm vi được giữ vượt 30 ngày. Soft delete là cấu hình cấp bucket, prefix `reports/` không tách riêng policy này. Với bucket chung chứa icon, phải ghi rõ ảnh hưởng của việc tắt recovery lên icon; nếu cần giữ recovery cho icon, chuyển upload report mới sang bucket riêng có policy phù hợp. Di chuyển bucket không tự xử lý ảnh report cũ: phải có phương án riêng cho bucket cũ, bao gồm những bản đã soft-delete mà hiện không thể rút ngắn thời gian giữ. Báo cáo tồn đọng và ngày hoàn tất thực tế; không tuyên bố đã xóa sạch khi mới tắt policy.

### Bước D — Model media, URL, cache và giao diện

- Thêm metadata server đáng tin cho object: bucket, object path, generation, uploaded_at, expires_at/hạn hiển thị, deleted_at, trạng thái và lý do. Có thể dùng bảng media riêng để nhiều ảnh cũ của cùng report không mất dấu. Worker inventory vẫn là nguồn dọn orphan độc lập.
- `uploaded_at/expires_at` phải lấy từ GCS metadata qua backend xác minh; không để client tự kéo dài hạn. Backfill metadata từ object thật, không đoán từ report.created_at.
- Nếu bắt đầu dọn từ ngày 29, API cần mô tả rõ thời điểm có thể ngừng phục vụ; không hiển thị lời hứa ảnh chắc chắn tồn tại đủ 30 ngày. Sau xóa, báo “Ảnh đã hết thời hạn lưu trữ”, giữ nội dung/status/rating/hash cần cho nghiệp vụ; không xóa report/credit chỉ vì ảnh hết hạn.
- Cập nhật RPC trả report, receipt/idempotent retry, `Models.kt` và các màn owner/admin/campaign; model nullable/default để tương thích response cũ. Khi worker xóa object và DB chưa cập nhật kịp, xử lý missing như media không còn, tránh spinner/retry liên tục. Không gắn nhãn hết hạn cho mọi lỗi mạng/403.
- Tách cache screenshot khỏi icon trong `FirebaseStorageHelper`: đề xuất screenshot `private, no-store`; giữ tối ưu cache icon hiện tại. Sửa metadata screenshot đang có bằng backend; client vẫn bị cấm update/delete. Rules phải kiểm soát cache metadata cho upload mới hoặc chuyển upload qua backend để client không tự đặt cache một năm.
- Coil/viewer/so sánh ảnh cần gate bằng trạng thái/hạn media trước khi dùng RAM/disk cache, hủy request/đóng ảnh khi hết hạn trong lúc mở app. Không chỉ dựa header mới để vô hiệu cache đã tồn tại. Dọn cache screenshot do app quản lý theo metadata; tránh xóa toàn bộ icon cache không cần thiết.
- Không coi đổi rules hoặc null URL trong DB là xóa object. Firebase `downloadUrl` là link sống lâu với token có thể thu hồi; nếu cần chặn truy cập chính xác tại deadline ngay cả khi job xóa gặp lỗi, dùng đường phục vụ có kiểm tra hạn ở server hoặc signed URL hết hạn không muộn hơn deadline, đồng thời loại đường public/token cũ. Đây là cơ chế truy cập riêng, không thay thế xóa storage.

Cache-Control quản lý khả năng lưu cache, nên xóa origin không đồng nghĩa mọi cache đã nhận bản sao được thu hồi. Nguồn: [Cloud Storage caching](https://docs.cloud.google.com/storage/docs/caching). Tính chất download URL: [Firebase StorageReference.getDownloadUrl](https://firebase.google.com/docs/reference/android/com/google/firebase/storage/StorageReference#getDownloadUrl()).

### Bước E — Giảm phát sinh rác và chính sách các bảng khác

- Thắt upload report: backend xác minh Supabase JWT rồi cấp upload được giới hạn path/size/type/hạn/quota, hoặc tích hợp Firebase Auth/App Check phù hợp. Không thêm `request.auth != null` vào rules hiện tại mà quên app chưa có Firebase Auth, làm hỏng toàn bộ upload.
- Parse/validate URL đúng host, bucket allowlist và object path; prefix giả trong query string không được chấp nhận. Nên liên kết receipt backend với object verified thay cho tin URL client. Giữ tương thích path cũ trong giai đoạn nâng cấp có giới hạn.
- Inventory thông báo, log cleanup, archive và bảng nhiệm vụ ngày; đề xuất thời hạn riêng bằng số liệu kích thước/tốc độ tăng. Chưa có yêu cầu đổi retention credit/audit nên không tự xóa các bảng này. Thông báo trỏ campaign đã purge cần UI xử lý đích không còn.
- Archive còn `owner_id/app_name/package_name`; không coi là dữ liệu ẩn danh hoàn toàn. Chốt mục đích lưu/ẩn danh riêng khi triển khai retention archive.

## 6. Kiểm thử nghiệm thu bắt buộc

| Nhóm | Ca cần chứng minh |
| --- | --- |
| Cài đặt DB | Full schema tạo được archive/log/purge/trigger; nâng cấp tuần tự cho cùng định nghĩa; migration mới chạy lại an toàn, không mất dữ liệu. |
| Campaign | Chưa đủ 90 ngày không xóa; đủ hạn và không blocker mới xóa; pending và mission thật sự còn hạn được giữ; mission hết hạn chưa ai mở màn hình không chặn mãi. |
| Cascade/archive | Owner delete, admin delete, purge, account cascade; archive tạo trước khi cascade trong đường campaign; lỗi archive rollback campaign tương ứng; xác nhận giới hạn snapshot khi profile cascade. |
| Lỗi và concurrency | Một campaign lỗi không chặn campaign khác; nhiều campaign lỗi đầu hàng đợi không gây starvation; worker đồng thời; submit/create mission cạnh purge; timeout/crash không làm báo thành công sai. Dùng PostgreSQL nhiều connection cho race. |
| Quyền | anon/authenticated không gọi được purge/media cleanup, không sửa/xóa ảnh; caller sai JWT, sai user/campaign/bucket bị chặn. Signed upload hết hạn, quota và cache metadata được kiểm soát. |
| Tuổi ảnh | Clock giả lập: trước ngưỡng 29 ngày giữ; chạm ngưỡng bắt đầu dọn; deadline 30 ngày có detector vi phạm; sửa/duyệt report, đổi metadata hoặc tên file không đổi tuổi gốc. UTC không lệch nửa đêm VN. |
| Phạm vi ảnh | Path cũ/mới, report pending/rejected/approved, orphan, upload thành công RPC fail, ảnh bị thay, campaign/account đã xóa đều được xét; icon và prefix khác nguyên vẹn. |
| GCS | Nhiều trang/generation, 404, 412, 403, 429/5xx, retry, crash giữa chừng, concurrent worker, policy holds; không xóa generation mới; inventory noncurrent và soft-deleted đầy đủ. |
| Cache/UI | Ảnh hết hạn không được mở/so sánh từ cache cũ, kể cả màn đang mở; trạng thái DB trễ và object missing; mạng lỗi không bị báo nhầm hết hạn; Android cũ không crash khi backend thêm metadata. |
| Credit/report | Không cộng/thu hồi credit hoặc đổi review vì media bị xóa; hash/idempotency và nộp lại sau rejected còn đúng; auto-accept/mission reward không hồi quy. |
| Vận hành | Scheduler thực chạy, retry được, alarm nhận đúng; không có job trùng; dung lượng lớn vẫn quét hết trước deadline; kiểm thử Firebase/GCS staging cho lifecycle/soft delete/metadata, không chỉ emulator. |

Chạy bộ regression liên quan hiện có sau khi sửa (backend và report upload); build Android nếu thay model/UI/helper. Ghi rõ test thực chạy, kết quả và phần chỉ mô phỏng. PGlite có thể kiểm tra SQL tuần tự nhưng không chứng minh cron thật hoặc race nhiều connection.

## 7. Rollout và điều kiện hoàn tất

1. Xuất preflight chỉ đọc và thống kê hiện trạng; lưu cấu hình bucket/scheduler/IAM hiện hữu để đối chiếu. Không tạo thêm bản sao ảnh báo cáo vào nơi lưu không có hạn tương ứng.
2. Triển khai DB/UI chịu được media expired trước; giữ mặc định retention campaign 90 ngày.
3. Triển khai worker storage ở dry-run, kiểm tra manifest chỉ có `reports/` đúng bucket và tuổi; xác minh soft delete/versioning/hold trước khi bật xóa.
4. Canary ở staging rồi batch nhỏ trên cloud theo phạm vi đã chốt; kiểm tra storage thật, DB và UI sau xóa. Xử lý tồn đọng >=30 ngày ngay trong rollout theo manifest, không chờ campaign bị purge sau 90 ngày.
5. Bật lịch mỗi giờ và lifecycle dự phòng; ghi nhận lần chạy thật. Theo dõi tối thiểu một chu kỳ scheduler và đo đường biên tuổi bằng fixture staging; không cần đợi 30 ngày mới kiểm thử logic.
6. Metrics tối thiểu: số/bytes ảnh >=29 ngày, số/bytes ảnh >=30 ngày, oldest_object_age, lần chạy hoàn tất gần nhất, tuổi cursor chưa quét, lỗi delete, backlog retry và campaign bị blocker. Cảnh báo nếu job bỏ lỡ hai lịch giờ liên tiếp, backlog gần deadline hoặc có object >=30 ngày; cảnh báo cả khi không có run mới.
7. Rollback có thể dừng scheduler/đổi code, nhưng **không phục hồi ảnh đã xóa vĩnh viễn**. Khi phải dừng xóa vẫn theo dõi deadline và báo vi phạm; không rollback bằng cách kéo dài chính sách ảnh. Không coi “restore backup” là rollback được nếu đã tắt soft delete.
8. Hoàn tất khi source + cấu hình deploy + dry-run + test + bằng chứng cron/cloud đều có. Nếu chưa có quyền cloud, bàn giao artifact và lệnh triển khai cụ thể, ghi “chưa deploy/chưa xác minh live”; không báo đã thực hiện giới hạn 30 ngày.

## 8. Nội dung giao trực tiếp cho Gemini

> Đọc và triển khai tài liệu này theo thứ tự A→E. Ưu tiên R01/R02/R03/R05/R06. Giữ các sửa cache/nộp báo cáo đang có. Tạo migration bổ sung và đồng bộ full schema, sửa mission expiry độc lập UI, thêm worker + scheduler + lifecycle dự phòng dọn toàn bộ screenshot `reports/` theo GCS timeCreated: bắt đầu ngày 29, deadline tối đa ngày 30. Không áp chính sách này cho icon hoặc dữ liệu campaign 90 ngày. Xử lý soft delete/versioning/hold, orphan, cache và UI expired; không tin URL client để xóa. Thực hiện ma trận kiểm thử và preflight; báo cáo rõ việc nào đã test/deploy, phần nào chưa có bằng chứng live. Bàn giao source, migration, test, cấu hình cloud, hướng dẫn vận hành và báo cáo kết quả. Không đổi chính sách credit/audit ngoài phạm vi.
