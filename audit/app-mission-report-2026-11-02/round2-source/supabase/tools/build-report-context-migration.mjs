import {readFileSync,writeFileSync} from 'node:fs';
const baseline=readFileSync(new URL('../migrations/062_consolidated_baseline.sql',import.meta.url),'utf8');
let body=baseline.slice(baseline.indexOf('CREATE OR REPLACE FUNCTION public.submit_tester_report('));
body=body.slice(0,body.indexOf('$function$;',body.indexOf('AS $function$'))+12);
body=body.replace('public.submit_tester_report(', 'public.submit_tester_report_internal_v2(').replace("SET search_path TO 'public'", "SET search_path TO 'public', 'extensions'");
const start=body.indexOf('            select count(*) into v_eligible_count');
const end=body.indexOf('\n        end if;',start);
body=body.slice(0,start)+"            v_submission_context := 'app_test';"+body.slice(end);
body=body.replace('    -- Tính payload fingerprint', `    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    if exists(select 1 from public.report_cancelled_requests where user_id=v_user_id and client_submit_id=p_client_submit_id) then
        raise exception 'REPORT_REQUEST_REQUIRES_REVIEW';
    end if;
    -- Tính payload fingerprint`);
body=body.replace('    -- TH1:', `    if v_existing_report.id is not null and (
        v_existing_report.submission_context is distinct from case when p_mission_id is null then 'app_test' else 'mission' end
        or v_existing_report.mission_id is distinct from p_mission_id
    ) then raise exception 'REPORT_CONTEXT_CONFLICT'; end if;
    -- TH1:`);
body=body.replace("if v_mission.status <> 'open' then\n                raise exception 'MISSION_NOT_OPEN';", `if v_mission.status not in ('open','full') then
                raise exception '%', case v_mission.status
                    when 'paused_by_creator' then 'MISSION_PAUSED'
                    when 'stopped_no_credits' then 'MISSION_STOPPED_NO_CREDITS'
                    when 'expired' then 'MISSION_EXPIRED'
                    when 'deleted' then 'MISSION_DELETED'
                    else 'MISSION_NOT_OPEN' end;`);
const args='uuid,text,text,integer,text,uuid,text,uuid';
const params="p_campaign_id uuid,p_content text default '',p_screenshot_url text default null,p_rating integer default 5,p_image_hash text default null,p_client_submit_id uuid default null,p_thumbnail_url text default null,p_mission_id uuid default null";
const call='p_campaign_id,p_content,p_screenshot_url,p_rating,p_image_hash,p_client_submit_id,p_thumbnail_url,p_mission_id';
const sql=`-- Explicit report context. Immutable receipts and existing reward rules are preserved.
begin;
create table if not exists public.report_cancelled_requests(user_id uuid not null references auth.users(id) on delete cascade,client_submit_id uuid not null,created_at timestamptz not null default now(),primary key(user_id,client_submit_id));
alter table public.report_cancelled_requests enable row level security;
revoke all on public.report_cancelled_requests from public,anon,authenticated;
${body}
revoke all on function public.submit_tester_report_internal_v2(${args}) from public,anon,authenticated;
create or replace function public.submit_tester_report_v2(p_submission_context text,${params}) returns jsonb
language plpgsql security definer set search_path=public,extensions as $$
begin
 if auth.uid() is null then raise exception 'NOT_AUTHENTICATED'; end if;
 if p_client_submit_id is null or p_submission_context is null or p_submission_context not in ('app_test','mission')
 or (p_submission_context='mission') <> (p_mission_id is not null) then raise exception 'INVALID_REPORT_CONTEXT'; end if;
 perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text || ':' || p_client_submit_id::text,64));
 if exists(select 1 from public.report_submission_receipts r
   where r.user_id=auth.uid() and r.client_submit_id=p_client_submit_id
   and (coalesce(r.response_json->>'submission_context',case when r.response_json->>'mission_id' is null then 'app_test' else 'mission' end) <> p_submission_context
       or (r.response_json->>'mission_id') is distinct from p_mission_id::text))
 then raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH'; end if;
 -- context is unambiguously encoded in the existing fingerprint by the required mission ID/null invariant.
 return public.submit_tester_report_internal_v2(${call});
end $$;
revoke all on function public.submit_tester_report_v2(text,${args}) from public,anon;
grant execute on function public.submit_tester_report_v2(text,${args}) to authenticated;
create or replace function public.submit_tester_report(${params}) returns jsonb
language plpgsql security definer set search_path=public,extensions as $$
begin
 if auth.uid() is null then raise exception 'NOT_AUTHENTICATED'; end if;
 perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text || ':' || p_client_submit_id::text,64));
 if p_mission_id is not null and not exists(select 1 from public.report_submission_receipts where user_id=auth.uid() and client_submit_id=p_client_submit_id)
 and not exists(select 1 from public.tester_reports where user_id=auth.uid() and client_submit_id=p_client_submit_id)
 then raise exception 'REPORT_CLIENT_UPDATE_REQUIRED'; end if;
 return public.submit_tester_report_internal_v2(${call});
end $$;
create or replace function public.reconcile_legacy_report(p_client_submit_id uuid) returns jsonb
language plpgsql security definer set search_path=public,extensions as $$
declare receipt jsonb;
begin
 if auth.uid() is null then raise exception 'NOT_AUTHENTICATED'; end if;
 if p_client_submit_id is null then raise exception 'INVALID_REPORT_CONTEXT'; end if;
 perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text || ':' || p_client_submit_id::text,64));
 select response_json into receipt from public.report_submission_receipts where user_id=auth.uid() and client_submit_id=p_client_submit_id;
 if receipt is not null then return receipt; end if;
 if exists(select 1 from public.tester_reports where user_id=auth.uid() and client_submit_id=p_client_submit_id) then
   return jsonb_build_object('success',false,'status','legacy_committed_requires_review');
 end if;
 insert into public.report_cancelled_requests(user_id,client_submit_id) values(auth.uid(),p_client_submit_id) on conflict do nothing;
 return jsonb_build_object('success',false,'status','legacy_cancelled_requires_review');
end $$;
revoke all on function public.reconcile_legacy_report(uuid) from public,anon;
grant execute on function public.reconcile_legacy_report(uuid) to authenticated;
notify pgrst,'reload schema';
commit;
`;
// This generator is used only to author 064. The checked-in SQL is the deployable artifact.
writeFileSync(new URL('../migrations/064_explicit_report_context.sql',import.meta.url),sql);
