# BÁO CÁO SỬA CHỨC NĂNG CHU KỲ THỐNG KÊ TRANG CHỦ SAU KIỂM TRA ĐỘC LẬP
**Ngày thực hiện:** 2026-10-28  
**Trạng thái:** HOÀN TẤT VÀ NGHIỆM THU ĐẠT 100%  
**Môi trường:** Local workspace `F:\AI\12Tester`, Node.js v24.15.0, JBR 21, Android SDK 34 / 36, PGlite.

---

## 1. TỔNG QUAN KẾT QUẢ NGHIỆM THU

Toàn bộ các gói yêu cầu từ **R00 đến R06** trong tài liệu `KE_HOACH_SUA_CHU_KY_THONG_KE_TRANG_CHU_SAU_KIEM_TRA_2026-10-28.md` đã được triển khai, kiểm thử thực tế và nghiệm thu thành công.

| Hạng mục kiểm thử | Số ca | Kết quả | Trạng thái |
| :--- | :---: | :---: | :--- |
| **Android Independent Review Probes** (`HomeStatsIndependentReviewTest.kt`) | 7 | **7/7 PASS** | **100% XANH** |
| **SQL Independent Review Probes** (`sql-independent-review.mjs`) | 8 | **8/8 PASS** | **100% XANH** |
| **SQL Migration 047 Tests** (`run-home-stats-cycle-test.mjs`) | 14 | **14/14 PASS** | **100% XANH** |
| **Project Unit Tests** (`:app:testDebugUnitTest`) | 184 | **184/184 PASS** | **100% XANH** |
| **Android Build** (`:app:assembleDebug`) | - | **BUILD SUCCESSFUL** | **100% SẴN SÀNG** |

---

## 2. ĐỐI CHIẾU CHI TIẾT CÁC GÓI SỬA ĐỔI (R00 - R06)

### R00: Build Blocker & Tính năng Xáo trộn Trang chủ
- **Vấn đề trước sửa:** `DailyHomeOrder.kt:45` gặp lỗi kiểu nullable khi truy cập `ownerId`, làm chặn toàn bộ chu trình compile/test của Android.
- **Giải pháp:** Áp dụng safe call `campaign.ownerId?.trim()?.lowercase().orEmpty()` theo đúng hợp đồng nullable của model `Campaign`, bảo toàn 100% logic xáo trộn hàng ngày theo ngày Việt Nam và thứ tự ưu tiên chỉ tiêu/Boost/tip.
- **Kiểm thử:**
  - `DailyHomeOrderTest`: PASS 100%.
  - `compileDebugKotlin` & `assembleDebug`: PASS.

### R01: Phân biệt Dữ liệu Legacy, Số cộng dồn Hợp lệ & Dữ liệu Hết hạn / Chưa biết
- **Vấn đề trước sửa:** Khi snapshot ngày hôm qua được làm sạch hoặc chưa có dữ liệu, `homeTestedCount = null` rơi về `todayTestedCount = 0`, làm hiển thị số 0 giả (`0/12 hôm nay`) ở giữa chu kỳ nhiều ngày. Danh sách app đã tham gia cũng có fallback về 0.
- **Giải pháp:**
  - `CampaignDisplayItem.displayTestedCount: Int?`: Sử dụng getter phân cấp rõ ràng:
    + Khi `homeTestedCount != null`: trả về `homeTestedCount` (kể cả số 0 thật từ server).
    + Khi `statsDate != null`: nếu đúng ngày hôm nay (`vnToday()`) trả về `todayTestedCount`, ngược lại trả về `null`.
    + Khi `todayTestedCount > 0`: trả về `todayTestedCount` (tương thích ngược với server legacy).
    + Mặc định: trả về `null` (unknown/chờ tải).
  - `CampaignStats.effectiveHomeTestedCount: Int?`: Cùng logic phân cấp, giữ an toàn cho danh sách "App đã tham gia" trên `HomeScreen.kt`.
  - `CampaignCard.kt`: Tham số `displayTestedCount: Int? = null`. Hiển thị placeholder `--` khi `null` (`val countStr = displayTestedCount?.toString() ?: "--"`).
  - Cập nhật format chuỗi `campaign_stats_line_format`: `%1$s/%2$d ...` trên cả `values/strings.xml` và `values-vi/strings.xml`.
  - Sửa assertion trong `HomeStatsCycleTest`: Kiểm tra `assertNull(sanitizedItem.displayTestedCount)` thay vì assert 0 giả.
- **Kiểm thử:**
  - Probe `expiredCycleCountMustRemainUnknownRatherThanRenderZero`: **PASS**.
  - `HomeStatsCycleTest`: **PASS**.

### R02: Dọn trạng thái Chuyển ngày Đúng phạm vi & Xử lý Đồng nhất
- **Vấn đề trước sửa:**
  + Khi refresh sau nửa đêm gặp lỗi 500/offline, cờ `hasTestedToday` của ngày hôm qua không bị dọn.
  + `joinedStatsMap` không được sanitize đồng nhất với danh sách chính khi feed cũ.
  + Lỗi feed cũ bị gộp vào `isDayRollover` làm xóa mất `todayCheckinCount` của dashboard ngày mới.
- **Giải pháp:**
  - Trong `HomeViewModel.kt`: Tách biệt `hasStaleStatsDate` khỏi `isDayRollover`. Chỉ reset `todayCheckinCount`, `dailyTask`, `questSummary` khi `repository.businessDay() != initialDay`.
  - Khi `hasStaleStatsDate`: Sanitize đồng bộ cả `displayList`, `pausedList`, `completedList` và `joinedStatsMap`. Các campaign stat có `statsDate != currentBusinessDay` được chuyển về `homeTestedCount = null, statsDate = null, hasTestedToday = false, todayTestedCount = 0`.
  - Trong khối catch của `refresh()`: Sanitize toàn bộ `displayItems`, `pausedDisplayItems`, `completedDisplayItems` và `joinedStatsMap` nếu phát hiện ngày nghiệp vụ đã đổi, bất kể RPC get_home_feed thất bại.
- **Kiểm thử:**
  - Probe `nextDayRefreshFailureMustClearPreviousDayTestFlag`: **PASS**.
  - Probe `staleFeedMustNotPublishUnsanitizedJoinedStats`: **PASS**.
  - Probe `staleFeedMustNotEraseFreshDailyDashboardCount`: **PASS**.

### R03: Validate stats_date & Refetch có Kiểm soát
- **Vấn đề trước sửa:** Repo lưu cache RAM cho feed và stats bất kể `statsDate` trả về từ server là hôm nay hay hôm qua; khi loại bỏ stale data không có cơ chế refetch lại.
- **Giải pháp:**
  - Trong `CampaignRepository.kt`: Kiểm tra `hasStaleStats = result.stats.values.any { it.statsDate != null && it.statsDate != requestDate.toString() }`. Nếu stale thì không ghi đè vào `cachedHomeFeed` và không lưu vào `statsCache`. Trong `getCampaignsStatsMap()`, chỉ cache các entry hợp lệ của ngày hôm nay.
  - Trong `HomeViewModel.kt`: Khi phát hiện `hasStaleStatsDate`, ViewModel tự động lên lịch refetch với số lần thử có kiểm soát (`staleFeedRetryCount.incrementAndGet() <= 2`), không vòng lặp vô hạn.
- **Kiểm thử:**
  - Probe `repositoryMustNotCacheOldServerDateAsFreshToday`: **PASS**.
  - Probe `staleFeedMustScheduleAControlledRefetch`: **PASS**.

### R04: Bảo vệ Vòng đời Cấu hình Admin
- **Vấn đề trước sửa:** `AdminUsersViewModel` không kiểm tra UID, generation hoặc sequence khi tải hoặc lưu cấu hình chu kỳ; phản hồi từ RPC của tài khoản Admin A có thể publish vào phiên của Admin B sau khi đổi tài khoản.
- **Giải pháp:**
  - Thêm `homeStatsSeq = AtomicLong(0L)` trong `AdminUsersViewModel`.
  - Trong `loadHomeStatsConfig()` và `saveHomeStatsCycle()`: Lưu `reqUid = authRepository.currentUserId()`, `seq = homeStatsSeq.incrementAndGet()`, `initialGen = SessionCacheManager.currentGeneration()`.
  - Kiểm tra trước khi cập nhật state: nếu UID đổi, generation đổi hoặc sequence không khớp, hủy cập nhật và đặt `isHomeStatsLoading = false, homeStatsConfig = null`.
  - Trong `refresh()`: Phát hiện đổi tài khoản admin thì reset ngay `currentAdminId`, `homeStatsConfig = null`.
- **Kiểm thử:**
  - Probe `adminConfigResponseMustNotPublishAfterAccountSwitch`: **PASS**.

### R05 & R06: Backend SQL (Migration 047)
- **Vấn đề trước sửa:**
  + `_get_home_stats_revision()` phụ thuộc timezone session PostgreSQL, gây sai lệch revision giữa các kết nối.
  + Chưa bắt buộc `p_expected_revision` khi ghi cấu hình (cho phép bỏ qua optimistic lock).
  + Chính sách đã active có thể bị sửa hồi tố qua UPDATE.
  + Ranh giới nửa đêm có thể bị race condition nếu đọc ngày trước khi lấy lock.
- **Giải pháp:** Trong `supabase/migrations/047_home_stats_cycle.sql`:
  - `_get_home_stats_revision()` sử dụng `extract(epoch from updated_at)::bigint::text` độc lập hoàn toàn với timezone của session.
  - `admin_set_home_stats_cycle()` kiểm tra bắt buộc `p_expected_revision IS NOT NULL AND btrim(p_expected_revision) <> ''`.
  - Trigger `trg_home_stats_policy_immutability` chặn mọi thao tác UPDATE trên chính sách active hoặc past (`old.effective_from <= v_current_vn`).
  - Lấy ngày Việt Nam `clock_timestamp() AT TIME ZONE 'Asia/Ho_Chi_Minh'` sau khi lấy exclusive lock trên bảng cấu hình.
- **Kiểm thử:**
  - `node supabase/tests/run-home-stats-cycle-test.mjs`: **14/14 PASS**.
  - `node audit/home-stats-review-2026-10-28/sql-independent-review.mjs`: **8/8 PASS**.

---

## 3. DANH SÁCH FILE THAY ĐỔI

1. `supabase/migrations/047_home_stats_cycle.sql` (Sửa trigger immutability, revision epoch, require expected revision, timestamp sau lock).
2. `app/src/main/java/com/ntt/crosstest/data/Models.kt` (Thêm logic phân cấp `effectiveHomeTestedCount`).
3. `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` (Validate cache statsDate, chặn lưu stale payload).
4. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/DailyHomeOrder.kt` (Safe call `ownerId` nullable).
5. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt` (Tách stale stats date khỏi rollover, sanitize joinedStatsMap, refetch có kiểm soát, dọn cờ ngày cũ trong catch, displayTestedCount phân cấp).
6. `app/src/main/java/com/ntt/crosstest/ui/components/CampaignCard.kt` (Hỗ trợ `displayTestedCount: Int? = null`, in `--` khi unknown).
7. `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt` (Chuyển sang `displayTestedCount = stats?.effectiveHomeTestedCount`).
8. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt` (Bảo vệ session UID, sequence, generation cho admin home stats).
9. `app/src/main/res/values/strings.xml` & `values-vi/strings.xml` (Đổi format string `%1$s` nhận chuỗi).
10. `app/src/test/java/com/ntt/crosstest/data/HomeStatsCycleTest.kt` (Cập nhật assertion không nhận 0 giả).

---

## 4. BẰNG CHỨNG THỰC CHẠY LỆNH VÀ KẾT QUẢ

### 4.1. Chạy 7 Probes Android Độc lập
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.ntt.crosstest.data.HomeStatsIndependentReviewTest -I audit/home-stats-review-2026-10-28/review.init.gradle --rerun-tasks --console=plain
```
**Kết quả:**
```xml
<testsuite name="com.ntt.crosstest.data.HomeStatsIndependentReviewTest" tests="7" skipped="0" failures="0" errors="0">
  <testcase name="adminConfigResponseMustNotPublishAfterAccountSwitch" time="2.371"/>
  <testcase name="expiredCycleCountMustRemainUnknownRatherThanRenderZero" time="0.048"/>
  <testcase name="repositoryMustNotCacheOldServerDateAsFreshToday" time="0.048"/>
  <testcase name="staleFeedMustNotEraseFreshDailyDashboardCount" time="0.078"/>
  <testcase name="staleFeedMustScheduleAControlledRefetch" time="0.232"/>
  <testcase name="staleFeedMustNotPublishUnsanitizedJoinedStats" time="0.033"/>
  <testcase name="nextDayRefreshFailureMustClearPreviousDayTestFlag" time="0.039"/>
</testsuite>
```
-> **BUILD SUCCESSFUL: 7/7 PASS (100%)**.

### 4.2. Chạy 8 Probes SQL Độc lập
```powershell
node audit/home-stats-review-2026-10-28/sql-independent-review.mjs
```
**Kết quả:**
```
PASS actual_RPC_2_day_accumulation
PASS actual_RPC_3_day_accumulation
PASS actual_RPC_2_day_boundary_excludes_previous_cycle
PASS actual_RPC_3_day_boundary_excludes_previous_cycle
PASS non_admin_direct_access_is_denied
PASS stale_admin_cannot_overwrite_without_revision
PASS active_policy_must_be_immutable_for_operational_DML
PASS same_revision_is_independent_of_session_TimeZone
```
-> **8/8 PASS (100%)**.

### 4.3. Chạy 14 Ca Kiểm Thử Migration 047
```powershell
node supabase/tests/run-home-stats-cycle-test.mjs
```
**Kết quả:**
```
PASS 1: Áp dụng thành công baseline schema và migration 045, 046, 047
PASS 2: Mặc định chu kỳ 1 ngày, không có lịch chờ
PASS 3: Phân quyền chặn anon và non-admin gọi RPC cấu hình
PASS 4: Validate chu kỳ hợp lệ (chỉ chấp nhận 1, 2, 3)
PASS 5: Chu kỳ 1 ngày cho home_tested_count đúng bằng today_tested_count
PASS 6: Admin lên lịch đổi chu kỳ 3 ngày, ghi nhận audit đầy đủ
PASS 7: Chống ghi đè đồng thời hoạt động chính xác khi sai revision
PASS 8: Chọn lại chu kỳ đang chạy hủy lịch chờ thành công
PASS 9: Helper _get_home_stats_period tính đúng mốc chu kỳ 3 ngày và tự reset đầu chu kỳ
PASS 10: Chu kỳ 3 ngày cộng dồn 4 -> 7 -> 9, sang chu kỳ mới reset về 0 rồi lên 5
PASS 11: Không cộng trùng lượt khi cùng tester nộp nhiều lần trong một ngày
PASS 12: Reject xóa check-in làm giảm home_tested_count ngay lập tức
PASS 13: Tử số vượt mục tiêu (14/12) không bị giới hạn hay cắt cụt
PASS 14: Đổi cấu hình giữa chu kỳ: ngày hôm nay giữ nguyên, ngày mai bắt đầu chu kỳ mới từ 0
```
-> **14/14 PASS (100%)**.

### 4.4. Toàn bộ Unit Tests của Dự án
```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```
**Kết quả:**
```
BUILD SUCCESSFUL in 16s
Tổng số test: 184
Fails: 0
Errors: 0
```

### 4.5. Assemble APK Debug
```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```
**Kết quả:**
```
BUILD SUCCESSFUL in 6s
39 actionable tasks: 4 executed, 35 up-to-date
```

---

## 5. BẢO TOÀN WORKSPACE VÀ NGUYÊN TẮC AN TOÀN

- **Không commit, push, deploy, release:** Workspace duy trì ở trạng thái modified/untracked sẵn sàng cho nhà phát triển review.
- **Không áp dụng SQL lên Supabase live:** Tất cả test SQL chạy hoàn toàn cục bộ thông qua PGlite in-memory database.
- **Bảo toàn 100% công việc đang tiến hành:** Các module tối ưu hóa Firebase Egress, Media Access Gateway, Thumbnail Support và tính năng xáo trộn Trang chủ `DailyHomeOrder.kt` đều được giữ nguyên vẹn và vượt qua toàn bộ 184 unit test.

---

## 6. HƯỚNG DẪN MIGRATION VÀ ROLLBACK

### 6.1. Hướng dẫn áp dụng (Khi deploy production)
1. Chạy migration `supabase/migrations/047_home_stats_cycle.sql` trên cơ sở dữ liệu Supabase.
2. Kiểm tra quyền RPC: Đảm bảo chỉ service role và tài khoản có role `admin` mới được gọi `admin_get_home_stats_config` và `admin_set_home_stats_cycle`.
3. Build và phát hành client Android mới. Client cũ vẫn hoạt động bình thường nhờ fallback `today_tested_count` từ các RPC đã được giữ nguyên tương thích ngược.

### 6.2. Hướng dẫn Rollback
1. Nếu muốn quay về chu kỳ 1 ngày mà không hạ migration: Admin chỉ cần chọn lại `1 ngày` trong Bảng quản trị viên. Hệ thống sẽ kích hoạt chu kỳ 1 ngày từ 00:00 ngày kế tiếp (hoặc hủy lịch chờ nếu có).
2. Nếu cần rollback hoàn toàn migration 047:
   - Chạy lệnh hoàn nguyên SQL:
     ```sql
     DROP TRIGGER IF EXISTS trg_home_stats_policy_immutability ON home_stats_policies;
     DROP FUNCTION IF EXISTS trg_check_home_stats_policy_immutability();
     DROP FUNCTION IF EXISTS admin_set_home_stats_cycle(integer, text);
     DROP FUNCTION IF EXISTS admin_get_home_stats_config();
     DROP FUNCTION IF EXISTS _get_home_stats_period(date);
     DROP FUNCTION IF EXISTS _get_home_stats_revision();
     DROP TABLE IF EXISTS home_stats_policy_audits;
     DROP TABLE IF EXISTS home_stats_policies;
     ```
   - Khôi phục hàm `get_home_feed` và `get_campaigns_stats` về phiên bản không có cột `home_tested_count` và `stats_date`.
   - Client Android sẽ tự động nhận biết legacy payload và hiển thị `today_tested_count` như cũ mà không phát sinh lỗi.
