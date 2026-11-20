CREATE OR REPLACE FUNCTION public.admin_get_home_stats_config()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_uid uuid := auth.uid();
v_caller_role text;
v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
v_active_days int;
v_active_from date;
v_pending_days int;
v_pending_from date;
v_revision text;
begin
if v_uid is null then
raise exception 'UNAUTHORIZED: Authentication required';
end if;
select role into v_caller_role from public.profiles where id = v_uid;
if v_caller_role is null or lower(v_caller_role) != 'admin' then
raise exception 'FORBIDDEN: Admin access required';
end if;
select cycle_days, effective_from into v_active_days, v_active_from
from public.home_stats_policies
where effective_from <= v_today
order by effective_from desc
limit 1;
select cycle_days, effective_from into v_pending_days, v_pending_from
from public.home_stats_policies
where effective_from > v_today
order by effective_from asc
limit 1;
v_revision := public._get_home_stats_revision();
return jsonb_build_object(
'active_cycle_days', coalesce(v_active_days, 1),
'active_effective_from', v_active_from,
'pending_cycle_days', v_pending_days,
'pending_effective_from', v_pending_from,
'revision', v_revision,
'today', v_today
);
end;
$function$
