-- ============================================================================
-- Migration 084: Campaign Posting Business Policies & Financial V2 Creation RPC
--
-- Scope:
-- 1. Extend public.business_policies with configurable campaign posting controls:
--    - min_credits_to_post_app (default 10, range 0..10000, standardized >=)
--    - slot_fee_1 (default 0, range 0..10000; per-campaign posting fee for permanent Slot 1)
--    - target_15_fee (default 10, range 0..10000)
--    - target_20_fee (default 20, range 0..10000, invariant target_15_fee <= target_20_fee)
-- 2. Add creation fee snapshot columns on public.campaigns and idempotent ledger
--    public.campaign_creation_receipts.
-- 3. Update revision hash, JSON formatters, single-signature admin_schedule_business_policy,
--    and get_business_quote ('all' and 'campaign_create').
-- 4. Introduce public.create_campaign_v2 with server quote token verification (CAS),
--    client_request_id replay protection, and atomic fee + min-balance enforcement.
-- 5. Update campaigns_insert_own RLS and handle_campaign_target_cost() with dynamic
--    min-balance enforcement and fail-closed CLIENT_UPDATE_REQUIRED for legacy clients
--    when posting/target fees differ from legacy defaults.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. EXTEND BUSINESS_POLICIES SCHEMA & BASELINE SEED
-- ============================================================================

ALTER TABLE public.business_policies
    ADD COLUMN IF NOT EXISTS min_credits_to_post_app integer NOT NULL DEFAULT 10
        CHECK (min_credits_to_post_app >= 0 AND min_credits_to_post_app <= 10000),
    ADD COLUMN IF NOT EXISTS slot_fee_1 integer NOT NULL DEFAULT 0
        CHECK (slot_fee_1 >= 0 AND slot_fee_1 <= 10000),
    ADD COLUMN IF NOT EXISTS target_15_fee integer NOT NULL DEFAULT 10
        CHECK (target_15_fee >= 0 AND target_15_fee <= 10000),
    ADD COLUMN IF NOT EXISTS target_20_fee integer NOT NULL DEFAULT 20
        CHECK (target_20_fee >= 0 AND target_20_fee <= 10000);

ALTER TABLE public.business_policies
    DROP CONSTRAINT IF EXISTS chk_business_policy_target_fees;

ALTER TABLE public.business_policies
    ADD CONSTRAINT chk_business_policy_target_fees
    CHECK (target_15_fee <= target_20_fee);

SET LOCAL test.allow_policy_retroactive_write = 'true';

UPDATE public.business_policies
SET min_credits_to_post_app = 10,
    slot_fee_1 = 0,
    target_15_fee = 10,
    target_20_fee = 20
WHERE effective_from = date '-infinity'
  AND (
      min_credits_to_post_app IS DISTINCT FROM 10 OR
      slot_fee_1 IS DISTINCT FROM 0 OR
      target_15_fee IS DISTINCT FROM 10 OR
      target_20_fee IS DISTINCT FROM 20
  );

RESET test.allow_policy_retroactive_write;

-- ============================================================================
-- 2. CAMPAIGN SNAPSHOT COLUMNS & CREATION RECEIPTS LEDGER
-- ============================================================================

ALTER TABLE public.campaigns
    ADD COLUMN IF NOT EXISTS slot_1_fee_paid integer NOT NULL DEFAULT 0
        CHECK (slot_1_fee_paid >= 0),
    ADD COLUMN IF NOT EXISTS target_fee_paid integer NOT NULL DEFAULT 0
        CHECK (target_fee_paid >= 0),
    ADD COLUMN IF NOT EXISTS creation_policy_id bigint REFERENCES public.business_policies(id);

-- Update check_campaign_slot_validity so unrelated column updates on already-open
-- campaigns do not re-fail on expired user_slots, enforce 1..6 slot range,
-- serialize per-owner slot checks via advisory lock, and validate legacy client INSERTs.
CREATE OR REPLACE FUNCTION public.check_campaign_slot_validity()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_is_client_direct boolean := (
        current_setting('app.campaign_client_direct_write', true) = 'true'
        or current_user in ('authenticated', 'anon', 'public')
    );
    v_clean_pkg text;
begin
    if current_setting('app.allow_campaign_snapshot_backfill', true) = 'true'
       and current_user not in ('authenticated', 'anon', 'public')
    then
        return new;
    end if;

    if new.slot_number is null or new.slot_number < 1 or new.slot_number > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    if new.owner_id is not null and (
        tg_op = 'INSERT'
        or old.status is distinct from new.status
        or old.slot_number is distinct from new.slot_number
        or old.owner_id is distinct from new.owner_id
    ) then
        perform pg_advisory_xact_lock(hashtextextended('campaign_slot_guard', hashtext(new.owner_id::text)));
    end if;

    if tg_op = 'INSERT' and v_is_client_direct then
        v_clean_pkg := btrim(coalesce(new.package_name, ''));
        if v_clean_pkg = ''
           or length(v_clean_pkg) > 255
           or v_clean_pkg !~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'
        then
            raise exception 'INVALID_PACKAGE_NAME';
        end if;
        new.package_name := v_clean_pkg;

        if exists (
            select 1 from public.campaigns
            where owner_id = new.owner_id
              and lower(btrim(package_name)) = lower(v_clean_pkg)
              and status in ('open', 'paused')
              and id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) then
            raise exception 'PACKAGE_ALREADY_ACTIVE';
        end if;

        if exists (
            select 1 from public.campaigns
            where owner_id = new.owner_id
              and slot_number = new.slot_number
              and status in ('open', 'paused')
              and id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) then
            raise exception 'SLOT_ALREADY_IN_USE';
        end if;
    end if;

    if new.status = 'open' then
        if tg_op = 'INSERT'
           or old.status is distinct from 'open'
           or new.slot_number is distinct from old.slot_number
           or new.owner_id is distinct from old.owner_id
        then
            -- Slot 1 is permanent and never requires a user_slots row
            if new.slot_number > 1 then
                if not exists (
                    select 1 from public.user_slots
                    where user_id = new.owner_id
                      and slot_number = new.slot_number
                      and expires_at > now()
                ) then
                    raise exception 'SLOT_NOT_ACTIVE_OR_EXPIRED';
                end if;
            end if;

            -- Prevent two open campaigns from using the same slot concurrently
            if exists (
                select 1 from public.campaigns
                where owner_id = new.owner_id
                  and slot_number = new.slot_number
                  and status = 'open'
                  and id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid)
            ) then
                raise exception 'SLOT_ALREADY_IN_USE';
            end if;
        end if;
    end if;
    return new;
end;
$function$;

REVOKE ALL ON FUNCTION public.check_campaign_slot_validity() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.check_campaign_slot_validity() TO PUBLIC, anon, authenticated, service_role;

-- Backfill historical target_fee_paid snapshot without touching balances or credit_logs
SET LOCAL app.allow_campaign_snapshot_backfill = 'true';

UPDATE public.campaigns
SET target_fee_paid = CASE target_testers
        WHEN 15 THEN 10
        WHEN 20 THEN 20
        ELSE 0
    END
WHERE target_testers IN (15, 20)
  AND target_fee_paid = 0
  AND creation_policy_id IS NULL;

RESET app.allow_campaign_snapshot_backfill;

CREATE TABLE IF NOT EXISTS public.campaign_creation_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    client_request_id uuid NOT NULL,
    campaign_id uuid REFERENCES public.campaigns(id) ON DELETE SET NULL,
    created_campaign_id uuid NOT NULL,
    package_name text NOT NULL,
    slot_number integer NOT NULL CHECK (slot_number >= 1 AND slot_number <= 6),
    target_testers integer NOT NULL CHECK (target_testers IN (12, 15, 20)),
    slot_1_cost integer NOT NULL CHECK (slot_1_cost >= 0),
    target_cost integer NOT NULL CHECK (target_cost >= 0),
    total_cost integer NOT NULL CHECK (total_cost >= 0),
    min_credits_required integer NOT NULL CHECK (min_credits_required >= 0),
    balance_after integer NOT NULL CHECK (balance_after >= 0),
    quote_token text,
    canonical_token text,
    business_policy_id bigint REFERENCES public.business_policies(id),
    payload_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_campaign_creation_receipts_user_request UNIQUE (user_id, client_request_id)
);

CREATE INDEX IF NOT EXISTS idx_campaign_creation_receipts_user
    ON public.campaign_creation_receipts (user_id, created_at DESC);

ALTER TABLE public.campaign_creation_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.campaign_creation_receipts NO FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "campaign_creation_receipts_select_own" ON public.campaign_creation_receipts;
CREATE POLICY "campaign_creation_receipts_select_own" ON public.campaign_creation_receipts
    FOR SELECT TO authenticated USING (user_id = auth.uid());

REVOKE ALL ON TABLE public.campaign_creation_receipts FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.campaign_creation_receipts TO authenticated;
GRANT ALL ON TABLE public.campaign_creation_receipts TO service_role;

-- ============================================================================
-- 3. REVISION CAS, FORMATTERS, ADMIN SCHEDULE RPC & QUOTES
-- ============================================================================

CREATE OR REPLACE FUNCTION public._get_business_policy_revision()
RETURNS text
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    select coalesce(
        md5(string_agg(
            p.id::text || ':' ||
            p.effective_from::text || ':' ||
            p.welcome_credits::text || ':' ||
            p.penalty_tier1_pct::text || ':' ||
            p.penalty_tier2_pct::text || ':' ||
            p.penalty_tier3_pct::text || ':' ||
            p.penalty_tier4_pct::text || ':' ||
            p.push_fee::text || ':' ||
            p.slot_fee_1::text || ':' ||
            p.slot_fee_2::text || ':' ||
            p.slot_fee_3::text || ':' ||
            p.slot_fee_4_plus::text || ':' ||
            p.slot_renewal_days::text || ':' ||
            p.min_credits_to_post_app::text || ':' ||
            p.target_15_fee::text || ':' ||
            p.target_20_fee::text || ':' ||
            p.report_provisional_reward::text || ':' ||
            p.report_review_tester_bonus::text || ':' ||
            p.report_review_owner_bonus::text || ':' ||
            p.app_update_reward_per_claim::text || ':' ||
            p.app_update_max_claims::text || ':' ||
            to_char(p.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || ':' ||
            md5(p.operator_note),
            ',' order by p.id
        )),
        'empty'
    )
    from public.business_policies p;
$$;

CREATE OR REPLACE FUNCTION public._format_business_policy_json(p public.business_policies)
RETURNS jsonb
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    select jsonb_build_object(
        'id', p.id,
        'effective_from', case when p.effective_from = date '-infinity' then null else to_char(p.effective_from, 'YYYY-MM-DD') end,
        'is_baseline', (p.effective_from = date '-infinity'),
        'welcome_credits', p.welcome_credits,
        'min_credits_to_post_app', p.min_credits_to_post_app,
        'daily_penalty_tiers', jsonb_build_object(
            'tier1_pct', p.penalty_tier1_pct,
            'tier2_pct', p.penalty_tier2_pct,
            'tier3_pct', p.penalty_tier3_pct,
            'tier4_pct', p.penalty_tier4_pct
        ),
        'push_fee', p.push_fee,
        'slot_fees', jsonb_build_object(
            'slot_1', p.slot_fee_1,
            'slot_2', p.slot_fee_2,
            'slot_3', p.slot_fee_3,
            'slot_4_plus', p.slot_fee_4_plus,
            'renewal_days', p.slot_renewal_days
        ),
        'target_fees', jsonb_build_object(
            'target_12', 0,
            'target_15', p.target_15_fee,
            'target_20', p.target_20_fee
        ),
        'report_rewards', jsonb_build_object(
            'provisional_reward', p.report_provisional_reward,
            'review_tester_bonus', p.report_review_tester_bonus,
            'review_owner_bonus', p.report_review_owner_bonus
        ),
        'app_update_rewards', jsonb_build_object(
            'reward_per_claim', p.app_update_reward_per_claim,
            'max_claims', p.app_update_max_claims,
            'reserved_budget', p.app_update_reward_per_claim * p.app_update_max_claims
        ),
        'operator_note', p.operator_note,
        'created_at', to_char(p.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'updated_at', to_char(p.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
$$;

CREATE OR REPLACE FUNCTION public._format_business_policy_public_json(p public.business_policies)
RETURNS jsonb
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    select jsonb_build_object(
        'id', p.id,
        'effective_from', case when p.effective_from = date '-infinity' then null else to_char(p.effective_from, 'YYYY-MM-DD') end,
        'is_baseline', (p.effective_from = date '-infinity'),
        'welcome_credits', p.welcome_credits,
        'min_credits_to_post_app', p.min_credits_to_post_app,
        'daily_penalty_tiers', jsonb_build_object(
            'tier1_pct', p.penalty_tier1_pct,
            'tier2_pct', p.penalty_tier2_pct,
            'tier3_pct', p.penalty_tier3_pct,
            'tier4_pct', p.penalty_tier4_pct
        ),
        'push_fee', p.push_fee,
        'slot_fees', jsonb_build_object(
            'slot_1', p.slot_fee_1,
            'slot_2', p.slot_fee_2,
            'slot_3', p.slot_fee_3,
            'slot_4_plus', p.slot_fee_4_plus,
            'renewal_days', p.slot_renewal_days
        ),
        'target_fees', jsonb_build_object(
            'target_12', 0,
            'target_15', p.target_15_fee,
            'target_20', p.target_20_fee
        ),
        'report_rewards', jsonb_build_object(
            'provisional_reward', p.report_provisional_reward,
            'review_tester_bonus', p.report_review_tester_bonus,
            'review_owner_bonus', p.report_review_owner_bonus
        ),
        'app_update_rewards', jsonb_build_object(
            'reward_per_claim', p.app_update_reward_per_claim,
            'max_claims', p.app_update_max_claims,
            'reserved_budget', p.app_update_reward_per_claim * p.app_update_max_claims
        )
    );
$$;

-- Drop old 19-parameter signature first to avoid PostgREST PGRST203 overload ambiguity
DROP FUNCTION IF EXISTS public.admin_schedule_business_policy(
    date, text, text,
    integer, integer, integer, integer, integer,
    integer, integer, integer, integer, integer,
    integer, integer, integer, integer, integer,
    uuid
);

CREATE OR REPLACE FUNCTION public.admin_schedule_business_policy(
    p_effective_from date,
    p_operator_note text,
    p_expected_revision text,
    p_welcome_credits integer DEFAULT 100,
    p_penalty_tier1_pct integer DEFAULT 1,
    p_penalty_tier2_pct integer DEFAULT 2,
    p_penalty_tier3_pct integer DEFAULT 5,
    p_penalty_tier4_pct integer DEFAULT 10,
    p_push_fee integer DEFAULT 100,
    p_slot_fee_2 integer DEFAULT 50,
    p_slot_fee_3 integer DEFAULT 100,
    p_slot_fee_4_plus integer DEFAULT 200,
    p_slot_renewal_days integer DEFAULT 30,
    p_report_provisional_reward integer DEFAULT 10,
    p_report_review_tester_bonus integer DEFAULT 2,
    p_report_review_owner_bonus integer DEFAULT 2,
    p_app_update_reward_per_claim integer DEFAULT 6,
    p_app_update_max_claims integer DEFAULT 20,
    p_request_id uuid DEFAULT NULL::uuid,
    p_min_credits_to_post_app integer DEFAULT 10,
    p_slot_fee_1 integer DEFAULT 0,
    p_target_15_fee integer DEFAULT 10,
    p_target_20_fee integer DEFAULT 20
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_actor uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_clean_note text := btrim(coalesce(p_operator_note, ''));
    v_current_rev text;
    v_payload_hash text;
    v_existing_req record;
    v_result jsonb;
    v_old_row jsonb;
    v_new_row jsonb;
begin
    -- 1. Auth check first
    v_actor := public._assert_daily_task_admin();

    -- 2. Request replay check BEFORE CAS
    if p_request_id is not null then
        v_payload_hash := md5(jsonb_build_object(
            'effective_from', to_char(p_effective_from, 'YYYY-MM-DD'),
            'welcome_credits', p_welcome_credits,
            'min_credits_to_post_app', p_min_credits_to_post_app,
            'penalty_tier1_pct', p_penalty_tier1_pct,
            'penalty_tier2_pct', p_penalty_tier2_pct,
            'penalty_tier3_pct', p_penalty_tier3_pct,
            'penalty_tier4_pct', p_penalty_tier4_pct,
            'push_fee', p_push_fee,
            'slot_fee_1', p_slot_fee_1,
            'slot_fee_2', p_slot_fee_2,
            'slot_fee_3', p_slot_fee_3,
            'slot_fee_4_plus', p_slot_fee_4_plus,
            'slot_renewal_days', p_slot_renewal_days,
            'target_15_fee', p_target_15_fee,
            'target_20_fee', p_target_20_fee,
            'report_provisional_reward', p_report_provisional_reward,
            'report_review_tester_bonus', p_report_review_tester_bonus,
            'report_review_owner_bonus', p_report_review_owner_bonus,
            'app_update_reward_per_claim', p_app_update_reward_per_claim,
            'app_update_max_claims', p_app_update_max_claims,
            'operator_note', v_clean_note
        )::text);

        select * into v_existing_req
        from public.business_policy_admin_requests
        where actor_id = v_actor and operation = 'schedule' and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash <> v_payload_hash then
                raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với nội dung khác';
            end if;
            return v_existing_req.result;
        end if;
    end if;

    perform pg_advisory_xact_lock(hashtextextended('business_policy_schedule', 0));

    -- Re-assert admin status after acquiring serialize lock
    v_actor := public._assert_daily_task_admin();

    -- Re-check request ledger after lock
    if p_request_id is not null then
        select * into v_existing_req
        from public.business_policy_admin_requests
        where actor_id = v_actor and operation = 'schedule' and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash <> v_payload_hash then
                raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với nội dung khác';
            end if;
            return v_existing_req.result;
        end if;
    end if;

    -- 3. CAS revision check
    if p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'BUSINESS_POLICY_REVISION_REQUIRED';
    end if;

    v_current_rev := public._get_business_policy_revision();
    if v_current_rev is distinct from p_expected_revision then
        raise exception 'BUSINESS_POLICY_CONFLICT';
    end if;

    -- 4. Date and bounds validation
    if p_effective_from is null or not isfinite(p_effective_from) or p_effective_from <= v_today or p_effective_from > (v_today + interval '3650 days') then
        raise exception 'BUSINESS_POLICY_FUTURE_DATE_REQUIRED';
    end if;

    if p_operator_note is null or length(v_clean_note) not between 1 and 1000 then
        raise exception 'BUSINESS_POLICY_NOTE_REQUIRED';
    end if;

    if p_welcome_credits is null or p_welcome_credits not between 0 and 10000 then
        raise exception 'BUSINESS_POLICY_INVALID_WELCOME_CREDITS';
    end if;

    if p_min_credits_to_post_app is null or p_min_credits_to_post_app not between 0 and 10000 then
        raise exception 'BUSINESS_POLICY_INVALID_MIN_CREDITS_TO_POST';
    end if;

    if p_penalty_tier1_pct is null or p_penalty_tier1_pct not between 0 and 100 or
       p_penalty_tier2_pct is null or p_penalty_tier2_pct not between 0 and 100 or
       p_penalty_tier3_pct is null or p_penalty_tier3_pct not between 0 and 100 or
       p_penalty_tier4_pct is null or p_penalty_tier4_pct not between 0 and 100 or
       not (p_penalty_tier1_pct <= p_penalty_tier2_pct and
            p_penalty_tier2_pct <= p_penalty_tier3_pct and
            p_penalty_tier3_pct <= p_penalty_tier4_pct)
    then
        raise exception 'BUSINESS_POLICY_INVALID_PENALTY_TIERS';
    end if;

    if p_push_fee is null or p_push_fee not between 1 and 10000 then
        raise exception 'BUSINESS_POLICY_INVALID_PUSH_FEE';
    end if;

    if p_slot_fee_1 is null or p_slot_fee_1 not between 0 and 10000 or
       p_slot_fee_2 is null or p_slot_fee_2 not between 1 and 10000 or
       p_slot_fee_3 is null or p_slot_fee_3 not between 1 and 10000 or
       p_slot_fee_4_plus is null or p_slot_fee_4_plus not between 1 and 10000 or
       p_slot_renewal_days is null or p_slot_renewal_days not between 1 and 365
    then
        raise exception 'BUSINESS_POLICY_INVALID_SLOT_FEES';
    end if;

    if p_target_15_fee is null or p_target_15_fee not between 0 and 10000 or
       p_target_20_fee is null or p_target_20_fee not between 0 and 10000 or
       p_target_15_fee > p_target_20_fee
    then
        raise exception 'BUSINESS_POLICY_INVALID_TARGET_FEES';
    end if;

    if p_report_provisional_reward is null or p_report_provisional_reward not between 0 and 1000 or
       p_report_review_tester_bonus is null or p_report_review_tester_bonus not between 0 and 1000 or
       p_report_review_owner_bonus is null or p_report_review_owner_bonus not between 0 and 1000
    then
        raise exception 'BUSINESS_POLICY_INVALID_REPORT_REWARDS';
    end if;

    if p_app_update_reward_per_claim is null or p_app_update_reward_per_claim not between 1 and 1000 or
       p_app_update_max_claims is null or p_app_update_max_claims not between 1 and 1000
    then
        raise exception 'BUSINESS_POLICY_INVALID_APP_UPDATE_REWARDS';
    end if;

    select to_jsonb(p) into v_old_row
    from public.business_policies p
    where p.effective_from = p_effective_from;

    insert into public.business_policies (
        effective_from,
        welcome_credits,
        min_credits_to_post_app,
        penalty_tier1_pct,
        penalty_tier2_pct,
        penalty_tier3_pct,
        penalty_tier4_pct,
        push_fee,
        slot_fee_1,
        slot_fee_2,
        slot_fee_3,
        slot_fee_4_plus,
        slot_renewal_days,
        target_15_fee,
        target_20_fee,
        report_provisional_reward,
        report_review_tester_bonus,
        report_review_owner_bonus,
        app_update_reward_per_claim,
        app_update_max_claims,
        operator_note,
        updated_at
    ) values (
        p_effective_from,
        p_welcome_credits,
        p_min_credits_to_post_app,
        p_penalty_tier1_pct,
        p_penalty_tier2_pct,
        p_penalty_tier3_pct,
        p_penalty_tier4_pct,
        p_push_fee,
        p_slot_fee_1,
        p_slot_fee_2,
        p_slot_fee_3,
        p_slot_fee_4_plus,
        p_slot_renewal_days,
        p_target_15_fee,
        p_target_20_fee,
        p_report_provisional_reward,
        p_report_review_tester_bonus,
        p_report_review_owner_bonus,
        p_app_update_reward_per_claim,
        p_app_update_max_claims,
        v_clean_note,
        clock_timestamp()
    )
    on conflict (effective_from) do update set
        welcome_credits = excluded.welcome_credits,
        min_credits_to_post_app = excluded.min_credits_to_post_app,
        penalty_tier1_pct = excluded.penalty_tier1_pct,
        penalty_tier2_pct = excluded.penalty_tier2_pct,
        penalty_tier3_pct = excluded.penalty_tier3_pct,
        penalty_tier4_pct = excluded.penalty_tier4_pct,
        push_fee = excluded.push_fee,
        slot_fee_1 = excluded.slot_fee_1,
        slot_fee_2 = excluded.slot_fee_2,
        slot_fee_3 = excluded.slot_fee_3,
        slot_fee_4_plus = excluded.slot_fee_4_plus,
        slot_renewal_days = excluded.slot_renewal_days,
        target_15_fee = excluded.target_15_fee,
        target_20_fee = excluded.target_20_fee,
        report_provisional_reward = excluded.report_provisional_reward,
        report_review_tester_bonus = excluded.report_review_tester_bonus,
        report_review_owner_bonus = excluded.report_review_owner_bonus,
        app_update_reward_per_claim = excluded.app_update_reward_per_claim,
        app_update_max_claims = excluded.app_update_max_claims,
        operator_note = excluded.operator_note,
        updated_at = clock_timestamp();

    select to_jsonb(p) into v_new_row
    from public.business_policies p
    where p.effective_from = p_effective_from;

    -- Record in 070 common admin audit table if function exists
    if exists (select 1 from pg_proc where proname = 'record_admin_action_event' and pronamespace = 'public'::regnamespace) then
        execute 'select public.record_admin_action_event($1, $2, $3, $4, $5, $6::jsonb, $7::jsonb, $8)'
        using v_actor, 'schedule_business_policy', 'business_policy', to_char(p_effective_from, 'YYYY-MM-DD'), v_clean_note, v_old_row, v_new_row, p_request_id;
    end if;

    v_result := public.admin_get_business_policy_config();

    if p_request_id is not null then
        insert into public.business_policy_admin_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_actor, 'schedule', v_payload_hash, v_result, clock_timestamp()
        ) on conflict (actor_id, operation, id) do nothing;
    end if;

    return v_result;
end;
$$;

CREATE OR REPLACE FUNCTION public._get_business_quote_token(
    p_quote_type text,
    p_policy public.business_policies,
    p_params jsonb DEFAULT '{}'::jsonb
)
RETURNS text
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    select md5(
        'quote:' ||
        lower(btrim(coalesce(p_quote_type, ''))) || ':' ||
        p_policy.id::text || ':' ||
        p_policy.effective_from::text || ':' ||
        to_char(p_policy.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || ':' ||
        case lower(btrim(coalesce(p_quote_type, '')))
            when 'push' then
                'push:' || p_policy.push_fee::text
            when 'slot' then
                'slot:' || coalesce((p_params ->> 'slot_number')::text, '2') || ':' ||
                (case coalesce((p_params ->> 'slot_number')::integer, 2)
                    when 2 then p_policy.slot_fee_2
                    when 3 then p_policy.slot_fee_3
                    else p_policy.slot_fee_4_plus
                 end)::text || ':' ||
                p_policy.slot_renewal_days::text
            when 'campaign_create' then
                'campaign_create:' ||
                coalesce((p_params ->> 'slot_number')::text, '1') || ':' ||
                coalesce((p_params ->> 'target_testers')::text, '12') || ':' ||
                (case when coalesce((p_params ->> 'slot_number')::integer, 1) = 1 then p_policy.slot_fee_1 else 0 end)::text || ':' ||
                (case coalesce((p_params ->> 'target_testers')::integer, 12)
                    when 15 then p_policy.target_15_fee
                    when 20 then p_policy.target_20_fee
                    else 0
                 end)::text || ':' ||
                p_policy.min_credits_to_post_app::text
            when 'app_update' then
                'app_update:' || p_policy.app_update_reward_per_claim::text || ':' ||
                p_policy.app_update_max_claims::text || ':' ||
                (p_policy.app_update_reward_per_claim * p_policy.app_update_max_claims)::text
            else
                'all:' || p_policy.push_fee::text || ':' ||
                p_policy.slot_fee_1::text || ':' ||
                p_policy.slot_fee_2::text || ':' || p_policy.slot_fee_3::text || ':' || p_policy.slot_fee_4_plus::text || ':' ||
                p_policy.slot_renewal_days::text || ':' ||
                p_policy.min_credits_to_post_app::text || ':' ||
                p_policy.target_15_fee::text || ':' || p_policy.target_20_fee::text || ':' ||
                p_policy.app_update_reward_per_claim::text || ':' || p_policy.app_update_max_claims::text || ':' ||
                p_policy.report_provisional_reward::text || ':' || p_policy.report_review_tester_bonus::text || ':' || p_policy.report_review_owner_bonus::text
        end
    );
$$;

CREATE OR REPLACE FUNCTION public.get_business_quote(
    p_quote_type text,
    p_params jsonb DEFAULT '{}'::jsonb
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_uid uuid := auth.uid();
    v_policy public.business_policies;
    v_token text;
    v_type text := lower(btrim(coalesce(p_quote_type, '')));
    v_slot integer;
    v_slot_cost integer;
    v_target integer;
    v_target_cost integer;
    v_total_cost integer;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    v_policy := public._get_effective_business_policy_row();

    if v_type = 'push' then
        v_token := public._get_business_quote_token('push', v_policy);
        return jsonb_build_object(
            'quote_type', 'push',
            'cost', v_policy.push_fee,
            'policy_id', v_policy.id,
            'effective_from', case when v_policy.effective_from = date '-infinity' then null else to_char(v_policy.effective_from, 'YYYY-MM-DD') end,
            'revision', v_token,
            'quote_token', v_token
        );
    elsif v_type = 'slot' then
        v_slot := coalesce((p_params ->> 'slot_number')::integer, 2);
        if v_slot < 2 or v_slot > 6 then
            raise exception 'INVALID_SLOT_NUMBER';
        end if;
        v_slot_cost := case
            when v_slot = 2 then v_policy.slot_fee_2
            when v_slot = 3 then v_policy.slot_fee_3
            else v_policy.slot_fee_4_plus
        end;
        v_token := public._get_business_quote_token('slot', v_policy, jsonb_build_object('slot_number', v_slot));
        return jsonb_build_object(
            'quote_type', 'slot',
            'slot_number', v_slot,
            'cost', v_slot_cost,
            'renewal_days', v_policy.slot_renewal_days,
            'policy_id', v_policy.id,
            'effective_from', case when v_policy.effective_from = date '-infinity' then null else to_char(v_policy.effective_from, 'YYYY-MM-DD') end,
            'revision', v_token,
            'quote_token', v_token
        );
    elsif v_type = 'campaign_create' then
        v_slot := coalesce((p_params ->> 'slot_number')::integer, 1);
        if v_slot < 1 or v_slot > 6 then
            raise exception 'INVALID_SLOT_NUMBER';
        end if;
        v_target := coalesce((p_params ->> 'target_testers')::integer, 12);
        if v_target not in (12, 15, 20) then
            raise exception 'INVALID_TARGET_TESTERS';
        end if;
        v_slot_cost := case when v_slot = 1 then v_policy.slot_fee_1 else 0 end;
        v_target_cost := case v_target
            when 15 then v_policy.target_15_fee
            when 20 then v_policy.target_20_fee
            else 0
        end;
        v_total_cost := v_slot_cost + v_target_cost;
        v_token := public._get_business_quote_token(
            'campaign_create',
            v_policy,
            jsonb_build_object('slot_number', v_slot, 'target_testers', v_target)
        );
        return jsonb_build_object(
            'quote_type', 'campaign_create',
            'slot_number', v_slot,
            'target_testers', v_target,
            'slot_1_cost', v_slot_cost,
            'target_cost', v_target_cost,
            'total_cost', v_total_cost,
            'min_credits_to_post_app', v_policy.min_credits_to_post_app,
            'required_credits', v_total_cost + v_policy.min_credits_to_post_app,
            'policy_id', v_policy.id,
            'effective_from', case when v_policy.effective_from = date '-infinity' then null else to_char(v_policy.effective_from, 'YYYY-MM-DD') end,
            'revision', v_token,
            'quote_token', v_token
        );
    elsif v_type = 'app_update' then
        v_token := public._get_business_quote_token('app_update', v_policy);
        return jsonb_build_object(
            'quote_type', 'app_update',
            'reward_per_claim', v_policy.app_update_reward_per_claim,
            'max_claims', v_policy.app_update_max_claims,
            'reserved_budget', v_policy.app_update_reward_per_claim * v_policy.app_update_max_claims,
            'policy_id', v_policy.id,
            'effective_from', case when v_policy.effective_from = date '-infinity' then null else to_char(v_policy.effective_from, 'YYYY-MM-DD') end,
            'revision', v_token,
            'quote_token', v_token
        );
    elsif v_type = 'all' then
        v_token := public._get_business_quote_token('all', v_policy);
        return jsonb_build_object(
            'quote_type', 'all',
            'policy_id', v_policy.id,
            'effective_from', case when v_policy.effective_from = date '-infinity' then null else to_char(v_policy.effective_from, 'YYYY-MM-DD') end,
            'revision', v_token,
            'quote_token', v_token,
            'min_credits_to_post_app', v_policy.min_credits_to_post_app,
            'push_fee', v_policy.push_fee,
            'slot_fees', jsonb_build_object(
                'slot_1', v_policy.slot_fee_1,
                'slot_2', v_policy.slot_fee_2,
                'slot_3', v_policy.slot_fee_3,
                'slot_4_plus', v_policy.slot_fee_4_plus,
                'renewal_days', v_policy.slot_renewal_days
            ),
            'target_fees', jsonb_build_object(
                'target_12', 0,
                'target_15', v_policy.target_15_fee,
                'target_20', v_policy.target_20_fee
            ),
            'app_update_rewards', jsonb_build_object(
                'reward_per_claim', v_policy.app_update_reward_per_claim,
                'max_claims', v_policy.app_update_max_claims,
                'reserved_budget', v_policy.app_update_reward_per_claim * v_policy.app_update_max_claims
            ),
            'report_rewards', jsonb_build_object(
                'provisional_reward', v_policy.report_provisional_reward,
                'review_tester_bonus', v_policy.report_review_tester_bonus,
                'review_owner_bonus', v_policy.report_review_owner_bonus
            )
        );
    else
        raise exception 'INVALID_QUOTE_TYPE: Must be push, slot, campaign_create, app_update, or all';
    end if;
end;
$$;

-- ============================================================================
-- 4. FINANCIAL V2 CAMPAIGN CREATION RPC & LEGACY DIRECT-WRITE GUARDS
-- ============================================================================

CREATE OR REPLACE FUNCTION public._can_caller_insert_campaign()
RETURNS boolean
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $$
declare
    v_uid uuid := auth.uid();
    v_policy public.business_policies;
begin
    if v_uid is null then
        return false;
    end if;

    v_policy := public._get_effective_business_policy_row();

    return exists (
        select 1
        from public.profiles p
        where p.id = v_uid
          and coalesce(p.is_locked, false) = false
          and coalesce(p.credits, 0) >= v_policy.min_credits_to_post_app
    );
end;
$$;

DROP POLICY IF EXISTS "campaigns_insert_own" ON public.campaigns;
CREATE POLICY "campaigns_insert_own" ON public.campaigns
    AS PERMISSIVE FOR INSERT TO authenticated
    WITH CHECK (owner_id = auth.uid() AND public._can_caller_insert_campaign());

-- Protect financial snapshot & core structural columns from direct client tampering
-- (SECURITY INVOKER so current_user accurately reflects 'authenticated' / 'anon' vs definer RPCs)
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

    return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_guard_financial_snapshots ON public.campaigns;
CREATE TRIGGER trg_campaigns_guard_financial_snapshots
    BEFORE INSERT OR UPDATE ON public.campaigns
    FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_guard_financial_snapshots();

-- Dynamic policy-aware trigger for legacy direct INSERT and internal target_testers UPDATE
CREATE OR REPLACE FUNCTION public.handle_campaign_target_cost()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_vn_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_policy public.business_policies;
    v_cost int := 0;
    v_old_cost int := 0;
    v_delta int := 0;
    v_min_credits int := 10;
    v_balance int;
    v_is_locked boolean := false;
    v_is_client_direct boolean := (
        current_setting('app.campaign_client_direct_write', true) = 'true'
        or current_user in ('authenticated', 'anon', 'public')
    );
begin
    -- Default null to 12 for internal seed inserts; reject invalid values explicitly
    if new.target_testers is null then
        new.target_testers := 12;
    elsif new.target_testers not in (12, 15, 20) then
        raise exception 'INVALID_TARGET_TESTERS';
    end if;

    -- V2 RPC already verified quotes, deducted fees, and populated snapshot columns
    if tg_op = 'INSERT'
       and current_setting('app.campaign_v2_bypass', true) = 'true'
       and not v_is_client_direct
    then
        return new;
    end if;

    v_policy := public._get_effective_business_policy_row(v_vn_today);
    v_min_credits := v_policy.min_credits_to_post_app;

    v_cost := case new.target_testers
        when 15 then v_policy.target_15_fee
        when 20 then v_policy.target_20_fee
        else 0
    end;

    if tg_op = 'INSERT' then
        -- Fail-closed for legacy direct-INSERT clients when unquoted fees are active
        if v_is_client_direct then
            if coalesce(new.slot_number, 1) = 1 and v_policy.slot_fee_1 > 0 then
                raise exception 'CLIENT_UPDATE_REQUIRED: Phí đăng ứng dụng ở Slot 1 hiện tại là % Credit. Vui lòng cập nhật ứng dụng để xác nhận báo giá.', v_policy.slot_fee_1;
            end if;
            if (new.target_testers = 15 and v_policy.target_15_fee <> 10)
               or (new.target_testers = 20 and v_policy.target_20_fee <> 20)
            then
                raise exception 'CLIENT_UPDATE_REQUIRED: Biểu phí nâng mục tiêu kiểm thử đã thay đổi. Vui lòng cập nhật ứng dụng để xác nhận báo giá.';
            end if;
        end if;

        v_delta := v_cost;

        -- Enforce dynamic minimum balance threshold even when v_delta = 0 for client inserts
        if v_is_client_direct or v_delta > 0 then
            select credits, coalesce(is_locked, false) into v_balance, v_is_locked
            from public.profiles
            where id = new.owner_id
            for update;

            if v_balance is null then
                raise exception 'PROFILE_NOT_FOUND';
            end if;

            if v_is_client_direct and v_is_locked then
                raise exception 'ACCOUNT_LOCKED';
            end if;

            if v_is_client_direct and v_balance < v_delta + v_min_credits then
                if v_delta > 0 and v_balance >= v_min_credits then
                    raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần ít nhất % Credit (gồm % Credit phí mục tiêu và % Credit số dư tối thiểu giữ lại)', v_delta + v_min_credits, v_delta, v_min_credits;
                else
                    raise exception 'INSUFFICIENT_CREDITS: Cần duy trì tối thiểu % Credit sau khi đăng ứng dụng', v_min_credits;
                end if;
            elsif not v_is_client_direct and v_delta > 0 and v_balance < v_delta then
                raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần ít nhất % Credit để nâng mục tiêu lên % Test/ngày', v_delta, new.target_testers;
            end if;
        end if;

        new.slot_1_fee_paid := 0;
        new.target_fee_paid := v_cost;
        new.creation_policy_id := v_policy.id;

        if v_delta <= 0 then
            return new;
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
    end if;

    -- tg_op = 'UPDATE'
    if new.target_testers is not distinct from old.target_testers then
        return new;
    end if;

    -- Direct clients cannot mutate target_testers after creation
    if v_is_client_direct then
        raise exception 'PERMISSION_DENIED: TARGET_TESTERS_IMMUTABLE';
    end if;

    if old.creation_policy_id is not null then
        v_old_cost := coalesce(old.target_fee_paid, 0);
    else
        v_old_cost := greatest(
            coalesce(old.target_fee_paid, 0),
            case old.target_testers when 15 then 10 when 20 then 20 else 0 end
        );
    end if;
    v_delta := v_cost - v_old_cost;

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

    new.target_fee_paid := coalesce(old.target_fee_paid, 0) + v_delta;
    return new;
end;
$function$;

-- Financial V2 Campaign Creation RPC
CREATE OR REPLACE FUNCTION public.create_campaign_v2(
    p_app_name text,
    p_package_name text,
    p_play_url text DEFAULT ''::text,
    p_testing_url text DEFAULT ''::text,
    p_description text DEFAULT ''::text,
    p_target_testers integer DEFAULT 12,
    p_icon_url text DEFAULT ''::text,
    p_test_username text DEFAULT ''::text,
    p_test_password text DEFAULT ''::text,
    p_slot_number integer DEFAULT 1,
    p_client_request_id uuid DEFAULT NULL::uuid,
    p_expected_total_cost integer DEFAULT NULL::integer,
    p_expected_min_credits integer DEFAULT NULL::integer,
    p_expected_revision text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
declare
    v_uid               uuid := auth.uid();
    v_now               timestamptz := clock_timestamp();
    v_vn_today          date := (v_now at time zone 'Asia/Ho_Chi_Minh')::date;
    v_clean_pkg         text;
    v_clean_name        text;
    v_clean_play_url    text;
    v_clean_test_url    text;
    v_clean_desc        text;
    v_clean_icon        text;
    v_clean_user        text;
    v_clean_pass        text;
    v_slot              integer;
    v_target            integer;
    v_payload_hash      text;
    v_existing_receipt  record;
    v_profile           public.profiles%rowtype;
    v_policy            public.business_policies;
    v_slot1_cost        integer;
    v_target_cost       integer;
    v_total_cost        integer;
    v_min_credits       integer;
    v_required_credits  integer;
    v_quote_token       text;
    v_all_token         text;
    v_balance_after     integer;
    v_campaign_id       uuid;
    v_receipt_id        uuid;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    v_clean_pkg := btrim(coalesce(p_package_name, ''));
    if v_clean_pkg = ''
       or length(v_clean_pkg) > 255
       or v_clean_pkg !~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'
    then
        raise exception 'INVALID_PACKAGE_NAME';
    end if;

    v_slot := coalesce(p_slot_number, 1);
    if v_slot < 1 or v_slot > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    v_target := coalesce(p_target_testers, 12);
    if v_target not in (12, 15, 20) then
        raise exception 'INVALID_TARGET_TESTERS';
    end if;

    if length(btrim(coalesce(p_app_name, ''))) > 200 then
        raise exception 'INVALID_APP_NAME: Tên ứng dụng tối đa 200 ký tự';
    end if;
    if length(btrim(coalesce(p_description, ''))) > 4000 then
        raise exception 'INVALID_DESCRIPTION: Mô tả tối đa 4000 ký tự';
    end if;
    if length(btrim(coalesce(p_play_url, ''))) > 2048 or length(btrim(coalesce(p_testing_url, ''))) > 2048 then
        raise exception 'INVALID_URL: Đường dẫn tối đa 2048 ký tự';
    end if;
    if length(btrim(coalesce(p_test_username, ''))) > 500 or length(btrim(coalesce(p_test_password, ''))) > 500 then
        raise exception 'INVALID_TEST_CREDENTIALS: Thông tin tài khoản thử nghiệm tối đa 500 ký tự';
    end if;
    if length(btrim(coalesce(p_icon_url, ''))) > 250000 then
        raise exception 'INVALID_ICON_URL: Dữ liệu biểu tượng vượt quá giới hạn cho phép';
    end if;

    v_clean_name := left(coalesce(nullif(btrim(coalesce(p_app_name, '')), ''), v_clean_pkg), 60);
    v_clean_play_url := coalesce(nullif(btrim(coalesce(p_play_url, '')), ''), 'https://play.google.com/store/apps/details?id=' || v_clean_pkg);
    v_clean_test_url := coalesce(nullif(btrim(coalesce(p_testing_url, '')), ''), 'https://play.google.com/apps/testing/' || v_clean_pkg);
    v_clean_desc := btrim(coalesce(p_description, ''));
    v_clean_icon := btrim(coalesce(p_icon_url, ''));
    v_clean_user := btrim(coalesce(p_test_username, ''));
    v_clean_pass := btrim(coalesce(p_test_password, ''));

    v_payload_hash := md5(jsonb_build_object(
        'package_name', lower(v_clean_pkg),
        'slot_number', v_slot,
        'target_testers', v_target,
        'app_name', v_clean_name,
        'play_url', v_clean_play_url,
        'testing_url', v_clean_test_url,
        'description', v_clean_desc,
        'icon_url', md5(v_clean_icon),
        'test_username', v_clean_user,
        'test_password', md5(v_clean_pass)
    )::text);

    -- 1. Pre-lock idempotent replay check
    select * into v_existing_receipt
    from public.campaign_creation_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.payload_hash <> v_payload_hash then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        if (p_expected_total_cost is not null and v_existing_receipt.total_cost <> p_expected_total_cost)
           or (p_expected_min_credits is not null and v_existing_receipt.min_credits_required <> p_expected_min_credits)
           or (p_expected_revision is not null and btrim(p_expected_revision) <> ''
               and p_expected_revision is distinct from v_existing_receipt.quote_token
               and p_expected_revision is distinct from v_existing_receipt.canonical_token)
        then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', v_existing_receipt.created_campaign_id,
            'slot_number', v_existing_receipt.slot_number,
            'target_testers', v_existing_receipt.target_testers,
            'slot_1_cost', v_existing_receipt.slot_1_cost,
            'target_cost', v_existing_receipt.target_cost,
            'charged_credits', v_existing_receipt.total_cost,
            'min_credits_required', v_existing_receipt.min_credits_required,
            'balance_after', v_existing_receipt.balance_after,
            'replayed', true
        );
    end if;

    -- 2. Serialize per-owner slot/package checks AND credit mutations in consistent lock order
    perform pg_advisory_xact_lock(hashtextextended('campaign_slot_guard', hashtext(v_uid::text)));

    select * into v_profile
    from public.profiles
    where id = v_uid
    for update;

    if v_profile.id is null then
        raise exception 'PROFILE_NOT_FOUND';
    end if;

    -- Re-check receipt under lock
    select * into v_existing_receipt
    from public.campaign_creation_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.payload_hash <> v_payload_hash then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        if (p_expected_total_cost is not null and v_existing_receipt.total_cost <> p_expected_total_cost)
           or (p_expected_min_credits is not null and v_existing_receipt.min_credits_required <> p_expected_min_credits)
           or (p_expected_revision is not null and btrim(p_expected_revision) <> ''
               and p_expected_revision is distinct from v_existing_receipt.quote_token
               and p_expected_revision is distinct from v_existing_receipt.canonical_token)
        then
            raise exception 'REQUEST_ID_CONFLICT: IDEMPOTENCY_KEY_REUSED_DIFFERENT_PARAMS';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', v_existing_receipt.created_campaign_id,
            'slot_number', v_existing_receipt.slot_number,
            'target_testers', v_existing_receipt.target_testers,
            'slot_1_cost', v_existing_receipt.slot_1_cost,
            'target_cost', v_existing_receipt.target_cost,
            'charged_credits', v_existing_receipt.total_cost,
            'min_credits_required', v_existing_receipt.min_credits_required,
            'balance_after', v_existing_receipt.balance_after,
            'replayed', true
        );
    end if;

    if coalesce(v_profile.is_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    -- 3. Resolve effective business policy & verify CAS quote terms
    v_policy := public._get_effective_business_policy_row(v_vn_today);
    v_slot1_cost := case when v_slot = 1 then v_policy.slot_fee_1 else 0 end;
    v_target_cost := case v_target
        when 15 then v_policy.target_15_fee
        when 20 then v_policy.target_20_fee
        else 0
    end;
    v_total_cost := v_slot1_cost + v_target_cost;
    v_min_credits := v_policy.min_credits_to_post_app;
    v_required_credits := v_total_cost + v_min_credits;

    v_quote_token := public._get_business_quote_token(
        'campaign_create',
        v_policy,
        jsonb_build_object('slot_number', v_slot, 'target_testers', v_target)
    );
    v_all_token := public._get_business_quote_token('all', v_policy);

    if p_expected_total_cost is null
       or p_expected_min_credits is null
       or p_expected_revision is null
       or btrim(p_expected_revision) = ''
    then
        raise exception 'BUSINESS_POLICY_CONFLICT: Báo giá đăng ứng dụng là bắt buộc đối với giao dịch v2';
    end if;

    if p_expected_total_cost <> v_total_cost or p_expected_min_credits <> v_min_credits then
        raise exception 'BUSINESS_POLICY_PRICE_MISMATCH: Điều kiện hoặc biểu phí đăng ứng dụng đã thay đổi (phí hiện tại: % Credit, số dư tối thiểu: % Credit)', v_total_cost, v_min_credits;
    end if;

    if p_expected_revision <> v_quote_token and p_expected_revision <> v_all_token then
        raise exception 'BUSINESS_POLICY_CONFLICT: Báo giá đăng ứng dụng đã hết hạn hoặc thay đổi. Vui lòng kiểm tra lại biểu phí mới.';
    end if;

    -- 4. Validate slot availability & duplicate active package
    if v_slot > 1 then
        if not exists (
            select 1 from public.user_slots
            where user_id = v_uid
              and slot_number = v_slot
              and expires_at > v_now
        ) then
            raise exception 'SLOT_NOT_ACTIVE_OR_EXPIRED';
        end if;
    end if;

    if exists (
        select 1 from public.campaigns
        where owner_id = v_uid
          and slot_number = v_slot
          and status in ('open', 'paused')
    ) then
        raise exception 'SLOT_ALREADY_IN_USE';
    end if;

    if exists (
        select 1 from public.campaigns
        where owner_id = v_uid
          and lower(package_name) = lower(v_clean_pkg)
          and status in ('open', 'paused')
    ) then
        raise exception 'PACKAGE_ALREADY_ACTIVE';
    end if;

    -- 5. Enforce standardized >= minimum balance after deducting total upfront posting fees
    v_balance_after := coalesce(v_profile.credits, 0);
    if v_balance_after < v_required_credits then
        if v_target_cost > 0 and v_balance_after >= (v_slot1_cost + v_min_credits) then
            raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần tối thiểu % Credit (gồm % Credit phí đăng và % Credit số dư duy trì)', v_required_credits, v_total_cost, v_min_credits;
        else
            raise exception 'INSUFFICIENT_CREDITS: Cần tối thiểu % Credit (gồm % Credit phí đăng và % Credit số dư duy trì)', v_required_credits, v_total_cost, v_min_credits;
        end if;
    end if;

    -- 6. Deduct upfront posting fees and write transparent credit_logs
    if v_slot1_cost > 0 then
        update public.profiles
        set credits = credits - v_slot1_cost
        where id = v_uid
        returning credits into v_balance_after;

        insert into public.credit_logs (user_id, amount, action, note, balance, created_at)
        values (
            v_uid,
            -v_slot1_cost,
            'post_app',
            format('Phí đăng ứng dụng "%s" tại Slot 1 (-%s Credit)', v_clean_name, v_slot1_cost),
            v_balance_after,
            v_now
        );
    end if;

    if v_target_cost > 0 then
        update public.profiles
        set credits = credits - v_target_cost
        where id = v_uid
        returning credits into v_balance_after;

        insert into public.credit_logs (user_id, amount, action, note, balance, created_at)
        values (
            v_uid,
            -v_target_cost,
            'target_upgrade',
            format('Nâng cấp mục tiêu %s Test/ngày cho "%s" (-%s Credit)', v_target, v_clean_name, v_target_cost),
            v_balance_after,
            case when v_slot1_cost > 0 then v_now + interval '1 microsecond' else v_now end
        );
    end if;

    -- 7. Insert campaign with snapshots & record receipt
    perform set_config('app.campaign_v2_bypass', 'true', true);

    insert into public.campaigns (
        owner_id,
        app_name,
        package_name,
        play_url,
        testing_url,
        description,
        target_testers,
        start_date,
        status,
        created_at,
        icon_url,
        test_username,
        test_password,
        slot_number,
        slot_1_fee_paid,
        target_fee_paid,
        creation_policy_id
    ) values (
        v_uid,
        v_clean_name,
        v_clean_pkg,
        v_clean_play_url,
        v_clean_test_url,
        v_clean_desc,
        v_target,
        v_vn_today,
        'open',
        v_now,
        v_clean_icon,
        v_clean_user,
        v_clean_pass,
        v_slot,
        v_slot1_cost,
        v_target_cost,
        v_policy.id
    )
    returning id into v_campaign_id;

    perform set_config('app.campaign_v2_bypass', 'false', true);

    insert into public.campaign_creation_receipts (
        user_id,
        client_request_id,
        campaign_id,
        created_campaign_id,
        package_name,
        slot_number,
        target_testers,
        slot_1_cost,
        target_cost,
        total_cost,
        min_credits_required,
        balance_after,
        quote_token,
        canonical_token,
        business_policy_id,
        payload_hash,
        created_at
    ) values (
        v_uid,
        p_client_request_id,
        v_campaign_id,
        v_campaign_id,
        v_clean_pkg,
        v_slot,
        v_target,
        v_slot1_cost,
        v_target_cost,
        v_total_cost,
        v_min_credits,
        v_balance_after,
        p_expected_revision,
        v_quote_token,
        v_policy.id,
        v_payload_hash,
        v_now
    )
    returning id into v_receipt_id;

    return jsonb_build_object(
        'success', true,
        'receipt_id', v_receipt_id,
        'client_request_id', p_client_request_id,
        'campaign_id', v_campaign_id,
        'slot_number', v_slot,
        'target_testers', v_target,
        'slot_1_cost', v_slot1_cost,
        'target_cost', v_target_cost,
        'charged_credits', v_total_cost,
        'min_credits_required', v_min_credits,
        'balance_after', v_balance_after,
        'replayed', false
    );
end;
$$;

-- ============================================================================
-- 5. PERMISSIONS & MINIMAL GRANTS
-- ============================================================================

REVOKE ALL ON FUNCTION public._get_business_policy_revision() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._get_business_policy_revision() TO service_role;

REVOKE ALL ON FUNCTION public._format_business_policy_json(public.business_policies) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._format_business_policy_json(public.business_policies) TO service_role;

REVOKE ALL ON FUNCTION public._format_business_policy_public_json(public.business_policies) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._format_business_policy_public_json(public.business_policies) TO service_role;

REVOKE ALL ON FUNCTION public._get_business_quote_token(text, public.business_policies, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._get_business_quote_token(text, public.business_policies, jsonb) TO service_role;

REVOKE ALL ON FUNCTION public.get_business_quote(text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_business_quote(text, jsonb) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_schedule_business_policy(
    date, text, text,
    integer, integer, integer, integer, integer,
    integer, integer, integer, integer, integer,
    integer, integer, integer, integer, integer,
    uuid,
    integer, integer, integer, integer
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_schedule_business_policy(
    date, text, text,
    integer, integer, integer, integer, integer,
    integer, integer, integer, integer, integer,
    integer, integer, integer, integer, integer,
    uuid,
    integer, integer, integer, integer
) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public._can_caller_insert_campaign() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public._can_caller_insert_campaign() TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.trg_campaigns_guard_financial_snapshots() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.trg_campaigns_guard_financial_snapshots() TO PUBLIC, anon, authenticated, service_role;

REVOKE ALL ON FUNCTION public.handle_campaign_target_cost() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.handle_campaign_target_cost() TO PUBLIC, anon, authenticated, service_role;

REVOKE ALL ON FUNCTION public.create_campaign_v2(
    text, text, text, text, text, integer, text, text, text, integer, uuid, integer, integer, text
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.create_campaign_v2(
    text, text, text, text, text, integer, text, text, text, integer, uuid, integer, integer, text
) TO authenticated, service_role;

COMMIT;
