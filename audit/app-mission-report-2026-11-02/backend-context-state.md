# Backend Context Validation State: Migration 074

**Session Model**: google/antigravity-gemini-3.8-flash (variant medium)  
**Date**: 2026-11-02  
**Target File**: `supabase/migrations/074_app_mission_window_and_multi_attachments.sql`  
**Status**: All 13 groups of root-three-image-probe and all other probes/tests pass individually (100%).

---

## 1. Issue Analysis

- **Defect**: In `_submit_tester_report_core`, `v_submission_context := coalesce(p_submission_context, case when p_mission_id is null then 'app_test' else 'mission' end);` allowed callers of `submit_tester_report_v2` and `submit_tester_report_v3` to supply `p_submission_context => NULL` alongside a valid `p_mission_id`, silently inferring caller intent instead of rejecting it.
- **Contract Requirement**: Deployed migration 064 and the explicit-context contract mandate that `p_submission_context` must be non-null and explicitly either `'app_test'` or `'mission'`, matching the presence/absence of `p_mission_id`. Silent intent inference is forbidden on explicit-context APIs (v2/v3).
- **Legacy Compatibility**: The legacy 8-argument `submit_tester_report` RPC wrapper derives context via `case when p_mission_id is null then 'app_test' else 'mission' end` and passes it explicitly to `_submit_tester_report_core`.

---

## 2. Changes Made in `074_app_mission_window_and_multi_attachments.sql`

1. **`_submit_tester_report_core`**:
   Replaced `coalesce(...)` inference with strict explicit check:
   ```sql
   if p_submission_context is null or p_submission_context not in ('app_test', 'mission')
      or (p_submission_context = 'mission') <> (p_mission_id is not null) then
       raise exception 'INVALID_REPORT_CONTEXT';
   end if;
   v_submission_context := p_submission_context;
   ```

2. **`submit_tester_report_v2`**:
   Added explicit context validation before delegating to core:
   ```sql
   if p_submission_context is null or p_submission_context not in ('app_test', 'mission')
      or (p_submission_context = 'mission') <> (p_mission_id is not null) then
       raise exception 'INVALID_REPORT_CONTEXT';
   end if;
   ```

3. **`submit_tester_report_v3`**:
   Added explicit context validation before delegating to core:
   ```sql
   if p_submission_context is null or p_submission_context not in ('app_test', 'mission')
      or (p_submission_context = 'mission') <> (p_mission_id is not null) then
       raise exception 'INVALID_REPORT_CONTEXT';
   end if;
   ```

4. **Legacy `submit_tester_report` wrapper**:
   Unchanged; passes derived `case when p_mission_id is null then 'app_test' else 'mission' end` to core, ensuring complete backward compatibility without breakage.

---

## 3. Individual Test Results

- `root-three-image-probe.mjs`: **PASS (13/13 groups)**
- `root-daily-coexistence-probe.mjs`: **PASS (2/2 orders)**
- `root-daily-state-matrix.mjs`: **PASS (6 matrix cases + later day)**
- `root-migration-upgrade-probe.mjs`: **PASS (1-day, 2-day historical upgrade + ACL)**
- `npm --prefix supabase/tests run test:mission-window`: **PASS (9/9 integration cases)**
- `node supabase/tests/run-admin-expansion-integration-test.mjs`: **PASS (5/5 admin cases)**
- `node supabase/tests/run-explicit-report-context-test.mjs`: **PASS (8/8 context cases)**
