# Sửa thông báo lỗi nộp báo cáo lặp — 05/11/2026

## Nguyên nhân

TestCheoApp khôi phục hàng đợi khi nhận trạng thái Authenticated, gồm cả lúc token được làm mới. ReportSubmitManager tiếp tục quan sát WorkInfo FAILED/CANCELLED của bài cũ; handleFailure trước đây luôn phát SubmitEvent.Failed. Khi observer kết thúc, activeCampaigns được giải phóng nên lần khôi phục tiếp theo lại phát cùng lỗi. deliveredRequests trước đây chỉ bảo vệ sự kiện thành công.

Lỗi cuối cùng và việc đã xử lý thông báo chưa được lưu bền vững. Sau khi WorkManager dọn lịch sử, bài thất bại còn trong PendingReportStore có thể bị xem như yêu cầu chưa enqueue và được gửi lại. MainActivity cũng thu sự kiện ngay cả khi Activity đang ở nền, đồng thời tạo Toast mới cho từng lỗi mà không hủy Toast trước.

## Thay đổi

- PendingReportStore lưu terminalFailure và failureNotificationConsumed, tương thích JSON cũ thiếu trường. Thao tác claim thông báo được đồng bộ và ghi bền vững trước khi phát sự kiện; reset chỉ khi người dùng bấm Thử lại.
- ReportUploadWorker lưu lỗi terminal trước khi trả FAILED. Worker tái tạo đọc lại lỗi đó và trả FAILED, không upload/gọi RPC tiếp. Lỗi mạng còn ngân sách retry vẫn giữ cơ chế retry hiện tại.
- ReportSubmitManager khôi phục lỗi cũ âm thầm, giữ Stage.FAILED để màn chi tiết có thể hiển thị và thử lại. WorkInfo FAILED/CANCELLED cũ chưa có marker cũng được khôi phục không phát toast.
- Nếu lịch sử WorkManager đã bị prune nhưng có terminalFailure, không enqueue lại. Nội dung/ảnh/URL upload và ID yêu cầu được giữ nguyên.
- Thử lại tường minh reset trạng thái lỗi/thông báo và giữ nguyên ID/ảnh. Khi lượt đó thất bại, được thông báo tối đa một lần.
- MainActivity chỉ thu sự kiện trong lifecycle STARTED. Hủy Toast trước khi hiện lỗi mới và khi Activity dừng; không xếp hàng các Toast lỗi toàn cục.

Các file sản phẩm: PendingReportStore.kt, ReportSubmitManager.kt, ReportUploadWorker.kt và MainActivity.kt. Không thay backend/schema hoặc cấu hình version. Source hiện tại là 1.1.5/build 35.

## Kiểm chứng

- Regression integration: thất bại mới → nhiều lần restore Authenticated → logout/login → tạo manager mới → prune WorkManager → vẫn FAILED, không phát lại sự kiện và không tạo work mới. Bấm Thử lại thất bại thêm → một sự kiện mới, các lần restore sau vẫn im lặng.
- Regression nâng cấp: WorkInfo FAILED của bản cũ chưa có marker không phát toast khi khôi phục.
- Regression Worker: lưu lỗi terminal → tạo Worker khác như sau process restart → không upload/RPC thêm; giữ nội dung nháp.
- Test Worker retry được cập nhật để đi qua bước prepareExplicitRetry như luồng thực tế.
- Toàn bộ test Android cuối cùng: **564 test, 75 suites, 0 failures, 0 errors**, BUILD SUCCESSFUL. Lượt trước từng gặp hai race tại cleanup Dispatchers.Main của AdminManagementCoreTest; không sửa hoặc bỏ test Admin, giữ log/XML rồi chạy lại toàn bộ và qua.
- assembleRelease: BUILD SUCCESSFUL. APK đã xác minh chữ ký và manifest 1.1.5/build 35.
- APK riêng: `.tmp/release/12Tester-1.1.5-35-no-repeat-report-error.apk`.
- SHA-256: `541640EB5BA94AEC2A7575CBCAABA0E7E6C13D89BB3422E14447298D0C19C552`.
- Evidence: `audit/report-failure-notification-2026-11-05/`.

Chưa thử thao tác trên điện thoại thật. Bản sửa xử lý thông báo và phục hồi yêu cầu lỗi, không chứng minh nguyên nhân mạng/backend khiến bài nộp ban đầu thất bại đã hết. Cài cập nhật APK rồi kiểm tra mở lại app/đăng nhập lại: lỗi cũ chỉ còn ở trạng thái bài nộp để tự bấm Thử lại, không phát toast lặp. Không cần SQL migration cho bản sửa này.
