-- ============================================================================
-- 12 Tester — Supabase migration 019: Admin update app config RPC
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Mục đích:
-- Cho phép Admin chỉnh cấu hình cập nhật ứng dụng ngay trong app (Admin Panel)
-- thay vì phải mở Supabase Dashboard.
--
-- RPC: admin_update_app_config(...)
-- - Chỉ tài khoản có role = 'admin' mới gọi được (kiểm tra auth.uid()).
-- - Validate: min >= 1 và latest >= min.
-- - Upsert bản ghi 'android_client' trong public.app_configs.
-- - Trả về nguyên bản ghi dưới dạng jsonb (khớp model AppConfig ở client).
-- ============================================================================

create or replace function public.admin_update_app_config(
    p_min_version_code int,
    p_latest_version_code int,
    p_latest_version_name text,
    p_force_update boolean,
    p_title text default null,
    p_message text default null,
    p_store_url text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_config public.app_configs;
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

    if p_min_version_code < 1 or p_latest_version_code < p_min_version_code then
        raise exception 'INVALID_VERSION_RANGE';
    end if;

    if p_latest_version_name is null or trim(p_latest_version_name) = '' then
        raise exception 'INVALID_VERSION_NAME';
    end if;

    insert into public.app_configs (
        id,
        min_version_code,
        latest_version_code,
        latest_version_name,
        force_update,
        title,
        message,
        store_url,
        updated_at
    )
    values (
        'android_client',
        p_min_version_code,
        p_latest_version_code,
        trim(p_latest_version_name),
        p_force_update,
        p_title,
        p_message,
        p_store_url,
        now()
    )
    on conflict (id) do update set
        min_version_code = excluded.min_version_code,
        latest_version_code = excluded.latest_version_code,
        latest_version_name = excluded.latest_version_name,
        force_update = excluded.force_update,
        title = coalesce(excluded.title, public.app_configs.title),
        message = coalesce(excluded.message, public.app_configs.message),
        store_url = coalesce(excluded.store_url, public.app_configs.store_url),
        updated_at = now()
    returning * into v_config;

    return to_jsonb(v_config);
end;
$$;
