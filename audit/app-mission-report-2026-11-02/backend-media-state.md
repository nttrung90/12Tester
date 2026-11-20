# Backend Media State: Migration 074

**Session Model**: google/antigravity-gemini-3.8-flash (variant medium)  
**Date**: 2026-11-02  
**Target Scope**: `supabase/migrations/074_app_mission_window_and_multi_attachments.sql`  
**Status**: All 6 groups of `root-media-grant-probe.mjs` and all 25 original root tests (total 31 groups) pass 100%.

---

## 1. Defects Identified & Implemented Fixes

### Defect 1: Primary (null attachment) media grant 5-part HMAC verification failure & overload ambiguity (SQL 42725)
- **Root Cause**:
  1. `request_report_media_access` signed 6 colon-separated parts including an empty attachment string when `p_attachment_id` was NULL, whereas `verify_media_access_grant_internal` expects 5 colon-separated parts for legacy/primary grants.
  2. The 6th argument (`p_attachment_id uuid DEFAULT NULL::uuid`) in `verify_media_access_grant_internal` had a default, which caused PostgreSQL error 42725 (ambiguous function call) when invoked with 5 arguments against the legacy 5-argument overload.
- **Fix**:
  1. Updated `request_report_media_access` so that when `p_attachment_id` is NULL, it computes HMAC tokens using the legacy 5-part format `format('%s:%s:%s:%s:%s', p_report_id, v_user_id, v_role, variant, v_expires_epoch)`. When `p_attachment_id` is non-null, it computes HMAC using the 6-part format `format('%s:%s:%s:%s:%s:%s', p_report_id, v_att_part, v_user_id, v_role, variant, v_expires_epoch)`.
  2. Defined the 6-parameter `verify_media_access_grant_internal` without a default for `p_attachment_id`, and updated the 5-parameter overload to cleanly delegate to the 6-parameter implementation with `null::uuid`. This eliminates overload ambiguity completely.

### Defect 2: Android `MediaAccessGrantResult` DTO field mismatch
- **Root Cause**: `request_report_media_access` returned `signed_full_url`, `signed_thumbnail_url`, and `expires_epoch`. The Android client's `MediaAccessGrantResult` serializer expects `screenshot_url`, `thumbnail_url`, and `expires_at_epoch`. Without these fields, the client decoded null URLs and fell back to the unsigned gateway path.
- **Fix**: Added `screenshot_url`, `thumbnail_url`, and `expires_at_epoch` aliases to the returned JSON object while preserving `signed_full_url`, `signed_thumbnail_url`, and `expires_epoch` for backward compatibility.

---

## 2. Test Verification Matrix (31 Acceptance Groups)

| Test Script | Groups / Cases | Result |
|---|---|---|
| `root-media-grant-probe.mjs` | 6 groups | **PASS** |
| `root-three-image-probe.mjs` | 13 groups | **PASS** |
| `root-daily-coexistence-probe.mjs` | 2 groups | **PASS** |
| `root-daily-state-matrix.mjs` | 7 groups | **PASS** |
| `root-migration-upgrade-probe.mjs` | 3 groups | **PASS** |
| `run-app-mission-window-test.mjs` | 9 integration cases | **PASS** |
| `run-admin-expansion-integration-test.mjs` | 5 integration cases | **PASS** |
| `run-media-gateway-test.mjs` | 11 gateway cases | **PASS** |

Total root acceptance cases: **31 / 31 groups passing (100%)**.
