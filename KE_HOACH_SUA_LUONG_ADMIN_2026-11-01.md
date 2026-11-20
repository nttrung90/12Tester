# Kiểm tra và kế hoạch sửa luồng admin — 2026-11-01

## Phạm vi
Android Compose: vào trang quản trị, danh sách/tìm kiếm/lọc người dùng, khóa/mở khóa, cộng/trừ credit, gửi thông báo riêng/toàn bộ, cấu hình phiên bản, chu kỳ thống kê, lịch nhiệm vụ ngày, phản hồi liên hệ; quyền admin/moderator khi quản lý campaign và duyệt báo cáo. Backend là migration hiện hành 062–068, không phải thư mục archive.

## Phát hiện từ mã nguồn cần sửa và xác minh bằng test
1. **P1: Credit ledger không khớp số dư thực tế.** `admin_adjust_credits` chặn số dư ở 0 nhưng ghi log và trả `amount = p_amount`. Ví dụ số dư 5, trừ 50: số dư 0 nhưng log -50 thay vì -5. Cần kiểm tra NULL, 0, overflow, mục tiêu không tồn tại và giữ khóa dòng, đảm bảo thất bại rollback hoàn toàn.
2. **P1: Admin bị khóa vẫn có thể gọi RPC quản trị.** Nhiều RPC chỉ kiểm tra `role` (credit, khóa người dùng, cấu hình, phản hồi liên hệ, pause/resume campaign); helper `_assert_daily_task_admin` đã có kiểm tra unlocked nhưng chưa dùng nhất quán. Tái hiện trước khi sửa, kiểm tra cả RPC mới 067 và RLS đọc profiles/contact reports. Chỉ áp dụng guard phù hợp với hợp đồng quyền hiện tại; moderator không được tăng quyền thành admin.
3. **P1/P2: Dữ liệu admin có thể được publish sai phiên hoặc bị response cũ ghi đè.** `AdminUsersViewModel.refresh`, thao tác người dùng/thông báo/app config và toàn bộ `AdminReportsViewModel` thiếu guard uid/generation/request sequence, trong khi refresh đổi uid chưa xóa allUsers/appConfig. Một số guard homeStats còn cập nhật state từ response đã stale. Sửa race, xóa state nhạy cảm khi đổi phiên, bỏ kết quả cũ, giữ cancellation và chặn submit trùng trước khi launch.
4. **P2: Lưu state dialog bằng object không Bundle-saveable.** `rememberSaveable` đang giữ `Profile?` ở 3 dialog và `ContactReport?` ở dialog trả lời, các DTO chỉ dùng kotlinx.serialization, không Parcelable/Java Serializable. Có thể crash khi Android lưu activity state. Dùng ID primitive hoặc Saver phù hợp, gắn draft theo đối tượng/phiên để không gửi nhầm.
5. **P2: Khóa user không tồn tại vẫn báo success.** `admin_set_user_lock` không kiểm tra số dòng update; bổ sung lỗi rõ ràng, không thay đổi hợp đồng mục tiêu hợp lệ.
6. **Bổ sung kiểm tra độc lập:** `audit/admin-flow-2026-11-01/independent-before-probe.log` chứng minh UPDATE trực tiếp `app_configs` vẫn được admin bị khóa thực hiện. Cần chặn đường đi vòng này trong RLS; kiểm tra cả nhánh admin DELETE campaigns và quyền UPDATE reports. Database role `anon`/`authenticated` còn được cấp TRUNCATE/TRIGGER ở nhiều bảng: kiểm tra và thu hồi capability nguy hiểm không phục vụ app (TRUNCATE bỏ qua RLS), giữ nguyên quyền cần thiết cho API/service role. Đây là quyền database đã xác nhận, không kết luận có endpoint PostgREST cho TRUNCATE.

## Thứ tự triển khai và tiêu chí nghiệm thu
1. Lưu checkpoint source/test trước sửa tại `audit/admin-flow-2026-11-01/before.zip`, giữ nguyên mọi thay đổi có từ trước, không git reset/clean/checkout/commit/push.
2. Viết test hành vi tái hiện các lỗi trên trên DB PGlite nạp migration hiện hành, Ktor MockEngine/coroutines/Robolectric cho ViewModel và Compose saveability bằng API runtime hiện có nếu khả thi.
3. Tạo migration bổ sung 069, không sửa baseline/archive. Giữ chữ ký RPC, quyền execute, search_path, RLS và dữ liệu. Audit mọi admin RPC và các thao tác campaign/report liên quan, ghi rõ các luồng đã kiểm tra.
4. Sửa Android tối thiểu theo lỗi đã chứng minh, giữ VI/EN, cache và hành vi nghiệp vụ hiện tại. Không mở rộng thành thiết kế lại/phân trang toàn hệ thống nếu chưa cần thiết.
5. Chạy bộ test admin mới, daily-task-policy, direct-popup và các regression liên quan; Android targeted tests + compile/assembleDebug. Dùng JAVA_HOME `C:\Program Files\Android\Android Studio\jbr`, Gradle offline trước, từng lệnh tuần tự. Không kết nối/sửa production, không đọc/in secret.
6. Codex đọc lại diff so với checkpoint, chạy lại các kiểm tra cần thiết, trả sửa tiếp qua cùng OpenCode session nếu còn lỗi. Bàn giao với kết quả thực tế và hướng dẫn áp dụng migration/build; không tuyên bố đã deploy hoặc test thiết bị nếu chưa thực hiện.

## Giao việc
OpenCode CLI model `google/antigravity-gemini-3.8-flash`, variant `high` (theo yêu cầu “hight”). Log, session ID, bằng chứng trước/sau và bàn giao lưu trong thư mục audit nói trên.

## Nghiệm thu
Đã hoàn thành. Kết quả cuối: 22 backend runners, 103 SQL checks độc lập, 122 Android tests và assembleDebug đều qua. Review bổ sung đã chặn locked admin đi vòng qua owner UPDATE campaign và chặn home-stats reload trong lúc save. Xem `BAO_CAO_KIEM_TRA_VA_SUA_LUONG_ADMIN_2026-11-01.md` cho bằng chứng, giới hạn kiểm thử và hướng dẫn áp dụng migration 069. Chưa triển khai production.
