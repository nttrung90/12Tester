-- submit_tester_report computes receipt fingerprints using pgcrypto.digest().
-- Existing Supabase projects can have pgcrypto in extensions; CREATE EXTENSION
-- IF NOT EXISTS ... WITH SCHEMA public does not relocate an installed extension.
-- Preserve the function body, owner, grants and all report/credit data.
begin;

alter function public.submit_tester_report(uuid, text, text, integer, text, uuid, text, uuid)
    set search_path = public, extensions;

notify pgrst, 'reload schema';
commit;
