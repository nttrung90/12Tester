REVOKE ALL ON FUNCTION public._submit_tester_report_business_092(text,uuid,text,integer,uuid,text,text,text,uuid,jsonb,date) FROM PUBLIC,anon,authenticated,service_role;

CREATE OR REPLACE FUNCTION public._submit_tester_report_core(p_submission_context text,p_campaign_id uuid,p_content text,p_rating integer,p_client_submit_id uuid,p_screenshot_url text,p_image_hash text,p_thumbnail_url text,p_mission_id uuid,p_attachments jsonb DEFAULT NULL,p_expected_date date DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,extensions,pg_catalog AS $function$
DECLARE result jsonb;rid uuid;uid uuid:=auth.uid();a record;data jsonb;ref jsonb;had_receipt boolean;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'NOT_AUTHENTICATED';END IF;
 -- Same lock order as allocator, reconcile, cleanup and acknowledgement.
 PERFORM 1 FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
 SELECT EXISTS(SELECT 1 FROM public.report_submission_receipts WHERE user_id=uid AND client_submit_id=p_client_submit_id) INTO had_receipt;
 result:=public._submit_tester_report_business_092(p_submission_context,p_campaign_id,p_content,p_rating,p_client_submit_id,p_screenshot_url,p_image_hash,p_thumbnail_url,p_mission_id,p_attachments,p_expected_date);
 -- Business core verified fingerprint and returns its durable receipt unchanged.
 -- Receipt retrieval must still work after media has expired/deleted.
 IF had_receipt THEN RETURN result;END IF;
 IF NOT coalesce((result->>'success')::boolean,false) THEN RETURN result;END IF;
 rid:=(result->>'report_id')::uuid;
 FOR a IN SELECT * FROM public.tester_report_attachments WHERE report_id=rid ORDER BY position,id LOOP
  ref:=public._report_storage_ref(a.screenshot_url);
  IF ref IS NULL THEN RAISE EXCEPTION 'INVALID_SCREENSHOT_URL';END IF;
  IF ref->>'provider'='supabase' THEN
   data:=public._prepare_report_media(a.screenshot_url,a.thumbnail_url,p_campaign_id,uid,rid);
   UPDATE public.tester_report_attachments SET storage_provider='supabase',
    created_at=(data->>'created_at')::timestamptz,view_expires_at=(data->>'view_expires_at')::timestamptz,media_expired=false WHERE id=a.id;
   PERFORM public._finalize_report_media(data,rid,a.id);
  ELSE
   UPDATE public.tester_report_attachments SET storage_provider='gcs',
    view_expires_at=a.created_at+interval '30 days',media_expired=(a.created_at+interval '30 days'<=now()) WHERE id=a.id;
   PERFORM public._finalize_report_media(ref,rid,a.id);
  END IF;
 END LOOP;
 UPDATE public.tester_reports tr SET storage_provider=first_media.storage_provider,
  view_expires_at=first_media.view_expires_at,media_expired=coalesce(first_media.media_expired,false)
 FROM (SELECT storage_provider,view_expires_at,media_expired FROM public.tester_report_attachments WHERE report_id=rid ORDER BY position,id LIMIT 1) first_media
 WHERE tr.id=rid;
 UPDATE public.storage_organization_usage SET active_reserved_bytes=(
  SELECT coalesce(sum(reserved_bytes),0) FROM public.report_media_uploads WHERE provider='supabase' AND status<>'deleted'),updated_at=now() WHERE id=1;
 RETURN result;
END $function$;
REVOKE ALL ON FUNCTION public._submit_tester_report_core(text,uuid,text,integer,uuid,text,text,text,uuid,jsonb,date) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public._submit_tester_report_core(text,uuid,text,integer,uuid,text,text,text,uuid,jsonb,date) TO authenticated,service_role;
