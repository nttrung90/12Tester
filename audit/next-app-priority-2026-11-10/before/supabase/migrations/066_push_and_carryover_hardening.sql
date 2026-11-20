-- Migration 066: Push and Daily Task Carryover Hardening
-- Wrapped in an explicit transaction for atomic deployment.

BEGIN;

-- ============================================================================
-- 1. SYSTEM PROVENANCE COVERAGE MARKER (DO NOTHING ON CONFLICT TO AVOID RESET)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.system_provenance_markers (
    marker_key text PRIMARY KEY,
    marker_time timestamptz NOT NULL DEFAULT clock_timestamp(),
    marker_date date NOT NULL DEFAULT (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date
);

ALTER TABLE public.system_provenance_markers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.system_provenance_markers NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.system_provenance_markers FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.system_provenance_markers TO service_role;

-- Global coverage start marker: ON CONFLICT DO NOTHING to never reset coverage on rerun
INSERT INTO public.system_provenance_markers (marker_key, marker_time, marker_date)
VALUES ('coverage_start', clock_timestamp(), (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date)
ON CONFLICT (marker_key) DO NOTHING;

-- ============================================================================
-- 2. CAMPAIGN STATUS JOURNAL (ALL CAMPAIGNS & DELETIONS PROSPECTIVELY RECORDED)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.campaign_status_journal (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    status text NOT NULL,
    paused_reason text,
    campaign_created_at timestamptz NOT NULL,
    completed_at timestamptz,
    slot_number integer DEFAULT 1,
    valid_from timestamptz NOT NULL DEFAULT clock_timestamp(),
    valid_to timestamptz DEFAULT NULL
);

CREATE INDEX IF NOT EXISTS idx_campaign_status_journal_query
ON public.campaign_status_journal(campaign_id, valid_from, valid_to);

CREATE INDEX IF NOT EXISTS idx_campaign_status_journal_owner_query
ON public.campaign_status_journal(owner_id, valid_from, valid_to);

ALTER TABLE public.campaign_status_journal ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.campaign_status_journal NO FORCE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.campaign_status_journal FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.campaign_status_journal TO service_role;

CREATE OR REPLACE FUNCTION public.trg_campaign_status_journal()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_now timestamptz := clock_timestamp();
begin
    if TG_OP = 'INSERT' then
        insert into public.campaign_status_journal (
            campaign_id, owner_id, status, paused_reason, campaign_created_at, completed_at, slot_number, valid_from, valid_to
        ) values (
            NEW.id, NEW.owner_id, NEW.status, NEW.paused_reason, NEW.created_at, NEW.completed_at, coalesce(NEW.slot_number, 1), v_now, null
        );
        return NEW;
    elsif TG_OP = 'UPDATE' then
        if (NEW.status is distinct from OLD.status
            or NEW.paused_reason is distinct from OLD.paused_reason
            or NEW.owner_id is distinct from OLD.owner_id
            or NEW.completed_at is distinct from OLD.completed_at
            or NEW.created_at is distinct from OLD.created_at
            or NEW.slot_number is distinct from OLD.slot_number) then

            update public.campaign_status_journal
            set valid_to = v_now
            where campaign_id = OLD.id and valid_to is null;

            insert into public.campaign_status_journal (
                campaign_id, owner_id, status, paused_reason, campaign_created_at, completed_at, slot_number, valid_from, valid_to
            ) values (
                NEW.id, NEW.owner_id, NEW.status, NEW.paused_reason, NEW.created_at, NEW.completed_at, coalesce(NEW.slot_number, 1), v_now, null
            );
        end if;
        return NEW;
    elsif TG_OP = 'DELETE' then
        update public.campaign_status_journal
        set valid_to = v_now
        where campaign_id = OLD.id and valid_to is null;

        insert into public.campaign_status_journal (
            campaign_id, owner_id, status, paused_reason, campaign_created_at, completed_at, slot_number, valid_from, valid_to
        ) values (
            OLD.id, OLD.owner_id, 'deleted', 'deleted', OLD.created_at, OLD.completed_at, coalesce(OLD.slot_number, 1), v_now, null
        );
        return OLD;
    end if;
    return null;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaign_status_journal_trg ON public.campaigns;
CREATE TRIGGER trg_campaign_status_journal_trg
AFTER INSERT OR UPDATE OR DELETE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaign_status_journal();

-- Seed existing campaigns into journal prospectively at migration timestamp, never fabricated to created_at
INSERT INTO public.campaign_status_journal (
    campaign_id, owner_id, status, paused_reason, campaign_created_at, completed_at, slot_number, valid_from, valid_to
)
SELECT
    c.id, c.owner_id, c.status, c.paused_reason, c.created_at, c.completed_at, coalesce(c.slot_number, 1),
    clock_timestamp(), null
FROM public.campaigns c
WHERE NOT EXISTS (
    SELECT 1 FROM public.campaign_status_journal j WHERE j.campaign_id = c.id
);

-- ============================================================================
-- 3. USER ROLE JOURNAL (PROSPECTIVE ROLE / EXEMPTION TRACKING)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.user_role_journal (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL,
    role text NOT NULL,
    is_exempt boolean NOT NULL,
    valid_from timestamptz NOT NULL DEFAULT clock_timestamp(),
    valid_to timestamptz DEFAULT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_role_journal_lookup
ON public.user_role_journal(user_id, valid_from, valid_to);

ALTER TABLE public.user_role_journal ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_role_journal NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_role_journal FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.user_role_journal TO service_role;

CREATE OR REPLACE FUNCTION public.trg_user_role_journal()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_now timestamptz := clock_timestamp();
begin
    if TG_OP = 'INSERT' then
        insert into public.user_role_journal (user_id, role, is_exempt, valid_from, valid_to)
        values (NEW.id, lower(NEW.role), (lower(NEW.role) in ('admin', 'moderator')), v_now, null);
        return NEW;
    elsif TG_OP = 'UPDATE' then
        if NEW.role is distinct from OLD.role then
            update public.user_role_journal
            set valid_to = v_now
            where user_id = OLD.id and valid_to is null;

            insert into public.user_role_journal (user_id, role, is_exempt, valid_from, valid_to)
            values (NEW.id, lower(NEW.role), (lower(NEW.role) in ('admin', 'moderator')), v_now, null);
        end if;
        return NEW;
    end if;
    return null;
end;
$function$;

DROP TRIGGER IF EXISTS trg_user_role_journal_trg ON public.profiles;
CREATE TRIGGER trg_user_role_journal_trg
AFTER INSERT OR UPDATE ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.trg_user_role_journal();

INSERT INTO public.user_role_journal (user_id, role, is_exempt, valid_from, valid_to)
SELECT p.id, lower(p.role), (lower(p.role) in ('admin', 'moderator')), clock_timestamp(), null
FROM public.profiles p
WHERE NOT EXISTS (
    SELECT 1 FROM public.user_role_journal j WHERE j.user_id = p.id
);

-- ============================================================================
-- 4. APPEND-ONLY QUALIFYING TEST EVENTS JOURNAL (PRESERVES D & SAME-DAY REJECTS)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.daily_test_events_journal (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type text NOT NULL, -- 'checkin' or 'report'
    source_id uuid NOT NULL,
    user_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    campaign_owner_id uuid NOT NULL,
    activity_date date NOT NULL,
    status text NOT NULL,
    is_qualifying boolean NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_daily_test_events_lookup
ON public.daily_test_events_journal(user_id, activity_date, observed_at);

CREATE INDEX IF NOT EXISTS idx_daily_test_events_source
ON public.daily_test_events_journal(source_type, source_id, observed_at);

ALTER TABLE public.daily_test_events_journal ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_test_events_journal NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.daily_test_events_journal FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.daily_test_events_journal TO service_role;

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
            source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at
        ) values (
            'checkin', NEW.id, NEW.user_id, NEW.campaign_id, coalesce(v_owner_id, '00000000-0000-0000-0000-000000000000'::uuid),
            NEW.checkin_date, 'created', v_qualifying, clock_timestamp()
        );
        return NEW;
    elsif TG_OP = 'DELETE' then
        -- Explicit removal while parent campaign still exists must invalidate that source at observed clock.
        -- Cascade campaign deletion must preserve previous legitimate qualification.
        select exists (select 1 from public.campaigns where id = OLD.campaign_id) into v_camp_exists;
        if v_camp_exists then
            insert into public.daily_test_events_journal (
                source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at
            ) values (
                'checkin', OLD.id, OLD.user_id, OLD.campaign_id, '00000000-0000-0000-0000-000000000000'::uuid,
                OLD.checkin_date, 'deleted', false, clock_timestamp()
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
begin
    if TG_OP = 'INSERT' or TG_OP = 'UPDATE' then
        select c.owner_id into v_owner_id from public.campaigns c where c.id = NEW.campaign_id;
        if v_owner_id is null then
            select j.owner_id into v_owner_id from public.campaign_status_journal j
            where j.campaign_id = NEW.campaign_id order by j.id desc limit 1;
        end if;

        v_act_date := (NEW.created_at at time zone 'Asia/Ho_Chi_Minh')::date;
        v_qualifying := (NEW.status in ('pending', 'approved') and v_owner_id is not null and NEW.user_id <> v_owner_id);

        insert into public.daily_test_events_journal (
            source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at
        ) values (
            'report', NEW.id, NEW.user_id, NEW.campaign_id, coalesce(v_owner_id, '00000000-0000-0000-0000-000000000000'::uuid),
            v_act_date, NEW.status, v_qualifying, clock_timestamp()
        );
        return NEW;
    elsif TG_OP = 'DELETE' then
        -- Explicit removal while parent campaign still exists must invalidate that source at observed clock.
        -- Cascade campaign deletion must preserve previous legitimate qualification.
        select exists (select 1 from public.campaigns where id = OLD.campaign_id) into v_camp_exists;
        if v_camp_exists then
            v_act_date := (OLD.created_at at time zone 'Asia/Ho_Chi_Minh')::date;
            insert into public.daily_test_events_journal (
                source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at
            ) values (
                'report', OLD.id, OLD.user_id, OLD.campaign_id, '00000000-0000-0000-0000-000000000000'::uuid,
                v_act_date, 'deleted', false, clock_timestamp()
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

-- Seed existing checkins and reports into daily_test_events_journal
INSERT INTO public.daily_test_events_journal (
    source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at
)
SELECT
    'checkin', ci.id, ci.user_id, ci.campaign_id, coalesce(c.owner_id, '00000000-0000-0000-0000-000000000000'::uuid),
    ci.checkin_date, 'created',
    (c.owner_id is not null and ci.user_id <> c.owner_id and (
        ci.note is null
        or (
            ci.note not like 'Đã kiểm thử%'
            and ci.note not like 'Đã nộp kết quả%'
            and ci.note not like '%tạm tính%'
            and ci.note not like 'Tự động duyệt%'
            and ci.note not like '%Tự động duyệt%'
            and ci.note not like '%chủ app duyệt%'
        )
    )),
    coalesce(ci.created_at, clock_timestamp())
FROM public.checkins ci
LEFT JOIN public.campaigns c ON c.id = ci.campaign_id
WHERE NOT EXISTS (
    SELECT 1 FROM public.daily_test_events_journal j
    WHERE j.source_type = 'checkin' AND j.source_id = ci.id
);

INSERT INTO public.daily_test_events_journal (
    source_type, source_id, user_id, campaign_id, campaign_owner_id, activity_date, status, is_qualifying, observed_at
)
SELECT
    'report', tr.id, tr.user_id, tr.campaign_id, coalesce(c.owner_id, '00000000-0000-0000-0000-000000000000'::uuid),
    (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date, tr.status,
    (tr.status in ('pending', 'approved') and c.owner_id is not null and tr.user_id <> c.owner_id),
    coalesce(tr.created_at, clock_timestamp())
FROM public.tester_reports tr
LEFT JOIN public.campaigns c ON c.id = tr.campaign_id
WHERE NOT EXISTS (
    SELECT 1 FROM public.daily_test_events_journal j
    WHERE j.source_type = 'report' AND j.source_id = tr.id
);

-- ============================================================================
-- 5. REJECT RETROACTIVE POLICY SCHEDULING & DIRECT TABLE MODIFICATIONS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.set_daily_task_carryover_policy(
    p_effective_from date,
    p_is_enabled boolean DEFAULT true,
    p_operator_note text DEFAULT 'Configured carryover policy'::text
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_old public.daily_task_carryover_policies;
    v_row public.daily_task_carryover_policies;
    v_caller text := coalesce(current_setting('session_user', true), session_user);
    v_allow_retro boolean := (current_setting('test.allow_policy_retroactive_write', true) = 'true'
                              and v_caller in ('postgres', 'service_role'));
    v_clean_note text;
begin
    if p_effective_from is null then
        raise exception 'INVALID_EFFECTIVE_DATE: Ngay hieu luc khong duoc de trong';
    end if;

    if p_effective_from = 'infinity'::date or p_effective_from = '-infinity'::date
       or p_effective_from < '2020-01-01'::date or p_effective_from > '2100-01-01'::date then
        raise exception 'INVALID_EFFECTIVE_DATE: Ngay hieu luc vuot ngoai pham vi cho phep';
    end if;

    perform pg_advisory_xact_lock(hashtext('set_daily_task_carryover_policy_lock'));

    v_clean_note := btrim(coalesce(p_operator_note, ''));
    if v_clean_note = '' then
        v_clean_note := 'Configured carryover policy';
    end if;

    select * into v_old
    from public.daily_task_carryover_policies
    where effective_from = p_effective_from;

    if p_effective_from <= v_today and not v_allow_retro then
        raise exception 'CANNOT_MODIFY_HISTORICAL_POLICY: Chinh sach phai duoc len lich tu ngay tuong lai (sau ngay hien tai)';
    end if;

    insert into public.daily_task_carryover_policies (
        effective_from, is_enabled, operator_note
    ) values (
        p_effective_from,
        coalesce(p_is_enabled, true),
        v_clean_note
    )
    on conflict (effective_from) do update
    set is_enabled = excluded.is_enabled,
        operator_note = excluded.operator_note
    returning * into v_row;

    insert into public.daily_task_carryover_policy_audit (
        policy_id, effective_from, old_is_enabled, new_is_enabled,
        old_operator_note, new_operator_note, changed_by, changed_at
    ) values (
        v_row.id, p_effective_from, v_old.is_enabled, v_row.is_enabled,
        v_old.operator_note, v_row.operator_note, current_user, clock_timestamp()
    );

    return to_jsonb(v_row);
end;
$function$;

CREATE OR REPLACE FUNCTION public.trg_protect_daily_task_carryover_policy()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_caller text := coalesce(current_setting('session_user', true), session_user);
    v_allow_retro boolean := (current_setting('test.allow_policy_retroactive_write', true) = 'true'
                              and v_caller in ('postgres', 'service_role'));
begin
    if TG_OP = 'DELETE' then
        if OLD.effective_from <= v_today and not v_allow_retro then
            raise exception 'CANNOT_MODIFY_HISTORICAL_POLICY: Khong duoc phep xoa chinh sach qua khu hoac hom nay truc tiep tren bang';
        end if;
        return OLD;
    elsif TG_OP = 'UPDATE' then
        if (OLD.effective_from <= v_today or NEW.effective_from <= v_today) and not v_allow_retro then
            raise exception 'CANNOT_MODIFY_HISTORICAL_POLICY: Khong duoc phep thay doi chinh sach qua khu hoac hom nay truc tiep tren bang';
        end if;
        if NEW.id is distinct from OLD.id then
            raise exception 'CANNOT_MODIFY_POLICY_IDENTITY: Khong duoc phep thay doi id chinh sach';
        end if;
        return NEW;
    elsif TG_OP = 'INSERT' then
        if NEW.effective_from <= v_today and not v_allow_retro then
            raise exception 'CANNOT_MODIFY_HISTORICAL_POLICY: Khong duoc phep them chinh sach qua khu hoac hom nay truc tiep tren bang';
        end if;
        return NEW;
    end if;
    return null;
end;
$function$;

DROP TRIGGER IF EXISTS trg_protect_daily_task_carryover_policy_trg ON public.daily_task_carryover_policies;
CREATE TRIGGER trg_protect_daily_task_carryover_policy_trg
BEFORE INSERT OR UPDATE OR DELETE ON public.daily_task_carryover_policies
FOR EACH ROW EXECUTE FUNCTION public.trg_protect_daily_task_carryover_policy();

-- ============================================================================
-- 6. GET_DAILY_TASK_STATUS (PROVEN OBSERVATION, GLOBAL PROVENANCE & APPEND-ONLY D)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_daily_task_status(p_user_id uuid, p_date date)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_role text;
    v_last_login date;
    v_streak int := 0;
    v_exempt boolean := false;
    v_k int := 0;
    v_a int := 0;
    v_d int := 0;
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

    select lower(p.role), p.last_login_date, p.daily_missed_streak
    into v_role, v_last_login, v_streak
    from public.profiles p
    where p.id = p_user_id;

    if not found then
        return null;
    end if;

    v_streak := coalesce(v_streak, 0);

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
                    when v_finalized_task.missed_streak <= 0 then 1
                    when v_finalized_task.missed_streak = 1 then 2
                    when v_finalized_task.missed_streak = 2 then 5
                    else 10
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

    -- Đọc global coverage start marker
    select marker_time, marker_date
    into v_coverage_start_time, v_coverage_start_date
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    -- 2. Tái dựng K, A, D và Role theo bằng chứng quan sát thực tế
    if p_date < v_today then
        -- Bất kỳ ngày quá khứ chưa finalized nào mà day_start bắt đầu trước mốc tin cậy coverage_start:
        -- Hoãn an toàn history_ready = false (cho mọi users, không đoán mò trạng thái)
        if v_coverage_start_time is null or v_day_start < v_coverage_start_time then
            v_history_ready := false;
        else
            v_history_ready := true;
        end if;

        -- Xác định role và exemption tại thời điểm kết thúc ngày (v_next_day_start)
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

        -- Tái dựng K và A từ campaign_status_journal: chỉ lấy bản ghi hợp lệ trước v_next_day_start.
        -- Tuyệt đối KHÔNG suy đoán ngược từ trạng thái open hôm nay về hôm qua!
        with hist as (
            select distinct on (j.campaign_id)
                j.campaign_id,
                j.owner_id,
                j.status,
                j.paused_reason,
                j.campaign_created_at,
                j.completed_at
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

        -- D: Tái dựng từ sổ nhật ký sự kiện append-only daily_test_events_journal
        -- Tính trạng thái qualifying của từng source tại mốc v_next_day_start;
        -- Không union live tables vào ngày quá khứ; bảo toàn lượt test khi campaign bị xóa
        with latest_events as (
            select distinct on (j.source_type, j.source_id)
                j.campaign_id,
                j.campaign_owner_id,
                j.is_qualifying
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
        -- Ngày hôm nay: dùng trạng thái live trực tiếp
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

        -- D hôm nay: Tính dựa trên sự kiện mới nhất của từng source trong ngày hôm nay
        -- Bảo toàn lượt test khi campaign bị xóa; phản ánh chính xác khi checkin/report bị xóa trực tiếp
        with latest_events as (
            select distinct on (j.source_type, j.source_id)
                j.campaign_id,
                j.campaign_owner_id,
                j.is_qualifying
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

    -- Login lấy từ lịch sử theo ngày hoặc profile
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

    -- Tính toán phần thiếu chuyển tiếp (Carryover)
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

    if v_is_carryover_active then
        v_carry_effective_from := v_carry_policy.effective_from;

        if p_date = v_carry_effective_from and not v_has_prior_history then
            v_carry_in := 0;
            v_carryover_ready := true;
        elsif p_date = v_carry_effective_from and v_has_prior_history then
            v_carry_in := coalesce(v_last_carry_out, 0);
            v_carryover_ready := true;
        else
            select t.finalized_at, t.carry_out_tests
            into v_prev_finalized, v_prev_carry_out
            from public.user_daily_tasks t
            where t.user_id = p_user_id and t.task_date = (p_date - 1);

            if v_prev_finalized is not null then
                v_carry_in := coalesce(v_prev_carry_out, 0);
                v_carryover_ready := true;
            else
                v_carryover_ready := false;
                v_carry_in := 0;
            end if;
        end if;

        if v_carryover_ready then
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
    else
        -- Chính sách cộng dồn chưa có hiệu lực hoặc đang bị TẮT
        v_carry_effective_from := null;
        v_carryover_ready := true;

        if v_has_prior_history and coalesce(v_last_carry_out, 0) > 0 then
            v_carry_in := v_last_carry_out;
            if v_mode = 'member' or v_exempt then
                v_voluntary_tests := v_d;
            else
                v_effective := least(v_base_required, v_a);
                v_voluntary_tests := greatest(0, v_d - v_effective);
            end if;
            v_carry_remaining := greatest(0, v_carry_in - v_voluntary_tests);
        else
            v_carry_in := 0;
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

    v_next_pct := case
        when v_streak <= 0 then 1
        when v_streak = 1 then 2
        when v_streak = 2 then 5
        else 10
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

-- ============================================================================
-- 7. RECONCILE LEGACY DAILY TASK CHECKPOINT (AUDITED RECONCILIATION FOR GAPS)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.reconcile_legacy_daily_task_checkpoint(
    p_user_id uuid,
    p_task_date date,
    p_mode text,
    p_active_app_count int,
    p_carry_in int,
    p_carry_out int,
    p_operator_note text
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_caller text := coalesce(current_setting('session_user', true), session_user);
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_row public.user_daily_tasks;
    v_clean_note text;
    v_known_streak int := 0;
begin
    if v_caller not in ('postgres', 'service_role') then
        raise exception 'PERMISSION_DENIED: Chi quan tri vien he thong moi co quyen doi soat checkpoint lich su';
    end if;

    if p_user_id is null then
        raise exception 'INVALID_USER_ID: user_id is required';
    end if;

    if p_task_date is null or p_task_date >= v_today then
        raise exception 'INVALID_DATE: Checkpoint doi soat chi ap dung cho ngay qua khu';
    end if;

    if p_mode is null or p_mode not in ('owner', 'member', 'exempt') then
        raise exception 'INVALID_MODE: mode must be owner, member or exempt';
    end if;

    if p_active_app_count is null or p_active_app_count < 0 then
        raise exception 'INVALID_APP_COUNT: active_app_count must be non-null and nonnegative';
    end if;

    if p_carry_in is null or p_carry_in < 0 then
        raise exception 'INVALID_CARRY_IN: Null or negative source debt must not silently turn into zero';
    end if;

    if p_carry_out is null or p_carry_out < 0 then
        raise exception 'INVALID_CARRY_OUT: carry_out must be non-null and nonnegative';
    end if;

    v_clean_note := btrim(coalesce(p_operator_note, ''));
    if v_clean_note = '' then
        raise exception 'INVALID_NOTE: Required nonblank operator note';
    end if;

    -- Never rewrite already finalized rows
    if exists (
        select 1 from public.user_daily_tasks
        where user_id = p_user_id and task_date = p_task_date and finalized_at is not null
    ) then
        raise exception 'CANNOT_OVERWRITE_FINALIZED_ROW: Khong duoc phep ghi de dong da finalized';
    end if;

    -- Preserve known existing streak (from trusted prior ledger or profile)
    select coalesce(
        (select t.missed_streak from public.user_daily_tasks t
         where t.user_id = p_user_id and t.task_date < p_task_date and t.finalized_at is not null
         order by t.task_date desc limit 1),
        (select p.daily_missed_streak from public.profiles p where p.id = p_user_id),
        0
    ) into v_known_streak;

    insert into public.user_daily_tasks (
        user_id, task_date, mode, active_app_count, required_tests,
        effective_required_tests, available_apps, completed_tests,
        logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
        policy_id, tests_per_active_app,
        carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
        carry_source_date, carryover_effective_from
    ) values (
        p_user_id, p_task_date, p_mode,
        p_active_app_count, p_carry_in,
        p_carry_in, 0, 0,
        true, true, true, v_known_streak, 0, clock_timestamp(),
        null, null,
        1, 0, p_carry_in, p_carry_out,
        null, p_task_date
    )
    on conflict (user_id, task_date) do update
    set mode = excluded.mode,
        active_app_count = excluded.active_app_count,
        required_tests = excluded.required_tests,
        effective_required_tests = excluded.effective_required_tests,
        available_apps = excluded.available_apps,
        completed_tests = excluded.completed_tests,
        logged_in = true,
        is_exempt = true,
        met = true,
        missed_streak = excluded.missed_streak,
        penalty_credits = 0,
        finalized_at = clock_timestamp(),
        carryover_version = excluded.carryover_version,
        base_required_tests = excluded.base_required_tests,
        carry_in_tests = excluded.carry_in_tests,
        carry_out_tests = excluded.carry_out_tests,
        carry_source_date = null,
        carryover_effective_from = excluded.carryover_effective_from
    where user_daily_tasks.finalized_at is null
    returning * into v_row;

    insert into public.daily_task_carryover_policy_audit (
        policy_id, effective_from, old_is_enabled, new_is_enabled,
        old_operator_note, new_operator_note, changed_by, changed_at
    ) values (
        null, p_task_date, null, true,
        'legacy_reconcile_checkpoint',
        v_clean_note,
        v_caller, clock_timestamp()
    );

    return to_jsonb(v_row);
end;
$function$;

REVOKE ALL ON FUNCTION public.reconcile_legacy_daily_task_checkpoint FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.reconcile_legacy_daily_task_checkpoint TO service_role;

-- ============================================================================
-- 8. FINALIZE DAILY TASKS (RESPECT HISTORY_READY & PRESERVE FROZEN DEBT)
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
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_eval >= v_carry_policy.effective_from);
    v_effective_from := case when v_is_carryover_active then v_carry_policy.effective_from else null end;

    for r in
        select p.id, p.credits, p.daily_missed_streak, lower(p.role) as role, p.created_at
        from public.profiles p
        where p.created_at < ((p_eval + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
        for update of p
    loop
        if r.created_at >= (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh') then
            insert into public.user_daily_tasks as t (
                user_id, task_date, mode, active_app_count, required_tests,
                effective_required_tests, available_apps, completed_tests,
                logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
                policy_id, tests_per_active_app,
                carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
                carry_source_date, carryover_effective_from
            ) values (
                r.id, p_eval, 'member',
                0, 0, 0, 0, 0,
                true, true, true, 0, 0, now(),
                v_policy.id, v_policy.tests_per_active_app,
                case when v_is_carryover_active then 1 else null end,
                0, 0, 0, null, v_effective_from
            )
            on conflict (user_id, task_date) do update
            set mode = 'member',
                active_app_count = 0,
                required_tests = 0,
                effective_required_tests = 0,
                available_apps = 0,
                completed_tests = 0,
                logged_in = true,
                is_exempt = true,
                met = true,
                missed_streak = 0,
                penalty_credits = 0,
                finalized_at = now(),
                policy_id = excluded.policy_id,
                tests_per_active_app = excluded.tests_per_active_app,
                carryover_version = case when v_is_carryover_active then 1 else null end,
                base_required_tests = 0,
                carry_in_tests = 0,
                carry_out_tests = 0,
                carry_source_date = null,
                carryover_effective_from = v_effective_from
            where t.finalized_at is null;

            continue;
        end if;

        if exists (
            select 1
            from public.user_daily_tasks t
            where t.user_id = r.id
              and t.task_date = p_eval
              and t.finalized_at is not null
        ) then
            continue;
        end if;

        v_status := public.get_daily_task_status(r.id, p_eval);
        if v_status is null then
            continue;
        end if;

        v_ready := coalesce((v_status ->> 'carryover_ready')::boolean, true);
        v_hist_ready := coalesce((v_status ->> 'history_ready')::boolean, true);

        -- Không chốt, không phạt khi carryover_ready = false hoặc history_ready = false
        if (v_is_carryover_active and not v_ready) or not v_hist_ready then
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
        order by t.task_date desc limit 1;

        v_source_date := case
            when v_is_carryover_active and p_eval > v_effective_from then (p_eval - 1)
            when not v_is_carryover_active and v_carry_in > 0 then v_last_task_date
            else null
        end;

        select coalesce(
            (select t.missed_streak
             from public.user_daily_tasks t
             where t.user_id = r.id
               and t.task_date < p_eval
               and t.finalized_at is not null
             order by t.task_date desc
             limit 1),
            r.daily_missed_streak,
            0
        ) into v_prev_streak;

        if v_exempt then
            v_new_streak := 0;
            v_deduction := 0;
        elsif v_met then
            v_new_streak := 0;
            v_deduction := 0;
        else
            if v_mode = 'owner' and v_effective <= 0 then
                v_new_streak := v_prev_streak;
                v_deduction := 0;
            else
                v_new_streak := v_prev_streak + 1;
                v_pct := case
                    when v_new_streak = 1 then 0.01
                    when v_new_streak = 2 then 0.02
                    when v_new_streak = 3 then 0.05
                    else 0.10
                end;

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

                if v_ratio <= 0 then
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
            carry_source_date, carryover_effective_from
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
            v_effective_from
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
            carryover_effective_from = excluded.carryover_effective_from
        where t.finalized_at is null;

        get diagnostics v_rows = row_count;
        if v_rows = 0 then
            continue;
        end if;

        if not v_exempt then
            update public.profiles
            set credits = v_new_balance,
                daily_missed_streak = v_new_streak
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
        end if;
    end loop;
end;
$function$;

CREATE OR REPLACE FUNCTION public.pause_inactive_owner_campaigns()
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    r record;
    v_count int := 0;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_status jsonb;
begin
    for r in
        select c.id, c.owner_id, c.app_name
        from public.campaigns c
        join public.profiles p on p.id = c.owner_id
        where c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
          and c.created_at <= now() - interval '24 hours'
          and lower(p.role) not in ('admin', 'moderator')
          and p.daily_missed_streak >= 7
        for update of c
    loop
        v_status := public.get_daily_task_status(r.owner_id, v_today);

        if coalesce((v_status ->> 'carryover_ready')::boolean, true) = false
           or coalesce((v_status ->> 'history_ready')::boolean, true) = false then
            continue;
        end if;

        if coalesce((v_status ->> 'met')::boolean, false) = true then
            continue;
        end if;

        update public.campaigns
        set status = 'paused',
            paused_reason = 'owner_inactive'
        where id = r.id;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã bị tạm dừng ⏸',
            format(
                'Ứng dụng "%s" đã bị tạm dừng vì bạn không hoàn thành chỉ tiêu test trong 7 ngày liên tục. Hãy hoàn thành chỉ tiêu hôm nay, sau đó bấm "Tiếp tục nhận tester" trong màn hình quản lý app để hiển thị lại.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'owner_inactive')
        );

        v_count := v_count + 1;
    end loop;

    return v_count;
end;
$function$;

CREATE OR REPLACE FUNCTION public.resume_campaign(p_campaign_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_camp record;
    v_missed int;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_status jsonb;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id for update;
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

    if v_camp.paused_reason = 'owner_inactive' then
        v_status := public.get_daily_task_status(v_uid, v_today);
        if coalesce((v_status ->> 'carryover_ready')::boolean, true) = false
           or coalesce((v_status ->> 'history_ready')::boolean, true) = false then
            raise exception 'DAILY_TASK_PENDING';
        end if;
        if coalesce((v_status ->> 'met')::boolean, false) = false then
            raise exception 'OWNER_INACTIVE';
        end if;
    end if;

    perform public.refresh_daily_completion(v_uid, v_today);

    select daily_missed_streak into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 7 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
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

-- ============================================================================
-- 9. READ/RECONCILE-ONLY RECEIPT RPC (R06/R08)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.reconcile_campaign_push_receipt(p_campaign_id uuid, p_client_request_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_uid               uuid := auth.uid();
    v_existing_receipt  record;
    v_current_credits   int := 0;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    if p_campaign_id is null then
        raise exception 'INVALID_CAMPAIGN_ID';
    end if;

    select * into v_existing_receipt
    from public.campaign_push_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is null then
        return jsonb_build_object(
            'success', true,
            'found', false,
            'client_request_id', p_client_request_id,
            'campaign_id', p_campaign_id
        );
    end if;

    if coalesce(v_existing_receipt.target_campaign_id, v_existing_receipt.campaign_id) is distinct from p_campaign_id then
        raise exception 'REQUEST_ID_CONFLICT';
    end if;

    select credits into v_current_credits
    from public.profiles
    where id = v_uid;

    return jsonb_build_object(
        'success', true,
        'found', true,
        'receipt_id', v_existing_receipt.id,
        'client_request_id', v_existing_receipt.client_request_id,
        'campaign_id', p_campaign_id,
        'charged_credits', v_existing_receipt.cost_credits,
        'balance_after', v_existing_receipt.balance_after,
        'current_credits', coalesce(v_current_credits, 0),
        'business_date', to_char(v_existing_receipt.business_date, 'YYYY-MM-DD'),
        'expires_at', to_char(v_existing_receipt.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_order', v_existing_receipt.push_order,
        'replayed', true
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.reconcile_campaign_push_receipt(uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reconcile_campaign_push_receipt(uuid, uuid) TO authenticated, service_role;

-- Reload PostgREST schema cache
NOTIFY pgrst, 'reload schema';

COMMIT;
