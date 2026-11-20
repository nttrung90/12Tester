-- ============================================================================
-- Migration 096: Add owner_stats (tested_count, installed_count) to get_campaign_detail
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_campaign_detail(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
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
    v_owner_tested_count int := 0;
    v_owner_installed_count int := 0;
    v_owner_stats      jsonb;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    SELECT * INTO v_camp FROM public.campaigns WHERE id = p_campaign_id;
    IF v_camp.id IS NULL THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    PERFORM public.auto_accept_expired_reports();

    v_is_actual_owner := (v_camp.owner_id = v_uid);

    v_can_manage := (
        v_is_actual_owner
        OR EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = v_uid AND lower(p.role) IN ('admin', 'moderator')
        )
    ) AND NOT EXISTS (
        SELECT 1 FROM public.profiles p
        WHERE p.id = v_uid AND coalesce(p.is_locked, false) = true
    );

    v_is_owner := v_can_manage;

    SELECT exists(
        SELECT 1 FROM public.campaign_members m
        WHERE m.campaign_id = p_campaign_id AND m.user_id = v_uid
    ) INTO v_is_member;

    IF v_is_member THEN
        SELECT joined_at INTO v_member_joined_at
        FROM public.campaign_members
        WHERE campaign_id = p_campaign_id AND user_id = v_uid;
    END IF;

    SELECT exists(
        SELECT 1 FROM public.checkins ci
        WHERE ci.campaign_id = p_campaign_id
          AND ci.user_id = v_uid
          AND ci.checkin_date = v_today
          AND coalesce(ci.note, '') NOT LIKE '%tạm tính%'
    ) INTO v_confirmed;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'user_id', t.user_id,
        'joined_at', t.joined_at,
        'days', t.days,
        'checkin_count', t.checkin_count,
        'last_checkin_date', t.last_checkin_date
    ) ORDER BY t.days DESC), '[]'::jsonb)
    INTO v_testers
    FROM (
        SELECT m.user_id,
               to_char(m.joined_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as joined_at,
               coalesce(s.days, 0) as days,
               coalesce(s.cnt, 0) as checkin_count,
               to_char(s.last_date, 'YYYY-MM-DD') as last_checkin_date
        FROM public.campaign_members m
        LEFT JOIN (
            SELECT user_id,
                   count(distinct checkin_date) as days,
                   count(*) as cnt,
                   max(checkin_date) as last_date
            FROM public.checkins
            WHERE campaign_id = p_campaign_id
            GROUP BY user_id
        ) s ON s.user_id = m.user_id
        WHERE m.campaign_id = p_campaign_id
    ) t;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'id', f.id,
        'campaign_id', f.campaign_id,
        'user_id', f.user_id,
        'rating', f.rating,
        'comment', f.comment,
        'created_at', to_char(f.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'updated_at', to_char(f.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) ORDER BY f.created_at DESC), '[]'::jsonb)
    INTO v_feedbacks
    FROM public.feedbacks f
    WHERE f.campaign_id = p_campaign_id;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
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
        'tester_bonus_awarded', coalesce(tr.tester_bonus_awarded, 0),
        'tester_bonus_round_id', tr.tester_bonus_round_id,
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
        'storage_provider', coalesce(tr.storage_provider, 'gcs'),
        'view_expires_at', to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'media_expired', (
            coalesce(tr.media_expired, false)
            OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
            OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
            OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
        ),
        'attachments', coalesce((
            SELECT jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256,
                'provider', coalesce(a.storage_provider, 'gcs'),
                'view_expires_at', to_char(a.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'created_at', to_char(a.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'media_expired', (
                    coalesce(a.media_expired, false)
                    OR (a.view_expires_at IS NOT NULL AND now() >= a.view_expires_at)
                    OR (coalesce(a.storage_provider, 'gcs') = 'supabase' AND a.created_at < (now() - interval '216 hours'))
                    OR (coalesce(a.storage_provider, 'gcs') <> 'supabase' AND a.created_at < (now() - interval '30 days'))
                )
            ) ORDER BY a.position)
            FROM public.tester_report_attachments a
            WHERE a.report_id = tr.id
        ), CASE WHEN tr.screenshot_url IS NOT NULL THEN jsonb_build_array(jsonb_build_object(
            'id', tr.id,
            'position', 0,
            'screenshot_url', tr.screenshot_url,
            'thumbnail_url', tr.thumbnail_url,
            'image_hash', tr.image_hash,
            'sha256', null,
            'provider', coalesce(tr.storage_provider, 'gcs'),
            'view_expires_at', to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'media_expired', (
                coalesce(tr.media_expired, false)
                OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
                OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
                OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
            )
        )) ELSE '[]'::jsonb END)
    ) ORDER BY tr.created_at DESC), '[]'::jsonb)
    INTO v_reports
    FROM (
        SELECT * FROM public.tester_reports
        WHERE campaign_id = p_campaign_id
          AND (v_can_manage OR user_id = v_uid)
        ORDER BY created_at DESC
        LIMIT 200
    ) tr;

    SELECT count(*),
           count(*) filter (where status = 'pending')
    INTO v_tester_reports_count, v_pending_reports_count
    FROM public.tester_reports
    WHERE campaign_id = p_campaign_id
      AND (v_can_manage OR user_id = v_uid);

    SELECT coalesce(jsonb_object_agg(t.id::text, public._profile_json(p)), '{}'::jsonb)
    INTO v_profiles
    FROM public.profiles p
    JOIN (
        SELECT v_camp.owner_id as id
        UNION
        SELECT m.user_id FROM public.campaign_members m WHERE m.campaign_id = p_campaign_id
        UNION
        SELECT f.user_id FROM public.feedbacks f WHERE f.campaign_id = p_campaign_id
        UNION
        SELECT tr.user_id FROM public.tester_reports tr
        WHERE tr.campaign_id = p_campaign_id AND (v_can_manage OR tr.user_id = v_uid)
    ) t ON t.id = p.id;

    SELECT count(distinct user_id) INTO v_today_tested
    FROM public.checkins
    WHERE campaign_id = p_campaign_id AND checkin_date = v_today;

    SELECT count(*), coalesce(round(avg(rating)::numeric, 1), 0.0)
    INTO v_reports_count, v_avg
    FROM public.feedbacks
    WHERE campaign_id = p_campaign_id;

    SELECT coalesce(jsonb_agg(to_char(ci.checkin_date, 'YYYY-MM-DD') ORDER BY ci.checkin_date ASC), '[]'::jsonb)
    INTO v_my_dates
    FROM public.checkins ci
    WHERE ci.campaign_id = p_campaign_id AND ci.user_id = v_uid;

    v_campaign := row_to_json(v_camp);

    SELECT public._profile_json(p) INTO v_my_profile
    FROM public.profiles p WHERE p.id = v_uid;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) ORDER BY w.created_at DESC), '[]'::jsonb)
    INTO v_unread
    FROM public.user_warnings w
    WHERE w.user_id = v_uid AND w.acknowledged = false
      AND NOT EXISTS (
          SELECT 1 FROM public.user_warning_revocations r
          WHERE r.warning_id = w.id
      );

    SELECT count(*) INTO v_weekly
    FROM public.user_warnings w
    WHERE w.user_id = v_uid
      AND (w.created_at at time zone 'Asia/Ho_Chi_Minh')::date >= (v_today - 6)
      AND NOT EXISTS (
          SELECT 1 FROM public.user_warning_revocations r
          WHERE r.warning_id = w.id
      );

    SELECT * INTO v_rel
    FROM public.app_update_releases
    WHERE campaign_id = p_campaign_id AND status IN ('active', 'exhausted')
    ORDER BY published_at DESC, target_version_code DESC
    LIMIT 1;

    IF v_rel.id IS NOT NULL THEN
        IF v_is_actual_owner THEN
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
        ELSE
            IF v_rel.paid_count < v_rel.max_claims AND v_rel.remaining_credits >= v_rel.reward_per_claim THEN
                SELECT * INTO v_claim
                FROM public.app_update_claims
                WHERE release_id = v_rel.id AND tester_id = v_uid;

                IF NOT v_is_member THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'not_member';
                ELSIF v_member_joined_at > v_rel.published_at THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'joined_after_release';
                ELSIF v_camp.status = 'paused' THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_paused';
                ELSIF v_camp.status <> 'open' THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_not_open';
                ELSIF EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = v_uid AND coalesce(p.is_locked, false) = true) THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'user_locked';
                END IF;

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
                    'has_claimed', (v_claim.id IS NOT NULL),
                    'is_eligible', v_is_eligible,
                    'ineligibility_reason', v_ineligible_reason,
                    'claim_receipt', CASE WHEN v_claim.id IS NOT NULL THEN jsonb_build_object(
                        'claim_id', v_claim.id,
                        'release_id', v_claim.release_id,
                        'ordinal', v_claim.ordinal,
                        'reward_credits', v_claim.reward_credits,
                        'confirmed_at', to_char(v_claim.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                    ) ELSE null END
                );
            END IF;
        END IF;
    END IF;

    IF v_camp.owner_id IS NOT NULL THEN
        SELECT count(*) INTO v_owner_tested_count
        FROM public.checkins ci
        JOIN public.campaigns c ON c.id = ci.campaign_id
        WHERE ci.user_id = v_camp.owner_id
          AND c.owner_id IS DISTINCT FROM v_camp.owner_id;

        SELECT count(*) INTO v_owner_installed_count
        FROM public.campaign_members cm
        JOIN public.campaigns c ON c.id = cm.campaign_id
        WHERE cm.user_id = v_camp.owner_id
          AND c.owner_id IS DISTINCT FROM v_camp.owner_id;
    END IF;

    v_owner_stats := jsonb_build_object(
        'tested_count', coalesce(v_owner_tested_count, 0),
        'installed_count', coalesce(v_owner_installed_count, 0)
    );

    RETURN jsonb_build_object(
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
        'app_update', v_app_update,
        'owner_stats', v_owner_stats
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail(uuid) TO authenticated, service_role;
