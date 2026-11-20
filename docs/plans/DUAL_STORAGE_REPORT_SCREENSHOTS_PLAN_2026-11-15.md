# Kế hoạch lưu ảnh báo cáo bằng Supabase Storage và Google Cloud

Ngày lập: 15/11/2026. Trạng thái: đã chốt yêu cầu và ba lựa chọn theo phản hồi của người dùng; sẵn sàng làm căn cứ triển khai sau bước xác minh môi trường. Đây là kế hoạch, chưa thay đổi mã sản phẩm, database hoặc cấu hình máy chủ.

## 1. Yêu cầu đã xác định

- Ảnh báo cáo cũ trên Google Cloud tiếp tục hiển thị từ nguồn cũ, theo chính sách hết hạn hiện tại của nguồn đó.
- Ảnh báo cáo mới được nén bằng luồng hiện tại, ưu tiên lưu Supabase Storage.
- Khi tổng dung lượng Storage của toàn bộ các dự án/bucket trong tổ chức Supabase đạt 95% toàn bộ hạn mức Storage của gói, chuyển các lượt tải lên mới sang Google Cloud. Tính cả phần dung lượng đang giữ chỗ để không vượt ngưỡng do upload đồng thời.
- Sau khi đã chuyển sang Google Cloud, chỉ tự ưu tiên Supabase trở lại khi tổng dung lượng tính cả giữ chỗ giảm xuống dưới 90%; tại đúng 90% vẫn giữ chế độ Google.
- Ảnh Supabase, bao gồm thumbnail và file tải lên chưa gắn được vào báo cáo, bắt đầu bị xóa từ tuổi 9 ngày; hoàn tất dọn trong thời hạn tối đa 10 ngày, có khoảng dự phòng 1 ngày để retry và xử lý cache.
- Duy trì hai nguồn lưu; không mặc định tạo hai bản sao cho mỗi ảnh. Không tự chuyển ảnh hết hạn Supabase sang Google để kéo dài thời gian lưu.
- Chỉ xóa file ảnh và cache liên quan; giữ nội dung báo cáo, trạng thái duyệt, hash, dấu vết gửi báo cáo và dữ liệu điểm thưởng.
- Phạm vi là ảnh chụp màn hình báo cáo. Icon app, avatar và media khác giữ luồng hiện tại.

## 2. Ba lựa chọn đã được người dùng chốt

| Mã | Vấn đề | Phương án đã chọn | Hệ quả triển khai |
|---|---|---|---|
| Q1 | Mẫu số của mốc 95% | Toàn bộ hạn mức Storage của gói Supabase | Thu thập tổng byte hiện có của mọi dự án/bucket cùng chia sẻ hạn mức trong tổ chức; không chỉ đếm bucket ảnh báo cáo hoặc riêng project 12Tester |
| Q2 | Khoảng dự phòng xóa trước hạn 10 ngày | Bắt đầu xóa từ tuổi 9 ngày, có khoảng dự phòng 1 ngày | Quét mỗi 5 phút; ngừng cho xem từ tuổi 216 giờ, xóa ảnh chính và thumbnail, retry và giám sát đến hạn tối đa 240 giờ |
| Q3 | Khi Supabase trống lại | Tự trở lại Supabase khi dưới 90% | Lưu trạng thái điều phối ở server; trong khoảng từ 90% đến dưới 95%, giữ chế độ hiện có để tránh đổi qua lại liên tục |

Chưa xác minh gói Free/Pro/Team/Enterprise và số GB thực tế của tài khoản. Đây là dữ liệu cần xác minh trước triển khai, không phải lựa chọn chính sách còn chờ người dùng. `capacity_bytes` phải phản ánh toàn bộ hạn mức Storage hiện hành của tổ chức, với đơn vị GB/GiB được chuyển đổi chính xác; không tự đặt một hạn mức riêng cho ảnh báo cáo. Khi gói hoặc hạn mức thay đổi phải cập nhật cấu hình server và tính lại trạng thái điều phối.

Dung lượng Storage, Cached Egress và Uncached Egress là ba đại lượng khác nhau; mốc 95% ở đây chỉ điều khiển dung lượng lưu trữ. Dùng số byte hiện đang chiếm chỗ, không lấy số GB-giờ tích lũy trong kỳ thanh toán để tính phần trăm đầy.

Yêu cầu xóa tối đa 10 ngày được coi là mục tiêu vận hành bắt buộc. Worker định kỳ không thể bảo đảm xóa vật lý đúng hạn tuyệt đối khi nhà cung cấp bị gián đoạn kéo dài. Phải có khoảng dự phòng, cảnh báo, worker dự phòng và kiểm tra việc xóa thật; chặn xem không được ghi nhận thay cho xóa vật lý.

## 3. Căn cứ từ mã nguồn hiện tại

| Thành phần | Hiện trạng và tác động |
|---|---|
| `util/FirebaseStorageHelper.kt` | Chuẩn bị ảnh tối đa 1080 px, JPEG chất lượng 80, giới hạn byte 1.900.000; tạo thumbnail 360 px; tải ảnh và thumbnail lên Firebase. Giữ nguyên bytes và cơ sở tính hash khi đổi nơi lưu. |
| `work/ReportUploadWorker.kt` | Gửi nền qua WorkManager, lưu URL sau upload để retry RPC không tải ảnh lại; hỗ trợ nhiều attachment. Thêm việc chọn và ghi nhớ nguồn trước upload. |
| `data/PendingReportStore.kt` | Lưu trạng thái gửi trên đĩa, URL từng attachment và receipt. Chưa có lựa chọn provider/reservation bền vững. |
| `data/SupabaseHolder.kt` | Client đang cài Auth và PostgREST; cần thêm tích hợp Storage tương thích dependency hiện có hoặc adapter HTTP. |
| `data/Models.kt` và `util/ScreenshotManager.kt` | Hết hạn ảnh còn dựa vào 30 ngày ở cấp báo cáo. Phải bổ sung thời hạn theo nguồn và từng attachment. |
| `util/ScreenshotRequestFactory.kt`, `ScreenshotCoilInterceptor.kt`, `PrivateScreenshotDiskCache.kt` | Có nhận diện URL, cache và metadata. Cache đĩa mặc định tắt; đường ảnh báo cáo hiện đặt `private, no-store`. Không chỉ bật cờ để tuyên bố đã có CDN cache. |
| `backend/media-gateway/` | Adapter hiện đọc Google Cloud; gateway và cache đĩa mặc định tắt trong Android. Phải xác minh cấu hình production, không suy ra từ source. |
| `backend/storage-cleanup/` | Worker riêng cho Google, mục tiêu dọn từ tuổi 29 ngày và giám sát mốc 30 ngày; không thể dùng nguyên trạng để dọn Supabase. |

Đã tra chỉ mục SQL có kiểm tra checksum. Vị trí định nghĩa cuối trong source hiện tại:

- `is_valid_report_screenshot_url` và `is_valid_report_thumbnail_url`: migration 062; hiện chỉ chấp nhận URL/bucket Google/Firebase được phép.
- `_submit_tester_report_core`, `get_campaign_detail`, `get_campaign_reports_for_owner`, `admin_get_tester_reports_queue`: migration 087.
- `submit_tester_report_v3` và các wrapper v2/legacy: migration 074.
- `request_report_media_access(uuid, uuid)` và `verify_media_access_grant_internal` sáu tham số: migration 081; wrapper năm tham số ở 074 phải giữ tương thích.
- `get_campaign_detail_sync_v2`: migration 085; cần kiểm tra revision/cache khi media đổi trạng thái.

Các vị trí trên là source local, không chứng minh production đã áp dụng. Khi triển khai phải tra lại chỉ mục và đọc định nghĩa, quyền, trigger, caller liên quan tại thời điểm đó. Không sửa migration đã triển khai; thêm migration tiếp nối, không cố định số migration mới từ kế hoạch này.

## 4. Thiết kế dữ liệu và quyền

### 4.1. Định danh ảnh

- Tạo sổ quản lý media do server quản lý, tách khỏi dữ liệu điểm: `upload_id`, chủ sở hữu, campaign, attachment, provider, bucket, object path, kích thước chính/thumbnail, thời điểm tạo được server xác thực, hạn xem, hạn xóa, thời điểm xóa, trạng thái và reservation.
- Provider là `supabase` hoặc `gcs`. Object path cố định theo campaign/user/upload ID, cùng cách ghép tên thumbnail; không ghi đè object đã chốt.
- Lưu đường dẫn ổn định và provider làm nguồn chuẩn. Duy trì các cột URL hiện có để tương thích; không lưu signed URL có thời hạn làm định danh lâu dài.
- Ảnh Google cũ được nhận diện từ URL/bucket allowlist khi chưa có metadata mới. Chỉ đối soát theo đợt nếu cần, không di chuyển file hàng loạt.
- Một báo cáo nhiều ảnh có thể có attachment ở hai provider; từng cặp ảnh chính/thumbnail dùng cùng provider và cùng hạn hết hạn. Không đánh dấu cả báo cáo hết ảnh khi mới chỉ một attachment hết hạn.
- Metadata ảnh Supabase mới phải có đường đi tới mọi API đọc của tester/chủ app/admin và sync V2; không chỉ cập nhật API gửi.

### 4.2. Bảo vệ truy cập

- Bucket Supabase riêng tư; quyền upload/read dựa vào phiên Supabase và quan hệ tác giả/chủ app/admin/moderator đang được dự án cho phép, có kiểm tra khóa tài khoản theo hợp đồng hiện hành.
- Không đưa service role hoặc khóa ký vào Android. Server phát quyền cho object path cụ thể đã giữ chỗ; giới hạn MIME, kích thước và thời gian upload.
- RLS phải ngăn đường upload trực tiếp bỏ qua reservation, chọn đường dẫn của người khác, tự tăng kích thước hoặc overwrite. Xác thực cả metadata thật sau upload; request từ client không phải số liệu tin cậy.
- Giữ quyền Google hiện hành trong giai đoạn chuyển tiếp; không siết quyền Google cũ mà làm ứng dụng đang phát hành mất ảnh.
- File Supabase được nộp báo cáo phải được server xác minh đã tồn tại và thuộc reservation hợp lệ, không chỉ vượt qua regex URL.

## 5. Chọn nguồn ở mốc 95%

Định nghĩa `B` là toàn bộ hạn mức Storage tính bằng byte của gói Supabase hiện hành trong tổ chức; `U` là tổng byte thực tế đang lưu của mọi project/bucket cùng chia sẻ hạn mức; `R` là phần còn giữ chỗ của các upload chưa hoàn tất chưa tính vào `U`; `N` là tổng byte ảnh nén và thumbnail sắp tải.

Server lưu chế độ `supabase_preferred` hoặc `google_overflow`. Từ chế độ ưu tiên Supabase, khi `U + R >= 0,95 × B` thì chuyển sang Google. Sau khi đã chuyển, chỉ thoát chế độ Google khi `U + R < 0,90 × B` và số liệu tổng dung lượng còn đủ mới; tại đúng 90% hoặc trong khoảng 90–95% không tự chuyển trở lại. Các upload đã cấp quyền tiếp tục theo provider đã giữ chỗ, không đổi nguồn giữa chừng.

- Nếu `U + R >= 0,95 × B`, chọn Google cho upload mới.
- Nếu `U + R + N > 0,95 × B`, cũng chọn Google ngay, không để một upload mới đẩy qua mốc. Dùng số nguyên byte, không làm tròn phần trăm khi quyết định.
- Nếu đang ở chế độ ưu tiên Supabase và đủ chỗ, giữ chỗ `N` bằng giao dịch/khóa hàng trước khi trả quyền upload Supabase. Một ảnh lớn không đủ chỗ dưới ngưỡng thì riêng upload đó sang Google; không đổi ngưỡng 95% chỉ vì kích thước ảnh.
- Cập nhật `U` và phần reservation còn lại phải tránh đếm trùng khi object đã tồn tại nhưng client chưa nhận phản hồi. Nếu thumbnail lỗi, chỉ giải phóng phần chưa tạo sau khi quyền upload tương ứng hết hiệu lực.
- Reservation hết hạn chỉ được giải phóng sau khi thu hồi/chờ hết quyền upload và đối soát upload muộn. Không để chữ ký còn hiệu lực tiếp tục tạo object sau khi đã hoàn trả dung lượng.
- Quyết định được lưu ở server theo upload ID, và lưu bền vững trong pending report/attachment trên điện thoại. Retry dùng lại nguồn/path đã chọn; không gọi lại phép chọn nguồn cho cùng upload.
- Kiểm tra object tồn tại trước khi tái upload sau timeout, xác minh fingerprint/kích thước thay vì suy ra upload thất bại. Giữ idempotency nộp báo cáo và canonical payload/receipt hiện hành.
- Mặc định chỉ chuyển nguồn vì dung lượng hoặc thao tác quản trị đã cấu hình. Lỗi đăng nhập/quyền không được tự chuyển Google để vượt qua kiểm tra. Mất mạng hoặc lỗi 5xx xử lý retry có giới hạn và thông báo hiện tại; không ngầm mở rộng điều kiện fallback.
- Job đối soát định kỳ đọc metadata Storage để sửa sai lệch counter, tính cả file mồ côi. Không SUM toàn kho cho mỗi ảnh. Bắt buộc có bộ thu thập số liệu cấp tổ chức: liệt kê các project liên quan và đọc dung lượng của từng project bằng quyền server thích hợp, không giả định SQL trong project 12Tester có thể đọc project khác. Thay đổi danh sách project/bucket phải được phát hiện và cập nhật.
- Số liệu phải kèm thời điểm đo, danh sách project đã đo và sai số/dung lượng đang tải từ các luồng khác. Chỉ cho phép trở lại Supabase dưới 90% khi đã đối soát đủ phạm vi; không dùng số liệu thiếu project để kết luận còn chỗ. Nếu không thu thập được tổng hiện hành, chọn Google cho upload mới và cảnh báo. Trước rollout phải xác minh nguồn đo được hỗ trợ, chu kỳ lấy mẫu và giới hạn độ cũ thực tế; chưa có nguồn đo thì chưa bật ưu tiên Supabase.
- Reservation của 12Tester chống vượt ngưỡng do các tester của ứng dụng gửi đồng thời. Các project hoặc ứng dụng khác không tham gia cơ chế này vẫn có thể ghi thêm giữa hai lần đo; không hứa ngăn tổng tổ chức vượt 95% tuyệt đối trong trường hợp đó. Muốn bảo đảm đồng thời trên toàn tổ chức cần mọi đường ghi cùng tham gia bộ điều phối; nếu chưa làm được phải theo dõi độ trễ số liệu và dùng chính sách Google khi số liệu không còn đáng tin cậy.
- Với số liệu không đủ tin cậy, không cấp thêm reservation Supabase; trả lựa chọn Google chỉ khi server vẫn xác thực được quyền nộp báo cáo. Nếu toàn bộ Supabase Auth/database lỗi thì lưu hàng đợi và retry, không hứa vẫn gửi báo cáo thành công nhờ Google.
- Dọn bớt file không tự di chuyển các ảnh đang có giữa hai nguồn. Q3 chỉ ảnh hưởng upload mới.

## 6. Thời hạn Supabase tối đa 10 ngày

### 6.1. Mốc thời gian

- Tính 10 ngày là 240 giờ kể từ thời điểm object ảnh chính được server Storage tạo; dùng UTC. Không dùng giờ điện thoại hoặc ngày nộp báo cáo để kéo dài tuổi file.
- Thumbnail không sống lâu hơn ảnh chính; upload/retry thumbnail muộn không đặt lại đồng hồ. Ảnh chưa finalize vẫn bị dọn bằng metadata Storage thật, không phụ thuộc việc có bản ghi báo cáo.
- `view_expires_at` và `cleanup_due_at` là thời điểm tạo + 216 giờ (9 ngày); `delete_deadline_at` là thời điểm tạo + 240 giờ (10 ngày). Đến tuổi 216 giờ phải chặn xem độc lập với việc worker đã chạy hay chưa; worker lần kế tiếp bắt đầu/tiếp tục xóa, có 24 giờ dự phòng để hoàn tất. Không mô tả ảnh Supabase là được xem đủ 10 ngày.
- Không đổi tất cả con số 30 thành 10: 30 ngày còn liên quan campaign và nghiệp vụ khác. Chỉ áp dụng chính sách mới cho media Supabase, giữ Google theo chính sách hiện hành.

### 6.2. Worker và đối soát

- Worker Supabase riêng chạy mỗi 5 phút, phân trang theo hạn xóa và ID; lấy lease để hai worker không xử lý trùng theo cách gây sai counter.
- Quy trình: đánh dấu chặn xem/deleting -> xóa full và thumbnail qua Storage API -> xác nhận kết quả -> lưu deleted_at/tombstone -> cập nhật dung lượng đúng một lần -> phát revision/invalidation cache cần thiết.
- Không DELETE trực tiếp `storage.objects` để thay cho xóa file. Chỉ đọc metadata phục vụ thống kê/đối soát; thao tác file qua Storage API. Batch nằm trong giới hạn API hiện hành.
- Nếu một file đã xóa nhưng file còn lại chưa xóa: retry phần còn lại. Không coi cả cặp đã xóa hoặc giải phóng cả cặp trước khi xác nhận. Chỉ 404/NotFound rõ ràng được coi là đã vắng; 403/5xx không phải bằng chứng xóa thành công.
- Quét bổ sung file chưa gắn báo cáo, upload dở, thumbnail mồ côi và file có metadata thiếu. Metadata lỗi phải được đối soát theo dữ liệu tin cậy, không âm thầm bỏ qua file đến quá hạn.
- Lease chống upload muộn: server kiểm tra hạn trước finalize; quyền upload tồn tại ngắn và không được phép tạo lại object đã tombstone. Pending cũ không tự hồi sinh ảnh đã hết hạn.
- Cảnh báo khi worker trễ quá 10 phút, deletion thất bại liên tiếp, tuổi file tiến sát 240 giờ, hoặc còn file từ 240 giờ trở lên. Có worker dự phòng chạy được độc lập với scheduler chính và runbook chạy lại có phạm vi bucket/prefix rõ ràng.
- Log riêng: đến hạn, bắt đầu xóa, xóa Storage thành công, cache invalidated và quá hạn. Không dùng riêng `media_expired=true` làm chứng cứ file đã được xóa vật lý.
- Worker Google tiếp tục theo lịch hiện hành; tách cấu hình retention, allowlist và prefix để không xóa nhầm Google ở mốc 10 ngày.

## 7. Hiển thị và cache

- Bổ sung expiry/provider theo attachment cho `TesterReport`, `ReportAttachment`, descriptor và API đọc. Mọi đường preview, zoom, so sánh và tính hash từ URL đều dùng cùng chính sách.
- Kiểm tra hạn trước đọc RAM/đĩa, sau trả lời mạng và trước hiển thị. Khi màn hình mở qua hạn thì ẩn ảnh, bỏ cache và hiện “Ảnh báo cáo đã hết thời gian lưu trữ”. Không yêu cầu người dùng nộp lại chỉ vì file hết hạn.
- Cache điện thoại tách theo tài khoản, xóa khi đăng xuất/đổi tài khoản; TTL không vượt hạn media. Sửa nhận diện Supabase theo host/project/bucket được phép, không chỉ kiểm tra chuỗi `/reports/`.
- Đề xuất đọc Supabase bằng URL ký do backend kiểm tra quyền cấp, dùng lại URL khi còn dùng được trong cùng phiên/tài khoản; Google giữ đường đọc tương thích. Không tạo signed URL mới trong mỗi lần recomposition.
- Thời gian URL, TTL cache và thời hạn được xem là ba tham số khác nhau. Không coi signed URL hết hạn là bằng chứng CDN đã ngừng trả ảnh. Chọn TTL ngắn, không gia hạn URL/cache sát hạn dọn; xóa object chủ động và giám sát CDN invalidation trước mốc tối đa.
- Supabase mô tả cache CDN có thể cần tới khoảng 60 giây để vô hiệu sau xóa. Dùng khoảng dự phòng 1 ngày đã chọn và kiểm thử thực tế; TTL trên điện thoại và thời gian cấp quyền phải được giới hạn theo mốc ngừng xem 216 giờ. Không khẳng định có thể thu hồi bản người dùng đã tải hoặc chụp lại.
- Nếu yêu cầu quyền xem phải được kiểm tra lại ở từng request, giữ phương án gateway thay cho signed URL trực tiếp và đo thêm độ trễ/egress. Chốt hợp đồng quyền trước khi chọn đường CDN; không đổi bucket thành public để tăng cache hit.
- Không cam kết mọi lượt tải là Cached Egress. Theo dõi HIT/MISS, byte, p50/p95 và dung lượng cache thật trên thiết bị thử nghiệm. Luôn ưu tiên thumbnail cho danh sách và ảnh lớn khi mở xem.
- Phiên bản Android cũ có thể không hiểu provider/expiry mới. Kiểm thử bản đang phát hành với payload thêm trường, URL private và ảnh hết hạn; nếu không đọc được Supabase thì bổ sung đường media tương thích hoặc nâng phiên bản tối thiểu trước rollout. Không tuyên bố tương thích chỉ vì API cũ vẫn nhận upload Google.

## 8. Các bước thực hiện

1. **Xác minh môi trường theo các lựa chọn đã chốt:** xác định gói và B thực tế, toàn bộ project/bucket trong tổ chức, nguồn đo tổng dung lượng và độ trễ, dữ liệu đang dùng, vùng máy chủ, gateway/cache flags thực tế và phiên bản Android đang phát hành. Ghi hợp đồng quyền xem và cấu hình 95%/90%/216 giờ/240 giờ trước khi viết mã.
2. **Database và backend:** tạo migration tiếp nối cho metadata/ledger/reservation/RLS, allowlist hai nguồn, các RPC cấp quyền/finalize và payload đọc. Giữ overload cũ, idempotency, role được phép và quyền điểm thưởng. Tạo bucket qua API/quy trình provisioning được hỗ trợ.
3. **Worker Supabase:** triển khai scheduler, deletion queue, đối soát, cảnh báo và dry-run. Chứng minh xóa được object thật trong môi trường thử trước khi bật upload sản xuất.
4. **Android upload:** adapter theo provider; giữ prepare/compression/hash; lưu nguồn và trạng thái trước upload; đảm bảo full/thumbnail cùng nơi; cập nhật retry và phân loại lỗi Supabase.
5. **Android đọc ảnh:** hỗ trợ Google/Supabase, expiry theo từng ảnh, private cache và các màn tester/chủ app/admin; cập nhật invalidate sync V2.
6. **Kiểm thử và thử có kiểm soát:** chạy test mục 9, smoke test hai nhà cung cấp trong môi trường thử, đo tốc độ và HIT/MISS. Theo dõi ít nhất một chu kỳ dọn tăng tốc trước khi thử nội bộ.
7. **Rollout:** backend có khả năng đọc hai nguồn trước, cleanup/monitor hoạt động trước, sau đó Android và cờ upload Supabase; tăng dần tỷ lệ. Không tự build APK/AAB, tăng phiên bản, deploy production hay chạy xóa thật trong giai đoạn chỉ lập kế hoạch.
8. **Rollback:** tắt cờ upload Supabase để ảnh mới sang Google; giữ đọc/xóa ảnh Supabase đã tồn tại, giữ metadata/reservation/receipt. Không rollback bằng xóa bảng, bucket hoặc rút hỗ trợ nguồn Supabase.

## 9. Kiểm thử và tiêu chí nghiệm thu

| Nhóm | Trường hợp bắt buộc |
|---|---|
| Ngưỡng | Dưới/đúng/trên 95%; upload kế tiếp vượt mốc; đúng 90% vẫn Google và dưới 90% mới trở lại; giữ chế độ trong khoảng 90–95%; thay đổi gói; số nguyên byte; dữ liệu thiếu/cũ; tổng dung lượng nhiều project/bucket |
| Đồng thời | Nhiều tester giữ chỗ sát mốc 95%, hai worker finalize cùng ID, retry giải phóng reservation, upload đến muộn, counter không âm và không tính hai lần |
| Upload | Một/ba attachment; full thành công nhưng timeout phản hồi; thumbnail thất bại; RPC lỗi sau upload; app bị kill; đổi tài khoản; pending từ bản cũ; Google được chọn trước khi upload |
| Thời hạn | Trước/đúng/sau 216 giờ phải ngừng xem và đến lịch xóa; trước/đúng/sau 240 giờ xác minh xóa vật lý và cảnh báo; report gửi muộn; thumbnail tạo muộn; file chưa gắn báo cáo; metadata lỗi; không dùng giờ client |
| Xóa vật lý | Kiểm tra qua Storage API; xóa một phần; 404/403/429/5xx; crash giữa các bước; worker dừng; worker dự phòng; không xóa icon/avatar hoặc Google theo hạn 10 ngày |
| Đọc ảnh | Ảnh Google cũ; ảnh Supabase mới; attachment trộn hai nguồn; ảnh hết hạn từng phần; preview/zoom/compare/hash/admin; đồng bộ sau expiry |
| Cache và quyền | RAM/đĩa/CDN qua hạn; logout/đổi tài khoản; URL ký cũ; người ngoài campaign; role bị khóa; giả path/provider/size; tạo lại object đã xóa |
| Nghiệp vụ | Không thay đổi điểm, duyệt báo cáo, chống nộp trùng, nhiệm vụ ngày, count báo cáo và receipts khi ảnh hết hạn |
| Hiệu năng | Đo cùng ảnh nén, Wi-Fi/5G, lần đầu/lần xem lại, thumbnail/full; không đặt chỉ tiêu tốc độ hoặc tỷ lệ cached khi chưa có baseline |

- SQL: kiểm thử migration tiếp nối trên trạng thái schema thực tế và fixture nâng cấp có ảnh Google cũ; test quyền, wrappers và RPC đọc hiện hành. Thêm lệnh test mới rõ ràng, không giả định `npm test` hiện tại bao phủ.
- Test cạnh tranh phải dùng PostgreSQL nhiều kết nối với `TEST_DATABASE_URL`; thiếu biến là NOT RUN, không phải PASS. Có thể bổ sung PGlite cho logic tuần tự nhưng không thay thế kiểm thử cạnh tranh thật.
- Android: mở rộng test upload nền/pending, multi-attachment, nhận diện URL, expiry và session cache phù hợp. Chạy Gradle test theo lớp liên quan, không build gói phát hành để kiểm thử tài liệu.
- Backend: unit test chọn nguồn, adapter hai provider, cleanup và retry; smoke test bằng bucket thử và dữ liệu giả. Kết quả dry-run không thay thế bằng chứng xóa thật.
- Chỉ bật rộng khi không cấp thêm Supabase upload vượt ngưỡng đã giữ chỗ, Google cũ xem được, ảnh Supabase bị xóa thật đúng chính sách, dữ liệu nghiệp vụ còn nguyên và đường rollback đã thử.

## 10. Nguồn kỹ thuật đã kiểm tra

- Supabase — Storage size usage: https://supabase.com/docs/guides/platform/manage-your-usage/storage-size . Hạn mức/dung lượng tính theo tổ chức; có thể đọc metadata để thống kê. Phải xác minh gói tài khoản thật trước khi đặt B.
- Supabase — Storage schema: https://supabase.com/docs/guides/storage/schema/design . Metadata không phải file thật; coi bảng Storage là read-only khi thao tác file.
- Supabase — Delete objects: https://supabase.com/docs/guides/storage/management/delete-objects . Xóa bằng API; không xóa metadata SQL thay file; hiện remove tối đa 1.000 object/lần.
- Supabase — Smart CDN: https://supabase.com/docs/guides/storage/cdn/smart-cdn . Signed URL có cache key theo token, URL expiry và cache TTL độc lập; xóa object làm invalidation CDN có độ trễ.
- Supabase — Egress: https://supabase.com/docs/guides/platform/manage-your-usage/egress . Cached/uncached có hạn mức độc lập; không có cơ chế mặc định dùng hết cached trước.

Tài liệu nhà cung cấp được kiểm tra lúc lập kế hoạch; khi triển khai cần xác minh lại hành vi/quota hiện hành. Kế hoạch này không sử dụng dữ liệu sản xuất hoặc đo thực tế để kết luận về tốc độ, dung lượng còn trống hay mức phí tài khoản.
