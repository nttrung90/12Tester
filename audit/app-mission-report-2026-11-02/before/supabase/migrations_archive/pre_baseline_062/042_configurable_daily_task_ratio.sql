-- ============================================================================
-- 042: Configurable daily test ratio, effective by Vietnam calendar date.
-- Apply this file ONCE in Supabase SQL Editor, as the database owner.
-- Baseline remains 3. Android RPC signatures and JSON are unchanged.
-- Do not rerun 036: its backfill resets streaks / grants grace days.
-- ============================================================================
begin;

-- Refuse to overwrite an unreviewed customization of the two existing functions.
-- Accepted body fingerprints match the shipped 036 / 042 bodies below.
-- Fingerprints normalize CRLF only, not SQL literals, comments or whitespace.
do $guard$
declare
    v_signature text;
    v_allowed text[];
    v_hash text;
begin
    for v_signature, v_allowed in
        select * from (values
            ('public.get_daily_task_status(uuid,date)', array['10688b04e236fc61d5301e85521a5965', '585c0bbdaff771afadeaad2881c90bff']),
            ('public._apply_daily_inactivity_penalty(date)', array['977a39219504fed09d2aba6dd6c1e059', '88a0d2866cd8b9708c5d3b9ed288b737'])
        ) expected(signature, allowed)
    loop
        select md5(btrim(replace(p.prosrc, E'\r\n', E'\n'), E' \t\r\n')) into v_hash
        from pg_proc p where p.oid = to_regprocedure(v_signature);
        if v_hash is null or not (v_hash = any(v_allowed)) then
            raise exception 'DAILY_TASK_FUNCTION_DRIFT: %', v_signature
                using hint = 'Run supabase/operations/daily_task_ratio_preflight.sql and review live definitions before deployment.';
        end if;
    end loop;
end;
$guard$;

create table if not exists public.daily_task_policies (
    id bigint generated always as identity primary key,
    effective_from date not null unique,
    tests_per_active_app integer not null
        check (tests_per_active_app between 1 and 30),
    operator_note text not null
        check (length(btrim(operator_note)) between 1 and 1000),
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp()
);

create table if not exists public.daily_task_policy_changes (
    id bigint generated always as identity primary key,
    policy_id bigint not null,
    operation text not null check (operation in ('INSERT', 'UPDATE', 'DELETE')),
    old_row jsonb,
    new_row jsonb,
    operator_note text not null,
    session_role text not null default session_user,
    invoked_role text,
    changed_at timestamptz not null default clock_timestamp()
);

alter table public.daily_task_policies enable row level security;
alter table public.daily_task_policy_changes enable row level security;
revoke all on public.daily_task_policies, public.daily_task_policy_changes
    from public, anon, authenticated, service_role;
revoke all on sequence public.daily_task_policies_id_seq,
    public.daily_task_policy_changes_id_seq
    from public, anon, authenticated, service_role;

-- On reruns, do not even attempt INSERT through the immutable-date trigger.
-- Missing baseline on an already-installed schema is corruption, not a reason
-- to silently reseed / reinterpret history.
do $$
begin
    if not exists (
        select 1 from public.daily_task_policies where effective_from = date '-infinity'
    ) then
        if exists (
            select 1 from pg_trigger
            where tgrelid = 'public.daily_task_policies'::regclass
              and tgname = 'trg_guard_daily_task_policy'
              and not tgisinternal
        ) then
            raise exception 'DAILY_TASK_POLICY_BASELINE_MISSING';
        end if;
        insert into public.daily_task_policies (
            effective_from, tests_per_active_app, operator_note
        ) values (date '-infinity', 3, 'Migration 042: preserve existing 3:1 policy');
        insert into public.daily_task_policy_changes (
            policy_id, operation, new_row, operator_note
        )
        select p.id, 'INSERT', to_jsonb(p), p.operator_note
        from public.daily_task_policies p where p.effective_from = date '-infinity';
    end if;
end;
$$;

create or replace function public.guard_daily_task_policy()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
    -- Wall clock prevents a transaction opened yesterday from editing today's
    -- effective policy. Evaluate after any row-lock wait, not at transaction start.
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if tg_op in ('UPDATE', 'DELETE') and old.effective_from <= v_today then
        raise exception 'DAILY_TASK_POLICY_IMMUTABLE';
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    if not isfinite(new.effective_from) or new.effective_from <= v_today then
        raise exception 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED';
    end if;
    if tg_op = 'UPDATE' and (
        new.id is distinct from old.id
        or new.effective_from is distinct from old.effective_from
        or new.created_at is distinct from old.created_at
    ) then
        raise exception 'DAILY_TASK_POLICY_IDENTITY_IMMUTABLE';
    end if;
    if tg_when = 'BEFORE' then
        new.operator_note := btrim(new.operator_note);
        new.updated_at := clock_timestamp();
    end if;
    return new;
end;
$$;

create or replace function public.audit_daily_task_policy()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.daily_task_policy_changes (
        policy_id, operation, old_row, new_row, operator_note, invoked_role
    ) values (
        case when tg_op = 'DELETE' then old.id else new.id end,
        tg_op,
        case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) end,
        case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) end,
        case when tg_op = 'DELETE' then old.operator_note else new.operator_note end,
        nullif(current_setting('role', true), 'none')
    );
    return null;
end;
$$;

create or replace function public.reject_daily_task_policy_history_change()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    raise exception 'DAILY_TASK_POLICY_HISTORY_IMMUTABLE';
end;
$$;

drop trigger if exists trg_guard_daily_task_policy on public.daily_task_policies;
create trigger trg_guard_daily_task_policy
    before insert or update or delete on public.daily_task_policies
    for each row execute function public.guard_daily_task_policy();
drop trigger if exists trg_audit_daily_task_policy on public.daily_task_policies;
create trigger trg_audit_daily_task_policy
    after insert or update or delete on public.daily_task_policies
    for each row execute function public.audit_daily_task_policy();
-- Recheck at transaction end as well: an operation begun before midnight must
-- not commit a change to a policy that has become effective while it waited.
drop trigger if exists trg_daily_task_policy_commit on public.daily_task_policies;
create constraint trigger trg_daily_task_policy_commit
    after insert or update or delete on public.daily_task_policies
    deferrable initially deferred
    for each row execute function public.guard_daily_task_policy();
drop trigger if exists trg_no_truncate_daily_task_policies on public.daily_task_policies;
create trigger trg_no_truncate_daily_task_policies
    before truncate on public.daily_task_policies
    for each statement execute function public.reject_daily_task_policy_history_change();
drop trigger if exists trg_no_change_daily_task_policy_history on public.daily_task_policy_changes;
create trigger trg_no_change_daily_task_policy_history
    before update or delete on public.daily_task_policy_changes
    for each row execute function public.reject_daily_task_policy_history_change();
drop trigger if exists trg_no_truncate_daily_task_policy_history on public.daily_task_policy_changes;
create trigger trg_no_truncate_daily_task_policy_history
    before truncate on public.daily_task_policy_changes
    for each statement execute function public.reject_daily_task_policy_history_change();

create or replace function public.get_daily_task_policy(p_date date)
returns public.daily_task_policies
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_policy public.daily_task_policies;
begin
    if p_date is null or not isfinite(p_date) then
        raise exception 'DAILY_TASK_POLICY_INVALID_DATE';
    end if;
    if not exists (
        select 1 from public.daily_task_policies where effective_from = date '-infinity'
    ) then
        raise exception 'DAILY_TASK_POLICY_BASELINE_MISSING';
    end if;
    select p.* into v_policy
    from public.daily_task_policies p
    where p.effective_from <= p_date
    order by p.effective_from desc limit 1;
    if not found then
        raise exception 'DAILY_TASK_POLICY_NOT_FOUND';
    end if;
    return v_policy;
end;
$$;

-- Invoker privileges deliberately: only the database owner / SQL Editor can
-- operate schedules. An app admin JWT and a service-role JWT are insufficient.
create or replace function public.schedule_daily_task_policy(
    p_tests_per_active_app integer,
    p_effective_from date,
    p_operator_note text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
    v_policy public.daily_task_policies;
begin
    perform pg_advisory_xact_lock(hashtextextended('daily_task_policy_schedule', 0));
    if p_tests_per_active_app is null or p_tests_per_active_app not between 1 and 30 then
        raise exception 'DAILY_TASK_POLICY_INVALID_RATIO';
    end if;
    if p_effective_from is null or not isfinite(p_effective_from)
       or p_effective_from <= (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date then
        raise exception 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED';
    end if;
    if p_operator_note is null or length(btrim(p_operator_note)) not between 1 and 1000 then
        raise exception 'DAILY_TASK_POLICY_NOTE_REQUIRED';
    end if;
    -- UPDATE first: INSERT ... ON CONFLICT would execute a BEFORE INSERT
    -- trigger before discovering a conflict. Both paths remain fully audited.
    update public.daily_task_policies
    set tests_per_active_app = p_tests_per_active_app, operator_note = btrim(p_operator_note)
    where effective_from = p_effective_from
    returning * into v_policy;
    if not found then
        insert into public.daily_task_policies (
            effective_from, tests_per_active_app, operator_note
        ) values (p_effective_from, p_tests_per_active_app, btrim(p_operator_note))
        on conflict (effective_from) do update
        set tests_per_active_app = excluded.tests_per_active_app,
            operator_note = excluded.operator_note
        returning * into v_policy;
    end if;
    return to_jsonb(v_policy);
end;
$$;

create or replace function public.cancel_daily_task_policy(
    p_effective_from date,
    p_operator_note text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
    v_policy public.daily_task_policies;
begin
    perform pg_advisory_xact_lock(hashtextextended('daily_task_policy_schedule', 0));
    if p_effective_from is null or not isfinite(p_effective_from)
       or p_effective_from <= (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date then
        raise exception 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED';
    end if;
    if p_operator_note is null or length(btrim(p_operator_note)) not between 1 and 1000 then
        raise exception 'DAILY_TASK_POLICY_NOTE_REQUIRED';
    end if;
    -- Preserve the cancellation reason in the DELETE audit, with a preceding
    -- UPDATE audit retaining the original note. Both changes are atomic.
    update public.daily_task_policies set operator_note = btrim(p_operator_note)
    where effective_from = p_effective_from returning * into v_policy;
    if not found then
        raise exception 'DAILY_TASK_POLICY_NOT_FOUND';
    end if;
    delete from public.daily_task_policies where id = v_policy.id;
    return to_jsonb(v_policy);
end;
$$;

revoke execute on function public.guard_daily_task_policy(),
    public.audit_daily_task_policy(), public.reject_daily_task_policy_history_change(),
    public.schedule_daily_task_policy(integer, date, text),
    public.cancel_daily_task_policy(date, text), public.get_daily_task_policy(date)
    from public, anon, authenticated, service_role;
grant execute on function public.get_daily_task_policy(date) to service_role;

alter table public.user_daily_tasks
    add column if not exists policy_id bigint references public.daily_task_policies(id),
    add column if not exists tests_per_active_app integer
        check (tests_per_active_app between 1 and 30);
comment on column public.user_daily_tasks.policy_id is
    'Policy used at finalization; NULL for legacy / unfinalized rows. No historical backfill.';
comment on column public.user_daily_tasks.tests_per_active_app is
    'Ratio used at finalization. Legacy rows remain unchanged.';

-- Existing helper / finalizer definitions below preserve the 036 behavior except
-- for selecting the dated ratio and writing its audit reference.


create or replace function public.get_daily_task_status(
    p_user_id uuid,
    p_date date
)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_policy public.daily_task_policies;
    v_role text;
    v_last_login date;
    v_streak int := 0;
    v_exempt boolean := false;
    v_k int := 0;
    v_a int := 0;
    v_d int := 0;
    v_required int := 0;
    v_effective int := 0;
    v_logged_in boolean := false;
    v_met boolean := false;
    v_next_pct int := 1;
    v_mode text;
    v_day_start timestamptz;
    v_next_day_start timestamptz;
    v_window_start timestamptz;
begin
    if p_user_id is null or p_date is null then
        return null;
    end if;

    select lower(p.role), p.last_login_date, p.daily_missed_streak
    into v_role, v_last_login, v_streak
    from public.profiles p
    where p.id = p_user_id;

    if not found then
        return null;
    end if;

    v_streak := coalesce(v_streak, 0);
    v_exempt := coalesce(v_role in ('admin', 'moderator'), false);

    v_day_start := (p_date::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_next_day_start := ((p_date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_window_start := ((p_date - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh');

    -- K: app của user đang open, hoặc paused do chính sách vắng mặt.
    -- App phải được tạo trước 00:00 VN của ngày chấm để bắt đầu tính từ ngày mai.
    select count(*) into v_k
    from public.campaigns c
    where c.owner_id = p_user_id
      and (
          c.status = 'open'
          or (c.status = 'paused' and c.paused_reason = 'owner_inactive')
      )
      and c.created_at < v_day_start
      and c.created_at > v_window_start;

    -- A: app open không thuộc sở hữu user, đã tồn tại trong ngày chấm.
    select count(*) into v_a
    from public.campaigns c
    where c.status = 'open'
      and c.owner_id <> p_user_id
      and c.created_at < v_next_day_start
      and c.created_at > v_window_start;

    -- D: số app khác nhau user đã test trong ngày.
    select count(distinct campaign_id) into v_d
    from (
        select ci.campaign_id
        from public.checkins ci
        join public.campaigns c on c.id = ci.campaign_id
        where ci.user_id = p_user_id
          and ci.checkin_date = p_date
          and c.owner_id <> p_user_id
          -- Checkin sinh ra từ report đã được đếm ở nhánh tester_reports.
          -- Bỏ qua để report cũ được duyệt hôm nay không tính thêm một lượt.
          and (
              ci.note is null
              or (
                  ci.note not like 'Đã kiểm thử%'
                  and ci.note not like 'Đã nộp kết quả%'
                  and ci.note not like '%tạm tính%'
                  and ci.note not like 'Tự động duyệt%'
                  and ci.note not like '%Tự động duyệt%'
                  and ci.note not like '%chủ app duyệt%'
              )
          )

        union

        select tr.campaign_id
        from public.tester_reports tr
        join public.campaigns c on c.id = tr.campaign_id
        where tr.user_id = p_user_id
          and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = p_date
          and tr.status in ('pending', 'approved')
          and c.owner_id <> p_user_id
    ) t;

    -- Login lấy từ lịch sử theo ngày (live/finalized row), fallback last_login_date.
    select (v_last_login = p_date) or exists (
        select 1
        from public.user_daily_tasks t
        where t.user_id = p_user_id
          and t.task_date = p_date
          and t.logged_in = true
    ) into v_logged_in;

    v_policy := public.get_daily_task_policy(p_date);
    v_required := v_policy.tests_per_active_app * v_k;
    v_effective := least(v_required, v_a);
    v_mode := case when v_k > 0 then 'owner' else 'member' end;

    if v_exempt then
        v_met := true;
    elsif v_k > 0 then
        v_met := v_d >= v_effective;
    else
        v_met := v_logged_in or v_d > 0;
    end if;

    v_next_pct := case
        when v_streak <= 0 then 1
        when v_streak = 1 then 2
        when v_streak = 2 then 5
        else 10
    end;

    return jsonb_build_object(
        'status_date', to_char(p_date, 'YYYY-MM-DD'),
        'mode', v_mode,
        'active_app_count', v_k,
        'required_tests', v_required,
        'available_apps', v_a,
        'effective_required_tests', v_effective,
        'completed_tests', v_d,
        'logged_in_today', v_logged_in,
        'is_penalty_exempt', v_exempt,
        'missed_streak', v_streak,
        'next_penalty_percent', v_next_pct,
        'met', v_met
    );
end;
$$;

create or replace function public._apply_daily_inactivity_penalty(p_eval date)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_policy public.daily_task_policies;
    r record;
    v_status jsonb;
    v_met boolean;
    v_mode text;
    v_required int;
    v_effective int;
    v_completed int;
    v_logged_in boolean;
    v_prev_streak int;
    v_new_streak int;
    v_pct numeric;
    v_ratio numeric;
    v_deduction int;
    v_new_balance int;
    v_rows int;
    v_note text;
begin
    if p_eval is null then
        return;
    end if;

    -- Chống chạy song song; unique key vẫn là lớp bảo vệ cuối.
    perform pg_advisory_xact_lock(hashtextextended('daily_inactivity_penalty', 0));
    v_policy := public.get_daily_task_policy(p_eval);

    for r in
        select p.id, p.credits, p.daily_missed_streak
        from public.profiles p
        where p.created_at < (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh')
          and lower(p.role) not in ('admin', 'moderator')
        for update of p
    loop
        if exists (
            select 1
            from public.user_daily_tasks t
            where t.user_id = r.id
              and t.task_date = p_eval
              and t.finalized_at is not null
        ) then
            continue;
        end if;

        v_status := public.get_daily_task_status(r.id, p_eval);
        if v_status is null then
            continue;
        end if;

        v_met := coalesce((v_status ->> 'met')::boolean, false);
        v_mode := coalesce(v_status ->> 'mode', 'member');
        v_required := coalesce((v_status ->> 'required_tests')::int, 0);
        v_effective := coalesce((v_status ->> 'effective_required_tests')::int, 0);
        v_completed := coalesce((v_status ->> 'completed_tests')::int, 0);
        v_logged_in := coalesce((v_status ->> 'logged_in_today')::boolean, false);

        -- Lấy streak từ row finalized gần nhất, không dùng profile hiện tại:
        -- user có thể đã hoàn thành hôm nay trước 00:05 và profile đã reset 0.
        select coalesce(
            (select t.missed_streak
             from public.user_daily_tasks t
             where t.user_id = r.id
               and t.task_date < p_eval
               and t.finalized_at is not null
             order by t.task_date desc
             limit 1),
            r.daily_missed_streak,
            0
        ) into v_prev_streak;

        v_new_streak := case when v_met then 0 else v_prev_streak + 1 end;

        if v_met then
            v_deduction := 0;
        else
            v_pct := case
                when v_new_streak = 1 then 0.01
                when v_new_streak = 2 then 0.02
                when v_new_streak = 3 then 0.05
                else 0.10
            end;

            if v_mode = 'owner' and v_effective > 0 then
                v_ratio := greatest(
                    0,
                    least(
                        1,
                        (v_effective - v_completed)::numeric / v_effective::numeric
                    )
                );
            elsif v_mode = 'member' then
                v_ratio := case when v_logged_in then 0 else 1 end;
            else
                v_ratio := 0;
            end if;

            if v_ratio <= 0 then
                v_deduction := 0;
            else
                v_deduction := least(
                    r.credits,
                    greatest(1, ceil(r.credits * v_pct * v_ratio)::int)
                );
            end if;
        end if;

        v_new_balance := r.credits - v_deduction;

        insert into public.user_daily_tasks as t (
            user_id, task_date, mode, active_app_count, required_tests,
            effective_required_tests, available_apps, completed_tests,
            logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
            policy_id, tests_per_active_app
        ) values (
            r.id, p_eval, v_mode,
            coalesce((v_status ->> 'active_app_count')::int, 0),
            v_required,
            v_effective,
            coalesce((v_status ->> 'available_apps')::int, 0),
            v_completed,
            v_logged_in,
            false,
            v_met,
            v_new_streak,
            v_deduction,
            now(),
            v_policy.id,
            v_policy.tests_per_active_app
        )
        on conflict (user_id, task_date) do update
        set mode = excluded.mode,
            active_app_count = excluded.active_app_count,
            required_tests = excluded.required_tests,
            effective_required_tests = excluded.effective_required_tests,
            available_apps = excluded.available_apps,
            completed_tests = excluded.completed_tests,
            logged_in = excluded.logged_in,
            is_exempt = excluded.is_exempt,
            met = excluded.met,
            missed_streak = excluded.missed_streak,
            penalty_credits = excluded.penalty_credits,
            finalized_at = now(),
            policy_id = excluded.policy_id,
            tests_per_active_app = excluded.tests_per_active_app
        where t.finalized_at is null;

        get diagnostics v_rows = row_count;
        if v_rows = 0 then
            continue;
        end if;

        update public.profiles
        set credits = v_new_balance,
            daily_missed_streak = v_new_streak
        where id = r.id;

        if v_deduction > 0 then
            if v_mode = 'owner' then
                v_note := format(
                    'Không đạt chỉ tiêu ngày %s: %s/%s app (thiếu %s). Trừ %s Credit.',
                    to_char(p_eval, 'DD/MM/YYYY'),
                    v_completed,
                    v_effective,
                    greatest(0, v_effective - v_completed),
                    v_deduction
                );
            else
                v_note := format(
                    'Không đăng nhập ngày %s. Trừ %s Credit.',
                    to_char(p_eval, 'DD/MM/YYYY'),
                    v_deduction
                );
            end if;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (r.id, -v_deduction, 'inactivity_penalty', v_note, v_new_balance);
        end if;

        if not v_met then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                r.id,
                'Không đạt nhiệm vụ ngày',
                case
                    when v_deduction = 0 and v_mode = 'owner' then format(
                        'Ngày %s bạn mới test %s/%s app. Số dư hiện tại là 0 nên không trừ Credit, nhưng chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_completed,
                        v_effective,
                        v_new_streak
                    )
                    when v_deduction = 0 then format(
                        'Ngày %s bạn không mở app. Số dư hiện tại là 0 nên không trừ Credit, nhưng chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_new_streak
                    )
                    when v_mode = 'owner' then format(
                        'Ngày %s bạn mới test %s/%s app. Bạn bị trừ %s Credit và chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_completed,
                        v_effective,
                        v_deduction,
                        v_new_streak
                    )
                    else format(
                        'Ngày %s bạn không mở app. Bạn bị trừ %s Credit và chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_deduction,
                        v_new_streak
                    )
                end,
                'credit',
                jsonb_build_object(
                    'task_date', to_char(p_eval, 'YYYY-MM-DD'),
                    'penalty_credits', v_deduction
                )
            );
        end if;

        -- Nếu hôm nay đã đạt, reset streak ngay để auto-pause không pause oan.
        perform public.refresh_daily_completion(r.id, v_today);
    end loop;
end;
$$;

revoke execute on function public.get_daily_task_status(uuid, date),
    public._apply_daily_inactivity_penalty(date) from public, anon, authenticated;
grant execute on function public.get_daily_task_status(uuid, date),
    public._apply_daily_inactivity_penalty(date) to service_role;

notify pgrst, 'reload schema';
commit;
