-- ============================================================================
-- Migration 081: Admin Flow Audit Fixes
--
-- 1. admin_list_notification_campaigns: include selected_user_ids, cancelled_at,
--    cancelled_by, cancel_reason to match _notif_campaign_to_jsonb and prevent
--    recipient loss when editing drafts from history.
-- 2. admin_preview_notification_campaign: honor live composer overrides when
--    p_campaign_id is provided alongside p_audience_type, while preserving
--    saved-campaign preview when called with p_campaign_id only.
-- 3. _impl_admin_delete_campaign: automatically close open missions and refund
--    unsettled mission escrow when no pending mission reports/jobs exist before
--    deleting the campaign.
-- 4. get_campaign_detail, get_campaign_reports_for_owner, and
--    admin_get_tester_reports_queue: include mission_reward_mode in report JSON;
--    enforce unlocked caller check in get_campaign_reports_for_owner,
--    request_report_media_access, and verify_media_access_grant_internal.
-- 5. audit_business_policy & admin_cancel_business_policy: avoid duplicate
--    UPDATE + DELETE audit rows in business_policy_changes upon cancellation.
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. Notification Campaigns: Full DTO in List & Live Composer Preview Support
-- ----------------------------------------------------------------------------

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
                'selected_user_ids', to_jsonb(COALESCE(v_row.selected_user_ids, '{}'::uuid[])),
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
                'cancelled_at', CASE WHEN v_row.cancelled_at IS NOT NULL THEN to_char(v_row.cancelled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') ELSE NULL END,
                'cancelled_by', v_row.cancelled_by,
                'cancel_reason', v_row.cancel_reason,
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
    v_has_live_overrides boolean := (p_audience_type IS NOT NULL);
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

        IF v_has_live_overrides THEN
            v_aud := p_audience_type;
            v_role := CASE WHEN p_audience_type = 'role' THEN p_target_role ELSE NULL END;
            IF p_audience_type = 'selected_users' AND p_selected_user_ids IS NOT NULL THEN
                SELECT COALESCE(array_agg(DISTINCT u ORDER BY u), '{}'::uuid[])
                INTO v_selected
                FROM unnest(p_selected_user_ids) AS u
                WHERE u IS NOT NULL;
            END IF;
            v_locale := COALESCE(NULLIF(TRIM(p_default_locale), ''), v_camp.default_locale);
            v_title_vi := COALESCE(p_title_vi, v_camp.title_vi);
            v_title_en := COALESCE(p_title_en, v_camp.title_en);
            v_body_vi := COALESCE(p_body_vi, v_camp.body_vi);
            v_body_en := COALESCE(p_body_en, v_camp.body_en);
        ELSE
            v_aud := v_camp.audience_type;
            v_role := v_camp.target_role;
            v_selected := COALESCE(v_camp.selected_user_ids, '{}'::uuid[]);
            v_locale := v_camp.default_locale;
            v_title_vi := v_camp.title_vi;
            v_title_en := v_camp.title_en;
            v_body_vi := v_camp.body_vi;
            v_body_en := v_camp.body_en;

            -- If campaign has frozen recipient snapshot and no live audience override is active, use exact snapshot count
            IF v_camp.recipient_snapshot_taken THEN
                v_count := v_camp.total_recipients;
            END IF;
        END IF;
    ELSE
        v_aud := COALESCE(p_audience_type, 'all');
        v_role := p_target_role;
        IF p_selected_user_ids IS NOT NULL THEN
            SELECT COALESCE(array_agg(DISTINCT u ORDER BY u), '{}'::uuid[])
            INTO v_selected
            FROM unnest(p_selected_user_ids) AS u
            WHERE u IS NOT NULL;
        END IF;
        v_locale := COALESCE(p_default_locale, 'vi');
        v_title_vi := COALESCE(p_title_vi, '');
        v_title_en := COALESCE(p_title_en, '');
        v_body_vi := COALESCE(p_body_vi, '');
        v_body_en := COALESCE(p_body_en, '');
    END IF;

    -- Compute live audience count if not using a frozen snapshot
    IF (v_camp.id IS NULL OR NOT v_camp.recipient_snapshot_taken OR v_has_live_overrides) THEN
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


-- ----------------------------------------------------------------------------
-- 2. _impl_admin_delete_campaign: Close Missions & Refund Unsettled Escrow
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public._impl_admin_delete_campaign(
    p_campaign_id uuid,
    p_reason text DEFAULT 'Gỡ bỏ bởi Admin'::text
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
    v_app_name text;
    v_owner_id uuid;
    v_m record;
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
    where id = p_campaign_id
    for update;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Close any non-deleted missions and attempt to refund remaining escrow
    -- when no pending mission reports or settlement jobs remain.
    for v_m in
        select id, status, escrow_remaining_credits, escrow_refunded
        from public.missions
        where campaign_id = p_campaign_id
        for update
    loop
        if v_m.status <> 'deleted' then
            update public.missions
            set status = 'deleted',
                admin_closed_at = coalesce(admin_closed_at, now())
            where id = v_m.id;
        end if;

        if v_m.escrow_remaining_credits > 0 and not v_m.escrow_refunded then
            perform public._check_and_refund_mission_escrow(v_m.id);
        end if;
    end loop;

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

REVOKE ALL ON FUNCTION public._impl_admin_delete_campaign(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_admin_delete_campaign(uuid, text) TO service_role;


-- ----------------------------------------------------------------------------
-- 3. Report & Media Read APIs: Include mission_reward_mode & Lock-Aware Staff
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
        'review_tester_bonus', tr.review_tester_bonus,
        'review_owner_bonus', tr.review_owner_bonus,
        'business_policy_id', tr.business_policy_id,
        'provisional_granted_credits', tr.provisional_granted_credits,
        'provisional_clawed', tr.provisional_clawed,
        'submission_context', tr.submission_context,
        'mission_id', tr.mission_id,
        'mission_participant_id', tr.mission_participant_id,
        'mission_title', tr.mission_title,
        'mission_reward_credits', tr.mission_reward_credits,
        'mission_reward_mode', tr.mission_reward_mode,
        'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'report_date', to_char(tr.report_date, 'YYYY-MM-DD'),
        'attachments', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256
            ) order by a.position)
            from public.tester_report_attachments a
            where a.report_id = tr.id
        ), case when tr.screenshot_url is not null then jsonb_build_array(jsonb_build_object(
            'id', tr.id,
            'position', 0,
            'screenshot_url', tr.screenshot_url,
            'thumbnail_url', tr.thumbnail_url,
            'image_hash', tr.image_hash,
            'sha256', null
        )) else '[]'::jsonb end)
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


CREATE OR REPLACE FUNCTION public.get_campaign_reports_for_owner(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_locked boolean;
    v_res jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role, coalesce(is_locked, false)
    into v_role, v_locked
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_locked, false) = true then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    perform public.auto_accept_expired_reports();

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
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
                'review_tester_bonus', tr.review_tester_bonus,
                'review_owner_bonus', tr.review_owner_bonus,
                'business_policy_id', tr.business_policy_id,
                'provisional_granted_credits', tr.provisional_granted_credits,
                'provisional_clawed', tr.provisional_clawed,
                'tester_name', coalesce(p.display_name, split_part(coalesce(p.email, ''), '@', 1), 'Tester'),
                'tester_avatar', p.avatar_url,
                'tester_credits', p.credits,
                'submission_context', tr.submission_context,
                'mission_id', tr.mission_id,
                'mission_participant_id', tr.mission_participant_id,
                'mission_title', tr.mission_title,
                'mission_reward_credits', tr.mission_reward_credits,
                'mission_reward_mode', tr.mission_reward_mode,
                'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'report_date', to_char(tr.report_date, 'YYYY-MM-DD'),
                'attachments', coalesce((
                    select jsonb_agg(jsonb_build_object(
                        'id', a.id,
                        'position', a.position,
                        'screenshot_url', a.screenshot_url,
                        'thumbnail_url', a.thumbnail_url,
                        'image_hash', a.image_hash,
                        'sha256', a.sha256
                    ) order by a.position)
                    from public.tester_report_attachments a
                    where a.report_id = tr.id
                ), case when tr.screenshot_url is not null then jsonb_build_array(jsonb_build_object(
                    'id', tr.id,
                    'position', 0,
                    'screenshot_url', tr.screenshot_url,
                    'thumbnail_url', tr.thumbnail_url,
                    'image_hash', tr.image_hash,
                    'sha256', null
                )) else '[]'::jsonb end)
            )
            order by tr.created_at desc
        ),
        '[]'::jsonb
    ) into v_res
    from public.tester_reports tr
    left join public.profiles p on p.id = tr.user_id
    where tr.campaign_id = p_campaign_id;

    return v_res;
end;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_reports_for_owner(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_reports_for_owner(uuid) TO authenticated, service_role;


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
    if exists (select 1 from pg_proc where proname = 'assert_unlocked_admin' and pronamespace = 'public'::regnamespace) then
        v_caller_id := public.assert_unlocked_admin();
    else
        v_caller_id := public._assert_daily_task_admin();
    end if;
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
            tr.mission_reward_mode,
            tr.created_at,
            tr.reviewed_at,
            tr.rejection_reason,
            tr.review_tester_bonus,
            tr.review_owner_bonus,
            tr.business_policy_id,
            tr.provisional_granted_credits,
            tr.provisional_clawed,
            tr.bonus_awarded,
            to_char(tr.report_date, 'YYYY-MM-DD') as report_date,
            coalesce((
                select jsonb_agg(jsonb_build_object(
                    'id', a.id,
                    'position', a.position,
                    'screenshot_url', a.screenshot_url,
                    'thumbnail_url', a.thumbnail_url,
                    'image_hash', a.image_hash,
                    'sha256', a.sha256
                ) order by a.position)
                from public.tester_report_attachments a
                where a.report_id = tr.id
            ), case when tr.screenshot_url is not null then jsonb_build_array(jsonb_build_object(
                'id', tr.id,
                'position', 0,
                'screenshot_url', tr.screenshot_url,
                'thumbnail_url', tr.thumbnail_url,
                'image_hash', tr.image_hash,
                'sha256', null
            )) else '[]'::jsonb end) as attachments
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


CREATE OR REPLACE FUNCTION public.request_report_media_access(
    p_report_id uuid,
    p_attachment_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_report record;
    v_camp record;
    v_attachment record;
    v_is_author boolean := false;
    v_is_owner boolean := false;
    v_is_admin boolean := false;
    v_role text := null;
    v_expires_at timestamptz;
    v_expires_epoch bigint;
    v_full_token text;
    v_thumb_token text;
    v_gateway_base text;
    v_signed_full_url text;
    v_signed_thumb_url text;
    v_att_part text := '';
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select id, campaign_id, user_id, screenshot_url, thumbnail_url,
           created_at, media_expired
    into v_report
    from public.tester_reports
    where id = p_report_id;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    if v_report.media_expired = true or v_report.created_at < (now() - interval '30 days') then
        raise exception 'MEDIA_EXPIRED';
    end if;

    if p_attachment_id is not null then
        select * into v_attachment
        from public.tester_report_attachments
        where id = p_attachment_id and report_id = p_report_id;

        if v_attachment.id is null then
            raise exception 'ATTACHMENT_NOT_FOUND';
        end if;
        v_att_part := p_attachment_id::text;
    end if;

    v_is_author := (v_report.user_id = v_user_id);

    select * into v_camp from public.campaigns where id = v_report.campaign_id;
    if v_camp.id is not null and v_camp.owner_id = v_user_id then
        v_is_owner := true;
    end if;

    if exists (
        select 1 from public.profiles
        where id = v_user_id
          and lower(coalesce(role, '')) in ('admin', 'moderator')
          and coalesce(is_locked, false) = false
    ) then
        v_is_admin := true;
    end if;

    if v_is_author then
        v_role := 'author';
    elsif v_is_owner then
        v_role := 'owner';
    elsif v_is_admin then
        v_role := 'admin';
    else
        raise exception 'FORBIDDEN';
    end if;

    v_expires_at := now() + interval '15 minutes';
    v_expires_epoch := extract(epoch from v_expires_at)::bigint;

    if p_attachment_id is not null then
        v_full_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s:%s', p_report_id, v_att_part, v_user_id, v_role, 'full', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );

        v_thumb_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s:%s', p_report_id, v_att_part, v_user_id, v_role, 'thumb', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    else
        v_full_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s', p_report_id, v_user_id, v_role, 'full', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );

        v_thumb_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s', p_report_id, v_user_id, v_role, 'thumb', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    end if;

    v_gateway_base := nullif(current_setting('app.settings.media_gateway_base_url', true), '');
    if v_gateway_base is null or length(trim(v_gateway_base)) = 0 then
        raise exception 'MEDIA_GATEWAY_BASE_URL_NOT_CONFIGURED';
    end if;

    if p_attachment_id is not null then
        v_signed_full_url := format('%s?report_id=%s&attachment_id=%s&variant=full&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, p_attachment_id, v_expires_epoch, v_full_token);
        v_signed_thumb_url := format('%s?report_id=%s&attachment_id=%s&variant=thumb&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, p_attachment_id, v_expires_epoch, v_thumb_token);
    else
        v_signed_full_url := format('%s?report_id=%s&variant=full&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, v_expires_epoch, v_full_token);
        v_signed_thumb_url := format('%s?report_id=%s&variant=thumb&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, v_expires_epoch, v_thumb_token);
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'attachment_id', p_attachment_id,
        'role', v_role,
        'screenshot_url', v_signed_full_url,
        'thumbnail_url', v_signed_thumb_url,
        'signed_full_url', v_signed_full_url,
        'signed_thumbnail_url', v_signed_thumb_url,
        'expires_at', to_char(v_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'expires_at_epoch', v_expires_epoch,
        'expires_epoch', v_expires_epoch
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.request_report_media_access(uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.request_report_media_access(uuid, uuid) TO authenticated, service_role;


CREATE OR REPLACE FUNCTION public.verify_media_access_grant_internal(
    p_report_id uuid,
    p_user_id uuid,
    p_grant_token text,
    p_variant text,
    p_expires_at_epoch bigint,
    p_attachment_id uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
declare
    v_report record;
    v_camp record;
    v_role text := null;
    v_now_epoch bigint := extract(epoch from now())::bigint;
    v_expected_sig text;
    v_att_part text := '';
begin
    if p_user_id is null or p_grant_token is null or p_expires_at_epoch is null then
        return false;
    end if;

    if v_now_epoch > p_expires_at_epoch or (p_expires_at_epoch - v_now_epoch) > 1800 then
        return false;
    end if;

    select id, campaign_id, user_id, media_expired, created_at
    into v_report
    from public.tester_reports
    where id = p_report_id;

    if v_report.id is null or v_report.media_expired = true or v_report.created_at < (now() - interval '30 days') then
        return false;
    end if;

    if p_attachment_id is not null then
        if not exists (
            select 1 from public.tester_report_attachments
            where id = p_attachment_id and report_id = p_report_id
        ) then
            return false;
        end if;
        v_att_part := p_attachment_id::text;
    end if;

    if v_report.user_id = p_user_id then
        v_role := 'author';
    else
        select owner_id into v_camp from public.campaigns where id = v_report.campaign_id;
        if v_camp.owner_id = p_user_id then
            v_role := 'owner';
        elsif exists (
            select 1 from public.profiles
            where id = p_user_id
              and lower(coalesce(role, '')) in ('admin', 'moderator')
              and coalesce(is_locked, false) = false
        ) then
            v_role := 'admin';
        else
            return false;
        end if;
    end if;

    if p_attachment_id is not null then
        v_expected_sig := encode(
            hmac(
                format('%s:%s:%s:%s:%s:%s', p_report_id, v_att_part, p_user_id, v_role, coalesce(p_variant, 'full'), p_expires_at_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    else
        v_expected_sig := encode(
            hmac(
                format('%s:%s:%s:%s:%s', p_report_id, p_user_id, v_role, coalesce(p_variant, 'full'), p_expires_at_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    end if;

    return (v_expected_sig = p_grant_token);
exception
    when others then
        return false;
end;
$function$;

REVOKE ALL ON FUNCTION public.verify_media_access_grant_internal(uuid, uuid, text, text, bigint, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.verify_media_access_grant_internal(uuid, uuid, text, text, bigint, uuid) TO service_role;


-- ----------------------------------------------------------------------------
-- 4. Business Policies: Eliminate Duplicate UPDATE + DELETE Audit Entry on Cancel
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.audit_business_policy()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_actor uuid := auth.uid();
    v_cancel_note text;
begin
    if tg_op = 'INSERT' then
        insert into public.business_policy_changes (
            policy_id, operation, old_row, new_row, operator_note,
            session_role, invoked_role, changed_at, actor_id
        ) values (
            new.id, 'INSERT', null, to_jsonb(new), new.operator_note,
            session_user, current_user, clock_timestamp(), v_actor
        );
        return new;
    elsif tg_op = 'UPDATE' then
        insert into public.business_policy_changes (
            policy_id, operation, old_row, new_row, operator_note,
            session_role, invoked_role, changed_at, actor_id
        ) values (
            new.id, 'UPDATE', to_jsonb(old), to_jsonb(new), new.operator_note,
            session_user, current_user, clock_timestamp(), v_actor
        );
        return new;
    elsif tg_op = 'DELETE' then
        v_cancel_note := coalesce(
            nullif(current_setting('app.business_policy_cancelling_note', true), ''),
            old.operator_note
        );
        insert into public.business_policy_changes (
            policy_id, operation, old_row, new_row, operator_note,
            session_role, invoked_role, changed_at, actor_id
        ) values (
            old.id, 'DELETE', to_jsonb(old), null, v_cancel_note,
            session_user, current_user, clock_timestamp(), v_actor
        );
        return old;
    end if;
    return null;
end;
$$;


CREATE OR REPLACE FUNCTION public.admin_cancel_business_policy(
    p_effective_from date,
    p_operator_note text,
    p_expected_revision text,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_actor uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_current_rev text;
    v_payload_hash text;
    v_existing_req record;
    v_old_row jsonb;
    v_result jsonb;
begin
    -- 1. Auth check first
    v_actor := public._assert_daily_task_admin();

    -- 2. Request replay check BEFORE CAS
    if p_request_id is not null then
        v_payload_hash := md5(jsonb_build_object(
            'effective_from', to_char(p_effective_from, 'YYYY-MM-DD'),
            'operator_note', btrim(coalesce(p_operator_note, ''))
        )::text);

        select * into v_existing_req
        from public.business_policy_admin_requests
        where actor_id = v_actor and operation = 'cancel' and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash <> v_payload_hash then
                raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với nội dung khác';
            end if;
            return v_existing_req.result;
        end if;
    end if;

    perform pg_advisory_xact_lock(hashtextextended('business_policy_schedule', 0));

    -- Re-check request ledger after lock
    if p_request_id is not null then
        select * into v_existing_req
        from public.business_policy_admin_requests
        where actor_id = v_actor and operation = 'cancel' and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash <> v_payload_hash then
                raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với nội dung khác';
            end if;
            return v_existing_req.result;
        end if;
    end if;

    -- 3. CAS revision check
    if p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'BUSINESS_POLICY_REVISION_REQUIRED';
    end if;

    v_current_rev := public._get_business_policy_revision();
    if v_current_rev is distinct from p_expected_revision then
        raise exception 'BUSINESS_POLICY_CONFLICT';
    end if;

    -- 4. Date validation (finite future day bounds)
    if p_effective_from is null or not isfinite(p_effective_from) or p_effective_from <= v_today or p_effective_from > (v_today + interval '3650 days') then
        raise exception 'BUSINESS_POLICY_FUTURE_DATE_REQUIRED';
    end if;

    if p_operator_note is null or length(btrim(p_operator_note)) not between 1 and 1000 then
        raise exception 'BUSINESS_POLICY_NOTE_REQUIRED';
    end if;

    select to_jsonb(p) into v_old_row
    from public.business_policies p
    where p.effective_from = p_effective_from;

    if v_old_row is null then
        raise exception 'BUSINESS_POLICY_NOT_FOUND';
    end if;

    -- Pass cancellation note via transaction-local setting so DELETE trigger records a single DELETE audit entry
    perform set_config('app.business_policy_cancelling_note', btrim(p_operator_note), true);

    delete from public.business_policies
    where effective_from = p_effective_from;

    perform set_config('app.business_policy_cancelling_note', '', true);

    -- Record in 070 common admin audit table if function exists
    if exists (select 1 from pg_proc where proname = 'record_admin_action_event' and pronamespace = 'public'::regnamespace) then
        execute 'select public.record_admin_action_event($1, $2, $3, $4, $5, $6::jsonb, $7::jsonb, $8)'
        using v_actor, 'cancel_business_policy', 'business_policy', to_char(p_effective_from, 'YYYY-MM-DD'), p_operator_note, v_old_row, null::jsonb, p_request_id;
    end if;

    v_result := public.admin_get_business_policy_config();

    if p_request_id is not null then
        insert into public.business_policy_admin_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_actor, 'cancel', v_payload_hash, v_result, clock_timestamp()
        ) on conflict (actor_id, operation, id) do nothing;
    end if;

    return v_result;
end;
$$;

REVOKE ALL ON FUNCTION public.admin_cancel_business_policy(date, text, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_cancel_business_policy(date, text, text, uuid) TO authenticated, service_role;

COMMIT;
