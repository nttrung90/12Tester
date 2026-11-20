-- Egress sync v2: reviewable rollout examples, not an automatic deployment.
-- All mutation examples are commented out. Copy/uncomment ONE selected phase.
-- Apply migration 085 and validate staging/API/device behavior before enabling.
-- Flags default false and affect all compatible APKs reading android_client.
-- These flags do not select user cohorts; staged APK distribution is separate.
-- Config reaches devices through existing AppConfigRepository load/restore and
-- explicit reloads (startup/manual check). Rollback is not an instant push.

-- Read-only pre-check; safe to execute as a complete file.
SELECT id,
       egress_sync_home_feed_v2_enabled,
       egress_sync_dashboard_v2_enabled,
       egress_sync_campaign_detail_v2_enabled,
       egress_candidates_v2_enabled,
       updated_at
FROM public.app_configs
WHERE id = 'android_client';

/* PHASE 1: Home feed only
BEGIN;
UPDATE public.app_configs
SET egress_sync_home_feed_v2_enabled = true, updated_at = now()
WHERE id = 'android_client'
RETURNING id, egress_sync_home_feed_v2_enabled;
COMMIT;
*/

/* PHASE 2: Dashboard, after phase 1 verification
BEGIN;
UPDATE public.app_configs
SET egress_sync_dashboard_v2_enabled = true, updated_at = now()
WHERE id = 'android_client'
RETURNING id, egress_sync_dashboard_v2_enabled;
COMMIT;
*/

/* PHASE 3: Campaign detail, after phase 2 verification
BEGIN;
UPDATE public.app_configs
SET egress_sync_campaign_detail_v2_enabled = true, updated_at = now()
WHERE id = 'android_client'
RETURNING id, egress_sync_campaign_detail_v2_enabled;
COMMIT;
*/

/* PHASE 4: Candidate suggestions, after phase 3 verification
BEGIN;
UPDATE public.app_configs
SET egress_candidates_v2_enabled = true, updated_at = now()
WHERE id = 'android_client'
RETURNING id, egress_candidates_v2_enabled;
COMMIT;
*/

/* ROLLBACK: disable all optimizations
BEGIN;
UPDATE public.app_configs
SET egress_sync_home_feed_v2_enabled = false,
    egress_sync_dashboard_v2_enabled = false,
    egress_sync_campaign_detail_v2_enabled = false,
    egress_candidates_v2_enabled = false,
    updated_at = now()
WHERE id = 'android_client'
RETURNING id,
          egress_sync_home_feed_v2_enabled,
          egress_sync_dashboard_v2_enabled,
          egress_sync_campaign_detail_v2_enabled,
          egress_candidates_v2_enabled;
COMMIT;
*/

-- Verify exactly one android_client row was updated in the selected example.
-- Repeat the read-only pre-check after the chosen transaction.
-- No PostgREST schema reload is needed for flag value updates; migration 085
-- already reloads schema after adding its columns/functions.
