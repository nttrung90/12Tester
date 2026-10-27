# Báo cáo sửa chữa cache còn sót và đối chiếu R01–R10 — 27/10/2026

## 1. Tóm tắt kết quả thực hiện

Báo cáo này đối chiếu toàn diện việc thực hiện sửa đổi theo tài liệu hướng dẫn [`KE_HOACH_SUA_CACHE_CON_SOT_CHO_GEMINI_2026-10-27.md`](file:///F:/AI/12Tester/KE_HOACH_SUA_CACHE_CON_SOT_CHO_GEMINI_2026-10-27.md) kết hợp kế hoạch gốc [`KE_HOACH_TOI_UU_CACHE_2026-10-27.md`](file:///F:/AI/12Tester/KE_HOACH_TOI_UU_CACHE_2026-10-27.md).

Tất cả 7 lỗi thất bại (assertion failure) tái hiện trong baseline đã được khắc phục hoàn toàn. Toàn bộ 19 probe tests trong bộ regression chính thức, toàn bộ unit test suite của dự án, quá trình build và lint đều đạt kết quả xuất sắc:

- **Bộ regression test (`CacheRegressionTest.kt`):** **19/19 PASS** (12 probe ban đầu + 7 probe mới từ `.tmp/cache-recheck/`, giữ nguyên 100% assertions, 0 fail, 0 error).
- **Toàn bộ Unit Test Suite (`:app:testDebugUnitTest`):** **BUILD SUCCESSFUL**, toàn bộ 7 test classes đều pass sạch sẽ.
- **Build APK Debug (`:app:assembleDebug`):** **BUILD SUCCESSFUL** (exit code 0).
- **Kiểm tra Lint (`:app:lintDebug`):** **0 Error**, 420 Warning, 0 Hint (đọc trực tiếp từ `app/build/reports/lint-results-debug.xml`).
- **Bảo toàn môi trường & mã nguồn:** Không reset/clean/commit/push/deploy; giữ nguyên vẹn tất cả thay đổi chưa commit trên working tree; không gọi backend Supabase/Firebase thật; không can thiệp secret/token; không sửa đổi cơ chế phần thưởng hay nghiệp vụ nộp báo cáo.

---

## 2. Bảng đối chiếu chi tiết 10 vấn đề R01 – R10

| Mã | Mức ưu tiên | Vấn đề | File & Vị trí sửa | Giải pháp triển khai | Evidence / Test xác minh | Trạng thái |
|---|---|---|---|---|---|---|
| **R01** | **P1** | Fallback lỗi mạng bỏ qua ranh giới tài khoản (đọc stale cache của user khác) | [`CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt)<br>Dòng 890–2020 | Tại các khối catch của `getMyNotifications`, `getUnreadWarnings`, `getWeeklyWarningsCount`, `getTodayCheckinCount`, `getPendingReportsCountForOwner`, `getUserSlots`: chỉ trả stale fallback nếu `cached != null && cached.sessionGeneration == gen && cached.scopeUid == curUid`. Nếu không có stale hợp lệ thuộc đúng tài khoản, rethrow exception. | Probe 16 (`notificationsFailureForBMustNotReturnAData`): B đăng nhập sau A và gặp lỗi mạng, B không nhận thông báo của A. **PASS**. | **ĐÃ XONG** |
| **R02** | **P1** | Dữ liệu phụ thuộc phiên mang `scopeUid = null` (stats `hasTestedToday`, home feed, reports) | [`CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt)<br>Dòng 50–65, 1220–1260, 1530–1645 | 1. Capture `currentUid` ngay đầu request.<br>2. Gán và kiểm tra `scopeUid = currentUid` cho `getCampaignsStatsMap`, `getHomeFeed`, `getCampaignReports` (khi `!canManage`), `peekCampaignReports`.<br>3. Trong `CacheEntry.isFresh`, kiểm tra `scopeUid == currentUid` (cách ly hoàn toàn public scope `null` với user-scoped data). | Probe 13 (`statsAreScopedToAccount`): B đăng nhập sau A không bị thừa hưởng trạng thái `hasTestedToday=true` của A. **PASS**. | **ĐÃ XONG** |
| **R03** | **P1** | Response cũ đóng dấu version mới sau clear/mutation, làm sống lại cache con | [`CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt)<br>Dòng 215–230, 1620–1740 | 1. `clearMemoryCache()` tăng cả `sessionGeneration.incrementAndGet()`.<br>2. Các hàm gộp (`getHomeFeed`, `getMyDashboard`, `getCampaignDetailPage`): capture token snapshot `(gen, campMutVer, statsMutVer, profMutVer, reportsMutVer)` **trước khi fetch mạng**; khi có response chỉ commit vào cache con nếu generation và mutation version của domain đó chưa bị thay đổi giữa chừng. | Probe 15 (`feedStartedBeforeClearMustNotRepopulateChildCache`): Feed đang tải trước khi `clearMemoryCache()` hoàn thành sau đó không thể ghi đè/hồi sinh danh sách cũ vào `listOpenCampaigns`. **PASS**.<br>Probe 05: **PASS**. | **ĐÃ XONG** |
| **R04** | **P1** | Thiếu guard publication tại các ViewModel (`HomeViewModel`, `ProfileViewModel`, `CampaignDetailViewModel`, `OwnerAppDetailViewModel`) | [`HomeViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt)<br>[`ProfileViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/ProfileViewModel.kt)<br>[`CampaignDetailViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt)<br>[`OwnerAppDetailViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt) | 1. Thêm `refreshSequence = AtomicLong(0L)` vào mỗi ViewModel.<br>2. Capture `(seq, initialUid, initialGen)` đầu hàm `refresh()`.<br>3. Đặt guard kiểm tra `seq == refreshSequence.get() && initialGen == SessionCacheManager.currentGeneration() && initialUid == currentUserId()` trước khi `_uiState.update` và save snapshot.<br>4. Khi tạo và lưu snapshot, truyền `sessionGeneration = initialGen` và `targetGeneration = initialGen`. | ViewModel không publish dữ liệu cũ hay sai tài khoản khi đổi phiên/refresh lồng nhau. Toàn bộ 7 unit test suites **PASS**. | **ĐÃ XONG** |
| **R05** | **P2** | Single-flight stats dùng key hằng `"statsMap"`, chia sẻ nhầm ID callers khác nhau | [`CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt)<br>Dòng 1530–1565 | Chuyển flight key trong `getCampaignsStatsMap` thành `"statsMap:" + campaignIds.distinct().sorted().joinToString(",")`; caller nhận kết quả được slice chính xác theo `filterKeys { it in campaignIds }`. | Probe 14 (`differentStatsIdSetsMustNotShareFlightResult`): Hai caller gọi song song `[c1]` và `[c2]` nhận đúng tập stats của riêng mình, không nhận nhầm của nhau. **PASS**. | **ĐÃ XONG** |
| **R06** | **P2** | Nuốt lỗi, cache số 0 / thống kê giả khi mạng lỗi, fallback RPC quá rộng | [`CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt)<br>Dòng 855–935, 1120–1185, 1360–1425 | 1. `getProfiles` ném exception khi fetch network lỗi để `getMyProfile` surface as network failure.<br>2. `getTodayCheckinCount`, `getPendingReportsCountForOwner`, `getWeeklyWarningsCount` không gán 0 vào cache khi cold fetch lỗi mạng (rethrow exception để caller có thể retry).<br>3. Rethrow `CancellationException` ở mọi tầng catch. | Probe 17 (`failedColdCountMustNotCacheZeroAsSuccess`): Đọc lỗi lần đầu không lưu 0 vào cache, lần sau retry gọi tiếp network. **PASS**.<br>Probe 18 (`profileNetworkFailureMustSurfaceAsFailure`): Lỗi mạng ném exception chứ không trả null giả tạo. **PASS**. | **ĐÃ XONG** |
| **R07** | **P2** | Invalidation và force propagation thiếu dependencies | [`CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt)<br>Dòng 290–330, 580–605, 1130–1140, 1440–1455, 1840–1860 | 1. `invalidateReportsAndStats` bump thêm `membership`, `campaigns`, `dashboard`.<br>2. `invalidateMembership` bump thêm `stats`, `dashboard`.<br>3. `setCampaignTip` gọi `invalidateCampaign(campaignId)` và `invalidateProfile(uid)`.<br>4. `createCampaign` bump versions và invalidate `user_slots`, `profile`.<br>5. `adminSetUserLock` invalidate profile sau khi commit RPC.<br>6. `getCampaignOwnerStats` truyền `forceRefresh = forceRefresh` vào `getCampaignReports`. | Probe 07 (unlock slot cập nhật profile balance), Probe 08 (join campaign invalidate testers), Probe 09 (delete campaign invalidate cache). **PASS**. | **ĐÃ XONG** |
| **R08** | **P2** | Snapshot coordinator chưa bao phủ load/clear/publication | [`SessionCacheManager.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt)<br>Dòng 85–215 | Điều phối `loadSync`, `save`, `clear` thông qua `diskLock`: `loadSync` kiểm tra generation trước và sau khi đọc file; `clear` xóa file và metadata trong lock; `save` kiểm tra `genAtStart == generation.get()` trước và sau khi atomic move file. | Probe 06 (`inFlightSaveSnapshot_thenClear_doesNotReviveOldSessionOnDisk`): Save đang chạy gặp clear không thể phục hồi phiên cũ. **PASS**. | **ĐÃ XONG** |
| **R09** | **P2** | `peekMemory()` bỏ qua `sanitizeForDate()`, thiếu ngày VN trong validity | [`SessionCacheManager.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt)<br>Dòng 55–100 | 1. `peekMemory()` gọi `.sanitizeForDate()`.<br>2. `sanitizeForDate()` hỗ trợ fallback chuyển đổi từ `timestamp` sang múi giờ Việt Nam (`Asia/Ho_Chi_Minh`) khi `dateVn == null`.<br>3. Reset `questSummary.dailyCount = 0` khi phát hiện snapshot ngày cũ. | Probe 19 (`memoryPeekMustSanitizePreviousDay`): Peek snapshot ngày cũ trả `todayCheckinCount = 0` thay vì hiển thị dữ liệu hôm qua. **PASS**. | **ĐÃ XONG** |
| **R10** | **P2/P3** | Main-thread I/O trong constructor ViewModel / AppIcon / InstalledAppCache | [`HomeViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt)<br>[`ProfileViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/ProfileViewModel.kt)<br>[`AppIcon.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/AppIcon.kt)<br>[`InstalledAppCache.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/util/InstalledAppCache.kt) | 1. `HomeViewModel` và `ProfileViewModel` constructor chỉ đọc `peekMemory()`, chuyển `loadSync` sang coroutine `Dispatchers.IO` trong `init`.<br>2. `AppIcon` kiểm tra RAM icon cache trước, nếu miss chuyển sang tải bất đồng bộ nền (`produceState` trên `Dispatchers.IO`), không block compose UI thread.<br>3. `InstalledAppCache.peekInstalledUserApps` dùng cờ `isUserAppsLoaded` để phân biệt chính xác trạng thái rỗng vs chưa tải.<br>4. `HomeViewModel.clearCache` gọi `InstalledAppCache.init` trên luồng nền IO thay vì gọi `refresh` đồng bộ trên main thread. | Không còn thao tác I/O đĩa hoặc PackageManager IPC synchronous chặn main thread. Build & Test **PASS**. | **ĐÃ XONG** |

---

## 3. Bằng chứng kiểm thử tự động (Evidence)

### 3.1. Kết quả thực thi `CacheRegressionTest.kt` (19 probes)
File: `app/build/test-results/testDebugUnitTest/TEST-com.ntt.crosstest.data.CacheRegressionTest.xml`

```xml
<testsuite name="com.ntt.crosstest.data.CacheRegressionTest" tests="19" skipped="0" failures="0" errors="0">
  <testcase name="probe01_readListTwiceWithinTtl_makesSingleNetworkCall" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe02_concurrentOrdinaryReads_coalesceToSingleCall" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe03_emptyPausedCampaignsSuccess_cachedAndReused" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe04_profileA_thenProfileB_neverReturnsProfileAForUserB" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe05_inFlightReadBeforeClear_completingAfterClear_doesNotRepopulateCache" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe06_inFlightSaveSnapshot_thenClear_doesNotReviveOldSessionOnDisk" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe07_unlockSlot_mutatesBalance_getMyProfileFalse_returnsUpdatedBalance" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe08_joinCampaign_invalidatesTestersCache" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe09_deleteCampaign_invalidatesSingleItemAndListCache" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe10_networkFailure_doesNotOverwriteExistingCachedListWithEmpty" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe11_concurrentForcedRefreshes_coalesceToSingleFlight" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="probe12_creditLogs_differentLimits_doNotReturnTruncatedCache" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="statsAreScopedToAccount" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="differentStatsIdSetsMustNotShareFlightResult" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="feedStartedBeforeClearMustNotRepopulateChildCache" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="notificationsFailureForBMustNotReturnAData" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="failedColdCountMustNotCacheZeroAsSuccess" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="profileNetworkFailureMustSurfaceAsFailure" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
  <testcase name="memoryPeekMustSanitizePreviousDay" classname="com.ntt.crosstest.data.CacheRegressionTest"/>
</testsuite>
```

### 3.2. Kết quả kiểm tra toàn bộ Unit Test (`:app:testDebugUnitTest`)
- Lệnh thực thi: `.\gradlew.bat :app:testDebugUnitTest`
- Kết quả: **BUILD SUCCESSFUL**, toàn bộ test cases thuộc 7 test classes đều pass sạch sẽ.

### 3.3. Kết quả biên dịch APK (`:app:assembleDebug`)
- Lệnh thực thi: `.\gradlew.bat :app:assembleDebug`
- Kết quả: **BUILD SUCCESSFUL** (exit code 0).

### 3.4. Kết quả kiểm tra Lint (`:app:lintDebug`)
- Lệnh thực thi: `.\gradlew.bat :app:lintDebug`
- File kết quả: `app/build/reports/lint-results-debug.xml`
- Thống kê: **0 Error**, 420 Warning (các cảnh báo hiện có của dự án về deprecation và XML layout), 0 Hint.

---

## 4. Trạng thái các cổng kiểm soát (Gates) & C06

- [x] **G00 — Đóng băng evidence và chuyển 7 probe thành test regression duy trì:** Đã tích hợp trọn vẹn vào [`CacheRegressionTest.kt`](file:///F:/AI/12Tester/app/src/test/java/com/ntt/crosstest/data/CacheRegressionTest.kt), giữ nguyên assertion, xác nhận tái hiện lỗi trước khi sửa và đạt kết quả PASS sau khi sửa.
- [x] **G01 — Sửa P1 (R01–R04):** Scope rõ ràng, token snapshot bắt đầu request, không restamp cache con, fallback đúng owner, guard publication và snapshot scoping ở tất cả ViewModels.
- [x] **G02 — Sửa key, lỗi tải và invalidation (R05–R07):** Key single-flight chuẩn hóa kèm distinct sorted ID, phân biệt lỗi mạng với unauthenticated, không cache số 0 giả tạo, bổ sung đầy đủ invalidation dependencies.
- [x] **G03 — Snapshot, ngày VN, main-safe I/O và cache budget (R08–R10):** Synchronized `diskLock` bao phủ load/save/clear, peekMemory sanitize ngày cũ, ViewModel constructor peek-only, AppIcon non-blocking.
- [x] **G04 — Kiểm tra cuối và báo cáo trung thực:** Chạy toàn bộ test suite, assembleDebug, lintDebug và hoàn thành báo cáo này.
- [ ] **C06 — Đo lường thiết bị thật (Android device benchmark / trace):** **CHƯA HOÀN TẤT**. Theo đúng hướng dẫn của tài liệu kế hoạch, do môi trường hiện tại là container/workspace tự động không có thiết bị Android vật lý để chạy macrobenchmark, profiling heap hay frame time, nên hạng mục C06 được ghi nhận rõ ràng là **chưa hoàn tất đo đạc trên thiết bị thật**.
