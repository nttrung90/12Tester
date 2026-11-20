# Handover: Android Admin-Related Race Fixes (AppConfigRepository & CampaignDetailViewModel)

**Date**: 2026-11-01  
**Status**: **READY FOR ROOT GRADLE**  
**Assigned Scope**: Exclusively `AppConfigRepository.kt`, `CampaignDetailViewModel.kt`, and behavioral tests `AdminRelatedRepositoryRaceTest.kt`, `AdminCampaignActionRaceTest.kt`.

---

## 1. Summary of Identified Races & Implementations

### A. `AppConfigRepository.kt`
1. **Repository Admission & Publication Atomicity under `cacheLock`**:
   - *Problem*: Incrementing `mutationSeq` and `inFlightMutations` separately allowed readers to capture a new generation with zero in-flight mutations in between, allowing a stale read to publish after a successful save.
   - *Fix*: Atomic mutation registration under `cacheLock`:
     ```kotlin
     val mutationId = synchronized(cacheLock) {
         mutationSeq++
         inFlightMutations++
         mutationSeq
     }
     ```
     Atomic read snapshot capture under the same `cacheLock`:
     ```kotlin
     val (requestId, mutationGenAtStart, hadInFlightMutationsAtStart) = synchronized(cacheLock) {
         requestSeq++
         Triple(requestSeq, mutationSeq, inFlightMutations > 0)
     }
     ```
     Unregistering in `finally` under `synchronized(cacheLock) { inFlightMutations-- }`.
     Memory (`cachedConfig`) and disk (`saveToDisk` with `commit()`) publications are guarded together under the same `cacheLock`, while network RPCs execute outside the lock to avoid blocking across network operations.
2. **Reverse Out-of-Order Saves Overwriting Newer Cache**:
   - *Fix*: Each mutation acquires an incremented `mutationId`. Under `cacheLock`, publication checks `if (mutationId > latestPublishedMutation)`. Stale writes cannot overwrite newer cache/disk, while still returning honest RPC results to callers.
3. **Reads While Save In-Flight Not Superseding Saved Config**:
   - *Fix*: `hadInFlightMutationsAtStart` and `inFlightMutations > 0` conditions prevent reads from overwriting cache/disk while a save is in flight.
4. **CancellationException Handling**:
   - *Fix*: Added explicit `if (e is CancellationException) throw e` in both `getAppConfig` and `updateAppConfig`.
5. **Durable Persistence & Cold Repo Restore**:
   - *Fix*: Switched disk write in `saveToDisk` to `commit()` on `withContext(Dispatchers.IO)` so disk writes are synchronously durable for cold repository restore. Preserved exact public global cache semantics (10-minute TTL), `forceRefresh` error handling, and legacy bilingual fallback behavior.

### B. `CampaignDetailViewModel.kt`
1. **Backing Fields Declaration Order (Eliminating Constructor NPE)**:
   - *Problem*: Backing fields (`adminActionLock`, `adminActionSequence`, `activeAdminToken`, `activeAdminUid`, `activeAdminGen`) were declared after `init`. When `init` launched collectors on `Dispatchers.Main.immediate` or `UnconfinedTestDispatcher` with an already authenticated client, `resetOldAdminActionIfSessionChanged()` was called before property initialization, throwing a `NullPointerException`.
   - *Fix*: Moved all admin action ownership backing fields to the top of the class body before `init`, `refreshSequence`, and `firstTimeTester`.
2. **Owned Token for Admin Actions & Session Change Reset**:
   - *Problem*: A single `AtomicBoolean` without ownership left `isActionLoading = true` after session changes, blocked the new session until the old network call finished, and risked an old `finally` clearing a new action's loading.
   - *Fix*: Implemented owned serial token architecture:
     - **Admission**: Checks if an action is already active for the *exact same session* (`activeAdminUid == currentUid && activeAdminGen == currentGen`). If session or generation changed, the old session's action is immediately invalidated and superseded, allowing the new session to admit its action immediately without waiting for the old network call to finish.
     - **Session Observer**: `repository.sessionStatus` observer resets any active admin action and resets `isActionLoading = false` on `SessionStatus.NotAuthenticated` or when `currentUserId()` changes.
     - **Refresh Integration**: `refresh()` checks and resets old admin action using real `repository.currentUserId()` (never `effectiveUserId()`, which falls back to constructor `myUserId` and would incorrectly invalidate a new user's active token).
     - **Ownership Guards**: Pre-network and post-response guards verify `isActionOwner(token, initiatingUid, initiatingGen)` so responses from an old session never publish, never trigger refreshes, and never invoke `onDeleted()`.
     - **Owned Cleanup**: `releaseAdminAction(token)` in `finally` ONLY clears `activeAdminToken` and `isActionLoading` if `activeAdminToken == token`. An old request's `finally` cannot clear a newer action's gate or loading.
3. **Ordering with In-Flight Refreshes**:
   - *Fix*: `refreshSequence.incrementAndGet()` is called upon initiating the admin action and upon completing the mutation, invalidating older in-flight refreshes so stale refreshes cannot override the mutated state.
4. **Cancellation Handling**:
   - *Fix*: Added `catch (e: CancellationException) { throw e }`. Cancellation releases only its own owned token and resets `isActionLoading` without interfering with newer sessions.

---

## 2. Root Validation Run & Fixture Coordination Fixes

Root executed focused Gradle validation (21 tests total):
- `AdminRelatedRepositoryRaceTest` (AppConfigRepository): **100% PASSED** (9/9).
- `AdminCampaignActionRaceTest`: 15 passed, 6 failed due to asynchronous test coordination with MockEngine IO and DTO fixture schema.

### Root Causes & Corrections in `AdminCampaignActionRaceTest.kt`:
1. **Profile DTO Contract in Fixtures**:
   - `detailJson` used `"is_admin":true`, but the `Profile` model defines `role: String = "member"` with `isAdmin: Boolean get() = role.equals("admin", ignoreCase = true)`. Updated fixture to `"role":"admin"`.
2. **MockEngine IO and StandardTestDispatcher Coordination**:
   - Ktor `MockEngine` dispatches network handling and response decoding on `Dispatchers.IO`. In tests using `runBlocking` + `StandardTestDispatcher`, calling `advanceUntilIdle()` or `runCurrent()` only drains tasks already enqueued on the Main dispatcher scheduler; it does not wait for pending IO tasks to resume Main.
   - *Correction*: Added `awaitJob(job, timeoutMs)` and `waitUntil(timeoutMs) { condition }` helpers that pump `testDispatcher.scheduler.runCurrent()` and `advanceUntilIdle()` while yielding to IO (`withContext(Dispatchers.IO) { Thread.sleep(5) }`), allowing asynchronous Ktor/Postgrest responses to complete and queue their Main continuations deterministically without blind sleeps.
3. **Explicit Child Job Tracking for In-Flight Actions**:
   - In delayed User A -> switch User B tests, captured the launched action Job via `vm.viewModelScope.coroutineContext[Job]?.children?.lastOrNull()`. Used `awaitJob(jobA)` to guarantee User A's coroutine and its `finally` block have completely finished processing before asserting that User B's loading remained intact.
4. **Initial Fetch Synchronization**:
   - Synchronized initial screen loading after `createVm(...)` with `waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }`, eliminating race conditions where assertions ran before initial RPC decoding completed.
5. **Bounded Timeouts & Scope Cleanup**:
   - Bounded all tests with `withTimeout(10_000)` and cleaned up `store.clear()` (`ViewModelStore`) in `@After cleanup()`.

---

## 3. Modified Production Files

1. `app/src/main/java/com/ntt/crosstest/data/AppConfigRepository.kt`
2. `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt`

*(No shared models, interfaces, build dependencies, SQL, or other ViewModels were modified in this session).*

---

## 4. Test Suites & Exact Class Names

### 1. `com.ntt.crosstest.data.AdminRelatedRepositoryRaceTest`
- **File**: `app/src/test/java/com/ntt/crosstest/data/AdminRelatedRepositoryRaceTest.kt`
- **Runner**: Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`)
- **Key Test Cases** (all bounded with `withTimeout(10_000)`):
  - `staleReadStartedBeforeMutation_doesNotOverwriteSavedConfigInRamOrDisk_andColdRestoreRestoresMutation`
  - `staleWritesCompletedInReverseOrder_doNotOverwriteNewerMutationInCacheOrDisk`
  - `readWhileSaveInFlight_doesNotSupersedeSavedConfig`
  - `getAppConfig_rethrowsCancellationException`
  - `forceRefresh_errorBehavior_returnsNullOnForcedRefresh_andCachedOnNormal`
  - `legacyBilingualFallback_recoversOnCapabilityErrorWithoutEnglish`
  - `legacyBilingualFallback_rethrowsWhenEnglishIsProvided`
  - `publicGlobalCacheSemantics_respectsTtl`
  - `atomicRegistrationAndPublication_preventsStaleReadPublishAfterSave`

### 2. `com.ntt.crosstest.ui.viewmodels.AdminCampaignActionRaceTest`
- **File**: `app/src/test/java/com/ntt/crosstest/ui/viewmodels/AdminCampaignActionRaceTest.kt`
- **Runner**: Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`)
- **Key Test Cases** (all bounded with `withTimeout(10_000)`, deterministic `awaitJob`/`waitUntil` coordination, and `ViewModelStore` lifecycle cleanup):
  - `constructor_withUnconfinedTestDispatcherAndAuthenticatedClient_doesNotCrash`: Tests ViewModel constructor under `UnconfinedTestDispatcher` with an already authenticated client to verify no initialization order or NPE crashes.
  - `pendingBAction_refreshDoesNotReleaseBGate_duplicateBActionRejected`: VM constructed with constructor user A, user switches to B and starts an admin action; `refresh()` runs without releasing B's gate; duplicate B action attempt is rejected, ensuring exactly 1 RPC is sent.
  - `delayedA_switchB_startB_finishA_whileBPending_preservesLoading_noACallbacks_thenBCompletesNormally`: User A starts delete -> switches to User B -> User B starts pause -> User A finishes while User B is still pending: verifies no A callbacks, User B retains `isActionLoading = true`, and then User B completes normally.
  - `sameUidGenerationChange_allowsNewAdminActionBeforeOldCompletes_andOldFinallyDoesNotClearNewLoading`: Generation changes for same UID while action 1 is in-flight; action 2 is admitted immediately; action 1 completing does not clear action 2's loading; action 2 completes normally.
  - `delayedAdminDeleteResponse_afterAccountChange_neverExecutesOnDeletedOrPublishesState`: Admin A deletes campaign; account changes to Admin B during network latency; verifies `onDeleted()` callback NEVER executes, message is not published, and loading flag is safely reset.
  - `delayedAdminPauseResponse_afterSameUidGenerationChange_doesNotPublishOrRefresh`: Same UID generation change during pause latency drops publication and avoids triggering stale refresh.
  - `duplicateSubmissions_withQueuedStandardTestDispatcher_admitsOnlyFirstCall`: Using queued `StandardTestDispatcher`, double clicks synchronously admit only 1 RPC and invoke callback exactly once.
  - `synchronousAdmission_setsIsActionLoadingImmediately`: Asserts `isActionLoading == true` synchronously before test dispatcher advances.
  - `cancellationHandling_andOwnedFlagCleanup`: Cancelling during in-flight RPC cleans up owned token so subsequent calls are admitted.
  - `inFlightRefresh_doesNotOverrideNewMutationState`: An in-flight background refresh returning `status = "open"` is rejected by `refreshSequence` after admin pauses app (`status = "paused"`).
  - `validAdminActionSuccess_andDeleteCallbackExactlyOnce`: Verifies normal pause, resume, and delete flows publish expected messages, trigger refreshes, and call `onDeleted()` exactly once.
  - `preNetworkGuard_unauthenticatedAdmin_rejectsImmediately`: Unauthenticated caller is synchronously rejected with permission denied error without making network calls.

---

## 5. Verification & Constraints Adherence

- **Gradle Execution**: Per explicit instructions, **NO GRADLE COMMAND WAS RUN** in this phase.
- **Source Inspection**: Node/source checks and git diffs verified that only assigned files and tests were modified/created.
- **Dirty Workspace**: Preserved all existing dirty workspace files and edits intact.
- **Dependencies / Models**: No Gradle build files or data models were altered.
- **Backend / SQL**: Untouched.

**Ready for Root to coordinate sequential Gradle validation for:**
- `com.ntt.crosstest.data.AdminRelatedRepositoryRaceTest`
- `com.ntt.crosstest.ui.viewmodels.AdminCampaignActionRaceTest`
