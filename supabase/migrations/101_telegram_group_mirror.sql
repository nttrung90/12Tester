-- ============================================================================
-- Migration 101: Telegram Group Read-Only Mirror (Guest + Logged-out Preview)
--
-- Creates:
-- 1. public.telegram_mirror_chat     - Mirrored Telegram community metadata
-- 2. public.telegram_mirror_messages - Mirrored messages (text, entities,
--    reply preview, tiny base64 photo thumbnail, soft-delete flag)
-- 3. RLS + strict ACLs: anon/authenticated cannot insert/update/delete or
--    query tables directly; only service_role writes.
-- 4. RPC public.get_telegram_mirror_feed(...) - SECURITY DEFINER read-only feed
--    granted to anon and authenticated (supports Guest preview, before-cursor
--    pagination, and after/since_edited_at delta polling).
-- 5. public.purge_old_telegram_mirror_messages(...) - Retention helper
--    restricted to service_role.
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. TABLES & INDEXES
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.telegram_mirror_chat (
    chat_key text PRIMARY KEY DEFAULT 'community_main',
    telegram_chat_id bigint NOT NULL,
    title text NOT NULL,
    avatar_url text,
    pinned_message_id bigint,
    pinned_preview text,
    pinned_sender_name text,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.telegram_mirror_messages (
    id bigserial PRIMARY KEY,
    chat_key text NOT NULL DEFAULT 'community_main',
    telegram_message_id bigint NOT NULL,
    sender_id bigint NOT NULL DEFAULT 0,
    sender_name text NOT NULL,
    sender_is_bot boolean NOT NULL DEFAULT false,
    content_type text NOT NULL,
    text_content text NOT NULL DEFAULT '',
    entities_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    media_width integer NOT NULL DEFAULT 0,
    media_height integer NOT NULL DEFAULT 0,
    media_thumb_base64 text,
    file_name text,
    file_size bigint NOT NULL DEFAULT 0,
    reply_to_message_id bigint,
    reply_sender_name text,
    reply_preview_text text,
    sent_at timestamptz NOT NULL,
    edited_at timestamptz,
    is_deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT telegram_mirror_messages_chat_msg_unique
        UNIQUE (chat_key, telegram_message_id),
    CONSTRAINT telegram_mirror_messages_content_type_check
        CHECK (content_type IN (
            'text', 'photo', 'sticker', 'document', 'video', 'voice', 'service', 'unsupported'
        ))
);

CREATE INDEX IF NOT EXISTS idx_telegram_mirror_messages_active_feed
    ON public.telegram_mirror_messages (chat_key, telegram_message_id DESC)
    WHERE is_deleted = false;

CREATE INDEX IF NOT EXISTS idx_telegram_mirror_messages_edited_at
    ON public.telegram_mirror_messages (chat_key, edited_at DESC)
    WHERE edited_at IS NOT NULL;

-- ============================================================================
-- 2. RLS & TABLE PRIVILEGES (DEFENSE-IN-DEPTH)
-- ============================================================================

ALTER TABLE public.telegram_mirror_chat ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.telegram_mirror_messages ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.telegram_mirror_chat FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.telegram_mirror_messages FROM PUBLIC, anon, authenticated;
REVOKE ALL ON SEQUENCE public.telegram_mirror_messages_id_seq FROM PUBLIC, anon, authenticated;

GRANT ALL ON TABLE public.telegram_mirror_chat TO service_role;
GRANT ALL ON TABLE public.telegram_mirror_messages TO service_role;
GRANT ALL ON SEQUENCE public.telegram_mirror_messages_id_seq TO service_role;

-- ============================================================================
-- 3. RPC: public.get_telegram_mirror_feed
-- ============================================================================

CREATE OR REPLACE FUNCTION public.get_telegram_mirror_feed(
    p_chat_key text DEFAULT 'community_main',
    p_before_message_id bigint DEFAULT NULL,
    p_after_message_id bigint DEFAULT NULL,
    p_since_edited_at timestamptz DEFAULT NULL,
    p_limit integer DEFAULT 40
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
DECLARE
    v_chat_key text := coalesce(nullif(btrim(p_chat_key), ''), 'community_main');
    v_limit integer := greatest(1, least(coalesce(p_limit, 40), 50));
    v_server_time timestamptz := now();
    v_server_time_iso text := to_char(v_server_time AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
    v_chat public.telegram_mirror_chat%ROWTYPE;
    v_chat_json jsonb;
    v_messages_json jsonb := '[]'::jsonb;
    v_has_more boolean := false;
BEGIN
    SELECT *
    INTO v_chat
    FROM public.telegram_mirror_chat c
    WHERE c.chat_key = v_chat_key;

    IF v_chat.chat_key IS NULL THEN
        RETURN jsonb_build_object(
            'chat', NULL,
            'messages', '[]'::jsonb,
            'has_more', false,
            'server_time', v_server_time_iso
        );
    END IF;

    v_chat_json := jsonb_build_object(
        'chat_key', v_chat.chat_key,
        'telegram_chat_id', v_chat.telegram_chat_id,
        'title', v_chat.title,
        'avatar_url', v_chat.avatar_url,
        'pinned_message_id', v_chat.pinned_message_id,
        'pinned_preview', v_chat.pinned_preview,
        'pinned_sender_name', v_chat.pinned_sender_name,
        'updated_at', to_char(v_chat.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
    );

    IF p_after_message_id IS NOT NULL OR p_since_edited_at IS NOT NULL THEN
        WITH raw_rows AS (
            SELECT m.*
            FROM public.telegram_mirror_messages m
            WHERE m.chat_key = v_chat_key
              AND (
                  (
                      p_after_message_id IS NOT NULL
                      AND m.telegram_message_id > p_after_message_id
                      AND m.is_deleted = false
                  )
                  OR (
                      p_since_edited_at IS NOT NULL
                      AND m.edited_at IS NOT NULL
                      AND m.edited_at > p_since_edited_at
                  )
              )
            ORDER BY m.telegram_message_id DESC
            LIMIT v_limit + 1
        ),
        counted AS (
            SELECT count(*)::integer AS total_fetched
            FROM raw_rows
        ),
        sliced AS (
            SELECT *
            FROM raw_rows
            ORDER BY telegram_message_id DESC
            LIMIT v_limit
        )
        SELECT
            (SELECT total_fetched > v_limit FROM counted),
            coalesce(
                (
                    SELECT jsonb_agg(
                        jsonb_build_object(
                            'telegram_message_id', s.telegram_message_id,
                            'sender_id', s.sender_id,
                            'sender_name', s.sender_name,
                            'sender_is_bot', s.sender_is_bot,
                            'content_type', s.content_type,
                            'text_content', s.text_content,
                            'entities_json', s.entities_json,
                            'media_width', s.media_width,
                            'media_height', s.media_height,
                            'media_thumb_base64', s.media_thumb_base64,
                            'file_name', s.file_name,
                            'file_size', s.file_size,
                            'reply_to_message_id', s.reply_to_message_id,
                            'reply_sender_name', s.reply_sender_name,
                            'reply_preview_text', s.reply_preview_text,
                            'sent_at', to_char(s.sent_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                            'edited_at', to_char(s.edited_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                            'is_deleted', s.is_deleted
                        )
                        ORDER BY s.telegram_message_id DESC
                    )
                    FROM sliced s
                ),
                '[]'::jsonb
            )
        INTO v_has_more, v_messages_json;
    ELSE
        WITH raw_rows AS (
            SELECT m.*
            FROM public.telegram_mirror_messages m
            WHERE m.chat_key = v_chat_key
              AND m.is_deleted = false
              AND (p_before_message_id IS NULL OR m.telegram_message_id < p_before_message_id)
            ORDER BY m.telegram_message_id DESC
            LIMIT v_limit + 1
        ),
        counted AS (
            SELECT count(*)::integer AS total_fetched
            FROM raw_rows
        ),
        sliced AS (
            SELECT *
            FROM raw_rows
            ORDER BY telegram_message_id DESC
            LIMIT v_limit
        )
        SELECT
            (SELECT total_fetched > v_limit FROM counted),
            coalesce(
                (
                    SELECT jsonb_agg(
                        jsonb_build_object(
                            'telegram_message_id', s.telegram_message_id,
                            'sender_id', s.sender_id,
                            'sender_name', s.sender_name,
                            'sender_is_bot', s.sender_is_bot,
                            'content_type', s.content_type,
                            'text_content', s.text_content,
                            'entities_json', s.entities_json,
                            'media_width', s.media_width,
                            'media_height', s.media_height,
                            'media_thumb_base64', s.media_thumb_base64,
                            'file_name', s.file_name,
                            'file_size', s.file_size,
                            'reply_to_message_id', s.reply_to_message_id,
                            'reply_sender_name', s.reply_sender_name,
                            'reply_preview_text', s.reply_preview_text,
                            'sent_at', to_char(s.sent_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                            'edited_at', to_char(s.edited_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                            'is_deleted', s.is_deleted
                        )
                        ORDER BY s.telegram_message_id DESC
                    )
                    FROM sliced s
                ),
                '[]'::jsonb
            )
        INTO v_has_more, v_messages_json;
    END IF;

    RETURN jsonb_build_object(
        'chat', v_chat_json,
        'messages', v_messages_json,
        'has_more', coalesce(v_has_more, false),
        'server_time', v_server_time_iso
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.get_telegram_mirror_feed(text, bigint, bigint, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_telegram_mirror_feed(text, bigint, bigint, timestamptz, integer) TO anon, authenticated, service_role;

-- ============================================================================
-- 4. RETENTION HELPER: public.purge_old_telegram_mirror_messages
-- ============================================================================

CREATE OR REPLACE FUNCTION public.purge_old_telegram_mirror_messages(
    p_chat_key text DEFAULT 'community_main',
    p_keep_latest integer DEFAULT 500
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
DECLARE
    v_chat_key text := coalesce(nullif(btrim(p_chat_key), ''), 'community_main');
    v_keep integer := greatest(1, least(coalesce(p_keep_latest, 500), 10000));
    v_deleted integer := 0;
BEGIN
    WITH doomed AS (
        SELECT m.id
        FROM public.telegram_mirror_messages m
        WHERE m.chat_key = v_chat_key
        ORDER BY m.telegram_message_id DESC
        OFFSET v_keep
    ),
    deleted AS (
        DELETE FROM public.telegram_mirror_messages target
        USING doomed d
        WHERE target.id = d.id
        RETURNING target.id
    )
    SELECT count(*)::integer
    INTO v_deleted
    FROM deleted;

    RETURN coalesce(v_deleted, 0);
END;
$function$;

REVOKE ALL ON FUNCTION public.purge_old_telegram_mirror_messages(text, integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.purge_old_telegram_mirror_messages(text, integer) TO service_role;

COMMIT;
