-- ============================================================================
-- MIGRATION 085: EGRESS OPTIMIZATION & VERSIONED CONDITIONAL SYNC V2
-- ============================================================================
-- Scope:
-- 0. Operational feature flags in public.app_configs:
--    - egress_sync_home_feed_v2_enabled
--    - egress_sync_dashboard_v2_enabled
--    - egress_sync_campaign_detail_v2_enabled
--    - egress_candidates_v2_enabled
-- 1. Helper function public._sync_block(p_key text, p_value jsonb, p_tokens jsonb)
--    Computes stable server fingerprint using builtin sha256 over canonical
--    UTF-8 text representation, and returns either {token, changed: false}
--    or {token, changed: true, value: p_value}.
-- 2. get_home_feed_sync_v2(p_tokens jsonb DEFAULT '{}'::jsonb)
--    Wraps public.get_home_feed(), preserving every single field, order, and
--    anon vs authenticated behavior without duplicating business SQL.
-- 3. get_my_dashboard_sync_v2(p_tokens jsonb DEFAULT '{}'::jsonb, p_include_leaderboard boolean DEFAULT true)
--    Wraps public.get_my_dashboard(p_include_leaderboard), preserving daily login
--    recording side-effects and all 15 blocks.
-- 4. get_campaign_detail_sync_v2(p_campaign_id uuid, p_tokens jsonb DEFAULT '{}'::jsonb)
--    Wraps public.get_campaign_detail(p_campaign_id), preserving auto-accept
--    side-effects, permissions, and all 20 blocks.
-- 5. get_next_campaign_candidates_v2(p_current_campaign_id uuid DEFAULT NULL)
--    Returns eligible next campaign candidates with installed_count directly,
--    matching the exact candidate universe and ordering of get_home_feed.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 0. OPERATIONAL FEATURE FLAGS: public.app_configs
-- ============================================================================

ALTER TABLE public.app_configs
    ADD COLUMN IF NOT EXISTS egress_sync_home_feed_v2_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS egress_sync_dashboard_v2_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS egress_sync_campaign_detail_v2_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS egress_candidates_v2_enabled boolean NOT NULL DEFAULT false;

-- ============================================================================
-- 1. HELPER: _sync_block
-- ============================================================================

CREATE OR REPLACE FUNCTION public._sync_block(p_key text, p_value jsonb, p_tokens jsonb)
RETURNS jsonb
LANGUAGE plpgsql
IMMUTABLE
SET search_path TO 'pg_catalog', 'public'
AS $function$
declare
    v_token text;
    v_client_token text;
    v_norm_text text;
begin
    -- SQL null and JSON null normalized to canonical text 'null'
    if p_value is null or pg_catalog.jsonb_typeof(p_value) = 'null' then
        v_norm_text := 'null';
    else
        v_norm_text := p_value::text;
    end if;

    -- Builtin pg_catalog sha256 over UTF-8 encoded bytes avoids pgcrypto extension dependencies
    v_token := pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(v_norm_text, 'UTF8')), 'hex');

    if p_tokens is not null then
        v_client_token := p_tokens ->> p_key;
    else
        v_client_token := null;
    end if;

    if v_client_token is not null and v_client_token = v_token then
        return pg_catalog.jsonb_build_object(
            'token', v_token,
            'changed', false
        );
    else
        return pg_catalog.jsonb_build_object(
            'token', v_token,
            'changed', true,
            'value', p_value
        );
    end if;
end;
$function$;

REVOKE ALL ON FUNCTION public._sync_block(text, jsonb, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public._sync_block(text, jsonb, jsonb) TO authenticated, service_role;


-- ============================================================================
-- 2. get_home_feed_sync_v2
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_home_feed_sync_v2(p_tokens jsonb DEFAULT '{}'::jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_tokens jsonb := coalesce(p_tokens, '{}'::jsonb);
    v_payload jsonb;
    v_blocks jsonb := '{}'::jsonb;
    v_key text;
    v_val jsonb;
begin
    -- Call canonical get_home_feed once
    v_payload := public.get_home_feed();

    for v_key, v_val in select key, value from jsonb_each(v_payload) loop
        v_blocks := jsonb_set(v_blocks, array[v_key], public._sync_block(v_key, v_val, v_tokens));
    end loop;

    return jsonb_build_object(
        'schema_version', 2,
        'business_date', to_char(v_today, 'YYYY-MM-DD'),
        'blocks', v_blocks
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_home_feed_sync_v2(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_home_feed_sync_v2(jsonb) TO anon, authenticated, service_role;


-- ============================================================================
-- 3. get_my_dashboard_sync_v2
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_my_dashboard_sync_v2(
    p_tokens jsonb DEFAULT '{}'::jsonb,
    p_include_leaderboard boolean DEFAULT true
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_tokens jsonb := coalesce(p_tokens, '{}'::jsonb);
    v_payload jsonb;
    v_blocks jsonb := '{}'::jsonb;
    v_key text;
    v_val jsonb;
begin
    -- Call canonical get_my_dashboard once (preserves daily login side-effects)
    v_payload := public.get_my_dashboard(coalesce(p_include_leaderboard, true));

    for v_key, v_val in select key, value from jsonb_each(v_payload) loop
        v_blocks := jsonb_set(v_blocks, array[v_key], public._sync_block(v_key, v_val, v_tokens));
    end loop;

    return jsonb_build_object(
        'schema_version', 2,
        'business_date', to_char(v_today, 'YYYY-MM-DD'),
        'blocks', v_blocks
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_my_dashboard_sync_v2(jsonb, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_my_dashboard_sync_v2(jsonb, boolean) TO authenticated, service_role;


-- ============================================================================
-- 4. get_campaign_detail_sync_v2
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_campaign_detail_sync_v2(
    p_campaign_id uuid,
    p_tokens jsonb DEFAULT '{}'::jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_tokens jsonb := coalesce(p_tokens, '{}'::jsonb);
    v_payload jsonb;
    v_blocks jsonb := '{}'::jsonb;
    v_key text;
    v_val jsonb;
begin
    -- Call canonical get_campaign_detail once (preserves auto_accept side-effects and permissions)
    v_payload := public.get_campaign_detail(p_campaign_id);

    for v_key, v_val in select key, value from jsonb_each(v_payload) loop
        v_blocks := jsonb_set(v_blocks, array[v_key], public._sync_block(v_key, v_val, v_tokens));
    end loop;

    return jsonb_build_object(
        'schema_version', 2,
        'business_date', to_char(v_today, 'YYYY-MM-DD'),
        'blocks', v_blocks
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_detail_sync_v2(uuid, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail_sync_v2(uuid, jsonb) TO authenticated, service_role;


-- ============================================================================
-- 5. get_next_campaign_candidates_v2
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_next_campaign_candidates_v2(
    p_current_campaign_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_candidates jsonb := '[]'::jsonb;
begin
    -- Preserves exact candidate universe and ordering:
    -- 1. status = 'open'
    -- 2. id <> p_current_campaign_id (if provided)
    -- 3. owner_id <> v_uid (if caller authenticated)
    -- 4. Not tested today by v_uid (no checkin today, and no non-rejected app_test report today)
    -- Order matches get_home_feed: created_at desc
    select coalesce(jsonb_agg(jsonb_build_object(
        'campaign', public._campaign_json(c),
        'installed_count', coalesce((
            select count(*) from public.campaign_members m where m.campaign_id = c.id
        ), 0)
    ) order by c.created_at desc), '[]'::jsonb)
    into v_candidates
    from public.campaigns c
    where c.status = 'open'
      and (p_current_campaign_id is null or c.id <> p_current_campaign_id)
      and (v_uid is null or c.owner_id <> v_uid)
      and (v_uid is null or not (
          exists (select 1 from public.checkins ci
                  where ci.campaign_id = c.id and ci.user_id = v_uid and ci.checkin_date = v_today)
          or exists (select 1 from public.tester_reports tr
                     where tr.campaign_id = c.id and tr.user_id = v_uid
                       and coalesce(tr.submission_context, 'app_test') = 'app_test'
                       and tr.status <> 'rejected'
                       and coalesce(tr.report_date, (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date) = v_today)
      ));

    return v_candidates;
end;
$function$;

REVOKE ALL ON FUNCTION public.get_next_campaign_candidates_v2(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_next_campaign_candidates_v2(uuid) TO anon, authenticated, service_role;

-- Reload schema for PostgREST
NOTIFY pgrst, 'reload schema';

COMMIT;
