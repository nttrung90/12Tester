-- ============================================================================
-- 12 Tester — Supabase migration 023: Credit & Privilege Hardening
-- Vá các lỗ hổng cộng/trừ Credit và phân quyền:
--   1. profiles: chặn client ghi trực tiếp credits/role/is_locked (revoke table-level,
--      chỉ grant column-level các cột client thực sự dùng).
--   2. make_user_admin / make_user_moderator: thêm guard admin + revoke khỏi client.
--   3. apply_daily_inactivity_penalty: revoke khỏi client (hàm vẫn ngủ, không schedule).
--   4. tester_reports RLS: ép INSERT chỉ status='pending' (chặn spoof 'rejected'/'approved'),
--      UPDATE chỉ chủ app (chặn tester tự sửa trạng thái báo cáo của mình).
--   5. handle_checkin_reward: mở rộng pattern suppress để khớp note auto duyệt
--      'Hệ thống tự động duyệt sau 8h' (không cộng +2 ngoài ý muốn).
--   6. submit_tester_report: khóa advisory chống race, v_already_checked_in null-safe,
--      Case 2 yêu cầu reject thật (reviewed_at not null), Case 2/4 không cộng trùng
--      2 Credit khi đã có checkin xác nhận trong ngày.
--   7. handle_campaign_target_cost: trừ chênh lệch khi tăng target_testers qua UPDATE.
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================


-- ============================================================================
-- 1. profiles: siết quyền ghi của client
-- ============================================================================
-- Client chỉ được ghi: id, email, display_name, avatar_url (ProfileUpsert).
-- credits/role/is_locked/last_active_date/missed_streak chỉ server (definer) ghi.
revoke insert, update on public.profiles from public, anon, authenticated;

grant insert (id, email, display_name, avatar_url) on public.profiles to authenticated;
grant update (id, email, display_name, avatar_url) on public.profiles to authenticated;


-- ============================================================================
-- 2. make_user_admin / make_user_moderator: guard admin + revoke khỏi client
-- ============================================================================
create or replace function public.make_user_admin(p_email text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count int;
    v_caller uuid := auth.uid();
begin
    -- Cho phép gọi từ SQL Editor / service_role (không có JWT), chặn mọi caller khác không phải admin
    if v_caller is not null and not exists (
        select 1 from public.profiles where id = v_caller and role = 'admin'
    ) then
        raise exception 'PERMISSION_DENIED: Chỉ admin mới được bổ nhiệm vai trò';
    end if;

    update public.profiles
    set role = 'admin'
    where lower(email) = lower(trim(p_email));

    get diagnostics v_count = row_count;
    if v_count > 0 then
        return format('Đã bổ nhiệm tài khoản %s thành Admin thành công.', p_email);
    else
        return format('Không tìm thấy tài khoản với email %s.', p_email);
    end if;
end;
$$;

create or replace function public.make_user_moderator(p_email text)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    v_count int;
    v_caller uuid := auth.uid();
begin
    if v_caller is not null and not exists (
        select 1 from public.profiles where id = v_caller and role = 'admin'
    ) then
        raise exception 'PERMISSION_DENIED: Chỉ admin mới được bổ nhiệm vai trò';
    end if;

    update public.profiles
    set role = 'moderator'
    where lower(email) = lower(trim(p_email));

    get diagnostics v_count = row_count;
    if v_count > 0 then
        return format('Đã bổ nhiệm tài khoản %s thành Moderator thành công.', p_email);
    else
        return format('Không tìm thấy tài khoản với email %s.', p_email);
    end if;
end;
$$;

revoke execute on function public.make_user_admin(text) from public, anon, authenticated;
revoke execute on function public.make_user_moderator(text) from public, anon, authenticated;
grant execute on function public.make_user_admin(text) to service_role;
grant execute on function public.make_user_moderator(text) to service_role;


-- ============================================================================
-- 3. apply_daily_inactivity_penalty: chỉ service_role/cron được gọi
-- ============================================================================
revoke execute on function public.apply_daily_inactivity_penalty() from public, anon, authenticated;
grant execute on function public.apply_daily_inactivity_penalty() to service_role;


-- ============================================================================
-- 4. tester_reports RLS: chặn spoof trạng thái & tester tự sửa báo cáo
-- ============================================================================
-- Insert: chỉ được tạo báo cáo pending, bonus_awarded=0, chưa review.
-- (RPC security definer không bị ảnh hưởng bởi policy này.)
drop policy if exists "reports_insert_tester" on public.tester_reports;
create policy "reports_insert_tester" on public.tester_reports
    for insert to authenticated
    with check (
        user_id = auth.uid()
        and status = 'pending'
        and bonus_awarded = 0
        and reviewed_at is null
        and exists (
            select 1 from public.profiles
            where id = auth.uid() and is_locked = false
        )
    );

-- Update: chỉ chủ app của campaign (đồng bộ với migration 012).
-- Chặn tester PATCH report của mình thành 'rejected' để gọi lại RPC farm +2 vô hạn.
drop policy if exists "reports_update_tester_or_owner" on public.tester_reports;
drop policy if exists "reports_update_owner" on public.tester_reports;
create policy "reports_update_owner" on public.tester_reports
    for update to authenticated
    using (exists (select 1 from public.campaigns c where c.id = campaign_id and c.owner_id = auth.uid()));


-- ============================================================================
-- 5. handle_checkin_reward: khớp cả note 'Hệ thống tự động duyệt sau 8h'
-- ============================================================================
create or replace function public.handle_checkin_reward()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    today_reward_count int;
    v_credit_reward int := 0;
    new_balance int;
begin
    update public.profiles
    set last_active_date = new.checkin_date,
        missed_streak = 0
    where id = new.user_id;

    -- Nếu dòng checkin này được tạo từ việc nộp/duyệt báo cáo (đã xử lý cộng điểm), bỏ qua không cộng lần 2
    if new.note is not null and (
        new.note like 'Đã kiểm thử%' or
        new.note like '%Tự động duyệt%' or
        new.note like '%chủ app duyệt%' or
        new.note like 'Đã nộp kết quả%' or
        new.note like '%tạm tính%'
    ) then
        return new;
    end if;

    select count(*) into today_reward_count
    from public.checkins
    where user_id = new.user_id and checkin_date = new.checkin_date;

    if today_reward_count <= 30 then
        v_credit_reward := 2;
    elsif today_reward_count <= 50 then
        v_credit_reward := 1;
    else
        v_credit_reward := 0;
    end if;

    if v_credit_reward > 0 then
        update public.profiles
        set credits = credits + v_credit_reward
        where id = new.user_id
        returning credits into new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            new.user_id,
            v_credit_reward,
            'checkin_reward',
            format('Thưởng test app (lượt %s/50 trong ngày, +%s Credit)', today_reward_count, v_credit_reward),
            new_balance
        );
    end if;

    return new;
end;
$$;


-- ============================================================================
-- 6. submit_tester_report: chống race & chống cộng trùng 2 Credit
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

    -- Khóa tuần tự theo (user, campaign, ngày) — hai request song song không cùng
    -- tạo 2 báo cáo + cộng đôi Credit.
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

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
        -- Trường hợp 1: Báo cáo hôm nay đã được duyệt từ trước (hoặc đã có checkin xác nhận)
        if v_existing_report.status = 'approved' or v_already_checked_in then
            update public.tester_reports
            set content = coalesce(p_content, content),
                screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                rating = v_rating,
                image_hash = coalesce(p_image_hash, image_hash)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_status := 'approved';

        -- Trường hợp 2: Báo cáo hôm nay bị từ chối THẬT (đã được chủ app review) -> Tester nộp lại
        elsif v_existing_report.status = 'rejected' and v_existing_report.reviewed_at is not null then
            if v_already_checked_in then
                -- Đã nhận 2 Credit từ checkin xác nhận hôm nay -> chỉ mở lại báo cáo, không cấp thêm
                update public.tester_reports
                set content = coalesce(p_content, content),
                    screenshot_url = coalesce(p_screenshot_url, screenshot_url),
                    rating = v_rating,
                    status = 'pending',
                    bonus_awarded = 2,
                    rejection_reason = null,
                    image_hash = coalesce(p_image_hash, image_hash),
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                returning id into v_report_id;

                v_status := 'pending';
            else
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
                    created_at = now(),
                    reviewed_at = null
                where id = v_existing_report.id
                returning id into v_report_id;

                v_status := 'pending';
                v_provisional_granted := true;
            end if;

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

grant execute on function public.submit_tester_report(uuid, text, text, int, text) to authenticated, anon, service_role;


-- ============================================================================
-- 7. handle_campaign_target_cost: trừ chênh lệch khi tăng target qua UPDATE
-- ============================================================================
-- Quy tắc: 12 Test/ngày = 0 Credit, 15 = 10 Credit, 20 = 20 Credit.
-- INSERT: trừ toàn bộ chi phí. UPDATE: chỉ trừ phần chênh lệch khi nâng gói.
create or replace function public.handle_campaign_target_cost()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_cost int := 0;
    v_old_cost int := 0;
    v_delta int := 0;
    v_balance int;
begin
    -- Chuẩn hóa target_testers (chỉ 12/15/20 hợp lệ)
    if new.target_testers is null or new.target_testers not in (12, 15, 20) then
        new.target_testers := 12;
    end if;

    if tg_op = 'UPDATE' then
        if new.target_testers is not distinct from old.target_testers then
            return new;
        end if;

        v_old_cost := case old.target_testers when 15 then 10 when 20 then 20 else 0 end;
        v_cost := case new.target_testers when 15 then 10 when 20 then 20 else 0 end;
        v_delta := v_cost - v_old_cost;
    else
        v_cost := case new.target_testers when 15 then 10 when 20 then 20 else 0 end;
        v_delta := v_cost;
    end if;

    -- Giảm gói hoặc giữ nguyên: không trừ thêm (không hoàn Credit khi hạ gói)
    if v_delta <= 0 then
        return new;
    end if;

    select credits into v_balance
    from public.profiles
    where id = new.owner_id
    for update;

    if v_balance is null or v_balance < v_delta then
        raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần ít nhất % Credit để nâng mục tiêu lên % Test/ngày', v_delta, new.target_testers;
    end if;

    update public.profiles
    set credits = credits - v_delta
    where id = new.owner_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        new.owner_id,
        -v_delta,
        'target_upgrade',
        format('Nâng cấp mục tiêu %s Test/ngày (-%s Credit)', new.target_testers, v_delta),
        v_balance
    );

    return new;
end;
$$;

drop trigger if exists trg_on_campaign_target_cost on public.campaigns;
create trigger trg_on_campaign_target_cost
    before insert or update of target_testers on public.campaigns
    for each row
    execute function public.handle_campaign_target_cost();


notify pgrst, 'reload schema';
