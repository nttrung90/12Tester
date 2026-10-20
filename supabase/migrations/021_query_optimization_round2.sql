-- ============================================================================
-- 12 Tester — Supabase migration 021: Query optimization round 2
-- - Index ghép cho các truy vấn nóng còn thiếu.
-- - get_my_dashboard v2:
--     + thêm 'slots' (user_slots) → app bỏ hẳn 1-2 round-trip cho Profile/Slots.
--     + tham số p_include_leaderboard (default true) → client Trang chủ truyền
--       false để trả kết quả nhanh hơn; bảng xếp hạng tải khi mở tab.
--   Tương thích ngược: chữ ký cũ get_my_dashboard() bị drop, chữ ký mới có
--   default nên app cũ gọi không tham số vẫn chạy bình thường.
-- - get_next_untested_campaign(p_current_id): 1 RPC nhỏ thay cho việc tải
--   toàn bộ danh sách open + stats trước mỗi lần nộp báo cáo.
-- - Đảm bảo lịch pg_cron (idempotent, bỏ qua nếu extension chưa bật).
-- Chạy trong Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================


-- ============================================================================
-- 1. INDEX CHO CÁC TRUY VẤN NÓNG (ROUND 2)
-- ============================================================================

create index if not exists idx_credit_logs_user_created
    on public.credit_logs(user_id, created_at desc);

create index if not exists idx_user_warnings_user_unread
    on public.user_warnings(user_id, acknowledged, created_at desc);


-- ============================================================================
-- 2. RPC: get_my_dashboard v2 — thêm slots + tùy chọn bỏ leaderboard
-- ============================================================================

drop function if exists public.get_my_dashboard();

create or replace function public.get_my_dashboard(p_include_leaderboard boolean default true)
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
    v_slots jsonb := '[]'::jsonb;
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

    -- Bảng xếp hạng: top 100 theo credits (khớp thuật toán cũ của app).
    -- Chỉ tính khi client yêu cầu (tab Bảng xếp hạng mở).
    if p_include_leaderboard then
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
    end if;

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

    -- Slot đã mở của người dùng (bỏ 1-2 round-trip riêng cho màn Profile/Tạo app)
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', s.id,
        'user_id', s.user_id,
        'slot_number', s.slot_number,
        'unlocked_at', to_char(s.unlocked_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'expires_at', to_char(s.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(s.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by s.slot_number asc), '[]'::jsonb)
    into v_slots
    from public.user_slots s
    where s.user_id = v_uid;

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
        'slots', v_slots,
        'quest_summary', v_quest
    );
end;
$$;

grant execute on function public.get_my_dashboard(boolean) to authenticated, service_role;


-- ============================================================================
-- 3. RPC: get_next_untested_campaign — app kế tiếp chưa test hôm nay
--    (dùng khi nộp báo cáo; thay cho listOpenCampaigns + get_campaigns_stats)
-- ============================================================================

create or replace function public.get_next_untested_campaign(p_current_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
stable
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp public.campaigns%rowtype;
begin
    select c.* into v_camp
    from public.campaigns c
    where c.status = 'open'
      and (p_current_id is null or c.id <> p_current_id)
      and (v_uid is null or c.owner_id <> v_uid)
      and not exists (
          select 1 from public.checkins ci
          where ci.campaign_id = c.id
            and ci.user_id = v_uid
            and ci.checkin_date = v_today
      )
      and not exists (
          select 1 from public.tester_reports tr
          where tr.campaign_id = c.id
            and tr.user_id = v_uid
            and tr.status <> 'rejected'
            and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
      )
    order by c.created_at desc
    limit 1;

    return jsonb_build_object(
        'campaign', case
            when v_camp.id is null then null
            else public._campaign_json(v_camp)
        end
    );
end;
$$;

grant execute on function public.get_next_untested_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 4. ĐẢM BẢO LỊCH PG_CRON (idempotent — bỏ qua nếu extension chưa bật)
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
        raise notice 'pg_cron chưa được bật — bật tại Database > Extensions rồi chạy lại khối này.';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;


notify pgrst, 'reload schema';
