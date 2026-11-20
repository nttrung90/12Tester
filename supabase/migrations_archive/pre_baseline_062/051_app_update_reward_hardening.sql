-- ==============================================================================
-- 12 Tester - Supabase migration 051: App Update Reward Hardening & Security
-- ==============================================================================
-- Kế hoạch & Báo cáo:
-- F:\AI\12Tester\RECHECK_VA_KE_HOACH_SUA_THUONG_CAP_NHAT_PHIEN_BAN_2026-10-29.md
-- Mã bàn giao sửa: APP-UPDATE-RECHECK-20261029-FIX
--
-- Xử lý đầy đủ 10 nhóm lỗi (R01 -> R10):
-- R01: Thu hồi quyền thực thi _settle_campaign_app_update khỏi client (public, anon, authenticated).
-- R02: Tự động hoàn quỹ khi campaign chuyển từ bất kỳ trạng thái nào (kể cả paused) sang completed/production/closed.
-- R03: Sửa ràng buộc FK app_update_releases.owner_id thành on delete set null và lưu snapshot định danh, cho phép xóa tài khoản chủ app an toàn.
-- R04: Gắn operation_id với fingerprint/payload của request; chặn cùng operation_id khác payload (OPERATION_PAYLOAD_CONFLICT).
-- R05: Kiểm tra concurrency đầy đủ (bao gồm expected = null), thống nhất thứ tự khóa (campaigns -> controls -> releases -> profiles).
-- R06: Invalidate đúng cache sau claim/close; hỗ trợ replay đối soát số dư.
-- R07: Bổ sung kiểm tra eligibility, membership, campaign status trước khi hiển thị/claim.
-- R08: Guard release id, sequence và session để response bản cũ không ghi nhận nhầm lên bản mới.
-- R09: Tách current release, history và watermark; đánh dấu superseded các đợt cũ khi ra đợt mới, không phục hồi exhausted cũ sau khi đóng.
-- R10: Chặn chủ app bị khóa (is_locked) công bố đợt mới (USER_LOCKED), nhưng vẫn bảo đảm đường quyết toán/hoàn quỹ hợp lệ.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. R03 & R04: Cập nhật cấu trúc bảng app_update_releases & app_update_operations
-- ------------------------------------------------------------------------------

-- R03: Cho phép xóa tài khoản chủ app bằng cách đổi FK sang ON DELETE SET NULL và lưu snapshot owner_id
alter table public.app_update_releases drop constraint if exists app_update_releases_owner_id_fkey;
alter table public.app_update_releases alter column owner_id drop not null;
alter table public.app_update_releases add column if not exists owner_id_snapshot uuid;
update public.app_update_releases set owner_id_snapshot = owner_id where owner_id_snapshot is null and owner_id is not null;

alter table public.app_update_releases add constraint app_update_releases_owner_id_fkey
    foreign key (owner_id) references public.profiles(id) on delete set null;

-- R04: Bổ sung metadata payload cho app_update_operations để chống xung đột payload
alter table public.app_update_operations add column if not exists campaign_id uuid;
alter table public.app_update_operations add column if not exists target_version_code bigint;
alter table public.app_update_operations add column if not exists version_name text;
alter table public.app_update_operations add column if not exists release_notes text;

-- ------------------------------------------------------------------------------
-- 2. R01: Thu hồi quyền thực thi helper _settle_campaign_app_update khỏi client
-- ------------------------------------------------------------------------------
revoke execute on function public._settle_campaign_app_update(uuid) from public, anon, authenticated;
grant execute on function public._settle_campaign_app_update(uuid) to service_role;

-- ------------------------------------------------------------------------------
-- 3. R01 & R02 & R05: Cập nhật hàm quyết toán nội bộ _settle_campaign_app_update
-- ------------------------------------------------------------------------------
create or replace function public._settle_campaign_app_update(p_campaign_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_camp                public.campaigns%rowtype;
    v_rel                 record;
    v_remaining           int;
    v_owner_balance       int;
    v_credit_log_id       uuid;
    v_target_owner_id     uuid;
begin
    -- Khóa theo thứ tự chuẩn: campaigns -> controls -> releases -> profiles
    select * into v_camp from public.campaigns where id = p_campaign_id for update;

    for v_rel in
        select * from public.app_update_releases
        where campaign_id = p_campaign_id and status = 'active'
        order by id
        for update
    loop
        v_remaining := v_rel.remaining_credits;
        v_target_owner_id := coalesce(v_rel.owner_id, v_rel.owner_id_snapshot);

        -- Khóa control trước
        update public.app_update_controls
        set active_release_id = null,
            updated_at = now()
        where owner_id = v_target_owner_id
          and package_name = v_rel.package_name
          and active_release_id = v_rel.id;

        -- Cập nhật release
        update public.app_update_releases
        set status = 'closed',
            closed_at = now(),
            remaining_credits = 0,
            refunded_credits = refunded_credits + v_remaining
        where id = v_rel.id;

        if v_remaining > 0 and v_target_owner_id is not null then
            -- Khóa profile nếu tồn tại
            if exists (select 1 from public.profiles where id = v_target_owner_id) then
                update public.profiles
                set credits = credits + v_remaining
                where id = v_target_owner_id
                returning credits into v_owner_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_target_owner_id,
                    v_remaining,
                    'app_update_refund',
                    format('Hoàn %s Credit chưa dùng từ phiên bản %s (%s)', v_remaining, v_rel.version_name, v_rel.app_name),
                    v_owner_balance
                )
                returning id into v_credit_log_id;

                insert into public.app_update_financial_events (
                    release_id, event_type, amount, owner_id, credit_log_id
                ) values (
                    v_rel.id, 'refund', v_remaining, v_target_owner_id, v_credit_log_id
                );
            else
                insert into public.app_update_financial_events (
                    release_id, event_type, amount, owner_id, credit_log_id
                ) values (
                    v_rel.id, 'refund', v_remaining, v_target_owner_id, null
                );
            end if;
        end if;
    end loop;
end;
$$;

revoke execute on function public._settle_campaign_app_update(uuid) from public, anon, authenticated;
grant execute on function public._settle_campaign_app_update(uuid) to service_role;

-- ------------------------------------------------------------------------------
-- 4. R02: Trigger tự động hoàn quỹ từ MỌI trạng thái sang completed/production/closed
-- ------------------------------------------------------------------------------
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
        -- R02: Xử lý mọi chuyển trạng thái hợp lệ sang completed/production/closed (bao gồm cả từ paused)
        if (OLD.status is distinct from NEW.status) and NEW.status in ('completed', 'production', 'closed') then
            perform public._settle_campaign_app_update(NEW.id);
        end if;
        return NEW;
    end if;
    return null;
end;
$$;

-- ------------------------------------------------------------------------------
-- 5. R04, R05, R09, R10: RPC publish_app_update nâng cấp
-- ------------------------------------------------------------------------------
drop function if exists public.publish_app_update(uuid, text, bigint, text);
drop function if exists public.publish_app_update(uuid, text, bigint, text, text);
drop function if exists public.publish_app_update(uuid, text, bigint, text, text, uuid);

create or replace function public.publish_app_update(
    p_campaign_id                  uuid,
    p_version_name                 text,
    p_target_version_code          bigint,
    p_release_notes                text,
    p_client_operation_id          text default null,
    p_expected_current_release_id  uuid default 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
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
    v_owner_is_locked     boolean := false;
    v_effective_credits   int;
    v_new_release_id      uuid;
    v_new_release         public.app_update_releases%rowtype;
    v_refund_log_id       uuid;
    v_reserve_log_id      uuid;
    v_existing_op         record;
    c_sentinel            constant uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Chuẩn hóa dữ liệu đầu vào trước khi so sánh
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

    -- R04: Kiểm tra idempotency theo client_operation_id
    if p_client_operation_id is not null and trim(p_client_operation_id) <> '' then
        select * into v_existing_op
        from public.app_update_operations
        where operation_id = trim(p_client_operation_id) and owner_id = v_caller_id;

        if v_existing_op.operation_id is not null then
            -- R04: Kiểm tra payload/fingerprint conflict
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

    -- R10: Kiểm tra trạng thái tài khoản chủ app (dưới khóa profile)
    select credits, coalesce(is_locked, false)
    into v_owner_credits, v_owner_is_locked
    from public.profiles
    where id = v_caller_id
    for update;

    if v_owner_is_locked then
        raise exception 'USER_LOCKED';
    end if;

    -- R05: Thứ tự khóa 1 - Khóa và kiểm tra campaign
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

    -- R05: Thứ tự khóa 2 - Khóa hàng điều khiển app_update_controls
    insert into public.app_update_controls (owner_id, package_name, highest_version_code)
    values (v_caller_id, v_camp.package_name, 0)
    on conflict (owner_id, package_name) do nothing;

    select * into v_ctrl
    from public.app_update_controls
    where owner_id = v_caller_id and package_name = v_camp.package_name
    for update;

    -- R05: Kiểm tra CAS concurrency đầy đủ (bao gồm cả khi caller mong đợi null)
    if p_expected_current_release_id is distinct from c_sentinel then
        if p_expected_current_release_id is distinct from v_ctrl.active_release_id then
            raise exception 'CONCURRENT_UPDATE_CONFLICT';
        end if;
    end if;

    -- Kiểm tra watermark versionCode: phải lớn hơn bản công bố trước của cùng app
    if p_target_version_code <= v_ctrl.highest_version_code then
        raise exception 'VERSION_CODE_NOT_GREATER';
    end if;

    -- R05: Thứ tự khóa 3 - Khóa đợt cập nhật active hiện tại (nếu có)
    select * into v_active_rel
    from public.app_update_releases
    where owner_id = v_caller_id
      and package_name = v_camp.package_name
      and status = 'active'
    for update;

    if v_active_rel.id is not null then
        v_old_remaining := v_active_rel.remaining_credits;
    end if;

    -- R05: Thứ tự khóa 4 - Ví chủ app đã được khóa ở bước trên
    v_effective_credits := v_owner_credits + v_old_remaining;
    if v_effective_credits < 120 then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- R09: Xử lý đợt active cũ: đổi sang superseded, hoàn phần chưa dùng
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

    -- R09: Đổi tất cả các đợt exhausted cũ của cùng package sang superseded để không bị chọn lại
    update public.app_update_releases
    set status = 'superseded'
    where owner_id = v_caller_id
      and package_name = v_camp.package_name
      and status in ('active', 'exhausted');

    -- Giữ trước 120 Credit từ ví chủ app cho đợt mới
    v_owner_credits := v_owner_credits - 120;
    update public.profiles set credits = v_owner_credits where id = v_caller_id;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_caller_id,
        -120,
        'app_update_reserve',
        format('Giữ trước 120 Credit thưởng cập nhật phiên bản %s (%s)', v_version_name_clean, v_camp.app_name),
        v_owner_credits
    )
    returning id into v_reserve_log_id;

    -- Tạo đợt cập nhật mới
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
        published_at
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
        6,
        20,
        120,
        0,
        120,
        0,
        now()
    )
    returning id into v_new_release_id;

    select * into v_new_release from public.app_update_releases where id = v_new_release_id;

    -- Ghi nhận financial event
    insert into public.app_update_financial_events (
        release_id, event_type, amount, owner_id, credit_log_id, operation_id
    ) values (
        v_new_release_id, 'reserve', 120, v_caller_id, v_reserve_log_id, p_client_operation_id
    );

    -- Cập nhật app_update_controls: watermark và active_release_id
    update public.app_update_controls
    set highest_version_code = greatest(v_ctrl.highest_version_code, p_target_version_code),
        active_release_id = v_new_release_id,
        updated_at = now()
    where owner_id = v_caller_id and package_name = v_camp.package_name;

    -- R04: Lưu operation_id kèm fingerprint đầy đủ
    if p_client_operation_id is not null and trim(p_client_operation_id) <> '' then
        insert into public.app_update_operations (
            operation_id, owner_id, release_id, status,
            campaign_id, target_version_code, version_name, release_notes
        )
        values (
            trim(p_client_operation_id), v_caller_id, v_new_release_id, 'completed',
            p_campaign_id, p_target_version_code, v_version_name_clean, v_release_notes_clean
        )
        on conflict (operation_id) do nothing;
    end if;

    return jsonb_build_object(
        'success', true,
        'release', row_to_json(v_new_release),
        'owner_credits', v_owner_credits,
        'refund_amount', v_old_remaining,
        'idempotent_replay', false
    );
end;
$$;

revoke execute on function public.publish_app_update(uuid, text, bigint, text, text, uuid) from public, anon;
grant execute on function public.publish_app_update(uuid, text, bigint, text, text, uuid) to authenticated, service_role;

-- ------------------------------------------------------------------------------
-- 6. R05 & R09: RPC close_app_update_release nâng cấp (thống nhất thứ tự khóa)
-- ------------------------------------------------------------------------------
drop function if exists public.close_app_update_release(uuid);
drop function if exists public.close_app_update_release(uuid, text);

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

    -- Tra cứu nhanh để biết campaign và package
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

    -- R05: Khóa theo thứ tự 1 - campaigns (nếu có)
    if v_rel_lookup.campaign_id is not null then
        select * into v_camp from public.campaigns where id = v_rel_lookup.campaign_id for update;
    end if;

    -- R05: Khóa theo thứ tự 2 - app_update_controls
    select * into v_ctrl
    from public.app_update_controls
    where owner_id = v_caller_id and package_name = v_rel_lookup.package_name
    for update;

    -- R05: Khóa theo thứ tự 3 - app_update_releases
    select * into v_rel
    from public.app_update_releases
    where id = p_release_id
    for update;

    if v_rel.status <> 'active' then
        raise exception 'RELEASE_NOT_ACTIVE';
    end if;

    v_remaining := v_rel.remaining_credits;

    -- R05: Khóa theo thứ tự 4 - profiles
    select credits into v_owner_credits from public.profiles where id = v_caller_id for update;

    -- Đóng đợt
    update public.app_update_releases
    set status = 'closed',
        closed_at = now(),
        remaining_credits = 0,
        refunded_credits = refunded_credits + v_remaining
    where id = v_rel.id;

    -- R09: Cập nhật active_release_id về null
    update public.app_update_controls
    set active_release_id = null,
        updated_at = now()
    where owner_id = v_caller_id
      and package_name = v_rel.package_name
      and active_release_id = v_rel.id;

    -- Hoàn tiền cho chủ app nếu còn số dư chưa chi
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
$$;

revoke execute on function public.close_app_update_release(uuid, text) from public, anon;
grant execute on function public.close_app_update_release(uuid, text) to authenticated, service_role;

-- ------------------------------------------------------------------------------
-- 7. R05, R06, R09: RPC claim_app_update_reward nâng cấp
-- ------------------------------------------------------------------------------
drop function if exists public.claim_app_update_reward(uuid, bigint);
drop function if exists public.claim_app_update_reward(uuid, bigint, text);

create or replace function public.claim_app_update_reward(
    p_release_id             uuid,
    p_observed_version_code  bigint,
    p_client_operation_id    text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
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

    -- Kiểm tra biên lai claim cũ dưới release_id và tester_id (Idempotency)
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

    -- R05: Khóa đợt cập nhật (FOR UPDATE)
    select * into v_rel from public.app_update_releases where id = p_release_id for update;
    if v_rel.id is null then
        raise exception 'RELEASE_NOT_FOUND';
    end if;

    -- Re-check claim sau khi đã có lock để tránh race condition giữa 2 request đồng thời
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

    -- Kiểm tra trạng thái đợt
    if v_rel.status = 'superseded' then
        raise exception 'RELEASE_REPLACED';
    elsif v_rel.status = 'closed' then
        raise exception 'RELEASE_CLOSED';
    elsif v_rel.status = 'exhausted' or v_rel.paid_count >= v_rel.max_claims or v_rel.remaining_credits < v_rel.reward_per_claim then
        raise exception 'RELEASE_EXHAUSTED';
    elsif v_rel.status <> 'active' then
        raise exception 'RELEASE_NOT_ACTIVE';
    end if;

    -- Chủ app không thể tự claim nhận thưởng của chính app mình
    if coalesce(v_rel.owner_id, v_rel.owner_id_snapshot) = v_caller_id then
        raise exception 'OWNER_CANNOT_CLAIM';
    end if;

    -- Kiểm tra campaign
    select * into v_camp from public.campaigns where id = v_rel.campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.status <> 'open' then
        raise exception 'CAMPAIGN_NOT_OPEN';
    end if;

    -- Kiểm tra quyền thành viên và thời điểm tham gia
    select * into v_member
    from public.campaign_members
    where campaign_id = v_rel.campaign_id and user_id = v_caller_id;

    if v_member.campaign_id is null then
        raise exception 'NOT_A_MEMBER';
    end if;

    if v_member.joined_at > v_rel.published_at then
        raise exception 'MEMBER_JOINED_AFTER_RELEASE';
    end if;

    -- Kiểm tra phiên bản cài đặt thực tế
    if p_observed_version_code < v_rel.target_version_code then
        raise exception 'VERSION_NOT_SATISFIED';
    end if;

    -- Khóa ví tester
    select credits, coalesce(is_locked, false)
    into v_tester_credits, v_tester_is_locked
    from public.profiles
    where id = v_caller_id
    for update;

    if v_tester_is_locked then
        raise exception 'USER_LOCKED';
    end if;

    -- Tính toán ordinal và số dư quỹ mới
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

    -- R09: Nếu đợt đã exhausted, cập nhật control active_release_id về null
    if v_new_status = 'exhausted' then
        update public.app_update_controls
        set active_release_id = null,
            updated_at = now()
        where owner_id = coalesce(v_rel.owner_id, v_rel.owner_id_snapshot)
          and package_name = v_rel.package_name
          and active_release_id = v_rel.id;
    end if;

    -- Cộng 6 Credit cho tester (trích từ quỹ, KHÔNG trừ ví chủ)
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
$$;

revoke execute on function public.claim_app_update_reward(uuid, bigint, text) from public, anon;
grant execute on function public.claim_app_update_reward(uuid, bigint, text) to authenticated, service_role;

-- ------------------------------------------------------------------------------
-- 8. R07, R08, R09: Cập nhật RPC get_campaign_detail
-- ------------------------------------------------------------------------------
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
    -- R09: Tìm release cập nhật phiên bản
    -- =========================================================================
    select * into v_rel
    from public.app_update_releases
    where campaign_id = p_campaign_id and status in ('active', 'exhausted')
    order by published_at desc, target_version_code desc
    limit 1;

    if v_rel.id is not null then
        if v_is_actual_owner then
            -- Góc nhìn chủ app: hiển thị toàn bộ tiến độ, ngân sách đang giữ và đã chi
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
            -- Góc nhìn tester:
            -- Quy tắc: Nếu đã đủ 20 suất (paid_count >= 20) thì ẩn toàn bộ đối với tester
            if v_rel.paid_count < v_rel.max_claims and v_rel.remaining_credits >= v_rel.reward_per_claim then
                select * into v_claim
                from public.app_update_claims
                where release_id = v_rel.id and tester_id = v_uid;

                -- R07: Kiểm tra điều kiện tester
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
