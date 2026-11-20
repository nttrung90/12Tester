# Bàn giao sửa Dual Storage — 16/11/2026

Trạng thái: đã hoàn tất sửa mã và các bộ kiểm tra local liên quan; không còn ca thất bại trong phạm vi đã chạy. Chưa nghiệm thu vận hành production.

## Điều khiển và phạm vi

Đã điều khiển Antigravity đang mở, xác nhận Gemini 3.8 Flash High. Gemini thực hiện Android và test; giám sát trực tiếp sửa SQL/backend, rà soát và sửa tiếp pipeline Android, chạy probe độc lập và hồi quy nghiệp vụ. Không deploy, commit/push, tăng phiên bản hoặc build APK/AAB trong lượt này.

Không sửa migration 091 đã có. SHA256 vẫn là 3a1dc6f2a5998fb697fe2c841af46532f5df885a777c3617bc37d71215b69486. Bổ sung 092_dual_storage_hardening.sql. Source sinh 092 và các mảnh SQL có trong thư mục audit để tái lập; SQL triển khai là file trong supabase/migrations, không phải chỉ mục.

## Các sửa đổi đã kiểm chứng

| Phát hiện | Cách xử lý |
|---|---|
| F01: đường đọc | Android xác thực direct private URL; gateway bootstrap dual adapter, parse đúng Google/Supabase; exact origin và không chuyển token tới origin lạ |
| F02: RLS/hạn xem | Policy đọc kiểm tra tài khoản, ledger, tuổi Storage thật và216h; gateway kiểm tra canonical deadline cả trước và sau tải |
| F03: nguồn ảnh | URL host cấu hình chính xác; finalize buộc đúng actor/campaign/reservation/object/owner/size/cặp thumbnail; Android409 đối chiếu SHA256 |
| F04: đồng hồ | Lấy created_at vật lý; giữ đồng hồ khi retry; nâng cấp sửa ledger và attachment091 đã có, không đặt tuổi về0 |
| F05: U+R | Finalize giữ phần chưa đo trong R; reconcile snapshot local đồng bộ với quota_accounted_bytes; ack chỉ trừ phần đã được đo, đúng một lần |
| F06: retry cleanup | Lease UUID và thời hạn thật, reclaim sau crash; dry-run không ghi/khóa FOR UPDATE; inventory orphan trong lượt live |
| F07: xóa đúng | Chỉ Supabase đúng bucket/prefix; xác nhận từng full/thumbnail;404 idempotent,403 không thành công; retry429/5xx có giới hạn |
| F08: ack | UUID + token + đúng đủ path + metadata đã vắng; idempotent và khóa; overload cũ từ chối upload ID không duy nhất |
| F09: metric | Không đoán quota1GiB; inventory tổ chức phân trang trước/sau đo; read-only metadata tất cả bucket; byte số nguyên chính xác; lỗi fail-safe Google |
| F10: bằng chứng | Backend/SQL có runner/log, index đã tạo lại; Android XML được lưu riêng; PG thật và Storage thật phân biệt NOT RUN |

Sửa thêm lỗi nghiệp vụ do091 gây ra: nộp lại nhiệm vụ bị từ chối phải có ảnh mới; bảo toàn thưởng tạm tính, thưởng tester, check-in, mission và receipts. Hai suite mission/bonus đều đạt qua090, thất bại qua091. 092 phục hồi nguyên core nghiệp vụ cuối trước091 (định nghĩa087, vẫn là bản hiện hành qua090) thành helper private; wrapper media kiểm tra/finalize nguyên tử trong cùng giao dịch. Không viết lại rút gọn luồng thưởng.

Sửa thêm cleanup: dọn object cũ không đánh dấu hết hạn attachment đã được thay bằng ảnh mới dù cùng attachment UUID. Quyền cấp/kiểm tra grant dùng cùng vai trò author/owner/admin; Google có chữ supabase trong filename không bị áp216h.

Android phục hồi upload quá hạn: chỉ xác minh object đã tồn tại, không POST tạo lại hoặc đổi provider; lỗi mạng/429/5xx retry, thiếu object hoặc hash sai là terminal. Giữ cam kết thumbnail khi cache local mất; cancellation được truyền tiếp.

RLS INSERT tham gia cùng khóa quota đến khi metadata commit, để finalize/reconcile không bỏ sót thumbnail đang hoàn tất. Runner PostgreSQL có thêm ca chờ transaction INSERT thực rồi xác minh U/R; ca này chưa chạy khi thiếu database test.

Hạn xem UI được tính theo attachment đang chọn: gallery không bị hạn của cha chặn toàn bộ; timer lightbox/compare, cache, đường tải thực và hash dùng descriptor tương ứng. Hash của ảnh phụ không lấy nhầm hash ảnh chính. Provider Google không bị thay bởi filename có chữ supabase; nhận diện URL Supabase dùng origin dự án cấu hình chính xác.

## Kiểm thử local

Nguồn bằng chứng chính: FINAL_VALIDATION.json và final-*.log trong cùng thư mục. Lệnh tái lập:

    node audit/dual-storage-review-2026-11-16/run-final-validation.mjs
    node tools/ai-sql-index.mjs check

| Bộ kiểm tra | Kết quả |
|---|---|
| Backend cleanup/reconciler/dual gateway, gồm Google cũ | 73 PASS |
| Media probe độc lập, fixture092 hợp lệ | 14 PASS |
| SQL quota/95%/90%/pinning/finalize/grant | 8 PASS |
| SQL hardening/đổi thứ tự reconcile-finalize/quyền/orphan/ack/ngưỡng chính xác | 20 PASS |
| Nâng cấp dữ liệu091 có đồng hồ sai | PASS |
| So nghiệp vụ090 với hiện tại | 9 PASS |
| Mission reward modes & management | 15 kịch bản PASS theo runner |
| Campaign tester bonus | 17 nhóm PASS theo runner |
| SQL index | fresh:true;31migrations;262functions |
| Android cuối, gồm pipeline tải/hash/so sánh từng ảnh | 170 PASS;17suites;0failures/errors/skipped;XML/log đã lưu |
| PostgreSQL nhiều kết nối thật | NOT RUN: thiếu TEST_DATABASE_URL |
| HTTP server gateway | 13 PASS trong môi trường Antigravity; log gateway-http-final.log. Lệnh root bị EACCES đã được kiểm chứng lại bằng runner native |
| Upload/xóa provider thật, scheduler/CDN/thiết bị và client cũ | NOT RUN |

Bộ review-probes.mjs gốc và bản baseline không sửa/hạ assertion; SHA256 gốc vẫn f49436f1dbb86a039ade466163fee22e9c926183dce82c78cfb1d54d7f91095d. Runner run-supervisor-media.mjs sinh bản chạy riêng giữ assertions nhưng bổ sung cấu hình host/ref trong DB test, clock lease hết hạn thực, dry-run RPC mới, quota_accounted_bytes fixture và acknowledgement success. Mọi thay đổi fixture ghi tại supervisor-fixture-changes.json. Không seed host fixture vào migration sản phẩm.

Runner nhiều kết nối thực đã được triển khai tại supabase/tests/run-dual-storage-concurrency-test.mjs: nhiều session tranh cùng ID, tranh ngưỡng95%, hai cleanup worker, duplicate ack. Chỉ nhận database test trống đã migrate092, tên12tester_test_*, thêm DUAL_STORAGE_TEST_DISPOSABLE=yes. Không reset/migrate hoặc xóa dữ liệu production. Chưa có kết quả chạy PostgreSQL thực; PGlite không thay thế kiểm tra đó.

## Việc còn lại trước khi bật production

1. Cấu hình local_project_ref, exact trusted Supabase hostname, organization slug, token đủ quyền toàn tổ chức và B thực. Migration cố ý giữ Google khi thiếu metric/config.
2. Chạy concurrency PostgreSQL thực trên database test dùng riêng.
3. Smoke test Storage API/RLS thật: upload/409/retry, direct/gateway, mixed attachment; lỗi xóa từng phần/crash và mốc216/240h.
4. Triển khai/đo scheduler5phút, đối soát15phút, cảnh báo exit code/backlog/SLA; kiểm tra CDN/cache/điện thoại và bản Android đang phát hành.

Runbook cấu hình và rollback: backend/storage-cleanup/RUNBOOK_DUAL_STORAGE.md. Manifest chốt nguồn/test: FINAL_REVIEW.json. Không sử dụng kết quả local để tuyên bố đã xóa file thật đúng hạn hoặc production ready.
