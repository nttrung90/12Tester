-- READ ONLY. Run in the intended Supabase project before applying 042.
-- Confirm the Dashboard project is com.ntt.crosstest before running migration.
-- Save result grids privately for rollback / comparison; no tokens are needed.
select current_database() as database_name, current_user as database_role,
    clock_timestamp() as checked_at,
    (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date as vietnam_date;

with expected(signature, baseline_hash, installed_hash) as (values
    ('public.get_daily_task_status(uuid,date)',
        '10688b04e236fc61d5301e85521a5965', '585c0bbdaff771afadeaad2881c90bff'),
    ('public._apply_daily_inactivity_penalty(date)',
        '977a39219504fed09d2aba6dd6c1e059', '88a0d2866cd8b9708c5d3b9ed288b737')
), actual as (
    select e.*, p.oid,
        md5(btrim(replace(p.prosrc, E'\r\n', E'\n'), E' \t\r\n')) as live_hash
    from expected e left join pg_proc p on p.oid=to_regprocedure(e.signature)
)
select signature, live_hash,
    case when live_hash=baseline_hash then 'BASELINE_036_OK'
         when live_hash=installed_hash then 'ALREADY_042_OK'
         when oid is null then 'STOP_MISSING_FUNCTION'
         else 'STOP_REVIEW_CUSTOM_DEFINITION' end as deployment_check
from actual;

select n.nspname as schema_name, p.proname, p.proacl,
    pg_get_functiondef(p.oid) as saved_definition
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where p.oid in (
    to_regprocedure('public.get_daily_task_status(uuid,date)'),
    to_regprocedure('public.get_my_daily_task_status()'),
    to_regprocedure('public.get_my_dashboard(boolean)'),
    to_regprocedure('public._apply_daily_inactivity_penalty(date)'),
    to_regprocedure('public.apply_daily_inactivity_penalty()'),
    to_regprocedure('public.refresh_daily_completion(uuid,date)'),
    to_regprocedure('public.pause_inactive_owner_campaigns()'),
    to_regprocedure('public.resume_campaign(uuid)')
) order by p.proname;

select table_name, column_name, data_type, is_nullable
from information_schema.columns
where table_schema='public'
  and table_name in ('user_daily_tasks','daily_task_policies','daily_task_policy_changes')
order by table_name, ordinal_position;

-- pg_cron is optional. This block only reads its existing job configuration.
do $$
declare v_job record;
begin
    if to_regclass('cron.job') is null then
        raise notice 'pg_cron unavailable: verify the existing daily penalty execution path.';
    else
        for v_job in execute
            'select jobid, jobname, schedule, command, active from cron.job where jobname = ''daily_inactivity_penalty_job'''
        loop
            raise notice 'Existing penalty job: %', row_to_json(v_job);
        end loop;
    end if;
end;
$$;
