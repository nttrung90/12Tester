-- ============================================================================
-- 12 Tester — Supabase migration 029: Cửa sổ hiển thị Trang chủ 30 ngày
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
--
-- Nghiệp vụ:
-- 1. App sau khi đăng hiển thị tối đa 30 ngày trên tab "App cần Test"
--    (trước đây 15 ngày); hết 30 ngày (tính từ created_at) tự chuyển sang
--    "Đã hoàn thành Kiểm thử".
-- 2. Chủ app bấm "App đã lên production" trước 30 ngày → app cũng chuyển sang
--    "Đã hoàn thành Kiểm thử" (status 'production').
-- 3. App tạm dừng được bật test lại (RPC resume_campaign) khi còn trong
--    cửa sổ 30 ngày và chủ app đã hoạt động trở lại (missed_streak < 3).
-- 4. Mở khóa / gia hạn Slot đăng app có thời hạn 30 ngày (trước đây 16 ngày).
--    Slot đang hoạt động giữ nguyên hạn cũ; chỉ lần mở khóa/gia hạn mới tính 30 ngày.
-- 5. Mở lại một lần các app đã bị hoàn thành theo luật 15 ngày cũ nếu vẫn trong
--    30 ngày kể từ ngày đăng (chạy khi áp migration).
-- ============================================================================


-- ============================================================================
-- 1. Hàm tự động: hoàn thành kiểm thử sau 30 ngày hiển thị
-- ============================================================================

create or replace function public.complete_expired_campaigns()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    v_count int := 0;
begin
    for r in
        update public.campaigns c
        set status = 'completed',
            completed_at = now()
        where c.status in ('open', 'paused')
          and c.created_at <= now() - interval '30 days'
        returning c.id, c.owner_id, c.app_name
    loop
        v_count := v_count + 1;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã hoàn thành kiểm thử ✅',
            format(
                'Ứng dụng "%s" đã hoàn thành thời gian hiển thị 30 ngày và được chuyển sang mục "Đã hoàn thành Kiểm thử" trên Trang chủ.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'window_expired')
        );
    end loop;

    return v_count;
end;
$$;


-- ============================================================================
-- 2. RPC: Chủ app bật test lại app đang tạm dừng → về "App cần Test"
--    (cửa sổ 30 ngày; không RAISE sau UPDATE để tránh rollback)
-- ============================================================================

drop function if exists public.resume_campaign(uuid);

create or replace function public.resume_campaign(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_camp record;
    v_missed int;
begin
    if v_uid is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id for update;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;
    if v_camp.owner_id <> v_uid then
        raise exception 'PERMISSION_DENIED';
    end if;
    if v_camp.status <> 'paused' then
        raise exception 'CAMPAIGN_NOT_PAUSED';
    end if;

    -- Quá cửa sổ 30 ngày → chuyển sang hoàn thành và trả kết quả (KHÔNG raise,
    -- vì exception sẽ rollback luôn UPDATE khiến app kẹt 'paused').
    if v_camp.created_at <= now() - interval '30 days' then
        update public.campaigns
        set status = 'completed',
            completed_at = now(),
            paused_reason = null
        where id = p_campaign_id;

        return jsonb_build_object(
            'success', true,
            'campaign_id', p_campaign_id,
            'status', 'completed'
        );
    end if;

    -- Chủ app vẫn đang vắng 3 ngày liên tục → yêu cầu làm nhiệm vụ ngày trước
    select greatest(missed_streak, (current_date - last_active_date - 1))
    into v_missed
    from public.profiles
    where id = v_uid;

    if coalesce(v_missed, 0) >= 3 then
        raise exception 'OWNER_INACTIVE';
    end if;

    update public.campaigns
    set status = 'open',
        paused_reason = null,
        completed_at = null
    where id = p_campaign_id;

    return jsonb_build_object(
        'success', true,
        'campaign_id', p_campaign_id,
        'status', 'open'
    );
end;
$$;

grant execute on function public.resume_campaign(uuid) to authenticated, service_role;


-- ============================================================================
-- 3. RPC: Mở khóa hoặc Gia hạn Slot đăng app (30 ngày)
--    Giá Credit giữ nguyên; slot đang hoạt động giữ nguyên hạn cũ.
-- ============================================================================

create or replace function public.unlock_or_renew_slot(p_slot_number int)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_cost int;
    v_balance int;
    v_current_expires timestamptz;
    v_new_expires timestamptz;
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Slot 1 luôn miễn phí và vĩnh viễn
    if p_slot_number < 2 or p_slot_number > 6 then
        raise exception 'INVALID_SLOT_NUMBER';
    end if;

    if p_slot_number = 2 then
        v_cost := 50;
    elsif p_slot_number = 3 then
        v_cost := 100;
    else
        v_cost := 200;
    end if;

    select credits into v_balance
    from public.profiles
    where id = v_user_id
    for update;

    if v_balance is null or v_balance < v_cost then
        raise exception 'INSUFFICIENT_CREDITS';
    end if;

    -- Slot N (N > 2) chỉ mở được khi Slot N - 1 đang còn hoạt động
    if p_slot_number > 2 then
        if not exists (
            select 1 from public.user_slots
            where user_id = v_user_id
              and slot_number = p_slot_number - 1
              and expires_at > now()
        ) then
            raise exception 'PREVIOUS_SLOT_NOT_ACTIVE';
        end if;
    end if;

    -- Tính hạn mới (+30 ngày); cộng dồn nếu slot còn hoạt động
    select expires_at into v_current_expires
    from public.user_slots
    where user_id = v_user_id and slot_number = p_slot_number;

    if v_current_expires is not null and v_current_expires > now() then
        v_new_expires := v_current_expires + interval '30 days';
    else
        v_new_expires := now() + interval '30 days';
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
        format('Mở khóa / Gia hạn Slot %s (thời hạn 30 ngày)', p_slot_number),
        v_balance
    );

    return jsonb_build_object(
        'success', true,
        'slot_number', p_slot_number,
        'cost', v_cost,
        'balance', v_balance,
        'expires_at', to_char(v_new_expires at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
end;
$$;

grant execute on function public.unlock_or_renew_slot(int) to authenticated, anon, service_role;


-- ============================================================================
-- 4. Mở lại một lần các app đã hoàn thành theo luật 15 ngày cũ nhưng vẫn
--    trong 30 ngày kể từ ngày đăng. (status 'completed' chỉ được set bởi
--    luật hết cửa sổ hiển thị; app "lên production" mang status 'production'.)
-- ============================================================================

update public.campaigns
set status = 'open',
    completed_at = null,
    paused_reason = null
where status = 'completed'
  and created_at > now() - interval '30 days';


-- ============================================================================
-- 5. Đồng bộ vòng đời để áp dụng luật 30 ngày cho dữ liệu hiện có:
--    app quá 30 ngày → hoàn thành; chủ app vắng / slot hết hạn → tạm dừng đúng luật.
-- ============================================================================

select public.sync_campaign_lifecycle();

notify pgrst, 'reload schema';
