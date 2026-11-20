-- ==============================================================================
-- 12 Tester - Supabase migration 052: Campaign Push Priority (Tính năng "Đẩy app")
-- ==============================================================================
-- Kế hoạch: F:\AI\12Tester\KE_HOACH_DAY_APP_2026-10-29.md
--
-- Nghiệp vụ:
-- 1. Thêm metadata đẩy app lên bảng campaigns:
--    - push_business_date: ngày Việt Nam (Asia/Ho_Chi_Minh) ghi nhận lượt đẩy gần nhất.
--    - push_expires_at: 00:00 ngày kế tiếp (Asia/Ho_Chi_Minh).
--    - push_order: số thứ tự đẩy cấp từ sequence (lượt sau > lượt trước).
-- 2. Bảng campaign_push_receipts:
--    - Lưu receipt giao dịch: user_id, campaign_id, client_request_id, cost_credits (100),
--      balance_after, business_date, pushed_at, expires_at, push_order, credit_log_id.
--    - Unique (user_id, client_request_id) đảm bảo idempotency chống trừ phí trùng.
-- 3. Trigger trg_protect_campaign_push_metadata:
--    - Chặn INSERT/UPDATE trực tiếp vào các cột push_* từ client; chỉ RPC push_campaign
--      được cấp quyền ghi.
-- 4. RPC push_campaign(p_campaign_id uuid, p_client_request_id uuid):
--    - Trừ đúng 100 Credit, ghi nhận lượt đẩy trong cùng 1 transaction nguyên tử.
--    - Khóa profile và campaign; re-check idempotency sau khi có khóa.
--    - Dùng pg_advisory_xact_lock cấp push_order từ sequence.
--    - Trả kết quả: success, receipt_id, client_request_id, campaign_id, charged_credits,
--      balance_after, business_date, expires_at, push_order, replayed.
-- 5. Cập nhật public._campaign_json(c public.campaigns) để trả đủ 3 cột push_*.
-- ==============================================================================

-- 1. Bổ sung cột metadata đẩy app trên bảng campaigns
alter table public.campaigns
    add column if not exists push_business_date date,
    add column if not exists push_expires_at timestamptz,
    add column if not exists push_order bigint;

-- Ràng buộc tính nhất quán của metadata: cùng null hoặc cùng có giá trị hợp lệ
alter table public.campaigns
    drop constraint if exists chk_campaign_push_metadata_consistency;

alter table public.campaigns
    add constraint chk_campaign_push_metadata_consistency
    check (
        (push_business_date is null and push_expires_at is null and push_order is null)
        or
        (push_business_date is not null and push_expires_at is not null and push_order is not null and push_order > 0)
    );

create index if not exists idx_campaigns_push_order on public.campaigns(push_business_date, push_order desc) where (push_order is not null);

-- 2. Sequence cấp thứ tự đẩy app toàn hệ thống
create sequence if not exists public.campaign_push_order_seq as bigint start with 1 increment by 1;

-- 3. Bảng biên lai giao dịch đẩy app (campaign_push_receipts)
create table if not exists public.campaign_push_receipts (
    id                uuid primary key default gen_random_uuid(),
    user_id           uuid not null references public.profiles(id) on delete restrict,
    campaign_id       uuid references public.campaigns(id) on delete set null,
    client_request_id uuid not null,
    cost_credits      int not null default 100 check (cost_credits = 100),
    balance_after     int not null check (balance_after >= 0),
    business_date     date not null,
    pushed_at         timestamptz not null default now(),
    expires_at        timestamptz not null,
    push_order        bigint not null,
    credit_log_id     uuid references public.credit_logs(id) on delete set null,
    constraint uq_campaign_push_receipts_user_request unique (user_id, client_request_id)
);

create index if not exists idx_campaign_push_receipts_user on public.campaign_push_receipts(user_id);
create index if not exists idx_campaign_push_receipts_campaign on public.campaign_push_receipts(campaign_id);
create index if not exists idx_campaign_push_receipts_date_order on public.campaign_push_receipts(business_date, push_order desc);

comment on table public.campaign_push_receipts is 'Biên lai giao dịch mua lượt Đẩy app (100 Credit/lượt, hiệu lực đến 00:00 hôm sau VN)';

-- Row Level Security cho campaign_push_receipts
alter table public.campaign_push_receipts enable row level security;

drop policy if exists "campaign_push_receipts_select_own" on public.campaign_push_receipts;
create policy "campaign_push_receipts_select_own"
    on public.campaign_push_receipts for select to authenticated
    using (user_id = auth.uid());

-- Thu hồi quyền thao tác trực tiếp trên receipts
revoke insert, update, delete on public.campaign_push_receipts from anon, authenticated;

-- 4. Trigger bảo vệ metadata đẩy app khỏi thao tác ghi trực tiếp từ client
create or replace function public.trg_fn_protect_campaign_push_metadata()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if tg_op = 'INSERT' then
        if new.push_business_date is not null
           or new.push_expires_at is not null
           or new.push_order is not null then
            if current_setting('app.allow_push_metadata_write', true) is distinct from 'true' then
                raise exception 'PUSH_METADATA_READ_ONLY: Khong duoc khoi tao metadata day app khi tao moi campaign';
            end if;
        end if;
    elsif tg_op = 'UPDATE' then
        if new.push_business_date is distinct from old.push_business_date
           or new.push_expires_at is distinct from old.push_expires_at
           or new.push_order is distinct from old.push_order then
            if current_setting('app.allow_push_metadata_write', true) is distinct from 'true' then
                raise exception 'PUSH_METADATA_READ_ONLY: Metadata day app chi duoc cap nhat qua RPC push_campaign';
            end if;
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_protect_campaign_push_metadata on public.campaigns;
create trigger trg_protect_campaign_push_metadata
    before insert or update on public.campaigns
    for each row execute function public.trg_fn_protect_campaign_push_metadata();

-- 5. Cập nhật public._campaign_json(c public.campaigns)
create or replace function public._campaign_json(c public.campaigns)
returns jsonb
language sql
stable
as $$
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
        'push_order', c.push_order
    );
$$;

revoke execute on function public._campaign_json(public.campaigns) from public;

-- 6. RPC push_campaign: Mua lượt đẩy ứng dụng lên đầu danh sách kiểm thử
create or replace function public.push_campaign(
    p_campaign_id uuid,
    p_client_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
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
begin
    -- 1. Yêu cầu phiên đăng nhập
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    if p_client_request_id is null then
        raise exception 'INVALID_REQUEST_ID';
    end if;

    if p_campaign_id is null then
        raise exception 'INVALID_CAMPAIGN_ID';
    end if;

    -- 2. Kiểm tra receipt trước khi khóa (tránh chờ khóa nếu đã hoàn thành trước đó)
    select * into v_existing_receipt
    from public.campaign_push_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.campaign_id is distinct from p_campaign_id then
            raise exception 'REQUEST_ID_CONFLICT';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', v_existing_receipt.campaign_id,
            'charged_credits', v_existing_receipt.cost_credits,
            'balance_after', v_existing_receipt.balance_after,
            'business_date', to_char(v_existing_receipt.business_date, 'YYYY-MM-DD'),
            'expires_at', to_char(v_existing_receipt.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'push_order', v_existing_receipt.push_order,
            'replayed', true
        );
    end if;

    -- 3. Khóa campaign và profile theo thứ tự nhất quán
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

    -- Re-check receipt sau khi đã có khóa (chống race condition 2 request trùng nhau)
    select * into v_existing_receipt
    from public.campaign_push_receipts
    where user_id = v_uid and client_request_id = p_client_request_id;

    if v_existing_receipt.id is not null then
        if v_existing_receipt.campaign_id is distinct from p_campaign_id then
            raise exception 'REQUEST_ID_CONFLICT';
        end if;
        return jsonb_build_object(
            'success', true,
            'receipt_id', v_existing_receipt.id,
            'client_request_id', v_existing_receipt.client_request_id,
            'campaign_id', v_existing_receipt.campaign_id,
            'charged_credits', v_existing_receipt.cost_credits,
            'balance_after', v_existing_receipt.balance_after,
            'business_date', to_char(v_existing_receipt.business_date, 'YYYY-MM-DD'),
            'expires_at', to_char(v_existing_receipt.expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'push_order', v_existing_receipt.push_order,
            'replayed', true
        );
    end if;

    -- 4. Kiểm tra chủ thật và trạng thái tài khoản
    if v_camp.owner_id is distinct from v_uid then
        raise exception 'NOT_OWNER';
    end if;

    if coalesce(v_profile.is_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- 5. Kiểm tra số dư (dưới 100 từ chối hoàn toàn; đúng 100 cho phép)
    if coalesce(v_profile.credits, 0) < 100 then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- 6. Khóa transaction chung cho việc cấp thứ tự đẩy (serialize push sequence allocation)
    perform pg_advisory_xact_lock(hashtext('campaign_push_order_lock'));

    v_now := clock_timestamp();
    v_business_date := (v_now at time zone 'Asia/Ho_Chi_Minh')::date;
    v_expires_at := (date_trunc('day', v_now at time zone 'Asia/Ho_Chi_Minh') + interval '1 day') at time zone 'Asia/Ho_Chi_Minh';

    -- Kiểm tra lại điều kiện thời gian sau khi có khóa và đồng hồ mới
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

    v_push_order := nextval('public.campaign_push_order_seq');

    -- 7. Trừ đúng 100 Credit, ghi log, cập nhật metadata campaign, ghi receipt
    update public.profiles
    set credits = credits - 100
    where id = v_uid
    returning credits into v_balance_after;

    v_note := format('Đẩy ứng dụng "%s" lên đầu danh sách (hạn đến hết %s)',
                     coalesce(nullif(v_camp.app_name, ''), 'của bạn'),
                     to_char(v_business_date, 'DD/MM/YYYY'));

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (v_uid, -100, 'campaign_push', v_note, v_balance_after)
    returning id into v_credit_log_id;

    perform set_config('app.allow_push_metadata_write', 'true', true);

    update public.campaigns
    set push_business_date = v_business_date,
        push_expires_at = v_expires_at,
        push_order = v_push_order
    where id = p_campaign_id;

    perform set_config('app.allow_push_metadata_write', 'false', true);

    insert into public.campaign_push_receipts (
        user_id,
        campaign_id,
        client_request_id,
        cost_credits,
        balance_after,
        business_date,
        pushed_at,
        expires_at,
        push_order,
        credit_log_id
    )
    values (
        v_uid,
        p_campaign_id,
        p_client_request_id,
        100,
        v_balance_after,
        v_business_date,
        v_now,
        v_expires_at,
        v_push_order,
        v_credit_log_id
    )
    returning id into v_receipt_id;

    -- 8. Trả kết quả
    return jsonb_build_object(
        'success', true,
        'receipt_id', v_receipt_id,
        'client_request_id', p_client_request_id,
        'campaign_id', p_campaign_id,
        'charged_credits', 100,
        'balance_after', v_balance_after,
        'business_date', to_char(v_business_date, 'YYYY-MM-DD'),
        'expires_at', to_char(v_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'push_order', v_push_order,
        'replayed', false
    );
end;
$$;

revoke execute on function public.push_campaign(uuid, uuid) from public;
revoke execute on function public.push_campaign(uuid, uuid) from anon;
grant execute on function public.push_campaign(uuid, uuid) to authenticated, service_role;
