CREATE OR REPLACE FUNCTION public.admin_adjust_credits(p_target_user_id uuid, p_amount integer, p_reason text DEFAULT 'Điều chỉnh bởi Admin'::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
v_current_credits int;
v_calc_balance bigint;
v_new_balance int;
v_effective_amount int;
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role, coalesce(is_locked, false)
into v_caller_role, v_caller_locked
from public.profiles
where id = v_caller_id;
if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_locked = true then
raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền điều chỉnh Credit';
end if;
if p_target_user_id is null then
raise exception 'TARGET_USER_REQUIRED';
end if;
if p_amount is null or p_amount = 0 then
raise exception 'INVALID_AMOUNT: Số lượng credit phải khác 0';
end if;
select credits into v_current_credits
from public.profiles
where id = p_target_user_id
for update;
if not found or v_current_credits is null then
raise exception 'USER_NOT_FOUND';
end if;
v_calc_balance := v_current_credits::bigint + p_amount::bigint;
if v_calc_balance > 2147483647 then
raise exception 'CREDIT_OVERFLOW: Số dư vượt quá giới hạn tối đa';
end if;
if v_calc_balance < 0 then
v_new_balance := 0;
else
v_new_balance := v_calc_balance::integer;
end if;
v_effective_amount := v_new_balance - v_current_credits;
update public.profiles
set credits = v_new_balance
where id = p_target_user_id;
insert into public.credit_logs (user_id, amount, action, note, balance)
values (
p_target_user_id,
v_effective_amount,
case when p_amount >= 0 then 'admin_grant' else 'admin_deduct' end,
coalesce(nullif(trim(p_reason), ''), case when p_amount >= 0 then 'Admin tặng Credit' else 'Admin trừ Credit' end),
v_new_balance
);
return jsonb_build_object(
'success', true,
'user_id', p_target_user_id,
'previous_credits', v_current_credits,
'new_credits', v_new_balance,
'amount', v_effective_amount
);
end;
$function$
