-- ============================================================================
-- Migration 070: Admin Management Core Backend (Stage A)
--
-- 1. Common assert unlocked admin helper and admin_action_events audit table.
-- 2. Server-side member search, member overview, credit history, and daily task explanation.
-- 3. Carryover policy management: active/future/history with revision, scheduling and cancellation.
-- 4. Centralized campaign search and tester report queue with multidimensional filters.
-- 5. Safe credit compensation API with UUID request ID and idempotency.
-- 6. Role management (member, moderator, admin) with explicit capability checks and last admin guard.
-- 7. Append-only warning revocations with consistent exclusion across warning counts.
-- 8. Real persisted system monitor summary and mission settlement retry wrapper.
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. Tables: Audit, Capabilities, Operation Requests, Warning Revocations
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.admin_action_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES public.profiles(id),
    action text NOT NULL,
    target_type text NOT NULL,
    target_id text NOT NULL,
    reason text,
    old_state jsonb,
    new_state jsonb,
    request_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_action_events_created
    ON public.admin_action_events (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_admin_action_events_actor
    ON public.admin_action_events (actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_action_events_target
    ON public.admin_action_events (target_type, target_id);

ALTER TABLE public.admin_action_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_action_events NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.admin_action_events FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT ON TABLE public.admin_action_events TO service_role;

-- Capabilities for explicit admin bootstrap
CREATE TABLE IF NOT EXISTS public.admin_capabilities (
    admin_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    capability text NOT NULL,
    granted_by uuid REFERENCES public.profiles(id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (admin_id, capability)
);

ALTER TABLE public.admin_capabilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_capabilities NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.admin_capabilities FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.admin_capabilities TO service_role;

-- Scoped request idempotency table: (actor_id, operation, id)
CREATE TABLE IF NOT EXISTS public.admin_operation_requests (
    id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    operation text NOT NULL,
    payload_hash text NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, operation, id)
);

CREATE INDEX IF NOT EXISTS idx_admin_operation_requests_created
    ON public.admin_operation_requests (created_at DESC);

ALTER TABLE public.admin_operation_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_operation_requests NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.admin_operation_requests FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.admin_operation_requests TO service_role;

-- Append-only warning revocations
CREATE TABLE IF NOT EXISTS public.user_warning_revocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    warning_id uuid NOT NULL UNIQUE REFERENCES public.user_warnings(id) ON DELETE CASCADE,
    revoked_by uuid NOT NULL REFERENCES public.profiles(id),
    reason text NOT NULL,
    request_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_warning_revocations_created
    ON public.user_warning_revocations (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_warning_revocations_user
    ON public.user_warning_revocations (revoked_by);

ALTER TABLE public.user_warning_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_warning_revocations NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_warning_revocations FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT ON TABLE public.user_warning_revocations TO service_role;

-- ----------------------------------------------------------------------------
-- 2. Core Security Helpers: assert_unlocked_admin & record_admin_action_event
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.assert_unlocked_admin(p_required_capability text DEFAULT NULL::text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_role text;
    v_locked boolean;
    v_has_cap boolean;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_role, v_locked
    from public.profiles
    where id = v_caller_id;

    if v_role is null or lower(v_role) != 'admin' or v_locked = true then
        raise exception 'PERMISSION_DENIED: Quản trị viên chưa bị khóa mới có quyền thực hiện';
    end if;

    if p_required_capability is not null then
        select exists (
            select 1
            from public.admin_capabilities
            where admin_id = v_caller_id
              and capability = p_required_capability
        ) into v_has_cap;

        if not v_has_cap then
            raise exception 'INSUFFICIENT_PRIVILEGE: Cần quyền % để thực hiện thao tác này', p_required_capability;
        end if;
    end if;

    return v_caller_id;
end;
$function$;

REVOKE ALL ON FUNCTION public.assert_unlocked_admin(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.assert_unlocked_admin(text) TO authenticated, service_role;

-- Internal helper to record admin action events (service_role only direct execution)
CREATE OR REPLACE FUNCTION public.record_admin_action_event(
    p_actor_id uuid,
    p_action text,
    p_target_type text,
    p_target_id text,
    p_reason text DEFAULT NULL::text,
    p_old_state jsonb DEFAULT NULL::jsonb,
    p_new_state jsonb DEFAULT NULL::jsonb,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_event_id uuid;
begin
    if p_actor_id is null or p_action is null or p_target_type is null or p_target_id is null then
        raise exception 'INVALID_AUDIT_PAYLOAD';
    end if;

    insert into public.admin_action_events (
        actor_id, action, target_type, target_id, reason, old_state, new_state, request_id, created_at
    ) values (
        p_actor_id, p_action, p_target_type, p_target_id, p_reason, p_old_state, p_new_state, p_request_id, now()
    )
    returning id into v_event_id;

    return v_event_id;
end;
$function$;

REVOKE ALL ON FUNCTION public.record_admin_action_event(uuid, text, text, text, text, jsonb, jsonb, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_admin_action_event(uuid, text, text, text, text, jsonb, jsonb, uuid) TO service_role;

-- Internal bootstrap helper for database owner / service_role
CREATE OR REPLACE FUNCTION public.bootstrap_admin_capability(
    p_admin_id uuid,
    p_capability text DEFAULT 'admin_management'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_role text := coalesce(current_setting('session_user', true), session_user);
    v_target_role text;
    v_target_locked boolean;
begin
    if v_caller_role not in ('postgres', 'service_role') and current_user not in ('postgres', 'service_role') then
        raise exception 'PERMISSION_DENIED: Chỉ service_role hoặc database owner mới có quyền bootstrap capability';
    end if;

    select role, coalesce(is_locked, false)
    into v_target_role, v_target_locked
    from public.profiles
    where id = p_admin_id;

    if v_target_role is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    if lower(v_target_role) != 'admin' then
        raise exception 'TARGET_MUST_BE_ADMIN';
    end if;

    insert into public.admin_capabilities (admin_id, capability, granted_by, granted_at)
    values (p_admin_id, p_capability, null, now())
    on conflict (admin_id, capability) do nothing;

    return jsonb_build_object(
        'success', true,
        'admin_id', p_admin_id,
        'capability', p_capability
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.bootstrap_admin_capability(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.bootstrap_admin_capability(uuid, text) TO service_role;

-- ----------------------------------------------------------------------------
-- 3. Read Audit Events RPC
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_get_action_events(
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid,
    p_limit integer DEFAULT 50,
    p_action text DEFAULT NULL::text,
    p_target_type text DEFAULT NULL::text,
    p_actor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    v_caller_id := public.assert_unlocked_admin();
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    with raw_events as (
        select
            e.id,
            e.actor_id,
            p.email as actor_email,
            coalesce(p.display_name, 'Admin') as actor_name,
            e.action,
            e.target_type,
            e.target_id,
            e.reason,
            e.old_state,
            e.new_state,
            e.request_id,
            e.created_at
        from public.admin_action_events e
        left join public.profiles p on p.id = e.actor_id
        where (p_cursor_created_at is null or (e.created_at, e.id) < (p_cursor_created_at, p_cursor_id))
          and (p_action is null or e.action = p_action)
          and (p_target_type is null or e.target_type = p_target_type)
          and (p_actor_id is null or e.actor_id = p_actor_id)
        order by e.created_at desc, e.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_events r;

    if v_count > v_limit then
        v_has_more := true;
        -- Bỏ phần tử thứ v_limit + 1 khỏi mảng kết quả
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_action_events(timestamptz, uuid, integer, text, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_action_events(timestamptz, uuid, integer, text, text, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 4. Member Management RPCs
-- ----------------------------------------------------------------------------

-- Search members server-side without 500 limit
CREATE OR REPLACE FUNCTION public.admin_search_members(
    p_query text DEFAULT NULL::text,
    p_role text DEFAULT NULL::text,
    p_is_locked boolean DEFAULT NULL::boolean,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
    v_clean_query text;
    v_is_uuid boolean := false;
    v_query_uuid uuid := null;
    v_escaped_pattern text := null;
begin
    v_caller_id := public.assert_unlocked_admin();
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    if p_query is not null and btrim(p_query) != '' then
        v_clean_query := btrim(p_query);
        if v_clean_query ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
            v_is_uuid := true;
            v_query_uuid := v_clean_query::uuid;
        end if;
        v_escaped_pattern := '%' || replace(replace(replace(v_clean_query, '\', '\\'), '%', '\%'), '_', '\_') || '%';
    end if;

    with raw_members as (
        select
            p.id,
            p.email,
            p.display_name,
            p.avatar_url,
            p.credits,
            p.role,
            coalesce(p.is_locked, false) as is_locked,
            p.created_at,
            p.last_login_date,
            p.daily_missed_streak
        from public.profiles p
        where (p_cursor_created_at is null or (p.created_at, p.id) < (p_cursor_created_at, p_cursor_id))
          and (p_role is null or p.role = p_role)
          and (p_is_locked is null or coalesce(p.is_locked, false) = p_is_locked)
          and (
              v_escaped_pattern is null or (
                  (v_is_uuid and p.id = v_query_uuid)
                  or p.display_name ilike v_escaped_pattern escape '\'
                  or p.email ilike v_escaped_pattern escape '\'
              )
          )
        order by p.created_at desc, p.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_members r;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_search_members(text, text, boolean, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_search_members(text, text, boolean, integer, timestamptz, uuid) TO authenticated, service_role;

-- Member overview with campaigns/slots/warnings counts
CREATE OR REPLACE FUNCTION public.admin_get_member_overview(p_user_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_prof record;
    v_camp_total int := 0;
    v_camp_open int := 0;
    v_slots_total int := 0;
    v_slots_active int := 0;
    v_warnings_active int := 0;
    v_warnings_revoked int := 0;
    v_tester_reports int := 0;
    v_contact_reports int := 0;
    v_slots_array jsonb := '[]'::jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    select
        p.id,
        p.email,
        p.display_name,
        p.avatar_url,
        p.credits,
        p.role,
        coalesce(p.is_locked, false) as is_locked,
        p.created_at,
        p.last_login_date,
        p.daily_missed_streak
    into v_prof
    from public.profiles p
    where p.id = p_user_id;

    if not found or v_prof.id is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    select count(*), count(*) filter (where status = 'open')
    into v_camp_total, v_camp_open
    from public.campaigns
    where owner_id = p_user_id;

    select count(*), count(*) filter (where expires_at > now())
    into v_slots_total, v_slots_active
    from public.user_slots
    where user_id = p_user_id;

    select
        count(*) filter (where r.id is null),
        count(*) filter (where r.id is not null)
    into v_warnings_active, v_warnings_revoked
    from public.user_warnings w
    left join public.user_warning_revocations r on r.warning_id = w.id
    where w.user_id = p_user_id;

    select count(*) into v_tester_reports
    from public.tester_reports
    where user_id = p_user_id;

    select count(*) into v_contact_reports
    from public.contact_reports
    where user_id = p_user_id;

    select coalesce(jsonb_agg(slot_info order by s_num asc), '[]'::jsonb)
    into v_slots_array
    from (
        select
            s_num,
            jsonb_build_object(
                'slot_number', s_num,
                'is_active', case when s_num = 1 then true else coalesce(us.expires_at > now(), false) end,
                'unlocked_at', case when s_num = 1 then to_char(v_prof.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') else to_char(us.unlocked_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') end,
                'expires_at', case when s_num = 1 then null else to_char(us.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') end,
                'current_campaign', case when c.id is not null then jsonb_build_object(
                    'id', c.id,
                    'app_name', c.app_name,
                    'package_name', c.package_name,
                    'status', c.status
                ) else null end
            ) as slot_info
        from generate_series(1, 6) s_num
        left join public.user_slots us on us.user_id = p_user_id and us.slot_number = s_num
        left join lateral (
            select id, app_name, package_name, status
            from public.campaigns
            where owner_id = p_user_id
              and slot_number = s_num
              and status in ('open', 'paused')
            order by created_at desc
            limit 1
        ) c on true
        where s_num = 1 or us.id is not null
    ) slots_sub;

    return jsonb_build_object(
        'id', v_prof.id,
        'email', v_prof.email,
        'display_name', v_prof.display_name,
        'avatar_url', v_prof.avatar_url,
        'credits', v_prof.credits,
        'role', v_prof.role,
        'is_locked', v_prof.is_locked,
        'created_at', to_char(v_prof.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'last_login_date', v_prof.last_login_date,
        'daily_missed_streak', v_prof.daily_missed_streak,
        'campaigns_total', v_camp_total,
        'campaigns_open', v_camp_open,
        'slots_total', v_slots_total,
        'slots_active', v_slots_active,
        'slots', v_slots_array,
        'warnings_active', v_warnings_active,
        'warnings_revoked', v_warnings_revoked,
        'tester_reports_count', v_tester_reports,
        'contact_reports_count', v_contact_reports
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_member_overview(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_member_overview(uuid) TO authenticated, service_role;

-- Dedicated member slots reader RPC
CREATE OR REPLACE FUNCTION public.admin_get_member_slots(p_user_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_overview jsonb;
begin
    v_overview := public.admin_get_member_overview(p_user_id);
    return jsonb_build_object(
        'user_id', p_user_id,
        'slots', v_overview->'slots',
        'slots_total', v_overview->'slots_total',
        'slots_active', v_overview->'slots_active'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_member_slots(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_member_slots(uuid) TO authenticated, service_role;

-- Paginated credit history
CREATE OR REPLACE FUNCTION public.admin_get_member_credit_history(
    p_user_id uuid,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    if not exists (select 1 from public.profiles where id = p_user_id) then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    with raw_logs as (
        select
            l.id,
            l.user_id,
            l.amount,
            l.action,
            l.note,
            l.balance,
            l.created_at,
            l.metadata
        from public.credit_logs l
        where l.user_id = p_user_id
          and (p_cursor_created_at is null or (l.created_at, l.id) < (p_cursor_created_at, p_cursor_id))
        order by l.created_at desc, l.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_logs r;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_member_credit_history(uuid, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_member_credit_history(uuid, integer, timestamptz, uuid) TO authenticated, service_role;

-- Daily task status history and explanation
CREATE OR REPLACE FUNCTION public.admin_get_member_daily_tasks(
    p_user_id uuid,
    p_limit integer DEFAULT 14,
    p_cursor_date date DEFAULT NULL::date
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_start_date date;
    v_d date;
    v_items jsonb := '[]'::jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_task_status jsonb;
    v_finalized_task record;
    v_is_finalized boolean;
    v_count int := 0;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    if not exists (select 1 from public.profiles where id = p_user_id) then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_limit := greatest(1, least(coalesce(p_limit, 14), 60));
    v_start_date := coalesce(p_cursor_date - 1, v_today);

    for v_d in
        select d::date
        from generate_series(v_start_date, v_start_date - (v_limit || ' days')::interval, '-1 day'::interval) d
    loop
        v_count := v_count + 1;
        if v_count > v_limit then
            v_has_more := true;
            v_next_cursor := jsonb_build_object('date', to_char(v_d + 1, 'YYYY-MM-DD'));
            exit;
        end if;

        select * into v_finalized_task
        from public.user_daily_tasks
        where user_id = p_user_id
          and task_date = v_d
          and finalized_at is not null;

        v_is_finalized := (v_finalized_task.task_date is not null);
        v_task_status := public.get_daily_task_status(p_user_id, v_d);

        v_items := v_items || jsonb_build_array(jsonb_build_object(
            'task_date', to_char(v_d, 'YYYY-MM-DD'),
            'is_finalized', v_is_finalized,
            'finalized_at', case when v_is_finalized then to_char(v_finalized_task.finalized_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') else null end,
            'penalty_credits', coalesce(v_finalized_task.penalty_credits, 0),
            'incomplete_history', not coalesce((v_task_status->>'history_ready')::boolean, true),
            'status', v_task_status
        ));
    end loop;

    return jsonb_build_object(
        'items', v_items,
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_member_daily_tasks(uuid, integer, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_member_daily_tasks(uuid, integer, date) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. Carryover Policy Management RPCs
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_get_carryover_policies()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_active record;
    v_active_json jsonb;
    v_future jsonb;
    v_history jsonb;
    v_revision bigint;
begin
    v_caller_id := public.assert_unlocked_admin();

    select coalesce(max(id), 0) into v_revision
    from public.daily_task_carryover_policy_audit;

    -- Active policy is latest row effective_from <= v_today
    select * into v_active
    from public.daily_task_carryover_policies
    where effective_from <= v_today
    order by effective_from desc
    limit 1;

    if v_active.id is not null then
        v_active_json := jsonb_build_object(
            'id', v_active.id::text,
            'effective_from', to_char(v_active.effective_from, 'YYYY-MM-DD'),
            'is_enabled', v_active.is_enabled,
            'operator_note', v_active.operator_note,
            'created_at', to_char(v_active.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        );
    else
        -- Defined empty state
        v_active_json := jsonb_build_object(
            'id', null,
            'effective_from', null,
            'is_enabled', true,
            'operator_note', 'Chính sách mặc định (bật cộng dồn)',
            'created_at', null
        );
    end if;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', p.id::text,
        'effective_from', to_char(p.effective_from, 'YYYY-MM-DD'),
        'is_enabled', p.is_enabled,
        'operator_note', p.operator_note,
        'created_at', to_char(p.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by p.effective_from asc), '[]'::jsonb)
    into v_future
    from (
        select * from public.daily_task_carryover_policies
        where effective_from > v_today
        order by effective_from asc
        limit 50
    ) p;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', p.id::text,
        'effective_from', to_char(p.effective_from, 'YYYY-MM-DD'),
        'is_enabled', p.is_enabled,
        'operator_note', p.operator_note,
        'created_at', to_char(p.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by p.effective_from desc), '[]'::jsonb)
    into v_history
    from (
        select * from public.daily_task_carryover_policies
        where effective_from <= v_today
        order by effective_from desc
        limit 50
    ) p;

    return jsonb_build_object(
        'active', v_active_json,
        'future', v_future,
        'history', v_history,
        'revision', v_revision::text
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_carryover_policies() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_carryover_policies() TO authenticated, service_role;

-- Paginated carryover policy audit and cancellation event history
CREATE OR REPLACE FUNCTION public.admin_get_carryover_policy_history(
    p_limit integer DEFAULT 50,
    p_cursor_id bigint DEFAULT NULL::bigint,
    p_include_cancelled boolean DEFAULT true
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    v_caller_id := public.assert_unlocked_admin();
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    with raw_audits as (
        select
            a.id,
            a.policy_id,
            to_char(a.effective_from, 'YYYY-MM-DD') as effective_from,
            a.old_is_enabled,
            a.new_is_enabled,
            a.old_operator_note,
            a.new_operator_note,
            (a.new_operator_note like 'CANCELLED:%') as is_cancelled,
            case when a.new_operator_note like 'CANCELLED:%' then
                substring(a.new_operator_note from '^CANCELLED:\s*(.*)$')
            else a.new_operator_note end as clean_note,
            a.changed_by,
            to_char(a.changed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as changed_at
        from public.daily_task_carryover_policy_audit a
        where (p_cursor_id is null or a.id < p_cursor_id)
          and (p_include_cancelled or not (a.new_operator_note like 'CANCELLED:%'))
        order by a.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_audits r;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'id', (v_rows->(v_limit - 1)->>'id')::bigint
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_carryover_policy_history(integer, bigint, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_carryover_policy_history(integer, bigint, boolean) TO authenticated, service_role;

-- Dedicated paginated future carryover policies reader RPC
CREATE OR REPLACE FUNCTION public.admin_list_future_carryover_policies(
    p_limit integer DEFAULT 50,
    p_cursor_date date DEFAULT NULL::date
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_cursor_date is not null and (not isfinite(p_cursor_date) or p_cursor_date = 'infinity'::date or p_cursor_date = '-infinity'::date) then
        raise exception 'INVALID_CURSOR_DATE: Ngày con trỏ không hợp lệ hoặc vô hạn';
    end if;

    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    with raw_future as (
        select
            p.id::text as id,
            to_char(p.effective_from, 'YYYY-MM-DD') as effective_from,
            p.is_enabled,
            p.operator_note,
            to_char(p.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as created_at
        from public.daily_task_carryover_policies p
        where p.effective_from > v_today
          and (p_cursor_date is null or p.effective_from > p_cursor_date)
        order by p.effective_from asc, p.id asc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_future r;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'date', v_rows->(v_limit - 1)->>'effective_from'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_list_future_carryover_policies(integer, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_list_future_carryover_policies(integer, date) TO authenticated, service_role;

-- Schedule carryover policy wrapper
CREATE OR REPLACE FUNCTION public.admin_schedule_carryover_policy(
    p_effective_from date,
    p_is_enabled boolean,
    p_operator_note text,
    p_expected_revision bigint DEFAULT NULL::bigint,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_curr_rev bigint;
    v_res jsonb;
    v_clean_note text;
    v_new_rev bigint;
    v_payload_hash text;
    v_existing_req record;
    v_canonical_payload jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_effective_from is null or not isfinite(p_effective_from) then
        raise exception 'INVALID_EFFECTIVE_DATE: Ngày hiệu lực không được để trống hoặc vô hạn';
    end if;

    if p_effective_from <= v_today then
        raise exception 'CANNOT_MODIFY_HISTORICAL_POLICY: Chính sách phải được lên lịch từ ngày tương lai (sau ngày hiện tại)';
    end if;

    v_clean_note := btrim(coalesce(p_operator_note, ''));
    if v_clean_note = '' then
        raise exception 'NOTE_REQUIRED: Lý do điều chỉnh chính sách là bắt buộc';
    end if;

    if length(v_clean_note) > 1000 then
        raise exception 'NOTE_TOO_LONG: Ghi chú không được vượt quá 1000 ký tự';
    end if;

    if p_request_id is not null then
        perform pg_advisory_xact_lock(hashtext(v_caller_id::text || ':schedule_carryover_policy:' || p_request_id::text));

        v_canonical_payload := jsonb_build_object(
            'effective_from', to_char(p_effective_from, 'YYYY-MM-DD'),
            'is_enabled', coalesce(p_is_enabled, true),
            'operator_note', v_clean_note
        );
        v_payload_hash := md5(v_canonical_payload::text);

        select * into v_existing_req
        from public.admin_operation_requests
        where actor_id = v_caller_id
          and operation = 'schedule_carryover_policy'
          and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash = v_payload_hash then
                return v_existing_req.result || jsonb_build_object('is_replay', true);
            else
                raise exception 'REQUEST_ID_PAYLOAD_MISMATCH: Request ID đã được sử dụng với tham số khác';
            end if;
        end if;
    end if;

    -- Concurrency advisory lock
    perform pg_advisory_xact_lock(hashtext('carryover_policy_write_lock'));

    select coalesce(max(id), 0) into v_curr_rev
    from public.daily_task_carryover_policy_audit;

    if p_expected_revision is not null and p_expected_revision != v_curr_rev then
        raise exception 'REVISION_CONFLICT: Phiên bản chính sách không khớp, vui lòng làm mới';
    end if;

    -- Call internal service-role business function
    v_res := public.set_daily_task_carryover_policy(p_effective_from, p_is_enabled, v_clean_note);

    select coalesce(max(id), 0) into v_new_rev
    from public.daily_task_carryover_policy_audit;

    -- Record in admin audit events
    perform public.record_admin_action_event(
        v_caller_id,
        'schedule_carryover_policy',
        'carryover_policy',
        to_char(p_effective_from, 'YYYY-MM-DD'),
        v_clean_note,
        null,
        v_res,
        p_request_id
    );

    v_res := jsonb_build_object(
        'success', true,
        'effective_from', to_char(p_effective_from, 'YYYY-MM-DD'),
        'is_enabled', p_is_enabled,
        'operator_note', v_clean_note,
        'revision', v_new_rev::text
    );

    if p_request_id is not null then
        insert into public.admin_operation_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_caller_id, 'schedule_carryover_policy', v_payload_hash, v_res, now()
        );
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_schedule_carryover_policy(date, boolean, text, bigint, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_schedule_carryover_policy(date, boolean, text, bigint, uuid) TO authenticated, service_role;

-- Cancel future carryover policy wrapper
CREATE OR REPLACE FUNCTION public.admin_cancel_future_carryover_policy(
    p_effective_from date,
    p_reason text,
    p_expected_revision bigint DEFAULT NULL::bigint,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_curr_rev bigint;
    v_clean_reason text;
    v_target_row public.daily_task_carryover_policies;
    v_new_rev bigint;
    v_payload_hash text;
    v_existing_req record;
    v_canonical_payload jsonb;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_effective_from is null or not isfinite(p_effective_from) then
        raise exception 'INVALID_EFFECTIVE_DATE: Ngày hiệu lực không được để trống hoặc vô hạn';
    end if;

    if p_effective_from <= v_today then
        raise exception 'CANNOT_CANCEL_NON_FUTURE_POLICY: Chỉ được phép hủy chính sách trong tương lai';
    end if;

    v_clean_reason := btrim(coalesce(p_reason, ''));
    if v_clean_reason = '' then
        raise exception 'REASON_REQUIRED: Lý do hủy chính sách là bắt buộc';
    end if;

    if length(v_clean_reason) > 1000 then
        raise exception 'REASON_TOO_LONG: Lý do không được vượt quá 1000 ký tự';
    end if;

    if p_request_id is not null then
        perform pg_advisory_xact_lock(hashtext(v_caller_id::text || ':cancel_carryover_policy:' || p_request_id::text));

        v_canonical_payload := jsonb_build_object(
            'effective_from', to_char(p_effective_from, 'YYYY-MM-DD'),
            'reason', v_clean_reason
        );
        v_payload_hash := md5(v_canonical_payload::text);

        select * into v_existing_req
        from public.admin_operation_requests
        where actor_id = v_caller_id
          and operation = 'cancel_carryover_policy'
          and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash = v_payload_hash then
                return v_existing_req.result || jsonb_build_object('is_replay', true);
            else
                raise exception 'REQUEST_ID_PAYLOAD_MISMATCH: Request ID đã được sử dụng với tham số khác';
            end if;
        end if;
    end if;

    perform pg_advisory_xact_lock(hashtext('carryover_policy_write_lock'));

    select coalesce(max(id), 0) into v_curr_rev
    from public.daily_task_carryover_policy_audit;

    if p_expected_revision is not null and p_expected_revision != v_curr_rev then
        raise exception 'REVISION_CONFLICT: Phiên bản chính sách không khớp, vui lòng làm mới';
    end if;

    select * into v_target_row
    from public.daily_task_carryover_policies
    where effective_from = p_effective_from;

    if not found or v_target_row.id is null then
        raise exception 'POLICY_NOT_FOUND: Không tìm thấy chính sách tương lai ứng với ngày đã cho';
    end if;

    delete from public.daily_task_carryover_policies
    where effective_from = p_effective_from;

    insert into public.daily_task_carryover_policy_audit (
        policy_id, effective_from, old_is_enabled, new_is_enabled,
        old_operator_note, new_operator_note, changed_by, changed_at
    ) values (
        v_target_row.id, p_effective_from, v_target_row.is_enabled, v_target_row.is_enabled,
        v_target_row.operator_note, 'CANCELLED: ' || v_clean_reason, 'admin:' || v_caller_id::text, clock_timestamp()
    );

    select coalesce(max(id), 0) into v_new_rev
    from public.daily_task_carryover_policy_audit;

    perform public.record_admin_action_event(
        v_caller_id,
        'cancel_carryover_policy',
        'carryover_policy',
        to_char(p_effective_from, 'YYYY-MM-DD'),
        v_clean_reason,
        to_jsonb(v_target_row),
        null,
        p_request_id
    );

    v_res := jsonb_build_object(
        'success', true,
        'cancelled_date', to_char(p_effective_from, 'YYYY-MM-DD'),
        'reason', v_clean_reason,
        'revision', v_new_rev::text
    );

    if p_request_id is not null then
        insert into public.admin_operation_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_caller_id, 'cancel_carryover_policy', v_payload_hash, v_res, now()
        );
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_cancel_future_carryover_policy(date, text, bigint, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_cancel_future_carryover_policy(date, text, bigint, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 6. Campaign Search & Tester Report Queue RPCs
-- ----------------------------------------------------------------------------

-- Search campaigns across all statuses
CREATE OR REPLACE FUNCTION public.admin_search_campaigns(
    p_query text DEFAULT NULL::text,
    p_status text DEFAULT NULL::text,
    p_owner_id uuid DEFAULT NULL::uuid,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
    v_clean_query text;
    v_is_uuid boolean := false;
    v_query_uuid uuid := null;
    v_escaped_pattern text := null;
begin
    v_caller_id := public.assert_unlocked_admin();
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    if p_query is not null and btrim(p_query) != '' then
        v_clean_query := btrim(p_query);
        if v_clean_query ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
            v_is_uuid := true;
            v_query_uuid := v_clean_query::uuid;
        end if;
        v_escaped_pattern := '%' || replace(replace(replace(v_clean_query, '\', '\\'), '%', '\%'), '_', '\_') || '%';
    end if;

    with raw_campaigns as (
        select
            c.id,
            c.owner_id,
            p.display_name as owner_name,
            p.email as owner_email,
            c.app_name,
            c.package_name,
            c.status,
            c.target_testers,
            (select count(*) from public.campaign_members cm where cm.campaign_id = c.id) as current_testers,
            c.paused_reason,
            c.created_at,
            (select count(*) from public.tester_reports tr where tr.campaign_id = c.id and tr.status = 'pending') as pending_reports_count
        from public.campaigns c
        left join public.profiles p on p.id = c.owner_id
        where (p_cursor_created_at is null or (c.created_at, c.id) < (p_cursor_created_at, p_cursor_id))
          and (p_status is null or c.status = p_status)
          and (p_owner_id is null or c.owner_id = p_owner_id)
          and (
              v_escaped_pattern is null or (
                  (v_is_uuid and c.id = v_query_uuid)
                  or c.app_name ilike v_escaped_pattern escape '\'
                  or c.package_name ilike v_escaped_pattern escape '\'
              )
          )
        order by c.created_at desc, c.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_campaigns r;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_search_campaigns(text, text, uuid, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_search_campaigns(text, text, uuid, integer, timestamptz, uuid) TO authenticated, service_role;

-- Tester reports queue with joined display data
CREATE OR REPLACE FUNCTION public.admin_get_tester_reports_queue(
    p_status text DEFAULT 'pending'::text,
    p_campaign_id uuid DEFAULT NULL::uuid,
    p_tester_id uuid DEFAULT NULL::uuid,
    p_owner_id uuid DEFAULT NULL::uuid,
    p_submission_context text DEFAULT NULL::text,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    v_caller_id := public.assert_unlocked_admin();
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    with raw_reports as (
        select
            tr.id,
            tr.campaign_id,
            c.app_name as campaign_name,
            c.package_name,
            c.owner_id,
            po.display_name as owner_name,
            tr.user_id as tester_id,
            pt.display_name as tester_name,
            pt.email as tester_email,
            tr.report_type,
            tr.content,
            tr.screenshot_url,
            tr.thumbnail_url,
            tr.media_expired,
            tr.rating,
            tr.status,
            tr.submission_context,
            tr.mission_title,
            tr.created_at,
            tr.reviewed_at,
            tr.rejection_reason
        from public.tester_reports tr
        inner join public.campaigns c on c.id = tr.campaign_id
        left join public.profiles po on po.id = c.owner_id
        left join public.profiles pt on pt.id = tr.user_id
        where (p_cursor_created_at is null or (tr.created_at, tr.id) < (p_cursor_created_at, p_cursor_id))
          and (p_status is null or tr.status = p_status)
          and (p_campaign_id is null or tr.campaign_id = p_campaign_id)
          and (p_tester_id is null or tr.user_id = p_tester_id)
          and (p_owner_id is null or c.owner_id = p_owner_id)
          and (p_submission_context is null or tr.submission_context = p_submission_context)
        order by tr.created_at desc, tr.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_reports r;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 7. Safe Credit Compensation RPC
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_compensate_credits(
    p_request_id uuid,
    p_target_user_id uuid,
    p_amount integer,
    p_reason text,
    p_original_credit_log_id uuid DEFAULT NULL::uuid,
    p_contact_report_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_payload_hash text;
    v_existing_req record;
    v_clean_reason text;
    v_adjust_res jsonb;
    v_res jsonb;
    v_current_credits int;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_request_id is null then
        raise exception 'REQUEST_ID_REQUIRED: Yêu cầu bồi hoàn phải có request_id';
    end if;

    if p_target_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    if p_amount is null or p_amount = 0 then
        raise exception 'INVALID_AMOUNT: Số lượng credit phải khác 0';
    end if;

    v_clean_reason := btrim(coalesce(p_reason, ''));
    if v_clean_reason = '' then
        raise exception 'REASON_REQUIRED: Lý do bồi hoàn là bắt buộc';
    end if;

    -- Canonical JSONB payload hash for idempotency mismatch verification
    v_payload_hash := md5(jsonb_build_object(
        'target_user_id', p_target_user_id,
        'amount', p_amount,
        'reason', v_clean_reason,
        'original_credit_log_id', p_original_credit_log_id,
        'contact_report_id', p_contact_report_id
    )::text);

    -- Transaction level lock on (actor_id, operation, request_id)
    perform pg_advisory_xact_lock(hashtext(v_caller_id::text || ':credit_compensation:' || p_request_id::text));

    select * into v_existing_req
    from public.admin_operation_requests
    where actor_id = v_caller_id
      and operation = 'credit_compensation'
      and id = p_request_id;

    if v_existing_req.id is not null then
        if v_existing_req.payload_hash = v_payload_hash then
            -- Idempotent retry: return original result with replay flag
            return v_existing_req.result || jsonb_build_object('is_replay', true);
        else
            raise exception 'REQUEST_ID_PAYLOAD_MISMATCH: Request ID đã được sử dụng với tham số khác';
        end if;
    end if;

    -- Verify target existence and lock row
    select credits into v_current_credits
    from public.profiles
    where id = p_target_user_id
    for update;

    if not found or v_current_credits is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    -- Validate optional references
    if p_original_credit_log_id is not null then
        if not exists (select 1 from public.credit_logs where id = p_original_credit_log_id and user_id = p_target_user_id) then
            raise exception 'ORIGINAL_CREDIT_LOG_NOT_FOUND: Không tìm thấy giao dịch credit gốc của người dùng';
        end if;
    end if;

    if p_contact_report_id is not null then
        if not exists (select 1 from public.contact_reports where id = p_contact_report_id) then
            raise exception 'CONTACT_REPORT_NOT_FOUND: Không tìm thấy báo cáo khiếu nại liên quan';
        end if;
    end if;

    -- Call canonical admin_adjust_credits
    v_adjust_res := public.admin_adjust_credits(p_target_user_id, p_amount, v_clean_reason);

    v_res := jsonb_build_object(
        'success', true,
        'request_id', p_request_id,
        'user_id', p_target_user_id,
        'previous_credits', v_adjust_res->'previous_credits',
        'new_credits', v_adjust_res->'new_credits',
        'amount', v_adjust_res->'amount',
        'reason', v_clean_reason,
        'original_credit_log_id', p_original_credit_log_id,
        'contact_report_id', p_contact_report_id,
        'is_replay', false
    );

    -- Record admin action audit in same transaction
    perform public.record_admin_action_event(
        v_caller_id,
        'compensate_credits',
        'user',
        p_target_user_id::text,
        v_clean_reason,
        jsonb_build_object('credits', v_adjust_res->'previous_credits'),
        jsonb_build_object(
            'credits', v_adjust_res->'new_credits',
            'amount', v_adjust_res->'amount',
            'original_credit_log_id', p_original_credit_log_id,
            'contact_report_id', p_contact_report_id
        ),
        p_request_id
    );

    -- Save in request idempotency table
    insert into public.admin_operation_requests (
        id, actor_id, operation, payload_hash, result, created_at
    ) values (
        p_request_id, v_caller_id, 'credit_compensation', v_payload_hash, v_res, now()
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_compensate_credits(uuid, uuid, integer, text, uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_compensate_credits(uuid, uuid, integer, text, uuid, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 8. Role Management & Capabilities RPCs
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_get_my_capabilities()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_caps jsonb;
    v_can_manage_admins boolean := false;
begin
    v_caller_id := public.assert_unlocked_admin();

    select coalesce(jsonb_agg(capability), '[]'::jsonb),
           bool_or(capability = 'admin_management')
    into v_caps, v_can_manage_admins
    from public.admin_capabilities
    where admin_id = v_caller_id;

    return jsonb_build_object(
        'caller_id', v_caller_id,
        'role', 'admin',
        'is_locked', false,
        'capabilities', coalesce(v_caps, '[]'::jsonb),
        'can_manage_admins', coalesce(v_can_manage_admins, false),
        'can_manage_moderators', true
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_my_capabilities() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_my_capabilities() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_set_user_role(
    p_target_user_id uuid,
    p_new_role text,
    p_reason text,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_clean_role text;
    v_clean_reason text;
    v_target_role text;
    v_target_locked boolean;
    v_has_admin_mgmt boolean := false;
    v_admin_count int;
    v_res jsonb;
    v_payload_hash text;
    v_existing_req record;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_target_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    v_clean_role := lower(btrim(coalesce(p_new_role, '')));
    if v_clean_role not in ('member', 'moderator', 'admin') then
        raise exception 'INVALID_ROLE: Vai trò chỉ nhận member, moderator hoặc admin';
    end if;

    v_clean_reason := btrim(coalesce(p_reason, ''));
    if v_clean_reason = '' then
        raise exception 'REASON_REQUIRED: Lý do điều chỉnh vai trò là bắt buộc';
    end if;

    if p_target_user_id = v_caller_id then
        raise exception 'CANNOT_CHANGE_OWN_ROLE: Không thể tự thay đổi vai trò của chính mình';
    end if;

    -- Concurrency lock
    perform pg_advisory_xact_lock(hashtext('admin_set_user_role_lock'));

    if p_request_id is not null then
        perform pg_advisory_xact_lock(hashtext(v_caller_id::text || ':set_user_role:' || p_request_id::text));
        v_payload_hash := md5(jsonb_build_object(
            'target_user_id', p_target_user_id,
            'new_role', v_clean_role,
            'reason', v_clean_reason
        )::text);

        select * into v_existing_req
        from public.admin_operation_requests
        where actor_id = v_caller_id
          and operation = 'set_user_role'
          and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash = v_payload_hash then
                return v_existing_req.result || jsonb_build_object('is_replay', true);
            else
                raise exception 'REQUEST_ID_PAYLOAD_MISMATCH: Request ID đã được sử dụng với tham số khác';
            end if;
        end if;
    end if;

    -- Lock target profile
    select role, coalesce(is_locked, false)
    into v_target_role, v_target_locked
    from public.profiles
    where id = p_target_user_id
    for update;

    if not found or v_target_role is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    -- Check explicit capability if touching admin role
    if v_clean_role = 'admin' or lower(v_target_role) = 'admin' then
        select exists (
            select 1
            from public.admin_capabilities
            where admin_id = v_caller_id
              and capability = 'admin_management'
        ) into v_has_admin_mgmt;

        if not v_has_admin_mgmt then
            raise exception 'INSUFFICIENT_PRIVILEGE: Cần quyền admin_management để bổ nhiệm hoặc hạ quyền Quản trị viên';
        end if;
    end if;

    -- Preserve last unlocked admin
    if lower(v_target_role) = 'admin' and v_clean_role != 'admin' then
        select count(*) into v_admin_count
        from public.profiles
        where role = 'admin'
          and coalesce(is_locked, false) = false
          and id != p_target_user_id;

        if v_admin_count = 0 then
            raise exception 'CANNOT_REMOVE_LAST_ADMIN: Không thể hạ quyền Quản trị viên cuối cùng trong hệ thống';
        end if;
    end if;

    update public.profiles
    set role = v_clean_role
    where id = p_target_user_id;

    v_res := jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'previous_role', v_target_role,
        'new_role', v_clean_role,
        'reason', v_clean_reason
    );

    perform public.record_admin_action_event(
        v_caller_id,
        'set_user_role',
        'user',
        p_target_user_id::text,
        v_clean_reason,
        jsonb_build_object('role', v_target_role),
        jsonb_build_object('role', v_clean_role),
        p_request_id
    );

    if p_request_id is not null then
        insert into public.admin_operation_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_caller_id, 'set_user_role', v_payload_hash, v_res, now()
        );
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_set_user_role(uuid, text, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_user_role(uuid, text, text, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 9. Warning Management & Consistency RPCs
-- ----------------------------------------------------------------------------

-- Get warnings with revocation status
CREATE OR REPLACE FUNCTION public.admin_get_member_warnings(
    p_user_id uuid,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    if not exists (select 1 from public.profiles where id = p_user_id) then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    with raw_warnings as (
        select
            w.id,
            w.user_id,
            w.warning_type,
            w.message,
            to_char(w.warning_date, 'YYYY-MM-DD') as warning_date,
            w.created_at,
            w.acknowledged,
            (r.id is not null) as is_revoked,
            case when r.id is not null then jsonb_build_object(
                'revoked_at', to_char(r.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'revoked_by', r.revoked_by,
                'reason', r.reason
            ) else null end as revocation
        from public.user_warnings w
        left join public.user_warning_revocations r on r.warning_id = w.id
        where w.user_id = p_user_id
          and (p_cursor_created_at is null or (w.created_at, w.id) < (p_cursor_created_at, p_cursor_id))
        order by w.created_at desc, w.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(rw)), count(*)
    into v_rows, v_count
    from raw_warnings rw;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_member_warnings(uuid, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_member_warnings(uuid, integer, timestamptz, uuid) TO authenticated, service_role;

-- Revoke warning (append-only)
CREATE OR REPLACE FUNCTION public.admin_revoke_warning(
    p_warning_id uuid,
    p_reason text,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_clean_reason text;
    v_warning record;
    v_existing_rev record;
    v_payload_hash text;
    v_existing_req record;
    v_canonical_payload jsonb;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_warning_id is null then
        raise exception 'WARNING_ID_REQUIRED';
    end if;

    v_clean_reason := btrim(coalesce(p_reason, ''));
    if v_clean_reason = '' then
        raise exception 'REASON_REQUIRED: Lý do thu hồi cảnh báo là bắt buộc';
    end if;

    if length(v_clean_reason) > 1000 then
        raise exception 'REASON_TOO_LONG: Lý do không được vượt quá 1000 ký tự';
    end if;

    if p_request_id is not null then
        perform pg_advisory_xact_lock(hashtext(v_caller_id::text || ':revoke_warning:' || p_request_id::text));

        v_canonical_payload := jsonb_build_object(
            'warning_id', p_warning_id,
            'reason', v_clean_reason
        );
        v_payload_hash := md5(v_canonical_payload::text);

        select * into v_existing_req
        from public.admin_operation_requests
        where actor_id = v_caller_id
          and operation = 'revoke_warning'
          and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash = v_payload_hash then
                return v_existing_req.result || jsonb_build_object('is_replay', true);
            else
                raise exception 'REQUEST_ID_PAYLOAD_MISMATCH: Request ID đã được sử dụng với tham số khác';
            end if;
        end if;
    end if;

    -- Concurrency lock
    perform pg_advisory_xact_lock(hashtext('revoke_warning_' || p_warning_id::text));

    select * into v_warning
    from public.user_warnings
    where id = p_warning_id
    for update;

    if not found or v_warning.id is null then
        raise exception 'WARNING_NOT_FOUND';
    end if;

    select * into v_existing_rev
    from public.user_warning_revocations
    where warning_id = p_warning_id;

    if v_existing_rev.id is not null then
        v_res := jsonb_build_object(
            'success', true,
            'warning_id', p_warning_id,
            'already_revoked', true,
            'revoked_at', to_char(v_existing_rev.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'reason', v_existing_rev.reason
        );
        if p_request_id is not null then
            insert into public.admin_operation_requests (
                id, actor_id, operation, payload_hash, result, created_at
            ) values (
                p_request_id, v_caller_id, 'revoke_warning', v_payload_hash, v_res, now()
            ) on conflict (actor_id, operation, id) do nothing;
        end if;
        return v_res;
    end if;

    insert into public.user_warning_revocations (
        warning_id, revoked_by, reason, request_id, created_at
    ) values (
        p_warning_id, v_caller_id, v_clean_reason, p_request_id, now()
    );

    perform public.record_admin_action_event(
        v_caller_id,
        'revoke_warning',
        'warning',
        p_warning_id::text,
        v_clean_reason,
        to_jsonb(v_warning),
        jsonb_build_object('is_revoked', true),
        p_request_id
    );

    v_res := jsonb_build_object(
        'success', true,
        'warning_id', p_warning_id,
        'revoked', true,
        'reason', v_clean_reason
    );

    if p_request_id is not null then
        insert into public.admin_operation_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_caller_id, 'revoke_warning', v_payload_hash, v_res, now()
        );
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_revoke_warning(uuid, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_revoke_warning(uuid, text, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 9b. Update Warning Consumer Functions to Exclude Revoked Warnings
-- ----------------------------------------------------------------------------

-- Override get_unread_warnings to exclude revoked warnings
CREATE OR REPLACE FUNCTION public.get_unread_warnings()
RETURNS SETOF public.user_warnings
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $function$
    select w.*
    from public.user_warnings w
    where w.user_id = auth.uid()
      and w.acknowledged = false
      and not exists (
          select 1 from public.user_warning_revocations r
          where r.warning_id = w.id
      )
    order by w.created_at desc;
$function$;

REVOKE ALL ON FUNCTION public.get_unread_warnings() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_unread_warnings() TO authenticated, service_role;

-- Override get_campaign_detail to exclude revoked warnings from v_unread and v_weekly
CREATE OR REPLACE FUNCTION public.get_campaign_detail(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_uid              uuid := auth.uid();
    v_today            date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp             public.campaigns%rowtype;
    v_is_owner         boolean := false;
    v_is_actual_owner  boolean := false;
    v_can_manage       boolean := false;
    v_is_member        boolean := false;
    v_member_joined_at timestamptz;
    v_confirmed        boolean := false;
    v_campaign         jsonb;
    v_testers          jsonb := '[]'::jsonb;
    v_feedbacks        jsonb := '[]'::jsonb;
    v_reports          jsonb := '[]'::jsonb;
    v_profiles         jsonb := '{}'::jsonb;
    v_today_tested     int := 0;
    v_reports_count    int := 0;
    v_tester_reports_count int := 0;
    v_pending_reports_count int := 0;
    v_avg              float;
    v_my_dates         jsonb := '[]'::jsonb;
    v_my_profile       jsonb;
    v_unread           jsonb := '[]'::jsonb;
    v_weekly           int := 0;
    v_rel              record;
    v_app_update       jsonb := null;
    v_claim            record;
    v_is_eligible      boolean := true;
    v_ineligible_reason text := null;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    perform public.auto_accept_expired_reports();

    v_is_actual_owner := (v_camp.owner_id = v_uid);

    v_can_manage := (
        v_is_actual_owner
        or exists (
            select 1 from public.profiles p
            where p.id = v_uid and lower(p.role) in ('admin', 'moderator')
        )
    ) and not exists (
        select 1 from public.profiles p
        where p.id = v_uid and coalesce(p.is_locked, false) = true
    );

    v_is_owner := v_can_manage;

    select joined_at into v_member_joined_at
    from public.campaign_members m
    where m.campaign_id = p_campaign_id and m.user_id = v_uid;

    v_is_member := (v_member_joined_at is not null);

    v_confirmed := exists (
        select 1 from public.checkins ci
        where ci.campaign_id = p_campaign_id
          and ci.user_id = v_uid
          and ci.checkin_date = v_today
          and coalesce(ci.note, '') not like '%tạm tính%'
    );

    select coalesce(jsonb_agg(jsonb_build_object(
        'user_id', t.user_id,
        'joined_at', t.joined_at,
        'days', t.days,
        'checkin_count', t.checkin_count,
        'last_checkin_date', t.last_checkin_date
    ) order by t.days desc), '[]'::jsonb)
    into v_testers
    from (
        select m.user_id,
               to_char(m.joined_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as joined_at,
               coalesce(s.days, 0) as days,
               coalesce(s.cnt, 0) as checkin_count,
               to_char(s.last_date, 'YYYY-MM-DD') as last_checkin_date
        from public.campaign_members m
        left join (
            select user_id,
                   count(distinct checkin_date) as days,
                   count(*) as cnt,
                   max(checkin_date) as last_date
            from public.checkins
            where campaign_id = p_campaign_id
            group by user_id
        ) s on s.user_id = m.user_id
        where m.campaign_id = p_campaign_id
    ) t;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', f.id,
        'campaign_id', f.campaign_id,
        'user_id', f.user_id,
        'rating', f.rating,
        'comment', f.comment,
        'created_at', to_char(f.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'updated_at', to_char(f.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by f.created_at desc), '[]'::jsonb)
    into v_feedbacks
    from public.feedbacks f
    where f.campaign_id = p_campaign_id;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', tr.id,
        'campaign_id', tr.campaign_id,
        'user_id', tr.user_id,
        'report_type', tr.report_type,
        'content', tr.content,
        'screenshot_url', tr.screenshot_url,
        'thumbnail_url', tr.thumbnail_url,
        'rating', tr.rating,
        'status', tr.status,
        'bonus_awarded', tr.bonus_awarded,
        'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'rejection_reason', tr.rejection_reason,
        'image_hash', tr.image_hash,
        'submission_context', tr.submission_context,
        'mission_id', tr.mission_id,
        'mission_participant_id', tr.mission_participant_id,
        'mission_title', tr.mission_title,
        'mission_reward_credits', tr.mission_reward_credits,
        'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by tr.created_at desc), '[]'::jsonb)
    into v_reports
    from (
        select * from public.tester_reports
        where campaign_id = p_campaign_id
          and (v_can_manage or user_id = v_uid)
        order by created_at desc
        limit 200
    ) tr;

    select count(*),
           count(*) filter (where status = 'pending')
    into v_tester_reports_count, v_pending_reports_count
    from public.tester_reports
    where campaign_id = p_campaign_id
      and (v_can_manage or user_id = v_uid);

    select coalesce(jsonb_object_agg(t.id::text, public._profile_json(p)), '{}'::jsonb)
    into v_profiles
    from public.profiles p
    join (
        select v_camp.owner_id as id
        union
        select m.user_id from public.campaign_members m where m.campaign_id = p_campaign_id
        union
        select f.user_id from public.feedbacks f where f.campaign_id = p_campaign_id
        union
        select tr.user_id from public.tester_reports tr
        where tr.campaign_id = p_campaign_id and (v_can_manage or tr.user_id = v_uid)
    ) t on t.id = p.id;

    select count(distinct user_id) into v_today_tested
    from public.checkins
    where campaign_id = p_campaign_id and checkin_date = v_today;

    select count(*), coalesce(round(avg(rating)::numeric, 1), 0.0)
    into v_reports_count, v_avg
    from public.feedbacks
    where campaign_id = p_campaign_id;

    select coalesce(jsonb_agg(to_char(ci.checkin_date, 'YYYY-MM-DD') order by ci.checkin_date asc), '[]'::jsonb)
    into v_my_dates
    from public.checkins ci
    where ci.campaign_id = p_campaign_id and ci.user_id = v_uid;

    v_campaign := row_to_json(v_camp);

    select public._profile_json(p) into v_my_profile
    from public.profiles p where p.id = v_uid;

    -- Consistent warning counts excluding revocations
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
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
    where w.user_id = v_uid
      and (w.created_at at time zone 'Asia/Ho_Chi_Minh')::date >= (v_today - 6)
      and not exists (
          select 1 from public.user_warning_revocations r
          where r.warning_id = w.id
      );

    select * into v_rel
    from public.app_update_releases
    where campaign_id = p_campaign_id and status in ('active', 'exhausted')
    order by published_at desc, target_version_code desc
    limit 1;

    if v_rel.id is not null then
        if v_is_actual_owner then
            v_app_update := jsonb_build_object(
                'id', v_rel.id,
                'campaign_id', v_rel.campaign_id,
                'owner_id', coalesce(v_rel.owner_id, v_rel.owner_id_snapshot),
                'package_name', v_rel.package_name,
                'app_name', v_rel.app_name,
                'version_name', v_rel.version_name,
                'target_version_code', v_rel.target_version_code,
                'release_notes', v_rel.release_notes,
                'status', v_rel.status,
                'reward_per_claim', v_rel.reward_per_claim,
                'max_claims', v_rel.max_claims,
                'reserved_credits', v_rel.reserved_credits,
                'paid_count', v_rel.paid_count,
                'remaining_credits', v_rel.remaining_credits,
                'spent_credits', v_rel.paid_count * v_rel.reward_per_claim,
                'published_at', to_char(v_rel.published_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            );
        else
            if v_rel.paid_count < v_rel.max_claims and v_rel.remaining_credits >= v_rel.reward_per_claim then
                select * into v_claim
                from public.app_update_claims
                where release_id = v_rel.id and tester_id = v_uid;

                if not v_is_member then
                    v_is_eligible := false;
                    v_ineligible_reason := 'not_member';
                elsif v_member_joined_at > v_rel.published_at then
                    v_is_eligible := false;
                    v_ineligible_reason := 'joined_after_release';
                elsif v_camp.status = 'paused' then
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_paused';
                elsif v_camp.status <> 'open' then
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_not_open';
                elsif exists (select 1 from public.profiles p where p.id = v_uid and coalesce(p.is_locked, false) = true) then
                    v_is_eligible := false;
                    v_ineligible_reason := 'user_locked';
                end if;

                v_app_update := jsonb_build_object(
                    'id', v_rel.id,
                    'campaign_id', v_rel.campaign_id,
                    'package_name', v_rel.package_name,
                    'app_name', v_rel.app_name,
                    'version_name', v_rel.version_name,
                    'target_version_code', v_rel.target_version_code,
                    'release_notes', v_rel.release_notes,
                    'status', v_rel.status,
                    'reward_per_claim', v_rel.reward_per_claim,
                    'max_claims', v_rel.max_claims,
                    'paid_count', v_rel.paid_count,
                    'has_claimed', (v_claim.id is not null),
                    'is_eligible', v_is_eligible,
                    'ineligibility_reason', v_ineligible_reason,
                    'claim_receipt', case when v_claim.id is not null then jsonb_build_object(
                        'claim_id', v_claim.id,
                        'release_id', v_claim.release_id,
                        'ordinal', v_claim.ordinal,
                        'reward_credits', v_claim.reward_credits,
                        'confirmed_at', to_char(v_claim.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                    ) else null end
                );
            end if;
        end if;
    end if;

    return jsonb_build_object(
        'campaign', v_campaign,
        'is_owner', v_is_owner,
        'is_actual_owner', v_is_actual_owner,
        'can_manage', v_can_manage,
        'is_member', v_is_member,
        'checked_in_confirmed_today', v_confirmed,
        'testers', v_testers,
        'feedbacks', v_feedbacks,
        'reports', v_reports,
        'profiles', v_profiles,
        'today_tested_count', v_today_tested,
        'reports_count', v_reports_count,
        'pending_reports_count', v_pending_reports_count,
        'tester_reports_count', v_tester_reports_count,
        'avg_rating', v_avg,
        'my_checkin_dates', v_my_dates,
        'my_profile', v_my_profile,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly,
        'app_update', v_app_update
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail(uuid) TO authenticated, service_role;

-- Override reject_tester_report to exclude revoked warnings in weekly threshold & streak
CREATE OR REPLACE FUNCTION public.reject_tester_report(
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

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(10, coalesce(v_report.provisional_granted_credits, 2)))
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

    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

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
              and not exists (
                  select 1 from public.user_warning_revocations r
                  where r.warning_id = w.id
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
              and not exists (
                  select 1 from public.user_warning_revocations r
                  where r.warning_id = w.id
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
              and not exists (
                  select 1 from public.user_warning_revocations r
                  where r.warning_id = w.id
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
              and not exists (
                  select 1 from public.user_warning_revocations r
                  where r.warning_id = w.id
              );

            if v_owner_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_camp.owner_id;
                v_is_owner_locked := true;
            end if;
        end if;
    end if;

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

REVOKE ALL ON FUNCTION public.reject_tester_report(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reject_tester_report(uuid, text, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 10. System Monitor & Settlement Retry RPCs
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_get_system_monitor_summary()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_members_total int := 0;
    v_members_locked int := 0;
    v_members_admins int := 0;
    v_members_moderators int := 0;

    v_camps_open int := 0;
    v_camps_paused int := 0;
    v_camps_completed int := 0;
    v_camps_total int := 0;

    v_reports_pending int := 0;
    v_reports_approved int := 0;
    v_reports_rejected int := 0;

    v_contact_pending int := 0;
    v_contact_resolved int := 0;

    v_jobs_pending int := 0;
    v_jobs_failed int := 0;
    v_jobs_completed int := 0;
    v_jobs_skipped int := 0;

    v_last_cleanup record;
    v_cleanup_json jsonb := null;
begin
    v_caller_id := public.assert_unlocked_admin();

    select
        count(*),
        count(*) filter (where coalesce(is_locked, false) = true),
        count(*) filter (where role = 'admin'),
        count(*) filter (where role = 'moderator')
    into v_members_total, v_members_locked, v_members_admins, v_members_moderators
    from public.profiles;

    select
        count(*),
        count(*) filter (where status = 'open'),
        count(*) filter (where status = 'paused'),
        count(*) filter (where status = 'completed')
    into v_camps_total, v_camps_open, v_camps_paused, v_camps_completed
    from public.campaigns;

    select
        count(*) filter (where status = 'pending'),
        count(*) filter (where status = 'approved'),
        count(*) filter (where status = 'rejected')
    into v_reports_pending, v_reports_approved, v_reports_rejected
    from public.tester_reports;

    select
        count(*) filter (where status not in ('resolved', 'closed')),
        count(*) filter (where status in ('resolved', 'closed'))
    into v_contact_pending, v_contact_resolved
    from public.contact_reports;

    select
        count(*) filter (where status = 'pending'),
        count(*) filter (where status = 'failed'),
        count(*) filter (where status = 'completed'),
        count(*) filter (where status = 'skipped')
    into v_jobs_pending, v_jobs_failed, v_jobs_completed, v_jobs_skipped
    from public.mission_settlement_jobs;

    select id, job_name, status, started_at, finished_at, scanned, deleted, error
    into v_last_cleanup
    from public.cleanup_runs
    order by started_at desc
    limit 1;

    if v_last_cleanup.id is not null then
        v_cleanup_json := jsonb_build_object(
            'id', v_last_cleanup.id,
            'job_name', v_last_cleanup.job_name,
            'status', v_last_cleanup.status,
            'started_at', to_char(v_last_cleanup.started_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'finished_at', case when v_last_cleanup.finished_at is not null then to_char(v_last_cleanup.finished_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') else null end,
            'scanned', v_last_cleanup.scanned,
            'deleted', v_last_cleanup.deleted,
            'error', v_last_cleanup.error
        );
    end if;

    return jsonb_build_object(
        'members', jsonb_build_object(
            'total', v_members_total,
            'locked', v_members_locked,
            'admins', v_members_admins,
            'moderators', v_members_moderators
        ),
        'campaigns', jsonb_build_object(
            'open', v_camps_open,
            'paused', v_camps_paused,
            'completed', v_camps_completed,
            'total', v_camps_total
        ),
        'tester_reports', jsonb_build_object(
            'pending', v_reports_pending,
            'approved', v_reports_approved,
            'rejected', v_reports_rejected
        ),
        'contact_reports', jsonb_build_object(
            'pending', v_contact_pending,
            'resolved', v_contact_resolved
        ),
        'mission_settlement_jobs', jsonb_build_object(
            'pending', v_jobs_pending,
            'failed', v_jobs_failed,
            'completed', v_jobs_completed,
            'skipped', v_jobs_skipped
        ),
        'cleanup_runs', jsonb_build_object(
            'last_run', v_cleanup_json
        ),
        'timestamp', to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_system_monitor_summary() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_system_monitor_summary() TO authenticated, service_role;

-- Paginated job runs queue
CREATE OR REPLACE FUNCTION public.admin_get_job_runs_queue(
    p_job_type text DEFAULT 'mission_settlement'::text,
    p_status text DEFAULT NULL::text,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    v_caller_id := public.assert_unlocked_admin();
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    if p_job_type = 'cleanup' then
        with raw_cleanups as (
            select
                cr.id,
                cr.job_name,
                cr.status,
                cr.started_at as created_at,
                cr.finished_at,
                cr.scanned,
                cr.deleted,
                cr.error,
                cr.details
            from public.cleanup_runs cr
            where (p_cursor_created_at is null or (cr.started_at, cr.id) < (p_cursor_created_at, p_cursor_id))
              and (p_status is null or cr.status = p_status)
            order by cr.started_at desc, cr.id desc
            limit v_limit + 1
        )
        select jsonb_agg(to_jsonb(r)), count(*)
        into v_rows, v_count
        from raw_cleanups r;
    else
        with raw_settlements as (
            select
                j.id,
                j.report_id,
                j.mission_id,
                j.participant_id,
                j.campaign_id,
                j.tester_id,
                j.creator_id,
                j.reward_credits,
                j.status,
                j.attempt_count,
                j.next_attempt_at,
                j.last_error,
                j.created_at,
                j.updated_at
            from public.mission_settlement_jobs j
            where (p_cursor_created_at is null or (j.created_at, j.id) < (p_cursor_created_at, p_cursor_id))
              and (p_status is null or j.status = p_status)
            order by j.created_at desc, j.id desc
            limit v_limit + 1
        )
        select jsonb_agg(to_jsonb(r)), count(*)
        into v_rows, v_count
        from raw_settlements r;
    end if;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_job_runs_queue(text, text, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_job_runs_queue(text, text, integer, timestamptz, uuid) TO authenticated, service_role;

-- Retry mission settlement jobs wrapper
CREATE OR REPLACE FUNCTION public.admin_retry_mission_settlement_jobs(
    p_job_id uuid DEFAULT NULL::uuid,
    p_batch_limit integer DEFAULT 20,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_target_job record;
    v_job record;
    v_settle_res jsonb;
    v_processed_count int := 0;
    v_success_count int := 0;
    v_res jsonb;
    v_payload_hash text;
    v_existing_req record;
begin
    v_caller_id := public.assert_unlocked_admin();
    v_limit := greatest(1, least(coalesce(p_batch_limit, 20), 50));

    if p_request_id is not null then
        perform pg_advisory_xact_lock(hashtext(v_caller_id::text || ':retry_mission_settlement:' || p_request_id::text));

        v_payload_hash := md5(jsonb_build_object(
            'job_id', p_job_id,
            'batch_limit', v_limit
        )::text);

        select * into v_existing_req
        from public.admin_operation_requests
        where actor_id = v_caller_id
          and operation = 'retry_mission_settlement'
          and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash = v_payload_hash then
                return v_existing_req.result || jsonb_build_object('is_replay', true);
            else
                raise exception 'REQUEST_ID_PAYLOAD_MISMATCH: Request ID đã được sử dụng với tham số khác';
            end if;
        end if;
    end if;

    if p_job_id is not null then
        select * into v_target_job
        from public.mission_settlement_jobs
        where id = p_job_id
        for update;

        if not found or v_target_job.id is null then
            raise exception 'JOB_NOT_FOUND: Không tìm thấy công việc chi trả nhiệm vụ';
        end if;

        if v_target_job.status not in ('pending', 'failed') then
            return jsonb_build_object(
                'success', false,
                'message', 'Công việc không đủ điều kiện thử lại (trạng thái: ' || v_target_job.status || ')',
                'job_id', p_job_id,
                'processed_count', 0,
                'success_count', 0
            );
        end if;

        v_settle_res := public._settle_mission_reward(v_target_job.report_id);
        v_processed_count := 1;
        if coalesce((v_settle_res->>'settled')::boolean, false) and coalesce((v_settle_res->>'paid')::boolean, false) then
            v_success_count := 1;
        end if;

        v_res := jsonb_build_object(
            'success', true,
            'job_id', p_job_id,
            'processed_count', v_processed_count,
            'success_count', v_success_count,
            'settlement_result', v_settle_res
        );

        perform public.record_admin_action_event(
            v_caller_id,
            'retry_mission_settlement',
            'mission_settlement_job',
            p_job_id::text,
            'Admin retry công việc chi trả nhiệm vụ',
            to_jsonb(v_target_job),
            v_res,
            p_request_id
        );
    else
        for v_job in
            select id, report_id
            from public.mission_settlement_jobs
            where status in ('pending', 'failed')
              and attempt_count < 10
            order by next_attempt_at asc, created_at asc
            limit v_limit
            for update skip locked
        loop
            v_processed_count := v_processed_count + 1;
            v_settle_res := public._settle_mission_reward(v_job.report_id);
            if coalesce((v_settle_res->>'settled')::boolean, false) and coalesce((v_settle_res->>'paid')::boolean, false) then
                v_success_count := v_success_count + 1;
            end if;
        end loop;

        v_res := jsonb_build_object(
            'success', true,
            'processed_count', v_processed_count,
            'success_count', v_success_count
        );

        perform public.record_admin_action_event(
            v_caller_id,
            'retry_mission_settlement_batch',
            'mission_settlement_jobs',
            'batch_' || v_limit::text,
            'Admin retry lô công việc chi trả nhiệm vụ',
            null,
            v_res,
            p_request_id
        );
    end if;

    if p_request_id is not null then
        insert into public.admin_operation_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_caller_id, 'retry_mission_settlement', v_payload_hash, v_res, now()
        );
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_retry_mission_settlement_jobs(uuid, integer, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_retry_mission_settlement_jobs(uuid, integer, uuid) TO authenticated, service_role;


-- ----------------------------------------------------------------------------
-- 9c. Override get_my_dashboard to exclude revoked warnings
-- ----------------------------------------------------------------------------
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

    -- Số app đã test hôm nay: checkins + báo cáo pending/approved trong ngày
    select count(distinct campaign_id) into v_today_checkins
    from (
        select campaign_id from public.checkins
        where user_id = v_uid and checkin_date = v_today
        union
        select campaign_id from public.tester_reports
        where user_id = v_uid
          and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
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
                where tr.campaign_id = c.id and tr.status <> 'rejected'
                  and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
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

COMMIT;
