-- ============================================================================
-- 12 Tester — Supabase migration 039: Nhiệm vụ do người dùng tạo (Missions)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Quy tắc:
--   • Chủ app mở nhiệm vụ cho 1 app đang đăng của mình (1 nhiệm vụ đang mở / app).
--   • Thiết lập: tên, mô tả, số người tham gia (1–50), số ngày nhận nhiệm vụ
--     (1–7), Credit trả người tham gia mỗi lượt (tối thiểu 10).
--   • Yêu cầu số dư tối thiểu 300 Credit; trừ thẳng 50 Credit × số ngày
--     ngay khi tạo nhiệm vụ (phí nền tảng, không hoàn lại).
--   • Người tham gia bấm "Nhận nhiệm vụ" (mỗi người 1 lần / nhiệm vụ) rồi nộp
--     báo cáo test cho app liên kết. Khi báo cáo ĐƯỢC DUYỆT (chủ app duyệt tay
--     hoặc hệ thống tự duyệt sau 8h), hệ thống trừ Credit chủ nhiệm vụ và
--     chuyển cho người tham gia (ngoài thưởng test thông thường).
--   • Nhiệm vụ dừng khi: đủ số người (full) / hết số ngày (expired) / chủ
--     nhiệm vụ hết Credit trả thưởng (stopped_no_credits — không trả một phần).
-- ============================================================================

-- ============================================================================
-- 1. Bảng missions & mission_participants
-- ============================================================================
create table if not exists public.missions (
    id uuid primary key default gen_random_uuid(),
    creator_id uuid not null references public.profiles(id) on delete cascade,
    campaign_id uuid not null references public.campaigns(id) on delete cascade,
    title text not null,
    description text not null default '',
    max_participants int not null check (max_participants between 1 and 50),
    duration_days int not null check (duration_days between 1 and 7),
    reward_credits int not null check (reward_credits >= 10),
    fee_credits int not null default 0,
    status text not null default 'open'
        check (status in ('open', 'full', 'stopped_no_credits', 'expired')),
    participant_count int not null default 0,
    created_at timestamptz not null default now(),
    ends_at timestamptz not null
);

create index if not exists idx_missions_status_created on public.missions (status, created_at desc);
create index if not exists idx_missions_campaign on public.missions (campaign_id);
create index if not exists idx_missions_creator on public.missions (creator_id);

create table if not exists public.mission_participants (
    id uuid primary key default gen_random_uuid(),
    mission_id uuid not null references public.missions(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    status text not null default 'joined' check (status in ('joined', 'completed')),
    joined_at timestamptz not null default now(),
    completed_at timestamptz,
    unique (mission_id, user_id)
);

create index if not exists idx_mission_participants_user on public.mission_participants (user_id);

-- RLS: chỉ đọc; mọi ghi đi qua RPC security definer bên dưới.
alter table public.missions enable row level security;
alter table public.mission_participants enable row level security;

drop policy if exists "missions_read_authenticated" on public.missions;
create policy "missions_read_authenticated" on public.missions
    for select to authenticated using (true);

drop policy if exists "mission_participants_read_authenticated" on public.mission_participants;
create policy "mission_participants_read_authenticated" on public.mission_participants
    for select to authenticated using (true);

-- ============================================================================
-- 2. RPC create_mission — mở nhiệm vụ, trừ phí 50 Credit × số ngày upfront
-- ============================================================================
create or replace function public.create_mission(
    p_campaign_id uuid,
    p_title text,
    p_description text,
    p_max_participants int,
    p_duration_days int,
    p_reward_credits int
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
    v_mission_id uuid;
    v_ends_at timestamptz;
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

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id <> v_user_id then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    if exists (
        select 1 from public.missions
        where campaign_id = p_campaign_id
          and status in ('open', 'full')
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
-- 3. RPC join_mission — người tham gia nhận nhiệm vụ (mỗi người 1 lần)
-- ============================================================================
create or replace function public.join_mission(p_mission_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
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

    select status into v_camp_status from public.campaigns where id = v_mission.campaign_id;
    if v_camp_status is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp_status <> 'open' then
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
$$;

revoke execute on function public.join_mission(uuid) from public, anon;
grant execute on function public.join_mission(uuid) to authenticated, service_role;

-- ============================================================================
-- 4. RPC _settle_mission_reward — trả thưởng khi báo cáo test được duyệt
-- Gọi từ approve_tester_report / auto_accept_expired_reports. Idempotent:
-- unique(mission_id, user_id) + chuyển trạng thái 'joined' → 'completed'.
-- ============================================================================
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
    v_mission record;
    v_creator_balance int;
    v_tester_balance int;
    v_paid boolean := false;
begin
    if p_user_id is null then
        return jsonb_build_object('settled', false);
    end if;

    begin
        select m.* into v_mission
        from public.missions m
        join public.mission_participants mp on mp.mission_id = m.id
        where m.campaign_id = p_campaign_id
          and mp.user_id = p_user_id
          and mp.status = 'joined'
          and m.status in ('open', 'full')
          and m.ends_at > now()
        order by m.created_at desc
        limit 1
        for update of m;

        if v_mission.id is null then
            return jsonb_build_object('settled', false);
        end if;

        select credits into v_creator_balance
        from public.profiles
        where id = v_mission.creator_id
        for update;

        if coalesce(v_creator_balance, 0) >= v_mission.reward_credits then
            update public.mission_participants
            set status = 'completed',
                completed_at = now()
            where mission_id = v_mission.id
              and user_id = p_user_id
              and status = 'joined';

            if not found then
                -- Đã được trả thưởng trước đó (đua duyệt tay/auto): không trừ/chuyển lần 2.
                return jsonb_build_object('settled', false);
            end if;

            update public.profiles
            set credits = credits - v_mission.reward_credits
            where id = v_mission.creator_id
            returning credits into v_creator_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_mission.creator_id,
                -v_mission.reward_credits,
                'mission_reward',
                format('Trả thưởng nhiệm vụ "%s" cho người tham gia', v_mission.title),
                v_creator_balance
            );

            update public.profiles
            set credits = credits + v_mission.reward_credits
            where id = p_user_id
            returning credits into v_tester_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                p_user_id,
                v_mission.reward_credits,
                'mission_reward',
                format('Hoàn thành nhiệm vụ "%s" (+%s Credit nhiệm vụ)', v_mission.title, v_mission.reward_credits),
                v_tester_balance
            );

            insert into public.notifications (user_id, title, message, type, data)
            values (
                p_user_id,
                'Hoàn thành nhiệm vụ! 🎉',
                format('Bạn nhận được %s Credit từ nhiệm vụ "%s". Cảm ơn bạn đã kiểm thử!',
                       v_mission.reward_credits, v_mission.title),
                'mission',
                jsonb_build_object('mission_id', v_mission.id, 'campaign_id', v_mission.campaign_id, 'reward', v_mission.reward_credits)
            );

            v_paid := true;
        else
            -- Chủ nhiệm vụ hết Credit trả thưởng: dừng nhiệm vụ, không trả một phần.
            update public.missions
            set status = 'stopped_no_credits'
            where id = v_mission.id
              and status in ('open', 'full');

            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_mission.creator_id,
                'Nhiệm vụ đã dừng ⛔',
                format('Nhiệm vụ "%s" đã dừng vì số Credit của bạn không đủ để trả thưởng người tham gia.',
                       v_mission.title),
                'mission',
                jsonb_build_object('mission_id', v_mission.id, 'campaign_id', v_mission.campaign_id, 'stopped', true)
            );
        end if;

        return jsonb_build_object('settled', true, 'paid', v_paid, 'mission_id', v_mission.id);
    exception when others then
        -- Lỗi结算 không được làm hỏng giao dịch duyệt báo cáo; rollback toàn bộ
        -- phần thưởng của block này (savepoint) và ghi notice để kiểm tra sau.
        raise notice 'settle_mission_reward failed: %', sqlerrm;
        return jsonb_build_object('settled', false, 'error', sqlerrm);
    end;
end;
$$;

revoke execute on function public._settle_mission_reward(uuid, uuid) from public, anon, authenticated;
grant execute on function public._settle_mission_reward(uuid, uuid) to service_role;

-- ============================================================================
-- 5. RPC get_open_missions — danh sách nhiệm vụ cho tab "Nhiệm vụ App"
-- Tự động đánh dấu expired (lazy sync) rồi trả danh sách gần đây.
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
    where status in ('open', 'full')
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
                order by t.is_active desc, t.created_at desc
            )
            from (
                select
                    m.id, m.title, m.description, m.reward_credits,
                    m.max_participants, m.participant_count, m.status,
                    m.ends_at, m.created_at, m.campaign_id, m.creator_id,
                    c.app_name, c.icon_url, c.package_name,
                    coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), '') as creator_name,
                    (m.status in ('open', 'full')) as is_active,
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
                where m.created_at > now() - interval '14 days'
                limit 100
            ) t
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function public.get_open_missions() from public, anon;
grant execute on function public.get_open_missions() to authenticated, service_role;

-- ============================================================================
-- 6. Hook vào luồng duyệt báo cáo — thêm lệnh trả thưởng nhiệm vụ
-- approve_tester_report / auto_accept_expired_reports được tạo lại nguyên văn
-- theo migration 037, bổ sung 1 dòng perform _settle_mission_reward.
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
    -- p_bonus giữ lại trong chữ ký để tương thích client cũ nhưng KHÔNG còn
    -- hiệu lực: trước đây p_bonus không giới hạn và không trừ Credit ai cả.
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

    -- Quá 8h: không cho duyệt tay, để auto_accept xử lý (không thưởng +2).
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

    -- Tip thật (nếu chủ app có đặt): trừ Credit chủ app, cộng cho tester.
    -- Giữ nguyên hành vi cũ: tip được trả trước, sau đó mới cộng thưởng duyệt.
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

    -- Chỉ chủ app thật mới nhận +2 khi bấm duyệt.
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

    -- Nhiệm vụ: nếu tester đã nhận nhiệm vụ của app này, trả thưởng nhiệm vụ.
    perform public._settle_mission_reward(v_report.campaign_id, v_report.user_id);

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

        -- Nhiệm vụ: nếu tester đã nhận nhiệm vụ của app này, trả thưởng nhiệm vụ.
        perform public._settle_mission_reward(v_report.campaign_id, v_report.user_id);

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
            jsonb_build_object('campaign_id', v_report.campaign_id, 'report_id', v_report.id, 'status', 'approved', 'auto_accepted', true, 'tip_awarded', v_tip)
        );

        v_count := v_count + 1;
    end loop;

    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$$;

revoke execute on function public.auto_accept_expired_reports() from public, anon;
grant execute on function public.auto_accept_expired_reports() to authenticated, service_role;

notify pgrst, 'reload schema';
