-- ============================================================================
-- 12 Tester — Supabase migration 040: Quản lý nhiệm vụ (dừng / tiếp tục / xóa)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- YÊU CẦU: chạy migration 039_missions.sql TRƯỚC.
--
-- Quy tắc (đã chốt với product):
--   • Dừng nhiệm vụ (paused_by_creator) = dừng hẳn: không nhận người mới VÀ
--     không trả thưởng nhiệm vụ khi duyệt báo cáo trong lúc dừng (thưởng test
--     thông thường không đổi). _settle_mission_reward / join_mission chỉ xử lý
--     status open/full nên tự động đúng.
--   • Tiếp tục: giữ nguyên thời hạn gốc ends_at (ngày vẫn trôi khi đang dừng).
--   • Xóa nhiệm vụ (deleted, xóa mềm): ẩn khỏi mọi danh sách, KHÔNG hoàn phí
--     nền tảng đã trừ. Người đã hoàn thành giữ Credit; người đã nhận nhưng
--     chưa hoàn thành nhận notification báo nhiệm vụ bị xóa.
-- ============================================================================

-- ============================================================================
-- 1. Mở rộng check constraint trạng thái mission
-- ============================================================================
alter table public.missions drop constraint if exists missions_status_check;
alter table public.missions add constraint missions_status_check
    check (status in ('open', 'full', 'stopped_no_credits', 'expired', 'paused_by_creator', 'deleted'));

-- ============================================================================
-- 2. RPC pause_mission — chủ nhiệm vụ dừng hẳn nhiệm vụ
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
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
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

    if v_mission.ends_at <= now() then
        update public.missions
        set status = 'expired'
        where id = p_mission_id
          and status in ('open', 'full', 'paused_by_creator');
        raise exception 'MISSION_EXPIRED';
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

revoke execute on function public.pause_mission(uuid) from public, anon;
grant execute on function public.pause_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 3. RPC resume_mission — mở lại nhiệm vụ đã dừng (giữ nguyên ends_at)
-- ============================================================================
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
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
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

    if v_mission.ends_at <= now() then
        update public.missions
        set status = 'expired'
        where id = p_mission_id;
        raise exception 'MISSION_EXPIRED';
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

revoke execute on function public.resume_mission(uuid) from public, anon;
grant execute on function public.resume_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 4. RPC delete_mission — xóa mềm, không hoàn phí, báo người đã nhận
-- ============================================================================
create or replace function public.delete_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
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

    update public.missions
    set status = 'deleted'
    where id = p_mission_id;

    -- Người đã nhận nhưng chưa hoàn thành: báo nhiệm vụ bị xóa, mất cơ hội nhận thưởng.
    insert into public.notifications (user_id, title, message, type, data)
    select
        mp.user_id,
        'Nhiệm vụ đã bị xóa 🗑️',
        format('Nhiệm vụ "%s" đã được chủ nhiệm vụ xóa. Bạn không thể tiếp tục nhận thưởng từ nhiệm vụ này.',
               v_mission.title),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'campaign_id', v_mission.campaign_id, 'deleted', true)
    from public.mission_participants mp
    where mp.mission_id = p_mission_id
      and mp.status = 'joined';

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', 'deleted'
    );
end;
$$;

revoke execute on function public.delete_mission(uuid) from public, anon;
grant execute on function public.delete_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 5. RPC get_campaign_mission — nhiệm vụ hiện tại của 1 app (màn quản lý)
-- Trả về mission mới nhất chưa xóa, cùng shape với get_open_missions.
-- ============================================================================
create or replace function public.get_campaign_mission(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_row record;
begin
    select
        m.id, m.title, m.description, m.reward_credits,
        m.max_participants, m.participant_count, m.status,
        m.ends_at, m.created_at, m.campaign_id, m.creator_id,
        c.app_name, c.icon_url, c.package_name,
        coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name
    into v_row
    from public.missions m
    join public.campaigns c on c.id = m.campaign_id
    left join public.profiles p on p.id = m.creator_id
    where m.campaign_id = p_campaign_id
      and m.status <> 'deleted'
    order by m.created_at desc
    limit 1;

    if v_row.id is null then
        return jsonb_build_object('mission', null);
    end if;

    return jsonb_build_object(
        'mission', jsonb_build_object(
            'id', v_row.id,
            'title', v_row.title,
            'description', v_row.description,
            'reward_credits', v_row.reward_credits,
            'max_participants', v_row.max_participants,
            'participant_count', v_row.participant_count,
            'status', v_row.status,
            'ends_at', to_char(v_row.ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'created_at', to_char(v_row.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'campaign_id', v_row.campaign_id,
            'app_name', v_row.app_name,
            'icon_url', v_row.icon_url,
            'package_name', v_row.package_name,
            'creator_id', v_row.creator_id,
            'creator_name', v_row.creator_name,
            'is_mine', v_row.creator_id = v_user_id,
            'has_joined', exists (
                select 1 from public.mission_participants mp2
                where mp2.mission_id = v_row.id and mp2.user_id = v_user_id
            ),
            'has_completed', exists (
                select 1 from public.mission_participants mp3
                where mp3.mission_id = v_row.id and mp3.user_id = v_user_id
                  and mp3.status = 'completed'
            )
        )
    );
end;
$$;

revoke execute on function public.get_campaign_mission(uuid) from public, anon;
grant execute on function public.get_campaign_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 6. get_open_missions — loại nhiệm vụ đã xóa; nhiệm vụ đang dừng vẫn hiện
--    (người đã nhận cần thấy trạng thái), đứng sau các nhiệm vụ đang mở.
-- ============================================================================
create or replace function public.get_open_missions()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    update public.missions
    set status = 'expired'
    where status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    return jsonb_build_object(
        'missions', coalesce((
            select jsonb_agg(
                jsonb_build_object(
                    'id', t.id,
                    'title', t.title,
                    'description', t.description,
                    'reward_credits', t.reward_credits,
                    'max_participants', t.max_participants,
                    'participant_count', t.participant_count,
                    'status', t.status,
                    'ends_at', to_char(t.ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'created_at', to_char(t.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'campaign_id', t.campaign_id,
                    'app_name', t.app_name,
                    'icon_url', t.icon_url,
                    'package_name', t.package_name,
                    'creator_id', t.creator_id,
                    'creator_name', t.creator_name,
                    'is_mine', t.creator_id = v_user_id,
                    'has_joined', t.has_joined,
                    'has_completed', t.has_completed
                )
                order by t.sort_active desc, t.created_at desc
            )
            from (
                select
                    m.id, m.title, m.description, m.reward_credits,
                    m.max_participants, m.participant_count, m.status,
                    m.ends_at, m.created_at, m.campaign_id, m.creator_id,
                    c.app_name, c.icon_url, c.package_name,
                    coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name,
                    case m.status
                        when 'open' then 2
                        when 'full' then 2
                        when 'paused_by_creator' then 1
                        else 0
                    end as sort_active,
                    exists (
                        select 1 from public.mission_participants mp2
                        where mp2.mission_id = m.id and mp2.user_id = v_user_id
                    ) as has_joined,
                    exists (
                        select 1 from public.mission_participants mp3
                        where mp3.mission_id = m.id and mp3.user_id = v_user_id
                          and mp3.status = 'completed'
                    ) as has_completed
                from public.missions m
                join public.campaigns c on c.id = m.campaign_id
                left join public.profiles p on p.id = m.creator_id
                where m.status <> 'deleted'
                  and m.created_at > now() - interval '14 days'
                limit 100
            ) t
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function public.get_open_missions() from public, anon;
grant execute on function public.get_open_missions() to authenticated, service_role;

notify pgrst, 'reload schema';
