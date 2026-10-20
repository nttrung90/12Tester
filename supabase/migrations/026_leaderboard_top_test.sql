-- ============================================================================
-- 026_leaderboard_top_test.sql
-- Mục đích:
--   + get_leaderboard_v2 thêm tham số p_sort ('credit' | 'test')
--       - 'credit' (mặc định): giữ nguyên cơ chế cũ — top theo credits
--       - 'test'  : top theo tổng lượt test (checkins, toàn thời gian),
--                   chỉ gồm thành viên đã thực hiện ít nhất 1 lượt test
--   + Drop hàm 1 tham số cũ rồi tạo lại bản 2 tham số (Postgres không cho
--     đổi signature bằng create or replace).
-- ============================================================================

drop function if exists public.get_leaderboard_v2(int);

create or replace function public.get_leaderboard_v2(
    p_limit int default 100,
    p_sort text default 'credit'
)
returns jsonb
language plpgsql
security definer
set search_path = public
stable
as $$
declare
    v_limit int := greatest(1, least(coalesce(p_limit, 100), 200));
    v_result jsonb := '[]'::jsonb;
begin
    if coalesce(p_sort, 'credit') = 'test' then
        -- Top Test: tổng lượt test toàn thời gian, tie-break theo credits rồi số app.
        select coalesce(jsonb_agg(jsonb_build_object(
            'userId', t.id,
            'displayName', t.name,
            'avatarUrl', t.avatar_url,
            'credits', t.credits,
            'checkinCount', t.checkin_count,
            'campaignsCount', t.campaigns_count,
            'rank', t.rn
        ) order by t.rn), '[]'::jsonb)
        into v_result
        from (
            select b.*, row_number() over (order by b.checkin_count desc, b.credits desc, b.campaigns_count desc) as rn
            from (
                select x.*
                from (
                    select p.id,
                           coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Tester') as name,
                           p.avatar_url,
                           p.credits,
                           (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                           (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
                    from public.profiles p
                ) x
                where x.checkin_count > 0
                order by x.checkin_count desc, x.credits desc, x.campaigns_count desc
                limit v_limit
            ) b
        ) t;
    else
        -- Top Credit: giữ nguyên thuật toán cũ.
        select coalesce(jsonb_agg(jsonb_build_object(
            'userId', t.id,
            'displayName', t.name,
            'avatarUrl', t.avatar_url,
            'credits', t.credits,
            'checkinCount', t.checkin_count,
            'campaignsCount', t.campaigns_count,
            'rank', t.rn
        ) order by t.rn), '[]'::jsonb)
        into v_result
        from (
            select b.*, row_number() over (order by b.credits desc, b.checkin_count desc, b.campaigns_count desc) as rn
            from (
                select p.id,
                       coalesce(nullif(p.display_name, ''), split_part(coalesce(p.email, ''), '@', 1), 'Tester') as name,
                       p.avatar_url,
                       p.credits,
                       (select count(*) from public.checkins ci where ci.user_id = p.id) as checkin_count,
                       (select count(distinct ci.campaign_id) from public.checkins ci where ci.user_id = p.id) as campaigns_count
                from public.profiles p
                order by p.credits desc
                limit v_limit
            ) b
        ) t;
    end if;

    return v_result;
end;
$$;

grant execute on function public.get_leaderboard_v2(int, text) to authenticated, anon, service_role;

notify pgrst, 'reload schema';
