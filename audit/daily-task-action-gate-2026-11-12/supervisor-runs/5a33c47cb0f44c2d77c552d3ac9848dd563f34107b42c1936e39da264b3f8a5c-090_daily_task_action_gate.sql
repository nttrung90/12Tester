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
--      * Raises stable error codes: ACCOUNT_LOCKED, DAILY_TASK_PENDING, DAILY_TASK_UNMET,
--        and OWNER_CARRYOVER_INCOMPLETE:<debt>.
-- 2. Owner Deletion Protection:
--    - public._request_campaign_removal:
--      Evaluates the action gate BEFORE any mission deletion, escrow refund, or round closing.
--      Allows idempotent replay when campaign is already closed with pending deletion status.
-- 3. Owner Resume Protection:
--    - public.resume_campaign:
--      Evaluates the action gate and locked profile BEFORE the 30-day expiration check.
--      Owner cannot bypass task/carryover debt by resuming an expired app into 'completed'.
-- 4. Production Confirmation RPC:
--    - public.confirm_campaign_production(p_campaign_id uuid):
--      Enforces row locking, ownership, unlocked profile, and daily task action gate.
--      Returns idempotent success if already in production.
-- 5. Direct Mutation Guards (Triggers):
--    - trg_campaigns_guard_action_gate_before_delete:
--      Intercepts direct DELETE attempts by authenticated owners and enforces the gate.
--    - trg_campaigns_guard_action_gate_before_update:
--      Intercepts direct status updates by authenticated owners that reduce daily quota
--      (production, completed, closed, or manual pause from active state) and enforces the gate.
--    - Preserves unhindered execution for unlocked Admins and service_role/workers.
--    - trg_campaigns_sync_daily_quota_after_update & after_delete:
--      Keeps daily completion and quota projection in sync upon status changes.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. INTERNAL HELPER: ASSERT OWNER DAILY TASK ACTION ALLOWED
-- ============================================================================

CREATE OR REPLACE FUNCTION public._assert_owner_daily_task_action_allowed(
    p_owner_id uuid,
    p_today date
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_status jsonb;
    v_met text;
    v_history_ready text;
    v_carryover_ready text;
    v_carry_rem_text text;
    v_carry_rem integer;
    v_status_date text;
    v_is_locked boolean;
begin
    if p_owner_id is null then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- 1. Verify account is not locked
    select coalesce(is_locked, false) into v_is_locked
    from public.profiles
    where id = p_owner_id;

    if v_is_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    -- 2. Fetch canonical daily task status for server's VN today
    v_status := public.get_daily_task_status(p_owner_id, p_today);

    if v_status is null then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày chưa sẵn sàng hoặc đang đồng bộ';
    end if;

    -- Validate status_date strictly matches server's VN date
    v_status_date := v_status ->> 'status_date';
    if v_status_date is null or v_status_date <> to_char(p_today, 'YYYY-MM-DD') then
        raise exception 'DAILY_TASK_PENDING: Ngày trạng thái nhiệm vụ không khớp với ngày hiện tại của máy chủ';
    end if;

    -- Strict readiness check: must be explicitly 'true', NOT null, missing, or 'false'
    v_history_ready := v_status ->> 'history_ready';
    v_carryover_ready := v_status ->> 'carryover_ready';
    if v_history_ready is null or v_history_ready <> 'true'
       or v_carryover_ready is null or v_carryover_ready <> 'true' then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày chưa sẵn sàng hoặc đang đồng bộ';
    end if;

    -- Strict met check: must be explicitly 'true'
    v_met := v_status ->> 'met';
    if v_met is null or v_met <> 'true' then
        raise exception 'DAILY_TASK_UNMET: OWNER_INACTIVE: Chưa hoàn thành nhiệm vụ ngày hôm nay';
    end if;

    -- Strict carryover debt check: must be present, valid integer, and strictly 0
    v_carry_rem_text := v_status ->> 'carryover_remaining_tests';
    if v_carry_rem_text is null then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nhiệm vụ ngày thiếu thông tin nợ cộng dồn';
    end if;

    begin
        v_carry_rem := v_carry_rem_text::integer;
    exception when others then
        raise exception 'DAILY_TASK_PENDING: Dữ liệu nợ cộng dồn không hợp lệ';
    end;

    if v_carry_rem > 0 then
        raise exception 'OWNER_CARRYOVER_INCOMPLETE:%', v_carry_rem;
    end if;
end;
$function$;

REVOKE ALL ON FUNCTION public._assert_owner_daily_task_action_allowed(uuid, date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._assert_owner_daily_task_action_allowed(uuid, date) TO service_role;


-- ============================================================================
-- 2. UPDATE _REQUEST_CAMPAIGN_REMOVAL WITH EARLY ACTION GATE
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
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    if p_by_admin then
        if v_caller_role is null or lower(v_caller_role) != 'admin' then
            raise exception 'PERMISSION_DENIED: Chỉ Quản trị viên chưa bị khóa mới có quyền gỡ bỏ ứng dụng';
        end if;
    end if;

    -- Level 3: Lock campaign row FOR UPDATE BEFORE round locks
    select app_name, owner_id, status, paused_reason
    into v_app_name, v_owner_id, v_camp_status, v_camp_paused_reason
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Strict Actual-Owner Authorization when not requested by admin:
    if not p_by_admin then
        if v_owner_id is distinct from v_caller_id then
            raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng';
        end if;

        -- Replay check: If campaign is already pending removal from a previously accepted request,
        -- allow reconcile/worker to proceed without re-evaluating today's daily task gate.
        if not (v_camp_status = 'closed' and v_camp_paused_reason in ('owner_deletion_pending', 'admin_deletion_pending')) then
            -- ENFORCE DAILY TASK ACTION GATE BEFORE ANY SIDE EFFECT OR MUTATION
            perform public._assert_owner_daily_task_action_allowed(v_caller_id, v_today);
        end if;
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
        -- Stop new eligibility immediately by setting status to 'closed'.
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

    -- Physical deletion
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

REVOKE ALL ON FUNCTION public._request_campaign_removal(uuid, text, boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._request_campaign_removal(uuid, text, boolean) TO service_role;


-- ============================================================================
-- 3. UPDATE RESUME_CAMPAIGN WITH STRICT GATE BEFORE 30-DAY CHECK
-- ============================================================================

CREATE OR REPLACE FUNCTION public.resume_campaign(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_uid uuid := auth.uid();
    v_camp public.campaigns%rowtype;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_missed integer;
    v_threshold integer;
    v_is_locked boolean;
    v_user_created_date date;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Documented lock ordering:
    -- 1. Advisory transaction lock scoped to owner to serialize concurrent resume checks
    perform pg_advisory_xact_lock(hashtextextended('user_campaign_resume', hashtext(v_uid::text)));

    -- 2. Lock target campaign row
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

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

    -- 3. Lock owner profile row and verify unlocked status
    select is_locked, (created_at at time zone 'Asia/Ho_Chi_Minh')::date
    into v_is_locked, v_user_created_date
    from public.profiles
    where id = v_uid
    for update;

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
            completed_at = clock_timestamp(),
            paused_reason = null,
            admin_pause_reason_code = null,
            admin_note = null,
            admin_resumed = false
        where id = p_campaign_id;

        perform public.refresh_daily_completion(v_uid, v_today);

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'completed'
        );
    end if;

    -- 6. Re-open campaign
    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null,
        admin_pause_reason_code = null,
        admin_note = null,
        admin_resumed = false
    where id = p_campaign_id;

    perform public.refresh_daily_completion(v_uid, v_today);

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.resume_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.resume_campaign(uuid) TO authenticated, service_role;


-- ============================================================================
-- 4. NEW RPC: CONFIRM_CAMPAIGN_PRODUCTION
-- ============================================================================

CREATE OR REPLACE FUNCTION public.confirm_campaign_production(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_camp public.campaigns%rowtype;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    -- Row lock following LOCK_ORDER.md
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_caller_role is null or lower(v_caller_role) != 'admin' then
        if v_camp.owner_id <> v_caller_id then
            raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu mới có quyền xác nhận production';
        end if;
    end if;

    -- Idempotent check
    if v_camp.status = 'production' then
        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'production',
            'idempotent', true
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
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    -- Service role / worker (auth.uid() is null): allow
    if v_caller_id is null then
        return old;
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
        return old;
    end if;

    -- If campaign is already pending removal from a committed request: allow physical deletion
    if old.status = 'closed' and old.paused_reason in ('owner_deletion_pending', 'admin_deletion_pending') then
        return old;
    end if;

    -- Enforce daily task action gate for owner
    perform public._assert_owner_daily_task_action_allowed(old.owner_id, v_today);

    return old;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_action_gate_before_delete ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_action_gate_before_delete
BEFORE DELETE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_fn_campaigns_guard_action_gate_before_delete();


-- Guard direct UPDATE on campaigns (production, completed, closed, manual pause)
CREATE OR REPLACE FUNCTION public.trg_fn_campaigns_guard_action_gate_before_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_was_active boolean;
    v_now_reducing boolean;
begin
    -- Service role / worker / background system: allow
    if v_caller_id is null then
        return new;
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

    -- Was the campaign contributing to owner's daily active app count?
    v_was_active := (old.status = 'open' or (old.status = 'paused' and old.paused_reason = 'owner_inactive'));

    -- Is the new state reducing or removing it from daily active app count?
    v_now_reducing := (
        new.status in ('production', 'completed', 'closed')
        or (new.status = 'paused' and new.paused_reason is distinct from 'owner_inactive')
    );

    if v_was_active and v_now_reducing then
        -- Enforce daily task action gate
        perform public._assert_owner_daily_task_action_allowed(old.owner_id, v_today);
    end if;

    return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_action_gate_before_update ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_action_gate_before_update
BEFORE UPDATE OF status, paused_reason ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_fn_campaigns_guard_action_gate_before_update();


-- Automatic sync of daily quota after status update or delete
CREATE OR REPLACE FUNCTION public.trg_fn_campaigns_sync_daily_quota_on_status_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if tg_op = 'DELETE' then
        perform public.refresh_daily_completion(old.owner_id, v_today);
        return old;
    elsif tg_op = 'UPDATE' then
        if (new.status is distinct from old.status) or (new.paused_reason is distinct from old.paused_reason) then
            perform public.refresh_daily_completion(coalesce(new.owner_id, old.owner_id), v_today);
        end if;
        return new;
    end if;
    return null;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_sync_daily_quota_after_update ON public.campaigns;
CREATE TRIGGER trg_campaigns_sync_daily_quota_after_update
AFTER UPDATE OF status, paused_reason ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_fn_campaigns_sync_daily_quota_on_status_change();

DROP TRIGGER IF EXISTS trg_campaigns_sync_daily_quota_after_delete ON public.campaigns;
CREATE TRIGGER trg_campaigns_sync_daily_quota_after_delete
AFTER DELETE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_fn_campaigns_sync_daily_quota_on_status_change();

NOTIFY pgrst, 'reload schema';

COMMIT;
