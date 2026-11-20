-- ============================================================================
-- MIGRATION 091: DUAL STORAGE REPORT SCREENSHOTS & ORG-LEVEL QUOTA DISPATCH
-- ============================================================================
-- Implementation based on: docs/plans/DUAL_STORAGE_REPORT_SCREENSHOTS_PLAN_2026-11-15.md
-- Requirements:
-- 1. Dual storage: legacy Google Cloud Storage preserved; new compressed screenshots prefer Supabase Storage.
-- 2. Organization-level storage quota coordination:
--    - Total quota B (bytes) across ALL projects/buckets in the organization.
--    - When (U + R) >= 0.95 * B, switch new uploads to Google Cloud ('google_overflow').
--    - Only return to Supabase ('supabase_preferred') when (U + R) < 0.90 * B and org metric is fresh/valid.
--    - In-between [90%, 95%), preserve existing dispatch mode (hysteresis).
--    - Strict integer byte math; reservation R prevents concurrent upload overflow.
--    - Fail-safe to Google Cloud if org metrics are invalid, missing, or stale.
-- 3. Supabase retention & cleanup:
--    - View access cut off and cleanup starts at 9 days (216 hours UTC).
--    - Physical deletion deadline: maximum 10 days (240 hours UTC) via Storage API.
--    - Thumbnail lifetime tied to parent; orphan/abandoned uploads cleaned up.
-- 4. Google Cloud retention preserved at current 30-day lifecycle.
-- 5. Per-attachment expiry, provider tracking, and durable idempotency preserved.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Storage Bucket & Schema Preparation
-- ----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS storage;

CREATE TABLE IF NOT EXISTS storage.buckets (
    id text PRIMARY KEY,
    name text NOT NULL,
    owner uuid,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    public boolean DEFAULT false,
    avif_autodetection boolean DEFAULT false,
    file_size_limit bigint,
    allowed_mime_types text[],
    owner_id text
);

CREATE TABLE IF NOT EXISTS storage.objects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_id text REFERENCES storage.buckets(id),
    name text,
    owner uuid,
    created_at timestamptz DEFAULT now(),
    updated_at timestamptz DEFAULT now(),
    last_accessed_at timestamptz DEFAULT now(),
    metadata jsonb,
    path_tokens text[] GENERATED ALWAYS AS (string_to_array(name, '/')) STORED
);

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('report-screenshots', 'report-screenshots', false, 2097152, ARRAY['image/jpeg', 'image/png', 'image/webp'])
ON CONFLICT (id) DO UPDATE SET
    public = false,
    file_size_limit = 2097152,
    allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp'];

-- ----------------------------------------------------------------------------
-- 2. Organization Usage & Dispatch State Table
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.storage_organization_usage (
    id int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    total_capacity_bytes bigint NOT NULL DEFAULT 1073741824, -- Default 1 GiB package quota
    used_storage_bytes bigint NOT NULL DEFAULT 0,
    active_reserved_bytes bigint NOT NULL DEFAULT 0,
    dispatch_mode text NOT NULL DEFAULT 'supabase_preferred' CHECK (dispatch_mode IN ('supabase_preferred', 'google_overflow')),
    last_reconciled_at timestamptz,
    measured_projects jsonb NOT NULL DEFAULT '[]'::jsonb,
    is_org_metric_valid boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO public.storage_organization_usage (
    id, total_capacity_bytes, used_storage_bytes, active_reserved_bytes, dispatch_mode, is_org_metric_valid
)
VALUES (1, 1073741824, 0, 0, 'supabase_preferred', false)
ON CONFLICT (id) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 3. Media Uploads & Reservation Ledger Table
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.report_media_uploads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    upload_id text NOT NULL,
    user_id uuid NOT NULL REFERENCES public.profiles(id),
    campaign_id uuid NOT NULL REFERENCES public.campaigns(id),
    report_id uuid REFERENCES public.tester_reports(id),
    attachment_id uuid,
    provider text NOT NULL CHECK (provider IN ('supabase', 'gcs')),
    bucket text NOT NULL,
    object_path text NOT NULL,
    thumbnail_object_path text,
    size_bytes bigint NOT NULL DEFAULT 0,
    thumbnail_size_bytes bigint NOT NULL DEFAULT 0,
    reserved_bytes bigint NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved', 'uploaded', 'finalized', 'expired', 'deleting', 'deleted', 'orphaned')),
    server_created_at timestamptz,
    view_expires_at timestamptz,
    cleanup_due_at timestamptz,
    delete_deadline_at timestamptz,
    reservation_expires_at timestamptz NOT NULL DEFAULT (now() + interval '30 minutes'),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_media_uploads_user_upload UNIQUE (user_id, upload_id)
);

CREATE INDEX IF NOT EXISTS idx_report_media_uploads_status_cleanup
    ON public.report_media_uploads(status, cleanup_due_at)
    WHERE status IN ('finalized', 'reserved', 'uploaded');

CREATE INDEX IF NOT EXISTS idx_report_media_uploads_reservation_expires
    ON public.report_media_uploads(status, reservation_expires_at)
    WHERE status IN ('reserved', 'uploaded');

CREATE INDEX IF NOT EXISTS idx_report_media_uploads_report_id
    ON public.report_media_uploads(report_id)
    WHERE report_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_report_media_uploads_object_path
    ON public.report_media_uploads(provider, bucket, object_path);

-- ----------------------------------------------------------------------------
-- 4. Schema Enhancements on Reports & Attachments
-- ----------------------------------------------------------------------------
ALTER TABLE public.tester_reports
    ADD COLUMN IF NOT EXISTS storage_provider text DEFAULT 'gcs',
    ADD COLUMN IF NOT EXISTS view_expires_at timestamptz;

ALTER TABLE public.tester_report_attachments
    ADD COLUMN IF NOT EXISTS storage_provider text DEFAULT 'gcs',
    ADD COLUMN IF NOT EXISTS view_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS media_expired boolean DEFAULT false;

-- ----------------------------------------------------------------------------
-- 5. Row Level Security Policies
-- ----------------------------------------------------------------------------
ALTER TABLE public.storage_organization_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.report_media_uploads ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS storage_org_usage_select_policy ON public.storage_organization_usage;
CREATE POLICY storage_org_usage_select_policy ON public.storage_organization_usage
    FOR SELECT TO authenticated USING (true);

DROP POLICY IF EXISTS report_media_uploads_select_policy ON public.report_media_uploads;
CREATE POLICY report_media_uploads_select_policy ON public.report_media_uploads
    FOR SELECT TO authenticated
    USING (
        user_id = auth.uid()
        OR EXISTS (SELECT 1 FROM public.campaigns c WHERE c.id = campaign_id AND c.owner_id = auth.uid())
        OR EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) IN ('admin', 'moderator'))
    );

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'storage' AND tablename = 'objects') THEN
        ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS storage_objects_read_policy ON storage.objects;
        CREATE POLICY storage_objects_read_policy ON storage.objects
            FOR SELECT TO authenticated
            USING (
                bucket_id = 'report-screenshots'
                AND (
                    owner = auth.uid()
                    OR EXISTS (
                        SELECT 1 FROM public.report_media_uploads m
                        WHERE m.bucket = bucket_id
                          AND (m.object_path = name OR m.thumbnail_object_path = name)
                          AND (
                              m.user_id = auth.uid()
                              OR EXISTS (SELECT 1 FROM public.campaigns c WHERE c.id = m.campaign_id AND c.owner_id = auth.uid())
                              OR EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND lower(p.role) IN ('admin', 'moderator'))
                          )
                    )
                )
            );

        DROP POLICY IF EXISTS storage_objects_insert_policy ON storage.objects;
        CREATE POLICY storage_objects_insert_policy ON storage.objects
            FOR INSERT TO authenticated
            WITH CHECK (
                bucket_id = 'report-screenshots'
                AND name ~* '^reports/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/.+$'
                AND EXISTS (
                    SELECT 1 FROM public.report_media_uploads m
                    WHERE m.user_id = auth.uid()
                      AND m.bucket = 'report-screenshots'
                      AND (m.object_path = name OR m.thumbnail_object_path = name)
                      AND m.status IN ('reserved', 'uploaded')
                      AND m.reservation_expires_at > now()
                )
                AND NOT EXISTS (
                    SELECT 1 FROM public.profiles p WHERE p.id = auth.uid() AND p.is_locked = true
                )
            );
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 6. Updated URL & Thumbnail Validators (Allowing Both Providers)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.is_valid_report_screenshot_url(p_url text, p_campaign_id uuid, p_user_id uuid)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
AS $function$
DECLARE
    v_clean_url text;
    v_bucket text;
    v_path text;
    v_matches text[];
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
    ELSIF v_clean_url ~* '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$', 'i');
        v_bucket := v_matches[2];
        v_path := v_matches[3];
    ELSIF v_clean_url ~* '^https://([^/]+)\.firebasestorage\.app/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/]+)\.firebasestorage\.app/o/(.+)$', 'i');
        v_bucket := v_matches[1] || '.firebasestorage.app';
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^https://storage\.googleapis\.com/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://storage\.googleapis\.com/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    -- 2. Parse theo Supabase Storage URL
    ELSIF v_clean_url ~* '^https://([^/]+)/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/]+)/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[2];
        v_path := v_matches[3];
    ELSIF v_clean_url ~* '^https://([^/]+)/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/]+)/storage/v1/object/public/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[2];
        v_path := v_matches[3];
    ELSIF v_clean_url ~* '^https://([^/]+)/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/]+)/storage/v1/object/([^/]+)/(.+)$', 'i');
        v_bucket := v_matches[2];
        v_path := v_matches[3];
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
IMMUTABLE
AS $function$
DECLARE
    v_clean_thumb text;
    v_clean_full text;
    v_thumb_bucket text;
    v_thumb_path text;
    v_matches text[];
    v_thumb_filename text;
    v_full_filename text;
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
    ELSIF v_clean_thumb ~* '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/]+)\.firebasestorage\.app/v0/b/([^/]+)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];
    ELSIF v_clean_thumb ~* '^https://([^/]+)\.firebasestorage\.app/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/]+)\.firebasestorage\.app/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[1] || '.firebasestorage.app';
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^https://storage\.googleapis\.com/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://storage\.googleapis\.com/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    -- Supabase format
    ELSIF v_clean_thumb ~* '^https://([^/]+)/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/]+)/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];
    ELSIF v_clean_thumb ~* '^https://([^/]+)/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/]+)/storage/v1/object/public/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];
    ELSIF v_clean_thumb ~* '^https://([^/]+)/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/]+)/storage/v1/object/([^/]+)/(.+)$', 'i');
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];
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
        v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '/thumb_[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
        OR v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '_thumb_[a-zA-Z0-9_\-\.]+\.(jpg|jpeg|png|webp)$')
    ) THEN
        RETURN false;
    END IF;

    IF p_screenshot_url IS NOT NULL THEN
        v_clean_full := split_part(split_part(p_screenshot_url, '?', 1), '#', 1);
        v_clean_full := replace(replace(v_clean_full, '%2F', '/'), '%2f', '/');

        v_thumb_filename := substring(v_thumb_path from '[^/]+$');
        v_full_filename := substring(v_clean_full from '[^/]+$');

        IF v_thumb_filename != ('thumb_' || v_full_filename) THEN
            RETURN false;
        END IF;
    END IF;

    RETURN true;
END;
$function$;

-- ----------------------------------------------------------------------------
-- 7. RPC: request_report_media_reservation
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
    v_profile record;
    v_existing record;
    v_usage record;
    v_needed_bytes bigint;
    v_chosen_provider text;
    v_chosen_bucket text;
    v_object_path text;
    v_thumb_path text := NULL;
    v_thresh_95 bigint;
    v_thresh_90 bigint;
    v_dispatch_mode text;
    v_active_reserved bigint;
    v_used_bytes bigint;
    v_total_capacity bigint;
    v_reservation_id uuid;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    IF p_campaign_id IS NULL OR p_upload_id IS NULL OR btrim(p_upload_id) = '' THEN
        RAISE EXCEPTION 'BAD_REQUEST: Missing campaign_id or upload_id';
    END IF;

    -- Validate user status
    SELECT * INTO v_profile FROM public.profiles WHERE id = v_user_id;
    IF v_profile.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND';
    END IF;
    IF coalesce(v_profile.is_locked, false) THEN
        RAISE EXCEPTION 'USER_LOCKED';
    END IF;

    -- Validate campaign status
    SELECT * INTO v_camp FROM public.campaigns WHERE id = p_campaign_id;
    IF v_camp.id IS NULL THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;
    IF v_camp.owner_id = v_user_id THEN
        RAISE EXCEPTION 'CANNOT_TEST_OWN_APP';
    END IF;

    -- 1. PINNING CHECK: If reservation already exists for this (user_id, upload_id), return it identically
    SELECT * INTO v_existing
    FROM public.report_media_uploads
    WHERE user_id = v_user_id AND upload_id = p_upload_id;

    IF v_existing.id IS NOT NULL AND v_existing.status IN ('reserved', 'uploaded', 'finalized') THEN
        RETURN jsonb_build_object(
            'success', true,
            'provider', v_existing.provider,
            'upload_id', v_existing.upload_id,
            'bucket', v_existing.bucket,
            'object_path', v_existing.object_path,
            'thumbnail_object_path', v_existing.thumbnail_object_path,
            'reservation_id', v_existing.id,
            'pinned', true,
            'status', v_existing.status,
            'dispatch_mode', v_existing.provider
        );
    END IF;

    -- 2. Lock organization quota row for atomic decision & reservation
    SELECT * INTO v_usage
    FROM public.storage_organization_usage
    WHERE id = 1
    FOR UPDATE;

    IF v_usage.id IS NULL THEN
        INSERT INTO public.storage_organization_usage (id, total_capacity_bytes, used_storage_bytes, active_reserved_bytes, dispatch_mode, is_org_metric_valid)
        VALUES (1, 1073741824, 0, 0, 'supabase_preferred', false)
        RETURNING * INTO v_usage;
    END IF;

    -- Clean expired reservations & recalculate active reserved bytes
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

    -- Compute byte requirement: main + thumbnail
    v_needed_bytes := greatest(50000, least(coalesce(p_estimated_size, 1000000), 2000000));
    IF p_has_thumbnail THEN
        v_needed_bytes := v_needed_bytes + 150000;
    END IF;

    -- Integer arithmetic thresholds
    v_thresh_95 := (v_total_capacity * 95) / 100;
    v_thresh_90 := (v_total_capacity * 90) / 100;

    -- Fail-safe rule: If organization quota metric is invalid or stale (> 30 mins), default to Google
    IF NOT v_usage.is_org_metric_valid OR (v_usage.last_reconciled_at IS NOT NULL AND v_usage.last_reconciled_at < (now() - interval '30 minutes')) THEN
        v_chosen_provider := 'gcs';
        v_chosen_bucket := 'cross-testers.firebasestorage.app';
    ELSE
        -- Evaluate 95% / 90% hysteresis
        IF v_dispatch_mode = 'supabase_preferred' THEN
            IF (v_used_bytes + v_active_reserved) >= v_thresh_95 OR (v_used_bytes + v_active_reserved + v_needed_bytes) > v_thresh_95 THEN
                v_dispatch_mode := 'google_overflow';
                v_chosen_provider := 'gcs';
                v_chosen_bucket := 'cross-testers.firebasestorage.app';
            ELSE
                v_chosen_provider := 'supabase';
                v_chosen_bucket := 'report-screenshots';
            END IF;
        ELSE -- google_overflow
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
        UPDATE public.storage_organization_usage
        SET dispatch_mode = v_dispatch_mode,
            active_reserved_bytes = v_active_reserved,
            updated_at = now()
        WHERE id = 1;

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
        'provider', v_chosen_provider,
        'upload_id', p_upload_id,
        'bucket', v_chosen_bucket,
        'object_path', v_object_path,
        'thumbnail_object_path', v_thumb_path,
        'reservation_id', v_reservation_id,
        'pinned', false,
        'dispatch_mode', v_dispatch_mode
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.request_report_media_reservation(uuid, text, bigint, boolean, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.request_report_media_reservation(uuid, text, bigint, boolean, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 8. Updated _submit_tester_report_core (Dual Storage Lifecycle & Expiry)
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
                v_att_view_exp := v_now_utc + interval '216 hours';
            ELSE
                v_att_provider := 'gcs';
                v_att_view_exp := v_now_utc + interval '30 days';
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
                now(),
                v_att_provider,
                v_att_view_exp,
                false
            );

            -- Finalize reservation record in report_media_uploads if present
            UPDATE public.report_media_uploads
            SET status = 'finalized',
                report_id = v_report_id,
                attachment_id = v_att_id,
                server_created_at = v_now_utc,
                view_expires_at = v_att_view_exp,
                cleanup_due_at = (CASE WHEN v_att_provider = 'supabase' THEN v_now_utc + interval '216 hours' ELSE v_now_utc + interval '29 days' END),
                delete_deadline_at = (CASE WHEN v_att_provider = 'supabase' THEN v_now_utc + interval '240 hours' ELSE v_now_utc + interval '30 days' END),
                reserved_bytes = 0,
                updated_at = now()
            WHERE user_id = v_user_id
              AND (
                  upload_id = v_att_id::text
                  OR object_path LIKE ('%' || substring(split_part(v_att_elem.screenshot_url, '?', 1) from '[^/]+$'))
              );
        END LOOP;
    ELSE
        v_att_id := gen_random_uuid();
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
            now(),
            v_provider,
            v_view_exp,
            false
        );

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

    -- Update active reserved bytes on quota ledger
    SELECT coalesce(sum(reserved_bytes), 0)
    INTO v_freed_bytes
    FROM public.report_media_uploads
    WHERE status IN ('reserved', 'uploaded');

    UPDATE public.storage_organization_usage
    SET active_reserved_bytes = v_freed_bytes,
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
-- 9. Updated Media Access RPCs (Checking 216h Supabase vs 30d Google)
-- ----------------------------------------------------------------------------
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

        v_is_supabase := (v_attachment.storage_provider = 'supabase' OR v_attachment.screenshot_url ~* 'supabase' OR v_attachment.screenshot_url ~* 'report-screenshots');

        IF v_attachment.media_expired = true
           OR (v_attachment.view_expires_at IS NOT NULL AND now() >= v_attachment.view_expires_at)
           OR (v_is_supabase AND v_attachment.created_at < (now() - interval '216 hours'))
           OR (NOT v_is_supabase AND v_attachment.created_at < (now() - interval '30 days')) THEN
            RAISE EXCEPTION 'MEDIA_EXPIRED';
        END IF;
    ELSE
        v_is_supabase := (v_report.storage_provider = 'supabase' OR v_report.screenshot_url ~* 'supabase' OR v_report.screenshot_url ~* 'report-screenshots');

        IF v_report.media_expired = true
           OR (v_report.view_expires_at IS NOT NULL AND now() >= v_report.view_expires_at)
           OR (v_is_supabase AND v_report.created_at < (now() - interval '216 hours'))
           OR (NOT v_is_supabase AND v_report.created_at < (now() - interval '30 days')) THEN
            RAISE EXCEPTION 'MEDIA_EXPIRED';
        END IF;
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

    v_expires_at := now() + interval '15 minutes';
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

REVOKE ALL ON FUNCTION public.request_report_media_access(uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.request_report_media_access(uuid, uuid) TO authenticated, service_role;

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
    IF p_user_id IS NULL OR p_grant_token IS NULL OR p_expires_at_epoch IS NULL THEN
        RETURN false;
    END IF;

    IF v_now_epoch > p_expires_at_epoch OR (p_expires_at_epoch - v_now_epoch) > 1800 THEN
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

        v_is_supabase := (v_attachment.storage_provider = 'supabase' OR v_attachment.screenshot_url ~* 'supabase' OR v_attachment.screenshot_url ~* 'report-screenshots');

        IF v_attachment.media_expired = true
           OR (v_attachment.view_expires_at IS NOT NULL AND now() >= v_attachment.view_expires_at)
           OR (v_is_supabase AND v_attachment.created_at < (now() - interval '216 hours'))
           OR (NOT v_is_supabase AND v_attachment.created_at < (now() - interval '30 days')) THEN
            RETURN false;
        END IF;
        v_att_part := p_attachment_id::text;
    ELSE
        v_is_supabase := (v_report.storage_provider = 'supabase' OR v_report.screenshot_url ~* 'supabase' OR v_report.screenshot_url ~* 'report-screenshots');

        IF v_report.media_expired = true
           OR (v_report.view_expires_at IS NOT NULL AND now() >= v_report.view_expires_at)
           OR (v_is_supabase AND v_report.created_at < (now() - interval '216 hours'))
           OR (NOT v_is_supabase AND v_report.created_at < (now() - interval '30 days')) THEN
            RETURN false;
        END IF;
    END IF;

    IF v_report.user_id = p_user_id THEN
        v_role := 'author';
    END IF;

    SELECT * INTO v_camp FROM public.campaigns WHERE id = v_report.campaign_id;
    IF v_camp.id IS NOT NULL AND v_camp.owner_id = p_user_id THEN
        v_role := 'owner';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id = p_user_id
          AND lower(coalesce(role, '')) IN ('admin', 'moderator')
          AND coalesce(is_locked, false) = false
    ) THEN
        v_role := 'admin';
    END IF;

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

REVOKE ALL ON FUNCTION public.verify_media_access_grant_internal(uuid, uuid, text, text, bigint, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verify_media_access_grant_internal(uuid, uuid, text, text, bigint, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 10. Query RPC Updates (get_campaign_detail & reports for owner & admin)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_campaign_detail(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_uid              uuid := auth.uid();
    v_today            date := (now() at time zone 'Asia/Ho_Chi_Minh')::date;
    v_camp             public.campaigns%rowtype;
    v_is_owner         boolean := false;
    v_is_actual_owner  boolean := false;
    v_can_manage       boolean := false;
    v_is_member        boolean := false;
    v_member_joined_at timestamptz;
    v_confirmed        boolean := false;
    v_campaign         jsonb;
    v_testers          jsonb := '[]'::jsonb;
    v_feedbacks        jsonb := '[]'::jsonb;
    v_reports          jsonb := '[]'::jsonb;
    v_profiles         jsonb := '{}'::jsonb;
    v_today_tested     int := 0;
    v_reports_count    int := 0;
    v_tester_reports_count int := 0;
    v_pending_reports_count int := 0;
    v_avg              float;
    v_my_dates         jsonb := '[]'::jsonb;
    v_my_profile       jsonb;
    v_unread           jsonb := '[]'::jsonb;
    v_weekly           int := 0;
    v_rel              record;
    v_app_update       jsonb := null;
    v_claim            record;
    v_is_eligible      boolean := true;
    v_ineligible_reason text := null;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    SELECT * INTO v_camp FROM public.campaigns WHERE id = p_campaign_id;
    IF v_camp.id IS NULL THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    PERFORM public.auto_accept_expired_reports();

    v_is_actual_owner := (v_camp.owner_id = v_uid);

    v_can_manage := (
        v_is_actual_owner
        OR EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = v_uid AND lower(p.role) IN ('admin', 'moderator')
        )
    ) AND NOT EXISTS (
        SELECT 1 FROM public.profiles p
        WHERE p.id = v_uid AND coalesce(p.is_locked, false) = true
    );

    v_is_owner := v_can_manage;

    SELECT exists(
        SELECT 1 FROM public.campaign_members m
        WHERE m.campaign_id = p_campaign_id AND m.user_id = v_uid
    ) INTO v_is_member;

    IF v_is_member THEN
        SELECT joined_at INTO v_member_joined_at
        FROM public.campaign_members
        WHERE campaign_id = p_campaign_id AND user_id = v_uid;
    END IF;

    SELECT exists(
        SELECT 1 FROM public.checkins ci
        WHERE ci.campaign_id = p_campaign_id
          AND ci.user_id = v_uid
          AND ci.checkin_date = v_today
          AND coalesce(ci.note, '') NOT LIKE '%tạm tính%'
    ) INTO v_confirmed;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'user_id', t.user_id,
        'joined_at', t.joined_at,
        'days', t.days,
        'checkin_count', t.checkin_count,
        'last_checkin_date', t.last_checkin_date
    ) ORDER BY t.days DESC), '[]'::jsonb)
    INTO v_testers
    FROM (
        SELECT m.user_id,
               to_char(m.joined_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as joined_at,
               coalesce(s.days, 0) as days,
               coalesce(s.cnt, 0) as checkin_count,
               to_char(s.last_date, 'YYYY-MM-DD') as last_checkin_date
        FROM public.campaign_members m
        LEFT JOIN (
            SELECT user_id,
                   count(distinct checkin_date) as days,
                   count(*) as cnt,
                   max(checkin_date) as last_date
            FROM public.checkins
            WHERE campaign_id = p_campaign_id
            GROUP BY user_id
        ) s ON s.user_id = m.user_id
        WHERE m.campaign_id = p_campaign_id
    ) t;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'id', f.id,
        'campaign_id', f.campaign_id,
        'user_id', f.user_id,
        'rating', f.rating,
        'comment', f.comment,
        'created_at', to_char(f.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'updated_at', to_char(f.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    ) ORDER BY f.created_at DESC), '[]'::jsonb)
    INTO v_feedbacks
    FROM public.feedbacks f
    WHERE f.campaign_id = p_campaign_id;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'id', tr.id,
        'campaign_id', tr.campaign_id,
        'user_id', tr.user_id,
        'report_type', tr.report_type,
        'content', tr.content,
        'screenshot_url', tr.screenshot_url,
        'thumbnail_url', tr.thumbnail_url,
        'rating', tr.rating,
        'status', tr.status,
        'bonus_awarded', tr.bonus_awarded,
        'tester_bonus_awarded', coalesce(tr.tester_bonus_awarded, 0),
        'tester_bonus_round_id', tr.tester_bonus_round_id,
        'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'rejection_reason', tr.rejection_reason,
        'image_hash', tr.image_hash,
        'review_tester_bonus', tr.review_tester_bonus,
        'review_owner_bonus', tr.review_owner_bonus,
        'business_policy_id', tr.business_policy_id,
        'provisional_granted_credits', tr.provisional_granted_credits,
        'provisional_clawed', tr.provisional_clawed,
        'submission_context', tr.submission_context,
        'mission_id', tr.mission_id,
        'mission_participant_id', tr.mission_participant_id,
        'mission_title', tr.mission_title,
        'mission_reward_credits', tr.mission_reward_credits,
        'mission_reward_mode', tr.mission_reward_mode,
        'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'report_date', to_char(tr.report_date, 'YYYY-MM-DD'),
        'storage_provider', coalesce(tr.storage_provider, 'gcs'),
        'view_expires_at', to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'media_expired', (
            coalesce(tr.media_expired, false)
            OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
            OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
            OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
        ),
        'attachments', coalesce((
            SELECT jsonb_agg(jsonb_build_object(
                'id', a.id,
                'position', a.position,
                'screenshot_url', a.screenshot_url,
                'thumbnail_url', a.thumbnail_url,
                'image_hash', a.image_hash,
                'sha256', a.sha256,
                'provider', coalesce(a.storage_provider, 'gcs'),
                'view_expires_at', to_char(a.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'created_at', to_char(a.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'media_expired', (
                    coalesce(a.media_expired, false)
                    OR (a.view_expires_at IS NOT NULL AND now() >= a.view_expires_at)
                    OR (coalesce(a.storage_provider, 'gcs') = 'supabase' AND a.created_at < (now() - interval '216 hours'))
                    OR (coalesce(a.storage_provider, 'gcs') <> 'supabase' AND a.created_at < (now() - interval '30 days'))
                )
            ) ORDER BY a.position)
            FROM public.tester_report_attachments a
            WHERE a.report_id = tr.id
        ), CASE WHEN tr.screenshot_url IS NOT NULL THEN jsonb_build_array(jsonb_build_object(
            'id', tr.id,
            'position', 0,
            'screenshot_url', tr.screenshot_url,
            'thumbnail_url', tr.thumbnail_url,
            'image_hash', tr.image_hash,
            'sha256', null,
            'provider', coalesce(tr.storage_provider, 'gcs'),
            'view_expires_at', to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
            'media_expired', (
                coalesce(tr.media_expired, false)
                OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
                OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
                OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
            )
        )) ELSE '[]'::jsonb END)
    ) ORDER BY tr.created_at DESC), '[]'::jsonb)
    INTO v_reports
    FROM (
        SELECT * FROM public.tester_reports
        WHERE campaign_id = p_campaign_id
          AND (v_can_manage OR user_id = v_uid)
        ORDER BY created_at DESC
        LIMIT 200
    ) tr;

    SELECT count(*),
           count(*) filter (where status = 'pending')
    INTO v_tester_reports_count, v_pending_reports_count
    FROM public.tester_reports
    WHERE campaign_id = p_campaign_id
      AND (v_can_manage OR user_id = v_uid);

    SELECT coalesce(jsonb_object_agg(t.id::text, public._profile_json(p)), '{}'::jsonb)
    INTO v_profiles
    FROM public.profiles p
    JOIN (
        SELECT v_camp.owner_id as id
        UNION
        SELECT m.user_id FROM public.campaign_members m WHERE m.campaign_id = p_campaign_id
        UNION
        SELECT f.user_id FROM public.feedbacks f WHERE f.campaign_id = p_campaign_id
        UNION
        SELECT tr.user_id FROM public.tester_reports tr
        WHERE tr.campaign_id = p_campaign_id AND (v_can_manage OR tr.user_id = v_uid)
    ) t ON t.id = p.id;

    SELECT count(distinct user_id) INTO v_today_tested
    FROM public.checkins
    WHERE campaign_id = p_campaign_id AND checkin_date = v_today;

    SELECT count(*), coalesce(round(avg(rating)::numeric, 1), 0.0)
    INTO v_reports_count, v_avg
    FROM public.feedbacks
    WHERE campaign_id = p_campaign_id;

    SELECT coalesce(jsonb_agg(to_char(ci.checkin_date, 'YYYY-MM-DD') ORDER BY ci.checkin_date ASC), '[]'::jsonb)
    INTO v_my_dates
    FROM public.checkins ci
    WHERE ci.campaign_id = p_campaign_id AND ci.user_id = v_uid;

    v_campaign := row_to_json(v_camp);

    SELECT public._profile_json(p) INTO v_my_profile
    FROM public.profiles p WHERE p.id = v_uid;

    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'id', w.id,
        'user_id', w.user_id,
        'message', w.message,
        'warning_date', to_char(w.warning_date, 'YYYY-MM-DD'),
        'created_at', to_char(w.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'acknowledged', w.acknowledged
    ) ORDER BY w.created_at DESC), '[]'::jsonb)
    INTO v_unread
    FROM public.user_warnings w
    WHERE w.user_id = v_uid AND w.acknowledged = false
      AND NOT EXISTS (
          SELECT 1 FROM public.user_warning_revocations r
          WHERE r.warning_id = w.id
      );

    SELECT count(*) INTO v_weekly
    FROM public.user_warnings w
    WHERE w.user_id = v_uid
      AND (w.created_at at time zone 'Asia/Ho_Chi_Minh')::date >= (v_today - 6)
      AND NOT EXISTS (
          SELECT 1 FROM public.user_warning_revocations r
          WHERE r.warning_id = w.id
      );

    SELECT * INTO v_rel
    FROM public.app_update_releases
    WHERE campaign_id = p_campaign_id AND status IN ('active', 'exhausted')
    ORDER BY published_at DESC, target_version_code DESC
    LIMIT 1;

    IF v_rel.id IS NOT NULL THEN
        IF v_is_actual_owner THEN
            v_app_update := jsonb_build_object(
                'id', v_rel.id,
                'campaign_id', v_rel.campaign_id,
                'owner_id', coalesce(v_rel.owner_id, v_rel.owner_id_snapshot),
                'package_name', v_rel.package_name,
                'app_name', v_rel.app_name,
                'version_name', v_rel.version_name,
                'target_version_code', v_rel.target_version_code,
                'release_notes', v_rel.release_notes,
                'status', v_rel.status,
                'reward_per_claim', v_rel.reward_per_claim,
                'max_claims', v_rel.max_claims,
                'reserved_credits', v_rel.reserved_credits,
                'paid_count', v_rel.paid_count,
                'remaining_credits', v_rel.remaining_credits,
                'spent_credits', v_rel.paid_count * v_rel.reward_per_claim,
                'published_at', to_char(v_rel.published_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
            );
        ELSE
            IF v_rel.paid_count < v_rel.max_claims AND v_rel.remaining_credits >= v_rel.reward_per_claim THEN
                SELECT * INTO v_claim
                FROM public.app_update_claims
                WHERE release_id = v_rel.id AND tester_id = v_uid;

                IF NOT v_is_member THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'not_member';
                ELSIF v_member_joined_at > v_rel.published_at THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'joined_after_release';
                ELSIF v_camp.status = 'paused' THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_paused';
                ELSIF v_camp.status <> 'open' THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'campaign_not_open';
                ELSIF EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = v_uid AND coalesce(p.is_locked, false) = true) THEN
                    v_is_eligible := false;
                    v_ineligible_reason := 'user_locked';
                END IF;

                v_app_update := jsonb_build_object(
                    'id', v_rel.id,
                    'campaign_id', v_rel.campaign_id,
                    'package_name', v_rel.package_name,
                    'app_name', v_rel.app_name,
                    'version_name', v_rel.version_name,
                    'target_version_code', v_rel.target_version_code,
                    'release_notes', v_rel.release_notes,
                    'status', v_rel.status,
                    'reward_per_claim', v_rel.reward_per_claim,
                    'max_claims', v_rel.max_claims,
                    'paid_count', v_rel.paid_count,
                    'has_claimed', (v_claim.id IS NOT NULL),
                    'is_eligible', v_is_eligible,
                    'ineligibility_reason', v_ineligible_reason,
                    'claim_receipt', CASE WHEN v_claim.id IS NOT NULL THEN jsonb_build_object(
                        'claim_id', v_claim.id,
                        'release_id', v_claim.release_id,
                        'ordinal', v_claim.ordinal,
                        'reward_credits', v_claim.reward_credits,
                        'confirmed_at', to_char(v_claim.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                    ) ELSE null END
                );
            END IF;
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'campaign', v_campaign,
        'is_owner', v_is_owner,
        'is_actual_owner', v_is_actual_owner,
        'can_manage', v_can_manage,
        'is_member', v_is_member,
        'checked_in_confirmed_today', v_confirmed,
        'testers', v_testers,
        'feedbacks', v_feedbacks,
        'reports', v_reports,
        'profiles', v_profiles,
        'today_tested_count', v_today_tested,
        'reports_count', v_reports_count,
        'pending_reports_count', v_pending_reports_count,
        'tester_reports_count', v_tester_reports_count,
        'avg_rating', v_avg,
        'my_checkin_dates', v_my_dates,
        'my_profile', v_my_profile,
        'unread_warnings', v_unread,
        'weekly_warnings_count', v_weekly,
        'app_update', v_app_update
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_detail(uuid) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.get_campaign_reports_for_owner(p_campaign_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_caller_id uuid := auth.uid();
    v_camp record;
    v_role text;
    v_locked boolean;
    v_res jsonb;
BEGIN
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    SELECT * INTO v_camp FROM public.campaigns WHERE id = p_campaign_id;
    IF v_camp.id IS NULL THEN
        RAISE EXCEPTION 'CAMPAIGN_NOT_FOUND';
    END IF;

    SELECT role, coalesce(is_locked, false)
    INTO v_role, v_locked
    FROM public.profiles
    WHERE id = v_caller_id;

    IF coalesce(v_locked, false) = true THEN
        RAISE EXCEPTION 'PERMISSION_DENIED';
    END IF;

    IF v_camp.owner_id <> v_caller_id
       AND lower(coalesce(v_role, '')) NOT IN ('admin', 'moderator') THEN
        RAISE EXCEPTION 'PERMISSION_DENIED';
    END IF;

    PERFORM public.auto_accept_expired_reports();

    SELECT coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', tr.id,
                'campaign_id', tr.campaign_id,
                'user_id', tr.user_id,
                'report_type', tr.report_type,
                'content', tr.content,
                'screenshot_url', tr.screenshot_url,
                'thumbnail_url', tr.thumbnail_url,
                'rating', tr.rating,
                'status', tr.status,
                'bonus_awarded', tr.bonus_awarded,
                'tester_bonus_awarded', coalesce(tr.tester_bonus_awarded, 0),
                'tester_bonus_round_id', tr.tester_bonus_round_id,
                'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'reviewed_at', to_char(tr.reviewed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'rejection_reason', tr.rejection_reason,
                'image_hash', tr.image_hash,
                'review_tester_bonus', tr.review_tester_bonus,
                'review_owner_bonus', tr.review_owner_bonus,
                'business_policy_id', tr.business_policy_id,
                'provisional_granted_credits', tr.provisional_granted_credits,
                'provisional_clawed', tr.provisional_clawed,
                'tester_name', coalesce(p.display_name, split_part(coalesce(p.email, ''), '@', 1), 'Tester'),
                'tester_avatar', p.avatar_url,
                'tester_credits', p.credits,
                'submission_context', tr.submission_context,
                'mission_id', tr.mission_id,
                'mission_participant_id', tr.mission_participant_id,
                'mission_title', tr.mission_title,
                'mission_reward_credits', tr.mission_reward_credits,
                'mission_reward_mode', tr.mission_reward_mode,
                'mission_ends_at', to_char(tr.mission_ends_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'mission_submitted_at', to_char(tr.mission_submitted_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'report_date', to_char(tr.report_date, 'YYYY-MM-DD'),
                'storage_provider', coalesce(tr.storage_provider, 'gcs'),
                'view_expires_at', to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'media_expired', (
                    coalesce(tr.media_expired, false)
                    OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
                    OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
                    OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
                ),
                'attachments', coalesce((
                    SELECT jsonb_agg(jsonb_build_object(
                        'id', a.id,
                        'position', a.position,
                        'screenshot_url', a.screenshot_url,
                        'thumbnail_url', a.thumbnail_url,
                        'image_hash', a.image_hash,
                        'sha256', a.sha256,
                        'provider', coalesce(a.storage_provider, 'gcs'),
                        'view_expires_at', to_char(a.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                        'created_at', to_char(a.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                        'media_expired', (
                            coalesce(a.media_expired, false)
                            OR (a.view_expires_at IS NOT NULL AND now() >= a.view_expires_at)
                            OR (coalesce(a.storage_provider, 'gcs') = 'supabase' AND a.created_at < (now() - interval '216 hours'))
                            OR (coalesce(a.storage_provider, 'gcs') <> 'supabase' AND a.created_at < (now() - interval '30 days'))
                        )
                    ) ORDER BY a.position)
                    FROM public.tester_report_attachments a
                    WHERE a.report_id = tr.id
                ), CASE WHEN tr.screenshot_url IS NOT NULL THEN jsonb_build_array(jsonb_build_object(
                    'id', tr.id,
                    'position', 0,
                    'screenshot_url', tr.screenshot_url,
                    'thumbnail_url', tr.thumbnail_url,
                    'image_hash', tr.image_hash,
                    'sha256', null,
                    'provider', coalesce(tr.storage_provider, 'gcs'),
                    'view_expires_at', to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'media_expired', (
                        coalesce(tr.media_expired, false)
                        OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
                        OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
                        OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
                    )
                )) ELSE '[]'::jsonb END)
            )
            ORDER BY tr.created_at DESC
        ),
        '[]'::jsonb
    ) INTO v_res
    FROM public.tester_reports tr
    LEFT JOIN public.profiles p ON p.id = tr.user_id
    WHERE tr.campaign_id = p_campaign_id;

    RETURN v_res;
END;
$function$;

REVOKE ALL ON FUNCTION public.get_campaign_reports_for_owner(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_campaign_reports_for_owner(uuid) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.admin_get_tester_reports_queue(
    p_status text DEFAULT 'pending'::text,
    p_campaign_id uuid DEFAULT NULL::uuid,
    p_tester_id uuid DEFAULT NULL::uuid,
    p_owner_id uuid DEFAULT NULL::uuid,
    p_submission_context text DEFAULT NULL::text,
    p_limit integer DEFAULT 50,
    p_cursor_created_at timestamptz DEFAULT NULL::timestamptz,
    p_cursor_id uuid DEFAULT NULL::uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_caller_id uuid;
    v_limit int;
    v_rows jsonb;
    v_has_more boolean := false;
    v_next_cursor jsonb := null;
    v_count int;
BEGIN
    IF exists (select 1 from pg_proc where proname = 'assert_unlocked_admin' and pronamespace = 'public'::regnamespace) THEN
        v_caller_id := public.assert_unlocked_admin();
    ELSE
        v_caller_id := public._assert_daily_task_admin();
    END IF;
    v_limit := greatest(1, least(coalesce(p_limit, 50), 100));

    WITH raw_reports AS (
        SELECT
            tr.id,
            tr.campaign_id,
            c.app_name as campaign_name,
            c.package_name,
            c.owner_id,
            po.display_name as owner_name,
            tr.user_id as tester_id,
            pt.display_name as tester_name,
            pt.email as tester_email,
            tr.report_type,
            tr.content,
            tr.screenshot_url,
            tr.thumbnail_url,
            tr.rating,
            tr.status,
            tr.submission_context,
            tr.mission_title,
            tr.mission_reward_mode,
            tr.created_at,
            tr.reviewed_at,
            tr.rejection_reason,
            tr.review_tester_bonus,
            tr.review_owner_bonus,
            tr.business_policy_id,
            tr.provisional_granted_credits,
            tr.provisional_clawed,
            tr.bonus_awarded,
            coalesce(tr.tester_bonus_awarded, 0) as tester_bonus_awarded,
            tr.tester_bonus_round_id,
            to_char(tr.report_date, 'YYYY-MM-DD') as report_date,
            coalesce(tr.storage_provider, 'gcs') as storage_provider,
            to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as view_expires_at,
            (
                coalesce(tr.media_expired, false)
                OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
                OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
                OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
            ) as media_expired,
            coalesce((
                SELECT jsonb_agg(jsonb_build_object(
                    'id', a.id,
                    'position', a.position,
                    'screenshot_url', a.screenshot_url,
                    'thumbnail_url', a.thumbnail_url,
                    'image_hash', a.image_hash,
                    'sha256', a.sha256,
                    'provider', coalesce(a.storage_provider, 'gcs'),
                    'view_expires_at', to_char(a.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'created_at', to_char(a.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                    'media_expired', (
                        coalesce(a.media_expired, false)
                        OR (a.view_expires_at IS NOT NULL AND now() >= a.view_expires_at)
                        OR (coalesce(a.storage_provider, 'gcs') = 'supabase' AND a.created_at < (now() - interval '216 hours'))
                        OR (coalesce(a.storage_provider, 'gcs') <> 'supabase' AND a.created_at < (now() - interval '30 days'))
                    )
                ) ORDER BY a.position)
                FROM public.tester_report_attachments a
                WHERE a.report_id = tr.id
            ), CASE WHEN tr.screenshot_url IS NOT NULL THEN jsonb_build_array(jsonb_build_object(
                'id', tr.id,
                'position', 0,
                'screenshot_url', tr.screenshot_url,
                'thumbnail_url', tr.thumbnail_url,
                'image_hash', tr.image_hash,
                'sha256', null,
                'provider', coalesce(tr.storage_provider, 'gcs'),
                'view_expires_at', to_char(tr.view_expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'created_at', to_char(tr.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'media_expired', (
                    coalesce(tr.media_expired, false)
                    OR (tr.view_expires_at IS NOT NULL AND now() >= tr.view_expires_at)
                    OR (coalesce(tr.storage_provider, 'gcs') = 'supabase' AND tr.created_at < (now() - interval '216 hours'))
                    OR (coalesce(tr.storage_provider, 'gcs') <> 'supabase' AND tr.created_at < (now() - interval '30 days'))
                )
            )) ELSE '[]'::jsonb END) as attachments
        FROM public.tester_reports tr
        INNER JOIN public.campaigns c ON c.id = tr.campaign_id
        LEFT JOIN public.profiles po ON po.id = c.owner_id
        LEFT JOIN public.profiles pt ON pt.id = tr.user_id
        WHERE (p_cursor_created_at IS NULL OR (tr.created_at, tr.id) < (p_cursor_created_at, p_cursor_id))
          AND (p_status IS NULL OR tr.status = p_status)
          AND (p_campaign_id IS NULL OR tr.campaign_id = p_campaign_id)
          AND (p_tester_id IS NULL OR tr.user_id = p_tester_id)
          AND (p_owner_id IS NULL OR c.owner_id = p_owner_id)
          AND (p_submission_context IS NULL OR tr.submission_context = p_submission_context)
        ORDER BY tr.created_at DESC, tr.id DESC
        LIMIT v_limit + 1
    )
    SELECT jsonb_agg(to_jsonb(r)), count(*)
    INTO v_rows, v_count
    FROM raw_reports r;

    IF v_count > v_limit THEN
        v_has_more := true;
        SELECT jsonb_agg(elem)
        INTO v_rows
        FROM (
            SELECT elem
            FROM jsonb_array_elements(v_rows) WITH ORDINALITY AS t(elem, ord)
            WHERE ord <= v_limit
        ) sub;

        v_next_cursor := jsonb_build_object(
            'created_at', v_rows->(v_limit - 1)->>'created_at',
            'id', v_rows->(v_limit - 1)->>'id'
        );
    END IF;

    RETURN jsonb_build_object(
        'items', coalesce(v_rows, '[]'::jsonb),
        'has_more', v_has_more,
        'next_cursor', v_next_cursor
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_tester_reports_queue(text, uuid, uuid, uuid, text, integer, timestamptz, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 11. Worker Cleanup & Reconcile RPCs
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_expired_report_media_for_cleanup(
    p_limit int DEFAULT 50,
    p_lease_seconds int DEFAULT 300
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_limit int := greatest(1, least(coalesce(p_limit, 50), 100));
    v_rows jsonb;
BEGIN
    -- Select candidates:
    -- 1. Finalized Supabase media reaching cleanup_due_at (216 hours)
    -- 2. Orphaned or expired unlinked reservations
    WITH locked_candidates AS (
        SELECT id
        FROM public.report_media_uploads
        WHERE (
            (status = 'finalized' AND cleanup_due_at <= now() AND provider = 'supabase')
            OR (status IN ('reserved', 'uploaded') AND reservation_expires_at < now() AND report_id IS NULL)
        )
        ORDER BY cleanup_due_at ASC NULLS LAST, created_at ASC
        LIMIT v_limit
        FOR UPDATE SKIP LOCKED
    ),
    leased AS (
        UPDATE public.report_media_uploads u
        SET status = 'deleting',
            updated_at = now()
        FROM locked_candidates lc
        WHERE u.id = lc.id
        RETURNING u.id, u.upload_id, u.provider, u.bucket, u.object_path,
                  u.thumbnail_object_path, u.size_bytes, u.thumbnail_size_bytes,
                  u.server_created_at, u.delete_deadline_at, u.status, u.report_id
    )
    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'id', l.id,
        'upload_id', l.upload_id,
        'provider', l.provider,
        'bucket', l.bucket,
        'object_path', l.object_path,
        'thumbnail_object_path', l.thumbnail_object_path,
        'size_bytes', l.size_bytes,
        'thumbnail_size_bytes', l.thumbnail_size_bytes,
        'server_created_at', to_char(l.server_created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'delete_deadline_at', to_char(l.delete_deadline_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'status', l.status,
        'report_id', l.report_id
    )), '[]'::jsonb) INTO v_rows
    FROM leased l;

    RETURN v_rows;
END;
$function$;

REVOKE ALL ON FUNCTION public.get_expired_report_media_for_cleanup(int, int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_expired_report_media_for_cleanup(int, int) TO service_role;

CREATE OR REPLACE FUNCTION public.record_report_media_deleted(
    p_upload_id text,
    p_bytes_freed bigint DEFAULT 0
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
DECLARE
    v_rec record;
    v_report_id uuid;
BEGIN
    SELECT * INTO v_rec
    FROM public.report_media_uploads
    WHERE upload_id = p_upload_id
    FOR UPDATE;

    IF v_rec.id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'reason', 'RECORD_NOT_FOUND');
    END IF;

    UPDATE public.report_media_uploads
    SET status = 'deleted',
        deleted_at = now(),
        updated_at = now()
    WHERE id = v_rec.id;

    -- Update organization storage counter atomically
    UPDATE public.storage_organization_usage
    SET used_storage_bytes = greatest(0, used_storage_bytes - coalesce(p_bytes_freed, v_rec.size_bytes + v_rec.thumbnail_size_bytes, 0)),
        updated_at = now()
    WHERE id = 1;

    -- Mark report and attachment media_expired
    IF v_rec.report_id IS NOT NULL THEN
        v_report_id := v_rec.report_id;
        UPDATE public.tester_report_attachments
        SET media_expired = true
        WHERE report_id = v_report_id
          AND (
              id = v_rec.attachment_id
              OR screenshot_url LIKE ('%' || v_rec.object_path)
              OR thumbnail_url LIKE ('%' || coalesce(v_rec.thumbnail_object_path, '__NONE__'))
          );

        -- If all attachments are expired, mark the main report media_expired
        IF NOT EXISTS (
            SELECT 1 FROM public.tester_report_attachments
            WHERE report_id = v_report_id AND coalesce(media_expired, false) = false
        ) THEN
            UPDATE public.tester_reports
            SET media_expired = true,
                updated_at = now()
            WHERE id = v_report_id;
        END IF;
    END IF;

    RETURN jsonb_build_object('success', true, 'upload_id', p_upload_id, 'status', 'deleted');
END;
$function$;

REVOKE ALL ON FUNCTION public.record_report_media_deleted(text, bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.record_report_media_deleted(text, bigint) TO service_role;

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
    v_usage record;
    v_thresh_95 bigint;
    v_thresh_90 bigint;
    v_new_dispatch_mode text;
    v_active_reserved bigint;
    v_caller_role text;
BEGIN
    SELECT * INTO v_usage
    FROM public.storage_organization_usage
    WHERE id = 1
    FOR UPDATE;

    SELECT coalesce(sum(reserved_bytes), 0)
    INTO v_active_reserved
    FROM public.report_media_uploads
    WHERE status IN ('reserved', 'uploaded');

    v_thresh_95 := (p_total_capacity_bytes * 95) / 100;
    v_thresh_90 := (p_total_capacity_bytes * 90) / 100;

    v_new_dispatch_mode := v_usage.dispatch_mode;

    IF NOT p_is_valid THEN
        -- If org metric is invalid, stay or fallback to google_overflow
        v_new_dispatch_mode := 'google_overflow';
    ELSE
        IF v_usage.dispatch_mode = 'supabase_preferred' THEN
            IF (p_used_storage_bytes + v_active_reserved) >= v_thresh_95 THEN
                v_new_dispatch_mode := 'google_overflow';
            END IF;
        ELSE -- google_overflow
            IF (p_used_storage_bytes + v_active_reserved) < v_thresh_90 THEN
                v_new_dispatch_mode := 'supabase_preferred';
            END IF;
        END IF;
    END IF;

    UPDATE public.storage_organization_usage
    SET total_capacity_bytes = p_total_capacity_bytes,
        used_storage_bytes = p_used_storage_bytes,
        active_reserved_bytes = v_active_reserved,
        measured_projects = coalesce(p_measured_projects, '[]'::jsonb),
        is_org_metric_valid = p_is_valid,
        dispatch_mode = v_new_dispatch_mode,
        last_reconciled_at = now(),
        updated_at = now()
    WHERE id = 1;

    RETURN jsonb_build_object(
        'success', true,
        'total_capacity_bytes', p_total_capacity_bytes,
        'used_storage_bytes', p_used_storage_bytes,
        'active_reserved_bytes', v_active_reserved,
        'dispatch_mode', v_new_dispatch_mode,
        'is_org_metric_valid', p_is_valid
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.reconcile_storage_organization_usage(bigint, bigint, jsonb, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reconcile_storage_organization_usage(bigint, bigint, jsonb, boolean) TO service_role;

NOTIFY pgrst, 'reload schema';
COMMIT;
