-- ============================================================================
-- MIGRATION 086: NEXT CAMPAIGN CANDIDATE DAILY TARGET PRIORITY
-- ============================================================================
-- Scope:
-- 1. Update public.get_next_campaign_candidates_v2(p_current_campaign_id uuid DEFAULT NULL)
--    Add additive fields to each candidate object:
--    - today_tested_count: count(distinct user_id) from checkins on campaign today (Asia/Ho_Chi_Minh)
--    - stats_date: text date 'YYYY-MM-DD' matching get_home_feed / get_campaigns_stats
-- 2. Preserves:
--    - RPC name, parameter names/types, security definer, search_path, permissions
--    - Exact candidate universe: open status, excluding current campaign, excluding owner,
--      excluding campaigns already tested today by the caller
--    - All candidates returned (including campaigns that reached daily target)
--    - Backward compatibility for older Android clients ignoring unknown JSON fields
-- ============================================================================

BEGIN;

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
        ), 0),
        'today_tested_count', coalesce((
            select count(distinct ci.user_id) from public.checkins ci
            where ci.campaign_id = c.id and ci.checkin_date = v_today
        ), 0),
        'stats_date', v_today::text
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
