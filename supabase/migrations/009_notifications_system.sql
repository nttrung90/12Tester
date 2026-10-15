-- ============================================================================
-- 12 Tester — Supabase migration 009: Notifications System
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Create table for user notifications (Trung tâm thông báo)
create table if not exists public.notifications (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references public.profiles(id) on delete cascade,
    title        text not null,
    message      text not null,
    type         text not null default 'general', -- 'credit', 'report', 'warning', 'account', 'campaign', 'admin', 'system'
    data         jsonb default '{}'::jsonb,
    is_read      boolean not null default false,
    created_at   timestamptz not null default now()
);

-- Indexes for performance
create index if not exists idx_notifications_user_read on public.notifications(user_id, is_read);
create index if not exists idx_notifications_user_created on public.notifications(user_id, created_at desc);

-- 2. Row Level Security for notifications
alter table public.notifications enable row level security;

drop policy if exists "notifications_select_own" on public.notifications;
create policy "notifications_select_own"
    on public.notifications for select to authenticated
    using (user_id = auth.uid());

drop policy if exists "notifications_update_own" on public.notifications;
create policy "notifications_update_own"
    on public.notifications for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "notifications_delete_own" on public.notifications;
create policy "notifications_delete_own"
    on public.notifications for delete to authenticated
    using (user_id = auth.uid());

-- Allow authenticated/service to insert (security definer functions bypass, but allow explicit insert if needed)
drop policy if exists "notifications_insert_all" on public.notifications;
create policy "notifications_insert_all"
    on public.notifications for insert to authenticated
    with check (true);

-- 3. Stored Procedure: admin_send_notification
-- Admin có thể gửi thông báo Broadcast (Toàn hệ thống) hoặc gửi Đích danh cho 1 user
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

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

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
        -- Gửi đích danh cho 1 user
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
        -- Broadcast toàn hệ thống tới tất cả thành viên
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
        'sent_count', v_count,
        'target', case when p_target_user_id is null then 'all' else p_target_user_id::text end
    );
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

-- 5. Database Trigger: Tự động tạo thông báo kiểm thử & báo cáo (tester_reports)
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
    if tg_op = 'INSERT' and new.status = 'pending' then
        -- Có báo cáo mới -> Thông báo cho Chủ ứng dụng
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo kiểm thử mới 📋',
                format('Ứng dụng "%s" vừa nhận được 1 báo cáo kiểm thử mới từ tester. Vui lòng kiểm tra và duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id)
            );
        end if;
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            -- Tester được duyệt báo cáo
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử được phê duyệt! 🎉',
                format('Báo cáo của bạn cho ứng dụng "%s" đã được duyệt thành công (+%s Credit). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), coalesce(new.bonus_awarded, 2)),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'approved')
            );
        elsif new.status = 'rejected' then
            -- Tester bị từ chối báo cáo
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc.', coalesce(v_camp_name, 'ứng dụng')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'rejected')
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
