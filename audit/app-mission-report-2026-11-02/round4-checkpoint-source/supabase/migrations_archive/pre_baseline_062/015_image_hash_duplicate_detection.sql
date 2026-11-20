-- Migration 015: Lưu Perceptual Hash (dHash) phát hiện ảnh trùng lặp trong tester_reports

-- 1. Bổ sung cột image_hash vào bảng tester_reports
alter table public.tester_reports
add column if not exists image_hash text;

create index if not exists idx_tester_reports_image_hash on public.tester_reports(image_hash);

-- 2. Cập nhật submit_tester_report hỗ trợ tham số p_image_hash
create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5,
    p_image_hash text default null
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
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    -- Tự động thêm tester vào danh sách thành viên nếu chưa tham gia
    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lưu/Cập nhật đánh giá sao và nhận xét trong bảng feedbacks
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = excluded.comment,
        updated_at = now();

    -- Kiểm tra xem hôm nay user đã có dòng checkin chính thức (không phải tạm tính) chưa
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and note not like '%tạm tính%'
    ) into v_already_checked_in;

    -- Tìm xem hôm nay user đã nộp báo cáo nào cho campaign này chưa
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1;

    if v_existing_report.id is not null then
        -- Trường hợp 1: Báo cáo hôm nay đã được duyệt từ trước
        if v_existing_report.status = 'approved' or v_already_checked_in then
            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = coalesce(p_image_hash, image_hash)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'approved';

        -- Trường hợp 2: Báo cáo hôm nay từng bị từ chối -> Tester nộp lại ảnh mới
        elsif v_existing_report.status = 'rejected' then
            -- Cấp lại 2 Credit tạm nhận
            update public.profiles
            set credits = credits + 2,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id,
                2,
                'test_provisional',
                'Tạm nhận 2 Credit sau khi nộp lại kết quả test (chờ chủ app duyệt)',
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                status = 'pending',
                bonus_awarded = 2,
                rejection_reason = null,
                image_hash = coalesce(p_image_hash, image_hash),
                created_at = now(), -- Đặt lại thời gian để chủ app có trọn vẹn 8h mới để duyệt
                reviewed_at = null
            where id = v_existing_report.id
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;

        -- Trường hợp 3: Báo cáo đang ở trạng thái pending (chỉnh sửa nội dung/ảnh trước khi duyệt)
        else
            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = coalesce(p_image_hash, image_hash)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'pending';
        end if;
    else
        -- Trường hợp 4: Chưa có báo cáo hôm nay -> Tạo mới & tạm nhận 2 Credit
        update public.profiles
        set credits = credits + 2,
            last_active_date = v_today,
            missed_streak = 0
        where id = v_user_id
        returning credits into v_new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_user_id,
            2,
            'test_provisional',
            'Tạm nhận 2 Credit sau khi nộp kết quả test (chờ chủ app duyệt)',
            v_new_balance
        );

        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
        on conflict (campaign_id, user_id, checkin_date)
        do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

        insert into public.tester_reports (
            campaign_id,
            user_id,
            report_type,
            content,
            screenshot_url,
            rating,
            status,
            bonus_awarded,
            image_hash,
            created_at
        )
        values (
            p_campaign_id,
            v_user_id,
            'feedback',
            coalesce(p_content, ''),
            p_screenshot_url,
            v_rating,
            'pending',
            2,
            p_image_hash,
            now()
        )
        returning id into v_report_id;

        v_status := 'pending';
        v_provisional_granted := true;
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted
    );
end;
$$;

-- 3. Cập nhật get_campaign_reports_for_owner để trả về image_hash
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

    if v_camp.owner_id <> v_caller_id and coalesce(v_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Tự động duyệt bất kỳ báo cáo nào quá hạn 8 giờ
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
                'rating', tr.rating,
                'status', tr.status,
                'bonus_awarded', tr.bonus_awarded,
                'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'rejection_reason', tr.rejection_reason,
                'image_hash', tr.image_hash,
                'tester_name', coalesce(p.display_name, split_part(coalesce(p.email, ''), '@', 1), 'Tester'),
                'tester_avatar', p.avatar_url,
                'tester_credits', p.credits
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

grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated;
grant execute on function public.get_campaign_reports_for_owner(uuid) to authenticated, anon, service_role;
