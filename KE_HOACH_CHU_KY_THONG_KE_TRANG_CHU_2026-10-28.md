# Kế hoạch cấu hình chu kỳ thống kê lượt test tại Trang chủ

Ngày nghiên cứu: 28/10/2026. Trạng thái: **nghiên cứu mã nguồn và lập kế hoạch; chưa sửa chức năng, chưa chạy migration hoặc thay đổi Supabase live**.

## 1. Yêu cầu và phạm vi

- Admin chọn một trong ba chu kỳ **1 ngày / 2 ngày / 3 ngày**, áp dụng chung cho thống kê các app tại Trang chủ. Mặc định 1 ngày để giữ hành vi hiện tại.
- Trong chu kỳ 2 hoặc 3 ngày, số lượt test của từng ngày được cộng vào cùng một bộ đếm. Sang chu kỳ kế tiếp, bộ đếm bắt đầu từ 0.
- Người test vẫn test từng ngày theo cơ chế hiện tại. Cùng một người test cùng app vào hai ngày khác nhau đóng góp **2 lượt**, không gộp thành 1 người duy nhất trong cả chu kỳ.
- Chuỗi hiển thị vẫn giữ nguyên, ví dụ `4/12 hôm nay · 10 người đã cài`. Không thêm chữ “chu kỳ”, “cộng dồn”, “2 ngày”, “3 ngày” hay thông báo giải thích cho người dùng thông thường.
- Giữ mẫu số theo `campaign.dailyTarget` hiện tại: app có mục tiêu 12/15/20 vẫn dùng đúng mục tiêu đó. Không nhân mẫu số theo số ngày và không chặn tử số ở mức mẫu số; 14 lượt thì hiển thị `14/12 hôm nay`.
- Chỉ thay số tổng hợp trên thẻ app ở Trang chủ, bao gồm các danh sách dùng cùng thẻ và kết quả tìm kiếm. Thống kê thật theo ngày ở chi tiết app, app của tôi, nhiệm vụ và các màn khác giữ nguyên.
- Giữ nguyên quyền test, chống nộp trùng trong ngày, ảnh báo cáo, duyệt/từ chối, Credit, tip, phạt, nhiệm vụ, xếp hạng, vòng đời campaign và thứ tự ưu tiên hiện tại.

**Quy ước đề xuất cho các chi tiết chưa được nêu trong yêu cầu:** cấu hình dùng chung toàn hệ thống; ngày tính theo `Asia/Ho_Chi_Minh`; admin lưu thay đổi thì áp dụng từ 00:00 ngày Việt Nam kế tiếp. Đây là lựa chọn thiết kế của kế hoạch, chưa phải hành vi đã triển khai. Không thêm cấu hình riêng cho từng app trong đợt này.

## 2. Kết quả rà soát mã nguồn

| Vị trí | Hiện trạng và tác động |
| --- | --- |
| `supabase/migrations/020_query_optimization.sql:103` | `get_home_feed()` tính `today_tested_count` bằng `count(distinct user_id)` với `checkin_date = v_today`. Có trả riêng `has_tested_today`. |
| Cùng migration, dòng 617 | `get_campaigns_stats(uuid[])` tính thống kê tương tự; được dùng khi tải bổ sung và ở các đường fallback. |
| `supabase/migrations/001_init.sql:51` | Bảng `checkins` có UNIQUE `(campaign_id, user_id, checkin_date)`: một người / một app / một ngày có tối đa một dòng. |
| `supabase/migrations/020_query_optimization.sql:33` | Đã có index `(campaign_id, checkin_date)`, phù hợp truy vấn khoảng ngày theo app. |
| `supabase/migrations/045_thumbnail_support.sql:514` | Nộp báo cáo có thể tạo check-in tạm tính ngay; không cần đợi duyệt để xuất hiện trong bộ đếm hiện tại. |
| `supabase/migrations/037_reward_10_credits.sql:671` | Khi từ chối, check-in tạm tính tương ứng có thể bị xóa; thống kê phải phản ánh lại dữ liệu còn hiệu lực. |
| `app/src/main/java/com/ntt/crosstest/data/Models.kt:810` | `CampaignStats` dùng chung cho Home và các màn khác. Đổi nghĩa trường cũ sẽ lan phạm vi. |
| `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt:1607` | `getCampaignsStatsMap()` ưu tiên RPC, fallback truy vấn check-in của riêng hôm nay; cache có ràng buộc ngày nghiệp vụ. |
| Cùng file, dòng 1691 | `getHomeFeed()` nạp và chia sẻ cache stats với các luồng khác. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt:47` | `CampaignDisplayItem.todayTestedCount` vừa phục vụ hiển thị vừa tính `isTargetReached`. |
| Cùng file, dòng 549 và `HomeScreen.kt:788` | `isTargetReached` điều khiển sắp xếp và hộp xác nhận trước khi vào app đã đủ chỉ tiêu. Không được vô tình chuyển sang số cộng dồn. |
| `app/src/main/java/com/ntt/crosstest/ui/components/CampaignCard.kt:199` | Thẻ dùng cùng số để in thống kê và hiển thị nhãn “Đủ chỉ tiêu”. Cần tách đầu vào hiển thị khỏi kiểm tra chỉ tiêu hằng ngày. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt:1167` | Danh sách app đã tham gia gọi thẻ bằng tham số riêng; phải truyền thêm số hiển thị, tránh sót đường này. |
| `app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt:56` | `sanitizeForDate()` đưa số ngày về 0 khi đọc cache khác ngày. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt:659` | Có xử lý request đi qua nửa đêm và nhánh lỗi đưa số ngày về 0; thêm số mới phải xử lý cả hai nhánh. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt:173` | Hiện refresh khi resume; chưa có cơ chế tự refresh đúng nửa đêm khi màn hình vẫn mở liên tục. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt` và `ui/viewmodels/AdminUsersViewModel.kt` | Đã có Bảng quản trị viên và mẫu tải/lưu cấu hình phiên bản; có thể bổ sung mục riêng cho thống kê. |
| `supabase/migrations/018_app_version_and_update_config.sql:30` | `app_configs` cho mọi người đọc. Không đưa cấu hình thống kê nội bộ vào bảng này. |
| `app/src/main/res/values-vi/strings.xml:419` | Chuỗi hiện tại là `%1$d/%2$d hôm nay · %3$d người đã cài`; giữ nguyên chuỗi và bản Anh. |

“Reset hằng ngày” hiện chủ yếu là thay điều kiện ngày khi truy vấn và dọn số ngày trong cache; **không phải job xóa lịch sử check-in lúc 00:00**. Vì vậy không cần job reset mới hay cập nhật lại lịch sử test.

Kết quả trên dựa trên checkout local, gồm migration tới 046; chưa xác nhận migration nào đã được áp dụng trên database live. Workspace đang có nhiều sửa đổi cache/media và file chưa commit; khi triển khai phải giữ các sửa đổi này, kiểm tra diff theo từng file và không ghi đè bằng bản từ HEAD.

## 3. Quy tắc chu kỳ và cách đếm

### 3.1. Chu kỳ cố định, không dùng cửa sổ trượt

Với ngày bắt đầu chính sách là `A`, độ dài chu kỳ `N` và ngày Việt Nam hiện tại `D`:

```text
period_start = A + floor((D - A) / N) * N
period_end_exclusive = period_start + N
home_tested_count = số dòng checkins của app
                    có period_start <= checkin_date <= D
```

Chỉ đếm đến hôm nay, không đếm bản ghi mang ngày tương lai. Khi sang `period_end_exclusive`, hàm tự chọn khoảng mới. Không ghi 0 vào DB, không xóa check-in, không cần cron để chu kỳ hoạt động.

Ví dụ chính sách 3 ngày có hiệu lực từ 29/10/2026:

| Ngày Việt Nam | Lượt mới trong ngày | Hiển thị sau các lượt đó |
| --- | ---: | --- |
| 29/10 | 4 | `4/12 hôm nay` |
| 30/10 | 3 | `7/12 hôm nay` |
| 31/10 | 2 | `9/12 hôm nay` |
| 01/11 lúc 00:00, chưa có lượt mới | 0 | `0/12 hôm nay` |
| 01/11 sau 5 lượt | 5 | `5/12 hôm nay` |

Chu kỳ 2 ngày tương tự: ngày 1 và ngày 2 cộng dồn, đầu ngày 3 bắt đầu từ 0. Chu kỳ 1 ngày khớp hiện tại. Đây không phải “luôn cộng 3 ngày gần nhất”, vì cửa sổ trượt sẽ không có lần reset về 0 đúng yêu cầu.

### 3.2. Nguồn dữ liệu giữ nguyên

- Dùng các dòng `checkins` đang tồn tại, như bộ đếm hiện hành. Không tự chuyển sang chỉ đếm báo cáo approved.
- Đếm `count(*)` trong khoảng ngày là đủ nhờ UNIQUE hiện có. Cũng có thể cộng số tester riêng của từng ngày; **không dùng `count(distinct user_id)` trên toàn khoảng nhiều ngày**.
- Pending có check-in tạm tính thì được tính; từ chối làm mất check-in tạm tính thì số tương ứng giảm. Duyệt/auto-duyệt giữ cùng dòng thì không cộng thêm. Nộp lại vẫn theo chống trùng hiện tại.
- Không tăng thủ công một bộ đếm cố định sau mỗi sự kiện, tránh cộng trùng khi retry và tránh phải đồng bộ giảm khi từ chối. Thống kê được tính lại từ dữ liệu nguồn sau mutation/refresh.
- Không sửa quy tắc ngày check-in của các RPC báo cáo trong phạm vi tính năng này.

### 3.3. Đổi cấu hình giữa chu kỳ

- Khi lưu ngày D, chu kỳ hiện tại tiếp tục đến hết ngày D; cấu hình mới bắt đầu từ D+1 lúc 00:00 VN và lấy D+1 làm mốc chu kỳ mới.
- Không kéo lượt của các ngày trước mốc mới sang chính sách mới. Không tính lại số đã hiển thị trong ngày đang diễn ra.
- Ví dụ 30/10 đang dùng chu kỳ 3 ngày, admin chọn 2 ngày: ngày 30/10 giữ số hiện tại; 31/10 bắt đầu chu kỳ 2 ngày từ 0; 02/11 bắt đầu chu kỳ tiếp theo.
- Lưu lại cùng giá trị đang có hiệu lực là no-op, không đặt lại mốc chu kỳ. Nếu có lịch ngày mai và admin chọn lại giá trị đang chạy, hủy lịch chưa hiệu lực đó, giữ mốc cũ.
- Admin thấy rõ cấu hình đang chạy, cấu hình chờ áp dụng và thời điểm áp dụng trong Bảng quản trị viên.

## 4. Thiết kế backend đề xuất

### 4.1. Cấu hình nội bộ

Tạo migration mới, dự kiến `047_home_stats_cycle.sql` nếu số này còn trống khi bắt đầu triển khai. Không chỉnh đè migration cũ.

Tạo bảng riêng `home_stats_policies` gồm `effective_from` (date, UNIQUE), `cycle_days` (NOT NULL, CHECK IN (1,2,3)), người thao tác và thời điểm tạo/cập nhật. Seed chính sách nền chu kỳ 1 ngày với mốc cố định trong quá khứ; migration chạy lại không được ghi đè cấu hình đã có. Giữ các chính sách đã có hiệu lực bất biến để mốc chu kỳ không thay đổi bất ngờ.

Hai RPC admin dự kiến:

- `admin_get_home_stats_config()`: trả chính sách đang chạy và thay đổi chờ áp dụng.
- `admin_set_home_stats_cycle(p_cycle_days, p_expected_revision)`: kiểm tra NULL/giá trị ngoài 1–3, tự xác định ngày mai bằng giờ server VN, cập nhật lịch chưa hiệu lực trong transaction và trả cấu hình đã lưu.

Khóa thao tác lưu và kiểm tra revision để hai admin không âm thầm ghi đè nhau. Ghi audit trước/sau kèm `auth.uid()`; chính sách đang chạy không được chỉnh hồi tố. Admin không cần tự nhập ngày hoặc thao tác Supabase SQL Editor.

Phân quyền:

- Mọi RPC admin kiểm tra đăng nhập và `profiles.role = 'admin'` ở server, kể cả khi client đã ẩn nút.
- Member, moderator, khách không được đọc/sửa bảng hoặc audit. Bật RLS, thu hồi quyền mặc định không cần thiết; không chỉ dựa vào ẩn UI.
- Hàm `SECURITY DEFINER` đặt `search_path` rõ ràng; helper tính chu kỳ chỉ dùng nội bộ, không cấp quyền gọi trực tiếp cho client thông thường.
- Không thêm `cycle_days`, ngày bắt đầu/kết thúc chu kỳ hoặc cấu hình đang chờ vào RPC feed công khai. Client thường chỉ nhận số đã tổng hợp và ngày của dữ liệu.

### 4.2. Thống kê bổ sung, không đổi nghĩa trường cũ

Giữ nguyên `today_tested_count`, `has_tested_today`, `installed_count` và chữ ký RPC cũ. Bổ sung vào `CampaignStats`:

- `home_tested_count`: số dùng để in trên thẻ tại Trang chủ.
- `stats_date`: ngày VN mà server đã dùng để tính dữ liệu, phục vụ kiểm tra freshness và request qua nửa đêm. Đây chỉ là ngày dữ liệu, không phải thông tin chu kỳ.

`get_home_feed()` và `get_campaigns_stats(uuid[])` dùng chung helper chọn chính sách/khoảng ngày, chụp cùng một ngày server cho toàn bộ response. Chu kỳ 1 ngày phải cho `home_tested_count == today_tested_count`.

`get_campaign_detail`, `get_my_dashboard`, thống kê owner, nhiệm vụ và các RPC submit/review không thay nghĩa. Thêm trường vào JSON không được làm client cũ lỗi decode; cần kiểm tra bằng model/decoder thực tế, không chỉ giả định.

Tận dụng index `(campaign_id, checkin_date)` sẵn có, tổng hợp theo lô các campaign được trả về. Kiểm tra kế hoạch truy vấn trên dữ liệu gần thực tế; chỉ thêm index nếu có bằng chứng cần thiết. Không tải toàn bộ check-in nhiều ngày xuống Android và không tạo request riêng cho từng app.

## 5. Thiết kế Android đề xuất

### 5.1. Tách số hiển thị và số nghiệp vụ

- `Models.kt`: thêm trường có default tương thích vào `CampaignStats`; model cấu hình admin và tham số RPC riêng, không ghép vào `AppConfig`.
- `CampaignRepository.kt`: truyền thêm dữ liệu RPC vào cache hiện có và bổ sung phương thức đọc/lưu cấu hình admin. Không thay nguồn tính Credit/nhiệm vụ.
- `HomeViewModel.kt`: truyền `homeTestedCount` và ngày dữ liệu qua `CampaignDisplayItem`, các danh sách, tìm kiếm, khôi phục snapshot và `joinedStatsMap`.
- `CampaignCard.kt`: dùng số mới chỉ cho dòng `%1$d/%2$d hôm nay`; giữ kiểm tra “Đủ chỉ tiêu” theo `todayTestedCount` thật.
- `HomeScreen.kt`: truyền số mới tại tất cả chỗ gọi thẻ, gồm app đã tham gia. Giữ sắp xếp, hộp xác nhận đủ chỉ tiêu và quyền vào test theo số riêng hôm nay.
- `MyAppCard`, chi tiết tester/owner, thanh nhiệm vụ, Credit và bảng xếp hạng không chuyển sang số cộng dồn.

Ví dụ tổng chu kỳ là 13 nhưng hôm nay mới 2 lượt: thẻ in `13/12 hôm nay`, còn thứ tự ưu tiên/hộp xác nhận/nhãn đủ chỉ tiêu vẫn theo 2 lượt của hôm nay. Đây là hệ quả của yêu cầu giữ nguyên các chức năng khác; không dùng số hiển thị mới để chặn test.

### 5.2. Cache và chuyển ngày

Không giữ nguyên số nhiều ngày bằng cách bỏ toàn bộ kiểm tra ngày, và không đưa số mới về 0 mỗi nửa đêm như trường cũ.

- Tiếp tục vô hiệu hóa các giá trị theo ngày khi sang ngày mới: `hasTestedToday`, số test riêng hôm nay, nhiệm vụ ngày và các thống kê owner.
- Số hiển thị cộng dồn phải được tải lại từ server cho ngày mới. Phân biệt rõ ba trạng thái: server cũ không hỗ trợ trường mới, dữ liệu mới hợp lệ và dữ liệu đã hết hạn.
- Nếu snapshot khác ngày hoặc response có `stats_date` cũ, không xuất bản số đó thành số của hôm nay, cũng không tự kết luận số cộng dồn là 0. Chỉ để phần số thống kê ở trạng thái loading/chưa có dữ liệu theo cách trình bày sẵn có, rồi thay bằng kết quả hợp lệ.
- Khi offline qua ngày mới, chưa thể biết số chính xác nếu không nhận thông tin chu kỳ; phần số hết hạn giữ trạng thái chưa có dữ liệu tới khi đồng bộ được. Không thêm giải thích chu kỳ vào UI và không hiển thị một số 0 giả.
- Bổ sung refresh khi đổi ngày trong lúc Home đang foreground; vẫn refresh khi resume. Tránh polling liên tục, chỉ lên lịch theo ranh giới ngày và hủy theo vòng đời màn hình.
- Request bắt đầu trước 00:00 và hoàn tất sau 00:00 phải xét ngày dữ liệu server; response ngày cũ bị loại và lên lịch refresh có kiểm soát. Không để request cũ thắng request mới.
- Rà cả RAM, snapshot đĩa, nhánh thành công, nhánh lỗi, tìm kiếm, đổi tài khoản và khởi động offline. Giữ generation/mutation guards đang có trong các sửa đổi cache hiện tại.
- Thành công submit/review/reject vẫn invalidate và refresh feed/stats; không tự cộng thêm vào số tổng bằng optimistic update dễ bị trùng.

Cách này không cần phát cấu hình chu kỳ xuống client thường. Đổi lại, cache khác ngày cần mạng để xác nhận số mới, thay vì cố suy ra chu kỳ trong Android.

### 5.3. Bảng quản trị viên

Thêm mục riêng “Thống kê Trang chủ” trong `AdminUsersScreen`, không nhét vào hộp cấu hình cập nhật phiên bản:

- Ba lựa chọn 1 ngày / 2 ngày / 3 ngày; thể hiện giá trị đã lưu và thay đổi chờ áp dụng.
- Giải thích phạm vi và thời điểm áp dụng chỉ trong mục admin này.
- Nút lưu có trạng thái đang xử lý, chặn bấm lặp; chỉ báo thành công sau khi server xác nhận. Lỗi lưu không đổi giá trị đang chạy trên UI.
- `AdminUsersViewModel` quản lý tải/lưu/error/revision riêng; không dùng chung tín hiệu lưu cấu hình phiên bản.
- Chỉ tải cấu hình sau khi xác nhận tài khoản admin. Xóa state nội bộ khi đổi tài khoản hoặc rời phiên; member/moderator không thấy mục này.
- Thêm resource VI/EN cho giao diện admin; giữ nguyên các chuỗi thống kê hiện tại của tester. Không gửi broadcast, notification hoặc thêm hướng dẫn ngoài khu vực admin.

### 5.4. Tương thích backend và APK cũ

- Client mới gặp backend cũ thiếu trường mới: tiếp tục dùng số hằng ngày như hiện tại.
- Backend mới + client mới: ưu tiên số mới; đường fallback `get_home_feed` → `get_campaigns_stats` cũng phải nhận số mới.
- Nếu cả hai RPC đều thiếu trên backend cũ, fallback legacy giữ cơ chế hằng ngày; không tải cấu hình nội bộ xuống client để tự cộng.
- Nếu RPC lỗi mạng/quyền/lỗi thực thi, không coi là “thiếu RPC” để âm thầm rơi về số 1 ngày. Giữ xử lý lỗi/cancellation đúng như repository hiện tại.
- APK cũ đọc trường cũ nên vẫn hiện số hằng ngày. **Cần cập nhật Android một lần để nhận chức năng mới.** Sau đó admin đổi 1/2/3 ngày không cần phát hành lại APK.
- Không tự bật cưỡng chế cập nhật hoặc đổi chính sách phiên bản trong đợt này. Trước khi bật chu kỳ nhiều ngày, xác định phiên bản client đang được hỗ trợ để tránh hiểu nhầm các APK sẽ hiển thị đồng nhất.

## 6. Trình tự triển khai

1. Kiểm tra diff hiện tại và schema thực tế trên môi trường thử nghiệm, đặc biệt định nghĩa RPC mới nhất, UNIQUE/index và quyền bảng; không dùng README/schema gộp cũ làm nguồn duy nhất.
2. Viết migration mới: cấu hình riêng, quyền, audit/revision, RPC admin và trường tổng hợp bổ sung. Mặc định 1 ngày.
3. Kiểm thử SQL độc lập với Android: công thức chu kỳ, tổng lượt theo ngày, dữ liệu pending/rejected, quyền và thay đổi chính sách.
4. Bổ sung model/repository, phân biệt số hiển thị với số nghiệp vụ và xử lý cache qua ngày.
5. Bổ sung giao diện admin và truyền số mới qua tất cả đường render Trang chủ.
6. Build/kiểm thử Android, kiểm tra trực quan VI/EN, snapshot cũ và kết nối yếu.
7. Triển khai backend ở 1 ngày, phát hành client mới, sau đó thử cấu hình 2/3 ngày trên nhóm thử nghiệm trước khi áp dụng thực tế. Database live và phát hành app là bước triển khai riêng, chưa thực hiện trong nghiên cứu này.

## 7. Kiểm thử và tiêu chí nghiệm thu

| Nhóm | Ca bắt buộc |
| --- | --- |
| Mặc định | Cấu hình 1 ngày cho kết quả và hành vi như trước; chưa có lượt thì 0. |
| 2 ngày | D1=4, D2=3 → 4 rồi 7; đầu D3=0. Không mang lượt D1 sang chu kỳ mới. |
| 3 ngày | D1=4, D2=3, D3=2 → 4,7,9; đầu D4=0. |
| Cùng người nhiều ngày | Một tester test cùng app ở ba ngày → 3 lượt; retry/cùng ngày không tăng trùng. |
| Mẫu số | App mục tiêu 12/15/20 giữ mẫu số; tổng vượt mục tiêu vẫn hiển thị đầy đủ. |
| Tính hợp lệ | Pending có check-in được tính; reject làm mất check-in thì giảm; duyệt, auto-duyệt, resubmit không cộng trùng. |
| Ranh giới | 23:59:59/00:00 VN, chuyển tháng/năm; thiết bị dùng múi giờ khác không làm lệch ngày server. |
| Đổi cấu hình | 1→3, 3→2, 2→1 giữa chu kỳ, cùng giá trị, sửa/hủy lịch ngày mai, lưu sát 00:00. Không đổi số giữa ngày ngoài sự kiện dữ liệu thật. |
| Phân quyền | Khách/member/moderator không đọc được cấu hình/audit và không sửa được bằng RPC hoặc bảng trực tiếp; admin thao tác hợp lệ. Giá trị NULL/0/4/âm bị từ chối. |
| Đồng thời | Hai admin sửa cùng revision: một thay đổi thành công, bên còn lại nhận xung đột và tải lại; không tự thay lịch đã có hiệu lực. |
| UI nghiệp vụ | Số cộng dồn >=12 nhưng số hôm nay <12 không làm đổi thứ tự ưu tiên, hộp xác nhận, nhãn theo ngày hoặc quyền test. |
| Bao phủ Trang chủ | Mở/tạm dừng/hoàn thành, tìm kiếm và app đã tham gia dùng đúng số mới; app của tôi/chi tiết vẫn dùng số ngày cũ. |
| Cache | RAM, cold start, snapshot thiếu trường mới, đổi tài khoản, offline qua ngày, reconnect, Home mở liên tục qua nửa đêm, request trả ngược thứ tự. |
| Tương thích | APK/decoder cũ đọc payload thêm trường; client mới với backend cũ; fallback stats; lỗi mạng không biến chu kỳ thành 1 ngày. |
| Hồi quy | Mỗi ngày vẫn test được theo luật hiện tại; nhiệm vụ, Credit/tip/phạt, duyệt/auto-duyệt, xếp hạng và vòng đời app không thay đổi. |
| Hiệu năng | Query theo index và theo lô; không thêm lượt gọi theo từng app; không cần cron reset hay tải check-in hàng loạt xuống client. |
| Nội dung | Tester vẫn thấy chuỗi “.../12 hôm nay”; không có mô tả chu kỳ trong dialog, guide, snackbar, notification hoặc màn công khai. |

Ưu tiên test hành vi SQL và test cache/chuyển ngày có clock kiểm soát; bổ sung kiểm tra Compose/integration cho dữ liệu hiển thị và quyền admin. Không coi test chỉ tìm chuỗi trong source là bằng chứng đủ cho reset qua nửa đêm. Khi triển khai, chạy bộ test liên quan và build debug bằng Gradle; ghi rõ kết quả thực chạy và các giới hạn môi trường.

## 8. Hoàn tác

- Hoàn tác nghiệp vụ thông thường: admin đặt lại 1 ngày, hiệu lực từ 00:00 ngày kế tiếp theo cùng quy tắc; không sửa lịch quá khứ hoặc xóa check-in.
- Trước khi bật 2/3 ngày, backend ở 1 ngày cho phép kiểm tra tương thích mà không đổi kết quả.
- Nếu client mới có lỗi, backend vẫn giữ trường số ngày cũ để APK cũ hoạt động. Không xóa bảng/cột hoặc thay chữ ký RPC đang có trong bản hoàn tác.
- Sau khi triển khai thử nghiệm, cập nhật tài liệu này bằng báo cáo SQL/Android/kiểm tra UI thực tế. Hiện chưa có kết quả kiểm thử chức năng mới vì đây là bước lập kế hoạch.
