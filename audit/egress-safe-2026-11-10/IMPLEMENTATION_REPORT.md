# Nghiệm thu tối ưu Supabase Egress — 10/11/2026

## Kết quả và phạm vi

Đã triển khai đồng bộ có điều kiện cho Home, Hồ sơ/dashboard, chi tiết chiến dịch và API gợi ý ứng dụng kế tiếp trong `F:/AI/12Tester`. Antigravity CLI chạy bằng **gemini-3.8-flash-high**, effort **high**; Codex điều phối, review, sửa các vấn đề phát hiện và đối chiếu kết quả độc lập.

Máy chủ vẫn thực hiện nghiệp vụ và kiểm tra quyền mỗi lượt tải. Khối chưa đổi chỉ trả token; khối đã đổi trả đầy đủ giá trị. Lượt đầu hoặc mất cơ sở so sánh tải đầy đủ dữ liệu.

**Chưa áp dụng migration, bật cờ hoặc phát hành APK lên hệ thống thật.** Kiểm chứng cục bộ không chứng minh mọi lỗi của ứng dụng đã được loại bỏ hoặc trải nghiệm production hoàn toàn tương đương.

## Hành vi được bảo toàn

- Giữ TTL Home 3 phút, dashboard 60 giây, chi tiết 3 phút; không kéo lên 10 phút.
- Giữ các điểm tải khi mở màn hình, resume, chuyển tab, kéo làm mới và sau mutation. Không thêm bước chờ hay thay giao diện.
- Giữ tải leaderboard trong dashboard, credit logs, thông báo, slot và các trường chi tiết hiện có.
- Giữ cửa sổ 200 báo cáo và các bộ đếm/pending/today theo nghiệp vụ cũ; không giảm còn 10–20 báo cáo.
- Giữ thuật toán chọn ứng dụng kế tiếp, tập ứng viên và thứ tự; không cắt danh sách còn 10 ứng dụng.
- Không sửa định nghĩa RPC/migration cũ; APK cũ tiếp tục gọi API hiện hành.
- Giữ tác dụng nghiệp vụ như ghi nhận đăng nhập và tự duyệt báo cáo đến hạn.

## Những vấn đề đã sửa

1. Kiểm tra JSON trước khi ghép, phân biệt null hợp lệ, danh sách rỗng và khối bị thiếu. Không biến dữ liệu lỗi thành danh sách rỗng hoặc số 0. Tải sửa đầy đủ tối đa một lần; nếu vẫn lỗi thì báo lỗi.
2. Lưu payload và token trong snapshot bất biến. Xóa bộ nhớ/đổi phiên loại bỏ cả cặp; không phục hồi token riêng từ đĩa.
3. Kiểm tra tài khoản, generation, ngày nghiệp vụ và phiên bản thay đổi trước khi công bố cache. Khóa gộp request có ngày nghiệp vụ. Response cũ không được ghi đè token phiên mới.
4. Mutation vẫn làm cache giao diện hết hiệu lực và buộc lần đọc kế tiếp hỏi máy chủ. Snapshot raw cùng tài khoản/phiên/ngày được giữ làm cơ sở so sánh, không được phục vụ như dữ liệu mới hoặc bị sửa theo trạng thái lạc quan của giao diện. Giới hạn số snapshot chi tiết giữ trong RAM.
5. API mới chỉ fallback khi đúng HTTP 404 + PGRST202 + đúng tên hàm trong nội dung lỗi gốc SDK. Không nhận diện từ URL ghép vào Exception.message; không che lỗi quyền/5xx/giải mã. Giữ riêng hành vi nhận diện lỗi API cũ.
6. Negative capability cache hết hạn sau 5 phút, cho phép thử lại; không khóa API mới vĩnh viễn hoặc retry vô hạn.
7. API ứng viên chuyên biệt trả Campaign và installed_count đúng tập lọc/thứ tự, có scope fence khi response về. Thiếu API mới dùng Home cũ.
8. Cache hit và single-flight join không tăng số lần gọi mạng. Probe thiếu API, repair và fallback được tính. Nhãn RPC là tên tĩnh, không chứa ID.
9. Truyền nguồn cold start/resume/tab/pull/mutation/prefetch/manual khi biết; còn lại UNKNOWN, không gán mọi force refresh thành pull.
10. Tách kích thước UTF-8 sau giải mã khỏi wire bytes; wire chưa đo để null/unknown. Không dùng JSON làm egress tính phí.
11. Thông báo protocol không echo nội dung giải mã hoặc giá trị header lỗi; thống kê mới không ghi JWT, nội dung report hoặc mật khẩu.
12. Dọn ViewModel/client trong fixture kiểm thử quản trị trước khi reset Main dispatcher; giữ nguyên assertion nghiệp vụ.

## Tệp chính

- `supabase/migrations/085_egress_optimization_sync_v2.sql`: RPC v2, fingerprint SHA-256, grants và bốn cờ mặc định false. Chạy lại không tự tắt cờ đã bật.
- `supabase/operations/egress_sync_v2_rollout.sql`: các ví dụ bật từng bước/rollback đều được comment; chạy nguyên tệp chỉ đọc trạng thái.
- `app/src/main/java/com/ntt/crosstest/data/EgressSyncProtocol.kt`: snapshot, kiểm tra/ghép phản hồi.
- `EgressOptimization.kt`, `CampaignRepository.kt`, `AppConfigRepository.kt`, `Models.kt`: cấu hình, tải, cache, thống kê và ứng viên.
- Home/Profile/CampaignDetail ViewModel và màn hình: truyền nguồn kích hoạt theo luồng hiện có.

Checkpoint trước nhiệm vụ chứa 771 tệp trong phạm vi sao lưu. Đối chiếu hash không thấy sửa migration/test SQL cũ. Trong tập cũ chỉ các tệp ứng dụng liên quan và fixture quản trị được sửa. Không reset/clean/checkout/commit/push; bảo toàn thay đổi có sẵn của người dùng.

## Kiểm chứng

Kết quả cuối: **80 suites, 611 tests, 0 failures, 0 errors, 0 skipped; assembleDebug BUILD SUCCESSFUL**. Xem `runs/FINAL_VERIFICATION.json`, `runs/acceptance2.ndjson` và `runs/final-android-verification.log`. Đối chiếu XML/kết quả Gradle, không suy từ trạng thái SUCCESS chung của CLI. Java 21 chỉ được chọn trong tiến trình chạy, không đổi Java toàn máy.

APK thử nghiệm: `F:/AI/12Tester/app/build/outputs/apk/debug/app-debug.apk`. Hash SHA-256 và thời điểm tạo được lưu trong FINAL_VERIFICATION.json.

```powershell
.\gradlew.bat testDebugUnitTest --offline
.\gradlew.bat assembleDebug --offline
```

Ba lớp Android bổ sung: EgressReviewContractTest, EgressProtocolReviewTest, EgressFinalClientReviewTest. Bao phủ repair, token/payload co-eviction, race generation, thiếu sai tên RPC, JSON sai kiểu/null, chẩn đoán không echo dữ liệu, 15 ứng viên, lỗi 403/500/JSON, negative TTL, mutation vẫn hỏi máy chủ nhưng giữ token so sánh, gộp request, cache hit và nguồn tải.

Sáu script dữ liệu cục bộ đã hoàn thành exit 0:

```text
run-egress-sync-v2-test.mjs
run-egress-flags-v2-test.mjs
run-carryover-fix-acceptance-test.mjs
run-admin-pause-resume-compatibility-test.mjs
run-owner-tester-reports-count-test.mjs
run-app-mission-window-test.mjs
```

Bằng chứng: `runs/final-run-*.log`. Kiểm tra độc lập ở `runs/verify-egress-edge-cases.mjs` và `runs/independent-db-review.log`: 205 báo cáo, pending ngoài cửa sổ 200, ba attachment, trường chi tiết đầy đủ, đổi profile từ xa, tài khoản khác/anon, 15 ứng viên đúng thứ tự.

Fixture detail lớn: JSON legacy 197.901 byte, unchanged v2 2.241 byte, giảm **98,87% trong fixture này**. Dashboard full 6.770 byte, unchanged 1.699 byte. Đây không phải mức giảm tổng egress production, không gồm nén/header và không suy ra mức giảm hóa đơn.

## Đưa vào sử dụng và quay lui

1. Áp dụng migration 085 trên môi trường thử; kiểm tra PostgREST thật, APK mới, quyền, ngày nghiệp vụ, cold/resume/tab/pull, gửi/duyệt báo cáo, đổi tài khoản và offline/phục hồi lỗi.
2. Đo baseline cùng cửa sổ thời gian/tập người dùng; đo byte truyền thực, lỗi, request và P95/CPU. Wrapper vẫn chạy truy vấn nghiệp vụ cũ và thêm băm JSON nên cần đo chi phí máy chủ.
3. Bật lần lượt Home, dashboard, detail; gợi ý ứng viên bật sau khi đo dữ liệu thực. Theo dõi vài ngày trước khi mở rộng phát hành APK.
4. Nếu hồi quy, đặt bốn cờ false để quay về API cũ khi thiết bị nhận cấu hình mới; không cần xóa dữ liệu/gỡ migration.

Cờ ảnh hưởng các APK tương thích đọc android_client, không chọn cohort người dùng. Cấu hình đi qua cơ chế load/restore và nạp lại startup/manual hiện có; không có đẩy rollback tức thì hoặc timer mới. Rollout APK theo nhóm thực hiện riêng.

## Giới hạn

- DB tests dùng PGlite cục bộ, chưa thay thế PostgreSQL/PostgREST và thiết bị thật.
- Token snapshot chỉ ở RAM; sau khi tiến trình đóng, lần đồng bộ tiếp theo tải đầy đủ. Cache giao diện đĩa không được coi là cơ sở token tin cậy.
- Thống kê request chỉ bao phủ các luồng tổng hợp/gợi ý được tích hợp, không phải mọi request ứng dụng hoặc retry bên dưới SDK. Decoded bytes là mẫu ở phản hồi v2/gợi ý xử lý thành công; lỗi/legacy chưa có kích thước đầy đủ. Không dùng tổng này tính tỷ lệ egress toàn hệ thống.
- Unchanged là RPC thành công với JSON envelope, không phải HTTP 304. Cold/full có overhead token/envelope; hiệu quả phụ thuộc dữ liệu và tần suất tải lại.
- Build còn một số cảnh báo công cụ/biên dịch không chặn; không mở rộng sang chức năng ngoài phạm vi.
- Chưa đo egress tính phí trong cửa sổ 3–7 ngày, chưa áp dụng migration/cờ từ xa, chưa cài hoặc phát hành APK.
