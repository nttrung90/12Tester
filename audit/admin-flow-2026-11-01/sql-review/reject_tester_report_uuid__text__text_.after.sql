CREATE OR REPLACE FUNCTION public.reject_tester_report(p_report_id uuid, p_reason text DEFAULT NULL::text, p_reason_code text DEFAULT NULL::text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
v_caller_id uuid := auth.uid();
v_caller_role text;
v_caller_locked boolean;
v_report record;
v_camp record;
v_tester_credits int;
v_deduct int := 0;
v_new_balance int;
v_report_date date;
v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
v_extracted_code text;
v_final_reason_code text;
v_tester_today_rejections int := 0;
v_tester_warned boolean := false;
v_tester_warning_msg text := null;
v_tester_weekly_warnings int := 0;
v_is_tester_locked boolean := false;
v_owner_today_rejections int := 0;
v_owner_warned boolean := false;
v_owner_warning_msg text := null;
v_owner_weekly_warnings int := 0;
v_is_owner_locked boolean := false;
v_msg_tester text := 'Hệ thống ghi nhận bạn tham gia kiểm thử chưa nghiêm túc, đề nghị test app nghiêm túc và chụp ảnh màn hình không trùng với ảnh đã chụp hôm trước (nên chụp các tính năng khác của ứng dụng kiểm thử). Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
v_msg_owner text := 'Hệ thống ghi nhận bạn xác nhận kết quả của người kiểm thử chưa nghiêm túc, đề nghị đối chiếu kỹ ảnh chụp của người kiểm thử chụp các ngày khác nhau để duyệt cho chính xác. Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
begin
if v_caller_id is null then
raise exception 'NOT_AUTHENTICATED';
end if;
select role, coalesce(is_locked, false)
into v_caller_role, v_caller_locked
from public.profiles
where id = v_caller_id;
if v_caller_locked = true then
raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
end if;
select * into v_report
from public.tester_reports
where id = p_report_id
for update;
if v_report.id is null then
raise exception 'REPORT_NOT_FOUND';
end if;
if v_report.status <> 'pending' then
raise exception 'REPORT_ALREADY_REVIEWED';
end if;
select * into v_camp
from public.campaigns
where id = v_report.campaign_id;
if v_camp.owner_id <> v_caller_id
and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
raise exception 'PERMISSION_DENIED';
end if;
if v_report.created_at <= (now() - interval '8 hours') then
raise exception 'REPORT_EXPIRED';
end if;
if p_reason is not null and p_reason like '[CODE:%]' then
v_extracted_code := substring(p_reason from '\[CODE:([A-Za-z0-9_]+)\]');
end if;
v_final_reason_code := coalesce(p_reason_code, v_extracted_code);
insert into public.report_review_events (
report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
) values (
v_report.id, v_report.campaign_id, v_report.user_id,
v_camp.owner_id, v_caller_id, 'reject', p_reason
);
v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;
select credits into v_tester_credits
from public.profiles
where id = v_report.user_id
for update;
v_deduct := least(
coalesce(v_tester_credits, 0),
greatest(0, least(10, coalesce(v_report.provisional_granted_credits, 2)))
);
if v_deduct > 0 then
update public.profiles
set credits = credits - v_deduct
where id = v_report.user_id
returning credits into v_new_balance;
insert into public.credit_logs (user_id, amount, action, note, balance, metadata)
values (
v_report.user_id,
-v_deduct,
'test_rejected_clawback',
format('Thu hồi %s Credit tạm nhận do báo cáo bị từ chối: %s', v_deduct, coalesce(p_reason, 'Chưa đạt yêu cầu')),
v_new_balance,
jsonb_build_object('report_id', v_report.id, 'campaign_id', v_camp.id, 'reason_code', v_final_reason_code)
);
else
v_new_balance := coalesce(v_tester_credits, 0);
end if;
update public.tester_reports
set status = 'rejected',
rejection_reason = p_reason,
reason_code = v_final_reason_code,
bonus_awarded = 0,
provisional_clawed = v_deduct,
reviewed_at = now()
where id = p_report_id;
delete from public.checkins
where campaign_id = v_report.campaign_id
and user_id = v_report.user_id
and checkin_date = v_report_date
and coalesce(note, '') like '%tạm tính%';
select count(*) into v_tester_today_rejections
from public.report_review_events
where tester_id = v_report.user_id
and event_type = 'reject'
and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;
if v_tester_today_rejections >= 3 then
if not exists (
select 1 from public.user_warnings
where user_id = v_report.user_id
and warning_type = 'tester_rejection_streak'
and warning_date = v_vn_today
) then
insert into public.user_warnings (user_id, warning_type, message, warning_date, metadata)
values (
v_report.user_id,
'tester_rejection_streak',
v_msg_tester,
v_vn_today,
jsonb_build_object('warning_type', 'tester_rejection_streak', 'streak_count', v_tester_today_rejections)
);
v_tester_warned := true;
v_tester_warning_msg := v_msg_tester;
select count(*) into v_tester_weekly_warnings
from public.user_warnings
where user_id = v_report.user_id
and warning_date >= (v_vn_today - 6);
if v_tester_weekly_warnings >= 3 then
update public.profiles
set is_locked = true
where id = v_report.user_id;
v_is_tester_locked := true;
end if;
end if;
end if;
select count(*) into v_owner_today_rejections
from public.report_review_events
where owner_id = v_camp.owner_id
and event_type = 'reject'
and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;
if v_owner_today_rejections >= 5 then
if not exists (
select 1 from public.user_warnings
where user_id = v_camp.owner_id
and warning_type = 'owner_rejection_streak'
and warning_date = v_vn_today
) then
insert into public.user_warnings (user_id, warning_type, message, warning_date, metadata)
values (
v_camp.owner_id,
'owner_rejection_streak',
v_msg_owner,
v_vn_today,
jsonb_build_object('warning_type', 'owner_rejection_streak', 'streak_count', v_owner_today_rejections)
);
v_owner_warned := true;
v_owner_warning_msg := v_msg_owner;
select count(*) into v_owner_weekly_warnings
from public.user_warnings
where user_id = v_camp.owner_id
and warning_date >= (v_vn_today - 6);
if v_owner_weekly_warnings >= 3 then
update public.profiles
set is_locked = true
where id = v_camp.owner_id;
v_is_owner_locked := true;
end if;
end if;
end if;
return jsonb_build_object(
'success', true,
'report_id', v_report.id,
'campaign_id', v_camp.id,
'tester_id', v_report.user_id,
'status', 'rejected',
'credits_deducted', v_deduct,
'clawed_credits', v_deduct,
'tester_balance', v_new_balance,
'new_balance', v_new_balance,
'reason_code', v_final_reason_code,
'tester_today_rejections', v_tester_today_rejections,
'tester_warned', v_tester_warned,
'tester_warning_msg', v_tester_warning_msg,
'is_tester_locked', v_is_tester_locked,
'tester_locked', v_is_tester_locked,
'owner_today_rejections', v_owner_today_rejections,
'owner_warned', v_owner_warned,
'owner_warning_msg', v_owner_warning_msg,
'is_owner_locked', v_is_owner_locked,
'owner_locked', v_is_owner_locked
);
end;
$function$
