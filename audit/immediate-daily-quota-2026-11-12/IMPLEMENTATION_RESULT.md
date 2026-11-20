# Implementation Result: Immediate Daily Quota on Campaign Creation & Deletion
Date: 2026-11-12
Model: Gemini 3.8 Flash (High) / Effort: High
Workspace: `F:\AI\12Tester`
Final supervisor acceptance, current packaging authorization and verified artifact: `SUPERVISOR_FINAL.md`.
Authorized Plan: `KE_HOACH_CAP_NHAT_NHIEM_VU_NGAY_NGAY_KHI_DANG_XOA_APP_2026-11-11.md`, `TASK_IMPLEMENTATION.txt` & `TASK_FINAL_SOURCE_REVIEW.txt`

---

## 1. Executive Summary

All functional and non-functional requirements specified in `KE_HOACH_CAP_NHAT_NHIEM_VU_NGAY_NGAY_KHI_DANG_XOA_APP_2026-11-11.md`, all review items (R1..R9), and all final focused source review items (Strict UID, Atomic Cache Publication during IO, Async Initial Disk Load Fence, and Localized Generic Refresh Copy) have been fully resolved, implemented, and verified across durable SQL acceptance tests, native PostgreSQL concurrency suites, supervisor probes, and the complete Android unit test suite (`testDebugUnitTest`):

1. **Immediate Quota Calculation on Campaign Creation**:
   - Newly created eligible campaigns posted today are counted immediately on successful server creation (`created_at < v_next_day_start` when immediate policy is active).
   - Base quota ratio $q$ is dynamically retrieved from effective `daily_task_policies.tests_per_active_app` (not hardcoded to 12 and independent of `target_testers`). Base quota $B = q \times K$ scales immediately (e.g. 1 app $\rightarrow$ 12, 2 apps $\rightarrow$ 24, 3 apps $\rightarrow$ 36).
2. **Immediate Quota Reduction on Removal & Pending Removal**:
   - Removed apps or apps transitioning to `closed` via accepted `pending_removal` (awaiting bonus escrow or dispute settlement) immediately reduce today's active app count $K$ and base quota $B$.
   - Eventual physical cleanup (`_process_closing_tester_bonus_rounds` / `purge_completed_campaigns`) does not duplicate the subtraction.
   - Failed deletion operations do not alter quota.
3. **Preservation of Core Ledger Invariants**:
   - Completed distinct qualifying tests ($D$) and yesterday's sealed carry-in debt ($C$) are preserved across app creation and removal.
   - If $D \ge B_{new} + C$, remaining task requirement clamps at 0 without creating negative debt or carryover credits for tomorrow.
   - Available source limits remain enforced without erasing the base quota, sealed carry-in or completed tests; the server retains its role-specific completion and carryover formulas.
   - Ordinary test and mission test report isolation, owner resume debt gates, admin pause thresholds, immutable financial receipts, and timestamp protections remain strictly preserved.
4. **Registration Day Owner Normalization**:
   - New accounts posting an eligible app on their registration day immediately receive daily quota ($mode = \text{'owner'}$, $carry\_in = 0$, $history\_ready = true$).
   - End-of-day finalization (`_apply_daily_inactivity_penalty`) treats registration day owners under a verified creation boundary ($v\_prev\_streak = 0$, requiring no nonexistent yesterday checkpoint or fake zero row).
   - Accounts without apps posted on registration day retain pure member exemption ($mode = \text{'member'}$, $streak = 0$, $met = true$, no penalty).
5. **Audited Activation Governance & Historical Preservation**:
   - Governed via `public.daily_task_immediate_quota_policies` and `public.daily_task_immediate_quota_policy_audit`.
   - Default seed is an inactive baseline (`-infinity`, false). An authorized administrator schedules a future Vietnamese business day for activation.
   - Historical days prior to policy activation evaluate strictly under historical rules; past sealed rows in `user_daily_tasks` remain frozen immutable snapshots.
6. **Supervisor Defect Resolutions (R1..R9)**:
   - **R1**: Dropped legacy ambiguous `_apply_daily_inactivity_penalty` signatures before recreating exact 2-argument finalizer and 1-argument wrapper.
   - **R2 / R9**: Fixed role detection in `set_daily_task_immediate_quota_policy` and `guard_immediate_daily_quota_policy()` using `v_invoker_role text := nullif(current_setting('role', true), 'none'); v_effective_role text := coalesce(v_invoker_role, session_user); v_is_trusted boolean := (v_effective_role in ('postgres', 'service_role', 'supabase_admin'));`. Cleaned up task-only username `quota_test_admin`.
   - **R3**: Future-only policy scheduling strictly enforced (`effective_from > v_today`). Client retroactive modifications rejected. Baseline seed row permitted on INSERT.
   - **R4 / R5**: Audit RLS requires admin role AND `coalesce(is_locked, false) = false`.
   - **R6**: Enforced canonical global lock order (`profiles` for update $\rightarrow$ `user_daily_tasks`) in `refresh_daily_completion(uuid, date)` to eliminate 40P01 deadlocks.
   - **R7**: Preserved legitimate owner debt posting and error handling.
   - **R8**: Client strictly rejects stale or missing payload date (`fresh.statusDate != expectedDay`), emitting `StalePending`.
7. **Final Focused Source Review Items (TASK_FINAL_SOURCE_REVIEW.txt)**:
   - **Strict UID Check**: `SessionCacheManager.updateCachedData()` enforces exact mismatch check `if (expectedUserId != null && current.userId != expectedUserId) return false`, preventing private user task data from being written into an existing null-UID guest cache.
   - **Atomic Publication During IO**: Introduced dedicated `publicationLock` for RAM cache operations. `SessionCacheManager.save()` acquires `diskLock`, writes staging, then inside `publicationLock` merges latest task fields, rewrites staging if needed, atomically moves staging to target, and publishes to RAM coherently (`diskLock` $\rightarrow$ `publicationLock` order). Legitimate current-generation saves clear tombstone (`isTombstoned = false`). `loadSync` shares `publicationLock` for memory publication.
   - **Async Initial Disk Load Fence**: `HomeViewModel.init` captures `initDailyTaskVersion` and `initBusinessDay` before launching IO load. Preserves current UI task state if task token or business day changed or if task is already non-null, preventing stale disk load from overwriting newer live task events.
   - **Generic Localized Pending Refresh Copy**: Added localized resource `daily_task_campaign_refresh_pending_note` in EN, default, and VI strings, selected in `DailyTaskCard` when `isPendingRefresh` is true, preserving existing `daily_task_carryover_pending_note` for carryover-only pending state.

---

## 2. Changed and Created Files

### Database Migrations & Tests
- `supabase/migrations/088_immediate_daily_quota_on_campaign_changes.sql` (Created):
  - Table `public.daily_task_immediate_quota_policies` with an inactive baseline; activation must be explicitly scheduled for a future day.
  - Access-controlled table `public.daily_task_immediate_quota_policy_audit` records policy changes and the actual caller identity.
  - Functions: `get_daily_task_immediate_quota_policy`, `is_immediate_daily_quota_active`, `set_daily_task_immediate_quota_policy`, `guard_immediate_daily_quota_policy`.
  - Updated `get_daily_task_status`: counts apps created today when policy active, excludes closed/pending removal apps, supports registration day owner mode.
  - Updated `_apply_daily_inactivity_penalty`: handles registration-day owner finalization, sets $v\_prev\_streak = 0$, carries out uncompleted debt, applies inactivity penalty while preserving pure member exemption. Dropped legacy ambiguous overloads.
  - Updated `create_campaign_v2`: atomically invokes `refresh_daily_completion(v_uid, v_vn_today)`.
  - Updated `_request_campaign_removal`: atomically invokes `refresh_daily_completion(v_owner_id, v_today)` on immediate removal and pending removal.
  - Updated `_process_closing_tester_bonus_rounds`: atomically invokes `refresh_daily_completion(v_owner_id, v_today)` on physical deletion without double decrement.
  - Canonical lock order in `refresh_daily_completion`: `perform 1 from public.profiles where id = p_user_id for update;` followed by `user_daily_tasks` lock.
- `supabase/tests/run-immediate-daily-quota-acceptance-test.mjs` (Created):
  - 7 comprehensive scenarios verifying policy activation, 12->24->36 scaling, receipt replay isolation, D and C preservation, deletion scaling, registration day owner/member boundary, and historical cutover.
- `supabase/tests/package.json` (Modified):
  - Added `"test:immediate-quota-acceptance": "node run-immediate-daily-quota-acceptance-test.mjs"`.

### Android Client & Tests
- `app/src/main/java/com/ntt/crosstest/data/DailyTaskChangeEvent.kt` (Created):
  - Sealed interface with `Updated` and `StalePending` events incorporating 4-way fencing metadata (`ownerId`, `generation`, `businessDate`, `mutationVersion`, `triggerSource`, `cause`).
- `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` (Modified):
  - Unified `"daily_task"` mutation version with `SessionCacheManager.currentDailyTaskVersion()` and `nextDailyTaskVersion()`.
  - Added `_dailyTaskEvents` SharedFlow and `dailyTaskEvents` public flow.
  - Implemented `refreshDailyTaskAfterCampaignChange(expectedUid, expectedGeneration, triggerSource)` with 4-way fencing.
  - Rejects payloads with wrong or missing `statusDate`.
  - In `createCampaignV2`, `adminDeleteCampaign`, and `deleteCampaign`: triggers scoped refresh.
  - Post-commit network/RPC failures emit `StalePending` without aborting the campaign action; version fencing guards the catch block against obsolete delayed failures.
- `app/src/main/java/com/ntt/crosstest/data/SessionCacheManager.kt` (Modified):
  - Added `isDailyTaskPendingRefresh` and `dailyTaskMutationVersion` to `CachedSessionData`.
  - Strict UID mismatch check in `updateCachedData`.
  - Introduced `publicationLock` ensuring atomic RAM publication and coherent staging-to-target disk writes.
  - `save()` merges and preserves newer in-memory task data and pending flags.
  - `clear()` / `clearSync()` resets `dailyTaskVersion` synchronously to 0 under `publicationLock`.
  - `loadSync()` rebases `dailyTaskVersion` to max of current and loaded snapshot.
- `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt` (Modified):
  - Captures `initDailyTaskVersion` and `initBusinessDay` before launching async IO disk load in `init`.
  - Preserves current task fields when task token or business day changed or if task is already non-null.
  - Subscribes to `repository.dailyTaskEvents` inside `init`.
  - Dispatches `Updated` event into `_uiState.dailyTask` and `missedStreak`.
  - Implemented bounded retry `scheduleDailyTaskRetry()` for `StalePending`.
  - In `refreshAll()`: captures initial daily task version; preserves UI quota if mutation occurred during slow dashboard fetch.
  - Persists `isDailyTaskPendingRefresh` in snapshot saved to disk.
- `app/src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt` (Modified):
  - Displays generic refresh note (`daily_task_campaign_refresh_pending_note`) when `isPendingRefresh` is true, and carryover note (`daily_task_carryover_pending_note`) when `pendingCarryover` is true.
- `app/src/main/res/values/strings.xml`, `values-en/strings.xml`, `values-vi/strings.xml` (Modified):
  - Added localized `daily_task_campaign_refresh_pending_note`.
- `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt` (Modified):
  - Passes `uiState.isDailyTaskPendingRefresh` into `DailyTaskCard`.
- `app/src/test/java/com/ntt/crosstest/data/ImmediateDailyQuotaUnitTest.kt` (Created):
  - 5 Robolectric unit tests covering create quota refresh, delete quota refresh, post-commit network fault tolerance, HomeViewModel UI state updates, and 4-way fencing.
- `app/src/test/java/com/ntt/crosstest/data/SupervisorImmediateQuotaRaceTest.kt` (Supervisor-owned, preserved):
  - Verified all 6 supervisor race and cache tests pass.

---

## 3. Verification & Evidence

### A. Supervisor Probes & Concurrency Tests (Loopback Native PG Cluster)
1. `node audit/immediate-daily-quota-2026-11-12/governance-probe.mjs`: **EXIT 0**
   - Ordinary member: `FORBIDDEN: Admin access required` (expected)
   - Unidentified authenticated: `UNAUTHORIZED: Authentication required` (expected)
   - Authorized admin: `success: true` (expected)
   - Trusted service role: `success: true` (expected)
2. `node audit/immediate-daily-quota-2026-11-12/fresh-acceptance.mjs`: **EXIT 0**
   - All 27 scenarios passed cleanly.
3. `node audit/immediate-daily-quota-2026-11-12/independent-concurrency.mjs`: **EXIT 0**
   - Create vs owner removal: passed without 40P01 deadlocks (`base: 12, carry: 24`).
   - Create vs admin removal: passed without 40P01 deadlocks (`base: 12, carry: 24`).
4. `node audit/immediate-daily-quota-2026-11-12/independent-finalization-race.mjs`: **EXIT 0**
   - Yesterday finalization vs owner removal: passed.
   - Yesterday finalization vs admin removal: passed.
5. `node audit/immediate-daily-quota-2026-11-12/independent-acceptance.mjs`: **EXIT 0**
   - All 26 scenarios passed.

### B. SQL Acceptance & Regression Suites
1. `npm --prefix supabase/tests run test:immediate-quota-acceptance`: **EXIT 0**
   - SCENARIO 1: Policy governance and audited activation: **PASS**. Final native probes additionally verify the inactive baseline, future-only client scheduling and migration replay.
   - SCENARIO 2: Immediate Quota Increase on `CreateCampaignV2` ($12 \rightarrow 24 \rightarrow 36$): **PASS**
   - SCENARIO 3: Replay Protection & Immutable Receipts Isolation: **PASS**
   - SCENARIO 4: Preservation of Completed Tests ($D=5$) and Carry-in Debt ($C=8$): **PASS**
   - SCENARIO 5: Immediate Quota Reduction on Campaign Removal & Pending Removal ($36 \rightarrow 24 \rightarrow 12 \rightarrow 0$): **PASS**
   - SCENARIO 6: Registration Day Owner Finalization & Member Exemption Boundary: **PASS**
   - SCENARIO 7: Historical Cutover Preserves Old Schema Before Activation Date: **PASS**
2. `npm --prefix supabase/tests run test:carryover-acceptance`: **EXIT 0**
   - C01, C03, BUG-1..5, NEW-BUG-1..2: ALL 14 SCENARIOS PASSED.

### C. Android Unit Test Suites
1. `SupervisorImmediateQuotaRaceTest`: **BUILD SUCCESSFUL** (6 tests, 0 failures, 0 errors, time: 3.5s)
   - `postCommitQuotaReadFailureRetriesEvenWhenPreviousQuotaWasReady`: **PASS**
   - `diskSaveQueuedBeforeMutationCannotRestoreOldQuotaInMemoryOrDisk`: **PASS**
   - `yesterdayQuotaPayloadCannotBePublishedAsCurrentDay`: **PASS**
   - `delayedDashboardCannotOverwriteQuotaAfterCampaignMutation`: **PASS**
   - `persistentQuotaReadFailureHasBoundedRetries`: **PASS**
   - `dashboardSnapshotAfterRepeatedEqualReadsCannotOverwriteLaterQuotaMutation`: **PASS**
2. `ImmediateDailyQuotaUnitTest`: **BUILD SUCCESSFUL** (5 tests, 0 failures, 0 errors, time: 2.7s)
   - `createCampaignV2_bumpsDailyTaskMutationAndRefreshesQuota`: **PASS**
   - `deleteCampaign_bumpsDailyTaskMutationAndEmitsUpdatedQuota`: **PASS**
   - `postCommitRefreshError_emitsStalePendingWithoutFailingFinancialAction`: **PASS**
   - `homeViewModel_updatesUiState_uponDailyTaskChangeEvent`: **PASS**
   - `fourWayFencing_dropsStaleOrMismatchedEvents`: **PASS**
3. Full Android Unit Test Suite (`testDebugUnitTest`): **BUILD SUCCESSFUL** (37s)
   - 716+ unit tests completed and passed with 0 failures, 0 errors.

---

## 4. Constraint Adherence
- App Version: Strictly preserved as `versionName = "1.2.4"` and `versionCode = 44`.
- Packaging: Initial verification generated no APK/AAB. The user subsequently authorized test packaging; the supervisor built and verified one debug APK (1.2.4 / 44). No release APK/AAB was generated or published; see `SUPERVISOR_FINAL.md`.
- Deployment / VCS: No commits, push or production deployment. Local CLI/build/test processes were used; the private test database was stopped after verification.
- Baseline Preservation: All existing dirty/untracked files and migrations 062..087 remain untouched and valid.
- Supervisor Assets: All supervisor test fixtures and probe scripts preserved intact.
