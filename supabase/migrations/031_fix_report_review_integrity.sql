-- ============================================================================
-- 12 Tester — Supabase migration 031: Fix toàn vẹn luồng duyệt báo cáo
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nội dung sửa (theo báo cáo kiểm tra luồng Tester ↔ Chủ app):
-- 1. Bảng audit report_review_events: ghi mọi lần duyệt/từ chối/auto-duyệt.
--    - Sửa lỗi bộ đếm cảnh báo: trước đây đếm report có status='rejected',
--      nhưng tester nộp lại làm status chuyển 'pending' nên mất dấu lần từ chối
--      → vòng reject → resubmit → reject không bao giờ đạt 3 lần/ngày.
--    - Ngày warning dùng ngày VN thay vì current_date (UTC).
-- 2. approve_tester_report: vô hiệu hóa p_bonus (trước đây chủ app có thể gọi
--    API trực tiếp để mint Credit không giới hạn cho tester).
-- 3. submit_tester_report:
--    - Khóa dòng report hôm nay FOR UPDATE (không đua với chủ app duyệt).
--    - Báo cáo đã duyệt: chỉ cho bổ sung nội dung, KHÔNG đổi ảnh/rating.
--    - Nộp lại sau từ chối: bắt buộc ảnh mới, khác ảnh đã bị từ chối
--      (so image_hash, fallback so URL) → chặn nộp lại đúng ảnh cũ.
--    - Update có điều kiện status/reviewed_at, chống ghi đè sau khi đã duyệt.
-- 4. Trigger thông báo: báo chủ app khi tester sửa báo cáo đang pending.
-- 5. get_campaign_detail: tự chạy auto_accept_expired_reports() để báo cáo
--    quá 8h vẫn được duyệt ngay cả khi pg_cron chưa bật.
-- ============================================================================


-- ============================================================================
-- 1. Bảng audit sự kiện review
-- ============================================================================
create table if not exists public.report_review_events (
    id           uuid primary key default gen_random_uuid(),
    report_id    uuid not null references public.tester_reports(id) on delete cascade,
    campaign_id  uuid not null references public.campaigns(id) on delete cascade,
    tester_id    uuid not null references public.profiles(id) on delete cascade,
    owner_id     uuid references public.profiles(id) on delete set null,
    reviewer_id  uuid references public.profiles(id) on delete set null,
    event_type   text not null check (event_type in ('reject', 'approve', 'auto_accept')),
    reason       text,
    created_at   timestamptz not null default now()
);

create index if not exists idx_review_events_tester_day
    on public.report_review_events (tester_id, created_at desc);
create index if not exists idx_review_events_owner_day
    on public.report_review_events (owner_id, created_at desc);
create index if not exists idx_review_events_report
    on public.report_review_events (report_id, created_at desc);
-- Tăng tốc quét báo cáo quá 8h cần auto-duyệt (get_campaign_detail gọi mỗi lần tải).
create index if not exists idx_tester_reports_pending_created
    on public.tester_reports (created_at)
    where status = 'pending';

alter table public.report_review_events enable row level security;
-- Chỉ các RPC security definer (owner của function) được ghi/đọc.
revoke all on table public.report_review_events from public, anon, authenticated;
grant all on table public.report_review_events to service_role;


-- ============================================================================
-- 2. reject_tester_report — đếm cảnh báo bằng audit event + ngày VN
-- ============================================================================
create or replace function public.reject_tester_report(
    p_report_id uuid,
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
    v_report record;
    v_camp record;
    v_tester_credits int;
    v_deduct int := 0;
    v_new_balance int;
    v_report_date date;
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;

    v_tester_today_rejections int := 0;
    v_tester_warned boolean := false;
    v_tester_warning_msg text := null;
    v_tester_weekly_warnings int := 0;
    v_is_tester_locked boolean := false;

    v_owner_today_rejections int := 0;
    v_owner_warned boolean := false;
    v_owner_warning_msg text := null;
    v_owner_weekly_warnings int := 0;
    v_is_owner_locked boolean := false;

    v_msg_tester text := 'Hệ thống ghi nhận bạn tham gia kiểm thử chưa nghiêm túc, đề nghị test app nghiêm túc và chụp ảnh màn hình không trùng với ảnh đã chụp hôm trước (nên chụp các tính năng khác của ứng dụng kiểm thử). Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
    v_msg_owner text := 'Hệ thống ghi nhận bạn xác nhận kết quả của người kiểm thử chưa nghiêm túc, đề nghị đối chiếu kỹ ảnh chụp của người kiểm thử chụp các ngày khác nhau để duyệt cho chính xác. Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa';
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    if v_report.status <> 'pending' then
        raise exception 'REPORT_ALREADY_REVIEWED';
    end if;

    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Ghi audit TRƯỚC khi tính cảnh báo: event này cũng được tính vào hôm nay.
    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'reject', p_reason
    );

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    -- 1. Thu hồi đúng số Credit THỰC đã cấp khi nộp
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(2, coalesce(v_report.provisional_granted_credits, 2)))
    );

    if v_deduct > 0 then
        update public.profiles
        set credits = credits - v_deduct
        where id = v_report.user_id
        returning credits into v_new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_report.user_id,
            -v_deduct,
            'test_rejected_clawback',
            format('Thu hồi %s Credit tạm nhận do chủ app từ chối kết quả: %s', v_deduct, coalesce(p_reason, 'Chưa đạt yêu cầu')),
            v_new_balance
        );
    else
        v_new_balance := coalesce(v_tester_credits, 0);
    end if;

    -- 2. Cập nhật trạng thái báo cáo thành rejected
    update public.tester_reports
    set status = 'rejected',
        rejection_reason = p_reason,
        bonus_awarded = 0,
        provisional_clawed = v_deduct,
        reviewed_at = now()
    where id = p_report_id;

    -- 3. Chỉ xóa checkin TẠM TÍNH (không xóa checkin xác nhận khác trong ngày)
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

    -- 4. Cảnh báo tester: đếm từ audit event (không bị reset khi nộp lại)
    select count(*) into v_tester_today_rejections
    from public.report_review_events
    where tester_id = v_report.user_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_report.user_id
              and warning_type = 'tester_rejection_streak'
              and warning_date = v_vn_today
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_report.user_id, 'tester_rejection_streak', v_msg_tester, v_vn_today);
            v_tester_warned := true;
            v_tester_warning_msg := v_msg_tester;

            select count(*) into v_tester_weekly_warnings
            from public.user_warnings
            where user_id = v_report.user_id
              and warning_date >= (v_vn_today - 6);

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    -- 5. Cảnh báo chủ app: đếm từ audit event
    select count(*) into v_owner_today_rejections
    from public.report_review_events
    where owner_id = v_caller_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_caller_id
              and warning_type = 'owner_excessive_rejections'
              and warning_date = v_vn_today
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_caller_id, 'owner_excessive_rejections', v_msg_owner, v_vn_today);
            v_owner_warned := true;
            v_owner_warning_msg := v_msg_owner;

            select count(*) into v_owner_weekly_warnings
            from public.user_warnings
            where user_id = v_caller_id
              and warning_date >= (v_vn_today - 6);

            if v_owner_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_caller_id;
                v_is_owner_locked := true;
            end if;
        end if;
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'rejected',
        'credits_deducted', v_deduct,
        'tester_balance', v_new_balance,
        'tester_today_rejections', v_tester_today_rejections,
        'tester_warned', v_tester_warned,
        'tester_warning_msg', v_tester_warning_msg,
        'is_tester_locked', v_is_tester_locked,
        'owner_today_rejections', v_owner_today_rejections,
        'owner_warned', v_owner_warned,
        'owner_warning_msg', v_owner_warning_msg,
        'is_owner_locked', v_is_owner_locked
    );
end;
$$;

revoke execute on function public.reject_tester_report(uuid, text) from public, anon;
grant execute on function public.reject_tester_report(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 3. approve_tester_report — vô hiệu hóa p_bonus, ghi audit event
-- ============================================================================
create or replace function public.approve_tester_report(
    p_report_id uuid,
    p_bonus int default 0
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_report record;
    v_camp record;
    -- p_bonus giữ lại trong chữ ký để tương thích client cũ nhưng KHÔNG còn
    -- hiệu lực: trước đây p_bonus không giới hạn và không trừ Credit ai cả.
    v_extra_reward int := 1;
    v_tip int := 0;
    v_total_bonus int;
    v_new_balance int;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    if v_report.status <> 'pending' then
        raise exception 'REPORT_ALREADY_REVIEWED';
    end if;

    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    -- Tip thật (nếu chủ app có đặt): trừ Credit chủ app, cộng cho tester
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);

    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

    update public.profiles
    set credits = credits + v_extra_reward,
        last_active_date = v_today,
        missed_streak = 0
    where id = v_report.user_id
    returning credits into v_new_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_extra_reward,
        'test_approved_bonus',
        format('Chủ app duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)', v_extra_reward),
        v_new_balance
    );

    update public.checkins
    set note = 'Đã kiểm thử và được chủ app duyệt'
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    if not found then
        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được chủ app duyệt')
        on conflict (campaign_id, user_id, checkin_date)
        do update set note = 'Đã kiểm thử và được chủ app duyệt';
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'message', format('Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester)', v_extra_reward)
    );
end;
$$;

revoke execute on function public.approve_tester_report(uuid, int) from public, anon;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;


-- ============================================================================
-- 4. auto_accept_expired_reports — ghi audit event auto_accept
-- ============================================================================
create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_count int := 0;
    v_owner_id uuid;
    v_camp_name text;
    v_tip int;
    v_total_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        insert into public.report_review_events (
            report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
        ) values (
            v_report.id, v_report.campaign_id, v_report.user_id,
            v_owner_id, null, 'auto_accept'
        );

        update public.checkins
        set note = 'Hệ thống tự động duyệt sau 8h'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (
                v_report.campaign_id, v_report.user_id,
                (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                'Hệ thống tự động duyệt sau 8h'
            )
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Hệ thống tự động duyệt sau 8h';
        end if;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên 2 Credit đã nhận + %s Credit boa từ chủ app). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên 2 Credit đã nhận). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'))
            end,
            'report',
            jsonb_build_object('campaign_id', v_report.campaign_id, 'report_id', v_report.id, 'status', 'approved', 'auto_accepted', true, 'tip_awarded', v_tip)
        );

        v_count := v_count + 1;
    end loop;

    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;


-- ============================================================================
-- 5. submit_tester_report — khóa dòng, bắt buộc ảnh mới khi nộp lại,
--    đóng băng ảnh/rating sau khi duyệt
-- ============================================================================
drop function if exists public.submit_tester_report(uuid, text, text, int);

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
    v_provisional_amount int := 0;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
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
            end
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
               and p_image_hash = v_existing_report.image_hash then
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
                    image_hash = p_image_hash,
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
                    image_hash = p_image_hash,
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
                image_hash = case when v_new_screenshot then p_image_hash else image_hash end
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
                provisional_granted_credits, image_hash, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 0, p_image_hash, now()
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
                provisional_granted_credits, image_hash, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', 2, 0, 2, p_image_hash, now()
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

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount
    );
end;
$$;

revoke execute on function public.submit_tester_report(uuid, text, text, int, text) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, service_role;


-- ============================================================================
-- 6. Trigger thông báo: thêm nhánh tester sửa báo cáo đang pending
-- ============================================================================
create or replace function public.trg_notify_tester_report()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
    v_clawed int;
begin
    if coalesce(current_setting('app.suppress_report_notify', true), '') = '1' then
        return new;
    end if;

    -- 1. Báo cáo mới
    if tg_op = 'INSERT' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo kiểm thử mới 📋',
                format('Ứng dụng "%s" vừa nhận được 1 báo cáo kiểm thử mới. Bạn có 8 giờ để kiểm tra và duyệt (+1 Credit thưởng cho tester nếu duyệt).', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending')
            );
        end if;

    -- 2. Tester nộp lại báo cáo sau khi bị từ chối
    elsif tg_op = 'UPDATE' and old.status = 'rejected' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo nộp lại 📋',
                format('Tester vừa nộp lại kết quả kiểm thử cho ứng dụng "%s" sau khi bị từ chối. Bạn có 8 giờ để kiểm tra và duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending', 'is_resubmission', true)
            );
        end if;

    -- 3. Tester sửa báo cáo đang chờ duyệt (đổi ảnh/nội dung) -> báo chủ app
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status = 'pending'
          and (old.screenshot_url is distinct from new.screenshot_url
               or old.image_hash is distinct from new.image_hash
               or old.content is distinct from new.content) then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Tester cập nhật báo cáo ✏️',
                format('Tester vừa cập nhật báo cáo kiểm thử cho ứng dụng "%s". Vui lòng xem lại ảnh/nội dung mới trước khi duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending', 'is_edited', true)
            );
        end if;

    -- 4. Chủ app duyệt hoặc từ chối
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử được phê duyệt! 🎉',
                format('Báo cáo của bạn cho ứng dụng "%s" đã được chủ app duyệt thành công! Bạn nhận được thêm +%s Credit thưởng (tổng cộng %s Credit). Cảm ơn bạn!',
                    coalesce(v_camp_name, 'ứng dụng'),
                    greatest(1, coalesce(new.bonus_awarded, 3) - 2),
                    coalesce(new.bonus_awarded, 3)
                ),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'approved')
            );
        elsif new.status = 'rejected' then
            v_clawed := greatest(0, coalesce(new.provisional_clawed, 0));

            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                case
                    when v_clawed > 0 and new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Lý do: "%s". %s Credit tạm nhận đã bị thu hồi. Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason, v_clawed)
                    when v_clawed > 0 then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. %s Credit tạm nhận đã bị thu hồi. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), v_clawed)
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Lý do: "%s". Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị chủ app từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'))
                end,
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'rejected', 'reason', new.rejection_reason, 'credits_clawed', v_clawed)
            );
        end if;
    end if;

    return new;
exception when others then
    return new;
end;
$$;

drop trigger if exists trg_on_tester_report_event on public.tester_reports;
create trigger trg_on_tester_report_event
    after insert or update on public.tester_reports
    for each row
    execute function public.trg_notify_tester_report();


-- ============================================================================
-- 7. get_campaign_detail — tự auto-duyệt báo cáo quá 8h trước khi trả dữ liệu
-- ============================================================================
create or replace function public.get_campaign_detail(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_ago date := v_today - 6;
    v_camp public.campaigns%rowtype;
    v_is_owner boolean := false;
    v_is_member boolean := false;
    v_confirmed boolean := false;
    v_campaign jsonb;
    v_testers jsonb := '[]'::jsonb;
    v_feedbacks jsonb := '[]'::jsonb;
    v_reports jsonb := '[]'::jsonb;
    v_profiles jsonb := '{}'::jsonb;
    v_today_tested int := 0;
    v_reports_count int := 0;
    v_pending int := 0;
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

    -- Báo cáo quá 8h phải được duyệt ngay cả khi pg_cron chưa bật.
    perform public.auto_accept_expired_reports();

    v_is_owner := (v_camp.owner_id = v_uid)
        or exists (
            select 1 from public.profiles p
            where p.id = v_uid and lower(p.role) in ('admin', 'moderator')
        );

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

    select coalesce(jsonb_agg(jsonb_build_object(
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
        'image_hash', tr.image_hash
    ) order by tr.created_at desc), '[]'::jsonb)
    into v_reports
    from (
        select * from public.tester_reports
        where campaign_id = p_campaign_id
          and (v_is_owner or user_id = v_uid)
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
        where tr.campaign_id = p_campaign_id and (v_is_owner or tr.user_id = v_uid)
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

    select greatest(
        (select count(*) from public.tester_reports tr where tr.campaign_id = p_campaign_id),
        (select count(*) from public.feedbacks f where f.campaign_id = p_campaign_id),
        (select count(*) from public.checkins ci where ci.campaign_id = p_campaign_id)
    ) into v_reports_count;

    select count(*) into v_pending
    from public.tester_reports tr
    where tr.campaign_id = p_campaign_id and tr.status = 'pending';

    select avg(f.rating)::float8 into v_avg
    from public.feedbacks f
    where f.campaign_id = p_campaign_id;

    select coalesce(jsonb_agg(distinct to_char(ci.checkin_date, 'YYYY-MM-DD')), '[]'::jsonb)
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
    from public.user_warnings
    where user_id = v_uid and warning_date >= v_week_ago;

    return jsonb_build_object(
        'campaign', v_campaign,
        'is_member', v_is_member,
        'is_owner', v_is_owner,
        'checked_in_confirmed_today', v_confirmed,
        'testers', v_testers,
        'feedbacks', v_feedbacks,
        'reports', v_reports,
        'profiles', v_profiles,
        'today_tested_count', v_today_tested,
        'reports_count', v_reports_count,
        'pending_reports_count', v_pending,
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


notify pgrst, 'reload schema';


