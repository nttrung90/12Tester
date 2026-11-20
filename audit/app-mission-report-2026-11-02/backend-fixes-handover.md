# Backend Fixes Handover: Migration 074 Corrections

**Model**: google/antigravity-gemini-3.8-flash (variant medium)  
**Date**: 2026-11-02  
**Target Scope**: `supabase/migrations/074_app_mission_window_and_multi_attachments.sql`  
**Overall Status**: **ALL IMMUTABLE ROOT PROBES & MIGRATION TESTS PASS (100%)**

---

## 1. Summary of Corrected Behaviors

1. **Non-destructive Historical Report Date Backfill**:
   - `report_date` column is added without `DEFAULT`.
   - Existing rows backfill from `(created_at at time zone 'Asia/Ho_Chi_Minh')::date WHERE report_date IS NULL`.
   - After backfilling, column sets `DEFAULT ((now() at time zone 'Asia/Ho_Chi_Minh')::date)` and `NOT NULL`.
   - Unique indexes `idx_tester_reports_daily_app_test` and `idx_tester_reports_daily_mission` succeed without 23505 errors regardless of historical multi-day volume.

2. **Strict Private ACL on Core Submission Helper**:
   - `_submit_tester_report_core` explicitly revokes execute privileges from `PUBLIC`, `anon`, and `authenticated`.
   - Only `service_role` has execution rights. Direct client invocation fails with permission denied.

3. **RPC-Only Attachment Write Privileges**:
   - `tester_report_attachments` revokes all privileges from `anon`, `authenticated`, and `PUBLIC`.
   - Authenticated users receive `SELECT` privilege only (protected by RLS). Direct table `INSERT`, `UPDATE`, `DELETE` are disallowed.
   - Added `CHECK (image_hash IS NULL OR lower(image_hash) ~ '^[0-9a-f]{16}$')` constraint.

4. **Mandatory Client Request ID for v2 and v3**:
   - `submit_tester_report_v2` and `submit_tester_report_v3` strictly require `p_client_submit_id` (`CLIENT_SUBMIT_ID_REQUIRED`).
   - `_submit_tester_report_core` also guards against missing client submit IDs on multi-attachment calls.
   - Legacy 8-parameter `submit_tester_report` retains nullable compatibility.

5. **Canonical Validation of All Secondary Attachments**:
   - Every attachment item in `p_attachments` is verified via `is_valid_report_screenshot_url` against allowlisted buckets, report path patterns, and caller ownership.
   - Every attachment item's `image_hash` is verified against 16-hex dHash format (`lower(hash) ~ '^[0-9a-f]{16}$'`).
   - Every attachment item's thumbnail is validated via `is_valid_report_thumbnail_url`, binding the thumbnail to its specific full image.

6. **Rejection of Empty Attachment Arrays (`[]`)**:
   - `p_attachments` requires length 1..3. Passing `[]` raises `ATTACHMENTS_EMPTY`, disallowing ambiguous empty array retention.

7. **Coherent Legacy v2 Cover Synchronization**:
   - When a multi-image report receives a v2 single-screenshot update, position 0 in `tester_report_attachments` is synchronized to the new screenshot URL, thumbnail, and dHash, while clearing `sha256 = null`.
   - Secondary attachments (positions 1 and 2) are completely preserved. Primary report and position 0 match exactly.

8. **Collision-Proof JSON Fingerprint**:
   - Canonical attachment fingerprint generation uses ordered `jsonb_agg` objects instead of delimiter concatenation, preventing URL spoofing collisions.

9. **Preserved Admin Queue Signature**:
   - `admin_get_tester_reports_queue` preserves the 8-parameter signature with cursor pagination, policy/media metadata, and full attachment lists.

---

## 2. Exact Execution Logs

### A. Coordinator Root Probes

```
> node audit/app-mission-report-2026-11-02/root-daily-coexistence-probe.mjs
PASS independent daily coexistence: app_test -> mission
PASS independent daily coexistence: mission -> app_test

> node audit/app-mission-report-2026-11-02/root-daily-state-matrix.mjs
PASS independent daily state matrix: app_test -> mission with first pending
PASS independent daily state matrix: app_test -> mission with first approved
PASS independent daily state matrix: app_test -> mission with first rejected
PASS independent daily state matrix: mission -> app_test with first pending
PASS independent daily state matrix: mission -> app_test with first approved
PASS independent daily state matrix: mission -> app_test with first rejected
PASS independent later-day mission report after actual owner approval

> node audit/app-mission-report-2026-11-02/root-migration-upgrade-probe.mjs
PASS independent migration upgrade: preserves 1 historical reporting days
PASS independent migration upgrade: preserves 2 historical reporting days
PASS independent private-helper and attachment write ACL

> node audit/app-mission-report-2026-11-02/root-three-image-probe.mjs
PASS independent three-image contract: create three ordered attachments and immutable exact replay
PASS independent three-image contract: four images rejected server-side
PASS independent three-image contract: client cannot move report_date to regain the daily slot
PASS independent three-image contract: v3 rejects missing client request identity
PASS independent three-image contract: foreign secondary image URL rejected
PASS independent three-image contract: secondary hash format validated
PASS independent three-image contract: secondary thumbnail bound to its own full image
PASS independent three-image contract: empty array cannot silently mean keep attachments
PASS independent three-image contract: changed order under same request ID rejected
PASS independent three-image contract: v2 cover update either rejected or retains coherent entire set
PASS independent three-image contract: distinct valid URL fields cannot collide in a delimiter-based fingerprint
```

### B. Project Regression Suites

```
> npm --prefix supabase/tests run test:mission-window
=== RUNNING APP MISSION WINDOW & MULTI-ATTACHMENT TESTS (MIGRATION 074) ===
PASS: 1. submit_tester_report_v3 successfully submits 3 attachments
PASS: 2. >3 attachments is rejected with TOO_MANY_ATTACHMENTS
PASS: 3. Idempotent replay returns identical response with 3 attachments
PASS: 4. Ordinary app_test succeeds independently on same day after mission report
PASS: 5. 2 distinct reports coexist in database for same user, same campaign, same day
PASS: 6. Owner approved day 1 mission report
PASS: 7. Day 2 mission report succeeds independently after day 1 approval
PASS: 8. get_campaign_detail returns attachments list with all 3 items
PASS: 9. get_campaign_reports_for_owner includes mission metadata and 3 attachments
=== ALL APP MISSION WINDOW & MULTI-ATTACHMENT TESTS PASSED 100% ===

> node supabase/tests/run-admin-expansion-integration-test.mjs
APPLIED: 062_consolidated_baseline.sql
APPLIED: 063_report_crypto_search_path.sql
APPLIED: 064_explicit_report_context.sql
APPLIED: 065_remove_dhash_hardblock.sql
APPLIED: 066_push_and_carryover_hardening.sql
APPLIED: 067_admin_direct_notification_popup.sql
APPLIED: 068_owner_tester_reports_count.sql
APPLIED: 069_admin_audit_hardening.sql
APPLIED: 070_admin_management.sql
APPLIED: 071_business_policies.sql
APPLIED: 072_admin_notification_campaigns.sql
APPLIED: 073_admin_action_audit.sql
APPLIED: 074_app_mission_window_and_multi_attachments.sql
PASS: Admin-owner review preserves071 snapshot bonuses and owner-mode audit semantics
PASS: Non-owner staff review retains snapshot and emits one common audit event
PASS: Staff rejection retains071 exact provisional clawback and common audit
PASS: Final queue combines070 media expiry and071 reward snapshots
PASS: Final-chain private helpers and public policy metadata remain restricted
ALL ADMIN EXPANSION INTEGRATION TESTS PASSED
```

---

## 3. Scope Verification & Remaining Limitations

- **Files Modified**: Exactly 1 file:
  `supabase/migrations/074_app_mission_window_and_multi_attachments.sql`
- **Documentation Added**:
  - `audit/app-mission-report-2026-11-02/backend-fixes-state.md`
  - `audit/app-mission-report-2026-11-02/backend-fixes-handover.md`
- **Remaining Limitations**:
  - Tests run in local PGlite environment simulating Postgres 15/16; does not test physical multi-process socket concurrency or connection pooling edge cases.
  - Android client-side integration is managed concurrently by the coordinator/primary session.
