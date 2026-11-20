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

CREATE OR REPLACE FUNCTION public._submit_tester_report_business_092(p_submission_context text, p_campaign_id uuid, p_content text, p_rating integer, p_client_submit_id uuid, p_screenshot_url text, p_image_hash text, p_thumbnail_url text, p_mission_id uuid, p_attachments jsonb DEFAULT NULL::jsonb, p_expected_date date DEFAULT NULL::date)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
declare
    v_user_id uuid := auth.uid();
    v_report_id uuid;
    v_today date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp record;
    v_existing_report record;
    v_idempotent_report record;
    v_receipt record;
    v_fingerprint text;
    v_already_checked_in boolean := false;
    v_status text := 'pending';
    v_rating int := greatest(1, least(5, coalesce(p_rating, 5)));
    v_new_balance int;
    v_provisional_granted boolean := false;
    v_provisional_amount int := 0;
    v_provisional_reward int := 10;
    v_policy public.business_policies;
    v_regrant int := 0;
    v_new_screenshot boolean := false;
    v_effective_full text;
    v_final_url text;
    v_final_hash text;
    v_final_thumb text;

    -- Mission context variables
    v_mission record;
    v_mp record;
    v_submission_context text;
    v_target_mission_id uuid := null;
    v_target_mp_id uuid := null;
    v_target_title text := null;
    v_target_reward int := null;
    v_target_ends_at timestamptz := null;
    v_target_reward_mode text := null;
    v_res_context text;
    v_res_mission_id uuid;
    v_res_title text;
    v_res_reward int;
    v_res_ends_at timestamptz;
    v_res_reward_mode text;
    v_result jsonb;

    -- Attachments variables
    v_att_canonical text := '';
    v_att_count int := 0;
    v_att_item jsonb;
    v_att_elem record;
    v_attachments_json jsonb := '[]'::jsonb;
    v_pos int;

    -- Tester Bonus Round Snapshot variable
    v_bonus_round_id uuid := null;
begin
    v_policy := public._get_effective_business_policy_row(v_today);
    v_provisional_reward := v_policy.report_provisional_reward;

    if v_user_id is null then
        raise exception 'NOT_AUTHENTICATED';
    end if;

    -- Strict explicit submission context
    if p_submission_context is null or p_submission_context not in ('app_test', 'mission')
       or (p_submission_context = 'mission') <> (p_mission_id is not null) then
        raise exception 'INVALID_REPORT_CONTEXT';
    end if;
    v_submission_context := p_submission_context;

    -- Advisory lock for client submission idempotency
    if p_client_submit_id is not null then
        perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    end if;

    if p_client_submit_id is not null and exists(select 1 from public.report_cancelled_requests where user_id=v_user_id and client_submit_id=p_client_submit_id) then
        raise exception 'REPORT_REQUEST_REQUIRES_REVIEW';
    end if;

    -- Validate attachments array if provided
    if p_attachments is not null then
        if p_client_submit_id is null then
            raise exception 'CLIENT_SUBMIT_ID_REQUIRED';
        end if;
        if jsonb_typeof(p_attachments) <> 'array' then
            raise exception 'ATTACHMENTS_MUST_BE_ARRAY';
        end if;
        v_att_count := jsonb_array_length(p_attachments);
        if v_att_count > 3 then
            raise exception 'TOO_MANY_ATTACHMENTS';
        end if;
        if v_att_count < 1 then
            raise exception 'ATTACHMENTS_EMPTY: attachments array cannot be empty';
        end if;

        for v_pos in 0 .. (v_att_count - 1) loop
            v_att_item := p_attachments->v_pos;
            if coalesce((v_att_item->>'position')::int, -1) <> v_pos then
                raise exception 'ATTACHMENT_POSITION_INVALID';
            end if;
            if v_att_item->>'screenshot_url' is null or btrim(v_att_item->>'screenshot_url') = '' then
                raise exception 'SCREENSHOT_URL_REQUIRED';
            end if;
            if not public.is_valid_report_screenshot_url(v_att_item->>'screenshot_url', p_campaign_id, v_user_id) then
                raise exception 'INVALID_SCREENSHOT_URL';
            end if;
            if v_att_item->>'image_hash' is null or btrim(v_att_item->>'image_hash') = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            if not (lower(v_att_item->>'image_hash') ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
            if v_att_item->>'thumbnail_url' is not null and btrim(v_att_item->>'thumbnail_url') <> '' then
                if not public.is_valid_report_thumbnail_url(v_att_item->>'thumbnail_url', p_campaign_id, v_user_id, v_att_item->>'screenshot_url') then
                    raise exception 'INVALID_THUMBNAIL_URL';
                end if;
            end if;
        end loop;

        select jsonb_agg(jsonb_build_object(
            'id', att->>'id',
            'position', (att->>'position')::int,
            'screenshot_url', att->>'screenshot_url',
            'thumbnail_url', att->>'thumbnail_url',
            'image_hash', lower(att->>'image_hash'),
            'sha256', att->>'sha256'
        ) order by (att->>'position')::int)::text
        into v_att_canonical
        from jsonb_array_elements(p_attachments) as att;
    end if;

    -- Validate single screenshot if provided without attachments
    if p_attachments is null and p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
        if not public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) then
            raise exception 'INVALID_SCREENSHOT_URL';
        end if;
        if p_image_hash is not null and btrim(p_image_hash) <> '' then
            if not (lower(p_image_hash) ~ '^[0-9a-f]{16}$') then
                raise exception 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            end if;
        end if;
        if p_thumbnail_url is not null and btrim(p_thumbnail_url) <> '' then
            if not public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, p_screenshot_url) then
                raise exception 'INVALID_THUMBNAIL_URL';
            end if;
        end if;
    end if;

    -- Compute payload fingerprint
    if p_attachments is not null then
        v_fingerprint := encode(digest(format('v3|%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            v_submission_context,
            coalesce(p_mission_id::text, ''),
            coalesce(p_content, ''),
            v_rating,
            coalesce(v_att_canonical, ''),
            coalesce(p_expected_date::text, '')
        ), 'sha256'), 'hex');
    else
        v_fingerprint := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            coalesce(p_content, ''),
            coalesce(p_screenshot_url, ''),
            coalesce(p_rating, 5),
            coalesce(lower(p_image_hash), ''),
            coalesce(p_thumbnail_url, ''),
            coalesce(p_mission_id::text, '')
        ), 'sha256'), 'hex');
    end if;

    -- Idempotency check via receipt ledger
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;

            if v_receipt.provenance = 'legacy_backfill' then
                declare
                    v_resp_mission text := coalesce(v_receipt.response_json ->> 'mission_id', '');
                    v_resp_url text := coalesce(v_receipt.response_json ->> 'screenshot_url', '');
                    v_resp_hash text := coalesce(lower(v_receipt.response_json ->> 'image_hash'), '');
                    v_resp_thumb text := coalesce(v_receipt.response_json ->> 'thumbnail_url', '');
                    v_resp_status text := coalesce(v_receipt.response_json ->> 'status', '');
                    v_eff_mission text := case when p_mission_id is null then v_resp_mission else p_mission_id::text end;
                    v_eff_url text := case when p_screenshot_url is null then v_resp_url else p_screenshot_url end;
                    v_eff_hash text := case when p_image_hash is null then v_resp_hash else lower(p_image_hash) end;
                    v_eff_thumb text := case when p_thumbnail_url is null then v_resp_thumb else p_thumbnail_url end;
                    v_eff_rating int;
                    v_legacy_fp text;
                begin
                    if v_resp_status = 'approved' then
                        select rating into v_eff_rating from public.tester_reports where id = v_receipt.report_id;
                        if v_eff_rating is null then
                            v_eff_rating := coalesce((v_receipt.response_json ->> 'rating')::int, 5);
                        end if;
                    else
                        v_eff_rating := coalesce(p_rating, 5);
                    end if;

                    if (p_screenshot_url is null or p_screenshot_url = v_resp_url)
                       and (p_image_hash is null or lower(p_image_hash) = v_resp_hash)
                       and (p_thumbnail_url is null or p_thumbnail_url = v_resp_thumb)
                       and (p_mission_id is null or p_mission_id::text = v_resp_mission)
                       and (v_resp_status = 'approved' or p_rating is null or p_rating = v_eff_rating)
                    then
                        v_legacy_fp := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                            p_campaign_id,
                            coalesce(p_content, ''),
                            v_eff_url,
                            v_eff_rating,
                            v_eff_hash,
                            v_eff_thumb,
                            v_eff_mission
                        ), 'sha256'), 'hex');

                        if v_receipt.payload_fingerprint = v_legacy_fp then
                            return v_receipt.response_json;
                        end if;
                    end if;
                end;
            end if;

            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;

        -- Fallback check tester_reports for legacy submissions
        select * into v_idempotent_report
        from public.tester_reports
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_idempotent_report.id is not null then
            if v_idempotent_report.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if p_mission_id is not null and v_idempotent_report.mission_id is distinct from p_mission_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if coalesce(v_idempotent_report.content, '') <> coalesce(p_content, '') then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_rating is not null and v_idempotent_report.rating is not null and v_idempotent_report.rating <> v_rating then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_image_hash is not null and v_idempotent_report.image_hash is not null and lower(v_idempotent_report.image_hash) <> lower(p_image_hash) then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;
            if p_screenshot_url is not null and v_idempotent_report.screenshot_url is not null and v_idempotent_report.screenshot_url <> p_screenshot_url then
                raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
            end if;

            select coalesce(jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256
            ) order by a.position), '[]'::jsonb) into v_attachments_json
            from public.tester_report_attachments a
            where a.report_id = v_idempotent_report.id;

            if jsonb_array_length(v_attachments_json) = 0 and v_idempotent_report.screenshot_url is not null then
                v_attachments_json := jsonb_build_array(jsonb_build_object(
                    'id', v_idempotent_report.id,
                    'position', 0,
                    'screenshot_url', v_idempotent_report.screenshot_url,
                    'thumbnail_url', v_idempotent_report.thumbnail_url,
                    'image_hash', v_idempotent_report.image_hash,
                    'sha256', null
                ));
            end if;

            v_result := jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_idempotent_report.screenshot_url,
                'image_hash', v_idempotent_report.image_hash,
                'thumbnail_url', v_idempotent_report.thumbnail_url,
                'submission_context', v_idempotent_report.submission_context,
                'mission_id', v_idempotent_report.mission_id,
                'mission_title', v_idempotent_report.mission_title,
                'mission_reward_credits', v_idempotent_report.mission_reward_credits,
                'mission_ends_at', to_char(v_idempotent_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_reward_mode', v_idempotent_report.mission_reward_mode,
                'rating', v_idempotent_report.rating,
                'report_date', to_char(v_idempotent_report.report_date, 'YYYY-MM-DD'),
                'attachments', v_attachments_json,
                'tester_bonus_round_id', v_idempotent_report.tester_bonus_round_id
            );

            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_idempotent_report.id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;

            return v_result;
        end if;
    end if;

    -- Date precondition check for uncommitted submissions
    if p_expected_date is not null and p_expected_date <> v_today then
        -- Cho phép nộp qua nửa đêm đối với nhiệm vụ once còn hiệu lực
        if v_submission_context = 'mission' and exists (
            select 1 from public.missions
            where id = p_mission_id and campaign_id = p_campaign_id and reward_mode = 'once' and status in ('open', 'full') and ends_at > now()
        ) then
            null;
        else
            raise exception 'REPORT_DATE_MISMATCH: Ngay bao cao du kien khong khop voi may chu';
        end if;
    end if;

    select * into v_camp from public.campaigns where id = p_campaign_id;
    if v_camp.id is null then
        raise exception 'CAMPAIGN_NOT_FOUND';
    end if;

    if v_camp.owner_id = v_user_id then
        raise exception 'CANNOT_TEST_OWN_APP';
    end if;

    if exists (
        select 1 from public.profiles
        where id = v_user_id and is_locked = true
    ) then
        raise exception 'USER_LOCKED';
    end if;

    -- Mission context allows open and finalized campaigns ('production', 'completed').
    -- Normal app_test context strictly requires 'open'.
    if v_submission_context = 'mission' then
        if v_camp.status not in ('open', 'production', 'completed') then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    else
        if v_camp.status <> 'open' then
            raise exception 'CAMPAIGN_NOT_OPEN';
        end if;
    end if;

    -- Lock contextually by (user, campaign, context, mission, date)
    perform pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s:%s:%s', v_user_id, p_campaign_id, v_submission_context, coalesce(p_mission_id::text, ''), v_today), 0)
    );

    -- Re-check receipt ledger under lock
    if p_client_submit_id is not null then
        select * into v_receipt
        from public.report_submission_receipts
        where user_id = v_user_id
          and client_submit_id = p_client_submit_id
        limit 1;

        if v_receipt.id is not null then
            if v_receipt.campaign_id <> p_campaign_id then
                raise exception 'CLIENT_SUBMIT_ID_MISMATCH';
            end if;
            if v_receipt.payload_fingerprint = v_fingerprint then
                return v_receipt.response_json;
            end if;
            raise exception 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        end if;
    end if;

    insert into public.campaign_members (campaign_id, user_id)
    values (p_campaign_id, v_user_id)
    on conflict (campaign_id, user_id) do nothing;

    -- Lookup existing report (khóa báo cáo trước nếu đã tồn tại để tương thích thứ tự khóa với review/reject):
    if v_submission_context = 'mission' then
        select reward_mode into v_target_reward_mode from public.missions where id = p_mission_id;
        if v_target_reward_mode = 'once' then
            select * into v_existing_report
            from public.tester_reports
            where campaign_id = p_campaign_id
              and user_id = v_user_id
              and submission_context = 'mission'
              and mission_id = p_mission_id
            order by created_at desc
            limit 1
            for update;
        else
            select * into v_existing_report
            from public.tester_reports
            where campaign_id = p_campaign_id
              and user_id = v_user_id
              and submission_context = 'mission'
              and mission_id = p_mission_id
              and report_date = v_today
            order by created_at desc
            limit 1
            for update;
        end if;

        -- Khóa hàng mission và participant dưới khóa để chống race condition với close/edit/delete/refund
        select * into v_mission from public.missions
        where id = p_mission_id and campaign_id = p_campaign_id
        for update;

        if v_mission.id is null then
            raise exception 'MISSION_NOT_FOUND';
        end if;

        if v_mission.status not in ('open', 'full') then
            raise exception '%', case v_mission.status
                when 'paused_by_creator' then 'MISSION_PAUSED'
                when 'stopped_no_credits' then 'MISSION_STOPPED_NO_CREDITS'
                when 'expired' then 'MISSION_EXPIRED'
                when 'deleted' then 'MISSION_DELETED'
                else 'MISSION_NOT_OPEN' end;
        end if;

        if v_mission.ends_at <= now() then
            raise exception 'MISSION_EXPIRED';
        end if;

        select * into v_mp from public.mission_participants
        where mission_id = v_mission.id and user_id = v_user_id
        for update;

        if v_mp.id is null or v_mp.joined_at > now() then
            raise exception 'MISSION_NOT_JOINED';
        end if;

        if v_mission.reward_mode = 'daily' then
            if v_today < (v_mp.joined_at at time zone 'Asia/Ho_Chi_Minh')::date then
                raise exception 'MISSION_NOT_JOINED';
            end if;
        end if;

        v_target_mission_id := v_mission.id;
        v_target_mp_id := v_mp.id;
        v_target_title := v_mission.title;
        v_target_reward := v_mission.reward_credits;
        v_target_ends_at := v_mission.ends_at;
        v_target_reward_mode := v_mission.reward_mode;
    else
        select * into v_existing_report
        from public.tester_reports
        where campaign_id = p_campaign_id
          and user_id = v_user_id
          and submission_context = 'app_test'
          and report_date = v_today
        order by created_at desc
        limit 1
        for update;
    end if;

    -- Backfill legacy receipt before client_submit_id gets overwritten
    if v_existing_report.id is not null
       and v_existing_report.client_submit_id is not null
       and v_existing_report.client_submit_id is distinct from p_client_submit_id then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id,
            v_existing_report.client_submit_id,
            p_campaign_id,
            v_existing_report.id,
            encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
                p_campaign_id,
                coalesce(v_existing_report.content, ''),
                coalesce(v_existing_report.screenshot_url, ''),
                coalesce(v_existing_report.rating, 5),
                coalesce(lower(v_existing_report.image_hash), ''),
                coalesce(v_existing_report.thumbnail_url, ''),
                coalesce(v_existing_report.mission_id::text, '')
            ), 'sha256'), 'hex'),
            jsonb_build_object(
                'success', true,
                'report_id', v_existing_report.id,
                'status', v_existing_report.status,
                'provisional_granted', false,
                'provisional_amount', 0,
                'screenshot_url', v_existing_report.screenshot_url,
                'image_hash', v_existing_report.image_hash,
                'thumbnail_url', v_existing_report.thumbnail_url,
                'submission_context', v_existing_report.submission_context,
                'mission_id', v_existing_report.mission_id,
                'mission_title', v_existing_report.mission_title,
                'mission_reward_credits', v_existing_report.mission_reward_credits,
                'mission_ends_at', to_char(v_existing_report.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_reward_mode', v_existing_report.mission_reward_mode,
                'rating', v_existing_report.rating,
                'report_date', to_char(v_existing_report.report_date, 'YYYY-MM-DD')
            ),
            v_existing_report.created_at,
            'legacy_backfill'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    -- Disallow mutating an existing report to another context or mission
    if v_existing_report.id is not null and (
        v_existing_report.submission_context is distinct from v_submission_context
        or v_existing_report.mission_id is distinct from p_mission_id
    ) then
        raise exception 'REPORT_CONTEXT_CONFLICT';
    end if;

    -- TH1: REPORT IS APPROVED -> comment update only (preserving original report_date and photos)
    if v_existing_report.id is not null and v_existing_report.status = 'approved' then
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;
        if p_screenshot_url is not null and p_screenshot_url <> v_existing_report.screenshot_url then
            raise exception 'REPORT_ALREADY_APPROVED: Khong the doi anh bao cao da duyet';
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (
            p_campaign_id, v_user_id, coalesce(v_existing_report.rating, v_rating),
            coalesce(p_content, ''), now()
        )
        on conflict (campaign_id, user_id)
        do update set
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        update public.tester_reports
        set content = case when coalesce(p_content, '') <> '' then p_content else content end,
            client_submit_id = coalesce(p_client_submit_id, client_submit_id)
        where id = v_existing_report.id
          and status = 'approved'
        returning id into v_report_id;

        if v_report_id is null then
            raise exception 'REPORT_ALREADY_REVIEWED';
        end if;
        v_status := 'approved';

        update public.profiles
        set last_active_date = v_today, missed_streak = 0
        where id = v_user_id;

        if v_submission_context = 'app_test' then
            perform public.refresh_daily_completion(v_user_id, v_today);
        end if;

        select
            submission_context, mission_id, mission_title,
            mission_reward_credits, mission_ends_at, mission_reward_mode,
            screenshot_url, image_hash, thumbnail_url,
            tester_bonus_round_id
        into
            v_res_context, v_res_mission_id, v_res_title,
            v_res_reward, v_res_ends_at, v_res_reward_mode,
            v_final_url, v_final_hash, v_final_thumb,
            v_bonus_round_id
        from public.tester_reports
        where id = v_report_id;

        select coalesce(jsonb_agg(jsonb_build_object(
            'id', a.id,
            'position', a.position,
            'screenshot_url', a.screenshot_url,
            'thumbnail_url', a.thumbnail_url,
            'image_hash', a.image_hash,
            'sha256', a.sha256
        ) order by a.position), '[]'::jsonb) into v_attachments_json
        from public.tester_report_attachments a
        where a.report_id = v_report_id;

        if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
            v_attachments_json := jsonb_build_array(jsonb_build_object(
                'id', v_report_id,
                'position', 0,
                'screenshot_url', v_final_url,
                'thumbnail_url', v_final_thumb,
                'image_hash', v_final_hash,
                'sha256', null
            ));
        end if;

        v_result := jsonb_build_object(
            'success', true,
            'report_id', v_report_id,
            'status', v_status,
            'provisional_granted', false,
            'provisional_amount', 0,
            'screenshot_url', v_final_url,
            'image_hash', v_final_hash,
            'thumbnail_url', v_final_thumb,
            'submission_context', v_res_context,
            'mission_id', v_res_mission_id,
            'mission_title', v_res_title,
            'mission_reward_credits', v_res_reward,
            'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'mission_reward_mode', v_res_reward_mode,
            'rating', coalesce(v_existing_report.rating, v_rating),
            'report_date', to_char(v_existing_report.report_date, 'YYYY-MM-DD'),
            'attachments', v_attachments_json,
            'tester_bonus_round_id', v_bonus_round_id
        );

        if p_client_submit_id is not null then
            insert into public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) values (
                v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) on conflict (user_id, client_submit_id) do nothing;
        end if;

        return v_result;
    end if;

    -- TH2: EXISTING REPORT IS PENDING OR REJECTED
    if v_existing_report.id is not null then
        v_bonus_round_id := v_existing_report.tester_bonus_round_id;
        if v_existing_report.submission_context = 'mission' then
            if p_mission_id is not null and v_existing_report.mission_id is distinct from p_mission_id then
                raise exception 'CANNOT_CHANGE_MISSION_IDENTITY';
            end if;
            v_submission_context := 'mission';
            v_target_mission_id := v_existing_report.mission_id;
            v_target_mp_id := v_existing_report.mission_participant_id;
            v_target_title := v_existing_report.mission_title;
            v_target_reward := v_existing_report.mission_reward_credits;
            v_target_ends_at := v_existing_report.mission_ends_at;
            v_target_reward_mode := coalesce(v_existing_report.mission_reward_mode, v_mission.reward_mode);

            if v_existing_report.status = 'rejected' then
                if v_target_ends_at is not null and v_target_ends_at < now() then
                    raise exception 'MISSION_EXPIRED';
                end if;
                -- Với bản đầu, sửa/nộp lại lượt hàng ngày trong chính ngày đó và trước hạn
                if v_target_reward_mode = 'daily' and v_existing_report.report_date <> v_today then
                    raise exception 'DAILY_REPORT_RESUBMIT_WINDOW_EXPIRED: Lượt hàng ngày chỉ được sửa hoặc nộp lại trong ngày báo cáo';
                end if;
            end if;
        else
            if p_mission_id is not null then
                raise exception 'CANNOT_CONVERT_EXISTING_PENDING_REPORT';
            end if;
            v_submission_context := 'app_test';
        end if;

        -- Image resolution for pending/rejected update
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_new_screenshot := true;
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
            v_final_thumb := p_attachments->0->>'thumbnail_url';

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = v_effective_full then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_new_screenshot := true;
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_hash := lower(p_image_hash);
            v_final_thumb := p_thumbnail_url;

            if v_existing_report.status = 'rejected' and v_existing_report.screenshot_url is not null and v_existing_report.screenshot_url = p_screenshot_url then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        else
            v_effective_full := v_existing_report.screenshot_url;
            v_final_hash := v_existing_report.image_hash;
            v_final_thumb := v_existing_report.thumbnail_url;
            if v_existing_report.status = 'rejected' then
                raise exception 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
            end if;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_existing_report.status = 'rejected' then
            v_regrant := coalesce(v_existing_report.provisional_granted_credits, v_provisional_reward);
            if v_existing_report.provisional_clawed > 0 then
                update public.profiles
                set credits = credits + v_regrant,
                    last_active_date = v_today,
                    missed_streak = 0
                where id = v_user_id
                returning credits into v_new_balance;

                insert into public.credit_logs (user_id, amount, action, note, balance)
                values (
                    v_user_id, v_regrant, 'test_provisional',
                    format('Tạm cấp lại %s Credit sau khi nộp lại kết quả test', v_regrant),
                    v_new_balance
                );

                v_provisional_granted := true;
                v_provisional_amount := v_regrant;
            else
                v_provisional_granted := false;
                v_provisional_amount := 0;
            end if;

            -- [BUG-2] Restore provisional checkin row for app_test resubmission
            if v_submission_context = 'app_test' then
                insert into public.checkins (campaign_id, user_id, checkin_date, note)
                values (p_campaign_id, v_user_id, v_today, 'Đã nộp kết quả test (tạm tính, chờ chủ app duyệt)')
                on conflict (campaign_id, user_id, checkin_date)
                do nothing;
            end if;

            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                status = 'pending',
                rejection_reason = null,
                reviewed_at = null,
                provisional_clawed = 0,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                created_at = now(),
                mission_submitted_at = case when v_submission_context = 'mission' then now() else mission_submitted_at end,
                mission_reward_mode = coalesce(mission_reward_mode, v_target_reward_mode)
            where id = v_existing_report.id
            returning id into v_report_id;
        else
            update public.tester_reports
            set content = case when coalesce(p_content, '') <> '' then p_content else content end,
                screenshot_url = v_effective_full,
                thumbnail_url = case when v_new_screenshot then v_final_thumb else thumbnail_url end,
                image_hash = case when v_new_screenshot then v_final_hash else image_hash end,
                rating = v_rating,
                client_submit_id = coalesce(p_client_submit_id, client_submit_id),
                mission_reward_mode = coalesce(mission_reward_mode, v_target_reward_mode)
            where id = v_existing_report.id
            returning id into v_report_id;
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Synchronize attachments table
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            delete from public.tester_report_attachments where report_id = v_report_id;
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        elsif v_new_screenshot then
            if exists (select 1 from public.tester_report_attachments where report_id = v_report_id and position = 0) then
                update public.tester_report_attachments
                set screenshot_url = v_effective_full,
                    thumbnail_url = v_final_thumb,
                    image_hash = v_final_hash,
                    sha256 = null,
                    created_at = now()
                where report_id = v_report_id and position = 0;
            else
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    gen_random_uuid(),
                    v_report_id,
                    0,
                    v_effective_full,
                    v_final_thumb,
                    v_final_hash,
                    null,
                    now()
                );
            end if;
        end if;

        v_status := 'pending';
    else
        -- TH3: BRAND NEW REPORT
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            v_effective_full := p_attachments->0->>'screenshot_url';
            v_final_thumb := p_attachments->0->>'thumbnail_url';
            v_final_hash := lower(p_attachments->0->>'image_hash');
        elsif p_screenshot_url is not null and btrim(p_screenshot_url) <> '' then
            v_effective_full := p_screenshot_url;
            if p_image_hash is null or btrim(p_image_hash) = '' then
                raise exception 'IMAGE_HASH_REQUIRED';
            end if;
            v_final_thumb := p_thumbnail_url;
            v_final_hash := lower(p_image_hash);
        else
            raise exception 'SCREENSHOT_URL_REQUIRED';
        end if;

        -- Acquire bonus round snapshot for new regular app_test report
        if v_submission_context = 'app_test' and v_camp.owner_id <> v_user_id then
            -- Lock campaign for key share to serialize against concurrent closing / admin deletion
            select * into v_camp from public.campaigns where id = p_campaign_id for key share;
            select id into v_bonus_round_id
            from public.campaign_tester_bonus_rounds
            where campaign_id = p_campaign_id
              and status = 'active'
            for key share;
        end if;

        if v_submission_context = 'mission' then
            v_target_mission_id := v_mission.id;
            v_target_mp_id := v_mp.id;
            v_target_title := v_mission.title;
            v_target_reward := v_mission.reward_credits;
            v_target_ends_at := v_mission.ends_at;
            v_target_reward_mode := v_mission.reward_mode;
        else
            v_target_mission_id := null;
            v_target_mp_id := null;
            v_target_title := null;
            v_target_reward := null;
            v_target_ends_at := null;
            v_target_reward_mode := null;
        end if;

        insert into public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
        values (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
        on conflict (campaign_id, user_id)
        do update set
            rating = excluded.rating,
            comment = case when coalesce(excluded.comment, '') <> '' then excluded.comment else public.feedbacks.comment end,
            updated_at = now();

        perform set_config('app.submitting_report', 'true', true);

        if v_submission_context = 'app_test' then
            select exists (
                select 1 from public.checkins
                where campaign_id = p_campaign_id
                  and user_id = v_user_id
                  and checkin_date = v_today
            ) into v_already_checked_in;

            if v_already_checked_in then
                insert into public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    mission_reward_mode,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                    tester_bonus_round_id, tester_bonus_awarded
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                    v_bonus_round_id, 0
                )
                returning id into v_report_id;

                v_status := 'pending';
                v_provisional_granted := false;
                v_provisional_amount := 0;
            else
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
                    mission_reward_mode,
                    review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                    tester_bonus_round_id, tester_bonus_awarded
                ) values (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', v_provisional_amount, 0, v_provisional_amount,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null,
                    null, null, null, null,
                    null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                    v_bonus_round_id, 0
                )
                returning id into v_report_id;

                v_status := 'pending';
            end if;
        else
            -- Mission report does not consume or conflict with ordinary app_test daily checkin
            insert into public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                mission_reward_mode,
                review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                tester_bonus_round_id, tester_bonus_awarded
            ) values (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                v_final_hash, p_client_submit_id, now(),
                'mission', v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at, now(),
                v_target_reward_mode,
                v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                null, 0
            )
            returning id into v_report_id;

            v_status := 'pending';
            v_provisional_granted := false;
            v_provisional_amount := 0;
        end if;

        -- Attachments
        if p_attachments is not null and jsonb_array_length(p_attachments) > 0 then
            for v_att_elem in select * from jsonb_to_recordset(p_attachments) as x(
                id uuid,
                position int,
                screenshot_url text,
                thumbnail_url text,
                image_hash text,
                sha256 text
            ) loop
                insert into public.tester_report_attachments (
                    id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
                ) values (
                    coalesce(v_att_elem.id, gen_random_uuid()),
                    v_report_id,
                    v_att_elem.position,
                    v_att_elem.screenshot_url,
                    v_att_elem.thumbnail_url,
                    lower(v_att_elem.image_hash),
                    v_att_elem.sha256,
                    now()
                );
            end loop;
        else
            insert into public.tester_report_attachments (
                id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256, created_at
            ) values (
                gen_random_uuid(),
                v_report_id,
                0,
                v_effective_full,
                v_final_thumb,
                v_final_hash,
                null,
                now()
            );
        end if;
    end if;

    update public.profiles
    set last_active_date = v_today, missed_streak = 0
    where id = v_user_id;

    -- [BUG-2] Refresh daily completion after app_test submission / resubmission
    if v_submission_context = 'app_test' then
        perform public.refresh_daily_completion(v_user_id, v_today);
    end if;

    select
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, mission_reward_mode,
        screenshot_url, image_hash, thumbnail_url, report_date, rating,
        tester_bonus_round_id
    into
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_res_reward_mode,
        v_final_url, v_final_hash, v_final_thumb, v_today, v_rating,
        v_bonus_round_id
    from public.tester_reports
    where id = v_report_id;

    select coalesce(jsonb_agg(jsonb_build_object(
        'id', a.id,
        'position', a.position,
        'screenshot_url', a.screenshot_url,
        'thumbnail_url', a.thumbnail_url,
        'image_hash', a.image_hash,
        'sha256', a.sha256
    ) order by a.position), '[]'::jsonb) into v_attachments_json
    from public.tester_report_attachments a
    where a.report_id = v_report_id;

    if jsonb_array_length(v_attachments_json) = 0 and v_final_url is not null then
        v_attachments_json := jsonb_build_array(jsonb_build_object(
            'id', v_report_id,
            'position', 0,
            'screenshot_url', v_final_url,
            'thumbnail_url', v_final_thumb,
            'image_hash', v_final_hash,
            'sha256', null
        ));
    end if;

    v_result := jsonb_build_object(
        'success', true,
        'report_id', v_report_id,
        'status', v_status,
        'provisional_granted', v_provisional_granted,
        'provisional_amount', v_provisional_amount,
        'screenshot_url', v_final_url,
        'image_hash', v_final_hash,
        'thumbnail_url', v_final_thumb,
        'submission_context', v_res_context,
        'mission_id', v_res_mission_id,
        'mission_title', v_res_title,
        'mission_reward_credits', v_res_reward,
        'mission_ends_at', to_char(v_res_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_reward_mode', v_res_reward_mode,
        'rating', v_rating,
        'report_date', to_char(v_today, 'YYYY-MM-DD'),
        'attachments', v_attachments_json,
        'tester_bonus_round_id', v_bonus_round_id
    );

    if p_client_submit_id is not null then
        insert into public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) values (
            v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
            v_fingerprint, v_result, now(), 'exact_v1'
        ) on conflict (user_id, client_submit_id) do nothing;
    end if;

    return v_result;
end;
$function$;
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

CREATE OR REPLACE FUNCTION public.request_report_media_access(
    p_report_id uuid,
    p_attachment_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
DECLARE
    v_user_id uuid := auth.uid();
    v_report record;
    v_camp record;
    v_attachment record;
    v_is_author boolean := false;
    v_is_owner boolean := false;
    v_is_admin boolean := false;
    v_role text := null;
    v_expires_at timestamptz;
    v_expires_epoch bigint;
    v_full_token text;
    v_thumb_token text;
    v_gateway_base text;
    v_signed_full_url text;
    v_signed_thumb_url text;
    v_att_part text := '';
    v_is_supabase boolean := false;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=v_user_id AND NOT coalesce(is_locked,false)) THEN RAISE EXCEPTION 'PERMISSION_DENIED';END IF;

    SELECT id, campaign_id, user_id, screenshot_url, thumbnail_url,
           created_at, media_expired, storage_provider, view_expires_at
    INTO v_report
    FROM public.tester_reports
    WHERE id = p_report_id;

    IF v_report.id IS NULL THEN
        RAISE EXCEPTION 'REPORT_NOT_FOUND';
    END IF;

    IF p_attachment_id IS NOT NULL THEN
        SELECT * INTO v_attachment
        FROM public.tester_report_attachments
        WHERE id = p_attachment_id AND report_id = p_report_id;

        IF v_attachment.id IS NULL THEN
            RAISE EXCEPTION 'ATTACHMENT_NOT_FOUND';
        END IF;
        v_att_part := p_attachment_id::text;


        IF public._report_media_deadline(p_report_id,p_attachment_id) IS NULL OR public._report_media_deadline(p_report_id,p_attachment_id)<=clock_timestamp() THEN RAISE EXCEPTION 'MEDIA_EXPIRED'; END IF;
    ELSE


        IF public._report_media_deadline(p_report_id,p_attachment_id) IS NULL OR public._report_media_deadline(p_report_id,p_attachment_id)<=clock_timestamp() THEN RAISE EXCEPTION 'MEDIA_EXPIRED'; END IF;
    END IF;

    v_is_author := (v_report.user_id = v_user_id);

    SELECT * INTO v_camp FROM public.campaigns WHERE id = v_report.campaign_id;
    IF v_camp.id IS NOT NULL AND v_camp.owner_id = v_user_id THEN
        v_is_owner := true;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id = v_user_id
          AND lower(coalesce(role, '')) IN ('admin', 'moderator')
          AND coalesce(is_locked, false) = false
    ) THEN
        v_is_admin := true;
    END IF;

    IF v_is_author THEN
        v_role := 'author';
    ELSIF v_is_owner THEN
        v_role := 'owner';
    ELSIF v_is_admin THEN
        v_role := 'admin';
    ELSE
        RAISE EXCEPTION 'FORBIDDEN';
    END IF;

    v_expires_at := least(now() + interval '15 minutes',public._report_media_deadline(p_report_id,p_attachment_id));
    v_expires_epoch := extract(epoch from v_expires_at)::bigint;

    IF p_attachment_id IS NOT NULL THEN
        v_full_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s:%s', p_report_id, v_att_part, v_user_id, v_role, 'full', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );

        v_thumb_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s:%s', p_report_id, v_att_part, v_user_id, v_role, 'thumb', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    ELSE
        v_full_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s', p_report_id, v_user_id, v_role, 'full', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );

        v_thumb_token := encode(
            hmac(
                format('%s:%s:%s:%s:%s', p_report_id, v_user_id, v_role, 'thumb', v_expires_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    END IF;

    v_gateway_base := nullif(current_setting('app.settings.media_gateway_base_url', true), '');
    IF v_gateway_base IS NULL OR length(trim(v_gateway_base)) = 0 THEN
        RAISE EXCEPTION 'MEDIA_GATEWAY_BASE_URL_NOT_CONFIGURED';
    END IF;

    IF p_attachment_id IS NOT NULL THEN
        v_signed_full_url := format('%s?report_id=%s&attachment_id=%s&variant=full&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, p_attachment_id, v_expires_epoch, v_full_token);
        v_signed_thumb_url := format('%s?report_id=%s&attachment_id=%s&variant=thumb&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, p_attachment_id, v_expires_epoch, v_thumb_token);
    ELSE
        v_signed_full_url := format('%s?report_id=%s&variant=full&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, v_expires_epoch, v_full_token);
        v_signed_thumb_url := format('%s?report_id=%s&variant=thumb&expires_at=%s&grant_token=%s',
            v_gateway_base, p_report_id, v_expires_epoch, v_thumb_token);
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'attachment_id', p_attachment_id,
        'role', v_role,
        'screenshot_url', v_signed_full_url,
        'thumbnail_url', v_signed_thumb_url,
        'signed_full_url', v_signed_full_url,
        'signed_thumbnail_url', v_signed_thumb_url,
        'expires_at', to_char(v_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'expires_at_epoch', v_expires_epoch,
        'expires_epoch', v_expires_epoch
    );
END;
$function$;

CREATE OR REPLACE FUNCTION public.verify_media_access_grant_internal(
    p_report_id uuid,
    p_user_id uuid,
    p_grant_token text,
    p_variant text,
    p_expires_at_epoch bigint,
    p_attachment_id uuid
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
DECLARE
    v_report record;
    v_camp record;
    v_attachment record;
    v_role text := null;
    v_now_epoch bigint := extract(epoch from now())::bigint;
    v_expected_sig text;
    v_att_part text := '';
    v_is_supabase boolean := false;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=p_user_id AND NOT coalesce(is_locked,false)) THEN RETURN false;END IF;
    IF p_user_id IS NULL OR p_grant_token IS NULL OR p_expires_at_epoch IS NULL THEN
        RETURN false;
    END IF;

    IF v_now_epoch >= p_expires_at_epoch OR (p_expires_at_epoch - v_now_epoch) > 1800 THEN
        RETURN false;
    END IF;

    SELECT id, campaign_id, user_id, media_expired, created_at, storage_provider, view_expires_at
    INTO v_report
    FROM public.tester_reports
    WHERE id = p_report_id;

    IF v_report.id IS NULL THEN
        RETURN false;
    END IF;

    IF p_attachment_id IS NOT NULL THEN
        SELECT * INTO v_attachment
        FROM public.tester_report_attachments
        WHERE id = p_attachment_id AND report_id = p_report_id;

        IF v_attachment.id IS NULL THEN
            RETURN false;
        END IF;


        IF public._report_media_deadline(p_report_id,p_attachment_id) IS NULL OR public._report_media_deadline(p_report_id,p_attachment_id)<=clock_timestamp() THEN RETURN false; END IF;
        v_att_part := p_attachment_id::text;
    ELSE


        IF public._report_media_deadline(p_report_id,p_attachment_id) IS NULL OR public._report_media_deadline(p_report_id,p_attachment_id)<=clock_timestamp() THEN RETURN false; END IF;
    END IF;

    SELECT * INTO v_camp FROM public.campaigns WHERE id=v_report.campaign_id;
    IF v_report.user_id=p_user_id THEN v_role:='author';
    ELSIF v_camp.owner_id=p_user_id THEN v_role:='owner';
    ELSIF EXISTS(SELECT 1 FROM public.profiles WHERE id=p_user_id AND lower(coalesce(role,'')) IN('admin','moderator') AND NOT coalesce(is_locked,false)) THEN v_role:='admin';END IF;
    IF v_role IS NULL THEN
        RETURN false;
    END IF;

    IF p_attachment_id IS NOT NULL THEN
        v_expected_sig := encode(
            hmac(
                format('%s:%s:%s:%s:%s:%s', p_report_id, v_att_part, p_user_id, v_role, p_variant, p_expires_at_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    ELSE
        v_expected_sig := encode(
            hmac(
                format('%s:%s:%s:%s:%s', p_report_id, p_user_id, v_role, p_variant, p_expires_at_epoch),
                public.get_media_gateway_secret(),
                'sha256'
            ),
            'hex'
        );
    END IF;

    RETURN (v_expected_sig = p_grant_token);
END;
$function$;


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
