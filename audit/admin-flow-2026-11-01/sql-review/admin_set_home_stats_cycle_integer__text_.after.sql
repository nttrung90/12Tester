CREATE OR REPLACE FUNCTION public.admin_set_home_stats_cycle(p_cycle_days integer, p_expected_revision text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_uid uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
v_today date;
v_tomorrow date;
v_active_days int;
v_active_from date;
v_existing_tomorrow int;
v_current_rev text;
r record;
begin
if v_uid is null then
raise exception 'UNAUTHORIZED: Authentication required';
end if;
select role, coalesce(is_locked, false)
into v_caller_role, v_caller_locked
from public.profiles
where id = v_uid;
if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
raise exception 'FORBIDDEN: Admin access required';
end if;
if p_cycle_days is null or p_cycle_days not in (1, 2, 3) then
raise exception 'INVALID_CYCLE_DAYS: Must be 1, 2, or 3';
end if;
if p_expected_revision is null or trim(p_expected_revision) = '' then
raise exception 'MISSING_REVISION: Expected revision is required';
end if;
lock table public.home_stats_policies in exclusive mode;
v_today := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
v_tomorrow := v_today + 1;
v_current_rev := public._get_home_stats_revision();
if p_expected_revision <> v_current_rev then
raise exception 'CONCURRENCY_CONFLICT: Config has been modified by another administrator';
end if;
select cycle_days, effective_from into v_active_days, v_active_from
from public.home_stats_policies
where effective_from <= v_today
order by effective_from desc
limit 1;
v_active_days := coalesce(v_active_days, 1);
if p_cycle_days = v_active_days then
for r in select * from public.home_stats_policies where effective_from > v_today loop
insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
values ('CANCEL_SCHEDULE', r.effective_from, r.cycle_days, null, v_uid);
end loop;
delete from public.home_stats_policies where effective_from > v_today;
else
select cycle_days into v_existing_tomorrow
from public.home_stats_policies
where effective_from = v_tomorrow;
if found then
if v_existing_tomorrow <> p_cycle_days then
update public.home_stats_policies
set cycle_days = p_cycle_days, updated_at = now(), updated_by = v_uid
where effective_from = v_tomorrow;
insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
values ('UPDATE_SCHEDULE', v_tomorrow, v_existing_tomorrow, p_cycle_days, v_uid);
end if;
else
insert into public.home_stats_policies (effective_from, cycle_days, created_by, updated_by, created_at, updated_at)
values (v_tomorrow, p_cycle_days, v_uid, v_uid, now(), now());
insert into public.home_stats_policy_audits (action, effective_from, cycle_days_old, cycle_days_new, actor_id)
values ('SET_SCHEDULE', v_tomorrow, v_active_days, p_cycle_days, v_uid);
end if;
delete from public.home_stats_policies where effective_from > v_tomorrow;
end if;
return public.admin_get_home_stats_config();
end;
$function$
