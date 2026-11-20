-- Read-only diagnostics: run in the affected project's Supabase SQL editor.
select e.extname, n.nspname as extension_schema
from pg_extension e join pg_namespace n on n.oid = e.extnamespace
where e.extname = 'pgcrypto';

select p.oid::regprocedure as function_signature, p.proconfig
from pg_proc p join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public' and p.proname = 'submit_tester_report';

-- Reproduce name resolution only; this does not submit any report.
begin;
set local search_path = public;
select to_regprocedure('digest(text,text)') as digest_visible_with_old_path;
set local search_path = public, extensions;
select to_regprocedure('digest(text,text)') as digest_visible_with_fixed_path;
rollback;
