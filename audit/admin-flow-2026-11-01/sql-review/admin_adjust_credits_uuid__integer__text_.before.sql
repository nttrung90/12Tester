CREATE OR REPLACE FUNCTION public.admin_adjust_credits(p_target_user_id uuid, p_amount integer, p_reason text DEFAULT 'Điều chỉnh bởi Admin'::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_current_credits int;
v_new_balance int;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role into v_caller_role
from public.profiles
where id = v_caller_id;
if v_caller_role is null or lower(v_caller_role) != 'admin' then
raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên mới có quyền điều chỉnh Credit';
end if;
select credits into v_current_credits
from public.profiles
where id = p_target_user_id
for update;
if v_current_credits is null then
raise exception 'USER_NOT_FOUND';
end if;
v_new_balance := v_current_credits + p_amount;
if v_new_balance < 0 then
v_new_balance := 0;
end if;
update public.profiles
set credits = v_new_balance
where id = p_target_user_id;
insert into public.credit_logs (user_id, amount, action, note, balance)
values (
p_target_user_id,
p_amount,
case when p_amount >= 0 then 'admin_grant' else 'admin_deduct' end,
coalesce(nullif(trim(p_reason), ''), case when p_amount >= 0 then 'Admin tặng Credit' else 'Admin trừ Credit' end),
v_new_balance
);
return jsonb_build_object(
'success', true,
'user_id', p_target_user_id,
'previous_credits', v_current_credits,
'new_credits', v_new_balance,
'amount', p_amount
);
end;
$function$
