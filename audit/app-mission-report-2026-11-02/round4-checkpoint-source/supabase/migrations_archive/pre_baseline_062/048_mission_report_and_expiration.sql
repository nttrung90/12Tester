-- ============================================================================
-- 12 Tester — Supabase migration 048: Báo cáo nhiệm vụ và ẩn nhiệm vụ hết hạn
-- ============================================================================

-- 1. Bổ sung các cột metadata nhiệm vụ vào bảng public.tester_reports
alter table public.tester_reports
    add column if not exists submission_context text check (submission_context in ('app_test', 'mission')),
    add column if not exists mission_id uuid references public.missions(id) on delete set null,
    add column if not exists mission_participant_id uuid references public.mission_participants(id) on delete set null,
    add column if not exists mission_title text,
    add column if not exists mission_reward_credits int,
    add column if not exists mission_ends_at timestamptz,
    add column if not exists mission_submitted_at timestamptz;

create index if not exists idx_tester_reports_mission_id
on public.tester_reports(mission_id)
where mission_id is not null;

create index if not exists idx_tester_reports_submission_context
on public.tester_reports(submission_context);

-- 2. Backfill an toàn dữ liệu lịch sử từ mission_settlement_jobs có định danh đủ chắc
update public.tester_reports tr
set
    submission_context = 'mission',
    mission_id = msj.mission_id,
    mission_participant_id = msj.participant_id,
    mission_title = m.title,
    mission_reward_credits = msj.reward_credits,
    mission_ends_at = m.ends_at,
    mission_submitted_at = tr.created_at
from public.mission_settlement_jobs msj
join public.missions m on m.id = msj.mission_id
where tr.id = msj.report_id
  and tr.user_id = msj.tester_id
  and tr.campaign_id = msj.campaign_id
  and tr.submission_context is null;

-- 3. Trigger chặn client tự gắn/sửa metadata nhiệm vụ qua đường ghi bảng trực tiếp
create or replace function public.trig_protect_tester_reports_mission_metadata()
returns trigger
language plpgsql
as $$
begin
    if current_setting('app.submitting_report', true) = 'true' then
        return new;
    end if;

    if current_user not in ('anon', 'authenticated')
       and coalesce(current_setting('request.jwt.claim.role', true), '') not in ('anon', 'authenticated') then
        return new;
    end if;

    if (TG_OP = 'INSERT') then
        if new.submission_context is not null
           or new.mission_id is not null
           or new.mission_participant_id is not null
           or new.mission_title is not null
           or new.mission_reward_credits is not null
           or new.mission_ends_at is not null
           or new.mission_submitted_at is not null then
            raise exception 'CANNOT_SET_MISSION_METADATA_DIRECTLY';
        end if;
    elsif (TG_OP = 'UPDATE') then
        if (new.submission_context is distinct from old.submission_context)
           or (new.mission_id is distinct from old.mission_id)
           or (new.mission_participant_id is distinct from old.mission_participant_id)
           or (new.mission_title is distinct from old.mission_title)
           or (new.mission_reward_credits is distinct from old.mission_reward_credits)
           or (new.mission_ends_at is distinct from old.mission_ends_at)
           or (new.mission_submitted_at is distinct from old.mission_submitted_at) then
            raise exception 'CANNOT_MODIFY_MISSION_METADATA_DIRECTLY';
        end if;
    end if;

    return new;
end;
$$;

drop trigger if exists trg_protect_tester_reports_mission_metadata on public.tester_reports;
create trigger trg_protect_tester_reports_mission_metadata
before insert or update on public.tester_reports
for each row execute function public.trig_protect_tester_reports_mission_metadata();

-- 4. Cập nhật submit_tester_report hỗ trợ tham số mission tùy chọn và lưu metadata
drop function if exists public.submit_tester_report(uuid, text, text, int);
drop function if exists public.submit_tester_report(uuid, text, text, int, text);
drop function if exists public.submit_tester_report(uuid, text, text, int, text, uuid);
drop function if exists public.submit_tester_report(uuid, text, text, int, text, uuid, text);
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

    -- Idempotency: nếu RPC đã commit nhưng response bị mất/timeout, lần retry
    -- trả lại đúng report cũ, không cộng credit lần hai (kể cả retry sau hạn).
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
        raise exception 'ACCOUNT_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- Thống nhất thứ tự khóa: Advisory lock -> Mission -> Campaign member -> Existing report
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    -- Xác minh mission context
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
        -- Khi client không chỉ định mission: tự động phân loại nếu tester có đúng 1 mission đã nhận còn hạn
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

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lấy report hôm nay và khóa dòng
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1
    for update;

    -- Ảnh mới = URL khác ảnh hiện tại. Báo cáo đã duyệt thì không nhận ảnh mới.
    v_new_screenshot := p_screenshot_url is not null
        and (v_existing_report.id is null
             or p_screenshot_url is distinct from v_existing_report.screenshot_url);

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

    -- Xác định effective full screenshot
    v_effective_full := coalesce(p_screenshot_url, v_existing_report.screenshot_url);

    if p_thumbnail_url is not null then
        if v_effective_full is null then
            raise exception 'INVALID_THUMBNAIL_URL';
        end if;
        if not public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, v_effective_full) then
            raise exception 'INVALID_THUMBNAIL_URL';
        end if;
    end if;

    -- Feedback
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (
        p_campaign_id,
        v_user_id,
        case when v_existing_report.status = 'approved'
             then coalesce(v_existing_report.rating, v_rating)
             else v_rating end,
        coalesce(p_content, ''),
        now()
    )
    on conflict (campaign_id, user_id)
    do update set
        rating = case
            when v_existing_report.status = 'approved' then public.feedbacks.rating
            else excluded.rating
        end,
        comment = case
            when coalesce(excluded.comment, '') <> '' then excluded.comment
            else public.feedbacks.comment
        end,
        updated_at = now();

    -- Checkin xác nhận hôm nay
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
    ) into v_already_checked_in;

    -- Cho phép cập nhật/chèn metadata qua session variable
    perform set_config('app.submitting_report', 'true', true);

    if v_existing_report.id is not null then
        -- Trường hợp 1: đã duyệt -> chỉ bổ sung comment, không cho đổi thành mission report
        if v_existing_report.status = 'approved' then
            if v_submission_context = 'mission' and v_existing_report.submission_context <> 'mission' then
                raise exception 'REPORT_ALREADY_APPROVED_AS_APP_TEST';
            end if;
            if v_submission_context = 'mission' and v_existing_report.mission_id is distinct from v_target_mission_id then
                raise exception 'REPORT_ALREADY_APPROVED_FOR_ANOTHER_MISSION';
            end if;

            update public.tester_reports
            set content = case
                when coalesce(p_content, '') <> '' then p_content
                else content
            end,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
              and status = 'approved'
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := 'approved';

        -- Trường hợp 2: bị từ chối -> nộp lại
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
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

            -- Nếu báo cáo bị từ chối vốn là mission report: bắt buộc giữ cùng identity và mission phải còn hạn
            if v_existing_report.submission_context = 'mission' then
                if v_target_mission_id is not null and v_existing_report.mission_id is distinct from v_target_mission_id then
                    raise exception 'CANNOT_SWITCH_REJECTED_REPORT_MISSION';
                end if;

                select * into v_check_m from public.missions where id = v_existing_report.mission_id;
                if v_check_m.id is null or v_check_m.ends_at <= now() or v_check_m.status = 'expired' then
                    raise exception 'MISSION_EXPIRED';
                end if;
                if v_check_m.status not in ('open', 'full') then
                    raise exception 'MISSION_NOT_OPEN';
                end if;

                v_submission_context := 'mission';
                v_target_mission_id := v_existing_report.mission_id;
                v_target_mp_id := v_existing_report.mission_participant_id;
                v_target_title := v_existing_report.mission_title;
                v_target_reward := v_existing_report.mission_reward_credits;
                v_target_ends_at := v_existing_report.mission_ends_at;
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
                v_regrant := least(
                    v_provisional_reward,
                    greatest(
                        0,
                        coalesce(
                            v_existing_report.provisional_clawed,
                            v_existing_report.provisional_granted_credits,
                            2
                        )
                    )
                );

                if v_regrant > 0 then
                    update public.profiles
                    set credits = credits + v_regrant,
                        last_active_date = v_today,
                        missed_streak = 0
                    where id = v_user_id
                    returning credits into v_new_balance;

                    insert into public.credit_logs (user_id, amount, action, note, balance)
                    values (
                        v_user_id,
                        v_regrant,
                        'test_provisional',
                        format('Tạm nhận lại %s Credit sau khi nộp lại kết quả test (chờ chủ app duyệt)', v_regrant),
                        v_new_balance
                    );

                    v_provisional_granted := true;
                    v_provisional_amount := v_regrant;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

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
            end if;

        -- Trường hợp 3: pending -> cho chỉnh sửa ảnh/nội dung, không gắn hồi tố mission nếu trước đó là app_test
        else
            if v_existing_report.submission_context = 'mission' then
                if v_target_mission_id is not null and v_existing_report.mission_id is distinct from v_target_mission_id then
                    raise exception 'CANNOT_CHANGE_MISSION_IDENTITY';
                end if;
            else
                if v_submission_context = 'mission' then
                    raise exception 'CANNOT_CONVERT_EXISTING_PENDING_REPORT';
                end if;
            end if;

            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = case when v_new_screenshot then p_screenshot_url else screenshot_url end,
                thumbnail_url = case
                    when v_new_screenshot then p_thumbnail_url
                    else coalesce(p_thumbnail_url, thumbnail_url)
                end,
                rating = v_rating,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
              and status = v_existing_report.status
              and reviewed_at is not distinct from v_existing_report.reviewed_at
            returning id into v_report_id;

            if v_report_id is null then
                raise exception 'REPORT_ALREADY_REVIEWED';
            end if;
            v_status := v_existing_report.status;
        end if;
    else
        -- Trường hợp 4: chưa có báo cáo hôm nay -> nộp mới
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
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    select
        screenshot_url, image_hash, thumbnail_url,
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at
    into
        v_final_url, v_final_hash, v_final_thumb,
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at
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

-- 5. Cập nhật _settle_mission_reward dùng danh tính đã lưu trên báo cáo
create or replace function public._settle_mission_reward(p_report_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_match record;
    v_job record;
    v_saved_m record;
    v_saved_mp record;
    v_creator_balance int;
    v_tester_balance int;
    v_paid boolean := false;
    v_backoff interval;
begin
    if p_report_id is null then
        return jsonb_build_object('settled', false, 'reason', 'NULL_REPORT_ID');
    end if;

    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        return jsonb_build_object('settled', false, 'reason', 'REPORT_NOT_FOUND');
    end if;

    if v_report.status <> 'approved' then
        return jsonb_build_object('settled', false, 'reason', 'REPORT_NOT_APPROVED');
    end if;

    -- Báo cáo test thường không được xét thưởng nhiệm vụ
    if v_report.submission_context = 'app_test' then
        return jsonb_build_object('settled', false, 'reason', 'NOT_A_MISSION_REPORT');
    end if;

    select * into v_job
    from public.mission_settlement_jobs
    where report_id = p_report_id
    for update;

    if v_job.id is not null and v_job.status in ('completed', 'skipped') then
        return jsonb_build_object('settled', false, 'reason', 'ALREADY_SETTLED', 'job_status', v_job.status);
    end if;

    if v_job.id is not null then
        select * into v_saved_m from public.missions where id = v_job.mission_id for update;
        select * into v_saved_mp from public.mission_participants where id = v_job.participant_id for update;

        if v_saved_m.id is null or v_saved_mp.id is null then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'MISSION_OR_PARTICIPANT_NOT_FOUND',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'TERMINATED_NOT_FOUND');
        end if;

        if v_saved_mp.status <> 'joined' then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'PARTICIPANT_NOT_JOINED',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'TERMINATED_PARTICIPANT_NOT_JOINED');
        end if;

        if v_saved_m.ends_at <= now() or v_saved_m.status in ('expired', 'cancelled', 'deleted', 'stopped_no_credits') then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'MISSION_EXPIRED_OR_TERMINATED',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'TERMINATED_MISSION_EXPIRED');
        end if;

        if v_saved_m.status = 'paused_by_creator' then
            update public.mission_settlement_jobs
            set attempt_count = attempt_count + 1,
                next_attempt_at = now() + interval '5 minutes',
                last_error = 'MISSION_PAUSED_BY_CREATOR',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'MISSION_PAUSED_BY_CREATOR');
        end if;

        select
            v_saved_m.id as mission_id,
            v_saved_m.creator_id,
            v_saved_m.title as mission_title,
            v_job.reward_credits,
            v_job.campaign_id,
            v_saved_mp.id as participant_id,
            v_saved_mp.joined_at
        into v_match;
    else
        -- Lần đầu (chưa có job)
        if v_report.mission_id is not null then
            -- Dữ liệu mới: Dùng chính xác identity nhiệm vụ đã lưu trên báo cáo
            select
                m.id as mission_id,
                m.creator_id,
                m.title as mission_title,
                m.reward_credits,
                m.campaign_id,
                mp.id as participant_id,
                mp.joined_at,
                m.status as mission_status,
                m.ends_at as mission_ends_at,
                mp.status as participant_status
            into v_match
            from public.missions m
            join public.mission_participants mp on mp.mission_id = m.id
            where m.id = v_report.mission_id
              and mp.id = v_report.mission_participant_id
              and mp.user_id = v_report.user_id
              and m.campaign_id = v_report.campaign_id
            for update of m;

            if v_match.mission_id is null then
                return jsonb_build_object('settled', false, 'reason', 'MISSION_OR_PARTICIPANT_NOT_FOUND');
            end if;

            if v_match.participant_status <> 'joined' then
                return jsonb_build_object('settled', false, 'reason', 'PARTICIPANT_NOT_JOINED');
            end if;

            -- Chính sách hiện hành: duyệt sau hạn ends_at <= now() không trả thưởng mission
            if v_match.mission_ends_at <= now() or v_match.mission_status in ('expired', 'cancelled', 'deleted', 'stopped_no_credits') then
                return jsonb_build_object('settled', false, 'reason', 'TERMINATED_MISSION_EXPIRED');
            end if;

            if v_match.mission_status = 'paused_by_creator' then
                return jsonb_build_object('settled', false, 'reason', 'MISSION_PAUSED_BY_CREATOR');
            end if;
        else
            -- Dữ liệu lịch sử (legacy reports chưa có mission_id)
            select
                m.id as mission_id,
                m.creator_id,
                m.title as mission_title,
                m.reward_credits,
                m.campaign_id,
                mp.id as participant_id,
                mp.joined_at
            into v_match
            from public.missions m
            join public.mission_participants mp on mp.mission_id = m.id
            where m.campaign_id = v_report.campaign_id
              and mp.user_id = v_report.user_id
              and mp.status = 'joined'
              and v_report.created_at >= mp.joined_at
              and m.status in ('open', 'full')
              and m.ends_at > now()
            order by m.created_at desc
            limit 1
            for update of m;

            if v_match.mission_id is null then
                return jsonb_build_object('settled', false, 'reason', 'NO_ELIGIBLE_MISSION');
            end if;
        end if;

        insert into public.mission_settlement_jobs (
            report_id, mission_id, participant_id, campaign_id,
            tester_id, creator_id, reward_credits, status,
            next_attempt_at
        ) values (
            v_report.id, v_match.mission_id, v_match.participant_id, v_match.campaign_id,
            v_report.user_id, v_match.creator_id, v_match.reward_credits, 'pending',
            now()
        )
        on conflict (report_id) do update
        set mission_id = excluded.mission_id,
            participant_id = excluded.participant_id,
            updated_at = now()
        returning * into v_job;
    end if;

    begin
        select credits into v_creator_balance
        from public.profiles
        where id = v_match.creator_id
        for update;

        if coalesce(v_creator_balance, 0) >= v_match.reward_credits then
            update public.mission_participants
            set status = 'completed',
                completed_at = now()
            where id = v_match.participant_id
              and status = 'joined';

            if not found then
                update public.mission_settlement_jobs
                set status = 'skipped',
                    skip_reason = 'PARTICIPANT_ALREADY_COMPLETED',
                    updated_at = now()
                where id = v_job.id;

                return jsonb_build_object('settled', false, 'reason', 'ALREADY_COMPLETED');
            end if;

            update public.profiles
            set credits = credits - v_match.reward_credits
            where id = v_match.creator_id
            returning credits into v_creator_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_match.creator_id,
                -v_match.reward_credits,
                'mission_reward',
                format('Trả thưởng nhiệm vụ "%s" cho người tham gia', v_match.mission_title),
                v_creator_balance
            );

            update public.profiles
            set credits = credits + v_match.reward_credits
            where id = v_report.user_id
            returning credits into v_tester_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_report.user_id,
                v_match.reward_credits,
                'mission_reward',
                format('Nhận thưởng nhiệm vụ "%s"', v_match.mission_title),
                v_tester_balance
            );

            update public.mission_settlement_jobs
            set status = 'completed',
                updated_at = now()
            where id = v_job.id;

            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_report.user_id,
                'Nhận thưởng nhiệm vụ 🎉',
                format('Báo cáo của bạn đã được duyệt. Bạn nhận được +%s Credit từ nhiệm vụ "%s"!', v_match.reward_credits, v_match.mission_title),
                'mission',
                jsonb_build_object(
                    'mission_id', v_match.mission_id,
                    'campaign_id', v_match.campaign_id,
                    'report_id', v_report.id,
                    'reward_credits', v_match.reward_credits
                )
            );

            return jsonb_build_object(
                'settled', true,
                'mission_id', v_match.mission_id,
                'reward_credits', v_match.reward_credits,
                'tester_balance', v_tester_balance
            );
        else
            update public.missions
            set status = 'stopped_no_credits'
            where id = v_match.mission_id
              and status in ('open', 'full', 'paused_by_creator');

            update public.mission_settlement_jobs
            set status = 'failed',
                attempt_count = attempt_count + 1,
                last_error = 'INSUFFICIENT_CREATOR_CREDITS',
                next_attempt_at = now() + interval '10 minutes',
                updated_at = now()
            where id = v_job.id;

            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_match.creator_id,
                'Nhiệm vụ bị tạm dừng do hết Credit',
                format('Nhiệm vụ "%s" không đủ Credit trả cho tester. Nhiệm vụ đã tạm dừng.', v_match.mission_title),
                'mission',
                jsonb_build_object(
                    'mission_id', v_match.mission_id,
                    'campaign_id', v_match.campaign_id,
                    'action', 'stopped_no_credits'
                )
            );

            return jsonb_build_object('settled', false, 'reason', 'INSUFFICIENT_CREATOR_CREDITS');
        end if;
    exception when others then
        v_backoff := case
            when coalesce(v_job.attempt_count, 0) < 3 then interval '1 minute'
            when coalesce(v_job.attempt_count, 0) < 6 then interval '5 minutes'
            else interval '30 minutes'
        end;

        update public.mission_settlement_jobs
        set status = 'failed',
            attempt_count = attempt_count + 1,
            last_error = sqlerrm,
            next_attempt_at = now() + v_backoff,
            updated_at = now()
        where id = v_job.id;

        return jsonb_build_object('settled', false, 'reason', 'ERROR', 'error', sqlerrm);
    end;
end;
$$;

revoke execute on function public._settle_mission_reward(uuid) from public, anon, authenticated;
grant execute on function public._settle_mission_reward(uuid) to service_role;

-- 6. Cập nhật get_open_missions lọc hết hạn trước ORDER BY/LIMIT và trả server_now
create or replace function public.get_open_missions()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_now timestamptz := now();
begin
    -- Đồng bộ trạng thái hết hạn trong DB
    update public.missions
    set status = 'expired'
    where status in ('open', 'full', 'paused_by_creator')
      and ends_at <= v_now;

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
                order by t.sort_mine desc, t.sort_active desc, t.created_at desc, t.id desc
            )
            from (
                select
                    m.id, m.title, m.description, m.reward_credits,
                    m.max_participants, m.participant_count, m.status,
                    m.ends_at, m.created_at, m.campaign_id, m.creator_id,
                    c.app_name, c.icon_url, c.package_name,
                    coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name,
                    case
                        when m.creator_id = v_user_id then 1
                        when exists (
                            select 1 from public.mission_participants mp_mine
                            where mp_mine.mission_id = m.id and mp_mine.user_id = v_user_id
                        ) then 1
                        else 0
                    end as sort_mine,
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
                where m.status in ('open', 'full', 'paused_by_creator')
                  and m.ends_at > v_now
                order by
                    case
                        when m.creator_id = v_user_id then 1
                        when exists (
                            select 1 from public.mission_participants mp_mine
                            where mp_mine.mission_id = m.id and mp_mine.user_id = v_user_id
                        ) then 1
                        else 0
                    end desc,
                    case m.status
                        when 'open' then 2
                        when 'full' then 2
                        when 'paused_by_creator' then 1
                        else 0
                    end desc,
                    m.created_at desc,
                    m.id desc
                limit 100
            ) t
        ), '[]'::jsonb),
        'server_now', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$$;

revoke execute on function public.get_open_missions() from public, anon;
grant execute on function public.get_open_missions() to authenticated, service_role;

-- 7. Cập nhật get_my_active_mission_campaign_ids lọc ends_at > now()
create or replace function public.get_my_active_mission_campaign_ids()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    update public.missions
    set status = 'expired'
    where creator_id = v_user_id
      and status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    return coalesce((
        select jsonb_agg(campaign_id)
        from public.missions
        where creator_id = v_user_id
          and status in ('open', 'full', 'paused_by_creator')
          and ends_at > now()
    ), '[]'::jsonb);
end;
$$;

revoke execute on function public.get_my_active_mission_campaign_ids() from public, anon;
grant execute on function public.get_my_active_mission_campaign_ids() to authenticated, service_role;

-- 8. Cập nhật get_campaign_reports_for_owner trả metadata nhiệm vụ và thumbnail
create or replace function public.get_campaign_reports_for_owner(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_res jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role into v_role from public.profiles where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    perform public.auto_accept_expired_reports();

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
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
                'tester_name', coalesce(p.display_name, split_part(coalesce(p.email, ''), '@', 1), 'Tester'),
                'tester_avatar', p.avatar_url,
                'tester_credits', p.credits,
                'submission_context', tr.submission_context,
                'mission_id', tr.mission_id,
                'mission_participant_id', tr.mission_participant_id,
                'mission_title', tr.mission_title,
                'mission_reward_credits', tr.mission_reward_credits,
                'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            )
            order by tr.created_at desc
        ),
        '[]'::jsonb
    ) into v_res
    from public.tester_reports tr
    left join public.profiles p on p.id = tr.user_id
    where tr.campaign_id = p_campaign_id;

    return v_res;
end;
$$;

revoke execute on function public.get_campaign_reports_for_owner(uuid) from public, anon;
grant execute on function public.get_campaign_reports_for_owner(uuid) to authenticated, service_role;

notify pgrst, 'reload schema';
