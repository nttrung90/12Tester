-- Run via run-daily-task-ratio.mjs in its DISPOSABLE database, with the fixture.
-- The runner opens the transaction; every fixture and schedule is rolled back.
create temp table ratio_assertions (name text not null);
grant insert on ratio_assertions to anon, authenticated, service_role;
create function pg_temp.ratio_check(p_ok boolean, p_name text) returns void
language plpgsql as $$
begin
    if p_ok is distinct from true then raise exception 'ASSERTION FAILED: %', p_name; end if;
    insert into ratio_assertions values (p_name);
end;
$$;
create function pg_temp.ratio_error(p_sql text, p_error text) returns void
language plpgsql as $$
begin
    begin
        execute p_sql;
    exception when others then
        if position(p_error in sqlerrm) > 0 then
            insert into ratio_assertions values ('deny: ' || p_error);
            return;
        end if;
        raise exception 'Expected %, got %', p_error, sqlerrm;
    end;
    raise exception 'Expected error %, SQL succeeded: %', p_error, p_sql;
end;
$$;

-- Ratio x owner-count x source-availability x progress matrix, including 0 apps.
do $$
declare
    d date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    uid uuid := md5('ratio:owner')::uuid;
    n integer; k integer; a integer; completed integer;
    target integer; s jsonb; p public.daily_task_policies;
begin
    perform pg_temp.ratio_check((public.get_daily_task_policy(d)).tests_per_active_app = 3,
        'baseline remains 3');
    foreach n in array array[1,2,3,4,30] loop
        perform public.schedule_daily_task_policy(n, d + 1, 'matrix ratio ' || n);
        p := public.get_daily_task_policy(d + 1);
        perform pg_temp.ratio_check(p.tests_per_active_app = n, 'future ratio selected');
        foreach k in array array[0,1,2,6] loop
            foreach a in array array[0,1,3,6,24,180] loop
                perform pg_temp.ratio_counts(k, a);
                target := least(n*k, a);
                foreach completed in array array[0,greatest(0,target-1),target,target+1] loop
                    -- Construct historical counts without exercising unrelated
                    -- reward triggers thousands of times. Real trigger paths
                    -- are exercised in the finalization / resume tests below.
                    perform set_config('session_replication_role','replica',true);
                    delete from public.checkins where user_id = uid;
                    insert into public.checkins (campaign_id, user_id, checkin_date, note)
                    select md5('ratio:other:' || v)::uuid, uid, d+1, 'matrix check-in'
                    from generate_series(1,completed) v;
                    perform set_config('session_replication_role','origin',true);
                    s := public.get_daily_task_status(uid, d+1);
                    perform pg_temp.ratio_check((s->>'required_tests')::int = n*k,
                        format('required N=%s K=%s A=%s D=%s', n,k,a,completed));
                    perform pg_temp.ratio_check((s->>'effective_required_tests')::int = target,
                        'target capped at availability');
                    perform pg_temp.ratio_check((s->>'completed_tests')::int = completed,
                        'distinct completion count');
                    if k>0 then
                        perform pg_temp.ratio_check((s->>'met')::boolean = (completed>=target),
                            'owner completion follows server target');
                    else
                        perform pg_temp.ratio_check(s->>'mode' = 'member', 'zero apps is member');
                    end if;
                end loop;
            end loop;
        end loop;
    end loop;
end;
$$;

-- Schedule selection is by evaluated date, not latest row or session timezone.
select public.schedule_daily_task_policy(2,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date+1, '2:1 from tomorrow');
select public.schedule_daily_task_policy(4,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date+3, '4:1 later');
set constraints trg_daily_task_policy_commit immediate;
set constraints trg_daily_task_policy_commit deferred;
select pg_temp.ratio_check((select tgdeferrable and tginitdeferred from pg_trigger
    where tgrelid='public.daily_task_policies'::regclass and tgname='trg_daily_task_policy_commit'),
    'policy dates rechecked at transaction end');
do $$
declare d date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    perform pg_temp.ratio_check((public.get_daily_task_policy(d-1)).tests_per_active_app=3, 'yesterday old ratio');
    perform pg_temp.ratio_check((public.get_daily_task_policy(d)).tests_per_active_app=3, 'today old ratio');
    perform pg_temp.ratio_check((public.get_daily_task_policy(d+1)).tests_per_active_app=2, 'new date new ratio');
    perform pg_temp.ratio_check((public.get_daily_task_policy(d+2)).tests_per_active_app=2, 'between schedule dates');
    perform pg_temp.ratio_check((public.get_daily_task_policy(d+3)).tests_per_active_app=4, 'later date later ratio');
    perform pg_temp.ratio_error('select public.get_daily_task_policy(null)', 'DAILY_TASK_POLICY_INVALID_DATE');
    perform pg_temp.ratio_error('select public.get_daily_task_policy(date ''infinity'')', 'DAILY_TASK_POLICY_INVALID_DATE');
end;
$$;
set local timezone = 'America/Los_Angeles';
select pg_temp.ratio_check(
    (public.get_daily_task_policy((now() at time zone 'Asia/Ho_Chi_Minh')::date)).tests_per_active_app=3,
    'session timezone does not choose policy date');
set local timezone = 'UTC';

-- Invalid input and immutable history, including direct SQL and TRUNCATE.
do $$
declare d date := (now() at time zone 'Asia/Ho_Chi_Minh')::date; n int;
begin
    foreach n in array array[0,-1,31] loop
        perform pg_temp.ratio_error(format('select public.schedule_daily_task_policy(%s,%L,''bad'')',n,d+1),
            'DAILY_TASK_POLICY_INVALID_RATIO');
    end loop;
    perform pg_temp.ratio_error(format('select public.schedule_daily_task_policy(null,%L,''bad'')',d+1), 'DAILY_TASK_POLICY_INVALID_RATIO');
    perform pg_temp.ratio_error('select public.schedule_daily_task_policy(2,null,''bad'')', 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED');
    perform pg_temp.ratio_error(format('select public.schedule_daily_task_policy(2,%L,''bad'')',d), 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED');
    perform pg_temp.ratio_error('select public.schedule_daily_task_policy(2,date ''infinity'',''bad'')', 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED');
    perform pg_temp.ratio_error(format('select public.schedule_daily_task_policy(2,%L,''  '')',d+1), 'DAILY_TASK_POLICY_NOTE_REQUIRED');
    perform pg_temp.ratio_error(format('select public.schedule_daily_task_policy(2,%L,null)',d+1), 'DAILY_TASK_POLICY_NOTE_REQUIRED');
    perform pg_temp.ratio_error('update public.daily_task_policies set tests_per_active_app=2 where effective_from=date ''-infinity''', 'DAILY_TASK_POLICY_IMMUTABLE');
    perform pg_temp.ratio_error('delete from public.daily_task_policies where effective_from=date ''-infinity''', 'DAILY_TASK_POLICY_IMMUTABLE');
    perform pg_temp.ratio_error(format('insert into public.daily_task_policies(effective_from,tests_per_active_app,operator_note) values(%L,2,''backdate'')',d-1), 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED');
    perform pg_temp.ratio_error(format('update public.daily_task_policies set effective_from=%L where effective_from=%L',d+2,d+1), 'DAILY_TASK_POLICY_IDENTITY_IMMUTABLE');
    perform pg_temp.ratio_error('truncate public.daily_task_policies cascade', 'DAILY_TASK_POLICY_HISTORY_IMMUTABLE');
    perform pg_temp.ratio_error('update public.daily_task_policy_changes set operator_note=''tamper''', 'DAILY_TASK_POLICY_HISTORY_IMMUTABLE');
    perform pg_temp.ratio_error('delete from public.daily_task_policy_changes', 'DAILY_TASK_POLICY_HISTORY_IMMUTABLE');
    perform pg_temp.ratio_error('truncate public.daily_task_policy_changes', 'DAILY_TASK_POLICY_HISTORY_IMMUTABLE');
    perform public.schedule_daily_task_policy(5,d+8,'original');
    perform public.schedule_daily_task_policy(6,d+8,'revised');
    perform pg_temp.ratio_check((select count(*)=1 from public.daily_task_policies where effective_from=d+8), 'future date unique');
    perform public.cancel_daily_task_policy(d+8,'canceled by test');
    perform pg_temp.ratio_check(not exists(select 1 from public.daily_task_policies where effective_from=d+8), 'future schedule canceled');
    perform pg_temp.ratio_check(exists(select 1 from public.daily_task_policy_changes
        where operation='DELETE' and operator_note='canceled by test'
        and old_row->>'tests_per_active_app'='6'), 'cancel reason and old ratio retained');
    perform pg_temp.ratio_error(format('select public.cancel_daily_task_policy(%L,''missing'')',d+8), 'DAILY_TASK_POLICY_NOT_FOUND');
    perform pg_temp.ratio_error(format('select public.cancel_daily_task_policy(%L,''past'')',d), 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED');
end;
$$;

-- Source-count rules: duplicate reports/checkins, rejected and old reports.
delete from public.checkins;
select pg_temp.ratio_counts(1,6);
set local session_replication_role = replica;
insert into public.tester_reports (campaign_id,user_id,status,created_at)
select md5('ratio:other:' || c)::uuid, md5('ratio:owner')::uuid, st,
    ((now() at time zone 'Asia/Ho_Chi_Minh')::date + day_offset)::timestamp at time zone 'Asia/Ho_Chi_Minh'
from (values (1,'pending',1),(1,'approved',1),(2,'approved',1),(3,'rejected',1),(4,'approved',0)) v(c,st,day_offset);
insert into public.tester_reports (campaign_id,user_id,status,created_at)
values(md5('ratio:own:1')::uuid,md5('ratio:owner')::uuid,'approved',
    ((now() at time zone 'Asia/Ho_Chi_Minh')::date+1)::timestamp at time zone 'Asia/Ho_Chi_Minh');
insert into public.checkins(campaign_id,user_id,checkin_date,note)
select md5('ratio:other:' || c)::uuid,md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date+1,note
from (values (1,'manual test'),(3,'Đã nộp kết quả tạm tính'),(4,'Tự động duyệt report cũ')) v(c,note);
set local session_replication_role = origin;
select pg_temp.ratio_check((public.get_daily_task_status(md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date+1)->>'completed_tests')::int=2,
    'pending/approved count, rejected/own/old/duplicate do not add tests');

-- Today RPCs work through authenticated; no new fields / parameters needed.
select set_config('request.jwt.claim.sub',md5('ratio:owner')::uuid::text,true);
set local role authenticated;
select pg_temp.ratio_check((public.get_my_daily_task_status()->>'required_tests')::int=3,
    'authenticated old RPC returns target');
select pg_temp.ratio_check(public.get_my_dashboard(false)->'daily_task' = public.get_my_daily_task_status(),
    'dashboard and standalone RPC use the same helper');
select pg_temp.ratio_error('select public.get_daily_task_status(md5(''ratio:admin'')::uuid,current_date)', 'permission denied');
select pg_temp.ratio_error('select public.get_daily_task_policy(current_date)', 'permission denied');
select pg_temp.ratio_error('select * from public.daily_task_policies', 'permission denied');
select pg_temp.ratio_error('select * from public.daily_task_policy_changes', 'permission denied');
select pg_temp.ratio_error('select public.schedule_daily_task_policy(2,current_date+1,''attack'')', 'permission denied');
select pg_temp.ratio_error('select public.cancel_daily_task_policy(current_date+1,''attack'')', 'permission denied');
select pg_temp.ratio_error('update public.daily_task_policies set tests_per_active_app=1', 'permission denied');
reset role;
set local role anon;
select pg_temp.ratio_error('select public.schedule_daily_task_policy(2,current_date+1,''attack'')', 'permission denied');
select pg_temp.ratio_error('select * from public.daily_task_policies', 'permission denied');
reset role;
set local role service_role;
select pg_temp.ratio_check((public.get_daily_task_policy((now() at time zone 'Asia/Ho_Chi_Minh')::date)).tests_per_active_app=3,
    'service role can diagnose dated policy');
select pg_temp.ratio_error('select public.schedule_daily_task_policy(2,current_date+1,''attack'')', 'permission denied');
select pg_temp.ratio_error('select public.cancel_daily_task_policy(current_date+1,''attack'')', 'permission denied');
select pg_temp.ratio_error('delete from public.daily_task_policies', 'permission denied');
reset role;
select set_config('request.jwt.claim.sub',md5('ratio:admin')::uuid::text,true);
set local role authenticated;
select pg_temp.ratio_error('select public.schedule_daily_task_policy(2,current_date+1,''admin JWT'')', 'permission denied');
reset role;
select set_config('request.jwt.claim.sub',md5('ratio:owner')::uuid::text,true);

-- A new app counts tomorrow; inactive-owner pause counts, other pause does not.
delete from public.tester_reports;
delete from public.checkins;
update public.campaigns set created_at=now() where id=md5('ratio:own:1')::uuid;
select pg_temp.ratio_check((public.get_daily_task_status(md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date)->>'active_app_count')::int=0, 'new campaign not counted today');
select pg_temp.ratio_check((public.get_daily_task_status(md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date+1)->>'active_app_count')::int=1, 'new campaign counted tomorrow');
update public.campaigns set created_at=now()-interval '2 days', status='paused', paused_reason='owner_inactive'
where id=md5('ratio:own:1')::uuid;
select pg_temp.ratio_check((public.get_daily_task_status(md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date)->>'active_app_count')::int=1, 'inactive pause counts');
update public.campaigns set paused_reason='admin_manual' where id=md5('ratio:own:1')::uuid;
select pg_temp.ratio_check((public.get_daily_task_status(md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date)->>'active_app_count')::int=0, 'admin pause does not count');
select pg_temp.ratio_error('select public.resume_campaign(md5(''ratio:own:1'')::uuid)', 'PAUSED_BY_ADMIN');
select set_config('request.jwt.claim.sub','',true);
update public.campaigns set status='open',paused_reason=null,created_at=now()-interval '31 days'
where id=md5('ratio:own:1')::uuid;
select pg_temp.ratio_check((public.get_daily_task_status(md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date)->>'active_app_count')::int=0, '30 day window retained');
update public.campaigns set created_at=now()-interval '2 days' where id=md5('ratio:own:1')::uuid;
select set_config('request.jwt.claim.sub',md5('ratio:owner')::uuid::text,true);

-- Penalty snapshots and repeated evaluation. Evaluate tomorrow in the isolated
-- DB so the new dated policy can be exercised without changing the host clock.
delete from public.user_daily_tasks;
delete from public.credit_logs;
update public.profiles set credits=100,daily_missed_streak=0;
select pg_temp.ratio_counts(1,6);
insert into public.checkins(campaign_id,user_id,checkin_date)
values(md5('ratio:other:1')::uuid,md5('ratio:owner')::uuid,(now() at time zone 'Asia/Ho_Chi_Minh')::date+1);
update public.profiles set credits=100 where id=md5('ratio:owner')::uuid;
select public._apply_daily_inactivity_penalty((now() at time zone 'Asia/Ho_Chi_Minh')::date+1);
select pg_temp.ratio_check((select t.required_tests=2 and t.effective_required_tests=2
    and t.completed_tests=1 and not t.met and t.penalty_credits=1
    and t.tests_per_active_app=2 and p.tests_per_active_app=2
    from public.user_daily_tasks t join public.daily_task_policies p on p.id=t.policy_id
    where t.user_id=md5('ratio:owner')::uuid and task_date=(now() at time zone 'Asia/Ho_Chi_Minh')::date+1),
    'new ratio drives finalizer, fractional penalty and audit together');
create temp table ratio_finalized_before as select to_jsonb(t) as row from public.user_daily_tasks t;
create temp table ratio_balances_before as select id,credits from public.profiles;
select public._apply_daily_inactivity_penalty((now() at time zone 'Asia/Ho_Chi_Minh')::date+1);
select pg_temp.ratio_check(not exists(
    (select to_jsonb(t) from public.user_daily_tasks t except select row from ratio_finalized_before)
    union all (select row from ratio_finalized_before except select to_jsonb(t) from public.user_daily_tasks t)
), 'finalized rows unchanged on repeated evaluation');
select pg_temp.ratio_check(not exists(select 1 from public.profiles p join ratio_balances_before b using(id) where p.credits<>b.credits),
    'repeated evaluation does not deduct twice');
select public.schedule_daily_task_policy(6,(now() at time zone 'Asia/Ho_Chi_Minh')::date+9,'later change');
select pg_temp.ratio_check(not exists(select 1 from public.user_daily_tasks t
    where to_jsonb(t) not in (select row from ratio_finalized_before)), 'future schedule leaves history untouched');
select pg_temp.ratio_check(not exists(select 1 from public.user_daily_tasks
    where user_id in (md5('ratio:admin')::uuid,md5('ratio:moderator')::uuid)), 'admin moderator exempt from penalty');

-- Pausing and resuming use server completion, not a second fixed 3x formula.
delete from public.checkins;
update public.profiles set daily_missed_streak=7 where id=md5('ratio:owner')::uuid;
select public.pause_inactive_owner_campaigns();
select pg_temp.ratio_check((select status='paused' and paused_reason='owner_inactive'
    from public.campaigns where id=md5('ratio:own:1')::uuid), 'incomplete owner paused');
select pg_temp.ratio_error('select public.resume_campaign(md5(''ratio:own:1'')::uuid)', 'OWNER_INACTIVE');
insert into public.checkins(campaign_id,user_id,checkin_date)
select md5('ratio:other:' || i)::uuid,md5('ratio:owner')::uuid,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date from generate_series(1,3) i;
select pg_temp.ratio_check((select daily_missed_streak=0 from public.profiles where id=md5('ratio:owner')::uuid),
    'completion refresh resets streak');
select pg_temp.ratio_check(public.resume_campaign(md5('ratio:own:1')::uuid)->>'status'='open', 'completed owner can resume');

-- Old-day evaluation and signup-day exemption retain the old 3x policy.
select public._apply_daily_inactivity_penalty((now() at time zone 'Asia/Ho_Chi_Minh')::date-1);
select pg_temp.ratio_check((select tests_per_active_app=3 and required_tests=3
    from public.user_daily_tasks where user_id=md5('ratio:owner')::uuid
    and task_date=(now() at time zone 'Asia/Ho_Chi_Minh')::date-1), 'delayed evaluation uses old-day ratio');
select public._apply_daily_inactivity_penalty((now() at time zone 'Asia/Ho_Chi_Minh')::date);
select pg_temp.ratio_check(not exists(select 1 from public.user_daily_tasks where user_id=md5('ratio:fresh')::uuid
    and task_date=(now() at time zone 'Asia/Ho_Chi_Minh')::date and finalized_at is not null), 'signup day exempt');

-- Penalty percentage and rounding across every streak tier and small balances.
delete from public.checkins;
select pg_temp.ratio_counts(1,2);
do $$
declare
    d date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    uid uuid := md5('ratio:owner')::uuid;
    tier int; balance int; expected int; pct numeric;
begin
    for tier in 1..4 loop
        pct := case tier when 1 then .01 when 2 then .02 when 3 then .05 else .10 end;
        foreach balance in array array[0,1,100] loop
            delete from public.user_daily_tasks where user_id=uid;
            insert into public.user_daily_tasks(user_id,task_date,missed_streak,finalized_at)
            values(uid,d-1,tier-1,now());
            perform set_config('session_replication_role','replica',true);
            delete from public.checkins where user_id=uid;
            insert into public.checkins(campaign_id,user_id,checkin_date)
            values(md5('ratio:other:1')::uuid,uid,d+1);
            perform set_config('session_replication_role','origin',true);
            update public.profiles set credits=balance,daily_missed_streak=tier-1 where id=uid;
            perform public._apply_daily_inactivity_penalty(d+1);
            expected := least(balance,greatest(1,ceil(balance*pct*.5)::int));
            perform pg_temp.ratio_check((select penalty_credits=expected and missed_streak=tier
                from public.user_daily_tasks where user_id=uid and task_date=d+1),
                format('penalty tier %s balance %s',tier,balance));
            perform pg_temp.ratio_check((select credits=balance-expected from public.profiles where id=uid),
                'balance never below zero');
        end loop;
    end loop;
    -- Fully completed at the new ratio: no deduction and streak reset.
    delete from public.user_daily_tasks where user_id=uid;
    perform set_config('session_replication_role','replica',true);
    insert into public.checkins(campaign_id,user_id,checkin_date)
    values(md5('ratio:other:2')::uuid,uid,d+1);
    perform set_config('session_replication_role','origin',true);
    update public.profiles set credits=100,daily_missed_streak=6 where id=uid;
    perform public._apply_daily_inactivity_penalty(d+1);
    perform pg_temp.ratio_check((select met and penalty_credits=0 and missed_streak=0
        from public.user_daily_tasks where user_id=uid and task_date=d+1), 'new quota met has no penalty');
end;
$$;

select count(*)::integer as sql_assertions from ratio_assertions;
rollback;
