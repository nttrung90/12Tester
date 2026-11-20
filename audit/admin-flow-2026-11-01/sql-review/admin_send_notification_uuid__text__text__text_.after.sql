CREATE OR REPLACE FUNCTION public.admin_send_notification(p_target_user_id uuid DEFAULT NULL::uuid, p_title text DEFAULT ''::text, p_message text DEFAULT ''::text, p_type text DEFAULT 'admin'::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
v_count int := 0;
v_new_notification_id uuid;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role, coalesce(is_locked, false)
into v_caller_role, v_caller_locked
from public.profiles
where id = v_caller_id;
if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
raise exception 'PERMISSION_DENIED';
end if;
if p_title is null or trim(p_title) = '' then
raise exception 'TITLE_REQUIRED';
end if;
if p_message is null or trim(p_message) = '' then
raise exception 'MESSAGE_REQUIRED';
end if;
if p_target_user_id is not null then
if not exists (select 1 from public.profiles where id = p_target_user_id) then
raise exception 'TARGET_USER_NOT_FOUND';
end if;
insert into public.notifications (user_id, title, message, type, data, created_at)
values (
p_target_user_id,
p_title,
p_message,
coalesce(p_type, 'admin'),
jsonb_build_object('sender', 'admin', 'broadcast', false),
clock_timestamp()
)
returning id into v_new_notification_id;
insert into public.notification_popup_states (notification_id, display_count)
values (v_new_notification_id, 0);
v_count := 1;
else
insert into public.notifications (user_id, title, message, type, data)
select
id,
p_title,
p_message,
coalesce(p_type, 'admin'),
jsonb_build_object('sender', 'admin', 'broadcast', true)
from public.profiles;
get diagnostics v_count = row_count;
end if;
return jsonb_build_object(
'success', true,
'sent_count', v_count,
'target', case when p_target_user_id is null then 'all' else p_target_user_id::text end
);
end;
$function$
