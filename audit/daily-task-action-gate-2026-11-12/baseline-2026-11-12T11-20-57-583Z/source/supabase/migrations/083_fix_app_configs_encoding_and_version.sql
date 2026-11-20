-- Migration 083: Fix corrupted Vietnamese encoding (mojibake) and synchronize latest_version_code on app_configs
-- Date: 2026-11-06

BEGIN;

UPDATE public.app_configs
SET
    latest_version_code = GREATEST(COALESCE(latest_version_code, 38), 38),
    latest_version_name = CASE
        WHEN COALESCE(latest_version_code, 0) <= 38 THEN '1.1.8'
        ELSE latest_version_name
    END,
    title = 'Đã có bản cập nhật mới',
    message = 'Vui lòng cập nhật để tiếp tục sử dụng.',
    title_vi = 'Đã có bản cập nhật mới',
    message_vi = 'Vui lòng cập nhật để tiếp tục sử dụng.',
    title_en = COALESCE(NULLIF(BTRIM(title_en), ''), 'New update available'),
    message_en = COALESCE(NULLIF(BTRIM(message_en), ''), 'Please update the app to continue.'),
    updated_at = now()
WHERE id = 'android_client';

COMMIT;
