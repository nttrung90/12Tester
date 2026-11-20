-- ============================================================================
-- Migration 080: Report Context Carryover Isolation and Chronological Gap Recovery
-- ============================================================================
-- 1. Explicitly isolate mission reports from regular app_test in daily journaling
-- 2. Prevent mission submissions from reducing regular test debt or bypassing campaign resume
-- 3. Historical journal reconciliation preserving legitimate events & marking missing source as unresolved
-- 4. Bounded chronological gap recovery advancing past 31-day lookback with immediate predecessor validation
-- 5. Diagnostic functions for stuck gaps and operational reconciliation of finalized history
-- ============================================================================

-- 1. SCHEMA HARDENING ON daily_test_events_journal
-- ============================================================================
ALTER TABLE public.daily_test_events_journal
    ADD COLUMN IF NOT EXISTS submission_context text,
    ADD COLUMN IF NOT EXISTS reconciliation_status text DEFAULT 'verified';

CREATE INDEX IF NOT EXISTS idx_daily_test_events_context
    ON public.daily_test_events_journal(source_type, submission_context);

-- Reconcile existing records in daily_test_events_journal using trustworthy source data
-- 1a. Matching reports in tester_reports
UPDATE public.daily_test_events_journal j
SET submission_context = tr.submission_context,
    is_qualifying = CASE
        WHEN tr.submission_context = 'mission' THEN false
        WHEN tr.submission_context = 'app_test' THEN (j.status IN ('pending', 'approved') AND j.campaign_owner_id <> j.user_id AND j.campaign_owner_id <> '00000000-0000-0000-0000-000000000000'::uuid)
        ELSE false
    END,
    reconciliation_status = CASE
        WHEN tr.submission_context = 'mission' THEN 'reconciled_mission'
        WHEN tr.submission_context = 'app_test' THEN 'verified_app_test'
        ELSE 'unresolved_unknown_context'
    END
FROM public.tester_reports tr
WHERE j.source_type = 'report' AND j.source_id = tr.id;

-- 1b. Reports missing from source table (do NOT silently infer as ordinary test or cleared debt)
UPDATE public.daily_test_events_journal j
SET is_qualifying = false,
    reconciliation_status = 'unresolved_missing_source'
WHERE j.source_type = 'report'
  AND (j.submission_context IS NULL OR j.reconciliation_status IS NULL OR j.reconciliation_status = 'verified')
  AND NOT EXISTS (SELECT 1 FROM public.tester_reports tr WHERE tr.id = j.source_id);

-- 1c. Checkins: explicitly marked
UPDATE public.daily_test_events_journal j
SET submission_context = 'checkin',
    reconciliation_status = 'verified_checkin'
WHERE j.source_type = 'checkin'
  AND (j.submission_context IS NULL OR j.reconciliation_status IS NULL);


-- 2. JOURNALING TRIGGERS (REPORTS & CHECKINS)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.trg_journal_report_event()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_owner_id uuid;
    v_qualifying boolean;
    v_act_date date;
    v_camp_exists boolean;
    v_context text;
    v_rec_status text;
begin
    if TG_OP = 'INSERT' or TG_OP = 'UPDATE' then
        select c.owner_id into v_owner_id from public.campaigns c where c.id = NEW.campaign_id;
        if v_owner_id is null then
            select j.owner_id into v_owner_id from public.campaign_status_journal j
            where j.campaign_id = NEW.campaign_id order by j.id desc limit 1;
        end if;

        v_context := NEW.submission_context;
        v_act_date := coalesce(NEW.report_date, (NEW.created_at at time zone 'Asia/Ho_Chi_Minh')::date);

        -- Strict separation: ONLY regular app_test reports qualify for daily test debt/targets.
        -- Mission reports NEVER qualify for regular test targets or debt reduction.
        if v_context = 'mission' then
            v_qualifying := false;
            v_rec_status := 'verified_mission';
        elsif v_context = 'app_test' then
            v_qualifying := (NEW.status in ('pending', 'approved') and v_owner_id is not null and NEW.user_id <> v_owner_id);
            v_rec_status := 'verified_app_test';
        else
            -- Context is missing or unknown: must NOT be inferred as ordinary test
            v_qualifying := false;
            v_rec_status := 'unresolved_unknown_context';
        end if;

        insert into public.daily_test_events_journal (
            source_type, source_id, user_id, campaign_id, campaign_owner_id,
            activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
        ) values (
            'report', NEW.id, NEW.user_id, NEW.campaign_id, coalesce(v_owner_id, '00000000-0000-0000-0000-000000000000'::uuid),
            v_act_date, NEW.status, v_qualifying, v_context, v_rec_status, clock_timestamp()
        );
        return NEW;
    elsif TG_OP = 'DELETE' then
        -- Cascade campaign deletion must preserve previous legitimate qualification
        select exists (select 1 from public.campaigns where id = OLD.campaign_id) into v_camp_exists;
        if v_camp_exists then
            v_act_date := coalesce(OLD.report_date, (OLD.created_at at time zone 'Asia/Ho_Chi_Minh')::date);
            insert into public.daily_test_events_journal (
                source_type, source_id, user_id, campaign_id, campaign_owner_id,
                activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
            ) values (
                'report', OLD.id, OLD.user_id, OLD.campaign_id, '00000000-0000-0000-0000-000000000000'::uuid,
                v_act_date, 'deleted', false, OLD.submission_context, 'verified_deletion', clock_timestamp()
            );
        end if;
        return OLD;
    end if;
    return null;
end;
$function$;

DROP TRIGGER IF EXISTS trg_journal_report_event_trg ON public.tester_reports;
CREATE TRIGGER trg_journal_report_event_trg
AFTER INSERT OR UPDATE OR DELETE ON public.tester_reports
FOR EACH ROW EXECUTE FUNCTION public.trg_journal_report_event();

CREATE OR REPLACE FUNCTION public.trg_journal_checkin_event()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_owner_id uuid;
    v_qualifying boolean;
    v_camp_exists boolean;
begin
    if TG_OP = 'INSERT' or TG_OP = 'UPDATE' then
        select c.owner_id into v_owner_id from public.campaigns c where c.id = NEW.campaign_id;
        if v_owner_id is null then
            select j.owner_id into v_owner_id from public.campaign_status_journal j
            where j.campaign_id = NEW.campaign_id order by j.id desc limit 1;
        end if;

        v_qualifying := (v_owner_id is not null and NEW.user_id <> v_owner_id and (
            NEW.note is null
            or (
                NEW.note not like 'Đã kiểm thử%'
                and NEW.note not like 'Đã nộp kết quả%'
                and NEW.note not like '%tạm tính%'
                and NEW.note not like 'Tự động duyệt%'
                and NEW.note not like '%Tự động duyệt%'
                and NEW.note not like '%chủ app duyệt%'
            )
        ));

        insert into public.daily_test_events_journal (
            source_type, source_id, user_id, campaign_id, campaign_owner_id,
            activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
        ) values (
            'checkin', NEW.id, NEW.user_id, NEW.campaign_id, coalesce(v_owner_id, '00000000-0000-0000-0000-000000000000'::uuid),
            NEW.checkin_date, 'created', v_qualifying, 'checkin', 'verified_checkin', clock_timestamp()
        );
        return NEW;
    elsif TG_OP = 'DELETE' then
        select exists (select 1 from public.campaigns where id = OLD.campaign_id) into v_camp_exists;
        if v_camp_exists then
            insert into public.daily_test_events_journal (
                source_type, source_id, user_id, campaign_id, campaign_owner_id,
                activity_date, status, is_qualifying, submission_context, reconciliation_status, observed_at
            ) values (
                'checkin', OLD.id, OLD.user_id, OLD.campaign_id, '00000000-0000-0000-0000-000000000000'::uuid,
                OLD.checkin_date, 'deleted', false, 'checkin', 'verified_checkin_deletion', clock_timestamp()
            );
        end if;
        return OLD;
    end if;
    return null;
end;
$function$;

DROP TRIGGER IF EXISTS trg_journal_checkin_event_trg ON public.checkins;
CREATE TRIGGER trg_journal_checkin_event_trg
AFTER INSERT OR UPDATE OR DELETE ON public.checkins
FOR EACH ROW EXECUTE FUNCTION public.trg_journal_checkin_event();


-- 3. GET DAILY TASK STATUS: ACCURATE D WITH UNRESOLVED GUARDS & HISTORICAL SNAPSHOT
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
        'carryover_ready', (v_carryover_ready and v_history_ready),
        'history_ready', v_history_ready,
        'carryover_effective_from', case when v_carry_effective_from is not null then to_char(v_carry_effective_from, 'YYYY-MM-DD') else null end
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_daily_task_status(uuid, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_daily_task_status(uuid, date) TO authenticated, service_role;


-- 4. BOUNDED CHRONOLOGICAL DAILY FINALIZATION RUNNERS
-- ============================================================================

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
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_eval >= v_carry_policy.effective_from);
    v_effective_from := case when v_is_carryover_active then v_carry_policy.effective_from else null end;

    for r in
        select p.id, p.credits, p.daily_missed_streak, lower(p.role) as role, p.created_at
        from public.profiles p
        where p.created_at < ((p_eval + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
        for update of p
    loop
        -- Checkpoint boundary protection (R08):
        -- An automatic recovery or global catch-up must not finalize or debit user r
        -- before user r's latest trusted checkpoint at or before the recovery target horizon.
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

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date, date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date, date) TO service_role;

CREATE OR REPLACE FUNCTION public._apply_daily_inactivity_penalty(p_eval date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
begin
    -- Security guard: Privileged execution restricted to service_role / cron (R02)
    if current_user in ('authenticated', 'anon') or auth.uid() is not null then
        raise exception 'PERMISSION_DENIED';
    end if;

    perform public._apply_daily_inactivity_penalty(p_eval, p_eval);
end;
$function$;

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date) TO service_role;

DROP FUNCTION IF EXISTS public.apply_daily_inactivity_penalty();
DROP FUNCTION IF EXISTS public.apply_daily_inactivity_penalty(integer);

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

    -- Scan from coverage start date without 31-day hard limit
    v_earliest_search_date := coalesce(v_coverage_start_date, v_yesterday - 30);

    -- Find earliest date that has at least one user who can actually be finalized:
    -- User existed, is not yet finalized on dt, AND:
    -- 1. Date dt is not before user's trusted checkpoint at/before yesterday (R08)
    -- 2. User has no unresolved events on dt (R05)
    -- 3. EITHER dt is their account creation day
    --    OR (predecessor dt - 1 is finalized, and dt is within full coverage).
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
              (p.created_at at time zone 'Asia/Ho_Chi_Minh')::date = d.dt::date
              or (
                  exists (
                      select 1 from public.user_daily_tasks prev
                      where prev.user_id = p.id
                        and prev.task_date = (d.dt::date - 1)
                        and prev.finalized_at is not null
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

    -- Default: finalize yesterday
    perform public._apply_daily_inactivity_penalty(v_yesterday, v_yesterday);
    return 1;
end;
$function$;

REVOKE ALL ON FUNCTION public.apply_daily_inactivity_penalty(integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_daily_inactivity_penalty(integer) TO service_role;

DROP FUNCTION IF EXISTS public.finalize_daily_tasks_for_date(date);

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

    -- Find earliest date that has at least one user who can actually be finalized:
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
              (p.created_at at time zone 'Asia/Ho_Chi_Minh')::date = d.dt::date
              or (
                  exists (
                      select 1 from public.user_daily_tasks prev
                      where prev.user_id = p.id
                        and prev.task_date = (d.dt::date - 1)
                        and prev.finalized_at is not null
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


-- 5. AUDITED DIAGNOSTICS & RECONCILIATION REPORTING
-- ============================================================================

CREATE OR REPLACE FUNCTION public.diagnose_daily_task_stuck_users()
RETURNS TABLE (
    user_id uuid,
    first_stuck_date date,
    stuck_reason text,
    user_created_at timestamptz,
    latest_finalized_date date,
    coverage_start_date date
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_yesterday date := v_today - 1;
    v_cov_date date;
    v_jwt_role text := coalesce(nullif(current_setting('request.jwt.claim.role', true), ''), nullif(current_setting('role', true), 'none'));
    v_jwt_sub text := nullif(current_setting('request.jwt.claim.sub', true), '');
begin
    -- Authorized access only: unlocked admin or service_role (R03)
    if v_jwt_role in ('authenticated', 'anon') or v_jwt_sub is not null or auth.uid() is not null then
        if auth.uid() is null then
            raise exception 'PERMISSION_DENIED';
        end if;
        perform public.assert_unlocked_admin();
    end if;

    select marker_date into v_cov_date
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    return query
    with user_unfinalized as (
        select
            p.id as uid,
            p.created_at as p_created,
            (
                select min(d.dt::date)
                from generate_series(
                    greatest(coalesce(v_cov_date, v_yesterday - 30), (p.created_at at time zone 'Asia/Ho_Chi_Minh')::date),
                    v_yesterday,
                    interval '1 day'
                ) d(dt)
                where not exists (
                    select 1 from public.user_daily_tasks t
                    where t.user_id = p.id and t.task_date = d.dt::date and t.finalized_at is not null
                )
            ) as earliest_gap
        from public.profiles p
        where p.created_at < (v_today::timestamp at time zone 'Asia/Ho_Chi_Minh')
    ),
    stuck_candidates as (
        select
            u.uid,
            u.earliest_gap,
            u.p_created,
            (select max(t.task_date) from public.user_daily_tasks t where t.user_id = u.uid and t.finalized_at is not null) as last_fin,
            case
                when exists (
                    select 1 from public.daily_test_events_journal j
                    where j.user_id = u.uid and j.activity_date = u.earliest_gap
                      and j.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')
                ) then 'UNRESOLVED_EVENT_SOURCE'
                when u.earliest_gap > (u.p_created at time zone 'Asia/Ho_Chi_Minh')::date
                  and not exists (
                      select 1 from public.user_daily_tasks prev
                      where prev.user_id = u.uid and prev.task_date = (u.earliest_gap - 1) and prev.finalized_at is not null
                  ) then 'MISSING_PREDECESSOR_FINALIZATION'
                else 'UNFINALIZED_PENDING'
            end as diag_reason
        from user_unfinalized u
        where u.earliest_gap is not null
          and u.earliest_gap <= v_yesterday
          and (
              exists (
                  select 1 from public.daily_test_events_journal j
                  where j.user_id = u.uid and j.activity_date = u.earliest_gap
                    and j.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')
              )
              or (
                  u.earliest_gap > (u.p_created at time zone 'Asia/Ho_Chi_Minh')::date
                  and not exists (
                      select 1 from public.user_daily_tasks prev
                      where prev.user_id = u.uid and prev.task_date = (u.earliest_gap - 1) and prev.finalized_at is not null
                  )
              )
          )
    )
    select
        sc.uid,
        sc.earliest_gap,
        sc.diag_reason,
        sc.p_created,
        sc.last_fin,
        v_cov_date
    from stuck_candidates sc;
end;
$function$;

REVOKE ALL ON FUNCTION public.diagnose_daily_task_stuck_users() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.diagnose_daily_task_stuck_users() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.audit_carryover_journal_reconciliation()
RETURNS TABLE (
    user_id uuid,
    task_date date,
    finalized_at timestamptz,
    stored_completed_tests integer,
    stored_carry_out_tests integer,
    mission_events_count bigint,
    unresolved_events_count bigint,
    audit_recommendation text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_jwt_role text := coalesce(nullif(current_setting('request.jwt.claim.role', true), ''), nullif(current_setting('role', true), 'none'));
    v_jwt_sub text := nullif(current_setting('request.jwt.claim.sub', true), '');
begin
    -- Authorized access only: unlocked admin or service_role (R03)
    if v_jwt_role in ('authenticated', 'anon') or v_jwt_sub is not null or auth.uid() is not null then
        if auth.uid() is null then
            raise exception 'PERMISSION_DENIED';
        end if;
        perform public.assert_unlocked_admin();
    end if;

    return query
    select
        t.user_id,
        t.task_date,
        t.finalized_at,
        t.completed_tests,
        t.carry_out_tests,
        count(j.id) filter (where j.submission_context = 'mission' or j.reconciliation_status in ('reconciled_mission', 'verified_mission')),
        count(j.id) filter (where j.reconciliation_status in ('unresolved_missing_source', 'unresolved_unknown_context')),
        case
            when count(j.id) filter (where j.submission_context = 'mission') > 0
                then 'FINALIZED_WITH_MISSION_REPORTS: DO_NOT_AUTO_REWRITE_BALANCE; REQUIRE_ADMIN_ADJUSTMENT'
            when count(j.id) filter (where j.reconciliation_status like 'unresolved%') > 0
                then 'AMBIGUOUS_MISSING_SOURCE: REQUIRE_MANUAL_AUDIT'
            else 'CLEAN'
        end
    from public.user_daily_tasks t
    join public.daily_test_events_journal j
      on j.user_id = t.user_id and j.activity_date = t.task_date
    where t.finalized_at is not null
      and (
          j.submission_context = 'mission'
          or j.reconciliation_status in ('reconciled_mission', 'verified_mission', 'unresolved_missing_source', 'unresolved_unknown_context')
      )
    group by t.user_id, t.task_date, t.finalized_at, t.completed_tests, t.carry_out_tests
    order by t.task_date desc, t.user_id;
end;
$function$;

REVOKE ALL ON FUNCTION public.audit_carryover_journal_reconciliation() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.audit_carryover_journal_reconciliation() TO authenticated, service_role;
