-- ============================================================================
-- Migration 099: Automated Purge of Zero-Credit Accounts (30 Continuous Days)
--
-- Policy & design:
-- 1. Track continuous zero-credit state via public.profiles.zero_credits_since:
--    - Automatically maintained by BEFORE INSERT OR UPDATE trigger
--      public.trg_fn_profiles_track_zero_credits():
--      * When credits > 0: zero_credits_since is ALWAYS NULL.
--      * When credits <= 0 on INSERT or transition from > 0 (or NULL):
--        zero_credits_since is set to coalesce(NEW.zero_credits_since, clock_timestamp()).
--      * When credits <= 0 on UPDATE and OLD.credits <= 0 with existing timestamp:
--        preserves OLD.zero_credits_since (unless an explicit non-null timestamp update is supplied).
--    - Historical backfill (Option A) via public.backfill_profiles_zero_credits_since():
--      Traces public.credit_logs to find the earliest balance <= 0 entry after the
--      account's last balance > 0 entry (falling back to last positive entry,
--      profile created_at, or clock_timestamp()).
-- 2. Eligibility & safety protections in public.purge_zero_credit_accounts:
--    - Account must have credits <= 0 and zero_credits_since <= cutoff (default 30 days).
--    - Excludes admin / moderator roles and any account in public.admin_capabilities.
--    - Defense-in-depth check on public.credit_logs: no entry with balance > 0
--      or amount > 0 after the cutoff timestamp.
--    - Excludes accounts with positive unsettled escrow in:
--      * public.missions (escrow_remaining_credits > 0 and not escrow_refunded)
--      * public.campaign_tester_bonus_rounds (status in ('active', 'closing') and remaining_credits > 0)
--      * public.app_update_releases (status = 'active' and remaining_credits > 0)
-- 3. Full account & profile data removal:
--    - Safely handles dual-storage report_media_uploads ledger rows (deleting zero-byte/GCS
--      rows and expiring/detaching active Supabase Storage rows for physical worker cleanup).
--    - Removes dependent rows in NO ACTION FK tables (campaign_tester_bonus_*, missions,
--      app_update_*, former-admin audit references if any).
--    - Deletes user's campaigns, auth.users row (cascading auth.identities, auth.sessions,
--      auth.refresh_tokens, public.profiles, and 30+ child tables), and public.profiles row.
--    - Cleans up non-FK journal/archive/receipt tables (daily_test_events_journal,
--      campaign_status_journal, user_role_journal, campaign_archive,
--      campaign_removal_receipts, campaign_push_receipts, user_daily_tasks,
--      report_cancelled_requests, report_submission_receipts) after AFTER DELETE triggers fire.
-- 4. Re-authentication behavior after purge:
--    - Google OAuth sign-in: GoTrue creates a brand-new auth.users record (new UUID),
--      triggering on_auth_user_created -> handle_new_user() to create a fresh profile
--      with welcome_credits.
--    - Manual Email/Password account: old credentials no longer exist; user must register
--      a new account (can reuse the same email) to receive a fresh profile.
-- 5. Automated execution via conditional pg_cron job ('45 3 * * *') + 24-hour fallback
--    inside public.sync_campaign_lifecycle().
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. SCHEMA: zero_credits_since COLUMN, TRIGGER, BACKFILL (OPTION A) & INDEX
-- ============================================================================

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS zero_credits_since timestamptz NULL;

CREATE OR REPLACE FUNCTION public.trg_fn_profiles_track_zero_credits()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
begin
    if coalesce(new.credits, 0) > 0 then
        new.zero_credits_since := null;
        return new;
    end if;

    -- new.credits <= 0
    if tg_op = 'INSERT' then
        new.zero_credits_since := coalesce(new.zero_credits_since, clock_timestamp());
        return new;
    end if;

    -- tg_op = 'UPDATE' and new.credits <= 0
    if coalesce(old.credits, 0) > 0 or old.zero_credits_since is null then
        if new.zero_credits_since is not null and new.zero_credits_since is distinct from old.zero_credits_since then
            return new;
        end if;
        new.zero_credits_since := clock_timestamp();
    else
        -- old.credits <= 0 and old.zero_credits_since is not null
        if new.zero_credits_since is not null and new.zero_credits_since is distinct from old.zero_credits_since then
            return new;
        end if;
        new.zero_credits_since := old.zero_credits_since;
    end if;

    return new;
end;
$function$;

REVOKE ALL ON FUNCTION public.trg_fn_profiles_track_zero_credits() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.trg_fn_profiles_track_zero_credits() TO service_role;

DROP TRIGGER IF EXISTS trg_profiles_track_zero_credits ON public.profiles;
CREATE TRIGGER trg_profiles_track_zero_credits
    BEFORE INSERT OR UPDATE ON public.profiles
    FOR EACH ROW
    EXECUTE FUNCTION public.trg_fn_profiles_track_zero_credits();

-- Helper function for Option A historical backfill from public.credit_logs
CREATE OR REPLACE FUNCTION public.backfill_profiles_zero_credits_since()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_updated integer := 0;
begin
    with last_positive as (
        select
            cl.user_id,
            max(cl.created_at) as last_pos_at
        from public.credit_logs cl
        where cl.balance > 0
        group by cl.user_id
    ),
    first_zero_after_positive as (
        select
            cl.user_id,
            min(cl.created_at) as zero_since
        from public.credit_logs cl
        left join last_positive lp on lp.user_id = cl.user_id
        where cl.balance <= 0
          and (lp.last_pos_at is null or cl.created_at > lp.last_pos_at)
        group by cl.user_id
    ),
    computed as (
        select
            p.id,
            case
                when coalesce(p.credits, 0) > 0 then null::timestamptz
                else coalesce(fz.zero_since, lp.last_pos_at, p.created_at, clock_timestamp())
            end as target_since
        from public.profiles p
        left join first_zero_after_positive fz on fz.user_id = p.id
        left join last_positive lp on lp.user_id = p.id
    )
    update public.profiles p
    set zero_credits_since = c.target_since
    from computed c
    where p.id = c.id
      and p.zero_credits_since is distinct from c.target_since;

    get diagnostics v_updated = row_count;
    return v_updated;
end;
$function$;

REVOKE ALL ON FUNCTION public.backfill_profiles_zero_credits_since() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.backfill_profiles_zero_credits_since() TO service_role;

-- Execute Option A backfill immediately on migration apply
SELECT public.backfill_profiles_zero_credits_since();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.profiles'::regclass
          AND conname = 'chk_profiles_zero_credits_since'
    ) THEN
        ALTER TABLE public.profiles
            ADD CONSTRAINT chk_profiles_zero_credits_since
            CHECK (
                (credits > 0 AND zero_credits_since IS NULL)
                OR (credits <= 0 AND zero_credits_since IS NOT NULL)
            );
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_profiles_zero_credits_since
    ON public.profiles (zero_credits_since ASC, id ASC)
    WHERE credits <= 0 AND zero_credits_since IS NOT NULL;

-- Allow ON DELETE SET NULL on home_stats_policies(created_by, updated_by) when a former admin account is deleted
CREATE OR REPLACE FUNCTION public._check_home_stats_policy_immutability()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_current_vn date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if tg_op = 'UPDATE' then
        if new.id is not distinct from old.id
           and new.effective_from is not distinct from old.effective_from
           and new.cycle_days is not distinct from old.cycle_days
           and new.created_at is not distinct from old.created_at
           and (new.created_by is null or new.created_by is not distinct from old.created_by)
           and (new.updated_by is null or new.updated_by is not distinct from old.updated_by)
           and (new.created_by is distinct from old.created_by or new.updated_by is distinct from old.updated_by)
        then
            return new;
        end if;

        if old.effective_from <= v_current_vn then
            raise exception 'ACTIVE_POLICY_IMMUTABLE: Cannot modify an active or past policy (effective_from: %)', old.effective_from;
        end if;
    end if;
    return new;
end;
$function$;


-- ============================================================================
-- 2. RPC: public.purge_zero_credit_accounts
-- ============================================================================

CREATE OR REPLACE FUNCTION public.purge_zero_credit_accounts(
    p_zero_credit_days integer DEFAULT 30,
    p_dry_run boolean DEFAULT false,
    p_batch_size integer DEFAULT 200
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'auth', 'pg_catalog'
AS $function$
declare
    v_now timestamptz := clock_timestamp();
    v_days integer := greatest(1, coalesce(p_zero_credit_days, 30));
    v_dry_run boolean := coalesce(p_dry_run, false);
    v_batch integer := greatest(1, least(coalesce(p_batch_size, 200), 5000));
    v_cutoff timestamptz := v_now - make_interval(days => v_days);
    v_run_id uuid;

    v_candidate_ids uuid[] := '{}'::uuid[];
    v_deleted_ids uuid[] := '{}'::uuid[];
    v_user_id uuid;
    v_prof record;

    v_scanned integer := 0;
    v_deleted integer := 0;
    v_deleted_campaigns integer := 0;
    v_deleted_media_rows integer := 0;
    v_expired_media_rows integer := 0;
    v_row_count integer := 0;
    v_auth_deleted integer := 0;
    v_prof_deleted integer := 0;

    v_errors jsonb := '[]'::jsonb;
    v_status text;
    v_details jsonb;
begin
    perform pg_advisory_xact_lock(hashtextextended('purge_zero_credit_accounts', 0));

    insert into public.cleanup_runs (job_name, status, started_at, details)
    values (
        'purge_zero_credit_accounts',
        'running',
        v_now,
        jsonb_build_object(
            'zero_credit_days', v_days,
            'cutoff', v_cutoff,
            'batch_size', v_batch,
            'dry_run', v_dry_run
        )
    )
    returning id into v_run_id;

    begin
        -- 1. Select eligible 0-credit accounts oldest-first with SKIP LOCKED
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_candidate_ids
        from (
            select p.id
            from public.profiles p
            where p.credits <= 0
              and p.zero_credits_since is not null
              and p.zero_credits_since <= v_cutoff
              -- Protect admin and moderator accounts
              and lower(btrim(coalesce(p.role, 'member'))) not in ('admin', 'moderator')
              and not exists (
                  select 1
                  from public.admin_capabilities ac
                  where ac.admin_id = p.id
              )
              -- Defense-in-depth: no positive balance or credit gain within the retention window
              and not exists (
                  select 1
                  from public.credit_logs cl
                  where cl.user_id = p.id
                    and (cl.balance > 0 or cl.amount > 0)
                    and cl.created_at > v_cutoff
              )
              -- Protect accounts with positive unsettled mission escrow
              and not exists (
                  select 1
                  from public.missions m
                  left join public.campaigns mc on mc.id = m.campaign_id
                  where (m.creator_id = p.id or mc.owner_id = p.id)
                    and m.escrow_remaining_credits > 0
                    and not coalesce(m.escrow_refunded, false)
              )
              -- Protect accounts with positive unsettled tester bonus rounds
              and not exists (
                  select 1
                  from public.campaign_tester_bonus_rounds br
                  left join public.campaigns bc on bc.id = br.campaign_id
                  where (br.owner_id = p.id or bc.owner_id = p.id)
                    and br.status in ('active', 'closing')
                    and br.remaining_credits > 0
              )
              -- Protect accounts with positive unsettled app update releases
              and not exists (
                  select 1
                  from public.app_update_releases ar
                  left join public.campaigns ac on ac.id = ar.campaign_id
                  where (ar.owner_id = p.id or ar.owner_id_snapshot = p.id or ac.owner_id = p.id)
                    and ar.status = 'active'
                    and ar.remaining_credits > 0
              )
              -- Anti-starvation backoff for accounts that failed in recent cleanup runs
              and not exists (
                  select 1
                  from (
                      select count(*) as fail_count, max(cr.started_at) as last_failed_at
                      from public.cleanup_runs cr,
                           jsonb_array_elements(coalesce(cr.details -> 'errors', '[]'::jsonb)) as err
                      where cr.job_name = 'purge_zero_credit_accounts'
                        and (err ->> 'user_id') = p.id::text
                        and cr.started_at > v_now - interval '30 days'
                  ) f
                  where f.fail_count > 0
                    and f.last_failed_at > v_now - make_interval(days => least(14, power(2, f.fail_count)::int))
              )
            order by
                (case when exists (
                    select 1
                    from public.cleanup_runs cr,
                         jsonb_array_elements(coalesce(cr.details -> 'errors', '[]'::jsonb)) as err
                    where cr.job_name = 'purge_zero_credit_accounts'
                      and (err ->> 'user_id') = p.id::text
                      and cr.started_at > v_now - interval '30 days'
                ) then 1 else 0 end) asc,
                p.zero_credits_since asc,
                p.id asc
            limit v_batch
            for update of p skip locked
        ) t;

        v_scanned := coalesce(array_length(v_candidate_ids, 1), 0);

        if v_dry_run then
            v_status := 'dry_run';
            v_details := jsonb_build_object(
                'zero_credit_days', v_days,
                'cutoff', v_cutoff,
                'batch_size', v_batch,
                'dry_run', true,
                'scanned_accounts', v_scanned,
                'deleted_accounts', 0,
                'candidate_user_ids', to_jsonb(v_candidate_ids),
                'errors', '[]'::jsonb
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

        perform set_config('app.delete_reason', 'zero_credit_account_purge', true);

        foreach v_user_id in array v_candidate_ids loop
            begin
                -- Re-check profile state under lock before physical deletion
                select p.id, p.credits, p.zero_credits_since, p.role
                into v_prof
                from public.profiles p
                where p.id = v_user_id
                for update;

                if v_prof.id is null then
                    continue;
                end if;

                if v_prof.credits > 0
                   or v_prof.zero_credits_since is null
                   or v_prof.zero_credits_since > v_cutoff
                   or lower(btrim(coalesce(v_prof.role, 'member'))) in ('admin', 'moderator')
                   or exists (
                       select 1 from public.admin_capabilities ac where ac.admin_id = v_user_id
                   )
                   or exists (
                       select 1 from public.credit_logs cl
                       where cl.user_id = v_user_id
                         and (cl.balance > 0 or cl.amount > 0)
                         and cl.created_at > v_cutoff
                   )
                   or exists (
                       select 1
                       from public.missions m
                       left join public.campaigns mc on mc.id = m.campaign_id
                       where (m.creator_id = v_user_id or mc.owner_id = v_user_id)
                         and m.escrow_remaining_credits > 0
                         and not coalesce(m.escrow_refunded, false)
                   )
                   or exists (
                       select 1
                       from public.campaign_tester_bonus_rounds br
                       left join public.campaigns bc on bc.id = br.campaign_id
                       where (br.owner_id = v_user_id or bc.owner_id = v_user_id)
                         and br.status in ('active', 'closing')
                         and br.remaining_credits > 0
                   )
                   or exists (
                       select 1
                       from public.app_update_releases ar
                       left join public.campaigns ac on ac.id = ar.campaign_id
                       where (ar.owner_id = v_user_id or ar.owner_id_snapshot = v_user_id or ac.owner_id = v_user_id)
                         and ar.status = 'active'
                         and ar.remaining_credits > 0
                   )
                then
                    continue;
                end if;

                -- Step A: Dual-storage report_media_uploads cleanup / detachment
                -- Lock storage_organization_usage (id = 1) first per 091..093 lock order.
                perform 1 from public.storage_organization_usage where id = 1 for update;

                delete from public.report_media_uploads m
                where (
                    m.user_id = v_user_id
                    or m.campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id)
                    or m.report_id in (
                        select tr.id
                        from public.tester_reports tr
                        where tr.user_id = v_user_id
                           or tr.campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id)
                    )
                )
                  and m.reserved_bytes = 0
                  and m.quota_accounted_bytes = 0
                  and (
                      m.provider = 'gcs'
                      or (
                          m.provider = 'supabase'
                          and not exists (
                              select 1
                              from storage.objects o
                              where o.bucket_id = m.bucket
                                and o.name in (m.object_path, m.thumbnail_object_path)
                          )
                      )
                  );
                get diagnostics v_row_count = row_count;
                v_deleted_media_rows := v_deleted_media_rows + v_row_count;

                -- Any remaining Supabase storage rows still holding physical objects/quota are
                -- detached from the user and marked expired for immediate storage-cleanup worker collection.
                update public.report_media_uploads m
                set user_id = null,
                    campaign_id = null,
                    report_id = null,
                    status = case
                        when m.status in ('reserved', 'uploaded', 'finalized') then 'expired'
                        else m.status
                    end,
                    cleanup_due_at = least(coalesce(m.cleanup_due_at, v_now), v_now),
                    delete_deadline_at = least(coalesce(m.delete_deadline_at, v_now + interval '24 hours'), v_now + interval '24 hours'),
                    updated_at = v_now
                where m.user_id = v_user_id
                   or m.campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id)
                   or m.report_id in (
                       select tr.id
                       from public.tester_reports tr
                       where tr.user_id = v_user_id
                          or tr.campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id)
                   );
                get diagnostics v_row_count = row_count;
                v_expired_media_rows := v_expired_media_rows + v_row_count;

                -- Step B: Remove dependent rows in NO ACTION FK tables referencing profiles / campaigns
                delete from public.campaign_tester_bonus_awards
                where tester_id = v_user_id
                   or owner_id = v_user_id
                   or campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id);

                delete from public.campaign_tester_bonus_events
                where owner_id = v_user_id
                   or actor_id = v_user_id
                   or campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id);

                update public.campaigns
                set tester_bonus_round_id = null
                where owner_id = v_user_id
                   or tester_bonus_round_id in (
                       select br.id
                       from public.campaign_tester_bonus_rounds br
                       where br.owner_id = v_user_id
                          or br.campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id)
                   );

                delete from public.campaign_tester_bonus_rounds
                where owner_id = v_user_id
                   or campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id);

                -- Clear settled / zero-escrow missions owned by user or on user's campaigns
                delete from public.missions
                where creator_id = v_user_id
                   or campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id);

                -- Clear settled / zero-escrow app update financial events and releases
                delete from public.app_update_financial_events
                where owner_id = v_user_id
                   or tester_id = v_user_id
                   or release_id in (
                       select ar.id
                       from public.app_update_releases ar
                       where ar.owner_id = v_user_id
                          or ar.owner_id_snapshot = v_user_id
                          or ar.campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id)
                   );

                delete from public.app_update_releases
                where owner_id = v_user_id
                   or owner_id_snapshot = v_user_id
                   or campaign_id in (select c.id from public.campaigns c where c.owner_id = v_user_id);

                -- Clear any former-admin NO ACTION FK references if a demoted account is ever purged
                delete from public.user_warning_revocations where revoked_by = v_user_id;
                delete from public.notification_campaign_events where admin_id = v_user_id;
                delete from public.notification_campaign_requests where admin_id = v_user_id;
                update public.notification_campaigns set cancelled_by = null where cancelled_by = v_user_id;
                delete from public.notification_campaigns where created_by = v_user_id;
                update public.admin_capabilities set granted_by = null where granted_by = v_user_id;
                delete from public.admin_action_events where actor_id = v_user_id;
                update public.owner_auto_pause_configs set updated_by = null where updated_by = v_user_id;
                update public.home_stats_policy_audits set actor_id = null where actor_id = v_user_id;

                -- Step C: Delete user's campaigns, auth.users row (cascading all auth & profile child tables), and profile row
                delete from public.campaigns where owner_id = v_user_id;
                get diagnostics v_row_count = row_count;
                v_deleted_campaigns := v_deleted_campaigns + v_row_count;

                delete from auth.users where id = v_user_id;
                get diagnostics v_auth_deleted = row_count;

                delete from public.profiles where id = v_user_id;
                get diagnostics v_prof_deleted = row_count;

                -- Step D: Purge non-FK / trigger-populated journal, archive, and receipt rows for this user
                delete from public.daily_test_events_journal where user_id = v_user_id;
                delete from public.campaign_status_journal where owner_id = v_user_id;
                delete from public.user_role_journal where user_id = v_user_id;
                delete from public.campaign_archive where owner_id = v_user_id;
                delete from public.campaign_removal_receipts where owner_id = v_user_id;
                delete from public.campaign_push_receipts where user_id = v_user_id;
                delete from public.user_daily_tasks where user_id = v_user_id;
                delete from public.report_cancelled_requests where user_id = v_user_id;
                delete from public.report_submission_receipts where user_id = v_user_id;

                if (v_auth_deleted + v_prof_deleted) > 0 then
                    v_deleted := v_deleted + 1;
                    v_deleted_ids := array_append(v_deleted_ids, v_user_id);
                end if;
            exception when others then
                v_errors := v_errors || jsonb_build_array(
                    jsonb_build_object(
                        'user_id', v_user_id,
                        'error', left(sqlerrm, 500)
                    )
                );
            end;
        end loop;

        perform set_config('app.delete_reason', '', true);

        if jsonb_array_length(v_errors) = 0 then
            v_status := 'success';
        elsif v_deleted = 0 then
            v_status := 'failed';
        else
            v_status := 'partial';
        end if;

        v_details := jsonb_build_object(
            'zero_credit_days', v_days,
            'cutoff', v_cutoff,
            'batch_size', v_batch,
            'dry_run', false,
            'scanned_accounts', v_scanned,
            'deleted_accounts', v_deleted,
            'deleted_campaigns', v_deleted_campaigns,
            'deleted_report_media_uploads', v_deleted_media_rows,
            'expired_report_media_uploads', v_expired_media_rows,
            'deleted_user_ids', to_jsonb(v_deleted_ids),
            'errors', v_errors
        );

        update public.cleanup_runs
        set finished_at = clock_timestamp(),
            status = v_status,
            scanned = v_scanned,
            deleted = v_deleted,
            error = case
                when jsonb_array_length(v_errors) > 0
                    then 'One or more accounts failed to purge; see details.errors'
                else null
            end,
            details = v_details
        where id = v_run_id;

        return jsonb_build_object(
            'success', (v_status <> 'failed'),
            'run_id', v_run_id,
            'status', v_status,
            'scanned', v_scanned,
            'deleted', v_deleted
        ) || v_details;
    exception when others then
        perform set_config('app.delete_reason', '', true);

        update public.cleanup_runs
        set finished_at = clock_timestamp(),
            status = 'failed',
            scanned = v_scanned,
            deleted = v_deleted,
            error = left(sqlerrm, 2000)
        where id = v_run_id;

        raise warning 'purge_zero_credit_accounts failed: %', sqlerrm;
        return jsonb_build_object(
            'success', false,
            'run_id', v_run_id,
            'status', 'failed',
            'scanned', v_scanned,
            'deleted', v_deleted,
            'error', left(sqlerrm, 2000)
        );
    end;
end;
$function$;

REVOKE ALL ON FUNCTION public.purge_zero_credit_accounts(integer, boolean, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.purge_zero_credit_accounts(integer, boolean, integer) TO service_role;


-- ============================================================================
-- 3. UPDATE sync_campaign_lifecycle() WITH 6H NOTIFICATION + 24H OPERATIONAL + 24H ZERO-CREDIT ACCOUNT FALLBACKS
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

    begin
        if not exists (
            select 1
            from public.cleanup_runs cr
            where cr.job_name = 'purge_zero_credit_accounts'
              and cr.status = 'success'
              and cr.started_at > clock_timestamp() - interval '24 hours'
        ) then
            perform public.purge_zero_credit_accounts(30, false, 100);
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
                SELECT 1 FROM cron.job WHERE jobname = 'purge_zero_credit_accounts_job'
            ) THEN
                PERFORM cron.schedule(
                    'purge_zero_credit_accounts_job',
                    '45 3 * * *',
                    'SELECT public.purge_zero_credit_accounts(30, false, 200);'
                );
            END IF;
        END IF;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_cron conditional scheduling skipped (environment without pg_cron): %', SQLERRM;
END;
$cron_setup$;

COMMIT;
