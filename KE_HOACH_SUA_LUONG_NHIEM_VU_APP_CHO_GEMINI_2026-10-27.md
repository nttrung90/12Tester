# Kiểm tra luồng Nhiệm vụ App và kế hoạch sửa cho Gemini — 27/10/2026

## 1. Phạm vi và kết luận

Kiểm tra luồng **Nhiệm vụ → Nhiệm vụ App**: tải danh sách → tạo nhiệm vụ → nhận nhiệm vụ → mở app liên kết → nộp/duyệt báo cáo → trả Credit → dừng/tiếp tục/xóa/hết hạn.

**Có lỗi cần sửa.** Ghi nhận 8 vấn đề: 5 lỗi backend đã tái hiện trên PostgreSQL nhúng PGlite và 3 lỗi xác nhận qua đọc mã nguồn. Ngoài ra có 1 tình huống biên đã tái hiện nhưng cần phân biệt quy tắc nghiệp vụ với lỗi kỹ thuật (M04).

Đối tượng kiểm tra là **working tree hiện tại**, HEAD `020e550e0f3fdd63f11d1c72419409e499365879`, bao gồm các thay đổi chưa commit và migration 043. Đây không phải kết quả kiểm thử bản Android đang cài hoặc Supabase đã triển khai. Không chạy emulator/thiết bị, không gọi Supabase/Firebase thật. Chưa xác minh backend production đã áp dụng các migration nào.

Chỉ bổ sung tài liệu bàn giao và probe trong `.tmp/mission-audit/`; **không sửa mã production, không deploy**. Repo đang có nhiều thay đổi của các công việc khác; Gemini phải giữ nguyên chúng.

## 2. Bằng chứng thực thi

Đã nạp `supabase/full_schema_singapore.sql` tới 043 vào database PGlite dùng một lần, tạo dữ liệu giả rồi gọi các RPC hiện tại. Mỗi tình huống nằm trong transaction riêng và rollback sau kiểm tra.

Chạy tại thư mục gốc:

```powershell
node .tmp/mission-audit/repro.mjs
```

| Mã | Tình huống | Kết quả quan sát | Loại bằng chứng |
|---|---|---|---|
| M01 | Người đã nhận, nhiệm vụ chuyển `full` | Nhánh `isFull` đứng trước `hasJoined`, che nút đi test | Đọc nhánh Compose; chưa chạy Android UI |
| M02 | Tạo A → dừng A → tạo B cùng app → tiếp tục A | RPC đều thành công; A và B cùng `open` | RPC/database |
| M03 | Báo cáo nộp trước lúc nhận nhiệm vụ 1 giờ → duyệt | Participant thành `completed`, nhận 10 Credit nhiệm vụ | RPC/database |
| M04 | Nhận trước, nộp báo cáo trước `ends_at`, auto duyệt sau `ends_at` | Báo cáo `approved`, participant vẫn `joined`, không trả thưởng nhiệm vụ | RPC/database; cần chốt ý nghĩa thời hạn |
| M05 | Giả lập lỗi ghi log thưởng trong lúc duyệt | RPC duyệt báo thành công, báo cáo `approved`, participant `joined`; chạy auto duyệt lại xử lý 0 báo cáo | RPC/database, có fault injection trong DB tạm |
| M06 | Nhiệm vụ cũ `open` nhưng `ends_at` đã qua → tạo mới | `MISSION_ALREADY_EXISTS`; chỉ sau đọc danh sách mới tạo thành công | RPC/database |
| M07 | Có 101 nhiệm vụ, nhiệm vụ cuối mới nhất | Danh sách trả 100 phần tử, thiếu nhiệm vụ mới nhất; phần tử đầu là `Item 100` | RPC/database |
| M08 | Lỗi tải danh sách/form | Catch bỏ lỗi, UI dùng trạng thái rỗng hoặc giá trị mặc định | Đọc ViewModel và màn hình |
| M09 | Response nhiệm vụ cũ về sau refresh mới/đổi phiên | Các coroutine mission publish trực tiếp, thiếu guard sequence/uid/generation hiện có ở luồng khác | Đọc source; chưa chạy probe coroutine |

**Lưu ý:** `repro.mjs` assert **hành vi lỗi đang tồn tại**. Exit code 0 xác nhận tái hiện được, không có nghĩa tính năng đã đúng. Sau khi sửa, chuyển thành regression test assert hành vi đúng; không lấy script hiện tại làm tiêu chí PASS sau sửa.

Evidence cục bộ, được Git-ignore:

- `.tmp/mission-audit/repro.mjs`: probe có thể chạy lại bằng dependency PGlite đã có tại `supabase/tests/node_modules`.
- `.tmp/mission-audit/results.json`: kết quả 6 tình huống database và kiểm tra thứ tự nhánh M01.
- `.tmp/mission-audit/source-hashes.json`: SHA256 các source chính lúc audit.

Đã chạy thêm:

```powershell
cd supabase/tests
node run-backend-cleanup-hardening.mjs
```

**5 nhóm kiểm thử hardening 043 PASS**, bao gồm sửa rollback khi pause/resume đã hết hạn. Không mở lại lỗi cũ đó trong kế hoạch này. Bộ test 043 không bao phủ các lỗi mission phát hiện ở đây.

## 3. Lỗi và hướng sửa

### M01 — P1: nhiệm vụ đủ người làm mất nút đi test của người đã nhận

**Vị trí:** `app/src/main/java/com/ntt/crosstest/ui/screens/QuestsScreen.kt:863`, nhất là nhánh `isFull` dòng 898 và `hasJoined` dòng 906.

**Tái hiện:** tạo nhiệm vụ chỉ có 1 chỗ; tester nhận chỗ đó. Backend trả `status=full`, HomeViewModel cập nhật `hasJoined=true`. Thẻ chỉ hiện “Đã đủ người”, không còn nút “Đã nhận — đi test app để nhận Credit”. Hàng tên/icon app cũng không có callback mở chi tiết nên đường đi tiếp ngay trên thẻ bị chặn.

**Sửa:** tách khả năng nhận mới khỏi khả năng tiếp tục của participant. Sau các trạng thái không được tiếp tục theo chính sách hiện tại, ưu tiên người đã nhận chưa hoàn thành trước nhánh báo đầy. `full` chỉ chặn người chưa nhận. Giữ thứ tự riêng cho `hasCompleted`, chủ nhiệm vụ, paused/stopped/expired.

**Đạt khi:** tester cuối cùng và mọi tester đã nhận vẫn mở app từ thẻ khi nhiệm vụ `full`; người chưa nhận chỉ thấy đủ người. Không mở nút tiếp tục cho paused nếu chưa thay đổi chính sách.

### M02 — P1: một app có thể có nhiều nhiệm vụ đang hoạt động

**Vị trí:** `supabase/migrations/039_missions.sql:122` (`create_mission` chỉ xét `open/full`); `040_mission_management.sql:84` và bản override hiện hành `043_backend_cleanup_hardening.sql:118` (`resume_mission`); `MissionCreateViewModel.kt:55` chỉ lọc `isActive`.

**Nguyên nhân đã tái hiện:** `paused_by_creator` không được giữ chỗ trong kiểm tra trùng. Có thể tạo B khi A tạm dừng rồi resume A. UI cũng coi app đó là có thể tạo nhiệm vụ mới. Hai mission cùng open làm RPC quản lý chỉ lấy mission mới nhất, còn settlement chọn mission mới nhất có participant; nhiệm vụ cũ dễ bị khuất và việc trả thưởng thiếu rõ ràng.

**Rủi ro bổ sung qua source:** kiểm tra `exists` trong `create_mission` chạy trước khóa profile; không có unique index tương ứng hoặc khóa campaign bao trùm check/insert. Hai request tạo đồng thời có thể cùng vượt kiểm tra. Chưa chạy kiểm thử nhiều kết nối để xác nhận lịch xen kẽ này.

**Sửa:**

1. Xem `open/full/paused_by_creator` còn hạn là cùng chiếm một vị trí nhiệm vụ trên campaign. Dùng chung định nghĩa trong create/resume/form quản lý.
2. Trong create, khóa campaign trước khi đồng bộ trạng thái hết hạn, kiểm tra trùng và insert. Resume phải dùng cơ chế khóa nhất quán và kiểm tra xung đột.
3. Thêm unique partial index trên `campaign_id` cho tập trạng thái chiếm vị trí, sau khi xử lý dữ liệu cũ. Không đưa `now()` vào predicate index; trạng thái quá hạn phải được đồng bộ trong giao dịch trước khi insert.
4. Không dùng danh sách khám phá giới hạn 100 bản ghi để quyết định app của chủ nào đang có mission. Có endpoint/query theo campaign hoặc chủ sở hữu đầy đủ.
5. Chuẩn hóa lỗi xung đột thành `MISSION_ALREADY_EXISTS` cho client.

**Dữ liệu cũ:** preflight thống kê campaign có nhiều mission sống; tạo phương án giữ mission/cancel mission dư có audit và thông báo. Không xóa participants, log Credit hoặc âm thầm hoàn/trừ phí. Chưa thực hiện migration dữ liệu thật trong lượt audit này.

**Đạt khi:** pause A không cho tạo B; 2 request cùng campaign chỉ có 1 thành công, chỉ trừ 1 lần phí; 2 campaign khác nhau vẫn tạo được; expired được thay thế đúng M06.

### M03 — P1: báo cáo trước khi nhận nhiệm vụ vẫn được tính thưởng

**Vị trí:** `039_missions.sql:285` (`_settle_mission_reward`), call site `:608` và `:713`.

Helper chỉ nhận `campaign_id/user_id`; không nhận `report_id`, không kiểm tra thời gian nộp với `mission_participants.joined_at`. Báo cáo đang pending từ trước có thể được duyệt sau lúc nhận mission và hoàn thành mission bằng công việc cũ. Probe đã xác nhận +10 Credit nhiệm vụ ngoài thưởng test thường.

**Sửa:** liên kết báo cáo đủ điều kiện với participant/mission cụ thể. Truyền report identity tới settlement; xác minh campaign, tester, trạng thái báo cáo được duyệt và thời gian thực sự nộp hợp lệ sau khi nhận. Nếu dùng timestamp, phải xét cả luồng báo cáo bị từ chối rồi nộp lại: `created_at` của bản ghi có thể không đại diện lần nộp mới. Nên lưu submission/attempt identity hoặc thời điểm nộp đủ điều kiện rõ ràng.

Không tìm mission mới nhất một cách mơ hồ để gắn một báo cáo cũ. Không cho một report đã dùng trả thưởng bị dùng lại ở một mission mới. Cần constraint/idempotency key bền vững và kiểm thử tay/auto duyệt đua nhau.

**Đạt khi:** báo cáo trước join không trả mission reward; báo cáo hợp lệ sau join trả đúng một lần; report từ campaign khác, tester khác, đã reject hoặc đã dùng không hoàn thành mission. Thưởng test thường vẫn theo quy tắc hiện hành.

### M05 — P1: duyệt báo cáo thành công nhưng mất lượt trả thưởng khi settlement lỗi

**Vị trí:** `039_missions.sql:397` bắt `exception when others`; `:608` và `:713` gọi helper bằng `perform`, bỏ kết quả.

Khi helper lỗi, phần chuyển Credit rollback trong block riêng rồi trả JSON `settled=false,error=...`. Caller vẫn commit `approved`. Auto-accept chỉ tìm `pending`, nên sau khi sự cố hết không thử lại báo cáo này. Một lỗi tạm thời có thể khiến phần thưởng của báo cáo đó bị bỏ qua; một báo cáo tương lai có thể thanh toán nếu mission vẫn còn đủ điều kiện, nhưng đây không phải cơ chế retry bảo đảm.

**Sửa:** giữ việc duyệt báo cáo có thể hoàn tất độc lập nếu đó là chủ đích, nhưng phải ghi một settlement job/outbox bền vững trong cùng giao dịch. Job liên kết chính xác report + participant + mission, có unique key, attempt/error, trạng thái pending/paid/skipped và retry có backoff. Phân biệt “không có mission”, “không đủ điều kiện theo chính sách”, “hết Credit” với “lỗi kỹ thuật”. Không nuốt mọi lỗi thành cùng một `false`.

Credit, log hai bên và chuyển participant sang completed phải atomic; retry không chuyển trùng. Nếu chọn phương án rollback cả approval thay vì outbox, phải mô tả tác động tới batch auto-accept và không để một báo cáo lỗi chặn toàn bộ batch. Không chỉ xóa catch rồi coi là hoàn thành.

**Đạt khi:** fault injection lúc ghi log/cộng Credit không để lệch số dư; sau khôi phục, retry trả đúng một lần cho đúng báo cáo. Không tự trả lại mọi mission lịch sử bị paused/deleted/expired.

### M06 — P2: tạo nhiệm vụ mới phụ thuộc việc ai đó đã mở danh sách trước đó

**Vị trí:** `039_missions.sql:122` và `040_mission_management.sql:289`.

Create chỉ kiểm tra trạng thái, không kiểm tra `ends_at` hoặc đồng bộ hết hạn dưới khóa. Dòng `status=open` nhưng đã qua hạn vẫn chặn tạo mới. Đọc `get_open_missions()` cập nhật expired toàn cục nên cùng dữ liệu đó lại tạo được ngay sau một lần đọc. Cron/helper 043 làm giảm khả năng gặp nhưng không bảo đảm đúng ngay ranh giới thời gian.

**Sửa:** đồng bộ mission hết hạn của campaign trong giao dịch create trước kiểm tra trùng; dùng chung cơ chế khóa/index của M02. Không phụ thuộc cron hoặc lượt đọc khác. Form cũng không nên dùng status cũ để ẩn app đủ điều kiện.

**Đạt khi:** với dòng open/full/paused đã qua `ends_at`, tạo mới thành công mà không cần gọi list/cron trước; mission cũ được đánh dấu expired. Đúng cả khi hai request tạo cùng lúc.

### M07 — P2: giới hạn 100 trước khi sắp xếp khiến mất nhiệm vụ mới/đang làm

**Vị trí:** `040_mission_management.sql:317` và `:344–346`.

`LIMIT 100` nằm trong subquery không có `ORDER BY`. Sắp xếp `jsonb_agg` bên ngoài chỉ sắp tập đã bị cắt. Nhiệm vụ mới nhất hoặc của chính người xem có thể không được lấy. Probe 101 bản ghi đã xác nhận thiếu `Item 101`.

**Sửa:** order trước limit, thêm khóa phụ ổn định như `created_at,id`. Thiết kế phân trang cho khám phá; lấy “đã nhận/của tôi” bằng query hoặc section riêng để không bị mất sau 100 nhiệm vụ cộng đồng. Form tạo không sử dụng trang khám phá để kiểm tra tồn tại (M02). Xem xét lọc/trả trạng thái campaign liên kết, vì list hiện vẫn trả mission khi campaign đã ngừng nhận tester trong khi join sẽ từ chối.

**Đạt khi:** dữ liệu >100 vẫn hiển thị đúng mới nhất, thứ tự ổn định, không lặp/bỏ sót khi tải trang; mission đã nhận và mission của chủ vẫn truy cập được. Nếu chỉ chọn sửa order trong đợt đầu, phải ghi rõ phân trang/section cá nhân còn chưa xử lý.

### M08 — P2: lỗi tải dữ liệu bị hiển thị thành không có nhiệm vụ/không có app

**Vị trí:** `HomeViewModel.kt:440–449`; `MissionCreateViewModel.kt:49–81`; `QuestsScreen.kt:453–489`; `MissionCreateScreen.kt:134–146`; `OwnerAppDetailViewModel.kt:537–543`.

Lỗi list bị bỏ qua; khi chưa có dữ liệu, UI báo “Chưa có nhiệm vụ nào đang mở”. Form nuốt lỗi tải ban đầu, dùng danh sách rỗng/0 Credit, rồi hiển thị như chưa đăng app. Ngay cả tải thành công nhưng mọi app đã có mission, form cũng dùng chuỗi “Bạn chưa có app nào đang đăng”. Đây là các nguyên nhân khác nhau, cần hướng xử lý khác nhau.

**Sửa:** có error state riêng cho missions và initial load của form, nút retry, phân biệt empty thật / tất cả app đã có nhiệm vụ / lỗi mạng / backend chưa hỗ trợ RPC. Giữ dữ liệu cũ cùng trạng thái lỗi nếu đã có snapshot hợp lệ. Không biến mọi lỗi thành cảnh báo thiếu migration. CancellationException phải được truyền tiếp nếu dùng catch rộng. Màn quản lý cần cho biết lỗi load mission thay vì âm thầm giữ dữ liệu cũ mãi.

**Đạt khi:** cold load timeout hiện lỗi và retry được; retry thành công không cần mở lại màn; empty thật vẫn đúng; offline có cache hiện được dữ liệu phù hợp. Có chuỗi Việt/Anh tương ứng.

### M09 — P1: response nhiệm vụ cũ có thể ghi đè UI sau đổi phiên hoặc thao tác mới

**Vị trí:** `HomeViewModel.kt:393–451`, `:981–1024`; `OwnerAppDetailViewModel.kt:88–92`, `:537–543`; `CampaignRepository.kt:2171–2180`.

Refresh chính đã capture sequence/uid/generation ở đầu, nhưng tải mission lại tạo `viewModelScope.launch` riêng, không kiểm tra các token trước `_uiState.update`. Một request cũ có thể về sau request mới và ghi lại hasJoined/isMine/hasCompleted của snapshot trước đó. `loadMission()` màn chủ cũng publish không có guard. Join success/error cũng không kiểm tra phiên hoặc request và lỗi `ALREADY_JOINED` chỉ hiện toast, không sửa/reload trạng thái đã nhận.

Cache mission còn dùng `scopeUid=null` dù RPC có các cờ cá nhân; writer chỉ kiểm tra generation. Cần xử lý cùng các yêu cầu scope/publication đã nêu ở kế hoạch cache trước đó, không mặc định guard của refresh chính bảo vệ coroutine mission riêng.

**Mức bằng chứng:** xác nhận thiếu guard từ source; chưa chạy Android/coroutine probe đảo response trong lượt audit này.

**Sửa:** quản lý job/sequence riêng cho mission, capture uid + generation + mutation version trước request và kiểm tra trước publish; response cũ không được ghi UI/loading/error. Scope cache bằng đúng uid. Áp dụng cho list/create/join/load owner và cancellation. Sau lỗi tranh chấp `ALREADY_JOINED/FULL/EXPIRED/NOT_OPEN`, tải lại trạng thái có thẩm quyền; không để nút cũ lặp lỗi. Chặn thao tác trùng bằng state được đặt trước launch hoặc cơ chế serialize phù hợp, thay vì chỉ một biến joiningMissionId có thể bị request khác ghi đè.

**Đạt khi:** kiểm thử deferred đảo thứ tự, đổi A→B/logout giữa tải và refresh xen join/pause/delete; chỉ phiên/request hợp lệ được publish. Busy state không biến mất khi request khác vẫn chạy; UI được đồng bộ sau ALREADY_JOINED.

## 4. M04 — hành vi đã xác nhận, cần thống nhất định nghĩa thời hạn

`_settle_mission_reward` yêu cầu `ends_at > now()` ở thời điểm **duyệt** (`039_missions.sql:317`), trong khi UI ghi “Số ngày nhận nhiệm vụ” / “Còn N ngày nhận nhiệm vụ”. Vì vậy người nhận hợp lệ, nộp hợp lệ trước hạn nhưng chờ auto duyệt sau 8 giờ có thể không bao giờ nhận mission reward.

Probe dùng: nhận từ 10 giờ trước, nộp 9 giờ trước, mission hết hạn 1 giờ trước. Auto duyệt thành công nhưng không hoàn thành mission. Đây là hành vi thực tế, **chưa tự coi là lỗi implementation**: comment migration 039 cũng nói nhiệm vụ dừng khi hết số ngày.

Gemini cần thể hiện rõ hai hướng trong bản sửa:

- Nếu hạn là hạn nhận/nộp: đánh giá eligibility tại thời điểm nhận/nộp, cho phép duyệt hoặc retry kỹ thuật sau hạn; trả thưởng liên kết report như M03/M05. Phải định nghĩa cửa sổ nộp nếu người dùng nhận sát hạn.
- Nếu hạn cũng là hạn thanh toán: giữ behavior, sửa nội dung hướng dẫn/cảnh báo để người dùng biết báo cáo chưa được duyệt trước hạn không có mission reward, kể cả auto duyệt. Không hứa chắc “nộp báo cáo để nhận Credit nhiệm vụ”.

Trong khi chưa có quyết định thay đổi nghiệp vụ, giữ quy tắc đang có và ghi rõ hạn chế; không âm thầm đổi chính sách hoặc backpay dữ liệu cũ. Tương tự, pause và delete hiện có quy tắc sản phẩm rõ trong migration 040: pause ngừng trả thưởng trong lúc dừng, delete chưa hoàn thành mất cơ hội nhận thưởng, không hoàn phí. Audit này không yêu cầu tự đổi chúng thành escrow hoặc bảo đảm thưởng sau khi bị xóa.

## 5. Thứ tự triển khai đề xuất

1. **Chốt contract và tạo regression test trước:** trạng thái mission/participant, report eligibility, kết quả settlement và lỗi client. Chuyển các repro lỗi thành assertion hành vi đúng; thêm Kotlin tests cho M01/M08/M09.
2. **Backend M02 + M03 + M05 + M06:** migration mới, dự kiến `044_mission_flow_hardening.sql` nếu số 044 còn trống lúc bắt đầu. Không chỉ sửa các file 039/040 đã deploy. Dùng định nghĩa pause/resume mới nhất ở 043, giữ contract trả JSON hết hạn để UPDATE không rollback.
3. **Dữ liệu và query M07:** preflight trùng mission, association báo cáo/phần thưởng, index/query/phân trang. Không tự gắn lịch sử mơ hồ vào nhiệm vụ mới. Nếu thay signature helper phải xử lý overload cũ và grant/revoke rõ ràng; helper settlement/outbox chỉ backend được gọi.
4. **Android M01 + M08 + M09:** hành động trên thẻ, error/retry, bộ lọc form theo owner, cache scope, guard publication, reload sau xung đột. Luồng đi test vẫn dùng CampaignDetailScreen và quy tắc nộp báo cáo hiện có.
5. **Đồng bộ bootstrap schema:** cập nhật `full_schema_singapore.sql` theo migration mới. Kiểm thử cả nâng cấp từ 043 và cài mới bằng full schema, áp dụng lại migration không tạo phí/thưởng trùng.
6. **Bàn giao:** báo cáo file sửa, test thực chạy, migration cần áp dụng và điểm M04 còn quyết định. Chỉ ghi PASS cho các kiểm thử đã thực thi.

## 6. Ma trận kiểm thử nghiệm thu

| Nhóm | Ca bắt buộc | Kết quả cần đạt |
|---|---|---|
| Tạo | Số dư 299/300; phí 7 ngày 350; reward <10; app người khác/không open | Từ chối đúng điều kiện; lỗi không trừ phí |
| Tạo | App có paused còn hạn; hai request tạo cùng app; tạo hai app khác nhau | Tối đa một mission sống/app; phí đúng một lần; không khóa nhầm app khác |
| Tạo | App có open/full/paused quá hạn, không gọi list trước | Tạo mới được, cũ expired |
| Nhận | Chủ tự nhận; nhận trùng; chỗ cuối 2 tester đua; mission expired/paused/campaign closed | Chặn đúng, số participant/count nhất quán, không quá số chỗ |
| Thẻ | Chưa nhận/open, đã nhận/open, đã nhận/full, chưa nhận/full, completed, paused, stopped, expired, mine | CTA và nhãn đúng; đã nhận/full mở được app |
| Nộp/duyệt | Report trước join, sau join, reject/nộp lại, khác campaign/tester, đã được dùng | Chỉ report hợp lệ hoàn thành mission |
| Credit | Chủ duyệt, admin/moderator duyệt, auto sau 8h, duyệt lặp/tay đua auto | Mission reward đúng một lần; thưởng test/tip không đổi ngoài scope |
| Credit | Lỗi log/cộng Credit; retry; chủ không đủ Credit | Atomic tiền/log/completed; lỗi kỹ thuật có retry; thiếu Credit theo quy tắc stopped hiện hành |
| Hết hạn | Nhận/nộp sát ends_at; duyệt trước/sau hạn; retry sau hạn | Đúng contract M04 đã ghi nhận, có test biên rõ |
| Quản lý | Pause/resume/delete khi request list cũ đang bay; pause/resume quá hạn | Không sống lại UI cũ; không rollback trạng thái expired đã sửa ở 043 |
| Danh sách | 0/100/101+ mission; cùng created_at; có mission của tôi/đã nhận | Đúng order/phân trang, không mất việc đang làm |
| Mạng/phiên | Cold timeout, retry, offline có cache, logout/A→B, response đảo thứ tự, join tranh chấp | Không giả empty; không publish dữ liệu phiên/request cũ; state đồng bộ lại |

Lệnh nền tảng sau sửa:

```powershell
cd supabase/tests
npm test
# Bổ sung runner mission-flow vào script test, assert hành vi đúng.
```

```powershell
# Tại root, khi đã cấu hình JDK/Android SDK theo môi trường dự án:
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Lượt audit này **chưa chạy** Gradle, lint hoặc Android UI. Vì vậy không dùng các lệnh trên làm bằng chứng build hiện tại đã PASS. Concurrency DB cần test PostgreSQL nhiều kết nối thực tế trên môi trường dùng để kiểm thử; PGlite probe hiện tại không chứng minh tính đúng đắn khi nhiều giao dịch chạy đồng thời.

## 7. Prompt giao Gemini

> Hãy sửa luồng Nhiệm vụ → Nhiệm vụ App theo tài liệu này. Đọc working tree và các thay đổi đang có trước khi chỉnh; giữ nguyên công việc khác. Ưu tiên M01/M02/M03/M05/M09, sau đó M06/M07/M08. Dùng migration mới sau 043 và cập nhật full schema. Probe `.tmp/mission-audit/repro.mjs` hiện xác nhận hành vi lỗi, phải đổi sang regression tests hành vi đúng. Không tự đổi nghiệp vụ pause/delete, cơ chế phí hoặc thưởng test/tip. M04 là điểm cần thống nhất: chưa tự thay chính sách thanh toán sau hạn. Kiểm thử upgrade/bootstrap, retry/idempotency, race tạo/duyệt và Android state; bàn giao báo cáo chỉ ghi các kiểm thử thực chạy. Chưa deploy Supabase hoặc sửa dữ liệu thật ở giai đoạn này.
