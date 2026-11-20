import fs from 'node:fs';

const content091 = fs.readFileSync('supabase/migrations/091_dual_storage_report_screenshots.sql', 'utf8');

// 1. Extract the exact core function from 091
const lines = content091.split('\n');
let coreStart = -1;
let coreEnd = -1;
for (let i = 0; i < lines.length; i++) {
  if (lines[i].includes('CREATE OR REPLACE FUNCTION public._submit_tester_report_core(')) {
    coreStart = i;
  }
  if (coreStart !== -1 && i > coreStart && lines[i].trim() === '$function$;') {
    coreEnd = i;
    break;
  }
}

if (coreStart === -1 || coreEnd === -1) {
  throw new Error(`Could not find _submit_tester_report_core in 091 (${coreStart}, ${coreEnd})`);
}

let coreLines = lines.slice(coreStart, coreEnd + 1);
let coreText = coreLines.join('\n');

// Verify key 091 logic is present in extracted core
if (!coreText.includes('v_provisional_reward') || !coreText.includes('refresh_daily_completion') || !coreText.includes('v_fingerprint')) {
  throw new Error('Extracted core is missing critical business variables');
}

// Target 1: Add new variables in DECLARE block
const declAnchor = `    -- Dual Storage specific variables`;
const declReplacement = `    -- Dual Storage hardening variables (092)
    v_media_rec record;
    v_storage_obj record;
    v_path_match text;
    v_real_created_at timestamptz;
    v_actual_bytes bigint;
    v_total_finalized_bytes bigint := 0;

    -- Dual Storage specific variables`;

if (!coreText.includes(declAnchor)) {
  throw new Error('declAnchor not found');
}
coreText = coreText.replace(declAnchor, () => declReplacement);

// Target 2: Attachment loop media provenance, object clock and quota calculation
const loopAnchor = `            IF v_att_elem.screenshot_url ~* 'report-screenshots' OR v_att_elem.screenshot_url ~* 'supabase' THEN
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
              );`;

const loopReplacement = `            IF v_att_elem.screenshot_url ~* 'report-screenshots' OR v_att_elem.screenshot_url ~* 'supabase' THEN
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
            END IF;`;

if (!coreText.includes(loopAnchor)) {
  throw new Error('loopAnchor not found');
}
coreText = coreText.replace(loopAnchor, () => loopReplacement);

// Target 3: Single screenshot branch provenance, object clock and quota calculation
const singleAnchor = `        v_att_id := gen_random_uuid();
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
          );`;

const singleReplacement = `        v_att_id := gen_random_uuid();
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
        END IF;`;

if (!coreText.includes(singleAnchor)) {
  throw new Error('singleAnchor not found');
}
coreText = coreText.replace(singleAnchor, () => singleReplacement);

// Target 4: Quota update: add finalized bytes to used_storage_bytes
const quotaAnchor = `    -- Update active reserved bytes on quota ledger
    SELECT coalesce(sum(reserved_bytes), 0)
    INTO v_freed_bytes
    FROM public.report_media_uploads
    WHERE status IN ('reserved', 'uploaded');

    UPDATE public.storage_organization_usage
    SET active_reserved_bytes = v_freed_bytes,
        updated_at = now()
    WHERE id = 1;`;

const quotaReplacement = `    -- Update active reserved bytes and add finalized bytes on quota ledger
    SELECT coalesce(sum(reserved_bytes), 0)
    INTO v_freed_bytes
    FROM public.report_media_uploads
    WHERE status IN ('reserved', 'uploaded');

    UPDATE public.storage_organization_usage
    SET used_storage_bytes = used_storage_bytes + v_total_finalized_bytes,
        active_reserved_bytes = v_freed_bytes,
        updated_at = now()
    WHERE id = 1;`;

if (!coreText.includes(quotaAnchor)) {
  throw new Error('quotaAnchor not found');
}
coreText = coreText.replace(quotaAnchor, () => quotaReplacement);

console.log('Successfully modified core091 with targeted media replacements!');

// Write full 092 migration
const migration092Header = `-- ============================================================================
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
    IF v_clean_url ~* '^https://firebasestorage\\.googleapis\\.com/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://firebasestorage\\.googleapis\\.com/v0/b/([^/]+)/o/(.+)$', 'i');
        v_bucket := v_matches[1];
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/v0/b/([^/]+)/o/(.+)$', 'i');
        v_bucket := v_matches[2];
        v_path := v_matches[3];
    ELSIF v_clean_url ~* '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/o/(.+)$', 'i');
        v_bucket := v_matches[1] || '.firebasestorage.app';
        v_path := v_matches[2];
    ELSIF v_clean_url ~* '^https://storage\\.googleapis\\.com/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://storage\\.googleapis\\.com/([^/]+)/(.+)$', 'i');
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
    ELSIF v_clean_url ~* '^https://([^/:]+)(?::\\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/:]+)(?::\\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
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
    ELSIF v_clean_url ~* '^https://([^/:]+)(?::\\d+)?/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/:]+)(?::\\d+)?/storage/v1/object/public/([^/]+)/(.+)$', 'i');
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
    ELSIF v_clean_url ~* '^https://([^/:]+)(?::\\d+)?/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_url, '^https://([^/:]+)(?::\\d+)?/storage/v1/object/([^/]+)/(.+)$', 'i');
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
        v_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '/[a-zA-Z0-9_\\-\\.]+\\.(jpg|jpeg|png|webp)$')
        OR v_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '_[a-zA-Z0-9_\\-\\.]+\\.(jpg|jpeg|png|webp)$')
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

    IF v_clean_thumb ~* '^https://firebasestorage\\.googleapis\\.com/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://firebasestorage\\.googleapis\\.com/v0/b/([^/]+)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[1];
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/v0/b/([^/]+)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/v0/b/([^/]+)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[2];
        v_thumb_path := v_matches[3];
    ELSIF v_clean_thumb ~* '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/o/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([a-zA-Z0-9_\\-]+\\.firebasestorage\\.app)/o/(.+)$', 'i');
        v_thumb_bucket := v_matches[1] || '.firebasestorage.app';
        v_thumb_path := v_matches[2];
    ELSIF v_clean_thumb ~* '^https://storage\\.googleapis\\.com/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://storage\\.googleapis\\.com/([^/]+)/(.+)$', 'i');
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
    ELSIF v_clean_thumb ~* '^https://([^/:]+)(?::\\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/:]+)(?::\\d+)?/storage/v1/object/authenticated/([^/]+)/(.+)$', 'i');
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
    ELSIF v_clean_thumb ~* '^https://([^/:]+)(?::\\d+)?/storage/v1/object/public/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/:]+)(?::\\d+)?/storage/v1/object/public/([^/]+)/(.+)$', 'i');
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
    ELSIF v_clean_thumb ~* '^https://([^/:]+)(?::\\d+)?/storage/v1/object/([^/]+)/(.+)$' THEN
        v_matches := regexp_matches(v_clean_thumb, '^https://([^/:]+)(?::\\d+)?/storage/v1/object/([^/]+)/(.+)$', 'i');
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
        v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '/[a-zA-Z0-9_\\-\\.]+\\.(jpg|jpeg|png|webp)$')
        OR v_thumb_path ~* ('^reports/' || p_campaign_id::text || '/' || p_user_id::text || '_[a-zA-Z0-9_\\-\\.]+\\.(jpg|jpeg|png|webp)$')
    ) THEN
        RETURN false;
    END IF;

    IF p_screenshot_url IS NOT NULL AND btrim(p_screenshot_url) <> '' THEN
        v_clean_full := split_part(split_part(p_screenshot_url, '?', 1), '#', 1);
        v_full_filename := substring(v_clean_full from '[^/]+$');
        v_thumb_filename := substring(v_thumb_path from '[^/]+$');

        IF v_thumb_filename <> v_full_filename AND v_thumb_filename <> ('thumb_' || v_full_filename) THEN
            IF NOT (v_thumb_filename ~* '^thumb_' OR v_thumb_filename ~* '_thumb\\.') THEN
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
`;

const migration092Footer = `

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
`;

const finalSql = migration092Header + coreText + migration092Footer;
fs.writeFileSync('supabase/migrations/092_dual_storage_hardening.sql', finalSql, 'utf8');
console.log('Successfully wrote supabase/migrations/092_dual_storage_hardening.sql! Length:', finalSql.length);
