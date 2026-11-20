# Notification Campaigns Backend Handover Report

**Session**: Task A — Admin Notification Campaigns Backend  
**Model**: `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Workspace**: `F:/AI/12Tester`  
**Date**: `2026-11-01`  
**Review Cycle**: Round 1 Review Fixes Applied & Verified  
**Status**: All confirmed defects fixed, 17/17 author tests pass, independent Codex probe passes  

---

## 1. Scope and Isolation Compliance

Per the concurrent task boundary rules specified in `audit/admin-expansion-2026-11-01/task-a-notifications.txt` and `audit/admin-expansion-2026-11-01/codex-notification-review-round1.md`:
- Active migrations in `supabase/migrations/` (including concurrent core session's `070_admin_management.sql` and business session's staged `071`) were **NOT modified**.
- `supabase/tests/package.json` was **NOT modified**.
- Android app code was **NOT modified** (Android integration is scheduled for Stage B).
- Migration `072_admin_notification_campaigns.sql` remains staged in `audit/admin-expansion-2026-11-01/notification-stage/` for Codex integration.
- Tests load active migrations `062` through `069` plus staged `072`, completely isolated from `070` and `071`.
- Existing `067`/`069` direct notification and popup flows remain untouched and 100% compatible.

---

## 2. Deliverables and File Artifacts

| File Path | Description |
|---|---|
| `audit/admin-expansion-2026-11-01/notification-api-contract.md` | API DTO contract, error codes, canonical JSON hashing, cursor formats, and integration points. |
| `audit/admin-expansion-2026-11-01/notification-stage/072_admin_notification_campaigns.sql` | Staged migration with RLS, tables, RPCs, worker, CAS, request advisory lock, and pg_cron conditional setup. |
| `backend/notification-worker/package.json` | Runnable service package definition with `@ntt/notification-worker` dependencies. |
| `backend/notification-worker/worker-runner.mjs` | Hardened background daemon with in-flight concurrency guards, completion-based scheduling, and sanitized logging. |
| `backend/notification-worker/README.md` | Deployment and execution runbook for standalone daemon / PM2 / Docker environments. |
| `audit/admin-expansion-2026-11-01/notification-stage/worker-runner.mjs` | Staged copy of the hardened runner script. |
| `supabase/tests/run-admin-notification-campaigns-test.mjs` | Comprehensive PGlite behavioral test suite (17 distinct test scenarios including all Codex regressions). |
| `audit/admin-expansion-2026-11-01/independent-notification-probe-results.json` | Updated results of the independent probe showing `payloadMismatchRejected: true`, `initialCount: 0`, and `afterCount: 0`. |
| `audit/admin-expansion-2026-11-01/notification-backend-handover.md` | This handover document. |

---

## 3. Confirmed Defects and Review Points Resolved

### 3.1 Defect 1: Delimiter-Based Payload Hash Collision
- **Problem**: Hashing via `md5(concat_ws('|', ...))` allowed collisions such as `title_vi='A|B', title_en='C'` vs `title_vi='A', title_en='B|C'`.
- **Fix**: Replaced all mutation payload fingerprints with canonical `jsonb` object hashing (`md5(jsonb_build_object(...)::text)`). Keys and values are escaped and ordered deterministically.
- **Request Advisory Locking**: Added `PERFORM pg_advisory_xact_lock(hashtext('notif_req:' || p_request_id::text))` before idempotency lookup. Concurrent requests with the same `request_id` wait and return the committed result without unique constraint violations.
- **Cross-Admin Safety**: Added check `IF v_existing_req.admin_id <> v_admin_id THEN RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already claimed by another administrator'; END IF;`.

### 3.2 Defect 2: Re-snapshotting Zero-Recipient Audiences
- **Problem**: Worker checked `IF v_camp.total_recipients = 0 THEN _notif_snapshot_recipients(...)`. When a campaign was scheduled for an empty role audience (e.g. 0 moderators), and a member later became a moderator, the worker re-snapshotted and delivered notifications instead of respecting the frozen count of 0.
- **Fix**: Added `recipient_snapshot_taken boolean NOT NULL DEFAULT false` and `snapshot_taken_at timestamptz` to `public.notification_campaigns`. Once scheduled or sent-now, `recipient_snapshot_taken` is permanently set to `true`. The worker only snapshots if `NOT v_camp.recipient_snapshot_taken`. If the frozen snapshot has 0 recipients, the worker marks the campaign `completed` with 0 sent and exits.

### 3.3 Scoped Review Points
- **Filter Whitelist Validation**: `admin_list_notification_campaigns` and `admin_list_notification_campaign_recipients` explicitly validate `p_status` and `p_audience_type` against valid enums and raise clear errors on unknown inputs.
- **Cursor Format Protection**: Keyset cursors validate format (`ISO8601|uuid`), reject missing delimiters, and reject malformed/`infinity`/`-infinity`/`NaN` timestamps.
- **Bounded Event Logs**: `admin_get_notification_campaign_detail` accepts optional `p_events_limit` (clamped to 1..100, default 50).
- **Read Count Semantics**: Contract updated to clarify that `read_count` reflects live read status among remaining/extant notifications in `public.notifications`. `sent_count` remains stable even after notification row deletion.
- **Service Worker Packaging**: Packaged standalone service under `backend/notification-worker/` with `package.json`, in-flight guard, completion-based `setTimeout` loop, and credential-scrubbed logging.
- **Scheduler Readiness**: `admin_get_notification_scheduler_health` exposes `scheduler_ready: boolean`, allowing the frontend to know whether pg_cron or daemon heartbeat is actively processing queues.

---

## 4. Verification Results

### 4.1 Independent Codex Probe (`independent-notification-probe.mjs`)
```
Command: node audit/admin-expansion-2026-11-01/independent-notification-probe.mjs
```
Output:
```json
[
  {
    "name": "different delimiter-containing payload must reject replay",
    "payloadMismatchRejected": true,
    "error": "REQUEST_ID_CONFLICT: Request ID already used with different payload"
  },
  {
    "name": "empty scheduled role audience must not grow when worker starts",
    "initialCount": 0,
    "afterCount": 0,
    "worker": {
      "details": [
        {
          "completed": true,
          "campaign_id": "17d08a52-11d6-4d20-a4b2-59b8a03f22f5",
          "sent_in_batch": 0,
          "pending_remaining": 0
        }
      ],
      "success": true,
      "notifications_sent": 0,
      "campaigns_processed": 1
    },
    "scheduledCount": 0
  }
]
```

### 4.2 Author Behavioral Test Suite (`run-admin-notification-campaigns-test.mjs`)
```
Command: node supabase/tests/run-admin-notification-campaigns-test.mjs
```
Output:
```
=== LOADING ACTIVE MIGRATIONS (062 THROUGH 069) EXCLUDING 070/071 ===
Applying migration 062_consolidated_baseline.sql...
Applying migration 063_report_crypto_search_path.sql...
Applying migration 064_explicit_report_context.sql...
Applying migration 065_remove_dhash_hardblock.sql...
Applying migration 066_push_and_carryover_hardening.sql...
Applying migration 067_admin_direct_notification_popup.sql...
Applying migration 068_owner_tester_reports_count.sql...
Applying migration 069_admin_audit_hardening.sql...
=== APPLYING STAGED 072 NOTIFICATION CAMPAIGNS MIGRATION ===
=== SEEDING BASE TEST PROFILES ===
=== RUNNING NOTIFICATION CAMPAIGNS BEHAVIORAL TESTS ===
PASS: Core tables and indexes created successfully
PASS: All admin RPCs enforce unlocked admin and deny member/moderator/locked/anon
PASS: Content boundaries, target_role, and selected_user_ids validation
PASS: Preview handles empty selection and role count accurately
PASS: Draft CAS conflict prevents stale updates
PASS: Future schedule/edit/cancel lifecycle and rejection of past/in-flight cancellation
PASS: Send-now scheduling and worker exactly-once fanout with idempotent restart
PASS: Mutation request ID replay succeeds idempotently and payload mismatch fails
Seeding 550 test members for scale batch testing...
PASS: Fanout worker processes 550+ recipients in batches and cursor paginates cleanly
PASS: Read count joined from notifications.is_read and no popup states registered
PASS: Deleted notification rows do not decrease sent_count
PASS: Internal helpers and direct table modifications are denied to client roles
PASS: Compatibility with existing direct notification popup registration and original broadcast
PASS: Admin scheduler health exposes status and background queue counts
PASS: Empty scheduled role audience freezes at 0 and does not grow when member changes role
PASS: Filter status whitelist and cursor malformed/infinity protection
PASS: Campaign detail bounds events list and supports events_limit
=== ALL ADMIN NOTIFICATION CAMPAIGNS TESTS PASSED ===
```

### 4.3 Existing Test Suite Regression Status
- `run-admin-direct-notification-popup-test.mjs`: **PASS (11/11)**
- `run-admin-audit-hardening-test.mjs`: **PASS (93/93)**

---

## 5. Codex Integration and Deployment Runbook

1. **Database Migration**:
   - Run `audit/admin-expansion-2026-11-01/notification-stage/072_admin_notification_campaigns.sql` in Supabase SQL editor as `postgres` or via migration runner.
   - If `pg_cron` is enabled, the cron job `process_due_notification_campaigns` is registered automatically.
2. **Scheduler Daemon (Fallback if pg_cron is disabled)**:
   - Deploy `backend/notification-worker`:
     ```bash
     cd backend/notification-worker
     npm install
     DATABASE_URL="postgres://..." npm start
     ```
3. **Common Audit Integration (`070_admin_management.sql`)**:
   - The notification campaigns module emits dedicated events in `public.notification_campaign_events`.
   - When 070 core management is merged, a trigger or wrapper hook can record matching entries in `public.admin_action_events` (`action = 'notification_campaign_create'|'schedule'|'cancel'`, `target = campaign_id`).
