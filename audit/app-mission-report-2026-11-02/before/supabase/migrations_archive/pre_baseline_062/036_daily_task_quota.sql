-- ============================================================================
-- 036_daily_task_quota.sql
-- Nhiệm vụ ngày mới:
--   • Chủ app: K app đang hoạt động -> cần test 3K app khác/ngày.
--     R_eff = min(3K, A), với A là số app open không thuộc sở hữu user.
--     Nếu A = 0 thì chỉ tiêu được miễn.
--   • Người không đăng app: chỉ cần đăng nhập mỗi ngày.
--   • Một app chỉ tính 1 lần/ngày; report pending/approved được tính.
--   • Phạt theo chuỗi 1% / 2% / 5% / 10%, nhân tỷ lệ thiếu, tối thiểu 1 Credit.
--   • Tự động tạm dừng app sau 7 ngày thiếu liên tiếp.
--   • Admin/Moderator được miễn.
--   • App mới bắt đầu tính từ ngày mai; tài khoản mới miễn ngày đăng ký.
--   • Toàn bộ mốc ngày dùng Asia/Ho_Chi_Minh.
-- ============================================================================

-- ============================================================================
-- 1. Cột mới trên profiles
-- ============================================================================

alter table public.profiles
    add column if not exists last_login_date date;

alter table public.profiles
    add column if not exists daily_missed_streak int not null default 0;

comment on column public.profiles.last_login_date is
    'Ngày đăng nhập gần nhất theo Asia/Ho_Chi_Minh.';
comment on column public.profiles.daily_missed_streak is
    'Số ngày liên tiếp không hoàn thành nhiệm vụ ngày. Không dùng missed_streak legacy.';

-- ============================================================================
-- 2. Bảng user_daily_tasks
--    - Row live: finalized_at IS NULL, chỉ logged_in là dữ liệu có nghĩa.
--    - Row finalized: đã chốt đủ các cột audit.
--    - Row grace: mode='exempt', is_exempt=true, met=true.
-- ============================================================================

create table if not exists public.user_daily_tasks (
    user_id uuid not null references public.profiles(id) on delete cascade,
    task_date date not null,
    mode text not null default 'member'
        check (mode in ('owner', 'member', 'exempt')),
    active_app_count int not null default 0,
    required_tests int not null default 0,
    effective_required_tests int not null default 0,
    available_apps int not null default 0,
    completed_tests int not null default 0,
    logged_in boolean not null default false,
    is_exempt boolean not null default false,
    met boolean not null default false,
    missed_streak int not null default 0,
    penalty_credits int not null default 0,
    finalized_at timestamptz,
    primary key (user_id, task_date)
);

create index if not exists idx_user_daily_tasks_date
    on public.user_daily_tasks(task_date);

alter table public.user_daily_tasks enable row level security;

revoke all on public.user_daily_tasks from anon, authenticated;
grant all on public.user_daily_tasks to service_role;

-- ============================================================================
-- 3. Helper: trạng thái nhiệm vụ của một user trong một ngày
-- ============================================================================

create or replace function public.get_daily_task_status(
    p_user_id uuid,
    p_date date
)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_role text;
    v_last_login date;
    v_streak int := 0;
    v_exempt boolean := false;
    v_k int := 0;
    v_a int := 0;
    v_d int := 0;
    v_required int := 0;
    v_effective int := 0;
    v_logged_in boolean := false;
    v_met boolean := false;
    v_next_pct int := 1;
    v_mode text;
    v_day_start timestamptz;
    v_next_day_start timestamptz;
    v_window_start timestamptz;
begin
    if p_user_id is null or p_date is null then
        return null;
    end if;

    select lower(p.role), p.last_login_date, p.daily_missed_streak
    into v_role, v_last_login, v_streak
    from public.profiles p
    where p.id = p_user_id;

    if not found then
        return null;
    end if;

    v_streak := coalesce(v_streak, 0);
    v_exempt := coalesce(v_role in ('admin', 'moderator'), false);

    v_day_start := (p_date::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_next_day_start := ((p_date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_window_start := ((p_date - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh');

    -- K: app của user đang open, hoặc paused do chính sách vắng mặt.
    -- App phải được tạo trước 00:00 VN của ngày chấm để bắt đầu tính từ ngày mai.
    select count(*) into v_k
    from public.campaigns c
    where c.owner_id = p_user_id
      and (
          c.status = 'open'
          or (c.status = 'paused' and c.paused_reason = 'owner_inactive')
      )
      and c.created_at < v_day_start
      and c.created_at > v_window_start;

    -- A: app open không thuộc sở hữu user, đã tồn tại trong ngày chấm.
    select count(*) into v_a
    from public.campaigns c
    where c.status = 'open'
      and c.owner_id <> p_user_id
      and c.created_at < v_next_day_start
      and c.created_at > v_window_start;

    -- D: số app khác nhau user đã test trong ngày.
    select count(distinct campaign_id) into v_d
    from (
        select ci.campaign_id
        from public.checkins ci
        join public.campaigns c on c.id = ci.campaign_id
        where ci.user_id = p_user_id
          and ci.checkin_date = p_date
          and c.owner_id <> p_user_id
          -- Checkin sinh ra từ report đã được đếm ở nhánh tester_reports.
          -- Bỏ qua để report cũ được duyệt hôm nay không tính thêm một lượt.
          and (
              ci.note is null
              or (
                  ci.note not like 'Đã kiểm thử%'
                  and ci.note not like 'Đã nộp kết quả%'
                  and ci.note not like '%tạm tính%'
                  and ci.note not like 'Tự động duyệt%'
                  and ci.note not like '%Tự động duyệt%'
                  and ci.note not like '%chủ app duyệt%'
              )
          )

        union

        select tr.campaign_id
        from public.tester_reports tr
        join public.campaigns c on c.id = tr.campaign_id
        where tr.user_id = p_user_id
          and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = p_date
          and tr.status in ('pending', 'approved')
          and c.owner_id <> p_user_id
    ) t;

    -- Login lấy từ lịch sử theo ngày (live/finalized row), fallback last_login_date.
    select (v_last_login = p_date) or exists (
        select 1
        from public.user_daily_tasks t
        where t.user_id = p_user_id
          and t.task_date = p_date
          and t.logged_in = true
    ) into v_logged_in;

    v_required := 3 * v_k;
    v_effective := least(v_required, v_a);
    v_mode := case when v_k > 0 then 'owner' else 'member' end;

    if v_exempt then
        v_met := true;
    elsif v_k > 0 then
        v_met := v_d >= v_effective;
    else
        v_met := v_logged_in or v_d > 0;
    end if;

    v_next_pct := case
        when v_streak <= 0 then 1
        when v_streak = 1 then 2
        when v_streak = 2 then 5
        else 10
    end;

    return jsonb_build_object(
        'status_date', to_char(p_date, 'YYYY-MM-DD'),
        'mode', v_mode,
        'active_app_count', v_k,
        'required_tests', v_required,
        'available_apps', v_a,
        'effective_required_tests', v_effective,
        'completed_tests', v_d,
        'logged_in_today', v_logged_in,
        'is_penalty_exempt', v_exempt,
        'missed_streak', v_streak,
        'next_penalty_percent', v_next_pct,
        'met', v_met
    );
end;
$$;

revoke execute on function public.get_daily_task_status(uuid, date)
    from public, anon, authenticated;
grant execute on function public.get_daily_task_status(uuid, date)
    to service_role;

create or replace function public.get_my_daily_task_status()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    return public.get_daily_task_status(
        v_uid,
        (now() at time zone 'Asia/Ho_Chi_Minh')::date
    );
end;
$$;

grant execute on function public.get_my_daily_task_status()
    to authenticated, service_role;

-- ============================================================================
-- 4. Refresh chuỗi ngày khi user vừa hoàn thành nhiệm vụ
-- ============================================================================

create or replace function public.refresh_daily_completion(
    p_user_id uuid,
    p_date date default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_date date := coalesce(p_date, v_today);
    v_status jsonb;
begin
    if p_user_id is null then
        return;
    end if;

    -- Chỉ đặt lại chuỗi hiện tại khi nhiệm vụ của đúng hôm nay đã đạt.
    -- Tránh checkin/backfill của ngày cũ vô tình xoá chuỗi hiện tại.
    if v_date <> v_today then
        return;
    end if;

    v_status := public.get_daily_task_status(p_user_id, v_date);
    if v_status is not null
       and coalesce((v_status ->> 'met')::boolean, false) then
        update public.profiles
        set daily_missed_streak = 0
        where id = p_user_id
          and daily_missed_streak <> 0;
    end if;
end;
$$;

revoke execute on function public.refresh_daily_completion(uuid, date)
    from public, anon, authenticated;
grant execute on function public.refresh_daily_completion(uuid, date)
    to service_role;

-- ============================================================================
-- 5. Ghi nhận đăng nhập theo ngày
-- ============================================================================

create or replace function public.record_daily_login_internal(
    p_user_id uuid,
    p_date date default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_date date := coalesce(
        p_date,
        (now() at time zone 'Asia/Ho_Chi_Minh')::date
    );
begin
    if p_user_id is null then
        return;
    end if;

    -- Auth user có thể chưa có profile (signup dở dang): bỏ qua thay vì lỗi FK.
    if not exists (select 1 from public.profiles where id = p_user_id) then
        return;
    end if;

    update public.profiles
    set last_login_date = v_date
    where id = p_user_id
      and (last_login_date is null or last_login_date < v_date);

    insert into public.user_daily_tasks as t (user_id, task_date, logged_in, finalized_at)
    values (p_user_id, v_date, true, null)
    on conflict (user_id, task_date) do update
    set logged_in = true
    where t.finalized_at is null;

    perform public.refresh_daily_completion(p_user_id, v_date);
end;
$$;

revoke execute on function public.record_daily_login_internal(uuid, date)
    from public, anon, authenticated;
grant execute on function public.record_daily_login_internal(uuid, date)
    to service_role;

create or replace function public.record_daily_login()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    perform public.record_daily_login_internal(
        v_uid,
        (now() at time zone 'Asia/Ho_Chi_Minh')::date
    );
end;
$$;

grant execute on function public.record_daily_login()
    to authenticated, service_role;

-- ============================================================================
-- 6. _profile_json: giữ missed_streak cho client cũ, thêm field mới
-- ============================================================================

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
        'missed_streak', p.daily_missed_streak,
        'daily_missed_streak', p.daily_missed_streak,
        'last_login_date', to_char(p.last_login_date, 'YYYY-MM-DD'),
        'role', p.role,
        'is_locked', p.is_locked,
        'created_at', to_char(p.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
$$;

revoke execute on function public._profile_json(public.profiles)
    from public, anon, authenticated;

-- ============================================================================
-- 7. get_my_dashboard: ghi nhận đăng nhập + trả daily_task
-- ============================================================================

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
    v_daily jsonb := null;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Mọi lần mở Trang chủ/Hồ sơ đều được tính là đăng nhập trong ngày.
    -- Không gọi get_my_dashboard từ background worker.
    perform public.record_daily_login_internal(v_uid, v_today);

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

    v_daily := public.get_daily_task_status(v_uid, v_today);

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
        'quest_summary', v_quest,
        'daily_task', v_daily
    );
end;
$$;

grant execute on function public.get_my_dashboard(boolean)
    to authenticated, service_role;

-- ============================================================================
-- 8. Penalty job
-- ============================================================================

create or replace function public._apply_daily_inactivity_penalty(p_eval date)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    r record;
    v_status jsonb;
    v_met boolean;
    v_mode text;
    v_required int;
    v_effective int;
    v_completed int;
    v_logged_in boolean;
    v_prev_streak int;
    v_new_streak int;
    v_pct numeric;
    v_ratio numeric;
    v_deduction int;
    v_new_balance int;
    v_rows int;
    v_note text;
begin
    if p_eval is null then
        return;
    end if;

    -- Chống chạy song song; unique key vẫn là lớp bảo vệ cuối.
    perform pg_advisory_xact_lock(hashtextextended('daily_inactivity_penalty', 0));

    for r in
        select p.id, p.credits, p.daily_missed_streak
        from public.profiles p
        where p.created_at < (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh')
          and lower(p.role) not in ('admin', 'moderator')
        for update of p
    loop
        if exists (
            select 1
            from public.user_daily_tasks t
            where t.user_id = r.id
              and t.task_date = p_eval
              and t.finalized_at is not null
        ) then
            continue;
        end if;

        v_status := public.get_daily_task_status(r.id, p_eval);
        if v_status is null then
            continue;
        end if;

        v_met := coalesce((v_status ->> 'met')::boolean, false);
        v_mode := coalesce(v_status ->> 'mode', 'member');
        v_required := coalesce((v_status ->> 'required_tests')::int, 0);
        v_effective := coalesce((v_status ->> 'effective_required_tests')::int, 0);
        v_completed := coalesce((v_status ->> 'completed_tests')::int, 0);
        v_logged_in := coalesce((v_status ->> 'logged_in_today')::boolean, false);

        -- Lấy streak từ row finalized gần nhất, không dùng profile hiện tại:
        -- user có thể đã hoàn thành hôm nay trước 00:05 và profile đã reset 0.
        select coalesce(
            (select t.missed_streak
             from public.user_daily_tasks t
             where t.user_id = r.id
               and t.task_date < p_eval
               and t.finalized_at is not null
             order by t.task_date desc
             limit 1),
            r.daily_missed_streak,
            0
        ) into v_prev_streak;

        v_new_streak := case when v_met then 0 else v_prev_streak + 1 end;

        if v_met then
            v_deduction := 0;
        else
            v_pct := case
                when v_new_streak = 1 then 0.01
                when v_new_streak = 2 then 0.02
                when v_new_streak = 3 then 0.05
                else 0.10
            end;

            if v_mode = 'owner' and v_effective > 0 then
                v_ratio := greatest(
                    0,
                    least(
                        1,
                        (v_effective - v_completed)::numeric / v_effective::numeric
                    )
                );
            elsif v_mode = 'member' then
                v_ratio := case when v_logged_in then 0 else 1 end;
            else
                v_ratio := 0;
            end if;

            if v_ratio <= 0 then
                v_deduction := 0;
            else
                v_deduction := least(
                    r.credits,
                    greatest(1, ceil(r.credits * v_pct * v_ratio)::int)
                );
            end if;
        end if;

        v_new_balance := r.credits - v_deduction;

        insert into public.user_daily_tasks as t (
            user_id, task_date, mode, active_app_count, required_tests,
            effective_required_tests, available_apps, completed_tests,
            logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at
        ) values (
            r.id, p_eval, v_mode,
            coalesce((v_status ->> 'active_app_count')::int, 0),
            v_required,
            v_effective,
            coalesce((v_status ->> 'available_apps')::int, 0),
            v_completed,
            v_logged_in,
            false,
            v_met,
            v_new_streak,
            v_deduction,
            now()
        )
        on conflict (user_id, task_date) do update
        set mode = excluded.mode,
            active_app_count = excluded.active_app_count,
            required_tests = excluded.required_tests,
            effective_required_tests = excluded.effective_required_tests,
            available_apps = excluded.available_apps,
            completed_tests = excluded.completed_tests,
            logged_in = excluded.logged_in,
            is_exempt = excluded.is_exempt,
            met = excluded.met,
            missed_streak = excluded.missed_streak,
            penalty_credits = excluded.penalty_credits,
            finalized_at = now()
        where t.finalized_at is null;

        get diagnostics v_rows = row_count;
        if v_rows = 0 then
            continue;
        end if;

        update public.profiles
        set credits = v_new_balance,
            daily_missed_streak = v_new_streak
        where id = r.id;

        if v_deduction > 0 then
            if v_mode = 'owner' then
                v_note := format(
                    'Không đạt chỉ tiêu ngày %s: %s/%s app (thiếu %s). Trừ %s Credit.',
                    to_char(p_eval, 'DD/MM/YYYY'),
                    v_completed,
                    v_effective,
                    greatest(0, v_effective - v_completed),
                    v_deduction
                );
            else
                v_note := format(
                    'Không đăng nhập ngày %s. Trừ %s Credit.',
                    to_char(p_eval, 'DD/MM/YYYY'),
                    v_deduction
                );
            end if;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (r.id, -v_deduction, 'inactivity_penalty', v_note, v_new_balance);
        end if;

        if not v_met then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                r.id,
                'Không đạt nhiệm vụ ngày',
                case
                    when v_deduction = 0 and v_mode = 'owner' then format(
                        'Ngày %s bạn mới test %s/%s app. Số dư hiện tại là 0 nên không trừ Credit, nhưng chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_completed,
                        v_effective,
                        v_new_streak
                    )
                    when v_deduction = 0 then format(
                        'Ngày %s bạn không mở app. Số dư hiện tại là 0 nên không trừ Credit, nhưng chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_new_streak
                    )
                    when v_mode = 'owner' then format(
                        'Ngày %s bạn mới test %s/%s app. Bạn bị trừ %s Credit và chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_completed,
                        v_effective,
                        v_deduction,
                        v_new_streak
                    )
                    else format(
                        'Ngày %s bạn không mở app. Bạn bị trừ %s Credit và chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_deduction,
                        v_new_streak
                    )
                end,
                'credit',
                jsonb_build_object(
                    'task_date', to_char(p_eval, 'YYYY-MM-DD'),
                    'penalty_credits', v_deduction
                )
            );
        end if;

        -- Nếu hôm nay đã đạt, reset streak ngay để auto-pause không pause oan.
        perform public.refresh_daily_completion(r.id, v_today);
    end loop;
end;
$$;

revoke execute on function public._apply_daily_inactivity_penalty(date)
    from public, anon, authenticated;
grant execute on function public._apply_daily_inactivity_penalty(date)
    to service_role;

create or replace function public.apply_daily_inactivity_penalty()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_eval date := ((now() at time zone 'Asia/Ho_Chi_Minh')::date) - 1;
begin
    perform public._apply_daily_inactivity_penalty(v_eval);
end;
$$;

revoke execute on function public.apply_daily_inactivity_penalty()
    from public, anon, authenticated;
grant execute on function public.apply_daily_inactivity_penalty()
    to service_role;

-- ============================================================================
-- 9. Auto-pause sau 7 ngày thiếu liên tiếp
-- ============================================================================

create or replace function public.pause_inactive_owner_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    v_count int := 0;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    for r in
        select c.id, c.owner_id, c.app_name
        from public.campaigns c
        join public.profiles p on p.id = c.owner_id
        where c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
          and c.created_at <= now() - interval '24 hours'
          and lower(p.role) not in ('admin', 'moderator')
          and p.daily_missed_streak >= 7
        for update of c
    loop
        -- Người vừa hoạt động lại trong hôm nay thì không pause oan.
        if coalesce(
            (public.get_daily_task_status(r.owner_id, v_today) ->> 'met')::boolean,
            false
        ) then
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
                'Ứng dụng "%s" đã bị tạm dừng vì bạn không hoàn thành chỉ tiêu test trong 7 ngày liên tục. Hãy hoàn thành chỉ tiêu hôm nay, sau đó bấm "Tiếp tục nhận tester" trong màn hình quản lý app để hiển thị lại.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'owner_inactive')
        );

        v_count := v_count + 1;
    end loop;

    return v_count;
end;
$$;

grant execute on function public.pause_inactive_owner_campaigns()
    to authenticated, service_role;

-- ============================================================================
-- 10. Resume: yêu cầu đã hoàn thành chỉ tiêu hôm nay
-- ============================================================================

drop function if exists public.resume_campaign(uuid);

create or replace function public.resume_campaign(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_camp record;
    v_missed int;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

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

    -- App bị Admin tạm dừng: chủ app không được tự mở lại.
    if v_camp.paused_reason = 'admin_manual' then
        raise exception 'PAUSED_BY_ADMIN';
    end if;

    -- Quá cửa sổ 30 ngày -> hoàn thành (không raise để tránh rollback UPDATE).
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

    -- Slot > 1 phải còn hạn thì mới được mở lại.
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

    -- Nếu hôm nay đã đạt chỉ tiêu, refresh sẽ reset chuỗi về 0.
    perform public.refresh_daily_completion(v_uid, v_today);

    select daily_missed_streak into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 7 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null,
        admin_note = null,
        admin_resumed = false
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$$;

grant execute on function public.resume_campaign(uuid)
    to authenticated, service_role;

-- ============================================================================
-- 11. Check-in trigger: cập nhật chuỗi ngay khi test đủ
-- ============================================================================

create or replace function public.handle_checkin_reward()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    today_reward_count int;
    v_credit_reward int := 0;
    new_balance int;
begin
    update public.profiles
    set last_active_date = new.checkin_date,
        missed_streak = 0
    where id = new.user_id;

    perform public.refresh_daily_completion(new.user_id, new.checkin_date);

    -- Nếu dòng checkin này được tạo từ việc nộp/duyệt báo cáo (đã xử lý cộng điểm), bỏ qua không cộng lần 2
    if new.note is not null and (
        new.note like 'Đã kiểm thử%' or
        new.note like '%Tự động duyệt%' or
        new.note like '%chủ app duyệt%' or
        new.note like 'Đã nộp kết quả%' or
        new.note like '%tạm tính%'
    ) then
        return new;
    end if;

    select count(*) into today_reward_count
    from public.checkins
    where user_id = new.user_id and checkin_date = new.checkin_date;

    if today_reward_count <= 30 then
        v_credit_reward := 2;
    elsif today_reward_count <= 50 then
        v_credit_reward := 1;
    else
        v_credit_reward := 0;
    end if;

    if v_credit_reward > 0 then
        update public.profiles
        set credits = credits + v_credit_reward
        where id = new.user_id
        returning credits into new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            new.user_id,
            v_credit_reward,
            'checkin_reward',
            format('Thưởng test app (lượt %s/50 trong ngày, +%s Credit)', today_reward_count, v_credit_reward),
            new_balance
        );
    end if;

    return new;
end;
$$;

-- ============================================================================
-- 12. Backfill + grace 2 ngày
-- ============================================================================

do $$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    update public.profiles
    set last_login_date = v_today
    where last_login_date is null;

    alter table public.profiles
        alter column last_login_date set default ((now() at time zone 'Asia/Ho_Chi_Minh')::date);
    alter table public.profiles
        alter column last_login_date set not null;

    update public.profiles
    set daily_missed_streak = 0
    where daily_missed_streak <> 0;

    update public.profiles
    set missed_streak = 0
    where lower(role) in ('admin', 'moderator')
      and missed_streak <> 0;

    -- Grace ngày hôm qua: tránh phạt ngay khi migration chạy trước 00:05.
    insert into public.user_daily_tasks (
        user_id, task_date, mode, logged_in, is_exempt, met,
        missed_streak, penalty_credits, finalized_at
    )
    select id, v_today - 1, 'exempt', true, true, true, 0, 0, now()
    from public.profiles
    on conflict (user_id, task_date) do nothing;

    -- Grace ngày hôm nay: enforcement đầu tiên là ngày mai.
    insert into public.user_daily_tasks (
        user_id, task_date, mode, logged_in, is_exempt, met,
        missed_streak, penalty_credits, finalized_at
    )
    select id, v_today, 'exempt', true, true, true, 0, 0, now()
    from public.profiles
    on conflict (user_id, task_date) do nothing;
end $$;

-- ============================================================================
-- 13. Quyền & reload schema
-- ============================================================================

grant execute on function public.get_my_dashboard(boolean)
    to authenticated, service_role;
grant execute on function public.get_my_daily_task_status()
    to authenticated, service_role;
grant execute on function public.record_daily_login()
    to authenticated, service_role;

notify pgrst, 'reload schema';
