-- ============================================================================
-- 057_report_idempotency_ledger_and_context.sql
-- 
-- 1. Thêm bảng public.report_submission_receipts để quản lý durable idempotency ledger
--    cho từng client_submit_id, bảo vệ chống ghi đè khi replay request cũ sau khi đã có cập nhật mới.
-- 2. Cập nhật submit_tester_report:
--    - Tra cứu receipt theo (user_id, client_submit_id)
--    - Kiểm tra fingerprint của payload: cùng ID cùng payload trả receipt; cùng ID khác payload báo lỗi
--    - Lưu receipt bền vững cho mọi thao tác thành công (mới, update pending, resubmit rejected, append comment)
-- 3. Cập nhật get_campaign_mission:
--    - Thêm tham số tùy chọn p_mission_id uuid default null
--    - Cho phép tải đúng mission của báo cáo thay vì luôn lấy mission mới nhất của app
-- ============================================================================

create table if not exists public.report_submission_receipts (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    client_submit_id uuid not null,
    campaign_id uuid not null references public.campaigns(id) on delete cascade,
    report_id uuid references public.tester_reports(id) on delete cascade,
    payload_fingerprint text not null,
    response_json jsonb not null,
    created_at timestamptz not null default now(),
    provenance text not null default 'exact_v1',
    constraint report_submission_receipts_user_client_submit_id_key unique (user_id, client_submit_id)
);

alter table public.report_submission_receipts
    add column if not exists provenance text not null default 'exact_v1';

create index if not exists idx_report_submission_receipts_lookup
    on public.report_submission_receipts (user_id, client_submit_id);

alter table public.report_submission_receipts enable row level security;
grant select on public.report_submission_receipts to authenticated;
drop policy if exists report_submission_receipts_select_policy on public.report_submission_receipts;
create policy report_submission_receipts_select_policy on public.report_submission_receipts
    for select using (auth.uid() = user_id);

-- Backfill các report hiện có vào receipt ledger với provenance = 'legacy_backfill'
insert into public.report_submission_receipts (
    user_id, client_submit_id, campaign_id, report_id,
    payload_fingerprint, response_json, created_at, provenance
)
select
    tr.user_id,
    tr.client_submit_id,
    tr.campaign_id,
    tr.id,
    encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
        tr.campaign_id,
        coalesce(tr.content, ''),
        coalesce(tr.screenshot_url, ''),
        coalesce(tr.rating, 5),
        coalesce(lower(tr.image_hash), ''),
        coalesce(tr.thumbnail_url, ''),
        coalesce(tr.mission_id::text, '')
    ), 'sha256'), 'hex'),
    jsonb_build_object(
        'success', true,
        'report_id', tr.id,
        'status', tr.status,
        'provisional_granted', false,
        'provisional_amount', 0,
        'screenshot_url', tr.screenshot_url,
        'image_hash', tr.image_hash,
        'thumbnail_url', tr.thumbnail_url,
        'submission_context', tr.submission_context,
        'mission_id', tr.mission_id,
        'mission_title', tr.mission_title,
        'mission_reward_credits', tr.mission_reward_credits,
        'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ),
    tr.created_at,
    'legacy_backfill'
from public.tester_reports tr
where tr.client_submit_id is not null
on conflict (user_id, client_submit_id) do nothing;

-- Cập nhật get_campaign_mission hỗ trợ p_mission_id
drop function if exists public.get_campaign_mission(uuid);

create or replace function public.get_campaign_mission(
    p_campaign_id uuid,
    p_mission_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_row record;
begin
    if p_mission_id is not null then
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
        where m.id = p_mission_id
          and m.campaign_id = p_campaign_id
          and m.status <> 'deleted'
        limit 1;
    else
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
    end if;

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

revoke execute on function public.get_campaign_mission(uuid, uuid) from public, anon;
grant execute on function public.get_campaign_mission(uuid, uuid) to authenticated, service_role;

-- Cập nhật submit_tester_report
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

            -- Nếu receipt là legacy_backfill: hỗ trợ semantics legacy an toàn (B01, B02)
            if coalesce(v_receipt.provenance, 'legacy_backfill') = 'legacy_backfill' then
                declare
                    v_resp_mission text := coalesce(v_receipt.response_json ->> 'mission_id', '');
                    v_resp_url text := coalesce(v_receipt.response_json ->> 'screenshot_url', '');
                    v_resp_hash text := coalesce(lower(v_receipt.response_json ->> 'image_hash'), '');
                    v_resp_thumb text := coalesce(v_receipt.response_json ->> 'thumbnail_url', '');
                    v_eff_mission text := case when p_mission_id is null then v_resp_mission else p_mission_id::text end;
                    v_eff_url text := case when p_screenshot_url is null then v_resp_url else p_screenshot_url end;
                    v_eff_hash text := case when p_image_hash is null then v_resp_hash else lower(p_image_hash) end;
                    v_eff_thumb text := case when p_thumbnail_url is null then v_resp_thumb else p_thumbnail_url end;
                    v_legacy_fp text;
                begin
                    if (p_screenshot_url is null or p_screenshot_url = v_resp_url)
                       and (p_image_hash is null or lower(p_image_hash) = v_resp_hash)
                       and (p_thumbnail_url is null or p_thumbnail_url = v_resp_thumb)
                       and (p_mission_id is null or p_mission_id::text = v_resp_mission)
                    then
                        v_legacy_fp := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                            p_campaign_id,
                            coalesce(p_content, ''),
                            v_eff_url,
                            coalesce(p_rating, 5),
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
                'mission_ends_at', to_char(v_idempotent_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            );

            -- Ghi vào receipt ledger để các lần retry tiếp theo dùng thẳng receipt
            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_idempotent_report.id,
                v_fingerprint, v_result, now()
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

    -- Khóa theo request ID trước để đồng bộ các request cùng ID (N06)
    if p_client_submit_id is not null then
        perform pg_advisory_xact_lock(
            hashtextextended(format('submit_req:%s:%s', v_user_id, p_client_submit_id), 0)
        );
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

            -- Nếu receipt là legacy_backfill: hỗ trợ semantics legacy an toàn (B01, B02)
            if coalesce(v_receipt.provenance, 'legacy_backfill') = 'legacy_backfill' then
                declare
                    v_resp_mission text := coalesce(v_receipt.response_json ->> 'mission_id', '');
                    v_resp_url text := coalesce(v_receipt.response_json ->> 'screenshot_url', '');
                    v_resp_hash text := coalesce(lower(v_receipt.response_json ->> 'image_hash'), '');
                    v_resp_thumb text := coalesce(v_receipt.response_json ->> 'thumbnail_url', '');
                    v_eff_mission text := case when p_mission_id is null then v_resp_mission else p_mission_id::text end;
                    v_eff_url text := case when p_screenshot_url is null then v_resp_url else p_screenshot_url end;
                    v_eff_hash text := case when p_image_hash is null then v_resp_hash else lower(p_image_hash) end;
                    v_eff_thumb text := case when p_thumbnail_url is null then v_resp_thumb else p_thumbnail_url end;
                    v_legacy_fp text;
                begin
                    if (p_screenshot_url is null or p_screenshot_url = v_resp_url)
                       and (p_image_hash is null or lower(p_image_hash) = v_resp_hash)
                       and (p_thumbnail_url is null or p_thumbnail_url = v_resp_thumb)
                       and (p_mission_id is null or p_mission_id::text = v_resp_mission)
                    then
                        v_legacy_fp := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                            p_campaign_id,
                            coalesce(p_content, ''),
                            v_eff_url,
                            coalesce(p_rating, 5),
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
                'mission_ends_at', to_char(v_existing_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            ),
            v_existing_report.created_at,
            'legacy_backfill'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

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

        if p_client_submit_id is not null then
            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
                v_fingerprint, v_result, now()
            ) on conflict (user_id, client_submit_id) do nothing;
        end if;

        return v_result;
    end if;

    -- TH2: BÁO CÁO ĐANG CHỜ DUYỆT (pending) HOẶC BỊ TỪ CHỐI (rejected) HOẶC NỘP MỚI
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
        'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
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
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text, uuid, text, uuid) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text, uuid, text, uuid) to authenticated, service_role;

notify pgrst, 'reload schema';
