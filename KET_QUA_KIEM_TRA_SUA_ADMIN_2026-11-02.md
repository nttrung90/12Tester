# Kết quả kiểm tra lại và sửa admin — 02/11/2026

Đã phát hiện và sửa thêm lỗi trong luồng chính sách nghiệp vụ và cấu hình TLS worker. **Toàn bộ 511 test Android trong 68 suite đạt, 0 thất bại, 0 lỗi, 0 bỏ qua; APK debug build thành công.** Bộ backend đầy đủ và các kiểm tra worker đều đạt. Không còn lỗi đã phát hiện chưa xử lý trong phạm vi lần kiểm tra này.

## Các lỗi đã sửa

1. **Lệnh dùng nhầm phiên:** lệnh lập/hủy được xác nhận dưới tài khoản A có thể gửi bằng phiên B nếu đổi tài khoản trước khi coroutine chạy. Đã kiểm tra UID/generation thực trước khi cập nhật trạng thái và gửi RPC, cũng như sau phản hồi/lỗi; lệnh cũ không làm kẹt trạng thái bận của tài khoản mới.
2. **Thử lại yêu cầu chưa rõ kết quả:** lưu UUID và payload đã xác nhận trước RPC, theo từng chủ sở hữu. Khôi phục ứng dụng, đóng hộp thoại, sửa nháp, chuyển chính sách hoặc qua ngày hiệu lực không thay thế yêu cầu gốc. Có banner/nút thử lại để đối chiếu với server; không tự gửi lại.
3. **Xung đột hủy lịch:** cập nhật revision và nội dung xác nhận cho lần thử lại tường minh, giữ lý do và kiểm tra CAS; tránh lặp mãi revision cũ.
4. **Mất nội dung nháp hoặc chọn sai ngày hủy:** giữ các chỉnh sửa số liệu đang nhập khi RPC hoàn tất. Khi một yêu cầu hủy chưa rõ kết quả, chặn chuyển sang yêu cầu hủy ngày khác cho tới khi đối chiếu xong; ngày hiển thị phải đúng với lệnh được gửi.
5. **TLS hiệu lực của worker:** URL `sslmode=disable`, `no-verify`, `ssl=false` hoặc cấu hình làm yếu kiểm tra hostname không còn ghi đè chế độ TLS mặc định. URL TLS hợp lệ vẫn dùng được; giữ cấu hình CA/certificate được hỗ trợ. `DATABASE_SSL=false` là lựa chọn tường minh cho môi trường phát triển cục bộ, được mô tả trong README.
6. **Fixture backend lệch ngày:** test clawback dùng check-in cố định 2026-11-01 nhưng report dùng `now()`. Đã lấy ngày Việt Nam trong cùng transaction để khớp report. Giữ nguyên assertion nghiệp vụ; không sửa SQL production để chiều theo fixture.

Thứ tự năm thẻ admin được giữ nguyên. Lần này sửa 10 tệp nguồn/test/tài liệu module và thêm một tệp chứa 12 ca hồi quy vào `app/src/test/java/com/ntt/crosstest/audit/BusinessPolicyRecheckIndependentTest.kt`. Không xóa tệp nguồn, không sửa migration hay dependency.

## Bằng chứng nghiệm thu

Log và snapshot ở `audit/admin-expansion-recheck-2026-11-02`.

| Kiểm tra | Kết quả | Tệp bằng chứng |
|---|---|---|
| Toàn bộ Android và APK cuối | 511 test / 68 suite; 0 thất bại, lỗi, bỏ qua; BUILD SUCCESSFUL | `root-final-all-with-regressions.log`, `final-android-results.json` |
| Chính sách: test độc lập và hồi quy liên quan | 24 test đạt, gồm 12 ca độc lập mới | `root-final-policy-targeted.log` và XML snapshot |
| Backend đầy đủ (`npm test`) | Đạt toàn bộ hồi quy cũ và tích hợp active migrations | `root-backend-all-after.log` |
| Worker phục hồi lỗi/chống chạy chồng/CLI/TLS | Đạt | `root-worker-after.log` |
| TLS hiệu lực qua `pg.Client` thật được cài | 7 cấu hình URL đạt yêu cầu; không kết nối DB | `root-worker-tls-after.log` |
| Tài nguyên Việt/Anh | 80 khóa và tham số định dạng khớp | `resource-validation.json` |
| Phạm vi thay đổi | Sửa 10 tệp, thêm 1 tệp test; không xóa tệp, không sửa migration | `change-manifest.json`, `before-manifest.json` |

Bộ 499 test cũ đã đạt ở đầu lượt kiểm tra; các ca độc lập bổ sung mới phát hiện những tình huống thiếu kiểm tra trước đó. Chín ca tái hiện ban đầu đều thất bại trước sửa; ba ca review bổ sung kiểm tra giữ nháp, đổi mục tiêu hủy và trạng thái bận của chủ phiên mới. Cả 12 ca đã được tích hợp vào thư mục test chính, giữ nguyên assertion và lưu bản gốc trong audit. JUnit XML từng lượt được lưu riêng trong `test-result-snapshots`; nguồn không thay đổi trong/sau lượt nghiệm thu cuối.

## OpenCode CLI và phần sửa chốt

Đã giao hai phiên CLI dùng đúng **`google/antigravity-gemini-3.8-flash --variant high`**:

- Chính sách/fixture backend: `ses_e6692a9d2ffeVerBnVRg37dr7M`.
- Worker TLS: `ses_e6690234effehk56SYDCxqsLGm`.

Metadata thực tế được kiểm tra qua export, lưu ở `model-main-final.json` và `model-worker-final.json`. Các lượt gọi model được ghi nhận đều dùng provider `google`, model `antigravity-gemini-3.8-flash`, variant `high`.

OpenCode thực hiện các bản sửa và nhiều lượt kiểm tra. Khi vòng giao sửa cuối bị quota chặn, Codex áp dụng lựa chọn trước của người dùng cho phép trực tiếp hoàn tất phần còn lại: bổ sung guard không ghi đè yêu cầu hủy chưa rõ kết quả khi mở ngày khác, khóa chỉnh sửa lý do của yêu cầu đã xác nhận, rồi chạy lại test tập trung và toàn bộ Android. Không đổi model hay nâng ngưỡng quota của OpenCode. Bản nguồn trước phần sửa chốt được giữ ở `opencode-partial-policy-viewmodel.kt` và `opencode-partial-policy-screen.kt` trong thư mục audit.

## APK cuối

- [app-debug.apk](app/build/outputs/apk/debug/app-debug.apk)
- Phiên bản **1.0.9**, versionCode **29**, biến thể **debug**.
- Kích thước **33.619.197 byte**.
- SHA-256: `C3F2368108FAF884DC02A26A623BDC5EF1F17A851D60BB8527F02A1430E2297C`.
- Metadata: `audit/admin-expansion-recheck-2026-11-02/final-artifact.json`.

Không có migration mới trong lần sửa này. Các chức năng mở rộng vẫn dùng backend 070–073 theo [hướng dẫn triển khai](HUONG_DAN_TRIEN_KHAI_MO_RONG_ADMIN_2026-11-01.md). Chưa triển khai Supabase production, commit/push, phát hành bản release hoặc kiểm tra trên thiết bị thật. Backend được kiểm tra bằng PGlite; phép kiểm tra TLS chỉ tạo client và kiểm tra cấu hình, không thực hiện kết nối TLS tới database thật.
