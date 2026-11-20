-- ============================================================================
-- 038_my_rank.sql
-- Mục đích:
--   + Thêm RPC get_my_rank_v1(p_sort) — trả về hạng chính xác của người dùng
--     hiện tại (auth.uid()) theo cùng thứ tự sắp xếp với get_leaderboard_v2:
--       - 'credit' (mặc định): credits desc, checkin_count desc, campaigns_count desc
--       - 'test'              : checkin_count desc, credits desc, campaigns_count desc
--   + Kèm tổng số thành viên để hiển thị "#42 / 567".
--   + Hạng = số người xếp trên mình (so sánh bộ ba tie-break) + 1, nên chính
--     xác kể cả khi người dùng nằm ngoài top 100.
--   + Trả về '{}'::jsonb khi không xác định được hạng (chưa đăng nhập,
--     không có profile, hoặc sort 'test' mà chưa có lượt test nào).
-- ============================================================================

create or replace function public.get_my_rank_v1(
    p_sort text default 'credit'
)
returns jsonb
language plpgsql
security definer
set search_path = public
stable
as $$
declare
    v_user uuid := auth.uid();
    v_credits int;
    v_checkins int;
    v_campaigns int;
    v_total int;
    v_rank int;
begin
    if v_user is null then
        return '{}'::jsonb;
    end if;

    -- Thống kê của chính mình.
    select p.credits,
           (select count(*) from public.checkins ci where ci.user_id = p.id),
           (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id)
    into v_credits, v_checkins, v_campaigns
    from public.profiles p
    where p.id = v_user;

    if not found then
        return '{}'::jsonb;
    end if;

    select count(*) into v_total from public.profiles;

    if coalesce(p_sort, 'credit') = 'test' then
        if v_checkins = 0 then
            -- Top Test chỉ gồm người đã thực hiện ít nhất 1 lượt test.
            return '{}'::jsonb;
        end if;

        select count(*) + 1 into v_rank
        from (
            select p.id,
                   p.credits,
                   (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                   (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
            from public.profiles p
        ) t
        where t.checkin_count > v_checkins
           or (t.checkin_count = v_checkins and t.credits > v_credits)
           or (t.checkin_count = v_checkins and t.credits = v_credits and t.campaigns_count > v_campaigns);
    else
        select count(*) + 1 into v_rank
        from (
            select p.id,
                   p.credits,
                   (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                   (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
            from public.profiles p
        ) t
        where t.credits > v_credits
           or (t.credits = v_credits and t.checkin_count > v_checkins)
           or (t.credits = v_credits and t.checkin_count = v_checkins and t.campaigns_count > v_campaigns);
    end if;

    return jsonb_build_object(
        'rank', v_rank,
        'credits', v_credits,
        'checkinCount', v_checkins,
        'campaignsCount', v_campaigns,
        'totalUsers', v_total
    );
end;
$$;

grant execute on function public.get_my_rank_v1(text) to authenticated;

notify pgrst, 'reload schema';
