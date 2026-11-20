-- ============================================================================
-- Migration 072: Admin Notification Campaigns Backend & Batch Fanout Worker
--
-- Features:
-- 1. Campaign schema: title_vi/en, body_vi/en, default_locale, audience (all/role/selected_users)
-- 2. Recipient snapshot taken deterministically at schedule / send-now (frozen flag protects zero-recipient audiences)
-- 3. Optimistic concurrency control (CAS) via campaign revision
-- 4. Idempotency ledger for mutations: canonical JSON payload hashing, request-scoped advisory locking, actor validation
-- 5. Atomic batch fanout worker using SKIP LOCKED; per-campaign/user unique receipts
-- 6. No popup states for mass broadcasts (preserves 067/069 direct popup flow)
-- 7. Stable sent counts even if user deletes notifications; live read count based on notifications.is_read
-- 8. Service-only batch worker + admin-guarded bounded manual trigger
-- 9. Keyset cursor pagination for campaigns and recipient delivery results with malformed/infinity protection
-- 10. Conditional pg_cron registration with safe fallback & scheduler readiness reporting
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. NOTIFICATION CAMPAIGNS CORE TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.notification_campaigns (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title_vi text NOT NULL CHECK (length(trim(title_vi)) >= 1 AND length(title_vi) <= 200),
    title_en text NOT NULL CHECK (length(trim(title_en)) >= 1 AND length(title_en) <= 200),
    body_vi text NOT NULL CHECK (length(trim(body_vi)) >= 1 AND length(body_vi) <= 2000),
    body_en text NOT NULL CHECK (length(trim(body_en)) >= 1 AND length(body_en) <= 2000),
    default_locale text NOT NULL DEFAULT 'vi' CHECK (default_locale IN ('vi', 'en', 'bilingual')),
    audience_type text NOT NULL DEFAULT 'all' CHECK (audience_type IN ('all', 'role', 'selected_users')),
    target_role text CHECK (target_role IS NULL OR target_role IN ('member', 'moderator', 'admin')),
    selected_user_ids uuid[] DEFAULT '{}'::uuid[],
    status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'queued', 'sending', 'completed', 'cancelled', 'failed')),
    revision integer NOT NULL DEFAULT 1 CHECK (revision >= 1),
    total_recipients integer NOT NULL DEFAULT 0 CHECK (total_recipients >= 0),
    recipient_snapshot_taken boolean NOT NULL DEFAULT false,
    snapshot_taken_at timestamptz,
    sent_count integer NOT NULL DEFAULT 0 CHECK (sent_count >= 0),
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    scheduled_for timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    cancelled_by uuid REFERENCES public.profiles(id),
    cancel_reason text,
    created_by uuid NOT NULL REFERENCES public.profiles(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_audience_role CHECK (
        (audience_type = 'role' AND target_role IS NOT NULL) OR
        (audience_type <> 'role' AND target_role IS NULL)
    ),
    CONSTRAINT chk_audience_selected CHECK (
        (audience_type = 'selected_users' AND cardinality(selected_user_ids) > 0 AND cardinality(selected_user_ids) <= 1000) OR
        (audience_type <> 'selected_users' AND (selected_user_ids IS NULL OR cardinality(selected_user_ids) = 0))
    )
);

CREATE INDEX IF NOT EXISTS idx_notif_campaigns_status_sched
    ON public.notification_campaigns (status, scheduled_for ASC);

CREATE INDEX IF NOT EXISTS idx_notif_campaigns_created_cursor
    ON public.notification_campaigns (created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_notif_campaigns_created_by
    ON public.notification_campaigns (created_by);

ALTER TABLE public.notification_campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_campaigns NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_campaigns FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.notification_campaigns TO service_role;


-- ============================================================================
-- 2. NOTIFICATION CAMPAIGN RECIPIENTS (Deterministic snapshot & delivery receipts)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.notification_campaign_recipients (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id uuid NOT NULL REFERENCES public.notification_campaigns(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
    notification_id uuid REFERENCES public.notifications(id) ON DELETE SET NULL,
    sent_at timestamptz,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_notification_campaign_recipients UNIQUE (campaign_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_notif_recipients_camp_status
    ON public.notification_campaign_recipients (campaign_id, status);

CREATE INDEX IF NOT EXISTS idx_notif_recipients_user
    ON public.notification_campaign_recipients (user_id);

CREATE INDEX IF NOT EXISTS idx_notif_recipients_notif_id
    ON public.notification_campaign_recipients (notification_id);

CREATE INDEX IF NOT EXISTS idx_notif_recipients_cursor
    ON public.notification_campaign_recipients (campaign_id, created_at ASC, id ASC);

ALTER TABLE public.notification_campaign_recipients ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_campaign_recipients NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_campaign_recipients FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.notification_campaign_recipients TO service_role;


-- ============================================================================
-- 3. NOTIFICATION CAMPAIGN REQUESTS (Mutation Idempotency & Replay Ledger)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.notification_campaign_requests (
    request_id uuid PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES public.profiles(id),
    action text NOT NULL,
    campaign_id uuid REFERENCES public.notification_campaigns(id) ON DELETE CASCADE,
    payload_hash text NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_notif_requests_actor
    ON public.notification_campaign_requests (admin_id, action);

ALTER TABLE public.notification_campaign_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_campaign_requests NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_campaign_requests FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.notification_campaign_requests TO service_role;


-- ============================================================================
-- 4. NOTIFICATION CAMPAIGN EVENTS (Append-only Audit Log)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.notification_campaign_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id uuid NOT NULL REFERENCES public.notification_campaigns(id) ON DELETE CASCADE,
    admin_id uuid REFERENCES public.profiles(id),
    action text NOT NULL,
    reason text,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_notif_events_campaign
    ON public.notification_campaign_events (campaign_id, created_at DESC);

ALTER TABLE public.notification_campaign_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_campaign_events NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_campaign_events FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.notification_campaign_events TO service_role;


-- ============================================================================
-- 5. WORKER EXECUTION MARKERS
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.notification_worker_heartbeats (
    worker_key text PRIMARY KEY,
    last_run_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    campaigns_processed integer NOT NULL DEFAULT 0,
    notifications_sent integer NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'idle'
);

ALTER TABLE public.notification_worker_heartbeats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_worker_heartbeats NO FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notification_worker_heartbeats FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.notification_worker_heartbeats TO service_role;


-- ============================================================================
-- 6. INTERNAL HELPER FUNCTIONS (Service/Definer only, private execution)
-- ============================================================================

-- Compute delivered title and message according to default_locale
CREATE OR REPLACE FUNCTION public._notif_resolve_delivered_content(
    p_title_vi text,
    p_title_en text,
    p_body_vi text,
    p_body_en text,
    p_locale text,
    OUT delivered_title text,
    OUT delivered_message text
)
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF p_locale = 'en' THEN
        delivered_title := COALESCE(NULLIF(TRIM(p_title_en), ''), p_title_vi);
        delivered_message := COALESCE(NULLIF(TRIM(p_body_en), ''), p_body_vi);
    ELSIF p_locale = 'bilingual' THEN
        delivered_title := TRIM(p_title_vi) || ' / ' || TRIM(p_title_en);
        delivered_message := TRIM(p_body_vi) || E'\n\n---\n\n' || TRIM(p_body_en);
    ELSE -- 'vi' or fallback
        delivered_title := COALESCE(NULLIF(TRIM(p_title_vi), ''), p_title_en);
        delivered_message := COALESCE(NULLIF(TRIM(p_body_vi), ''), p_body_en);
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION public._notif_resolve_delivered_content(text, text, text, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._notif_resolve_delivered_content(text, text, text, text, text) TO service_role;

-- Format campaign row into consistent JSON DTO
CREATE OR REPLACE FUNCTION public._notif_campaign_to_jsonb(
    p_camp public.notification_campaigns
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_live_read_count bigint;
BEGIN
    SELECT COUNT(*) INTO v_live_read_count
    FROM public.notification_campaign_recipients r
    JOIN public.notifications n ON n.id = r.notification_id
    WHERE r.campaign_id = p_camp.id AND n.is_read = true;

    RETURN jsonb_build_object(
        'id', p_camp.id,
        'title_vi', p_camp.title_vi,
        'title_en', p_camp.title_en,
        'body_vi', p_camp.body_vi,
        'body_en', p_camp.body_en,
        'default_locale', p_camp.default_locale,
        'audience_type', p_camp.audience_type,
        'target_role', p_camp.target_role,
        'selected_user_ids', to_jsonb(COALESCE(p_camp.selected_user_ids, '{}'::uuid[])),
        'status', p_camp.status,
        'revision', p_camp.revision,
        'total_recipients', p_camp.total_recipients,
        'recipient_snapshot_taken', p_camp.recipient_snapshot_taken,
        'snapshot_taken_at', CASE WHEN p_camp.snapshot_taken_at IS NOT NULL THEN to_char(p_camp.snapshot_taken_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
        'sent_count', p_camp.sent_count,
        'read_count', COALESCE(v_live_read_count, 0),
        'failed_count', p_camp.failed_count,
        'scheduled_for', CASE WHEN p_camp.scheduled_for IS NOT NULL THEN to_char(p_camp.scheduled_for AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
        'started_at', CASE WHEN p_camp.started_at IS NOT NULL THEN to_char(p_camp.started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
        'completed_at', CASE WHEN p_camp.completed_at IS NOT NULL THEN to_char(p_camp.completed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
        'cancelled_at', CASE WHEN p_camp.cancelled_at IS NOT NULL THEN to_char(p_camp.cancelled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
        'cancelled_by', p_camp.cancelled_by,
        'cancel_reason', p_camp.cancel_reason,
        'created_by', p_camp.created_by,
        'created_at', to_char(p_camp.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'updated_at', to_char(p_camp.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
    );
END;
$$;

REVOKE ALL ON FUNCTION public._notif_campaign_to_jsonb(public.notification_campaigns) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._notif_campaign_to_jsonb(public.notification_campaigns) TO service_role;

-- Take deterministic recipient snapshot and freeze it
CREATE OR REPLACE FUNCTION public._notif_snapshot_recipients(
    p_campaign_id uuid
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_camp public.notification_campaigns;
    v_count integer := 0;
BEGIN
    SELECT * INTO v_camp
    FROM public.notification_campaigns
    WHERE id = p_campaign_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    -- Delete any un-sent recipients if re-snapshotting pending draft/queued
    DELETE FROM public.notification_campaign_recipients
    WHERE campaign_id = p_campaign_id AND status = 'pending';

    IF v_camp.audience_type = 'all' THEN
        INSERT INTO public.notification_campaign_recipients (campaign_id, user_id, status)
        SELECT v_camp.id, p.id, 'pending'
        FROM public.profiles p
        ORDER BY p.id ASC
        ON CONFLICT (campaign_id, user_id) DO NOTHING;

    ELSIF v_camp.audience_type = 'role' THEN
        INSERT INTO public.notification_campaign_recipients (campaign_id, user_id, status)
        SELECT v_camp.id, p.id, 'pending'
        FROM public.profiles p
        WHERE p.role = v_camp.target_role
        ORDER BY p.id ASC
        ON CONFLICT (campaign_id, user_id) DO NOTHING;

    ELSIF v_camp.audience_type = 'selected_users' THEN
        INSERT INTO public.notification_campaign_recipients (campaign_id, user_id, status)
        SELECT v_camp.id, u.user_id, 'pending'
        FROM (SELECT DISTINCT unnest(v_camp.selected_user_ids) AS user_id) u
        JOIN public.profiles p ON p.id = u.user_id
        ORDER BY u.user_id ASC
        ON CONFLICT (campaign_id, user_id) DO NOTHING;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM public.notification_campaign_recipients
    WHERE campaign_id = p_campaign_id;

    UPDATE public.notification_campaigns
    SET total_recipients = v_count,
        recipient_snapshot_taken = true,
        snapshot_taken_at = clock_timestamp(),
        updated_at = clock_timestamp()
    WHERE id = p_campaign_id;

    RETURN v_count;
END;
$$;

REVOKE ALL ON FUNCTION public._notif_snapshot_recipients(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._notif_snapshot_recipients(uuid) TO service_role;

-- Common admin audit integration helper (records to 070 admin_action_events if available)
CREATE OR REPLACE FUNCTION public._notif_record_common_admin_audit(
    p_actor_id uuid,
    p_action text,
    p_target_type text,
    p_target_id text,
    p_reason text DEFAULT NULL,
    p_old_state jsonb DEFAULT NULL,
    p_new_state jsonb DEFAULT NULL,
    p_request_id uuid DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
BEGIN
    IF p_actor_id IS NOT NULL AND to_regprocedure('public.record_admin_action_event(uuid,text,text,text,text,jsonb,jsonb,uuid)') IS NOT NULL THEN
        PERFORM public.record_admin_action_event(
            p_actor_id,
            p_action,
            p_target_type,
            p_target_id,
            p_reason,
            p_old_state,
            p_new_state,
            p_request_id
        );
    END IF;
EXCEPTION WHEN OTHERS THEN
    NULL;
END;
$$;

REVOKE ALL ON FUNCTION public._notif_record_common_admin_audit(uuid, text, text, text, text, jsonb, jsonb, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._notif_record_common_admin_audit(uuid, text, text, text, text, jsonb, jsonb, uuid) TO service_role;


-- ============================================================================
-- 7. ADMIN RPC: ADMIN_CREATE_NOTIFICATION_CAMPAIGN
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_create_notification_campaign(
    p_request_id uuid,
    p_title_vi text,
    p_title_en text,
    p_body_vi text,
    p_body_en text,
    p_default_locale text DEFAULT 'vi',
    p_audience_type text DEFAULT 'all',
    p_target_role text DEFAULT NULL,
    p_selected_user_ids uuid[] DEFAULT NULL,
    p_reason text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_payload_hash text;
    v_existing_req public.notification_campaign_requests;
    v_new_campaign public.notification_campaigns;
    v_clean_selected uuid[] := '{}';
    v_missing_count integer;
    v_response jsonb;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_request_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_REQUEST_ID';
    END IF;

    -- Clean selected user IDs
    IF p_selected_user_ids IS NOT NULL AND cardinality(p_selected_user_ids) > 0 THEN
        SELECT array_agg(DISTINCT u ORDER BY u) INTO v_clean_selected
        FROM unnest(p_selected_user_ids) AS u
        WHERE u IS NOT NULL;
    END IF;

    -- Canonical JSON payload hash eliminates delimiter collisions
    v_payload_hash := md5(jsonb_build_object(
        'action', 'create',
        'title_vi', TRIM(COALESCE(p_title_vi, '')),
        'title_en', TRIM(COALESCE(p_title_en, '')),
        'body_vi', TRIM(COALESCE(p_body_vi, '')),
        'body_en', TRIM(COALESCE(p_body_en, '')),
        'default_locale', COALESCE(p_default_locale, 'vi'),
        'audience_type', COALESCE(p_audience_type, 'all'),
        'target_role', p_target_role,
        'selected_user_ids', to_jsonb(COALESCE(v_clean_selected, '{}'::uuid[])),
        'reason', NULLIF(TRIM(COALESCE(p_reason, '')), '')
    )::text);

    -- Request-scoped advisory lock prevents concurrency race on identical request_id
    PERFORM pg_advisory_xact_lock(hashtext('notif_req:' || p_request_id::text));

    -- Check idempotency
    SELECT * INTO v_existing_req
    FROM public.notification_campaign_requests
    WHERE request_id = p_request_id;

    IF v_existing_req.request_id IS NOT NULL THEN
        IF v_existing_req.admin_id <> v_admin_id THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already claimed by another administrator';
        END IF;
        IF v_existing_req.payload_hash <> v_payload_hash OR v_existing_req.action <> 'create' THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already used with different payload';
        END IF;
        RETURN v_existing_req.response_json || jsonb_build_object('replayed', true);
    END IF;

    -- Validate string bounds
    IF p_title_vi IS NULL OR TRIM(p_title_vi) = '' OR length(p_title_vi) > 200 THEN
        RAISE EXCEPTION 'INVALID_TITLE_VI: Title must be between 1 and 200 characters';
    END IF;
    IF p_title_en IS NULL OR TRIM(p_title_en) = '' OR length(p_title_en) > 200 THEN
        RAISE EXCEPTION 'INVALID_TITLE_EN: Title must be between 1 and 200 characters';
    END IF;
    IF p_body_vi IS NULL OR TRIM(p_body_vi) = '' OR length(p_body_vi) > 2000 THEN
        RAISE EXCEPTION 'INVALID_BODY_VI: Body must be between 1 and 2000 characters';
    END IF;
    IF p_body_en IS NULL OR TRIM(p_body_en) = '' OR length(p_body_en) > 2000 THEN
        RAISE EXCEPTION 'INVALID_BODY_EN: Body must be between 1 and 2000 characters';
    END IF;

    IF p_default_locale NOT IN ('vi', 'en', 'bilingual') THEN
        RAISE EXCEPTION 'INVALID_LOCALE: default_locale must be vi, en, or bilingual';
    END IF;

    IF p_audience_type NOT IN ('all', 'role', 'selected_users') THEN
        RAISE EXCEPTION 'INVALID_AUDIENCE_TYPE';
    END IF;

    IF p_audience_type = 'role' THEN
        IF p_target_role IS NULL OR p_target_role NOT IN ('member', 'moderator', 'admin') THEN
            RAISE EXCEPTION 'INVALID_TARGET_ROLE: Must specify valid role for role audience';
        END IF;
        v_clean_selected := '{}';
    ELSIF p_audience_type = 'selected_users' THEN
        p_target_role := NULL;
        IF v_clean_selected IS NULL OR cardinality(v_clean_selected) = 0 THEN
            RAISE EXCEPTION 'EMPTY_SELECTED_USERS: At least one user ID required';
        END IF;
        IF cardinality(v_clean_selected) > 1000 THEN
            RAISE EXCEPTION 'TOO_MANY_SELECTED_USERS: Maximum 1000 users allowed';
        END IF;

        -- Verify all selected users exist
        SELECT COUNT(*) INTO v_missing_count
        FROM unnest(v_clean_selected) AS u
        WHERE NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = u);

        IF v_missing_count > 0 THEN
            RAISE EXCEPTION 'INVALID_RECIPIENT_USER: One or more selected user IDs do not exist';
        END IF;
    ELSE
        p_target_role := NULL;
        v_clean_selected := '{}';
    END IF;

    INSERT INTO public.notification_campaigns (
        title_vi,
        title_en,
        body_vi,
        body_en,
        default_locale,
        audience_type,
        target_role,
        selected_user_ids,
        status,
        revision,
        created_by
    ) VALUES (
        TRIM(p_title_vi),
        TRIM(p_title_en),
        TRIM(p_body_vi),
        TRIM(p_body_en),
        p_default_locale,
        p_audience_type,
        p_target_role,
        v_clean_selected,
        'draft',
        1,
        v_admin_id
    ) RETURNING * INTO v_new_campaign;

    -- Append audit event
    INSERT INTO public.notification_campaign_events (
        campaign_id,
        admin_id,
        action,
        reason,
        details
    ) VALUES (
        v_new_campaign.id,
        v_admin_id,
        'created',
        p_reason,
        jsonb_build_object(
            'audience_type', p_audience_type,
            'default_locale', p_default_locale
        )
    );

    -- Common admin audit integration (if 070 installed)
    PERFORM public._notif_record_common_admin_audit(
        v_admin_id,
        'notification_campaign_create',
        'notification_campaign',
        v_new_campaign.id::text,
        p_reason,
        NULL,
        public._notif_campaign_to_jsonb(v_new_campaign),
        p_request_id
    );

    v_response := jsonb_build_object(
        'success', true,
        'replayed', false,
        'campaign', public._notif_campaign_to_jsonb(v_new_campaign)
    );

    INSERT INTO public.notification_campaign_requests (
        request_id,
        admin_id,
        action,
        campaign_id,
        payload_hash,
        response_json
    ) VALUES (
        p_request_id,
        v_admin_id,
        'create',
        v_new_campaign.id,
        v_payload_hash,
        v_response
    );

    RETURN v_response;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_create_notification_campaign(uuid, text, text, text, text, text, text, text, uuid[], text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_create_notification_campaign(uuid, text, text, text, text, text, text, text, uuid[], text) TO authenticated, service_role;


-- ============================================================================
-- 8. ADMIN RPC: ADMIN_UPDATE_NOTIFICATION_CAMPAIGN
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_update_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_title_vi text,
    p_title_en text,
    p_body_vi text,
    p_body_en text,
    p_default_locale text DEFAULT 'vi',
    p_audience_type text DEFAULT 'all',
    p_target_role text DEFAULT NULL,
    p_selected_user_ids uuid[] DEFAULT NULL,
    p_reason text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_payload_hash text;
    v_existing_req public.notification_campaign_requests;
    v_camp public.notification_campaigns;
    v_old_campaign_json jsonb;
    v_clean_selected uuid[] := '{}';
    v_missing_count integer;
    v_response jsonb;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_request_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_REQUEST_ID';
    END IF;
    IF p_campaign_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_CAMPAIGN_ID';
    END IF;
    IF p_expected_revision IS NULL THEN
        RAISE EXCEPTION 'REVISION_REQUIRED';
    END IF;

    IF p_selected_user_ids IS NOT NULL AND cardinality(p_selected_user_ids) > 0 THEN
        SELECT array_agg(DISTINCT u ORDER BY u) INTO v_clean_selected
        FROM unnest(p_selected_user_ids) AS u
        WHERE u IS NOT NULL;
    END IF;

    -- Canonical JSON payload hash eliminates delimiter collisions
    v_payload_hash := md5(jsonb_build_object(
        'action', 'update',
        'campaign_id', p_campaign_id,
        'expected_revision', p_expected_revision,
        'title_vi', TRIM(COALESCE(p_title_vi, '')),
        'title_en', TRIM(COALESCE(p_title_en, '')),
        'body_vi', TRIM(COALESCE(p_body_vi, '')),
        'body_en', TRIM(COALESCE(p_body_en, '')),
        'default_locale', COALESCE(p_default_locale, 'vi'),
        'audience_type', COALESCE(p_audience_type, 'all'),
        'target_role', p_target_role,
        'selected_user_ids', to_jsonb(COALESCE(v_clean_selected, '{}'::uuid[])),
        'reason', NULLIF(TRIM(COALESCE(p_reason, '')), '')
    )::text);

    PERFORM pg_advisory_xact_lock(hashtext('notif_req:' || p_request_id::text));

    SELECT * INTO v_existing_req
    FROM public.notification_campaign_requests
    WHERE request_id = p_request_id;

    IF v_existing_req.request_id IS NOT NULL THEN
        IF v_existing_req.admin_id <> v_admin_id THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already claimed by another administrator';
        END IF;
        IF v_existing_req.payload_hash <> v_payload_hash OR v_existing_req.action <> 'update' THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already used with different payload';
        END IF;
        RETURN v_existing_req.response_json || jsonb_build_object('replayed', true);
    END IF;

    SELECT * INTO v_camp
    FROM public.notification_campaigns
    WHERE id = p_campaign_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    v_old_campaign_json := public._notif_campaign_to_jsonb(v_camp);

    IF v_camp.status NOT IN ('draft', 'queued') THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_MODIFIABLE: Only draft or queued campaigns can be modified';
    END IF;

    IF v_camp.status = 'queued' AND v_camp.scheduled_for <= clock_timestamp() THEN
        RAISE EXCEPTION 'CAMPAIGN_ALREADY_DUE: Campaign is already due for processing';
    END IF;

    IF v_camp.revision <> p_expected_revision THEN
        RAISE EXCEPTION 'REVISION_CONFLICT: Expected revision % but found %', p_expected_revision, v_camp.revision;
    END IF;

    -- Validation
    IF p_title_vi IS NULL OR TRIM(p_title_vi) = '' OR length(p_title_vi) > 200 THEN
        RAISE EXCEPTION 'INVALID_TITLE_VI';
    END IF;
    IF p_title_en IS NULL OR TRIM(p_title_en) = '' OR length(p_title_en) > 200 THEN
        RAISE EXCEPTION 'INVALID_TITLE_EN';
    END IF;
    IF p_body_vi IS NULL OR TRIM(p_body_vi) = '' OR length(p_body_vi) > 2000 THEN
        RAISE EXCEPTION 'INVALID_BODY_VI';
    END IF;
    IF p_body_en IS NULL OR TRIM(p_body_en) = '' OR length(p_body_en) > 2000 THEN
        RAISE EXCEPTION 'INVALID_BODY_EN';
    END IF;

    IF p_default_locale NOT IN ('vi', 'en', 'bilingual') THEN
        RAISE EXCEPTION 'INVALID_LOCALE';
    END IF;

    IF p_audience_type NOT IN ('all', 'role', 'selected_users') THEN
        RAISE EXCEPTION 'INVALID_AUDIENCE_TYPE';
    END IF;

    IF p_audience_type = 'role' THEN
        IF p_target_role IS NULL OR p_target_role NOT IN ('member', 'moderator', 'admin') THEN
            RAISE EXCEPTION 'INVALID_TARGET_ROLE';
        END IF;
        v_clean_selected := '{}';
    ELSIF p_audience_type = 'selected_users' THEN
        p_target_role := NULL;
        IF v_clean_selected IS NULL OR cardinality(v_clean_selected) = 0 THEN
            RAISE EXCEPTION 'EMPTY_SELECTED_USERS';
        END IF;
        IF cardinality(v_clean_selected) > 1000 THEN
            RAISE EXCEPTION 'TOO_MANY_SELECTED_USERS';
        END IF;

        SELECT COUNT(*) INTO v_missing_count
        FROM unnest(v_clean_selected) AS u
        WHERE NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = u);

        IF v_missing_count > 0 THEN
            RAISE EXCEPTION 'INVALID_RECIPIENT_USER';
        END IF;
    ELSE
        p_target_role := NULL;
        v_clean_selected := '{}';
    END IF;

    UPDATE public.notification_campaigns
    SET title_vi = TRIM(p_title_vi),
        title_en = TRIM(p_title_en),
        body_vi = TRIM(p_body_vi),
        body_en = TRIM(p_body_en),
        default_locale = p_default_locale,
        audience_type = p_audience_type,
        target_role = p_target_role,
        selected_user_ids = v_clean_selected,
        revision = revision + 1,
        updated_at = clock_timestamp()
    WHERE id = p_campaign_id
    RETURNING * INTO v_camp;

    -- If campaign was queued, re-take recipient snapshot
    IF v_camp.status = 'queued' THEN
        PERFORM public._notif_snapshot_recipients(v_camp.id);
        SELECT * INTO v_camp FROM public.notification_campaigns WHERE id = v_camp.id;
    END IF;

    INSERT INTO public.notification_campaign_events (
        campaign_id,
        admin_id,
        action,
        reason,
        details
    ) VALUES (
        v_camp.id,
        v_admin_id,
        'updated',
        p_reason,
        jsonb_build_object('revision', v_camp.revision)
    );

    -- Common admin audit integration (if 070 installed)
    PERFORM public._notif_record_common_admin_audit(
        v_admin_id,
        'notification_campaign_update',
        'notification_campaign',
        v_camp.id::text,
        p_reason,
        v_old_campaign_json,
        public._notif_campaign_to_jsonb(v_camp),
        p_request_id
    );

    v_response := jsonb_build_object(
        'success', true,
        'replayed', false,
        'campaign', public._notif_campaign_to_jsonb(v_camp)
    );

    INSERT INTO public.notification_campaign_requests (
        request_id,
        admin_id,
        action,
        campaign_id,
        payload_hash,
        response_json
    ) VALUES (
        p_request_id,
        v_admin_id,
        'update',
        v_camp.id,
        v_payload_hash,
        v_response
    );

    RETURN v_response;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_update_notification_campaign(uuid, uuid, integer, text, text, text, text, text, text, text, uuid[], text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_notification_campaign(uuid, uuid, integer, text, text, text, text, text, text, text, uuid[], text) TO authenticated, service_role;


-- ============================================================================
-- 9. ADMIN RPC: ADMIN_PREVIEW_NOTIFICATION_CAMPAIGN
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_preview_notification_campaign(
    p_campaign_id uuid DEFAULT NULL,
    p_audience_type text DEFAULT NULL,
    p_target_role text DEFAULT NULL,
    p_selected_user_ids uuid[] DEFAULT NULL,
    p_default_locale text DEFAULT 'vi',
    p_title_vi text DEFAULT '',
    p_title_en text DEFAULT '',
    p_body_vi text DEFAULT '',
    p_body_en text DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_camp public.notification_campaigns;
    v_aud text;
    v_role text;
    v_selected uuid[] := '{}';
    v_locale text;
    v_title_vi text;
    v_title_en text;
    v_body_vi text;
    v_body_en text;
    v_count integer := 0;
    v_prev_vi_title text;
    v_prev_vi_body text;
    v_prev_en_title text;
    v_prev_en_body text;
    v_eff_title text;
    v_eff_body text;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_campaign_id IS NOT NULL THEN
        SELECT * INTO v_camp
        FROM public.notification_campaigns
        WHERE id = p_campaign_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
        END IF;

        v_aud := v_camp.audience_type;
        v_role := v_camp.target_role;
        v_selected := COALESCE(v_camp.selected_user_ids, '{}'::uuid[]);
        v_locale := v_camp.default_locale;
        v_title_vi := v_camp.title_vi;
        v_title_en := v_camp.title_en;
        v_body_vi := v_camp.body_vi;
        v_body_en := v_camp.body_en;

        -- If campaign has frozen recipient snapshot, use that exact count
        IF v_camp.recipient_snapshot_taken THEN
            v_count := v_camp.total_recipients;
        END IF;
    ELSE
        v_aud := COALESCE(p_audience_type, 'all');
        v_role := p_target_role;
        IF p_selected_user_ids IS NOT NULL THEN
            SELECT array_agg(DISTINCT u ORDER BY u) INTO v_selected FROM unnest(p_selected_user_ids) AS u WHERE u IS NOT NULL;
        END IF;
        v_locale := COALESCE(p_default_locale, 'vi');
        v_title_vi := COALESCE(p_title_vi, '');
        v_title_en := COALESCE(p_title_en, '');
        v_body_vi := COALESCE(p_body_vi, '');
        v_body_en := COALESCE(p_body_en, '');
    END IF;

    -- If count not yet derived from snapshot
    IF (v_camp.id IS NULL OR NOT v_camp.recipient_snapshot_taken) THEN
        IF v_aud = 'all' THEN
            SELECT COUNT(*) INTO v_count FROM public.profiles;
        ELSIF v_aud = 'role' THEN
            IF v_role NOT IN ('member', 'moderator', 'admin') THEN
                RAISE EXCEPTION 'INVALID_TARGET_ROLE';
            END IF;
            SELECT COUNT(*) INTO v_count FROM public.profiles WHERE role = v_role;
        ELSIF v_aud = 'selected_users' THEN
            IF v_selected IS NOT NULL AND cardinality(v_selected) > 0 THEN
                SELECT COUNT(*) INTO v_count
                FROM public.profiles
                WHERE id = ANY(v_selected);
            ELSE
                v_count := 0;
            END IF;
        END IF;
    END IF;

    -- Compute previews
    SELECT delivered_title, delivered_message INTO v_prev_vi_title, v_prev_vi_body
    FROM public._notif_resolve_delivered_content(v_title_vi, v_title_en, v_body_vi, v_body_en, 'vi');

    SELECT delivered_title, delivered_message INTO v_prev_en_title, v_prev_en_body
    FROM public._notif_resolve_delivered_content(v_title_vi, v_title_en, v_body_vi, v_body_en, 'en');

    SELECT delivered_title, delivered_message INTO v_eff_title, v_eff_body
    FROM public._notif_resolve_delivered_content(v_title_vi, v_title_en, v_body_vi, v_body_en, v_locale);

    RETURN jsonb_build_object(
        'success', true,
        'recipient_count', v_count,
        'audience_type', v_aud,
        'target_role', v_role,
        'default_locale', v_locale,
        'preview_vi', jsonb_build_object('title', v_prev_vi_title, 'body', v_prev_vi_body),
        'preview_en', jsonb_build_object('title', v_prev_en_title, 'body', v_prev_en_body),
        'preview_effective', jsonb_build_object('locale', v_locale, 'title', v_eff_title, 'body', v_eff_body)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_preview_notification_campaign(uuid, text, text, uuid[], text, text, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_preview_notification_campaign(uuid, text, text, uuid[], text, text, text, text, text) TO authenticated, service_role;


-- ============================================================================
-- 10. ADMIN RPC: ADMIN_SCHEDULE_NOTIFICATION_CAMPAIGN
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_schedule_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_scheduled_for timestamptz,
    p_reason text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_payload_hash text;
    v_existing_req public.notification_campaign_requests;
    v_camp public.notification_campaigns;
    v_old_campaign_json jsonb;
    v_response jsonb;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_request_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_REQUEST_ID';
    END IF;
    IF p_campaign_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_CAMPAIGN_ID';
    END IF;
    IF p_expected_revision IS NULL THEN
        RAISE EXCEPTION 'REVISION_REQUIRED';
    END IF;
    IF p_scheduled_for IS NULL THEN
        RAISE EXCEPTION 'SCHEDULE_TIME_REQUIRED';
    END IF;

    -- Canonical JSON payload hash eliminates delimiter collisions
    v_payload_hash := md5(jsonb_build_object(
        'action', 'schedule',
        'campaign_id', p_campaign_id,
        'expected_revision', p_expected_revision,
        'scheduled_for', to_char(p_scheduled_for AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'reason', NULLIF(TRIM(COALESCE(p_reason, '')), '')
    )::text);

    PERFORM pg_advisory_xact_lock(hashtext('notif_req:' || p_request_id::text));

    SELECT * INTO v_existing_req
    FROM public.notification_campaign_requests
    WHERE request_id = p_request_id;

    IF v_existing_req.request_id IS NOT NULL THEN
        IF v_existing_req.admin_id <> v_admin_id THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already claimed by another administrator';
        END IF;
        IF v_existing_req.payload_hash <> v_payload_hash OR v_existing_req.action <> 'schedule' THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already used with different payload';
        END IF;
        RETURN v_existing_req.response_json || jsonb_build_object('replayed', true);
    END IF;

    -- Fresh schedule validation: scheduled delivery must be strictly in the future
    IF p_scheduled_for <= clock_timestamp() THEN
        RAISE EXCEPTION 'SCHEDULE_TIME_MUST_BE_IN_FUTURE: Scheduled delivery must be strictly in the future';
    END IF;

    SELECT * INTO v_camp
    FROM public.notification_campaigns
    WHERE id = p_campaign_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    v_old_campaign_json := public._notif_campaign_to_jsonb(v_camp);

    IF v_camp.status NOT IN ('draft', 'queued') THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_MODIFIABLE: Only draft or queued campaigns can be scheduled';
    END IF;

    IF v_camp.status = 'queued' AND v_camp.scheduled_for <= clock_timestamp() THEN
        RAISE EXCEPTION 'CAMPAIGN_ALREADY_DUE: Campaign is already due for processing';
    END IF;

    IF v_camp.revision <> p_expected_revision THEN
        RAISE EXCEPTION 'REVISION_CONFLICT: Expected revision % but found %', p_expected_revision, v_camp.revision;
    END IF;

    -- Take deterministic recipient snapshot and freeze it
    PERFORM public._notif_snapshot_recipients(v_camp.id);

    UPDATE public.notification_campaigns
    SET status = 'queued',
        scheduled_for = p_scheduled_for,
        revision = revision + 1,
        updated_at = clock_timestamp()
    WHERE id = p_campaign_id
    RETURNING * INTO v_camp;

    INSERT INTO public.notification_campaign_events (
        campaign_id,
        admin_id,
        action,
        reason,
        details
    ) VALUES (
        v_camp.id,
        v_admin_id,
        'scheduled',
        p_reason,
        jsonb_build_object(
            'scheduled_for', to_char(p_scheduled_for AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'total_recipients', v_camp.total_recipients,
            'revision', v_camp.revision
        )
    );

    -- Common admin audit integration (if 070 installed)
    PERFORM public._notif_record_common_admin_audit(
        v_admin_id,
        'notification_campaign_schedule',
        'notification_campaign',
        v_camp.id::text,
        p_reason,
        v_old_campaign_json,
        public._notif_campaign_to_jsonb(v_camp),
        p_request_id
    );

    v_response := jsonb_build_object(
        'success', true,
        'replayed', false,
        'campaign', public._notif_campaign_to_jsonb(v_camp)
    );

    INSERT INTO public.notification_campaign_requests (
        request_id,
        admin_id,
        action,
        campaign_id,
        payload_hash,
        response_json
    ) VALUES (
        p_request_id,
        v_admin_id,
        'schedule',
        v_camp.id,
        v_payload_hash,
        v_response
    );

    RETURN v_response;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_schedule_notification_campaign(uuid, uuid, integer, timestamptz, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_schedule_notification_campaign(uuid, uuid, integer, timestamptz, text) TO authenticated, service_role;


-- ============================================================================
-- 11. ADMIN RPC: ADMIN_SEND_NOW_NOTIFICATION_CAMPAIGN
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_send_now_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_reason text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_payload_hash text;
    v_existing_req public.notification_campaign_requests;
    v_camp public.notification_campaigns;
    v_old_campaign_json jsonb;
    v_response jsonb;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_request_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_REQUEST_ID';
    END IF;
    IF p_campaign_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_CAMPAIGN_ID';
    END IF;
    IF p_expected_revision IS NULL THEN
        RAISE EXCEPTION 'REVISION_REQUIRED';
    END IF;

    -- Canonical JSON payload hash eliminates delimiter collisions
    v_payload_hash := md5(jsonb_build_object(
        'action', 'send_now',
        'campaign_id', p_campaign_id,
        'expected_revision', p_expected_revision,
        'reason', NULLIF(TRIM(COALESCE(p_reason, '')), '')
    )::text);

    PERFORM pg_advisory_xact_lock(hashtext('notif_req:' || p_request_id::text));

    SELECT * INTO v_existing_req
    FROM public.notification_campaign_requests
    WHERE request_id = p_request_id;

    IF v_existing_req.request_id IS NOT NULL THEN
        IF v_existing_req.admin_id <> v_admin_id THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already claimed by another administrator';
        END IF;
        IF v_existing_req.payload_hash <> v_payload_hash OR v_existing_req.action <> 'send_now' THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already used with different payload';
        END IF;
        RETURN v_existing_req.response_json || jsonb_build_object('replayed', true);
    END IF;

    SELECT * INTO v_camp
    FROM public.notification_campaigns
    WHERE id = p_campaign_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    v_old_campaign_json := public._notif_campaign_to_jsonb(v_camp);

    IF v_camp.status NOT IN ('draft', 'queued') THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_MODIFIABLE: Only draft or queued campaigns can be sent now';
    END IF;

    IF v_camp.revision <> p_expected_revision THEN
        RAISE EXCEPTION 'REVISION_CONFLICT: Expected revision % but found %', p_expected_revision, v_camp.revision;
    END IF;

    -- Freeze recipient snapshot if not yet taken
    IF NOT v_camp.recipient_snapshot_taken THEN
        PERFORM public._notif_snapshot_recipients(v_camp.id);
    END IF;

    UPDATE public.notification_campaigns
    SET status = 'queued',
        scheduled_for = clock_timestamp(),
        revision = revision + 1,
        updated_at = clock_timestamp()
    WHERE id = p_campaign_id
    RETURNING * INTO v_camp;

    INSERT INTO public.notification_campaign_events (
        campaign_id,
        admin_id,
        action,
        reason,
        details
    ) VALUES (
        v_camp.id,
        v_admin_id,
        'send_now_requested',
        p_reason,
        jsonb_build_object(
            'total_recipients', v_camp.total_recipients,
            'revision', v_camp.revision
        )
    );

    -- Common admin audit integration (if 070 installed)
    PERFORM public._notif_record_common_admin_audit(
        v_admin_id,
        'notification_campaign_send_now',
        'notification_campaign',
        v_camp.id::text,
        p_reason,
        v_old_campaign_json,
        public._notif_campaign_to_jsonb(v_camp),
        p_request_id
    );

    v_response := jsonb_build_object(
        'success', true,
        'replayed', false,
        'campaign', public._notif_campaign_to_jsonb(v_camp)
    );

    INSERT INTO public.notification_campaign_requests (
        request_id,
        admin_id,
        action,
        campaign_id,
        payload_hash,
        response_json
    ) VALUES (
        p_request_id,
        v_admin_id,
        'send_now',
        v_camp.id,
        v_payload_hash,
        v_response
    );

    RETURN v_response;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_send_now_notification_campaign(uuid, uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_send_now_notification_campaign(uuid, uuid, integer, text) TO authenticated, service_role;


-- ============================================================================
-- 12. ADMIN RPC: ADMIN_CANCEL_NOTIFICATION_CAMPAIGN
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_cancel_notification_campaign(
    p_request_id uuid,
    p_campaign_id uuid,
    p_expected_revision integer,
    p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_payload_hash text;
    v_existing_req public.notification_campaign_requests;
    v_camp public.notification_campaigns;
    v_old_campaign_json jsonb;
    v_response jsonb;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_request_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_REQUEST_ID';
    END IF;
    IF p_campaign_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_CAMPAIGN_ID';
    END IF;
    IF p_expected_revision IS NULL THEN
        RAISE EXCEPTION 'REVISION_REQUIRED';
    END IF;
    IF p_reason IS NULL OR TRIM(p_reason) = '' THEN
        RAISE EXCEPTION 'REASON_REQUIRED: Cancellation reason is required';
    END IF;

    -- Canonical JSON payload hash eliminates delimiter collisions
    v_payload_hash := md5(jsonb_build_object(
        'action', 'cancel',
        'campaign_id', p_campaign_id,
        'expected_revision', p_expected_revision,
        'reason', TRIM(p_reason)
    )::text);

    PERFORM pg_advisory_xact_lock(hashtext('notif_req:' || p_request_id::text));

    SELECT * INTO v_existing_req
    FROM public.notification_campaign_requests
    WHERE request_id = p_request_id;

    IF v_existing_req.request_id IS NOT NULL THEN
        IF v_existing_req.admin_id <> v_admin_id THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already claimed by another administrator';
        END IF;
        IF v_existing_req.payload_hash <> v_payload_hash OR v_existing_req.action <> 'cancel' THEN
            RAISE EXCEPTION 'REQUEST_ID_CONFLICT: Request ID already used with different payload';
        END IF;
        RETURN v_existing_req.response_json || jsonb_build_object('replayed', true);
    END IF;

    SELECT * INTO v_camp
    FROM public.notification_campaigns
    WHERE id = p_campaign_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    v_old_campaign_json := public._notif_campaign_to_jsonb(v_camp);

    IF v_camp.revision <> p_expected_revision THEN
        RAISE EXCEPTION 'REVISION_CONFLICT: Expected revision % but found %', p_expected_revision, v_camp.revision;
    END IF;

    -- Only draft or future queued campaigns can be cancelled
    IF v_camp.status = 'draft' THEN
        -- Allowed
        NULL;
    ELSIF v_camp.status = 'queued' AND v_camp.scheduled_for > clock_timestamp() THEN
        -- Future queued: Allowed
        NULL;
    ELSE
        RAISE EXCEPTION 'CANNOT_CANCEL_IN_FLIGHT_OR_PAST_CAMPAIGN: Cannot cancel campaign that is sending, completed, cancelled, or already due';
    END IF;

    -- Remove any unsent recipients
    DELETE FROM public.notification_campaign_recipients
    WHERE campaign_id = p_campaign_id AND status = 'pending';

    UPDATE public.notification_campaigns
    SET status = 'cancelled',
        cancelled_at = clock_timestamp(),
        cancelled_by = v_admin_id,
        cancel_reason = TRIM(p_reason),
        revision = revision + 1,
        updated_at = clock_timestamp()
    WHERE id = p_campaign_id
    RETURNING * INTO v_camp;

    INSERT INTO public.notification_campaign_events (
        campaign_id,
        admin_id,
        action,
        reason,
        details
    ) VALUES (
        v_camp.id,
        v_admin_id,
        'cancelled',
        TRIM(p_reason),
        jsonb_build_object('revision', v_camp.revision)
    );

    -- Common admin audit integration (if 070 installed)
    PERFORM public._notif_record_common_admin_audit(
        v_admin_id,
        'notification_campaign_cancel',
        'notification_campaign',
        v_camp.id::text,
        p_reason,
        v_old_campaign_json,
        public._notif_campaign_to_jsonb(v_camp),
        p_request_id
    );

    v_response := jsonb_build_object(
        'success', true,
        'replayed', false,
        'campaign', public._notif_campaign_to_jsonb(v_camp)
    );

    INSERT INTO public.notification_campaign_requests (
        request_id,
        admin_id,
        action,
        campaign_id,
        payload_hash,
        response_json
    ) VALUES (
        p_request_id,
        v_admin_id,
        'cancel',
        v_camp.id,
        v_payload_hash,
        v_response
    );

    RETURN v_response;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_cancel_notification_campaign(uuid, uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_cancel_notification_campaign(uuid, uuid, integer, text) TO authenticated, service_role;


-- ============================================================================
-- 13. CORE BATCH FANOUT WORKER (Service-only execution)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.service_process_notification_campaigns(
    p_batch_size integer DEFAULT 500,
    p_max_campaigns integer DEFAULT 10,
    p_campaign_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_camp RECORD;
    v_campaigns_processed integer := 0;
    v_total_notifications_sent integer := 0;
    v_camp_batch_sent integer := 0;
    v_deliv_title text;
    v_deliv_message text;
    v_pending_remaining integer;
    v_batch_limit integer;
    v_details jsonb := '[]'::jsonb;
BEGIN
    v_batch_limit := GREATEST(1, LEAST(COALESCE(p_batch_size, 500), 1000));

    FOR v_camp IN
        SELECT c.*
        FROM public.notification_campaigns c
        WHERE (p_campaign_id IS NULL OR c.id = p_campaign_id)
          AND c.status IN ('queued', 'sending')
          AND c.scheduled_for <= clock_timestamp()
        ORDER BY c.scheduled_for ASC, c.id ASC
        LIMIT p_max_campaigns
        FOR UPDATE SKIP LOCKED
    LOOP
        -- If campaign was queued, transition to sending and ensure snapshot
        IF v_camp.status = 'queued' THEN
            -- Only take snapshot if NOT already frozen
            IF NOT v_camp.recipient_snapshot_taken THEN
                PERFORM public._notif_snapshot_recipients(v_camp.id);
                SELECT * INTO v_camp FROM public.notification_campaigns WHERE id = v_camp.id;
            END IF;

            -- If frozen snapshot has 0 recipients, complete immediately with 0 sent
            IF v_camp.total_recipients = 0 THEN
                UPDATE public.notification_campaigns
                SET status = 'completed',
                    started_at = COALESCE(v_camp.started_at, clock_timestamp()),
                    completed_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = v_camp.id;

                INSERT INTO public.notification_campaign_events (campaign_id, action, details)
                VALUES (v_camp.id, 'worker_completed', jsonb_build_object('total_recipients', 0, 'sent_count', 0));

                v_details := v_details || jsonb_build_object(
                    'campaign_id', v_camp.id,
                    'sent_in_batch', 0,
                    'pending_remaining', 0,
                    'completed', true
                );
                v_campaigns_processed := v_campaigns_processed + 1;
                CONTINUE;
            END IF;

            UPDATE public.notification_campaigns
            SET status = 'sending',
                started_at = COALESCE(v_camp.started_at, clock_timestamp()),
                updated_at = clock_timestamp()
            WHERE id = v_camp.id;

            INSERT INTO public.notification_campaign_events (campaign_id, action, details)
            VALUES (v_camp.id, 'worker_started', jsonb_build_object('batch_size', v_batch_limit));
        END IF;

        -- Resolve delivered content
        SELECT delivered_title, delivered_message INTO v_deliv_title, v_deliv_message
        FROM public._notif_resolve_delivered_content(
            v_camp.title_vi,
            v_camp.title_en,
            v_camp.body_vi,
            v_camp.body_en,
            v_camp.default_locale
        );

        -- Atomically lock pending recipient rows with SKIP LOCKED,
        -- create notifications rows without popup states,
        -- and update recipient receipts to 'sent'
        WITH target_recipients AS (
            SELECT r.id AS recipient_id, r.user_id
            FROM public.notification_campaign_recipients r
            WHERE r.campaign_id = v_camp.id
              AND r.status = 'pending'
            ORDER BY r.user_id ASC
            LIMIT v_batch_limit
            FOR UPDATE SKIP LOCKED
        ),
        created_notifications AS (
            INSERT INTO public.notifications (
                user_id,
                title,
                message,
                type,
                data,
                created_at
            )
            SELECT
                tr.user_id,
                v_deliv_title,
                v_deliv_message,
                'admin',
                jsonb_build_object(
                    'campaign_id', v_camp.id,
                    'broadcast', true,
                    'sender', 'admin',
                    'locale', v_camp.default_locale,
                    'title_vi', v_camp.title_vi,
                    'title_en', v_camp.title_en
                ),
                clock_timestamp()
            FROM target_recipients tr
            RETURNING id AS notification_id, user_id
        ),
        updated_recipients AS (
            UPDATE public.notification_campaign_recipients r
            SET status = 'sent',
                notification_id = cn.notification_id,
                sent_at = clock_timestamp()
            FROM created_notifications cn
            WHERE r.campaign_id = v_camp.id
              AND r.user_id = cn.user_id
              AND r.status = 'pending'
            RETURNING r.id
        )
        SELECT COUNT(*) INTO v_camp_batch_sent FROM updated_recipients;

        v_total_notifications_sent := v_total_notifications_sent + v_camp_batch_sent;
        v_campaigns_processed := v_campaigns_processed + 1;

        -- Update sent count on campaign
        UPDATE public.notification_campaigns
        SET sent_count = (
                SELECT COUNT(*)
                FROM public.notification_campaign_recipients
                WHERE campaign_id = v_camp.id AND status = 'sent'
            ),
            updated_at = clock_timestamp()
        WHERE id = v_camp.id;

        -- Check remaining pending recipients
        SELECT COUNT(*) INTO v_pending_remaining
        FROM public.notification_campaign_recipients
        WHERE campaign_id = v_camp.id AND status = 'pending';

        IF v_pending_remaining = 0 THEN
            UPDATE public.notification_campaigns
            SET status = 'completed',
                completed_at = clock_timestamp(),
                updated_at = clock_timestamp()
            WHERE id = v_camp.id;

            INSERT INTO public.notification_campaign_events (campaign_id, action, details)
            VALUES (v_camp.id, 'worker_completed', jsonb_build_object(
                'total_recipients', v_camp.total_recipients,
                'sent_count', (SELECT COUNT(*) FROM public.notification_campaign_recipients WHERE campaign_id = v_camp.id AND status = 'sent')
            ));
        END IF;

        v_details := v_details || jsonb_build_object(
            'campaign_id', v_camp.id,
            'sent_in_batch', v_camp_batch_sent,
            'pending_remaining', v_pending_remaining,
            'completed', (v_pending_remaining = 0)
        );
    END LOOP;

    -- Update heartbeat
    INSERT INTO public.notification_worker_heartbeats (
        worker_key,
        last_run_at,
        campaigns_processed,
        notifications_sent,
        status
    ) VALUES (
        'service_cron',
        clock_timestamp(),
        v_campaigns_processed,
        v_total_notifications_sent,
        'idle'
    )
    ON CONFLICT (worker_key) DO UPDATE SET
        last_run_at = excluded.last_run_at,
        campaigns_processed = public.notification_worker_heartbeats.campaigns_processed + excluded.campaigns_processed,
        notifications_sent = public.notification_worker_heartbeats.notifications_sent + excluded.notifications_sent,
        status = excluded.status;

    RETURN jsonb_build_object(
        'success', true,
        'campaigns_processed', v_campaigns_processed,
        'notifications_sent', v_total_notifications_sent,
        'details', v_details
    );
END;
$$;

REVOKE ALL ON FUNCTION public.service_process_notification_campaigns(integer, integer, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.service_process_notification_campaigns(integer, integer, uuid) TO service_role;


-- ============================================================================
-- 14. ADMIN RPC: ADMIN_PROCESS_DUE_NOTIFICATION_CAMPAIGNS (Bounded wrapper)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_process_due_notification_campaigns(
    p_batch_size integer DEFAULT 100,
    p_campaign_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_bounded_batch integer;
    v_result jsonb;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    v_bounded_batch := GREATEST(1, LEAST(COALESCE(p_batch_size, 100), 500));

    v_result := public.service_process_notification_campaigns(
        v_bounded_batch,
        10,
        p_campaign_id
    );

    -- Common admin audit integration (if 070 installed)
    PERFORM public._notif_record_common_admin_audit(
        v_admin_id,
        'notification_campaign_process_due',
        'notification_campaign_worker',
        COALESCE(p_campaign_id::text, 'all_due'),
        'admin_manual_trigger',
        NULL,
        v_result,
        NULL
    );

    RETURN v_result || jsonb_build_object('triggered_by_admin', v_admin_id);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_process_due_notification_campaigns(integer, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_process_due_notification_campaigns(integer, uuid) TO authenticated, service_role;


-- ============================================================================
-- 15. ADMIN RPC: ADMIN_LIST_NOTIFICATION_CAMPAIGNS (Cursor paginated)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_list_notification_campaigns(
    p_status text DEFAULT NULL,
    p_audience_type text DEFAULT NULL,
    p_limit integer DEFAULT 20,
    p_cursor text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_limit integer;
    v_cursor_created_at timestamptz;
    v_cursor_id uuid;
    v_items jsonb := '[]'::jsonb;
    v_has_more boolean := false;
    v_next_cursor text := NULL;
    v_last_created_at timestamptz;
    v_last_id uuid;
    v_row RECORD;
    v_count integer := 0;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    v_limit := GREATEST(1, LEAST(COALESCE(p_limit, 20), 100));

    -- Whitelist status and audience filters
    IF p_status IS NOT NULL AND TRIM(p_status) <> '' THEN
        IF p_status NOT IN ('draft', 'queued', 'sending', 'completed', 'cancelled', 'failed') THEN
            RAISE EXCEPTION 'INVALID_STATUS_FILTER: Status must be one of draft, queued, sending, completed, cancelled, failed';
        END IF;
    END IF;

    IF p_audience_type IS NOT NULL AND TRIM(p_audience_type) <> '' THEN
        IF p_audience_type NOT IN ('all', 'role', 'selected_users') THEN
            RAISE EXCEPTION 'INVALID_AUDIENCE_FILTER: Audience must be one of all, role, selected_users';
        END IF;
    END IF;

    IF p_cursor IS NOT NULL AND TRIM(p_cursor) <> '' THEN
        IF position('|' in p_cursor) = 0 THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor format must be ISO8601|uuid';
        END IF;

        IF lower(p_cursor) LIKE '%infinity%' OR lower(p_cursor) LIKE '%nan%' THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor cannot contain infinity or NaN values';
        END IF;

        BEGIN
            v_cursor_created_at := split_part(p_cursor, '|', 1)::timestamptz;
            v_cursor_id := split_part(p_cursor, '|', 2)::uuid;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor format must be ISO8601|uuid';
        END;

        IF v_cursor_created_at IS NULL OR v_cursor_id IS NULL THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor format must be ISO8601|uuid';
        END IF;
    END IF;

    FOR v_row IN
        SELECT c.*,
            COALESCE((
                SELECT COUNT(*)
                FROM public.notification_campaign_recipients r
                JOIN public.notifications n ON n.id = r.notification_id
                WHERE r.campaign_id = c.id AND n.is_read = true
            ), 0) AS live_read_count
        FROM public.notification_campaigns c
        WHERE (p_status IS NULL OR c.status = p_status)
          AND (p_audience_type IS NULL OR c.audience_type = p_audience_type)
          AND (
              v_cursor_created_at IS NULL OR
              (c.created_at, c.id) < (v_cursor_created_at, v_cursor_id)
          )
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT v_limit + 1
    LOOP
        v_count := v_count + 1;
        IF v_count <= v_limit THEN
            v_items := v_items || jsonb_build_object(
                'id', v_row.id,
                'title_vi', v_row.title_vi,
                'title_en', v_row.title_en,
                'body_vi', v_row.body_vi,
                'body_en', v_row.body_en,
                'default_locale', v_row.default_locale,
                'audience_type', v_row.audience_type,
                'target_role', v_row.target_role,
                'status', v_row.status,
                'revision', v_row.revision,
                'total_recipients', v_row.total_recipients,
                'recipient_snapshot_taken', v_row.recipient_snapshot_taken,
                'snapshot_taken_at', CASE WHEN v_row.snapshot_taken_at IS NOT NULL THEN to_char(v_row.snapshot_taken_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
                'sent_count', v_row.sent_count,
                'read_count', v_row.live_read_count,
                'failed_count', v_row.failed_count,
                'scheduled_for', CASE WHEN v_row.scheduled_for IS NOT NULL THEN to_char(v_row.scheduled_for AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
                'started_at', CASE WHEN v_row.started_at IS NOT NULL THEN to_char(v_row.started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
                'completed_at', CASE WHEN v_row.completed_at IS NOT NULL THEN to_char(v_row.completed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
                'created_by', v_row.created_by,
                'created_at', to_char(v_row.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'updated_at', to_char(v_row.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
            );
            v_last_created_at := v_row.created_at;
            v_last_id := v_row.id;
        ELSE
            v_has_more := true;
        END IF;
    END LOOP;

    IF v_has_more AND v_last_created_at IS NOT NULL THEN
        v_next_cursor := to_char(v_last_created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|' || v_last_id::text;
    END IF;

    RETURN jsonb_build_object(
        'items', v_items,
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_notification_campaigns(text, text, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_list_notification_campaigns(text, text, integer, text) TO authenticated, service_role;


-- ============================================================================
-- 16. ADMIN RPC: ADMIN_GET_NOTIFICATION_CAMPAIGN_DETAIL
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_get_notification_campaign_detail(
    p_campaign_id uuid,
    p_events_limit integer DEFAULT 50
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_camp public.notification_campaigns;
    v_events jsonb := '[]'::jsonb;
    v_preview jsonb;
    v_ev RECORD;
    v_events_limit integer;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_campaign_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_CAMPAIGN_ID';
    END IF;

    v_events_limit := GREATEST(1, LEAST(COALESCE(p_events_limit, 50), 100));

    SELECT * INTO v_camp
    FROM public.notification_campaigns
    WHERE id = p_campaign_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    v_preview := public.admin_preview_notification_campaign(p_campaign_id => p_campaign_id);

    FOR v_ev IN
        SELECT e.id, e.action, e.admin_id, e.reason, e.details,
               to_char(e.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS created_at_iso
        FROM public.notification_campaign_events e
        WHERE e.campaign_id = p_campaign_id
        ORDER BY e.created_at DESC, e.id DESC
        LIMIT v_events_limit
    LOOP
        v_events := v_events || jsonb_build_object(
            'id', v_ev.id,
            'action', v_ev.action,
            'admin_id', v_ev.admin_id,
            'reason', v_ev.reason,
            'details', v_ev.details,
            'created_at', v_ev.created_at_iso
        );
    END LOOP;

    RETURN jsonb_build_object(
        'campaign', public._notif_campaign_to_jsonb(v_camp),
        'preview', v_preview,
        'events', v_events
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_notification_campaign_detail(uuid, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_notification_campaign_detail(uuid, integer) TO authenticated, service_role;


-- ============================================================================
-- 17. ADMIN RPC: ADMIN_LIST_NOTIFICATION_CAMPAIGN_RECIPIENTS (Cursor paginated)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_list_notification_campaign_recipients(
    p_campaign_id uuid,
    p_status text DEFAULT NULL,
    p_limit integer DEFAULT 50,
    p_cursor text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_limit integer;
    v_cursor_created_at timestamptz;
    v_cursor_id uuid;
    v_items jsonb := '[]'::jsonb;
    v_has_more boolean := false;
    v_next_cursor text := NULL;
    v_last_created_at timestamptz;
    v_last_id uuid;
    v_row RECORD;
    v_count integer := 0;
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    IF p_campaign_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_CAMPAIGN_ID';
    END IF;

    v_limit := GREATEST(1, LEAST(COALESCE(p_limit, 50), 100));

    -- Whitelist status filter
    IF p_status IS NOT NULL AND TRIM(p_status) <> '' THEN
        IF p_status NOT IN ('pending', 'sent', 'failed') THEN
            RAISE EXCEPTION 'INVALID_STATUS_FILTER: Recipient status must be one of pending, sent, failed';
        END IF;
    END IF;

    IF p_cursor IS NOT NULL AND TRIM(p_cursor) <> '' THEN
        IF position('|' in p_cursor) = 0 THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor format must be ISO8601|uuid';
        END IF;

        IF lower(p_cursor) LIKE '%infinity%' OR lower(p_cursor) LIKE '%nan%' THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor cannot contain infinity or NaN values';
        END IF;

        BEGIN
            v_cursor_created_at := split_part(p_cursor, '|', 1)::timestamptz;
            v_cursor_id := split_part(p_cursor, '|', 2)::uuid;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor format must be ISO8601|uuid';
        END;

        IF v_cursor_created_at IS NULL OR v_cursor_id IS NULL THEN
            RAISE EXCEPTION 'INVALID_CURSOR: Cursor format must be ISO8601|uuid';
        END IF;
    END IF;

    FOR v_row IN
        SELECT r.id AS recipient_id,
               r.user_id,
               p.email AS user_email,
               p.display_name AS user_display_name,
               p.role AS user_role,
               r.status,
               r.notification_id,
               r.sent_at,
               r.error_message,
               r.created_at,
               COALESCE(n.is_read, false) AS is_read
        FROM public.notification_campaign_recipients r
        JOIN public.profiles p ON p.id = r.user_id
        LEFT JOIN public.notifications n ON n.id = r.notification_id
        WHERE r.campaign_id = p_campaign_id
          AND (p_status IS NULL OR r.status = p_status)
          AND (
              v_cursor_created_at IS NULL OR
              (r.created_at, r.id) > (v_cursor_created_at, v_cursor_id)
          )
        ORDER BY r.created_at ASC, r.id ASC
        LIMIT v_limit + 1
    LOOP
        v_count := v_count + 1;
        IF v_count <= v_limit THEN
            v_items := v_items || jsonb_build_object(
                'recipient_id', v_row.recipient_id,
                'user_id', v_row.user_id,
                'user_email', v_row.user_email,
                'user_display_name', v_row.user_display_name,
                'user_role', v_row.user_role,
                'status', v_row.status,
                'notification_id', v_row.notification_id,
                'sent_at', CASE WHEN v_row.sent_at IS NOT NULL THEN to_char(v_row.sent_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
                'is_read', v_row.is_read,
                'error_message', v_row.error_message
            );
            v_last_created_at := v_row.created_at;
            v_last_id := v_row.recipient_id;
        ELSE
            v_has_more := true;
        END IF;
    END LOOP;

    IF v_has_more AND v_last_created_at IS NOT NULL THEN
        v_next_cursor := to_char(v_last_created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|' || v_last_id::text;
    END IF;

    RETURN jsonb_build_object(
        'items', v_items,
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_list_notification_campaign_recipients(uuid, text, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_list_notification_campaign_recipients(uuid, text, integer, text) TO authenticated, service_role;


-- ============================================================================
-- 18. ADMIN RPC: ADMIN_GET_NOTIFICATION_SCHEDULER_HEALTH
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_get_notification_scheduler_health()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
    v_admin_id uuid;
    v_cron_available boolean := false;
    v_cron_job_active boolean := false;
    v_scheduler_ready boolean := false;
    v_pending_due_count integer := 0;
    v_in_flight_count integer := 0;
    v_heartbeat RECORD;
    v_health_status text := 'healthy';
BEGIN
    v_admin_id := public._assert_daily_task_admin();

    -- Check pg_cron
    IF to_regclass('cron.job') IS NOT NULL THEN
        v_cron_available := true;
        BEGIN
            EXECUTE 'SELECT EXISTS (SELECT 1 FROM cron.job WHERE jobname = ''process_due_notification_campaigns'' AND active = true)'
            INTO v_cron_job_active;
        EXCEPTION WHEN OTHERS THEN
            v_cron_job_active := false;
        END;
    END IF;

    -- Count pending due campaigns
    SELECT COUNT(*) INTO v_pending_due_count
    FROM public.notification_campaigns
    WHERE status IN ('queued', 'sending')
      AND scheduled_for <= clock_timestamp();

    -- Count in flight
    SELECT COUNT(*) INTO v_in_flight_count
    FROM public.notification_campaigns
    WHERE status = 'sending';

    -- Retrieve last heartbeat
    SELECT * INTO v_heartbeat
    FROM public.notification_worker_heartbeats
    WHERE worker_key = 'service_cron';

    -- Evaluate readiness status
    IF v_cron_available AND v_cron_job_active THEN
        v_scheduler_ready := true;
        v_health_status := 'healthy';
    ELSIF v_heartbeat.last_run_at IS NOT NULL AND v_heartbeat.last_run_at >= (clock_timestamp() - interval '5 minutes') THEN
        v_scheduler_ready := true;
        v_health_status := 'healthy_daemon_active';
    ELSE
        v_scheduler_ready := false;
        v_health_status := 'scheduler_manual_fallback_required';
    END IF;

    IF v_pending_due_count > 10 THEN
        v_health_status := 'degraded_backlog_accumulating';
    ELSIF v_pending_due_count = 0 AND v_in_flight_count = 0 AND v_scheduler_ready THEN
        v_health_status := 'idle';
    END IF;

    RETURN jsonb_build_object(
        'pg_cron_available', v_cron_available,
        'pg_cron_job_active', v_cron_job_active,
        'scheduler_ready', v_scheduler_ready,
        'pending_due_campaigns_count', v_pending_due_count,
        'in_flight_campaigns_count', v_in_flight_count,
        'last_worker_run_at', CASE WHEN v_heartbeat.last_run_at IS NOT NULL THEN to_char(v_heartbeat.last_run_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
        'lifetime_campaigns_processed', COALESCE(v_heartbeat.campaigns_processed, 0),
        'lifetime_notifications_sent', COALESCE(v_heartbeat.notifications_sent, 0),
        'health_status', v_health_status
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_get_notification_scheduler_health() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_notification_scheduler_health() TO authenticated, service_role;


-- ============================================================================
-- 19. CONDITIONAL PG_CRON REGISTRATION WITH SAFE FALLBACK
-- ============================================================================

DO $cron_setup$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'pg_cron'
    ) OR EXISTS (
        SELECT 1 FROM pg_available_extensions WHERE name = 'pg_cron'
    ) THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS pg_cron;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'pg_cron extension creation skipped: %', SQLERRM;
        END;

        IF to_regclass('cron.job') IS NOT NULL THEN
            IF NOT EXISTS (
                SELECT 1 FROM cron.job WHERE jobname = 'process_due_notification_campaigns'
            ) THEN
                PERFORM cron.schedule(
                    'process_due_notification_campaigns',
                    '* * * * *',
                    'SELECT public.service_process_notification_campaigns(500, 10);'
                );
            END IF;
        END IF;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pg_cron conditional scheduling skipped (environment without pg_cron): %', SQLERRM;
END;
$cron_setup$;

COMMIT;
