# Kết quả sửa đường nộp báo cáo — 27/10/2026

## Phạm vi

Sửa các phát hiện F01–F04 tại baseline `d2da711`, các lỗi lint Error đã ghi trong RECHECK và bổ sung kiểm thử hồi quy. Giữ các chỉnh sửa tài liệu có sẵn trong working tree. Không thay Storage rules, migrations, tỷ lệ test, cấu hình backend hay phiên bản thư viện runtime.

## Thay đổi

1. **Trạng thái WorkManager:** mapping phân biệt ENQUEUED/BLOCKED, RUNNING và terminal. RUNNING lần đầu là SENDING/1; lần hai RETRYING/2; DONE/FAILED không cộng sang lần tiếp theo. Manager chờ enqueue commit và quan sát WorkInfo thực được KEEP giữ lại. Một request không phát success overlay hai lần trong cùng process.
2. **Retry URL:** chỉ thử lại lỗi tạm thời có bằng chứng; lỗi quyền/cấu hình dừng ngay. Tối đa 3 calls, delay 500/1.000 ms, timeout chờ mỗi call 8 giây và tổng lượt 20 giây; không delay happy path. Đây là ngân sách chờ coroutine, không phải cam kết latency trên thiết bị hay bảo đảm hủy được GET task nội bộ của SDK.
3. **Checksum/cancellation:** mismatch của upload được retry hữu hạn, không dùng retry URL để sửa upload. Lưu SHA-256 của file pending mới và kiểm tra trước upload để phát hiện bytes bị thay đổi. Giữ ID, dHash và Storage path; hủy UploadTask khi coroutine upload bị hủy. Cancellation ở URL/backoff/join/Worker không chuyển thành success hay retry mạng.
4. **Kết quả bền vững:** pending có `completedResult` mặc định null để đọc được JSON cũ. RPC trả thành công thì lưu receipt xuống đĩa trước Result.success; Worker chạy lại đọc receipt và trả cùng kết quả, kể cả chưa khởi tạo Supabase, không upload/RPC lần nữa. Ảnh local có thể dọn sau khi receipt đã lưu; JSON receipt chỉ dọn khi WorkManager xác nhận SUCCEEDED hoặc bản ghi work đã bị prune, ngoại trừ khi người dùng đăng xuất/xóa dữ liệu ứng dụng.
5. **Ghi file:** ghi tệp tạm, `FileDescriptor.sync()` rồi `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` trong cùng thư mục, API tối thiểu 26. Không fallback sang ghi đè không nguyên tử; lỗi commit được ném ra. Hỗ trợ khôi phục `.bak` cũ. Receipt chưa được WorkManager xác nhận không bị TTL/quota cleanup xóa và không chặn gợi ý app kế tiếp.
6. **Đổi tài khoản:** kiểm tra owner trước upload/RPC; khi sign out, manager hủy và chờ các coroutine persist/observe kết thúc trước khi dọn trạng thái. Không tiếp tục submit request của tài khoản cũ bằng phiên tài khoản mới.
7. **Chống nộp trùng:** Worker yêu cầu RPC có `p_client_submit_id`; nếu server thiếu signature này thì báo lỗi dịch vụ, giữ pending, không tự fallback RPC legacy không có idempotency key. Backend vẫn cần được xác minh độc lập trước khi kết luận không thể cộng Credit trùng trên môi trường thực.
8. **Lint/UI:** các callback đọc chuỗi qua LocalResources; thông báo so ảnh lấy từ comparison result theo ngôn ngữ hiện tại. Thay BoxWithConstraints không dùng constraints bằng Box, giữ layout/overlay. Không suppression để che Error.
9. **Lỗi recovery:** object cũ làm upload bị từ chối update nhưng đọc URL bị 503 thì giữ lỗi đọc URL làm nguyên nhân để còn retry; lỗi đọc URL vĩnh viễn dừng ngay. Nếu object không tồn tại thì giữ lỗi upload gốc, chẳng hạn checksum mismatch có thể retry. Hai lỗi được giữ bằng suppressed exception, không mất ngữ cảnh.

## Kiểm thử

Bổ sung test dependencies cùng phiên bản runtime đang pin: WorkManager testing 2.11.2, coroutine test 1.11.0, Ktor MockEngine 3.6.0; Robolectric 4.17 chỉ dùng cho test. Không thêm các thư viện test vào ứng dụng phát hành.

Các nhóm test:

- Mapping lần chạy đầu, chạy lại, chờ và trạng thái cuối.
- WorkManager thực với database/scheduler test và observer production: giữ Worker ở RUNNING để bắt retry giả, sau đó xác nhận DONE/1 và cleanup.
- Policy bằng StorageException thật, đúng errorCode; HTTP 400/401/403/404/422/408/429/500/503 qua Ktor MockEngine.
- Coroutine virtual time: lỗi quyền, unknown, transient, giới hạn attempts, timeout từng request/tổng lượt, hủy trong delay và timeout từ bên ngoài.
- Store thật trên filesystem test: JSON cũ, fingerprint, receipt re-read, cleanup, staged write gián đoạn và khôi phục backup.
- Worker production với fake ở ranh giới upload/RPC: replay sau receipt/offline, mất RPC response, checksum retry giữ file/ID, file thay đổi, cancellation, đổi tài khoản và hết số lần thử.

Lệnh: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon`, cache Gradle/SDK của máy được truyền bằng biến môi trường process.

| Kiểm tra | Kết quả |
|---|---|
| Unit/Robolectric tests | **41/41 PASS**, 0 failures, 0 errors |
| ReportWorkStateTest | 4 PASS |
| ReportSubmitManagerIntegrationTest | 2 PASS — WorkManager thực và cleanup/reconciliation |
| ReportSubmitPolicyTest | 10 PASS, có HTTP status qua MockEngine |
| ReportUrlRetryTest | 12 PASS, có các nhánh recovery/cancellation/deadline |
| PendingReportStoreTest | 6 PASS trên filesystem test |
| ReportUploadWorkerTest | 7 PASS trên Worker production với fake upload/RPC |
| assembleDebug | **PASS** |
| lintDebug | **0 errors, 420 warnings, 1 hint** — đọc XML/TXT, không chỉ dựa vào exit code |

Lệnh cuối **BUILD SUCCESSFUL**, 57 tasks (17 executed, 40 up-to-date). `git diff --check` đạt. Các cảnh báo còn lại chưa được coi là đã xử lý; đợt này đóng nhóm Error đã phát hiện, không thực hiện một đợt refactor toàn ứng dụng để xóa mọi Warning.

Evidence: XML trong `app/build/test-results/testDebugUnitTest`, HTML test report tại `app/build/reports/tests/testDebugUnitTest/index.html`, lint XML/TXT/HTML trong `app/build/reports`, APK tại `app/build/outputs/apk/debug/app-debug.apk`.

Trong quá trình sửa, 3 test persistence đầu tiên không đạt đã buộc kiểm tra lại cập nhật file và chuyển sang commit nguyên tử có báo lỗi rõ ràng. Bộ test cuối kiểm chứng receipt và URL thực sự được đọc lại từ đĩa; không làm xanh bằng cách bỏ assertion hoặc tăng số retry.

## Gate còn cần môi trường thực

- Chưa có thiết bị Android kết nối; chưa cài/chạy debug APK, kill process trên Android hoặc kiểm tra UI đổi ngôn ngữ trực tiếp.
- Chưa kiểm tra Firebase/Supabase staging hoặc các rules/RPC đã deploy; fake backend kiểm tra hành vi client, không chứng minh database production idempotent.
- Chưa đo 20 lượt nộp và p50/p95 trên mạng thật; prefetch giữ nguyên vì chưa có bằng chứng cần thay đổi.
- Chưa build/ký/phát hành release và chưa deploy backend. Không commit hoặc push trong lần sửa này.

## Tài liệu tham chiếu

- `RECHECK_MANG_YEU_BAO_CAO_2026-10-27.md`: bằng chứng trước sửa và các gói S00–S05.
- `KE_HOACH_SUA_LOI_MANG_YEU_BAO_CAO.md`: lịch sử kế hoạch và trạng thái triển khai.
