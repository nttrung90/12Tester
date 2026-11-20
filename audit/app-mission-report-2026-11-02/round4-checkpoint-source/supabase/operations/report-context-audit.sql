-- Read-only candidates for investigation. A mission label alone is not proof of a mistake.
select r.id,r.user_id,r.campaign_id,r.client_submit_id,r.created_at,r.status,
 r.submission_context,r.mission_id,r.provisional_granted_credits,r.provisional_clawed,
 m.status as mission_status,m.title as mission_title,
 exists(select 1 from public.report_submission_receipts x
   where x.user_id=r.user_id and x.client_submit_id=r.client_submit_id) as has_receipt,
 exists(select 1 from public.checkins c where c.user_id=r.user_id and c.campaign_id=r.campaign_id
   and c.checkin_date=(r.created_at at time zone 'Asia/Ho_Chi_Minh')::date) as has_checkin
from public.tester_reports r left join public.missions m on m.id=r.mission_id
where r.created_at>=now()-interval '7 days' and r.submission_context='mission'
order by r.created_at desc limit 500;
