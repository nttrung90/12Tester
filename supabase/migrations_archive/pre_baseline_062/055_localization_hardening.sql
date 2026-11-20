-- ==============================================================================
-- 12 Tester - Supabase migration 055: Localization Hardening (L01-L09 / R01-R05)
-- ==============================================================================
-- Kế hoạch: KE_HOACH_SUA_DA_NGON_NGU_CHO_GEMINI_2026-10-29.md
-- Recheck: RECHECK_VA_KE_HOACH_SUA_TIEP_DA_NGON_NGU_2026-10-29.md
--
-- Mục tiêu:
-- 1. L08/R01: Cung cấp cột title_vi, title_en, message_vi, message_en cho public.app_configs.
-- 2. R01: Drop ambiguous overloads trước khi tạo hàm mới để tránh SQLSTATE 42725.
-- 3. R02: Bảo toàn response contract cũ trong reject_tester_report đồng thời cung cấp alias mới.
-- 4. R03: Cập nhật triggers thông báo (trg_notify_tester_report, trg_notify_credit_change)
--    để phát event_code và parameters có cấu trúc trong notifications.data.
-- 5. R04: Thêm reason_code cho tester_reports, tự động dọn dẹp reason_code khi nộp lại.
-- 6. R05: Bổ sung metadata jsonb cho credit_logs và user_warnings.
-- ==============================================================================

-- 1. L08: Nâng cấp bảng public.app_configs
alter table public.app_configs
    add column if not exists title_vi text default null,
    add column if not exists title_en text default null,
    add column if not exists message_vi text default null,
    add column if not exists message_en text default null;

update public.app_configs
set title_vi = case
        when title ~* '[àáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵđ]'
            then coalesce(title_vi, title, 'Đã có bản cập nhật mới')
        else coalesce(title_vi, 'Đã có bản cập nhật mới')
    end,
    title_en = case
        when title ~* '[àáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵđ]'
            then coalesce(title_en, 'New Update Available')
        else coalesce(title_en, title, 'New Update Available')
    end,
    message_vi = case
        when message ~* '[àáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵđ]'
            then coalesce(message_vi, message, 'Phiên bản mới với nhiều tính năng nâng cấp và sửa lỗi đã sẵn sàng. Vui lòng cập nhật để tiếp tục sử dụng ứng dụng mượt mà nhất.')
        else coalesce(message_vi, 'Phiên bản mới với nhiều tính năng nâng cấp và sửa lỗi đã sẵn sàng. Vui lòng cập nhật để tiếp tục sử dụng ứng dụng mượt mà nhất.')
    end,
    message_en = case
        when message ~* '[àáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵđ]'
            then coalesce(message_en, 'A new update with improvements and bug fixes is ready. Please update now for the best experience.')
        else coalesce(message_en, message, 'A new update with improvements and bug fixes is ready. Please update now for the best experience.')
    end
where id = 'android_client';

-- 2. R01: Xóa overload cũ trước khi tạo hàm mới để tránh mơ hồ chữ ký (SQLSTATE 42725)
drop function if exists public.admin_update_app_config(integer, integer, text, boolean, text, text, text);

create or replace function public.admin_update_app_config(
    p_min_version_code int,
    p_latest_version_code int,
    p_latest_version_name text,
    p_force_update boolean,
    p_title text default null,
    p_message text default null,
    p_store_url text default null,
    p_title_vi text default null,
    p_title_en text default null,
    p_message_vi text default null,
    p_message_en text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_config public.app_configs;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_caller_role is distinct from 'admin' then
        raise exception 'NOT_ADMIN';
    end if;

    if p_min_version_code < 1 or p_latest_version_code < p_min_version_code then
        raise exception 'INVALID_VERSION_RANGE';
    end if;

    if p_latest_version_name is null or trim(p_latest_version_name) = '' then
        raise exception 'INVALID_VERSION_NAME';
    end if;

    insert into public.app_configs (
        id,
        min_version_code,
        latest_version_code,
        latest_version_name,
        force_update,
        title,
        message,
        store_url,
        title_vi,
        title_en,
        message_vi,
        message_en,
        updated_at
    )
    values (
        'android_client',
        p_min_version_code,
        p_latest_version_code,
        trim(p_latest_version_name),
        p_force_update,
        coalesce(p_title, p_title_vi, p_title_en),
        coalesce(p_message, p_message_vi, p_message_en),
        p_store_url,
        p_title_vi,
        p_title_en,
        p_message_vi,
        p_message_en,
        now()
    )
    on conflict (id) do update set
        min_version_code = excluded.min_version_code,
        latest_version_code = excluded.latest_version_code,
        latest_version_name = excluded.latest_version_name,
        force_update = excluded.force_update,
        title = coalesce(excluded.title, p_title_vi, p_title_en),
        message = coalesce(excluded.message, p_message_vi, p_message_en),
        store_url = coalesce(excluded.store_url, public.app_configs.store_url),
        title_vi = excluded.title_vi,
        title_en = excluded.title_en,
        message_vi = excluded.message_vi,
        message_en = excluded.message_en,
        updated_at = now()
    returning * into v_config;

    return to_jsonb(v_config);
end;
$$;

-- 3. L07 / R04: Nâng cấp bảng public.tester_reports
alter table public.tester_reports
    add column if not exists reason_code text default null;

create index if not exists idx_tester_reports_reason_code
    on public.tester_reports(reason_code)
    where reason_code is not null;

-- Tự động trích xuất reason_code cho các báo cáo đã bị từ chối trước đây nếu có định dạng [CODE:...]
update public.tester_reports
set reason_code = substring(rejection_reason from '\[CODE:([A-Za-z0-9_]+)\]')
where rejection_reason like '[CODE:%]'
  and reason_code is null;

-- Tự động xóa reason_code và rejection_reason khi tester nộp lại báo cáo (status: rejected -> pending)
create or replace function public.trg_reset_tester_report_rejection()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if tg_op = 'UPDATE' and old.status = 'rejected' and new.status = 'pending' then
        new.reason_code := null;
        new.rejection_reason := null;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_tester_reports_reset_rejection on public.tester_reports;
create trigger trg_tester_reports_reset_rejection
before update on public.tester_reports
for each row execute function public.trg_reset_tester_report_rejection();

-- 4. R01 / R02: Xóa overload cũ trước khi tạo hàm mới để tránh mơ hồ chữ ký (SQLSTATE 42725)
drop function if exists public.reject_tester_report(uuid, text);

create or replace function public.reject_tester_report(
    p_report_id uuid,
    p_reason text default null,
    p_reason_code text default null
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

    -- Quá 8h: không cho từ chối tay, để auto_accept xử lý.
    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    -- Xác định reason_code: ưu tiên p_reason_code, sau đó trích xuất từ [CODE:...] trong p_reason
    if p_reason is not null and p_reason like '[CODE:%]' then
        v_extracted_code := substring(p_reason from '\[CODE:([A-Za-z0-9_]+)\]');
    end if;
    v_final_reason_code := coalesce(p_reason_code, v_extracted_code);

    -- Ghi audit TRƯỚC khi tính cảnh báo: event này cũng được tính vào hôm nay.
    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type, reason
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'reject', p_reason
    );

    v_report_date := (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

    -- 1. Thu hồi đúng số Credit THỰC đã cấp khi nộp (10 mới, 2 cũ).
    select credits into v_tester_credits
    from public.profiles
    where id = v_report.user_id
    for update;

    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(10, coalesce(v_report.provisional_granted_credits, 2)))
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

    -- 2. Cập nhật trạng thái báo cáo thành rejected với reason_code
    update public.tester_reports
    set status = 'rejected',
        rejection_reason = p_reason,
        reason_code = v_final_reason_code,
        bonus_awarded = 0,
        provisional_clawed = v_deduct,
        reviewed_at = now()
    where id = p_report_id;

    -- 3. Chỉ xóa checkin TẠM TÍNH (không xóa checkin xác nhận khác trong ngày)
    delete from public.checkins
    where campaign_id = v_report.campaign_id
      and user_id = v_report.user_id
      and checkin_date = v_report_date
      and coalesce(note, '') like '%tạm tính%';

    -- 4. Cảnh báo tester: đếm từ audit event (không bị reset khi nộp lại)
    select count(*) into v_tester_today_rejections
    from public.report_review_events
    where tester_id = v_report.user_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_tester_today_rejections >= 3 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_report.user_id
              and warning_type = 'tester_rejection_streak'
              and warning_date = v_vn_today
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
            from public.user_warnings
            where user_id = v_report.user_id
              and warning_date >= (v_vn_today - 6);

            if v_tester_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_report.user_id;
                v_is_tester_locked := true;
            end if;
        end if;
    end if;

    -- 5. Cảnh báo chủ app: đếm từ audit event
    select count(*) into v_owner_today_rejections
    from public.report_review_events
    where owner_id = v_camp.owner_id
      and event_type = 'reject'
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_vn_today;

    if v_owner_today_rejections >= 5 then
        if not exists (
            select 1 from public.user_warnings
            where user_id = v_camp.owner_id
              and warning_type = 'owner_rejection_streak'
              and warning_date = v_vn_today
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
            from public.user_warnings
            where user_id = v_camp.owner_id
              and warning_date >= (v_vn_today - 6);

            if v_owner_weekly_warnings >= 3 then
                update public.profiles
                set is_locked = true
                where id = v_camp.owner_id;
                v_is_owner_locked := true;
            end if;
        end if;
    end if;

    -- R02: Trả đầy đủ cả khóa cũ (credits_deducted, tester_balance, is_tester_locked, is_owner_locked...)
    -- lẫn khóa alias mới (clawed_credits, new_balance, tester_locked, owner_locked...)
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
$$;

-- 5. R03: Cập nhật producers thông báo để phát event_code và parameters
create or replace function public.trg_notify_tester_report()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
    v_extra int;
    v_clawed int;
begin
    if coalesce(current_setting('app.suppress_report_notify', true), '') = '1' then
        return new;
    end if;

    -- 1. Báo cáo mới
    if tg_op = 'INSERT' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo kiểm thử mới 📋',
                format('Ứng dụng "%s" vừa nhận được 1 báo cáo kiểm thử mới. Bạn có 8 giờ để kiểm tra và duyệt (+2 Credit thưởng cho tester và +2 Credit cho bạn nếu duyệt).', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object(
                    'campaign_id', new.campaign_id,
                    'report_id', new.id,
                    'status', 'pending',
                    'event_code', 'new_report',
                    'app_name', coalesce(v_camp_name, 'của bạn')
                )
            );
        end if;

    -- 2. Tester nộp lại báo cáo sau khi bị từ chối
    elsif tg_op = 'UPDATE' and old.status = 'rejected' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo nộp lại 🔄',
                format('Tester đã nộp lại báo cáo cho ứng dụng "%s". Vui lòng kiểm tra lại.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object(
                    'campaign_id', new.campaign_id,
                    'report_id', new.id,
                    'status', 'pending',
                    'event_code', 'resubmit_report',
                    'app_name', coalesce(v_camp_name, 'của bạn')
                )
            );
        end if;

    -- 3. Tester sửa báo cáo đang chờ duyệt (đổi ảnh/nội dung) -> báo chủ app
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status = 'pending'
          and (old.screenshot_url is distinct from new.screenshot_url
               or old.image_hash is distinct from new.image_hash
               or old.content is distinct from new.content) then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Tester cập nhật báo cáo ✏️',
                format('Tester vừa cập nhật báo cáo kiểm thử cho ứng dụng "%s". Vui lòng xem lại ảnh/nội dung mới trước khi duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object(
                    'campaign_id', new.campaign_id,
                    'report_id', new.id,
                    'status', 'pending',
                    'is_edited', true,
                    'event_code', 'report_edited',
                    'app_name', coalesce(v_camp_name, 'của bạn')
                )
            );
        end if;

    -- 4. Chủ app/Admin/Moderator duyệt hoặc từ chối
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            v_extra := greatest(0, coalesce(new.bonus_awarded, 0) - coalesce(old.bonus_awarded, 0));

            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử được phê duyệt! 🎉',
                format('Báo cáo của bạn cho ứng dụng "%s" đã được duyệt thành công! Bạn nhận thêm %s Credit cho lượt test này. Cảm ơn bạn!',
                    coalesce(v_camp_name, 'ứng dụng'),
                    v_extra
                ),
                'report',
                jsonb_build_object(
                    'campaign_id', new.campaign_id,
                    'report_id', new.id,
                    'status', 'approved',
                    'event_code', 'report_approved',
                    'app_name', coalesce(v_camp_name, 'ứng dụng'),
                    'bonus', v_extra
                )
            );
        elsif new.status = 'rejected' then
            v_clawed := greatest(0, coalesce(new.provisional_clawed, 0));

            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                case
                    when v_clawed > 0 and new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Lý do: "%s". %s Credit tạm nhận đã bị thu hồi. Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason, v_clawed)
                    when v_clawed > 0 then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. %s Credit tạm nhận đã bị thu hồi. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), v_clawed)
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Lý do: "%s". Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc để nộp lại.', coalesce(v_camp_name, 'ứng dụng'))
                end,
                'report',
                jsonb_build_object(
                    'campaign_id', new.campaign_id,
                    'report_id', new.id,
                    'status', 'rejected',
                    'reason', new.rejection_reason,
                    'reason_code', new.reason_code,
                    'event_code', 'report_rejected',
                    'app_name', coalesce(v_camp_name, 'ứng dụng'),
                    'credits_clawed', v_clawed
                )
            );
        end if;
    end if;

    return new;
end;
$$;

create or replace function public.trg_notify_credit_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_title text;
begin
    if new.action = 'test_approved' then
        return new;
    end if;

    if new.amount > 0 then
        v_title := format('Biến động số dư: +%s Credit 🪙', new.amount);
    else
        v_title := format('Biến động số dư: %s Credit 🪙', new.amount);
    end if;

    insert into public.notifications (user_id, title, message, type, data)
    values (
        new.user_id,
        v_title,
        coalesce(new.note, format('Số dư hiện tại: %s Credit', new.balance)),
        'credit',
        jsonb_build_object(
            'amount', new.amount,
            'balance', new.balance,
            'action', new.action,
            'event_code', 'credit_change'
        )
    );

    return new;
exception when others then
    return new;
end;
$$;

-- 6. L03 / R05: Nâng cấp bảng public.credit_logs & public.user_warnings
alter table public.credit_logs
    add column if not exists metadata jsonb default null;

alter table public.user_warnings
    add column if not exists metadata jsonb default null;

-- Phân quyền rõ ràng với chữ ký tường minh (R01)
grant select on public.app_configs to anon, authenticated, service_role;
grant execute on function public.admin_update_app_config(integer, integer, text, boolean, text, text, text, text, text, text, text) to authenticated, service_role;
grant execute on function public.reject_tester_report(uuid, text, text) to authenticated, service_role;

-- Tải lại cấu hình PostgREST
notify pgrst, 'reload schema';
