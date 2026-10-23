-- ============================================================================
-- 035_admin_penalty_exemption.sql
-- Miễn phạt vắng mặt cho admin/moderator:
--   1. Không trừ Credit bởi apply_daily_inactivity_penalty()
--   2. Không auto-pause app bởi pause_inactive_owner_campaigns()
--   3. Reset missed_streak cũ để UI không còn cảnh báo phạt
-- ============================================================================

-- 1. Miễn trừ Credit penalty
create or replace function public.apply_daily_inactivity_penalty()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    r record;
    pct numeric;
    deduction int;
    new_balance int;
    yesterday_date date := current_date - 1;
begin
    for r in 
        select id, credits, missed_streak, last_active_date, created_at
        from public.profiles
        where last_active_date < yesterday_date 
          and credits > 0
          and created_at < (now() - interval '24 hours')
          and lower(role) not in ('admin', 'moderator')
    loop
        r.missed_streak := r.missed_streak + 1;

        if r.missed_streak = 1 then
            pct := 0.01;
        elsif r.missed_streak = 2 then
            pct := 0.02;
        elsif r.missed_streak = 3 then
            pct := 0.05;
        else
            pct := 0.10;
        end if;

        -- Trừ tối thiểu 1 credit nếu tỉ lệ % ra nhỏ hơn 1
        deduction := greatest(1, ceil(r.credits * pct)::int);
        if deduction > r.credits then
            deduction := r.credits;
        end if;
        new_balance := r.credits - deduction;

        update public.profiles
        set credits = new_balance,
            missed_streak = r.missed_streak
        where id = r.id;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            r.id,
            -deduction,
            'inactivity_penalty',
            format('Trừ %s%% do không test app ngày thứ %s (còn %s Credit)', (pct * 100)::int, r.missed_streak, new_balance),
            new_balance
        );
    end loop;
end;
$$;

-- 2. Miễn auto-pause app của admin/moderator
create or replace function public.pause_inactive_owner_campaigns()
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
        set status = 'paused',
            paused_reason = 'owner_inactive'
        from public.profiles p
        where c.owner_id = p.id
          and c.status = 'open'
          and coalesce(c.admin_resumed, false) = false
          and c.created_at <= now() - interval '24 hours'
          and greatest(p.missed_streak, (current_date - p.last_active_date - 1)) >= 3
          and lower(p.role) not in ('admin', 'moderator')
        returning c.id, c.owner_id, c.app_name
    loop
        v_count := v_count + 1;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            r.owner_id,
            'Ứng dụng đã bị tạm dừng ⏸',
            format(
                'Ứng dụng "%s" đã bị tạm dừng vì bạn không hoàn thành nhiệm vụ ngày trong 3 ngày liên tục. Hãy test 1 app hôm nay, sau đó bấm "Tiếp tục nhận tester" trong màn hình quản lý app để hiển thị lại.',
                coalesce(nullif(r.app_name, ''), 'của bạn')
            ),
            'campaign',
            jsonb_build_object('campaign_id', r.id, 'reason', 'owner_inactive')
        );
    end loop;

    return v_count;
end;
$$;

-- 3. Reset streak cũ của admin/moderator
update public.profiles
set missed_streak = 0
where lower(role) in ('admin', 'moderator')
  and missed_streak <> 0;

-- Giữ nguyên quyền function
revoke execute on function public.apply_daily_inactivity_penalty()
    from public, anon, authenticated;
grant execute on function public.apply_daily_inactivity_penalty()
    to service_role;

grant execute on function public.pause_inactive_owner_campaigns()
    to authenticated, service_role;

notify pgrst, 'reload schema';