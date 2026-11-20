-- ==============================================================================
-- 12 Tester - Supabase migration 056: Push Campaign Role Hardening
-- ==============================================================================
-- Kế hoạch: KE_HOACH_SUA_LOI_DAY_APP_RPC_2026-10-29.md
-- Khắc phục lỗi: R01 / P0001 / PUSH_METADATA_READ_ONLY khi gọi RPC push_campaign
--
-- Nguyên nhân:
-- Trigger trg_fn_protect_campaign_push_metadata ở migration 054 kiểm tra setting
-- 'role' (thường mang giá trị 'authenticated' từ request ban đầu của PostgREST),
-- dẫn đến chặn nhầm cả thao tác hợp lệ bên trong RPC SECURITY DEFINER push_campaign.
--
-- Giải pháp:
-- 1. Sửa trigger để kiểm tra quyền thực thi hiệu lực (current_user) thay vì current_setting('role').
--    Trigger giữ nguyên là SECURITY INVOKER để chạy theo quyền của caller:
--    - Nếu client gọi trực tiếp qua PostgREST (bảng campaigns): current_user là 'authenticated'/'anon' -> CHẶN NGAY.
--    - Nếu client tự đặt GUC 'app.allow_push_metadata_write = true' rồi ghi trực tiếp: current_user vẫn là 'authenticated' -> VẪN BỊ CHẶN NGAY.
--    - Nếu gọi qua RPC push_campaign (SECURITY DEFINER): current_user chuyển thành owner tin cậy ('postgres') và có cờ transaction 'app.allow_push_metadata_write = true' -> THÀNH CÔNG.
-- 2. Đồng bộ quyền EXECUTE trên push_campaign cho authenticated và service_role.
-- ==============================================================================

-- 1. Cập nhật Trigger function bảo vệ metadata đẩy app (SECURITY INVOKER)
create or replace function public.trg_fn_protect_campaign_push_metadata()
returns trigger
language plpgsql
set search_path = public
as $$
begin
    if (tg_op = 'INSERT' and (new.push_business_date is not null or new.push_expires_at is not null or new.push_order is not null))
       or (tg_op = 'UPDATE' and (new.push_business_date is distinct from old.push_business_date
            or new.push_expires_at is distinct from old.push_expires_at
            or new.push_order is distinct from old.push_order)) then
        -- Client trực tiếp mang role authenticated/anon/public tuyệt đối không được ghi đè metadata
        -- Sử dụng current_user (quyền thực thi hiệu lực trong ngữ cảnh invoker) để phân biệt
        -- lời gọi trực tiếp từ client với lời gọi bên trong RPC SECURITY DEFINER (đang chạy với role owner tin cậy).
        if current_user in ('authenticated', 'anon', 'public') or session_user in ('anon') then
            raise exception 'PUSH_METADATA_READ_ONLY: Client khong duoc phep thay doi metadata day app truc tiep';
        end if;

        -- Metadata chỉ được cập nhật khi có cờ transaction-local do RPC push_campaign bật
        if current_setting('app.allow_push_metadata_write', true) is distinct from 'true' then
            raise exception 'PUSH_METADATA_READ_ONLY: Metadata day app chi duoc cap nhat qua RPC push_campaign';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_protect_campaign_push_metadata on public.campaigns;
create trigger trg_protect_campaign_push_metadata
    before insert or update on public.campaigns
    for each row execute function public.trg_fn_protect_campaign_push_metadata();

-- 2. Cập nhật RPC push_campaign (SECURITY DEFINER, nguyên tử, idempotent)
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

    -- 2. Kiểm tra receipt trước khi khóa (so sánh target_campaign_id bất biến)
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

    -- Re-check receipt sau khi đã có khóa
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

    -- 6. Khóa transaction chung cho việc cấp thứ tự đẩy
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
        target_campaign_id,
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

    -- 8. Trả về kết quả hoàn chỉnh
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

-- 3. Cấp quyền thực thi và reload PostgREST
grant execute on function public.push_campaign(uuid, uuid) to authenticated, service_role;
notify pgrst, 'reload schema';
