-- ============================================================================
-- 12 Tester — Supabase migration 043: Backend cleanup hardening (R03, R04, R07, R08)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Mục tiêu:
--   1. (R03) sync_expired_missions() là helper backend độc lập: tự động chuyển mission
--      hết hạn sang expired; chỉ cấp quyền cho service_role / cron.
--   2. (R03, R07, R08) purge_completed_campaigns():
--      - Dry-run an toàn: chỉ log và scan, KHÔNG gọi sync_expired_missions() hay đổi data.
--      - Chống starvation: ưu tiên campaign không lỗi; áp dụng exponential backoff tăng dần
--        (2 ngày -> 4 ngày -> 8 ngày -> 14 ngày) để 100 campaign lỗi không chặn các campaign khác.
--      - Chống race condition: dùng pg_advisory_xact_lock và for update skip locked;
--        kiểm tra không còn pending report và không có report mới nộp trong 1h qua;
--        đảm bảo không còn mission nào chưa quá hạn ends_at > now().
--   3. (R04, R06) pause_mission / resume_mission:
--      - Giữ nguyên contract lỗi auth/quyền cũ (RAISE EXCEPTION 'PERMISSION_DENIED', v.v.).
--      - Khi mission đã hết hạn (ends_at <= now()), cập nhật status = 'expired' vào DB
--        và trả về JSON có cấu trúc code: 'MISSION_EXPIRED' để không bị rollback UPDATE.
-- ============================================================================

-- ============================================================================
-- 1. sync_expired_missions() — Đồng bộ trạng thái mission hết hạn độc lập UI
-- ============================================================================

create or replace function public.sync_expired_missions()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_updated int := 0;
begin
    update public.missions
    set status = 'expired'
    where status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    get diagnostics v_updated = row_count;
    return v_updated;
end;
$$;

comment on function public.sync_expired_missions() is
    'Helper backend: tự động chuyển các mission open/full/paused_by_creator đã quá ends_at sang expired. Chỉ cấp quyền service_role/cron.';

-- Helper backend an toàn: chỉ service_role được gọi (review point 2)
revoke all on function public.sync_expired_missions() from public, anon, authenticated;
grant execute on function public.sync_expired_missions() to service_role;


-- ============================================================================
-- 2. Sửa pause_mission và resume_mission (R04, review point 6)
-- ============================================================================

create or replace function public.pause_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
begin
    -- Giữ nguyên contract lỗi auth/quyền cũ
    if v_user_id is null then
        raise exception 'Not authenticated';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null or v_mission.status = 'deleted' then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Nếu đã hết hạn: cập nhật DB và trả kết quả JSON có cấu trúc, KHÔNG raise exception
    -- để lệnh UPDATE status = 'expired' không bị rollback (R04).
    if v_mission.ends_at <= now() then
        update public.missions
        set status = 'expired'
        where id = p_mission_id
          and status in ('open', 'full', 'paused_by_creator');

        return jsonb_build_object(
            'success', false,
            'code', 'MISSION_EXPIRED',
            'error', 'MISSION_EXPIRED',
            'message', 'Nhiệm vụ đã hết hạn lưu hành',
            'mission_id', p_mission_id,
            'status', 'expired'
        );
    end if;

    if v_mission.status not in ('open', 'full') then
        raise exception 'MISSION_NOT_OPEN';
    end if;

    update public.missions
    set status = 'paused_by_creator'
    where id = p_mission_id;

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', 'paused_by_creator'
    );
end;
$$;

create or replace function public.resume_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
    v_camp_status text;
    v_new_status text;
begin
    -- Giữ nguyên contract lỗi auth/quyền cũ
    if v_user_id is null then
        raise exception 'Not authenticated';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null or v_mission.status = 'deleted' then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_mission.status <> 'paused_by_creator' then
        raise exception 'MISSION_NOT_PAUSED';
    end if;

    -- Nếu đã quá hạn: cập nhật DB và trả kết quả JSON có cấu trúc, không rollback UPDATE (R04).
    if v_mission.ends_at <= now() then
        update public.missions
        set status = 'expired'
        where id = p_mission_id;

        return jsonb_build_object(
            'success', false,
            'code', 'MISSION_EXPIRED',
            'error', 'MISSION_EXPIRED',
            'message', 'Nhiệm vụ đã hết hạn lưu hành',
            'mission_id', p_mission_id,
            'status', 'expired'
        );
    end if;

    select status into v_camp_status from public.campaigns where id = v_mission.campaign_id;
    if v_camp_status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp_status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    v_new_status := case when v_mission.participant_count >= v_mission.max_participants
                         then 'full' else 'open' end;

    update public.missions
    set status = v_new_status
    where id = p_mission_id;

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', v_new_status
    );
end;
$$;


-- ============================================================================
-- 3. Cập nhật purge_completed_campaigns() (R03, R07, R08, review points 1, 3, 5)
-- ============================================================================

create or replace function public.purge_completed_campaigns(
    p_retention_days int default 90,
    p_dry_run boolean default false,
    p_batch_size int default 100
)
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_days int := greatest(1, coalesce(p_retention_days, 90));
    v_batch int := greatest(1, least(coalesce(p_batch_size, 100), 1000));
    v_dry_run boolean := coalesce(p_dry_run, false);
    v_run_id uuid;
    v_ids uuid[];
    v_scanned int := 0;
    v_deleted int := 0;
    v_errors jsonb := '[]'::jsonb;
    v_id uuid;
    v_row_count int;
    v_status text;
    v_stale_pending_count int := 0;
    v_expired_missions_count int := 0;
    v_camp_rec record;
begin
    -- Khóa chống race condition khi nhiều connection cùng gọi hàm purge (review point 5)
    perform pg_advisory_xact_lock(hashtextextended('purge_completed_campaigns', 0));

    -- (Review point 1): Dry-run chỉ được log và scan, TUYỆT ĐỐI không gọi sync_expired_missions()
    -- làm đổi dữ liệu nghiệp vụ. Chỉ gọi khi NOT dry_run.
    if not v_dry_run then
        begin
            v_expired_missions_count := public.sync_expired_missions();
        exception when others then
            v_expired_missions_count := -1;
        end;
    end if;

    -- Đếm số pending reports quá hạn > 7 ngày để theo dõi backlog (R07)
    begin
        select count(*)
        into v_stale_pending_count
        from public.tester_reports
        where status = 'pending'
          and created_at < now() - interval '7 days';
    exception when others then
        v_stale_pending_count := -1;
    end;

    insert into public.cleanup_runs (job_name, status, details)
    values (
        'purge_completed_campaigns',
        'running',
        jsonb_build_object(
            'retention_days', v_days,
            'batch_size', v_batch,
            'dry_run', v_dry_run,
            'expired_missions_synced', v_expired_missions_count,
            'stale_pending_reports_count', v_stale_pending_count
        )
    )
    returning id into v_run_id;

    begin
        -- Lọc campaign đủ điều kiện purge:
        -- 1. status completed/closed/production quá v_days ngày
        -- 2. không còn pending report
        -- 3. không có report nộp trong 1h qua (chống race condition khi tester vừa nộp)
        -- 4. không còn mission đang chạy (open/full/paused_by_creator) hoặc ends_at > now() (R03)
        -- 5. (Review point 3): Chống starvation:
        --    - Ưu tiên campaign chưa từng lỗi lên đầu (order by has_error asc)
        --    - Áp dụng exponential backoff (2^fail_count ngày, tối đa 14 ngày)
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_ids
        from (
            select c.id
            from public.campaigns c
            where c.status in ('completed', 'closed', 'production')
              and coalesce(c.completed_at, c.created_at) < now() - make_interval(days => v_days)
              and not exists (
                  select 1
                  from public.tester_reports tr
                  where tr.campaign_id = c.id
                    and tr.status = 'pending'
              )
              and not exists (
                  select 1
                  from public.tester_reports tr
                  where tr.campaign_id = c.id
                    and tr.created_at > now() - interval '1 hour'
              )
              -- 4. Không còn mission nào chưa quá hạn ends_at (R03: mission đã quá hạn ends_at <= now() không chặn purge)
              and not exists (
                  select 1
                  from public.missions m
                  where m.campaign_id = c.id
                    and m.ends_at > now()
              )
              and not exists (
                  select 1
                  from (
                      select count(*) as fail_count, max(cr.started_at) as last_failed_at
                      from public.cleanup_runs cr,
                           jsonb_array_elements(cr.details->'errors') as err
                      where (err->>'campaign_id')::uuid = c.id
                        and cr.started_at > now() - interval '30 days'
                  ) f
                  where f.fail_count > 0
                    and f.last_failed_at > now() - make_interval(days => least(14, power(2, f.fail_count)::int))
              )
            order by
              -- Ưu tiên 1: Campaign chưa từng bị lỗi ưu tiên 0, có lỗi bị đẩy xuống sau (chống starvation)
              (case when exists (
                  select 1
                  from public.cleanup_runs cr,
                       jsonb_array_elements(cr.details->'errors') as err
                  where (err->>'campaign_id')::uuid = c.id
                    and cr.started_at > now() - interval '30 days'
              ) then 1 else 0 end) asc,
              coalesce(c.completed_at, c.created_at) asc,
              c.id asc
            limit v_batch
            for update skip locked
        ) t;

        v_scanned := coalesce(array_length(v_ids, 1), 0);

        if v_dry_run then
            update public.cleanup_runs
            set finished_at = now(),
                status = 'dry_run',
                scanned = v_scanned,
                details = details || jsonb_build_object('campaign_ids', to_jsonb(v_ids))
            where id = v_run_id;

            return v_scanned;
        end if;

        perform set_config('app.delete_reason', 'retention', true);

        foreach v_id in array v_ids loop
            begin
                -- Re-check trạng thái campaign trước khi delete thực tế
                select status, completed_at, created_at into v_camp_rec from public.campaigns where id = v_id for update;
                if v_camp_rec.status not in ('completed', 'closed', 'production')
                   or coalesce(v_camp_rec.completed_at, v_camp_rec.created_at) > now() - make_interval(days => v_days) then
                    continue;
                end if;

                delete from public.campaigns where id = v_id;
                get diagnostics v_row_count = row_count;
                v_deleted := v_deleted + v_row_count;
            exception when others then
                v_errors := v_errors || jsonb_build_array(
                    jsonb_build_object(
                        'campaign_id', v_id,
                        'error', left(sqlerrm, 500)
                    )
                );
            end;
        end loop;

        perform set_config('app.delete_reason', '', true);

        if jsonb_array_length(v_errors) = 0 then
            v_status := 'success';
        elsif v_deleted = 0 then
            v_status := 'failed';
        else
            v_status := 'partial';
        end if;

        update public.cleanup_runs
        set finished_at = now(),
            status = v_status,
            scanned = v_scanned,
            deleted = v_deleted,
            error = case
                when jsonb_array_length(v_errors) > 0
                    then 'One or more campaigns failed; see details.errors'
                else null
            end,
            details = details || jsonb_build_object('errors', v_errors)
        where id = v_run_id;

        return v_deleted;
    exception when others then
        perform set_config('app.delete_reason', '', true);

        update public.cleanup_runs
        set finished_at = now(),
            status = 'failed',
            scanned = v_scanned,
            error = left(sqlerrm, 2000)
        where id = v_run_id;

        raise warning 'purge_completed_campaigns failed: %', sqlerrm;
        return -1;
    end;
end;
$$;

revoke all on function public.purge_completed_campaigns(int, boolean, int) from public, anon, authenticated;
grant execute on function public.purge_completed_campaigns(int, boolean, int) to service_role;


-- ============================================================================
-- 4. Lập lịch pg_cron: sync_expired_missions (mỗi giờ) & purge_completed_campaigns
-- ============================================================================

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        -- 1. Job đồng bộ mission hết hạn: chạy đầu mỗi giờ
        begin
            perform cron.unschedule('sync_expired_missions_job');
        exception when others then null;
        end;

        perform cron.schedule(
            'sync_expired_missions_job',
            '0 * * * *',
            'select public.sync_expired_missions();'
        );

        -- 2. Job dọn dẹp campaign quá 90 ngày: 03:15 UTC hằng ngày (10:15 VN)
        begin
            perform cron.unschedule('purge_completed_campaigns_job');
        exception when others then null;
        end;

        perform cron.schedule(
            'purge_completed_campaigns_job',
            '15 3 * * *',
            'select public.purge_completed_campaigns(90, false, 100);'
        );
    else
        raise notice 'pg_cron chưa được bật — vui lòng bật pg_cron extension hoặc sử dụng backend scheduler.';
    end if;
exception when others then
    raise notice 'Không thể cấu hình pg_cron: %', sqlerrm;
end $$;
