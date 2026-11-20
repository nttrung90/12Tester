CREATE OR REPLACE FUNCTION public.admin_update_app_config(p_min_version_code integer, p_latest_version_code integer, p_latest_version_name text, p_force_update boolean, p_title text DEFAULT NULL::text, p_message text DEFAULT NULL::text, p_store_url text DEFAULT NULL::text, p_title_vi text DEFAULT NULL::text, p_title_en text DEFAULT NULL::text, p_message_vi text DEFAULT NULL::text, p_message_en text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
v_config public.app_configs;
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
if p_min_version_code < 1 or p_latest_version_code < p_min_version_code then
raise exception 'INVALID_VERSION_RANGE';
end if;
if p_latest_version_name is null or trim(p_latest_version_name) = '' then
raise exception 'INVALID_VERSION_NAME';
end if;
insert into public.app_configs (
id,
min_version_code,
latest_version_code,
latest_version_name,
force_update,
title,
message,
store_url,
title_vi,
title_en,
message_vi,
message_en,
updated_at
)
values (
'android_client',
p_min_version_code,
p_latest_version_code,
trim(p_latest_version_name),
p_force_update,
coalesce(p_title, p_title_vi, p_title_en),
coalesce(p_message, p_message_vi, p_message_en),
p_store_url,
p_title_vi,
p_title_en,
p_message_vi,
p_message_en,
now()
)
on conflict (id) do update set
min_version_code = excluded.min_version_code,
latest_version_code = excluded.latest_version_code,
latest_version_name = excluded.latest_version_name,
force_update = excluded.force_update,
title = coalesce(excluded.title, p_title_vi, p_title_en),
message = coalesce(excluded.message, p_message_vi, p_message_en),
store_url = coalesce(excluded.store_url, public.app_configs.store_url),
title_vi = excluded.title_vi,
title_en = excluded.title_en,
message_vi = excluded.message_vi,
message_en = excluded.message_en,
updated_at = now()
returning * into v_config;
return to_jsonb(v_config);
end;
$function$
