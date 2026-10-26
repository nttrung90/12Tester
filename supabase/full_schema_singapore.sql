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
-- 7b. RPC: get_my_rank_v1 — hạng chính xác của người dùng hiện tại (auth.uid())
--     theo cùng thứ tự sắp xếp với get_leaderboard_v2, kèm tổng số thành viên.
--     Trả '{}'::jsonb khi không xác định được (chưa đăng nhập / không có
--     profile / sort 'test' mà chưa có lượt test).
-- ============================================================================

create or replace function public.get_my_rank_v1(
    p_sort text default 'credit'
)
returns jsonb
language plpgsql
security definer
set search_path = public
stable
as $$
declare
    v_user uuid := auth.uid();
    v_credits int;
    v_checkins int;
    v_campaigns int;
    v_total int;
    v_rank int;
begin
    if v_user is null then
        return '{}'::jsonb;
    end if;

    -- Thống kê của chính mình.
    select p.credits,
           (select count(*) from public.checkins ci where ci.user_id = p.id),
           (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id)
    into v_credits, v_checkins, v_campaigns
    from public.profiles p
    where p.id = v_user;

    if not found then
        return '{}'::jsonb;
    end if;

    select count(*) into v_total from public.profiles;

    if coalesce(p_sort, 'credit') = 'test' then
        if v_checkins = 0 then
            -- Top Test chỉ gồm người đã thực hiện ít nhất 1 lượt test.
            return '{}'::jsonb;
        end if;

        select count(*) + 1 into v_rank
        from (
            select p.id,
                   p.credits,
                   (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                   (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
            from public.profiles p
        ) t
        where t.checkin_count > v_checkins
           or (t.checkin_count = v_checkins and t.credits > v_credits)
           or (t.checkin_count = v_checkins and t.credits = v_credits and t.campaigns_count > v_campaigns);
    else
        select count(*) + 1 into v_rank
        from (
            select p.id,
                   p.credits,
                   (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                   (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
            from public.profiles p
        ) t
        where t.credits > v_credits
           or (t.credits = v_credits and t.checkin_count > v_checkins)
           or (t.credits = v_credits and t.checkin_count = v_checkins and t.campaigns_count > v_campaigns);
    end if;

    return jsonb_build_object(
        'rank', v_rank,
        'credits', v_credits,
        'checkinCount', v_checkins,
        'campaignsCount', v_campaigns,
        'totalUsers', v_total
    );
end;
$$;

grant execute on function public.get_my_rank_v1(text) to authenticated;


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

-- ============================================================================
-- BO SUNG (dong bo migration 030) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

-- ============================================================================
-- 12 Tester — Supabase migration 030: Credit Hardening, Tip thật, Cron phạt vắng mặt
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nội dung:
-- 1. Top-up: khóa RPC khỏi client (chỉ service_role), thêm unique index
--    purchase_token cho đơn completed, kiểm tra gói is_active khi xử lý.
-- 2. Tip thật: RPC set_campaign_tip (owner/admin) + helper _grant_report_tip
--    trả tip cho tester khi báo cáo được duyệt (chủ app duyệt hoặc auto 8h):
--    trừ Credit chủ app, cộng Credit tester, ghi credit_logs 2 phía.
-- 3. Edge accounting: cột tester_reports.provisional_granted_credits ghi số
--    Credit THỰC đã cấp khi nộp; reject chỉ thu hồi đúng số đã cấp và chỉ xóa
--    dòng checkin tạm tính (không xóa checkin xác nhận khác trong ngày).
-- 4. Bật cron apply_daily_inactivity_penalty lúc 00:05 giờ VN (17:05 UTC).
-- ============================================================================


-- ============================================================================
-- 1. TOP-UP: khóa RPC khỏi client + chống trùng token + kiểm tra gói active
-- ============================================================================

-- Mỗi purchase_token chỉ được hoàn tất 1 đơn (chống double-spend khi check-then-insert).
create unique index if not exists uq_topup_orders_purchase_token_completed
    on public.topup_orders (purchase_token)
    where payment_status = 'completed' and purchase_token is not null;


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
    v_note          text;
begin
    -- 1. Ensure authenticated
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- 2. Check for duplicate purchase token (Idempotency / anti double-spend)
    if p_purchase_token is not null and trim(p_purchase_token) <> '' then
        select * into v_existing_order
        from public.topup_orders
        where purchase_token = trim(p_purchase_token)
          and payment_status = 'completed'
        limit 1;

        if found then
            -- Token already processed previously, return existing balance safely
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

    -- 3. Fetch package details
    select * into v_pkg
    from public.topup_packages
    where id = p_package_id;

    if not found then
        raise exception 'PACKAGE_NOT_FOUND: Gói nạp không tồn tại hoặc đã ngừng hoạt động.';
    end if;

    if v_pkg.is_active is not true then
        raise exception 'PACKAGE_INACTIVE: Gói nạp đã ngừng hoạt động.';
    end if;

    v_total_credits := v_pkg.credits + coalesce(v_pkg.bonus_credits, 0);

    -- 4. Create completed order record
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
        nullif(trim(coalesce(p_purchase_token, '')), ''),
        p_order_ref,
        p_raw_receipt,
        now()
    )
    returning id into v_order_id;

    -- 5. Atomically increment user credits in profiles
    update public.profiles
    set credits = credits + v_total_credits
    where id = v_user_id
    returning credits into v_new_balance;

    -- 6. Insert audit record into credit_logs
    v_note := 'Nạp ' || v_total_credits || ' Credit (' || v_pkg.title || ')';
    if v_pkg.bonus_credits > 0 then
        v_note := v_note || ' - Tặng +' || v_pkg.bonus_credits || ' C';
    end if;

    insert into public.credit_logs (
        user_id,
        amount,
        action,
        note,
        balance
    )
    values (
        v_user_id,
        v_total_credits,
        'topup',
        v_note,
        v_new_balance
    );

    -- 7. Return success result
    return jsonb_build_object(
        'status', 'success',
        'order_id', v_order_id,
        'credits_awarded', v_total_credits,
        'current_balance', v_new_balance,
        'message', 'Nạp ' || v_total_credits || ' Credit thành công!'
    );
end;
$$;

-- Top-up chỉ được xử lý phía server (Edge Function xác thực Google Play receipt
-- gọi bằng service_role). Client KHÔNG được gọi trực tiếp để tránh mint Credit.
revoke execute on function public.process_topup_order(text, text, text, text, text) from public, anon, authenticated;
grant execute on function public.process_topup_order(text, text, text, text, text) to service_role;


-- ============================================================================
-- 2. TIP THẬT: trừ Credit chủ app, cộng cho tester khi báo cáo được duyệt
-- ============================================================================

-- 2.1. Chủ app (hoặc admin) đặt mức tip mỗi lượt duyệt: 0/1/2/3/5/10 Credit.
create or replace function public.set_campaign_tip(
    p_campaign_id uuid,
    p_tip_credits int
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_role text;
    v_camp record;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_tip_credits is null or p_tip_credits not in (0, 1, 2, 3, 5, 10) then
        raise exception 'INVALID_TIP_AMOUNT: Mức tip chỉ nhận 0/1/2/3/5/10 Credit.';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role into v_role from public.profiles where id = v_uid;
    if v_camp.owner_id <> v_uid and lower(coalesce(v_role, '')) <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    update public.campaigns
    set tip_credits = p_tip_credits,
        is_boosted = (p_tip_credits > 0)
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'tip_credits', p_tip_credits,
        'is_boosted', p_tip_credits > 0
    );
end;
$$;

revoke execute on function public.set_campaign_tip(uuid, int) from public, anon;
grant execute on function public.set_campaign_tip(uuid, int) to authenticated, service_role;


-- 2.2. Helper nội bộ: trả tip cho tester của một báo cáo vừa được duyệt.
--      Khóa dòng profile chủ app (FOR UPDATE) để không chi vượt số dư khi
--      nhiều báo cáo được duyệt song song. Không đủ Credit -> bỏ qua tip.
create or replace function public._grant_report_tip(
    p_campaign_id uuid,
    p_tester_id uuid
)
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_tip int;
    v_app_name text;
    v_owner_balance int;
    v_tester_balance int;
begin
    select owner_id, tip_credits, app_name
      into v_owner_id, v_tip, v_app_name
    from public.campaigns
    where id = p_campaign_id;

    if v_owner_id is null
       or coalesce(v_tip, 0) <= 0
       or v_owner_id = p_tester_id then
        return 0;
    end if;

    if not exists (select 1 from public.profiles where id = p_tester_id) then
        return 0;
    end if;

    select credits into v_owner_balance
    from public.profiles
    where id = v_owner_id
    for update;

    if coalesce(v_owner_balance, 0) < v_tip then
        return 0;
    end if;

    update public.profiles
    set credits = credits - v_tip
    where id = v_owner_id
    returning credits into v_owner_balance;

    update public.profiles
    set credits = credits + v_tip
    where id = p_tester_id
    returning credits into v_tester_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_owner_id,
        -v_tip,
        'campaign_tip_paid',
        format('Boa cho tester ứng dụng "%s" (mỗi lượt duyệt, -%s Credit)', coalesce(v_app_name, 'của bạn'), v_tip),
        v_owner_balance
    );

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        p_tester_id,
        v_tip,
        'campaign_tip_received',
        format('Nhận %s Credit boa từ chủ app "%s"', v_tip, coalesce(v_app_name, '')),
        v_tester_balance
    );

    return v_tip;
end;
$$;

-- Chỉ dùng nội bộ từ các RPC security definer; không cho client gọi trực tiếp.
revoke execute on function public._grant_report_tip(uuid, uuid) from public, anon, authenticated;


-- ============================================================================
-- 3. EDGE ACCOUNTING: ghi số Credit thực đã cấp khi nộp báo cáo
-- ============================================================================
-- default 2 = hành vi cũ (an toàn cho các báo cáo pending có từ trước).
alter table public.tester_reports
    add column if not exists provisional_granted_credits int not null default 2;


-- ============================================================================
-- 4. submit_tester_report — bản 027/028 + ghi provisional_granted_credits
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
    v_provisional_amount int := 0;
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
                    provisional_granted_credits = 0,
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
                    v_provisional_amount := v_regrant;
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
                    provisional_granted_credits = v_regrant,
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
                provisional_granted_credits,
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
                provisional_granted_credits,
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
                2,
                p_image_hash,
                now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := 2;
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
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount
    );
end;
$$;

-- Chỉ tài khoản đã đăng nhập được nộp báo cáo (RPC tự raise nếu thiếu uid).
revoke execute on function public.submit_tester_report(uuid, text, text, int, text) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, service_role;


-- ============================================================================
-- 5. approve_tester_report — bản 027/028 + trả tip cho tester
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
    v_tip int := 0;
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

    -- Tip thật: trừ Credit chủ app, cộng cho tester (bỏ qua nếu chủ app không đủ Credit)
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);

    -- Tester đã nhận 2 Credit tạm lúc nộp. Chủ app duyệt: Thưởng thêm v_extra_reward (+1 C) + tip
    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

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
        'tip_awarded', v_tip,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'message', format('Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester)', v_extra_reward)
    );
end;
$$;

revoke execute on function public.approve_tester_report(uuid, int) from public, anon;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;


-- ============================================================================
-- 6. reject_tester_report — bản 027/028 + thu hồi đúng số đã cấp
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

    -- 1. Thu hồi đúng số Credit THỰC đã cấp khi nộp (không âm điểm nếu số dư thấp).
    --    Ghi lại số THỰC thu hồi vào provisional_clawed để lần nộp lại chỉ
    --    được cấp lại đúng bấy nhiêu (chống farm Credit).
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(2, coalesce(v_report.provisional_granted_credits, 2)))
    );

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

    -- 3. Hủy bỏ kết quả ghi nhận tạm thời: chỉ xóa dòng checkin TẠM TÍNH
    --    (không xóa checkin xác nhận khác trong ngày của tester).
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

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

revoke execute on function public.reject_tester_report(uuid, text) from public, anon;
grant execute on function public.reject_tester_report(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 7. auto_accept_expired_reports — bản 016 + trả tip cho tester
-- ============================================================================
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
    v_tip int;
    v_total_bonus int;
begin
    -- Chặn trigger trg_on_tester_report_event bắn thông báo "được duyệt +1 Credit" sai
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        -- Tester đã tạm nhận 2 Credit khi nộp báo cáo.
        -- Tự động duyệt sau 8h: giữ nguyên 2 Credit tạm nhận, KHÔNG cộng +1,
        -- nhưng TRẢ TIP nếu chủ app có đặt mức tip (trừ Credit chủ app).
        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        -- Cập nhật dòng checkin thành chính thức tự động duyệt
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

        -- Gửi thông báo cho tester
        select app_name into v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên 2 Credit đã nhận + %s Credit boa từ chủ app). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên 2 Credit đã nhận). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'))
            end,
            'report',
            jsonb_build_object('campaign_id', v_report.campaign_id, 'report_id', v_report.id, 'status', 'approved', 'auto_accepted', true, 'tip_awarded', v_tip)
        );

        v_count := v_count + 1;
    end loop;

    -- Mở lại thông báo cho các giao dịch khác trong cùng transaction
    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;


-- ============================================================================
-- 8. trg_notify_tester_report — message thu hồi đúng số Credit thực
-- ============================================================================
create or replace function public.trg_notify_tester_report()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
    v_clawed int;
begin
    -- auto_accept_expired_reports tự gửi thông báo riêng -> không bắn thông báo trùng
    if coalesce(current_setting('app.suppress_report_notify', true), '') = '1' then
        return new;
    end if;

    -- Trường hợp 1: Có báo cáo mới được tạo
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

    -- Trường hợp 2: Tester nộp lại báo cáo sau khi bị từ chối trước đó
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

    -- Trường hợp 3: Chủ app duyệt hoặc từ chối báo cáo
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            -- Tester được chủ app duyệt báo cáo (thưởng thêm +1 Credit, đã gồm tip nếu có)
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
            -- Tester bị từ chối báo cáo: thông báo thu hồi đúng số Credit tạm nhận
            v_clawed := greatest(0, coalesce(new.provisional_clawed, 0));

            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                case
                    when v_clawed > 0 and new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Lý do: "%s". %s Credit tạm nhận đã bị thu hồi. Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason, v_clawed)
                    when v_clawed > 0 then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. %s Credit tạm nhận đã bị thu hồi. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), v_clawed)
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Lý do: "%s". Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'))
                end,
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'rejected', 'reason', new.rejection_reason, 'credits_clawed', v_clawed)
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
-- 9. Cron trừ phạt vắng mặt 00:05 giờ VN (17:05 UTC)
-- ============================================================================
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        begin
            perform cron.unschedule('daily_inactivity_penalty_job');
        exception when others then null;
        end;
        perform cron.schedule(
            'daily_inactivity_penalty_job',
            '5 17 * * *',
            'select public.apply_daily_inactivity_penalty();'
        );
    else
        raise notice 'pg_cron chưa được bật — bật tại Database > Extensions rồi chạy lại migration 030 để tự động trừ phạt vắng mặt lúc 00:05 (giờ VN).';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;


notify pgrst, 'reload schema';


-- ============================================================================
-- BO SUNG (dong bo migration 031) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

-- ============================================================================
-- 12 Tester — Supabase migration 031: Fix toàn vẹn luồng duyệt báo cáo
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nội dung sửa (theo báo cáo kiểm tra luồng Tester ↔ Chủ app):
-- 1. Bảng audit report_review_events: ghi mọi lần duyệt/từ chối/auto-duyệt.
--    - Sửa lỗi bộ đếm cảnh báo: trước đây đếm report có status='rejected',
--      nhưng tester nộp lại làm status chuyển 'pending' nên mất dấu lần từ chối
--      → vòng reject → resubmit → reject không bao giờ đạt 3 lần/ngày.
--    - Ngày warning dùng ngày VN thay vì current_date (UTC).
-- 2. approve_tester_report: vô hiệu hóa p_bonus (trước đây chủ app có thể gọi
--    API trực tiếp để mint Credit không giới hạn cho tester).
-- 3. submit_tester_report:
--    - Khóa dòng report hôm nay FOR UPDATE (không đua với chủ app duyệt).
--    - Báo cáo đã duyệt: chỉ cho bổ sung nội dung, KHÔNG đổi ảnh/rating.
--    - Nộp lại sau từ chối: bắt buộc ảnh mới, khác ảnh đã bị từ chối
--      (so image_hash, fallback so URL) → chặn nộp lại đúng ảnh cũ.
--    - Update có điều kiện status/reviewed_at, chống ghi đè sau khi đã duyệt.
-- 4. Trigger thông báo: báo chủ app khi tester sửa báo cáo đang pending.
-- 5. get_campaign_detail: tự chạy auto_accept_expired_reports() để báo cáo
--    quá 8h vẫn được duyệt ngay cả khi pg_cron chưa bật.
-- ============================================================================


-- ============================================================================
-- 1. Bảng audit sự kiện review
-- ============================================================================
create table if not exists public.report_review_events (
    id           uuid primary key default gen_random_uuid(),
    report_id    uuid not null references public.tester_reports(id) on delete cascade,
    campaign_id  uuid not null references public.campaigns(id) on delete cascade,
    tester_id    uuid not null references public.profiles(id) on delete cascade,
    owner_id     uuid references public.profiles(id) on delete set null,
    reviewer_id  uuid references public.profiles(id) on delete set null,
    event_type   text not null check (event_type in ('reject', 'approve', 'auto_accept')),
    reason       text,
    created_at   timestamptz not null default now()
);

create index if not exists idx_review_events_tester_day
    on public.report_review_events (tester_id, created_at desc);
create index if not exists idx_review_events_owner_day
    on public.report_review_events (owner_id, created_at desc);
create index if not exists idx_review_events_report
    on public.report_review_events (report_id, created_at desc);
-- Tăng tốc quét báo cáo quá 8h cần auto-duyệt (get_campaign_detail gọi mỗi lần tải).
create index if not exists idx_tester_reports_pending_created
    on public.tester_reports (created_at)
    where status = 'pending';

alter table public.report_review_events enable row level security;
-- Chỉ các RPC security definer (owner của function) được ghi/đọc.
revoke all on table public.report_review_events from public, anon, authenticated;
grant all on table public.report_review_events to service_role;


-- ============================================================================
-- 2. reject_tester_report — đếm cảnh báo bằng audit event + ngày VN
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
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;

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

    -- Ghi audit TRƯỚC khi tính cảnh báo: event này cũng được tính vào hôm nay.
    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'reject', p_reason
    );

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    -- 1. Thu hồi đúng số Credit THỰC đã cấp khi nộp
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(2, coalesce(v_report.provisional_granted_credits, 2)))
    );

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

    -- 3. Chỉ xóa checkin TẠM TÍNH (không xóa checkin xác nhận khác trong ngày)
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

    -- 4. Cảnh báo tester: đếm từ audit event (không bị reset khi nộp lại)
    select count(*) into v_tester_today_rejections
    from public.report_review_events
    where tester_id = v_report.user_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_report.user_id
              and warning_type = 'tester_rejection_streak'
              and warning_date = v_vn_today
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_report.user_id, 'tester_rejection_streak', v_msg_tester, v_vn_today);
            v_tester_warned := true;
            v_tester_warning_msg := v_msg_tester;

            select count(*) into v_tester_weekly_warnings
            from public.user_warnings
            where user_id = v_report.user_id
              and warning_date >= (v_vn_today - 6);

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    -- 5. Cảnh báo chủ app: đếm từ audit event
    select count(*) into v_owner_today_rejections
    from public.report_review_events
    where owner_id = v_caller_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_caller_id
              and warning_type = 'owner_excessive_rejections'
              and warning_date = v_vn_today
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_caller_id, 'owner_excessive_rejections', v_msg_owner, v_vn_today);
            v_owner_warned := true;
            v_owner_warning_msg := v_msg_owner;

            select count(*) into v_owner_weekly_warnings
            from public.user_warnings
            where user_id = v_caller_id
              and warning_date >= (v_vn_today - 6);

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

revoke execute on function public.reject_tester_report(uuid, text) from public, anon;
grant execute on function public.reject_tester_report(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 3. approve_tester_report — vô hiệu hóa p_bonus, ghi audit event
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
    -- p_bonus giữ lại trong chữ ký để tương thích client cũ nhưng KHÔNG còn
    -- hiệu lực: trước đây p_bonus không giới hạn và không trừ Credit ai cả.
    v_extra_reward int := 1;
    v_tip int := 0;
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

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    -- Tip thật (nếu chủ app có đặt): trừ Credit chủ app, cộng cho tester
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);

    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

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
        'tip_awarded', v_tip,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'message', format('Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester)', v_extra_reward)
    );
end;
$$;

revoke execute on function public.approve_tester_report(uuid, int) from public, anon;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;


-- ============================================================================
-- 4. auto_accept_expired_reports — ghi audit event auto_accept
-- ============================================================================
create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_count int := 0;
    v_owner_id uuid;
    v_camp_name text;
    v_tip int;
    v_total_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        insert into public.report_review_events (
            report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
        ) values (
            v_report.id, v_report.campaign_id, v_report.user_id,
            v_owner_id, null, 'auto_accept'
        );

        update public.checkins
        set note = 'Hệ thống tự động duyệt sau 8h'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (
                v_report.campaign_id, v_report.user_id,
                (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                'Hệ thống tự động duyệt sau 8h'
            )
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Hệ thống tự động duyệt sau 8h';
        end if;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên 2 Credit đã nhận + %s Credit boa từ chủ app). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên 2 Credit đã nhận). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'))
            end,
            'report',
            jsonb_build_object('campaign_id', v_report.campaign_id, 'report_id', v_report.id, 'status', 'approved', 'auto_accepted', true, 'tip_awarded', v_tip)
        );

        v_count := v_count + 1;
    end loop;

    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;


-- ============================================================================
-- 5. submit_tester_report — khóa dòng, bắt buộc ảnh mới khi nộp lại,
--    đóng băng ảnh/rating sau khi duyệt
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
    v_provisional_amount int := 0;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
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

    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lấy report hôm nay TRƯỚC và khóa dòng: không đua với thao tác duyệt/từ chối.
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1
    for update;

    -- Ảnh mới = URL khác ảnh hiện tại. Báo cáo đã duyệt thì không nhận ảnh mới.
    v_new_screenshot := p_screenshot_url is not null
        and (v_existing_report.id is null
             or p_screenshot_url is distinct from v_existing_report.screenshot_url);

    -- Feedback: báo cáo đã duyệt thì giữ nguyên rating, chỉ cho bổ sung comment.
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (
        p_campaign_id,
        v_user_id,
        case when v_existing_report.status = 'approved'
             then coalesce(v_existing_report.rating, v_rating)
             else v_rating end,
        coalesce(p_content, ''),
        now()
    )
    on conflict (campaign_id, user_id)
    do update set
        rating = case
            when v_existing_report.status = 'approved' then public.feedbacks.rating
            else excluded.rating
        end,
        comment = case
            when coalesce(excluded.comment, '') <> '' then excluded.comment
            else public.feedbacks.comment
        end,
        updated_at = now();

    -- Checkin "xác nhận" hôm nay = checkin không phải tạm tính
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
    ) into v_already_checked_in;

    if v_existing_report.id is not null then
        -- Trường hợp 1: đã duyệt -> chỉ bổ sung nội dung, giữ nguyên ảnh & rating
        if v_existing_report.status = 'approved' then
            update public.tester_reports
            set content = case
                when coalesce(p_content, '') <> '' then p_content
                else content
            end
            where id = v_existing_report.id
              and status = 'approved'
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := 'approved';

        -- Trường hợp 2: bị từ chối thật -> nộp lại, BẮT BUỘC ảnh mới khác ảnh cũ
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if not v_new_screenshot then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is not null
               and p_image_hash is not null
               and p_image_hash = v_existing_report.image_hash then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is null
               and p_image_hash is null
               and p_screenshot_url = v_existing_report.screenshot_url then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_already_checked_in then
                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    provisional_clawed = 0,
                    provisional_granted_credits = 0,
                    rejection_reason = null,
                    image_hash = p_image_hash,
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            else
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
                    v_provisional_amount := v_regrant;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = v_regrant,
                    provisional_clawed = 0,
                    provisional_granted_credits = v_regrant,
                    rejection_reason = null,
                    image_hash = p_image_hash,
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            end if;

        -- Trường hợp 3: pending (hoặc rejected legacy chưa review) -> cho chỉnh sửa ảnh/nội dung
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = case when v_new_screenshot then p_screenshot_url else screenshot_url end,
                rating = v_rating,
                image_hash = case when v_new_screenshot then p_image_hash else image_hash end
            where id = v_existing_report.id
              and status = v_existing_report.status
              and reviewed_at is not distinct from v_existing_report.reviewed_at
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := v_existing_report.status;
        end if;
    else
        -- Trường hợp 4: chưa có báo cáo hôm nay
        if v_already_checked_in then
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 0, p_image_hash, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
        else
            update public.profiles
            set credits = credits + 2,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id, 2, 'test_provisional',
                'Tạm nhận 2 Credit sau khi nộp kết quả test (chờ chủ app duyệt)',
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 2, p_image_hash, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := 2;
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount
    );
end;
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, service_role;


-- ============================================================================
-- 6. Trigger thông báo: thêm nhánh tester sửa báo cáo đang pending
-- ============================================================================
create or replace function public.trg_notify_tester_report()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
    v_clawed int;
begin
    if coalesce(current_setting('app.suppress_report_notify', true), '') = '1' then
        return new;
    end if;

    -- 1. Báo cáo mới
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

    -- 2. Tester nộp lại báo cáo sau khi bị từ chối
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

    -- 3. Tester sửa báo cáo đang chờ duyệt (đổi ảnh/nội dung) -> báo chủ app
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status = 'pending'
          and (old.screenshot_url is distinct from new.screenshot_url
               or old.image_hash is distinct from new.image_hash
               or old.content is distinct from new.content) then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Tester cập nhật báo cáo ✏️',
                format('Tester vừa cập nhật báo cáo kiểm thử cho ứng dụng "%s". Vui lòng xem lại ảnh/nội dung mới trước khi duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending', 'is_edited', true)
            );
        end if;

    -- 4. Chủ app duyệt hoặc từ chối
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
            v_clawed := greatest(0, coalesce(new.provisional_clawed, 0));

            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                case
                    when v_clawed > 0 and new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Lý do: "%s". %s Credit tạm nhận đã bị thu hồi. Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason, v_clawed)
                    when v_clawed > 0 then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. %s Credit tạm nhận đã bị thu hồi. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), v_clawed)
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Lý do: "%s". Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'))
                end,
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'rejected', 'reason', new.rejection_reason, 'credits_clawed', v_clawed)
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
-- 7. get_campaign_detail — tự auto-duyệt báo cáo quá 8h trước khi trả dữ liệu
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

    -- Báo cáo quá 8h phải được duyệt ngay cả khi pg_cron chưa bật.
    perform public.auto_accept_expired_reports();

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

revoke execute on function public.get_campaign_detail(uuid) from public, anon;
grant execute on function public.get_campaign_detail(uuid) to authenticated, service_role;


notify pgrst, 'reload schema';




-- ============================================================================
-- BO SUNG (dong bo migration 032) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

-- ============================================================================
-- 12 Tester — Supabase migration 032: Admin pause/resume campaign controls
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nghiệp vụ:
-- 1. Admin có thể tạm dừng / tiếp tục nhận tester cho app của bất kỳ chủ app.
-- 2. Admin tạm dừng app -> chủ app KHÔNG thể tự bấm "Tiếp tục nhận tester".
-- 3. Admin tiếp tục app bị auto-pause vì chủ vắng 3 ngày -> đánh dấu
--    admin_resumed để cron không tự tạm dừng lại ngay.
-- 4. Vẫn giữ nguyên luật 30 ngày hiển thị và luật Slot hết hạn.
-- 5. Không bao giờ đổi owner_id khi Admin thao tác.
-- ============================================================================


-- ============================================================================
-- 1. Cột phục vụ Admin pause/resume
-- ============================================================================

alter table public.campaigns add column if not exists admin_note text;
alter table public.campaigns add column if not exists admin_resumed boolean not null default false;

comment on column public.campaigns.admin_note is
    'Lý do Admin tạm dừng app; hiển thị cho chủ app. Bị xóa khi app mở lại.';
comment on column public.campaigns.admin_resumed is
    'True khi Admin mở lại app vốn bị auto-pause vì chủ vắng; cron bỏ qua auto-pause.';

create index if not exists idx_campaigns_admin_resumed
    on public.campaigns(admin_resumed)
    where admin_resumed = true;


-- ============================================================================
-- 2. Trigger vòng đời: đồng bộ admin_note / admin_resumed
-- ============================================================================

create or replace function public.trg_campaign_status_lifecycle()
returns trigger
language plpgsql
as $$
begin
    if new.status in ('production', 'completed') then
        if new.completed_at is null then
            new.completed_at := now();
        end if;
        new.admin_resumed := false;
        -- Giữ admin_note khi app hết hạn: guard bên dưới dựa vào việc note
        -- không đổi để phân biệt hệ thống hoàn thành app với chủ app lách luật.
    elsif new.status = 'paused' then
        new.admin_resumed := false;
    elsif new.status = 'open' then
        new.completed_at := null;
        new.paused_reason := null;
        new.admin_note := null;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_campaign_status_lifecycle on public.campaigns;
create trigger trg_campaign_status_lifecycle
    before insert or update of status on public.campaigns
    for each row execute function public.trg_campaign_status_lifecycle();


-- ============================================================================
-- 3. _campaign_json: bổ sung admin_note cho model Campaign phía client
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
        'admin_note', c.admin_note,
        'completed_at', to_char(c.completed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(c.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
$$;

revoke execute on function public._campaign_json(public.campaigns) from public;


-- ============================================================================
-- 4. Auto-pause vì chủ vắng: bỏ qua app do Admin đã mở lại
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
begin
    for r in
        update public.campaigns c
        set status = 'paused',
            paused_reason = 'owner_inactive'
        from public.profiles p
        where c.owner_id = p.id
          and c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
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

grant execute on function public.pause_inactive_owner_campaigns() to authenticated, service_role;


-- ============================================================================
-- 5. Chủ app resume: chặn app đang bị Admin tạm dừng + reset admin_resumed
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

    -- App bị Admin tạm dừng: chủ app không được tự mở lại (kể cả khi đã hết
    -- 30 ngày — cron/sync_campaign_lifecycle sẽ tự chuyển app sang hoàn thành).
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

    -- Slot > 1 phải còn hạn thì mới được mở lại (tránh mở app rồi bị auto-pause lại).
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

    -- Chủ app vẫn đang vắng 3 ngày liên tục -> yêu cầu làm nhiệm vụ ngày trước.
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

grant execute on function public.resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 6. RPC: Admin tạm dừng bất kỳ app nào
-- ============================================================================

drop function if exists public.admin_pause_campaign(uuid, text);

create or replace function public.admin_pause_campaign(
    p_campaign_id uuid,
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
    v_camp public.campaigns%rowtype;
    v_note text;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select lower(role) into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'NOT_ADMIN';
    end if;

    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status in ('production', 'completed') then
        raise exception 'CAMPAIGN_FINALIZED';
    end if;

    if v_camp.status not in ('open', 'paused') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    v_note := nullif(btrim(coalesce(p_reason, '')), '');

    -- Idempotent: đã tạm dừng bởi Admin với cùng lý do thì không gửi trùng thông báo.
    if v_camp.status = 'paused'
       and v_camp.paused_reason = 'admin_manual'
       and v_camp.admin_note is not distinct from v_note then
        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'previous_status', v_camp.status,
            'status', v_camp.status,
            'paused_reason', v_camp.paused_reason,
            'app_name', v_camp.app_name,
            'owner_id', v_camp.owner_id,
            'admin_note', v_camp.admin_note
        );
    end if;

    update public.campaigns
    set status = 'paused',
        paused_reason = 'admin_manual',
        admin_note = v_note,
        admin_resumed = false
    where id = p_campaign_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_camp.owner_id,
        'Ứng dụng đã bị tạm dừng bởi Admin ⏸',
        format(
            'Ứng dụng "%s" đã bị Quản trị viên tạm dừng.%s',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
            case when v_note is null then '' else E'\nLý do: ' || v_note end
        ),
        'campaign',
        jsonb_build_object(
            'campaign_id', p_campaign_id,
            'reason', 'admin_pause',
            'admin_note', v_note
        )
    );

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'previous_status', v_camp.status,
        'status', 'paused',
        'paused_reason', 'admin_manual',
        'app_name', v_camp.app_name,
        'owner_id', v_camp.owner_id,
        'admin_note', v_note
    );
end;
$$;

revoke execute on function public.admin_pause_campaign(uuid, text) from public, anon;
grant execute on function public.admin_pause_campaign(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 7. RPC: Admin tiếp tục app đang tạm dừng
-- ============================================================================

drop function if exists public.admin_resume_campaign(uuid);

create or replace function public.admin_resume_campaign(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_camp public.campaigns%rowtype;
    v_hold_open boolean;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select lower(role) into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'NOT_ADMIN';
    end if;

    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status in ('production', 'completed') then
        raise exception 'CAMPAIGN_FINALIZED';
    end if;

    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    -- Vẫn giữ luật 30 ngày hiển thị.
    if v_camp.created_at <= now() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_camp.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 30 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
                coalesce(nullif(v_camp.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'window_expired')
        );

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'previous_status', v_camp.status,
            'status', 'completed',
            'app_name', v_camp.app_name,
            'owner_id', v_camp.owner_id
        );
    end if;

    -- Không cho Admin mở app đã hết hạn Slot: chủ app vẫn phải gia hạn Slot.
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

    -- Chỉ giữ mở khi app bị auto-pause vì chủ vắng. Các lý do khác
    -- (chủ tự tạm dừng, Admin tạm dừng) trả về luật bình thường.
    v_hold_open := (v_camp.paused_reason = 'owner_inactive');

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null,
        admin_note = null,
        admin_resumed = v_hold_open
    where id = p_campaign_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_camp.owner_id,
        'Ứng dụng đã được Admin mở lại ✅',
        format(
            'Ứng dụng "%s" đã được Quản trị viên mở lại và đang nhận tester bình thường.',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn')
        ),
        'campaign',
        jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'admin_resume')
    );

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'previous_status', v_camp.status,
        'status', 'open',
        'paused_reason', null,
        'app_name', v_camp.app_name,
        'owner_id', v_camp.owner_id,
        'admin_resumed', v_hold_open
    );
end;
$$;

revoke execute on function public.admin_resume_campaign(uuid) from public, anon;
grant execute on function public.admin_resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 8. Chặn cứng ở DB: owner không thể tự mở app bị Admin tạm dừng,
--    không thể tự bật cờ Admin, và owner_id không bị đổi qua client.
-- ============================================================================

create or replace function public.trg_campaigns_guard_admin_actions()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_role text;
begin
    -- SQL Editor / service_role không có auth.uid -> cho phép.
    if v_uid is null then
        return new;
    end if;

    -- Chặn client tự tạo campaign với cờ/lý do của Admin.
    if tg_op = 'INSERT' then
        if new.admin_resumed
           or new.admin_note is not null
           or new.paused_reason = 'admin_manual' then
            raise exception 'PERMISSION_DENIED';
        end if;
        return new;
    end if;

    -- Không ai (kể cả Admin qua client) được đổi chủ sở hữu campaign.
    -- SQL Editor / service_role vẫn có thể sửa trực tiếp khi thật sự cần.
    if new.owner_id is distinct from old.owner_id then
        raise exception 'OWNER_ID_IMMUTABLE';
    end if;

    -- Chỉ Admin được bật cờ bỏ qua auto-pause; chủ app không được tự bật.
    if new.admin_resumed and not coalesce(old.admin_resumed, false) then
        select lower(p.role) into v_role
        from public.profiles p
        where p.id = v_uid;

        if coalesce(v_role, '') <> 'admin' then
            raise exception 'PERMISSION_DENIED';
        end if;
    end if;

    -- App đang bị Admin tạm dừng: chủ app không được mở lại, không được xóa
    -- paused_reason/admin_note để lách luật. Hệ thống vẫn được chuyển app sang
    -- 'completed' khi hết 30 ngày vì update đó không đổi paused_reason/admin_note.
    if old.paused_reason = 'admin_manual' then
        if new.status = 'open'
           or new.paused_reason is distinct from 'admin_manual'
           or new.admin_note is distinct from old.admin_note
        then
            select lower(p.role) into v_role
            from public.profiles p
            where p.id = v_uid;

            if coalesce(v_role, '') <> 'admin' then
                raise exception 'PAUSED_BY_ADMIN';
            end if;
        end if;
    end if;

    return new;
end;
$$;

drop trigger if exists trg_campaigns_guard_admin_actions on public.campaigns;
create trigger trg_campaigns_guard_admin_actions
    before insert or update on public.campaigns
    for each row execute function public.trg_campaigns_guard_admin_actions();


-- ============================================================================
-- 9. Reload PostgREST schema cache
-- ============================================================================

notify pgrst, 'reload schema';


-- ============================================================================
-- BO SUNG (dong bo migration 033) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

-- ============================================================================
-- 033_media_integrity.sql
--
-- Mục tiêu:
-- 1. Chống nộp trùng khi client retry/timeout: client_submit_id + idempotency.
-- 2. Bắt buộc image_hash hợp lệ khi có ảnh mới; so ảnh gần trùng bằng dHash.
-- 3. Chỉ nhận screenshot URL thuộc đúng campaign/user trên Firebase Storage.
-- 4. Chặn client ghi thẳng tester_reports (chỉ RPC SECURITY DEFINER được ghi).
-- ============================================================================

alter table public.tester_reports
    add column if not exists client_submit_id uuid;

-- Mỗi user chỉ có một report cho một client_submit_id. Index partial vì
-- report cũ chưa có client_submit_id.
create unique index if not exists idx_tester_reports_user_client_submit
    on public.tester_reports (user_id, client_submit_id)
    where client_submit_id is not null;

-- Hash dHash là 16 ký tự hex. NOT VALID để không chặn dữ liệu cũ sai format,
-- nhưng mọi row mới/sửa sau migration đều phải đúng.
alter table public.tester_reports
    drop constraint if exists tester_reports_image_hash_format;
alter table public.tester_reports
    add constraint tester_reports_image_hash_format
    check (image_hash is null or image_hash ~ '^[0-9a-f]{16}$') not valid;

comment on column public.tester_reports.client_submit_id is
    'Idempotency key do client sinh ra cho một lần nộp; retry dùng lại cùng id.';

-- Khoảng cách Hamming giữa 2 dHash 64-bit. Trả 64 nếu input không hợp lệ.
create or replace function public.dhash_hamming(p_a text, p_b text)
returns int
language sql
immutable
as $$
    select case
        when p_a is null or p_b is null then 64
        when lower(p_a) !~ '^[0-9a-f]{16}$' or lower(p_b) !~ '^[0-9a-f]{16}$' then 64
        else length(
            replace(
                (
                    (('x' || lower(p_a))::bit(64))
                    #
                    (('x' || lower(p_b))::bit(64))
                )::text,
                '0',
                ''
            )
        )
    end;
$$;

-- URL ảnh phải nằm trong path của đúng campaign + user. Chấp nhận cả path mới
-- (reports/<campaign>/<user>/<file>) và path cũ (reports/<campaign>/<user>_<ts>)
-- để app cũ chưa update vẫn nộp được trong giai đoạn chuyển tiếp.
create or replace function public.is_valid_report_screenshot_url(
    p_url text,
    p_campaign_id uuid,
    p_user_id uuid
)
returns boolean
language sql
immutable
as $$
    select p_url is not null
       and length(p_url) <= 2048
       and (
           p_url like 'https://firebasestorage.googleapis.com/v0/b/%'
           or p_url like 'https://storage.googleapis.com/%'
       )
       and (
           p_url like '%reports%2F' || p_campaign_id::text || '%2F' || p_user_id::text || '%2F%'
           or p_url like '%reports%2F' || p_campaign_id::text || '%2F' || p_user_id::text || '_%'
           or p_url like '%/reports/' || p_campaign_id::text || '/' || p_user_id::text || '/%'
           or p_url like '%/reports/' || p_campaign_id::text || '/' || p_user_id::text || '_%'
       );
$$;

drop function if exists public.submit_tester_report(uuid, text, text, int, text);
drop function if exists public.submit_tester_report(uuid, text, text, int, text, uuid);

create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5,
    p_image_hash text default null,
    p_client_submit_id uuid default null
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
    v_idempotent_report record;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_provisional_amount int := 0;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_final_url text;
    v_final_hash text;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Idempotency: nếu RPC đã commit nhưng response bị mất/timeout, lần retry
    -- trả lại đúng report cũ, không cộng credit lần hai.
    if p_client_submit_id is not null then
        select * into v_idempotent_report
        from public.tester_reports
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_idempotent_report.id is not null then
            return jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_idempotent_report.screenshot_url,
                'image_hash', v_idempotent_report.image_hash
            );
        end if;
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lấy report hôm nay TRƯỚC và khóa dòng: không đua với thao tác duyệt/từ chối.
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1
    for update;

    -- Ảnh mới = URL khác ảnh hiện tại. Báo cáo đã duyệt thì không nhận ảnh mới.
    v_new_screenshot := p_screenshot_url is not null
        and (v_existing_report.id is null
             or p_screenshot_url is distinct from v_existing_report.screenshot_url);

    if p_image_hash is not null and lower(p_image_hash) !~ '^[0-9a-f]{16}$' then
        raise exception 'INVALID_IMAGE_HASH';
    end if;

    if v_new_screenshot then
        if p_image_hash is null or p_image_hash = '' then
            raise exception 'IMAGE_HASH_REQUIRED';
        end if;
        if not public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) then
            raise exception 'INVALID_SCREENSHOT_URL';
        end if;
    end if;

    -- Feedback: báo cáo đã duyệt thì giữ nguyên rating, chỉ cho bổ sung comment.
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (
        p_campaign_id,
        v_user_id,
        case when v_existing_report.status = 'approved'
             then coalesce(v_existing_report.rating, v_rating)
             else v_rating end,
        coalesce(p_content, ''),
        now()
    )
    on conflict (campaign_id, user_id)
    do update set
        rating = case
            when v_existing_report.status = 'approved' then public.feedbacks.rating
            else excluded.rating
        end,
        comment = case
            when coalesce(excluded.comment, '') <> '' then excluded.comment
            else public.feedbacks.comment
        end,
        updated_at = now();

    -- Checkin "xác nhận" hôm nay = checkin không phải tạm tính
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
    ) into v_already_checked_in;

    if v_existing_report.id is not null then
        -- Trường hợp 1: đã duyệt -> chỉ bổ sung nội dung, giữ nguyên ảnh & rating
        if v_existing_report.status = 'approved' then
            update public.tester_reports
            set content = case
                when coalesce(p_content, '') <> '' then p_content
                else content
            end,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
              and status = 'approved'
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := 'approved';

        -- Trường hợp 2: bị từ chối thật -> nộp lại, BẮT BUỘC ảnh mới khác ảnh cũ
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if not v_new_screenshot then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is not null
               and p_image_hash is not null
               and public.dhash_hamming(v_existing_report.image_hash, p_image_hash) <= 5 then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is null
               and p_image_hash is null
               and p_screenshot_url = v_existing_report.screenshot_url then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_already_checked_in then
                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    provisional_clawed = 0,
                    provisional_granted_credits = 0,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            else
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
                    v_provisional_amount := v_regrant;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = v_regrant,
                    provisional_clawed = 0,
                    provisional_granted_credits = v_regrant,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            end if;

        -- Trường hợp 3: pending (hoặc rejected legacy chưa review) -> cho chỉnh sửa ảnh/nội dung
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = case when v_new_screenshot then p_screenshot_url else screenshot_url end,
                rating = v_rating,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
              and status = v_existing_report.status
              and reviewed_at is not distinct from v_existing_report.reviewed_at
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := v_existing_report.status;
        end if;
    else
        -- Trường hợp 4: chưa có báo cáo hôm nay
        if v_already_checked_in then
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 0,
                lower(p_image_hash), p_client_submit_id, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
        else
            update public.profiles
            set credits = credits + 2,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id, 2, 'test_provisional',
                'Tạm nhận 2 Credit sau khi nộp kết quả test (chờ chủ app duyệt)',
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 2,
                lower(p_image_hash), p_client_submit_id, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := 2;
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    select screenshot_url, image_hash
    into v_final_url, v_final_hash
    from public.tester_reports
    where id = v_report_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount,
        'screenshot_url', v_final_url,
        'image_hash', v_final_hash
    );
end;
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text, uuid) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text, uuid) to authenticated, service_role;

-- Chỉ RPC SECURITY DEFINER được ghi report. Client chỉ còn quyền SELECT theo RLS.
revoke insert, update, delete on public.tester_reports from PUBLIC, anon, authenticated;

comment on function public.submit_tester_report(uuid, text, text, int, text, uuid) is
    'Nộp báo cáo idempotent, bắt buộc hash/URL ảnh hợp lệ và chặn ảnh gần trùng khi nộp lại.';


-- ============================================================================
-- BO SUNG (dong bo migration 034) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

-- ============================================================================
-- 034_admin_tester_flow.sql
-- Tách quyền chủ app thật khỏi quyền quản lý:
--   is_actual_owner = owner_id = auth.uid()
--   can_manage      = owner thật hoặc admin/moderator (giữ quyền xem báo cáo cũ)
--   is_owner        = legacy cho client cũ (owner + admin + moderator)
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
    v_is_owner boolean := false;          -- legacy: owner + admin/moderator
    v_is_actual_owner boolean := false;   -- chỉ chủ app thật
    v_can_manage boolean := false;        -- owner + admin/moderator (quyền xem báo cáo)
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

    -- Báo cáo quá 8h phải được duyệt ngay cả khi pg_cron chưa bật.
    perform public.auto_accept_expired_reports();

    v_is_actual_owner := (v_camp.owner_id = v_uid);

    v_can_manage := v_is_actual_owner
        or exists (
            select 1 from public.profiles p
            where p.id = v_uid and lower(p.role) in ('admin', 'moderator')
        );

    -- Legacy cho client cũ: giữ nguyên owner + admin/moderator.
    v_is_owner := v_can_manage;

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
          and (v_can_manage or user_id = v_uid)
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
        where tr.campaign_id = p_campaign_id and (v_can_manage or tr.user_id = v_uid)
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
        'is_owner', v_is_owner,                 -- legacy cho client cũ
        'is_actual_owner', v_is_actual_owner,
        'can_manage', v_can_manage,
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

revoke execute on function public.get_campaign_detail(uuid) from public, anon;
grant execute on function public.get_campaign_detail(uuid) to authenticated, service_role;


notify pgrst, 'reload schema';



-- ============================================================================
-- BO SUNG (dong bo migration 035) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

-- ============================================================================
-- 035_admin_penalty_exemption.sql
-- Miễn phạt vắng mặt cho admin/moderator:
--   1. Không trừ Credit bởi apply_daily_inactivity_penalty()
--   2. Không auto-pause app bởi pause_inactive_owner_campaigns()
--   3. Reset missed_streak cũ để UI không còn cảnh báo phạt
-- ============================================================================

-- 1. Miễn trừ Credit penalty
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
          and lower(role) not in ('admin', 'moderator')
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

        -- Trừ tối thiểu 1 credit nếu tỉ lệ % ra nhỏ hơn 1
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

-- 2. Miễn auto-pause app của admin/moderator
create or replace function public.pause_inactive_owner_campaigns()
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
        set status = 'paused',
            paused_reason = 'owner_inactive'
        from public.profiles p
        where c.owner_id = p.id
          and c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
          and c.created_at <= now() - interval '24 hours'
          and greatest(p.missed_streak, (current_date - p.last_active_date - 1)) >= 3
          and lower(p.role) not in ('admin', 'moderator')
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

-- 3. Reset streak cũ của admin/moderator
update public.profiles
set missed_streak = 0
where lower(role) in ('admin', 'moderator')
  and missed_streak <> 0;

-- Giữ nguyên quyền function
revoke execute on function public.apply_daily_inactivity_penalty()
    from public, anon, authenticated;
grant execute on function public.apply_daily_inactivity_penalty()
    to service_role;

grant execute on function public.pause_inactive_owner_campaigns()
    to authenticated, service_role;

notify pgrst, 'reload schema';

-- ============================================================================
-- BO SUNG (dong bo migration 036) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

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


-- ============================================================================
-- BO SUNG (dong bo migration 037) — cac migration nay DA CHAY tren cloud
-- Singapore tu truoc; khoi duoi day chi la mirror lai vao file chuan de file
-- phan anh dung trang thai hien tai cua cloud. KHONG can chay lai tren cloud.
-- ============================================================================

-- ============================================================================
-- 12 Tester — Supabase migration 037: Cơ chế thưởng test app mới
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Quy tắc:
--   • Tester nhận 10 Credit tạm nhận ngay khi nộp báo cáo.
--   • Chủ app thật duyệt trong 8h: tester +2 (tổng 12), chủ app +2.
--   • Admin/Moderator duyệt: tester +2, chủ app không được +2.
--   • Quá 8h hệ thống tự duyệt: tester giữ 10, không ai được +2.
--   • Từ chối: thu hồi đúng số tạm nhận thực tế (10; báo cáo cũ 2).
--   • Nộp lại: cấp lại đúng số đã thu hồi, tối đa 10.
--   • Báo cáo cũ đang pending giữ nguyên mức 2, không backfill. Nếu chủ app
--     duyệt tay thì tester vẫn nhận thêm +2 (tổng 4); auto-duyệt giữ 2.
--   • Tip giữ nguyên: trả khi chủ app duyệt tay và khi auto-duyệt.
--   • Chặn duyệt/từ chối tay sau 8h.
-- ============================================================================

-- ============================================================================
-- 1. submit_tester_report — tạm nhận 10 Credit
-- ============================================================================
drop function if exists public.submit_tester_report(uuid, text, text, int, text);

create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5,
    p_image_hash text default null,
    p_client_submit_id uuid default null
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
    v_idempotent_report record;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_provisional_amount int := 0;
    v_provisional_reward int := 10;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_final_url text;
    v_final_hash text;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Idempotency: nếu RPC đã commit nhưng response bị mất/timeout, lần retry
    -- trả lại đúng report cũ, không cộng credit lần hai.
    if p_client_submit_id is not null then
        select * into v_idempotent_report
        from public.tester_reports
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_idempotent_report.id is not null then
            return jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_idempotent_report.screenshot_url,
                'image_hash', v_idempotent_report.image_hash
            );
        end if;
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lấy report hôm nay TRƯỚC và khóa dòng: không đua với thao tác duyệt/từ chối.
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1
    for update;

    -- Ảnh mới = URL khác ảnh hiện tại. Báo cáo đã duyệt thì không nhận ảnh mới.
    v_new_screenshot := p_screenshot_url is not null
        and (v_existing_report.id is null
             or p_screenshot_url is distinct from v_existing_report.screenshot_url);

    if p_image_hash is not null and lower(p_image_hash) !~ '^[0-9a-f]{16}$' then
        raise exception 'INVALID_IMAGE_HASH';
    end if;

    if v_new_screenshot then
        if p_image_hash is null or p_image_hash = '' then
            raise exception 'IMAGE_HASH_REQUIRED';
        end if;
        if not public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) then
            raise exception 'INVALID_SCREENSHOT_URL';
        end if;
    end if;

    -- Feedback: báo cáo đã duyệt thì giữ nguyên rating, chỉ cho bổ sung comment.
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (
        p_campaign_id,
        v_user_id,
        case when v_existing_report.status = 'approved'
             then coalesce(v_existing_report.rating, v_rating)
             else v_rating end,
        coalesce(p_content, ''),
        now()
    )
    on conflict (campaign_id, user_id)
    do update set
        rating = case
            when v_existing_report.status = 'approved' then public.feedbacks.rating
            else excluded.rating
        end,
        comment = case
            when coalesce(excluded.comment, '') <> '' then excluded.comment
            else public.feedbacks.comment
        end,
        updated_at = now();

    -- Checkin "xác nhận" hôm nay = checkin không phải tạm tính
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
    ) into v_already_checked_in;

    if v_existing_report.id is not null then
        -- Trường hợp 1: đã duyệt -> chỉ bổ sung nội dung, giữ nguyên ảnh & rating
        if v_existing_report.status = 'approved' then
            update public.tester_reports
            set content = case
                when coalesce(p_content, '') <> '' then p_content
                else content
            end,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
              and status = 'approved'
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := 'approved';

        -- Trường hợp 2: bị từ chối thật -> nộp lại, BẮT BUỘC ảnh mới khác ảnh cũ
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if not v_new_screenshot then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is not null
               and p_image_hash is not null
               and public.dhash_hamming(v_existing_report.image_hash, p_image_hash) <= 5 then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is null
               and p_image_hash is null
               and p_screenshot_url = v_existing_report.screenshot_url then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_already_checked_in then
                -- Legacy: đã có checkin xác nhận trong ngày, không cấp thêm tạm nhận.
                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    provisional_clawed = 0,
                    provisional_granted_credits = 0,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            else
                -- Chỉ cấp lại đúng số đã thu hồi; báo cáo cũ tối đa 2, báo cáo mới tối đa 10.
                v_regrant := least(
                    v_provisional_reward,
                    greatest(
                        0,
                        coalesce(
                            v_existing_report.provisional_clawed,
                            v_existing_report.provisional_granted_credits,
                            2
                        )
                    )
                );

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
                    v_provisional_amount := v_regrant;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = v_regrant,
                    provisional_clawed = 0,
                    provisional_granted_credits = v_regrant,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            end if;

        -- Trường hợp 3: pending (hoặc rejected legacy chưa review) -> cho chỉnh sửa ảnh/nội dung
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = case when v_new_screenshot then p_screenshot_url else screenshot_url end,
                rating = v_rating,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
              and status = v_existing_report.status
              and reviewed_at is not distinct from v_existing_report.reviewed_at
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := v_existing_report.status;
        end if;
    else
        -- Trường hợp 4: chưa có báo cáo hôm nay
        if v_already_checked_in then
            -- Legacy: đã có checkin xác nhận trong ngày, không cấp thêm tạm nhận.
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 0,
                lower(p_image_hash), p_client_submit_id, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
        else
            update public.profiles
            set credits = credits + v_provisional_reward,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id, v_provisional_reward, 'test_provisional',
                format('Tạm nhận %s Credit sau khi nộp kết quả test (chờ chủ app duyệt)', v_provisional_reward),
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', v_provisional_reward, 0, v_provisional_reward,
                lower(p_image_hash), p_client_submit_id, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := v_provisional_reward;
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    select screenshot_url, image_hash
    into v_final_url, v_final_hash
    from public.tester_reports
    where id = v_report_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount,
        'screenshot_url', v_final_url,
        'image_hash', v_final_hash
    );
end;
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text, uuid) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text, uuid) to authenticated, service_role;


-- ============================================================================
-- 2. approve_tester_report — tester +2, chủ app thật +2, chặn sau 8h
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
    -- p_bonus giữ lại trong chữ ký để tương thích client cũ nhưng KHÔNG còn
    -- hiệu lực: trước đây p_bonus không giới hạn và không trừ Credit ai cả.
    v_extra_reward int := 2;
    v_tip int := 0;
    v_total_bonus int;
    v_new_balance int;
    v_owner_bonus int := 0;
    v_owner_balance int;
    v_is_owner boolean := false;
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

    -- Quá 8h: không cho duyệt tay, để auto_accept xử lý (không thưởng +2).
    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    v_is_owner := (v_camp.owner_id = v_caller_id);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    -- Tip thật (nếu chủ app có đặt): trừ Credit chủ app, cộng cho tester.
    -- Giữ nguyên hành vi cũ: tip được trả trước, sau đó mới cộng thưởng duyệt.
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);

    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

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
        format(
            '%s duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)',
            case when v_is_owner then 'Chủ app' else 'Admin/Moderator' end,
            v_extra_reward
        ),
        v_new_balance
    );

    -- Chỉ chủ app thật mới nhận +2 khi bấm duyệt.
    if v_is_owner then
        v_owner_bonus := 2;
        update public.profiles
        set credits = credits + v_owner_bonus
        where id = v_camp.owner_id
        returning credits into v_owner_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_camp.owner_id,
            v_owner_bonus,
            'owner_approval_bonus',
            format('Duyệt báo cáo kiểm thử (+%s Credit)', v_owner_bonus),
            v_owner_balance
        );
    end if;

    update public.checkins
    set note = 'Đã kiểm thử và được duyệt'
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    if not found then
        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được duyệt')
        on conflict (campaign_id, user_id, checkin_date)
        do update set note = 'Đã kiểm thử và được duyệt';
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'owner_credits_awarded', v_owner_bonus,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'owner_balance', v_owner_balance,
        'message', format(
            'Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester%s)',
            v_extra_reward,
            case when v_owner_bonus > 0 then format(' và +%s Credit cho bạn', v_owner_bonus) else '' end
        )
    );
end;
$$;

revoke execute on function public.approve_tester_report(uuid, int) from public, anon;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;


-- ============================================================================
-- 3. reject_tester_report — thu hồi đúng số tạm nhận, chặn sau 8h
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
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;

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

    -- Quá 8h: không cho từ chối tay, để auto_accept xử lý.
    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    -- Ghi audit TRƯỚC khi tính cảnh báo: event này cũng được tính vào hôm nay.
    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'reject', p_reason
    );

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    -- 1. Thu hồi đúng số Credit THỰC đã cấp khi nộp (10 mới, 2 cũ).
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(10, coalesce(v_report.provisional_granted_credits, 2)))
    );

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
            format('Thu hồi %s Credit tạm nhận do báo cáo bị từ chối: %s', v_deduct, coalesce(p_reason, 'Chưa đạt yêu cầu')),
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

    -- 3. Chỉ xóa checkin TẠM TÍNH (không xóa checkin xác nhận khác trong ngày)
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

    -- 4. Cảnh báo tester: đếm từ audit event (không bị reset khi nộp lại)
    select count(*) into v_tester_today_rejections
    from public.report_review_events
    where tester_id = v_report.user_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_report.user_id
              and warning_type = 'tester_rejection_streak'
              and warning_date = v_vn_today
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_report.user_id, 'tester_rejection_streak', v_msg_tester, v_vn_today);
            v_tester_warned := true;
            v_tester_warning_msg := v_msg_tester;

            select count(*) into v_tester_weekly_warnings
            from public.user_warnings
            where user_id = v_report.user_id
              and warning_date >= (v_vn_today - 6);

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    -- 5. Cảnh báo chủ app: đếm từ audit event
    select count(*) into v_owner_today_rejections
    from public.report_review_events
    where owner_id = v_caller_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_caller_id
              and warning_type = 'owner_excessive_rejections'
              and warning_date = v_vn_today
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_caller_id, 'owner_excessive_rejections', v_msg_owner, v_vn_today);
            v_owner_warned := true;
            v_owner_warning_msg := v_msg_owner;

            select count(*) into v_owner_weekly_warnings
            from public.user_warnings
            where user_id = v_caller_id
              and warning_date >= (v_vn_today - 6);

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

revoke execute on function public.reject_tester_report(uuid, text) from public, anon;
grant execute on function public.reject_tester_report(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 4. auto_accept_expired_reports — giữ 10, không thưởng +2, tip vẫn trả
-- ============================================================================
create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_count int := 0;
    v_owner_id uuid;
    v_camp_name text;
    v_tip int;
    v_base int;
    v_total_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded, provisional_granted_credits
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        v_base := coalesce(
            nullif(v_report.provisional_granted_credits, 0),
            nullif(v_report.bonus_awarded, 0),
            0
        );
        v_total_bonus := coalesce(nullif(v_report.bonus_awarded, 0), v_base) + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        insert into public.report_review_events (
            report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
        ) values (
            v_report.id, v_report.campaign_id, v_report.user_id,
            v_owner_id, null, 'auto_accept'
        );

        update public.checkins
        set note = 'Tự động duyệt sau 8h'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (
                v_report.campaign_id, v_report.user_id,
                (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                'Tự động duyệt sau 8h'
            )
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Tự động duyệt sau 8h';
        end if;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này + %s Credit boa từ chủ app). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base)
            end,
            'report',
            jsonb_build_object('campaign_id', v_report.campaign_id, 'report_id', v_report.id, 'status', 'approved', 'auto_accepted', true, 'tip_awarded', v_tip)
        );

        v_count := v_count + 1;
    end loop;

    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;


-- ============================================================================
-- 5. trg_notify_tester_report — cập nhật nội dung thông báo
-- ============================================================================
create or replace function public.trg_notify_tester_report()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
    v_clawed int;
    v_extra int;
begin
    if coalesce(current_setting('app.suppress_report_notify', true), '') = '1' then
        return new;
    end if;

    -- 1. Báo cáo mới
    if tg_op = 'INSERT' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo kiểm thử mới 📋',
                format('Ứng dụng "%s" vừa nhận được 1 báo cáo kiểm thử mới. Bạn có 8 giờ để kiểm tra và duyệt (+2 Credit thưởng cho tester và +2 Credit cho bạn nếu duyệt).', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending')
            );
        end if;

    -- 2. Tester nộp lại báo cáo sau khi bị từ chối
    elsif tg_op = 'UPDATE' and old.status = 'rejected' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo nộp lại 📋',
                format('Tester vừa nộp lại kết quả kiểm thử cho ứng dụng "%s" sau khi bị từ chối. Bạn có 8 giờ để kiểm tra và duyệt (+2 Credit thưởng cho tester và +2 Credit cho bạn nếu duyệt).', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending', 'is_resubmission', true)
            );
        end if;

    -- 3. Tester sửa báo cáo đang chờ duyệt (đổi ảnh/nội dung) -> báo chủ app
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status = 'pending'
          and (old.screenshot_url is distinct from new.screenshot_url
               or old.image_hash is distinct from new.image_hash
               or old.content is distinct from new.content) then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Tester cập nhật báo cáo ✏️',
                format('Tester vừa cập nhật báo cáo kiểm thử cho ứng dụng "%s". Vui lòng xem lại ảnh/nội dung mới trước khi duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending', 'is_edited', true)
            );
        end if;

    -- 4. Chủ app/Admin/Moderator duyệt hoặc từ chối
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            -- Chỉ báo phần tăng thêm của lượt duyệt này (thường +2, cộng tip nếu có),
            -- tránh ghi "tổng cộng" sai khi báo cáo từng bị clawback một phần.
            v_extra := greatest(0, coalesce(new.bonus_awarded, 0) - coalesce(old.bonus_awarded, 0));

            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử được phê duyệt! 🎉',
                format('Báo cáo của bạn cho ứng dụng "%s" đã được duyệt thành công! Bạn nhận thêm %s Credit cho lượt test này. Cảm ơn bạn!',
                    coalesce(v_camp_name, 'ứng dụng'),
                    v_extra
                ),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'approved')
            );
        elsif new.status = 'rejected' then
            v_clawed := greatest(0, coalesce(new.provisional_clawed, 0));

            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                case
                    when v_clawed > 0 and new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Lý do: "%s". %s Credit tạm nhận đã bị thu hồi. Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason, v_clawed)
                    when v_clawed > 0 then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. %s Credit tạm nhận đã bị thu hồi. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), v_clawed)
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Lý do: "%s". Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'))
                end,
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'rejected', 'reason', new.rejection_reason, 'credits_clawed', v_clawed)
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


notify pgrst, 'reload schema';


-- ============================================================================
-- BO SUNG (cac object cu con thieu cua migration 007 + 009) — cac ham/trigger
-- nay DA CHAY tren cloud Singapore tu truoc nhung bi thieu trong file chuan.
-- trg_notify_tester_report KHONG duoc bo sung o day vi ban moi nhat (migration
-- 037) da co san o khoi tren. KHONG can chay lai tren cloud.
-- ============================================================================

-- 6. Helper functions: get_unread_warnings & acknowledge_warning
create or replace function public.get_unread_warnings()
returns setof public.user_warnings
language sql
security definer
set search_path = public
as $$
    select * from public.user_warnings
    where user_id = auth.uid()
      and acknowledged = false
    order by created_at desc;
$$;

create or replace function public.acknowledge_warning(p_warning_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.user_warnings
    set acknowledged = true
    where id = p_warning_id
      and user_id = auth.uid();
    return true;
end;
$$;

-- 4. Database Trigger: Tự động tạo thông báo khi có biến động Credit (credit_logs)
create or replace function public.trg_notify_credit_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_title text;
begin
    -- Không gửi lặp lại cho 'test_approved' vì đã được thông báo chi tiết trong trigger duyệt báo cáo
    if new.action = 'test_approved' then
        return new;
    end if;

    if new.amount > 0 then
        v_title := format('Biến động số dư: +%s Credit 🪙', new.amount);
    else
        v_title := format('Biến động số dư: %s Credit 🪙', new.amount);
    end if;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        new.user_id,
        v_title,
        coalesce(new.note, format('Số dư hiện tại: %s Credit', new.balance)),
        'credit',
        jsonb_build_object('amount', new.amount, 'balance', new.balance, 'action', new.action)
    );

    return new;
exception when others then
    -- Tránh việc lỗi trigger làm rollback giao dịch chính
    return new;
end;
$$;

drop trigger if exists trg_on_credit_log_created on public.credit_logs;
create trigger trg_on_credit_log_created
    after insert on public.credit_logs
    for each row
    execute function public.trg_notify_credit_change();

-- 6. Database Trigger: Tự động tạo thông báo khi nhận Cảnh báo vi phạm (user_warnings)
create or replace function public.trg_notify_user_warning()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.notifications (user_id, title, message, type, data)
    values (
        new.user_id,
        'Cảnh báo từ hệ thống ⚠️',
        new.message,
        'warning',
        jsonb_build_object('warning_id', new.id, 'warning_type', new.warning_type)
    );
    return new;
exception when others then
    return new;
end;
$$;

drop trigger if exists trg_on_user_warning_created on public.user_warnings;
create trigger trg_on_user_warning_created
    after insert on public.user_warnings
    for each row
    execute function public.trg_notify_user_warning();

-- 7. Database Trigger: Tự động tạo thông báo khi Khóa hoặc Mở khóa tài khoản (profiles.is_locked)
create or replace function public.trg_notify_profile_lock_status()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if old.is_locked = false and new.is_locked = true then
        insert into public.notifications (user_id, title, message, type, data)
        values (
            new.id,
            'Tài khoản đã bị tạm khóa 🔒',
            'Tài khoản của bạn đã bị tạm khóa do vi phạm quy tắc kiểm thử hoặc quyết định từ Quản trị viên. Bạn có thể tự mở khóa bằng 100 Credit hoặc liên hệ Admin.',
            'account',
            jsonb_build_object('is_locked', true)
        );
    elsif old.is_locked = true and new.is_locked = false then
        insert into public.notifications (user_id, title, message, type, data)
        values (
            new.id,
            'Tài khoản đã được mở khóa 🔓',
            'Chúc mừng! Tài khoản của bạn đã được mở khóa thành công. Bạn có thể tiếp tục tham gia kiểm thử và đăng chiến dịch.',
            'account',
            jsonb_build_object('is_locked', false)
        );
    end if;
    return new;
exception when others then
    return new;
end;
$$;

drop trigger if exists trg_on_profile_lock_changed on public.profiles;
create trigger trg_on_profile_lock_changed
    after update on public.profiles
    for each row
    when (old.is_locked is distinct from new.is_locked)
    execute function public.trg_notify_profile_lock_status();

-- 8. Database Trigger: Thông báo cho Chủ ứng dụng khi có Tester mới tham gia (campaign_members)
create or replace function public.trg_notify_campaign_member_joined()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
begin
    select owner_id, app_name into v_owner_id, v_camp_name
    from public.campaigns
    where id = new.campaign_id;

    if v_owner_id is not null and v_owner_id <> new.user_id then
        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_owner_id,
            'Tester mới tham gia 🚀',
            format('Một người dùng vừa tham gia thử nghiệm ứng dụng "%s" của bạn.', coalesce(v_camp_name, '')),
            'campaign',
            jsonb_build_object('campaign_id', new.campaign_id, 'tester_id', new.user_id)
        );
    end if;
    return new;
exception when others then
    return new;
end;
$$;

drop trigger if exists trg_on_campaign_member_joined on public.campaign_members;
create trigger trg_on_campaign_member_joined
    after insert on public.campaign_members
    for each row
    execute function public.trg_notify_campaign_member_joined();

notify pgrst, 'reload schema';


-- ============================================================================
-- 12 Tester — Supabase migration 039: Nhiệm vụ do người dùng tạo (Missions)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Quy tắc:
--   • Chủ app mở nhiệm vụ cho 1 app đang đăng của mình (1 nhiệm vụ đang mở / app).
--   • Thiết lập: tên, mô tả, số người tham gia (1–50), số ngày nhận nhiệm vụ
--     (1–7), Credit trả người tham gia mỗi lượt (tối thiểu 10).
--   • Yêu cầu số dư tối thiểu 300 Credit; trừ thẳng 50 Credit × số ngày
--     ngay khi tạo nhiệm vụ (phí nền tảng, không hoàn lại).
--   • Người tham gia bấm "Nhận nhiệm vụ" (mỗi người 1 lần / nhiệm vụ) rồi nộp
--     báo cáo test cho app liên kết. Khi báo cáo ĐƯỢC DUYỆT (chủ app duyệt tay
--     hoặc hệ thống tự duyệt sau 8h), hệ thống trừ Credit chủ nhiệm vụ và
--     chuyển cho người tham gia (ngoài thưởng test thông thường).
--   • Nhiệm vụ dừng khi: đủ số người (full) / hết số ngày (expired) / chủ
--     nhiệm vụ hết Credit trả thưởng (stopped_no_credits — không trả một phần).
-- ============================================================================

-- ============================================================================
-- 1. Bảng missions & mission_participants
-- ============================================================================
create table if not exists public.missions (
    id uuid primary key default gen_random_uuid(),
    creator_id uuid not null references public.profiles(id) on delete cascade,
    campaign_id uuid not null references public.campaigns(id) on delete cascade,
    title text not null,
    description text not null default '',
    max_participants int not null check (max_participants between 1 and 50),
    duration_days int not null check (duration_days between 1 and 7),
    reward_credits int not null check (reward_credits >= 10),
    fee_credits int not null default 0,
    status text not null default 'open'
        check (status in ('open', 'full', 'stopped_no_credits', 'expired')),
    participant_count int not null default 0,
    created_at timestamptz not null default now(),
    ends_at timestamptz not null
);

create index if not exists idx_missions_status_created on public.missions (status, created_at desc);
create index if not exists idx_missions_campaign on public.missions (campaign_id);
create index if not exists idx_missions_creator on public.missions (creator_id);

create table if not exists public.mission_participants (
    id uuid primary key default gen_random_uuid(),
    mission_id uuid not null references public.missions(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    status text not null default 'joined' check (status in ('joined', 'completed')),
    joined_at timestamptz not null default now(),
    completed_at timestamptz,
    unique (mission_id, user_id)
);

create index if not exists idx_mission_participants_user on public.mission_participants (user_id);

-- RLS: chỉ đọc; mọi ghi đi qua RPC security definer bên dưới.
alter table public.missions enable row level security;
alter table public.mission_participants enable row level security;

drop policy if exists "missions_read_authenticated" on public.missions;
create policy "missions_read_authenticated" on public.missions
    for select to authenticated using (true);

drop policy if exists "mission_participants_read_authenticated" on public.mission_participants;
create policy "mission_participants_read_authenticated" on public.mission_participants
    for select to authenticated using (true);

-- ============================================================================
-- 2. RPC create_mission — mở nhiệm vụ, trừ phí 50 Credit × số ngày upfront
-- ============================================================================
create or replace function public.create_mission(
    p_campaign_id uuid,
    p_title text,
    p_description text,
    p_max_participants int,
    p_duration_days int,
    p_reward_credits int
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_camp record;
    v_balance int;
    v_fee int;
    v_mission_id uuid;
    v_ends_at timestamptz;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if nullif(trim(coalesce(p_title, '')), '') is null then
        raise exception 'MISSION_TITLE_REQUIRED';
    end if;

    if p_max_participants is null or p_max_participants < 1 or p_max_participants > 50 then
        raise exception 'INVALID_PARTICIPANTS';
    end if;

    if p_duration_days is null or p_duration_days < 1 or p_duration_days > 7 then
        raise exception 'INVALID_DURATION';
    end if;

    if p_reward_credits is null or p_reward_credits < 10 then
        raise exception 'INVALID_REWARD';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    if exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and status in ('open', 'full')
    ) then
        raise exception 'MISSION_ALREADY_EXISTS';
    end if;

    v_fee := 50 * p_duration_days;

    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    if v_balance is null or v_balance < 300 then
        raise exception 'INSUFFICIENT_MIN_CREDITS';
    end if;

    if v_balance < v_fee then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    v_ends_at := now() + make_interval(days => p_duration_days);

    insert into public.missions (
        creator_id, campaign_id, title, description,
        max_participants, duration_days, reward_credits, fee_credits,
        status, ends_at
    ) values (
        v_user_id, p_campaign_id, trim(p_title), coalesce(p_description, ''),
        p_max_participants, p_duration_days, p_reward_credits, v_fee,
        'open', v_ends_at
    )
    returning id into v_mission_id;

    update public.profiles
    set credits = credits - v_fee
    where id = v_user_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_user_id,
        -v_fee,
        'mission_fee',
        format('Mở nhiệm vụ "%s" (%s ngày × 50 Credit phí nền tảng)', trim(p_title), p_duration_days),
        v_balance
    );

    return jsonb_build_object(
        'success', true,
        'mission_id', v_mission_id,
        'fee', v_fee,
        'balance', v_balance,
        'ends_at', to_char(v_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$$;

revoke execute on function public.create_mission(uuid, text, text, int, int, int) from public, anon;
grant execute on function public.create_mission(uuid, text, text, int, int, int) to authenticated, service_role;

-- ============================================================================
-- 3. RPC join_mission — người tham gia nhận nhiệm vụ (mỗi người 1 lần)
-- ============================================================================
create or replace function public.join_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
    v_camp_status text;
    v_new_status text;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id = v_user_id then
        raise exception 'CANNOT_JOIN_OWN_MISSION';
    end if;

    if v_mission.status not in ('open', 'full') then
        raise exception 'MISSION_NOT_OPEN';
    end if;

    if v_mission.ends_at <= now() then
        raise exception 'MISSION_EXPIRED';
    end if;

    if v_mission.participant_count >= v_mission.max_participants then
        raise exception 'MISSION_FULL';
    end if;

    if exists (
        select 1 from public.mission_participants
        where mission_id = p_mission_id and user_id = v_user_id
    ) then
        raise exception 'ALREADY_JOINED';
    end if;

    select status into v_camp_status from public.campaigns where id = v_mission.campaign_id;
    if v_camp_status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp_status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    insert into public.mission_participants (mission_id, user_id)
    values (p_mission_id, v_user_id);

    v_new_status := case when v_mission.participant_count + 1 >= v_mission.max_participants
                         then 'full' else v_mission.status end;

    update public.missions
    set participant_count = participant_count + 1,
        status = v_new_status
    where id = p_mission_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_mission.creator_id,
        'Người tham gia nhiệm vụ mới 🎯',
        format('"%s" vừa nhận nhiệm vụ "%s". Hãy sẵn sàng duyệt báo cáo test để trả thưởng.',
               coalesce((select display_name from public.profiles where id = v_user_id), 'Một người dùng'),
               v_mission.title),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'campaign_id', v_mission.campaign_id)
    );

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'campaign_id', v_mission.campaign_id,
        'status', v_new_status
    );
end;
$$;

revoke execute on function public.join_mission(uuid) from public, anon;
grant execute on function public.join_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 4. RPC _settle_mission_reward — trả thưởng khi báo cáo test được duyệt
-- Gọi từ approve_tester_report / auto_accept_expired_reports. Idempotent:
-- unique(mission_id, user_id) + chuyển trạng thái 'joined' → 'completed'.
-- ============================================================================
create or replace function public._settle_mission_reward(
    p_campaign_id uuid,
    p_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_mission record;
    v_creator_balance int;
    v_tester_balance int;
    v_paid boolean := false;
begin
    if p_user_id is null then
        return jsonb_build_object('settled', false);
    end if;

    begin
        select m.* into v_mission
        from public.missions m
        join public.mission_participants mp on mp.mission_id = m.id
        where m.campaign_id = p_campaign_id
          and mp.user_id = p_user_id
          and mp.status = 'joined'
          and m.status in ('open', 'full')
          and m.ends_at > now()
        order by m.created_at desc
        limit 1
        for update of m;

        if v_mission.id is null then
            return jsonb_build_object('settled', false);
        end if;

        select credits into v_creator_balance
        from public.profiles
        where id = v_mission.creator_id
        for update;

        if coalesce(v_creator_balance, 0) >= v_mission.reward_credits then
            update public.mission_participants
            set status = 'completed',
                completed_at = now()
            where mission_id = v_mission.id
              and user_id = p_user_id
              and status = 'joined';

            if not found then
                -- Đã được trả thưởng trước đó (đua duyệt tay/auto): không trừ/chuyển lần 2.
                return jsonb_build_object('settled', false);
            end if;

            update public.profiles
            set credits = credits - v_mission.reward_credits
            where id = v_mission.creator_id
            returning credits into v_creator_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_mission.creator_id,
                -v_mission.reward_credits,
                'mission_reward',
                format('Trả thưởng nhiệm vụ "%s" cho người tham gia', v_mission.title),
                v_creator_balance
            );

            update public.profiles
            set credits = credits + v_mission.reward_credits
            where id = p_user_id
            returning credits into v_tester_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                p_user_id,
                v_mission.reward_credits,
                'mission_reward',
                format('Hoàn thành nhiệm vụ "%s" (+%s Credit nhiệm vụ)', v_mission.title, v_mission.reward_credits),
                v_tester_balance
            );

            insert into public.notifications (user_id, title, message, type, data)
            values (
                p_user_id,
                'Hoàn thành nhiệm vụ! 🎉',
                format('Bạn nhận được %s Credit từ nhiệm vụ "%s". Cảm ơn bạn đã kiểm thử!',
                       v_mission.reward_credits, v_mission.title),
                'mission',
                jsonb_build_object('mission_id', v_mission.id, 'campaign_id', v_mission.campaign_id, 'reward', v_mission.reward_credits)
            );

            v_paid := true;
        else
            -- Chủ nhiệm vụ hết Credit trả thưởng: dừng nhiệm vụ, không trả một phần.
            update public.missions
            set status = 'stopped_no_credits'
            where id = v_mission.id
              and status in ('open', 'full');

            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_mission.creator_id,
                'Nhiệm vụ đã dừng ⛔',
                format('Nhiệm vụ "%s" đã dừng vì số Credit của bạn không đủ để trả thưởng người tham gia.',
                       v_mission.title),
                'mission',
                jsonb_build_object('mission_id', v_mission.id, 'campaign_id', v_mission.campaign_id, 'stopped', true)
            );
        end if;

        return jsonb_build_object('settled', true, 'paid', v_paid, 'mission_id', v_mission.id);
    exception when others then
        -- Lỗi结算 không được làm hỏng giao dịch duyệt báo cáo; rollback toàn bộ
        -- phần thưởng của block này (savepoint) và ghi notice để kiểm tra sau.
        raise notice 'settle_mission_reward failed: %', sqlerrm;
        return jsonb_build_object('settled', false, 'error', sqlerrm);
    end;
end;
$$;

revoke execute on function public._settle_mission_reward(uuid, uuid) from public, anon, authenticated;
grant execute on function public._settle_mission_reward(uuid, uuid) to service_role;

-- ============================================================================
-- 5. RPC get_open_missions — danh sách nhiệm vụ cho tab "Nhiệm vụ App"
-- Tự động đánh dấu expired (lazy sync) rồi trả danh sách gần đây.
-- ============================================================================
create or replace function public.get_open_missions()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    update public.missions
    set status = 'expired'
    where status in ('open', 'full')
      and ends_at <= now();

    return jsonb_build_object(
        'missions', coalesce((
            select jsonb_agg(
                jsonb_build_object(
                    'id', t.id,
                    'title', t.title,
                    'description', t.description,
                    'reward_credits', t.reward_credits,
                    'max_participants', t.max_participants,
                    'participant_count', t.participant_count,
                    'status', t.status,
                    'ends_at', to_char(t.ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'created_at', to_char(t.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'campaign_id', t.campaign_id,
                    'app_name', t.app_name,
                    'icon_url', t.icon_url,
                    'package_name', t.package_name,
                    'creator_id', t.creator_id,
                    'creator_name', t.creator_name,
                    'is_mine', t.creator_id = v_user_id,
                    'has_joined', t.has_joined,
                    'has_completed', t.has_completed
                )
                order by t.is_active desc, t.created_at desc
            )
            from (
                select
                    m.id, m.title, m.description, m.reward_credits,
                    m.max_participants, m.participant_count, m.status,
                    m.ends_at, m.created_at, m.campaign_id, m.creator_id,
                    c.app_name, c.icon_url, c.package_name,
                    coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name,
                    (m.status in ('open', 'full')) as is_active,
                    exists (
                        select 1 from public.mission_participants mp2
                        where mp2.mission_id = m.id and mp2.user_id = v_user_id
                    ) as has_joined,
                    exists (
                        select 1 from public.mission_participants mp3
                        where mp3.mission_id = m.id and mp3.user_id = v_user_id
                          and mp3.status = 'completed'
                    ) as has_completed
                from public.missions m
                join public.campaigns c on c.id = m.campaign_id
                left join public.profiles p on p.id = m.creator_id
                where m.created_at > now() - interval '14 days'
                limit 100
            ) t
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function public.get_open_missions() from public, anon;
grant execute on function public.get_open_missions() to authenticated, service_role;

-- ============================================================================
-- 6. Hook vào luồng duyệt báo cáo — thêm lệnh trả thưởng nhiệm vụ
-- approve_tester_report / auto_accept_expired_reports được tạo lại nguyên văn
-- theo migration 037, bổ sung 1 dòng perform _settle_mission_reward.
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
    -- p_bonus giữ lại trong chữ ký để tương thích client cũ nhưng KHÔNG còn
    -- hiệu lực: trước đây p_bonus không giới hạn và không trừ Credit ai cả.
    v_extra_reward int := 2;
    v_tip int := 0;
    v_total_bonus int;
    v_new_balance int;
    v_owner_bonus int := 0;
    v_owner_balance int;
    v_is_owner boolean := false;
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

    -- Quá 8h: không cho duyệt tay, để auto_accept xử lý (không thưởng +2).
    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    v_is_owner := (v_camp.owner_id = v_caller_id);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    -- Tip thật (nếu chủ app có đặt): trừ Credit chủ app, cộng cho tester.
    -- Giữ nguyên hành vi cũ: tip được trả trước, sau đó mới cộng thưởng duyệt.
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);

    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

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
        format(
            '%s duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)',
            case when v_is_owner then 'Chủ app' else 'Admin/Moderator' end,
            v_extra_reward
        ),
        v_new_balance
    );

    -- Chỉ chủ app thật mới nhận +2 khi bấm duyệt.
    if v_is_owner then
        v_owner_bonus := 2;
        update public.profiles
        set credits = credits + v_owner_bonus
        where id = v_camp.owner_id
        returning credits into v_owner_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_camp.owner_id,
            v_owner_bonus,
            'owner_approval_bonus',
            format('Duyệt báo cáo kiểm thử (+%s Credit)', v_owner_bonus),
            v_owner_balance
        );
    end if;

    -- Nhiệm vụ: nếu tester đã nhận nhiệm vụ của app này, trả thưởng nhiệm vụ.
    perform public._settle_mission_reward(v_report.campaign_id, v_report.user_id);

    update public.checkins
    set note = 'Đã kiểm thử và được duyệt'
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    if not found then
        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được duyệt')
        on conflict (campaign_id, user_id, checkin_date)
        do update set note = 'Đã kiểm thử và được duyệt';
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'owner_credits_awarded', v_owner_bonus,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'owner_balance', v_owner_balance,
        'message', format(
            'Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester%s)',
            v_extra_reward,
            case when v_owner_bonus > 0 then format(' và +%s Credit cho bạn', v_owner_bonus) else '' end
        )
    );
end;
$$;

revoke execute on function public.approve_tester_report(uuid, int) from public, anon;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;

create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_count int := 0;
    v_owner_id uuid;
    v_camp_name text;
    v_tip int;
    v_base int;
    v_total_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded, provisional_granted_credits
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        v_base := coalesce(
            nullif(v_report.provisional_granted_credits, 0),
            nullif(v_report.bonus_awarded, 0),
            0
        );
        v_total_bonus := coalesce(nullif(v_report.bonus_awarded, 0), v_base) + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        insert into public.report_review_events (
            report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
        ) values (
            v_report.id, v_report.campaign_id, v_report.user_id,
            v_owner_id, null, 'auto_accept'
        );

        update public.checkins
        set note = 'Tự động duyệt sau 8h'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (
                v_report.campaign_id, v_report.user_id,
                (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                'Tự động duyệt sau 8h'
            )
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Tự động duyệt sau 8h';
        end if;

        -- Nhiệm vụ: nếu tester đã nhận nhiệm vụ của app này, trả thưởng nhiệm vụ.
        perform public._settle_mission_reward(v_report.campaign_id, v_report.user_id);

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này + %s Credit boa từ chủ app). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base)
            end,
            'report',
            jsonb_build_object('campaign_id', v_report.campaign_id, 'report_id', v_report.id, 'status', 'approved', 'auto_accepted', true, 'tip_awarded', v_tip)
        );

        v_count := v_count + 1;
    end loop;

    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;

notify pgrst, 'reload schema';


-- ============================================================================
-- 12 Tester — Supabase migration 040: Quản lý nhiệm vụ (dừng / tiếp tục / xóa)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- YÊU CẦU: chạy migration 039_missions.sql TRƯỚC.
--
-- Quy tắc (đã chốt với product):
--   • Dừng nhiệm vụ (paused_by_creator) = dừng hẳn: không nhận người mới VÀ
--     không trả thưởng nhiệm vụ khi duyệt báo cáo trong lúc dừng (thưởng test
--     thông thường không đổi). _settle_mission_reward / join_mission chỉ xử lý
--     status open/full nên tự động đúng.
--   • Tiếp tục: giữ nguyên thời hạn gốc ends_at (ngày vẫn trôi khi đang dừng).
--   • Xóa nhiệm vụ (deleted, xóa mềm): ẩn khỏi mọi danh sách, KHÔNG hoàn phí
--     nền tảng đã trừ. Người đã hoàn thành giữ Credit; người đã nhận nhưng
--     chưa hoàn thành nhận notification báo nhiệm vụ bị xóa.
-- ============================================================================

-- ============================================================================
-- 1. Mở rộng check constraint trạng thái mission
-- ============================================================================
alter table public.missions drop constraint if exists missions_status_check;
alter table public.missions add constraint missions_status_check
    check (status in ('open', 'full', 'stopped_no_credits', 'expired', 'paused_by_creator', 'deleted'));

-- ============================================================================
-- 2. RPC pause_mission — chủ nhiệm vụ dừng hẳn nhiệm vụ
-- ============================================================================
create or replace function public.pause_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null or v_mission.status = 'deleted' then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_mission.ends_at <= now() then
        update public.missions
        set status = 'expired'
        where id = p_mission_id
          and status in ('open', 'full', 'paused_by_creator');
        raise exception 'MISSION_EXPIRED';
    end if;

    if v_mission.status not in ('open', 'full') then
        raise exception 'MISSION_NOT_OPEN';
    end if;

    update public.missions
    set status = 'paused_by_creator'
    where id = p_mission_id;

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', 'paused_by_creator'
    );
end;
$$;

revoke execute on function public.pause_mission(uuid) from public, anon;
grant execute on function public.pause_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 3. RPC resume_mission — mở lại nhiệm vụ đã dừng (giữ nguyên ends_at)
-- ============================================================================
create or replace function public.resume_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
    v_camp_status text;
    v_new_status text;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null or v_mission.status = 'deleted' then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_mission.status <> 'paused_by_creator' then
        raise exception 'MISSION_NOT_PAUSED';
    end if;

    if v_mission.ends_at <= now() then
        update public.missions
        set status = 'expired'
        where id = p_mission_id;
        raise exception 'MISSION_EXPIRED';
    end if;

    select status into v_camp_status from public.campaigns where id = v_mission.campaign_id;
    if v_camp_status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp_status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    v_new_status := case when v_mission.participant_count >= v_mission.max_participants
                         then 'full' else 'open' end;

    update public.missions
    set status = v_new_status
    where id = p_mission_id;

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', v_new_status
    );
end;
$$;

revoke execute on function public.resume_mission(uuid) from public, anon;
grant execute on function public.resume_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 4. RPC delete_mission — xóa mềm, không hoàn phí, báo người đã nhận
-- ============================================================================
create or replace function public.delete_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null or v_mission.status = 'deleted' then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    update public.missions
    set status = 'deleted'
    where id = p_mission_id;

    -- Người đã nhận nhưng chưa hoàn thành: báo nhiệm vụ bị xóa, mất cơ hội nhận thưởng.
    insert into public.notifications (user_id, title, message, type, data)
    select
        mp.user_id,
        'Nhiệm vụ đã bị xóa 🗑️',
        format('Nhiệm vụ "%s" đã được chủ nhiệm vụ xóa. Bạn không thể tiếp tục nhận thưởng từ nhiệm vụ này.',
               v_mission.title),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'campaign_id', v_mission.campaign_id, 'deleted', true)
    from public.mission_participants mp
    where mp.mission_id = p_mission_id
      and mp.status = 'joined';

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', 'deleted'
    );
end;
$$;

revoke execute on function public.delete_mission(uuid) from public, anon;
grant execute on function public.delete_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 5. RPC get_campaign_mission — nhiệm vụ hiện tại của 1 app (màn quản lý)
-- Trả về mission mới nhất chưa xóa, cùng shape với get_open_missions.
-- ============================================================================
create or replace function public.get_campaign_mission(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_row record;
begin
    select
        m.id, m.title, m.description, m.reward_credits,
        m.max_participants, m.participant_count, m.status,
        m.ends_at, m.created_at, m.campaign_id, m.creator_id,
        c.app_name, c.icon_url, c.package_name,
        coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name
    into v_row
    from public.missions m
    join public.campaigns c on c.id = m.campaign_id
    left join public.profiles p on p.id = m.creator_id
    where m.campaign_id = p_campaign_id
      and m.status <> 'deleted'
    order by m.created_at desc
    limit 1;

    if v_row.id is null then
        return jsonb_build_object('mission', null);
    end if;

    return jsonb_build_object(
        'mission', jsonb_build_object(
            'id', v_row.id,
            'title', v_row.title,
            'description', v_row.description,
            'reward_credits', v_row.reward_credits,
            'max_participants', v_row.max_participants,
            'participant_count', v_row.participant_count,
            'status', v_row.status,
            'ends_at', to_char(v_row.ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'created_at', to_char(v_row.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'campaign_id', v_row.campaign_id,
            'app_name', v_row.app_name,
            'icon_url', v_row.icon_url,
            'package_name', v_row.package_name,
            'creator_id', v_row.creator_id,
            'creator_name', v_row.creator_name,
            'is_mine', v_row.creator_id = v_user_id,
            'has_joined', exists (
                select 1 from public.mission_participants mp2
                where mp2.mission_id = v_row.id and mp2.user_id = v_user_id
            ),
            'has_completed', exists (
                select 1 from public.mission_participants mp3
                where mp3.mission_id = v_row.id and mp3.user_id = v_user_id
                  and mp3.status = 'completed'
            )
        )
    );
end;
$$;

revoke execute on function public.get_campaign_mission(uuid) from public, anon;
grant execute on function public.get_campaign_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 6. get_open_missions — loại nhiệm vụ đã xóa; nhiệm vụ đang dừng vẫn hiện
--    (người đã nhận cần thấy trạng thái), đứng sau các nhiệm vụ đang mở.
-- ============================================================================
create or replace function public.get_open_missions()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    update public.missions
    set status = 'expired'
    where status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    return jsonb_build_object(
        'missions', coalesce((
            select jsonb_agg(
                jsonb_build_object(
                    'id', t.id,
                    'title', t.title,
                    'description', t.description,
                    'reward_credits', t.reward_credits,
                    'max_participants', t.max_participants,
                    'participant_count', t.participant_count,
                    'status', t.status,
                    'ends_at', to_char(t.ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'created_at', to_char(t.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'campaign_id', t.campaign_id,
                    'app_name', t.app_name,
                    'icon_url', t.icon_url,
                    'package_name', t.package_name,
                    'creator_id', t.creator_id,
                    'creator_name', t.creator_name,
                    'is_mine', t.creator_id = v_user_id,
                    'has_joined', t.has_joined,
                    'has_completed', t.has_completed
                )
                order by t.sort_active desc, t.created_at desc
            )
            from (
                select
                    m.id, m.title, m.description, m.reward_credits,
                    m.max_participants, m.participant_count, m.status,
                    m.ends_at, m.created_at, m.campaign_id, m.creator_id,
                    c.app_name, c.icon_url, c.package_name,
                    coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name,
                    case m.status
                        when 'open' then 2
                        when 'full' then 2
                        when 'paused_by_creator' then 1
                        else 0
                    end as sort_active,
                    exists (
                        select 1 from public.mission_participants mp2
                        where mp2.mission_id = m.id and mp2.user_id = v_user_id
                    ) as has_joined,
                    exists (
                        select 1 from public.mission_participants mp3
                        where mp3.mission_id = m.id and mp3.user_id = v_user_id
                          and mp3.status = 'completed'
                    ) as has_completed
                from public.missions m
                join public.campaigns c on c.id = m.campaign_id
                left join public.profiles p on p.id = m.creator_id
                where m.status <> 'deleted'
                  and m.created_at > now() - interval '14 days'
                limit 100
            ) t
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function public.get_open_missions() from public, anon;
grant execute on function public.get_open_missions() to authenticated, service_role;

notify pgrst, 'reload schema';
