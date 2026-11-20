CREATE OR REPLACE FUNCTION public.admin_reply_contact_report(p_report_id uuid, p_message text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_report public.contact_reports;
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
if p_message is null or trim(p_message) = '' then
raise exception 'MESSAGE_REQUIRED';
end if;
update public.contact_reports
set admin_reply = trim(p_message),
replied_at = now(),
status = 'replied'
where id = p_report_id
returning * into v_report;
if v_report.id is null then
raise exception 'REPORT_NOT_FOUND';
end if;
insert into public.notifications (user_id, title, message, type, data)
values (
v_report.user_id,
'Phản hồi từ Admin',
trim(p_message),
'admin',
jsonb_build_object(
'sender', 'admin',
'broadcast', false,
'contact_report_id', p_report_id
)
);
return to_jsonb(v_report);
end;
$function$
