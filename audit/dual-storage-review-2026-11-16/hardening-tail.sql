
-- Inventory is read from provider metadata; physical deletion is exclusively Storage API.
CREATE OR REPLACE FUNCTION public.get_expired_report_media_for_cleanup(p_limit integer,p_lease_seconds integer,p_dry_run boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE ids uuid[];result jsonb;limit_rows integer:=greatest(1,least(coalesce(p_limit,50),100));
BEGIN
 IF coalesce(p_dry_run,false) THEN
  SELECT coalesce(jsonb_agg(to_jsonb(candidate)),'[]'::jsonb) INTO result FROM (
   SELECT m.* FROM public.report_media_uploads m
   WHERE m.provider='supabase' AND m.bucket='report-screenshots' AND m.status<>'deleted'
    AND (m.lease_expires_at IS NULL OR m.lease_expires_at<=clock_timestamp())
    AND ((m.status='finalized' AND m.cleanup_due_at<=now()) OR m.status IN('expired','orphaned','deleting')
     OR (m.status IN('reserved','uploaded') AND m.reservation_expires_at<=now()))
   ORDER BY m.delete_deadline_at NULLS LAST,m.created_at,m.id LIMIT limit_rows
  ) candidate;
  RETURN result;
 END IF;
 IF NOT coalesce(p_dry_run,false) THEN
  PERFORM 1 FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
  -- Recover files without a ledger, including files surviving a prior incorrect acknowledgement.
  INSERT INTO public.report_media_uploads(upload_id,provider,bucket,object_path,thumbnail_object_path,
   size_bytes,thumbnail_size_bytes,status,server_created_at,view_expires_at,cleanup_due_at,delete_deadline_at,reserved_bytes)
  SELECT 'inventory-'||o.id::text,'supabase',o.bucket_id,o.name,t.name,
   CASE WHEN o.metadata->>'size' ~ '^[0-9]+$' THEN (o.metadata->>'size')::bigint ELSE 0 END,
   CASE WHEN t.metadata->>'size' ~ '^[0-9]+$' THEN (t.metadata->>'size')::bigint ELSE 0 END,
   'orphaned',least(o.created_at,t.created_at),least(o.created_at,t.created_at)+interval '216 hours',
   least(o.created_at,t.created_at)+interval '216 hours',least(o.created_at,t.created_at)+interval '240 hours',0
  FROM storage.objects o LEFT JOIN storage.objects t ON t.bucket_id=o.bucket_id
   AND t.name=regexp_replace(o.name,'([^/]+)$','thumb_\1')
  WHERE o.bucket_id='report-screenshots' AND o.name ~ '^reports/(?:[A-Za-z0-9_-]+/){2}[A-Za-z0-9_.-]+\.(jpg|jpeg|png|webp)$'
   AND least(o.created_at,t.created_at)<=now()-interval '216 hours'
   AND NOT EXISTS(SELECT 1 FROM public.report_media_uploads m WHERE m.provider='supabase' AND m.bucket=o.bucket_id
    AND m.status<>'deleted' AND o.name IN(m.object_path,m.thumbnail_object_path))
   -- If the real parent exists, it owns the orphan thumbnail pair.
   AND NOT (regexp_replace(o.name,'^.*/','') LIKE 'thumb\_%' ESCAPE '\' AND EXISTS(
    SELECT 1 FROM storage.objects parent WHERE parent.bucket_id=o.bucket_id
     AND parent.name=regexp_replace(o.name,'/thumb_([^/]+)$','/\1')))
  ORDER BY least(o.created_at,t.created_at),o.id LIMIT limit_rows;
 END IF;
 SELECT array_agg(x.id) INTO ids FROM (
  SELECT m.id FROM public.report_media_uploads m
  WHERE m.provider='supabase' AND m.bucket='report-screenshots' AND m.status<>'deleted'
   AND (m.lease_expires_at IS NULL OR m.lease_expires_at<=clock_timestamp())
   AND ((m.status='finalized' AND m.cleanup_due_at<=now()) OR m.status IN('expired','orphaned','deleting')
    OR (m.status IN('reserved','uploaded') AND m.reservation_expires_at<=now()))
  ORDER BY m.delete_deadline_at NULLS LAST,m.created_at,m.id LIMIT limit_rows FOR UPDATE SKIP LOCKED
 ) x;
 IF ids IS NULL THEN RETURN '[]'::jsonb;END IF;
 IF NOT coalesce(p_dry_run,false) THEN
  UPDATE public.report_media_uploads SET status='deleting',lease_token=gen_random_uuid(),
   lease_expires_at=clock_timestamp()+make_interval(secs=>greatest(30,least(coalesce(p_lease_seconds,300),3600))),
   retry_count=retry_count+1,updated_at=now() WHERE id=ANY(ids);
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.delete_deadline_at NULLS LAST,m.id),'[]'::jsonb) INTO result
 FROM public.report_media_uploads m WHERE m.id=ANY(ids);
 RETURN result;
END $$;
CREATE OR REPLACE FUNCTION public.get_expired_report_media_for_cleanup(p_limit integer DEFAULT 50,p_lease_seconds integer DEFAULT 300)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
 SELECT public.get_expired_report_media_for_cleanup(p_limit,p_lease_seconds,false)
$$;

CREATE OR REPLACE FUNCTION public.record_report_media_deleted(p_media_id uuid,p_lease_token uuid,p_deleted_paths text[])
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE m public.report_media_uploads%rowtype;expected text[];remaining_r bigint;
BEGIN
 PERFORM 1 FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
 SELECT * INTO m FROM public.report_media_uploads WHERE id=p_media_id FOR UPDATE;
 IF m.id IS NULL THEN RETURN jsonb_build_object('success',false,'reason','RECORD_NOT_FOUND');END IF;
 IF m.status='deleted' THEN RETURN jsonb_build_object('success',true,'bytes_freed',0,'already_deleted',true);END IF;
 IF m.provider<>'supabase' OR m.bucket<>'report-screenshots' OR m.status<>'deleting'
  OR p_lease_token IS NULL OR m.lease_token IS DISTINCT FROM p_lease_token
  OR m.lease_expires_at IS NULL OR m.lease_expires_at<=clock_timestamp() THEN
  RETURN jsonb_build_object('success',false,'reason','LEASE_MISMATCH');END IF;
 expected:=array_remove(ARRAY[m.object_path,m.thumbnail_object_path],NULL);
 IF p_deleted_paths IS NULL OR cardinality(p_deleted_paths)<>cardinality(expected)
  OR NOT (p_deleted_paths @> expected AND expected @> p_deleted_paths) THEN
  RETURN jsonb_build_object('success',false,'reason','INCOMPLETE_OBJECT_CONFIRMATION');END IF;
 IF EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=m.bucket AND o.name=ANY(expected)) THEN
  RETURN jsonb_build_object('success',false,'reason','OBJECTS_STILL_PRESENT');END IF;
 UPDATE public.report_media_uploads SET status='deleted',deleted_at=now(),updated_at=now(),
  reserved_bytes=0,quota_accounted_bytes=0,lease_token=NULL,lease_expires_at=NULL WHERE id=m.id;
 SELECT coalesce(sum(reserved_bytes),0) INTO remaining_r FROM public.report_media_uploads WHERE provider='supabase' AND status<>'deleted';
 UPDATE public.storage_organization_usage SET used_storage_bytes=greatest(0,used_storage_bytes-m.quota_accounted_bytes),
  active_reserved_bytes=remaining_r,updated_at=now() WHERE id=1;
 UPDATE public.tester_report_attachments SET media_expired=true WHERE report_id=m.report_id
  AND (public._report_storage_ref(screenshot_url)->>'provider')='supabase'
  AND (public._report_storage_ref(screenshot_url)->>'bucket')=m.bucket
  AND (public._report_storage_ref(screenshot_url)->>'path')=m.object_path;
 UPDATE public.tester_reports tr SET media_expired=true,updated_at=now() WHERE tr.id=m.report_id
  AND NOT EXISTS(SELECT 1 FROM public.tester_report_attachments a WHERE a.report_id=tr.id AND NOT coalesce(a.media_expired,false));
 RETURN jsonb_build_object('success',true,'media_id',m.id,'bytes_freed',m.size_bytes+m.thumbnail_size_bytes);
END $$;
CREATE OR REPLACE FUNCTION public.record_report_media_deleted(p_upload_id text,p_bytes_freed bigint DEFAULT 0)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE m public.report_media_uploads%rowtype;n integer;token uuid:=gen_random_uuid();paths text[];
BEGIN
 PERFORM 1 FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
 SELECT count(*) INTO n FROM public.report_media_uploads WHERE upload_id=p_upload_id;
 IF n<>1 THEN RETURN jsonb_build_object('success',false,'reason','AMBIGUOUS_OR_MISSING_UPLOAD');END IF;
 SELECT * INTO m FROM public.report_media_uploads WHERE upload_id=p_upload_id FOR UPDATE;
 IF m.provider<>'supabase' OR m.bucket<>'report-screenshots' THEN RETURN jsonb_build_object('success',false,'reason','PROVIDER_MISMATCH');END IF;
 IF m.status='deleted' THEN RETURN jsonb_build_object('success',true,'already_deleted',true,'bytes_freed',0);END IF;
 -- Legacy trusted service caller still needs provider absence; no arbitrary byte decrements.
 paths:=array_remove(ARRAY[m.object_path,m.thumbnail_object_path],NULL);
 IF EXISTS(SELECT 1 FROM storage.objects WHERE bucket_id=m.bucket AND name=ANY(paths)) THEN RETURN jsonb_build_object('success',false,'reason','OBJECTS_STILL_PRESENT');END IF;
 IF m.lease_expires_at>clock_timestamp() THEN RETURN jsonb_build_object('success',false,'reason','ACTIVE_LEASE_REQUIRES_UUID_ACK');END IF;
 UPDATE public.report_media_uploads SET status='deleting',lease_token=token,lease_expires_at=clock_timestamp()+interval '1 minute' WHERE id=m.id;
 RETURN public.record_report_media_deleted(m.id,token,paths);
END $$;

CREATE OR REPLACE FUNCTION public.reconcile_storage_organization_usage(p_total_capacity_bytes bigint,p_used_storage_bytes bigint,p_measured_projects jsonb,p_is_valid boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE u public.storage_organization_usage%rowtype;valid boolean:=coalesce(p_is_valid,false);n integer;
 local_snapshot bigint;local_live bigint;r bigint;global_used bigint;invalid_count bigint;refs text[];sum_bytes numeric;mode text;
BEGIN
 SELECT * INTO u FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
 IF p_total_capacity_bytes IS NULL OR p_total_capacity_bytes<=0 OR p_used_storage_bytes IS NULL OR p_used_storage_bytes<0
  OR u.local_project_ref IS NULL OR NOT EXISTS(SELECT 1 FROM public.storage_trusted_hosts)
  OR jsonb_typeof(p_measured_projects) IS DISTINCT FROM 'array' THEN valid:=false;END IF;
 IF valid THEN
  BEGIN
   SELECT array_agg(v->>'project_ref'),sum((v->>'bytes')::numeric),count(*) INTO refs,sum_bytes,n FROM jsonb_array_elements(p_measured_projects) v;
   IF n=0 OR (SELECT count(DISTINCT x) FROM unnest(refs) x)<>n OR sum_bytes<>p_used_storage_bytes
    OR NOT u.local_project_ref=ANY(refs)
    OR EXISTS(SELECT 1 FROM jsonb_array_elements(p_measured_projects) v WHERE v->>'status' IS DISTINCT FROM 'SUCCESS'
      OR coalesce(v->>'bytes','')!~'^[0-9]+$' OR coalesce(v->>'project_ref','')!~'^[a-z]{20}$'
      OR (v->>'observed_at')::timestamptz<=now()-interval '30 minutes'
      OR (v->>'observed_at')::timestamptz>clock_timestamp()+interval '1 minute'
      OR v->>'observed_at' IS NULL) THEN valid:=false;END IF;
   SELECT (v->>'bytes')::bigint INTO local_snapshot FROM jsonb_array_elements(p_measured_projects) v WHERE v->>'project_ref'=u.local_project_ref;
  EXCEPTION WHEN OTHERS THEN valid:=false;END;
 END IF;
 IF NOT valid THEN
  UPDATE public.storage_organization_usage SET is_org_metric_valid=false,dispatch_mode='google_overflow',updated_at=now() WHERE id=1;
  RETURN jsonb_build_object('success',true,'dispatch_mode','google_overflow','is_org_metric_valid',false);
 END IF;
 -- One materialized snapshot for both U and which reservation bytes U contains.
 WITH actual AS MATERIALIZED(SELECT bucket_id,name,metadata,created_at FROM storage.objects),
 observed AS(SELECT m.id,coalesce(sum(CASE WHEN a.metadata->>'size' ~ '^[0-9]+$' THEN (a.metadata->>'size')::bigint ELSE 0 END),0)::bigint bytes
  FROM public.report_media_uploads m LEFT JOIN actual a ON a.bucket_id=m.bucket AND a.name IN(m.object_path,m.thumbnail_object_path)
  WHERE m.provider='supabase' AND m.status<>'deleted' GROUP BY m.id),
 updated AS(UPDATE public.report_media_uploads m SET quota_accounted_bytes=o.bytes,
  reserved_bytes=CASE WHEN m.status IN('reserved','uploaded') AND m.reservation_expires_at>now()
   THEN greatest(0,coalesce(m.expected_main_bytes,2000000)+CASE WHEN m.thumbnail_object_path IS NULL THEN 0 ELSE coalesce(m.expected_thumbnail_bytes,150000) END-o.bytes)
   ELSE 0 END FROM observed o WHERE m.id=o.id RETURNING m.reserved_bytes)
 SELECT coalesce((SELECT sum(CASE WHEN metadata->>'size' ~ '^[0-9]+$' THEN (metadata->>'size')::bigint ELSE 0 END) FROM actual),0),
  (SELECT count(*) FROM actual WHERE metadata->>'size' IS NULL OR metadata->>'size'!~'^[0-9]+$'),
  coalesce((SELECT sum(reserved_bytes) FROM updated),0) INTO local_live,invalid_count,r;
 IF invalid_count>0 THEN
  -- Preserve conservative holds and prevent uploads until provider metadata is complete.
  UPDATE public.storage_organization_usage SET is_org_metric_valid=false,dispatch_mode='google_overflow',updated_at=now() WHERE id=1;
  RETURN jsonb_build_object('success',true,'dispatch_mode','google_overflow','is_org_metric_valid',false);
 END IF;
 global_used:=p_used_storage_bytes-local_snapshot+local_live;
 mode:=u.dispatch_mode;
 IF global_used+r>=floor(p_total_capacity_bytes::numeric*95/100) THEN mode:='google_overflow';
 ELSIF mode='google_overflow' AND global_used+r<floor(p_total_capacity_bytes::numeric*90/100) THEN mode:='supabase_preferred';END IF;
 UPDATE public.storage_organization_usage SET total_capacity_bytes=p_total_capacity_bytes,used_storage_bytes=global_used,
  active_reserved_bytes=r,dispatch_mode=mode,last_reconciled_at=now(),is_org_metric_valid=true,measured_projects=p_measured_projects,updated_at=now() WHERE id=1;
 RETURN jsonb_build_object('success',true,'dispatch_mode',mode,'is_org_metric_valid',true,'used_storage_bytes',global_used,'active_reserved_bytes',r);
END $$;

REVOKE ALL ON FUNCTION public.get_expired_report_media_for_cleanup(integer,integer),public.get_expired_report_media_for_cleanup(integer,integer,boolean),
 public.record_report_media_deleted(text,bigint),public.record_report_media_deleted(uuid,uuid,text[]),
 public.reconcile_storage_organization_usage(bigint,bigint,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.get_expired_report_media_for_cleanup(integer,integer),public.get_expired_report_media_for_cleanup(integer,integer,boolean),
 public.record_report_media_deleted(text,bigint),public.record_report_media_deleted(uuid,uuid,text[]),
 public.reconcile_storage_organization_usage(bigint,bigint,jsonb,boolean) TO service_role;
NOTIFY pgrst,'reload schema';
COMMIT;
