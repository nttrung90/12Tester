# Kiểm tra độc lập và kế hoạch sửa cộng dồn nhiệm vụ + Đẩy app

Ngày kiểm tra: **29/10/2026**, múi giờ Việt Nam. Dự án: `F:\AI\12Tester`.

## 1. Kết luận nghiệm thu

**Chưa đáp ứng đầy đủ hai kế hoạch.** Luồng thông thường đã có và các kiểm thử hiện có đều qua, nhưng còn lỗi ở chốt ngày thiếu nguồn, khôi phục giao dịch trả phí, đổi tài khoản và vòng đời dữ liệu.

Phạm vi lần này: đọc hai kế hoạch, đánh giá kết hợp, báo cáo bàn giao; rà SQL/Kotlin; chạy lại kiểm thử và thêm ca đối chứng độc lập trong `audit/`. **Chưa sửa code sản phẩm, migration hoặc dữ liệu live.**

Không lấy tuyên bố “PASS 100% local” trong báo cáo bàn giao làm kết luận đạt toàn bộ kế hoạch. Các suite đang chạy chưa phủ các lỗi bên dưới; PGlite một kết nối cũng không chứng minh concurrency trên PostgreSQL thật.

## 2. Bằng chứng kiểm tra thực tế

| Hạng mục | Kết quả |
| --- | --- |
| `npm.cmd test --prefix supabase/tests` | Exit 0, cả 10 suite hiện có chạy xong, gồm 19 nhóm Đẩy app và 10 nhóm cộng dồn. |
| `:app:testDebugUnitTest --rerun --offline --console=plain` | Task test thực sự chạy lại; **33 suite, 240 test, 0 failure, 0 error**. |
| `:app:assembleDebug` | BUILD SUCCESSFUL. |
| SQL đối chứng, nạp toàn bộ schema cuối tới 053 | **10 tình huống sai đã tái hiện**. Đây là bằng chứng lỗi, không phải 10 tiêu chí sản phẩm đạt. |
| Android đối chứng qua Robolectric/MockEngine | 3 ca chạy xong, tái hiện ghi nhầm cache khi đổi tài khoản, không xác minh request cũ khi số dư thấp, và chấp nhận metadata đẩy thiếu expiry. |
| Migration và schema gộp | SQL 052/053 khớp khi bỏ comment/dòng trắng; không khớp từng byte vì comment khác. Chưa thấy khác biệt SQL trong hai section này. |
| PostgreSQL nhiều kết nối / Supabase live | Chưa kiểm tra; không có CLI `psql`/`docker` sẵn trong PATH tại lần rà soát. Không kết luận đã đạt concurrency/live. |
| Kiểm tra giao diện bằng thiết bị/emulator | Chưa thực hiện trong lần này; các nhận xét giao diện dựa trên code/resource. |

Tệp bằng chứng:

- `audit/two-plans-review-2026-10-29/probes.mjs`: các ca SQL độc lập, chạy trong database nhúng tạm, không nối mạng tới Supabase.
- `audit/two-plans-review-2026-10-29/results.json`: dữ liệu tái hiện từng lỗi SQL.
- `audit/two-plans-review-2026-10-29/tests/com/ntt/crosstest/data/TwoPlansReviewProbeTest.kt`: các ca Android độc lập.
- `audit/two-plans-review-2026-10-29/android-results/`: XML kết quả 3 ca Android.
- `audit/two-plans-review-2026-10-29/verification.json`: tổng hợp kiểm chứng.
- `audit/two-plans-review-2026-10-29/reviewed-source-sha256.json`: hash các source trọng tâm đã kiểm tra.

Các ca đối chứng cố ý assert **hành vi sai đang xảy ra** để xác nhận nguyên nhân. Khi sửa xong phải chuyển thành regression test assert hành vi đúng; không dùng việc các probe xanh làm bằng chứng sản phẩm đã sửa.

## 3. Phần đã làm đúng

- Có migration riêng: 052 Đẩy app, 053 cộng dồn; không trùng số.
- Công thức thông thường B/C/A/D/T/Q, giới hạn nguồn app và không tạo số thiếu âm đã được triển khai.
- Member không có app vẫn hoàn thành bằng đăng nhập hoặc test hợp lệ, không bị áp chỉ tiêu owner.
- Đẩy app có hộp xác nhận, giá server 100, kiểm tra chủ/khóa tài khoản/app/slot, receipt chống thu trùng khi cùng request ID và campaign còn tồn tại.
- Comparator tách nhóm đẩy lên trước, sắp `push_order DESC`; nhóm thường giữ thuật toán cũ; ngày đẩy dùng giờ Việt Nam.
- Mua đẩy không tự xóa nghĩa vụ, tăng D hoặc reset chuỗi.
- Mua và resume đều khóa campaign trước profile trên các đường đã đọc. Điều này xử lý một nguy cơ đảo thứ tự được nêu trước, nhưng vẫn cần concurrency test thật và rà toàn bộ trigger/RPC liên quan.
- Có trường JSON/default model, thẻ pending và worker không nhắc chỉ tiêu khi chưa ready.

## 4. Các lỗi còn lại và cách sửa

### R01 — P1: Chốt và phạt một ngày khi phần chuyển vào chưa sẵn sàng

**Vị trí:** `supabase/migrations/053_daily_task_carryover.sql:460–475, 538–589`; helper dòng 275–295; wrapper dòng 690–714.

Finalizer lấy trạng thái từ helper nhưng không kiểm tra `carryover_ready`. Khi ngày trước chưa chốt, helper trả `carry_in=0`, phần còn lại bằng 0, `met=false`; finalizer vẫn ghi row finalized và trừ Credit. Row đã finalized về sau bị bỏ qua nên không tự khôi phục phần thiếu đúng.

**Tái hiện C01:** owner chưa có row nguồn hôm trước; trạng thái `ready=false`; chốt ngày vẫn ghi `carry_out=0`, `met=false` và **phạt 10 Credit** từ số dư 1.000. Không phải chỉ lỗi hiển thị.

Nhánh member/admin còn fallback tới row đã chốt xa hơn khi thiếu ngày liền trước. Cho phép UI member hoàn thành đăng nhập là đúng, nhưng lấy nguồn xa hơn để ghi ledger sẽ phá chuỗi ngày nếu các ngày giữa chưa được giải quyết.

**Kế hoạch sửa:**

1. Tách “member đã đạt nhờ đăng nhập” khỏi “nguồn chuyển tiếp đủ để chốt ledger”.
2. Finalizer từ chối/hoãn riêng user-day thiếu nguồn; không ghi finalized, không phạt, không ghi 0 thay C và không ảnh hưởng user-day khác đã đủ nguồn.
3. Xác minh ngày nguồn, phiên bản, checkpoint, ngày hiệu lực; không chỉ thấy `finalized_at != null` là coi mọi row mặc định/legacy hợp lệ.
4. Điều phối chốt tuần tự từ checkpoint hợp lệ. Cửa sổ 30 ngày hiện tại không được làm mất nguồn trước cửa sổ rồi tiếp tục chốt sai.
5. Giới hạn ngày được chốt ở các API vận hành để tránh vô tình đóng hôm nay/ngày tương lai; hỗ trợ test bằng cơ chế riêng, không để API production tùy ý chốt tương lai.

**Nghiệm thu:** C01 không trừ tiền/không đóng ngày khi pending; bổ sung đúng nguồn rồi chạy lại cho kết quả đúng, chạy trùng không nhân đôi.

### R02 — P1: Chốt bù lịch sử bằng trạng thái campaign hiện tại, chưa có snapshot phục hồi

**Vị trí:** migration 053 dòng 191–239, 460, 691–714.

Wrapper mới tự chốt bù, nhưng K/A và report hợp lệ được đọc từ bảng đang tồn tại lúc chạy; không có snapshot/journal hoặc bước kiểm tra bằng chứng lịch sử. Việc lấy `p_date` trong điều kiện ngày tạo không tái dựng được status/role/report của ngày cũ.

**Tái hiện C05:** cùng ngày quá khứ, owner đang thiếu nhiệm vụ; chỉ chuyển campaign sang completed ở hiện tại, trạng thái ngày cũ đổi từ `owner, met=false` thành `member, met=true`. Chạy finalizer sau đó ghi **member đã đạt, phạt 0**, dù ngày cần chấm trước đó là owner thiếu nhiệm vụ.

Helper trước đây đã có giới hạn lịch sử tương tự. Việc thêm chốt bù nhiều ngày mà không bổ sung nguồn lịch sử làm rủi ro này trở thành phần trực tiếp của tính năng mới và không đạt mục 5.3 của kế hoạch.

**Kế hoạch sửa:**

- Thiết kế snapshot/checkpoint hoặc journal đủ cho K/A/D, trạng thái miễn và login của ngày cần phục hồi; xác định thời điểm lấy và cách cập nhật từ các sự kiện trong ngày.
- Chốt ngày thông thường và chốt bù dùng cùng nguồn đã được xác nhận; không dùng trạng thái hiện tại thay lịch sử đã mất.
- Không đủ snapshot thì đánh dấu cần đối soát và giữ ngày chưa chốt, không đoán chỉ tiêu/tiền phạt. Việc gỡ tắc phải có checkpoint/điều chỉnh có audit.
- Chốt bù theo batch có giới hạn, lưu tiến độ và cảnh báo khoảng trống; không quét/chốt 31 ngày cho toàn bộ users trong một giao dịch không đo tải.

**Nghiệm thu:** thay đổi/xóa campaign hoặc reject report sau ngày chấm không tự viết lại nghĩa vụ lịch sử đã xác nhận; thiếu lịch sử không sinh khoản phạt suy đoán.

### R03 — P1: Pending vẫn bị auto-pause hoặc được resume

**Vị trí:** `036_daily_task_quota.sql:897–918` vẫn là định nghĩa auto-pause hiện hành; `053_daily_task_carryover.sql:809–817`.

Auto-pause chỉ xem `met=false`, không phân biệt owner chưa ready. Resume chỉ refresh rồi xem streak có >=7 không; chưa có chặn pending riêng.

**Tái hiện:**

- C02: `ready=false`, streak 7 → gọi auto-pause vẫn đưa app sang `paused/owner_inactive`.
- C03: owner pending, app paused, streak 0 → resume vẫn trả `success=true`.

**Kế hoạch sửa:** bổ sung điều kiện ready vào auto-pause/resume và các consumer quyết định trạng thái. Pending không được kích hoạt pause vì nhiệm vụ chưa xác định; resume theo điều kiện nhiệm vụ phải trả mã chờ cập nhật, không suy luận đã đủ từ streak thấp. Giữ độc lập các điều kiện admin pause, slot, hết hạn và các đường quản trị được phép.

**Nghiệm thu:** hai ca trên không đổi trạng thái sai; sau ready đủ T thì reset/mở lại đúng; mua đẩy không thay điều kiện này.

### R04 — P1: Tắt/bật chính sách làm mất phần thiếu; chính sách còn sửa được quá khứ

**Vị trí:** migration 053 dòng 58–63, 93–120, 257–301.

Mỗi row bật được dùng như một ngày bắt đầu mới; đúng ngày bật lại luôn C=0. Nhánh tắt cũng không giữ C theo thiết kế đóng băng. Setter cho phép ghi ngày quá khứ và UPDATE chính sách đã áp dụng, không có bảo vệ bất biến hoặc audit trước/sau.

**Tái hiện:** C04 có 8 app phần thiếu cũ, tắt rồi bật lại → C=0. C06 thay trực tiếp row chính sách đã có hiệu lực từ enabled sang disabled được chấp nhận.

Baseline còn hard-code ngày bật `2026-10-30`. Hôm kiểm tra đó là ngày mai, nhưng khi cài sau mốc này nó trở thành ngày quá khứ và có thể kích hoạt chốt bù ngay, không bảo đảm ngày hiệu lực được chọn sau khi APK sẵn sàng.

**Kế hoạch sửa:**

1. Tách ngày kích hoạt lần đầu khỏi từng lần đổi enabled; chỉ lần đầu C=0. Tắt thì bảo lưu số cũ, không tạo nghĩa vụ mới; bật lại nối checkpoint cũ.
2. Lịch mới chỉ từ ngày tương lai VN; trigger bảo vệ lịch đã hiệu lực và chống ghi hồi tố, audit thay đổi, serialize cập nhật lịch.
3. Cài schema ở trạng thái chưa bật hoặc qua cấu hình rollout có kiểm soát; không dùng ngày lịch cố định trong source như quyết định bật production.
4. Fixture test được tạo lịch quá khứ theo đường test/seed riêng, không nới chính sách production để tiện test.

**Nghiệm thu:** còn 8 → tắt nhiều ngày → bật lại vẫn giữ đúng phần còn lại; sửa/xóa/insert hồi tố bị chặn; cài vào một ngày sau 30/10 không tự truy thu lịch sử.

### R05 — P1: Phản hồi mua tài khoản A ghi số dư vào cache tài khoản B

**Vị trí:** `CampaignRepository.kt:1584–1625`, `SessionCacheManager.kt:116–119`, `OwnerAppDetailViewModel.kt:762–787`.

Repository lấy uid sau khi chờ RPC và cập nhật snapshot hiện tại mà không kiểm tra uid/generation ban đầu. ViewModel mua cũng không có guard như luồng refresh đã có.

**Tái hiện Android:** giữ response mua A, đổi sang B và lưu cache B với 77 Credit, sau đó trả response A có balance 900 → cache vẫn ghi `userId=B` nhưng **userCredits và profile credits thành 900**.

Đây là ghi sai trạng thái local; probe không chứng minh số dư B trong database bị sửa. Tuy vậy giao diện có thể hiển thị tiền, metadata hoặc thành công của giao dịch thuộc phiên khác.

**Kế hoạch sửa:** chụp uid/generation/request sequence trước RPC, xác minh sau mỗi await và trước publish/invalidate/snapshot/callback. API cập nhật cache phải nhận expected uid/generation và cập nhật nguyên tử trong cơ chế đồng bộ hiện có; không transform snapshot bất kỳ. Receipt thuộc A chỉ lưu/xử lý ở phạm vi A khi phiên đã đổi, không publish vào B.

**Nghiệm thu:** ca đối chứng phải giữ B=77; không toast/callback/metadata của A trên B; logout trong lúc request chạy không khôi phục snapshot cũ.

### R06 — P1: Giao dịch cũ chưa rõ kết quả không có đường phục hồi đầy đủ

**Vị trí:** `OwnerAppDetailViewModel.kt:726–765`; `OwnerAppDetailScreen.kt:730–732, 806`; `CampaignPushStore.kt:20–37`.

Kiểm tra paused và số dư <100 diễn ra trước khi đọc request đang chờ. UI cũng disable xác nhận nếu thiếu tiền. Không có luồng tự tra receipt khi mở lại màn hình; `peekPendingRequestId` chưa được tích hợp để phục hồi.

**Tái hiện Android:** có request ID đang chờ, app open, số dư 0 sau một lần mua có thể đã commit → gọi `pushApp` không hề gửi request replay, chỉ báo thiếu Credit và giữ pending. Nếu app đã paused/xóa thì còn thêm rào cản tương tự. Không thể dựa vào điều kiện mua mới để tra một giao dịch đã được xác nhận trước đó.

Ngoài ra success/replayed chưa được phân biệt ở thông báo; retry receipt cũ vẫn hiện “Đã trừ 100 Credit”. SharedPreferences đang dùng `.apply()` và chưa có kiểm chứng lưu bền vững trước khi gửi qua các lỗi process death.

**Kế hoạch sửa:**

- Tách thao tác mua mới và xác minh request cũ. Vào màn hoặc khôi phục process phải tra request/receipt còn chờ trước; không đòi đủ 100 hoặc app còn open để xác minh.
- Chỉ giao dịch mua mới cần hộp xác nhận và các điều kiện hiện hành; retry request đã xác nhận không hỏi trả thêm tiền và không tạo UUID mới.
- Có trạng thái `resolving/unknown/confirmed/failed` cùng thông báo rõ; không coi timeout là mua thất bại chắc chắn.
- Lưu pending thành công bền vững trước khi gửi; nếu lưu thất bại không gửi. Phân biệt lỗi nghiệp vụ chắc chắn với chưa biết kết quả để kết thúc pending đúng lúc.
- Xử lý response replay với metadata/ngày lịch sử; không quảng bá lại vị trí/hạn cũ hoặc khẳng định vừa bị thu phí mới. Tải số dư hiện tại riêng thay vì luôn coi `balance_after` lịch sử là hiện tại.

**Nghiệm thu:** timeout sau commit với số dư về 0, process death, pause/xóa app và retry hôm sau đều đối soát được bằng cùng request; một giao dịch chỉ thu 100 một lần.

### R07 — P1: Receipt Đẩy app làm hỏng xóa tài khoản

**Vị trí:** `052_campaign_push_priority.sql:53`.

`campaign_push_receipts.user_id` tham chiếu profile với `ON DELETE RESTRICT`. Các sửa trước ở migration 051 đã xử lý vòng đời tài khoản cho thưởng cập nhật, nhưng bảng receipt mới lại chặn cascade.

**Tái hiện P01:** mua đẩy rồi xóa `auth.users` trong DB thử nghiệm → lỗi **23001**, chỉ rõ `campaign_push_receipts_user_id_fkey`. Không phải suy đoán từ DDL.

**Kế hoạch sửa:** khóa ngoại user nullable/SET NULL hoặc mô hình lưu danh tính đã ẩn danh phù hợp luồng xóa tài khoản; bảo toàn snapshot tài chính cần thiết, giữ quyền đọc không để lộ receipt khi user đã xóa. Không dùng cascade xóa sạch bằng chứng thu phí như cách né lỗi.

**Nghiệm thu:** xóa tài khoản từng mua nhiều lượt, kể cả có app update/mission liên quan, thành công; ledger/receipt vẫn đối soát được và không rò dữ liệu cho user khác.

### R08 — P2: Xóa campaign làm request mua cũ không replay được

**Vị trí:** migration 052 dòng 54, 195–198, 237–239.

Campaign bị xóa thì receipt có `campaign_id=null`; RPC so với campaign ID gốc người dùng gửi lại rồi trả `REQUEST_ID_CONFLICT`. Kế hoạch yêu cầu replay giao dịch cũ vẫn tra được sau khi campaign dừng/xóa.

**Tái hiện P02:** mua thành công → xóa campaign → gửi lại cùng user/request/campaign ID ban đầu → REQUEST_ID_CONFLICT.

**Kế hoạch sửa:** lưu identity campaign gốc bất biến ngoài FK nullable, kèm snapshot tên/hạn cần thiết; dùng identity đó kiểm tra payload và tra receipt trước các điều kiện mua mới. RPC tra receipt riêng có thể phục vụ R06 nhưng vẫn phải chặn cùng request ID được gắn sang campaign khác.

**Nghiệm thu:** replay sau xóa trả receipt cũ, không thu lại/đẩy lại; request cũ với campaign khác vẫn bị từ chối.

### R09 — P2: Bảo vệ metadata đẩy dựa vào cờ GUC do session có thể tự đặt

**Vị trí:** migration 052 dòng 84–109, 312–320.

Trigger SECURITY DEFINER chỉ tin `current_setting('app.allow_push_metadata_write')`, trái thiết kế không dùng cờ tùy ý làm bằng chứng đã thanh toán.

**Tái hiện P03:** trong phiên SQL role authenticated có quyền sửa campaign của mình, đặt custom setting true rồi UPDATE metadata → được push_order 999999, số dư giữ 1.000, không receipt.

**Giới hạn bằng chứng:** đã chứng minh bypass ở SQL-role/session. **Chưa chứng minh người dùng chỉ có JWT qua PostgREST hiện tại gọi được set_config tùy ý**; không mô tả đây là lỗ hổng khai thác từ app đã tái hiện.

**Kế hoạch sửa:** dùng quyền ghi/owner tin cậy của đường RPC và trigger theo quyền caller, hoặc tách metadata sang bảng client không được ghi. Không lấy GUC tự đặt làm điều kiện đủ. Kiểm tra cả INSERT/UPDATE và các SECURITY DEFINER RPC hiện có có thể ghi campaign; pause/resume hợp lệ phải tiếp tục chạy khi metadata không đổi.

**Nghiệm thu:** role client tự đặt GUC vẫn không sửa được metadata; RPC thu phí đúng vẫn ghi được; không làm hỏng update thường, auto-pause và resume.

### R10 — P2: Checkpoint ngày đăng ký giữ row mặc định thành “chưa đạt”

**Vị trí:** migration 053 dòng 425–444.

Nếu record_daily_login đã tạo row live ngày đăng ký, nhánh ON CONFLICT chỉ cập nhật finalized_at, bỏ qua các field miễn/phần thiếu/phiên bản.

**Tái hiện C07:** row signup đã logged_in=true được chốt thành `is_exempt=false, met=false, carryover_version=null`, dù checkpoint ngày đăng ký phải được miễn/đạt. Probe không thấy phạt ở nhánh này, nhưng ledger/audit sai và không đạt tiêu chí checkpoint.

**Kế hoạch sửa:** upsert đầy đủ dữ liệu checkpoint đúng cho row chưa finalized, giữ row finalized bất biến. Test cả có/không row login và ngày đăng ký trùng ngày đầu hiệu lực.

**Nghiệm thu:** signup row finalized có exempt/met/phiên bản/ngày hiệu lực đúng và carry=0; retry không thay lịch sử đã chốt.

### R11 — P2: Thẻ nhiệm vụ thiếu/hiển thị sai giải thích phần bảo lưu

**Vị trí:** `DailyTaskCard.kt:409–436`, `values-vi/strings.xml:218` và resource tương ứng.

- Nhánh thiếu nguồn truyền `(availableApps, target, remaining)` trong khi string dùng `%1$d` và `%2$d`. Với `A=5, target=5, remaining=3`, dòng “còn thiếu bảo lưu” hiển thị **5**, không phải 3.
- Nhánh `waived` đứng trước owner, chỉ nói miễn hôm nay; C cũ còn 8 khi A=0 không được giải thích như kế hoạch.
- Khi streak>0, nhánh cảnh báo cũ che phần giải thích `B + C` — chính là trạng thái thường gặp sau ngày thiếu nhiệm vụ.

**Kế hoạch sửa:** tách thông báo trạng thái, phân rã chỉ tiêu và số bảo lưu thành các dòng độc lập; dùng đúng argument index và diễn giải số dự kiến sau tiến độ hiện tại. A=0 vẫn cho biết phần cũ còn giữ. Đồng bộ 3 ngôn ngữ.

**Nghiệm thu:** UI/resource test và ảnh kiểm tra các ví dụ 6+2=8, C=8/A=5/D=5 còn 3, C=8/A=0 còn 8, và có streak>0. Chưa có ảnh kiểm tra UI cho nhận xét này trong lần review.

### R12 — P2: Pending không có retry theo kết quả khi cron trễ

**Vị trí:** `HomeViewModel.kt:217–244`.

Scheduler chỉ thêm một lần refresh 00:05:05 sau nửa đêm; không có nhánh retry/backoff theo `carryover_ready=false`. Nếu cron chưa xong tại mốc đó, màn hình đang mở không có cơ chế mới tự lấy trạng thái sẵn sàng, phải đợi tác động refresh khác. Mở màn sau mốc 00:05 cũng không đi qua lần chờ này.

**Kế hoạch sửa:** dùng trạng thái pending điều phối retry có giới hạn/backoff, hủy khi đổi user/ngày/ra khỏi màn và dừng khi ready; dùng cache ngắn cho pending. Foreground/resume sau 00:05 phải cùng cơ chế. Thêm giám sát lỗi cron/khoảng trống bên server, không gọi finalizer từ UI.

**Nghiệm thu:** cron hoàn tất 00:07 hoặc một request bị lỗi mạng → UI tự chuyển ready mà không cần kéo refresh, không tạo vòng request vô hạn.

## 5. Những phần cần hoàn tất thêm trước rollout

Các mục này chưa được chứng minh đạt; không gộp thành khẳng định đã có lỗi live:

1. **Capability/version:** chưa thấy capability Đẩy app ở UI; code luôn chọn các cột mới. Cần kiểm tra backend cũ, kế hoạch migration trước APK, phiên bản tối thiểu và bật bán sau khi client hiểu ưu tiên trả phí.
2. **Thông báo chính sách Đẩy app:** resource hiện có nói giá/hạn/người mua sau đứng trên; chưa thấy dòng rõ về không hoàn tiền và việc có thể bị auto-pause do thiếu nhiệm vụ. Bổ sung theo đặc tả đã chọn.
3. **Metadata lỗi:** probe Android chấp nhận push_business_date đúng ngày + order dương nhưng expiry=null. DB thường chặn bộ cột thiếu; vẫn nên validate đủ metadata theo kế hoạch và test JSON/cache hỏng, không coi date/order là đủ cho mọi nguồn.
4. **Độ bền request:** thử lỗi ghi pending/process death giữa lưu và gửi; test hiện có chỉ parse JSON/AtomicBoolean riêng không kiểm tra đầy đủ state machine của ViewModel/store.
5. **Tính nguyên tử migration:** 052/053 không tự bao BEGIN/COMMIT trong file; xác nhận runner triển khai thật có transaction. Cài bằng SQL Editor phải có quy trình nguyên tử, không để nửa schema bật tính năng nếu statement sau lỗi.
6. **SQL mirror:** khác comment không phải lỗi nghiệp vụ. Nên chuẩn hóa mirror/test so sánh định nghĩa/quyền cuối để tránh drift thật trong lần sửa tới.
7. **Concurrency:** chưa chạy test nhiều kết nối; phải bao cả push với finalizer/resume/auto-pause/chi Credit khác và thao tác xóa campaign/account.

## 6. Kế hoạch sửa theo thứ tự

### Gói A — Nguồn chốt ngày và chính sách: R01–R04, R10

Ưu tiên vì có thể làm sai phạt, số chuyển tiếp hoặc trạng thái app.

- Chốt thiết kế nguồn lịch sử/checkpoint và sẵn sàng ledger; sửa finalizer + wrapper + helper chung, lịch bật/tắt và pending guard cho pause/resume.
- Tách điều kiện UI member đã đăng nhập khỏi điều kiện đủ nguồn chốt phần thiếu.
- Bổ sung ràng buộc, audit và giới hạn chốt/batch. Không phục hồi lịch sử bằng cách tự điền mọi row carry=0.

### Gói B — Giao dịch Đẩy app và danh tính: R05–R09

- Sửa state machine khôi phục receipt, guard user/session, snapshot atomic update và cơ chế ghi metadata tin cậy.
- Sửa identity bất biến của receipt và FK xóa tài khoản; thiết kế cùng một lần để tránh sửa FK xong lại mất payload dùng replay.
- Giữ nguyên giá 100, thứ tự/hạn, idempotency và độc lập nghĩa vụ test.

### Gói C — Giao diện, cache, rollout: R11–R12 và mục 5

- Sửa hướng dẫn số bảo lưu, retry pending, thông báo giao dịch lịch sử, cảnh báo auto-pause/không hoàn tiền, capability và phiên bản hỗ trợ.
- Kiểm tra phản hồi muộn sau chấm phạt: receipt balance là số lịch sử; profile tải mới mới là số hiện tại. Không làm giảm hiệu lực các guard user/session đã sửa.

### Gói D — Kiểm thử và bàn giao lại

1. Chuyển toàn bộ ca tái hiện sang regression test mong hành vi đúng.
2. Chạy trên **schema cuối đã áp cả hai tính năng và bản sửa**. Runner hiện tại nhiều suite nạp schema trước migration mới, nên việc cả chuỗi npm test qua không tự chứng minh các tính năng cũ hoạt động dưới 053.
3. Giữ các ca nghiệp vụ bình thường: 6/4→2, hôm sau 6+2=8, nguồn thiếu, member login, giá 99/100/150, đẩy sau lên trước, hết ngày, hủy xác nhận không gửi mua.
4. Thêm PostgreSQL đa kết nối, kiểm tra UI/thiết bị, account/process lifecycle và backend cũ/new client.
5. Sửa báo cáo bàn giao: tách rõ số test đã chạy, ca chưa chạy và live chưa xác minh; không dùng “PASS 100%” để thay thế tiêu chí nghiệm thu toàn bộ kế hoạch.

## 7. Cách phát hành bản sửa

- Trước hết kiểm tra chỉ đọc definition/quyền/policy/cron và migration live. Từ việc file có trong repo không thể suy ra đã deploy.
- Nếu 052/053 đã áp ở bất kỳ môi trường cần bảo toàn: tạo migration sửa mới theo số còn trống; không chỉ sửa file cũ rồi kỳ vọng live tự đổi. Đồng bộ schema gộp trên source cuối.
- Nếu chắc chắn chưa phát hành, có thể hợp nhất chỉnh sửa migration trước rollout theo quy trình dự án, vẫn phải test nâng cấp và dựng mới.
- Giữ nguyên các thay đổi ngoài phạm vi đang có trong workspace; không chép nguyên file từ kế hoạch cũ để ghi đè tính năng khác.
- Với row đã chốt sai hoặc phần thiếu đã mất: lập danh sách ảnh hưởng và bằng chứng đối soát riêng; không tự sửa finalized/thu phạt bù/hoàn tiền bằng dữ liệu suy đoán.
- Chỉ bật ngày hiệu lực mới khi bản backend/client và các kiểm thử cần thiết đã sẵn sàng; không giữ ngày bật cứng trong migration như hiện tại.

## 8. Điều kiện nghiệm thu lại

1. Pending hoặc thiếu snapshot không bị chốt/phạt nhầm, không pause/resume sai và không bị coi carry=0.
2. Tắt/bật và thay lịch chính sách không xóa phần thiếu đã bảo lưu hoặc sửa quá khứ.
3. Response A không thay cache/UI B; request đã mua luôn đối soát được kể cả thiếu tiền, paused, xóa app, đổi ngày hoặc process death.
4. Xóa tài khoản không bị receipt cản; lịch sử thu phí còn đối soát và đúng quyền.
5. Client không thể tự tạo ưu tiên trả phí qua sửa metadata; quyền/trigger không chặn các cập nhật trạng thái hợp lệ.
6. UI nói đúng số cơ bản/chuyển vào/còn giữ, member giữ luật cũ; pending tự cập nhật khi server sẵn sàng.
7. Có kết quả hồi quy trên schema cuối, concurrency PostgreSQL thật và kiểm tra giao diện; ghi rõ môi trường triển khai thực tế.
