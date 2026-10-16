-- ============================================================================
-- 12 Tester — TOÀN BỘ SCHEMA HỢP NHẤT CHO PROJECT MỚI (SINGAPORE)
-- File này gộp toàn bộ 18 file migration (001 -> 014) thành 1 bản duy nhất.
-- Cách dùng:
-- 1. Vào Supabase Dashboard (Singapore) > SQL Editor > New query
-- 2. Dán toàn bộ nội dung file này vào và bấm Run.
-- ============================================================================

-- Bật các extension cần thiết
create extension if not exists "pgcrypto";
create extension if not exists "uuid-ossp";

-- ============================================================================
-- 1. TẠO TOÀN BỘ CÁC BẢNG (TABLES)
-- ============================================================================

-- 1.1. Bảng profiles (Hồ sơ người dùng)
create table if not exists public.profiles (
    id               uuid primary key references auth.users(id) on delete cascade,
    email            text,
    display_name     text,
    avatar_url       text,
    credits          int not null default 100 check (credits >= 0),
    last_active_date date not null default current_date,
    missed_streak    int not null default 0,
    role             text not null default 'member' check (lower(role) in ('member', 'moderator', 'admin')),
    is_locked        boolean not null default false,
    lock_reason      text,
    created_at       timestamptz not null default now()
);

create index if not exists idx_profiles_role on public.profiles(role);
create index if not exists idx_profiles_is_locked on public.profiles(is_locked);
create index if not exists idx_profiles_credits on public.profiles(credits desc);

-- 1.2. Bảng campaigns (Chiến dịch test app)
create table if not exists public.campaigns (
    id                uuid primary key default gen_random_uuid(),
    owner_id          uuid not null references public.profiles(id) on delete cascade,
    app_name          text not null default '',
    package_name      text not null,
    play_url          text not null default '',
    testing_url       text not null default '',
    description       text not null default '',
    target_testers    int not null default 12,
    target_days       int not null default 14,
    start_date        date not null default current_date,
    status            text not null default 'open' check (status in ('open', 'closed', 'paused', 'production')),
    icon_url          text not null default '',
    test_username     text default '',
    test_password     text default '',
    slot_number       int not null default 1 check (slot_number between 1 and 6),
    tip_credits       int not null default 0,
    is_boosted        boolean not null default false,
    auto_accept_hours int not null default 8,
    created_at        timestamptz not null default now()
);

create index if not exists idx_campaigns_status on public.campaigns(status);
create index if not exists idx_campaigns_owner on public.campaigns(owner_id);
create index if not exists idx_campaigns_owner_slot on public.campaigns(owner_id, slot_number);
create index if not exists idx_campaigns_created_at on public.campaigns(created_at desc);

-- 1.3. Bảng campaign_members (Danh sách tester tham gia)
create table if not exists public.campaign_members (
    campaign_id uuid not null references public.campaigns(id) on delete cascade,
    user_id     uuid not null references public.profiles(id) on delete cascade,
    joined_at   timestamptz not null default now(),
    primary key (campaign_id, user_id)
);

create index if not exists idx_campaign_members_user on public.campaign_members(user_id);

-- 1.4. Bảng checkins (Lượt test mỗi ngày - chống trùng)
create table if not exists public.checkins (
    id           uuid primary key default gen_random_uuid(),
    campaign_id  uuid not null references public.campaigns(id) on delete cascade,
    user_id      uuid not null references public.profiles(id) on delete cascade,
    checkin_date date not null default current_date,
    note         text,
    created_at   timestamptz not null default now(),
    unique (campaign_id, user_id, checkin_date)
);

create index if not exists idx_checkins_campaign on public.checkins(campaign_id);
create index if not exists idx_checkins_user_date on public.checkins(user_id, checkin_date);

-- 1.5. Bảng feedbacks (Đánh giá sao & góp ý)
create table if not exists public.feedbacks (
    id          uuid primary key default gen_random_uuid(),
    campaign_id uuid not null references public.campaigns(id) on delete cascade,
    user_id     uuid not null references public.profiles(id) on delete cascade,
    rating      int not null check (rating between 1 and 5),
    comment     text not null default '',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (campaign_id, user_id)
);

create index if not exists idx_feedbacks_campaign on public.feedbacks(campaign_id);

-- 1.6. Bảng credit_logs (Lịch sử biến động Credit)
create table if not exists public.credit_logs (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references public.profiles(id) on delete cascade,
    amount     int not null,
    action     text not null,
    note       text,
    balance    int not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_credit_logs_user on public.credit_logs(user_id);
create index if not exists idx_credit_logs_created on public.credit_logs(created_at desc);

-- 1.7. Bảng tester_reports (Báo cáo kết quả kiểm thử kèm ảnh)
create table if not exists public.tester_reports (
    id               uuid primary key default gen_random_uuid(),
    campaign_id      uuid not null references public.campaigns(id) on delete cascade,
    user_id          uuid not null references public.profiles(id) on delete cascade,
    report_type      text not null default 'feedback' check (report_type in ('daily_test', 'bug_report', 'feedback')),
    content          text not null default '',
    screenshot_url   text,
    rating           int check (rating between 1 and 5),
    status           text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
    bonus_awarded    int not null default 0,
    rejection_reason text,
    image_hash       text,
    created_at       timestamptz not null default now(),
    reviewed_at      timestamptz
);

create index if not exists idx_tester_reports_campaign on public.tester_reports(campaign_id);
create index if not exists idx_tester_reports_user on public.tester_reports(user_id);
create index if not exists idx_tester_reports_status on public.tester_reports(status);
create index if not exists idx_tester_reports_created on public.tester_reports(created_at desc);
create index if not exists idx_tester_reports_image_hash on public.tester_reports(image_hash);

-- 1.8. Bảng user_warnings (Cảnh báo vi phạm kiểm thử)
create table if not exists public.user_warnings (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references public.profiles(id) on delete cascade,
    warning_type text not null check (warning_type in ('tester_rejection_streak', 'owner_excessive_rejections', 'admin_manual')),
    message      text not null,
    warning_date date not null default current_date,
    acknowledged boolean not null default false,
    created_at   timestamptz not null default now()
);

create index if not exists idx_user_warnings_user_date on public.user_warnings(user_id, warning_date);
create index if not exists idx_user_warnings_user_created on public.user_warnings(user_id, created_at desc);

-- 1.9. Bảng user_slots (Mở khóa slot đăng app đa nhiệm 2-6)
create table if not exists public.user_slots (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references public.profiles(id) on delete cascade,
    slot_number int not null check (slot_number between 2 and 6),
    unlocked_at timestamptz not null default now(),
    expires_at  timestamptz not null,
    created_at  timestamptz not null default now(),
    unique (user_id, slot_number)
);

create index if not exists idx_user_slots_user on public.user_slots(user_id);
create index if not exists idx_user_slots_expires on public.user_slots(expires_at);

-- 1.10. Bảng topup_packages (Gói nạp Credit)
create table if not exists public.topup_packages (
    id            text primary key,
    title         text not null,
    credits       int not null,
    bonus_credits int not null default 0,
    price_usd     numeric(10, 2) not null,
    price_vnd     numeric(12, 0) not null default 0,
    badge_text    text,
    is_popular    boolean not null default false,
    is_active     boolean not null default true,
    sort_order    int not null default 0,
    created_at    timestamptz not null default now()
);

-- Seed các gói nạp mặc định
insert into public.topup_packages (id, title, credits, bonus_credits, price_usd, price_vnd, badge_text, is_popular, is_active, sort_order)
values
    ('credit_pack_starter_20', 'Gói Khởi Đầu', 20, 0, 0.99, 25000, null, false, true, 1),
    ('credit_pack_basic_50', 'Gói Tiêu Chuẩn', 50, 0, 2.49, 62000, null, false, true, 2),
    ('credit_pack_popular_100', 'Gói Phổ Biến', 100, 5, 4.99, 125000, 'Phổ biến nhất (+5%)', true, true, 3),
    ('credit_pack_pro_200', 'Gói Nâng Cao', 200, 20, 9.99, 250000, 'Tiết kiệm (+10%)', false, true, 4),
    ('credit_pack_master_400', 'Gói Doanh Nghiệp', 400, 80, 19.99, 500000, 'Siêu ưu đãi (+20%)', false, true, 5)
on conflict (id) do update set
    title = excluded.title,
    credits = excluded.credits,
    bonus_credits = excluded.bonus_credits,
    price_usd = excluded.price_usd,
    price_vnd = excluded.price_vnd,
    badge_text = excluded.badge_text,
    is_popular = excluded.is_popular,
    is_active = excluded.is_active,
    sort_order = excluded.sort_order;

-- 1.11. Bảng topup_orders (Lịch sử giao dịch nạp Credit)
create table if not exists public.topup_orders (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references public.profiles(id) on delete cascade,
    package_id      text references public.topup_packages(id) on delete set null,
    credits_awarded int not null,
    price_usd       numeric(10, 2) not null,
    price_vnd       numeric(12, 0) not null default 0,
    payment_method  text not null default 'google_play',
    payment_status  text not null default 'pending' check (payment_status in ('pending', 'completed', 'failed', 'cancelled')),
    purchase_token  text,
    order_reference text,
    raw_receipt     text,
    created_at      timestamptz not null default now(),
    completed_at    timestamptz
);

create index if not exists idx_topup_orders_user on public.topup_orders(user_id);
create index if not exists idx_topup_orders_token on public.topup_orders(purchase_token);

-- 1.12. Bảng notifications (Trung tâm thông báo)
create table if not exists public.notifications (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references public.profiles(id) on delete cascade,
    title      text not null,
    message    text not null,
    type       text not null default 'general',
    data       jsonb default '{}'::jsonb,
    is_read    boolean not null default false,
    created_at timestamptz not null default now()
);

create index if not exists idx_notifications_user_read on public.notifications(user_id, is_read);
create index if not exists idx_notifications_user_created on public.notifications(user_id, created_at desc);

-- 1.13. Bảng user_quest_claims (Theo dõi nhận thưởng nhiệm vụ)
create table if not exists public.user_quest_claims (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid not null references public.profiles(id) on delete cascade,
    quest_type     text not null check (quest_type in ('daily', 'weekly')),
    milestone      int not null,
    reward_credits int not null,
    period_key     date not null,
    claimed_at     timestamptz not null default now(),
    constraint uq_user_quest_claim unique (user_id, quest_type, milestone, period_key)
);

create index if not exists idx_user_quest_claims_lookup on public.user_quest_claims(user_id, quest_type, period_key);


-- ============================================================================
-- 2. ROW LEVEL SECURITY (RLS POLICIES)
-- ============================================================================

alter table public.profiles          enable row level security;
alter table public.campaigns         enable row level security;
alter table public.campaign_members  enable row level security;
alter table public.checkins          enable row level security;
alter table public.feedbacks         enable row level security;
alter table public.credit_logs       enable row level security;
alter table public.tester_reports    enable row level security;
alter table public.user_warnings     enable row level security;
alter table public.user_slots        enable row level security;
alter table public.topup_packages    enable row level security;
alter table public.topup_orders      enable row level security;
alter table public.notifications     enable row level security;
alter table public.user_quest_claims enable row level security;

-- profiles
drop policy if exists "profiles_select_all" on public.profiles;
create policy "profiles_select_all" on public.profiles for select to anon, authenticated using (true);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles for insert to authenticated with check (id = auth.uid());

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- campaigns
drop policy if exists "campaigns_select_anon" on public.campaigns;
create policy "campaigns_select_anon" on public.campaigns for select to anon using (status = 'open');

drop policy if exists "campaigns_select_visible" on public.campaigns;
create policy "campaigns_select_visible" on public.campaigns for select to authenticated
    using (status = 'open' or owner_id = auth.uid() or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin'));

drop policy if exists "campaigns_insert_own" on public.campaigns;
create policy "campaigns_insert_own" on public.campaigns for insert to authenticated
    with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.profiles
            where id = auth.uid() and credits > 10 and is_locked = false
        )
    );

drop policy if exists "campaigns_update_owner" on public.campaigns;
create policy "campaigns_update_owner" on public.campaigns for update to authenticated
    using (owner_id = auth.uid() or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin'))
    with check (owner_id = auth.uid() or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin'));

drop policy if exists "campaigns_delete_owner_or_admin" on public.campaigns;
create policy "campaigns_delete_owner_or_admin" on public.campaigns for delete to authenticated
    using (owner_id = auth.uid() or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin'));

-- campaign_members
drop policy if exists "members_select_anon" on public.campaign_members;
create policy "members_select_anon" on public.campaign_members for select to anon using (true);

drop policy if exists "members_select_all" on public.campaign_members;
create policy "members_select_all" on public.campaign_members for select to authenticated using (true);

drop policy if exists "members_insert_own" on public.campaign_members;
create policy "members_insert_own" on public.campaign_members for insert to authenticated with check (user_id = auth.uid());

drop policy if exists "members_delete_own_or_owner" on public.campaign_members;
create policy "members_delete_own_or_owner" on public.campaign_members for delete to authenticated
    using (
        user_id = auth.uid()
        or exists (select 1 from public.campaigns c where c.id = campaign_members.campaign_id and c.owner_id = auth.uid())
    );

-- checkins
drop policy if exists "checkins_select_all" on public.checkins;
create policy "checkins_select_all" on public.checkins for select to anon, authenticated using (true);

drop policy if exists "checkins_insert_own" on public.checkins;
create policy "checkins_insert_own" on public.checkins for insert to authenticated
    with check (
        user_id = auth.uid()
        and exists (select 1 from public.profiles where id = auth.uid() and is_locked = false)
    );

-- feedbacks
drop policy if exists "feedbacks_select_anon" on public.feedbacks;
create policy "feedbacks_select_anon" on public.feedbacks for select to anon using (true);

drop policy if exists "feedbacks_select_all" on public.feedbacks;
create policy "feedbacks_select_all" on public.feedbacks for select to authenticated using (true);

drop policy if exists "feedbacks_insert_own" on public.feedbacks;
create policy "feedbacks_insert_own" on public.feedbacks for insert to authenticated with check (user_id = auth.uid());

drop policy if exists "feedbacks_update_own" on public.feedbacks;
create policy "feedbacks_update_own" on public.feedbacks for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

-- credit_logs
drop policy if exists "credit_logs_select_own" on public.credit_logs;
create policy "credit_logs_select_own" on public.credit_logs for select to authenticated
    using (user_id = auth.uid() or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin'));

-- tester_reports
drop policy if exists "reports_select_own_or_owner" on public.tester_reports;
create policy "reports_select_own_or_owner" on public.tester_reports for select to authenticated
    using (
        user_id = auth.uid()
        or exists (select 1 from public.campaigns c where c.id = tester_reports.campaign_id and c.owner_id = auth.uid())
        or exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
    );

drop policy if exists "reports_insert_tester" on public.tester_reports;
create policy "reports_insert_tester" on public.tester_reports for insert to authenticated with check (user_id = auth.uid());

drop policy if exists "reports_update_tester_or_owner" on public.tester_reports;
create policy "reports_update_tester_or_owner" on public.tester_reports for update to authenticated
    using (
        user_id = auth.uid()
        or exists (select 1 from public.campaigns c where c.id = tester_reports.campaign_id and c.owner_id = auth.uid())
        or exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
    );

-- user_warnings
drop policy if exists "warnings_select_own" on public.user_warnings;
create policy "warnings_select_own" on public.user_warnings for select to authenticated using (user_id = auth.uid());

drop policy if exists "warnings_update_own" on public.user_warnings;
create policy "warnings_update_own" on public.user_warnings for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

-- user_slots
drop policy if exists "user_slots_select_own" on public.user_slots;
create policy "user_slots_select_own" on public.user_slots for select to authenticated using (user_id = auth.uid());

-- topup_packages
drop policy if exists "topup_packages_read_all" on public.topup_packages;
create policy "topup_packages_read_all" on public.topup_packages for select using (is_active = true);

-- topup_orders
drop policy if exists "topup_orders_select_own" on public.topup_orders;
create policy "topup_orders_select_own" on public.topup_orders for select to authenticated
    using (user_id = auth.uid() or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin'));

-- notifications
drop policy if exists "notifications_select_own" on public.notifications;
create policy "notifications_select_own" on public.notifications for select to authenticated using (user_id = auth.uid());

drop policy if exists "notifications_update_own" on public.notifications;
create policy "notifications_update_own" on public.notifications for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "notifications_delete_own" on public.notifications;
create policy "notifications_delete_own" on public.notifications for delete to authenticated using (user_id = auth.uid());

drop policy if exists "notifications_insert_all" on public.notifications;
create policy "notifications_insert_all" on public.notifications for insert to authenticated with check (true);

-- user_quest_claims
drop policy if exists "quest_claims_select_own" on public.user_quest_claims;
create policy "quest_claims_select_own" on public.user_quest_claims for select to authenticated using (user_id = auth.uid());


-- ============================================================================
-- 3. TRIGGERS VÀ HÀM TRIGGER CỦA HỆ THỐNG
-- ============================================================================

-- 3.1. Tự động xác thực email (Auto-confirm email) khi tạo tài khoản
create or replace function public.auto_confirm_user()
returns trigger 
language plpgsql 
security definer 
set search_path = public, auth
as $$
begin
    if new.email_confirmed_at is null then
        new.email_confirmed_at := now();
    end if;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created_auto_confirm on auth.users;
create trigger on_auth_user_created_auto_confirm
    before insert on auth.users
    for each row
    execute function public.auto_confirm_user();

-- 3.2. Tự động tạo bản ghi Profile và tặng 100 Credit chào mừng
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (
        id,
        email,
        display_name,
        avatar_url,
        credits,
        last_active_date,
        missed_streak,
        role,
        is_locked
    )
    values (
        new.id,
        new.email,
        coalesce(
            new.raw_user_meta_data ->> 'full_name',
            new.raw_user_meta_data ->> 'name',
            new.raw_user_meta_data ->> 'display_name',
            split_part(coalesce(new.email, ''), '@', 1)
        ),
        coalesce(
            new.raw_user_meta_data ->> 'avatar_url',
            new.raw_user_meta_data ->> 'picture'
        ),
        100,
        current_date,
        0,
        'member',
        false
    )
    on conflict (id) do nothing;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        new.id,
        100,
        'welcome',
        'Thưởng 100 Credit khi mở tài khoản',
        100
    )
    on conflict do nothing;

    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- 3.3. Thưởng điểm kiểm thử cơ bản & cập nhật chuỗi hoạt động
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

    -- Bỏ qua nếu là checkin được tạo từ duyệt báo cáo / tạm tính
    if new.note is not null and (
        new.note like 'Đã kiểm thử%' or
        new.note like 'Tự động duyệt%' or
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

drop trigger if exists on_checkin_reward_trigger on public.checkins;
create trigger on_checkin_reward_trigger
    after insert on public.checkins
    for each row execute function public.handle_checkin_reward();

-- 3.4. Trigger tự động trừ phí khi người đăng chọn mục tiêu cao hơn (15 hoặc 20 Test/ngày)
create or replace function public.handle_campaign_target_cost()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_cost int := 0;
    v_balance int;
begin
    if new.target_testers = 15 then
        v_cost := 10;
    elsif new.target_testers = 20 then
        v_cost := 20;
    else
        new.target_testers := 12;
        v_cost := 0;
    end if;

    if v_cost > 0 then
        select credits into v_balance
        from public.profiles
        where id = new.owner_id
        for update;

        if v_balance is null or v_balance < v_cost then
            raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần ít nhất % Credit để đăng app với mục tiêu % Test/ngày', v_cost, new.target_testers;
        end if;

        update public.profiles
        set credits = credits - v_cost
        where id = new.owner_id
        returning credits into v_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            new.owner_id,
            -v_cost,
            'target_upgrade',
            format('Nâng cấp mục tiêu %s Test/ngày (-%s Credit)', new.target_testers, v_cost),
            v_balance
        );
    end if;

    return new;
end;
$$;

drop trigger if exists trg_on_campaign_target_cost on public.campaigns;
create trigger trg_on_campaign_target_cost
    before insert on public.campaigns
    for each row
    execute function public.handle_campaign_target_cost();

-- 3.5. Kiểm tra tính hợp lệ của slot đăng app
create or replace function public.check_campaign_slot_validity()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if new.status = 'open' then
        if new.slot_number > 1 then
            if not exists (
                select 1 from public.user_slots
                where user_id = new.owner_id
                  and slot_number = new.slot_number
                  and expires_at > now()
            ) then
                raise exception 'SLOT_NOT_ACTIVE_OR_EXPIRED';
            end if;
        end if;

        if exists (
            select 1 from public.campaigns
            where owner_id = new.owner_id
              and slot_number = new.slot_number
              and status = 'open'
              and id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) then
            raise exception 'SLOT_ALREADY_IN_USE';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_check_campaign_slot on public.campaigns;
create trigger trg_check_campaign_slot
    before insert or update on public.campaigns
    for each row execute function public.check_campaign_slot_validity();

-- 3.6. Trigger bắn thông báo liên quan đến báo cáo kiểm thử
create or replace function public.trg_notify_tester_report()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
begin
    -- auto_accept_expired_reports tự gửi thông báo riêng -> không bắn thông báo trùng
    if coalesce(current_setting('app.suppress_report_notify', true), '') = '1' then
        return new;
    end if;

    if tg_op = 'INSERT' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo kiểm thử mới 📋',
                format('Ứng dụng "%s" vừa nhận được 1 báo cáo kiểm thử mới. Bạn có 8 giờ để kiểm tra và duyệt (+1 Credit thưởng cho tester nếu duyệt).', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending')
            );
        end if;

    elsif tg_op = 'UPDATE' and old.status = 'rejected' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo nộp lại 📋',
                format('Tester vừa nộp lại kết quả kiểm thử cho ứng dụng "%s" sau khi bị từ chối. Bạn có 8 giờ để kiểm tra và duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending', 'is_resubmission', true)
            );
        end if;

    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử được phê duyệt! 🎉',
                format('Báo cáo của bạn cho ứng dụng "%s" đã được chủ app duyệt thành công! Bạn nhận được thêm +%s Credit thưởng (tổng cộng %s Credit). Cảm ơn bạn!',
                    coalesce(v_camp_name, 'ứng dụng'),
                    greatest(1, coalesce(new.bonus_awarded, 3) - 2),
                    coalesce(new.bonus_awarded, 3)
                ),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'approved')
            );
        elsif new.status = 'rejected' then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                case
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Lý do: "%s". 2 Credit tạm nhận đã bị thu hồi. Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. 2 Credit tạm nhận đã bị thu hồi. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'))
                end,
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'rejected', 'reason', new.rejection_reason)
            );
        end if;
    end if;

    return new;
exception when others then
    return new;
end;
$$;

drop trigger if exists trg_on_tester_report_event on public.tester_reports;
create trigger trg_on_tester_report_event
    after insert or update on public.tester_reports
    for each row
    execute function public.trg_notify_tester_report();


-- ============================================================================
-- 4. STORED PROCEDURES (RPCs) NGHIỆP VỤ LÕI
-- ============================================================================

-- 4.1. RPC: Nộp báo cáo kiểm thử (Tạm nhận 2 Credit & Ghi nhận checkin tạm tính)
-- Loại bỏ phiên bản 4 tham số cũ (nếu còn) để PostgREST không nhập nhằng khi chọn hàm.
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

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = excluded.comment,
        updated_at = now();

    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and note not like '%tạm tính%'
    ) into v_already_checked_in;

    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1;

    if v_existing_report.id is not null then
        if v_existing_report.status = 'approved' or v_already_checked_in then
            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = coalesce(p_image_hash, image_hash)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'approved';

        elsif v_existing_report.status = 'rejected' then
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
                'Tạm nhận 2 Credit sau khi nộp lại kết quả test (chờ chủ app duyệt)',
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                status = 'pending',
                bonus_awarded = 2,
                rejection_reason = null,
                image_hash = coalesce(p_image_hash, image_hash),
                created_at = now(),
                reviewed_at = null
            where id = v_existing_report.id
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
        else
            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = coalesce(p_image_hash, image_hash)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'pending';
        end if;
    else
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
            p_image_hash,
            now()
        )
        returning id into v_report_id;

        v_status := 'pending';
        v_provisional_granted := true;
    end if;

    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_credits', case when v_provisional_granted then 2 else 0 end
    );
end;
$$;

-- 4.2. RPC: Chủ app duyệt báo cáo (Thưởng thêm +1 Credit & xác nhận checkin)
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
    v_extra_reward int := 1 + greatest(0, coalesce(p_bonus, 0));
    v_total_bonus int;
    v_new_balance int;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

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

    select * into v_camp from public.campaigns where id = v_report.campaign_id;
    select role into v_caller_role from public.profiles where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id and coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

    update public.profiles
    set credits = credits + v_extra_reward,
        last_active_date = v_today,
        missed_streak = 0
    where id = v_report.user_id
    returning credits into v_new_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_extra_reward,
        'test_approved_bonus',
        format('Chủ app duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)', v_extra_reward),
        v_new_balance
    );

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

-- 4.3. RPC: Từ chối báo cáo kiểm thử (Thu hồi 2 Credit tạm nhận, cảnh báo)
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

    v_msg_tester text := 'Hệ thống ghi nhận bạn tham gia kiểm thử chưa nghiêm túc, đề nghị test app nghiêm túc và chụp ảnh màn hình không trùng với ảnh đã chụp hôm trước. Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
    v_msg_owner text := 'Hệ thống ghi nhận bạn xác nhận kết quả của người kiểm thử chưa nghiêm túc, đề nghị đối chiếu kỹ ảnh chụp của người kiểm thử chụp các ngày khác nhau để duyệt cho chính xác. Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

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

    select * into v_camp from public.campaigns where id = v_report.campaign_id;
    select role into v_caller_role from public.profiles where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id and coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    update public.tester_reports
    set status = 'rejected',
        rejection_reason = p_reason,
        bonus_awarded = 0,
        reviewed_at = now()
    where id = p_report_id;

    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date;

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

-- 4.4. RPC: Tự động duyệt báo cáo quá hạn 8 giờ
create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_count int := 0;
    v_camp_name text;
begin
    -- Chặn trigger trg_on_tester_report_event bắn thông báo "được duyệt +1 Credit" sai:
    -- hàm này tự gửi thông báo "tự động duyệt" cho tester bên dưới.
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        update public.tester_reports
        set status = 'approved',
            bonus_awarded = coalesce(v_report.bonus_awarded, 2),
            reviewed_at = now()
        where id = v_report.id;

        update public.checkins
        set note = 'Hệ thống tự động duyệt sau 8h'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (
                v_report.campaign_id,
                v_report.user_id,
                (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                'Hệ thống tự động duyệt sau 8h'
            )
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Hệ thống tự động duyệt sau 8h';
        end if;

        select app_name into v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên 2 Credit đã nhận). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng')),
            'report',
            jsonb_build_object('campaign_id', v_report.campaign_id, 'report_id', v_report.id, 'status', 'approved', 'auto_accepted', true)
        );

        v_count := v_count + 1;
    end loop;

    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

-- 4.5. RPC: Lấy danh sách báo cáo cho chủ app (Bypass RLS)
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

    if v_camp.owner_id <> v_caller_id and coalesce(v_role, '') <> 'admin' then
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

-- 4.6. RPC: Tự mở khóa tài khoản (Trừ 100 Credit)
create or replace function public.self_unlock_account()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_current_credits int;
    v_new_balance int;
    v_is_locked boolean;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select credits, is_locked into v_current_credits, v_is_locked
    from public.profiles
    where id = v_caller_id
    for update;

    if v_is_locked is not true then
        raise exception 'ACCOUNT_NOT_LOCKED';
    end if;

    if v_current_credits is null or v_current_credits < 100 then
        raise exception 'INSUFFICIENT_CREDITS: Cần tối thiểu 100 Credit để mở khóa tài khoản';
    end if;

    v_new_balance := v_current_credits - 100;

    update public.profiles
    set credits = v_new_balance,
        is_locked = false
    where id = v_caller_id;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_caller_id,
        -100,
        'self_unlock_penalty',
        'Trừ 100 Credit để mở khóa tài khoản',
        v_new_balance
    );

    update public.user_warnings
    set acknowledged = true
    where user_id = v_caller_id
      and warning_date >= (current_date - 6);

    return jsonb_build_object(
        'success', true,
        'user_id', v_caller_id,
        'previous_credits', v_current_credits,
        'new_credits', v_new_balance,
        'is_locked', false
    );
end;
$$;

-- 4.7. RPC: Mở khóa hoặc Gia hạn Slot đăng app (16 ngày)
create or replace function public.unlock_or_renew_slot(p_slot_number int)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_cost int;
    v_balance int;
    v_current_expires timestamptz;
    v_new_expires timestamptz;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_slot_number < 2 or p_slot_number > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    if p_slot_number = 2 then
        v_cost := 50;
    elsif p_slot_number = 3 then
        v_cost := 100;
    else
        v_cost := 200;
    end if;

    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    if v_balance is null or v_balance < v_cost then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    if p_slot_number > 2 then
        if not exists (
            select 1 from public.user_slots
            where user_id = v_user_id
              and slot_number = p_slot_number - 1
              and expires_at > now()
        ) then
            raise exception 'PREVIOUS_SLOT_NOT_ACTIVE';
        end if;
    end if;

    select expires_at into v_current_expires
    from public.user_slots
    where user_id = v_user_id and slot_number = p_slot_number;

    if v_current_expires is not null and v_current_expires > now() then
        v_new_expires := v_current_expires + interval '16 days';
    else
        v_new_expires := now() + interval '16 days';
    end if;

    insert into public.user_slots (user_id, slot_number, unlocked_at, expires_at)
    values (v_user_id, p_slot_number, now(), v_new_expires)
    on conflict (user_id, slot_number)
    do update set
        unlocked_at = now(),
        expires_at = v_new_expires;

    update public.profiles
    set credits = credits - v_cost
    where id = v_user_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_user_id,
        -v_cost,
        'unlock_slot',
        format('Mở khóa / Gia hạn Slot %s (thời hạn 16 ngày)', p_slot_number),
        v_balance
    );

    return jsonb_build_object(
        'success', true,
        'slot_number', p_slot_number,
        'cost', v_cost,
        'balance', v_balance,
        'expires_at', to_char(v_new_expires at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$$;

-- 4.8. RPC: Tự động tạm dừng chiến dịch khi slot hết hạn
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
    set status = 'paused'
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

-- 4.9. RPC: Admin điều chỉnh Credit
create or replace function public.admin_adjust_credits(
    p_target_user_id uuid,
    p_amount int,
    p_reason text default 'Điều chỉnh bởi Admin'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_current_credits int;
    v_new_balance int;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền điều chỉnh Credit';
    end if;

    select credits into v_current_credits
    from public.profiles
    where id = p_target_user_id
    for update;

    if v_current_credits is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_new_balance := v_current_credits + p_amount;
    if v_new_balance < 0 then
        v_new_balance := 0;
    end if;

    update public.profiles
    set credits = v_new_balance
    where id = p_target_user_id;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        p_target_user_id,
        p_amount,
        'admin_adjustment',
        coalesce(p_reason, 'Điều chỉnh bởi Admin'),
        v_new_balance
    );

    return jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'previous_credits', v_current_credits,
        'new_credits', v_new_balance,
        'adjustment', p_amount
    );
end;
$$;

-- 4.10. RPC: Admin khóa hoặc mở khóa tài khoản
create or replace function public.admin_set_user_lock(
    p_target_user_id uuid,
    p_locked boolean,
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
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền khóa/mở khóa tài khoản';
    end if;

    if p_target_user_id = v_caller_id and p_locked = true then
        raise exception 'CANNOT_LOCK_SELF: Không thể tự khóa tài khoản của chính mình';
    end if;

    update public.profiles
    set is_locked = p_locked,
        lock_reason = p_reason
    where id = p_target_user_id;

    return jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'is_locked', p_locked
    );
end;
$$;

-- 4.11. RPC: Admin gỡ bỏ bất kỳ ứng dụng nào và cascade dữ liệu
create or replace function public.admin_delete_campaign(
    p_campaign_id uuid,
    p_reason text default 'Gỡ bỏ bởi Admin'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_app_name text;
    v_owner_id uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền gỡ bỏ ứng dụng';
    end if;

    select app_name, owner_id into v_app_name, v_owner_id
    from public.campaigns
    where id = p_campaign_id;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    delete from public.campaigns where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'app_name', v_app_name,
        'owner_id', v_owner_id,
        'reason', coalesce(p_reason, 'Gỡ bỏ bởi Admin')
    );
end;
$$;

-- 4.12. RPC: Admin gửi thông báo (Broadcast hoặc Đích danh)
create or replace function public.admin_send_notification(
    p_target_user_id uuid default null,
    p_title text default '',
    p_message text default '',
    p_type text default 'admin'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_count int := 0;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_caller_role from public.profiles where id = v_caller_id;

    if coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    if p_title is null or trim(p_title) = '' then
        raise exception 'TITLE_REQUIRED';
    end if;

    if p_message is null or trim(p_message) = '' then
        raise exception 'MESSAGE_REQUIRED';
    end if;

    if p_target_user_id is not null then
        insert into public.notifications (user_id, title, message, type, data)
        values (
            p_target_user_id,
            p_title,
            p_message,
            coalesce(p_type, 'admin'),
            jsonb_build_object('sender', 'admin', 'broadcast', false)
        );
        v_count := 1;
    else
        insert into public.notifications (user_id, title, message, type, data)
        select
            id,
            p_title,
            p_message,
            coalesce(p_type, 'admin'),
            jsonb_build_object('sender', 'admin', 'broadcast', true)
        from public.profiles;

        get diagnostics v_count = row_count;
    end if;

    return jsonb_build_object(
        'success', true,
        'count', v_count,
        'target', case when p_target_user_id is not null then 'single_user' else 'broadcast_all' end
    );
end;
$$;

-- 4.13. RPC: Xử lý giao dịch nạp Credit (In-App Purchase)
create or replace function public.process_topup_order(
    p_package_id    text,
    p_purchase_token text default null,
    p_order_ref     text default null,
    p_payment_method text default 'google_play',
    p_raw_receipt   text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id       uuid := auth.uid();
    v_pkg           record;
    v_existing_order record;
    v_total_credits int;
    v_new_balance   int;
    v_order_id      uuid;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_purchase_token is not null and trim(p_purchase_token) <> '' then
        select * into v_existing_order
        from public.topup_orders
        where purchase_token = trim(p_purchase_token)
          and payment_status = 'completed'
        limit 1;

        if found then
            select credits into v_new_balance from public.profiles where id = v_user_id;
            return jsonb_build_object(
                'status', 'already_processed',
                'order_id', v_existing_order.id,
                'credits_awarded', v_existing_order.credits_awarded,
                'current_balance', coalesce(v_new_balance, 0),
                'message', 'Giao dịch này đã được cộng điểm trước đó.'
            );
        end if;
    end if;

    select * into v_pkg from public.topup_packages where id = p_package_id;
    if not found then
        raise exception 'PACKAGE_NOT_FOUND: Gói nạp không tồn tại hoặc đã ngừng hoạt động.';
    end if;

    v_total_credits := v_pkg.credits + coalesce(v_pkg.bonus_credits, 0);

    insert into public.topup_orders (
        user_id,
        package_id,
        credits_awarded,
        price_usd,
        price_vnd,
        payment_method,
        payment_status,
        purchase_token,
        order_reference,
        raw_receipt,
        completed_at
    )
    values (
        v_user_id,
        v_pkg.id,
        v_total_credits,
        v_pkg.price_usd,
        v_pkg.price_vnd,
        coalesce(p_payment_method, 'google_play'),
        'completed',
        trim(p_purchase_token),
        trim(p_order_ref),
        p_raw_receipt,
        now()
    )
    returning id into v_order_id;

    update public.profiles
    set credits = credits + v_total_credits
    where id = v_user_id
    returning credits into v_new_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_user_id,
        v_total_credits,
        'topup_purchase',
        format('Nạp %s Credit qua gói "%s"', v_total_credits, v_pkg.title),
        v_new_balance
    );

    return jsonb_build_object(
        'status', 'completed',
        'order_id', v_order_id,
        'package_id', v_pkg.id,
        'credits_awarded', v_total_credits,
        'new_balance', v_new_balance,
        'message', format('Nạp thành công +%s Credit vào tài khoản!', v_total_credits)
    );
end;
$$;

-- 4.14. RPC: Lấy tiến độ nhiệm vụ ngày & tuần (Quests Summary)
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
        select count(*) into v_daily_count
        from public.checkins
        where user_id = v_uid and checkin_date = v_today;

        select count(*) into v_weekly_count
        from public.checkins
        where user_id = v_uid and checkin_date >= v_week_start and checkin_date <= v_today;

        select coalesce(jsonb_agg(milestone), '[]'::jsonb) into v_daily_claimed
        from public.user_quest_claims
        where user_id = v_uid and quest_type = 'daily' and period_key = v_today;

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

-- 4.15. RPC: Nhận thưởng mốc nhiệm vụ
create or replace function public.claim_quest_reward(
    p_quest_type text,
    p_milestone int
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_start date := date_trunc('week', (now() at time zone 'Asia/Ho_Chi_Minh'))::date;
    v_period_key date;
    v_reward int := 0;
    v_current_count int := 0;
    v_new_balance int;
    v_is_locked boolean;
begin
    if v_uid is null then
        return jsonb_build_object('success', false, 'error', 'NOT_AUTHENTICATED', 'message', 'Bạn chưa đăng nhập.');
    end if;

    select is_locked into v_is_locked from public.profiles where id = v_uid;
    if v_is_locked is true then
        return jsonb_build_object('success', false, 'error', 'ACCOUNT_LOCKED', 'message', 'Tài khoản của bạn đang bị tạm khóa.');
    end if;

    if p_quest_type = 'daily' then
        v_period_key := v_today;
        if p_milestone = 10 then
            v_reward := 5;
        elsif p_milestone = 20 then
            v_reward := 15;
        elsif p_milestone = 50 then
            v_reward := 30;
        else
            return jsonb_build_object('success', false, 'error', 'INVALID_MILESTONE', 'message', 'Mốc nhiệm vụ ngày không hợp lệ.');
        end if;

        select count(*) into v_current_count
        from public.checkins
        where user_id = v_uid and checkin_date = v_today;

    elsif p_quest_type = 'weekly' then
        v_period_key := v_week_start;
        if p_milestone = 150 then
            v_reward := 50;
        elsif p_milestone = 300 then
            v_reward := 100;
        else
            return jsonb_build_object('success', false, 'error', 'INVALID_MILESTONE', 'message', 'Mốc nhiệm vụ tuần không hợp lệ.');
        end if;

        select count(*) into v_current_count
        from public.checkins
        where user_id = v_uid and checkin_date >= v_week_start and checkin_date <= v_today;
    else
        return jsonb_build_object('success', false, 'error', 'INVALID_QUEST_TYPE', 'message', 'Loại nhiệm vụ không hợp lệ.');
    end if;

    if v_current_count < p_milestone then
        return jsonb_build_object(
            'success', false,
            'error', 'MILESTONE_NOT_REACHED',
            'current_count', v_current_count,
            'milestone', p_milestone,
            'message', format('Bạn chưa hoàn thành mốc kiểm thử này (hiện tại %s/%s)!', v_current_count, p_milestone)
        );
    end if;

    if exists (
        select 1 from public.user_quest_claims
        where user_id = v_uid and quest_type = p_quest_type and milestone = p_milestone and period_key = v_period_key
    ) then
        return jsonb_build_object(
            'success', false,
            'error', 'ALREADY_CLAIMED',
            'message', 'Bạn đã nhận phần thưởng này rồi!'
        );
    end if;

    insert into public.user_quest_claims (user_id, quest_type, milestone, reward_credits, period_key)
    values (v_uid, p_quest_type, p_milestone, v_reward, v_period_key);

    update public.profiles
    set credits = credits + v_reward
    where id = v_uid
    returning credits into v_new_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_uid,
        v_reward,
        case when p_quest_type = 'daily' then 'quest_daily_reward' else 'quest_weekly_reward' end,
        case when p_quest_type = 'daily'
            then format('Thưởng hoàn thành nhiệm vụ ngày (mốc %s app, +%s Credit)', p_milestone, v_reward)
            else format('Thưởng hoàn thành nhiệm vụ tuần (mốc %s app, +%s Credit)', p_milestone, v_reward)
        end,
        v_new_balance
    );

    return jsonb_build_object(
        'success', true,
        'quest_type', p_quest_type,
        'milestone', p_milestone,
        'reward_credits', v_reward,
        'new_balance', v_new_balance,
        'message', format('Chúc mừng! Bạn đã nhận thành công +%s Credit thưởng nhiệm vụ.', v_reward)
    );
end;
$$;

-- 4.16. RPC: Tự động trừ phạt Credit khi không thực hiện nhiệm vụ hàng ngày
create or replace function public.apply_daily_inactivity_penalty()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    pct numeric;
    deduction int;
    new_balance int;
    yesterday_date date := current_date - 1;
begin
    for r in 
        select id, credits, missed_streak, last_active_date, created_at
        from public.profiles
        where last_active_date < yesterday_date 
          and credits > 0
          and created_at < (now() - interval '24 hours')
    loop
        r.missed_streak := r.missed_streak + 1;

        if r.missed_streak = 1 then
            pct := 0.01;
        elsif r.missed_streak = 2 then
            pct := 0.02;
        elsif r.missed_streak = 3 then
            pct := 0.05;
        else
            pct := 0.10;
        end if;

        deduction := greatest(1, ceil(r.credits * pct)::int);
        if deduction > r.credits then
            deduction := r.credits;
        end if;
        new_balance := r.credits - deduction;

        update public.profiles
        set credits = new_balance,
            missed_streak = r.missed_streak
        where id = r.id;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            r.id,
            -deduction,
            'inactivity_penalty',
            format('Trừ %s%% do không test app ngày thứ %s (còn %s Credit)', (pct * 100)::int, r.missed_streak, new_balance),
            new_balance
        );
    end loop;
end;
$$;

-- 4.17. Các hàm tiện ích gán quyền Quản trị viên (Admin & Moderator)
-- Dùng trong Supabase SQL Editor:
-- SELECT public.make_user_admin('email_cua_ban@gmail.com');
create or replace function public.make_user_admin(p_email text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count int;
begin
    update public.profiles
    set role = 'admin'
    where lower(email) = lower(trim(p_email));

    get diagnostics v_count = row_count;
    if v_count > 0 then
        return format('Đã bổ nhiệm tài khoản %s thành Admin thành công.', p_email);
    else
        return format('Không tìm thấy tài khoản với email %s.', p_email);
    end if;
end;
$$;

create or replace function public.make_user_moderator(p_email text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count int;
begin
    update public.profiles
    set role = 'moderator'
    where lower(email) = lower(trim(p_email));

    get diagnostics v_count = row_count;
    if v_count > 0 then
        return format('Đã bổ nhiệm tài khoản %s thành Moderator thành công.', p_email);
    else
        return format('Không tìm thấy tài khoản với email %s.', p_email);
    end if;
end;
$$;


-- 4.18. Lên lịch tự động duyệt báo cáo quá hạn 8h bằng pg_cron (bỏ qua nếu chưa bật extension)
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule(
            'auto_accept_reports_job',
            '*/30 * * * *',
            'select public.auto_accept_expired_reports();'
        );
    else
        raise notice 'pg_cron chưa được bật — bật tại Database > Extensions rồi chạy lại để tự động duyệt sau 8h.';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;


-- ============================================================================
-- 5. CẤP QUYỀN THỰC THI (GRANTS) VÀ TẢI LẠI CACHE
-- ============================================================================

grant all on all tables in schema public to authenticated, anon, service_role;
grant all on all sequences in schema public to authenticated, anon, service_role;
grant execute on all functions in schema public to authenticated, anon, service_role;

notify pgrst, 'reload schema';
