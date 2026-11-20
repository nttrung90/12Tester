CREATE OR REPLACE FUNCTION public.admin_update_report_status(p_report_id uuid, p_resolved boolean)
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
update public.contact_reports
set status = case
when p_resolved then 'resolved'
when admin_reply is not null then 'replied'
else 'new'
end
where id = p_report_id
returning * into v_report;
if v_report.id is null then
raise exception 'REPORT_NOT_FOUND';
end if;
return to_jsonb(v_report);
end;
$function$
