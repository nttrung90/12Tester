-- ============================================================================
-- Migration 069: Admin Flow Audit & Hardening
--
-- 1. Effective credit delta matching ledger and return amount with input/overflow validation
-- 2. Fail-closed unlocked admin verification across all admin RPCs
-- 3. Target existence validation for user lock (prevent phantom success)
-- 4. Fail-closed unlocked caller check for report approval/rejection (admin/moderator/owner)
-- 5. Lock-aware can_manage in get_campaign_detail (excluding locked staff & locked owner)
-- 6. Hardened RLS policies for app_configs UPDATE, campaigns DELETE/SELECT, and reports
-- 7. Revoke excessive TRUNCATE & TRIGGER capabilities from client roles (anon, authenticated, public)
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. admin_adjust_credits: fix ledger overdraft mismatch, validate null/0/overflow
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
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_current_credits int;
    v_calc_balance bigint;
    v_new_balance int;
    v_effective_amount int;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền điều chỉnh Credit';
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

-- ----------------------------------------------------------------------------
-- 2. admin_set_user_lock: validate target existence and unlocked admin caller
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
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền khóa/mở khóa tài khoản';
    end if;

    if p_target_user_id is null then
        raise exception 'TARGET_USER_REQUIRED';
    end if;

    if p_target_user_id = v_caller_id and p_locked = true then
        raise exception 'CANNOT_LOCK_SELF: Không thể tự khóa tài khoản của chính mình';
    end if;

    update public.profiles
    set is_locked = p_locked
    where id = p_target_user_id;

    if not found then
        raise exception 'USER_NOT_FOUND';
    end if;

    return jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'is_locked', p_locked
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 3. admin_get_contact_reports: guard unlocked admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_get_contact_reports(p_status text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_result jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'NOT_ADMIN';
    end if;

    select coalesce(jsonb_agg(to_jsonb(t) order by t.created_at desc), '[]'::jsonb)
    into v_result
    from (
        select
            r.id,
            r.user_id,
            r.category,
            r.message,
            r.app_version,
            r.platform,
            r.device_model,
            r.status,
            r.admin_reply,
            r.replied_at,
            r.created_at,
            p.display_name,
            p.email,
            p.avatar_url
        from public.contact_reports r
        left join public.profiles p on p.id = r.user_id
        where p_status is null or r.status = p_status
        order by r.created_at desc
        limit 300
    ) t;

    return v_result;
end;
$function$;

-- ----------------------------------------------------------------------------
-- 4. admin_reply_contact_report: guard unlocked admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_reply_contact_report(p_report_id uuid, p_message text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_report public.contact_reports;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'NOT_ADMIN';
    end if;

    if p_message is null or trim(p_message) = '' then
        raise exception 'MESSAGE_REQUIRED';
    end if;

    update public.contact_reports
    set admin_reply = trim(p_message),
        replied_at = now(),
        status = 'replied'
    where id = p_report_id
    returning * into v_report;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_report.user_id,
        'Phản hồi từ Admin',
        trim(p_message),
        'admin',
        jsonb_build_object(
            'sender', 'admin',
            'broadcast', false,
            'contact_report_id', p_report_id
        )
    );

    return to_jsonb(v_report);
end;
$function$;

-- ----------------------------------------------------------------------------
-- 5. admin_update_report_status: guard unlocked admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_update_report_status(p_report_id uuid, p_resolved boolean)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_report public.contact_reports;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'NOT_ADMIN';
    end if;

    update public.contact_reports
    set status = case
        when p_resolved then 'resolved'
        when admin_reply is not null then 'replied'
        else 'new'
    end
    where id = p_report_id
    returning * into v_report;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    return to_jsonb(v_report);
end;
$function$;

-- ----------------------------------------------------------------------------
-- 6. admin_send_notification: guard unlocked admin, preserve 067 popup registration
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
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_count int := 0;
    v_new_notification_id uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'PERMISSION_DENIED';
    end if;

    if p_title is null or trim(p_title) = '' then
        raise exception 'TITLE_REQUIRED';
    end if;

    if p_message is null or trim(p_message) = '' then
        raise exception 'MESSAGE_REQUIRED';
    end if;

    if p_target_user_id is not null then
        if not exists (select 1 from public.profiles where id = p_target_user_id) then
            raise exception 'TARGET_USER_NOT_FOUND';
        end if;

        insert into public.notifications (user_id, title, message, type, data, created_at)
        values (
            p_target_user_id,
            p_title,
            p_message,
            coalesce(p_type, 'admin'),
            jsonb_build_object('sender', 'admin', 'broadcast', false),
            clock_timestamp()
        )
        returning id into v_new_notification_id;

        insert into public.notification_popup_states (notification_id, display_count)
        values (v_new_notification_id, 0);

        v_count := 1;
    else
        insert into public.notifications (user_id, title, message, type, data)
        select
            id,
            p_title,
            p_message,
            coalesce(p_type, 'admin'),
            jsonb_build_object('sender', 'admin', 'broadcast', true)
        from public.profiles;
        get diagnostics v_count = row_count;
    end if;

    return jsonb_build_object(
        'success', true,
        'sent_count', v_count,
        'target', case when p_target_user_id is null then 'all' else p_target_user_id::text end
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 7. admin_update_app_config: guard unlocked admin
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
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_config public.app_configs;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'NOT_ADMIN';
    end if;

    if p_min_version_code < 1 or p_latest_version_code < p_min_version_code then
        raise exception 'INVALID_VERSION_RANGE';
    end if;

    if p_latest_version_name is null or trim(p_latest_version_name) = '' then
        raise exception 'INVALID_VERSION_NAME';
    end if;

    insert into public.app_configs (
        id,
        min_version_code,
        latest_version_code,
        latest_version_name,
        force_update,
        title,
        message,
        store_url,
        title_vi,
        title_en,
        message_vi,
        message_en,
        updated_at
    )
    values (
        'android_client',
        p_min_version_code,
        p_latest_version_code,
        trim(p_latest_version_name),
        p_force_update,
        coalesce(p_title, p_title_vi, p_title_en),
        coalesce(p_message, p_message_vi, p_message_en),
        p_store_url,
        p_title_vi,
        p_title_en,
        p_message_vi,
        p_message_en,
        now()
    )
    on conflict (id) do update set
        min_version_code = excluded.min_version_code,
        latest_version_code = excluded.latest_version_code,
        latest_version_name = excluded.latest_version_name,
        force_update = excluded.force_update,
        title = coalesce(excluded.title, p_title_vi, p_title_en),
        message = coalesce(excluded.message, p_message_vi, p_message_en),
        store_url = coalesce(excluded.store_url, public.app_configs.store_url),
        title_vi = excluded.title_vi,
        title_en = excluded.title_en,
        message_vi = excluded.message_vi,
        message_en = excluded.message_en,
        updated_at = now()
    returning * into v_config;

    return to_jsonb(v_config);
end;
$function$;

-- ----------------------------------------------------------------------------
-- 8. admin_get_home_stats_config: guard unlocked admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_get_home_stats_config()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_active_days int;
    v_active_from date;
    v_pending_days int;
    v_pending_from date;
    v_revision text;
begin
    if v_uid is null then
        raise exception 'UNAUTHORIZED: Authentication required';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_uid;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'FORBIDDEN: Admin access required';
    end if;

    select cycle_days, effective_from into v_active_days, v_active_from
    from public.home_stats_policies
    where effective_from <= v_today
    order by effective_from desc
    limit 1;

    select cycle_days, effective_from into v_pending_days, v_pending_from
    from public.home_stats_policies
    where effective_from > v_today
    order by effective_from asc
    limit 1;

    v_revision := public._get_home_stats_revision();

    return jsonb_build_object(
        'active_cycle_days', coalesce(v_active_days, 1),
        'active_effective_from', v_active_from,
        'pending_cycle_days', v_pending_days,
        'pending_effective_from', v_pending_from,
        'revision', v_revision,
        'today', v_today
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 9. admin_set_home_stats_cycle: guard unlocked admin
-- ----------------------------------------------------------------------------
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
    v_uid uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_today date;
    v_tomorrow date;
    v_active_days int;
    v_active_from date;
    v_existing_tomorrow int;
    v_current_rev text;
    r record;
begin
    if v_uid is null then
        raise exception 'UNAUTHORIZED: Authentication required';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_uid;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'FORBIDDEN: Admin access required';
    end if;

    if p_cycle_days is null or p_cycle_days not in (1, 2, 3) then
        raise exception 'INVALID_CYCLE_DAYS: Must be 1, 2, or 3';
    end if;

    if p_expected_revision is null or trim(p_expected_revision) = '' then
        raise exception 'MISSING_REVISION: Expected revision is required';
    end if;

    lock table public.home_stats_policies in exclusive mode;

    v_today := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_tomorrow := v_today + 1;

    v_current_rev := public._get_home_stats_revision();
    if p_expected_revision <> v_current_rev then
        raise exception 'CONCURRENCY_CONFLICT: Config has been modified by another administrator';
    end if;

    select cycle_days, effective_from into v_active_days, v_active_from
    from public.home_stats_policies
    where effective_from <= v_today
    order by effective_from desc
    limit 1;

    v_active_days := coalesce(v_active_days, 1);

    if p_cycle_days = v_active_days then
        for r in select * from public.home_stats_policies where effective_from > v_today loop
            insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
            values ('CANCEL_SCHEDULE', r.effective_from, r.cycle_days, null, v_uid);
        end loop;
        delete from public.home_stats_policies where effective_from > v_today;
    else
        select cycle_days into v_existing_tomorrow
        from public.home_stats_policies
        where effective_from = v_tomorrow;

        if found then
            if v_existing_tomorrow <> p_cycle_days then
                update public.home_stats_policies
                set cycle_days = p_cycle_days, updated_at = now(), updated_by = v_uid
                where effective_from = v_tomorrow;

                insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
                values ('UPDATE_SCHEDULE', v_tomorrow, v_existing_tomorrow, p_cycle_days, v_uid);
            end if;
        else
            insert into public.home_stats_policies (effective_from, cycle_days, created_by, updated_by, created_at, updated_at)
            values (v_tomorrow, p_cycle_days, v_uid, v_uid, now(), now());

            insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
            values ('SET_SCHEDULE', v_tomorrow, v_active_days, p_cycle_days, v_uid);
        end if;

        delete from public.home_stats_policies where effective_from > v_tomorrow;
    end if;

    return public.admin_get_home_stats_config();
end;
$function$;

-- ----------------------------------------------------------------------------
-- 10. admin_pause_campaign: guard unlocked admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_pause_campaign(p_campaign_id uuid, p_reason text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_camp public.campaigns%rowtype;
    v_note text;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'NOT_ADMIN';
    end if;

    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status in ('production', 'completed') then
        raise exception 'CAMPAIGN_FINALIZED';
    end if;

    if v_camp.status not in ('open', 'paused') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    v_note := nullif(btrim(coalesce(p_reason, '')), '');

    if v_camp.status = 'paused'
       and v_camp.paused_reason = 'admin_manual'
       and v_camp.admin_note is not distinct from v_note then
        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'previous_status', v_camp.status,
            'status', v_camp.status,
            'paused_reason', v_camp.paused_reason,
            'app_name', v_camp.app_name,
            'owner_id', v_camp.owner_id,
            'admin_note', v_camp.admin_note
        );
    end if;

    update public.campaigns
    set status = 'paused',
        paused_reason = 'admin_manual',
        admin_note = v_note,
        admin_resumed = false
    where id = p_campaign_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_camp.owner_id,
        'Ứng dụng đã bị tạm dừng bởi Admin ⏸',
        format(
            'Ứng dụng "%s" đã bị Quản trị viên tạm dừng.%s',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
            case when v_note is null then '' else E'\nLý do: ' || v_note end
        ),
        'campaign',
        jsonb_build_object(
            'campaign_id', p_campaign_id,
            'reason', 'admin_pause',
            'admin_note', v_note
        )
    );

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'previous_status', v_camp.status,
        'status', 'paused',
        'paused_reason', 'admin_manual',
        'app_name', v_camp.app_name,
        'owner_id', v_camp.owner_id,
        'admin_note', v_note
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 11. admin_resume_campaign: guard unlocked admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_resume_campaign(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_camp public.campaigns%rowtype;
    v_hold_open boolean;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'NOT_ADMIN';
    end if;

    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status in ('production', 'completed') then
        raise exception 'CAMPAIGN_FINALIZED';
    end if;

    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    if v_camp.created_at <= now() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_camp.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 30 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
                coalesce(nullif(v_camp.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'window_expired')
        );

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'previous_status', v_camp.status,
            'status', 'completed',
            'app_name', v_camp.app_name,
            'owner_id', v_camp.owner_id
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

    v_hold_open := (v_camp.paused_reason = 'owner_inactive');

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null,
        admin_note = null,
        admin_resumed = v_hold_open
    where id = p_campaign_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_camp.owner_id,
        'Ứng dụng đã được Admin mở lại ✅',
        format(
            'Ứng dụng "%s" đã được Quản trị viên mở lại và đang nhận tester bình thường.',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn')
        ),
        'campaign',
        jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'admin_resume')
    );

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'previous_status', v_camp.status,
        'status', 'open',
        'paused_reason', null,
        'app_name', v_camp.app_name,
        'owner_id', v_camp.owner_id,
        'admin_resumed', v_hold_open
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 12. admin_delete_campaign: guard unlocked admin
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.admin_delete_campaign(p_campaign_id uuid, p_reason text DEFAULT 'Gỡ bỏ bởi Admin'::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_app_name text;
    v_owner_id uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền gỡ bỏ ứng dụng';
    end if;

    select app_name, owner_id into v_app_name, v_owner_id
    from public.campaigns
    where id = p_campaign_id;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    delete from public.campaigns
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'app_name', v_app_name,
        'owner_id', v_owner_id,
        'reason', coalesce(p_reason, 'Gỡ bỏ bởi Admin')
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 13. approve_tester_report: guard locked actor, preserve unlocked staff/owner
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.approve_tester_report(p_report_id uuid, p_bonus integer DEFAULT 0)
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
    v_extra_reward int := 2;
    v_tip int := 0;
    v_total_bonus int;
    v_new_balance int;
    v_owner_bonus int := 0;
    v_owner_balance int;
    v_is_owner boolean := false;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_locked, false) = true then
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

    v_is_owner := (v_camp.owner_id = v_caller_id);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

    update public.profiles
    set credits = credits + v_extra_reward,
        last_active_date = v_today,
        missed_streak = 0
    where id = v_report.user_id
    returning credits into v_new_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_extra_reward,
        'test_approved_bonus',
        format(
            '%s duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)',
            case when v_is_owner then 'Chủ app' else 'Admin/Moderator' end,
            v_extra_reward
        ),
        v_new_balance
    );

    if v_is_owner then
        v_owner_bonus := 2;
        update public.profiles
        set credits = credits + v_owner_bonus
        where id = v_camp.owner_id
        returning credits into v_owner_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_camp.owner_id,
            v_owner_bonus,
            'owner_approval_bonus',
            format('Duyệt báo cáo kiểm thử (+%s Credit)', v_owner_bonus),
            v_owner_balance
        );
    end if;

    perform public._settle_mission_reward(v_report.id);

    update public.checkins
    set note = 'Đã kiểm thử và được duyệt'
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    if not found then
        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được duyệt')
        on conflict (campaign_id, user_id, checkin_date)
        do update set note = 'Đã kiểm thử và được duyệt';
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'owner_credits_awarded', v_owner_bonus,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'owner_balance', v_owner_balance,
        'message', format(
            'Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester%s)',
            v_extra_reward,
            case when v_owner_bonus > 0 then format(' và +%s Credit cho bạn', v_owner_bonus) else '' end
        )
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 14. reject_tester_report: exact 062 baseline logic with caller lock check
-- ----------------------------------------------------------------------------
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
            select 1 from public.user_warnings
            where user_id = v_report.user_id
              and warning_type = 'tester_rejection_streak'
              and warning_date = v_vn_today
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
            from public.user_warnings
            where user_id = v_report.user_id
              and warning_date >= (v_vn_today - 6);

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
            select 1 from public.user_warnings
            where user_id = v_camp.owner_id
              and warning_type = 'owner_rejection_streak'
              and warning_date = v_vn_today
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
            from public.user_warnings
            where user_id = v_camp.owner_id
              and warning_date >= (v_vn_today - 6);

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

-- ----------------------------------------------------------------------------
-- 15. get_campaign_detail: exact 068 definition with lock-aware can_manage
-- ----------------------------------------------------------------------------
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
    where w.user_id = v_uid and w.acknowledged = false;

    select count(*) into v_weekly
    from public.user_warnings w
    where w.user_id = v_uid
      and (w.created_at at time zone 'Asia/Ho_Chi_Minh')::date >= (v_today - 6);

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

-- ----------------------------------------------------------------------------
-- 16. Explicit grants for all replaced functions
-- ----------------------------------------------------------------------------
REVOKE ALL ON FUNCTION public.admin_adjust_credits(uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_adjust_credits(uuid, integer, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_set_user_lock(uuid, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_user_lock(uuid, boolean, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_get_contact_reports(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_contact_reports(text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_reply_contact_report(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_reply_contact_report(uuid, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_update_report_status(uuid, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_report_status(uuid, boolean) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_send_notification(uuid, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_send_notification(uuid, text, text, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_get_home_stats_config() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_home_stats_config() TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_set_home_stats_cycle(integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_home_stats_cycle(integer, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_pause_campaign(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_pause_campaign(uuid, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_resume_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_resume_campaign(uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_delete_campaign(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_delete_campaign(uuid, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.approve_tester_report(uuid, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_tester_report(uuid, integer) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.reject_tester_report(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reject_tester_report(uuid, text, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail(uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 17. Hardened RLS policies
-- ----------------------------------------------------------------------------
-- app_configs: prevent locked admin or unauthorized UPDATE
DROP POLICY IF EXISTS "Allow update app_configs to admin" ON public."app_configs";
CREATE POLICY "Allow update app_configs to admin" ON public."app_configs" AS PERMISSIVE FOR UPDATE TO "authenticated"
  USING (EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) = 'admin' AND coalesce(p.is_locked, false) = false))
  WITH CHECK (EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) = 'admin' AND coalesce(p.is_locked, false) = false));

-- campaigns: prevent locked owner/admin from DELETE and ensure unlocked admin visibility
DROP POLICY IF EXISTS "campaigns_delete_owner_or_admin" ON public."campaigns";
CREATE POLICY "campaigns_delete_owner_or_admin" ON public."campaigns" AS PERMISSIVE FOR DELETE TO "authenticated"
  USING (
    ((owner_id = auth.uid() AND EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND coalesce(p.is_locked, false) = false))
     OR (EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) = 'admin' AND coalesce(p.is_locked, false) = false)))
  );

-- The owner branch must not bypass account lock or the admin RPC checks.
DROP POLICY IF EXISTS "campaigns_update_owner" ON public."campaigns";
CREATE POLICY "campaigns_update_owner" ON public."campaigns" AS PERMISSIVE FOR UPDATE TO "authenticated"
  USING (owner_id = auth.uid() AND EXISTS (
    SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND coalesce(p.is_locked, false) = false
  ))
  WITH CHECK (owner_id = auth.uid() AND EXISTS (
    SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND coalesce(p.is_locked, false) = false
  ));

DROP POLICY IF EXISTS "campaigns_select_visible" ON public."campaigns";
CREATE POLICY "campaigns_select_visible" ON public."campaigns" AS PERMISSIVE FOR SELECT TO "authenticated"
  USING (
    ((status = ANY (ARRAY['open'::text, 'paused'::text, 'production'::text, 'completed'::text]))
     OR (owner_id = auth.uid())
     OR (EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) = 'admin' AND coalesce(p.is_locked, false) = false)))
  );

-- tester_reports: prevent locked admin from reading via admin branch
DROP POLICY IF EXISTS "reports_select_own_or_owner" ON public."tester_reports";
CREATE POLICY "reports_select_own_or_owner" ON public."tester_reports" AS PERMISSIVE FOR SELECT TO "authenticated"
  USING (
    ((user_id = auth.uid())
     OR (EXISTS (SELECT 1 FROM public.campaigns c WHERE c.id = tester_reports.campaign_id AND c.owner_id = auth.uid()))
     OR (EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) = 'admin' AND coalesce(p.is_locked, false) = false)))
  );

-- ----------------------------------------------------------------------------
-- 18. Revoke TRUNCATE and TRIGGER from client roles (anon, authenticated, PUBLIC)
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT tablename FROM pg_tables WHERE schemaname = 'public'
    ) LOOP
        EXECUTE format('REVOKE TRUNCATE, TRIGGER ON TABLE public.%I FROM anon, authenticated, PUBLIC;', r.tablename);
    END LOOP;
END;
$$;

COMMIT;
