-- ============================================================================
-- MIGRATION 087: CAMPAIGN TESTER BONUS ROUNDS & SETTLEMENT
-- ============================================================================
-- Scope:
-- 1. Tables & Ledgers:
--    - campaign_tester_bonus_rounds: Track bonus rounds, pre-funded escrow budget,
--      recipients, remaining/spent/refunded amounts, status (active/closing/exhausted/closed).
--    - campaign_tester_bonus_awards: Immutable ledger of awards per (round, tester, report).
--    - campaign_tester_bonus_events: Audit trail of financial movements (reserve, payout, refund).
--    - tester_reports: Add immutable tester_bonus_round_id snapshot & tester_bonus_awarded.
--    - campaigns: Add protected summary columns (tester_bonus_round_id, tester_bonus_credits,
--      tester_bonus_remaining_slots, tester_bonus_paid_count, tester_bonus_status).
-- 2. Security & Guard Triggers:
--    - Guard campaigns financial snapshots & bonus summary against direct client writes.
--    - Guard tester_reports bonus snapshot & awarded amount against direct client writes.
--    - RLS on rounds, awards, events (read-only for authorized callers, no direct client DML).
-- 3. APIs:
--    - create_campaign_tester_bonus: Authenticated actual-owner-only create RPC with
--      account/campaign lifecycle checks, atomic pre-fund reserve, idempotency & conflict detection.
--    - reconcile_campaign_tester_bonus: Safe read-only lookup of owner request result.
--    - get_campaign_tester_bonus: Dual projection: detailed financial view for actual owner,
--      clean public summary & own award eligibility view for testers/public.
--    - close_campaign_tester_bonus: Owner RPC to stop taking new applicants & initiate closing.
-- 4. Settlement & Report Lifecycle:
--    - _submit_tester_report_core: Attach active round snapshot ONLY to new app_test reports
--      for non-owner testers while round is active. Preserves resubmit/edit/mission semantics.
--    - _settle_campaign_tester_bonus: Internal helper to settle awards from reserved escrow.
--      One award per distinct tester per round. Serialized last-slot allocation.
--    - Integration into _impl_approve_tester_report (manual approve) and
--      auto_accept_expired_reports (8h auto accept).
--    - Separate accounting for extra bonus, preventing double-counting in bonus_awarded.
-- 5. Lifecycle & Deletion Protection:
--    - _close_and_refund_tester_bonus_round & _process_closing_tester_bonus_rounds.
--    - Hard-delete guard: prevent deletion while pending bonus obligations exist.
--    - Campaign completion/closed trigger transitions active rounds to closing.
-- 6. Projections:
--    - Extend _campaign_json to project bonus summary (invalidates sync v2 block tokens).
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. SCHEMA DEFINITIONS: TABLES, CONSTRAINTS & COLUMNS
-- ============================================================================

-- 1.1 Table: campaign_tester_bonus_rounds
CREATE TABLE IF NOT EXISTS public.campaign_tester_bonus_rounds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id uuid REFERENCES public.campaigns(id) ON DELETE SET NULL,
    original_campaign_id uuid NOT NULL,
    owner_id uuid NOT NULL REFERENCES public.profiles(id),
    app_name text NOT NULL DEFAULT '',
    reward_credits integer NOT NULL CHECK (reward_credits > 0),
    max_recipients integer NOT NULL CHECK (max_recipients > 0),
    paid_count integer NOT NULL DEFAULT 0 CHECK (paid_count >= 0 AND paid_count <= max_recipients),
    reserved_credits integer NOT NULL CHECK (reserved_credits >= 0),
    remaining_credits integer NOT NULL CHECK (remaining_credits >= 0),
    spent_credits integer NOT NULL DEFAULT 0 CHECK (spent_credits >= 0),
    refunded_credits integer NOT NULL DEFAULT 0 CHECK (refunded_credits >= 0),
    status text NOT NULL CHECK (status IN ('active', 'closing', 'exhausted', 'closed')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    stopped_at timestamptz,
    closed_at timestamptz,
    client_request_id uuid NOT NULL,
    request_fingerprint text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

    -- Exact budget conservation invariant: reserved = remaining + spent + refunded
    CONSTRAINT chk_tester_bonus_budget_conservation
        CHECK (reserved_credits = (remaining_credits + spent_credits + refunded_credits)),

    -- Spent calculation invariant: spent = reward * paid_count
    CONSTRAINT chk_tester_bonus_spent_invariant
        CHECK (spent_credits = (reward_credits::bigint * paid_count::bigint)),

    -- Reserved calculation invariant: reserved = reward * max_recipients
    CONSTRAINT chk_tester_bonus_reserved_invariant
        CHECK (reserved_credits = (reward_credits::bigint * max_recipients::bigint))
);

-- Indexes for campaign_tester_bonus_rounds
-- At most one active or closing round per campaign:
CREATE UNIQUE INDEX IF NOT EXISTS idx_tester_bonus_rounds_one_active_or_closing
    ON public.campaign_tester_bonus_rounds (campaign_id)
    WHERE (status IN ('active', 'closing') AND campaign_id IS NOT NULL);

-- Strict idempotency by (owner_id, client_request_id):
CREATE UNIQUE INDEX IF NOT EXISTS idx_tester_bonus_rounds_owner_client_request
    ON public.campaign_tester_bonus_rounds (owner_id, client_request_id);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_rounds_campaign_status
    ON public.campaign_tester_bonus_rounds (campaign_id, status);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_rounds_orig_campaign
    ON public.campaign_tester_bonus_rounds (original_campaign_id);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_rounds_status
    ON public.campaign_tester_bonus_rounds (status);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_rounds_owner
    ON public.campaign_tester_bonus_rounds (owner_id);


-- 1.2 Table: campaign_tester_bonus_awards
CREATE TABLE IF NOT EXISTS public.campaign_tester_bonus_awards (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    round_id uuid NOT NULL REFERENCES public.campaign_tester_bonus_rounds(id) ON DELETE CASCADE,
    report_id uuid REFERENCES public.tester_reports(id) ON DELETE SET NULL,
    campaign_id uuid REFERENCES public.campaigns(id) ON DELETE SET NULL,
    original_campaign_id uuid,
    owner_id uuid NOT NULL REFERENCES public.profiles(id),
    tester_id uuid NOT NULL REFERENCES public.profiles(id),
    amount integer NOT NULL CHECK (amount > 0),
    award_order integer NOT NULL CHECK (award_order > 0),
    credit_log_id uuid REFERENCES public.credit_logs(id) ON DELETE SET NULL,
    awarded_at timestamptz NOT NULL DEFAULT clock_timestamp(),

    -- Exactly one award per distinct tester per round:
    CONSTRAINT uq_tester_bonus_award_round_tester
        UNIQUE (round_id, tester_id),

    -- Monotonic ordering of awards per round:
    CONSTRAINT uq_tester_bonus_award_round_order
        UNIQUE (round_id, award_order)
);

-- At most one bonus award per report:
CREATE UNIQUE INDEX IF NOT EXISTS idx_tester_bonus_awards_report_unique
    ON public.campaign_tester_bonus_awards (report_id)
    WHERE (report_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_awards_tester
    ON public.campaign_tester_bonus_awards (tester_id);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_awards_owner
    ON public.campaign_tester_bonus_awards (owner_id);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_awards_round
    ON public.campaign_tester_bonus_awards (round_id);


-- 1.3 Table: campaign_tester_bonus_events
CREATE TABLE IF NOT EXISTS public.campaign_tester_bonus_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    round_id uuid NOT NULL REFERENCES public.campaign_tester_bonus_rounds(id) ON DELETE CASCADE,
    campaign_id uuid REFERENCES public.campaigns(id) ON DELETE SET NULL,
    original_campaign_id uuid,
    owner_id uuid NOT NULL REFERENCES public.profiles(id),
    event_type text NOT NULL CHECK (event_type IN ('reserve', 'payout', 'refund')),
    amount integer NOT NULL CHECK (amount >= 0),
    actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
    credit_log_id uuid REFERENCES public.credit_logs(id) ON DELETE SET NULL,
    notes text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_events_round_type
    ON public.campaign_tester_bonus_events (round_id, event_type);

CREATE INDEX IF NOT EXISTS idx_tester_bonus_events_owner
    ON public.campaign_tester_bonus_events (owner_id);


-- 1.4 Extend tester_reports with immutable bonus round snapshot & bonus awarded amount
ALTER TABLE public.tester_reports
    ADD COLUMN IF NOT EXISTS tester_bonus_round_id uuid REFERENCES public.campaign_tester_bonus_rounds(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS tester_bonus_awarded integer NOT NULL DEFAULT 0 CHECK (tester_bonus_awarded >= 0);

CREATE INDEX IF NOT EXISTS idx_tester_reports_tester_bonus_round
    ON public.tester_reports (tester_bonus_round_id)
    WHERE (tester_bonus_round_id IS NOT NULL);


-- 1.5 Extend campaigns with protected summary columns
ALTER TABLE public.campaigns
    ADD COLUMN IF NOT EXISTS tester_bonus_round_id uuid REFERENCES public.campaign_tester_bonus_rounds(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS tester_bonus_credits integer NOT NULL DEFAULT 0 CHECK (tester_bonus_credits >= 0),
    ADD COLUMN IF NOT EXISTS tester_bonus_remaining_slots integer NOT NULL DEFAULT 0 CHECK (tester_bonus_remaining_slots >= 0),
    ADD COLUMN IF NOT EXISTS tester_bonus_paid_count integer NOT NULL DEFAULT 0 CHECK (tester_bonus_paid_count >= 0),
    ADD COLUMN IF NOT EXISTS tester_bonus_status text CHECK (tester_bonus_status IS NULL OR tester_bonus_status IN ('active', 'closing', 'exhausted', 'closed'));


-- ============================================================================
-- 2. SECURITY, RLS & FINANCIAL TAMPER-PROOF TRIGGERS
-- ============================================================================

-- 2.1 Enable RLS on new tables
ALTER TABLE public.campaign_tester_bonus_rounds ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.campaign_tester_bonus_awards ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.campaign_tester_bonus_events ENABLE ROW LEVEL SECURITY;

-- 2.2 RLS Policies for campaign_tester_bonus_rounds (Owner and Admin only, no leak to outsiders)
DROP POLICY IF EXISTS "bonus_rounds_select_authenticated" ON public.campaign_tester_bonus_rounds;
DROP POLICY IF EXISTS "bonus_rounds_select_anon" ON public.campaign_tester_bonus_rounds;
DROP POLICY IF EXISTS "bonus_rounds_select_owner_or_admin" ON public.campaign_tester_bonus_rounds;
CREATE POLICY "bonus_rounds_select_owner_or_admin" ON public.campaign_tester_bonus_rounds
    FOR SELECT TO authenticated
    USING (
        owner_id = auth.uid()
        OR EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid() AND lower(p.role) IN ('admin', 'moderator')
        )
    );

REVOKE ALL ON public.campaign_tester_bonus_rounds FROM PUBLIC, anon;
GRANT SELECT ON public.campaign_tester_bonus_rounds TO authenticated;
GRANT ALL ON public.campaign_tester_bonus_rounds TO service_role;

-- No direct client INSERT/UPDATE/DELETE on campaign_tester_bonus_rounds
-- (Only authorized SECURITY DEFINER RPCs can modify)

-- 2.3 RLS Policies for campaign_tester_bonus_awards
DROP POLICY IF EXISTS "bonus_awards_select_own_or_owner" ON public.campaign_tester_bonus_awards;
CREATE POLICY "bonus_awards_select_own_or_owner" ON public.campaign_tester_bonus_awards
    FOR SELECT TO authenticated
    USING (
        tester_id = auth.uid()
        OR owner_id = auth.uid()
        OR EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid() AND lower(p.role) IN ('admin', 'moderator')
        )
    );

REVOKE ALL ON public.campaign_tester_bonus_awards FROM PUBLIC, anon;
GRANT SELECT ON public.campaign_tester_bonus_awards TO authenticated;
GRANT ALL ON public.campaign_tester_bonus_awards TO service_role;

-- 2.4 RLS Policies for campaign_tester_bonus_events
DROP POLICY IF EXISTS "bonus_events_select_owner" ON public.campaign_tester_bonus_events;
CREATE POLICY "bonus_events_select_owner" ON public.campaign_tester_bonus_events
    FOR SELECT TO authenticated
    USING (
        owner_id = auth.uid()
        OR actor_id = auth.uid()
        OR EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid() AND lower(p.role) IN ('admin', 'moderator')
        )
    );

REVOKE ALL ON public.campaign_tester_bonus_events FROM PUBLIC, anon;
GRANT SELECT ON public.campaign_tester_bonus_events TO authenticated;
GRANT ALL ON public.campaign_tester_bonus_events TO service_role;


-- 2.5 Update campaigns financial snapshots guard trigger to protect tester_bonus columns
CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_financial_snapshots()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO ''
AS $function$
begin
    if current_user not in ('authenticated', 'anon', 'public') then
        perform set_config('app.campaign_client_direct_write', 'false', true);
        return new;
    end if;

    perform set_config('app.campaign_client_direct_write', 'true', true);

    if tg_op = 'INSERT' then
        if coalesce(new.slot_1_fee_paid, 0) <> 0
           or coalesce(new.target_fee_paid, 0) <> 0
           or new.creation_policy_id is not null
           or coalesce(new.status, 'open') <> 'open'
           or coalesce(new.tip_credits, 0) <> 0
           or coalesce(new.is_boosted, false) <> false
           or new.tester_bonus_round_id is not null
           or coalesce(new.tester_bonus_credits, 0) <> 0
           or coalesce(new.tester_bonus_remaining_slots, 0) <> 0
           or coalesce(new.tester_bonus_paid_count, 0) <> 0
           or new.tester_bonus_status is not null
        then
            raise exception 'PERMISSION_DENIED';
        end if;
        return new;
    end if;

    if new.slot_1_fee_paid is distinct from old.slot_1_fee_paid
       or new.target_fee_paid is distinct from old.target_fee_paid
       or new.creation_policy_id is distinct from old.creation_policy_id
    then
        raise exception 'PERMISSION_DENIED: FINANCIAL_SNAPSHOT_IMMUTABLE';
    end if;

    if new.slot_number is distinct from old.slot_number then
        raise exception 'PERMISSION_DENIED: SLOT_NUMBER_IMMUTABLE';
    end if;

    if new.target_testers is distinct from old.target_testers then
        raise exception 'PERMISSION_DENIED: TARGET_TESTERS_IMMUTABLE';
    end if;

    if new.package_name is distinct from old.package_name then
        raise exception 'PERMISSION_DENIED: PACKAGE_NAME_IMMUTABLE';
    end if;

    if new.owner_id is distinct from old.owner_id then
        raise exception 'PERMISSION_DENIED: OWNER_ID_IMMUTABLE';
    end if;

    if new.start_date is distinct from old.start_date
       or new.target_days is distinct from old.target_days
       or new.auto_accept_hours is distinct from old.auto_accept_hours
       or new.is_boosted is distinct from old.is_boosted
       or new.tip_credits is distinct from old.tip_credits
    then
        raise exception 'PERMISSION_DENIED: CAMPAIGN_CORE_CONFIG_IMMUTABLE';
    end if;

    -- Protect tester bonus summary columns against direct client tampering
    if new.tester_bonus_round_id is distinct from old.tester_bonus_round_id
       or new.tester_bonus_credits is distinct from old.tester_bonus_credits
       or new.tester_bonus_remaining_slots is distinct from old.tester_bonus_remaining_slots
       or new.tester_bonus_paid_count is distinct from old.tester_bonus_paid_count
       or new.tester_bonus_status is distinct from old.tester_bonus_status
    then
        raise exception 'PERMISSION_DENIED: TESTER_BONUS_SUMMARY_IMMUTABLE';
    end if;

    return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_financial_snapshots ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_financial_snapshots
    BEFORE INSERT OR UPDATE ON public.campaigns
    FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_financial_snapshots();


-- 2.6 Update tester_reports financial metadata guard trigger
CREATE OR REPLACE FUNCTION public.guard_tester_report_financial_metadata()
RETURNS trigger
LANGUAGE plpgsql
AS $$
begin
    if current_user in ('anon', 'authenticated') then
        if tg_op = 'INSERT' then
            if new.review_tester_bonus is not null or
               new.review_owner_bonus is not null or
               new.business_policy_id is not null or
               coalesce(new.provisional_granted_credits, 0) <> 0 or
               coalesce(new.provisional_clawed, 0) <> 0 or
               coalesce(new.bonus_awarded, 0) <> 0 or
               new.tester_bonus_round_id is not null or
               coalesce(new.tester_bonus_awarded, 0) <> 0 then
                raise exception 'FORBIDDEN_REPORT_METADATA_WRITE: Chi duoc tao bao cao qua RPC nop bao cao';
            end if;
        elsif tg_op = 'UPDATE' then
            if new.review_tester_bonus is distinct from old.review_tester_bonus or
               new.review_owner_bonus is distinct from old.review_owner_bonus or
               new.business_policy_id is distinct from old.business_policy_id or
               new.provisional_granted_credits is distinct from old.provisional_granted_credits or
               new.provisional_clawed is distinct from old.provisional_clawed or
               new.bonus_awarded is distinct from old.bonus_awarded or
               new.tester_bonus_round_id is distinct from old.tester_bonus_round_id or
               new.tester_bonus_awarded is distinct from old.tester_bonus_awarded then
                raise exception 'FORBIDDEN_REPORT_METADATA_UPDATE: Khong duoc phep sua doi thong so tai chinh cua bao cao';
            end if;
        end if;
    end if;

    -- Strict immutability of report's bonus snapshot once attached
    if tg_op = 'UPDATE' and old.tester_bonus_round_id is not null and new.tester_bonus_round_id is distinct from old.tester_bonus_round_id then
        raise exception 'FORBIDDEN: TESTER_BONUS_ROUND_ID_IMMUTABLE';
    end if;

    return new;
end;
$$;

DROP TRIGGER IF EXISTS trg_guard_tester_report_financial_metadata ON public.tester_reports;
CREATE TRIGGER trg_guard_tester_report_financial_metadata
    BEFORE INSERT OR UPDATE ON public.tester_reports
    FOR EACH ROW EXECUTE FUNCTION public.guard_tester_report_financial_metadata();


-- ============================================================================
-- 3. CAMPAIGN JSON PROJECTION EXTENSION
-- ============================================================================

CREATE OR REPLACE FUNCTION public._campaign_json(c campaigns)
RETURNS jsonb
LANGUAGE sql
STABLE
AS $function$
    select jsonb_build_object(
        'id', c.id,
        'owner_id', c.owner_id,
        'app_name', c.app_name,
        'package_name', c.package_name,
        'play_url', c.play_url,
        'testing_url', c.testing_url,
        'description', c.description,
        'target_testers', c.target_testers,
        'target_days', c.target_days,
        'start_date', to_char(c.start_date, 'YYYY-MM-DD'),
        'status', c.status,
        'icon_url', c.icon_url,
        'test_username', c.test_username,
        'test_password', c.test_password,
        'tip_credits', c.tip_credits,
        'is_boosted', c.is_boosted,
        'slot_number', c.slot_number,
        'paused_reason', c.paused_reason,
        'admin_note', c.admin_note,
        'completed_at', to_char(c.completed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(c.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_business_date', to_char(c.push_business_date, 'YYYY-MM-DD'),
        'push_expires_at', to_char(c.push_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_order', c.push_order,
        'tester_bonus_round_id', c.tester_bonus_round_id,
        'tester_bonus_credits', coalesce(c.tester_bonus_credits, 0),
        'tester_bonus_remaining_slots', coalesce(c.tester_bonus_remaining_slots, 0),
        'tester_bonus_paid_count', coalesce(c.tester_bonus_paid_count, 0),
        'tester_bonus_status', c.tester_bonus_status
    );
$function$;

REVOKE ALL ON FUNCTION public._campaign_json(campaigns) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public._campaign_json(campaigns) TO anon, authenticated, service_role;


-- ============================================================================
-- 4. CREATE CAMPAIGN TESTER BONUS RPC
-- ============================================================================

CREATE OR REPLACE FUNCTION public.create_campaign_tester_bonus(
    p_campaign_id uuid,
    p_reward_credits integer,
    p_max_recipients integer,
    p_client_request_id uuid
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
    v_fingerprint text;
    v_existing_round record;
    v_camp record;
    v_total_budget_bigint bigint;
    v_total_budget integer;
    v_owner_balance integer;
    v_new_balance integer;
    v_round_id uuid;
    v_credit_log_id uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    if p_client_request_id is null then
        raise exception 'CLIENT_REQUEST_ID_REQUIRED';
    end if;

    -- Validate integer inputs & prevent overflow
    if p_reward_credits is null or p_reward_credits <= 0 then
        raise exception 'INVALID_INPUT: Mức thưởng phải là số nguyên dương';
    end if;

    if p_max_recipients is null or p_max_recipients <= 0 then
        raise exception 'INVALID_INPUT: Số người nhận phải là số nguyên dương';
    end if;

    v_total_budget_bigint := (p_reward_credits::bigint * p_max_recipients::bigint);
    if v_total_budget_bigint > 2147483647 or v_total_budget_bigint <= 0 then
        raise exception 'INPUT_OVERFLOW: Tổng ngân sách vượt quá giới hạn hệ thống';
    end if;
    v_total_budget := v_total_budget_bigint::integer;

    -- Compute payload fingerprint for conflict detection
    v_fingerprint := encode(digest(format('%s|%s|%s', p_campaign_id, p_reward_credits, p_max_recipients), 'sha256'), 'hex');

    -- Level 1: Advisory transaction lock per (owner_id, client_request_id)
    perform pg_advisory_xact_lock(hashtextextended(v_caller_id::text || ':' || p_client_request_id::text, 64));

    -- Check for existing round with same client_request_id (Idempotency Replay check)
    -- Must be recognized BEFORE checking creation-only conditions (such as account locked or campaign closed)
    select * into v_existing_round
    from public.campaign_tester_bonus_rounds
    where owner_id = v_caller_id
      and client_request_id = p_client_request_id;

    if v_existing_round.id is not null then
        if v_existing_round.request_fingerprint = v_fingerprint then
            select credits into v_owner_balance
            from public.profiles
            where id = v_caller_id;

            return jsonb_build_object(
                'success', true,
                'round_id', v_existing_round.id,
                'client_request_id', v_existing_round.client_request_id,
                'campaign_id', coalesce(v_existing_round.original_campaign_id, v_existing_round.campaign_id),
                'reward_credits', v_existing_round.reward_credits,
                'max_recipients', v_existing_round.max_recipients,
                'paid_count', v_existing_round.paid_count,
                'remaining_slots', greatest(0, v_existing_round.max_recipients - v_existing_round.paid_count),
                'reserved_credits', v_existing_round.reserved_credits,
                'remaining_credits', v_existing_round.remaining_credits,
                'spent_credits', v_existing_round.spent_credits,
                'refunded_credits', v_existing_round.refunded_credits,
                'owner_balance', v_owner_balance,
                'status', v_existing_round.status,
                'replayed', true
            );
        else
            raise exception 'CLIENT_REQUEST_ID_CONFLICT: Request ID đã được sử dụng với thông số khác';
        end if;
    end if;

    -- Creation-only checks: Account status check for brand new round creation
    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if v_caller_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    -- Level 4: Lock campaign row
    select * into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Strict Actual-Owner Authorization:
    -- An admin who is not the actual owner cannot spend the owner's credits!
    if v_camp.owner_id <> v_caller_id then
        raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới được tạo đợt thưởng';
    end if;

    -- Campaign lifecycle check: only open campaigns qualify to launch new bonus round
    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_ELIGIBLE: Chỉ ứng dụng đang mở (open) mới được tạo đợt thưởng';
    end if;

    -- At most one active or closing round per campaign:
    if exists (
        select 1 from public.campaign_tester_bonus_rounds
        where campaign_id = p_campaign_id
          and status in ('active', 'closing')
    ) then
        raise exception 'ACTIVE_ROUND_ALREADY_EXISTS: Ứng dụng đã có đợt thưởng đang hoạt động hoặc đang chờ kết toán';
    end if;

    -- Level 5: Lock owner profile row (Serializes concurrent creates across multiple apps of same owner)
    select credits into v_owner_balance
    from public.profiles
    where id = v_caller_id
    for update;

    if coalesce(v_owner_balance, 0) < v_total_budget then
        raise exception 'INSUFFICIENT_CREDITS: Không đủ Credit để tạo đợt thưởng (cần %s, hiện có %s)', v_total_budget, coalesce(v_owner_balance, 0);
    end if;

    -- Deduct full prepayment budget from owner wallet atomically
    update public.profiles
    set credits = credits - v_total_budget
    where id = v_caller_id
    returning credits into v_new_balance;

    -- Insert credit log (Level 6)
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_caller_id,
        -v_total_budget,
        'tester_bonus_reserve',
        format('Giữ trước ngân sách thưởng tester (+%s Credit x %s người = %s Credit) cho ứng dụng "%s"',
            p_reward_credits, p_max_recipients, v_total_budget, coalesce(v_camp.app_name, 'ứng dụng')),
        v_new_balance
    ) returning id into v_credit_log_id;

    -- Insert round record (Level 3)
    insert into public.campaign_tester_bonus_rounds (
        campaign_id, original_campaign_id, owner_id, app_name, reward_credits, max_recipients, paid_count,
        reserved_credits, remaining_credits, spent_credits, refunded_credits,
        status, started_at, client_request_id, request_fingerprint
    ) values (
        p_campaign_id, p_campaign_id, v_caller_id, coalesce(v_camp.app_name, ''), p_reward_credits, p_max_recipients, 0,
        v_total_budget, v_total_budget, 0, 0,
        'active', clock_timestamp(), p_client_request_id, v_fingerprint
    ) returning id into v_round_id;

    -- Insert financial event (Level 6)
    insert into public.campaign_tester_bonus_events (
        round_id, campaign_id, original_campaign_id, owner_id, event_type, amount, actor_id, credit_log_id, notes, created_at
    ) values (
        v_round_id, p_campaign_id, p_campaign_id, v_caller_id, 'reserve', v_total_budget, v_caller_id, v_credit_log_id,
        format('Tạo đợt thưởng mới: +%s Credit cho %s người (ngân sách %s Credit)', p_reward_credits, p_max_recipients, v_total_budget),
        clock_timestamp()
    );

    -- Update campaigns summary columns (Level 4)
    update public.campaigns
    set tester_bonus_round_id = v_round_id,
        tester_bonus_credits = p_reward_credits,
        tester_bonus_remaining_slots = p_max_recipients,
        tester_bonus_paid_count = 0,
        tester_bonus_status = 'active'
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'round_id', v_round_id,
        'client_request_id', p_client_request_id,
        'campaign_id', p_campaign_id,
        'reward_credits', p_reward_credits,
        'max_recipients', p_max_recipients,
        'paid_count', 0,
        'remaining_slots', p_max_recipients,
        'reserved_credits', v_total_budget,
        'remaining_credits', v_total_budget,
        'spent_credits', 0,
        'refunded_credits', 0,
        'owner_balance', v_new_balance,
        'status', 'active',
        'replayed', false
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.create_campaign_tester_bonus(uuid, integer, integer, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.create_campaign_tester_bonus(uuid, integer, integer, uuid) TO authenticated, service_role;


-- ============================================================================
-- 5. RECONCILE CAMPAIGN TESTER BONUS RPC
-- ============================================================================

CREATE OR REPLACE FUNCTION public.reconcile_campaign_tester_bonus(
    p_campaign_id uuid,
    p_client_request_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_round record;
    v_owner_balance integer;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'CLIENT_REQUEST_ID_REQUIRED';
    end if;

    -- Strict read-only lookup of owner request result. Never creates a request!
    select * into v_round
    from public.campaign_tester_bonus_rounds
    where owner_id = v_caller_id
      and client_request_id = p_client_request_id;

    if v_round.id is null then
        return jsonb_build_object(
            'found', false,
            'client_request_id', p_client_request_id
        );
    end if;

    -- If caller specified a campaign_id, verify it matches either current campaign_id or original_campaign_id
    if p_campaign_id is not null and
       p_campaign_id is distinct from v_round.campaign_id and
       p_campaign_id is distinct from v_round.original_campaign_id then
        return jsonb_build_object(
            'found', false,
            'client_request_id', p_client_request_id
        );
    end if;

    select credits into v_owner_balance
    from public.profiles
    where id = v_caller_id;

    return jsonb_build_object(
        'found', true,
        'replayed', true,
        'round_id', v_round.id,
        'client_request_id', v_round.client_request_id,
        'campaign_id', coalesce(v_round.original_campaign_id, v_round.campaign_id),
        'reward_credits', v_round.reward_credits,
        'max_recipients', v_round.max_recipients,
        'paid_count', v_round.paid_count,
        'remaining_slots', greatest(0, v_round.max_recipients - v_round.paid_count),
        'reserved_credits', v_round.reserved_credits,
        'remaining_credits', v_round.remaining_credits,
        'spent_credits', v_round.spent_credits,
        'refunded_credits', v_round.refunded_credits,
        'owner_balance', v_owner_balance,
        'status', v_round.status
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.reconcile_campaign_tester_bonus(uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reconcile_campaign_tester_bonus(uuid, uuid) TO authenticated, service_role;


-- ============================================================================
-- 6. GET CAMPAIGN TESTER BONUS RPC (DUAL PROJECTION)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_campaign_tester_bonus(
    p_campaign_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_is_owner boolean := false;
    v_round record;
    v_my_award record;
    v_my_pending boolean := false;
begin
    if p_campaign_id is null then
        return jsonb_build_object('has_round', false, 'error', 'CAMPAIGN_ID_REQUIRED');
    end if;

    select id, owner_id, app_name, status into v_camp
    from public.campaigns
    where id = p_campaign_id;

    if v_camp.id is null then
        return jsonb_build_object('has_round', false, 'error', 'CAMPAIGN_NOT_FOUND');
    end if;

    v_is_owner := (v_caller_id is not null and v_camp.owner_id = v_caller_id);

    -- Find current or most recent round for this campaign
    select * into v_round
    from public.campaign_tester_bonus_rounds
    where campaign_id = p_campaign_id
    order by (case when status in ('active', 'closing') then 0 else 1 end), started_at desc
    limit 1;

    if v_round.id is null then
        return jsonb_build_object(
            'has_round', false,
            'campaign_id', p_campaign_id,
            'is_owner', v_is_owner
        );
    end if;

    -- Owner View: full administrative and financial status
    if v_is_owner then
        return jsonb_build_object(
            'has_round', true,
            'round_id', v_round.id,
            'campaign_id', p_campaign_id,
            'reward_credits', v_round.reward_credits,
            'max_recipients', v_round.max_recipients,
            'paid_count', v_round.paid_count,
            'remaining_slots', greatest(0, v_round.max_recipients - v_round.paid_count),
            'reserved_credits', v_round.reserved_credits,
            'remaining_credits', v_round.remaining_credits,
            'spent_credits', v_round.spent_credits,
            'refunded_credits', v_round.refunded_credits,
            'status', v_round.status,
            'started_at', to_char(v_round.started_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'stopped_at', case when v_round.stopped_at is not null then to_char(v_round.stopped_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') else null end,
            'closed_at', case when v_round.closed_at is not null then to_char(v_round.closed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') else null end,
            'is_owner', true
        );
    end if;

    -- Tester / Public View: summary & own award eligibility (no owner wallet or private ledger rows)
    if v_caller_id is not null then
        select * into v_my_award
        from public.campaign_tester_bonus_awards
        where round_id = v_round.id
          and tester_id = v_caller_id;

        select exists (
            select 1 from public.tester_reports
            where tester_bonus_round_id = v_round.id
              and user_id = v_caller_id
              and status = 'pending'
        ) into v_my_pending;
    end if;

    return jsonb_build_object(
        'has_round', (v_round.status in ('active', 'closing')),
        'round_id', v_round.id,
        'campaign_id', p_campaign_id,
        'reward_credits', coalesce(v_round.reward_credits, 0),
        'max_recipients', coalesce(v_round.max_recipients, 0),
        'paid_count', coalesce(v_round.paid_count, 0),
        'remaining_slots', greatest(0, coalesce(v_round.max_recipients, 0) - coalesce(v_round.paid_count, 0)),
        'status', coalesce(v_round.status, 'none'),
        'is_owner', false,
        'my_awarded', (v_my_award.id is not null),
        'my_award_amount', coalesce(v_my_award.amount, 0),
        'my_awarded_at', case when v_my_award.awarded_at is not null then to_char(v_my_award.awarded_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') else null end,
        'my_pending_eligible', coalesce(v_my_pending, false)
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_tester_bonus(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_campaign_tester_bonus(uuid) TO anon, authenticated, service_role;


-- ============================================================================
-- 7. INTERNAL SETTLEMENT HELPER: _settle_campaign_tester_bonus
-- ============================================================================

CREATE OR REPLACE FUNCTION public._settle_campaign_tester_bonus(
    p_report_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_report record;
    v_round record;
    v_existing_award record;
    v_new_paid integer;
    v_new_spent integer;
    v_new_remaining integer;
    v_new_status text;
    v_tester_balance integer;
    v_credit_log_id uuid;
begin
    if p_report_id is null then
        return jsonb_build_object('settled', false, 'reason', 'NULL_REPORT_ID');
    end if;

    -- Level 2: Lock report row
    select * into v_report
    from public.tester_reports
    where id = p_report_id
    for update;

    if v_report.id is null then
        return jsonb_build_object('settled', false, 'reason', 'REPORT_NOT_FOUND');
    end if;

    -- Only approved regular app_test reports with an attached bonus round snapshot qualify
    if v_report.status <> 'approved' then
        return jsonb_build_object('settled', false, 'reason', 'REPORT_NOT_APPROVED');
    end if;

    if coalesce(v_report.submission_context, 'app_test') <> 'app_test' then
        return jsonb_build_object('settled', false, 'reason', 'NOT_APP_TEST_CONTEXT');
    end if;

    if v_report.tester_bonus_round_id is null then
        return jsonb_build_object('settled', false, 'reason', 'NO_BONUS_ROUND_SNAPSHOT');
    end if;

    -- Check if award was already granted for this report (Idempotency)
    select * into v_existing_award
    from public.campaign_tester_bonus_awards
    where report_id = p_report_id;

    if v_existing_award.id is not null then
        return jsonb_build_object(
            'settled', true,
            'replayed', true,
            'reward_credits', v_existing_award.amount,
            'award_order', v_existing_award.award_order
        );
    end if;

    -- Level 3: Lock the round row to serialize award slot allocation
    select * into v_round
    from public.campaign_tester_bonus_rounds
    where id = v_report.tester_bonus_round_id
    for update;

    if v_round.id is null then
        return jsonb_build_object('settled', false, 'reason', 'ROUND_NOT_FOUND');
    end if;

    -- Check if this tester already received an award in this round (Strict 1 award per tester per round)
    if exists (
        select 1 from public.campaign_tester_bonus_awards
        where round_id = v_round.id
          and tester_id = v_report.user_id
    ) then
        return jsonb_build_object('settled', false, 'reason', 'ALREADY_AWARDED_IN_ROUND');
    end if;

    -- Check if round is full or no remaining budget
    if v_round.status not in ('active', 'closing')
       or v_round.paid_count >= v_round.max_recipients
       or v_round.remaining_credits < v_round.reward_credits
    then
        -- If active round became exhausted, record status update
        if v_round.status = 'active' and v_round.paid_count >= v_round.max_recipients then
            update public.campaign_tester_bonus_rounds
            set status = 'exhausted'
            where id = v_round.id;

            update public.campaigns
            set tester_bonus_remaining_slots = 0,
                tester_bonus_status = 'exhausted'
            where id = v_round.campaign_id;
        end if;
        return jsonb_build_object('settled', false, 'reason', 'ROUND_EXHAUSTED_OR_CLOSED');
    end if;

    -- Calculate new counters
    v_new_paid := v_round.paid_count + 1;
    v_new_spent := v_round.spent_credits + v_round.reward_credits;
    v_new_remaining := v_round.remaining_credits - v_round.reward_credits;
    v_new_status := case
        when v_new_paid >= v_round.max_recipients then
            case when v_round.status = 'closing' then 'closed' else 'exhausted' end
        else v_round.status
    end;

    -- Update round escrow counters (Level 3)
    update public.campaign_tester_bonus_rounds
    set paid_count = v_new_paid,
        spent_credits = v_new_spent,
        remaining_credits = v_new_remaining,
        status = v_new_status,
        closed_at = case when v_new_status = 'closed' then coalesce(closed_at, clock_timestamp()) else closed_at end
    where id = v_round.id;

    -- Update campaigns summary columns (Level 4)
    if v_round.campaign_id is not null then
        update public.campaigns
        set tester_bonus_paid_count = v_new_paid,
            tester_bonus_remaining_slots = greatest(0, v_round.max_recipients - v_new_paid),
            tester_bonus_status = v_new_status
        where id = v_round.campaign_id;
    end if;

    -- Credit tester profile (Level 5) with integer overflow guard
    select credits into v_tester_balance
    from public.profiles
    where id = v_report.user_id
    for update;

    if (v_tester_balance::bigint + v_round.reward_credits::bigint) > 2147483647 then
        return jsonb_build_object('settled', false, 'reason', 'TESTER_WALLET_OVERFLOW');
    end if;

    update public.profiles
    set credits = credits + v_round.reward_credits
    where id = v_report.user_id
    returning credits into v_tester_balance;

    -- Insert credit log (Level 6)
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_round.reward_credits,
        'tester_bonus_reward',
        format('Thưởng thêm tester (+%s Credit) từ ứng dụng "%s"', v_round.reward_credits, coalesce(v_round.app_name, 'ứng dụng')),
        v_tester_balance
    ) returning id into v_credit_log_id;

    -- Insert award record (Level 6)
    insert into public.campaign_tester_bonus_awards (
        round_id, report_id, campaign_id, original_campaign_id, owner_id, tester_id, amount, award_order, credit_log_id, awarded_at
    ) values (
        v_round.id, v_report.id, v_round.campaign_id, coalesce(v_round.original_campaign_id, v_round.campaign_id), v_round.owner_id, v_report.user_id, v_round.reward_credits, v_new_paid, v_credit_log_id, clock_timestamp()
    );

    -- Insert financial event (Level 6)
    insert into public.campaign_tester_bonus_events (
        round_id, campaign_id, original_campaign_id, owner_id, event_type, amount, actor_id, credit_log_id, notes, created_at
    ) values (
        v_round.id, v_round.campaign_id, coalesce(v_round.original_campaign_id, v_round.campaign_id), v_round.owner_id, 'payout', v_round.reward_credits, v_report.user_id, v_credit_log_id,
        format('Cấp thưởng thêm cho tester %s (thứ tự %s/%s)', v_report.user_id, v_new_paid, v_round.max_recipients),
        clock_timestamp()
    );

    -- Record bonus awarded on report record
    update public.tester_reports
    set tester_bonus_awarded = v_round.reward_credits
    where id = v_report.id;

    -- Notify tester (Level 6)
    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_report.user_id,
        'Nhận thưởng thêm tester 🎉',
        format('Báo cáo của bạn cho ứng dụng "%s" đã nhận thêm %s Credit từ đợt thưởng của chủ app!', coalesce(v_round.app_name, 'ứng dụng'), v_round.reward_credits),
        'tester_bonus',
        jsonb_build_object(
            'round_id', v_round.id,
            'campaign_id', v_round.campaign_id,
            'report_id', v_report.id,
            'reward_credits', v_round.reward_credits,
            'award_order', v_new_paid
        )
    );

    return jsonb_build_object(
        'settled', true,
        'replayed', false,
        'round_id', v_round.id,
        'reward_credits', v_round.reward_credits,
        'award_order', v_new_paid,
        'tester_balance', v_tester_balance
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._settle_campaign_tester_bonus(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._settle_campaign_tester_bonus(uuid) TO service_role;


-- ============================================================================
-- 8. CLOSING & ESCROW REFUND HELPER
-- ============================================================================

CREATE OR REPLACE FUNCTION public._close_and_refund_tester_bonus_round(
    p_round_id uuid,
    p_reason text DEFAULT 'round_closed'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_camp_id uuid;
    v_round record;
    v_pending_count integer := 0;
    v_refund integer := 0;
    v_owner_balance integer;
    v_credit_log_id uuid;
begin
    if p_round_id is null then
        return jsonb_build_object('closed', false, 'reason', 'NULL_ROUND_ID');
    end if;

    -- Level 3: Lock campaign row first to maintain strict lock hierarchy
    select campaign_id into v_camp_id
    from public.campaign_tester_bonus_rounds
    where id = p_round_id;

    if v_camp_id is not null then
        perform 1 from public.campaigns where id = v_camp_id for update;
    end if;

    -- Level 4: Lock round row
    select * into v_round
    from public.campaign_tester_bonus_rounds
    where id = p_round_id
    for update;

    if v_round.id is null then
        return jsonb_build_object('closed', false, 'reason', 'ROUND_NOT_FOUND');
    end if;

    if v_round.status = 'closed' then
        return jsonb_build_object('closed', true, 'status', 'closed', 'refund_amount', 0, 'reason', 'ALREADY_CLOSED');
    end if;

    -- Count pending reports attached to this round snapshot
    -- (Uses MVCC count without row locks to prevent deadlock with approve/reject)
    select count(*) into v_pending_count
    from public.tester_reports
    where tester_bonus_round_id = v_round.id
      and status = 'pending';

    -- If there are pending reports, we MUST NOT refund yet!
    -- Transition round to 'closing' to stop any new submissions from acquiring this round snapshot.
    if v_pending_count > 0 then
        if v_round.status = 'active' then
            update public.campaign_tester_bonus_rounds
            set status = 'closing',
                stopped_at = coalesce(stopped_at, clock_timestamp())
            where id = v_round.id;

            update public.campaigns
            set tester_bonus_status = 'closing'
            where id = v_round.campaign_id;
        end if;

        return jsonb_build_object(
            'closed', false,
            'status', 'closing',
            'pending_reports', v_pending_count,
            'reason', 'PENDING_REPORTS_REMAIN'
        );
    end if;

    -- Pending count is 0: all obligations are resolved, finalize closing and refund unused escrow
    v_refund := v_round.remaining_credits;

    if v_refund > 0 then
        -- Level 5: Credit owner wallet with integer overflow guard under FOR UPDATE lock
        select credits into v_owner_balance
        from public.profiles
        where id = v_round.owner_id
        for update;

        if (v_owner_balance::bigint + v_refund::bigint) > 2147483647 then
            -- Integer overflow: preserve escrow, transition to closing status, return retryable status
            if v_round.status = 'active' then
                update public.campaign_tester_bonus_rounds
                set status = 'closing',
                    stopped_at = coalesce(stopped_at, clock_timestamp())
                where id = v_round.id;

                if v_round.campaign_id is not null then
                    update public.campaigns
                    set tester_bonus_status = 'closing'
                    where id = v_round.campaign_id;
                end if;
            end if;

            return jsonb_build_object(
                'closed', false,
                'status', 'closing',
                'refund_amount', 0,
                'reason', 'WALLET_BALANCE_OVERFLOW'
            );
        end if;

        update public.profiles
        set credits = credits + v_refund
        where id = v_round.owner_id
        returning credits into v_owner_balance;

        -- Level 6: Credit log
        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_round.owner_id,
            v_refund,
            'tester_bonus_refund',
            format('Hoàn ngân sách thưởng thêm tester (+%s Credit) từ ứng dụng "%s"', v_refund, coalesce(v_round.app_name, 'ứng dụng')),
            v_owner_balance
        ) returning id into v_credit_log_id;

        -- Level 6: Event
        insert into public.campaign_tester_bonus_events (
            round_id, campaign_id, original_campaign_id, owner_id, event_type, amount, actor_id, credit_log_id, notes, created_at
        ) values (
            v_round.id, v_round.campaign_id, coalesce(v_round.original_campaign_id, v_round.campaign_id), v_round.owner_id, 'refund', v_refund, v_round.owner_id, v_credit_log_id,
            format('Hoàn lại ngân sách chưa sử dụng: %s Credit (lý do: %s)', v_refund, coalesce(p_reason, 'round_closed')),
            clock_timestamp()
        );

        -- Level 6: Notification
        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_round.owner_id,
            'Hoàn ngân sách thưởng tester 💰',
            format('Đợt thưởng thêm cho ứng dụng "%s" đã kết thúc. Bạn nhận lại %s Credit từ ngân sách còn dư.', coalesce(v_round.app_name, 'ứng dụng'), v_refund),
            'tester_bonus',
            jsonb_build_object('round_id', v_round.id, 'campaign_id', v_round.campaign_id, 'refund_credits', v_refund)
        );
    else
        select credits into v_owner_balance from public.profiles where id = v_round.owner_id;
    end if;

    -- Update round to closed (Level 4)
    update public.campaign_tester_bonus_rounds
    set refunded_credits = refunded_credits + remaining_credits,
        remaining_credits = 0,
        status = 'closed',
        stopped_at = coalesce(stopped_at, clock_timestamp()),
        closed_at = coalesce(closed_at, clock_timestamp())
    where id = v_round.id;

    -- Clear campaign summary columns (Level 3) if campaign exists
    if v_round.campaign_id is not null then
        update public.campaigns
        set tester_bonus_round_id = null,
            tester_bonus_credits = 0,
            tester_bonus_remaining_slots = 0,
            tester_bonus_paid_count = 0,
            tester_bonus_status = 'closed'
        where id = v_round.campaign_id;
    end if;

    return jsonb_build_object(
        'closed', true,
        'status', 'closed',
        'round_id', v_round.id,
        'refund_amount', v_refund,
        'owner_balance', v_owner_balance
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._close_and_refund_tester_bonus_round(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._close_and_refund_tester_bonus_round(uuid, text) TO service_role;


-- 8.2 Periodic batch closing processor for closing rounds & durable admin deletion
CREATE OR REPLACE FUNCTION public._process_closing_tester_bonus_rounds()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_camp record;
    v_round record;
    v_closed_count integer := 0;
    v_has_pending_reports boolean;
    v_has_unsettled_bonus boolean;
    v_has_unsettled_missions boolean;
begin
    -- =========================================================================
    -- PART 1: Process campaigns that have closing bonus rounds OR pending deletion.
    -- Strict lock hierarchy: Campaign row is locked FOR UPDATE OF c SKIP LOCKED FIRST.
    -- This strictly eliminates any deadlock cycle between batch closing and lifecycle/delete.
    -- =========================================================================
    for v_camp in
        select c.id, c.status, c.paused_reason, c.admin_note
        from public.campaigns c
        where c.id in (
            select campaign_id from public.campaign_tester_bonus_rounds
            where status in ('active', 'closing') and campaign_id is not null
            union
            select id from public.campaigns
            where status = 'closed' and paused_reason in ('admin_deletion_pending', 'owner_deletion_pending')
        )
        order by c.id
        for update of c skip locked
    loop
        -- 1. If campaign is pending deletion (admin or owner), ensure any active bonus rounds transition to closing
        if v_camp.status = 'closed' and v_camp.paused_reason in ('admin_deletion_pending', 'owner_deletion_pending') then
            update public.campaign_tester_bonus_rounds
            set status = 'closing',
                stopped_at = coalesce(stopped_at, clock_timestamp())
            where campaign_id = v_camp.id
              and status = 'active';
        end if;

        -- 2. Process closing rounds for this locked campaign (Level 4: Round lock under Campaign lock)
        for v_round in
            select r.id, r.remaining_credits
            from public.campaign_tester_bonus_rounds r
            where r.campaign_id = v_camp.id
              and r.status = 'closing'
            order by r.created_at
            for update of r
        loop
            if not exists (
                select 1 from public.tester_reports
                where tester_bonus_round_id = v_round.id
                  and status = 'pending'
            ) then
                declare
                    v_close_res jsonb;
                begin
                    v_close_res := public._close_and_refund_tester_bonus_round(
                        v_round.id,
                        case
                            when v_camp.paused_reason = 'admin_deletion_pending' then 'admin_delete_settled'
                            when v_camp.paused_reason = 'owner_deletion_pending' then 'owner_delete_settled'
                            else 'closing_batch_settled'
                        end
                    );
                    if coalesce((v_close_res->>'closed')::boolean, false) then
                        v_closed_count := v_closed_count + 1;
                    end if;
                end;
            end if;
        end loop;

        -- 3. Durable deferred deletion completion (admin or owner):
        -- Completes independently of round status (handles both full payout where round is already
        -- 'closed' / remaining_credits=0, and partial payout with unused escrow refund).
        if v_camp.status = 'closed' and v_camp.paused_reason in ('admin_deletion_pending', 'owner_deletion_pending') then
            -- Check if any pending reports remain on this campaign
            select exists (
                select 1 from public.tester_reports
                where campaign_id = v_camp.id
                  and status = 'pending'
            ) into v_has_pending_reports;

            -- Check if any unsettled bonus rounds remain with unrefunded credits
            select exists (
                select 1 from public.campaign_tester_bonus_rounds
                where campaign_id = v_camp.id
                  and status in ('active', 'closing')
                  and remaining_credits > 0
            ) into v_has_unsettled_bonus;

            -- Check if any unsettled missions remain
            select exists (
                select 1 from public.missions
                where campaign_id = v_camp.id
                  and (status <> 'deleted' or (escrow_remaining_credits > 0 and not escrow_refunded))
            ) into v_has_unsettled_missions;

            -- When all pending obligations are resolved, durable delete completes removal
            if not v_has_pending_reports and not v_has_unsettled_bonus and not v_has_unsettled_missions then
                delete from public.campaigns where id = v_camp.id;
            end if;
        end if;
    end loop;

    -- =========================================================================
    -- PART 2: Handle any orphan closing rounds where campaign_id IS NULL
    -- (Financial ledgers preserved after campaign deletion with ON DELETE SET NULL)
    -- =========================================================================
    for v_round in
        select r.id
        from public.campaign_tester_bonus_rounds r
        where r.status = 'closing'
          and r.campaign_id is null
        order by r.created_at
        for update of r skip locked
    loop
        if not exists (
            select 1 from public.tester_reports
            where tester_bonus_round_id = v_round.id
              and status = 'pending'
        ) then
            declare
                v_orphan_res jsonb;
            begin
                v_orphan_res := public._close_and_refund_tester_bonus_round(v_round.id, 'closing_batch_settled');
                if coalesce((v_orphan_res->>'closed')::boolean, false) then
                    v_closed_count := v_closed_count + 1;
                end if;
            end;
        end if;
    end loop;

    return v_closed_count;
end;
$function$;

REVOKE ALL ON FUNCTION public._process_closing_tester_bonus_rounds() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._process_closing_tester_bonus_rounds() TO service_role;


-- 8.3 Owner Public RPC: close_campaign_tester_bonus
CREATE OR REPLACE FUNCTION public.close_campaign_tester_bonus(
    p_campaign_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_round record;
    v_res jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    -- Level 3: Lock campaign row first to maintain strict lock hierarchy
    select id, owner_id into v_camp
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_caller_id then
        raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới được đóng đợt thưởng';
    end if;

    select id into v_round
    from public.campaign_tester_bonus_rounds
    where campaign_id = p_campaign_id
      and status in ('active', 'closing')
    limit 1;

    if v_round.id is null then
        return jsonb_build_object('success', true, 'status', 'no_active_round');
    end if;

    v_res := public._close_and_refund_tester_bonus_round(v_round.id, 'owner_manual_close');

    return jsonb_build_object(
        'success', true,
        'round_id', v_round.id,
        'closed', coalesce((v_res->>'closed')::boolean, false),
        'status', v_res->>'status',
        'refund_amount', coalesce((v_res->>'refund_amount')::integer, 0)
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.close_campaign_tester_bonus(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.close_campaign_tester_bonus(uuid) TO authenticated, service_role;


-- ============================================================================
-- 9. LIFECYCLE & DELETION INTEGRATIONS
-- ============================================================================

-- 9.1 Guard trigger: Prevent hard delete of campaign if active/closing bonus round exists with remaining credits
CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_tester_bonus_before_delete()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
begin
    if exists (
        select 1 from public.campaign_tester_bonus_rounds
        where campaign_id = old.id
          and status in ('active', 'closing')
          and remaining_credits > 0
    ) then
        raise exception 'CANNOT_DELETE_CAMPAIGN_WITH_UNSETTLED_BONUS_ROUND: Ứng dụng còn đợt thưởng tester chưa kết toán';
    end if;
    return old;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_tester_bonus_before_delete ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_tester_bonus_before_delete
BEFORE DELETE ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_tester_bonus_before_delete();


-- 9.2 Lifecycle trigger: On campaign completed / closed / production, transition active round to closing
CREATE OR REPLACE FUNCTION public.trg_campaigns_bonus_round_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
begin
    if new.status in ('completed', 'production', 'closed')
       and (old.status is null or old.status not in ('completed', 'production', 'closed')) then
        update public.campaign_tester_bonus_rounds
        set status = 'closing',
            stopped_at = coalesce(stopped_at, clock_timestamp())
        where campaign_id = new.id
          and status = 'active';

        new.tester_bonus_status := 'closing';
        new.tester_bonus_remaining_slots := 0;
    end if;
    return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_bonus_round_lifecycle ON public.campaigns;
CREATE TRIGGER trg_campaigns_bonus_round_lifecycle
BEFORE UPDATE OF status ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_bonus_round_lifecycle();


-- 9.3 Shared Campaign Removal Implementation & Public Deletion Wrappers

-- Private shared helper for campaign removal (admin or owner)
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
    v_app_name text;
    v_owner_id uuid;
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
    select app_name, owner_id into v_app_name, v_owner_id
    from public.campaigns
    where id = p_campaign_id
    for update;

    if v_app_name is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Strict Actual-Owner Authorization when not requested by admin:
    -- Even an admin cannot use owner RPC on behalf of another user!
    if not p_by_admin then
        if v_owner_id is distinct from v_caller_id then
            raise exception 'PERMISSION_DENIED: Chỉ chủ sở hữu ứng dụng mới có quyền gỡ bỏ ứng dụng';
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
        -- Stop new eligibility immediately by setting status to 'closed'
        -- Preserve pending obligations so testers can still be reviewed/auto-accepted.
        -- Preserve removal reason durable in admin_note until worker completes deletion.
        update public.campaigns
        set status = 'closed',
            paused_reason = case when p_by_admin then 'admin_deletion_pending' else 'owner_deletion_pending' end,
            admin_note = v_effective_reason
        where id = p_campaign_id;

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

    -- No pending reports: close and refund all remaining rounds
    for v_r in
        select id from public.campaign_tester_bonus_rounds
        where campaign_id = p_campaign_id
          and status in ('active', 'closing')
        order by created_at
        for update of campaign_tester_bonus_rounds
    loop
        perform public._close_and_refund_tester_bonus_round(
            v_r.id,
            case when p_by_admin then 'admin_delete' else 'owner_delete' end
        );
    end loop;

    -- If wallet refund overflowed or mission escrow unrefunded, keep closing/pending removal retryable
    select exists (
        select 1 from public.campaign_tester_bonus_rounds
        where campaign_id = p_campaign_id
          and status in ('active', 'closing')
          and remaining_credits > 0
    ) into v_has_unsettled_bonus;

    select exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and (status <> 'deleted' or (escrow_remaining_credits > 0 and not escrow_refunded))
    ) into v_has_unsettled_missions;

    if v_has_unsettled_bonus or v_has_unsettled_missions then
        update public.campaigns
        set status = 'closed',
            paused_reason = case when p_by_admin then 'admin_deletion_pending' else 'owner_deletion_pending' end,
            admin_note = v_effective_reason
        where id = p_campaign_id;

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

    delete from public.campaigns
    where id = p_campaign_id;

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


-- Admin wrapper: retains existing _impl_admin_delete_campaign interface and service_role grant
CREATE OR REPLACE FUNCTION public._impl_admin_delete_campaign(
    p_campaign_id uuid,
    p_reason text DEFAULT 'Gỡ bỏ bởi Admin'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
begin
    return public._request_campaign_removal(
        p_campaign_id,
        coalesce(p_reason, 'Gỡ bỏ bởi Admin'),
        true
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._impl_admin_delete_campaign(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_admin_delete_campaign(uuid, text) TO service_role;


-- Owner wrapper: public RPC for authenticated actual owners
CREATE OR REPLACE FUNCTION public.delete_owned_campaign(
    p_campaign_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_ID_REQUIRED';
    end if;

    return public._request_campaign_removal(
        p_campaign_id,
        'Gỡ bỏ bởi Chủ sở hữu',
        false
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.delete_owned_campaign(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_owned_campaign(uuid) TO authenticated, service_role;


-- ============================================================================
-- 10. ATTACH BONUS SNAPSHOT IN _submit_tester_report_core
-- ============================================================================

CREATE OR REPLACE FUNCTION public._submit_tester_report_core(p_submission_context text, p_campaign_id uuid, p_content text, p_rating integer, p_client_submit_id uuid, p_screenshot_url text, p_image_hash text, p_thumbnail_url text, p_mission_id uuid, p_attachments jsonb DEFAULT NULL::jsonb, p_expected_date date DEFAULT NULL::date)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'extensions', 'pg_catalog'
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

    -- Tester Bonus Round Snapshot variable
    v_bonus_round_id uuid := null;
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
                'attachments', v_attachments_json,
                'tester_bonus_round_id', v_idempotent_report.tester_bonus_round_id
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

        if v_submission_context = 'app_test' then
            perform public.refresh_daily_completion(v_user_id, v_today);
        end if;

        select
            submission_context, mission_id, mission_title,
            mission_reward_credits, mission_ends_at, mission_reward_mode,
            screenshot_url, image_hash, thumbnail_url,
            tester_bonus_round_id
        into
            v_res_context, v_res_mission_id, v_res_title,
            v_res_reward, v_res_ends_at, v_res_reward_mode,
            v_final_url, v_final_hash, v_final_thumb,
            v_bonus_round_id
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
            'attachments', v_attachments_json,
            'tester_bonus_round_id', v_bonus_round_id
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
        v_bonus_round_id := v_existing_report.tester_bonus_round_id;
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

            -- [BUG-2] Restore provisional checkin row for app_test resubmission
            if v_submission_context = 'app_test' then
                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do nothing;
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

        -- Acquire bonus round snapshot for new regular app_test report
        if v_submission_context = 'app_test' and v_camp.owner_id <> v_user_id then
            -- Lock campaign for key share to serialize against concurrent closing / admin deletion
            select * into v_camp from public.campaigns where id = p_campaign_id for key share;
            select id into v_bonus_round_id
            from public.campaign_tester_bonus_rounds
            where campaign_id = p_campaign_id
              and status = 'active'
            for key share;
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
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                    tester_bonus_round_id, tester_bonus_awarded
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                    v_bonus_round_id, 0
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
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                    tester_bonus_round_id, tester_bonus_awarded
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', v_provisional_amount, 0, v_provisional_amount,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                    v_bonus_round_id, 0
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
                review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                tester_bonus_round_id, tester_bonus_awarded
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                v_final_hash, p_client_submit_id, now(),
                'mission', v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at, now(),
                v_target_reward_mode,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                null, 0
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

    -- [BUG-2] Refresh daily completion after app_test submission / resubmission
    if v_submission_context = 'app_test' then
        perform public.refresh_daily_completion(v_user_id, v_today);
    end if;

    select
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, mission_reward_mode,
        screenshot_url, image_hash, thumbnail_url, report_date, rating,
        tester_bonus_round_id
    into
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_res_reward_mode,
        v_final_url, v_final_hash, v_final_thumb, v_today, v_rating,
        v_bonus_round_id
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
        'attachments', v_attachments_json,
        'tester_bonus_round_id', v_bonus_round_id
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
-- 11. INTEGRATE SETTLEMENT INTO MANUAL APPROVE: _impl_approve_tester_report
-- ============================================================================

CREATE OR REPLACE FUNCTION public._impl_approve_tester_report(
    p_report_id uuid,
    p_bonus integer DEFAULT 0
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
    v_report record;
    v_camp record;
    v_extra_reward int := 2;
    v_tip int := 0;
    v_total_bonus bigint;
    v_new_balance int;
    v_owner_bonus int := 0;
    v_owner_balance int;
    v_is_owner boolean := false;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;

    -- Tester bonus variables
    v_bonus_res jsonb;
    v_tester_bonus bigint := 0;
begin
    perform set_config('app.reviewing_report', 'true', true);

    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    -- Level 2: Lock report
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

    -- Level 3: Lock campaign row
    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id
    for update;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    v_is_owner := (v_camp.owner_id = v_caller_id);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    -- Preserve 069 semantics: p_bonus is ignored to prevent arbitrary caller credit minting
    v_extra_reward := coalesce(v_report.review_tester_bonus, 2);
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
    v_total_bonus := coalesce(v_report.bonus_awarded, coalesce(v_report.provisional_granted_credits, 2)) + v_extra_reward + v_tip;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

    if v_extra_reward > 0 then
        update public.profiles
        set credits = credits + v_extra_reward,
            last_active_date = v_today,
            missed_streak = 0
        where id = v_report.user_id
        returning credits into v_new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_report.user_id,
            v_extra_reward,
            'test_approved_bonus',
            format(
                '%s duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)',
                case when v_is_owner then 'Chủ app' else 'Admin/Moderator' end,
                v_extra_reward
            ),
            v_new_balance
        );
    else
        select credits into v_new_balance from public.profiles where id = v_report.user_id;
    end if;

    if v_is_owner then
        v_owner_bonus := coalesce(v_report.review_owner_bonus, 2);
        if v_owner_bonus > 0 then
            update public.profiles
            set credits = credits + v_owner_bonus
            where id = v_camp.owner_id
            returning credits into v_owner_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_camp.owner_id,
                v_owner_bonus,
                'owner_approval_bonus',
                format('Duyệt báo cáo kiểm thử (+%s Credit)', v_owner_bonus),
                v_owner_balance
            );
        else
            select credits into v_owner_balance from public.profiles where id = v_camp.owner_id;
        end if;
    else
        v_owner_bonus := 0;
        select credits into v_owner_balance from public.profiles where id = v_camp.owner_id;
    end if;

    perform public._settle_mission_reward(v_report.id);

    -- Only ordinary app_test reports touch checkins table
    if coalesce(v_report.submission_context, 'app_test') = 'app_test' then
        update public.checkins
        set note = 'Đã kiểm thử và được duyệt'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được duyệt')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã kiểm thử và được duyệt';
        end if;
    end if;

    -- Settle Campaign Tester Bonus Round (if eligible snapshot attached)
    v_bonus_res := public._settle_campaign_tester_bonus(v_report.id);
    if coalesce((v_bonus_res->>'settled')::boolean, false) then
        v_tester_bonus := coalesce((v_bonus_res->>'reward_credits')::int, 0);
        v_new_balance := coalesce((v_bonus_res->>'tester_balance')::int, v_new_balance);
    else
        v_tester_bonus := 0;
    end if;

    perform set_config('app.reviewing_report', 'false', true);

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'tester_bonus_awarded', v_tester_bonus,
        'owner_credits_awarded', v_owner_bonus,
        'total_earned', (v_total_bonus::bigint + v_tester_bonus::bigint),
        'tester_balance', v_new_balance,
        'owner_balance', v_owner_balance,
        'message', format(
            'Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester%s%s)',
            (v_extra_reward::bigint + v_tester_bonus::bigint),
            case when v_tester_bonus > 0 then format(' gồm +%s từ đợt thưởng', v_tester_bonus) else '' end,
            case when v_owner_bonus > 0 then format(' và +%s Credit cho bạn', v_owner_bonus) else '' end
        )
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._impl_approve_tester_report(uuid, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._impl_approve_tester_report(uuid, integer) TO service_role;


-- ============================================================================
-- 12. INTEGRATE SETTLEMENT INTO 8H AUTO-ACCEPT: auto_accept_expired_reports
-- ============================================================================

CREATE OR REPLACE FUNCTION public.auto_accept_expired_reports()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_report record;
    v_count int := 0;
    v_retried int := 0;
    v_closed_rounds int := 0;
    v_owner_id uuid;
    v_camp_name text;
    v_tip int;
    v_base int;
    v_total_bonus int;
    v_bonus_res jsonb;
    v_tester_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);
    perform set_config('app.reviewing_report', 'true', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded, provisional_granted_credits, submission_context
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        -- Level 3: Lock campaign row
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id
        for update;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        -- Genuine provisional 0 does not fall back to unrelated bonus
        v_base := coalesce(
            v_report.provisional_granted_credits,
            coalesce(nullif(v_report.bonus_awarded, 0), case when coalesce(v_report.submission_context, 'app_test') = 'app_test' then 2 else 0 end)
        );
        -- Retain provisional reward only + tip; no extra manual review bonus in auto approval
        v_total_bonus := v_base + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        insert into public.report_review_events (
            report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
        ) values (
            v_report.id, v_report.campaign_id, v_report.user_id,
            v_owner_id, null, 'auto_accept'
        );

        -- Only ordinary app_test reports touch checkins table
        if coalesce(v_report.submission_context, 'app_test') = 'app_test' then
            update public.checkins
            set note = 'Tự động duyệt sau 8h'
            where campaign_id = v_report.campaign_id
              and user_id = v_report.user_id
              and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

            if not found then
                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (
                    v_report.campaign_id, v_report.user_id,
                    (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                    'Tự động duyệt sau 8h'
                )
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Tự động duyệt sau 8h';
            end if;
        end if;

        -- Settle mission reward if mission report
        perform public._settle_mission_reward(v_report.id);

        -- Settle Campaign Tester Bonus Round (if eligible snapshot attached)
        v_bonus_res := public._settle_campaign_tester_bonus(v_report.id);
        if coalesce((v_bonus_res->>'settled')::boolean, false) then
            v_tester_bonus := coalesce((v_bonus_res->>'reward_credits')::int, 0);
        else
            v_tester_bonus := 0;
        end if;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tester_bonus > 0 and v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit + %s Credit boa + %s Credit thưởng thêm đợt). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip, v_tester_bonus)
                when v_tester_bonus > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit + %s Credit thưởng thêm đợt). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base, v_tester_bonus)
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này + %s Credit boa từ chủ app). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base)
            end,
            'report',
            jsonb_build_object(
                'report_id', v_report.id,
                'campaign_id', v_report.campaign_id,
                'status', 'approved',
                'auto_accepted', true,
                'tester_bonus_awarded', v_tester_bonus
            )
        );

        v_count := v_count + 1;
    end loop;

    -- Retry failed mission settlement jobs
    v_retried := public.retry_failed_mission_settlements(50);

    -- Process any closing tester bonus rounds that now have 0 pending reports
    v_closed_rounds := public._process_closing_tester_bonus_rounds();

    -- Reset suppress notification flag
    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$function$;

REVOKE ALL ON FUNCTION public.auto_accept_expired_reports() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.auto_accept_expired_reports() TO authenticated, service_role;



-- ============================================================================
-- 13. PROJECTION RPCS WITH TESTER BONUS FIELDS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_campaign_detail(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_uid              uuid := auth.uid();
    v_today            date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp             public.campaigns%rowtype;
    v_is_owner         boolean := false;
    v_is_actual_owner  boolean := false;
    v_can_manage       boolean := false;
    v_is_member        boolean := false;
    v_member_joined_at timestamptz;
    v_confirmed        boolean := false;
    v_campaign         jsonb;
    v_testers          jsonb := '[]'::jsonb;
    v_feedbacks        jsonb := '[]'::jsonb;
    v_reports          jsonb := '[]'::jsonb;
    v_profiles         jsonb := '{}'::jsonb;
    v_today_tested     int := 0;
    v_reports_count    int := 0;
    v_tester_reports_count int := 0;
    v_pending_reports_count int := 0;
    v_avg              float;
    v_my_dates         jsonb := '[]'::jsonb;
    v_my_profile       jsonb;
    v_unread           jsonb := '[]'::jsonb;
    v_weekly           int := 0;
    v_rel              record;
    v_app_update       jsonb := null;
    v_claim            record;
    v_is_eligible      boolean := true;
    v_ineligible_reason text := null;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    perform public.auto_accept_expired_reports();

    v_is_actual_owner := (v_camp.owner_id = v_uid);

    v_can_manage := (
        v_is_actual_owner
        or exists (
            select 1 from public.profiles p
            where p.id = v_uid and lower(p.role) in ('admin', 'moderator')
        )
    ) and not exists (
        select 1 from public.profiles p
        where p.id = v_uid and coalesce(p.is_locked, false) = true
    );

    v_is_owner := v_can_manage;

    select joined_at into v_member_joined_at
    from public.campaign_members m
    where m.campaign_id = p_campaign_id and m.user_id = v_uid;

    v_is_member := (v_member_joined_at is not null);

    v_confirmed := exists (
        select 1 from public.checkins ci
        where ci.campaign_id = p_campaign_id
          and ci.user_id = v_uid
          and ci.checkin_date = v_today
          and coalesce(ci.note, '') not like '%tạm tính%'
    );

    select coalesce(jsonb_agg(jsonb_build_object(
        'user_id', t.user_id,
        'joined_at', t.joined_at,
        'days', t.days,
        'checkin_count', t.checkin_count,
        'last_checkin_date', t.last_checkin_date
    ) order by t.days desc), '[]'::jsonb)
    into v_testers
    from (
        select m.user_id,
               to_char(m.joined_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as joined_at,
               coalesce(s.days, 0) as days,
               coalesce(s.cnt, 0) as checkin_count,
               to_char(s.last_date, 'YYYY-MM-DD') as last_checkin_date
        from public.campaign_members m
        left join (
            select user_id,
                   count(distinct checkin_date) as days,
                   count(*) as cnt,
                   max(checkin_date) as last_date
            from public.checkins
            where campaign_id = p_campaign_id
            group by user_id
        ) s on s.user_id = m.user_id
        where m.campaign_id = p_campaign_id
    ) t;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', f.id,
        'campaign_id', f.campaign_id,
        'user_id', f.user_id,
        'rating', f.rating,
        'comment', f.comment,
        'created_at', to_char(f.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'updated_at', to_char(f.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) order by f.created_at desc), '[]'::jsonb)
    into v_feedbacks
    from public.feedbacks f
    where f.campaign_id = p_campaign_id;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', tr.id,
        'campaign_id', tr.campaign_id,
        'user_id', tr.user_id,
        'report_type', tr.report_type,
        'content', tr.content,
        'screenshot_url', tr.screenshot_url,
        'thumbnail_url', tr.thumbnail_url,
        'rating', tr.rating,
        'status', tr.status,
        'bonus_awarded', tr.bonus_awarded,
        'tester_bonus_awarded', coalesce(tr.tester_bonus_awarded, 0),
        'tester_bonus_round_id', tr.tester_bonus_round_id,
        'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'rejection_reason', tr.rejection_reason,
        'image_hash', tr.image_hash,
        'review_tester_bonus', tr.review_tester_bonus,
        'review_owner_bonus', tr.review_owner_bonus,
        'business_policy_id', tr.business_policy_id,
        'provisional_granted_credits', tr.provisional_granted_credits,
        'provisional_clawed', tr.provisional_clawed,
        'submission_context', tr.submission_context,
        'mission_id', tr.mission_id,
        'mission_participant_id', tr.mission_participant_id,
        'mission_title', tr.mission_title,
        'mission_reward_credits', tr.mission_reward_credits,
        'mission_reward_mode', tr.mission_reward_mode,
        'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'report_date', to_char(tr.report_date, 'YYYY-MM-DD'),
        'attachments', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256
            ) order by a.position)
            from public.tester_report_attachments a
            where a.report_id = tr.id
        ), case when tr.screenshot_url is not null then jsonb_build_array(jsonb_build_object(
            'id', tr.id,
            'position', 0,
            'screenshot_url', tr.screenshot_url,
            'thumbnail_url', tr.thumbnail_url,
            'image_hash', tr.image_hash,
            'sha256', null
        )) else '[]'::jsonb end)
    ) order by tr.created_at desc), '[]'::jsonb)
    into v_reports
    from (
        select * from public.tester_reports
        where campaign_id = p_campaign_id
          and (v_can_manage or user_id = v_uid)
        order by created_at desc
        limit 200
    ) tr;

    select count(*),
           count(*) filter (where status = 'pending')
    into v_tester_reports_count, v_pending_reports_count
    from public.tester_reports
    where campaign_id = p_campaign_id
      and (v_can_manage or user_id = v_uid);

    select coalesce(jsonb_object_agg(t.id::text, public._profile_json(p)), '{}'::jsonb)
    into v_profiles
    from public.profiles p
    join (
        select v_camp.owner_id as id
        union
        select m.user_id from public.campaign_members m where m.campaign_id = p_campaign_id
        union
        select f.user_id from public.feedbacks f where f.campaign_id = p_campaign_id
        union
        select tr.user_id from public.tester_reports tr
        where tr.campaign_id = p_campaign_id and (v_can_manage or tr.user_id = v_uid)
    ) t on t.id = p.id;

    select count(distinct user_id) into v_today_tested
    from public.checkins
    where campaign_id = p_campaign_id and checkin_date = v_today;

    select count(*), coalesce(round(avg(rating)::numeric, 1), 0.0)
    into v_reports_count, v_avg
    from public.feedbacks
    where campaign_id = p_campaign_id;

    select coalesce(jsonb_agg(to_char(ci.checkin_date, 'YYYY-MM-DD') order by ci.checkin_date asc), '[]'::jsonb)
    into v_my_dates
    from public.checkins ci
    where ci.campaign_id = p_campaign_id and ci.user_id = v_uid;

    v_campaign := row_to_json(v_camp);

    select public._profile_json(p) into v_my_profile
    from public.profiles p where p.id = v_uid;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) order by w.created_at desc), '[]'::jsonb)
    into v_unread
    from public.user_warnings w
    where w.user_id = v_uid and w.acknowledged = false
      and not exists (
          select 1 from public.user_warning_revocations r
          where r.warning_id = w.id
      );

    select count(*) into v_weekly
    from public.user_warnings w
    where w.user_id = v_uid
      and (w.created_at at time zone 'Asia/Ho_Chi_Minh')::date >= (v_today - 6)
      and not exists (
          select 1 from public.user_warning_revocations r
          where r.warning_id = w.id
      );

    select * into v_rel
    from public.app_update_releases
    where campaign_id = p_campaign_id and status in ('active', 'exhausted')
    order by published_at desc, target_version_code desc
    limit 1;

    if v_rel.id is not null then
        if v_is_actual_owner then
            v_app_update := jsonb_build_object(
                'id', v_rel.id,
                'campaign_id', v_rel.campaign_id,
                'owner_id', coalesce(v_rel.owner_id, v_rel.owner_id_snapshot),
                'package_name', v_rel.package_name,
                'app_name', v_rel.app_name,
                'version_name', v_rel.version_name,
                'target_version_code', v_rel.target_version_code,
                'release_notes', v_rel.release_notes,
                'status', v_rel.status,
                'reward_per_claim', v_rel.reward_per_claim,
                'max_claims', v_rel.max_claims,
                'reserved_credits', v_rel.reserved_credits,
                'paid_count', v_rel.paid_count,
                'remaining_credits', v_rel.remaining_credits,
                'spent_credits', v_rel.paid_count * v_rel.reward_per_claim,
                'published_at', to_char(v_rel.published_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            );
        else
            if v_rel.paid_count < v_rel.max_claims and v_rel.remaining_credits >= v_rel.reward_per_claim then
                select * into v_claim
                from public.app_update_claims
                where release_id = v_rel.id and tester_id = v_uid;

                if not v_is_member then
                    v_is_eligible := false;
                    v_ineligible_reason := 'not_member';
                elsif v_member_joined_at > v_rel.published_at then
                    v_is_eligible := false;
                    v_ineligible_reason := 'joined_after_release';
                elsif v_camp.status = 'paused' then
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_paused';
                elsif v_camp.status <> 'open' then
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_not_open';
                elsif exists (select 1 from public.profiles p where p.id = v_uid and coalesce(p.is_locked, false) = true) then
                    v_is_eligible := false;
                    v_ineligible_reason := 'user_locked';
                end if;

                v_app_update := jsonb_build_object(
                    'id', v_rel.id,
                    'campaign_id', v_rel.campaign_id,
                    'package_name', v_rel.package_name,
                    'app_name', v_rel.app_name,
                    'version_name', v_rel.version_name,
                    'target_version_code', v_rel.target_version_code,
                    'release_notes', v_rel.release_notes,
                    'status', v_rel.status,
                    'reward_per_claim', v_rel.reward_per_claim,
                    'max_claims', v_rel.max_claims,
                    'paid_count', v_rel.paid_count,
                    'has_claimed', (v_claim.id is not null),
                    'is_eligible', v_is_eligible,
                    'ineligibility_reason', v_ineligible_reason,
                    'claim_receipt', case when v_claim.id is not null then jsonb_build_object(
                        'claim_id', v_claim.id,
                        'release_id', v_claim.release_id,
                        'ordinal', v_claim.ordinal,
                        'reward_credits', v_claim.reward_credits,
                        'confirmed_at', to_char(v_claim.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                    ) else null end
                );
            end if;
        end if;
    end if;

    return jsonb_build_object(
        'campaign', v_campaign,
        'is_owner', v_is_owner,
        'is_actual_owner', v_is_actual_owner,
        'can_manage', v_can_manage,
        'is_member', v_is_member,
        'checked_in_confirmed_today', v_confirmed,
        'testers', v_testers,
        'feedbacks', v_feedbacks,
        'reports', v_reports,
        'profiles', v_profiles,
        'today_tested_count', v_today_tested,
        'reports_count', v_reports_count,
        'pending_reports_count', v_pending_reports_count,
        'tester_reports_count', v_tester_reports_count,
        'avg_rating', v_avg,
        'my_checkin_dates', v_my_dates,
        'my_profile', v_my_profile,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly,
        'app_update', v_app_update
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail(uuid) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.get_campaign_reports_for_owner(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_locked boolean;
    v_res jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role, coalesce(is_locked, false)
    into v_role, v_locked
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_locked, false) = true then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

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
                'thumbnail_url', tr.thumbnail_url,
                'rating', tr.rating,
                'status', tr.status,
                'bonus_awarded', tr.bonus_awarded,
                'tester_bonus_awarded', coalesce(tr.tester_bonus_awarded, 0),
                'tester_bonus_round_id', tr.tester_bonus_round_id,
                'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'rejection_reason', tr.rejection_reason,
                'image_hash', tr.image_hash,
                'review_tester_bonus', tr.review_tester_bonus,
                'review_owner_bonus', tr.review_owner_bonus,
                'business_policy_id', tr.business_policy_id,
                'provisional_granted_credits', tr.provisional_granted_credits,
                'provisional_clawed', tr.provisional_clawed,
                'tester_name', coalesce(p.display_name, split_part(coalesce(p.email, ''), '@', 1), 'Tester'),
                'tester_avatar', p.avatar_url,
                'tester_credits', p.credits,
                'submission_context', tr.submission_context,
                'mission_id', tr.mission_id,
                'mission_participant_id', tr.mission_participant_id,
                'mission_title', tr.mission_title,
                'mission_reward_credits', tr.mission_reward_credits,
                'mission_reward_mode', tr.mission_reward_mode,
                'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'report_date', to_char(tr.report_date, 'YYYY-MM-DD'),
                'attachments', coalesce((
                    select jsonb_agg(jsonb_build_object(
                        'id', a.id,
                        'position', a.position,
                        'screenshot_url', a.screenshot_url,
                        'thumbnail_url', a.thumbnail_url,
                        'image_hash', a.image_hash,
                        'sha256', a.sha256
                    ) order by a.position)
                    from public.tester_report_attachments a
                    where a.report_id = tr.id
                ), case when tr.screenshot_url is not null then jsonb_build_array(jsonb_build_object(
                    'id', tr.id,
                    'position', 0,
                    'screenshot_url', tr.screenshot_url,
                    'thumbnail_url', tr.thumbnail_url,
                    'image_hash', tr.image_hash,
                    'sha256', null
                )) else '[]'::jsonb end)
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
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_reports_for_owner(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_reports_for_owner(uuid) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_get_tester_reports_queue(
    p_status text DEFAULT 'pending'::text,
    p_campaign_id uuid DEFAULT NULL::uuid,
    p_tester_id uuid DEFAULT NULL::uuid,
    p_owner_id uuid DEFAULT NULL::uuid,
    p_submission_context text DEFAULT NULL::text,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
begin
    if exists (select 1 from pg_proc where proname = 'assert_unlocked_admin' and pronamespace = 'public'::regnamespace) then
        v_caller_id := public.assert_unlocked_admin();
    else
        v_caller_id := public._assert_daily_task_admin();
    end if;
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    with raw_reports as (
        select
            tr.id,
            tr.campaign_id,
            c.app_name as campaign_name,
            c.package_name,
            c.owner_id,
            po.display_name as owner_name,
            tr.user_id as tester_id,
            pt.display_name as tester_name,
            pt.email as tester_email,
            tr.report_type,
            tr.content,
            tr.screenshot_url,
            tr.thumbnail_url,
            tr.media_expired,
            tr.rating,
            tr.status,
            tr.submission_context,
            tr.mission_title,
            tr.mission_reward_mode,
            tr.created_at,
            tr.reviewed_at,
            tr.rejection_reason,
            tr.review_tester_bonus,
            tr.review_owner_bonus,
            tr.business_policy_id,
            tr.provisional_granted_credits,
            tr.provisional_clawed,
            tr.bonus_awarded,
            coalesce(tr.tester_bonus_awarded, 0) as tester_bonus_awarded,
            tr.tester_bonus_round_id,
            to_char(tr.report_date, 'YYYY-MM-DD') as report_date,
            coalesce((
                select jsonb_agg(jsonb_build_object(
                    'id', a.id,
                    'position', a.position,
                    'screenshot_url', a.screenshot_url,
                    'thumbnail_url', a.thumbnail_url,
                    'image_hash', a.image_hash,
                    'sha256', a.sha256
                ) order by a.position)
                from public.tester_report_attachments a
                where a.report_id = tr.id
            ), case when tr.screenshot_url is not null then jsonb_build_array(jsonb_build_object(
                'id', tr.id,
                'position', 0,
                'screenshot_url', tr.screenshot_url,
                'thumbnail_url', tr.thumbnail_url,
                'image_hash', tr.image_hash,
                'sha256', null
            )) else '[]'::jsonb end) as attachments
        from public.tester_reports tr
        inner join public.campaigns c on c.id = tr.campaign_id
        left join public.profiles po on po.id = c.owner_id
        left join public.profiles pt on pt.id = tr.user_id
        where (p_cursor_created_at is null or (tr.created_at, tr.id) < (p_cursor_created_at, p_cursor_id))
          and (p_status is null or tr.status = p_status)
          and (p_campaign_id is null or tr.campaign_id = p_campaign_id)
          and (p_tester_id is null or tr.user_id = p_tester_id)
          and (p_owner_id is null or c.owner_id = p_owner_id)
          and (p_submission_context is null or tr.submission_context = p_submission_context)
        order by tr.created_at desc, tr.id desc
        limit v_limit + 1
    )
    select jsonb_agg(to_jsonb(r)), count(*)
    into v_rows, v_count
    from raw_reports r;

    if v_count > v_limit then
        v_has_more := true;
        select jsonb_agg(elem)
        into v_rows
        from (
            select elem
            from jsonb_array_elements(v_rows) with ordinality as t(elem, ord)
            where ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    end if;

    return jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) TO authenticated, service_role;

COMMIT;
