CREATE OR REPLACE FUNCTION public._impl_approve_tester_report(p_report_id uuid, p_bonus integer DEFAULT 0)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    v_caller_id uuid := auth.uid();
    v_caller_role text;
    v_caller_locked boolean;
    v_report record;
    v_camp record;
    v_extra_reward int := 2;
    v_tip int := 0;
    v_total_bonus int;
    v_new_balance int;
    v_owner_bonus int := 0;
    v_owner_balance int;
    v_is_owner boolean := false;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
begin
    perform set_config('app.reviewing_report', 'true', true);

    if v_caller_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    select role, coalesce(is_locked, false)
    into v_caller_role, v_caller_locked
    from public.profiles
    where id = v_caller_id;

    if coalesce(v_caller_locked, false) = true then
        raise exception 'ACCOUNT_LOCKED: Tài khoản của bạn đã bị khóa';
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

    if v_camp.owner_id <> v_caller_id
       and lower(coalesce(v_caller_role, '')) not in ('admin', 'moderator') then
        raise exception 'PERMISSION_DENIED';
    end if;

    if v_report.created_at <= (now() - interval '8 hours') then
        raise exception 'REPORT_EXPIRED';
    end if;

    v_is_owner := (v_camp.owner_id = v_caller_id);

    insert into public.report_review_events (
        report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
    ) values (
        v_report.id, v_report.campaign_id, v_report.user_id,
        v_camp.owner_id, v_caller_id, 'approve'
    );

    -- Preserve 069 semantics: p_bonus is ignored to prevent arbitrary caller credit minting
    v_extra_reward := coalesce(v_report.review_tester_bonus, 2);
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
    v_total_bonus := coalesce(v_report.bonus_awarded, coalesce(v_report.provisional_granted_credits, 2)) + v_extra_reward + v_tip;

    update public.tester_reports
    set status = 'approved',
        bonus_awarded = v_total_bonus,
        reviewed_at = now()
    where id = p_report_id;

    if v_extra_reward > 0 then
        update public.profiles
        set credits = credits + v_extra_reward,
            last_active_date = v_today,
            missed_streak = 0
        where id = v_report.user_id
        returning credits into v_new_balance;

        insert into public.credit_logs (user_id, amount, action, note, balance)
        values (
            v_report.user_id,
            v_extra_reward,
            'test_approved_bonus',
            format(
                '%s duyệt kết quả kiểm thử (Thưởng thêm +%s Credit)',
                case when v_is_owner then 'Chủ app' else 'Admin/Moderator' end,
                v_extra_reward
            ),
            v_new_balance
        );
    else
        select credits into v_new_balance from public.profiles where id = v_report.user_id;
    end if;

    if v_is_owner then
        v_owner_bonus := coalesce(v_report.review_owner_bonus, 2);
        if v_owner_bonus > 0 then
            update public.profiles
            set credits = credits + v_owner_bonus
            where id = v_camp.owner_id
            returning credits into v_owner_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_camp.owner_id,
                v_owner_bonus,
                'owner_approval_bonus',
                format('Duyệt báo cáo kiểm thử (+%s Credit)', v_owner_bonus),
                v_owner_balance
            );
        else
            select credits into v_owner_balance from public.profiles where id = v_camp.owner_id;
        end if;
    else
        v_owner_bonus := 0;
        select credits into v_owner_balance from public.profiles where id = v_camp.owner_id;
    end if;

    perform public._settle_mission_reward(v_report.id);

    -- Only ordinary app_test reports touch checkins table
    if coalesce(v_report.submission_context, 'app_test') = 'app_test' then
        update public.checkins
        set note = 'Đã kiểm thử và được duyệt'
        where campaign_id = v_report.campaign_id
          and user_id = v_report.user_id
          and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

        if not found then
            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (v_report.campaign_id, v_report.user_id, v_today, 'Đã kiểm thử và được duyệt')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã kiểm thử và được duyệt';
        end if;
    end if;

    perform set_config('app.reviewing_report', 'false', true);

    return jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'tester_id', v_report.user_id,
        'status', 'approved',
        'credits_awarded', v_extra_reward,
        'tip_awarded', v_tip,
        'owner_credits_awarded', v_owner_bonus,
        'total_earned', v_total_bonus,
        'tester_balance', v_new_balance,
        'owner_balance', v_owner_balance,
        'message', format(
            'Duyệt kết quả thành công (Thưởng thêm +%s Credit cho tester%s)',
            v_extra_reward,
            case when v_owner_bonus > 0 then format(' và +%s Credit cho bạn', v_owner_bonus) else '' end
        )
    );
end;
$function$
