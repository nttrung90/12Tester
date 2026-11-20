-- ============================================================================
-- 12 Tester — Supabase migration 007: Auto-confirm & Sync Existing Users
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Kích hoạt ngay lập tức tất cả tài khoản đã tạo trước đó (cho phép đăng nhập ngay)
-- Lưu ý: chỉ update email_confirmed_at vì confirmed_at là generated column tự động tính toán
update auth.users
set email_confirmed_at = now()
where email_confirmed_at is null;

-- 2. Tự động xác thực email cho tất cả tài khoản mới tạo từ nay về sau
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

-- 3. Đồng bộ tạo Profile (100 Credit, Member) cho các tài khoản tạo thủ công chưa có Profile
insert into public.profiles (id, email, display_name, credits, role, is_locked)
select 
    u.id,
    u.email,
    coalesce(
        u.raw_user_meta_data->>'full_name',
        u.raw_user_meta_data->>'name',
        split_part(u.email, '@', 1)
    ),
    100,
    'member',
    false
from auth.users u
where not exists (
    select 1 from public.profiles p where p.id = u.id
)
on conflict (id) do nothing;

-- Ghi log thưởng 100 Credit cho các user mới được đồng bộ
insert into public.credit_logs (user_id, amount, action, note, balance)
select 
    u.id,
    100,
    'welcome',
    'Thưởng 100 Credit khi mở tài khoản',
    100
from auth.users u
where not exists (
    select 1 from public.credit_logs l where l.user_id = u.id and l.action = 'welcome'
)
on conflict do nothing;

notify pgrst, 'reload schema';
