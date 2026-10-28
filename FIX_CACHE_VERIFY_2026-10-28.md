# Sửa tiếp cache — vòng xác minh ngày 28/10/2026

## Phạm vi và baseline

Người dùng yêu cầu điều khiển Antigravity sửa tiếp và kiểm tra lặp tới khi hết lỗi trong phạm vi cache. Không chỉ lập kế hoạch rồi dừng. Không commit/push/deploy, không gọi backend production, không đọc/in secret. Giữ mọi thay đổi Firebase egress, mission và thay đổi khác hiện có; không reset/clean/revert cả file. HEAD lúc kiểm tra: `9cc2193`.

- Chạy thực toàn bộ suite với `.tmp/cache-final-2026-10-28/baseline.init.gradle`: 117 test, 115 PASS, 2 FAIL, 0 errors. Hai failure: `EgressAuditProbeTest.probe08_diskDecodeMustPreserveDHashBasis` và `FirebaseEgressOptimizationTest.regression_f10_diskDecodeMustPreserveDHashBasis`. Assertion fail tại `PrivateScreenshotDiskCache.put(...)`, chưa tới so sánh dHash. Không được suy diễn thành decode sai; điều tra lifecycle/fixture/order/clock và write thực.
- Bổ sung 9 test vào `.tmp/cache-final-2026-10-28/tests/com/ntt/crosstest/data/CacheFinalAuditTest.kt`; chạy cùng CacheRegressionTest: 28 test, 19 PASS, 9 FAIL bằng assertion, 0 errors. Tất cả chín test mới đều FAIL đúng dữ liệu mong muốn, không có lỗi setup/compiler.
- Evidence: `baseline.log`, `baseline-results/`, `audit-round1.log`, `round1-results/` cùng thư mục `.tmp/cache-final-2026-10-28/`.
- Có nhiều thay đổi chưa commit từ công việc khác. Chỉ chỉnh phần cần thiết cho lỗi và regression; không hủy các tính năng mới.

## B01 — P1: guest/null scope, manager reports và admin data còn trộn phiên

`CacheEntry.isFresh` vẫn có `if (scopeUid != null && scopeUid != currentUid) return false`, trái báo cáo cũ khẳng định equality hoàn toàn. Guest cache scope null vẫn hit ở user B. Chín probe có `guestStatsMustNotCarryIntoAuthenticatedUser` xác nhận.

`getCampaignReports`/`peekCampaignReports`/detail child reports dùng `scope = if(canManage) null else uid`. `canManage=true` không làm dữ liệu công khai: user A có quyền quản lý rồi user B không được dùng cache A. Probe `managerReportsMustBeScopedToRequestingAccount` xác nhận. `getAllProfiles` cũng unscoped và probe `adminProfilesMustNotCarryIntoAnotherAccount` FAIL.

Sửa scope public/guest/user rõ ràng và nhất quán ở hit/peek/stale fallback/child writer. Mọi dữ liệu phụ thuộc RLS/role/account (members/checkins/reports/profiles/admin/stats/feed/dashboard) cần đúng owner; không chỉ sửa ba test. Session A→B trực tiếp phải vô hiệu response A, kể cả repository dùng bởi Worker không có Activity. Guard request đầu/cuối, không restamp response cũ bằng token mới. Không dùng clear toàn bộ sau mọi mutation để chữa triệu chứng.

## B02 — P2: lỗi tải stats và fallback RPC vẫn thành thành công giả

`getCampaignOwnerStats` catch các thành phần thành rỗng rồi cache thống kê 0. Probe `ownerStatsFailureMustNotOverwriteGoodCount`: count tốt 1 bị đổi thành cache fresh 0 sau forced refresh 500. `getCampaignsStatsMap` cũng fallback mọi lỗi rồi ghép 0: probe `stats500MustNotManufactureSuccessfulZeroStats` FAIL.

`getHomeFeed` vẫn bắt mọi lỗi trả null làm caller chạy legacy; probe `homeFeed500MustNotLookLikeMissingRpc` FAIL. Dashboard/detail/reports/leaderboard có mẫu tương tự. Chỉ fallback khi xác định lỗi thiếu function/signature; 403/500/offline/timeout/serialization phải báo failure hoặc stale đúng scope/version. RPC thành công rỗng là thành công thật, không phải tự động thiếu RPC. Rà `getMySlotsInfo`, `getCampaignTesters`, quest summary và mọi catch lồng nhau. Luôn rethrow CancellationException; stale fallback không vượt qua mutation invalidation. Giữ UI dữ liệu tốt với trạng thái lỗi/stale có chủ đích.

Thêm test positive controls empty/0 hợp lệ được cache; missing-RPC thật có fallback; 403/500/timeout không storm; cancellation không publish/cache timestamp mới.

## B03 — P2: cache coverage không slice limit

`getMyCreditLogs(50)` rồi `(20)` trả 50. Probe `creditLogCoverageMustSliceRequestedLimit` FAIL. Notifications cùng mẫu. Sửa take(limit) cho đường hit/return phù hợp hoặc key limit riêng; test cả 20→50 và 50→20, concurrent limits.

## B04 — P2: invalidation slot info phụ thuộc campaign còn thiếu

Sau `updateCampaignDetails`, slotInfo.currentCampaign vẫn tên Old. Probe `campaignUpdateMustInvalidateSlotInfoDependency` FAIL (mong New). Map mutation→cache phải phủ create/update/status/delete/join/leave/review/slot/profile/admin/lifecycle. Invalidate phải sau commit và chống reader trước mutation ghi lại; không chỉ null một biến. SlotInfo chứa campaign nên domain user_slots phụ thuộc campaign change. Rà invalidation lifecycle list/single/detail và leaderboard/quest dependencies.

## B05 — P2: ngày VN/freshness còn thiếu

Snapshot `sanitizeForDate` không reset ownerStatsMap.testedTodayCount. Probe `previousDaySnapshotMustClearOwnerTodayStats` FAIL: 7 thay vì 0. Rà tất cả dữ liệu theo ngày (ownerStats, quest daily, badges/counts). dateVn/timestamp không rõ nguồn phải có chính sách stale rõ; không gán ngày mới cho dữ liệu cũ.

Cache RAM chưa có ngày VN trong validity; `SystemClock.elapsedRealtime()` giải quyết TTL, không giải quyết 23:59→00:00. Inject clock monotonic + business day, capture day trước request, reject result ngày cũ sau rollover. Dashboard 3 phút không được kéo warning/notification 1 phút thành fresh. Thêm deterministic clock tests rollover, resume ngày sau, chỉnh giờ máy. Snapshot chưa có schema/freshness/stale metadata đủ để biết dữ liệu mới hay chỉ offline.

## B06 — P1/P2: publication guard chưa phủ hydrate, early return, error và helper suspend

Home/Profile async hydrate đọc `loadSync` rồi update state không có request/generation token. Snapshot load decode nằm ngoài diskLock và có check-then-write race với clear. `loadSync` còn return data sau generation đổi dù không ghi RAM. Clear trên main phải chờ diskLock và xóa/SharedPreferences IO đồng bộ.

Các refresh có success guard nhưng catch/locked-account early branch/prewarm/leaderboard vẫn có đường publish cũ. `OwnerAppDetailViewModel.applyDetail` là suspend và tải thêm dữ liệu: guard trước khi gọi chưa bảo vệ publication sau await bên trong. Kiểm tra từng `_uiState.update` sau suspend, không chỉ thêm một if đầu hàm. Capture request owner/gen/sequence/day đầu lượt; dùng cùng token khi hydrate/save. Không để error/cancel của lượt cũ tắt isLoading/error của lượt mới. Thêm test ViewModel đảo thứ tự/deferred, đổi uid giữa await, hydrate→clear và locked branch.

Snapshot load/save/clear cần commit coordinator đúng; làm atomic replace trên Android và giữ bản tốt khi write/move lỗi. Không chỉ synchronized IO lớn rồi khóa main. Test race bằng gate ở ranh giới commit và test lỗi write. Giữ offline flow đã hỗ trợ.

## B07 — P2/P3: package cache và ngân sách map chưa thực hiện

`InstalledAppCache` chưa có receiver package add/remove/replace, resume reconcile; list/icon giữ cũ; Compose thiếu version key. TTL package còn wall clock. Implement invalidation cả set/list/icon/negative entry; tránh stale async writer repopulate sau clear; cache empty hợp lệ. Danh sách app không giữ Drawable vô hạn ngoài LRU. Map repository chưa có bounded capacity/sweep; định nghĩa cap/timed eviction, test nhiều ID và concurrency. Flight release cần chính xác cả success/error/cancel, không race hai flight cùng key. Force cùng ordinary đủ mới nên coalesce; mutation mới không join flight cũ.

## B08 — Hai test cache ảnh baseline đang đỏ

Điều tra assertion put fail trong hai test nói ở baseline. Không bỏ test, không đổi assertion theo implementation; không vô hiệu private-disk policy / expiry / session validation để test xanh. Nếu fixture không đúng hợp đồng mới hoặc file handling Windows/Robolectric khác Android, chứng minh nguyên nhân và sửa fixture/platform adapter hẹp, giữ nguyên phép kiểm tra dHash. Thêm test ranh giới gây failure để không tái diễn. Giữ mọi thay đổi egress hiện có và phối hợp thay đổi source test tối thiểu.

## Quy trình bắt buộc

1. Đọc tài liệu này và hai kế hoạch cache gốc; xác minh status, baseline/evidence.
2. Chuyển 9 probe mới thành test duy trì, giữ nguyên hành vi/assertion. Tránh trùng class: nếu copy cùng CacheFinalAuditTest vào app/src/test thì chạy Gradle không kèm audit.init.gradle. Codex có thể tiếp tục chạy probe riêng trong `.tmp`.
3. Sửa B01–B08, viết regression cho các điểm source-only B05–B07 và publication. Không chỉ làm 9 test xanh rồi tuyên bố hoàn thành tất cả.
4. Chạy toàn bộ unit/Robolectric hiện có (117 test trước khi bổ sung), assembleDebug, lintDebug, đọc diagnostics. Không test thật backend/secret.
5. Viết `BAO_CAO_CACHE_VERIFY_2026-10-28.md`: mỗi B01–B08, file/test/evidence, gate chưa làm. Thông báo khi source ổn định để Codex kiểm tra độc lập. Chưa đo thiết bị thì C06 vẫn chưa hoàn tất; không tạo số liệu hiệu năng giả.
6. Không tự commit/push/deploy; giữ nguyên thay đổi của người dùng và Firebase/mission.

Lệnh probe baseline từ PowerShell trong workspace:

```powershell
$env:JAVA_HOME='C:\Users\nguye\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$env:ANDROID_HOME='C:\Users\nguye\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME='C:\Users\nguye\.gradle'
.\gradlew.bat -I .tmp/cache-final-2026-10-28/audit.init.gradle :app:testDebugUnitTest --tests com.ntt.crosstest.data.CacheFinalAuditTest --tests com.ntt.crosstest.data.CacheRegressionTest --offline --no-daemon
```

Init script chỉ thêm Kotlin source test riêng và buộc thực thi lại test, không sửa dependency hoặc production.

## Bổ sung trong lúc xác minh — publication thật đã có probe

`CachePublicationAuditTest.ownerDetailMustRecheckAccountAfterLoadingSlots` trong `.tmp/cache-final-2026-10-28/tests/` chạy OwnerAppDetailViewModel + CampaignRepository thật, MockEngine và gate tại user_slots, đổi A→B trước khi nhả gate. Test FAIL assertion: UI vẫn publish ownerId=userA sau đổi phiên. Evidence `publication-baseline.log` (XML trong app/build đã bị lượt test tiếp theo thay thế). Sửa B06 cần bảo vệ cả applyDetail sau suspend, không chỉ guard trước gọi helper. Chạy probe này khi xác minh cuối.

Gợi ý B08 cần chứng minh: hai test ảnh lặp put cùng URL 25 lần; `File.renameTo(target)` không bảo đảm overwrite trên filesystem Windows. Kiểm tra lần put thứ hai, sửa atomic replace giữ file tốt/session/expiry; không đổi phép so dHash.


Bổ sung B02: `CacheMutationAuditTest.oldNotificationsMustNotReviveAfterReadMutationAndNetworkFailure` đã FAIL assertion. Request notifications bắt đầu trước markAllNotificationsAsRead; sau PATCH thành công, response cũ về, rồi read mới lỗi 500 vẫn trả notification unread cũ qua fallback. Evidence `mutation-baseline.log`/`mutation-baseline.xml`. Test này nằm cùng thư mục nguồn probe riêng, cần chạy cả ở gate cuối. Mutation version phải được kiểm tra tại commit và stale fallback, không chỉ tại TTL hit.

Bổ sung B02 — kiểm tra lỗi từng thành phần sau khi 9 probe đã xanh: `.tmp/cache-final-2026-10-28/tests/com/ntt/crosstest/data/CacheCompositeAuditTest.kt` có 2 probe mới đã FAIL assertion ở `composite-round1.log` và `composite-results/`: (1) chỉ feedbacks lỗi 500 làm averageRating tốt 5.0 bị cache thành null; (2) chỉ checkins lỗi 500 làm tester.checkinCount tốt 1 bị cache thành 0. Đây vẫn là B02, không phải mở rộng scope. Xóa catch tạo empty/0 ở component hoặc trả stale cùng token; không đánh dấu composite chưa đủ dữ liệu là fresh. Chạy cả CacheCompositeAuditTest ở nghiệm thu và tích hợp regression chính thức.

Sau khi đóng các failure ở CacheCompositeAuditTest, tiếp tục các gate source chưa đạt trong `CACHE_REMAINING_GATES_2026-10-28.md`. Tài liệu mới nêu cụ thể các dòng vẫn thiếu: clock/day validity, clear/load commit và main-safe IO, receiver wiring/observable Compose/package race, bounded maps/flight cleanup. Thực hiện từng gói A→B→C→D, không báo hoàn tất B05–B07 chỉ nhờ suite xanh.
