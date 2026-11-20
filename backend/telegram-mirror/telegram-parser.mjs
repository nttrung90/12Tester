/**
 * Telegram Bot API Update Parser & Thumbnail Helper
 *
 * Normalizes Telegram Bot API updates (`message`, `edited_message`, `pinned_message`)
 * into rows matching `public.telegram_mirror_chat` and `public.telegram_mirror_messages`.
 */

export const DEFAULT_CHAT_KEY = 'community_main';
export const MAX_THUMBNAIL_BYTES = 64 * 1024; // 64 KB hard safety cap for base64 thumbs
export const MAX_PREVIEW_CHARS = 180;

export const SUPPORTED_ENTITY_TYPES = new Set([
  'url',
  'text_url',
  'bold',
  'italic',
  'code',
  'pre',
  'underline',
  'strikethrough',
  'spoiler',
  'mention',
  'bot_command',
  'hashtag'
]);

/**
 * Validates that a chat ID strictly matches the configured TELEGRAM_ALLOWED_CHAT_ID.
 * Fails closed when allowedChatId is empty or malformed.
 */
export function isAllowedChatId(chatId, allowedChatId) {
  if (chatId === null || chatId === undefined) return false;
  if (allowedChatId === null || allowedChatId === undefined) return false;

  const expected = String(allowedChatId).trim();
  if (!/^-?\d+$/.test(expected)) return false;

  const actual = String(chatId).trim();
  if (!/^-?\d+$/.test(actual)) return false;

  return actual === expected;
}

/**
 * Formats a Telegram User or Chat sender into a clean display name.
 */
export function formatSenderName(msg) {
  if (!msg || typeof msg !== 'object') return 'Telegram User';

  if (msg.from && typeof msg.from === 'object') {
    const fullName = [msg.from.first_name, msg.from.last_name]
      .filter(part => typeof part === 'string' && part.trim().length > 0)
      .join(' ')
      .trim();
    if (fullName) return fullName;
    if (typeof msg.from.username === 'string' && msg.from.username.trim()) {
      return `@${msg.from.username.trim()}`;
    }
  }

  if (msg.sender_chat && typeof msg.sender_chat === 'object') {
    if (typeof msg.sender_chat.title === 'string' && msg.sender_chat.title.trim()) {
      return msg.sender_chat.title.trim();
    }
    if (typeof msg.sender_chat.username === 'string' && msg.sender_chat.username.trim()) {
      return `@${msg.sender_chat.username.trim()}`;
    }
  }

  return 'Telegram User';
}

/**
 * Normalizes Telegram Bot API UTF-16 entities into the mirror JSON contract.
 */
export function normalizeEntities(entities) {
  if (!Array.isArray(entities) || entities.length === 0) return [];

  const normalized = [];
  for (const entity of entities) {
    if (!entity || typeof entity !== 'object') continue;
    const { offset, length, type, url } = entity;
    if (!Number.isInteger(offset) || offset < 0) continue;
    if (!Number.isInteger(length) || length <= 0) continue;
    if (typeof type !== 'string' || !SUPPORTED_ENTITY_TYPES.has(type)) continue;

    if (type === 'text_url') {
      if (typeof url !== 'string' || !url.trim()) continue;
      normalized.push({ offset, length, type, url: url.trim() });
    } else {
      normalized.push({ offset, length, type });
    }
  }
  return normalized;
}

export function truncatePreview(text, maxChars = MAX_PREVIEW_CHARS) {
  if (typeof text !== 'string') return '';
  const singleLine = text.replace(/\s+/g, ' ').trim();
  if (singleLine.length <= maxChars) return singleLine;
  return `${singleLine.slice(0, maxChars - 1)}…`;
}

/**
 * Builds a short single-line preview string for reply/pinned banners.
 */
export function buildPreviewText(msg) {
  if (!msg || typeof msg !== 'object') return '';
  if (typeof msg.text === 'string' && msg.text.trim()) {
    return truncatePreview(msg.text);
  }
  const caption = typeof msg.caption === 'string' ? msg.caption.trim() : '';
  if (Array.isArray(msg.photo) && msg.photo.length > 0) {
    return caption ? `📷 ${truncatePreview(caption)}` : '📷 Photo';
  }
  if (msg.sticker) {
    return msg.sticker.emoji ? `${msg.sticker.emoji} Sticker` : 'Sticker';
  }
  if (msg.video || msg.animation) {
    return caption ? `🎬 ${truncatePreview(caption)}` : '🎬 Video';
  }
  if (msg.voice || msg.audio) {
    return caption ? `🎤 ${truncatePreview(caption)}` : '🎤 Voice message';
  }
  if (msg.document) {
    const name = msg.document.file_name || 'Document';
    return caption ? `📎 ${name}: ${truncatePreview(caption)}` : `📎 ${name}`;
  }
  if (caption) return truncatePreview(caption);
  return 'Message';
}

/**
 * Picks the smallest PhotoSize entry for lightweight base64 thumbnail download,
 * and the largest PhotoSize dimensions for aspect-ratio layout.
 */
export function selectPhotoSizes(photos) {
  if (!Array.isArray(photos) || photos.length === 0) {
    return { smallest: null, largest: null };
  }
  const valid = photos.filter(p => p && typeof p.file_id === 'string' && p.file_id.length > 0);
  if (valid.length === 0) {
    return { smallest: null, largest: null };
  }

  const byAreaAsc = [...valid].sort((a, b) => {
    const areaA = (Number(a.width) || 0) * (Number(a.height) || 0);
    const areaB = (Number(b.width) || 0) * (Number(b.height) || 0);
    if (areaA !== areaB) return areaA - areaB;
    return (Number(a.file_size) || 0) - (Number(b.file_size) || 0);
  });

  return {
    smallest: byAreaAsc[0],
    largest: byAreaAsc[byAreaAsc.length - 1]
  };
}

/**
 * Formats service messages (join, leave, pin, title change, etc.).
 */
function parseServiceMessage(msg) {
  const actor = formatSenderName(msg);
  if (Array.isArray(msg.new_chat_members) && msg.new_chat_members.length > 0) {
    const names = msg.new_chat_members
      .map(u => [u.first_name, u.last_name].filter(Boolean).join(' ').trim() || (u.username ? `@${u.username}` : 'Member'))
      .join(', ');
    return `${names} joined the group`;
  }
  if (msg.left_chat_member) {
    const u = msg.left_chat_member;
    const name = [u.first_name, u.last_name].filter(Boolean).join(' ').trim() || (u.username ? `@${u.username}` : 'Member');
    return `${name} left the group`;
  }
  if (typeof msg.new_chat_title === 'string' && msg.new_chat_title.trim()) {
    return `${actor} changed group title to "${msg.new_chat_title.trim()}"`;
  }
  if (Array.isArray(msg.new_chat_photo) && msg.new_chat_photo.length > 0) {
    return `${actor} updated the group photo`;
  }
  if (msg.delete_chat_photo) {
    return `${actor} removed the group photo`;
  }
  if (msg.group_chat_created || msg.supergroup_chat_created) {
    return `${actor} created the group`;
  }
  if (msg.pinned_message) {
    const preview = buildPreviewText(msg.pinned_message);
    return preview ? `${actor} pinned: ${preview}` : `${actor} pinned a message`;
  }
  return null;
}

/**
 * Parses a Telegram Message object into normalized mirror message fields + optional thumbnail file_id.
 */
export function extractMessageRecord(msg, { chatKey = DEFAULT_CHAT_KEY, isEdited = false } = {}) {
  const sentUnix = Number(msg.date);
  const sentAt = Number.isFinite(sentUnix) && sentUnix > 0
    ? new Date(sentUnix * 1000).toISOString()
    : new Date().toISOString();

  const editUnix = Number(msg.edit_date);
  const editedAt = isEdited
    ? (Number.isFinite(editUnix) && editUnix > 0 ? new Date(editUnix * 1000).toISOString() : new Date().toISOString())
    : null;

  let contentType = 'unsupported';
  let textContent = '';
  let entitiesJson = [];
  let mediaWidth = 0;
  let mediaHeight = 0;
  let fileName = null;
  let fileSize = 0;
  let thumbFileId = null;

  const serviceText = parseServiceMessage(msg);
  if (serviceText !== null) {
    contentType = 'service';
    textContent = serviceText;
  } else if (Array.isArray(msg.photo) && msg.photo.length > 0) {
    const { smallest, largest } = selectPhotoSizes(msg.photo);
    contentType = 'photo';
    textContent = typeof msg.caption === 'string' ? msg.caption : '';
    entitiesJson = normalizeEntities(msg.caption_entities);
    mediaWidth = Number(largest?.width || smallest?.width || 0);
    mediaHeight = Number(largest?.height || smallest?.height || 0);
    fileSize = Number(largest?.file_size || 0);
    thumbFileId = smallest?.file_id || null;
  } else if (msg.sticker) {
    contentType = 'sticker';
    textContent = typeof msg.sticker.emoji === 'string' ? msg.sticker.emoji : '';
    mediaWidth = Number(msg.sticker.width || 0);
    mediaHeight = Number(msg.sticker.height || 0);
    fileSize = Number(msg.sticker.file_size || 0);
    thumbFileId = msg.sticker.thumbnail?.file_id || msg.sticker.thumb?.file_id || null;
  } else if (msg.video || msg.animation) {
    const v = msg.video || msg.animation;
    contentType = 'video';
    textContent = typeof msg.caption === 'string' ? msg.caption : '';
    entitiesJson = normalizeEntities(msg.caption_entities);
    mediaWidth = Number(v.width || 0);
    mediaHeight = Number(v.height || 0);
    fileName = typeof v.file_name === 'string' ? v.file_name : null;
    fileSize = Number(v.file_size || 0);
    thumbFileId = v.thumbnail?.file_id || v.thumb?.file_id || null;
  } else if (msg.voice || msg.audio) {
    const a = msg.voice || msg.audio;
    contentType = 'voice';
    textContent = typeof msg.caption === 'string' ? msg.caption : '';
    entitiesJson = normalizeEntities(msg.caption_entities);
    fileName = typeof a.file_name === 'string' ? a.file_name : (typeof a.title === 'string' ? a.title : null);
    fileSize = Number(a.file_size || 0);
  } else if (msg.document) {
    contentType = 'document';
    textContent = typeof msg.caption === 'string' ? msg.caption : '';
    entitiesJson = normalizeEntities(msg.caption_entities);
    fileName = typeof msg.document.file_name === 'string' ? msg.document.file_name : null;
    fileSize = Number(msg.document.file_size || 0);
  } else if (typeof msg.text === 'string') {
    contentType = 'text';
    textContent = msg.text;
    entitiesJson = normalizeEntities(msg.entities);
  } else if (typeof msg.caption === 'string') {
    contentType = 'unsupported';
    textContent = msg.caption;
    entitiesJson = normalizeEntities(msg.caption_entities);
  }

  let replyToMessageId = null;
  let replySenderName = null;
  let replyPreviewText = null;
  if (msg.reply_to_message && Number.isInteger(msg.reply_to_message.message_id)) {
    replyToMessageId = msg.reply_to_message.message_id;
    replySenderName = formatSenderName(msg.reply_to_message);
    replyPreviewText = buildPreviewText(msg.reply_to_message);
  }

  return {
    record: {
      chat_key: chatKey,
      telegram_message_id: msg.message_id,
      sender_id: Number(msg.from?.id ?? msg.sender_chat?.id ?? 0),
      sender_name: formatSenderName(msg),
      sender_is_bot: Boolean(msg.from?.is_bot),
      content_type: contentType,
      text_content: textContent,
      entities_json: entitiesJson,
      media_width: mediaWidth,
      media_height: mediaHeight,
      media_thumb_base64: null,
      file_name: fileName,
      file_size: fileSize,
      reply_to_message_id: replyToMessageId,
      reply_sender_name: replySenderName,
      reply_preview_text: replyPreviewText,
      sent_at: sentAt,
      edited_at: editedAt,
      is_deleted: false
    },
    thumbFileId,
    isEdited
  };
}

/**
 * Checks whether a Telegram message is an admin `/hide_preview` moderation command.
 */
export function isHidePreviewCommand(text) {
  if (typeof text !== 'string') return false;
  return /^\/hide_preview(?:@[A-Za-z0-9_]+)?(?:\s|$)/i.test(text.trim());
}

/**
 * Parses a Telegram Bot API Update object.
 * Returns one of:
 * - { kind: 'ignored', reason: string }
 * - { kind: 'hide_preview', chatKey, chatId, commandMessageId, targetMessageId, actorUserId, isSenderChatAdmin }
 * - { kind: 'message', chatUpdate, record, thumbFileId, isEdited }
 */
export function parseTelegramUpdate(update, { allowedChatId, chatKey = DEFAULT_CHAT_KEY } = {}) {
  if (!update || typeof update !== 'object') {
    return { kind: 'ignored', reason: 'invalid_update' };
  }

  const isEdited = Boolean(update.edited_message || update.edited_channel_post);
  const msg = update.message || update.edited_message || update.channel_post || update.edited_channel_post;

  if (!msg || typeof msg !== 'object' || !msg.chat || !Number.isInteger(msg.message_id)) {
    return { kind: 'ignored', reason: 'unsupported_update_type' };
  }

  if (!isAllowedChatId(msg.chat.id, allowedChatId)) {
    return { kind: 'ignored', reason: 'chat_not_allowed' };
  }

  if (isHidePreviewCommand(msg.text)) {
    if (msg.reply_to_message && Number.isInteger(msg.reply_to_message.message_id)) {
      return {
        kind: 'hide_preview',
        chatKey,
        chatId: msg.chat.id,
        commandMessageId: msg.message_id,
        targetMessageId: msg.reply_to_message.message_id,
        actorUserId: msg.from?.id ?? null,
        isSenderChatAdmin: Boolean(msg.sender_chat && String(msg.sender_chat.id) === String(msg.chat.id))
      };
    }
    return { kind: 'ignored', reason: 'hide_preview_missing_reply' };
  }

  const { record, thumbFileId } = extractMessageRecord(msg, { chatKey, isEdited });

  const chatTitle = (typeof msg.new_chat_title === 'string' && msg.new_chat_title.trim())
    ? msg.new_chat_title.trim()
    : (typeof msg.chat.title === 'string' && msg.chat.title.trim())
      ? msg.chat.title.trim()
      : (typeof msg.chat.username === 'string' && msg.chat.username.trim())
        ? msg.chat.username.trim()
        : 'Telegram Community';

  const chatUpdate = {
    chat_key: chatKey,
    telegram_chat_id: msg.chat.id,
    title: chatTitle,
    avatar_url: null,
    pinned_message_id: null,
    pinned_preview: null,
    pinned_sender_name: null
  };

  if (msg.pinned_message && Number.isInteger(msg.pinned_message.message_id)) {
    chatUpdate.pinned_message_id = msg.pinned_message.message_id;
    chatUpdate.pinned_preview = buildPreviewText(msg.pinned_message);
    chatUpdate.pinned_sender_name = formatSenderName(msg.pinned_message);
  }

  return {
    kind: 'message',
    chatUpdate,
    record,
    thumbFileId,
    isEdited
  };
}

/**
 * Downloads a small Telegram file (e.g., photo[0] thumbnail) via Bot API `getFile`
 * and encodes it as raw base64 (without data URI prefix).
 * Enforces `maxBytes` to avoid storing large files in PostgreSQL.
 */
export async function downloadTelegramThumbnailBase64({
  botToken,
  fileId,
  fetchImpl = fetch,
  maxBytes = MAX_THUMBNAIL_BYTES,
  timeoutMs = 8000,
  apiBaseUrl = 'https://api.telegram.org'
}) {
  if (!botToken || !fileId) return null;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const getFileUrl = `${apiBaseUrl}/bot${botToken}/getFile?file_id=${encodeURIComponent(fileId)}`;
    const metaRes = await fetchImpl(getFileUrl, { signal: controller.signal });
    if (!metaRes.ok) {
      throw new Error(`Telegram getFile failed with HTTP ${metaRes.status}`);
    }
    const metaJson = await metaRes.json();
    if (!metaJson?.ok || !metaJson.result?.file_path) {
      throw new Error('Telegram getFile returned missing file_path');
    }
    if (typeof metaJson.result.file_size === 'number' && metaJson.result.file_size > maxBytes) {
      return null;
    }

    const fileUrl = `${apiBaseUrl}/file/bot${botToken}/${metaJson.result.file_path}`;
    const fileRes = await fetchImpl(fileUrl, { signal: controller.signal });
    if (!fileRes.ok) {
      throw new Error(`Telegram file download failed with HTTP ${fileRes.status}`);
    }

    const arrayBuffer = await fileRes.arrayBuffer();
    if (!arrayBuffer || arrayBuffer.byteLength === 0 || arrayBuffer.byteLength > maxBytes) {
      return null;
    }

    return Buffer.from(arrayBuffer).toString('base64');
  } finally {
    clearTimeout(timer);
  }
}
