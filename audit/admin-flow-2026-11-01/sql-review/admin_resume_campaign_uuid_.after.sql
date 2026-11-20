CREATE OR REPLACE FUNCTION public.admin_resume_campaign(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
v_camp public.campaigns%rowtype;
v_hold_open boolean;
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
if v_camp.status <> 'paused' then
raise exception 'CAMPAIGN_NOT_PAUSED';
end if;
if v_camp.created_at <= now() - interval '30 days' then
update public.campaigns
set status = 'completed',
completed_at = now(),
paused_reason = null,
admin_note = null,
admin_resumed = false
where id = p_campaign_id;
insert into public.notifications (user_id, title, message, type, data)
values (
v_camp.owner_id,
'Ứng dụng đã hoàn thành kiểm thử ✅',
format(
'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 30 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
coalesce(nullif(v_camp.app_name, ''), 'của bạn')
),
'campaign',
jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'window_expired')
);
return jsonb_build_object(
'success', true,
'campaign_id', p_campaign_id,
'previous_status', v_camp.status,
'status', 'completed',
'app_name', v_camp.app_name,
'owner_id', v_camp.owner_id
);
end if;
if v_camp.slot_number > 1 then
if not exists (
select 1 from public.user_slots s
where s.user_id = v_camp.owner_id
and s.slot_number = v_camp.slot_number
and s.expires_at > now()
) then
raise exception 'SLOT_EXPIRED';
end if;
end if;
v_hold_open := (v_camp.paused_reason = 'owner_inactive');
update public.campaigns
set status = 'open',
paused_reason = null,
completed_at = null,
admin_note = null,
admin_resumed = v_hold_open
where id = p_campaign_id;
insert into public.notifications (user_id, title, message, type, data)
values (
v_camp.owner_id,
'Ứng dụng đã được Admin mở lại ✅',
format(
'Ứng dụng "%s" đã được Quản trị viên mở lại và đang nhận tester bình thường.',
coalesce(nullif(v_camp.app_name, ''), 'của bạn')
),
'campaign',
jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'admin_resume')
);
return jsonb_build_object(
'success', true,
'campaign_id', p_campaign_id,
'previous_status', v_camp.status,
'status', 'open',
'paused_reason', null,
'app_name', v_camp.app_name,
'owner_id', v_camp.owner_id,
'admin_resumed', v_hold_open
);
end;
$function$
