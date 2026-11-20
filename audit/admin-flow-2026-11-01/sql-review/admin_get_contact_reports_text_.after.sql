CREATE OR REPLACE FUNCTION public.admin_get_contact_reports(p_status text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
v_result jsonb;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role, coalesce(is_locked, false)
into v_caller_role, v_caller_locked
from public.profiles
where id = v_caller_id;
if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
raise exception 'NOT_ADMIN';
end if;
select coalesce(jsonb_agg(to_jsonb(t) order by t.created_at desc), '[]'::jsonb)
into v_result
from (
select
r.id,
r.user_id,
r.category,
r.message,
r.app_version,
r.platform,
r.device_model,
r.status,
r.admin_reply,
r.replied_at,
r.created_at,
p.display_name,
p.email,
p.avatar_url
from public.contact_reports r
left join public.profiles p on p.id = r.user_id
where p_status is null or r.status = p_status
order by r.created_at desc
limit 300
) t;
return v_result;
end;
$function$
