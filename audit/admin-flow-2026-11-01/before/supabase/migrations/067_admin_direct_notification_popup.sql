-- Migration 067: Admin Direct Notification In-App Popup
-- Atomic transaction for deploying popup states, receipts, security constraints, and RPCs.

BEGIN;

-- ============================================================================
-- 1. NOTIFICATION POPUP STATES (Server-managed display counters)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.notification_popup_states (
    notification_id uuid PRIMARY KEY REFERENCES public.notifications(id) ON DELETE CASCADE,
    display_count smallint NOT NULL DEFAULT 0 CHECK (display_count >= 0 AND display_count <= 5),
    last_display_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_popup_states_display_count
    ON public.notification_popup_states (display_count);

ALTER TABLE public.notification_popup_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_popup_states NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_popup_states FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.notification_popup_states TO service_role;

-- ============================================================================
-- 2. NOTIFICATION POPUP RECEIPTS (Session idempotency & replay ledger)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.notification_popup_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    foreground_session_id uuid NOT NULL,
    notification_id uuid REFERENCES public.notifications(id) ON DELETE SET NULL,
    display_number smallint NOT NULL CHECK (display_number >= 1 AND display_number <= 5),
    claimed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_popup_receipts UNIQUE (user_id, foreground_session_id)
);

CREATE INDEX IF NOT EXISTS idx_popup_receipts_user_session
    ON public.notification_popup_receipts (user_id, foreground_session_id);

CREATE INDEX IF NOT EXISTS idx_popup_receipts_notif
    ON public.notification_popup_receipts (notification_id);

ALTER TABLE public.notification_popup_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_popup_receipts NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_popup_receipts FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.notification_popup_receipts TO service_role;

-- ============================================================================
-- 3. INDEX FOR POPUP CANDIDATE LOOKUP ON NOTIFICATIONS
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_notifications_popup_lookup
    ON public.notifications (user_id, is_read, created_at ASC, id ASC);

-- ============================================================================
-- 4. TRIGGER: PREVENT CLIENT FORGERY / TAMPERING OF POPUP NOTIFICATIONS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.protect_popup_notification_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
BEGIN
    -- Only protect rows registered in notification_popup_states
    IF EXISTS (SELECT 1 FROM public.notification_popup_states WHERE notification_id = OLD.id) THEN
        -- Prevent changing immutable fields (id, user_id, title, message, type, data, created_at)
        IF NEW.id <> OLD.id OR NEW.user_id <> OLD.user_id OR NEW.title <> OLD.title OR NEW.message <> OLD.message OR NEW.type <> OLD.type OR NEW.data IS DISTINCT FROM OLD.data OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'CANNOT_MODIFY_PROTECTED_NOTIFICATION';
        END IF;

        -- Prevent unmarking read (switching is_read from true back to false)
        IF OLD.is_read = true AND NEW.is_read = false THEN
            RAISE EXCEPTION 'CANNOT_UNREAD_POPUP_NOTIFICATION';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_popup_notification_update ON public.notifications;
CREATE TRIGGER trg_protect_popup_notification_update
BEFORE UPDATE ON public.notifications
FOR EACH ROW
EXECUTE FUNCTION public.protect_popup_notification_update();

-- ============================================================================
-- 5. RPC: ADMIN_SEND_NOTIFICATION (DIRECT NOTIFICATIONS REGISTER POPUP STATE)
-- ============================================================================

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
    v_count int := 0;
    v_new_notification_id uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    if p_title is null or trim(p_title) = '' then
        raise exception 'TITLE_REQUIRED';
    end if;

    if p_message is null or trim(p_message) = '' then
        raise exception 'MESSAGE_REQUIRED';
    end if;

    if p_target_user_id is not null then
        -- Verify target user exists
        if not exists (select 1 from public.profiles where id = p_target_user_id) then
            raise exception 'TARGET_USER_NOT_FOUND';
        end if;

        -- Direct send to 1 user -> create notification AND register popup state
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
        -- Broadcast to all members (does NOT register popup state)
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

REVOKE ALL ON FUNCTION public.admin_send_notification(uuid, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_send_notification(uuid, text, text, text) TO authenticated, service_role;

-- ============================================================================
-- 6. RPC: CLAIM_MY_NOTIFICATION_POPUP
-- ============================================================================

CREATE OR REPLACE FUNCTION public.claim_my_notification_popup(p_foreground_session_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_is_locked boolean;
    v_receipt record;
    v_cand record;
    v_new_count smallint;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_foreground_session_id is null then
        raise exception 'INVALID_SESSION_ID';
    end if;

    -- Serialize concurrent claim operations per user
    perform pg_advisory_xact_lock(hashtext(v_uid::text));

    -- Check account status
    select is_locked into v_is_locked
    from public.profiles
    where id = v_uid;

    if not found then
        raise exception 'USER_NOT_FOUND';
    end if;

    if coalesce(v_is_locked, false) = true then
        return jsonb_build_object(
            'status', 'blocked',
            'reason', 'account_locked'
        );
    end if;

    -- Check if receipt already exists for this session
    select
        r.id,
        r.notification_id,
        r.display_number,
        n.title,
        n.message,
        n.type,
        n.data,
        n.created_at,
        n.is_read
    into v_receipt
    from public.notification_popup_receipts r
    left join public.notifications n on n.id = r.notification_id
    where r.user_id = v_uid and r.foreground_session_id = p_foreground_session_id;

    if found then
        -- Notification was deleted
        if v_receipt.notification_id is null then
            return jsonb_build_object(
                'status', 'stopped',
                'reason', 'deleted',
                'foreground_session_id', p_foreground_session_id
            );
        end if;

        -- Notification was marked read
        if v_receipt.is_read = true then
            return jsonb_build_object(
                'status', 'stopped',
                'reason', 'read',
                'foreground_session_id', p_foreground_session_id
            );
        end if;

        -- Replay existing claim idempotently
        return jsonb_build_object(
            'status', 'claimed',
            'notification', jsonb_build_object(
                'id', v_receipt.notification_id,
                'user_id', v_uid,
                'title', v_receipt.title,
                'message', v_receipt.message,
                'type', v_receipt.type,
                'data', v_receipt.data,
                'created_at', v_receipt.created_at,
                'is_read', false
            ),
            'display_count', v_receipt.display_number,
            'foreground_session_id', p_foreground_session_id
        );
    end if;

    -- Look for oldest eligible popup notification
    select
        n.id,
        n.title,
        n.message,
        n.type,
        n.data,
        n.created_at,
        s.display_count
    into v_cand
    from public.notifications n
    join public.notification_popup_states s on s.notification_id = n.id
    where n.user_id = v_uid
      and n.is_read = false
      and s.display_count < 5
    order by n.created_at asc, n.id asc
    for update of s, n
    limit 1;

    if not found or v_cand.id is null then
        -- No eligible candidate -> do not consume quota or create receipt
        return jsonb_build_object(
            'status', 'none',
            'foreground_session_id', p_foreground_session_id
        );
    end if;

    -- Increment count
    v_new_count := (v_cand.display_count + 1)::smallint;

    update public.notification_popup_states
    set display_count = v_new_count,
        last_display_at = now()
    where notification_id = v_cand.id;

    -- Record receipt
    insert into public.notification_popup_receipts (
        user_id,
        foreground_session_id,
        notification_id,
        display_number,
        claimed_at
    ) values (
        v_uid,
        p_foreground_session_id,
        v_cand.id,
        v_new_count,
        now()
    );

    return jsonb_build_object(
        'status', 'claimed',
        'notification', jsonb_build_object(
            'id', v_cand.id,
            'user_id', v_uid,
            'title', v_cand.title,
            'message', v_cand.message,
            'type', v_cand.type,
            'data', v_cand.data,
            'created_at', v_cand.created_at,
            'is_read', false
        ),
        'display_count', v_new_count,
        'foreground_session_id', p_foreground_session_id
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.claim_my_notification_popup(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.claim_my_notification_popup(uuid) TO authenticated, service_role;

-- ============================================================================
-- 7. RPC: ACKNOWLEDGE_MY_NOTIFICATION (IDEMPOTENT MARK READ)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.acknowledge_my_notification(p_notification_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_is_read boolean;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_notification_id is null then
        raise exception 'INVALID_NOTIFICATION_ID';
    end if;

    select is_read into v_is_read
    from public.notifications
    where id = p_notification_id and user_id = v_uid;

    if not found then
        return jsonb_build_object(
            'success', true,
            'status', 'deleted',
            'notification_id', p_notification_id
        );
    end if;

    if v_is_read = true then
        return jsonb_build_object(
            'success', true,
            'status', 'already_read',
            'notification_id', p_notification_id
        );
    end if;

    update public.notifications
    set is_read = true
    where id = p_notification_id and user_id = v_uid;

    return jsonb_build_object(
        'success', true,
        'status', 'read',
        'notification_id', p_notification_id
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.acknowledge_my_notification(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.acknowledge_my_notification(uuid) TO authenticated, service_role;

-- ============================================================================
-- 8. RPC: GET_MY_NOTIFICATION_STATUS (LIGHTWEIGHT REALTIME CHECK)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_my_notification_status(p_notification_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_is_read boolean;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_notification_id is null then
        raise exception 'INVALID_NOTIFICATION_ID';
    end if;

    select is_read into v_is_read
    from public.notifications
    where id = p_notification_id and user_id = v_uid;

    if not found then
        return jsonb_build_object(
            'exists', false,
            'is_read', false
        );
    end if;

    return jsonb_build_object(
        'exists', true,
        'is_read', v_is_read
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_my_notification_status(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_my_notification_status(uuid) TO authenticated, service_role;

COMMIT;
