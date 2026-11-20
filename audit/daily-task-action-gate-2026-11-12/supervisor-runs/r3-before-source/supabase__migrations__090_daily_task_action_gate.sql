-- ============================================================================
-- MIGRATION 090: DAILY TASK ACTION GATE FOR OWNER CAMPAIGN OPERATIONS
-- ============================================================================
-- Scope:
-- 1. Common Action Gate Assertion Helper:
--    - public._assert_owner_daily_task_action_allowed(p_owner_id uuid, p_today date)
--      Strictly verifies:
--      * Account is not locked.
--      * Canonical get_daily_task_status is non-null and status_date matches server's VN today.
--      * Both history_ready and carryover_ready are strictly 'true' (not null/false/omitted).
--      * met is strictly 'true' (not null/false/omitted).
--      * carryover_remaining_tests is non-null, valid integer, and strictly equal to 0.
--        Negative values (< 0) are treated as invalid/pending data and rejected.
--      * Raises stable error codes: ACCOUNT_LOCKED, DAILY_TASK_PENDING, DAILY_TASK_UNMET,
--        and OWNER_CARRYOVER_INCOMPLETE:<debt>.
-- 2. Owner Deletion Protection:
--    - public.campaign_removal_receipts:
--      Tombstone ledger for idempotent replay of campaign deletions.
--    - public._request_campaign_removal:
--      Serializes lifecycle operations under advisory lock.
--      Lock ordering follows LOCK_ORDER.md: advisory -> campaigns FOR UPDATE -> rounds/missions -> profiles.
--      Evaluates the action gate BEFORE any mission deletion, escrow refund, or round closing.
--      Idempotent return when campaign is already closed with pending deletion status,
--      or already physically deleted with existing tombstone receipt.
--      Strictly maintains financial calculations, remaining escrow checks, and refund isolation.
-- 3. Owner Resume Protection:
--    - public.resume_campaign:
--      Serializes lifecycle operations under advisory lock.
--      Lock ordering follows LOCK_ORDER.md: advisory -> campaigns FOR UPDATE -> profiles.
--      Evaluates the action gate and locked profile BEFORE the 30-day expiration check.
--      Replay idempotency: returns success if already open or completed.
--      Refreshes daily completion before evaluating missed streak against threshold.
-- 4. Production Confirmation RPC:
--    - public.confirm_campaign_production(p_campaign_id uuid):
--      Enforces canonical row locking, ownership, unlocked profile, and daily task action gate.
--      Returns idempotent success if already in production or completed.
-- 5. Direct Mutation Guards (Triggers):
--    - trg_campaigns_guard_action_gate_before_delete:
--      Blocks direct DELETE by authenticated owners unless daily task action gate is met.
--      Records tombstone receipt upon physical deletion.
--    - trg_campaigns_guard_action_gate_before_update:
--      Blocks direct client updates forging deletion-pending metadata or closing status.
--      Enforces daily task action gate on ANY transition to 'production' (regardless of prior status).
--      Enforces daily task action gate on manual pause when old status was active.
--    - Caller authentication strictly distinguishes service_role/worker/postgres from authenticated clients
--      using real database roles (current_user), impervious to spoofed JWT claims or custom session GUCs.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 0. TOMBSTONE LEDGER FOR DELETED CAMPAIGNS (REPLAY IDEMPOTENCY)
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.campaign_removal_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id uuid NOT NULL UNIQUE,
    owner_id uuid NOT NULL,
    app_name text NOT NULL,
    removed_by text NOT NULL, -- 'owner' or 'admin'
    removed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    status text NOT NULL DEFAULT 'removed',
    reason text
);

CREATE INDEX IF NOT EXISTS idx_campaign_removal_receipts_owner
    ON public.campaign_removal_receipts (owner_id, removed_at DESC);

ALTER TABLE public.campaign_removal_receipts ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.campaign_removal_receipts FROM anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.campaign_removal_receipts TO service_role, postgres;

-- Private helper to record receipt (only callable from trigger or service context)
CREATE OR REPLACE FUNCTION public._record_campaign_removal_receipt(
    p_campaign_id uuid,
    p_owner_id uuid,
    p_app_name text,
    p_removed_by text,
    p_status text,
    p_reason text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
begin
    if pg_trigger_depth() = 0 and session_user not in ('postgres', 'supabase_admin', 'service_role') then
        raise exception 'PERMISSION_DENIED: Cannot record removal receipt outside trigger or service context';
    end if;

    insert into public.campaign_removal_receipts (campaign_id, owner_id, app_name, removed_by, status, reason)
    values (p_campaign_id, p_owner_id, p_app_name, p_removed_by, p_status, p_reason)
    on conflict (campaign_id) do update set
        status = excluded.status,
        reason = excluded.reason,
        removed_at = clock_timestamp();
end;
$function$;

REVOKE ALL ON FUNCTION public._record_campaign_removal_receipt(uuid, uuid, text, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public._record_campaign_removal_receipt(uuid, uuid, text, text, text, text) TO authenticated, service_role;


-- ============================================================================
-- 1. INTERNAL HELPER: ASSERT OWNER DAILY TASK ACTION ALLOWED
-- ============================================================================

CREATE OR REPLACE FUNCTION public._assert_owner_daily_task_action_allowed(
    p_owner_id uuid,
    p_today date
)
RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_locked boolean;
    v_task_json jsonb;
    v_task record;
    v_debt int;
    v_history_ready_text text;
    v_carryover_ready_text text;
    v_met_text text;
    v_remaining_text text;
    v_real_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if p_owner_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_today is not null and p_today <> v_real_today then
        raise exception 'DAILY_TASK_PENDING: Ngày trạng thái nhiệm vụ không khớp với ngày hiện tại của máy chủ';
    end if;

    -- 1. Verify account is not locked
    select coalesce(is_locked, false)
    into v_locked
    from public.profiles
    where id = p_owner_id;

    if v_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    -- 2. Fetch canonical daily task status JSON for real VN date
    v_task_json := public.get_daily_task_status(p_owner_id, v_real_today);

    if v_task_json is null or v_task_json = '{}'::jsonb then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày chưa sẵn sàng hoặc đang đồng bộ';
    end if;

    -- Extract raw string values to detect omitted keys or explicit nulls
    v_history_ready_text := v_task_json ->> 'history_ready';
    v_carryover_ready_text := v_task_json ->> 'carryover_ready';
    v_met_text := v_task_json ->> 'met';
    v_remaining_text := v_task_json ->> 'carryover_remaining_tests';

    -- 3. Business date validation: must match current Vietnam date
    if (v_task_json ->> 'status_date') is null
       or (v_task_json ->> 'status_date') = ''
       or (v_task_json ->> 'status_date')::date <> v_real_today then
        raise exception 'DAILY_TASK_PENDING: Ngày trạng thái nhiệm vụ không khớp với ngày hiện tại của máy chủ';
    end if;

    -- 4. Readiness validation: history_ready and carryover_ready must both be explicitly true
    if v_history_ready_text is null or lower(v_history_ready_text) <> 'true'
       or v_carryover_ready_text is null or lower(v_carryover_ready_text) <> 'true' then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày chưa sẵn sàng hoặc đang đồng bộ';
    end if;

    -- 5. Carryover debt validation: must be present and parseable
    if v_remaining_text is null or trim(v_remaining_text) = '' then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày thiếu thông tin nợ cộng dồn';
    end if;

    begin
        v_debt := v_remaining_text::int;
    exception when others then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nợ cộng dồn không hợp lệ';
    end;

    -- Negative carryover debt is corrupt/unsettled state -> treat as pending/blocked
    if v_debt < 0 then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nợ cộng dồn đang ở trạng thái chờ đối soát';
    end if;

    if v_debt > 0 then
        raise exception 'OWNER_CARRYOVER_INCOMPLETE:%', v_debt
            using errcode = 'P0001';
    end if;

    -- 6. Daily task completion: met must be present and explicitly true
    if v_met_text is null or trim(v_met_text) = '' then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày chưa sẵn sàng hoặc đang đồng bộ';
    end if;

    if lower(v_met_text) <> 'true' then
        raise exception 'DAILY_TASK_UNMET: OWNER_INACTIVE: Chưa hoàn thành nhiệm vụ ngày hôm nay';
    end if;
end;
$function$;

REVOKE ALL ON FUNCTION public._assert_owner_daily_task_action_allowed(uuid, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public._assert_owner_daily_task_action_allowed(uuid, date) TO authenticated, service_role;


-- ============================================================================
-- 2. OWNER DELETION & PENDING REMOVAL IMPLEMENTATION
-- ============================================================================

CREATE OR REPLACE FUNCTION public._request_campaign_removal(
    p_campaign_id uuid,
    p_reason text,
    p_by_admin boolean
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_jwt_role text := coalesce(current_setting('request.jwt.claim.role', true), '');
    v_caller_role text;
    v_caller_locked boolean;
    v_owner_id uuid;
    v_app_name text;
    v_camp_status text;
    v_camp_paused_reason text;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_m record;
    v_r record;
    v_has_pending boolean := false;
    v_has_unsettled_bonus boolean := false;
    v_has_unsettled_missions boolean := false;
    v_effective_reason text;
    v_receipt record;
begin
    if v_caller_id is null and v_jwt_role <> 'service_role' and session_user not in ('postgres', 'supabase_admin', 'service_role') then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    -- Serialize per-caller lifecycle mutations under advisory lock FIRST
    if v_caller_id is not null then
        perform pg_advisory_xact_lock(hashtextextended('user_campaign_lifecycle', hashtext(v_caller_id::text)));
    end if;

    if v_caller_id is not null then
        select role, coalesce(is_locked, false)
        into v_caller_role, v_caller_locked
        from public.profiles
        where id = v_caller_id;

        if v_caller_locked = true then
            raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
        end if;
    end if;

    if p_by_admin then
        if v_caller_role is null or lower(v_caller_role) != 'admin' then
            raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền gỡ bỏ ứng dụng';
        end if;
    end if;

    -- Canonical lock order: lock campaign row FOR UPDATE before rounds/missions
    select app_name, owner_id, status, paused_reason
    into v_app_name, v_owner_id, v_camp_status, v_camp_paused_reason
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_app_name is null then
        -- Check tombstone receipts ledger for idempotent replay of previous physical deletion
        select campaign_id, owner_id, app_name, removed_by, status, reason
        into v_receipt
        from public.campaign_removal_receipts
        where campaign_id = p_campaign_id;

        if found then
            if not p_by_admin and v_receipt.owner_id is distinct from v_caller_id then
                raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng';
            end if;
            if p_by_admin and (v_caller_role is null or lower(v_caller_role) != 'admin') then
                raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền gỡ bỏ ứng dụng';
            end if;

            return jsonb_build_object(
                'success', true,
                'status', v_receipt.status,
                'campaign_id', v_receipt.campaign_id,
                'app_name', v_receipt.app_name,
                'owner_id', v_receipt.owner_id,
                'reason', v_receipt.reason,
                'replayed', true,
                'message', case
                    when v_receipt.removed_by = 'admin' then 'Chiến dịch đã được xóa hoàn toàn'
                    else 'Ứng dụng đã được gỡ bỏ hoàn toàn'
                end
            );
        end if;

        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Strict Actual-Owner Authorization when not requested by admin:
    if not p_by_admin then
        if v_owner_id is distinct from v_caller_id then
            raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng';
        end if;

        -- Replay check: If campaign is already pending removal from a previously accepted request,
        -- return pending_removal (idempotent); do NOT bypass gate to perform physical deletion!
        if v_camp_status = 'closed' and v_camp_paused_reason in ('owner_deletion_pending', 'admin_deletion_pending') then
            return jsonb_build_object(
                'success', true,
                'status', 'pending_removal',
                'campaign_id', p_campaign_id,
                'app_name', v_app_name,
                'owner_id', v_owner_id,
                'reason', coalesce(nullif(trim(p_reason), ''), 'Gỡ bỏ bởi Chủ sở hữu'),
                'replayed', true,
                'message', case
                    when p_by_admin then 'Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất xóa'
                    else 'Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất gỡ bỏ'
                end
            );
        end if;

        -- ENFORCE DAILY TASK ACTION GATE BEFORE ANY SIDE EFFECT OR MUTATION
        perform public._assert_owner_daily_task_action_allowed(v_caller_id, v_today);
    end if;

    v_effective_reason := coalesce(
        nullif(trim(p_reason), ''),
        case when p_by_admin then 'Gỡ bỏ bởi Admin' else 'Gỡ bỏ bởi Chủ sở hữu' end
    );

    -- Close any non-deleted missions and attempt to refund remaining escrow
    for v_m in
        select id, status, escrow_remaining_credits, escrow_refunded
        from public.missions
        where campaign_id = p_campaign_id
        for update
    loop
        if v_m.status <> 'deleted' then
            update public.missions
            set status = 'deleted',
                admin_closed_at = coalesce(admin_closed_at, now())
            where id = v_m.id;
        end if;

        if v_m.escrow_remaining_credits > 0 and not v_m.escrow_refunded then
            perform public._check_and_refund_mission_escrow(v_m.id);
        end if;
    end loop;

    -- Transition active bonus rounds to closing
    update public.campaign_tester_bonus_rounds
    set status = 'closing',
        stopped_at = coalesce(stopped_at, clock_timestamp())
    where campaign_id = p_campaign_id
      and status = 'active';

    -- Check if any pending reports exist on this campaign
    select exists (
        select 1 from public.tester_reports
        where campaign_id = p_campaign_id
          and status = 'pending'
    ) into v_has_pending;

    if v_has_pending then
        update public.campaigns
        set status = 'closed',
            paused_reason = case when p_by_admin then 'admin_deletion_pending' else 'owner_deletion_pending' end,
            admin_note = case when p_by_admin then v_effective_reason else admin_note end
        where id = p_campaign_id;

        -- Quota decreases upon transition to 'closed'
        perform public.refresh_daily_completion(v_owner_id, v_today);

        return jsonb_build_object(
            'success', true,
            'status', 'pending_removal',
            'campaign_id', p_campaign_id,
            'app_name', v_app_name,
            'owner_id', v_owner_id,
            'reason', v_effective_reason,
            'message', case
                when p_by_admin then 'Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất xóa'
                else 'Chiến dịch đã được đóng để giải quyết các báo cáo tester đang chờ trước khi hoàn tất gỡ bỏ'
            end
        );
    end if;

    -- Check if any unsettled rounds remain
    for v_r in
        select id, remaining_credits
        from public.campaign_tester_bonus_rounds
        where campaign_id = p_campaign_id
          and status in ('active', 'closing')
        order by created_at
        for update
    loop
        if v_r.remaining_credits > 0 then
            declare
                v_close_res jsonb;
            begin
                v_close_res := public._close_and_refund_tester_bonus_round(
                    v_r.id,
                    case when p_by_admin then 'admin_delete_refund' else 'owner_delete_refund' end
                );
                if coalesce((v_close_res->>'closed')::boolean, false) = false then
                    v_has_unsettled_bonus := true;
                end if;
            end;
        end if;
    end loop;

    -- Check unsettled missions
    select exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and (status <> 'deleted' or (escrow_remaining_credits > 0 and not escrow_refunded))
    ) into v_has_unsettled_missions;

    if v_has_unsettled_bonus or v_has_unsettled_missions then
        update public.campaigns
        set status = 'closed',
            paused_reason = case when p_by_admin then 'admin_deletion_pending' else 'owner_deletion_pending' end,
            admin_note = case when p_by_admin then v_effective_reason else admin_note end
        where id = p_campaign_id;

        perform public.refresh_daily_completion(v_owner_id, v_today);

        return jsonb_build_object(
            'success', true,
            'status', 'pending_removal',
            'campaign_id', p_campaign_id,
            'app_name', v_app_name,
            'owner_id', v_owner_id,
            'reason', v_effective_reason,
            'message', case
                when p_by_admin then 'Chiến dịch đã được đóng để hoàn tất kết toán ngân sách trước khi hoàn tất xóa'
                else 'Chiến dịch đã được đóng để hoàn tất kết toán ngân sách trước khi hoàn tất gỡ bỏ'
            end
        );
    end if;

    -- Physical deletion: record tombstone receipt for idempotent replay protection
    insert into public.campaign_removal_receipts (campaign_id, owner_id, app_name, removed_by, status, reason)
    values (
        p_campaign_id,
        v_owner_id,
        v_app_name,
        case when p_by_admin then 'admin' else 'owner' end,
        case when p_by_admin then 'deleted' else 'removed' end,
        v_effective_reason
    )
    on conflict (campaign_id) do update
    set removed_at = clock_timestamp(),
        status = excluded.status,
        reason = excluded.reason;

    delete from public.campaigns
    where id = p_campaign_id;

    perform public.refresh_daily_completion(v_owner_id, v_today);

    return jsonb_build_object(
        'success', true,
        'status', case when p_by_admin then 'deleted' else 'removed' end,
        'campaign_id', p_campaign_id,
        'app_name', v_app_name,
        'owner_id', v_owner_id,
        'reason', v_effective_reason,
        'message', case
            when p_by_admin then 'Chiến dịch đã được xóa hoàn toàn'
            else 'Ứng dụng đã được gỡ bỏ hoàn toàn'
        end
    );
end;
$function$;


-- ============================================================================
-- 3. RESUME CAMPAIGN (OWNER & ADMIN) WITH ACTION GATE & REPLAY IDEMPOTENCY
-- ============================================================================

CREATE OR REPLACE FUNCTION public.resume_campaign(
    p_campaign_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_camp record;
    v_threshold int := 7;
    v_missed int;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_is_locked boolean;
    v_user_created_date date;
begin
    -- 1. Must be authenticated
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Serialize per-owner lifecycle mutations under advisory lock
    perform pg_advisory_xact_lock(hashtextextended('user_campaign_lifecycle', hashtext(v_uid::text)));

    -- 2. Lock campaign row FOR UPDATE following LOCK_ORDER.md
    select id, owner_id, app_name, status, paused_reason, created_at, slot_number
    into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp.owner_id <> v_uid then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Replay idempotency: if campaign is already open, return success safely
    if v_camp.status = 'open' then
        return jsonb_build_object(
            'success', true,
            'status', 'open',
            'campaign_id', p_campaign_id,
            'app_name', v_camp.app_name,
            'replayed', true,
            'message', 'Chiến dịch đã được kích hoạt lại'
        );
    end if;

    -- Replay idempotency: if campaign was already transitioned to completed (e.g. 30-day window expired)
    if v_camp.status = 'completed' and v_camp.paused_reason = 'window_expired' then
        return jsonb_build_object(
            'success', true,
            'status', 'completed',
            'campaign_id', p_campaign_id,
            'app_name', v_camp.app_name,
            'replayed', true,
            'message', 'Chiến dịch đã kết thúc kỳ kiểm thử 30 ngày'
        );
    end if;

    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    if v_camp.paused_reason = 'admin_manual' then
        raise exception 'PAUSED_BY_ADMIN';
    end if;

    if v_camp.slot_number > 1 then
        if not exists (
            select 1 from public.user_slots s
            where s.user_id = v_camp.owner_id
              and s.slot_number = v_camp.slot_number
              and s.expires_at > clock_timestamp()
        ) then
            raise exception 'SLOT_EXPIRED';
        end if;
    end if;

    -- 3. Check owner profile unlocked status
    select is_locked, (created_at at time zone 'Asia/Ho_Chi_Minh')::date
    into v_is_locked, v_user_created_date
    from public.profiles
    where id = v_uid;

    if coalesce(v_is_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    -- If account existed prior to today, yesterday MUST be finalized in user_daily_tasks
    if v_user_created_date is not null and v_user_created_date < v_today then
        if not exists (
            select 1 from public.user_daily_tasks t
            where t.user_id = v_uid
              and t.task_date = (v_today - 1)
              and t.finalized_at is not null
        ) then
            raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày hôm qua chưa được chốt';
        end if;
    end if;

    -- 4. ENFORCE DAILY TASK ACTION GATE BEFORE THE 30-DAY EXPIRATION CHECK
    perform public._assert_owner_daily_task_action_allowed(v_uid, v_today);

    -- Sync completion and reset streak if met
    perform public.refresh_daily_completion(v_uid, v_today);

    select coalesce((
        select missed_days from public.owner_auto_pause_configs where id = 'default'
    ), 7) into v_threshold;

    select daily_missed_streak into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= v_threshold then
        raise exception 'DAILY_TASK_UNMET: OWNER_INACTIVE: Chuỗi ngày bỏ lỡ vượt ngưỡng cho phép';
    end if;

    -- 5. If campaign is >= 30 days old, transition to completed
    if v_camp.created_at <= clock_timestamp() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            paused_reason = 'window_expired'
        where id = p_campaign_id;

        perform public.refresh_daily_completion(v_uid, v_today);

        return jsonb_build_object(
            'success', true,
            'status', 'completed',
            'campaign_id', p_campaign_id,
            'app_name', v_camp.app_name,
            'message', 'Chiến dịch đã kết thúc kỳ kiểm thử 30 ngày'
        );
    end if;

    -- 6. Reactivate campaign
    update public.campaigns
    set status = 'open',
        paused_reason = null
    where id = p_campaign_id;

    -- Quota increases upon resumption to 'open'
    perform public.refresh_daily_completion(v_uid, v_today);

    return jsonb_build_object(
        'success', true,
        'status', 'open',
        'campaign_id', p_campaign_id,
        'app_name', v_camp.app_name,
        'message', 'Chiến dịch đã được kích hoạt lại'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.resume_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.resume_campaign(uuid) TO authenticated, service_role;


-- Admin Resume Campaign (keeps existing bypass for owner inactivity, retains 30-day and slot checks)
CREATE OR REPLACE FUNCTION public.admin_resume_campaign(
    p_campaign_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_admin_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_admin_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_role from public.profiles where id = v_admin_id;
    if v_role is null or lower(v_role) != 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Canonical lock order: lock campaign row FOR UPDATE following LOCK_ORDER.md
    select id, owner_id, app_name, status, paused_reason, created_at, slot_number
    into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Replay idempotency: if campaign is already open, return success safely
    if v_camp.status = 'open' then
        return jsonb_build_object(
            'success', true,
            'status', 'open',
            'campaign_id', p_campaign_id,
            'app_name', v_camp.app_name,
            'replayed', true,
            'message', 'Chiến dịch đã được kích hoạt lại bởi Admin'
        );
    end if;

    if v_camp.status = 'completed' and v_camp.paused_reason = 'window_expired' then
        return jsonb_build_object(
            'success', true,
            'status', 'completed',
            'campaign_id', p_campaign_id,
            'app_name', v_camp.app_name,
            'replayed', true,
            'message', 'Chiến dịch đã kết thúc kỳ kiểm thử 30 ngày'
        );
    end if;

    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    if v_camp.slot_number > 1 then
        if not exists (
            select 1 from public.user_slots s
            where s.user_id = v_camp.owner_id
              and s.slot_number = v_camp.slot_number
              and s.expires_at > clock_timestamp()
        ) then
            raise exception 'SLOT_EXPIRED';
        end if;
    end if;

    if v_camp.created_at <= clock_timestamp() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            paused_reason = 'window_expired'
        where id = p_campaign_id;

        perform public.refresh_daily_completion(v_camp.owner_id, v_today);

        return jsonb_build_object(
            'success', true,
            'status', 'completed',
            'campaign_id', p_campaign_id,
            'app_name', v_camp.app_name,
            'message', 'Chiến dịch đã kết thúc kỳ kiểm thử 30 ngày'
        );
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null
    where id = p_campaign_id;

    perform public.refresh_daily_completion(v_camp.owner_id, v_today);

    return jsonb_build_object(
        'success', true,
        'status', 'open',
        'campaign_id', p_campaign_id,
        'app_name', v_camp.app_name,
        'message', 'Chiến dịch đã được kích hoạt lại bởi Admin'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_resume_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_resume_campaign(uuid) TO authenticated, service_role;


-- ============================================================================
-- 4. CONFIRM CAMPAIGN PRODUCTION RPC
-- ============================================================================

CREATE OR REPLACE FUNCTION public.confirm_campaign_production(
    p_campaign_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_camp record;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null and session_user not in ('postgres', 'supabase_admin', 'service_role') then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    -- Serialize per-owner lifecycle mutations under advisory lock FIRST
    if v_caller_id is not null then
        perform pg_advisory_xact_lock(hashtextextended('user_campaign_lifecycle', hashtext(v_caller_id::text)));
    end if;

    if v_caller_id is not null then
        select role, coalesce(is_locked, false)
        into v_caller_role, v_caller_locked
        from public.profiles
        where id = v_caller_id;

        if v_caller_locked = true then
            raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
        end if;
    end if;

    -- Lock campaign row FOR UPDATE following LOCK_ORDER.md
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        if v_camp.owner_id is distinct from v_caller_id then
            raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu mới có quyền xác nhận production';
        end if;
    end if;

    -- Idempotent check (replayed if already in production or completed)
    if v_camp.status in ('production', 'completed') then
        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', v_camp.status,
            'replayed', true
        );
    end if;

    -- Enforce action gate if caller is owner or admin acting on own app
    if v_caller_role is null or lower(v_caller_role) != 'admin' or v_caller_id = v_camp.owner_id then
        perform public._assert_owner_daily_task_action_allowed(v_camp.owner_id, v_today);
    end if;

    update public.campaigns
    set status = 'production',
        paused_reason = null
    where id = p_campaign_id;

    perform public.refresh_daily_completion(v_camp.owner_id, v_today);

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'production'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.confirm_campaign_production(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.confirm_campaign_production(uuid) TO authenticated, service_role;


-- ============================================================================
-- 5. DIRECT MUTATION GUARDS (TRIGGERS)
-- ============================================================================

-- Guard direct DELETE on campaigns
CREATE OR REPLACE FUNCTION public.trg_fn_campaigns_guard_action_gate_before_delete()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_is_service_or_worker boolean;
begin
    -- Real database role check: only real service_role, postgres, or supabase_admin can bypass
    v_is_service_or_worker := (current_user in ('postgres', 'supabase_admin', 'service_role'));

    -- Service role / worker cleanup: allow and record tombstone
    if v_is_service_or_worker then
        perform public._record_campaign_removal_receipt(
            old.id,
            old.owner_id,
            old.app_name,
            case when current_user in ('postgres', 'supabase_admin') then 'admin' else 'worker' end,
            'removed',
            'Service/worker physical cleanup'
        );
        return old;
    end if;

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

    -- Unlocked admin acting on someone else's campaign: allow and record tombstone
    if v_caller_role is not null and lower(v_caller_role) = 'admin' and v_caller_id <> old.owner_id then
        perform public._record_campaign_removal_receipt(old.id, old.owner_id, old.app_name, 'admin', 'deleted', 'Admin direct deletion');
        return old;
    end if;

    -- Check financial obligations (087 / 088 bonus rounds and missions) first
    if exists (
        select 1 from public.campaign_tester_bonus_rounds
        where campaign_id = old.id
          and status in ('active', 'closing')
          and remaining_credits > 0
    ) then
        raise exception 'CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_BONUS_ROUND: Ứng dụng còn đợt thưởng tester chưa kết toán';
    end if;

    if exists (
        select 1 from public.missions
        where campaign_id = old.id
          and (status <> 'deleted' or (escrow_remaining_credits > 0 and not escrow_refunded))
    ) then
        raise exception 'CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_MISSION: Ứng dụng còn nhiệm vụ tester chưa kết toán';
    end if;

    -- Direct client DELETE cannot physically delete campaigns under pending_removal;
    -- physical cleanup must be performed by the worker/service_role.
    if old.status = 'closed' and old.paused_reason in ('owner_deletion_pending', 'admin_deletion_pending') then
        raise exception 'PERMISSION_DENIED: Ứng dụng đang trong quy trình chờ xử lý gỡ bỏ bởi hệ thống';
    end if;

    -- Enforce daily task action gate for owner
    perform public._assert_owner_daily_task_action_allowed(old.owner_id, v_today);

    -- Record tombstone receipt upon deletion
    perform public._record_campaign_removal_receipt(old.id, old.owner_id, old.app_name, 'owner', 'removed', 'Owner direct deletion');

    return old;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_action_gate_before_delete ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_action_gate_before_delete
BEFORE DELETE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_fn_campaigns_guard_action_gate_before_delete();


-- Guard direct UPDATE on campaigns (production, completed, closed, manual pause, metadata forgery)
CREATE OR REPLACE FUNCTION public.trg_fn_campaigns_guard_action_gate_before_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_was_active boolean;
    v_now_reducing boolean;
    v_is_service_or_worker boolean;
begin
    -- Real database role check: only real service_role, postgres, or supabase_admin can bypass
    v_is_service_or_worker := (current_user in ('postgres', 'supabase_admin', 'service_role'));

    -- Service role / worker / internal SECURITY DEFINER functions: allow
    if v_is_service_or_worker then
        return new;
    end if;

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

    -- Unlocked admin acting on someone else's campaign: allow
    if v_caller_role is not null and lower(v_caller_role) = 'admin' and v_caller_id <> old.owner_id then
        return new;
    end if;

    -- 1. Anti-forgery: Direct clients CANNOT forge deletion-pending metadata
    if new.paused_reason in ('owner_deletion_pending', 'admin_deletion_pending')
       and new.paused_reason is distinct from old.paused_reason then
        raise exception 'PERMISSION_DENIED: Không thể tự đặt trạng thái chờ gỡ bỏ';
    end if;

    -- 2. Anti-forgery: Direct clients CANNOT forge status 'closed' directly
    if new.status = 'closed' and old.status <> 'closed' then
        raise exception 'PERMISSION_DENIED: Trạng thái đóng chỉ được thực hiện qua quy trình gỡ bỏ';
    end if;

    -- 3. Anti-forgery: Direct clients CANNOT forge status 'completed' directly
    if new.status = 'completed' and old.status <> 'completed' then
        raise exception 'PERMISSION_DENIED: Trạng thái hoàn thành chỉ được thực hiện qua tiếp tục app quá 30 ngày';
    end if;

    -- 4. Action Gate: Transition to 'production' MUST ALWAYS PASS THE ACTION GATE
    if new.status = 'production' and old.status <> 'production' then
        perform public._assert_owner_daily_task_action_allowed(old.owner_id, v_today);
    end if;

    -- 5. Action Gate: Manual pause when old status was active reduces daily quota
    v_was_active := (old.status = 'open' or (old.status = 'paused' and old.paused_reason = 'owner_inactive'));
    v_now_reducing := (new.status = 'paused' and new.paused_reason is distinct from 'owner_inactive');

    if v_was_active and v_now_reducing and new.status <> 'production' then
        perform public._assert_owner_daily_task_action_allowed(old.owner_id, v_today);
    end if;

    return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_action_gate_before_update ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_action_gate_before_update
BEFORE UPDATE OF status, paused_reason ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_fn_campaigns_guard_action_gate_before_update();


-- Keep user_daily_tasks completion quota in sync after any campaign lifecycle mutation
CREATE OR REPLACE FUNCTION public.trg_fn_campaigns_refresh_daily_completion_after()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_owner_id uuid;
begin
    if TG_OP = 'DELETE' then
        v_owner_id := old.owner_id;
    else
        v_owner_id := new.owner_id;
    end if;

    if v_owner_id is not null then
        if TG_OP = 'DELETE'
           or (new.status is distinct from old.status)
           or (new.paused_reason is distinct from old.paused_reason) then
            perform public.refresh_daily_completion(v_owner_id, v_today);
        end if;
    end if;

    if TG_OP = 'DELETE' then
        return old;
    else
        return new;
    end if;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_refresh_daily_completion_after ON public.campaigns;
CREATE TRIGGER trg_campaigns_refresh_daily_completion_after
    AFTER INSERT OR UPDATE OR DELETE ON public.campaigns
    FOR EACH ROW
    EXECUTE FUNCTION public.trg_fn_campaigns_refresh_daily_completion_after();

NOTIFY pgrst, 'reload schema';

COMMIT;
