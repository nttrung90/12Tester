-- ============================================================================
-- 12 Tester — Supabase migration 012: Fix Owner Report Receiving & Single Daily Reward
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Cập nhật toàn bộ RLS Policies cho bảng tester_reports
-- Đảm bảo cả tester và chủ app đều đọc/ghi được không bị chặn
alter table public.tester_reports enable row level security;

-- Cho phép tester xem báo cáo của mình và chủ app xem tất cả báo cáo thuộc app của mình
drop policy if exists "reports_select_own_or_owner" on public.tester_reports;
create policy "reports_select_own_or_owner" on public.tester_reports
    for select to authenticated
    using (
        user_id = auth.uid()
        or exists (
            select 1 from public.campaigns c
            where c.id = tester_reports.campaign_id
              and c.owner_id = auth.uid()
        )
        or exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and p.role = 'admin'
        )
    );

-- Cho phép tester thêm báo cáo của chính mình
drop policy if exists "reports_insert_tester" on public.tester_reports;
create policy "reports_insert_tester" on public.tester_reports
    for insert to authenticated
    with check (user_id = auth.uid());

-- Cho phép tester cập nhật báo cáo của chính mình (chỉnh sửa nhận xét / đổi ảnh trong ngày)
drop policy if exists "reports_update_tester" on public.tester_reports;
create policy "reports_update_tester" on public.tester_reports
    for update to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Cho phép chủ app cập nhật trạng thái báo cáo (duyệt hoặc từ chối)
drop policy if exists "reports_update_owner" on public.tester_reports;
create policy "reports_update_owner" on public.tester_reports
    for update to authenticated
    using (
        exists (
            select 1 from public.campaigns c
            where c.id = tester_reports.campaign_id
              and c.owner_id = auth.uid()
        )
        or exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and p.role = 'admin'
        )
    );


-- 2. Stored Procedure RPC: submit_tester_report
-- Xử lý nộp kết quả test một cách an toàn, nguyên tử (atomic), chống lỗi RLS và đồng bộ ngay
create or replace function public.submit_tester_report(
    p_campaign_id uuid,
    p_content text default '',
    p_screenshot_url text default null,
    p_rating int default 5
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_report_id uuid;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp record;
    v_existing_report record;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
begin
    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    -- Tự động thêm tester vào danh sách thành viên nếu chưa tham gia
    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lưu/Cập nhật đánh giá sao và nhận xét trong bảng feedbacks
    insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    on conflict (campaign_id, user_id)
    do update set
        rating = excluded.rating,
        comment = excluded.comment,
        updated_at = now();

    -- Kiểm tra hôm nay user đã có dòng checkin được duyệt trước đó chưa
    select exists (
        select 1 from public.checkins
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and checkin_date = v_today
    ) into v_already_checked_in;

    -- Tìm xem hôm nay user đã nộp báo cáo nào cho campaign này chưa
    select * into v_existing_report
    from public.tester_reports
    where campaign_id = p_campaign_id
      and user_id = v_user_id
      and (created_at at time zone 'Asia/Ho_Chi_Minh')::date = v_today
    order by created_at desc
    limit 1;

    if v_existing_report.id is not null then
        -- Cập nhật báo cáo đã nộp hôm nay
        v_status := case when v_existing_report.status = 'approved' or v_already_checked_in then 'approved' else 'pending' end;
        update public.tester_reports
        set content = coalesce(p_content, content),
            screenshot_url = coalesce(p_screenshot_url, screenshot_url),
            rating = v_rating,
            status = v_status,
            rejection_reason = null
        where id = v_existing_report.id
        returning id into v_report_id;
    else
        -- Tạo mới báo cáo hôm nay
        v_status := case when v_already_checked_in then 'approved' else 'pending' end;
        insert into public.tester_reports (
            campaign_id,
            user_id,
            report_type,
            content,
            screenshot_url,
            rating,
            status,
            bonus_awarded
        )
        values (
            p_campaign_id,
            v_user_id,
            'feedback',
            coalesce(p_content, ''),
            p_screenshot_url,
            v_rating,
            v_status,
            case when v_already_checked_in then 2 else 0 end
        )
        returning id into v_report_id;
    end if;

    -- Cập nhật ngày hoạt động gần nhất để chống trừ phạt vắng mặt oan
    update public.profiles
    set last_active_date = v_today,
        missed_streak = 0
    where id = v_user_id;

    return jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status
    );
end;
$$;


-- 3. Stored Procedure RPC: get_campaign_reports_for_owner
-- Chủ app gọi hàm này để lấy toàn bộ báo cáo từ tester kèm avatar, tên, điểm (bảo đảm 100% không bị RLS chặn)
create or replace function public.get_campaign_reports_for_owner(p_campaign_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_res jsonb;
begin
    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    select role into v_role from public.profiles where id = v_caller_id;

    if v_camp.owner_id <> v_caller_id and coalesce(v_role, '') <> 'admin' then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Tự động duyệt bất kỳ báo cáo nào quá hạn 24 giờ
    perform public.auto_accept_expired_reports();

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', tr.id,
                'campaign_id', tr.campaign_id,
                'user_id', tr.user_id,
                'report_type', tr.report_type,
                'content', tr.content,
                'screenshot_url', tr.screenshot_url,
                'rating', tr.rating,
                'status', tr.status,
                'bonus_awarded', tr.bonus_awarded,
                'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'rejection_reason', tr.rejection_reason,
                'tester_name', coalesce(p.display_name, split_part(coalesce(p.email, ''), '@', 1), 'Tester'),
                'tester_avatar', p.avatar_url,
                'tester_credits', p.credits
            )
            order by tr.created_at desc
        ),
        '[]'::jsonb
    ) into v_res
    from public.tester_reports tr
    left join public.profiles p on p.id = tr.user_id
    where tr.campaign_id = p_campaign_id;

    return v_res;
end;
$$;


-- 4. Cập nhật approve_tester_report: Đảm bảo 1 tester chỉ nhận điểm 1 lần/ngày cho mỗi app
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
          and checkin_date = current_date
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
        last_active_date = current_date,
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
    values (v_report.campaign_id, v_report.user_id, current_date, 'Đã kiểm thử và được chủ app duyệt')
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


-- 5. Cập nhật auto_accept_expired_reports: Chỉ cộng điểm nếu chưa nhận điểm cho app trong ngày
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
              and checkin_date = current_date
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
                last_active_date = current_date,
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
            values (v_report.campaign_id, v_report.user_id, current_date, 'Tự động duyệt sau 24h')
            on conflict (campaign_id, user_id, checkin_date) do nothing;
        end if;

        v_count := v_count + 1;
    end loop;

    return v_count;
end;
$$;

-- Phân quyền thực thi
grant execute on function public.submit_tester_report(uuid, text, text, int) to authenticated, anon, service_role;
grant execute on function public.get_campaign_reports_for_owner(uuid) to authenticated, anon, service_role;
grant execute on function public.approve_tester_report(uuid, int) to authenticated, anon, service_role;
grant execute on function public.auto_accept_expired_reports() to authenticated, anon, service_role;

notify pgrst, 'reload schema';
