#!/usr/bin/env node

/**
 * One-time CLI Importer for Telegram Desktop `result.json` Exports
 *
 * Usage:
 *   DATABASE_URL="..." TELEGRAM_ALLOWED_CHAT_ID="-1001234567890" \
 *     node import-desktop-export.mjs --file=./result.json [--chat-key=community_main] [--limit=500] [--dry-run]
 */

import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import {
  DEFAULT_CHAT_KEY,
  SUPPORTED_ENTITY_TYPES,
  isAllowedChatId,
  truncatePreview
} from './telegram-parser.mjs';
import {
  createMirrorStore,
  createPoolFromEnv,
  sanitizeError,
  sanitizeUrl
} from './mirror-store.mjs';

const DESKTOP_ENTITY_MAP = new Map([
  ['link', 'url'],
  ['text_link', 'text_url'],
  ['bold', 'bold'],
  ['italic', 'italic'],
  ['code', 'code'],
  ['pre', 'pre'],
  ['underline', 'underline'],
  ['strikethrough', 'strikethrough'],
  ['spoiler', 'spoiler'],
  ['mention', 'mention'],
  ['bot_command', 'bot_command'],
  ['hashtag', 'hashtag']
]);

export function parseDesktopSenderId(fromId) {
  if (typeof fromId === 'number' && Number.isFinite(fromId)) return fromId;
  if (typeof fromId !== 'string') return 0;
  const match = fromId.match(/(-?\d+)$/);
  if (!match) return 0;
  const parsed = Number.parseInt(match[1], 10);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function resolveDesktopChatId(exportData, allowedChatId) {
  const rawId = exportData?.id;
  if (rawId === null || rawId === undefined) return null;

  const candidates = [String(rawId)];
  const type = String(exportData?.type || '').toLowerCase();
  if ((type.includes('supergroup') || type.includes('channel')) && !String(rawId).startsWith('-100')) {
    candidates.push(`-100${rawId}`);
  } else if (!String(rawId).startsWith('-')) {
    candidates.push(`-${rawId}`);
  }

  if (allowedChatId !== undefined && allowedChatId !== null && String(allowedChatId).trim() !== '') {
    for (const candidate of candidates) {
      if (isAllowedChatId(candidate, allowedChatId)) {
        return Number(candidate);
      }
    }
    return null;
  }

  return Number(candidates[candidates.length - 1]);
}

export function parseDesktopEntities(msg) {
  const segments = Array.isArray(msg.text_entities)
    ? msg.text_entities
    : (Array.isArray(msg.text) ? msg.text : null);

  if (!segments) {
    return {
      text: typeof msg.text === 'string' ? msg.text : '',
      entities: []
    };
  }

  let text = '';
  const entities = [];

  for (const seg of segments) {
    if (typeof seg === 'string') {
      text += seg;
      continue;
    }
    if (!seg || typeof seg !== 'object') continue;
    const segText = typeof seg.text === 'string' ? seg.text : '';
    if (!segText) continue;

    const offset = text.length;
    const length = segText.length;
    text += segText;

    const mappedType = DESKTOP_ENTITY_MAP.get(seg.type);
    if (!mappedType || !SUPPORTED_ENTITY_TYPES.has(mappedType)) continue;

    if (mappedType === 'text_url') {
      const url = typeof seg.href === 'string' ? seg.href.trim() : '';
      if (!url) continue;
      entities.push({ offset, length, type: 'text_url', url });
    } else {
      entities.push({ offset, length, type: mappedType });
    }
  }

  return { text, entities };
}

function parseDesktopTimestamp(unixStr, isoStr) {
  const unix = Number(unixStr);
  if (Number.isFinite(unix) && unix > 0) {
    return new Date(unix * 1000).toISOString();
  }
  if (typeof isoStr === 'string' && isoStr.trim()) {
    const d = new Date(isoStr.endsWith('Z') ? isoStr : `${isoStr}Z`);
    if (!Number.isNaN(d.getTime())) {
      return d.toISOString();
    }
  }
  return new Date().toISOString();
}

function formatDesktopServiceText(msg) {
  const actor = typeof msg.actor === 'string' && msg.actor.trim() ? msg.actor.trim() : 'Member';
  const action = String(msg.action || '');
  switch (action) {
    case 'pin_message':
      return `${actor} pinned a message`;
    case 'invite_members': {
      const members = Array.isArray(msg.members) ? msg.members.join(', ') : 'member';
      return `${actor} added ${members}`;
    }
    case 'join_group_by_link':
      return `${actor} joined the group via invite link`;
    case 'remove_members': {
      const members = Array.isArray(msg.members) ? msg.members.join(', ') : 'member';
      return `${actor} removed ${members}`;
    }
    case 'edit_group_title':
      return `${actor} changed group title to "${msg.title || ''}"`;
    case 'create_group':
      return `${actor} created the group`;
    default:
      return action ? `${actor}: ${action.replace(/_/g, ' ')}` : `${actor} updated the group`;
  }
}

function buildDesktopPreview(record) {
  if (!record) return null;
  if (record.text_content && record.text_content.trim()) {
    return truncatePreview(record.text_content);
  }
  switch (record.content_type) {
    case 'photo': return '📷 Photo';
    case 'sticker': return 'Sticker';
    case 'video': return '🎬 Video';
    case 'voice': return '🎤 Voice message';
    case 'document': return record.file_name ? `📎 ${record.file_name}` : '📎 Document';
    default: return 'Message';
  }
}

/**
 * Parses a Telegram Desktop `result.json` object into chat metadata + normalized records.
 */
export function parseDesktopExport(exportData, {
  allowedChatId,
  chatKey = DEFAULT_CHAT_KEY,
  limit = 500
} = {}) {
  if (!exportData || typeof exportData !== 'object' || !Array.isArray(exportData.messages)) {
    throw new Error('Invalid Telegram Desktop export JSON: missing messages array.');
  }

  const telegramChatId = resolveDesktopChatId(exportData, allowedChatId);
  if (telegramChatId === null) {
    throw new Error('Export chat id does not match TELEGRAM_ALLOWED_CHAT_ID.');
  }

  const maxItems = Math.max(1, Number(limit) || 500);
  const validMessages = exportData.messages
    .filter(m => m && Number.isInteger(m.id))
    .sort((a, b) => a.id - b.id);

  const sliced = validMessages.length > maxItems
    ? validMessages.slice(validMessages.length - maxItems)
    : validMessages;

  const byId = new Map();
  const records = [];
  let latestPinnedId = null;

  for (const msg of sliced) {
    const sentAt = parseDesktopTimestamp(msg.date_unixtime, msg.date);
    const editedAt = (msg.edited_unixtime || msg.edited)
      ? parseDesktopTimestamp(msg.edited_unixtime, msg.edited)
      : null;

    if (msg.type === 'service') {
      if (msg.action === 'pin_message' && Number.isInteger(msg.message_id)) {
        latestPinnedId = msg.message_id;
      }

      const serviceRecord = {
        chat_key: chatKey,
        telegram_message_id: msg.id,
        sender_id: parseDesktopSenderId(msg.actor_id),
        sender_name: (typeof msg.actor === 'string' && msg.actor.trim()) ? msg.actor.trim() : 'Group',
        sender_is_bot: false,
        content_type: 'service',
        text_content: formatDesktopServiceText(msg),
        entities_json: [],
        media_width: 0,
        media_height: 0,
        media_thumb_base64: null,
        file_name: null,
        file_size: 0,
        reply_to_message_id: Number.isInteger(msg.message_id) ? msg.message_id : null,
        reply_sender_name: null,
        reply_preview_text: null,
        sent_at: sentAt,
        edited_at: editedAt,
        is_deleted: false
      };
      byId.set(msg.id, serviceRecord);
      records.push(serviceRecord);
      continue;
    }

    const { text, entities } = parseDesktopEntities(msg);
    let contentType = 'text';
    let textContent = text;
    let fileName = typeof msg.file_name === 'string' ? msg.file_name : null;
    const fileSize = Number(msg.file_size || 0);
    const mediaWidth = Number(msg.width || 0);
    const mediaHeight = Number(msg.height || 0);

    if (msg.photo) {
      contentType = 'photo';
    } else if (msg.media_type === 'sticker') {
      contentType = 'sticker';
      if (!textContent && typeof msg.sticker_emoji === 'string') {
        textContent = msg.sticker_emoji;
      }
    } else if (msg.media_type === 'video_file' || msg.media_type === 'animation') {
      contentType = 'video';
    } else if (msg.media_type === 'voice_message' || msg.media_type === 'audio_file') {
      contentType = 'voice';
    } else if (msg.file) {
      contentType = 'document';
      if (!fileName && typeof msg.file === 'string') {
        fileName = path.basename(msg.file);
      }
    } else if (!textContent && !msg.text && !msg.text_entities) {
      contentType = 'unsupported';
    }

    const replyToId = Number.isInteger(msg.reply_to_message_id) ? msg.reply_to_message_id : null;
    const replied = replyToId ? byId.get(replyToId) : null;

    const record = {
      chat_key: chatKey,
      telegram_message_id: msg.id,
      sender_id: parseDesktopSenderId(msg.from_id),
      sender_name: (typeof msg.from === 'string' && msg.from.trim()) ? msg.from.trim() : 'Telegram User',
      sender_is_bot: false,
      content_type: contentType,
      text_content: textContent,
      entities_json: entities,
      media_width: mediaWidth,
      media_height: mediaHeight,
      media_thumb_base64: null,
      file_name: fileName,
      file_size: fileSize,
      reply_to_message_id: replyToId,
      reply_sender_name: replied?.sender_name ?? null,
      reply_preview_text: replied ? buildDesktopPreview(replied) : null,
      sent_at: sentAt,
      edited_at: editedAt,
      is_deleted: false
    };

    byId.set(msg.id, record);
    records.push(record);
  }

  const pinnedRecord = latestPinnedId ? byId.get(latestPinnedId) : null;
  const chatMeta = {
    chat_key: chatKey,
    telegram_chat_id: telegramChatId,
    title: (typeof exportData.name === 'string' && exportData.name.trim()) ? exportData.name.trim() : 'Telegram Community',
    avatar_url: null,
    pinned_message_id: latestPinnedId,
    pinned_preview: pinnedRecord ? buildDesktopPreview(pinnedRecord) : null,
    pinned_sender_name: pinnedRecord?.sender_name ?? null
  };

  return { chatMeta, records };
}

export async function runImport({
  filePath,
  store,
  allowedChatId,
  chatKey = DEFAULT_CHAT_KEY,
  limit = 500,
  dryRun = false
}) {
  const raw = fs.readFileSync(filePath, 'utf8');
  const exportData = JSON.parse(raw);
  const { chatMeta, records } = parseDesktopExport(exportData, {
    allowedChatId,
    chatKey,
    limit
  });

  if (dryRun) {
    return { dryRun: true, chatMeta, count: records.length };
  }

  await store.upsertChat(chatMeta);
  for (const record of records) {
    await store.upsertMessage(record, { preserveEditedAt: true });
  }

  return { dryRun: false, chatMeta, count: records.length };
}

async function main() {
  const { values } = parseArgs({
    options: {
      file: { type: 'string' },
      'chat-key': { type: 'string', default: process.env.TELEGRAM_CHAT_KEY || DEFAULT_CHAT_KEY },
      'allowed-chat-id': { type: 'string', default: process.env.TELEGRAM_ALLOWED_CHAT_ID || '' },
      limit: { type: 'string', default: '500' },
      'dry-run': { type: 'boolean', default: false }
    },
    allowPositionals: true
  });

  if (!values.file) {
    console.error('[telegram-mirror-import] Fatal: --file=<path/to/result.json> is required.');
    process.exit(1);
  }

  const allowedChatId = values['allowed-chat-id'];
  if (!allowedChatId || !/^-?\d+$/.test(String(allowedChatId).trim())) {
    console.error('[telegram-mirror-import] Fatal: TELEGRAM_ALLOWED_CHAT_ID (or --allowed-chat-id) is required.');
    process.exit(1);
  }

  const dryRun = Boolean(values['dry-run']);
  const dbUrl = process.env.DATABASE_URL || process.env.SUPABASE_DB_URL;
  if (!dryRun && !dbUrl) {
    console.error('[telegram-mirror-import] Fatal: DATABASE_URL or SUPABASE_DB_URL is required unless --dry-run is set.');
    process.exit(1);
  }

  let store = null;
  try {
    if (!dryRun) {
      console.log(`[telegram-mirror-import] Connecting to ${sanitizeUrl(dbUrl)}`);
      const pool = await createPoolFromEnv({ databaseUrl: dbUrl, databaseSsl: process.env.DATABASE_SSL });
      store = createMirrorStore({ pool });
    }

    const result = await runImport({
      filePath: path.resolve(values.file),
      store,
      allowedChatId,
      chatKey: values['chat-key'] || DEFAULT_CHAT_KEY,
      limit: Math.max(1, parseInt(values.limit, 10) || 500),
      dryRun
    });

    console.log(
      `[telegram-mirror-import] ${dryRun ? 'DRY-RUN' : 'IMPORTED'} ${result.count} messages into chat_key=${result.chatMeta.chat_key} (telegram_chat_id=${result.chatMeta.telegram_chat_id})`
    );
  } catch (err) {
    console.error('[telegram-mirror-import] Failed:', sanitizeError(err));
    process.exitCode = 1;
  } finally {
    if (store) {
      await store.close();
    }
  }
}

if (process.argv[1]) {
  try {
    const scriptPath = fileURLToPath(import.meta.url);
    if (path.resolve(process.argv[1]) === path.resolve(scriptPath)) {
      main();
    }
  } catch {
    // ignore
  }
}
