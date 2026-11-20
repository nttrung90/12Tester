-- Migration 060: Củng cố phân trang, transaction và metadata cấu hình nhiệm vụ ngày
-- Khắc phục các vấn đề:
-- 1. admin_list_daily_task_policies: dùng CTE thay bảng tạm, sửa cursor lấy từ dòng cuối thực sự trả về
-- 2. admin_get_daily_task_policy_history: dùng CTE thay bảng tạm, sửa cursor lấy từ dòng cuối thực sự trả về
-- 3. admin_get_daily_task_config: bổ sung metadata phân trang (has_more, next_cursor_date, next_cursor_id, total_future_policies)

begin;

-- 1. admin_get_daily_task_config: bổ sung metadata phân trang
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

-- 2. admin_list_daily_task_policies: dùng CTE thay bảng tạm, cursor lấy từ dòng cuối thực sự trả về
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

-- 3. admin_get_daily_task_policy_history: dùng CTE thay bảng tạm, cursor lấy từ dòng cuối thực sự trả về
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

revoke execute on function public.admin_get_daily_task_config() from public, anon;
grant execute on function public.admin_get_daily_task_config() to authenticated, service_role;

revoke execute on function public.admin_list_daily_task_policies(integer, date, bigint) from public, anon;
grant execute on function public.admin_list_daily_task_policies(integer, date, bigint) to authenticated, service_role;

revoke execute on function public.admin_get_daily_task_policy_history(integer, bigint) from public, anon;
grant execute on function public.admin_get_daily_task_policy_history(integer, bigint) to authenticated, service_role;

notify pgrst, 'reload schema';

commit;
