-- ==============================================================================
-- 12 Tester - Supabase migration 050: App Version Update Reward (Thưởng cập nhật phiên bản app)
-- ==============================================================================
-- Kế hoạch: F:\AI\12Tester\KE_HOACH_THUONG_CAP_NHAT_PHIEN_BAN_2026-10-29.md
-- Mã bàn giao: APP-UPDATE-20261029-01a18562
--
-- Nghiệp vụ cốt lõi:
-- 1. Giữ trước 120 Credit mỗi phiên bản từ ví chủ app khi công bố (app_update_reserve, -120).
-- 2. Trả thưởng 6 Credit/người cho tối đa 20 người cập nhật bản mới đáp ứng target_version_code.
-- 3. Mỗi tài khoản tester chỉ nhận thưởng 1 lần trong 1 đợt phiên bản.
-- 4. Khi chi trả 6 Credit cho tester (app_update_reward, +6), trích từ quỹ 120 đang giữ;
--    TUYỆT ĐỐI KHÔNG trừ thêm 6 Credit từ ví chủ app.
-- 5. Đủ 20 người nhận thưởng -> chuyển trạng thái exhausted, ẩn khối cập nhật ở phía tester.
-- 6. Chủ app công bố phiên bản tiếp theo -> hoàn phần Credit chưa dùng của đợt cũ (nếu có),
--    và giữ 120 Credit cho đợt mới trong cùng 1 transaction nguyên tử.
-- 7. Cho phép chủ app kết thúc sớm đợt đang mở và hoàn lại toàn bộ Credit chưa dùng.
-- 8. Tự động đóng đợt và hoàn Credit chưa dùng khi campaign hoàn thành (completed), lên production,
--    hoặc trước khi campaign bị xóa (BEFORE DELETE trigger).
-- 9. Bất biến kế toán duy trì cho mọi đợt:
--    reserved_credits = remaining_credits + (paid_count * 6) + refunded_credits = 120.
-- ==============================================================================

-- 1. Bảng app_update_releases: Lưu trữ thông tin từng đợt cập nhật phiên bản
create table if not exists public.app_update_releases (
    id                  uuid primary key default gen_random_uuid(),
    campaign_id         uuid references public.campaigns(id) on delete set null,
    owner_id            uuid not null references public.profiles(id) on delete restrict,
    package_name        text not null,
    app_name            text not null default '',
    version_name        text not null,
    target_version_code bigint not null,
    release_notes       text not null default '',
    status              text not null default 'active' check (status in ('active', 'exhausted', 'superseded', 'closed')),
    reward_per_claim    int not null default 6 check (reward_per_claim = 6),
    max_claims          int not null default 20 check (max_claims = 20),
    reserved_credits    int not null default 120 check (reserved_credits = 120),
    paid_count          int not null default 0 check (paid_count >= 0 and paid_count <= 20),
    remaining_credits   int not null default 120 check (remaining_credits >= 0),
    refunded_credits    int not null default 0 check (refunded_credits >= 0),
    published_at        timestamptz not null default now(),
    closed_at           timestamptz,
    constraint chk_release_version_code check (target_version_code > 0),
    constraint chk_release_accounting check (reserved_credits = remaining_credits + (paid_count * reward_per_claim) + refunded_credits)
);

create index if not exists idx_app_update_releases_campaign on public.app_update_releases(campaign_id);
create index if not exists idx_app_update_releases_owner on public.app_update_releases(owner_id);
create index if not exists idx_app_update_releases_pkg on public.app_update_releases(package_name);
create unique index if not exists idx_app_update_releases_active on public.app_update_releases(owner_id, package_name) where (status = 'active');
create unique index if not exists idx_app_update_releases_code on public.app_update_releases(owner_id, package_name, target_version_code);

comment on table public.app_update_releases is 'Các đợt công bố phiên bản cập nhật kèm quỹ thưởng 120 Credit (20 suất x 6 Credit)';

-- 2. Bảng app_update_controls: Khóa tuần tự hóa và watermark mã phiên bản cao nhất
create table if not exists public.app_update_controls (
    owner_id             uuid not null references public.profiles(id) on delete cascade,
    package_name         text not null,
    highest_version_code bigint not null default 0,
    active_release_id    uuid references public.app_update_releases(id) on delete set null,
    updated_at           timestamptz not null default now(),
    primary key (owner_id, package_name)
);

-- 3. Bảng app_update_claims: Biên lai nhận thưởng của từng tester
create table if not exists public.app_update_claims (
    id                    uuid primary key default gen_random_uuid(),
    release_id            uuid not null references public.app_update_releases(id) on delete cascade,
    tester_id             uuid not null references public.profiles(id) on delete cascade,
    ordinal               int not null check (ordinal >= 1 and ordinal <= 20),
    observed_version_code bigint not null check (observed_version_code > 0),
    reward_credits        int not null default 6 check (reward_credits = 6),
    confirmed_at          timestamptz not null default now(),
    credit_log_id         uuid references public.credit_logs(id) on delete set null,
    constraint uq_release_tester unique (release_id, tester_id),
    constraint uq_release_ordinal unique (release_id, ordinal)
);

create index if not exists idx_app_update_claims_tester on public.app_update_claims(tester_id);

-- 4. Bảng app_update_operations: Lưu trữ idempotency cho các thao tác công bố
create table if not exists public.app_update_operations (
    operation_id         text primary key,
    owner_id             uuid not null references public.profiles(id) on delete cascade,
    release_id           uuid references public.app_update_releases(id) on delete set null,
    status               text not null default 'completed',
    created_at           timestamptz not null default now()
);

-- 5. Bảng app_update_financial_events: Nhật ký dòng tiền độc lập để đối soát
create table if not exists public.app_update_financial_events (
    id                   uuid primary key default gen_random_uuid(),
    release_id           uuid not null references public.app_update_releases(id) on delete cascade,
    event_type           text not null check (event_type in ('reserve', 'reward', 'refund')),
    amount               int not null,
    owner_id             uuid not null,
    tester_id            uuid,
    credit_log_id        uuid references public.credit_logs(id) on delete set null,
    operation_id         text,
    created_at           timestamptz not null default now()
);

-- Bật RLS và phân quyền
alter table public.app_update_releases enable row level security;
alter table public.app_update_controls enable row level security;
alter table public.app_update_claims enable row level security;
alter table public.app_update_operations enable row level security;
alter table public.app_update_financial_events enable row level security;

-- Policies
drop policy if exists "app_update_releases_select_policy" on public.app_update_releases;
create policy "app_update_releases_select_policy" on public.app_update_releases
    for select to authenticated
    using (
        owner_id = auth.uid()
        or (
            campaign_id is not null and exists (
                select 1 from public.campaign_members cm
                where cm.campaign_id = app_update_releases.campaign_id
                  and cm.user_id = auth.uid()
            )
        )
        or exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and lower(p.role) in ('admin', 'moderator')
        )
    );

drop policy if exists "app_update_controls_select_policy" on public.app_update_controls;
create policy "app_update_controls_select_policy" on public.app_update_controls
    for select to authenticated
    using (
        owner_id = auth.uid()
        or exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and lower(p.role) in ('admin', 'moderator')
        )
    );

drop policy if exists "app_update_claims_select_policy" on public.app_update_claims;
create policy "app_update_claims_select_policy" on public.app_update_claims
    for select to authenticated
    using (
        tester_id = auth.uid()
        or exists (
            select 1 from public.app_update_releases r
            where r.id = app_update_claims.release_id
              and r.owner_id = auth.uid()
        )
        or exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and lower(p.role) in ('admin', 'moderator')
        )
    );

drop policy if exists "app_update_operations_select_policy" on public.app_update_operations;
create policy "app_update_operations_select_policy" on public.app_update_operations
    for select to authenticated
    using (
        owner_id = auth.uid()
        or exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and lower(p.role) in ('admin', 'moderator')
        )
    );

drop policy if exists "app_update_financial_events_select_policy" on public.app_update_financial_events;
create policy "app_update_financial_events_select_policy" on public.app_update_financial_events
    for select to authenticated
    using (
        owner_id = auth.uid()
        or tester_id = auth.uid()
        or exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and lower(p.role) in ('admin', 'moderator')
        )
    );

-- Thu hồi quyền ghi trực tiếp từ client trên các bảng này
revoke insert, update, delete on table public.app_update_releases from public, anon, authenticated;
revoke insert, update, delete on table public.app_update_controls from public, anon, authenticated;
revoke insert, update, delete on table public.app_update_claims from public, anon, authenticated;
revoke insert, update, delete on table public.app_update_operations from public, anon, authenticated;
revoke insert, update, delete on table public.app_update_financial_events from public, anon, authenticated;

grant select on table public.app_update_releases to authenticated, service_role;
grant select on table public.app_update_controls to authenticated, service_role;
grant select on table public.app_update_claims to authenticated, service_role;
grant select on table public.app_update_operations to authenticated, service_role;
grant select on table public.app_update_financial_events to authenticated, service_role;

-- 6. Helper nội bộ: Quyết toán và hoàn Credit đợt cập nhật khi đóng hoặc xóa campaign
create or replace function public._settle_campaign_app_update(p_campaign_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_rel record;
    v_remaining int;
    v_owner_balance int;
    v_credit_log_id uuid;
begin
    for v_rel in
        select * from public.app_update_releases
        where campaign_id = p_campaign_id and status = 'active'
        for update
    loop
        v_remaining := v_rel.remaining_credits;
        update public.app_update_releases
        set status = 'closed',
            closed_at = now(),
            remaining_credits = 0,
            refunded_credits = refunded_credits + v_remaining
        where id = v_rel.id;

        update public.app_update_controls
        set active_release_id = null,
            updated_at = now()
        where owner_id = v_rel.owner_id
          and package_name = v_rel.package_name
          and active_release_id = v_rel.id;

        if v_remaining > 0 then
            -- Kiểm tra xem profile chủ app còn tồn tại không (phòng trường hợp account deletion cascade)
            if exists (select 1 from public.profiles where id = v_rel.owner_id) then
                update public.profiles
                set credits = credits + v_remaining
                where id = v_rel.owner_id
                returning credits into v_owner_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_rel.owner_id,
                    v_remaining,
                    'app_update_refund',
                    format('Hoàn %s Credit chưa dùng từ phiên bản %s (%s)', v_remaining, v_rel.version_name, v_rel.app_name),
                    v_owner_balance
                )
                returning id into v_credit_log_id;

                insert into public.app_update_financial_events (
                    release_id, event_type, amount, owner_id, credit_log_id
                ) values (
                    v_rel.id, 'refund', v_remaining, v_rel.owner_id, v_credit_log_id
                );
            else
                insert into public.app_update_financial_events (
                    release_id, event_type, amount, owner_id, credit_log_id
                ) values (
                    v_rel.id, 'refund', v_remaining, v_rel.owner_id, null
                );
            end if;
        end if;
    end loop;
end;
$$;

revoke execute on function public._settle_campaign_app_update(uuid) from public, anon;
grant execute on function public._settle_campaign_app_update(uuid) to authenticated, service_role;

-- 7. Triggers trên public.campaigns để bảo vệ quỹ khi hoàn thành/lên production hoặc xóa campaign
create or replace function public.trg_fn_campaigns_app_update_settle()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if TG_OP = 'DELETE' then
        perform public._settle_campaign_app_update(OLD.id);
        return OLD;
    elsif TG_OP = 'UPDATE' then
        if OLD.status = 'open' and NEW.status in ('completed', 'production') then
            perform public._settle_campaign_app_update(NEW.id);
        end if;
        return NEW;
    end if;
    return null;
end;
$$;

drop trigger if exists trg_campaigns_app_update_settle_delete on public.campaigns;
create trigger trg_campaigns_app_update_settle_delete
    before delete on public.campaigns
    for each row
    execute function public.trg_fn_campaigns_app_update_settle();

drop trigger if exists trg_campaigns_app_update_settle_update on public.campaigns;
create trigger trg_campaigns_app_update_settle_update
    after update of status on public.campaigns
    for each row
    execute function public.trg_fn_campaigns_app_update_settle();

-- 8. RPC publish_app_update: Công bố phiên bản mới và giữ quỹ 120 Credit
create or replace function public.publish_app_update(
    p_campaign_id                  uuid,
    p_version_name                 text,
    p_target_version_code          bigint,
    p_release_notes                text,
    p_client_operation_id          text default null,
    p_expected_current_release_id  uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id           uuid := auth.uid();
    v_camp                public.campaigns%rowtype;
    v_version_name_clean  text;
    v_release_notes_clean text;
    v_ctrl                record;
    v_active_rel          record;
    v_old_remaining       int := 0;
    v_owner_credits       int;
    v_effective_credits   int;
    v_new_release_id      uuid;
    v_new_release         public.app_update_releases%rowtype;
    v_refund_log_id       uuid;
    v_reserve_log_id      uuid;
    v_existing_op         record;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Kiểm tra idempotency theo client_operation_id nếu có
    if p_client_operation_id is not null and trim(p_client_operation_id) <> '' then
        select * into v_existing_op
        from public.app_update_operations
        where operation_id = trim(p_client_operation_id) and owner_id = v_caller_id;

        if v_existing_op.operation_id is not null then
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

    -- Khóa và kiểm tra campaign
    select * into v_camp from public.campaigns where id = p_campaign_id for update;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    -- Chỉ chủ app thật mới được giữ quỹ và công bố (không dùng quyền admin xem giúp)
    if v_camp.owner_id <> v_caller_id then
        raise exception 'NOT_ACTUAL_OWNER';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    if v_camp.package_name is null or trim(v_camp.package_name) = '' then
        raise exception 'INVALID_PACKAGE_NAME';
    end if;

    -- Validate version_name
    v_version_name_clean := trim(coalesce(p_version_name, ''));
    if v_version_name_clean = '' or length(v_version_name_clean) > 100 then
        raise exception 'INVALID_VERSION_NAME';
    end if;

    -- Validate target_version_code
    if p_target_version_code is null or p_target_version_code <= 0 then
        raise exception 'INVALID_VERSION_CODE';
    end if;

    -- Validate release_notes
    v_release_notes_clean := trim(coalesce(p_release_notes, ''));
    if v_release_notes_clean = '' or length(v_release_notes_clean) > 4000 then
        raise exception 'INVALID_RELEASE_NOTES';
    end if;

    -- Khóa hàng điều khiển app_update_controls (ngăn 2 request công bố đồng thời)
    insert into public.app_update_controls (owner_id, package_name, highest_version_code)
    values (v_caller_id, v_camp.package_name, 0)
    on conflict (owner_id, package_name) do nothing;

    select * into v_ctrl
    from public.app_update_controls
    where owner_id = v_caller_id and package_name = v_camp.package_name
    for update;

    -- Kiểm tra expected_current_release_id để phát hiện form cũ
    if p_expected_current_release_id is not null and v_ctrl.active_release_id is not null then
        if p_expected_current_release_id <> v_ctrl.active_release_id then
            raise exception 'CONCURRENT_UPDATE_CONFLICT';
        end if;
    end if;

    -- Kiểm tra high-watermark versionCode: phải lớn hơn bản công bố trước của cùng app
    if p_target_version_code <= v_ctrl.highest_version_code then
        raise exception 'VERSION_CODE_NOT_GREATER';
    end if;

    -- Kiểm tra xem có đợt active nào đang chạy không
    select * into v_active_rel
    from public.app_update_releases
    where owner_id = v_caller_id
      and package_name = v_camp.package_name
      and status = 'active'
    for update;

    if v_active_rel.id is not null then
        v_old_remaining := v_active_rel.remaining_credits;
    end if;

    -- Khóa ví chủ app
    select credits into v_owner_credits from public.profiles where id = v_caller_id for update;

    -- Tính toán ngân sách: cần 120 cho bản mới, đợt cũ hoàn lại v_old_remaining
    v_effective_credits := v_owner_credits + v_old_remaining;
    if v_effective_credits < 120 then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- Xử lý đợt cũ nếu có: đóng đợt, hoàn phần chưa dùng
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

    -- Trừ 120 Credit cho đợt mới
    v_owner_credits := v_owner_credits - 120;
    update public.profiles set credits = v_owner_credits where id = v_caller_id;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_caller_id,
        -120,
        'app_update_reserve',
        format('Giữ trước 120 Credit cho phiên bản mới %s (%s)', v_version_name_clean, v_camp.app_name),
        v_owner_credits
    )
    returning id into v_reserve_log_id;

    -- Tạo release mới
    insert into public.app_update_releases (
        campaign_id,
        owner_id,
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
        published_at
    ) values (
        p_campaign_id,
        v_caller_id,
        v_camp.package_name,
        coalesce(v_camp.app_name, ''),
        v_version_name_clean,
        p_target_version_code,
        v_release_notes_clean,
        'active',
        6,
        20,
        120,
        0,
        120,
        0,
        now()
    )
    returning * into v_new_release;

    v_new_release_id := v_new_release.id;

    -- Ghi nhận financial event cho đợt giữ tiền
    insert into public.app_update_financial_events (
        release_id, event_type, amount, owner_id, credit_log_id, operation_id
    ) values (
        v_new_release_id, 'reserve', 120, v_caller_id, v_reserve_log_id, p_client_operation_id
    );

    -- Cập nhật app_update_controls
    update public.app_update_controls
    set highest_version_code = p_target_version_code,
        active_release_id = v_new_release_id,
        updated_at = now()
    where owner_id = v_caller_id and package_name = v_camp.package_name;

    -- Lưu vết idempotency
    if p_client_operation_id is not null and trim(p_client_operation_id) <> '' then
        insert into public.app_update_operations (operation_id, owner_id, release_id, status)
        values (trim(p_client_operation_id), v_caller_id, v_new_release_id, 'completed')
        on conflict (operation_id) do nothing;
    end if;

    return jsonb_build_object(
        'success', true,
        'release', row_to_json(v_new_release),
        'owner_credits', v_owner_credits,
        'refund_amount', v_old_remaining
    );
end;
$$;

revoke execute on function public.publish_app_update(uuid, text, bigint, text, text, uuid) from public, anon;
grant execute on function public.publish_app_update(uuid, text, bigint, text, text, uuid) to authenticated, service_role;

-- 9. RPC claim_app_update_reward: Tester nhận thưởng cập nhật 6 Credit
create or replace function public.claim_app_update_reward(
    p_release_id            uuid,
    p_observed_version_code bigint
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id          uuid := auth.uid();
    v_existing_claim     record;
    v_rel                record;
    v_camp               record;
    v_member             record;
    v_tester_credits     int;
    v_is_banned          boolean;
    v_next_ordinal       int;
    v_new_paid_count     int;
    v_new_remaining      int;
    v_new_status         text;
    v_credit_log_id      uuid;
    v_claim_id           uuid;
    v_confirmed_at       timestamptz;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_observed_version_code is null or p_observed_version_code <= 0 then
        raise exception 'INVALID_OBSERVED_VERSION_CODE';
    end if;

    -- Idempotency: Kiểm tra xem tester đã nhận thưởng đợt này chưa
    select * into v_existing_claim
    from public.app_update_claims
    where release_id = p_release_id and tester_id = v_caller_id;

    if v_existing_claim.id is not null then
        select credits into v_tester_credits from public.profiles where id = v_caller_id;
        select paid_count, max_claims, status into v_new_paid_count, v_next_ordinal, v_new_status
        from public.app_update_releases where id = p_release_id;

        return jsonb_build_object(
            'success', true,
            'newly_awarded', false,
            'reward', v_existing_claim.reward_credits,
            'tester_balance', v_tester_credits,
            'paid_count', v_new_paid_count,
            'max_claims', 20,
            'release_status', v_new_status,
            'receipt', jsonb_build_object(
                'claim_id', v_existing_claim.id,
                'release_id', v_existing_claim.release_id,
                'ordinal', v_existing_claim.ordinal,
                'reward_credits', v_existing_claim.reward_credits,
                'confirmed_at', to_char(v_existing_claim.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            )
        );
    end if;

    -- Khóa và kiểm tra release
    select * into v_rel
    from public.app_update_releases
    where id = p_release_id
    for update;

    if v_rel.id is null then
        raise exception 'RELEASE_NOT_FOUND';
    end if;

    -- Kiểm tra chủ app không được tự claim thưởng của chính app mình
    if v_rel.owner_id = v_caller_id then
        raise exception 'OWNER_CANNOT_CLAIM';
    end if;

    -- Kiểm tra trạng thái đợt
    if v_rel.status = 'superseded' then
        raise exception 'RELEASE_REPLACED';
    end if;

    if v_rel.status in ('exhausted', 'closed') or v_rel.paid_count >= v_rel.max_claims or v_rel.remaining_credits < v_rel.reward_per_claim then
        -- Cập nhật trạng thái exhausted nếu chưa cập nhật
        if v_rel.status = 'active' then
            update public.app_update_releases set status = 'exhausted', closed_at = coalesce(closed_at, now()) where id = v_rel.id;
        end if;
        raise exception 'RELEASE_EXHAUSTED';
    end if;

    -- Kiểm tra campaign
    if v_rel.campaign_id is not null then
        select status, owner_id into v_camp
        from public.campaigns
        where id = v_rel.campaign_id;

        if v_camp.owner_id = v_caller_id then
            raise exception 'OWNER_CANNOT_CLAIM';
        end if;

        if v_camp.status = 'paused' then
            raise exception 'CAMPAIGN_PAUSED';
        end if;

        if v_camp.status <> 'open' then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;

        -- Kiểm tra membership và điều kiện tham gia trước/tại thời điểm công bố
        select joined_at into v_member
        from public.campaign_members
        where campaign_id = v_rel.campaign_id and user_id = v_caller_id;

        if v_member.joined_at is null then
            raise exception 'NOT_CAMPAIGN_MEMBER';
        end if;

        if v_member.joined_at > v_rel.published_at then
            raise exception 'MEMBER_JOINED_AFTER_RELEASE';
        end if;
    end if;

    -- Kiểm tra versionCode quan sát được từ client: phải >= target_version_code
    if p_observed_version_code < v_rel.target_version_code then
        raise exception 'VERSION_NOT_SATISFIED';
    end if;

    -- Khóa và kiểm tra profile tester
    select credits, is_locked into v_tester_credits, v_is_banned
    from public.profiles
    where id = v_caller_id
    for update;

    if v_is_banned then
        raise exception 'USER_LOCKED';
    end if;

    -- Gán số thứ tự suất thưởng (1..20)
    v_next_ordinal := v_rel.paid_count + 1;
    if v_next_ordinal > v_rel.max_claims or v_rel.remaining_credits < v_rel.reward_per_claim then
        update public.app_update_releases
        set status = 'exhausted', closed_at = coalesce(closed_at, now())
        where id = v_rel.id;
        raise exception 'RELEASE_EXHAUSTED';
    end if;

    -- Cập nhật release: giảm remaining_credits 6, tăng paid_count 1
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

    -- Cộng 6 Credit cho tester (TUYỆT ĐỐI KHÔNG trừ từ ví chủ app)
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

    -- Ghi nhận biên lai claim
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

    -- Ghi nhận financial event
    insert into public.app_update_financial_events (
        release_id, event_type, amount, owner_id, tester_id, credit_log_id
    ) values (
        v_rel.id, 'reward', v_rel.reward_per_claim, v_rel.owner_id, v_caller_id, v_credit_log_id
    );

    return jsonb_build_object(
        'success', true,
        'newly_awarded', true,
        'reward', v_rel.reward_per_claim,
        'tester_balance', v_tester_credits,
        'paid_count', v_new_paid_count,
        'max_claims', v_rel.max_claims,
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
$$;

revoke execute on function public.claim_app_update_reward(uuid, bigint) from public, anon;
grant execute on function public.claim_app_update_reward(uuid, bigint) to authenticated, service_role;

-- 10. RPC close_app_update_release: Chủ app kết thúc sớm đợt cập nhật và hoàn tiền chưa dùng
create or replace function public.close_app_update_release(
    p_release_id uuid,
    p_reason     text default 'manual_close'
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id      uuid := auth.uid();
    v_rel            record;
    v_remaining      int;
    v_owner_balance  int;
    v_credit_log_id  uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_rel
    from public.app_update_releases
    where id = p_release_id
    for update;

    if v_rel.id is null then
        raise exception 'RELEASE_NOT_FOUND';
    end if;

    if v_rel.owner_id <> v_caller_id then
        raise exception 'NOT_ACTUAL_OWNER';
    end if;

    if v_rel.status <> 'active' then
        return jsonb_build_object(
            'success', false,
            'error_code', 'RELEASE_NOT_ACTIVE',
            'message', 'Đợt cập nhật này không còn hoạt động.',
            'release_status', v_rel.status
        );
    end if;

    v_remaining := v_rel.remaining_credits;

    update public.app_update_releases
    set status = 'closed',
        closed_at = now(),
        remaining_credits = 0,
        refunded_credits = refunded_credits + v_remaining
    where id = v_rel.id;

    update public.app_update_controls
    set active_release_id = null,
        updated_at = now()
    where owner_id = v_rel.owner_id
      and package_name = v_rel.package_name
      and active_release_id = v_rel.id;

    if v_remaining > 0 then
        update public.profiles
        set credits = credits + v_remaining
        where id = v_rel.owner_id
        returning credits into v_owner_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_rel.owner_id,
            v_remaining,
            'app_update_refund',
            format('Hoàn %s Credit chưa dùng từ phiên bản %s (%s)', v_remaining, v_rel.version_name, v_rel.app_name),
            v_owner_balance
        )
        returning id into v_credit_log_id;

        insert into public.app_update_financial_events (
            release_id, event_type, amount, owner_id, credit_log_id
        ) values (
            v_rel.id, 'refund', v_remaining, v_rel.owner_id, v_credit_log_id
        );
    else
        select credits into v_owner_balance from public.profiles where id = v_rel.owner_id;
    end if;

    return jsonb_build_object(
        'success', true,
        'refund_amount', v_remaining,
        'owner_credits', v_owner_balance,
        'release_status', 'closed'
    );
end;
$$;

revoke execute on function public.close_app_update_release(uuid, text) from public, anon;
grant execute on function public.close_app_update_release(uuid, text) to authenticated, service_role;

-- 11. RPC get_campaign_update_history: Lấy lịch sử các đợt cập nhật cho chủ app
create or replace function public.get_campaign_update_history(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id  uuid := auth.uid();
    v_camp       record;
    v_can_manage boolean := false;
    v_history    jsonb := '[]'::jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select owner_id into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.owner_id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    v_can_manage := (v_camp.owner_id = v_caller_id)
        or exists (
            select 1 from public.profiles p
            where p.id = v_caller_id and lower(p.role) in ('admin', 'moderator')
        );

    if not v_can_manage then
        raise exception 'FORBIDDEN';
    end if;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', r.id,
        'campaign_id', r.campaign_id,
        'owner_id', r.owner_id,
        'package_name', r.package_name,
        'app_name', r.app_name,
        'version_name', r.version_name,
        'target_version_code', r.target_version_code,
        'release_notes', r.release_notes,
        'status', r.status,
        'reward_per_claim', r.reward_per_claim,
        'max_claims', r.max_claims,
        'reserved_credits', r.reserved_credits,
        'paid_count', r.paid_count,
        'remaining_credits', r.remaining_credits,
        'refunded_credits', r.refunded_credits,
        'spent_credits', r.paid_count * r.reward_per_claim,
        'published_at', to_char(r.published_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'closed_at', case when r.closed_at is not null then to_char(r.closed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') else null end
    ) order by r.published_at desc), '[]'::jsonb)
    into v_history
    from public.app_update_releases r
    where r.campaign_id = p_campaign_id;

    return v_history;
end;
$$;

revoke execute on function public.get_campaign_update_history(uuid) from public, anon;
grant execute on function public.get_campaign_update_history(uuid) to authenticated, service_role;

-- 12. Override get_campaign_detail: Mở rộng trả về đối tượng app_update cho chủ app và tester
create or replace function public.get_campaign_detail(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
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
    v_avg              float;
    v_my_dates         jsonb := '[]'::jsonb;
    v_my_profile       jsonb;
    v_unread           jsonb := '[]'::jsonb;
    v_weekly           int := 0;
    -- Biến cho tính năng thưởng cập nhật phiên bản
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

    v_can_manage := v_is_actual_owner
        or exists (
            select 1 from public.profiles p
            where p.id = v_uid and lower(p.role) in ('admin', 'moderator')
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
        'warning_type', w.warning_type,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) order by w.created_at desc), '[]'::jsonb)
    into v_unread
    from public.user_warnings w
    where w.user_id = v_uid and w.acknowledged = false;

    select count(*) into v_weekly
    from public.user_warnings w
    where w.user_id = v_uid
      and (w.created_at at time zone 'Asia/Ho_Chi_Minh')::date >= (v_today - 6);

    -- =========================================================================
    -- Xây dựng metadata thưởng cập nhật phiên bản (app_update)
    -- =========================================================================
    select * into v_rel
    from public.app_update_releases
    where campaign_id = p_campaign_id and status in ('active', 'exhausted')
    order by published_at desc
    limit 1;

    if v_rel.id is not null then
        if v_is_actual_owner then
            -- Góc nhìn chủ app: hiển thị toàn bộ tiến độ, ngân sách đang giữ và đã chi
            v_app_update := jsonb_build_object(
                'id', v_rel.id,
                'campaign_id', v_rel.campaign_id,
                'owner_id', v_rel.owner_id,
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
            -- Góc nhìn tester:
            -- Quy tắc: Nếu đã đủ 20 suất (paid_count >= 20) thì ẩn toàn bộ đối với tester
            if v_rel.paid_count < v_rel.max_claims and v_rel.remaining_credits >= v_rel.reward_per_claim then
                select * into v_claim
                from public.app_update_claims
                where release_id = v_rel.id and tester_id = v_uid;

                -- Kiểm tra điều kiện tester
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
        'avg_rating', v_avg,
        'my_checkin_dates', v_my_dates,
        'my_profile', v_my_profile,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly,
        'app_update', v_app_update
    );
end;
$$;

revoke execute on function public.get_campaign_detail(uuid) from public, anon;
grant execute on function public.get_campaign_detail(uuid) to authenticated, service_role;

notify pgrst, 'reload schema';
