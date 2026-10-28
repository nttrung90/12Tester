# Nghiệm thu độc lập Firebase Egress E00–E05 — 28/10/2026

Kết luận: Các lỗi đã tái hiện trong phạm vi E00–E05 ở local đã được sửa và không còn tái hiện trong bộ kiểm thử chốt. Đây là nghiệm thu code/hồi quy cục bộ; chưa phải xác nhận production đã triển khai hoặc đạt mức tiết kiệm dự kiến.

Codex kiểm tra working tree, trực tiếp giao Antigravity/Gemini sửa mã qua ứng dụng, bổ sung hồi quy và chạy lại độc lập. Không dùng lời báo hoàn tất của Gemini thay cho bằng chứng thực thi. Các báo cáo ban đầu và kết quả đỏ được giữ trong `audit/` để đối chiếu.

## Đối chiếu 15 nhóm lỗi ban đầu

| Mã | Thay đổi và bằng chứng cần đối chiếu |
|---|---|
| F01 | Migration 045 giữ nghiệp vụ từ 037: credit/check-in, feedback, approved rating, rejected hash. `business-check.mjs` chạy cùng fixture trước/sau. |
| F02 | Validator full/thumb kiểm tra bucket, đường dẫn, cặp upload; effective full từ DB khi RPC bỏ tham số; chặn orphan/mismatch. `db-check.mjs`. |
| F03 | HMAC có secret thật, không fallback; thu hồi quyền đọc secret; bind người dùng/report/quyền/hạn; endpoint HTTP và ADC Storage adapter; Android provider/RPC/Bearer nối runtime; App Check SDK có provider/listener. Gateway HTTP, adapter, crypto-schema và Android client tests. |
| F04 | Persist cả full/thumb bền vững; worker giữ receipt qua RPC retry và dọn cả prepared/pending files. `EgressThumbnailPipelineAuditTest`. |
| F05 | Full mới xóa thumbnail cũ khi không có thumb mới; thumb-only phải khớp full hiện có. Database migration + strict assertions. |
| F06 | Thumbnail 404 fallback cả lần đầu và các lần xem tiếp; tombstone khác media-expired. Android regression. |
| F07 | Chụp UID/generation, kiểm tra phiên trước ghi/commit, xóa dữ liệu của generation cũ sau invalidation. Test clear trong lúc đọc body. |
| F08 | Session observer trong application scope xử lý restore async, logout/switch có application Context; contextless hooks không làm mất pending purge. `EgressRound2OptInTest`. |
| F09 | Fail-closed metadata expiry; no-store/no-cache, max-age/Age, clock skew; header sai/xung đột không thành cache mới 30 phút. Android policy tests. |
| F10 | Decode đĩa ARGB_8888 nhất quán network để giữ cơ sở dHash. Android legacy regression. |
| F11 | Tuổi thumbnail theo full, full dùng server timeCreated; kiểm tra parent ngoài listing/orphan sau lifecycle; generation guard/dry-run. Cleanup + independent retention tests. |
| F12 | Một đầu mối memory counter; không thu khi opt-out ở manager và Coil runtime. Android opt-in tests. |
| F13 | Export version/build/cohort/UTC window/body bytes; CLI nhận cloud metadata; thiếu/sai window/coverage trả UNKNOWN/INVALID; không tự tạo demo khỏe. tools-check + CLI test. |
| F14 | AppIcon gọi CDN routing, chỉ bucket/prefix hợp lệ; chặn bucket giả kể cả tên bucket hợp lệ nằm trong object path; fallback origin có giới hạn. Android policy/routing tests. |
| F15 | TCO phân biệt hit/miss, lookup/fill và marginal free allowance; mặc định không tự áp miễn phí; bác input sai. TCO + tools tests. |

## Kiểm thử chốt

| Kiểm tra độc lập trên bản cuối | Kết quả |
|---|---|
| Android JUnit XML | **117 tests, 14 suites; 0 failure, 0 error, 0 skipped** |
| `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | **BUILD SUCCESSFUL**, exit 0; 1 phút 14 giây |
| Lint XML | **0 error; 426 warning, 1 hint**; không tuyên bố dự án sạch mọi cảnh báo |
| Toàn bộ `*.test.mjs` trong backend, tools và audit vòng 2 | **53 tests, 4 suites; 53 pass, 0 fail/skipped** |
| `db-check.mjs` | **12/12 strict assertions đạt** |
| `crypto-schema-check.mjs` | Grant hợp lệ khi pgcrypto nằm trong schema `extensions`: đạt |
| `business-check.mjs` | **5 kịch bản nghiệp vụ trước/sau, 10/10 đối chiếu đạt** |
| Hai script migration 045/046 hiện có | Cả hai exit 0 |
| `git diff --check` | Exit 0; chỉ còn thông báo chuyển LF/CRLF |

117 Android tests gồm bộ hồi quy mới về opt-out, contextless purge, session restore/logout/switch runtime, pipeline persist/upload/RPC retry/cleanup, report ID thật + Bearer gateway, snake_case DTO và cache/icon policy. Ba case policy đã được chạy đỏ trước sửa; fixture bucket giả nằm trong object path vẫn được giữ. Bốn ca retention độc lập kiểm tra parent 404, parent già ngoài listing, parent trẻ và lỗi 403.

53 Node tests đã bao gồm HTTP gateway/adapter/CLI và 4 ca retention mới; **không cộng thêm 12 database assertions vào số 53**. Business audit có một phép thử grant thiếu cấu hình trả `MEDIA_GATEWAY_SECRET_NOT_CONFIGURED` đúng thiết kế; positive configured grant được kiểm tra riêng ở crypto-schema/migration tests.

Lệnh chạy lại từ workspace:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
$nodeTests = @(rg --files backend tools audit/firebase-egress-round2-2026-10-28 -g '*.test.mjs')
node --test @nodeTests
node audit/firebase-egress-round2-2026-10-28/db-check.mjs
node audit/firebase-egress-round2-2026-10-28/crypto-schema-check.mjs
node audit/firebase-egress-round2-2026-10-28/business-check.mjs
node supabase/tests/run-thumbnail-migration.mjs
node supabase/tests/run-media-gateway-test.mjs
```

Log chạy cuối: `audit/firebase-egress-round2-2026-10-28/final-android.log`, `final-node.log`, `final-thumbnail-migration.log`, `final-media-migration.log`, `final-diff-check.log`; số đếm máy đọc ở `verification-final.json`.

## Giới hạn thực tế và điều kiện production

- Các bài HTTP dùng server loopback thật với dependency giả lập; database dùng PGlite. Không có bằng chứng cloud đang chạy đúng từ các kiểm thử này.
- Cờ private disk cache, media gateway và CDN giữ `false`; URL gateway/CDN mặc định rỗng. App Check giữ `MONITOR_ONLY`.
- Cần kiểm tra staging/thiết bị thật, đăng ký App Check/Play Integrity, cấu hình secret/base URL/ADC/quyền cloud, kiểm tra backup/migration/rollback trước rollout. Hành vi debug provider không chứng minh release attestation.
- E00 có exporter cục bộ; việc vận chuyển/thu telemetry thật, độ phủ cohort và baseline Billing/Monitoring 7–14 ngày vẫn phải được vận hành xác nhận. Không suy tỷ lệ tiết kiệm thực từ test hoặc đầu vào TCO giả định.
- Chưa deploy cloud, apply migration production, revoke download token, chạy cleanup trên bucket thật, commit hoặc push trong lượt kiểm tra này.
- Không kết luận mọi lint warning vô hại, hoặc mọi lỗi có thể có đều đã được chứng minh vắng mặt; chỉ nghiệm thu phạm vi code và hồi quy đã kiểm tra.

## Bằng chứng lưu lại

- `REVIEW_FIREBASE_EGRESS_E00_E05_2026-10-28.md`: phát hiện ban đầu, không phải trạng thái cuối.
- `RECHECK_ROUND2_FIREBASE_EGRESS_2026-10-28.md`: các yêu cầu sửa/bằng chứng từng vòng.
- `audit/firebase-egress-2026-10-28/`: probe và kết quả trước sửa.
- `audit/firebase-egress-round2-2026-10-28/`: strict assertions, fixture, snapshot đỏ và kết quả chạy cuối.
- `FIX_FIREBASE_EGRESS_ROUND2_2026-10-28.md`: giải trình thay đổi của Antigravity; kết quả nghiệm thu do báo cáo độc lập này quyết định.
