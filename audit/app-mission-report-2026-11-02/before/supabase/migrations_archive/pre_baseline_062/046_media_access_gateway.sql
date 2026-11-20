-- ============================================================================
-- 12 Tester — Supabase migration 046: Gateway truy cập Media có kiểm tra quyền (E04)
-- R2-01: Bỏ secret hardcode, fail closed khi thiếu config, bảo vệ quyền thực thi,
--        ràng buộc danh tính consumer (auth.uid), role hiện tại, expiry và unrevoked status.
-- ============================================================================

-- 1. Đảm bảo cột media_expired tồn tại trên bảng tester_reports
alter table public.tester_reports
add column if not exists media_expired boolean not null default false;

-- 2. Secret nội bộ cho chữ ký HMAC của Media Gateway (server-side only)
-- Không lưu secret mặc định trong source code; fail closed nếu chưa cấu hình.
create or replace function public.get_media_gateway_secret()
returns text
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare
    v_secret text;
begin
    v_secret := current_setting('app.settings.media_gateway_secret', true);
    if v_secret is null or length(trim(v_secret)) = 0 then
        raise exception 'MEDIA_GATEWAY_SECRET_NOT_CONFIGURED';
    end if;
    return v_secret;
end;
$$;

-- Thu hồi toàn bộ quyền public/anon/authenticated đối với hàm lấy secret
revoke all on function public.get_media_gateway_secret() from public, anon, authenticated;
grant execute on function public.get_media_gateway_secret() to service_role;

-- 3. Hàm xác thực chữ ký grant token của Media Gateway với identity binding
create or replace function public.verify_media_access_grant(
    p_report_id uuid,
    p_grant_token text,
    p_variant text default 'full',
    p_expires_at_epoch bigint default null
)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_consumer_id uuid := auth.uid();
    v_report record;
    v_camp record;
    v_role text := null;
    v_now_epoch bigint := extract(epoch from now())::bigint;
    v_expected_sig text;
begin
    -- 1. Identity binding: Bắt buộc phải có phiên người dùng thực tế
    if v_consumer_id is null or p_grant_token is null or p_expires_at_epoch is null then
        return false;
    end if;

    -- 2. Kiểm tra hết hạn & tuổi thọ tối đa của grant (tối đa 30 phút = 1800s)
    if v_now_epoch > p_expires_at_epoch or (p_expires_at_epoch - v_now_epoch) > 1800 then
        return false;
    end if;

    -- 3. Kiểm tra báo cáo tồn tại, không bị xóa và chưa hết hạn lưu trữ (SLA 30 ngày)
    select id, campaign_id, user_id, media_expired, created_at
    into v_report
    from public.tester_reports
    where id = p_report_id;

    if v_report.id is null or v_report.media_expired = true or v_report.created_at < (now() - interval '30 days') then
        return false;
    end if;

    -- 4. Xác định quyền hiện tại của consumer đối với báo cáo
    if v_report.user_id = v_consumer_id then
        v_role := 'author';
    else
        select owner_id into v_camp from public.campaigns where id = v_report.campaign_id;
        if v_camp.owner_id = v_consumer_id then
            v_role := 'owner';
        elsif exists (
            select 1 from public.profiles
            where id = v_consumer_id and lower(coalesce(role, '')) in ('admin', 'moderator')
        ) then
            v_role := 'admin';
        else
            return false;
        end if;
    end if;

    -- 5. Tính lại chữ ký HMAC gắn chặt với định danh consumer, role, report, variant, expires
    v_expected_sig := encode(
        hmac(
            format('%s:%s:%s:%s:%s', p_report_id, v_consumer_id, v_role, coalesce(p_variant, 'full'), p_expires_at_epoch),
            public.get_media_gateway_secret(),
            'sha256'
        ),
        'hex'
    );

    return (v_expected_sig = p_grant_token);
exception
    when others then
        return false;
end;
$$;

-- 3b. Phiên bản nội bộ cho Backend Service Role (xác thực token thay mặt user qua JWT)
create or replace function public.verify_media_access_grant_internal(
    p_report_id uuid,
    p_user_id uuid,
    p_grant_token text,
    p_variant text default 'full',
    p_expires_at_epoch bigint default null
)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_report record;
    v_camp record;
    v_role text := null;
    v_now_epoch bigint := extract(epoch from now())::bigint;
    v_expected_sig text;
begin
    if p_user_id is null or p_grant_token is null or p_expires_at_epoch is null then
        return false;
    end if;

    if v_now_epoch > p_expires_at_epoch or (p_expires_at_epoch - v_now_epoch) > 1800 then
        return false;
    end if;

    select id, campaign_id, user_id, media_expired, created_at
    into v_report
    from public.tester_reports
    where id = p_report_id;

    if v_report.id is null or v_report.media_expired = true or v_report.created_at < (now() - interval '30 days') then
        return false;
    end if;

    if v_report.user_id = p_user_id then
        v_role := 'author';
    else
        select owner_id into v_camp from public.campaigns where id = v_report.campaign_id;
        if v_camp.owner_id = p_user_id then
            v_role := 'owner';
        elsif exists (
            select 1 from public.profiles
            where id = p_user_id and lower(coalesce(role, '')) in ('admin', 'moderator')
        ) then
            v_role := 'admin';
        else
            return false;
        end if;
    end if;

    v_expected_sig := encode(
        hmac(
            format('%s:%s:%s:%s:%s', p_report_id, p_user_id, v_role, coalesce(p_variant, 'full'), p_expires_at_epoch),
            public.get_media_gateway_secret(),
            'sha256'
        ),
        'hex'
    );

    return (v_expected_sig = p_grant_token);
exception
    when others then
        return false;
end;
$$;

-- 4. Function cấp quyền truy cập media ngắn hạn có xác thực danh tính từ JWT auth.uid()
create or replace function public.get_report_media_access(
    p_report_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_user_id uuid := auth.uid();
    v_report record;
    v_camp record;
    v_role text := null;
    v_is_admin boolean := false;
    v_is_author boolean := false;
    v_is_owner boolean := false;
    v_full_token text;
    v_thumb_token text;
    v_expires_at timestamptz;
    v_expires_epoch bigint;
    v_gateway_base text;
    v_signed_full_url text;
    v_signed_thumb_url text;
begin
    -- 1. Xác thực bắt buộc: Phải có JWT hợp lệ, không tin bất kỳ UID/role client gửi
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- 2. Đọc thông tin báo cáo
    select id, campaign_id, user_id, screenshot_url, thumbnail_url,
           created_at, media_expired
    into v_report
    from public.tester_reports
    where id = p_report_id;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    -- 3. Kiểm tra thời hạn lưu trữ & thu hồi (SLA 30 ngày)
    if v_report.media_expired = true or v_report.created_at < (now() - interval '30 days') then
        raise exception 'MEDIA_EXPIRED';
    end if;

    -- 4. Xác định quyền dựa trên quan hệ thực tế trong database
    v_is_author := (v_report.user_id = v_user_id);

    select * into v_camp from public.campaigns where id = v_report.campaign_id;
    if v_camp.id is not null and v_camp.owner_id = v_user_id then
        v_is_owner := true;
    end if;

    if exists (
        select 1 from public.profiles
        where id = v_user_id and lower(coalesce(role, '')) in ('admin', 'moderator')
    ) then
        v_is_admin := true;
    end if;

    if v_is_author then
        v_role := 'author';
    elsif v_is_owner then
        v_role := 'owner';
    elsif v_is_admin then
        v_role := 'admin';
    else
        -- Không có quyền truy cập báo cáo này
        raise exception 'FORBIDDEN';
    end if;

    -- 5. Cấp grant tải media có hiệu lực ngắn hạn (15 phút) dùng HMAC sha256 với secret server
    v_expires_at := now() + interval '15 minutes';
    v_expires_epoch := extract(epoch from v_expires_at)::bigint;

    v_full_token := encode(
        hmac(
            format('%s:%s:%s:%s:%s', p_report_id, v_user_id, v_role, 'full', v_expires_epoch),
            public.get_media_gateway_secret(),
            'sha256'
        ),
        'hex'
    );

    v_thumb_token := encode(
        hmac(
            format('%s:%s:%s:%s:%s', p_report_id, v_user_id, v_role, 'thumb', v_expires_epoch),
            public.get_media_gateway_secret(),
            'sha256'
        ),
        'hex'
    );

    v_gateway_base := nullif(current_setting('app.settings.media_gateway_base_url', true), '');
    if v_gateway_base is null or length(trim(v_gateway_base)) = 0 then
        raise exception 'MEDIA_GATEWAY_BASE_URL_NOT_CONFIGURED';
    end if;

    v_signed_full_url := format('%s?report_id=%s&variant=full&expires_at=%s&grant_token=%s',
        v_gateway_base, p_report_id, v_expires_epoch, v_full_token);

    v_signed_thumb_url := case
        when v_report.thumbnail_url is not null then
            format('%s?report_id=%s&variant=thumb&expires_at=%s&grant_token=%s',
                v_gateway_base, p_report_id, v_expires_epoch, v_thumb_token)
        else null
    end;

    -- Không trả về raw_storage_url cho client để chặn bypass URL
    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'role', v_role,
        'screenshot_url', v_signed_full_url,
        'thumbnail_url', v_signed_thumb_url,
        'expires_at', v_expires_at,
        'expires_at_epoch', v_expires_epoch,
        'access_grant_token', v_full_token
    );
end;
$$;

revoke execute on function public.get_report_media_access(uuid) from public, anon;
grant execute on function public.get_report_media_access(uuid) to authenticated, service_role;
grant execute on function public.verify_media_access_grant(uuid, text, text, bigint) to authenticated, service_role;
revoke execute on function public.verify_media_access_grant_internal(uuid, uuid, text, text, bigint) from public, anon, authenticated;
grant execute on function public.verify_media_access_grant_internal(uuid, uuid, text, text, bigint) to service_role;
