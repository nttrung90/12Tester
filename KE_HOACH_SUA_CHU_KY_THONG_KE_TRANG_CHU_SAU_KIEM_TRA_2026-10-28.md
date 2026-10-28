# Kiểm tra độc lập và kế hoạch sửa chu kỳ thống kê Trang chủ

Ngày kiểm tra: **28/10/2026**. Phạm vi: triển khai theo `KE_HOACH_CHU_KY_THONG_KE_TRANG_CHU_2026-10-28.md`.

Trạng thái: **còn lỗi, chưa đủ điều kiện nghiệm thu**. Lượt này chỉ rà soát, tạo kiểm thử độc lập và lập kế hoạch sửa; không sửa mã chức năng, không commit/push/deploy hoặc thay đổi Supabase live.

## 1. Kết quả thực chạy và giới hạn

| Kiểm tra | Kết quả |
| --- | --- |
| Bộ SQL của triển khai `supabase/tests/run-home-stats-cycle-test.mjs` | 14 ca PASS. |
| Bộ unit test gốc Android, không nạp bộ audit mới | **150 tests, 0 failures, 0 errors**, BUILD SUCCESSFUL. Có lưu XML và tổng số thực tế; không dùng số test trong báo cáo cũ để thay kết quả chạy lần này. |
| `:app:assembleDebug` trong lần kiểm tra đầu | BUILD SUCCESSFUL; các task đóng gói đã up-to-date, không coi là kiểm thử giao diện thiết bị thật. |
| Bộ Android độc lập lần đầu | **6 tests chạy thật, 6 FAIL bằng assertion**, không phải lỗi compile hoặc môi trường. Tái hiện số 0 giả, cờ test ngày cũ, stats app đã tham gia, thiếu refetch, cache gắn sai ngày và cấu hình admin sau đổi tài khoản. |
| Bộ SQL độc lập | **8 ca: 5 PASS, 3 FAIL**. PASS hai RPC thực tế ở chu kỳ 2/3 ngày, reset đầu chu kỳ 2/3 ngày và quyền đọc trực tiếp của non-admin. FAIL ba ràng buộc cấu hình trình bày ở R05/R06. |
| Lần chạy bổ sung Android | Đã thêm ca thứ 7 kiểm tra feed cũ không được xóa số dashboard ngày mới, nhưng **bị chặn trước khi chạy test** bởi lỗi compile mới ở `DailyHomeOrder.kt:45`. Không tính ca này là FAIL hành vi đã thực chạy. |

**Thay đổi đồng thời:** Trong lúc review, workspace xuất hiện thêm `DailyHomeOrder.kt` thuộc tính năng xáo trộn Trang chủ. File này chưa tồn tại lúc build đầu thành công. Lần build bổ sung báo `UNSAFE_CALL` tại `campaign.ownerId.trim()` vì `ownerId` nullable. Vì vậy kết quả “build thành công” phía trên chỉ áp dụng cho thời điểm đầu; trạng thái gần nhất đã kiểm tra đang có lỗi compile. Không tự sửa/hoàn tác mã tính năng đang được làm đồng thời.

Bằng chứng trong `audit/home-stats-review-2026-10-28/`:

- `sql-baseline.log`: 14 ca SQL có sẵn.
- `android-baseline.log`: lần test/build đầu; `android-baseline-final.log`, `baseline-summary.json`, `baseline-results/`: lần đối chiếu bộ test gốc 150/150.
- `android-results/TEST-com.ntt.crosstest.data.HomeStatsIndependentReviewTest.xml`: **kết quả 6 ca độc lập đã chạy**, kèm assertion và stack trace; được giữ riêng trước khi chạy bổ sung.
- `tests/com/ntt/crosstest/data/HomeStatsIndependentReviewTest.kt`: source audit hiện có 7 ca, trong đó ca `staleFeedMustNotEraseFreshDailyDashboardCount` được bổ sung sau lần XML 6 ca.
- `android-concurrent-build-blocker.log`: bằng chứng compile bị chặn trong lần chạy ca bổ sung.
- `sql-independent-review.mjs`, `sql-independent-review.log`: source và kết quả SQL độc lập.
- `review.init.gradle`: chỉ nạp test audit khi truyền init script, không thay build config chính.
- `reviewed-source-sha256.json`: dấu vết file và hash để phân biệt các thay đổi sau thời điểm rà soát. Đây không phải snapshot bất biến của toàn bộ workspace.

Không kết nối database sản xuất; chưa kiểm thử UI trên thiết bị/emulator, chưa chạy lại lint trong review này. Không kết luận tất cả chức năng đã đúng chỉ vì bộ test gốc xanh.

## 2. Các phần đã xác nhận đúng

- Hai RPC `get_home_feed` và `get_campaigns_stats` trả đúng tổng của chu kỳ 2/3 ngày trên dữ liệu fixture: cùng một người test khác ngày được cộng nhiều lượt; trường `today_tested_count` vẫn giữ số riêng hôm nay.
- Khi hôm nay là đầu chu kỳ mới, cả hai RPC không mang check-in của chu kỳ trước sang: tổng bằng 0 khi chưa có lượt mới.
- Trong fixture quyền thật, anon/member/moderator bị chặn đọc bảng cấu hình, bảng audit, helper nội bộ và RPC đọc cấu hình admin.
- Trường số hiển thị đã được tách khỏi kiểm tra chỉ tiêu theo ngày; chuỗi tester vẫn giữ định dạng cũ. Lỗi hiện tập trung vào trạng thái hết hạn, chuyển ngày và các ràng buộc cấu hình, không phải công thức cộng lượt cơ bản.

## 3. Phát hiện và kế hoạch sửa

### R00 — P1: Gỡ lỗi compile mới trước vòng nghiệm thu tiếp theo

**Vị trí:** `app/src/main/java/com/ntt/crosstest/ui/viewmodels/DailyHomeOrder.kt:45`.

**Bằng chứng thực chạy:** `compileDebugKotlin` báo chỉ được gọi safe-call/non-null assertion trên receiver `String?` tại `campaign.ownerId.trim().lowercase()`. Đây là mã tính năng khác xuất hiện trong lúc review, cần phối hợp để không ghi đè công việc đồng thời.

**Kế hoạch:** Chuẩn hóa `ownerId` nullable theo hợp đồng fallback định danh campaign của tính năng xáo trộn, không dùng `!!` để chuyển lỗi compile thành crash khi gặp campaign thiếu owner. Thêm fixture thiếu owner/id/package phù hợp model và xác minh định danh ổn định. Chạy lại compile/build trước khi lấy kết quả test hành vi mới.

**Nghiệm thu:** `compileDebugKotlin` và `assembleDebug` thành công với source đã ổn định; không xóa file tính năng đang triển khai để làm build xanh.

### R01 — P1: Cache cộng dồn hết hạn vẫn hiển thị số 0 giả

**Vị trí:** `HomeViewModel.kt:60`, `SessionCacheManager.kt:75`, `CampaignCard.kt:72`, `HomeScreen.kt:1174`, getter `CampaignStats.effectiveHomeTestedCount` trong `Models.kt:817`.

**Tái hiện đã FAIL:** `expiredCycleCountMustRemainUnknownRatherThanRenderZero`. Snapshot hôm qua có tổng 9; sanitize đặt `homeTestedCount=null` và `todayTestedCount=0`; `displayTestedCount = homeTestedCount ?: todayTestedCount` trả **0**, rồi thẻ nhận 0 để in dòng thống kê. Danh sách app đã tham gia cũng có chuỗi fallback `?: 0`.

**Ảnh hưởng:** Người dùng có thể thấy `0/12 hôm nay` ở giữa chu kỳ nhiều ngày khi chưa lấy được số mới. Test có sẵn `HomeStatsCycleTest` lại chủ động assert trường hợp này bằng 0 nên đang xác nhận hành vi trái kế hoạch.

**Kế hoạch:**

1. Tạo trạng thái hiển thị phân biệt **legacy chưa có trường mới**, **giá trị mới hợp lệ** và **dữ liệu hết hạn/chưa biết**. Có thể dùng nullable cộng trạng thái freshness hoặc kiểu trạng thái riêng.
2. Chỉ fallback số ngày khi payload backend cũ còn hợp lệ cho hôm nay; không dùng cùng `null` cho cả legacy và expired rồi mặc định thành số 0.
3. `CampaignCard` và mọi đường gọi, gồm joined/search/snapshot, phải render trạng thái chờ/placeholder khi chưa biết. Không đổi các chuỗi có số hợp lệ; không thêm thông tin chu kỳ ngoài khu vực admin.
4. Sửa test cũ để xác nhận không có số 0 giả. Nếu thay hợp đồng model bằng trạng thái UI mới, cập nhật probe để kiểm tra hành vi render tương đương, không chỉ đổi assertion thành giá trị hiện đang sai.

**Nghiệm thu:** Cache 9 của ngày cũ trong chu kỳ 3 ngày không biến thành số 0 được xác nhận; có mạng thì thay bằng tổng server hiện tại, không mạng thì giữ trạng thái chưa biết. Số 0 hợp lệ server trả về ở đầu chu kỳ vẫn phải hiển thị bình thường.

### R02 — P1: Dọn trạng thái chuyển ngày chưa đúng phạm vi và chưa đủ các nhánh

**Vị trí:** `HomeViewModel.kt:432`, `469`, `689`, `721`, `797`; timer tại `205`; `HomeScreen.kt:173`.

**Tái hiện đã FAIL:**

- `nextDayRefreshFailureMustClearPreviousDayTestFlag`: tải được trạng thái “đã test” ngày D; chuyển clock sang D+1; bắt đầu refresh mới và server lỗi 500. `initialDay` đã là D+1 nên phép so sánh cuối request cho `isDayRollover=false`; cờ ngày D vẫn là **true**.
- `staleFeedMustNotPublishUnsanitizedJoinedStats`: feed có `stats_date` hôm qua; danh sách chính bị sanitize, nhưng `joinedStatsMap = joinedStats` vẫn xuất bản nguyên tổng **9** và cờ ngày cũ cho thẻ app đã tham gia.

**Các nhánh cần sửa cùng gói:**

- Nhánh offline return sớm trước khi dọn dữ liệu ngày cũ.
- Nhánh catch chỉ xét ngày ở đầu request, không xét ngày của dữ liệu đang hiển thị.
- `hasStaleStatsDate` của feed hiện bị gộp vào `isDayRollover`, từ đó còn đặt `personal.todayCheckins=0`, xóa `dailyTask` và reset quest/owner stats dù dashboard riêng có thể thuộc hôm nay. Source cho thấy lỗi này; ca thứ 7 đã chuẩn bị để xác nhận sau khi gỡ R00, chưa có kết quả thực chạy cho ca bổ sung.
- Timer đang khởi chạy từ `init` của ViewModel, chưa gắn start/stop với foreground như kế hoạch; hiện dùng clock thực riêng, khó kiểm thử bằng clock nghiệp vụ được inject.

**Kế hoạch:**

1. Theo dõi ngày của dữ liệu đã render; sanitize ngay khi bắt đầu refresh/resume hoặc nhận sự kiện đổi ngày, trước nhánh offline, trước request và trước fallback lỗi.
2. Dọn đồng nhất display/paused/completed/search/joined, cờ cá nhân và các trường theo ngày. Giữ số cộng dồn chưa xác nhận ở trạng thái R01, không gán số giả.
3. Tách freshness của feed khỏi dashboard/các nguồn khác; feed cũ chỉ làm mất hiệu lực phần feed cũ. Không dùng một cờ để xóa dữ liệu dashboard mới.
4. Cho timer và các quyết định ngày dùng clock có thể kiểm soát; gắn vòng đời foreground/resume, hủy lịch khi pause và ViewModel clear. Khi resume sau nhiều ngày chỉ refresh cần thiết, không tạo hàng loạt timer/request.
5. Giữ sequence/session/mutation guards; phối hợp với tính năng xáo trộn mới vì cả hai cùng xử lý chuyển ngày và thứ tự danh sách.

**Nghiệm thu:** Hai probe đã FAIL phải PASS; ca dashboard mới vẫn giữ số 2 khi feed cũ; test thêm offline qua ngày, resume sau ngày mới, rollover trong request, toàn bộ danh sách và timer pause/resume. Trạng thái “Đã test hôm nay” không sống sang ngày mới vì mạng lỗi.

### R03 — P1: Payload sai ngày được cache thành hôm nay và không có refetch sau khi loại bỏ

**Vị trí:** `CampaignRepository.kt:1629`, `1712`, `1722`; `HomeViewModel.kt:689` và luồng sau khi xuất bản state.

**Tái hiện đã FAIL:**

- `repositoryMustNotCacheOldServerDateAsFreshToday`: server trả `stats_date` hôm qua, repository vẫn ghi `businessDate=requestDate` hôm nay. Gọi tiếp không force chỉ dùng cache: **1 lần gọi mạng thay vì 2**.
- `staleFeedMustScheduleAControlledRefetch`: server thử nghiệm sẽ trả dữ liệu đúng hôm nay ở lần gọi thứ hai, nhưng Home chỉ gọi feed **1 lần** rồi dừng sau khi loại bỏ dữ liệu cũ.

**Ảnh hưởng:** Sau rollover hoặc response cũ, Home có thể giữ số 0 giả/chưa biết tới khi người dùng tự kéo làm mới hoặc TTL hết; cache sai ngày còn tiếp tục cung cấp lại payload cũ. Bản báo cáo triển khai nói đã “vô hiệu hóa và làm mới” nhưng phần làm mới chưa có trong luồng này.

**Kế hoạch:**

1. Validate ngày payload trước khi đưa vào `cachedHomeFeed` và `statsCache`, đồng thời recheck business day/session/mutation tại thời điểm publish. Không dán ngày bắt đầu request lên payload server thuộc ngày khác.
2. Áp dụng cùng quy tắc cho feed và batch stats, kể cả stats tải bổ sung cho joined app. Xử lý legacy thiếu `stats_date` theo ngày request chỉ khi request không băng qua ngày mới.
3. Khi nhận response stale, invalidate đúng cache và thực hiện retry có giới hạn, không lấy lại chính cache stale. Nếu tiếp tục sai ngày hoặc lỗi mạng, giữ trạng thái chưa biết và cho phép retry bằng resume/pull-to-refresh.
4. Trường hợp request bắt đầu trước 00:00 nhưng server trả ngày mới hợp lệ, quyết định theo ngày từng nguồn thay vì tự động xóa toàn bộ response mới. Kiểm thử cả hai chiều.

**Nghiệm thu:** Hai probe PASS; không có vòng retry vô hạn, duplicate request hoặc stale overwrite; trường hợp một stat cũ không biến tất cả dữ liệu cá nhân hợp lệ thành 0.

### R04 — P2: Cấu hình admin có thể được publish sau khi đổi tài khoản

**Vị trí:** `AdminUsersViewModel.kt:52`, `220`, `243`, các lần gán `homeStatsConfig` tại `229` và `253`.

**Tái hiện đã FAIL:** `adminConfigResponseMustNotPublishAfterAccountSwitch`. Bắt đầu request khi tài khoản A là admin; giữ response đang bay; đổi sang B; trả response đã được xác thực cho A. ViewModel vẫn đưa cấu hình chu kỳ 3 và revision của A vào state sau đổi phiên. Không có kiểm tra UID/generation/sequence hoặc observer dọn cấu hình khi đổi tài khoản.

Đây là lỗi phạm vi state client; các RPC server vẫn chặn member gọi mới trong thử nghiệm. Không coi đây là bằng chứng member đã vượt kiểm tra role ở database.

**Kế hoạch:** Chụp UID/session generation/request sequence lúc load/save, kiểm tra lại trước mọi cập nhật state/signal/snackbar; hủy hoặc bỏ response cũ. Xóa cấu hình, revision, lỗi và trạng thái đang tải/lưu khi đổi phiên. Chỉ cho hiển thị/tải/lưu sau khi xác nhận role admin của tài khoản hiện tại. Bảo toàn `CancellationException`; không nuốt cancellation thành lỗi tác vụ bình thường. Khi gặp revision conflict, tải lại cấu hình và yêu cầu người thao tác chọn/lưu trên trạng thái mới, không gửi lại revision cũ lặp vô hạn.

**Nghiệm thu:** Probe đổi tài khoản PASS cho cả load và save; logout trong request không publish cấu hình/success của phiên cũ; lỗi không làm UI tự nhận chính sách mới chưa lưu.

### R05 — P2/P3: Revision có thể bỏ qua và còn phụ thuộc timezone phiên SQL

**Vị trí:** `supabase/migrations/047_home_stats_cycle.sql:165`, `201`, `_get_home_stats_revision()` tại `97`; các model/params và RPC repository liên quan.

**Tái hiện SQL đã FAIL:**

- `stale_admin_cannot_overwrite_without_revision`: admin thứ nhất lưu lịch với revision hợp lệ; admin thứ hai gửi NULL thì server bỏ qua kiểm tra và ghi đè lịch. Chuỗi rỗng cũng được bỏ qua theo điều kiện hiện tại. Lock chỉ tuần tự hóa ghi, không thay được optimistic revision check. Mức **P2**, cần đáp ứng hợp đồng chống ghi đè của kế hoạch.
- `same_revision_is_independent_of_session_TimeZone`: không sửa chính sách, chỉ đổi `SET timezone` giữa UTC và Asia/Ho_Chi_Minh thì hash revision thay đổi vì dùng `updated_at::text`. Mức **P3**, có thể tạo xung đột giả ở các session cấu hình timezone khác nhau; chưa có bằng chứng app hiện tại luôn gặp tình huống này.

**Kế hoạch:** Bắt buộc revision khác NULL/rỗng cho API ghi cấu hình; không có nhu cầu client cũ gọi RPC mới này. So sánh dưới lock, trả lỗi thiếu revision khác lỗi xung đột. Đổi revision sang một số tăng đơn điệu hoặc chuẩn hóa timestamp UTC/epoch trước khi băm; không phụ thuộc định dạng/timezone session. Đồng bộ params Android và trạng thái xử lý xung đột ở R04.

**Nghiệm thu:** NULL/rỗng/sai revision không ghi dữ liệu/audit; hai admin cùng revision chỉ có một thay đổi được chấp nhận; timezone khác nhau không tạo revision khác cho cùng dữ liệu. No-op không đặt lại mốc chu kỳ.

### R06 — P2: Chưa bảo vệ chính sách đã có hiệu lực khỏi sửa hồi tố

**Vị trí:** `047_home_stats_cycle.sql:38`, `175`, `197`, các nhánh UPDATE/DELETE chính sách; hiện không có trigger kiểm tra bất biến.

**Tái hiện SQL đã FAIL:** `active_policy_must_be_immutable_for_operational_DML`: role `service_role` được cấp UPDATE và có thể đổi trực tiếp chính sách đang chạy. Đây là thiếu guard vận hành so với kế hoạch, **không phải quyền member/anon**, và cũng không thể bảo vệ tuyệt đối trước superuser chủ động tắt guard.

**Rủi ro thêm từ source:** Ngày `v_today/v_tomorrow` được chốt bằng `now()` trước khi chờ lock. Request bắt đầu trước 00:00 nhưng lấy lock sau 00:00 vẫn có thể sửa/xóa lịch đã chuyển sang hiệu lực. Chưa chạy thực nghiệm hai kết nối thật qua nửa đêm; cần thêm kiểm thử clock/lock riêng khi sửa.

**Kế hoạch:**

1. Trigger chặn INSERT hồi tố thông thường và UPDATE các cột nghiệp vụ/DELETE chính sách đang hoặc đã có hiệu lực, dùng ngày từ wall clock Việt Nam. Seed nền phải được tạo an toàn trước khi bật bảo vệ.
2. Tính lại ngày áp dụng sau khi có lock, dùng `clock_timestamp()` cho quyết định quản trị; kiểm tra ranh giới ngay trước DML. Nếu ngày đổi trong thao tác, từ chối/rollback có kiểm soát, không sửa chính sách đã chạy.
3. Không chặn các thay đổi metadata hợp lệ như FK `ON DELETE SET NULL` của người thao tác bằng một trigger cấm mọi UPDATE quá rộng. Dọn lịch tương lai phải ghi audit đầy đủ.
4. Không tự động sửa/xóa chính sách đã có hiệu lực để “reset” bộ đếm. Hoàn tác nghiệp vụ bằng lịch mới trong tương lai.

**Nghiệm thu:** Probe bất biến PASS; cập nhật/hủy lịch ngày mai vẫn hoạt động; seed và migration chạy lại an toàn; test chờ lock qua ranh giới ngày không sửa lịch đang có hiệu lực; audit phản ánh chính xác actor và thay đổi được chấp nhận.

## 4. Trình tự sửa và nghiệm thu

1. Chốt nguồn đang sửa đồng thời và gỡ R00. Giữ nguyên công việc cache/media/xáo trộn, không reset/clean hoặc thay file bằng bản HEAD.
2. Làm **R01 → R02 → R03** như một nhóm về trạng thái dữ liệu; chốt hợp đồng unknown/legacy/current trước, rồi sửa mọi đường cache/render. Các test được bổ sung phải đi qua code thật, không chỉ tìm chuỗi source.
3. Làm **R04** cho vòng đời cấu hình admin.
4. Làm **R05 + R06** cho backend. Nếu 047 đã áp dụng ở bất cứ môi trường chia sẻ nào, tạo migration bổ sung theo số còn trống; không chỉ sửa 047 và giả định môi trường đã chạy sẽ tự nhận thay đổi. Lượt review chưa kiểm tra trạng thái deploy live.
5. Chạy lại 7 probe Android hiện có; bổ sung test cho offline/foreground clock, save sau logout, batch stats, kiểm tra UI placeholder và hai kết nối admin. Sửa test cũ đang assert số 0 giả.
6. Chạy 8 probe SQL độc lập, 14 ca SQL cũ và thêm submit/reject/resubmit/approve qua **RPC thật**. Ca SQL cũ số 10 tự viết query mô phỏng và ca 12 xóa check-in trực tiếp, nên không đủ bằng chứng toàn bộ luồng báo cáo đã được kiểm thử.
7. Chạy bộ unit test gốc, `assembleDebug`, lint liên quan; kiểm tra VI/EN và UI trên emulator/thiết bị nếu có. Ghi rõ ca nào không chạy được. Không tự xóa/skip probe FAIL để tuyên bố hoàn tất.
8. Viết lại báo cáo triển khai với kết quả chạy mới, phân biệt test cũ xanh với feature acceptance. Chỉ nghiệm thu sau khi lỗi P1/P2 được xử lý và không còn build blocker.

### Lệnh tái kiểm tra

Các lệnh chạy trong `F:\AI\12Tester`; dùng JBR/Android SDK đã cấu hình của máy. Test SQL chỉ tạo database PGlite local, không dùng thông tin kết nối live.

```powershell
node supabase/tests/run-home-stats-cycle-test.mjs
node audit/home-stats-review-2026-10-28/sql-independent-review.mjs

$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\nguye\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests com.ntt.crosstest.data.HomeStatsIndependentReviewTest -I audit/home-stats-review-2026-10-28/review.init.gradle --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Chạy từng lệnh Gradle tuần tự. Kết quả 6 probe Android cũ nằm trong XML đã lưu; source audit hiện có ca thứ 7 chưa được chạy do R00, nên không dùng số lượng test hiện trong file để thay cho kết quả XML.

## 5. Điều kiện giữ nguyên nghiệp vụ

- Chu kỳ 1/2/3 ngày do admin điều khiển; thống kê số lượt qua các ngày, không đổi thành số người duy nhất toàn chu kỳ, không dùng cửa sổ trượt.
- Khi có số hợp lệ, tester vẫn thấy định dạng `số lượt/mục tiêu hôm nay`; không công bố cấu hình hay hướng dẫn chu kỳ cho người thường.
- Mọi quyền test, nhiệm vụ, Credit/tip, duyệt/từ chối, phạt, xếp hạng và thống kê ngày ở màn khác tiếp tục dùng dữ liệu ngày thật.
- Tính năng xáo trộn đang được làm riêng phải tiếp tục giữ ưu tiên chỉ tiêu/Boost/tip như yêu cầu của tính năng đó; các sửa lỗi thống kê không được tự đưa tổng nhiều ngày vào khóa ưu tiên.
