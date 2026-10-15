-- ============================================================================
-- 12 Tester — Supabase migration 011: Fix Review Rewards, Streaks & Rejection Reasons
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Bổ sung cột lưu lý do từ chối vào bảng tester_reports
alter table public.tester_reports
    add column if not exists rejection_reason text;

-- 2. Cập nhật trigger handle_checkin_reward để chống nhân đôi điểm (Double Reward Bug)
-- Khi chủ app duyệt (approve_tester_report) hoặc hệ thống tự duyệt sau 24h (auto_accept_expired_reports),
-- thủ tục đã cộng điểm và ghi credit_log. Khi insert vào checkins, trigger không được cộng tiếp lần 2.
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
    -- Luôn cập nhật ngày hoạt động gần nhất và reset chuỗi vắng mặt
    update public.profiles
    set last_active_date = new.checkin_date,
        missed_streak = 0
    where id = new.user_id;

    -- Nếu dòng checkin này được tạo từ việc duyệt báo cáo (đã cộng điểm trước đó), bỏ qua không cộng lần 2
    if new.note is not null and (
        new.note like 'Đã kiểm thử%' or
        new.note like 'Tự động duyệt%' or
        new.note like '%chủ app duyệt%'
    ) then
        return new;
    end if;

    -- Đếm số lượt check-in thông thường trong ngày hôm nay của user (bao gồm lượt mới)
    select count(*) into today_reward_count
    from public.checkins
    where user_id = new.user_id and checkin_date = new.checkin_date;

    -- Tính điểm thưởng kiểm thử cơ bản theo mốc
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

-- 3. Trigger tự động cập nhật last_active_date & reset missed_streak ngay khi nộp báo cáo
-- Giúp tester không bị phạt vắng mặt (inactivity penalty) nếu chủ app chậm duyệt báo cáo
create or replace function public.handle_tester_report_submitted()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.profiles
    set last_active_date = (now() at time zone 'Asia/Ho_Chi_Minh')::date,
        missed_streak = 0
    where id = new.user_id;

    return new;
end;
$$;

drop trigger if exists trg_on_tester_report_submitted on public.tester_reports;
create trigger trg_on_tester_report_submitted
    after insert on public.tester_reports
    for each row
    execute function public.handle_tester_report_submitted();

-- 4. Cập nhật hàm reject_tester_report để lưu lý do từ chối (p_reason)
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

    -- Lock and select the report
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

    -- Check caller authority
    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id and coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Update report status to rejected & lưu lý do từ chối
    update public.tester_reports
    set status = 'rejected',
        rejection_reason = p_reason,
        reviewed_at = now()
    where id = p_report_id;

    -- -----------------------------------------------------------------
    -- RULE 1: TESTER REJECTION CHECK (3 rejections / day -> Warning)
    -- -----------------------------------------------------------------
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

    -- -----------------------------------------------------------------
    -- RULE 2: APP OWNER EXCESSIVE REJECTIONS (5 rejections / day -> Warning)
    -- -----------------------------------------------------------------
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
        'rejection_reason', p_reason,
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

-- 5. Cập nhật hàm get_user_quest_summary: tính cả số bài test đã nộp hôm nay (checkins + pending tester_reports)
create or replace function public.get_user_quest_summary()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_start date := date_trunc('week', (now() at time zone 'Asia/Ho_Chi_Minh'))::date;
    v_daily_count int := 0;
    v_weekly_count int := 0;
    v_daily_claimed jsonb := '[]'::jsonb;
    v_weekly_claimed jsonb := '[]'::jsonb;
begin
    if v_uid is not null then
        -- Đếm số ứng dụng đã kiểm thử hôm nay (hợp nhất checkins và các báo cáo đã nộp hôm nay)
        select count(distinct campaign_id) into v_daily_count
        from (
            select campaign_id from public.checkins
            where user_id = v_uid and checkin_date = v_today
            union
            select campaign_id from public.tester_reports
            where user_id = v_uid
              and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
              and status in ('pending', 'approved')
        ) t;

        -- Đếm số ứng dụng đã kiểm thử trong tuần này
        select count(distinct campaign_id || '-' || check_date::text) into v_weekly_count
        from (
            select campaign_id, checkin_date as check_date from public.checkins
            where user_id = v_uid and checkin_date >= v_week_start and checkin_date <= v_today
            union
            select campaign_id, (created_at at time zone 'Asia/Ho_Chi_Minh')::date as check_date from public.tester_reports
            where user_id = v_uid
              and (created_at at time zone 'Asia/Ho_Chi_Minh')::date >= v_week_start
              and (created_at at time zone 'Asia/Ho_Chi_Minh')::date <= v_today
              and status in ('pending', 'approved')
        ) w;

        -- Lấy các mốc ngày đã nhận thưởng hôm nay
        select coalesce(jsonb_agg(milestone), '[]'::jsonb) into v_daily_claimed
        from public.user_quest_claims
        where user_id = v_uid and quest_type = 'daily' and period_key = v_today;

        -- Lấy các mốc tuần đã nhận thưởng tuần này
        select coalesce(jsonb_agg(milestone), '[]'::jsonb) into v_weekly_claimed
        from public.user_quest_claims
        where user_id = v_uid and quest_type = 'weekly' and period_key = v_week_start;
    end if;

    return jsonb_build_object(
        'daily_count', v_daily_count,
        'weekly_count', v_weekly_count,
        'daily_claimed', v_daily_claimed,
        'weekly_claimed', v_weekly_claimed,
        'today_date', v_today,
        'week_start_date', v_week_start,
        'daily_milestones', jsonb_build_array(
            jsonb_build_object('milestone', 10, 'reward', 5),
            jsonb_build_object('milestone', 20, 'reward', 15),
            jsonb_build_object('milestone', 50, 'reward', 30)
        ),
        'weekly_milestones', jsonb_build_array(
            jsonb_build_object('milestone', 150, 'reward', 50),
            jsonb_build_object('milestone', 300, 'reward', 100)
        )
    );
end;
$$;

notify pgrst, 'reload schema';
