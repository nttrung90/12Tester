CREATE OR REPLACE FUNCTION public._impl_admin_delete_campaign(p_campaign_id uuid, p_reason text DEFAULT 'Gỡ bỏ bởi Admin'::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_app_name text;
    v_owner_id uuid;
    v_m record;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
        raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền gỡ bỏ ứng dụng';
    end if;

    select app_name, owner_id into v_app_name, v_owner_id
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Close any non-deleted missions and attempt to refund remaining escrow
    -- when no pending mission reports or settlement jobs remain.
    for v_m in
        select id, status, escrow_remaining_credits, escrow_refunded
        from public.missions
        where campaign_id = p_campaign_id
        for update
    loop
        if v_m.status <> 'deleted' then
            update public.missions
            set status = 'deleted',
                admin_closed_at = coalesce(admin_closed_at, now())
            where id = v_m.id;
        end if;

        if v_m.escrow_remaining_credits > 0 and not v_m.escrow_refunded then
            perform public._check_and_refund_mission_escrow(v_m.id);
        end if;
    end loop;

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
$function$
