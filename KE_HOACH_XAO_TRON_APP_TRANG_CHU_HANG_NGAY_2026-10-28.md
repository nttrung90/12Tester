# Kế hoạch luân phiên ngẫu nhiên app trên Trang chủ mỗi ngày

Ngày nghiên cứu: **28/10/2026**.

Trạng thái: **đã nghiên cứu source và lập kế hoạch; chưa sửa chức năng Android, chưa thay đổi database hoặc triển khai live**.

## 1. Yêu cầu và quyết định

- Người dùng đã chọn **luân phiên ngẫu nhiên mỗi ngày** cho các app hiển thị trên Trang chủ.
- Luồng đăng app, điều kiện đăng, slot, Credit, tip, Boost, tham gia test, nộp/duyệt báo cáo, nhiệm vụ và các luồng khác giữ nguyên.
- Phạm vi đề xuất: danh sách app ở ba tab con của Trang chủ: đang mở, tạm dừng và đã hoàn thành. Mỗi tab chỉ sắp xếp các app vốn được phép xuất hiện trong tab đó; giữ nguyên quyền xem của khách và thành viên.
- Không áp dụng cho App của tôi, App đã tham gia, Nhiệm vụ App, bảng xếp hạng, chi tiết app, danh sách báo cáo hoặc danh sách quản trị.
- Ngày hiển thị tính theo **Asia/Ho_Chi_Minh**; đổi bộ khóa thứ tự từ 00:00 ngày Việt Nam mới.
- Trong cùng ngày và cùng dữ liệu đầu vào, kéo làm mới, đóng/mở app, xoay màn hình và tải lại cache cho cùng thứ tự.
- Quy ước đề xuất: dùng chung khóa theo ngày cho mọi tài khoản; cùng tập app và cùng nhóm ưu tiên sẽ có cùng thứ tự. Không đưa user ID, thiết bị, trạng thái đã cài hay đã test cá nhân vào khóa.

**Đã được người dùng xác nhận:** giữ ưu tiên app chưa đủ lượt hôm nay, sau đó Boost, sau đó tip cao; chỉ thay thứ tự trong nhóm ngang ưu tiên bằng khóa ngẫu nhiên theo ngày. Các app thuộc cùng nhóm khi cả ba tiêu chí này bằng nhau. Quyết định này đã chốt, không còn là giả định thiết kế.

## 2. Kết quả nghiên cứu source

| Vị trí | Hiện trạng | Hệ quả thiết kế |
| --- | --- | --- |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt:549` | Danh sách đang mở được sort theo `isTargetReached` tăng dần, `isBoosted` giảm dần, `tipCredits` giảm dần. | Đây là nơi quyết định ưu tiên cuối của Android; đổi ORDER BY trên server riêng lẻ chưa đủ. |
| Cùng file, dòng 554–555 | Danh sách tạm dừng/hoàn thành được map từ feed, chưa có comparator riêng. | Có thể áp dụng cùng khóa theo ngày cho hai tab này mà không trộn trạng thái. |
| `supabase/migrations/020_query_optimization.sql:118` và `supabase/migrations/047_home_stats_cycle.sql:274` | Các mảng app của `get_home_feed()` lấy theo `created_at DESC`. | Khi các tiêu chí ưu tiên bằng nhau, source hiện thường giữ app mới trước. |
| `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt:403` | Các đường tải danh sách legacy cũng theo `created_at DESC`. | Nên sắp xếp ở lớp hiển thị để bao phủ cả RPC lẫn fallback. |
| `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt:1691` | Feed cache có ngày nghiệp vụ, generation, phiên và mutation guard; các mảng cũng được dùng để điền cache campaign chung. | Không đưa thứ tự ngẫu nhiên vào repository dùng chung, tránh lan sang màn khác. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt:150` và `:234` | Có đường vẽ ngay từ cache RAM và snapshot đĩa. | Cần sắp xếp trước lần hiển thị đầu tiên; chỉ sort khi tải mạng xong sẽ làm danh sách nhảy hai lần. |
| `app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt:56` | Snapshot cũ được làm sạch các trường theo ngày. | Sau khi làm sạch phải tính lại thứ tự theo ngày hiện tại; không tin thứ tự đã lưu từ hôm qua. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt:173` | Có refresh khi resume. | Cần thêm xử lý Home mở liên tục qua nửa đêm. |
| Cùng file, dòng 768 | Tìm kiếm thực tế lọc trên danh sách của tab đang chọn. | Lọc sau khi sắp xếp để kết quả giữ thứ tự tương đối của tab. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt:881` | ViewModel còn duy trì `searchResults` riêng. | Phải rà cả hai đường để tránh state tìm kiếm cũ sau đổi ngày/refresh. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt:909` | Key của thẻ app có cả index. | Nên bỏ index cho campaign có ID hợp lệ để Compose theo đúng app khi đổi vị trí. |
| `app/src/main/java/com/ntt/crosstest/util/TimeFormat.kt:16` | Có `VnTime.today()` dùng múi giờ Việt Nam. | Dùng cùng nguồn ngày hiện có, có thể tiêm clock để kiểm thử. |

Các nhận xét trên dựa trên working tree hiện tại, vốn có nhiều thay đổi chưa commit. Migration 047 tồn tại trong workspace; nghiên cứu này **không xác nhận đã chạy trên Supabase live**. Android hiện vẫn dùng `todayTestedCount` cho `isTargetReached`; không lấy số cộng dồn theo chu kỳ làm tiêu chí sort trong thay đổi này.

## 3. Kiến trúc đề xuất

Thực hiện ở **lớp trình bày Trang chủ của Android**, sau khi có campaign và thống kê, trước khi xuất bản danh sách ra UI.

Không cần cron hằng ngày, không cần bảng lịch xếp hạng, không ghi lại `created_at` hoặc vị trí của campaign. Database tiếp tục lưu và trả dữ liệu như hiện tại; thứ tự hiển thị được suy ra từ ngày và ID.

Lý do chọn phương án này:

1. Tác động tập trung vào Trang chủ; repository và các luồng ghi giữ nguyên.
2. Có thể tính lại từ snapshot khi offline, không cần gọi mạng chỉ để đổi thứ tự.
3. RPC, fallback và cache đều dùng chung một thuật toán hiển thị.
4. Không tăng số truy vấn hoặc tạo yêu cầu riêng theo từng app.

Cần phát hành Android một lần để nhận chức năng. APK cũ vẫn dùng thứ tự hiện tại. Nếu tương lai cần thứ tự do admin điều khiển hoặc đồng nhất theo đồng hồ server bất kể giờ máy sai, đó là phần mở rộng riêng.

## 4. Thuật toán

### 4.1. Khóa ngẫu nhiên xác định theo ngày

Với campaign có UUID hợp lệ:

```text
date = ngày Việt Nam dạng YYYY-MM-DD
id = UUID campaign chuẩn hóa lowercase
key = SHA-256(UTF-8("home-order-v1|" + date + "|" + id))
```

So sánh digest dưới dạng chuỗi hex lowercase đủ 64 ký tự; nếu hai khóa bằng nhau, so tiếp UUID tăng dần. Thuật toán có version cố định để các bản app triển khai cùng quy tắc.

Tính khóa một lần cho mỗi app trong mỗi lần sắp xếp, không hash lại trong từng phép so sánh. Độ phức tạp là O(n log n), bộ nhớ bổ sung O(n).

Không dùng `shuffled()` không seed ở mỗi refresh. Cũng không chỉ shuffle với seed ngày trên thứ tự đầu vào chưa chuẩn hóa: cùng tập app nhưng đầu vào khác thứ tự có thể cho kết quả khác. Khóa riêng theo ID giúp thêm/xóa một app không đảo thứ tự tương đối của các app còn lại trong cùng nhóm.

Kotlin ghi rõ chuỗi số của `Random(seed)` có thể đổi giữa các phiên bản runtime. Dùng hash đã đặc tả giúp tránh phụ thuộc đó. Nguồn: [Kotlin Random](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.random/-random.html).

Campaign từ database bình thường có ID. Với dữ liệu cache lỗi/fixture thiếu ID, dùng fallback định danh cố định từ `ownerId + packageName + slotNumber`; không dùng index, thời gian hiện tại hay số ngẫu nhiên làm fallback. Nếu fallback vẫn trùng, giữ dữ liệu theo chính sách hiện tại và xử lý key UI an toàn; không tự xóa campaign như một phần sắp xếp.

### 4.2. Giữ ưu tiên hiện tại

Tab đang mở dùng thứ tự so sánh:

```text
1. isTargetReached ASC      // chưa đủ lượt trước
2. isBoosted DESC          // Boost trước trong cùng nhóm
3. tipCredits DESC         // tip cao trước trong cùng nhóm
4. dailyKey ASC            // ngẫu nhiên theo ngày khi ba tiêu chí trên bằng nhau
5. campaignId ASC          // phá hòa ổn định
```

Tab tạm dừng và hoàn thành: `dailyKey ASC`, rồi `campaignId ASC`. Không tự bổ sung ưu tiên Boost/tip cho hai tab vốn chưa có ưu tiên đó.

Vì các nhóm ưu tiên còn hoạt động, trạng thái đạt mục tiêu/Boost/tip thay đổi có thể làm app chuyển vị trí ngay trong ngày. Phần ổn định trong ngày chỉ áp dụng khi dữ liệu ưu tiên không đổi. Nhóm một app không có vị trí nào để đảo; các nhóm khác nhau không đổi chỗ cho nhau.

### 4.3. Ý nghĩa của ngẫu nhiên

Khóa được tính lại cho ngày mới, nhưng ngẫu nhiên không bảo đảm từng app đổi vị trí mỗi ngày hoặc mọi hoán vị liên tiếp khác nhau. Một nhóm nhỏ có thể tình cờ giữ cùng thứ tự. Không cam kết mỗi app được lên đầu trong một số ngày cố định.

Nếu sau này cần lượt lên đầu luân phiên có bảo đảm, cần một chính sách vòng quay khác và tiêu chí công bằng cụ thể; không đưa vào đợt này.

## 5. Tích hợp vào source

### 5.1. Hàm sắp xếp thuần

Đề xuất file mới `app/src/main/java/com/ntt/crosstest/ui/viewmodels/DailyHomeOrder.kt`, nhận danh sách và ngày tường minh, không truy vấn DB hoặc đọc clock trong comparator. Tách chế độ tab đang mở với hai tab còn lại.

Giữ nguyên `Campaign`, ID, trạng thái, nội dung, số lượt, `hasTestedToday`, URL, tip và Boost. Kết quả chỉ đổi thứ tự phần tử. Không thêm trường nghiệp vụ vào DTO Supabase.

### 5.2. Mọi đường xuất bản danh sách

Trong `HomeViewModel`, áp dụng cùng helper cho:

- Cache RAM khi khởi tạo state.
- Snapshot đọc từ đĩa, sau bước làm sạch ngày cũ.
- Feed RPC và các danh sách fallback sau khi tạo `CampaignDisplayItem`.
- Dữ liệu giữ lại khi refresh lỗi, offline hoặc đổi tài khoản.
- Sự kiện sang ngày mới và trường hợp request bắt đầu trước 00:00, kết thúc sau 00:00.

Chụp một ngày duy nhất cho cả ba danh sách trong cùng lần xuất bản. Trước khi publish response, kiểm tra lại ngày, generation, UID và sequence theo guard hiện có. Nếu ngày đã đổi, xử lý các trường theo ngày bằng quy tắc cache hiện có, rồi mới áp dụng comparator ngày mới. Không sort xong rồi reset số lượt làm danh sách sai nhóm ưu tiên.

Ghi `homeOrderDate` trong state trình bày nếu cần nhận biết ngày đã áp dụng; không bắt buộc thêm metadata mới vào snapshot vì có thể tính lại thứ tự từ ngày hiện tại sau mỗi lần đọc. Không đổi version snapshot chỉ để lưu thứ tự.

Không đẩy helper vào `listOpenCampaigns()`, `myCampaigns()` hoặc cache campaign dùng chung. `myCampaigns`, `joinedCampaigns`, bảng xếp hạng và missions giữ nguyên thứ tự hiện tại.

### 5.3. Chuyển ngày và offline

- Mỗi lần resume: kiểm tra ngày và sắp xếp lại dữ liệu đang có trước khi chờ mạng.
- Khi Home đang hiển thị: dùng coroutine gắn vòng đời màn hình, chờ ranh giới ngày Việt Nam kế tiếp; khi thức dậy phải đọc lại ngày thực tế rồi mới cập nhật. Gộp với cơ chế chuyển ngày đã có nếu lúc triển khai source đã bổ sung.
- Hủy lịch chờ khi màn hình không còn hoạt động; khi hoạt động lại, kiểm tra ngày ngay. Android hỗ trợ hủy/khởi chạy lại coroutine theo lifecycle; xem [hướng dẫn lifecycle và coroutine](https://developer.android.com/topic/libraries/architecture/views/coroutines-views).
- Không thêm WorkManager, exact alarm hoặc tác vụ chạy nền chỉ để thay thứ tự một màn hình đang đóng.
- Offline qua ngày vẫn sắp xếp được từ cache. Phải đặt bước kiểm tra ngày trước nhánh early-return offline hiện tại của `refresh()`.
- Sắp xếp lại không được tự tạo check-in, reset dữ liệu server, làm mới Credit hoặc suy đoán số lượt test mới. Giữ cách làm sạch/hiển thị số liệu đã có; lỗi cũ ngoài phạm vi được ghi riêng.
- Nếu request qua ranh giới ngày, không cho danh sách ngày cũ ghi đè thứ tự ngày mới; tránh gọi refresh lặp vô hạn. Các guard phiên và mutation hiện có phải được giữ.
- Với máy đặt sai ngày thực tế, phương án client sẽ suy ra ngày sai. Đổi múi giờ máy đơn thuần không ảnh hưởng vì cố định múi giờ VN. Khi người dùng sửa đồng hồ trong lúc app mở, tính lại mốc chờ và kiểm tra ngày theo tín hiệu thay đổi giờ của hệ thống; kiểm tra lại khi resume luôn là lớp dự phòng.

### 5.4. Tìm kiếm và trải nghiệm danh sách

- Tìm kiếm chỉ lọc danh sách đã sắp xếp trong tab hiện tại; không tạo một thứ tự ngẫu nhiên khác cho mỗi từ khóa.
- Nếu còn duy trì `searchResults` ở ViewModel, tính lại sau đổi ngày và sau dữ liệu mới, giữ nguyên ý nghĩa tìm kiếm hiện có.
- Thẻ có ID dùng key ổn định theo ID, không ghép index. Với fixture/dữ liệu thiếu ID, cần fallback key có phân biệt va chạm mà không thay chính sách dữ liệu.
- Giữ tab đang chọn, từ khóa và app đang mở. Khi đảo danh sách không tự điều hướng hoặc tự mở app khác. Thử nghiệm cuộn: giữ app đang nhìn thấy làm mốc khi còn tồn tại, không chủ động đưa người dùng về đầu danh sách.
- App vừa đăng vẫn được đưa vào đúng tab sau refresh theo luồng cũ, nhận vị trí theo khóa ngày. Không giữ mặc định app mới luôn đứng đầu; đây là hệ quả hiển thị của yêu cầu sắp xếp, không phải đổi luồng đăng.
- Không thêm nút, màn hình hướng dẫn, thông báo hằng ngày hoặc thay bố cục để vận hành chức năng này.

## 6. Các file dự kiến

| File | Thay đổi dự kiến |
| --- | --- |
| `ui/viewmodels/DailyHomeOrder.kt` (mới) | Khóa hash ổn định, comparator và hàm sắp xếp thuần. |
| `ui/viewmodels/HomeViewModel.kt` | Sắp xếp tại các đường tải/cache/lỗi và xử lý đổi ngày, giữ guard hiện có. |
| `ui/screens/HomeScreen.kt` | Gắn kiểm tra đổi ngày vào lifecycle; sửa key danh sách, giữ bộ lọc tìm kiếm. |
| `src/test/.../DailyHomeOrderTest.kt` (mới) | Test thuật toán và bảo toàn dữ liệu. |
| Test Home/cache phù hợp | Test đổi ngày, offline, RAM/đĩa, response trễ và tìm kiếm bằng clock kiểm soát. |

`CampaignRepository.kt`, `SessionCacheManager.kt` và `TimeFormat.kt` chỉ sửa nếu thực tế cần một điểm nối clock/cache; ưu tiên dùng API đang có. Không cần migration SQL, cấu hình admin hoặc sửa các hàm tạo campaign/nộp báo cáo.

## 7. Thứ tự thực hiện

1. Áp dụng chính sách đã chốt: giữ ưu tiên, ngẫu nhiên trong từng nhóm; chụp diff hiện tại để tránh chồng các sửa đổi cache và chu kỳ thống kê đang có trong workspace.
2. Thêm helper thuần và test hành vi thứ tự/ngày/định danh.
3. Tích hợp HomeViewModel ở đầy đủ đường dữ liệu, đặc biệt cache trước lần vẽ đầu và nhánh offline.
4. Thêm xử lý chuyển ngày theo lifecycle; giữ một nguồn ngày cho một lần publish và các guard chống response cũ.
5. Rà tìm kiếm, key Compose và trạng thái cuộn, kiểm tra cả ba tab và guest/member.
6. Chạy test liên quan, build debug, kiểm thử trực quan trên Android. Chỉ mở rộng test nếu có lỗi hoặc thay đổi liên quan.
7. Phát hành Android theo quy trình hiện tại; không có bước triển khai backend riêng cho tính năng này.

## 8. Tiêu chí nghiệm thu

| Nhóm | Điều cần xác nhận |
| --- | --- |
| Ổn định trong ngày | Cùng ngày, cùng tập app và ưu tiên: refresh, restart, rotate, RAM, đĩa, RPC/fallback cho cùng thứ tự; đầu vào hoán đổi vẫn cho cùng kết quả. |
| Qua ngày | Bộ khóa đổi ở 00:00 VN; thử ngày cuối tháng/năm, Home mở liên tục, app ngủ rồi resume và offline qua ngày. Không assert mọi app bắt buộc đổi vị trí. |
| Ưu tiên | App chưa đủ lượt đứng trước; trong cùng nhóm Boost/tip được giữ đúng; khóa ngày chỉ phá hòa khi cả ba tiêu chí bằng nhau. Không ngẫu nhiên hóa giữa các nhóm ưu tiên. |
| Tính toàn vẹn | Tập ID, số phần tử và mọi trường từng app không đổi sau sort; danh sách rỗng/một app hoạt động; khóa hòa/ID thiếu xử lý xác định. |
| Thêm/xóa app | App mới xuất hiện đúng tab; thêm/xóa không đảo thứ tự tương đối của các app cũ trong cùng nhóm và cùng ngày. Không bị phụ thuộc thứ tự `created_at`. |
| Thống kê | App đạt mục tiêu/Boost/tip thay đổi: xử lý đúng chính sách đã chọn; không dùng số chu kỳ nhiều ngày để quyết định đủ lượt ngày. |
| Nguồn dữ liệu | RAM, snapshot cũ, mạng lỗi, fallback, cold start offline đều áp dụng cùng thuật toán trước publish. |
| Đồng thời | Request qua nửa đêm, trả ngược thứ tự, đổi tài khoản khi đang tải: giữ guard và ngày mới; không phục hồi dữ liệu cá nhân phiên trước. |
| Tìm kiếm/UI | Đúng tab, giữ từ khóa, thứ tự kết quả là tập con của tab; thẻ có key ổn định, không nhảy nội dung; cuộn không tự về đầu. |
| Hồi quy đăng app | Form, validate, điều kiện Credit, slot, tạo thành công, quyền sửa và việc app xuất hiện sau đăng giữ nguyên; chỉ vị trí hiển thị thay đổi. |
| Hồi quy các luồng | Tham gia, đã test hôm nay, hộp xác nhận đủ lượt, nộp/duyệt/từ chối, tip/Credit/Boost và nhiệm vụ giữ nguyên. App của tôi, App đã tham gia, xếp hạng không bị sort lại. |
| Hiệu năng | Không phát sinh RPC theo app, không cron, không ghi vị trí vào DB, không hash lặp trong comparator; không vô hiệu cache toàn cục chỉ vì đổi thứ tự. |

Test thuật toán cần có vector khóa cố định và clock tường minh; test tích hợp phải quan sát danh sách thực tế, không chỉ tìm chuỗi trong source. Nghiên cứu này chưa chạy build/test chức năng vì chưa triển khai thay đổi.

## 9. Hoàn tác

Khôi phục comparator và cách hiển thị cũ trong Android, bỏ lịch đổi ngày dành riêng cho thứ tự. Không xóa dữ liệu hoặc đổi ngày tạo app. Các snapshot vẫn đọc được vì không đổi cấu trúc bắt buộc. Cần phát hành APK sửa nếu đã phát hành chức năng; phương án ban đầu không thêm công tắc server để tắt từ xa.
