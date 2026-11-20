# Báo cáo sửa so sánh ảnh báo cáo test giữa các ngày — 30/10/2026

## 1. Mục tiêu và tổng quan giải pháp

Đã hoàn thành toàn bộ các yêu cầu trong kế hoạch `KE_HOACH_SUA_SO_SANH_ANH_BAO_CAO_CHO_GEMINI_2026-10-30.md` nhằm khắc phục dứt điểm vấn đề:
1. Hai ảnh khác nhau bị dHash 64-bit đánh giá 100% bit giống nhau và suy diễn sai lệch thành "ảnh trùng lặp 100%".
2. Bị chặn nhầm khi nộp báo cáo (hard-block) trên cả ứng dụng Android và backend (`SAME_SCREENSHOT_REJECTED`) giữa các tài khoản, chiến dịch hoặc khi nộp lại ảnh đã chỉnh sửa.
3. Chủ app suy diễn `100 - similarity` thành phần trăm tính năng mới đã kiểm thử.
4. Chọn mốc đối chiếu sai ngày (do lấy theo UTC hoặc take(10) thay vì múi giờ Việt Nam `Asia/Ho_Chi_Minh`), hoặc lẫn lộn ngữ cảnh test app với nhiệm vụ (mission).

---

## 2. Chi tiết các file mã nguồn & migration đã thực hiện

### 2.1 Backend Migration & Kiểm thử DB
- **`supabase/migrations/065_remove_dhash_hardblock.sql`**:
  - Không sửa đổi baseline 062 hay các migration cũ 063, 064.
  - Loại bỏ hoàn toàn điều kiện ném lỗi `SAME_SCREENSHOT_REJECTED` dựa trên `image_hash` ở cả hai nhánh:
    - Nhánh cập nhật (TH2): Bỏ quét `exists (...) where lower(image_hash) = lower(p_image_hash) and id <> v_existing_report.id`. Bổ sung kiểm tra bắt buộc thay URL ảnh mới khi nộp lại báo cáo bị từ chối (`REPORT_RESUBMIT_NEW_PHOTO_REQUIRED`).
    - Nhánh tạo mới (TH3): Bỏ quét toàn bảng `exists (...) where lower(image_hash) = lower(p_image_hash)`.
  - Giữ nguyên toàn bộ xác thực định dạng dHash (16 hex), quota, idempotency receipt ledger, kiểm tra mission, quyền media và chuyển giao Credit tạm tính.
  - Sửa đúng trường hiện có: Cập nhật `rejection_reason = null, reviewed_at = null, provisional_clawed = 0` khi nộp lại báo cáo bị từ chối; không thêm cột `rejected_at` không cần thiết.
- **`supabase/tests/run-remove-dhash-hardblock-test.mjs`**: Kiểm thử backend trên PGlite harness thật:
  - Hai người dùng khác nhau có cùng dHash -> nộp thành công (không còn `SAME_SCREENSHOT_REJECTED`).
  - Một người dùng nộp 2 chiến dịch khác nhau có cùng dHash -> nộp thành công.
  - Nộp lại báo cáo bị từ chối dùng đúng URL cũ -> bị từ chối với `REPORT_RESUBMIT_NEW_PHOTO_REQUIRED`.
  - Nộp lại báo cáo bị từ chối với URL mới (kể cả cùng dHash) -> nộp thành công.
  - Replay idempotency và kiểm tra chạy lại migration (idempotent).
- **`supabase/tests/run-explicit-report-context-test.mjs`**: Cập nhật nạp cả migration 065 để chạy kiểm thử ma trận ngữ cảnh/quyền trên DB thực sự đã áp dụng 065.
- **`supabase/tests/package.json`**: Thêm script `test:remove-dhash` và cập nhật lệnh `test`.

### 2.2 Thuật toán Perceptual Hash (`ImageHashHelper.kt`)
- Bổ sung hàm kiểm tra định dạng hex nghiêm ngặt `isValidDHash(hash: String?): Boolean` (đúng 16 ký tự hex).
- `hammingDistance` và `similarityPercentage`: Trả về `-1` khi một trong hai hash null, rỗng hoặc không hợp lệ; không coi hash thiếu/sai là khác 0%.
- Tách rạch ròi kết quả dHash:
  - Cập nhật enum `SimilarityLevel`: `UNKNOWN` (thiếu/sai hash), `SIMILAR_LAYOUT` (Hamming <= 9, bố cục gần giống, cần xem ảnh), `MODERATE` (10..15, bố cục tương đối khác), `DIFFERENT` (>= 16, khác bố cục). Giữ alias deprecated để tương thích.
  - `ComparisonResult`: Thêm `isExactDuplicate = false`. Thuộc tính `isBlockingDuplicate` luôn trả về `false` từ dHash (vì dHash không thể là bằng chứng trùng khít exact). `isSuspectDuplicate` chỉ đóng vai trò cảnh báo mềm về bố cục.
  - Nhãn hiển thị (`label`) mô tả bản chất bố cục, không hiển thị phần trăm như tỷ lệ nội dung/tính năng trùng nhau.

### 2.3 Helper Chọn Mốc Đối Chiếu (`ReportComparisonSelector.kt`)
- Tách thành helper độc lập `com.ntt.crosstest.util.ReportComparisonSelector`:
  - **Lọc chính xác**: Cùng `userId`, cùng `campaignId`, cùng `submissionContext`. Đối với mission, bắt buộc cùng `missionId`; không ghép lẫn giữa `app_test` và `mission`.
  - **Múi giờ**: Chuyển đổi timestamp ISO-8601 sang `Instant` và `LocalDate` theo `Asia/Ho_Chi_Minh`. Ngày đối chiếu bắt buộc phải trước ngày hiện tại (strictly before theo giờ Việt Nam). Cung cấp helper `formatIsoUtcToVnDate(isoTimestamp)` định dạng `dd/MM/yyyy` theo giờ VN, triệt tiêu việc cắt chuỗi thô `take(10)` (vốn lấy theo UTC).
  - **Độc lập thứ tự**: Sử dụng comparator ổn định: ưu tiên thời gian gần nhất (`Instant`), tie-break bằng `id` để kết quả nhất quán không phụ thuộc thứ tự danh sách đầu vào.
  - **Trường hợp nộp lại (Validate chặt chẽ)**: Nếu có `rejectedReportOverride`, bắt buộc thẩm định đầy đủ: cùng user, cùng campaign, cùng context/mission, đúng trạng thái `isRejected`, có `screenshotUrl` và cùng ngày VN với báo cáo hiện tại. Không nhận override tùy ý. Gắn nhãn riêng biệt `REJECTED_SAME_DAY`.
  - **Phân trang / Lịch sử thu gọn**: Bảo toàn cờ `isHistoryPartial = true` kể cả khi tìm thấy mốc đối chiếu trong tập dữ liệu tải về. Khi timestamp bị lỗi/không parse được, trả về `noteResId = null`, không tuyên bố sai là "báo cáo đầu tiên".

### 2.4 Thuật toán So Sánh Chi Tiết Màu & Vùng (`DetailedImageComparator.kt`)
- Triển khai thuật toán `region_rgb_v1` chạy trên background dispatcher (`Dispatchers.Default`), có kiểm tra `ensureActive()` để hỗ trợ hủy bỏ (cancellation) tức thời khi đóng dialog.
- **Loại bỏ nhãn Exact từ Bitmap Cache**:
  - Do toàn bộ bitmap trong cache/Coil có thể đã bị downsample/subsample (qua `inSampleSize`), không thể khẳng định là pixel nguyên bản 100%. Đã bỏ hoàn toàn các nhãn `EXACT_PIXELS` và `EXACT_FILE`; mức tương đồng cao nhất trả về là `SUSPECT_SIMILAR` ("Gần giống, cần kiểm tra").
- **Đo chênh lệch pixel thực sự trong từng ô**:
  - Không lấy hiệu của giá trị trung bình `abs(meanRGB1 - meanRGB2)` (dễ bị triệt tiêu khi hoán đổi màu đối xứng trong ô).
  - Chuyển sang tính `mean(abs(pixelRGB1 - pixelRGB2))` trên từng điểm ảnh của ô lưới. Hai ảnh hoán đổi vị trí đen/trắng trong ô được phát hiện khác biệt 100% thay vì 0%.
- **Giới hạn bộ nhớ & tài nguyên**:
  - Không kéo giãn hai ảnh có tỷ lệ khác nhau. Nếu độ chênh tỉ lệ khung hình > 5%, trả về ngay `DIFFERENT_ASPECT`.
  - Chuẩn hoá kích thước làm việc về tối đa 240px giữ nguyên tỷ lệ khung hình.
  - Tuyệt đối không recycle bitmap nguồn dùng chung từ `ScreenshotManager`/Coil; chỉ recycle bitmap tạm thời được tạo trong quá trình so sánh.
- **Xử lý màu và alpha**:
  - Hòa trộn kênh alpha với nền trắng đục để loại trừ sai lệch do trong suốt.
- **Phân cấp mức bằng chứng**:
  - `SUSPECT_SIMILAR`: Bố cục và màu sắc gần giống, gợi ý chủ app kiểm tra chi tiết.
  - `DIFFERENT` / `DIFFERENT_ASPECT`: Phát hiện khác biệt rõ rệt.
  - `UNAVAILABLE`: Không đủ dữ liệu / ảnh lỗi / hết hạn.

### 2.5 Điều Phối Trạng Thái An Toàn (`ComparisonStateCoordinator.kt`)
- Thêm `ComparisonStateCoordinator` để loại trừ hoàn toàn race condition:
  - Tạo `operationId` đơn điệu tăng dần và ghi nhận `sessionGeneration` tại thời điểm bắt đầu.
  - Kiểm tra `isCurrent(execution)` trước khi chạy, sau khi fetch từng bitmap, và ngay trước khi publish.
  - Kiểm tra hết hạn động (`ScreenshotManager.isExpired`) và 404 tombstone sau khi fetch mạng để không publish kết quả của ảnh đã hết hạn.
  - Khối `finally` chỉ cập nhật `isCalculatingDetail = false` khi tác vụ đang là tác vụ hiện hành (`isCurrent`), ngăn tác vụ cũ bị huỷ đè trạng thái loading của tác vụ mới.

### 2.6 Cập nhật Giao Diện (UI)
- **`OwnerAppDetailScreen.kt`**:
  - Thay thế logic tìm `previousReport` cũ bằng `ReportComparisonSelector.selectComparisonTarget(...)`.
  - Loại bỏ hoàn toàn nhãn "100 - similarity = % tính năng mới" và ký hiệu báo động đỏ sai.
  - Cập nhật thẻ báo cáo hiển thị trạng thái bố cục tương đồng (`oad_layout_similar`) kèm cảnh báo vàng mềm, hoặc khác bố cục (`oad_layout_different`).
  - Sử dụng ngày định dạng theo giờ Việt Nam (`ReportComparisonSelector.formatIsoUtcToVnDate`), loại bỏ hoàn toàn `take(10)`.
  - Nút "So sánh" mở dialog đối chiếu chi tiết.
  - Reset trạng thái `comparison` khi URL/report thay đổi để chống stale state.
- **`CampaignDetailScreen.kt`**:
  - Sử dụng `ReportComparisonSelector` để chọn mốc ảnh đối chiếu.
  - Cả hai chế độ preview card và dialog so sánh của tester đều hiển thị đúng nhãn `report_selector_rejected_same_day` khi là báo cáo nộp lại sau khi bị từ chối.
  - Loại bỏ biến `rejectedImageDuplicate` khỏi điều kiện vô hiệu hóa nút nộp `canSubmit`. dHash không còn khóa nút nộp của tester.
  - Giữ nguyên yêu cầu bắt buộc chụp ảnh mới khi nộp lại báo cáo bị từ chối (`missingNewScreenshot`).
  - Hiển thị cảnh báo mềm để tester nắm được thông tin.
- **`ImageViewerComponents.kt`**:
  - Khi mở dialog so sánh (cả `ImageComparisonDialog` của owner và `TesterImageComparisonDialog` của tester), đều kích hoạt so sánh chi tiết màu & vùng qua `ComparisonStateCoordinator`.
  - Hiển thị banner đánh giá rõ ràng: "Đang so sánh chi tiết màu & vùng...", "Phát hiện khác biệt", "Gần giống, cần kiểm tra", "Khác tỷ lệ màn hình".
  - Hiển thị ngày giờ theo múi giờ địa phương (`formatIsoUtcToLocal`) thay cho `take(16).replace("T", " ")`.
  - Giữ nguyên các cơ chế bảo vệ phiên (session generation guard, kiểm tra hết hạn 30 ngày, auth token).
- **Strings đa ngôn ngữ (`values/strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml`)**:
  - Bổ sung và cập nhật đồng bộ các chuỗi nhãn cho `similarity_*`, `detailed_verdict_*`, `report_selector_*`, `oad_layout_*`.
  - Không hardcode chuỗi tiếng Việt trong code Kotlin.

---

## 3. Lệnh và Kết quả Kiểm thử

### 3.1 Backend Tests (PGlite Database Harness)
| Lệnh kiểm thử | Kết quả | Ghi chú |
|---|---|---|
| `npm run test:remove-dhash` | **PASS (100%)** | Chạy qua 7 kịch bản: cùng dHash khác user, khác campaign, nộp lại ảnh từ chối cũ bị chặn URL, ảnh từ chối mới pass, idempotency, rerun migration 065. |
| `npm run test:report-context` | **PASS (8/8 ma trận)** | Nạp thực sự migration 065 trên nền 062-064; bảo toàn các ràng buộc ngữ cảnh `app_test` vs `mission`, credits, ACL, tombstone. |
| `npm run test:report-schema` | **PASS (4/4)** | Bảo toàn tương thích schema pgcrypto trong public và extensions. |
| `node run-report-idempotency-ledger-test.mjs` | **PASS (All)** | Bảo toàn toàn vẹn receipt idempotency và provenance. |

### 3.2 Android Unit Tests (Robolectric & Native Graphics)
| Lệnh kiểm thử | Kết quả | Ghi chú |
|---|---|---|
| `.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.audit.ImageHashCollisionAuditTest" --offline` | **BUILD SUCCESSFUL (4 tests PASS)** | Tái hiện va chạm dHash nhưng xác nhận `isBlockingDuplicate=false`, `isExactDuplicate=false`, UNKNOWN khi hash lỗi. |
| `.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.util.ReportComparisonSelectorTest" --offline` | **BUILD SUCCESSFUL (10 tests PASS)** | Kiểm tra chọn ngày VN qua ranh giới UTC, ngữ cảnh mission vs app_test, thẩm định chặt chẽ `rejectedReportOverride` (user/campaign/context/isRejected/cùng ngày VN), bảo toàn `isHistoryPartial`, không báo first report khi timestamp lỗi, định dạng ngày VN `dd/MM/yyyy`. |
| `.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.util.DetailedImageComparatorTest" --offline` | **BUILD SUCCESSFUL (6 tests PASS)** | Trắng vs Xanh -> DIFFERENT (khắc phục dHash 100%), ảnh giống hệt từ cache trả về SUSPECT_SIMILAR (không exact), cùng URL trả về SUSPECT_SIMILAR (không exact), Khác Aspect Ratio, Peak difference phát hiện thay đổi cục bộ, Hoán đổi màu trong ô lưới phát hiện khác biệt qua `mean(abs(p1 - p2))`. |
| `.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.util.ComparisonStateCoordinatorTest" --offline` | **BUILD SUCCESSFUL (5 tests PASS)** | Kiểm tra superseded operation bị hủy không publish, thay đổi session generation (logout/login) vô hiệu hoá operation, ảnh hết hạn trả null ngay, 404 tombstone trả null, execution hợp lệ trả kết quả chi tiết. |
| `.\gradlew.bat testDebugUnitTest --tests "com.ntt.crosstest.data.ReportRpc*" --tests "com.ntt.crosstest.data.ReportSubmitManagerIntegrationTest" --offline` | **BUILD SUCCESSFUL** | Tương thích RPC nộp báo cáo, worker nộp ngầm và retry idempotency. |

### 3.3 Android Compilation & Build
| Lệnh | Kết quả |
|---|---|
| `.\gradlew.bat assembleDebug --offline` | **BUILD SUCCESSFUL in 13s** (0 lỗi biên dịch, không lỗi cú pháp hay tài nguyên) |

---

## 4. Phần chưa kiểm tra (Phạm vi giới hạn tại local)

1. Chưa kiểm tra trực tiếp trên thiết bị Android vật lý với camera thực tế và độ phân giải màn hình đặc thù.
2. Chưa deploy trực tiếp lên Supabase Production và Firebase Storage thật.
3. Không thực hiện so sánh trên cặp ảnh người dùng thực tế từ cơ sở dữ liệu production (chỉ kiểm thử bằng bộ fixture chuẩn theo quy định).

---

## 5. Giới hạn chấp nhận trong bản sửa này

- **Upload lại cùng một ảnh thành URL mới**: Trong bản sửa này, nếu người dùng upload lại chính file ảnh cũ lên Storage tạo ra một URL mới, backend sẽ không chặn cứng (do đã bỏ hard-block dHash và server hiện tại chưa có cơ chế độc lập giải mã/băm digest object tải lên). Tuy nhiên, khi chủ app mở xem xét báo cáo, thuật toán `DetailedImageComparator` sẽ phân tích và gắn nhãn "Gần giống, cần kiểm tra" (`SUSPECT_SIMILAR`) để chủ app chủ động từ chối nếu có dấu hiệu gian lận.
- Việc chặn tuyệt đối việc tái sử dụng file ảnh trên toàn hệ thống cần giải pháp server-side object digest (được định vị là hạng mục tiếp theo).

---

## 6. Thứ tự Triển khai (Deployment) và Kế hoạch Quay lui (Rollback)

### 6.1 Thứ tự Triển khai
1. **Bước 1 — Backend Database Migration**:
   - Chạy migration `065_remove_dhash_hardblock.sql` trên cơ sở dữ liệu Supabase.
   - *Lưu ý tương thích*:
     - Nếu đã deploy Backend mới nhưng người dùng vẫn dùng App cũ: App cũ vẫn có thể tự hiển thị nhãn cũ (ví dụ 100% hoặc cảnh báo dHash cũ), nhưng backend sẽ **không còn từ chối nhầm** `SAME_SCREENSHOT_REJECTED`.
     - Nếu cập nhật App mới trước khi chạy Migration backend: App mới sẽ hiển thị đúng nhãn và không chặn nút nộp, nhưng nếu dHash trùng, backend cũ vẫn có thể trả về lỗi `SAME_SCREENSHOT_REJECTED`. Do đó, **bắt buộc deploy Migration backend trước hoặc đồng thời với App**.
2. **Bước 2 — Ứng dụng Android**:
   - Phát hành bản cập nhật Android có chứa các helper mới và giao diện đã tinh chỉnh.

### 6.2 Kế hoạch Quay lui (Rollback)
- **Nếu rollback code Android**: Quay lại commit trước đó. Cần lưu ý rằng việc rollback app sẽ đưa các nhãn "trùng lặp 100%" và hard-block dHash trên client quay trở lại.
- **Nếu rollback backend**: Khôi phục lại định nghĩa `submit_tester_report_internal_v2` trong migration 064. Lưu ý việc rollback backend sẽ kích hoạt lại lỗi false-positive `SAME_SCREENSHOT_REJECTED` khi dHash va chạm giữa các tài khoản.

## 7. Nghiệm thu cuối bởi Codex sau phiên OpenCode

- Model đã dùng qua CLI: `google/antigravity-gemini-3.8-flash` (Antigravity), session `ses_e743ed727ffeLYgHJ0qAvWmm9R`. Đã giao triển khai và một vòng sửa theo review, cả hai phiên đã kết thúc thành công.
- Codex bổ sung giải mã ảnh URI theo hai bước đọc kích thước / inSampleSize, giới hạn cạnh dài 1024 để tránh cấp phát nguyên ảnh thư viện lớn. Màn tester ưu tiên URI file prepared đã nén và chuẩn hóa hướng ảnh. Có regression test ảnh 3072x1536 được decode có giới hạn, giữ tỷ lệ.
- Chạy nghiệm thu Android gồm collision, selector, detailed comparator, coordinator, ReportRpc*, submit integration, background submission và upload worker: **61 tests, 0 failures, 0 errors**. `:app:assembleDebug` thành công. Kết quả chi tiết: `audit/opencode-image-comparison-2026-10-30/final-android-tests.json`; log: `final-android.log` cùng thư mục.
- Codex chạy lại `npm run test:remove-dhash` và `npm run test:report-context`: thành công. Bộ context thực sự apply migration 065 và kiểm tra 8 tổ hợp schema pgcrypto / role / context.
- APK debug: `app/build/outputs/apk/debug/app-debug.apk`. Đây là bản debug để kiểm tra, chưa phải bản release đã phát hành.
- Còn giới hạn: so chi tiết ở độ phân giải làm việc 240px có thể bỏ sót thay đổi rất nhỏ, vì vậy chỉ mô tả gần giống/cần xem ảnh, không kết luận trùng tuyệt đối. Chưa kiểm chứng bằng cặp ảnh thực tế của người nộp hoặc thiết bị vật lý.
- Chưa deploy migration, chưa commit/push và chưa thay dữ liệu production. Khi phát hành cần apply migration 065 trước hoặc cùng đợt cập nhật ứng dụng.
