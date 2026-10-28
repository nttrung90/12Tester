# Báo cáo triển khai chu kỳ thống kê lượt test Trang chủ (1 / 2 / 3 ngày)

Ngày triển khai: 28/10/2026  
Trạng thái: **Đã triển khai mã nguồn, test SQL hành vi và test Android hoàn tất 100% trong repo local. Chưa commit, chưa push, chưa deploy, chưa áp dụng migration lên Supabase live.**

---

## 1. Tổng quan triển khai

Đã hoàn thành toàn bộ các yêu cầu trong tài liệu [`KE_HOACH_CHU_KY_THONG_KE_TRANG_CHU_2026-10-28.md`](file:///F:/AI/12Tester/KE_HOACH_CHU_KY_THONG_KE_TRANG_CHU_2026-10-28.md) cho dự án 12Tester:
- **Chu kỳ cố định (1, 2, 3 ngày):** Mặc định 1 ngày; áp dụng múi giờ Việt Nam `Asia/Ho_Chi_Minh`. Không dùng cửa sổ trượt, không xóa lịch sử check-in, tự động bắt đầu từ 0 khi bước sang chu kỳ mới.
- **Tính lượt theo ngày trong chu kỳ:** Cùng 1 tester test ở nhiều ngày khác nhau trong chu kỳ được tính đủ số lượt tương ứng (dựa trên các bản ghi `checkins` hợp lệ). Cùng 1 ngày vẫn chống trùng tuyệt đối theo ràng buộc `UNIQUE (campaign_id, user_id, checkin_date)`.
- **Tách biệt hiển thị và nghiệp vụ:** `home_tested_count` chỉ dùng hiển thị trên các thẻ app Trang chủ (kể cả tab Mở, Tạm dừng, Hoàn thành, thẻ app đã tham gia và kết quả tìm kiếm). Mọi logic nghiệp vụ (kiểm tra đủ chỉ tiêu hằng ngày, thứ tự ưu tiên sắp xếp, hộp thoại cảnh báo trước khi vào test, thanh nhiệm vụ ngày, Credit, tip, duyệt/từ chối, màn hình chi tiết app và app của tôi) tiếp tục dựa trên `today_tested_count` thật của hôm nay.
- **Giữ nguyên giao diện tester:** Tester vẫn thấy đúng chuỗi hiện hành (`%1$d/%2$d hôm nay · %3$d người đã cài`), không thêm từ ngữ "chu kỳ", không có thông báo hay banner ngoài khu vực admin. Mẫu số giữ nguyên theo `campaign.target_testers` (12/15/20); cho phép tử số cộng dồn vượt mẫu số (ví dụ `14/12`).
- **Bảo mật và phân quyền:** Bảng cấu hình nội bộ `home_stats_policies` và bảng audit `home_stats_policy_audits` được bật RLS và thu hồi quyền trực tiếp từ `anon`/`authenticated`. Chỉ tài khoản có `role = 'admin'` mới được đọc hoặc chỉnh sửa thông qua các RPC `admin_get_home_stats_config` và `admin_set_home_stats_cycle`. Có concurrency revision guard chống ghi đè đồng thời giữa các admin.
- **Cache và chuyển ngày:** Xử lý triệt để chuyển ngày qua nửa đêm, offline, cold start và request qua nửa đêm. Khi cache đã hết hạn sang ngày mới, không tạo số 0 giả cho chu kỳ nhiều ngày mà đưa về trạng thái chờ server xác nhận; tự động kích hoạt refresh đúng `00:00:01` VN khi app đang mở foreground.

---

## 2. Danh sách file thay đổi và tạo mới

### 2.1. Backend & Database Migrations
- [`supabase/migrations/047_home_stats_cycle.sql`](file:///F:/AI/12Tester/supabase/migrations/047_home_stats_cycle.sql) *(Tạo mới)*:
  - Tạo bảng `public.home_stats_policies` (`effective_from`, `cycle_days` in (1, 2, 3), audit fields).
  - Tạo bảng `public.home_stats_policy_audits` ghi nhận lịch sử thay đổi cấu hình.
  - RLS bật, revoke quyền trực tiếp từ `anon`, `authenticated`.
  - Seed chính sách nền chu kỳ 1 ngày từ mốc `2020-01-01`.
  - Helper nội bộ `public._get_home_stats_period(p_today date)` tính `period_start`, `period_end_exclusive` theo công thức chu kỳ cố định `A + floor((D - A) / N) * N`.
  - Helper nội bộ `public._get_home_stats_revision()` băm revision chống ghi đè đồng thời.
  - RPC Admin `public.admin_get_home_stats_config()` và `public.admin_set_home_stats_cycle(p_cycle_days, p_expected_revision)`.
  - Nâng cấp RPC `public.get_home_feed()` và `public.get_campaigns_stats(uuid[])` bổ sung `home_tested_count` và `stats_date` vào payload `stats`.
- [`supabase/tests/run-home-stats-cycle-test.mjs`](file:///F:/AI/12Tester/supabase/tests/run-home-stats-cycle-test.mjs) *(Tạo mới)*:
  - Bộ test tự động trên môi trường in-memory PGlite kiểm thử toàn bộ 14 ca kiểm thử hành vi SQL.

### 2.2. Android Models & Repositories
- [`app/src/main/java/com/ntt/crosstest/data/Models.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/Models.kt):
  - Bổ sung `home_tested_count: Int? = null`, `stats_date: String? = null`, và getter `effectiveHomeTestedCount` vào `CampaignStats`.
  - Thêm model `HomeStatsPolicyConfig` và `AdminSetHomeStatsCycleParams` cho RPC Admin.
- [`app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt):
  - Nhánh fallback legacy của `getCampaignsStatsMap()` cấp `homeTestedCount = todayCountMap[id]` và `statsDate = today`.
  - Bổ sung phương thức `adminGetHomeStatsConfig()` và `adminSetHomeStatsCycle(cycleDays, expectedRevision)`.
- [`app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt):
  - Trong `sanitizeForDate()`, khi snapshot thuộc về ngày cũ: đặt `todayTestedCount = 0`, `hasTestedToday = false`, và `homeTestedCount = null`, `statsDate = null` (không gán số 0 giả cho số chu kỳ).

### 2.3. Android UI & ViewModels
- [`app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt):
  - `CampaignDisplayItem`: bổ sung `homeTestedCount: Int? = null`, `statsDate: String? = null`, và `displayTestedCount: Int get() = homeTestedCount ?: todayTestedCount`. `isTargetReached` giữ nguyên kiểm tra theo `todayTestedCount >= dailyTarget`.
  - Khôi phục snapshot đĩa nạp `homeTestedCount` vào `joinedStatsMap`.
  - `toDisplayItem()` map đầy đủ `homeTestedCount` và `statsDate`.
  - Xử lý chuyển ngày: phát hiện request qua nửa đêm hoặc response có `stats_date` cũ -> vô hiệu hóa số cũ và làm mới dữ liệu.
  - Lên lịch tự động làm mới `scheduleMidnightRefresh()` đúng thời điểm `00:00:01` VN kế tiếp khi màn hình foreground.
- [`app/src/main/java/com/ntt/crosstest/ui/components/CampaignCard.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/components/CampaignCard.kt):
  - Bổ sung tham số `displayTestedCount: Int = todayTestedCount`.
  - In dòng thống kê bằng `displayTestedCount`, trong khi `isTargetReached` vẫn kiểm tra bằng `todayTestedCount`.
- [`app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt):
  - Truyền `displayTestedCount = stats?.homeTestedCount ?: stats?.todayTestedCount ?: 0` cho thẻ app đã tham gia (`joinedCampaigns`).
- [`app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt):
  - Bổ sung state: `homeStatsConfig`, `isHomeStatsLoading`, `isHomeStatsSaving`, `homeStatsSaveSignal`.
  - Bổ sung phương thức `loadHomeStatsConfig()` và `saveHomeStatsCycle(cycleDays)`.
- [`app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt`](file:///F:/AI/12Tester/app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt):
  - Thêm nút cấu hình Chu kỳ thống kê Trang chủ trên thanh TopAppBar.
  - Dialog `AdminHomeStatsCycleDialog` cho admin chọn 1, 2, 3 ngày, xem chính sách đang chạy, lịch chờ áp dụng và gửi revision chống xung đột.

### 2.4. Resources & Tests
- [`app/src/main/res/values/strings.xml`](file:///F:/AI/12Tester/app/src/main/res/values/strings.xml) & [`app/src/main/res/values-vi/strings.xml`](file:///F:/AI/12Tester/app/src/main/res/values-vi/strings.xml): Thêm chuỗi giao diện quản trị viên tiếng Việt và tiếng Anh.
- [`app/src/test/java/com/ntt/crosstest/data/HomeStatsCycleTest.kt`](file:///F:/AI/12Tester/app/src/test/java/com/ntt/crosstest/data/HomeStatsCycleTest.kt) *(Tạo mới)*: Bộ test đơn vị Android kiểm tra serialization, fallback backend cũ, tách biệt `displayTestedCount` với `isTargetReached`, và làm sạch cache ngày cũ.

---

## 3. Kết quả kiểm thử thực tế

### 3.1. Kiểm thử SQL Backend (`supabase/tests/run-home-stats-cycle-test.mjs`)
Chạy với Node.js v24 + `@electric-sql/pglite` mô phỏng đầy đủ schema PostgreSQL Singapore:
```
=== BẮT ĐẦU KIỂM THỬ MIGRATION 047: CHU KỲ THỐNG KÊ TRANG CHỦ (1 / 2 / 3 NGÀY) ===
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
=== TẤT CẢ 14 KIỂM THỬ BACKEND SQL MIGRATION 047 ĐỀU PASS 100% ===
```

### 3.2. Kiểm thử Unit Test Android (`HomeStatsCycleTest.kt`)
Lệnh chạy:
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; $env:ANDROID_HOME="C:\Users\nguye\AppData\Local\Android\Sdk"; .\gradlew testDebugUnitTest --tests com.ntt.crosstest.data.HomeStatsCycleTest
```
Kết quả:
```
BUILD SUCCESSFUL in 49s
31 actionable tasks: 5 executed, 26 up-to-date
```
Chi tiết test cases trong XML report:
- `testHomeStatsPolicyConfigDeserialization`: PASS
- `testCampaignDisplayItem_targetReachedUsesTodayTestedCount_notHomeTestedCount`: PASS
- `testAdminSetHomeStatsCycleParamsSerialization`: PASS
- `testCampaignStatsDeserialization_legacyBackendPayload`: PASS
- `testCampaignStatsDeserialization_withNewFields`: PASS
- `testSanitizeForDate_clearsOldTodayStats_andInvalidatesHomeStatsWithoutFakeZero`: PASS

### 3.3. Kiểm thử hồi quy Cache Android (`CacheRegressionTest.kt`)
Lệnh chạy:
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; $env:ANDROID_HOME="C:\Users\nguye\AppData\Local\Android\Sdk"; .\gradlew testDebugUnitTest --tests com.ntt.crosstest.data.CacheRegressionTest
```
Kết quả:
```
BUILD SUCCESSFUL in 17s
31 actionable tasks: 1 executed, 30 up-to-date
```

### 3.4. Kiểm thử biên dịch và đóng gói APK (`assembleDebug`)
Lệnh chạy:
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; $env:ANDROID_HOME="C:\Users\nguye\AppData\Local\Android\Sdk"; .\gradlew assembleDebug
```
Kết quả:
```
BUILD SUCCESSFUL in 20s
39 actionable tasks: 4 executed, 35 up-to-date
```
Toàn bộ mã nguồn biên dịch thành công, không có lỗi cú pháp hay thiếu liên kết.

---

## 4. Hạn chế còn lại & Giới hạn môi trường

1. **Chưa áp dụng lên Supabase live:** Tuân thủ chỉ thị bảo toàn an toàn hệ thống, migration `047_home_stats_cycle.sql` chưa được thực thi trên cơ sở dữ liệu Supabase sản xuất. Cần chạy migration này khi phát hành đợt backend tiếp theo.
2. **Khách hàng dùng APK phiên bản cũ:** Khách hàng chưa nâng cấp APK mới vẫn đọc trường `today_tested_count` từ response nên sẽ tiếp tục thấy số lượt test theo ngày (chu kỳ 1 ngày). Khi người dùng nâng cấp lên bản APK mới, họ sẽ tự động nhận số lượt tổng hợp theo chu kỳ 1/2/3 ngày mà admin thiết lập mà không cần can thiệp thêm.
3. **Môi trường thiết bị thật / Giả lập:** Quá trình kiểm thử đã được thực hiện bằng bộ test tự động độc lập (PGlite SQL engine cho backend và Robolectric/JUnit cho Android). Do không có thiết bị thật hoặc AVD đang chạy trong phiên làm việc, việc kiểm thử hiển thị thực tế trên màn hình vật lý chưa được thực hiện.

---

## 5. Hướng dẫn áp dụng migration và hoàn tác (Rollback)

### 5.1. Áp dụng lên môi trường Supabase
1. Đăng nhập vào trang quản trị Supabase Dashboard > **SQL Editor**.
2. Mở file [`supabase/migrations/047_home_stats_cycle.sql`](file:///F:/AI/12Tester/supabase/migrations/047_home_stats_cycle.sql).
3. Chạy toàn bộ nội dung file migration.
4. Mặc định hệ thống sẽ khởi tạo chính sách chu kỳ **1 ngày**, đảm bảo hành vi hiển thị hoàn toàn tương thích và không làm thay đổi số liệu hiện tại của hệ thống.
5. Khi muốn đổi sang 2 hoặc 3 ngày, Admin chỉ cần vào **Bảng quản trị viên** trên app và chọn chu kỳ mong muốn. Cấu hình sẽ tự động kích hoạt từ 00:00 ngày tiếp theo (Asia/Ho_Chi_Minh).

### 5.2. Hướng dẫn hoàn tác nghiệp vụ (Không cần can thiệp database)
- Nếu muốn quay về chu kỳ 1 ngày: Admin vào **Bảng quản trị viên** > Mở hộp thoại **Chu kỳ thống kê Trang chủ** > Chọn **1 ngày (Mặc định)** > Bấm **Xác nhận**.
- Hệ thống sẽ tự động lên lịch chuyển về chu kỳ 1 ngày từ 00:00 ngày hôm sau mà không cần sửa code, không xóa lịch sử test và không làm gián đoạn người dùng.

### 5.3. Hướng dẫn rollback database khẩn cấp (Nếu cần)
Nếu cần gỡ bỏ hoàn toàn migration 047 và khôi phục định nghĩa RPC cũ:
```sql
-- 1. Khôi phục lại get_home_feed và get_campaigns_stats về bản 020
-- (Tham khảo định nghĩa trong supabase/migrations/020_query_optimization.sql)

-- 2. Xóa các RPC và bảng chu kỳ mới
drop function if exists public.admin_set_home_stats_cycle(int, text);
drop function if exists public.admin_get_home_stats_config();
drop function if exists public._get_home_stats_revision();
drop function if exists public._get_home_stats_period(date);
drop table if exists public.home_stats_policy_audits;
drop table if exists public.home_stats_policies;
```
Client mới vẫn tự động fallback an toàn về `today_tested_count` nếu các trường chu kỳ mới bị thiếu trên server.
