-- ==============================================================================
-- 049_mission_report_hardening.sql
-- Khắc phục các vấn đề ngữ cảnh nhiệm vụ theo kế hoạch recheck 2026-10-29:
-- 1. get_campaign_detail: Bổ sung metadata nhiệm vụ (context, id, title, reward, ends_at)
--    và thumbnail_url để đồng bộ contract với receipt và API chủ app (R01).
-- 2. submit_tester_report:
--    - Tách nhánh cập nhật comment báo cáo approved khỏi luồng kiểm tra participant joined (R03).
--    - Cho phép sửa comment báo cáo approved mà không bị chặn bởi trạng thái completed
--      hoặc auto-resolve nhầm sang mission mới (R03).
--    - Kiểm tra thời hạn/trạng thái nhiệm vụ của báo cáo pending mang identity mission,
--      kể cả khi request không truyền p_mission_id (R04).
--    - Giữ nguyên bảo vệ hồi tố, replay idempotency và settlement an toàn một lần.
-- ==============================================================================

-- 1. Override get_campaign_detail đồng bộ metadata nhiệm vụ và thumbnail
create or replace function public.get_campaign_detail(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp public.campaigns%rowtype;
    v_is_owner boolean := false;
    v_is_actual_owner boolean := false;
    v_can_manage boolean := false;
    v_is_member boolean := false;
    v_confirmed boolean := false;
    v_campaign jsonb;
    v_testers jsonb := '[]'::jsonb;
    v_feedbacks jsonb := '[]'::jsonb;
    v_reports jsonb := '[]'::jsonb;
    v_profiles jsonb := '{}'::jsonb;
    v_today_tested int := 0;
    v_reports_count int := 0;
    v_avg float;
    v_my_dates jsonb := '[]'::jsonb;
    v_my_profile jsonb;
    v_unread jsonb := '[]'::jsonb;
    v_weekly int := 0;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    perform public.auto_accept_expired_reports();

    v_is_actual_owner := (v_camp.owner_id = v_uid);

    v_can_manage := v_is_actual_owner
        or exists (
            select 1 from public.profiles p
            where p.id = v_uid and lower(p.role) in ('admin', 'moderator')
        );

    v_is_owner := v_can_manage;

    v_is_member := exists (
        select 1 from public.campaign_members m
        where m.campaign_id = p_campaign_id and m.user_id = v_uid
    );

    v_confirmed := exists (
        select 1 from public.checkins ci
        where ci.campaign_id = p_campaign_id
          and ci.user_id = v_uid
          and ci.checkin_date = v_today
          and coalesce(ci.note, '') not like '%tạm tính%'
    );

    v_campaign := public._campaign_json(v_camp);

    select coalesce(jsonb_agg(jsonb_build_object(
        'user_id', t.user_id,
        'joined_at', t.joined_at,
        'days', t.days,
        'checkin_count', t.checkin_count,
        'last_checkin_date', t.last_checkin_date
    ) order by t.days desc), '[]'::jsonb)
    into v_testers
    from (
        select m.user_id,
               to_char(m.joined_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as joined_at,
               coalesce(s.days, 0) as days,
               coalesce(s.cnt, 0) as checkin_count,
               to_char(s.last_date, 'YYYY-MM-DD') as last_checkin_date
        from public.campaign_members m
        left join (
            select user_id,
                   count(distinct checkin_date) as days,
                   count(*) as cnt,
                   max(checkin_date) as last_date
            from public.checkins
            where campaign_id = p_campaign_id
            group by user_id
        ) s on s.user_id = m.user_id
        where m.campaign_id = p_campaign_id
    ) t;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', f.id,
        'campaign_id', f.campaign_id,
        'user_id', f.user_id,
        'rating', f.rating,
        'comment', f.comment,
        'created_at', to_char(f.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'updated_at', to_char(f.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by f.created_at desc), '[]'::jsonb)
    into v_feedbacks
    from public.feedbacks f
    where f.campaign_id = p_campaign_id;

    -- Bổ sung đầy đủ metadata nhiệm vụ và thumbnail cho lịch sử báo cáo
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', tr.id,
        'campaign_id', tr.campaign_id,
        'user_id', tr.user_id,
        'report_type', tr.report_type,
        'content', tr.content,
        'screenshot_url', tr.screenshot_url,
        'thumbnail_url', tr.thumbnail_url,
        'rating', tr.rating,
        'status', tr.status,
        'bonus_awarded', tr.bonus_awarded,
        'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'rejection_reason', tr.rejection_reason,
        'image_hash', tr.image_hash,
        'submission_context', tr.submission_context,
        'mission_id', tr.mission_id,
        'mission_participant_id', tr.mission_participant_id,
        'mission_title', tr.mission_title,
        'mission_reward_credits', tr.mission_reward_credits,
        'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by tr.created_at desc), '[]'::jsonb)
    into v_reports
    from (
        select * from public.tester_reports
        where campaign_id = p_campaign_id
          and (v_can_manage or user_id = v_uid)
        order by created_at desc
        limit 200
    ) tr;

    select coalesce(jsonb_object_agg(t.id::text, public._profile_json(p)), '{}'::jsonb)
    into v_profiles
    from public.profiles p
    join (
        select v_camp.owner_id as id
        union
        select m.user_id from public.campaign_members m where m.campaign_id = p_campaign_id
        union
        select f.user_id from public.feedbacks f where f.campaign_id = p_campaign_id
        union
        select tr.user_id from public.tester_reports tr
        where tr.campaign_id = p_campaign_id and (v_can_manage or tr.user_id = v_uid)
    ) t on t.id = p.id;

    select count(distinct u.user_id) into v_today_tested
    from (
        select ci.user_id from public.checkins ci
        where ci.campaign_id = p_campaign_id and ci.checkin_date = v_today
        union
        select tr.user_id from public.tester_reports tr
        where tr.campaign_id = p_campaign_id and tr.status <> 'rejected'
          and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    ) u;

    select count(*) into v_reports_count
    from public.tester_reports
    where campaign_id = p_campaign_id;

    select avg(rating) into v_avg
    from public.feedbacks
    where campaign_id = p_campaign_id;

    select coalesce(jsonb_agg(to_char(ci.checkin_date, 'YYYY-MM-DD') order by ci.checkin_date desc), '[]'::jsonb)
    into v_my_dates
    from public.checkins ci
    where ci.campaign_id = p_campaign_id and ci.user_id = v_uid;

    select public._profile_json(p) into v_my_profile
    from public.profiles p where p.id = v_uid;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
        'warning_type', w.warning_type,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) order by w.created_at desc), '[]'::jsonb)
    into v_unread
    from public.user_warnings w
    where w.user_id = v_uid and w.acknowledged = false;

    select count(*) into v_weekly
    from public.user_warnings w
    where w.user_id = v_uid
      and (w.created_at at time zone 'Asia/Ho_Chi_Minh')::date >= (v_today - 6);

    return jsonb_build_object(
        'campaign', v_campaign,
        'is_owner', v_is_owner,
        'is_actual_owner', v_is_actual_owner,
        'can_manage', v_can_manage,
        'is_member', v_is_member,
        'checked_in_confirmed_today', v_confirmed,
        'testers', v_testers,
        'feedbacks', v_feedbacks,
        'reports', v_reports,
        'profiles', v_profiles,
        'today_tested_count', v_today_tested,
        'reports_count', v_reports_count,
        'avg_rating', v_avg,
        'my_checkin_dates', v_my_dates,
        'my_profile', v_my_profile,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly
    );
end;
$$;

revoke execute on function public.get_campaign_detail(uuid) from public, anon;
grant execute on function public.get_campaign_detail(uuid) to authenticated, service_role;

-- 2. Override submit_tester_report hoàn thiện các phân nhánh R03, R04
drop function if exists public.submit_tester_report(uuid, text, text, int, text, uuid, text, uuid);

create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5,
    p_image_hash text default null,
    p_client_submit_id uuid default null,
    p_thumbnail_url text default null,
    p_mission_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_report_id uuid;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp record;
    v_existing_report record;
    v_idempotent_report record;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_provisional_amount int := 0;
    v_provisional_reward int := 10;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_effective_full text;
    v_final_url text;
    v_final_hash text;
    v_final_thumb text;

    -- Mission context variables
    v_mission record;
    v_mp record;
    v_eligible_count int := 0;
    v_submission_context text := 'app_test';
    v_target_mission_id uuid := null;
    v_target_mp_id uuid := null;
    v_target_title text := null;
    v_target_reward int := null;
    v_target_ends_at timestamptz := null;
    v_check_m record;
    v_res_context text;
    v_res_mission_id uuid;
    v_res_title text;
    v_res_reward int;
    v_res_ends_at timestamptz;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Idempotency check: receipt đã commit với cùng client_submit_id trả lại kết quả ngay
    if p_client_submit_id is not null then
        select * into v_idempotent_report
        from public.tester_reports
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_idempotent_report.id is not null then
            if v_idempotent_report.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if p_mission_id is not null and v_idempotent_report.mission_id is distinct from p_mission_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;

            return jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_idempotent_report.screenshot_url,
                'image_hash', v_idempotent_report.image_hash,
                'thumbnail_url', v_idempotent_report.thumbnail_url,
                'submission_context', v_idempotent_report.submission_context,
                'mission_id', v_idempotent_report.mission_id,
                'mission_title', v_idempotent_report.mission_title,
                'mission_reward_credits', v_idempotent_report.mission_reward_credits,
                'mission_ends_at', to_char(v_idempotent_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            );
        end if;
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'USER_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- Khóa tuần tự theo (user, campaign, ngày)
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Tìm báo cáo hôm nay và khóa dòng để phân loại thao tác
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1
    for update;

    -- TH1: BÁO CÁO ĐÃ DUYỆT (approved) -> Chỉ cho phép bổ sung comment, không phát sinh thưởng
    if v_existing_report.id is not null and v_existing_report.status = 'approved' then
        -- R03: Nếu nộp kèm p_mission_id mà báo cáo đã duyệt vốn là app_test thường -> chặn
        if p_mission_id is not null then
            if v_existing_report.submission_context <> 'mission' then
                raise exception 'REPORT_ALREADY_APPROVED_AS_APP_TEST';
            end if;
            if v_existing_report.mission_id is distinct from p_mission_id then
                raise exception 'REPORT_ALREADY_APPROVED_FOR_ANOTHER_MISSION';
            end if;
            -- Báo cáo duyệt là mission report và đúng missionId: cho phép sửa comment, giữ context mission
            v_submission_context := 'mission';
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
        else
            -- R03: Không truyền p_mission_id: giữ nguyên context cũ (app_test hoặc mission), KHÔNG auto-resolve
            v_submission_context := coalesce(v_existing_report.submission_context, 'app_test');
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
        end if;

        -- Feedback: chỉ cập nhật comment, giữ rating
        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (
            p_campaign_id, v_user_id, coalesce(v_existing_report.rating, v_rating),
            coalesce(p_content, ''), now()
        )
        on conflict (campaign_id, user_id)
        do update set
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        update public.tester_reports
        set content = case when coalesce(p_content, '') <> '' then p_content else content end,
            client_submit_id = coalesce(p_client_submit_id, client_submit_id)
        where id = v_existing_report.id
          and status = 'approved'
        returning id into v_report_id;

        if v_report_id is null then
            raise exception 'REPORT_ALREADY_REVIEWED';
        end if;
        v_status := 'approved';

        update public.profiles
        set last_active_date = v_today, missed_streak = 0
        where id = v_user_id;

        select
            submission_context, mission_id, mission_title,
            mission_reward_credits, mission_ends_at, screenshot_url,
            image_hash, thumbnail_url
        into
            v_res_context, v_res_mission_id, v_res_title,
            v_res_reward, v_res_ends_at, v_final_url,
            v_final_hash, v_final_thumb
        from public.tester_reports
        where id = v_report_id;

        return jsonb_build_object(
            'success', true,
            'report_id', v_report_id,
            'status', 'approved',
            'provisional_granted', false,
            'provisional_amount', 0,
            'screenshot_url', v_final_url,
            'image_hash', v_final_hash,
            'thumbnail_url', v_final_thumb,
            'submission_context', v_res_context,
            'mission_id', v_res_mission_id,
            'mission_title', v_res_title,
            'mission_reward_credits', v_res_reward,
            'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        );
    end if;

    -- TH2: BÁO CÁO ĐANG CHỜ DUYỆT (pending) HOẶC BỊ TỪ CHỐI (rejected) HOẶC NỘP MỚI
    -- R04: Nếu báo cáo đã có mission identity, việc sửa ảnh/minh chứng PHẢI kiểm tra mission cũ còn hạn
    if v_existing_report.id is not null and v_existing_report.submission_context = 'mission' then
        if p_mission_id is not null and p_mission_id <> v_existing_report.mission_id then
            raise exception 'CANNOT_CHANGE_MISSION_IDENTITY';
        end if;

        select * into v_check_m
        from public.missions
        where id = v_existing_report.mission_id
        for update;

        if v_check_m.id is null or v_check_m.ends_at <= now() or v_check_m.status = 'expired' then
            raise exception 'MISSION_EXPIRED';
        end if;
        if v_check_m.status not in ('open', 'full') then
            raise exception 'MISSION_NOT_OPEN';
        end if;

        select * into v_mp
        from public.mission_participants
        where mission_id = v_existing_report.mission_id
          and user_id = v_user_id
        for update;

        if v_mp.id is null or v_mp.status <> 'joined' then
            raise exception 'MISSION_NOT_JOINED';
        end if;

        v_submission_context := 'mission';
        v_target_mission_id := v_existing_report.mission_id;
        v_target_mp_id := v_mp.id;
        v_target_title := v_check_m.title;
        v_target_reward := v_check_m.reward_credits;
        v_target_ends_at := v_check_m.ends_at;

    elsif v_existing_report.id is not null and v_existing_report.submission_context <> 'mission' then
        -- Báo cáo hôm nay là app_test thường: không cho phép chuyển đổi pending thành mission report
        if p_mission_id is not null then
            raise exception 'CANNOT_CONVERT_EXISTING_PENDING_REPORT';
        end if;
        v_submission_context := 'app_test';

    else
        -- Báo cáo nộp mới (v_existing_report.id is null)
        if p_mission_id is not null then
            select * into v_mission
            from public.missions
            where id = p_mission_id
            for update;

            if v_mission.id is null then
                raise exception 'MISSION_NOT_FOUND';
            end if;
            if v_mission.campaign_id <> p_campaign_id then
                raise exception 'INVALID_MISSION_CAMPAIGN';
            end if;
            if v_mission.creator_id = v_user_id then
                raise exception 'CANNOT_TEST_OWN_APP';
            end if;
            if v_mission.ends_at <= now() or v_mission.status = 'expired' then
                raise exception 'MISSION_EXPIRED';
            end if;
            if v_mission.status not in ('open', 'full') then
                raise exception 'MISSION_NOT_OPEN';
            end if;

            select * into v_mp
            from public.mission_participants
            where mission_id = p_mission_id
              and user_id = v_user_id
            for update;

            if v_mp.id is null or v_mp.status <> 'joined' then
                raise exception 'MISSION_NOT_JOINED';
            end if;

            v_submission_context := 'mission';
            v_target_mission_id := v_mission.id;
            v_target_mp_id := v_mp.id;
            v_target_title := v_mission.title;
            v_target_reward := v_mission.reward_credits;
            v_target_ends_at := v_mission.ends_at;
        else
            -- Tự động phân loại nếu tester đã join đúng 1 mission đang mở và còn hạn
            select count(*) into v_eligible_count
            from public.missions m
            join public.mission_participants mp on mp.mission_id = m.id
            where m.campaign_id = p_campaign_id
              and mp.user_id = v_user_id
              and mp.status = 'joined'
              and m.status in ('open', 'full')
              and m.ends_at > now();

            if v_eligible_count = 1 then
                select m.*, mp.id as mp_id
                into v_mission
                from public.missions m
                join public.mission_participants mp on mp.mission_id = m.id
                where m.campaign_id = p_campaign_id
                  and mp.user_id = v_user_id
                  and mp.status = 'joined'
                  and m.status in ('open', 'full')
                  and m.ends_at > now()
                for update of m;

                v_submission_context := 'mission';
                v_target_mission_id := v_mission.id;
                v_target_mp_id := v_mission.mp_id;
                v_target_title := v_mission.title;
                v_target_reward := v_mission.reward_credits;
                v_target_ends_at := v_mission.ends_at;
            elsif v_eligible_count > 1 then
                raise exception 'AMBIGUOUS_MISSION_SUBMISSION';
            else
                v_submission_context := 'app_test';
            end if;
        end if;
    end if;

    -- Kiểm tra ảnh mới và tính hợp lệ
    v_new_screenshot := p_screenshot_url is not null
        and (v_existing_report.id is null or p_screenshot_url is distinct from v_existing_report.screenshot_url);

    if p_image_hash is not null and lower(p_image_hash) !~ '^[0-9a-f]{16}$' then
        raise exception 'INVALID_IMAGE_HASH';
    end if;

    if v_new_screenshot then
        if p_image_hash is null or p_image_hash = '' then
            raise exception 'IMAGE_HASH_REQUIRED';
        end if;
        if not public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) then
            raise exception 'INVALID_SCREENSHOT_URL';
        end if;
    end if;

    v_effective_full := coalesce(p_screenshot_url, v_existing_report.screenshot_url);

    if p_thumbnail_url is not null then
        if v_effective_full is null then
            raise exception 'INVALID_THUMBNAIL_URL';
        end if;
        if not public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, v_effective_full) then
            raise exception 'INVALID_THUMBNAIL_URL';
        end if;
    end if;

    -- Ghi nhận feedback
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (
        p_campaign_id, v_user_id, v_rating,
        coalesce(p_content, ''), now()
    )
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
        updated_at = now();

    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
    ) into v_already_checked_in;

    perform set_config('app.submitting_report', 'true', true);

    if v_existing_report.id is not null then
        -- TH2a: Nộp lại báo cáo bị từ chối
        if v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if not v_new_screenshot then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is not null
               and p_image_hash is not null
               and public.dhash_hamming(v_existing_report.image_hash, p_image_hash) <= 5 then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_existing_report.image_hash is null
               and p_image_hash is null
               and p_screenshot_url = v_existing_report.screenshot_url then
                raise exception 'SAME_SCREENSHOT_REJECTED';
            end if;

            if v_already_checked_in then
                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    thumbnail_url = p_thumbnail_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    provisional_clawed = 0,
                    provisional_granted_credits = 0,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null,
                    submission_context = v_submission_context,
                    mission_id = v_target_mission_id,
                    mission_participant_id = v_target_mp_id,
                    mission_title = v_target_title,
                    mission_reward_credits = v_target_reward,
                    mission_ends_at = v_target_ends_at,
                    mission_submitted_at = case when v_submission_context = 'mission' then now() else null end
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            else
                v_regrant := coalesce(v_existing_report.provisional_clawed, 0);
                if v_regrant <= 0 then
                    v_regrant := v_provisional_reward;
                end if;

                update public.profiles
                set credits = credits + v_regrant,
                    last_active_date = v_today,
                    missed_streak = 0
                where id = v_user_id
                returning credits into v_new_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_user_id, v_regrant, 'test_provisional',
                    format('Hoàn trả %s Credit tạm nhận sau khi nộp lại báo cáo (chờ duyệt)', v_regrant),
                    v_new_balance
                );

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp lại kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp lại kết quả test (tạm tính, chờ chủ app duyệt)';

                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    thumbnail_url = p_thumbnail_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = v_regrant,
                    provisional_clawed = 0,
                    provisional_granted_credits = v_regrant,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null,
                    submission_context = v_submission_context,
                    mission_id = v_target_mission_id,
                    mission_participant_id = v_target_mp_id,
                    mission_title = v_target_title,
                    mission_reward_credits = v_target_reward,
                    mission_ends_at = v_target_ends_at,
                    mission_submitted_at = case when v_submission_context = 'mission' then now() else null end
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
                v_provisional_granted := true;
                v_provisional_amount := v_regrant;
            end if;

        -- TH2b: Báo cáo pending -> cho phép chỉnh sửa nội dung/ảnh
        elsif v_existing_report.status = 'pending' then
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = case when v_new_screenshot then p_screenshot_url else screenshot_url end,
                thumbnail_url = case
                    when p_thumbnail_url is not null then p_thumbnail_url
                    when v_new_screenshot then null
                    else thumbnail_url
                end,
                rating = v_rating,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
              and status = 'pending'
              and reviewed_at is null
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := 'pending';
        else
            raise exception 'REPORT_ALREADY_REVIEWED';
        end if;

    else
        -- TH3: Chưa có báo cáo hôm nay -> nộp mới
        if v_already_checked_in then
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', 2, 0, 0,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end
            )
            returning id into v_report_id;

            v_status := 'pending';
        else
            update public.profiles
            set credits = credits + v_provisional_reward,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id, v_provisional_reward, 'test_provisional',
                format('Tạm nhận %s Credit sau khi nộp kết quả test (chờ chủ app duyệt)', v_provisional_reward),
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', v_provisional_reward, 0, v_provisional_reward,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := v_provisional_reward;
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today, missed_streak = 0
    where id = v_user_id;

    select
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, screenshot_url,
        image_hash, thumbnail_url
    into
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_final_url,
        v_final_hash, v_final_thumb
    from public.tester_reports
    where id = v_report_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount,
        'screenshot_url', v_final_url,
        'image_hash', v_final_hash,
        'thumbnail_url', v_final_thumb,
        'submission_context', v_res_context,
        'mission_id', v_res_mission_id,
        'mission_title', v_res_title,
        'mission_reward_credits', v_res_reward,
        'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text, uuid, text, uuid) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text, uuid, text, uuid) to authenticated, service_role;

notify pgrst, 'reload schema';
