-- ============================================================================
-- 12 Tester — Supabase migration 044: Hardening luồng Nhiệm vụ App
-- (M02, M03, M05, M06, M07)
-- ============================================================================

-- ============================================================================
-- 1. Bảng outbox / settlement jobs cho trả thưởng nhiệm vụ (M05)
-- ============================================================================
create table if not exists public.mission_settlement_jobs (
    id uuid primary key default gen_random_uuid(),
    report_id uuid not null references public.tester_reports(id) on delete cascade,
    mission_id uuid not null references public.missions(id) on delete cascade,
    participant_id uuid not null references public.mission_participants(id) on delete cascade,
    campaign_id uuid not null references public.campaigns(id) on delete cascade,
    tester_id uuid not null references public.profiles(id) on delete cascade,
    creator_id uuid not null references public.profiles(id) on delete cascade,
    reward_credits int not null,
    status text not null default 'pending' check (status in ('pending', 'completed', 'skipped', 'failed')),
    skip_reason text,
    attempt_count int not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (report_id)
);

alter table public.mission_settlement_jobs add column if not exists next_attempt_at timestamptz not null default now();

create index if not exists idx_mission_settlement_jobs_status
on public.mission_settlement_jobs(status)
where status in ('pending', 'failed');

create index if not exists idx_mission_settlement_jobs_retry
on public.mission_settlement_jobs(next_attempt_at asc, created_at asc)
where status in ('pending', 'failed');

alter table public.mission_settlement_jobs enable row level security;
revoke all on table public.mission_settlement_jobs from public, anon, authenticated;
grant all on table public.mission_settlement_jobs to service_role;

-- ============================================================================
-- 2. Bảng audit hòa giải xung đột & Dọn dẹp dữ liệu cũ & Unique partial index (M02, R04)
-- ============================================================================
create table if not exists public.mission_reconciliation_events (
    id uuid primary key default gen_random_uuid(),
    mission_id uuid not null references public.missions(id) on delete cascade,
    campaign_id uuid not null references public.campaigns(id) on delete cascade,
    creator_id uuid not null references public.profiles(id) on delete cascade,
    reconciled_action text not null,
    reason text not null,
    details jsonb default '{}'::jsonb,
    created_at timestamptz not null default now()
);

alter table public.mission_reconciliation_events enable row level security;
revoke all on table public.mission_reconciliation_events from public, anon, authenticated;
grant all on table public.mission_reconciliation_events to service_role;

-- Đồng bộ mission đã quá hạn
update public.missions
set status = 'expired'
where status in ('open', 'full', 'paused_by_creator')
  and ends_at <= now();

-- Hòa giải các mission trùng lặp active trên cùng campaign trước khi tạo unique index (R04):
-- Giữ lại mission có số tester đang tham gia ('joined') nhiều nhất; nếu bằng nhau thì giữ bản mới nhất.
-- Các bản trùng lặp khác được kết thúc ('expired', không phải stopped_no_credits), ghi audit và thông báo.
do $$
declare
    v_dup record;
    v_winner_id uuid;
    v_participant record;
begin
    for v_dup in
        with active_camps as (
            select campaign_id
            from public.missions
            where status in ('open', 'full', 'paused_by_creator')
            group by campaign_id
            having count(*) > 1
        ),
        ranked as (
            select
                m.id,
                m.campaign_id,
                m.creator_id,
                m.title,
                m.reward_credits,
                m.status,
                (select count(*) from public.mission_participants mp where mp.mission_id = m.id and mp.status = 'joined') as active_participants,
                row_number() over (
                    partition by m.campaign_id
                    order by
                        (select count(*) from public.mission_participants mp where mp.mission_id = m.id and mp.status = 'joined') desc,
                        m.created_at desc,
                        m.id desc
                ) as rn
            from public.missions m
            join active_camps ac on ac.campaign_id = m.campaign_id
            where m.status in ('open', 'full', 'paused_by_creator')
        )
        select * from ranked where rn > 1
    loop
        select m.id into v_winner_id
        from public.missions m
        where m.campaign_id = v_dup.campaign_id
          and m.status in ('open', 'full', 'paused_by_creator')
          and m.id <> v_dup.id
        order by
            (select count(*) from public.mission_participants mp where mp.mission_id = m.id and mp.status = 'joined') desc,
            m.created_at desc,
            m.id desc
        limit 1;

        update public.missions
        set status = 'expired',
            ends_at = least(ends_at, now())
        where id = v_dup.id;

        insert into public.mission_reconciliation_events (
            mission_id, campaign_id, creator_id, reconciled_action, reason, details
        ) values (
            v_dup.id,
            v_dup.campaign_id,
            v_dup.creator_id,
            'retired_duplicate',
            'Đồng bộ nâng cấp hệ thống: mỗi ứng dụng chỉ có tối đa 1 nhiệm vụ hoạt động.',
            jsonb_build_object(
                'previous_status', v_dup.status,
                'active_participants', v_dup.active_participants,
                'winner_mission_id', v_winner_id
            )
        );

        insert into public.notifications (
            user_id, title, message, type, data
        ) values (
            v_dup.creator_id,
            'Đồng bộ nhiệm vụ ứng dụng',
            'Nhiệm vụ "' || v_dup.title || '" đã được kết thúc để chuẩn hóa hệ thống (ứng dụng đã có nhiệm vụ đang chạy).',
            'system',
            jsonb_build_object(
                'mission_id', v_dup.id,
                'campaign_id', v_dup.campaign_id,
                'action', 'duplicate_reconciled'
            )
        );

        for v_participant in
            select user_id from public.mission_participants
            where mission_id = v_dup.id and status = 'joined'
        loop
            insert into public.notifications (
                user_id, title, message, type, data
            ) values (
                v_participant.user_id,
                'Nhiệm vụ ứng dụng đã kết thúc',
                'Nhiệm vụ "' || v_dup.title || '" đã kết thúc do cập nhật hệ thống.',
                'mission',
                jsonb_build_object(
                    'mission_id', v_dup.id,
                    'campaign_id', v_dup.campaign_id,
                    'action', 'duplicate_reconciled'
                )
            );
        end loop;
    end loop;
end;
$$;

create unique index if not exists idx_missions_campaign_active_unique
on public.missions(campaign_id)
where status in ('open', 'full', 'paused_by_creator');

-- ============================================================================
-- 3. Cập nhật create_mission (M02, M06)
-- ============================================================================
create or replace function public.create_mission(
    p_campaign_id uuid,
    p_title text,
    p_description text default '',
    p_max_participants int default 10,
    p_duration_days int default 3,
    p_reward_credits int default 10
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
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

    if v_camp.status <> 'open' then
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
        status, ends_at
    ) values (
        v_user_id, p_campaign_id, trim(p_title), coalesce(p_description, ''),
        p_max_participants, p_duration_days, p_reward_credits, v_fee,
        'open', v_ends_at
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
        'ends_at', to_char(v_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$$;

revoke execute on function public.create_mission(uuid, text, text, int, int, int) from public, anon;
grant execute on function public.create_mission(uuid, text, text, int, int, int) to authenticated, service_role;

-- ============================================================================
-- 4. Cập nhật resume_mission (M02 + bảo toàn 043)
-- ============================================================================
create or replace function public.resume_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_mission record;
    v_camp record;
    v_new_status text;
begin
    if v_user_id is null then
        raise exception 'Not authenticated';
    end if;

    select * into v_mission
    from public.missions
    where id = p_mission_id
    for update;

    if v_mission.id is null or v_mission.status = 'deleted' then
        raise exception 'MISSION_NOT_FOUND';
    end if;

    if v_mission.creator_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_mission.status <> 'paused_by_creator' then
        raise exception 'MISSION_NOT_PAUSED';
    end if;

    -- Bảo toàn sửa 043: nếu đã hết hạn, cập nhật DB và trả kết quả JSON, không rollback UPDATE
    if v_mission.ends_at <= now() then
        update public.missions
        set status = 'expired'
        where id = p_mission_id;

        return jsonb_build_object(
            'success', false,
            'code', 'MISSION_EXPIRED',
            'error', 'MISSION_EXPIRED',
            'message', 'Nhiệm vụ đã hết hạn lưu hành',
            'mission_id', p_mission_id,
            'status', 'expired'
        );
    end if;

    -- Khóa campaign row
    select * into v_camp
    from public.campaigns
    where id = v_mission.campaign_id
    for update;

    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- M02: Đồng bộ hết hạn trên campaign trước khi kiểm tra xung đột
    update public.missions
    set status = 'expired'
    where campaign_id = v_mission.campaign_id
      and id <> p_mission_id
      and status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    if exists (
        select 1 from public.missions
        where campaign_id = v_mission.campaign_id
          and id <> p_mission_id
          and status in ('open', 'full')
    ) then
        raise exception 'MISSION_ALREADY_EXISTS';
    end if;

    v_new_status := case when v_mission.participant_count >= v_mission.max_participants
                         then 'full' else 'open' end;

    update public.missions
    set status = v_new_status
    where id = p_mission_id;

    return jsonb_build_object(
        'success', true,
        'mission_id', p_mission_id,
        'status', v_new_status
    );
end;
$$;

revoke execute on function public.resume_mission(uuid) from public, anon;
grant execute on function public.resume_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 5. Helper _settle_mission_reward với report identity & outbox retry (M03, M05)
-- ============================================================================
create or replace function public._settle_mission_reward(p_report_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_match record;
    v_job record;
    v_saved_m record;
    v_saved_mp record;
    v_creator_balance int;
    v_tester_balance int;
    v_paid boolean := false;
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

    -- Báo cáo phải ở trạng thái approved
    if v_report.status <> 'approved' then
        return jsonb_build_object('settled', false, 'reason', 'REPORT_NOT_APPROVED');
    end if;

    -- Kiểm tra báo cáo này đã được thanh toán hoặc đã xử lý bỏ qua chưa
    select * into v_job
    from public.mission_settlement_jobs
    where report_id = p_report_id
    for update;

    if v_job.id is not null and v_job.status in ('completed', 'skipped') then
        return jsonb_build_object('settled', false, 'reason', 'ALREADY_SETTLED', 'job_status', v_job.status);
    end if;

    -- Nếu job đã tồn tại (lần retry): kiểm tra trạng thái mission và participant theo job identity (R05)
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

        if v_saved_mp.status <> 'joined' then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'PARTICIPANT_NOT_JOINED',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'TERMINATED_PARTICIPANT_NOT_JOINED');
        end if;

        if v_saved_m.ends_at <= now() or v_saved_m.status in ('expired', 'cancelled', 'deleted', 'stopped_no_credits') then
            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'MISSION_EXPIRED_OR_TERMINATED',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'TERMINATED_MISSION_EXPIRED');
        end if;

        if v_saved_m.status = 'paused_by_creator' then
            -- Tạm hoãn và lùi lịch retry với backoff để không chiếm batch (R05)
            update public.mission_settlement_jobs
            set attempt_count = attempt_count + 1,
                next_attempt_at = now() + interval '5 minutes',
                last_error = 'MISSION_PAUSED_BY_CREATOR',
                updated_at = now()
            where id = v_job.id;
            return jsonb_build_object('settled', false, 'reason', 'MISSION_PAUSED_BY_CREATOR');
        end if;

        -- Mission open/full và participant joined
        select
            v_saved_m.id as mission_id,
            v_saved_m.creator_id,
            v_saved_m.title as mission_title,
            v_job.reward_credits,
            v_job.campaign_id,
            v_saved_mp.id as participant_id,
            v_saved_mp.joined_at
        into v_match;
    else
        -- Lần đầu (chưa có job): Tìm nhiệm vụ và participant đủ điều kiện:
        -- 1. Cùng campaign_id và user_id
        -- 2. Participant đang ở trạng thái 'joined'
        -- 3. Báo cáo nộp sau lúc nhận: v_report.created_at >= mp.joined_at
        -- 4. Nhiệm vụ đang open/full
        -- 5. M04: Còn hạn ends_at > now() tại thời điểm duyệt theo quy tắc hiện hành
        select
            m.id as mission_id,
            m.creator_id,
            m.title as mission_title,
            m.reward_credits,
            m.campaign_id,
            mp.id as participant_id,
            mp.joined_at
        into v_match
        from public.missions m
        join public.mission_participants mp on mp.mission_id = m.id
        where m.campaign_id = v_report.campaign_id
          and mp.user_id = v_report.user_id
          and mp.status = 'joined'
          and v_report.created_at >= mp.joined_at
          and m.status in ('open', 'full')
          and m.ends_at > now()
        order by m.created_at desc
        limit 1
        for update of m;

        if v_match.mission_id is null then
            return jsonb_build_object('settled', false, 'reason', 'NO_ELIGIBLE_MISSION');
        end if;

        -- M05: Lưu / cập nhật bản ghi settlement outbox bền vững
        insert into public.mission_settlement_jobs (
            report_id, mission_id, participant_id, campaign_id,
            tester_id, creator_id, reward_credits, status,
            next_attempt_at
        ) values (
            v_report.id, v_match.mission_id, v_match.participant_id, v_match.campaign_id,
            v_report.user_id, v_match.creator_id, v_match.reward_credits, 'pending',
            now()
        )
        on conflict (report_id) do update
        set mission_id = excluded.mission_id,
            participant_id = excluded.participant_id,
            updated_at = now()
        returning * into v_job;
    end if;

    -- Khối thực hiện thanh toán trong subtransaction để lỗi không cuốn bay bản ghi job
    begin
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
                format('Hoàn thành nhiệm vụ "%s" (+%s Credit nhiệm vụ)', v_match.mission_title, v_match.reward_credits),
                v_tester_balance
            );

            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_report.user_id,
                'Hoàn thành nhiệm vụ! 🎉',
                format('Bạn nhận được %s Credit từ nhiệm vụ "%s". Cảm ơn bạn đã kiểm thử!',
                       v_match.reward_credits, v_match.mission_title),
                'mission',
                jsonb_build_object('mission_id', v_match.mission_id, 'campaign_id', v_match.campaign_id, 'reward', v_match.reward_credits)
            );

            update public.mission_settlement_jobs
            set status = 'completed',
                updated_at = now()
            where id = v_job.id;

            v_paid := true;
        else
            -- Chủ nhiệm vụ hết Credit trả thưởng: dừng nhiệm vụ theo quy tắc sản phẩm
            update public.missions
            set status = 'stopped_no_credits'
            where id = v_match.mission_id
              and status in ('open', 'full');

            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_match.creator_id,
                'Nhiệm vụ đã dừng ⛔',
                format('Nhiệm vụ "%s" đã dừng vì số Credit của bạn không đủ để trả thưởng người tham gia.',
                       v_match.mission_title),
                'mission',
                jsonb_build_object('mission_id', v_match.mission_id, 'campaign_id', v_match.campaign_id, 'stopped', true)
            );

            update public.mission_settlement_jobs
            set status = 'skipped',
                skip_reason = 'INSUFFICIENT_CREATOR_CREDITS',
                updated_at = now()
            where id = v_job.id;
        end if;

        return jsonb_build_object('settled', true, 'paid', v_paid, 'mission_id', v_match.mission_id);
    exception when others then
        v_backoff := case
            when coalesce(v_job.attempt_count, 0) = 0 then interval '0 seconds'
            else (interval '30 seconds' * power(2, least(v_job.attempt_count, 5)))
        end;
        -- Ghi nhận lỗi kỹ thuật vào job để retry sau (kèm backoff để không chặn batch - R05)
        update public.mission_settlement_jobs
        set status = 'failed',
            attempt_count = attempt_count + 1,
            next_attempt_at = now() + v_backoff,
            last_error = sqlerrm,
            updated_at = now()
        where id = v_job.id;

        raise notice 'settle_mission_reward failed: %', sqlerrm;
        return jsonb_build_object('settled', false, 'error', sqlerrm, 'job_id', v_job.id);
    end;
end;
$$;

revoke execute on function public._settle_mission_reward(uuid) from public, anon, authenticated;
grant execute on function public._settle_mission_reward(uuid) to service_role;

-- Overload tương thích chữ ký cũ (campaign_id, user_id)
create or replace function public._settle_mission_reward(
    p_campaign_id uuid,
    p_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report_id uuid;
begin
    if p_campaign_id is null or p_user_id is null then
        return jsonb_build_object('settled', false);
    end if;

    select id into v_report_id
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = p_user_id
      and status = 'approved'
    order by coalesce(reviewed_at, created_at) desc, created_at desc
    limit 1;

    if v_report_id is not null then
        return public._settle_mission_reward(v_report_id);
    end if;

    return jsonb_build_object('settled', false);
end;
$$;

revoke execute on function public._settle_mission_reward(uuid, uuid) from public, anon, authenticated;
grant execute on function public._settle_mission_reward(uuid, uuid) to service_role;

-- ============================================================================
-- 6. RPC retry_failed_mission_settlements (M05, R05)
-- ============================================================================
create or replace function public.retry_failed_mission_settlements(p_limit int default 50)
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_job record;
    v_success_count int := 0;
    v_res jsonb;
begin
    for v_job in
        select id, report_id
        from public.mission_settlement_jobs
        where status in ('pending', 'failed')
          and attempt_count < 10
          and next_attempt_at <= now()
        order by next_attempt_at asc, created_at asc
        limit p_limit
        for update skip locked
    loop
        v_res := public._settle_mission_reward(v_job.report_id);
        if coalesce((v_res->>'settled')::boolean, false) and coalesce((v_res->>'paid')::boolean, false) then
            v_success_count := v_success_count + 1;
        end if;
    end loop;

    return v_success_count;
end;
$$;

revoke execute on function public.retry_failed_mission_settlements(int) from public, anon, authenticated;
grant execute on function public.retry_failed_mission_settlements(int) to service_role;

-- ============================================================================
-- 7. Cập nhật approve_tester_report & auto_accept_expired_reports (M03, M05)
-- ============================================================================
create or replace function public.approve_tester_report(
    p_report_id uuid,
    p_bonus int default 0
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
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
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
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

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

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

    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

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

    if v_is_owner then
        v_owner_bonus := 2;
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
    end if;

    -- M03: Chuyển p_report_id sang helper settlement
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
$$;

revoke execute on function public.approve_tester_report(uuid, int) from public, anon;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, service_role;

create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
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
        v_base := coalesce(
            nullif(v_report.provisional_granted_credits, 0),
            nullif(v_report.bonus_awarded, 0),
            0
        );
        v_total_bonus := coalesce(nullif(v_report.bonus_awarded, 0), v_base) + v_tip;

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
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;

-- ============================================================================
-- 8. Cập nhật get_open_missions (M07: Sắp xếp trước khi Limit 100)
-- ============================================================================
create or replace function public.get_open_missions()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    update public.missions
    set status = 'expired'
    where status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

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
                    'is_mine', t.creator_id = v_user_id,
                    'has_joined', t.has_joined,
                    'has_completed', t.has_completed
                )
                order by t.sort_mine desc, t.sort_active desc, t.created_at desc, t.id desc
            )
            from (
                select
                    m.id, m.title, m.description, m.reward_credits,
                    m.max_participants, m.participant_count, m.status,
                    m.ends_at, m.created_at, m.campaign_id, m.creator_id,
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
                    ) as has_completed
                from public.missions m
                join public.campaigns c on c.id = m.campaign_id
                left join public.profiles p on p.id = m.creator_id
                where m.status <> 'deleted'
                  and m.created_at > now() - interval '14 days'
                -- M07: Sắp xếp xác định trước khi limit 100 để không rơi rớt bản ghi mới nhất hoặc bản ghi của chính user
                order by
                    case
                        when m.creator_id = v_user_id then 1
                        when exists (
                            select 1 from public.mission_participants mp_mine
                            where mp_mine.mission_id = m.id and mp_mine.user_id = v_user_id
                        ) then 1
                        else 0
                    end desc,
                    case m.status
                        when 'open' then 2
                        when 'full' then 2
                        when 'paused_by_creator' then 1
                        else 0
                    end desc,
                    m.created_at desc,
                    m.id desc
                limit 100
            ) t
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function public.get_open_missions() from public, anon;
grant execute on function public.get_open_missions() to authenticated, service_role;

-- ============================================================================
-- 9. RPC get_my_active_mission_campaign_ids (M02: Độc lập với 100 discovery limit)
-- ============================================================================
create or replace function public.get_my_active_mission_campaign_ids()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    update public.missions
    set status = 'expired'
    where creator_id = v_user_id
      and status in ('open', 'full', 'paused_by_creator')
      and ends_at <= now();

    return coalesce((
        select jsonb_agg(campaign_id)
        from public.missions
        where creator_id = v_user_id
          and status in ('open', 'full', 'paused_by_creator')
    ), '[]'::jsonb);
end;
$$;

revoke execute on function public.get_my_active_mission_campaign_ids() from public, anon;
grant execute on function public.get_my_active_mission_campaign_ids() to authenticated, service_role;

notify pgrst, 'reload schema';
