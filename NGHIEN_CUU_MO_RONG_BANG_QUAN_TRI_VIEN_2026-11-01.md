# Nghiên cứu mở rộng Bảng Quản Trị Viên — 01/11/2026

## Kết luận và phạm vi

Có thể đưa phần lớn thao tác vận hành dữ liệu ứng dụng lên Bảng Quản Trị Viên bằng kiến trúc hiện tại: Android → phiên Supabase Auth → PostgREST/RPC → PostgreSQL. Các chức năng mới cần được chia thành phần đã có quyền/API, phần có nghiệp vụ nội bộ nhưng chưa có API admin, và phần phải thiết kế cấu hình/nghiệp vụ mới.

Đây là khảo sát mã Android và active migrations 062–069 trong workspace; chưa kiểm tra schema/quyền hoặc trạng thái triển khai của Supabase production. Không sửa source ứng dụng, chạy migration hay thay đổi dữ liệu production trong lượt nghiên cứu này. Các tên RPC mới bên dưới là đề xuất, chưa tồn tại.

## Nền tảng hiện có

| Năng lực | Bằng chứng trong mã |
|---|---|
| App dùng Supabase Auth + PostgREST qua client chung | [SupabaseHolder.kt](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/SupabaseHolder.kt) |
| Danh sách thành viên chỉ tải tối đa 500 hồ sơ, tìm kiếm/lọc trên tập đã tải | [CampaignRepository.kt](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt:2122), [AdminUsersViewModel.kt](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt:239) |
| Điều chỉnh credit, khóa/mở khóa, gửi thông báo, cấu hình phiên bản và chu kỳ thống kê | [Migration 069](F:/AI/12Tester/supabase/migrations/069_admin_audit_hardening.sql:18) |
| Lên lịch, sửa/hủy lịch tương lai và lịch sử tỷ lệ nhiệm vụ ngày | [Migration 062](F:/AI/12Tester/supabase/migrations/062_consolidated_baseline.sql:2192) |
| Liên hệ/báo lỗi: đã có màn hình riêng cùng RPC trả lời và đánh dấu xử lý | [AdminReportsViewModel.kt](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminReportsViewModel.kt:26), [Migration 069](F:/AI/12Tester/supabase/migrations/069_admin_audit_hardening.sql:161) |
| Chiến dịch: đã có RPC admin tạm dừng, mở lại và gỡ; UI đang ở chi tiết chiến dịch | [Migration 069](F:/AI/12Tester/supabase/migrations/069_admin_audit_hardening.sql:658), [CampaignDetailViewModel.kt](F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt:797) |
| Báo cáo test: đã có duyệt/từ chối, truy cập báo cáo của admin chưa bị khóa | [Migration 069](F:/AI/12Tester/supabase/migrations/069_admin_audit_hardening.sql:931), [RLS báo cáo](F:/AI/12Tester/supabase/migrations/069_admin_audit_hardening.sql:1716) |

## Các tính năng nên bổ sung

### 1. Quản lý thành viên nâng cao và hồ sơ hỗ trợ — ưu tiên cao

Cho phép tìm kiếm trên toàn bộ database theo ID/tên/email; phân trang; lọc vai trò, trạng thái khóa, ngày tham gia. Trang chi tiết thành viên gom chiến dịch, slot, báo cáo, cảnh báo, lịch sử credit và nhiệm vụ ngày.

Điểm cần sửa thực tế: giới hạn 500 hồ sơ hiện tại có thể khiến admin không tìm thấy thành viên cũ dù tài khoản vẫn tồn tại. Cần tìm kiếm/phân trang phía server, thay vì chỉ tăng limit.

Đề xuất API: `admin_search_members`, `admin_get_member_overview`, `admin_get_member_credit_history`, `admin_get_member_daily_tasks`. Giới hạn dữ liệu trả về và phân trang từng tập, không trả toàn bộ lịch sử trong một JSON khổng lồ.

`credit_logs` hiện chỉ cho người dùng đọc lịch sử của mình qua RLS; hàm tính nhiệm vụ của user bất kỳ `get_daily_task_status(uuid,date)` chỉ cấp thực thi cho `service_role`. Vì vậy không thể chỉ dùng API lịch sử cá nhân rồi thay user ID. Cần RPC admin kiểm tra quyền và phạm vi dữ liệu.

### 2. Tra cứu và xử lý khiếu nại nhiệm vụ/credit — ưu tiên cao

Hiển thị theo ngày: số app thuộc diện tính nhiệm vụ, tỷ lệ, chỉ tiêu gốc, phần chuyển sang, lượt test hợp lệ, phần thiếu, số credit đã phạt, trạng thái chốt và chính sách áp dụng. Trả lời được các câu hỏi “vì sao hôm nay phải test N app?” và “vì sao bị trừ credit?”.

Có nền tảng trong `user_daily_tasks`, `daily_task_policies`, các journal và `get_daily_task_status`. Nên hiển thị rõ dữ liệu đã chốt với dữ liệu đang tính; không tính lại lịch sử bằng công thức hiện tại.

Khi cần bồi hoàn, tạo giao dịch điều chỉnh mới liên kết khiếu nại/giao dịch gốc, lý do và người thực hiện; không sửa số dư hoặc xóa dòng phạt trực tiếp. RPC điều chỉnh credit đã có, nhưng để retry an toàn cần bổ sung mã thao tác duy nhất và audit người thực hiện; bản hiện tại chưa có tham số chống lặp theo request.

### 3. Quản lý cộng dồn nhiệm vụ — ưu tiên cao

Mở rộng thẻ “Nhiệm vụ ngày”: xem cộng dồn đang bật/tắt, lên lịch bật/tắt từ ngày tương lai, xem lịch sắp áp dụng và lịch sử thay đổi.

Nghiệp vụ đã có trong [set_daily_task_carryover_policy](F:/AI/12Tester/supabase/migrations/066_push_and_carryover_hardening.sql:370), nhưng quyền gọi hiện chỉ dành cho `service_role` theo [grant trong baseline](F:/AI/12Tester/supabase/migrations/062_consolidated_baseline.sql:13308). Không cấp thẳng hàm này cho mọi `authenticated`, vì bản thân hàm chưa kiểm tra admin.

Cần RPC wrapper admin đọc/lên lịch, kiểm tra tài khoản admin chưa bị khóa, chống lưu đè bằng revision và lưu actor ID. Nếu có chức năng hủy lịch tương lai, cần triển khai quy tắc hủy riêng. Giữ nguyên lịch sử, mốc 00:00 giờ Việt Nam và quy tắc không hồi tố hiện hành.

### 4. Quản lý chiến dịch tập trung — ưu tiên cao

Thêm thẻ tìm kiếm theo tên app, package, chủ sở hữu, ID; lọc đang mở/tạm dừng/hoàn tất và lý do tạm dừng. Mở chi tiết để xem slot, nhiệm vụ, báo cáo và thực hiện tạm dừng/mở lại/gỡ.

Đã có RPC thay đổi trạng thái và quyền SELECT cho admin chưa bị khóa. Có thể thêm màn hình, truy vấn có phân trang và dùng lại RPC hiện tại; không cần tạo lại nghiệp vụ mutation. RPC liệt kê tổng hợp là lựa chọn nếu cần join nhiều nguồn.

Không dùng danh sách Trang chủ để làm danh sách quản trị vì feed phục vụ mục đích hiển thị khác. Không dùng nút mở lại để bỏ qua thời hạn slot/cửa sổ chiến dịch hoặc điều kiện lifecycle đang được backend kiểm tra.

Có thể mở rộng tiếp sang **quản lý nhiệm vụ app (`missions`)**: tìm nhiệm vụ vi phạm, xem người tham gia và tình trạng thưởng, tạm ngừng phát nhận mới. Đây là loại nhiệm vụ khác “Nhiệm vụ ngày”. Các hàm `pause_mission`, `resume_mission`, `delete_mission` hiện chỉ cho creator thực hiện; quyền admin của chiến dịch không tự mở quyền cho các hàm này. Cần RPC moderation riêng cùng quy tắc xử lý người đã nhận/đã nộp báo cáo và tiền thưởng, không gọi API chủ nhiệm vụ với ID người khác.

### 5. Hàng đợi báo cáo test toàn hệ thống — ưu tiên cao

Gom báo cáo chờ duyệt từ nhiều chiến dịch; lọc theo chủ app, tester, loại daily/mission, thời gian và trạng thái. Ưu tiên những báo cáo gần hết cửa sổ xử lý.

Dùng lại duyệt/từ chối và quyền đọc hiện có. Cần truy vấn/RPC danh sách có phân trang, thông tin chiến dịch/tester và số đếm tổng. Giữ quy tắc xử lý báo cáo sau 8 giờ ở backend; không thêm quyền sửa báo cáo đã chốt bằng cách UPDATE trực tiếp.

Nên làm từng báo cáo trước. Nếu thêm duyệt hàng loạt, cần hiển thị kết quả theo từng báo cáo để xử lý ca đã được chủ app hoặc hệ thống xử lý đồng thời.

### 6. Trung tâm hỗ trợ/liên hệ — có thể làm nhanh

Thêm thẻ dẫn đến `AdminReportsScreen` hiện có; xem danh sách mới/đã phản hồi/đã giải quyết, phản hồi người dùng và mở hồ sơ liên quan.

Không cần tạo lại module hỗ trợ hoặc backend cho các thao tác đã có. Phân công người xử lý, gắn nhãn, mức độ ưu tiên, nhiều lượt trao đổi và phân trang đầy đủ là phần mở rộng riêng, cần schema/RPC bổ sung. Lưu ý đây là `contact_reports`, khác với `tester_reports` dùng để báo cáo test.

### 7. Cấu hình mức thưởng, phạt và phí — giá trị cao, độ phức tạp cao

Đây là nhóm trực tiếp thay thế việc sửa SQL/function thủ công:

| Cấu hình đề xuất | Hiện trạng trong mã | Phạm vi cần thay đổi |
|---|---|---|
| Credit khởi tạo tài khoản | 100 trong `handle_new_user` | Policy backend và log khởi tạo dùng cùng giá trị |
| Mức phạt vắng nhiệm vụ | 1% / 2% / 5% / 10% trong migration 066 | Policy có ngày hiệu lực; giữ snapshot đã chốt |
| Phí đẩy app | 100 credit trong `push_campaign` | Backend, dữ liệu báo giá cho client và UI xác nhận |
| Phí slot và kỳ gia hạn | 50/100/200 credit, 30 ngày trong `unlock_or_renew_slot` | Backend và `SlotInfo.costForSlot`/các màn xác nhận Android |
| Thưởng báo cáo và thưởng duyệt | Nhiều hằng số trong luồng submit/approve/reject/auto-approve | Đồng bộ tất cả nhánh trả thưởng/thu hồi, chụp chính sách ở thời điểm tạo nghĩa vụ |
| Thưởng cập nhật app của chủ chiến dịch | Các mức ngân sách/thưởng trong `publish_app_update`, release/claim | Giữ cân đối tiền giữ chỗ, chi trả và hoàn; mỗi release giữ policy đã chốt |

Không phải mọi giá trị trên đều là cột cấu hình đang sửa được: nhiều giá trị đang nằm trong function SQL và code Android. Cần migration chuyển sang policy có phiên bản, API đọc chính sách/báo giá, cập nhật các hàm tiêu thụ và client một lần. Sau đó admin mới thay giá trị bằng form mà không cần sửa SQL mỗi lần.

Tên API đề xuất: `admin_get_business_policy`, `admin_schedule_business_policy`, `get_effective_business_policy`. Không dùng một JSON tùy ý làm cơ chế sửa mọi biến; mỗi loại policy cần giới hạn và kiểm tra nhất quán riêng. Phí/thưởng của giao dịch cũ không đổi theo policy mới. App cũ cần bị chặn thao tác hoặc được xử lý bằng chính sách tương thích khi chưa hiểu báo giá mới.

### 8. Phân quyền và xử lý cảnh báo — ưu tiên giai đoạn sau

Có thể thêm bổ nhiệm/gỡ moderator, quản lý vai trò và xem lịch sử thay đổi. `make_user_admin`/`make_user_moderator` đã có, nhưng chỉ cấp gọi cho `service_role`; chưa có luồng admin-app hoàn chỉnh để hạ quyền, bảo vệ admin cuối cùng hoặc phân quyền ai được bổ nhiệm admin.

Nên bắt đầu từ quản lý moderator; việc bổ nhiệm admin cần quyền riêng được thiết kế rõ, không coi mọi admin đều có quyền tối cao. `user_role_journal` đang ghi trạng thái vai trò/miễn phạt theo thời gian, không thay thế audit ai đã thực hiện thao tác.

Vai trò admin/moderator còn liên quan đến miễn phạt nhiệm vụ. Không dùng việc bổ nhiệm moderator để “miễn phạt” một thành viên. Nếu cần miễn phạt cá nhân, tạo chính sách miễn phạt riêng có thời hạn.

Cho phép xem cảnh báo và xử lý khiếu nại là hợp lý; chức năng thu hồi cảnh báo cần thêm trạng thái/sự kiện thu hồi và sửa các phép đếm cảnh báo tương ứng. Không xóa cảnh báo gốc hoặc chỉ reset streak để che lịch sử.

### 9. Nhật ký quản trị — nên đi cùng các thay đổi có ghi dữ liệu

Một thẻ tra cứu ai thao tác, với tài khoản/chiến dịch nào, khi nào, lý do, trước/sau và mã yêu cầu. Lọc theo admin, loại thao tác và khoảng thời gian.

Đã có audit chuyên biệt cho tỷ lệ nhiệm vụ, chu kỳ thống kê, các journal và credit logs; chưa có nhật ký thống nhất cho tất cả hành động admin. Ví dụ credit log hiện ghi người nhận, số tiền và lý do, không có cột actor của admin trong schema hiện tại; RPC khóa nhận lý do nhưng chưa có audit quản trị đầy đủ trong body.

Đề xuất `admin_action_events` ghi từ backend trong cùng transaction mutation; cho admin đọc qua RPC có phân trang, không cho client tự ghi/sửa/xóa audit. Không tuyên bố log cũ có actor nếu trước đây chưa lưu được.

### 10. Thông báo nâng cao — ưu tiên trung bình

Mở rộng thẻ thông báo hiện có: bản nháp, xem trước VI/EN, chọn nhóm nhận, hẹn giờ, lịch sử đợt gửi và chống gửi trùng.

RPC hiện gửi một người hoặc toàn bộ profiles; broadcast chưa có thực thể đợt gửi và mã request riêng. Cần bảng đợt gửi/người nhận, scheduler/worker, khóa xử lý và API truy vấn thống kê. Phân biệt “đã tạo thông báo”, “đã đọc” và “đã xác nhận popup”; đây không phải cùng một sự kiện. Cơ chế popup hiện dành cho thông báo riêng, không tự suy ra tỷ lệ đọc broadcast từ popup receipts.

### 11. Tổng quan vận hành và công việc lỗi — ưu tiên trung bình

Hiển thị thành viên mới/đang hoạt động theo định nghĩa rõ, chiến dịch theo trạng thái, báo cáo chờ, khiếu nại chưa xử lý; job thưởng nhiệm vụ đang pending/failed và kết quả dọn dữ liệu đã ghi nhận.

Nguồn có sẵn gồm `mission_settlement_jobs`, `cleanup_runs`, receipts và journal. Cần API tổng hợp giới hạn, chỉ mục phù hợp, cache ngắn và theo dõi thời điểm cập nhật; một số số liệu tác vụ bên ngoài Supabase cần worker gửi telemetry về trước.

Nên triển khai chế độ xem trước. Nút retry công việc lỗi chỉ được thêm qua thao tác server nằm trong danh sách cho phép, có kiểm tra quyền, giới hạn batch và audit. `retry_failed_mission_settlements` hiện là hàm nội bộ cấp cho service role. Không cho app gọi arbitrary SQL hoặc nhận secret vận hành.

Các báo cáo chưa rời thiết bị không thể được thống kê đầy đủ từ Supabase nếu chưa có telemetry; không hiển thị một con số “tất cả báo cáo bị kẹt” dựa riêng vào bảng server. Việc dọn ảnh đang có thành phần Firebase/GCS/Cloud Run, không phải chỉ thao tác bảng Supabase là điều khiển được toàn bộ storage.

### 12. Quản lý gói nạp và cờ tính năng — tùy nhu cầu, chưa ưu tiên

Đã có `topup_packages`, `topup_orders`, nhưng Android đang tắt luồng nạp bằng `TopupConfig.IS_TOPUP_FEATURE_ENABLED = false`; `process_topup_order` chỉ cấp cho service role. Không thể bật từ Supabase bằng một thẻ admin ở trạng thái client hiện nay.

Nếu phát triển tiếp: thêm API quản trị gói nạp/đọc giao dịch, đồng bộ nguồn giá thực tế của hệ thống thanh toán và xác minh giao dịch ở backend trước khi cộng credit. Cờ bật/tắt có kiểm soát cần client đọc remote config và backend kiểm tra cho thao tác ghi. Chỉ ẩn/hiện nút không phải cơ chế khóa chức năng phía server.

## Cách kết nối đề xuất

- Thao tác dữ liệu ứng dụng: giữ client và JWT hiện tại; RPC admin kiểm tra `auth.uid()`, vai trò và `is_locked` ở server. RLS/EXECUTE giới hạn phạm vi. Không suy quyền từ việc nút chỉ hiển thị cho admin. [Supabase: Functions](https://supabase.com/docs/guides/database/functions), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security).
- RPC đặc quyền dùng `SECURITY DEFINER` khi cần, cố định `search_path`, định danh schema và thu hẹp EXECUTE; không đơn thuần mở quyền các hàm nội bộ đang dành cho service role. [Hướng dẫn bảo vệ function](https://supabase.com/docs/guides/database/functions#secure-a-database-function).
- Thao tác Auth Admin, xác minh thanh toán hoặc gọi Cloud Run/GCS cần backend trung gian xác thực admin. Secret/service-role key ở server, không đặt trong APK; tiếp tục dùng public client key cùng JWT người dùng cho app. [Supabase: API keys](https://supabase.com/docs/guides/getting-started/api-keys).
- Mutation quan trọng cần audit, request ID chống xử lý lặp, transaction và revision chống lưu đè khi phù hợp. Policy có ngày hiệu lực và snapshot giúp giữ dữ liệu đã chốt. Cache/client state tiếp tục ràng buộc UID + generation như các ViewModel hiện tại.
- Bổ sung migration mới theo thứ tự sau migration cao nhất tại thời điểm triển khai; không sửa lại baseline 062 hoặc chạy archive. Deploy backend và client lần đầu, sau đó vận hành các giá trị đã đưa lên form bằng app.

## Lộ trình khuyến nghị

1. **Tăng khả năng quan sát và gom công cụ:** thẻ hỗ trợ trỏ màn hình hiện có; danh sách chiến dịch và hàng đợi báo cáo tập trung; tìm thành viên phía server; nhật ký cho các mutation mới. Tận dụng quyền/API đang có, thêm RPC đọc nếu cần.
2. **Giảm sửa database khi hỗ trợ người dùng:** hồ sơ thành viên đầy đủ, giải thích nhiệm vụ/credit, chính sách carryover, bồi hoàn có liên kết và chống lặp; sau đó quản lý moderator/cảnh báo với audit.
3. **Đưa quy tắc kinh doanh lên form:** chính sách phạt và credit khởi tạo trước, sau đó phí slot/đẩy app và thưởng có giữ chỗ. Đổi cả backend/client, kiểm thử giao dịch cũ và mới, giờ Việt Nam, lưu đồng thời và retry.
4. **Vận hành nâng cao:** thông báo theo nhóm/hẹn giờ, job monitoring và retry có giới hạn, gói nạp nếu sản phẩm cần.

Giữ thứ tự năm thẻ đã thống nhất. Mở rộng chức năng thành viên và nhiệm vụ trong hai thẻ tương ứng; các công cụ mới có thể đặt sau nhóm hiện có hoặc vào một màn hình công cụ quản trị riêng để bảng chính vẫn gọn.

## Ranh giới mục tiêu

Mục tiêu khả thi là **không phải vào Supabase sửa dữ liệu/cấu hình nghiệp vụ thường ngày**. Thay đổi schema, triển khai migration/function, xoay secret, cấu hình hạ tầng và khôi phục backup vẫn thuộc công cụ triển khai/vận hành. Quản lý danh tính Auth như xóa tài khoản hoặc thu hồi phiên cần backend phù hợp, không tương đương xóa một dòng `profiles`.
