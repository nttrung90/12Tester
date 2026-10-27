# Kế hoạch cấu hình tỷ lệ test theo app đăng trên Supabase

Ngày nghiên cứu: 27/10/2026. Cập nhật: **đã triển khai mã backend và kiểm thử cục bộ; chưa áp dụng Supabase live**. Xem `BAO_CAO_KIEM_THU_TY_LE_TEST_SUPABASE.md` và `supabase/operations/HUONG_DAN_DOI_TY_LE_TEST.md` để biết kết quả và cách vận hành.

Bổ sung theo yêu cầu tiếp theo: đã sửa ba chuỗi hướng dẫn trong cả ba bộ resource Android để không cố định tỷ lệ 3. Cần build/cập nhật app một lần; các lần đổi tỷ lệ tiếp theo chỉ làm trên Supabase. Các phần dưới giữ nội dung nghiên cứu/phân chia phạm vi ban đầu.

## 1. Kết luận và phạm vi

Có thể chuyển tỷ lệ hiện tại **3 app phải test / 1 app đang hoạt động / ngày** thành cấu hình trên Supabase. Sau khi triển khai backend một lần, mỗi lần đổi tỷ lệ chỉ cần thao tác trong Supabase SQL Editor, không cần sửa Kotlin, build APK/AAB hay phát hành phiên bản mới để thay đổi chỉ tiêu và cách chấm phạt.

Ứng dụng hiện đã nhận chỉ tiêu tính sẵn từ server. Tuy nhiên, hộp hướng dẫn trong APK vẫn ghi cố định “3 app”. Backend không thể thay nội dung resource đã đóng gói trong APK. Vì vậy:

- Phương án chính của tài liệu: **chỉ triển khai backend**, chỉ tiêu thực tế đổi theo cấu hình; ghi nhận giới hạn nội dung hướng dẫn ở app cũ.
- Nếu yêu cầu cả hướng dẫn luôn đúng với mọi tỷ lệ: cần một lần cập nhật app để bỏ số 3 khỏi hướng dẫn hoặc đọc nội dung động. Đây là hạng mục tùy chọn riêng, không nằm trong phạm vi backend được đề xuất.

Hiểu “mỗi app người dùng đăng” theo chính sách hiện hành: số campaign được tính vào nhiệm vụ ngày, không phải tổng số app từng đăng. Đây cũng không phải điều kiện “phải test trước N app mới được bấm đăng app”. Không thay phí đăng app, giá slot, mục tiêu số tester của campaign, thưởng report hay nhiệm vụ có thưởng do người dùng tạo.

## 2. Bằng chứng từ checkout hiện tại

Các đường dẫn dưới đây tính từ thư mục gốc dự án.

| Thành phần | Vị trí đã kiểm tra | Kết quả |
| --- | --- | --- |
| Công thức tỷ lệ | `supabase/migrations/036_daily_task_quota.sql:180` | `v_required := 3 * v_k;` đang cố định số 3 trong backend. |
| Giới hạn theo nguồn app | Cùng file, dòng 117–181 | `effective_required_tests = min(required_tests, available_apps)`. |
| Dữ liệu cho Trang chủ | Cùng file, dòng 604–621 | `get_my_dashboard(boolean)` trả `daily_task` từ helper chung. |
| RPC riêng cho nhiệm vụ | Cùng file, dòng 221–242 | `get_my_daily_task_status()` gọi cùng helper cho ngày Việt Nam hiện tại. |
| Chấm phạt | Cùng file, dòng 672–708, 746–777 | `_apply_daily_inactivity_penalty(date)` lấy trạng thái của ngày cần chấm; bỏ qua row đã finalized. |
| Tạm dừng / mở lại | Cùng file, dòng 885–1036 | Auto-pause đọc helper; resume gọi `refresh_daily_completion`, cũng dùng helper. |
| Model Android | `app/src/main/java/com/ntt/crosstest/data/Models.kt:811` | Có `required_tests`, `effective_required_tests`, `completed_tests`, `met`; không tự nhân tỷ lệ 3. |
| Thẻ nhiệm vụ | `app/src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt:79` | Tiến độ và mục tiêu dùng số server trả; câu hướng dẫn owner cũng truyền mục tiêu động ở dòng 399–401. |
| Hộp giải thích | Cùng file, dòng 140 | Đọc `R.string.daily_penalty_dialog_content` cố định trong APK. |
| Nhắc việc | `app/src/main/java/com/ntt/crosstest/work/NotificationHelper.kt:88` | Thông báo owner dùng `effectiveRequiredTests` nhận từ server. |
| Resource cố định | `app/src/main/res/values/strings.xml`, `values-en/strings.xml`, `values-vi/strings.xml`, dòng 190, 197, 200 | Có các chuỗi ghi “3 app”; hộp giải thích ở dòng 200 có đường gọi đang dùng. Không tìm thấy tham chiếu Kotlin tới hai key `daily_quest_desc`, `daily_task_goal` trong lần rà soát này. |
| Cache | `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt:41`, `1604–1633` | Dashboard có TTL 3 phút; khi không phát sinh request mới, UI không tự nhận thay đổi ngay. |
| Bản schema gộp | `supabase/full_schema_singapore.sql:10001` | Có bản sao công thức cố định; phải đồng bộ khi triển khai. |
| Cấu hình sẵn có | `supabase/migrations/018_app_version_and_update_config.sql` và `019_admin_update_app_config.sql` | `app_configs` phục vụ phiên bản/cưỡng chế cập nhật, chưa có lịch tỷ lệ nhiệm vụ. |

Trong các migration hiện có tới 041, không tìm thấy bản định nghĩa helper `get_daily_task_status` mới hơn migration 036. README có mô tả cũ hơn code/schema, vì vậy không dùng README làm căn cứ cho chính sách hiện tại.

Đây là bằng chứng từ mã nguồn local, chưa xác nhận database đang chạy hoặc APK người dùng đang cài giống checkout này. Checkout hiện ghi versionCode 17, versionName 0.9.7 trong `app/build.gradle.kts`.

## 3. Thiết kế backend đề xuất

### 3.1. Lịch chính sách thay cho một giá trị bị ghi đè

Tạo bảng riêng `public.daily_task_policies`, không ghép vào cấu hình phiên bản ứng dụng.

| Cột | Ý nghĩa / ràng buộc |
| --- | --- |
| `id` | Khóa chính của chính sách. |
| `effective_from` | Ngày bắt đầu áp dụng, kiểu `date`, UNIQUE; diễn giải theo `Asia/Ho_Chi_Minh`. |
| `tests_per_active_app` | Số nguyên bắt buộc, đề xuất CHECK từ 1 đến 30; mặc định nghiệp vụ hiện tại là 3. |
| `created_at`, `updated_at` | Thời điểm tạo/sửa lịch tương lai. |
| `operator_note` | Người thao tác/mục đích thay đổi để tra cứu; không chứa thông tin bí mật. |

Giới hạn 1–30 là rào nhập nhầm được đề xuất, không phải yêu cầu vốn có của hệ thống. Không cho 0 trong đợt này: client đang hiểu chỉ tiêu 0 là miễn vì không có app khả dụng; cho 0 sẽ khiến giải thích hiện tại sai nghĩa. Không thêm tỷ lệ thập phân trong phạm vi này.

Khi cài lần đầu, seed một chính sách nền tỷ lệ 3 có hiệu lực từ `date '-infinity'` để bao phủ mọi ngày cũ. Sentinel này chỉ được migration tạo, không cho thao tác vận hành thông thường sửa/xóa. Seed xong mới bật bảo vệ bất biến; không seed lại gây ghi đè khi chạy lại migration.

Mỗi ngày chỉ có một tỷ lệ chung cho tất cả owner. Helper nội bộ `get_daily_task_policy(p_date date)` chọn row có `effective_from <= p_date`, sắp xếp ngày giảm dần và lấy 1 row. Dùng **ngày đang tính nhiệm vụ**, không lấy tỷ lệ mới nhất theo thời điểm chạy hàm.

Nếu thiếu chính sách nền thì báo lỗi cấu hình rõ ràng và dừng chấm phạt trong transaction đó; không âm thầm coi tỷ lệ là 0 hay dùng chính sách tương lai. Việc chặn sửa/xóa chính sách nền và preflight phải ngăn tình huống này trước rollout.

### 3.2. Quy tắc thay đổi

Thêm hàm vận hành dự kiến `public.schedule_daily_task_policy(p_tests_per_active_app integer, p_effective_from date, p_operator_note text)` để chủ dự án gọi từ SQL Editor.

- Chỉ nhận ngày từ **ngày mai theo giờ Việt Nam**; NULL, quá khứ và hôm nay đều bị từ chối.
- Có thể tạo hoặc chỉnh lịch chưa có hiệu lực. Mỗi ngày có tối đa một row; thao tác cùng ngày phải được khóa/serialize và ghi audit trước/sau.
- Bảng có trigger chặn UPDATE/DELETE chính sách đã có hiệu lực và chặn INSERT hồi tố, kể cả khi chỉnh trực tiếp bằng Table Editor với quyền vận hành thông thường. Hàm phải kiểm tra cùng quy tắc.
- Mọi thay đổi lịch tương lai ghi vào bảng audit `daily_task_policy_changes`: row trước/sau, thời điểm và ghi chú người thao tác. `current_user` khi dùng SQL Editor có thể chỉ là `postgres`; không coi đó là danh tính cá nhân đầy đủ.
- Nếu cần hủy lịch chưa có hiệu lực, cung cấp hàm vận hành tương ứng với cùng kiểm tra ngày và audit. Không hướng dẫn người dùng xóa thủ công row lịch đã chạy.
- Giữ nguyên row của những ngày đã có hiệu lực. Hoàn tác nghiệp vụ bằng một lịch mới trong tương lai, không sửa quá khứ.

Chọn lịch theo ngày giúp tránh lỗi: tỷ lệ mới bắt đầu lúc 00:00, nhưng cron lúc 00:05 vẫn phải chấm **ngày hôm qua theo tỷ lệ cũ**. Một ô `ratio` bị ghi đè trực tiếp không đáp ứng yêu cầu này.

### 3.3. Một nguồn tính cho mọi luồng

Trong `get_daily_task_status(uuid, date)`, chỉ thay phần lấy hệ số:

```text
N = get_daily_task_policy(p_date).tests_per_active_app
required_tests = N × active_app_count
effective_required_tests = min(required_tests, available_apps)
```

Giữ nguyên chữ ký hàm, key JSON, kiểu dữ liệu và ý nghĩa của dữ liệu trả về. Không bắt app gọi RPC mới, không thêm trường bắt buộc, không đổi `mode`.

Giữ nguyên cách xác định K, A, D hiện có:

- K: app của owner đang `open`, hoặc `paused` vì `owner_inactive`, được tạo trước đầu ngày đang chấm và trong cửa sổ 30 ngày theo helper hiện hành.
- A: app `open` của người khác, theo điều kiện thời gian hiện hành.
- D: số campaign khác nhau đã test trong ngày, gồm check-in hợp lệ và report pending/approved; loại own app, rejected report và tránh đếm lặp check-in do report sinh ra.
- Thành viên không có app thuộc diện tính nhiệm vụ tiếp tục theo quy tắc đăng nhập; Admin/Moderator tiếp tục được miễn; cơ chế miễn phạt ngày đăng ký giữ nguyên.
- Phạt 1%/2%/5%/10%, nhân phần chỉ tiêu thiếu, quy tắc số dư, streak 7 ngày, auto-pause/resume tiếp tục dựa trên trạng thái chung.

Không viết thêm công thức riêng trong dashboard, worker, cron hoặc resume. Các hàm này đang đi qua helper chung, cần kiểm thử đường gọi thay vì sao chép lại logic.

### 3.4. Dấu vết khi chốt phạt

Thêm hai cột nullable vào `user_daily_tasks`: `policy_id` tham chiếu chính sách và `tests_per_active_app` ghi hệ số tại lúc chốt. Row live chưa chốt và row cũ được phép NULL.

Điều chỉnh `_apply_daily_inactivity_penalty(date)` có phạm vi hẹp để lấy chính sách đúng `p_eval` và ghi hai cột cùng các số liệu chốt sẵn có, trong cùng transaction. Đảm bảo hệ số ghi audit và hệ số helper sử dụng giống nhau. Không sửa cách tính phạt và không ghi đè row đã finalized. Chính sách ngày đã chạy là bất biến nên lịch tương lai không thay kết quả chấm ngày đó.

Không backfill hay tính lại Credit/streak của lịch sử khi thêm cột. Các số liệu K/A/D hiện vẫn được tính từ dữ liệu campaign/report theo thiết kế cũ; lịch tỷ lệ không biến toàn bộ hệ thống thành một sổ lịch sử bất biến cho trạng thái campaign. Không mở rộng sang sửa vấn đề lịch sử đó trong gói này.

### 3.5. Quyền truy cập

- Bật RLS và thu hồi quyền trực tiếp của `PUBLIC`, `anon`, `authenticated` trên bảng lịch/audit. Không cấp quyền chỉnh tỷ lệ cho tài khoản đăng nhập thông thường, kể cả qua RPC.
- Các hàm tạo/sửa/hủy lịch chỉ dành cho role quản trị database chạy từ SQL Editor; không grant EXECUTE cho `PUBLIC`, `anon`, `authenticated` hay `service_role` trong phương án vận hành này.
- Helper nội bộ thu hồi EXECUTE từ `PUBLIC`, `anon`, `authenticated`; chỉ mở cho role hệ thống thật sự cần kiểm tra. Các RPC hiện có vẫn trả trạng thái đã tính sẵn.
- Nếu dùng `SECURITY DEFINER`, cố định `search_path`, định danh đầy đủ schema, tránh dynamic SQL và rà soát quyền sau CREATE. Không dựa vào RLS một mình để bảo vệ function.
- Không đưa `service_role` key hay thông tin quản trị DB vào Android.

Thiết kế quyền được đối chiếu với [Supabase Database Functions](https://supabase.com/docs/guides/database/functions) và [Supabase Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security): quyền chạy function cần kiểm soát riêng, đặc biệt với `SECURITY DEFINER`.

## 4. Ví dụ vận hành sau khi đã triển khai

Ví dụ dưới đây là **giao diện SQL dự kiến**, hiện chưa có trong database; không chạy trước khi migration được triển khai và nghiệm thu.

```sql
-- Ví dụ đổi thành 2 app phải test / app đang hoạt động,
-- bắt đầu từ ngày mai theo giờ Việt Nam.
select public.schedule_daily_task_policy(
    p_tests_per_active_app => 2,
    p_effective_from => (now() at time zone 'Asia/Ho_Chi_Minh')::date + 1,
    p_operator_note => 'Chuyển tỷ lệ nhiệm vụ ngày sang 2:1'
);
```

Với đủ app khác để test:

| App được tính của owner | Tỷ lệ 3 | Tỷ lệ 2 | Tỷ lệ 4 |
| --- | --- | --- | --- |
| 1 | 3 lượt/ngày | 2 lượt/ngày | 4 lượt/ngày |
| 2 | 6 lượt/ngày | 4 lượt/ngày | 8 lượt/ngày |
| 6 | 18 lượt/ngày | 12 lượt/ngày | 24 lượt/ngày |

Nếu chỉ có 3 app khác khả dụng, owner có 2 app ở tỷ lệ 2 vẫn chỉ cần test 3 app. Khi không có app khác, chỉ tiêu tiếp tục được miễn. Ví dụ không thay định nghĩa app khả dụng hiện tại.

Sau mốc có hiệu lực, số mới xuất hiện khi client lấy trạng thái mới thành công. Không hứa thay đổi tức thì trên màn hình đang mở: client có cache và snapshot trong bộ nhớ. Có thể dùng thao tác làm mới Trang chủ hiện có; không cần gọi cơ chế realtime mới.

## 5. Các gói triển khai tuần tự

### Gói A — Xác nhận môi trường đang chạy, chỉ đọc

1. Đọc định nghĩa thật trên Supabase bằng `pg_get_functiondef` cho helper, dashboard, hàm phạt, refresh, pause và resume. So với migration 036 và các migration đã áp dụng.
2. Xác minh `user_daily_tasks`, các quyền hiện tại và lịch cron `daily_inactivity_penalty_job`; trong repo cron là `5 17 * * *` UTC, tương đương 00:05 giờ Việt Nam.
3. Xác nhận APK đang được sử dụng có model/trạng thái nhiệm vụ như checkout này. Lưu mẫu JSON từ hai RPC và ảnh thẻ nhiệm vụ; không đưa token/dữ liệu người dùng vào báo cáo.
4. Chụp định nghĩa/schema/quyền và số liệu đối chiếu cần thiết để hoàn tác kỹ thuật; dùng môi trường staging cho các thao tác chấm phạt.

Điểm dừng: nếu live có custom function hoặc APK cũ chưa nhận `daily_task`, cập nhật kế hoạch tương thích trước khi triển khai. Không suy đoán rằng có migration trong repo nghĩa là live đã chạy.

### Gói B — Migration backend một lần

File dự kiến: `supabase/migrations/042_configurable_daily_task_ratio.sql` (xác nhận số migration còn trống ở thời điểm làm).

Nội dung: bảng lịch/audit, ràng buộc/trigger bảo vệ, seed tỷ lệ 3, helper lấy chính sách, hàm quản trị lịch, thay hệ số trong helper hiện có, thêm cột audit và ghi chúng ở finalizer, quyền thực thi, reload schema nếu cần. DDL liên quan phải trong transaction.

Đồng bộ phần thay đổi vào `supabase/full_schema_singapore.sql`; kiểm tra fresh install và upgrade có cùng định nghĩa cuối. `supabase/check_mirror.sh` chỉ kiểm tra sự có mặt của object, không chứng minh thân hàm giống nhau.

Không sửa migration 036 đã phát hành và **không chạy lại toàn bộ 036**: file đó có đoạn backfill reset streak và grace 2 ngày, không phù hợp dùng làm cách cập nhật tỷ lệ. Seed/migration chạy lại không được reset lịch, Credit hoặc dữ liệu đã chốt.

Điểm dừng: chưa đổi tỷ lệ thực tế; baseline vẫn là 3, chưa triển khai production khi thiếu bằng chứng tương thích và kiểm thử database.

### Gói C — Kiểm thử backend và APK hiện có trên staging

Tạo SQL test có fixture và rollback, dự kiến `supabase/tests/daily_task_ratio.sql`; chạy trên PostgreSQL/Supabase thực, kiểm tra cả allow/deny bằng role thật. Không chỉ kiểm tra chuỗi SQL hoặc phép nhân mô phỏng.

| Nhóm | Điều kiện nghiệm thu |
| --- | --- |
| Tương đương baseline | Với tỷ lệ 3, dữ liệu RPC và kết quả phạt khớp trước migration trên cùng fixture, trừ hai cột audit mới. |
| Các tỷ lệ | 1, 2, 3, 4, 30; K = 0/1/2/6; A = 0, nhỏ hơn, bằng và lớn hơn N×K. |
| Kết quả test | D = 0, thiếu 1, vừa đủ, vượt; duplicate cùng app chỉ 1 lượt; own app không tính. |
| Vòng đời report | Pending/approved có tính, rejected không tính, duyệt lại report ngày trước không cộng thành lượt mới hôm nay. |
| Thời gian | Trước/sau 00:00 VN; lịch N=2 bắt đầu ngày D, chấm D−1 lúc 00:05 dùng N=3; chấm D dùng N=2. Chạy cron trễ vẫn chọn theo ngày chấm. |
| Bất biến lịch | Sửa/xóa tỷ lệ đang áp dụng hoặc insert hồi tố bị từ chối; lịch tương lai được sửa/hủy có audit; input NULL/0/âm/31 bị từ chối. |
| Lịch tương lai | Có nhiều mốc tương lai thì chọn mốc gần nhất không vượt ngày nhiệm vụ; không dùng nhầm row tương lai xa nhất. |
| Đồng thời | Hai thao tác lịch cùng ngày serialize đúng; chạy chấm phạt lặp/song song không trừ Credit hai lần. |
| Phạt | Giữ phần trăm theo streak; nhân phần thiếu theo effective target; minimum 1 khi thực sự thiếu và có số dư; không âm số dư; hoàn thành không bị trừ. |
| Lịch sử | Row finalized và credit log không bị sửa khi thêm/đổi lịch tương lai; ngày cũ chưa chốt chọn đúng hệ số cũ. |
| Miễn nhiệm vụ | K=0 vẫn theo đăng nhập; account mới giữ miễn ngày đăng ký; Admin/Moderator được miễn; A=0 không bị phạt quota. |
| Vòng đời app | App mới tính từ hôm sau; open/paused owner_inactive và cửa sổ 30 ngày giữ cách đếm hiện tại; admin_manual không tự mở lại. |
| Pause/resume | Streak 7 ngày và việc hoàn thành quota mới được helper phản ánh đúng ở auto-pause, refresh và resume; không có công thức tỷ lệ riêng. |
| Quyền | anon/authenticated không sửa bảng và không gọi hàm quản trị; authenticated chỉ lấy trạng thái của mình qua RPC đang dùng; helper không lộ dữ liệu user khác. |
| Tương thích APK | Giữ nguyên APK, đổi lịch trên staging, qua mốc hiệu lực rồi refresh: thẻ hiện mục tiêu mới, `met` đúng, notification dùng mục tiêu mới. Hộp giải thích còn số 3 được ghi rõ là giới hạn đã biết. |
| Lỗi tải/cache | Mất mạng, request lỗi và cache cũ không được dùng làm chứng cứ server đang tính sai; kiểm tra response mới và trạng thái sau force refresh. |
| Cài đặt | Cài mới và upgrade cùng kết quả; migration chạy lại không ghi đè lịch hay reset dữ liệu nghiệp vụ. |

Ghi rõ fixture, lệnh chạy, PASS/FAIL và phần chưa chạy trong báo cáo `BAO_CAO_KIEM_THU_TY_LE_TEST_SUPABASE.md`. Không đánh dấu “đã kiểm thử app” nếu mới đọc code hoặc chạy SQL.

### Gói D — Triển khai và hướng dẫn thao tác

1. Triển khai migration production với baseline 3 sau khi các gate trên đạt; kiểm tra hai RPC bằng tài khoản thử được phép.
2. Đối chiếu trạng thái, quyền và lịch cron; không tạo job chấm phạt trùng.
3. Khi muốn đổi tỷ lệ, gọi hàm lịch trong SQL Editor với ngày từ hôm sau. Kiểm tra lại row lịch và audit ngay sau thao tác.
4. Qua ngày có hiệu lực, đối chiếu target mới và lượt chấm ngày cũ; xác minh không phạt theo tỷ lệ sai ngày.
5. Hướng dẫn chủ dự án chỉ thao tác số tỷ lệ, ngày áp dụng, ghi chú; không cần sửa function hay phát hành app mỗi lần.

Hoàn tác nghiệp vụ: tạo chính sách mới trả về tỷ lệ trước từ ngày tương lai. Nếu lịch chưa tới ngày chạy, có thể sửa/hủy bằng hàm vận hành. Nếu migration lỗi kỹ thuật, dùng bản định nghĩa đã lưu và phương án rollback đã thử trên staging; việc khôi phục source không tự hoàn trả Credit đã trừ. Không chạy hồi tố để tự “sửa số dư”, không xóa lịch hoặc audit đã sử dụng.

## 6. Hạng mục tùy chọn để hướng dẫn trong app luôn đúng

Không thuộc gói backend. Chỉ làm nếu được mở rộng phạm vi:

- Thay hướng dẫn cố định bằng câu trung tính ở cả ba bộ resource, ví dụ: “Chủ app cần hoàn thành chỉ tiêu test hôm nay hiển thị trên thẻ nhiệm vụ. Chỉ tiêu phụ thuộc số app đang hoạt động và chính sách hệ thống.”
- Bỏ ví dụ cố định 1→3, 2→6, 6→18 khỏi hộp phạt. Thẻ nhiệm vụ vốn đã hiển thị số động nên không bắt buộc thêm API/config mới.
- Nếu muốn hiển thị rõ “N app / app đăng” hoặc lịch sắp áp dụng, phải bổ sung dữ liệu và UI trong một bản cập nhật riêng; không bắt backend tự đổi ý nghĩa các trường JSON hiện có.

Sau một lần cập nhật resource này, các lần đổi tỷ lệ tiếp theo vẫn chỉ làm trên Supabase. APK cũ vẫn còn lời hướng dẫn cũ cho tới khi người dùng cập nhật.

## 7. Trạng thái ở lần nghiên cứu ban đầu, trước triển khai

- Đã rà soát công thức, đường gọi RPC, xử lý phạt/pause/resume, model/UI/notification, cache và schema gộp trong checkout.
- Đã đối chiếu tài liệu chính thức Supabase về function và RLS.
- Chưa có kết nối Supabase live trong phiên này; chưa đọc định nghĩa live, chạy migration, SQL integration test hay kiểm thử APK/device.
- Chỉ tạo tài liệu kế hoạch. Chưa sửa code Android, migration hoặc backend production.
- Có sẵn thay đổi chưa commit trong `KE_HOACH_SUA_LOI_MANG_YEU_BAO_CAO.md`; giữ nguyên file đó.

Tiêu chí hoàn thành khi triển khai: chủ dự án thay tỷ lệ và ngày trong Supabase; APK hiện có lấy đúng chỉ tiêu mới qua các RPC cũ; chấm phạt chọn đúng chính sách theo ngày; lịch sử đã chốt được giữ nguyên; client không có quyền sửa tỷ lệ. Phần chữ “3 app” trong APK cũ là giới hạn riêng phải thông báo rõ, không tuyên bố đã được backend sửa.
