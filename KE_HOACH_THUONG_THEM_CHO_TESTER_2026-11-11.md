# Nghiên cứu và kế hoạch: Thưởng thêm cho tester

Ngày: 11/11/2026. Dự án: 12Tester / 12 Cross Testers.

Phạm vi công việc này: đọc source hiện tại, xác định luồng tích hợp và lập kế hoạch triển khai. Chưa sửa mã chức năng, chưa chạy migration, chưa triển khai backend và chưa build APK. Các nhận định về hiện trạng bên dưới dựa trên source trong workspace, chưa xác minh trạng thái database production. Repository có nhiều thay đổi từ công việc khác; không ghi đè hoặc khôi phục các thay đổi đó.

## 1. Yêu cầu đã xác định

1. Trong trang quản lý app của chủ app, thêm mục **“Thưởng thêm cho tester”** ngay dưới **“Đẩy app”**.
2. Bấm vào mục này mở một **trang riêng**, gắn với đúng app được chọn.
3. Trang có hai ô nhập: **Credit thưởng cho mỗi tester** và **Số người tối đa được nhận đầu tiên**; có nút **Đồng ý**, **Hủy**.
4. Khi thiết lập thành công, thẻ của app tại Trang chủ có **biểu tượng Credit và `+N` màu cam ở bên phải**. Ví dụ: thưởng 5 Credit thì hiện biểu tượng Credit và **+5**.

## 2. Các quy tắc đề xuất để triển khai

Đây là đề xuất nghiệp vụ bổ sung, chưa phải các quyết định đã được người dùng xác nhận. Chúng được ghi rõ để có thể điều chỉnh trước khi triển khai.

| Nội dung | Phương án đề xuất |
|---|---|
| “Người đầu tiên” | N tester khác nhau có báo cáo test thường **được backend chấp nhận và ghi nhận thưởng thành công đầu tiên**, trong các báo cáo nộp sau khi đợt thưởng bắt đầu. Không tính lúc bấm cài, tham gia hay mở app. |
| Thứ tự | Theo thứ tự cấp thưởng được tuần tự hóa trong transaction ở server, gồm duyệt thủ công và tự động duyệt. Không theo đồng hồ điện thoại. Chủ app duyệt trước có thể ảnh hưởng thứ tự; nếu muốn theo thứ tự nộp, cần thiết kế hàng đợi/giữ suất riêng như mục 9. |
| Thời điểm trả | Khi báo cáo được chấp nhận; tự động duyệt sau 8 giờ theo source hiện tại cũng được xét. Báo cáo pending/rejected chưa nhận thưởng. |
| Tần suất | Một tester nhận tối đa một lần trong **mỗi đợt thưởng**, không nhận lại vào ngày tiếp theo trong cùng đợt. |
| Người đủ điều kiện | Cả tester đã tham gia trước đó và tester mới; loại chính chủ app. Không tự loại tester chỉ vì có vai trò admin/moderator, trừ khi quy tắc chung đã loại. |
| Loại báo cáo | Chỉ `submission_context = 'app_test'`. Báo cáo `mission`, thưởng cập nhật phiên bản và các khoản thưởng khác xử lý theo luồng riêng. |
| Báo cáo trước đợt | Không được nhận hồi tố, kể cả được duyệt sau lúc mở đợt. Báo cáo cũ bị từ chối rồi chỉnh sửa không được biến thành báo cáo mới để lấy thưởng. |
| Nguồn Credit | Chủ app tài trợ; giữ trước toàn bộ **Credit/người × số người** khi Đồng ý thành công. |
| Chi phí khác | Không đề xuất thêm phí dịch vụ. Ví dụ 5 Credit × 20 người = 100 Credit ngân sách. |
| Số đợt | Mỗi app tối đa một đợt chưa kết toán. Đợt đã tạo không sửa số tiền/số người; khi kết thúc mới có thể tạo đợt tiếp theo. |
| Thời hạn | Không tự đặt lại theo ngày; đợt kéo dài đến khi đủ người hoặc app kết thúc/gỡ bỏ. Tạm dừng app không làm mất ngân sách. |
| Thẻ Trang chủ | Hiện `+N` khi app đang mở, đợt đang hoạt động và còn suất; đủ người/đóng thì ẩn. Đây là thông tin chung của đợt, không bảo đảm tester đang xem còn đủ điều kiện nhận. Trang chi tiết phải giải thích điều kiện và trạng thái của người xem. |
| Thứ tự app | Thưởng thêm chỉ bổ sung thông tin và quyền nhận thưởng; không tự thay đổi thứ tự app hoặc mua lượt Đẩy app. |

Ví dụ: mở đợt +5 cho 20 người, giữ trước 100 Credit. A có báo cáo mới được chấp nhận sẽ nhận thêm 5; A test các ngày sau không lấy thêm suất trong đợt này. B–T đủ điều kiện nhận tiếp; sau 20 người thì dừng cấp và ẩn nhãn. Nếu đợt kết thúc sau 8 người, đã chi 40 và còn 60 cần hoàn sau khi xử lý các báo cáo đang chờ đủ điều kiện.

## 3. Kết quả khảo sát source

Các số dòng là mốc của snapshot nghiên cứu và có thể dịch chuyển khi có chỉnh sửa khác.

| Thành phần | Vị trí hiện tại | Ý nghĩa |
|---|---|---|
| Menu chủ app | `app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt:538–574` | Mục Đẩy app nằm trong nhánh `isActualOwner`; chèn mục mới ngay sau và trước Tạm dừng/Tiếp tục. Phải tách điều kiện để không vô tình hiện hành động tạo thưởng cho app đã kết thúc chỉ vì còn request Đẩy app pending. |
| Tip UI cũ | `OwnerAppDetailScreen.kt:197,1452`; `OwnerAppDetailViewModel.kt:618` | Có state/dialog và hàm `updateTip`; tìm trong file chưa thấy nơi đặt `showTipDialog = true`. Không chỉ bật lại dialog vì thiếu giới hạn người nhận/ngân sách. |
| Tip backend cũ | `supabase/migrations/062_consolidated_baseline.sql:1458,8188` | `_grant_report_tip` trừ ví chủ app khi từng báo cáo được duyệt; không có giới hạn người nhận, không có ngân sách giữ trước; thiếu tiền thì bỏ qua. `set_campaign_tip` chỉ cho mức 0/1/2/3/5/10 và đặt `is_boosted`. |
| Sắp xếp Trang chủ | `app/src/main/java/com/ntt/crosstest/ui/viewmodels/DailyHomeOrder.kt:91` | Comparator đang dùng `isBoosted`, `tipCredits` trong nhóm thường và có nhóm Đẩy app riêng. Dùng lại tip cho thưởng mới sẽ làm thay đổi sắp xếp ngoài yêu cầu. |
| Thẻ app | `app/src/main/java/com/ntt/crosstest/ui/components/CampaignCard.kt` | Vùng bên phải hàng tiêu đề hiện có thể chứa nút gỡ app; phải bố trí nhãn thưởng cùng nút gỡ, không chồng lên tên app. |
| Chỗ dùng thẻ | `HomeScreen.kt:1001,1275` | Component dùng ở nhiều danh sách; truyền tham số rõ nơi cần hiện nhãn, tránh tự bật ở mọi chỗ gọi. |
| Data/model | `app/src/main/java/com/ntt/crosstest/data/Models.kt:61`; `CampaignRepository.kt:103` | `Campaign` chưa có dữ liệu đợt thưởng giới hạn; projection legacy lấy cột tường minh. |
| JSON campaign | `062_consolidated_baseline.sql:1324`, `_campaign_json` | Điểm chung cấp dữ liệu campaign cho nhiều RPC; cần đưa bản tóm tắt thưởng vào dữ liệu trả về. |
| Feed và sync | `082_carryover_flow_audit_fixes.sql:1020`; `085_egress_optimization_sync_v2.sql:94` | `get_home_feed_sync_v2` bọc feed chuẩn bằng token. Metadata thưởng phải tham gia payload/token để thay đổi suất làm feed được cập nhật. |
| Nộp báo cáo | `082_carryover_flow_audit_fixes.sql:1459`, `_submit_tester_report_core` | Điểm hiện hành cần gắn snapshot đợt thưởng vào báo cáo mới; giữ nguyên idempotency, context, carryover và attachments. |
| Duyệt thủ công | `074_app_mission_window_and_multi_attachments.sql:2096`, `_impl_approve_tester_report` | Hiện gọi `_grant_report_tip`; thêm helper thưởng riêng và kết quả trả thưởng vào transaction duyệt. |
| Tự động duyệt | `074_app_mission_window_and_multi_attachments.sql:2548`, `auto_accept_expired_reports` | Hiện cũng gọi tip và settlement nhiệm vụ. Phải tích hợp thưởng mới ở đây, không chỉ sửa nút duyệt. |
| Từ chối | `082_carryover_flow_audit_fixes.sql:2549`, `_impl_reject_tester_report` | Không cấp thưởng cho báo cáo bị từ chối; bảo vệ snapshot khi gửi lại và kiểm tra chính sách đóng đợt. |
| Cache | `CampaignRepository.kt:360,372` | Có invalidate profile gồm credit logs và invalidate campaign/feed/dashboard/detail. Kiểm tra thêm sync snapshots/token để không ghép lại dữ liệu cũ sau mutation. |
| Bảo vệ tài chính | `084_campaign_posting_business_policies.sql:873`, `trg_campaigns_guard_financial_snapshots` | Cần mở rộng bảo vệ nếu thêm summary trên campaign; RLS riêng không ngăn được sửa metadata qua các RPC có quyền cao. |
| Migration mới nhất trong thư mục | `086_next_campaign_candidate_daily_target_priority.sql` | Tạo migration bổ sung ở số còn trống lúc triển khai; không sửa baseline 062 hoặc migration đã áp dụng. |

Không kết luận production có lỗi từ việc đọc source. Trước khi triển khai phải so sánh migration đã áp dụng và định nghĩa hàm thực tế.

## 4. Giao diện và luồng sử dụng

### 4.1. Trang quản lý app

- Thứ tự mục: **Đẩy app → Thưởng thêm cho tester → Tạm dừng/Tiếp tục nhận tester**.
- Dùng `isActualOwner` cho quyền mở tính năng; server vẫn kiểm tra lại chủ sở hữu. Admin đang xem app người khác không được dùng tiền của chủ app để mở đợt.
- Mô tả khi chưa có đợt: “Tặng Credit cho những tester được nhận thưởng đầu tiên”.
- Khi có đợt: “+5 Credit/người · Đã thưởng 8/20 người”; bấm vào mở cùng trang để xem tiến độ, không tạo đợt trùng.
- Chỉ tạo đợt mới khi app đủ điều kiện nhận test và xuất hiện ở danh sách đang mở. App tạm dừng có thể xem tiến độ và hướng dẫn mở lại.

### 4.2. Trang mới

Tên dự kiến: `TesterBonusScreen.kt`, `TesterBonusViewModel.kt`; route `owner_app/{campaignId}/tester_bonus`.

Nội dung theo thứ tự:

1. Tiêu đề **Thưởng thêm cho tester**, nút quay lại.
2. Icon và tên app đang cấu hình.
3. Số dư Credit hiện có.
4. Ô **Credit thưởng cho mỗi tester**: số nguyên dương.
5. Ô **Số người tối đa được nhận đầu tiên**: số nguyên dương.
6. Tổng ngân sách tính trực tiếp, ví dụ **5 × 20 = 100 Credit**; số dư dự kiến sau khi giữ ngân sách.
7. Dòng điều kiện: “Mỗi tester nhận một lần trong đợt, khi báo cáo test mới được chấp nhận. Số người đầu tiên được xác định khi hệ thống ghi nhận thưởng. Ngân sách chưa sử dụng sẽ được hoàn khi đợt kết thúc.”
8. Hai nút **Hủy**, **Đồng ý**.

Hủy/quay lại trước khi gửi chỉ đóng trang, không tạo đợt hay trừ Credit. Đồng ý chỉ bật khi dữ liệu hợp lệ, đủ tiền theo số dư đã tải và backend hỗ trợ; hiển thị loading khi gửi. Server quyết định số dư thực tế. Khi gửi xong nhưng timeout, hiển thị trạng thái đang xác minh và truy vấn theo request ID; quay lại không được hiểu là hoàn tác giao dịch đã gửi.

Khi thành công, quay về quản lý app và cập nhật tiến độ/số dư. Khi lỗi, giữ dữ liệu nhập. Không thông báo thành công trước khi xác minh server đã ghi nhận.

Không tự đặt trần nghiệp vụ 10 Credit hay 12/20 người vì người dùng chưa yêu cầu. Dùng phép tính 64-bit ở Android, kiểm tra giới hạn kiểu dữ liệu server và chống tràn số khi nhân/cộng; trần nghiệp vụ nếu cần sẽ là cấu hình riêng có giải thích trên UI.

### 4.3. Nhãn trên thẻ Trang chủ

- Một hàng nhỏ gồm icon Credit đồng bộ phong cách hiện có và chữ **+5**, cả hai màu cam có độ tương phản phù hợp light/dark theme.
- Canh phải hàng tiêu đề, giữ tên app trong vùng `weight(1f)`. Nếu có nút gỡ thì xếp nhãn phía trên nút gỡ trong cột bên phải hoặc dùng bố cục tương đương đã kiểm tra màn hẹp.
- Không dùng emoji làm biểu tượng Credit. Thêm mô tả trợ năng “Thưởng thêm 5 Credit cho mỗi tester đủ điều kiện”.
- Hiện nhãn tại danh sách app đang mở và kết quả tìm kiếm của danh sách đó; tab tạm dừng/hoàn thành không quảng bá thưởng đang nhận.
- Khi mở chi tiết, hiển thị mức thưởng, còn bao nhiêu suất và điều kiện. Nếu người xem đã nhận trong đợt thì báo “Bạn đã nhận thưởng đợt này”. Không thêm nút nhận thưởng độc lập.
- Không thay đổi thuật toán Đẩy app, hash xáo trộn hoặc ưu tiên gợi ý app.

## 5. Thiết kế backend đề xuất

Các tên dưới đây là tên mới dự kiến, chưa có trong source.

### 5.1. Dữ liệu

**`campaign_tester_bonus_rounds`**: `id`, `campaign_id`, `owner_id`, snapshot tên app/chủ sở hữu, `reward_credits`, `max_recipients`, `paid_count`, `reserved_credits`, `remaining_credits`, `spent_credits`, `refunded_credits`, `status`, `started_at`, `stopped_at`, `closed_at`, `client_request_id`, `request_fingerprint`.

- `reserved_credits = reward_credits × max_recipients`.
- Luôn bảo toàn **reserved = remaining + spent + refunded**; `spent = reward × paid_count`; `0 ≤ paid_count ≤ max_recipients`.
- Trạng thái: `active`, `closing`, `exhausted`, `closed`. `closing` ngăn nhận báo cáo mới vào đợt nhưng còn xử lý báo cáo đủ điều kiện đã nộp.
- Unique partial trên campaign cho trạng thái `active/closing`; unique `(owner_id, client_request_id)` chống tạo/trừ tiền trùng.

**`campaign_tester_bonus_awards`**: `id`, `round_id`, `report_id`, `tester_id`, `amount`, thứ tự cấp thưởng trong đợt, `awarded_at`, liên kết credit log.

- Unique `(round_id, tester_id)`, unique `report_id`, unique `(round_id, award_order)`.
- Giữ lịch sử giao dịch theo retention hiện hành khi app bị gỡ; thiết kế FK/snapshot tránh xóa cascade mất chứng từ tài chính.

**`campaign_tester_bonus_events`** hoặc bảng tài chính tương đương: các sự kiện reserve/payout/refund, số tiền, round, operation ID và credit log để đối chiếu được toàn bộ ngân sách.

**`tester_reports`**: thêm snapshot `tester_bonus_round_id` do server gắn khi tạo báo cáo app_test mới. Snapshot không phải giữ suất; chỉ xác định đợt mà báo cáo có thể được xét. Chỉ gắn nếu đợt active tại thời điểm server chấp nhận báo cáo. Replay, chỉnh sửa và gửi lại báo cáo giữ snapshot ban đầu; không tìm “đợt đang chạy hiện tại” khi duyệt báo cáo cũ.

**Campaign summary**: bản tóm tắt có thể gồm `tester_bonus_round_id`, `tester_bonus_credits`, `tester_bonus_remaining_slots`, `tester_bonus_status`. Ưu tiên cột summary được bảo vệ và cập nhật cùng transaction, để `_campaign_json`, projection legacy và token sync cùng nhận dữ liệu, không tạo một RPC riêng cho mỗi thẻ. Bảng rounds/awards là sổ gốc; summary cần có kiểm tra đối chiếu.

### 5.2. API

| API dự kiến | Trách nhiệm |
|---|---|
| `create_campaign_tester_bonus` | Nhận campaign, mức thưởng, số người, client request ID. Kiểm tra chủ thật, tài khoản, vòng đời app, dữ liệu, số dư; giữ ngân sách, tạo đợt và log trong cùng transaction. |
| `get_campaign_tester_bonus` | Chủ app xem đầy đủ tiến độ/ngân sách; tester chỉ nhận summary công khai và trạng thái nhận thưởng của chính mình. Không trả số dư ví chủ app cho người khác. |
| `reconcile_campaign_tester_bonus` | Xác minh request tạo sau timeout/process death; không tự tạo request mới khi kết quả cũ chưa rõ. |
| `_settle_campaign_tester_bonus(report_id)` | Helper nội bộ cấp thưởng từ ngân sách đã giữ, chỉ cho báo cáo app_test đã approved và snapshot đúng đợt; không trừ ví chủ app lần nữa. |
| `_close_campaign_tester_bonus(campaign_id, reason)` | Đóng nhận mới, bảo toàn quyền xét của báo cáo đã nộp, kết toán/hoàn dư đúng một lần. |

Tạo đợt trả ít nhất `round_id`, request ID, reward, max/paid/remaining slots, reserved budget, số dư ví sau giao dịch và `replayed`. Cùng ID nhưng payload khác trả lỗi conflict. Replay phải được nhận diện trước khi áp các điều kiện tạo mới có thể đã thay đổi.

Mã lỗi tối thiểu: chưa đăng nhập, không phải chủ, tài khoản khóa, app không hợp lệ, đợt đang tồn tại, số nhập không hợp lệ, thiếu Credit, request conflict. Android dịch qua string resources; không hiện tên hàm SQL cho người dùng.

### 5.3. Cấp thưởng và chống tranh chấp

1. Khóa/đọc lại báo cáo, xác nhận approved và đúng `app_test`; snapshot đợt đã được lưu ở lúc tạo.
2. Kiểm tra award tồn tại để replay trả cùng kết quả, không cấp lần hai.
3. Tuần tự hóa thao tác trên cùng đợt; đọc lại số suất còn và trạng thái sau khi đợi khóa.
4. Nếu tester đã nhận hoặc hết suất, trả kết quả không cấp thêm; báo cáo vẫn được duyệt và nhận thưởng thường theo quy tắc hiện hành.
5. Nếu đủ điều kiện, giảm ngân sách đợt, tăng `paid_count/spent`, cộng ví tester, ghi award + financial event + credit log và summary trong cùng transaction. Không có nhánh chỉ tăng ví mà chưa ghi award.
6. Đủ N người thì chuyển exhausted và ẩn nhãn. Việc đóng/dò lại sau đó không phát sinh hoàn tiền đã chi.
7. Thêm khoản thưởng vào kết quả API, tổng thưởng của báo cáo, lịch sử và thông báo; phân biệt rõ thưởng cơ bản, thưởng duyệt, tip cũ, thưởng thêm và nhiệm vụ, tránh cộng hai lần trong `bonus_awarded`.

Gắn helper vào cả `_impl_approve_tester_report` và `auto_accept_expired_reports`, sau khi báo cáo được đánh dấu approved trong cùng transaction. Không để job tự động cấp khác luật với duyệt thủ công. Nếu dùng retry job, unique award và tính bảo toàn ngân sách vẫn bắt buộc.

Phải rà thứ tự khóa của approve/auto-accept/reject, mở đợt, đóng đợt, xóa app và chuyển production trước khi chốt implementation. Các luồng hiện có khóa report/profile khác nhau; không đưa vào một thứ tự campaign → report tùy ý có thể đảo với report → campaign. Helper kết toán/đóng cần theo cùng sơ đồ khóa đã kiểm chứng, hoặc dùng job đóng không giữ khóa campaign khi chờ report. Kiểm thử bằng các kết nối PostgreSQL đồng thời thật.

### 5.4. Quyền ghi và tương thích tip cũ

- Client không được INSERT/UPDATE/DELETE rounds, awards, events hoặc sửa summary/snapshot trên report trực tiếp. Chỉ các RPC có xác thực mới được thực hiện hành động được phép.
- Helper nội bộ không cấp EXECUTE cho PUBLIC/anon/authenticated; dùng search path cố định và định danh schema. Không dựa vào cờ do client tùy ý đặt để chứng minh đã thanh toán.
- Không triển khai bằng cách gán `tip_credits`, `is_boosted` hoặc chỉ đổi nhãn `TipSettingsDialog`.
- Trước triển khai, thống kê app còn tip cũ. Phiên bản đầu không đổi tip cũ, không backfill thành đợt thưởng và không tự tắt tip đang tồn tại. Nếu app có cả hai, các khoản xử lý riêng và thể hiện tách bạch; `+N` chỉ đại diện thưởng mới. Muốn thay thế hoàn toàn tip cũ là một quyết định chuyển đổi riêng.
- Các API client cũ nộp/duyệt báo cáo vẫn đi qua logic mới ở server. Thêm field JSON có default an toàn cho client mới; backend chưa hỗ trợ thì không cho tạo đợt và không giả lập thành công ở local.

## 6. Vòng đời và hoàn ngân sách

| Sự kiện | Xử lý đề xuất |
|---|---|
| Hủy trang trước gửi | Không thay đổi dữ liệu hoặc Credit. |
| Request tạo thất bại | Rollback tất cả: không có đợt dở dang hoặc Credit bị giữ mà không có chứng từ. |
| App tạm dừng, kể cả admin/slot/tự động | Không nhận báo cáo mới theo luật hiện có; giữ đợt và ngân sách, ẩn nhãn. Báo cáo đã nộp hợp lệ vẫn được xét thưởng khi được chấp nhận. Mở lại hợp lệ thì tiếp tục số suất còn. |
| App hoàn thành, lên production, hết cửa sổ hiển thị hoặc bị gỡ | Chuyển đợt closing, chốt `stopped_at`, không nhận thêm báo cáo mới vào đợt. Duyệt/tự động duyệt báo cáo hợp lệ đã nộp trước mốc dừng, sau đó mới hoàn dư. Không hoàn tiền dành cho nghĩa vụ chưa giải quyết rồi lại cố trả thưởng. |
| Còn pending khi đóng | Giữ số ngân sách chưa kết toán cho đến khi các báo cáo liên quan đã kết thúc. Chạy job kết toán định kỳ sau auto-accept; không buộc chủ app đợi trực tiếp trên UI. |
| Báo cáo rejected/gửi lại | Không nhận khi rejected. Sau mốc dừng, việc sửa rồi gửi lại cần chặn gắn quyền mới vào đợt đã đóng; áp đúng luật resubmit và snapshot gốc, không tự kéo dài thời hạn. |
| Đã chi đủ | Exhausted, remaining = 0, không hoàn, không cấp thêm. |
| Hoàn dư | Cộng ví chủ theo số dư thực tế còn lại, ghi log/event có idempotency và đặt remaining về 0 trong cùng transaction. |

Khi gỡ app phải tránh xóa báo cáo pending trước khi giải quyết quyền xét thưởng và ngân sách. Nếu quy trình gỡ hiện tại không giữ được báo cáo, cần chuyển đóng đợt và lưu snapshot nghĩa vụ trước khi xóa; đây là một phần bắt buộc của triển khai, không xử lý bằng FK cascade mặc định. Đóng đợt không tự phê duyệt báo cáo.

## 7. Tích hợp Android, dữ liệu và cache

Các thay đổi dự kiến:

- Thêm `TesterBonusScreen`, `TesterBonusViewModel` và route/callback trong `NavGraph`/`OwnerAppDetailScreen`.
- Thêm model request/result/round summary; repository gọi tạo, đọc, reconcile.
- Lưu request đang chờ theo tài khoản + campaign tương tự ý tưởng `CampaignPushStore`; giữ nguyên ID qua timeout, xoay màn hình và process death. Đổi tài khoản không gửi request của tài khoản trước.
- Thêm metadata có default an toàn vào `Campaign`; cập nhật `_campaign_json` và projection legacy với xử lý tương thích schema cũ. Kiểm tra Home feed, sync v2, dashboard, detail và các fallback dùng cùng dữ liệu summary.
- Mở rộng `CampaignDisplayItem` nếu cần dữ liệu cá nhân; badge dùng summary chung và không tạo N+1 request.
- Tạo `TesterBonusBadge` hoặc thành phần tương đương trong `CampaignCard`; thêm tham số hiển thị tường minh cho các danh sách cần dùng.
- Tạo/thưởng/hoàn thành công phải invalidation đúng domain: profile + credit logs + campaign/feed/dashboard/detail + token snapshots liên quan. Tránh request cũ đang chạy ghi đè summary sau khi mutation đã thành công; tận dụng mutation version/session generation hiện có.
- Quay về Trang chủ làm mới dữ liệu theo cơ chế mutation/resume hiện có; người khác nhìn thấy thay đổi khi refresh/resume theo chính sách cache. Không hứa cập nhật tức thì trên tất cả máy nếu chưa bổ sung realtime.
- Thêm đầy đủ chuỗi cho `values`, `values-vi`, `values-en`; kiểm tra phông lớn, tiếng Anh và dark mode. Không hardcode văn bản tiếng Việt trong Composable.

## 8. Thứ tự triển khai và nghiệm thu

### Giai đoạn 1 — Chốt nghiệp vụ và rà dữ liệu

Xác nhận cách tính người đầu tiên và quy tắc ngân sách ở mục 2; rà migration thực tế, tip cũ, report lifecycle và đường xóa app. Ghi lại sơ đồ khóa hiện tại trước khi thêm khóa mới.

### Giai đoạn 2 — Backend và kiểm thử tài chính

Migration bổ sung tables/constraints/RLS/summary, API tạo/reconcile, snapshot report, helper settle/close, hai luồng duyệt, job hoàn và tích hợp vòng đời. Dùng migration sau 086 nếu số đó vẫn trống lúc triển khai; không chỉnh migration cũ để áp lại.

### Giai đoạn 3 — Android

Triển khai trang nhập và request pending, menu, badge, chi tiết điều kiện, chuỗi dịch, cache và fallback. Server phải hỗ trợ trước khi bật hành động tạo trên client.

### Giai đoạn 4 — Các ca bắt buộc

| Nhóm | Ca nghiệm thu |
|---|---|
| Giao diện | Đúng vị trí dưới Đẩy app; mở đúng app; có hai trường, Đồng ý/Hủy; +5 cam bên phải không đè tên/nút gỡ; TalkBack đọc được ý nghĩa. |
| Đầu vào | Rỗng, 0, âm, thập phân, ký tự, số rất lớn và phép nhân tràn bị chặn; số dư vừa đủ về 0; thiếu 1 Credit bị từ chối. |
| Tạo/idempotency | Bấm hai lần, retry timeout, process death, hai máy cùng tài khoản; cùng ID chỉ giữ tiền một lần; cùng ID khác payload bị từ chối; hai ID cùng app chỉ một đợt đang mở. |
| Phạm vi | Báo cáo cũ, mission, tự test app của mình, rejected, chỉnh sửa/resubmit và client cũ; không tự lấy thưởng ngoài context/snapshot hợp lệ. |
| Trả thưởng | Duyệt thủ công và tự động 8h cùng luật; một người nhiều ngày chỉ một lần/đợt; đủ N thì tester N+1 vẫn nhận thưởng thường nhưng không có thưởng đợt. |
| Đồng thời | Nhiều tester tranh suất cuối; approve và auto-accept cùng báo cáo; hai app của cùng chủ giữ tiền cùng lúc; đóng/gỡ app trong lúc duyệt; không âm ví, không vượt suất, không deadlock. |
| Bảo toàn | Tổng ngân sách ban đầu = đã chi + còn giữ + đã hoàn; log/award/summary khớp; lỗi giả lập giữa các bước phải rollback hoặc retry idempotent theo thiết kế. |
| Vòng đời | Pause/resume, production/completed, hết cửa sổ, gỡ app còn pending, hoàn dư gọi lại nhiều lần; không mất nghĩa vụ hoặc hoàn trùng. |
| Cache/sync | Tạo về Trang chủ thấy +N; hết suất/đóng thì ẩn sau refresh; sync token đổi đúng; dữ liệu trả chậm không ghi đè mutation; fallback không mất nhãn; không N+1 query. |
| Hồi quy | Đẩy app, DailyHomeOrder, tip cũ, thưởng cơ bản/duyệt, mission once/daily, thưởng cập nhật, carryover và thống kê giữ đúng quy tắc của chúng. |

Kiểm thử dự kiến: runner SQL theo mẫu `supabase/tests` cho chức năng/RLS và bài concurrency dùng PostgreSQL thật; Android unit test cho input/request state/mapping/cache, kiểm tra Compose hoặc thiết bị cho bố cục. Không dùng test chỉ tìm chuỗi trong source để kết luận đã an toàn giao dịch. Chỉ chạy test liên quan và hồi quy cần thiết trong môi trường local/staging, không chạy fixture ghi dữ liệu lên production.

### Giai đoạn 5 — Triển khai

Triển khai backend có tương thích client cũ, kiểm tra hợp đồng API, sau đó phát hành client có tính năng. Theo dõi lỗi tạo/thưởng/hoàn và sai lệch ngân sách. Nếu cần dừng nhận đợt mới, vẫn phải duy trì settle/refund cho đợt đã mở; không rollback bằng cách xóa bảng tiền hoặc bỏ helper khỏi luồng duyệt.

## 9. Các điểm cần xác nhận trước khi viết chức năng

1. **Cách tính đầu tiên:** kế hoạch đang đề xuất theo thứ tự được chấp nhận/cấp thưởng. Nếu cần theo thứ tự nộp, phải giữ suất lúc nộp, chờ duyệt, giải phóng khi từ chối và có hàng đợi để người đã nộp sau được đôn lên đúng thứ tự; không chỉ `ORDER BY created_at` lúc duyệt.
2. **Ngân sách:** đề xuất giữ trước toàn bộ, không thu thêm phí và hoàn phần chưa dùng sau khi kết toán.
3. **Tần suất:** đề xuất mỗi người một lần trong một đợt, không phải mỗi ngày; đợt không tự hết lúc 00:00.

Sau khi thống nhất ba điểm này, kế hoạch đã xác định được các thành phần phải sửa, luồng Credit, điều kiện hiển thị và ca nghiệm thu để triển khai.
