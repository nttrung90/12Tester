# Android Owner Campaign Removal & Pending Removal Lifecycle Integration

**Status**: `SOURCE_STABLE`  
**Execution Date**: 2026-11-11  
**Target Platform**: Android (`app` module in `F:/AI/12Tester`)  
**Gradle Build Status**: `BUILD SUCCESSFUL` (`testDebugUnitTest` 705/705 tests passed, 0 failures, 0 skipped, and `assembleDebug` APK generated)

---

## 1. Architectural & Implementation Summary

### Requirement 1: Data Contracts & DTOs (`Models.kt`)
- **Owner Removal Wire DTO (`DeleteOwnedCampaignParams`)**:
  - Encodes strictly `p_campaign_id` as UUID string:
    ```kotlin
    @Serializable
    data class DeleteOwnedCampaignParams(
        @SerialName("p_campaign_id") val campaignId: String
    )
    ```
- **Removal Result Representation (`AdminDeleteCampaignResult`)**:
  - Extended to support optional `status` and `message` returned by the server:
    ```kotlin
    @Serializable
    data class AdminDeleteCampaignResult(
        val success: Boolean,
        @SerialName("campaign_id") val campaignId: String? = null,
        @SerialName("app_name") val appName: String? = null,
        @SerialName("owner_id") val ownerId: String? = null,
        val reason: String? = null,
        val status: String? = null,
        val message: String? = null,
    ) {
        val isPendingRemoval: Boolean
            get() = success && status == "pending_removal"

        val isRemoved: Boolean
            get() = success && (status == "removed" || status == null)
    }
    typealias DeleteCampaignResult = AdminDeleteCampaignResult
    ```
  - **Status Invariants**:
    - `isPendingRemoval`: explicitly requires `success == true` and `status == "pending_removal"`.
    - `isRemoved`: requires `success == true` and either `status == "removed"` or `status == null` (for backward compatibility with legacy servers that omit status on immediate deletion).
    - Failure or unknown status never evaluates to `isRemoved` or `isPendingRemoval`.

---

### Requirement 2: Repository Wire & Fallback Guard (`CampaignRepository.kt`)
- **`deleteCampaign(campaignId: String): DeleteCampaignResult`**:
  - **Admin Call Routing**: Admins execute `adminDeleteCampaign(campaignId)`. Administrative errors are never fallen back to direct table `DELETE`.
  - **Owner Call Routing**: Owners execute RPC `delete_owned_campaign` with wire argument `DeleteOwnedCampaignParams(campaignId = campaignId)`.
  - **Strict RPC Fallback Guard**:
    - Direct table `DELETE` fallback is executed **strictly** if and only if the RPC is genuinely missing on the server (status 404 with error code `"PGRST202"` or message containing `"function ... does not exist"` via `isMissingRpc(e, "delete_owned_campaign")`).
    - Administrative/policy rejections, HTTP 500/503 errors, authentication failures, network timeouts, or unsettled bonus budget constraints **never** trigger direct table deletion.
  - **Cache Invalidation Discipline**:
    - Both `adminDeleteCampaign` and `deleteCampaign` perform cache invalidation under a captured UID and session generation fence (`capturedUid`, `capturedGen`).
    - Invalidates campaign detail, campaign caches, reports, stats, user profile, credit logs, feed, and dashboard cache (`invalidateDashboardCache()`).

---

### Requirement 3: ViewModels Lifecycle & Stale Session Fencing (`OwnerAppDetailViewModel.kt`, `CampaignDetailViewModel.kt`)
- **`OwnerAppDetailViewModel.deleteCampaign`**:
  - Overloaded with backward-compatible single lambda `(onDeleted: () -> Unit)` and dual lambda `(onDeleted: () -> Unit, onPendingRemoval: (() -> Unit)?)`.
  - Distinguishes `result.isPendingRemoval` from `result.isRemoved`.
  - Synchronously updates `isActionLoading = true, error = null` before launching the coroutine to provide immediate UI feedback and prevent double submissions.
  - Guarded by operation sequence `currentDeleteOpId`, `capturedUid`, and `capturedGen`. If the session changes or user switches accounts before the network call returns, callbacks and UI updates are safely discarded.
- **`CampaignDetailViewModel.adminDeleteCampaign`**:
  - Overloaded with backward-compatible 2-parameter signature and 3-parameter signature supporting `onPendingRemoval`.
  - Distinguishes `isPendingRemoval` from `isRemoved`.
  - Preserves admin admission fences (`admitAdminAction`, `activeAdminToken`, `isActionOwner`, `releaseAdminAction`).

---

### Requirement 4: UI Dialogs & Localized Copy (`OwnerAppDetailScreen.kt`, `CampaignDetailScreen.kt`, `strings.xml`)
- **Localization (`campaign_pending_removal_msg`)**:
  - Default & Vietnamese (`values`, `values-vi`):
    `"Ứng dụng đã dừng nhận test và đang chờ xử lý báo cáo. Ngân sách còn dư sẽ được hoàn khi quyết toán."`
  - English (`values-en`):
    `"The app has stopped accepting tests and is pending report processing. Remaining budget will be refunded upon settlement."`
- **Confirmation Handling in Screens**:
  - Both screens provide `onPendingRemoval` callback to show a Toast with `campaign_pending_removal_msg` before navigating back (`onBack()`), avoiding lost messages from transient screen recompositions.

---

### Requirement 5: Test Verification (`OwnerRemovalLifecycleTest.kt`)
Created 9 comprehensive unit tests in `app/src/test/java/com/ntt/crosstest/data/OwnerRemovalLifecycleTest.kt`:
1. `owner RPC submits only p_campaign_id and decodes status pending_removal`
2. `admin delete RPC decodes status pending_removal and preserves reason`
3. `admin legacy body omitting status is compatible as removed`
4. `OwnerAppDetailViewModel pending does NOT fire onDeleted but DOES fire onPendingRemoval`
5. `CampaignDetailViewModel admin delete pending does NOT fire onDeleted but DOES fire onPendingRemoval`
6. `server 500 error on owner delete does NOT fallback to table DELETE`
7. `genuine PGRST202 404 missing RPC triggers legacy table DELETE fallback`
8. `success false or unknown status does NOT acknowledge as removed`
9. `delayed owner delete response arriving after account switch does NOT trigger navigation`

---

## 2. Verification Results

### Unit Test Suite
Ran:
```powershell
.\gradlew.bat testDebugUnitTest --offline
```
- **Total Tests Completed**: `705`
- **Failures**: `0`
- **Errors**: `0`
- **Skipped**: `0`
- **Success Rate**: `100%`

### Debug APK Assembly
Ran:
```powershell
.\gradlew.bat assembleDebug --offline
```
- **Result**: `BUILD SUCCESSFUL`
- **Output Artifact**: `app/build/outputs/apk/debug/app-debug.apk`

---

## 3. Source Stability Confirmation

- **Workspace**: Preserved dirty workspace, existing migrations, settings, and prior 4-group recommendations.
- **Git State**: No git reset, checkout, clean, or commit operations were executed.
- **Network Boundaries**: No external network or remote calls; all builds and test executions conducted strictly offline.
- **Status**: `SOURCE_STABLE`
