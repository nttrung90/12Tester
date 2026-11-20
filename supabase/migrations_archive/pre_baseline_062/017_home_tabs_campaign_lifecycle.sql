-- ============================================================================
-- 12 Tester — Supabase migration 017: Home sub-tabs & Campaign lifecycle
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nghiệp vụ:
-- 1. App sau khi đăng hiển thị tối đa 15 ngày trên tab "App cần Test";
--    hết 15 ngày (tính từ created_at) tự chuyển sang "Đã hoàn thành Kiểm thử".
-- 2. Chủ app bấm "App đã lên production" trước 15 ngày → app cũng chuyển sang
--    "Đã hoàn thành Kiểm thử" (status 'production').
-- 3. Chủ app không hoàn thành nhiệm vụ ngày 3 ngày liên tục → toàn bộ app đang
--    mở của họ tự chuyển sang "App tạm dừng" (paused_reason = 'owner_inactive').
-- 4. App tạm dừng được bật test lại (RPC resume_campaign) khi:
--    - còn trong cửa sổ 15 ngày, và
--    - chủ app đã hoạt động trở lại (missed_streak < 3, tức đã test app hôm nay).
-- ============================================================================


-- ============================================================================
-- 1. Mở rộng trạng thái campaign + cột phục vụ vòng đời
-- ============================================================================

alter table public.campaigns drop constraint if exists campaigns_status_check;
alter table public.campaigns add constraint campaigns_status_check
    check (status in ('open', 'closed', 'paused', 'production', 'completed'));

alter table public.campaigns add column if not exists paused_reason text;
alter table public.campaigns add column if not exists completed_at timestamptz;

create index if not exists idx_campaigns_completed_at on public.campaigns(completed_at desc);

-- Trigger giữ completed_at đồng bộ: set khi sang production/completed, xóa khi mở lại
create or replace function public.trg_campaign_status_lifecycle()
returns trigger
language plpgsql
as $$
begin
    if new.status in ('production', 'completed') then
        if new.completed_at is null then
            new.completed_at := now();
        end if;
    elsif new.status = 'open' then
        new.completed_at := null;
        new.paused_reason := null;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_campaign_status_lifecycle on public.campaigns;
create trigger trg_campaign_status_lifecycle
    before insert or update of status on public.campaigns
    for each row execute function public.trg_campaign_status_lifecycle();

-- Ghi rõ lý do khi tạm dừng do Slot hết hạn (thay vì để trống như trước)
create or replace function public.pause_expired_slot_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count int := 0;
begin
    update public.campaigns c
    set status = 'paused',
        paused_reason = 'slot_expired'
    where c.status = 'open'
      and c.slot_number > 1
      and exists (
          select 1 from public.user_slots s
          where s.user_id = c.owner_id
            and s.slot_number = c.slot_number
            and s.expires_at <= now()
      );
    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

grant execute on function public.pause_expired_slot_campaigns() to authenticated, service_role;


-- ============================================================================
-- 1b. Chặn nộp báo cáo mới cho app không ở trạng thái 'open'
--     (app tạm dừng / đã hoàn thành kiểm thử / đã lên production)
-- ============================================================================

create or replace function public.trg_reports_require_open_campaign()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_status text;
begin
    select status into v_status from public.campaigns where id = new.campaign_id;
    if v_status is not null and v_status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;
    return new;
end;
$$;

drop trigger if exists trg_reports_require_open on public.tester_reports;
create trigger trg_reports_require_open
    before insert on public.tester_reports
    for each row execute function public.trg_reports_require_open_campaign();



-- ============================================================================
-- 2. RLS: người dùng đã đăng nhập xem được cả app đang test, tạm dừng và
--    đã hoàn thành (3 tab nhỏ trên Trang chủ). Khách vẫn chỉ thấy app 'open'.
-- ============================================================================

drop policy if exists "campaigns_select_visible" on public.campaigns;
create policy "campaigns_select_visible" on public.campaigns for select to authenticated
    using (
        status in ('open', 'paused', 'production', 'completed')
        or owner_id = auth.uid()
        or exists (select 1 from public.profiles where id = auth.uid() and role = 'admin')
    );


-- ============================================================================
-- 3. Hàm tự động: tạm dừng app của chủ app vắng mặt 3 ngày liên tục
-- ============================================================================

create or replace function public.pause_inactive_owner_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    v_count int := 0;
    v_missed int;
begin
    for r in
        update public.campaigns c
        set status = 'paused',
            paused_reason = 'owner_inactive'
        from public.profiles p
        where c.owner_id = p.id
          and c.status = 'open'
          and c.created_at <= now() - interval '24 hours'
          and greatest(p.missed_streak, (current_date - p.last_active_date - 1)) >= 3
        returning c.id, c.owner_id, c.app_name
    loop
        v_count := v_count + 1;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã bị tạm dừng ⏸',
            format(
                'Ứng dụng "%s" đã bị tạm dừng vì bạn không hoàn thành nhiệm vụ ngày trong 3 ngày liên tục. Hãy test 1 app hôm nay, sau đó bấm "Tiếp tục nhận tester" trong màn hình quản lý app để hiển thị lại.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'owner_inactive')
        );
    end loop;

    return v_count;
end;
$$;


-- ============================================================================
-- 4. Hàm tự động: hoàn thành kiểm thử sau 15 ngày hiển thị
-- ============================================================================

create or replace function public.complete_expired_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    v_count int := 0;
begin
    for r in
        update public.campaigns c
        set status = 'completed',
            completed_at = now()
        where c.status in ('open', 'paused')
          and c.created_at <= now() - interval '15 days'
        returning c.id, c.owner_id, c.app_name
    loop
        v_count := v_count + 1;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 15 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'window_expired')
        );
    end loop;

    return v_count;
end;
$$;


-- ============================================================================
-- 5. Hàm tổng hợp: đồng bộ vòng đời campaign (gọi từ app + pg_cron)
-- ============================================================================

create or replace function public.sync_campaign_lifecycle()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_completed int := 0;
    v_paused int := 0;
begin
    v_completed := public.complete_expired_campaigns();
    v_paused := public.pause_inactive_owner_campaigns();
    return jsonb_build_object('completed', v_completed, 'paused', v_paused);
end;
$$;

grant execute on function public.sync_campaign_lifecycle() to authenticated, service_role;


-- ============================================================================
-- 6. RPC: Chủ app bật test lại app đang tạm dừng → về "App cần Test"
-- ============================================================================

drop function if exists public.resume_campaign(uuid);

create or replace function public.resume_campaign(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_camp record;
    v_missed int;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id for update;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp.owner_id <> v_uid then
        raise exception 'PERMISSION_DENIED';
    end if;
    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    -- Quá cửa sổ 15 ngày → chuyển luôn sang hoàn thành
    if v_camp.created_at <= now() - interval '15 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now()
        where id = p_campaign_id;
        raise exception 'CAMPAIGN_WINDOW_EXPIRED';
    end if;

    -- Chủ app vẫn đang vắng 3 ngày liên tục → yêu cầu làm nhiệm vụ ngày trước
    select greatest(missed_streak, (current_date - last_active_date - 1))
    into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 3 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$$;

grant execute on function public.resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 7. Gọi đồng bộ ngay lần đầu để áp dụng luật cho dữ liệu cũ
-- ============================================================================

select public.sync_campaign_lifecycle();


-- ============================================================================
-- 8. Lên lịch đồng bộ vòng đời mỗi giờ bằng pg_cron (bỏ qua nếu chưa bật)
-- ============================================================================

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule(
            'campaign_lifecycle_sync_job',
            '11 * * * *',
            'select public.sync_campaign_lifecycle();'
        );
    else
        raise notice 'pg_cron chưa được bật — app vẫn tự đồng bộ khi mở Trang chủ, nhưng nên bật pg_cron để tự động hoàn thành/tạm dừng đúng giờ.';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;

notify pgrst, 'reload schema';
