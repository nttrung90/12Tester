# Báo Cáo Bàn Giao Kỹ Thuật Android Admin Flow — 2026-11-01

## 1. Tổng Quan Triển Khai
Thực hiện toàn diện gói sửa lỗi luồng quản trị Android theo kế hoạch `KE_HOACH_SUA_LUONG_ADMIN_2026-11-01.md` (mục 3-4), ghi chú reviewer `reviewer-followup-notes.md`, `android-reviewer-notes.md`, `opencode-android-review.txt` và `opencode-android-test-coordination.txt`.

### Các file thay đổi & tạo mới:
1. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt`
2. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminReportsViewModel.kt`
3. `app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt`
4. `app/src/main/java/com/ntt/crosstest/ui/screens/AdminReportsScreen.kt`
5. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupViewModel.kt`
6. `app/src/test/java/com/ntt/crosstest/ui/viewmodels/NotificationPopupViewModelTest.kt`
7. `app/src/test/java/com/ntt/crosstest/ui/viewmodels/AdminFlowSessionRaceTest.kt` (New behavioral test suite)

---

## 2. Chi Tiết Các Sửa Đổi Kỹ Thuật

### 2.1. Quản lý Vòng Đời & Phiên (Lifecycle & Session Isolation)
- **Quan sát `sessionStatus`**: Cả `AdminUsersViewModel` và `AdminReportsViewModel` đều quan sát trạng thái phiên từ `AuthRepository` (hoặc fallback `CampaignRepository.sessionStatus`).
- **Xóa sạch dữ liệu nhạy cảm**: Khi logout (`SessionStatus.NotAuthenticated`) hoặc đổi tài khoản/generation (`SessionStatus.Authenticated` với `newUid != activeUserId || newGen != activeGeneration`), gọi `handleSessionReset()` xóa toàn bộ danh sách người dùng, báo cáo, app config, home stats config, daily task schedules, lịch sử, bản nháp, thông điệp hành động và lỗi, đồng thời reset toàn bộ cờ loading.
- **Bảo vệ danh tính `uid` + `SessionCacheManager.currentGeneration`**: Mọi entry point công khai (`refresh`, `setUserLock`, `adjustCredits`, `sendNotification`, `loadHomeStatsConfig`, `saveHomeStatsCycle`, `loadDailyTaskConfig`, `saveDailyTaskSchedule`, `cancelDailyTaskSchedule`, `loadDailyTaskHistory`, `loadMoreDailyTaskPolicies`, `loadAppConfig`, `saveAppConfig`, `replyTo`, `setResolved`) đều chủ động gọi `reconcileSession()`.
- **Response Guard & Generation Change**: Kiểm tra danh tính trước khi gọi network và sau khi nhận kết quả. Nếu phát hiện generation hoặc UID thay đổi (`checkSessionChanged`), ViewModel tự động kích hoạt `handleSessionReset(curUid, curGen)` để không lưu giữ state nhạy cảm cũ hay làm kẹt cờ loading.

### 2.2. Thứ Tự Request & Chống Trùng (Ordering & Mutation Admission)
- **Chặn trùng tức thì (Synchronous Admission)**: Cờ loading (`isActionLoading = true`, `isDailyTaskSaving = true`, v.v.) được cập nhật đồng bộ **trước** `viewModelScope.launch`, ngăn chặn double-submit trên `StandardTestDispatcher` hoặc click nhanh.
- **Tách biệt sequence tải & lưu**: Phân tách sequence tải và lưu độc lập cho HomeStats (`homeStatsLoadSeq`, `homeStatsSaveSeq`) và AppConfig (`appConfigLoadSeq`, `appConfigSaveSeq`). Response đọc cũ hoàn tất muộn hơn mutation lưu sẽ giữ nguyên config mới nhất thay vì null hóa hoặc ghi đè config đã lưu.
- **Mutation vô hiệu hóa refresh cũ**: Sau khi mutation thành công, sequence refresh được increment ngay lập tức trước khi gọi `refresh(forceRefresh = true)`. Bất kỳ refresh nào phát động trước mutation đều bị loại bỏ khi trả về.
- **Không nuốt lỗi forced refresh**: Khi `forceRefresh = true` (kéo để làm mới hoặc bấm nút refresh), nếu gặp lỗi mạng thì ViewModel luôn cập nhật `uiState.error` để thông báo cho admin, không nuốt lỗi kể cả khi đã có dữ liệu cũ trong cache.
- **Bảo toàn Cancellation**: Luôn throw `CancellationException` để coroutine hủy bình thường, và chỉ giải phóng cờ loading nếu request hiện tại sở hữu flag đó.

### 2.3. Sửa Lỗi Gốc NotificationPopupViewModel (autoStartPolling = false)
- **Nguyên nhân gốc**: Nhánh `SessionStatus.Authenticated` trong `observeAuthStatus` trước đó gọi `restartPollingLoop()` vô điều kiện, bỏ qua `autoStartPolling = false`. Điều này khiến các test manual tick bị race condition với vòng lặp polling tự động chạy ngầm.
- **Khắc phục**: Thêm điều kiện `if (autoStartPolling)` trước các lệnh gọi `restartPollingLoop()` trong `observeAuthStatus`.
- **Dọn dẹp fixture**: Dùng vòng đời chuẩn `ViewModelStore.clear()` và đóng `client` trong `@After`, gỡ bỏ scaffolding không cần thiết trong code production. Giữ nguyên assertion `Held -> Showing`.

### 2.4. Khôi Phục & Session Ownership Trong Compose Dialogs
- **Lưu ID primitive**: Thay thế việc lưu trực tiếp object `Profile?` và `ContactReport?` (không bundle-saveable) bằng các ID nguyên thủy (`targetUserLockId`, `targetUserCreditId`, `targetUserNotifId`, `targetReportReplyId`) qua `rememberSaveable`.
- **Giải quyết thực thể an toàn (Safe Restoration)**: Dùng `remember(id, list)` để giải quyết target từ danh sách hiện hành. Khi danh sách tải xong (`!isLoading && list.isNotEmpty()`), nếu target ID không tồn tại trong danh sách thì tự động gán null an toàn.
- **Bảo tồn khi xoay màn hình (Same-session Rotation)**: Lưu `savedSessionUid` và `savedSessionGen` qua `rememberSaveable`. Khi Activity tái tạo trong cùng phiên (`savedSessionUid == currentAdminId && savedSessionGen == sessionGeneration`), toàn bộ dialog và bản nháp được giữ nguyên. Khi phiên thay đổi thực sự (UID hoặc generation đổi), toàn bộ dialog được đóng và ID được dọn sạch.
- **Bảo vệ Consumed Save Signals**: Lưu `lastConsumedHomeStatsSignal` và `lastConsumedConfigSignal` qua `rememberSaveable`. Dialog chỉ đóng khi signal thực sự lớn hơn giá trị đã tiêu thụ, ngăn chặn lỗi tự động đóng dialog khi xoay màn hình ở lần mở tiếp theo.
- **Cô lập bản nháp theo Target & Phiên**: Toàn bộ bản nháp credit (`isGrant`, `amountText`, `reasonText`), notification (`title`, `message`, `selectedType`), và phản hồi báo cáo (`message`) được gắn key `(targetId, currentAdminId, sessionGeneration)`. Không bao giờ tái sử dụng bản nháp giữa các người dùng hoặc giữa các phiên admin.

---

## 3. Kết Quả Kiểm Thử Thực Tế

### 3.1. Bộ test hành vi mới `AdminFlowSessionRaceTest` (8 tests):
1. `delayed_stale_refresh_does_not_overwrite_newer_refresh_in_admin_reports`: **PASSED** (0.033s)
2. `refresh_initiated_before_lock_mutation_does_not_overwrite_mutation_result`: **PASSED** (0.043s)
3. `refresh_initiated_before_report_reply_mutation_does_not_overwrite_replied_status`: **PASSED** (0.050s)
4. `account_switch_during_in_flight_request_and_action_drops_old_response_and_clears_sensitive_state`: **PASSED** (0.036s)
5. `generation_change_for_same_uid_rejects_stale_response`: **PASSED** (0.031s)
6. `double_action_submission_with_standard_test_dispatcher_admits_only_first_call`: **PASSED** (0.081s)
7. `stale_home_stats_response_does_not_clear_current_config_or_flags`: **PASSED** (0.040s)
8. `forced_refresh_error_with_existing_data_is_surfaced_to_ui_state`: **PASSED** (0.039s)

### 3.2. Chạy tổ hợp toàn bộ 6 suite liên quan (57 tests):
```powershell
.\gradlew testDebugUnitTest --offline `
  --tests "com.ntt.crosstest.ui.viewmodels.AdminFlowSessionRaceTest" `
  --tests "com.ntt.crosstest.data.AdminDailyTaskPolicyTest" `
  --tests "com.ntt.crosstest.data.AdminDailyTaskHardeningRegressionTest" `
  --tests "com.ntt.crosstest.ui.viewmodels.NotificationPopupViewModelTest" `
  --tests "com.ntt.crosstest.ui.viewmodels.AdminCampaignActionRaceTest" `
  --tests "com.ntt.crosstest.data.AdminRelatedRepositoryRaceTest"
```
**Kết quả: BUILD SUCCESSFUL (57 tests completed, 0 failed, 0 skipped)**
- `AdminFlowSessionRaceTest`: 8 tests — 0 failed
- `AdminCampaignActionRaceTest`: 12 tests — 0 failed
- `AdminRelatedRepositoryRaceTest`: 9 tests — 0 failed
- `AdminDailyTaskPolicyTest`: 7 tests — 0 failed
- `AdminDailyTaskHardeningRegressionTest`: 7 tests — 0 failed
- `NotificationPopupViewModelTest`: 14 tests — 0 failed

### 3.3. Biên dịch & Đóng gói Android:
```powershell
.\gradlew assembleDebug --offline
```
**Kết quả: BUILD SUCCESSFUL in 10s** (39 actionable tasks: 4 executed, 35 up-to-date).

---

## 4. Xác Nhận Tuân Thủ Ràng Buộc
- Không thay đổi SQL/migration hoặc backend test nào.
- Giữ nguyên các thay đổi hiện có trong dirty workspace.
- Không git commit / push / clean / reset.
- Môi trường chạy offline với JDK Studio JBR.
