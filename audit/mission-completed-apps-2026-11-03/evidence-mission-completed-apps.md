# Audit & Verification Evidence: Mission Creation & Reporting for Finalized Apps

**Date:** 2026-11-03  
**Target:** Finalized Apps (`production` and `completed`) eligibility for Missions  
**Auditor / Agent:** OpenCode (`antigravity-gemini-3.8-flash --variant medium`)  
**Working Directory:** `F:/AI/12Tester`  

---

## 1. Summary of Changes

### A. Android Client
1. **Model (`Campaign` in `Models.kt`):**
   - Added explicit property `isMissionEligible: Boolean get() = isOpen || isFinalized` (reusing existing `isFinalized: Boolean get() = isProduction || isCompleted`).
   - Ensures `open`, `production`, and `completed` apps are eligible for missions, while `paused`, `deleted`, `hidden`, and unsupported states remain excluded.

2. **Picker (`MissionCreateViewModel.kt`):**
   - Updated `loadInitialData()` to filter `allMyCampaigns.filter { it.isMissionEligible }` instead of `it.isOpen`.
   - Kept independent busy IDs endpoint `getMyActiveMissionCampaignIds()` to exclude campaigns already holding an active mission.
   - Preserved `allAppsBusy` logic: `eligibleCampaigns.isNotEmpty() && selectable.isEmpty()`.
   - Hardened `create()` preflight to verify that the selected campaign exists in `myCampaigns` and satisfies `isMissionEligible`.

3. **Picker UI (`MissionCreateScreen.kt`):**
   - Added category badge next to app title in the picker row reusing existing localized status labels: `stringResource(R.string.home_tab_completed)` for finalized apps and `stringResource(R.string.home_tab_need_test)` for open apps.
   - Kept entry point strictly in "App của tôi" (no extra FAB on public tabs).

4. **Mission Report Preflight & Links (`MissionReportViewModel.kt` & `MissionReportScreen.kt`):**
   - Replaced `!camp.isOpen` with `!camp.isMissionEligible` in `MissionReportViewModel.submit()` preflight so testers can submit mission reports for finalized apps.
   - Updated store fallback link in `MissionReportScreen.kt` to prioritize `camp.playUrl` when `camp.isProduction` is true.

5. **Owner App Detail Screen (`OwnerAppDetailScreen.kt`):**
   - Moved the mission management item (`mission_manage_title`) and load retry item outside the `if (!campaign.isFinalized || hasPendingPush)` gate, unblocking owners of finalized apps while strictly preserving owner-only access and the push/pause/production controls group.

6. **Localization (`res/values/`, `res/values-en/`, `res/values-vi/`):**
   - Updated `mission_create_app_label`, `mission_create_no_apps`, and `mission_create_all_apps_busy` in `vi`, `en`, and default strings to accurately reflect eligibility for both open and completed/production apps.

---

### B. Backend Migrations (`076_mission_for_finalized_campaigns.sql`)
1. **`create_mission(uuid, text, text, integer, integer, integer)`:**
   - Extended campaign status guard: `if v_camp.status not in ('open', 'production', 'completed') then raise exception 'CAMPAIGN_NOT_OPEN'; end if;`.
   - Preserved security definer, search_path, fee calculation (`50 * duration_days`), balance check (min 300 credits), single active mission slot lock/sync, and credit logging.
2. **`join_mission(uuid)`:**
   - Extended campaign status guard to allow `('open', 'production', 'completed')`.
   - Preserved participant capacity, creator restrictions, and notification logic.
3. **`resume_mission(uuid)`:**
   - Extended campaign status guard to allow `('open', 'production', 'completed')`.
   - Preserved row locking, expiry synchronization, and conflict checks.
4. **`_submit_tester_report_core(...)`:**
   - Restored EXACT 074 function body mechanically, with ONLY the context-aware campaign status gate modified near line 422:
     - When `v_submission_context = 'mission'`, allows `('open', 'production', 'completed')`.
     - When `submission_context = 'app_test'`, strictly retains `status = 'open'`.
   - Preserved all 074 multi-attachment update logic, retaining secondary attachments (positions 1 and 2) when a legacy v2 client updates position 0.
   - Ensured mission submissions do NOT touch `public.checkins` or grant ordinary test checkins/provisional rewards.
5. **`trg_reports_require_open_campaign()` Trigger:**
   - Hardened BEFORE INSERT trigger on `public.tester_reports`:
     - Allows `v_status = 'open'` unconditionally.
     - Strictly permits `v_status in ('production', 'completed')` ONLY when `app.submitting_report = 'true'` (transaction-local marker), `new.submission_context = 'mission'`, and both `new.mission_id` and `new.mission_participant_id` are not null.
     - All other cases, direct bypass inserts, or ordinary `app_test` submissions raise `CAMPAIGN_NOT_OPEN`.

---

## 2. Verification Evidence

### A. Independent Three-Image Contract Probe (`root-three-image-probe.mjs`)
Command: `node audit/app-mission-report-2026-11-02/root-three-image-probe.mjs`

```text
PASS independent three-image contract: create three ordered attachments and immutable exact replay
PASS independent three-image contract: four images rejected server-side
PASS independent three-image contract: client cannot move report_date to regain the daily slot
PASS independent three-image contract: v3 rejects missing client request identity
PASS independent three-image contract: v3 rejects null context instead of inferring intent from mission ID
PASS independent three-image contract: explicit v2 also rejects null context
PASS independent three-image contract: foreign secondary image URL rejected
PASS independent three-image contract: secondary hash format validated
PASS independent three-image contract: secondary thumbnail bound to its own full image
PASS independent three-image contract: empty array cannot silently mean keep attachments
PASS independent three-image contract: changed order under same request ID rejected
PASS independent three-image contract: v2 cover update either rejected or retains coherent entire set
PASS independent three-image contract: distinct valid URL fields cannot collide in a delimiter-based fingerprint
```
Result: 13/13 PASSED (100%).

---

### B. Local PGlite Migration & Flow Test (`run-mission-finalized-campaigns-test.mjs`)
Command: `node supabase/tests/run-mission-finalized-campaigns-test.mjs`

```text
=== RUNNING MISSION FOR FINALIZED CAMPAIGNS TESTS (MIGRATION 076) ===
Loading 15 migrations from 062 to 076...

--- Test 1: Production Campaign Mission Flow ---
PASS: Owner created mission for production campaign, fee correctly deducted
PASS: Campaign status remained "production"
PASS: Tester discovered production campaign mission via get_open_missions
PASS: Tester joined production mission
PASS: Tester submitted v3 report for production mission
PASS: No ordinary check-in created for finalized mission report
PASS: Owner approved production mission report
PASS: Production mission reward settled, campaign status remained "production"
PASS: Ordinary report (app_test) on production campaign strictly rejected

--- Test 2: Completed Campaign Mission Flow ---
PASS: Owner created mission for completed campaign
PASS: Campaign status remained "completed"
PASS: Tester joined completed campaign mission
PASS: Completed campaign mission report approved & settled, campaign status remained "completed"
PASS: Ordinary report (app_test) on completed campaign strictly rejected

--- Test 3: Open Campaign Regression Flow ---
PASS: Open campaign mission regression passed completely

--- Test 4: Exclusion of Paused & Unsupported States ---
PASS: create_mission rejected for paused campaign
PASS: join_mission rejected when campaign is paused
PASS: submit_tester_report_v3 rejected for mission on paused campaign

--- Test 5: Single Live Mission Slot & Busy IDs Endpoint ---
PASS: Exactly one live mission slot enforced per campaign; busy endpoint reports active IDs

--- Test 6: Mission Pause & Resume Regression on Finalized Campaign ---
PASS: Owner paused and resumed mission on completed campaign successfully
PASS: resume_mission rejected if campaign becomes paused

--- Test 7: Direct RPC Security & Validation ---
PASS: Security definer, auth, permissions, and invalid parameter guards enforced without debiting fees

--- Test 8: Trigger trg_reports_require_open_campaign Hardening ---
PASS: Direct INSERT attempts on finalized campaigns strictly blocked by triggers and RLS

=== ALL MISSION FOR FINALIZED CAMPAIGNS TESTS PASSED 100% ===
```
Result: 8/8 test suites PASSED (100%).

---

### C. Backend Regression Suites
- `run-app-mission-window-test.mjs`: 9/9 PASS (100%)
- `run-owner-auto-pause-test.mjs`: 6/6 PASS (100%)
- `run-mission-report-and-expiration-test.mjs`: 18/18 PASS (100%)
- `run-mission-flow-hardening.mjs`: ALL PASS (100%)
- `run-business-policies-test.mjs`: 21/21 PASS (100%)
- `run-admin-management-test.mjs`: ALL PASS (100%)

---

### D. Android Unit Tests (via `run-gradle-logged.ps1`)
1. **Compilation (`:app:compileDebugKotlin`):**
   - Status: `BUILD SUCCESSFUL` (0 compilation errors).
2. **`MissionRegressionTest` (Robolectric Unit Tests):**
   - Tests executed: 6 (0 failures, 0 skipped).
   - Validated `campaign mission eligibility for open, production, completed and exclusion of paused`.
   - Validated `MissionCreateViewModel picker selects open and finalized apps and excludes busy apps`.
   - Validated `MissionCreateViewModel create validation rejects non-eligible campaign`.
   - Validated `M01`, `M08`, `M09` regressions.
3. **`OwnerReportCountBehavioralTest`:**
   - Tests executed: 9 (0 failures, 0 skipped).
4. **`MissionReportMultiAttachmentTest`:**
   - Tests executed: 4 (0 failures, 0 skipped).
5. **`MissionReportRecheckProbeTest`:**
   - Tests executed: 4 (0 failures, 0 skipped).
6. **`ReportRpcFallbackAuditTest`:**
   - Status: `BUILD SUCCESSFUL`.

---

## 3. Scope Compliance Checklist
- [x] No APK/AAB builds, version edits, deploy/live SQL, commit/push, secret reads or dependency upgrades.
- [x] Preserved existing dirty changes.
- [x] Exact 074 `_submit_tester_report_core` body restored with only the context-aware campaign-status diff.
- [x] `root-three-image-probe.mjs` passes 13/13 with coherent secondary attachments preserved on v2 cover updates.
- [x] Finalized apps remain in original home tab and do not reopen ordinary testing.
- [x] Ordinary daily testing on finalized apps remains strictly prohibited.
- [x] `OwnerAppDetailScreen.kt` mission management accessible to finalized owners; push/pause/production group preserved.
- [x] `trg_reports_require_open_campaign` trigger narrowed to explicit mission context with transaction-local marker and non-null metadata.
- [x] All verification evidence recorded in `audit/mission-completed-apps-2026-11-03`.

Coordinator verification: final JUnit snapshot has 15 tests across 2 suites with zero failures, errors or skips. Compared effective SQL bodies against migrations 062/074 and confirmed the four RPC bodies differ only in the campaign-status gate. Local schema checks confirmed signatures, security mode, search paths and ACLs, including denial of direct anon/authenticated INSERT/UPDATE on tester_reports. Existing APK, Gradle version configuration and migrations 062–075 matched their recorded SHA-256 hashes. OpenCode metadata confirmed all 225 assistant entries used google/antigravity-gemini-3.8-flash/medium.
