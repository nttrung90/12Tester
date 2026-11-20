#!/usr/bin/env node

/**
 * Telegram Group Mirror Bridge Worker (--mode=polling | --mode=webhook)
 *
 * Bridges updates from a single allowed Telegram community group
 * (TELEGRAM_ALLOWED_CHAT_ID) into Supabase PostgreSQL tables:
 *   - public.telegram_mirror_chat
 *   - public.telegram_mirror_messages
 *
 * Security properties:
 * - Fail-closed on missing TELEGRAM_ALLOWED_CHAT_ID, DATABASE_URL, or
 *   TELEGRAM_WEBHOOK_SECRET (in webhook mode).
 * - Constant-time verification (crypto.timingSafeEqual) on
 *   X-Telegram-Bot-Api-Secret-Token header in webhook mode.
 * - Sanitized logging (never logs DB credentials, bot tokens, or secrets).
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createHash, timingSafeEqual } from 'node:crypto';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import {
  DEFAULT_CHAT_KEY,
  downloadTelegramThumbnailBase64,
  parseTelegramUpdate
} from './telegram-parser.mjs';
import {
  createMirrorStore,
  createPoolFromEnv,
  sanitizeError,
  sanitizeUrl
} from './mirror-store.mjs';

const MAX_WEBHOOK_BODY_BYTES = 1024 * 1024; // 1 MB
const DEFAULT_POLL_TIMEOUT_SECONDS = 30;
const DEFAULT_KEEP_LATEST = 500;
const DEFAULT_PURGE_EVERY = 100;

/**
 * Constant-time comparison for Telegram webhook secret tokens.
 */
export function verifyWebhookSecret(provided, expected) {
  if (typeof provided !== 'string' || typeof expected !== 'string') return false;
  if (!provided || !expected) return false;

  const providedDigest = createHash('sha256').update(provided, 'utf8').digest();
  const expectedDigest = createHash('sha256').update(expected, 'utf8').digest();
  return timingSafeEqual(providedDigest, expectedDigest) && provided.length === expected.length;
}

/**
 * Computes capped exponential backoff delay in milliseconds.
 */
export function computeBackoffMs(consecutiveErrors, baseMs = 2000, maxMs = 60000) {
  if (!Number.isFinite(consecutiveErrors) || consecutiveErrors <= 0) return 0;
  const exp = Math.min(consecutiveErrors - 1, 10);
  return Math.min(maxMs, baseMs * Math.pow(2, exp));
}

/**
 * File-backed offset tracker so polling resumes cleanly across restarts.
 */
export function createOffsetStateStore({ filePath } = {}) {
  let memoryOffset = null;

  return {
    load() {
      if (memoryOffset !== null) return memoryOffset;
      if (!filePath) return null;
      try {
        if (!fs.existsSync(filePath)) return null;
        const raw = fs.readFileSync(filePath, 'utf8');
        const parsed = JSON.parse(raw);
        if (Number.isInteger(parsed?.nextOffset) && parsed.nextOffset >= 0) {
          memoryOffset = parsed.nextOffset;
          return memoryOffset;
        }
      } catch {
        // Ignore corrupt or unreadable offset state file
      }
      return null;
    },

    save(nextOffset) {
      if (!Number.isInteger(nextOffset) || nextOffset < 0) return;
      memoryOffset = nextOffset;
      if (!filePath) return;
      try {
        fs.mkdirSync(path.dirname(filePath), { recursive: true });
        const tmpPath = `${filePath}.tmp`;
        fs.writeFileSync(tmpPath, JSON.stringify({ nextOffset, updatedAt: new Date().toISOString() }), 'utf8');
        fs.renameSync(tmpPath, filePath);
      } catch {
        // Ignore non-writable filesystem (e.g. ephemeral container without disk state)
      }
    }
  };
}

/**
 * Telegram Bot API HTTP client wrapper.
 */
export function createTelegramBotClient({
  botToken,
  fetchImpl = fetch,
  apiBaseUrl = 'https://api.telegram.org'
} = {}) {
  return {
    async getUpdates({ offset = null, timeoutSeconds = DEFAULT_POLL_TIMEOUT_SECONDS, limit = 100 } = {}) {
      if (!botToken) {
        throw new Error('TELEGRAM_BOT_TOKEN is required for getUpdates.');
      }
      const params = new URLSearchParams({
        timeout: String(Math.max(0, Math.min(50, timeoutSeconds))),
        limit: String(Math.max(1, Math.min(100, limit))),
        allowed_updates: JSON.stringify(['message', 'edited_message'])
      });
      if (Number.isInteger(offset) && offset >= 0) {
        params.set('offset', String(offset));
      }

      const controller = new AbortController();
      const timeoutMs = (Math.max(5, timeoutSeconds + 15)) * 1000;
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const url = `${apiBaseUrl}/bot${botToken}/getUpdates?${params.toString()}`;
        const res = await fetchImpl(url, { signal: controller.signal });
        if (!res.ok) {
          throw new Error(`Telegram getUpdates HTTP ${res.status}`);
        }
        const data = await res.json();
        if (!data?.ok || !Array.isArray(data.result)) {
          throw new Error(`Telegram getUpdates API error: ${data?.description || 'invalid response'}`);
        }
        return data.result;
      } finally {
        clearTimeout(timer);
      }
    },

    async getChatMember({ chatId, userId }) {
      if (!botToken || chatId === null || chatId === undefined || !userId) {
        return null;
      }
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 8000);
      try {
        const url = `${apiBaseUrl}/bot${botToken}/getChatMember?chat_id=${encodeURIComponent(String(chatId))}&user_id=${encodeURIComponent(String(userId))}`;
        const res = await fetchImpl(url, { signal: controller.signal });
        if (!res.ok) {
          throw new Error(`Telegram getChatMember HTTP ${res.status}`);
        }
        const data = await res.json();
        return data?.ok ? data.result : null;
      } finally {
        clearTimeout(timer);
      }
    },

    async downloadThumbnailBase64(fileId) {
      if (!botToken || !fileId) return null;
      return downloadTelegramThumbnailBase64({
        botToken,
        fileId,
        fetchImpl,
        apiBaseUrl
      });
    }
  };
}

/**
 * Processes a single Telegram Bot API update end-to-end.
 */
export async function processTelegramUpdate(update, {
  store,
  telegramApi = null,
  allowedChatId,
  chatKey = DEFAULT_CHAT_KEY,
  secretsToRedact = [],
  logger = console
}) {
  const parsed = parseTelegramUpdate(update, { allowedChatId, chatKey });

  if (parsed.kind === 'ignored') {
    return { action: 'ignored', reason: parsed.reason };
  }

  if (parsed.kind === 'hide_preview') {
    let isAdmin = parsed.isSenderChatAdmin;
    if (!isAdmin && parsed.actorUserId && telegramApi?.getChatMember) {
      try {
        const member = await telegramApi.getChatMember({
          chatId: parsed.chatId,
          userId: parsed.actorUserId
        });
        isAdmin = member?.status === 'creator' || member?.status === 'administrator';
      } catch (err) {
        logger.warn?.('[telegram-mirror] getChatMember failed:', sanitizeError(err, secretsToRedact));
        isAdmin = false;
      }
    }

    if (!isAdmin) {
      return {
        action: 'denied_hide_preview',
        targetMessageId: parsed.targetMessageId
      };
    }

    const updatedRows = await store.softDeleteMessage({
      chatKey: parsed.chatKey,
      telegramMessageId: parsed.targetMessageId
    });
    return {
      action: 'hidden',
      targetMessageId: parsed.targetMessageId,
      updatedRows
    };
  }

  const { chatUpdate, record, thumbFileId, isEdited } = parsed;

  if (thumbFileId && telegramApi?.downloadThumbnailBase64) {
    try {
      const thumbBase64 = await telegramApi.downloadThumbnailBase64(thumbFileId);
      if (thumbBase64) {
        record.media_thumb_base64 = thumbBase64;
      }
    } catch (err) {
      logger.warn?.('[telegram-mirror] Thumbnail download skipped:', sanitizeError(err, secretsToRedact));
    }
  }

  await store.upsertChatAndMessage(chatUpdate, record, { isEdited });

  return {
    action: isEdited ? 'edited' : 'mirrored',
    telegramMessageId: record.telegram_message_id
  };
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readRequestBody(req, maxBytes = MAX_WEBHOOK_BODY_BYTES) {
  return new Promise((resolveBody, rejectBody) => {
    const chunks = [];
    let totalBytes = 0;
    let settled = false;

    req.on('data', (chunk) => {
      if (settled) return;
      totalBytes += chunk.length;
      if (totalBytes > maxBytes) {
        settled = true;
        const err = new Error('Payload too large');
        err.statusCode = 413;
        rejectBody(err);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      if (settled) return;
      settled = true;
      resolveBody(Buffer.concat(chunks).toString('utf8'));
    });

    req.on('error', (err) => {
      if (settled) return;
      settled = true;
      rejectBody(err);
    });
  });
}

/**
 * Creates the HTTP request handler for `--mode=webhook`.
 */
export function createWebhookHandler({
  store,
  telegramApi = null,
  allowedChatId,
  chatKey = DEFAULT_CHAT_KEY,
  webhookSecret,
  keepLatest = DEFAULT_KEEP_LATEST,
  purgeEvery = DEFAULT_PURGE_EVERY,
  secretsToRedact = [],
  logger = console
}) {
  if (!webhookSecret || typeof webhookSecret !== 'string') {
    throw new Error('TELEGRAM_WEBHOOK_SECRET is required in webhook mode.');
  }

  let processedCount = 0;

  return async (req, res) => {
    const reqUrl = new URL(req.url || '/', 'http://localhost');

    if (reqUrl.pathname === '/healthz') {
      if (req.method !== 'GET') {
        return sendJson(res, 405, { error: 'Method Not Allowed' });
      }
      return sendJson(res, 200, { ok: true, mode: 'webhook', chatKey });
    }

    if (reqUrl.pathname !== '/telegram/webhook') {
      return sendJson(res, 404, { error: 'Not Found' });
    }

    if (req.method !== 'POST') {
      return sendJson(res, 405, { error: 'Method Not Allowed' });
    }

    const headerSecret = req.headers['x-telegram-bot-api-secret-token'];
    const providedSecret = Array.isArray(headerSecret) ? headerSecret[0] : headerSecret;
    if (!verifyWebhookSecret(providedSecret, webhookSecret)) {
      return sendJson(res, 401, { error: 'Unauthorized' });
    }

    let rawBody;
    try {
      rawBody = await readRequestBody(req, MAX_WEBHOOK_BODY_BYTES);
    } catch (err) {
      if (err?.statusCode === 413) {
        return sendJson(res, 413, { error: 'Payload Too Large' });
      }
      return sendJson(res, 400, { error: 'Invalid Request Body' });
    }

    let update;
    try {
      update = JSON.parse(rawBody);
    } catch {
      return sendJson(res, 400, { error: 'Invalid JSON' });
    }

    try {
      const result = await processTelegramUpdate(update, {
        store,
        telegramApi,
        allowedChatId,
        chatKey,
        secretsToRedact,
        logger
      });

      if (result.action === 'mirrored' || result.action === 'edited' || result.action === 'hidden') {
        processedCount += 1;
        if (purgeEvery > 0 && processedCount % purgeEvery === 0 && store.purgeOldMessages) {
          await store.purgeOldMessages({ chatKey, keepLatest });
        }
      }

      return sendJson(res, 200, { ok: true, ...result });
    } catch (err) {
      logger.error?.('[telegram-mirror] Webhook processing error:', sanitizeError(err, secretsToRedact));
      return sendJson(res, 500, { error: 'Internal Server Error' });
    }
  };
}

/**
 * Creates a long-polling runner with in-flight guard and exponential backoff.
 */
export function createPollingRunner({
  store,
  telegramApi,
  allowedChatId,
  chatKey = DEFAULT_CHAT_KEY,
  pollTimeoutSeconds = DEFAULT_POLL_TIMEOUT_SECONDS,
  offsetStore = createOffsetStateStore(),
  keepLatest = DEFAULT_KEEP_LATEST,
  purgeEvery = DEFAULT_PURGE_EVERY,
  baseBackoffMs = 2000,
  maxBackoffMs = 60000,
  secretsToRedact = [],
  logger = console
}) {
  let inFlight = false;
  let stopped = false;
  let timer = null;
  let consecutiveErrors = 0;
  let processedSincePurge = 0;
  let nextOffset = offsetStore.load();

  async function pollOnce() {
    if (inFlight) {
      return { skipped: true, reason: 'in_flight' };
    }

    inFlight = true;
    try {
      const updates = await telegramApi.getUpdates({
        offset: nextOffset,
        timeoutSeconds: pollTimeoutSeconds
      });

      let processed = 0;
      let ignored = 0;

      for (const update of updates) {
        const res = await processTelegramUpdate(update, {
          store,
          telegramApi,
          allowedChatId,
          chatKey,
          secretsToRedact,
          logger
        });

        if (res.action === 'mirrored' || res.action === 'edited' || res.action === 'hidden') {
          processed += 1;
          processedSincePurge += 1;
        } else {
          ignored += 1;
        }

        if (Number.isInteger(update?.update_id)) {
          nextOffset = update.update_id + 1;
          offsetStore.save(nextOffset);
        }
      }

      if (purgeEvery > 0 && processedSincePurge >= purgeEvery && store.purgeOldMessages) {
        await store.purgeOldMessages({ chatKey, keepLatest });
        processedSincePurge = 0;
      }

      consecutiveErrors = 0;
      return {
        skipped: false,
        updatesCount: updates.length,
        processed,
        ignored,
        nextOffset,
        backoffMs: 0
      };
    } catch (err) {
      consecutiveErrors += 1;
      const backoffMs = computeBackoffMs(consecutiveErrors, baseBackoffMs, maxBackoffMs);
      logger.error?.(
        `[telegram-mirror] Polling error (attempt ${consecutiveErrors}, backoff ${backoffMs}ms):`,
        sanitizeError(err, secretsToRedact)
      );
      return {
        skipped: false,
        error: sanitizeError(err, secretsToRedact),
        consecutiveErrors,
        backoffMs
      };
    } finally {
      inFlight = false;
    }
  }

  function start() {
    stopped = false;
    const loop = async () => {
      if (stopped) return;
      const outcome = await pollOnce();
      if (stopped) return;
      const delayMs = outcome?.backoffMs ? outcome.backoffMs : 250;
      timer = setTimeout(loop, delayMs);
    };
    loop();
  }

  function stop() {
    stopped = true;
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  }

  return {
    pollOnce,
    start,
    stop,
    getState: () => ({ inFlight, consecutiveErrors, nextOffset })
  };
}

async function main() {
  const { values } = parseArgs({
    options: {
      mode: { type: 'string', default: process.env.MIRROR_MODE || 'polling' },
      port: { type: 'string', default: process.env.PORT || '8080' },
      host: { type: 'string', default: process.env.HOST || '0.0.0.0' },
      once: { type: 'boolean', default: false },
      'poll-timeout': { type: 'string', default: process.env.TELEGRAM_POLL_TIMEOUT || String(DEFAULT_POLL_TIMEOUT_SECONDS) },
      'keep-latest': { type: 'string', default: process.env.PURGE_KEEP_LATEST || String(DEFAULT_KEEP_LATEST) },
      'purge-every': { type: 'string', default: process.env.PURGE_EVERY || String(DEFAULT_PURGE_EVERY) },
      'chat-key': { type: 'string', default: process.env.TELEGRAM_CHAT_KEY || DEFAULT_CHAT_KEY },
      'offset-file': { type: 'string', default: process.env.TELEGRAM_OFFSET_FILE || '' }
    },
    allowPositionals: true
  });

  const mode = values.mode.toLowerCase();
  if (mode !== 'polling' && mode !== 'webhook') {
    console.error(`[telegram-mirror] Fatal: Unsupported --mode=${values.mode}. Expected 'polling' or 'webhook'.`);
    process.exit(1);
  }

  const dbUrl = process.env.DATABASE_URL || process.env.SUPABASE_DB_URL;
  if (!dbUrl) {
    console.error('[telegram-mirror] Fatal: DATABASE_URL or SUPABASE_DB_URL environment variable is required.');
    process.exit(1);
  }

  const allowedChatId = process.env.TELEGRAM_ALLOWED_CHAT_ID;
  if (!allowedChatId || !/^-?\d+$/.test(String(allowedChatId).trim())) {
    console.error('[telegram-mirror] Fatal: TELEGRAM_ALLOWED_CHAT_ID must be configured as a numeric Telegram chat ID.');
    process.exit(1);
  }

  const botToken = process.env.TELEGRAM_BOT_TOKEN || '';
  const webhookSecret = process.env.TELEGRAM_WEBHOOK_SECRET || '';
  const secretsToRedact = [botToken, webhookSecret].filter(Boolean);

  if (mode === 'polling' && !botToken) {
    console.error('[telegram-mirror] Fatal: TELEGRAM_BOT_TOKEN is required in polling mode.');
    process.exit(1);
  }

  if (mode === 'webhook' && webhookSecret.length < 16) {
    console.error('[telegram-mirror] Fatal: TELEGRAM_WEBHOOK_SECRET (>= 16 chars) is required in webhook mode.');
    process.exit(1);
  }

  const chatKey = values['chat-key'] || DEFAULT_CHAT_KEY;
  const keepLatest = Math.max(1, parseInt(values['keep-latest'], 10) || DEFAULT_KEEP_LATEST);
  const purgeEvery = Math.max(0, parseInt(values['purge-every'], 10) || DEFAULT_PURGE_EVERY);
  const pollTimeoutSeconds = Math.max(0, Math.min(50, parseInt(values['poll-timeout'], 10) || DEFAULT_POLL_TIMEOUT_SECONDS));

  let pool;
  try {
    pool = await createPoolFromEnv({ databaseUrl: dbUrl, databaseSsl: process.env.DATABASE_SSL });
  } catch (err) {
    console.error('[telegram-mirror] Fatal DB setup error:', sanitizeError(err, secretsToRedact));
    process.exit(1);
  }

  const store = createMirrorStore({ pool });
  const telegramApi = botToken ? createTelegramBotClient({ botToken }) : null;

  console.log(
    `[telegram-mirror] Starting in mode=${mode}, chatKey=${chatKey}, allowedChatId=${allowedChatId}, db=${sanitizeUrl(dbUrl)}`
  );

  if (mode === 'polling') {
    const offsetFile = values['offset-file'] || path.join(process.cwd(), `.telegram-mirror-offset-${chatKey}.json`);
    const offsetStore = createOffsetStateStore({ filePath: offsetFile });
    const runner = createPollingRunner({
      store,
      telegramApi,
      allowedChatId,
      chatKey,
      pollTimeoutSeconds,
      offsetStore,
      keepLatest,
      purgeEvery,
      secretsToRedact
    });

    if (values.once) {
      try {
        const result = await runner.pollOnce();
        console.log('[telegram-mirror] Single poll completed:', JSON.stringify(result));
        await store.close();
        process.exit(result?.error ? 1 : 0);
      } catch (err) {
        console.error('[telegram-mirror] Fatal --once error:', sanitizeError(err, secretsToRedact));
        await store.close();
        process.exit(1);
      }
    }

    const shutdown = async () => {
      runner.stop();
      await store.close();
      process.exit(0);
    };
    process.on('SIGINT', shutdown);
    process.on('SIGTERM', shutdown);

    runner.start();
    return;
  }

  // mode === 'webhook'
  const port = Math.max(1, parseInt(values.port, 10) || 8080);
  const host = values.host || '0.0.0.0';
  const handler = createWebhookHandler({
    store,
    telegramApi,
    allowedChatId,
    chatKey,
    webhookSecret,
    keepLatest,
    purgeEvery,
    secretsToRedact
  });

  const server = http.createServer(handler);
  server.listen(port, host, () => {
    console.log(`[telegram-mirror] Webhook server listening on ${host}:${port} (POST /telegram/webhook, GET /healthz)`);
  });

  const shutdown = async () => {
    server.close(async () => {
      await store.close();
      process.exit(0);
    });
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (process.argv[1]) {
  try {
    const scriptPath = fileURLToPath(import.meta.url);
    if (path.resolve(process.argv[1]) === path.resolve(scriptPath)) {
      main();
    }
  } catch {
    // ignore entry check failure
  }
}
