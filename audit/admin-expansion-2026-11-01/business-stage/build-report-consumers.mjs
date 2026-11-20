import { readFileSync, appendFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const root = new URL('../../../', import.meta.url);
const read = p => readFileSync(new URL(p, root), 'utf8').replaceAll('\r\n', '\n');

console.log('Building concrete SQL for Report Consumers...');

// 1. Read sources
const sql062 = read('supabase/migrations/062_consolidated_baseline.sql');
const sql065 = read('supabase/migrations/065_remove_dhash_hardblock.sql');
const sql069 = read('supabase/migrations/069_admin_audit_hardening.sql');
const sql070 = read('supabase/migrations/070_admin_management.sql');

// ============================================================================
// PART A: Table alteration & metadata guard
// ============================================================================
let partA = `
-- ============================================================================
-- 9. REPORT FINANCIAL METADATA & SNAPSHOT COLUMNS
-- ============================================================================

ALTER TABLE public.tester_reports
    ADD COLUMN IF NOT EXISTS review_tester_bonus integer,
    ADD COLUMN IF NOT EXISTS review_owner_bonus integer,
    ADD COLUMN IF NOT EXISTS business_policy_id bigint REFERENCES public.business_policies(id);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_review_tester_bonus,
    ADD CONSTRAINT chk_tester_reports_review_tester_bonus CHECK (review_tester_bonus IS NULL OR review_tester_bonus >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_review_owner_bonus,
    ADD CONSTRAINT chk_tester_reports_review_owner_bonus CHECK (review_owner_bonus IS NULL OR review_owner_bonus >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_provisional_granted_credits,
    ADD CONSTRAINT chk_tester_reports_provisional_granted_credits CHECK (provisional_granted_credits >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_provisional_clawed,
    ADD CONSTRAINT chk_tester_reports_provisional_clawed CHECK (provisional_clawed >= 0);

ALTER TABLE public.tester_reports
    DROP CONSTRAINT IF EXISTS chk_tester_reports_bonus_awarded,
    ADD CONSTRAINT chk_tester_reports_bonus_awarded CHECK (bonus_awarded >= 0);

CREATE OR REPLACE FUNCTION public.guard_tester_report_financial_metadata()
RETURNS trigger
LANGUAGE plpgsql
AS $$
begin
    -- Security invoker: discriminates current_user 'authenticated'/'anon' for direct client DML
    -- vs trusted SECURITY DEFINER RPCs where current_user is the function owner (postgres).
    if current_user in ('anon', 'authenticated') then
        if tg_op = 'INSERT' then
            if new.review_tester_bonus is not null or
               new.review_owner_bonus is not null or
               new.business_policy_id is not null or
               coalesce(new.provisional_granted_credits, 0) <> 0 or
               coalesce(new.provisional_clawed, 0) <> 0 or
               coalesce(new.bonus_awarded, 0) <> 0 then
                raise exception 'FORBIDDEN_REPORT_METADATA_WRITE: Chi duoc tao bao cao qua RPC nop bao cao';
            end if;
        elsif tg_op = 'UPDATE' then
            if new.review_tester_bonus is distinct from old.review_tester_bonus or
               new.review_owner_bonus is distinct from old.review_owner_bonus or
               new.business_policy_id is distinct from old.business_policy_id or
               new.provisional_granted_credits is distinct from old.provisional_granted_credits or
               new.provisional_clawed is distinct from old.provisional_clawed or
               new.bonus_awarded is distinct from old.bonus_awarded then
                raise exception 'FORBIDDEN_REPORT_METADATA_UPDATE: Khong duoc phep sua doi thong so tai chinh cua bao cao';
            end if;
        end if;
    end if;
    return new;
end;
$$;

DROP TRIGGER IF EXISTS trg_guard_tester_report_financial_metadata ON public.tester_reports;
CREATE TRIGGER trg_guard_tester_report_financial_metadata
    BEFORE INSERT OR UPDATE ON public.tester_reports
    FOR EACH ROW EXECUTE FUNCTION public.guard_tester_report_financial_metadata();
`;

// ============================================================================
// PART B: submit_tester_report_internal_v2 from 065
// ============================================================================
const submitMarkerStart = 'CREATE OR REPLACE FUNCTION public.submit_tester_report_internal_v2(';
const submitMarkerEnd = 'revoke all on function public.submit_tester_report_internal_v2';
const submitStartIdx = sql065.indexOf(submitMarkerStart);
const submitEndIdx = sql065.indexOf(submitMarkerEnd);
assert.ok(submitStartIdx !== -1 && submitEndIdx !== -1, 'Could not locate submit_tester_report_internal_v2 in 065');

let submitFn = sql065.slice(submitStartIdx, submitEndIdx).trim();

// Assertions on 065
assert.ok(submitFn.includes('v_provisional_reward int := 10;'));
assert.ok(submitFn.includes('if v_already_checked_in then'));

// Transformation 1: add v_policy declaration and fetch
submitFn = submitFn.replace(
  'v_provisional_reward int := 10;',
  'v_provisional_reward int := 10;\n    v_policy public.business_policies;'
);

assert.ok(submitFn.includes('begin\n    if v_user_id is null then'), 'begin if v_user_id is null not found in submitFn');
submitFn = submitFn.replace(
  'begin\n    if v_user_id is null then',
  `begin
    v_policy := public._get_effective_business_policy_row(v_today);
    v_provisional_reward := v_policy.report_provisional_reward;

    if v_user_id is null then`
);

// Transformation 2: when already checked in, insert snapshot columns
const oldInsertCheckedIn = `insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', 0, 0, 0,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end
            )`;

const newInsertCheckedIn = `insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                review_tester_bonus, review_owner_bonus, business_policy_id
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', 0, 0, 0,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id
            )`;

assert.ok(submitFn.includes(oldInsertCheckedIn), 'oldInsertCheckedIn not found in submitFn');
submitFn = submitFn.replace(oldInsertCheckedIn, newInsertCheckedIn);

// Transformation 3: when not checked in, handle provisional_reward = 0 and insert snapshot columns
const oldNotCheckedInBlock = `        else
            update public.profiles
            set credits = credits + v_provisional_reward,
                last_active_date = v_today,
                missed_streak = 0
            where id = v_user_id
            returning credits into v_new_balance;

            insert into public.credit_logs (user_id, amount, action, note, balance)
            values (
                v_user_id, v_provisional_reward, 'test_provisional',
                format('Tạm nhận %s Credit sau khi nộp kết quả test (chờ chủ app duyệt)', v_provisional_reward),
                v_new_balance
            );

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', v_provisional_reward, 0, v_provisional_reward,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := true;
            v_provisional_amount := v_provisional_reward;
        end if;`;

const newNotCheckedInBlock = `        else
            if v_provisional_reward > 0 then
                update public.profiles
                set credits = credits + v_provisional_reward,
                    last_active_date = v_today,
                    missed_streak = 0
                where id = v_user_id
                returning credits into v_new_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_user_id, v_provisional_reward, 'test_provisional',
                    format('Tạm nhận %s Credit sau khi nộp kết quả test (chờ chủ app duyệt)', v_provisional_reward),
                    v_new_balance
                );

                v_provisional_granted := true;
                v_provisional_amount := v_provisional_reward;
            else
                v_provisional_granted := false;
                v_provisional_amount := 0;
            end if;

            insert into public.checkins (campaign_id, user_id, checkin_date, note)
            values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
            on conflict (campaign_id, user_id, checkin_date)
            do update set note = 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)';

            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                review_tester_bonus, review_owner_bonus, business_policy_id
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                p_screenshot_url, p_thumbnail_url, v_rating, 'pending', v_provisional_amount, 0, v_provisional_amount,
                lower(p_image_hash), p_client_submit_id, now(),
                v_submission_context, v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at,
                case when v_submission_context = 'mission' then now() else null end,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id
            )
            returning id into v_report_id;

            v_status := 'pending';
        end if;`;

assert.ok(submitFn.includes(oldNotCheckedInBlock), 'oldNotCheckedInBlock not found in submitFn');
submitFn = submitFn.replace(oldNotCheckedInBlock, newNotCheckedInBlock);

// ============================================================================
// PART C: approve_tester_report from 069
// ============================================================================
const approveMarkerStart = 'CREATE OR REPLACE FUNCTION public.approve_tester_report(';
const approveMarkerEnd = 'CREATE OR REPLACE FUNCTION public.reject_tester_report(';
const appStartIdx = sql069.indexOf(approveMarkerStart);
const appEndIdx = sql069.indexOf(approveMarkerEnd);
assert.ok(appStartIdx !== -1 && appEndIdx !== -1, 'Could not locate approve_tester_report in 069');

let approveFn = sql069.slice(appStartIdx, appEndIdx).trim();

// Transform approve_tester_report to read snapshot bonuses
assert.ok(approveFn.includes('v_extra_reward int := 2;'));
assert.ok(approveFn.includes('v_owner_bonus := 2;'));

approveFn = approveFn.replace(
  'begin\n    if v_caller_id is null then',
  `begin
    perform set_config('app.reviewing_report', 'true', true);

    if v_caller_id is null then`
);

approveFn = approveFn.replace(
  `    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
    v_total_bonus := coalesce(v_report.bonus_awarded, 2) + v_extra_reward + v_tip;`,
  `    -- Preserve 069 semantics: p_bonus is ignored to prevent arbitrary caller credit minting
    v_extra_reward := coalesce(v_report.review_tester_bonus, 2);
    v_tip := public._grant_report_tip(v_report.campaign_id, v_report.user_id);
    v_total_bonus := coalesce(v_report.bonus_awarded, coalesce(v_report.provisional_granted_credits, 2)) + v_extra_reward + v_tip;`
);

approveFn = approveFn.replace(
  `    if v_is_owner then
        v_owner_bonus := 2;
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
    end if;`,
  `    if v_is_owner then
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
    end if;`
);

approveFn = approveFn.replace(
  `    update public.profiles
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
    );`,
  `    if v_extra_reward > 0 then
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
    end if;`
);

approveFn = approveFn.replace(
  '    return jsonb_build_object(\n        \'success\', true,',
  `    perform set_config('app.reviewing_report', 'false', true);

    return jsonb_build_object(
        'success', true,`
);

// ============================================================================
// PART D: reject_tester_report from 070 (authoritative with warning revocations)
// ============================================================================
const rejectMarkerStart = 'CREATE OR REPLACE FUNCTION public.reject_tester_report(';
const rejectMarkerEnd = 'REVOKE ALL ON FUNCTION public.reject_tester_report(uuid, text, text)';
const rejStartIdx = sql070.indexOf(rejectMarkerStart);
const rejEndIdx = sql070.indexOf(rejectMarkerEnd);
assert.ok(rejStartIdx !== -1 && rejEndIdx !== -1, 'Could not locate reject_tester_report in 070');

let rejectFn = sql070.slice(rejStartIdx, rejEndIdx).trim();

// Transform reject_tester_report to dynamically bound clawback
assert.ok(rejectFn.includes('greatest(0, least(10, coalesce(v_report.provisional_granted_credits, 2)))'));
assert.ok(rejectFn.includes('user_warning_revocations'));

rejectFn = rejectFn.replace(
  'begin\n    if v_caller_id is null then',
  `begin
    perform set_config('app.reviewing_report', 'true', true);

    if v_caller_id is null then`
);

rejectFn = rejectFn.replace(
  `    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, least(10, coalesce(v_report.provisional_granted_credits, 2)))
    );`,
  `    v_deduct := least(
        coalesce(v_tester_credits, 0),
        greatest(0, coalesce(v_report.provisional_granted_credits, 2))
    );`
);

// Graceful check for user_warning_revocations table presence if running on 069
rejectFn = rejectFn.replaceAll(
  'and not exists (\n                  select 1 from public.user_warning_revocations r\n                  where r.warning_id = w.id\n              )',
  `and (
                  to_regclass('public.user_warning_revocations') is null
                  or not exists (
                      select 1 from public.user_warning_revocations r
                      where r.warning_id = w.id
                  )
              )`
);

rejectFn = rejectFn.replace(
  '    return jsonb_build_object(\n        \'success\', true,',
  `    perform set_config('app.reviewing_report', 'false', true);

    return jsonb_build_object(
        'success', true,`
);

// ============================================================================
// PART E: auto_accept_expired_reports from 062
// ============================================================================
const autoMarkerStart = 'CREATE OR REPLACE FUNCTION public.auto_accept_expired_reports()';
const autoMarkerEnd = 'CREATE OR REPLACE FUNCTION public.cancel_daily_task_policy(';
const autoStartIdx = sql062.indexOf(autoMarkerStart);
const autoEndIdx = sql062.indexOf(autoMarkerEnd);
assert.ok(autoStartIdx !== -1 && autoEndIdx !== -1, 'Could not locate auto_accept_expired_reports in 062');

let autoFn = sql062.slice(autoStartIdx, autoEndIdx).trim();

assert.ok(autoFn.includes('v_total_bonus := coalesce(nullif(v_report.bonus_awarded, 0), v_base) + v_tip;'));

autoFn = autoFn.replace(
  "begin\n    perform set_config('app.suppress_report_notify', '1', true);",
  `begin
    perform set_config('app.suppress_report_notify', '1', true);
    perform set_config('app.reviewing_report', 'true', true);`
);

autoFn = autoFn.replace(
  `        v_base := coalesce(
            nullif(v_report.provisional_granted_credits, 0),
            nullif(v_report.bonus_awarded, 0),
            0
        );`,
  `        -- Genuine provisional 0 does not fall back to unrelated bonus
        v_base := coalesce(
            v_report.provisional_granted_credits,
            coalesce(nullif(v_report.bonus_awarded, 0), 2)
        );`
);

autoFn = autoFn.replace(
  'v_total_bonus := coalesce(nullif(v_report.bonus_awarded, 0), v_base) + v_tip;',
  '-- Retain provisional reward only + tip; no extra manual review bonus in auto approval\n        v_total_bonus := v_base + v_tip;'
);

autoFn = autoFn.replace(
  '    return v_count;',
  `    perform set_config('app.reviewing_report', 'false', true);
    return v_count;`
);

// ============================================================================
// PART F: Projections get_campaign_reports_for_owner (062) & get_campaign_detail (070)
// ============================================================================
const ownerMarkerStart = 'CREATE OR REPLACE FUNCTION public.get_campaign_reports_for_owner(p_campaign_id uuid)';
const ownerMarkerEnd = 'CREATE OR REPLACE FUNCTION public.get_campaign_update_history(p_campaign_id uuid)';
const ownerStartIdx = sql062.indexOf(ownerMarkerStart);
const ownerEndIdx = sql062.indexOf(ownerMarkerEnd);
assert.ok(ownerStartIdx !== -1 && ownerEndIdx !== -1, 'Could not locate get_campaign_reports_for_owner in 062');

let ownerReportsFn = sql062.slice(ownerStartIdx, ownerEndIdx).trim();

ownerReportsFn = ownerReportsFn.replace(
  `'image_hash', tr.image_hash,`,
  `'image_hash', tr.image_hash,
                'review_tester_bonus', tr.review_tester_bonus,
                'review_owner_bonus', tr.review_owner_bonus,
                'business_policy_id', tr.business_policy_id,
                'provisional_granted_credits', tr.provisional_granted_credits,
                'provisional_clawed', tr.provisional_clawed,`
);

const detailMarkerStart = 'CREATE OR REPLACE FUNCTION public.get_campaign_detail(p_campaign_id uuid)';
const detailMarkerEnd = 'REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;';
const detStartIdx = sql070.indexOf(detailMarkerStart);
const detEndIdx = sql070.indexOf(detailMarkerEnd);
assert.ok(detStartIdx !== -1 && detEndIdx !== -1, 'Could not locate get_campaign_detail in 070');

let detailFn = sql070.slice(detStartIdx, detEndIdx).trim();

detailFn = detailFn.replace(
  `'image_hash', tr.image_hash,`,
  `'image_hash', tr.image_hash,
        'review_tester_bonus', tr.review_tester_bonus,
        'review_owner_bonus', tr.review_owner_bonus,
        'business_policy_id', tr.business_policy_id,
        'provisional_granted_credits', tr.provisional_granted_credits,
        'provisional_clawed', tr.provisional_clawed,`
);

// ============================================================================
// PART G: admin_get_tester_reports_queue override from 070 with snapshot columns
// ============================================================================
const queueMarkerStart = 'CREATE OR REPLACE FUNCTION public.admin_get_tester_reports_queue(';
const queueMarkerEnd = 'REVOKE ALL ON FUNCTION public.admin_get_tester_reports_queue(';
const queueStartIdx = sql070.indexOf(queueMarkerStart);
const queueEndIdx = sql070.indexOf(queueMarkerEnd);
assert.ok(queueStartIdx !== -1 && queueEndIdx !== -1, 'Could not locate admin_get_tester_reports_queue in 070');

let queueFn = sql070.slice(queueStartIdx, queueEndIdx).trim();

queueFn = queueFn.replace(
  `tr.rejection_reason\n        from public.tester_reports tr`,
  `tr.rejection_reason,
            tr.review_tester_bonus,
            tr.review_owner_bonus,
            tr.business_policy_id,
            tr.provisional_granted_credits,
            tr.provisional_clawed,
            tr.bonus_awarded
        from public.tester_reports tr`
);

queueFn = queueFn.replace(
  'v_caller_id := public.assert_unlocked_admin();',
  `if exists (select 1 from pg_proc where proname = 'assert_unlocked_admin' and pronamespace = 'public'::regnamespace) then
        v_caller_id := public.assert_unlocked_admin();
    else
        v_caller_id := public._assert_daily_task_admin();
    end if;`
);

// Grants
const grantsSQL = `
-- Grants for report consumers
REVOKE ALL ON FUNCTION public.submit_tester_report_internal_v2(uuid, text, text, integer, text, uuid, text, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.submit_tester_report_internal_v2(uuid, text, text, integer, text, uuid, text, uuid) TO service_role;

REVOKE ALL ON FUNCTION public.approve_tester_report(uuid, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_tester_report(uuid, integer) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.reject_tester_report(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reject_tester_report(uuid, text, text) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.auto_accept_expired_reports() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.auto_accept_expired_reports() TO service_role;

REVOKE ALL ON FUNCTION public.get_campaign_reports_for_owner(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_reports_for_owner(uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail(uuid) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) TO authenticated, service_role;
`;

import { writeFileSync } from 'node:fs';

const stagedPath = new URL('audit/admin-expansion-2026-11-01/business-stage/071_business_policies.sql', root);
let current071 = readFileSync(stagedPath, 'utf8').replaceAll('\r\n', '\n');
const sec9Marker = '-- ============================================================================\n-- 9. REPORT FINANCIAL METADATA & SNAPSHOT COLUMNS';
const sec9Idx = current071.indexOf(sec9Marker);
if (sec9Idx !== -1) {
  current071 = current071.slice(0, sec9Idx).trimEnd() + '\n';
}

const fullSQL = [
  current071,
  partA,
  submitFn + ';\n',
  approveFn + ';\n',
  rejectFn + ';\n',
  autoFn + ';\n',
  ownerReportsFn + ';\n',
  detailFn + ';\n',
  queueFn + ';\n',
  grantsSQL
].join('\n');

writeFileSync(stagedPath, fullSQL, 'utf8');
console.log('Appended all report consumers to staged 071_business_policies.sql successfully!');
