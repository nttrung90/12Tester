# Biên bản Bàn giao Toàn diện Android Business Policy Editor & Financial Consumers

> **Nghiệm thu cuối của Codex:** Hai lượt toàn bộ liên tiếp đạt **499/499 test trong 67 suite, 0 thất bại/lỗi/bỏ qua**, kèm `assembleDebug` thành công. Xem `root-final-android-acceptance.log`, `root-final-android-stability.log`, `root-final-android-results.json` và `root-final-stability-results.json`. Sau khi OpenCode chạm quota, người dùng đã cho phép Codex trực tiếp hoàn tất phần còn lại. Codex sửa fixture Auth khởi tạo/dọn dẹp và ca kiểm tra admin ẩn danh; không hạ các assertion nghiệp vụ hoặc guard quyền/phiên. Các số liệu chặng riêng dưới đây là lịch sử, không thay thế nghiệm thu toàn bộ này. Chưa thử trên thiết bị thật hoặc deploy production.

**Tài liệu:** `audit/admin-expansion-2026-11-01/business-android-handover.md`  
**Chặng:** C — Android Business Policy & Financial Consumers  
**Ngày:** 01/11/2026  
**Thực hiện:** OpenCode (`google/antigravity-gemini-3.8-flash`, `--variant high`)  
**Tình trạng:** Hoàn tất 100% phạm vi được giao. Tất cả 100/100 ca kiểm thử đơn vị tự động đã VƯỢT QUA (BUILD SUCCESSFUL).  

---

## 1. Danh sách file và các chức năng tài chính đã triển khai

### 1.1 Các file tạo mới (Scope sở hữu của Business Android)
| Đường dẫn file | Nội dung chức năng |
| :--- | :--- |
| `app/src/main/java/com/ntt/crosstest/data/BusinessPolicyModels.kt` | Typed DTOs cho chính sách cố định (welcome, penalty tiers, push, slot, report, app update), tham số RPC CAS, Quotes (`PushQuote`, `SlotQuote`, `AppUpdateQuote`, `AllBusinessQuote`), V2 call params (`PushCampaignV2Params`, `UnlockOrRenewSlotV2Params`, `PublishAppUpdateV2Params` với hỗ trợ rõ ràng cho `expectedCurrentReleaseId: String? = null`). |
| `app/src/main/java/com/ntt/crosstest/data/BusinessPolicyRepository.kt` | Repository Supabase Postgrest cho các RPC quản trị và công khai (`admin_get_business_policy_config`, `admin_schedule_business_policy`, `admin_cancel_business_policy`, `admin_list_business_policies`, `admin_get_business_policy_history`, `get_effective_business_policy`, `get_business_quote`). Không fake default policy khi lỗi mạng lúc giao dịch; bộ nhớ đệm cache ngắn hạn có guard UID và session generation; `publishAppUpdateV2` gửi tường minh `JsonNull` khi caller truyền null. |
| `app/src/main/java/com/ntt/crosstest/data/FinancialIntentStore.kt` | Bộ lưu trữ bền vững (SharedPreferences commit đồng bộ xuống đĩa) cho các intent tài chính mở slot (`PendingSlotIntent`) và đăng ký cập nhật app (`PendingPublishUpdateIntent`). Cách ly 100% giữa các user (`${userId}_*`), bảo lưu toàn bộ điều khoản giá/token/UUID gốc qua sự cố mất mạng hoặc process death/recreation; không tự động gửi ngầm khi restore mà cung cấp API retry tường minh. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminBusinessPolicyViewModel.kt` | ViewModel quản trị chính sách với 4 tab (`ACTIVE`, `SCHEDULED`, `EDITOR`, `HISTORY`). Ràng buộc validation chặt chẽ (bounds, thứ tự tăng dần của các bậc phạt, kiểm tra ngày tương lai bằng `LocalDate.parse` theo giờ VN, ghi chú lý do 1-1000 ký tự). Chống mất bản nháp khi gặp xung đột CAS revision (`BUSINESS_POLICY_CONFLICT`). Cổng đồng bộ `AtomicBoolean` trước khi launch coroutine. Khôi phục draft và pending cancel dialog từ `SavedStateHandle`. Phân trang chính sách tương lai (`loadMoreFuturePolicies`). |
| `app/src/main/java/com/ntt/crosstest/ui/screens/AdminBusinessPolicyScreen.kt` | Màn hình Compose hoàn chỉnh: Tab selector, thẻ hiển thị baseline/ngày hiệu lực/revision, form biên tập 2x2 cho các bậc phạt và phí slot thân thiện màn hình 320dp/font 200%, gắn kết nối `createSavedStateHandle()` thật sự trong factory, phân trang chính sách tương lai và lịch sử con trỏ ID, dialog hủy lịch kèm lưu giữ intent. |
| `app/src/main/res/values/admin_business_policy.xml` | Chuỗi tài nguyên bản địa hóa mặc định (EN) cho editor, summary card, hôm nay, và định dạng thời hạn slot `%1$d days`. |
| `app/src/main/res/values-vi/admin_business_policy.xml` | Chuỗi tài nguyên bản địa hóa tiếng Việt đầy đủ cho editor, summary card, hôm nay, và định dạng thời hạn slot `%1$d ngày`. |
| `app/src/main/res/values-en/admin_business_policy.xml` | Chuỗi tài nguyên bản địa hóa tiếng Anh chuẩn xác. |
| `app/src/test/java/com/ntt/crosstest/data/BusinessPolicyRepositoryTest.kt` | 10 ca kiểm thử MockEngine kiểm tra exact RPC names, JSON payload decoding, opaque revision tokens, `JsonNull` CAS, và không fake default policy khi lỗi mạng. |
| `app/src/test/java/com/ntt/crosstest/ui/viewmodels/AdminBusinessPolicyViewModelTest.kt` | 12 ca kiểm thử ViewModel: chuyển tab, bounds/order validation, `LocalDate` parsing, tính ngân sách app update tự động, CAS conflict reload giữ nguyên bản nháp, lưu giữ dialog/UUID hủy lịch khi lỗi, cổng đồng bộ chặn double-invoke, sinh UUID mới khi sửa form, khôi phục `SavedStateHandle`, phân trang lịch sử. |
| `app/src/test/java/com/ntt/crosstest/data/FinancialConsumerV2BehavioralTest.kt` | 6 ca kiểm thử hành vi tài chính V2: slot quote token, conflict không tự trừ tiền, push V2 replay provenance, app update V2 dynamic budget & bảo toàn snapshot release cũ, quote unavailable vô hiệu hóa `canPublish`, slot bắt buộc có revision token. |
| `app/src/test/java/com/ntt/crosstest/ui/viewmodels/AppUpdateReleaseViewModelBehavioralTest.kt` | 3 ca kiểm thử hành vi ViewModel: mất kết nối khi publish lưu intent xuống đĩa, recreate ViewModel gửi lại đúng UUID và quote gốc, cổng atomic mutual exclusion giữa publish và close, xung đột báo giá xóa disk intent và yêu cầu xác nhận lại. |
| `app/src/test/java/com/ntt/crosstest/ui/viewmodels/SlotAndPushDurabilityBehavioralTest.kt` | 4 ca kiểm thử hành vi: gia hạn slot mất kết nối thử lại dùng đúng UUID từ `FinancialIntentStore`, đẩy app mất kết nối thử lại dùng đúng request ID từ `CampaignPushStore`, cách ly intent giữa các user, thiếu báo giá slot chặn giao dịch. |
| `app/src/test/java/com/ntt/crosstest/data/FinancialIntentStoreTest.kt` | 3 ca kiểm thử bộ lưu trữ bền vững: lưu/đọc/xóa slot intent, lưu/đọc/xóa publish intent bảo toàn exact null release CAS, cách ly dữ liệu giữa User A và User B. |
| `audit/admin-expansion-2026-11-01/business-android-backend-gaps.md` | Tài liệu phân tích khoảng trống và tương thích ngược giữa Android và Backend. |

### 1.2 Các file sửa đổi mục tiêu (Targeted Financial Patches)
| Đường dẫn file | Nội dung sửa đổi mục tiêu tài chính |
| :--- | :--- |
| `app/src/main/java/com/ntt/crosstest/data/Models.kt` | Bổ sung `replayed`, `clientRequestId`, `renewalDays` vào `UnlockSlotResult`; `renewalDays`, `policyRevision` vào `SlotInfo`; `reviewTesterBonus`, `reviewOwnerBonus`, `policyId` vào `TesterReport` kèm fallback an toàn. |
| `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` | Thêm `pushCampaignV2`, `unlockOrRenewSlotV2`, `publishAppUpdateV2` (gửi tường minh `JsonNull` khi `expectedCurrentReleaseId` null); cập nhật `getMySlotsInfo` đọc báo giá động từ server (`get_business_quote("all")`) thay vì hardcode `SlotInfo.costForSlot`; thêm `getAppUpdateQuote`, `getPushQuote`, `getSlotQuote`. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/ProfileViewModel.kt` | Khóa đồng bộ `isSlotActionInProgress`, tích hợp `FinancialIntentStore` lưu giữ intent mở slot qua sự cố mạng, bắt buộc `slot.policyRevision != null`, xử lý xung đột giá/revision không tự động trừ tiền ngầm; cập nhật số dư profile sau trừ chính xác. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/ProfileScreen.kt` | Dialog mở/gia hạn slot hiển thị số ngày gia hạn thực tế qua resource định dạng (`admin_policy_slot_renewal_days_format`), nút xác nhận yêu cầu `slot.policyRevision != null`; truyền đúng `expectedCost` và `expectedRevision` đã xác nhận. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CreateCampaignViewModel.kt` | Khóa đồng bộ `isSlotActionInProgress`, tích hợp `FinancialIntentStore`, bắt buộc có báo giá hợp lệ trước khi mở khóa slot từ màn hình tạo chiến dịch. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/CreateCampaignScreen.kt` | Dialog hiển thị thời hạn động qua resource định dạng và nút xác nhận yêu cầu `slot.policyRevision != null`. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/OwnerAppDetailViewModel.kt` | Tích hợp `pushCampaignV2` thay thế check cứng 100 Credit; cập nhật `pushCost` và `pushRevision` từ báo giá server; bảo toàn cơ chế `CampaignPushStore` durable pending request ID; khi gặp xung đột chính sách (`BUSINESS_POLICY_PRICE_MISMATCH`), làm mới báo giá và yêu cầu người dùng xác nhận lại; cập nhật `renewCurrentSlot` dùng `FinancialIntentStore` tái sử dụng UUID khi mất mạng. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/OwnerAppDetailScreen.kt` | Dialog xác nhận đẩy app và dialog thử lại hiển thị giá động `uiState.pushCost` và số dư dự kiến sau trừ theo giá thực tế; kiểm tra `hasValidPushQuote`. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AppUpdateReleaseViewModel.kt` | Thêm trạng thái báo giá rõ ràng (`hasValidQuote`), kiểm tra `!policyRevision.isNullOrBlank()`; cổng `canPublish` vô hiệu hóa khi chưa có báo giá; khóa đồng bộ `actionLock` giữa publish và close; tích hợp `FinancialIntentStore` lưu giữ `PendingPublishUpdateIntent` với exact NULL CAS; lắng nghe auth lifecycle hủy bỏ stale publish khi đổi account. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/AppUpdateReleaseScreen.kt` | Thẻ form hiển thị banner cảnh báo và nút thử lại khi có yêu cầu publish đang chờ xử lý từ phiên trước; cập nhật hiển thị ngân sách động và thanh tiến độ theo `reservedBudget`, `rewardPerClaim`, `maxClaims`. |
| `app/src/test/java/com/ntt/crosstest/data/PushAndCarryoverHardeningTest.kt` | Cấu hình fixture MockEngine trả về báo giá hợp lệ cho `get_business_quote` và thêm `enableLifecycleCallbacks = false` trong `install(Auth)` để ngăn chặn lỗi `SetupPlatform.addLifecycleCallbacks` làm ô nhiễm cross-test. |
| `app/src/test/java/com/ntt/crosstest/data/CacheRegressionTest.kt` | Thêm `enableLifecycleCallbacks = false` trong `install(Auth)`. |
| `app/src/test/java/com/ntt/crosstest/data/AppUpdateRewardTest.kt` | Cập nhật fixture `validBase` có `policyRevision = "valid-quote-token-fixture"`, `isLoading = false`, `isQuoteLoading = false`, bổ sung ca kiểm tra từ chối publish khi token rỗng/null. |

---

## 2. Hướng dẫn Điều hướng dành cho Core Android Agent

Core Android agent khi gắn kết nối màn hình quản trị chính sách vào `NavGraph.kt` và bảng điều khiển Admin:
1. **Route:**
   ```kotlin
   const val ROUTE_ADMIN_BUSINESS_POLICIES = "admin_business_policies"
   ```
2. **NavHost:**
   ```kotlin
   composable(ROUTE_ADMIN_BUSINESS_POLICIES) {
       com.ntt.crosstest.ui.screens.AdminBusinessPolicyScreen(
           onBack = { navController.popBackStack() }
       )
   }
   ```
3. **Thẻ công cụ Admin:**
   ```kotlin
   AdminToolCard(
       title = stringResource(R.string.admin_policy_title),
       description = "Cấu hình credit khởi tạo, tỷ lệ phạt, phí đẩy app, phí slot và thưởng cập nhật",
       onClick = { navController.navigate(ROUTE_ADMIN_BUSINESS_POLICIES) }
   )
   ```

---

## 3. Kết quả Kiểm thử & Trạng thái Biên dịch Thực tế

### 3.1 Biên dịch Kotlin (`:app:compileDebugKotlin`)
Chạy qua công cụ điều phối mutex `run-gradle-logged.ps1`:
```powershell
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName business-android-compile-6 -GradleArguments @(':app:compileDebugKotlin')
```
- **Kết quả:** `BUILD SUCCESSFUL in 33s`. Toàn bộ mã nguồn Kotlin của ứng dụng biên dịch thành công 100%.

### 3.2 Bộ Test Đơn vị Tự động Chạy Gộp (Combined Regression Run)
Lệnh thực thi gộp toàn bộ 9 suite qua mutex helper:
```powershell
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName test-combined-business-final -GradleArguments @(
    ':app:testDebugUnitTest',
    '--tests', 'com.ntt.crosstest.data.BusinessPolicyRepositoryTest',
    '--tests', 'com.ntt.crosstest.ui.viewmodels.AdminBusinessPolicyViewModelTest',
    '--tests', 'com.ntt.crosstest.data.FinancialConsumerV2BehavioralTest',
    '--tests', 'com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseViewModelBehavioralTest',
    '--tests', 'com.ntt.crosstest.ui.viewmodels.SlotAndPushDurabilityBehavioralTest',
    '--tests', 'com.ntt.crosstest.data.FinancialIntentStoreTest',
    '--tests', 'com.ntt.crosstest.data.AppUpdateRewardTest',
    '--tests', 'com.ntt.crosstest.data.PushAndCarryoverHardeningTest',
    '--tests', 'com.ntt.crosstest.data.CacheRegressionTest'
)
```
- **File nhật ký build:** `audit/admin-expansion-2026-11-01/test-combined-business-final.log`
- **Thư mục snapshot JUnit:** `audit/admin-expansion-2026-11-01/test-result-snapshots/test-combined-business-final-20261101-162656-541`
- **Kết quả chi tiết từng suite:**
  1. `AppUpdateRewardTest`: 10/10 tests passed (0 failures, 0 errors, 0 skipped).
  2. `BusinessPolicyRepositoryTest`: 10/10 tests passed (0 failures, 0 errors, 0 skipped).
  3. `CacheRegressionTest`: 36/36 tests passed (0 failures, 0 errors, 0 skipped).
  4. `FinancialConsumerV2BehavioralTest`: 6/6 tests passed (0 failures, 0 errors, 0 skipped).
  5. `FinancialIntentStoreTest`: 3/3 tests passed (0 failures, 0 errors, 0 skipped).
  6. `PushAndCarryoverHardeningTest`: 16/16 tests passed (0 failures, 0 errors, 0 skipped).
  7. `AdminBusinessPolicyViewModelTest`: 12/12 tests passed (0 failures, 0 errors, 0 skipped).
  8. `AppUpdateReleaseViewModelBehavioralTest`: 3/3 tests passed (0 failures, 0 errors, 0 skipped).
  9. `SlotAndPushDurabilityBehavioralTest`: 4/4 tests passed (0 failures, 0 errors, 0 skipped).
  - **Tổng cộng:** 100/100 tests PASSED (0 failures, 0 errors).

---

## 4. Giới hạn Kiểm thử & Bàn giao Hoàn tất

- Toàn bộ các ca kiểm thử trên được xác minh qua Gradle offline trên môi trường Robolectric/JVM với Ktor MockEngine và SharedPreferences cục bộ.
- Không có bất kỳ kết nối mạng trực tiếp nào đến máy chủ Supabase live hay thử nghiệm trên thiết bị Android vật lý trong phiên làm việc này (tuân thủ nghiêm ngặt chỉ thị an toàn).
- Quá trình chạy kiểm thử toàn diện độc lập trên toàn bộ hệ thống và đóng gói APK cuối cùng sẽ do Root điều phối.
