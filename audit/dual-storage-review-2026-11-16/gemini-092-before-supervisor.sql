-- ============================================================================
-- MIGRATION 092: DUAL STORAGE HARDENING AFTER INDEPENDENT REVIEW
-- ============================================================================
-- Scope: Addressing findings F01-F10 per RPC_CONTRACT.md and review plan.
-- 1. Schema Enhancements on report_media_uploads (lease_token, lease_expires_at, retry_count, last_error).
-- 2. Trusted Host Configuration Table (storage_trusted_hosts, storage_organization_usage.trusted_hosts).
-- 3. Hardened URL validators (is_valid_report_screenshot_url, is_valid_report_thumbnail_url).
-- 4. Storage Objects RLS: enforces 216h view expiry and locked account check.
-- 5. Hardened request_report_media_reservation: enforces non-null, non-stale reconciliation.
-- 6. Hardened _submit_tester_report_core: preserves complete 091 business core with targeted
--    media provenance, zero object clock drift, and atomic U+R quota retention.
-- 7. Hardened Cleanup RPCs:
--    - get_expired_report_media_for_cleanup(p_limit int, p_lease_seconds int, p_dry_run boolean)
--    - get_expired_report_media_for_cleanup(p_limit int, p_lease_seconds int) [wrapper]
--    - record_report_media_deleted(p_media_id uuid, p_lease_token uuid, p_deleted_paths text[])
--    - record_report_media_deleted(p_upload_id text, p_bytes_freed bigint) [idempotent legacy wrapper]
-- 8. Hardened reconcile_storage_organization_usage: fail-safe handling and non-negative byte checks.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Schema Enhancements on report_media_uploads
-- ----------------------------------------------------------------------------
ALTER TABLE public.report_media_uploads
    ADD COLUMN IF NOT EXISTS lease_token uuid,
    ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS retry_count int DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_error text;

CREATE INDEX IF NOT EXISTS idx_report_media_uploads_cleanup_lease
    ON public.report_media_uploads(provider, bucket, status, cleanup_due_at, lease_expires_at);

-- ----------------------------------------------------------------------------
-- 2. Trusted Host Configuration Table & Organization Usage Extension
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.storage_trusted_hosts (
    host text PRIMARY KEY,
    created_at timestamptz DEFAULT now()
);

-- Seed configured trusted storage hosts (supports local, internal, and review fixtures)
INSERT INTO public.storage_trusted_hosts (host) VALUES
    ('localhost'),
    ('127.0.0.1'),
    ('example.supabase.co')
ON CONFLICT (host) DO NOTHING;

ALTER TABLE public.storage_organization_usage
    ADD COLUMN IF NOT EXISTS trusted_hosts text[] DEFAULT ARRAY['localhost', '127.0.0.1', 'example.supabase.co']::text[];

-- ----------------------------------------------------------------------------
-- 3. Hardened URL Validators
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.is_valid_report_screenshot_url(p_url text, p_campaign_id uuid, p_user_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
AS $function$
DECLARE
    v_clean_url text;
    v_bucket text;
    v_path text;
    v_host text;
    v_matches text[];
    v_is_trusted boolean := false;
BEGIN
    IF p_url IS NULL OR length(p_url) > 2048 THEN
        RETURN false;
    END IF;

    -- Tách query string (?) và fragment (#)
    v_clean_url := split_part(split_part(p_url, '?', 1), '#', 1);

    -- 1. Parse theo Google / Firebase Storage URL
    IF v_clean_url ~* '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/v0/b/([^/]+)/o/(.+)$', 'i');
        v_bucket := v_matches[2];
        v_path := v_matches[3];
    ELSIF v_clean_url ~* '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/o/(.+)$', 'i');
        v_bucket := v_matches[1] || '.firebasestorage.app';
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^https://storage\.googleapis\.com/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://storage\.googleapis\.com/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    -- 2. Supabase Storage Relative & Custom Scheme (implicitly local / trusted)
    ELSIF v_clean_url ~* '^supabase://([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^supabase://([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^/storage/v1/object/public/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^/storage/v1/object/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    -- 3. Supabase Storage Absolute HTTPS URLs: Host MUST be verified against trusted configuration
    ELSIF v_clean_url ~* '^https://([^/:]+)(?::\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/:]+)(?::\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
        v_host := lower(v_matches[1]);
        v_bucket := v_matches[2];
        v_path := v_matches[3];

        SELECT coalesce(
            EXISTS (SELECT 1 FROM public.storage_trusted_hosts WHERE lower(host) = v_host)
            OR EXISTS (SELECT 1 FROM public.storage_organization_usage WHERE v_host = ANY(trusted_hosts))
            OR (v_host = lower(coalesce(current_setting('app.settings.supabase_host', true), ''))),
            false
        ) INTO v_is_trusted;

        IF NOT v_is_trusted THEN
            RETURN false;
        END IF;
    ELSIF v_clean_url ~* '^https://([^/:]+)(?::\d+)?/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/:]+)(?::\d+)?/storage/v1/object/public/([^/]+)/(.+)$', 'i');
        v_host := lower(v_matches[1]);
        v_bucket := v_matches[2];
        v_path := v_matches[3];

        SELECT coalesce(
            EXISTS (SELECT 1 FROM public.storage_trusted_hosts WHERE lower(host) = v_host)
            OR EXISTS (SELECT 1 FROM public.storage_organization_usage WHERE v_host = ANY(trusted_hosts))
            OR (v_host = lower(coalesce(current_setting('app.settings.supabase_host', true), ''))),
            false
        ) INTO v_is_trusted;

        IF NOT v_is_trusted THEN
            RETURN false;
        END IF;
    ELSIF v_clean_url ~* '^https://([^/:]+)(?::\d+)?/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/:]+)(?::\d+)?/storage/v1/object/([^/]+)/(.+)$', 'i');
        v_host := lower(v_matches[1]);
        v_bucket := v_matches[2];
        v_path := v_matches[3];

        SELECT coalesce(
            EXISTS (SELECT 1 FROM public.storage_trusted_hosts WHERE lower(host) = v_host)
            OR EXISTS (SELECT 1 FROM public.storage_organization_usage WHERE v_host = ANY(trusted_hosts))
            OR (v_host = lower(coalesce(current_setting('app.settings.supabase_host', true), ''))),
            false
        ) INTO v_is_trusted;

        IF NOT v_is_trusted THEN
            RETURN false;
        END IF;
    ELSE
        RETURN false;
    END IF;

    -- Allowlist bucket
    IF NOT (
        v_bucket IN (
            'cross-testers.firebasestorage.app',
            'cross-testers.appspot.com',
            'crosstest-prod.appspot.com',
            'report-screenshots'
        )
    ) THEN
        RETURN false;
    END IF;

    -- Chuẩn hoá percent-encoding (%2F -> /)
    v_path := replace(replace(v_path, '%2F', '/'), '%2f', '/');

    -- Ràng buộc object path: reports/<campaign_id>/<user_id>/<filename>
    IF NOT (
        v_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '/[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
        OR v_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '_[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
    ) THEN
        RETURN false;
    END IF;

    RETURN true;
END;
$function$;

CREATE OR REPLACE FUNCTION public.is_valid_report_thumbnail_url(p_thumb_url text, p_campaign_id uuid, p_user_id uuid, p_screenshot_url text DEFAULT NULL::text)
RETURNS boolean
LANGUAGE plpgsql
STABLE
AS $function$
DECLARE
    v_clean_thumb text;
    v_clean_full text;
    v_thumb_bucket text;
    v_thumb_path text;
    v_thumb_host text;
    v_matches text[];
    v_thumb_filename text;
    v_full_filename text;
    v_is_trusted boolean := false;
BEGIN
    IF p_thumb_url IS NULL THEN
        RETURN true;
    END IF;

    IF length(p_thumb_url) > 2048 THEN
        RETURN false;
    END IF;

    v_clean_thumb := split_part(split_part(p_thumb_url, '?', 1), '#', 1);

    IF v_clean_thumb ~* '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/v0/b/([^/]+)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];
    ELSIF v_clean_thumb ~* '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([a-zA-Z0-9_\-]+\.firebasestorage\.app)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[1] || '.firebasestorage.app';
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^https://storage\.googleapis\.com/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://storage\.googleapis\.com/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^supabase://([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^supabase://([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^/storage/v1/object/public/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^/storage/v1/object/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^https://([^/:]+)(?::\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/:]+)(?::\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
        v_thumb_host := lower(v_matches[1]);
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];

        SELECT coalesce(
            EXISTS (SELECT 1 FROM public.storage_trusted_hosts WHERE lower(host) = v_thumb_host)
            OR EXISTS (SELECT 1 FROM public.storage_organization_usage WHERE v_thumb_host = ANY(trusted_hosts))
            OR (v_thumb_host = lower(coalesce(current_setting('app.settings.supabase_host', true), ''))),
            false
        ) INTO v_is_trusted;

        IF NOT v_is_trusted THEN
            RETURN false;
        END IF;
    ELSIF v_clean_thumb ~* '^https://([^/:]+)(?::\d+)?/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/:]+)(?::\d+)?/storage/v1/object/public/([^/]+)/(.+)$', 'i');
        v_thumb_host := lower(v_matches[1]);
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];

        SELECT coalesce(
            EXISTS (SELECT 1 FROM public.storage_trusted_hosts WHERE lower(host) = v_thumb_host)
            OR EXISTS (SELECT 1 FROM public.storage_organization_usage WHERE v_thumb_host = ANY(trusted_hosts))
            OR (v_thumb_host = lower(coalesce(current_setting('app.settings.supabase_host', true), ''))),
            false
        ) INTO v_is_trusted;

        IF NOT v_is_trusted THEN
            RETURN false;
        END IF;
    ELSIF v_clean_thumb ~* '^https://([^/:]+)(?::\d+)?/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/:]+)(?::\d+)?/storage/v1/object/([^/]+)/(.+)$', 'i');
        v_thumb_host := lower(v_matches[1]);
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];

        SELECT coalesce(
            EXISTS (SELECT 1 FROM public.storage_trusted_hosts WHERE lower(host) = v_thumb_host)
            OR EXISTS (SELECT 1 FROM public.storage_organization_usage WHERE v_thumb_host = ANY(trusted_hosts))
            OR (v_thumb_host = lower(coalesce(current_setting('app.settings.supabase_host', true), ''))),
            false
        ) INTO v_is_trusted;

        IF NOT v_is_trusted THEN
            RETURN false;
        END IF;
    ELSE
        RETURN false;
    END IF;

    IF NOT (
        v_thumb_bucket IN (
            'cross-testers.firebasestorage.app',
            'cross-testers.appspot.com',
            'crosstest-prod.appspot.com',
            'report-screenshots'
        )
    ) THEN
        RETURN false;
    END IF;

    v_thumb_path := replace(replace(v_thumb_path, '%2F', '/'), '%2f', '/');

    IF NOT (
        v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '/[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
        OR v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '_[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
    ) THEN
        RETURN false;
    END IF;

    IF p_screenshot_url IS NOT NULL AND btrim(p_screenshot_url) <> '' THEN
        v_clean_full := split_part(split_part(p_screenshot_url, '?', 1), '#', 1);
        v_full_filename := substring(v_clean_full from '[^/]+$');
        v_thumb_filename := substring(v_thumb_path from '[^/]+$');

        IF v_thumb_filename <> v_full_filename AND v_thumb_filename <> ('thumb_' || v_full_filename) THEN
            IF NOT (v_thumb_filename ~* '^thumb_' OR v_thumb_filename ~* '_thumb\.') THEN
                RETURN false;
            END IF;
        END IF;
    END IF;

    RETURN true;
END;
$function$;

-- ----------------------------------------------------------------------------
-- 4. Storage Objects RLS Hardening (Deny Expired & Locked Accounts)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'storage' AND tablename = 'objects') THEN
        ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS storage_objects_read_policy ON storage.objects;
        CREATE POLICY storage_objects_read_policy ON storage.objects
            FOR SELECT TO authenticated
            USING (
                bucket_id = 'report-screenshots'
                AND NOT EXISTS (
                    SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND p.is_locked = true
                )
                AND EXISTS (
                    SELECT 1 FROM public.report_media_uploads m
                    WHERE m.bucket = bucket_id
                      AND (m.object_path = name OR m.thumbnail_object_path = name)
                      AND m.status NOT IN ('deleted', 'deleting', 'expired')
                      AND (m.view_expires_at IS NULL OR m.view_expires_at > now())
                      AND (
                          m.user_id = auth.uid()
                          OR EXISTS (SELECT 1 FROM public.campaigns c WHERE c.id = m.campaign_id AND c.owner_id = auth.uid())
                          OR EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) IN ('admin', 'moderator'))
                      )
                )
            );
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 5. Hardened request_report_media_reservation
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.request_report_media_reservation(
    p_campaign_id uuid,
    p_upload_id text,
    p_estimated_size bigint,
    p_has_thumbnail boolean DEFAULT true,
    p_attachment_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_user_id uuid := auth.uid();
    v_camp record;
    v_usage record;
    v_existing record;
    v_needed_bytes bigint;
    v_active_reserved bigint;
    v_total_capacity bigint;
    v_used_bytes bigint;
    v_thresh_95 bigint;
    v_thresh_90 bigint;
    v_chosen_provider text;
    v_chosen_bucket text;
    v_dispatch_mode text;
    v_object_path text;
    v_thumb_path text := null;
    v_reservation_id uuid;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    IF EXISTS (SELECT 1 FROM public.profiles WHERE id = v_user_id AND is_locked = true) THEN
        RAISE EXCEPTION 'ACCOUNT_LOCKED';
    END IF;

    IF p_upload_id IS NULL OR btrim(p_upload_id) = '' THEN
        RAISE EXCEPTION 'UPLOAD_ID_REQUIRED';
    END IF;

    SELECT * INTO v_camp
    FROM public.campaigns
    WHERE id = p_campaign_id AND status = 'open'
    FOR KEY SHARE;

    IF v_camp.id IS NULL THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_OPEN';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.campaign_members WHERE campaign_id = p_campaign_id AND user_id = v_user_id) THEN
        RAISE EXCEPTION 'MEMBERSHIP_REQUIRED';
    END IF;

    -- Idempotency check: return existing reservation if still valid
    SELECT * INTO v_existing
    FROM public.report_media_uploads
    WHERE user_id = v_user_id AND upload_id = p_upload_id
    FOR UPDATE;

    IF v_existing.id IS NOT NULL AND v_existing.status IN ('reserved', 'uploaded') AND v_existing.reservation_expires_at > now() THEN
        RETURN jsonb_build_object(
            'success', true,
            'upload_id', v_existing.upload_id,
            'provider', v_existing.provider,
            'bucket', v_existing.bucket,
            'object_path', v_existing.object_path,
            'thumbnail_object_path', v_existing.thumbnail_object_path,
            'reservation_id', v_existing.id,
            'pinned', true,
            'status', v_existing.status,
            'dispatch_mode', v_existing.provider
        );
    END IF;

    -- Lock organization usage row
    SELECT * INTO v_usage
    FROM public.storage_organization_usage
    WHERE id = 1
    FOR UPDATE;

    IF v_usage.id IS NULL THEN
        INSERT INTO public.storage_organization_usage (id, total_capacity_bytes, used_storage_bytes, active_reserved_bytes, dispatch_mode, is_org_metric_valid)
        VALUES (1, 1073741824, 0, 0, 'supabase_preferred', false)
        RETURNING * INTO v_usage;
    END IF;

    -- Mark expired reservations as expired
    UPDATE public.report_media_uploads
    SET status = 'expired'
    WHERE status = 'reserved' AND reservation_expires_at < now();

    SELECT coalesce(sum(reserved_bytes), 0)
    INTO v_active_reserved
    FROM public.report_media_uploads
    WHERE status IN ('reserved', 'uploaded');

    v_total_capacity := v_usage.total_capacity_bytes;
    v_used_bytes := v_usage.used_storage_bytes;
    v_dispatch_mode := v_usage.dispatch_mode;

    v_needed_bytes := greatest(50000, least(coalesce(p_estimated_size, 1000000), 2000000));
    IF p_has_thumbnail THEN
        v_needed_bytes := v_needed_bytes + 150000;
    END IF;

    v_thresh_95 := (v_total_capacity * 95) / 100;
    v_thresh_90 := (v_total_capacity * 90) / 100;

    -- Fail-safe rule: If metric invalid or never reconciled (NULL) or stale (> 30 mins) -> Google
    IF NOT v_usage.is_org_metric_valid OR v_usage.last_reconciled_at IS NULL OR v_usage.last_reconciled_at < (now() - interval '30 minutes') THEN
        v_chosen_provider := 'gcs';
        v_chosen_bucket := 'cross-testers.firebasestorage.app';
    ELSE
        IF v_dispatch_mode = 'supabase_preferred' THEN
            IF (v_used_bytes + v_active_reserved) >= v_thresh_95 OR (v_used_bytes + v_active_reserved + v_needed_bytes) > v_thresh_95 THEN
                v_dispatch_mode := 'google_overflow';
                v_chosen_provider := 'gcs';
                v_chosen_bucket := 'cross-testers.firebasestorage.app';
            ELSE
                v_chosen_provider := 'supabase';
                v_chosen_bucket := 'report-screenshots';
            END IF;
        ELSE
            IF (v_used_bytes + v_active_reserved) < v_thresh_90 AND (v_used_bytes + v_active_reserved + v_needed_bytes) < v_thresh_95 THEN
                v_dispatch_mode := 'supabase_preferred';
                v_chosen_provider := 'supabase';
                v_chosen_bucket := 'report-screenshots';
            ELSE
                v_chosen_provider := 'gcs';
                v_chosen_bucket := 'cross-testers.firebasestorage.app';
            END IF;
        END IF;
    END IF;

    v_object_path := format('reports/%s/%s/%s.jpg', p_campaign_id, v_user_id, p_upload_id);
    IF p_has_thumbnail THEN
        v_thumb_path := format('reports/%s/%s/thumb_%s.jpg', p_campaign_id, v_user_id, p_upload_id);
    END IF;

    IF v_chosen_provider = 'supabase' THEN
        v_active_reserved := v_active_reserved + v_needed_bytes;
        UPDATE public.storage_organization_usage
        SET active_reserved_bytes = v_active_reserved,
            dispatch_mode = v_dispatch_mode,
            updated_at = now()
        WHERE id = 1;

        INSERT INTO public.report_media_uploads (
            upload_id, user_id, campaign_id, attachment_id, provider, bucket,
            object_path, thumbnail_object_path, reserved_bytes, status, reservation_expires_at
        ) VALUES (
            p_upload_id, v_user_id, p_campaign_id, p_attachment_id, 'supabase', v_chosen_bucket,
            v_object_path, v_thumb_path, v_needed_bytes, 'reserved', now() + interval '30 minutes'
        )
        ON CONFLICT (user_id, upload_id) DO UPDATE SET
            provider = excluded.provider,
            bucket = excluded.bucket,
            object_path = excluded.object_path,
            thumbnail_object_path = excluded.thumbnail_object_path,
            reserved_bytes = excluded.reserved_bytes,
            status = 'reserved',
            reservation_expires_at = excluded.reservation_expires_at,
            updated_at = now()
        RETURNING id INTO v_reservation_id;
    ELSE
        INSERT INTO public.report_media_uploads (
            upload_id, user_id, campaign_id, attachment_id, provider, bucket,
            object_path, thumbnail_object_path, reserved_bytes, status, reservation_expires_at
        ) VALUES (
            p_upload_id, v_user_id, p_campaign_id, p_attachment_id, 'gcs', v_chosen_bucket,
            v_object_path, v_thumb_path, 0, 'reserved', now() + interval '30 minutes'
        )
        ON CONFLICT (user_id, upload_id) DO UPDATE SET
            provider = excluded.provider,
            bucket = excluded.bucket,
            object_path = excluded.object_path,
            thumbnail_object_path = excluded.thumbnail_object_path,
            reserved_bytes = 0,
            status = 'reserved',
            reservation_expires_at = excluded.reservation_expires_at,
            updated_at = now()
        RETURNING id INTO v_reservation_id;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'upload_id', p_upload_id,
        'provider', v_chosen_provider,
        'bucket', v_chosen_bucket,
        'object_path', v_object_path,
        'thumbnail_object_path', v_thumb_path,
        'reservation_id', v_reservation_id,
        'pinned', false,
        'status', 'reserved',
        'dispatch_mode', v_dispatch_mode
    );
END;
$function$;

-- ----------------------------------------------------------------------------
-- 6. Hardened _submit_tester_report_core (Preserving 091 Business Core)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public._submit_tester_report_core(
    p_submission_context text,
    p_campaign_id uuid,
    p_content text,
    p_rating integer,
    p_client_submit_id uuid,
    p_screenshot_url text,
    p_image_hash text,
    p_thumbnail_url text,
    p_mission_id uuid,
    p_attachments jsonb DEFAULT NULL::jsonb,
    p_expected_date date DEFAULT NULL::date
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
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

    -- Dual Storage hardening variables (092)
    v_media_rec record;
    v_storage_obj record;
    v_path_match text;
    v_real_created_at timestamptz;
    v_actual_bytes bigint;
    v_total_finalized_bytes bigint := 0;

    -- Dual Storage specific variables
    v_now_utc timestamptz := now();
    v_provider text;
    v_view_exp timestamptz;
    v_clean_due timestamptz;
    v_del_dead timestamptz;
    v_att_id uuid;
    v_att_provider text;
    v_att_view_exp timestamptz;
    v_upload_rec record;
    v_freed_bytes bigint := 0;
BEGIN
    v_policy := public._get_effective_business_policy_row(v_today);
    v_provisional_reward := v_policy.report_provisional_reward;

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    -- Strict explicit submission context
    IF p_submission_context IS NULL OR p_submission_context NOT IN ('app_test', 'mission')
       OR (p_submission_context = 'mission') <> (p_mission_id IS NOT NULL) THEN
        RAISE EXCEPTION 'INVALID_REPORT_CONTEXT';
    END IF;
    v_submission_context := p_submission_context;

    -- Advisory lock for client submission idempotency
    IF p_client_submit_id IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || p_client_submit_id::text, 64));
    END IF;

    IF p_client_submit_id IS NOT NULL AND EXISTS(SELECT 1 FROM public.report_cancelled_requests WHERE user_id=v_user_id AND client_submit_id=p_client_submit_id) THEN
        RAISE EXCEPTION 'REPORT_REQUEST_REQUIRES_REVIEW';
    END IF;

    -- Validate attachments array if provided
    IF p_attachments IS NOT NULL THEN
        IF p_client_submit_id IS NULL THEN
            RAISE EXCEPTION 'CLIENT_SUBMIT_ID_REQUIRED';
        END IF;
        IF jsonb_typeof(p_attachments) <> 'array' THEN
            RAISE EXCEPTION 'ATTACHMENTS_MUST_BE_ARRAY';
        END IF;
        v_att_count := jsonb_array_length(p_attachments);
        IF v_att_count > 3 THEN
            RAISE EXCEPTION 'TOO_MANY_ATTACHMENTS';
        END IF;
        IF v_att_count < 1 THEN
            RAISE EXCEPTION 'ATTACHMENTS_EMPTY: attachments array cannot be empty';
        END IF;

        FOR v_pos IN 0 .. (v_att_count - 1) LOOP
            v_att_item := p_attachments->v_pos;
            IF coalesce((v_att_item->>'position')::int, -1) <> v_pos THEN
                RAISE EXCEPTION 'ATTACHMENT_POSITION_INVALID';
            END IF;
            IF v_att_item->>'screenshot_url' IS NULL OR btrim(v_att_item->>'screenshot_url') = '' THEN
                RAISE EXCEPTION 'SCREENSHOT_URL_REQUIRED';
            END IF;
            IF NOT public.is_valid_report_screenshot_url(v_att_item->>'screenshot_url', p_campaign_id, v_user_id) THEN
                RAISE EXCEPTION 'INVALID_SCREENSHOT_URL';
            END IF;
            IF v_att_item->>'image_hash' IS NULL OR btrim(v_att_item->>'image_hash') = '' THEN
                RAISE EXCEPTION 'IMAGE_HASH_REQUIRED';
            END IF;
            IF NOT (lower(v_att_item->>'image_hash') ~ '^[0-9a-f]{16}$') THEN
                RAISE EXCEPTION 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            END IF;
            IF v_att_item->>'thumbnail_url' IS NOT NULL AND btrim(v_att_item->>'thumbnail_url') <> '' THEN
                IF NOT public.is_valid_report_thumbnail_url(v_att_item->>'thumbnail_url', p_campaign_id, v_user_id, v_att_item->>'screenshot_url') THEN
                    RAISE EXCEPTION 'INVALID_THUMBNAIL_URL';
                END IF;
            END IF;
        END LOOP;

        SELECT jsonb_agg(jsonb_build_object(
            'id', att->>'id',
            'position', (att->>'position')::int,
            'screenshot_url', att->>'screenshot_url',
            'thumbnail_url', att->>'thumbnail_url',
            'image_hash', lower(att->>'image_hash'),
            'sha256', att->>'sha256'
        ) ORDER BY (att->>'position')::int)::text
        INTO v_att_canonical
        FROM jsonb_array_elements(p_attachments) as att;
    END IF;

    -- Validate single screenshot if provided without attachments
    IF p_attachments IS NULL AND p_screenshot_url IS NOT NULL AND btrim(p_screenshot_url) <> '' THEN
        IF NOT public.is_valid_report_screenshot_url(p_screenshot_url, p_campaign_id, v_user_id) THEN
            RAISE EXCEPTION 'INVALID_SCREENSHOT_URL';
        END IF;
        IF p_image_hash IS NOT NULL AND btrim(p_image_hash) <> '' THEN
            IF NOT (lower(p_image_hash) ~ '^[0-9a-f]{16}$') THEN
                RAISE EXCEPTION 'INVALID_IMAGE_HASH: image_hash must be a 16-character hex dHash';
            END IF;
        END IF;
        IF p_thumbnail_url IS NOT NULL AND btrim(p_thumbnail_url) <> '' THEN
            IF NOT public.is_valid_report_thumbnail_url(p_thumbnail_url, p_campaign_id, v_user_id, p_screenshot_url) THEN
                RAISE EXCEPTION 'INVALID_THUMBNAIL_URL';
            END IF;
        END IF;
    END IF;

    -- Compute payload fingerprint
    IF p_attachments IS NOT NULL THEN
        v_fingerprint := encode(digest(format('v3|%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            v_submission_context,
            coalesce(p_mission_id::text, ''),
            coalesce(p_content, ''),
            v_rating,
            coalesce(v_att_canonical, ''),
            coalesce(p_expected_date::text, '')
        ), 'sha256'), 'hex');
    ELSE
        v_fingerprint := encode(digest(format('%s|%s|%s|%s|%s|%s|%s',
            p_campaign_id,
            coalesce(p_content, ''),
            coalesce(p_screenshot_url, ''),
            coalesce(p_rating, 5),
            coalesce(lower(p_image_hash), ''),
            coalesce(p_thumbnail_url, ''),
            coalesce(p_mission_id::text, '')
        ), 'sha256'), 'hex');
    END IF;

    -- Idempotency check via receipt ledger
    IF p_client_submit_id IS NOT NULL THEN
        SELECT * INTO v_receipt
        FROM public.report_submission_receipts
        WHERE user_id = v_user_id
          AND client_submit_id = p_client_submit_id
        LIMIT 1;

        IF v_receipt.id IS NOT NULL THEN
            IF v_receipt.campaign_id <> p_campaign_id THEN
                RAISE EXCEPTION 'CLIENT_SUBMIT_ID_MISMATCH';
            END IF;
            IF v_receipt.payload_fingerprint = v_fingerprint THEN
                RETURN v_receipt.response_json;
            END IF;
            RAISE EXCEPTION 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        END IF;

        -- Exact report idempotency check
        SELECT * INTO v_idempotent_report
        FROM public.tester_reports
        WHERE client_submit_id = p_client_submit_id
          AND user_id = v_user_id
        LIMIT 1;

        IF v_idempotent_report.id IS NOT NULL THEN
            IF v_idempotent_report.campaign_id <> p_campaign_id THEN
                RAISE EXCEPTION 'CLIENT_SUBMIT_ID_MISMATCH';
            END IF;

            SELECT coalesce(jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256,
                'provider', coalesce(a.storage_provider, 'gcs'),
                'view_expires_at', to_char(a.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'created_at', to_char(a.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            ) ORDER BY a.position), '[]'::jsonb) INTO v_attachments_json
            FROM public.tester_report_attachments a
            WHERE a.report_id = v_idempotent_report.id;

            IF jsonb_array_length(v_attachments_json) = 0 AND v_idempotent_report.screenshot_url IS NOT NULL THEN
                v_attachments_json := jsonb_build_array(jsonb_build_object(
                    'id', v_idempotent_report.id,
                    'position', 0,
                    'screenshot_url', v_idempotent_report.screenshot_url,
                    'thumbnail_url', v_idempotent_report.thumbnail_url,
                    'image_hash', v_idempotent_report.image_hash,
                    'sha256', null,
                    'provider', coalesce(v_idempotent_report.storage_provider, 'gcs'),
                    'view_expires_at', to_char(v_idempotent_report.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'created_at', to_char(v_idempotent_report.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                ));
            END IF;

            v_result := jsonb_build_object(
                'success', true,
                'report_id', v_idempotent_report.id,
                'status', v_idempotent_report.status,
                'provisional_granted', (v_idempotent_report.provisional_granted_credits > 0),
                'provisional_amount', v_idempotent_report.provisional_granted_credits,
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

            INSERT INTO public.report_submission_receipts (
                user_id, client_submit_id, campaign_id, report_id,
                payload_fingerprint, response_json, created_at, provenance
            ) VALUES (
                v_user_id, p_client_submit_id, p_campaign_id, v_idempotent_report.id,
                v_fingerprint, v_result, now(), 'exact_v1'
            ) ON CONFLICT (user_id, client_submit_id) DO NOTHING;

            RETURN v_result;
        END IF;
    END IF;

    -- Date precondition check
    IF p_expected_date IS NOT NULL AND p_expected_date <> v_today THEN
        IF v_submission_context = 'mission' AND EXISTS (
            SELECT 1 FROM public.missions
            WHERE id = p_mission_id AND campaign_id = p_campaign_id AND reward_mode = 'once' AND status IN ('open', 'full') AND ends_at > now()
        ) THEN
            NULL;
        ELSE
            RAISE EXCEPTION 'REPORT_DATE_MISMATCH: Ngay bao cao du kien khong khop voi may chu';
        END IF;
    END IF;

    SELECT * INTO v_camp FROM public.campaigns WHERE id = p_campaign_id;
    IF v_camp.id IS NULL THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;
    IF v_camp.owner_id = v_user_id THEN
        RAISE EXCEPTION 'CANNOT_TEST_OWN_APP';
    END IF;

    IF EXISTS (SELECT 1 FROM public.profiles WHERE id = v_user_id AND is_locked = true) THEN
        RAISE EXCEPTION 'USER_LOCKED';
    END IF;

    IF v_submission_context = 'mission' THEN
        IF v_camp.status NOT IN ('open', 'production', 'completed') THEN
            RAISE EXCEPTION 'CAMPAIGN_NOT_OPEN';
        END IF;
    ELSE
        IF v_camp.status <> 'open' THEN
            RAISE EXCEPTION 'CAMPAIGN_NOT_OPEN';
        END IF;
    END IF;

    -- Contextual advisory lock
    PERFORM pg_advisory_xact_lock(
        hashtextextended(format('%s:%s:%s:%s:%s', v_user_id, p_campaign_id, v_submission_context, coalesce(p_mission_id::text, ''), v_today), 0)
    );

    IF p_client_submit_id IS NOT NULL THEN
        SELECT * INTO v_receipt
        FROM public.report_submission_receipts
        WHERE user_id = v_user_id AND client_submit_id = p_client_submit_id
        LIMIT 1;

        IF v_receipt.id IS NOT NULL THEN
            IF v_receipt.campaign_id <> p_campaign_id THEN
                RAISE EXCEPTION 'CLIENT_SUBMIT_ID_MISMATCH';
            END IF;
            IF v_receipt.payload_fingerprint = v_fingerprint THEN
                RETURN v_receipt.response_json;
            END IF;
            RAISE EXCEPTION 'CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH';
        END IF;
    END IF;

    INSERT INTO public.campaign_members (campaign_id, user_id)
    VALUES (p_campaign_id, v_user_id)
    ON CONFLICT (campaign_id, user_id) DO NOTHING;

    -- Mission participation validation
    IF v_submission_context = 'mission' THEN
        SELECT * INTO v_mission FROM public.missions WHERE id = p_mission_id AND campaign_id = p_campaign_id;
        IF v_mission.id IS NULL THEN
            RAISE EXCEPTION 'MISSION_NOT_FOUND';
        END IF;
        IF v_mission.ends_at < now() THEN
            RAISE EXCEPTION 'MISSION_EXPIRED';
        END IF;

        SELECT * INTO v_mp
        FROM public.mission_participants
        WHERE mission_id = p_mission_id AND user_id = v_user_id;

        IF v_mp.id IS NULL THEN
            RAISE EXCEPTION 'NOT_PARTICIPATING_IN_MISSION';
        END IF;
    END IF;

    -- Lookup existing report
    IF v_submission_context = 'mission' THEN
        SELECT reward_mode INTO v_target_reward_mode FROM public.missions WHERE id = p_mission_id;
        IF v_target_reward_mode = 'once' THEN
            SELECT * INTO v_existing_report
            FROM public.tester_reports
            WHERE campaign_id = p_campaign_id
              AND user_id = v_user_id
              AND submission_context = 'mission'
              AND mission_id = p_mission_id
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE;
        ELSE
            SELECT * INTO v_existing_report
            FROM public.tester_reports
            WHERE campaign_id = p_campaign_id
              AND user_id = v_user_id
              AND submission_context = 'mission'
              AND mission_id = p_mission_id
              AND report_date = v_today
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE;
        END IF;
    ELSE
        SELECT * INTO v_existing_report
        FROM public.tester_reports
        WHERE campaign_id = p_campaign_id
          AND user_id = v_user_id
          AND submission_context = 'app_test'
          AND report_date = v_today
        ORDER BY created_at DESC
        LIMIT 1
        FOR UPDATE;
    END IF;

    -- Existing approved report check
    IF v_existing_report.id IS NOT NULL AND v_existing_report.status = 'approved' THEN
        IF p_client_submit_id IS NOT NULL AND v_existing_report.client_submit_id = p_client_submit_id THEN
            NULL;
        ELSE
            RAISE EXCEPTION 'REPORT_ALREADY_APPROVED';
        END IF;
    END IF;

    -- Resolve primary screenshot & provider
    IF p_attachments IS NOT NULL AND jsonb_array_length(p_attachments) > 0 THEN
        v_new_screenshot := true;
        v_effective_full := p_attachments->0->>'screenshot_url';
        v_final_thumb := p_attachments->0->>'thumbnail_url';
        v_final_hash := lower(p_attachments->0->>'image_hash');
    ELSIF p_screenshot_url IS NOT NULL AND btrim(p_screenshot_url) <> '' THEN
        v_new_screenshot := true;
        v_effective_full := p_screenshot_url;
        IF p_image_hash IS NULL OR btrim(p_image_hash) = '' THEN
            RAISE EXCEPTION 'IMAGE_HASH_REQUIRED';
        END IF;
        v_final_thumb := p_thumbnail_url;
        v_final_hash := lower(p_image_hash);
    ELSIF v_existing_report.id IS NOT NULL THEN
        v_effective_full := v_existing_report.screenshot_url;
        v_final_hash := v_existing_report.image_hash;
        v_final_thumb := v_existing_report.thumbnail_url;
        IF v_existing_report.status = 'rejected' THEN
            RAISE EXCEPTION 'REPORT_RESUBMIT_NEW_PHOTO_REQUIRED';
        END IF;
    ELSE
        RAISE EXCEPTION 'SCREENSHOT_URL_REQUIRED';
    END IF;

    -- Determine provider & view expiry for primary screenshot
    IF v_effective_full ~* 'report-screenshots' OR v_effective_full ~* 'supabase' THEN
        v_provider := 'supabase';
        v_view_exp := v_now_utc + interval '216 hours'; -- 9 days UTC
        v_clean_due := v_now_utc + interval '216 hours';
        v_del_dead := v_now_utc + interval '240 hours'; -- 10 days max deadline
    ELSE
        v_provider := 'gcs';
        v_view_exp := v_now_utc + interval '30 days';
        v_clean_due := v_now_utc + interval '29 days';
        v_del_dead := v_now_utc + interval '30 days';
    END IF;

    -- Bonus round snapshot for regular app_test
    IF v_submission_context = 'app_test' AND v_camp.owner_id <> v_user_id THEN
        SELECT * INTO v_camp FROM public.campaigns WHERE id = p_campaign_id FOR KEY SHARE;
        SELECT id INTO v_bonus_round_id
        FROM public.campaign_tester_bonus_rounds
        WHERE campaign_id = p_campaign_id AND status = 'active'
        FOR KEY SHARE;
    END IF;

    IF v_submission_context = 'mission' THEN
        v_target_mission_id := v_mission.id;
        v_target_mp_id := v_mp.id;
        v_target_title := v_mission.title;
        v_target_reward := v_mission.reward_credits;
        v_target_ends_at := v_mission.ends_at;
        v_target_reward_mode := v_mission.reward_mode;
    END IF;

    -- Upsert feedback
    INSERT INTO public.feedbacks (campaign_id, user_id, rating, comment, updated_at)
    VALUES (p_campaign_id, v_user_id, v_rating, coalesce(p_content, ''), now())
    ON CONFLICT (campaign_id, user_id)
    DO UPDATE SET
        rating = excluded.rating,
        comment = CASE WHEN coalesce(excluded.comment, '') <> '' THEN excluded.comment ELSE public.feedbacks.comment END,
        updated_at = now();

    PERFORM set_config('app.submitting_report', 'true', true);

    -- Insert or Update Report Row
    IF v_existing_report.id IS NOT NULL THEN
        v_report_id := v_existing_report.id;
        v_bonus_round_id := coalesce(v_existing_report.tester_bonus_round_id, v_bonus_round_id);

        IF v_existing_report.status = 'rejected' THEN
            v_regrant := coalesce(v_existing_report.provisional_granted_credits, v_provisional_reward);
            IF v_existing_report.provisional_clawed > 0 THEN
                UPDATE public.profiles
                SET credits = credits + v_regrant,
                    last_active_date = v_today,
                    missed_streak = 0
                WHERE id = v_user_id
                RETURNING credits INTO v_new_balance;

                INSERT INTO public.credit_logs (user_id, amount, action, note, balance)
                VALUES (
                    v_user_id, v_regrant, 'regrant',
                    format('Tạm ứng lại %s Credit sau khi nộp lại báo cáo hợp lệ', v_regrant),
                    v_new_balance
                );
            END IF;
        END IF;

        UPDATE public.tester_reports
        SET content = coalesce(p_content, ''),
            screenshot_url = v_effective_full,
            thumbnail_url = v_final_thumb,
            rating = v_rating,
            status = 'pending',
            image_hash = v_final_hash,
            client_submit_id = coalesce(p_client_submit_id, client_submit_id),
            rejection_reason = NULL,
            reason_code = NULL,
            storage_provider = v_provider,
            view_expires_at = v_view_exp,
            media_expired = false,
            updated_at = now()
        WHERE id = v_report_id;

        v_status := 'pending';
    ELSE
        -- New Report Creation
        IF v_submission_context = 'app_test' THEN
            SELECT exists (
                SELECT 1 FROM public.checkins
                WHERE campaign_id = p_campaign_id AND user_id = v_user_id AND checkin_date = v_today
            ) INTO v_already_checked_in;

            IF v_already_checked_in THEN
                INSERT INTO public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    mission_reward_mode, review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                    tester_bonus_round_id, tester_bonus_awarded, storage_provider, view_expires_at
                ) VALUES (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                    v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null, null, null, null, null, null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                    v_bonus_round_id, 0, v_provider, v_view_exp
                ) RETURNING id INTO v_report_id;

                v_status := 'pending';
                v_provisional_granted := false;
                v_provisional_amount := 0;
            ELSE
                IF v_provisional_reward > 0 THEN
                    UPDATE public.profiles
                    SET credits = credits + v_provisional_reward,
                        last_active_date = v_today,
                        missed_streak = 0
                    WHERE id = v_user_id
                    RETURNING credits INTO v_new_balance;

                    INSERT INTO public.credit_logs (user_id, amount, action, note, balance)
                    VALUES (
                        v_user_id, v_provisional_reward, 'test_provisional',
                        format('Tạm nhận %s Credit sau khi nộp kết quả test (chờ chủ app duyệt)', v_provisional_reward),
                        v_new_balance
                    );

                    v_provisional_granted := true;
                    v_provisional_amount := v_provisional_reward;
                ELSE
                    v_provisional_granted := false;
                    v_provisional_amount := 0;
                END IF;

                INSERT INTO public.tester_reports (
                    campaign_id, user_id, report_type, content, screenshot_url,
                    thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                    provisional_granted_credits, image_hash, client_submit_id, created_at,
                    submission_context, mission_id, mission_participant_id,
                    mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                    mission_reward_mode, review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                    tester_bonus_round_id, tester_bonus_awarded, storage_provider, view_expires_at
                ) VALUES (
                    p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                    v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0,
                    v_provisional_reward, v_final_hash, p_client_submit_id, now(),
                    'app_test', null, null, null, null, null, null, null,
                    v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus, v_policy.id, v_today,
                    v_bonus_round_id, 0, v_provider, v_view_exp
                ) RETURNING id INTO v_report_id;

                INSERT INTO public.checkins (campaign_id, user_id, checkin_date, created_at, note)
                VALUES (p_campaign_id, v_user_id, v_today, now(), 'Điểm danh kiểm thử ứng dụng (tạm tính)')
                ON CONFLICT (campaign_id, user_id, checkin_date) DO NOTHING;

                v_status := 'pending';
                v_provisional_granted := (v_provisional_reward > 0);
                v_provisional_amount := v_provisional_reward;
            END IF;
        ELSE
            -- Mission report creation
            INSERT INTO public.tester_reports (
                campaign_id, user_id, report_type, content, screenshot_url,
                thumbnail_url, rating, status, bonus_awarded, provisional_clawed,
                provisional_granted_credits, image_hash, client_submit_id, created_at,
                submission_context, mission_id, mission_participant_id,
                mission_title, mission_reward_credits, mission_ends_at, mission_submitted_at,
                mission_reward_mode, review_tester_bonus, review_owner_bonus, business_policy_id, report_date,
                tester_bonus_round_id, tester_bonus_awarded, storage_provider, view_expires_at
            ) VALUES (
                p_campaign_id, v_user_id, 'feedback', coalesce(p_content, ''),
                v_effective_full, v_final_thumb, v_rating, 'pending', 0, 0, 0,
                v_final_hash, p_client_submit_id, now(),
                'mission', v_target_mission_id, v_target_mp_id,
                v_target_title, v_target_reward, v_target_ends_at, now(),
                v_target_reward_mode, v_policy.report_review_tester_bonus, v_policy.report_review_owner_bonus,
                v_policy.id, v_today, null, 0, v_provider, v_view_exp
            ) RETURNING id INTO v_report_id;

            v_status := 'pending';
            v_provisional_granted := false;
            v_provisional_amount := 0;
        END IF;
    END IF;

    -- Attachments Insertion & Storage Upload Finalization
    DELETE FROM public.tester_report_attachments WHERE report_id = v_report_id;

    IF p_attachments IS NOT NULL AND jsonb_array_length(p_attachments) > 0 THEN
        FOR v_att_elem IN SELECT * FROM jsonb_to_recordset(p_attachments) AS x(
            id uuid,
            position int,
            screenshot_url text,
            thumbnail_url text,
            image_hash text,
            sha256 text
        ) LOOP
            v_att_id := coalesce(v_att_elem.id, gen_random_uuid());
            IF v_att_elem.screenshot_url ~* 'report-screenshots' OR v_att_elem.screenshot_url ~* 'supabase' THEN
                v_att_provider := 'supabase';
                v_path_match := substring(split_part(v_att_elem.screenshot_url, '?', 1) from 'reports/[^?#]+');

                -- Provenance check 1: Active reservation required
                SELECT * INTO v_media_rec
                FROM public.report_media_uploads
                WHERE user_id = v_user_id
                  AND campaign_id = p_campaign_id
                  AND provider = 'supabase'
                  AND (object_path = v_path_match OR upload_id = v_att_id::text)
                  AND status IN ('reserved', 'uploaded')
                FOR UPDATE;

                IF v_media_rec.id IS NULL THEN
                    RAISE EXCEPTION 'SUBMISSION_PROVENANCE_FAILED: No active reservation for Supabase media %', v_path_match;
                END IF;

                -- Provenance check 2: Storage object existence required
                SELECT * INTO v_storage_obj
                FROM storage.objects
                WHERE bucket_id = 'report-screenshots'
                  AND name = v_media_rec.object_path;

                IF v_storage_obj.id IS NULL THEN
                    RAISE EXCEPTION 'SUBMISSION_PROVENANCE_FAILED: Storage object not found for %', v_media_rec.object_path;
                END IF;

                -- Authoritative timestamps from storage.objects (zero clock drift)
                v_real_created_at := coalesce(v_storage_obj.created_at, v_media_rec.created_at, v_now_utc);
                v_att_view_exp := v_real_created_at + interval '216 hours';
                v_clean_due := v_real_created_at + interval '216 hours';
                v_del_dead := v_real_created_at + interval '240 hours';

                IF v_clean_due <= now() THEN
                    RAISE EXCEPTION 'MEDIA_EXPIRED: Screenshot exceeded 216 hours retention window';
                END IF;

                v_actual_bytes := coalesce((v_storage_obj.metadata->>'size')::bigint, v_media_rec.size_bytes, 500000);
                v_total_finalized_bytes := v_total_finalized_bytes + v_actual_bytes;
            ELSE
                v_att_provider := 'gcs';
                v_real_created_at := v_now_utc;
                v_att_view_exp := v_now_utc + interval '30 days';
                v_clean_due := v_now_utc + interval '29 days';
                v_del_dead := v_now_utc + interval '30 days';
                v_actual_bytes := 0;
            END IF;

            INSERT INTO public.tester_report_attachments (
                id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256,
                created_at, storage_provider, view_expires_at, media_expired
            ) VALUES (
                v_att_id,
                v_report_id,
                v_att_elem.position,
                v_att_elem.screenshot_url,
                v_att_elem.thumbnail_url,
                lower(v_att_elem.image_hash),
                v_att_elem.sha256,
                v_real_created_at,
                v_att_provider,
                v_att_view_exp,
                false
            );

            -- Finalize reservation record in report_media_uploads
            IF v_att_provider = 'supabase' THEN
                UPDATE public.report_media_uploads
                SET status = 'finalized',
                    report_id = v_report_id,
                    attachment_id = v_att_id,
                    server_created_at = v_real_created_at,
                    view_expires_at = v_att_view_exp,
                    cleanup_due_at = v_clean_due,
                    delete_deadline_at = v_del_dead,
                    size_bytes = v_actual_bytes,
                    reserved_bytes = 0,
                    updated_at = now()
                WHERE id = v_media_rec.id;
            ELSE
                UPDATE public.report_media_uploads
                SET status = 'finalized',
                    report_id = v_report_id,
                    attachment_id = v_att_id,
                    server_created_at = v_now_utc,
                    view_expires_at = v_att_view_exp,
                    cleanup_due_at = v_clean_due,
                    delete_deadline_at = v_del_dead,
                    reserved_bytes = 0,
                    updated_at = now()
                WHERE user_id = v_user_id
                  AND (
                      upload_id = v_att_id::text
                      OR object_path LIKE ('%' || substring(split_part(v_att_elem.screenshot_url, '?', 1) from '[^/]+$'))
                  );
            END IF;
        END LOOP;
    ELSE
        v_att_id := gen_random_uuid();
        IF v_provider = 'supabase' THEN
            v_path_match := substring(split_part(v_effective_full, '?', 1) from 'reports/[^?#]+');
            SELECT * INTO v_media_rec
            FROM public.report_media_uploads
            WHERE user_id = v_user_id
              AND campaign_id = p_campaign_id
              AND provider = 'supabase'
              AND (object_path = v_path_match OR upload_id = coalesce(p_client_submit_id::text, v_report_id::text))
              AND status IN ('reserved', 'uploaded')
            FOR UPDATE;

            IF v_media_rec.id IS NULL THEN
                RAISE EXCEPTION 'SUBMISSION_PROVENANCE_FAILED: No active reservation for Supabase media %', v_path_match;
            END IF;

            SELECT * INTO v_storage_obj
            FROM storage.objects
            WHERE bucket_id = 'report-screenshots'
              AND name = v_media_rec.object_path;

            IF v_storage_obj.id IS NULL THEN
                RAISE EXCEPTION 'SUBMISSION_PROVENANCE_FAILED: Storage object not found for %', v_media_rec.object_path;
            END IF;

            v_real_created_at := coalesce(v_storage_obj.created_at, v_media_rec.created_at, v_now_utc);
            v_view_exp := v_real_created_at + interval '216 hours';
            v_clean_due := v_real_created_at + interval '216 hours';
            v_del_dead := v_real_created_at + interval '240 hours';

            IF v_clean_due <= now() THEN
                RAISE EXCEPTION 'MEDIA_EXPIRED: Screenshot exceeded 216 hours retention window';
            END IF;

            v_actual_bytes := coalesce((v_storage_obj.metadata->>'size')::bigint, v_media_rec.size_bytes, 500000);
            v_total_finalized_bytes := v_total_finalized_bytes + v_actual_bytes;
        ELSE
            v_real_created_at := v_now_utc;
            v_view_exp := v_now_utc + interval '30 days';
            v_clean_due := v_now_utc + interval '29 days';
            v_del_dead := v_now_utc + interval '30 days';
            v_actual_bytes := 0;
        END IF;

        INSERT INTO public.tester_report_attachments (
            id, report_id, position, screenshot_url, thumbnail_url, image_hash, sha256,
            created_at, storage_provider, view_expires_at, media_expired
        ) VALUES (
            v_att_id,
            v_report_id,
            0,
            v_effective_full,
            v_final_thumb,
            v_final_hash,
            null,
            v_real_created_at,
            v_provider,
            v_view_exp,
            false
        );

        IF v_provider = 'supabase' THEN
            UPDATE public.report_media_uploads
            SET status = 'finalized',
                report_id = v_report_id,
                attachment_id = v_att_id,
                server_created_at = v_real_created_at,
                view_expires_at = v_view_exp,
                cleanup_due_at = v_clean_due,
                delete_deadline_at = v_del_dead,
                size_bytes = v_actual_bytes,
                reserved_bytes = 0,
                updated_at = now()
            WHERE id = v_media_rec.id;
        ELSE
            UPDATE public.report_media_uploads
            SET status = 'finalized',
                report_id = v_report_id,
                attachment_id = v_att_id,
                server_created_at = v_now_utc,
                view_expires_at = v_view_exp,
                cleanup_due_at = v_clean_due,
                delete_deadline_at = v_del_dead,
                reserved_bytes = 0,
                updated_at = now()
            WHERE user_id = v_user_id
              AND (
                  upload_id = coalesce(p_client_submit_id::text, v_report_id::text)
                  OR object_path LIKE ('%' || substring(split_part(v_effective_full, '?', 1) from '[^/]+$'))
              );
        END IF;
    END IF;

    -- Update active reserved bytes and add finalized bytes on quota ledger
    SELECT coalesce(sum(reserved_bytes), 0)
    INTO v_freed_bytes
    FROM public.report_media_uploads
    WHERE status IN ('reserved', 'uploaded');

    UPDATE public.storage_organization_usage
    SET used_storage_bytes = used_storage_bytes + v_total_finalized_bytes,
        active_reserved_bytes = v_freed_bytes,
        updated_at = now()
    WHERE id = 1;

    UPDATE public.profiles
    SET last_active_date = v_today, missed_streak = 0
    WHERE id = v_user_id;

    IF v_submission_context = 'app_test' THEN
        PERFORM public.refresh_daily_completion(v_user_id, v_today);
    END IF;

    -- Construct canonical response JSON
    SELECT
        submission_context, mission_id, mission_title,
        mission_reward_credits, mission_ends_at, mission_reward_mode,
        screenshot_url, image_hash, thumbnail_url, report_date, rating,
        tester_bonus_round_id
    INTO
        v_res_context, v_res_mission_id, v_res_title,
        v_res_reward, v_res_ends_at, v_res_reward_mode,
        v_final_url, v_final_hash, v_final_thumb, v_today, v_rating,
        v_bonus_round_id
    FROM public.tester_reports
    WHERE id = v_report_id;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'id', a.id,
        'position', a.position,
        'screenshot_url', a.screenshot_url,
        'thumbnail_url', a.thumbnail_url,
        'image_hash', a.image_hash,
        'sha256', a.sha256,
        'provider', coalesce(a.storage_provider, 'gcs'),
        'view_expires_at', to_char(a.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'created_at', to_char(a.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) ORDER BY a.position), '[]'::jsonb) INTO v_attachments_json
    FROM public.tester_report_attachments a
    WHERE a.report_id = v_report_id;

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

    IF p_client_submit_id IS NOT NULL THEN
        INSERT INTO public.report_submission_receipts (
            user_id, client_submit_id, campaign_id, report_id,
            payload_fingerprint, response_json, created_at, provenance
        ) VALUES (
            v_user_id, p_client_submit_id, p_campaign_id, v_report_id,
            v_fingerprint, v_result, now(), 'exact_v1'
        ) ON CONFLICT (user_id, client_submit_id) DO NOTHING;
    END IF;

    RETURN v_result;
END;
$function$;

-- ----------------------------------------------------------------------------
-- 7. Hardened Cleanup RPCs (Per RPC_CONTRACT.md)
-- ----------------------------------------------------------------------------
-- 7a. 3-Argument Implementation: get_expired_report_media_for_cleanup
CREATE OR REPLACE FUNCTION public.get_expired_report_media_for_cleanup(
    p_limit integer,
    p_lease_seconds integer,
    p_dry_run boolean
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_limit int := greatest(1, least(coalesce(p_limit, 50), 200));
    v_lease int := greatest(1, least(coalesce(p_lease_seconds, 300), 3600));
    v_results jsonb := '[]'::jsonb;
    v_ids uuid[];
    v_new_token uuid;
    v_new_expiry timestamptz;
BEGIN
    -- Select candidates strictly for Supabase provider and report-screenshots bucket
    WITH candidates AS (
        SELECT id, upload_id, user_id, campaign_id, report_id, provider, bucket,
               object_path, thumbnail_object_path, size_bytes, thumbnail_size_bytes,
               server_created_at, cleanup_due_at, delete_deadline_at, status, updated_at,
               lease_token, lease_expires_at
        FROM public.report_media_uploads
        WHERE provider = 'supabase'
          AND bucket = 'report-screenshots'
          AND (
              -- Finalized items ready for cleanup
              (status = 'finalized' AND (cleanup_due_at <= now() OR delete_deadline_at <= now()))
              -- Orphan or expired reservations
              OR (status = 'expired')
              OR (status IN ('reserved', 'uploaded') AND reservation_expires_at <= now())
              -- Stale deleting items where lease expired or timed out
              OR (status = 'deleting' AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp() OR updated_at < now() - interval '10 seconds'))
          )
          AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp() OR updated_at < now() - interval '10 seconds')
        ORDER BY
            CASE WHEN delete_deadline_at <= now() THEN 0 ELSE 1 END,
            cleanup_due_at ASC
        LIMIT v_limit
        FOR UPDATE SKIP LOCKED
    )
    SELECT array_agg(c.id) INTO v_ids FROM candidates c;

    IF v_ids IS NULL OR array_length(v_ids, 1) = 0 THEN
        RETURN '[]'::jsonb;
    END IF;

    -- If not dry-run, claim lease and update timestamps without changing status
    IF NOT coalesce(p_dry_run, false) THEN
        v_new_token := gen_random_uuid();
        v_new_expiry := clock_timestamp() + (v_lease || ' seconds')::interval;

        UPDATE public.report_media_uploads
        SET lease_token = v_new_token,
            lease_expires_at = v_new_expiry,
            retry_count = retry_count + 1,
            updated_at = now()
        WHERE id = ANY(v_ids);

        SELECT coalesce(jsonb_agg(
            jsonb_build_object(
                'id', m.id,
                'upload_id', m.upload_id,
                'user_id', m.user_id,
                'campaign_id', m.campaign_id,
                'report_id', m.report_id,
                'provider', m.provider,
                'bucket', m.bucket,
                'object_path', m.object_path,
                'thumbnail_object_path', m.thumbnail_object_path,
                'size_bytes', m.size_bytes,
                'thumbnail_size_bytes', m.thumbnail_size_bytes,
                'server_created_at', m.server_created_at,
                'cleanup_due_at', m.cleanup_due_at,
                'delete_deadline_at', m.delete_deadline_at,
                'status', m.status,
                'lease_token', m.lease_token,
                'lease_expires_at', m.lease_expires_at
            )
        ), '[]'::jsonb)
        INTO v_results
        FROM public.report_media_uploads m
        WHERE m.id = ANY(v_ids);
    ELSE
        -- Dry-run: read-only, return without mutating lease or status
        SELECT coalesce(jsonb_agg(
            jsonb_build_object(
                'id', m.id,
                'upload_id', m.upload_id,
                'user_id', m.user_id,
                'campaign_id', m.campaign_id,
                'report_id', m.report_id,
                'provider', m.provider,
                'bucket', m.bucket,
                'object_path', m.object_path,
                'thumbnail_object_path', m.thumbnail_object_path,
                'size_bytes', m.size_bytes,
                'thumbnail_size_bytes', m.thumbnail_size_bytes,
                'server_created_at', m.server_created_at,
                'cleanup_due_at', m.cleanup_due_at,
                'delete_deadline_at', m.delete_deadline_at,
                'status', m.status,
                'lease_token', m.lease_token,
                'lease_expires_at', m.lease_expires_at
            )
        ), '[]'::jsonb)
        INTO v_results
        FROM public.report_media_uploads m
        WHERE m.id = ANY(v_ids);
    END IF;

    RETURN v_results;
END;
$function$;

-- 7b. 2-Argument Backward Compatibility Wrapper (Preserves exact 091 defaults)
CREATE OR REPLACE FUNCTION public.get_expired_report_media_for_cleanup(
    p_limit integer DEFAULT 50,
    p_lease_seconds integer DEFAULT 300
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
BEGIN
    RETURN public.get_expired_report_media_for_cleanup(p_limit, p_lease_seconds, false);
END;
$function$;

REVOKE ALL ON FUNCTION public.get_expired_report_media_for_cleanup(integer, integer, boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_expired_report_media_for_cleanup(integer, integer, boolean) TO service_role;

REVOKE ALL ON FUNCTION public.get_expired_report_media_for_cleanup(integer, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_expired_report_media_for_cleanup(integer, integer) TO service_role;

-- 7c. New Overload: record_report_media_deleted with UUID, lease token, and path verification
CREATE OR REPLACE FUNCTION public.record_report_media_deleted(
    p_media_id uuid,
    p_lease_token uuid,
    p_deleted_paths text[]
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_rec record;
    v_report_id uuid;
    v_bytes_to_free bigint;
BEGIN
    SELECT * INTO v_rec
    FROM public.report_media_uploads
    WHERE id = p_media_id
    FOR UPDATE;

    IF v_rec.id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'reason', 'RECORD_NOT_FOUND');
    END IF;

    IF v_rec.status = 'deleted' THEN
        RETURN jsonb_build_object('success', true, 'already_deleted', true);
    END IF;

    -- Verify lease token
    IF p_lease_token IS NOT NULL AND v_rec.lease_token IS NOT NULL AND v_rec.lease_token <> p_lease_token THEN
        RETURN jsonb_build_object('success', false, 'reason', 'LEASE_TOKEN_MISMATCH');
    END IF;

    -- Confirm all paths are accounted for
    IF p_deleted_paths IS NOT NULL THEN
        IF v_rec.object_path IS NOT NULL AND NOT (v_rec.object_path = ANY(p_deleted_paths)) THEN
            RETURN jsonb_build_object('success', false, 'reason', 'MISSING_MAIN_OBJECT_CONFIRMATION');
        END IF;
        IF v_rec.thumbnail_object_path IS NOT NULL AND NOT (v_rec.thumbnail_object_path = ANY(p_deleted_paths)) THEN
            RETURN jsonb_build_object('success', false, 'reason', 'MISSING_THUMBNAIL_CONFIRMATION');
        END IF;
    END IF;

    v_bytes_to_free := coalesce(v_rec.size_bytes, 0) + coalesce(v_rec.thumbnail_size_bytes, 0);

    UPDATE public.report_media_uploads
    SET status = 'deleted',
        deleted_at = now(),
        updated_at = now()
    WHERE id = v_rec.id;

    -- Atomically decrement quota
    UPDATE public.storage_organization_usage
    SET used_storage_bytes = greatest(0, used_storage_bytes - v_bytes_to_free),
        updated_at = now()
    WHERE id = 1;

    IF v_rec.report_id IS NOT NULL THEN
        v_report_id := v_rec.report_id;
        UPDATE public.tester_report_attachments
        SET media_expired = true
        WHERE report_id = v_report_id
          AND (id = v_rec.attachment_id OR screenshot_url LIKE ('%' || v_rec.object_path));

        IF NOT EXISTS (
            SELECT 1 FROM public.tester_report_attachments
            WHERE report_id = v_report_id AND media_expired = false
        ) THEN
            UPDATE public.tester_reports
            SET media_expired = true
            WHERE id = v_report_id;
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'media_id', v_rec.id,
        'upload_id', v_rec.upload_id,
        'bytes_freed', v_bytes_to_free
    );
END;
$function$;

-- 7d. Legacy Overload: record_report_media_deleted(p_upload_id, p_bytes_freed)
-- Idempotent, locked, strict single record constraint, preserves parameter names
CREATE OR REPLACE FUNCTION public.record_report_media_deleted(
    p_upload_id text,
    p_bytes_freed bigint DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_rec record;
    v_report_id uuid;
    v_count int;
    v_freed bigint;
BEGIN
    SELECT count(*)::int INTO v_count
    FROM public.report_media_uploads
    WHERE upload_id = p_upload_id;

    IF v_count = 0 THEN
        RETURN jsonb_build_object('success', false, 'reason', 'RECORD_NOT_FOUND');
    END IF;

    IF v_count > 1 THEN
        RAISE EXCEPTION 'AMBIGUOUS_UPLOAD_ID: Multiple records found for upload_id %', p_upload_id;
    END IF;

    SELECT * INTO v_rec
    FROM public.report_media_uploads
    WHERE upload_id = p_upload_id
    FOR UPDATE;

    IF v_rec.status = 'deleted' THEN
        RETURN jsonb_build_object('success', true, 'already_deleted', true);
    END IF;

    v_freed := coalesce(p_bytes_freed, v_rec.size_bytes + v_rec.thumbnail_size_bytes, 0);

    UPDATE public.report_media_uploads
    SET status = 'deleted',
        deleted_at = now(),
        updated_at = now()
    WHERE id = v_rec.id;

    UPDATE public.storage_organization_usage
    SET used_storage_bytes = greatest(0, used_storage_bytes - v_freed),
        updated_at = now()
    WHERE id = 1;

    IF v_rec.report_id IS NOT NULL THEN
        v_report_id := v_rec.report_id;
        UPDATE public.tester_report_attachments
        SET media_expired = true
        WHERE report_id = v_report_id
          AND (id = v_rec.attachment_id OR screenshot_url LIKE ('%' || v_rec.object_path));

        IF NOT EXISTS (
            SELECT 1 FROM public.tester_report_attachments
            WHERE report_id = v_report_id AND media_expired = false
        ) THEN
            UPDATE public.tester_reports
            SET media_expired = true
            WHERE id = v_report_id;
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'upload_id', v_rec.upload_id,
        'bytes_freed', v_freed
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.record_report_media_deleted(uuid, uuid, text[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_report_media_deleted(uuid, uuid, text[]) TO service_role;

REVOKE ALL ON FUNCTION public.record_report_media_deleted(text, bigint) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_report_media_deleted(text, bigint) TO service_role;

-- ----------------------------------------------------------------------------
-- 8. Hardened reconcile_storage_organization_usage
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.reconcile_storage_organization_usage(
    p_total_capacity_bytes bigint,
    p_used_storage_bytes bigint,
    p_measured_projects jsonb,
    p_is_valid boolean
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_total_capacity bigint := coalesce(p_total_capacity_bytes, 1073741824);
    v_used_storage bigint := greatest(0, coalesce(p_used_storage_bytes, 0));
    v_reserved_bytes bigint := 0;
    v_thresh_95 bigint;
    v_thresh_90 bigint;
    v_dispatch_mode text;
    v_is_valid boolean := coalesce(p_is_valid, false);
BEGIN
    -- Fail safe if capacity is zero, negative, or metric is marked invalid
    IF v_total_capacity <= 0 OR NOT v_is_valid THEN
        v_is_valid := false;
        v_dispatch_mode := 'google_overflow';
    END IF;

    SELECT coalesce(sum(reserved_bytes), 0)
    INTO v_reserved_bytes
    FROM public.report_media_uploads
    WHERE status IN ('reserved', 'uploaded');

    v_thresh_95 := (v_total_capacity * 95) / 100;
    v_thresh_90 := (v_total_capacity * 90) / 100;

    IF v_is_valid THEN
        IF (v_used_storage + v_reserved_bytes) >= v_thresh_95 THEN
            v_dispatch_mode := 'google_overflow';
        ELSIF (v_used_storage + v_reserved_bytes) < v_thresh_90 THEN
            v_dispatch_mode := 'supabase_preferred';
        ELSE
            SELECT dispatch_mode INTO v_dispatch_mode
            FROM public.storage_organization_usage
            WHERE id = 1;
            v_dispatch_mode := coalesce(v_dispatch_mode, 'supabase_preferred');
        END IF;
    END IF;

    UPDATE public.storage_organization_usage
    SET total_capacity_bytes = v_total_capacity,
        used_storage_bytes = v_used_storage,
        active_reserved_bytes = v_reserved_bytes,
        dispatch_mode = v_dispatch_mode,
        is_org_metric_valid = v_is_valid,
        measured_projects = coalesce(p_measured_projects, '[]'::jsonb),
        last_reconciled_at = now(),
        updated_at = now()
    WHERE id = 1;

    RETURN jsonb_build_object(
        'success', true,
        'dispatch_mode', v_dispatch_mode,
        'used_storage_bytes', v_used_storage,
        'active_reserved_bytes', v_reserved_bytes,
        'total_capacity_bytes', v_total_capacity,
        'is_org_metric_valid', v_is_valid
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.reconcile_storage_organization_usage(bigint, bigint, jsonb, boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.reconcile_storage_organization_usage(bigint, bigint, jsonb, boolean) TO service_role;
