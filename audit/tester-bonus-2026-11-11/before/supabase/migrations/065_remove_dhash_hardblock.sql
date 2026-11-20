-- Remove global dHash hard-block while preserving photo requirements, media integrity, context & idempotency.
begin;

CREATE OR REPLACE FUNCTION public.submit_tester_report_internal_v2(
    p_campaign_id uuid,
    p_content text DEFAULT ''::text,
    p_screenshot_url text DEFAULT NULL::text,
    p_rating integer DEFAULT 5,
    p_image_hash text DEFAULT NULL::text,
    p_client_submit_id uuid DEFAULT NULL::uuid,
    p_thumbnail_url text DEFAULT NULL::text,
    p_mission_id uuid DEFAULT NULL::uuid
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
    v_result jsonb;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    if exists(select 1 from public.report_cancelled_requests where user_id=v_user_id and client_submit_id=p_client_submit_id) then
        raise exception 'REPORT_REQUEST_REQUIRES_REVIEW';
    end if;
    -- Tính payload fingerprint
    v_fingerprint := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
        p_campaign_id,
        coalesce(p_content, ''),
        coalesce(p_screenshot_url, ''),
        coalesce(p_rating, 5),
        coalesce(lower(p_image_hash), ''),
        coalesce(p_thumbnail_url, ''),
        coalesce(p_mission_id::text, '')
    ), 'sha256'), 'hex');

    -- Idempotency check qua durable receipt ledger
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

            -- Nếu receipt là legacy_backfill: hỗ trợ semantics legacy an toàn (B01, B02, P02)
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
                    -- Với báo cáo đã duyệt (approved): thao tác bổ sung comment giữ nguyên rating đã khóa
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

        -- Fallback check tester_reports cho legacy submissions
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
                'rating', v_idempotent_report.rating
            );

            -- Ghi vào receipt ledger để các lần retry tiếp theo dùng thẳng receipt
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

    -- Re-check idempotency sau khi đã nắm giữ khóa để loại trừ race condition
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
                    -- Với báo cáo đã duyệt (approved): thao tác bổ sung comment giữ nguyên rating đã khóa
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
    end if;

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

    -- N04: Bảo toàn dấu yêu cầu legacy/trước đó của report trước khi client_submit_id bị ghi đè
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
                'rating', v_existing_report.rating
            ),
            v_existing_report.created_at,
            'legacy_backfill'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    if v_existing_report.id is not null and (
        v_existing_report.submission_context is distinct from case when p_mission_id is null then 'app_test' else 'mission' end
        or v_existing_report.mission_id is distinct from p_mission_id
    ) then raise exception 'REPORT_CONTEXT_CONFLICT'; end if;
    -- TH1: BÁO CÁO ĐÃ DUYỆT (approved) -> Chỉ cho phép bổ sung comment, không phát sinh thưởng
    if v_existing_report.id is not null and v_existing_report.status = 'approved' then
        if p_mission_id is not null then
            if v_existing_report.submission_context <> 'mission' then
                raise exception 'REPORT_ALREADY_APPROVED_AS_APP_TEST';
            end if;
            if v_existing_report.mission_id is distinct from p_mission_id then
                raise exception 'REPORT_ALREADY_APPROVED_FOR_ANOTHER_MISSION';
            end if;
            v_submission_context := 'mission';
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
        else
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
            'rating', coalesce(v_existing_report.rating, v_rating)
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

    -- TH2: ĐÃ CÓ BÁO CÁO PENDING HOẶC REJECTED TRONG NGÀY
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

        if p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_new_screenshot := true;
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;

            -- Nếu nộp lại báo cáo bị từ chối, bắt buộc ảnh mới khác với URL đã bị từ chối
            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = p_screenshot_url then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        else
            v_effective_full := v_existing_report.screenshot_url;
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
                thumbnail_url = case when v_new_screenshot then p_thumbnail_url else thumbnail_url end,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
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
                thumbnail_url = case when v_new_screenshot then p_thumbnail_url else thumbnail_url end,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
                rating = v_rating,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;
        v_status := 'pending';
    else
        -- TH3: BÁO CÁO MỚI TINH TRONG NGÀY
        if p_screenshot_url is null or btrim(p_screenshot_url) = '' then
            raise exception 'SCREENSHOT_URL_REQUIRED';
        end if;
        if p_image_hash is null or btrim(p_image_hash) = '' then
            raise exception 'IMAGE_HASH_REQUIRED';
        end if;

        if p_mission_id is not null then
            select * into v_mission from public.missions
            where id = p_mission_id and campaign_id = p_campaign_id;

            if v_mission.id is null then
                raise exception 'MISSION_NOT_FOUND';
            end if;
            if v_mission.status not in ('open','full') then
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

            v_submission_context := 'mission';
            v_target_mission_id := v_mission.id;
            v_target_mp_id := v_mp.id;
            v_target_title := v_mission.title;
            v_target_reward := v_mission.reward_credits;
            v_target_ends_at := v_mission.ends_at;
        else
            v_submission_context := 'app_test';
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

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
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', 0, 0, 0,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := false;
            v_provisional_amount := 0;
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
        end
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

revoke all on function public.submit_tester_report_internal_v2(uuid,text,text,integer,text,uuid,text,uuid) from public,anon,authenticated;

commit;
