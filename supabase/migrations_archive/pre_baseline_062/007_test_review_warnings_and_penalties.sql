-- ============================================================================
-- 12 Tester — Supabase migration 007: Test Review, Warnings, Auto-Accept & Penalties
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Create user_warnings table
create table if not exists public.user_warnings (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references public.profiles(id) on delete cascade,
    warning_type text not null check (warning_type in ('tester_rejection_streak', 'owner_excessive_rejections', 'admin_manual')),
    message      text not null,
    warning_date date not null default current_date,
    created_at   timestamptz not null default now(),
    acknowledged boolean not null default false
);

create index if not exists idx_user_warnings_user_date on public.user_warnings(user_id, warning_date);
create index if not exists idx_user_warnings_user_created on public.user_warnings(user_id, created_at desc);

-- Row Level Security for user_warnings
alter table public.user_warnings enable row level security;

drop policy if exists "warnings_select_own" on public.user_warnings;
create policy "warnings_select_own"
    on public.user_warnings for select to authenticated
    using (user_id = auth.uid());

drop policy if exists "warnings_update_own" on public.user_warnings;
create policy "warnings_update_own"
    on public.user_warnings for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

-- 2. Stored Procedure: approve_tester_report
-- Approves report, awards +2 credits (+ optional bonus) to tester, records check-in.
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
    v_reward int := 2 + greatest(0, coalesce(p_bonus, 0));
    v_new_balance int;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Lock and select the report
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

    -- Check caller authority (must be campaign owner or admin)
    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id and coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Update report status
    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_reward,
        reviewed_at = now()
    where id = p_report_id;

    -- Credit tester
    update public.profiles
    set credits = credits + v_reward,
        last_active_date = current_date,
        missed_streak = 0
    where id = v_report.user_id
    returning credits into v_new_balance;

    -- Log credit transaction
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_reward,
        'test_approved',
        format('Chủ app duyệt kết quả kiểm thử (+%s Credit)', v_reward),
        v_new_balance
    );

    -- Ensure check-in record exists for tester today
    insert into public.checkins (campaign_id, user_id, checkin_date, note)
    values (v_report.campaign_id, v_report.user_id, current_date, 'Đã kiểm thử và được chủ app duyệt')
    on conflict (campaign_id, user_id, checkin_date) do nothing;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_reward,
        'tester_balance', v_new_balance
    );
end;
$$;

-- 3. Stored Procedure: reject_tester_report
-- Rejects report (no credits awarded). Evaluates warning rules:
-- - Tester rejected >= 3 times in a day -> warning. (3 warnings in 1 week -> locked)
-- - Owner rejects >= 5 times in a day -> warning. (3 warnings in 1 week -> locked)
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

    -- Lock and select the report
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

    -- Check caller authority
    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id and coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Update report status to rejected (NO credits awarded, NO checkin)
    update public.tester_reports
    set status = 'rejected',
        reviewed_at = now()
    where id = p_report_id;

    -- -----------------------------------------------------------------
    -- RULE 1: TESTER REJECTION CHECK (3 rejections / day -> Warning)
    -- -----------------------------------------------------------------
    select count(*) into v_tester_today_rejections
    from public.tester_reports
    where user_id = v_report.user_id
      and status = 'rejected'
      and date(coalesce(reviewed_at, now()) at time zone 'Asia/Ho_Chi_Minh') = date(now() at time zone 'Asia/Ho_Chi_Minh');

    if v_tester_today_rejections >= 3 then
        -- Check if tester already received this warning today
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

            -- Check if tester has accumulated >= 3 warnings in the last 7 days
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

    -- -----------------------------------------------------------------
    -- RULE 2: APP OWNER EXCESSIVE REJECTIONS (5 rejections / day -> Warning)
    -- -----------------------------------------------------------------
    select count(*) into v_owner_today_rejections
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where c.owner_id = v_caller_id
      and tr.status = 'rejected'
      and date(coalesce(tr.reviewed_at, now()) at time zone 'Asia/Ho_Chi_Minh') = date(now() at time zone 'Asia/Ho_Chi_Minh');

    if v_owner_today_rejections >= 5 then
        -- Check if owner already received this warning today
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

            -- Check if owner has accumulated >= 3 warnings in the last 7 days
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

-- 4. Stored Procedure: auto_accept_expired_reports
-- Any pending report older than 24 hours is automatically accepted (+2 credits to tester & check-in).
create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_count int := 0;
    v_new_balance int;
begin
    for v_report in
        select id, campaign_id, user_id
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '24 hours')
        for update skip locked
    loop
        -- Update report
        update public.tester_reports
        set status = 'approved',
            bonus_awarded = 2,
            reviewed_at = now()
        where id = v_report.id;

        -- Award 2 credits to tester
        update public.profiles
        set credits = credits + 2,
            last_active_date = current_date,
            missed_streak = 0
        where id = v_report.user_id
        returning credits into v_new_balance;

        -- Log transaction
        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_report.user_id,
            2,
            'auto_accepted',
            'Hệ thống tự động duyệt kết quả kiểm thử sau 24h (+2 Credit)',
            v_new_balance
        );

        -- Record checkin
        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (v_report.campaign_id, v_report.user_id, current_date, 'Tự động duyệt sau 24h')
        on conflict (campaign_id, user_id, checkin_date) do nothing;

        v_count := v_count + 1;
    end loop;

    return v_count;
end;
$$;

-- 5. Stored Procedure: self_unlock_account
-- Allows a locked user to deduct 100 Credit to immediately unlock their account.
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

    -- Unlock account and deduct credits
    update public.profiles
    set credits = v_new_balance,
        is_locked = false
    where id = v_caller_id;

    -- Record credit log
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_caller_id,
        -100,
        'self_unlock_penalty',
        'Trừ 100 Credit để mở khóa tài khoản',
        v_new_balance
    );

    -- Reset weekly warnings so user is not immediately re-locked
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
