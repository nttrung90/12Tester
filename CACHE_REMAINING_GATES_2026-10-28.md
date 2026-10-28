# Các gate còn mở sau khi 31 test cache đã xanh

Đối chiếu source lúc khoảng 16:12 ngày 28/10/2026. Đây là phần còn thiếu của B02/B05/B06/B07 trong FIX_CACHE_VERIFY_2026-10-28.md, không phải mở rộng sang tính năng khác. Không dừng ở 31/31 PASS. Hoàn tất từng gói nhỏ, sửa code và test trước khi sang gói tiếp theo. Giữ toàn bộ thay đổi người dùng/Firebase/mission; không commit/push/deploy/backend production.

## Gói tiếp A: đóng B02 đầy đủ

- Hai probe `CacheCompositeAuditTest` đã FAIL thật: feedback-only 500 làm averageRating 5 thành null; checkin-only 500 làm tester history 1 thành 0. Source/evidence trong `.tmp/cache-final-2026-10-28/`.
- `getCampaignOwnerStats` vẫn catch feedback thành empty; `getCampaignTesters` vẫn catch members/profiles/checkins thành empty. Rà cả legacy leaderboard, slots info, quest summary để lỗi một component không thành composite fresh giả.
- Dashboard/detail vẫn catch mọi lỗi RPC trả null; áp dụng phân loại missing-RPC như feed, và propagate CancellationException ở catch lồng nhau. Empty RPC thành công không mặc định kích hoạt fallback.
- Stale fallback ở unread warnings/weekly count/today count/pending count/user slots còn chỉ check generation+uid. Cần check cả mutation version hiện tại và ngày nghiệp vụ; không chỉ sửa notification. Không phục hồi cảnh báo đã acknowledge hoặc slots cũ từ response trước mutation khi lượt tải mới lỗi.
- Thêm positive controls: genuine PGRST202 fallback, 403/500/timeout không fallback, cancellation không cache 0/rỗng, empty/0 hợp lệ được cache.

## Gói tiếp B: B05 — ngày VN và clock phải được triển khai thật

Source repository hiện không có ngày VN trong CacheEntry/validity, không inject clock. Thêm metadata/reset ownerStats trong snapshot chỉ đóng một phần B05.

- Inject monotonic clock và business-day clock (mặc định elapsedRealtime + Asia/Ho_Chi_Minh). TTL dùng monotonic; cache chứa dữ liệu "hôm nay" cần ngày trong validity hoặc key.
- Capture ngày **đầu request**; request 23:59 hoàn tất sau 00:00 không được đóng dấu ngày mới vào response cũ hoặc publish UI/snapshot như hôm nay. Không dùng default constructor lấy ngày ở cuối fetch để che race.
- Dashboard validity phải tôn trọng TTL warning/notification 1 phút thay vì 3 phút.
- Snapshot dùng timestamp/schema/freshness có chủ đích, xử lý dateVn/timestamp thiếu/hỏng; giữ offline data dưới trạng thái stale, không dùng như current-day fresh.
- Regression phải dùng fake clock thực, chứng minh trước/sau 00:00, request đang bay qua midnight, resume ngày sau, monotonic TTL khi wall clock lùi.

## Gói tiếp C: B06 — snapshot và clear main-safe, commit đúng

Source hiện `clear` vẫn tăng gen + null RAM ngoài diskLock, rồi chờ lock để xóa/SharedPreferences.commit đồng bộ. `save`/`loadSync` kiểm tra generation rồi gán RAM ở thao tác tách biệt. Nếu clear chạy giữa check và assignment, writer có thể gán RAM cũ sau null; clear không null RAM lần nữa trong lock. Dùng diskLock quanh decode chưa giải quyết được linearization với phần clear nằm ngoài lock.

- Có điểm commit/invalidate được điều phối cho memory + generation. Phần IO/staging/encode không chặn main; clear phải vô hiệu scope cũ ngay, disk cleanup được serialize trên IO, không xóa snapshot writer mới hợp lệ.
- Không giữ khóa IO lớn rồi để UI chờ khóa đó. MainActivity/Home/Profile gọi clear phải dùng API main-safe; thay .apply bằng .commit trên main không giải quyết yêu cầu.
- Load khởi đầu trước clear không được capture generation mới sau khi đợi lock rồi dùng file cũ. Snapshot save/clear và metadata cần cùng owner/token.
- Atomic replace phải giữ file tốt khi staging/move lỗi; chỉ fallback đúng lỗi không hỗ trợ atomic move, không bắt mọi Exception để thử replace không atomic.
- Bổ sung deterministic gates cho save-clear, load-clear, clear-save-new, failed write. Guard hydrate/early-return/error/leaderboard và helper suspend sau await; không publish dữ liệu/error của lượt cũ.

## Gói tiếp D: B07 — callback package phải nối vào lifecycle, Compose và budget

InstalledAppCache đã có callbacks onPackageAdded/Removed/Replaced và AtomicLong version, nhưng tại thời điểm kiểm tra chưa thấy receiver đăng ký/caller invoke các callback. AppIcon vẫn remember/produceState chỉ key theo packageName. AtomicLong không tự khiến Compose recompose.

- Đăng ký receiver add/remove/replace trong process, resume reconciliation. Event cập nhật set/list/icon/negative cache; invalidation phải chặn writer bắt đầu trước event/clear ghi lại dữ liệu cũ (bump token thích hợp, không chỉ version hiển thị).
- Expose version dạng observable (StateFlow/Compose state) và collect/use đúng key để icon/list đang hiển thị cập nhật ngay. Không trông chờ một recomposition không liên quan.
- `getInstalledUserApps` và `getInstalledAppDrawable` cũng phải guard commit theo token, không chỉ refresh/refreshPackages. clear/event trong lúc PackageManager load không được repopulate stale entry.
- Giới hạn map repository bằng capacity/TTL sweep rõ ràng. Hết TTL mà map giữ mọi ID mãi không phải eviction. List installed app không giữ Drawable ngoài budget vô hạn. Giữ empty-list loaded state.
- Flight Deferred hiện tạo async trước khi computeIfAbsent hoàn tất và remove trong finally; nếu fetch hoàn tất cực nhanh có thể remove trước khi entry được chèn. Dùng deferred lazy/đăng ký cleanup sau insertion, remove đúng identity và test success/error/cancel, không tạo hai flight cùng key.
- Thêm test event install/remove/replace -> state/icon invalidation, writer đang bay qua event/clear, bounded map/flight cleanup. Không cần đổi backend hoặc Coil budget khi chưa có phép đo.

## Gate cuối

1. Sửa và kiểm tra từng gói A→B→C→D. Báo đúng gate còn mở, không tuyên bố B05/B06/B07 xong chỉ nhờ build xanh.
2. Chuyển 14 probe audit hiện có thành regression duy trì, giữ assertions. Bộ probe độc lập Codex lưu trong `.tmp/cache-final-2026-10-28/independent-tests/`, class prefixed Independent để tránh trùng nguồn chính.
3. Chạy toàn bộ suite + build/lint. Chỉ rerun test task khi cần; không --rerun-tasks toàn project mỗi probe và không khởi chạy hai Gradle đồng thời trên cùng outputs Windows.
4. Viết BAO_CAO_CACHE_VERIFY_2026-10-28.md đối chiếu B01–B08 và các gate trên, cung cấp XML/log. Codex sẽ tự chạy probe độc lập khi source ổn định và không còn Gradle nền.
5. Không có Android device/emulator kết nối (`adb devices` đã kiểm tra). C06 chưa thể đo; ghi rõ, không tạo số liệu hiệu năng hoặc khẳng định đã kiểm chứng thiết bị.

## B06 — probe bổ sung về dữ liệu đã hiển thị

`CachePublicationAuditTest.switchingAccountWithRefreshFailureMustRemoveAlreadyDisplayedPrivateState` (cũng có bản Independent) đã chuẩn bị để kiểm tra Home hiện profile/notification A, đổi sang B, B refresh lỗi 500: dữ liệu riêng tư A đã có sẵn phải được purge khỏi UI, không chỉ chặn response mới của A. Source hiện có các fallback `myRank ?: it.myRank`, `userCredits ?: it.userCredits` và error giữ state, nên phải kiểm tra boundary của state đang hiển thị khi đổi owner. Test mới chưa có kết quả assertion baseline: lượt chạy bị chặn ở compileDebugKotlin do AppIcon.collectAsState đang chỉnh sửa; không tính lỗi setup/compile là bằng chứng fail của probe. Chạy probe này sau khi source ổn định và thêm regression nếu xác nhận lỗi.

## B06 — kiểm tra clear bất đồng bộ mới

Source mới chuyển clear disk sang launch IO khi ở main, nhưng loadSync có thể đọc lại file cũ trước khi queued cleanup chạy; clearDisk chưa có tombstone chống đọc và có thể xóa snapshot save mới cùng generation. Probe `CacheSnapshotClearAuditTest.clearMustImmediatelyPreventReloadEvenWhenDiskCleanupIsQueued` đã chuẩn bị (bản Independent cũng có): giữ diskLock reentrant trên main để cleanup IO đang chờ, clear rồi loadSync trong cùng critical section phải trả null. Không dùng sleep. Probe chưa có runtime baseline vì source còn chỉnh sửa/compile; chạy khi ổn định, port gate nếu đổi coordinator nội bộ, giữ nguyên hành vi clear→read không hồi sinh snapshot. clear hiện cũng synchronized(diskLock) trước launch IO nên vẫn có thể chặn main khi save/load đang giữ khóa lớn.

## B05 — probe clock độc lập đúng cùng repository

Đã chuẩn bị `CacheClockAuditTest` (và Independent) gồm 5 test: feed/dashboard/detail phải làm mới daily data khi fake business day đổi; TTL list phải dùng injected monotonic clock cả lúc đọc; request qua midnight phải bị loại ở lần đọc kế tiếp trên CÙNG repository. Chưa chạy baseline khi source còn sửa. Lưu ý test `inFlightRequestAcrossMidnightStampsStartDay` mới trong CacheRegressionTest tạo `repo2` để đọc ngày sau — repo mới luôn cache miss nên chưa chứng minh cache repo đầu đúng. Giữ hoặc chuyển assertion sang cùng instance như probe độc lập; đếm riêng endpoint checkins vì getTodayCheckinCount còn query tester_reports.

## B06 — không dùng số epoch RAM như định danh bền vững qua process

`loadSync` mới thêm `data.sessionGeneration != initialGen` để chặn hồi sinh sau clear. Cần sửa tiếp: AtomicLong generation bắt đầu lại 1 ở process mới, trong khi snapshot hợp lệ có thể lưu generation 3/5 sau sign-out/clear/login trước đó. Strict equality với số từ JSON sẽ làm mất hydrate/offline ở cold start hợp lệ. Tách epoch runtime với schema/owner/persistent commit metadata; rebase snapshot hợp lệ trong process mới và dùng tombstone/coordinator cho clear trong cùng process. Thêm test cold-start/recreate coordinator với snapshot từ epoch trước; không chữa bằng bỏ mọi guard clear hoặc tăng generation ngẫu nhiên. Đây là regression phát sinh bởi cách sửa B06, cần đóng trước kết luận.

B05 baseline độc lập: đã chạy 5 CacheClockAuditTest, 4 FAIL thực ở feed/dashboard/detail giữ daily data hôm qua và TTL list đọc không dùng injected monotonic clock; test qua midnight trên cùng repository PASS. Evidence `clock-first-results/` trong thư mục audit. Đã thêm `ownerDetailMustNotPublishYesterdayCountAfterSlotsAwaitCrossesMidnight` vào Publication/Independent để kiểm tra UI guard ngày sau helper suspend (chưa chạy baseline của test mới này). Không chỉ bảo vệ ngày ở cache rồi vẫn publish response hôm qua vào UI mới; giữ token ngày ở ViewModel và snapshot.

B06 UI owner phải tách khỏi `userProfile?.id` (profile nullable trong model/RPC). Probe `optionalProfileMustNotDisableOwnerPurgeForNotificationsAndFeedFlags` đã bổ sung vào Publication/Independent: dashboard A profile=null nhưng notifications A + feed hasTestedToday=true hợp lệ; sau A→B và B lỗi, phải purge notifications và cờ riêng A. Chưa chạy baseline probe mới. Rà toàn bộ state riêng (rank, owner/joined stats, missedStreak, reward, daily flags), không chỉ profile/credits; giữ public data có chủ đích. Track owner của state đã render từ request/snapshot token, không suy ra owner từ DTO profile tùy chọn.

Đã chuẩn bị `validSnapshotMustSurviveRuntimeEpochResetAndStillRespectNextClear` trong SnapshotClear/Independent: lưu snapshot hợp lệ khi epoch runtime >1, reset RAM+runtime epoch như process mới, phải load lại đúng owner; clear tiếp trong runtime mới vẫn phải tombstone file đang chờ xóa. Probe dùng reflection đọc diskLock/reset RAM của singleton hiện tại để mô phỏng boot; nếu coordinator mới có boot identity/state khác, port fixture sang fresh coordinator đúng nghĩa, giữ cả hai invariants hydrate hợp lệ và không hồi sinh sau clear. Chưa có baseline runtime cho test cold mới.

B07 source review: evictOldEntries hiện được gọi cho ownerStatsCache/testersCache/statsCache, nhưng profileCache/campaignCache/reportsCache/membersCache/checkinsCache/feedbacksCache/detailCache/myRankCache vẫn chưa cùng budget. Đã chuẩn bị CacheBudgetAuditTest/Independent: public API getProfiles 500 ID phải trả đủ dữ liệu nhưng không giữ cả 500 entry vượt budget 200 đã chọn; lần đọc lại cần fetch phần đã evict. Chưa có runtime baseline probe mới. Áp dụng policy chung cho mọi map theo contract, không chỉ thêm một call cho map đang bị test. mutationVersions chỉ có các domain cố định và flightMap có lifecycle riêng thì không cần ép cùng eviction tùy tiện.

B05: source chỉ Owner detail hiện capture initialDay; Home/Tester detail vẫn có thể publish response cũ sau midnight dù cache repository đã stamp đúng ngày đầu request. Đã chuẩn bị `homeMustNotPublishPreviousDayCountersFromInFlightRefresh` (Publication/Independent): gate feed trước midnight, dashboard đã trả count 9, đổi fake day rồi nhả feed; UI hôm nay không được hiển thị 9/cờ đã-test của hôm qua. Fixture dùng 2 tín hiệu request-start, không sleep. Chưa chạy baseline case mới. Áp dụng day token ở UI/snapshot (Home và các detail), không chỉ thêm businessDate vào cache entry.

C02/B06 còn hợp đồng metadata/file cần kiểm tra: `.tmp/.../CacheSnapshotIntegrityAuditTest.kt` (và Independent) bổ sung hai fixture lỗi đã nêu trong kế hoạch gốc: JSON hỏng phải làm hasCachedSession=false, và file owner B không được validate với metadata owner A. Probe mới chưa có runtime baseline; không gọi auth/backend thật. Source load catch hiện chỉ return null, metadata prefs vẫn true, và không đối chiếu KEY_LAST_USER_ID với data.userId. Port fixture nếu đổi schema/path, giữ invariant file hỏng/mismatch không thể được đánh dấu phiên cache hợp lệ.

B06 main-safe đã có probe liveness chuẩn bị: `mainThreadClearMustNotWaitForPendingDiskIoLock` trong SnapshotClear/Independent. Một thread giữ diskLock và gate xác nhận đã giữ; main gọi clear; watchdog chỉ nhả lock khi clear đã return hoặc timeout 3s để tránh deadlock. Không dùng sleep để đoán race và không coi đây là benchmark fps. Clear phải vô hiệu memory ngay và return dù writer/disk IO đang chờ. Source hiện vẫn synchronized(diskLock) trước khi launch IO nên sẽ chờ IO; tách lock commit-memory ngắn khỏi IO/staging, bảo vệ epoch+memory+tombstone cùng điểm commit. Đồng thời clearDisk phải không xóa snapshot mới đã save hợp lệ sau clear cùng generation (kiểm tra token/sequence/tombstone đã commit, không chỉ clearGen==generation). Probe mới chưa chạy baseline.
