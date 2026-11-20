-- 076_mission_for_finalized_campaigns.sql
-- Extend create_mission, join_mission, resume_mission, _submit_tester_report_core,
-- and trg_reports_require_open_campaign to allow finalized campaigns ('production' and 'completed')
-- in addition to 'open' strictly for mission flows.
-- Normal testing submission ('app_test') remains strictly 'open' only.

-- 1. create_mission
CREATE OR REPLACE FUNCTION public.create_mission(
    p_campaign_id uuid,
    p_title text,
    p_description text DEFAULT ''::text,
    p_max_participants integer DEFAULT 10,
    p_duration_days integer DEFAULT 3,
    p_reward_credits integer DEFAULT 10
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_camp record;
    v_balance int;
    v_fee int;
    v_ends_at timestamptz;
    v_mission_id uuid;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if nullif(trim(coalesce(p_title, '')), '') is null then
        raise exception 'MISSION_TITLE_REQUIRED';
    end if;

    if p_max_participants is null or p_max_participants < 1 or p_max_participants > 50 then
        raise exception 'INVALID_PARTICIPANTS';
    end if;

    if p_duration_days is null or p_duration_days < 1 or p_duration_days > 7 then
        raise exception 'INVALID_DURATION';
    end if;

    if p_reward_credits is null or p_reward_credits < 10 then
        raise exception 'INVALID_REWARD';
    end if;

    -- Khóa campaign row để serialize các request tạo mission đồng thời
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Cho phép app 'open' hoặc đã hoàn thành kiểm thử ('production', 'completed')
    if v_camp.status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- M06: Đồng bộ mission hết hạn của campaign này dưới khóa trước khi kiểm tra trùng
    update public.missions
    set status = 'expired'
    where campaign_id = p_campaign_id
      and status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    -- M02: open, full, paused_by_creator đều chiếm vị trí nhiệm vụ
    if exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and status in ('open', 'full', 'paused_by_creator')
    ) then
        raise exception 'MISSION_ALREADY_EXISTS';
    end if;

    v_fee := 50 * p_duration_days;

    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    if v_balance is null or v_balance < 300 then
        raise exception 'INSUFFICIENT_MIN_CREDITS';
    end if;

    if v_balance < v_fee then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    v_ends_at := now() + make_interval(days => p_duration_days);

    insert into public.missions (
        creator_id, campaign_id, title, description,
        max_participants, duration_days, reward_credits, fee_credits,
        status, ends_at
    ) values (
        v_user_id, p_campaign_id, trim(p_title), coalesce(p_description, ''),
        p_max_participants, p_duration_days, p_reward_credits, v_fee,
        'open', v_ends_at
    )
    returning id into v_mission_id;

    update public.profiles
    set credits = credits - v_fee
    where id = v_user_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_user_id,
        -v_fee,
        'mission_fee',
        format('Mở nhiệm vụ "%s" (%s ngày × 50 Credit phí nền tảng)', trim(p_title), p_duration_days),
        v_balance
    );

    return jsonb_build_object(
        'success', true,
        'mission_id', v_mission_id,
        'fee', v_fee,
        'balance', v_balance,
        'ends_at', to_char(v_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.create_mission(uuid, text, text, integer, integer, integer) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.create_mission(uuid, text, text, integer, integer, integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.create_mission(uuid, text, text, integer, integer, integer) TO service_role;


-- 2. join_mission
CREATE OR REPLACE FUNCTION public.join_mission(p_mission_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
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

    if v_mission.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id = v_user_id then
        raise exception 'CANNOT_JOIN_OWN_MISSION';
    end if;

    if v_mission.status not in ('open', 'full') then
        raise exception 'MISSION_NOT_OPEN';
    end if;

    if v_mission.ends_at <= now() then
        raise exception 'MISSION_EXPIRED';
    end if;

    if v_mission.participant_count >= v_mission.max_participants then
        raise exception 'MISSION_FULL';
    end if;

    if exists (
        select 1 from public.mission_participants
        where mission_id = p_mission_id and user_id = v_user_id
    ) then
        raise exception 'ALREADY_JOINED';
    end if;

    select status into v_camp_status from public.campaigns where id = v_mission.campaign_id;
    if v_camp_status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp_status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    insert into public.mission_participants (mission_id, user_id)
    values (p_mission_id, v_user_id);

    v_new_status := case when v_mission.participant_count + 1 >= v_mission.max_participants
                         then 'full' else v_mission.status end;

    update public.missions
    set participant_count = participant_count + 1,
        status = v_new_status
    where id = p_mission_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_mission.creator_id,
        'Người tham gia nhiệm vụ mới 🎯',
        format('"%s" vừa nhận nhiệm vụ "%s". Hãy sẵn sàng duyệt báo cáo test để trả thưởng.',
               coalesce((select display_name from public.profiles where id = v_user_id), 'Một người dùng'),
               v_mission.title),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'campaign_id', v_mission.campaign_id)
    );

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'campaign_id', v_mission.campaign_id,
        'status', v_new_status
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.join_mission(uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.join_mission(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.join_mission(uuid) TO service_role;


-- 3. resume_mission
CREATE OR REPLACE FUNCTION public.resume_mission(p_mission_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
    v_camp record;
    v_new_status text;
begin
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

    -- Bảo toàn sửa 043: nếu đã hết hạn, cập nhật DB và trả kết quả JSON, không rollback UPDATE
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

    -- Khóa campaign row
    select * into v_camp
    from public.campaigns
    where id = v_mission.campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp.status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- M02: Đồng bộ hết hạn trên campaign trước khi kiểm tra xung đột
    update public.missions
    set status = 'expired'
    where campaign_id = v_mission.campaign_id
      and id <> p_mission_id
      and status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    if exists (
        select 1 from public.missions
        where campaign_id = v_mission.campaign_id
          and id <> p_mission_id
          and status in ('open', 'full')
    ) then
        raise exception 'MISSION_ALREADY_EXISTS';
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
$function$;

REVOKE ALL ON FUNCTION public.resume_mission(uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.resume_mission(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.resume_mission(uuid) TO service_role;


-- 4. _submit_tester_report_core
-- Restored exact 074 body with ONLY the context-aware campaign status check modified near line 422.
CREATE OR REPLACE FUNCTION public._submit_tester_report_core(
    p_submission_context text,
    p_campaign_id uuid,
    p_content text,
    p_rating integer,
    p_client_submit_id uuid,
    p_screenshot_url text,
    p_image_hash text,
    p_thumbnail_url text,
    p_mission_id uuid,
    p_attachments jsonb DEFAULT NULL,
    p_expected_date date DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_report_id uuid;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp record;
    v_existing_report record;
    v_idempotent_report record;
    v_receipt record;
    v_fingerprint text;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_provisional_amount int := 0;
    v_provisional_reward int := 10;
    v_policy public.business_policies;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_effective_full text;
    v_final_url text;
    v_final_hash text;
    v_final_thumb text;

    -- Mission context variables
    v_mission record;
    v_mp record;
    v_submission_context text;
    v_target_mission_id uuid := null;
    v_target_mp_id uuid := null;
    v_target_title text := null;
    v_target_reward int := null;
    v_target_ends_at timestamptz := null;
    v_res_context text;
    v_res_mission_id uuid;
    v_res_title text;
    v_res_reward int;
    v_res_ends_at timestamptz;
    v_result jsonb;

    -- Attachments variables
    v_att_canonical text := '';
    v_att_count int := 0;
    v_att_item jsonb;
    v_att_elem record;
    v_attachments_json jsonb := '[]'::jsonb;
    v_pos int;
begin
    v_policy := public._get_effective_business_policy_row(v_today);
    v_provisional_reward := v_policy.report_provisional_reward;

    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Strict explicit submission context
    if p_submission_context is null or p_submission_context not in ('app_test', 'mission')
       or (p_submission_context = 'mission') <> (p_mission_id is not null) then
        raise exception 'INVALID_REPORT_CONTEXT';
    end if;
    v_submission_context := p_submission_context;

    -- Precondition: if expected_date is supplied, check whether today matches
    -- (committed replay is allowed to pass even if across date)
    if p_client_submit_id is not null then
        perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    end if;

    if p_client_submit_id is not null and exists(select 1 from public.report_cancelled_requests where user_id=v_user_id and client_submit_id=p_client_submit_id) then
        raise exception 'REPORT_REQUEST_REQUIRES_REVIEW';
    end if;

    -- Validate attachments array if provided
    if p_attachments is not null then
        if p_client_submit_id is null then
            raise exception 'CLIENT_SUBMIT_ID_REQUIRED';
        end if;
        if jsonb_typeof(p_attachments) <> 'array' then
            raise exception 'ATTACHMENTS_MUST_BE_ARRAY';
        end if;
        v_att_count := jsonb_array_length(p_attachments);
        if v_att_count > 3 then
            raise exception 'TOO_MANY_ATTACHMENTS';
        end if;
        if v_att_count < 1 then
            raise exception 'ATTACHMENTS_EMPTY: attachments array cannot be empty';
        end if;

        -- Verify positions are contiguous 0..count-1 and validate each attachment
        for v_pos in 0 .. (v_att_count - 1) loop
            v_att_item := p_attachments->v_pos;
            if coalesce((v_att_item->>'position')::int, -1) <> v_pos then
                raise exception 'ATTACHMENT_POSITION_INVALID';
            end if;
            if v_att_item->>'screenshot_url' is null or btrim(v_att_item->>'screenshot_url') = '' then
                raise exception 'SCREENSHOT_URL_REQUIRED';
            end if;
            if not public.is_valid_report_screenshot_url(v_att_item->>'screenshot_url', p_campaign_id, v_user_id) then
                raise exception 'INVALID_SCREENSHOT_URL';
            end if;
            if v_att_item->>'image_hash' is null or btrim(v_att_item->>'image_hash') = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            if not (lower(v_att_item->>'image_hash') ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
            if v_att_item->>'thumbnail_url' is not null and btrim(v_att_item->>'thumbnail_url') <> '' then
                if not public.is_valid_report_thumbnail_url(v_att_item->>'thumbnail_url', p_campaign_id, v_user_id, v_att_item->>'screenshot_url') then
                    raise exception 'INVALID_THUMBNAIL_URL';
                end if;
            end if;
        end loop;

        select jsonb_agg(jsonb_build_object(
            'id', att->>'id',
            'position', (att->>'position')::int,
            'screenshot_url', att->>'screenshot_url',
            'thumbnail_url', att->>'thumbnail_url',
            'image_hash', lower(att->>'image_hash'),
            'sha256', att->>'sha256'
        ) order by (att->>'position')::int)::text
        into v_att_canonical
        from jsonb_array_elements(p_attachments) as att;
    end if;

    -- Validate single screenshot if provided without attachments
    if p_attachments is null and p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
        if not public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) then
            raise exception 'INVALID_SCREENSHOT_URL';
        end if;
        if p_image_hash is not null and btrim(p_image_hash) <> '' then
            if not (lower(p_image_hash) ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
        end if;
        if p_thumbnail_url is not null and btrim(p_thumbnail_url) <> '' then
            if not public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, p_screenshot_url) then
                raise exception 'INVALID_THUMBNAIL_URL';
            end if;
        end if;
    end if;

    -- Compute payload fingerprint
    if p_attachments is not null then
        v_fingerprint := encode(digest(format('v3|%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            v_submission_context,
            coalesce(p_mission_id::text, ''),
            coalesce(p_content, ''),
            v_rating,
            coalesce(v_att_canonical, ''),
            coalesce(p_expected_date::text, '')
        ), 'sha256'), 'hex');
    else
        v_fingerprint := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            coalesce(p_content, ''),
            coalesce(p_screenshot_url, ''),
            coalesce(p_rating, 5),
            coalesce(lower(p_image_hash), ''),
            coalesce(p_thumbnail_url, ''),
            coalesce(p_mission_id::text, '')
        ), 'sha256'), 'hex');
    end if;

    -- Idempotency check via receipt ledger
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;

            if v_receipt.provenance = 'legacy_backfill' then
                declare
                    v_resp_mission text := coalesce(v_receipt.response_json ->> 'mission_id', '');
                    v_resp_url text := coalesce(v_receipt.response_json ->> 'screenshot_url', '');
                    v_resp_hash text := coalesce(lower(v_receipt.response_json ->> 'image_hash'), '');
                    v_resp_thumb text := coalesce(v_receipt.response_json ->> 'thumbnail_url', '');
                    v_resp_status text := coalesce(v_receipt.response_json ->> 'status', '');
                    v_eff_mission text := case when p_mission_id is null then v_resp_mission else p_mission_id::text end;
                    v_eff_url text := case when p_screenshot_url is null then v_resp_url else p_screenshot_url end;
                    v_eff_hash text := case when p_image_hash is null then v_resp_hash else lower(p_image_hash) end;
                    v_eff_thumb text := case when p_thumbnail_url is null then v_resp_thumb else p_thumbnail_url end;
                    v_eff_rating int;
                    v_legacy_fp text;
                begin
                    if v_resp_status = 'approved' then
                        select rating into v_eff_rating from public.tester_reports where id = v_receipt.report_id;
                        if v_eff_rating is null then
                            v_eff_rating := coalesce((v_receipt.response_json ->> 'rating')::int, 5);
                        end if;
                    else
                        v_eff_rating := coalesce(p_rating, 5);
                    end if;

                    if (p_screenshot_url is null or p_screenshot_url = v_resp_url)
                       and (p_image_hash is null or lower(p_image_hash) = v_resp_hash)
                       and (p_thumbnail_url is null or p_thumbnail_url = v_resp_thumb)
                       and (p_mission_id is null or p_mission_id::text = v_resp_mission)
                       and (v_resp_status = 'approved' or p_rating is null or p_rating = v_eff_rating)
                    then
                        v_legacy_fp := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                            p_campaign_id,
                            coalesce(p_content, ''),
                            v_eff_url,
                            v_eff_rating,
                            v_eff_hash,
                            v_eff_thumb,
                            v_eff_mission
                        ), 'sha256'), 'hex');

                        if v_receipt.payload_fingerprint = v_legacy_fp then
                            return v_receipt.response_json;
                        end if;
                    end if;
                end;
            end if;

            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;

        -- Fallback check tester_reports for legacy submissions
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
            if coalesce(v_idempotent_report.content, '') <> coalesce(p_content, '') then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_rating is not null and v_idempotent_report.rating is not null and v_idempotent_report.rating <> v_rating then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_image_hash is not null and v_idempotent_report.image_hash is not null and lower(v_idempotent_report.image_hash) <> lower(p_image_hash) then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_screenshot_url is not null and v_idempotent_report.screenshot_url is not null and v_idempotent_report.screenshot_url <> p_screenshot_url then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;

            -- Attachments from tester_report_attachments or fallback
            select coalesce(jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256
            ) order by a.position), '[]'::jsonb) into v_attachments_json
            from public.tester_report_attachments a
            where a.report_id = v_idempotent_report.id;

            if jsonb_array_length(v_attachments_json) = 0 and v_idempotent_report.screenshot_url is not null then
                v_attachments_json := jsonb_build_array(jsonb_build_object(
                    'id', v_idempotent_report.id,
                    'position', 0,
                    'screenshot_url', v_idempotent_report.screenshot_url,
                    'thumbnail_url', v_idempotent_report.thumbnail_url,
                    'image_hash', v_idempotent_report.image_hash,
                    'sha256', null
                ));
            end if;

            v_result := jsonb_build_object(
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
                'mission_ends_at', to_char(v_idempotent_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'rating', v_idempotent_report.rating,
                'report_date', to_char(v_idempotent_report.report_date, 'YYYY-MM-DD'),
                'attachments', v_attachments_json
            );

            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_idempotent_report.id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;

            return v_result;
        end if;
    end if;

    -- Date precondition check for uncommitted submissions
    if p_expected_date is not null and p_expected_date <> v_today then
        raise exception 'REPORT_DATE_MISMATCH: Ngay bao cao du kien khong khop voi may chu';
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

    -- Mission context allows open and finalized campaigns ('production', 'completed').
    -- Normal app_test context strictly requires 'open'.
    if v_submission_context = 'mission' then
        if v_camp.status not in ('open', 'production', 'completed') then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    else
        if v_camp.status <> 'open' then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    end if;

    -- Lock contextually by (user, campaign, context, mission, date)
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s:%s:%s', v_user_id, p_campaign_id, v_submission_context, coalesce(p_mission_id::text, ''), v_today), 0)
    );

    -- Re-check receipt ledger under lock
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;
            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;
    end if;

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lookup existing report for today strictly within the same context and mission
    if v_submission_context = 'mission' then
        select * into v_existing_report
        from public.tester_reports
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and submission_context = 'mission'
          and mission_id = p_mission_id
          and report_date = v_today
        order by created_at desc
        limit 1
        for update;
    else
        select * into v_existing_report
        from public.tester_reports
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and submission_context = 'app_test'
          and report_date = v_today
        order by created_at desc
        limit 1
        for update;
    end if;

    -- Backfill legacy receipt before client_submit_id gets overwritten
    if v_existing_report.id is not null
       and v_existing_report.client_submit_id is not null
       and v_existing_report.client_submit_id is distinct from p_client_submit_id then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id,
            v_existing_report.client_submit_id,
            p_campaign_id,
            v_existing_report.id,
            encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                p_campaign_id,
                coalesce(v_existing_report.content, ''),
                coalesce(v_existing_report.screenshot_url, ''),
                coalesce(v_existing_report.rating, 5),
                coalesce(lower(v_existing_report.image_hash), ''),
                coalesce(v_existing_report.thumbnail_url, ''),
                coalesce(v_existing_report.mission_id::text, '')
            ), 'sha256'), 'hex'),
            jsonb_build_object(
                'success', true,
                'report_id', v_existing_report.id,
                'status', v_existing_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_existing_report.screenshot_url,
                'image_hash', v_existing_report.image_hash,
                'thumbnail_url', v_existing_report.thumbnail_url,
                'submission_context', v_existing_report.submission_context,
                'mission_id', v_existing_report.mission_id,
                'mission_title', v_existing_report.mission_title,
                'mission_reward_credits', v_existing_report.mission_reward_credits,
                'mission_ends_at', to_char(v_existing_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'rating', v_existing_report.rating,
                'report_date', to_char(v_existing_report.report_date, 'YYYY-MM-DD')
            ),
            v_existing_report.created_at,
            'legacy_backfill'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    -- Disallow mutating an existing report to another context or mission
    if v_existing_report.id is not null and (
        v_existing_report.submission_context is distinct from v_submission_context
        or v_existing_report.mission_id is distinct from p_mission_id
    ) then
        raise exception 'REPORT_CONTEXT_CONFLICT';
    end if;

    -- TH1: REPORT IS APPROVED -> comment update only
    if v_existing_report.id is not null and v_existing_report.status = 'approved' then
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;
        if p_screenshot_url is not null and p_screenshot_url <> v_existing_report.screenshot_url then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;

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
            image_hash, thumbnail_url, report_date
        into
            v_res_context, v_res_mission_id, v_res_title,
            v_res_reward, v_res_ends_at, v_final_url,
            v_final_hash, v_final_thumb, v_today
        from public.tester_reports
        where id = v_report_id;

        -- Attachments
        select coalesce(jsonb_agg(jsonb_build_object(
            'id', a.id,
            'position', a.position,
            'screenshot_url', a.screenshot_url,
            'thumbnail_url', a.thumbnail_url,
            'image_hash', a.image_hash,
            'sha256', a.sha256
        ) order by a.position), '[]'::jsonb) into v_attachments_json
        from public.tester_report_attachments a
        where a.report_id = v_report_id;

        if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
            v_attachments_json := jsonb_build_array(jsonb_build_object(
                'id', v_report_id,
                'position', 0,
                'screenshot_url', v_final_url,
                'thumbnail_url', v_final_thumb,
                'image_hash', v_final_hash,
                'sha256', null
            ));
        end if;

        v_result := jsonb_build_object(
            'success', true,
            'report_id', v_report_id,
            'status', v_status,
            'provisional_granted', false,
            'provisional_amount', 0,
            'screenshot_url', v_final_url,
            'image_hash', v_final_hash,
            'thumbnail_url', v_final_thumb,
            'submission_context', v_res_context,
            'mission_id', v_res_mission_id,
            'mission_title', v_res_title,
            'mission_reward_credits', v_res_reward,
            'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'rating', coalesce(v_existing_report.rating, v_rating),
            'report_date', to_char(v_today, 'YYYY-MM-DD'),
            'attachments', v_attachments_json
        );

        if p_client_submit_id is not null then
            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;
        end if;

        return v_result;
    end if;

    -- TH2: EXISTING REPORT IS PENDING OR REJECTED
    if v_existing_report.id is not null then
        if v_existing_report.submission_context = 'mission' then
            if p_mission_id is not null and v_existing_report.mission_id is distinct from p_mission_id then
                raise exception 'CANNOT_CHANGE_MISSION_IDENTITY';
            end if;
            v_submission_context := 'mission';
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;

            if v_existing_report.status = 'rejected' then
                if v_target_ends_at is not null and v_target_ends_at < now() then
                    raise exception 'MISSION_EXPIRED';
                end if;
            end if;
        else
            if p_mission_id is not null then
                raise exception 'CANNOT_CONVERT_EXISTING_PENDING_REPORT';
            end if;
            v_submission_context := 'app_test';
        end if;

        -- Image resolution for pending/rejected update
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_new_screenshot := true;
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
            v_final_thumb := p_attachments->0->>'thumbnail_url';

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = v_effective_full then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_new_screenshot := true;
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_hash := lower(p_image_hash);
            v_final_thumb := p_thumbnail_url;

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = p_screenshot_url then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        else
            v_effective_full := v_existing_report.screenshot_url;
            v_final_hash := v_existing_report.image_hash;
            v_final_thumb := v_existing_report.thumbnail_url;
            if v_existing_report.status = 'rejected' then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_existing_report.status = 'rejected' then
            v_regrant := coalesce(v_existing_report.provisional_granted_credits, v_provisional_reward);
            if v_existing_report.provisional_clawed > 0 then
                update public.profiles
                set credits = credits + v_regrant,
                    last_active_date = v_today,
                    missed_streak = 0
                where id = v_user_id
                returning credits into v_new_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_user_id, v_regrant, 'test_provisional',
                    format('Tạm cấp lại %s Credit sau khi nộp lại kết quả test', v_regrant),
                    v_new_balance
                );

                v_provisional_granted := true;
                v_provisional_amount := v_regrant;
            else
                v_provisional_granted := false;
                v_provisional_amount := 0;
            end if;

            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                status = 'pending',
                rejection_reason = null,
                reviewed_at = null,
                provisional_clawed = 0,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                created_at = now(),
                mission_submitted_at = case when v_submission_context = 'mission' then now() else mission_submitted_at end
            where id = v_existing_report.id
            returning id into v_report_id;
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Update attachments table atomically if attachments payload provided
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            delete from public.tester_report_attachments where report_id = v_report_id;
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        elsif v_new_screenshot then
            -- Legacy v2 or single-screenshot update: synchronize position 0 in tester_report_attachments
            -- while retaining all secondary attachments (positions 1, 2) intact
            if exists (select 1 from public.tester_report_attachments where report_id = v_report_id and position = 0) then
                update public.tester_report_attachments
                set screenshot_url = v_effective_full,
                    thumbnail_url = v_final_thumb,
                    image_hash = v_final_hash,
                    sha256 = null,
                    created_at = now()
                where report_id = v_report_id and position = 0;
            else
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    gen_random_uuid(),
                    v_report_id,
                    0,
                    v_effective_full,
                    v_final_thumb,
                    v_final_hash,
                    null,
                    now()
                );
            end if;
        end if;

        v_status := 'pending';
    else
        -- TH3: BRAND NEW REPORT FOR TODAY
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_thumb := p_attachments->0->>'thumbnail_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_thumb := p_thumbnail_url;
            v_final_hash := lower(p_image_hash);
        else
            raise exception 'SCREENSHOT_URL_REQUIRED';
        end if;

        if v_submission_context = 'mission' then
            select * into v_mission from public.missions
            where id = p_mission_id and campaign_id = p_campaign_id;

            if v_mission.id is null then
                raise exception 'MISSION_NOT_FOUND';
            end if;
            if v_mission.status not in ('open', 'full') then
                raise exception '%', case v_mission.status
                    when 'paused_by_creator' then 'MISSION_PAUSED'
                    when 'stopped_no_credits' then 'MISSION_STOPPED_NO_CREDITS'
                    when 'expired' then 'MISSION_EXPIRED'
                    when 'deleted' then 'MISSION_DELETED'
                    else 'MISSION_NOT_OPEN' end;
            end if;
            if v_mission.ends_at < now() then
                raise exception 'MISSION_EXPIRED';
            end if;

            select * into v_mp from public.mission_participants
            where mission_id = v_mission.id and user_id = v_user_id;

            if v_mp.id is null then
                raise exception 'MISSION_NOT_JOINED';
            end if;

            if v_mp.joined_at > now() then
                raise exception 'MISSION_NOT_JOINED';
            end if;

            v_target_mission_id := v_mission.id;
            v_target_mp_id := v_mp.id;
            v_target_title := v_mission.title;
            v_target_reward := v_mission.reward_credits;
            v_target_ends_at := v_mission.ends_at;
        else
            v_target_mission_id := null;
            v_target_mp_id := null;
            v_target_title := null;
            v_target_reward := null;
            v_target_ends_at := null;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_submission_context = 'app_test' then
            select exists (
                select 1 from public.checkins
                where campaign_id = p_campaign_id
                  and user_id = v_user_id
                  and checkin_date = v_today
            ) into v_already_checked_in;

            if v_already_checked_in then
                insert into public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
                )
                returning id into v_report_id;

                v_status := 'pending';
                v_provisional_granted := false;
                v_provisional_amount := 0;
            else
                if v_provisional_reward > 0 then
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

                    v_provisional_granted := true;
                    v_provisional_amount := v_provisional_reward;
                else
                    v_provisional_granted := false;
                    v_provisional_amount := 0;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                insert into public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', v_provisional_amount, 0, v_provisional_amount,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
                )
                returning id into v_report_id;

                v_status := 'pending';
            end if;
        else
            -- Mission report does not consume or conflict with ordinary app_test daily checkin
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                review_tester_bonus, review_owner_bonus, business_policy_id, report_date
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                v_final_hash, p_client_submit_id, now(),
                'mission', v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at, now(),
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Insert attachments into tester_report_attachments
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        else
            -- Single attachment row from single-image submission for uniform internal state
            insert into public.tester_report_attachments (
                id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
            ) values (
                gen_random_uuid(),
                v_report_id,
                0,
                v_effective_full,
                v_final_thumb,
                v_final_hash,
                null,
                now()
            );
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today, missed_streak = 0
    where id = v_user_id;

    select
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, screenshot_url,
        image_hash, thumbnail_url, report_date
    into
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_final_url,
        v_final_hash, v_final_thumb, v_today
    from public.tester_reports
    where id = v_report_id;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', a.id,
        'position', a.position,
        'screenshot_url', a.screenshot_url,
        'thumbnail_url', a.thumbnail_url,
        'image_hash', a.image_hash,
        'sha256', a.sha256
    ) order by a.position), '[]'::jsonb) into v_attachments_json
    from public.tester_report_attachments a
    where a.report_id = v_report_id;

    if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
        v_attachments_json := jsonb_build_array(jsonb_build_object(
            'id', v_report_id,
            'position', 0,
            'screenshot_url', v_final_url,
            'thumbnail_url', v_final_thumb,
            'image_hash', v_final_hash,
            'sha256', null
        ));
    end if;

    v_result := jsonb_build_object(
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
        'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'rating', case
            when v_existing_report.id is not null and v_existing_report.status = 'approved' then coalesce(v_existing_report.rating, v_rating)
            else v_rating
        end,
        'report_date', to_char(v_today, 'YYYY-MM-DD'),
        'attachments', v_attachments_json
    );

    if p_client_submit_id is not null then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
            v_fingerprint, v_result, now(), 'exact_v1'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    return v_result;
end;
$function$;

REVOKE ALL ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) TO service_role;


-- 5. trg_reports_require_open_campaign
-- Permit finalized campaigns ('production', 'completed') strictly when:
-- 1. Trusted submit RPC set the transaction-local marker app.submitting_report = 'true'
-- 2. Submission context is explicitly 'mission'
-- 3. Both mission_id and mission_participant_id are present (not null)
-- Normal testing submission ('app_test') and direct inserts remain strictly 'open' only.
CREATE OR REPLACE FUNCTION public.trg_reports_require_open_campaign()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_status text;
begin
    select status into v_status from public.campaigns where id = new.campaign_id;
    if v_status is null then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    if v_status = 'open' then
        return new;
    end if;

    if v_status in ('production', 'completed') then
        if coalesce(current_setting('app.submitting_report', true), '') = 'true'
           and new.submission_context = 'mission'
           and new.mission_id is not null
           and new.mission_participant_id is not null then
            return new;
        end if;
    end if;

    raise exception 'CAMPAIGN_NOT_OPEN';
end;
$function$;

REVOKE ALL ON FUNCTION public.trg_reports_require_open_campaign() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.trg_reports_require_open_campaign() TO PUBLIC, anon, authenticated, service_role;
