-- ============================================================================
-- 12 Tester — Supabase migration 028: Áp nốt schema còn thiếu (DB live) + fix 027
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Dùng khi DB Supabase hiện tại CHƯA chắc đã chạy đủ các migration cũ
-- (full_schema trước đây thiếu 011 phần trigger/quest, 017, 018, 019, 021).
-- File này gồm: khối bù 011 (phần thiếu) + 017 + 018 + 019 + 021, sau đó là
-- toàn bộ nội dung 027 (bản đã fix) ở cuối — nên chạy 1 lần là đủ và đúng thứ tự.
--
-- An toàn để chạy lặp lại (idempotent): create or replace / if not exists /
-- drop ... if exists / on conflict do nothing. Nếu DB đã đủ migration cũ thì
-- chạy file này vẫn không thay đổi gì ngoài việc áp các fix của 027.
-- ============================================================================

-- ===== 011 (phan con thieu): trigger last_active_date khi nop bao cao =====

-- 3. Trigger tự động cập nhật last_active_date & reset missed_streak ngay khi nộp báo cáo
-- Giúp tester không bị phạt vắng mặt (inactivity penalty) nếu chủ app chậm duyệt báo cáo
create or replace function public.handle_tester_report_submitted()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.profiles
    set last_active_date = (now() at time zone 'Asia/Ho_Chi_Minh')::date,
        missed_streak = 0
    where id = new.user_id;

    return new;
end;
$$;

drop trigger if exists trg_on_tester_report_submitted on public.tester_reports;
create trigger trg_on_tester_report_submitted
    after insert on public.tester_reports
    for each row
    execute function public.handle_tester_report_submitted();

-- ===== 011 (phan con thieu): get_user_quest_summary (dem ca bao cao pending) =====

-- 5. Cập nhật hàm get_user_quest_summary: tính cả số bài test đã nộp hôm nay (checkins + pending tester_reports)
create or replace function public.get_user_quest_summary()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_start date := date_trunc('week', (now() at time zone 'Asia/Ho_Chi_Minh'))::date;
    v_daily_count int := 0;
    v_weekly_count int := 0;
    v_daily_claimed jsonb := '[]'::jsonb;
    v_weekly_claimed jsonb := '[]'::jsonb;
begin
    if v_uid is not null then
        -- Đếm số ứng dụng đã kiểm thử hôm nay (hợp nhất checkins và các báo cáo đã nộp hôm nay)
        select count(distinct campaign_id) into v_daily_count
        from (
            select campaign_id from public.checkins
            where user_id = v_uid and checkin_date = v_today
            union
            select campaign_id from public.tester_reports
            where user_id = v_uid
              and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
              and status in ('pending', 'approved')
        ) t;

        -- Đếm số ứng dụng đã kiểm thử trong tuần này
        select count(distinct campaign_id || '-' || check_date::text) into v_weekly_count
        from (
            select campaign_id, checkin_date as check_date from public.checkins
            where user_id = v_uid and checkin_date >= v_week_start and checkin_date <= v_today
            union
            select campaign_id, (created_at at time zone 'Asia/Ho_Chi_Minh')::date as check_date from public.tester_reports
            where user_id = v_uid
              and (created_at at time zone 'Asia/Ho_Chi_Minh')::date >= v_week_start
              and (created_at at time zone 'Asia/Ho_Chi_Minh')::date <= v_today
              and status in ('pending', 'approved')
        ) w;

        -- Lấy các mốc ngày đã nhận thưởng hôm nay
        select coalesce(jsonb_agg(milestone), '[]'::jsonb) into v_daily_claimed
        from public.user_quest_claims
        where user_id = v_uid and quest_type = 'daily' and period_key = v_today;

        -- Lấy các mốc tuần đã nhận thưởng tuần này
        select coalesce(jsonb_agg(milestone), '[]'::jsonb) into v_weekly_claimed
        from public.user_quest_claims
        where user_id = v_uid and quest_type = 'weekly' and period_key = v_week_start;
    end if;

    return jsonb_build_object(
        'daily_count', v_daily_count,
        'weekly_count', v_weekly_count,
        'daily_claimed', v_daily_claimed,
        'weekly_claimed', v_weekly_claimed,
        'today_date', v_today,
        'week_start_date', v_week_start,
        'daily_milestones', jsonb_build_array(
            jsonb_build_object('milestone', 10, 'reward', 5),
            jsonb_build_object('milestone', 20, 'reward', 15),
            jsonb_build_object('milestone', 50, 'reward', 30)
        ),
        'weekly_milestones', jsonb_build_array(
            jsonb_build_object('milestone', 150, 'reward', 50),
            jsonb_build_object('milestone', 300, 'reward', 100)
        )
    );
end;
$$;


-- ===== 017_home_tabs_campaign_lifecycle =====

-- ============================================================================
-- 12 Tester — Supabase migration 017: Home sub-tabs & Campaign lifecycle
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nghiệp vụ:
-- 1. App sau khi đăng hiển thị tối đa 15 ngày trên tab "App cần Test";
--    hết 15 ngày (tính từ created_at) tự chuyển sang "Đã hoàn thành Kiểm thử".
-- 2. Chủ app bấm "App đã lên production" trước 15 ngày → app cũng chuyển sang
--    "Đã hoàn thành Kiểm thử" (status 'production').
-- 3. Chủ app không hoàn thành nhiệm vụ ngày 3 ngày liên tục → toàn bộ app đang
--    mở của họ tự chuyển sang "App tạm dừng" (paused_reason = 'owner_inactive').
-- 4. App tạm dừng được bật test lại (RPC resume_campaign) khi:
--    - còn trong cửa sổ 15 ngày, và
--    - chủ app đã hoạt động trở lại (missed_streak < 3, tức đã test app hôm nay).
-- ============================================================================


-- ============================================================================
-- 1. Mở rộng trạng thái campaign + cột phục vụ vòng đời
-- ============================================================================

alter table public.campaigns drop constraint if exists campaigns_status_check;
alter table public.campaigns add constraint campaigns_status_check
    check (status in ('open', 'closed', 'paused', 'production', 'completed'));

alter table public.campaigns add column if not exists paused_reason text;
alter table public.campaigns add column if not exists completed_at timestamptz;

create index if not exists idx_campaigns_completed_at on public.campaigns(completed_at desc);

-- Trigger giữ completed_at đồng bộ: set khi sang production/completed, xóa khi mở lại
create or replace function public.trg_campaign_status_lifecycle()
returns trigger
language plpgsql
as $$
begin
    if new.status in ('production', 'completed') then
        if new.completed_at is null then
            new.completed_at := now();
        end if;
    elsif new.status = 'open' then
        new.completed_at := null;
        new.paused_reason := null;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_campaign_status_lifecycle on public.campaigns;
create trigger trg_campaign_status_lifecycle
    before insert or update of status on public.campaigns
    for each row execute function public.trg_campaign_status_lifecycle();

-- Ghi rõ lý do khi tạm dừng do Slot hết hạn (thay vì để trống như trước)
create or replace function public.pause_expired_slot_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count int := 0;
begin
    update public.campaigns c
    set status = 'paused',
        paused_reason = 'slot_expired'
    where c.status = 'open'
      and c.slot_number > 1
      and exists (
          select 1 from public.user_slots s
          where s.user_id = c.owner_id
            and s.slot_number = c.slot_number
            and s.expires_at <= now()
      );
    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

grant execute on function public.pause_expired_slot_campaigns() to authenticated, service_role;


-- ============================================================================
-- 1b. Chặn nộp báo cáo mới cho app không ở trạng thái 'open'
--     (app tạm dừng / đã hoàn thành kiểm thử / đã lên production)
-- ============================================================================

create or replace function public.trg_reports_require_open_campaign()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_status text;
begin
    select status into v_status from public.campaigns where id = new.campaign_id;
    if v_status is not null and v_status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;
    return new;
end;
$$;

drop trigger if exists trg_reports_require_open on public.tester_reports;
create trigger trg_reports_require_open
    before insert on public.tester_reports
    for each row execute function public.trg_reports_require_open_campaign();



-- ============================================================================
-- 2. RLS: người dùng đã đăng nhập xem được cả app đang test, tạm dừng và
--    đã hoàn thành (3 tab nhỏ trên Trang chủ). Khách vẫn chỉ thấy app 'open'.
-- ============================================================================

drop policy if exists "campaigns_select_visible" on public.campaigns;
create policy "campaigns_select_visible" on public.campaigns for select to authenticated
    using (
        status in ('open', 'paused', 'production', 'completed')
        or owner_id = auth.uid()
        or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin')
    );


-- ============================================================================
-- 3. Hàm tự động: tạm dừng app của chủ app vắng mặt 3 ngày liên tục
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
    v_missed int;
begin
    for r in
        update public.campaigns c
        set status = 'paused',
            paused_reason = 'owner_inactive'
        from public.profiles p
        where c.owner_id = p.id
          and c.status = 'open'
          and c.created_at <= now() - interval '24 hours'
          and greatest(p.missed_streak, (current_date - p.last_active_date - 1)) >= 3
        returning c.id, c.owner_id, c.app_name
    loop
        v_count := v_count + 1;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã bị tạm dừng ⏸',
            format(
                'Ứng dụng "%s" đã bị tạm dừng vì bạn không hoàn thành nhiệm vụ ngày trong 3 ngày liên tục. Hãy test 1 app hôm nay, sau đó bấm "Tiếp tục nhận tester" trong màn hình quản lý app để hiển thị lại.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'owner_inactive')
        );
    end loop;

    return v_count;
end;
$$;


-- ============================================================================
-- 4. Hàm tự động: hoàn thành kiểm thử sau 15 ngày hiển thị
-- ============================================================================

create or replace function public.complete_expired_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    v_count int := 0;
begin
    for r in
        update public.campaigns c
        set status = 'completed',
            completed_at = now()
        where c.status in ('open', 'paused')
          and c.created_at <= now() - interval '15 days'
        returning c.id, c.owner_id, c.app_name
    loop
        v_count := v_count + 1;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 15 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'window_expired')
        );
    end loop;

    return v_count;
end;
$$;


-- ============================================================================
-- 5. Hàm tổng hợp: đồng bộ vòng đời campaign (gọi từ app + pg_cron)
-- ============================================================================

create or replace function public.sync_campaign_lifecycle()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_completed int := 0;
    v_paused int := 0;
begin
    v_completed := public.complete_expired_campaigns();
    v_paused := public.pause_inactive_owner_campaigns();
    return jsonb_build_object('completed', v_completed, 'paused', v_paused);
end;
$$;

grant execute on function public.sync_campaign_lifecycle() to authenticated, service_role;


-- ============================================================================
-- 6. RPC: Chủ app bật test lại app đang tạm dừng → về "App cần Test"
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

    -- Quá cửa sổ 15 ngày → chuyển luôn sang hoàn thành
    if v_camp.created_at <= now() - interval '15 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now()
        where id = p_campaign_id;
        raise exception 'CAMPAIGN_WINDOW_EXPIRED';
    end if;

    -- Chủ app vẫn đang vắng 3 ngày liên tục → yêu cầu làm nhiệm vụ ngày trước
    select greatest(missed_streak, (current_date - last_active_date - 1))
    into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 3 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$$;

grant execute on function public.resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 7. Gọi đồng bộ ngay lần đầu để áp dụng luật cho dữ liệu cũ
-- ============================================================================

select public.sync_campaign_lifecycle();


-- ============================================================================
-- 8. Lên lịch đồng bộ vòng đời mỗi giờ bằng pg_cron (bỏ qua nếu chưa bật)
-- ============================================================================

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule(
            'campaign_lifecycle_sync_job',
            '11 * * * *',
            'select public.sync_campaign_lifecycle();'
        );
    else
        raise notice 'pg_cron chưa được bật — app vẫn tự đồng bộ khi mở Trang chủ, nhưng nên bật pg_cron để tự động hoàn thành/tạm dừng đúng giờ.';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;

notify pgrst, 'reload schema';

-- ===== 018_app_version_and_update_config =====

-- ============================================================================
-- 12 Tester — Supabase migration 018: App version & update configuration
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Mục đích:
-- Cho phép cấu hình phiên bản ứng dụng từ xa:
-- 1. min_version_code: Phiên bản build tối thiểu. Nếu máy người dùng < min_version_code
--    hoặc force_update = true (và < latest_version_code) -> bắt buộc cập nhật mới dùng được app.
-- 2. latest_version_code / latest_version_name: Phiên bản mới nhất trên Store.
-- 3. force_update: Cờ bật cưỡng chế cập nhật khẩn cấp.
-- 4. title / message / store_url: Nội dung hiển thị & link tải ứng dụng.
-- ============================================================================

create table if not exists public.app_configs (
    id text primary key default 'android_client',
    min_version_code int not null default 1,
    latest_version_code int not null default 4,
    latest_version_name text not null default '0.2.0',
    force_update boolean not null default false,
    title text default 'Đã có bản cập nhật mới',
    message text default 'Phiên bản mới với nhiều tính năng nâng cấp và sửa lỗi đã sẵn sàng. Vui lòng cập nhật để tiếp tục sử dụng ứng dụng mượt mà nhất.',
    store_url text default 'market://details?id=com.ntt.crosstest',
    updated_at timestamptz default now()
);

-- Bật RLS
alter table public.app_configs enable row level security;

-- Mọi người (cả khách vãng lai và đã đăng nhập) đều được phép đọc cấu hình phiên bản
drop policy if exists "Allow read app_configs to all" on public.app_configs;
create policy "Allow read app_configs to all"
    on public.app_configs
    for select
    using (true);

-- Chỉ admin mới có quyền sửa đổi cấu hình phiên bản
drop policy if exists "Allow update app_configs to admin" on public.app_configs;
create policy "Allow update app_configs to admin"
    on public.app_configs
    for update
    to authenticated
    using (
        exists (
            select 1 from public.profiles
            where profiles.id = auth.uid()
            and profiles.role = 'admin'
        )
    );

-- Tạo hoặc cập nhật bản ghi mặc định cho android_client
insert into public.app_configs (
    id,
    min_version_code,
    latest_version_code,
    latest_version_name,
    force_update,
    title,
    message,
    store_url
)
values (
    'android_client',
    1,
    4,
    '0.2.0',
    false,
    'Đã có bản cập nhật mới',
    'Phiên bản mới với nhiều tính năng nâng cấp và sửa lỗi đã sẵn sàng. Vui lòng cập nhật để tiếp tục sử dụng ứng dụng mượt mà nhất.',
    'market://details?id=com.ntt.crosstest'
)
on conflict (id) do nothing;

-- ===== 019_admin_update_app_config =====

-- ============================================================================
-- 12 Tester — Supabase migration 019: Admin update app config RPC
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Mục đích:
-- Cho phép Admin chỉnh cấu hình cập nhật ứng dụng ngay trong app (Admin Panel)
-- thay vì phải mở Supabase Dashboard.
--
-- RPC: admin_update_app_config(...)
-- - Chỉ tài khoản có role = 'admin' mới gọi được (kiểm tra auth.uid()).
-- - Validate: min >= 1 và latest >= min.
-- - Upsert bản ghi 'android_client' trong public.app_configs.
-- - Trả về nguyên bản ghi dưới dạng jsonb (khớp model AppConfig ở client).
-- ============================================================================

create or replace function public.admin_update_app_config(
    p_min_version_code int,
    p_latest_version_code int,
    p_latest_version_name text,
    p_force_update boolean,
    p_title text default null,
    p_message text default null,
    p_store_url text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_config public.app_configs;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is distinct from 'admin' then
        raise exception 'NOT_ADMIN';
    end if;

    if p_min_version_code < 1 or p_latest_version_code < p_min_version_code then
        raise exception 'INVALID_VERSION_RANGE';
    end if;

    if p_latest_version_name is null or trim(p_latest_version_name) = '' then
        raise exception 'INVALID_VERSION_NAME';
    end if;

    insert into public.app_configs (
        id,
        min_version_code,
        latest_version_code,
        latest_version_name,
        force_update,
        title,
        message,
        store_url,
        updated_at
    )
    values (
        'android_client',
        p_min_version_code,
        p_latest_version_code,
        trim(p_latest_version_name),
        p_force_update,
        p_title,
        p_message,
        p_store_url,
        now()
    )
    on conflict (id) do update set
        min_version_code = excluded.min_version_code,
        latest_version_code = excluded.latest_version_code,
        latest_version_name = excluded.latest_version_name,
        force_update = excluded.force_update,
        title = coalesce(excluded.title, public.app_configs.title),
        message = coalesce(excluded.message, public.app_configs.message),
        store_url = coalesce(excluded.store_url, public.app_configs.store_url),
        updated_at = now()
    returning * into v_config;

    return to_jsonb(v_config);
end;
$$;

-- ===== 021_query_optimization_round2 =====

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

-- ===== 027_fix_owner_tester_flow =====

-- ============================================================================
-- 12 Tester — Supabase migration 027: Fix luồng tương tác Chủ app ↔ Tester
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nội dung sửa:
-- 1. submit_tester_report:
--    - Chặn tài khoản đang bị khóa (is_locked) nộp báo cáo/nhận Credit.
--    - Chặn nộp/nộp lại báo cáo khi campaign KHÔNG ở trạng thái 'open'
--      (trigger trg_reports_require_open chỉ bắt INSERT, nhánh UPDATE lách qua).
--    - Trả về đúng status thật của báo cáo (trước đây trả 'approved' dù DB
--      vẫn 'pending' khi tester đã có checkin xác nhận trong ngày).
--    - Không xóa nội dung/comment cũ khi tester chỉ sửa ảnh (p_content rỗng).
-- 2. Chống farm Credit qua vòng Từ chối → Nộp lại:
--    - Cột mới tester_reports.provisional_clawed ghi số Credit THỰC đã thu hồi
--      khi từ chối (trước đây thu hồi min(số dư, 2) nhưng nộp lại luôn cấp đủ +2
--      → người có số dư thấp lãi +2 mỗi vòng lặp).
--    - Nộp lại chỉ được cấp lại đúng số đã thu hồi (tối đa 2).
-- 3. resume_campaign: bỏ RAISE EXCEPTION sau khi UPDATE 'completed'
--    (exception làm rollback luôn UPDATE → app kẹt 'paused' vĩnh viễn).
--    Trả { success, status: 'completed' } để client hiển thị đúng.
-- 4. sync_campaign_lifecycle: gọi thêm pause_expired_slot_campaigns()
--    (trước đây hàm này bị mồ côi, app hết hạn slot không bao giờ tự tạm dừng).
-- 5. Đồng bộ kiểm tra quyền: admin HOẶC moderator cho approve/reject/
--    get_campaign_reports_for_owner (trước đây moderator thấy UI chủ app
--    nhưng bị PERMISSION_DENIED).
-- 6. apply_report_credit: ghi credit_logs đúng số tiền thực bị kẹp (>= 0).
-- 7. Thu hồi quyền gọi các RPC chỉ dành cho người đã đăng nhập khỏi role anon.
-- ============================================================================


-- ============================================================================
-- 1. Cột theo dõi số Credit đã thu hồi thực tế khi từ chối báo cáo
--    (default 2 = hành vi cũ, an toàn cho dữ liệu lịch sử)
-- ============================================================================
alter table public.tester_reports
    add column if not exists provisional_clawed int not null default 2;


-- ============================================================================
-- 2. submit_tester_report — bản mới nhất (dựa trên migration 024 + fix)
-- ============================================================================
drop function if exists public.submit_tester_report(uuid, text, text, int);

create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5,
    p_image_hash text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_report_id uuid;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp record;
    v_existing_report record;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_regrant int := 0;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    -- Tài khoản bị khóa không được nộp báo cáo/nhận Credit (RLS chỉ chặn ghi
    -- trực tiếp; RPC security definer phải tự kiểm tra).
    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    -- Khớp trigger trg_reports_require_open: nhánh UPDATE (nộp lại/sửa báo cáo)
    -- không đi qua trigger nên phải chặn tại đây.
    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- Khóa tuần tự theo (user, campaign, ngày) — hai request song song không cùng
    -- tạo 2 báo cáo + cộng đôi Credit.
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    -- Tự động thêm tester vào danh sách thành viên nếu chưa tham gia
    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lưu/Cập nhật đánh giá sao và nhận xét trong bảng feedbacks.
    -- Khi tester chỉ sửa ảnh (không nhập lại nội dung) thì GIỮ comment cũ.
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = case
            when coalesce(excluded.comment, '') <> '' then excluded.comment
            else public.feedbacks.comment
        end,
        updated_at = now();

    -- Checkin "xác nhận" hôm nay = checkin không phải tạm tính (note NULL cũng tính là xác nhận)
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
    ) into v_already_checked_in;

    -- Tìm xem hôm nay user đã nộp báo cáo nào cho campaign này chưa
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1;

    if v_existing_report.id is not null then
        -- Trường hợp 1: Báo cáo hôm nay đã được duyệt -> chỉ sửa nội dung/ảnh
        if v_existing_report.status = 'approved' then
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = case
                    when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                        then p_image_hash
                    else coalesce(p_image_hash, image_hash)
                end
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'approved';

        -- Trường hợp 2: Báo cáo hôm nay bị từ chối THẬT (đã được chủ app review) -> Tester nộp lại
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if v_already_checked_in then
                -- Đã nhận 2 Credit từ checkin xác nhận hôm nay -> chỉ mở lại báo cáo, không cấp thêm
                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    provisional_clawed = 0,
                    rejection_reason = null,
                    image_hash = case
                        when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                            then p_image_hash
                        else coalesce(p_image_hash, image_hash)
                    end,
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                returning id into v_report_id;

                v_status := 'pending';
            else
                -- Chỉ cấp lại ĐÚNG số Credit đã bị thu hồi khi từ chối (tối đa 2):
                -- nếu lúc từ chối số dư < 2 thì chỉ thu hồi được bấy nhiêu, nộp lại
                -- cũng chỉ nhận lại bấy nhiêu → không thể farm Credit qua vòng lặp.
                v_regrant := least(2, greatest(0, coalesce(v_existing_report.provisional_clawed, 2)));

                if v_regrant > 0 then
                    update public.profiles
                    set credits = credits + v_regrant,
                        last_active_date = v_today,
                        missed_streak = 0
                    where id = v_user_id
                    returning credits into v_new_balance;

                    insert into public.credit_logs (user_id, amount, action, note, balance)
                    values (
                        v_user_id,
                        v_regrant,
                        'test_provisional',
                        format('Tạm nhận lại %s Credit sau khi nộp lại kết quả test (chờ chủ app duyệt)', v_regrant),
                        v_new_balance
                    );

                    v_provisional_granted := true;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = v_regrant,
                    provisional_clawed = 0,
                    rejection_reason = null,
                    image_hash = case
                        when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                            then p_image_hash
                        else coalesce(p_image_hash, image_hash)
                    end,
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                returning id into v_report_id;

                v_status := 'pending';
            end if;

        -- Trường hợp 3: Báo cáo đang pending (hoặc rejected nhưng chưa được review)
        -- -> chỉ chỉnh sửa nội dung/ảnh, giữ nguyên trạng thái thật
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = case
                    when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                        then p_image_hash
                    else coalesce(p_image_hash, image_hash)
                end
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := v_existing_report.status;
        end if;
    else
        -- Trường hợp 4: Chưa có báo cáo hôm nay
        if v_already_checked_in then
            -- Đã nhận 2 Credit từ checkin xác nhận hôm nay -> chỉ tạo báo cáo, không cộng thêm,
            -- không ghi đè note checkin đã xác nhận.
            insert into public.tester_reports (
                campaign_id,
                user_id,
                report_type,
                content,
                screenshot_url,
                rating,
                status,
                bonus_awarded,
                provisional_clawed,
                image_hash,
                created_at
            )
            values (
                p_campaign_id,
                v_user_id,
                'feedback',
                coalesce(p_content, ''),
                p_screenshot_url,
                v_rating,
                'pending',
                2,
                0,
                p_image_hash,
                now()
            )
            returning id into v_report_id;

            v_status := 'pending';
        else
            -- Tạo mới & tạm nhận 2 Credit
            update public.profiles
            set credits = credits + 2,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id,
                2,
                'test_provisional',
                'Tạm nhận 2 Credit sau khi nộp kết quả test (chờ chủ app duyệt)',
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id,
                user_id,
                report_type,
                content,
                screenshot_url,
                rating,
                status,
                bonus_awarded,
                provisional_clawed,
                image_hash,
                created_at
            )
            values (
                p_campaign_id,
                v_user_id,
                'feedback',
                coalesce(p_content, ''),
                p_screenshot_url,
                v_rating,
                'pending',
                2,
                0,
                p_image_hash,
                now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
        end if;
    end if;

    -- Cập nhật ngày hoạt động gần nhất để chống trừ phạt vắng mặt oan
    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted
    );
end;
$$;

grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, service_role;


-- ============================================================================
-- 3. approve_tester_report — đồng bộ quyền admin/moderator (dựa trên 014)
-- ============================================================================
create or replace function public.approve_tester_report(
    p_report_id uuid,
    p_bonus int default 0
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_report record;
    v_camp record;
    -- Chủ app duyệt thưởng thêm 1 Credit (+ điểm thưởng tự nguyện p_bonus nếu chủ app tặng thêm)
    v_extra_reward int := 1 + greatest(0, coalesce(p_bonus, 0));
    v_total_bonus int;
    v_new_balance int;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Khóa và lấy bản ghi báo cáo
    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    if v_report.status <> 'pending' then
        raise exception 'REPORT_ALREADY_REVIEWED';
    end if;

    -- Kiểm tra quyền của người gọi (chủ app hoặc admin/moderator)
    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Tester đã nhận 2 Credit tạm lúc nộp. Chủ app duyệt: Thưởng thêm v_extra_reward (+1 C)
    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

    -- Thưởng thêm Credit cho tester
    update public.profiles
    set credits = credits + v_extra_reward,
        last_active_date = v_today,
        missed_streak = 0
    where id = v_report.user_id
    returning credits into v_new_balance;

    -- Ghi lịch sử Credit
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_extra_reward,
        'test_approved_bonus',
        format('Chủ app duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)', v_extra_reward),
        v_new_balance
    );

    -- Cập nhật dòng checkin thành chính thức đã duyệt
    update public.checkins
    set note = 'Đã kiểm thử và được chủ app duyệt'
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    if not found then
        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được chủ app duyệt')
        on conflict (campaign_id, user_id, checkin_date)
        do update set note = 'Đã kiểm thử và được chủ app duyệt';
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'message', format('Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester)', v_extra_reward)
    );
end;
$$;

grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;


-- ============================================================================
-- 4. reject_tester_report — ghi nhận số Credit đã thu hồi thực tế (dựa trên 014)
-- ============================================================================
create or replace function public.reject_tester_report(
    p_report_id uuid,
    p_reason text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_report record;
    v_camp record;
    v_tester_credits int;
    v_deduct int := 0;
    v_new_balance int;
    v_report_date date;

    v_tester_today_rejections int := 0;
    v_tester_warned boolean := false;
    v_tester_warning_msg text := null;
    v_tester_weekly_warnings int := 0;
    v_is_tester_locked boolean := false;

    v_owner_today_rejections int := 0;
    v_owner_warned boolean := false;
    v_owner_warning_msg text := null;
    v_owner_weekly_warnings int := 0;
    v_is_owner_locked boolean := false;

    v_msg_tester text := 'Hệ thống ghi nhận bạn tham gia kiểm thử chưa nghiêm túc, đề nghị test app nghiêm túc và chụp ảnh màn hình không trùng với ảnh đã chụp hôm trước (nên chụp các tính năng khác của ứng dụng kiểm thử). Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
    v_msg_owner text := 'Hệ thống ghi nhận bạn xác nhận kết quả của người kiểm thử chưa nghiêm túc, đề nghị đối chiếu kỹ ảnh chụp của người kiểm thử chụp các ngày khác nhau để duyệt cho chính xác. Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Khóa và lấy bản ghi báo cáo
    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    if v_report.status <> 'pending' then
        raise exception 'REPORT_ALREADY_REVIEWED';
    end if;

    -- Kiểm tra quyền chủ app hoặc admin/moderator
    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    -- 1. Thu hồi Credit tạm nhận (đảm bảo không âm điểm nếu số dư < 2).
    --    Ghi lại số THỰC thu hồi vào provisional_clawed để lần nộp lại chỉ
    --    được cấp lại đúng bấy nhiêu (chống farm Credit).
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(coalesce(v_tester_credits, 0), 2);
    if v_deduct > 0 then
        update public.profiles
        set credits = credits - v_deduct
        where id = v_report.user_id
        returning credits into v_new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_report.user_id,
            -v_deduct,
            'test_rejected_clawback',
            format('Thu hồi %s Credit tạm nhận do chủ app từ chối kết quả: %s', v_deduct, coalesce(p_reason, 'Chưa đạt yêu cầu')),
            v_new_balance
        );
    else
        v_new_balance := coalesce(v_tester_credits, 0);
    end if;

    -- 2. Cập nhật trạng thái báo cáo thành rejected
    update public.tester_reports
    set status = 'rejected',
        rejection_reason = p_reason,
        bonus_awarded = 0,
        provisional_clawed = v_deduct,
        reviewed_at = now()
    where id = p_report_id;

    -- 3. Hủy bỏ kết quả ghi nhận tạm thời: Xóa dòng checkin tạm tính
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date;

    -- 4. Kiểm tra cảnh báo cho Tester (3 lần bị từ chối / ngày)
    select count(*) into v_tester_today_rejections
    from public.tester_reports
    where user_id = v_report.user_id
      and status = 'rejected'
      and date(coalesce(reviewed_at, now()) at time zone 'Asia/Ho_Chi_Minh') = date(now() at time zone 'Asia/Ho_Chi_Minh');

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_report.user_id
              and warning_type = 'tester_rejection_streak'
              and warning_date = current_date
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_report.user_id, 'tester_rejection_streak', v_msg_tester, current_date);
            v_tester_warned := true;
            v_tester_warning_msg := v_msg_tester;

            select count(*) into v_tester_weekly_warnings
            from public.user_warnings
            where user_id = v_report.user_id
              and warning_date >= (current_date - 6);

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    -- 5. Kiểm tra cảnh báo cho Chủ app (5 lần từ chối / ngày)
    select count(*) into v_owner_today_rejections
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where c.owner_id = v_caller_id
      and tr.status = 'rejected'
      and date(coalesce(tr.reviewed_at, now()) at time zone 'Asia/Ho_Chi_Minh') = date(now() at time zone 'Asia/Ho_Chi_Minh');

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_caller_id
              and warning_type = 'owner_excessive_rejections'
              and warning_date = current_date
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_caller_id, 'owner_excessive_rejections', v_msg_owner, current_date);
            v_owner_warned := true;
            v_owner_warning_msg := v_msg_owner;

            select count(*) into v_owner_weekly_warnings
            from public.user_warnings
            where user_id = v_caller_id
              and warning_date >= (current_date - 6);

            if v_owner_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_caller_id;
                v_is_owner_locked := true;
            end if;
        end if;
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'rejected',
        'credits_deducted', v_deduct,
        'tester_balance', v_new_balance,
        'tester_today_rejections', v_tester_today_rejections,
        'tester_warned', v_tester_warned,
        'tester_warning_msg', v_tester_warning_msg,
        'is_tester_locked', v_is_tester_locked,
        'owner_today_rejections', v_owner_today_rejections,
        'owner_warned', v_owner_warned,
        'owner_warning_msg', v_owner_warning_msg,
        'is_owner_locked', v_is_owner_locked
    );
end;
$$;

grant execute on function public.reject_tester_report(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 5. resume_campaign — không dùng RAISE sau UPDATE (tránh rollback)
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

    -- Quá cửa sổ 15 ngày → chuyển sang hoàn thành và trả kết quả (KHÔNG raise,
    -- vì exception sẽ rollback luôn UPDATE khiến app kẹt 'paused').
    if v_camp.created_at <= now() - interval '15 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'completed'
        );
    end if;

    -- Chủ app vẫn đang vắng 3 ngày liên tục → yêu cầu làm nhiệm vụ ngày trước
    select greatest(missed_streak, (current_date - last_active_date - 1))
    into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 3 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$$;

grant execute on function public.resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 6. sync_campaign_lifecycle — gọi thêm pause_expired_slot_campaigns
-- ============================================================================
create or replace function public.sync_campaign_lifecycle()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_completed int := 0;
    v_paused int := 0;
    v_slot_paused int := 0;
begin
    v_completed := public.complete_expired_campaigns();
    v_paused := public.pause_inactive_owner_campaigns();
    -- Trước đây hàm này bị mồ côi: app có slot hết hạn không bao giờ tự tạm dừng.
    v_slot_paused := public.pause_expired_slot_campaigns();
    return jsonb_build_object(
        'completed', v_completed,
        'paused', v_paused,
        'slot_paused', v_slot_paused
    );
end;
$$;

grant execute on function public.sync_campaign_lifecycle() to authenticated, service_role;


-- ============================================================================
-- 7. get_campaign_reports_for_owner — đồng bộ quyền admin/moderator (dựa trên 016)
-- ============================================================================
create or replace function public.get_campaign_reports_for_owner(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_res jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role into v_role from public.profiles where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Tự động duyệt bất kỳ báo cáo nào quá hạn 8 giờ
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
                'rating', tr.rating,
                'status', tr.status,
                'bonus_awarded', tr.bonus_awarded,
                'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'rejection_reason', tr.rejection_reason,
                'image_hash', tr.image_hash,
                'tester_name', coalesce(p.display_name, split_part(coalesce(p.email, ''), '@', 1), 'Tester'),
                'tester_avatar', p.avatar_url,
                'tester_credits', p.credits
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
$$;

grant execute on function public.get_campaign_reports_for_owner(uuid) to authenticated, service_role;


-- ============================================================================
-- 8. apply_report_credit — ghi log đúng số tiền thực bị kẹp (dựa trên 022)
-- ============================================================================
create or replace function public.apply_report_credit(
    p_report_id uuid,
    p_amount int,
    p_action text default '',
    p_note text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller uuid := auth.uid();
    v_role text;
    v_target uuid;
    v_owner uuid;
    v_current int;
    v_new int;
    v_actual int;
begin
    if v_caller is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select tr.user_id, c.owner_id
      into v_target, v_owner
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where tr.id = p_report_id;

    if v_target is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    select role into v_role
    from public.profiles
    where id = v_caller;

    if v_owner is distinct from v_caller
       and lower(coalesce(v_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Khoá dòng profile mục tiêu: hai lượt cộng/trừ song song không mất cập nhật.
    select credits into v_current
    from public.profiles
    where id = v_target
    for update;

    if v_current is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_new := greatest(v_current + p_amount, 0);
    -- Số tiền thực thay đổi (đã bị kẹp ở mức >= 0) — khớp với số dư mới.
    v_actual := v_new - v_current;

    update public.profiles
    set credits = v_new
    where id = v_target;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_target,
        v_actual,
        coalesce(nullif(trim(p_action), ''), 'report_adjust'),
        p_note,
        v_new
    );

    return jsonb_build_object(
        'success', true,
        'user_id', v_target,
        'previous_credits', v_current,
        'new_credits', v_new,
        'amount', v_actual
    );
end;
$$;

grant execute on function public.apply_report_credit(uuid, int, text, text) to authenticated, service_role;


-- ============================================================================
-- 9. Thu hồi quyền gọi RPC chỉ dành cho người đã đăng nhập khỏi role anon
-- ============================================================================
do $$
declare
    v_sig text;
begin
    foreach v_sig in array array[
        'public.submit_tester_report(uuid, text, text, int, text)',
        'public.submit_tester_report(uuid, text, text, int)',
        'public.approve_tester_report(uuid, int)',
        'public.reject_tester_report(uuid, text)',
        'public.auto_accept_expired_reports()',
        'public.get_campaign_reports_for_owner(uuid)',
        'public.unlock_or_renew_slot(int)'
    ]
    loop
        if to_regprocedure(v_sig) is not null then
            execute format('revoke execute on function %s from anon, public', v_sig);
            -- Cấp lại quyền cho authenticated/service_role: trước đây các hàm như
            -- auto_accept_expired_reports / unlock_or_renew_slot chỉ sống nhờ quyền
            -- PUBLIC mặc định của Postgres (full_schema không grant tường minh);
            -- nếu chỉ revoke mà không grant lại thì DB dựng mới sẽ hỏng quyền gọi.
            execute format('grant execute on function %s to authenticated, service_role', v_sig);
        end if;
    end loop;
end $$;


notify pgrst, 'reload schema';