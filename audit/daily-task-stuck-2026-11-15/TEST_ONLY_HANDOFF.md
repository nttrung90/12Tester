# Nghiệm thu bản sửa đã khôi phục — chỉ chạy và đọc kết quả

Người giám sát đã khôi phục đúng HomeViewModel từ baseline làm việc trước phiên (90916 bytes), giữ thay đổi trước đó của người dùng, rồi trực tiếp áp dụng bản sửa điều phối mới. Không dùng git HEAD làm baseline: HEAD thiếu nhiều thay đổi có sẵn. Các bản trước dùng để đối chứng không còn tương thích HomeScreen hiện tại.

## Phạm vi Antigravity ở bước này

1. Chỉ chạy Gradle, đọc XML/log và báo cáo lỗi. **Không chỉnh mã ứng dụng hoặc test, không thay tệp bằng bản nền, không git show/checkout/reset/clean, không tự sửa assertion.** Người giám sát sẽ xử lý lỗi còn lại. Không build APK/AAB, không deploy/push, không đổi version 1.2.5 build 45.
2. JDK: `C:\Program Files\Android\Android Studio\jbr`. Dự án `F:\AI\12Tester`.
3. Chạy `gradlew.bat testDebugUnitTest --tests "*SupervisorDailyTaskRecoveryReviewTest" --tests "*SupervisorDailyTaskHomeRecoveryReviewTest" --tests "*DailyTaskCompletionDisplayTest" --tests "*PendingCarryoverRetryRegressionTest"`. Chờ command thật sự kết thúc, không chỉ báo đang chạy. Lưu toàn bộ stdout/stderr vào `audit/daily-task-stuck-2026-11-15/antigravity-focused.log`, lưu exit code. Nếu lỗi compile hay test, báo lỗi/stacktrace và XML; dừng chỉnh sửa, chờ người giám sát sửa.
4. Nếu nhóm trên đạt, chạy toàn bộ `gradlew.bat testDebugUnitTest`, lưu log `antigravity-full.log` và exit code. Chờ hoàn tất, tổng hợp XML tests/failures/errors/skipped. Không coi UP-TO-DATE là lần chạy mới nếu test filters cũ vẫn còn; chạy đúng task không filters ở bước full.
5. Tạo báo cáo UTF-8 `audit/daily-task-stuck-2026-11-15/RESULT.md`: lệnh, exit code, số ca từng nhóm, lỗi chưa giải quyết, version không đổi, chưa kiểm chứng production/thiết bị thật. Không tuyên bố toàn bộ lỗi ứng dụng đã hết nếu chưa có bằng chứng.

## Thay đổi người giám sát vừa áp dụng

- Home dùng đường lấy kết quả có kiểu, timeout nội bộ là lỗi tải còn hủy từ parent vẫn truyền tiếp.
- Mọi kết quả trực tiếp được kiểm tra UID, session, ngày VN, mutation và thứ tự; công bố tăng phiên bản để chặn dashboard cũ.
- Retry sau report/campaign và chờ lịch sử có giới hạn và trạng thái lỗi/Thử lại. Manual retry gọi tải thật.
- Không xác nhận hoàn thành bằng todayCheckinCount khi snapshot thiếu; UI giữ guard pending và raw readiness.
- PendingCarryoverRetryRegressionTest chỉ bổ sung trường `history_ready:true` cho HTTP fixture để khớp contract hiện tại; giữ assertions.
- 3 ca Home giám sát có diagnostics đường gọi và trạng thái khi baseline hết giờ. Payload fixture legacy được cô lập feature flags V2. Nếu vẫn timeout, gửi nguyên diagnostics để người giám sát sửa fixture hoặc mã theo bằng chứng.
