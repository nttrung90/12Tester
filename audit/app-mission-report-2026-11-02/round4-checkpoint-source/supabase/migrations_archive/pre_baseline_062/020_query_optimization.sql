-- ============================================================================
-- 12 Tester — Supabase migration 020: Query optimization (Phase 2 + 3)
-- - Gộp hàng loạt truy vấn nhỏ của app thành RPC batch (1 round-trip / màn hình):
--     get_home_feed        → 3 tab Trang chủ + tên chủ app + thống kê
--     get_my_dashboard     → toàn bộ dữ liệu cá nhân Trang chủ
--     get_campaign_detail  → toàn bộ dữ liệu màn chi tiết (tester & chủ app)
--     get_campaigns_stats  → thống kê nhiều campaign trong 1 lần
--     get_leaderboard_v2   → bảng xếp hạng (bỏ tải toàn bộ checkins)
-- - Thêm index phục vụ các truy vấn nóng.
-- - Lên lịch pg_cron (idempotent, tự bỏ qua nếu chưa bật extension).
-- Chạy trong Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================


-- ============================================================================
-- 0. Đồng bộ schema (idempotent — an toàn cho DB cài từ full_schema_singapore.sql)
-- ============================================================================

alter table public.campaigns add column if not exists paused_reason text;
alter table public.campaigns add column if not exists completed_at timestamptz;
alter table public.campaigns drop constraint if exists campaigns_status_check;
alter table public.campaigns add constraint campaigns_status_check
    check (status in ('open', 'closed', 'paused', 'production', 'completed'));


-- ============================================================================
-- 1. INDEX CHO CÁC TRUY VẤN NÓNG (Phase 3)
-- ============================================================================

create index if not exists idx_campaigns_status_created
    on public.campaigns(status, created_at desc);

create index if not exists idx_checkins_campaign_date
    on public.checkins(campaign_id, checkin_date);

create index if not exists idx_tester_reports_campaign_status_created
    on public.tester_reports(campaign_id, status, created_at desc);

create index if not exists idx_tester_reports_campaign_user_created
    on public.tester_reports(campaign_id, user_id, created_at desc);


-- ============================================================================
-- 2. HELPERS: chuyển row thành JSON đúng shape model phía app
-- ============================================================================

create or replace function public._campaign_json(c public.campaigns)
returns jsonb
language sql
stable
as $$
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
        'completed_at', to_char(c.completed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(c.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
$$;

create or replace function public._profile_json(p public.profiles)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', p.id,
        'email', p.email,
        'display_name', p.display_name,
        'avatar_url', p.avatar_url,
        'credits', p.credits,
        'last_active_date', to_char(p.last_active_date, 'YYYY-MM-DD'),
        'missed_streak', p.missed_streak,
        'role', p.role,
        'is_locked', p.is_locked,
        'created_at', to_char(p.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
$$;

revoke execute on function public._campaign_json(public.campaigns) from public;
revoke execute on function public._profile_json(public.profiles) from public;


-- ============================================================================
-- 3. RPC: get_home_feed — 3 tab Trang chủ trong 1 round-trip
-- ============================================================================

create or replace function public.get_home_feed()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_open jsonb := '[]'::jsonb;
    v_paused jsonb := '[]'::jsonb;
    v_completed jsonb := '[]'::jsonb;
    v_owner_names jsonb := '{}'::jsonb;
    v_stats jsonb := '{}'::jsonb;
begin
    select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
    into v_open
    from public.campaigns c
    where c.status = 'open';

    if v_uid is not null then
        select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
        into v_paused
        from public.campaigns c
        where c.status = 'paused';

        select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
        into v_completed
        from public.campaigns c
        where c.status in ('production', 'completed');
    end if;

    select coalesce(jsonb_object_agg(t.owner_id::text, t.name), '{}'::jsonb)
    into v_owner_names
    from (
        select distinct c.owner_id,
               coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Chủ app') as name
        from public.campaigns c
        left join public.profiles p on p.id = c.owner_id
        where c.status = 'open'
           or (v_uid is not null and c.status in ('paused', 'production', 'completed'))
    ) t;

    select coalesce(jsonb_object_agg(s.id::text, jsonb_build_object(
        'installed_count', coalesce(s.installed_count, 0),
        'today_tested_count', coalesce(s.today_tested_count, 0),
        'has_tested_today', coalesce(s.has_tested_today, false)
    )), '{}'::jsonb)
    into v_stats
    from (
        select c.id,
            (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed_count,
            (select count(distinct ci.user_id) from public.checkins ci
              where ci.campaign_id = c.id and ci.checkin_date = v_today) as today_tested_count,
            (v_uid is not null and (
                exists (select 1 from public.checkins ci2
                        where ci2.campaign_id = c.id and ci2.user_id = v_uid and ci2.checkin_date = v_today)
                or exists (select 1 from public.tester_reports tr
                           where tr.campaign_id = c.id and tr.user_id = v_uid
                             and tr.status <> 'rejected'
                             and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today)
            )) as has_tested_today
        from public.campaigns c
        where c.status = 'open'
           or (v_uid is not null and c.status in ('paused', 'production', 'completed'))
    ) s;

    return jsonb_build_object(
        'open', v_open,
        'paused', v_paused,
        'completed', v_completed,
        'owner_names', v_owner_names,
        'stats', v_stats
    );
end;
$$;

grant execute on function public.get_home_feed() to authenticated, anon, service_role;


-- ============================================================================
-- 4. RPC: get_my_dashboard — toàn bộ dữ liệu cá nhân Trang chủ trong 1 round-trip
-- ============================================================================

create or replace function public.get_my_dashboard()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_ago date := v_today - 6;
    v_profile jsonb;
    v_my_campaigns jsonb := '[]'::jsonb;
    v_joined jsonb := '[]'::jsonb;
    v_leaderboard jsonb := '[]'::jsonb;
    v_today_checkins int := 0;
    v_pending int := 0;
    v_unread jsonb := '[]'::jsonb;
    v_weekly int := 0;
    v_logs jsonb := '[]'::jsonb;
    v_packages jsonb := '[]'::jsonb;
    v_notifications jsonb := '[]'::jsonb;
    v_owner_stats jsonb := '{}'::jsonb;
    v_quest jsonb := null;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select public._profile_json(p) into v_profile
    from public.profiles p where p.id = v_uid;

    select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
    into v_my_campaigns
    from public.campaigns c
    where c.owner_id = v_uid;

    select coalesce(jsonb_agg(public._campaign_json(c) order by c.created_at desc), '[]'::jsonb)
    into v_joined
    from public.campaigns c
    join public.campaign_members m on m.campaign_id = c.id and m.user_id = v_uid
    where c.owner_id <> v_uid;

    -- Bảng xếp hạng: top 100 theo credits (khớp thuật toán cũ của app)
    select coalesce(jsonb_agg(jsonb_build_object(
        'userId', t.id,
        'displayName', t.name,
        'avatarUrl', t.avatar_url,
        'credits', t.credits,
        'checkinCount', t.checkin_count,
        'campaignsCount', t.campaigns_count,
        'rank', t.rn
    ) order by t.rn), '[]'::jsonb)
    into v_leaderboard
    from (
        select b.*, row_number() over (order by b.credits desc, b.checkin_count desc, b.campaigns_count desc) as rn
        from (
            select p.id,
                   coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Tester') as name,
                   p.avatar_url,
                   p.credits,
                   (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                   (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
            from public.profiles p
            order by p.credits desc
            limit 100
        ) b
    ) t;

    -- Số app đã test hôm nay: checkins + báo cáo pending/approved trong ngày
    select count(distinct campaign_id) into v_today_checkins
    from (
        select campaign_id from public.checkins
        where user_id = v_uid and checkin_date = v_today
        union
        select campaign_id from public.tester_reports
        where user_id = v_uid
          and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
          and status in ('pending', 'approved')
    ) t;

    -- Báo cáo đang chờ duyệt trên toàn bộ app của tôi
    select count(*) into v_pending
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where c.owner_id = v_uid and tr.status = 'pending';

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
        'warning_type', w.warning_type,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) order by w.created_at desc), '[]'::jsonb)
    into v_unread
    from public.user_warnings w
    where w.user_id = v_uid and w.acknowledged = false;

    select count(*) into v_weekly
    from public.user_warnings
    where user_id = v_uid and warning_date >= v_week_ago;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', l.id,
        'user_id', l.user_id,
        'amount', l.amount,
        'action', l.action,
        'note', l.note,
        'balance', l.balance,
        'created_at', to_char(l.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by l.created_at desc), '[]'::jsonb)
    into v_logs
    from (
        select * from public.credit_logs
        where user_id = v_uid
        order by created_at desc
        limit 20
    ) l;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', tp.id,
        'title', tp.title,
        'credits', tp.credits,
        'bonus_credits', tp.bonus_credits,
        'price_usd', tp.price_usd,
        'price_vnd', tp.price_vnd,
        'badge_text', tp.badge_text,
        'is_popular', tp.is_popular,
        'is_active', tp.is_active,
        'sort_order', tp.sort_order
    ) order by tp.sort_order asc), '[]'::jsonb)
    into v_packages
    from public.topup_packages tp
    where tp.is_active = true;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', n.id,
        'user_id', n.user_id,
        'title', n.title,
        'message', n.message,
        'type', n.type,
        'data', coalesce(n.data, '{}'::jsonb),
        'is_read', n.is_read,
        'created_at', to_char(n.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by n.created_at desc), '[]'::jsonb)
    into v_notifications
    from (
        select * from public.notifications
        where user_id = v_uid
        order by created_at desc
        limit 50
    ) n;

    -- Thống kê cho từng app của chủ app hiện tại
    select coalesce(jsonb_object_agg(s.campaign_id::text, jsonb_build_object(
        'installedTestersCount', s.installed,
        'testedTodayCount', s.tested_today,
        'totalReportsCount', s.total_reports,
        'pendingReportsCount', s.pending_reports,
        'averageRating', s.avg_rating
    )), '{}'::jsonb)
    into v_owner_stats
    from (
        select c.id as campaign_id,
            (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed,
            (select count(distinct u.user_id) from (
                select ci.user_id from public.checkins ci
                where ci.campaign_id = c.id and ci.checkin_date = v_today
                union
                select tr.user_id from public.tester_reports tr
                where tr.campaign_id = c.id and tr.status <> 'rejected'
                  and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
            ) u) as tested_today,
            greatest(
                (select count(*) from public.tester_reports tr2 where tr2.campaign_id = c.id),
                (select count(*) from public.checkins ci2 where ci2.campaign_id = c.id)
            ) as total_reports,
            (select count(*) from public.tester_reports tr3
              where tr3.campaign_id = c.id and tr3.status = 'pending') as pending_reports,
            (select avg(f.rating)::float8 from public.feedbacks f where f.campaign_id = c.id) as avg_rating
        from public.campaigns c
        where c.owner_id = v_uid
    ) s;

    begin
        v_quest := public.get_user_quest_summary();
    exception when others then
        v_quest := null;
    end;

    return jsonb_build_object(
        'profile', v_profile,
        'my_campaigns', v_my_campaigns,
        'joined_campaigns', v_joined,
        'leaderboard', v_leaderboard,
        'today_checkin_count', v_today_checkins,
        'pending_reports_count', v_pending,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly,
        'credit_logs', v_logs,
        'topup_packages', v_packages,
        'notifications', v_notifications,
        'owner_stats', v_owner_stats,
        'quest_summary', v_quest
    );
end;
$$;

grant execute on function public.get_my_dashboard() to authenticated, service_role;


-- ============================================================================
-- 5. RPC: get_campaign_detail — toàn bộ dữ liệu màn chi tiết trong 1 round-trip
--    (chủ app/admin nhận mọi báo cáo; tester chỉ nhận báo cáo của mình — khớp RLS)
-- ============================================================================

create or replace function public.get_campaign_detail(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_ago date := v_today - 6;
    v_camp public.campaigns%rowtype;
    v_is_owner boolean := false;
    v_is_member boolean := false;
    v_confirmed boolean := false;
    v_campaign jsonb;
    v_testers jsonb := '[]'::jsonb;
    v_feedbacks jsonb := '[]'::jsonb;
    v_reports jsonb := '[]'::jsonb;
    v_profiles jsonb := '{}'::jsonb;
    v_today_tested int := 0;
    v_reports_count int := 0;
    v_pending int := 0;
    v_avg float;
    v_my_dates jsonb := '[]'::jsonb;
    v_my_profile jsonb;
    v_unread jsonb := '[]'::jsonb;
    v_weekly int := 0;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    v_is_owner := (v_camp.owner_id = v_uid)
        or exists (
            select 1 from public.profiles p
            where p.id = v_uid and lower(p.role) in ('admin', 'moderator')
        );

    v_is_member := exists (
        select 1 from public.campaign_members m
        where m.campaign_id = p_campaign_id and m.user_id = v_uid
    );

    v_confirmed := exists (
        select 1 from public.checkins ci
        where ci.campaign_id = p_campaign_id
          and ci.user_id = v_uid
          and ci.checkin_date = v_today
          and coalesce(ci.note, '') not like '%tạm tính%'
    );

    v_campaign := public._campaign_json(v_camp);

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
        'rating', tr.rating,
        'status', tr.status,
        'bonus_awarded', tr.bonus_awarded,
        'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'rejection_reason', tr.rejection_reason,
        'image_hash', tr.image_hash
    ) order by tr.created_at desc), '[]'::jsonb)
    into v_reports
    from (
        select * from public.tester_reports
        where campaign_id = p_campaign_id
          and (v_is_owner or user_id = v_uid)
        order by created_at desc
        limit 200
    ) tr;

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
        where tr.campaign_id = p_campaign_id and (v_is_owner or tr.user_id = v_uid)
    ) t on t.id = p.id;

    select count(distinct u.user_id) into v_today_tested
    from (
        select ci.user_id from public.checkins ci
        where ci.campaign_id = p_campaign_id and ci.checkin_date = v_today
        union
        select tr.user_id from public.tester_reports tr
        where tr.campaign_id = p_campaign_id and tr.status <> 'rejected'
          and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    ) u;

    select greatest(
        (select count(*) from public.tester_reports tr where tr.campaign_id = p_campaign_id),
        (select count(*) from public.feedbacks f where f.campaign_id = p_campaign_id),
        (select count(*) from public.checkins ci where ci.campaign_id = p_campaign_id)
    ) into v_reports_count;

    select count(*) into v_pending
    from public.tester_reports tr
    where tr.campaign_id = p_campaign_id and tr.status = 'pending';

    select avg(f.rating)::float8 into v_avg
    from public.feedbacks f
    where f.campaign_id = p_campaign_id;

    select coalesce(jsonb_agg(distinct to_char(ci.checkin_date, 'YYYY-MM-DD')), '[]'::jsonb)
    into v_my_dates
    from public.checkins ci
    where ci.campaign_id = p_campaign_id and ci.user_id = v_uid;

    select public._profile_json(p) into v_my_profile
    from public.profiles p where p.id = v_uid;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
        'warning_type', w.warning_type,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) order by w.created_at desc), '[]'::jsonb)
    into v_unread
    from public.user_warnings w
    where w.user_id = v_uid and w.acknowledged = false;

    select count(*) into v_weekly
    from public.user_warnings
    where user_id = v_uid and warning_date >= v_week_ago;

    return jsonb_build_object(
        'campaign', v_campaign,
        'is_member', v_is_member,
        'is_owner', v_is_owner,
        'checked_in_confirmed_today', v_confirmed,
        'testers', v_testers,
        'feedbacks', v_feedbacks,
        'reports', v_reports,
        'profiles', v_profiles,
        'today_tested_count', v_today_tested,
        'reports_count', v_reports_count,
        'pending_reports_count', v_pending,
        'avg_rating', v_avg,
        'my_checkin_dates', v_my_dates,
        'my_profile', v_my_profile,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly
    );
end;
$$;

grant execute on function public.get_campaign_detail(uuid) to authenticated, service_role;


-- ============================================================================
-- 6. RPC: get_campaigns_stats — thống kê nhiều campaign trong 1 round-trip
-- ============================================================================

create or replace function public.get_campaigns_stats(p_campaign_ids uuid[])
returns jsonb
language plpgsql
security definer
set search_path = public
stable
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if p_campaign_ids is null or array_length(p_campaign_ids, 1) is null then
        return '{}'::jsonb;
    end if;

    return (
        select coalesce(jsonb_object_agg(cid::text, jsonb_build_object(
            'installed_count', coalesce(t.installed, 0),
            'today_tested_count', coalesce(t.today_tested, 0),
            'has_tested_today', coalesce(t.has_tested, false)
        )), '{}'::jsonb)
        from unnest(p_campaign_ids) as cid
        left join (
            select c.id,
                (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed,
                (select count(distinct ci.user_id) from public.checkins ci
                  where ci.campaign_id = c.id and ci.checkin_date = v_today) as today_tested,
                (v_uid is not null and (
                    exists (select 1 from public.checkins ci2
                            where ci2.campaign_id = c.id and ci2.user_id = v_uid and ci2.checkin_date = v_today)
                    or exists (select 1 from public.tester_reports tr
                               where tr.campaign_id = c.id and tr.user_id = v_uid
                                 and tr.status <> 'rejected'
                                 and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today)
                )) as has_tested
            from public.campaigns c
            where c.id = any(p_campaign_ids)
        ) t on t.id = cid
    );
end;
$$;

grant execute on function public.get_campaigns_stats(uuid[]) to authenticated, service_role;


-- ============================================================================
-- 7. RPC: get_leaderboard_v2 — bảng xếp hạng không cần tải toàn bộ checkins
-- ============================================================================

create or replace function public.get_leaderboard_v2(p_limit int default 100)
returns jsonb
language sql
security definer
set search_path = public
stable
as $$
    select coalesce(jsonb_agg(jsonb_build_object(
        'userId', t.id,
        'displayName', t.name,
        'avatarUrl', t.avatar_url,
        'credits', t.credits,
        'checkinCount', t.checkin_count,
        'campaignsCount', t.campaigns_count,
        'rank', t.rn
    ) order by t.rn), '[]'::jsonb)
    from (
        select b.*, row_number() over (order by b.credits desc, b.checkin_count desc, b.campaigns_count desc) as rn
        from (
            select p.id,
                   coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Tester') as name,
                   p.avatar_url,
                   p.credits,
                   (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                   (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
            from public.profiles p
            order by p.credits desc
            limit greatest(1, least(coalesce(p_limit, 100), 200))
        ) b
    ) t;
$$;

grant execute on function public.get_leaderboard_v2(int) to authenticated, anon, service_role;


-- ============================================================================
-- 8. LÊN LỊCH PG_CRON (idempotent — bỏ qua nếu extension chưa bật)
-- ============================================================================

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        begin
            perform cron.unschedule('campaign_lifecycle_sync_job');
        exception when others then null;
        end;
        begin
            perform cron.unschedule('auto_accept_reports_job');
        exception when others then null;
        end;
        perform cron.schedule(
            'campaign_lifecycle_sync_job',
            '11 * * * *',
            'select public.sync_campaign_lifecycle();'
        );
        perform cron.schedule(
            'auto_accept_reports_job',
            '*/30 * * * *',
            'select public.auto_accept_expired_reports();'
        );
    else
        raise notice 'pg_cron chưa được bật — app vẫn tự đồng bộ (throttle) khi mở Trang chủ. Nên bật pg_cron tại Database > Extensions rồi chạy lại khối này.';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;


notify pgrst, 'reload schema';
