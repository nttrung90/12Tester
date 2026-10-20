-- ============================================================================
-- 12 Tester — Supabase migration 022: Credit integrity fix
-- - RPC `apply_report_credit`: cộng/trừ Credit NGUYÊN TỬ cho tester của một báo
--   cáo (khoá dòng profile bằng FOR UPDATE + ghi credit_logs trong cùng
--   transaction). Client dùng ở đường fallback khi RPC duyệt/từ chối báo cáo
--   chưa tồn tại, thay cho kiểu đọc-rồi-ghi dễ mất cập nhật khi chạy song song.
-- - Quyền: chỉ chủ app của campaign chứa báo cáo (hoặc admin/moderator) được gọi.
-- Chạy trong Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

create or replace function public.apply_report_credit(
    p_report_id uuid,
    p_amount int,
    p_action text default '',
    p_note text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_caller uuid := auth.uid();
    v_role text;
    v_target uuid;
    v_owner uuid;
    v_current int;
    v_new int;
begin
    if v_caller is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select tr.user_id, c.owner_id
      into v_target, v_owner
    from public.tester_reports tr
    join public.campaigns c on c.id = tr.campaign_id
    where tr.id = p_report_id;

    if v_target is null then
        raise exception 'REPORT_NOT_FOUND';
    end if;

    select role into v_role
    from public.profiles
    where id = v_caller;

    if v_owner is distinct from v_caller
       and lower(coalesce(v_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    -- Khoá dòng profile mục tiêu: hai lượt cộng/trừ song song không mất cập nhật.
    select credits into v_current
    from public.profiles
    where id = v_target
    for update;

    if v_current is null then
        raise exception 'USER_NOT_FOUND';
    end if;

    v_new := greatest(v_current + p_amount, 0);

    update public.profiles
    set credits = v_new
    where id = v_target;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_target,
        p_amount,
        coalesce(nullif(trim(p_action), ''), 'report_adjust'),
        p_note,
        v_new
    );

    return jsonb_build_object(
        'success', true,
        'user_id', v_target,
        'previous_credits', v_current,
        'new_credits', v_new,
        'amount', p_amount
    );
end;
$$;

grant execute on function public.apply_report_credit(uuid, int, text, text) to authenticated, service_role;

notify pgrst, 'reload schema';
