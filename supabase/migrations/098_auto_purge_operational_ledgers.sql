-- ============================================================================
-- Migration 098: Automated Purge of Expired Operational Ledgers & Receipts
--
-- Retention policy (defaults):
-- 1. report_submission_receipts (16 days, p_receipt_retention_days = 16):
--    Purge when created_at < cutoff AND linked report is not 'pending'.
-- 2. report_cancelled_requests (16 days, p_receipt_retention_days = 16):
--    Purge when created_at < cutoff (composite PK: user_id, client_submit_id).
-- 3. admin_operation_requests (30 days, p_operation_retention_days = 30):
--    Purge when created_at < cutoff (composite PK: actor_id, operation, id).
-- 4. business_policy_admin_requests (30 days, p_operation_retention_days = 30):
--    Purge when created_at < cutoff (composite PK: actor_id, operation, id).
-- 5. notification_campaign_requests (30 days, p_operation_retention_days = 30):
--    Purge when created_at < cutoff AND (campaign_id IS NULL OR linked campaign
--    is finalized: completed / cancelled / failed).
-- 6. app_update_operations (30 days, p_operation_retention_days = 30):
--    Purge when created_at < cutoff AND (release_id IS NULL OR linked release
--    is finalized: exhausted / superseded / closed).
-- 7. report_media_uploads (16 days, p_media_retention_days = 16):
--    Lock storage_organization_usage (id = 1) FOR UPDATE first (091..093 order).
--    Require reserved_bytes = 0 AND quota_accounted_bytes = 0 AND:
--    - provider = 'supabase': status = 'deleted', main_committed = true,
--      (thumbnail_object_path IS NULL OR thumbnail_committed = true),
--      coalesce(deleted_at, updated_at, created_at) < cutoff, and no objects
--      remaining in storage.objects.
--    - provider = 'gcs': created_at < cutoff AND linked report is not 'pending'.
-- 8. cleanup_runs (30 days, p_cleanup_log_retention_days = 30):
--    Purge when id <> v_run_id AND status <> 'running'
--    AND coalesce(finished_at, started_at) < cutoff.
-- 9. user_quest_claims (30 days, p_quest_retention_days = 30):
--    Purge when period_key < ((v_now at time zone 'Asia/Ho_Chi_Minh')::date - days).
-- 10. contact_reports (60 days, p_contact_retention_days = 60):
--    Purge when status IN ('resolved', 'replied')
--    AND coalesce(replied_at, created_at) < cutoff.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. SUPPORTING INDEXES FOR TIME-BASED CLEANUP SCANS
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_report_submission_receipts_created_at
    ON public.report_submission_receipts (created_at ASC);

CREATE INDEX IF NOT EXISTS idx_report_cancelled_requests_created_at
    ON public.report_cancelled_requests (created_at ASC);

-- ============================================================================
-- 2. RPC: public.purge_expired_operational_data
-- ============================================================================

CREATE OR REPLACE FUNCTION public.purge_expired_operational_data(
    p_receipt_retention_days integer DEFAULT 16,
    p_operation_retention_days integer DEFAULT 30,
    p_media_retention_days integer DEFAULT 16,
    p_cleanup_log_retention_days integer DEFAULT 30,
    p_quest_retention_days integer DEFAULT 30,
    p_contact_retention_days integer DEFAULT 60,
    p_dry_run boolean DEFAULT false,
    p_batch_size integer DEFAULT 5000
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_now timestamptz := clock_timestamp();
    v_receipt_days integer := greatest(1, coalesce(p_receipt_retention_days, 16));
    v_operation_days integer := greatest(1, coalesce(p_operation_retention_days, 30));
    v_media_days integer := greatest(1, coalesce(p_media_retention_days, 16));
    v_cleanup_log_days integer := greatest(1, coalesce(p_cleanup_log_retention_days, 30));
    v_quest_days integer := greatest(1, coalesce(p_quest_retention_days, 30));
    v_contact_days integer := greatest(1, coalesce(p_contact_retention_days, 60));
    v_dry_run boolean := coalesce(p_dry_run, false);
    v_batch integer := greatest(1, least(coalesce(p_batch_size, 5000), 50000));

    v_receipt_cutoff timestamptz := v_now - make_interval(days => v_receipt_days);
    v_operation_cutoff timestamptz := v_now - make_interval(days => v_operation_days);
    v_media_cutoff timestamptz := v_now - make_interval(days => v_media_days);
    v_cleanup_log_cutoff timestamptz := v_now - make_interval(days => v_cleanup_log_days);
    v_quest_cutoff date := ((v_now at time zone 'Asia/Ho_Chi_Minh')::date - v_quest_days);
    v_contact_cutoff timestamptz := v_now - make_interval(days => v_contact_days);

    v_run_id uuid;

    v_receipt_ids uuid[] := '{}'::uuid[];
    v_cancelled_ctids tid[] := '{}'::tid[];
    v_admin_op_ctids tid[] := '{}'::tid[];
    v_policy_op_ctids tid[] := '{}'::tid[];
    v_notif_req_ids uuid[] := '{}'::uuid[];
    v_app_update_op_ids text[] := '{}'::text[];
    v_media_ids uuid[] := '{}'::uuid[];
    v_cleanup_run_ids uuid[] := '{}'::uuid[];
    v_quest_claim_ids uuid[] := '{}'::uuid[];
    v_contact_report_ids uuid[] := '{}'::uuid[];

    v_scanned_receipts integer := 0;
    v_scanned_cancelled integer := 0;
    v_scanned_admin_ops integer := 0;
    v_scanned_policy_ops integer := 0;
    v_scanned_notif_reqs integer := 0;
    v_scanned_app_update_ops integer := 0;
    v_scanned_media integer := 0;
    v_scanned_cleanup_runs integer := 0;
    v_scanned_quest_claims integer := 0;
    v_scanned_contact_reports integer := 0;
    v_scanned integer := 0;

    v_deleted_receipts integer := 0;
    v_deleted_cancelled integer := 0;
    v_deleted_admin_ops integer := 0;
    v_deleted_policy_ops integer := 0;
    v_deleted_notif_reqs integer := 0;
    v_deleted_app_update_ops integer := 0;
    v_deleted_media integer := 0;
    v_deleted_cleanup_runs integer := 0;
    v_deleted_quest_claims integer := 0;
    v_deleted_contact_reports integer := 0;
    v_deleted integer := 0;

    v_status text;
    v_details jsonb;
begin
    perform pg_advisory_xact_lock(hashtextextended('purge_expired_operational_data', 0));

    insert into public.cleanup_runs (job_name, status, started_at, details)
    values (
        'purge_expired_operational_data',
        'running',
        v_now,
        jsonb_build_object(
            'receipt_retention_days', v_receipt_days,
            'operation_retention_days', v_operation_days,
            'media_retention_days', v_media_days,
            'cleanup_log_retention_days', v_cleanup_log_days,
            'quest_retention_days', v_quest_days,
            'contact_retention_days', v_contact_days,
            'batch_size', v_batch,
            'dry_run', v_dry_run
        )
    )
    returning id into v_run_id;

    begin
        -- Follow dual-storage lock order (091..093) before touching report_media_uploads or receipts.
        perform 1 from public.storage_organization_usage where id = 1 for update;

        -- 1. report_submission_receipts (16d, non-pending report)
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_receipt_ids
        from (
            select r.id
            from public.report_submission_receipts r
            where r.created_at < v_receipt_cutoff
              and (
                  r.report_id is null
                  or not exists (
                      select 1
                      from public.tester_reports tr
                      where tr.id = r.report_id
                        and tr.status = 'pending'
                  )
              )
            order by r.created_at asc, r.id asc
            limit v_batch
            for update of r skip locked
        ) t;
        v_scanned_receipts := coalesce(array_length(v_receipt_ids, 1), 0);

        -- 2. report_cancelled_requests (16d, composite PK: user_id, client_submit_id)
        select coalesce(array_agg(t.row_ctid), '{}'::tid[])
        into v_cancelled_ctids
        from (
            select r.ctid as row_ctid
            from public.report_cancelled_requests r
            where r.created_at < v_receipt_cutoff
            order by r.created_at asc, r.user_id asc, r.client_submit_id asc
            limit v_batch
            for update of r skip locked
        ) t;
        v_scanned_cancelled := coalesce(array_length(v_cancelled_ctids, 1), 0);

        -- 3. admin_operation_requests (30d, composite PK: actor_id, operation, id)
        select coalesce(array_agg(t.row_ctid), '{}'::tid[])
        into v_admin_op_ctids
        from (
            select r.ctid as row_ctid
            from public.admin_operation_requests r
            where r.created_at < v_operation_cutoff
            order by r.created_at asc, r.actor_id asc, r.operation asc, r.id asc
            limit v_batch
            for update of r skip locked
        ) t;
        v_scanned_admin_ops := coalesce(array_length(v_admin_op_ctids, 1), 0);

        -- 4. business_policy_admin_requests (30d, composite PK: actor_id, operation, id)
        select coalesce(array_agg(t.row_ctid), '{}'::tid[])
        into v_policy_op_ctids
        from (
            select r.ctid as row_ctid
            from public.business_policy_admin_requests r
            where r.created_at < v_operation_cutoff
            order by r.created_at asc, r.actor_id asc, r.operation asc, r.id asc
            limit v_batch
            for update of r skip locked
        ) t;
        v_scanned_policy_ops := coalesce(array_length(v_policy_op_ctids, 1), 0);

        -- 5. notification_campaign_requests (30d, finalized campaign or null)
        select coalesce(array_agg(t.request_id), '{}'::uuid[])
        into v_notif_req_ids
        from (
            select r.request_id
            from public.notification_campaign_requests r
            where r.created_at < v_operation_cutoff
              and (
                  r.campaign_id is null
                  or exists (
                      select 1
                      from public.notification_campaigns c
                      where c.id = r.campaign_id
                        and c.status in ('completed', 'cancelled', 'failed')
                  )
              )
            order by r.created_at asc, r.request_id asc
            limit v_batch
            for update of r skip locked
        ) t;
        v_scanned_notif_reqs := coalesce(array_length(v_notif_req_ids, 1), 0);

        -- 6. app_update_operations (30d, finalized release or null)
        select coalesce(array_agg(t.operation_id), '{}'::text[])
        into v_app_update_op_ids
        from (
            select r.operation_id
            from public.app_update_operations r
            where r.created_at < v_operation_cutoff
              and (
                  r.release_id is null
                  or exists (
                      select 1
                      from public.app_update_releases rel
                      where rel.id = r.release_id
                        and rel.status in ('exhausted', 'superseded', 'closed')
                  )
              )
            order by r.created_at asc, r.operation_id asc
            limit v_batch
            for update of r skip locked
        ) t;
        v_scanned_app_update_ops := coalesce(array_length(v_app_update_op_ids, 1), 0);

        -- 7. report_media_uploads (16d, storage_organization_usage already locked FOR UPDATE)
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_media_ids
        from (
            select m.id
            from public.report_media_uploads m
            where m.reserved_bytes = 0
              and m.quota_accounted_bytes = 0
              and (
                  (
                      m.provider = 'supabase'
                      and m.status = 'deleted'
                      and m.main_committed = true
                      and (m.thumbnail_object_path is null or m.thumbnail_committed = true)
                      and coalesce(m.deleted_at, m.updated_at, m.created_at) < v_media_cutoff
                      and not exists (
                          select 1
                          from storage.objects o
                          where o.bucket_id = m.bucket
                            and o.name in (m.object_path, m.thumbnail_object_path)
                      )
                  )
                  or
                  (
                      m.provider = 'gcs'
                      and m.created_at < v_media_cutoff
                      and (
                          m.report_id is null
                          or not exists (
                              select 1
                              from public.tester_reports tr
                              where tr.id = m.report_id
                                and tr.status = 'pending'
                          )
                      )
                  )
              )
            order by coalesce(m.deleted_at, m.created_at, m.updated_at) asc, m.id asc
            limit v_batch
            for update of m skip locked
        ) t;
        v_scanned_media := coalesce(array_length(v_media_ids, 1), 0);

        -- 8. cleanup_runs (30d, exclude self and running rows)
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_cleanup_run_ids
        from (
            select cr.id
            from public.cleanup_runs cr
            where cr.id <> v_run_id
              and cr.status <> 'running'
              and coalesce(cr.finished_at, cr.started_at) < v_cleanup_log_cutoff
            order by coalesce(cr.finished_at, cr.started_at) asc, cr.id asc
            limit v_batch
            for update of cr skip locked
        ) t;
        v_scanned_cleanup_runs := coalesce(array_length(v_cleanup_run_ids, 1), 0);

        -- 9. user_quest_claims (30d based on Asia/Ho_Chi_Minh date)
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_quest_claim_ids
        from (
            select q.id
            from public.user_quest_claims q
            where q.period_key < v_quest_cutoff
            order by q.period_key asc, q.claimed_at asc, q.id asc
            limit v_batch
            for update of q skip locked
        ) t;
        v_scanned_quest_claims := coalesce(array_length(v_quest_claim_ids, 1), 0);

        -- 10. contact_reports (60d, resolved or replied only)
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_contact_report_ids
        from (
            select c.id
            from public.contact_reports c
            where c.status in ('resolved', 'replied')
              and coalesce(c.replied_at, c.created_at) < v_contact_cutoff
            order by coalesce(c.replied_at, c.created_at) asc, c.id asc
            limit v_batch
            for update of c skip locked
        ) t;
        v_scanned_contact_reports := coalesce(array_length(v_contact_report_ids, 1), 0);

        v_scanned :=
            v_scanned_receipts
            + v_scanned_cancelled
            + v_scanned_admin_ops
            + v_scanned_policy_ops
            + v_scanned_notif_reqs
            + v_scanned_app_update_ops
            + v_scanned_media
            + v_scanned_cleanup_runs
            + v_scanned_quest_claims
            + v_scanned_contact_reports;

        if v_dry_run then
            v_status := 'dry_run';
            v_details := jsonb_build_object(
                'receipt_retention_days', v_receipt_days,
                'operation_retention_days', v_operation_days,
                'media_retention_days', v_media_days,
                'cleanup_log_retention_days', v_cleanup_log_days,
                'quest_retention_days', v_quest_days,
                'contact_retention_days', v_contact_days,
                'batch_size', v_batch,
                'dry_run', true,
                'scanned_report_submission_receipts', v_scanned_receipts,
                'scanned_report_cancelled_requests', v_scanned_cancelled,
                'scanned_admin_operation_requests', v_scanned_admin_ops,
                'scanned_business_policy_admin_requests', v_scanned_policy_ops,
                'scanned_notification_campaign_requests', v_scanned_notif_reqs,
                'scanned_app_update_operations', v_scanned_app_update_ops,
                'scanned_report_media_uploads', v_scanned_media,
                'scanned_cleanup_runs', v_scanned_cleanup_runs,
                'scanned_user_quest_claims', v_scanned_quest_claims,
                'scanned_contact_reports', v_scanned_contact_reports,
                'deleted_report_submission_receipts', 0,
                'deleted_report_cancelled_requests', 0,
                'deleted_admin_operation_requests', 0,
                'deleted_business_policy_admin_requests', 0,
                'deleted_notification_campaign_requests', 0,
                'deleted_app_update_operations', 0,
                'deleted_report_media_uploads', 0,
                'deleted_cleanup_runs', 0,
                'deleted_user_quest_claims', 0,
                'deleted_contact_reports', 0
            );

            update public.cleanup_runs
            set finished_at = clock_timestamp(),
                status = v_status,
                scanned = v_scanned,
                deleted = 0,
                details = v_details
            where id = v_run_id;

            return jsonb_build_object(
                'success', true,
                'run_id', v_run_id,
                'status', v_status,
                'scanned', v_scanned,
                'deleted', 0
            ) || v_details;
        end if;

        if v_scanned_receipts > 0 then
            delete from public.report_submission_receipts
            where id = any(v_receipt_ids);
            get diagnostics v_deleted_receipts = row_count;
        end if;

        if v_scanned_cancelled > 0 then
            delete from public.report_cancelled_requests
            where ctid = any(v_cancelled_ctids);
            get diagnostics v_deleted_cancelled = row_count;
        end if;

        if v_scanned_admin_ops > 0 then
            delete from public.admin_operation_requests
            where ctid = any(v_admin_op_ctids);
            get diagnostics v_deleted_admin_ops = row_count;
        end if;

        if v_scanned_policy_ops > 0 then
            delete from public.business_policy_admin_requests
            where ctid = any(v_policy_op_ctids);
            get diagnostics v_deleted_policy_ops = row_count;
        end if;

        if v_scanned_notif_reqs > 0 then
            delete from public.notification_campaign_requests
            where request_id = any(v_notif_req_ids);
            get diagnostics v_deleted_notif_reqs = row_count;
        end if;

        if v_scanned_app_update_ops > 0 then
            delete from public.app_update_operations
            where operation_id = any(v_app_update_op_ids);
            get diagnostics v_deleted_app_update_ops = row_count;
        end if;

        if v_scanned_media > 0 then
            delete from public.report_media_uploads
            where id = any(v_media_ids);
            get diagnostics v_deleted_media = row_count;
        end if;

        if v_scanned_cleanup_runs > 0 then
            delete from public.cleanup_runs
            where id = any(v_cleanup_run_ids);
            get diagnostics v_deleted_cleanup_runs = row_count;
        end if;

        if v_scanned_quest_claims > 0 then
            delete from public.user_quest_claims
            where id = any(v_quest_claim_ids);
            get diagnostics v_deleted_quest_claims = row_count;
        end if;

        if v_scanned_contact_reports > 0 then
            delete from public.contact_reports
            where id = any(v_contact_report_ids);
            get diagnostics v_deleted_contact_reports = row_count;
        end if;

        v_deleted :=
            v_deleted_receipts
            + v_deleted_cancelled
            + v_deleted_admin_ops
            + v_deleted_policy_ops
            + v_deleted_notif_reqs
            + v_deleted_app_update_ops
            + v_deleted_media
            + v_deleted_cleanup_runs
            + v_deleted_quest_claims
            + v_deleted_contact_reports;

        v_status := 'success';
        v_details := jsonb_build_object(
            'receipt_retention_days', v_receipt_days,
            'operation_retention_days', v_operation_days,
            'media_retention_days', v_media_days,
            'cleanup_log_retention_days', v_cleanup_log_days,
            'quest_retention_days', v_quest_days,
            'contact_retention_days', v_contact_days,
            'batch_size', v_batch,
            'dry_run', false,
            'scanned_report_submission_receipts', v_scanned_receipts,
            'scanned_report_cancelled_requests', v_scanned_cancelled,
            'scanned_admin_operation_requests', v_scanned_admin_ops,
            'scanned_business_policy_admin_requests', v_scanned_policy_ops,
            'scanned_notification_campaign_requests', v_scanned_notif_reqs,
            'scanned_app_update_operations', v_scanned_app_update_ops,
            'scanned_report_media_uploads', v_scanned_media,
            'scanned_cleanup_runs', v_scanned_cleanup_runs,
            'scanned_user_quest_claims', v_scanned_quest_claims,
            'scanned_contact_reports', v_scanned_contact_reports,
            'deleted_report_submission_receipts', v_deleted_receipts,
            'deleted_report_cancelled_requests', v_deleted_cancelled,
            'deleted_admin_operation_requests', v_deleted_admin_ops,
            'deleted_business_policy_admin_requests', v_deleted_policy_ops,
            'deleted_notification_campaign_requests', v_deleted_notif_reqs,
            'deleted_app_update_operations', v_deleted_app_update_ops,
            'deleted_report_media_uploads', v_deleted_media,
            'deleted_cleanup_runs', v_deleted_cleanup_runs,
            'deleted_user_quest_claims', v_deleted_quest_claims,
            'deleted_contact_reports', v_deleted_contact_reports
        );

        update public.cleanup_runs
        set finished_at = clock_timestamp(),
            status = v_status,
            scanned = v_scanned,
            deleted = v_deleted,
            details = v_details
        where id = v_run_id;

        return jsonb_build_object(
            'success', true,
            'run_id', v_run_id,
            'status', v_status,
            'scanned', v_scanned,
            'deleted', v_deleted
        ) || v_details;
    exception when others then
        update public.cleanup_runs
        set finished_at = clock_timestamp(),
            status = 'failed',
            scanned = v_scanned,
            deleted = 0,
            error = left(sqlerrm, 2000)
        where id = v_run_id;

        raise warning 'purge_expired_operational_data failed: %', sqlerrm;
        return jsonb_build_object(
            'success', false,
            'run_id', v_run_id,
            'status', 'failed',
            'scanned', v_scanned,
            'deleted', 0,
            'error', left(sqlerrm, 2000)
        );
    end;
end;
$function$;

REVOKE ALL ON FUNCTION public.purge_expired_operational_data(integer, integer, integer, integer, integer, integer, boolean, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.purge_expired_operational_data(integer, integer, integer, integer, integer, integer, boolean, integer) TO service_role;

-- ============================================================================
-- 3. UPDATE sync_campaign_lifecycle() WITH 6H NOTIFICATION + 24H OPERATIONAL FALLBACKS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.sync_campaign_lifecycle()
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_completed int := 0;
    v_paused int := 0;
    v_slot_paused int := 0;
begin
    v_completed := public.complete_expired_campaigns();
    v_paused := public.pause_inactive_owner_campaigns();
    -- Trước đây hàm này bị mồ côi: app có slot hết hạn không bao giờ tự tạm dừng.
    v_slot_paused := public.pause_expired_slot_campaigns();

    begin
        if not exists (
            select 1
            from public.cleanup_runs cr
            where cr.job_name = 'purge_expired_notifications'
              and cr.status = 'success'
              and cr.started_at > clock_timestamp() - interval '6 hours'
        ) then
            perform public.purge_expired_notifications(7, 60, false, 1000);
        end if;
    exception when others then
        null;
    end;

    begin
        if not exists (
            select 1
            from public.cleanup_runs cr
            where cr.job_name = 'purge_expired_operational_data'
              and cr.status = 'success'
              and cr.started_at > clock_timestamp() - interval '24 hours'
        ) then
            perform public.purge_expired_operational_data(16, 30, 16, 30, 30, 60, false, 1000);
        end if;
    exception when others then
        null;
    end;

    return jsonb_build_object(
        'completed', v_completed,
        'paused', v_paused,
        'slot_paused', v_slot_paused
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.sync_campaign_lifecycle() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.sync_campaign_lifecycle() TO PUBLIC, anon, authenticated, service_role;

-- ============================================================================
-- 4. CONDITIONAL PG_CRON REGISTRATION WITH SAFE FALLBACK
-- ============================================================================

DO $cron_setup$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'pg_cron'
    ) OR EXISTS (
        SELECT 1 FROM pg_available_extensions WHERE name = 'pg_cron'
    ) THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS pg_cron;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'pg_cron extension creation skipped: %', SQLERRM;
        END;

        IF to_regclass('cron.job') IS NOT NULL THEN
            IF NOT EXISTS (
                SELECT 1 FROM cron.job WHERE jobname = 'purge_expired_operational_data_job'
            ) THEN
                PERFORM cron.schedule(
                    'purge_expired_operational_data_job',
                    '35 3 * * *',
                    'SELECT public.purge_expired_operational_data(16, 30, 16, 30, 30, 60, false, 5000);'
                );
            END IF;
        END IF;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_cron conditional scheduling skipped (environment without pg_cron): %', SQLERRM;
END;
$cron_setup$;

COMMIT;
