-- ============================================================================
-- MIGRATION 102: PLAY PRODUCTION DETECTION (SERVER-SIDE STATE, HYSTERESIS & ADMIN)
-- ============================================================================
-- Scope:
-- 1. Additive Play Store detection columns on public.campaigns + queue index.
-- 2. Extend trg_campaigns_guard_financial_snapshots() to block direct client
--    INSERT/UPDATE tampering of play_* metadata columns.
-- 3. Extend public._campaign_json(campaigns) with Play detection fields
--    (automatically invalidates Egress Sync V2 block hashes & updates
--    get_home_feed*, get_my_dashboard*, get_campaign_detail*,
--    get_next_campaign_candidates_v2).
-- 4. Extend public.admin_search_campaigns(...) with additive Play fields.
-- 5. Create system_list_campaigns_for_play_check(...) (service_role only).
-- 6. Create system_record_play_store_check(...) with 2-hit >=120m hysteresis,
--    unknown preservation, lock protection, and zero campaigns.status mutation.
-- 7. Create admin_override_campaign_play_production(...) with unlocked admin
--    enforcement and admin_action_events audit logging.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. ADDITIVE COLUMNS, CONSTRAINTS & PARTIAL QUEUE INDEX ON public.campaigns
-- ============================================================================

ALTER TABLE public.campaigns
    ADD COLUMN IF NOT EXISTS play_store_state text NOT NULL DEFAULT 'unknown',
    ADD COLUMN IF NOT EXISTS play_production_detected boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS play_released_date text,
    ADD COLUMN IF NOT EXISTS play_checked_at timestamptz,
    ADD COLUMN IF NOT EXISTS play_prod_confirm_streak smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS play_last_prod_seen_at timestamptz,
    ADD COLUMN IF NOT EXISTS play_classification_locked boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS play_check_evidence jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'campaigns_play_store_state_check'
          AND conrelid = 'public.campaigns'::regclass
    ) THEN
        ALTER TABLE public.campaigns
            ADD CONSTRAINT campaigns_play_store_state_check
            CHECK (play_store_state IN ('unknown', 'not_public', 'pre_registration', 'open_testing', 'production'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'campaigns_play_prod_confirm_streak_check'
          AND conrelid = 'public.campaigns'::regclass
    ) THEN
        ALTER TABLE public.campaigns
            ADD CONSTRAINT campaigns_play_prod_confirm_streak_check
            CHECK (play_prod_confirm_streak >= 0);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_campaigns_play_check_queue
    ON public.campaigns (play_checked_at ASC NULLS FIRST, created_at ASC)
    WHERE status = 'open' AND play_classification_locked = false;


-- ============================================================================
-- 2. GUARD TRIGGER: PROTECT play_* METADATA AGAINST DIRECT CLIENT WRITES
-- ============================================================================

CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_financial_snapshots()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO ''
AS $function$
begin
    if current_user not in ('authenticated', 'anon', 'public') then
        perform set_config('app.campaign_client_direct_write', 'false', true);
        return new;
    end if;

    perform set_config('app.campaign_client_direct_write', 'true', true);

    if tg_op = 'INSERT' then
        if coalesce(new.slot_1_fee_paid, 0) <> 0
           or coalesce(new.target_fee_paid, 0) <> 0
           or new.creation_policy_id is not null
           or coalesce(new.status, 'open') <> 'open'
           or coalesce(new.tip_credits, 0) <> 0
           or coalesce(new.is_boosted, false) <> false
           or new.tester_bonus_round_id is not null
           or coalesce(new.tester_bonus_credits, 0) <> 0
           or coalesce(new.tester_bonus_remaining_slots, 0) <> 0
           or coalesce(new.tester_bonus_paid_count, 0) <> 0
           or new.tester_bonus_status is not null
        then
            raise exception 'PERMISSION_DENIED';
        end if;

        if coalesce(new.play_store_state, 'unknown') <> 'unknown'
           or coalesce(new.play_production_detected, false) <> false
           or new.play_released_date is not null
           or new.play_checked_at is not null
           or coalesce(new.play_prod_confirm_streak, 0) <> 0
           or new.play_last_prod_seen_at is not null
           or coalesce(new.play_classification_locked, false) <> false
           or new.play_check_evidence is not null
        then
            raise exception 'PERMISSION_DENIED: PLAY_DETECTION_METADATA_IMMUTABLE';
        end if;

        return new;
    end if;

    if new.slot_1_fee_paid is distinct from old.slot_1_fee_paid
       or new.target_fee_paid is distinct from old.target_fee_paid
       or new.creation_policy_id is distinct from old.creation_policy_id
    then
        raise exception 'PERMISSION_DENIED: FINANCIAL_SNAPSHOT_IMMUTABLE';
    end if;

    if new.slot_number is distinct from old.slot_number then
        raise exception 'PERMISSION_DENIED: SLOT_NUMBER_IMMUTABLE';
    end if;

    if new.target_testers is distinct from old.target_testers then
        raise exception 'PERMISSION_DENIED: TARGET_TESTERS_IMMUTABLE';
    end if;

    if new.package_name is distinct from old.package_name then
        raise exception 'PERMISSION_DENIED: PACKAGE_NAME_IMMUTABLE';
    end if;

    if new.owner_id is distinct from old.owner_id then
        raise exception 'PERMISSION_DENIED: OWNER_ID_IMMUTABLE';
    end if;

    if new.start_date is distinct from old.start_date
       or new.target_days is distinct from old.target_days
       or new.auto_accept_hours is distinct from old.auto_accept_hours
       or new.is_boosted is distinct from old.is_boosted
       or new.tip_credits is distinct from old.tip_credits
    then
        raise exception 'PERMISSION_DENIED: CAMPAIGN_CORE_CONFIG_IMMUTABLE';
    end if;

    -- Protect tester bonus summary columns against direct client tampering
    if new.tester_bonus_round_id is distinct from old.tester_bonus_round_id
       or new.tester_bonus_credits is distinct from old.tester_bonus_credits
       or new.tester_bonus_remaining_slots is distinct from old.tester_bonus_remaining_slots
       or new.tester_bonus_paid_count is distinct from old.tester_bonus_paid_count
       or new.tester_bonus_status is distinct from old.tester_bonus_status
    then
        raise exception 'PERMISSION_DENIED: TESTER_BONUS_SUMMARY_IMMUTABLE';
    end if;

    -- Protect Play Production detection metadata against direct client tampering
    if new.play_store_state is distinct from old.play_store_state
       or new.play_production_detected is distinct from old.play_production_detected
       or new.play_released_date is distinct from old.play_released_date
       or new.play_checked_at is distinct from old.play_checked_at
       or new.play_prod_confirm_streak is distinct from old.play_prod_confirm_streak
       or new.play_last_prod_seen_at is distinct from old.play_last_prod_seen_at
       or new.play_classification_locked is distinct from old.play_classification_locked
       or new.play_check_evidence is distinct from old.play_check_evidence
    then
        raise exception 'PERMISSION_DENIED: PLAY_DETECTION_METADATA_IMMUTABLE';
    end if;

    return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_financial_snapshots ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_financial_snapshots
    BEFORE INSERT OR UPDATE ON public.campaigns
    FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_financial_snapshots();

REVOKE ALL ON FUNCTION public.trg_campaigns_guard_financial_snapshots() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.trg_campaigns_guard_financial_snapshots() TO PUBLIC, anon, authenticated, service_role;


-- ============================================================================
-- 3. CAMPAIGN JSON PROJECTION EXTENSION
-- ============================================================================

CREATE OR REPLACE FUNCTION public._campaign_json(c campaigns)
RETURNS jsonb
LANGUAGE sql
STABLE
AS $function$
    select jsonb_build_object(
        'id', c.id,
        'owner_id', c.owner_id,
        'app_name', c.app_name,
        'package_name', c.package_name,
        'play_url', c.play_url,
        'testing_url', c.testing_url,
        'description', c.description,
        'target_testers', c.target_testers,
        'target_days', c.target_days,
        'start_date', to_char(c.start_date, 'YYYY-MM-DD'),
        'status', c.status,
        'icon_url', c.icon_url,
        'test_username', c.test_username,
        'test_password', c.test_password,
        'tip_credits', c.tip_credits,
        'is_boosted', c.is_boosted,
        'slot_number', c.slot_number,
        'paused_reason', c.paused_reason,
        'admin_note', c.admin_note,
        'completed_at', to_char(c.completed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(c.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_business_date', to_char(c.push_business_date, 'YYYY-MM-DD'),
        'push_expires_at', to_char(c.push_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_order', c.push_order,
        'tester_bonus_round_id', c.tester_bonus_round_id,
        'tester_bonus_credits', coalesce(c.tester_bonus_credits, 0),
        'tester_bonus_remaining_slots', coalesce(c.tester_bonus_remaining_slots, 0),
        'tester_bonus_paid_count', coalesce(c.tester_bonus_paid_count, 0),
        'tester_bonus_status', c.tester_bonus_status,
        'play_store_state', c.play_store_state,
        'play_production_detected', c.play_production_detected,
        'play_released_date', c.play_released_date,
        'play_checked_at', to_char(c.play_checked_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'play_classification_locked', c.play_classification_locked
    );
$function$;

REVOKE ALL ON FUNCTION public._campaign_json(campaigns) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public._campaign_json(campaigns) TO anon, authenticated, service_role;


-- ============================================================================
-- 4. ADMIN SEARCH CAMPAIGNS PROJECTION EXTENSION
-- ============================================================================

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
            (select count(*) from public.tester_reports tr where tr.campaign_id = c.id and tr.status = 'pending') as pending_reports_count,
            c.play_store_state,
            c.play_production_detected,
            c.play_released_date,
            c.play_classification_locked
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


-- ============================================================================
-- 5. SYSTEM QUEUE RPC: LIST CAMPAIGNS FOR PLAY PRODUCTION CHECK
-- ============================================================================

CREATE OR REPLACE FUNCTION public.system_list_campaigns_for_play_check(
    p_limit integer DEFAULT 25,
    p_recheck_hours integer DEFAULT 6,
    p_reverify_prod_hours integer DEFAULT 24
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_now timestamptz := clock_timestamp();
    v_limit integer := greatest(1, least(coalesce(p_limit, 25), 100));
    v_recheck_hours integer := greatest(0, coalesce(p_recheck_hours, 6));
    v_reverify_prod_hours integer := greatest(0, coalesce(p_reverify_prod_hours, 24));
    v_items jsonb := '[]'::jsonb;
begin
    with candidates as (
        select
            c.id,
            c.app_name,
            c.package_name,
            c.play_url,
            c.play_store_state,
            c.play_production_detected,
            c.play_prod_confirm_streak,
            c.play_last_prod_seen_at,
            c.play_checked_at,
            c.created_at,
            case
                when c.play_checked_at is null then 1
                when c.play_production_detected = false
                     and c.play_prod_confirm_streak >= 1
                     and c.play_last_prod_seen_at is not null
                     and c.play_last_prod_seen_at <= (v_now - interval '2 hours') then 2
                when c.play_production_detected = false
                     and c.play_checked_at <= (v_now - make_interval(hours => v_recheck_hours)) then 3
                when c.play_production_detected = true
                     and c.play_checked_at <= (v_now - make_interval(hours => v_reverify_prod_hours)) then 4
                else null
            end as priority_bucket
        from public.campaigns c
        where c.status = 'open'
          and c.play_classification_locked = false
          and nullif(btrim(c.package_name), '') is not null
    ),
    queued as (
        select
            id,
            app_name,
            package_name,
            play_url,
            play_store_state,
            play_production_detected,
            play_prod_confirm_streak,
            play_last_prod_seen_at,
            play_checked_at,
            created_at,
            priority_bucket
        from candidates
        where priority_bucket is not null
        order by priority_bucket asc, play_checked_at asc nulls first, created_at asc
        limit v_limit
    )
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', q.id,
        'app_name', q.app_name,
        'package_name', q.package_name,
        'play_url', q.play_url,
        'play_store_state', q.play_store_state,
        'play_production_detected', q.play_production_detected,
        'play_prod_confirm_streak', q.play_prod_confirm_streak,
        'play_last_prod_seen_at', to_char(q.play_last_prod_seen_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'play_checked_at', to_char(q.play_checked_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'priority_bucket', q.priority_bucket
    ) order by q.priority_bucket asc, q.play_checked_at asc nulls first, q.created_at asc), '[]'::jsonb)
    into v_items
    from queued q;

    return v_items;
end;
$function$;

REVOKE ALL ON FUNCTION public.system_list_campaigns_for_play_check(integer, integer, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.system_list_campaigns_for_play_check(integer, integer, integer) TO service_role;


-- ============================================================================
-- 6. SYSTEM RECORD RPC: HYSTERESIS & STATE TRANSITION FOR PLAY STORE CHECKS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.system_record_play_store_check(
    p_campaign_id uuid,
    p_detected_state text,
    p_released_date text DEFAULT NULL,
    p_evidence jsonb DEFAULT '{}'::jsonb,
    p_min_confirm_gap_minutes integer DEFAULT 120,
    p_required_streak integer DEFAULT 2
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_now timestamptz := clock_timestamp();
    v_gap_minutes integer := greatest(0, coalesce(p_min_confirm_gap_minutes, 120));
    v_required_streak integer := greatest(1, coalesce(p_required_streak, 2));
    v_clean_released text := nullif(btrim(p_released_date), '');
    v_evidence jsonb := coalesce(p_evidence, '{}'::jsonb);
    v_camp public.campaigns%rowtype;
    v_new_state text;
    v_new_detected boolean;
    v_new_streak smallint;
    v_new_last_seen timestamptz;
    v_new_released text;
begin
    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    if p_detected_state is null
       or p_detected_state not in ('unknown', 'not_public', 'pre_registration', 'open_testing', 'production')
    then
        raise exception 'INVALID_PLAY_STORE_STATE';
    end if;

    select *
    into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if not found then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Locked classification: record check timestamp & evidence only.
    if v_camp.play_classification_locked = true then
        update public.campaigns
        set play_checked_at = v_now,
            play_check_evidence = v_evidence
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'locked', true,
            'updated_classification', false,
            'play_store_state', v_camp.play_store_state,
            'play_production_detected', v_camp.play_production_detected,
            'play_prod_confirm_streak', v_camp.play_prod_confirm_streak,
            'play_released_date', v_camp.play_released_date,
            'play_classification_locked', v_camp.play_classification_locked,
            'play_checked_at', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        );
    end if;

    -- Transient / ambiguous result: preserve streak, state, and detected flag.
    if p_detected_state = 'unknown' then
        update public.campaigns
        set play_checked_at = v_now,
            play_check_evidence = v_evidence
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'locked', false,
            'updated_classification', false,
            'play_store_state', v_camp.play_store_state,
            'play_production_detected', v_camp.play_production_detected,
            'play_prod_confirm_streak', v_camp.play_prod_confirm_streak,
            'play_released_date', v_camp.play_released_date,
            'play_classification_locked', v_camp.play_classification_locked,
            'play_checked_at', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        );
    end if;

    -- Production hit: enforce hysteresis (2 consecutive hits >= 120 minutes apart).
    if p_detected_state = 'production' then
        v_new_released := coalesce(v_clean_released, v_camp.play_released_date);

        if coalesce(v_camp.play_prod_confirm_streak, 0) <= 0 then
            v_new_streak := 1;
            v_new_last_seen := v_now;
        elsif v_camp.play_last_prod_seen_at is null
              or v_now >= (v_camp.play_last_prod_seen_at + make_interval(mins => v_gap_minutes))
        then
            v_new_streak := least(32767, v_camp.play_prod_confirm_streak + 1)::smallint;
            v_new_last_seen := v_now;
        else
            -- Elapsed < gap: do NOT increment streak and do NOT reset timer.
            v_new_streak := v_camp.play_prod_confirm_streak;
            v_new_last_seen := v_camp.play_last_prod_seen_at;
        end if;

        if v_new_streak >= v_required_streak then
            v_new_detected := true;
            v_new_state := 'production';
        else
            v_new_detected := coalesce(v_camp.play_production_detected, false);
            v_new_state := v_camp.play_store_state;
        end if;

        update public.campaigns
        set play_store_state = v_new_state,
            play_production_detected = v_new_detected,
            play_released_date = v_new_released,
            play_checked_at = v_now,
            play_prod_confirm_streak = v_new_streak,
            play_last_prod_seen_at = v_new_last_seen,
            play_check_evidence = v_evidence
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'locked', false,
            'updated_classification', true,
            'play_store_state', v_new_state,
            'play_production_detected', v_new_detected,
            'play_prod_confirm_streak', v_new_streak,
            'play_released_date', v_new_released,
            'play_classification_locked', false,
            'play_checked_at', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        );
    end if;

    -- Definitive non-production state ('not_public', 'pre_registration', 'open_testing'):
    -- reset streak and clear detected flag, never mutate campaigns.status.
    v_new_state := p_detected_state;
    v_new_detected := false;
    v_new_streak := 0;
    v_new_last_seen := null;
    v_new_released := v_clean_released;

    update public.campaigns
    set play_store_state = v_new_state,
        play_production_detected = v_new_detected,
        play_released_date = v_new_released,
        play_checked_at = v_now,
        play_prod_confirm_streak = v_new_streak,
        play_last_prod_seen_at = v_new_last_seen,
        play_check_evidence = v_evidence
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'locked', false,
        'updated_classification', true,
        'play_store_state', v_new_state,
        'play_production_detected', v_new_detected,
        'play_prod_confirm_streak', v_new_streak,
        'play_released_date', v_new_released,
        'play_classification_locked', false,
        'play_checked_at', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.system_record_play_store_check(uuid, text, text, jsonb, integer, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.system_record_play_store_check(uuid, text, text, jsonb, integer, integer) TO service_role;


-- ============================================================================
-- 7. ADMIN OVERRIDE RPC: MANUAL PLAY PRODUCTION CLASSIFICATION & LOCK
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_override_campaign_play_production(
    p_campaign_id uuid,
    p_is_production boolean,
    p_lock boolean DEFAULT true,
    p_note text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_caller_role text := coalesce(
        nullif(current_setting('request.jwt.claim.role', true), ''),
        nullif(current_setting('role', true), 'none'),
        session_user
    );
    v_caller_id uuid := null;
    v_now timestamptz := clock_timestamp();
    v_camp public.campaigns%rowtype;
    v_new_detected boolean;
    v_new_state text;
    v_new_lock boolean := coalesce(p_lock, true);
    v_new_streak smallint;
    v_new_last_seen timestamptz;
    v_clean_note text := nullif(btrim(p_note), '');
begin
    if v_uid is not null then
        v_caller_id := public.assert_unlocked_admin();
    elsif v_caller_role not in ('postgres', 'service_role') then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    if p_is_production is null then
        raise exception 'IS_PRODUCTION_REQUIRED';
    end if;

    select *
    into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if not found then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    v_new_detected := p_is_production;
    v_new_state := case when p_is_production then 'production' else 'not_public' end;
    v_new_streak := case when p_is_production then greatest(coalesce(v_camp.play_prod_confirm_streak, 0), 2)::smallint else 0 end;
    v_new_last_seen := case when p_is_production then coalesce(v_camp.play_last_prod_seen_at, v_now) else null end;

    update public.campaigns
    set play_production_detected = v_new_detected,
        play_store_state = v_new_state,
        play_classification_locked = v_new_lock,
        play_prod_confirm_streak = v_new_streak,
        play_last_prod_seen_at = v_new_last_seen,
        play_checked_at = v_now,
        play_check_evidence = jsonb_build_object(
            'source', 'admin_override',
            'actor_id', v_caller_id,
            'note', v_clean_note,
            'at', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        )
    where id = p_campaign_id;

    if v_caller_id is not null then
        perform public.record_admin_action_event(
            v_caller_id,
            'override_campaign_play_production',
            'campaign',
            p_campaign_id::text,
            v_clean_note,
            jsonb_build_object(
                'play_production_detected', v_camp.play_production_detected,
                'play_store_state', v_camp.play_store_state,
                'play_classification_locked', v_camp.play_classification_locked,
                'play_prod_confirm_streak', v_camp.play_prod_confirm_streak
            ),
            jsonb_build_object(
                'play_production_detected', v_new_detected,
                'play_store_state', v_new_state,
                'play_classification_locked', v_new_lock,
                'play_prod_confirm_streak', v_new_streak
            ),
            null
        );
    end if;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'play_production_detected', v_new_detected,
        'play_store_state', v_new_state,
        'play_classification_locked', v_new_lock
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_override_campaign_play_production(uuid, boolean, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_override_campaign_play_production(uuid, boolean, boolean, text) TO authenticated, service_role;

NOTIFY pgrst, 'reload schema';

COMMIT;
