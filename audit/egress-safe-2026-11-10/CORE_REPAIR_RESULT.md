# Kết quả xác minh lõi đồng bộ egress — 10/11/2026

Lõi đồng bộ có điều kiện đã biên dịch, vượt qua toàn bộ 603 kiểm thử Android và tạo được APK debug. Đây là kết quả cục bộ của phần lõi; vòng hoàn thiện gợi ý app, tái sử dụng cơ sở so sánh sau mutation và telemetry vẫn đang thực hiện.

Antigravity CLI sử dụng `gemini-3.8-flash-high`, mức suy luận `high`. Codex điều phối, chuẩn bị các bản sửa giới hạn, review và đối chiếu kết quả độc lập.

## Thay đổi đã xác minh

- Thêm RPC v2 có điều kiện; giữ API cũ, trường dữ liệu và tác dụng nghiệp vụ như ghi nhận đăng nhập/tự duyệt báo cáo.
- Kiểm tra phản hồi JSON trước khi ghép: phiên bản/ngày/khối bắt buộc, kiểu Boolean, token, giá trị null hợp lệ và token của đúng cơ sở so sánh.
- Dữ liệu lỗi không bị chuyển thành danh sách rỗng hoặc bộ đếm 0. Có tải sửa đầy đủ giới hạn một lần; lỗi tiếp tục được báo thay vì ghi cache thiếu.
- Lưu token cùng payload trong snapshot có scope; phản hồi khác generation không được làm bẩn token hiện hành.
- Nhận diện RPC mới chưa tồn tại từ mã lỗi và nội dung lỗi gốc của SDK, không từ chuỗi message đã ghép thêm URL request.
- Chẩn đoán protocol không đưa nội dung phản hồi thô vào thông báo lỗi.
- Cờ tối ưu mặc định false, được đọc qua cấu hình hiện có. Chu kỳ cập nhật 3 phút/60 giây/3 phút và phạm vi report hiện tại được giữ.
- Sửa dọn dẹp tài nguyên của kiểm thử quản trị: theo dõi ViewModel/client, hủy và chờ tác vụ trước khi reset Main dispatcher. Các assertion giữ nguyên.

## Lệnh và bằng chứng

Tất cả lệnh chạy tuần tự bằng Java 21 có sẵn, với thư mục làm việc `F:/AI/12Tester`:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.ntt.crosstest.ui.viewmodels.AdminNotificationCampaignViewModelTest --offline
.\gradlew.bat testDebugUnitTest --offline
.\gradlew.bat assembleDebug --offline
```

Kết quả:

| Kiểm tra | Kết quả |
|---|---|
| Lớp kiểm thử quản trị đã sửa | BUILD SUCCESSFUL |
| Toàn bộ kiểm thử Android | 79 suites, 603 tests, 0 failures, 0 errors, 0 skipped |
| Build debug | BUILD SUCCESSFUL |

Kết quả đối chiếu từ XML trong `F:/AI/12Tester/app/build/test-results/testDebugUnitTest`, không suy ra từ trạng thái SUCCESS chung của CLI. Chi tiết công cụ nằm trong [round9.ndjson](F:/AI/12Tester/audit/egress-safe-2026-11-10/runs/round9.ndjson).

APK tại [app-debug.apk](F:/AI/12Tester/app/build/outputs/apk/debug/app-debug.apk). Bản này sẽ cần build lại nếu vòng hoàn thiện client tiếp theo có thay đổi.

Các kiểm thử độc lập đã thêm gồm `EgressReviewContractTest` (4 trường hợp qua repository/HTTP mock) và `EgressProtocolReviewTest` (6 trường hợp protocol/chẩn đoán).

## Database cục bộ

- Bốn nhóm conditional sync/candidates qua PGlite đã qua.
- Sáu nhóm cờ cấu hình/idempotence/rollback/quyền đọc đã qua; xem [báo cáo backend](F:/AI/12Tester/audit/egress-safe-2026-11-10/BACKEND_FLAGS_RESULT.md).
- Kiểm tra độc lập với 205 reports, ba attachment, pending ngoài cửa sổ 200, đổi profile, scope tài khoản, 15 ứng viên và quyền anon đã qua.
- Fixture detail không đổi: JSON legacy 197.901 byte, JSON sync 2.241 byte, giảm 98,87%. Đây là JSON sau giải mã trong kiểm thử cục bộ, không phải số đo wire/billing egress production.
- Bằng chứng: [independent-db-review.log](F:/AI/12Tester/audit/egress-safe-2026-11-10/runs/independent-db-review.log).

## Phần chưa được xác minh / còn lại

- Hoàn thiện client candidate: giới hạn fallback đúng lỗi thiếu RPC, scope, đo byte và kiểm thử các lỗi khác.
- Tối ưu cơ sở so sánh sau mutation và hoàn thiện số đếm request/trigger trong telemetry, rồi chạy lại toàn bộ kiểm thử/build.
- Chưa áp dụng migration hoặc bật cờ trên database từ xa; chưa phát hành/cài APK lên thiết bị người dùng.
- Chưa xác minh PostgREST thật, trải nghiệm trên thiết bị thật, CPU/P95 server hoặc mức giảm egress theo cửa sổ 3–7 ngày.
- Không có kết luận mọi lỗi của ứng dụng đã được loại bỏ; chỉ xác nhận phạm vi và bằng chứng nêu trên.
