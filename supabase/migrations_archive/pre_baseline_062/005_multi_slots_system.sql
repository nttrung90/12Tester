-- ============================================================================
-- 12 Tester — Supabase migration 005: Multi-Slot System (Up to 6 slots, 16 days duration)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Create user_slots table
create table if not exists public.user_slots (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references public.profiles(id) on delete cascade,
    slot_number   int not null check (slot_number between 2 and 6),
    unlocked_at   timestamptz not null default now(),
    expires_at    timestamptz not null,
    created_at    timestamptz not null default now(),
    unique (user_id, slot_number)
);

create index if not exists idx_user_slots_user on public.user_slots(user_id);
create index if not exists idx_user_slots_expires on public.user_slots(expires_at);

-- Row Level Security
alter table public.user_slots enable row level security;

drop policy if exists "user_slots_select_own" on public.user_slots;
create policy "user_slots_select_own"
    on public.user_slots for select to authenticated
    using (user_id = auth.uid());

grant all on table public.user_slots to authenticated, service_role;

-- 2. Add slot_number column to campaigns
alter table public.campaigns
    add column if not exists slot_number int not null default 1 check (slot_number between 1 and 6);

create index if not exists idx_campaigns_owner_slot on public.campaigns(owner_id, slot_number);

-- 3. Stored Procedure: unlock_or_renew_slot
-- Rules:
--   Slot 2: 50 Credit (16 days)
--   Slot 3: 100 Credit (16 days)
--   Slot 4..6: 200 Credit each (16 days)
-- Sequential: Slot N requires Slot N-1 to be currently active.
-- Atomic credit deduction and logging.
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

    -- Validate slot range (Slot 1 is always free and permanent)
    if p_slot_number < 2 or p_slot_number > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    -- Cost based on slot number
    if p_slot_number = 2 then
        v_cost := 50;
    elsif p_slot_number = 3 then
        v_cost := 100;
    else
        v_cost := 200;
    end if;

    -- Check and lock user's balance
    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    if v_balance is null or v_balance < v_cost then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- Sequential requirement: to unlock/renew Slot N (N > 2), Slot N - 1 must be active
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

    -- Calculate new expiration (+16 days)
    select expires_at into v_current_expires
    from public.user_slots
    where user_id = v_user_id and slot_number = p_slot_number;

    if v_current_expires is not null and v_current_expires > now() then
        -- Cumulative extension if active
        v_new_expires := v_current_expires + interval '16 days';
    else
        -- Fresh unlock or renewal after expiry
        v_new_expires := now() + interval '16 days';
    end if;

    -- Upsert user_slots
    insert into public.user_slots (user_id, slot_number, unlocked_at, expires_at)
    values (v_user_id, p_slot_number, now(), v_new_expires)
    on conflict (user_id, slot_number)
    do update set
        unlocked_at = now(),
        expires_at = v_new_expires;

    -- Deduct credits
    update public.profiles
    set credits = credits - v_cost
    where id = v_user_id
    returning credits into v_balance;

    -- Log transaction
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

-- Grant execution permissions
grant execute on function public.unlock_or_renew_slot(int) to authenticated, anon, service_role;

-- 4. Trigger to validate slot validity upon campaign insert or update to 'open'
create or replace function public.check_campaign_slot_validity()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if new.status = 'open' then
        -- Slot 1 is always unlocked
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

        -- Prevent two open campaigns from using the same slot concurrently
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

-- 5. Helper function to auto-pause campaigns whose slots have expired
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

grant execute on function public.pause_expired_slot_campaigns() to authenticated, service_role;

-- 6. Reload schema cache for PostgREST
notify pgrst, 'reload schema';
