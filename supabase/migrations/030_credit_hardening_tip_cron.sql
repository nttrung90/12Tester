-- ============================================================================
-- 12 Tester — Supabase migration 030: Credit Hardening, Tip thật, Cron phạt vắng mặt
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nội dung:
-- 1. Top-up: khóa RPC khỏi client (chỉ service_role), thêm unique index
--    purchase_token cho đơn completed, kiểm tra gói is_active khi xử lý.
-- 2. Tip thật: RPC set_campaign_tip (owner/admin) + helper _grant_report_tip
--    trả tip cho tester khi báo cáo được duyệt (chủ app duyệt hoặc auto 8h):
--    trừ Credit chủ app, cộng Credit tester, ghi credit_logs 2 phía.
-- 3. Edge accounting: cột tester_reports.provisional_granted_credits ghi số
--    Credit THỰC đã cấp khi nộp; reject chỉ thu hồi đúng số đã cấp và chỉ xóa
--    dòng checkin tạm tính (không xóa checkin xác nhận khác trong ngày).
-- 4. Bật cron apply_daily_inactivity_penalty lúc 00:05 giờ VN (17:05 UTC).
-- ============================================================================


-- ============================================================================
-- 1. TOP-UP: khóa RPC khỏi client + chống trùng token + kiểm tra gói active
-- ============================================================================

-- Mỗi purchase_token chỉ được hoàn tất 1 đơn (chống double-spend khi check-then-insert).
create unique index if not exists uq_topup_orders_purchase_token_completed
    on public.topup_orders (purchase_token)
    where payment_status = 'completed' and purchase_token is not null;


create or replace function public.process_topup_order(
    p_package_id    text,
    p_purchase_token text default null,
    p_order_ref     text default null,
    p_payment_method text default 'google_play',
    p_raw_receipt   text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id       uuid := auth.uid();
    v_pkg           record;
    v_existing_order record;
    v_total_credits int;
    v_new_balance   int;
    v_order_id      uuid;
    v_note          text;
begin
    -- 1. Ensure authenticated
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- 2. Check for duplicate purchase token (Idempotency / anti double-spend)
    if p_purchase_token is not null and trim(p_purchase_token) <> '' then
        select * into v_existing_order
        from public.topup_orders
        where purchase_token = trim(p_purchase_token)
          and payment_status = 'completed'
        limit 1;

        if found then
            -- Token already processed previously, return existing balance safely
            select credits into v_new_balance from public.profiles where id = v_user_id;
            return jsonb_build_object(
                'status', 'already_processed',
                'order_id', v_existing_order.id,
                'credits_awarded', v_existing_order.credits_awarded,
                'current_balance', coalesce(v_new_balance, 0),
                'message', 'Giao dịch này đã được cộng điểm trước đó.'
            );
        end if;
    end if;

    -- 3. Fetch package details
    select * into v_pkg
    from public.topup_packages
    where id = p_package_id;

    if not found then
        raise exception 'PACKAGE_NOT_FOUND: Gói nạp không tồn tại hoặc đã ngừng hoạt động.';
    end if;

    if v_pkg.is_active is not true then
        raise exception 'PACKAGE_INACTIVE: Gói nạp đã ngừng hoạt động.';
    end if;

    v_total_credits := v_pkg.credits + coalesce(v_pkg.bonus_credits, 0);

    -- 4. Create completed order record
    insert into public.topup_orders (
        user_id,
        package_id,
        credits_awarded,
        price_usd,
        price_vnd,
        payment_method,
        payment_status,
        purchase_token,
        order_reference,
        raw_receipt,
        completed_at
    )
    values (
        v_user_id,
        v_pkg.id,
        v_total_credits,
        v_pkg.price_usd,
        v_pkg.price_vnd,
        coalesce(p_payment_method, 'google_play'),
        'completed',
        nullif(trim(coalesce(p_purchase_token, '')), ''),
        p_order_ref,
        p_raw_receipt,
        now()
    )
    returning id into v_order_id;

    -- 5. Atomically increment user credits in profiles
    update public.profiles
    set credits = credits + v_total_credits
    where id = v_user_id
    returning credits into v_new_balance;

    -- 6. Insert audit record into credit_logs
    v_note := 'Nạp ' || v_total_credits || ' Credit (' || v_pkg.title || ')';
    if v_pkg.bonus_credits > 0 then
        v_note := v_note || ' - Tặng +' || v_pkg.bonus_credits || ' C';
    end if;

    insert into public.credit_logs (
        user_id,
        amount,
        action,
        note,
        balance
    )
    values (
        v_user_id,
        v_total_credits,
        'topup',
        v_note,
        v_new_balance
    );

    -- 7. Return success result
    return jsonb_build_object(
        'status', 'success',
        'order_id', v_order_id,
        'credits_awarded', v_total_credits,
        'current_balance', v_new_balance,
        'message', 'Nạp ' || v_total_credits || ' Credit thành công!'
    );
end;
$$;

-- Top-up chỉ được xử lý phía server (Edge Function xác thực Google Play receipt
-- gọi bằng service_role). Client KHÔNG được gọi trực tiếp để tránh mint Credit.
revoke execute on function public.process_topup_order(text, text, text, text, text) from public, anon, authenticated;
grant execute on function public.process_topup_order(text, text, text, text, text) to service_role;


-- ============================================================================
-- 2. TIP THẬT: trừ Credit chủ app, cộng cho tester khi báo cáo được duyệt
-- ============================================================================

-- 2.1. Chủ app (hoặc admin) đặt mức tip mỗi lượt duyệt: 0/1/2/3/5/10 Credit.
create or replace function public.set_campaign_tip(
    p_campaign_id uuid,
    p_tip_credits int
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_role text;
    v_camp record;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_tip_credits is null or p_tip_credits not in (0, 1, 2, 3, 5, 10) then
        raise exception 'INVALID_TIP_AMOUNT: Mức tip chỉ nhận 0/1/2/3/5/10 Credit.';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role into v_role from public.profiles where id = v_uid;
    if v_camp.owner_id <> v_uid and lower(coalesce(v_role, '')) <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    update public.campaigns
    set tip_credits = p_tip_credits,
        is_boosted = (p_tip_credits > 0)
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'tip_credits', p_tip_credits,
        'is_boosted', p_tip_credits > 0
    );
end;
$$;

revoke execute on function public.set_campaign_tip(uuid, int) from public, anon;
grant execute on function public.set_campaign_tip(uuid, int) to authenticated, service_role;


-- 2.2. Helper nội bộ: trả tip cho tester của một báo cáo vừa được duyệt.
--      Khóa dòng profile chủ app (FOR UPDATE) để không chi vượt số dư khi
--      nhiều báo cáo được duyệt song song. Không đủ Credit -> bỏ qua tip.
create or replace function public._grant_report_tip(
    p_campaign_id uuid,
    p_tester_id uuid
)
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_tip int;
    v_app_name text;
    v_owner_balance int;
    v_tester_balance int;
begin
    select owner_id, tip_credits, app_name
      into v_owner_id, v_tip, v_app_name
    from public.campaigns
    where id = p_campaign_id;

    if v_owner_id is null
       or coalesce(v_tip, 0) <= 0
       or v_owner_id = p_tester_id then
        return 0;
    end if;

    if not exists (select 1 from public.profiles where id = p_tester_id) then
        return 0;
    end if;

    select credits into v_owner_balance
    from public.profiles
    where id = v_owner_id
    for update;

    if coalesce(v_owner_balance, 0) < v_tip then
        return 0;
    end if;

    update public.profiles
    set credits = credits - v_tip
    where id = v_owner_id
    returning credits into v_owner_balance;

    update public.profiles
    set credits = credits + v_tip
    where id = p_tester_id
    returning credits into v_tester_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_owner_id,
        -v_tip,
        'campaign_tip_paid',
        format('Boa cho tester ứng dụng "%s" (mỗi lượt duyệt, -%s Credit)', coalesce(v_app_name, 'của bạn'), v_tip),
        v_owner_balance
    );

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        p_tester_id,
        v_tip,
        'campaign_tip_received',
        format('Nhận %s Credit boa từ chủ app "%s"', v_tip, coalesce(v_app_name, '')),
        v_tester_balance
    );

    return v_tip;
end;
$$;

-- Chỉ dùng nội bộ từ các RPC security definer; không cho client gọi trực tiếp.
revoke execute on function public._grant_report_tip(uuid, uuid) from public, anon, authenticated;


-- ============================================================================
-- 3. EDGE ACCOUNTING: ghi số Credit thực đã cấp khi nộp báo cáo
-- ============================================================================
-- default 2 = hành vi cũ (an toàn cho các báo cáo pending có từ trước).
alter table public.tester_reports
    add column if not exists provisional_granted_credits int not null default 2;


-- ============================================================================
-- 4. submit_tester_report — bản 027/028 + ghi provisional_granted_credits
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
                    provisional_granted_credits = 0,
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
                    v_provisional_amount := v_regrant;
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
                    provisional_granted_credits = v_regrant,
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
                provisional_granted_credits,
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
                provisional_granted_credits,
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
                2,
                p_image_hash,
                now()
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := 2;
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
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount
    );
end;
$$;

-- Chỉ tài khoản đã đăng nhập được nộp báo cáo (RPC tự raise nếu thiếu uid).
revoke execute on function public.submit_tester_report(uuid, text, text, int, text) from public, anon;
grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, service_role;


-- ============================================================================
-- 5. approve_tester_report — bản 027/028 + trả tip cho tester
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
    v_tip int := 0;
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

    -- Tip thật: trừ Credit chủ app, cộng cho tester (bỏ qua nếu chủ app không đủ Credit)
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);

    -- Tester đã nhận 2 Credit tạm lúc nộp. Chủ app duyệt: Thưởng thêm v_extra_reward (+1 C) + tip
    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

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
-- 6. reject_tester_report — bản 027/028 + thu hồi đúng số đã cấp
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

    -- 1. Thu hồi đúng số Credit THỰC đã cấp khi nộp (không âm điểm nếu số dư thấp).
    --    Ghi lại số THỰC thu hồi vào provisional_clawed để lần nộp lại chỉ
    --    được cấp lại đúng bấy nhiêu (chống farm Credit).
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

    -- 3. Hủy bỏ kết quả ghi nhận tạm thời: chỉ xóa dòng checkin TẠM TÍNH
    --    (không xóa checkin xác nhận khác trong ngày của tester).
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

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

revoke execute on function public.reject_tester_report(uuid, text) from public, anon;
grant execute on function public.reject_tester_report(uuid, text) to authenticated, service_role;


-- ============================================================================
-- 7. auto_accept_expired_reports — bản 016 + trả tip cho tester
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
    v_camp_name text;
    v_tip int;
    v_total_bonus int;
begin
    -- Chặn trigger trg_on_tester_report_event bắn thông báo "được duyệt +1 Credit" sai
    perform set_config('app.suppress_report_notify', '1', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        -- Tester đã tạm nhận 2 Credit khi nộp báo cáo.
        -- Tự động duyệt sau 8h: giữ nguyên 2 Credit tạm nhận, KHÔNG cộng +1,
        -- nhưng TRẢ TIP nếu chủ app có đặt mức tip (trừ Credit chủ app).
        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        -- Cập nhật dòng checkin thành chính thức tự động duyệt
        update public.checkins
        set note = 'Hệ thống tự động duyệt sau 8h'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (
                v_report.campaign_id,
                v_report.user_id,
                (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                'Hệ thống tự động duyệt sau 8h'
            )
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Hệ thống tự động duyệt sau 8h';
        end if;

        -- Gửi thông báo cho tester
        select app_name into v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

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

    -- Mở lại thông báo cho các giao dịch khác trong cùng transaction
    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;


-- ============================================================================
-- 8. trg_notify_tester_report — message thu hồi đúng số Credit thực
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
    -- auto_accept_expired_reports tự gửi thông báo riêng -> không bắn thông báo trùng
    if coalesce(current_setting('app.suppress_report_notify', true), '') = '1' then
        return new;
    end if;

    -- Trường hợp 1: Có báo cáo mới được tạo
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

    -- Trường hợp 2: Tester nộp lại báo cáo sau khi bị từ chối trước đó
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

    -- Trường hợp 3: Chủ app duyệt hoặc từ chối báo cáo
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            -- Tester được chủ app duyệt báo cáo (thưởng thêm +1 Credit, đã gồm tip nếu có)
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
            -- Tester bị từ chối báo cáo: thông báo thu hồi đúng số Credit tạm nhận
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
-- 9. Cron trừ phạt vắng mặt 00:05 giờ VN (17:05 UTC)
-- ============================================================================
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        begin
            perform cron.unschedule('daily_inactivity_penalty_job');
        exception when others then null;
        end;
        perform cron.schedule(
            'daily_inactivity_penalty_job',
            '5 17 * * *',
            'select public.apply_daily_inactivity_penalty();'
        );
    else
        raise notice 'pg_cron chưa được bật — bật tại Database > Extensions rồi chạy lại migration 030 để tự động trừ phạt vắng mặt lúc 00:05 (giờ VN).';
    end if;
exception when others then
    raise notice 'Không thể lên lịch pg_cron: %', sqlerrm;
end $$;


notify pgrst, 'reload schema';
