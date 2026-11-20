-- ==============================================================================
-- 12 Tester - Supabase migration 054: Push & Carryover Hardening (Sửa lỗi R01-R10)
-- ==============================================================================
-- Kế hoạch: RECHECK_VA_KE_HOACH_SUA_CONG_DON_VA_DAY_APP_2026-10-29.md
--
-- Các lỗi xử lý trong migration này:
-- 1. R07: Sửa FK campaign_push_receipts.user_id on delete set null để không chặn xóa tài khoản auth.users/profiles.
-- 2. R08: Lưu identity campaign gốc bất biến (target_campaign_id) trong campaign_push_receipts để replay được sau khi campaign bị xóa.
-- 3. R09: Trigger bảo vệ metadata đẩy app chặn triệt để client/authenticated role kể cả khi tự đặt custom GUC setting.
-- 4. R04: Khóa chính sách quá khứ chống sửa đổi hồi tố (CANNOT_MODIFY_HISTORICAL_POLICY), thêm audit log và bảo lưu phần thiếu cũ khi tắt/bật lại chính sách.
-- 5. R10: Checkpoint ngày đăng ký cập nhật đầy đủ is_exempt=true, met=true, carryover_version đúng khi đã có row login trước đó.
-- 6. R01: Finalizer từ chối/hoãn chốt ngày khi carryover_ready = false; không phạt và không ghi carry_out = 0 khi thiếu nguồn.
-- 7. R02: Tái dựng chính xác K/A trong quá khứ khi campaign được hoàn tất ở hiện tại; không biến owner thành member để miễn phạt sai.
-- 8. R03: Pending không kích hoạt auto-pause (pause_inactive_owner_campaigns) và chặn resume_campaign khi pending.
-- ==============================================================================

-- 1. R07 & R08: Nâng cấp bảng campaign_push_receipts
alter table public.campaign_push_receipts
    drop constraint if exists campaign_push_receipts_user_id_fkey;

alter table public.campaign_push_receipts
    alter column user_id drop not null;

alter table public.campaign_push_receipts
    add constraint campaign_push_receipts_user_id_fkey
    foreign key (user_id) references public.profiles(id) on delete set null;

alter table public.campaign_push_receipts
    add column if not exists target_campaign_id uuid;

update public.campaign_push_receipts
set target_campaign_id = campaign_id
where target_campaign_id is null and campaign_id is not null;

update public.campaign_push_receipts
set target_campaign_id = '00000000-0000-0000-0000-000000000000'::uuid
where target_campaign_id is null;

alter table public.campaign_push_receipts
    alter column target_campaign_id set not null;

create index if not exists idx_campaign_push_receipts_target_campaign
    on public.campaign_push_receipts(target_campaign_id);

-- 2. R09: Trigger bảo vệ metadata đẩy app khỏi client / role authenticated
create or replace function public.trg_fn_protect_campaign_push_metadata()
returns trigger
language plpgsql
set search_path = public
as $$
declare
    v_active_role text := coalesce(nullif(current_setting('role', true), ''), current_user);
begin
    if (tg_op = 'INSERT' and (new.push_business_date is not null or new.push_expires_at is not null or new.push_order is not null))
       or (tg_op = 'UPDATE' and (new.push_business_date is distinct from old.push_business_date
            or new.push_expires_at is distinct from old.push_expires_at
            or new.push_order is distinct from old.push_order)) then
        -- Client trực tiếp mang role authenticated/anon/public tuyệt đối không được ghi đè metadata
        if v_active_role in ('authenticated', 'anon', 'public') or current_user in ('authenticated', 'anon', 'public') or session_user in ('anon') then
            raise exception 'PUSH_METADATA_READ_ONLY: Client khong duoc phep thay doi metadata day app truc tiep';
        end if;
        if current_setting('app.allow_push_metadata_write', true) is distinct from 'true' then
            raise exception 'PUSH_METADATA_READ_ONLY: Metadata day app chi duoc cap nhat qua RPC push_campaign';
        end if;
    end if;
    return new;
end;
$$;

-- 3. R08: Cập nhật RPC push_campaign dùng target_campaign_id bất biến
create or replace function public.push_campaign(
    p_campaign_id uuid,
    p_client_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid               uuid := auth.uid();
    v_existing_receipt  record;
    v_camp              public.campaigns%rowtype;
    v_profile           public.profiles%rowtype;
    v_now               timestamptz;
    v_business_date     date;
    v_expires_at        timestamptz;
    v_push_order        bigint;
    v_balance_after     int;
    v_credit_log_id     uuid;
    v_receipt_id        uuid;
    v_note              text;
begin
    -- 1. Yêu cầu phiên đăng nhập
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    if p_campaign_id is null then
        raise exception 'INVALID_CAMPAIGN_ID';
    end if;

    -- 2. Kiểm tra receipt trước khi khóa (so sánh target_campaign_id bất biến)
    select * into v_existing_receipt
    from public.campaign_push_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if coalesce(v_existing_receipt.target_campaign_id, v_existing_receipt.campaign_id) is distinct from p_campaign_id then
            raise exception 'REQUEST_ID_CONFLICT';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', p_campaign_id,
            'charged_credits', v_existing_receipt.cost_credits,
            'balance_after', v_existing_receipt.balance_after,
            'business_date', to_char(v_existing_receipt.business_date, 'YYYY-MM-DD'),
            'expires_at', to_char(v_existing_receipt.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'push_order', v_existing_receipt.push_order,
            'replayed', true
        );
    end if;

    -- 3. Khóa campaign và profile theo thứ tự nhất quán
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select * into v_profile
    from public.profiles
    where id = v_uid
    for update;

    if v_profile.id is null then
        raise exception 'PROFILE_NOT_FOUND';
    end if;

    -- Re-check receipt sau khi đã có khóa
    select * into v_existing_receipt
    from public.campaign_push_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if coalesce(v_existing_receipt.target_campaign_id, v_existing_receipt.campaign_id) is distinct from p_campaign_id then
            raise exception 'REQUEST_ID_CONFLICT';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', p_campaign_id,
            'charged_credits', v_existing_receipt.cost_credits,
            'balance_after', v_existing_receipt.balance_after,
            'business_date', to_char(v_existing_receipt.business_date, 'YYYY-MM-DD'),
            'expires_at', to_char(v_existing_receipt.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'push_order', v_existing_receipt.push_order,
            'replayed', true
        );
    end if;

    -- 4. Kiểm tra chủ thật và trạng thái tài khoản
    if v_camp.owner_id is distinct from v_uid then
        raise exception 'NOT_OWNER';
    end if;

    if coalesce(v_profile.is_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- 5. Kiểm tra số dư (dưới 100 từ chối hoàn toàn; đúng 100 cho phép)
    if coalesce(v_profile.credits, 0) < 100 then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- 6. Khóa transaction chung cho việc cấp thứ tự đẩy
    perform pg_advisory_xact_lock(hashtext('campaign_push_order_lock'));

    v_now := clock_timestamp();
    v_business_date := (v_now at time zone 'Asia/Ho_Chi_Minh')::date;
    v_expires_at := (date_trunc('day', v_now at time zone 'Asia/Ho_Chi_Minh') + interval '1 day') at time zone 'Asia/Ho_Chi_Minh';

    -- Kiểm tra lại điều kiện thời gian sau khi có khóa và đồng hồ mới
    if v_camp.created_at <= v_now - interval '30 days' then
        raise exception 'CAMPAIGN_WINDOW_EXPIRED';
    end if;

    if v_camp.slot_number > 1 then
        if not exists (
            select 1 from public.user_slots
            where user_id = v_camp.owner_id
              and slot_number = v_camp.slot_number
              and expires_at > v_now
        ) then
            raise exception 'SLOT_EXPIRED';
        end if;
    end if;

    v_push_order := nextval('public.campaign_push_order_seq');

    -- 7. Trừ đúng 100 Credit, ghi log, cập nhật metadata campaign, ghi receipt
    update public.profiles
    set credits = credits - 100
    where id = v_uid
    returning credits into v_balance_after;

    v_note := format('Đẩy ứng dụng "%s" lên đầu danh sách (hạn đến hết %s)',
                     coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
                     to_char(v_business_date, 'DD/MM/YYYY'));

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (v_uid, -100, 'campaign_push', v_note, v_balance_after)
    returning id into v_credit_log_id;

    perform set_config('app.allow_push_metadata_write', 'true', true);

    update public.campaigns
    set push_business_date = v_business_date,
        push_expires_at = v_expires_at,
        push_order = v_push_order
    where id = p_campaign_id;

    perform set_config('app.allow_push_metadata_write', 'false', true);

    insert into public.campaign_push_receipts (
        user_id,
        campaign_id,
        target_campaign_id,
        client_request_id,
        cost_credits,
        balance_after,
        business_date,
        pushed_at,
        expires_at,
        push_order,
        credit_log_id
    )
    values (
        v_uid,
        p_campaign_id,
        p_campaign_id,
        p_client_request_id,
        100,
        v_balance_after,
        v_business_date,
        v_now,
        v_expires_at,
        v_push_order,
        v_credit_log_id
    )
    returning id into v_receipt_id;

    -- 8. Trả kết quả
    return jsonb_build_object(
        'success', true,
        'receipt_id', v_receipt_id,
        'client_request_id', p_client_request_id,
        'campaign_id', p_campaign_id,
        'charged_credits', 100,
        'balance_after', v_balance_after,
        'business_date', to_char(v_business_date, 'YYYY-MM-DD'),
        'expires_at', to_char(v_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_order', v_push_order,
        'replayed', false
    );
end;
$$;

revoke execute on function public.push_campaign(uuid, uuid) from public, anon;
grant execute on function public.push_campaign(uuid, uuid) to authenticated, service_role;

-- 4. R04: Bảng Audit chính sách cộng dồn và bảo vệ bất biến
create table if not exists public.daily_task_carryover_policy_audit (
    id bigint generated always as identity primary key,
    policy_id bigint,
    effective_from date not null,
    old_is_enabled boolean,
    new_is_enabled boolean not null,
    old_operator_note text,
    new_operator_note text not null,
    changed_by text not null default current_user,
    changed_at timestamptz not null default clock_timestamp()
);

alter table public.daily_task_carryover_policy_audit enable row level security;
revoke all on public.daily_task_carryover_policy_audit from public, anon, authenticated;
grant all on public.daily_task_carryover_policy_audit to service_role;

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
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_old public.daily_task_carryover_policies;
    v_row public.daily_task_carryover_policies;
    v_allow_retro boolean := (current_setting('test.allow_policy_retroactive_write', true) = 'true');
begin
    if p_effective_from is null then
        raise exception 'INVALID_EFFECTIVE_DATE';
    end if;

    select * into v_old
    from public.daily_task_carryover_policies
    where effective_from = p_effective_from;

    -- Chặn sửa hồi tố chính sách quá khứ đã áp dụng (trừ khi bật cờ test)
    if p_effective_from <= v_today and v_old.id is not null and not v_allow_retro then
        raise exception 'CANNOT_MODIFY_HISTORICAL_POLICY: Chinh sach da co hieu luc khong the sua doi hoi to';
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

    insert into public.daily_task_carryover_policy_audit (
        policy_id, effective_from, old_is_enabled, new_is_enabled,
        old_operator_note, new_operator_note, changed_by, changed_at
    ) values (
        v_row.id, p_effective_from, v_old.is_enabled, v_row.is_enabled,
        v_old.operator_note, v_row.operator_note, current_user, clock_timestamp()
    );

    return to_jsonb(v_row);
end;
$$;

revoke execute on function public.set_daily_task_carryover_policy(date, boolean, text) from public, anon, authenticated;
grant execute on function public.set_daily_task_carryover_policy(date, boolean, text) to service_role;

-- 5. R01, R02, R04: Cập nhật public.get_daily_task_status
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
    v_first_policy_effective date;
    v_has_prior_history boolean := false;
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

    -- R02: Tái dựng K chính xác: App được tính cho ngày p_date nếu được tạo trước v_day_start
    -- và trong khoảng [v_day_start, v_next_day_start] chưa bị completed/production.
    -- Nếu hiện tại completed nhưng completed_at >= v_next_day_start thì vào ngày p_date app vẫn active!
    select count(*) into v_k
    from public.campaigns c
    where c.owner_id = p_user_id
      and c.created_at < v_day_start
      and c.created_at > v_window_start
      and (
          c.status = 'open'
          or (c.status = 'paused' and c.paused_reason = 'owner_inactive')
          or (c.completed_at is not null and c.completed_at >= v_next_day_start)
      );

    -- R02: Tái dựng A chính xác: App open của người khác trong ngày p_date
    select count(*) into v_a
    from public.campaigns c
    where c.owner_id <> p_user_id
      and c.created_at < v_next_day_start
      and c.created_at > v_window_start
      and (
          c.status = 'open'
          or (c.completed_at is not null and c.completed_at >= v_next_day_start)
      );

    -- D: số app khác nhau user đã test trong ngày p_date
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

        -- Tìm ngày kích hoạt lần đầu của hệ thống
        select min(effective_from) into v_first_policy_effective
        from public.daily_task_carryover_policies
        where is_enabled = true;

        -- Tìm xem user đã từng có row carryover nào đã finalized trước đó hay chưa (kể cả trước khi policy bị tắt)
        select t.carry_out_tests into v_last_carry_out
        from public.user_daily_tasks t
        where t.user_id = p_user_id
          and t.task_date < p_date
          and t.finalized_at is not null
          and t.carryover_version is not null
        order by t.task_date desc
        limit 1;

        v_has_prior_history := (v_last_carry_out is not null);

        if p_date = v_carry_effective_from and not v_has_prior_history then
            -- Chỉ ngày kích hoạt đầu tiên của hệ thống mới bắt đầu C = 0
            v_carry_in := 0;
            v_carryover_ready := true;
        elsif p_date = v_carry_effective_from and v_has_prior_history then
            -- Bật lại chính sách sau một khoảng thời gian tắt: nối tiếp checkpoint nợ cũ đã bảo lưu
            v_carry_in := coalesce(v_last_carry_out, 0);
            v_carryover_ready := true;
        else
            -- p_date > effective_from: nguồn bắt buộc phải từ ngày liền trước (p_date - 1)
            select t.finalized_at, t.carry_out_tests
            into v_prev_finalized, v_prev_carry_out
            from public.user_daily_tasks t
            where t.user_id = p_user_id and t.task_date = (p_date - 1);

            if v_prev_finalized is not null then
                v_carry_in := coalesce(v_prev_carry_out, 0);
                v_carryover_ready := true;
            else
                -- Ngày hôm qua chưa finalized -> nguồn chưa sẵn sàng
                v_carryover_ready := false;
                v_carry_in := 0;
            end if;
        end if;
    else
        -- Chính sách cộng dồn chưa có hiệu lực hoặc đang bị tắt
        v_carry_in := 0;
        v_carryover_ready := true;
        v_carry_effective_from := null;
    end if;

    -- R01: Tách trạng thái member hoàn thành bằng đăng nhập khỏi điều kiện đủ nguồn chốt ledger
    if v_carryover_ready then
        if v_exempt then
            v_required := v_base_required + v_carry_in;
            v_effective := least(v_required, v_a);
            v_carry_remaining := greatest(0, v_carry_in - v_d);
            v_met := true;
        elsif v_mode = 'member' then
            v_required := 0;
            v_effective := 0;
            v_carry_remaining := greatest(0, v_carry_in - v_d);
            v_met := v_logged_in or v_d > 0;
        else
            -- mode = 'owner'
            v_required := v_base_required + v_carry_in;
            v_effective := least(v_required, v_a);
            v_q := greatest(v_carry_in, v_effective);
            v_carry_remaining := greatest(0, v_q - v_d);
            v_met := (v_d >= v_effective);
        end if;
    else
        -- Khi pending:
        if v_mode = 'member' then
            -- Member vẫn thấy mình hoàn thành nhiệm vụ đăng nhập trên UI
            v_required := 0;
            v_effective := 0;
            v_carry_remaining := 0;
            v_met := v_logged_in or v_d > 0;
        else
            -- Owner: nhiệm vụ chưa xác định, met = false
            v_required := v_base_required;
            v_effective := least(v_required, v_a);
            v_carry_remaining := 0;
            v_met := false;
        end if;
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

-- 6. R01, R10: Cập nhật public._apply_daily_inactivity_penalty
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
    v_ready boolean;
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

    -- Giới hạn không chốt ngày hôm nay hoặc tương lai (trừ cờ test)
    if p_eval >= v_today and current_setting('test.allow_future_finalize', true) is distinct from 'true' then
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
        -- 1. R10: Tài khoản đăng ký trong chính ngày p_eval: miễn cho ngày đăng ký, tạo checkpoint
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
            set mode = 'member',
                active_app_count = 0,
                required_tests = 0,
                effective_required_tests = 0,
                available_apps = 0,
                completed_tests = 0,
                logged_in = true,
                is_exempt = true,
                met = true,
                missed_streak = 0,
                penalty_credits = 0,
                finalized_at = now(),
                policy_id = excluded.policy_id,
                tests_per_active_app = excluded.tests_per_active_app,
                carryover_version = case when v_is_carryover_active then 1 else null end,
                base_required_tests = 0,
                carry_in_tests = 0,
                carry_out_tests = 0,
                carry_source_date = null,
                carryover_effective_from = v_effective_from
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

        v_ready := coalesce((v_status ->> 'carryover_ready')::boolean, true);

        -- R01: Finalizer từ chối/hoãn riêng user-day thiếu nguồn chuyển tiếp
        -- Không finalized, không phạt, không ghi carry_out = 0 khi carryover_ready = false
        if v_is_carryover_active and not v_ready then
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

-- 7. R03: Cập nhật pause_inactive_owner_campaigns chặn auto-pause khi pending
create or replace function public.pause_inactive_owner_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    v_count int := 0;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_status jsonb;
begin
    for r in
        select c.id, c.owner_id, c.app_name
        from public.campaigns c
        join public.profiles p on p.id = c.owner_id
        where c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
          and c.created_at <= now() - interval '24 hours'
          and lower(p.role) not in ('admin', 'moderator')
          and p.daily_missed_streak >= 7
        for update of c
    loop
        v_status := public.get_daily_task_status(r.owner_id, v_today);

        -- R03: Pending không được kích hoạt auto-pause vì nhiệm vụ chưa xác định
        if coalesce((v_status ->> 'carryover_ready')::boolean, true) = false then
            continue;
        end if;

        -- Người vừa hoạt động lại trong hôm nay thì không pause oan
        if coalesce((v_status ->> 'met')::boolean, false) = true then
            continue;
        end if;

        update public.campaigns
        set status = 'paused',
            paused_reason = 'owner_inactive'
        where id = r.id;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã bị tạm dừng ⏸',
            format(
                'Ứng dụng "%s" đã bị tạm dừng vì bạn không hoàn thành chỉ tiêu test trong 7 ngày liên tục. Hãy hoàn thành chỉ tiêu hôm nay, sau đó bấm "Tiếp tục nhận tester" trong màn hình quản lý app để hiển thị lại.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'owner_inactive')
        );

        v_count := v_count + 1;
    end loop;

    return v_count;
end;
$$;

revoke execute on function public.pause_inactive_owner_campaigns() from public, anon;
grant execute on function public.pause_inactive_owner_campaigns() to authenticated, service_role;

-- 8. R03: Cập nhật resume_campaign chặn resume khi pending
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
    v_status jsonb;
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

    if v_camp.paused_reason = 'admin_manual' then
        raise exception 'PAUSED_BY_ADMIN';
    end if;

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

    -- R03: Nếu bị pause vì owner_inactive, bắt buộc phải kiểm tra nhiệm vụ hôm nay
    if v_camp.paused_reason = 'owner_inactive' then
        v_status := public.get_daily_task_status(v_uid, v_today);
        if coalesce((v_status ->> 'carryover_ready')::boolean, true) = false then
            raise exception 'DAILY_TASK_PENDING';
        end if;
        if coalesce((v_status ->> 'met')::boolean, false) = false then
            raise exception 'OWNER_INACTIVE';
        end if;
    end if;

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

revoke execute on function public.resume_campaign(uuid) from public, anon;
grant execute on function public.resume_campaign(uuid) to authenticated, service_role;

notify pgrst, 'reload schema';
