CREATE OR REPLACE FUNCTION public.admin_pause_campaign(p_campaign_id uuid, p_reason text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_camp public.campaigns%rowtype;
v_note text;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select lower(role) into v_caller_role
from public.profiles
where id = v_caller_id;
if coalesce(v_caller_role, '') <> 'admin' then
raise exception 'NOT_ADMIN';
end if;
select * into v_camp
from public.campaigns
where id = p_campaign_id
for update;
if v_camp.id is null then
raise exception 'CAMPAIGN_NOT_FOUND';
end if;
if v_camp.status in ('production', 'completed') then
raise exception 'CAMPAIGN_FINALIZED';
end if;
if v_camp.status not in ('open', 'paused') then
raise exception 'CAMPAIGN_NOT_OPEN';
end if;
v_note := nullif(btrim(coalesce(p_reason, '')), '');
if v_camp.status = 'paused'
and v_camp.paused_reason = 'admin_manual'
and v_camp.admin_note is not distinct from v_note then
return jsonb_build_object(
'success', true,
'campaign_id', p_campaign_id,
'previous_status', v_camp.status,
'status', v_camp.status,
'paused_reason', v_camp.paused_reason,
'app_name', v_camp.app_name,
'owner_id', v_camp.owner_id,
'admin_note', v_camp.admin_note
);
end if;
update public.campaigns
set status = 'paused',
paused_reason = 'admin_manual',
admin_note = v_note,
admin_resumed = false
where id = p_campaign_id;
insert into public.notifications (user_id, title, message, type, data)
values (
v_camp.owner_id,
'Ứng dụng đã bị tạm dừng bởi Admin ⏸',
format(
'Ứng dụng "%s" đã bị Quản trị viên tạm dừng.%s',
coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
case when v_note is null then '' else E'\nLý do: ' || v_note end
),
'campaign',
jsonb_build_object(
'campaign_id', p_campaign_id,
'reason', 'admin_pause',
'admin_note', v_note
)
);
return jsonb_build_object(
'success', true,
'campaign_id', p_campaign_id,
'previous_status', v_camp.status,
'status', 'paused',
'paused_reason', 'admin_manual',
'app_name', v_camp.app_name,
'owner_id', v_camp.owner_id,
'admin_note', v_note
);
end;
$function$
