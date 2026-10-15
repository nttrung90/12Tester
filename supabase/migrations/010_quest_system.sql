-- ============================================================================
-- 12 Tester — Supabase migration 010: Quests System (Nhiệm vụ Ngày & Tuần)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Cập nhật hàm trigger thưởng điểm kiểm thử cơ bản (handle_checkin_reward)
-- Thể lệ:
--   - App 1 - 30: +2 Credit / app (tối đa 60 Credit)
--   - App 31 - 50: +1 Credit / app (tối đa 20 Credit)
--   - Từ app 51 trở đi: 0 Credit cơ bản (vẫn ghi nhận dòng checkin hợp lệ để tính nhiệm vụ ngày & tuần)
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
    -- Đếm số lượt check-in trong ngày hôm nay của user (bao gồm lượt mới)
    select count(*) into today_reward_count
    from public.checkins
    where user_id = new.user_id and checkin_date = new.checkin_date;

    -- Luôn cập nhật ngày hoạt động gần nhất và reset chuỗi vắng mặt
    update public.profiles
    set last_active_date = new.checkin_date,
        missed_streak = 0
    where id = new.user_id;

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

-- Đảm bảo trigger on_checkin_reward_trigger vẫn liên kết chính xác
drop trigger if exists on_checkin_reward_trigger on public.checkins;
create trigger on_checkin_reward_trigger
    after insert on public.checkins
    for each row execute function public.handle_checkin_reward();


-- 2. Bảng lưu vết nhận thưởng nhiệm vụ (user_quest_claims)
create table if not exists public.user_quest_claims (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid not null references public.profiles(id) on delete cascade,
    quest_type     text not null check (quest_type in ('daily', 'weekly')),
    milestone      int not null,        -- 10, 20, 50 (ngày) hoặc 150, 300 (tuần)
    reward_credits int not null,        -- 5, 15, 30 hoặc 50, 100
    period_key     date not null,       -- Ngày kiểm thử (với ngày) hoặc ngày đầu tuần Thứ Hai (với tuần)
    claimed_at     timestamptz not null default now(),
    constraint uq_user_quest_claim unique (user_id, quest_type, milestone, period_key)
);

create index if not exists idx_user_quest_claims_lookup 
    on public.user_quest_claims(user_id, quest_type, period_key);

alter table public.user_quest_claims enable row level security;

drop policy if exists "quest_claims_select_own" on public.user_quest_claims;
create policy "quest_claims_select_own"
    on public.user_quest_claims for select to authenticated
    using (user_id = auth.uid());


-- 3. Stored Procedure RPC: get_user_quest_summary()
-- Lấy tổng hợp tiến độ nhiệm vụ và trạng thái nhận thưởng
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
        -- Đếm số lượt check-in hôm nay
        select count(*) into v_daily_count
        from public.checkins
        where user_id = v_uid and checkin_date = v_today;

        -- Đếm số lượt check-in trong tuần này (từ Thứ Hai)
        select count(*) into v_weekly_count
        from public.checkins
        where user_id = v_uid and checkin_date >= v_week_start and checkin_date <= v_today;

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


-- 4. Stored Procedure RPC: claim_quest_reward(p_quest_type text, p_milestone int)
-- Người dùng bấm nút "Nhận thưởng" thủ công khi đạt mốc
create or replace function public.claim_quest_reward(
    p_quest_type text,
    p_milestone int
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_is_locked boolean := false;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_week_start date := date_trunc('week', (now() at time zone 'Asia/Ho_Chi_Minh'))::date;
    v_period_key date;
    v_reward int := 0;
    v_current_count int := 0;
    v_new_balance int := 0;
begin
    if v_uid is null then
        return jsonb_build_object('success', false, 'error', 'NOT_AUTHENTICATED', 'message', 'Vui lòng đăng nhập để nhận thưởng.');
    end if;

    -- Kiểm tra tài khoản có bị khóa không
    select is_locked into v_is_locked from public.profiles where id = v_uid;
    if v_is_locked then
        return jsonb_build_object('success', false, 'error', 'ACCOUNT_LOCKED', 'message', 'Tài khoản của bạn đang bị tạm khóa.');
    end if;

    -- Kiểm tra tính hợp lệ của mốc và loại nhiệm vụ
    if p_quest_type = 'daily' then
        v_period_key := v_today;
        if p_milestone = 10 then
            v_reward := 5;
        elsif p_milestone = 20 then
            v_reward := 15;
        elsif p_milestone = 50 then
            v_reward := 30;
        else
            return jsonb_build_object('success', false, 'error', 'INVALID_MILESTONE', 'message', 'Mốc nhiệm vụ ngày không hợp lệ.');
        end if;

        -- Đếm số lượt checkin hôm nay
        select count(*) into v_current_count
        from public.checkins
        where user_id = v_uid and checkin_date = v_today;

    elsif p_quest_type = 'weekly' then
        v_period_key := v_week_start;
        if p_milestone = 150 then
            v_reward := 50;
        elsif p_milestone = 300 then
            v_reward := 100;
        else
            return jsonb_build_object('success', false, 'error', 'INVALID_MILESTONE', 'message', 'Mốc nhiệm vụ tuần không hợp lệ.');
        end if;

        -- Đếm số lượt checkin trong tuần này
        select count(*) into v_current_count
        from public.checkins
        where user_id = v_uid and checkin_date >= v_week_start and checkin_date <= v_today;

    else
        return jsonb_build_object('success', false, 'error', 'INVALID_QUEST_TYPE', 'message', 'Loại nhiệm vụ không hợp lệ.');
    end if;

    -- Kiểm tra xem người dùng đã đạt mốc chưa
    if v_current_count < p_milestone then
        return jsonb_build_object(
            'success', false,
            'error', 'MILESTONE_NOT_REACHED',
            'current_count', v_current_count,
            'milestone', p_milestone,
            'message', format('Bạn chưa hoàn thành mốc kiểm thử này (hiện tại %s/%s)!', v_current_count, p_milestone)
        );
    end if;

    -- Kiểm tra xem đã nhận thưởng mốc này trong chu kỳ chưa
    if exists (
        select 1 from public.user_quest_claims
        where user_id = v_uid and quest_type = p_quest_type and milestone = p_milestone and period_key = v_period_key
    ) then
        return jsonb_build_object(
            'success', false,
            'error', 'ALREADY_CLAIMED',
            'message', 'Bạn đã nhận phần thưởng này rồi!'
        );
    end if;

    -- Ghi nhận nhận thưởng
    insert into public.user_quest_claims (user_id, quest_type, milestone, reward_credits, period_key)
    values (v_uid, p_quest_type, p_milestone, v_reward, v_period_key);

    -- Cộng dồn Credit vào tài khoản
    update public.profiles
    set credits = credits + v_reward
    where id = v_uid
    returning credits into v_new_balance;

    -- Ghi nhật ký biến động số dư (Credit Log)
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_uid,
        v_reward,
        case when p_quest_type = 'daily' then 'quest_daily_reward' else 'quest_weekly_reward' end,
        case when p_quest_type = 'daily'
             then format('Thưởng hoàn thành nhiệm vụ ngày (Mốc %s lần test, +%s Credit)', p_milestone, v_reward)
             else format('Thưởng hoàn thành nhiệm vụ tuần (Mốc %s lần test, +%s Credit)', p_milestone, v_reward)
        end,
        v_new_balance
    );

    -- Tự động gửi thông báo chúc mừng
    insert into public.notifications (user_id, title, message, type)
    values (
        v_uid,
        case when p_quest_type = 'daily' then '🎉 Thưởng nhiệm vụ ngày!' else '🏆 Thưởng nhiệm vụ tuần!' end,
        case when p_quest_type = 'daily'
             then format('Chúc mừng bạn đã hoàn thành mốc test %s app hôm nay và nhận được +%s Credit!', p_milestone, v_reward)
             else format('Chúc mừng bạn đã hoàn thành mốc test %s app trong tuần này và nhận được +%s Credit!', p_milestone, v_reward)
        end,
        'credit'
    );

    return jsonb_build_object(
        'success', true,
        'quest_type', p_quest_type,
        'milestone', p_milestone,
        'reward', v_reward,
        'new_balance', v_new_balance,
        'message', format('Nhận thành công +%s Credit!', v_reward)
    );
end;
$$;
