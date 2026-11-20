# Nghiên cứu và kế hoạch sửa tự tạm dừng app do thiếu nhiệm vụ

Ngày kiểm tra: 05/11/2026, múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`.

Cập nhật triển khai: đã sửa mã và nghiệm thu cục bộ theo [báo cáo kết quả](F:/AI/12Tester/KET_QUA_SUA_TU_DUNG_APP_THIEU_NHIEM_VU_2026-11-05.md). Các phát hiện và kết quả trước sửa bên dưới được giữ làm hồ sơ nghiên cứu; xem báo cáo kết quả để biết trạng thái mới nhất và phần chưa triển khai trên máy chủ thật.

## 1. Kết luận

**Luồng cơ bản hoạt động đúng ở cả 7 ngưỡng, nhưng chưa thể nghiệm thu toàn bộ: đã tái hiện hai lỗi.**

1. **P1 — Lách điều kiện tạm dừng/bật lại bằng cách sửa ngày đăng app.** Chủ app có thể sửa `campaigns.created_at`, đăng nhập rồi bật lại app mà không cần thực hiện kiểm thử.
2. **P1 — Đếm sai chuỗi liên tục nếu bỏ sót ngày chốt trong lúc cộng dồn tắt.** Một ngày đã đạt chỉ tiêu nhưng chưa chốt không cắt chuỗi cũ; tài khoản có thể bị dừng toàn bộ app đủ điều kiện và bị áp mức phạt theo chuỗi sai.

Ngoài ra, có rủi ro vận hành: lịch sử chưa được đối soát có thể khiến trạng thái nhiệm vụ mãi chờ dữ liệu và không tự tạm dừng. Đây là nhánh bảo vệ tránh phạt khi chưa đủ bằng chứng, cần xử lý dữ liệu và giám sát, không được sửa bằng cách coi ngày chưa rõ là ngày thiếu.

**Yêu cầu bổ sung của chủ sản phẩm:** chủ app chưa hoàn thành toàn bộ nhiệm vụ thiếu đã cộng dồn từ những ngày trước **không được bật “Tiếp tục test” cho bất kỳ app nào của mình**, kể cả khi đã đạt chỉ tiêu hôm nay hoặc chuỗi thiếu đã được đặt về 0. Kế hoạch thực hiện và nghiệm thu nằm ở mục 7; đây là yêu cầu cần triển khai, chưa phải hành vi đã được xác nhận đúng ở bản hiện tại.

Kết quả này được xác nhận trên cơ sở dữ liệu cục bộ dùng **toàn bộ migration 062–078 đang có trong workspace**. Chưa đối chiếu định nghĩa hàm, lịch cron và dữ liệu trên Supabase production. Không sửa mã vận hành, không triển khai migration, không ghi dữ liệu máy chủ.

## 2. Luồng thực tế và phạm vi “toàn bộ app”

1. `apply_daily_inactivity_penalty()` chọn ngày cần chốt và gọi `_apply_daily_inactivity_penalty()`.
2. `get_daily_task_status()` tính nhiệm vụ, số app cần test, số đã test, tính sẵn sàng của lịch sử/cộng dồn và trạng thái đạt chỉ tiêu.
3. Finalizer lưu `user_daily_tasks.missed_streak` và cập nhật `profiles.daily_missed_streak`.
4. `sync_campaign_lifecycle()` lần lượt kết thúc app hết 30 ngày, xét thiếu nhiệm vụ, rồi xét hết hạn slot.
5. `pause_inactive_owner_campaigns()` đọc ngưỡng chung 1–7 từ `owner_auto_pause_configs`, chuyển app đủ điều kiện sang `paused`, lý do `owner_inactive`, và gửi thông báo có `threshold_days`.
6. Chủ app bật lại qua `resume_campaign()`: phải đạt chỉ tiêu hôm nay, dữ liệu sẵn sàng, app còn hạn và slot còn hiệu lực. Cập nhật trực tiếp `paused → open` đã bị chặn.

“Toàn bộ” hiện tại có nghĩa là **tất cả app đủ điều kiện của cùng tài khoản**, không phải mọi bản ghi app:

- Chỉ xét app `open`, đăng được ít nhất 24 giờ.
- Miễn app của Admin/Moderator và app có `admin_resumed = true`.
- Không tự tạm dừng nếu hôm nay đã đạt hoặc dữ liệu chưa sẵn sàng.
- App đã kết thúc/Production không thuộc nhánh này; app đủ 30 ngày được kết thúc trước khi xét thiếu nhiệm vụ.
- App `paused` với lý do khác được giữ nguyên lý do đó.
- Bộ đếm thuộc tài khoản và có thể chứa ngày thiếu từ thời điểm người dùng còn ở chế độ thành viên.

Đây là các quy tắc hiện hữu. Nếu yêu cầu sản phẩm là dừng cả app mới dưới 24 giờ hoặc bỏ các ngoại lệ, đó là thay đổi chính sách riêng, không được tự gộp vào bản sửa lỗi.

Ngưỡng N là N **ngày thiếu đã được chốt**, không phải N lần mở app hay N × 24 giờ kể từ lần cuối hoạt động. Baseline khai báo chốt hôm qua lúc 17:05 UTC, tương ứng 00:05 Việt Nam; đồng bộ vòng đời vào phút 11 mỗi giờ. Nếu lịch thực tế đúng cấu hình này, lượt đêm thông thường xét dừng lúc 00:11, và Trang chủ cũng có thể gọi đồng bộ. PGlite không thực thi pg_cron nên thời điểm chạy trên máy chủ chưa được xác minh.

## 3. Kết quả kiểm chứng

Đã chạy thành công:

```text
node supabase/tests/run-owner-auto-pause-test.mjs
node supabase/tests/run-admin-pause-resume-compatibility-test.mjs
node supabase/tests/audit-owner-auto-pause-flow.mjs
```

Script audit mới nạp migration hiện hành, dùng các hàm SQL thật, không thay hàm bằng dữ liệu giả. Dữ liệu lịch sử và sự kiện được thiết lập trong cơ sở dữ liệu dùng một lần; từng kịch bản được hoàn tác. Sự kiện lịch sử đại diện cho hoạt động đủ điều kiện đã được quan sát trong ngày đó.

| Kiểm tra | Kết quả |
| --- | --- |
| Từng ngưỡng 1, 2, 3, 4, 5, 6, 7; lưu qua RPC đúng quyền admin rồi chốt ngày thật từ checkpoint xác định | Chuỗi N−1 không dừng, chuỗi N dừng cả hai app của chủ |
| Đồng bộ lần hai | Không dừng lại, không gửi trùng thông báo |
| Chưa đạt hôm nay, dùng RPC bật lại | Bị chặn `OWNER_INACTIVE` ở cả 7 ngưỡng |
| Ghi trực tiếp `paused → open` | Bị chặn `DIRECT_RESUME_NOT_ALLOWED` |
| Có hoạt động đủ chỉ tiêu hôm nay rồi bật lại lần lượt hai app | Thành công ở cả 7 ngưỡng; không bị dừng lại ngay |
| App đúng 24 giờ và app còn thiếu 1 giây | Chỉ app đúng 24 giờ bị dừng |
| Chuỗi đã chạm ngưỡng nhưng hôm nay đạt chỉ tiêu | Không tự tạm dừng |
| Quyền cấu hình, giá trị ngoài 1–7, revision cũ, nhật ký quản trị | Bộ kiểm thử hiện có vượt qua |
| App Admin, app mới, app được admin mở lại, slot và luồng mở lại của admin | Các kiểm thử tương ứng hiện có vượt qua |
| Sửa ngày đăng rồi đăng nhập và bật lại | **Tái hiện lỗi số 1** |
| Ngày đạt chưa chốt nằm giữa chuỗi cũ và hôm qua; cộng dồn tắt | **Tái hiện lỗi số 2** |
| Lịch sử trước mốc coverage chưa có checkpoint | `carryover_ready=false`, không tự tạm dừng |

Lưu ý: `PASS` ở các kịch bản tái hiện lỗi nghĩa là đã chạy và xác nhận được hiện tượng lỗi, không có nghĩa lỗi đã được sửa. Script audit đang giữ kiểm tra hành vi lỗi hiện tại để làm bằng chứng; khi triển khai sửa cần chuyển thành kiểm thử kỳ vọng đúng.

Các kết quả bật lại sau khi đạt chỉ tiêu hôm nay trong bảng trên mô tả bản hiện tại, chưa nghiệm thu yêu cầu trả hết nhiệm vụ cộng dồn mới bổ sung. Cần cập nhật kiểm thử để trường hợp còn nợ ngày trước vẫn bị chặn dù `met=true`.

## 4. Lỗi 1: ngày đăng app còn sửa được từ client

### Bằng chứng

Kịch bản chạy bằng vai trò `authenticated`, đúng tài khoản chủ app:

1. Chủ có chuỗi thiếu 7, cả hai app đã bị `owner_inactive`.
2. Cập nhật ngày đăng của hai app thành thời điểm hiện tại.
3. Gọi `record_daily_login()`.
4. Gọi `resume_campaign()` cho app thứ nhất.

Kết quả:

```json
{"mode":"member","completed":0,"met":true,"result":{"status":"open","success":true}}
```

Không có sự kiện test mới. Như vậy bảo vệ đường mở lại trực tiếp hiện có vẫn chưa đủ.

### Nguyên nhân

- Chính sách `campaigns_update_owner` trong `069_admin_audit_hardening.sql:1698` cho chủ chưa khóa cập nhật dòng; không giới hạn riêng cột ngày đăng.
- Guard ở `078_admin_pause_reason_popup.sql:59` và trigger ở dòng 112 chỉ bảo vệ một số trường vòng đời; chưa bảo vệ `created_at`.
- `get_daily_task_status()` tại `071_business_policies.sql:2241` chỉ đếm app chủ đăng trước đầu ngày. Đổi ngày sang hôm nay làm cả hai app biến khỏi số app dùng để xác định nghĩa vụ chủ app.
- Hàm chuyển về chế độ thành viên tại dòng 2285; đăng nhập đủ để `met=true`. `refresh_daily_completion()` đặt chuỗi về 0, rồi RPC bật lại cho phép mở.
- Cùng cột đó còn quyết định mốc 24 giờ và 30 ngày nên tác động vượt riêng thao tác bật lại.

### Kế hoạch sửa

1. Thêm migration mới sau migration cuối cùng thực tế tại thời điểm triển khai; không sửa đè 075/078 đã phát hành.
2. Chặn client đổi `campaigns.created_at` sau khi tạo bằng guard áp dụng cho **mọi UPDATE**, gồm cả cập nhật chỉ một cột ngày. Phân biệt quyền thực thi tin cậy tương tự guard hiện có, không dựa vào cờ do client tự khai báo.
3. Kiểm soát ngày đăng lúc INSERT: lấy từ máy chủ hoặc từ RPC tạo app tin cậy. Rà luồng tạo app Android trước khi chọn cách tương thích để tránh làm hỏng client cũ.
4. Giữ nguyên các trường chủ app được sửa hợp lệ như tên, link, mô tả và luồng tạm dừng/Production được cho phép.
5. Đối soát các dòng có ngày đăng khác thường với journal trước khi cân nhắc khôi phục. Journal hỗ trợ phát hiện nhưng không mặc nhiên chứng minh timestamp do client gửi ban đầu là đúng.

Tiêu chí nghiệm thu: thử đổi timestamp trên cả app mở và app bị dừng; bị từ chối và không thay đổi nhiệm vụ/chuỗi. Không thể INSERT ngày giả để né mốc tuổi app. Sau bản sửa, chưa test thì RPC vẫn trả `OWNER_INACTIVE`, test đủ thì mở lại bình thường.

## 5. Lỗi 2: chuỗi “liên tục” nối qua ngày đã đạt nhưng chưa chốt

### Bằng chứng

Kịch bản có dữ liệu hoạt động đủ tin cậy:

| Ngày | Trạng thái |
| --- | --- |
| D−3 | Đã chốt, chuỗi thiếu 6 |
| D−2 | Có hoạt động test đủ chỉ tiêu, `get_daily_task_status().met=true`, nhưng chưa chốt |
| D−1 | Thiếu nhiệm vụ; chính sách cộng dồn đang tắt |
| D | Chạy đúng hàm cron `apply_daily_inactivity_penalty()`, rồi xét tạm dừng |

Kết quả tái hiện:

```json
{"intermediate_day_met":true,"streak":7,"expected_streak":1,"missing_day_rows":0,"paused":2}
```

Ngày D−2 phải cắt chuỗi. Hệ thống lại lấy 6 từ D−3, cộng 1 cho D−1 rồi dừng cả hai app. Nhánh tính phần trăm phạt cũng đọc chuỗi sai này, nên cần đối soát Credit của tài khoản bị ảnh hưởng.

### Nguyên nhân

- `062_consolidated_baseline.sql:3092`: hàm cron chỉ tìm và chốt bù các ngày thiếu khi chính sách cộng dồn đang bật. Khi tắt, nhánh dòng 3135 chỉ chốt hôm qua.
- `071_business_policies.sql:1897`: finalizer lấy `missed_streak` từ bản ghi đã chốt gần nhất có ngày **nhỏ hơn** ngày đang xét, không bắt buộc là ngày liền trước.
- Dòng 1924 cộng thêm 1 dù giữa hai bản ghi có khoảng trống. Không có bước xác minh ngày nằm trong khoảng trống đã đạt, thiếu hay chưa đủ dữ liệu.

Lỗi có thể xuất hiện khi cron bỏ một ngày, xử lý ngày đó bị hoãn, hoặc dữ liệu được phục hồi/chốt lại không theo thứ tự. Không cần client sửa dữ liệu hệ thống để tạo điều kiện này.

### Kế hoạch sửa

1. Cho cơ chế chốt bù chạy theo thời gian **độc lập với việc bật/tắt cộng dồn**. Chính sách cộng dồn chỉ chi phối phần thiếu chuyển tiếp; chuỗi thiếu luôn cần ngày liên tiếp.
2. Xác định ngày chưa chốt từ ngày tài khoản bắt đầu chịu nghĩa vụ, các checkpoint tin cậy và mốc coverage; xử lý từ cũ đến mới với giới hạn công việc rõ ràng.
3. Trong finalizer, chỉ lấy chuỗi từ ngày liền trước đã được xác nhận. Nếu thiếu predecessor thì chốt predecessor từ dữ liệu tin cậy hoặc để pending; không lấy một ngày xa bất kỳ hay profile làm bằng chứng cho chuỗi liên tục.
4. Quy định rõ ngày tạo tài khoản được miễn, ngày đạt, ngày miễn và ngày không có app khả dụng. Ngày đạt/miễn phải cắt chuỗi theo chính sách hiện hành. Ngày chưa rõ không được tự coi là thiếu hay đạt.
5. Bảo đảm chạy lại không phạt hai lần; dùng khóa và dấu chốt hiện có. Kiểm tra cả API chốt một ngày để không thể bỏ qua quy tắc thứ tự bằng cách gọi hàm con.
6. Tách công việc sửa dữ liệu cũ khỏi finalizer thường ngày: lập danh sách ledger có khoảng trống và chuỗi không chứng minh được; tính lại trong bản đối soát, có nhật ký cho mọi sửa Credit/trạng thái. Không tự ghi đè dòng đã finalized hoặc tự mở app đang bị admin dừng/hết slot.

Tiêu chí nghiệm thu: với mọi ngưỡng 1–7, ngày đạt ở giữa phải cắt chuỗi dù hôm đó chưa được chốt trước; các ngày thiếu thật được cộng đủ; dữ liệu không rõ thì pending; bật/tắt cộng dồn không thay đổi định nghĩa “liên tục”. Chạy lại không tăng chuỗi hay trừ Credit thêm.

## 6. Rủi ro vận hành: pending do lịch sử chưa đối soát

Đã tái hiện với mốc `coverage_start` là hôm nay, không có checkpoint đã chốt cho hôm qua, trong khi cộng dồn đang bật. Sau khi chạy hàm cron, hôm nay trả `history_ready=true` nhưng `carryover_ready=false`; dù profile có chuỗi 7, lượt xét dừng không dừng app nào.

Nhánh bỏ qua ở `075_owner_auto_pause_config.sql:177` là bảo vệ hợp lý khi chưa biết nghĩa vụ thực tế. Nhưng cron không tự tạo bằng chứng cho ngày trước coverage; không có đối soát thì có thể tiếp tục pending ở các ngày sau.

Kế hoạch vận hành:

- Theo dõi số tài khoản pending, ngày chưa chốt sớm nhất, tuổi của khoảng trống, lần chốt và lần đồng bộ thành công gần nhất.
- Đối chiếu lịch cron, múi giờ cron và định nghĩa hàm đang triển khai bằng truy vấn chỉ đọc.
- Dùng quy trình checkpoint tin cậy; trong repo đã có `reconcile_legacy_daily_task_checkpoint()`. Ghi chú người thao tác và nguồn xác minh, giữ đúng phần thiếu đã biết, không mặc định xóa nợ hoặc giả lập ngày thiếu.
- Kiểm tra giới hạn chốt bù 30 ngày; khoảng trống cũ hơn cần đường phục hồi có chủ đích.

Chưa có bằng chứng production đang gặp tình trạng này; cần đo trước khi thực hiện sửa dữ liệu.

## 7. Bổ sung: phải hoàn thành nhiệm vụ cộng dồn trước khi bật “Tiếp tục test”

### Quy tắc bắt buộc

- Điều kiện bật lại qua luồng chủ app phải đồng thời thỏa: dữ liệu nhiệm vụ/lịch sử sẵn sàng, đạt chỉ tiêu hôm nay, **phần nhiệm vụ cộng dồn còn lại bằng 0**, và các điều kiện quyền sở hữu, trạng thái app, thời hạn, slot hiện có.
- Nếu còn nhiệm vụ thiếu chuyển từ ngày trước thì chặn bật lại cho **tất cả app của cùng chủ**, không chỉ app đã tạo ra phần thiếu. Áp dụng cho mọi lý do tạm dừng mà chủ được phép tự bật lại, gồm `owner_inactive`, `owner_manual` và `slot_expired` sau khi gia hạn; không được đổi app hoặc lý do dừng để né điều kiện.
- `met=true` hoặc `daily_missed_streak=0` không chứng minh đã hết nợ cộng dồn. Việc đổi ngưỡng 1–7, tắt chính sách cộng dồn, đăng nhập, chuyển chế độ thành viên hoặc gia hạn slot không được tự xóa phần thiếu đã ghi nhận còn tồn tại.
- Nếu số app khả dụng hôm nay ít hơn số nhiệm vụ phải hoàn thành, giữ lại phần thiếu chưa trả. Không được dùng chỉ tiêu đã giới hạn theo số app khả dụng để kết luận đã hết nợ; chủ app tiếp tục hoàn thành trong các ngày sau rồi mới được bật lại.
- Khi dữ liệu chưa sẵn sàng hoặc chưa xác định được phần thiếu, trả trạng thái chờ đối soát và không bật lại. Không mặc định giá trị chưa biết thành 0.

### Kế hoạch triển khai

1. **Nguồn tính trên máy chủ:** rà `carry_in_tests`, `carry_out_tests`, `carryover_remaining_tests` và công thức phân bổ số test hiện có; thống nhất giá trị biểu thị phần thiếu còn lại sau các hoạt động hợp lệ hôm nay. Tính từ ledger/checkpoint đã xác minh; không nhận số nợ hay cờ hoàn thành do client gửi. Một hoạt động test chỉ được tính một lần theo công thức nhiệm vụ, không trừ lặp qua mỗi lần bật một app.
2. **Chặn trong `resume_campaign()`:** bổ sung kiểm tra phần thiếu cộng dồn, không chỉ kiểm tra `met` và chuỗi thiếu. Dùng kiểm tra chung cho mọi nhánh chủ app mở lại. Giữ luồng admin có quyền riêng theo thiết kế hiện tại; chủ app không được tự đi qua luồng đó.
3. **Không lách qua giới hạn app khả dụng:** kể cả khi `completed_tests >= effective_required_tests`, nếu phần thiếu cộng dồn còn lại lớn hơn 0 thì vẫn trả lỗi. Nếu chính sách cộng dồn đang tắt, vẫn kiểm tra phần thiếu cũ được giữ lại theo ledger.
4. **Phản hồi rõ ràng:** dự kiến dùng mã riêng như `OWNER_CARRYOVER_INCOMPLETE`, kèm số nhiệm vụ cộng dồn còn thiếu do máy chủ tính. Dữ liệu chưa sẵn sàng dùng `DAILY_TASK_PENDING`; không gộp hai trường hợp thành thông báo yêu cầu test một số lượng chưa xác định.
5. **Android:** hiển thị phần cộng dồn còn thiếu và hướng dẫn hoàn thành trước khi bật “Tiếp tục test”. Vô hiệu hóa thao tác khi đã biết còn thiếu/chưa sẵn sàng, đồng thời giữ kiểm tra bắt buộc phía máy chủ cho client cũ hoặc lời gọi trực tiếp. Làm mới nhiệm vụ sau khi gửi test thành công và sau khi máy chủ từ chối do dữ liệu thay đổi. Cập nhật VI/EN.
6. **Nhất quán và chống chạy đồng thời:** tính lại điều kiện trong cùng giao dịch bật lại với cơ chế khóa phù hợp; rà thứ tự khóa với finalizer và xử lý báo cáo. Hai yêu cầu bật lại đồng thời không được làm giảm nợ hai lần hoặc sử dụng số nợ đã hết hạn.

### Tiêu chí nghiệm thu bổ sung

| Tình huống | Kết quả bắt buộc |
| --- | --- |
| Đạt chỉ tiêu hôm nay, vẫn còn 1 nhiệm vụ cộng dồn | Chặn bật lại, trả số còn thiếu là 1 |
| Chuỗi thiếu đã về 0 nhưng phần cộng dồn còn dương | Vẫn chặn |
| Không còn phần cộng dồn nhưng chưa đạt chỉ tiêu hôm nay | Vẫn chặn |
| Đạt hôm nay và phần cộng dồn còn lại bằng 0 | Cho bật lại nếu các điều kiện app/slot/quyền đều hợp lệ |
| Hôm nay chỉ có ít app khả dụng nên đạt chỉ tiêu giới hạn nhưng còn nợ | Chặn; phần thiếu được giữ sang ngày sau |
| Tắt cộng dồn hoặc tăng ngưỡng tạm dừng trong khi còn nợ cũ | Không xóa nợ, không cho bật lại khi chưa trả đủ |
| Chủ có nhiều app, thử mở app khác hoặc app tự dừng/hết slot đã gia hạn | Cùng bị chặn nếu tài khoản còn nợ |
| Lịch sử/cộng dồn đang pending | Không bật lại; thông báo đang chờ dữ liệu |
| Test đủ qua nhiều ngày rồi bật lại lần lượt nhiều app | Cho mở khi hết nợ; không trừ lặp hoặc làm nợ âm |
| Client cũ, gọi RPC trực tiếp, sửa ngày đăng/đổi lý do dừng | Không vượt qua điều kiện máy chủ |
| Hai lần bật lại đồng thời, finalizer hoặc trạng thái test thay đổi đồng thời | Quyết định dựa trên dữ liệu nhất quán, không mở sai hoặc trừ nợ trùng |

Chạy các trường hợp này với cả 7 ngưỡng và với chính sách cộng dồn bật/tắt có phần thiếu cũ. Chuyển kiểm thử audit hiện tại từ “đạt hôm nay thì mở được” sang “đạt hôm nay **và hết phần thiếu cộng dồn** thì mở được”; giữ riêng trường hợp đã đạt nhưng còn nợ để kiểm tra bị từ chối.

## 8. Thứ tự triển khai và nghiệm thu

1. **Đọc production/staging:** xác nhận đã có 075 và 078, cấu hình hiện tại, hàm thực tế, cron, khoảng trống ledger, timestamp bất thường và lý do app đang dừng.
2. **Sửa P1 timestamp:** migration riêng, kiểm tra tương thích tạo/sửa app và các client hiện có.
3. **Sửa P1 chuỗi ngày:** migration riêng cho wrapper/finalizer, không thay công thức cộng dồn hay mức phạt khi không cần thiết.
4. **Bổ sung điều kiện hết nhiệm vụ cộng dồn:** triển khai mục 7 trên máy chủ và Android; kiểm tra mọi nhánh chủ app bật lại, gồm trường hợp đạt hôm nay nhưng còn nợ ngày trước.
5. **Kiểm thử trên chuỗi migration đầy đủ:** đổi các phép tái hiện lỗi thành kỳ vọng đã sửa; bổ sung kiểm thử yêu cầu cộng dồn vào lệnh kiểm thử tự động thích hợp.
6. **Chạy staging:** ngưỡng 1–7; đúng/trước 24 giờ; nhiều app; ngày đạt cắt chuỗi; khoảng trống khi cộng dồn bật/tắt và đổi chính sách; dữ liệu pending; miễn Admin/Moderator; `admin_resumed`; hết slot; hết 30 ngày; thao tác đồng thời và chạy lại; toàn bộ bảng nghiệm thu ở mục 7.
7. **Kiểm tra Android:** lưu/tải cấu hình, xung đột revision, đổi phiên, lỗi mạng; màn hình app bị dừng, thông báo đúng ngưỡng lịch sử, thông báo bật lại khi chưa đạt hoặc còn nhiệm vụ cộng dồn. Phần cấu hình hiện có đã có kiểm tra phạm vi và bảo vệ phản hồi phiên cũ; chưa thấy lỗi trực tiếp cần sửa ở phần này qua đọc mã.
8. **Triển khai và theo dõi:** giữ ngưỡng hiện tại trong lúc phát hành; theo dõi số app dừng và ledger; chỉ xử lý dữ liệu bị ảnh hưởng sau đối soát. Không tự tăng ngưỡng hay mở lại hàng loạt để che lỗi.

Điều kiện hoàn thành: cả hai lỗi không còn tái hiện, 7 ngưỡng hoạt động đúng với chuỗi ngày thật, không dừng oan khi có ngày đạt ở giữa, không bỏ qua nghĩa vụ bằng timestamp, **chủ app còn nhiệm vụ cộng dồn không thể bật “Tiếp tục test” cho bất kỳ app nào của mình**, không phạt trùng và có khả năng phát hiện/phục hồi pending lịch sử.

## 9. Kiểm tra bổ sung: admin lưu và áp dụng ngưỡng, ví dụ 3 ngày

### Kết luận kiểm tra ngày 05/11/2026

**Riêng luồng lưu cấu hình và sử dụng ngưỡng đã lưu đang hoạt động đúng trong các kiểm thử đã chạy.** Không phát hiện tình trạng UI gửi 3 nhưng máy chủ vẫn dùng mốc 7 trên chuỗi migration cục bộ hiện hành. Hai lỗi ở mục 4–5 vẫn tồn tại; yêu cầu chặn bật lại khi còn nhiệm vụ cộng dồn ở mục 7 chưa được triển khai.

### Đường áp dụng đã đối chiếu

1. Trong `AdminUsersScreen.kt`, nút cấu hình mở hộp thoại và tải ngưỡng từ máy chủ. Chọn 3 rồi bấm Lưu gọi `saveOwnerAutoPauseConfig(3)`.
2. `AdminUsersViewModel.kt` kiểm tra giá trị 1–7, trạng thái đang tải/lưu và phiên hiện tại, sau đó gọi `CampaignRepository.adminSetOwnerAutoPauseConfig()` với ngưỡng và revision đang có.
3. Repository gửi RPC `admin_set_owner_auto_pause_config(p_missed_days, p_expected_revision)`.
4. RPC kiểm tra admin chưa khóa, khóa dòng cấu hình, chống revision cũ, lưu giá trị mới và ghi nhật ký. Android chỉ cập nhật giá trị xác nhận khi máy chủ báo thành công.
5. `pause_inactive_owner_campaigns()` đọc ngưỡng từ cùng bảng cấu hình mỗi lần chạy. Không có mốc 7 cố định thay thế giá trị 3 đã lưu; 7 chỉ là giá trị dự phòng nếu dòng cấu hình không tồn tại.

**Thời điểm áp dụng:** bấm Lưu chưa tự quét và dừng app trong cùng thao tác. Ngưỡng mới được lưu ngay, được dùng ở lần đồng bộ vòng đời kế tiếp do cron hoặc client gọi. Việc chỉ chọn 3 mà chưa Lưu không thay đổi máy chủ. Không có nút bật/tắt riêng trong luồng cấu hình này.

### Kết quả kiểm thử bổ sung

Script `supabase/tests/audit-owner-auto-pause-flow.mjs` đã được cập nhật để cả 7 ngưỡng đều được thiết lập qua **RPC của admin có đăng nhập**, thay vì cập nhật trực tiếp bảng cấu hình. Vẫn dùng SQL thật và chốt ngày thật từ checkpoint, không thay hàm nghiệp vụ bằng stub.

| Tình huống | Kết quả thực tế |
| --- | --- |
| Admin lưu 3, đọc lại cấu hình | Đúng 3, có revision do máy chủ xác nhận |
| Ngưỡng 3, chuỗi thiếu 2 đã chốt | Hai app của chủ vẫn mở |
| Ngưỡng 3, chốt thêm ngày thứ ba | Cả hai app đủ điều kiện bị dừng |
| Đang dùng 7, chủ đã có chuỗi 3, admin đổi xuống 3 | Ngay sau Lưu app còn mở; lần đồng bộ kế tiếp dừng cả hai app, không xóa hay bắt đầu lại chuỗi cũ |
| Thông báo và nhật ký lúc đổi 7 xuống 3 | Nhật ký đúng admin, giá trị cũ 7/mới 3; mỗi app có thông báo `threshold_days=3` và nội dung 3 ngày |
| Đồng bộ lần hai | Không tạo thêm lần dừng/thông báo trùng |
| Đổi từ 3 lên 7 trước lần quét | App đang mở có chuỗi 3 không bị dừng |
| Đổi từ 3 lên 7 sau khi đã dừng | App tiếp tục bị dừng; thông báo lịch sử giữ ngưỡng 3; chưa đạt hôm nay vẫn không được tự bật lại |
| Giá trị sai, tài khoản không có quyền, revision cũ | Bộ kiểm thử cấu hình hiện có từ chối đúng |

Đã chạy lại thành công bộ kiểm thử cấu hình SQL và script audit. Đồng thời chạy mới hai nhóm kiểm thử Android:

- `AdminOwnerAutoPauseTest`: **4 kiểm thử**, không lỗi.
- `AdminOwnerAutoPauseViewModelTest`: **6 kiểm thử**, không lỗi.

Các kiểm thử Android kiểm tra đọc/ghi dữ liệu, tải cấu hình, gửi đúng ngưỡng 3 cùng revision, cập nhật sau khi lưu thành công, tải lại khi xung đột, thiếu revision, thiếu RPC và bỏ phản hồi từ phiên cũ. Chúng dùng phản hồi mạng mô phỏng; không thay thế thao tác chạm giao diện trên thiết bị kết nối production. Chưa kiểm thử trực quan hộp thoại trên thiết bị ở lượt này.

Bằng chứng chạy mới:

```text
audit/admin-pause-reason-popup-2026-11-05/owner-threshold-review-20261105-retry.log
audit/admin-pause-reason-popup-2026-11-05/test-result-snapshots/owner-threshold-review-20261105-retry-20261105-045029-740/
```

### Đối chiếu thêm yêu cầu nhiệm vụ cộng dồn ở ngưỡng 3

Đã tái hiện với 3 ngày thiếu được chốt khi trước đó có 3 app khả dụng. Hôm nay chỉ còn 1 app khả dụng; chủ test app đó nên đạt chỉ tiêu thực tế hôm nay, nhưng còn 2 nhiệm vụ cộng dồn. Sau khi admin đã lưu ngưỡng 3 và hệ thống dừng app, RPC chủ app vẫn cho bật lại:

```json
{"met":true,"carryover_remaining_tests":2,"result":"open"}
```

Kết quả này xác nhận **chưa đáp ứng yêu cầu mới ở mục 7**, dù lưu và áp dụng ngưỡng 3 hoạt động đúng. Khi triển khai phải giữ phép thử này và đổi kỳ vọng sang từ chối bật lại. Tiếp tục giữ điều kiện đạt hôm nay và hết toàn bộ phần thiếu cộng dồn, không dùng riêng `met=true` để cho mở.

Phạm vi xác minh vẫn là mã hiện tại, SQL cục bộ và kiểm thử Android tự động. Cần đối chiếu cấu hình, migration và cron trên production trước khi khẳng định phiên bản người dùng đang dùng có cùng hành vi.
