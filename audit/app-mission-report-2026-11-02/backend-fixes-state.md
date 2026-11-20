# Backend Fixes State: Migration 074 App Mission Window & Multi-Attachments

**Session Model**: google/antigravity-gemini-3.8-flash (variant medium)  
**Date**: 2026-11-02  
**Status**: All 9 proven defects resolved. All 4 coordinator probes + test suites passing 100%.

---

## 1. Scope & Isolation Compliance

- **Exclusive Write Scope**: `supabase/migrations/074_app_mission_window_and_multi_attachments.sql`
- **Zero writes outside exclusive scope**:
  - No Android files touched
  - No `backend/media-gateway` files touched
  - No historical migrations (062..073) touched
  - No `root-*.mjs` probe files modified or weakened
  - No Gradle commands run
  - No live credentials or production DB accessed

---

## 2. Issues Investigated & Corrected

### Defect 1: `report_date` DEFAULT backfill corrupted historical rows
- **Root Cause**: `ALTER TABLE public.tester_reports ADD COLUMN IF NOT EXISTS report_date date DEFAULT ((now() at time zone 'Asia/Ho_Chi_Minh')::date);` backfilled every historical row with today's date in Postgres, making the subsequent `WHERE report_date IS NULL` a no-op and causing unique index violation 23505 on multiple historical days.
- **Fix**: Added column without default, backfilled from `(created_at at time zone 'Asia/Ho_Chi_Minh')::date WHERE report_date IS NULL`, then set default and `NOT NULL`.

### Defect 2: `_submit_tester_report_core` lacked private ACL
- **Root Cause**: `_submit_tester_report_core` had default execution grant to `PUBLIC`/`authenticated` under Supabase default grants.
- **Fix**: Added `REVOKE ALL ON FUNCTION public._submit_tester_report_core(...) FROM PUBLIC, anon, authenticated; GRANT EXECUTE ... TO service_role;`.

### Defect 3: `tester_report_attachments` inherited write grants
- **Root Cause**: `REVOKE ALL ON public.tester_report_attachments FROM anon, PUBLIC;` omitted `authenticated`, leaving inherited DML write grants active.
- **Fix**: Executed `REVOKE ALL ON public.tester_report_attachments FROM anon, authenticated, PUBLIC; GRANT SELECT ... TO authenticated; GRANT ALL ... TO service_role;`. Added check constraint `CHECK (image_hash IS NULL OR lower(image_hash) ~ '^[0-9a-f]{16}$')`.

### Defect 4: Missing v3 client request ID accepted
- **Root Cause**: `p_client_submit_id` defaulted to NULL and was not required in v2/v3 endpoints.
- **Fix**: Enforced `if p_client_submit_id is null then raise exception 'CLIENT_SUBMIT_ID_REQUIRED'; end if;` in `submit_tester_report_v2`, `submit_tester_report_v3`, and `_submit_tester_report_core` (when `p_attachments is not null`), preserving legacy `submit_tester_report` compatibility.

### Defect 5 & 6: Secondary attachment URLs, hashes, and thumbnail pairs unvalidated
- **Root Cause**: Validation only occurred via row triggers on primary screenshot fields in `tester_reports`. Secondary attachments in `p_attachments` were inserted into `tester_report_attachments` without checks.
- **Fix**: Enforced per-item validation across all attachments in `_submit_tester_report_core`:
  - `is_valid_report_screenshot_url(att->>'screenshot_url', p_campaign_id, v_user_id)`
  - `lower(att->>'image_hash') ~ '^[0-9a-f]{16}$'`
  - `is_valid_report_thumbnail_url(att->>'thumbnail_url', p_campaign_id, v_user_id, att->>'screenshot_url')`

### Defect 7: Empty attachment array `[]` accepted
- **Root Cause**: `if v_att_count > 0` bypassed checks for `v_att_count = 0`, retaining existing attachments silently.
- **Fix**: Enforced `if v_att_count < 1 then raise exception 'ATTACHMENTS_EMPTY...'; end if;` and `if v_att_count > 3 then raise exception 'TOO_MANY_ATTACHMENTS'; end if;`.

### Defect 8: Legacy v2 cover update incoherent on multi-image report
- **Root Cause**: When updating an existing report with `v_new_screenshot` via v2, `tester_reports.screenshot_url` was updated but `tester_report_attachments` was ignored when `p_attachments` was NULL.
- **Fix**: Added synchronization of position 0 in `tester_report_attachments` with new cover URL/hash and cleared `sha256 = null`, while fully preserving positions 1 and 2.

### Defect 9: Fingerprint delimiter collision
- **Root Cause**: `v_att_canonical` used `:` delimiter concatenation, allowing `full + ':' + thumb` vs `thumb + ':' + thumb` to produce identical fingerprints.
- **Fix**: Generated `v_att_canonical` via `jsonb_agg` ordered by position, guaranteeing distinct and tamper-proof fingerprints.

### Defect 10: Admin queue overload ambiguity
- **Verification**: Verified `admin_get_tester_reports_queue` retains its published 8-parameter signature with full backward compatibility and attachment inclusion.

---

## 3. Verification Commands & Exit Status

| Command | Result |
|---|---|
| `node audit/app-mission-report-2026-11-02/root-daily-coexistence-probe.mjs` | **PASS** (both orders) |
| `node audit/app-mission-report-2026-11-02/root-daily-state-matrix.mjs` | **PASS** (all 6 matrix cases + later day) |
| `node audit/app-mission-report-2026-11-02/root-migration-upgrade-probe.mjs` | **PASS** (1-day, 2-day historical upgrade + ACL) |
| `node audit/app-mission-report-2026-11-02/root-three-image-probe.mjs` | **PASS** (all 11 probe tests) |
| `npm --prefix supabase/tests run test:mission-window` | **PASS** (all 9 integration cases) |
| `node supabase/tests/run-admin-expansion-integration-test.mjs` | **PASS** (all 5 admin cases) |
