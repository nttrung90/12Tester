CREATE OR REPLACE FUNCTION public.admin_set_user_lock(p_target_user_id uuid, p_locked boolean, p_reason text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role into v_caller_role
from public.profiles
where id = v_caller_id;
if v_caller_role is null or lower(v_caller_role) != 'admin' then
raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền khóa/mở khóa tài khoản';
end if;
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
$function$
