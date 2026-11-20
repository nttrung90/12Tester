# Kiểm tra và sửa gửi báo cáo test khi chuyển app — 29/10/2026

Đã sửa mã nguồn Android và build APK debug. Chưa phát hành; chưa đo độ trễ hoặc xác minh trên điện thoại gặp lỗi. Vì chưa có log từ máy bị ảnh hưởng, các phát hiện dưới đây là vấn đề trong luồng mã nguồn, chưa chứng minh bao quát toàn bộ nguyên nhân production.

## Phát hiện và thay đổi

1. **Tác vụ nộp do người dùng yêu cầu vẫn xếp lịch như công việc nền thông thường.** `ReportSubmitManager` chuyển sang expedited WorkManager. Khi hết quota, công việc được giữ để chạy thường bằng `RUN_AS_NON_EXPEDITED_WORK_REQUEST`. `ReportUploadWorker.getForegroundInfo()` cung cấp thông báo tiến trình riêng cho từng work để tương thích Android trước 12. Thông báo có bản tiếng Việt/Anh, kênh ít gây chú ý và chạm để mở lại ứng dụng. Không gọi cưỡng bức foreground service trên Android mới.

   Cách chọn tác vụ này theo [hướng dẫn expedited work chính thức của Android](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#expedited): dành cho công việc ngắn, do người dùng khởi tạo và cần tiếp tục khi đóng giao diện. Expedited vẫn chịu lịch và quota của hệ điều hành, không phải bảo đảm thời gian hoàn tất.

2. **Khi process khởi động lại, manager chỉ dọn biên nhận thành công, không khôi phục việc theo dõi các bài còn chờ.** Sau khi session đã authenticated, ứng dụng đọc lại bài của đúng tài khoản, theo dõi work hiện có, hoặc enqueue lại bằng ID cũ nếu process chết giữa lúc lưu request và enqueue. Work thất bại vẫn hiện lỗi để thử lại, không tự đặt lại ngân sách retry mỗi lần đăng nhập. Tác vụ khôi phục thuộc scope manager và được hủy cùng tác vụ con khi logout.

3. **Trạng thái và chống nộp trùng chỉ nhớ app được gửi gần nhất.** Manager giữ trạng thái riêng theo campaign và khóa các campaign đang gửi. Khi gửi A, tiếp tục gửi B rồi quay lại A, màn hình A vẫn đọc được tiến trình/kết quả/lỗi của A. Nút thử lại gửi đúng request của màn hình đang mở. Thành công của bài trước vẫn phát sự kiện hoàn tất.

4. **Có lượt gọi tham gia app trước RPC nộp báo cáo dù backend đã thực hiện thao tác đó trong giao dịch.** Bỏ HTTP insert thành viên khỏi worker. Các phiên bản RPC idempotent trong migrations 033, 037, 045, 048 và 049 đều tự thêm thành viên; `submitFeedback()` cũng invalidate cache membership. Giữ đọc được trường `needsJoin` trong metadata cũ. Không thay backend hay cơ chế kiểm tra quyền.

5. **Các bước bắt buộc chưa có ngân sách chờ tổng tại worker.** Bổ sung giới hạn mỗi lần chạy: chờ khôi phục session 30 giây, upload ảnh chính gồm resolve/recovery URL 120 giây, RPC 45 giây. Đây là giới hạn xử lý treo, không phải thời gian gửi mục tiêu. Thumbnail vẫn dùng giới hạn 5 giây đã có. Timeout của bước bắt buộc chuyển thành lỗi mạng để dùng cơ chế retry hiện tại; cancellation của WorkManager/logout vẫn thoát. Không tăng số lần thử: vẫn tối đa 3 lần theo `runAttemptCount`. Ảnh đã upload và mã nộp được giữ lại khi retry để tránh upload/cộng thưởng trùng.

## Kiểm chứng

Lệnh cuối:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon
```

| Kiểm tra | Kết quả cuối |
|---|---|
| Unit test Android | 254/254, 35 suites, 0 failure/error/skipped |
| ReportSubmitManagerIntegrationTest | 6/6 |
| ReportBackgroundSubmissionTest | 6/6 |
| ReportSubmissionLatencyTest | 3/3 |
| ReportUploadWorkerTest | 7/7 |
| assembleDebug | Thành công |
| lintDebug | Task hoàn tất; XML còn 1 Error, 460 Warning, 2 Hint |
| git diff --check | Không có lỗi whitespace |
| ADB devices | Không có thiết bị kết nối |

Các test riêng là tập con của tổng 254. Kiểm tra mới bao gồm chính sách expedited thực sự được lưu trong WorkManager, giữ công việc khi hết quota, khôi phục request chưa enqueue, gắn lại work cũ mà không thay ID, bỏ qua tài khoản khác, chống gửi trùng giữa nhiều app, giữ lỗi và thử lại đúng campaign, upload/RPC treo, reuse ảnh/mã nộp khi retry, session khôi phục chậm, cancellation từ parent và thông báo Android 9.

Một lượt full suite có `homeDeadlineTimerMustPruneEachDeadlineWithoutRefresh` thất bại ở mốc thời gian ảo đầu tiên. Không thay source hay assertions của test này: chạy riêng test đã qua, sau đó chạy lại toàn bộ 254 test cũng qua. Cần theo dõi độ ổn định của kiểm thử hẹn giờ; không coi các lượt thất bại trước đó là chưa từng xảy ra.

Lỗi lint còn lại là `LocalContextGetResourceValueCall` tại `OwnerAppDetailScreen.kt:525`; dòng này đã tồn tại trong HEAD trước thay đổi, không thuộc luồng gửi báo cáo. Vì cấu hình dự án đặt `abortOnError=false`, task lint thành công không có nghĩa lint sạch lỗi.

Bằng chứng cuối lưu tại `.tmp/report-background-evidence/`, gồm XML các suite, lint XML, tổng kết, hash source và APK. Log cuối: `.tmp/report-background-final-recheck.log`. Log lượt có test hẹn giờ thất bại: `.tmp/report-background-final.log`; XML lỗi: `.tmp/report-background-mission-failure.xml`; log chạy riêng: `.tmp/report-background-mission-recheck.log`.

## Cần xác nhận trên thiết bị trước phát hành

- Gửi một báo cáo rồi chuyển ngay sang app đang test; kiểm tra báo cáo được ghi nhận trên backend và trạng thái khi quay lại.
- Gửi A rồi B liên tiếp; quay lại từng app để kiểm tra tiến trình, lỗi và nút thử lại không lẫn nhau.
- Thử mạng chậm/mất mạng, chuyển Wi-Fi sang dữ liệu di động; kiểm tra retry giữ cùng mã nộp, không nhân bản báo cáo hoặc thưởng.
- Để Android thu hồi process trong lúc gửi rồi mở lại; kiểm tra bài đang chờ được theo dõi lại và không phải chọn lại ảnh.
- Đo log `ReportSubmitManager`, `ReportUploadWorker`, `FirebaseStorageHelper`: thời gian chờ lịch, session, upload/URL, thumbnail và RPC. Chưa có p50/p95 hoặc số đo trước/sau trên mạng thật.

APK kiểm thử: `app/build/outputs/apk/debug/app-debug.apk`. Chưa commit/push/phát hành; không deploy SQL, Storage rules hoặc dịch vụ cloud trong lượt này.
