# Vận hành Dual Storage — bản giám sát 16/11/2026

Ảnh mới được nén ở Android rồi xin reservation ổn định theo upload ID. Server chọn một provider; lỗi RPC không phải lý do tự chuyển Google. Google cũ vẫn đọc được. Supabase dùng bucket private report-screenshots, cấm upsert và giới hạn byte theo reservation.

## Điều kiện trước khi bật

1. Áp dụng chuỗi migrations hiện hành đến 092 theo quy trình staging; không sửa 091 đã triển khai, reset database hoặc replay archive.
2. Cấu hình local_project_ref trên hàng id=1 của public.storage_organization_usage bằng project ref thực. Thêm đúng hostname Supabase của dự án vào public.storage_trusted_hosts (không scheme/path; không wildcard). Không dùng host fixture example.supabase.co.
3. Cấu hình B bằng hạn mức Storage thực của toàn tổ chức, đơn vị byte. Không có giá trị gói mặc định đáng tin cậy. Migration 092 vô hiệu hóa metric cũ; upload mới dùng Google cho đến khi đối soát hợp lệ.
4. Token Management API phải thấy toàn bộ inventory của tổ chức và đọc metadata Storage ở mọi project. Inventory phân trang được kiểm tra trước và sau đo; danh sách tự khai không thể thay thế inventory.
5. Bật worker cleanup và reconciler, giám sát lỗi/backlog/SLA. Đây là cấu hình vận hành cần triển khai riêng; mã nguồn không chứng minh scheduler đang hoạt động.

## Biến môi trường

| Biến | Dịch vụ | Ý nghĩa |
|---|---|---|
| SUPABASE_URL | Cả hai worker | URL chính xác dự án chứa ledger |
| SUPABASE_SERVICE_ROLE_KEY | Cả hai worker | Chỉ dùng server, không đưa Android/log |
| SUPABASE_STORAGE_BUCKET | Cleanup | Chỉ chấp nhận report-screenshots |
| SUPABASE_ORG_SLUG | Reconciler | Tổ chức cần đo, bắt buộc |
| SUPABASE_MANAGEMENT_TOKEN | Reconciler | Token Management API |
| SUPABASE_TOTAL_CAPACITY_BYTES | Reconciler | Tổng hạn mức gói thực, chuỗi số nguyên byte dương |
| SUPABASE_ORG_PROJECT_REFS | Reconciler | Tùy chọn; nếu khai phải khớp đầy đủ inventory |
| DRY_RUN | Cả hai worker | true: không gọi RPC ghi/Storage delete |

Tên cũ SUPABASE_MANAGEMENT_API_TOKEN và SUPABASE_ORG_CAPACITY_BYTES được hỗ trợ. Hai tên cùng cấu hình khác nhau sẽ thất bại an toàn. Không ghi secrets vào repository.

Cài dependency bằng npm ci trong mỗi thư mục backend. Cleanup khai báo trực tiếp @supabase/supabase-js; không dựa vào node_modules của dịch vụ khác.

## Kế toán hạn mức

U là byte Storage của mọi bucket trong mọi project được inventory xác nhận. R là byte local chưa được tính vào U, gồm phần giữ chỗ cho upload đang hoạt động và object đã finalize nhưng chưa được snapshot tính vào U.

Reconcile đọc metadata local trong một MVCC snapshot để đồng bộ U và quota_accounted_bytes của từng ledger. Finalize giữ phần byte chưa đo trong R, không cộng U một lần nữa. Ack xóa chỉ trừ phần byte đã biết thuộc U, và chỉ một lần. Các RPC khóa hàng quota trước hàng ledger theo cùng thứ tự. RLS INSERT của object cũng giữ khóa quota trong transaction metadata để không có upload đang commit bị finalize/reconcile bỏ sót.

Tại U+R >=95% B, đổi google_overflow. Chỉ trở lại supabase_preferred khi U+R <90% B. Giữa hai ngưỡng giữ mode. Upload riêng không vừa phần còn lại được chọn Google mà không thay provider cho ảnh đã được pin. Metric thiếu/sai/quá30phút chuyển upload mới sang Google.

Đo các project khác là snapshot định kỳ, không phải giao dịch nguyên tử trên toàn tổ chức. Các writer ngoài hệ thống vẫn có thể thay đổi U giữa hai lần đo. Cần headroom và tần suất đối soát phù hợp tải thực. Collector dùng endpoint read-only query đang ở trạng thái experimental; thay đổi schema API phải khiến phép đo thất bại an toàn.

## Retention và lịch chạy

| Provider | Ngừng xem / bắt đầu dọn | Hạn xóa |
|---|---|---|
| Supabase | 216h từ created_at thực của object cũ nhất trong cặp | Trước 240h |
| Google | Chính sách cũ: dọn từ ngày29 | Ngày30 |

Lịch đề xuất: cleanup mỗi5phút, reconcile mỗi15phút; phải đo khả năng xử lý backlog trên staging. Worker chỉ nhận Supabase đúng bucket/prefix, xóa từng main/thumbnail qua API rồi ack UUID/lease token. Lỗi một file không được coi cả cặp đã xóa. Lease hết hạn có thể được worker sau nhận lại. Metadata provider không được xóa bằng SQL trong vận hành.

Cleanup live còn inventory object từ216h thiếu ledger, kể cả object tồn tại sau ack sai trước đây. Dry-run chỉ xem ledger hiện có, không tạo ledger cho orphan; do đó số preview không thể dùng làm bằng chứng đã kiểm kê toàn bộ orphan.

Lệnh kiểm tra không ghi:

- node backend/storage-cleanup/cleanup-supabase-storage.mjs --dry-run
- node backend/storage-cleanup/reconcile-org-storage.mjs --dry-run

Bỏ --dry-run là chạy thật, chỉ thực hiện sau khi đã rà soát môi trường. Không chạy worker thật trên production trong lượt sửa này.

## Cảnh báo và xử lý

- SUPABASE_SLA_RETENTION_VIOLATION: object >=240h hoặc quá delete_deadline_at; kiểm tra quyền Storage, lease,429/5xx, worker và backlog ngay.
- SUPABASE_MEDIA_CLEANUP_FAILED / DELETE_ACK_REJECTED: giữ ledger để thử lại, không tự đánh dấu deleted.
- ORG_METRICS_FAIL_SAFE: kiểm tra organization slug, quyền token, inventory đủ project, hạn mức B và metadata. Không tự thay bằng quota 1GiB.
- CLI trả mã khác0 khi cleanup có lỗi/SLA/backlog hoặc metric không hợp lệ. Scheduler phải cảnh báo từ exit code và log; không chỉ ghi log rồi bỏ qua.

## Kiểm chứng bắt buộc trước production

- Upload thật main/thumbnail, restart app và gửi lại;409 cùng byte phải được kiểm tra nội dung.
- Direct authenticated và gateway đọc được cả Google cũ/Supabase mới, token không gửi tới origin lạ.
- Ngừng xem đúng216h; lỗi partial delete/crash/429/lease rồi retry; xóa vật lý trước240h.
- Đối soát toàn tổ chức, thiếu một project, thay đổi inventory, hết quyền token và đổi gói.
- PostgreSQL nhiều kết nối chạy bằng TEST_DATABASE_URL trên database trống đã migrate092, tên 12tester_test_*, thêm DUAL_STORAGE_TEST_DISPOSABLE=yes. Runner không reset/migrate database; thiếu URL là NOT RUN.
- PGlite và fake Storage không chứng minh chính sách Storage API/RLS, scheduler hoặc concurrency thực đã đúng.

Tạm dừng Supabase cho upload mới: service gọi reconcile_storage_organization_usage với p_is_valid=false. Không xóa bucket/ledger và không tắt cleanup ảnh đã tồn tại.
