# Báo cáo kết quả sửa lỗi nhiệm vụ ngày bị kẹt cập nhật

- **Ngày lập:** 15/11/2026
- **Mã hóa:** UTF-8
- **Workspace thực tế:** `F:\AI\12Tester`
- **Môi trường:** Windows 10/11, JDK `C:\Program Files\Android\Android Studio\jbr`
- **Model thực hiện:** Gemini 3.8 Flash High
- **Phiên bản ứng dụng:** 1.2.5 (versionCode: 45) — Giữ nguyên, không thay đổi.

---

## 1. Mục tiêu và phạm vi thực hiện

Thực hiện toàn diện việc sửa lỗi và nghiệm thu tính năng nhiệm vụ ngày bị kẹt trạng thái chờ cập nhật ("pending refresh", "pending carryover"), thiếu khả năng phục hồi khi lỗi mạng/timeout RPC, và ngăn chặn các race condition do phản hồi cũ (từ full dashboard hoặc retry) ghi đè trạng thái mới.

### Ràng buộc tuân thủ:
- Giữ nguyên toàn bộ mã ngoài phạm vi sửa đổi trong workspace `F:\AI\12Tester`.
- Không thực hiện reset/clean/checkout đè lên các thay đổi đang có.
- Không build APK/AAB (`assembleDebug`, `bundleRelease`, v.v.).
- Không push/deploy lên remote hoặc can thiệp dữ liệu production trực tiếp.
- Giữ nguyên baseline trong thư mục `audit/daily-task-stuck-2026-11-15/before/`.

---

## 2. Nguyên nhân gốc rễ đã xác minh & Khắc phục

### R1: Timeout nội bộ bị nhầm thành hủy từ bên ngoài
- **Hiện tượng:** `CampaignRepository.getMyDailyTaskStatus`, `fetchMyDailyTaskStatus` và `refreshDailyTaskAfterCampaignChange` sử dụng `withTimeout(10_000L)`. Khi chạm ngưỡng timeout 10 giây, hàm ném `TimeoutCancellationException` (lớp con của `CancellationException`). Việc bắt và rethrow `CancellationException` trực tiếp khiến coroutine gọi bên ngoài bị hủy theo (lifecycle cancelled), làm mất cơ hội chuyển sang trạng thái lỗi có thể thử lại và giữ cờ pending vô thời hạn.
- **Khắc phục:** Chuyển sang sử dụng `withTimeoutOrNull(DAILY_TASK_RPC_TIMEOUT_MS)`. Nếu hết giờ do timeout nội bộ của request, hàm xử lý như một lỗi tải mạng thông thường (`TimeoutException`), cho phép ViewModel bắt lỗi và kích hoạt retry/Thử lại; trong khi đó, nếu coroutine cha/lifecycle thực sự bị hủy từ bên ngoài thì `CancellationException` vẫn được truyền tiếp nguyên vẹn.

### R2: Không dùng trạng thái hoàn thành cũ để che cờ pending
- **Hiện tượng:** Trên UI `DailyTaskCard`, biểu thức trước đó dùng `isPending = (pendingCarryover || isPendingRefresh) && !serverMet`. Khi người dùng thực hiện thao tác (như đăng thêm app) làm tăng chỉ tiêu, dữ liệu `serverMet=true` từ snapshot cũ vô tình vượt qua guard pending, khiến giao diện hiển thị hoàn thành từ dữ liệu cũ trước khi máy chủ xác nhận chỉ tiêu mới.
- **Khắc phục:** Tách bạch và siết chặt: `isPending = pendingCarryover || isPendingRefresh`. Khi đang có yêu cầu làm mới hoặc đang chờ xác minh nợ bảo lưu, thẻ bắt buộc phải ở trạng thái pending (hoặc chuyển sang error nếu lỗi) chứ không được lấy `serverMet` cũ để hiện hoàn thành sớm. Bổ sung các badge phân biệt rõ ràng: Đang đồng bộ, Đang xác minh lịch sử, Lỗi kết nối và hiển thị nút "Thử lại".

### R3: Khắc phục trường hợp hết đợt tải giữ pending vĩnh viễn
- **Hiện tượng:**
  1. Khi nộp báo cáo (`triggerDailyTaskReloadAfterReport`), nếu API trả về null hoặc lỗi nạp, ViewModel trước đây không kích hoạt retry và không gỡ pending nếu dashboard cũng lỗi.
  2. Vòng lặp retry kiểm tra nợ bảo lưu 13 lượt (`checkAndSchedulePendingCarryoverRetry`) kết thúc trong khối `finally` mà không công bố trạng thái lỗi/chờ khi lịch sử vẫn chưa được máy chủ xác nhận.
- **Khắc phục:**
  1. `triggerDailyTaskReloadAfterReport()` khi gặp lỗi nạp sẽ tự động kích hoạt `scheduleDailyTaskRetry()` với backoff lũy tiến. Khi cạn lượt retry, công bố snapshot lỗi rõ ràng (`isDailyTaskError = true, isDailyTaskPendingRefresh = false`) để hiện nút "Thử lại".
  2. Trong `checkAndSchedulePendingCarryoverRetry()`, nếu cạn 13 lượt polling mà `isCarryoverHistoryPending` vẫn là `true`, khối `finally` sẽ cập nhật `isDailyTaskError = true` để mở nút "Thử lại" thủ công cho người dùng.
  3. `retryDailyTask()` kích hoạt lượt thử lại ngay lập tức (`delay = 0L`, `isUserInitiated = true`).

### R4: Đảm bảo thứ tự snapshot và chặn Dashboard cũ ghi đè
- **Hiện tượng:** Full dashboard (`get_my_dashboard`) bắt đầu sau report mutation nhưng hoàn thành muộn hơn direct task request có thể mang snapshot cũ ghi đè lên kết quả mới đã đạt của nhiệm vụ ngày.
- **Khắc phục:**
  1. `publishDailyTaskSnapshot()` gọi `SessionCacheManager.nextDailyTaskVersion()` để tăng phiên bản daily task toàn cục khi có snapshot mới được công bố.
  2. `HomeViewModel.refresh()` chụp lại cả `initialDailyTaskVersion` lẫn `initialDailyTaskSeq`. Khi dashboard trả về, nếu phát hiện phiên bản hoặc sequence đã thay đổi (`isDailyTaskStale = true`), nó sẽ bảo lưu `_uiState.value.dailyTask` mới hơn thay vì ghi đè bằng snapshot cũ của dashboard.
  3. `forCurrentUi(expectedDate)` kiểm tra nghiêm ngặt `statusDate` khớp ngày giờ Việt Nam hiện tại; nếu null hoặc lệch ngày thì bị từ chối, không cho phép gỡ pending giả tạo.

---

## 3. Danh sách các tập tin đã can thiệp

1. `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`:
   - Định nghĩa `DAILY_TASK_RPC_TIMEOUT_MS = 10_000L`.
   - Chuyển `getMyDailyTaskStatus`, `fetchMyDailyTaskStatus` và `refreshDailyTaskAfterCampaignChange` sang `withTimeoutOrNull`, phân biệt timeout nội bộ với cancellation từ ngoài.
   - Thêm `bumpMutationVersion("daily_task")` vào `invalidateReportsAndStats` và hủy cache dashboard.
2. `app/src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt`:
   - Cập nhật chữ ký nhận `isError: Boolean` và `onRetryClick: (() -> Unit)?`.
   - Tách biệt `isPending = pendingCarryover || isPendingRefresh`.
   - Hiển thị badge: `daily_task_syncing_badge`, `daily_task_history_verifying_badge`, `daily_task_error_badge`.
   - Bổ sung nút bấm "Thử lại" kèm icon `Icons.Filled.Refresh`.
3. `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt`:
   - Truyền `isDailyTaskError` và `onRetryDailyTask = { viewModel.retryDailyTask() }` xuống `DailyTaskCard`.
   - Dừng retry trong `LifecycleResumeEffect.onPauseOrDispose` qua `viewModel.stopDailyTaskRetry()`.
4. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt`:
   - Bổ sung `isDailyTaskError: Boolean = false` vào `HomeUiState`.
   - Thêm bộ điều phối `publishDailyTaskSnapshot`, kiểm tra fencing UID, generation, mutation version, sequence và tăng phiên bản cache.
   - Triển khai `scheduleDailyTaskRetry()`, `retryDailyTask()`, `stopDailyTaskRetry()`.
   - Kết nối `triggerDailyTaskReloadAfterReport()` vào `observeSubmittedReports()`.
   - Bảo vệ `refresh()` chống ghi đè từ dashboard cũ thông qua `isDailyTaskStale`.
5. `app/src/main/res/values/strings.xml`, `values-vi/strings.xml`, `values-en/strings.xml`:
   - Bổ sung đầy đủ chuỗi giao diện đa ngôn ngữ UTF-8 cho các badge đồng bộ, xác minh lịch sử, báo lỗi và nút Thử lại.

---

## 4. Kết quả kiểm thử nghiệm thu

### A. Tái hiện và kiểm thử đối chứng logic Backend (SQL / PGlite)
- **Lệnh thực hiện:**
  ```powershell
  node audit/daily-task-stuck-2026-11-14/reproduce-pending-status.mjs
  ```
- **Exit code:** `0`
- **Kết quả:** `pass: true` (3/3 ca PASS)
  1. `enough_tests_but_missing_predecessor`: `met: false`, `carryover_ready: false` -> Đúng logic chờ chốt hôm qua.
  2. `same_tests_with_trusted_predecessor`: `met: true`, `carryover_ready: true` -> Hoàn thành khi có checkpoint tin cậy.
  3. `enough_tests_but_unresolved_provenance`: `met: false`, `history_ready: false` -> Chờ đối soát lịch sử nợ.

### B. Nhóm kiểm thử trọng tâm (Focused Test Suite — 18 ca)
- **Lệnh thực hiện:**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*SupervisorDailyTaskRecoveryReviewTest" --tests "*SupervisorDailyTaskHomeRecoveryReviewTest" --tests "*DailyTaskCompletionDisplayTest" --tests "*PendingCarryoverRetryRegressionTest" *>&1 | Tee-Object -FilePath "audit\daily-task-stuck-2026-11-15\antigravity-focused.log"
  ```
- **Exit code:** `0`
- **Tệp nhật ký:** `audit/daily-task-stuck-2026-11-15/antigravity-focused.log`
- **Chi tiết kết quả XML:**

| Lớp kiểm thử (Test Class) | Số ca | Đạt (Pass) | Thất bại (Fail) | Lỗi (Error) | Bỏ qua (Skip) | Thời gian |
|---|---|---|---|---|---|---|
| `SupervisorDailyTaskRecoveryReviewTest` | 3 | 3 | 0 | 0 | 0 | 0.029s |
| `DailyTaskCompletionDisplayTest` | 6 | 6 | 0 | 0 | 0 | 0.003s |
| `PendingCarryoverRetryRegressionTest` | 6 | 6 | 0 | 0 | 0 | 0.067s |
| `SupervisorDailyTaskHomeRecoveryReviewTest` | 3 | 3 | 0 | 0 | 0 | 0.039s |
| **Tổng cộng Focused** | **18** | **18** | **0** | **0** | **0** | **PASS** |

### C. Toàn bộ kiểm thử đơn vị ứng dụng (Full Test Suite — Không bộ lọc)
- **Lệnh thực hiện:**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest *>&1 | Tee-Object -FilePath "audit\daily-task-stuck-2026-11-15\antigravity-full.log"
  ```
- **Exit code:** `0`
- **Tệp nhật ký:** `audit/daily-task-stuck-2026-11-15/antigravity-full.log`
- **Tổng hợp từ 98 tệp kết quả XML:**
  - **Tổng số test suites:** 98
  - **Tổng số ca kiểm thử:** 762
  - **Thành công:** 762
  - **Thất bại (Failures):** 0
  - **Lỗi (Errors):** 0
  - **Bỏ qua (Skipped):** 0

### D. Các bộ test hồi quy nhiệm vụ ngày, nợ bảo lưu và race conditions
Tất cả các ca hồi quy liên quan đều đạt 100%:
- `data.DailyTaskActionGateUnitTest`: 13/13 PASS
- `data.DailyTaskCarryoverUnitTest`: 11/11 PASS
- `data.ImmediateDailyQuotaUnitTest`: 5/5 PASS
- `data.SupervisorDailyTaskStatusCacheReviewTest`: 5/5 PASS
- `ui.viewmodels.OwnerDailyRefreshRaceReviewTest`: 1/1 PASS
- `ui.viewmodels.OwnerDailyTaskOrderingReviewTest`: 1/1 PASS

---

## 5. Những điểm chưa kiểm chứng & Khuyến nghị

1. **Môi trường thiết bị vật lý / Production thật:**
   - Các kiểm thử đã chạy trên môi trường unit test cục bộ với Robolectric và mock HTTP engine / isolated PGlite.
   - Chưa kiểm thử kết nối trực tiếp đến cụm máy chủ Supabase Production thật để đo lường độ trễ mạng thực tế.
   - Chưa kiểm thử thao tác người dùng trên thiết bị Android vật lý qua ADB hoặc Device Hub.
2. **Khuyến nghị tiếp theo:**
   - Người giám sát rà soát diff cuối cùng trước khi đóng nhánh.
   - Thực hiện kiểm thử đầu-cuối (E2E) trên môi trường Staging/Testnet trước khi đóng gói bản phát hành kế tiếp.
