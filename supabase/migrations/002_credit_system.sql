-- ============================================================================
-- TestChéo — Supabase migration 002: Credit System & Daily Quests
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Bổ sung các cột quản lý Credit vào bảng profiles
alter table public.profiles
    add column if not exists credits int not null default 100,
    add column if not exists last_active_date date not null default current_date,
    add column if not exists missed_streak int not null default 0;

-- Đảm bảo credits không bị âm
alter table public.profiles
    drop constraint if exists check_credits_non_negative;
alter table public.profiles
    add constraint check_credits_non_negative check (credits >= 0);

-- 2. Bảng lưu lịch sử biến động Credit (credit_logs)
create table if not exists public.credit_logs (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references public.profiles(id) on delete cascade,
    amount      int not null,
    action      text not null,
    note        text,
    balance     int not null,
    created_at  timestamptz not null default now()
);

create index if not exists credit_logs_user_idx on public.credit_logs (user_id);
create index if not exists credit_logs_created_idx on public.credit_logs (created_at desc);

alter table public.credit_logs enable row level security;

drop policy if exists "credit_logs_select_own" on public.credit_logs;
create policy "credit_logs_select_own"
    on public.credit_logs for select to authenticated
    using (user_id = auth.uid());

-- 3. Cập nhật hàm trigger khi tạo tài khoản mới: Thưởng mặc định 100 Credit + Ghi log
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, email, display_name, avatar_url, credits, last_active_date, missed_streak)
    values (
        new.id,
        new.email,
        coalesce(
            new.raw_user_meta_data ->> 'full_name',
            new.raw_user_meta_data ->> 'name',
            split_part(coalesce(new.email, ''), '@', 1)
        ),
        coalesce(
            new.raw_user_meta_data ->> 'avatar_url',
            new.raw_user_meta_data ->> 'picture'
        ),
        100,
        current_date,
        0
    )
    on conflict (id) do nothing;

    -- Ghi log khởi tạo 100 credit
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

-- 4. Trigger thưởng điểm khi check-in (2 Credit / app, tối đa 30 app/ngày = max 60 Credit/ngày)
create or replace function public.handle_checkin_reward()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    today_reward_count int;
    new_balance int;
begin
    -- Đếm số lượt check-in trong ngày hôm nay của user (bao gồm lượt mới)
    select count(*) into today_reward_count
    from public.checkins
    where user_id = new.user_id and checkin_date = new.checkin_date;

    -- Luôn cập nhật ngày hoạt động gần nhất và reset chuỗi vắng mặt
    update public.profiles
    set last_active_date = new.checkin_date,
        missed_streak = 0
    where id = new.user_id;

    -- Thưởng điểm nếu trong hạn mức 30 app/ngày
    if today_reward_count <= 30 then
        update public.profiles
        set credits = credits + 2
        where id = new.user_id
        returning credits into new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            new.user_id,
            2,
            'checkin_reward',
            format('Thưởng test app (lượt %s/30 trong ngày)', today_reward_count),
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

-- 5. Cập nhật RLS Policy trên bảng campaigns: Phải có > 10 Credit mới được đăng app
drop policy if exists "campaigns_insert_own" on public.campaigns;
create policy "campaigns_insert_own"
    on public.campaigns for insert to authenticated
    with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.profiles
            where id = auth.uid() and credits > 10
        )
    );

-- Cho phép cả khách (anon) và người dùng đã đăng nhập đọc danh sách profiles và checkins để xem bảng xếp hạng
drop policy if exists "profiles_select_all" on public.profiles;
create policy "profiles_select_all"
    on public.profiles for select to anon, authenticated using (true);

drop policy if exists "checkins_select_all" on public.checkins;
create policy "checkins_select_all"
    on public.checkins for select to anon, authenticated using (true);


-- 6. Hàm tự động trừ phạt Credit khi không thực hiện nhiệm vụ hàng ngày
-- Quy tắc:
--   Ngày 1 bỏ lỡ: -1%
--   Ngày 2 liên tiếp: -2%
--   Ngày 3 liên tiếp: -5%
--   Ngày 4+ liên tiếp: -10% mỗi ngày
--   Làm tròn: trừ ít nhất 1 credit nếu còn điểm
--   Miễn phạt trong 24 giờ đầu sau khi tạo tài khoản
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

-- 7. (Tùy chọn) Lên lịch chạy phạt mỗi ngày lúc 00:05 AM (Giờ Việt Nam UTC+7 = 17:05 UTC)
-- Yêu cầu bật pg_cron extension trong Supabase Database Settings > Extensions.
-- select cron.schedule('daily_inactivity_penalty_job', '5 17 * * *', 'select public.apply_daily_inactivity_penalty();');
