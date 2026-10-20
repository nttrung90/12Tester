-- ============================================================================
-- 12 Tester — Supabase migration 027: Fix luồng tương tác Chủ app ↔ Tester
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nội dung sửa:
-- 1. submit_tester_report:
--    - Chặn tài khoản đang bị khóa (is_locked) nộp báo cáo/nhận Credit.
--    - Chặn nộp/nộp lại báo cáo khi campaign KHÔNG ở trạng thái 'open'
--      (trigger trg_reports_require_open chỉ bắt INSERT, nhánh UPDATE lách qua).
--    - Trả về đúng status thật của báo cáo (trước đây trả 'approved' dù DB
--      vẫn 'pending' khi tester đã có checkin xác nhận trong ngày).
--    - Không xóa nội dung/comment cũ khi tester chỉ sửa ảnh (p_content rỗng).
-- 2. Chống farm Credit qua vòng Từ chối → Nộp lại:
--    - Cột mới tester_reports.provisional_clawed ghi số Credit THỰC đã thu hồi
--      khi từ chối (trước đây thu hồi min(số dư, 2) nhưng nộp lại luôn cấp đủ +2
--      → người có số dư thấp lãi +2 mỗi vòng lặp).
--    - Nộp lại chỉ được cấp lại đúng số đã thu hồi (tối đa 2).
-- 3. resume_campaign: bỏ RAISE EXCEPTION sau khi UPDATE 'completed'
--    (exception làm rollback luôn UPDATE → app kẹt 'paused' vĩnh viễn).
--    Trả { success, status: 'completed' } để client hiển thị đúng.
-- 4. sync_campaign_lifecycle: gọi thêm pause_expired_slot_campaigns()
--    (trước đây hàm này bị mồ côi, app hết hạn slot không bao giờ tự tạm dừng).
-- 5. Đồng bộ kiểm tra quyền: admin HOẶC moderator cho approve/reject/
--    get_campaign_reports_for_owner (trước đây moderator thấy UI chủ app
--    nhưng bị PERMISSION_DENIED).
-- 6. apply_report_credit: ghi credit_logs đúng số tiền thực bị kẹp (>= 0).
-- 7. Thu hồi quyền gọi các RPC chỉ dành cho người đã đăng nhập khỏi role anon.
-- ============================================================================


-- ============================================================================
-- 1. Cột theo dõi số Credit đã thu hồi thực tế khi từ chối báo cáo
--    (default 2 = hành vi cũ, an toàn cho dữ liệu lịch sử)
-- ============================================================================
alter table public.tester_reports
    add column if not exists provisional_clawed int not null default 2;


-- ============================================================================
-- 2. submit_tester_report — bản mới nhất (dựa trên migration 024 + fix)
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
    v_regrant int := 0;
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

    -- Tài khoản bị khóa không được nộp báo cáo/nhận Credit (RLS chỉ chặn ghi
    -- trực tiếp; RPC security definer phải tự kiểm tra).
    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    -- Khớp trigger trg_reports_require_open: nhánh UPDATE (nộp lại/sửa báo cáo)
    -- không đi qua trigger nên phải chặn tại đây.
    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- Khóa tuần tự theo (user, campaign, ngày) — hai request song song không cùng
    -- tạo 2 báo cáo + cộng đôi Credit.
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    -- Tự động thêm tester vào danh sách thành viên nếu chưa tham gia
    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lưu/Cập nhật đánh giá sao và nhận xét trong bảng feedbacks.
    -- Khi tester chỉ sửa ảnh (không nhập lại nội dung) thì GIỮ comment cũ.
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = case
            when coalesce(excluded.comment, '') <> '' then excluded.comment
            else public.feedbacks.comment
        end,
        updated_at = now();

    -- Checkin "xác nhận" hôm nay = checkin không phải tạm tính (note NULL cũng tính là xác nhận)
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
          and (note is null or note not like '%tạm tính%')
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
        -- Trường hợp 1: Báo cáo hôm nay đã được duyệt -> chỉ sửa nội dung/ảnh
        if v_existing_report.status = 'approved' then
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = case
                    when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                        then p_image_hash
                    else coalesce(p_image_hash, image_hash)
                end
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'approved';

        -- Trường hợp 2: Báo cáo hôm nay bị từ chối THẬT (đã được chủ app review) -> Tester nộp lại
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if v_already_checked_in then
                -- Đã nhận 2 Credit từ checkin xác nhận hôm nay -> chỉ mở lại báo cáo, không cấp thêm
                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    provisional_clawed = 0,
                    rejection_reason = null,
                    image_hash = case
                        when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                            then p_image_hash
                        else coalesce(p_image_hash, image_hash)
                    end,
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                returning id into v_report_id;

                v_status := 'pending';
            else
                -- Chỉ cấp lại ĐÚNG số Credit đã bị thu hồi khi từ chối (tối đa 2):
                -- nếu lúc từ chối số dư < 2 thì chỉ thu hồi được bấy nhiêu, nộp lại
                -- cũng chỉ nhận lại bấy nhiêu → không thể farm Credit qua vòng lặp.
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
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                update public.tester_reports
                set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                    screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = v_regrant,
                    provisional_clawed = 0,
                    rejection_reason = null,
                    image_hash = case
                        when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                            then p_image_hash
                        else coalesce(p_image_hash, image_hash)
                    end,
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                returning id into v_report_id;

                v_status := 'pending';
            end if;

        -- Trường hợp 3: Báo cáo đang pending (hoặc rejected nhưng chưa được review)
        -- -> chỉ chỉnh sửa nội dung/ảnh, giữ nguyên trạng thái thật
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = case
                    when p_screenshot_url is not null and p_screenshot_url is distinct from screenshot_url
                        then p_image_hash
                    else coalesce(p_image_hash, image_hash)
                end
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := v_existing_report.status;
        end if;
    else
        -- Trường hợp 4: Chưa có báo cáo hôm nay
        if v_already_checked_in then
            -- Đã nhận 2 Credit từ checkin xác nhận hôm nay -> chỉ tạo báo cáo, không cộng thêm,
            -- không ghi đè note checkin đã xác nhận.
            insert into public.tester_reports (
                campaign_id,
                user_id,
                report_type,
                content,
                screenshot_url,
                rating,
                status,
                bonus_awarded,
                provisional_clawed,
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
                0,
                p_image_hash,
                now()
            )
            returning id into v_report_id;

            v_status := 'pending';
        else
            -- Tạo mới & tạm nhận 2 Credit
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
                provisional_clawed,
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
                0,
                p_image_hash,
                now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
        end if;
    end if;

    -- Cập nhật ngày hoạt động gần nhất để chống trừ phạt vắng mặt oan
    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted
    );
end;
$$;

grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, service_role;


-- ============================================================================
-- 3. approve_tester_report — đồng bộ quyền admin/moderator (dựa trên 014)
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
    -- Chủ app duyệt thưởng thêm 1 Credit (+ điểm thưởng tự nguyện p_bonus nếu chủ app tặng thêm)
    v_extra_reward int := 1 + greatest(0, coalesce(p_bonus, 0));
    v_total_bonus int;
    v_new_balance int;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Khóa và lấy bản ghi báo cáo
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

    -- Kiểm tra quyền của người gọi (chủ app hoặc admin/moderator)
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

    -- Tester đã nhận 2 Credit tạm lúc nộp. Chủ app duyệt: Thưởng thêm v_extra_reward (+1 C)
    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

    -- Thưởng thêm Credit cho tester
    update public.profiles
    set credits = credits + v_extra_reward,
        last_active_date = v_today,
        missed_streak = 0
    where id = v_report.user_id
    returning credits into v_new_balance;

    -- Ghi lịch sử Credit
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_extra_reward,
        'test_approved_bonus',
        format('Chủ app duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)', v_extra_reward),
        v_new_balance
    );

    -- Cập nhật dòng checkin thành chính thức đã duyệt
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
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'message', format('Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester)', v_extra_reward)
    );
end;
$$;

grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;


-- ============================================================================
-- 4. reject_tester_report — ghi nhận số Credit đã thu hồi thực tế (dựa trên 014)
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

    -- Khóa và lấy bản ghi báo cáo
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

    -- Kiểm tra quyền chủ app hoặc admin/moderator
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

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    -- 1. Thu hồi Credit tạm nhận (đảm bảo không âm điểm nếu số dư < 2).
    --    Ghi lại số THỰC thu hồi vào provisional_clawed để lần nộp lại chỉ
    --    được cấp lại đúng bấy nhiêu (chống farm Credit).
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(coalesce(v_tester_credits, 0), 2);
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

    -- 3. Hủy bỏ kết quả ghi nhận tạm thời: Xóa dòng checkin tạm tính
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date;

    -- 4. Kiểm tra cảnh báo cho Tester (3 lần bị từ chối / ngày)
    select count(*) into v_tester_today_rejections
    from public.tester_reports
    where user_id = v_report.user_id
      and status = 'rejected'
      and date(coalesce(reviewed_at, now()) at time zone 'Asia/Ho_Chi_Minh') = date(now() at time zone 'Asia/Ho_Chi_Minh');

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_report.user_id
              and warning_type = 'tester_rejection_streak'
              and warning_date = current_date
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_report.user_id, 'tester_rejection_streak', v_msg_tester, current_date);
            v_tester_warned := true;
            v_tester_warning_msg := v_msg_tester;

            select count(*) into v_tester_weekly_warnings
            from public.user_warnings
            where user_id = v_report.user_id
              and warning_date >= (current_date - 6);

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    -- 5. Kiểm tra cảnh báo cho Chủ app (5 lần từ chối / ngày)
    select count(*) into v_owner_today_rejections
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where c.owner_id = v_caller_id
      and tr.status = 'rejected'
      and date(coalesce(tr.reviewed_at, now()) at time zone 'Asia/Ho_Chi_Minh') = date(now() at time zone 'Asia/Ho_Chi_Minh');

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_caller_id
              and warning_type = 'owner_excessive_rejections'
              and warning_date = current_date
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date)
            values (v_caller_id, 'owner_excessive_rejections', v_msg_owner, current_date);
            v_owner_warned := true;
            v_owner_warning_msg := v_msg_owner;

            select count(*) into v_owner_weekly_warnings
            from public.user_warnings
            where user_id = v_caller_id
              and warning_date >= (current_date - 6);

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

grant execute on function public.reject_tester_report(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 5. resume_campaign — không dùng RAISE sau UPDATE (tránh rollback)
-- ============================================================================
drop function if exists public.resume_campaign(uuid);

create or replace function public.resume_campaign(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_camp record;
    v_missed int;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id for update;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp.owner_id <> v_uid then
        raise exception 'PERMISSION_DENIED';
    end if;
    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    -- Quá cửa sổ 15 ngày → chuyển sang hoàn thành và trả kết quả (KHÔNG raise,
    -- vì exception sẽ rollback luôn UPDATE khiến app kẹt 'paused').
    if v_camp.created_at <= now() - interval '15 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'completed'
        );
    end if;

    -- Chủ app vẫn đang vắng 3 ngày liên tục → yêu cầu làm nhiệm vụ ngày trước
    select greatest(missed_streak, (current_date - last_active_date - 1))
    into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 3 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$$;

grant execute on function public.resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 6. sync_campaign_lifecycle — gọi thêm pause_expired_slot_campaigns
-- ============================================================================
create or replace function public.sync_campaign_lifecycle()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_completed int := 0;
    v_paused int := 0;
    v_slot_paused int := 0;
begin
    v_completed := public.complete_expired_campaigns();
    v_paused := public.pause_inactive_owner_campaigns();
    -- Trước đây hàm này bị mồ côi: app có slot hết hạn không bao giờ tự tạm dừng.
    v_slot_paused := public.pause_expired_slot_campaigns();
    return jsonb_build_object(
        'completed', v_completed,
        'paused', v_paused,
        'slot_paused', v_slot_paused
    );
end;
$$;

grant execute on function public.sync_campaign_lifecycle() to authenticated, service_role;


-- ============================================================================
-- 7. get_campaign_reports_for_owner — đồng bộ quyền admin/moderator (dựa trên 016)
-- ============================================================================
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

grant execute on function public.get_campaign_reports_for_owner(uuid) to authenticated, service_role;


-- ============================================================================
-- 8. apply_report_credit — ghi log đúng số tiền thực bị kẹp (dựa trên 022)
-- ============================================================================
create or replace function public.apply_report_credit(
    p_report_id uuid,
    p_amount int,
    p_action text default '',
    p_note text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller uuid := auth.uid();
    v_role text;
    v_target uuid;
    v_owner uuid;
    v_current int;
    v_new int;
    v_actual int;
begin
    if v_caller is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select tr.user_id, c.owner_id
      into v_target, v_owner
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where tr.id = p_report_id;

    if v_target is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    select role into v_role
    from public.profiles
    where id = v_caller;

    if v_owner is distinct from v_caller
       and lower(coalesce(v_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Khoá dòng profile mục tiêu: hai lượt cộng/trừ song song không mất cập nhật.
    select credits into v_current
    from public.profiles
    where id = v_target
    for update;

    if v_current is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_new := greatest(v_current + p_amount, 0);
    -- Số tiền thực thay đổi (đã bị kẹp ở mức >= 0) — khớp với số dư mới.
    v_actual := v_new - v_current;

    update public.profiles
    set credits = v_new
    where id = v_target;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_target,
        v_actual,
        coalesce(nullif(trim(p_action), ''), 'report_adjust'),
        p_note,
        v_new
    );

    return jsonb_build_object(
        'success', true,
        'user_id', v_target,
        'previous_credits', v_current,
        'new_credits', v_new,
        'amount', v_actual
    );
end;
$$;

grant execute on function public.apply_report_credit(uuid, int, text, text) to authenticated, service_role;


-- ============================================================================
-- 9. Thu hồi quyền gọi RPC chỉ dành cho người đã đăng nhập khỏi role anon
-- ============================================================================
do $$
declare
    v_sig text;
begin
    foreach v_sig in array array[
        'public.submit_tester_report(uuid, text, text, int, text)',
        'public.submit_tester_report(uuid, text, text, int)',
        'public.approve_tester_report(uuid, int)',
        'public.reject_tester_report(uuid, text)',
        'public.auto_accept_expired_reports()',
        'public.get_campaign_reports_for_owner(uuid)',
        'public.unlock_or_renew_slot(int)'
    ]
    loop
        if to_regprocedure(v_sig) is not null then
            execute format('revoke execute on function %s from anon, public', v_sig);
            -- Cấp lại quyền cho authenticated/service_role: trước đây các hàm như
            -- auto_accept_expired_reports / unlock_or_renew_slot chỉ sống nhờ quyền
            -- PUBLIC mặc định của Postgres (full_schema không grant tường minh);
            -- nếu chỉ revoke mà không grant lại thì DB dựng mới sẽ hỏng quyền gọi.
            execute format('grant execute on function %s to authenticated, service_role', v_sig);
        end if;
    end loop;
end $$;


notify pgrst, 'reload schema';
