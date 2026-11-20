-- ============================================================================
-- 12 Tester — Supabase migration 025: Contact & Bug Reports (Liên hệ & Báo cáo lỗi)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Mục đích:
--   Cho phép người dùng gửi tin nhắn liên hệ / báo lỗi cho Admin ngay trong app
--   (tab Hồ sơ > Liên hệ & Báo cáo lỗi) và cho Admin xem/trả lời/đánh dấu
--   đã xử lý trong màn hình riêng của Admin Panel.
--
-- Thành phần:
--   1. Bảng public.contact_reports + index + RLS (user chỉ insert/select của mình).
--   2. Column-level grant: chặn client tự set status/admin_reply/replied_at.
--   3. RPC admin_get_contact_reports   — danh sách phản hồi (chỉ admin).
--   4. RPC admin_update_report_status  — đánh dấu đã xử lý / mở lại (chỉ admin).
--   5. RPC admin_reply_contact_report  — trả lời + đẩy notification cho user (chỉ admin).
-- ============================================================================


-- ============================================================================
-- 1. Bảng contact_reports
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

-- ============================================================================
-- 2. RLS + column-level grants (theo pattern migration 023)
-- ============================================================================
alter table public.contact_reports enable row level security;

-- User chỉ thấy phản hồi của chính mình (không dùng cho UI hiện tại, giữ cho tương lai).
drop policy if exists "contact_reports_select_own" on public.contact_reports;
create policy "contact_reports_select_own"
    on public.contact_reports for select to authenticated
    using (user_id = auth.uid());

-- User chỉ được tạo phản hồi cho chính mình, luôn ở trạng thái 'new', chưa có reply.
drop policy if exists "contact_reports_insert_own" on public.contact_reports;
create policy "contact_reports_insert_own"
    on public.contact_reports for insert to authenticated
    with check (
        user_id = auth.uid()
        and status = 'new'
        and admin_reply is null
        and replied_at is null
    );

-- Không có policy update/delete cho client: trạng thái & trả lời chỉ qua RPC definer.

-- Client chỉ được ghi các cột nội dung; status/admin_reply/replied_at do server set.
revoke insert, update, delete on public.contact_reports from public, anon, authenticated;
grant insert (user_id, category, message, app_version, platform, device_model)
    on public.contact_reports to authenticated;
grant select on public.contact_reports to authenticated;

-- ============================================================================
-- 3. RPC: admin_get_contact_reports — danh sách phản hồi (chỉ admin)
-- ============================================================================
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

-- ============================================================================
-- 4. RPC: admin_update_report_status — đánh dấu đã xử lý / mở lại (chỉ admin)
-- ============================================================================
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

-- ============================================================================
-- 5. RPC: admin_reply_contact_report — trả lời + notification cho user (chỉ admin)
-- ============================================================================
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

    -- Đẩy thông báo cho user sở hữu phản hồi (hiện trong Trung tâm thông báo).
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
