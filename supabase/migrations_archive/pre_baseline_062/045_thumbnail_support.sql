-- ============================================================================
-- 12 Tester — Supabase migration 045: Hỗ trợ Thumbnail cho Báo cáo (E02)
-- Đã hiệu chỉnh: Bảo toàn 100% hợp đồng nghiệp vụ từ migration 037/044/schema chuẩn
-- (F01, F02, F05: validator full/thumb, checkin reward, feedbacks upsert,
--  approved immutable rating, rejected dHash check, replacement full clears thumb)
-- ============================================================================

-- 1. Thêm cột thumbnail_url vào bảng tester_reports (tùy chọn, tương thích ngược)
alter table public.tester_reports
add column if not exists thumbnail_url text;

-- 2a. Validator cho URL Screenshot báo cáo (chống query spoofing & ràng buộc bucket allowlist)
create or replace function public.is_valid_report_screenshot_url(
    p_url text,
    p_campaign_id uuid,
    p_user_id uuid
)
returns boolean
language plpgsql
immutable
as $$
declare
    v_clean_url text;
    v_bucket text;
    v_path text;
    v_matches text[];
begin
    if p_url is null or length(p_url) > 2048 then
        return false;
    end if;

    -- Tách query string (?) và fragment (#) để triệt tiêu query spoofing
    v_clean_url := split_part(split_part(p_url, '?', 1), '#', 1);

    -- 1. Parse theo định dạng Firebase Storage chuẩn hoặc GCS
    if v_clean_url ~* '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$' then
        v_matches := regexp_matches(v_clean_url, '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    elsif v_clean_url ~* '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$' then
        v_matches := regexp_matches(v_clean_url, '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$', 'i');
        v_bucket := v_matches[2];
        v_path := v_matches[3];
    elsif v_clean_url ~* '^https://([^/]+)\.firebasestorage\.app/o/(.+)$' then
        v_matches := regexp_matches(v_clean_url, '^https://([^/]+)\.firebasestorage\.app/o/(.+)$', 'i');
        v_bucket := v_matches[1] || '.firebasestorage.app';
        v_path := v_matches[2];
    elsif v_clean_url ~* '^https://storage\.googleapis\.com/([^/]+)/(.+)$' then
        v_matches := regexp_matches(v_clean_url, '^https://storage\.googleapis\.com/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    else
        return false;
    end if;

    -- 2. Allowlist bucket (chỉ cho phép các bucket chính thức của dự án, không cho phép ký tự đại diện hoặc bucket test trong validator production)
    if not (
        v_bucket in ('cross-testers.firebasestorage.app', 'cross-testers.appspot.com', 'crosstest-prod.appspot.com')
    ) then
        return false;
    end if;

    -- 3. Chuẩn hoá percent-encoding (%2F -> /)
    v_path := replace(replace(v_path, '%2F', '/'), '%2f', '/');

    -- 4. Ràng buộc object path: reports/<campaign_id>/<user_id>/<filename>
    if not (
        v_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '/[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
        or v_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '_[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
    ) then
        return false;
    end if;

    return true;
end;
$$;

-- 2b. Validator cho URL Thumbnail báo cáo (ràng buộc bucket, path và cặp full-thumbnail)
create or replace function public.is_valid_report_thumbnail_url(
    p_thumb_url text,
    p_campaign_id uuid,
    p_user_id uuid,
    p_screenshot_url text default null
)
returns boolean
language plpgsql
immutable
as $$
declare
    v_clean_thumb text;
    v_clean_full text;
    v_thumb_bucket text;
    v_thumb_path text;
    v_matches text[];
    v_thumb_filename text;
    v_full_filename text;
begin
    -- 1. Thumbnail là tùy chọn: NULL là hoàn toàn hợp lệ (legacy reports)
    if p_thumb_url is null then
        return true;
    end if;

    if length(p_thumb_url) > 2048 then
        return false;
    end if;

    -- Tách query string (?) và fragment (#)
    v_clean_thumb := split_part(split_part(p_thumb_url, '?', 1), '#', 1);

    if v_clean_thumb ~* '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$' then
        v_matches := regexp_matches(v_clean_thumb, '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    elsif v_clean_thumb ~* '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$' then
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];
    elsif v_clean_thumb ~* '^https://([^/]+)\.firebasestorage\.app/o/(.+)$' then
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/]+)\.firebasestorage\.app/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[1] || '.firebasestorage.app';
        v_thumb_path := v_matches[2];
    elsif v_clean_thumb ~* '^https://storage\.googleapis\.com/([^/]+)/(.+)$' then
        v_matches := regexp_matches(v_clean_thumb, '^https://storage\.googleapis\.com/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    else
        return false;
    end if;

    -- Allowlist bucket
    if not (
        v_thumb_bucket in ('cross-testers.firebasestorage.app', 'cross-testers.appspot.com', 'crosstest-prod.appspot.com')
    ) then
        return false;
    end if;

    -- Chuẩn hoá percent-encoding
    v_thumb_path := replace(replace(v_thumb_path, '%2F', '/'), '%2f', '/');

    -- Đường dẫn object thumbnail phải thuộc đúng campaign, user và có tiền tố thumb_
    if not (
        v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '/thumb_[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
        or v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '_thumb_[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
    ) then
        return false;
    end if;

    -- 2. Kiểm tra ràng buộc cặp Full - Thumbnail nếu có p_screenshot_url
    if p_screenshot_url is not null then
        v_clean_full := split_part(split_part(p_screenshot_url, '?', 1), '#', 1);
        v_clean_full := replace(replace(v_clean_full, '%2F', '/'), '%2f', '/');
        
        v_thumb_filename := substring(v_thumb_path from '[^/]+$');
        v_full_filename := substring(v_clean_full from '[^/]+$');

        -- Tên file thumbnail phải đúng định dạng 'thumb_' + tên file full
        if v_thumb_filename != ('thumb_' || v_full_filename) then
            return false;
        end if;
    end if;

    return true;
end;
$$;

-- 3. Cập nhật stored procedure submit_tester_report với tham số p_thumbnail_url tùy chọn
drop function if exists public.submit_tester_report(uuid, text, text, int, text, uuid);

create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5,
    p_image_hash text default null,
    p_client_submit_id uuid default null,
    p_thumbnail_url text default null
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
                'image_hash', v_idempotent_report.image_hash,
                'thumbnail_url', v_idempotent_report.thumbnail_url
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

    -- Xác định effective full screenshot (từ tham số mới hoặc từ report hiện tại trong DB)
    v_effective_full := coalesce(p_screenshot_url, v_existing_report.screenshot_url);

    -- Kiểm tra thumbnail URL nếu được truyền vào
    if p_thumbnail_url is not null then
        -- Chặn orphan thumbnail: không cho phép thumbnail khi không có ảnh full hợp lệ (kể cả report mới lẫn omitted full)
        if v_effective_full is null then
            raise exception 'INVALID_THUMBNAIL_URL';
        end if;
        if not public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, v_effective_full) then
            raise exception 'INVALID_THUMBNAIL_URL';
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
                -- Legacy: đã có checkin xác nhận trong ngày, không cấp thêm tạm nhận.
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
                -- Chỉ cấp lại đúng số đã thu hồi; báo cáo cũ tối đa 2, báo cáo mới tối đa 10.
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
                -- F05: Khi đổi ảnh full, thumbnail phải lấy ảnh mới p_thumbnail_url (null nếu không có),
                -- không được giữ thumbnail cũ của ảnh trước.
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
        -- Trường hợp 4: chưa có báo cáo hôm nay
        if v_already_checked_in then
            -- Legacy: đã có checkin xác nhận trong ngày, không cấp thêm tạm nhận.
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', 2, 0, 0,
                lower(p_image_hash), p_client_submit_id, now()
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
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', v_provisional_reward, 0, v_provisional_reward,
                lower(p_image_hash), p_client_submit_id, now()
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

    select screenshot_url, image_hash, thumbnail_url
    into v_final_url, v_final_hash, v_final_thumb
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
        'thumbnail_url', v_final_thumb
    );
end;
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text, uuid, text) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text, uuid, text) to authenticated, service_role;
