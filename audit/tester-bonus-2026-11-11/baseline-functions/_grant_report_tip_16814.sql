CREATE OR REPLACE FUNCTION public._grant_report_tip(p_campaign_id uuid, p_tester_id uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_owner_id uuid;
    v_tip int;
    v_app_name text;
    v_owner_balance int;
    v_tester_balance int;
begin
    select owner_id, tip_credits, app_name
      into v_owner_id, v_tip, v_app_name
    from public.campaigns
    where id = p_campaign_id;

    if v_owner_id is null
       or coalesce(v_tip, 0) <= 0
       or v_owner_id = p_tester_id then
        return 0;
    end if;

    if not exists (select 1 from public.profiles where id = p_tester_id) then
        return 0;
    end if;

    select credits into v_owner_balance
    from public.profiles
    where id = v_owner_id
    for update;

    if coalesce(v_owner_balance, 0) < v_tip then
        return 0;
    end if;

    update public.profiles
    set credits = credits - v_tip
    where id = v_owner_id
    returning credits into v_owner_balance;

    update public.profiles
    set credits = credits + v_tip
    where id = p_tester_id
    returning credits into v_tester_balance;

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        v_owner_id,
        -v_tip,
        'campaign_tip_paid',
        format('Boa cho tester ứng dụng "%s" (mỗi lượt duyệt, -%s Credit)', coalesce(v_app_name, 'của bạn'), v_tip),
        v_owner_balance
    );

    insert into public.credit_logs (user_id, amount, action, note, balance)
    values (
        p_tester_id,
        v_tip,
        'campaign_tip_received',
        format('Nhận %s Credit boa từ chủ app "%s"', v_tip, coalesce(v_app_name, '')),
        v_tester_balance
    );

    return v_tip;
end;
$function$
