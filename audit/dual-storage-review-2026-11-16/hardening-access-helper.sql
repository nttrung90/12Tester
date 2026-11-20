-- Canonical expiry also protects rows created by the defective 091 clock.
CREATE OR REPLACE FUNCTION public._report_media_deadline(p_report_id uuid,p_attachment_id uuid)
RETURNS timestamptz LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE r record;a record;ref jsonb;physical_time timestamptz;cutoff timestamptz;
BEGIN
 SELECT * INTO r FROM public.tester_reports WHERE id=p_report_id;
 IF r.id IS NULL THEN RETURN NULL;END IF;
 IF p_attachment_id IS NULL THEN
  SELECT r.screenshot_url screenshot_url,r.thumbnail_url thumbnail_url,r.storage_provider storage_provider,r.created_at created_at,r.view_expires_at view_expires_at,r.media_expired media_expired INTO a;
 ELSE
  SELECT * INTO a FROM public.tester_report_attachments WHERE id=p_attachment_id AND report_id=p_report_id;
  IF a.id IS NULL THEN RETURN NULL;END IF;
 END IF;
 IF coalesce(a.media_expired,false) THEN RETURN NULL;END IF;
 ref:=public._report_storage_ref(a.screenshot_url);
 IF ref IS NULL THEN RETURN NULL;END IF;
 IF ref->>'provider'='supabase' THEN
  SELECT least(o.created_at,t.created_at) INTO physical_time FROM public.report_media_uploads m
  JOIN storage.objects o ON o.bucket_id=m.bucket AND o.name=m.object_path
  LEFT JOIN storage.objects t ON t.bucket_id=m.bucket AND t.name=m.thumbnail_object_path
  WHERE m.provider='supabase' AND m.report_id=p_report_id AND m.object_path=ref->>'path' AND m.status NOT IN('deleted','deleting','orphaned');
  IF physical_time IS NULL THEN RETURN NULL;END IF;
  cutoff:=physical_time+interval '216 hours';
 ELSE cutoff:=a.created_at+interval '30 days';END IF;
 RETURN least(cutoff,a.view_expires_at);
END $$;
REVOKE ALL ON FUNCTION public._report_media_deadline(uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public._report_media_deadline(uuid,uuid) TO service_role;
