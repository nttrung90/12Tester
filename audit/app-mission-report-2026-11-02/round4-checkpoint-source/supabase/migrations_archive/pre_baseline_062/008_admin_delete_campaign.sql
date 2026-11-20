-- ============================================================================
-- 12 Tester — Supabase migration 008: Admin Delete Any Campaign & Cascade Data
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Cập nhật RLS Policy trên bảng campaigns: Cho phép Admin hoặc Chủ app xóa app
drop policy if exists "campaigns_delete_owner" on public.campaigns;
drop policy if exists "campaigns_delete_owner_or_admin" on public.campaigns;

create policy "campaigns_delete_owner_or_admin"
    on public.campaigns for delete to authenticated
    using (
        owner_id = auth.uid()
        or exists (
            select 1 from public.profiles
            where id = auth.uid() and role = 'admin'
        )
    );

-- 2. Stored Procedure: Admin gỡ bỏ chiến dịch và xóa sạch toàn bộ hồ sơ test liên quan
create or replace function public.admin_delete_campaign(
    p_campaign_id uuid,
    p_reason text default 'Gỡ bỏ bởi Admin'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_app_name text;
    v_owner_id uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Kiểm tra quyền Admin của người thực hiện
    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền gỡ bỏ ứng dụng';
    end if;

    -- Lấy thông tin ứng dụng trước khi xóa
    select app_name, owner_id into v_app_name, v_owner_id
    from public.campaigns
    where id = p_campaign_id;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Xóa app (nhờ ON DELETE CASCADE, tất cả checkins, feedbacks, campaign_members, tester_reports sẽ tự động bị xóa theo)
    delete from public.campaigns
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'app_name', v_app_name,
        'owner_id', v_owner_id,
        'reason', coalesce(p_reason, 'Gỡ bỏ bởi Admin')
    );
end;
$$;

-- 3. Cấp quyền thực thi và nạp lại schema cache cho PostgREST
grant execute on function public.admin_delete_campaign(uuid, text) to authenticated, service_role;

notify pgrst, 'reload schema';
