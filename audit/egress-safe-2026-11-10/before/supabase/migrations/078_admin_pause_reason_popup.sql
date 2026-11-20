-- 078_admin_pause_reason_popup.sql
-- Admin Campaign Pause Reason Popup & Structured Audit Integration

BEGIN;

-- ============================================================================
-- 1. SCHEMA EXTENSION: Structured Reason Code Column & Constraint
-- ============================================================================

ALTER TABLE public.campaigns
    ADD COLUMN IF NOT EXISTS admin_pause_reason_code text NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.campaigns'::regclass
          AND conname = 'campaigns_admin_pause_reason_code_check'
    ) THEN
        ALTER TABLE public.campaigns
            ADD CONSTRAINT campaigns_admin_pause_reason_code_check
            CHECK (admin_pause_reason_code IS NULL OR admin_pause_reason_code IN ('missing_google_group', 'policy_violation'));
    END IF;
END $$;

-- ============================================================================
-- 2. CLIENT TAMPERING & LIFECYCLE GUARDS
-- ============================================================================

-- Extend existing status lifecycle trigger to clear admin_pause_reason_code on reopen
CREATE OR REPLACE FUNCTION public.trg_campaign_status_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
begin
    if new.status in ('production', 'completed') then
        if new.completed_at is null then
            new.completed_at := now();
        end if;
        new.admin_resumed := false;
        -- Giữ admin_note khi app hết hạn: guard bên dưới dựa vào việc note
        -- không đổi để phân biệt hệ thống hoàn thành app với chủ app lách luật.
    elsif new.status = 'paused' then
        new.admin_resumed := false;
    elsif new.status = 'open' then
        new.completed_at := null;
        new.paused_reason := null;
        new.admin_note := null;
        if tg_op = 'UPDATE' then
            new.admin_pause_reason_code := null;
        end if;
    end if;
    return new;
end;
$function$;

GRANT EXECUTE ON FUNCTION public.trg_campaign_status_lifecycle() TO PUBLIC, anon, authenticated, service_role;

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
        return new;
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

DROP TRIGGER IF EXISTS trg_campaigns_guard_client_lifecycle ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_client_lifecycle
BEFORE INSERT OR UPDATE OF status, paused_reason, admin_pause_reason_code ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_client_lifecycle();

CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_admin_actions()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_role text;
begin
    -- SQL Editor / service_role without auth.uid -> allow.
    if v_uid is null then
        return new;
    end if;

    -- Block client inserts carrying admin flags/reasons
    if tg_op = 'INSERT' then
        if new.admin_resumed
           or new.admin_note is not null
           or new.paused_reason = 'admin_manual'
           or new.admin_pause_reason_code is not null then
            raise exception 'PERMISSION_DENIED';
        end if;
        return new;
    end if;

    -- Owner ID is immutable
    if new.owner_id is distinct from old.owner_id then
        raise exception 'OWNER_ID_IMMUTABLE';
    end if;

    -- Only admin can enable admin_resumed flag
    if new.admin_resumed and not coalesce(old.admin_resumed, false) then
        select lower(p.role) into v_role
        from public.profiles p
        where p.id = v_uid;

        if coalesce(v_role, '') <> 'admin' then
            raise exception 'PERMISSION_DENIED';
        end if;
    end if;

    -- If paused by admin, owner cannot resume or alter paused_reason/admin_note/admin_pause_reason_code
    if old.paused_reason = 'admin_manual' then
        if new.status = 'open'
           or new.paused_reason is distinct from 'admin_manual'
           or new.admin_note is distinct from old.admin_note
           or new.admin_pause_reason_code is distinct from old.admin_pause_reason_code
        then
            select lower(p.role) into v_role
            from public.profiles p
            where p.id = v_uid;

            if coalesce(v_role, '') <> 'admin' then
                raise exception 'PAUSED_BY_ADMIN';
            end if;
        end if;
    end if;

    -- Non-admin cannot alter admin_pause_reason_code
    if new.admin_pause_reason_code is distinct from old.admin_pause_reason_code then
        select lower(p.role) into v_role
        from public.profiles p
        where p.id = v_uid;

        if coalesce(v_role, '') <> 'admin' then
            raise exception 'PERMISSION_DENIED';
        end if;
    end if;

    return new;
end;
$function$;

-- ============================================================================
-- 3. INTERNAL SHARED IMPLEMENTATION HELPER
-- ============================================================================

CREATE OR REPLACE FUNCTION public._impl_admin_pause_campaign_core(
    p_campaign_id uuid,
    p_reason_code text,
    p_custom_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_camp public.campaigns%rowtype;
    v_stored_reason_code text;
    v_note text;
    v_app_display text;
    v_msg text;
    v_notif_data jsonb;
    v_new_notification_id uuid;
begin
    -- 1. Initial privilege verification
    v_caller_id := public.assert_unlocked_admin();

    if p_reason_code is not null and p_reason_code not in ('missing_google_group', 'policy_violation', 'custom') then
        raise exception 'INVALID_REASON_CODE';
    end if;

    -- 2. Lock target campaign row
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    -- Re-verify caller admin status after acquiring row lock
    perform public.assert_unlocked_admin();

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status in ('production', 'completed') then
        raise exception 'CAMPAIGN_FINALIZED';
    end if;

    if v_camp.status not in ('open', 'paused') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- 3. Determine structured reason code, display admin_note, and notification text/payload
    if p_reason_code = 'missing_google_group' then
        v_stored_reason_code := 'missing_google_group';
        v_note := 'Chưa thêm Google Group';
        v_app_display := nullif(btrim(coalesce(v_camp.app_name, '')), '');
        if v_app_display is not null then
            v_msg := format(
                'Ứng dụng "%s" của bạn đã bị tạm dừng do chưa thêm Google Group của nhóm khiến các Tester không tải được ứng dụng của bạn để test. Vui lòng thêm Google Group của nhóm (theo hướng dẫn trong app) để tiếp tục tham gia Test.',
                v_app_display
            );
        else
            v_msg := 'Ứng dụng của bạn đã bị tạm dừng do chưa thêm Google Group của nhóm khiến các Tester không tải được ứng dụng của bạn để test. Vui lòng thêm Google Group của nhóm (theo hướng dẫn trong app) để tiếp tục tham gia Test.';
        end if;
        v_notif_data := jsonb_build_object(
            'campaign_id', p_campaign_id,
            'reason', 'admin_pause',
            'sender', 'admin',
            'broadcast', false,
            'app_name', v_camp.app_name,
            'reason_code', 'missing_google_group',
            'admin_note', v_note
        );
    elsif p_reason_code = 'policy_violation' then
        v_stored_reason_code := 'policy_violation';
        v_note := 'Vi phạm chính sách';
        v_msg := 'Ứng dụng của bạn đã bị tạm dừng do vi phạm chính sách, vui lòng liên hệ Admin để được mở lại Test.';
        v_notif_data := jsonb_build_object(
            'campaign_id', p_campaign_id,
            'reason', 'admin_pause',
            'sender', 'admin',
            'broadcast', false,
            'app_name', v_camp.app_name,
            'reason_code', 'policy_violation',
            'admin_note', v_note
        );
    else
        v_stored_reason_code := null;
        v_note := nullif(btrim(coalesce(p_custom_reason, '')), '');
        v_msg := format(
            'Ứng dụng "%s" đã bị Quản trị viên tạm dừng.%s',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
            case when v_note is null then '' else E'\nLý do: ' || v_note end
        );
        v_notif_data := jsonb_build_object(
            'campaign_id', p_campaign_id,
            'reason', 'admin_pause',
            'admin_note', v_note
        );
    end if;

    -- 4. Idempotency / No-op check (state, paused_reason, code and note are already identical)
    if v_camp.status = 'paused'
       and v_camp.paused_reason = 'admin_manual'
       and v_camp.admin_pause_reason_code is not distinct from v_stored_reason_code
       and v_camp.admin_note is not distinct from v_note then
        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'previous_status', v_camp.status,
            'status', v_camp.status,
            'paused_reason', v_camp.paused_reason,
            'app_name', v_camp.app_name,
            'owner_id', v_camp.owner_id,
            'admin_note', v_camp.admin_note,
            'admin_pause_reason_code', v_camp.admin_pause_reason_code,
            'changed', false,
            'notification_id', null
        );
    end if;

    -- 5. Mutation: update campaign
    update public.campaigns
    set status = 'paused',
        paused_reason = 'admin_manual',
        admin_pause_reason_code = v_stored_reason_code,
        admin_note = v_note,
        admin_resumed = false
    where id = p_campaign_id;

    -- 6. Insert exactly one notification
    insert into public.notifications (user_id, title, message, type, data, created_at)
    values (
        v_camp.owner_id,
        'Ứng dụng đã bị tạm dừng bởi Admin ⏸',
        v_msg,
        'campaign',
        v_notif_data,
        clock_timestamp()
    )
    returning id into v_new_notification_id;

    -- 7. Register popup state if structured reason (missing_google_group or policy_violation)
    if v_stored_reason_code in ('missing_google_group', 'policy_violation') then
        insert into public.notification_popup_states (notification_id, display_count, created_at)
        values (v_new_notification_id, 0, clock_timestamp());
    end if;

    -- 8. Return result
    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'previous_status', v_camp.status,
        'status', 'paused',
        'paused_reason', 'admin_manual',
        'app_name', v_camp.app_name,
        'owner_id', v_camp.owner_id,
        'admin_note', v_note,
        'admin_pause_reason_code', v_stored_reason_code,
        'changed', true,
        'notification_id', v_new_notification_id
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._impl_admin_pause_campaign_core(uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_admin_pause_campaign_core(uuid, text, text) TO service_role;

-- ============================================================================
-- 4. FORWARDING LEGACY _impl_admin_pause_campaign(uuid, text)
-- ============================================================================

CREATE OR REPLACE FUNCTION public._impl_admin_pause_campaign(p_campaign_id uuid, p_reason text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
begin
    return public._impl_admin_pause_campaign_core(p_campaign_id, 'custom', p_reason);
end;
$function$;

REVOKE ALL ON FUNCTION public._impl_admin_pause_campaign(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_admin_pause_campaign(uuid, text) TO service_role;

-- ============================================================================
-- 5. AUDITED PUBLIC RPC: admin_pause_campaign(uuid, text) [LEGACY COMPATIBILITY]
-- ============================================================================

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

    select status, paused_reason, admin_pause_reason_code, admin_note
    into v_old
    from public.campaigns
    where id = p_campaign_id
    for update;

    if not found or v_old.status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

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

-- ============================================================================
-- 6. AUDITED PUBLIC RPC: admin_pause_campaign_with_reason(uuid, text) [NEW]
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_pause_campaign_with_reason(
    p_campaign_id uuid,
    p_reason_code text
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
    v_changed boolean;
begin
    v_caller_id := public.assert_unlocked_admin();

    if p_reason_code is null or p_reason_code not in ('missing_google_group', 'policy_violation') then
        raise exception 'INVALID_REASON_CODE';
    end if;

    select status, paused_reason, admin_pause_reason_code, admin_note
    into v_old
    from public.campaigns
    where id = p_campaign_id
    for update;

    if not found or v_old.status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    v_res := public._impl_admin_pause_campaign_core(p_campaign_id, p_reason_code, null);

    v_changed := coalesce((v_res->>'changed')::boolean, true);
    if v_changed then
        perform public.record_admin_action_event(
            v_caller_id,
            'pause_campaign',
            'campaign',
            p_campaign_id::text,
            p_reason_code,
            to_jsonb(v_old),
            v_res,
            null
        );
    end if;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_pause_campaign_with_reason(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_pause_campaign_with_reason(uuid, text) TO authenticated, service_role;

-- ============================================================================
-- 7. REOPEN & RESUME: Clear admin_pause_reason_code along with admin_note
-- ============================================================================

CREATE OR REPLACE FUNCTION public._impl_admin_resume_campaign(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_camp public.campaigns%rowtype;
    v_hold_open boolean;
begin
    perform public.assert_unlocked_admin();

    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    perform public.assert_unlocked_admin();

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
            admin_pause_reason_code = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        insert into public.notifications (user_id, title, message, type, data, created_at)
        values (
            v_camp.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 30 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
                coalesce(nullif(v_camp.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'window_expired'),
            clock_timestamp()
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
        admin_pause_reason_code = null,
        admin_note = null,
        admin_resumed = v_hold_open
    where id = p_campaign_id;

    insert into public.notifications (user_id, title, message, type, data, created_at)
    values (
        v_camp.owner_id,
        'Ứng dụng đã được Admin mở lại ✅',
        format(
            'Ứng dụng "%s" đã được Quản trị viên mở lại và đang nhận tester bình thường.',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn')
        ),
        'campaign',
        jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'admin_resume'),
        clock_timestamp()
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

REVOKE ALL ON FUNCTION public._impl_admin_resume_campaign(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_admin_resume_campaign(uuid) TO service_role;

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

    select status, paused_reason, admin_pause_reason_code, admin_note
    into v_old
    from public.campaigns
    where id = p_campaign_id
    for update;

    if not found or v_old.status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

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

-- Update owner resume_campaign from 075 to clear admin_pause_reason_code if completed or resumed
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
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select coalesce((
        select missed_days from public.owner_auto_pause_configs where id = 'default'
    ), 7) into v_threshold;

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

COMMIT;
