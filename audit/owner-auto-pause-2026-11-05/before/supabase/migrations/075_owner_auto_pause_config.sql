-- ============================================================================
-- Migration 075: Configurable Owner Auto-Pause Threshold & Campaign Update Hardening
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. CONFIG TABLE & SINGLETON ROW
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.owner_auto_pause_configs (
    id text PRIMARY KEY DEFAULT 'default',
    missed_days integer NOT NULL CHECK (missed_days BETWEEN 1 AND 7),
    revision text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
    CONSTRAINT chk_owner_auto_pause_singleton CHECK (id = 'default')
);

INSERT INTO public.owner_auto_pause_configs (id, missed_days, revision, updated_at)
VALUES ('default', 7, gen_random_uuid()::text, clock_timestamp())
ON CONFLICT (id) DO NOTHING;

ALTER TABLE public.owner_auto_pause_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.owner_auto_pause_configs NO FORCE ROW LEVEL SECURITY;

-- Revoke direct table mutations from client roles
REVOKE ALL ON TABLE public.owner_auto_pause_configs FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE public.owner_auto_pause_configs TO service_role;

-- ----------------------------------------------------------------------------
-- 2. ADMIN CONFIG RPCS
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.admin_get_owner_auto_pause_config()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_row record;
begin
    perform public.assert_unlocked_admin();

    select missed_days, revision, updated_at
    into v_row
    from public.owner_auto_pause_configs
    where id = 'default';

    if not found then
        raise exception 'CONFIG_NOT_FOUND';
    end if;

    return jsonb_build_object(
        'missed_days', v_row.missed_days,
        'revision', v_row.revision,
        'updated_at', v_row.updated_at
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_owner_auto_pause_config() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_owner_auto_pause_config() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_set_owner_auto_pause_config(
    p_missed_days integer,
    p_expected_revision text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_old_missed_days integer;
    v_old_revision text;
    v_new_revision text;
begin
    -- 1. Initial permission check
    v_caller_id := public.assert_unlocked_admin();

    -- 2. Validation
    if p_missed_days is null or p_missed_days < 1 or p_missed_days > 7 then
        raise exception 'INVALID_MISSED_DAYS: Must be between 1 and 7';
    end if;

    if p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'MISSING_REVISION: Expected revision is required';
    end if;

    -- 3. Row locking
    select missed_days, revision
    into v_old_missed_days, v_old_revision
    from public.owner_auto_pause_configs
    where id = 'default'
    for update;

    -- 4. Re-verify rights under lock
    v_caller_id := public.assert_unlocked_admin();

    if v_old_revision is null then
        raise exception 'CONFIG_NOT_FOUND';
    end if;

    if p_expected_revision <> v_old_revision then
        raise exception 'CONCURRENCY_CONFLICT: Config has been modified by another administrator';
    end if;

    v_new_revision := gen_random_uuid()::text;

    update public.owner_auto_pause_configs
    set missed_days = p_missed_days,
        revision = v_new_revision,
        updated_at = clock_timestamp(),
        updated_by = v_caller_id
    where id = 'default';

    perform public.record_admin_action_event(
        v_caller_id,
        'set_owner_auto_pause_config',
        'owner_auto_pause_config',
        'default',
        'Cấu hình số ngày tự tạm dừng app do thiếu nhiệm vụ',
        jsonb_build_object('missed_days', v_old_missed_days, 'revision', v_old_revision),
        jsonb_build_object('missed_days', p_missed_days, 'revision', v_new_revision),
        null
    );

    return jsonb_build_object(
        'success', true,
        'missed_days', p_missed_days,
        'revision', v_new_revision,
        'previous_missed_days', v_old_missed_days
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_set_owner_auto_pause_config(integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_owner_auto_pause_config(integer, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 3. CAMPAIGN LIFECYCLE & PAUSE FUNCTIONS WITH THRESHOLD
-- ----------------------------------------------------------------------------

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
    v_threshold int;
begin
    select coalesce((
        select missed_days from public.owner_auto_pause_configs where id = 'default'
    ), 7) into v_threshold;

    for r in
        select c.id, c.owner_id, c.app_name
        from public.campaigns c
        join public.profiles p on p.id = c.owner_id
        where c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
          and c.created_at <= now() - interval '24 hours'
          and lower(p.role) not in ('admin', 'moderator')
          and p.daily_missed_streak >= v_threshold
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
                'Ứng dụng "%s" đã bị tạm dừng vì bạn không hoàn thành chỉ tiêu test trong %s ngày liên tục. Hãy hoàn thành chỉ tiêu hôm nay, sau đó bấm "Tiếp tục nhận tester" trong màn hình quản lý app để hiển thị lại.',
                coalesce(nullif(r.app_name, ''), 'của bạn'),
                v_threshold
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'owner_inactive', 'threshold_days', v_threshold)
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
    v_threshold int;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select coalesce((
        select missed_days from public.owner_auto_pause_configs where id = 'default'
    ), 7) into v_threshold;

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

    if coalesce(v_missed, 0) >= v_threshold then
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

REVOKE ALL ON FUNCTION public.resume_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.resume_campaign(uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 4. GUARD DIRECT CLIENT WRITES TO CAMPAIGN LIFECYCLE
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_client_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO ''
AS $function$
begin
    -- Trusted RPCs execute their writes as their definer. Direct PostgREST
    -- writes execute as the client role, which cannot authorize its own resume.
    if current_user not in ('authenticated', 'anon') then
        return new;
    end if;

    if tg_op = 'INSERT' then
        if new.paused_reason in ('owner_inactive', 'slot_expired', 'admin_manual') then
            raise exception 'PERMISSION_DENIED';
        end if;
        return new;
    end if;

    if old.status is distinct from 'open' and new.status = 'open' then
        raise exception 'DIRECT_RESUME_NOT_ALLOWED';
    end if;

    if old.status = 'paused'
       and old.paused_reason in ('owner_inactive', 'slot_expired', 'admin_manual')
       and (new.status is distinct from old.status
            or new.paused_reason is distinct from old.paused_reason) then
        -- Keep the existing owner action to mark a non-admin-paused app as
        -- Production. A direct write still cannot reopen a finalized app.
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
BEFORE INSERT OR UPDATE OF status, paused_reason ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_client_lifecycle();

COMMIT;
