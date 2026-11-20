# Kiểm tra lại Dual Storage sau bản sửa mới nhất — 16/11/2026

**Kết luận: chưa nên nghiệm thu hoặc bật rộng upload Supabase.** Phát hiện 2 lỗi tái hiện trực tiếp và 1 rủi ro kế toán quota tái hiện bằng mô hình thứ tự sự kiện, cần kiểm chứng bằng Storage/PostgreSQL thật.

18 tệp có checksum trong FINAL_REVIEW.json vẫn khớp bản chốt lúc09:28:31Z ngày16/11. Chỉ mục SQL còn fresh,31migrations/262functions. Không có migration sau092 trong cây triển khai hiện tại. Việc tệp khớp không thay thế kiểm tra giao thức của nhà cung cấp.

Lượt này chỉ thêm phép kiểm tra/báo cáo trong audit và tài liệu kế hoạch; không sửa mã sản phẩm, lịch sử migrations hoặc production.

## R01 — P0: policy INSERT từ chối bước preflight của Storage

**Vị trí:** supabase/migrations/092_dual_storage_hardening.sql:99–121, đặc biệt điều kiện metadata.size tại108/114.

Policy yêu cầu size trước khi cho INSERT. Mã Storage chính thức được đối chiếu truyền contentLength ở preflight của binary upload; đây chưa phải metadata cuối cùng sau ghi file [S1].

Phép kiểm tra độc lập tạo reservation hợp lệ, đúng campaign/user/path/quota, sau đó thử quyền và INSERT dưới role authenticated:

| Metadata | Helper chấp nhận | INSERT authenticated |
|---|---|---|
| size=100, mimetype=image/jpeg (fixture trước) | true | Đường fixture cũ hoạt động |
| contentLength=100, mimetype=image/jpeg (preflight) | **false** | **42501: new row violates row-level security policy for table objects** |
| Chỉ mimetype, chưa có độ dài | false | Chỉ ghi nhận; từ chối độ dài chưa biết có thể là fail-closed chủ đích, không tính thành lỗi riêng |

Bằng chứng: storage-contract-results.json, các ca CONTROL-SIZE, STORAGE-PREFLIGHT; STORAGE-LEGACY-PREFLIGHT là quan sát bổ sung. Exit code1 có chủ đích vì trường hợp độ dài đã biết hợp lệ vẫn thất bại.

**Ảnh hưởng:** luồng Android POST file tới Supabase có thể bị chặn trước khi ghi file khi Storage dùng hợp đồng preflight này. Lượt review chưa truy vấn phiên bản Storage đang chạy và chưa thử upload production; đây là lỗi tương thích đã chứng minh bằng input hợp đồng và RLS thật trong PGlite, không phải báo cáo sự cố production.

**Vì sao test trước xanh:** test SQL gọi helper hoặc chèn metadata đã có size; test Android trả response HTTP giả, không chạy qua RLS/Storage server. Chúng không bao phủ preflight. Kết quả170Android/86backend trước đó không chứng minh upload Storage thật thành công.

## R02 — P1: khóa trong policy không chứng minh giữ đến actual commit

**Vị trí:**
- 092:104 — FOR UPDATE trên hàng quota trong policy.
- 092:205–214 — finalize giảm phần giữ chỗ theo các object đã nhìn thấy.
- supabase/tests/run-dual-storage-concurrency-test.mjs:65 trở đi — gọi helper rồi INSERT và COMMIT cùng transaction.

Storage testPermission rollback phép thử quyền. Luồng hoàn tất được đối chiếu còn có caller-policy probe trong savepoint trước privileged write [S1][S2]. PostgreSQL giải phóng khóa lấy sau savepoint khi rollback về savepoint [S3].

Runner hiện tại không mô phỏng khoảng trống đó: nó giữ transaction chứa helper đến COMMIT, do đó kiểm tra mô hình mạnh hơn đường ghi đã đối chiếu. Không thể dùng runner này để chứng minh RLS lock bao trùm actual commit.

**Mô hình độc lập đã chạy:**

1. Giữ chỗ cho main và thumbnail; main100byte đã tồn tại.
2. Policy probe cho thumbnail50byte được chấp nhận với metadata cuối; rollback savepoint của phép thử.
3. Application finalize khi thumbnail chưa xuất hiện; R còn100byte.
4. Privileged metadata write của thumbnail50byte hoàn tất.
5. U=0, R=100 trong khi object thực trong fixture là150byte: **thiếu50byte trong U+R**.

Bằng chứng: QUOTA-LATE-THUMB trong storage-contract-results.json.

Đây là **mô hình thứ tự sự kiện trên một PGlite**, không phải phép thử cạnh tranh nhiều kết nối hoặc chạy Storage server; không dùng nó làm bằng chứng đo khóa thực giữa các session. Nó cô lập rủi ro thiết kế sau khi R01 được sửa; R01 hiện còn chặn preflight sớm. Chưa kết luận đã quan sát vượt95% trên production.

**Hướng xử lý:** không giải phóng ngân sách cho các write còn có thể hoàn tất chỉ vì finalize chưa thấy thumbnail. Cần chọn và kiểm chứng hợp đồng hoàn tất upload/giữ ngân sách trên Storage version thực; không coi khóa trong permission probe là khóa của actual commit. Không dùng session advisory lock trong policy như một cách vá nhanh vì kết nối Storage có thể được tái sử dụng.

## R03 — P1: SQL báo metric không hợp lệ nhưng worker vẫn báo thành công

**Vị trí:** backend/storage-cleanup/reconcile-org-storage.mjs:127,143–144,159. SQL trả fail-safe tại092:1801–1802 và1818–1819.

Collector đặt is_metric_valid=true trước RPC. Khi RPC trả success=true nhưng is_org_metric_valid=false, worker chỉ nhận dispatch_mode, không nhận trạng thái invalid. CLI dùng cờ collector để trả exit code.

Phép kiểm tra injected RPC trả:

    success=true
    is_org_metric_valid=false
    dispatch_mode=google_overflow

Kết quả worker:

    is_metric_valid=true
    fail_safe_triggered=false
    dispatch_mode_result=google_overflow
    CLI exit code theo điều kiện hiện tại=0

Mong đợi: trạng thái cuối là invalid/fail-safe và exit khác0 trong lượt chạy live. Bằng chứng: reconcile-ack-results.json.

**Ảnh hưởng:** database vẫn fail-safe Google, nên trường hợp này không mở upload Supabase sai quyền. Nhưng scheduler/log có thể báo đối soát thành công trong khi backend đang từ chối metric, ví dụ thiếu local_project_ref/trusted host hoặc metadata local không hợp lệ. Điều này làm chậm phát hiện cấu hình sai và tính năng không chuyển về Supabase.

## Kết quả kiểm chứng lượt này

| Kiểm tra | Kết quả và giới hạn |
|---|---|
| Backend unit liên quan | Chạy mới73/73PASS,0fail |
| Storage contract probes mới | 1PASS,2FAIL,1quan sát; FAIL thuộcR01 và mô hìnhR02 |
| Reconcile acknowledgement probe mới | FAIL, tái hiệnR03 |
| SQL index | fresh:true,31migrations,262functions |
| Checksum18tệp đã chốt | Khớp; source-snapshot.json |
| Android170/170 và HTTP gateway13/13 | Bằng chứng từ lần chạy trước; lượt này không chạy lại, không gọi đó là test mới |
| PostgreSQL nhiều kết nối / Storage API thật / scheduler | Chưa chạy; không đổi thành PASS |

Các lệnh tái lập từ gốc dự án:

    node audit/dual-storage-recheck-2026-11-16/storage-contract-probes.mjs
    node audit/dual-storage-recheck-2026-11-16/reconcile-ack-probe.mjs

Cả hai lệnh hiện trả exit1 để thể hiện chưa đáp ứng hành vi yêu cầu. Chúng chỉ dùng PGlite hoặc injected RPC; không đọc credentials production, không gọi provider và không xóa file thật.

## Kế hoạch sửa tiếp theo thứ tự

1. **Chốt Storage version và sửaR01 trước.** Tách preflight khỏi kiểm tra actual metadata, hỗ trợ đúng dữ liệu binary upload của phiên bản đang dùng. Không chỉ bỏ kiểm tra size hoặc nới quyền rộng. Giữ kiểm tra user/campaign/path/reservation, bucket limit và kiểm tra byte thực sau upload. Tạo migration tiếp nối nếu092 đã áp dụng; không sửa lịch sử đã triển khai.
2. **Giải quyếtR02 cùng thay đổi upload contract.** Xác định trạng thái in-flight và thời điểm an toàn để giảm R. Sửa runner mô phỏng đúng probe rollback/savepoint rồi actual privileged write. Chạy PostgreSQL nhiều kết nối cùng Storage server; bao phủ thumbnail đến muộn, finalize/reconcile xen kẽ, crash và cleanup.
3. **SửaR03 ở reconciler.** Phân biệt metric thu thập được và metric SQL đã chấp nhận. Xử lý is_org_metric_valid=false sau RPC thành fail-safe/exit khác0 trong lượt live; dry-run vẫn chỉ đọc. Thêm các test RPCsuccess/invalid, RPCsuccess/valid và malformed acknowledgement.
4. Chạy lại bộ hồi quy local, tạo lại SQL index khi có migration mới, rồi smoke test main/thumbnail qua Storage API thật. Chỉ chốt bật tính năng sau khi preflight/upload/finalize/quota/cleanup thực đều đạt.

Không cần build APK/AAB, tăng phiên bản hay deploy để hoàn thành phần sửa source/test này.

## Nguồn kỹ thuật đối chiếu

- [S1 — Supabase Storage uploader.ts](https://github.com/supabase/storage/blob/master/src/storage/uploader.ts): authorizeUpload, prepareUpload, upload, completeUpload. Đã đọc ngày16/11/2026; tham chiếu upstream, không khẳng định trùng phiên bản đang triển khai.
- [S2 — Supabase Storage database/pg.ts](https://github.com/supabase/storage/blob/master/src/storage/database/pg.ts): testPermission, rollback và actual metadata write.
- [S3 — PostgreSQL Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html): khóa lấy sau savepoint được giải phóng khi rollback về savepoint.
