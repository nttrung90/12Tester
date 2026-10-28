-- ============================================================================
-- Migration 047: Cấu hình chu kỳ thống kê lượt test Trang chủ (1 / 2 / 3 ngày)
-- ============================================================================

-- 1. Bảng lưu trữ chính sách chu kỳ thống kê Trang chủ
create table if not exists public.home_stats_policies (
    id bigserial primary key,
    effective_from date not null unique,
    cycle_days int not null check (cycle_days in (1, 2, 3)),
    created_at timestamptz not null default now(),
    created_by uuid references auth.users(id) on delete set null,
    updated_at timestamptz not null default now(),
    updated_by uuid references auth.users(id) on delete set null
);

create index if not exists idx_home_stats_policies_effective on public.home_stats_policies(effective_from desc);

-- 2. Bảng audit ghi nhận lịch sử thay đổi cấu hình chu kỳ
create table if not exists public.home_stats_policy_audits (
    id bigserial primary key,
    action text not null, -- 'INIT', 'SET_SCHEDULE', 'UPDATE_SCHEDULE', 'CANCEL_SCHEDULE'
    effective_from date not null,
    cycle_days_old int,
    cycle_days_new int,
    actor_id uuid references auth.users(id) on delete set null,
    recorded_at timestamptz not null default now()
);

-- 3. Bảo vệ RLS và phân quyền truy cập
alter table public.home_stats_policies enable row level security;
alter table public.home_stats_policy_audits enable row level security;

-- Thu hồi mọi quyền đọc/sửa trực tiếp từ anon và authenticated.
-- Toàn bộ việc đọc và cập nhật phải qua các RPC có kiểm tra role admin.
revoke all on public.home_stats_policies from public, anon, authenticated;
revoke all on public.home_stats_policy_audits from public, anon, authenticated;

grant select, insert, update, delete on public.home_stats_policies to service_role;
grant select, insert on public.home_stats_policy_audits to service_role;

-- 4. Seed chính sách nền: Mặc định chu kỳ 1 ngày từ mốc cố định trong quá khứ
insert into public.home_stats_policies (effective_from, cycle_days, created_at, updated_at)
values ('2020-01-01', 1, now(), now())
on conflict (effective_from) do nothing;

-- 5. Helper nội bộ: Tính chu kỳ cố định theo ngày hiện tại Asia/Ho_Chi_Minh
--    Công thức: period_start = A + floor((D - A) / N) * N
create or replace function public._get_home_stats_period(p_today date)
returns table (
    period_start date,
    period_end_exclusive date,
    cycle_days int
)
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_eff date;
    v_days int;
    v_diff int;
begin
    -- Tìm chính sách có effective_from <= p_today gần nhất
    select p.effective_from, p.cycle_days
    into v_eff, v_days
    from public.home_stats_policies p
    where p.effective_from <= p_today
    order by p.effective_from desc
    limit 1;

    if v_eff is null then
        v_eff := p_today;
        v_days := 1;
    end if;

    v_diff := p_today - v_eff;
    period_start := v_eff + ((floor(v_diff::numeric / v_days) * v_days)::int);
    period_end_exclusive := period_start + v_days;
    cycle_days := v_days;
    return next;
end;
$$;

revoke execute on function public._get_home_stats_period(date) from public, anon, authenticated;
grant execute on function public._get_home_stats_period(date) to service_role;

-- 6. Helper nội bộ: Tạo revision băm để kiểm tra xung đột sửa đồng thời (concurrency revision check)
--    Chuẩn hóa timestamp bằng extract(epoch from updated_at) để không phụ thuộc vào session timezone.
create or replace function public._get_home_stats_revision()
returns text
language sql
stable
security definer
set search_path = public
as $$
    select coalesce(
        md5(string_agg(
            id::text || ':' || effective_from::text || ':' || cycle_days::text || ':' || coalesce(extract(epoch from updated_at)::bigint::text, '0'),
            ',' order by id
        )),
        'empty'
    ) from public.home_stats_policies;
$$;

revoke execute on function public._get_home_stats_revision() from public, anon, authenticated;
grant execute on function public._get_home_stats_revision() to service_role;

-- 7. Trigger: Bảo vệ bất biến chính sách đang hoặc đã có hiệu lực (R06)
create or replace function public._check_home_stats_policy_immutability()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_current_vn date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if tg_op = 'UPDATE' then
        if old.effective_from <= v_current_vn then
            raise exception 'ACTIVE_POLICY_IMMUTABLE: Cannot modify an active or past policy (effective_from: %)', old.effective_from;
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_home_stats_policy_immutability on public.home_stats_policies;
create trigger trg_home_stats_policy_immutability
    before update on public.home_stats_policies
    for each row execute function public._check_home_stats_policy_immutability();

-- 8. RPC Admin: Đọc cấu hình chu kỳ hiện tại và lịch chờ áp dụng
create or replace function public.admin_get_home_stats_config()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_caller_role text;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_active_days int;
    v_active_from date;
    v_pending_days int;
    v_pending_from date;
    v_revision text;
begin
    if v_uid is null then
        raise exception 'UNAUTHORIZED: Authentication required';
    end if;

    select role into v_caller_role from public.profiles where id = v_uid;
    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'FORBIDDEN: Admin access required';
    end if;

    -- Lấy chính sách đang chạy (effective_from <= v_today)
    select cycle_days, effective_from into v_active_days, v_active_from
    from public.home_stats_policies
    where effective_from <= v_today
    order by effective_from desc
    limit 1;

    -- Lấy chính sách chờ áp dụng ở tương lai gần nhất (effective_from > v_today)
    select cycle_days, effective_from into v_pending_days, v_pending_from
    from public.home_stats_policies
    where effective_from > v_today
    order by effective_from asc
    limit 1;

    v_revision := public._get_home_stats_revision();

    return jsonb_build_object(
        'active_cycle_days', coalesce(v_active_days, 1),
        'active_effective_from', v_active_from,
        'pending_cycle_days', v_pending_days,
        'pending_effective_from', v_pending_from,
        'revision', v_revision,
        'today', v_today
    );
end;
$$;

grant execute on function public.admin_get_home_stats_config() to authenticated, service_role;
revoke execute on function public.admin_get_home_stats_config() from anon;

-- 9. RPC Admin: Cập nhật chu kỳ thống kê Trang chủ
--    Admin chọn 1, 2 hoặc 3 ngày; có hiệu lực từ 00:00 ngày hôm sau.
create or replace function public.admin_set_home_stats_cycle(
    p_cycle_days int,
    p_expected_revision text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_caller_role text;
    v_today date;
    v_tomorrow date;
    v_active_days int;
    v_active_from date;
    v_existing_tomorrow int;
    v_current_rev text;
    r record;
begin
    if v_uid is null then
        raise exception 'UNAUTHORIZED: Authentication required';
    end if;

    select role into v_caller_role from public.profiles where id = v_uid;
    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'FORBIDDEN: Admin access required';
    end if;

    if p_cycle_days is null or p_cycle_days not in (1, 2, 3) then
        raise exception 'INVALID_CYCLE_DAYS: Must be 1, 2, or 3';
    end if;

    -- R05: Bắt buộc revision hợp lệ; không được để null hoặc rỗng
    if p_expected_revision is null or trim(p_expected_revision) = '' then
        raise exception 'MISSING_REVISION: Expected revision is required';
    end if;

    -- Khóa tránh hai admin thao tác cùng lúc
    lock table public.home_stats_policies in exclusive mode;

    -- R06: Tính lại ngày chính xác sau khi đã lấy được lock
    v_today := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_tomorrow := v_today + 1;

    -- Kiểm tra revision dưới lock
    v_current_rev := public._get_home_stats_revision();
    if p_expected_revision <> v_current_rev then
        raise exception 'CONCURRENCY_CONFLICT: Config has been modified by another administrator';
    end if;

    -- Tìm chu kỳ đang có hiệu lực hôm nay
    select cycle_days, effective_from into v_active_days, v_active_from
    from public.home_stats_policies
    where effective_from <= v_today
    order by effective_from desc
    limit 1;

    v_active_days := coalesce(v_active_days, 1);

    if p_cycle_days = v_active_days then
        -- Admin chọn lại cùng giá trị đang chạy
        -- Nếu có lịch chờ ở tương lai (> v_today), hủy lịch chờ đó (rollback về mốc cũ)
        for r in select * from public.home_stats_policies where effective_from > v_today loop
            insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
            values ('CANCEL_SCHEDULE', r.effective_from, r.cycle_days, null, v_uid);
        end loop;
        delete from public.home_stats_policies where effective_from > v_today;
    else
        -- Admin chọn chu kỳ mới khác chu kỳ đang chạy -> áp dụng từ 00:00 ngày mai
        select cycle_days into v_existing_tomorrow
        from public.home_stats_policies
        where effective_from = v_tomorrow;

        if found then
            if v_existing_tomorrow <> p_cycle_days then
                update public.home_stats_policies
                set cycle_days = p_cycle_days, updated_at = now(), updated_by = v_uid
                where effective_from = v_tomorrow;

                insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
                values ('UPDATE_SCHEDULE', v_tomorrow, v_existing_tomorrow, p_cycle_days, v_uid);
            end if;
        else
            insert into public.home_stats_policies (effective_from, cycle_days, created_by, updated_by, created_at, updated_at)
            values (v_tomorrow, p_cycle_days, v_uid, v_uid, now(), now());

            insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
            values ('SET_SCHEDULE', v_tomorrow, v_active_days, p_cycle_days, v_uid);
        end if;

        -- Dọn các lịch xa hơn ngày mai nếu có
        delete from public.home_stats_policies where effective_from > v_tomorrow;
    end if;

    -- Trả về cấu hình mới nhất
    return public.admin_get_home_stats_config();
end;
$$;

grant execute on function public.admin_set_home_stats_cycle(int, text) to authenticated, service_role;
revoke execute on function public.admin_set_home_stats_cycle(int, text) from anon;

-- 10. Nâng cấp get_home_feed(): Bổ sung home_tested_count và stats_date vào stats
create or replace function public.get_home_feed()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_period_start date;
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

    -- Xác định mốc đầu chu kỳ hiện tại
    select period_start into v_period_start from public._get_home_stats_period(v_today);

    select coalesce(jsonb_object_agg(s.id::text, jsonb_build_object(
        'installed_count', coalesce(s.installed_count, 0),
        'today_tested_count', coalesce(s.today_tested_count, 0),
        'has_tested_today', coalesce(s.has_tested_today, false),
        'home_tested_count', coalesce(s.home_tested_count, 0),
        'stats_date', v_today::text
    )), '{}'::jsonb)
    into v_stats
    from (
        select c.id,
            (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed_count,
            (select count(distinct ci.user_id) from public.checkins ci
              where ci.campaign_id = c.id and ci.checkin_date = v_today) as today_tested_count,
            (select count(*) from public.checkins ci_period
              where ci_period.campaign_id = c.id
                and ci_period.checkin_date >= v_period_start
                and ci_period.checkin_date <= v_today) as home_tested_count,
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

-- 11. Nâng cấp get_campaigns_stats(): Bổ sung home_tested_count và stats_date vào stats
create or replace function public.get_campaigns_stats(p_campaign_ids uuid[])
returns jsonb
language plpgsql
security definer
set search_path = public
stable
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_period_start date;
begin
    if p_campaign_ids is null or array_length(p_campaign_ids, 1) is null then
        return '{}'::jsonb;
    end if;

    select period_start into v_period_start from public._get_home_stats_period(v_today);

    return (
        select coalesce(jsonb_object_agg(cid::text, jsonb_build_object(
            'installed_count', coalesce(t.installed, 0),
            'today_tested_count', coalesce(t.today_tested, 0),
            'has_tested_today', coalesce(t.has_tested, false),
            'home_tested_count', coalesce(t.home_tested, 0),
            'stats_date', v_today::text
        )), '{}'::jsonb)
        from unnest(p_campaign_ids) as cid
        left join (
            select c.id,
                (select count(*) from public.campaign_members m where m.campaign_id = c.id) as installed,
                (select count(distinct ci.user_id) from public.checkins ci
                  where ci.campaign_id = c.id and ci.checkin_date = v_today) as today_tested,
                (select count(*) from public.checkins ci_period
                  where ci_period.campaign_id = c.id
                    and ci_period.checkin_date >= v_period_start
                    and ci_period.checkin_date <= v_today) as home_tested,
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
