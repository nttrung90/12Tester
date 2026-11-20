-- ============================================================================
-- MIGRATION 082: CARRYOVER TASK FLOW AUDIT FIXES
-- ============================================================================
-- Scope:
--   1. [BUG-1] Isolate regular app_test reports from mission reports in
--      get_home_feed(), get_campaigns_stats(), get_next_untested_campaign(),
--      and get_my_dashboard() so submitting a mission report does not falsely
--      mark a campaign as tested today for regular daily tasks / carryover.
--   2. [BUG-2] Harden refresh_daily_completion(), _submit_tester_report_core(),
--      and _impl_reject_tester_report() so:
--      - Rejecting an app_test report refreshes daily completion and restores
--        profiles.daily_missed_streak from yesterday's finalized row if met
--        drops to false.
--      - Resubmitting a rejected app_test report (TH2) restores the provisional
--        checkins row and refreshes daily completion.
--   3. [BUG-3] In _apply_daily_inactivity_penalty(p_eval, p_horizon_scope),
--      after sealing yesterday (v_today - 1), refresh today's completion for
--      users who already have activity on v_today (00:00-00:05 race window).
--   4. [BUG-5] In get_daily_task_status(p_user_id, p_date), preserve known
--      carry_in_tests in required_tests and carryover_remaining_tests when
--      v_carryover_ready = true even if v_history_ready = false.
-- ============================================================================

-- ============================================================================
-- 1. get_daily_task_status: preserve known carry_in_tests in required_tests
--    when predecessor day is finalized (v_carryover_ready = true)
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
        -- [BUG-5] When v_carryover_ready is true (predecessor day is sealed)
        -- but v_history_ready is false, preserve known carry_in_tests in
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
-- 2. refresh_daily_completion: sync unfinalized row & restore daily_missed_streak
--    when today's task becomes unmet (e.g. after report rejection)
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
    v_carry_ready boolean;
    v_prev_streak integer;
begin
    if p_user_id is null then
        return;
    end if;

    -- Chỉ đặt lại hoặc khôi phục chuỗi hiện tại khi đánh giá đúng ngày hôm nay.
    -- Tránh checkin/backfill của ngày cũ vô tình xoá chuỗi hiện tại.
    if v_date <> v_today then
        return;
    end if;

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
        -- Nếu nhiệm vụ hôm nay chưa đạt (hoặc vừa bị từ chối báo cáo làm met tụt về false),
        -- khôi phục daily_missed_streak về giá trị đã chốt gần nhất trước hôm nay (nếu đã có bản ghi chốt).
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


-- ============================================================================
-- 2b. [NEW-BUG-1] Ensure journal triggers refresh daily completion AFTER
--     writing to daily_test_events_journal (fixes trigger execution order on
--     checkins where on_checkin_reward_trigger fired before trg_journal_checkin_event_trg)
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

        perform public.refresh_daily_completion(NEW.user_id, v_act_date);
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
            perform public.refresh_daily_completion(OLD.user_id, v_act_date);
        end if;
        return OLD;
    end if;
    return null;
end;
$function$;

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

        perform public.refresh_daily_completion(NEW.user_id, NEW.checkin_date);
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
            perform public.refresh_daily_completion(OLD.user_id, OLD.checkin_date);
        end if;
        return OLD;
    end if;
    return null;
end;
$function$;


-- ============================================================================
-- 3. _apply_daily_inactivity_penalty: refresh today's completion after sealing
--    yesterday for users with early-morning activity on v_today (00:00-00:05)
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
        select p.id, p.credits, p.daily_missed_streak, lower(p.role) as role, p.created_at, p.last_login_date
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

        -- [BUG-3] If we just sealed yesterday (v_today - 1) and the user already
        -- has activity on v_today (e.g. logged in or tested between 00:00 and 00:05),
        -- re-evaluate v_today so daily_missed_streak and today's unfinalized row
        -- reflect today's completion against the newly finalized carry_in_tests.
        if p_eval = (v_today - 1) and (
            r.last_login_date >= v_today
            or exists (
                select 1 from public.user_daily_tasks udt_today
                where udt_today.user_id = r.id and udt_today.task_date = v_today
            )
            or exists (
                select 1 from public.daily_test_events_journal j_today
                where j_today.user_id = r.id and j_today.activity_date = v_today
            )
        ) then
            perform public.refresh_daily_completion(r.id, v_today);
        end if;
    end loop;
end;
$function$;

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date, date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date, date) TO service_role;


-- ============================================================================
-- 4. [BUG-1] Isolate regular app_test reports from mission reports in
--    get_home_feed, get_campaigns_stats, get_next_untested_campaign, get_my_dashboard
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_home_feed()
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_period_start date;
    v_open jsonb := '[]'::jsonb;
    v_paused jsonb := '[]'::jsonb;
    v_completed jsonb := '[]'::jsonb;
    v_owner_names jsonb := '{}'::jsonb;
    v_stats jsonb := '{}'::jsonb;
begin
    select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
    into v_open
    from public.campaigns c
    where c.status = 'open';

    if v_uid is not null then
        select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
        into v_paused
        from public.campaigns c
        where c.status = 'paused';

        select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
        into v_completed
        from public.campaigns c
        where c.status in ('production', 'completed');
    end if;

    select coalesce(jsonb_object_agg(t.owner_id::text, t.name), '{}'::jsonb)
    into v_owner_names
    from (
        select distinct c.owner_id,
               coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Chủ app') as name
        from public.campaigns c
        left join public.profiles p on p.id = c.owner_id
        where c.status = 'open'
           or (v_uid is not null and c.status in ('paused', 'production', 'completed'))
    ) t;

    -- Xác định mốc đầu chu kỳ hiện tại
    select period_start into v_period_start from public._get_home_stats_period(v_today);

    select coalesce(jsonb_object_agg(s.id::text, jsonb_build_object(
        'installed_count', coalesce(s.installed_count, 0),
        'today_tested_count', coalesce(s.today_tested_count, 0),
        'has_tested_today', coalesce(s.has_tested_today, false),
        'home_tested_count', coalesce(s.home_tested_count, 0),
        'stats_date', v_today::text
    )), '{}'::jsonb)
    into v_stats
    from (
        select c.id,
            (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed_count,
            (select count(distinct ci.user_id) from public.checkins ci
              where ci.campaign_id = c.id and ci.checkin_date = v_today) as today_tested_count,
            (select count(*) from public.checkins ci_period
              where ci_period.campaign_id = c.id
                and ci_period.checkin_date >= v_period_start
                and ci_period.checkin_date <= v_today) as home_tested_count,
            (v_uid is not null and (
                exists (select 1 from public.checkins ci2
                        where ci2.campaign_id = c.id and ci2.user_id = v_uid and ci2.checkin_date = v_today)
                or exists (select 1 from public.tester_reports tr
                           where tr.campaign_id = c.id and tr.user_id = v_uid
                             and coalesce(tr.submission_context, 'app_test') = 'app_test'
                             and tr.status <> 'rejected'
                             and coalesce(tr.report_date, (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date) = v_today)
            )) as has_tested_today
        from public.campaigns c
        where c.status = 'open'
           or (v_uid is not null and c.status in ('paused', 'production', 'completed'))
    ) s;

    return jsonb_build_object(
        'open', v_open,
        'paused', v_paused,
        'completed', v_completed,
        'owner_names', v_owner_names,
        'stats', v_stats
    );
end;
$function$;


CREATE OR REPLACE FUNCTION public.get_campaigns_stats(p_campaign_ids uuid[])
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_period_start date;
begin
    if p_campaign_ids is null or array_length(p_campaign_ids, 1) is null then
        return '{}'::jsonb;
    end if;

    select period_start into v_period_start from public._get_home_stats_period(v_today);

    return (
        select coalesce(jsonb_object_agg(cid::text, jsonb_build_object(
            'installed_count', coalesce(t.installed, 0),
            'today_tested_count', coalesce(t.today_tested, 0),
            'has_tested_today', coalesce(t.has_tested, false),
            'home_tested_count', coalesce(t.home_tested, 0),
            'stats_date', v_today::text
        )), '{}'::jsonb)
        from unnest(p_campaign_ids) as cid
        left join (
            select c.id,
                (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed,
                (select count(distinct ci.user_id) from public.checkins ci
                  where ci.campaign_id = c.id and ci.checkin_date = v_today) as today_tested,
                (select count(*) from public.checkins ci_period
                  where ci_period.campaign_id = c.id
                    and ci_period.checkin_date >= v_period_start
                    and ci_period.checkin_date <= v_today) as home_tested,
                (v_uid is not null and (
                    exists (select 1 from public.checkins ci2
                            where ci2.campaign_id = c.id and ci2.user_id = v_uid and ci2.checkin_date = v_today)
                    or exists (select 1 from public.tester_reports tr
                               where tr.campaign_id = c.id and tr.user_id = v_uid
                                 and coalesce(tr.submission_context, 'app_test') = 'app_test'
                                 and tr.status <> 'rejected'
                                 and coalesce(tr.report_date, (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date) = v_today)
                )) as has_tested
            from public.campaigns c
            where c.id = any(p_campaign_ids)
        ) t on t.id = cid
    );
end;
$function$;


CREATE OR REPLACE FUNCTION public.get_next_untested_campaign(p_current_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp public.campaigns%rowtype;
begin
    select c.* into v_camp
    from public.campaigns c
    where c.status = 'open'
      and (p_current_id is null or c.id <> p_current_id)
      and (v_uid is null or c.owner_id <> v_uid)
      and not exists (
          select 1 from public.checkins ci
          where ci.campaign_id = c.id
            and ci.user_id = v_uid
            and ci.checkin_date = v_today
      )
      and not exists (
          select 1 from public.tester_reports tr
          where tr.campaign_id = c.id
            and tr.user_id = v_uid
            and coalesce(tr.submission_context, 'app_test') = 'app_test'
            and tr.status <> 'rejected'
            and coalesce(tr.report_date, (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date) = v_today
      )
    order by c.created_at desc
    limit 1;

    return jsonb_build_object(
        'campaign', case
            when v_camp.id is null then null
            else public._campaign_json(v_camp)
        end
    );
end;
$function$;


CREATE OR REPLACE FUNCTION public.get_my_dashboard(p_include_leaderboard boolean DEFAULT true)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_ago date := v_today - 6;
    v_profile jsonb;
    v_my_campaigns jsonb := '[]'::jsonb;
    v_joined jsonb := '[]'::jsonb;
    v_leaderboard jsonb := '[]'::jsonb;
    v_today_checkins int := 0;
    v_pending int := 0;
    v_unread jsonb := '[]'::jsonb;
    v_weekly int := 0;
    v_logs jsonb := '[]'::jsonb;
    v_packages jsonb := '[]'::jsonb;
    v_notifications jsonb := '[]'::jsonb;
    v_owner_stats jsonb := '{}'::jsonb;
    v_slots jsonb := '[]'::jsonb;
    v_quest jsonb := null;
    v_daily jsonb := null;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Mọi lần mở Trang chủ/Hồ sơ đều được tính là đăng nhập trong ngày.
    -- Không gọi get_my_dashboard từ background worker.
    perform public.record_daily_login_internal(v_uid, v_today);

    select public._profile_json(p) into v_profile
    from public.profiles p where p.id = v_uid;

    select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
    into v_my_campaigns
    from public.campaigns c
    where c.owner_id = v_uid;

    select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
    into v_joined
    from public.campaigns c
    join public.campaign_members m on m.campaign_id = c.id and m.user_id = v_uid
    where c.owner_id <> v_uid;

    -- Bảng xếp hạng: top 100 theo credits (khớp thuật toán cũ của app).
    -- Chỉ tính khi client yêu cầu (tab Bảng xếp hạng mở).
    if p_include_leaderboard then
        select coalesce(jsonb_agg(jsonb_build_object(
            'userId', t.id,
            'displayName', t.name,
            'avatarUrl', t.avatar_url,
            'credits', t.credits,
            'checkinCount', t.checkin_count,
            'campaignsCount', t.campaigns_count,
            'rank', t.rn
        ) order by t.rn), '[]'::jsonb)
        into v_leaderboard
        from (
            select b.*, row_number() over (order by b.credits desc, b.checkin_count desc, b.campaigns_count desc) as rn
            from (
                select p.id,
                       coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Tester') as name,
                       p.avatar_url,
                       p.credits,
                       (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                       (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
                from public.profiles p
                order by p.credits desc
                limit 100
            ) b
        ) t;
    end if;

    -- [BUG-1] Số app đã test hôm nay: checkins + báo cáo app_test pending/approved trong ngày
    select count(distinct campaign_id) into v_today_checkins
    from (
        select campaign_id from public.checkins
        where user_id = v_uid and checkin_date = v_today
        union
        select campaign_id from public.tester_reports
        where user_id = v_uid
          and coalesce(submission_context, 'app_test') = 'app_test'
          and coalesce(report_date, (created_at at time zone 'Asia/Ho_Chi_Minh')::date) = v_today
          and status in ('pending', 'approved')
    ) t;

    -- Báo cáo đang chờ duyệt trên toàn bộ app của tôi
    select count(*) into v_pending
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where c.owner_id = v_uid and tr.status = 'pending';

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
        'warning_type', w.warning_type,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) order by w.created_at desc), '[]'::jsonb)
    into v_unread
    from public.user_warnings w
    where w.user_id = v_uid and w.acknowledged = false
      and not exists (
          select 1 from public.user_warning_revocations r
          where r.warning_id = w.id
      );

    select count(*) into v_weekly
    from public.user_warnings w
    where w.user_id = v_uid and w.warning_date >= v_week_ago
      and not exists (
          select 1 from public.user_warning_revocations r
          where r.warning_id = w.id
      );

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', l.id,
        'user_id', l.user_id,
        'amount', l.amount,
        'action', l.action,
        'note', l.note,
        'balance', l.balance,
        'created_at', to_char(l.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by l.created_at desc), '[]'::jsonb)
    into v_logs
    from (
        select * from public.credit_logs
        where user_id = v_uid
        order by created_at desc
        limit 20
    ) l;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', tp.id,
        'title', tp.title,
        'credits', tp.credits,
        'bonus_credits', tp.bonus_credits,
        'price_usd', tp.price_usd,
        'price_vnd', tp.price_vnd,
        'badge_text', tp.badge_text,
        'is_popular', tp.is_popular,
        'is_active', tp.is_active,
        'sort_order', tp.sort_order
    ) order by tp.sort_order asc), '[]'::jsonb)
    into v_packages
    from public.topup_packages tp
    where tp.is_active = true;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', n.id,
        'user_id', n.user_id,
        'title', n.title,
        'message', n.message,
        'type', n.type,
        'data', coalesce(n.data, '{}'::jsonb),
        'is_read', n.is_read,
        'created_at', to_char(n.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by n.created_at desc), '[]'::jsonb)
    into v_notifications
    from (
        select * from public.notifications
        where user_id = v_uid
        order by created_at desc
        limit 50
    ) n;

    -- Thống kê cho từng app của chủ app hiện tại
    select coalesce(jsonb_object_agg(s.campaign_id::text, jsonb_build_object(
        'installedTestersCount', s.installed,
        'testedTodayCount', s.tested_today,
        'totalReportsCount', s.total_reports,
        'pendingReportsCount', s.pending_reports,
        'averageRating', s.avg_rating
    )), '{}'::jsonb)
    into v_owner_stats
    from (
        select c.id as campaign_id,
            (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed,
            (select count(distinct u.user_id) from (
                select ci.user_id from public.checkins ci
                where ci.campaign_id = c.id and ci.checkin_date = v_today
                union
                select tr.user_id from public.tester_reports tr
                where tr.campaign_id = c.id
                  and coalesce(tr.submission_context, 'app_test') = 'app_test'
                  and tr.status <> 'rejected'
                  and coalesce(tr.report_date, (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date) = v_today
            ) u) as tested_today,
            greatest(
                (select count(*) from public.tester_reports tr2 where tr2.campaign_id = c.id),
                (select count(*) from public.checkins ci2 where ci2.campaign_id = c.id)
            ) as total_reports,
            (select count(*) from public.tester_reports tr3
              where tr3.campaign_id = c.id and tr3.status = 'pending') as pending_reports,
            (select avg(f.rating)::float8 from public.feedbacks f where f.campaign_id = c.id) as avg_rating
        from public.campaigns c
        where c.owner_id = v_uid
    ) s;

    -- Slot đã mở của người dùng (bỏ 1-2 round-trip riêng cho màn Profile/Tạo app)
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', s.id,
        'user_id', s.user_id,
        'slot_number', s.slot_number,
        'unlocked_at', to_char(s.unlocked_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'expires_at', to_char(s.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(s.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by s.slot_number asc), '[]'::jsonb)
    into v_slots
    from public.user_slots s
    where s.user_id = v_uid;

    begin
        v_quest := public.get_user_quest_summary();
    exception when others then
        v_quest := null;
    end;

    v_daily := public.get_daily_task_status(v_uid, v_today);

    return jsonb_build_object(
        'profile', v_profile,
        'my_campaigns', v_my_campaigns,
        'joined_campaigns', v_joined,
        'leaderboard', v_leaderboard,
        'today_checkin_count', v_today_checkins,
        'pending_reports_count', v_pending,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly,
        'credit_logs', v_logs,
        'topup_packages', v_packages,
        'notifications', v_notifications,
        'owner_stats', v_owner_stats,
        'slots', v_slots,
        'quest_summary', v_quest,
        'daily_task', v_daily
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_my_dashboard(boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_my_dashboard(boolean) TO authenticated, service_role;


-- ============================================================================
-- 5. [BUG-2] _submit_tester_report_core & _impl_reject_tester_report:
--    restore provisional checkins on TH2 resubmit and refresh_daily_completion
-- ============================================================================

CREATE OR REPLACE FUNCTION public._submit_tester_report_core(
    p_submission_context text,
    p_campaign_id uuid,
    p_content text,
    p_rating integer,
    p_client_submit_id uuid,
    p_screenshot_url text,
    p_image_hash text,
    p_thumbnail_url text,
    p_mission_id uuid,
    p_attachments jsonb DEFAULT NULL,
    p_expected_date date DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_report_id uuid;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp record;
    v_existing_report record;
    v_idempotent_report record;
    v_receipt record;
    v_fingerprint text;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_provisional_amount int := 0;
    v_provisional_reward int := 10;
    v_policy public.business_policies;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_effective_full text;
    v_final_url text;
    v_final_hash text;
    v_final_thumb text;

    -- Mission context variables
    v_mission record;
    v_mp record;
    v_submission_context text;
    v_target_mission_id uuid := null;
    v_target_mp_id uuid := null;
    v_target_title text := null;
    v_target_reward int := null;
    v_target_ends_at timestamptz := null;
    v_target_reward_mode text := null;
    v_res_context text;
    v_res_mission_id uuid;
    v_res_title text;
    v_res_reward int;
    v_res_ends_at timestamptz;
    v_res_reward_mode text;
    v_result jsonb;

    -- Attachments variables
    v_att_canonical text := '';
    v_att_count int := 0;
    v_att_item jsonb;
    v_att_elem record;
    v_attachments_json jsonb := '[]'::jsonb;
    v_pos int;
begin
    v_policy := public._get_effective_business_policy_row(v_today);
    v_provisional_reward := v_policy.report_provisional_reward;

    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Strict explicit submission context
    if p_submission_context is null or p_submission_context not in ('app_test', 'mission')
       or (p_submission_context = 'mission') <> (p_mission_id is not null) then
        raise exception 'INVALID_REPORT_CONTEXT';
    end if;
    v_submission_context := p_submission_context;

    -- Advisory lock for client submission idempotency
    if p_client_submit_id is not null then
        perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    end if;

    if p_client_submit_id is not null and exists(select 1 from public.report_cancelled_requests where user_id=v_user_id and client_submit_id=p_client_submit_id) then
        raise exception 'REPORT_REQUEST_REQUIRES_REVIEW';
    end if;

    -- Validate attachments array if provided
    if p_attachments is not null then
        if p_client_submit_id is null then
            raise exception 'CLIENT_SUBMIT_ID_REQUIRED';
        end if;
        if jsonb_typeof(p_attachments) <> 'array' then
            raise exception 'ATTACHMENTS_MUST_BE_ARRAY';
        end if;
        v_att_count := jsonb_array_length(p_attachments);
        if v_att_count > 3 then
            raise exception 'TOO_MANY_ATTACHMENTS';
        end if;
        if v_att_count < 1 then
            raise exception 'ATTACHMENTS_EMPTY: attachments array cannot be empty';
        end if;

        for v_pos in 0 .. (v_att_count - 1) loop
            v_att_item := p_attachments->v_pos;
            if coalesce((v_att_item->>'position')::int, -1) <> v_pos then
                raise exception 'ATTACHMENT_POSITION_INVALID';
            end if;
            if v_att_item->>'screenshot_url' is null or btrim(v_att_item->>'screenshot_url') = '' then
                raise exception 'SCREENSHOT_URL_REQUIRED';
            end if;
            if not public.is_valid_report_screenshot_url(v_att_item->>'screenshot_url', p_campaign_id, v_user_id) then
                raise exception 'INVALID_SCREENSHOT_URL';
            end if;
            if v_att_item->>'image_hash' is null or btrim(v_att_item->>'image_hash') = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            if not (lower(v_att_item->>'image_hash') ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
            if v_att_item->>'thumbnail_url' is not null and btrim(v_att_item->>'thumbnail_url') <> '' then
                if not public.is_valid_report_thumbnail_url(v_att_item->>'thumbnail_url', p_campaign_id, v_user_id, v_att_item->>'screenshot_url') then
                    raise exception 'INVALID_THUMBNAIL_URL';
                end if;
            end if;
        end loop;

        select jsonb_agg(jsonb_build_object(
            'id', att->>'id',
            'position', (att->>'position')::int,
            'screenshot_url', att->>'screenshot_url',
            'thumbnail_url', att->>'thumbnail_url',
            'image_hash', lower(att->>'image_hash'),
            'sha256', att->>'sha256'
        ) order by (att->>'position')::int)::text
        into v_att_canonical
        from jsonb_array_elements(p_attachments) as att;
    end if;

    -- Validate single screenshot if provided without attachments
    if p_attachments is null and p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
        if not public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) then
            raise exception 'INVALID_SCREENSHOT_URL';
        end if;
        if p_image_hash is not null and btrim(p_image_hash) <> '' then
            if not (lower(p_image_hash) ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
        end if;
        if p_thumbnail_url is not null and btrim(p_thumbnail_url) <> '' then
            if not public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, p_screenshot_url) then
                raise exception 'INVALID_THUMBNAIL_URL';
            end if;
        end if;
    end if;

    -- Compute payload fingerprint
    if p_attachments is not null then
        v_fingerprint := encode(digest(format('v3|%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            v_submission_context,
            coalesce(p_mission_id::text, ''),
            coalesce(p_content, ''),
            v_rating,
            coalesce(v_att_canonical, ''),
            coalesce(p_expected_date::text, '')
        ), 'sha256'), 'hex');
    else
        v_fingerprint := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            coalesce(p_content, ''),
            coalesce(p_screenshot_url, ''),
            coalesce(p_rating, 5),
            coalesce(lower(p_image_hash), ''),
            coalesce(p_thumbnail_url, ''),
            coalesce(p_mission_id::text, '')
        ), 'sha256'), 'hex');
    end if;

    -- Idempotency check via receipt ledger
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;

            if v_receipt.provenance = 'legacy_backfill' then
                declare
                    v_resp_mission text := coalesce(v_receipt.response_json ->> 'mission_id', '');
                    v_resp_url text := coalesce(v_receipt.response_json ->> 'screenshot_url', '');
                    v_resp_hash text := coalesce(lower(v_receipt.response_json ->> 'image_hash'), '');
                    v_resp_thumb text := coalesce(v_receipt.response_json ->> 'thumbnail_url', '');
                    v_resp_status text := coalesce(v_receipt.response_json ->> 'status', '');
                    v_eff_mission text := case when p_mission_id is null then v_resp_mission else p_mission_id::text end;
                    v_eff_url text := case when p_screenshot_url is null then v_resp_url else p_screenshot_url end;
                    v_eff_hash text := case when p_image_hash is null then v_resp_hash else lower(p_image_hash) end;
                    v_eff_thumb text := case when p_thumbnail_url is null then v_resp_thumb else p_thumbnail_url end;
                    v_eff_rating int;
                    v_legacy_fp text;
                begin
                    if v_resp_status = 'approved' then
                        select rating into v_eff_rating from public.tester_reports where id = v_receipt.report_id;
                        if v_eff_rating is null then
                            v_eff_rating := coalesce((v_receipt.response_json ->> 'rating')::int, 5);
                        end if;
                    else
                        v_eff_rating := coalesce(p_rating, 5);
                    end if;

                    if (p_screenshot_url is null or p_screenshot_url = v_resp_url)
                       and (p_image_hash is null or lower(p_image_hash) = v_resp_hash)
                       and (p_thumbnail_url is null or p_thumbnail_url = v_resp_thumb)
                       and (p_mission_id is null or p_mission_id::text = v_resp_mission)
                       and (v_resp_status = 'approved' or p_rating is null or p_rating = v_eff_rating)
                    then
                        v_legacy_fp := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                            p_campaign_id,
                            coalesce(p_content, ''),
                            v_eff_url,
                            v_eff_rating,
                            v_eff_hash,
                            v_eff_thumb,
                            v_eff_mission
                        ), 'sha256'), 'hex');

                        if v_receipt.payload_fingerprint = v_legacy_fp then
                            return v_receipt.response_json;
                        end if;
                    end if;
                end;
            end if;

            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;

        -- Fallback check tester_reports for legacy submissions
        select * into v_idempotent_report
        from public.tester_reports
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_idempotent_report.id is not null then
            if v_idempotent_report.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if p_mission_id is not null and v_idempotent_report.mission_id is distinct from p_mission_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if coalesce(v_idempotent_report.content, '') <> coalesce(p_content, '') then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_rating is not null and v_idempotent_report.rating is not null and v_idempotent_report.rating <> v_rating then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_image_hash is not null and v_idempotent_report.image_hash is not null and lower(v_idempotent_report.image_hash) <> lower(p_image_hash) then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_screenshot_url is not null and v_idempotent_report.screenshot_url is not null and v_idempotent_report.screenshot_url <> p_screenshot_url then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;

            select coalesce(jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256
            ) order by a.position), '[]'::jsonb) into v_attachments_json
            from public.tester_report_attachments a
            where a.report_id = v_idempotent_report.id;

            if jsonb_array_length(v_attachments_json) = 0 and v_idempotent_report.screenshot_url is not null then
                v_attachments_json := jsonb_build_array(jsonb_build_object(
                    'id', v_idempotent_report.id,
                    'position', 0,
                    'screenshot_url', v_idempotent_report.screenshot_url,
                    'thumbnail_url', v_idempotent_report.thumbnail_url,
                    'image_hash', v_idempotent_report.image_hash,
                    'sha256', null
                ));
            end if;

            v_result := jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_idempotent_report.screenshot_url,
                'image_hash', v_idempotent_report.image_hash,
                'thumbnail_url', v_idempotent_report.thumbnail_url,
                'submission_context', v_idempotent_report.submission_context,
                'mission_id', v_idempotent_report.mission_id,
                'mission_title', v_idempotent_report.mission_title,
                'mission_reward_credits', v_idempotent_report.mission_reward_credits,
                'mission_ends_at', to_char(v_idempotent_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_reward_mode', v_idempotent_report.mission_reward_mode,
                'rating', v_idempotent_report.rating,
                'report_date', to_char(v_idempotent_report.report_date, 'YYYY-MM-DD'),
                'attachments', v_attachments_json
            );

            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_idempotent_report.id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;

            return v_result;
        end if;
    end if;

    -- Date precondition check for uncommitted submissions
    if p_expected_date is not null and p_expected_date <> v_today then
        -- Cho phép nộp qua nửa đêm đối với nhiệm vụ once còn hiệu lực
        if v_submission_context = 'mission' and exists (
            select 1 from public.missions
            where id = p_mission_id and campaign_id = p_campaign_id and reward_mode = 'once' and status in ('open', 'full') and ends_at > now()
        ) then
            null;
        else
            raise exception 'REPORT_DATE_MISMATCH: Ngay bao cao du kien khong khop voi may chu';
        end if;
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'USER_LOCKED';
    end if;

    -- Mission context allows open and finalized campaigns ('production', 'completed').
    -- Normal app_test context strictly requires 'open'.
    if v_submission_context = 'mission' then
        if v_camp.status not in ('open', 'production', 'completed') then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    else
        if v_camp.status <> 'open' then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    end if;

    -- Lock contextually by (user, campaign, context, mission, date)
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s:%s:%s', v_user_id, p_campaign_id, v_submission_context, coalesce(p_mission_id::text, ''), v_today), 0)
    );

    -- Re-check receipt ledger under lock
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;
            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;
    end if;

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lookup existing report (khóa báo cáo trước nếu đã tồn tại để tương thích thứ tự khóa với review/reject):
    if v_submission_context = 'mission' then
        select reward_mode into v_target_reward_mode from public.missions where id = p_mission_id;
        if v_target_reward_mode = 'once' then
            select * into v_existing_report
            from public.tester_reports
            where campaign_id = p_campaign_id
              and user_id = v_user_id
              and submission_context = 'mission'
              and mission_id = p_mission_id
            order by created_at desc
            limit 1
            for update;
        else
            select * into v_existing_report
            from public.tester_reports
            where campaign_id = p_campaign_id
              and user_id = v_user_id
              and submission_context = 'mission'
              and mission_id = p_mission_id
              and report_date = v_today
            order by created_at desc
            limit 1
            for update;
        end if;

        -- Khóa hàng mission và participant dưới khóa để chống race condition với close/edit/delete/refund
        select * into v_mission from public.missions
        where id = p_mission_id and campaign_id = p_campaign_id
        for update;

        if v_mission.id is null then
            raise exception 'MISSION_NOT_FOUND';
        end if;

        if v_mission.status not in ('open', 'full') then
            raise exception '%', case v_mission.status
                when 'paused_by_creator' then 'MISSION_PAUSED'
                when 'stopped_no_credits' then 'MISSION_STOPPED_NO_CREDITS'
                when 'expired' then 'MISSION_EXPIRED'
                when 'deleted' then 'MISSION_DELETED'
                else 'MISSION_NOT_OPEN' end;
        end if;

        if v_mission.ends_at <= now() then
            raise exception 'MISSION_EXPIRED';
        end if;

        select * into v_mp from public.mission_participants
        where mission_id = v_mission.id and user_id = v_user_id
        for update;

        if v_mp.id is null or v_mp.joined_at > now() then
            raise exception 'MISSION_NOT_JOINED';
        end if;

        if v_mission.reward_mode = 'daily' then
            if v_today < (v_mp.joined_at at time zone 'Asia/Ho_Chi_Minh')::date then
                raise exception 'MISSION_NOT_JOINED';
            end if;
        end if;

        v_target_mission_id := v_mission.id;
        v_target_mp_id := v_mp.id;
        v_target_title := v_mission.title;
        v_target_reward := v_mission.reward_credits;
        v_target_ends_at := v_mission.ends_at;
        v_target_reward_mode := v_mission.reward_mode;
    else
        select * into v_existing_report
        from public.tester_reports
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and submission_context = 'app_test'
          and report_date = v_today
        order by created_at desc
        limit 1
        for update;
    end if;

    -- Backfill legacy receipt before client_submit_id gets overwritten
    if v_existing_report.id is not null
       and v_existing_report.client_submit_id is not null
       and v_existing_report.client_submit_id is distinct from p_client_submit_id then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id,
            v_existing_report.client_submit_id,
            p_campaign_id,
            v_existing_report.id,
            encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                p_campaign_id,
                coalesce(v_existing_report.content, ''),
                coalesce(v_existing_report.screenshot_url, ''),
                coalesce(v_existing_report.rating, 5),
                coalesce(lower(v_existing_report.image_hash), ''),
                coalesce(v_existing_report.thumbnail_url, ''),
                coalesce(v_existing_report.mission_id::text, '')
            ), 'sha256'), 'hex'),
            jsonb_build_object(
                'success', true,
                'report_id', v_existing_report.id,
                'status', v_existing_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_existing_report.screenshot_url,
                'image_hash', v_existing_report.image_hash,
                'thumbnail_url', v_existing_report.thumbnail_url,
                'submission_context', v_existing_report.submission_context,
                'mission_id', v_existing_report.mission_id,
                'mission_title', v_existing_report.mission_title,
                'mission_reward_credits', v_existing_report.mission_reward_credits,
                'mission_ends_at', to_char(v_existing_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_reward_mode', v_existing_report.mission_reward_mode,
                'rating', v_existing_report.rating,
                'report_date', to_char(v_existing_report.report_date, 'YYYY-MM-DD')
            ),
            v_existing_report.created_at,
            'legacy_backfill'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    -- Disallow mutating an existing report to another context or mission
    if v_existing_report.id is not null and (
        v_existing_report.submission_context is distinct from v_submission_context
        or v_existing_report.mission_id is distinct from p_mission_id
    ) then
        raise exception 'REPORT_CONTEXT_CONFLICT';
    end if;

    -- TH1: REPORT IS APPROVED -> comment update only (preserving original report_date and photos)
    if v_existing_report.id is not null and v_existing_report.status = 'approved' then
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;
        if p_screenshot_url is not null and p_screenshot_url <> v_existing_report.screenshot_url then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (
            p_campaign_id, v_user_id, coalesce(v_existing_report.rating, v_rating),
            coalesce(p_content, ''), now()
        )
        on conflict (campaign_id, user_id)
        do update set
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        update public.tester_reports
        set content = case when coalesce(p_content, '') <> '' then p_content else content end,
            client_submit_id = coalesce(p_client_submit_id, client_submit_id)
        where id = v_existing_report.id
          and status = 'approved'
        returning id into v_report_id;

        if v_report_id is null then
            raise exception 'REPORT_ALREADY_REVIEWED';
        end if;
        v_status := 'approved';

        update public.profiles
        set last_active_date = v_today, missed_streak = 0
        where id = v_user_id;

        if v_submission_context = 'app_test' then
            perform public.refresh_daily_completion(v_user_id, v_today);
        end if;

        select
            submission_context, mission_id, mission_title,
            mission_reward_credits, mission_ends_at, mission_reward_mode,
            screenshot_url, image_hash, thumbnail_url
        into
            v_res_context, v_res_mission_id, v_res_title,
            v_res_reward, v_res_ends_at, v_res_reward_mode,
            v_final_url, v_final_hash, v_final_thumb
        from public.tester_reports
        where id = v_report_id;

        select coalesce(jsonb_agg(jsonb_build_object(
            'id', a.id,
            'position', a.position,
            'screenshot_url', a.screenshot_url,
            'thumbnail_url', a.thumbnail_url,
            'image_hash', a.image_hash,
            'sha256', a.sha256
        ) order by a.position), '[]'::jsonb) into v_attachments_json
        from public.tester_report_attachments a
        where a.report_id = v_report_id;

        if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
            v_attachments_json := jsonb_build_array(jsonb_build_object(
                'id', v_report_id,
                'position', 0,
                'screenshot_url', v_final_url,
                'thumbnail_url', v_final_thumb,
                'image_hash', v_final_hash,
                'sha256', null
            ));
        end if;

        v_result := jsonb_build_object(
            'success', true,
            'report_id', v_report_id,
            'status', v_status,
            'provisional_granted', false,
            'provisional_amount', 0,
            'screenshot_url', v_final_url,
            'image_hash', v_final_hash,
            'thumbnail_url', v_final_thumb,
            'submission_context', v_res_context,
            'mission_id', v_res_mission_id,
            'mission_title', v_res_title,
            'mission_reward_credits', v_res_reward,
            'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'mission_reward_mode', v_res_reward_mode,
            'rating', coalesce(v_existing_report.rating, v_rating),
            'report_date', to_char(v_existing_report.report_date, 'YYYY-MM-DD'),
            'attachments', v_attachments_json
        );

        if p_client_submit_id is not null then
            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;
        end if;

        return v_result;
    end if;

    -- TH2: EXISTING REPORT IS PENDING OR REJECTED
    if v_existing_report.id is not null then
        if v_existing_report.submission_context = 'mission' then
            if p_mission_id is not null and v_existing_report.mission_id is distinct from p_mission_id then
                raise exception 'CANNOT_CHANGE_MISSION_IDENTITY';
            end if;
            v_submission_context := 'mission';
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
            v_target_reward_mode := coalesce(v_existing_report.mission_reward_mode, v_mission.reward_mode);

            if v_existing_report.status = 'rejected' then
                if v_target_ends_at is not null and v_target_ends_at < now() then
                    raise exception 'MISSION_EXPIRED';
                end if;
                -- Với bản đầu, sửa/nộp lại lượt hàng ngày trong chính ngày đó và trước hạn
                if v_target_reward_mode = 'daily' and v_existing_report.report_date <> v_today then
                    raise exception 'DAILY_REPORT_RESUBMIT_WINDOW_EXPIRED: Lượt hàng ngày chỉ được sửa hoặc nộp lại trong ngày báo cáo';
                end if;
            end if;
        else
            if p_mission_id is not null then
                raise exception 'CANNOT_CONVERT_EXISTING_PENDING_REPORT';
            end if;
            v_submission_context := 'app_test';
        end if;

        -- Image resolution for pending/rejected update
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_new_screenshot := true;
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
            v_final_thumb := p_attachments->0->>'thumbnail_url';

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = v_effective_full then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_new_screenshot := true;
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_hash := lower(p_image_hash);
            v_final_thumb := p_thumbnail_url;

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = p_screenshot_url then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        else
            v_effective_full := v_existing_report.screenshot_url;
            v_final_hash := v_existing_report.image_hash;
            v_final_thumb := v_existing_report.thumbnail_url;
            if v_existing_report.status = 'rejected' then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_existing_report.status = 'rejected' then
            v_regrant := coalesce(v_existing_report.provisional_granted_credits, v_provisional_reward);
            if v_existing_report.provisional_clawed > 0 then
                update public.profiles
                set credits = credits + v_regrant,
                    last_active_date = v_today,
                    missed_streak = 0
                where id = v_user_id
                returning credits into v_new_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_user_id, v_regrant, 'test_provisional',
                    format('Tạm cấp lại %s Credit sau khi nộp lại kết quả test', v_regrant),
                    v_new_balance
                );

                v_provisional_granted := true;
                v_provisional_amount := v_regrant;
            else
                v_provisional_granted := false;
                v_provisional_amount := 0;
            end if;

            -- [BUG-2] Restore provisional checkin row for app_test resubmission
            if v_submission_context = 'app_test' then
                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do nothing;
            end if;

            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                status = 'pending',
                rejection_reason = null,
                reviewed_at = null,
                provisional_clawed = 0,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                created_at = now(),
                mission_submitted_at = case when v_submission_context = 'mission' then now() else mission_submitted_at end,
                mission_reward_mode = coalesce(mission_reward_mode, v_target_reward_mode)
            where id = v_existing_report.id
            returning id into v_report_id;
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                mission_reward_mode = coalesce(mission_reward_mode, v_target_reward_mode)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Synchronize attachments table
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            delete from public.tester_report_attachments where report_id = v_report_id;
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        elsif v_new_screenshot then
            if exists (select 1 from public.tester_report_attachments where report_id = v_report_id and position = 0) then
                update public.tester_report_attachments
                set screenshot_url = v_effective_full,
                    thumbnail_url = v_final_thumb,
                    image_hash = v_final_hash,
                    sha256 = null,
                    created_at = now()
                where report_id = v_report_id and position = 0;
            else
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    gen_random_uuid(),
                    v_report_id,
                    0,
                    v_effective_full,
                    v_final_thumb,
                    v_final_hash,
                    null,
                    now()
                );
            end if;
        end if;

        v_status := 'pending';
    else
        -- TH3: BRAND NEW REPORT
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_thumb := p_attachments->0->>'thumbnail_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_thumb := p_thumbnail_url;
            v_final_hash := lower(p_image_hash);
        else
            raise exception 'SCREENSHOT_URL_REQUIRED';
        end if;

        if v_submission_context = 'mission' then
            v_target_mission_id := v_mission.id;
            v_target_mp_id := v_mp.id;
            v_target_title := v_mission.title;
            v_target_reward := v_mission.reward_credits;
            v_target_ends_at := v_mission.ends_at;
            v_target_reward_mode := v_mission.reward_mode;
        else
            v_target_mission_id := null;
            v_target_mp_id := null;
            v_target_title := null;
            v_target_reward := null;
            v_target_ends_at := null;
            v_target_reward_mode := null;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_submission_context = 'app_test' then
            select exists (
                select 1 from public.checkins
                where campaign_id = p_campaign_id
                  and user_id = v_user_id
                  and checkin_date = v_today
            ) into v_already_checked_in;

            if v_already_checked_in then
                insert into public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    mission_reward_mode,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
                )
                returning id into v_report_id;

                v_status := 'pending';
                v_provisional_granted := false;
                v_provisional_amount := 0;
            else
                if v_provisional_reward > 0 then
                    update public.profiles
                    set credits = credits + v_provisional_reward,
                        last_active_date = v_today,
                        missed_streak = 0
                    where id = v_user_id
                    returning credits into v_new_balance;

                    insert into public.credit_logs (user_id, amount, action, note, balance)
                    values (
                        v_user_id, v_provisional_reward, 'test_provisional',
                        format('Tạm nhận %s Credit sau khi nộp kết quả test (chờ chủ app duyệt)', v_provisional_reward),
                        v_new_balance
                    );

                    v_provisional_granted := true;
                    v_provisional_amount := v_provisional_reward;
                else
                    v_provisional_granted := false;
                    v_provisional_amount := 0;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                insert into public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    mission_reward_mode,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', v_provisional_amount, 0, v_provisional_amount,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
                )
                returning id into v_report_id;

                v_status := 'pending';
            end if;
        else
            -- Mission report does not consume or conflict with ordinary app_test daily checkin
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                mission_reward_mode,
                review_tester_bonus, review_owner_bonus, business_policy_id, report_date
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                v_final_hash, p_client_submit_id, now(),
                'mission', v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at, now(),
                v_target_reward_mode,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Attachments
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        else
            insert into public.tester_report_attachments (
                id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
            ) values (
                gen_random_uuid(),
                v_report_id,
                0,
                v_effective_full,
                v_final_thumb,
                v_final_hash,
                null,
                now()
            );
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today, missed_streak = 0
    where id = v_user_id;

    -- [BUG-2] Refresh daily completion after app_test submission / resubmission
    if v_submission_context = 'app_test' then
        perform public.refresh_daily_completion(v_user_id, v_today);
    end if;

    select
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, mission_reward_mode,
        screenshot_url, image_hash, thumbnail_url, report_date, rating
    into
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_res_reward_mode,
        v_final_url, v_final_hash, v_final_thumb, v_today, v_rating
    from public.tester_reports
    where id = v_report_id;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', a.id,
        'position', a.position,
        'screenshot_url', a.screenshot_url,
        'thumbnail_url', a.thumbnail_url,
        'image_hash', a.image_hash,
        'sha256', a.sha256
    ) order by a.position), '[]'::jsonb) into v_attachments_json
    from public.tester_report_attachments a
    where a.report_id = v_report_id;

    if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
        v_attachments_json := jsonb_build_array(jsonb_build_object(
            'id', v_report_id,
            'position', 0,
            'screenshot_url', v_final_url,
            'thumbnail_url', v_final_thumb,
            'image_hash', v_final_hash,
            'sha256', null
        ));
    end if;

    v_result := jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount,
        'screenshot_url', v_final_url,
        'image_hash', v_final_hash,
        'thumbnail_url', v_final_thumb,
        'submission_context', v_res_context,
        'mission_id', v_res_mission_id,
        'mission_title', v_res_title,
        'mission_reward_credits', v_res_reward,
        'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_reward_mode', v_res_reward_mode,
        'rating', v_rating,
        'report_date', to_char(v_today, 'YYYY-MM-DD'),
        'attachments', v_attachments_json
    );

    if p_client_submit_id is not null then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
            v_fingerprint, v_result, now(), 'exact_v1'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    return v_result;
end;
$function$;

REVOKE ALL ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) TO service_role;


CREATE OR REPLACE FUNCTION public._impl_reject_tester_report(
    p_report_id uuid,
    p_reason text DEFAULT NULL::text,
    p_reason_code text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_report record;
    v_camp record;
    v_tester_credits int;
    v_deduct int := 0;
    v_new_balance int;
    v_report_date date;
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;

    v_extracted_code text;
    v_final_reason_code text;

    v_tester_today_rejections int := 0;
    v_tester_warned boolean := false;
    v_tester_warning_msg text := null;
    v_tester_weekly_warnings int := 0;
    v_is_tester_locked boolean := false;

    v_owner_today_rejections int := 0;
    v_owner_warned boolean := false;
    v_owner_warning_msg text := null;
    v_owner_weekly_warnings int := 0;
    v_is_owner_locked boolean := false;

    v_msg_tester text := 'Hệ thống ghi nhận bạn tham gia kiểm thử chưa nghiêm túc, đề nghị test app nghiêm túc và chụp ảnh màn hình không trùng với ảnh đã chụp hôm trước (nên chụp các tính năng khác của ứng dụng kiểm thử). Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
    v_msg_owner text := 'Hệ thống ghi nhận bạn xác nhận kết quả của người kiểm thử chưa nghiêm túc, đề nghị đối chiếu kỹ ảnh chụp của người kiểm thử chụp các ngày khác nhau để duyệt cho chính xác. Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
begin
    perform set_config('app.reviewing_report', 'true', true);

    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    if v_report.status <> 'pending' then
        raise exception 'REPORT_ALREADY_REVIEWED';
    end if;

    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    if p_reason is not null and p_reason like '[CODE:%]' then
        v_extracted_code := substring(p_reason from '\[CODE:([A-Za-z0-9_]+)\]');
    end if;
    v_final_reason_code := coalesce(p_reason_code, v_extracted_code);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'reject', p_reason
    );

    v_report_date := coalesce(v_report.report_date, (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date);

    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, coalesce(v_report.provisional_granted_credits, case when coalesce(v_report.submission_context, 'app_test') = 'app_test' then 2 else 0 end))
    );

    if v_deduct > 0 then
        update public.profiles
        set credits = credits - v_deduct
        where id = v_report.user_id
        returning credits into v_new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance, metadata)
        values (
            v_report.user_id,
            -v_deduct,
            'test_rejected_clawback',
            format('Thu hồi %s Credit tạm nhận do báo cáo bị từ chối: %s', v_deduct, coalesce(p_reason, 'Chưa đạt yêu cầu')),
            v_new_balance,
            jsonb_build_object('report_id', v_report.id, 'campaign_id', v_camp.id, 'reason_code', v_final_reason_code)
        );
    else
        v_new_balance := coalesce(v_tester_credits, 0);
    end if;

    update public.tester_reports
    set status = 'rejected',
        rejection_reason = p_reason,
        reason_code = v_final_reason_code,
        bonus_awarded = 0,
        provisional_clawed = v_deduct,
        reviewed_at = now()
    where id = p_report_id;

    -- Only ordinary app_test reports touch checkins table and daily completion
    if coalesce(v_report.submission_context, 'app_test') = 'app_test' then
        delete from public.checkins
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = v_report_date
          and coalesce(note, '') like '%tạm tính%';

        perform public.refresh_daily_completion(v_report.user_id, v_report_date);
    end if;

    select count(*) into v_tester_today_rejections
    from public.report_review_events
    where tester_id = v_report.user_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings w
            where w.user_id = v_report.user_id
              and w.warning_type = 'tester_rejection_streak'
              and w.warning_date = v_vn_today
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              )
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date, metadata)
            values (
                v_report.user_id,
                'tester_rejection_streak',
                v_msg_tester,
                v_vn_today,
                jsonb_build_object('warning_type', 'tester_rejection_streak', 'streak_count', v_tester_today_rejections)
            );
            v_tester_warned := true;
            v_tester_warning_msg := v_msg_tester;

            select count(*) into v_tester_weekly_warnings
            from public.user_warnings w
            where w.user_id = v_report.user_id
              and w.warning_date >= (v_vn_today - 6)
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              );

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    select count(*) into v_owner_today_rejections
    from public.report_review_events
    where owner_id = v_camp.owner_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings w
            where w.user_id = v_camp.owner_id
              and w.warning_type = 'owner_rejection_streak'
              and w.warning_date = v_vn_today
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              )
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date, metadata)
            values (
                v_camp.owner_id,
                'owner_rejection_streak',
                v_msg_owner,
                v_vn_today,
                jsonb_build_object('warning_type', 'owner_rejection_streak', 'streak_count', v_owner_today_rejections)
            );
            v_owner_warned := true;
            v_owner_warning_msg := v_msg_owner;

            select count(*) into v_owner_weekly_warnings
            from public.user_warnings w
            where w.user_id = v_camp.owner_id
              and w.warning_date >= (v_vn_today - 6)
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              );

            if v_owner_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_camp.owner_id;
                v_is_owner_locked := true;
            end if;
        end if;
    end if;

    -- Kiểm tra hoàn quỹ nếu là báo cáo nhiệm vụ
    if v_report.mission_id is not null then
        perform public._check_and_refund_mission_escrow(v_report.mission_id);
    end if;

    perform set_config('app.reviewing_report', 'false', true);

    return jsonb_build_object(
        'success', true,
        'report_id', v_report.id,
        'campaign_id', v_camp.id,
        'tester_id', v_report.user_id,
        'status', 'rejected',
        'credits_deducted', v_deduct,
        'clawed_credits', v_deduct,
        'tester_balance', v_new_balance,
        'new_balance', v_new_balance,
        'reason_code', v_final_reason_code,
        'tester_today_rejections', v_tester_today_rejections,
        'tester_warned', v_tester_warned,
        'tester_warning_msg', v_tester_warning_msg,
        'is_tester_locked', v_is_tester_locked,
        'tester_locked', v_is_tester_locked,
        'owner_today_rejections', v_owner_today_rejections,
        'owner_warned', v_owner_warned,
        'owner_warning_msg', v_owner_warning_msg,
        'is_owner_locked', v_is_owner_locked,
        'owner_locked', v_is_owner_locked
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._impl_reject_tester_report(uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_reject_tester_report(uuid, text, text) TO service_role;
