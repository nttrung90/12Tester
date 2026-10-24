-- ============================================================================
-- 12 Tester — Supabase migration 037: Cơ chế thưởng test app mới
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Quy tắc:
--   • Tester nhận 10 Credit tạm nhận ngay khi nộp báo cáo.
--   • Chủ app thật duyệt trong 8h: tester +2 (tổng 12), chủ app +2.
--   • Admin/Moderator duyệt: tester +2, chủ app không được +2.
--   • Quá 8h hệ thống tự duyệt: tester giữ 10, không ai được +2.
--   • Từ chối: thu hồi đúng số tạm nhận thực tế (10; báo cáo cũ 2).
--   • Nộp lại: cấp lại đúng số đã thu hồi, tối đa 10.
--   • Báo cáo cũ đang pending giữ nguyên mức 2, không backfill. Nếu chủ app
--     duyệt tay thì tester vẫn nhận thêm +2 (tổng 4); auto-duyệt giữ 2.
--   • Tip giữ nguyên: trả khi chủ app duyệt tay và khi auto-duyệt.
--   • Chặn duyệt/từ chối tay sau 8h.
-- ============================================================================

-- ============================================================================
-- 1. submit_tester_report — tạm nhận 10 Credit
-- ============================================================================
drop function if exists public.submit_tester_report(uuid, text, text, int, text);

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
    v_provisional_reward int := 10;
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
                -- Legacy: đã có checkin xác nhận trong ngày, không cấp thêm tạm nhận.
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
            -- Legacy: đã có checkin xác nhận trong ngày, không cấp thêm tạm nhận.
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
                rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, v_rating, 'pending', v_provisional_reward, 0, v_provisional_reward,
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


-- ============================================================================
-- 2. approve_tester_report — tester +2, chủ app thật +2, chặn sau 8h
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
    v_extra_reward int := 2;
    v_tip int := 0;
    v_total_bonus int;
    v_new_balance int;
    v_owner_bonus int := 0;
    v_owner_balance int;
    v_is_owner boolean := false;
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

    -- Quá 8h: không cho duyệt tay, để auto_accept xử lý (không thưởng +2).
    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    v_is_owner := (v_camp.owner_id = v_caller_id);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    -- Tip thật (nếu chủ app có đặt): trừ Credit chủ app, cộng cho tester.
    -- Giữ nguyên hành vi cũ: tip được trả trước, sau đó mới cộng thưởng duyệt.
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
        format(
            '%s duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)',
            case when v_is_owner then 'Chủ app' else 'Admin/Moderator' end,
            v_extra_reward
        ),
        v_new_balance
    );

    -- Chỉ chủ app thật mới nhận +2 khi bấm duyệt.
    if v_is_owner then
        v_owner_bonus := 2;
        update public.profiles
        set credits = credits + v_owner_bonus
        where id = v_camp.owner_id
        returning credits into v_owner_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_camp.owner_id,
            v_owner_bonus,
            'owner_approval_bonus',
            format('Duyệt báo cáo kiểm thử (+%s Credit)', v_owner_bonus),
            v_owner_balance
        );
    end if;

    update public.checkins
    set note = 'Đã kiểm thử và được duyệt'
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    if not found then
        insert into public.checkins (campaign_id, user_id, checkin_date, note)
        values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được duyệt')
        on conflict (campaign_id, user_id, checkin_date)
        do update set note = 'Đã kiểm thử và được duyệt';
    end if;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'owner_credits_awarded', v_owner_bonus,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'owner_balance', v_owner_balance,
        'message', format(
            'Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester%s)',
            v_extra_reward,
            case when v_owner_bonus > 0 then format(' và +%s Credit cho bạn', v_owner_bonus) else '' end
        )
    );
end;
$$;

revoke execute on function public.approve_tester_report(uuid, int) from public, anon;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;


-- ============================================================================
-- 3. reject_tester_report — thu hồi đúng số tạm nhận, chặn sau 8h
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

    -- Quá 8h: không cho từ chối tay, để auto_accept xử lý.
    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    -- Ghi audit TRƯỚC khi tính cảnh báo: event này cũng được tính vào hôm nay.
    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'reject', p_reason
    );

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    -- 1. Thu hồi đúng số Credit THỰC đã cấp khi nộp (10 mới, 2 cũ).
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(10, coalesce(v_report.provisional_granted_credits, 2)))
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
            format('Thu hồi %s Credit tạm nhận do báo cáo bị từ chối: %s', v_deduct, coalesce(p_reason, 'Chưa đạt yêu cầu')),
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
-- 4. auto_accept_expired_reports — giữ 10, không thưởng +2, tip vẫn trả
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
    v_base int;
    v_total_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded, provisional_granted_credits
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        v_base := coalesce(
            nullif(v_report.provisional_granted_credits, 0),
            nullif(v_report.bonus_awarded, 0),
            0
        );
        v_total_bonus := coalesce(nullif(v_report.bonus_awarded, 0), v_base) + v_tip;

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
        set note = 'Tự động duyệt sau 8h'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (
                v_report.campaign_id, v_report.user_id,
                (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                'Tự động duyệt sau 8h'
            )
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Tự động duyệt sau 8h';
        end if;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit đã nhận + %s Credit boa từ chủ app). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit đã nhận). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base)
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
-- 5. trg_notify_tester_report — cập nhật nội dung thông báo
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
                format('Ứng dụng "%s" vừa nhận được 1 báo cáo kiểm thử mới. Bạn có 8 giờ để kiểm tra và duyệt (+2 Credit thưởng cho tester và +2 Credit cho bạn nếu duyệt).', coalesce(v_camp_name, 'của bạn')),
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
                format('Tester vừa nộp lại kết quả kiểm thử cho ứng dụng "%s" sau khi bị từ chối. Bạn có 8 giờ để kiểm tra và duyệt (+2 Credit thưởng cho tester và +2 Credit cho bạn nếu duyệt).', coalesce(v_camp_name, 'của bạn')),
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

    -- 4. Chủ app/Admin/Moderator duyệt hoặc từ chối
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử được phê duyệt! 🎉',
                format('Báo cáo của bạn cho ứng dụng "%s" đã được duyệt thành công! Tổng cộng bạn nhận được %s Credit cho lượt test này. Cảm ơn bạn!',
                    coalesce(v_camp_name, 'ứng dụng'),
                    coalesce(new.bonus_awarded, 0)
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
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Lý do: "%s". %s Credit tạm nhận đã bị thu hồi. Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason, v_clawed)
                    when v_clawed > 0 then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. %s Credit tạm nhận đã bị thu hồi. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), v_clawed)
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Lý do: "%s". Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'))
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


notify pgrst, 'reload schema';
