# FINAL CLIENT DRAFT: Egress Optimization v2 Completion

> **Target Architecture:** Android Client (Kotlin / Jetpack Compose / Supabase PostgREST)  
> **Repository:** `F:/AI/12Tester`  
> **Author:** Gemini 3.8 Flash (High)  
> **Status:** Draft Prepared — Unexecuted (Awaiting Codex Allowlist Review)  
> **Baseline Checkpoint:** 603 passing Android tests, 0 failures/errors/skips, `assembleDebug` clean  

---

## 1. Executive Summary

This deliverable provides the final client-side implementation artifacts for the authorized Egress Optimization v2 task. The core baseline checkpoint remains completely preserved.

Per the task protocol:
- **No existing application source files have been modified in this turn.**
- **No scripts or build tools have been executed in this turn.**
- All source changes are strictly encapsulated inside the reviewed-to-be-applied Node script [`finish-client.mjs`](file:///F:/AI/12Tester/audit/egress-safe-2026-11-10/finish-client.mjs).
- New Robolectric behavioral tests are delivered in [`EgressFinalClientReviewTest.kt`](file:///F:/AI/12Tester/app/src/test/java/com/ntt/crosstest/data/EgressFinalClientReviewTest.kt).
- Execution will proceed only after Codex review and allowlisting.

---

## 2. Script Safety Guarantees (`finish-client.mjs`)

The Node script [`audit/egress-safe-2026-11-10/finish-client.mjs`](file:///F:/AI/12Tester/audit/egress-safe-2026-11-10/finish-client.mjs) implements strict defensive guards:

1. **Manifest Confinement:**
   - The script is restricted to the exact eight source files cataloged in [`after-core-hashes.json`](file:///F:/AI/12Tester/audit/egress-safe-2026-11-10/after-core-hashes.json).
2. **Pre-Write SHA256 Verification:**
   - Reads the raw bytes of all eight files and verifies their SHA256 checksums against `after-core-hashes.json` before performing **any** write.
   - Any checksum mismatch immediately aborts execution with zero disk writes.
3. **Workspace Path Containment:**
   - Target absolute paths are resolved and validated to reside strictly under `F:/AI/12Tester`. Outside paths are rejected.
4. **Zero Ambient Side-Effects:**
   - No `child_process` (no shell, git commands, or subprocesses).
   - No network calls (`http`, `https`, or `fetch`).
   - No file deletions (`unlink`, `rm`).
   - No writes to manifest or checkpoint files.
5. **Format & Encoding Preservation:**
   - UTF-8 encoding is strictly maintained.
   - Per-file line-ending styles (CRLF on Windows vs. LF) are dynamically detected and preserved.

---

## 3. Specification of Target Changes

### 3.1. Candidate RPC Client Correctness
- **Strict Error Classifier:** Candidate endpoint (`get_next_campaign_candidates_v2`) uses `EgressOptimizationConfig.isStrictMissingRpc` exclusively.
- **Error Propagation:** 401 Unauthorized, 403 Forbidden, 5xx Server Errors, network failures, and deserialization errors propagate immediately to the caller instead of silently falling back to Home feed or marking the RPC missing.
- **Clean Logging:** Raw exception objects, error bodies, and Supabase URLs are not printed to production logs.
- **Unchanged Universe & Ordering:** Returns the complete candidate list from the RPC without client-side top-N truncation. The ViewModel's `chooseNextCampaign` and `PendingReportStore` filtering continue to manage presentation-layer selection.
- **Scope Barrier:** Captures `startUid`, `startGen`, and `startDay` before launching the network call. If the session generation, authenticated user, or business day changes while in flight, the response is discarded (`emptyList()`).
- **Honest Decoded Bytes:** Decodes `response.data` once and records the actual UTF-8 byte length in telemetry (`wireBytesEstimate = null`).
- **Fallback Trigger:** When disabled or genuinely missing (PGRST202), the fallback call to `getHomeFeed` uses `triggerSource = EgressTriggerSource.PREFETCH`.

### 3.2. Legacy Missing-RPC Behavior Restoration
- **Removal of Overloaded Adapter:** Removed `isMissingRpc(error: Throwable, expectedFunctionName: String)` from `CampaignRepository.kt`.
- **Restoration of Legacy Classifier:** Restored `isMissingRpc(e: Exception, functionName: String? = null)` with legacy recognition semantics (PGRST202 code / message matching).
- **Strict Scope Separation:** New conditional RPCs (`get_home_feed_sync_v2`, `get_my_dashboard_sync_v2`, `get_campaign_detail_sync_v2`, `get_next_campaign_candidates_v2`) explicitly call `EgressOptimizationConfig.isStrictMissingRpc`. Generic `fetchLegacy` and legacy fallbacks use `isMissingRpc`.
- **Intact URL Protection:** Preserves `PostgrestRestException.error` checking in `isStrictMissingRpc`, avoiding false positives from requested URLs in `.message`.

### 3.3. Mutation Refresh Snapshot Retention & Freshness Decoupling
- **Architectural Distinction:**
  - *UI CacheEntry Freshness:* Determines whether the UI can immediately display in-memory data without a server trip. Local mutations invalidate this freshness (clearing `cachedHomeFeed`, `cachedDashboard`, `detailCache` and bumping mutation versions).
  - *Raw Server Snapshot Base (`ScopedSyncSnapshot`):* Represents the immutable state of the server at last contact. It is strictly a comparison base for sending `p_tokens` in conditional requests.
- **Retention Across Mutations:** Local invalidators (`invalidateCampaign`, `invalidateMembership`, `invalidateReportsAndStats`, `invalidateCampaignLists`, `invalidateDashboardCache`, `invalidateDetailCache`) no longer erase `homeFeedSnapshot`, `dashboardSnapshot`, or `detailSnapshots`.
- **Base Eligibility Criteria:** Relaxed base snapshot matching to check `sessionGeneration == gen && scopeUid == curUid && businessDate == requestDate` (removing the check for `mutationVersion == currentMutationVersion`). This allows the server to send delta blocks for changed items instead of full payloads after local mutations.
- **Publication Fences:** Request-start mutation versions (`mutationVer`) are captured before the RPC; post-response snapshot publication checks `mutationVer == getMutationVersion(domain)` to prevent race conditions.
- **Boundary Eviction:** Hard boundaries (`clearMemoryCache`, account switch, day change) drop all snapshots, preventing token-only state.
- **Detail Snapshot Retention:** Bounded via LRU-style map size limiting (`MAX_MAP_ENTRIES`).

### 3.4. Honest Telemetry Accounting
- **Actual HTTP Attempt Counting:** `totalRequests` tracks actual HTTP/RPC attempts via `noteNetworkAttempt`. Failed attempts, negative capability probes, repairs, and fallback legacy calls increment `totalRequests`.
- **Zero-Cost Cache Hits & Dedup:** Local cache hits (`LOCAL_CACHE_HIT`) and single-flight joins (`SINGLE_FLIGHT_DEDUP`) add **zero** to `totalRequests`.
- **Static RPC Labels:** Cache hits and in-flight joins use static string labels (`"get_home_feed_sync_v2"`, `"get_my_dashboard_sync_v2"`, `"get_campaign_detail_sync_v2"`), never dynamic keys containing user IDs or campaign IDs.
- **Full Repair Decoded Accounting:** When a protocol repair occurs, measured decoded bytes honestly include both the initial body and the repair body.
- **Unknown Wire Metrics:** Added `totalUnknownWireBytes` and `totalKnownWireBytesSamples` counters to distinguish unmeasured wire sizes from zero egress.
- **Zero PII:** Telemetry records contain only RPC name, sync type, trigger source, block counts, and byte counters; no JWTs, tokens, payloads, URLs, or IDs.

### 3.5. Real Trigger Propagation
- **Method Signatures:** Added optional `triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN` to:
  - `CampaignRepository.getHomeFeed`
  - `CampaignRepository.getMyDashboard`
  - `CampaignRepository.getCampaignDetailPage`
  - `CampaignRepository.getNextCampaignCandidates` (default: `PREFETCH`)
  - ViewModels: `HomeViewModel.refresh`, `ProfileViewModel.refresh`, `CampaignDetailViewModel.refresh`
- **Call-Site Labeling:**
  - `COLD_START`: Initial ViewModel `init` launches.
  - `RESUME`: Screen `LifecycleResumeEffect` triggers.
  - `PULL_REFRESH`: User pull-to-refresh gestures.
  - `MUTATION`: Handlers responding to completed report submissions.
  - `TAB_SWITCH`: Profile tab activation (`isActive`).
  - `MANUAL`: Refresh icon button clicks or error retry cards.
  - `PREFETCH`: Next campaign candidate queries.
- **Preserved Semantics:** Retained both Profile screen resume and active-tab refresh triggers; single-flight deduplication coalesces redundant in-flight network requests.

### 3.6. Capability Negative Caching & Expiration
- **Injectable Clock:** Tests inject `EgressOptimizationConfig.clock` to test deterministic time progression.
- **Bounded Negative Cache:** `markRpcUnavailable` sets a 5-minute timeout (`NEGATIVE_CAPABILITY_TTL_MS = 5 * 60 * 1000L`). Within this window, subsequent calls skip network probes and route directly to fallback.
- **Expiry Probing:** Once the clock advances past the TTL, `isRpcAvailable` automatically clears the negative entry, allowing a fresh probe.

---

## 4. Targeted Source Files (8 Files in Manifest)

| File Path | Component | Changes Made by `finish-client.mjs` |
| :--- | :--- | :--- |
| `app/src/main/java/com/ntt/crosstest/data/EgressOptimization.kt` | Telemetry & Config | Added `noteNetworkAttempt`, sample/unknown wire counters; zero-request accounting on cache hits |
| `app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt` | Repository | Strict candidate RPC, legacy `isMissingRpc` restore, snapshot retention across mutations, trigger propagation, honest decoded bytes, repair attempt counting |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt` | ViewModel | Propagates `triggerSource` through `refresh` to `getHomeFeed` & `getMyDashboard`; labels `COLD_START` and `MUTATION` |
| `app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt` | UI Screen | Labels `RESUME` in `LifecycleResumeEffect`, `PULL_REFRESH` on Discover and Quests tabs |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/ProfileViewModel.kt` | ViewModel | Propagates `triggerSource` through `refresh` to `getMyDashboard` |
| `app/src/main/java/com/ntt/crosstest/ui/screens/ProfileScreen.kt` | UI Screen | Labels `RESUME` in `LifecycleResumeEffect`, `TAB_SWITCH` on active tab, `PULL_REFRESH` on pull, `MANUAL` on retry card |
| `app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt` | ViewModel | Propagates `triggerSource` through `refresh` to `getCampaignDetailPage`; labels `COLD_START` and `MUTATION` |
| `app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt` | UI Screen | Labels `RESUME` in `LifecycleResumeEffect`, `MANUAL` on TopAppBar refresh button |

---

## 5. New Behavioral Tests (`EgressFinalClientReviewTest.kt`)

The new test suite in [`EgressFinalClientReviewTest.kt`](file:///F:/AI/12Tester/app/src/test/java/com/ntt/crosstest/data/EgressFinalClientReviewTest.kt) validates real runtime behavior with Ktor `MockEngine`, Supabase PostgREST, and Robolectric:

1. `fifteenCandidatesReturnedUnchanged_v2Only_noHomeRequest`:
   - Returns 15 candidates with complete metadata without truncation; invokes candidate RPC once with 0 Home requests; verifies `totalRequests == 1` and honest decoded byte accounting.
2. `candidateForbiddenServerErrorMalformed_doesNotSilentlyFallbackOrMarkMissing`:
   - Verifies 403 Forbidden, 500 Server Error, and malformed JSON propagate exceptions to the caller, never trigger Home fallback, and do not mark the RPC unavailable.
3. `genuinePgrst202OnCandidateEndpoint_legacyHomeFallback_negativeTtlCaches_expiryRetries`:
   - Verifies PGRST202 triggers legacy Home fallback; second call within negative TTL skips probe; clock advancement past TTL retries probe; observed `totalRequests` matches mock HTTP attempt count (5).
4. `candidateStaleGenerationOrScopeChange_rejectsResult`:
   - Verifies an in-flight candidate request that completes after a session generation bump (`clearMemoryCache`) is safely discarded (`emptyList()`).
5. `afterLocalReportInvalidation_freshnessInvalidated_newServerRequest_reusesTokens_changedStatsUpdate`:
   - Confirms that local mutation invalidation forces a new server request (UI freshness expired), but retains the raw token snapshot in `p_tokens` for delta updates; verifies `clearMemoryCache` fully purges tokens.
6. `concurrentEquivalentRefreshes_shareOneHttpRequest_singleFlightJoin_cacheHitAddsZeroHttpAttempts`:
   - Tests deterministic concurrency using `CompletableDeferred`; confirms single-flight coalescence to 1 HTTP attempt and verifies subsequent local cache hits add zero to `totalRequests`.
7. `explicitPrefetchResumeMutationMetadata_notPullRefresh_decodedBytesAccurate`:
   - Validates that `RESUME`, `PREFETCH`, and `MUTATION` triggers retain their true identity in telemetry without being overridden as `PULL_REFRESH`.

---

## 6. Verification and Execution Steps

When authorized, the rollout and verification proceed in three steps:

```powershell
# Step 1: Run the guarded client updater script
node audit/egress-safe-2026-11-10/finish-client.mjs

# Step 2: Run the newly added client behavioral review tests
./gradlew testDebugUnitTest --tests "com.ntt.crosstest.data.EgressFinalClientReviewTest"

# Step 3: Run the full test suite to verify all 603+ tests pass with zero regressions
./gradlew testDebugUnitTest

# Step 4: Verify complete debug assembly
./gradlew assembleDebug
```
