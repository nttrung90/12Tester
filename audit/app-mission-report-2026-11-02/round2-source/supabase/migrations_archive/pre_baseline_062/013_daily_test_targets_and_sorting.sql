-- ============================================================================
-- 12 Tester — Supabase migration 013: Daily Test Targets & Target Upgrade Costs
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Cập nhật giá trị mặc định cho target_testers thành 12
alter table public.campaigns alter column target_testers set default 12;

-- 2. Trigger tự động trừ phí khi người đăng chọn mục tiêu cao hơn (15 hoặc 20 Test/ngày)
-- Quy tắc:
--   - 12 Test/ngày: 0 Credit (Mặc định)
--   - 15 Test/ngày: 10 Credit
--   - 20 Test/ngày: 20 Credit
create or replace function public.handle_campaign_target_cost()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_cost int := 0;
    v_balance int;
begin
    -- Chuẩn hóa target_testers
    if new.target_testers = 15 then
        v_cost := 10;
    elsif new.target_testers = 20 then
        v_cost := 20;
    else
        new.target_testers := 12;
        v_cost := 0;
    end if;

    -- Nếu gói nâng cao, trừ Credit của chủ app
    if v_cost > 0 then
        select credits into v_balance
        from public.profiles
        where id = new.owner_id
        for update;

        if v_balance is null or v_balance < v_cost then
            raise exception 'INSUFFICIENT_CREDITS_FOR_TARGET: Cần ít nhất % Credit để đăng app với mục tiêu % Test/ngày', v_cost, new.target_testers;
        end if;

        -- Trừ Credit
        update public.profiles
        set credits = credits - v_cost
        where id = new.owner_id
        returning credits into v_balance;

        -- Ghi log Credit
        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            new.owner_id,
            -v_cost,
            'target_upgrade',
            format('Nâng cấp mục tiêu %s Test/ngày (-%s Credit)', new.target_testers, v_cost),
            v_balance
        );
    end if;

    return new;
end;
$$;

drop trigger if exists trg_on_campaign_target_cost on public.campaigns;
create trigger trg_on_campaign_target_cost
    before insert on public.campaigns
    for each row
    execute function public.handle_campaign_target_cost();

notify pgrst, 'reload schema';
