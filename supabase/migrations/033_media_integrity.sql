-- ============================================================================
-- 033_media_integrity.sql
--
-- Mục tiêu:
-- 1. Chống nộp trùng khi client retry/timeout: client_submit_id + idempotency.
-- 2. Bắt buộc image_hash hợp lệ khi có ảnh mới; so ảnh gần trùng bằng dHash.
-- 3. Chỉ nhận screenshot URL thuộc đúng campaign/user trên Firebase Storage.
-- 4. Chặn client ghi thẳng tester_reports (chỉ RPC SECURITY DEFINER được ghi).
-- ============================================================================

alter table public.tester_reports
    add column if not exists client_submit_id uuid;

-- Mỗi user chỉ có một report cho một client_submit_id. Index partial vì
-- report cũ chưa có client_submit_id.
create unique index if not exists idx_tester_reports_user_client_submit
    on public.tester_reports (user_id, client_submit_id)
    where client_submit_id is not null;

-- Hash dHash là 16 ký tự hex. NOT VALID để không chặn dữ liệu cũ sai format,
-- nhưng mọi row mới/sửa sau migration đều phải đúng.
alter table public.tester_reports
    drop constraint if exists tester_reports_image_hash_format;
alter table public.tester_reports
    add constraint tester_reports_image_hash_format
    check (image_hash is null or image_hash ~ '^[0-9a-f]{16}$') not valid;

comment on column public.tester_reports.client_submit_id is
    'Idempotency key do client sinh ra cho một lần nộp; retry dùng lại cùng id.';

-- Khoảng cách Hamming giữa 2 dHash 64-bit. Trả 64 nếu input không hợp lệ.
create or replace function public.dhash_hamming(p_a text, p_b text)
returns int
language sql
immutable
as $$
    select case
        when p_a is null or p_b is null then 64
        when lower(p_a) !~ '^[0-9a-f]{16}$' or lower(p_b) !~ '^[0-9a-f]{16}$' then 64
        else length(
            replace(
                (
                    (('x' || lower(p_a))::bit(64))
                    #
                    (('x' || lower(p_b))::bit(64))
                )::text,
                '0',
                ''
            )
        )
    end;
$$;

-- URL ảnh phải nằm trong path của đúng campaign + user. Chấp nhận cả path mới
-- (reports/<campaign>/<user>/<file>) và path cũ (reports/<campaign>/<user>_<ts>)
-- để app cũ chưa update vẫn nộp được trong giai đoạn chuyển tiếp.
create or replace function public.is_valid_report_screenshot_url(
    p_url text,
    p_campaign_id uuid,
    p_user_id uuid
)
returns boolean
language sql
immutable
as $$
    select p_url is not null
       and length(p_url) <= 2048
       and (
           p_url like 'https://firebasestorage.googleapis.com/v0/b/%'
           or p_url like 'https://storage.googleapis.com/%'
       )
       and (
           p_url like '%reports%2F' || p_campaign_id::text || '%2F' || p_user_id::text || '%2F%'
           or p_url like '%reports%2F' || p_campaign_id::text || '%2F' || p_user_id::text || '_%'
           or p_url like '%/reports/' || p_campaign_id::text || '/' || p_user_id::text || '/%'
           or p_url like '%/reports/' || p_campaign_id::text || '/' || p_user_id::text || '_%'
       );
$$;

drop function if exists public.submit_tester_report(uuid, text, text, int, text);
drop function if exists public.submit_tester_report(uuid, text, text, int, text, uuid);

create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5,
    p_image_hash text default null,
    p_client_submit_id uuid default null
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
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_final_url text;
    v_final_hash text;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Idempotency: nếu RPC đã commit nhưng response bị mất/timeout, lần retry
    -- trả lại đúng report cũ, không cộng credit lần hai.
    if p_client_submit_id is not null then
        select * into v_idempotent_report
        from public.tester_reports
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_idempotent_report.id is not null then
            return jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_idempotent_report.screenshot_url,
                'image_hash', v_idempotent_report.image_hash
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

    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lấy report hôm nay TRƯỚC và khóa dòng: không đua với thao tác duyệt/từ chối.
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

    -- Feedback: báo cáo đã duyệt thì giữ nguyên rating, chỉ cho bổ sung comment.
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

    -- Checkin "xác nhận" hôm nay = checkin không phải tạm tính
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
    ) into v_already_checked_in;

    if v_existing_report.id is not null then
        -- Trường hợp 1: đã duyệt -> chỉ bổ sung nội dung, giữ nguyên ảnh & rating
        if v_existing_report.status = 'approved' then
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

        -- Trường hợp 2: bị từ chối thật -> nộp lại, BẮT BUỘC ảnh mới khác ảnh cũ
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

            if v_already_checked_in then
                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = p_screenshot_url,
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    provisional_clawed = 0,
                    provisional_granted_credits = 0,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            else
                v_regrant := least(2, greatest(0, coalesce(v_existing_report.provisional_clawed, 2)));

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
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = v_regrant,
                    provisional_clawed = 0,
                    provisional_granted_credits = v_regrant,
                    rejection_reason = null,
                    image_hash = lower(p_image_hash),
                    client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                  and status = 'rejected'
                  and reviewed_at is not null
                returning id into v_report_id;

                if v_report_id is null then
                    raise exception 'REPORT_ALREADY_REVIEWED';
                end if;
                v_status := 'pending';
            end if;

        -- Trường hợp 3: pending (hoặc rejected legacy chưa review) -> cho chỉnh sửa ảnh/nội dung
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = case when v_new_screenshot then p_screenshot_url else screenshot_url end,
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
        -- Trường hợp 4: chưa có báo cáo hôm nay
        if v_already_checked_in then
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 0,
                lower(p_image_hash), p_client_submit_id, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
        else
            update public.profiles
            set credits = credits + 2,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id, 2, 'test_provisional',
                'Tạm nhận 2 Credit sau khi nộp kết quả test (chờ chủ app duyệt)',
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 2,
                lower(p_image_hash), p_client_submit_id, now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := 2;
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    select screenshot_url, image_hash
    into v_final_url, v_final_hash
    from public.tester_reports
    where id = v_report_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount,
        'screenshot_url', v_final_url,
        'image_hash', v_final_hash
    );
end;
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text, uuid) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text, uuid) to authenticated, service_role;

-- Chỉ RPC SECURITY DEFINER được ghi report. Client chỉ còn quyền SELECT theo RLS.
revoke insert, update, delete on public.tester_reports from PUBLIC, anon, authenticated;

comment on function public.submit_tester_report(uuid, text, text, int, text, uuid) is
    'Nộp báo cáo idempotent, bắt buộc hash/URL ảnh hợp lệ và chặn ảnh gần trùng khi nộp lại.';
