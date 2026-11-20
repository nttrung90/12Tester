-- Migration 079: Owner Auto-Pause Debt Gate, Creation Timestamp Guard, Chronological Finalization & Hardening
-- Date: 2026-11-05
-- Scope:
-- 1. Protect campaigns.created_at from client-side tampering (insert & update) while preserving legitimate Android creation & edits.
-- 2. Chronological daily task finalization in apply_daily_inactivity_penalty and finalize_daily_tasks_for_date.
-- 3. Strict predecessor sequentiality in _apply_daily_inactivity_penalty without reaching back past unfinalized days.
-- 4. Preserve current profile summary consistent with latest finalized ledger row when backfilling older gap days.
-- 5. Require ready history (both active & disabled carryover), quota met, and debt == 0 across all owner resume paths.
-- 6. Verify unlocked account on SECURITY DEFINER owner resume RPC.
-- 7. Account creation day exemption boundary: brand new accounts on signup day do not require yesterday's records.
-- 8. Authorized diagnostic query audit_daily_task_ledger_gaps restricted to unlocked admins and service_role.

BEGIN;

-- ============================================================================
-- 1. PROTECT CAMPAIGNS.CREATED_AT FROM CLIENT TAMPERING
-- ============================================================================

CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_client_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO ''
AS $function$
begin
    -- Trusted RPCs execute writes as their definer (e.g. postgres / service_role).
    -- Direct PostgREST client writes run as authenticated / anon.
    if current_user not in ('authenticated', 'anon') then
        return new;
    end if;

    if tg_op = 'INSERT' then
        if new.paused_reason in ('owner_inactive', 'slot_expired', 'admin_manual')
           or new.admin_pause_reason_code is not null then
            raise exception 'PERMISSION_DENIED';
        end if;

        -- Direct client inserts cannot forge past or future creation timestamps.
        -- Legitimate Android clients omit created_at or send null (server sets clock_timestamp()).
        if new.created_at is not null and abs(extract(epoch from (new.created_at - clock_timestamp()))) > 10 then
            raise exception 'PERMISSION_DENIED';
        end if;
        new.created_at := clock_timestamp();

        return new;
    end if;

    -- Direct client updates cannot forge or modify created_at under any circumstances
    if new.created_at is distinct from old.created_at then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Direct client updates cannot forge or modify admin_pause_reason_code
    if new.admin_pause_reason_code is distinct from old.admin_pause_reason_code then
        raise exception 'PERMISSION_DENIED';
    end if;

    if old.status is distinct from 'open' and new.status = 'open' then
        raise exception 'DIRECT_RESUME_NOT_ALLOWED';
    end if;

    if old.status = 'paused'
       and old.paused_reason in ('owner_inactive', 'slot_expired', 'admin_manual')
       and (new.status is distinct from old.status
            or new.paused_reason is distinct from old.paused_reason) then
        if old.paused_reason = 'admin_manual' then
            raise exception 'PAUSED_BY_ADMIN';
        end if;
        if new.status <> 'production' or new.paused_reason is not null then
            raise exception 'CANNOT_CHANGE_PAUSED_REASON';
        end if;
    end if;

    if new.paused_reason in ('owner_inactive', 'slot_expired', 'admin_manual')
       and new.paused_reason is distinct from old.paused_reason then
        raise exception 'PERMISSION_DENIED';
    end if;

    return new;
end;
$function$;

GRANT EXECUTE ON FUNCTION public.trg_campaigns_guard_client_lifecycle() TO PUBLIC, anon, authenticated, service_role;

DROP TRIGGER IF EXISTS trg_campaigns_guard_client_lifecycle ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_client_lifecycle
BEFORE INSERT OR UPDATE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_client_lifecycle();


-- ============================================================================
-- 2. GET_DAILY_TASK_STATUS: CREATION DAY EXEMPTION & PREDECESSOR READINESS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_daily_task_status(p_user_id uuid, p_date date)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_role text;
    v_last_login date;
    v_streak int;
    v_user_created_at timestamptz;
    v_user_created_date date;
    v_k int := 0;
    v_a int := 0;
    v_d int := 0;
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_biz_policy public.business_policies;
    v_exempt boolean := false;
    v_base_required int := 0;
    v_carry_in int := 0;
    v_required int := 0;
    v_effective int := 0;
    v_logged_in boolean := false;
    v_met boolean := false;
    v_next_pct int := 1;
    v_mode text;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_day_start timestamptz;
    v_next_day_start timestamptz;
    v_window_start timestamptz;
    v_carryover_ready boolean := true;
    v_history_ready boolean := true;
    v_carry_remaining int := 0;
    v_carry_effective_from date := null;
    v_prev_finalized timestamptz;
    v_prev_carry_out int;
    v_last_task_date date;
    v_last_carry_out int;
    v_last_finalized timestamptz;
    v_last_version int;
    v_has_prior_history boolean := false;
    v_is_carryover_active boolean := false;
    v_voluntary_tests int := 0;
    v_q int := 0;
    v_finalized_task record;
    v_coverage_start_time timestamptz;
    v_coverage_start_date date;
    v_hist_role text;
    v_hist_exempt boolean;
begin
    if p_user_id is null or p_date is null then
        return null;
    end if;

    select lower(p.role), p.last_login_date, p.daily_missed_streak, p.created_at
    into v_role, v_last_login, v_streak, v_user_created_at
    from public.profiles p
    where p.id = p_user_id;

    if not found then
        return null;
    end if;

    v_streak := coalesce(v_streak, 0);
    v_user_created_date := (v_user_created_at at time zone 'Asia/Ho_Chi_Minh')::date;
    v_biz_policy := public._get_effective_business_policy_row(p_date);

    -- 1. Nếu ngày p_date đã được chốt (finalized) trong user_daily_tasks:
    -- Trả lại snapshot lịch sử bất biến
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
                  and h.campaign_created_at < v_day_start
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
                j.campaign_id, j.campaign_owner_id, j.is_qualifying
            from public.daily_test_events_journal j
            where j.user_id = p_user_id
              and j.activity_date = p_date
              and j.observed_at < v_next_day_start
            order by j.source_type, j.source_id, j.observed_at desc, j.id desc
        )
        select count(distinct e.campaign_id) into v_d
        from latest_events e
        where e.is_qualifying = true
          and e.campaign_owner_id <> p_user_id;
    else
        v_exempt := coalesce(v_role in ('admin', 'moderator'), false);

        select count(*) into v_k
        from public.campaigns c
        where c.owner_id = p_user_id
          and c.created_at < v_day_start
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
                j.campaign_id, j.campaign_owner_id, j.is_qualifying
            from public.daily_test_events_journal j
            where j.user_id = p_user_id
              and j.activity_date = p_date
            order by j.source_type, j.source_id, j.observed_at desc, j.id desc
        )
        select count(distinct e.campaign_id) into v_d
        from latest_events e
        where e.is_qualifying = true
          and e.campaign_owner_id <> p_user_id;

        v_history_ready := true;
    end if;

    select (v_last_login = p_date) or exists (
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
        -- Account creation day exemption: account has no prior history/debt
        v_carry_in := 0;
        v_carry_remaining := 0;
        v_carryover_ready := true;
        v_history_ready := true;
    elsif v_prev_finalized is null then
        -- Predecessor day is unfinalized: history is pending regardless of carryover policy
        v_carryover_ready := false;
        v_carry_in := 0;
        v_carry_remaining := 0;
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

    if v_carryover_ready then
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
        if v_mode = 'member' then
            v_required := 0;
            v_effective := 0;
            v_carry_remaining := 0;
            v_met := v_logged_in or v_d > 0;
        else
            v_required := v_base_required;
            v_effective := least(v_required, v_a);
            v_carry_remaining := 0;
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
        'carryover_ready', v_carryover_ready,
        'history_ready', v_history_ready,
        'carryover_effective_from', case when v_carry_effective_from is not null then to_char(v_carry_effective_from, 'YYYY-MM-DD') else null end
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_daily_task_status(uuid, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_daily_task_status(uuid, date) TO authenticated, service_role;


-- ============================================================================
-- 3. CHRONOLOGICAL DAILY FINALIZATION RUNNERS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.apply_daily_inactivity_penalty()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_yesterday date := ((now() at time zone 'Asia/Ho_Chi_Minh')::date) - 1;
    v_coverage_start_date date;
    v_earliest_date date;
    v_earliest_unfinalized date;
    v_cur date;
begin
    select marker_date into v_coverage_start_date
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    v_earliest_date := greatest(coalesce(v_coverage_start_date, v_yesterday - 30), v_yesterday - 30);

    -- Find earliest unfinalized date among users created before the end of that day,
    -- regardless of whether carryover policy is currently active.
    select min(d.dt::date) into v_earliest_unfinalized
    from generate_series(v_earliest_date, v_yesterday, interval '1 day') d(dt)
    where exists (
        select 1 from public.profiles p
        where p.created_at < ((d.dt::date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
          and not exists (
              select 1 from public.user_daily_tasks t
              where t.user_id = p.id
                and t.task_date = d.dt::date
                and t.finalized_at is not null
          )
    );

    if v_earliest_unfinalized is not null and v_earliest_unfinalized <= v_yesterday then
        v_cur := v_earliest_unfinalized;
        while v_cur <= v_yesterday loop
            perform public._apply_daily_inactivity_penalty(v_cur);
            v_cur := v_cur + 1;
        end loop;
        return;
    end if;

    -- Default: finalize yesterday
    perform public._apply_daily_inactivity_penalty(v_yesterday);
end;
$function$;

CREATE OR REPLACE FUNCTION public.finalize_daily_tasks_for_date(p_target_date date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_coverage_start_date date;
    v_earliest_date date;
    v_earliest_unfinalized date;
    v_cur date;
begin
    if p_target_date is null or p_target_date >= v_today then
        return;
    end if;

    select marker_date into v_coverage_start_date
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    v_earliest_date := greatest(coalesce(v_coverage_start_date, p_target_date - 30), p_target_date - 30);

    select min(d.dt::date) into v_earliest_unfinalized
    from generate_series(v_earliest_date, p_target_date, interval '1 day') d(dt)
    where exists (
        select 1 from public.profiles p
        where p.created_at < ((d.dt::date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
          and not exists (
              select 1 from public.user_daily_tasks t
              where t.user_id = p.id
                and t.task_date = d.dt::date
                and t.finalized_at is not null
          )
    );

    if v_earliest_unfinalized is not null and v_earliest_unfinalized < p_target_date then
        v_cur := v_earliest_unfinalized;
        while v_cur <= p_target_date loop
            perform public._apply_daily_inactivity_penalty(v_cur);
            v_cur := v_cur + 1;
        end loop;
        return;
    end if;

    perform public._apply_daily_inactivity_penalty(p_target_date);
end;
$function$;


-- ============================================================================
-- 4. FINALIZER: SEQUENTIAL PREDECESSOR VALIDATION & SUMMARY INTEGRITY
-- ============================================================================

CREATE OR REPLACE FUNCTION public._apply_daily_inactivity_penalty(p_eval date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_biz_policy public.business_policies;
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
    v_latest_finalized_streak int;
begin
    if p_eval is null then
        return;
    end if;

    if p_eval >= v_today and current_setting('test.allow_future_finalize', true) is distinct from 'true' then
        return;
    end if;

    perform pg_advisory_xact_lock(hashtextextended('daily_inactivity_penalty', 0));

    v_policy := public.get_daily_task_policy(p_eval);
    v_carry_policy := public.get_daily_task_carryover_policy(p_eval);
    v_biz_policy := public._get_effective_business_policy_row(p_eval);
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_eval >= v_carry_policy.effective_from);
    v_effective_from := case when v_is_carryover_active then v_carry_policy.effective_from else null end;

    for r in
        select p.id, p.credits, p.daily_missed_streak, lower(p.role) as role, p.created_at
        from public.profiles p
        where p.created_at < ((p_eval + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
        for update of p
    loop
        -- 1. Account creation day exemption: sealed with streak 0, no penalty
        if r.created_at >= (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh') then
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

        -- 2. Skip already finalized record
        if exists (
            select 1 from public.user_daily_tasks t
            where t.user_id = r.id and t.task_date = p_eval
              and t.finalized_at is not null
        ) then
            continue;
        end if;

        -- 3. Strict chronological predecessor validation.
        -- Consecutive streak requires verified immediately preceding day (p_eval - 1).
        select t.missed_streak, t.met, t.is_exempt, t.finalized_at
        into v_prev_streak_val, v_prev_met, v_prev_exempt, v_prev_finalized
        from public.user_daily_tasks t
        where t.user_id = r.id and t.task_date = (p_eval - 1);

        -- If predecessor day is unfinalized, this user's streak history cannot be proven.
        -- Do not reach back across gaps or take unverified streak from profile. Leave user pending.
        if v_prev_finalized is null then
            continue;
        end if;

        v_prev_streak := coalesce(v_prev_streak_val, 0);

        v_status := public.get_daily_task_status(r.id, p_eval);
        if v_status is null then
            continue;
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

        -- Profile summary reflects latest finalized ledger row to preserve consistency
        -- when backfilling older gap days.
        select t.missed_streak into v_latest_finalized_streak
        from public.user_daily_tasks t
        where t.user_id = r.id and t.finalized_at is not null
        order by t.task_date desc
        limit 1;

        if not v_exempt then
            update public.profiles
            set credits = v_new_balance,
                daily_missed_streak = coalesce(v_latest_finalized_streak, v_new_streak)
            where id = r.id;

            if v_deduction > 0 then
                if v_mode = 'owner' then
                    v_note := format(
                        'Không đạt chỉ tiêu ngày %s: %s/%s app (thiếu %s). Trừ %s Credit.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_completed,
                        v_effective,
                        greatest(0, v_effective - v_completed),
                        v_deduction
                    );
                else
                    v_note := format(
                        'Không đăng nhập ngày %s. Trừ %s Credit.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_deduction
                    );
                end if;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (r.id, -v_deduction, 'daily_missed_penalty', v_note, v_new_balance);
            end if;
        else
            update public.profiles
            set daily_missed_streak = coalesce(v_latest_finalized_streak, 0)
            where id = r.id and daily_missed_streak <> coalesce(v_latest_finalized_streak, 0);
        end if;
    end loop;
end;
$function$;

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date) TO service_role;

REVOKE ALL ON FUNCTION public.apply_daily_inactivity_penalty() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_daily_inactivity_penalty() TO service_role;

REVOKE ALL ON FUNCTION public.finalize_daily_tasks_for_date(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.finalize_daily_tasks_for_date(date) TO service_role;


-- ============================================================================
-- 5. OWNER RESUME GATE: UNLOCKED CHECK, QUOTA MET AND REMAINING PRIOR DEBT == 0
-- ============================================================================

CREATE OR REPLACE FUNCTION public.resume_campaign(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_camp public.campaigns%rowtype;
    v_status jsonb;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_missed integer;
    v_threshold integer;
    v_carry_rem integer;
    v_is_locked boolean;
    v_user_created_date date;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Documented lock ordering:
    -- 1. Advisory transaction lock scoped to owner to serialize concurrent resume checks
    perform pg_advisory_xact_lock(hashtextextended('user_campaign_resume', hashtext(v_uid::text)));

    -- 2. Lock target campaign row
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp.owner_id <> v_uid then
        raise exception 'PERMISSION_DENIED';
    end if;
    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    if v_camp.paused_reason = 'admin_manual' then
        raise exception 'PAUSED_BY_ADMIN';
    end if;

    if v_camp.created_at <= now() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null,
            admin_pause_reason_code = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'completed'
        );
    end if;

    if v_camp.slot_number > 1 then
        if not exists (
            select 1 from public.user_slots s
            where s.user_id = v_camp.owner_id
              and s.slot_number = v_camp.slot_number
              and s.expires_at > now()
        ) then
            raise exception 'SLOT_EXPIRED';
        end if;
    end if;

    -- 3. Lock owner profile row and verify unlocked status
    select is_locked, (created_at at time zone 'Asia/Ho_Chi_Minh')::date
    into v_is_locked, v_user_created_date
    from public.profiles
    where id = v_uid
    for update;

    if coalesce(v_is_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    -- If account existed prior to today, yesterday MUST be finalized in user_daily_tasks
    -- regardless of whether carryover is enabled or disabled.
    if v_user_created_date is not null and v_user_created_date < v_today then
        if not exists (
            select 1 from public.user_daily_tasks t
            where t.user_id = v_uid
              and t.task_date = (v_today - 1)
              and t.finalized_at is not null
        ) then
            raise exception 'DAILY_TASK_PENDING';
        end if;
    end if;

    select coalesce((
        select missed_days from public.owner_auto_pause_configs where id = 'default'
    ), 7) into v_threshold;

    -- ALL owner-controlled resume paths (owner_inactive, owner_manual, slot_expired)
    -- require:
    --   1) Ready history & carryover
    --   2) Today's quota met (met = true)
    --   3) Actual remaining prior carryover debt == 0
    --   4) Profile streak below threshold
    v_status := public.get_daily_task_status(v_uid, v_today);

    if coalesce((v_status ->> 'carryover_ready')::boolean, true) = false
       or coalesce((v_status ->> 'history_ready')::boolean, true) = false then
        raise exception 'DAILY_TASK_PENDING';
    end if;

    if coalesce((v_status ->> 'met')::boolean, false) = false then
        raise exception 'OWNER_INACTIVE';
    end if;

    v_carry_rem := coalesce((v_status ->> 'carryover_remaining_tests')::int, 0);
    if v_carry_rem > 0 then
        raise exception 'OWNER_CARRYOVER_INCOMPLETE:%', v_carry_rem;
    end if;

    perform public.refresh_daily_completion(v_uid, v_today);

    select daily_missed_streak into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= v_threshold then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null,
        admin_pause_reason_code = null,
        admin_note = null,
        admin_resumed = false
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.resume_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.resume_campaign(uuid) TO authenticated, service_role;


-- ============================================================================
-- 6. AUTHORIZED READ-ONLY OPERATIONAL AUDIT QUERY
-- ============================================================================

CREATE OR REPLACE FUNCTION public.audit_daily_task_ledger_gaps()
RETURNS TABLE(
    user_id uuid,
    user_role text,
    daily_missed_streak integer,
    earliest_unfinalized_date date,
    unfinalized_days_count integer,
    last_finalized_date date,
    last_finalized_streak integer,
    last_finalized_carry_out integer,
    status_met_today boolean,
    status_carryover_remaining integer,
    history_ready_today boolean,
    carryover_ready_today boolean,
    diagnosis text
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_yesterday date := v_today - 1;
    v_coverage_start date;
    v_earliest date;
    r record;
    v_first_unfin date;
    v_unfin_cnt int;
    v_last_fin date;
    v_last_streak int;
    v_last_carry_out int;
    v_st jsonb;
    v_diag text;
    v_jwt_role text := coalesce(nullif(current_setting('request.jwt.claim.role', true), ''), nullif(current_setting('role', true), 'none'));
    v_jwt_sub text := nullif(current_setting('request.jwt.claim.sub', true), '');
begin
    -- Authorized access only: unlocked admin or service_role
    if v_jwt_role in ('authenticated', 'anon') or v_jwt_sub is not null or auth.uid() is not null then
        if auth.uid() is null then
            raise exception 'PERMISSION_DENIED';
        end if;
        perform public.assert_unlocked_admin();
    end if;

    select marker_date into v_coverage_start
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    v_earliest := greatest(coalesce(v_coverage_start, v_yesterday - 30), v_yesterday - 30);

    for r in
        select p.id, p.role, p.daily_missed_streak as streak, p.created_at
        from public.profiles p
        where p.created_at < ((v_yesterday + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
        order by p.id
    loop
        select min(d.dt::date), count(*)::int
        into v_first_unfin, v_unfin_cnt
        from generate_series(
            greatest(v_earliest, (r.created_at at time zone 'Asia/Ho_Chi_Minh')::date),
            v_yesterday,
            interval '1 day'
        ) d(dt)
        where not exists (
            select 1 from public.user_daily_tasks t
            where t.user_id = r.id
              and t.task_date = d.dt::date
              and t.finalized_at is not null
        );

        select t.task_date, t.missed_streak, t.carry_out_tests
        into v_last_fin, v_last_streak, v_last_carry_out
        from public.user_daily_tasks t
        where t.user_id = r.id and t.finalized_at is not null
        order by t.task_date desc
        limit 1;

        v_st := public.get_daily_task_status(r.id, v_today);

        v_diag := 'OK';
        if coalesce((v_st ->> 'history_ready')::boolean, true) = false then
            v_diag := 'HISTORY_NOT_READY';
        elsif coalesce((v_st ->> 'carryover_ready')::boolean, true) = false then
            v_diag := 'CARRYOVER_PENDING_UNFINALIZED_PREDECESSOR';
        elsif v_unfin_cnt > 0 then
            v_diag := 'GAP_UNFINALIZED_DAYS';
        end if;

        if v_unfin_cnt > 0 or v_diag <> 'OK' then
            user_id := r.id;
            user_role := r.role;
            daily_missed_streak := r.streak;
            earliest_unfinalized_date := v_first_unfin;
            unfinalized_days_count := coalesce(v_unfin_cnt, 0);
            last_finalized_date := v_last_fin;
            last_finalized_streak := v_last_streak;
            last_finalized_carry_out := v_last_carry_out;
            status_met_today := coalesce((v_st ->> 'met')::boolean, false);
            status_carryover_remaining := coalesce((v_st ->> 'carryover_remaining_tests')::int, 0);
            history_ready_today := coalesce((v_st ->> 'history_ready')::boolean, true);
            carryover_ready_today := coalesce((v_st ->> 'carryover_ready')::boolean, true);
            diagnosis := v_diag;
            return next;
        end if;
    end loop;
end;
$function$;

REVOKE ALL ON FUNCTION public.audit_daily_task_ledger_gaps() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.audit_daily_task_ledger_gaps() TO authenticated, service_role;

COMMIT;
