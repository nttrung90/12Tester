-- ==============================================================================
-- 12 Tester - Migration 058: Admin Daily Task Policy Configuration
-- ==============================================================================
begin;

-- 1. Preflight check: Bắt buộc đã có schema và các hàm từ migration 042
do $guard$
begin
    if to_regclass('public.daily_task_policies') is null
       or to_regclass('public.daily_task_policy_changes') is null
       or to_regprocedure('public.schedule_daily_task_policy(integer,date,text)') is null
       or to_regprocedure('public.cancel_daily_task_policy(date,text)') is null
       or to_regprocedure('public.get_daily_task_policy(date)') is null
    then
        raise exception 'DAILY_TASK_POLICY_BASE_SCHEMA_MISSING'
            using hint = 'Migration 042 must be applied before migration 058.';
    end if;

    if not exists (
        select 1 from public.daily_task_policies where effective_from = date '-infinity'
    ) then
        raise exception 'DAILY_TASK_POLICY_BASELINE_MISSING'
            using hint = 'Baseline policy (-infinity) is missing from public.daily_task_policies.';
    end if;
end;
$guard$;

-- 2. Nâng cấp bảng audit để lưu actor_id từ auth.uid()
alter table public.daily_task_policy_changes
    add column if not exists actor_id uuid;

-- 3. Cập nhật trigger audit để ghi nhận auth.uid() của phiên gọi
create or replace function public.audit_daily_task_policy()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.daily_task_policy_changes (
        policy_id, operation, old_row, new_row, operator_note, invoked_role, actor_id
    ) values (
        case when tg_op = 'DELETE' then old.id else new.id end,
        tg_op,
        case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) end,
        case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) end,
        case when tg_op = 'DELETE' then old.operator_note else new.operator_note end,
        nullif(current_setting('role', true), 'none'),
        auth.uid()
    );
    return null;
end;
$$;

-- 4. Helper nội bộ: Tính revision băm phản ánh toàn bộ tập policy
create or replace function public._get_daily_task_policy_revision()
returns text
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        md5(string_agg(
            p.id::text || ':' ||
            p.effective_from::text || ':' ||
            p.tests_per_active_app::text || ':' ||
            to_char(p.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || ':' ||
            md5(p.operator_note),
            ',' order by p.id
        )),
        'empty'
    )
    from public.daily_task_policies p;
$$;

revoke execute on function public._get_daily_task_policy_revision() from public, anon, authenticated;
grant execute on function public._get_daily_task_policy_revision() to service_role;

-- 5. Helper nội bộ: Chuẩn hóa row policy thành JSON
create or replace function public._format_daily_task_policy_json(p public.daily_task_policies)
returns jsonb
language sql
immutable
security definer
set search_path = ''
as $$
    select jsonb_build_object(
        'id', p.id,
        'tests_per_active_app', p.tests_per_active_app,
        'effective_from', case when not isfinite(p.effective_from) then null else to_char(p.effective_from, 'YYYY-MM-DD') end,
        'is_baseline', not isfinite(p.effective_from),
        'operator_note', p.operator_note,
        'updated_at', to_char(p.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
    );
$$;

revoke execute on function public._format_daily_task_policy_json(public.daily_task_policies) from public, anon, authenticated;
grant execute on function public._format_daily_task_policy_json(public.daily_task_policies) to service_role;

-- 6. Helper nội bộ: Kiểm tra quyền admin và trạng thái tài khoản
create or replace function public._assert_daily_task_admin()
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
    v_role text;
    v_locked boolean;
begin
    if v_uid is null then
        raise exception 'UNAUTHORIZED: Authentication required';
    end if;

    select p.role, coalesce(p.is_locked, false)
    into v_role, v_locked
    from public.profiles p
    where p.id = v_uid;

    if v_role is null or lower(v_role) != 'admin' or v_locked = true then
        raise exception 'FORBIDDEN: Admin access required';
    end if;

    return v_uid;
end;
$$;

revoke execute on function public._assert_daily_task_admin() from public, anon, authenticated;
grant execute on function public._assert_daily_task_admin() to service_role;

-- 7. RPC Admin: Đọc cấu hình nhiệm vụ ngày (chính sách hiện tại, lịch tương lai, revision)
create or replace function public.admin_get_daily_task_config()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_today date;
    v_active_policy public.daily_task_policies;
    v_future_json jsonb;
    v_revision text;
    v_has_more boolean := false;
    v_next_date date := null;
    v_next_id bigint := null;
    v_total_future integer := 0;
begin
    perform public._assert_daily_task_admin();
    v_today := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;

    select p.* into v_active_policy
    from public.daily_task_policies p
    where p.effective_from <= v_today
    order by p.effective_from desc
    limit 1;

    with candidates as (
        select p as policy_row, count(*) over() as found_count
        from public.daily_task_policies p
        where p.effective_from > v_today
        order by p.effective_from asc, p.id asc
        limit 51
    ),
    page_items as (
        select policy_row, found_count
        from candidates
        order by (policy_row).effective_from asc, (policy_row).id asc
        limit 50
    ),
    last_item as (
        select (policy_row).effective_from as effective_from, (policy_row).id as id, found_count
        from page_items
        order by (policy_row).effective_from desc, (policy_row).id desc
        limit 1
    )
    select
        coalesce(jsonb_agg(public._format_daily_task_policy_json(p.policy_row) order by (p.policy_row).effective_from asc, (p.policy_row).id asc), '[]'::jsonb),
        coalesce((select found_count > 50 from last_item), false),
        (select case when found_count > 50 then effective_from else null end from last_item),
        (select case when found_count > 50 then id else null end from last_item)
    into v_future_json, v_has_more, v_next_date, v_next_id
    from page_items p;

    select count(*) into v_total_future
    from public.daily_task_policies
    where effective_from > v_today;

    v_revision := public._get_daily_task_policy_revision();

    return jsonb_build_object(
        'today', to_char(v_today, 'YYYY-MM-DD'),
        'timezone', 'Asia/Ho_Chi_Minh',
        'revision', v_revision,
        'active_policy', case when v_active_policy.id is not null then public._format_daily_task_policy_json(v_active_policy) else null end,
        'future_policies', coalesce(v_future_json, '[]'::jsonb),
        'has_more', coalesce(v_has_more, false),
        'next_cursor_date', case when v_next_date is not null then to_char(v_next_date, 'YYYY-MM-DD') else null end,
        'next_cursor_id', v_next_id,
        'total_future_policies', v_total_future
    );
end;
$$;

-- 8. RPC Admin: Lên lịch hoặc cập nhật tỷ lệ nhiệm vụ ngày
create or replace function public.admin_schedule_daily_task_policy(
    p_tests_per_active_app integer,
    p_effective_from date,
    p_operator_note text,
    p_expected_revision text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_today date;
    v_current_rev text;
    v_saved_row jsonb;
    v_new_config jsonb;
begin
    perform public._assert_daily_task_admin();

    -- Lấy khóa advisory trước khi kiểm tra revision và ngày
    perform pg_advisory_xact_lock(hashtextextended('daily_task_policy_schedule', 0));

    if p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'DAILY_TASK_POLICY_REVISION_REQUIRED';
    end if;

    v_current_rev := public._get_daily_task_policy_revision();
    if v_current_rev is distinct from p_expected_revision then
        raise exception 'DAILY_TASK_POLICY_CONFLICT';
    end if;

    v_today := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    if p_effective_from is null or not isfinite(p_effective_from) or p_effective_from <= v_today then
        raise exception 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED';
    end if;

    if p_tests_per_active_app is null or p_tests_per_active_app not between 1 and 30 then
        raise exception 'DAILY_TASK_POLICY_INVALID_RATIO';
    end if;

    if p_operator_note is null or length(btrim(p_operator_note)) not between 1 and 1000 then
        raise exception 'DAILY_TASK_POLICY_NOTE_REQUIRED';
    end if;

    -- Gọi hàm nội bộ kế thừa từ 042 (chạy dưới quyền security definer của wrapper)
    v_saved_row := public.schedule_daily_task_policy(
        p_tests_per_active_app,
        p_effective_from,
        p_operator_note
    );

    v_new_config := public.admin_get_daily_task_config();

    return jsonb_build_object(
        'success', true,
        'policy', v_saved_row,
        'config', v_new_config,
        'revision', v_new_config ->> 'revision'
    );
end;
$$;

-- 9. RPC Admin: Hủy một lịch thay đổi chưa áp dụng
create or replace function public.admin_cancel_daily_task_policy(
    p_effective_from date,
    p_operator_note text,
    p_expected_revision text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_today date;
    v_current_rev text;
    v_cancelled_row jsonb;
    v_new_config jsonb;
begin
    perform public._assert_daily_task_admin();

    -- Lấy khóa advisory trước khi kiểm tra revision và ngày
    perform pg_advisory_xact_lock(hashtextextended('daily_task_policy_schedule', 0));

    if p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'DAILY_TASK_POLICY_REVISION_REQUIRED';
    end if;

    v_current_rev := public._get_daily_task_policy_revision();
    if v_current_rev is distinct from p_expected_revision then
        raise exception 'DAILY_TASK_POLICY_CONFLICT';
    end if;

    v_today := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    if p_effective_from is null or not isfinite(p_effective_from) or p_effective_from <= v_today then
        raise exception 'DAILY_TASK_POLICY_FUTURE_DATE_REQUIRED';
    end if;

    if p_operator_note is null or length(btrim(p_operator_note)) not between 1 and 1000 then
        raise exception 'DAILY_TASK_POLICY_NOTE_REQUIRED';
    end if;

    -- Gọi hàm nội bộ kế thừa từ 042
    v_cancelled_row := public.cancel_daily_task_policy(
        p_effective_from,
        p_operator_note
    );

    v_new_config := public.admin_get_daily_task_config();

    return jsonb_build_object(
        'success', true,
        'cancelled_policy', v_cancelled_row,
        'config', v_new_config,
        'revision', v_new_config ->> 'revision'
    );
end;
$$;

-- 10. RPC Admin: Danh sách lịch tương lai có phân trang
create or replace function public.admin_list_daily_task_policies(
    p_limit integer default 50,
    p_cursor_date date default null,
    p_cursor_id bigint default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_today date;
    v_limit integer;
    v_items jsonb;
    v_has_more boolean := false;
    v_next_date date := null;
    v_next_id bigint := null;
begin
    perform public._assert_daily_task_admin();
    v_today := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_limit := least(greatest(coalesce(p_limit, 50), 1), 100);

    with candidates as (
        select p as policy_row, count(*) over() as total_found
        from public.daily_task_policies p
        where p.effective_from > v_today
          and (
              p_cursor_date is null
              or (p.effective_from, p.id) > (p_cursor_date, coalesce(p_cursor_id, 0))
          )
        order by p.effective_from asc, p.id asc
        limit v_limit + 1
    ),
    page_items as (
        select policy_row, total_found
        from candidates
        order by (policy_row).effective_from asc, (policy_row).id asc
        limit v_limit
    ),
    last_item as (
        select (policy_row).effective_from as effective_from, (policy_row).id as id, total_found
        from page_items
        order by (policy_row).effective_from desc, (policy_row).id desc
        limit 1
    )
    select
        coalesce(jsonb_agg(public._format_daily_task_policy_json(p.policy_row) order by (p.policy_row).effective_from asc, (p.policy_row).id asc), '[]'::jsonb),
        coalesce((select total_found > v_limit from last_item), false),
        (select case when total_found > v_limit then effective_from else null end from last_item),
        (select case when total_found > v_limit then id else null end from last_item)
    into v_items, v_has_more, v_next_date, v_next_id
    from page_items p;

    return jsonb_build_object(
        'items', coalesce(v_items, '[]'::jsonb),
        'has_more', coalesce(v_has_more, false),
        'next_cursor_date', case when v_next_date is not null then to_char(v_next_date, 'YYYY-MM-DD') else null end,
        'next_cursor_id', v_next_id
    );
end;
$$;

-- 11. RPC Admin: Xem lịch sử thay đổi có phân trang kèm profile người thực hiện
create or replace function public.admin_get_daily_task_policy_history(
    p_limit integer default 20,
    p_cursor_id bigint default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_limit integer;
    v_items jsonb;
    v_has_more boolean := false;
    v_next_id bigint := null;
begin
    perform public._assert_daily_task_admin();
    v_limit := least(greatest(coalesce(p_limit, 20), 1), 100);

    with candidates as (
        select c.*
        from public.daily_task_policy_changes c
        where (p_cursor_id is null or c.id < p_cursor_id)
        order by c.id desc
        limit v_limit + 1
    ),
    counted as (
        select candidates.*, count(*) over() as total_found
        from candidates
    ),
    page_items as (
        select *
        from counted
        order by id desc
        limit v_limit
    ),
    last_item as (
        select id, total_found
        from page_items
        order by id asc
        limit 1
    )
    select
        coalesce(jsonb_agg(jsonb_build_object(
            'id', h.id,
            'policy_id', h.policy_id,
            'operation', h.operation,
            'old_row', h.old_row,
            'new_row', h.new_row,
            'operator_note', h.operator_note,
            'session_role', h.session_role,
            'invoked_role', h.invoked_role,
            'actor_id', h.actor_id,
            'actor_name', prof.display_name,
            'actor_email', prof.email,
            'changed_at', to_char(h.changed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        ) order by h.id desc), '[]'::jsonb),
        coalesce((select total_found > v_limit from last_item), false),
        (select case when total_found > v_limit then id else null end from last_item)
    into v_items, v_has_more, v_next_id
    from page_items h
    left join public.profiles prof on prof.id = h.actor_id;

    return jsonb_build_object(
        'items', coalesce(v_items, '[]'::jsonb),
        'has_more', coalesce(v_has_more, false),
        'next_cursor_id', v_next_id
    );
end;
$$;

-- 12. Phân quyền chặt chẽ: chỉ cấp authenticated và service_role cho các RPC wrapper
revoke execute on function public.admin_get_daily_task_config() from public, anon;
grant execute on function public.admin_get_daily_task_config() to authenticated, service_role;

revoke execute on function public.admin_schedule_daily_task_policy(integer, date, text, text) from public, anon;
grant execute on function public.admin_schedule_daily_task_policy(integer, date, text, text) to authenticated, service_role;

revoke execute on function public.admin_cancel_daily_task_policy(date, text, text) from public, anon;
grant execute on function public.admin_cancel_daily_task_policy(date, text, text) to authenticated, service_role;

revoke execute on function public.admin_list_daily_task_policies(integer, date, bigint) from public, anon;
grant execute on function public.admin_list_daily_task_policies(integer, date, bigint) to authenticated, service_role;

revoke execute on function public.admin_get_daily_task_policy_history(integer, bigint) from public, anon;
grant execute on function public.admin_get_daily_task_policy_history(integer, bigint) to authenticated, service_role;

notify pgrst, 'reload schema';

commit;
