-- 092: dual storage correctness. Restore the complete pre-091 business core.
BEGIN;
ALTER TABLE public.tester_reports ADD COLUMN IF NOT EXISTS updated_at timestamptz DEFAULT now();
ALTER TABLE public.report_media_uploads
 ADD COLUMN IF NOT EXISTS lease_token uuid,
 ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz,
 ADD COLUMN IF NOT EXISTS retry_count integer NOT NULL DEFAULT 0,
 ADD COLUMN IF NOT EXISTS last_error text,
 ADD COLUMN IF NOT EXISTS expected_main_bytes bigint,
 ADD COLUMN IF NOT EXISTS expected_thumbnail_bytes bigint,
 ADD COLUMN IF NOT EXISTS quota_accounted_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE public.storage_organization_usage ADD COLUMN IF NOT EXISTS local_project_ref text;
ALTER TABLE public.report_media_uploads ALTER COLUMN user_id DROP NOT NULL,ALTER COLUMN campaign_id DROP NOT NULL;
ALTER TABLE public.report_media_uploads DROP CONSTRAINT IF EXISTS report_media_uploads_user_id_fkey,
 DROP CONSTRAINT IF EXISTS report_media_uploads_campaign_id_fkey,DROP CONSTRAINT IF EXISTS report_media_uploads_report_id_fkey;
ALTER TABLE public.report_media_uploads
 ADD CONSTRAINT report_media_uploads_user_id_fkey FOREIGN KEY(user_id) REFERENCES public.profiles(id) ON DELETE SET NULL,
 ADD CONSTRAINT report_media_uploads_campaign_id_fkey FOREIGN KEY(campaign_id) REFERENCES public.campaigns(id) ON DELETE SET NULL,
 ADD CONSTRAINT report_media_uploads_report_id_fkey FOREIGN KEY(report_id) REFERENCES public.tester_reports(id) ON DELETE SET NULL;
ALTER TABLE public.storage_organization_usage ALTER COLUMN total_capacity_bytes DROP DEFAULT;
UPDATE public.storage_organization_usage SET is_org_metric_valid=false,dispatch_mode='google_overflow',last_reconciled_at=NULL WHERE id=1;
CREATE TABLE IF NOT EXISTS public.storage_trusted_hosts(host text PRIMARY KEY CHECK(host=lower(host) AND host ~ '^[a-z0-9.-]+(:[0-9]+)?$'));
ALTER TABLE public.storage_trusted_hosts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.storage_trusted_hosts FROM PUBLIC,anon,authenticated;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.storage_trusted_hosts TO service_role;
CREATE INDEX IF NOT EXISTS report_media_cleanup_lease_idx ON public.report_media_uploads(provider,status,lease_expires_at,cleanup_due_at);

CREATE OR REPLACE FUNCTION public._report_storage_ref(p_url text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE u text; m text[]; b text; p text; h text; provider text;
BEGIN
 IF p_url IS NULL OR length(p_url)>2048 THEN RETURN NULL; END IF;
 u:=split_part(split_part(p_url,'?',1),'#',1);
 IF u ~ '^https://firebasestorage\.googleapis\.com/v0/b/[^/]+/o/' THEN
  m:=regexp_match(u,'^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$'); b:=m[1];p:=m[2];provider:='gcs';
 ELSIF u ~ '^https://storage\.googleapis\.com/' THEN
  m:=regexp_match(u,'^https://storage\.googleapis\.com/([^/]+)/(.+)$');b:=m[1];p:=m[2];provider:='gcs';
 ELSIF u ~ '^https://[a-z0-9-]+\.firebasestorage\.app/' THEN
  m:=regexp_match(u,'^https://([^/]+)/(?:v0/b/([^/]+)/)?o/(.+)$');
  IF m IS NULL OR (m[2] IS NOT NULL AND m[2]<>m[1]) THEN RETURN NULL;END IF;
  b:=coalesce(m[2],m[1]);p:=m[3];provider:='gcs';
 ELSIF u ~ '^https://' THEN
  m:=regexp_match(u,'^https://([^/]+)/storage/v1/object/(?:(?:authenticated|public)/)?([^/]+)/(.+)$');
  IF m IS NULL THEN RETURN NULL;END IF;h:=lower(m[1]);
  IF NOT EXISTS(SELECT 1 FROM public.storage_trusted_hosts t WHERE t.host=h) THEN RETURN NULL;END IF;
  b:=m[2];p:=m[3];provider:='supabase';
 ELSIF u ~ '^supabase://' THEN
  m:=regexp_match(u,'^supabase://([^/]+)/(.+)$');b:=m[1];p:=m[2];provider:='supabase';
 ELSIF u ~ '^/storage/' THEN
  m:=regexp_match(u,'^/storage/v1/object/(?:(?:authenticated|public)/)?([^/]+)/(.+)$');b:=m[1];p:=m[2];provider:='supabase';
 ELSE RETURN NULL;END IF;
 IF b IS NULL OR p IS NULL THEN RETURN NULL;END IF;
 p:=replace(replace(p,'%2F','/'),'%2f','/');
 IF p !~ '^reports/(?:[A-Za-z0-9_-]+/){1,2}[A-Za-z0-9_.-]+\.(jpg|jpeg|png|webp)$' OR p ~ '(^|/)\.\.?(/|$)' THEN RETURN NULL;END IF;
 IF provider='supabase' AND b<>'report-screenshots' THEN RETURN NULL;END IF;
 IF provider='gcs' AND b NOT IN('cross-testers.firebasestorage.app','cross-testers.appspot.com','crosstest-prod.appspot.com') THEN RETURN NULL;END IF;
 RETURN jsonb_build_object('provider',provider,'bucket',b,'path',p);
END $$;
REVOKE ALL ON FUNCTION public._report_storage_ref(text) FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.is_valid_report_screenshot_url(p_url text,p_campaign_id uuid,p_user_id uuid)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE r jsonb; p text;
BEGIN
 r:=public._report_storage_ref(p_url);p:=r->>'path';
 RETURN coalesce(r IS NOT NULL AND (p LIKE 'reports/'||p_campaign_id::text||'/'||p_user_id::text||'/%'
  OR (r->>'provider'='gcs' AND p LIKE 'reports/'||p_campaign_id::text||'/'||p_user_id::text||'\_%' ESCAPE '\')),false);
END $$;
CREATE OR REPLACE FUNCTION public.is_valid_report_thumbnail_url(p_thumb_url text,p_campaign_id uuid,p_user_id uuid,p_screenshot_url text DEFAULT NULL)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE t jsonb;f jsonb;tp text;fp text;tn text;fn text;
BEGIN
 IF p_thumb_url IS NULL THEN RETURN true;END IF;
 IF NOT public.is_valid_report_screenshot_url(p_thumb_url,p_campaign_id,p_user_id) THEN RETURN false;END IF;
 IF p_screenshot_url IS NULL THEN RETURN true;END IF;
 t:=public._report_storage_ref(p_thumb_url);f:=public._report_storage_ref(p_screenshot_url);
 tp:=t->>'path';fp:=f->>'path';tn:=regexp_replace(tp,'^.*/','');fn:=regexp_replace(fp,'^.*/','');
 RETURN coalesce(t->>'provider'=f->>'provider' AND t->>'bucket'=f->>'bucket'
  AND regexp_replace(tp,'[^/]+$','')=regexp_replace(fp,'[^/]+$','')
  AND (tn=fn OR tn='thumb_'||fn OR tn=regexp_replace(fn,'(\.[^.]+)$','_thumb\1')),false);
END $$;
REVOKE ALL ON FUNCTION public.is_valid_report_screenshot_url(text,uuid,uuid) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.is_valid_report_thumbnail_url(text,uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.is_valid_report_screenshot_url(text,uuid,uuid),public.is_valid_report_thumbnail_url(text,uuid,uuid,text) TO authenticated,service_role;

CREATE OR REPLACE FUNCTION public._can_read_report_object(p_bucket text,p_name text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=public,pg_catalog AS $$
 SELECT p_bucket='report-screenshots' AND auth.uid() IS NOT NULL
 AND EXISTS(SELECT 1 FROM public.profiles p WHERE p.id=auth.uid() AND NOT coalesce(p.is_locked,false))
 AND EXISTS(SELECT 1 FROM public.report_media_uploads m
  JOIN storage.objects o ON o.bucket_id=m.bucket AND o.name=m.object_path
  LEFT JOIN storage.objects t ON t.bucket_id=m.bucket AND t.name=m.thumbnail_object_path
  WHERE m.provider='supabase' AND m.bucket=p_bucket AND p_name IN(m.object_path,m.thumbnail_object_path)
  AND m.status NOT IN('deleted','deleting','expired','orphaned')
  AND now()<least(m.view_expires_at,o.created_at+interval '216 hours',t.created_at+interval '216 hours')
  AND (m.user_id=auth.uid() OR EXISTS(SELECT 1 FROM public.campaigns c WHERE c.id=m.campaign_id AND c.owner_id=auth.uid())
   OR EXISTS(SELECT 1 FROM public.profiles p WHERE p.id=auth.uid() AND lower(p.role) IN('admin','moderator'))))
$$;
CREATE OR REPLACE FUNCTION public._can_insert_report_object(p_bucket text,p_name text,p_metadata jsonb)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=public,pg_catalog AS $$
BEGIN
 -- Keep an authorized Storage insert and its metadata commit in the same lock
 -- order as finalize/reconcile, including a thumbnail finishing during submit.
 PERFORM 1 FROM public.storage_organization_usage WHERE id=1 FOR UPDATE;
 RETURN p_bucket='report-screenshots' AND auth.uid() IS NOT NULL
 AND EXISTS(SELECT 1 FROM public.profiles p WHERE p.id=auth.uid() AND NOT coalesce(p.is_locked,false))
 AND coalesce(p_metadata->>'size','') ~ '^[0-9]+$'
 AND EXISTS(SELECT 1 FROM public.report_media_uploads m
  WHERE m.provider='supabase' AND m.bucket=p_bucket AND m.user_id=auth.uid()
  AND m.status IN('reserved','uploaded') AND m.reservation_expires_at>now()
  AND p_name IN(m.object_path,m.thumbnail_object_path)
  AND (CASE WHEN p_metadata->>'size' ~ '^[0-9]+$' THEN (p_metadata->>'size')::numeric ELSE 0 END)
      BETWEEN 1 AND CASE WHEN p_name=m.object_path THEN coalesce(m.expected_main_bytes,2000000) ELSE coalesce(m.expected_thumbnail_bytes,150000) END
  AND NOT EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=m.bucket AND o.name=m.object_path AND o.created_at<=now()-interval '216 hours'));
END
$$;
REVOKE ALL ON FUNCTION public._can_read_report_object(text,text),public._can_insert_report_object(text,text,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public._can_read_report_object(text,text),public._can_insert_report_object(text,text,jsonb) TO authenticated,service_role;
DROP POLICY IF EXISTS storage_objects_read_policy ON storage.objects;
CREATE POLICY storage_objects_read_policy ON storage.objects FOR SELECT TO authenticated USING(public._can_read_report_object(bucket_id,name));
DROP POLICY IF EXISTS storage_objects_insert_policy ON storage.objects;
CREATE POLICY storage_objects_insert_policy ON storage.objects FOR INSERT TO authenticated WITH CHECK(public._can_insert_report_object(bucket_id,name,metadata));

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
   -- Finalize already uploaded objects; never renew credentials after expiry.
   RETURN jsonb_build_object('success',true,'upload_id',m.upload_id,'provider',m.provider,'bucket',m.bucket,'object_path',m.object_path,
    'thumbnail_object_path',m.thumbnail_object_path,'reservation_id',m.id,'pinned',true,'status',m.status,'dispatch_mode',u.dispatch_mode,'upload_expired',m.provider='supabase');
  END IF;
  RETURN jsonb_build_object('success',true,'upload_id',m.upload_id,'provider',m.provider,'bucket',m.bucket,'object_path',m.object_path,
   'thumbnail_object_path',m.thumbnail_object_path,'reservation_id',m.id,'pinned',true,'status',m.status,'dispatch_mode',u.dispatch_mode);
 END IF;
 SELECT coalesce(sum(mm.reserved_bytes),0) INTO r FROM public.report_media_uploads mm WHERE mm.provider='supabase' AND mm.status<>'deleted';
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
  expected_main_bytes,expected_thumbnail_bytes,reserved_bytes,status)
 VALUES(p_upload_id,uid,p_campaign_id,p_attachment_id,provider,bucket,path,thumb,size_main,CASE WHEN p_has_thumbnail THEN 150000 ELSE 0 END,CASE WHEN provider='supabase' THEN n ELSE 0 END,'reserved') RETURNING id INTO mid;
 IF provider='supabase' AND u.used_storage_bytes+r+n>=floor(u.total_capacity_bytes::numeric*95/100) THEN mode:='google_overflow';END IF;
 UPDATE public.storage_organization_usage SET active_reserved_bytes=r+CASE WHEN provider='supabase' THEN n ELSE 0 END,dispatch_mode=mode,updated_at=now() WHERE id=1;
 RETURN jsonb_build_object('success',true,'upload_id',p_upload_id,'provider',provider,'bucket',bucket,'object_path',path,
  'thumbnail_object_path',thumb,'reservation_id',mid,'pinned',false,'status','reserved','dispatch_mode',mode);
END $$;
REVOKE ALL ON FUNCTION public.request_report_media_reservation(uuid,text,bigint,boolean,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.request_report_media_reservation(uuid,text,bigint,boolean,uuid) TO authenticated,service_role;

CREATE OR REPLACE FUNCTION public._prepare_report_media(p_url text,p_thumb text,p_campaign uuid,p_user uuid,p_allowed_report uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
DECLARE ref jsonb;tr jsonb;m public.report_media_uploads%rowtype;o storage.objects%rowtype;t storage.objects%rowtype;
 c timestamptz;main_bytes bigint;thumb_bytes bigint:=0;
BEGIN
 IF NOT public.is_valid_report_screenshot_url(p_url,p_campaign,p_user) OR NOT public.is_valid_report_thumbnail_url(p_thumb,p_campaign,p_user,p_url) THEN RAISE EXCEPTION 'INVALID_SCREENSHOT_URL';END IF;
 ref:=public._report_storage_ref(p_url);
 IF ref->>'provider'='gcs' THEN RETURN ref||jsonb_build_object('created_at',now(),'view_expires_at',now()+interval '30 days','cleanup_due_at',now()+interval '29 days','delete_deadline_at',now()+interval '30 days');END IF;
 SELECT * INTO m FROM public.report_media_uploads WHERE provider='supabase' AND bucket=ref->>'bucket' AND object_path=ref->>'path' AND user_id=p_user AND campaign_id=p_campaign FOR UPDATE;
 IF m.id IS NULL OR m.status NOT IN('reserved','uploaded','expired','finalized') OR (m.report_id IS NOT NULL AND m.report_id IS DISTINCT FROM p_allowed_report) THEN RAISE EXCEPTION 'SUBMISSION_PROVENANCE_FAILED';END IF;
 SELECT * INTO o FROM storage.objects WHERE bucket_id=m.bucket AND name=m.object_path;
 IF o.id IS NULL OR coalesce(o.owner::text,to_jsonb(o)->>'owner_id','')<>p_user::text OR coalesce(o.metadata->>'size','')!~'^[0-9]+$' THEN RAISE EXCEPTION 'STORAGE_OBJECT_NOT_FOUND';END IF;
 main_bytes:=(o.metadata->>'size')::bigint;
 IF main_bytes NOT BETWEEN 1 AND coalesce(m.expected_main_bytes,2000000) THEN RAISE EXCEPTION 'MEDIA_SIZE_MISMATCH';END IF;
 c:=o.created_at;
 IF m.thumbnail_object_path IS NOT NULL THEN
  SELECT * INTO t FROM storage.objects WHERE bucket_id=m.bucket AND name=m.thumbnail_object_path;
  IF t.id IS NOT NULL THEN
   IF coalesce(t.owner::text,to_jsonb(t)->>'owner_id','')<>p_user::text OR coalesce(t.metadata->>'size','')!~'^[0-9]+$' THEN RAISE EXCEPTION 'MEDIA_SIZE_MISMATCH';END IF;
   thumb_bytes:=(t.metadata->>'size')::bigint;
   IF thumb_bytes NOT BETWEEN 1 AND coalesce(m.expected_thumbnail_bytes,150000) THEN RAISE EXCEPTION 'MEDIA_SIZE_MISMATCH';END IF;
   c:=least(c,t.created_at);
  END IF;
 END IF;
 IF p_thumb IS NOT NULL AND p_thumb<>p_url THEN
  tr:=public._report_storage_ref(p_thumb);
  IF t.id IS NULL OR tr->>'path'<>m.thumbnail_object_path THEN RAISE EXCEPTION 'THUMBNAIL_PROVENANCE_FAILED';END IF;
 END IF;
 IF c IS NULL OR c<=now()-interval '216 hours' THEN RAISE EXCEPTION 'MEDIA_EXPIRED';END IF;
 RETURN ref||jsonb_build_object('media_id',m.id,'main_bytes',main_bytes,'thumbnail_bytes',thumb_bytes,
  'created_at',c,'view_expires_at',c+interval '216 hours','cleanup_due_at',c+interval '216 hours','delete_deadline_at',c+interval '240 hours');
END $$;
CREATE OR REPLACE FUNCTION public._finalize_report_media(p_data jsonb,p_report uuid,p_attachment uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_catalog AS $$
BEGIN
 IF p_data->>'provider'='supabase' THEN
  UPDATE public.report_media_uploads SET status='finalized',report_id=p_report,attachment_id=p_attachment,
   server_created_at=(p_data->>'created_at')::timestamptz,view_expires_at=(p_data->>'view_expires_at')::timestamptz,
   cleanup_due_at=(p_data->>'cleanup_due_at')::timestamptz,delete_deadline_at=(p_data->>'delete_deadline_at')::timestamptz,
   size_bytes=(p_data->>'main_bytes')::bigint,thumbnail_size_bytes=(p_data->>'thumbnail_bytes')::bigint,
   reserved_bytes=greatest(0,(p_data->>'main_bytes')::bigint+(p_data->>'thumbnail_bytes')::bigint-quota_accounted_bytes),updated_at=now()
  WHERE id=(p_data->>'media_id')::uuid AND user_id=auth.uid();
 ELSE
  UPDATE public.report_media_uploads SET status='finalized',report_id=p_report,attachment_id=p_attachment,updated_at=now()
  WHERE provider='gcs' AND bucket=p_data->>'bucket' AND object_path=p_data->>'path' AND user_id=auth.uid() AND report_id IS NULL;
 END IF;
END $$;
REVOKE ALL ON FUNCTION public._prepare_report_media(text,text,uuid,uuid,uuid),public._finalize_report_media(jsonb,uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;

-- Upgrade existing 091 rows using provider object time, without changing files.
WITH actual AS (
 SELECT m.id,least(m.server_created_at,o.created_at,t.created_at) created_at
 FROM public.report_media_uploads m
 LEFT JOIN storage.objects o ON o.bucket_id=m.bucket AND o.name=m.object_path
 LEFT JOIN storage.objects t ON t.bucket_id=m.bucket AND t.name=m.thumbnail_object_path
 WHERE m.provider='supabase' AND m.status<>'deleted' AND (o.id IS NOT NULL OR t.id IS NOT NULL)
)
UPDATE public.report_media_uploads m SET server_created_at=a.created_at,
 view_expires_at=least(m.view_expires_at,a.created_at+interval '216 hours'),
 cleanup_due_at=least(m.cleanup_due_at,a.created_at+interval '216 hours'),
 delete_deadline_at=least(m.delete_deadline_at,a.created_at+interval '240 hours'),
 status=CASE WHEN m.status IN('reserved','uploaded') AND a.created_at<=now()-interval '216 hours' THEN 'expired' ELSE m.status END
FROM actual a WHERE m.id=a.id;
UPDATE public.tester_report_attachments a SET storage_provider='supabase',created_at=m.server_created_at,
 view_expires_at=m.view_expires_at,media_expired=coalesce(a.media_expired,false) OR m.view_expires_at<=now()
FROM public.report_media_uploads m WHERE m.provider='supabase' AND m.status<>'deleted'
 AND m.report_id=a.report_id AND m.server_created_at IS NOT NULL
 AND right(split_part(split_part(a.screenshot_url,'?',1),'#',1),length(m.object_path)+1)='/'||m.object_path;
UPDATE public.tester_reports r SET storage_provider=a.storage_provider,view_expires_at=a.view_expires_at,
 media_expired=coalesce(r.media_expired,false) OR coalesce(a.media_expired,false)
FROM public.tester_report_attachments a WHERE a.report_id=r.id AND a.position=0 AND a.storage_provider='supabase';
