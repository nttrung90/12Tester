# Kết quả kiểm tra và kế hoạch sửa xáo trộn Trang chủ

Ngày kiểm tra: **28/10/2026**. Phạm vi: tính năng xáo trộn app hằng ngày và phần tích hợp với chu kỳ thống kê Trang chủ.

**Kết luận: chưa đạt nghiệm thu.** Build debug và 21 test có sẵn thành công, nhưng bộ test độc lập có **12 ca, 2 PASS, 10 FAIL bằng assertion, 0 error**. Mười ca thất bại không đồng nghĩa với mười lỗi độc lập: một số ca kiểm tra cùng vấn đề ở các đường dữ liệu khác nhau.

Đợt này chỉ đọc source, chạy build/test và tạo bằng chứng/kế hoạch; **không sửa code sản phẩm, không chạy migration, không kết nối Supabase production, không phát hành APK**. Các test audit được nạp bằng init script riêng, không sửa build config chính.

## 1. Những phần đã đúng

- `DailyHomeOrder` tạo khóa SHA-256 theo ngày và định danh; hash nằm ngoài comparator.
- Tab đang mở giữ thứ tự chưa đủ lượt hôm nay → Boost → tip → khóa ngày → định danh. `isTargetReached` vẫn dựa trên `todayTestedCount`, không dùng tổng chu kỳ.
- Hai tab còn lại dùng khóa ngày và không thêm ưu tiên Boost/tip.
- Đường lấy feed bình thường đã dùng helper. Test độc lập thực sự tạo `HomeViewModel`, tải dữ liệu bằng MockEngine, đảo thứ tự response rồi refresh: thứ tự UI vẫn ổn định, tổng chu kỳ 18 và số riêng hôm nay 2 giữ nguyên.
- Key Compose của app có ID đã bỏ index. Bộ lọc tìm kiếm thực tế vẫn lọc theo danh sách của tab đang chọn.
- Chỉ có một `midnightJob`; không thấy một job xáo trộn thứ hai được thêm cạnh job thống kê. Tuy nhiên job hiện chưa đáp ứng vòng đời tại X05.
- Ca `nextDayRefreshFailureMustClearPreviousDayTestFlag` đã PASS ở danh sách đang mở. So với review chu kỳ trước, nhánh này đã được cải thiện; không đánh dấu nó là lỗi còn nguyên.
- Lỗi compile nullable `ownerId` của review trước đã được xử lý; build hiện tại thành công.

## 2. Kết quả thực chạy và bằng chứng

| Lần chạy | Kết quả |
| --- | --- |
| `:app:testDebugUnitTest --tests com.ntt.crosstest.ui.viewmodels.DailyHomeOrderTest --tests com.ntt.crosstest.data.HomeStatsCycleTest :app:assembleDebug` | **BUILD SUCCESSFUL**. DailyHomeOrderTest: 15/15 PASS; HomeStatsCycleTest: 6/6 PASS. Assemble debug thành công, nhiều task up-to-date. |
| Cùng task test, nạp `audit/home-order-review-2026-10-28/review.init.gradle`, lọc hai lớp audit | **12 ca chạy thật; 10 assertion FAIL, 2 PASS, 0 error.** Gradle báo FAILED vì test hành vi thất bại, không phải lỗi compile hay môi trường. |

Thư mục bằng chứng: `F:\AI\12Tester\audit\home-order-review-2026-10-28\`.

- `baseline.log`, `baseline-results/`: kết quả 21 ca có sẵn và build.
- `independent-tests.log`, `android-results/`, `android-html/`: kết quả bộ test độc lập lần này.
- `tests/com/ntt/crosstest/ui/viewmodels/DailyHomeOrderIndependentReviewTest.kt`: 5 ca tích hợp mới, 1 PASS và 4 FAIL.
- `tests/com/ntt/crosstest/data/HomeStatsIndependentReviewTest.kt`: bản sao 7 ca từ review chu kỳ trước, chạy lại trên source hiện tại; 1 PASS và 6 FAIL. Không sử dụng XML cũ làm kết quả của lần này.
- `review.init.gradle`: chỉ thêm nguồn test audit khi được truyền bằng `-I`.
- `reviewed-source-sha256.json`: hash các file sản phẩm liên quan; đối chiếu sau test cho thấy sáu file không thay đổi trong khoảng kiểm tra.

Chưa kiểm thử UI trên thiết bị/emulator, hành vi cuộn thực tế, thay đổi đồng hồ hệ thống hoặc SQL production. Chưa chạy lại bộ SQL của review chu kỳ; không suy diễn trạng thái các lỗi SQL cũ từ kết quả Android lần này.

## 3. Các lỗi cần sửa

### X01 — Đổi thứ tự khi qua ngày bị phụ thuộc tab đang mở có app

**Vị trí:** `HomeViewModel.kt:492–517`, đặc biệt điều kiện `isStateStaleDate && openCampaigns.isNotEmpty()` tại dòng 495; nhánh offline kế tiếp cũng chỉ return sớm khi có app đang mở.

**Bằng chứng FAIL:**

- `pausedAndCompletedOnlyMustReorderBeforeAwaitingNetworkAtNewDay`: tải Trang chủ có 0 app đang mở, có app tạm dừng và hoàn thành. Chuyển clock từ 28 sang 29/10, giữ response mới chưa trả về. `homeOrderDate` vẫn **28/10**, trong khi phải đổi theo dữ liệu local trước khi chờ mạng.
- `pausedOnlyOfflineRolloverMustNotDependOnNetworkSuccess`: chuyển ngày trong trạng thái offline. Tại lúc refresh trả quyền điều khiển, ngày thứ tự vẫn là hôm qua. Source tiếp tục thử tải mạng dù các danh sách tạm dừng/hoàn thành đã có trong state.

**Ảnh hưởng:** Xáo trộn hai tab còn lại phụ thuộc vào việc tab đang mở có app và tốc độ/kết quả request. Nhánh catch có thể sắp xếp lại sau khi request lỗi, nhưng đó không đáp ứng yêu cầu đổi thứ tự local ngay cả khi offline.

**Kế hoạch sửa:**

1. Bỏ điều kiện phải có `openCampaigns` khỏi bước đổi ngày. Xử lý cả ba danh sách độc lập; danh sách rỗng là đầu vào hợp lệ.
2. Tách việc xác nhận ngày thứ tự với việc đánh giá “có nội dung để hiển thị”. Không dùng riêng danh sách mở để quyết định đã có cache hay cần vòng loading toàn màn hình.
3. Khi offline, áp dụng cập nhật ngày/thứ tự trên dữ liệu đang có trước mọi nhánh tải mạng; giữ nội dung các tab có sẵn. Không tạo request chỉ để xác định thứ tự.
4. Cập nhật tìm kiếm từ các danh sách mới trong cùng lượt xuất bản state.

**Nghiệm thu:** Test hai ca trên PASS trước khi response mới được trả; bổ sung trường hợp chỉ có tab hoàn thành và trường hợp cả ba tab rỗng.

### X02 — Dọn ngày mới ở danh sách chính nhưng bỏ sót thống kê App đã tham gia

**Vị trí:** `HomeViewModel.kt:495–513`, `:772`, `:887–910`; đường render `HomeScreen.kt:1187`.

**Bằng chứng FAIL:**

- `offlineRolloverMustNotLeaveYesterdayJoinedFlagsAndCycleTotal`: sau khi tải ngày D, cùng app trong danh sách chính và `joinedStatsMap` đều có `hasTestedToday=true`, tổng chu kỳ 9. Sang D+1 offline, danh sách chính được dọn nhưng `joinedStatsMap` vẫn giữ **hasTestedToday=true** và dữ liệu ngày D.
- `staleFeedMustNotPublishUnsanitizedJoinedStats`: response ngày cũ bị loại ở danh sách chính nhưng `joinedStatsMap` vẫn xuất bản tổng **9** nguyên trạng.

**Ảnh hưởng:** Cùng app hiển thị trạng thái khác nhau giữa Trang chủ và App đã tham gia. Đây là lỗi tích hợp dữ liệu theo ngày; yêu cầu không xáo trộn App đã tham gia không có nghĩa là được bỏ qua việc làm sạch thống kê của danh sách này.

**Kế hoạch sửa:**

1. Áp dụng cùng chính sách freshness cho `CampaignDisplayItem`, `CampaignStats` và mọi đường gọi thẻ, bao gồm tìm kiếm, joined map, RAM/đĩa, thành công và catch.
2. Khi chỉ dữ liệu ngày cũ hết hạn, vô hiệu các cờ/số theo ngày và tổng chu kỳ chưa xác nhận. Không xóa campaign, thứ tự joined list, tên chủ app hoặc số đã cài còn hợp lệ.
3. Không xuất bản `joinedStats` thô sau khi quyết định stats cùng nguồn là cũ.
4. Cần xử lý “chưa biết” theo X04; không biến invalidation thành số 0 được xác nhận.

**Nghiệm thu:** Hai test trên PASS; offline qua ngày không còn chip đã test hôm qua và không mang số chu kỳ cũ sang dưới dạng số hợp lệ.

### X03 — Một stats cũ làm xóa dữ liệu mới, đổi sai nhóm ưu tiên

**Vị trí:** `HomeViewModel.kt:493–512` và `:735–748`.

**Bằng chứng FAIL:**

- `staleCountInOneCampaignMustNotClearFreshCampaignPriority`: feed có app A stats đúng hôm nay với **12 lượt riêng hôm nay, 24 lượt chu kỳ**, app B stats hôm qua. Sau publish, app A bị đổi từ **12 thành 0**, mất trạng thái đủ mục tiêu. Như vậy thứ tự không còn bảo toàn nhóm ưu tiên theo dữ liệu hợp lệ.
- `staleFeedMustNotEraseFreshDailyDashboardCount`: feed cũ nhưng dashboard riêng trả **2 lượt hôm nay**. UI xuất bản **0** vì `hasStaleStatsDate` được gộp vào `isDayRollover` và reset cả dữ liệu cá nhân.

**Nguồn gốc:** Lỗi gộp freshness này đã có trong phần chu kỳ thống kê. Xáo trộn không dùng trực tiếp `homeTestedCount` để phân nhóm, nhưng vẫn chịu ảnh hưởng vì bước dọn dữ liệu làm sai `todayTestedCount`. Nhánh dọn đầu refresh mới cũng cần được sửa nhất quán.

**Kế hoạch sửa:**

1. Tách ba khái niệm: ngày của thứ tự, ngày dữ liệu stats từng app và ngày dữ liệu dashboard cá nhân.
2. Một stats cũ chỉ vô hiệu stats đó; không xóa số mới của app khác hoặc dashboard độc lập. Nếu backend cam kết một ngày chung cho toàn feed thì cần biểu diễn hợp đồng đó rõ ràng; không suy ra ngày của dashboard từ bất kỳ app nào trong feed.
3. `homeOrderDate` chỉ cho biết ngày đã dùng để sắp xếp; không dùng nó thay metadata ngày của toàn bộ dữ liệu cá nhân. Không đổi nhãn ngày để làm dữ liệu cũ trông như mới.
4. Sau khi chuẩn hóa freshness đúng phạm vi, chạy comparator theo dữ liệu ngày thật. Không sửa bằng cách chuyển `isTargetReached` sang tổng chu kỳ.
5. Giữ nguyên guard phiên/request hiện có và kiểm tra lại ngày sát thời điểm publish.

**Nghiệm thu:** App A vẫn 12/24 và đủ mục tiêu; dashboard vẫn 2; app B ở trạng thái cần tải lại. Tìm kiếm và joined map dùng cùng cách đánh giá.

### X04 — Lỗi chu kỳ còn tồn tại: số 0 giả và không tự lấy lại dữ liệu hợp lệ

**Vị trí:** `HomeViewModel.kt:60`, `SessionCacheManager.kt:75–77`, `CampaignCard.kt:72`, `HomeScreen.kt:1187`, `Models.kt:817`; cache feed/stats tại `CampaignRepository.kt:1712`, `:1722` và helper cache stats tại `:1629`.

**Bằng chứng FAIL chạy lại:**

- `expiredCycleCountMustRemainUnknownRatherThanRenderZero`: snapshot hôm qua tổng 9, sanitize về `homeTestedCount=null`, `todayTestedCount=0`; getter đưa ra **0** để render.
- `repositoryMustNotCacheOldServerDateAsFreshToday`: hai lần đọc feed có `stats_date` hôm qua nhưng thực tế chỉ có **1 request** vì response cũ được cache với nhãn ngày request hôm nay.
- `staleFeedMustScheduleAControlledRefetch`: phát hiện feed cũ rồi dọn nhưng vẫn chỉ **1 request**, không có lần lấy lại có kiểm soát để nhận số mới.

**Kế hoạch sửa:** Thực hiện đồng bộ với R01/R03 trong `KE_HOACH_SUA_CHU_KY_THONG_KE_TRANG_CHU_SAU_KIEM_TRA_2026-10-28.md`:

1. Phân biệt legacy hợp lệ, số chu kỳ hợp lệ, dữ liệu hết hạn/chưa biết. Nullable đơn lẻ hiện đang gộp các ý nghĩa này.
2. Khi chưa biết, thẻ dùng trạng thái chờ/placeholder, không in 0 giả. Giữ nguyên định dạng chuỗi khi có số hợp lệ; server xác nhận 0 vẫn hiển thị 0.
3. Kiểm tra ngày payload trước khi coi cache là fresh; không gắn ngày hiện tại cho stats hôm qua. Cache campaign công khai vẫn có thể dùng riêng khi thích hợp.
4. Lập một lần refetch có kiểm soát khi payload cũ/request qua ngày; giới hạn retry, gộp request và không polling vô hạn.
5. Trong lúc chờ số mới, thứ tự ngày mới vẫn tính local. Không bắt server phải chạy cron hoặc trả trường thứ tự để giải quyết lỗi cache client.

**Nghiệm thu:** Ba test trên PASS bằng hành vi tương đương; cache hết hạn không in 0 giả; response cũ không ngăn request lấy dữ liệu mới. Không sửa test thành chấp nhận lỗi hiện có.

### X05 — Lịch nửa đêm chưa theo vòng đời và chưa xử lý đổi giờ

**Bằng chứng đọc source:** `HomeViewModel.kt:211–228`, khởi chạy ở `init` dòng 309; `HomeScreen.kt:173` chỉ refresh khi resume và không dừng job lúc pause. Lịch dùng `ZonedDateTime.now()` riêng, không cùng clock nghiệp vụ inject vào repository.

Job tiếp tục sống khi ViewModel còn trong back stack; khoảng chờ tính một lần có thể không còn đúng sau khi người dùng sửa đồng hồ trong lúc Home đang mở. Đây là khoảng thiếu so với kế hoạch, **chưa có test UI/đổi giờ hệ thống chạy thật trong đợt này**; không gộp nó vào 10 assertion FAIL.

**Kế hoạch sửa:**

- Duy trì duy nhất một cơ chế chuyển ngày dùng chung cho thống kê và thứ tự; gắn start/stop vào vòng đời hiển thị phù hợp.
- Inject nguồn thời gian nhất quán để kiểm thử; khi timer thức dậy phải kiểm tra ngày hiện tại và áp dụng chuẩn hóa/sắp xếp trước khi chờ mạng.
- Khi đổi giờ hệ thống trong lúc hiển thị, tính lại mốc chờ; resume luôn kiểm tra ngày ngay. Không thêm timer polling liên tục hoặc exact alarm.
- Hủy job khi rời lifecycle tương ứng; không phát sinh refresh lặp khi resume sát nửa đêm.

**Nghiệm thu cần bổ sung:** Home mở liên tục qua ngày; đi sang chi tiết rồi quay lại; app background/resume; sửa giờ vượt nửa đêm; một sự kiện chuyển ngày không làm tăng gấp đôi số request.

## 4. Phát hiện kế thừa ngoài phần xáo trộn

`adminConfigResponseMustNotPublishAfterAccountSwitch` vẫn FAIL: response cấu hình chu kỳ của user A được xuất bản vào state sau khi chuyển sang user B. `AdminUsersViewModel.kt:220` và `:243` cần guard UID/generation/request cho load/save, hủy/vô hiệu state khi phiên thay đổi theo kế hoạch chu kỳ cũ. Test lần này xác nhận đường load; đường save cần ca tương ứng.

Không kết luận đây là bypass quyền server: bằng chứng hiện có là state client nhận response của phiên trước. Không quy lỗi này cho thuật toán xáo trộn. Có thể giao sửa trong gói chu kỳ riêng, nhưng phải đối chiếu chung trước nghiệm thu hai tính năng.

Các ràng buộc SQL R05/R06 trong kế hoạch review trước không được chạy lại ở đợt này. Nếu sửa SQL trong gói kế tiếp, cần chạy lại fixture tương ứng và ghi kết quả mới riêng.

## 5. Trình tự sửa và nghiệm thu

1. Đọc working tree hiện tại và cả hai kế hoạch sửa. Chỉ định một người/agent tích hợp các file Home/cache; tránh hai chat cùng ghi `HomeViewModel.kt` và `HomeScreen.kt`.
2. Chốt hợp đồng freshness dùng chung theo X02–X04; giữ số theo ngày và số chu kỳ tách biệt. Đây là nền để sửa sort đúng, không chỉ đổi điều kiện của một nhánh.
3. Sửa X01 và gom bước chuẩn hóa/sắp xếp/xuất bản cho ba tab; cập nhật tìm kiếm và joined stats nhất quán. Giữ thứ tự các màn ngoài phạm vi.
4. Sửa cache/refetch X04; giữ response guard và kiểm thử request đi qua nửa đêm/đổi tài khoản.
5. Tích hợp lifecycle X05 vào một lịch chuyển ngày hiện có; bổ sung ca thời gian/vòng đời có clock kiểm soát.
6. Xử lý guard cấu hình admin theo kế hoạch chu kỳ, hoặc báo rõ đây là phần chưa hoàn tất của gói đó.
7. Chạy lại 21 test tính năng hiện có và toàn bộ audit lần này. Khi đổi hợp đồng hiển thị thành nullable/state, cập nhật audit để kiểm tra trạng thái UI tương đương, không bỏ yêu cầu “không có 0 giả”. Giữ bằng chứng trước/sau riêng.
8. Chạy các regression cache liên quan và `:app:assembleDebug`. Chạy test SQL nếu thực sự sửa backend; không deploy trong bước kiểm tra local.
9. Kiểm thử UI VI/EN, tìm kiếm, cuộn/key, offline, ba tab, App đã tham gia, luồng đăng app và hộp xác nhận đủ lượt. Ghi rõ phần nào chưa có thiết bị để xác minh.

**Điều kiện hoàn tất:** Các ca FAIL được sửa hoặc thay bằng kiểm thử tương đương khi hợp đồng thay đổi; không còn ngày thứ tự cũ vì tab mở rỗng, cờ test ngày cũ ở joined map, reset sai dữ liệu mới, số 0 giả hoặc cache ngăn refetch. Ưu tiên và toàn bộ luồng đăng/test/duyệt/Credit vẫn giữ nguyên. Chưa đạt các điều kiện này thì không dùng riêng kết quả build xanh làm xác nhận hoàn tất.
