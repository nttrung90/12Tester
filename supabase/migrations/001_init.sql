-- ============================================================================
-- TestChéo — Supabase migration 001
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------- profiles
-- One row per user. Also auto-created by the handle_new_user() trigger below.
create table public.profiles (
    id          uuid primary key references auth.users (id) on delete cascade,
    email       text,
    display_name text,
    avatar_url  text,
    credits     int not null default 100 check (credits >= 0),
    last_active_date date not null default current_date,
    missed_streak int not null default 0,
    created_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------- campaigns
create table public.campaigns (
    id             uuid primary key default gen_random_uuid(),
    owner_id       uuid not null references public.profiles (id) on delete cascade,
    app_name       text not null,
    package_name   text not null,
    play_url       text not null default '',
    testing_url    text not null default '',
    description    text not null default '',
    target_testers int  not null default 20,
    target_days    int  not null default 14,
    start_date     date not null default current_date,
    status         text not null default 'open' check (status in ('open', 'closed')),
    created_at     timestamptz not null default now()
);
create index campaigns_status_idx on public.campaigns (status);
create index campaigns_owner_idx  on public.campaigns (owner_id);

-- ---------------------------------------------------------------- campaign_members
create table public.campaign_members (
    campaign_id uuid not null references public.campaigns (id) on delete cascade,
    user_id     uuid not null references public.profiles (id) on delete cascade,
    joined_at   timestamptz not null default now(),
    primary key (campaign_id, user_id)
);
create index campaign_members_user_idx on public.campaign_members (user_id);

-- ---------------------------------------------------------------- checkins
-- One row = one tester testing one campaign on one day.
-- The UNIQUE constraint is what physically prevents double check-ins.
create table public.checkins (
    id           uuid primary key default gen_random_uuid(),
    campaign_id  uuid not null references public.campaigns (id) on delete cascade,
    user_id      uuid not null references public.profiles (id) on delete cascade,
    checkin_date date not null default current_date,
    note         text,
    created_at   timestamptz not null default now(),
    unique (campaign_id, user_id, checkin_date)
);
create index checkins_campaign_idx on public.checkins (campaign_id);

-- ---------------------------------------------------------------- feedbacks
-- One rating + comment per user per campaign (upsert = edit my own review).
create table public.feedbacks (
    id          uuid primary key default gen_random_uuid(),
    campaign_id uuid not null references public.campaigns (id) on delete cascade,
    user_id     uuid not null references public.profiles (id) on delete cascade,
    rating      int  not null check (rating between 1 and 5),
    comment     text not null default '',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (campaign_id, user_id)
);
create index feedbacks_campaign_idx on public.feedbacks (campaign_id);

-- ---------------------------------------------------------------- credit_logs
create table public.credit_logs (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references public.profiles (id) on delete cascade,
    amount      int not null,
    action      text not null,
    note        text,
    balance     int not null,
    created_at  timestamptz not null default now()
);
create index credit_logs_user_idx on public.credit_logs (user_id);
create index credit_logs_created_idx on public.credit_logs (created_at desc);

-- ============================================================================
-- Row Level Security
-- ============================================================================

alter table public.profiles          enable row level security;
alter table public.campaigns         enable row level security;
alter table public.campaign_members  enable row level security;
alter table public.checkins          enable row level security;
alter table public.feedbacks         enable row level security;
alter table public.credit_logs       enable row level security;

-- profiles: readable by everyone (including guests viewing the leaderboard); users can insert/update only their own row
create policy "profiles_select_all"
    on public.profiles for select to anon, authenticated using (true);
create policy "profiles_insert_own"
    on public.profiles for insert to authenticated with check (id = auth.uid());
create policy "profiles_update_own"
    on public.profiles for update to authenticated
    using (id = auth.uid()) with check (id = auth.uid());

-- campaigns: open campaigns (or your own) are visible; only users with credits > 10 can create
create policy "campaigns_select_visible"
    on public.campaigns for select to authenticated
    using (status = 'open' or owner_id = auth.uid());
create policy "campaigns_insert_own"
    on public.campaigns for insert to authenticated
    with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.profiles
            where id = auth.uid() and credits > 10
        )
    );
create policy "campaigns_update_owner"
    on public.campaigns for update to authenticated
    using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "campaigns_delete_owner"
    on public.campaigns for delete to authenticated
    using (owner_id = auth.uid());

-- campaign_members: readable by signed-in users; join/leave only your own row
create policy "members_select_all"
    on public.campaign_members for select to authenticated using (true);
create policy "members_insert_own"
    on public.campaign_members for insert to authenticated
    with check (user_id = auth.uid());
create policy "members_delete_own_or_owner"
    on public.campaign_members for delete to authenticated
    using (
        user_id = auth.uid()
        or exists (
            select 1 from public.campaigns c
            where c.id = campaign_members.campaign_id
              and c.owner_id = auth.uid()
        )
    );

-- checkins: readable by everyone (for public stats on leaderboard); each user inserts only their own rows
create policy "checkins_select_all"
    on public.checkins for select to anon, authenticated using (true);
create policy "checkins_insert_own"
    on public.checkins for insert to authenticated
    with check (user_id = auth.uid());

-- feedbacks: readable by signed-in users; each user writes only their own rows
create policy "feedbacks_select_all"
    on public.feedbacks for select to authenticated using (true);
create policy "feedbacks_insert_own"
    on public.feedbacks for insert to authenticated
    with check (user_id = auth.uid());
create policy "feedbacks_update_own"
    on public.feedbacks for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

-- credit_logs: user reads only their own logs
create policy "credit_logs_select_own"
    on public.credit_logs for select to authenticated
    using (user_id = auth.uid());

-- ============================================================================
-- Auto-create a profile row whenever a new user signs up (100 credits default)
-- ============================================================================

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

-- ============================================================================
-- Reward checkins (+2 credit, cap 30 apps/day)
-- ============================================================================

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
    select count(*) into today_reward_count
    from public.checkins
    where user_id = new.user_id and checkin_date = new.checkin_date;

    update public.profiles
    set last_active_date = new.checkin_date,
        missed_streak = 0
    where id = new.user_id;

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

-- ============================================================================
-- Daily inactivity penalty (Day 1: -1%, Day 2: -2%, Day 3: -5%, Day 4+: -10%)
-- ============================================================================

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
