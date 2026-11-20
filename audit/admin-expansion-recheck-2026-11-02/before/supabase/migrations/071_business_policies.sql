-- ============================================================================
-- Migration 071: Business Policies Foundation & Consumers
-- Staged outside active migrations until integration
-- ============================================================================

-- ============================================================================
-- 1. BUSINESS POLICIES STORAGE & AUDIT TABLES
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.business_policies (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    effective_from date NOT NULL UNIQUE,
    welcome_credits integer NOT NULL CHECK (welcome_credits >= 0 AND welcome_credits <= 10000),
    penalty_tier1_pct integer NOT NULL CHECK (penalty_tier1_pct >= 0 AND penalty_tier1_pct <= 100),
    penalty_tier2_pct integer NOT NULL CHECK (penalty_tier2_pct >= 0 AND penalty_tier2_pct <= 100),
    penalty_tier3_pct integer NOT NULL CHECK (penalty_tier3_pct >= 0 AND penalty_tier3_pct <= 100),
    penalty_tier4_pct integer NOT NULL CHECK (penalty_tier4_pct >= 0 AND penalty_tier4_pct <= 100),
    CONSTRAINT chk_business_policy_penalty_tiers CHECK (
        penalty_tier1_pct <= penalty_tier2_pct AND
        penalty_tier2_pct <= penalty_tier3_pct AND
        penalty_tier3_pct <= penalty_tier4_pct
    ),
    push_fee integer NOT NULL CHECK (push_fee >= 1 AND push_fee <= 10000),
    slot_fee_2 integer NOT NULL CHECK (slot_fee_2 >= 1 AND slot_fee_2 <= 10000),
    slot_fee_3 integer NOT NULL CHECK (slot_fee_3 >= 1 AND slot_fee_3 <= 10000),
    slot_fee_4_plus integer NOT NULL CHECK (slot_fee_4_plus >= 1 AND slot_fee_4_plus <= 10000),
    slot_renewal_days integer NOT NULL CHECK (slot_renewal_days >= 1 AND slot_renewal_days <= 365),
    report_provisional_reward integer NOT NULL CHECK (report_provisional_reward >= 0 AND report_provisional_reward <= 1000),
    report_review_tester_bonus integer NOT NULL CHECK (report_review_tester_bonus >= 0 AND report_review_tester_bonus <= 1000),
    report_review_owner_bonus integer NOT NULL CHECK (report_review_owner_bonus >= 0 AND report_review_owner_bonus <= 1000),
    app_update_reward_per_claim integer NOT NULL CHECK (app_update_reward_per_claim >= 1 AND app_update_reward_per_claim <= 1000),
    app_update_max_claims integer NOT NULL CHECK (app_update_max_claims >= 1 AND app_update_max_claims <= 1000),
    operator_note text NOT NULL CHECK (length(btrim(operator_note)) >= 1 AND length(btrim(operator_note)) <= 1000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS public.business_policy_changes (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_id bigint NOT NULL,
    operation text NOT NULL CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
    old_row jsonb,
    new_row jsonb,
    operator_note text NOT NULL,
    session_role text NOT NULL DEFAULT session_user,
    invoked_role text,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor_id uuid
);

CREATE TABLE IF NOT EXISTS public.slot_purchase_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    client_request_id uuid NOT NULL,
    slot_number integer NOT NULL CHECK (slot_number >= 2 AND slot_number <= 6),
    cost_credits integer NOT NULL CHECK (cost_credits >= 0),
    balance_after integer NOT NULL CHECK (balance_after >= 0),
    expires_at timestamptz NOT NULL,
    renewal_days integer NOT NULL DEFAULT 30 CHECK (renewal_days >= 1 AND renewal_days <= 365),
    quote_token text,
    canonical_token text,
    business_policy_id bigint REFERENCES public.business_policies(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_slot_purchase_receipts_user_request UNIQUE (user_id, client_request_id)
);

ALTER TABLE public.slot_purchase_receipts
    ADD COLUMN IF NOT EXISTS renewal_days integer NOT NULL DEFAULT 30 CHECK (renewal_days >= 1 AND renewal_days <= 365),
    ADD COLUMN IF NOT EXISTS quote_token text,
    ADD COLUMN IF NOT EXISTS canonical_token text,
    ADD COLUMN IF NOT EXISTS business_policy_id bigint REFERENCES public.business_policies(id);

CREATE TABLE IF NOT EXISTS public.business_policy_admin_requests (
    id uuid NOT NULL,
    actor_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    operation text NOT NULL CHECK (operation IN ('schedule', 'cancel')),
    payload_hash text NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_id, operation, id)
);

CREATE INDEX IF NOT EXISTS idx_business_policy_admin_requests_actor
    ON public.business_policy_admin_requests (actor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_slot_purchase_receipts_user
    ON public.slot_purchase_receipts (user_id);

CREATE INDEX IF NOT EXISTS idx_business_policy_changes_cursor
    ON public.business_policy_changes (id DESC);

-- ============================================================================
-- 2. AUDIT & INTEGRITY TRIGGERS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.audit_business_policy()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_actor uuid := auth.uid();
begin
    if tg_op = 'INSERT' then
        insert into public.business_policy_changes (
            policy_id, operation, old_row, new_row, operator_note,
            session_role, invoked_role, changed_at, actor_id
        ) values (
            new.id, 'INSERT', null, to_jsonb(new), new.operator_note,
            session_user, current_user, clock_timestamp(), v_actor
        );
        return new;
    elsif tg_op = 'UPDATE' then
        insert into public.business_policy_changes (
            policy_id, operation, old_row, new_row, operator_note,
            session_role, invoked_role, changed_at, actor_id
        ) values (
            new.id, 'UPDATE', to_jsonb(old), to_jsonb(new), new.operator_note,
            session_user, current_user, clock_timestamp(), v_actor
        );
        return new;
    elsif tg_op = 'DELETE' then
        insert into public.business_policy_changes (
            policy_id, operation, old_row, new_row, operator_note,
            session_role, invoked_role, changed_at, actor_id
        ) values (
            old.id, 'DELETE', to_jsonb(old), null, old.operator_note,
            session_user, current_user, clock_timestamp(), v_actor
        );
        return old;
    end if;
    return null;
end;
$$;

CREATE OR REPLACE FUNCTION public.guard_business_policy()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if current_setting('test.allow_policy_retroactive_write', true) = 'true' then
        return coalesce(new, old);
    end if;

    if tg_op = 'INSERT' then
        if new.effective_from <= v_today and new.effective_from <> date '-infinity' then
            raise exception 'BUSINESS_POLICY_FUTURE_DATE_REQUIRED';
        end if;
        return new;
    elsif tg_op = 'UPDATE' then
        if old.effective_from <= v_today then
            raise exception 'BUSINESS_POLICY_CANNOT_MODIFY_ACTIVE_OR_PAST';
        end if;
        if new.effective_from <= v_today then
            raise exception 'BUSINESS_POLICY_FUTURE_DATE_REQUIRED';
        end if;
        new.updated_at := clock_timestamp();
        return new;
    elsif tg_op = 'DELETE' then
        if old.effective_from <= v_today then
            raise exception 'BUSINESS_POLICY_CANNOT_DELETE_ACTIVE_OR_PAST';
        end if;
        return old;
    end if;
    return null;
end;
$$;

CREATE OR REPLACE FUNCTION public.reject_business_policy_history_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
begin
    raise exception 'BUSINESS_POLICY_HISTORY_READ_ONLY';
end;
$$;

DROP TRIGGER IF EXISTS trg_audit_business_policy ON public.business_policies;
CREATE TRIGGER trg_audit_business_policy
    AFTER INSERT OR UPDATE OR DELETE ON public.business_policies
    FOR EACH ROW EXECUTE FUNCTION public.audit_business_policy();

DROP TRIGGER IF EXISTS trg_guard_business_policy ON public.business_policies;
CREATE TRIGGER trg_guard_business_policy
    BEFORE INSERT OR UPDATE OR DELETE ON public.business_policies
    FOR EACH ROW EXECUTE FUNCTION public.guard_business_policy();

DROP TRIGGER IF EXISTS trg_no_truncate_business_policies ON public.business_policies;
CREATE TRIGGER trg_no_truncate_business_policies
    BEFORE TRUNCATE ON public.business_policies
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_business_policy_history_change();

DROP TRIGGER IF EXISTS trg_no_change_business_policy_history ON public.business_policy_changes;
CREATE TRIGGER trg_no_change_business_policy_history
    BEFORE UPDATE OR DELETE ON public.business_policy_changes
    FOR EACH ROW EXECUTE FUNCTION public.reject_business_policy_history_change();

DROP TRIGGER IF EXISTS trg_no_truncate_business_policy_history ON public.business_policy_changes;
CREATE TRIGGER trg_no_truncate_business_policy_history
    BEFORE TRUNCATE ON public.business_policy_changes
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_business_policy_history_change();

-- Seed baseline default policy
SET LOCAL test.allow_policy_retroactive_write = 'true';

INSERT INTO public.business_policies (
    effective_from,
    welcome_credits,
    penalty_tier1_pct,
    penalty_tier2_pct,
    penalty_tier3_pct,
    penalty_tier4_pct,
    push_fee,
    slot_fee_2,
    slot_fee_3,
    slot_fee_4_plus,
    slot_renewal_days,
    report_provisional_reward,
    report_review_tester_bonus,
    report_review_owner_bonus,
    app_update_reward_per_claim,
    app_update_max_claims,
    operator_note
) VALUES (
    date '-infinity',
    100,
    1, 2, 5, 10,
    100,
    50, 100, 200, 30,
    10, 2, 2,
    6, 20,
    'Baseline default business policy'
) ON CONFLICT (effective_from) DO NOTHING;

RESET test.allow_policy_retroactive_write;

-- ============================================================================
-- 3. REVISION CAS & FORMATTERS
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
            p.slot_fee_2::text || ':' ||
            p.slot_fee_3::text || ':' ||
            p.slot_fee_4_plus::text || ':' ||
            p.slot_renewal_days::text || ':' ||
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
        'daily_penalty_tiers', jsonb_build_object(
            'tier1_pct', p.penalty_tier1_pct,
            'tier2_pct', p.penalty_tier2_pct,
            'tier3_pct', p.penalty_tier3_pct,
            'tier4_pct', p.penalty_tier4_pct
        ),
        'push_fee', p.push_fee,
        'slot_fees', jsonb_build_object(
            'slot_2', p.slot_fee_2,
            'slot_3', p.slot_fee_3,
            'slot_4_plus', p.slot_fee_4_plus,
            'renewal_days', p.slot_renewal_days
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
        'daily_penalty_tiers', jsonb_build_object(
            'tier1_pct', p.penalty_tier1_pct,
            'tier2_pct', p.penalty_tier2_pct,
            'tier3_pct', p.penalty_tier3_pct,
            'tier4_pct', p.penalty_tier4_pct
        ),
        'push_fee', p.push_fee,
        'slot_fees', jsonb_build_object(
            'slot_2', p.slot_fee_2,
            'slot_3', p.slot_fee_3,
            'slot_4_plus', p.slot_fee_4_plus,
            'renewal_days', p.slot_renewal_days
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

-- ============================================================================
-- 4. ADMIN BUSINESS POLICY RPCS
-- ============================================================================

CREATE OR REPLACE FUNCTION public.admin_get_business_policy_config(p_future_limit integer DEFAULT 20)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_revision text;
    v_active_policy public.business_policies;
    v_future_policies jsonb;
    v_limit integer := least(greatest(coalesce(p_future_limit, 20), 1), 100);
    v_has_more boolean := false;
    v_next_cursor text := null;
begin
    perform public._assert_daily_task_admin();

    select p.* into v_active_policy
    from public.business_policies p
    where p.effective_from <= v_today
    order by p.effective_from desc
    limit 1;

    with future_rows as (
        select p.effective_from, p.id, public._format_business_policy_json(p) as policy_json
        from public.business_policies p
        where p.effective_from > v_today
        order by p.effective_from asc, p.id asc
        limit (v_limit + 1)
    ),
    numbered as (
        select fr.effective_from, fr.policy_json, row_number() over (order by fr.effective_from asc, fr.id asc) as row_num
        from future_rows fr
    )
    select
        coalesce(jsonb_agg(policy_json order by effective_from asc) filter (where row_num <= v_limit), '[]'::jsonb),
        coalesce(bool_or(row_num > v_limit), false),
        max(case when row_num = v_limit then to_char(effective_from, 'YYYY-MM-DD') else null end)
    into v_future_policies, v_has_more, v_next_cursor
    from numbered;

    v_revision := public._get_business_policy_revision();

    return jsonb_build_object(
        'today', to_char(v_today, 'YYYY-MM-DD'),
        'timezone', 'Asia/Ho_Chi_Minh',
        'revision', v_revision,
        'active_policy', case when v_active_policy.id is not null then public._format_business_policy_json(v_active_policy) else null end,
        'future_policies', coalesce(v_future_policies, '[]'::jsonb),
        'future_has_more', v_has_more,
        'future_next_cursor', case when v_has_more then v_next_cursor else null end
    );
end;
$$;

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
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_actor uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
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
            'penalty_tier1_pct', p_penalty_tier1_pct,
            'penalty_tier2_pct', p_penalty_tier2_pct,
            'penalty_tier3_pct', p_penalty_tier3_pct,
            'penalty_tier4_pct', p_penalty_tier4_pct,
            'push_fee', p_push_fee,
            'slot_fee_2', p_slot_fee_2,
            'slot_fee_3', p_slot_fee_3,
            'slot_fee_4_plus', p_slot_fee_4_plus,
            'slot_renewal_days', p_slot_renewal_days,
            'report_provisional_reward', p_report_provisional_reward,
            'report_review_tester_bonus', p_report_review_tester_bonus,
            'report_review_owner_bonus', p_report_review_owner_bonus,
            'app_update_reward_per_claim', p_app_update_reward_per_claim,
            'app_update_max_claims', p_app_update_max_claims,
            'operator_note', btrim(coalesce(p_operator_note, ''))
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

    -- 4. Date and bounds validation (finite future day bounds)
    if p_effective_from is null or not isfinite(p_effective_from) or p_effective_from <= v_today or p_effective_from > (v_today + interval '3650 days') then
        raise exception 'BUSINESS_POLICY_FUTURE_DATE_REQUIRED';
    end if;

    if p_operator_note is null or length(btrim(p_operator_note)) not between 1 and 1000 then
        raise exception 'BUSINESS_POLICY_NOTE_REQUIRED';
    end if;

    if p_welcome_credits is null or p_welcome_credits not between 0 and 10000 then
        raise exception 'BUSINESS_POLICY_INVALID_WELCOME_CREDITS';
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

    if p_slot_fee_2 is null or p_slot_fee_2 not between 1 and 10000 or
       p_slot_fee_3 is null or p_slot_fee_3 not between 1 and 10000 or
       p_slot_fee_4_plus is null or p_slot_fee_4_plus not between 1 and 10000 or
       p_slot_renewal_days is null or p_slot_renewal_days not between 1 and 365
    then
        raise exception 'BUSINESS_POLICY_INVALID_SLOT_FEES';
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
        penalty_tier1_pct,
        penalty_tier2_pct,
        penalty_tier3_pct,
        penalty_tier4_pct,
        push_fee,
        slot_fee_2,
        slot_fee_3,
        slot_fee_4_plus,
        slot_renewal_days,
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
        p_penalty_tier1_pct,
        p_penalty_tier2_pct,
        p_penalty_tier3_pct,
        p_penalty_tier4_pct,
        p_push_fee,
        p_slot_fee_2,
        p_slot_fee_3,
        p_slot_fee_4_plus,
        p_slot_renewal_days,
        p_report_provisional_reward,
        p_report_review_tester_bonus,
        p_report_review_owner_bonus,
        p_app_update_reward_per_claim,
        p_app_update_max_claims,
        p_operator_note,
        clock_timestamp()
    )
    on conflict (effective_from) do update set
        welcome_credits = excluded.welcome_credits,
        penalty_tier1_pct = excluded.penalty_tier1_pct,
        penalty_tier2_pct = excluded.penalty_tier2_pct,
        penalty_tier3_pct = excluded.penalty_tier3_pct,
        penalty_tier4_pct = excluded.penalty_tier4_pct,
        push_fee = excluded.push_fee,
        slot_fee_2 = excluded.slot_fee_2,
        slot_fee_3 = excluded.slot_fee_3,
        slot_fee_4_plus = excluded.slot_fee_4_plus,
        slot_renewal_days = excluded.slot_renewal_days,
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
        using v_actor, 'schedule_business_policy', 'business_policy', to_char(p_effective_from, 'YYYY-MM-DD'), p_operator_note, v_old_row, v_new_row, p_request_id;
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

CREATE OR REPLACE FUNCTION public.admin_cancel_business_policy(
    p_effective_from date,
    p_operator_note text,
    p_expected_revision text,
    p_request_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_actor uuid;
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_current_rev text;
    v_payload_hash text;
    v_existing_req record;
    v_old_row jsonb;
    v_result jsonb;
begin
    -- 1. Auth check first
    v_actor := public._assert_daily_task_admin();

    -- 2. Request replay check BEFORE CAS
    if p_request_id is not null then
        v_payload_hash := md5(jsonb_build_object(
            'effective_from', to_char(p_effective_from, 'YYYY-MM-DD'),
            'operator_note', btrim(coalesce(p_operator_note, ''))
        )::text);

        select * into v_existing_req
        from public.business_policy_admin_requests
        where actor_id = v_actor and operation = 'cancel' and id = p_request_id;

        if v_existing_req.id is not null then
            if v_existing_req.payload_hash <> v_payload_hash then
                raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với nội dung khác';
            end if;
            return v_existing_req.result;
        end if;
    end if;

    perform pg_advisory_xact_lock(hashtextextended('business_policy_schedule', 0));

    -- Re-check request ledger after lock
    if p_request_id is not null then
        select * into v_existing_req
        from public.business_policy_admin_requests
        where actor_id = v_actor and operation = 'cancel' and id = p_request_id;

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

    -- 4. Date validation (finite future day bounds)
    if p_effective_from is null or not isfinite(p_effective_from) or p_effective_from <= v_today or p_effective_from > (v_today + interval '3650 days') then
        raise exception 'BUSINESS_POLICY_FUTURE_DATE_REQUIRED';
    end if;

    if p_operator_note is null or length(btrim(p_operator_note)) not between 1 and 1000 then
        raise exception 'BUSINESS_POLICY_NOTE_REQUIRED';
    end if;

    select to_jsonb(p) into v_old_row
    from public.business_policies p
    where p.effective_from = p_effective_from;

    if v_old_row is null then
        raise exception 'BUSINESS_POLICY_NOT_FOUND';
    end if;

    -- Store cancellation note on row before delete so trigger captures note
    update public.business_policies
    set operator_note = p_operator_note, updated_at = clock_timestamp()
    where effective_from = p_effective_from;

    delete from public.business_policies
    where effective_from = p_effective_from;

    -- Record in 070 common admin audit table if function exists
    if exists (select 1 from pg_proc where proname = 'record_admin_action_event' and pronamespace = 'public'::regnamespace) then
        execute 'select public.record_admin_action_event($1, $2, $3, $4, $5, $6::jsonb, $7::jsonb, $8)'
        using v_actor, 'cancel_business_policy', 'business_policy', to_char(p_effective_from, 'YYYY-MM-DD'), p_operator_note, v_old_row, null::jsonb, p_request_id;
    end if;

    v_result := public.admin_get_business_policy_config();

    if p_request_id is not null then
        insert into public.business_policy_admin_requests (
            id, actor_id, operation, payload_hash, result, created_at
        ) values (
            p_request_id, v_actor, 'cancel', v_payload_hash, v_result, clock_timestamp()
        ) on conflict (actor_id, operation, id) do nothing;
    end if;

    return v_result;
end;
$$;

CREATE OR REPLACE FUNCTION public.admin_list_business_policies(
    p_limit integer DEFAULT 20,
    p_cursor_date date DEFAULT NULL::date
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_active public.business_policies;
    v_future jsonb;
    v_limit integer := least(greatest(coalesce(p_limit, 20), 1), 100);
    v_has_more boolean := false;
    v_next_cursor text := null;
begin
    perform public._assert_daily_task_admin();

    select p.* into v_active
    from public.business_policies p
    where p.effective_from <= v_today
    order by p.effective_from desc
    limit 1;

    with future_rows as (
        select p.effective_from, p.id, public._format_business_policy_json(p) as policy_json
        from public.business_policies p
        where p.effective_from > v_today
          and (p_cursor_date is null or p.effective_from > p_cursor_date)
        order by p.effective_from asc, p.id asc
        limit (v_limit + 1)
    ),
    numbered as (
        select fr.effective_from, fr.policy_json, row_number() over (order by fr.effective_from asc, fr.id asc) as row_num
        from future_rows fr
    )
    select
        coalesce(jsonb_agg(policy_json order by effective_from asc) filter (where row_num <= v_limit), '[]'::jsonb),
        coalesce(bool_or(row_num > v_limit), false),
        max(case when row_num = v_limit then to_char(effective_from, 'YYYY-MM-DD') else null end)
    into v_future, v_has_more, v_next_cursor
    from numbered;

    return jsonb_build_object(
        'active_policy', case when v_active.id is not null then public._format_business_policy_json(v_active) else null end,
        'future_policies', coalesce(v_future, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', case when v_has_more then v_next_cursor else null end
    );
end;
$$;

CREATE OR REPLACE FUNCTION public.admin_get_business_policy_history(
    p_limit integer DEFAULT 20,
    p_cursor_id bigint DEFAULT NULL::bigint
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_limit integer := least(greatest(coalesce(p_limit, 20), 1), 100);
    v_items jsonb;
    v_has_more boolean := false;
    v_next_id bigint := null;
begin
    perform public._assert_daily_task_admin();

    with candidates as (
        select c.*
        from public.business_policy_changes c
        where (p_cursor_id is null or c.id < p_cursor_id)
        order by c.id desc
        limit v_limit + 1
    ),
    counted as (
        select candidates.*, count(*) over() as total_found
        from candidates
    ),
    page_items as (
        select *
        from counted
        order by id desc
        limit v_limit
    ),
    last_item as (
        select id, total_found
        from page_items
        order by id asc
        limit 1
    )
    select
        coalesce(jsonb_agg(jsonb_build_object(
            'id', h.id,
            'policy_id', h.policy_id,
            'operation', h.operation,
            'operator_note', h.operator_note,
            'actor_id', h.actor_id,
            'actor_name', prof.display_name,
            'actor_email', prof.email,
            'session_role', h.session_role,
            'invoked_role', h.invoked_role,
            'changed_at', to_char(h.changed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'old_row', h.old_row,
            'new_row', h.new_row
        ) order by h.id desc), '[]'::jsonb),
        coalesce((select total_found > v_limit from last_item), false),
        (select case when total_found > v_limit then id else null end from last_item)
    into v_items, v_has_more, v_next_id
    from page_items h
    left join public.profiles prof on prof.id = h.actor_id;

    return jsonb_build_object(
        'items', coalesce(v_items, '[]'::jsonb),
        'has_more', coalesce(v_has_more, false),
        'next_cursor', v_next_id,
        'next_cursor_id', v_next_id
    );
end;
$$;

-- ============================================================================
-- 5. PUBLIC AUTHENTICATED POLICY & QUOTE RPCS
-- ============================================================================

CREATE OR REPLACE FUNCTION public._get_effective_business_policy_row(p_date date DEFAULT NULL::date)
RETURNS public.business_policies
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_date date := coalesce(p_date, (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date);
    v_policy public.business_policies;
begin
    if not isfinite(v_date) then
        raise exception 'BUSINESS_POLICY_INVALID_DATE';
    end if;

    select p.* into v_policy
    from public.business_policies p
    where p.effective_from <= v_date
    order by p.effective_from desc
    limit 1;

    if v_policy.id is null then
        raise exception 'BUSINESS_POLICY_NOT_FOUND';
    end if;

    return v_policy;
end;
$$;

CREATE OR REPLACE FUNCTION public.get_effective_business_policy(p_date date DEFAULT NULL::date)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
declare
    v_policy public.business_policies;
begin
    v_policy := public._get_effective_business_policy_row(p_date);
    return public._format_business_policy_public_json(v_policy);
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
            when 'app_update' then
                'app_update:' || p_policy.app_update_reward_per_claim::text || ':' ||
                p_policy.app_update_max_claims::text || ':' ||
                (p_policy.app_update_reward_per_claim * p_policy.app_update_max_claims)::text
            else
                'all:' || p_policy.push_fee::text || ':' ||
                p_policy.slot_fee_2::text || ':' || p_policy.slot_fee_3::text || ':' || p_policy.slot_fee_4_plus::text || ':' ||
                p_policy.slot_renewal_days::text || ':' ||
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
            'push_fee', v_policy.push_fee,
            'slot_fees', jsonb_build_object(
                'slot_2', v_policy.slot_fee_2,
                'slot_3', v_policy.slot_fee_3,
                'slot_4_plus', v_policy.slot_fee_4_plus,
                'renewal_days', v_policy.slot_renewal_days
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
        raise exception 'INVALID_QUOTE_TYPE: Must be push, slot, app_update, or all';
    end if;
end;
$$;

-- ============================================================================
-- 6. SECURITY: RLS & MINIMAL GRANTS
-- ============================================================================

ALTER TABLE public.business_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.business_policies NO FORCE ROW LEVEL SECURITY;

ALTER TABLE public.business_policy_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.business_policy_changes NO FORCE ROW LEVEL SECURITY;

ALTER TABLE public.slot_purchase_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.slot_purchase_receipts NO FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "business_policies_select_all" ON public.business_policies;

DROP POLICY IF EXISTS "slot_purchase_receipts_select_own" ON public.slot_purchase_receipts;
CREATE POLICY "slot_purchase_receipts_select_own" ON public.slot_purchase_receipts
    FOR SELECT TO authenticated USING (user_id = auth.uid());

-- Revoke all table permissions by default
ALTER TABLE public.business_policy_admin_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.business_policy_admin_requests NO FORCE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.business_policies FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE public.business_policies TO service_role;

REVOKE ALL ON TABLE public.business_policy_changes FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT ON TABLE public.business_policy_changes TO service_role;

REVOKE ALL ON TABLE public.slot_purchase_receipts FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.slot_purchase_receipts TO authenticated;
GRANT ALL ON TABLE public.slot_purchase_receipts TO service_role;

REVOKE ALL ON TABLE public.business_policy_admin_requests FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.business_policy_admin_requests TO service_role;

-- Function permissions
REVOKE ALL ON FUNCTION public._get_business_policy_revision() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._get_business_policy_revision() TO service_role;

REVOKE ALL ON FUNCTION public._format_business_policy_json(public.business_policies) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._format_business_policy_json(public.business_policies) TO service_role;

REVOKE ALL ON FUNCTION public._format_business_policy_public_json(public.business_policies) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._format_business_policy_public_json(public.business_policies) TO service_role;

REVOKE ALL ON FUNCTION public._get_effective_business_policy_row(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._get_effective_business_policy_row(date) TO service_role;

REVOKE ALL ON FUNCTION public.get_effective_business_policy(date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_effective_business_policy(date) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_business_quote(text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_business_quote(text, jsonb) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public._get_business_quote_token(text, public.business_policies, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._get_business_quote_token(text, public.business_policies, jsonb) TO service_role;

REVOKE ALL ON FUNCTION public.admin_get_business_policy_config(integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_business_policy_config(integer) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_schedule_business_policy(date, text, text, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_schedule_business_policy(date, text, text, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_cancel_business_policy(date, text, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_cancel_business_policy(date, text, text, uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_list_business_policies(integer, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_list_business_policies(integer, date) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_get_business_policy_history(integer, bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_business_policy_history(integer, bigint) TO authenticated, service_role;

-- ============================================================================
-- 7. FINANCIAL CONSUMERS: WELCOME CREDITS, PUSH CAMPAIGN & USER SLOTS
-- ============================================================================

-- 7.1 handle_new_user (Welcome credits consumer)
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
declare
    v_vn_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_policy public.business_policies;
    v_welcome_credits integer := 100;
    v_inserted_id uuid;
begin
    v_policy := public._get_effective_business_policy_row(v_vn_today);
    if v_policy.id is not null then
        v_welcome_credits := v_policy.welcome_credits;
    end if;

    insert into public.profiles (
        id, email, display_name, avatar_url, credits,
        last_active_date, missed_streak, role, is_locked
    )
    values (
        new.id,
        new.email,
        coalesce(
            new.raw_user_meta_data ->> 'full_name',
            new.raw_user_meta_data ->> 'name',
            new.raw_user_meta_data ->> 'display_name',
            split_part(coalesce(new.email, ''), '@', 1)
        ),
        coalesce(
            new.raw_user_meta_data ->> 'avatar_url',
            new.raw_user_meta_data ->> 'picture'
        ),
        v_welcome_credits,
        v_vn_today,
        0,
        'member',
        false
    )
    on conflict (id) do nothing
    returning id into v_inserted_id;

    if v_inserted_id is not null then
        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            new.id,
            v_welcome_credits,
            'welcome',
            format('Thưởng %s Credit khi mở tài khoản', v_welcome_credits),
            v_welcome_credits
        )
        on conflict do nothing;
    end if;

    return new;
end;
$$;

-- 7.2 Relax campaign_push_receipts cost check to allow configurable push fees
ALTER TABLE public.campaign_push_receipts
    DROP CONSTRAINT IF EXISTS campaign_push_receipts_cost_credits_check;

ALTER TABLE public.campaign_push_receipts
    ADD CONSTRAINT campaign_push_receipts_cost_credits_check
    CHECK (cost_credits >= 1 AND cost_credits <= 10000);

-- 7.3 push_campaign_v2 (Financial push operation with CAS & RequestID replay)
CREATE OR REPLACE FUNCTION public.push_campaign_v2(
    p_campaign_id uuid,
    p_client_request_id uuid,
    p_expected_cost integer DEFAULT NULL::integer,
    p_expected_revision text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
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
    v_policy            public.business_policies;
    v_cost              int;
    v_current_rev       text;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    if p_campaign_id is null then
        raise exception 'INVALID_CAMPAIGN_ID';
    end if;

    -- Replay check before locking
    select * into v_existing_receipt
    from public.campaign_push_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if coalesce(v_existing_receipt.target_campaign_id, v_existing_receipt.campaign_id) is distinct from p_campaign_id then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với campaign khác';
        end if;
        if p_expected_cost is not null and v_existing_receipt.cost_credits <> p_expected_cost then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với giá khác';
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

    -- Consistent lock ordering: campaigns, then profiles
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

    -- Re-check receipt under lock
    select * into v_existing_receipt
    from public.campaign_push_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if coalesce(v_existing_receipt.target_campaign_id, v_existing_receipt.campaign_id) is distinct from p_campaign_id then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với campaign khác';
        end if;
        if p_expected_cost is not null and v_existing_receipt.cost_credits <> p_expected_cost then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng lặp với giá khác';
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

    if v_camp.owner_id is distinct from v_uid then
        raise exception 'NOT_OWNER';
    end if;

    if coalesce(v_profile.is_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    v_now := clock_timestamp();
    v_business_date := (v_now at time zone 'Asia/Ho_Chi_Minh')::date;
    v_expires_at := (date_trunc('day', v_now at time zone 'Asia/Ho_Chi_Minh') + interval '1 day') at time zone 'Asia/Ho_Chi_Minh';

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

    v_policy := public._get_effective_business_policy_row(v_business_date);
    v_cost := v_policy.push_fee;
    v_current_rev := public._get_business_quote_token('push', v_policy);

    -- Fresh purchase requires explicit expected cost and quote token
    if p_expected_cost is null or p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'BUSINESS_POLICY_CONFLICT: Chi phí dự kiến và mã báo giá là bắt buộc đối với giao dịch v2';
    end if;

    if p_expected_cost <> v_cost then
        raise exception 'BUSINESS_POLICY_PRICE_MISMATCH: Phí đẩy ứng dụng đã thay đổi (hiện tại: %s Credit)', v_cost;
    end if;

    if p_expected_revision <> v_current_rev and
       p_expected_revision <> public._get_business_quote_token('all', v_policy) then
        raise exception 'BUSINESS_POLICY_CONFLICT: Báo giá đẩy ứng dụng đã hết hạn hoặc thay đổi. Vui lòng lấy lại báo giá.';
    end if;

    if coalesce(v_profile.credits, 0) < v_cost then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    perform pg_advisory_xact_lock(hashtext('campaign_push_order_lock'));

    v_push_order := nextval('public.campaign_push_order_seq');

    update public.profiles
    set credits = credits - v_cost
    where id = v_uid
    returning credits into v_balance_after;

    v_note := format('Đẩy ứng dụng "%s" lên đầu danh sách (hạn đến hết %s)',
                     coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
                     to_char(v_business_date, 'DD/MM/YYYY'));

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (v_uid, -v_cost, 'campaign_push', v_note, v_balance_after)
    returning id into v_credit_log_id;

    perform set_config('app.allow_push_metadata_write', 'true', true);

    update public.campaigns
    set push_business_date = v_business_date,
        push_expires_at = v_expires_at,
        push_order = v_push_order
    where id = p_campaign_id;

    perform set_config('app.allow_push_metadata_write', 'false', true);

    insert into public.campaign_push_receipts (
        user_id, campaign_id, target_campaign_id, client_request_id,
        cost_credits, balance_after, business_date, pushed_at,
        expires_at, push_order, credit_log_id
    ) values (
        v_uid, p_campaign_id, p_campaign_id, p_client_request_id,
        v_cost, v_balance_after, v_business_date, v_now,
        v_expires_at, v_push_order, v_credit_log_id
    )
    returning id into v_receipt_id;

    return jsonb_build_object(
        'success', true,
        'receipt_id', v_receipt_id,
        'client_request_id', p_client_request_id,
        'campaign_id', p_campaign_id,
        'charged_credits', v_cost,
        'balance_after', v_balance_after,
        'business_date', to_char(v_business_date, 'YYYY-MM-DD'),
        'expires_at', to_char(v_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_order', v_push_order,
        'replayed', false
    );
end;
$$;

-- 7.4 push_campaign (Legacy wrapper with fail-closed check)
CREATE OR REPLACE FUNCTION public.push_campaign(p_campaign_id uuid, p_client_request_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
declare
    v_uid uuid := auth.uid();
    v_existing_receipt record;
    v_policy public.business_policies;
    v_token text;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    -- Prior replay across policy change succeeds
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

    v_policy := public._get_effective_business_policy_row();
    if v_policy.push_fee <> 100 then
        raise exception 'CLIENT_UPDATE_REQUIRED: Phí đẩy ứng dụng đã thay đổi (%s Credit). Vui lòng cập nhật ứng dụng.', v_policy.push_fee;
    end if;

    v_token := public._get_business_quote_token('push', v_policy);
    return public.push_campaign_v2(p_campaign_id, p_client_request_id, 100, v_token);
end;
$$;

-- 7.5 unlock_or_renew_slot_v2 (Financial slot operation with CAS & RequestID replay)
CREATE OR REPLACE FUNCTION public.unlock_or_renew_slot_v2(
    p_slot_number integer,
    p_client_request_id uuid,
    p_expected_cost integer DEFAULT NULL::integer,
    p_expected_revision text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
declare
    v_user_id uuid := auth.uid();
    v_cost int;
    v_balance int;
    v_locked boolean;
    v_current_expires timestamptz;
    v_prev_expires timestamptz;
    v_new_expires timestamptz;
    v_policy public.business_policies;
    v_renewal_days int;
    v_existing_receipt record;
    v_receipt_id uuid;
    v_current_rev text;
    v_confirmed_token text;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    if p_slot_number < 2 or p_slot_number > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    -- Replay check before CAS
    select * into v_existing_receipt
    from public.slot_purchase_receipts
    where user_id = v_user_id and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.slot_number <> p_slot_number then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng với slot khác';
        end if;
        if p_expected_cost is not null and v_existing_receipt.cost_credits <> p_expected_cost then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng với giá khác';
        end if;
        if p_expected_revision is not null and v_existing_receipt.quote_token is not null then
            if p_expected_revision <> v_existing_receipt.quote_token and
               p_expected_revision is distinct from v_existing_receipt.canonical_token then
                raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng với điều khoản báo giá khác';
            end if;
        end if;
        return jsonb_build_object(
            'success', true,
            'slot_number', v_existing_receipt.slot_number,
            'cost', v_existing_receipt.cost_credits,
            'balance', v_existing_receipt.balance_after,
            'expires_at', to_char(v_existing_receipt.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'renewal_days', coalesce(v_existing_receipt.renewal_days, 30),
            'quote_token', v_existing_receipt.quote_token,
            'replayed', true,
            'receipt_id', v_existing_receipt.id
        );
    end if;

    -- Lock profile
    select credits, coalesce(is_locked, false)
    into v_balance, v_locked
    from public.profiles
    where id = v_user_id
    for update;

    if v_locked = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
    end if;

    -- Re-check receipt after lock
    select * into v_existing_receipt
    from public.slot_purchase_receipts
    where user_id = v_user_id and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.slot_number <> p_slot_number then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng với slot khác';
        end if;
        if p_expected_cost is not null and v_existing_receipt.cost_credits <> p_expected_cost then
            raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng với giá khác';
        end if;
        if p_expected_revision is not null and v_existing_receipt.quote_token is not null then
            if p_expected_revision <> v_existing_receipt.quote_token and
               p_expected_revision is distinct from v_existing_receipt.canonical_token then
                raise exception 'REQUEST_ID_CONFLICT: Yêu cầu bị trùng với điều khoản báo giá khác';
            end if;
        end if;
        return jsonb_build_object(
            'success', true,
            'slot_number', v_existing_receipt.slot_number,
            'cost', v_existing_receipt.cost_credits,
            'balance', v_existing_receipt.balance_after,
            'expires_at', to_char(v_existing_receipt.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'renewal_days', coalesce(v_existing_receipt.renewal_days, 30),
            'quote_token', v_existing_receipt.quote_token,
            'replayed', true,
            'receipt_id', v_existing_receipt.id
        );
    end if;

    v_policy := public._get_effective_business_policy_row();
    v_cost := case
        when p_slot_number = 2 then v_policy.slot_fee_2
        when p_slot_number = 3 then v_policy.slot_fee_3
        else v_policy.slot_fee_4_plus
    end;
    v_renewal_days := v_policy.slot_renewal_days;
    v_current_rev := public._get_business_quote_token('slot', v_policy, jsonb_build_object('slot_number', p_slot_number));

    -- Fresh purchase requires explicit expected cost and quote token
    if p_expected_cost is null or p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'BUSINESS_POLICY_CONFLICT: Chi phí dự kiến và mã báo giá là bắt buộc đối với giao dịch v2';
    end if;

    if p_expected_cost <> v_cost then
        raise exception 'BUSINESS_POLICY_PRICE_MISMATCH: Phí slot đã thay đổi (hiện tại: %s Credit)', v_cost;
    end if;

    if p_expected_revision <> v_current_rev and
       p_expected_revision <> public._get_business_quote_token('all', v_policy) then
        raise exception 'BUSINESS_POLICY_CONFLICT: Báo giá slot đã hết hạn hoặc điều khoản đã thay đổi. Vui lòng lấy lại báo giá.';
    end if;

    if v_balance is null or v_balance < v_cost then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- Slot N (N > 2) chỉ mở được khi Slot N - 1 đang còn hoạt động
    if p_slot_number > 2 then
        select expires_at into v_prev_expires
        from public.user_slots
        where user_id = v_user_id and slot_number = p_slot_number - 1
        for update;

        if v_prev_expires is null or v_prev_expires <= now() then
            raise exception 'PREVIOUS_SLOT_NOT_ACTIVE';
        end if;
    end if;

    -- Lock current slot if exists
    select expires_at into v_current_expires
    from public.user_slots
    where user_id = v_user_id and slot_number = p_slot_number
    for update;

    if v_current_expires is not null and v_current_expires > now() then
        v_new_expires := v_current_expires + (v_renewal_days || ' days')::interval;
    else
        v_new_expires := now() + (v_renewal_days || ' days')::interval;
    end if;

    insert into public.user_slots (user_id, slot_number, unlocked_at, expires_at)
    values (v_user_id, p_slot_number, now(), v_new_expires)
    on conflict (user_id, slot_number)
    do update set
        unlocked_at = now(),
        expires_at = v_new_expires;

    update public.profiles
    set credits = credits - v_cost
    where id = v_user_id
    returning credits into v_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_user_id,
        -v_cost,
        'unlock_slot',
        format('Mở khóa / Gia hạn Slot %s (thời hạn %s ngày)', p_slot_number, v_renewal_days),
        v_balance
    );

    v_confirmed_token := coalesce(p_expected_revision, v_current_rev);

    insert into public.slot_purchase_receipts (
        user_id, client_request_id, slot_number, cost_credits, balance_after,
        expires_at, renewal_days, quote_token, canonical_token, business_policy_id, created_at
    ) values (
        v_user_id, p_client_request_id, p_slot_number, v_cost, v_balance,
        v_new_expires, v_renewal_days, v_confirmed_token, v_current_rev, v_policy.id, clock_timestamp()
    ) returning id into v_receipt_id;

    return jsonb_build_object(
        'success', true,
        'slot_number', p_slot_number,
        'cost', v_cost,
        'balance', v_balance,
        'expires_at', to_char(v_new_expires at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'renewal_days', v_renewal_days,
        'quote_token', v_confirmed_token,
        'receipt_id', v_receipt_id,
        'replayed', false
    );
end;
$$;

-- 7.6 unlock_or_renew_slot (Legacy wrapper with fail-closed check)
CREATE OR REPLACE FUNCTION public.unlock_or_renew_slot(p_slot_number integer)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
declare
    v_policy public.business_policies;
    v_default_cost integer;
    v_cost integer;
    v_token text;
begin
    if p_slot_number < 2 or p_slot_number > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    v_default_cost := case when p_slot_number = 2 then 50 when p_slot_number = 3 then 100 else 200 end;
    v_policy := public._get_effective_business_policy_row();
    v_cost := case
        when p_slot_number = 2 then v_policy.slot_fee_2
        when p_slot_number = 3 then v_policy.slot_fee_3
        else v_policy.slot_fee_4_plus
    end;

    if v_cost <> v_default_cost or v_policy.slot_renewal_days <> 30 then
        raise exception 'CLIENT_UPDATE_REQUIRED: Phí hoặc thời hạn slot đã thay đổi. Vui lòng cập nhật ứng dụng.';
    end if;

    v_token := public._get_business_quote_token('slot', v_policy, jsonb_build_object('slot_number', p_slot_number));
    return public.unlock_or_renew_slot_v2(p_slot_number, gen_random_uuid(), v_default_cost, v_token);
end;
$$;

-- 7.7 Grants for consumers
REVOKE ALL ON FUNCTION public.handle_new_user() FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.handle_new_user() TO PUBLIC, anon, authenticated, service_role;

REVOKE ALL ON FUNCTION public.push_campaign(uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.push_campaign(uuid, uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.push_campaign_v2(uuid, uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.push_campaign_v2(uuid, uuid, integer, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.unlock_or_renew_slot(integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.unlock_or_renew_slot(integer) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.unlock_or_renew_slot_v2(integer, uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.unlock_or_renew_slot_v2(integer, uuid, integer, text) TO authenticated, service_role;

-- ============================================================================
-- 8. CONSUMER: DAILY INACTIVITY PENALTY TIERS
-- ============================================================================

ALTER TABLE public.user_daily_tasks
    ADD COLUMN IF NOT EXISTS business_policy_id bigint REFERENCES public.business_policies(id),
    ADD COLUMN IF NOT EXISTS penalty_percent integer;

CREATE OR REPLACE FUNCTION public._apply_daily_inactivity_penalty(p_eval date)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_biz_policy public.business_policies;
    v_is_carryover_active boolean := false;
    v_effective_from date := null;
    r record;
    v_status jsonb;
    v_ready boolean;
    v_hist_ready boolean;
    v_met boolean;
    v_mode text;
    v_required int;
    v_effective int;
    v_completed int;
    v_logged_in boolean;
    v_exempt boolean;
    v_prev_streak int;
    v_new_streak int;
    v_raw_pct int;
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
    v_last_task_date date;
begin
    if p_eval is null then
        return;
    end if;

    if p_eval >= v_today and current_setting('test.allow_future_finalize', true) is distinct from 'true' then
        return;
    end if;

    perform pg_advisory_xact_lock(hashtextextended('daily_inactivity_penalty', 0));

    v_policy := public.get_daily_task_policy(p_eval);
    v_carry_policy := public.get_daily_task_carryover_policy(p_eval);
    v_biz_policy := public._get_effective_business_policy_row(p_eval);
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_eval >= v_carry_policy.effective_from);
    v_effective_from := case when v_is_carryover_active then v_carry_policy.effective_from else null end;

    for r in
        select p.id, p.credits, p.daily_missed_streak, lower(p.role) as role, p.created_at
        from public.profiles p
        where p.created_at < ((p_eval + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh')
        for update of p
    loop
        if r.created_at >= (p_eval::timestamp at time zone 'Asia/Ho_Chi_Minh') then
            insert into public.user_daily_tasks as t (
                user_id, task_date, mode, active_app_count, required_tests,
                effective_required_tests, available_apps, completed_tests,
                logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
                policy_id, tests_per_active_app,
                carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
                carry_source_date, carryover_effective_from, business_policy_id, penalty_percent
            ) values (
                r.id, p_eval,
                case when r.role = 'owner' then 'owner' else 'member' end,
                0, 0, 0, 0, 0,
                true, true, true, 0, 0, now(),
                v_policy.id, v_policy.tests_per_active_app,
                case when v_is_carryover_active then 1 else null end,
                0, 0, 0, null, v_effective_from,
                v_biz_policy.id, 0
            )
            on conflict (user_id, task_date) do update
            set is_exempt = true,
                met = true,
                missed_streak = 0,
                penalty_credits = 0,
                finalized_at = now(),
                policy_id = excluded.policy_id,
                tests_per_active_app = excluded.tests_per_active_app,
                carryover_version = excluded.carryover_version,
                base_required_tests = 0,
                carry_in_tests = 0,
                carry_out_tests = 0,
                carryover_effective_from = excluded.carryover_effective_from,
                business_policy_id = excluded.business_policy_id,
                penalty_percent = 0
            where t.finalized_at is null;

            continue;
        end if;

        if exists (
            select 1 from public.user_daily_tasks t
            where t.user_id = r.id and t.task_date = p_eval
              and t.finalized_at is not null
        ) then
            continue;
        end if;

        v_status := public.get_daily_task_status(r.id, p_eval);
        if v_status is null then
            continue;
        end if;

        v_ready := coalesce((v_status ->> 'carryover_ready')::boolean, true);
        v_hist_ready := coalesce((v_status ->> 'history_ready')::boolean, true);

        if (v_is_carryover_active and not v_ready) or (not v_hist_ready) then
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

        select t.task_date into v_last_task_date
        from public.user_daily_tasks t
        where t.user_id = r.id and t.task_date < p_eval and t.finalized_at is not null
        order by t.task_date desc
        limit 1;

        if v_carry_out > 0 then
            v_source_date := coalesce(v_last_task_date, p_eval);
        else
            v_source_date := null;
        end if;

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

        if v_exempt then
            v_new_streak := 0;
            v_deduction := 0;
            v_raw_pct := 0;
        elsif v_met then
            v_new_streak := 0;
            v_deduction := 0;
            v_raw_pct := 0;
        else
            if v_mode = 'owner' and v_effective <= 0 then
                v_new_streak := v_prev_streak;
                v_deduction := 0;
                v_raw_pct := 0;
            else
                v_new_streak := v_prev_streak + 1;
                v_raw_pct := case
                    when v_new_streak = 1 then v_biz_policy.penalty_tier1_pct
                    when v_new_streak = 2 then v_biz_policy.penalty_tier2_pct
                    when v_new_streak = 3 then v_biz_policy.penalty_tier3_pct
                    else v_biz_policy.penalty_tier4_pct
                end;
                v_pct := v_raw_pct::numeric / 100.0;

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

                if v_ratio <= 0 or v_raw_pct <= 0 then
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

        insert into public.user_daily_tasks as t (
            user_id, task_date, mode, active_app_count, required_tests,
            effective_required_tests, available_apps, completed_tests,
            logged_in, is_exempt, met, missed_streak, penalty_credits, finalized_at,
            policy_id, tests_per_active_app,
            carryover_version, base_required_tests, carry_in_tests, carry_out_tests,
            carry_source_date, carryover_effective_from, business_policy_id, penalty_percent
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
            v_effective_from,
            v_biz_policy.id,
            v_raw_pct
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
            carryover_effective_from = excluded.carryover_effective_from,
            business_policy_id = excluded.business_policy_id,
            penalty_percent = excluded.penalty_percent
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
                values (r.id, -v_deduction, 'daily_missed_penalty', v_note, v_new_balance);
            end if;
        end if;
    end loop;
end;
$function$;

CREATE OR REPLACE FUNCTION public.get_daily_task_status(p_user_id uuid, p_date date)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_policy public.daily_task_policies;
    v_carry_policy public.daily_task_carryover_policies;
    v_biz_policy public.business_policies;
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
    v_today date := (clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_day_start timestamptz;
    v_next_day_start timestamptz;
    v_window_start timestamptz;
    v_carryover_ready boolean := true;
    v_history_ready boolean := true;
    v_carry_remaining int := 0;
    v_carry_effective_from date := null;
    v_prev_finalized timestamptz;
    v_prev_carry_out int;
    v_last_task_date date;
    v_last_carry_out int;
    v_last_finalized timestamptz;
    v_last_version int;
    v_has_prior_history boolean := false;
    v_is_carryover_active boolean := false;
    v_voluntary_tests int := 0;
    v_q int := 0;
    v_finalized_task record;
    v_coverage_start_time timestamptz;
    v_coverage_start_date date;
    v_hist_role text;
    v_hist_exempt boolean;
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
    v_biz_policy := public._get_effective_business_policy_row(p_date);

    -- 1. Nếu ngày p_date đã được chốt (finalized) trong user_daily_tasks:
    -- Trả lại snapshot lịch sử bất biến
    if p_date < v_today then
        select * into v_finalized_task
        from public.user_daily_tasks
        where user_id = p_user_id
          and task_date = p_date
          and finalized_at is not null;

        if v_finalized_task.task_date is not null then
            return jsonb_build_object(
                'status_date', to_char(p_date, 'YYYY-MM-DD'),
                'mode', v_finalized_task.mode,
                'active_app_count', v_finalized_task.active_app_count,
                'required_tests', v_finalized_task.required_tests,
                'available_apps', v_finalized_task.available_apps,
                'effective_required_tests', v_finalized_task.effective_required_tests,
                'completed_tests', v_finalized_task.completed_tests,
                'logged_in_today', v_finalized_task.logged_in,
                'is_penalty_exempt', v_finalized_task.is_exempt,
                'missed_streak', v_finalized_task.missed_streak,
                'next_penalty_percent', case
                    when v_finalized_task.missed_streak <= 0 then v_biz_policy.penalty_tier1_pct
                    when v_finalized_task.missed_streak = 1 then v_biz_policy.penalty_tier2_pct
                    when v_finalized_task.missed_streak = 2 then v_biz_policy.penalty_tier3_pct
                    else v_biz_policy.penalty_tier4_pct
                end,
                'met', v_finalized_task.met,
                'base_required_tests', v_finalized_task.base_required_tests,
                'carry_in_tests', v_finalized_task.carry_in_tests,
                'carryover_remaining_tests', v_finalized_task.carry_out_tests,
                'carryover_ready', true,
                'history_ready', true,
                'carryover_effective_from', case when v_finalized_task.carryover_effective_from is not null then to_char(v_finalized_task.carryover_effective_from, 'YYYY-MM-DD') else null end
            );
        end if;
    end if;

    v_day_start := (p_date::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_next_day_start := ((p_date + 1)::timestamp at time zone 'Asia/Ho_Chi_Minh');
    v_window_start := ((p_date - 30)::timestamp at time zone 'Asia/Ho_Chi_Minh');

    select marker_time, marker_date
    into v_coverage_start_time, v_coverage_start_date
    from public.system_provenance_markers
    where marker_key = 'coverage_start';

    if p_date < v_today then
        if v_coverage_start_time is null or v_day_start < v_coverage_start_time then
            v_history_ready := false;
        else
            v_history_ready := true;
        end if;

        select j.role, j.is_exempt
        into v_hist_role, v_hist_exempt
        from public.user_role_journal j
        where j.user_id = p_user_id
          and j.valid_from < v_next_day_start
        order by j.valid_from desc, j.id desc
        limit 1;

        if v_hist_role is not null then
            v_role := v_hist_role;
            v_exempt := v_hist_exempt;
        else
            if v_history_ready then
                v_exempt := coalesce(v_role in ('admin', 'moderator'), false);
            else
                v_exempt := false;
            end if;
        end if;

        with hist as (
            select distinct on (j.campaign_id)
                j.campaign_id, j.owner_id, j.status, j.paused_reason,
                j.campaign_created_at, j.completed_at
            from public.campaign_status_journal j
            where j.valid_from < v_next_day_start
            order by j.campaign_id, j.valid_from desc, j.id desc
        )
        select
            coalesce(count(*) filter (
                where h.owner_id = p_user_id
                  and h.campaign_created_at < v_day_start
                  and h.campaign_created_at > v_window_start
                  and (
                      h.status = 'open'
                      or (h.status = 'paused' and h.paused_reason = 'owner_inactive')
                      or (h.completed_at is not null and h.completed_at >= v_next_day_start)
                  )
            ), 0),
            coalesce(count(*) filter (
                where h.owner_id <> p_user_id
                  and h.campaign_created_at < v_next_day_start
                  and h.campaign_created_at > v_window_start
                  and (
                      h.status = 'open'
                      or (h.completed_at is not null and h.completed_at >= v_next_day_start)
                  )
            ), 0)
        into v_k, v_a
        from hist h;

        with latest_events as (
            select distinct on (j.source_type, j.source_id)
                j.campaign_id, j.campaign_owner_id, j.is_qualifying
            from public.daily_test_events_journal j
            where j.user_id = p_user_id
              and j.activity_date = p_date
              and j.observed_at < v_next_day_start
            order by j.source_type, j.source_id, j.observed_at desc, j.id desc
        )
        select count(distinct e.campaign_id) into v_d
        from latest_events e
        where e.is_qualifying = true
          and e.campaign_owner_id <> p_user_id;
    else
        v_exempt := coalesce(v_role in ('admin', 'moderator'), false);

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

        select count(*) into v_a
        from public.campaigns c
        where c.owner_id <> p_user_id
          and c.created_at < v_next_day_start
          and c.created_at > v_window_start
          and (
              c.status = 'open'
              or (c.completed_at is not null and c.completed_at >= v_next_day_start)
          );

        with latest_events as (
            select distinct on (j.source_type, j.source_id)
                j.campaign_id, j.campaign_owner_id, j.is_qualifying
            from public.daily_test_events_journal j
            where j.user_id = p_user_id
              and j.activity_date = p_date
            order by j.source_type, j.source_id, j.observed_at desc, j.id desc
        )
        select count(distinct e.campaign_id) into v_d
        from latest_events e
        where e.is_qualifying = true
          and e.campaign_owner_id <> p_user_id;

        v_history_ready := true;
    end if;

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

    v_carry_policy := public.get_daily_task_carryover_policy(p_date);
    v_is_carryover_active := (v_carry_policy is not null and v_carry_policy.is_enabled and p_date >= v_carry_policy.effective_from);

    select t.task_date, t.carry_out_tests, t.finalized_at, t.carryover_version
    into v_last_task_date, v_last_carry_out, v_last_finalized, v_last_version
    from public.user_daily_tasks t
    where t.user_id = p_user_id
      and t.task_date < p_date
      and t.finalized_at is not null
    order by t.task_date desc
    limit 1;

    v_has_prior_history := (v_last_task_date is not null);

    if v_is_carryover_active then
        v_carry_effective_from := v_carry_policy.effective_from;

        if p_date = v_carry_effective_from and not v_has_prior_history then
            v_carry_in := 0;
            v_carryover_ready := true;
        elsif p_date = v_carry_effective_from and v_has_prior_history then
            v_carry_in := coalesce(v_last_carry_out, 0);
            v_carryover_ready := true;
        else
            select t.finalized_at, t.carry_out_tests
            into v_prev_finalized, v_prev_carry_out
            from public.user_daily_tasks t
            where t.user_id = p_user_id and t.task_date = (p_date - 1);

            if v_prev_finalized is not null then
                v_carry_in := coalesce(v_prev_carry_out, 0);
                v_carryover_ready := true;
            else
                v_carryover_ready := false;
                v_carry_in := 0;
            end if;
        end if;

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
                v_required := v_base_required + v_carry_in;
                v_effective := least(v_required, v_a);
                v_q := greatest(v_carry_in, v_effective);
                v_carry_remaining := greatest(0, v_q - v_d);
                v_met := (v_d >= v_effective);
            end if;
        else
            if v_mode = 'member' then
                v_required := 0;
                v_effective := 0;
                v_carry_remaining := 0;
                v_met := v_logged_in or v_d > 0;
            else
                v_required := v_base_required;
                v_effective := least(v_required, v_a);
                v_carry_remaining := 0;
                v_met := false;
            end if;
        end if;
    else
        v_carry_effective_from := null;
        v_carryover_ready := true;

        if v_has_prior_history and coalesce(v_last_carry_out, 0) > 0 then
            v_carry_in := v_last_carry_out;
            if v_mode = 'member' or v_exempt then
                v_voluntary_tests := v_d;
            else
                v_effective := least(v_base_required, v_a);
                v_voluntary_tests := greatest(0, v_d - v_effective);
            end if;
            v_carry_remaining := greatest(0, v_carry_in - v_voluntary_tests);
        else
            v_carry_in := 0;
            v_carry_remaining := 0;
        end if;

        if v_exempt then
            v_required := v_base_required;
            v_effective := least(v_required, v_a);
            v_met := true;
        elsif v_mode = 'member' then
            v_required := 0;
            v_effective := 0;
            v_met := v_logged_in or v_d > 0;
        else
            v_required := v_base_required;
            v_effective := least(v_required, v_a);
            v_met := (v_d >= v_effective);
        end if;
    end if;

    v_next_pct := case
        when v_streak <= 0 then v_biz_policy.penalty_tier1_pct
        when v_streak = 1 then v_biz_policy.penalty_tier2_pct
        when v_streak = 2 then v_biz_policy.penalty_tier3_pct
        else v_biz_policy.penalty_tier4_pct
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
        'history_ready', v_history_ready,
        'carryover_effective_from', case when v_carry_effective_from is not null then to_char(v_carry_effective_from, 'YYYY-MM-DD') else null end
    );
end;
$function$;

REVOKE ALL ON FUNCTION public._apply_daily_inactivity_penalty(date) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._apply_daily_inactivity_penalty(date) TO service_role;

REVOKE ALL ON FUNCTION public.get_daily_task_status(uuid, date) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_daily_task_status(uuid, date) TO authenticated, service_role;


-- ============================================================================
-- 9. REPORT FINANCIAL METADATA & SNAPSHOT COLUMNS
-- ============================================================================

ALTER TABLE public.tester_reports
    ADD COLUMN IF NOT EXISTS review_tester_bonus integer,
    ADD COLUMN IF NOT EXISTS review_owner_bonus integer,
    ADD COLUMN IF NOT EXISTS business_policy_id bigint REFERENCES public.business_policies(id);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_review_tester_bonus,
    ADD CONSTRAINT chk_tester_reports_review_tester_bonus CHECK (review_tester_bonus IS NULL OR review_tester_bonus >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_review_owner_bonus,
    ADD CONSTRAINT chk_tester_reports_review_owner_bonus CHECK (review_owner_bonus IS NULL OR review_owner_bonus >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_provisional_granted_credits,
    ADD CONSTRAINT chk_tester_reports_provisional_granted_credits CHECK (provisional_granted_credits >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_provisional_clawed,
    ADD CONSTRAINT chk_tester_reports_provisional_clawed CHECK (provisional_clawed >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_bonus_awarded,
    ADD CONSTRAINT chk_tester_reports_bonus_awarded CHECK (bonus_awarded >= 0);

CREATE OR REPLACE FUNCTION public.guard_tester_report_financial_metadata()
RETURNS trigger
LANGUAGE plpgsql
AS $$
begin
    -- Security invoker: discriminates current_user 'authenticated'/'anon' for direct client DML
    -- vs trusted SECURITY DEFINER RPCs where current_user is the function owner (postgres).
    if current_user in ('anon', 'authenticated') then
        if tg_op = 'INSERT' then
            if new.review_tester_bonus is not null or
               new.review_owner_bonus is not null or
               new.business_policy_id is not null or
               coalesce(new.provisional_granted_credits, 0) <> 0 or
               coalesce(new.provisional_clawed, 0) <> 0 or
               coalesce(new.bonus_awarded, 0) <> 0 then
                raise exception 'FORBIDDEN_REPORT_METADATA_WRITE: Chi duoc tao bao cao qua RPC nop bao cao';
            end if;
        elsif tg_op = 'UPDATE' then
            if new.review_tester_bonus is distinct from old.review_tester_bonus or
               new.review_owner_bonus is distinct from old.review_owner_bonus or
               new.business_policy_id is distinct from old.business_policy_id or
               new.provisional_granted_credits is distinct from old.provisional_granted_credits or
               new.provisional_clawed is distinct from old.provisional_clawed or
               new.bonus_awarded is distinct from old.bonus_awarded then
                raise exception 'FORBIDDEN_REPORT_METADATA_UPDATE: Khong duoc phep sua doi thong so tai chinh cua bao cao';
            end if;
        end if;
    end if;
    return new;
end;
$$;

DROP TRIGGER IF EXISTS trg_guard_tester_report_financial_metadata ON public.tester_reports;
CREATE TRIGGER trg_guard_tester_report_financial_metadata
    BEFORE INSERT OR UPDATE ON public.tester_reports
    FOR EACH ROW EXECUTE FUNCTION public.guard_tester_report_financial_metadata();

CREATE OR REPLACE FUNCTION public.submit_tester_report_internal_v2(
    p_campaign_id uuid,
    p_content text DEFAULT ''::text,
    p_screenshot_url text DEFAULT NULL::text,
    p_rating integer DEFAULT 5,
    p_image_hash text DEFAULT NULL::text,
    p_client_submit_id uuid DEFAULT NULL::uuid,
    p_thumbnail_url text DEFAULT NULL::text,
    p_mission_id uuid DEFAULT NULL::uuid
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
    v_eligible_count int := 0;
    v_submission_context text := 'app_test';
    v_target_mission_id uuid := null;
    v_target_mp_id uuid := null;
    v_target_title text := null;
    v_target_reward int := null;
    v_target_ends_at timestamptz := null;
    v_check_m record;
    v_res_context text;
    v_res_mission_id uuid;
    v_res_title text;
    v_res_reward int;
    v_res_ends_at timestamptz;
    v_result jsonb;
begin
    v_policy := public._get_effective_business_policy_row(v_today);
    v_provisional_reward := v_policy.report_provisional_reward;

    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    if exists(select 1 from public.report_cancelled_requests where user_id=v_user_id and client_submit_id=p_client_submit_id) then
        raise exception 'REPORT_REQUEST_REQUIRES_REVIEW';
    end if;
    -- Tính payload fingerprint
    v_fingerprint := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
        p_campaign_id,
        coalesce(p_content, ''),
        coalesce(p_screenshot_url, ''),
        coalesce(p_rating, 5),
        coalesce(lower(p_image_hash), ''),
        coalesce(p_thumbnail_url, ''),
        coalesce(p_mission_id::text, '')
    ), 'sha256'), 'hex');

    -- Idempotency check qua durable receipt ledger
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

            -- Nếu receipt là legacy_backfill: hỗ trợ semantics legacy an toàn (B01, B02, P02)
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
                    -- Với báo cáo đã duyệt (approved): thao tác bổ sung comment giữ nguyên rating đã khóa
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

        -- Fallback check tester_reports cho legacy submissions
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
                'rating', v_idempotent_report.rating
            );

            -- Ghi vào receipt ledger để các lần retry tiếp theo dùng thẳng receipt
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

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- Khóa tuần tự theo (user, campaign, ngày)
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s', v_user_id, p_campaign_id, v_today), 0)
    );

    -- Re-check idempotency sau khi đã nắm giữ khóa để loại trừ race condition
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
                    -- Với báo cáo đã duyệt (approved): thao tác bổ sung comment giữ nguyên rating đã khóa
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
    end if;

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Tìm báo cáo hôm nay và khóa dòng để phân loại thao tác
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1
    for update;

    -- N04: Bảo toàn dấu yêu cầu legacy/trước đó của report trước khi client_submit_id bị ghi đè
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
                'rating', v_existing_report.rating
            ),
            v_existing_report.created_at,
            'legacy_backfill'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    if v_existing_report.id is not null and (
        v_existing_report.submission_context is distinct from case when p_mission_id is null then 'app_test' else 'mission' end
        or v_existing_report.mission_id is distinct from p_mission_id
    ) then raise exception 'REPORT_CONTEXT_CONFLICT'; end if;
    -- TH1: BÁO CÁO ĐÃ DUYỆT (approved) -> Chỉ cho phép bổ sung comment, không phát sinh thưởng
    if v_existing_report.id is not null and v_existing_report.status = 'approved' then
        if p_mission_id is not null then
            if v_existing_report.submission_context <> 'mission' then
                raise exception 'REPORT_ALREADY_APPROVED_AS_APP_TEST';
            end if;
            if v_existing_report.mission_id is distinct from p_mission_id then
                raise exception 'REPORT_ALREADY_APPROVED_FOR_ANOTHER_MISSION';
            end if;
            v_submission_context := 'mission';
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
        else
            v_submission_context := coalesce(v_existing_report.submission_context, 'app_test');
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
        end if;

        -- Feedback: chỉ cập nhật comment, giữ rating
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
            mission_reward_credits, mission_ends_at, screenshot_url,
            image_hash, thumbnail_url
        into
            v_res_context, v_res_mission_id, v_res_title,
            v_res_reward, v_res_ends_at, v_final_url,
            v_final_hash, v_final_thumb
        from public.tester_reports
        where id = v_report_id;

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
            'rating', coalesce(v_existing_report.rating, v_rating)
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

    -- TH2: ĐÃ CÓ BÁO CÁO PENDING HOẶC REJECTED TRONG NGÀY
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

            if v_existing_report.status = 'rejected' then
                if v_target_ends_at is not null and v_target_ends_at < now() then
                    raise exception 'MISSION_EXPIRED';
                end if;
            end if;
        else
            if p_mission_id is not null then
                raise exception 'CANNOT_CONVERT_EXISTING_PENDING_REPORT';
            end if;
            v_submission_context := 'app_test';
        end if;

        if p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_new_screenshot := true;
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;

            -- Nếu nộp lại báo cáo bị từ chối, bắt buộc ảnh mới khác với URL đã bị từ chối
            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = p_screenshot_url then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        else
            v_effective_full := v_existing_report.screenshot_url;
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
                thumbnail_url = case when v_new_screenshot then p_thumbnail_url else thumbnail_url end,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
                rating = v_rating,
                status = 'pending',
                rejection_reason = null,
                reviewed_at = null,
                provisional_clawed = 0,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                created_at = now(),
                mission_submitted_at = case when v_submission_context = 'mission' then now() else mission_submitted_at end
            where id = v_existing_report.id
            returning id into v_report_id;
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then p_thumbnail_url else thumbnail_url end,
                image_hash = case when v_new_screenshot then lower(p_image_hash) else image_hash end,
                rating = v_rating,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;
        v_status := 'pending';
    else
        -- TH3: BÁO CÁO MỚI TINH TRONG NGÀY
        if p_screenshot_url is null or btrim(p_screenshot_url) = '' then
            raise exception 'SCREENSHOT_URL_REQUIRED';
        end if;
        if p_image_hash is null or btrim(p_image_hash) = '' then
            raise exception 'IMAGE_HASH_REQUIRED';
        end if;

        if p_mission_id is not null then
            select * into v_mission from public.missions
            where id = p_mission_id and campaign_id = p_campaign_id;

            if v_mission.id is null then
                raise exception 'MISSION_NOT_FOUND';
            end if;
            if v_mission.status not in ('open','full') then
                raise exception '%', case v_mission.status
                    when 'paused_by_creator' then 'MISSION_PAUSED'
                    when 'stopped_no_credits' then 'MISSION_STOPPED_NO_CREDITS'
                    when 'expired' then 'MISSION_EXPIRED'
                    when 'deleted' then 'MISSION_DELETED'
                    else 'MISSION_NOT_OPEN' end;
            end if;
            if v_mission.ends_at < now() then
                raise exception 'MISSION_EXPIRED';
            end if;

            select * into v_mp from public.mission_participants
            where mission_id = v_mission.id and user_id = v_user_id;

            if v_mp.id is null then
                raise exception 'MISSION_NOT_JOINED';
            end if;

            v_submission_context := 'mission';
            v_target_mission_id := v_mission.id;
            v_target_mp_id := v_mp.id;
            v_target_title := v_mission.title;
            v_target_reward := v_mission.reward_credits;
            v_target_ends_at := v_mission.ends_at;
        else
            v_submission_context := 'app_test';
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

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
                review_tester_bonus, review_owner_bonus, business_policy_id
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', 0, 0, 0,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id
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
                review_tester_bonus, review_owner_bonus, business_policy_id
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', v_provisional_amount, 0, v_provisional_amount,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id
            )
            returning id into v_report_id;

            v_status := 'pending';
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today, missed_streak = 0
    where id = v_user_id;

    select
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, screenshot_url,
        image_hash, thumbnail_url
    into
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_final_url,
        v_final_hash, v_final_thumb
    from public.tester_reports
    where id = v_report_id;

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
        'rating', case
            when v_existing_report.id is not null and v_existing_report.status = 'approved' then coalesce(v_existing_report.rating, v_rating)
            else v_rating
        end
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
$function$;;

CREATE OR REPLACE FUNCTION public.approve_tester_report(p_report_id uuid, p_bonus integer DEFAULT 0)
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
    v_extra_reward int := 2;
    v_tip int := 0;
    v_total_bonus int;
    v_new_balance int;
    v_owner_bonus int := 0;
    v_owner_balance int;
    v_is_owner boolean := false;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
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

    perform set_config('app.reviewing_report', 'false', true);

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'owner_credits_awarded', v_owner_bonus,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'owner_balance', v_owner_balance,
        'message', format(
            'Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester%s)',
            v_extra_reward,
            case when v_owner_bonus > 0 then format(' và +%s Credit cho bạn', v_owner_bonus) else '' end
        )
    );
end;
$function$;

-- ----------------------------------------------------------------------------
-- 14. reject_tester_report: exact 062 baseline logic with caller lock check
-- ----------------------------------------------------------------------------;

CREATE OR REPLACE FUNCTION public.reject_tester_report(
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
        greatest(0, coalesce(v_report.provisional_granted_credits, 2))
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

    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

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
$function$;;

CREATE OR REPLACE FUNCTION public.auto_accept_expired_reports()
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_report record;
    v_count int := 0;
    v_retried int := 0;
    v_owner_id uuid;
    v_camp_name text;
    v_tip int;
    v_base int;
    v_total_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);
    perform set_config('app.reviewing_report', 'true', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded, provisional_granted_credits
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        -- Genuine provisional 0 does not fall back to unrelated bonus
        v_base := coalesce(
            v_report.provisional_granted_credits,
            coalesce(nullif(v_report.bonus_awarded, 0), 2)
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

        -- M03: Gọi settlement với report_id
        perform public._settle_mission_reward(v_report.id);

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này + %s Credit boa từ chủ app). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được hệ thống tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), v_base)
            end,
            'report',
            jsonb_build_object(
                'report_id', v_report.id,
                'campaign_id', v_report.campaign_id,
                'status', 'approved',
                'auto_accepted', true
            )
        );

        v_count := v_count + 1;
    end loop;

    -- M05: Tự động chạy retry các settlement job thất bại trong lượt quét định kỳ
    v_retried := public.retry_failed_mission_settlements(50);

    -- R06: Reset cờ suppress notification trước khi trả về
    perform set_config('app.suppress_report_notify', '0', true);

    return v_count + v_retried;
end;
$function$;

CREATE OR REPLACE FUNCTION public.auto_confirm_user()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'auth'
AS $function$
begin
    if new.email_confirmed_at is null then
        new.email_confirmed_at := now();
    end if;
    return new;
end;
$function$;;

CREATE OR REPLACE FUNCTION public.get_campaign_reports_for_owner(p_campaign_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_res jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role into v_role from public.profiles where id = v_caller_id;

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
                'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
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
$function$;;

CREATE OR REPLACE FUNCTION public.get_campaign_detail(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
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
        'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
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

    -- Consistent warning counts excluding revocations
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
$function$;;

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
SET search_path TO 'public'
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
            tr.created_at,
            tr.reviewed_at,
            tr.rejection_reason,
            tr.review_tester_bonus,
            tr.review_owner_bonus,
            tr.business_policy_id,
            tr.provisional_granted_credits,
            tr.provisional_clawed,
            tr.bonus_awarded
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
$function$;;


-- Grants for report consumers
REVOKE ALL ON FUNCTION public.submit_tester_report_internal_v2(uuid, text, text, integer, text, uuid, text, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.submit_tester_report_internal_v2(uuid, text, text, integer, text, uuid, text, uuid) TO service_role;

REVOKE ALL ON FUNCTION public.approve_tester_report(uuid, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_tester_report(uuid, integer) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.reject_tester_report(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reject_tester_report(uuid, text, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.auto_accept_expired_reports() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.auto_accept_expired_reports() TO service_role;

REVOKE ALL ON FUNCTION public.get_campaign_reports_for_owner(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_reports_for_owner(uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail(uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) TO authenticated, service_role;

-- ============================================================================
-- 10. CONSUMER: APP UPDATE LIFECYCLE (PUBLISH, CLAIM, CLOSE, REFUND)
-- ============================================================================

-- 10.1 Dynamic policy-aligned check constraints
ALTER TABLE public.app_update_claims
    DROP CONSTRAINT IF EXISTS app_update_claims_reward_credits_check,
    ADD CONSTRAINT app_update_claims_reward_credits_check CHECK (reward_credits >= 1 AND reward_credits <= 1000);

ALTER TABLE public.app_update_claims
    DROP CONSTRAINT IF EXISTS app_update_claims_ordinal_check,
    ADD CONSTRAINT app_update_claims_ordinal_check CHECK (ordinal >= 1 AND ordinal <= 1000);

ALTER TABLE public.app_update_releases
    ADD COLUMN IF NOT EXISTS business_policy_id bigint REFERENCES public.business_policies(id);

ALTER TABLE public.app_update_releases
    DROP CONSTRAINT IF EXISTS app_update_releases_max_claims_check,
    ADD CONSTRAINT app_update_releases_max_claims_check CHECK (max_claims >= 1 AND max_claims <= 1000);

ALTER TABLE public.app_update_releases
    DROP CONSTRAINT IF EXISTS app_update_releases_reward_per_claim_check,
    ADD CONSTRAINT app_update_releases_reward_per_claim_check CHECK (reward_per_claim >= 1 AND reward_per_claim <= 1000);

ALTER TABLE public.app_update_releases
    DROP CONSTRAINT IF EXISTS app_update_releases_paid_count_check,
    ADD CONSTRAINT app_update_releases_paid_count_check CHECK (paid_count >= 0 AND paid_count <= max_claims);

ALTER TABLE public.app_update_releases
    DROP CONSTRAINT IF EXISTS app_update_releases_reserved_credits_check,
    ADD CONSTRAINT app_update_releases_reserved_credits_check CHECK (reserved_credits = max_claims * reward_per_claim);

-- 10.2 publish_app_update_v2 (Financial operation with quote token, budget & CAS checks)
CREATE OR REPLACE FUNCTION public.publish_app_update_v2(
    p_campaign_id uuid,
    p_version_name text,
    p_target_version_code bigint,
    p_release_notes text,
    p_client_operation_id text,
    p_expected_budget integer,
    p_expected_revision text,
    p_expected_current_release_id uuid DEFAULT 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id           uuid := auth.uid();
    v_camp                public.campaigns%rowtype;
    v_version_name_clean  text;
    v_release_notes_clean text;
    v_ctrl                record;
    v_active_rel          record;
    v_old_remaining       int := 0;
    v_owner_credits       int;
    v_owner_is_locked     boolean := false;
    v_effective_credits   int;
    v_new_release_id      uuid;
    v_new_release         public.app_update_releases%rowtype;
    v_refund_log_id       uuid;
    v_reserve_log_id      uuid;
    v_existing_op         record;
    v_policy              public.business_policies;
    v_current_quote_token text;
    v_reward_per_claim    int;
    v_max_claims          int;
    v_reserved_budget     int;
    v_today               date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    c_sentinel            constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    v_version_name_clean := trim(coalesce(p_version_name, ''));
    if v_version_name_clean = '' or length(v_version_name_clean) > 100 then
        raise exception 'INVALID_VERSION_NAME';
    end if;

    if p_target_version_code is null or p_target_version_code <= 0 then
        raise exception 'INVALID_VERSION_CODE';
    end if;

    v_release_notes_clean := trim(coalesce(p_release_notes, ''));
    if v_release_notes_clean = '' or length(v_release_notes_clean) > 4000 then
        raise exception 'INVALID_RELEASE_NOTES';
    end if;

    -- 1. Serialize and replay check BEFORE quote/budget gate
    if p_client_operation_id is not null and trim(p_client_operation_id) <> '' then
        perform pg_advisory_xact_lock(hashtextextended('app_update_op:' || v_caller_id::text || ':' || trim(p_client_operation_id), 0));

        select * into v_existing_op
        from public.app_update_operations
        where operation_id = trim(p_client_operation_id) and owner_id = v_caller_id;

        if v_existing_op.operation_id is not null then
            if v_existing_op.campaign_id is distinct from p_campaign_id
               or v_existing_op.target_version_code is distinct from p_target_version_code
               or v_existing_op.version_name is distinct from v_version_name_clean
               or v_existing_op.release_notes is distinct from v_release_notes_clean
            then
                raise exception 'OPERATION_PAYLOAD_CONFLICT';
            end if;

            select * into v_new_release from public.app_update_releases where id = v_existing_op.release_id;
            select credits into v_owner_credits from public.profiles where id = v_caller_id;

            return jsonb_build_object(
                'success', true,
                'release', row_to_json(v_new_release),
                'owner_credits', v_owner_credits,
                'refund_amount', 0,
                'idempotent_replay', true
            );
        end if;
    end if;

    -- 2. Validate mandatory expected budget and quote token for fresh publish
    if p_expected_budget is null or p_expected_revision is null or btrim(p_expected_revision) = '' then
        raise exception 'BUSINESS_POLICY_CONFLICT: Ngân sách dự kiến và mã báo giá là bắt buộc đối với giao dịch v2';
    end if;

    -- 3. Check account lock
    select credits, coalesce(is_locked, false)
    into v_owner_credits, v_owner_is_locked
    from public.profiles
    where id = v_caller_id
    for update;

    if v_owner_is_locked then
        raise exception 'USER_LOCKED';
    end if;

    -- 4. Lock ordering 1 - campaigns
    select * into v_camp from public.campaigns where id = p_campaign_id for update;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_caller_id then
        raise exception 'NOT_ACTUAL_OWNER';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    if v_camp.package_name is null or trim(v_camp.package_name) = '' then
        raise exception 'INVALID_PACKAGE_NAME';
    end if;

    -- Advisory lock per owner & package
    perform pg_advisory_xact_lock(hashtextextended(v_caller_id::text || ':' || v_camp.package_name, 0));

    -- 5. Lock ordering 2 - app_update_controls
    insert into public.app_update_controls (owner_id, package_name, highest_version_code)
    values (v_caller_id, v_camp.package_name, 0)
    on conflict (owner_id, package_name) do nothing;

    select * into v_ctrl
    from public.app_update_controls
    where owner_id = v_caller_id and package_name = v_camp.package_name
    for update;

    -- Concurrency CAS on current release:
    -- If p_expected_current_release_id is NULL -> caller expects NO active release!
    -- Only if p_expected_current_release_id = c_sentinel -> unspecified legacy
    if p_expected_current_release_id is distinct from c_sentinel then
        if p_expected_current_release_id is distinct from v_ctrl.active_release_id then
            raise exception 'CONCURRENT_UPDATE_CONFLICT';
        end if;
    end if;

    if p_target_version_code <= v_ctrl.highest_version_code then
        raise exception 'VERSION_CODE_NOT_GREATER';
    end if;

    -- 6. Lock ordering 3 - active release
    select * into v_active_rel
    from public.app_update_releases
    where owner_id = v_caller_id
      and package_name = v_camp.package_name
      and status = 'active'
    for update;

    if v_active_rel.id is not null then
        v_old_remaining := v_active_rel.remaining_credits;
    end if;

    -- 7. Policy and quote token verification
    v_policy := public._get_effective_business_policy_row(v_today);
    v_reward_per_claim := v_policy.app_update_reward_per_claim;
    v_max_claims := v_policy.app_update_max_claims;
    v_reserved_budget := v_reward_per_claim * v_max_claims;
    v_current_quote_token := public._get_business_quote_token('app_update', v_policy);

    if p_expected_budget <> v_reserved_budget then
        raise exception 'BUSINESS_POLICY_PRICE_MISMATCH: Ngân sách giữ trước đã thay đổi (hiện tại: %s Credit)', v_reserved_budget;
    end if;

    if p_expected_revision <> v_current_quote_token and p_expected_revision <> public._get_business_quote_token('all', v_policy) then
        raise exception 'BUSINESS_POLICY_CONFLICT: Báo giá cập nhật ứng dụng đã hết hạn hoặc điều khoản đã thay đổi. Vui lòng lấy lại báo giá.';
    end if;

    -- 8. Insufficient credits calculation includes prior remaining refund
    v_effective_credits := v_owner_credits + v_old_remaining;
    if v_effective_credits < v_reserved_budget then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- 9. Handle old active release
    if v_active_rel.id is not null then
        update public.app_update_releases
        set status = 'superseded',
            closed_at = now(),
            remaining_credits = 0,
            refunded_credits = refunded_credits + v_old_remaining
        where id = v_active_rel.id;

        if v_old_remaining > 0 then
            v_owner_credits := v_owner_credits + v_old_remaining;
            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_caller_id,
                v_old_remaining,
                'app_update_refund',
                format('Hoàn %s Credit chưa dùng từ phiên bản %s (%s)', v_old_remaining, v_active_rel.version_name, v_camp.app_name),
                v_owner_credits
            )
            returning id into v_refund_log_id;

            insert into public.app_update_financial_events (
                release_id, event_type, amount, owner_id, credit_log_id, operation_id
            ) values (
                v_active_rel.id, 'refund', v_old_remaining, v_caller_id, v_refund_log_id, p_client_operation_id
            );
        end if;
    end if;

    update public.app_update_releases
    set status = 'superseded'
    where owner_id = v_caller_id
      and package_name = v_camp.package_name
      and status in ('active', 'exhausted');

    -- Deduct reserved budget
    v_owner_credits := v_owner_credits - v_reserved_budget;
    update public.profiles set credits = v_owner_credits where id = v_caller_id;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_caller_id,
        -v_reserved_budget,
        'app_update_reserve',
        format('Giữ trước %s Credit thưởng cập nhật phiên bản %s (%s)', v_reserved_budget, v_version_name_clean, v_camp.app_name),
        v_owner_credits
    )
    returning id into v_reserve_log_id;

    insert into public.app_update_releases (
        campaign_id,
        owner_id,
        owner_id_snapshot,
        package_name,
        app_name,
        version_name,
        target_version_code,
        release_notes,
        status,
        reward_per_claim,
        max_claims,
        reserved_credits,
        paid_count,
        remaining_credits,
        refunded_credits,
        published_at,
        business_policy_id
    ) values (
        p_campaign_id,
        v_caller_id,
        v_caller_id,
        v_camp.package_name,
        v_camp.app_name,
        v_version_name_clean,
        p_target_version_code,
        v_release_notes_clean,
        'active',
        v_reward_per_claim,
        v_max_claims,
        v_reserved_budget,
        0,
        v_reserved_budget,
        0,
        now(),
        v_policy.id
    )
    returning id into v_new_release_id;

    select * into v_new_release from public.app_update_releases where id = v_new_release_id;

    insert into public.app_update_financial_events (
        release_id, event_type, amount, owner_id, credit_log_id, operation_id
    ) values (
        v_new_release_id, 'reserve', v_reserved_budget, v_caller_id, v_reserve_log_id, p_client_operation_id
    );

    update public.app_update_controls
    set highest_version_code = p_target_version_code,
        active_release_id = v_new_release_id,
        updated_at = now()
    where owner_id = v_caller_id and package_name = v_camp.package_name;

    if p_client_operation_id is not null and trim(p_client_operation_id) <> '' then
        insert into public.app_update_operations (
            operation_id, owner_id, campaign_id, release_id,
            target_version_code, version_name, release_notes, created_at
        ) values (
            trim(p_client_operation_id), v_caller_id, p_campaign_id, v_new_release_id,
            p_target_version_code, v_version_name_clean, v_release_notes_clean, now()
        );
    end if;

    return jsonb_build_object(
        'success', true,
        'release', row_to_json(v_new_release),
        'owner_credits', v_owner_credits,
        'refund_amount', v_old_remaining,
        'idempotent_replay', false
    );
end;
$function$;

-- 10.3 publish_app_update (Legacy wrapper with fail-closed gate)
CREATE OR REPLACE FUNCTION public.publish_app_update(
    p_campaign_id uuid,
    p_version_name text,
    p_target_version_code bigint,
    p_release_notes text,
    p_client_operation_id text DEFAULT NULL::text,
    p_expected_current_release_id uuid DEFAULT 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_existing_op record;
    v_new_release public.app_update_releases%rowtype;
    v_owner_credits int;
    v_version_name_clean text;
    v_release_notes_clean text;
    v_policy public.business_policies;
    v_token text;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    v_version_name_clean := trim(coalesce(p_version_name, ''));
    v_release_notes_clean := trim(coalesce(p_release_notes, ''));

    -- Serialize and replay check before quote gate
    if p_client_operation_id is not null and trim(p_client_operation_id) <> '' then
        perform pg_advisory_xact_lock(hashtextextended('app_update_op:' || v_caller_id::text || ':' || trim(p_client_operation_id), 0));

        select * into v_existing_op
        from public.app_update_operations
        where operation_id = trim(p_client_operation_id) and owner_id = v_caller_id;

        if v_existing_op.operation_id is not null then
            if v_existing_op.campaign_id is distinct from p_campaign_id
               or v_existing_op.target_version_code is distinct from p_target_version_code
               or v_existing_op.version_name is distinct from v_version_name_clean
               or v_existing_op.release_notes is distinct from v_release_notes_clean
            then
                raise exception 'OPERATION_PAYLOAD_CONFLICT';
            end if;

            select * into v_new_release from public.app_update_releases where id = v_existing_op.release_id;
            select credits into v_owner_credits from public.profiles where id = v_caller_id;

            return jsonb_build_object(
                'success', true,
                'release', row_to_json(v_new_release),
                'owner_credits', v_owner_credits,
                'refund_amount', 0,
                'idempotent_replay', true
            );
        end if;
    end if;

    -- Legacy gate: reject CLIENT_UPDATE_REQUIRED when ANY perclaim or maxclaims differs default 6/20
    v_policy := public._get_effective_business_policy_row();
    if v_policy.app_update_reward_per_claim <> 6 or v_policy.app_update_max_claims <> 20 then
        raise exception 'CLIENT_UPDATE_REQUIRED: Chính sách ngân sách cập nhật ứng dụng đã thay đổi (%s Credit/claim, tối đa %s claim). Vui lòng cập nhật ứng dụng để xác nhận.',
            v_policy.app_update_reward_per_claim, v_policy.app_update_max_claims;
    end if;

    v_token := public._get_business_quote_token('app_update', v_policy);

    return public.publish_app_update_v2(
        p_campaign_id,
        p_version_name,
        p_target_version_code,
        p_release_notes,
        p_client_operation_id,
        120,
        v_token,
        p_expected_current_release_id
    );
end;
$function$;

-- 10.4 claim_app_update_reward (Payout & claims bounded by release snapshot)
CREATE OR REPLACE FUNCTION public.claim_app_update_reward(
    p_release_id uuid,
    p_observed_version_code bigint,
    p_client_operation_id text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id            uuid := auth.uid();
    v_rel                  public.app_update_releases%rowtype;
    v_camp                 public.campaigns%rowtype;
    v_member               public.campaign_members%rowtype;
    v_existing_claim       public.app_update_claims%rowtype;
    v_tester_is_locked     boolean := false;
    v_next_ordinal         int;
    v_new_paid_count       int;
    v_new_remaining        int;
    v_new_status           text;
    v_tester_credits       int;
    v_credit_log_id        uuid;
    v_claim_id             uuid;
    v_confirmed_at         timestamptz;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_observed_version_code is null or p_observed_version_code <= 0 then
        raise exception 'INVALID_OBSERVED_VERSION_CODE';
    end if;

    -- Idempotency check before locking
    select * into v_existing_claim
    from public.app_update_claims
    where release_id = p_release_id and tester_id = v_caller_id;

    if v_existing_claim.id is not null then
        select credits into v_tester_credits from public.profiles where id = v_caller_id;
        select * into v_rel from public.app_update_releases where id = p_release_id;

        return jsonb_build_object(
            'success', true,
            'newly_awarded', false,
            'reward', v_existing_claim.reward_credits,
            'tester_balance', v_tester_credits,
            'paid_count', v_rel.paid_count,
            'release_status', v_rel.status,
            'receipt', jsonb_build_object(
                'claim_id', v_existing_claim.id,
                'release_id', v_existing_claim.release_id,
                'ordinal', v_existing_claim.ordinal,
                'reward_credits', v_existing_claim.reward_credits,
                'confirmed_at', to_char(v_existing_claim.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            )
        );
    end if;

    -- Lock release
    select * into v_rel from public.app_update_releases where id = p_release_id for update;
    if v_rel.id is null then
        raise exception 'RELEASE_NOT_FOUND';
    end if;

    -- Re-check claim under lock
    select * into v_existing_claim
    from public.app_update_claims
    where release_id = p_release_id and tester_id = v_caller_id;

    if v_existing_claim.id is not null then
        select credits into v_tester_credits from public.profiles where id = v_caller_id;
        return jsonb_build_object(
            'success', true,
            'newly_awarded', false,
            'reward', v_existing_claim.reward_credits,
            'tester_balance', v_tester_credits,
            'paid_count', v_rel.paid_count,
            'release_status', v_rel.status,
            'receipt', jsonb_build_object(
                'claim_id', v_existing_claim.id,
                'release_id', v_existing_claim.release_id,
                'ordinal', v_existing_claim.ordinal,
                'reward_credits', v_existing_claim.reward_credits,
                'confirmed_at', to_char(v_existing_claim.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            )
        );
    end if;

    if v_rel.status = 'superseded' then
        raise exception 'RELEASE_REPLACED';
    elsif v_rel.status = 'closed' then
        raise exception 'RELEASE_CLOSED';
    elsif v_rel.status = 'exhausted' or v_rel.paid_count >= v_rel.max_claims or v_rel.remaining_credits < v_rel.reward_per_claim then
        raise exception 'RELEASE_EXHAUSTED';
    elsif v_rel.status <> 'active' then
        raise exception 'RELEASE_NOT_ACTIVE';
    end if;

    if coalesce(v_rel.owner_id, v_rel.owner_id_snapshot) = v_caller_id then
        raise exception 'OWNER_CANNOT_CLAIM';
    end if;

    select * into v_camp from public.campaigns where id = v_rel.campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    select * into v_member
    from public.campaign_members
    where campaign_id = v_rel.campaign_id and user_id = v_caller_id;

    if v_member.campaign_id is null then
        raise exception 'NOT_A_MEMBER';
    end if;

    if v_member.joined_at > v_rel.published_at then
        raise exception 'MEMBER_JOINED_AFTER_RELEASE';
    end if;

    if p_observed_version_code < v_rel.target_version_code then
        raise exception 'VERSION_NOT_SATISFIED';
    end if;

    select credits, coalesce(is_locked, false)
    into v_tester_credits, v_tester_is_locked
    from public.profiles
    where id = v_caller_id
    for update;

    if v_tester_is_locked then
        raise exception 'USER_LOCKED';
    end if;

    -- Ordinal and remaining calculation using release snapshot
    v_next_ordinal := v_rel.paid_count + 1;
    v_new_paid_count := v_next_ordinal;
    v_new_remaining := v_rel.remaining_credits - v_rel.reward_per_claim;
    v_new_status := case
        when v_new_paid_count >= v_rel.max_claims or v_new_remaining < v_rel.reward_per_claim then 'exhausted'
        else 'active'
    end;

    update public.app_update_releases
    set paid_count = v_new_paid_count,
        remaining_credits = v_new_remaining,
        status = v_new_status,
        closed_at = case when v_new_status = 'exhausted' then now() else closed_at end
    where id = v_rel.id;

    if v_new_status = 'exhausted' then
        update public.app_update_controls
        set active_release_id = null,
            updated_at = now()
        where owner_id = coalesce(v_rel.owner_id, v_rel.owner_id_snapshot)
          and package_name = v_rel.package_name
          and active_release_id = v_rel.id;
    end if;

    -- Pay reward from release snapshot
    v_tester_credits := v_tester_credits + v_rel.reward_per_claim;
    update public.profiles set credits = v_tester_credits where id = v_caller_id;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_caller_id,
        v_rel.reward_per_claim,
        'app_update_reward',
        format('Thưởng +%s Credit cập nhật phiên bản %s (%s)', v_rel.reward_per_claim, v_rel.version_name, v_rel.app_name),
        v_tester_credits
    )
    returning id into v_credit_log_id;

    insert into public.app_update_claims (
        release_id,
        tester_id,
        ordinal,
        observed_version_code,
        reward_credits,
        confirmed_at,
        credit_log_id
    ) values (
        v_rel.id,
        v_caller_id,
        v_next_ordinal,
        p_observed_version_code,
        v_rel.reward_per_claim,
        now(),
        v_credit_log_id
    )
    returning id, confirmed_at into v_claim_id, v_confirmed_at;

    insert into public.app_update_financial_events (
        release_id, event_type, amount, owner_id, tester_id, credit_log_id, operation_id
    ) values (
        v_rel.id, 'reward', v_rel.reward_per_claim, coalesce(v_rel.owner_id, v_rel.owner_id_snapshot), v_caller_id, v_credit_log_id, p_client_operation_id
    );

    return jsonb_build_object(
        'success', true,
        'newly_awarded', true,
        'reward', v_rel.reward_per_claim,
        'tester_balance', v_tester_credits,
        'paid_count', v_new_paid_count,
        'release_status', v_new_status,
        'receipt', jsonb_build_object(
            'claim_id', v_claim_id,
            'release_id', v_rel.id,
            'ordinal', v_next_ordinal,
            'reward_credits', v_rel.reward_per_claim,
            'confirmed_at', to_char(v_confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        )
    );
end;
$function$;

-- 10.5 close_app_update_release (Manual close & refund based on remaining_credits snapshot)
CREATE OR REPLACE FUNCTION public.close_app_update_release(
    p_release_id uuid,
    p_reason text DEFAULT 'manual_close'::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_caller_id       uuid := auth.uid();
    v_rel_lookup      record;
    v_rel             public.app_update_releases%rowtype;
    v_ctrl            record;
    v_camp            public.campaigns%rowtype;
    v_remaining       int;
    v_owner_credits   int;
    v_credit_log_id   uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select id, campaign_id, owner_id, owner_id_snapshot, package_name, status, remaining_credits
    into v_rel_lookup
    from public.app_update_releases
    where id = p_release_id;

    if v_rel_lookup.id is null then
        raise exception 'RELEASE_NOT_FOUND';
    end if;

    if coalesce(v_rel_lookup.owner_id, v_rel_lookup.owner_id_snapshot) <> v_caller_id then
        raise exception 'NOT_ACTUAL_OWNER';
    end if;

    if v_rel_lookup.campaign_id is not null then
        select * into v_camp from public.campaigns where id = v_rel_lookup.campaign_id for update;
    end if;

    select * into v_ctrl
    from public.app_update_controls
    where owner_id = v_caller_id and package_name = v_rel_lookup.package_name
    for update;

    select * into v_rel
    from public.app_update_releases
    where id = p_release_id
    for update;

    if v_rel.status <> 'active' then
        raise exception 'RELEASE_NOT_ACTIVE';
    end if;

    v_remaining := v_rel.remaining_credits;

    select credits into v_owner_credits from public.profiles where id = v_caller_id for update;

    update public.app_update_releases
    set status = 'closed',
        closed_at = now(),
        remaining_credits = 0,
        refunded_credits = refunded_credits + v_remaining
    where id = v_rel.id;

    update public.app_update_controls
    set active_release_id = null,
        updated_at = now()
    where owner_id = v_caller_id
      and package_name = v_rel.package_name
      and active_release_id = v_rel.id;

    if v_remaining > 0 then
        v_owner_credits := v_owner_credits + v_remaining;
        update public.profiles set credits = v_owner_credits where id = v_caller_id;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_caller_id,
            v_remaining,
            'app_update_refund',
            format('Hoàn %s Credit chưa dùng từ phiên bản %s (%s)', v_remaining, v_rel.version_name, v_rel.app_name),
            v_owner_credits
        )
        returning id into v_credit_log_id;

        insert into public.app_update_financial_events (
            release_id, event_type, amount, owner_id, credit_log_id
        ) values (
            v_rel.id, 'refund', v_remaining, v_caller_id, v_credit_log_id
        );
    end if;

    return jsonb_build_object(
        'success', true,
        'release_id', v_rel.id,
        'release_status', 'closed',
        'refund_amount', v_remaining,
        'owner_credits', v_owner_credits
    );
end;
$function$;

-- Grants for app update lifecycle
REVOKE ALL ON FUNCTION public.publish_app_update(uuid, text, bigint, text, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_app_update(uuid, text, bigint, text, text, uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.publish_app_update_v2(uuid, text, bigint, text, text, integer, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_app_update_v2(uuid, text, bigint, text, text, integer, text, uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.claim_app_update_reward(uuid, bigint, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.claim_app_update_reward(uuid, bigint, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.close_app_update_release(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.close_app_update_release(uuid, text) TO authenticated, service_role;
