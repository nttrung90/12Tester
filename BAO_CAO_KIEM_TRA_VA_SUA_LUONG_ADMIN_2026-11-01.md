Đã hoàn tất kiểm tra và sửa luồng admin trong mã nguồn ngày 01/11/2026. Công việc được giao qua OpenCode CLI bằng `google/antigravity-gemini-3.8-flash`, `--variant high`, sau đó Codex review diff, trả sửa các hồi quy và kiểm thử độc lập. Chưa triển khai lên Supabase production hoặc thiết bị thật.

Các lỗi được xác nhận và sửa:

| Vấn đề | Kết quả sau sửa |
|---|---|
| Trừ 50 credit khi chỉ có 5: số dư về 0 nhưng lịch sử/response vẫn ghi −50 | Ghi đúng delta −5; kiểm tra NULL, số 0, mục tiêu không tồn tại và overflow; giữ khóa dòng và tính nguyên tử |
| Admin bị khóa vẫn gọi được RPC quản trị; staff bị khóa vẫn duyệt/từ chối báo cáo | Chặn caller không đủ quyền hoặc bị khóa; giữ quyền duyệt hợp lệ của owner/admin/moderator và mức thưởng hiện hành |
| Đi vòng RPC qua UPDATE cấu hình, DELETE campaign, hoặc UPDATE campaign do admin bị khóa sở hữu | Siết RLS; test chứng minh admin bị khóa không thể tự mở lại campaign đã bị moderation; owner chưa khóa vẫn sửa được |
| Client database roles có TRUNCATE/TRIGGER dư thừa | Thu hồi trên bảng public đối với anon/authenticated/PUBLIC; giữ quyền vận hành của owner/service role. Đây là kiểm tra capability của database role, không khẳng định PostgREST cung cấp endpoint TRUNCATE |
| Khóa user ID không tồn tại vẫn trả success | Trả lỗi USER_NOT_FOUND |
| Response cũ ghi state sau đổi UID hoặc generation; refresh cũ ghi đè mutation mới | Tách ownership/sequence, reset state theo phiên, bỏ response cũ, giữ cancellation và giải phóng đúng loading flag |
| Double-submit trước khi coroutine chạy; callback xóa campaign chạy sau đổi phiên | Chặn thao tác trùng ngay lúc tiếp nhận; callback/state/refresh chỉ chạy khi đúng owner của request |
| Cache cấu hình RAM/đĩa bị response đọc/lưu cũ ghi đè | Đăng ký mutation và chụp token dưới cùng khóa publication; giữ ordering và cache public; không nuốt cancellation |
| Dialog lưu Profile/ContactReport không Bundle-saveable | Lưu ID primitive; giải target từ danh sách hiện tại; giữ owner UID/generation cho bản nháp; tiêu thụ save signal để không tự đóng editor khi tạo lại màn hình |
| Màn home-stats tạo lại trong khi save đang chạy phát sinh lượt đọc cũ | Chờ save hoàn tất trước khi nhận lượt load mới; có test tái hiện thất bại trước bản vá |
| autoStartPolling=false vẫn tự chạy polling khi auth phát sự kiện | Kiểm tra flag ở nhánh auth; fixture dùng ViewModelStore lifecycle và đóng client; giữ assertion Held → Showing |

Hợp đồng `get_campaign_detail` từ migration 068 được giữ nguyên ngoài kiểm tra locked caller cho `can_manage`: các trường DTO, bộ đếm tester reports/feedbacks, warning count và app-update eligibility/receipt không đổi. `reject_tester_report` giữ thuật toán hiện hành về bounded clawback, ledger metadata, reason code, checkin tạm tính/đã xác nhận và warning rules.

Phạm vi đã rà soát gồm quản lý/tìm kiếm/lọc người dùng, khóa/mở khóa, điều chỉnh credit, thông báo riêng/broadcast và popup, cấu hình phiên bản VI/EN cùng legacy fallback, chu kỳ thống kê, lịch nhiệm vụ ngày/revision/history, phản hồi liên hệ, quản lý campaign và duyệt báo cáo của staff. Các giới hạn danh sách có sẵn (500 profiles, 300 contact reports) được giữ nguyên; đây không phải lần triển khai phân trang mới.

Kết quả kiểm thử cuối:

| Kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| `npm --prefix supabase/tests test` | 22 runner, exit 0; gồm regression admin mới và ca locked owner UPDATE | [backend-final.log](F:/AI/12Tester/audit/admin-flow-2026-11-01/backend-final.log) |
| Oracle SQL độc lập, nạp active migrations 062–069 | 103 checks PASS | [independent-after-verification.log](F:/AI/12Tester/audit/admin-flow-2026-11-01/independent-after-verification.log) |
| Gradle offline: admin flow, campaign actions, config cache, daily policy, popup, cache publication, owner counts và campaign push | 12 suite, 122 tests; 0 failures/errors/skips | [android-test-summary.json](F:/AI/12Tester/audit/admin-flow-2026-11-01/android-test-summary.json) |
| `:app:assembleDebug` trong lượt kiểm tra cuối | BUILD SUCCESSFUL | [android-final-validation.log](F:/AI/12Tester/audit/admin-flow-2026-11-01/android-final-validation.log) |

Hai ca cuối do Codex kiểm tra bổ sung đã tái hiện thất bại trước sửa và qua trong bộ cuối: [home-stats overlap](F:/AI/12Tester/audit/admin-flow-2026-11-01/home-stats-save-overlap-before.log), [locked owner UPDATE](F:/AI/12Tester/audit/admin-flow-2026-11-01/locked-owner-update-before.log). Bằng chứng lỗi ban đầu nằm trong [independent-before-probe.log](F:/AI/12Tester/audit/admin-flow-2026-11-01/independent-before-probe.log).

Thay đổi tập trung ở 7 file production Android và [migration 069](F:/AI/12Tester/supabase/migrations/069_admin_audit_hardening.sql), cùng tests/scripts liên quan. [Manifest đối chiếu](F:/AI/12Tester/audit/admin-flow-2026-11-01/final-change-manifest.json) ghi 10 file có từ trước được sửa và 5 file source/test mới. Checkpoint 220 file trước sửa được giữ tại [before.zip](F:/AI/12Tester/audit/admin-flow-2026-11-01/before.zip). Không xóa source, sửa baseline/archive, commit, push hoặc triển khai production; các thay đổi cũ trong workspace được giữ để đối chiếu.

Các phiên OpenCode sử dụng cùng model và mức high:

- Backend: `ses_e6a62108dffe094XZ6LuADdPUS`.
- Android admin/users/reports/popup: `ses_e6a448523ffeaUQRkeFYEy5E01`.
- Android config repository/campaign actions: `ses_e6a41beaaffeKxzipmi1SGd6Ls`.

Log CLI, handover từng phần, XML kết quả test và hash source cuối nằm tại [thư mục audit](F:/AI/12Tester/audit/admin-flow-2026-11-01). Báo cáo tổng hợp này bao gồm các bản vá cuối sau handover từng phần; dùng kết quả kiểm thử cuối ở bảng trên làm mốc nghiệm thu.

Để áp dụng thực tế, đưa database qua các active migrations còn thiếu theo đúng thứ tự đến 068, rồi chạy toàn bộ `069_admin_audit_hardening.sql` bằng quyền migration/database owner. Không chạy migration archive hay SQL backup trong audit. Sau đó cập nhật Android từ source đã sửa; [APK debug](F:/AI/12Tester/app/build/outputs/apk/debug/app-debug.apk) đã sẵn để kiểm tra cục bộ. SHA-256 APK: `AC1977C0B8FCCD2AA97BC46759126D53DF66736751699DD0AB9B570E77D56A10`.

Các kiểm thử Android dùng Robolectric/Ktor MockEngine; SQL dùng PGlite. Chưa đăng nhập tài khoản admin thật để thao tác UI trên thiết bị hoặc kiểm tra database production. Khôi phục dialog đã được review trong code; không tuyên bố đã kiểm thử xoay màn hình bằng instrumentation trên máy thật.
