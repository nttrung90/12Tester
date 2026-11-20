-- Read-only diagnostics. Run on the exact Supabase project used by the APK.
select p.oid::regprocedure as signature,
 pg_get_function_arguments(p.oid) as arguments,
 p.pronargdefaults as default_argument_count,
 p.proconfig,
 has_function_privilege('authenticated',p.oid,'execute') as authenticated_can_execute,
 has_function_privilege('anon',p.oid,'execute') as anon_can_execute
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where n.nspname='public' and p.proname in
 ('submit_tester_report_v2','reconcile_legacy_report','submit_tester_report_internal_v2')
order by p.proname;

select to_regclass('public.report_cancelled_requests') as cancellation_table;

-- Inspect the payload contract without submitting a report:
-- v2 requires BOTH p_submission_context AND p_campaign_id.
-- Existence here does not prove the HTTP payload matches or PostgREST cache is current.
