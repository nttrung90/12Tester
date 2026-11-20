CREATE OR REPLACE FUNCTION public.auto_accept_expired_reports()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_catalog'
AS $function$
declare
    v_report record;
    v_count int := 0;
    v_retried int := 0;
    v_closed_rounds int := 0;
    v_owner_id uuid;
    v_camp_name text;
    v_tip int;
    v_base int;
    v_total_bonus int;
    v_bonus_res jsonb;
    v_tester_bonus int;
begin
    perform set_config('app.suppress_report_notify', '1', true);
    perform set_config('app.reviewing_report', 'true', true);

    for v_report in
        select id, campaign_id, user_id, created_at, bonus_awarded, provisional_granted_credits, submission_context
        from public.tester_reports
        where status = 'pending'
          and created_at <= (now() - interval '8 hours')
        for update skip locked
    loop
        -- Level 3: Lock campaign row
        select owner_id, app_name into v_owner_id, v_camp_name
        from public.campaigns
        where id = v_report.campaign_id
        for update;

        v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
        -- Genuine provisional 0 does not fall back to unrelated bonus
        v_base := coalesce(
            v_report.provisional_granted_credits,
            coalesce(nullif(v_report.bonus_awarded, 0), case when coalesce(v_report.submission_context, 'app_test') = 'app_test' then 2 else 0 end)
        );
        -- Retain provisional reward only + tip; no extra manual review bonus in auto approval
        v_total_bonus := v_base + v_tip;

        update public.tester_reports
        set status = 'approved',
            bonus_awarded = v_total_bonus,
            reviewed_at = now()
        where id = v_report.id;

        insert into public.report_review_events (
            report_id, campaign_id, tester_id, owner_id, reviewer_id, event_type
        ) values (
            v_report.id, v_report.campaign_id, v_report.user_id,
            v_owner_id, null, 'auto_accept'
        );

        -- Only ordinary app_test reports touch checkins table
        if coalesce(v_report.submission_context, 'app_test') = 'app_test' then
            update public.checkins
            set note = 'Tự động duyệt sau 8h'
            where campaign_id = v_report.campaign_id
              and user_id = v_report.user_id
              and checkin_date = (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date;

            if not found then
                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (
                    v_report.campaign_id, v_report.user_id,
                    (v_report.created_at at time zone 'Asia/Ho_Chi_Minh')::date,
                    'Tự động duyệt sau 8h'
                )
                on conflict (campaign_id, user_id, checkin_date)
                do update set note = 'Tự động duyệt sau 8h';
            end if;
        end if;

        -- Settle mission reward if mission report
        perform public._settle_mission_reward(v_report.id);

        -- Settle Campaign Tester Bonus Round (if eligible snapshot attached)
        v_bonus_res := public._settle_campaign_tester_bonus(v_report.id);
        if coalesce((v_bonus_res->>'settled')::boolean, false) then
            v_tester_bonus := coalesce((v_bonus_res->>'reward_credits')::int, 0);
        else
            v_tester_bonus := 0;
        end if;

        insert into public.notifications (user_id, title, message, type, data)
        values (
            v_report.user_id,
            'Báo cáo kiểm thử tự động duyệt ⏱️',
            case
                when v_tester_bonus > 0 and v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit + %s Credit boa + %s Credit thưởng thêm đợt). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip, v_tester_bonus)
                when v_tester_bonus > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit + %s Credit thưởng thêm đợt). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base, v_tester_bonus)
                when v_tip > 0 then
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này + %s Credit boa từ chủ app). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base, v_tip)
                else
                    format('Báo cáo của bạn cho ứng dụng "%s" đã được tự động duyệt sau 8 giờ (giữ nguyên %s Credit của lượt test này). Cảm ơn bạn!',
                        coalesce(v_camp_name, 'ứng dụng'), v_base)
            end,
            'report',
            jsonb_build_object(
                'report_id', v_report.id,
                'campaign_id', v_report.campaign_id,
                'status', 'approved',
                'auto_accepted', true,
                'tester_bonus_awarded', v_tester_bonus
            )
        );

        v_count := v_count + 1;
    end loop;

    -- Retry failed mission settlement jobs
    v_retried := public.retry_failed_mission_settlements(50);

    -- Process any closing tester bonus rounds that now have 0 pending reports
    v_closed_rounds := public._process_closing_tester_bonus_rounds();

    -- Reset suppress notification flag
    perform set_config('app.suppress_report_notify', '0', true);

    return v_count;
end;
$function$;
