# Báo cáo bật lại tính năng "Top Test" trong tab "Xếp hạng" (31/10/2026)

## 1. Mục đích

Bật lại sub-tab **"Top Test"** trong tab **"Xếp hạng"** của màn hình Trang chủ — bảng xếp hạng theo **tổng lượt test toàn thời gian** (trước đây tab chính là "Top Credit", xếp theo số dư Credit). Kèm theo đó, bổ sung thẻ ghim **"Hạng của bạn"** cho sub-tab Top Test (trước đây thẻ này chỉ có ở Top Credit).

## 2. Hiện trạng nghiên cứu

- Tính năng đã được xây dựng **hoàn chỉnh** từ commit `f7970ce` (20/10/2026) nhưng bị **ẩn ngay từ đầu** bằng đúng 1 cờ:
  - `HomeScreen.kt` — `private const val SHOW_LEADERBOARD_TEST_TAB = false`, kèm comment *"Tạm ẩn sub-tab 'Top Test' trong tab Xếp hạng. Đổi thành true để bật lại."*
- Toàn bộ pipeline đã nối sẵn: `LeaderboardTab` có TabRow 2 sub-tab + lazy load khi mở sub-tab + pull-to-refresh riêng + empty state riêng; `HomeViewModel.ensureTestLeaderboardLoaded()` / `refreshTestLeaderboard()`; `CampaignRepository.getLeaderboard(sort = "test")` với cache riêng (TTL như leaderboard Credit).
- **Backend đã sẵn sàng trong baseline production** (migration `062_consolidated_baseline.sql`):
  - `get_leaderboard_v2(p_limit, p_sort)` — dòng 5277, hỗ trợ `p_sort = 'test'`, GRANT EXECUTE cho anon/authenticated (dòng 13210–13213).
  - `get_my_rank_v1(p_sort)` — dòng 5650, hỗ trợ `p_sort = 'test'` (chỉ tính hạng cho người có ≥ 1 lượt test).
- Strings đủ 3 locale: `leaderboard_tab_test` (vi: "Xếp hạng kiểm thử", en/default: "Top Test"), `leaderboard_test_empty`.
- Fallback client-side đã có sẵn: nếu Supabase trả `PGRST202` (RPC chưa deploy), `CampaignRepository` tự truy vấn `profiles` + `checkins` rồi sắp xếp trên máy.

## 3. Các file đã chỉnh sửa

### 3.1. `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt`
- Đổi `SHOW_LEADERBOARD_TEST_TAB = false` → `true`, cập nhật comment thành mô tả công tắc sub-tab.
- Call site `LeaderboardTab` (khối `3 ->` trong HomeContent): truyền thêm `testMyRank = uiState.testMyRank`.
- `LeaderboardTab`: thêm param `testMyRank: MyRank?`; tính `val activeMyRank = if (isTestTab) testMyRank else myRank` và hiện `MyRankCard` khi đã đăng nhập và `rank > 0` — thẻ giờ hiện ở **cả hai** sub-tab.
- `MyRankCard` tái sử dụng nguyên vẹn (đã hiển thị lượt test, số app, credit).

### 3.2. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`
- `HomeUiState`: thêm `val testMyRank: MyRank? = null` — hạng của người dùng theo bảng Top Test.
- `refreshTestLeaderboard()`: sửa theo mẫu `refreshLeaderboard()` — dùng `coroutineScope { async }` tải song song `getLeaderboard(sort = "test")` và `getMyRank(sort = "test")`; lỗi của `getMyRank` được nuốt thành `null` không làm hỏng bảng xếp hạng.

### 3.3. `app/build.gradle.kts`
- Bump version: `versionCode = 27` → `28`, `versionName = "1.0.7"` → `"1.0.8"`.

> Không cần chỉnh sửa backend: cả hai RPC đã hỗ trợ `p_sort = 'test'` trong baseline production; cache `myRankCache` của repository đã key theo `sort`.

## 4. Xác minh RPC production (curl, dùng anon key từ local.properties)

| RPC | Body | Kết quả |
|---|---|---|
| `get_leaderboard_v2` | `{"p_limit":5,"p_sort":"test"}` | HTTP 200 — JSON array 5 người, xếp theo `checkinCount` giảm dần (72, 61, 61, 54, 45), tie-break theo credits/campaignsCount đúng thiết kế |
| `get_my_rank_v1` | `{"p_sort":"test"}` | HTTP 200 — `{}` (đúng cho anon vì `auth.uid()` null; app đăng nhập sẽ nhận hạng thật) |

→ **Không cần deploy SQL nào lên Supabase.**

## 5. Kiểm thử tự động

- `./gradlew :app:assembleDebug :app:testDebugUnitTest` — **BUILD SUCCESSFUL**.
- 51 lớp unit test, **0 failures, 0 errors, 0 skipped**.
- APK debug sinh tại `app/build/outputs/apk/debug/app-debug.apk` (versionName 1.0.8, versionCode 28).

## 6. Checklist QA thủ công (trên máy thật)

1. Đăng nhập → tab **Xếp hạng** → thấy 2 sub-tab **Xếp hạng Credit** | **Xếp hạng kiểm thử** (vi) / **Top Credit** | **Top Test** (en).
2. Mở sub-tab Top Test lần đầu → bảng tải lazy (không chặn Trang chủ), danh sách xếp theo tổng lượt test, top 3 có huy chương 🥇🥈🥉.
3. Người dùng đã có ≥ 1 lượt test → thấy thẻ **"Hạng của bạn"** đầu sub-tab Top Test với hạng đúng cả khi ngoài Top 100.
4. Người chưa từng test → không thấy thẻ "Hạng của bạn" ở sub-tab Top Test (hạng chỉ tính khi có ≥ 1 lượt test).
5. Pull-to-refresh ở sub-tab Top Test → tải lại riêng, không ảnh hưởng tab Credit.
6. Đổi ngôn ngữ en → nhãn sub-tab và empty state ("No test turns on the leaderboard yet.") hiển thị đúng.
7. Tắt mạng → mở sub-tab lần đầu (chưa có cache) → empty state, không crash; bật lại mạng → pull-to-refresh nhận dữ liệu.
8. Tab Credit: hành vi cũ giữ nguyên (thẻ "Hạng của bạn" vẫn hiện như trước).

## 7. Ghi chú triển khai

- **Tải lazy**: bảng Top Test chỉ được gọi khi người dùng mở sub-tab lần đầu (`ensureTestLeaderboardLoaded`) — ở lại tab Credit thì không tốn thêm request nào, không tăng tải Supabase.
- **Cache**: bảng Top Test có `CacheEntry` riêng, TTL bằng bảng Credit và bị vô hiệu khi có mutation hoặc đổi session generation (cơ chế chung của leaderboard).
- **Fallback client-side**: nếu RPC bị thiếu/bỏ trên production, app tự tính bảng từ `profiles` + `checkins` — tính năng vẫn hoạt động, chỉ chậm hơn và tính trên 100 người đầu theo `credits` trước khi sắp xếp lại (đúng hành vi thiết kế sẵn, không đổi trong đợt này).
- **`totalUsers`** trong thẻ "Hạng của bạn" tính trên toàn bộ profiles (kể cả người 0 lượt test) — nhất quán với semantics hiện tại của tab Credit.
- Cờ `SHOW_LEADERBOARD_TEST_TAB` giữ lại làm công tắc: nếu sau này cần ẩn nhanh, đổi về `false` là mọi nhánh UI/ViewModel tự về cấu hình cũ.
