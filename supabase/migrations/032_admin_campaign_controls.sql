-- ============================================================================
-- 12 Tester — Supabase migration 032: Admin pause/resume campaign controls
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nghiệp vụ:
-- 1. Admin có thể tạm dừng / tiếp tục nhận tester cho app của bất kỳ chủ app.
-- 2. Admin tạm dừng app -> chủ app KHÔNG thể tự bấm "Tiếp tục nhận tester".
-- 3. Admin tiếp tục app bị auto-pause vì chủ vắng 3 ngày -> đánh dấu
--    admin_resumed để cron không tự tạm dừng lại ngay.
-- 4. Vẫn giữ nguyên luật 30 ngày hiển thị và luật Slot hết hạn.
-- 5. Không bao giờ đổi owner_id khi Admin thao tác.
-- ============================================================================


-- ============================================================================
-- 1. Cột phục vụ Admin pause/resume
-- ============================================================================

alter table public.campaigns add column if not exists admin_note text;
alter table public.campaigns add column if not exists admin_resumed boolean not null default false;

comment on column public.campaigns.admin_note is
    'Lý do Admin tạm dừng app; hiển thị cho chủ app. Bị xóa khi app mở lại.';
comment on column public.campaigns.admin_resumed is
    'True khi Admin mở lại app vốn bị auto-pause vì chủ vắng; cron bỏ qua auto-pause.';

create index if not exists idx_campaigns_admin_resumed
    on public.campaigns(admin_resumed)
    where admin_resumed = true;


-- ============================================================================
-- 2. Trigger vòng đời: đồng bộ admin_note / admin_resumed
-- ============================================================================

create or replace function public.trg_campaign_status_lifecycle()
returns trigger
language plpgsql
as $$
begin
    if new.status in ('production', 'completed') then
        if new.completed_at is null then
            new.completed_at := now();
        end if;
        new.admin_resumed := false;
        -- Giữ admin_note khi app hết hạn: guard bên dưới dựa vào việc note
        -- không đổi để phân biệt hệ thống hoàn thành app với chủ app lách luật.
    elsif new.status = 'paused' then
        new.admin_resumed := false;
    elsif new.status = 'open' then
        new.completed_at := null;
        new.paused_reason := null;
        new.admin_note := null;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_campaign_status_lifecycle on public.campaigns;
create trigger trg_campaign_status_lifecycle
    before insert or update of status on public.campaigns
    for each row execute function public.trg_campaign_status_lifecycle();


-- ============================================================================
-- 3. _campaign_json: bổ sung admin_note cho model Campaign phía client
-- ============================================================================

create or replace function public._campaign_json(c public.campaigns)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', c.id,
        'owner_id', c.owner_id,
        'app_name', c.app_name,
        'package_name', c.package_name,
        'play_url', c.play_url,
        'testing_url', c.testing_url,
        'description', c.description,
        'target_testers', c.target_testers,
        'target_days', c.target_days,
        'start_date', to_char(c.start_date, 'YYYY-MM-DD'),
        'status', c.status,
        'icon_url', c.icon_url,
        'test_username', c.test_username,
        'test_password', c.test_password,
        'tip_credits', c.tip_credits,
        'is_boosted', c.is_boosted,
        'slot_number', c.slot_number,
        'paused_reason', c.paused_reason,
        'admin_note', c.admin_note,
        'completed_at', to_char(c.completed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(c.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
$$;

revoke execute on function public._campaign_json(public.campaigns) from public;


-- ============================================================================
-- 4. Auto-pause vì chủ vắng: bỏ qua app do Admin đã mở lại
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
begin
    for r in
        update public.campaigns c
        set status = 'paused',
            paused_reason = 'owner_inactive'
        from public.profiles p
        where c.owner_id = p.id
          and c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
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

grant execute on function public.pause_inactive_owner_campaigns() to authenticated, service_role;


-- ============================================================================
-- 5. Chủ app resume: chặn app đang bị Admin tạm dừng + reset admin_resumed
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

    -- App bị Admin tạm dừng: chủ app không được tự mở lại (kể cả khi đã hết
    -- 30 ngày — cron/sync_campaign_lifecycle sẽ tự chuyển app sang hoàn thành).
    if v_camp.paused_reason = 'admin_manual' then
        raise exception 'PAUSED_BY_ADMIN';
    end if;

    -- Quá cửa sổ 30 ngày -> hoàn thành (không raise để tránh rollback UPDATE).
    if v_camp.created_at <= now() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'completed'
        );
    end if;

    -- Slot > 1 phải còn hạn thì mới được mở lại (tránh mở app rồi bị auto-pause lại).
    if v_camp.slot_number > 1 then
        if not exists (
            select 1 from public.user_slots s
            where s.user_id = v_camp.owner_id
              and s.slot_number = v_camp.slot_number
              and s.expires_at > now()
        ) then
            raise exception 'SLOT_EXPIRED';
        end if;
    end if;

    -- Chủ app vẫn đang vắng 3 ngày liên tục -> yêu cầu làm nhiệm vụ ngày trước.
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
        completed_at = null,
        admin_note = null,
        admin_resumed = false
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
-- 6. RPC: Admin tạm dừng bất kỳ app nào
-- ============================================================================

drop function if exists public.admin_pause_campaign(uuid, text);

create or replace function public.admin_pause_campaign(
    p_campaign_id uuid,
    p_reason text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_camp public.campaigns%rowtype;
    v_note text;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select lower(role) into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'NOT_ADMIN';
    end if;

    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status in ('production', 'completed') then
        raise exception 'CAMPAIGN_FINALIZED';
    end if;

    if v_camp.status not in ('open', 'paused') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    v_note := nullif(btrim(coalesce(p_reason, '')), '');

    -- Idempotent: đã tạm dừng bởi Admin với cùng lý do thì không gửi trùng thông báo.
    if v_camp.status = 'paused'
       and v_camp.paused_reason = 'admin_manual'
       and v_camp.admin_note is not distinct from v_note then
        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'previous_status', v_camp.status,
            'status', v_camp.status,
            'paused_reason', v_camp.paused_reason,
            'app_name', v_camp.app_name,
            'owner_id', v_camp.owner_id,
            'admin_note', v_camp.admin_note
        );
    end if;

    update public.campaigns
    set status = 'paused',
        paused_reason = 'admin_manual',
        admin_note = v_note,
        admin_resumed = false
    where id = p_campaign_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_camp.owner_id,
        'Ứng dụng đã bị tạm dừng bởi Admin ⏸',
        format(
            'Ứng dụng "%s" đã bị Quản trị viên tạm dừng.%s',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
            case when v_note is null then '' else E'\nLý do: ' || v_note end
        ),
        'campaign',
        jsonb_build_object(
            'campaign_id', p_campaign_id,
            'reason', 'admin_pause',
            'admin_note', v_note
        )
    );

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'previous_status', v_camp.status,
        'status', 'paused',
        'paused_reason', 'admin_manual',
        'app_name', v_camp.app_name,
        'owner_id', v_camp.owner_id,
        'admin_note', v_note
    );
end;
$$;

revoke execute on function public.admin_pause_campaign(uuid, text) from public, anon;
grant execute on function public.admin_pause_campaign(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 7. RPC: Admin tiếp tục app đang tạm dừng
-- ============================================================================

drop function if exists public.admin_resume_campaign(uuid);

create or replace function public.admin_resume_campaign(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_camp public.campaigns%rowtype;
    v_hold_open boolean;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select lower(role) into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'NOT_ADMIN';
    end if;

    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status in ('production', 'completed') then
        raise exception 'CAMPAIGN_FINALIZED';
    end if;

    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    -- Vẫn giữ luật 30 ngày hiển thị.
    if v_camp.created_at <= now() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_camp.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 30 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
                coalesce(nullif(v_camp.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'window_expired')
        );

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'previous_status', v_camp.status,
            'status', 'completed',
            'app_name', v_camp.app_name,
            'owner_id', v_camp.owner_id
        );
    end if;

    -- Không cho Admin mở app đã hết hạn Slot: chủ app vẫn phải gia hạn Slot.
    if v_camp.slot_number > 1 then
        if not exists (
            select 1 from public.user_slots s
            where s.user_id = v_camp.owner_id
              and s.slot_number = v_camp.slot_number
              and s.expires_at > now()
        ) then
            raise exception 'SLOT_EXPIRED';
        end if;
    end if;

    -- Chỉ giữ mở khi app bị auto-pause vì chủ vắng. Các lý do khác
    -- (chủ tự tạm dừng, Admin tạm dừng) trả về luật bình thường.
    v_hold_open := (v_camp.paused_reason = 'owner_inactive');

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null,
        admin_note = null,
        admin_resumed = v_hold_open
    where id = p_campaign_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_camp.owner_id,
        'Ứng dụng đã được Admin mở lại ✅',
        format(
            'Ứng dụng "%s" đã được Quản trị viên mở lại và đang nhận tester bình thường.',
            coalesce(nullif(v_camp.app_name, ''), 'của bạn')
        ),
        'campaign',
        jsonb_build_object('campaign_id', p_campaign_id, 'reason', 'admin_resume')
    );

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'previous_status', v_camp.status,
        'status', 'open',
        'paused_reason', null,
        'app_name', v_camp.app_name,
        'owner_id', v_camp.owner_id,
        'admin_resumed', v_hold_open
    );
end;
$$;

revoke execute on function public.admin_resume_campaign(uuid) from public, anon;
grant execute on function public.admin_resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 8. Chặn cứng ở DB: owner không thể tự mở app bị Admin tạm dừng,
--    không thể tự bật cờ Admin, và owner_id không bị đổi qua client.
-- ============================================================================

create or replace function public.trg_campaigns_guard_admin_actions()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_role text;
begin
    -- SQL Editor / service_role không có auth.uid -> cho phép.
    if v_uid is null then
        return new;
    end if;

    -- Chặn client tự tạo campaign với cờ/lý do của Admin.
    if tg_op = 'INSERT' then
        if new.admin_resumed
           or new.admin_note is not null
           or new.paused_reason = 'admin_manual' then
            raise exception 'PERMISSION_DENIED';
        end if;
        return new;
    end if;

    -- Không ai (kể cả Admin qua client) được đổi chủ sở hữu campaign.
    -- SQL Editor / service_role vẫn có thể sửa trực tiếp khi thật sự cần.
    if new.owner_id is distinct from old.owner_id then
        raise exception 'OWNER_ID_IMMUTABLE';
    end if;

    -- Chỉ Admin được bật cờ bỏ qua auto-pause; chủ app không được tự bật.
    if new.admin_resumed and not coalesce(old.admin_resumed, false) then
        select lower(p.role) into v_role
        from public.profiles p
        where p.id = v_uid;

        if coalesce(v_role, '') <> 'admin' then
            raise exception 'PERMISSION_DENIED';
        end if;
    end if;

    -- App đang bị Admin tạm dừng: chủ app không được mở lại, không được xóa
    -- paused_reason/admin_note để lách luật. Hệ thống vẫn được chuyển app sang
    -- 'completed' khi hết 30 ngày vì update đó không đổi paused_reason/admin_note.
    if old.paused_reason = 'admin_manual' then
        if new.status = 'open'
           or new.paused_reason is distinct from 'admin_manual'
           or new.admin_note is distinct from old.admin_note
        then
            select lower(p.role) into v_role
            from public.profiles p
            where p.id = v_uid;

            if coalesce(v_role, '') <> 'admin' then
                raise exception 'PAUSED_BY_ADMIN';
            end if;
        end if;
    end if;

    return new;
end;
$$;

drop trigger if exists trg_campaigns_guard_admin_actions on public.campaigns;
create trigger trg_campaigns_guard_admin_actions
    before insert or update on public.campaigns
    for each row execute function public.trg_campaigns_guard_admin_actions();


-- ============================================================================
-- 9. Reload PostgREST schema cache
-- ============================================================================

notify pgrst, 'reload schema';
