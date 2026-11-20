# Kết quả sửa cộng dồn nhiệm vụ — 05/11/2026

## Kết luận

Đã giao Gemini sửa qua OpenCode CLI và giám sát qua **3 lượt trong cùng phiên**, dùng đúng `google/antigravity-gemini-3.8-flash`, mức `high`. Các lỗi đã xác nhận trong phạm vi cộng dồn nhiệm vụ hiện đã được sửa và vượt qua các kiểm tra cục bộ cuối cùng.

Không chấp nhận hai bản bàn giao đầu khi kiểm tra độc lập còn phát hiện lỗi. Đặc biệt, đã sửa thêm lỗi chốt bù trừ Credit cho những ngày trước mốc đã đối soát của từng người.

**Chưa triển khai migration hoặc điều chỉnh dữ liệu trên máy chủ thật.** APK dưới đây là bản debug để kiểm tra.

## Kết quả thực tế

| Nội dung | Trạng thái cuối |
|---|---|
| Báo cáo Nhiệm vụ App bị tính vào test thường | Đã tách bằng loại báo cáo; mission không giảm nợ thường, không giúp vượt điều kiện bật lại app |
| Báo cáo thường pending/approved/rejected | Pending/approved được tính; rejected bị loại; trùng app chỉ tính một lần/ngày |
| Cộng dồn qua nhiều ngày, bật/tắt chính sách | Giữ đúng phần thiếu và phần còn bảo lưu theo quy tắc hiện có |
| Hoàn thành chỉ tiêu hôm nay nhưng còn nợ | Hiển thị số nợ trên dòng riêng có thể xuống dòng; server vẫn chặn bật lại app khi còn nợ |
| Member có nợ cũ hoặc lịch sử đang chờ | Giữ việc hoàn thành đăng nhập độc lập, đồng thời hiển thị nợ/trạng thái đang cập nhật |
| Chốt bù 3/31/40 ngày | Xử lý theo đợt có giới hạn, giữ tiền nhiệm và mốc lịch sử đáng tin; không bị một tài khoản thiếu bằng chứng làm kẹt tài khoản khác |
| Ngày trước checkpoint đã xác nhận | Không tự tạo thêm ngày chốt hoặc trừ Credit trước mốc đó khi chốt bù toàn hệ thống |
| Ngày đã chốt và sự kiện đến muộn | Giữ snapshot bất biến; không ghi lại lịch sử đã chốt |
| Nguồn báo cáo cũ không rõ loại hoặc mất nguồn | Đánh dấu chưa đối soát, không tự suy thành test thường hoặc coi là đã hết nợ |
| Quyền tài chính và chẩn đoán | Chốt ngày chỉ dành cho máy chủ; chẩn đoán chặn thành viên thường, phiên thiếu danh tính và admin bị khóa |
| Bộ kiểm thử nạp lại 066 sau mã mới | Đã cô lập việc thử chạy lại 066; kiểm thử hành vi hiện hành nạp đủ migration đến 080 |

Mã sửa nằm trong migration mới [080_report_context_carryover_and_gap_recovery.sql](supabase/migrations/080_report_context_carryover_and_gap_recovery.sql). Không sửa đè các migration 062–079. Các thay đổi có sẵn khác trong workspace được giữ nguyên.

## Bằng chứng nghiệm thu cuối

| Kiểm tra | Kết quả |
|---|---|
| Toàn bộ `npm test` trong `supabase/tests`, sau sửa cuối | PASS, mã thoát 0 |
| Kiểm tra độc lập cộng dồn | 18/18 ca đạt |
| Kiểm tra nâng cấp dữ liệu cũ, nguồn bị xóa, chạy lại migration | 8/8 ca đạt |
| Kiểm tra quyền và các trường hợp kẹt chốt ngày | 14/14 ca đạt |
| Các kiểm tra độc lập dừng/bật lại app ở lượt trước | Đạt, gồm ngưỡng 1–7 ngày và đổi cấu hình admin về 3 ngày |
| Android | 567 kiểm thử / 75 suite, 0 thất bại, 0 lỗi, 0 bỏ qua |
| Dựng APK debug | Thành công |

Tôi tự chạy lại 40 ca độc lập và các kiểm tra dừng/bật lại app trước đó trên cùng hash migration cuối. Với toàn bộ SQL suite, đã kiểm tra mã thoát và lưu nhật ký gốc đầy đủ từ lượt Gemini chạy sau sửa cuối. Với Android, đã đối chiếu XML JUnit, thời điểm kiểm thử và mã nguồn: toàn bộ nguồn Android liên quan được sửa trước lượt 567 kiểm thử thành công và APK được dựng sau đó. Lượt sửa cuối chỉ thay đổi backend/kiểm thử SQL, nên không chạy lặp Android khi nguồn ứng dụng không đổi.

Ca đối chứng tài chính: số dư **10.000 → 9.726 Credit**, chỉ trừ **274** cho ba ngày cần chốt; số bản ghi tạo trước checkpoint là **0**. Chạy lại không trừ thêm. Kiểm tra chốt bù cho nhiều người cùng lúc cũng xác nhận mốc cũ của người này không làm phát sinh khoản trừ trước checkpoint của người khác.

Bằng chứng lưu tại [thư mục nghiệm thu](audit/carryover-fix-2026-11-05/):

- [Kết quả kiểm tra độc lập và hash mã](audit/carryover-fix-2026-11-05/supervisor-final-probes.json)
- [Nhật ký toàn bộ SQL suite](audit/carryover-fix-2026-11-05/verified-final-database.log) và [thời điểm/hash xác minh](audit/carryover-fix-2026-11-05/verified-final-database.json)
- [Tổng hợp Android](audit/carryover-fix-2026-11-05/verified-final-android-summary.json) và [XML JUnit đã lưu](audit/carryover-fix-2026-11-05/verified-final-junit/)
- [Kết quả kiểm tra Credit và cộng dồn](audit/carryover-fix-2026-11-05/independent-results.json)
- [Đối chiếu thay đổi với bản sao trước sửa](audit/carryover-fix-2026-11-05/verified-final-changes.json)

## APK và vận hành

[APK debug](app/build/outputs/apk/debug/app-debug.apk)

SHA-256: `99754442F6B0066C697C817E8E0C8E694F9B86449EA0453C9DCCF7388D2F110F`.

Migration cuối đã kiểm tra có SHA-256: `F3BBF9ED3E841462BDD67D247AA2480FE507E67443D162340D3AD7EA93B51106`.

[Hướng dẫn triển khai, đối soát và phục hồi](supabase/operations/carryover_080_recovery.md) mô tả kiểm tra quyền, chẩn đoán lịch sử thiếu, đối soát ngày đã chốt và điều kiện nghiệm thu. Bản ghi đã chốt có nghi vấn cần đối chiếu dữ liệu thật và phương án điều chỉnh có kiểm toán; không tự ghi đè nợ hoặc số dư.

Chưa có môi trường PostgreSQL nhiều kết nối, thiết bị Android thật hoặc quyền truy cập dữ liệu máy chủ để xác minh cạnh tranh, chuyển ngày thực tế và phạm vi dữ liệu lịch sử bị ảnh hưởng. Không có commit, push hoặc triển khai production trong đợt này.
