-- ============================================================================
-- MIGRATION 088: IMMEDIATE DAILY TASK QUOTA ON CAMPAIGN CREATION AND REMOVAL
-- ============================================================================
-- Scope:
-- 1. Configuration & Policy:
--    - Table public.daily_task_immediate_quota_policies: Audited effective date
--      for new quota semantics (default seeded to future business day).
--    - Table public.daily_task_immediate_quota_policy_audit: Audit log of changes.
--    - Helper functions:
--        get_daily_task_immediate_quota_policy(p_date date)
--        is_immediate_daily_quota_active(p_date date)
--        set_daily_task_immediate_quota_policy(p_effective_from date, p_is_enabled boolean, p_operator_note text)
-- 2. Quota Calculation (get_daily_task_status):
--    - When immediate quota is active on p_date:
--        * Today & historical reconstruction branches count eligible campaigns
--          created during p_date (created_at < v_next_day_start instead of created_at < v_day_start).
--        * Base quota B = tests_per_active_app * active_app_count (NOT target_testers, NOT hardcoded 12).
--        * Closed / pending_removal campaigns are excluded from active_app_count immediately.
--        * Registration day support: Accounts registered on p_date that post an active
--          campaign receive daily quota immediately (mode = 'owner', carry_in = 0, history_ready = true).
--        * Preserves completed tests D, carry_in C, debt formulas, and source caps.
--    - When immediate quota is not active (prior to activation):
--        * Retains previous semantics (created_at < v_day_start).
--    - Frozen historical snapshots (finalized_at is not null) remain completely immutable.
-- 3. Daily Completion Refresh (refresh_daily_completion):
--    - Atomically syncs unfinalized user_daily_tasks row and updates streak in profiles.
--    - Called upon campaign creation (create_campaign_v2) and campaign removal/closure
--      (_request_campaign_removal, _process_closing_tester_bonus_rounds).
-- 4. Finalizer (_apply_daily_inactivity_penalty):
--    - When immediate quota is active:
--        * Accounts registered on p_eval that posted an active campaign are finalized
--          normally under verified creation boundary (v_prev_streak = 0, no predecessor requirement,
--          no fake past checkpoint).
--        * Accounts registered on p_eval with no active campaigns (pure members / exempt)
--          retain the registration day exemption.
--    - When immediate quota is not active:
--        * Retains previous account creation day exemption for all users created on p_eval.
-- 5. Campaign Lifecycle Integration:
--    - create_campaign_v2: Invokes refresh_daily_completion after campaign insertion.
--      Preserves idempotent replay safety and financial receipt immutability.
--    - _request_campaign_removal: Invokes refresh_daily_completion when campaign is removed
--      or closed to pending_removal.
--    - _process_closing_tester_bonus_rounds: Invokes refresh_daily_completion when deferred
--      deletion physically completes (ensuring quota does not subtract a second time).
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. POLICY SCHEMA & CONFIGURATION
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.daily_task_immediate_quota_policies (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    effective_from date NOT NULL UNIQUE,
    is_enabled boolean DEFAULT true NOT NULL,
    operator_note text NOT NULL,
    created_at timestamptz DEFAULT clock_timestamp() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.daily_task_immediate_quota_policy_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_id bigint,
    effective_from date NOT NULL,
    old_is_enabled boolean,
    new_is_enabled boolean NOT NULL,
    old_operator_note text,
    new_operator_note text NOT NULL,
    changed_by text,
    changed_at timestamptz DEFAULT clock_timestamp() NOT NULL
);

ALTER TABLE public.daily_task_immediate_quota_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_task_immediate_quota_policy_audit ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "immediate_quota_policies_select" ON public.daily_task_immediate_quota_policies;
CREATE POLICY "immediate_quota_policies_select" ON public.daily_task_immediate_quota_policies
    FOR SELECT TO authenticated, anon USING (true);

DROP POLICY IF EXISTS "immediate_quota_policy_audit_select" ON public.daily_task_immediate_quota_policy_audit;
CREATE POLICY "immediate_quota_policy_audit_select" ON public.daily_task_immediate_quota_policy_audit
    FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid()
              AND lower(coalesce(p.role, 'user')) = 'admin'
              AND coalesce(p.is_locked, false) = false
        )
    );

REVOKE ALL ON public.daily_task_immediate_quota_policies FROM PUBLIC, anon;
GRANT SELECT ON public.daily_task_immediate_quota_policies TO anon, authenticated;
GRANT ALL ON public.daily_task_immediate_quota_policies TO service_role;

REVOKE ALL ON public.daily_task_immediate_quota_policy_audit FROM PUBLIC, anon;
GRANT SELECT ON public.daily_task_immediate_quota_policy_audit TO authenticated;
GRANT ALL ON public.daily_task_immediate_quota_policy_audit TO service_role;

DROP TRIGGER IF EXISTS trg_guard_immediate_daily_quota_policy ON public.daily_task_immediate_quota_policies;

-- Seed explicit baseline row: immediate quota inactive until scheduled future activation
INSERT INTO public.daily_task_immediate_quota_policies (effective_from, is_enabled, operator_note)
VALUES ('-infinity'::date, false, 'Baseline: immediate daily quota inactive until scheduled activation')
ON CONFLICT (effective_from) DO NOTHING;

-- Getter for effective immediate quota policy
CREATE OR REPLACE FUNCTION public.get_daily_task_immediate_quota_policy(p_date date DEFAULT NULL::date)
RETURNS public.daily_task_immediate_quota_policies
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_target date := coalesce(p_date, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date);
    v_policy public.daily_task_immediate_quota_policies;
begin
    if v_target is null or not isfinite(v_target) then
        return null;
    end if;

    select * into v_policy
    from public.daily_task_immediate_quota_policies
    where effective_from <= v_target
    order by effective_from desc
    limit 1;

    return v_policy;
end;
$function$;

REVOKE ALL ON FUNCTION public.get_daily_task_immediate_quota_policy(date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_daily_task_immediate_quota_policy(date) TO authenticated, service_role;

-- Check whether immediate quota is active on a given date
CREATE OR REPLACE FUNCTION public.is_immediate_daily_quota_active(p_date date DEFAULT NULL::date)
RETURNS boolean
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_policy public.daily_task_immediate_quota_policies;
begin
    v_policy := public.get_daily_task_immediate_quota_policy(p_date);
    return coalesce(v_policy.is_enabled, false);
end;
$function$;

REVOKE ALL ON FUNCTION public.is_immediate_daily_quota_active(date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_immediate_daily_quota_active(date) TO authenticated, service_role;

-- Setter for immediate quota policy with audit trail & historical modification protection
CREATE OR REPLACE FUNCTION public.set_daily_task_immediate_quota_policy(
    p_effective_from date,
    p_is_enabled boolean DEFAULT true,
    p_operator_note text DEFAULT 'Configured immediate quota policy'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_old public.daily_task_immediate_quota_policies;
    v_row public.daily_task_immediate_quota_policies;
    v_invoker_role text := nullif(current_setting('role', true), 'none');
    v_effective_role text := coalesce(v_invoker_role, session_user);
    v_is_trusted boolean := (v_effective_role in ('postgres', 'service_role', 'supabase_admin'));
    v_allow_retro boolean := (current_setting('test.allow_policy_retroactive_write', true) = 'true'
                              and v_is_trusted);
    v_clean_note text;
    v_admin_id uuid;
    v_actor text;
begin
    -- R2/R9: Authorization check:
    -- If called under authenticated session or with auth.uid, require unlocked admin.
    -- Null/empty identity or non-admin in authenticated session cannot schedule policy.
    -- Trusted roles (service_role, postgres) are identified via role context.
    if v_effective_role in ('authenticated', 'anon') or auth.uid() is not null then
        v_admin_id := public._assert_daily_task_admin();
        v_actor := v_admin_id::text;
    elsif v_is_trusted then
        v_actor := v_effective_role;
    else
        raise exception 'PERMISSION_DENIED: Admin access required';
    end if;

    if p_effective_from is null then
        raise exception 'INVALID_EFFECTIVE_DATE: Ngay hieu luc khong duoc de trong';
    end if;

    if p_effective_from = 'infinity'::date or p_effective_from = '-infinity'::date
       or p_effective_from < '2020-01-01'::date or p_effective_from > '2100-01-01'::date then
        raise exception 'INVALID_EFFECTIVE_DATE: Ngay hieu luc vuot ngoai pham vi cho phep';
    end if;

    v_clean_note := btrim(coalesce(p_operator_note, ''));
    if length(v_clean_note) < 3 then
        raise exception 'INVALID_OPERATOR_NOTE: Ghi chu van hanh phai co it nhat 3 ky tu';
    end if;

    select * into v_old
    from public.daily_task_immediate_quota_policies
    where effective_from = p_effective_from;

    -- R3: Future-only scheduling: Cannot insert or modify historical or current policy (effective_from <= v_today)
    if p_effective_from <= v_today and not v_allow_retro then
        raise exception 'CANNOT_MODIFY_HISTORICAL_OR_CURRENT_POLICY: Policy effective date must be in the future (greater than %)', v_today;
    end if;

    insert into public.daily_task_immediate_quota_policies (
        effective_from, is_enabled, operator_note, created_at
    ) values (
        p_effective_from, p_is_enabled, v_clean_note, clock_timestamp()
    )
    on conflict (effective_from) do update set
        is_enabled = excluded.is_enabled,
        operator_note = excluded.operator_note
    returning * into v_row;

    -- R4: Audit log records the actual administrator identity
    insert into public.daily_task_immediate_quota_policy_audit (
        policy_id, effective_from, old_is_enabled, new_is_enabled,
        old_operator_note, new_operator_note, changed_by, changed_at
    ) values (
        v_row.id, p_effective_from, v_old.is_enabled, v_row.is_enabled,
        v_old.operator_note, v_row.operator_note, v_actor, clock_timestamp()
    );

    return jsonb_build_object(
        'success', true,
        'id', v_row.id,
        'effective_from', to_char(v_row.effective_from, 'YYYY-MM-DD'),
        'is_enabled', v_row.is_enabled,
        'operator_note', v_row.operator_note
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.set_daily_task_immediate_quota_policy(date, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.set_daily_task_immediate_quota_policy(date, boolean, text) TO authenticated, service_role;

-- Direct table guard trigger on public.daily_task_immediate_quota_policies
CREATE OR REPLACE FUNCTION public.guard_immediate_daily_quota_policy()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_invoker_role text := nullif(current_setting('role', true), 'none');
    v_effective_role text := coalesce(v_invoker_role, session_user);
    v_is_client boolean := (v_effective_role in ('authenticated', 'anon') or auth.uid() is not null);
    v_allow_retro boolean := (current_setting('test.allow_policy_retroactive_write', true) = 'true'
                              and not v_is_client);
begin
    if not v_is_client or v_allow_retro then
        return case when tg_op = 'DELETE' then old else new end;
    end if;

    if tg_op = 'INSERT' and new.effective_from = '-infinity'::date then
        return new;
    end if;

    if tg_op in ('UPDATE', 'DELETE') and old.effective_from <= v_today then
        raise exception 'CANNOT_MODIFY_HISTORICAL_OR_CURRENT_POLICY: Policy effective date must be in the future';
    end if;

    if tg_op in ('INSERT', 'UPDATE') and (new.effective_from is null or new.effective_from <= v_today) then
        raise exception 'CANNOT_MODIFY_HISTORICAL_OR_CURRENT_POLICY: Policy effective date must be in the future';
    end if;

    return case when tg_op = 'DELETE' then old else new end;
end;
$function$;

DROP TRIGGER IF EXISTS trg_guard_immediate_daily_quota_policy ON public.daily_task_immediate_quota_policies;
CREATE TRIGGER trg_guard_immediate_daily_quota_policy
    BEFORE INSERT OR UPDATE OR DELETE ON public.daily_task_immediate_quota_policies
    FOR EACH ROW EXECUTE FUNCTION public.guard_immediate_daily_quota_policy();


-- ============================================================================
-- 2. GET_DAILY_TASK_STATUS: IMMEDIATE QUOTA & REGISTRATION DAY LOGIC
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

    -- 1. Nếu ngày p_date đã được chốt (finalized) trong user_daily_tasks:
    -- Trả lại snapshot lịch sử bất biến (R01 snapshot integrity)
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
        v_carry_in := 0;
        v_carry_remaining := 0;
        v_carryover_ready := true;
        v_history_ready := true;
    elsif v_prev_finalized is null then
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
        -- When v_carryover_ready is true but v_history_ready is false, preserve known carry_in_tests in
        -- required_tests and carryover_remaining_tests while keeping met = false
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
-- 3. _APPLY_DAILY_INACTIVITY_PENALTY: REGISTRATION DAY NORMAL OWNER PATH
-- ============================================================================

-- R1: Drop legacy signatures safely before recreating exact 2-arg and 1-arg signatures
DROP FUNCTION IF EXISTS public._apply_daily_inactivity_penalty(date);
DROP FUNCTION IF EXISTS public._apply_daily_inactivity_penalty(date, date);

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
            if v_immediate_active then
                v_status := public.get_daily_task_status(r.id, p_eval);
                if v_status is null then
                    continue;
                end if;

                -- If the account registered today has an eligible active app and is not exempt,
                -- it receives daily quota immediately and finalizes normally with v_prev_streak = 0.
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
                -- Retain account creation day exemption for pure members, exempt roles,
                -- or historical evaluations prior to immediate quota activation.
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
            -- Strict chronological predecessor validation for days after registration.
            select t.missed_streak, t.met, t.is_exempt, t.finalized_at
            into v_prev_streak_val, v_prev_met, v_prev_exempt, v_prev_finalized
            from public.user_daily_tasks t
            where t.user_id = r.id and t.task_date = (p_eval - 1);

            if v_prev_finalized is null then
                continue;
            end if;

            v_prev_streak := coalesce(v_prev_streak_val, 0);

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

    -- Refresh today's completion for users who already have activity on v_today
    if p_eval = (v_today - 1) then
        for r in
            select distinct e.user_id
            from public.daily_test_events_journal e
            where e.activity_date = v_today
        loop
            perform public.refresh_daily_completion(r.user_id, v_today);
        end loop;
    end if;
end;
$function$;

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date, date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date, date) TO service_role;

CREATE OR REPLACE FUNCTION public._apply_daily_inactivity_penalty(p_eval date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
begin
    if current_user in ('authenticated', 'anon') or auth.uid() is not null then
        raise exception 'PERMISSION_DENIED';
    end if;

    perform public._apply_daily_inactivity_penalty(p_eval, p_eval);
end;
$function$;

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date) TO service_role;


-- ============================================================================
-- 3b. REFRESH_DAILY_COMPLETION: CANONICAL LOCK ORDERING (R06 FIX)
-- ============================================================================
-- Canonical lock order: Lock profiles row FIRST before locking user_daily_tasks row.
-- Guarantees strict lock hierarchy (profiles -> user_daily_tasks) across
-- create_campaign_v2, delete_owned_campaign, admin_delete_campaign,
-- preventing 40P01 deadlocks when campaigns are concurrently created and deleted.

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
    v_carry_ready := coalesce((v_status ->> 'carryover_ready')::boolean, true);

    -- Đồng bộ bản ghi user_daily_tasks chưa chốt của ngày hôm nay (nếu đã có)
    update public.user_daily_tasks t
    set mode = coalesce(v_status ->> 'mode', t.mode),
        active_app_count = coalesce((v_status ->> 'active_app_count')::int, t.active_app_count),
        base_required_tests = coalesce((v_status ->> 'base_required_tests')::int, t.base_required_tests),
        carry_in_tests = coalesce((v_status ->> 'carry_in_tests')::int, t.carry_in_tests),
        required_tests = coalesce((v_status ->> 'required_tests')::int, t.required_tests),
        available_apps = coalesce((v_status ->> 'available_apps')::int, t.available_apps),
        effective_required_tests = coalesce((v_status ->> 'effective_required_tests')::int, t.effective_required_tests),
        completed_tests = coalesce((v_status ->> 'completed_tests')::int, t.completed_tests),
        is_exempt = coalesce((v_status ->> 'is_penalty_exempt')::boolean, t.is_exempt),
        met = v_met
    where t.user_id = p_user_id
      and t.task_date = v_date
      and t.finalized_at is null;

    if v_met then
        update public.profiles
        set daily_missed_streak = 0
        where id = p_user_id
          and daily_missed_streak <> 0;
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
-- 4. CREATE_CAMPAIGN_V2: ATOMIC REFRESH OF TODAY'S DAILY COMPLETION
-- ============================================================================

CREATE OR REPLACE FUNCTION public.create_campaign_v2(
    p_app_name text,
    p_package_name text,
    p_play_url text DEFAULT ''::text,
    p_testing_url text DEFAULT ''::text,
    p_description text DEFAULT ''::text,
    p_target_testers integer DEFAULT 12,
    p_icon_url text DEFAULT ''::text,
    p_test_username text DEFAULT ''::text,
    p_test_password text DEFAULT ''::text,
    p_slot_number integer DEFAULT 1,
    p_client_request_id uuid DEFAULT NULL::uuid,
    p_expected_total_cost integer DEFAULT NULL::integer,
    p_expected_min_credits integer DEFAULT NULL::integer,
    p_expected_revision text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
declare
    v_uid               uuid := auth.uid();
    v_now               timestamptz := clock_timestamp();
    v_vn_today          date := (v_now at time zone 'Asia/Ho_Chi_Minh')::date;
    v_clean_pkg         text;
    v_clean_name        text;
    v_clean_play_url    text;
    v_clean_test_url    text;
    v_clean_desc        text;
    v_clean_icon        text;
    v_clean_user        text;
    v_clean_pass        text;
    v_slot              integer;
    v_target            integer;
    v_payload_hash      text;
    v_existing_receipt  record;
    v_profile           public.profiles%rowtype;
    v_policy            public.business_policies;
    v_slot1_cost        integer;
    v_target_cost       integer;
    v_total_cost        integer;
    v_min_credits       integer;
    v_required_credits  integer;
    v_quote_token       text;
    v_all_token         text;
    v_balance_after     integer;
    v_campaign_id       uuid;
    v_receipt_id        uuid;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    v_clean_pkg := btrim(coalesce(p_package_name, ''));
    if v_clean_pkg = ''
       or length(v_clean_pkg) > 255
       or v_clean_pkg !~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'
    then
        raise exception 'INVALID_PACKAGE_NAME';
    end if;

    v_slot := coalesce(p_slot_number, 1);
    if v_slot < 1 or v_slot > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    v_target := coalesce(p_target_testers, 12);
    if v_target not in (12, 15, 20) then
        raise exception 'INVALID_TARGET_TESTERS';
    end if;

    if length(btrim(coalesce(p_app_name, ''))) > 200 then
        raise exception 'INVALID_APP_NAME: Tên ứng dụng tối đa 200 ký tự';
    end if;
    if length(btrim(coalesce(p_description, ''))) > 4000 then
        raise exception 'INVALID_DESCRIPTION: Mô tả tối đa 4000 ký tự';
    end if;
    if length(btrim(coalesce(p_play_url, ''))) > 2048 or length(btrim(coalesce(p_testing_url, ''))) > 2048 then
        raise exception 'INVALID_URL: Đường dẫn tối đa 2048 ký tự';
    end if;
    if length(btrim(coalesce(p_test_username, ''))) > 500 or length(btrim(coalesce(p_test_password, ''))) > 500 then
        raise exception 'INVALID_TEST_CREDENTIALS: Thông tin tài khoản thử nghiệm tối đa 500 ký tự';
    end if;
    if length(btrim(coalesce(p_icon_url, ''))) > 250000 then
        raise exception 'INVALID_ICON_URL: Dữ liệu biểu tượng vượt quá giới hạn cho phép';
    end if;

    v_clean_name := left(coalesce(nullif(btrim(coalesce(p_app_name, '')), ''), v_clean_pkg), 60);
    v_clean_play_url := coalesce(nullif(btrim(coalesce(p_play_url, '')), ''), 'https://play.google.com/store/apps/details?id=' || v_clean_pkg);
    v_clean_test_url := coalesce(nullif(btrim(coalesce(p_testing_url, '')), ''), 'https://play.google.com/apps/testing/' || v_clean_pkg);
    v_clean_desc := btrim(coalesce(p_description, ''));
    v_clean_icon := btrim(coalesce(p_icon_url, ''));
    v_clean_user := btrim(coalesce(p_test_username, ''));
    v_clean_pass := btrim(coalesce(p_test_password, ''));

    v_payload_hash := md5(jsonb_build_object(
        'package_name', lower(v_clean_pkg),
        'slot_number', v_slot,
        'target_testers', v_target,
        'app_name', v_clean_name,
        'play_url', v_clean_play_url,
        'testing_url', v_clean_test_url,
        'description', v_clean_desc,
        'icon_url', md5(v_clean_icon),
        'test_username', v_clean_user,
        'test_password', md5(v_clean_pass)
    )::text);

    -- 1. Pre-lock idempotent replay check (pure immutable financial receipt)
    select * into v_existing_receipt
    from public.campaign_creation_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.payload_hash <> v_payload_hash then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        if (p_expected_total_cost is not null and v_existing_receipt.total_cost <> p_expected_total_cost)
           or (p_expected_min_credits is not null and v_existing_receipt.min_credits_required <> p_expected_min_credits)
           or (p_expected_revision is not null and btrim(p_expected_revision) <> ''
               and p_expected_revision is distinct from v_existing_receipt.quote_token
               and p_expected_revision is distinct from v_existing_receipt.canonical_token)
        then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', v_existing_receipt.created_campaign_id,
            'slot_number', v_existing_receipt.slot_number,
            'target_testers', v_existing_receipt.target_testers,
            'slot_1_cost', v_existing_receipt.slot_1_cost,
            'target_cost', v_existing_receipt.target_cost,
            'charged_credits', v_existing_receipt.total_cost,
            'min_credits_required', v_existing_receipt.min_credits_required,
            'balance_after', v_existing_receipt.balance_after,
            'replayed', true
        );
    end if;

    -- 2. Serialize per-owner slot/package checks AND credit mutations in consistent lock order
    perform pg_advisory_xact_lock(hashtextextended('campaign_slot_guard', hashtext(v_uid::text)));

    select * into v_profile
    from public.profiles
    where id = v_uid
    for update;

    if v_profile.id is null then
        raise exception 'PROFILE_NOT_FOUND';
    end if;

    -- Re-check receipt under lock
    select * into v_existing_receipt
    from public.campaign_creation_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.payload_hash <> v_payload_hash then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        if (p_expected_total_cost is not null and v_existing_receipt.total_cost <> p_expected_total_cost)
           or (p_expected_min_credits is not null and v_existing_receipt.min_credits_required <> p_expected_min_credits)
           or (p_expected_revision is not null and btrim(p_expected_revision) <> ''
               and p_expected_revision is distinct from v_existing_receipt.quote_token
               and p_expected_revision is distinct from v_existing_receipt.canonical_token)
        then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', v_existing_receipt.created_campaign_id,
            'slot_number', v_existing_receipt.slot_number,
            'target_testers', v_existing_receipt.target_testers,
            'slot_1_cost', v_existing_receipt.slot_1_cost,
            'target_cost', v_existing_receipt.target_cost,
            'charged_credits', v_existing_receipt.total_cost,
            'min_credits_required', v_existing_receipt.min_credits_required,
            'balance_after', v_existing_receipt.balance_after,
            'replayed', true
        );
    end if;

    if coalesce(v_profile.is_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    -- 3. Resolve effective business policy & verify CAS quote terms
    v_policy := public._get_effective_business_policy_row(v_vn_today);
    v_slot1_cost := case when v_slot = 1 then v_policy.slot_fee_1 else 0 end;
    v_target_cost := case v_target
        when 15 then v_policy.target_15_fee
        when 20 then v_policy.target_20_fee
        else 0
    end;
    v_total_cost := v_slot1_cost + v_target_cost;
    v_min_credits := v_policy.min_credits_to_post_app;
    v_required_credits := v_total_cost + v_min_credits;

    v_quote_token := public._get_business_quote_token(
        'campaign_create',
        v_policy,
        jsonb_build_object('slot_number', v_slot, 'target_testers', v_target)
    );
    v_all_token := public._get_business_quote_token('all', v_policy);

    if p_expected_total_cost is null
       or p_expected_min_credits is null
       or p_expected_revision is null
       or btrim(p_expected_revision) = ''
    then
        raise exception 'BUSINESS_POLICY_CONFLICT: Báo giá đăng ứng dụng là bắt buộc đối với giao dịch v2';
    end if;

    if p_expected_total_cost <> v_total_cost or p_expected_min_credits <> v_min_credits then
        raise exception 'BUSINESS_POLICY_PRICE_MISMATCH: Điều kiện hoặc biểu phí đăng ứng dụng đã thay đổi (phí hiện tại: % Credit, số dư tối thiểu: % Credit)', v_total_cost, v_min_credits;
    end if;

    if p_expected_revision <> v_quote_token and p_expected_revision <> v_all_token then
        raise exception 'BUSINESS_POLICY_CONFLICT: Báo giá đăng ứng dụng đã hết hạn hoặc thay đổi. Vui lòng kiểm tra lại biểu phí mới.';
    end if;

    -- 4. Validate slot availability & duplicate active package
    if v_slot > 1 then
        if not exists (
            select 1 from public.user_slots
            where user_id = v_uid
              and slot_number = v_slot
              and expires_at > v_now
        ) then
            raise exception 'SLOT_NOT_ACTIVE_OR_EXPIRED';
        end if;
    end if;

    if exists (
        select 1 from public.campaigns
        where owner_id = v_uid
          and slot_number = v_slot
          and status in ('open', 'paused')
    ) then
        raise exception 'SLOT_ALREADY_IN_USE';
    end if;

    if exists (
        select 1 from public.campaigns
        where owner_id = v_uid
          and lower(package_name) = lower(v_clean_pkg)
          and status in ('open', 'paused')
    ) then
        raise exception 'PACKAGE_ALREADY_ACTIVE';
    end if;

    -- 5. Enforce standardized >= minimum balance after deducting total upfront posting fees
    v_balance_after := coalesce(v_profile.credits, 0);
    if v_balance_after < v_required_credits then
        if v_target_cost > 0 and v_balance_after >= (v_slot1_cost + v_min_credits) then
            raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần tối thiểu % Credit (gồm % Credit phí đăng và % Credit số dư duy trì)', v_required_credits, v_total_cost, v_min_credits;
        else
            raise exception 'INSUFFICIENT_CREDITS: Cần tối thiểu % Credit (gồm % Credit phí đăng và % Credit số dư duy trì)', v_required_credits, v_total_cost, v_min_credits;
        end if;
    end if;

    -- 6. Deduct upfront posting fees and write transparent credit_logs
    if v_slot1_cost > 0 then
        update public.profiles
        set credits = credits - v_slot1_cost
        where id = v_uid
        returning credits into v_balance_after;

        insert into public.credit_logs (user_id, amount, action, note, balance, created_at)
        values (
            v_uid,
            -v_slot1_cost,
            'post_app',
            format('Phí đăng ứng dụng "%s" tại Slot 1 (-%s Credit)', v_clean_name, v_slot1_cost),
            v_balance_after,
            v_now
        );
    end if;

    if v_target_cost > 0 then
        update public.profiles
        set credits = credits - v_target_cost
        where id = v_uid
        returning credits into v_balance_after;

        insert into public.credit_logs (user_id, amount, action, note, balance, created_at)
        values (
            v_uid,
            -v_target_cost,
            'target_upgrade',
            format('Nâng cấp mục tiêu %s Test/ngày cho "%s" (-%s Credit)', v_target, v_clean_name, v_target_cost),
            v_balance_after,
            case when v_slot1_cost > 0 then v_now + interval '1 microsecond' else v_now end
        );
    end if;

    -- 7. Insert campaign with snapshots & record receipt
    perform set_config('app.campaign_v2_bypass', 'true', true);

    insert into public.campaigns (
        owner_id,
        app_name,
        package_name,
        play_url,
        testing_url,
        description,
        target_testers,
        start_date,
        status,
        created_at,
        icon_url,
        test_username,
        test_password,
        slot_number,
        slot_1_fee_paid,
        target_fee_paid,
        creation_policy_id
    ) values (
        v_uid,
        v_clean_name,
        v_clean_pkg,
        v_clean_play_url,
        v_clean_test_url,
        v_clean_desc,
        v_target,
        v_vn_today,
        'open',
        v_now,
        v_clean_icon,
        v_clean_user,
        v_clean_pass,
        v_slot,
        v_slot1_cost,
        v_target_cost,
        v_policy.id
    )
    returning id into v_campaign_id;

    perform set_config('app.campaign_v2_bypass', 'false', true);

    insert into public.campaign_creation_receipts (
        user_id,
        client_request_id,
        campaign_id,
        created_campaign_id,
        package_name,
        slot_number,
        target_testers,
        slot_1_cost,
        target_cost,
        total_cost,
        min_credits_required,
        balance_after,
        quote_token,
        canonical_token,
        business_policy_id,
        payload_hash,
        created_at
    ) values (
        v_uid,
        p_client_request_id,
        v_campaign_id,
        v_campaign_id,
        v_clean_pkg,
        v_slot,
        v_target,
        v_slot1_cost,
        v_target_cost,
        v_total_cost,
        v_min_credits,
        v_balance_after,
        p_expected_revision,
        v_quote_token,
        v_policy.id,
        v_payload_hash,
        v_now
    )
    returning id into v_receipt_id;

    -- 8. Atomically refresh unfinalized daily completion status for the owner on VN today
    perform public.refresh_daily_completion(v_uid, v_vn_today);

    return jsonb_build_object(
        'success', true,
        'receipt_id', v_receipt_id,
        'client_request_id', p_client_request_id,
        'campaign_id', v_campaign_id,
        'slot_number', v_slot,
        'target_testers', v_target,
        'slot_1_cost', v_slot1_cost,
        'target_cost', v_target_cost,
        'charged_credits', v_total_cost,
        'min_credits_required', v_min_credits,
        'balance_after', v_balance_after,
        'replayed', false
    );
end;
$$;

REVOKE ALL ON FUNCTION public.create_campaign_v2(text, text, text, text, text, integer, text, text, text, integer, uuid, integer, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.create_campaign_v2(text, text, text, text, text, integer, text, text, text, integer, uuid, integer, integer, text) TO authenticated, service_role;


-- ============================================================================
-- 5. CAMPAIGN REMOVAL LIFECYCLE: ATOMIC REFRESH ON DELETE OR PENDING REMOVAL
-- ============================================================================

CREATE OR REPLACE FUNCTION public._request_campaign_removal(
    p_campaign_id uuid,
    p_reason text,
    p_by_admin boolean
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_owner_id uuid;
    v_app_name text;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_m record;
    v_r record;
    v_has_pending boolean := false;
    v_has_unsettled_bonus boolean := false;
    v_has_unsettled_missions boolean := false;
    v_effective_reason text;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    if p_by_admin then
        if v_caller_role is null or lower(v_caller_role) != 'admin' then
            raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền gỡ bỏ ứng dụng';
        end if;
    end if;

    -- Level 3: Lock campaign row FOR UPDATE BEFORE round locks
    select app_name, owner_id into v_app_name, v_owner_id
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Strict Actual-Owner Authorization when not requested by admin:
    if not p_by_admin then
        if v_owner_id is distinct from v_caller_id then
            raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng';
        end if;
    end if;

    v_effective_reason := coalesce(
        nullif(trim(p_reason), ''),
        case when p_by_admin then 'Gỡ bỏ bởi Admin' else 'Gỡ bỏ bởi Chủ sở hữu' end
    );

    -- Close any non-deleted missions and attempt to refund remaining escrow
    for v_m in
        select id, status, escrow_remaining_credits, escrow_refunded
        from public.missions
        where campaign_id = p_campaign_id
        for update
    loop
        if v_m.status <> 'deleted' then
            update public.missions
            set status = 'deleted',
                admin_closed_at = coalesce(admin_closed_at, now())
            where id = v_m.id;
        end if;

        if v_m.escrow_remaining_credits > 0 and not v_m.escrow_refunded then
            perform public._check_and_refund_mission_escrow(v_m.id);
        end if;
    end loop;

    -- Transition active bonus rounds to closing
    update public.campaign_tester_bonus_rounds
    set status = 'closing',
        stopped_at = coalesce(stopped_at, clock_timestamp())
    where campaign_id = p_campaign_id
      and status = 'active';

    -- Check if any pending reports exist on this campaign
    select exists (
        select 1 from public.tester_reports
        where campaign_id = p_campaign_id
          and status = 'pending'
    ) into v_has_pending;

    if v_has_pending then
        -- Stop new eligibility immediately by setting status to 'closed'.
        -- Base daily quota reduces immediately on transition to 'closed'.
        update public.campaigns
        set status = 'closed',
            paused_reason = case when p_by_admin then 'admin_deletion_pending' else 'owner_deletion_pending' end,
            admin_note = v_effective_reason
        where id = p_campaign_id;

        -- Atomically refresh unfinalized daily completion status for the owner on VN today
        perform public.refresh_daily_completion(v_owner_id, v_today);

        return jsonb_build_object(
            'success', true,
            'status', 'pending_removal',
            'campaign_id', p_campaign_id,
            'app_name', v_app_name,
            'owner_id', v_owner_id,
            'reason', v_effective_reason,
            'message', case
                when p_by_admin then 'Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất xóa'
                else 'Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất gỡ bỏ'
            end
        );
    end if;

    -- No pending reports: close and refund all remaining rounds
    for v_r in
        select id from public.campaign_tester_bonus_rounds
        where campaign_id = p_campaign_id
          and status in ('active', 'closing')
        order by created_at
        for update of campaign_tester_bonus_rounds
    loop
        perform public._close_and_refund_tester_bonus_round(
            v_r.id,
            case when p_by_admin then 'admin_delete' else 'owner_delete' end
        );
    end loop;

    -- If wallet refund overflowed or mission escrow unrefunded, keep closing/pending removal retryable
    select exists (
        select 1 from public.campaign_tester_bonus_rounds
        where campaign_id = p_campaign_id
          and status in ('active', 'closing')
          and remaining_credits > 0
    ) into v_has_unsettled_bonus;

    select exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and (status <> 'deleted' or (escrow_remaining_credits > 0 and not escrow_refunded))
    ) into v_has_unsettled_missions;

    if v_has_unsettled_bonus or v_has_unsettled_missions then
        update public.campaigns
        set status = 'closed',
            paused_reason = case when p_by_admin then 'admin_deletion_pending' else 'owner_deletion_pending' end,
            admin_note = v_effective_reason
        where id = p_campaign_id;

        -- Atomically refresh unfinalized daily completion status for the owner on VN today
        perform public.refresh_daily_completion(v_owner_id, v_today);

        return jsonb_build_object(
            'success', true,
            'status', 'pending_removal',
            'campaign_id', p_campaign_id,
            'app_name', v_app_name,
            'owner_id', v_owner_id,
            'reason', v_effective_reason,
            'message', case
                when p_by_admin then 'Chiến dịch đã được đóng để hoàn tất kết toán ngân sách trước khi hoàn tất xóa'
                else 'Chiến dịch đã được đóng để hoàn tất kết toán ngân sách trước khi hoàn tất gỡ bỏ'
            end
        );
    end if;

    delete from public.campaigns
    where id = p_campaign_id;

    -- Atomically refresh unfinalized daily completion status for the owner on VN today
    perform public.refresh_daily_completion(v_owner_id, v_today);

    return jsonb_build_object(
        'success', true,
        'status', case when p_by_admin then 'deleted' else 'removed' end,
        'campaign_id', p_campaign_id,
        'app_name', v_app_name,
        'owner_id', v_owner_id,
        'reason', v_effective_reason,
        'message', case
            when p_by_admin then 'Chiến dịch đã được xóa hoàn toàn'
            else 'Ứng dụng đã được gỡ bỏ hoàn toàn'
        end
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._request_campaign_removal(uuid, text, boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._request_campaign_removal(uuid, text, boolean) TO service_role;


-- Update _process_closing_tester_bonus_rounds to refresh daily completion on eventual physical deletion
-- (eventual cleanup does NOT subtract base quota again since campaign was already closed)
CREATE OR REPLACE FUNCTION public._process_closing_tester_bonus_rounds()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_camp record;
    v_round record;
    v_closed_count integer := 0;
    v_has_pending_reports boolean;
    v_has_unsettled_bonus boolean;
    v_has_unsettled_missions boolean;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    -- PART 1: Process campaigns with active/closing rounds OR pending deletion
    for v_camp in
        select c.id, c.status, c.paused_reason, c.owner_id
        from public.campaigns c
        where c.id in (
            select campaign_id from public.campaign_tester_bonus_rounds
            where status in ('active', 'closing') and campaign_id is not null
            union
            select id from public.campaigns
            where status = 'closed' and paused_reason in ('admin_deletion_pending', 'owner_deletion_pending')
        )
        order by c.id
        for update of c skip locked
    loop
        -- 1. If campaign is pending deletion (admin or owner), ensure any active bonus rounds transition to closing
        if v_camp.status = 'closed' and v_camp.paused_reason in ('admin_deletion_pending', 'owner_deletion_pending') then
            update public.campaign_tester_bonus_rounds
            set status = 'closing',
                stopped_at = coalesce(stopped_at, clock_timestamp())
            where campaign_id = v_camp.id
              and status = 'active';
        end if;

        -- 2. Process closing rounds for this locked campaign
        for v_round in
            select r.id, r.remaining_credits
            from public.campaign_tester_bonus_rounds r
            where r.campaign_id = v_camp.id
              and r.status = 'closing'
            order by r.created_at
            for update of r
        loop
            if not exists (
                select 1 from public.tester_reports
                where tester_bonus_round_id = v_round.id
                  and status = 'pending'
            ) then
                declare
                    v_close_res jsonb;
                begin
                    v_close_res := public._close_and_refund_tester_bonus_round(
                        v_round.id,
                        case
                            when v_camp.paused_reason = 'admin_deletion_pending' then 'admin_delete_settled'
                            when v_camp.paused_reason = 'owner_deletion_pending' then 'owner_delete_settled'
                            else 'closing_batch_settled'
                        end
                    );
                    if coalesce((v_close_res->>'closed')::boolean, false) then
                        v_closed_count := v_closed_count + 1;
                    end if;
                end;
            end if;
        end loop;

        -- 3. Durable deferred deletion completion (admin or owner):
        if v_camp.status = 'closed' and v_camp.paused_reason in ('admin_deletion_pending', 'owner_deletion_pending') then
            select exists (
                select 1 from public.tester_reports
                where campaign_id = v_camp.id
                  and status = 'pending'
            ) into v_has_pending_reports;

            select exists (
                select 1 from public.campaign_tester_bonus_rounds
                where campaign_id = v_camp.id
                  and status in ('active', 'closing')
                  and remaining_credits > 0
            ) into v_has_unsettled_bonus;

            select exists (
                select 1 from public.missions
                where campaign_id = v_camp.id
                  and (status <> 'deleted' or (escrow_remaining_credits > 0 and not escrow_refunded))
            ) into v_has_unsettled_missions;

            if not v_has_pending_reports and not v_has_unsettled_bonus and not v_has_unsettled_missions then
                delete from public.campaigns where id = v_camp.id;
                -- Quota was already reduced upon transition to 'closed'; refresh confirms state
                perform public.refresh_daily_completion(v_camp.owner_id, v_today);
            end if;
        end if;
    end loop;

    -- PART 2: Handle any orphan closing rounds where campaign_id IS NULL
    for v_round in
        select r.id
        from public.campaign_tester_bonus_rounds r
        where r.status = 'closing'
          and r.campaign_id is null
        order by r.created_at
        for update of r skip locked
    loop
        if not exists (
            select 1 from public.tester_reports
            where tester_bonus_round_id = v_round.id
              and status = 'pending'
        ) then
            declare
                v_orphan_res jsonb;
            begin
                v_orphan_res := public._close_and_refund_tester_bonus_round(v_round.id, 'closing_batch_settled');
                if coalesce((v_orphan_res->>'closed')::boolean, false) then
                    v_closed_count := v_closed_count + 1;
                end if;
            end;
        end if;
    end loop;

    return v_closed_count;
end;
$function$;

REVOKE ALL ON FUNCTION public._process_closing_tester_bonus_rounds() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._process_closing_tester_bonus_rounds() TO service_role;

NOTIFY pgrst, 'reload schema';

COMMIT;
