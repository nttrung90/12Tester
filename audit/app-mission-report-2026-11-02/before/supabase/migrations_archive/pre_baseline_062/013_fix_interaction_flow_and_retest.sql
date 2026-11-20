-- ============================================================================
-- 12 Tester — Supabase migration 013: Fix Interaction Flow, Re-test Notification & Timezone
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Cập nhật trigger thông báo trg_notify_tester_report
-- Bắt thêm:
-- - Khi tester nộp lại sau khi bị từ chối (old.status = 'rejected' AND new.status = 'pending') -> Báo cho Chủ app
-- - Kèm lý do từ chối chi tiết (rejection_reason) gửi cho Tester khi bị từ chối
create or replace function public.trg_notify_tester_report()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_owner_id uuid;
    v_camp_name text;
begin
    -- Trường hợp 1: Có báo cáo mới được tạo
    if tg_op = 'INSERT' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo kiểm thử mới 📋',
                format('Ứng dụng "%s" vừa nhận được 1 báo cáo kiểm thử mới từ tester. Vui lòng kiểm tra và duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending')
            );
        end if;

    -- Trường hợp 2: Tester nộp lại báo cáo sau khi bị từ chối trước đó
    elsif tg_op = 'UPDATE' and old.status = 'rejected' and new.status = 'pending' then
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if v_owner_id is not null and v_owner_id <> new.user_id then
            insert into public.notifications (user_id, title, message, type, data)
            values (
                v_owner_id,
                'Báo cáo nộp lại 📋',
                format('Tester vừa nộp lại kết quả kiểm thử cho ứng dụng "%s" sau khi bị từ chối. Vui lòng kiểm tra và duyệt.', coalesce(v_camp_name, 'của bạn')),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'pending', 'is_resubmission', true)
            );
        end if;

    -- Trường hợp 3: Chủ app duyệt hoặc từ chối báo cáo
    elsif tg_op = 'UPDATE' and old.status = 'pending' and new.status <> 'pending' then
        select app_name into v_camp_name
        from public.campaigns
        where id = new.campaign_id;

        if new.status = 'approved' then
            -- Tester được duyệt báo cáo
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử được phê duyệt! 🎉',
                format('Báo cáo của bạn cho ứng dụng "%s" đã được duyệt thành công (+%s Credit). Cảm ơn bạn!', coalesce(v_camp_name, 'ứng dụng'), coalesce(new.bonus_awarded, 2)),
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'approved')
            );
        elsif new.status = 'rejected' then
            -- Tester bị từ chối báo cáo (kèm lý do cụ thể)
            insert into public.notifications (user_id, title, message, type, data)
            values (
                new.user_id,
                'Báo cáo kiểm thử bị từ chối ⚠️',
                case
                    when new.rejection_reason is not null and trim(new.rejection_reason) <> '' then
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Lý do: "%s". Vui lòng kiểm tra và chụp ảnh khác để nộp lại.', coalesce(v_camp_name, 'ứng dụng'), new.rejection_reason)
                    else
                        format('Báo cáo của bạn cho ứng dụng "%s" đã bị từ chối. Hãy đảm bảo chụp đúng giao diện ứng dụng và kiểm thử nghiêm túc.', coalesce(v_camp_name, 'ứng dụng'))
                end,
                'report',
                jsonb_build_object('campaign_id', new.campaign_id, 'report_id', new.id, 'status', 'rejected', 'reason', new.rejection_reason)
            );
        end if;
    end if;

    return new;
exception when others then
    return new;
end;
$$;

drop trigger if exists trg_on_tester_report_event on public.tester_reports;
create trigger trg_on_tester_report_event
    after insert or update on public.tester_reports
    for each row
    execute function public.trg_notify_tester_report();


-- 2. Cập nhật approve_tester_report: Chuẩn hóa múi giờ Việt Nam (Asia/Ho_Chi_Minh)
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
    v_reward int := 2 + greatest(0, coalesce(p_bonus, 0));
    v_new_balance int;
    v_already_checked_in boolean := false;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Khóa và lấy bản ghi báo cáo
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

    -- Kiểm tra quyền của người gọi (chủ app hoặc admin)
    select * into v_camp
    from public.campaigns
    where id = v_report.campaign_id;

    select role into v_caller_role
    from public.profiles
    where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id and coalesce(v_caller_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Kiểm tra xem hôm nay tester đã nhận điểm kiểm thử (check-in) cho app này chưa
    select exists (
        select 1 from public.checkins
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = v_today
    ) into v_already_checked_in;

    if v_already_checked_in then
        -- Đã nhận điểm hôm nay: Duyệt báo cáo nhưng KHÔNG cộng thêm Credit
        update public.tester_reports
        set status = 'approved',
            bonus_awarded = 0,
            reviewed_at = now()
        where id = p_report_id;

        select credits into v_new_balance
        from public.profiles
        where id = v_report.user_id;

        return jsonb_build_object(
            'success', true,
            'report_id', p_report_id,
            'tester_id', v_report.user_id,
            'status', 'approved',
            'credits_awarded', 0,
            'tester_balance', v_new_balance,
            'message', 'Báo cáo đã được duyệt (Tester đã nhận thưởng cho app này hôm nay)'
        );
    end if;

    -- Chưa nhận điểm hôm nay: Duyệt báo cáo và cộng điểm thưởng
    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_reward,
        reviewed_at = now()
    where id = p_report_id;

    -- Cộng Credit cho tester
    update public.profiles
    set credits = credits + v_reward,
        last_active_date = v_today,
        missed_streak = 0
    where id = v_report.user_id
    returning credits into v_new_balance;

    -- Ghi lịch sử Credit
    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_report.user_id,
        v_reward,
        'test_approved',
        format('Chủ app duyệt kết quả kiểm thử (+%s Credit)', v_reward),
        v_new_balance
    );

    -- Ghi nhận lượt check-in hôm nay
    insert into public.checkins (campaign_id, user_id, checkin_date, note)
    values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được chủ app duyệt')
    on conflict (campaign_id, user_id, checkin_date) do nothing;

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_reward,
        'tester_balance', v_new_balance,
        'message', 'Duyệt kết quả thành công (+2 Credit)'
    );
end;
$$;


-- 3. Cập nhật auto_accept_expired_reports: Chuẩn hóa múi giờ Việt Nam
create or replace function public.auto_accept_expired_reports()
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_report record;
    v_count int := 0;
    v_new_balance int;
    v_already_checked_in boolean := false;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    for v_report in
        select id, campaign_id, user_id
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '24 hours')
        for update skip locked
    loop
        -- Kiểm tra xem tester đã có checkin hôm nay cho app này chưa
        select exists (
            select 1 from public.checkins
            where campaign_id = v_report.campaign_id
              and user_id = v_report.user_id
              and checkin_date = v_today
        ) into v_already_checked_in;

        if v_already_checked_in then
            -- Chỉ đổi trạng thái duyệt, không cộng thêm điểm
            update public.tester_reports
            set status = 'approved',
                bonus_awarded = 0,
                reviewed_at = now()
            where id = v_report.id;
        else
            -- Đổi trạng thái duyệt và cộng 2 Credit
            update public.tester_reports
            set status = 'approved',
                bonus_awarded = 2,
                reviewed_at = now()
            where id = v_report.id;

            update public.profiles
            set credits = credits + 2,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_report.user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_report.user_id,
                2,
                'auto_accepted',
                'Hệ thống tự động duyệt kết quả kiểm thử sau 24h (+2 Credit)',
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (v_report.campaign_id, v_report.user_id, v_today, 'Tự động duyệt sau 24h')
            on conflict (campaign_id, user_id, checkin_date) do nothing;
        end if;

        v_count := v_count + 1;
    end loop;

    return v_count;
end;
$$;

-- Cấp quyền thực thi
grant execute on function public.approve_tester_report(uuid, int) to authenticated, anon, service_role;
grant execute on function public.auto_accept_expired_reports() to authenticated, anon, service_role;

notify pgrst, 'reload schema';
