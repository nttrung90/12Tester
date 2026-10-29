# Kế hoạch tính năng “Đẩy app”

Ngày nghiên cứu: **29/10/2026**. Phạm vi: nghiên cứu source hiện tại và lập kế hoạch; chưa sửa code sản phẩm, chưa chạy migration hoặc triển khai backend.

## 1. Yêu cầu và hành vi dự kiến

- Thêm mục **“Đẩy app”** trong màn quản lý app của chủ app, ngay **phía trên “Tạm dừng nhận Tester”**.
- Mô tả giữ đúng: **“Trả 100 Credit để đưa ứng dụng của bạn lên đầu danh sách kiểm thử”**.
- Bấm **“Đẩy app”** mở hộp thoại xác nhận để tránh mua nhầm; chưa gửi yêu cầu mua và chưa trừ Credit. Chỉ sau khi người dùng bấm **“Xác nhận đẩy app”** mới gửi yêu cầu. Khi backend xử lý thành công, **trừ ngay đúng 100 Credit** và ghi nhận ưu tiên trong cùng giao dịch.
- Số dư **100** được phép thực hiện, sau đó còn **0**. Số dư dưới 100: không đẩy, không trừ tiền, thông báo thiếu Credit.
- App được đẩy nằm trước mọi app thông thường trong tab **App cần Test/đang mở**, kể cả khi app đã đủ lượt kiểm thử hôm nay.
- Trong nhóm app được đẩy còn hiệu lực, **lượt đẩy thành công sau đứng trên lượt đẩy trước**. Không giữ cố định vị trí số 1 cho người mua trước.
- Hiệu lực chỉ trong **ngày Việt Nam** của giao dịch: hết lúc **00:00 ngày kế tiếp, Asia/Ho_Chi_Minh**; không phải gói 24 giờ.
- Sang ngày mới, không còn ưu tiên của lượt đẩy cũ. Các app trở lại thuật toán sắp xếp/xáo trộn hiện hành, giữ nguyên cấu hình cũ.

Ví dụ: A đẩy lúc 09:00, B đẩy lúc 10:00 → thứ tự B, A, các app khác. A mua lượt mới lúc 11:00 → A, B, các app khác. Cả ba giao dịch hợp lệ đều thu 100 Credit. Sang 00:00, các lượt hôm trước hết hiệu lực.

Các lựa chọn bổ sung sau được đề xuất để triển khai rõ ràng; đây không phải yêu cầu đã được người dùng nêu riêng:

1. Cho mua nhiều lượt trong ngày; mỗi hành động mua mới thu 100 Credit. Không tự áp giới hạn một lần/ngày hoặc thời gian chờ.
2. Chỉ chủ app thật được mua cho app của mình; admin/moderator xem app người khác không được trừ tiền của chủ app.
3. Chỉ mua khi app đang mở và còn đủ điều kiện xuất hiện trong danh sách kiểm thử: không bị khóa, không hết cửa sổ hiển thị, không hết slot nếu loại slot đó có hạn. App tạm dừng cần mở lại trước khi đẩy.
4. Sau khi mua, app bị tạm dừng sẽ không xuất hiện ở danh sách đang mở. Nếu mở lại cùng ngày và vẫn hợp lệ, ưu tiên cũ tiếp tục với thứ tự mua cũ; không tự tạo lượt đẩy mới. Production/completed/bị xóa không được ưu tiên.
5. Không tự hoàn Credit khi có người đẩy sau, chủ tự tạm dừng hoặc ngày kết thúc. Quy tắc này cần hiển thị bằng dòng trợ giúp ngắn, cùng thời hạn trong ngày.
6. Danh sách của người khác cập nhật khi tải/làm mới hoặc quay lại Trang chủ theo cơ chế hiện có. Chưa bổ sung yêu cầu cập nhật tức thời trên mọi máy đang mở màn hình.

## 2. Những gì đã xác nhận trong source

Các vị trí dưới đây là snapshot lúc nghiên cứu; repository đang có thay đổi chưa commit từ công việc khác nên số dòng có thể dịch chuyển.

| Thành phần | Vị trí hiện tại | Ý nghĩa cho tính năng |
|---|---|---|
| Menu quản lý | `OwnerAppDetailScreen.kt:491` bắt đầu nhóm Tạm dừng/Tiếp tục | Chèn mục mới ngay trước nhóm này; không chèn vào menu từng tester |
| Phân quyền màn chủ | `OwnerAppDetailViewModel.kt:47` có `isActualOwner` | Dùng quyền chủ thật; không dùng quyền quản lý rộng của admin |
| Sắp xếp Trang chủ | `DailyHomeOrder.kt`, hàm `sortOpenCampaigns` | Hiện là chưa đủ lượt hôm nay → `isBoosted` → tip giảm dần → SHA-256 theo ngày → ID |
| Boost cũ | `030_credit_hardening_tip_cron.sql:200` | `set_campaign_tip` đặt `is_boosted = (p_tip_credits > 0)`; không tái sử dụng cờ này cho đẩy trả phí |
| Dữ liệu campaign | `Models.kt:61` | Chưa có ngày, hạn hoặc thứ tự của lượt đẩy trả phí |
| JSON campaign | Định nghĩa `_campaign_json` tại `032_admin_campaign_controls.sql:68` | Cần bổ sung metadata lượt đẩy cho feed và các RPC dùng serializer này |
| Truy vấn trực tiếp | `CampaignRepository.kt:97`, `CAMPAIGN_COLUMNS` | Phải bổ sung cột mới, nếu không nhánh fallback sẽ bỏ mất ưu tiên |
| Cache | `invalidateCampaign` tại `CampaignRepository.kt:312`; `invalidateProfile` tại 301 | Có sẵn cơ chế invalidate, nhưng lịch sử Credit có cache riêng cần xóa thêm |
| Qua ngày | `HomeViewModel.kt:217` có scheduler; nhánh đổi ngày khoảng 498 | Tái sử dụng luồng hiện tại và comparator chung, không tạo timer xáo trộn thứ hai |
| Resume/làm mới | `HomeScreen.kt:176` và các callback refresh | Cần nhận metadata mới khi quay về từ quản lý app, kể cả khi feed đang cache |
| Lịch sử Credit | `ProfileScreen.kt:1026` hiển thị `log.note ?: log.action` | Ghi note rõ tên app, chi phí và thời hạn; không cần thêm một màn lịch sử mới |
| Kiểm thử | `DailyHomeOrderTest.kt`, `HomeStatsCycleTest.kt`, runner SQL trong `supabase/tests` | Mở rộng test hành vi, kiểm thử đồng thời trên PostgreSQL thật ở môi trường local/staging |
| Migration | Đã có `048_mission_report_and_expiration.sql` | Dùng số migration còn trống khi triển khai; kế hoạch thưởng cập nhật cũng dự kiến 049 nên không giữ cứng số này |

Source hiện tại đã có một số sửa đổi so với báo cáo review xáo trộn ngày 28/10. Không dùng kết quả FAIL cũ để khẳng định lỗi còn tồn tại; phải chạy kiểm tra lại trên source triển khai.

## 3. Thiết kế dữ liệu và API

### 3.1. Metadata ưu tiên trên campaign

Đề xuất thêm các cột nullable, mặc định null cho app cũ:

- `push_business_date date`: ngày Việt Nam ghi nhận lượt đẩy gần nhất.
- `push_expires_at timestamptz`: 00:00 ngày kế tiếp theo Asia/Ho_Chi_Minh, lưu dưới dạng timestamp có múi giờ.
- `push_order bigint`: thứ tự do backend cấp cho lượt đẩy thành công.

Không thay `is_boosted`, `tip_credits`, `created_at`, ngày hết hạn slot hoặc ngày kết thúc kiểm thử. Thêm ràng buộc metadata cùng null hoặc cùng hợp lệ. Dùng `Long` khi đọc `bigint` ở Android.

Các cột này là tóm tắt lượt gần nhất để đọc nhanh; receipt bên dưới lưu đầy đủ từng giao dịch. Tạo index theo ngày/thứ tự nếu truy vấn thực tế cần; không thêm index dư khi feed vẫn đọc toàn bộ app đang mở.

### 3.2. Receipt và chống tính phí trùng

Tạo bảng `campaign_push_receipts`, tối thiểu có:

`id`, `user_id`, `campaign_id`, `client_request_id`, `cost_credits = 100`, `balance_after`, `business_date`, `pushed_at`, `expires_at`, `push_order`, liên kết bản ghi `credit_logs`.

- Unique `(user_id, client_request_id)`; một request chỉ được gắn với một campaign. Dùng lại request ID với campaign khác trả lỗi.
- Client chỉ đọc receipt của mình, không có quyền INSERT/UPDATE/DELETE. Khách và người khác không đọc được số dư hoặc thông tin giao dịch riêng tư.
- Không xóa receipt theo cron hết ngày. Khi xóa campaign, giữ bằng chứng giao dịch bằng snapshot/khóa ngoại phù hợp với retention; không cascade mất lịch sử thu phí.
- Client tạo UUID trước khi gửi, lưu request đang chờ vào vùng riêng tư gắn với tài khoản và campaign. Timeout, process death hoặc mở lại app vẫn truy vấn/gửi lại **cùng request ID**.
- Chỉ tạo ID mới cho một hành động mua mới rõ ràng sau khi lượt trước đã có kết quả. Không tự retry timeout bằng ID mới.
- Retry receipt đã thành công chỉ trả lại kết quả cũ; không trừ lần hai, không đổi thứ tự, không chuyển hiệu lực sang ngày mới.

### 3.3. RPC mua lượt đẩy

Đề xuất API: `push_campaign(p_campaign_id uuid, p_client_request_id uuid)`. Giá 100 do backend quyết định, không nhận giá, số dư, ngày hoặc thứ tự từ client.

Luồng giao dịch:

1. Yêu cầu phiên đăng nhập; xác định người trả bằng `auth.uid()`.
2. Kiểm tra receipt theo user/request. Nếu đã có và khớp campaign, trả lại receipt cũ trước khi áp điều kiện mua mới.
3. Khóa profile trả tiền và campaign theo thứ tự nhất quán với các RPC liên quan; sau khi chờ khóa phải kiểm tra receipt lần nữa để chống hai request trùng chạy đồng thời.
4. Kiểm tra chủ thật, trạng thái tài khoản và điều kiện hiển thị app ngay trên server. Nếu không hợp lệ, kết thúc mà không thu phí.
5. Kiểm tra số dư đang được khóa; dưới 100 trả `INSUFFICIENT_CREDITS`. Số dư cache trên điện thoại chỉ hỗ trợ UX.
6. Ở đoạn cuối của giao dịch, lấy một khóa giao dịch chung dành riêng cho việc cấp thứ tự đẩy. Sau khi có khóa, đọc `clock_timestamp()` để tính ngày/hạn, kiểm tra lại các điều kiện phụ thuộc thời gian, rồi cấp `push_order` từ sequence. Không dùng `now()` của lúc bắt đầu request sau thời gian chờ khóa.
7. Trừ 100, cập nhật metadata campaign, ghi `credit_logs` với amount `-100`, action `campaign_push`, note và số dư mới; ghi receipt. Tất cả trong cùng transaction.
8. Commit rồi trả kết quả. Nếu lỗi ở bất kỳ bước ghi nào, rollback cả tiền, thứ tự trên campaign, log và receipt. Sequence có khoảng trống sau rollback là chấp nhận được.

Khóa chung ở đoạn cuối bảo đảm thứ tự các giao dịch đẩy đã commit không phụ thuộc thời điểm client bấm hoặc timestamp trùng nhau. Không gọi dịch vụ ngoài hoặc thực hiện công việc dài khi giữ khóa. Hai thao tác đồng thời được phân thứ tự bởi backend; không thể lấy đồng hồ điện thoại để quyết định ai “sau”.

Response tối thiểu: `success`, `receipt_id`, `client_request_id`, `campaign_id`, `charged_credits`, `balance_after`, `business_date`, `expires_at`, `push_order`, `replayed`. `charged_credits` phản ánh giá của receipt, không có nghĩa retry vừa bị trừ thêm; UI dùng `replayed` để tránh thông báo giao dịch mới.

Các lỗi có mã riêng: `NOT_AUTHENTICATED`, `NOT_OWNER`, `ACCOUNT_LOCKED`, `CAMPAIGN_NOT_FOUND`, `CAMPAIGN_NOT_OPEN`, `CAMPAIGN_WINDOW_EXPIRED`, `SLOT_EXPIRED`, `INSUFFICIENT_CREDITS`, `REQUEST_ID_CONFLICT`.

### 3.4. Chống tự đẩy qua API trực tiếp

RLS hiện cho chủ sửa campaign của mình; chỉ thêm cột mới và ẩn trên UI sẽ không đủ.

- Chặn client ghi metadata đẩy ngay từ INSERT và UPDATE; chỉ RPC tin cậy được tạo/cập nhật metadata trả phí.
- Phương án: trigger chạy theo quyền người gọi, phân biệt thao tác trực tiếp `authenticated`/`anon` với RPC `SECURITY DEFINER` có owner tin cậy; kiểm tra cả các RPC hiện có có thể ghi campaign. Không dùng biến client truyền hoặc cờ tùy ý làm bằng chứng đã thanh toán.
- RPC dùng search path cố định, định danh bảng rõ ràng, thu hồi quyền EXECUTE mặc định của PUBLIC/anon và chỉ cấp cho vai trò cần thiết. Người dùng đã đăng nhập vẫn phải qua kiểm tra chủ thật trong hàm.
- Thử trực tiếp quyền ghi profile Credit, credit log, receipt và metadata; không chỉ thử bằng tài khoản quản trị SQL.

## 4. Tích hợp thuật toán Trang chủ

Mở rộng `DailyHomeOrder.sortOpenCampaigns`, sử dụng ngày Việt Nam được truyền vào helper:

1. Chia app thành **được đẩy còn hiệu lực hôm nay** và **app thông thường**. Metadata thiếu/sai/hết ngày không được ưu tiên.
2. Nhóm được đẩy: `push_order DESC`, rồi ID ổn định nếu cần phá hòa.
3. Nhóm thông thường: giữ **nguyên comparator cũ**: chưa đủ lượt hôm nay → Boost cũ → tip giảm dần → daily hash tăng dần → ID.
4. Ghép nhóm được đẩy trước nhóm thông thường. Không để tiêu chí đủ lượt test, Boost cũ hay tip chen lên trước lượt đẩy trả phí.

Hết ngày, toàn bộ app có receipt hôm trước tự trở thành thông thường và đi qua comparator cũ. Không cần cron xóa cột để ưu tiên hết hiệu lực; giữ metadata cũ phục vụ đối chiếu. Nếu có lượt đẩy mới trong ngày mới thì chỉ lượt mới đó còn ưu tiên.

Giữ nguyên hash prefix `home-order-v1`, cách tính hash và thuật toán hai tab tạm dừng/hoàn thành. Danh sách App của tôi/App đã tham gia không đổi thứ tự theo đẩy. Tìm kiếm trong tab đang mở lọc từ danh sách đã sắp đúng để giữ cùng thứ tự.

Mở rộng `Campaign`, `_campaign_json`, `CAMPAIGN_COLUMNS` và kiểm tra toàn bộ fast path/fallback/cache disk để metadata không bị mất. Mặc định null cho JSON cũ; khi backend chưa có tính năng thì ẩn hành động mua, không giả lập thành công ở local.

Metadata nullable chỉ giúp đọc JSON cũ; truy vấn cột mới trên database chưa migrate vẫn có thể lỗi. Cần backend triển khai trước Android và tín hiệu capability rõ ràng; nếu cần hỗ trợ backend cũ phải có đường chọn tập cột cũ khi đúng lỗi thiếu cột, không nuốt mọi lỗi mạng/quyền thành fallback.

## 5. UI, Credit và cache

### Màn chủ app

- Tái sử dụng `OwnerActionItem`, icon phù hợp với đẩy thứ hạng; thêm khả năng disable/loading vì component hiện chưa có trạng thái thao tác riêng cho việc này.
- Hiển thị đúng tiêu đề, mô tả; dòng trợ giúp bổ sung: **“Hiệu lực đến hết hôm nay (giờ Việt Nam). Mỗi lần đẩy tốn 100 Credit.”** Khi đã đẩy có thể hiển thị hạn hiện tại mà vẫn giữ nguyên mô tả yêu cầu.
- Mục mua chỉ dành cho `isActualOwner`. Nếu app đang tạm dừng, hiển thị lý do chưa thể đẩy và yêu cầu tiếp tục nhận tester trước; không tự mở app khi mua.
- Bấm mục **“Đẩy app”** khi đủ điều kiện chỉ mở một hộp thoại, không gọi RPC mua. Tiêu đề: **“Xác nhận đẩy app”**. Nội dung: **“Bạn sẽ trả 100 Credit để đưa ứng dụng lên đầu danh sách kiểm thử đến hết hôm nay (giờ Việt Nam). App được đẩy sau sẽ đứng trên app của bạn.”** Hiển thị thêm tên app, số dư hiện tại và số dư dự kiến sau mua.
- Hộp thoại có **“Hủy”** và **“Xác nhận đẩy app”**. Hủy, bấm ngoài hộp thoại hoặc nút Back trước khi xác nhận chỉ đóng hộp thoại; không gửi yêu cầu và không trừ tiền. Mỗi lượt mua mới, kể cả mua lại trong ngày, đều phải qua xác nhận.
- Khi bấm **“Xác nhận đẩy app”**, kiểm tra lại điều kiện hiện tại rồi khóa hành động đồng bộ ngay trong ViewModel trước khi launch coroutine, tránh hai tap cùng tạo hai UUID. Chỉ lúc này mới tạo/lưu request ID và gửi RPC mua. Hiển thị đang xử lý, vô hiệu nút xác nhận và hủy trong khi chờ kết quả; không cho thao tác tạm dừng/xóa cạnh tranh trên cùng màn lúc mua đang chạy. Đóng màn hoặc process death sau khi gửi không đồng nghĩa hủy giao dịch; tiếp tục xác minh cùng request ID khi trở lại.
- Nếu không đủ tiền, hiện **“Bạn cần ít nhất 100 Credit để đẩy app.”**; không gọi giao dịch mua khi UI đã biết thiếu. Backend vẫn kiểm tra để bảo vệ khi số dư thay đổi trên thiết bị khác.
- Nếu chưa tải được số dư/đang offline, chưa cho mua; không xếp hàng mua tự động khi có mạng trở lại.
- Thành công: **“Đã đẩy app lên đầu danh sách. Đã trừ 100 Credit.”** và thông tin hiệu lực đến hết ngày. Không hứa app tiếp tục ở vị trí 1 sau khi người khác mua lượt mới.

“Trừ ngay” được thực hiện ngay trong giao dịch server khi mua thành công **sau bước xác nhận**, không phải khi mở hộp thoại. UI hiển thị số dư `balance_after` từ response, không tự khẳng định đã trừ tiền trước khi biết kết quả. Timeout sau commit là trạng thái **chưa xác định**, cần xác minh receipt cùng request, không báo chắc chắn mua thất bại rồi cho bấm mua mới. Việc xác minh/gửi lại cùng request đã xác nhận không tạo lượt mua mới và không yêu cầu người dùng xác nhận trả thêm 100 Credit.

### Đồng bộ sau mua

- `CampaignRepository.pushCampaign` gọi RPC, không fallback sang sửa cột/trừ Credit trực tiếp.
- Khi thành công: áp số dư và metadata từ receipt, invalidate profile, campaign/feed, dashboard, lịch sử Credit và snapshot Trang chủ liên quan trong RAM/đĩa. `invalidateProfile()` hiện không tự xóa `cachedCreditLogs`, cần xử lý bổ sung.
- Không hiển thị số dư cũ hoặc thứ tự cũ từ cache sau khi quay về Trang chủ. Invalidation phiên/request hiện có phải ngăn response cũ đang bay ghi đè dữ liệu vừa mua.
- Nếu mua thành công nhưng refresh phụ bị lỗi, giữ thành công và receipt; không gộp lỗi tải lại thành lỗi mua rồi khuyến khích trả thêm 100.
- Giữ guard user/session khi logout hoặc đổi tài khoản lúc request đang chạy. Request pending và receipt không được lẫn giữa tài khoản.
- Qua ngày ở foreground, resume sau nửa đêm, khôi phục snapshot hoặc offline: comparator phải bỏ ưu tiên cũ bằng ngày hiện tại trước khi chờ mạng. Tái sử dụng scheduler/lifecycle đang có, kiểm tra lại ngày sát lúc publish.
- Nếu request/response đi qua nửa đêm, dùng ngày/hạn do backend trả; nếu đã hết hiệu lực khi response tới thì vẫn ghi nhận giao dịch thực tế nhưng không đưa app lên đầu ngày mới.

Thêm resource tiếng Việt/Anh trong `values`, `values-vi`, `values-en`; không hard-code chuỗi trong Compose/ViewModel.

## 6. Kế hoạch thực hiện theo thứ tự

1. Kiểm tra lại source/diff và số migration hiện tại; xác nhận các lựa chọn bổ sung tại mục 1 khi bắt đầu triển khai.
2. Tạo migration riêng cho cột, receipt, quyền/trigger, RPC và serializer. Đồng bộ `full_schema_singapore.sql`; kiểm tra migration nâng cấp lẫn database mới.
3. Viết và chạy test SQL về giao dịch, thiếu tiền, quyền truy cập, replay và rollback. Kiểm thử cạnh tranh bằng nhiều kết nối PostgreSQL local/staging; runner PGlite một kết nối không đủ chứng minh khóa đúng.
4. Thêm model/request/result, API repository và vòng đời request ID bền vững. Tích hợp cache và khả năng nhận diện backend hỗ trợ.
5. Sửa comparator, kiểm thử hết ngày và bảo toàn thứ tự cũ khi không có lượt đẩy còn hiệu lực.
6. Thêm menu, hộp thoại xác nhận bắt buộc, trạng thái loading/lỗi/thành công và bản dịch; nối luồng refresh sau mua.
7. Chạy các ca hồi quy dưới đây, build debug và kiểm thử thiết bị/emulator. Chỉ kết luận đạt theo kết quả chạy mới.
8. Triển khai backend trước, kiểm tra bằng tài khoản thử trên staging, sau đó phát hành Android có tính năng. Client cũ dùng comparator cũ sẽ chưa hiển thị đúng ưu tiên trả phí; cần đưa thay đổi vào chính sách cập nhật ứng dụng trước khi mở bán rộng rãi.

Nếu cần rollback tính năng: tắt khả năng mua mới trước; giữ receipt/log và dữ liệu đã thu phí. Không xóa bảng lịch sử hoặc phát hành client lùi mất hỗ trợ ưu tiên khi lượt trả phí trong ngày còn hiệu lực.

## 7. Ma trận nghiệm thu

| Nhóm | Ca bắt buộc | Kết quả mong đợi |
|---|---|---|
| Giao diện | Mở quản lý app của chính mình | Đúng nhãn/mô tả; mục nằm ngay trên Tạm dừng |
| Xác nhận | Bấm Đẩy app; Hủy/bấm ngoài/Back trước xác nhận | Chỉ mở/đóng hộp thoại; không gọi RPC mua, không tạo receipt/log hoặc trừ Credit |
| Xác nhận mua | Bấm Xác nhận đẩy app; mua lại trong ngày | Chỉ gửi sau xác nhận; mỗi lượt mua mới phải xác nhận lại, hiển thị rõ 100 Credit và thời hạn |
| Điều kiện thay đổi | Số dư/trạng thái app đổi khi hộp thoại đang mở | Kiểm tra lại lúc xác nhận và ở backend; không mua nếu không còn hợp lệ |
| Giá | Số dư 99 / 100 / 150 | Lần lượt thất bại không thay đổi / thành công còn 0 / thành công còn 50 |
| Thứ tự | A mua, B mua sau; A mua lại | B đứng trước A; sau lượt mới A đứng trước B; thu 100 cho mỗi lượt mới |
| Ưu tiên | App được đẩy đã đủ lượt hôm nay, app khác chưa đủ/có tip/Boost cũ | App được đẩy vẫn đứng trước các app thông thường |
| Luật cũ | Không có lượt hôm nay hoặc tất cả hết hạn | Thứ tự bằng thuật toán cũ trên cùng dữ liệu/ngày; các tab khác không đổi luật |
| Ngày | Mua 23:59:59; sang 00:00 | Hết ưu tiên tại ngày mới; không kéo dài thêm 24 giờ, không tự thu tiền mới |
| Qua ngày | Foreground, background/resume, offline, restore snapshot | Bỏ ưu tiên hôm qua trước khi chờ mạng; không phụ thuộc có app ở các tab khác |
| Idempotency | Double tap nút xác nhận, timeout sau commit, gửi lại, process death | Một receipt, một log -100, một lần trừ, một vị trí; retry giữ nguyên thứ tự, không tạo lượt mua mới |
| Replay muộn | Retry request hôm trước vào hôm sau; campaign sau đó đã dừng/xóa | Trả receipt lịch sử đúng quyền; không mua lại hoặc khôi phục ưu tiên |
| Lỗi giữa giao dịch | Ép lỗi ghi log/receipt/cập nhật campaign | Rollback toàn bộ; không có tiền mất hoặc lượt đẩy miễn phí |
| Cạnh tranh tiền | Hai thiết bị mua đồng thời khi chỉ còn 100; mua cùng lúc chi phí nhiệm vụ/slot/tip | Chỉ giao dịch đủ số dư được commit, không âm tiền hoặc mất cập nhật |
| Cạnh tranh thứ tự | Hai chủ mua đồng thời; một request chờ khóa qua nửa đêm | Backend phân thứ tự nhất quán; ngày/hạn tính sau chờ khóa |
| Quyền | User khác, guest, admin xem app khác, account locked | Không tạo lượt mới, không trừ tiền người khác |
| Chống giả mạo | Ghi thẳng metadata ở INSERT/UPDATE, sửa receipt/log hoặc gọi giá 0 | Bị từ chối; sửa tip/Boost cũ không tạo lượt đẩy trả phí |
| Lifecycle app | Tạm dừng, production, completed, hết slot, hết 30 ngày, xóa app | Chặn mua nếu không hợp lệ; lọc đúng tab; không mất audit khi xóa |
| Dữ liệu/cache | Fast RPC, fallback, cache RAM/đĩa, tìm kiếm, response cũ đang bay | Cùng metadata/thứ tự, số dư và log mới; response cũ không ghi đè |
| Tương thích | Backend chưa migrate, client cũ, nâng cấp dữ liệu cũ | Không giả lập mua thành công; có kế hoạch cập nhật client trước mở bán |
| Đổi tài khoản | Logout/login user khác lúc mua chưa rõ kết quả | Không hiển thị tiền/receipt/thông báo của tài khoản cũ |

Lệnh Android dự kiến: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, lọc test trong quá trình phát triển khi cần. Thêm runner SQL riêng `supabase/tests/run-campaign-push-test.mjs` và đưa vào lệnh kiểm tra phù hợp; không coi `npm test` hiện tại đã tự bao phủ các runner mới.

Điều kiện hoàn thành: các ca bắt buộc đạt trên source cuối, có bằng chứng kiểm thử cạnh tranh thật, build thành công, metadata có ở mọi đường đọc, hết ưu tiên tự động theo ngày và Credit có thể đối soát bằng receipt/log.
