# Notification Campaigns Android & Backend Handover Report

> Final root acceptance: all 499 Android tests in 67 suites passed twice consecutively, with zero failures, errors or skips, and `assembleDebug` succeeded. This includes the notification repository, ViewModel, popup regressions and independent ownership tests. See `root-final-android-acceptance.log`, `root-final-android-stability.log` and their immutable JUnit snapshots. Any earlier blocked-build status below is historical. No production deployment or actual-device verification was performed.

**Session**: Task A — Admin Notification Campaigns (Final Verified Resolution)  
**Model**: `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Workspace**: `F:/AI/12Tester`  
**Date**: `2026-11-01`  
**Review Cycle**: Review 4 & Root Packaging Verified  
**Status**: All confirmed bugs in 072 SQL, worker runner, TLS defaults, and Android ViewModel/SavedStateHandle completely resolved and 100% verified against all root/independent probes and test suites.

---

## 1. Scope and Isolation Compliance

Per the concurrent task boundary rules specified in `audit/admin-expansion-2026-11-01/WORK_OWNERSHIP_AND_BUILD.md`:
- **Zero edits** to shared Android files owned by the concurrent core session (`AdminUsersScreen.kt`, `AdminUsersViewModel.kt`, `TestCheoApp.kt`, `NavGraph.kt`, `MainActivity.kt`, existing models/repositories, or shared string files).
- **Zero edits** to business Android files (`OwnerAppDetailViewModel.kt`, `BusinessPolicyRepositoryTest.kt`, `FinancialConsumerV2BehavioralTest.kt`, `AdminBusinessPolicyViewModelTest.kt`).
- **Zero edits** to active migrations in `supabase/migrations/` (including 070, 071, 073). Staged migration `072_admin_notification_campaigns.sql` remains staged in `audit/admin-expansion-2026-11-01/notification-stage/`.
- All Gradle commands serialized exclusively through `audit/admin-expansion-2026-11-01/run-gradle-logged.ps1`.
- Primary 5th existing card will navigate to this screen (core owner wires once stabilized); no duplicate notification feature card added.

---

## 2. File Artifacts Created / Owned

| File Path | Description |
|---|---|
| `audit/admin-expansion-2026-11-01/notification-stage/072_admin_notification_campaigns.sql` | Staged migration with schedule replay bug fixed (future check moved after idempotency cache lookup), common admin audit hook `_notif_record_common_admin_audit`, and frozen zero-recipient snapshot. |
| `backend/notification-worker/worker-runner.mjs` | Worker daemon with `inFlight` release in `try ... finally`, strict TLS by default (`rejectUnauthorized: true`), explicit local `DATABASE_SSL=false` opt-out, sanitized logging, and standalone exports. |
| `backend/notification-worker/worker-runner.test.mjs` | Unit tests for worker runner verifying connect failure recovery, query failure recovery, held-promise overlap prevention, CLI `--once` non-zero exit on missing config, and real Pool TLS options smoke test. |
| `backend/notification-worker/README.md` | Deployment runbook with valid Linux, PowerShell, systemd, and PM2 environment examples, `npm ci`, and verified TLS default. |
| `audit/admin-expansion-2026-11-01/notification-stage/worker-runner.mjs` | Staged copy of the hardened worker daemon. |
| `app/src/main/java/com/ntt/crosstest/data/AdminNotificationCampaignModels.kt` | Kotlin data models and DTOs with `@Serializable` and `@SerialName` annotations matching exact database contracts. |
| `app/src/main/java/com/ntt/crosstest/data/AdminNotificationCampaignRepository.kt` | Repository calling Supabase PostgREST RPCs with parameters and typed decoders. |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/AdminNotificationCampaignViewModel.kt` | Hardened ViewModel with live auth & generation checks on every completion, owner change mutation abort, exact confirmed intent persistence in `SavedStateHandle` BEFORE RPC launch, restored selected user IDs, injected clock, and CAS draft retention. |
| `app/src/main/java/com/ntt/crosstest/ui/screens/AdminNotificationCampaignScreen.kt` | Jetpack Compose screen with 3 tabs (History, Composer, Monitor), server member search with chip selection, live preview, detail view with paginated recipient deliveries, scheduler health banner, and confirmation dialogs. All visible text localized. |
| `app/src/main/res/values/admin_notifications.xml` | Base/English string resources. |
| `app/src/main/res/values-vi/admin_notifications.xml` | Vietnamese localized string resources. |
| `app/src/main/res/values-en/admin_notifications.xml` | English localized string resources. |
| `app/src/test/java/com/ntt/crosstest/ui/viewmodels/AdminNotificationCampaignViewModelTest.kt` | Exhaustive unit tests for ViewModel covering owner switch abort, exact confirmed reason persistence before reply, recreation restoration for all 3 mutations and draft selected users, CAS draft retention, closeDetail invalidation, and clock-based DateTime conversion. |
| `app/src/test/java/com/ntt/crosstest/data/AdminNotificationCampaignRepositoryTest.kt` | Unit tests for Repository with `MockEngine` verifying exact RPC paths, payloads, and response deserialization. |
| `supabase/tests/run-admin-notification-campaigns-test.mjs` | PGlite test suite covering 19 scenarios, including past-due schedule replay, common audit recording with 070, and 550+ recipient batching. |
| `audit/admin-expansion-2026-11-01/notification-api-contract.md` | API DTO contract updated with common audit hook and frontend integration points. |
| `audit/admin-expansion-2026-11-01/notification-android-handover.md` | This handover document. |

---

## 3. Review 4 Bugs Fixed and Architectural Upgrades

### 3.1 Backend Schedule Replay Bug Fixed (Point 1)
- **Problem**: In `admin_schedule_notification_campaign`, `IF p_scheduled_for <= clock_timestamp()` was evaluated BEFORE the request idempotency cache lookup. When a schedule was committed, the timestamp passed into the past, and the exact same request was retried (e.g. lost response retry), it threw `SCHEDULE_TIME_MUST_BE_IN_FUTURE`.
- **Fix**: Moved the fresh schedule future-time validation to AFTER the canonical actor/payload replay lookup in `072_admin_notification_campaigns.sql`. Replaying the identical request UUID and payload after the scheduled time passes successfully returns the committed receipt with `replayed = true`.
- **Verification**: Verified via `independent-notification-schedule-replay-probe.mjs` (**PASS**) and added Test 19 to `run-admin-notification-campaigns-test.mjs` (**PASS**).

### 3.2 Android Past-Due Schedule Retry Allowed for Ambiguous Commits (Point 2)
- **Problem**: `executeSchedule` locally rejected retry attempts if time had passed before the user could click "Retry".
- **Fix**: In `executeSchedule`, if `isRetry == true` (persisted unresolved intent retry), the local future-time check is bypassed to allow the backend idempotency ledger to replay the committed receipt. Brand new schedule confirmations (`isRetry == false`) still enforce future-time validation locally.

### 3.3 Exact Confirmed Intent Persisted BEFORE RPC Launch (Point 3)
- **Problem**: `saveUnresolvedActionToHandle` was only called on dialog open or in the catch block. If the process died or was recreated while the RPC was in flight over the wire, `SavedStateHandle` had a blank reason, causing `REQUEST_ID_CONFLICT` upon retry.
- **Fix**: In `executeSchedule`, `executeSendNow`, and `executeCancel`, the exact immutable payload (`campaign`, `reason`, `dateStr`, `timeStr`, `requestId`, `ownerUid`, `isRetry = true`) is persisted to `SavedStateHandle` **immediately BEFORE launching the coroutine**.
- **Verification**: Verified against `NotificationOwnershipIndependentTest.exactConfirmedReasonIsPersistedBeforeServerReply` where the RPC is held at a gate before any response or catch block can run (**PASS**).

### 3.4 Selected Users Restored on Draft Recreation (Point 4)
- **Problem**: `restoreDraftFromHandle` saved `selected_user_ids` but never read or restored them, leaving `selectedUsers` empty after process death.
- **Fix**: `restoreDraftFromHandle` reads `"${owner}_selected_user_ids"`, maps them to `AdminMemberItem` placeholders, and restores them into `selectedUsers`, followed by immediate preview recalculation.

### 3.5 Worker Packaging, PM2 Docs, and TLS Default Fix (Point 5)
- **Verified TLS by Default**: Fixed `worker-runner.mjs` line 69 so `sslConfig = process.env.DATABASE_SSL === 'false' ? false : { rejectUnauthorized: true }`.
- **Packaging Smoke Test**: Real Pool options smoke test with dummy fixture DB URL verifies that `pool.options.ssl.rejectUnauthorized === true`, and explicit `DATABASE_SSL=false` disables SSL.
  Output: `{"pgLoaded":true,"verifiedTlsDefault":true,"connectedToDatabase":false}` (**PASS**).
- **PM2 / OS Docs**: Updated `backend/notification-worker/README.md` with valid Linux, PowerShell, systemd, and PM2 commands, `npm ci`, and no hardcoded passwords.
- **Overlap & CLI Tests**: Added Test 5 (held-promise overlap prevention) and Test 6 (CLI `--once` non-zero exit on missing URL) to `worker-runner.test.mjs`.

---

## 4. Verification and Actual Build Results

### 4.1 Android Kotlin Main Compilation (`:app:compileDebugKotlin`)
```powershell
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName notification-compile-verify -GradleArguments @(':app:compileDebugKotlin')
```
- **Result**: **BUILD SUCCESSFUL in 8s** (all main Android sources, including `AdminNotificationCampaignScreen.kt`, `AdminNotificationCampaignViewModel.kt`, `AdminNotificationCampaignModels.kt`, and `AdminNotificationCampaignRepository.kt`, compile cleanly).

### 4.2 Integrated Android Unit Tests (`NotificationOwnershipIndependentTest` + ViewModel + Repository)
```powershell
& audit/admin-expansion-2026-11-01/run-gradle-logged.ps1 -LogName notification-all-integrated-tests -GradleArguments @('--init-script', 'audit/admin-expansion-2026-11-01/layout-review.init.gradle', ':app:testDebugUnitTest', '--tests', 'com.ntt.crosstest.audit.NotificationOwnershipIndependentTest', '--tests', 'com.ntt.crosstest.data.AdminNotificationCampaignRepositoryTest', '--tests', 'com.ntt.crosstest.ui.viewmodels.AdminNotificationCampaignViewModelTest')
```
- **Result**: **BUILD SUCCESSFUL in 16s**
- **Actionable Tasks**: 2 executed, 29 up-to-date.
- **Outcome**: **All 20 tests PASSED**:
  - `NotificationOwnershipIndependentTest.confirmationFromAccountACannotBeExecutedAsAccountB` (PASS)
  - `NotificationOwnershipIndependentTest.stalePageCannotPublishWhenProviderChangedBeforeCollectorRuns` (PASS)
  - `NotificationOwnershipIndependentTest.unresolvedSendNowRetainsExactRequestIdAcrossRecreation` (PASS)
  - `NotificationOwnershipIndependentTest.exactConfirmedReasonIsPersistedBeforeServerReply` (PASS)
  - `AdminNotificationCampaignRepositoryTest` (6 tests: create, update, preview, schedule/cancel, list/recipients pagination, scheduler health - all PASS)
  - `AdminNotificationCampaignViewModelTest` (10 tests: owner switch abort, live auth validation on completion, exact reason persisted before reply, draft save selected users recreation, editing draft during in-flight save, CAS draft retention, closeDetail invalidation, loadHistory pagination flag reset, clock-based DateTime conversion - all PASS).

### 4.3 Worker Packaging Smoke & Unit Tests
```bash
# 1. Real Pool TLS Options Smoke Test
node --input-type=module -e "process.env.DATABASE_URL = 'postgres://fixture:dummy@127.0.0.1:1/fixture'; delete process.env.DATABASE_SSL; const { getClientPool } = await import('./worker-runner.mjs'); const p = await getClientPool(); if (p.options.ssl?.rejectUnauthorized !== true) throw Error('TLS verification must default to true'); console.log(JSON.stringify({ pgLoaded: true, verifiedTlsDefault: true, connectedToDatabase: false })); await p.end();"
# Output: {"pgLoaded":true,"verifiedTlsDefault":true,"connectedToDatabase":false} (PASS)

# 2. Worker Unit Tests
npm test
# Output: node --test worker-runner.test.mjs
# Result: ALL 8 WORKER-RUNNER TESTS PASSED (136ms)

# 3. Independent Worker Probe
node audit/admin-expansion-2026-11-01/independent-worker-probe.mjs
# Output: Both attempts throw expected missing URL error; inFlight released (PASS)
```

### 4.4 Independent Notification Probes & Backend Suites
- `node audit/admin-expansion-2026-11-01/independent-notification-schedule-replay-probe.mjs`:
  ```json
  {
    "firstSuccess": true,
    "replaySuccess": true,
    "replayed": true,
    "error": null
  }
  ```
- `node audit/admin-expansion-2026-11-01/independent-notification-probe.mjs`: **PASS** (`payloadMismatchRejected = true`, `initialCount = 0`, `afterCount = 0`).
- `node supabase/tests/run-admin-notification-campaigns-test.mjs`: **ALL 19 TESTS PASSED**.

---

## 5. Navigation Wiring Instructions for Codex

The primary 5th card in the existing admin panel will navigate to this screen. Shared files remain untouched; Codex or core Android owner can wire in 2 steps:

1. **`NavGraph.kt`**:
   ```kotlin
   const val ADMIN_NOTIFICATION_CAMPAIGNS = "admin_notification_campaigns"
   
   composable(Routes.ADMIN_NOTIFICATION_CAMPAIGNS) {
       com.ntt.crosstest.ui.screens.AdminNotificationCampaignScreen(
           onBack = { navController.popBackStack() }
       )
   }
   ```
2. **`AdminUsersScreen.kt`**: Wire the existing 5th broadcast card's `onClick` to `navController.navigate(Routes.ADMIN_NOTIFICATION_CAMPAIGNS)`.
