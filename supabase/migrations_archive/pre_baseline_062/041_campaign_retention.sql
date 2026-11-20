-- ============================================================================
-- 12 Tester — Supabase migration 041: Campaign retention & archive (Phase 1)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- YÊU CẦU: đã chạy migration 017/029 (campaigns.completed_at) và 039/040
--          (bảng public.missions tồn tại).
--
-- Mục tiêu:
--   1. Lưu thống kê campaign vào campaign_archive TRƯỚC khi campaign bị xóa.
--   2. Tự động purge campaign đã hoàn thành quá thời hạn giữ liệu.
--   3. Ghi log mỗi lần purge vào cleanup_runs.
--
-- Cơ chế:
--   - Trigger BEFORE DELETE trên campaigns archive cho MỌI đường xóa:
--     chủ app xóa, admin xóa, cascade khi xóa tài khoản, và purge tự động.
--   - purge_completed_campaigns() chỉ xóa parent row campaigns; các bảng con
--     tự cascade theo FK sẵn có (campaign_members, checkins, feedbacks,
--     tester_reports, report_review_events, missions, mission_participants).
--   - Không xóa campaign còn tester_reports 'pending' hoặc mission chưa kết thúc.
--
-- Mặc định: giữ 90 ngày kể từ completed_at; purge cả 'completed' và 'production'.
-- Cron: 03:15 UTC hằng ngày (10:15 giờ VN), batch 100 campaign/lần.
--
-- Lưu ý: migration này chỉ dọn dữ liệu Postgres. Ảnh trên Firebase Storage
-- cần GCS lifecycle hoặc Edge Function (Phase 3).
--
-- Số liệu archive là snapshot tại THỜI ĐIỂM XÓA. Nếu tester xóa tài khoản
-- trước đó, checkin/feedback/report của họ đã bị cascade nên số liệu có thể
-- thấp hơn thời điểm campaign hoàn thành. Muốn chốt số liệu ngay khi hoàn
-- thành cần snapshot sớm hơn (Phase 2/3).
-- ============================================================================


-- ============================================================================
-- 1. Bảng archive thống kê campaign
-- ============================================================================

create table if not exists public.campaign_archive (
    campaign_id             uuid primary key,
    owner_id                uuid,
    app_name                text,
    package_name            text,
    campaign_status         text,
    target_testers          int,
    target_days             int,
    created_at              timestamptz,
    completed_at            timestamptz,
    member_count            int not null default 0,
    completed_tester_count  int not null default 0,
    checkin_count           int not null default 0,
    feedback_count          int not null default 0,
    report_count            int not null default 0,
    avg_rating              numeric(3,2),
    archived_reason         text not null default 'unknown',
    archived_at             timestamptz not null default now()
);

comment on table public.campaign_archive is
    'Thống kê campaign được chụp trước khi xóa. Không có FK tới campaigns/profiles để tồn tại sau khi dữ liệu gốc bị xóa.';
comment on column public.campaign_archive.completed_at is
    'completed_at gốc; nếu null (dữ liệu cũ) thì dùng created_at tại thời điểm archive.';
comment on column public.campaign_archive.completed_tester_count is
    'Số tester có số checkin >= campaigns.target_days tại thời điểm archive.';
comment on column public.campaign_archive.archived_reason is
    'retention | unknown | ... (lấy từ app.delete_reason nếu có).';

create index if not exists idx_campaign_archive_owner
    on public.campaign_archive (owner_id);
create index if not exists idx_campaign_archive_archived_at
    on public.campaign_archive (archived_at desc);

alter table public.campaign_archive enable row level security;
-- Không tạo policy: chỉ trigger/function security definer và service_role ghi/đọc.
-- Admin đọc trực tiếp bằng SQL Editor hoặc qua RPC riêng nếu cần.


-- ============================================================================
-- 2. Bảng log các lần dọn dẹp
-- ============================================================================

create table if not exists public.cleanup_runs (
    id           uuid primary key default gen_random_uuid(),
    job_name     text not null,
    status       text not null default 'running'
        check (status in ('running', 'success', 'partial', 'failed', 'dry_run')),
    started_at   timestamptz not null default now(),
    finished_at  timestamptz,
    scanned      int not null default 0,
    deleted      int not null default 0,
    error        text,
    details      jsonb not null default '{}'::jsonb
);

comment on table public.cleanup_runs is
    'Log các lần chạy job dọn dẹp dữ liệu (purge, retention...). Không chứa PII.';

create index if not exists idx_cleanup_runs_job_started
    on public.cleanup_runs (job_name, started_at desc);

alter table public.cleanup_runs enable row level security;
-- Không tạo policy: chỉ function security definer ghi; service_role đọc khi cần.


-- ============================================================================
-- 3. Trigger archive trước khi xóa campaign
-- ============================================================================

create or replace function public.archive_campaign_before_delete()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_reason text;
    v_target_days int;
begin
    v_reason := coalesce(
        nullif(current_setting('app.delete_reason', true), ''),
        'unknown'
    );
    v_target_days := coalesce(old.target_days, 14);

    insert into public.campaign_archive (
        campaign_id,
        owner_id,
        app_name,
        package_name,
        campaign_status,
        target_testers,
        target_days,
        created_at,
        completed_at,
        member_count,
        completed_tester_count,
        checkin_count,
        feedback_count,
        report_count,
        avg_rating,
        archived_reason
    )
    values (
        old.id,
        old.owner_id,
        old.app_name,
        old.package_name,
        old.status,
        old.target_testers,
        old.target_days,
        old.created_at,
        coalesce(old.completed_at, old.created_at),
        (select count(*) from public.campaign_members m where m.campaign_id = old.id),
        (
            select count(*)
            from (
                select ci.user_id
                from public.checkins ci
                where ci.campaign_id = old.id
                group by ci.user_id
                having count(*) >= v_target_days
            ) completed
        ),
        (select count(*) from public.checkins ci where ci.campaign_id = old.id),
        (select count(*) from public.feedbacks f where f.campaign_id = old.id),
        (select count(*) from public.tester_reports tr where tr.campaign_id = old.id),
        (select round(avg(f.rating), 2) from public.feedbacks f where f.campaign_id = old.id),
        v_reason
    )
    on conflict (campaign_id) do nothing;

    return old;
end;
$$;

comment on function public.archive_campaign_before_delete() is
    'Trigger BEFORE DELETE trên campaigns: chụp thống kê vào campaign_archive trước khi cascade xóa dữ liệu con.';

drop trigger if exists trg_campaigns_archive_before_delete on public.campaigns;
create trigger trg_campaigns_archive_before_delete
    before delete on public.campaigns
    for each row
    execute function public.archive_campaign_before_delete();


-- ============================================================================
-- 4. Index hỗ trợ truy vấn purge
-- ============================================================================

create index if not exists idx_campaigns_purge_due
    on public.campaigns (coalesce(completed_at, created_at))
    where status in ('completed', 'production');


-- ============================================================================
-- 5. Function purge campaign đã hoàn thành quá hạn
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
    -- Tối thiểu 1 ngày để tránh gọi nhầm với 0 gây xóa toàn bộ campaign.
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
begin
    -- Dòng log phải nằm NGOÀI block EXCEPTION bên dưới. Nếu đặt bên trong,
    -- khi lỗi toàn cục xảy ra subtransaction rollback sẽ xóa luôn dòng log.
    insert into public.cleanup_runs (job_name, status, details)
    values (
        'purge_completed_campaigns',
        'running',
        jsonb_build_object(
            'retention_days', v_days,
            'batch_size', v_batch,
            'dry_run', v_dry_run
        )
    )
    returning id into v_run_id;

    begin
        select coalesce(array_agg(t.id), '{}'::uuid[])
        into v_ids
        from (
            select c.id
            from public.campaigns c
            where c.status in ('completed', 'production')
              and coalesce(c.completed_at, c.created_at) < now() - make_interval(days => v_days)
              and not exists (
                  select 1
                  from public.tester_reports tr
                  where tr.campaign_id = c.id
                    and tr.status = 'pending'
              )
              and not exists (
                  select 1
                  from public.missions m
                  where m.campaign_id = c.id
                    and m.status not in ('expired', 'stopped_no_credits', 'deleted')
              )
            order by coalesce(c.completed_at, c.created_at), c.id
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

        -- Đánh dấu lý do để trigger archive ghi archived_reason = 'retention'.
        perform set_config('app.delete_reason', 'retention', true);

        foreach v_id in array v_ids loop
            begin
                delete from public.campaigns where id = v_id;
                get diagnostics v_row_count = row_count;
                v_deleted := v_deleted + v_row_count;
            exception when others then
                -- Chỉ rollback campaign lỗi; các campaign khác vẫn được xử lý.
                v_errors := v_errors || jsonb_build_array(
                    jsonb_build_object(
                        'campaign_id', v_id,
                        'error', left(sqlerrm, 500)
                    )
                );
            end;
        end loop;

        -- Không để GUC rò rỉ sang câu lệnh khác trong cùng transaction.
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
        -- Block con rollback nhưng dòng cleanup_runs phía trên vẫn được giữ.
        -- Không re-raise để log lỗi không bị rollback theo; theo dõi qua
        -- public.cleanup_runs (status = 'failed'), không dựa vào cron.job_run_details.
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

comment on function public.purge_completed_campaigns(int, boolean, int) is
    'Xóa campaign completed/production đã quá retention_days. Trả về số campaign đã xóa; dry_run trả về số sẽ xóa; -1 nếu lỗi toàn cục.';


-- ============================================================================
-- 6. Quyền: chỉ service_role/cron được gọi hàm purge
-- ============================================================================

-- Bảng archive/log không cho client đọc, kể cả khi RLS bị cấu hình sai sau này.
revoke all on table public.campaign_archive from public, anon, authenticated;
revoke all on table public.cleanup_runs from public, anon, authenticated;
grant select on table public.campaign_archive to service_role;
grant select on table public.cleanup_runs to service_role;

-- Chỉ service_role/cron được gọi hàm purge.
-- Lưu ý: quyền EXECUTE của trigger function được kiểm tra lúc CREATE TRIGGER,
-- không kiểm tra lúc chạy, nên revoke bên dưới không làm hỏng trigger archive.
revoke execute on function public.purge_completed_campaigns(int, boolean, int)
    from public, anon, authenticated;
grant execute on function public.purge_completed_campaigns(int, boolean, int)
    to service_role;

revoke execute on function public.archive_campaign_before_delete()
    from public, anon, authenticated;
grant execute on function public.archive_campaign_before_delete()
    to service_role;


-- ============================================================================
-- 7. Cron: chạy hằng ngày 03:15 UTC (10:15 giờ VN)
-- ============================================================================

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
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
        raise notice 'pg_cron chưa được bật — bật tại Database > Extensions rồi chạy lại migration 041 để tự động dọn campaign hoàn thành.';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;


-- ============================================================================
-- 8. Kiểm tra nhanh (chạy tay khi cần)
-- ============================================================================
-- Xem trước campaign sẽ bị xóa (không xóa thật):
-- select public.purge_completed_campaigns(90, true, 100);
--
-- Xem log:
-- select * from public.cleanup_runs order by started_at desc limit 10;
--
-- Xem archive:
-- select * from public.campaign_archive order by archived_at desc limit 10;
--
-- Gỡ cron khi cần:
-- select cron.unschedule('purge_completed_campaigns_job');

notify pgrst, 'reload schema';
