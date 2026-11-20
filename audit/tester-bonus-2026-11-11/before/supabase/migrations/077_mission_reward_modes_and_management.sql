-- 077_mission_reward_modes_and_management.sql
-- Two Mission Reward Modes (once & daily), Escrow Budgeting, Mission Editing, and Admin Deletion.

-- ============================================================================
-- 1. SCHEMA EXTENSIONS
-- ============================================================================

-- A. Table: missions
ALTER TABLE public.missions
    ADD COLUMN IF NOT EXISTS reward_mode text NOT NULL DEFAULT 'legacy',
    ADD COLUMN IF NOT EXISTS escrow_total_credits integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS escrow_remaining_credits integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS escrow_refunded boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS max_fee_duration_days integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tester_announcement text NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS admin_closed_at timestamptz DEFAULT NULL;

DO $check$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.missions'::regclass
          AND conname = 'missions_reward_mode_check'
    ) THEN
        ALTER TABLE public.missions
            ADD CONSTRAINT missions_reward_mode_check
            CHECK (reward_mode IN ('legacy', 'once', 'daily'));
    END IF;
END;
$check$;

-- Set initial max_fee_duration_days for existing legacy records
UPDATE public.missions
SET max_fee_duration_days = duration_days
WHERE max_fee_duration_days = 0 AND duration_days > 0;

-- B. Table: tester_reports
ALTER TABLE public.tester_reports
    ADD COLUMN IF NOT EXISTS mission_reward_mode text DEFAULT NULL;

-- In once mode: strictly at most 1 mission report per participant (user_id, campaign_id, mission_id) across all days
CREATE UNIQUE INDEX IF NOT EXISTS idx_tester_reports_once_mission
    ON public.tester_reports (user_id, campaign_id, mission_id)
    WHERE submission_context = 'mission' AND mission_reward_mode = 'once';

-- C. Table: mission_settlement_jobs
ALTER TABLE public.mission_settlement_jobs
    ADD COLUMN IF NOT EXISTS report_date date DEFAULT NULL;

-- In daily mode: strictly at most 1 completed settlement job per (mission_id, participant_id, report_date)
CREATE UNIQUE INDEX IF NOT EXISTS idx_settlement_jobs_daily_date_completed
    ON public.mission_settlement_jobs (mission_id, participant_id, report_date)
    WHERE status = 'completed' AND report_date IS NOT NULL;

-- D. Guard: Cannot delete campaign while an unrefunded mission escrow is held
CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_mission_escrow_before_delete()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
begin
    if exists (
        select 1 from public.missions
        where campaign_id = old.id
          and escrow_remaining_credits > 0
          and not escrow_refunded
    ) then
        raise exception 'CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_MISSION_ESCROW: Ứng dụng còn nhiệm vụ đang giữ quỹ thưởng';
    end if;
    return old;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_mission_escrow_before_delete ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_mission_escrow_before_delete
BEFORE DELETE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_mission_escrow_before_delete();


-- ============================================================================
-- 2. CREATE MISSION (LEGACY COMPATIBILITY)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.create_mission(
    p_campaign_id uuid,
    p_title text,
    p_description text DEFAULT ''::text,
    p_max_participants integer DEFAULT 10,
    p_duration_days integer DEFAULT 3,
    p_reward_credits integer DEFAULT 10
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_camp record;
    v_balance int;
    v_fee int;
    v_ends_at timestamptz;
    v_mission_id uuid;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if nullif(trim(coalesce(p_title, '')), '') is null then
        raise exception 'MISSION_TITLE_REQUIRED';
    end if;

    if p_max_participants is null or p_max_participants < 1 or p_max_participants > 50 then
        raise exception 'INVALID_PARTICIPANTS';
    end if;

    if p_duration_days is null or p_duration_days < 1 or p_duration_days > 7 then
        raise exception 'INVALID_DURATION';
    end if;

    if p_reward_credits is null or p_reward_credits < 10 then
        raise exception 'INVALID_REWARD';
    end if;

    -- Khóa campaign row để serialize các request tạo mission đồng thời
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Cho phép app 'open' hoặc đã hoàn thành kiểm thử ('production', 'completed')
    if v_camp.status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- M06: Đồng bộ mission hết hạn của campaign này dưới khóa trước khi kiểm tra trùng
    update public.missions
    set status = 'expired'
    where campaign_id = p_campaign_id
      and status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    -- M02: open, full, paused_by_creator đều chiếm vị trí nhiệm vụ
    if exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and status in ('open', 'full', 'paused_by_creator')
    ) then
        raise exception 'MISSION_ALREADY_EXISTS';
    end if;

    v_fee := 50 * p_duration_days;

    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    if v_balance is null or v_balance < 300 then
        raise exception 'INSUFFICIENT_MIN_CREDITS';
    end if;

    if v_balance < v_fee then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    v_ends_at := now() + make_interval(days => p_duration_days);

    insert into public.missions (
        creator_id, campaign_id, title, description,
        max_participants, duration_days, reward_credits, fee_credits,
        status, ends_at, reward_mode, escrow_total_credits,
        escrow_remaining_credits, escrow_refunded, max_fee_duration_days, version
    ) values (
        v_user_id, p_campaign_id, trim(p_title), coalesce(p_description, ''),
        p_max_participants, p_duration_days, p_reward_credits, v_fee,
        'open', v_ends_at, 'legacy', 0,
        0, false, p_duration_days, 1
    )
    returning id into v_mission_id;

    update public.profiles
    set credits = credits - v_fee
    where id = v_user_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_user_id,
        -v_fee,
        'mission_fee',
        format('Mở nhiệm vụ "%s" (%s ngày × 50 Credit phí nền tảng)', trim(p_title), p_duration_days),
        v_balance
    );

    return jsonb_build_object(
        'success', true,
        'mission_id', v_mission_id,
        'fee', v_fee,
        'balance', v_balance,
        'ends_at', to_char(v_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'reward_mode', 'legacy'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.create_mission(uuid, text, text, integer, integer, integer) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.create_mission(uuid, text, text, integer, integer, integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.create_mission(uuid, text, text, integer, integer, integer) TO service_role;


-- ============================================================================
-- 3. CREATE MISSION V2 (WITH REWARD MODE & UPFRONT ESCROW)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.create_mission_v2(
    p_campaign_id uuid,
    p_title text,
    p_description text DEFAULT ''::text,
    p_max_participants integer DEFAULT 10,
    p_duration_days integer DEFAULT 3,
    p_reward_credits integer DEFAULT 10,
    p_reward_mode text DEFAULT 'once'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_camp record;
    v_balance int;
    v_fee int;
    v_escrow int;
    v_total_cost int;
    v_mode text;
    v_start_date date;
    v_ends_at timestamptz;
    v_mission_id uuid;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if nullif(trim(coalesce(p_title, '')), '') is null then
        raise exception 'MISSION_TITLE_REQUIRED';
    end if;

    if p_max_participants is null or p_max_participants < 1 or p_max_participants > 50 then
        raise exception 'INVALID_PARTICIPANTS';
    end if;

    if p_duration_days is null or p_duration_days < 1 or p_duration_days > 7 then
        raise exception 'INVALID_DURATION';
    end if;

    if p_reward_credits is null or p_reward_credits < 10 then
        raise exception 'INVALID_REWARD';
    end if;

    v_mode := lower(trim(coalesce(p_reward_mode, 'once')));
    if v_mode not in ('once', 'daily') then
        raise exception 'INVALID_REWARD_MODE: Chế độ thưởng phải là "once" hoặc "daily"';
    end if;

    -- Khóa campaign row để serialize các request tạo mission đồng thời
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Cho phép app 'open' hoặc đã hoàn thành kiểm thử ('production', 'completed')
    if v_camp.status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- Đồng bộ mission hết hạn của campaign này dưới khóa trước khi kiểm tra trùng
    update public.missions
    set status = 'expired'
    where campaign_id = p_campaign_id
      and status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    -- open, full, paused_by_creator đều chiếm vị trí nhiệm vụ
    if exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and status in ('open', 'full', 'paused_by_creator')
    ) then
        raise exception 'MISSION_ALREADY_EXISTS';
    end if;

    -- 1. Phí nền tảng 50 Credit × số ngày
    v_fee := 50 * p_duration_days;

    -- 2. Quỹ thưởng giữ trước:
    --    - once: số chỗ × Credit/lượt
    --    - daily: số chỗ × số ngày × Credit/ngày
    if v_mode = 'daily' then
        v_escrow := p_max_participants * p_duration_days * p_reward_credits;
        v_start_date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
        -- Chốt N ngày lịch liên tiếp theo Asia/Ho_Chi_Minh từ ngày mở, hết hạn ở 00:00 đầu ngày sau cửa sổ
        v_ends_at := ((v_start_date + p_duration_days)::text || ' 00:00:00+07')::timestamptz;
    else
        v_escrow := p_max_participants * p_reward_credits;
        v_ends_at := now() + make_interval(days => p_duration_days);
    end if;

    v_total_cost := v_fee + v_escrow;

    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    if v_balance is null or v_balance < 300 then
        raise exception 'INSUFFICIENT_MIN_CREDITS';
    end if;

    if v_balance < v_total_cost then
        raise exception 'INSUFFICIENT_CREDITS: Cần %s Credit (%s phí + %s quỹ thưởng), hiện có %s',
            v_total_cost, v_fee, v_escrow, v_balance;
    end if;

    insert into public.missions (
        creator_id, campaign_id, title, description,
        max_participants, duration_days, reward_credits, fee_credits,
        status, ends_at, reward_mode, escrow_total_credits,
        escrow_remaining_credits, escrow_refunded, max_fee_duration_days, version
    ) values (
        v_user_id, p_campaign_id, trim(p_title), coalesce(p_description, ''),
        p_max_participants, p_duration_days, p_reward_credits, v_fee,
        'open', v_ends_at, v_mode, v_escrow,
        v_escrow, false, p_duration_days, 1
    )
    returning id into v_mission_id;

    -- Trừ nguyên tử cả phí nền tảng lẫn quỹ thưởng
    update public.profiles
    set credits = credits - v_total_cost
    where id = v_user_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values
        (
            v_user_id,
            -v_fee,
            'mission_fee',
            format('Mở nhiệm vụ "%s" (%s ngày × 50 Credit phí nền tảng)', trim(p_title), p_duration_days),
            v_balance + v_escrow
        ),
        (
            v_user_id,
            -v_escrow,
            'mission_escrow',
            case when v_mode = 'daily'
                then format('Giữ quỹ thưởng nhiệm vụ "%s" (%s chỗ × %s ngày × %s Credit)', trim(p_title), p_max_participants, p_duration_days, p_reward_credits)
                else format('Giữ quỹ thưởng nhiệm vụ "%s" (%s chỗ × %s Credit)', trim(p_title), p_max_participants, p_reward_credits)
            end,
            v_balance
        );

    return jsonb_build_object(
        'success', true,
        'mission_id', v_mission_id,
        'fee', v_fee,
        'escrow', v_escrow,
        'total_cost', v_total_cost,
        'balance', v_balance,
        'ends_at', to_char(v_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'reward_mode', v_mode
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.create_mission_v2(uuid, text, text, integer, integer, integer, text) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.create_mission_v2(uuid, text, text, integer, integer, integer, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.create_mission_v2(uuid, text, text, integer, integer, integer, text) TO service_role;


-- ============================================================================
-- 4. ESCROW REFUND HELPER
-- ============================================================================

CREATE OR REPLACE FUNCTION public._check_and_refund_mission_escrow(p_mission_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_m record;
    v_pending_reports int;
    v_pending_jobs int;
    v_refund int;
    v_creator_balance int;
begin
    if p_mission_id is null then
        return jsonb_build_object('refunded', false, 'reason', 'NULL_MISSION_ID');
    end if;

    select * into v_m
    from public.missions
    where id = p_mission_id
    for update;

    if v_m.id is null then
        return jsonb_build_object('refunded', false, 'reason', 'MISSION_NOT_FOUND');
    end if;

    if v_m.reward_mode = 'legacy' or v_m.escrow_remaining_credits <= 0 or v_m.escrow_refunded then
        return jsonb_build_object('refunded', false, 'reason', 'NO_ESCROW_TO_REFUND');
    end if;

    -- Nhiệm vụ phải ở trạng thái đã đóng (deleted, expired hoặc ends_at <= now())
    if not (v_m.status in ('expired', 'deleted') or v_m.ends_at <= now()) then
        return jsonb_build_object('refunded', false, 'reason', 'MISSION_NOT_CLOSED');
    end if;

    -- Kiểm tra còn báo cáo đang chờ duyệt không
    select count(*) into v_pending_reports
    from public.tester_reports
    where mission_id = p_mission_id
      and status = 'pending';

    if v_pending_reports > 0 then
        return jsonb_build_object('refunded', false, 'reason', 'HAS_PENDING_REPORTS', 'pending_count', v_pending_reports);
    end if;

    -- Kiểm tra còn công việc chi trả đang chờ / lỗi không
    select count(*) into v_pending_jobs
    from public.mission_settlement_jobs
    where mission_id = p_mission_id
      and status in ('pending', 'failed');

    if v_pending_jobs > 0 then
        return jsonb_build_object('refunded', false, 'reason', 'HAS_PENDING_JOBS', 'pending_count', v_pending_jobs);
    end if;

    -- Mọi nghĩa vụ đã duyệt/từ chối và quyết toán xong -> hoàn toàn bộ quỹ dư một lần
    v_refund := v_m.escrow_remaining_credits;

    update public.profiles
    set credits = credits + v_refund
    where id = v_m.creator_id
    returning credits into v_creator_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_m.creator_id,
        v_refund,
        'mission_refund',
        format('Hoàn quỹ thưởng còn dư của nhiệm vụ "%s"', v_m.title),
        v_creator_balance
    );

    update public.missions
    set escrow_refunded = true,
        escrow_remaining_credits = 0
    where id = v_m.id;

    insert into public.mission_reconciliation_events (
        mission_id, campaign_id, creator_id, reconciled_action, reason, details
    ) values (
        v_m.id,
        v_m.campaign_id,
        v_m.creator_id,
        'escrow_refund',
        'MISSION_COMPLETED_OR_TERMINATED',
        jsonb_build_object('refunded_credits', v_refund, 'creator_balance', v_creator_balance)
    );

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_m.creator_id,
        'Hoàn quỹ nhiệm vụ 💰',
        format('Nhiệm vụ "%s" đã kết thúc. Bạn nhận lại %s Credit từ quỹ thưởng còn dư.', v_m.title, v_refund),
        'mission',
        jsonb_build_object('mission_id', v_m.id, 'refund_credits', v_refund)
    );

    return jsonb_build_object(
        'refunded', true,
        'mission_id', v_m.id,
        'refund_amount', v_refund,
        'creator_balance', v_creator_balance
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._check_and_refund_mission_escrow(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._check_and_refund_mission_escrow(uuid) TO service_role;


-- ============================================================================
-- 5. PREVIEW & EDIT MISSION (OWNER)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.preview_edit_mission(
    p_mission_id uuid,
    p_reward_mode text DEFAULT NULL::text,
    p_max_participants integer DEFAULT NULL::integer,
    p_duration_days integer DEFAULT NULL::integer,
    p_reward_credits integer DEFAULT NULL::integer
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_m record;
    v_can_edit_terms boolean;
    v_eff_mode text;
    v_eff_participants int;
    v_eff_duration int;
    v_eff_reward int;
    v_highest_fee_days int;
    v_fee_diff int := 0;
    v_new_escrow int := 0;
    v_escrow_diff int := 0;
    v_total_diff int := 0;
    v_start_date date;
    v_new_ends_at timestamptz;
    v_balance int;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_m
    from public.missions
    where id = p_mission_id;

    if v_m.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_m.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_m.status in ('deleted', 'expired') or v_m.ends_at <= now() then
        raise exception 'MISSION_CLOSED: Nhiệm vụ đã kết thúc hoặc đã bị xóa';
    end if;

    select credits into v_balance from public.profiles where id = v_user_id;

    v_can_edit_terms := (v_m.participant_count = 0 and v_m.reward_mode in ('once', 'daily'));

    if v_can_edit_terms then
        v_eff_mode := lower(trim(coalesce(p_reward_mode, v_m.reward_mode)));
        if v_eff_mode not in ('once', 'daily') then
            raise exception 'INVALID_REWARD_MODE';
        end if;

        v_eff_participants := coalesce(p_max_participants, v_m.max_participants);
        if v_eff_participants < 1 or v_eff_participants > 50 then
            raise exception 'INVALID_PARTICIPANTS';
        end if;

        v_eff_duration := coalesce(p_duration_days, v_m.duration_days);
        if v_eff_duration < 1 or v_eff_duration > 7 then
            raise exception 'INVALID_DURATION';
        end if;

        v_eff_reward := coalesce(p_reward_credits, v_m.reward_credits);
        if v_eff_reward < 10 then
            raise exception 'INVALID_REWARD';
        end if;

        -- Dùng thời điểm bắt đầu gốc khi tính lại thời hạn
        if v_eff_mode = 'daily' then
            v_start_date := (v_m.created_at at time zone 'Asia/Ho_Chi_Minh')::date;
            v_new_ends_at := ((v_start_date + v_eff_duration)::text || ' 00:00:00+07')::timestamptz;
        else
            v_new_ends_at := v_m.created_at + make_interval(days => v_eff_duration);
        end if;

        if v_new_ends_at <= now() then
            raise exception 'INVALID_DURATION: Thời hạn mới đã qua';
        end if;

        -- Phí theo số ngày đã thu là mốc cao nhất: tăng thu phần chênh, giảm không hoàn
        v_highest_fee_days := greatest(coalesce(v_m.max_fee_duration_days, v_m.duration_days), v_m.duration_days);
        if v_eff_duration > v_highest_fee_days then
            v_fee_diff := 50 * (v_eff_duration - v_highest_fee_days);
        else
            v_fee_diff := 0;
        end if;

        -- Quỹ thưởng mới
        if v_eff_mode = 'daily' then
            v_new_escrow := v_eff_participants * v_eff_duration * v_eff_reward;
        else
            v_new_escrow := v_eff_participants * v_eff_reward;
        end if;

        v_escrow_diff := v_new_escrow - v_m.escrow_remaining_credits;
        v_total_diff := v_fee_diff + v_escrow_diff;
    else
        v_fee_diff := 0;
        v_escrow_diff := 0;
        v_total_diff := 0;
        v_new_ends_at := v_m.ends_at;
    end if;

    return jsonb_build_object(
        'mission_id', v_m.id,
        'can_edit_terms', v_can_edit_terms,
        'fee_diff', v_fee_diff,
        'escrow_diff', v_escrow_diff,
        'total_diff', v_total_diff,
        'new_ends_at', to_char(v_new_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'current_balance', v_balance,
        'version', v_m.version
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.preview_edit_mission(uuid, text, integer, integer, integer) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.preview_edit_mission(uuid, text, integer, integer, integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.preview_edit_mission(uuid, text, integer, integer, integer) TO service_role;


CREATE OR REPLACE FUNCTION public.edit_mission(
    p_mission_id uuid,
    p_title text,
    p_description text DEFAULT ''::text,
    p_reward_mode text DEFAULT NULL::text,
    p_max_participants integer DEFAULT NULL::integer,
    p_duration_days integer DEFAULT NULL::integer,
    p_reward_credits integer DEFAULT NULL::integer,
    p_tester_announcement text DEFAULT NULL::text,
    p_expected_version integer DEFAULT NULL::integer,
    p_expected_cost_diff integer DEFAULT NULL::integer
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_m record;
    v_eff_mode text;
    v_eff_participants int;
    v_eff_duration int;
    v_eff_reward int;
    v_highest_fee_days int;
    v_fee_diff int := 0;
    v_new_escrow int := 0;
    v_escrow_diff int := 0;
    v_total_diff int := 0;
    v_start_date date;
    v_new_ends_at timestamptz;
    v_balance int;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if nullif(trim(coalesce(p_title, '')), '') is null then
        raise exception 'MISSION_TITLE_REQUIRED';
    end if;

    -- Khóa hàng nhiệm vụ để chống race condition khi nhận lượt hoặc sửa đồng thời
    select * into v_m
    from public.missions
    where id = p_mission_id
    for update;

    if v_m.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_m.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_m.status in ('deleted', 'expired') or v_m.ends_at <= now() then
        raise exception 'MISSION_CLOSED: Không thể sửa nhiệm vụ đã kết thúc hoặc đã xóa';
    end if;

    -- Chống ghi đè bản sửa mới
    if p_expected_version is not null and v_m.version <> p_expected_version then
        raise exception 'CONCURRENT_MODIFICATION: Nhiệm vụ đã được cập nhật bởi một phiên làm việc khác';
    end if;

    -- TRƯỜNG HỢP 1: ĐÃ CÓ NGƯỜI NHẬN HOẶC NHIỆM VỤ LEGACY
    -- Khóa chế độ, thưởng, chỗ và thời hạn. Chỉ cho sửa tiêu đề, mô tả và thông báo tester.
    if v_m.participant_count > 0 or v_m.reward_mode = 'legacy' then
        if (p_reward_mode is not null and lower(trim(p_reward_mode)) <> lower(v_m.reward_mode))
           or (p_max_participants is not null and p_max_participants <> v_m.max_participants)
           or (p_duration_days is not null and p_duration_days <> v_m.duration_days)
           or (p_reward_credits is not null and p_reward_credits <> v_m.reward_credits) then
            raise exception 'CANNOT_MODIFY_TERMS_AFTER_JOIN: Nhiệm vụ đã có người nhận hoặc là nhiệm vụ cũ, không thể đổi điều khoản thưởng';
        end if;

        update public.missions
        set title = trim(p_title),
            description = coalesce(p_description, ''),
            tester_announcement = coalesce(p_tester_announcement, tester_announcement),
            version = version + 1
        where id = p_mission_id;

        -- Gửi thông báo đến người đã nhận khi tiêu đề, mô tả hoặc thông báo thay đổi
        if trim(p_title) <> v_m.title
           or coalesce(p_description, '') <> v_m.description
           or (nullif(trim(coalesce(p_tester_announcement, '')), '') is not null
               and trim(p_tester_announcement) <> coalesce(v_m.tester_announcement, '')) then
            insert into public.notifications (user_id, title, message, type, data)
            select
                user_id,
                'Cập nhật từ chủ nhiệm vụ 📢',
                case
                    when nullif(trim(coalesce(p_tester_announcement, '')), '') is not null then
                        format('Chủ nhiệm vụ "%s": %s', trim(p_title), trim(p_tester_announcement))
                    else
                        format('Nhiệm vụ "%s" đã được chủ nhiệm vụ cập nhật nội dung.', trim(p_title))
                end,
                'mission',
                jsonb_build_object('mission_id', p_mission_id, 'announcement', coalesce(trim(p_tester_announcement), ''))
            from public.mission_participants
            where mission_id = p_mission_id;
        end if;

        return jsonb_build_object(
            'success', true,
            'mission_id', p_mission_id,
            'terms_modified', false,
            'version', v_m.version + 1
        );
    end if;

    -- TRƯỜNG HỢP 2: CHƯA CÓ NGƯỜI NHẬN (participant_count = 0) VÀ CHẾ ĐỘ ONCE/DAILY
    -- Cho phép sửa điều khoản thưởng, thời hạn, số chỗ, chế độ
    v_eff_mode := lower(trim(coalesce(p_reward_mode, v_m.reward_mode)));
    if v_eff_mode not in ('once', 'daily') then
        raise exception 'INVALID_REWARD_MODE';
    end if;

    v_eff_participants := coalesce(p_max_participants, v_m.max_participants);
    if v_eff_participants < 1 or v_eff_participants > 50 then
        raise exception 'INVALID_PARTICIPANTS';
    end if;

    v_eff_duration := coalesce(p_duration_days, v_m.duration_days);
    if v_eff_duration < 1 or v_eff_duration > 7 then
        raise exception 'INVALID_DURATION';
    end if;

    v_eff_reward := coalesce(p_reward_credits, v_m.reward_credits);
    if v_eff_reward < 10 then
        raise exception 'INVALID_REWARD';
    end if;

    -- Dùng thời điểm bắt đầu gốc khi tính lại thời hạn
    if v_eff_mode = 'daily' then
        v_start_date := (v_m.created_at at time zone 'Asia/Ho_Chi_Minh')::date;
        v_new_ends_at := ((v_start_date + v_eff_duration)::text || ' 00:00:00+07')::timestamptz;
    else
        v_new_ends_at := v_m.created_at + make_interval(days => v_eff_duration);
    end if;

    if v_new_ends_at <= now() then
        raise exception 'INVALID_DURATION: Thời hạn mới đã qua';
    end if;

    -- Phí theo số ngày đã thu là mốc cao nhất
    v_highest_fee_days := greatest(coalesce(v_m.max_fee_duration_days, v_m.duration_days), v_m.duration_days);
    if v_eff_duration > v_highest_fee_days then
        v_fee_diff := 50 * (v_eff_duration - v_highest_fee_days);
    else
        v_fee_diff := 0;
    end if;

    -- Quỹ thưởng mới
    if v_eff_mode = 'daily' then
        v_new_escrow := v_eff_participants * v_eff_duration * v_eff_reward;
    else
        v_new_escrow := v_eff_participants * v_eff_reward;
    end if;

    v_escrow_diff := v_new_escrow - v_m.escrow_remaining_credits;
    v_total_diff := v_fee_diff + v_escrow_diff;

    -- Bắt buộc phiên bản và chênh lệch chi phí khi sửa điều khoản tài chính
    if p_expected_version is null or p_expected_cost_diff is null then
        raise exception 'EXPECTED_VERSION_AND_DELTA_REQUIRED: Vui lòng cung cấp expected_version và expected_cost_diff khi sửa điều khoản';
    end if;

    -- Từ chối giá trị xem trước lỗi thời
    if p_expected_cost_diff <> v_total_diff then
        raise exception 'STALE_PREVIEW_COST: Giá trị chênh lệch quỹ đã thay đổi, vui lòng xác nhận lại';
    end if;

    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    -- Kiểm tra số dư trước khi áp dụng
    if v_balance < 300 then
        raise exception 'INSUFFICIENT_MIN_CREDITS';
    end if;
    if v_balance < v_total_diff then
        raise exception 'INSUFFICIENT_CREDITS: Cần thêm %s Credit, hiện có %s', v_total_diff, v_balance;
    end if;

    -- Điều chỉnh số dư và ghi nhật ký từng chân (fee và escrow) để số dư nhật ký nối tiếp chính xác
    if v_fee_diff > 0 then
        v_balance := v_balance - v_fee_diff;
        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_user_id,
            -v_fee_diff,
            'mission_fee',
            format('Tăng thời hạn nhiệm vụ "%s" (+%s ngày × 50 Credit)', trim(p_title), v_eff_duration - v_highest_fee_days),
            v_balance
        );
    end if;

    if v_escrow_diff > 0 then
        v_balance := v_balance - v_escrow_diff;
        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_user_id,
            -v_escrow_diff,
            'mission_escrow',
            format('Giữ thêm quỹ thưởng nhiệm vụ "%s" (+%s Credit)', trim(p_title), v_escrow_diff),
            v_balance
        );
    elsif v_escrow_diff < 0 then
        v_balance := v_balance + (-v_escrow_diff);
        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_user_id,
            -v_escrow_diff,
            'mission_refund',
            format('Hoàn bớt quỹ thưởng nhiệm vụ "%s" do giảm điều khoản (%s Credit)', trim(p_title), -v_escrow_diff),
            v_balance
        );
    end if;

    update public.profiles
    set credits = v_balance
    where id = v_user_id;

    update public.missions
    set title = trim(p_title),
        description = coalesce(p_description, ''),
        reward_mode = v_eff_mode,
        max_participants = v_eff_participants,
        duration_days = v_eff_duration,
        reward_credits = v_eff_reward,
        ends_at = v_new_ends_at,
        escrow_total_credits = v_new_escrow,
        escrow_remaining_credits = v_new_escrow,
        fee_credits = fee_credits + v_fee_diff,
        max_fee_duration_days = greatest(v_highest_fee_days, v_eff_duration),
        tester_announcement = coalesce(p_tester_announcement, tester_announcement),
        version = version + 1
    where id = p_mission_id;

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'terms_modified', true,
        'fee_diff', v_fee_diff,
        'escrow_diff', v_escrow_diff,
        'total_diff', v_total_diff,
        'new_ends_at', to_char(v_new_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'balance', v_balance,
        'version', v_m.version + 1
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.edit_mission(uuid, text, text, text, integer, integer, integer, text, integer, integer) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.edit_mission(uuid, text, text, text, integer, integer, integer, text, integer, integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.edit_mission(uuid, text, text, text, integer, integer, integer, text, integer, integer) TO service_role;


-- ============================================================================
-- 6. ADMIN DELETE MISSION & OWNER CLOSE MISSION
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_delete_mission(
    p_mission_id uuid,
    p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid;
    v_m record;
    v_event_id uuid;
begin
    -- Chỉ Admin đủ quyền theo cơ chế kiểm tra hiện hành
    v_caller_id := public.assert_unlocked_admin();

    if nullif(trim(coalesce(p_reason, '')), '') is null then
        raise exception 'REASON_REQUIRED: Vui lòng nhập lý do xóa nhiệm vụ';
    end if;

    select * into v_m
    from public.missions
    where id = p_mission_id
    for update;

    if v_m.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    -- Idempotent: lặp lại yêu cầu không phát trùng audit/thông báo/hoàn quỹ
    if v_m.status = 'deleted' then
        return jsonb_build_object(
            'success', true,
            'mission_id', p_mission_id,
            'status', 'deleted',
            'is_replay', true,
            'reason', trim(p_reason)
        );
    end if;

    update public.missions
    set status = 'deleted',
        admin_closed_at = now()
    where id = p_mission_id;

    -- Ghi audit actor/mission/reason/time
    v_event_id := public.record_admin_action_event(
        v_caller_id,
        'admin_delete_mission',
        'mission',
        p_mission_id::text,
        trim(p_reason),
        jsonb_build_object(
            'status', v_m.status,
            'title', v_m.title,
            'creator_id', v_m.creator_id,
            'reward_mode', v_m.reward_mode,
            'escrow_remaining_credits', v_m.escrow_remaining_credits
        ),
        jsonb_build_object('status', 'deleted')
    );

    -- Thông báo chủ app
    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_m.creator_id,
        'Nhiệm vụ đã bị gỡ bỏ bởi Admin ⚠️',
        format('Nhiệm vụ "%s" đã bị Quản trị viên xóa. Lý do: %s', v_m.title, trim(p_reason)),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'deleted_by_admin', true, 'reason', trim(p_reason))
    );

    -- Thông báo tester đã nhận
    insert into public.notifications (user_id, title, message, type, data)
    select
        user_id,
        'Nhiệm vụ đã kết thúc ⚠️',
        format('Nhiệm vụ "%s" đã bị Quản trị viên xóa. Lý do: %s', v_m.title, trim(p_reason)),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'deleted_by_admin', true, 'reason', trim(p_reason))
    from public.mission_participants
    where mission_id = p_mission_id;

    -- Nếu không có báo cáo chờ nào thì hoàn quỹ dư ngay; nếu còn báo cáo chờ, quỹ sẽ hoàn sau khi quyết toán xong
    perform public._check_and_refund_mission_escrow(p_mission_id);

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', 'deleted',
        'reason', trim(p_reason),
        'audit_event_id', v_event_id
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_delete_mission(uuid, text) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.admin_delete_mission(uuid, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.admin_delete_mission(uuid, text) TO service_role;


-- Cập nhật delete_mission của chủ app cho nhiệm vụ mới dùng cùng cơ chế đóng/quỹ
CREATE OR REPLACE FUNCTION public.delete_mission(p_mission_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_m record;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_m
    from public.missions
    where id = p_mission_id
    for update;

    if v_m.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_m.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Idempotent nếu đã xóa
    if v_m.status = 'deleted' then
        return jsonb_build_object(
            'success', true,
            'mission_id', p_mission_id,
            'status', 'deleted',
            'is_replay', true
        );
    end if;

    update public.missions
    set status = 'deleted'
    where id = p_mission_id;

    -- Thông báo người tham gia
    insert into public.notifications (user_id, title, message, type, data)
    select
        mp.user_id,
        'Nhiệm vụ đã bị đóng 🗑️',
        format('Nhiệm vụ "%s" đã được chủ nhiệm vụ đóng. Báo cáo hợp lệ đã gửi vẫn được giữ quyền xét thưởng.', v_m.title),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'campaign_id', v_m.campaign_id, 'deleted', true)
    from public.mission_participants mp
    where mp.mission_id = p_mission_id;

    -- Hoàn quỹ nếu không còn báo cáo chờ
    perform public._check_and_refund_mission_escrow(p_mission_id);

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', 'deleted'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.delete_mission(uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.delete_mission(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.delete_mission(uuid) TO service_role;


-- ============================================================================
-- 7. SETTLE MISSION REWARD (ONCE, DAILY & LEGACY)
-- ============================================================================

CREATE OR REPLACE FUNCTION public._settle_mission_reward(p_report_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_report record;
    v_match record;
    v_job record;
    v_saved_m record;
    v_saved_mp record;
    v_creator_balance int;
    v_tester_balance int;
    v_submitted_at timestamptz;
    v_m_ends_at timestamptz;
    v_backoff interval;
begin
    if p_report_id is null then
        return jsonb_build_object('settled', false, 'reason', 'NULL_REPORT_ID');
    end if;

    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        return jsonb_build_object('settled', false, 'reason', 'REPORT_NOT_FOUND');
    end if;

    if v_report.status <> 'approved' then
        return jsonb_build_object('settled', false, 'reason', 'REPORT_NOT_APPROVED');
    end if;

    -- Báo cáo test thường không được xét thưởng nhiệm vụ
    if v_report.submission_context = 'app_test' then
        return jsonb_build_object('settled', false, 'reason', 'NOT_A_MISSION_REPORT');
    end if;

    select * into v_job
    from public.mission_settlement_jobs
    where report_id = p_report_id
    for update;

    if v_job.id is not null and v_job.status in ('completed', 'skipped') then
        return jsonb_build_object('settled', false, 'reason', 'ALREADY_SETTLED', 'job_status', v_job.status);
    end if;

    if v_job.id is not null then
        select * into v_saved_m from public.missions where id = v_job.mission_id for update;
        select * into v_saved_mp from public.mission_participants where id = v_job.participant_id for update;

        if v_saved_m.id is null or v_saved_mp.id is null then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'MISSION_OR_PARTICIPANT_NOT_FOUND',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'TERMINATED_NOT_FOUND');
        end if;

        -- Kiểm tra thời điểm nộp báo cáo:
        v_submitted_at := coalesce(v_report.mission_submitted_at, v_report.created_at);
        v_m_ends_at := coalesce(v_report.mission_ends_at, v_saved_m.ends_at);

        if v_saved_m.reward_mode = 'legacy' then
            -- Ngoại lệ duy nhất cho legacy: khi Admin đóng, cho quyết toán báo cáo đủ điều kiện đã nộp trước lúc đóng
            if v_saved_m.status = 'deleted' and v_saved_m.admin_closed_at is not null then
                if v_submitted_at > v_saved_m.admin_closed_at then
                    update public.mission_settlement_jobs
                    set status = 'skipped',
                        skip_reason = 'TERMINATED_MISSION_EXPIRED',
                        updated_at = now()
                    where id = v_job.id;
                    return jsonb_build_object('settled', false, 'reason', 'TERMINATED_MISSION_EXPIRED');
                end if;
            else
                -- Hết hạn tự nhiên hoặc chủ app đóng: giữ nguyên luật legacy cũ không trả sau hạn
                if v_saved_m.ends_at <= now() or v_saved_m.status in ('expired', 'cancelled', 'deleted', 'stopped_no_credits') then
                    update public.mission_settlement_jobs
                    set status = 'skipped',
                        skip_reason = 'TERMINATED_MISSION_EXPIRED',
                        updated_at = now()
                    where id = v_job.id;
                    return jsonb_build_object('settled', false, 'reason', 'TERMINATED_MISSION_EXPIRED');
                end if;
            end if;
        else
            -- Nhiệm vụ mới (once hoặc daily): Báo cáo nộp hợp lệ trước hạn giữ quyền được xét thưởng
            if v_submitted_at > v_m_ends_at then
                update public.mission_settlement_jobs
                set status = 'skipped',
                    skip_reason = 'SUBMITTED_AFTER_DEADLINE',
                    updated_at = now()
                where id = v_job.id;
                return jsonb_build_object('settled', false, 'reason', 'SUBMITTED_AFTER_DEADLINE');
            end if;
        end if;

        select
            v_saved_m.id as mission_id,
            v_saved_m.creator_id,
            v_saved_m.title as mission_title,
            v_job.reward_credits,
            v_job.campaign_id,
            v_saved_mp.id as participant_id,
            v_saved_mp.joined_at,
            v_saved_m.reward_mode,
            v_saved_m.escrow_remaining_credits,
            v_saved_m.status as mission_status,
            v_saved_m.ends_at as mission_ends_at,
            v_saved_m.admin_closed_at
        into v_match;
    else
        -- Lần đầu (chưa có job)
        if v_report.mission_id is not null then
            select
                m.id as mission_id,
                m.creator_id,
                m.title as mission_title,
                m.reward_credits,
                m.campaign_id,
                mp.id as participant_id,
                mp.joined_at,
                m.status as mission_status,
                m.ends_at as mission_ends_at,
                mp.status as participant_status,
                m.reward_mode,
                m.escrow_remaining_credits,
                m.admin_closed_at
            into v_match
            from public.missions m
            join public.mission_participants mp on mp.mission_id = m.id
            where m.id = v_report.mission_id
              and mp.id = v_report.mission_participant_id
              and mp.user_id = v_report.user_id
              and m.campaign_id = v_report.campaign_id
            for update of m;

            if v_match.mission_id is null then
                return jsonb_build_object('settled', false, 'reason', 'MISSION_OR_PARTICIPANT_NOT_FOUND');
            end if;

            v_submitted_at := coalesce(v_report.mission_submitted_at, v_report.created_at);
            v_m_ends_at := coalesce(v_report.mission_ends_at, v_match.mission_ends_at);

            if v_match.reward_mode = 'legacy' then
                if v_match.mission_status = 'deleted' and v_match.admin_closed_at is not null then
                    if v_submitted_at > v_match.admin_closed_at then
                        return jsonb_build_object('settled', false, 'reason', 'TERMINATED_MISSION_EXPIRED');
                    end if;
                else
                    if v_match.mission_ends_at <= now() or v_match.mission_status in ('expired', 'cancelled', 'deleted', 'stopped_no_credits') then
                        return jsonb_build_object('settled', false, 'reason', 'TERMINATED_MISSION_EXPIRED');
                    end if;
                end if;
            else
                if v_submitted_at > v_m_ends_at then
                    return jsonb_build_object('settled', false, 'reason', 'SUBMITTED_AFTER_DEADLINE');
                end if;
            end if;
        else
            -- Legacy reports chưa có mission_id: chỉ tìm mission legacy đang active còn hạn
            select
                m.id as mission_id,
                m.creator_id,
                m.title as mission_title,
                m.reward_credits,
                m.campaign_id,
                mp.id as participant_id,
                mp.joined_at,
                m.reward_mode,
                m.escrow_remaining_credits,
                m.status as mission_status,
                m.ends_at as mission_ends_at,
                m.admin_closed_at
            into v_match
            from public.missions m
            join public.mission_participants mp on mp.mission_id = m.id
            where m.campaign_id = v_report.campaign_id
              and mp.user_id = v_report.user_id
              and mp.status = 'joined'
              and v_report.created_at >= mp.joined_at
              and m.status in ('open', 'full')
              and m.ends_at > now()
              and m.reward_mode = 'legacy'
            order by m.created_at desc
            limit 1
            for update of m;

            if v_match.mission_id is null then
                return jsonb_build_object('settled', false, 'reason', 'NO_ELIGIBLE_MISSION');
            end if;
        end if;

        insert into public.mission_settlement_jobs (
            report_id, mission_id, participant_id, campaign_id,
            tester_id, creator_id, reward_credits, status,
            next_attempt_at, report_date
        ) values (
            v_report.id, v_match.mission_id, v_match.participant_id, v_match.campaign_id,
            v_report.user_id, v_match.creator_id, v_match.reward_credits, 'pending',
            now(), v_report.report_date
        )
        on conflict (report_id) do update
        set mission_id = excluded.mission_id,
            participant_id = excluded.participant_id,
            report_date = excluded.report_date,
            updated_at = now()
        returning * into v_job;
    end if;

    -- Ràng buộc chống trả trùng:
    -- 1. Với once: Mỗi người tham gia chỉ được trả thưởng 1 lần duy nhất cho cả nhiệm vụ
    if v_match.reward_mode = 'once' then
        if exists (
            select 1 from public.mission_settlement_jobs j
            where j.mission_id = v_match.mission_id
              and j.participant_id = v_match.participant_id
              and j.status = 'completed'
              and j.id <> coalesce(v_job.id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'PARTICIPANT_ALREADY_REWARDED',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'ALREADY_REWARDED');
        end if;
    end if;

    -- 2. Với daily: Mỗi người tham gia chỉ được trả thưởng 1 lần cho mỗi ngày báo cáo (report_date)
    if v_match.reward_mode = 'daily' then
        if exists (
            select 1 from public.mission_settlement_jobs j
            where j.mission_id = v_match.mission_id
              and j.participant_id = v_match.participant_id
              and j.report_date = v_report.report_date
              and j.status = 'completed'
              and j.id <> coalesce(v_job.id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'DATE_ALREADY_REWARDED',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'ALREADY_REWARDED');
        end if;
    end if;

    begin
        -- TRƯỜNG HỢP A: NHIỆM VỤ MỚI (once hoặc daily) -> CHI TRẢ TỪ QUỸ ĐÃ GIỮ TRƯỚC (ESCROW)
        if v_match.reward_mode in ('once', 'daily') then
            -- Khóa lại hàng mission để cập nhật số dư escrow
            select * into v_saved_m from public.missions where id = v_match.mission_id for update;

            if coalesce(v_saved_m.escrow_remaining_credits, 0) >= v_match.reward_credits then
                -- Trừ quỹ đã giữ của nhiệm vụ (TUYỆT ĐỐI không trừ lại tài khoản chủ app)
                update public.missions
                set escrow_remaining_credits = escrow_remaining_credits - v_match.reward_credits
                where id = v_match.mission_id;

                -- Cộng Credit cho tester
                update public.profiles
                set credits = credits + v_match.reward_credits
                where id = v_report.user_id
                returning credits into v_tester_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_report.user_id,
                    v_match.reward_credits,
                    'mission_reward',
                    case when v_match.reward_mode = 'daily'
                        then format('Nhận thưởng nhiệm vụ "%s" (ngày %s)', v_match.mission_title, to_char(v_report.report_date, 'YYYY-MM-DD'))
                        else format('Nhận thưởng nhiệm vụ "%s"', v_match.mission_title)
                    end,
                    v_tester_balance
                );

                -- Với once: đánh dấu participant completed sau khi trả thưởng đúng một lần
                -- Với daily: KHÔNG đánh dấu participant completed để người dùng tiếp tục làm các ngày sau
                if v_match.reward_mode = 'once' then
                    update public.mission_participants
                    set status = 'completed',
                        completed_at = now()
                    where id = v_match.participant_id;
                end if;

                update public.mission_settlement_jobs
                set status = 'completed',
                    report_date = v_report.report_date,
                    updated_at = now()
                where id = v_job.id;

                insert into public.notifications (user_id, title, message, type, data)
                values (
                    v_report.user_id,
                    'Nhận thưởng nhiệm vụ 🎉',
                    format('Báo cáo của bạn đã được duyệt. Bạn nhận được +%s Credit từ nhiệm vụ "%s"!', v_match.reward_credits, v_match.mission_title),
                    'mission',
                    jsonb_build_object(
                        'mission_id', v_match.mission_id,
                        'campaign_id', v_match.campaign_id,
                        'report_id', v_report.id,
                        'reward_credits', v_match.reward_credits,
                        'reward_mode', v_match.reward_mode,
                        'report_date', to_char(v_report.report_date, 'YYYY-MM-DD')
                    )
                );

                -- Kiểm tra hoàn quỹ nếu nhiệm vụ đã kết thúc và không còn nghĩa vụ
                perform public._check_and_refund_mission_escrow(v_match.mission_id);

                return jsonb_build_object(
                    'settled', true,
                    'paid', true,
                    'mission_id', v_match.mission_id,
                    'reward_credits', v_match.reward_credits,
                    'tester_balance', v_tester_balance
                );
            else
                update public.mission_settlement_jobs
                set status = 'failed',
                    attempt_count = attempt_count + 1,
                    last_error = 'INSUFFICIENT_ESCROW_CREDITS',
                    next_attempt_at = now() + interval '10 minutes',
                    updated_at = now()
                where id = v_job.id;

                return jsonb_build_object('settled', false, 'reason', 'INSUFFICIENT_ESCROW_CREDITS');
            end if;

        -- TRƯỜNG HỢP B: NHIỆM VỤ CŨ (legacy) KHÔNG CÓ QUỸ -> DÙNG CƠ CHẾ CHI TRẢ CŨ
        else
            select credits into v_creator_balance
            from public.profiles
            where id = v_match.creator_id
            for update;

            if coalesce(v_creator_balance, 0) >= v_match.reward_credits then
                update public.mission_participants
                set status = 'completed',
                    completed_at = now()
                where id = v_match.participant_id
                  and status = 'joined';

                if not found then
                    update public.mission_settlement_jobs
                    set status = 'skipped',
                        skip_reason = 'PARTICIPANT_ALREADY_COMPLETED',
                        updated_at = now()
                    where id = v_job.id;

                    return jsonb_build_object('settled', false, 'reason', 'ALREADY_COMPLETED');
                end if;

                update public.profiles
                set credits = credits - v_match.reward_credits
                where id = v_match.creator_id
                returning credits into v_creator_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_match.creator_id,
                    -v_match.reward_credits,
                    'mission_reward',
                    format('Trả thưởng nhiệm vụ "%s" cho người tham gia', v_match.mission_title),
                    v_creator_balance
                );

                update public.profiles
                set credits = credits + v_match.reward_credits
                where id = v_report.user_id
                returning credits into v_tester_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_report.user_id,
                    v_match.reward_credits,
                    'mission_reward',
                    format('Nhận thưởng nhiệm vụ "%s"', v_match.mission_title),
                    v_tester_balance
                );

                update public.mission_settlement_jobs
                set status = 'completed',
                    report_date = v_report.report_date,
                    updated_at = now()
                where id = v_job.id;

                insert into public.notifications (user_id, title, message, type, data)
                values (
                    v_report.user_id,
                    'Nhận thưởng nhiệm vụ 🎉',
                    format('Báo cáo của bạn đã được duyệt. Bạn nhận được +%s Credit từ nhiệm vụ "%s"!', v_match.reward_credits, v_match.mission_title),
                    'mission',
                    jsonb_build_object(
                        'mission_id', v_match.mission_id,
                        'campaign_id', v_match.campaign_id,
                        'report_id', v_report.id,
                        'reward_credits', v_match.reward_credits
                    )
                );

                return jsonb_build_object(
                    'settled', true,
                    'paid', true,
                    'mission_id', v_match.mission_id,
                    'reward_credits', v_match.reward_credits,
                    'tester_balance', v_tester_balance
                );
            else
                update public.missions
                set status = 'stopped_no_credits'
                where id = v_match.mission_id
                  and status in ('open', 'full', 'paused_by_creator');

                update public.mission_settlement_jobs
                set status = 'failed',
                    attempt_count = attempt_count + 1,
                    last_error = 'INSUFFICIENT_CREATOR_CREDITS',
                    next_attempt_at = now() + interval '10 minutes',
                    updated_at = now()
                where id = v_job.id;

                insert into public.notifications (user_id, title, message, type, data)
                values (
                    v_match.creator_id,
                    'Nhiệm vụ bị tạm dừng do hết Credit',
                    format('Nhiệm vụ "%s" không đủ Credit trả cho tester. Nhiệm vụ đã tạm dừng.', v_match.mission_title),
                    'mission',
                    jsonb_build_object(
                        'mission_id', v_match.mission_id,
                        'campaign_id', v_match.campaign_id,
                        'action', 'stopped_no_credits'
                    )
                );

                return jsonb_build_object('settled', false, 'reason', 'INSUFFICIENT_CREATOR_CREDITS');
            end if;
        end if;
    exception when others then
        v_backoff := case
            when coalesce(v_job.attempt_count, 0) < 3 then interval '1 minute'
            when coalesce(v_job.attempt_count, 0) < 6 then interval '5 minutes'
            else interval '30 minutes'
        end;

        update public.mission_settlement_jobs
        set status = 'failed',
            attempt_count = attempt_count + 1,
            last_error = sqlerrm,
            next_attempt_at = now() + v_backoff,
            updated_at = now()
        where id = v_job.id;

        return jsonb_build_object('settled', false, 'reason', 'ERROR', 'error', sqlerrm);
    end;
end;
$function$;

REVOKE ALL ON FUNCTION public._settle_mission_reward(uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public._settle_mission_reward(uuid) TO service_role;


-- ============================================================================
-- 8. SUBMIT TESTER REPORT CORE (REWARD MODES ADMISSION & IDENTITIES)
-- ============================================================================

CREATE OR REPLACE FUNCTION public._submit_tester_report_core(
    p_submission_context text,
    p_campaign_id uuid,
    p_content text,
    p_rating integer,
    p_client_submit_id uuid,
    p_screenshot_url text,
    p_image_hash text,
    p_thumbnail_url text,
    p_mission_id uuid,
    p_attachments jsonb DEFAULT NULL,
    p_expected_date date DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_report_id uuid;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp record;
    v_existing_report record;
    v_idempotent_report record;
    v_receipt record;
    v_fingerprint text;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_provisional_amount int := 0;
    v_provisional_reward int := 10;
    v_policy public.business_policies;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_effective_full text;
    v_final_url text;
    v_final_hash text;
    v_final_thumb text;

    -- Mission context variables
    v_mission record;
    v_mp record;
    v_submission_context text;
    v_target_mission_id uuid := null;
    v_target_mp_id uuid := null;
    v_target_title text := null;
    v_target_reward int := null;
    v_target_ends_at timestamptz := null;
    v_target_reward_mode text := null;
    v_res_context text;
    v_res_mission_id uuid;
    v_res_title text;
    v_res_reward int;
    v_res_ends_at timestamptz;
    v_res_reward_mode text;
    v_result jsonb;

    -- Attachments variables
    v_att_canonical text := '';
    v_att_count int := 0;
    v_att_item jsonb;
    v_att_elem record;
    v_attachments_json jsonb := '[]'::jsonb;
    v_pos int;
begin
    v_policy := public._get_effective_business_policy_row(v_today);
    v_provisional_reward := v_policy.report_provisional_reward;

    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Strict explicit submission context
    if p_submission_context is null or p_submission_context not in ('app_test', 'mission')
       or (p_submission_context = 'mission') <> (p_mission_id is not null) then
        raise exception 'INVALID_REPORT_CONTEXT';
    end if;
    v_submission_context := p_submission_context;

    -- Advisory lock for client submission idempotency
    if p_client_submit_id is not null then
        perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    end if;

    if p_client_submit_id is not null and exists(select 1 from public.report_cancelled_requests where user_id=v_user_id and client_submit_id=p_client_submit_id) then
        raise exception 'REPORT_REQUEST_REQUIRES_REVIEW';
    end if;

    -- Validate attachments array if provided
    if p_attachments is not null then
        if p_client_submit_id is null then
            raise exception 'CLIENT_SUBMIT_ID_REQUIRED';
        end if;
        if jsonb_typeof(p_attachments) <> 'array' then
            raise exception 'ATTACHMENTS_MUST_BE_ARRAY';
        end if;
        v_att_count := jsonb_array_length(p_attachments);
        if v_att_count > 3 then
            raise exception 'TOO_MANY_ATTACHMENTS';
        end if;
        if v_att_count < 1 then
            raise exception 'ATTACHMENTS_EMPTY: attachments array cannot be empty';
        end if;

        for v_pos in 0 .. (v_att_count - 1) loop
            v_att_item := p_attachments->v_pos;
            if coalesce((v_att_item->>'position')::int, -1) <> v_pos then
                raise exception 'ATTACHMENT_POSITION_INVALID';
            end if;
            if v_att_item->>'screenshot_url' is null or btrim(v_att_item->>'screenshot_url') = '' then
                raise exception 'SCREENSHOT_URL_REQUIRED';
            end if;
            if not public.is_valid_report_screenshot_url(v_att_item->>'screenshot_url', p_campaign_id, v_user_id) then
                raise exception 'INVALID_SCREENSHOT_URL';
            end if;
            if v_att_item->>'image_hash' is null or btrim(v_att_item->>'image_hash') = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            if not (lower(v_att_item->>'image_hash') ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
            if v_att_item->>'thumbnail_url' is not null and btrim(v_att_item->>'thumbnail_url') <> '' then
                if not public.is_valid_report_thumbnail_url(v_att_item->>'thumbnail_url', p_campaign_id, v_user_id, v_att_item->>'screenshot_url') then
                    raise exception 'INVALID_THUMBNAIL_URL';
                end if;
            end if;
        end loop;

        select jsonb_agg(jsonb_build_object(
            'id', att->>'id',
            'position', (att->>'position')::int,
            'screenshot_url', att->>'screenshot_url',
            'thumbnail_url', att->>'thumbnail_url',
            'image_hash', lower(att->>'image_hash'),
            'sha256', att->>'sha256'
        ) order by (att->>'position')::int)::text
        into v_att_canonical
        from jsonb_array_elements(p_attachments) as att;
    end if;

    -- Validate single screenshot if provided without attachments
    if p_attachments is null and p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
        if not public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) then
            raise exception 'INVALID_SCREENSHOT_URL';
        end if;
        if p_image_hash is not null and btrim(p_image_hash) <> '' then
            if not (lower(p_image_hash) ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
        end if;
        if p_thumbnail_url is not null and btrim(p_thumbnail_url) <> '' then
            if not public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, p_screenshot_url) then
                raise exception 'INVALID_THUMBNAIL_URL';
            end if;
        end if;
    end if;

    -- Compute payload fingerprint
    if p_attachments is not null then
        v_fingerprint := encode(digest(format('v3|%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            v_submission_context,
            coalesce(p_mission_id::text, ''),
            coalesce(p_content, ''),
            v_rating,
            coalesce(v_att_canonical, ''),
            coalesce(p_expected_date::text, '')
        ), 'sha256'), 'hex');
    else
        v_fingerprint := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            coalesce(p_content, ''),
            coalesce(p_screenshot_url, ''),
            coalesce(p_rating, 5),
            coalesce(lower(p_image_hash), ''),
            coalesce(p_thumbnail_url, ''),
            coalesce(p_mission_id::text, '')
        ), 'sha256'), 'hex');
    end if;

    -- Idempotency check via receipt ledger
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;

            if v_receipt.provenance = 'legacy_backfill' then
                declare
                    v_resp_mission text := coalesce(v_receipt.response_json ->> 'mission_id', '');
                    v_resp_url text := coalesce(v_receipt.response_json ->> 'screenshot_url', '');
                    v_resp_hash text := coalesce(lower(v_receipt.response_json ->> 'image_hash'), '');
                    v_resp_thumb text := coalesce(v_receipt.response_json ->> 'thumbnail_url', '');
                    v_resp_status text := coalesce(v_receipt.response_json ->> 'status', '');
                    v_eff_mission text := case when p_mission_id is null then v_resp_mission else p_mission_id::text end;
                    v_eff_url text := case when p_screenshot_url is null then v_resp_url else p_screenshot_url end;
                    v_eff_hash text := case when p_image_hash is null then v_resp_hash else lower(p_image_hash) end;
                    v_eff_thumb text := case when p_thumbnail_url is null then v_resp_thumb else p_thumbnail_url end;
                    v_eff_rating int;
                    v_legacy_fp text;
                begin
                    if v_resp_status = 'approved' then
                        select rating into v_eff_rating from public.tester_reports where id = v_receipt.report_id;
                        if v_eff_rating is null then
                            v_eff_rating := coalesce((v_receipt.response_json ->> 'rating')::int, 5);
                        end if;
                    else
                        v_eff_rating := coalesce(p_rating, 5);
                    end if;

                    if (p_screenshot_url is null or p_screenshot_url = v_resp_url)
                       and (p_image_hash is null or lower(p_image_hash) = v_resp_hash)
                       and (p_thumbnail_url is null or p_thumbnail_url = v_resp_thumb)
                       and (p_mission_id is null or p_mission_id::text = v_resp_mission)
                       and (v_resp_status = 'approved' or p_rating is null or p_rating = v_eff_rating)
                    then
                        v_legacy_fp := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                            p_campaign_id,
                            coalesce(p_content, ''),
                            v_eff_url,
                            v_eff_rating,
                            v_eff_hash,
                            v_eff_thumb,
                            v_eff_mission
                        ), 'sha256'), 'hex');

                        if v_receipt.payload_fingerprint = v_legacy_fp then
                            return v_receipt.response_json;
                        end if;
                    end if;
                end;
            end if;

            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;

        -- Fallback check tester_reports for legacy submissions
        select * into v_idempotent_report
        from public.tester_reports
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_idempotent_report.id is not null then
            if v_idempotent_report.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if p_mission_id is not null and v_idempotent_report.mission_id is distinct from p_mission_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if coalesce(v_idempotent_report.content, '') <> coalesce(p_content, '') then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_rating is not null and v_idempotent_report.rating is not null and v_idempotent_report.rating <> v_rating then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_image_hash is not null and v_idempotent_report.image_hash is not null and lower(v_idempotent_report.image_hash) <> lower(p_image_hash) then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_screenshot_url is not null and v_idempotent_report.screenshot_url is not null and v_idempotent_report.screenshot_url <> p_screenshot_url then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;

            select coalesce(jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256
            ) order by a.position), '[]'::jsonb) into v_attachments_json
            from public.tester_report_attachments a
            where a.report_id = v_idempotent_report.id;

            if jsonb_array_length(v_attachments_json) = 0 and v_idempotent_report.screenshot_url is not null then
                v_attachments_json := jsonb_build_array(jsonb_build_object(
                    'id', v_idempotent_report.id,
                    'position', 0,
                    'screenshot_url', v_idempotent_report.screenshot_url,
                    'thumbnail_url', v_idempotent_report.thumbnail_url,
                    'image_hash', v_idempotent_report.image_hash,
                    'sha256', null
                ));
            end if;

            v_result := jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_idempotent_report.screenshot_url,
                'image_hash', v_idempotent_report.image_hash,
                'thumbnail_url', v_idempotent_report.thumbnail_url,
                'submission_context', v_idempotent_report.submission_context,
                'mission_id', v_idempotent_report.mission_id,
                'mission_title', v_idempotent_report.mission_title,
                'mission_reward_credits', v_idempotent_report.mission_reward_credits,
                'mission_ends_at', to_char(v_idempotent_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_reward_mode', v_idempotent_report.mission_reward_mode,
                'rating', v_idempotent_report.rating,
                'report_date', to_char(v_idempotent_report.report_date, 'YYYY-MM-DD'),
                'attachments', v_attachments_json
            );

            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_idempotent_report.id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;

            return v_result;
        end if;
    end if;

    -- Date precondition check for uncommitted submissions
    if p_expected_date is not null and p_expected_date <> v_today then
        -- Cho phép nộp qua nửa đêm đối với nhiệm vụ once còn hiệu lực
        if v_submission_context = 'mission' and exists (
            select 1 from public.missions
            where id = p_mission_id and campaign_id = p_campaign_id and reward_mode = 'once' and status in ('open', 'full') and ends_at > now()
        ) then
            null;
        else
            raise exception 'REPORT_DATE_MISMATCH: Ngay bao cao du kien khong khop voi may chu';
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
        raise exception 'USER_LOCKED';
    end if;

    -- Mission context allows open and finalized campaigns ('production', 'completed').
    -- Normal app_test context strictly requires 'open'.
    if v_submission_context = 'mission' then
        if v_camp.status not in ('open', 'production', 'completed') then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    else
        if v_camp.status <> 'open' then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    end if;

    -- Lock contextually by (user, campaign, context, mission, date)
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s:%s:%s', v_user_id, p_campaign_id, v_submission_context, coalesce(p_mission_id::text, ''), v_today), 0)
    );

    -- Re-check receipt ledger under lock
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;
            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;
    end if;

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lookup existing report (khóa báo cáo trước nếu đã tồn tại để tương thích thứ tự khóa với review/reject):
    if v_submission_context = 'mission' then
        select reward_mode into v_target_reward_mode from public.missions where id = p_mission_id;
        if v_target_reward_mode = 'once' then
            select * into v_existing_report
            from public.tester_reports
            where campaign_id = p_campaign_id
              and user_id = v_user_id
              and submission_context = 'mission'
              and mission_id = p_mission_id
            order by created_at desc
            limit 1
            for update;
        else
            select * into v_existing_report
            from public.tester_reports
            where campaign_id = p_campaign_id
              and user_id = v_user_id
              and submission_context = 'mission'
              and mission_id = p_mission_id
              and report_date = v_today
            order by created_at desc
            limit 1
            for update;
        end if;

        -- Khóa hàng mission và participant dưới khóa để chống race condition với close/edit/delete/refund
        select * into v_mission from public.missions
        where id = p_mission_id and campaign_id = p_campaign_id
        for update;

        if v_mission.id is null then
            raise exception 'MISSION_NOT_FOUND';
        end if;

        if v_mission.status not in ('open', 'full') then
            raise exception '%', case v_mission.status
                when 'paused_by_creator' then 'MISSION_PAUSED'
                when 'stopped_no_credits' then 'MISSION_STOPPED_NO_CREDITS'
                when 'expired' then 'MISSION_EXPIRED'
                when 'deleted' then 'MISSION_DELETED'
                else 'MISSION_NOT_OPEN' end;
        end if;

        if v_mission.ends_at <= now() then
            raise exception 'MISSION_EXPIRED';
        end if;

        select * into v_mp from public.mission_participants
        where mission_id = v_mission.id and user_id = v_user_id
        for update;

        if v_mp.id is null or v_mp.joined_at > now() then
            raise exception 'MISSION_NOT_JOINED';
        end if;

        if v_mission.reward_mode = 'daily' then
            if v_today < (v_mp.joined_at at time zone 'Asia/Ho_Chi_Minh')::date then
                raise exception 'MISSION_NOT_JOINED';
            end if;
        end if;

        v_target_mission_id := v_mission.id;
        v_target_mp_id := v_mp.id;
        v_target_title := v_mission.title;
        v_target_reward := v_mission.reward_credits;
        v_target_ends_at := v_mission.ends_at;
        v_target_reward_mode := v_mission.reward_mode;
    else
        select * into v_existing_report
        from public.tester_reports
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and submission_context = 'app_test'
          and report_date = v_today
        order by created_at desc
        limit 1
        for update;
    end if;

    -- Backfill legacy receipt before client_submit_id gets overwritten
    if v_existing_report.id is not null
       and v_existing_report.client_submit_id is not null
       and v_existing_report.client_submit_id is distinct from p_client_submit_id then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id,
            v_existing_report.client_submit_id,
            p_campaign_id,
            v_existing_report.id,
            encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                p_campaign_id,
                coalesce(v_existing_report.content, ''),
                coalesce(v_existing_report.screenshot_url, ''),
                coalesce(v_existing_report.rating, 5),
                coalesce(lower(v_existing_report.image_hash), ''),
                coalesce(v_existing_report.thumbnail_url, ''),
                coalesce(v_existing_report.mission_id::text, '')
            ), 'sha256'), 'hex'),
            jsonb_build_object(
                'success', true,
                'report_id', v_existing_report.id,
                'status', v_existing_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_existing_report.screenshot_url,
                'image_hash', v_existing_report.image_hash,
                'thumbnail_url', v_existing_report.thumbnail_url,
                'submission_context', v_existing_report.submission_context,
                'mission_id', v_existing_report.mission_id,
                'mission_title', v_existing_report.mission_title,
                'mission_reward_credits', v_existing_report.mission_reward_credits,
                'mission_ends_at', to_char(v_existing_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_reward_mode', v_existing_report.mission_reward_mode,
                'rating', v_existing_report.rating,
                'report_date', to_char(v_existing_report.report_date, 'YYYY-MM-DD')
            ),
            v_existing_report.created_at,
            'legacy_backfill'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    -- Disallow mutating an existing report to another context or mission
    if v_existing_report.id is not null and (
        v_existing_report.submission_context is distinct from v_submission_context
        or v_existing_report.mission_id is distinct from p_mission_id
    ) then
        raise exception 'REPORT_CONTEXT_CONFLICT';
    end if;

    -- TH1: REPORT IS APPROVED -> comment update only (preserving original report_date and photos)
    if v_existing_report.id is not null and v_existing_report.status = 'approved' then
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;
        if p_screenshot_url is not null and p_screenshot_url <> v_existing_report.screenshot_url then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (
            p_campaign_id, v_user_id, coalesce(v_existing_report.rating, v_rating),
            coalesce(p_content, ''), now()
        )
        on conflict (campaign_id, user_id)
        do update set
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        update public.tester_reports
        set content = case when coalesce(p_content, '') <> '' then p_content else content end,
            client_submit_id = coalesce(p_client_submit_id, client_submit_id)
        where id = v_existing_report.id
          and status = 'approved'
        returning id into v_report_id;

        if v_report_id is null then
            raise exception 'REPORT_ALREADY_REVIEWED';
        end if;
        v_status := 'approved';

        update public.profiles
        set last_active_date = v_today, missed_streak = 0
        where id = v_user_id;

        select
            submission_context, mission_id, mission_title,
            mission_reward_credits, mission_ends_at, mission_reward_mode,
            screenshot_url, image_hash, thumbnail_url
        into
            v_res_context, v_res_mission_id, v_res_title,
            v_res_reward, v_res_ends_at, v_res_reward_mode,
            v_final_url, v_final_hash, v_final_thumb
        from public.tester_reports
        where id = v_report_id;

        select coalesce(jsonb_agg(jsonb_build_object(
            'id', a.id,
            'position', a.position,
            'screenshot_url', a.screenshot_url,
            'thumbnail_url', a.thumbnail_url,
            'image_hash', a.image_hash,
            'sha256', a.sha256
        ) order by a.position), '[]'::jsonb) into v_attachments_json
        from public.tester_report_attachments a
        where a.report_id = v_report_id;

        if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
            v_attachments_json := jsonb_build_array(jsonb_build_object(
                'id', v_report_id,
                'position', 0,
                'screenshot_url', v_final_url,
                'thumbnail_url', v_final_thumb,
                'image_hash', v_final_hash,
                'sha256', null
            ));
        end if;

        v_result := jsonb_build_object(
            'success', true,
            'report_id', v_report_id,
            'status', v_status,
            'provisional_granted', false,
            'provisional_amount', 0,
            'screenshot_url', v_final_url,
            'image_hash', v_final_hash,
            'thumbnail_url', v_final_thumb,
            'submission_context', v_res_context,
            'mission_id', v_res_mission_id,
            'mission_title', v_res_title,
            'mission_reward_credits', v_res_reward,
            'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'mission_reward_mode', v_res_reward_mode,
            'rating', coalesce(v_existing_report.rating, v_rating),
            'report_date', to_char(v_existing_report.report_date, 'YYYY-MM-DD'),
            'attachments', v_attachments_json
        );

        if p_client_submit_id is not null then
            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;
        end if;

        return v_result;
    end if;

    -- TH2: EXISTING REPORT IS PENDING OR REJECTED
    if v_existing_report.id is not null then
        if v_existing_report.submission_context = 'mission' then
            if p_mission_id is not null and v_existing_report.mission_id is distinct from p_mission_id then
                raise exception 'CANNOT_CHANGE_MISSION_IDENTITY';
            end if;
            v_submission_context := 'mission';
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
            v_target_reward_mode := coalesce(v_existing_report.mission_reward_mode, v_mission.reward_mode);

            if v_existing_report.status = 'rejected' then
                if v_target_ends_at is not null and v_target_ends_at < now() then
                    raise exception 'MISSION_EXPIRED';
                end if;
                -- Với bản đầu, sửa/nộp lại lượt hàng ngày trong chính ngày đó và trước hạn
                if v_target_reward_mode = 'daily' and v_existing_report.report_date <> v_today then
                    raise exception 'DAILY_REPORT_RESUBMIT_WINDOW_EXPIRED: Lượt hàng ngày chỉ được sửa hoặc nộp lại trong ngày báo cáo';
                end if;
            end if;
        else
            if p_mission_id is not null then
                raise exception 'CANNOT_CONVERT_EXISTING_PENDING_REPORT';
            end if;
            v_submission_context := 'app_test';
        end if;

        -- Image resolution for pending/rejected update
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_new_screenshot := true;
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
            v_final_thumb := p_attachments->0->>'thumbnail_url';

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = v_effective_full then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_new_screenshot := true;
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_hash := lower(p_image_hash);
            v_final_thumb := p_thumbnail_url;

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = p_screenshot_url then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        else
            v_effective_full := v_existing_report.screenshot_url;
            v_final_hash := v_existing_report.image_hash;
            v_final_thumb := v_existing_report.thumbnail_url;
            if v_existing_report.status = 'rejected' then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_existing_report.status = 'rejected' then
            v_regrant := coalesce(v_existing_report.provisional_granted_credits, v_provisional_reward);
            if v_existing_report.provisional_clawed > 0 then
                update public.profiles
                set credits = credits + v_regrant,
                    last_active_date = v_today,
                    missed_streak = 0
                where id = v_user_id
                returning credits into v_new_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_user_id, v_regrant, 'test_provisional',
                    format('Tạm cấp lại %s Credit sau khi nộp lại kết quả test', v_regrant),
                    v_new_balance
                );

                v_provisional_granted := true;
                v_provisional_amount := v_regrant;
            else
                v_provisional_granted := false;
                v_provisional_amount := 0;
            end if;

            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                status = 'pending',
                rejection_reason = null,
                reviewed_at = null,
                provisional_clawed = 0,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                created_at = now(),
                mission_submitted_at = case when v_submission_context = 'mission' then now() else mission_submitted_at end,
                mission_reward_mode = coalesce(mission_reward_mode, v_target_reward_mode)
            where id = v_existing_report.id
            returning id into v_report_id;
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                mission_reward_mode = coalesce(mission_reward_mode, v_target_reward_mode)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Synchronize attachments table
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            delete from public.tester_report_attachments where report_id = v_report_id;
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        elsif v_new_screenshot then
            if exists (select 1 from public.tester_report_attachments where report_id = v_report_id and position = 0) then
                update public.tester_report_attachments
                set screenshot_url = v_effective_full,
                    thumbnail_url = v_final_thumb,
                    image_hash = v_final_hash,
                    sha256 = null,
                    created_at = now()
                where report_id = v_report_id and position = 0;
            else
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    gen_random_uuid(),
                    v_report_id,
                    0,
                    v_effective_full,
                    v_final_thumb,
                    v_final_hash,
                    null,
                    now()
                );
            end if;
        end if;

        v_status := 'pending';
    else
        -- TH3: BRAND NEW REPORT
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_thumb := p_attachments->0->>'thumbnail_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_thumb := p_thumbnail_url;
            v_final_hash := lower(p_image_hash);
        else
            raise exception 'SCREENSHOT_URL_REQUIRED';
        end if;

        if v_submission_context = 'mission' then
            v_target_mission_id := v_mission.id;
            v_target_mp_id := v_mp.id;
            v_target_title := v_mission.title;
            v_target_reward := v_mission.reward_credits;
            v_target_ends_at := v_mission.ends_at;
            v_target_reward_mode := v_mission.reward_mode;
        else
            v_target_mission_id := null;
            v_target_mp_id := null;
            v_target_title := null;
            v_target_reward := null;
            v_target_ends_at := null;
            v_target_reward_mode := null;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_submission_context = 'app_test' then
            select exists (
                select 1 from public.checkins
                where campaign_id = p_campaign_id
                  and user_id = v_user_id
                  and checkin_date = v_today
            ) into v_already_checked_in;

            if v_already_checked_in then
                insert into public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    mission_reward_mode,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
                )
                returning id into v_report_id;

                v_status := 'pending';
                v_provisional_granted := false;
                v_provisional_amount := 0;
            else
                if v_provisional_reward > 0 then
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

                    v_provisional_granted := true;
                    v_provisional_amount := v_provisional_reward;
                else
                    v_provisional_granted := false;
                    v_provisional_amount := 0;
                end if;

                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

                insert into public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    mission_reward_mode,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', v_provisional_amount, 0, v_provisional_amount,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
                )
                returning id into v_report_id;

                v_status := 'pending';
            end if;
        else
            -- Mission report does not consume or conflict with ordinary app_test daily checkin
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                mission_reward_mode,
                review_tester_bonus, review_owner_bonus, business_policy_id, report_date
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                v_final_hash, p_client_submit_id, now(),
                'mission', v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at, now(),
                v_target_reward_mode,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Attachments
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        else
            insert into public.tester_report_attachments (
                id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
            ) values (
                gen_random_uuid(),
                v_report_id,
                0,
                v_effective_full,
                v_final_thumb,
                v_final_hash,
                null,
                now()
            );
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today, missed_streak = 0
    where id = v_user_id;

    select
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, mission_reward_mode,
        screenshot_url, image_hash, thumbnail_url, report_date, rating
    into
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_res_reward_mode,
        v_final_url, v_final_hash, v_final_thumb, v_today, v_rating
    from public.tester_reports
    where id = v_report_id;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', a.id,
        'position', a.position,
        'screenshot_url', a.screenshot_url,
        'thumbnail_url', a.thumbnail_url,
        'image_hash', a.image_hash,
        'sha256', a.sha256
    ) order by a.position), '[]'::jsonb) into v_attachments_json
    from public.tester_report_attachments a
    where a.report_id = v_report_id;

    if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
        v_attachments_json := jsonb_build_array(jsonb_build_object(
            'id', v_report_id,
            'position', 0,
            'screenshot_url', v_final_url,
            'thumbnail_url', v_final_thumb,
            'image_hash', v_final_hash,
            'sha256', null
        ));
    end if;

    v_result := jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount,
        'screenshot_url', v_final_url,
        'image_hash', v_final_hash,
        'thumbnail_url', v_final_thumb,
        'submission_context', v_res_context,
        'mission_id', v_res_mission_id,
        'mission_title', v_res_title,
        'mission_reward_credits', v_res_reward,
        'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_reward_mode', v_res_reward_mode,
        'rating', v_rating,
        'report_date', to_char(v_today, 'YYYY-MM-DD'),
        'attachments', v_attachments_json
    );

    if p_client_submit_id is not null then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
            v_fingerprint, v_result, now(), 'exact_v1'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    return v_result;
end;
$function$;

REVOKE ALL ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) TO authenticated;
GRANT EXECUTE ON FUNCTION public._submit_tester_report_core(text, uuid, text, integer, uuid, text, text, text, uuid, jsonb, date) TO service_role;


-- ============================================================================
-- 9. REJECT TESTER REPORT (CALL ESCROW REFUND IF MISSION CLOSED)
-- ============================================================================

CREATE OR REPLACE FUNCTION public._impl_reject_tester_report(
    p_report_id uuid,
    p_reason text DEFAULT NULL::text,
    p_reason_code text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_report record;
    v_camp record;
    v_tester_credits int;
    v_deduct int := 0;
    v_new_balance int;
    v_report_date date;
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;

    v_extracted_code text;
    v_final_reason_code text;

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
    perform set_config('app.reviewing_report', 'true', true);

    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
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

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    if p_reason is not null and p_reason like '[CODE:%]' then
        v_extracted_code := substring(p_reason from '\[CODE:([A-Za-z0-9_]+)\]');
    end if;
    v_final_reason_code := coalesce(p_reason_code, v_extracted_code);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'reject', p_reason
    );

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, coalesce(v_report.provisional_granted_credits, case when coalesce(v_report.submission_context, 'app_test') = 'app_test' then 2 else 0 end))
    );

    if v_deduct > 0 then
        update public.profiles
        set credits = credits - v_deduct
        where id = v_report.user_id
        returning credits into v_new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance, metadata)
        values (
            v_report.user_id,
            -v_deduct,
            'test_rejected_clawback',
            format('Thu hồi %s Credit tạm nhận do báo cáo bị từ chối: %s', v_deduct, coalesce(p_reason, 'Chưa đạt yêu cầu')),
            v_new_balance,
            jsonb_build_object('report_id', v_report.id, 'campaign_id', v_camp.id, 'reason_code', v_final_reason_code)
        );
    else
        v_new_balance := coalesce(v_tester_credits, 0);
    end if;

    update public.tester_reports
    set status = 'rejected',
        rejection_reason = p_reason,
        reason_code = v_final_reason_code,
        bonus_awarded = 0,
        provisional_clawed = v_deduct,
        reviewed_at = now()
    where id = p_report_id;

    -- Only ordinary app_test reports touch checkins table
    if coalesce(v_report.submission_context, 'app_test') = 'app_test' then
        delete from public.checkins
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = v_report_date
          and coalesce(note, '') like '%tạm tính%';
    end if;

    select count(*) into v_tester_today_rejections
    from public.report_review_events
    where tester_id = v_report.user_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings w
            where w.user_id = v_report.user_id
              and w.warning_type = 'tester_rejection_streak'
              and w.warning_date = v_vn_today
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              )
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date, metadata)
            values (
                v_report.user_id,
                'tester_rejection_streak',
                v_msg_tester,
                v_vn_today,
                jsonb_build_object('warning_type', 'tester_rejection_streak', 'streak_count', v_tester_today_rejections)
            );
            v_tester_warned := true;
            v_tester_warning_msg := v_msg_tester;

            select count(*) into v_tester_weekly_warnings
            from public.user_warnings w
            where w.user_id = v_report.user_id
              and w.warning_date >= (v_vn_today - 6)
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              );

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    select count(*) into v_owner_today_rejections
    from public.report_review_events
    where owner_id = v_camp.owner_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings w
            where w.user_id = v_camp.owner_id
              and w.warning_type = 'owner_rejection_streak'
              and w.warning_date = v_vn_today
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              )
        ) then
            insert into public.user_warnings (user_id, warning_type, message, warning_date, metadata)
            values (
                v_camp.owner_id,
                'owner_rejection_streak',
                v_msg_owner,
                v_vn_today,
                jsonb_build_object('warning_type', 'owner_rejection_streak', 'streak_count', v_owner_today_rejections)
            );
            v_owner_warned := true;
            v_owner_warning_msg := v_msg_owner;

            select count(*) into v_owner_weekly_warnings
            from public.user_warnings w
            where w.user_id = v_camp.owner_id
              and w.warning_date >= (v_vn_today - 6)
              and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              );

            if v_owner_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_camp.owner_id;
                v_is_owner_locked := true;
            end if;
        end if;
    end if;

    -- Kiểm tra hoàn quỹ nếu là báo cáo nhiệm vụ
    if v_report.mission_id is not null then
        perform public._check_and_refund_mission_escrow(v_report.mission_id);
    end if;

    perform set_config('app.reviewing_report', 'false', true);

    return jsonb_build_object(
        'success', true,
        'report_id', v_report.id,
        'campaign_id', v_camp.id,
        'tester_id', v_report.user_id,
        'status', 'rejected',
        'credits_deducted', v_deduct,
        'clawed_credits', v_deduct,
        'tester_balance', v_new_balance,
        'new_balance', v_new_balance,
        'reason_code', v_final_reason_code,
        'tester_today_rejections', v_tester_today_rejections,
        'tester_warned', v_tester_warned,
        'tester_warning_msg', v_tester_warning_msg,
        'is_tester_locked', v_is_tester_locked,
        'tester_locked', v_is_tester_locked,
        'owner_today_rejections', v_owner_today_rejections,
        'owner_warned', v_owner_warned,
        'owner_warning_msg', v_owner_warning_msg,
        'is_owner_locked', v_is_owner_locked,
        'owner_locked', v_is_owner_locked
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._impl_reject_tester_report(uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_reject_tester_report(uuid, text, text) TO service_role;


-- ============================================================================
-- 10. GET OPEN MISSIONS & GET CAMPAIGN MISSION (EXPANDED FIELDS)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_open_missions()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_now timestamptz := now();
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    -- Đồng bộ trạng thái hết hạn trong DB
    perform public.sync_expired_missions();

    return jsonb_build_object(
        'missions', coalesce((
            select jsonb_agg(
                jsonb_build_object(
                    'id', t.id,
                    'title', t.title,
                    'description', t.description,
                    'reward_credits', t.reward_credits,
                    'max_participants', t.max_participants,
                    'participant_count', t.participant_count,
                    'status', t.status,
                    'ends_at', to_char(t.ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'created_at', to_char(t.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'campaign_id', t.campaign_id,
                    'app_name', t.app_name,
                    'icon_url', t.icon_url,
                    'package_name', t.package_name,
                    'creator_id', t.creator_id,
                    'creator_name', t.creator_name,
                    'reward_mode', t.reward_mode,
                    'duration_days', t.duration_days,
                    'fee_credits', t.fee_credits,
                    'escrow_total_credits', t.escrow_total_credits,
                    'escrow_remaining_credits', t.escrow_remaining_credits,
                    'tester_announcement', t.tester_announcement,
                    'version', t.version,
                    'is_mine', t.creator_id = v_user_id,
                    'has_joined', t.has_joined,
                    'has_completed', t.has_completed,
                    'submitted_days_count', t.submitted_days_count,
                    'today_submitted', t.today_submitted,
                    'today_report_status', t.today_report_status,
                    'report_status', t.report_status
                )
                order by t.sort_mine desc, t.sort_active desc, t.created_at desc, t.id desc
            )
            from (
                select
                    m.id, m.title, m.description, m.reward_credits,
                    m.max_participants, m.participant_count, m.status,
                    m.ends_at, m.created_at, m.campaign_id, m.creator_id,
                    m.reward_mode, m.duration_days, m.fee_credits,
                    m.escrow_total_credits, m.escrow_remaining_credits,
                    m.tester_announcement, m.version,
                    c.app_name, c.icon_url, c.package_name,
                    coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name,
                    case
                        when m.creator_id = v_user_id then 1
                        when exists (
                            select 1 from public.mission_participants mp_mine
                            where mp_mine.mission_id = m.id and mp_mine.user_id = v_user_id
                        ) then 1
                        else 0
                    end as sort_mine,
                    case m.status
                        when 'open' then 2
                        when 'full' then 2
                        when 'paused_by_creator' then 1
                        else 0
                    end as sort_active,
                    exists (
                        select 1 from public.mission_participants mp2
                        where mp2.mission_id = m.id and mp2.user_id = v_user_id
                    ) as has_joined,
                    exists (
                        select 1 from public.mission_participants mp3
                        where mp3.mission_id = m.id and mp3.user_id = v_user_id
                          and mp3.status = 'completed'
                    ) as has_completed,
                    (
                        select count(distinct tr.report_date)
                        from public.tester_reports tr
                        where tr.mission_id = m.id and tr.user_id = v_user_id
                    ) as submitted_days_count,
                    exists (
                        select 1 from public.tester_reports tr_today
                        where tr_today.mission_id = m.id and tr_today.user_id = v_user_id
                          and tr_today.report_date = v_vn_today
                    ) as today_submitted,
                    (
                        select tr_st.status
                        from public.tester_reports tr_st
                        where tr_st.mission_id = m.id and tr_st.user_id = v_user_id
                          and tr_st.report_date = v_vn_today
                        order by tr_st.created_at desc limit 1
                    ) as today_report_status,
                    (
                        select tr_once.status
                        from public.tester_reports tr_once
                        where tr_once.mission_id = m.id and tr_once.user_id = v_user_id
                        order by tr_once.created_at desc limit 1
                    ) as report_status
                from public.missions m
                join public.campaigns c on c.id = m.campaign_id
                left join public.profiles p on p.id = m.creator_id
                where m.status in ('open', 'full', 'paused_by_creator')
                  and m.ends_at > v_now
            ) t
        ), '[]'::jsonb),
        'server_now', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_open_missions() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_open_missions() TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_open_missions() TO service_role;


CREATE OR REPLACE FUNCTION public.get_campaign_mission(p_campaign_id uuid, p_mission_id uuid DEFAULT NULL::uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_row record;
begin
    if p_mission_id is not null then
        select
            m.id, m.title, m.description, m.reward_credits,
            m.max_participants, m.participant_count, m.status,
            m.ends_at, m.created_at, m.campaign_id, m.creator_id,
            m.reward_mode, m.duration_days, m.fee_credits,
            m.escrow_total_credits, m.escrow_remaining_credits,
            m.tester_announcement, m.version,
            c.app_name, c.icon_url, c.package_name,
            coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name
        into v_row
        from public.missions m
        join public.campaigns c on c.id = m.campaign_id
        left join public.profiles p on p.id = m.creator_id
        where m.id = p_mission_id
          and m.campaign_id = p_campaign_id
          and m.status <> 'deleted'
        limit 1;
    else
        select
            m.id, m.title, m.description, m.reward_credits,
            m.max_participants, m.participant_count, m.status,
            m.ends_at, m.created_at, m.campaign_id, m.creator_id,
            m.reward_mode, m.duration_days, m.fee_credits,
            m.escrow_total_credits, m.escrow_remaining_credits,
            m.tester_announcement, m.version,
            c.app_name, c.icon_url, c.package_name,
            coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name
        into v_row
        from public.missions m
        join public.campaigns c on c.id = m.campaign_id
        left join public.profiles p on p.id = m.creator_id
        where m.campaign_id = p_campaign_id
          and m.status <> 'deleted'
        order by m.created_at desc
        limit 1;
    end if;

    if v_row.id is null then
        return jsonb_build_object('mission', null);
    end if;

    return jsonb_build_object(
        'mission', jsonb_build_object(
            'id', v_row.id,
            'title', v_row.title,
            'description', v_row.description,
            'reward_credits', v_row.reward_credits,
            'max_participants', v_row.max_participants,
            'participant_count', v_row.participant_count,
            'status', v_row.status,
            'ends_at', to_char(v_row.ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'created_at', to_char(v_row.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'campaign_id', v_row.campaign_id,
            'app_name', v_row.app_name,
            'icon_url', v_row.icon_url,
            'package_name', v_row.package_name,
            'creator_id', v_row.creator_id,
            'creator_name', v_row.creator_name,
            'reward_mode', v_row.reward_mode,
            'duration_days', v_row.duration_days,
            'fee_credits', v_row.fee_credits,
            'escrow_total_credits', v_row.escrow_total_credits,
            'escrow_remaining_credits', v_row.escrow_remaining_credits,
            'tester_announcement', v_row.tester_announcement,
            'version', v_row.version,
            'is_mine', v_row.creator_id = v_user_id,
            'has_joined', exists (
                select 1 from public.mission_participants mp2
                where mp2.mission_id = v_row.id and mp2.user_id = v_user_id
            ),
            'has_completed', exists (
                select 1 from public.mission_participants mp3
                where mp3.mission_id = v_row.id and mp3.user_id = v_user_id
                  and mp3.status = 'completed'
            ),
            'submitted_days_count', (
                select count(distinct tr.report_date)
                from public.tester_reports tr
                where tr.mission_id = v_row.id and tr.user_id = v_user_id
            ),
            'today_submitted', exists (
                select 1 from public.tester_reports tr_today
                where tr_today.mission_id = v_row.id and tr_today.user_id = v_user_id
                  and tr_today.report_date = v_vn_today
            ),
            'today_report_status', (
                select tr_st.status
                from public.tester_reports tr_st
                where tr_st.mission_id = v_row.id and tr_st.user_id = v_user_id
                  and tr_st.report_date = v_vn_today
                order by tr_st.created_at desc limit 1
            ),
            'report_status', (
                select tr_once.status
                from public.tester_reports tr_once
                where tr_once.mission_id = v_row.id and tr_once.user_id = v_user_id
                order by tr_once.created_at desc limit 1
            )
        )
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_mission(uuid, uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_campaign_mission(uuid, uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_campaign_mission(uuid, uuid) TO service_role;


-- ============================================================================
-- 11. SYNC EXPIRED MISSIONS (SWEEP AND REFUND UNUSED ESCROW)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.sync_expired_missions()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_updated int := 0;
    v_m record;
begin
    update public.missions
    set status = 'expired'
    where status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    get diagnostics v_updated = row_count;

    -- Quét và hoàn quỹ cho các nhiệm vụ đã kết thúc mà không còn báo cáo chờ
    for v_m in
        select id from public.missions
        where (status = 'expired' or ends_at <= now())
          and escrow_remaining_credits > 0
          and not escrow_refunded
          and reward_mode in ('once', 'daily')
    loop
        perform public._check_and_refund_mission_escrow(v_m.id);
    end loop;

    return v_updated;
end;
$function$;

REVOKE ALL ON FUNCTION public.sync_expired_missions() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.sync_expired_missions() TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_expired_missions() TO service_role;


