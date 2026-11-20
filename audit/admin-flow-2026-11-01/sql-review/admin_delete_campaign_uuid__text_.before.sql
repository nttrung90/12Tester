CREATE OR REPLACE FUNCTION public.admin_delete_campaign(p_campaign_id uuid, p_reason text DEFAULT 'Gỡ bỏ bởi Admin'::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_app_name text;
v_owner_id uuid;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role into v_caller_role
from public.profiles
where id = v_caller_id;
if v_caller_role is null or lower(v_caller_role) != 'admin' then
raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền gỡ bỏ ứng dụng';
end if;
select app_name, owner_id into v_app_name, v_owner_id
from public.campaigns
where id = p_campaign_id;
if v_app_name is null then
raise exception 'CAMPAIGN_NOT_FOUND';
end if;
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
