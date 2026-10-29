# Kế hoạch cộng dồn app test còn thiếu sang ngày hôm sau

Ngày lập: 29/10/2026. Trạng thái: **kế hoạch triển khai, chưa sửa logic ứng dụng hoặc database**.

## 1. Yêu cầu và phạm vi

- Chủ app chưa test đủ chỉ tiêu ngày hôm trước: phần thiếu chuyển vào nhiệm vụ ngày hôm sau. Phần thiếu tiếp tục chuyển qua nhiều ngày cho đến khi hoàn thành.
- **Tester không đăng app giữ nguyên quy tắc hiện tại**: đăng nhập trong ngày hoặc có lượt test hợp lệ thì hoàn thành; không bị bắt test thêm chỉ vì có cơ chế cộng dồn.
- Giữ cách xác định app thuộc diện tính nhiệm vụ, tỷ lệ cấu hình, một campaign chỉ tính một lần/ngày, report pending/approved được tính, rejected không được tính, múi giờ Việt Nam.
- Giữ chính sách phạt Credit và tự tạm dừng sau 7 ngày thiếu liên tiếp; bổ sung phần thiếu chuyển sang khi tính chỉ tiêu. Không đổi phí, thưởng report, thưởng cập nhật phiên bản hoặc Nhiệm vụ App có thưởng.
- Bắt đầu tích lũy từ một ngày có hiệu lực mới; không truy thu phần thiếu trước ngày áp dụng.

Các quy tắc chi tiết ở dưới là phương án đề xuất để triển khai, đặc biệt khi thiếu app khả dụng hoặc chủ app chuyển thành thành viên.

## 2. Kết quả rà soát mã nguồn

| Thành phần | Vị trí | Hiện trạng / việc cần làm |
| --- | --- | --- |
| Công thức chỉ tiêu | `supabase/migrations/042_configurable_daily_task_ratio.sql:422` | Chỉ có `tỷ lệ × K`, sau đó giới hạn bởi A. Bổ sung phần thiếu chuyển vào. |
| Đếm test trong ngày | Cùng file, dòng 379–410 | Đếm distinct campaign theo ngày Việt Nam. Giữ nguyên, không lấy lượt test hôm qua làm tiến độ hôm nay. |
| Chốt ngày và phạt | Cùng file, dòng 459–689 | Có row finalized và khóa chống chạy trùng; bổ sung sổ phần thiếu vào cùng transaction. |
| Mốc chấm ngày | `supabase/migrations/036_daily_task_quota.sql:863` | Wrapper chấm ngày hôm qua; cron được khai báo ở migration 030, chạy 00:05 VN. |
| Lưu ngày | `supabase/migrations/036_daily_task_quota.sql:38` | `user_daily_tasks` có khóa `(user_id, task_date)`, chưa có phần thiếu chuyển vào/chuyển ra. |
| Nguồn dữ liệu UI | Migration 036: `get_my_daily_task_status`, `get_my_dashboard` | Cùng lấy trạng thái từ `get_daily_task_status`; tiếp tục dùng một nguồn tính. |
| Reset chuỗi / mở lại app | Migration 036: `refresh_daily_completion`, `pause_inactive_owner_campaigns`, `resume_campaign` | Phải nhận đúng chỉ tiêu có cộng dồn và trạng thái đã sẵn sàng. |
| Model / thẻ nhiệm vụ | `Models.kt:886`, `ui/components/DailyTaskCard.kt:77` | Nhận tổng chỉ tiêu từ server, cần thêm chi tiết chỉ tiêu cơ bản và phần chuyển sang. |
| Cache / nhắc việc | `SessionCacheManager.kt`, `HomeViewModel.kt`, `CampaignRepository.kt`, `work/NotificationHelper.kt` | Có xử lý đổi ngày; cần thêm xử lý kết quả đang chờ chốt hôm qua. |
| Kiểm thử | `supabase/tests/run-daily-task-ratio.mjs`, `daily_task_ratio.sql` | Có fixture và PGlite để kiểm thử nghiệp vụ trước khi đụng live. |

Đây là kết quả kiểm tra checkout hiện tại tới migration 051, không phải xác nhận Supabase live đã áp dụng đủ migration. Workspace đang có các thay đổi khác; khi triển khai cần giữ nguyên những thay đổi đó.

## 3. Quy tắc nghiệp vụ đề xuất

### 3.1. Khi có đủ app để test

```text
Chỉ tiêu cơ bản hôm nay = tỷ lệ hôm nay × số app thuộc diện tính nhiệm vụ hôm nay
Tổng nghĩa vụ hôm nay = chỉ tiêu cơ bản hôm nay + phần thiếu chuyển sang
Phần thiếu chuyển ngày sau = max(0, tổng nghĩa vụ hôm nay − số app đã test hôm nay)
```

Ví dụ cùng một chủ app, chỉ tiêu cơ bản mỗi ngày là 6, nguồn app đủ:

| Ngày | Cơ bản | Chuyển vào | Phải test | Đã test | Chuyển ngày sau |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ngày 1 | 6 | 0 | 6 | 4 | 2 |
| Ngày 2 | 6 | 2 | 8 | 5 | 3 |
| Ngày 3 | 6 | 3 | 9 | 9 | 0 |
| Ngày 4 | 6 | 0 | 6 | 6 | 0 |

Phần thiếu là **số lượng app phải test**, không phải danh sách campaign bắt buộc quay lại. Campaign đã test hôm qua có thể được tính lại hôm nay nếu đáp ứng quy tắc test hiện tại. Không cộng lượt test vượt chỉ tiêu thành tín dụng cho các ngày tương lai.

Không đặt trần hoặc tự hết hạn phần thiếu trong phạm vi yêu cầu này. Dùng kiểu số đủ rộng và kiểm tra biên; không âm thầm cắt số khi vượt giới hạn kỹ thuật.

### 3.2. Khi không đủ app khả dụng

Cần giữ việc giới hạn chỉ tiêu theo nguồn app hiện có. Không tạo thêm phần thiếu mới chỉ vì hệ thống không có đủ app để người dùng test; phần thiếu cũ chưa trả hết vẫn được giữ.

Ký hiệu cho ngày d:

```text
B = tỷ lệ × số app thuộc diện tính nhiệm vụ
C = phần thiếu chuyển vào từ ngày trước
A = số app khác khả dụng theo helper hiện hành
D = số campaign khác nhau đã test hợp lệ trong ngày

R = B + C                         -- tổng nghĩa vụ trước giới hạn nguồn app
T = min(R, A)                     -- chỉ tiêu thực tế phải hoàn thành hôm nay
Q = max(C, T)                     -- nghĩa vụ giữ lại, gồm phần cũ chưa thể làm hết
carry_out = max(0, Q − D)         -- chuyển sang ngày sau
met = D >= T                     -- chỉ xét sau khi dữ liệu chuyển vào đã sẵn sàng
```

Diễn giải: ưu tiên hoàn thành phần thiếu cũ; chỉ đưa nghĩa vụ cơ bản mới vào trong khả năng nguồn app cho phép. Chỉ tiêu cơ bản bị miễn do thiếu nguồn app không trở thành phần thiếu mới.

| B | C | A | T | D | Chuyển ngày sau | Ý nghĩa |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 6 | 2 | 20 | 8 | 5 | 3 | Trường hợp bình thường. |
| 6 | 2 | 5 | 5 | 5 | 0 | Hoàn thành 5 app khả dụng, không sinh thêm phần thiếu bất khả thi. |
| 6 | 8 | 5 | 5 | 5 | 3 | Trả được 5 trong 8 phần thiếu cũ; giữ 3 còn lại. |
| 6 | 8 | 0 | 0 | 0 | 8 | Miễn chỉ tiêu hôm nay vì không có app, giữ phần thiếu cũ. |
| 6 | 2 | 5 | 5 | 3 | 2 | Thiếu 2 trong chỉ tiêu thực tế hôm nay. |

Khi `T = 0`, không phạt và không tăng chuỗi thiếu vì nguồn app bằng 0. UI phải giải thích việc miễn hôm nay và phần thiếu cũ còn giữ, tránh tạo cảm giác phần thiếu đã được xóa.

`D` có thể lớn hơn `A` nếu app đã được test hợp lệ rồi chuyển trạng thái trong ngày. Giữ cách đếm hiện tại: lượt hợp lệ vẫn có thể giảm phần thiếu cũ, tối đa về 0. Không giảm D xuống A một cách máy móc.

### 3.3. Tester không có app thuộc diện tính nhiệm vụ

- `K = 0`: tiếp tục `mode = member`, chỉ cần đăng nhập hoặc có lượt test hợp lệ như code hiện tại. Đăng app mới trong ngày vẫn giữ quy tắc bắt đầu tính từ ngày mai.
- Thành viên chưa từng có phần thiếu luôn có số chuyển vào/chuyển ra bằng 0; không phát sinh nghĩa vụ test mới.
- Nếu trước đây là chủ app và còn phần thiếu, đề xuất **giữ phần thiếu cũ ở trạng thái tạm giữ** trong các ngày member; không cộng vào điều kiện hoàn thành của member và không tăng thêm vì chưa test đủ app.
- Khi quay lại owner, dùng tiếp số còn giữ. Có thể dùng lượt test tự nguyện hợp lệ trong các ngày member để giảm phần thiếu cũ: `carry_out = max(0, carry_in − D)`; việc đăng nhập vẫn đủ để đạt nhiệm vụ member, kể cả phần thiếu cũ chưa về 0.
- Cách này giữ quy tắc thành viên và tránh xóa toàn bộ phần thiếu chỉ bằng việc tạm dừng/xóa app. Không yêu cầu thành viên trả hết phần thiếu để được coi là hoàn thành.
- Admin/Moderator tiếp tục miễn; không phát sinh phần thiếu mới trong thời gian miễn. Nếu tài khoản đã có phần thiếu trước khi được miễn, giữ lại và cho giảm bằng test tự nguyện như trên, không ảnh hưởng trạng thái miễn.

### 3.4. Phạt, chuỗi ngày và mở lại app

- Phần thiếu chuyển tiếp **không thay thế** mức phạt Credit hiện hành. Ngày không đạt vẫn chịu phạt; số app chưa làm tiếp tục sang ngày sau.
- Owner: giữ mức 1% / 2% / 5% / 10% theo chuỗi, nhân `max(0, T − D) / T`, tối thiểu 1 Credit khi có số dư và có thiếu, không vượt quá số dư. `T = 0` không chia và không phạt.
- Member: giữ nguyên nhánh phạt do không đăng nhập, không dùng phần thiếu cũ để tính tỷ lệ phạt.
- Đạt chỉ tiêu thực tế hôm nay thì reset chuỗi theo hành vi hiện tại; nếu nguồn app thiếu, có thể đạt hôm nay nhưng vẫn còn phần thiếu cũ được giữ.
- Auto-pause/resume tiếp tục dùng cùng trạng thái `met` của hôm nay. Không bắt hoàn thành số app vượt nguồn app khả dụng mới cho mở lại.
- Chốt Credit một lần cho mỗi ngày. Không ghi thêm giao dịch phạt riêng chỉ vì ghi số chuyển tiếp.

## 4. Thiết kế dữ liệu và RPC

### 4.1. Mở rộng sổ nhiệm vụ theo ngày

Thêm các cột đề xuất vào `user_daily_tasks`:

| Cột | Ý nghĩa |
| --- | --- |
| `carryover_version` | Phiên bản công thức; NULL/0 cho dữ liệu cũ. |
| `base_required_tests` | B của ngày đang chấm. |
| `carry_in_tests` | Phần thiếu đã chuyển vào C. |
| `carry_out_tests` | Phần thiếu sau khi chốt ngày. |
| `carry_source_date` | Ngày liền trước dùng làm nguồn; giúp phát hiện khoảng trống. |
| `carryover_effective_from` hoặc tham chiếu chính sách | Truy được mốc bắt đầu áp dụng. |

Giữ `required_tests = B + C`, `effective_required_tests = T`, `completed_tests = D`. Các giá trị mới không âm; chọn kiểu và phép tính tránh tràn khi cộng dồn lâu dài. Row cũ không bị biến thành kết quả mới chỉ vì thêm DEFAULT; nhận diện bằng phiên bản/ngày hiệu lực.

Row đã finalized là nguồn chính thức, không dùng biến cache Android hoặc riêng `profiles` làm sổ phần thiếu. Có thể thêm bảng trạng thái theo user để tăng tốc sau khi đo tải, nhưng phải cập nhật nguyên tử cùng sổ ngày và có thể đối chiếu lại từ sổ.

Không xóa sổ ngày đang được dùng làm nguồn chuyển tiếp khi dọn dữ liệu. Nếu sau này có retention cho bảng này, phải giữ checkpoint độc lập đã đối chiếu trước.

### 4.2. Mở rộng JSON có tương thích

Giữ chữ ký hai RPC hiện có. Bổ sung các trường:

```text
base_required_tests
carry_in_tests
carryover_remaining_tests          -- dự kiến còn lại theo tiến độ hôm nay
carryover_ready                    -- đã có nguồn ngày trước hợp lệ hay chưa
carryover_effective_from
```

`carry_out_tests` trong database chỉ là số chính thức của ngày đã chốt; trường UI dự kiến phải có tên/ý nghĩa riêng. Model Kotlin thêm giá trị mặc định để đọc được server cũ; server mới giữ các key cũ. Kiểm tra cấu hình JSON bỏ qua key lạ trước khi tuyên bố tương thích với APK cũ.

Helper `get_daily_task_status` hiện là `STABLE`: giữ vai trò đọc/tính, không nhét ghi dữ liệu hoặc chạy phạt vào helper này. Hàm chốt ngày là đường ghi duy nhất; RPC công khai lấy user từ `auth.uid()`, không nhận user tùy ý để sửa phần thiếu.

## 5. Chuyển ngày và tính nhất quán

### 5.1. Xử lý khoảng 00:00–00:05

Giữ cron phạt 00:05 theo giờ Việt Nam. Chọn phương án an toàn cho bản đầu: **chỉ tiêu có cộng dồn được xác nhận khi ngày hôm qua đã chốt**.

1. Sau 00:00, nếu row hôm qua chưa finalized thì owner trả `carryover_ready = false`. Không giả định phần thiếu bằng 0 và không lấy số của một ngày xa hơn làm hôm qua.
2. UI hiển thị “Đang cập nhật chỉ tiêu ngày mới”, vẫn cho test/nộp report. Các lượt sau 00:00 tính cho hôm nay như cũ.
3. Khi chưa ready, không kết luận owner đã đạt, không reset chuỗi, không dùng kết quả tạm để auto-pause hay cho resume theo điều kiện hoàn thành hôm nay. Các điều kiện độc lập như Admin pause vẫn giữ nguyên.
4. Cron chốt hôm qua bằng công thức chung, ghi phần thiếu, phạt, Credit log và trạng thái trong cùng transaction.
5. Sau khi commit, tải lại RPC: lấy `carry_in` chính thức và đếm mọi lượt đã test từ 00:00; không bắt người dùng làm lại các lượt đó.
6. UI đang mở cần tải lại sau mốc 00:05 và khi quay lại foreground. Trạng thái chưa ready dùng thời gian cache ngắn và retry có giới hạn/backoff; worker nhắc việc không thông báo sai chỉ tiêu trong thời gian này.

Member không bị buộc chờ để hoàn thành nhiệm vụ đăng nhập. Nếu có phần thiếu cũ cần theo dõi thì chỉ trì hoãn dữ liệu phần thiếu, không trì hoãn trạng thái member đã đăng nhập.

### 5.2. Chốt đúng thứ tự, tránh ghi lặp

- Khi chốt d, nguồn phải là ngày d−1 đã chốt hoặc checkpoint ngày bắt đầu chính sách. Chốt các ngày theo thứ tự tăng dần.
- Tạo row/checkpoint cho cả ngày đăng ký được miễn và ngày Admin/Moderator được miễn. Finalizer hiện bỏ qua các vai trò này; khi bổ sung sổ phải có nhánh ghi phần thiếu được giữ/giảm với penalty bằng 0, không để mất nguồn khi tài khoản đổi vai trò về owner. Checkpoint ngày đăng ký bắt đầu phần thiếu bằng 0.
- Giữ khóa chống chấm phạt song song và unique `(user_id, task_date)`; không cộng bằng phép `carry += thiếu` khi retry. Tính giá trị tuyệt đối từ nguồn ngày trước rồi ghi một lần.
- Đã finalized thì không ghi đè, không trừ Credit lần nữa và không thay phần thiếu vì report cũ đổi trạng thái sau đó. Muốn điều chỉnh lịch sử phải là nghiệp vụ điều chỉnh có audit riêng.
- Không để một giao dịch vừa ghi phần thiếu thành công vừa thất bại ở Credit log. Chốt ngày là giao dịch nguyên tử.
- Rà lại thứ tự khóa của finalizer, refresh và resume để tránh deadlock với khóa campaign/profile hiện có.

### 5.3. Cron trễ, thiếu ngày hoặc thiếu dữ liệu

- Thêm kiểm tra cron chốt thành công, retry và cảnh báo khi hôm qua chưa chốt sau mốc dự kiến. Không coi việc đã tạo cron là bằng chứng cron đã chạy.
- Wrapper hiện chỉ chấm hôm qua; cần bổ sung điều phối tìm khoảng trống sau ngày hiệu lực, không bỏ qua ngày vắng vì người dùng không đăng nhập.
- Không suy ra kết quả lịch sử chỉ từ trạng thái campaign hiện tại: K/A có thể đã thay đổi hoặc campaign/report đã bị dọn. Ngày thiếu dữ liệu nguồn phải có checkpoint/snapshot đủ để chấm chính xác.
- Trong triển khai phải có snapshot hoặc dữ liệu lịch sử đủ cho phục hồi; nếu không có bằng chứng đầy đủ thì giữ trạng thái cần đối soát, thông báo vận hành và không tự phát sinh phạt/phần thiếu bằng số đoán.
- Đo chi phí chốt bù, chạy theo lô có giới hạn và tiếp tục qua nhiều lượt nếu cần. Không quét đệ quy toàn bộ lịch sử trên mỗi request dashboard.

## 6. Thay đổi Android

1. `Models.kt`: nhận các trường mới, tách trạng thái “chưa sẵn sàng” khỏi “được miễn vì không có app”. Default cho server cũ là sẵn sàng với phần chuyển sang 0; nếu server mới trả false thì phải giữ false.
2. `DailyTaskCard.kt`: hiển thị `Đã test 3/8 app`; dòng giải thích `6 app hôm nay + 2 app còn thiếu từ trước`. Khi thiếu nguồn app, hiển thị chỉ tiêu thực tế và số còn giữ thay vì phép cộng gây hiểu nhầm.
3. Cập nhật hướng dẫn phạt ở `values`, `values-en`, `values-vi`: làm rõ phần thiếu tiếp tục sang ngày sau và phạt Credit vẫn áp dụng. Nội dung member giữ quy tắc đăng nhập.
4. `HomeViewModel`, `SessionCacheManager`, `CampaignRepository`: không tái sử dụng nhiệm vụ ngày cũ; phân biệt ready/pending; làm mới sau report, reject/approve có ảnh hưởng ngày hiện tại, đổi trạng thái app và khi chốt ngày xong.
5. `NotificationHelper` và worker: dùng tổng T server trả; chỉ nhắc hoàn thành khi chỉ tiêu đã sẵn sàng, tránh báo đã xong dựa trên B chưa cộng phần thiếu.
6. Kiểm tra các màn hình đang dùng `dailyTask.met`, nhãn miễn và cảnh báo chuỗi để không có đường riêng bỏ qua `carryover_ready`.

APK cũ có thể hiển thị tổng chỉ tiêu qua field cũ, nhưng chưa có cách giải thích chi tiết hoặc xử lý trạng thái pending. Phải kiểm thử APK cũ và chốt phương án phiên bản tối thiểu trước khi bật chính sách; không mặc định backend mới là đủ.

## 7. Các gói triển khai

| Gói | Công việc | Điều kiện hoàn tất |
| --- | --- | --- |
| A — Preflight | Đọc function/schema/quyền/cron live, đối chiếu migration 036/042 và thay đổi riêng; xác nhận snapshot/history phục hồi, cấu hình JSON, bản APK. | Có baseline và phương án xử lý khác biệt thực tế. |
| B — Database | Migration mới, dự kiến `052_daily_task_carryover.sql` nếu số còn trống; cột sổ ngày, chính sách có ngày hiệu lực, công thức chung, finalizer, ready state, quyền và điều phối ngày thiếu. | Chạy được trên DB thử nghiệm; schema gộp khớp migration. |
| C — Android | Model, thẻ nhiệm vụ, hướng dẫn ba ngôn ngữ, cache/đổi ngày, worker, pause/resume. | Build và kiểm thử các đường hiển thị/hoàn thành. |
| D — Kiểm chứng | Bộ test carryover riêng và hồi quy tỷ lệ, báo cáo, Credit, cache, dashboard, pause/resume. | Kết quả đạt các trường hợp mục 8, không có sai lệch chưa giải thích. |
| E — Rollout | Triển khai code trước; chọn ngày áp dụng tương lai theo giờ VN; ngày đầu carry-in bằng 0. | Theo dõi được lần chốt đầu và lần chuyển tiếp đầu, đối chiếu sổ ngày với UI. |

Đồng bộ `supabase/full_schema_singapore.sql` và cập nhật runner/package test cần thiết. Không sửa lại migration 036/042 đã phát hành để giả lập tính năng mới.

Ngày bắt đầu E: `carry_in(E) = 0`; tối E phát sinh phần thiếu; sau khi E được chốt, E+1 nhận phần đó. Lịch cũ giữ nguyên Credit và kết quả finalized. Chính sách ngày hiệu lực không sửa hồi tố.

## 8. Ma trận kiểm thử bắt buộc

| Nhóm | Trường hợp / kết quả mong đợi |
| --- | --- |
| Cộng dồn cơ bản | 6 cần/4 làm → ngày sau 6+2=8; ngày sau làm 5 → chuyển 3; đủ 9 → phần thiếu 0. |
| Vượt chỉ tiêu | Làm hơn yêu cầu không tạo số âm hoặc tín dụng cho ngày sau. |
| Nhiều ngày không hoạt động | Ngày không đăng nhập vẫn được chốt theo thứ tự; phần thiếu không chỉ tính từ lần mở app gần nhất. |
| Nguồn app thiếu | Bao phủ toàn bộ ví dụ mục 3.2; không xóa phần cũ và không sinh nghĩa vụ mới bất khả thi. |
| Nguồn app đổi trong ngày | A tăng/giảm, D có thể lớn hơn A; chốt dùng quy tắc nhất quán, không tạo phần thiếu âm. |
| Thành viên thuần | Không đăng app, đăng nhập → đạt; không đăng nhập → phạt như cũ; không phát sinh chỉ tiêu test. |
| Chuyển owner/member | Phần cũ tạm giữ, login vẫn đạt ở member; test tự nguyện giảm phần cũ; quay lại owner dùng phần còn lại. |
| App mới / tài khoản mới | App mới chưa tăng B trong ngày tạo; không ghi phần thiếu ở ngày tài khoản được miễn. |
| Vai trò miễn | Admin/Moderator vẫn đạt và không phát sinh phần thiếu mới. |
| Tỷ lệ / K thay đổi | B lấy tỷ lệ và K đúng ngày; C là số lượng cố định, không nhân lại theo tỷ lệ mới. |
| Biên nửa đêm | 23:59:59 và 00:00 thuộc hai ngày; 00:00–00:05 owner pending; report lúc pending vẫn được tính sau khi ready. |
| Chuỗi / resume | Không reset khi chưa ready; sau ready đủ T thì reset, auto-pause/resume dùng cùng kết quả. |
| Chạy trùng / song song | Cron retry, hai phiên chốt và request đồng thời không nhân đôi phần thiếu, Credit log hoặc penalty. |
| Chốt ngày lỗi | Lỗi giữa transaction không để lại số chuyển sang một phần; retry ra đúng kết quả. |
| Khoảng trống lịch sử | Không lấy ngày xa hơn làm hôm qua; không đủ snapshot thì báo cần đối soát, không phạt bằng dữ liệu suy đoán. |
| Report và check-in | Một campaign/ngày đếm một lần; pending/approved tính, rejected không tính; duyệt hôm nay report hôm qua không đếm lại hôm nay. |
| Report sau chốt | Thay đổi trạng thái sau finalized không âm thầm viết lại phần thiếu đã chuyển hoặc phạt lịch sử. |
| Phạt | Đối chiếu số cụ thể của cả bốn mức %, tỷ lệ thiếu theo T, làm tròn, số dư 0 và tối thiểu 1. |
| Tương thích / cache | Server cũ + app mới; server mới + app cũ theo phương án phiên bản; đổi user, đổi ngày và pending→ready không giữ dữ liệu sai. |
| Hiệu lực / rollback | Ngày đầu C=0; không truy thu; chuyển sang chính sách tương lai không ghi lại ngày finalized. |
| Quyền / tải | Client không sửa được C hoặc gọi finalizer; chốt nhiều user/ngày không timeout hoặc tăng tải dashboard không kiểm soát. |

Đề xuất thêm `supabase/tests/run-daily-task-carryover.mjs` và SQL fixture riêng. Test nghiệp vụ cục bộ bằng PGlite; test scheduler, quyền thực tế và concurrency trên PostgreSQL/Supabase staging vì môi trường nhúng không xác minh đầy đủ các phần đó.

## 9. Theo dõi và hoàn tác

- Theo dõi số ngày chưa chốt, số user pending kéo dài, phần thiếu chuyển vào/ra, Credit phạt, số lần retry và lỗi truy nguồn ngày trước. Không log token hoặc nội dung report riêng tư.
- Đối chiếu ngày liền nhau: `carry_in(d+1) = carry_out(d)` sau ngày bắt đầu, bao gồm ngày member giữ phần thiếu.
- Trước rollout lưu định nghĩa function, quyền, chính sách và baseline kiểm thử. Chạy thử dữ liệu giả trên staging, không chạy phạt thử trên user thật.
- Nếu cần dừng tính năng, áp dụng lịch tắt từ một ngày tương lai và giữ nguyên audit. Phần thiếu còn lại được đóng băng trong thời gian tắt, không xóa và không âm thầm thu lại.
- Hoàn tác khẩn cấp phải có phương án backend tương thích các field JSON đã phát hành; không chỉ khôi phục helper cũ rồi tiếp tục cron, vì có thể làm đứt sổ hoặc chấm lại ngày. Không tự hoàn tiền/phạt bù lịch sử nếu chưa có nghiệp vụ điều chỉnh cụ thể.

## 10. Tiêu chí nghiệm thu

1. Hôm trước thiếu 2, hôm nay cơ bản 6 và đủ nguồn app: server, thẻ nhiệm vụ, nhắc việc và chấm phạt đều dùng chỉ tiêu 8.
2. Thiếu tiếp thì chuyển tiếp đúng; hoàn thành hết thì về 0; không tính trùng khi retry.
3. Tester không đăng app hoàn thành bằng đăng nhập như hiện tại, kể cả tài khoản từng có phần thiếu lúc còn là owner.
4. Thiếu nguồn app không tạo nghĩa vụ mới bất khả thi và không tự xóa phần thiếu cũ.
5. Biên ngày, cron chậm, trạng thái chưa ready, reset chuỗi và resume không tạo đường hoàn thành sai.
6. Có bằng chứng kiểm thử, đối chiếu dữ liệu chốt ngày và tài liệu vận hành trước khi áp dụng live.
