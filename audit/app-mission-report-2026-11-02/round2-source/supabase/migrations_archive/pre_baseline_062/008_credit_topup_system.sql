-- ============================================================================
-- 12 Tester — Supabase migration 008: Credit Top-up System (In-App Purchases)
-- Exchange Rate: 1 USD = 20 Credits (0.05$ / Credit)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Create table for top-up packages (Gói nạp Credit)
create table if not exists public.topup_packages (
    id             text primary key,
    title          text not null,
    credits        int not null,
    bonus_credits  int not null default 0,
    price_usd      numeric(10, 2) not null,
    price_vnd      numeric(12, 0) not null default 0,
    badge_text     text,
    is_popular     boolean not null default false,
    is_active      boolean not null default true,
    sort_order     int not null default 0,
    created_at     timestamptz not null default now()
);

-- Seed top-up packages (1$ = 20 Credit standard)
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

-- Enable RLS for topup_packages
alter table public.topup_packages enable row level security;

drop policy if exists "topup_packages_read_all" on public.topup_packages;
create policy "topup_packages_read_all"
    on public.topup_packages for select
    using (is_active = true);

-- 2. Create table for top-up orders (Đơn giao dịch nạp Credit)
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

create index if not exists idx_topup_orders_user_created on public.topup_orders(user_id, created_at desc);
create index if not exists idx_topup_orders_purchase_token on public.topup_orders(purchase_token);
create index if not exists idx_topup_orders_status on public.topup_orders(payment_status);

-- Enable RLS for topup_orders
alter table public.topup_orders enable row level security;

drop policy if exists "topup_orders_select_own" on public.topup_orders;
create policy "topup_orders_select_own"
    on public.topup_orders for select to authenticated
    using (user_id = auth.uid());

-- 3. Stored Procedure: process_topup_order
-- Processes In-App Purchase idempotently, updates user credit balance, and logs credit change.
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
        trim(p_purchase_token),
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
