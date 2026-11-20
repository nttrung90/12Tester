-- ============================================================================
-- Migration 073 (Staged Focus 1): Admin Legacy Mutations Audit & Coordinated Role/Lock
--
-- Applies after 070 (and 071/072 when integrated).
-- Focus 1 scope:
-- 1. Private internal credit adjustment helper shared between legacy adjust and compensation.
-- 2. Audited public admin_adjust_credits wrapper recording admin_action_events.
-- 3. Compensation wrapper delegation to avoid duplicate audit events.
-- 4. Coordinated admin_set_user_lock with shared advisory lock, post-wait actor revalidation,
--    and last unlocked admin protection.
-- 5. Coordinated admin_set_user_role with post-wait revalidation and capability cleanup on demotion.
-- 6. Audited admin_send_notification preserving 067 direct popup state registration.
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 0. Update record_admin_action_event to use clock_timestamp()
-- ----------------------------------------------------------------------------

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
        p_actor_id, p_action, p_target_type, p_target_id, p_reason, p_old_state, p_new_state, p_request_id, clock_timestamp()
    )
    returning id into v_event_id;

    return v_event_id;
end;
$function$;

REVOKE ALL ON FUNCTION public.record_admin_action_event(uuid, text, text, text, text, jsonb, jsonb, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_admin_action_event(uuid, text, text, text, text, jsonb, jsonb, uuid) TO service_role;

-- ----------------------------------------------------------------------------
-- 1. Private internal credit calculation helper (NOT exposed to clients)
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public._admin_adjust_credits_internal(
    p_actor_id uuid,
    p_target_user_id uuid,
    p_amount integer,
    p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_current_credits int;
    v_calc_balance bigint;
    v_new_balance int;
    v_effective_amount int;
begin
    if p_actor_id is null then
        raise exception 'ACTOR_REQUIRED';
    end if;

    if p_target_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    if p_amount is null or p_amount = 0 then
        raise exception 'INVALID_AMOUNT: Số lượng credit phải khác 0';
    end if;

    select credits into v_current_credits
    from public.profiles
    where id = p_target_user_id
    for update;

    if not found or v_current_credits is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_calc_balance := v_current_credits::bigint + p_amount::bigint;
    if v_calc_balance > 2147483647 then
        raise exception 'CREDIT_OVERFLOW: Số dư vượt quá giới hạn tối đa';
    end if;

    if v_calc_balance < 0 then
        v_new_balance := 0;
    else
        v_new_balance := v_calc_balance::integer;
    end if;

    v_effective_amount := v_new_balance - v_current_credits;

    update public.profiles
    set credits = v_new_balance
    where id = p_target_user_id;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        p_target_user_id,
        v_effective_amount,
        case when p_amount >= 0 then 'admin_grant' else 'admin_deduct' end,
        coalesce(nullif(trim(p_reason), ''), case when p_amount >= 0 then 'Admin tặng Credit' else 'Admin trừ Credit' end),
        v_new_balance
    );

    return jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'previous_credits', v_current_credits,
        'new_credits', v_new_balance,
        'amount', v_effective_amount
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._admin_adjust_credits_internal(uuid, uuid, integer, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._admin_adjust_credits_internal(uuid, uuid, integer, text) TO service_role;

-- ----------------------------------------------------------------------------
-- 2. Audited public admin_adjust_credits wrapper
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_adjust_credits(
    p_target_user_id uuid,
    p_amount integer,
    p_reason text DEFAULT 'Điều chỉnh bởi Admin'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
    v_clean_reason text;
begin
    v_caller_id := public.assert_unlocked_admin();

    v_clean_reason := coalesce(nullif(btrim(p_reason), ''), 'Điều chỉnh bởi Admin');

    -- Execute internal calculation
    v_res := public._admin_adjust_credits_internal(v_caller_id, p_target_user_id, p_amount, v_clean_reason);

    -- Record common audit event
    perform public.record_admin_action_event(
        v_caller_id,
        case when p_amount >= 0 then 'admin_grant_credits' else 'admin_deduct_credits' end,
        'user',
        p_target_user_id::text,
        v_clean_reason,
        jsonb_build_object('credits', v_res->'previous_credits'),
        jsonb_build_object('credits', v_res->'new_credits', 'amount', v_res->'amount'),
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_adjust_credits(uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_adjust_credits(uuid, integer, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 3. admin_compensate_credits delegating to internal helper (1 common event only)
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
    v_canonical_payload jsonb;
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

    -- Canonical JSONB payload hash
    v_canonical_payload := jsonb_build_object(
        'target_user_id', p_target_user_id,
        'amount', p_amount,
        'reason', v_clean_reason,
        'original_credit_log_id', p_original_credit_log_id,
        'contact_report_id', p_contact_report_id
    );
    v_payload_hash := md5(v_canonical_payload::text);

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

    -- Delegate to internal calculation (does NOT emit separate public adjust event)
    v_adjust_res := public._admin_adjust_credits_internal(v_caller_id, p_target_user_id, p_amount, v_clean_reason);

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

    -- Record single authoritative audit event
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
-- 4. Coordinated admin_set_user_lock with post-wait auth revalidation
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_set_user_lock(
    p_target_user_id uuid,
    p_locked boolean,
    p_reason text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_target_role text;
    v_target_locked boolean;
    v_admin_count int;
    v_clean_reason text;
    v_has_cap boolean := false;
begin
    -- 1. Initial caller assertion
    v_caller_id := public.assert_unlocked_admin();

    if p_target_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    if p_target_user_id = v_caller_id and p_locked = true then
        raise exception 'CANNOT_LOCK_SELF: Không thể tự khóa tài khoản của chính mình';
    end if;

    -- 2. Shared global advisory lock across BOTH role and lock changes
    perform pg_advisory_xact_lock(hashtext('admin_role_and_lock_mutation'));

    -- 3. Re-assert caller is STILL unlocked admin after acquiring lock
    v_caller_id := public.assert_unlocked_admin();

    -- 4. Target row lock & existence check
    select role, coalesce(is_locked, false)
    into v_target_role, v_target_locked
    from public.profiles
    where id = p_target_user_id
    for update;

    if not found or v_target_role is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    -- 5. Special rules when target is an admin
    if lower(v_target_role) = 'admin' and p_locked = true then
        -- Caller must hold explicit admin_management capability
        select exists (
            select 1
            from public.admin_capabilities
            where admin_id = v_caller_id
              and capability = 'admin_management'
        ) into v_has_cap;

        if not v_has_cap then
            raise exception 'INSUFFICIENT_PRIVILEGE: Cần quyền admin_management để khóa tài khoản Quản trị viên';
        end if;

        -- Must preserve at least one unlocked admin
        select count(*) into v_admin_count
        from public.profiles
        where role = 'admin'
          and coalesce(is_locked, false) = false
          and id != p_target_user_id;

        if v_admin_count = 0 then
            raise exception 'CANNOT_LOCK_LAST_ADMIN: Không thể khóa Quản trị viên chưa bị khóa cuối cùng trong hệ thống';
        end if;
    end if;

    v_clean_reason := coalesce(nullif(btrim(p_reason), ''), case when p_locked then 'Khóa bởi Admin' else 'Mở khóa bởi Admin' end);

    update public.profiles
    set is_locked = p_locked
    where id = p_target_user_id;

    -- Record audit event
    perform public.record_admin_action_event(
        v_caller_id,
        case when p_locked then 'lock_user' else 'unlock_user' end,
        'user',
        p_target_user_id::text,
        v_clean_reason,
        jsonb_build_object('is_locked', v_target_locked),
        jsonb_build_object('is_locked', p_locked),
        null
    );

    return jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'is_locked', p_locked
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_set_user_lock(uuid, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_user_lock(uuid, boolean, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. Coordinated admin_set_user_role with post-wait revalidation & cap revocation
-- ----------------------------------------------------------------------------

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
    -- 1. Initial caller assertion
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

    -- 2. Idempotency check with serialized request-scope lock
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

    -- 3. Shared global advisory lock across BOTH role and lock changes
    perform pg_advisory_xact_lock(hashtext('admin_role_and_lock_mutation'));

    -- 4. Re-assert caller is STILL unlocked admin after acquiring lock
    v_caller_id := public.assert_unlocked_admin();

    -- 5. Lock target profile
    select role, coalesce(is_locked, false)
    into v_target_role, v_target_locked
    from public.profiles
    where id = p_target_user_id
    for update;

    if not found or v_target_role is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    -- 6. Check explicit capability if touching admin role
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

    -- 7. Preserve last unlocked admin
    if lower(v_target_role) = 'admin' and v_clean_role != 'admin' then
        select count(*) into v_admin_count
        from public.profiles
        where role = 'admin'
          and coalesce(is_locked, false) = false
          and id != p_target_user_id;

        if v_admin_count = 0 then
            raise exception 'CANNOT_REMOVE_LAST_ADMIN: Không thể hạ quyền Quản trị viên cuối cùng trong hệ thống';
        end if;

        -- Remove all capabilities so later promotion cannot reactivate dormant admin_management
        delete from public.admin_capabilities
        where admin_id = p_target_user_id;
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
-- 6. Audited admin_send_notification preserving 067 popup registration
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_send_notification(
    p_target_user_id uuid DEFAULT NULL::uuid,
    p_title text DEFAULT ''::text,
    p_message text DEFAULT ''::text,
    p_type text DEFAULT 'admin'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_count int := 0;
    v_new_notification_id uuid;
    v_clean_title text;
    v_clean_message text;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    v_clean_title := trim(coalesce(p_title, ''));
    if v_clean_title = '' then
        raise exception 'TITLE_REQUIRED';
    end if;

    v_clean_message := trim(coalesce(p_message, ''));
    if v_clean_message = '' then
        raise exception 'MESSAGE_REQUIRED';
    end if;

    if p_target_user_id is not null then
        if not exists (select 1 from public.profiles where id = p_target_user_id) then
            raise exception 'TARGET_USER_NOT_FOUND';
        end if;

        insert into public.notifications (user_id, title, message, type, data, created_at)
        values (
            p_target_user_id,
            v_clean_title,
            v_clean_message,
            coalesce(p_type, 'admin'),
            jsonb_build_object('sender', 'admin', 'broadcast', false),
            clock_timestamp()
        )
        returning id into v_new_notification_id;

        -- Exact 067 popup state registration for direct notifications
        insert into public.notification_popup_states (notification_id, display_count)
        values (v_new_notification_id, 0);

        v_count := 1;
    else
        insert into public.notifications (user_id, title, message, type, data)
        select
            id,
            v_clean_title,
            v_clean_message,
            coalesce(p_type, 'admin'),
            jsonb_build_object('sender', 'admin', 'broadcast', true)
        from public.profiles;
        get diagnostics v_count = row_count;
    end if;

    v_res := jsonb_build_object(
        'success', true,
        'sent_count', v_count,
        'target', case when p_target_user_id is null then 'all' else p_target_user_id::text end
    );

    -- Record audit event
    perform public.record_admin_action_event(
        v_caller_id,
        'send_notification',
        'user',
        case when p_target_user_id is null then 'all' else p_target_user_id::text end,
        v_clean_title,
        null,
        jsonb_build_object(
            'sent_count', v_count,
            'message', v_clean_message,
            'type', coalesce(p_type, 'admin'),
            'broadcast', (p_target_user_id is null)
        ),
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_send_notification(uuid, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_send_notification(uuid, text, text, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 7. Controlled migration-time ALTER FUNCTION RENAME for remaining legacy RPCs
-- ----------------------------------------------------------------------------

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_update_app_config') THEN
        ALTER FUNCTION public.admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) RENAME TO _impl_admin_update_app_config;
        REVOKE ALL ON FUNCTION public._impl_admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_set_home_stats_cycle') THEN
        ALTER FUNCTION public.admin_set_home_stats_cycle(integer, text) RENAME TO _impl_admin_set_home_stats_cycle;
        REVOKE ALL ON FUNCTION public._impl_admin_set_home_stats_cycle(integer, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_set_home_stats_cycle(integer, text) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_schedule_daily_task_policy') THEN
        ALTER FUNCTION public.admin_schedule_daily_task_policy(integer, date, text, text) RENAME TO _impl_admin_schedule_daily_task_policy;
        REVOKE ALL ON FUNCTION public._impl_admin_schedule_daily_task_policy(integer, date, text, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_schedule_daily_task_policy(integer, date, text, text) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_cancel_daily_task_policy') THEN
        ALTER FUNCTION public.admin_cancel_daily_task_policy(date, text, text) RENAME TO _impl_admin_cancel_daily_task_policy;
        REVOKE ALL ON FUNCTION public._impl_admin_cancel_daily_task_policy(date, text, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_cancel_daily_task_policy(date, text, text) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_pause_campaign') THEN
        ALTER FUNCTION public.admin_pause_campaign(uuid, text) RENAME TO _impl_admin_pause_campaign;
        REVOKE ALL ON FUNCTION public._impl_admin_pause_campaign(uuid, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_pause_campaign(uuid, text) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_resume_campaign') THEN
        ALTER FUNCTION public.admin_resume_campaign(uuid) RENAME TO _impl_admin_resume_campaign;
        REVOKE ALL ON FUNCTION public._impl_admin_resume_campaign(uuid) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_resume_campaign(uuid) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_delete_campaign') THEN
        ALTER FUNCTION public.admin_delete_campaign(uuid, text) RENAME TO _impl_admin_delete_campaign;
        REVOKE ALL ON FUNCTION public._impl_admin_delete_campaign(uuid, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_delete_campaign(uuid, text) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_reply_contact_report') THEN
        ALTER FUNCTION public.admin_reply_contact_report(uuid, text) RENAME TO _impl_admin_reply_contact_report;
        REVOKE ALL ON FUNCTION public._impl_admin_reply_contact_report(uuid, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_reply_contact_report(uuid, text) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_admin_update_report_status') THEN
        ALTER FUNCTION public.admin_update_report_status(uuid, boolean) RENAME TO _impl_admin_update_report_status;
        REVOKE ALL ON FUNCTION public._impl_admin_update_report_status(uuid, boolean) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_admin_update_report_status(uuid, boolean) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_approve_tester_report') THEN
        ALTER FUNCTION public.approve_tester_report(uuid, integer) RENAME TO _impl_approve_tester_report;
        REVOKE ALL ON FUNCTION public._impl_approve_tester_report(uuid, integer) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_approve_tester_report(uuid, integer) TO service_role;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = '_impl_reject_tester_report') THEN
        ALTER FUNCTION public.reject_tester_report(uuid, text, text) RENAME TO _impl_reject_tester_report;
        REVOKE ALL ON FUNCTION public._impl_reject_tester_report(uuid, text, text) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public._impl_reject_tester_report(uuid, text, text) TO service_role;
    END IF;
END $do$;

-- ----------------------------------------------------------------------------
-- 8. Audited public wrappers for configuration, policies, campaigns & support
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_update_app_config(
    p_min_version_code integer,
    p_latest_version_code integer,
    p_latest_version_name text,
    p_force_update boolean,
    p_title text DEFAULT NULL::text,
    p_message text DEFAULT NULL::text,
    p_store_url text DEFAULT NULL::text,
    p_title_vi text DEFAULT NULL::text,
    p_title_en text DEFAULT NULL::text,
    p_message_vi text DEFAULT NULL::text,
    p_message_en text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
    v_old record;
begin
    v_caller_id := public.assert_unlocked_admin();
    select * into v_old from public.app_configs where id = 'android_client';

    v_res := public._impl_admin_update_app_config(
        p_min_version_code, p_latest_version_code, p_latest_version_name,
        p_force_update, p_title, p_message, p_store_url,
        p_title_vi, p_title_en, p_message_vi, p_message_en
    );

    perform public.record_admin_action_event(
        v_caller_id,
        'update_app_config',
        'app_config',
        'android_client',
        'Cập nhật cấu hình phiên bản ứng dụng',
        to_jsonb(v_old),
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_set_home_stats_cycle(
    p_cycle_days integer,
    p_expected_revision text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    v_res := public._impl_admin_set_home_stats_cycle(p_cycle_days, p_expected_revision);

    perform public.record_admin_action_event(
        v_caller_id,
        'set_home_stats_cycle',
        'home_stats_policy',
        'active',
        'Điều chỉnh chu kỳ thống kê trang chủ',
        null,
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_set_home_stats_cycle(integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_home_stats_cycle(integer, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_schedule_daily_task_policy(
    p_tests_per_active_app integer,
    p_effective_from date,
    p_operator_note text,
    p_expected_revision text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    v_res := public._impl_admin_schedule_daily_task_policy(
        p_tests_per_active_app, p_effective_from, p_operator_note, p_expected_revision
    );

    perform public.record_admin_action_event(
        v_caller_id,
        'schedule_daily_task_policy',
        'daily_task_policy',
        to_char(p_effective_from, 'YYYY-MM-DD'),
        p_operator_note,
        null,
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_schedule_daily_task_policy(integer, date, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_schedule_daily_task_policy(integer, date, text, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_cancel_daily_task_policy(
    p_effective_from date,
    p_operator_note text,
    p_expected_revision text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    v_res := public._impl_admin_cancel_daily_task_policy(
        p_effective_from, p_operator_note, p_expected_revision
    );

    perform public.record_admin_action_event(
        v_caller_id,
        'cancel_daily_task_policy',
        'daily_task_policy',
        to_char(p_effective_from, 'YYYY-MM-DD'),
        p_operator_note,
        null,
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_cancel_daily_task_policy(date, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_cancel_daily_task_policy(date, text, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_pause_campaign(
    p_campaign_id uuid,
    p_reason text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
    v_old record;
begin
    v_caller_id := public.assert_unlocked_admin();
    select status, paused_reason into v_old from public.campaigns where id = p_campaign_id;

    v_res := public._impl_admin_pause_campaign(p_campaign_id, p_reason);

    perform public.record_admin_action_event(
        v_caller_id,
        'pause_campaign',
        'campaign',
        p_campaign_id::text,
        p_reason,
        to_jsonb(v_old),
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_pause_campaign(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_pause_campaign(uuid, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_resume_campaign(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
    v_old record;
begin
    v_caller_id := public.assert_unlocked_admin();
    select status, paused_reason into v_old from public.campaigns where id = p_campaign_id;

    v_res := public._impl_admin_resume_campaign(p_campaign_id);

    perform public.record_admin_action_event(
        v_caller_id,
        'resume_campaign',
        'campaign',
        p_campaign_id::text,
        'Khôi phục chiến dịch bởi Admin',
        to_jsonb(v_old),
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_resume_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_resume_campaign(uuid) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_delete_campaign(
    p_campaign_id uuid,
    p_reason text DEFAULT 'Gỡ bỏ bởi Admin'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
    v_old record;
begin
    v_caller_id := public.assert_unlocked_admin();
    select status, paused_reason into v_old from public.campaigns where id = p_campaign_id;

    v_res := public._impl_admin_delete_campaign(p_campaign_id, p_reason);

    perform public.record_admin_action_event(
        v_caller_id,
        'delete_campaign',
        'campaign',
        p_campaign_id::text,
        p_reason,
        to_jsonb(v_old),
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_delete_campaign(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_delete_campaign(uuid, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_reply_contact_report(p_report_id uuid, p_message text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    v_res := public._impl_admin_reply_contact_report(p_report_id, p_message);

    perform public.record_admin_action_event(
        v_caller_id,
        'reply_contact_report',
        'contact_report',
        p_report_id::text,
        p_message,
        null,
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_reply_contact_report(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_reply_contact_report(uuid, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_update_report_status(p_report_id uuid, p_resolved boolean)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_res jsonb;
begin
    v_caller_id := public.assert_unlocked_admin();

    v_res := public._impl_admin_update_report_status(p_report_id, p_resolved);

    perform public.record_admin_action_event(
        v_caller_id,
        'update_report_status',
        'contact_report',
        p_report_id::text,
        case when p_resolved then 'Đánh dấu đã xử lý' else 'Đánh dấu mở lại' end,
        null,
        v_res,
        null
    );

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_update_report_status(uuid, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_report_status(uuid, boolean) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 9. Staff moderation wrappers for tester report approval & rejection
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.approve_tester_report(
    p_report_id uuid,
    p_bonus integer DEFAULT 0
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
    v_camp_owner_id uuid;
    v_res jsonb;
begin
    -- Lookup campaign owner to distinguish owner mode vs staff moderation
    select c.owner_id into v_camp_owner_id
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where tr.id = p_report_id;

    -- Execute underlying implementation (authoritative business logic, whether 069 or 071)
    v_res := public._impl_approve_tester_report(p_report_id, p_bonus);

    -- Only record in admin_action_events if reviewer is NON-OWNER staff (admin or moderator)
    if v_caller_id is not null and (v_camp_owner_id is null or v_caller_id <> v_camp_owner_id) then
        select role, coalesce(is_locked, false)
        into v_caller_role, v_caller_locked
        from public.profiles
        where id = v_caller_id;

        if v_caller_locked = false and lower(coalesce(v_caller_role, '')) in ('admin', 'moderator') then
            perform public.record_admin_action_event(
                v_caller_id,
                'staff_approve_tester_report',
                'tester_report',
                p_report_id::text,
                'Duyệt báo cáo tester bởi staff',
                null,
                v_res,
                null
            );
        end if;
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.approve_tester_report(uuid, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_tester_report(uuid, integer) TO authenticated, service_role;

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
    v_camp_owner_id uuid;
    v_res jsonb;
begin
    -- Lookup campaign owner to distinguish owner mode vs staff moderation
    select c.owner_id into v_camp_owner_id
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where tr.id = p_report_id;

    -- Execute underlying implementation (authoritative business logic)
    v_res := public._impl_reject_tester_report(p_report_id, p_reason, p_reason_code);

    -- Only record in admin_action_events if reviewer is NON-OWNER staff (admin or moderator)
    if v_caller_id is not null and (v_camp_owner_id is null or v_caller_id <> v_camp_owner_id) then
        select role, coalesce(is_locked, false)
        into v_caller_role, v_caller_locked
        from public.profiles
        where id = v_caller_id;

        if v_caller_locked = false and lower(coalesce(v_caller_role, '')) in ('admin', 'moderator') then
            perform public.record_admin_action_event(
                v_caller_id,
                'staff_reject_tester_report',
                'tester_report',
                p_report_id::text,
                p_reason,
                null,
                v_res,
                null
            );
        end if;
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.reject_tester_report(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reject_tester_report(uuid, text, text) TO authenticated, service_role;

COMMIT;
