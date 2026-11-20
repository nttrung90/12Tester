-- ============================================================================
-- 12 Tester — Supabase migration 006: User Roles & Admin Management
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Bổ sung các cột phân quyền và trạng thái khóa vào bảng profiles
alter table public.profiles
    add column if not exists role text not null default 'member',
    add column if not exists is_locked boolean not null default false;

alter table public.profiles drop constraint if exists profiles_role_check;
alter table public.profiles
    add constraint profiles_role_check check (lower(role) in ('member', 'moderator', 'admin'));

create index if not exists idx_profiles_role on public.profiles(role);
create index if not exists idx_profiles_is_locked on public.profiles(is_locked);

-- 2. Cập nhật hàm trigger khi tạo tài khoản mới:
-- Mặc định luôn là 'member', không bị khóa (is_locked = false), nhận 100 Credit chào mừng
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

-- 2.1. Tự động xác thực email (Auto-confirm email) cho tất cả tài khoản đăng ký mới
-- Giúp người dùng đăng ký xong có thể đăng nhập và sử dụng ngay lập tức mà không cần xác nhận email
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

-- 3. Stored Procedure: Admin điều chỉnh Credit (Tặng / Trừ Credit)
-- Chỉ tài khoản có role = 'admin' mới được thực hiện
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

    -- Kiểm tra quyền Admin của người gọi
    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền điều chỉnh Credit';
    end if;

    -- Khóa dòng profile của user mục tiêu để đảm bảo tính nguyên tử
    select credits into v_current_credits
    from public.profiles
    where id = p_target_user_id
    for update;

    if v_current_credits is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_new_balance := v_current_credits + p_amount;
    if v_new_balance < 0 then
        v_new_balance := 0; -- Số dư không được âm
    end if;

    update public.profiles
    set credits = v_new_balance
    where id = p_target_user_id;

    -- Ghi lịch sử biến động Credit
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        p_target_user_id,
        p_amount,
        case when p_amount >= 0 then 'admin_grant' else 'admin_deduct' end,
        coalesce(nullif(trim(p_reason), ''), case when p_amount >= 0 then 'Admin tặng Credit' else 'Admin trừ Credit' end),
        v_new_balance
    );

    return jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'previous_credits', v_current_credits,
        'new_credits', v_new_balance,
        'amount', p_amount
    );
end;
$$;

-- 4. Stored Procedure: Admin Khóa / Mở khóa tài khoản
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

    -- Kiểm tra quyền Admin
    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền khóa/mở khóa tài khoản';
    end if;

    -- Không cho phép Admin tự khóa chính mình
    if p_target_user_id = v_caller_id and p_locked = true then
        raise exception 'CANNOT_LOCK_SELF: Không thể tự khóa tài khoản của chính mình';
    end if;

    update public.profiles
    set is_locked = p_locked
    where id = p_target_user_id;

    return jsonb_build_object(
        'success', true,
        'user_id', p_target_user_id,
        'is_locked', p_locked
    );
end;
$$;

-- 5. Helper function: Bổ nhiệm một tài khoản thành Admin qua Email
-- Sử dụng trong Supabase SQL Editor:
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

-- 5.1. Helper function: Bổ nhiệm một tài khoản thành Moderator (lớp trung gian) qua Email
-- Sử dụng trong Supabase SQL Editor:
-- SELECT public.make_user_moderator('email_thanh_vien@gmail.com');
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

-- 6. Cập nhật RLS Policies để ngăn tài khoản bị khóa thực hiện ghi dữ liệu
drop policy if exists "checkins_insert_own" on public.checkins;
create policy "checkins_insert_own"
    on public.checkins for insert to authenticated
    with check (
        user_id = auth.uid()
        and exists (
            select 1 from public.profiles
            where id = auth.uid() and is_locked = false
        )
    );

drop policy if exists "campaigns_insert_own" on public.campaigns;
create policy "campaigns_insert_own"
    on public.campaigns for insert to authenticated
    with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.profiles
            where id = auth.uid() and credits > 10 and is_locked = false
        )
    );

-- 7. Cấp quyền thực thi và tải lại schema cache cho PostgREST
grant execute on function public.admin_adjust_credits(uuid, int, text) to authenticated, service_role;
grant execute on function public.admin_set_user_lock(uuid, boolean, text) to authenticated, service_role;
grant execute on function public.make_user_admin(text) to authenticated, service_role;
grant execute on function public.make_user_moderator(text) to authenticated, service_role;

notify pgrst, 'reload schema';
