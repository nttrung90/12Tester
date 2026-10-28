# Báo cáo tối ưu Firebase Storage egress — 12 Tester

Ngày: **28/10/2026**, giờ Việt Nam. Gemini triển khai trong Antigravity; Codex điều khiển, review source và đối chiếu kết quả kiểm thử. Tài liệu đã được hiệu chỉnh sau review để phân biệt số đo fixture, cấu hình trong code và phần chưa xác minh trên production.

Kế hoạch: [KE_HOACH_TOI_UU_FIREBASE_EGRESS_2026-10-27.md](F:/AI/12Tester/KE_HOACH_TOI_UU_FIREBASE_EGRESS_2026-10-27.md).

## 1. Phạm vi hoàn thành

Đã triển khai **E00 cục bộ** và các thay đổi **E01a–E01d** trong source Android:

- Instrumentation opt-in cho số HTTP request, byte đọc từ response body, status và nguồn phục vụ ảnh. Mặc định tắt; định danh ảnh được băm, không ghi URL/token thô vào log chẩn đoán mới.
- RAM LRU 24 MiB cho bitmap screenshot; gom request đồng thời cùng URL trong một generation thành một flight. Hủy HTTP call khi không còn waiter hoặc khi clear phiên.
- Request factory gắn metadata report cho preview, fullscreen và hai dialog so sánh; helper hash nhận report tương ứng và ưu tiên `image_hash` có sẵn.
- Kiểm tra expiry trước tải và trước công bố kết quả; gắn cache/hash với generation để hạn chế kết quả cũ sống lại sau logout.
- Phân biệt 403 với 404. Ghi nhận 404 trong LRU giới hạn theo phiên để hạn chế tải lặp.
- Đọc stream có giới hạn 2.500.000 byte và kiểm tra kích thước ảnh trước decode. RAM LRU có budget riêng; **24 MiB không phải trần toàn bộ heap**, vì còn bitmap đang hiển thị, dữ liệu tải và tác vụ đang chạy.
- Dialog kiểm tra lại expiry định kỳ 5 giây. Đây là logic đã tích hợp trong source, **chưa phải kết quả kiểm thử Compose hoặc thiết bị thật**.

Giữ `REPORT_CACHE_CONTROL = "private, no-store"`. Chưa triển khai thumbnail production, persistent cache screenshot, migration bucket, CDN, IAM/App Check hoặc thay đổi backend trả thưởng/idempotency. Chưa commit/push/deploy.

## 2. File thay đổi

| File | Nội dung |
|---|---|
| `util/ImageInstrumentation.kt` — mới | Request/body bytes/status; opt-in và định danh được băm. |
| `util/ScreenshotManager.kt` — mới | RAM cache, in-flight tasks, giới hạn đọc/decode, expiry, 403/404 và clear generation. `ScreenshotDescriptor` được định nghĩa tại đây. |
| `util/ScreenshotRequestFactory.kt` — mới | Tạo request gắn descriptor trực tiếp từ report; giữ model local không phải String. |
| `util/ScreenshotCoilInterceptor.kt` — mới | Nối screenshot vào ImageLoader dùng chung; kiểm tra phiên và truyền cancellation. |
| `util/ImageHashHelper.kt` | Tái sử dụng bitmap/flight; ưu tiên hash DB; key hash có generation và kiểm tra trước publication. |
| `ui/components/ImageViewerComponents.kt` | Factory cho fullscreen/compare; metadata và expiry động. |
| `ui/screens/CampaignDetailScreen.kt` | Factory cho preview; truyền report vào hash và tester compare; giữ report khi mở fullscreen. |
| `ui/screens/OwnerAppDetailScreen.kt` | Factory cho preview; truyền report qua callback fullscreen và hash. |
| `TestCheoApp.kt` | Đăng ký interceptor/instrumentation; clear manager khi logout. |
| `FirebaseEgressOptimizationTest.kt` — mới | 16 regression cho pipeline Coil/HTTP fixture và logic manager/helper. |

File Android nằm dưới `app/src/main/java/com/ntt/crosstest/`; test dưới `app/src/test/java/com/ntt/crosstest/util/`. `local.properties` cục bộ được Gemini tạo để cấu hình Android SDK, thuộc gitignore; không đưa vào diff bàn giao.

## 3. Bằng chứng GET/byte trên fixture

Test tạo **JPEG 540×960, quality 80** từ bitmap màu đơn cho screenshot, không phải ảnh production. WebP được dùng cho fixture icon riêng. Response screenshot có `Cache-Control: private, no-store`.

Gọi `F` là `sampleFullJpegBytes1.size`, dung lượng JPEG được sinh tại runtime. Assertion so byte đo được với `F`; log/XML hiện không ghi riêng giá trị tuyệt đối này. Vì vậy báo cáo không gán một số byte cụ thể cho `F`.

| Kịch bản | Kết quả có bằng chứng | Giới hạn diễn giải |
|---|---|---|
| Preview 180×126 → fullscreen 1080×1920 | Coil cũ: **2 GET, 2F body bytes**. Pipeline mới: **1 GET, F**. Giảm **50%** trong workload này. | Chạy cả hai pipeline trên cùng fixture. Chưa suy ra tỷ lệ toàn phiên hoặc toàn hóa đơn. |
| Preview và dHash thiếu hash DB đồng thời | Pipeline cũ: **2 GET, 2F**. Pipeline mới: **1 GET, F**. | Workload được test, chưa đo tỷ lệ xuất hiện trên thiết bị/production. |
| Hai ảnh qua ba lượt request tương ứng các chế độ so sánh | Pipeline mới: **2 GET ban đầu**; bốn request tiếp theo không có GET/body bytes mới; test ghi nhận 4 memory hits. | Thực thi request qua ImageLoader, không thao tác UI chuyển tab. Chưa đo baseline cũ riêng cho kịch bản này nên không gán tỷ lệ giảm before/after. |
| Yêu cầu cùng URL 404 năm lần | Pipeline mới có **1 GET**, những lần sau bị chặn bởi tombstone. | Chưa đối chiếu năm lần trên pipeline cũ; không ghi “giảm 80%” như số đo before/after. |
| Stream unknown-length 3.000.000 byte | Test xác nhận không đọc hết stream; byte fixture phát ra ≤ `2.500.000 + 32.768`, đồng thời < 2.600.000. | Bound được assert, không phải giá trị emitted riêng đã log; có buffering. Chưa đo baseline cũ cho trường hợp này. |
| Screenshot `no-store` | Disk cache của ImageLoader mới có size 0 sau workload. | Chỉ xác nhận disk cache trong test, không phải audit toàn filesystem thiết bị. |

**Response-body bytes không đồng nghĩa wire bytes hay billable egress.** Fixture trả response trong interceptor cục bộ, không truyền qua Firebase thật. Counter cache/cancellation trên ImageLoader runtime còn cần đối chiếu khi đo thiết bị; kết quả body/GET trên không chứng minh độ chính xác của mọi counter production.

Regression còn kiểm tra: descriptor hết hạn chặn request, creator bị hủy khi follower vẫn chờ, clear mid-flight, expiry thay đổi trong lúc tải, hash không publish sau clear, 403 không bị đánh dấu như 404 và truyền cancellation. Các điều kiện race cụ thể đã test không thay thế việc kiểm tra mọi cách đổi tài khoản ngoài luồng hiện có.

## 4. Kết quả xác minh

Đối chiếu artifact của những lượt chạy hoàn tất sau khi tích hợp source:

| Gate | Kết quả |
|---|---|
| `:app:testDebugUnitTest` | **79 tests, 0 failures, 0 errors**, 9 suites; XML cập nhật 07:35:06 ngày 28/10/2026. |
| `FirebaseEgressOptimizationTest` | **16/16 đạt**, thuộc tổng 79 test. |
| `:app:assembleDebug` | Hoàn tất; APK debug cập nhật 07:31:39. |
| `:app:lintDebug` | Hoàn tất; XML cập nhật 07:33:56: **0 Error/Fatal, 422 Warning, 1 Hint**. |
| `git diff --check` | Không ghi nhận lỗi whitespace trong diff tracked tại thời điểm review. |

Lint vẫn có warning; không gọi kết quả là “không có warning”. File mới `ScreenshotCoilInterceptor.kt` có hai gợi ý `UseKtx` liên quan tạo `BitmapDrawable`. Chưa có baseline lint cùng revision lưu riêng để phân loại toàn bộ cảnh báo còn lại thành cũ/mới.

Bằng chứng cục bộ:

- [Kết quả test](F:/AI/12Tester/app/build/test-results/testDebugUnitTest/TEST-com.ntt.crosstest.util.FirebaseEgressOptimizationTest.xml).
- [Lint XML](F:/AI/12Tester/app/build/reports/lint-results-debug.xml).
- [APK debug](F:/AI/12Tester/app/build/outputs/apk/debug/app-debug.apk).

## 5. Gate còn mở

1. **Thiết bị/UI:** Kiểm tra thao tác thật preview/fullscreen/compare, dialog qua mốc hết hạn, local Uri, đổi tài khoản, offline/reconnect, memory pressure và chất lượng ảnh. Robolectric kiểm tra pipeline/logic, chưa chứng minh hành vi Compose hay hiệu năng thiết bị.
2. **E00 production:** Xác nhận region/billing SKU, lấy baseline 7–14 ngày và đo sau phát hành cùng workload/cohort. Metric bucket `storage.googleapis.com/network/sent_bytes_count` không được mặc định là billable bytes hoặc tự có nhãn client version; phân nhóm phiên bản cần telemetry app và đối chiếu label thực tế.
3. **Billing:** Chưa có hóa đơn/export để tính tiền tiết kiệm. Tính cả operations, storage, processing và traffic ngoài app; không suy từ giảm 50% body bytes fixture thành giảm 50% hóa đơn.
4. **Quyền và App Check:** Đánh giá riêng từng SDK/endpoint và URL download token được chia sẻ. Chưa xác minh App Check chặn đường token URL; không tuyên bố chặn toàn bộ tải ngoài ứng dụng, không tự enforce hoặc sửa rules trong đợt này.
5. **Retention:** Xác nhận worker/lifecycle thực tế, đồng bộ tuổi object 29–30 ngày với metadata report và chính sách công bố. Cache client không thay cho xóa dữ liệu trên cloud.
6. **E02–E05:** Thumbnail xem xét sau số liệu tỷ lệ preview→full; persistent cache/CDN/migration tiếp tục theo các gate của kế hoạch.

Bàn giao source và bản debug đã qua gate cục bộ nêu trên cho bước kiểm thử nội bộ trên thiết bị. Production rollout và xác nhận tiết kiệm hóa đơn chưa thực hiện.
