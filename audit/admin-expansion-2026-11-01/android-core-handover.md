# Báo Cáo Bàn Giao Kỹ Thuật Android Core Management (Chặng B Hoàn Tất 100%)
Ngày lập: 01/11/2026 (Cập nhật 16:15 UTC)
Tác giả: Core Android (`ses_e694e3214ffeA65KCtmBe1HUpC`)
Model: `google/antigravity-gemini-3.8-flash` (`--variant high`)
Môi trường biên dịch: Android Gradle Plugin 9.1.1, Kotlin 2.2.21, Java 25 Adoptium, Robolectric 4.14+
Cơ chế thực thi Gradle: `audit/admin-expansion-2026-11-01/run-gradle-logged.ps1` (Mutex `Local\Codex12TesterGradle`, `--offline`, `--no-daemon`, redirected output)

Bản lưu trữ chính thức: `audit/admin-expansion-2026-11-01/android-core-handover.md`

---

## 1. Tóm tắt các hạng mục hoàn thành trong phạm vi Core Android

Tuân thủ nghiêm ngặt ma trận phân quyền `WORK_OWNERSHIP_AND_BUILD.md`, phiên Core Android phụ trách toàn diện các tệp mã nguồn quản trị cốt lõi, giải quyết triệt để các lỗi độc lập được phản hồi và hoàn tất 100% các yêu cầu nghiệp vụ theo hợp đồng API:

### 1.1 Khắc phục lỗi độc lập Carryover Streams (`CarryoverStreamsIndependentTest`)
- **Nguyên nhân lỗi cũ**: `CarryoverPolicyViewModel` trước đó sử dụng chung một `loadSeq` cho cả `loadPolicies()` và `loadAuditHistory()`. Khi một lệnh tải lịch sử kiểm toán chạy trong lúc tải chính sách đang diễn ra, nó làm tăng `loadSeq`, khiến phản hồi chính sách bị coi là lỗi thời và làm kẹt cờ `isLoading = true`.
- **Giải pháp triển khai**:
  - Tách biệt độc lập các chuỗi tuần tự theo luồng: `configSeq`, `futureSeq`, `auditHistorySeq`, `actionSeq`.
  - Thao tác `loadAuditHistory` sử dụng riêng `auditHistorySeq`, hoàn toàn không ảnh hưởng tới `configSeq`.
  - Tải trang đầu chính sách tương lai qua RPC thực tế `admin_list_future_carryover_policies(limit = 50)`.
  - Xử lý lỗi mạng khi tải trang tương lai: Ghi nhận `futureError` và hiển thị ra `uiState.error` để người dùng làm mới rõ ràng (qua nút làm mới trên TopAppBar), **tuyệt đối không bịa đặt `hasMore = true` khi không có `next_cursor` từ server**.
  - Kiểm tra CAS revision: `schedulePolicy` và `cancelFuturePolicy` bắt buộc phải có `revision` hợp lệ từ server, không cho phép gửi mutation khi revision null.
  - Kết quả kiểm thử: **2/2 ca kiểm thử trong `CarryoverStreamsIndependentTest` đều PASSED 100%**.

### 1.2 Độ bền bồi hoàn tài chính qua khởi động lại tiến trình (SavedStateHandle Persistence)
- **Cơ chế khôi phục bền vững**:
  - `MemberDetailViewModel` lưu trữ intent bồi hoàn (`CompensationIntent`) và `compensationRequestId` vào `SavedStateHandle` với các khóa: `targetUserId`, `adminUID`, `requestId`, `amount`, `reason`, `origLogId`, `contactId`, `isUnresolved`.
  - Lưu intent xuống đĩa **TRƯỚC** khi dispatch lệnh RPC (bảo đảm an toàn kể cả khi process chết trong lúc mạng đang gửi mà không có catch nào chạy).
  - Khôi phục intent bền vững dựa trên cặp định danh bền vững `(targetUserId, adminUID)` (không phụ thuộc vào bộ đếm `SessionCacheManager` cục bộ của tiến trình vốn bị reset về 0).
  - Không tự động submit lại khi vừa mở lại app (`no auto-submit`); hiển thị thẻ thông báo yêu cầu chưa hoàn tất kèm nút "Thử lại yêu cầu chưa hoàn tất" và nút "Hủy bỏ yêu cầu này".
  - Nút "Thử lại" tái sử dụng chính xác cùng `compensationRequestId` ban đầu, kích hoạt cơ chế replay idempotency của backend.
  - `dismissPendingCompensation()` ẩn banner thông báo trên giao diện nhưng **giữ lại** intent và UUID trong handle; lần bồi hoàn tiếp theo nếu nhập cùng nội dung sẽ tiếp tục dùng UUID đó, chống trùng tiền triệt để.
  - Chỉ xóa intent khi backend trả về kết quả xác nhận thành công hoặc lỗi định tính dứt khoát (`USER_NOT_FOUND`, `INSUFFICIENT_PRIVILEGE`).
  - Khi chuyển sang tài khoản Admin B: Admin B tuyệt đối không nhìn thấy và không thể kích hoạt intent còn treo của Admin A.

### 1.3 Bảo vệ phiên làm việc & Stale Confirmation Guard
- **Idempotent Session Reset**:
  - `handleSessionReset(newUid, newGen)` kiểm tra `if (activeUserId == newUid && activeGeneration == newGen) return;`.
  - `checkSessionChanged(reqUid, reqGen)` và `isStale(reqUid, reqGen)`: Khi phản hồi cũ của Admin A đến sau khi Admin B đã đăng nhập, nó chỉ đơn thuần bị hủy (`return@launch`), **tuyệt đối không gọi reset phiên của Admin B**.
- **Ngăn chặn xác nhận stale**:
  - Các thao tác nhạy cảm (`changeRole`, `compensateCredits`, `revokeWarning`, `schedulePolicy`, `cancelFuturePolicy`, `approveReport`, `rejectReport`, `retryJob`, `retryBatch`) đều nhận `confirmingAdminId` và `confirmingSessionGen`.
  - Nếu phiên quản trị viên bị thay đổi giữa lúc mở dialog xác nhận và lúc bấm nút thực thi: Lệnh bị hủy bỏ lập tức với thông báo lỗi thân thiện `admin_action_aborted_session_changed`, yêu cầu xác nhận lại dưới phiên mới.

### 1.4 Phân trang chính sách tương lai A6 (125+ items)
- Tích hợp RPC mới `admin_list_future_carryover_policies(p_limit, p_cursor_date)` vào DTO, Repository, ViewModel và Screen.
- Trang đầu tiên tải từ RPC thực tế; hỗ trợ nút "Tải thêm" cuộn con trỏ ngày tăng dần (ASC) không bỏ sót và không lặp phần tử.
- Kiểm thử `carryoverPolicy_futurePagination125Items_noDuplicatesOrOmissions` xác minh phân trang 125 chính sách tương lai qua 3 trang (50/50/25) theo ngày ISO `LocalDate` hợp lệ không bỏ sót và không trùng lặp, đọc tham số từ POST body JSON.
- Mọi chính sách tương lai vượt mốc 50 bản ghi đều có thể hủy trực tiếp kèm lý do kiểm toán.

### 1.5 Hoàn tất điều hướng cuối cùng & Bố cục
- **`NavGraph.kt` nối các tuyến thực tế**:
  - `Routes.ADMIN_NOTIFICATION_CAMPAIGNS` -> `AdminNotificationCampaignScreen(onBack = { navController.popBackStack() })`.
  - `Routes.ADMIN_BUSINESS_POLICIES` -> `AdminBusinessPolicyScreen(onBack = { navController.popBackStack() })`.
  - `Routes.ADMIN_MEMBER_DETAIL`: `admin_member/{userId}` -> `MemberDetailScreen`.
  - `Routes.ADMIN_CAMPAIGNS` -> `AdminCampaignsScreen`.
  - `Routes.ADMIN_TESTER_REPORTS` -> `AdminTesterReportsQueueScreen`.
  - `Routes.ADMIN_AUDIT` -> `AdminAuditScreen`.
  - `Routes.ADMIN_MONITOR` -> `AdminSystemMonitorScreen`.
  - `Routes.ADMIN_CARRYOVER` -> `CarryoverPolicyScreen`.
- **`AdminUsersScreen`**:
  - Giữ nguyên thứ tự 5 thẻ ban đầu: (1) Quản lý thành viên, (2) Nhiệm vụ ngày, (3) Chu kỳ thống kê trang chủ, (4) Cấu hình cập nhật phiên bản, (5) Gửi thông báo toàn hệ thống.
  - Thẻ 5 nối trực tiếp tới trình quản lý chiến dịch thông báo `onNavigateToNotificationCampaigns`, giữ nguyên nhãn gốc. Hộp thoại gửi thông báo riêng cho 1 user được bảo toàn.
  - Thêm thẻ thứ 6: "Chính sách nghiệp vụ" ngay sau thẻ 5.
  - Thẻ thành viên bảo toàn FlowRow layout fix ở 320dp / fontScale 200%.

### 1.6 Hàng đợi duyệt báo cáo & Media Gateway
- DTO `AdminTesterReportQueueItem` nhận đầy đủ: `media_expired`, `review_tester_bonus`, `review_owner_bonus`, `business_policy_id`, `provisional_granted_credits`, `provisional_clawed`, `bonus_awarded`.
- Giao diện duyệt hiển thị huy hiệu thưởng duyệt cấu hình thực tế (+2 Credit), loại bỏ ô nhập thưởng tùy ý của caller, gọi RPC duyệt với tham số tương thích `bonus = 0`.
- Toàn bộ ảnh thumbnail và phóng to toàn màn hình đều qua `ScreenshotRequestFactory` và `FullscreenImageViewer`.

---

## 2. Kết quả kiểm thử thực tế độc lập (Logged Mutex Helper)

Toàn bộ các bộ test được chạy qua `audit/admin-expansion-2026-11-01/run-gradle-logged.ps1` dưới mutex tuần tự hóa hệ thống:

Lệnh thực thi tổng hợp:
```powershell
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName full-core-suite-final -GradleArguments @('-I','audit/admin-expansion-2026-11-01/layout-review.init.gradle',':app:testDebugUnitTest','--tests','com.ntt.crosstest.ui.viewmodels.AdminManagementCoreTest','--tests','com.ntt.crosstest.audit.MemberDetailPaginationIndependentTest','--tests','com.ntt.crosstest.audit.AdminExpansionMemberCardLayoutTest','--tests','com.ntt.crosstest.audit.CarryoverStreamsIndependentTest','--tests','com.ntt.crosstest.ui.viewmodels.AdminFlowSessionRaceTest','--tests','com.ntt.crosstest.ui.viewmodels.AdminCampaignActionRaceTest','--tests','com.ntt.crosstest.data.AdminRelatedRepositoryRaceTest','--tests','com.ntt.crosstest.data.AdminDailyTask*')
```

### Bảng kết quả chi tiết từng bộ kiểm thử (Trích xuất từ XML kết quả test thực tế):
| Tên bộ kiểm thử (Test Suite) | Số test | Thất bại | Lỗi | Trạng thái |
|---|---|---|---|---|
| `AdminManagementCoreTest` | 12 | 0 | 0 | **PASSED 100%** |
| `MemberDetailPaginationIndependentTest` | 2 | 0 | 0 | **PASSED 100%** |
| `AdminExpansionMemberCardLayoutTest` | 5 | 0 | 0 | **PASSED 100%** |
| `CarryoverStreamsIndependentTest` | 2 | 0 | 0 | **PASSED 100%** |
| `AdminFlowSessionRaceTest` | 9 | 0 | 0 | **PASSED 100%** |
| `AdminCampaignActionRaceTest` | 12 | 0 | 0 | **PASSED 100%** |
| `AdminRelatedRepositoryRaceTest` | 9 | 0 | 0 | **PASSED 100%** |
| `AdminDailyTaskPolicyTest` | 7 | 0 | 0 | **PASSED 100%** |
| `AdminDailyTaskHardeningRegressionTest` | 7 | 0 | 0 | **PASSED 100%** |
| **TỔNG CỘNG** | **65** | **0** | **0** | **TẤT CẢ VƯỢT QUA (100%)** |

### Chi tiết 12 ca kiểm thử trong `AdminManagementCoreTest`:
1. `action_ownerChangedDuringConfirmation_abortsAndRequiresFreshConfirm`: Đổi phiên giữa dialog và action abort không gọi RPC.
2. `compensation_requestIdReusedOnFailureRetry_andNewIdOnPayloadChange`: Cùng intent dùng lại đúng requestId, đổi payload cấp requestId mới.
3. `navigation_adminRoutes_contractAndConstruction`: Hợp đồng định tuyến và đường dẫn helper khớp 100% đặc tả.
4. `memberDetail_staleActionPreflightAfterSessionReset_doesNotReleaseNewSessionGuard`: Action cũ phiên A không giải phóng guard phiên B.
5. `carryoverPolicy_revisionConflict_reloadsWithoutLosingDraft`: Tải lại khi xung đột revision giữ nguyên bản nháp.
6. `memberSearch_staleLoadMoreAfterQueryChange_isIgnored`: LoadMore của từ khóa cũ bị bỏ qua khi đổi từ khóa.
7. `sameUID_newGeneration_rejectsStalePage_andPreservesFreshState`: Cùng UID nhưng thế hệ session mới loại bỏ trang cũ và hiển thị trang mới sau refresh hợp lệ.
8. `adminManagementDto_exactJsonParsingAgainstRealBackendContract`: Phân giải JSON chuẩn xác cho 16 cấu trúc DTO backend (bao gồm cả mảng slots và carryover history).
9. `compensation_doubleSubmitBeforeCoroutineStarts_isGuarded`: Nhấn đúp đồng thời trước khi coroutine chạy chỉ gửi đúng 1 lệnh mạng.
10. `carryoverPolicy_futurePagination125Items_noDuplicatesOrOmissions`: Phân trang 125 chính sách tương lai qua 3 trang (50/50/25) theo ngày ISO LocalDate hợp lệ không bỏ sót và không trùng lặp, đọc tham số từ POST body JSON.
11. `compensation_heldRpcProcessRecreate_noCatchRan_restoresOriginalRequestForReplay`: RPC bị kẹt giữa chừng (chưa chạy catch), process khởi động lại vẫn khôi phục chính xác intent gốc để replay.
12. `compensation_savedStateHandleRestoration_persistsIntentAndRequestId_noAutoSubmit`: Khôi phục intent và requestId từ SavedStateHandle mà không tự ý gửi ngầm lệnh mạng.

### Kiểm tra biên dịch ứng dụng debug:
- Lệnh: `& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName assemble-debug-final-2 -GradleArguments @(':app:assembleDebug')`
- Kết quả: **`BUILD SUCCESSFUL in 13s`** (39 actionable tasks: 3 executed, 36 up-to-date).

---

## 3. Tệp tin đã chỉnh sửa / tạo mới trong Chặng B

1. `app/src/main/java/com/ntt/crosstest/data/AdminManagementModels.kt` (DTOs, Envelopes, Slots, Carryover history & future pagination).
2. `app/src/main/java/com/ntt/crosstest/data/AdminManagementRepository.kt` (18 RPCs core + slot details + future pagination).
3. `app/src/main/java/com/ntt/crosstest/TestCheoApp.kt` (Cấp phát `adminManagementRepository`).
4. `app/src/main/java/com/ntt/crosstest/ui/navigation/NavGraph.kt` (Đầy đủ các tuyến quản trị mới + kết nối màn hình thực).
5. `app/src/main/java/com/ntt/crosstest/ui/screens/AdminUsersScreen.kt` (Bảo toàn 5 thẻ gốc, thẻ carryover, 6 thẻ công cụ mới, FlowRow fix).
6. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminUsersViewModel.kt` (Server-side member search, pagination, idempotent reset, owner guard).
7. `app/src/main/java/com/ntt/crosstest/ui/screens/MemberDetailScreen.kt` (Overview, credit history, daily task explanation, warnings, slot details, durable retry banner).
8. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/MemberDetailViewModel.kt` (Durable SavedStateHandle, request ID idempotency, per-stream sequence, session guards).
9. `app/src/main/java/com/ntt/crosstest/ui/screens/CarryoverPolicyScreen.kt` (Bảng chính sách cộng dồn, lên lịch, hủy lịch, tải thêm lịch tương lai >50 items).
10. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CarryoverPolicyViewModel.kt` (Tách biệt config/future/audit sequences, future pagination, conflict draft reload, CAS revision validation).
11. `app/src/main/java/com/ntt/crosstest/ui/screens/AdminCampaignsScreen.kt` (Duyệt chiến dịch toàn hệ thống, lọc trạng thái, phân trang).
12. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminCampaignsViewModel.kt` (Server search & pagination, session guards).
13. `app/src/main/java/com/ntt/crosstest/ui/screens/AdminTesterReportsQueueScreen.kt` (Hàng đợi duyệt báo cáo, media gateway protection, snapshot bonus display, approve compatibility bonus 0).
14. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminTesterReportsQueueViewModel.kt` (Xử lý duyệt/từ chối, token guard, session guards).
15. `app/src/main/java/com/ntt/crosstest/ui/screens/AdminAuditScreen.kt` (Nhật ký kiểm toán, lọc đa chiều, hiển thị trước/sau).
16. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminAuditViewModel.kt` (Phân trang sự kiện kiểm toán).
17. `app/src/main/java/com/ntt/crosstest/ui/screens/AdminSystemMonitorScreen.kt` (Giám sát vận hành DB thật, hàng đợi tác vụ lỗi, retry đơn/lô).
18. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminSystemMonitorViewModel.kt` (Summary & retry settlement jobs).
19. `app/src/main/res/values/admin_management.xml` (Base strings: >75 chuỗi giao diện, không hardcode).
20. `app/src/main/res/values-en/admin_management.xml` (English strings).
21. `app/src/main/res/values-vi/admin_management.xml` (Vietnamese strings).
22. `app/src/test/java/com/ntt/crosstest/ui/viewmodels/AdminManagementCoreTest.kt` (12 ca kiểm thử toàn diện cho Core).
23. `android-backend-gaps.md` (Cập nhật hiện trạng backend 070 media_expired và 071 queue fields).
24. `android-core-handover.md` (Bản sao lưu tại thư mục gốc workspace).

---

## 4. Tôn trọng ranh giới sở hữu các module khác
- Phiên Core Android **không** sửa đổi SQL, không chạm vào các file tài chính thuộc Business Android (`BusinessPolicy*`, `071`, `FinancialConsumer*`) và không chạm vào Notification (`072`, `worker`, `AdminNotificationCampaign*`).
- Mọi kiểm thử và build đều đi qua `run-gradle-logged.ps1` có bảo vệ mutex, giữ cho workspace ổn định hoàn toàn.
