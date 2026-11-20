# BACKEND REPAIR RESULT: Campaign Tester Bonus Rounds (Milestone 1)

> **Status:** SOURCE_STABLE  
> **Timestamp:** 2026-11-11T15:44:00+07:00  
> **Engineer:** Antigravity Backend Specialist  
> **Target Migration:** `supabase/migrations/087_campaign_tester_bonus_rounds.sql`  

---

## 1. Executive Summary of Concrete Repairs

All 8 technical defects identified by the parent review have been repaired directly in source files with zero modifications to legacy migrations (`001`–`086`), checkpoint files, or Android source files:

1. **Restored Baseline Report Core (`_submit_tester_report_core`):**
   - Full 1072-line baseline restored from `baseline-functions/_submit_tester_report_core_18210.sql`.
   - Fingerprint generation preserved with `v3|...` and attachments canonicalization.
   - Early receipt checking before campaign status check restored, fixing replay on completed campaigns.
   - Legacy receipt backfill (`legacy_backfill`) and idempotent fallback checks preserved.
   - Narrow bonus snapshot integration:
     - Variable `v_bonus_round_id uuid := null;`
     - TH1 (approved comment edit) preserves snapshot and returns `tester_bonus_round_id`.
     - TH2 (pending update / rejected resubmit) preserves `v_existing_report.tester_bonus_round_id` without stealing new slots.
     - TH3 (brand new report) locks `campaigns FOR KEY SHARE` and active round `FOR KEY SHARE`, setting `tester_bonus_round_id = v_bonus_round_id, tester_bonus_awarded = 0`.
   - Semantic diff written to `runs/report-core-review.diff`.

2. **Eliminated `40P01` Batch Worker Deadlock (`_process_closing_tester_bonus_rounds`):**
   - Batch loop now scans and locks `campaigns` first with `FOR UPDATE OF c SKIP LOCKED`, then selects and locks associated rounds under the campaign lock.
   - Follows strict global lock order: `Campaign -> Round`, matching admin deletion and lifecycle triggers.

3. **Durable Deferred Admin Deletion (Fixed `recipients = 1` Stranded Bug):**
   - Batch worker scans campaigns with `status = 'closed' AND paused_reason = 'admin_deletion_pending'`.
   - Once pending reports, bonus escrow, and mission escrow reach 0, worker durably deletes the campaign from `public.campaigns`.
   - Deletion reason `admin_note` is preserved until worker execution; financial audit ledgers and credit logs remain intact.
   - Admin delete wrapper emits `pending_removal` status without misleading immediate deletion claim.

4. **Safe Extension Resolution (`digest` in `extensions` schema):**
   - Added `'extensions'` to `SET search_path TO 'public', 'extensions', 'pg_catalog'` across all functions in migration 087.

5. **Concurrency Test Driver Fixes & Additions (`run-campaign-tester-bonus-concurrency-test.mjs`):**
   - Fixed assertion at line 223: `rd.remaining_slots` -> `rd.max_recipients - rd.paid_count`.
   - Added Scenario 5: Report submission vs. Close round race (snapshot linearization proof).
   - Added Scenario 6: Batch closing worker vs. Campaign lifecycle close race (40P01 elimination proof).
   - Added Scenario 7: Resubmit rejected report under active round (original snapshot preservation proof).

6. **Report Read Projection RPCs Extended:**
   - Added `get_campaign_detail`, `get_campaign_reports_for_owner`, and `admin_get_tester_reports_queue` to migration 087 before `COMMIT;`.
   - Return both `tester_bonus_awarded` (Int) and `tester_bonus_round_id` (UUID).
   - Search paths and permissions explicitly configured.

7. **Valid Large Bonus 64-Bit Arithmetic & Overflow Recovery:**
   - In `_impl_approve_tester_report`, response arithmetic uses 64-bit bigint (`total_earned = v_total_bonus::bigint + v_tester_bonus::bigint`), preventing error `22003` when valid large awards exceed 32-bit integer limits.
   - In `_close_and_refund_tester_bonus_round`, owner profile is locked `FOR UPDATE` before checking overflow. If `v_owner_balance + refund > 2147483647`, round transitions to `closing`, escrow is preserved, and retryable status `WALLET_BALANCE_OVERFLOW` is returned without aborting the batch worker.

8. **Coherent Documentation & Safe Financial Rollback:**
   - `API_CONTRACT.md`, `BACKEND_RESULT.md`, and `LOCK_ORDER.md` updated in both root and `audit/tester-bonus-2026-11-11/`.
   - Explicit safe financial rollback procedure documented: settle and refund all remaining escrow before any schema removal; no blanket dropping of financial tables.

---

## 2. Verification Test Execution Summary

### A. Actually Run In This Session (100% PASS)

| Test Script | Test Focus | Result | Notes |
| :--- | :--- | :--- | :--- |
| `node supabase/tests/run-campaign-tester-bonus-test.mjs` | Full behavioral, financial, RLS, projections, large bonus, overflow isolation & deferred removal | **PASS 16/16 (100%)** | All 16 comprehensive suites passed with zero errors |
| `node supabase/tests/run-app-mission-window-test.mjs` | Multi-attachment & mission window regressions | **PASS 9/9 (100%)** | Baseline submission & attachments intact |
| `node supabase/tests/run-mission-reward-modes-test.mjs` | Mission reward modes (`once`, `daily`), edits & admin deletion | **PASS 15/15 (100%)** | All mission flows verified |
| `node supabase/tests/run-carryover-fix-acceptance-test.mjs` | Carryover recovery, debt, and streak calculations | **PASS 14/14 (100%)** | Zero regression on carryover and daily completion |
| `node supabase/tests/run-egress-sync-v2-test.mjs` | Egress sync v2 token hashing & cache invalidation | **PASS 5/5 (100%)** | Parity maintained across all block types |
| `node supabase/tests/run-owner-auto-pause-test.mjs` | Owner auto-pause triggers and admin override | **PASS 6/6 (100%)** | All 6 pause paths verified |

---

### B. Gated for Parent Execution on Native PostgreSQL

Due to Windows CLI sandbox TCP socket loopback restrictions (`connect EACCES 127.0.0.1:55439`), real multi-connection testing was not executed from this CLI session. All code and test scripts are fully prepared.

#### Parent Execution Command:
```powershell
$env:TEST_DATABASE_URL="postgresql://bonus_test_admin@127.0.0.1:55439/tester_bonus_test"
node supabase/tests/run-campaign-tester-bonus-concurrency-test.mjs
```

#### Coverage of Concurrency Scenarios in Driver:
- **Scenario 1:** Concurrent wallet reservation across two apps of same owner (wallet contention -> no overdraft).
- **Scenario 2:** Last slot race between two testers (1 winner gets bonus, 1 gets base reward, no budget breach, verified `max_recipients - paid_count = 0`).
- **Scenario 3:** Manual approve vs. 8h auto-accept on the same report (exactly 1 bonus award, zero duplicate credits).
- **Scenario 4:** Close round vs. approve report race (lock order consistency, strict conservation).
- **Scenario 5:** Report submission vs. Close round race (linearized snapshot attachment with `FOR KEY SHARE`).
- **Scenario 6:** Batch closing worker vs. Campaign lifecycle close race (lock order Campaign -> Round, verifying 40P01 deadlock elimination).
- **Scenario 7:** Resubmit rejected report under active round (original snapshot preservation, zero slot theft).

---

## 3. Artifacts & Deliverables

- Main migration: [087_campaign_tester_bonus_rounds.sql](file:///F:/AI/12Tester/supabase/migrations/087_campaign_tester_bonus_rounds.sql)
- Semantic diff: [report-core-review.diff](file:///F:/AI/12Tester/runs/report-core-review.diff)
- Concurrency driver: [run-campaign-tester-bonus-concurrency-test.mjs](file:///F:/AI/12Tester/supabase/tests/run-campaign-tester-bonus-concurrency-test.mjs)
- Comprehensive behavioral test: [run-campaign-tester-bonus-test.mjs](file:///F:/AI/12Tester/supabase/tests/run-campaign-tester-bonus-test.mjs)
- API Contract: [API_CONTRACT.md](file:///F:/AI/12Tester/API_CONTRACT.md)
- Lock Order Documentation: [LOCK_ORDER.md](file:///F:/AI/12Tester/LOCK_ORDER.md)
- Backend Result Report: [BACKEND_RESULT.md](file:///F:/AI/12Tester/BACKEND_RESULT.md)
