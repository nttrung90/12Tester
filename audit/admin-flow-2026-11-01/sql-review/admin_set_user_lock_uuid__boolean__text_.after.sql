CREATE OR REPLACE FUNCTION public.admin_set_user_lock(p_target_user_id uuid, p_locked boolean, p_reason text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role, coalesce(is_locked, false)
into v_caller_role, v_caller_locked
from public.profiles
where id = v_caller_id;
if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền khóa/mở khóa tài khoản';
end if;
if p_target_user_id is null then
raise exception 'TARGET_USER_REQUIRED';
end if;
if p_target_user_id = v_caller_id and p_locked = true then
raise exception 'CANNOT_LOCK_SELF: Không thể tự khóa tài khoản của chính mình';
end if;
update public.profiles
set is_locked = p_locked
where id = p_target_user_id;
if not found then
raise exception 'USER_NOT_FOUND';
end if;
return jsonb_build_object(
'success', true,
'user_id', p_target_user_id,
'is_locked', p_locked
);
end;
$function$
