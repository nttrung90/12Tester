CREATE OR REPLACE FUNCTION public.trg_campaigns_guard_financial_snapshots()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
begin
    if current_user not in ('authenticated', 'anon', 'public') then
        perform set_config('app.campaign_client_direct_write', 'false', true);
        return new;
    end if;

    perform set_config('app.campaign_client_direct_write', 'true', true);

    if tg_op = 'INSERT' then
        if coalesce(new.slot_1_fee_paid, 0) <> 0
           or coalesce(new.target_fee_paid, 0) <> 0
           or new.creation_policy_id is not null
           or coalesce(new.status, 'open') <> 'open'
           or coalesce(new.tip_credits, 0) <> 0
           or coalesce(new.is_boosted, false) <> false
        then
            raise exception 'PERMISSION_DENIED';
        end if;
        return new;
    end if;

    if new.slot_1_fee_paid is distinct from old.slot_1_fee_paid
       or new.target_fee_paid is distinct from old.target_fee_paid
       or new.creation_policy_id is distinct from old.creation_policy_id
    then
        raise exception 'PERMISSION_DENIED: FINANCIAL_SNAPSHOT_IMMUTABLE';
    end if;

    if new.slot_number is distinct from old.slot_number then
        raise exception 'PERMISSION_DENIED: SLOT_NUMBER_IMMUTABLE';
    end if;

    if new.target_testers is distinct from old.target_testers then
        raise exception 'PERMISSION_DENIED: TARGET_TESTERS_IMMUTABLE';
    end if;

    if new.package_name is distinct from old.package_name then
        raise exception 'PERMISSION_DENIED: PACKAGE_NAME_IMMUTABLE';
    end if;

    if new.owner_id is distinct from old.owner_id then
        raise exception 'PERMISSION_DENIED: OWNER_ID_IMMUTABLE';
    end if;

    if new.start_date is distinct from old.start_date
       or new.target_days is distinct from old.target_days
       or new.auto_accept_hours is distinct from old.auto_accept_hours
       or new.is_boosted is distinct from old.is_boosted
       or new.tip_credits is distinct from old.tip_credits
    then
        raise exception 'PERMISSION_DENIED: CAMPAIGN_CORE_CONFIG_IMMUTABLE';
    end if;

    return new;
end;
$function$
