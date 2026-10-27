# Kiểm tra lại cache và kế hoạch sửa cho Gemini (Antigravity) — 27/10/2026

## 1. Kết luận

**Vẫn còn lỗi. Chưa đạt kế hoạch `KE_HOACH_TOI_UU_CACHE_2026-10-27.md`.**

Kiểm tra working tree tại HEAD `020e550e0f3fdd63f11d1c72419409e499365879`, gồm 10 file production đang sửa và `CacheRegressionTest.kt` chưa được Git theo dõi. Kết quả áp dụng cho source trước khi bàn giao tài liệu này sang Gemini.

- Đã chạy lại toàn bộ 53 test hiện có cùng 7 probe bổ sung bằng Robolectric + Supabase client thật + Ktor MockEngine: **60 test, 53 PASS, 7 FAIL bằng assertion, 0 errors**. Cả 7 probe mới đều tái hiện được lỗi. Không gọi Supabase/Firebase thật.
- `:app:assembleDebug`: **PASS**.
- `:app:lintDebug`: hoàn tất, **0 Error, 407 Warning, 1 Hint**. Dự án cấu hình `abortOnError = false`; vì vậy đã đọc XML thay vì chỉ tin exit code. Chưa phân loại từng warning thành cũ/mới; không yêu cầu dọn toàn bộ warning ngoài phạm vi.
- Lượt build đầu dùng kết quả 53 test UP-TO-DATE. Lượt có init script sau đó thực sự thực thi lại cả 60 test, nên kết luận 53 PASS không chỉ dựa vào cache Gradle.
- Chưa đo startup/frame/heap/request p50/p95 trên Android thật; chưa xác minh kill process, backend đã deploy hay atomicity trên filesystem Android.

Đã giữ được các cải tiến hữu ích: CacheEntry bất biến, monotonic TTL ở repository, force-refresh coalescing cùng khóa, một số invalidation sau commit, peek RAM cho detail, force credit logs từ Profile và chuyển load danh sách app trong form sang IO. Không cần bỏ các cải tiến này.

## 2. Bằng chứng test bổ sung

Source: `.tmp/cache-recheck/tests/com/ntt/crosstest/data/CacheRecheckProbeTest.kt`.

| Test | Mong muốn | Kết quả thực tế | Vấn đề |
|---|---|---|---|
| `notificationsFailureForBMustNotReturnAData` | Sau A→B, B lỗi mạng không nhận thông báo A | Nhận notification `a-notif` của A | R01 |
| `statsAreScopedToAccount` | A đã test, B chưa test thì B nhận false | B nhận `hasTestedToday=true` từ A | R02 |
| `feedStartedBeforeClearMustNotRepopulateChildCache` | Feed cũ bị chặn sau `clearMemoryCache` | `listOpenCampaigns()` trả `old-c`, không fetch `new-c` | R03 |
| `differentStatsIdSetsMustNotShareFlightResult` | Caller `[c2]` nhận map có `c2` | Nhận map chỉ có `c1` của caller đầu | R05 |
| `failedColdCountMustNotCacheZeroAsSuccess` | Count tải lỗi phải có thể retry | Lần đọc sau không request vì đã cache số 0 | R06 |
| `profileNetworkFailureMustSurfaceAsFailure` | Lỗi tải profile phải được phân biệt với không có profile | API trả null, không báo lỗi | R06 |
| `memoryPeekMustSanitizePreviousDay` | Peek snapshot ngày cũ không hiển thị 9 test như hôm nay | `todayCheckinCount=9` giữ nguyên | R09 |

Hai probe concurrency dùng `CompletableDeferred`, `CoroutineStart.UNDISPATCHED` và tín hiệu request-start có timeout; không dùng sleep để suy đoán request đã chạy. Các assertion kiểm tra hành vi cần đạt sau sửa. BUILD FAILED của lượt probe là do 7 assertion này, không phải compiler/setup.

Evidence lưu cục bộ trong thư mục Git-ignore `.tmp/cache-recheck/`:

- `results/*.xml`: kết quả thực thi lại 60 test.
- `probes.log`: log lượt test cuối.
- `baseline-results/`: XML bộ 53 test trước khi bổ sung probe.
- `validation.log`: build + lint.
- `source-hashes.txt`: SHA256 bốn source chính tại thời điểm audit.
- `recheck.init.gradle`: thêm riêng nguồn Kotlin test, không thay build script dự án.

## 3. Các lỗi cần sửa

### R01 — P1: fallback lỗi mạng bỏ qua ranh giới tài khoản

`CampaignRepository.kt`: `getMyNotifications` khoảng dòng 1977–2007; cùng mẫu trong `getUnreadWarnings`, `getWeeklyWarningsCount`, `getTodayCheckinCount`, `getPendingReportsCountForOwner`, `getUserSlots`.

Đường hit có kiểm tra `scopeUid`, nhưng catch lại đọc thẳng `cachedNotifications?.value` và trả về, không kiểm tra owner/generation/version. Cache của A còn trong biến sau đổi phiên; B miss rồi tải lỗi sẽ nhận dữ liệu A. Probe notification đã xác nhận hành vi này.

**Sửa:** chỉ trả stale entry thuộc đúng scope và generation còn hiệu lực, theo chính sách stale rõ ràng. Nếu không có bản cùng owner, ném lỗi hoặc trả trạng thái lỗi có kiểu; không trả cache tùy ý. Áp dụng cùng quy tắc cho mọi fallback, không chỉ notification.

### R02 — P1: nhiều dữ liệu phụ thuộc phiên vẫn mang scope null

`CampaignRepository.kt`: `getCampaignsStatsMap` dòng 1529+, `getHomeFeed` 1619+, `getCampaignReports` 1221+, `peekCampaignReports` 336+, và các cache con được `getCampaignDetailPage` ghi ở 1720+.

- Stats có `hasTestedToday` nhưng lưu/đọc với `scopeUid=null`; A→B vẫn hit stats của A. Probe đã xác nhận.
- Home feed chứa stats cá nhân nhưng validity cũng dùng null.
- Reports khóa bằng campaign + `canManage`, không có uid. `false` không đồng nghĩa dữ liệu công khai: query trực tiếp còn bị RLS/auth scope giới hạn. Cache reports từ detail cũng ghi scope null.
- `getAllProfiles` và dữ liệu phụ thuộc quyền phải được audit theo cùng nguyên tắc; cache công khai và dữ liệu do RLS lọc không được đánh đồng.
- `CacheEntry.isFresh` hiện coi `scopeUid=null` như không kiểm tra owner, nên chưa phân biệt guest với public.

**Sửa:** biểu diễn public/user/guest scope rõ ràng, capture scope đầu request, kiểm tra tại hit, stale fallback, publication và ghi các cache con. Auth transition cần tăng generation cho cả A→B trực tiếp, không chỉ trông chờ `NotAuthenticated`. Không để request A đang bay ghi đè cache B. Test cả read thông thường và peek.

### R03 — P1: response cũ được đóng dấu version mới và làm sống lại cache sau clear/mutation

`clearMemoryCache` dòng 215+ tăng các mutationVersion nhưng **không tăng sessionGeneration**. Nhiều writer chỉ kiểm tra generation. Các writer tổng hợp còn đọc version con **sau await**:

- `getHomeFeed` dòng 1634–1641 lấy `campMutVer`/`statsMutVer` lúc response đã về.
- `getMyDashboard` dòng 1665+ lấy version campaigns/profile lúc commit cache con.
- `getCampaignDetailPage` dòng 1723+ lấy version reports/profile lúc response về.

Vì vậy request trước clear hoặc trước mutation có thể ghi dữ liệu cũ nhưng mang version hiện tại; các reader mới coi đó là dữ liệu fresh. Probe feed→clear→response đã xác nhận `old-c` sống lại ở cache list.

Ngoài ra, writer chỉ kiểm tra version rồi ghi ở hai thao tác tách biệt vẫn có thể bị invalidate xen giữa. Với entry mang version cũ, reader mới có thể bỏ qua được, nhưng response cũ còn có thể đè bản mới hoặc lọt vào fallback thiếu kiểm tra R01.

**Sửa:** capture token gồm scope + generation + version của **mọi dependency** trước tải; commit tổng hợp/các cache con chỉ với token còn hợp lệ. Không lấy version mới để đóng dấu response cũ. Clear phải vô hiệu hóa toàn bộ request cũ, cả request UI/Worker và snapshot có liên quan. Thiết kế điểm commit/invalidate được điều phối; không chỉ rải thêm `if`.

### R04 — P1: Home/Profile/detail chưa có guard publication như yêu cầu

`HomeViewModel.kt`: refresh dòng 356–674; cập nhật UI 584+; snapshot 622–644.

- Mỗi refresh tạo coroutine mới; không có refresh sequence/job guard cho toàn lượt tải.
- Snapshot lấy `userId=repository.currentUserId()` lúc hoàn tất; `CachedSessionData.sessionGeneration` cũng mặc định lấy generation lúc tạo snapshot. Dữ liệu của A tải từ trước có thể bị gắn owner/generation mới sau clear hoặc đổi sang B.
- `_uiState.update` không kiểm tra token scope/request trước publish. Repository chặn ghi RAM vẫn chưa đủ vì caller cũ vẫn nhận result.
- Profile refresh ghép profile/logs/slots tải trước với email đọc ở thời điểm publish; không có guard tương đương. Detail/owner detail cũng cần audit publication.

**Sửa:** capture owner/generation/day/request-sequence trước await; chỉ lượt hợp lệ publish UI và save. Truyền token ban đầu vào snapshot; không lấy owner/generation hiện tại để hợp thức hóa dữ liệu cũ. Định nghĩa coalescing/force và xử lý cancellation. Thêm test deferred đảo thứ tự và đổi phiên giữa tải cho từng ViewModel quan trọng.

**Mức bằng chứng:** lỗi code được xác nhận bằng đọc source; chưa chạy probe ViewModel end-to-end trong lượt này. Báo cáo cũ trong Antigravity có nói đã thêm guard nhưng diff/source hiện tại chưa có guard đó.

### R05 — P2: single-flight stats không chứa tham số campaignIds

`getCampaignsStatsMap` dòng 1536–1539 dùng hằng `"statsMap"` làm key. Hai caller cùng scope/version, một caller `[c1]`, caller khác `[c2]`, bị chia sẻ nguyên result của caller đầu. Đây là lỗi đúng dữ liệu do cơ chế dedup mới, đã có probe xác nhận.

**Sửa:** chuẩn hóa tập ID distinct + sort vào request key, hoặc thiết kế coalescing batch có bảo đảm coverage và slice đúng cho từng caller. Rà mọi API batch/limit/sort/role. Cache có coverage lớn hơn limit cần slice đúng hợp đồng; không chỉ kiểm tra `limit >= requestedLimit` rồi trả nguyên list.

### R06 — P2: vẫn nuốt lỗi, cache số 0/thống kê giả và fallback RPC quá rộng

`CampaignRepository.kt`:

- `getProfiles` dòng 858+ catch lỗi thành empty; `getMyProfile` vì thế trả null khi server 500. Mâu thuẫn cả với comment ở `createCampaign` rằng lỗi mạng được ném lên.
- `getTodayCheckinCount` dòng 918+; pending count và weekly warnings tương tự: cold failure sinh 0 rồi ghi CacheEntry mới. Probe cold count đã xác nhận lần đọc sau không retry.
- Owner stats dòng 1123–1164 ghép members/checkins/feedbacks/reports lỗi thành rỗng rồi cache thống kê 0. Testers và stats legacy có mẫu tương tự.
- `getHomeFeed`, dashboard và detail bắt mọi lỗi RPC rồi trả null; caller fallback legacy cả khi timeout/403/offline. Owner reports, leaderboard/stats cũng fallback quá rộng.
- Nhiều catch rộng bên trong vẫn nuốt `CancellationException` dù wrapper ngoài đã rethrow. Ví dụ owner stats/testers, stats legacy, owner reports; cần rà toàn hàm và caller.

**Sửa:** tách missing-function/signature (ví dụ lỗi PostgREST xác định được) khỏi timeout/offline/403/serialization. Chỉ fallback legacy đúng loại. Trả stale đúng scope hoặc báo failure; empty/0 chỉ được cache từ response thành công thực. Rethrow cancellation ở mọi tầng. Thêm positive control cho empty/0 hợp lệ và test không stamp TTL khi lỗi.

### R07 — P2: invalidation và force propagation vẫn thiếu dependency

- `getCampaignOwnerStats` dòng 1135 gọi `getCampaignReports(... canManage=true)` không truyền `forceRefresh`.
- `approveReport`/`rejectReport` gọi `invalidateReportsAndStats` nhưng helper dòng 304 không xóa/bump cache testers (checkinCount/lastCheckinDate), không invalidate home feed/campaign lists. Feed đang ở domain dashboard nhưng membership/report invalidation chỉ null `cachedHomeFeed`, không tạo một token dependency đầy đủ cho request đang bay.
- `invalidateMembership` xóa ownerStats nhưng không bump domain stats; ownerStats cũ đang bay có thể repopulate như fresh.
- `setCampaignTip` dòng 1441+ còn invalidation thủ công, không bump campaign version/không vô hiệu đầy đủ joined/paused/completed; `createCampaign` cũng còn mẫu null thủ công.
- `invalidateCampaignLists` dùng cho lifecycle chưa xử lý đồng bộ joined/single-item/detail. Slot info phụ thuộc campaign nhưng không phải mọi mutation campaign đều invalidate domain user_slots.
- `adminSetUserLock` bump profile trước RPC, chưa có invalidation sau commit. Reader trong lúc RPC chờ vẫn có thể đóng dấu cùng version và giữ trạng thái trước khóa.
- `getProfiles` seed `scopeUid=p.id` nhưng hit truyền `currentUid=null`, khiến entry có id luôn miss. Cần tách owner scope của cache với ID hồ sơ được truy vấn.

**Sửa:** viết bảng mutation→cache phụ thuộc trước, gom helper sau commit và token nhiều dependency. Test hành vi đọc sau mutation, không chỉ reflection thấy entry bị xóa. Không sửa bằng clear toàn bộ sau mọi mutation.

### R08 — P2: snapshot coordinator chưa bao phủ load/clear và publication

`SessionCacheManager.kt`: chỉ save dùng `diskCoordinator` (146+); `loadSync` 110+ và `clear` 198+ không dùng cùng coordinator. Generation check rồi gán RAM/prefs vẫn tách biệt với clear. Load không capture/check generation nên preload có thể publish sau clear nếu đã đọc được nội dung trước đó.

Clear còn dọn mọi file theo prefix ngoài coordinator, có thể đua với writer mới hợp lệ. Save fallback từ ATOMIC_MOVE sang move không atomic khi bắt mọi Exception; chưa có test giữ bản tốt khi filesystem từ chối atomic move/write.

**Sửa:** điều phối load/save/clear dưới một cơ chế commit phù hợp; invalidation phải có hiệu lực ngay với writer/load cũ, không khóa IO kéo dài trên main. Schema/owner/timestamp phải được kiểm chứng nhất quán với metadata. Dùng primitive atomic phù hợp Android và test lỗi ở từng ranh giới; không tuyên bố atomicity thực trên Android chỉ vì Robolectric xanh. Snapshot save test hiện tại không có gate tại commit nên chưa đủ chứng minh race này đã đóng.

### R09 — P2: freshness/ngày VN chưa đúng ở peek và RAM

- `peekMemory()` dòng 97 trả dữ liệu chưa sanitize; Home ưu tiên peek nên bỏ qua `loadSync().sanitizeForDate()`. Probe ngày cũ trả count 9 đã xác nhận.
- `sanitizeForDate` chỉ xử lý khi `dateVn != null`; snapshot schema cũ chưa có dateVn không được xử lý tương ứng timestamp.
- ownerStatsMap/questSummary và các giá trị phụ thuộc ngày chưa được xử lý đầy đủ. Snapshot chưa có trạng thái stale/giới hạn freshness dựa timestamp.
- RAM entries có elapsed TTL nhưng không có ngày VN trong validity, vẫn có thể phục vụ trạng thái hôm qua sau 00:00.
- Dashboard TTL vẫn 3 phút, trong khi warning/notification TTL con là 1 phút.

**Sửa:** inject clock cho monotonic TTL + ngày nghiệp vụ; sanitize mọi đường hydrate/peek, hỗ trợ schema cũ có chủ đích. Định nghĩa stale hiển thị được và dữ liệu đủ mới để thao tác. Test 23:59→00:00, resume ngày sau, date null, chỉnh giờ máy, TTL các thành phần dashboard.

### R10 — P2/P3: I/O main, package change và eviction chưa xong

- Home constructor dòng 137 vẫn `peekMemory() ?: loadSync()`; Profile dòng 58 gọi `loadSync` trực tiếp. Preload chưa hoàn tất vẫn read/parse trên main.
- Home clear-cache dòng 685 gọi `InstalledAppCache.refresh` đồng bộ, enumerate package/label/icon.
- `AppIcon` vẫn gọi `getInstalledAppDrawable` trong `remember` khi iconUrl rỗng; cache miss còn gọi PackageManager trên compose thread. Bỏ lookup khi có URL chưa giải quyết miss.
- `InstalledAppCache.peekInstalledUserApps()` dùng `takeIf { isNotEmpty() }`, chưa phân biệt đã load rỗng với chưa load.
- Chưa có package add/remove/replace invalidation cả list/icon/negative cache + version Compose/resume reconciliation. List installed vẫn giữ Drawable ngoài LRU.
- Các map repository chưa có cap/sweep; flight đã có remove khi hoàn tất nhưng cần test success/error/cancellation và race create/remove. Không khẳng định flight đang leak nếu chưa tái hiện.
- Một số comment tiếng Việt trong CampaignRepository đã có dấu hiệu mojibake; rà diff encoding khi chỉnh, không mở rộng refactor không liên quan.

**Sửa:** constructor chỉ peek, hydrate suspend IO; clear-cache/PackageManager chạy IO; icon miss tải async + version; cache empty đúng; bổ sung package event/reconciliation và ngân sách map/Drawable. Giữ cấu hình Coil làm baseline tới khi đo.

## 4. Thứ tự thực hiện cho Gemini

### G00 — Đóng băng evidence và bổ sung regression

1. Đọc kế hoạch gốc và tài liệu này, xem git status/diff. Giữ nguyên mọi thay đổi chưa commit.
2. Giữ nguyên XML/log audit; chuyển 7 probe thành test duy trì trong app/src/test, tránh chạy trùng cùng class qua init script.
3. Bổ sung probe UI publication, report scope, fallback đúng uid và mutation xen giữa RPC tổng hợp. Thay các `delay(50)` của test cũ bằng gate/scheduler có kiểm soát.
4. Không đổi assertion để test xanh; test phải xác minh dữ liệu/request thực, không mô phỏng lại thuật toán cache.

### G01 — Sửa P1 trước: scope, commit token và UI publication

Xử lý R01–R04 cùng một thiết kế: cache scope rõ ràng; session transition/clear tăng token ngay; mọi response dùng token đầu request; không restamp cache con; stale fallback cùng owner; UI và snapshot cùng kiểm tra request/scope.

**Gate:** A→B, A→guest→B, đổi uid trực tiếp, clear/mutation giữa request, response đảo thứ tự không làm dữ liệu cũ vào cache/UI/đĩa; ba probe R01/R02/R03 xanh và có test UI riêng. Luồng Worker vẫn an toàn khi Activity không sống.

### G02 — Sửa key, lỗi tải và invalidation

Xử lý R05–R07. Lập bảng dependency cho create/update/delete/join/leave/submit/approve/reject/slot/topup/quest/admin/lifecycle. Force phải truyền xuyên các component. Fallback chỉ cho missing-RPC; error/cancellation không thành thành công 0/rỗng.

**Gate:** caller stats các tập ID khác nhau nhận đúng dữ liệu; same-key success/error/force 10 caller chỉ một fetch thích hợp; count/profile probes xanh; mutation đọc lại đúng stats/testers/profile/feed/slots; không request storm khi 500/timeout/403.

### G03 — Snapshot, ngày VN và main-safe/package/cache budget

Xử lý R08–R10. Sửa coordinator và async hydrate trước; sau đó freshness/day/clock và package/icon/eviction.

**Gate:** peek date probe xanh; load/save/clear có deterministic race tests; failure write giữ bản tốt; không I/O đĩa/PackageManager trên compose/constructor/clear UI; empty installed list được cache; package change cập nhật list/icon; map không tăng vô hạn theo ID.

### G04 — Kiểm tra cuối và báo cáo trung thực

1. Chạy toàn bộ unit/Robolectric gồm 53 test hiện có + 7 probe + test bổ sung, không bỏ test để né lỗi.
2. Chạy `:app:assembleDebug :app:lintDebug`; đọc XML lint, phân biệt diagnostics do thay đổi với cảnh báo sẵn có.
3. Viết `BAO_CAO_SUA_CACHE_CON_SOT_2026-10-27.md`: từng R01–R10, file/dòng, test và kết quả, gate còn mở.
4. C06 của kế hoạch gốc vẫn cần thiết bị/dataset/trace. Nếu chưa có thiết bị, ghi rõ chưa đo; không tuyên bố đã hoàn tất C06 hoặc đã tối ưu hiệu năng toàn diện.

## 5. Lệnh tái hiện audit

PowerShell từ `F:\AI\12Tester` (JDK hiện có trên máy):

```powershell
$env:JAVA_HOME = 'C:\Users\nguye\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$env:ANDROID_HOME = 'C:\Users\nguye\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
.\gradlew.bat -I .tmp/cache-recheck/recheck.init.gradle :app:testDebugUnitTest --no-daemon --offline
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon --offline
```

Init script dùng `android.sourceSets.test.kotlin.srcDir(...)` cho AGP 9 hiện tại và buộc test thực thi lại. Chỉ thêm `java.srcDir` đã không đưa probe Kotlin vào compiler; lượt đó UP-TO-DATE không được tính là chạy probe. Sau khi chuyển probe vào test chính, bỏ init script khi chạy để không trùng nguồn.

## 6. Ràng buộc bàn giao

- Không reset/clean hoặc ghi đè các thay đổi người dùng; không commit/push/deploy.
- Không gọi backend production để tái hiện; dùng mock/fixture. Không đọc/in/chia sẻ secret từ local.properties hoặc auth token.
- Không thay reward/credits nghiệp vụ, quyền RLS, submit idempotency, pending receipt/retry policy ngoài phần cache cần sửa.
- Không dùng tăng TTL, force mọi read hoặc clear toàn bộ sau mọi mutation để che lỗi.
- Không coi 12/12 probe cũ hoặc build xanh là bằng chứng đã đạt cả C00–C06. Mỗi kết luận phải có evidence đúng phạm vi.
- Người kiểm tra chỉ thêm tài liệu này và harness/evidence trong `.tmp/cache-recheck/`; không sửa production hoặc test gốc trong lượt audit.
