# Kế hoạch thêm cấu hình tỷ lệ nhiệm vụ ngày vào Bảng Quản Trị Viên

Ngày nghiên cứu: **29/10/2026**. Phạm vi: khảo sát mã nguồn hiện tại và lập kế hoạch triển khai. Chưa sửa code sản phẩm, chưa kết nối/chỉnh database Supabase đang chạy, chưa build hoặc phát hành APK.

## 1. Kết luận

Có thể đưa việc đổi cơ số vào **Bảng Quản Trị Viên → Nhiệm vụ ngày**, tận dụng chính sách Supabase đã có. Admin chỉ cần nhập số app phải test, chọn ngày bắt đầu và bấm lưu; các lần đổi tiếp theo không cần viết SQL hoặc phát hành app mới.

Đây là **một tỷ lệ N app test / 1 app được tính nhiệm vụ / ngày**, không phải hai giới hạn độc lập và không phải giới hạn số app được đăng trong ngày. Mẫu số giữ nguyên là 1; admin thay N trong khoảng **1–30**, đúng ràng buộc backend hiện hành.

Người dùng cho biết mức hiện tại là **6:1**. Source migration 042 khởi tạo mức nền **3:1** và cho phép lên lịch thay đổi; nghiên cứu này chưa xác minh tỷ lệ live. Giao diện bắt buộc đọc mức đang có hiệu lực từ server, không tự mặc định về 3 hoặc 6 và không ghi đè lịch khi cài tính năng.

## 2. Bằng chứng từ dự án

| Thành phần | Hiện trạng và ý nghĩa |
|---|---|
| `supabase/migrations/042_configurable_daily_task_ratio.sql` | Có `daily_task_policies`, lịch sử `daily_task_policy_changes`, hàm đọc theo ngày, hàm lên lịch và hủy lịch. Tỷ lệ nguyên 1–30; ghi chú dài 1–1000 ký tự sau trim. |
| Cũng trong 042 | `schedule_daily_task_policy` và `cancel_daily_task_policy` chạy theo quyền người gọi, không cấp EXECUTE cho `authenticated`, `service_role`, `anon`, `PUBLIC`; các bảng cũng không mở quyền trực tiếp. Ngay cả JWT admin trong app hiện tại cũng không đổi được. |
| Cũng trong 042 | Chỉ sửa/hủy lịch tương lai, dùng ngày Việt Nam qua `clock_timestamp()`; có khóa advisory chung và trigger kiểm tra lại lúc commit. Chính sách đang/đã áp dụng là bất biến. |
| `supabase/operations/HUONG_DAN_DOI_TY_LE_TEST.md` | Hướng dẫn thao tác SQL Editor hiện tại, có lịch ngày tương lai và hủy lịch. App mới được tính nhiệm vụ từ ngày hôm sau. |
| `supabase/migrations/054_push_and_carryover_hardening.sql` | Định nghĩa mới hơn của `get_daily_task_status`: nhiệm vụ cơ bản lấy `tests_per_active_app × active_app_count`, kết hợp phần thiếu bảo lưu và nguồn app khả dụng. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt` | Màn hình “Bảng Quản Trị Viên” hiện có danh sách thành viên, cấu hình cập nhật và cấu hình chu kỳ thống kê. Chưa có cấu hình tỷ lệ nhiệm vụ ngày. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt` | Có mẫu tải/lưu cấu hình chu kỳ thống kê với kiểm tra UID, session generation và thứ tự request; có thể theo cấu trúc này cho tính năng mới. |
| `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` | Đã có các RPC admin, gồm `adminGetHomeStatsConfig`, `adminSetHomeStatsCycle`; chưa có RPC admin cho tỷ lệ nhiệm vụ. |
| `supabase/migrations/047_home_stats_cycle.sql` | Có mẫu API admin và kiểm tra revision cho cấu hình, nhưng không sao chép cách xử lý lịch của cấu hình thống kê sang nhiệm vụ ngày. |
| `supabase/tests/daily_task_ratio.sql` và runner tương ứng | Đã có test tỷ lệ, lịch, audit, quyền và số liệu nhiệm vụ. Cần bổ sung test cho API admin mới, giữ test cấm gọi hàm nội bộ. |

Workspace đang có nhiều thay đổi chưa commit, gồm chính các file admin, repository, models và tài nguyên ngôn ngữ. Khi triển khai cần bổ sung trên trạng thái hiện tại, giữ các thay đổi đang có; không checkout/reset file hoặc phục hồi bản cũ để làm nền.

## 3. Quy tắc nghiệp vụ giữ nguyên

1. Tỷ lệ áp dụng toàn hệ thống cho các chủ app thuộc diện tính nhiệm vụ, không cấu hình riêng từng tài khoản.
2. Nhiệm vụ cơ bản = **N × K**, trong đó K là số app được backend tính cho ngày đó. App mới đăng hôm nay chưa thuộc K hôm nay; backend có thêm điều kiện về trạng thái, cửa sổ 30 ngày và thời điểm hoàn thành.
3. Khi có bảo lưu phần thiếu: với owner và dữ liệu bảo lưu đã sẵn sàng, chỉ tiêu trước giới hạn nguồn app là **N × K + phần thiếu mang sang**; chỉ tiêu hiệu lực còn bị giới hạn bởi số app khác khả dụng. Không tự tính lại phần thiếu, điều kiện hoàn thành hoặc miễn phạt ở client.
4. Ví dụ **không có phần thiếu và đủ nguồn app**: mức 6 cho K=1 → 6 lượt test app khác nhau; K=2 → 12; mức 4 cho K=2 → 8. Đổi mức không xóa phần thiếu đã bảo lưu.
5. Mức mới bắt đầu từ **00:00 ngày đã chọn, giờ Việt Nam**, sớm nhất ngày mai. Không đổi hồi tố hoặc giữa ngày vì có thể làm thay đổi nghĩa vụ sau khi người dùng đã làm nhiệm vụ.
6. Cron chấm ngày cũ vẫn dùng chính sách của ngày cũ. Không chấm lại ngày đã chốt, sửa Credit, reset streak hoặc chạy lại migration 036/042/053/054.
7. Không thay đổi chính sách carryover, mức phạt, phần thưởng, slot hoặc điều kiện đăng app trong tính năng này.

## 4. Giao diện đề xuất

Thêm một mục có nhãn **“Nhiệm vụ ngày”** ở đầu nội dung Bảng Quản Trị Viên, phía trên ô tìm thành viên. Mở trang phụ hoặc bottom sheet riêng, ưu tiên bottom sheet đủ rộng và cuộn được. Thanh trên đã có bốn nút; không thêm biểu tượng khó nhận biết vào cùng hàng.

Nội dung:

- **Đang áp dụng:** “6 app test / 1 app được tính nhiệm vụ / ngày” nếu server trả mức 6; hiển thị ngày hiệu lực khi có ngày hữu hạn.
- **Cơ số mới:** ô số nguyên 1–30, nút tăng/giảm nếu thuận tiện; dòng đơn vị cố định “app test / 1 app / ngày”. Giá trị ban đầu lấy từ lịch đang sửa hoặc chính sách hiện tại sau khi tải thành công.
- **Bắt đầu áp dụng:** mặc định ngày mai theo `today` server; được chọn ngày tương lai. Ghi rõ “00:00, giờ Việt Nam”.
- **Lý do thay đổi:** bắt buộc, 1–1000 ký tự sau trim, ví dụ “Điều chỉnh nhiệm vụ theo lượng app hiện có”.
- **Minh họa:** xem trước mức cơ bản cho 1, 2, 3 app, kèm chú thích chưa tính phần thiếu bảo lưu và nguồn app thực tế.
- **Nút “Lưu lịch áp dụng”**; khi sửa lịch cùng ngày dùng “Cập nhật lịch”. Thành công báo rõ mức và ngày áp dụng, giữ mức hôm nay hiển thị riêng.
- **Lịch sắp áp dụng:** liệt kê các lịch tương lai theo ngày, có “Sửa” và “Hủy lịch”. Hủy yêu cầu lý do, chỉ tác động đúng ngày đã chọn. Nếu danh sách dài, cho tải thêm.
- **Lịch sử thay đổi:** phần mở rộng có phân trang, thể hiện thời điểm, tài khoản admin nếu có, mức trước/sau và ghi chú. Bản ghi cũ chưa có người thực hiện hiển thị “Thao tác hệ thống/SQL” theo dữ liệu thực tế.

Các trạng thái bắt buộc: đang tải, lỗi tải có nút thử lại, đang lưu/hủy, xung đột cấu hình, thiếu quyền, không có lịch tương lai. Không cho lưu nếu chưa tải được cấu hình hoặc dữ liệu nhập sai. Giữ bản nháp khi lưu lỗi; không hiện thành công dựa trên cập nhật lạc quan.

Mọi nhãn, thông báo và lỗi phải có đủ `values`, `values-en`, `values-vi`. Nội dung lưu trong `operator_note` là ghi chú admin, không dịch tự động.

## 5. Backend: migration mới, API admin riêng

Tạo migration mới, tên dự kiến `058_admin_daily_task_policy.sql`; kiểm tra lại số migration còn trống lúc bắt đầu triển khai.

### 5.1. Quyền thực thi

- Thêm các wrapper RPC `SECURITY DEFINER`, `search_path = ''`, gọi bảng/hàm bằng tên đầy đủ schema.
- Mỗi RPC kiểm tra `auth.uid()` khác NULL, profile tồn tại, `lower(role) = 'admin'` và tài khoản không bị khóa. Kiểm tra lại ở mỗi request, không tin role từ client.
- Thu hồi EXECUTE từ `PUBLIC`, `anon`; chỉ cấp `authenticated` cho API app mới. JWT member/moderator gọi được endpoint nhưng phải bị kiểm tra quyền từ chối.
- Giữ nguyên việc không cấp truy cập bảng, sequence và các hàm schedule/cancel cũ cho client. Không đưa service-role key vào APK và không đổi hàm cũ thành API công khai.
- Wrapper gọi hàm nội bộ bằng quyền owner hợp lệ. Không chỉ thêm `GRANT EXECUTE` cho hàm cũ: hàm đó vừa thiếu kiểm tra admin vừa chạy theo quyền invoker nên vẫn thiếu quyền bảng.

### 5.2. Hợp đồng API dự kiến

| RPC | Đầu vào | Kết quả |
|---|---|---|
| `admin_get_daily_task_config()` | Không | `today`, `timezone`, `revision`, `active_policy`, trang đầu `future_policies`, cursor tiếp theo nếu có. |
| `admin_schedule_daily_task_policy(...)` | `p_tests_per_active_app`, `p_effective_from`, `p_operator_note`, `p_expected_revision` bắt buộc | Lịch đã lưu, snapshot cấu hình mới và revision mới. |
| `admin_cancel_daily_task_policy(...)` | `p_effective_from`, `p_operator_note`, `p_expected_revision` bắt buộc | Lịch đã hủy, snapshot cấu hình mới và revision mới. |
| `admin_list_daily_task_policies(...)` | Cursor theo ngày/id, giới hạn có chặn tối đa ở server | Các lịch tương lai còn lại, thứ tự tăng dần; không chỉ trả lịch gần nhất. |
| `admin_get_daily_task_policy_history(...)` | Cursor theo audit id, giới hạn có chặn tối đa ở server | Lịch sử giảm dần theo id, cursor tiếp theo. |

`active_policy` gồm `id`, `tests_per_active_app`, `effective_from`, `is_baseline`, `operator_note`, `updated_at`. Với row nền `-infinity`, API chuẩn hóa ngày thành NULL và `is_baseline=true` để Android không parse ngày vô hạn. Lịch tương lai luôn có ngày hữu hạn. Không dùng `LocalDate.now()` của thiết bị để quyết định ngày hợp lệ.

### 5.3. Chống ghi đè và giữ lịch hiện có

1. Dùng cùng khóa advisory `daily_task_policy_schedule` như hàm cũ, lấy khóa trước khi kiểm tra revision và ngày server.
2. Revision phản ánh toàn bộ tập policy có thứ tự ổn định, gồm id/ngày/tỷ lệ/ghi chú/timestamp đầy đủ; không làm tròn timestamp xuống giây. Nhờ đó sửa từ SQL Editor qua hàm cũ cũng làm revision thay đổi.
3. Bắt buộc revision khác rỗng, so sánh dưới khóa; sai revision trả `DAILY_TASK_POLICY_CONFLICT`, không tự ghi đè hoặc tự retry với revision mới.
4. Validate ngày hữu hạn > hôm nay theo wall clock sau khi lấy khóa. Qua nửa đêm mà ngày được chọn không còn ở tương lai thì báo tải lại; không tự dời lịch sang ngày khác.
5. Lưu cùng ngày chỉ cập nhật lịch ngày đó qua `schedule_daily_task_policy`; tạo ngày khác không xóa các lịch khác. Không sao chép luồng cấu hình thống kê có thể dọn lịch tương lai.
6. Hủy qua `cancel_daily_task_policy` để giữ lý do và audit hiện có. Hủy lịch không tồn tại trả lỗi rõ ràng; tải lại danh sách sau lỗi/xung đột.
7. Không sửa trigger bất biến và trigger kiểm tra lại ở commit đang có. Mỗi mutation và audit phải nằm trong cùng transaction.

### 5.4. Lưu người thực hiện

Thêm cột nullable `actor_id uuid` vào `daily_task_policy_changes`, bổ sung trong `audit_daily_task_policy()` bằng `auth.uid()` từ phiên gọi RPC. Không nhận actor id do client gửi. Không bắt buộc foreign key xóa/SET NULL vì lịch sử có trigger chống sửa/xóa; UUID cần giữ được khi tài khoản bị xóa.

Giữ nguyên lịch sử cũ, không sửa/backfill các row audit bất biến; SQL Editor không có user claim thì actor để NULL, vẫn giữ session role, invoked role và ghi chú như hiện tại. Trigger ghi chú hủy hiện có tạo UPDATE rồi DELETE: lịch sử phải diễn đạt đúng, không giả thành hai lần đổi tỷ lệ.

### 5.5. Phạm vi migration

- Chỉ bổ sung API admin, helper revision/đọc danh sách và metadata audit; không viết lại `get_daily_task_status`, `_apply_daily_inactivity_penalty` hoặc công thức carryover.
- Không seed lại 3 hoặc 6; không đổi/xóa bất kỳ policy đang có nào.
- Có transaction, có kiểm tra dependency/schema trước khi sửa, có `notify pgrst, 'reload schema'`.
- Đồng bộ phần migration mới vào `full_schema_singapore.sql` theo quy ước dự án, nhưng production chỉ chạy migration bổ sung.
- Preflight mới kiểm tra các bảng/hàm 042 và trigger cần thiết; không tái dùng máy móc fingerprint của preflight 042 vì định nghĩa nhiệm vụ đã thay đổi ở 053/054.

## 6. Android: vị trí thay đổi

| File | Công việc |
|---|---|
| `data/Models.kt` | Thêm DTO policy, snapshot cấu hình, kết quả phân trang, lịch sử và params RPC; `@SerialName` khớp hợp đồng SQL. Ngày nền nullable, actor nullable. |
| `data/CampaignRepository.kt` | Thêm hàm gọi API admin mới; không ghi trực tiếp bảng và không fallback sang API SQL cũ nếu thiếu migration. |
| `ui/viewmodels/AdminUsersViewModel.kt` | State tải/lưu/hủy độc lập với thao tác thành viên và cấu hình khác; lưu revision và bản nháp, tải lịch/history khi cần, xử lý conflict bằng tải cấu hình mới và yêu cầu xem lại bản nháp. |
| `ui/screens/AdminUsersScreen.kt` | Thêm mục “Nhiệm vụ ngày”, điều khiển mở/đóng giao diện mới. |
| `ui/components/AdminDailyTaskPolicySheet.kt` (mới, tên dự kiến) | Hiển thị cơ số, lịch, ngày hiệu lực, ghi chú, ví dụ, validation và lịch sử; tránh tăng thêm một khối lớn vào màn hình admin hiện tại. |
| `res/values/strings.xml`, `values-en/strings.xml`, `values-vi/strings.xml` | Bổ sung đầy đủ chuỗi giao diện và lỗi. |
| `supabase/operations/HUONG_DAN_DOI_TY_LE_TEST.md` | Bổ sung cách thao tác trong app, lần nâng cấp backend/APK ban đầu, SQL Editor là đường vận hành dự phòng. |

State bất đồng bộ phải gắn UID + session generation + request sequence. Khi đổi tài khoản/đăng xuất, xóa cấu hình admin và bỏ qua kết quả request cũ, kể cả thông báo lưu thành công; request cũ cũng không được tắt trạng thái loading của request mới. Khi đóng/mở lại phải tải mới, không ghi đè input người dùng đang nhập bằng response đến muộn. Bắt `CancellationException` riêng và ném tiếp.

Trang chủ tiếp tục lấy nhiệm vụ từ `get_my_daily_task_status`, không nhân tỷ lệ trong app. Lưu lịch tương lai không đổi chỉ tiêu hôm nay. Kiểm tra cơ chế refresh/resume/chuyển ngày hiện có; chỉ sửa cache nếu kiểm thử chứng minh số liệu của ngày cũ còn hiển thị sau khi tải ngày mới thành công. Không cần Realtime hoặc broadcast thông báo cho toàn bộ người dùng trong phạm vi này.

Map lỗi theo mã ổn định: chưa đăng nhập, không phải admin/tài khoản bị khóa, tỷ lệ không hợp lệ, thiếu ghi chú, ngày không còn hợp lệ, xung đột revision, lịch không tồn tại và backend chưa được nâng cấp. UI dùng thông báo bản địa hóa, không đẩy nguyên URL/header/token/exception lên màn hình.

## 7. Kiểm thử và tiêu chí nghiệm thu

### Backend

- Test **`SET LOCAL ROLE authenticated` + claim của admin thật** gọi wrapper đọc/lưu/sửa/hủy thành công. Không chỉ đặt claim trong phiên postgres rồi kết luận API app hoạt động.
- Anonymous, member, moderator, admin bị khóa và phiên thiếu UID bị từ chối. Các đường ghi bảng trực tiếp, gọi hàm schedule/cancel cũ và sửa audit vẫn bị từ chối.
- Mức 1, 6, 30 hợp lệ; 0, 31, NULL sai. Chuỗi rỗng/whitespace, >1000 ký tự sai. Hôm nay, ngày quá khứ và ngày vô hạn sai; ngày mai/ngày tương lai hợp lệ.
- Tạo hai lịch ở hai ngày khác nhau; sửa/hủy một ngày phải giữ nguyên ngày còn lại. Cài migration trên fixture có tỷ lệ 6 và nhiều lịch phải giữ nguyên dữ liệu.
- Hai admin dùng cùng revision: lượt đầu thành công, lượt sau conflict; không ghi đè. Sửa qua SQL Editor cũng làm revision cũ hết hiệu lực. Thử khóa/chuyển ngày trên PostgreSQL staging thật vì test WASM không thay thế kiểm tra nhiều connection.
- Audit lưu trước/sau, lý do, UID server; transaction lỗi không để lại mutation/audit dở dang. Kiểm tra cặp UPDATE/DELETE lúc hủy và baseline `-infinity` qua JSON.
- Trước ngày hiệu lực, đúng ngày và ngày sau đó chọn đúng mức. Ngày hôm trước vẫn được cron chấm bằng mức cũ; row đã finalized/Credit/streak không bị đổi do lưu cấu hình.
- Hồi quy carryover trên schema mới nhất: thay N chỉ thay nhiệm vụ cơ bản; nợ cũ, giới hạn nguồn app, member/admin exemption và trạng thái chờ chốt giữ logic hiện tại.

### Android

- Mock RPC xác nhận tên và params, decode snapshot/history, revision mới và lỗi; không có request ghi trực tiếp bảng.
- ViewModel: loading/error/retry, validation, chặn bấm liên tiếp, bản nháp khi lỗi/conflict, kết quả cũ sau đổi tài khoản và sau request mới.
- Thiết bị: mở từ Bảng Quản Trị Viên; hiển thị mức live 6; lưu 4 từ ngày mai; đóng/mở thấy lịch còn; sửa 5; hủy và thấy mức kế tiếp đúng. Lưu thay đổi không báo đã áp dụng ngay hôm nay.
- UI tiếng Việt/Anh, màn hình nhỏ, bàn phím số, cuộn, font lớn; không tràn nút/lịch, không thiếu chuỗi.
- Qua ngày ở staging, tài khoản owner K=1 và K=2 nhận mức mới sau tải dữ liệu; có/không carryover và thiếu nguồn app vẫn hiển thị đúng RPC.

Lệnh dự kiến sau triển khai: runner SQL riêng mới `node supabase/tests/run-admin-daily-task-policy-test.mjs`; các runner hồi quy `run-daily-task-ratio.mjs`, `run-daily-task-carryover.mjs`, `run-push-and-carryover-hardening-test.mjs`; Gradle `:app:testDebugUnitTest` với các lớp liên quan và `:app:assembleDebug`. Gắn runner mới vào `supabase/tests/package.json`. Đánh giá lỗi theo baseline workspace hiện tại, không quy toàn bộ lỗi sẵn có cho tính năng mới.

## 8. Thứ tự triển khai và bàn giao

1. Chốt hợp đồng API và test fixture từ schema hiện tại; lưu baseline của các file đang thay đổi.
2. Viết migration/API/audit và test quyền với role authenticated thật; kiểm tra giữ nguyên lịch live bằng fixture.
3. Thêm DTO/repository/ViewModel rồi giao diện có đủ ba bộ resource; chạy test trọng tâm và build.
4. Chạy migration mới trên Supabase staging; kiểm tra nhiều phiên, mốc 00:00 và app trên thiết bị.
5. Áp dụng migration bổ sung lên đúng project Supabase, sau đó phát hành APK/AAB có giao diện admin. Các bản app cũ vẫn lấy nhiệm vụ qua RPC cũ như trước.
6. Sau lần nâng cấp này, admin có thể đổi cơ số trong app mà không cần SQL cho mỗi lần đổi. Nếu lịch chưa áp dụng, sửa/hủy trong app; nếu đã áp dụng, lên lịch đưa về mức cũ từ ngày tương lai.

**Hoàn thành khi:** admin đổi, xem, sửa và hủy được lịch nhiệm vụ ngày ngay trong Bảng Quản Trị Viên; mức hiện hành được đọc đúng; chỉ admin hợp lệ được thao tác; lịch sử có người thực hiện; nhiệm vụ/tiền/phần thiếu ngày cũ không bị tính lại.

Kết quả nghiên cứu hiện tại là tài liệu này. Chưa có bằng chứng test runtime, xác minh tỷ lệ production hoặc triển khai tính năng; các ca kiểm thử ở trên là yêu cầu cho bước thực hiện tiếp theo.
