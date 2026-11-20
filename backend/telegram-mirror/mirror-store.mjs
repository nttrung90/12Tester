/**
 * PostgreSQL Persistence Store for Telegram Group Mirror
 *
 * Uses strict verified TLS by default (matching backend/notification-worker),
 * sanitized logging/error redaction, and idempotent upserts.
 */

import fs from 'node:fs';

export function sanitizeUrl(rawUrl) {
  if (!rawUrl) return '(not configured)';
  try {
    const parsed = new URL(rawUrl);
    return `${parsed.protocol}//${parsed.username ? '***@' : ''}${parsed.host}${parsed.pathname}`;
  } catch {
    return '(redacted)';
  }
}

export function sanitizeError(err, extraSecrets = []) {
  if (!err) return 'Unknown error';
  let msg = typeof err === 'string' ? err : (err.message || String(err));
  msg = msg
    .replace(/postgres(?:ql)?:\/\/[^\s]+/gi, '[DATABASE_URL_REDACTED]')
    .replace(/password=[^\s]+/gi, 'password=[REDACTED]')
    .replace(/bearer\s+[a-z0-9._~+/-]+/gi, 'Bearer [REDACTED]')
    .replace(/key=[^\s]+/gi, 'key=[REDACTED]')
    .replace(/\/bot\d+:[A-Za-z0-9_-]+/g, '/bot[REDACTED]')
    .replace(/\b\d{6,}:[A-Za-z0-9_-]{20,}\b/g, '[BOT_TOKEN_REDACTED]');

  for (const secret of extraSecrets) {
    if (typeof secret === 'string' && secret.length >= 4) {
      msg = msg.split(secret).join('[SECRET_REDACTED]');
    }
  }
  return msg;
}

export function resolveTlsConfiguration(rawUrl, databaseSslEnv = process.env.DATABASE_SSL) {
  if (!rawUrl) {
    throw new Error('DATABASE_URL or SUPABASE_DB_URL environment variable is required.');
  }

  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    try {
      parsed = new URL(rawUrl, 'postgres://base');
    } catch {
      if (databaseSslEnv === 'false') {
        return { connectionString: rawUrl, ssl: false };
      }
      return { connectionString: rawUrl, ssl: { rejectUnauthorized: true } };
    }
  }

  const sslModeParam = parsed.searchParams.get('sslmode');
  const sslParam = parsed.searchParams.get('ssl');
  const sslMode = (sslModeParam || '').toLowerCase();
  const sslVal = (sslParam || '').toLowerCase();
  const uselibpqcompat = (parsed.searchParams.get('uselibpqcompat') || '').toLowerCase();

  if (databaseSslEnv === 'false') {
    parsed.searchParams.delete('sslmode');
    parsed.searchParams.delete('ssl');
    parsed.searchParams.delete('sslrootcert');
    parsed.searchParams.delete('sslcert');
    parsed.searchParams.delete('sslkey');
    parsed.searchParams.delete('uselibpqcompat');
    return {
      connectionString: parsed.toString(),
      ssl: false
    };
  }

  if (['disable', 'no-verify', 'allow', 'prefer', 'verify-ca'].includes(sslMode)) {
    if (sslMode === 'verify-ca') {
      throw new Error(
        "Insecure database TLS configuration: 'sslmode=verify-ca' disables hostname verification. " +
        "Verified TLS requires both certificate and hostname verification. Use 'sslmode=verify-full' or leave sslmode unspecified."
      );
    }
    if (sslMode === 'disable') {
      throw new Error(
        "Insecure database TLS configuration: Connection URL specifies 'sslmode=disable' which disables TLS. " +
        "Verified TLS is enforced by default. To allow unencrypted connections for local development, set DATABASE_SSL=false."
      );
    }
    throw new Error(
      `Insecure database TLS configuration: Connection URL specifies 'sslmode=${sslModeParam}', which disables or weakens TLS verification. ` +
      "Verified TLS is enforced by default. Use 'sslmode=verify-full' or set DATABASE_SSL=false for local development."
    );
  }

  if (['false', '0', 'no-verify', 'off'].includes(sslVal)) {
    throw new Error(
      `Insecure database TLS configuration: Connection URL specifies 'ssl=${sslParam}', which disables or weakens TLS. ` +
      "Verified TLS is enforced by default. To allow unencrypted connections for local development, set DATABASE_SSL=false."
    );
  }

  if (uselibpqcompat === 'true' && sslMode !== 'verify-full') {
    throw new Error(
      `Insecure database TLS configuration: 'uselibpqcompat=true' with sslmode='${sslModeParam || 'default'}' weakens verification. ` +
      "Use 'sslmode=verify-full' for verified TLS."
    );
  }

  const sslConfig = { rejectUnauthorized: true };

  const rootCertPath = parsed.searchParams.get('sslrootcert');
  if (rootCertPath) {
    try {
      sslConfig.ca = fs.readFileSync(rootCertPath, 'utf8');
    } catch (err) {
      throw new Error(`Failed to read SSL root certificate from sslrootcert: ${err.message}`);
    }
    parsed.searchParams.delete('sslrootcert');
  }

  const certPath = parsed.searchParams.get('sslcert');
  if (certPath) {
    try {
      sslConfig.cert = fs.readFileSync(certPath, 'utf8');
    } catch (err) {
      throw new Error(`Failed to read SSL client certificate from sslcert: ${err.message}`);
    }
    parsed.searchParams.delete('sslcert');
  }

  const keyPath = parsed.searchParams.get('sslkey');
  if (keyPath) {
    try {
      sslConfig.key = fs.readFileSync(keyPath, 'utf8');
    } catch (err) {
      throw new Error(`Failed to read SSL client key from sslkey: ${err.message}`);
    }
    parsed.searchParams.delete('sslkey');
  }

  parsed.searchParams.delete('sslmode');
  parsed.searchParams.delete('ssl');
  parsed.searchParams.delete('uselibpqcompat');

  return {
    connectionString: parsed.toString(),
    ssl: sslConfig
  };
}

export async function createPoolFromEnv({
  databaseUrl = process.env.DATABASE_URL || process.env.SUPABASE_DB_URL,
  databaseSsl = process.env.DATABASE_SSL
} = {}) {
  const { connectionString, ssl } = resolveTlsConfiguration(databaseUrl, databaseSsl);
  let pgModule;
  try {
    pgModule = await import('pg');
  } catch {
    throw new Error('pg package not resolved. Ensure npm install has been executed.');
  }

  const { Pool } = pgModule.default || pgModule;
  const pool = new Pool({
    connectionString,
    ssl,
    max: 4,
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 10000
  });

  pool.on('error', (err) => {
    console.error('[telegram-mirror] Pool background error:', sanitizeError(err));
  });

  return pool;
}

const UPSERT_CHAT_SQL = `
  INSERT INTO public.telegram_mirror_chat (
    chat_key,
    telegram_chat_id,
    title,
    avatar_url,
    pinned_message_id,
    pinned_preview,
    pinned_sender_name,
    updated_at
  ) VALUES ($1, $2, $3, $4, $5, $6, $7, now())
  ON CONFLICT (chat_key) DO UPDATE SET
    telegram_chat_id = EXCLUDED.telegram_chat_id,
    title = EXCLUDED.title,
    avatar_url = COALESCE(EXCLUDED.avatar_url, public.telegram_mirror_chat.avatar_url),
    pinned_message_id = COALESCE(EXCLUDED.pinned_message_id, public.telegram_mirror_chat.pinned_message_id),
    pinned_preview = COALESCE(EXCLUDED.pinned_preview, public.telegram_mirror_chat.pinned_preview),
    pinned_sender_name = COALESCE(EXCLUDED.pinned_sender_name, public.telegram_mirror_chat.pinned_sender_name),
    updated_at = now()
`;

const UPSERT_MESSAGE_SQL = `
  INSERT INTO public.telegram_mirror_messages (
    chat_key,
    telegram_message_id,
    sender_id,
    sender_name,
    sender_is_bot,
    content_type,
    text_content,
    entities_json,
    media_width,
    media_height,
    media_thumb_base64,
    file_name,
    file_size,
    reply_to_message_id,
    reply_sender_name,
    reply_preview_text,
    sent_at,
    edited_at,
    is_deleted
  ) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9, $10, $11, $12, $13, $14, $15, $16, $17::timestamptz,
    CASE
      WHEN $20::boolean THEN $18::timestamptz
      WHEN $21::boolean THEN GREATEST(COALESCE($18::timestamptz, now()), now())
      ELSE $18::timestamptz
    END,
    $19
  )
  ON CONFLICT (chat_key, telegram_message_id) DO UPDATE SET
    sender_id = EXCLUDED.sender_id,
    sender_name = EXCLUDED.sender_name,
    sender_is_bot = EXCLUDED.sender_is_bot,
    content_type = EXCLUDED.content_type,
    text_content = EXCLUDED.text_content,
    entities_json = EXCLUDED.entities_json,
    media_width = EXCLUDED.media_width,
    media_height = EXCLUDED.media_height,
    media_thumb_base64 = COALESCE(EXCLUDED.media_thumb_base64, public.telegram_mirror_messages.media_thumb_base64),
    file_name = EXCLUDED.file_name,
    file_size = EXCLUDED.file_size,
    reply_to_message_id = COALESCE(EXCLUDED.reply_to_message_id, public.telegram_mirror_messages.reply_to_message_id),
    reply_sender_name = COALESCE(EXCLUDED.reply_sender_name, public.telegram_mirror_messages.reply_sender_name),
    reply_preview_text = COALESCE(EXCLUDED.reply_preview_text, public.telegram_mirror_messages.reply_preview_text),
    sent_at = EXCLUDED.sent_at,
    edited_at = CASE
      WHEN $20::boolean THEN COALESCE(EXCLUDED.edited_at, public.telegram_mirror_messages.edited_at)
      WHEN $21::boolean THEN GREATEST(COALESCE(EXCLUDED.edited_at, now()), now())
      ELSE COALESCE(EXCLUDED.edited_at, public.telegram_mirror_messages.edited_at)
    END,
    is_deleted = (public.telegram_mirror_messages.is_deleted OR EXCLUDED.is_deleted)
`;

export function createMirrorStore({ pool }) {
  if (!pool) {
    throw new Error('Mirror store requires a pg Pool or compatible queryable pool.');
  }

  async function withClient(fn) {
    const client = await pool.connect();
    try {
      return await fn(client);
    } finally {
      try {
        client.release();
      } catch {
        // ignore release error
      }
    }
  }

  async function runUpsertChat(client, chatMeta) {
    await client.query(UPSERT_CHAT_SQL, [
      chatMeta.chat_key,
      chatMeta.telegram_chat_id,
      chatMeta.title,
      chatMeta.avatar_url ?? null,
      chatMeta.pinned_message_id ?? null,
      chatMeta.pinned_preview ?? null,
      chatMeta.pinned_sender_name ?? null
    ]);
  }

  async function runUpsertMessage(client, record, { preserveEditedAt = false, isEdited = false } = {}) {
    await client.query(UPSERT_MESSAGE_SQL, [
      record.chat_key,
      record.telegram_message_id,
      record.sender_id ?? 0,
      record.sender_name,
      Boolean(record.sender_is_bot),
      record.content_type,
      record.text_content ?? '',
      JSON.stringify(record.entities_json ?? []),
      record.media_width ?? 0,
      record.media_height ?? 0,
      record.media_thumb_base64 ?? null,
      record.file_name ?? null,
      record.file_size ?? 0,
      record.reply_to_message_id ?? null,
      record.reply_sender_name ?? null,
      record.reply_preview_text ?? null,
      record.sent_at,
      record.edited_at ?? null,
      Boolean(record.is_deleted),
      Boolean(preserveEditedAt),
      Boolean(isEdited)
    ]);
  }

  return {
    async upsertChat(chatMeta) {
      return withClient(client => runUpsertChat(client, chatMeta));
    },

    async upsertMessage(record, options = {}) {
      return withClient(client => runUpsertMessage(client, record, options));
    },

    async upsertChatAndMessage(chatMeta, record, options = {}) {
      return withClient(async (client) => {
        await client.query('BEGIN');
        try {
          await runUpsertChat(client, chatMeta);
          await runUpsertMessage(client, record, options);
          await client.query('COMMIT');
        } catch (err) {
          try { await client.query('ROLLBACK'); } catch {}
          throw err;
        }
      });
    },

    async softDeleteMessage({ chatKey, telegramMessageId }) {
      return withClient(async (client) => {
        await client.query('BEGIN');
        try {
          const res = await client.query(
            `UPDATE public.telegram_mirror_messages
             SET is_deleted = true,
                 edited_at = now()
             WHERE chat_key = $1
               AND telegram_message_id = $2`,
            [chatKey, telegramMessageId]
          );

          await client.query(
            `UPDATE public.telegram_mirror_chat
             SET pinned_message_id = NULL,
                 pinned_preview = NULL,
                 pinned_sender_name = NULL,
                 updated_at = now()
             WHERE chat_key = $1
               AND pinned_message_id = $2`,
            [chatKey, telegramMessageId]
          );

          await client.query('COMMIT');
          return res.rowCount ?? 0;
        } catch (err) {
          try { await client.query('ROLLBACK'); } catch {}
          throw err;
        }
      });
    },

    async purgeOldMessages({ chatKey, keepLatest = 500 }) {
      return withClient(async (client) => {
        const res = await client.query(
          'SELECT public.purge_old_telegram_mirror_messages($1, $2) AS deleted_count',
          [chatKey, keepLatest]
        );
        return Number(res.rows[0]?.deleted_count ?? 0);
      });
    },

    async close() {
      if (typeof pool.end === 'function') {
        await pool.end();
      }
    }
  };
}
