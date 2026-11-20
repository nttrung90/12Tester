-- 100_mission_copy_and_block_previous_participants.sql
-- Mission Copy from Expired Missions & Optional Direct Previous-Participant Blocking.

-- ============================================================================
-- 1. SCHEMA EXTENSIONS ON public.missions
-- ============================================================================

ALTER TABLE public.missions
    ADD COLUMN IF NOT EXISTS source_mission_id uuid DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS block_previous_participants boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS client_operation_id text DEFAULT NULL;

DO $ddl$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.missions'::regclass
          AND conname = 'missions_block_requires_source_check'
    ) THEN
        ALTER TABLE public.missions
            ADD CONSTRAINT missions_block_requires_source_check
            CHECK (NOT block_previous_participants OR source_mission_id IS NOT NULL);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.missions'::regclass
          AND conname = 'missions_source_not_self_check'
    ) THEN
        ALTER TABLE public.missions
            ADD CONSTRAINT missions_source_not_self_check
            CHECK (source_mission_id IS NULL OR source_mission_id <> id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.missions'::regclass
          AND conname = 'missions_source_mission_id_fkey'
    ) THEN
        -- Deferred self-reference lets physical campaign cascade / 099 account purge
        -- delete source and copied missions together regardless of row deletion order,
        -- while preventing standalone physical deletion of a referenced source mission.
        ALTER TABLE public.missions
            ADD CONSTRAINT missions_source_mission_id_fkey
            FOREIGN KEY (source_mission_id)
            REFERENCES public.missions(id)
            ON DELETE NO ACTION
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
END;
$ddl$;

CREATE INDEX IF NOT EXISTS idx_missions_source_mission_id
    ON public.missions (source_mission_id)
    WHERE source_mission_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_missions_creator_client_operation_id
    ON public.missions (creator_id, client_operation_id)
    WHERE client_operation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.mission_copy_operations (
    creator_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    client_operation_id text NOT NULL,
    mission_id uuid NOT NULL REFERENCES public.missions(id) ON DELETE CASCADE,
    source_mission_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    request_fingerprint text NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (creator_id, client_operation_id)
);

ALTER TABLE public.mission_copy_operations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.mission_copy_operations FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE public.mission_copy_operations TO service_role;


-- ============================================================================
-- 2. SHARED EXPIRATION ELIGIBILITY HELPER
-- ============================================================================

CREATE OR REPLACE FUNCTION public._is_mission_expired_for_copy(
    p_status text,
    p_ends_at timestamptz,
    p_now timestamptz DEFAULT now()
)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
    SELECT (
        p_status = 'expired'
        OR (
            p_status IN ('open', 'full', 'paused_by_creator')
            AND p_ends_at IS NOT NULL
            AND p_ends_at <= coalesce(p_now, now())
        )
    );
$function$;

REVOKE ALL ON FUNCTION public._is_mission_expired_for_copy(text, timestamptz, timestamptz) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public._is_mission_expired_for_copy(text, timestamptz, timestamptz) TO service_role;


-- ============================================================================
-- 3. GET MISSION COPY DRAFT (OWNER ONLY)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_mission_copy_draft(
    p_source_mission_id uuid,
    p_campaign_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_now timestamptz := now();
    v_src record;
    v_camp record;
    v_balance int;
    v_reward_mode text;
    v_participants int;
    v_days int;
    v_reward int;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_source_mission_id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    select * into v_src
    from public.missions
    where id = p_source_mission_id;

    if v_src.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_src.status = 'deleted' then
        raise exception 'SOURCE_MISSION_DELETED';
    end if;

    if p_campaign_id is not null and v_src.campaign_id <> p_campaign_id then
        raise exception 'SOURCE_MISSION_CAMPAIGN_MISMATCH';
    end if;

    select * into v_camp
    from public.campaigns
    where id = v_src.campaign_id;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_user_id or v_src.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_camp.status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    if v_src.status = 'stopped_no_credits' then
        raise exception 'SOURCE_MISSION_STOPPED_NO_CREDITS';
    end if;

    if not public._is_mission_expired_for_copy(v_src.status, v_src.ends_at, v_now) then
        raise exception 'SOURCE_MISSION_NOT_EXPIRED';
    end if;

    if exists (
        select 1 from public.missions m2
        where m2.campaign_id = v_src.campaign_id
          and m2.status in ('open', 'full', 'paused_by_creator')
          and m2.ends_at > v_now
    ) then
        raise exception 'MISSION_ALREADY_EXISTS';
    end if;

    select credits into v_balance
    from public.profiles
    where id = v_user_id;

    v_reward_mode := case
        when lower(trim(coalesce(v_src.reward_mode, 'legacy'))) = 'daily' then 'daily'
        else 'once'
    end;
    v_participants := greatest(1, least(50, coalesce(v_src.max_participants, 5)));
    v_days := greatest(1, least(7, coalesce(v_src.duration_days, 3)));
    v_reward := greatest(10, coalesce(v_src.reward_credits, 10));

    return jsonb_build_object(
        'source_mission_id', v_src.id,
        'campaign_id', v_camp.id,
        'app_name', coalesce(v_camp.app_name, ''),
        'package_name', coalesce(v_camp.package_name, ''),
        'icon_url', v_camp.icon_url,
        'campaign_status', v_camp.status,
        'title', coalesce(v_src.title, ''),
        'description', coalesce(v_src.description, ''),
        'reward_mode', v_reward_mode,
        'max_participants', v_participants,
        'duration_days', v_days,
        'reward_credits', v_reward,
        'block_previous_participants', false,
        'user_credits', coalesce(v_balance, 0),
        'source_status', v_src.status,
        'source_reward_mode', coalesce(v_src.reward_mode, 'legacy')
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_mission_copy_draft(uuid, uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_mission_copy_draft(uuid, uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_mission_copy_draft(uuid, uuid) TO service_role;


-- ============================================================================
-- 4. CREATE MISSION FROM SOURCE (ATOMIC COPY + IDEMPOTENT OPERATION ID)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.create_mission_from_source(
    p_source_mission_id uuid,
    p_campaign_id uuid,
    p_title text,
    p_description text DEFAULT ''::text,
    p_max_participants integer DEFAULT 10,
    p_duration_days integer DEFAULT 3,
    p_reward_credits integer DEFAULT 10,
    p_reward_mode text DEFAULT 'once'::text,
    p_block_previous_participants boolean DEFAULT false,
    p_client_operation_id text DEFAULT NULL::text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_now timestamptz := now();
    v_mode text;
    v_block boolean := coalesce(p_block_previous_participants, false);
    v_op_id text := nullif(trim(coalesce(p_client_operation_id, '')), '');
    v_fingerprint text;
    v_receipt record;
    v_balance int;
    v_src_pre record;
    v_camp record;
    v_src record;
    v_create_res jsonb;
    v_new_mission_id uuid;
    v_result jsonb;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_source_mission_id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if p_campaign_id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
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

    -- Immutable request fingerprint (independent of later edit_mission mutations)
    v_fingerprint := encode(
        sha256(
            convert_to(
                jsonb_build_array(
                    p_source_mission_id::text,
                    p_campaign_id::text,
                    trim(p_title),
                    coalesce(p_description, ''),
                    p_max_participants,
                    p_duration_days,
                    p_reward_credits,
                    v_mode,
                    v_block
                )::text,
                'UTF8'
            )
        ),
        'hex'
    );

    -- Serialize duplicate retries of the same client_operation_id before any mutation
    if v_op_id is not null then
        perform pg_advisory_xact_lock(
            hashtextextended('mission_copy_op:' || v_user_id::text || ':' || v_op_id, 0)
        );

        select * into v_receipt
        from public.mission_copy_operations
        where creator_id = v_user_id
          and client_operation_id = v_op_id;

        if v_receipt.mission_id is not null then
            if v_receipt.request_fingerprint is distinct from v_fingerprint then
                raise exception 'CLIENT_OPERATION_ID_CONFLICT';
            end if;

            select credits into v_balance
            from public.profiles
            where id = v_user_id;

            return (v_receipt.response_json - 'balance') || jsonb_build_object(
                'balance', coalesce(v_balance, 0),
                'is_replay', true
            );
        end if;
    end if;

    -- 1. Non-locking pre-check of source mission before acquiring campaign lock.
    --    Prevents lock-order inversion with resume_mission (which locks paused unexpired
    --    missions before campaigns) when given an unexpired or cross-campaign source.
    select * into v_src_pre
    from public.missions
    where id = p_source_mission_id;

    if v_src_pre.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_src_pre.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_src_pre.status = 'deleted' then
        raise exception 'SOURCE_MISSION_DELETED';
    end if;

    if v_src_pre.campaign_id <> p_campaign_id then
        raise exception 'SOURCE_MISSION_CAMPAIGN_MISMATCH';
    end if;

    if v_src_pre.status = 'stopped_no_credits' then
        raise exception 'SOURCE_MISSION_STOPPED_NO_CREDITS';
    end if;

    if not public._is_mission_expired_for_copy(v_src_pre.status, v_src_pre.ends_at, v_now) then
        raise exception 'SOURCE_MISSION_NOT_EXPIRED';
    end if;

    -- 2. Lock campaign FIRST (consistent with create_mission_v2), then re-validate campaign
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

    if v_camp.status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- 3. Under campaign lock, acquire FOR UPDATE NOWAIT on both:
    --    (a) the eligible source mission (if still belonging to p_campaign_id and expired at v_now)
    --    (b) any mission rows of p_campaign_id that create_mission_v2 will sweep (ends_at <= v_now)
    --    Using the exact same v_now/now() prevents deadlocks with a resume_mission transaction
    --    that started before ends_at and already holds a mission lock while waiting for campaign.
    begin
        perform m.id
        from public.missions m
        where m.campaign_id = p_campaign_id
          and (
              (
                  m.id = p_source_mission_id
                  and (
                      m.status = 'expired'
                      or (
                          m.status in ('open', 'full', 'paused_by_creator')
                          and m.ends_at <= v_now
                      )
                  )
              )
              or (
                  m.status in ('open', 'full', 'paused_by_creator')
                  and m.ends_at <= v_now
              )
          )
        order by m.id
        for update nowait;
    exception
        when lock_not_available then
            raise exception 'MISSION_BUSY_RETRY';
    end;

    select * into v_src
    from public.missions
    where id = p_source_mission_id
      and campaign_id = p_campaign_id
      and (
          status = 'expired'
          or (
              status in ('open', 'full', 'paused_by_creator')
              and ends_at <= v_now
          )
      );

    if v_src.id is null then
        select * into v_src_pre
        from public.missions
        where id = p_source_mission_id;

        if v_src_pre.id is null then
            raise exception 'MISSION_NOT_FOUND';
        elsif v_src_pre.creator_id <> v_user_id then
            raise exception 'PERMISSION_DENIED';
        elsif v_src_pre.status = 'deleted' then
            raise exception 'SOURCE_MISSION_DELETED';
        elsif v_src_pre.campaign_id <> p_campaign_id then
            raise exception 'SOURCE_MISSION_CAMPAIGN_MISMATCH';
        elsif v_src_pre.status = 'stopped_no_credits' then
            raise exception 'SOURCE_MISSION_STOPPED_NO_CREDITS';
        else
            raise exception 'SOURCE_MISSION_NOT_EXPIRED';
        end if;
    end if;

    if v_src.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Reuse create_mission_v2 for authoritative fee, escrow, balance, and active-slot rules
    v_create_res := public.create_mission_v2(
        p_campaign_id,
        p_title,
        p_description,
        p_max_participants,
        p_duration_days,
        p_reward_credits,
        v_mode
    );

    v_new_mission_id := (v_create_res ->> 'mission_id')::uuid;

    update public.missions
    set source_mission_id = p_source_mission_id,
        block_previous_participants = v_block,
        client_operation_id = v_op_id
    where id = v_new_mission_id;

    v_result := v_create_res || jsonb_build_object(
        'source_mission_id', p_source_mission_id,
        'block_previous_participants', v_block,
        'is_replay', false
    );

    if v_op_id is not null then
        insert into public.mission_copy_operations (
            creator_id,
            client_operation_id,
            mission_id,
            source_mission_id,
            campaign_id,
            request_fingerprint,
            response_json
        ) values (
            v_user_id,
            v_op_id,
            v_new_mission_id,
            p_source_mission_id,
            p_campaign_id,
            v_fingerprint,
            v_result
        );
    end if;

    return v_result;
end;
$function$;

REVOKE ALL ON FUNCTION public.create_mission_from_source(uuid, uuid, text, text, integer, integer, integer, text, boolean, text) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.create_mission_from_source(uuid, uuid, text, text, integer, integer, integer, text, boolean, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.create_mission_from_source(uuid, uuid, text, text, integer, integer, integer, text, boolean, text) TO service_role;


-- ============================================================================
-- 5. JOIN MISSION (KEEP SIGNATURE; ENFORCE DIRECT-SOURCE BLOCK BEFORE WRITES)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.join_mission(p_mission_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
    v_camp_status text;
    v_new_status text;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id = v_user_id then
        raise exception 'CANNOT_JOIN_OWN_MISSION';
    end if;

    if v_mission.status not in ('open', 'full') then
        raise exception 'MISSION_NOT_OPEN';
    end if;

    if v_mission.ends_at <= now() then
        raise exception 'MISSION_EXPIRED';
    end if;

    if v_mission.participant_count >= v_mission.max_participants then
        raise exception 'MISSION_FULL';
    end if;

    if exists (
        select 1 from public.mission_participants
        where mission_id = p_mission_id and user_id = v_user_id
    ) then
        raise exception 'ALREADY_JOINED';
    end if;

    if coalesce(v_mission.block_previous_participants, false)
       and v_mission.source_mission_id is not null
       and exists (
           select 1 from public.mission_participants mp_prev
           where mp_prev.mission_id = v_mission.source_mission_id
             and mp_prev.user_id = v_user_id
       )
    then
        raise exception 'MISSION_PREVIOUS_PARTICIPANT_BLOCKED';
    end if;

    select status into v_camp_status from public.campaigns where id = v_mission.campaign_id;
    if v_camp_status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp_status not in ('open', 'production', 'completed') then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    insert into public.mission_participants (mission_id, user_id)
    values (p_mission_id, v_user_id);

    v_new_status := case when v_mission.participant_count + 1 >= v_mission.max_participants
                         then 'full' else v_mission.status end;

    update public.missions
    set participant_count = participant_count + 1,
        status = v_new_status
    where id = p_mission_id;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        v_mission.creator_id,
        'Người tham gia nhiệm vụ mới 🎯',
        format('"%s" vừa nhận nhiệm vụ "%s". Hãy sẵn sàng duyệt báo cáo test để trả thưởng.',
               coalesce((select display_name from public.profiles where id = v_user_id), 'Một người dùng'),
               v_mission.title),
        'mission',
        jsonb_build_object('mission_id', p_mission_id, 'campaign_id', v_mission.campaign_id)
    );

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'campaign_id', v_mission.campaign_id,
        'status', v_new_status
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.join_mission(uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.join_mission(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.join_mission(uuid) TO service_role;


-- ============================================================================
-- 6. DISCOVERY & DETAIL RPCS WITH VIEWER-ONLY BLOCK FLAG & EFFECTIVE EXPIRATION
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
                    'source_mission_id', t.source_mission_id,
                    'block_previous_participants', t.block_previous_participants,
                    'is_blocked_previous_participant', t.is_blocked_previous_participant,
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
                    m.source_mission_id,
                    coalesce(m.block_previous_participants, false) as block_previous_participants,
                    (
                        v_user_id is not null
                        and coalesce(m.block_previous_participants, false)
                        and m.source_mission_id is not null
                        and exists (
                            select 1 from public.mission_participants mp_prev
                            where mp_prev.mission_id = m.source_mission_id
                              and mp_prev.user_id = v_user_id
                        )
                    ) as is_blocked_previous_participant,
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


CREATE OR REPLACE FUNCTION public.get_campaign_mission(
    p_campaign_id uuid,
    p_mission_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_now timestamptz := now();
    v_vn_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_row record;
    v_effective_status text;
begin
    if p_mission_id is not null then
        select
            m.id, m.title, m.description, m.reward_credits,
            m.max_participants, m.participant_count, m.status,
            m.ends_at, m.created_at, m.campaign_id, m.creator_id,
            m.reward_mode, m.duration_days, m.fee_credits,
            m.escrow_total_credits, m.escrow_remaining_credits,
            m.tester_announcement, m.version,
            m.source_mission_id,
            coalesce(m.block_previous_participants, false) as block_previous_participants,
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
            m.source_mission_id,
            coalesce(m.block_previous_participants, false) as block_previous_participants,
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
        return jsonb_build_object(
            'mission', null,
            'server_now', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
        );
    end if;

    v_effective_status := case
        when v_row.status in ('open', 'full', 'paused_by_creator') and v_row.ends_at <= v_now then 'expired'
        else v_row.status
    end;

    return jsonb_build_object(
        'mission', jsonb_build_object(
            'id', v_row.id,
            'title', v_row.title,
            'description', v_row.description,
            'reward_credits', v_row.reward_credits,
            'max_participants', v_row.max_participants,
            'participant_count', v_row.participant_count,
            'status', v_effective_status,
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
            'source_mission_id', v_row.source_mission_id,
            'block_previous_participants', v_row.block_previous_participants,
            'is_blocked_previous_participant', (
                v_user_id is not null
                and v_row.block_previous_participants
                and v_row.source_mission_id is not null
                and exists (
                    select 1 from public.mission_participants mp_prev
                    where mp_prev.mission_id = v_row.source_mission_id
                      and mp_prev.user_id = v_user_id
                )
            ),
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
        ),
        'server_now', to_char(v_now at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_mission(uuid, uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_campaign_mission(uuid, uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_campaign_mission(uuid, uuid) TO service_role;
