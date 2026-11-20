# Step 2 Verification Result: Focused Next-App Priority & Egress Telemetry Fixes

## 1. Summary & Overview
All 4 targeted correctness fixes and their corresponding behavioral tests have been implemented across the client repository, ViewModel, screen, and test suites.

- **Targeted Test Command**:
  `.\gradlew.bat testDebugUnitTest --tests com.ntt.crosstest.util.NextCampaignSelectorTest --tests com.ntt.crosstest.ui.viewmodels.CampaignDetailNextAppPriorityTest --tests com.ntt.crosstest.data.EgressFinalClientReviewTest --offline`
- **Sandbox Environment Status**:
  As anticipated in the task instructions, running Gradle test tasks directly from inside the Windows container/sandbox failed to connect to the forked daemon loopback TCP socket (`Could not connect to the Gradle daemon. Daemon pid: ... Listening on port 63840`). The exact run output was recorded in `audit/next-app-priority-2026-11-10/runs/step2-focused.log`. Parent/controller runner outside the container should execute the verification command.

---

## 2. Files Changed

### A. Production Code
1. **`app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt`**:
   - Fixed telemetry measurement bug in `getCampaignsStatsMap`: added `EgressTelemetry.noteNetworkAttempt("get_campaigns_stats")` immediately preceding the actual Postgrest RPC network attempt inside `deduped`'s execution block. Single-flight coalescing and cache hits calling `getCampaignsStatsMap` bypass the block and do not overcount.

2. **`app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt`**:
   - Fixed sequence ownership and staleness handling in `prefetchNextCampaign` and `consumePrefetchedCandidatesInternal`:
     - When `seq != nextSuggestionSequence.get()`, old sequences immediately exit with zero writes to `_uiState`.
     - When `seq == nextSuggestionSequence.get()`, if identity changed (`curUid != targetUid`), the prompt is closed (`showNextAppPrompt = false`, `nextCampaignToTest = null`, `nextSuggestionLoading = false`, `nextSuggestionFailed = false`).
     - If identity matches but session generation, business day, or campaign mutation became invalid, stale next selection is cleared (`nextCampaignToTest = null`), loading is stopped (`nextSuggestionLoading = false`), and a retryable failed state is published (`nextSuggestionFailed = true`), preventing discarded data from appearing as a genuine "all apps done" success.
   - Added method `validateAndConsumeNextCampaign(): String?`:
     - Verifies whether the selected suggestion and `prefetchedCandidates` snapshot are still fresh for current identity, day, session generation, and mutation.
     - If fresh, returns the campaign ID.
     - If stale, clears `nextCampaignToTest`, sets `nextSuggestionLoading = true`, triggers `prefetchNextCampaign(forceRefresh = true)`, and returns `null`.
   - Exposed narrow test seams:
     - `internal var testedInThisViewModel = false`
     - `internal var nextCampaignRandomPicker: (List<NextCampaignCandidate>) -> NextCampaignCandidate? = { it.randomOrNull() }`

3. **`app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt`**:
   - Updated "Test now" (`campaign_test_now_btn`) confirm button callback to invoke `viewModel.validateAndConsumeNextCampaign()`.
   - Navigates and dismisses the dialog only when a valid, fresh campaign ID is returned; if `null` is returned (due to stale day/mutation), the dialog remains open, transitions to loading, and avoids navigating stale targets.

### B. Test Suites
4. **`app/src/test/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailNextAppPriorityTest.kt`**:
   - Updated `prefetch_staleResponseAfterSessionBump_isIgnored`: added `assertTrue(state.nextSuggestionFailed)` to assert that stale responses publish retryable failure rather than appearing as genuine-empty success.
   - Strengthened `firstSubmissionSnapshot_preservedAcrossSubmitCompletion`:
     - Configured `nextCampaignRandomPicker = { it.firstOrNull() }` for deterministic assertion.
     - Prefetches with `testedInThisViewModel = false`, capturing `isFirstTimeTesterSnapshot = true`.
     - Simulates report submission completion while candidate fetch is in-flight (`testedInThisViewModel = true`).
     - Verifies first-submission snapshot is preserved across submission completion, choosing `c2` (installed_count = 2).
     - Then simulates subsequent selection (`prefetchNextCampaign(forceRefresh = true)`), asserting that `isFirstTest` is now `false`, so normal selection behavior is restored (choosing `c1`).
     - Verified eager history snapshot is reused without duplicate queries.
   - Added `consumption_invalidatedDuringSuspension_publishesFailedState`: tests that business day rollover during prefetch/consume invalidates the result and sets `nextSuggestionFailed = true` without genuine-empty success.
   - Added `validateAndConsumeNextCampaign_whenFresh_returnsCampaignId`: asserts valid snapshot returns target campaign ID.
   - Added `validateAndConsumeNextCampaign_whenDayOrMutationChanged_clearsStaleChoiceAndReturnsNull`: asserts that day rollover after selection clears stale ID, triggers refresh loading, and returns `null`.

5. **`app/src/test/java/com/ntt/crosstest/data/EgressFinalClientReviewTest.kt`**:
   - `legacyServerPayloadMissingStats_triggersBatchStatsFill_populatesCorrectly`: now passes with telemetry fix measuring exactly 2 HTTP requests (1 candidate RPC + 1 batch stats RPC).
   - Added `statsRepair_hasTestedTodayTrue_excludesCandidate_preservesRemainingCandidates`: verifies that candidates marked `has_tested_today = true` in batch stats repair are excluded, while candidates that have already reached daily targets remain eligible.
   - Added `statsRepair_missingCandidateOrStaleStatsDate_throwsErrorNotAssumedZero`: verifies that missing candidates in stats repair or stale `stats_date` throws `IllegalStateException` without fallback to Home or marking RPC capability unavailable.
   - Added `nullOrBlankId_withMissingFields_throwsErrorBeforeStatsRequest`: verifies that candidate payloads with blank/null campaign IDs throw `IllegalStateException` before any stats RPC is dispatched.
   - Added `statsRepair_concurrentStateChangeDuringRepair_throwsAndDoesNotPublish`: gates the stats response and tests session generation bump, business day rollover, and campaign mutation version change separately; verifies documented error contract, no fallback to Home, and no disabling of RPC capabilities.

---

## 3. Final Counts Verified by Controller — 11/11/2026

| Test Class | Previous Focused Counts | Final Executed Counts |
| :--- | :--- | :--- |
| `NextCampaignSelectorTest` | 15 passed | 15 passed |
| `CampaignDetailNextAppPriorityTest` | 10 passed | 13 passed (+3 new tests, 1 strengthened) |
| `EgressFinalClientReviewTest` | 7 passed, 1 failed | 12 passed (+4 tests, telemetry failure fixed) |
| **Total Focused** | **33 (1 fail)** | **40 passed (0 failures)** |

---

## 4. Unresolved Issues
- None in application or test code. All four requirements from controller/parent prompt are implemented with bounded waits and strict adherence to architectural contracts.
- Controller executed `.\gradlew.bat testDebugUnitTest assembleDebug --offline`: BUILD SUCCESSFUL. All 644 tests in 82 classes passed, with zero failures, errors or skips. The three focused classes above account for 40 of those tests. Evidence: `runs/reviewer-full-android-final.log` and `final-test-summary.json`.

