# Kiểm tra và sửa cộng dồn nhiệm vụ + Đẩy app

Ngày 30/10/2026, dự án `F:\AI\12Tester`.

## Phạm vi và kết quả

Đã điều khiển OpenCode CLI bằng đúng model `google/antigravity-gemini-3.8-flash`, kiểm tra độc lập kết quả và yêu cầu sửa tiếp các lỗi còn tái hiện. Các sửa đổi nằm trong workspace; chưa chạy migration lên Supabase đang vận hành, chưa phát hành APK, chưa commit/push.

Migration sửa bổ sung là `supabase/migrations/066_push_and_carryover_hardening.sql`. Hash của baseline 062 và migration 063–065 được giữ nguyên; không sửa lịch sử migration để che lỗi.

## Các nhóm lỗi đã xử lý

| Mục rà soát | Kết quả sửa |
| --- | --- |
| R01, R03 | Không chốt/phạt/auto-pause hoặc suy luận đủ điều kiện resume khi nguồn cộng dồn chưa sẵn sàng. |
| R02 | Journal campaign, role và sự kiện test theo thời điểm quan sát thực tế; không dùng trạng thái hiện tại làm bằng chứng lịch sử. Chốt ngày đã có đủ bằng chứng không yêu cầu tester mở app. Giữ bằng chứng test khi campaign bị xóa; thao tác xóa nguồn test rõ ràng vẫn được phản ánh. |
| R04 | Tắt cộng dồn giữ phần thiếu cũ; bật lại nối tiếp phần còn bảo lưu. Chặn thêm/sửa/chuyển lịch đã có hiệu lực theo cách hồi tố, có bảo vệ và audit. |
| R05 | Kiểm tra uid và generation trước khi cập nhật cache/UI/callback sau RPC, bao gồm đổi A → B → A. |
| R06 | Tự tra pending receipt khi mở màn; đối soát chỉ đọc tách khỏi mua mới, không phụ thuộc số dư hay trạng thái campaign. Không thấy receipt không tự mua lại. Retry giao dịch gốc cần xác nhận rõ và dùng đúng UUID cũ. Trạng thái pending là observable. |
| R07, R08 | Receipt giữ identity gốc để tra lại sau xóa campaign và không chặn luồng xóa tài khoản; replay không tạo lượt thu phí mới. |
| R09 | Bảo vệ metadata và receipt theo vai trò/quyền thực thi, không chỉ tin cờ GUC tự đặt. |
| R10 | Checkpoint đăng ký có đủ provenance/trạng thái; công cụ đối soát legacy yêu cầu dữ liệu tường minh và ghi chú audit, giữ streak đã biết, không ghi đè ngày đã chốt hoặc tự trừ tiền. |
| R11 | Sửa diễn giải phần bảo lưu và format tài nguyên giao diện. |
| R12 | Retry pending có giới hạn 13 lần, trải khoảng 9 phút 05 giây, mỗi khoảng không quá 60 giây; dừng khi ready, đổi phiên/ngày hoặc đóng ViewModel. Job cũ không xóa tham chiếu job mới. |

Tester không đăng app vẫn theo quy tắc member cũ. Mua Đẩy app không xóa phần thiếu, không tăng số test đã làm và không reset chuỗi vi phạm.

## Lưu ý về dữ liệu lịch sử

Không thể tái dựng chắc chắn lịch sử trước thời điểm hệ thống bắt đầu ghi nhận journal chỉ từ bảng hiện tại. Những ngày đó được hoãn với `history_ready=false`, không đoán nợ hoặc phát sinh tiền phạt. Nếu dữ liệu cũ cần gỡ pending, người vận hành phải đối soát và nhập checkpoint tin cậy qua helper có audit. Đây là công việc dữ liệu riêng khi triển khai; không có thao tác tự đoán hoặc truy thu dữ liệu live trong lần sửa này.

## Kiểm chứng

Kết quả tổng cuối được ghi trong `audit/push-carryover-review-2026-10-30/verification.json`.

- Backend: `npm.cmd test --prefix supabase/tests`, 20 runner hoàn tất thành công; runner mới kiểm tra schema cuối 062–066, gồm ngày lịch sử đủ bằng chứng, tester vắng mặt, bảo lưu 8 app thiếu, lịch sử role/test và chặn lịch hồi tố.
- SQL đối chứng độc lập: 14/14 ca qua trong `probes.mjs` (4), `provenance.mjs` (3), `final-provenance.mjs` (7). Nhóm legacy assert hoãn an toàn khi không chứng minh được lịch sử; nhóm ngày được phủ đầy đủ kiểm tra kết quả nghiệp vụ thực, không chỉ kiểm tra pending.
- Android: **47 suite, 356 test, 0 failure, 0 error, 0 skipped**, gồm ca đối chứng độc lập response mua cũ A → B → A và sáu ca retry pending.
- Build: `:app:assembleDebug` trong cùng lượt nghiệm thu cuối, **BUILD SUCCESSFUL**.

Lượt Android chạy đồng thời trước đó bị xung đột tệp binary kết quả Gradle, đã giữ log riêng `android-final-collision.log`; không dùng lượt đó để kết luận sản phẩm. Lượt cuối dùng thư mục kết quả riêng và chạy sau khi đã kết thúc worker OpenCode.

Giới hạn: SQL chạy bằng PGlite, chưa kiểm chứng PostgreSQL nhiều kết nối/Supabase live; chưa thao tác giao diện trên thiết bị hoặc emulator. Không kết luận các kiểm thử local thay thế nghiệm thu môi trường production.

## Bằng chứng

- `audit/push-carryover-review-2026-10-30/backend-final.log`
- `audit/push-carryover-review-2026-10-30/android-final.log`
- `audit/push-carryover-review-2026-10-30/android-results/`
- `audit/push-carryover-review-2026-10-30/*-results.json`
- `audit/push-carryover-review-2026-10-30/baseline-hash-verification.json`
- Prompt và transcript OpenCode trong cùng thư mục audit, model được ghi trong sự kiện phiên CLI. Worker retry đã chạy xong kiểm thử mục tiêu; phiên CLI được dừng khi không còn Gradle đang chạy để chuyển sang nghiệm thu độc lập.
