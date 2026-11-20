-- ==============================================================================
-- Migration 093: Dual Storage Runtime Recheck (R01, R02, R03 Hardening)
-- Scope:
-- 1. R01: Storage binary upload preflight sends contentLength in metadata.
--    Support both size and contentLength in _can_insert_report_object with strict
--    positive integer format and strict size caps for main and thumbnail.
-- 2. R02: Eliminate quota undercounting on late privileged commit via monotonic
--    per-path completion history (main_committed, thumbnail_committed).
--    - When finalizing a report with missing thumbnail, retain conservative
--      reservation hold for uncommitted pending thumbnail writer + unmeasured main.
--    - In record_report_media_deleted, check individual path completion:
--      * Fully confirmed pairs release hold completely and stay released (v_hold = 0).
--      * Partial pairs (main deleted while thumb pending, or thumb deleted while main pending)
--        retain the exact hold of the uncommitted path (e.g. 150KB or 2MB).
--      * Total absence ACK retains conservative holds for all uncommitted paths.
--    - In _submit_tester_report_core, preserve tombstone holds in active_reserved_bytes
--      by checking reserved_bytes > 0 instead of status <> 'deleted'.
--    - In reconcile_storage_organization_usage, monotonic per-path tracking ensures
--      holds are zeroed when all expected objects have been committed, and never recreated
--      after physical deletion.
--    - In get_expired_report_media_for_cleanup, rediscover resurrected objects that
--      committed late after deletion, preserving least(deadline, created_at + 240h).
--    - Safe data backfill: restore conservative capacity holds and monotonic flags
--      for existing 091/092 rows.
-- 3. Allocator: request_report_media_reservation respects active tombstone holds.
-- ==============================================================================

BEGIN;

-- Add monotonic per-path completion tracking columns
ALTER TABLE public.report_media_uploads
  ADD COLUMN IF NOT EXISTS main_committed boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS thumbnail_committed boolean NOT NULL DEFAULT false;

-- 1. Fix R01: _can_insert_report_object supports contentLength preflight & strict caps
CREATE OR REPLACE FUNCTION public._can_insert_report_object(p_bucket text,p_name text,p_metadata jsonb)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE
  v_raw text;
  v_size bigint;
BEGIN
  -- Note: The FOR UPDATE lock on storage_organization_usage is released if the Storage
  -- provider tests permissions inside a savepoint and rolls back before actual commit.
  -- Quota integrity is protected by conservative residual reservation accounting
  -- in finalize, reconcile, and deletion ACK, NOT by expecting policy locks to survive rollback.
  PERFORM 1 FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
  IF p_bucket <> 'report-screenshots' OR auth.uid() IS NULL THEN
    RETURN false;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM public.profiles p WHERE p.id=auth.uid() AND NOT coalesce(p.is_locked,false)) THEN
    RETURN false;
  END IF;

  -- Extract size or contentLength from metadata (Storage binary preflight contract)
  v_raw := coalesce(p_metadata->>'size', p_metadata->>'contentLength');
  IF v_raw IS NULL OR v_raw !~ '^[0-9]+$' OR length(v_raw) > 15 THEN
    RETURN false;
  END IF;
  v_size := v_raw::bigint;
  IF v_size <= 0 THEN
    RETURN false;
  END IF;

  RETURN EXISTS(SELECT 1 FROM public.report_media_uploads m
    WHERE m.provider='supabase' AND m.bucket=p_bucket AND m.user_id=auth.uid()
    AND m.status IN('reserved','uploaded') AND m.reservation_expires_at>now()
    AND p_name IN(m.object_path,m.thumbnail_object_path)
    AND v_size BETWEEN 1 AND CASE WHEN p_name=m.object_path THEN coalesce(m.expected_main_bytes,2000000) ELSE coalesce(m.expected_thumbnail_bytes,150000) END
    AND NOT EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=m.bucket AND o.name=m.object_path AND o.created_at<=now()-interval '216 hours'));
END
$$;

-- 2. Fix R02 (Finalize): retain conservative hold when thumbnail is pending/delayed
CREATE OR REPLACE FUNCTION public._finalize_report_media(p_data jsonb,p_report uuid,p_attachment uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE
 v_m public.report_media_uploads%rowtype;
 v_main_bytes bigint := (p_data->>'main_bytes')::bigint;
 v_thumb_bytes bigint := coalesce((p_data->>'thumbnail_bytes')::bigint, 0);
 v_needed_reserved bigint;
BEGIN
 IF p_data->>'provider'='supabase' THEN
  SELECT * INTO v_m FROM public.report_media_uploads WHERE id=(p_data->>'media_id')::uuid AND user_id=auth.uid() FOR UPDATE;
  IF v_m.id IS NOT NULL THEN
   -- Main is verified in storage.objects by _prepare_report_media
   -- If thumbnail is expected but missing from storage.objects during finalize,
   -- retain conservative residual reservation hold for in-flight/delayed writer,
   -- including unmeasured main bytes if reconcile has not run yet.
   IF v_m.thumbnail_object_path IS NOT NULL AND v_thumb_bytes = 0 THEN
    v_needed_reserved := greatest(0, v_main_bytes + coalesce(v_m.expected_thumbnail_bytes, 150000) - v_m.quota_accounted_bytes);
   ELSE
    v_needed_reserved := greatest(0, v_main_bytes + v_thumb_bytes - v_m.quota_accounted_bytes);
   END IF;

   UPDATE public.report_media_uploads SET status='finalized',report_id=p_report,attachment_id=p_attachment,
    main_committed=true,
    thumbnail_committed=v_m.thumbnail_committed OR (v_thumb_bytes > 0),
    server_created_at=(p_data->>'created_at')::timestamptz,view_expires_at=(p_data->>'view_expires_at')::timestamptz,
    cleanup_due_at=(p_data->>'cleanup_due_at')::timestamptz,delete_deadline_at=(p_data->>'delete_deadline_at')::timestamptz,
    size_bytes=v_main_bytes,thumbnail_size_bytes=v_thumb_bytes,
    reserved_bytes=v_needed_reserved,updated_at=now()
   WHERE id=v_m.id;
  END IF;
 ELSE
  UPDATE public.report_media_uploads SET status='finalized',report_id=p_report,attachment_id=p_attachment,updated_at=now()
  WHERE provider='gcs' AND bucket=p_data->>'bucket' AND object_path=p_data->>'path' AND user_id=auth.uid() AND report_id IS NULL;
 END IF;
END $$;

-- 3. Fix R02 (Allocator): Ensure reservation count includes active holds / tombstones
CREATE OR REPLACE FUNCTION public.request_report_media_reservation(p_campaign_id uuid,p_upload_id text,p_estimated_size bigint,p_has_thumbnail boolean DEFAULT true,p_attachment_id uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE uid uuid:=auth.uid(); m public.report_media_uploads%rowtype;u public.storage_organization_usage%rowtype;
 n bigint;r bigint;provider text;bucket text;mode text;path text;thumb text;mid uuid;size_main bigint;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'NOT_AUTHENTICATED';END IF;
 IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=uid AND NOT coalesce(is_locked,false)) THEN RAISE EXCEPTION 'USER_LOCKED';END IF;
 IF p_upload_id IS NULL OR p_upload_id !~ '^[A-Za-z0-9_-]{1,128}$' OR p_upload_id LIKE 'thumb\_%' ESCAPE '\' OR p_estimated_size IS NULL OR p_has_thumbnail IS NULL OR p_estimated_size NOT BETWEEN 1 AND 2000000 THEN RAISE EXCEPTION 'INVALID_MEDIA_REQUEST';END IF;
 IF NOT EXISTS(SELECT 1 FROM public.campaigns WHERE id=p_campaign_id AND owner_id<>uid) THEN RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';END IF;
 IF NOT EXISTS(SELECT 1 FROM public.campaign_members WHERE campaign_id=p_campaign_id AND user_id=uid)
 AND NOT EXISTS(SELECT 1 FROM public.mission_participants mp JOIN public.missions mi ON mi.id=mp.mission_id WHERE mp.user_id=uid AND mi.campaign_id=p_campaign_id) THEN RAISE EXCEPTION 'NOT_CAMPAIGN_MEMBER';END IF;
 SELECT * INTO u FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
 SELECT * INTO m FROM public.report_media_uploads WHERE user_id=uid AND upload_id=p_upload_id FOR UPDATE;
 IF m.id IS NOT NULL THEN
  IF m.campaign_id<>p_campaign_id OR coalesce(m.expected_main_bytes,p_estimated_size)<>p_estimated_size
   OR (m.thumbnail_object_path IS NOT NULL)<>p_has_thumbnail THEN RAISE EXCEPTION 'MEDIA_RESERVATION_PAYLOAD_CONFLICT';END IF;
  IF m.status IN('deleted','deleting','orphaned') THEN RAISE EXCEPTION 'MEDIA_EXPIRED';END IF;
  IF m.status='expired' OR m.reservation_expires_at<=now() THEN
   RETURN jsonb_build_object('success',true,'upload_id',m.upload_id,'provider',m.provider,'bucket',m.bucket,'object_path',m.object_path,
    'thumbnail_object_path',m.thumbnail_object_path,'reservation_id',m.id,'pinned',true,'status',m.status,'dispatch_mode',u.dispatch_mode,'upload_expired',m.provider='supabase');
  END IF;
  RETURN jsonb_build_object('success',true,'upload_id',m.upload_id,'provider',m.provider,'bucket',m.bucket,'object_path',m.object_path,
   'thumbnail_object_path',m.thumbnail_object_path,'reservation_id',m.id,'pinned',true,'status',m.status,'dispatch_mode',u.dispatch_mode);
 END IF;
 -- Include all active reservation holds and tombstones
 SELECT coalesce(sum(mm.reserved_bytes),0) INTO r FROM public.report_media_uploads mm WHERE mm.provider='supabase' AND mm.reserved_bytes>0;
 size_main:=p_estimated_size;n:=size_main+CASE WHEN p_has_thumbnail THEN 150000 ELSE 0 END;mode:=u.dispatch_mode;
 IF NOT coalesce(u.is_org_metric_valid,false) OR u.last_reconciled_at IS NULL OR u.last_reconciled_at<=now()-interval '30 minutes'
  OR u.total_capacity_bytes<=0 OR u.local_project_ref IS NULL OR NOT EXISTS(SELECT 1 FROM public.storage_trusted_hosts) THEN
  mode:='google_overflow';provider:='gcs';
 ELSE
  IF u.used_storage_bytes+r>=floor(u.total_capacity_bytes::numeric*95/100) THEN mode:='google_overflow';
  ELSIF mode='google_overflow' AND u.used_storage_bytes+r<floor(u.total_capacity_bytes::numeric*90/100) THEN mode:='supabase_preferred';END IF;
  provider:=CASE WHEN mode='supabase_preferred' AND u.used_storage_bytes+r+n<=floor(u.total_capacity_bytes::numeric*95/100) THEN 'supabase' ELSE 'gcs' END;
 END IF;
 bucket:=CASE WHEN provider='supabase' THEN 'report-screenshots' ELSE 'cross-testers.firebasestorage.app' END;
 path:=format('reports/%s/%s/%s.jpg',p_campaign_id,uid,p_upload_id);
 thumb:=CASE WHEN p_has_thumbnail THEN format('reports/%s/%s/thumb_%s.jpg',p_campaign_id,uid,p_upload_id) END;
 INSERT INTO public.report_media_uploads(upload_id,user_id,campaign_id,attachment_id,provider,bucket,object_path,thumbnail_object_path,
  expected_main_bytes,expected_thumbnail_bytes,reserved_bytes,status,main_committed,thumbnail_committed)
 VALUES(p_upload_id,uid,p_campaign_id,p_attachment_id,provider,bucket,path,thumb,size_main,CASE WHEN p_has_thumbnail THEN 150000 ELSE 0 END,CASE WHEN provider='supabase' THEN n ELSE 0 END,'reserved',false,false) RETURNING id INTO mid;
 IF provider='supabase' AND u.used_storage_bytes+r+n>=floor(u.total_capacity_bytes::numeric*95/100) THEN mode:='google_overflow';END IF;
 UPDATE public.storage_organization_usage SET active_reserved_bytes=r+CASE WHEN provider='supabase' THEN n ELSE 0 END,dispatch_mode=mode,updated_at=now() WHERE id=1;
 RETURN jsonb_build_object('success',true,'upload_id',p_upload_id,'provider',provider,'bucket',bucket,'object_path',path,
  'thumbnail_object_path',thumb,'reservation_id',mid,'pinned',false,'status','reserved','dispatch_mode',mode);
END $$;

-- 4. Fix R02 (Submission Core): Preserve tombstone holds when updating active_reserved_bytes
CREATE OR REPLACE FUNCTION public._submit_tester_report_core(p_submission_context text,p_campaign_id uuid,p_content text,p_rating integer,p_client_submit_id uuid,p_screenshot_url text,p_image_hash text,p_thumbnail_url text,p_mission_id uuid,p_attachments jsonb DEFAULT NULL,p_expected_date date DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,extensions,pg_catalog AS $function$
DECLARE result jsonb;rid uuid;uid uuid:=auth.uid();a record;data jsonb;ref jsonb;had_receipt boolean;
BEGIN
 IF uid IS NULL THEN RAISE EXCEPTION 'NOT_AUTHENTICATED';END IF;
 -- Same lock order as allocator, reconcile, cleanup and acknowledgement.
 PERFORM 1 FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
 SELECT EXISTS(SELECT 1 FROM public.report_submission_receipts WHERE user_id=uid AND client_submit_id=p_client_submit_id) INTO had_receipt;
 result:=public._submit_tester_report_business_092(p_submission_context,p_campaign_id,p_content,p_rating,p_client_submit_id,p_screenshot_url,p_image_hash,p_thumbnail_url,p_mission_id,p_attachments,p_expected_date);
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
 -- CRITICAL FIX: reserved_bytes > 0 preserves active tombstone capacity holds of deleted/expired rows
 UPDATE public.storage_organization_usage SET active_reserved_bytes=(
  SELECT coalesce(sum(reserved_bytes),0) FROM public.report_media_uploads WHERE provider='supabase' AND reserved_bytes>0),updated_at=now() WHERE id=1;
 RETURN result;
END $function$;

-- 5. Fix R02 (Deletion ACK): Monotonic per-path tracking determines residual hold on partial / absence deletion
CREATE OR REPLACE FUNCTION public.record_report_media_deleted(p_media_id uuid,p_lease_token uuid,p_deleted_paths text[])
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE
 m public.report_media_uploads%rowtype;
 expected text[];
 remaining_r bigint;
 v_freed bigint := 0;
 v_hold bigint := 0;
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

 -- Deduct accounted bytes that were actually deleted
 v_freed := m.quota_accounted_bytes;

 -- Monotonic per-path check:
 -- If a path was NEVER committed in storage, writer may still be delayed/in-flight!
 -- Maintain residual hold for that uncommitted path (e.g. 2MB main or 150KB thumbnail).
 -- If ALL paths were already committed, pair is fully accounted and deleted => v_hold = 0!
 IF NOT m.main_committed THEN
   v_hold := v_hold + coalesce(m.expected_main_bytes, 2000000);
 END IF;
 IF m.thumbnail_object_path IS NOT NULL AND NOT m.thumbnail_committed THEN
   v_hold := v_hold + coalesce(m.expected_thumbnail_bytes, 150000);
 END IF;

 UPDATE public.report_media_uploads SET status='deleted',deleted_at=now(),updated_at=now(),
  reserved_bytes=v_hold,quota_accounted_bytes=0,lease_token=NULL,lease_expires_at=NULL,
  size_bytes=0,thumbnail_size_bytes=0 WHERE id=m.id;

 IF v_freed > 0 THEN
  UPDATE public.storage_organization_usage SET used_storage_bytes=greatest(0,used_storage_bytes-v_freed),updated_at=now() WHERE id=1;
 END IF;

 SELECT coalesce(sum(reserved_bytes),0) INTO remaining_r FROM public.report_media_uploads WHERE provider='supabase' AND reserved_bytes>0;
 UPDATE public.storage_organization_usage SET active_reserved_bytes=remaining_r,updated_at=now() WHERE id=1;

 UPDATE public.tester_report_attachments SET media_expired=true WHERE report_id=m.report_id
  AND (public._report_storage_ref(screenshot_url)->>'provider')='supabase'
  AND (public._report_storage_ref(screenshot_url)->>'bucket')=m.bucket
  AND (public._report_storage_ref(screenshot_url)->>'path')=m.object_path;
 UPDATE public.tester_reports tr SET media_expired=true,updated_at=now() WHERE tr.id=m.report_id
  AND NOT EXISTS(SELECT 1 FROM public.tester_report_attachments a WHERE a.report_id=tr.id AND NOT coalesce(a.media_expired,false));
 RETURN jsonb_build_object('success',true,'media_id',m.id,'bytes_freed',coalesce(m.size_bytes,0)+coalesce(m.thumbnail_size_bytes,0));
END $$;

-- 6. Fix R02 (Reconciliation): Monotonic per-path hold tracking and authoritative size
CREATE OR REPLACE FUNCTION public.reconcile_storage_organization_usage(p_total_capacity_bytes bigint,p_used_storage_bytes bigint,p_measured_projects jsonb,p_is_valid boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE
 u public.storage_organization_usage%rowtype;valid boolean:=coalesce(p_is_valid,false);n integer;
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
      OR (coalesce(v->>'bytes','')!~'^[0-9]+$') OR coalesce(v->>'project_ref','')!~'^[a-z]{20}$'
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

 -- One materialized snapshot of storage.objects
 WITH actual AS MATERIALIZED(
   SELECT bucket_id,name,metadata,created_at FROM storage.objects
 ),
 observed AS(
  SELECT m.id,
         coalesce(sum(CASE
           WHEN a.metadata->>'size' ~ '^[0-9]+$' THEN (a.metadata->>'size')::bigint
           ELSE 0 END), 0)::bigint bytes,
         count(a.name) FILTER (WHERE a.name = m.object_path) AS has_main,
         count(a.name) FILTER (WHERE a.name = m.thumbnail_object_path) AS has_thumb,
         coalesce(max(CASE WHEN a.name = m.object_path AND a.metadata->>'size' ~ '^[0-9]+$' THEN (a.metadata->>'size')::bigint ELSE 0 END), 0)::bigint main_bytes,
         coalesce(max(CASE WHEN a.name = m.thumbnail_object_path AND a.metadata->>'size' ~ '^[0-9]+$' THEN (a.metadata->>'size')::bigint ELSE 0 END), 0)::bigint thumb_bytes
  FROM public.report_media_uploads m
  LEFT JOIN actual a ON a.bucket_id=m.bucket AND a.name IN(m.object_path,m.thumbnail_object_path)
  WHERE m.provider='supabase'
  GROUP BY m.id
 ),
 updated AS(
  UPDATE public.report_media_uploads m
  SET quota_accounted_bytes=o.bytes,
      main_committed=m.main_committed OR (o.has_main > 0),
      thumbnail_committed=m.thumbnail_committed OR (o.has_thumb > 0),
      size_bytes=CASE WHEN o.has_main > 0 THEN o.main_bytes ELSE m.size_bytes END,
      thumbnail_size_bytes=CASE WHEN o.has_thumb > 0 THEN o.thumb_bytes ELSE m.thumbnail_size_bytes END,
      reserved_bytes=CASE
        -- If all expected files have been committed at least once (monotonic history),
        -- any uncommitted pending write risk is over. When present in storage, they are in U.
        -- When physically deleted from storage, they remain released. Zero out reserved_bytes completely!
        WHEN (m.main_committed OR o.has_main > 0)
             AND (m.thumbnail_object_path IS NULL OR (m.thumbnail_committed OR o.has_thumb > 0))
        THEN 0

        -- Otherwise, retain conservative hold ONLY for paths that have NEVER been committed!
        ELSE (
          (CASE WHEN (m.main_committed OR o.has_main > 0) THEN 0 ELSE coalesce(m.expected_main_bytes, 2000000) END)
          + (CASE WHEN m.thumbnail_object_path IS NOT NULL AND NOT (m.thumbnail_committed OR o.has_thumb > 0)
                  THEN coalesce(m.expected_thumbnail_bytes, 150000) ELSE 0 END)
        )
      END
  FROM observed o WHERE m.id=o.id
  RETURNING m.reserved_bytes
 )
 SELECT coalesce((SELECT sum(CASE
   WHEN metadata->>'size' ~ '^[0-9]+$' THEN (metadata->>'size')::bigint
   ELSE 0 END) FROM actual), 0),
  (SELECT count(*) FROM actual WHERE metadata->>'size' IS NULL OR metadata->>'size'!~'^[0-9]+$'),
  coalesce((SELECT sum(reserved_bytes) FROM updated), 0)
 INTO local_live,invalid_count,r;

 IF invalid_count>0 THEN
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

-- 7. Fix R02 (Cleanup): Rediscover resurrected objects preserving strict deletion deadline
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

  -- 1. Rediscover resurrected objects after tombstone / absence deletion:
  -- Revive the record as 'orphaned' for immediate cleanup, preserving least(deadline, created_at + 240h).
  WITH resurrected AS (
    SELECT m.id,
           least(
             coalesce(m.delete_deadline_at, now() + interval '24 hours'),
             min(o.created_at) + interval '240 hours'
           ) AS new_deadline
    FROM public.report_media_uploads m
    JOIN storage.objects o ON o.bucket_id = m.bucket AND o.name IN (m.object_path, m.thumbnail_object_path)
    WHERE m.provider = 'supabase' AND m.bucket = 'report-screenshots' AND m.status = 'deleted'
    GROUP BY m.id, m.delete_deadline_at
  )
  UPDATE public.report_media_uploads m
  SET status = 'orphaned',
      cleanup_due_at = now(),
      delete_deadline_at = r.new_deadline,
      updated_at = now()
  FROM resurrected r
  WHERE m.id = r.id;

  -- 2. Recover unmanaged files without any ledger
  INSERT INTO public.report_media_uploads(upload_id,provider,bucket,object_path,thumbnail_object_path,
   size_bytes,thumbnail_size_bytes,status,server_created_at,view_expires_at,cleanup_due_at,delete_deadline_at,reserved_bytes)
  SELECT 'inventory-'||o.id::text,'supabase',o.bucket_id,o.name,t.name,
   CASE WHEN o.metadata->>'size' ~ '^[0-9]+$' THEN (o.metadata->>'size')::bigint WHEN o.metadata->>'contentLength' ~ '^[0-9]+$' THEN (o.metadata->>'contentLength')::bigint ELSE 0 END,
   CASE WHEN t.metadata->>'size' ~ '^[0-9]+$' THEN (t.metadata->>'size')::bigint WHEN t.metadata->>'contentLength' ~ '^[0-9]+$' THEN (t.metadata->>'contentLength')::bigint ELSE 0 END,
   'orphaned',least(o.created_at,t.created_at),least(o.created_at,t.created_at)+interval '216 hours',
   least(o.created_at,t.created_at)+interval '216 hours',least(o.created_at,t.created_at)+interval '240 hours',0
  FROM storage.objects o LEFT JOIN storage.objects t ON t.bucket_id=o.bucket_id
   AND t.name=regexp_replace(o.name,'([^/]+)$','thumb_\1')
  WHERE o.bucket_id='report-screenshots' AND o.name ~ '^reports/(?:[A-Za-z0-9_-]+/){2}[A-Za-z0-9_.-]+\.(jpg|jpeg|png|webp)$'
   AND least(o.created_at,t.created_at)<=now()-interval '216 hours'
   AND NOT EXISTS(SELECT 1 FROM public.report_media_uploads m WHERE m.provider='supabase' AND m.bucket=o.bucket_id
    AND m.status<>'deleted' AND o.name IN(m.object_path,m.thumbnail_object_path))
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
  -- Monotonic live observation: record paths present in storage BEFORE physical deletion
  -- so ACK can distinguish confirmed pairs from uncommitted pending writes.
  UPDATE public.report_media_uploads m
  SET main_committed = m.main_committed OR EXISTS (
        SELECT 1 FROM storage.objects o WHERE o.bucket_id = m.bucket AND o.name = m.object_path
      ),
      thumbnail_committed = m.thumbnail_committed OR EXISTS (
        SELECT 1 FROM storage.objects t WHERE t.bucket_id = m.bucket AND t.name = m.thumbnail_object_path
      ),
      status='deleting',lease_token=gen_random_uuid(),
      lease_expires_at=clock_timestamp()+make_interval(secs=>greatest(30,least(coalesce(p_lease_seconds,300),3600))),
      retry_count=retry_count+1,updated_at=now() WHERE id=ANY(ids);
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.delete_deadline_at NULLS LAST,m.id),'[]'::jsonb) INTO result
  FROM public.report_media_uploads m WHERE m.id=ANY(ids);
 RETURN result;
END $$;

-- 8. Safe backfill for legacy rows created under 091/092
-- Restore conservative capacity holds and monotonic flags for rows where expected files were not committed before upgrade
WITH actual AS (
  SELECT bucket_id, name, metadata, created_at FROM storage.objects
),
missing_analysis AS (
  SELECT m.id,
         count(a.name) FILTER (WHERE a.name = m.object_path) AS has_main,
         count(a.name) FILTER (WHERE a.name = m.thumbnail_object_path) AS has_thumb
  FROM public.report_media_uploads m
  LEFT JOIN actual a ON a.bucket_id = m.bucket AND a.name IN (m.object_path, m.thumbnail_object_path)
  WHERE m.provider = 'supabase'
  GROUP BY m.id
)
UPDATE public.report_media_uploads m
SET main_committed = (ma.has_main > 0) OR (m.status = 'finalized' AND m.size_bytes > 0),
    thumbnail_committed = (ma.has_thumb > 0) OR (m.status = 'finalized' AND m.thumbnail_size_bytes > 0),
    reserved_bytes = CASE
      -- If both files are already present or were finalized, no hold needed
      WHEN ((ma.has_main > 0) OR (m.status = 'finalized' AND m.size_bytes > 0))
           AND (m.thumbnail_object_path IS NULL OR (ma.has_thumb > 0) OR (m.status = 'finalized' AND m.thumbnail_size_bytes > 0))
      THEN 0
      -- If files were never committed, restore conservative hold
      ELSE (
        (CASE WHEN (ma.has_main > 0) OR (m.status = 'finalized' AND m.size_bytes > 0) THEN 0 ELSE coalesce(m.expected_main_bytes, 2000000) END)
        + (CASE WHEN m.thumbnail_object_path IS NOT NULL AND NOT ((ma.has_thumb > 0) OR (m.status = 'finalized' AND m.thumbnail_size_bytes > 0))
                THEN coalesce(m.expected_thumbnail_bytes, 150000) ELSE 0 END)
      )
    END
FROM missing_analysis ma
WHERE m.id = ma.id;

UPDATE public.storage_organization_usage
SET active_reserved_bytes = (
  SELECT coalesce(sum(reserved_bytes), 0) FROM public.report_media_uploads WHERE provider = 'supabase' AND reserved_bytes > 0
),
is_org_metric_valid = false,
dispatch_mode = 'google_overflow',
last_reconciled_at = NULL,
updated_at = now()
WHERE id = 1;

-- 9. Explicit Security Permissions:
-- Client RPC permissions for authenticated:
GRANT EXECUTE ON FUNCTION public.request_report_media_reservation(uuid,text,bigint,boolean,uuid) TO authenticated,service_role;
GRANT EXECUTE ON FUNCTION public._can_insert_report_object(text,text,jsonb) TO authenticated,service_role;
GRANT EXECUTE ON FUNCTION public._submit_tester_report_core(text,uuid,text,integer,uuid,text,text,text,uuid,jsonb,date) TO authenticated,service_role;

-- Worker functions strictly service-role only:
REVOKE ALL ON FUNCTION public._finalize_report_media(jsonb,uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;

REVOKE ALL ON FUNCTION public.get_expired_report_media_for_cleanup(integer,integer),public.get_expired_report_media_for_cleanup(integer,integer,boolean),
 public.record_report_media_deleted(uuid,uuid,text[]),
 public.reconcile_storage_organization_usage(bigint,bigint,jsonb,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.get_expired_report_media_for_cleanup(integer,integer),public.get_expired_report_media_for_cleanup(integer,integer,boolean),
 public.record_report_media_deleted(uuid,uuid,text[]),
 public.reconcile_storage_organization_usage(bigint,bigint,jsonb,boolean) TO service_role;

COMMIT;
