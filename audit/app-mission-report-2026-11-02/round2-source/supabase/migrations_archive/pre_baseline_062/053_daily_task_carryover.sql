-- ==============================================================================
-- 12 Tester - Supabase migration 053: Daily Task Carryover (Cộng dồn app test còn thiếu)
-- ==============================================================================
-- Kế hoạch: F:\AI\12Tester\KE_HOACH_CONG_DON_APP_TEST_CON_THIEU_2026-10-29.md
-- Đánh giá kết hợp: F:\AI\12Tester\REVIEW_KET_HOP_CONG_DON_VA_DAY_APP_2026-10-29.md
--
-- Nghiệp vụ chính:
-- 1. Chủ app (owner) hôm trước chưa test đủ: chuyển số app còn thiếu vào chỉ tiêu hôm sau.
--    Ví dụ: cơ bản 6, thiếu 2 -> hôm nay phải test 8 nếu đủ nguồn app.
-- 2. Tester không đăng app (member) giữ nguyên quy tắc hiện tại: đăng nhập hoặc có lượt test
--    hợp lệ là hoàn thành. Không bắt họ test thêm. Nếu trước đó từng có phần thiếu lúc là owner,
--    phần thiếu đó được tạm giữ, test tự nguyện sẽ giảm phần thiếu nhưng không phạt nếu không test.
-- 3. Thiếu nguồn app khả dụng (A):
--    R = B + C (tổng nghĩa vụ trước giới hạn nguồn)
--    T = min(R, A) (chỉ tiêu thực tế phải hoàn thành hôm nay)
--    Q = max(C, T) (nghĩa vụ giữ lại, gồm phần cũ chưa thể làm hết)
--    carry_out = max(0, Q - D) (phần thiếu chuyển tiếp sang ngày sau)
--    Khi T = 0 (do không có app khả dụng), không phạt và không tăng chuỗi thiếu.
-- 4. Bắt đầu từ ngày có hiệu lực mới (carryover_effective_from), không truy thu quá khứ.
--    Ngày đầu tiên áp dụng: carry_in = 0.
-- 5. Sau 00:00, nếu ngày hôm qua chưa chốt (finalized), owner trả carryover_ready = false.
--    Không giả định phần thiếu = 0, không kết luận owner đã đạt, không reset chuỗi cho đến khi chốt.
-- 6. Tương thích với Đẩy app (migration 052):
--    - Mua đẩy chỉ thay thứ hạng hiển thị và thu 100 Credit; không thay đổi nghĩa vụ test B, C, D, T.
--    - Ưu tiên đẩy hết lúc 00:00; nhiệm vụ cộng dồn có thể chờ chốt từ 00:05. Hai trạng thái độc lập.
--    - Khóa profiles/campaigns nhất quán, chống race condition và deadlock.
-- ==============================================================================

-- 1. Bổ sung các cột lưu trữ sổ cộng dồn trên bảng user_daily_tasks
alter table public.user_daily_tasks
    add column if not exists carryover_version int default null,
    add column if not exists base_required_tests int not null default 0 check (base_required_tests >= 0),
    add column if not exists carry_in_tests int not null default 0 check (carry_in_tests >= 0),
    add column if not exists carry_out_tests int not null default 0 check (carry_out_tests >= 0),
    add column if not exists carry_source_date date default null,
    add column if not exists carryover_effective_from date default null;

comment on column public.user_daily_tasks.carryover_version is 'Phiên bản công thức cộng dồn (1 cho chính sách mới; NULL cho dòng lịch sử)';
comment on column public.user_daily_tasks.base_required_tests is 'Chỉ tiêu cơ bản B của ngày đang chấm (tỷ lệ * số active app)';
comment on column public.user_daily_tasks.carry_in_tests is 'Số app còn thiếu chuyển vào từ ngày trước (C)';
comment on column public.user_daily_tasks.carry_out_tests is 'Số app còn thiếu chuyển sang ngày sau sau khi chốt (carry_out)';
comment on column public.user_daily_tasks.carry_source_date is 'Ngày liền trước dùng làm nguồn chuyển tiếp; giúp phát hiện khoảng trống';
comment on column public.user_daily_tasks.carryover_effective_from is 'Ngày bắt đầu có hiệu lực của chính sách cộng dồn';

-- 2. Bảng quản lý chính sách cộng dồn (daily_task_carryover_policies)
create table if not exists public.daily_task_carryover_policies (
    id bigint generated always as identity primary key,
    effective_from date not null unique,
    is_enabled boolean not null default true,
    operator_note text not null,
    created_at timestamptz not null default clock_timestamp()
);

alter table public.daily_task_carryover_policies enable row level security;
revoke all on public.daily_task_carryover_policies from public, anon, authenticated;
grant all on public.daily_task_carryover_policies to service_role;

-- Baseline chính sách cộng dồn: mặc định có hiệu lực từ 2026-10-30 VN (ngày mai)
insert into public.daily_task_carryover_policies (
    effective_from, is_enabled, operator_note
)
select '2026-10-30'::date, true, 'Migration 053: baseline daily task carryover policy'
where not exists (select 1 from public.daily_task_carryover_policies);

-- 3. Helper lấy chính sách cộng dồn theo ngày
create or replace function public.get_daily_task_carryover_policy(p_date date)
returns public.daily_task_carryover_policies
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_row public.daily_task_carryover_policies;
begin
    if p_date is null then
        return null;
    end if;
    select *
    into v_row
    from public.daily_task_carryover_policies
    where effective_from <= p_date
    order by effective_from desc
    limit 1;
    return v_row;
end;
$$;

revoke execute on function public.get_daily_task_carryover_policy(date) from public, anon, authenticated;
grant execute on function public.get_daily_task_carryover_policy(date) to service_role;

-- 4. Helper thiết lập chính sách cộng dồn (cho quản trị / kiểm thử)
create or replace function public.set_daily_task_carryover_policy(
    p_effective_from date,
    p_is_enabled boolean default true,
    p_operator_note text default 'Configured carryover policy'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_row public.daily_task_carryover_policies;
begin
    if p_effective_from is null then
        raise exception 'INVALID_EFFECTIVE_DATE';
    end if;

    insert into public.daily_task_carryover_policies (
        effective_from, is_enabled, operator_note
    ) values (
        p_effective_from,
        coalesce(p_is_enabled, true),
        coalesce(btrim(p_operator_note), 'Configured carryover policy')
    )
    on conflict (effective_from) do update
    set is_enabled = excluded.is_enabled,
        operator_note = excluded.operator_note
    returning * into v_row;

    return to_jsonb(v_row);
end;
$$;

revoke execute on function public.set_daily_task_carryover_policy(date, boolean, text) from public, anon, authenticated;
grant execute on function public.set_daily_task_carryover_policy(date, boolean, text) to service_role;

-- 5. Helper tính toán trạng thái nhiệm vụ ngày có cộng dồn (get_daily_task_status)
create or replace function public.get_daily_task_status(
    p_user_id uuid,
    p_date date
)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_role text;
    v_last_login date;
    v_streak int := 0;
    v_exempt boolean := false;
    v_k int := 0;
    v_a int := 0;
    v_d int := 0;
    v_base_required int := 0;
    v_carry_in int := 0;
    v_required int := 0;
    v_effective int := 0;
    v_logged_in boolean := false;
    v_met boolean := false;
    v_next_pct int := 1;
    v_mode text;
    v_day_start timestamptz;
    v_next_day_start timestamptz;
    v_window_start timestamptz;
    v_carryover_ready boolean := true;
    v_carry_remaining int := 0;
    v_carry_effective_from date := null;
    v_prev_finalized timestamptz;
    v_prev_carry_out int;
    v_last_carry_out int;
    v_q int := 0;
begin
    if p_user_id is null or p_date is null then
        return null;
    end if;

    select lower(p.role), p.last_login_date, p.daily_missed_streak
    into v_role, v_last_login, v_streak
    from public.profiles p
    where p.id = p_user_id;

    if not found then
        return null;
    end if;

    v_streak := coalesce(v_streak, 0);
    v_exempt := coalesce(v_role in ('admin', 'moderator'), false);

    v_day_start := (p_date::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_next_day_start := ((p_date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_window_start := ((p_date - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh');

    -- K: app của user đang open, hoặc paused do vắng mặt.
    -- App tạo trước 00:00 VN của ngày chấm để bắt đầu tính từ ngày mai.
    select count(*) into v_k
    from public.campaigns c
    where c.owner_id = p_user_id
      and (
          c.status = 'open'
          or (c.status = 'paused' and c.paused_reason = 'owner_inactive')
      )
      and c.created_at < v_day_start
      and c.created_at > v_window_start;

    -- A: app open của người khác, đã tồn tại trong ngày chấm.
    select count(*) into v_a
    from public.campaigns c
    where c.status = 'open'
      and c.owner_id <> p_user_id
      and c.created_at < v_next_day_start
      and c.created_at > v_window_start;

    -- D: số app khác nhau user đã test trong ngày.
    select count(distinct campaign_id) into v_d
    from (
        select ci.campaign_id
        from public.checkins ci
        join public.campaigns c on c.id = ci.campaign_id
        where ci.user_id = p_user_id
          and ci.checkin_date = p_date
          and c.owner_id <> p_user_id
          and (
              ci.note is null
              or (
                  ci.note not like 'Đã kiểm thử%'
                  and ci.note not like 'Đã nộp kết quả%'
                  and ci.note not like '%tạm tính%'
                  and ci.note not like 'Tự động duyệt%'
                  and ci.note not like '%Tự động duyệt%'
                  and ci.note not like '%chủ app duyệt%'
              )
          )

        union

        select tr.campaign_id
        from public.tester_reports tr
        join public.campaigns c on c.id = tr.campaign_id
        where tr.user_id = p_user_id
          and (tr.created_at at time zone 'Asia/Ho_Chi_Minh')::date = p_date
          and tr.status in ('pending', 'approved')
          and c.owner_id <> p_user_id
    ) t;

    -- Login lấy từ lịch sử theo ngày hoặc profile
    select (v_last_login = p_date) or exists (
        select 1
        from public.user_daily_tasks t
        where t.user_id = p_user_id
          and t.task_date = p_date
          and t.logged_in = true
    ) into v_logged_in;

    v_policy := public.get_daily_task_policy(p_date);
    v_base_required := coalesce(v_policy.tests_per_active_app, 3) * v_k;
    v_mode := case when v_k > 0 then 'owner' else 'member' end;

    -- Tính toán phần thiếu chuyển tiếp (Carryover)
    v_carry_policy := public.get_daily_task_carryover_policy(p_date);

    if v_carry_policy is not null and v_carry_policy.is_enabled and p_date >= v_carry_policy.effective_from then
        v_carry_effective_from := v_carry_policy.effective_from;

        if p_date = v_carry_effective_from then
            -- Mốc bắt đầu: không truy thu phần thiếu trước ngày áp dụng
            v_carry_in := 0;
            v_carryover_ready := true;
        else
            -- p_date > effective_from: nguồn từ ngày liền trước
            select t.finalized_at, t.carry_out_tests
            into v_prev_finalized, v_prev_carry_out
            from public.user_daily_tasks t
            where t.user_id = p_user_id and t.task_date = (p_date - 1);

            if v_prev_finalized is not null then
                v_carry_in := coalesce(v_prev_carry_out, 0);
                v_carryover_ready := true;
            else
                -- Ngày hôm qua chưa finalized (khoảng 00:00 - 00:05 hoặc cron trễ)
                if v_exempt or v_mode = 'member' then
                    -- Tester thuần không có app: không bị hoãn trạng thái hoàn thành login
                    -- Lấy phần thiếu cũ được tạm giữ gần nhất (nếu trước đây từng là owner)
                    select t.carry_out_tests
                    into v_last_carry_out
                    from public.user_daily_tasks t
                    where t.user_id = p_user_id
                      and t.task_date >= v_carry_effective_from
                      and t.task_date < p_date
                      and t.finalized_at is not null
                    order by t.task_date desc
                    limit 1;

                    v_carry_in := coalesce(v_last_carry_out, 0);
                    v_carryover_ready := true;
                else
                    -- Chủ app: chưa thể chốt chỉ tiêu mới khi hôm qua chưa finalized
                    v_carryover_ready := false;
                    v_carry_in := 0;
                end if;
            end if;
        end if;
    else
        -- Chính sách cộng dồn chưa có hiệu lực
        v_carry_in := 0;
        v_carryover_ready := true;
        v_carry_effective_from := null;
    end if;

    -- Tính chỉ tiêu và trạng thái hoàn thành
    if v_carryover_ready then
        if v_exempt then
            v_required := v_base_required + v_carry_in;
            v_effective := least(v_required, v_a);
            v_carry_remaining := greatest(0, v_carry_in - v_d);
            v_met := true;
        elsif v_mode = 'member' then
            v_required := 0;
            v_effective := 0;
            -- Member hoàn thành bằng đăng nhập hoặc có test, test tự nguyện giảm phần thiếu cũ
            v_carry_remaining := greatest(0, v_carry_in - v_d);
            v_met := v_logged_in or v_d > 0;
        else
            -- mode = 'owner'
            -- Công thức:
            -- R = B + C
            -- T = min(R, A)
            -- Q = max(C, T)
            -- carry_out = max(0, Q - D)
            v_required := v_base_required + v_carry_in;
            v_effective := least(v_required, v_a);
            v_q := greatest(v_carry_in, v_effective);
            v_carry_remaining := greatest(0, v_q - v_d);
            v_met := (v_d >= v_effective);
        end if;
    else
        -- Owner đang chờ chốt hôm qua: hiển thị chỉ tiêu cơ bản tạm thời, met = false
        v_required := v_base_required;
        v_effective := least(v_required, v_a);
        v_carry_remaining := 0;
        v_met := false;
    end if;

    v_next_pct := case
        when v_streak <= 0 then 1
        when v_streak = 1 then 2
        when v_streak = 2 then 5
        else 10
    end;

    return jsonb_build_object(
        'status_date', to_char(p_date, 'YYYY-MM-DD'),
        'mode', v_mode,
        'active_app_count', v_k,
        'required_tests', v_required,
        'available_apps', v_a,
        'effective_required_tests', v_effective,
        'completed_tests', v_d,
        'logged_in_today', v_logged_in,
        'is_penalty_exempt', v_exempt,
        'missed_streak', v_streak,
        'next_penalty_percent', v_next_pct,
        'met', v_met,
        'base_required_tests', v_base_required,
        'carry_in_tests', v_carry_in,
        'carryover_remaining_tests', coalesce(v_carry_remaining, 0),
        'carryover_ready', v_carryover_ready,
        'carryover_effective_from', case when v_carry_effective_from is not null then to_char(v_carry_effective_from, 'YYYY-MM-DD') else null end
    );
end;
$$;

revoke execute on function public.get_daily_task_status(uuid, date) from public, anon, authenticated;
grant execute on function public.get_daily_task_status(uuid, date) to service_role;

-- 6. Finalizer chốt ngày nguyên tử (public._apply_daily_inactivity_penalty)
create or replace function public._apply_daily_inactivity_penalty(p_eval date)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_is_carryover_active boolean := false;
    v_effective_from date := null;
    r record;
    v_status jsonb;
    v_met boolean;
    v_mode text;
    v_required int;
    v_effective int;
    v_completed int;
    v_logged_in boolean;
    v_exempt boolean;
    v_prev_streak int;
    v_new_streak int;
    v_pct numeric;
    v_ratio numeric;
    v_deduction int;
    v_new_balance int;
    v_rows int;
    v_note text;
    v_base_req int;
    v_carry_in int;
    v_carry_out int;
    v_source_date date;
begin
    if p_eval is null then
        return;
    end if;

    -- Chống chạy song song giữa nhiều phiên / cron retry
    perform pg_advisory_xact_lock(hashtextextended('daily_inactivity_penalty', 0));

    v_policy := public.get_daily_task_policy(p_eval);
    v_carry_policy := public.get_daily_task_carryover_policy(p_eval);
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_eval >= v_carry_policy.effective_from);
    v_effective_from := case when v_is_carryover_active then v_carry_policy.effective_from else null end;

    -- Khóa tuần tự từng profile
    for r in
        select p.id, p.credits, p.daily_missed_streak, lower(p.role) as role, p.created_at
        from public.profiles p
        where p.created_at < ((p_eval + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
        for update of p
    loop
        -- 1. Tài khoản đăng ký trong chính ngày p_eval: miễn cho ngày đăng ký, tạo checkpoint
        if r.created_at >= (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh') then
            insert into public.user_daily_tasks as t (
                user_id, task_date, mode, active_app_count, required_tests,
                effective_required_tests, available_apps, completed_tests,
                logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
                policy_id, tests_per_active_app,
                carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
                carry_source_date, carryover_effective_from
            ) values (
                r.id, p_eval, 'member',
                0, 0, 0, 0, 0,
                true, true, true, 0, 0, now(),
                v_policy.id, v_policy.tests_per_active_app,
                case when v_is_carryover_active then 1 else null end,
                0, 0, 0, null, v_effective_from
            )
            on conflict (user_id, task_date) do update
            set finalized_at = now()
            where t.finalized_at is null;

            continue;
        end if;

        -- 2. Đã finalized trước đó -> bỏ qua (idempotent, không trừ Credit lần hai)
        if exists (
            select 1
            from public.user_daily_tasks t
            where t.user_id = r.id
              and t.task_date = p_eval
              and t.finalized_at is not null
        ) then
            continue;
        end if;

        v_status := public.get_daily_task_status(r.id, p_eval);
        if v_status is null then
            continue;
        end if;

        v_met := coalesce((v_status ->> 'met')::boolean, false);
        v_mode := coalesce(v_status ->> 'mode', 'member');
        v_required := coalesce((v_status ->> 'required_tests')::int, 0);
        v_effective := coalesce((v_status ->> 'effective_required_tests')::int, 0);
        v_completed := coalesce((v_status ->> 'completed_tests')::int, 0);
        v_logged_in := coalesce((v_status ->> 'logged_in_today')::boolean, false);
        v_exempt := coalesce((v_status ->> 'is_penalty_exempt')::boolean, false);
        v_base_req := coalesce((v_status ->> 'base_required_tests')::int, 0);
        v_carry_in := coalesce((v_status ->> 'carry_in_tests')::int, 0);
        v_carry_out := coalesce((v_status ->> 'carryover_remaining_tests')::int, 0);
        v_source_date := case when v_is_carryover_active and p_eval > v_effective_from then (p_eval - 1) else null end;

        -- Lấy missed_streak từ row finalized gần nhất
        select coalesce(
            (select t.missed_streak
             from public.user_daily_tasks t
             where t.user_id = r.id
               and t.task_date < p_eval
               and t.finalized_at is not null
             order by t.task_date desc
             limit 1),
            r.daily_missed_streak,
            0
        ) into v_prev_streak;

        -- Xác định streak mới và phạt
        if v_exempt then
            v_new_streak := 0;
            v_deduction := 0;
        elsif v_met then
            v_new_streak := 0;
            v_deduction := 0;
        else
            -- Khi owner có effective_required_tests = 0 (do nguồn app = 0): không phạt và không tăng chuỗi
            if v_mode = 'owner' and v_effective <= 0 then
                v_new_streak := v_prev_streak;
                v_deduction := 0;
            else
                v_new_streak := v_prev_streak + 1;
                v_pct := case
                    when v_new_streak = 1 then 0.01
                    when v_new_streak = 2 then 0.02
                    when v_new_streak = 3 then 0.05
                    else 0.10
                end;

                if v_mode = 'owner' and v_effective > 0 then
                    v_ratio := greatest(
                        0,
                        least(
                            1,
                            (v_effective - v_completed)::numeric / v_effective::numeric
                        )
                    );
                elsif v_mode = 'member' then
                    v_ratio := case when v_logged_in then 0 else 1 end;
                else
                    v_ratio := 0;
                end if;

                if v_ratio <= 0 then
                    v_deduction := 0;
                else
                    v_deduction := least(
                        r.credits,
                        greatest(1, ceil(r.credits * v_pct * v_ratio)::int)
                    );
                end if;
            end if;
        end if;

        v_new_balance := r.credits - v_deduction;

        -- Ghi bản ghi vào user_daily_tasks
        insert into public.user_daily_tasks as t (
            user_id, task_date, mode, active_app_count, required_tests,
            effective_required_tests, available_apps, completed_tests,
            logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
            policy_id, tests_per_active_app,
            carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
            carry_source_date, carryover_effective_from
        ) values (
            r.id, p_eval, v_mode,
            coalesce((v_status ->> 'active_app_count')::int, 0),
            v_required,
            v_effective,
            coalesce((v_status ->> 'available_apps')::int, 0),
            v_completed,
            v_logged_in,
            v_exempt,
            v_met,
            v_new_streak,
            v_deduction,
            now(),
            v_policy.id,
            v_policy.tests_per_active_app,
            case when v_is_carryover_active then 1 else null end,
            v_base_req,
            v_carry_in,
            v_carry_out,
            v_source_date,
            v_effective_from
        )
        on conflict (user_id, task_date) do update
        set mode = excluded.mode,
            active_app_count = excluded.active_app_count,
            required_tests = excluded.required_tests,
            effective_required_tests = excluded.effective_required_tests,
            available_apps = excluded.available_apps,
            completed_tests = excluded.completed_tests,
            logged_in = excluded.logged_in,
            is_exempt = excluded.is_exempt,
            met = excluded.met,
            missed_streak = excluded.missed_streak,
            penalty_credits = excluded.penalty_credits,
            finalized_at = now(),
            policy_id = excluded.policy_id,
            tests_per_active_app = excluded.tests_per_active_app,
            carryover_version = excluded.carryover_version,
            base_required_tests = excluded.base_required_tests,
            carry_in_tests = excluded.carry_in_tests,
            carry_out_tests = excluded.carry_out_tests,
            carry_source_date = excluded.carry_source_date,
            carryover_effective_from = excluded.carryover_effective_from
        where t.finalized_at is null;

        get diagnostics v_rows = row_count;
        if v_rows = 0 then
            continue;
        end if;

        if not v_exempt then
            update public.profiles
            set credits = v_new_balance,
                daily_missed_streak = v_new_streak
            where id = r.id;

            if v_deduction > 0 then
                if v_mode = 'owner' then
                    v_note := format(
                        'Không đạt chỉ tiêu ngày %s: %s/%s app (thiếu %s). Trừ %s Credit.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_completed,
                        v_effective,
                        greatest(0, v_effective - v_completed),
                        v_deduction
                    );
                else
                    v_note := format(
                        'Không đăng nhập ngày %s. Trừ %s Credit.',
                        to_char(p_eval, 'DD/MM/YYYY'),
                        v_deduction
                    );
                end if;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (r.id, -v_deduction, 'inactivity_penalty', v_note, v_new_balance);
            end if;

            if not v_met then
                insert into public.notifications (user_id, title, message, type, data)
                values (
                    r.id,
                    'Không đạt nhiệm vụ ngày',
                    case
                        when v_deduction = 0 and v_mode = 'owner' then format(
                            'Ngày %s bạn mới test %s/%s app. Số dư hiện tại là 0 nên không trừ Credit, nhưng chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                            to_char(p_eval, 'DD/MM/YYYY'),
                            v_completed,
                            v_effective,
                            v_new_streak
                        )
                        when v_deduction = 0 then format(
                            'Ngày %s bạn không mở app. Số dư hiện tại là 0 nên không trừ Credit, nhưng chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                            to_char(p_eval, 'DD/MM/YYYY'),
                            v_new_streak
                        )
                        when v_mode = 'owner' then format(
                            'Ngày %s bạn mới test %s/%s app. Bạn bị trừ %s Credit và chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                            to_char(p_eval, 'DD/MM/YYYY'),
                            v_completed,
                            v_effective,
                            v_deduction,
                            v_new_streak
                        )
                        else format(
                            'Ngày %s bạn không mở app. Bạn bị trừ %s Credit và chuỗi thiếu nhiệm vụ tăng lên %s ngày.',
                            to_char(p_eval, 'DD/MM/YYYY'),
                            v_deduction,
                            v_new_streak
                        )
                    end,
                    'credit',
                    jsonb_build_object(
                        'task_date', to_char(p_eval, 'YYYY-MM-DD'),
                        'penalty_credits', v_deduction
                    )
                );
            end if;

            -- Nếu hôm nay (v_today) đã hoàn thành, reset streak ngay
            perform public.refresh_daily_completion(r.id, v_today);
        end if;
    end loop;
end;
$$;

revoke execute on function public._apply_daily_inactivity_penalty(date) from public, anon, authenticated;
grant execute on function public._apply_daily_inactivity_penalty(date) to service_role;

-- 7. Điều phối chốt nhiệm vụ ngày bù tự động (apply_daily_inactivity_penalty)
create or replace function public.apply_daily_inactivity_penalty()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_yesterday date := ((now() at time zone 'Asia/Ho_Chi_Minh')::date) - 1;
    v_carry_policy public.daily_task_carryover_policies;
    v_earliest_unfinalized date;
    v_cur date;
begin
    v_carry_policy := public.get_daily_task_carryover_policy(v_yesterday);

    if v_carry_policy is not null and v_carry_policy.is_enabled then
        -- Tìm ngày chưa chốt sớm nhất (tối đa lùi 30 ngày)
        select min(d.dt::date) into v_earliest_unfinalized
        from generate_series(
            greatest(v_carry_policy.effective_from, v_yesterday - 30),
            v_yesterday,
            interval '1 day'
        ) d(dt)
        where exists (
            select 1 from public.profiles p
            where p.created_at < (d.dt::timestamp at time zone 'Asia/Ho_Chi_Minh')
              and not exists (
                  select 1 from public.user_daily_tasks t
                  where t.user_id = p.id
                    and t.task_date = d.dt::date
                    and t.finalized_at is not null
              )
        );

        if v_earliest_unfinalized is not null and v_earliest_unfinalized <= v_yesterday then
            v_cur := v_earliest_unfinalized;
            while v_cur <= v_yesterday loop
                perform public._apply_daily_inactivity_penalty(v_cur);
                v_cur := v_cur + 1;
            end loop;
            return;
        end if;
    end if;

    -- Mặc định chốt hôm qua
    perform public._apply_daily_inactivity_penalty(v_yesterday);
end;
$$;

revoke execute on function public.apply_daily_inactivity_penalty() from public, anon, authenticated;
grant execute on function public.apply_daily_inactivity_penalty() to service_role;

-- 8. Wrapper hỗ trợ chốt ngày chỉ định (dùng cho service_role / kiểm thử)
create or replace function public.finalize_daily_tasks_for_date(p_target_date date)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if p_target_date is null then
        raise exception 'TARGET_DATE_REQUIRED';
    end if;
    perform public._apply_daily_inactivity_penalty(p_target_date);
end;
$$;

revoke execute on function public.finalize_daily_tasks_for_date(date) from public, anon, authenticated;
grant execute on function public.finalize_daily_tasks_for_date(date) to service_role;

-- 9. Đồng bộ thứ tự khóa trong resume_campaign (campaigns -> profiles)
-- Giữ nguyên hành vi hiện hành và tương thích với 052_campaign_push_priority
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
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Khóa campaign trước, nhất quán với RPC push_campaign
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

    -- App bị Admin tạm dừng: chủ app không được tự mở lại
    if v_camp.paused_reason = 'admin_manual' then
        raise exception 'PAUSED_BY_ADMIN';
    end if;

    -- Quá cửa sổ 30 ngày -> hoàn thành
    if v_camp.created_at <= now() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'completed'
        );
    end if;

    -- Slot > 1 phải còn hạn
    if v_camp.slot_number > 1 then
        if not exists (
            select 1 from public.user_slots s
            where s.user_id = v_camp.owner_id
              and s.slot_number = v_camp.slot_number
              and s.expires_at > now()
        ) then
            raise exception 'SLOT_EXPIRED';
        end if;
    end if;

    -- Nếu hôm nay đã đạt chỉ tiêu (kể cả có cộng dồn), refresh sẽ reset streak về 0
    perform public.refresh_daily_completion(v_uid, v_today);

    select daily_missed_streak into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 7 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null,
        admin_note = null,
        admin_resumed = false
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$$;

revoke execute on function public.resume_campaign(uuid) from public;
grant execute on function public.resume_campaign(uuid) to authenticated, service_role;

notify pgrst, 'reload schema';
