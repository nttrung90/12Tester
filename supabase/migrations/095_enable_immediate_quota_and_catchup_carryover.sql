-- ============================================================================
-- MIGRATION 095: ENABLE IMMEDIATE DAILY QUOTA & CATCH-UP CARRYOVER FINALIZATION
-- ============================================================================
-- 1. Enable immediate daily task quota by default (effective_from = '-infinity')
--    and update any disabled policy rows with audit trail records.
-- 2. Reconcile historical unresolved_missing_source / unresolved_unknown_context
--    journal events so deleted-campaign orphans do not block finalization, and
--    backfill/normalize campaign_status_journal valid_from timestamps.
-- 3. Harden get_daily_task_status, _apply_daily_inactivity_penalty,
--    apply_daily_inactivity_penalty, finalize_daily_tasks_for_date, and
--    refresh_daily_completion:
--    - Pre-coverage signup days fall back to registration-day exemption.
--    - Registration-day unresolved events on active owners do not pin
--      v_earliest_unfinalized.
--    - Pre-coverage finalized rows (< coverage_start_date) preserve their
--      carry_out_tests and missed_streak when anchoring at coverage_start_date.
--    - Member mode with unpaid carryover_remaining_tests > 0 preserves
--      yesterday's missed_streak on login until carryover debt is cleared.
-- 4. Ensure pg_cron daily_inactivity_penalty_job is scheduled, execute bounded
--    catch-up finalization through yesterday, and refresh today's completion
--    for all profiles.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. ENABLE IMMEDIATE DAILY TASK QUOTA BY DEFAULT
-- ============================================================================

DO $$
declare
    v_note text := 'Migration 095: Enable immediate daily quota on campaign creation';
    v_invoker_role text := nullif(current_setting('role', true), 'none');
    v_actor text := coalesce(v_invoker_role, session_user, 'migration_095');
    v_row public.daily_task_immediate_quota_policies%rowtype;
    r record;
begin
    set local test.allow_policy_retroactive_write = 'true';

    select * into v_row
    from public.daily_task_immediate_quota_policies
    where effective_from = '-infinity'::date
    for update;

    if v_row.id is null then
        insert into public.daily_task_immediate_quota_policies (
            effective_from, is_enabled, operator_note, created_at
        ) values (
            '-infinity'::date, true, v_note, clock_timestamp()
        )
        returning * into v_row;

        insert into public.daily_task_immediate_quota_policy_audit (
            policy_id, effective_from, old_is_enabled, new_is_enabled,
            old_operator_note, new_operator_note, changed_by, changed_at
        ) values (
            v_row.id, v_row.effective_from, null, true,
            null, v_note, v_actor, clock_timestamp()
        );
    end if;

    for r in
        select *
        from public.daily_task_immediate_quota_policies
        where (
                effective_from = '-infinity'::date
                and (is_enabled is distinct from true or operator_note is distinct from v_note)
              )
           or is_enabled = false
        order by effective_from asc
        for update
    loop
        update public.daily_task_immediate_quota_policies
        set is_enabled = true,
            operator_note = v_note
        where id = r.id;

        insert into public.daily_task_immediate_quota_policy_audit (
            policy_id, effective_from, old_is_enabled, new_is_enabled,
            old_operator_note, new_operator_note, changed_by, changed_at
        ) values (
            r.id, r.effective_from, r.is_enabled, true,
            r.operator_note, v_note, v_actor, clock_timestamp()
        );
    end loop;
end;
$$;


-- ============================================================================
-- 2. RECONCILE HISTORICAL UNRESOLVED JOURNAL EVENTS & NORMALIZE CAMPAIGN JOURNAL
-- ============================================================================

UPDATE public.daily_test_events_journal j
SET submission_context = 'app_test',
    reconciliation_status = 'verified_app_test',
    is_qualifying = (
        j.status IN ('pending', 'approved')
        AND j.campaign_owner_id <> j.user_id
        AND j.campaign_owner_id <> '00000000-0000-0000-0000-000000000000'::uuid
    )
WHERE j.reconciliation_status IN ('unresolved_missing_source', 'unresolved_unknown_context')
  AND (
      EXISTS (
          SELECT 1
          FROM public.checkins c
          WHERE c.user_id = j.user_id
            AND c.campaign_id = j.campaign_id
            AND c.checkin_date = j.activity_date
      )
      OR EXISTS (
          SELECT 1
          FROM public.daily_test_events_journal jc
          WHERE jc.user_id = j.user_id
            AND jc.campaign_id = j.campaign_id
            AND jc.activity_date = j.activity_date
            AND jc.source_type = 'checkin'
            AND jc.is_qualifying = true
      )
  );

UPDATE public.daily_test_events_journal j
SET reconciliation_status = 'verified_deletion',
    is_qualifying = false
WHERE j.reconciliation_status IN ('unresolved_missing_source', 'unresolved_unknown_context');

INSERT INTO public.campaign_status_journal (
    campaign_id, owner_id, status, paused_reason, campaign_created_at, completed_at, slot_number, valid_from, valid_to
)
SELECT
    c.id,
    c.owner_id,
    c.status,
    c.paused_reason,
    c.created_at,
    c.completed_at,
    coalesce(c.slot_number, 1),
    greatest(
        c.created_at,
        coalesce(
            (SELECT marker_time FROM public.system_provenance_markers WHERE marker_key = 'coverage_start'),
            c.created_at
        )
    ),
    null
FROM public.campaigns c
WHERE NOT EXISTS (
    SELECT 1
    FROM public.campaign_status_journal j
    WHERE j.campaign_id = c.id
);

WITH first_entries AS (
    SELECT min(id) AS first_id
    FROM public.campaign_status_journal
    GROUP BY campaign_id
),
cov AS (
    SELECT marker_time
    FROM public.system_provenance_markers
    WHERE marker_key = 'coverage_start'
)
UPDATE public.campaign_status_journal j
SET valid_from = least(
        j.valid_from,
        greatest(
            j.campaign_created_at,
            coalesce((SELECT marker_time FROM cov), j.campaign_created_at)
        )
    )
FROM first_entries fe
WHERE j.id = fe.first_id
  AND j.valid_from > greatest(
      j.campaign_created_at,
      coalesce((SELECT marker_time FROM cov), j.campaign_created_at)
  );


-- ============================================================================
-- 3. GET_DAILY_TASK_STATUS: PRESERVE PRE-COVERAGE CARRY_OUT WHEN ANCHORING
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_daily_task_status(p_user_id uuid, p_date date)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_biz_policy public.business_policies;
    v_immediate_active boolean := false;
    v_streak int := 0;
    v_next_pct int := 1;
    v_k int := 0;
    v_a int := 0;
    v_d int := 0;
    v_base_required int := 0;
    v_carry_in int := 0;
    v_required int := 0;
    v_effective int := 0;
    v_carry_remaining int := 0;
    v_met boolean := false;
    v_exempt boolean := false;
    v_logged_in boolean := false;
    v_mode text := 'member';
    v_role text := 'user';
    v_user_created_date date;
    v_day_start timestamptz;
    v_next_day_start timestamptz;
    v_window_start timestamptz;
    v_coverage_start_time timestamptz;
    v_coverage_start_date date;
    v_history_ready boolean := false;
    v_carryover_ready boolean := false;
    v_is_carryover_active boolean := false;
    v_carry_effective_from date := null;
    v_last_task_date date;
    v_last_carry_out int;
    v_last_finalized timestamptz;
    v_last_version int;
    v_has_prior_history boolean := false;
    v_prev_finalized timestamptz;
    v_prev_carry_out int;
    v_voluntary_tests int := 0;
    v_q int := 0;
    v_hist_role text;
    v_hist_exempt boolean;
    v_last_login date;
    v_has_unresolved boolean := false;
    v_finalized_task public.user_daily_tasks%rowtype;
begin
    if p_user_id is null or p_date is null then
        return null;
    end if;

    select
        coalesce(daily_missed_streak, 0),
        lower(coalesce(role, 'user')),
        (created_at at time zone 'Asia/Ho_Chi_Minh')::date,
        last_login_date
    into v_streak, v_role, v_user_created_date, v_last_login
    from public.profiles
    where id = p_user_id;

    if not found then
        return null;
    end if;

    v_biz_policy := public._get_effective_business_policy_row(p_date);

    -- 1. Return immutable historical snapshot if p_date is already finalized
    if p_date < v_today then
        select * into v_finalized_task
        from public.user_daily_tasks
        where user_id = p_user_id
          and task_date = p_date
          and finalized_at is not null;

        if v_finalized_task.task_date is not null then
            return jsonb_build_object(
                'status_date', to_char(p_date, 'YYYY-MM-DD'),
                'mode', v_finalized_task.mode,
                'active_app_count', v_finalized_task.active_app_count,
                'required_tests', v_finalized_task.required_tests,
                'available_apps', v_finalized_task.available_apps,
                'effective_required_tests', v_finalized_task.effective_required_tests,
                'completed_tests', v_finalized_task.completed_tests,
                'logged_in_today', v_finalized_task.logged_in,
                'is_penalty_exempt', v_finalized_task.is_exempt,
                'missed_streak', v_finalized_task.missed_streak,
                'next_penalty_percent', case
                    when v_finalized_task.missed_streak <= 0 then v_biz_policy.penalty_tier1_pct
                    when v_finalized_task.missed_streak = 1 then v_biz_policy.penalty_tier2_pct
                    when v_finalized_task.missed_streak = 2 then v_biz_policy.penalty_tier3_pct
                    else v_biz_policy.penalty_tier4_pct
                end,
                'met', v_finalized_task.met,
                'base_required_tests', v_finalized_task.base_required_tests,
                'carry_in_tests', v_finalized_task.carry_in_tests,
                'carryover_remaining_tests', v_finalized_task.carry_out_tests,
                'carryover_ready', true,
                'history_ready', true,
                'carryover_effective_from', case when v_finalized_task.carryover_effective_from is not null then to_char(v_finalized_task.carryover_effective_from, 'YYYY-MM-DD') else null end
            );
        end if;
    end if;

    v_immediate_active := public.is_immediate_daily_quota_active(p_date);
    v_day_start := (p_date::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_next_day_start := ((p_date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_window_start := ((p_date - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh');

    select marker_time, marker_date
    into v_coverage_start_time, v_coverage_start_date
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    if p_date < v_today then
        if v_coverage_start_time is null or v_day_start < v_coverage_start_time then
            v_history_ready := false;
        else
            v_history_ready := true;
        end if;

        select j.role, j.is_exempt
        into v_hist_role, v_hist_exempt
        from public.user_role_journal j
        where j.user_id = p_user_id
          and j.valid_from < v_next_day_start
        order by j.valid_from desc, j.id desc
        limit 1;

        if v_hist_role is not null then
            v_role := v_hist_role;
            v_exempt := v_hist_exempt;
        else
            if v_history_ready then
                v_exempt := coalesce(v_role in ('admin', 'moderator'), false);
            else
                v_exempt := false;
            end if;
        end if;

        with hist as (
            select distinct on (j.campaign_id)
                j.campaign_id, j.owner_id, j.status, j.paused_reason,
                j.campaign_created_at, j.completed_at
            from public.campaign_status_journal j
            where j.valid_from < v_next_day_start
            order by j.campaign_id, j.valid_from desc, j.id desc
        )
        select
            coalesce(count(*) filter (
                where h.owner_id = p_user_id
                  and (
                      case when v_immediate_active then h.campaign_created_at < v_next_day_start
                           else h.campaign_created_at < v_day_start end
                  )
                  and h.campaign_created_at > v_window_start
                  and (
                      h.status = 'open'
                      or (h.status = 'paused' and h.paused_reason = 'owner_inactive')
                      or (h.completed_at is not null and h.completed_at >= v_next_day_start)
                  )
            ), 0),
            coalesce(count(*) filter (
                where h.owner_id <> p_user_id
                  and h.campaign_created_at < v_next_day_start
                  and h.campaign_created_at > v_window_start
                  and (
                      h.status = 'open'
                      or (h.completed_at is not null and h.completed_at >= v_next_day_start)
                  )
            ), 0)
        into v_k, v_a
        from hist h;

        with latest_events as (
            select distinct on (j.source_type, j.source_id)
                j.campaign_id, j.campaign_owner_id, j.is_qualifying, j.reconciliation_status
            from public.daily_test_events_journal j
            where j.user_id = p_user_id
              and j.activity_date = p_date
              and j.observed_at < v_next_day_start
            order by j.source_type, j.source_id, j.observed_at desc, j.id desc
        )
        select
            coalesce(count(distinct e.campaign_id) filter (
                where e.is_qualifying = true
                  and e.campaign_owner_id <> p_user_id
            ), 0),
            coalesce(bool_or(e.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')), false)
        into v_d, v_has_unresolved
        from latest_events e;

        if v_has_unresolved then
            v_history_ready := false;
        end if;
    else
        v_exempt := coalesce(v_role in ('admin', 'moderator'), false);

        select count(*) into v_k
        from public.campaigns c
        where c.owner_id = p_user_id
          and (
              case when v_immediate_active then c.created_at < v_next_day_start
                   else c.created_at < v_day_start end
          )
          and c.created_at > v_window_start
          and (
              c.status = 'open'
              or (c.status = 'paused' and c.paused_reason = 'owner_inactive')
              or (c.completed_at is not null and c.completed_at >= v_next_day_start)
          );

        select count(*) into v_a
        from public.campaigns c
        where c.owner_id <> p_user_id
          and c.created_at < v_next_day_start
          and c.created_at > v_window_start
          and (
              c.status = 'open'
              or (c.completed_at is not null and c.completed_at >= v_next_day_start)
          );

        with latest_events as (
            select distinct on (j.source_type, j.source_id)
                j.campaign_id, j.campaign_owner_id, j.is_qualifying, j.reconciliation_status
            from public.daily_test_events_journal j
            where j.user_id = p_user_id
              and j.activity_date = p_date
            order by j.source_type, j.source_id, j.observed_at desc, j.id desc
        )
        select
            coalesce(count(distinct e.campaign_id) filter (
                where e.is_qualifying = true
                  and e.campaign_owner_id <> p_user_id
            ), 0),
            coalesce(bool_or(e.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')), false)
        into v_d, v_has_unresolved
        from latest_events e;

        if v_has_unresolved then
            v_history_ready := false;
        else
            v_history_ready := true;
        end if;
    end if;

    select coalesce(v_last_login = p_date, false) or exists (
        select 1
        from public.user_daily_tasks t
        where t.user_id = p_user_id
          and t.task_date = p_date
          and t.logged_in = true
    ) into v_logged_in;

    v_policy := public.get_daily_task_policy(p_date);
    v_base_required := coalesce(v_policy.tests_per_active_app, 3) * v_k;
    v_mode := case when v_k > 0 then 'owner' else 'member' end;

    v_carry_policy := public.get_daily_task_carryover_policy(p_date);
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_date >= v_carry_policy.effective_from);

    select t.task_date, t.carry_out_tests, t.finalized_at, t.carryover_version
    into v_last_task_date, v_last_carry_out, v_last_finalized, v_last_version
    from public.user_daily_tasks t
    where t.user_id = p_user_id
      and t.task_date < p_date
      and t.finalized_at is not null
    order by t.task_date desc
    limit 1;

    v_has_prior_history := (v_last_task_date is not null);

    -- Check predecessor day (p_date - 1)
    select t.finalized_at, t.carry_out_tests
    into v_prev_finalized, v_prev_carry_out
    from public.user_daily_tasks t
    where t.user_id = p_user_id and t.task_date = (p_date - 1);

    if v_user_created_date is not null and p_date <= v_user_created_date then
        v_carry_in := 0;
        v_carry_remaining := 0;
        v_carryover_ready := true;
        if not v_has_unresolved then
            v_history_ready := true;
        end if;
    elsif v_prev_finalized is null then
        -- Users created before coverage_start_date without a finalized row on
        -- (coverage_start_date - 1) anchor at coverage_start_date, preserving
        -- carry_out_tests if they had a pre-coverage finalized row (< coverage_start_date).
        if v_coverage_start_date is not null
           and p_date = v_coverage_start_date
           and v_coverage_start_time is not null
           and v_day_start >= v_coverage_start_time then
            if v_last_task_date is not null and v_last_task_date < v_coverage_start_date then
                v_carry_in := greatest(0, coalesce(v_last_carry_out, 0));
            else
                v_carry_in := 0;
            end if;
            v_carry_remaining := 0;
            v_carryover_ready := true;
            if v_is_carryover_active then
                v_carry_effective_from := v_carry_policy.effective_from;
            else
                v_carry_effective_from := null;
            end if;
        else
            v_carryover_ready := false;
            v_carry_in := 0;
            v_carry_remaining := 0;
        end if;
    elsif v_is_carryover_active then
        v_carry_effective_from := v_carry_policy.effective_from;
        v_carry_in := coalesce(v_prev_carry_out, 0);
        v_carryover_ready := true;
    else
        v_carry_effective_from := null;
        v_carryover_ready := true;
        if coalesce(v_prev_carry_out, 0) > 0 then
            v_carry_in := v_prev_carry_out;
        else
            v_carry_in := 0;
        end if;
    end if;

    if v_carryover_ready and v_history_ready then
        if v_is_carryover_active then
            if v_exempt then
                v_required := v_base_required + v_carry_in;
                v_effective := least(v_required, v_a);
                v_carry_remaining := greatest(0, v_carry_in - v_d);
                v_met := true;
            elsif v_mode = 'member' then
                v_required := 0;
                v_effective := 0;
                v_carry_remaining := greatest(0, v_carry_in - v_d);
                v_met := v_logged_in or v_d > 0;
            else
                v_required := v_base_required + v_carry_in;
                v_effective := least(v_required, v_a);
                v_q := greatest(v_carry_in, v_effective);
                v_carry_remaining := greatest(0, v_q - v_d);
                v_met := (v_d >= v_effective);
            end if;
        else
            if v_carry_in > 0 then
                if v_mode = 'member' or v_exempt then
                    v_voluntary_tests := v_d;
                else
                    v_effective := least(v_base_required, v_a);
                    v_voluntary_tests := greatest(0, v_d - v_effective);
                end if;
                v_carry_remaining := greatest(0, v_carry_in - v_voluntary_tests);
            else
                v_carry_remaining := 0;
            end if;

            if v_exempt then
                v_required := v_base_required;
                v_effective := least(v_required, v_a);
                v_met := true;
            elsif v_mode = 'member' then
                v_required := 0;
                v_effective := 0;
                v_met := v_logged_in or v_d > 0;
            else
                v_required := v_base_required;
                v_effective := least(v_required, v_a);
                v_met := (v_d >= v_effective);
            end if;
        end if;
    else
        -- Preserve known carry_in_tests when predecessor is finalized but history_ready is false
        if v_mode = 'member' then
            v_required := 0;
            v_effective := 0;
            v_carry_remaining := case when v_carryover_ready then greatest(0, v_carry_in - v_d) else 0 end;
            v_met := v_logged_in or v_d > 0;
        else
            v_required := v_base_required + (case when v_carryover_ready and v_is_carryover_active then v_carry_in else 0 end);
            v_effective := least(v_required, v_a);
            if v_carryover_ready and v_carry_in > 0 then
                if v_is_carryover_active then
                    v_carry_remaining := greatest(0, greatest(v_carry_in, v_effective) - v_d);
                else
                    v_voluntary_tests := greatest(0, v_d - least(v_base_required, v_a));
                    v_carry_remaining := greatest(0, v_carry_in - v_voluntary_tests);
                end if;
            else
                v_carry_remaining := 0;
            end if;
            v_met := false;
        end if;
    end if;

    v_next_pct := case
        when v_streak <= 0 then v_biz_policy.penalty_tier1_pct
        when v_streak = 1 then v_biz_policy.penalty_tier2_pct
        when v_streak = 2 then v_biz_policy.penalty_tier3_pct
        else v_biz_policy.penalty_tier4_pct
    end;

    return jsonb_build_object(
        'status_date', to_char(p_date, 'YYYY-MM-DD'),
        'mode', v_mode,
        'active_app_count', v_k,
        'required_tests', v_required,
        'available_apps', v_a,
        'effective_required_tests', v_effective,
        'completed_tests', v_d,
        'logged_in_today', v_logged_in,
        'is_penalty_exempt', v_exempt,
        'missed_streak', v_streak,
        'next_penalty_percent', v_next_pct,
        'met', v_met,
        'base_required_tests', v_base_required,
        'carry_in_tests', v_carry_in,
        'carryover_remaining_tests', coalesce(v_carry_remaining, 0),
        'carryover_ready', (v_carryover_ready and v_history_ready),
        'history_ready', v_history_ready,
        'carryover_effective_from', case when v_carry_effective_from is not null then to_char(v_carry_effective_from, 'YYYY-MM-DD') else null end
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_daily_task_status(uuid, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_daily_task_status(uuid, date) TO authenticated, service_role;


-- ============================================================================
-- 4. _APPLY_DAILY_INACTIVITY_PENALTY, APPLY_DAILY_INACTIVITY_PENALTY,
--    FINALIZE_DAILY_TASKS_FOR_DATE
-- ============================================================================

CREATE OR REPLACE FUNCTION public._apply_daily_inactivity_penalty(p_eval date, p_horizon_scope date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_scope date := coalesce(p_horizon_scope, p_eval);
    v_checkpoint_boundary date;
    v_coverage_start_date date;
    v_coverage_start_time timestamptz;
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_biz_policy public.business_policies;
    v_immediate_active boolean := false;
    v_is_carryover_active boolean := false;
    v_effective_from date := null;
    r record;
    v_status jsonb;
    v_ready boolean;
    v_hist_ready boolean;
    v_met boolean;
    v_mode text;
    v_required int;
    v_effective int;
    v_completed int;
    v_logged_in boolean;
    v_exempt boolean;
    v_prev_streak int;
    v_new_streak int;
    v_raw_pct int;
    v_pct numeric;
    v_ratio numeric;
    v_deduction int;
    v_new_balance int;
    v_rows int;
    v_note text;
    v_base_req int;
    v_carry_in int;
    v_carry_out int;
    v_source_date date;
    v_last_task_date date;
    v_prev_streak_val int;
    v_prev_met boolean;
    v_prev_exempt boolean;
    v_prev_finalized timestamptz;
    v_last_streak int;
    v_latest_finalized_streak int;
    v_is_reg_day boolean := false;
    v_owner_reg_active boolean := false;
begin
    if p_eval is null then
        return;
    end if;

    -- Security guard: Privileged execution restricted to service_role / cron (R02)
    if current_user in ('authenticated', 'anon') or auth.uid() is not null then
        raise exception 'PERMISSION_DENIED';
    end if;

    if p_eval >= v_today and current_setting('test.allow_future_finalize', true) is distinct from 'true' then
        return;
    end if;

    perform pg_advisory_xact_lock(hashtextextended('daily_inactivity_penalty', 0));

    select marker_date, marker_time
    into v_coverage_start_date, v_coverage_start_time
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    v_policy := public.get_daily_task_policy(p_eval);
    v_carry_policy := public.get_daily_task_carryover_policy(p_eval);
    v_biz_policy := public._get_effective_business_policy_row(p_eval);
    v_immediate_active := public.is_immediate_daily_quota_active(p_eval);
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_eval >= v_carry_policy.effective_from);
    v_effective_from := case when v_is_carryover_active then v_carry_policy.effective_from else null end;

    for r in
        select p.id, p.credits, p.daily_missed_streak, lower(p.role) as role, p.created_at, p.last_login_date
        from public.profiles p
        where p.created_at < ((p_eval + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
        for update of p
    loop
        -- Checkpoint boundary protection (R08)
        if v_scope is not null then
            select max(t.task_date) into v_checkpoint_boundary
            from public.user_daily_tasks t
            where t.user_id = r.id
              and t.task_date <= v_scope
              and t.finalized_at is not null;

            if v_checkpoint_boundary is not null and p_eval < v_checkpoint_boundary then
                continue;
            end if;
        end if;

        -- Skip already finalized record
        if exists (
            select 1 from public.user_daily_tasks t
            where t.user_id = r.id and t.task_date = p_eval
              and t.finalized_at is not null
        ) then
            continue;
        end if;

        v_is_reg_day := (r.created_at >= (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh'));

        if v_is_reg_day then
            if v_immediate_active
               and (v_coverage_start_date is null or p_eval >= v_coverage_start_date)
               and (v_coverage_start_time is null or (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh') >= v_coverage_start_time)
            then
                v_status := public.get_daily_task_status(r.id, p_eval);
                if v_status is null then
                    continue;
                end if;

                if (v_status ->> 'mode' = 'owner')
                   and coalesce((v_status ->> 'active_app_count')::int, 0) > 0
                   and not coalesce((v_status ->> 'is_penalty_exempt')::boolean, false)
                then
                    v_owner_reg_active := true;
                    v_prev_streak := 0;
                else
                    v_owner_reg_active := false;
                end if;
            else
                v_owner_reg_active := false;
            end if;

            if not v_owner_reg_active then
                insert into public.user_daily_tasks as t (
                    user_id, task_date, mode, active_app_count, required_tests,
                    effective_required_tests, available_apps, completed_tests,
                    logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
                    policy_id, tests_per_active_app,
                    carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
                    carry_source_date, carryover_effective_from, business_policy_id, penalty_percent
                ) values (
                    r.id, p_eval,
                    case when r.role = 'owner' then 'owner' else 'member' end,
                    0, 0, 0, 0, 0,
                    true, true, true, 0, 0, now(),
                    v_policy.id, v_policy.tests_per_active_app,
                    case when v_is_carryover_active then 1 else null end,
                    0, 0, 0, null, v_effective_from,
                    v_biz_policy.id, 0
                )
                on conflict (user_id, task_date) do update
                set is_exempt = true,
                    met = true,
                    missed_streak = 0,
                    penalty_credits = 0,
                    finalized_at = now(),
                    policy_id = excluded.policy_id,
                    tests_per_active_app = excluded.tests_per_active_app,
                    carryover_version = excluded.carryover_version,
                    base_required_tests = 0,
                    carry_in_tests = 0,
                    carry_out_tests = 0,
                    carryover_effective_from = excluded.carryover_effective_from,
                    business_policy_id = excluded.business_policy_id,
                    penalty_percent = 0
                where t.finalized_at is null;

                continue;
            end if;
        else
            -- Strict chronological predecessor validation for days after registration,
            -- with clean anchoring at coverage_start_date for pre-coverage accounts
            -- (preserving pre-coverage finalized missed_streak if present).
            select t.missed_streak, t.met, t.is_exempt, t.finalized_at
            into v_prev_streak_val, v_prev_met, v_prev_exempt, v_prev_finalized
            from public.user_daily_tasks t
            where t.user_id = r.id and t.task_date = (p_eval - 1);

            if v_prev_finalized is null then
                if v_coverage_start_date is not null
                   and p_eval = v_coverage_start_date
                   and v_coverage_start_time is not null
                   and (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh') >= v_coverage_start_time then
                    select t.missed_streak into v_last_streak
                    from public.user_daily_tasks t
                    where t.user_id = r.id
                      and t.task_date < v_coverage_start_date
                      and t.finalized_at is not null
                    order by t.task_date desc
                    limit 1;

                    v_prev_streak := coalesce(v_last_streak, 0);
                else
                    continue;
                end if;
            else
                v_prev_streak := coalesce(v_prev_streak_val, 0);
            end if;

            v_status := public.get_daily_task_status(r.id, p_eval);
            if v_status is null then
                continue;
            end if;
        end if;

        v_ready := coalesce((v_status ->> 'carryover_ready')::boolean, true);
        v_hist_ready := coalesce((v_status ->> 'history_ready')::boolean, true);

        if (not v_ready) or (not v_hist_ready) then
            continue;
        end if;

        v_met := coalesce((v_status ->> 'met')::boolean, false);
        v_mode := coalesce(v_status ->> 'mode', 'member');
        v_required := coalesce((v_status ->> 'required_tests')::int, 0);
        v_effective := coalesce((v_status ->> 'effective_required_tests')::int, 0);
        v_completed := coalesce((v_status ->> 'completed_tests')::int, 0);
        v_logged_in := coalesce((v_status ->> 'logged_in_today')::boolean, false);
        v_exempt := coalesce((v_status ->> 'is_penalty_exempt')::boolean, false);
        v_base_req := coalesce((v_status ->> 'base_required_tests')::int, 0);
        v_carry_in := coalesce((v_status ->> 'carry_in_tests')::int, 0);
        v_carry_out := coalesce((v_status ->> 'carryover_remaining_tests')::int, 0);

        select t.task_date into v_last_task_date
        from public.user_daily_tasks t
        where t.user_id = r.id and t.task_date < p_eval and t.finalized_at is not null
        order by t.task_date desc
        limit 1;

        if v_carry_out > 0 then
            v_source_date := coalesce(v_last_task_date, p_eval);
        else
            v_source_date := null;
        end if;

        if v_exempt then
            v_new_streak := 0;
            v_deduction := 0;
            v_raw_pct := 0;
        elsif v_met then
            v_new_streak := 0;
            v_deduction := 0;
            v_raw_pct := 0;
        else
            if v_mode = 'owner' and v_effective <= 0 then
                v_new_streak := v_prev_streak;
                v_deduction := 0;
                v_raw_pct := 0;
            else
                v_new_streak := v_prev_streak + 1;
                v_raw_pct := case
                    when v_new_streak = 1 then v_biz_policy.penalty_tier1_pct
                    when v_new_streak = 2 then v_biz_policy.penalty_tier2_pct
                    when v_new_streak = 3 then v_biz_policy.penalty_tier3_pct
                    else v_biz_policy.penalty_tier4_pct
                end;
                v_pct := v_raw_pct::numeric / 100.0;

                if v_mode = 'owner' and v_effective > 0 then
                    v_ratio := greatest(
                        0,
                        least(
                            1,
                            (v_effective - v_completed)::numeric / v_effective::numeric
                        )
                    );
                elsif v_mode = 'member' then
                    v_ratio := case when v_logged_in then 0 else 1 end;
                else
                    v_ratio := 0;
                end if;

                if v_ratio <= 0 or v_raw_pct <= 0 then
                    v_deduction := 0;
                else
                    v_deduction := least(
                        r.credits,
                        greatest(1, ceil(r.credits * v_pct * v_ratio)::int)
                    );
                end if;
            end if;
        end if;

        v_new_balance := r.credits - v_deduction;

        insert into public.user_daily_tasks as t (
            user_id, task_date, mode, active_app_count, required_tests,
            effective_required_tests, available_apps, completed_tests,
            logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
            policy_id, tests_per_active_app,
            carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
            carry_source_date, carryover_effective_from, business_policy_id, penalty_percent
        ) values (
            r.id, p_eval, v_mode,
            coalesce((v_status ->> 'active_app_count')::int, 0),
            v_required,
            v_effective,
            coalesce((v_status ->> 'available_apps')::int, 0),
            v_completed,
            v_logged_in,
            v_exempt,
            v_met,
            v_new_streak,
            v_deduction,
            now(),
            v_policy.id,
            v_policy.tests_per_active_app,
            case when v_is_carryover_active then 1 else null end,
            v_base_req,
            v_carry_in,
            v_carry_out,
            v_source_date,
            v_effective_from,
            v_biz_policy.id,
            v_raw_pct
        )
        on conflict (user_id, task_date) do update
        set mode = excluded.mode,
            active_app_count = excluded.active_app_count,
            required_tests = excluded.required_tests,
            effective_required_tests = excluded.effective_required_tests,
            available_apps = excluded.available_apps,
            completed_tests = excluded.completed_tests,
            logged_in = excluded.logged_in,
            is_exempt = excluded.is_exempt,
            met = excluded.met,
            missed_streak = excluded.missed_streak,
            penalty_credits = excluded.penalty_credits,
            finalized_at = now(),
            policy_id = excluded.policy_id,
            tests_per_active_app = excluded.tests_per_active_app,
            carryover_version = excluded.carryover_version,
            base_required_tests = excluded.base_required_tests,
            carry_in_tests = excluded.carry_in_tests,
            carry_out_tests = excluded.carry_out_tests,
            carry_source_date = excluded.carry_source_date,
            carryover_effective_from = excluded.carryover_effective_from,
            business_policy_id = excluded.business_policy_id,
            penalty_percent = excluded.penalty_percent
        where t.finalized_at is null;

        get diagnostics v_rows = row_count;
        if v_rows = 0 then
            continue;
        end if;

        -- Profile summary reflects latest finalized ledger row
        select t.missed_streak into v_latest_finalized_streak
        from public.user_daily_tasks t
        where t.user_id = r.id and t.finalized_at is not null
        order by t.task_date desc
        limit 1;

        if v_deduction > 0 then
            update public.profiles
            set credits = v_new_balance,
                daily_missed_streak = coalesce(v_latest_finalized_streak, v_new_streak)
            where id = r.id;

            v_note := format('Phạt không hoàn thành nhiệm vụ ngày %s (-%s%%, -%s Credit)',
                to_char(p_eval, 'DD/MM/YYYY'), v_raw_pct, v_deduction);

            insert into public.credit_logs (
                user_id, amount, action, note, balance, created_at
            ) values (
                r.id, -v_deduction, 'penalty', v_note, v_new_balance, now()
            );

            insert into public.notifications (
                user_id, title, message, type, data
            ) values (
                r.id,
                'Phạt nhiệm vụ ngày ⚠️',
                v_note,
                'penalty',
                jsonb_build_object(
                    'task_date', to_char(p_eval, 'YYYY-MM-DD'),
                    'penalty_credits', v_deduction,
                    'penalty_percent', v_raw_pct,
                    'missed_streak', v_new_streak,
                    'mode', v_mode,
                    'required_tests', v_required,
                    'effective_required_tests', v_effective,
                    'completed_tests', v_completed,
                    'base_required_tests', v_base_req,
                    'carry_in_tests', v_carry_in,
                    'carry_out_tests', v_carry_out,
                    'business_policy_id', v_biz_policy.id,
                    'balance_after', v_new_balance
                )
            );
        else
            update public.profiles
            set daily_missed_streak = coalesce(v_latest_finalized_streak, v_new_streak)
            where id = r.id;
        end if;
    end loop;

    -- Refresh today's completion for ALL users who already logged in, have an
    -- unfinalized task row on v_today, or recorded test events on v_today
    if p_eval = (v_today - 1) then
        for r in
            select p.id as user_id
            from public.profiles p
            where p.last_login_date >= v_today
               or exists (
                   select 1
                   from public.user_daily_tasks udt_today
                   where udt_today.user_id = p.id
                     and udt_today.task_date = v_today
               )
               or exists (
                   select 1
                   from public.daily_test_events_journal j_today
                   where j_today.user_id = p.id
                     and j_today.activity_date = v_today
               )
            order by p.id
        loop
            perform public.refresh_daily_completion(r.user_id, v_today);
        end loop;
    end if;
end;
$function$;

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date, date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date, date) TO service_role;


CREATE OR REPLACE FUNCTION public.apply_daily_inactivity_penalty(p_batch_limit integer DEFAULT 31)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_yesterday date := v_today - 1;
    v_coverage_start_date date;
    v_coverage_start_time timestamptz;
    v_earliest_search_date date;
    v_earliest_unfinalized date;
    v_target_end date;
    v_cur date;
    v_batch int := coalesce(p_batch_limit, 31);
    v_days_processed int := 0;
begin
    -- Security guard: Privileged execution restricted to service_role / cron (R02)
    if current_user in ('authenticated', 'anon') or auth.uid() is not null then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_batch <= 0 then
        v_batch := 31;
    end if;

    select marker_date, marker_time
    into v_coverage_start_date, v_coverage_start_time
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    v_earliest_search_date := coalesce(v_coverage_start_date, v_yesterday - 30);

    select min(d.dt::date) into v_earliest_unfinalized
    from generate_series(v_earliest_search_date, v_yesterday, interval '1 day') d(dt)
    where exists (
        select 1 from public.profiles p
        where p.created_at < ((d.dt::date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
          -- Must not be before user's trusted checkpoint at or before yesterday (R08)
          and not exists (
              select 1 from public.user_daily_tasks cp
              where cp.user_id = p.id
                and cp.task_date <= v_yesterday
                and cp.task_date > d.dt::date
                and cp.finalized_at is not null
          )
          and not exists (
              select 1 from public.user_daily_tasks t
              where t.user_id = p.id
                and t.task_date = d.dt::date
                and t.finalized_at is not null
          )
          and (
              (
                  (p.created_at at time zone 'Asia/Ho_Chi_Minh')::date = d.dt::date
                  and (
                      v_coverage_start_date is null
                      or d.dt::date < v_coverage_start_date
                      or (v_coverage_start_time is not null and (d.dt::date::timestamp at time zone 'Asia/Ho_Chi_Minh') < v_coverage_start_time)
                      or not public.is_immediate_daily_quota_active(d.dt::date)
                      or not exists (
                          select 1 from public.daily_test_events_journal j
                          where j.user_id = p.id
                            and j.activity_date = d.dt::date
                            and j.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')
                      )
                  )
              )
              or (
                  (
                      exists (
                          select 1 from public.user_daily_tasks prev
                          where prev.user_id = p.id
                            and prev.task_date = (d.dt::date - 1)
                            and prev.finalized_at is not null
                      )
                      or (
                          v_coverage_start_date is not null
                          and d.dt::date = v_coverage_start_date
                          and (p.created_at at time zone 'Asia/Ho_Chi_Minh')::date < v_coverage_start_date
                      )
                  )
                  and (
                      v_coverage_start_time is not null
                      and (d.dt::date::timestamp at time zone 'Asia/Ho_Chi_Minh') >= v_coverage_start_time
                  )
                  and not exists (
                      select 1 from public.daily_test_events_journal j
                      where j.user_id = p.id
                        and j.activity_date = d.dt::date
                        and j.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')
                  )
              )
          )
    );

    if v_earliest_unfinalized is not null and v_earliest_unfinalized <= v_yesterday then
        v_target_end := least(v_yesterday, v_earliest_unfinalized + (v_batch - 1));
        v_cur := v_earliest_unfinalized;
        while v_cur <= v_target_end loop
            perform public._apply_daily_inactivity_penalty(v_cur, v_yesterday);
            v_days_processed := v_days_processed + 1;
            v_cur := v_cur + 1;
        end loop;
        return v_days_processed;
    end if;

    perform public._apply_daily_inactivity_penalty(v_yesterday, v_yesterday);
    return 1;
end;
$function$;

REVOKE ALL ON FUNCTION public.apply_daily_inactivity_penalty(integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_daily_inactivity_penalty(integer) TO service_role;


CREATE OR REPLACE FUNCTION public.finalize_daily_tasks_for_date(p_target_date date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_coverage_start_date date;
    v_coverage_start_time timestamptz;
    v_earliest_search_date date;
    v_earliest_unfinalized date;
    v_cur date;
begin
    -- Security guard: Privileged execution restricted to service_role / cron (R02)
    if current_user in ('authenticated', 'anon') or auth.uid() is not null then
        raise exception 'PERMISSION_DENIED';
    end if;

    if p_target_date is null then
        return;
    end if;
    if p_target_date >= v_today and current_setting('test.allow_future_finalize', true) is distinct from 'true' then
        return;
    end if;

    select marker_date, marker_time
    into v_coverage_start_date, v_coverage_start_time
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    v_earliest_search_date := coalesce(v_coverage_start_date, p_target_date - 30);

    select min(d.dt::date) into v_earliest_unfinalized
    from generate_series(v_earliest_search_date, p_target_date, interval '1 day') d(dt)
    where exists (
        select 1 from public.profiles p
        where p.created_at < ((d.dt::date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
          -- Must not be before user's trusted checkpoint at or before p_target_date (R08)
          and not exists (
              select 1 from public.user_daily_tasks cp
              where cp.user_id = p.id
                and cp.task_date <= p_target_date
                and cp.task_date > d.dt::date
                and cp.finalized_at is not null
          )
          and not exists (
              select 1 from public.user_daily_tasks t
              where t.user_id = p.id
                and t.task_date = d.dt::date
                and t.finalized_at is not null
          )
          and (
              (
                  (p.created_at at time zone 'Asia/Ho_Chi_Minh')::date = d.dt::date
                  and (
                      v_coverage_start_date is null
                      or d.dt::date < v_coverage_start_date
                      or (v_coverage_start_time is not null and (d.dt::date::timestamp at time zone 'Asia/Ho_Chi_Minh') < v_coverage_start_time)
                      or not public.is_immediate_daily_quota_active(d.dt::date)
                      or not exists (
                          select 1 from public.daily_test_events_journal j
                          where j.user_id = p.id
                            and j.activity_date = d.dt::date
                            and j.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')
                      )
                  )
              )
              or (
                  (
                      exists (
                          select 1 from public.user_daily_tasks prev
                          where prev.user_id = p.id
                            and prev.task_date = (d.dt::date - 1)
                            and prev.finalized_at is not null
                      )
                      or (
                          v_coverage_start_date is not null
                          and d.dt::date = v_coverage_start_date
                          and (p.created_at at time zone 'Asia/Ho_Chi_Minh')::date < v_coverage_start_date
                      )
                  )
                  and (
                      v_coverage_start_time is not null
                      and (d.dt::date::timestamp at time zone 'Asia/Ho_Chi_Minh') >= v_coverage_start_time
                  )
                  and not exists (
                      select 1 from public.daily_test_events_journal j
                      where j.user_id = p.id
                        and j.activity_date = d.dt::date
                        and j.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')
                  )
              )
          )
    );

    if v_earliest_unfinalized is not null and v_earliest_unfinalized < p_target_date then
        v_cur := v_earliest_unfinalized;
        while v_cur <= p_target_date loop
            perform public._apply_daily_inactivity_penalty(v_cur, p_target_date);
            v_cur := v_cur + 1;
        end loop;
        return;
    end if;

    perform public._apply_daily_inactivity_penalty(p_target_date, p_target_date);
end;
$function$;

REVOKE ALL ON FUNCTION public.finalize_daily_tasks_for_date(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_daily_tasks_for_date(date) TO service_role;


-- ============================================================================
-- 5. REFRESH_DAILY_COMPLETION: PRESERVE STREAK WHILE MEMBER CARRIES DEBT
-- ============================================================================

CREATE OR REPLACE FUNCTION public.refresh_daily_completion(p_user_id uuid, p_date date DEFAULT NULL::date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_date date := coalesce(p_date, v_today);
    v_status jsonb;
    v_met boolean;
    v_mode text;
    v_carry_remaining integer;
    v_carry_ready boolean;
    v_prev_streak integer;
begin
    if p_user_id is null then
        return;
    end if;

    if v_date <> v_today then
        return;
    end if;

    -- Canonical lock order: Lock profiles row FIRST before updating user_daily_tasks
    perform 1
    from public.profiles
    where id = p_user_id
    for update;

    v_status := public.get_daily_task_status(p_user_id, v_date);
    if v_status is null then
        return;
    end if;

    v_met := coalesce((v_status ->> 'met')::boolean, false);
    v_mode := coalesce(v_status ->> 'mode', 'member');
    v_carry_remaining := coalesce((v_status ->> 'carryover_remaining_tests')::int, 0);
    v_carry_ready := coalesce((v_status ->> 'carryover_ready')::boolean, true);

    update public.user_daily_tasks t
    set mode = coalesce(v_status ->> 'mode', t.mode),
        active_app_count = coalesce((v_status ->> 'active_app_count')::int, t.active_app_count),
        base_required_tests = coalesce((v_status ->> 'base_required_tests')::int, t.base_required_tests),
        carry_in_tests = coalesce((v_status ->> 'carry_in_tests')::int, t.carry_in_tests),
        required_tests = coalesce((v_status ->> 'required_tests')::int, t.required_tests),
        available_apps = coalesce((v_status ->> 'available_apps')::int, t.available_apps),
        effective_required_tests = coalesce((v_status ->> 'effective_required_tests')::int, t.effective_required_tests),
        completed_tests = coalesce((v_status ->> 'completed_tests')::int, t.completed_tests),
        logged_in = coalesce((v_status ->> 'logged_in_today')::boolean, t.logged_in),
        is_exempt = coalesce((v_status ->> 'is_penalty_exempt')::boolean, t.is_exempt),
        met = v_met
    where t.user_id = p_user_id
      and t.task_date = v_date
      and t.finalized_at is null;

    -- Member with unpaid carryover debt keeps previous missed streak until debt clears
    if v_met and not (v_mode = 'member' and v_carry_remaining > 0) then
        update public.profiles
        set daily_missed_streak = 0
        where id = p_user_id
          and daily_missed_streak is distinct from 0;
    elsif v_carry_ready then
        select t.missed_streak
          into v_prev_streak
        from public.user_daily_tasks t
        where t.user_id = p_user_id
          and t.task_date < v_today
          and t.finalized_at is not null
        order by t.task_date desc
        limit 1;

        if v_prev_streak is not null then
            update public.profiles
            set daily_missed_streak = v_prev_streak
            where id = p_user_id
              and daily_missed_streak is distinct from v_prev_streak;
        end if;
    end if;
end;
$function$;

REVOKE ALL ON FUNCTION public.refresh_daily_completion(uuid, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.refresh_daily_completion(uuid, date) TO authenticated, service_role;


-- ============================================================================
-- 6. ENSURE PG_CRON SCHEDULE & EXECUTE CATCH-UP FINALIZATION + TODAY SYNC
-- ============================================================================

DO $$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_yesterday date := v_today - 1;
    v_days integer := 0;
    v_iter integer;
    r record;
begin
    if to_regnamespace('cron') is not null then
        if not exists (
            select 1
            from cron.job
            where jobname = 'daily_inactivity_penalty_job'
              and schedule = '5 17 * * *'
              and command = 'select public.apply_daily_inactivity_penalty();'
        ) then
            perform cron.schedule(
                'daily_inactivity_penalty_job',
                '5 17 * * *',
                'select public.apply_daily_inactivity_penalty();'
            );
        end if;
    end if;

    for v_iter in 1..20 loop
        v_days := public.apply_daily_inactivity_penalty(31);
        exit when coalesce(v_days, 0) < 31;
    end loop;

    perform public.finalize_daily_tasks_for_date(v_yesterday);

    for r in
        select p.id
        from public.profiles p
        order by p.id
    loop
        perform public.refresh_daily_completion(r.id, v_today);
    end loop;
end;
$$;


NOTIFY pgrst, 'reload schema';

COMMIT;
