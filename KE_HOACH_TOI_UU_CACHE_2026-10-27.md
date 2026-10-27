# Kiểm tra và kế hoạch tối ưu cache — 27/10/2026

## 1. Kết luận và phạm vi

**Chưa tối ưu.** Ứng dụng đã có nhiều tối ưu hữu ích, nhưng tính nhất quán giữa các lớp cache, phạm vi tài khoản, xử lý lỗi và điều phối tải còn thiếu. Cần sửa tính đúng trước khi tăng TTL hoặc tăng dung lượng cache.

- Kiểm tra working tree tại HEAD `d2da711089f31eb1128d27c6cf499baea1306af8`, bao gồm các thay đổi chưa commit đang có. Kết luận áp dụng cho working tree này, không chỉ cho HEAD.
- Đọc repository, snapshot, Application, các ViewModel/màn hình liên quan, PackageManager/icon, Coil, ảnh prepared/pending và SQL định nghĩa feed/dashboard.
- Chỉ thêm tài liệu này và harness audit trong `.tmp/cache-audit/` đã được ignore. Không sửa production, test gốc, dependency, migration hoặc các thay đổi chưa commit có sẵn.
- Các con số p50/p95, dung lượng thực, tỷ lệ hit và độ mượt **chưa được đo trên thiết bị**. Không khẳng định có ANR/OOM đã xảy ra.
- Không gọi Supabase/Firebase thật, không deploy, không commit/push.

## 2. Luồng cache hiện tại

```text
Application.onCreate
  ├─ InstalledAppCache.init → IO → package set + danh sách app/icon
  ├─ SessionCacheManager.preload → IO → JSON → RAM
  └─ CampaignRepository dùng chung cho UI và Worker

Home/Profile khởi tạo
  ├─ loadSync → RAM; nếu RAM chưa có thì đọc/parse JSON đồng bộ
  ├─ dựng UI từ snapshot nếu điều kiện userId cho phép
  └─ refresh
      ├─ feed + dashboard RPC → cache tổng hợp → ghi một số cache con
      ├─ RPC thất bại → legacy nhiều truy vấn
      └─ Home ghi snapshot JSON toàn màn hình

Thao tác ghi → invalidation thủ công từng biến/map
Đăng xuất → clear RAM/snapshot/ảnh; chưa có generation ngăn response cũ ghi lại
Ảnh remote → Coil: RAM 25% heap, disk 50 MiB
```

| Lớp | Chính sách hiện tại | Nhận xét |
|---|---|---|
| CampaignRepository RAM | Phần lớn 3 phút; warning/log/notification 1 phút; topup package 10 phút | Có TTL nhưng nhiều cache không mang uid/ngày/tham số; map không có eviction theo dung lượng |
| Dashboard tổng hợp | 3 phút, có uid và cờ leaderboard | Có thể phục vụ warning/notification cũ hơn TTL 1 phút của API con |
| Session snapshot | Một JSON trong `filesDir`, có timestamp | Timestamp không được kiểm tra khi hydrate; preload không bảo đảm xong trước constructor |
| InstalledAppCache | Package snapshot/negative cache 15 giây; icon LRU 120 mục | Danh sách app và icon chưa cập nhật đầy đủ theo sự kiện cài/gỡ/cập nhật |
| Coil | RAM 25%, disk 50 MiB | Đã có giới hạn; chưa có bằng chứng cần tăng/giảm |
| ImageHashHelper | LRU 128 hash theo URL | Đã giới hạn; chỉ đúng khi nội dung URL không bị thay tại chỗ |
| PreparedScreenshot | Dọn file quá 48 giờ khi khởi động | Là ảnh tạm; tách khỏi pending/receipt cần tồn tại để retry |

Điểm tốt cần giữ: singleton repository dùng chung Worker/UI; RPC gộp feed/dashboard/detail; tải leaderboard theo tab; cache stats theo từng ID; negative package cache; cache ảnh có giới hạn; snapshot bỏ bớt list campaign trùng lặp. Các điểm này không đủ để bảo đảm mọi đường đọc/ghi nhất quán.

## 3. Các vấn đề và bằng chứng

### F01 — P1: cache chưa cách ly đầy đủ theo tài khoản và vòng đời request

**Vị trí:** `CampaignRepository.kt:190–273, 568–578, 828–835, 1152–1194, 1575–1597`; `MainActivity.kt:243–247`; `TestCheoApp.kt:148–170`.

- `cachedMyProfile`, danh sách cá nhân, warnings, notifications, rank và reports không đồng loạt khóa theo uid. Feed/stats có `hasTestedToday` theo người dùng nhưng cache theo khóa chung/campaign ID.
- Dashboard và detail đã có uid; điều này không bảo vệ các cache con được chúng ghi vào.
- `clearMemoryCache()` chỉ xóa dữ liệu hiện có. Request đang chờ mạng vẫn có thể hoàn tất sau đó và ghi dữ liệu cũ trở lại.
- Nhánh `NotAuthenticated` ở Activity chỉ dọn snapshot; không dọn repository. Đường đăng xuất chủ động có dọn repository, nhưng không chặn request đọc cũ hoặc cập nhật UI cũ sau clear.

**Tác động:** API cache có thể trả profile A trong phiên B; dữ liệu đã xóa có thể sống lại. Đây là lỗi ranh giới client, không phải bằng chứng vượt RLS hoặc đọc trái phép từ backend production.

### F02 — P1: snapshot có thể được ghi lại sau clear

**Vị trí:** `SessionCacheManager.kt:73–135`; `HomeViewModel.kt:620–644`.

- `save`, `loadSync`/preload và `clear` không có chung khóa/generation.
- `save` gán RAM trước khi hoàn tất ghi đĩa; nhiều writer dùng chung một file `.tmp`.
- Một save đã bắt đầu có thể tiếp tục sau clear và tạo lại file/metadata phiên cũ.
- Home lấy uid lúc tạo snapshot sau khi đã tải dữ liệu, thay vì giữ uid/generation từ đầu lượt tải. Nếu phiên đổi giữa chừng, dữ liệu và nhãn owner có thể lệch nhau.
- Fallback `file.delete()` rồi rename làm mất tính nguyên tử của đường thay thế; lỗi ghi/đọc bị nuốt, không có trạng thái phân biệt metadata/file không khớp.

**Tác động:** mở lại dữ liệu phiên cũ sau thao tác xóa/đăng xuất; các lần save đồng thời có thể làm snapshot không nhất quán. Chưa có thử kill process thực trên Android.

### F03 — P1/P2: invalidation chưa phủ đủ các lớp phụ thuộc

**Vị trí:** `CampaignRepository.kt:584–606, 751–767, 1058–1092, 1120–1148, 1244–1279, 1291–1296, 1370–1381, 1407–1476, 1768–1777, 1968–1970`.

| Thao tác | Cache còn có thể cũ | Biểu hiện |
|---|---|---|
| Mở/gia hạn slot; self-unlock; topup; submit report | Xóa `cachedMyProfile` nhưng còn `profileCache[uid]` | `getMyProfile(false)` có thể nạp lại số dư/trạng thái cũ từ lớp dưới |
| Join/leave | `testersCache`, `ownerStatsCache` | Đã sửa members nhưng số tester/danh sách tester vẫn cũ |
| Submit/approve/reject | `testersCache`; một số stats/feed tùy thao tác | Số lần test/trạng thái thống kê không đồng bộ giữa màn hình |
| Approve/reject | Xóa cache trước RPC, chưa có invalidation sau commit | Reader chạy trong lúc RPC chờ có thể cache lại trạng thái trước duyệt |
| Xóa campaign | `campaignCache`, list paused/completed/joined | `getCampaign(id)` vẫn có thể trả app đã xóa |
| Sửa thông tin/trạng thái campaign | `cachedMyJoinedCampaigns` | Fast-path `getCampaign` còn có thể lấy campaign cũ từ list joined |

Ngoài ra, `getCampaignOwnerStats(forceRefresh = true)` không truyền force xuống `getCampaignReports` ở dòng 1074. Profile legacy refresh không truyền force xuống `getMyCreditLogs` ở `ProfileViewModel.kt:193`. Force ở lớp ngoài chưa bảo đảm dữ liệu thành phần mới.

### F04 — P2: lỗi mạng được cache thành dữ liệu rỗng/0 thành công

**Vị trí:** `CampaignRepository.kt:295–325, 839–870, 1299–1350, 1479–1565, 1976–1990`.

- Ví dụ danh sách paused/completed: catch mọi Exception → `emptyList()` → ghi cache và timestamp mới trong 3 phút.
- Notifications/warnings/today count cũng có đường lỗi → rỗng/0 và cập nhật TTL.
- Stats legacy có thể ghép các thành phần tải lỗi thành thống kê 0 rồi cache toàn bộ.
- Catch rộng cũng bắt `CancellationException`, có thể biến việc hủy thành kết quả bình thường hoặc kích hoạt fallback không cần thiết.
- Feed/dashboard/detail coi mọi lỗi RPC như dấu hiệu server cũ, gây fallback nhiều truy vấn khi nguyên nhân thật là mất mạng/timeout/quyền.

**Tác động:** dữ liệu đang có biến mất, UI báo không có dữ liệu dù chỉ tải thất bại; tăng request và kéo dài recovery. Phải phân biệt response rỗng hợp lệ với thất bại.

### F05 — P2: chống request trùng chỉ đúng ở một số nhánh

**Vị trí:** `CampaignRepository.kt:190–213`; `HomeViewModel.kt:223–225, 277–297, 356–358, 584–644`.

- `forceRefresh` bỏ hoàn toàn single-flight. Hai lượt kéo refresh/success event cùng lúc tạo nhiều request cùng khóa.
- Khi người thắng lỗi hoặc không cache được kết quả, các waiter đều thoát khóa rồi tự fetch ngoài khóa; có thể tạo một đợt request trùng.
- Home tạo coroutine mới cho mỗi refresh; chưa có refresh job/request sequence để chặn lượt cũ ghi UI và snapshot sau lượt mới.
- Các cache entry được tách thành nhiều volatile value/timestamp: không phải một transaction; clear/invalidate/refresh có thể xen giữa. Comment happens-before không thay cho snapshot bất biến và generation.

**Tác động:** tốn mạng/CPU và có khả năng response cũ thắng response mới.

### F06 — P2: thiếu chính sách freshness cho snapshot và ngày nghiệp vụ

**Vị trí:** `SessionCacheManager.kt:20–39, 72–87`; `HomeViewModel.kt:137–181`; `CampaignRepository.kt:839–869, 1479–1597, 1606–1677`.

- Snapshot lưu timestamp nhưng không dùng để quyết định dữ liệu nào được hiển thị như dữ liệu hiện tại.
- `dailyTask.forCurrentUi()` đã lọc ngày, nhưng `hasTestedToday`, số test hôm nay và stats còn hydrate nguyên trạng từ snapshot ngày trước.
- RAM TTL 3 phút không có ngày VN trong khóa/validity nên request ở 23:59 có thể phục vụ dữ liệu ngày cũ sau 00:00.
- Dashboard TTL 3 phút có thể trả notifications/warnings sau khi TTL API con 1 phút đã hết.
- `System.currentTimeMillis()` dùng cho tuổi cache RAM; khi giờ máy bị chỉnh lùi có thể kéo dài độ tươi giả. Cần clock elapsed cho TTL RAM; giữ wall clock/ngày VN cho dữ liệu nghiệp vụ và timestamp bền vững.

Không đề xuất xóa mọi dữ liệu offline chỉ vì hết TTL. Cần phân biệt stale có thể hiển thị với dữ liệu đủ mới để quyết định thao tác.

### F07 — P2: cache miss còn chặn luồng giao diện; prewarm màn chi tiết còn phát sinh mạng

**Vị trí:** `SessionCacheManager.kt:72–101`; `HomeViewModel.kt:679–690`; `AppIcon.kt:46–48`; `InstalledAppCache.kt:89–98, 145–164`; `CreateCampaignViewModel.kt:240–250`; `OwnerAppDetailViewModel.kt:60–82`; `CampaignDetailViewModel.kt:253–269`.

- Constructor Home/Profile gọi `loadSync`; nếu preload IO chưa xong, main vẫn đọc/parse JSON. Comment `< 2ms` chưa phải số đo.
- Clear-cache trên UI gọi refresh package/list app đồng bộ; miss icon gọi PackageManager trực tiếp trong `remember`.
- `getInstalledUserApps` là hàm đồng bộ có thể enumerate toàn bộ package/đọc label/icon; được gọi trước khi chuyển IO ở form tạo campaign.
- Owner detail gọi bốn API được đặt tên biến `cached...`, nhưng các API này được phép fetch khi miss. Vì chạy trước `getCampaignDetailPage`, cold open làm nhiều truy vấn legacy rồi mới gọi RPC gộp. Tester detail cũng gọi `getCampaign` fetch trước RPC.

**Tác động:** công việc I/O/IPC nằm trên đường tạo frame và mở màn hình; số request cold-open tăng. Mức giật/thời gian tăng cần đo trên thiết bị.

### F08 — P2/P3: eviction, package change và tham số cache chưa hoàn chỉnh

**Vị trí:** `CampaignRepository.kt:78–188, 874–887, 1976–1990`; `InstalledAppCache.kt:23–43, 58–98`; `PlayStoreHelper.kt:176–201`.

- Nhiều map entry và `flightLocks` không bị xóa theo TTL/cap; hết TTL chỉ ngăn đọc lại, không giải phóng entry.
- Icon LRU giới hạn 120 mục nhưng list installed app giữ riêng `Drawable` của mọi app, không cùng giới hạn đó.
- `ensureFresh` chỉ refresh package set; danh sách app đã cache và icon không được invalidation theo package add/remove/replace. List rỗng hợp lệ bị xem như chưa load.
- Key logs/notifications không chứa `limit`; cache 20 dòng có thể đáp ứng sai một request đòi 50. Chưa thấy caller production hiện tại chủ động đổi limit; đây là lỗi hợp đồng API tiềm ẩn.
- Feed hiện tải cả ba nhóm campaign; snapshot lưu toàn bộ nhóm. SQL migration 020 không phân trang feed. Chưa có số đo chứng minh cần đổi RPC/schema ngay.

### Các lựa chọn hiện tại chưa cần thay đổi

- `AppConfigRepository` nạp cấu hình đĩa để hiển thị nhanh; `MainActivity.kt:183–187` tiếp tục force-refresh nền. Vì có lần đọc mới này, không coi việc hydrate cấu hình là bằng chứng app luôn dùng cấu hình cũ 10 phút khi mở.
- Xóa ảnh Coil khi đăng xuất có chi phí tải lại nhưng đang bảo vệ ranh giới tài khoản; không bỏ bước này chỉ để tăng hit rate.
- Không có disk cache đầy đủ cho mọi màn hình chưa tự nó là lỗi. Chưa có căn cứ bắt buộc chuyển sang Room hoặc triển khai realtime.
- Cache response rỗng thành công là hợp lệ; chỉ cấm biến lỗi thành response rỗng thành công.

## 4. Kết quả kiểm chứng

Harness dùng source production, Robolectric, Supabase client và Ktor MockEngine; không mô phỏng lại thuật toán repository. Reflection chỉ dùng để seed một số cache phụ thuộc. Snapshot probe dùng filesystem Robolectric và latch chặn tại ranh giới `Context.filesDir`.

**Kết quả chạy cuối:** 12 probe, **3 PASS / 9 FAIL bằng assertion / 0 errors**. Lệnh audit kết thúc `BUILD FAILED` do các hành vi mong muốn chưa được đáp ứng, không phải do compiler/setup.

| Probe | Kết quả quan sát | Liên hệ |
|---|---|---|
| Đọc list hai lần trong TTL | PASS: một request | Đối chứng cache cơ bản |
| Nhiều ordinary read cùng khóa | PASS: một request | Đối chứng single-flight nhánh thông thường |
| Response paused rỗng thành công | PASS: cache và dùng lại, một request | Đối chứng empty hợp lệ |
| Profile A rồi chuyển session B | FAIL: nhận profile A thay vì B | F01 |
| Response bắt đầu trước clear, trả về sau clear | FAIL: đọc tiếp vẫn nhận `old-response` | F01/F05 |
| Save snapshot bị tạm dừng rồi clear | FAIL: `old-user` xuất hiện lại từ file | F02 |
| Mở slot đổi số dư 100→80 | FAIL: `getMyProfile(false)` vẫn trả 100 | F03 |
| Join campaign có cache testers trước đó | FAIL: entry testers vẫn tồn tại | F03 |
| Xóa campaign có single-item cache | FAIL: đọc lại còn trả campaign đã xóa | F03 |
| Có paused list tốt rồi forced refresh lỗi mạng | FAIL: bản tốt bị thay bằng empty | F04 |
| Ba forced refresh đồng thời | FAIL: ba request thay vì một | F05 |
| Lịch sử Credit tải 20 rồi yêu cầu 50 | FAIL: chỉ trả 20 dòng dù server fixture có 50 | F08 |

**Bộ test gốc:** chạy riêng không dùng init script audit, **41/41 PASS**, 0 failures/errors, `BUILD SUCCESSFUL`. Bao gồm PendingReportStore 6, ReportSubmitManagerIntegration 2, ReportSubmitPolicy 10, ReportWorkState 4, ReportUrlRetry 12 và ReportUploadWorker 7. Bộ test gốc xanh không phủ các failure cache vừa nêu.

Evidence cục bộ, không được Git theo dõi:

- `.tmp/cache-audit/tests/com/ntt/crosstest/data/CacheAuditProbeTest.kt`: source probe; `.tmp/cache-audit/cache-audit.init.gradle`: gắn nguồn test riêng, không sửa build script.
- `.tmp/cache-audit/probe-results.xml`, `.tmp/cache-audit/gradle.log`: XML và log của lượt audit cuối.
- `.tmp/cache-audit/baseline-results/`, `baseline-summary.json`, `baseline-tests.log`: kết quả 41 test gốc lưu riêng để không bị lượt audit ghi đè.
- `.tmp/cache-audit/source-hashes.txt`: fingerprint các source cốt lõi tại thời điểm kiểm tra.

Các kết luận F06/F07 và phần dung lượng/package change của F08 dựa trên đường code đã đọc, chưa có phép đo/runtime probe trên thiết bị. Trong lượt audit này không chạy thêm assemble/lint, không kiểm tra backend đã deploy hoặc benchmark Android.

Lệnh tái hiện từ PowerShell tại thư mục dự án:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-22'
$env:ANDROID_HOME = 'C:\Users\nguye\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME = 'C:\Users\nguye\.gradle'
.\gradlew.bat -I .tmp/cache-audit/cache-audit.init.gradle :app:testDebugUnitTest --tests com.ntt.crosstest.data.CacheAuditProbeTest --no-daemon --offline
```

Assertions kiểm tra hành vi **mong muốn sau sửa**, vì vậy các failure do assertion là bằng chứng lỗi hiện tại. Lỗi setup/compiler của harness không được tính là lỗi cache. Các vòng chỉnh harness chỉ sửa trong `.tmp`, không sửa ứng dụng để làm test xanh.

## 5. Kế hoạch sửa theo thứ tự

### C00 — Chốt baseline và bộ regression

**Phạm vi:** tài liệu audit, test mới về cache; không đổi production ở bước này.

- Giữ nguyên thay đổi chưa commit có sẵn. Lưu status/diff và kết quả baseline trước khi triển khai.
- Chuyển các assertion audit thành test duy trì trong `app/src/test` khi bắt đầu sửa. Bổ sung test UI publication và response chạy xen giữa mutation/session change.
- Dùng fake clock/scheduler và deferred gate; không dựa vào sleep để chứng minh race. Kết quả rỗng hợp lệ là positive control bắt buộc.
- Chạy lại 41 test hiện có về report/pending/Worker độc lập với probe. Không làm yếu assertion để hợp thức hóa dữ liệu cũ.

**Nghiệm thu:** lỗi tái hiện có bằng chứng assertion cụ thể; baseline cũ được ghi riêng; không dùng build xanh thay cho kiểm tra cache.

### C01 — Chặn dữ liệu phiên/lượt tải cũ quay lại

**Phạm vi:** `CampaignRepository`, `TestCheoApp.performSignOut`, auth lifecycle ở Activity, publication Home/Profile/detail; thêm helper cache scope nếu cần.

- Mỗi lượt đọc ghi giữ `uid`/guest scope và session generation ngay khi bắt đầu. Cache cá nhân và feed/stats chứa dữ liệu cá nhân phải mang scope này.
- Đổi phiên, đăng xuất và clear toàn bộ phải tăng generation trước khi cho phép writer cũ commit. Một request bắt đầu trước clear không được ghi lại cache sau clear.
- Invalidation do mutation cần version theo nhóm/key để response trước mutation không ghi lại trạng thái cũ. Không chỉ dùng uid, vì uid có thể giữ nguyên.
- UI publication phải kiểm tra scope và request sequence, không chỉ bảo vệ chỗ ghi repository. Không đọc uid mới để gắn nhãn dữ liệu tải từ uid cũ.
- Điều phối clear khi SDK chuyển sang `NotAuthenticated`; không clear trên `Initializing`/`RefreshFailure` tạm thời. Giữ đường offline đã được chủ ý hỗ trợ.
- Dùng cache entry bất biến thay vì cập nhật nhiều volatile field riêng rẽ cho cùng một entry.

**Nghiệm thu:** A→B, A→guest→B, clear giữa request, mutation giữa request và response đảo thứ tự đều không làm dữ liệu cũ trở lại RAM/UI. Luồng report retry/idempotency tiếp tục đạt regression.

### C02 — Sửa snapshot: serialization, owner, I/O và atomicity

**Phụ thuộc:** C01 cung cấp owner/generation.

**Phạm vi:** `SessionCacheManager`, hydrate/save Home/Profile, preload Application.

- Dùng một coordinator có serialization cho load/save/clear và generation đã capture; clear vô hiệu hóa writer đang chờ. Chỉ thêm Mutex mà không chặn writer cũ sau clear là chưa đủ.
- Writer dùng staged file riêng hoặc một writer duy nhất; replace nguyên tử trong cùng thư mục. Không xóa bản tốt trước khi biết bản mới commit thành công.
- Gộp/kiểm tra metadata với snapshot; version schema và owner. File hỏng/không khớp không được đánh dấu có phiên hợp lệ.
- Tách `peekMemory` không I/O khỏi load suspend chạy IO; constructor chỉ peek và render placeholder/public data khi cần, sau đó hydrate bất đồng bộ.
- Coalesce các yêu cầu save gần nhau; chỉ giữ snapshot mới nhất đúng scope, đo size/encode time trước khi chọn debounce/quota.
- Snapshot phải có trạng thái stale và ngày VN. Dữ liệu ngày trước không được gắn nhãn “hôm nay”; quyền thao tác xác nhận từ auth/server hiện tại.

**Nghiệm thu:** save–save, preload–clear, save–clear và đổi phiên giữa save không phục hồi snapshot cũ; lỗi staged write giữ bản tốt; không file read/JSON decode trong constructor main. Test kill process/atomicity thực trên thiết bị là gate riêng.

### C03 — Đồng bộ invalidation và kết quả thành công/thất bại

**Phạm vi:** mutation và các cache phụ thuộc trong `CampaignRepository`; force propagation trong ViewModel liên quan.

- Lập nhóm invalidation nhỏ, có tên theo dữ liệu: profile/credits, campaign, membership/testers, report/checkins/stats, dashboard/feed, notifications/quests. Một mutation gọi đúng nhóm sau server commit.
- Khi xóa profile cá nhân phải xóa cả `profileCache[uid]`; khi members/checkins thay đổi phải xóa cache testers/owner stats tương ứng.
- Xóa campaign khỏi `campaignCache` và mọi list chứa nó; update phải vô hiệu list joined liên quan. Phủ cả đường owner/admin/lifecycle.
- Với approve/reject, invalidation sau commit và version của C01 bảo vệ reader xen giữa; lỗi RPC không được quảng bá trạng thái thành công.
- Không cache empty/0 do catch. Empty hợp lệ vẫn cache bình thường. Giữ bản tốt cũ kèm stale/error hoặc ném lỗi có chủ đích cho UI giữ dữ liệu đang có.
- Luôn rethrow cancellation trước xử lý lỗi mạng. Chỉ fallback RPC legacy khi lỗi chứng minh thiếu function/signature; timeout/offline/quyền không tự tạo chuỗi truy vấn fallback.
- Truyền force nhất quán xuống các thành phần; thêm `limit`/sort/role/uid cần thiết vào key hoặc lưu coverage đủ lớn rồi slice có kiểm tra.

**Nghiệm thu:** bảng mutation ở F03 có test; sau ghi đọc lại thấy đúng số dư/tester/report/campaign; offline không làm danh sách tốt biến thành rỗng; forced refresh thực sự làm mới thành phần; cancellation không ghi timestamp mới.

### C04 — Gộp request và bảo đảm lượt tải mới nhất thắng

**Phạm vi:** `deduped`, `HomeViewModel.refresh`, constructor detail/owner detail.

- Chia sẻ một Deferred/kết quả in-flight theo key + scope + version, kể cả caller force cùng tham gia một lượt tải mới đủ điều kiện. Force không dùng cache cũ, nhưng không mặc định tạo request trùng.
- Sau mutation, force mới không được tham gia request có version trước mutation. Waiter phải nhận cùng lỗi/kết quả và có thể hủy riêng mà không phá request caller khác còn dùng.
- Không để mọi waiter tự fetch ngoài khóa khi người thắng lỗi/null; lần retry sau có backoff hữu hạn theo loại lỗi, không tạo storm.
- Home giữ refresh job/request sequence hoặc coalescing controller; apply UI/snapshot theo lượt còn hiệu lực. Phân biệt force mới hơn một ordinary refresh đang chạy.
- Thêm API peek cache thuần RAM cho prewarm detail; cache miss đi thẳng RPC detail rồi mới fallback, không chạy chuỗi legacy trước RPC.
- Giải phóng flight entry khi hoàn tất bằng cơ chế không tạo hai lock khác nhau cho cùng key còn caller đang chờ.

**Nghiệm thu:** 10 caller cùng key/scope/version chỉ một fetch ở cả success, error và force; response đảo thứ tự không rollback UI; cold detail không gọi legacy trước RPC thành công; regression có kiểm đếm request ở MockEngine.

### C05 — Freshness, package/icon và giới hạn bộ nhớ

**Phụ thuộc:** C01–C04 đã đạt.

- Inject cache clock: TTL RAM dùng monotonic elapsed; ngày VN dùng clock nghiệp vụ. Test 23:59→00:00, app resume sau nhiều ngày, chỉnh giờ máy.
- Dashboard có TTL/validity đủ ngắn cho thành phần quan trọng hoặc tách refresh phần warning/notification; snapshot stale không tự trở thành bản fresh mới.
- PackageManager enumeration và load icon chuyển IO. `AppIcon` peek icon ngay, miss tải bất đồng bộ và cache miss ngắn hạn; không enumerate đồng bộ khi bấm clear/tạo campaign.
- Nhận sự kiện package add/remove/replace khi process sống, cập nhật cả package set, list app, negative entry và icon; resume có reconciliation dự phòng. Có version để Compose cập nhật icon đã `remember`.
- Phân biệt “đã load và rỗng” với “chưa load”. Giới hạn/sweep các map cache và cache icon theo ngân sách; tránh list app giữ vô hạn Drawable ngoài LRU.
- Giữ Coil 25%/50 MiB làm baseline; chỉ chỉnh sau đo peak heap, hit rate, ảnh decode và disk. Chưa đưa Room/realtime/phân trang backend vào phạm vi bắt buộc.

**Nghiệm thu:** không PackageManager/disk I/O trên đường compose/cache-clear; gỡ/cập nhật app phản ánh đúng ở list/icon; map/flight không tăng vô hạn sau nhiều ID; đổi ngày vô hiệu dữ liệu “hôm nay”.

### C06 — Đo trên thiết bị và chốt nghiệm thu

**Phạm vi:** benchmark/QA và tài liệu; backend migration chỉ lập thêm khi số đo chỉ ra cần thiết.

| Kịch bản | Số liệu/bằng chứng cần thu |
|---|---|
| Cold start, warm start, snapshot lớn | Thời gian frame đầu, thời gian dữ liệu mới, disk read/parse/write main, size snapshot |
| Home↔Profile↔detail trong và ngoài TTL | Số request theo endpoint, hit/miss, TTL, p50/p95 thời gian hiển thị |
| 10 refresh/caller đồng thời | Một request cùng key/scope/version; không rollback UI/snapshot |
| Offline, reconnect, timeout, 403, RPC thiếu | Dữ liệu cũ còn hiển thị đúng stale; không cache lỗi thành empty; fallback đúng loại |
| A→B, auth expiry, clear khi đang tải | Không dữ liệu A xuất hiện trong B, kể cả sau kill/relaunch |
| 23:59→00:00 VN và resume hôm sau | Các chỉ số “hôm nay” không dùng ngày trước |
| Cài/gỡ/update app, cuộn danh sách dài | Icon/install state đúng, main không enumerate, không tăng heap không giới hạn |
| Submit/review/topup/slot, Worker chạy nền | Credits/test count/report/list thống nhất; 41 test report hiện tại tiếp tục đạt |

- Thu ít nhất 20 lượt mỗi kịch bản hiệu năng chính trên cùng thiết bị/dataset/build và so trước/sau. Không đặt số giảm phần trăm khi chưa có baseline.
- Đo startup bằng Android benchmark/trace thích hợp; StrictMode/debug tracing cho I/O main và PackageManager; release-like build để đánh giá hiệu năng cuối.
- Sau triển khai chạy unit/Robolectric, `:app:assembleDebug`, `:app:lintDebug`; đọc diagnostics, phân biệt cảnh báo cũ/mới. Không coi test giả lập là xác minh backend đã deploy.

**Gate cuối:** tất cả lỗi P1 được đóng bằng regression, cache failure không giả dữ liệu thành công, không request storm/rollback và có bằng chứng thiết bị. Các tối ưu dung lượng/phân trang tiếp tục là đề xuất có số đo, không tự mở rộng scope.

## 6. Hướng dẫn bàn giao triển khai

> Đọc tài liệu này và git status/diff trước. Giữ nguyên mọi thay đổi chưa commit có sẵn. Thực hiện lần lượt C00→C06; mỗi gói báo file sửa, test, kết quả và gate còn mở. Ưu tiên F01–F04; không giải quyết bằng cách tăng TTL, clear toàn bộ sau mọi thao tác hoặc force mọi request. Không thay đổi reward, quyền, RPC idempotency, pending receipt hay retry policy của luồng nộp báo cáo ngoài phần cache đã nêu. Không reset/clean/commit/push/deploy. Nếu cần thay API backend hoặc thay đổi cách hiển thị offline đáng kể, lập diff/kế hoạch bổ sung cụ thể trước khi mở rộng phạm vi. Chỉ tuyên bố tối ưu hiệu năng sau khi có số đo C06.

## 7. Nguồn đối chiếu

- [Android: thiết kế ứng dụng offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first): phân biệt dữ liệu local/network và chính sách đồng bộ.
- [Android: coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices): main-safe và không nuốt cancellation.
- [Supabase Kotlin: import session](https://supabase.com/docs/reference/kotlin/auth-setsession): dùng API session thật trong harness, không gọi auth backend.
- Bằng chứng lỗi cụ thể dựa vào source working tree và probe cục bộ; tài liệu bên ngoài không thay thế bằng chứng tái hiện.
