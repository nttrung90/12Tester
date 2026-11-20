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
