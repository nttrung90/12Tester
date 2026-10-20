-- ============================================================================
-- 12 Tester — TOÀN BỘ SCHEMA HỢP NHẤT CHO PROJECT MỚI (SINGAPORE)
-- File này gộp toàn bộ 18 file migration (001 -> 014) thành 1 bản duy nhất.
-- Cách dùng:
-- 1. Vào Supabase Dashboard (Singapore) > SQL Editor > New query
-- 2. Dán toàn bộ nội dung file này vào và bấm Run.
--
-- ⚠️ LƯU Ý: file này KHÔNG bao gồm các migration 015 -> 031.
-- Sau khi chạy file này, BẮT BUỘC chạy tiếp lần lượt các file trong
-- supabase/migrations/ từ 015 đến 031_fix_report_review_integrity.sql
-- để có đủ hệ thống Credit, Tip, cron auto-duyệt và các bản vá bảo mật.
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

-- 4.7. RPC: Mở khóa hoặc Gia hạn Slot đăng app (30 ngày)
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
        v_new_expires := v_current_expires + interval '30 days';
    else
        v_new_expires := now() + interval '30 days';
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
        format('Mở khóa / Gia hạn Slot %s (thời hạn 30 ngày)', p_slot_number),
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


-- ============================================================================
-- PHẦN 6: TỐI ƯU TRUY VẤN (MIGRATION 020 — QUERY OPTIMIZATION)
-- ============================================================================

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
--    p_sort = 'credit' (mặc định): top theo credits — thuật toán cũ
--    p_sort = 'test'            : top theo tổng lượt test toàn thời gian
-- ============================================================================

drop function if exists public.get_leaderboard_v2(int);

create or replace function public.get_leaderboard_v2(
    p_limit int default 100,
    p_sort text default 'credit'
)
returns jsonb
language plpgsql
security definer
set search_path = public
stable
as $$
declare
    v_limit int := greatest(1, least(coalesce(p_limit, 100), 200));
    v_result jsonb := '[]'::jsonb;
begin
    if coalesce(p_sort, 'credit') = 'test' then
        select coalesce(jsonb_agg(jsonb_build_object(
            'userId', t.id,
            'displayName', t.name,
            'avatarUrl', t.avatar_url,
            'credits', t.credits,
            'checkinCount', t.checkin_count,
            'campaignsCount', t.campaigns_count,
            'rank', t.rn
        ) order by t.rn), '[]'::jsonb)
        into v_result
        from (
            select b.*, row_number() over (order by b.checkin_count desc, b.credits desc, b.campaigns_count desc) as rn
            from (
                select x.*
                from (
                    select p.id,
                           coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Tester') as name,
                           p.avatar_url,
                           p.credits,
                           (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                           (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
                    from public.profiles p
                ) x
                where x.checkin_count > 0
                order by x.checkin_count desc, x.credits desc, x.campaigns_count desc
                limit v_limit
            ) b
        ) t;
    else
        select coalesce(jsonb_agg(jsonb_build_object(
            'userId', t.id,
            'displayName', t.name,
            'avatarUrl', t.avatar_url,
            'credits', t.credits,
            'checkinCount', t.checkin_count,
            'campaignsCount', t.campaigns_count,
            'rank', t.rn
        ) order by t.rn), '[]'::jsonb)
        into v_result
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
                limit v_limit
            ) b
        ) t;
    end if;

    return v_result;
end;
$$;

grant execute on function public.get_leaderboard_v2(int, text) to authenticated, anon, service_role;


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


-- ============================================================================
-- Bo sung (dong bo migration 022 + 023) - Credit & Privilege Hardening
-- ============================================================================

-- ============================================================================
-- 12 Tester — Supabase migration 022: Credit integrity fix
-- - RPC `apply_report_credit`: cộng/trừ Credit NGUYÊN TỬ cho tester của một báo
--   cáo (khoá dòng profile bằng FOR UPDATE + ghi credit_logs trong cùng
--   transaction). Client dùng ở đường fallback khi RPC duyệt/từ chối báo cáo
--   chưa tồn tại, thay cho kiểu đọc-rồi-ghi dễ mất cập nhật khi chạy song song.
-- - Quyền: chỉ chủ app của campaign chứa báo cáo (hoặc admin/moderator) được gọi.
-- Chạy trong Supabase Dashboard: SQL Editor > New query > paste > Run.
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

    update public.profiles
    set credits = v_new
    where id = v_target;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_target,
        p_amount,
        coalesce(nullif(trim(p_action), ''), 'report_adjust'),
        p_note,
        v_new
    );

    return jsonb_build_object(
        'success', true,
        'user_id', v_target,
        'previous_credits', v_current,
        'new_credits', v_new,
        'amount', p_amount
    );
end;
$$;

grant execute on function public.apply_report_credit(uuid, int, text, text) to authenticated, service_role;

notify pgrst, 'reload schema';



-- ============================================================================
-- 12 Tester — Supabase migration 023: Credit & Privilege Hardening
-- Vá các lỗ hổng cộng/trừ Credit và phân quyền:
--   1. profiles: chặn client ghi trực tiếp credits/role/is_locked (revoke table-level,
--      chỉ grant column-level các cột client thực sự dùng).
--   2. make_user_admin / make_user_moderator: thêm guard admin + revoke khỏi client.
--   3. apply_daily_inactivity_penalty: revoke khỏi client (hàm vẫn ngủ, không schedule).
--   4. tester_reports RLS: ép INSERT chỉ status='pending' (chặn spoof 'rejected'/'approved'),
--      UPDATE chỉ chủ app (chặn tester tự sửa trạng thái báo cáo của mình).
--   5. handle_checkin_reward: mở rộng pattern suppress để khớp note auto duyệt
--      'Hệ thống tự động duyệt sau 8h' (không cộng +2 ngoài ý muốn).
--   6. submit_tester_report: khóa advisory chống race, v_already_checked_in null-safe,
--      Case 2 yêu cầu reject thật (reviewed_at not null), Case 2/4 không cộng trùng
--      2 Credit khi đã có checkin xác nhận trong ngày.
--   7. handle_campaign_target_cost: trừ chênh lệch khi tăng target_testers qua UPDATE.
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================


-- ============================================================================
-- 1. profiles: siết quyền ghi của client
-- ============================================================================
-- Client chỉ được ghi: id, email, display_name, avatar_url (ProfileUpsert).
-- credits/role/is_locked/last_active_date/missed_streak chỉ server (definer) ghi.
revoke insert, update on public.profiles from public, anon, authenticated;

grant insert (id, email, display_name, avatar_url) on public.profiles to authenticated;
grant update (id, email, display_name, avatar_url) on public.profiles to authenticated;


-- ============================================================================
-- 2. make_user_admin / make_user_moderator: guard admin + revoke khỏi client
-- ============================================================================
create or replace function public.make_user_admin(p_email text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count int;
    v_caller uuid := auth.uid();
begin
    -- Cho phép gọi từ SQL Editor / service_role (không có JWT), chặn mọi caller khác không phải admin
    if v_caller is not null and not exists (
        select 1 from public.profiles where id = v_caller and role = 'admin'
    ) then
        raise exception 'PERMISSION_DENIED: Chỉ admin mới được bổ nhiệm vai trò';
    end if;

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
    v_caller uuid := auth.uid();
begin
    if v_caller is not null and not exists (
        select 1 from public.profiles where id = v_caller and role = 'admin'
    ) then
        raise exception 'PERMISSION_DENIED: Chỉ admin mới được bổ nhiệm vai trò';
    end if;

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

revoke execute on function public.make_user_admin(text) from public, anon, authenticated;
revoke execute on function public.make_user_moderator(text) from public, anon, authenticated;
grant execute on function public.make_user_admin(text) to service_role;
grant execute on function public.make_user_moderator(text) to service_role;


-- ============================================================================
-- 3. apply_daily_inactivity_penalty: chỉ service_role/cron được gọi
-- ============================================================================
revoke execute on function public.apply_daily_inactivity_penalty() from public, anon, authenticated;
grant execute on function public.apply_daily_inactivity_penalty() to service_role;


-- ============================================================================
-- 4. tester_reports RLS: chặn spoof trạng thái & tester tự sửa báo cáo
-- ============================================================================
-- Insert: chỉ được tạo báo cáo pending, bonus_awarded=0, chưa review.
-- (RPC security definer không bị ảnh hưởng bởi policy này.)
drop policy if exists "reports_insert_tester" on public.tester_reports;
create policy "reports_insert_tester" on public.tester_reports
    for insert to authenticated
    with check (
        user_id = auth.uid()
        and status = 'pending'
        and bonus_awarded = 0
        and reviewed_at is null
        and exists (
            select 1 from public.profiles
            where id = auth.uid() and is_locked = false
        )
    );

-- Update: chỉ chủ app của campaign (đồng bộ với migration 012).
-- Chặn tester PATCH report của mình thành 'rejected' để gọi lại RPC farm +2 vô hạn.
drop policy if exists "reports_update_tester_or_owner" on public.tester_reports;
drop policy if exists "reports_update_owner" on public.tester_reports;
create policy "reports_update_owner" on public.tester_reports
    for update to authenticated
    using (exists (select 1 from public.campaigns c where c.id = campaign_id and c.owner_id = auth.uid()));


-- ============================================================================
-- 5. handle_checkin_reward: khớp cả note 'Hệ thống tự động duyệt sau 8h'
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
-- 6. submit_tester_report: chống race & chống cộng trùng 2 Credit
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

    -- Khóa tuần tự theo (user, campaign, ngày) — hai request song song không cùng
    -- tạo 2 báo cáo + cộng đôi Credit.
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    -- Tự động thêm tester vào danh sách thành viên nếu chưa tham gia
    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lưu/Cập nhật đánh giá sao và nhận xét trong bảng feedbacks
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = excluded.comment,
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
        -- Trường hợp 1: Báo cáo hôm nay đã được duyệt từ trước (hoặc đã có checkin xác nhận)
        if v_existing_report.status = 'approved' or v_already_checked_in then
            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = coalesce(p_image_hash, image_hash)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'approved';

        -- Trường hợp 2: Báo cáo hôm nay bị từ chối THẬT (đã được chủ app review) -> Tester nộp lại
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if v_already_checked_in then
                -- Đã nhận 2 Credit từ checkin xác nhận hôm nay -> chỉ mở lại báo cáo, không cấp thêm
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
            else
                -- Cấp lại 2 Credit tạm nhận
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
            end if;

        -- Trường hợp 3: Báo cáo đang ở trạng thái pending (chỉnh sửa nội dung/ảnh trước khi duyệt)
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

grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, anon, service_role;


-- ============================================================================
-- 7. handle_campaign_target_cost: trừ chênh lệch khi tăng target qua UPDATE
-- ============================================================================
-- Quy tắc: 12 Test/ngày = 0 Credit, 15 = 10 Credit, 20 = 20 Credit.
-- INSERT: trừ toàn bộ chi phí. UPDATE: chỉ trừ phần chênh lệch khi nâng gói.
create or replace function public.handle_campaign_target_cost()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_cost int := 0;
    v_old_cost int := 0;
    v_delta int := 0;
    v_balance int;
begin
    -- Chuẩn hóa target_testers (chỉ 12/15/20 hợp lệ)
    if new.target_testers is null or new.target_testers not in (12, 15, 20) then
        new.target_testers := 12;
    end if;

    if tg_op = 'UPDATE' then
        if new.target_testers is not distinct from old.target_testers then
            return new;
        end if;

        v_old_cost := case old.target_testers when 15 then 10 when 20 then 20 else 0 end;
        v_cost := case new.target_testers when 15 then 10 when 20 then 20 else 0 end;
        v_delta := v_cost - v_old_cost;
    else
        v_cost := case new.target_testers when 15 then 10 when 20 then 20 else 0 end;
        v_delta := v_cost;
    end if;

    -- Giảm gói hoặc giữ nguyên: không trừ thêm (không hoàn Credit khi hạ gói)
    if v_delta <= 0 then
        return new;
    end if;

    select credits into v_balance
    from public.profiles
    where id = new.owner_id
    for update;

    if v_balance is null or v_balance < v_delta then
        raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần ít nhất % Credit để nâng mục tiêu lên % Test/ngày', v_delta, new.target_testers;
    end if;

    update public.profiles
    set credits = credits - v_delta
    where id = new.owner_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        new.owner_id,
        -v_delta,
        'target_upgrade',
        format('Nâng cấp mục tiêu %s Test/ngày (-%s Credit)', new.target_testers, v_delta),
        v_balance
    );

    return new;
end;
$$;

drop trigger if exists trg_on_campaign_target_cost on public.campaigns;
create trigger trg_on_campaign_target_cost
    before insert or update of target_testers on public.campaigns
    for each row
    execute function public.handle_campaign_target_cost();


notify pgrst, 'reload schema';



-- ============================================================================
-- Migration 024: Đồng bộ image_hash với screenshot_url khi tester thay ảnh
-- ============================================================================
-- Vấn đề: các nhánh UPDATE dùng `image_hash = coalesce(p_image_hash, image_hash)`.
-- Nếu tester chọn ảnh mới nhưng client không tính được hash (p_image_hash = null),
-- URL ảnh bị thay trong khi hash CŨ được giữ lại -> hash không còn mô tả đúng ảnh
-- đã lưu, làm sai phát hiện ảnh trùng lặp sau này.
--
-- Cách sửa: khi p_screenshot_url được cung cấp VÀ khác URL hiện tại thì ghi thẳng
-- p_image_hash (kể cả null) để luôn đồng bộ; các trường hợp còn lại giữ coalesce
-- như trước (chỉ cập nhật nội dung/rating mà không đổi ảnh).
--
-- Lưu ý ngữ nghĩa: trong một câu UPDATE của PostgreSQL, mọi biểu thức vế phải SET
-- đều tham chiếu giá trị CŨ của hàng, nên `screenshot_url` trong CASE chính là
-- URL cũ trước khi bị thay.
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

    -- Khóa tuần tự theo (user, campaign, ngày) — hai request song song không cùng
    -- tạo 2 báo cáo + cộng đôi Credit.
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    -- Tự động thêm tester vào danh sách thành viên nếu chưa tham gia
    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lưu/Cập nhật đánh giá sao và nhận xét trong bảng feedbacks
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = excluded.comment,
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
        -- Trường hợp 1: Báo cáo hôm nay đã được duyệt từ trước (hoặc đã có checkin xác nhận)
        if v_existing_report.status = 'approved' or v_already_checked_in then
            update public.tester_reports
            set content = coalesce(p_content, content),
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
                set content = coalesce(p_content, content),
                    screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
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
                -- Cấp lại 2 Credit tạm nhận
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
                v_provisional_granted := true;
            end if;

        -- Trường hợp 3: Báo cáo đang ở trạng thái pending (chỉnh sửa nội dung/ảnh trước khi duyệt)
        else
            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = case
                    when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                        then p_image_hash
                    else coalesce(p_image_hash, image_hash)
                end
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'pending';
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

grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, anon, service_role;


-- ============================================================================
-- Bổ sung (đồng bộ migration 025) - Contact & Bug Reports
-- ============================================================================
create table if not exists public.contact_reports (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references public.profiles(id) on delete cascade,
    category     text not null default 'bug', -- 'bug' | 'feedback' | 'other'
    message      text not null,
    app_version  text,                        -- ví dụ: "0.3.0 (6)"
    platform     text,                        -- ví dụ: "Android 14"
    device_model text,                        -- ví dụ: "Samsung SM-G991B"
    status       text not null default 'new', -- 'new' | 'replied' | 'resolved'
    admin_reply  text,
    replied_at   timestamptz,
    created_at   timestamptz not null default now(),
    constraint contact_reports_category_check
        check (category in ('bug', 'feedback', 'other')),
    constraint contact_reports_status_check
        check (status in ('new', 'replied', 'resolved'))
);

create index if not exists idx_contact_reports_user_created
    on public.contact_reports(user_id, created_at desc);
create index if not exists idx_contact_reports_status_created
    on public.contact_reports(status, created_at desc);

alter table public.contact_reports enable row level security;

drop policy if exists "contact_reports_select_own" on public.contact_reports;
create policy "contact_reports_select_own"
    on public.contact_reports for select to authenticated
    using (user_id = auth.uid());

drop policy if exists "contact_reports_insert_own" on public.contact_reports;
create policy "contact_reports_insert_own"
    on public.contact_reports for insert to authenticated
    with check (
        user_id = auth.uid()
        and status = 'new'
        and admin_reply is null
        and replied_at is null
    );

revoke insert, update, delete on public.contact_reports from public, anon, authenticated;
grant insert (user_id, category, message, app_version, platform, device_model)
    on public.contact_reports to authenticated;
grant select on public.contact_reports to authenticated;

create or replace function public.admin_get_contact_reports(
    p_status text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_result jsonb;
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

    select coalesce(jsonb_agg(to_jsonb(t) order by t.created_at desc), '[]'::jsonb)
    into v_result
    from (
        select
            r.id,
            r.user_id,
            r.category,
            r.message,
            r.app_version,
            r.platform,
            r.device_model,
            r.status,
            r.admin_reply,
            r.replied_at,
            r.created_at,
            p.display_name,
            p.email,
            p.avatar_url
        from public.contact_reports r
        left join public.profiles p on p.id = r.user_id
        where p_status is null or r.status = p_status
        order by r.created_at desc
        limit 300
    ) t;

    return v_result;
end;
$$;

create or replace function public.admin_update_report_status(
    p_report_id uuid,
    p_resolved  boolean
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_report public.contact_reports;
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

    update public.contact_reports
    set status = case
        when p_resolved then 'resolved'
        when admin_reply is not null then 'replied'
        else 'new'
    end
    where id = p_report_id
    returning * into v_report;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    return to_jsonb(v_report);
end;
$$;

create or replace function public.admin_reply_contact_report(
    p_report_id uuid,
    p_message   text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_report public.contact_reports;
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

    if p_message is null or trim(p_message) = '' then
        raise exception 'MESSAGE_REQUIRED';
    end if;

    update public.contact_reports
    set admin_reply = trim(p_message),
        replied_at = now(),
        status = 'replied'
    where id = p_report_id
    returning * into v_report;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_report.user_id,
        'Phản hồi từ Admin',
        trim(p_message),
        'admin',
        jsonb_build_object(
            'sender', 'admin',
            'broadcast', false,
            'contact_report_id', p_report_id
        )
    );

    return to_jsonb(v_report);
end;
$$;


notify pgrst, 'reload schema';

-- ============================================================================
-- BO SUNG KHOI BU (dong bo cac migration con thieu vao full schema):
--   * 011 (chi trigger cap nhat last_active_date + get_user_quest_summary moi)
--   * 017 (home tabs & vong doi campaign)
--   * 018/019 (app_configs + admin_update_app_config)
--   * 021 (get_my_dashboard v2 + get_next_untested_campaign + pg_cron)
--   * 027 (fix luong tuong tac chu app <-> tester)
-- Tat ca deu idempotent, chay duoc tren DB moi hoac DB da co du lieu.
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
-- 1. App sau khi đăng hiển thị tối đa 30 ngày trên tab "App cần Test";
--    hết 30 ngày (tính từ created_at) tự chuyển sang "Đã hoàn thành Kiểm thử".
-- 2. Chủ app bấm "App đã lên production" trước 30 ngày → app cũng chuyển sang
--    "Đã hoàn thành Kiểm thử" (status 'production').
-- 3. Chủ app không hoàn thành nhiệm vụ ngày 3 ngày liên tục → toàn bộ app đang
--    mở của họ tự chuyển sang "App tạm dừng" (paused_reason = 'owner_inactive').
-- 4. App tạm dừng được bật test lại (RPC resume_campaign) khi:
--    - còn trong cửa sổ 30 ngày, và
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
-- 4. Hàm tự động: hoàn thành kiểm thử sau 30 ngày hiển thị
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
          and c.created_at <= now() - interval '30 days'
        returning c.id, c.owner_id, c.app_name
    loop
        v_count := v_count + 1;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 30 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
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

    -- Quá cửa sổ 30 ngày → chuyển luôn sang hoàn thành
    if v_camp.created_at <= now() - interval '30 days' then
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

    -- Quá cửa sổ 30 ngày → chuyển sang hoàn thành và trả kết quả (KHÔNG raise,
    -- vì exception sẽ rollback luôn UPDATE khiến app kẹt 'paused').
    if v_camp.created_at <= now() - interval '30 days' then
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