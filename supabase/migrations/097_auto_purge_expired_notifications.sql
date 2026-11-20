-- ============================================================================
-- Migration 097: Automated Purge of Expired Notifications & Related Ledgers
--
-- Retention policy:
-- 1. Admin notifications (lower(btrim(coalesce(type, ''))) = 'admin'):
--    60 days max retention (default p_admin_retention_days = 60).
-- 2. All other notifications (lower(btrim(coalesce(type, ''))) <> 'admin'):
--    7 days max retention (default p_other_retention_days = 7).
--    (notification_popup_states cascades automatically via ON DELETE CASCADE.)
-- 3. notification_campaign_recipients:
--    Rows belonging to finalized notification_campaigns (completed/cancelled/failed)
--    older than p_admin_retention_days (60 days) are purged while keeping parent
--    notification_campaigns summary rows (total_recipients, sent_count, failed_count).
-- 4. notification_popup_receipts:
--    Rows with claimed_at older than p_admin_retention_days (60 days), or
--    orphaned rows (notification_id IS NULL) older than p_other_retention_days (7 days).
-- 5. Automated execution via conditional pg_cron job + fallback inside
--    public.sync_campaign_lifecycle() if no successful run in the last 6 hours.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. SUPPORTING INDEXES FOR TIME-BASED CLEANUP SCANS
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_notifications_created_at
    ON public.notifications (created_at ASC);

CREATE INDEX IF NOT EXISTS idx_notification_popup_receipts_claimed_at
    ON public.notification_popup_receipts (claimed_at ASC);

-- ============================================================================
-- 2. RPC: public.purge_expired_notifications
-- ============================================================================

CREATE OR REPLACE FUNCTION public.purge_expired_notifications(
    p_other_retention_days integer DEFAULT 7,
    p_admin_retention_days integer DEFAULT 60,
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
    v_other_days integer := greatest(1, coalesce(p_other_retention_days, 7));
    v_admin_days integer := greatest(v_other_days, coalesce(p_admin_retention_days, 60));
    v_dry_run boolean := coalesce(p_dry_run, false);
    v_batch integer := greatest(1, least(coalesce(p_batch_size, 5000), 50000));
    v_other_cutoff timestamptz := v_now - make_interval(days => v_other_days);
    v_admin_cutoff timestamptz := v_now - make_interval(days => v_admin_days);
    v_run_id uuid;

    v_notif_ids uuid[] := '{}'::uuid[];
    v_recipient_ids uuid[] := '{}'::uuid[];
    v_receipt_ids uuid[] := '{}'::uuid[];

    v_scanned_admin_notifs integer := 0;
    v_scanned_other_notifs integer := 0;
    v_scanned_recipients integer := 0;
    v_scanned_receipts integer := 0;
    v_scanned integer := 0;

    v_deleted_admin_notifs integer := 0;
    v_deleted_other_notifs integer := 0;
    v_deleted_recipients integer := 0;
    v_deleted_receipts integer := 0;
    v_deleted integer := 0;

    v_status text;
    v_details jsonb;
begin
    perform pg_advisory_xact_lock(hashtextextended('purge_expired_notifications', 0));

    insert into public.cleanup_runs (job_name, status, started_at, details)
    values (
        'purge_expired_notifications',
        'running',
        v_now,
        jsonb_build_object(
            'other_retention_days', v_other_days,
            'admin_retention_days', v_admin_days,
            'batch_size', v_batch,
            'dry_run', v_dry_run
        )
    )
    returning id into v_run_id;

    begin
        -- 1. Select expired notifications oldest-first with SKIP LOCKED
        select
            coalesce(array_agg(t.id), '{}'::uuid[]),
            coalesce(count(*) filter (where t.is_admin), 0)::integer,
            coalesce(count(*) filter (where not t.is_admin), 0)::integer
        into
            v_notif_ids,
            v_scanned_admin_notifs,
            v_scanned_other_notifs
        from (
            select
                n.id,
                (lower(btrim(coalesce(n.type, ''))) = 'admin') as is_admin
            from public.notifications n
            where (
                lower(btrim(coalesce(n.type, ''))) = 'admin'
                and n.created_at < v_admin_cutoff
            )
            or (
                lower(btrim(coalesce(n.type, ''))) <> 'admin'
                and n.created_at < v_other_cutoff
            )
            order by n.created_at asc, n.id asc
            limit v_batch
            for update of n skip locked
        ) t;

        -- 2. Select expired campaign recipient detail rows oldest-first with SKIP LOCKED
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_recipient_ids
        from (
            select r.id
            from public.notification_campaign_recipients r
            join public.notification_campaigns c on c.id = r.campaign_id
            where c.status in ('completed', 'cancelled', 'failed')
              and coalesce(c.completed_at, c.cancelled_at, c.updated_at, c.created_at) < v_admin_cutoff
            order by coalesce(c.completed_at, c.cancelled_at, c.updated_at, c.created_at) asc, r.created_at asc, r.id asc
            limit v_batch
            for update of r skip locked
        ) t;

        v_scanned_recipients := coalesce(array_length(v_recipient_ids, 1), 0);

        -- 3. Select expired popup receipts oldest-first with SKIP LOCKED
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_receipt_ids
        from (
            select r.id
            from public.notification_popup_receipts r
            where r.claimed_at < v_admin_cutoff
               or (r.notification_id is null and r.claimed_at < v_other_cutoff)
            order by r.claimed_at asc, r.id asc
            limit v_batch
            for update of r skip locked
        ) t;

        v_scanned_receipts := coalesce(array_length(v_receipt_ids, 1), 0);
        v_scanned := v_scanned_admin_notifs + v_scanned_other_notifs + v_scanned_recipients + v_scanned_receipts;

        if v_dry_run then
            v_status := 'dry_run';
            v_details := jsonb_build_object(
                'other_retention_days', v_other_days,
                'admin_retention_days', v_admin_days,
                'batch_size', v_batch,
                'dry_run', true,
                'scanned_admin_notifications', v_scanned_admin_notifs,
                'scanned_other_notifications', v_scanned_other_notifs,
                'scanned_campaign_recipients', v_scanned_recipients,
                'scanned_popup_receipts', v_scanned_receipts,
                'deleted_admin_notifications', 0,
                'deleted_other_notifications', 0,
                'deleted_campaign_recipients', 0,
                'deleted_popup_receipts', 0
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

        if coalesce(array_length(v_notif_ids, 1), 0) > 0 then
            with deleted_notifs as (
                delete from public.notifications
                where id = any(v_notif_ids)
                returning (lower(btrim(coalesce(type, ''))) = 'admin') as is_admin
            )
            select
                coalesce(count(*) filter (where is_admin), 0)::integer,
                coalesce(count(*) filter (where not is_admin), 0)::integer
            into
                v_deleted_admin_notifs,
                v_deleted_other_notifs
            from deleted_notifs;
        end if;

        if coalesce(array_length(v_recipient_ids, 1), 0) > 0 then
            delete from public.notification_campaign_recipients
            where id = any(v_recipient_ids);
            get diagnostics v_deleted_recipients = row_count;
        end if;

        if coalesce(array_length(v_receipt_ids, 1), 0) > 0 then
            delete from public.notification_popup_receipts
            where id = any(v_receipt_ids);
            get diagnostics v_deleted_receipts = row_count;
        end if;

        v_deleted := v_deleted_admin_notifs + v_deleted_other_notifs + v_deleted_recipients + v_deleted_receipts;
        v_status := 'success';
        v_details := jsonb_build_object(
            'other_retention_days', v_other_days,
            'admin_retention_days', v_admin_days,
            'batch_size', v_batch,
            'dry_run', false,
            'scanned_admin_notifications', v_scanned_admin_notifs,
            'scanned_other_notifications', v_scanned_other_notifs,
            'scanned_campaign_recipients', v_scanned_recipients,
            'scanned_popup_receipts', v_scanned_receipts,
            'deleted_admin_notifications', v_deleted_admin_notifs,
            'deleted_other_notifications', v_deleted_other_notifs,
            'deleted_campaign_recipients', v_deleted_recipients,
            'deleted_popup_receipts', v_deleted_receipts
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

        raise warning 'purge_expired_notifications failed: %', sqlerrm;
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

REVOKE ALL ON FUNCTION public.purge_expired_notifications(integer, integer, boolean, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.purge_expired_notifications(integer, integer, boolean, integer) TO service_role;

-- ============================================================================
-- 3. UPDATE sync_campaign_lifecycle() WITH SAFE CLEANUP FALLBACK
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
                SELECT 1 FROM cron.job WHERE jobname = 'purge_expired_notifications_job'
            ) THEN
                PERFORM cron.schedule(
                    'purge_expired_notifications_job',
                    '25 */6 * * *',
                    'SELECT public.purge_expired_notifications(7, 60, false, 5000);'
                );
            END IF;
        END IF;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_cron conditional scheduling skipped (environment without pg_cron): %', SQLERRM;
END;
$cron_setup$;

COMMIT;
