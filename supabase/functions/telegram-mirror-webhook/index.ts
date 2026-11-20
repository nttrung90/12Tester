// Supabase Edge Function: Telegram Group Mirror Webhook
// Zero external dependencies — compatible with both `npx supabase functions deploy`
// and copy-pasting directly into the Supabase Dashboard Edge Function editor.
//
// IMPORTANT: Deploy with JWT verification DISABLED (--no-verify-jwt / "Enforce JWT" OFF)
// because Telegram sends `X-Telegram-Bot-Api-Secret-Token` rather than a Supabase JWT.

declare const Deno: {
  env: { get(key: string): string | undefined };
  serve(handler: (req: Request) => Response | Promise<Response>): unknown;
} | undefined;

const DEFAULT_CHAT_KEY = "community_main";
const MAX_THUMBNAIL_BYTES = 64 * 1024;
const MAX_PREVIEW_CHARS = 180;
const DEFAULT_KEEP_LATEST = 500;

const SUPPORTED_ENTITY_TYPES = new Set([
  "url",
  "text_url",
  "bold",
  "italic",
  "code",
  "pre",
  "underline",
  "strikethrough",
  "spoiler",
  "mention",
  "bot_command",
  "hashtag",
]);

export interface EdgeEnv {
  SUPABASE_URL?: string;
  SUPABASE_SERVICE_ROLE_KEY?: string;
  TELEGRAM_BOT_TOKEN?: string;
  TELEGRAM_ALLOWED_CHAT_ID?: string;
  TELEGRAM_WEBHOOK_SECRET?: string;
  TELEGRAM_CHAT_KEY?: string;
  PURGE_KEEP_LATEST?: string;
}

function getDefaultEnv(): EdgeEnv {
  if (typeof Deno !== "undefined" && Deno?.env) {
    return {
      SUPABASE_URL: Deno.env.get("SUPABASE_URL"),
      SUPABASE_SERVICE_ROLE_KEY: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"),
      TELEGRAM_BOT_TOKEN: Deno.env.get("TELEGRAM_BOT_TOKEN"),
      TELEGRAM_ALLOWED_CHAT_ID: Deno.env.get("TELEGRAM_ALLOWED_CHAT_ID"),
      TELEGRAM_WEBHOOK_SECRET: Deno.env.get("TELEGRAM_WEBHOOK_SECRET"),
      TELEGRAM_CHAT_KEY: Deno.env.get("TELEGRAM_CHAT_KEY"),
      PURGE_KEEP_LATEST: Deno.env.get("PURGE_KEEP_LATEST"),
    };
  }
  return {};
}

export function sanitizeLogMessage(err: unknown, extraSecrets: string[] = []): string {
  let msg = typeof err === "string"
    ? err
    : err instanceof Error
      ? err.message
      : String(err ?? "Unknown error");

  msg = msg
    .replace(/bearer\s+[a-z0-9._~+/-]+/gi, "Bearer [REDACTED]")
    .replace(/\/bot\d+:[A-Za-z0-9_-]+/g, "/bot[REDACTED]")
    .replace(/\b\d{6,}:[A-Za-z0-9_-]{20,}\b/g, "[BOT_TOKEN_REDACTED]");

  for (const secret of extraSecrets) {
    if (typeof secret === "string" && secret.length >= 4) {
      msg = msg.split(secret).join("[SECRET_REDACTED]");
    }
  }
  return msg;
}

export async function timingSafeEqualSecret(provided: string | null, expected: string): Promise<boolean> {
  if (!provided || !expected) return false;
  const enc = new TextEncoder();
  const [hashA, hashB] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(provided)),
    crypto.subtle.digest("SHA-256", enc.encode(expected)),
  ]);
  const viewA = new Uint8Array(hashA);
  const viewB = new Uint8Array(hashB);
  let diff = 0;
  for (let i = 0; i < viewA.length; i++) {
    diff |= viewA[i] ^ viewB[i];
  }
  return diff === 0;
}

export function isAllowedChatId(chatId: unknown, allowedChatId: unknown): boolean {
  if (chatId === null || chatId === undefined) return false;
  if (allowedChatId === null || allowedChatId === undefined) return false;
  const expected = String(allowedChatId).trim();
  const actual = String(chatId).trim();
  if (!/^-?\d+$/.test(expected) || !/^-?\d+$/.test(actual)) return false;
  return actual === expected;
}

function formatSenderName(msg: Record<string, any>): string {
  const from = msg?.from;
  if (from && typeof from === "object") {
    const fullName = [from.first_name, from.last_name]
      .filter((p) => typeof p === "string" && p.trim().length > 0)
      .join(" ")
      .trim();
    if (fullName) return fullName;
    if (typeof from.username === "string" && from.username.trim()) {
      return `@${from.username.trim()}`;
    }
  }
  const senderChat = msg?.sender_chat;
  if (senderChat && typeof senderChat === "object") {
    if (typeof senderChat.title === "string" && senderChat.title.trim()) {
      return senderChat.title.trim();
    }
    if (typeof senderChat.username === "string" && senderChat.username.trim()) {
      return `@${senderChat.username.trim()}`;
    }
  }
  return "Telegram User";
}

function normalizeEntities(entities: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(entities) || entities.length === 0) return [];
  const normalized: Array<Record<string, unknown>> = [];
  for (const entity of entities) {
    if (!entity || typeof entity !== "object") continue;
    const { offset, length, type, url } = entity as Record<string, unknown>;
    if (!Number.isInteger(offset) || (offset as number) < 0) continue;
    if (!Number.isInteger(length) || (length as number) <= 0) continue;
    if (typeof type !== "string" || !SUPPORTED_ENTITY_TYPES.has(type)) continue;
    if (type === "text_url") {
      if (typeof url !== "string" || !url.trim()) continue;
      normalized.push({ offset, length, type, url: url.trim() });
    } else {
      normalized.push({ offset, length, type });
    }
  }
  return normalized;
}

function truncatePreview(text: unknown, maxChars = MAX_PREVIEW_CHARS): string {
  if (typeof text !== "string") return "";
  const singleLine = text.replace(/\s+/g, " ").trim();
  if (singleLine.length <= maxChars) return singleLine;
  return `${singleLine.slice(0, maxChars - 1)}…`;
}

function buildPreviewText(msg: Record<string, any>): string {
  if (!msg || typeof msg !== "object") return "";
  if (typeof msg.text === "string" && msg.text.trim()) {
    return truncatePreview(msg.text);
  }
  const caption = typeof msg.caption === "string" ? msg.caption.trim() : "";
  if (Array.isArray(msg.photo) && msg.photo.length > 0) {
    return caption ? `📷 ${truncatePreview(caption)}` : "📷 Photo";
  }
  if (msg.sticker) {
    return msg.sticker.emoji ? `${msg.sticker.emoji} Sticker` : "Sticker";
  }
  if (msg.video || msg.animation) {
    return caption ? `🎬 ${truncatePreview(caption)}` : "🎬 Video";
  }
  if (msg.voice || msg.audio) {
    return caption ? `🎤 ${truncatePreview(caption)}` : "🎤 Voice message";
  }
  if (msg.document) {
    const name = msg.document.file_name || "Document";
    return caption ? `📎 ${name}: ${truncatePreview(caption)}` : `📎 ${name}`;
  }
  if (caption) return truncatePreview(caption);
  return "Message";
}

function selectPhotoSizes(photos: unknown): { smallest: Record<string, any> | null; largest: Record<string, any> | null } {
  if (!Array.isArray(photos) || photos.length === 0) {
    return { smallest: null, largest: null };
  }
  const valid = photos.filter((p) => p && typeof p.file_id === "string" && p.file_id.length > 0);
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
    largest: byAreaAsc[byAreaAsc.length - 1],
  };
}

function parseServiceMessage(msg: Record<string, any>): string | null {
  const actor = formatSenderName(msg);
  if (Array.isArray(msg.new_chat_members) && msg.new_chat_members.length > 0) {
    const names = msg.new_chat_members
      .map((u: Record<string, any>) =>
        [u.first_name, u.last_name].filter(Boolean).join(" ").trim() || (u.username ? `@${u.username}` : "Member")
      )
      .join(", ");
    return `${names} joined the group`;
  }
  if (msg.left_chat_member) {
    const u = msg.left_chat_member;
    const name = [u.first_name, u.last_name].filter(Boolean).join(" ").trim() || (u.username ? `@${u.username}` : "Member");
    return `${name} left the group`;
  }
  if (typeof msg.new_chat_title === "string" && msg.new_chat_title.trim()) {
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

function isHidePreviewCommand(text: unknown): boolean {
  if (typeof text !== "string") return false;
  return /^\/hide_preview(?:@[A-Za-z0-9_]+)?(?:\s|$)/i.test(text.trim());
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    const slice = bytes.subarray(i, i + chunkSize);
    binary += String.fromCharCode(...slice);
  }
  return btoa(binary);
}

async function downloadThumbnailBase64(
  botToken: string,
  fileId: string,
  fetchImpl: typeof fetch,
): Promise<string | null> {
  if (!botToken || !fileId) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const metaRes = await fetchImpl(
      `https://api.telegram.org/bot${botToken}/getFile?file_id=${encodeURIComponent(fileId)}`,
      { signal: controller.signal },
    );
    if (!metaRes.ok) return null;
    const metaJson = await metaRes.json();
    const filePath = metaJson?.result?.file_path;
    if (!metaJson?.ok || typeof filePath !== "string" || !filePath) return null;
    if (typeof metaJson.result.file_size === "number" && metaJson.result.file_size > MAX_THUMBNAIL_BYTES) {
      return null;
    }

    const fileRes = await fetchImpl(`https://api.telegram.org/file/bot${botToken}/${filePath}`, {
      signal: controller.signal,
    });
    if (!fileRes.ok) return null;
    const buf = await fileRes.arrayBuffer();
    if (!buf || buf.byteLength === 0 || buf.byteLength > MAX_THUMBNAIL_BYTES) {
      return null;
    }
    return bytesToBase64(new Uint8Array(buf));
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function isTelegramGroupAdmin(
  botToken: string,
  chatId: number | string,
  userId: number | null,
  fetchImpl: typeof fetch,
): Promise<boolean> {
  if (!botToken || !userId) return false;
  try {
    const res = await fetchImpl(
      `https://api.telegram.org/bot${botToken}/getChatMember?chat_id=${encodeURIComponent(String(chatId))}&user_id=${encodeURIComponent(String(userId))}`,
    );
    if (!res.ok) return false;
    const data = await res.json();
    const status = data?.result?.status;
    return status === "creator" || status === "administrator";
  } catch {
    return false;
  }
}

function postgrestHeaders(serviceRoleKey: string, extra: Record<string, string> = {}): Record<string, string> {
  return {
    "Content-Type": "application/json",
    apikey: serviceRoleKey,
    Authorization: `Bearer ${serviceRoleKey}`,
    ...extra,
  };
}

async function upsertChatViaPostgrest(
  supabaseUrl: string,
  serviceRoleKey: string,
  chatUpdate: Record<string, unknown>,
  fetchImpl: typeof fetch,
): Promise<void> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const chatKey = String(chatUpdate.chat_key);
  const nowIso = new Date().toISOString();

  // PATCH first so existing pinned_message_* and avatar_url are preserved when null.
  const patchPayload: Record<string, unknown> = {
    telegram_chat_id: chatUpdate.telegram_chat_id,
    title: chatUpdate.title,
    updated_at: nowIso,
  };
  if (chatUpdate.pinned_message_id !== null && chatUpdate.pinned_message_id !== undefined) {
    patchPayload.pinned_message_id = chatUpdate.pinned_message_id;
    patchPayload.pinned_preview = chatUpdate.pinned_preview ?? null;
    patchPayload.pinned_sender_name = chatUpdate.pinned_sender_name ?? null;
  }

  const patchRes = await fetchImpl(
    `${baseUrl}/rest/v1/telegram_mirror_chat?chat_key=eq.${encodeURIComponent(chatKey)}`,
    {
      method: "PATCH",
      headers: postgrestHeaders(serviceRoleKey, { Prefer: "return=representation" }),
      body: JSON.stringify(patchPayload),
    },
  );
  if (!patchRes.ok) {
    throw new Error(`PATCH telegram_mirror_chat failed (${patchRes.status}): ${await patchRes.text()}`);
  }
  const patchedRows = await patchRes.json();
  if (Array.isArray(patchedRows) && patchedRows.length > 0) {
    return;
  }

  const insertPayload = {
    chat_key: chatKey,
    telegram_chat_id: chatUpdate.telegram_chat_id,
    title: chatUpdate.title,
    avatar_url: null,
    pinned_message_id: chatUpdate.pinned_message_id ?? null,
    pinned_preview: chatUpdate.pinned_preview ?? null,
    pinned_sender_name: chatUpdate.pinned_sender_name ?? null,
    updated_at: nowIso,
  };
  const insertRes = await fetchImpl(
    `${baseUrl}/rest/v1/telegram_mirror_chat?on_conflict=chat_key`,
    {
      method: "POST",
      headers: postgrestHeaders(serviceRoleKey, { Prefer: "resolution=merge-duplicates" }),
      body: JSON.stringify(insertPayload),
    },
  );
  if (!insertRes.ok) {
    throw new Error(`POST telegram_mirror_chat failed (${insertRes.status}): ${await insertRes.text()}`);
  }
}

async function upsertMessageViaPostgrest(
  supabaseUrl: string,
  serviceRoleKey: string,
  record: Record<string, unknown>,
  isEdited: boolean,
  fetchImpl: typeof fetch,
): Promise<void> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const chatKey = String(record.chat_key);
  const messageId = String(record.telegram_message_id);

  if (isEdited) {
    const patchPayload: Record<string, unknown> = {
      sender_id: record.sender_id,
      sender_name: record.sender_name,
      sender_is_bot: record.sender_is_bot,
      content_type: record.content_type,
      text_content: record.text_content,
      entities_json: record.entities_json,
      media_width: record.media_width,
      media_height: record.media_height,
      file_name: record.file_name,
      file_size: record.file_size,
      edited_at: new Date().toISOString(),
    };
    if (record.media_thumb_base64) {
      patchPayload.media_thumb_base64 = record.media_thumb_base64;
    }
    if (record.reply_to_message_id !== null && record.reply_to_message_id !== undefined) {
      patchPayload.reply_to_message_id = record.reply_to_message_id;
      patchPayload.reply_sender_name = record.reply_sender_name ?? null;
      patchPayload.reply_preview_text = record.reply_preview_text ?? null;
    }

    const patchRes = await fetchImpl(
      `${baseUrl}/rest/v1/telegram_mirror_messages?chat_key=eq.${encodeURIComponent(chatKey)}&telegram_message_id=eq.${encodeURIComponent(messageId)}`,
      {
        method: "PATCH",
        headers: postgrestHeaders(serviceRoleKey, { Prefer: "return=representation" }),
        body: JSON.stringify(patchPayload),
      },
    );
    if (!patchRes.ok) {
      throw new Error(`PATCH telegram_mirror_messages failed (${patchRes.status}): ${await patchRes.text()}`);
    }
    const patchedRows = await patchRes.json();
    if (Array.isArray(patchedRows) && patchedRows.length > 0) {
      return;
    }
  }

  const upsertRes = await fetchImpl(
    `${baseUrl}/rest/v1/telegram_mirror_messages?on_conflict=chat_key,telegram_message_id`,
    {
      method: "POST",
      headers: postgrestHeaders(serviceRoleKey, { Prefer: "resolution=merge-duplicates" }),
      body: JSON.stringify(record),
    },
  );
  if (!upsertRes.ok) {
    throw new Error(`POST telegram_mirror_messages failed (${upsertRes.status}): ${await upsertRes.text()}`);
  }
}

async function softDeleteMessageViaPostgrest(
  supabaseUrl: string,
  serviceRoleKey: string,
  chatKey: string,
  targetMessageId: number,
  fetchImpl: typeof fetch,
): Promise<void> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const nowIso = new Date().toISOString();

  const msgRes = await fetchImpl(
    `${baseUrl}/rest/v1/telegram_mirror_messages?chat_key=eq.${encodeURIComponent(chatKey)}&telegram_message_id=eq.${targetMessageId}`,
    {
      method: "PATCH",
      headers: postgrestHeaders(serviceRoleKey),
      body: JSON.stringify({ is_deleted: true, edited_at: nowIso }),
    },
  );
  if (!msgRes.ok) {
    throw new Error(`Soft-delete message failed (${msgRes.status}): ${await msgRes.text()}`);
  }

  const chatRes = await fetchImpl(
    `${baseUrl}/rest/v1/telegram_mirror_chat?chat_key=eq.${encodeURIComponent(chatKey)}&pinned_message_id=eq.${targetMessageId}`,
    {
      method: "PATCH",
      headers: postgrestHeaders(serviceRoleKey),
      body: JSON.stringify({
        pinned_message_id: null,
        pinned_preview: null,
        pinned_sender_name: null,
        updated_at: nowIso,
      }),
    },
  );
  if (!chatRes.ok) {
    throw new Error(`Clear pinned message failed (${chatRes.status}): ${await chatRes.text()}`);
  }
}

async function purgeOldMessagesViaPostgrest(
  supabaseUrl: string,
  serviceRoleKey: string,
  chatKey: string,
  keepLatest: number,
  fetchImpl: typeof fetch,
): Promise<void> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const res = await fetchImpl(`${baseUrl}/rest/v1/rpc/purge_old_telegram_mirror_messages`, {
    method: "POST",
    headers: postgrestHeaders(serviceRoleKey),
    body: JSON.stringify({ p_chat_key: chatKey, p_keep_latest: keepLatest }),
  });
  if (!res.ok) {
    console.warn("[telegram-mirror-webhook] Retention purge warning:", res.status);
  }
}

export function createWebhookHandler({
  envProvider = getDefaultEnv,
  fetchImpl = fetch,
}: {
  envProvider?: () => EdgeEnv;
  fetchImpl?: typeof fetch;
} = {}) {
  return async function handleRequest(req: Request): Promise<Response> {
    if (req.method === "GET") {
      return Response.json({ ok: true, mode: "supabase-edge-webhook" }, { status: 200 });
    }
    if (req.method !== "POST") {
      return Response.json({ ok: false, error: "Method Not Allowed" }, { status: 405 });
    }

    const env = envProvider();
    const supabaseUrl = env.SUPABASE_URL?.trim() || "";
    const serviceRoleKey = env.SUPABASE_SERVICE_ROLE_KEY?.trim() || "";
    const allowedChatId = env.TELEGRAM_ALLOWED_CHAT_ID?.trim() || "";
    const webhookSecret = env.TELEGRAM_WEBHOOK_SECRET?.trim() || "";
    const botToken = env.TELEGRAM_BOT_TOKEN?.trim() || "";
    const chatKey = env.TELEGRAM_CHAT_KEY?.trim() || DEFAULT_CHAT_KEY;
    const keepLatest = Number.parseInt(env.PURGE_KEEP_LATEST || "", 10) || DEFAULT_KEEP_LATEST;

    if (!supabaseUrl || !serviceRoleKey || !allowedChatId || !webhookSecret) {
      console.error("[telegram-mirror-webhook] Missing required environment variables.");
      return Response.json({ ok: false, error: "Server Misconfigured" }, { status: 500 });
    }

    const providedSecret = req.headers.get("x-telegram-bot-api-secret-token");
    if (!(await timingSafeEqualSecret(providedSecret, webhookSecret))) {
      return Response.json({ ok: false, error: "Unauthorized" }, { status: 401 });
    }

    let update: Record<string, any>;
    try {
      update = await req.json();
    } catch {
      return Response.json({ ok: false, error: "Invalid JSON" }, { status: 400 });
    }

    const isEdited = Boolean(update?.edited_message || update?.edited_channel_post);
    const msg = update?.message || update?.edited_message || update?.channel_post || update?.edited_channel_post;

    if (!msg || typeof msg !== "object" || !msg.chat || !Number.isInteger(msg.message_id)) {
      return Response.json({ ok: true, ignored: "unsupported_update_type" }, { status: 200 });
    }

    if (!isAllowedChatId(msg.chat.id, allowedChatId)) {
      return Response.json({ ok: true, ignored: "chat_not_allowed" }, { status: 200 });
    }

    try {
      if (isHidePreviewCommand(msg.text)) {
        const targetId = msg.reply_to_message?.message_id;
        if (!Number.isInteger(targetId)) {
          return Response.json({ ok: true, ignored: "hide_preview_missing_reply" }, { status: 200 });
        }
        const isSenderChatAdmin = Boolean(
          msg.sender_chat && String(msg.sender_chat.id) === String(msg.chat.id),
        );
        const actorId = typeof msg.from?.id === "number" ? msg.from.id : null;
        const allowed = isSenderChatAdmin || await isTelegramGroupAdmin(botToken, msg.chat.id, actorId, fetchImpl);
        if (!allowed) {
          return Response.json({ ok: true, ignored: "hide_preview_not_admin" }, { status: 200 });
        }
        await softDeleteMessageViaPostgrest(supabaseUrl, serviceRoleKey, chatKey, targetId, fetchImpl);
        return Response.json({ ok: true, hidden_message_id: targetId }, { status: 200 });
      }

      const sentUnix = Number(msg.date);
      const sentAt = Number.isFinite(sentUnix) && sentUnix > 0
        ? new Date(sentUnix * 1000).toISOString()
        : new Date().toISOString();
      const editUnix = Number(msg.edit_date);
      const editedAt = isEdited
        ? (Number.isFinite(editUnix) && editUnix > 0 ? new Date(editUnix * 1000).toISOString() : new Date().toISOString())
        : null;

      let contentType = "unsupported";
      let textContent = "";
      let entitiesJson: Array<Record<string, unknown>> = [];
      let mediaWidth = 0;
      let mediaHeight = 0;
      let fileName: string | null = null;
      let fileSize = 0;
      let thumbFileId: string | null = null;

      const serviceText = parseServiceMessage(msg);
      if (serviceText !== null) {
        contentType = "service";
        textContent = serviceText;
      } else if (Array.isArray(msg.photo) && msg.photo.length > 0) {
        const { smallest, largest } = selectPhotoSizes(msg.photo);
        contentType = "photo";
        textContent = typeof msg.caption === "string" ? msg.caption : "";
        entitiesJson = normalizeEntities(msg.caption_entities);
        mediaWidth = Number(largest?.width || smallest?.width || 0);
        mediaHeight = Number(largest?.height || smallest?.height || 0);
        fileSize = Number(largest?.file_size || 0);
        thumbFileId = smallest?.file_id || null;
      } else if (msg.sticker) {
        contentType = "sticker";
        textContent = typeof msg.sticker.emoji === "string" ? msg.sticker.emoji : "";
        mediaWidth = Number(msg.sticker.width || 0);
        mediaHeight = Number(msg.sticker.height || 0);
        fileSize = Number(msg.sticker.file_size || 0);
        thumbFileId = msg.sticker.thumbnail?.file_id || msg.sticker.thumb?.file_id || null;
      } else if (msg.video || msg.animation) {
        const v = msg.video || msg.animation;
        contentType = "video";
        textContent = typeof msg.caption === "string" ? msg.caption : "";
        entitiesJson = normalizeEntities(msg.caption_entities);
        mediaWidth = Number(v.width || 0);
        mediaHeight = Number(v.height || 0);
        fileName = typeof v.file_name === "string" ? v.file_name : null;
        fileSize = Number(v.file_size || 0);
        thumbFileId = v.thumbnail?.file_id || v.thumb?.file_id || null;
      } else if (msg.voice || msg.audio) {
        const a = msg.voice || msg.audio;
        contentType = "voice";
        textContent = typeof msg.caption === "string" ? msg.caption : "";
        entitiesJson = normalizeEntities(msg.caption_entities);
        fileName = typeof a.file_name === "string" ? a.file_name : (typeof a.title === "string" ? a.title : null);
        fileSize = Number(a.file_size || 0);
      } else if (msg.document) {
        contentType = "document";
        textContent = typeof msg.caption === "string" ? msg.caption : "";
        entitiesJson = normalizeEntities(msg.caption_entities);
        fileName = typeof msg.document.file_name === "string" ? msg.document.file_name : null;
        fileSize = Number(msg.document.file_size || 0);
      } else if (typeof msg.text === "string") {
        contentType = "text";
        textContent = msg.text;
        entitiesJson = normalizeEntities(msg.entities);
      } else if (typeof msg.caption === "string") {
        contentType = "unsupported";
        textContent = msg.caption;
        entitiesJson = normalizeEntities(msg.caption_entities);
      }

      const thumbBase64 = thumbFileId && botToken
        ? await downloadThumbnailBase64(botToken, thumbFileId, fetchImpl)
        : null;

      const record: Record<string, unknown> = {
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
        media_thumb_base64: thumbBase64,
        file_name: fileName,
        file_size: fileSize,
        reply_to_message_id: Number.isInteger(msg.reply_to_message?.message_id) ? msg.reply_to_message.message_id : null,
        reply_sender_name: Number.isInteger(msg.reply_to_message?.message_id) ? formatSenderName(msg.reply_to_message) : null,
        reply_preview_text: Number.isInteger(msg.reply_to_message?.message_id) ? buildPreviewText(msg.reply_to_message) : null,
        sent_at: sentAt,
        edited_at: editedAt,
        is_deleted: false,
      };

      const chatTitle = (typeof msg.new_chat_title === "string" && msg.new_chat_title.trim())
        ? msg.new_chat_title.trim()
        : (typeof msg.chat.title === "string" && msg.chat.title.trim())
          ? msg.chat.title.trim()
          : (typeof msg.chat.username === "string" && msg.chat.username.trim())
            ? msg.chat.username.trim()
            : "Telegram Community";

      const chatUpdate: Record<string, unknown> = {
        chat_key: chatKey,
        telegram_chat_id: msg.chat.id,
        title: chatTitle,
        pinned_message_id: msg.pinned_message && Number.isInteger(msg.pinned_message.message_id)
          ? msg.pinned_message.message_id
          : null,
        pinned_preview: msg.pinned_message && Number.isInteger(msg.pinned_message.message_id)
          ? buildPreviewText(msg.pinned_message)
          : null,
        pinned_sender_name: msg.pinned_message && Number.isInteger(msg.pinned_message.message_id)
          ? formatSenderName(msg.pinned_message)
          : null,
      };

      await upsertChatViaPostgrest(supabaseUrl, serviceRoleKey, chatUpdate, fetchImpl);
      await upsertMessageViaPostgrest(supabaseUrl, serviceRoleKey, record, isEdited, fetchImpl);

      if (msg.message_id % 100 === 0) {
        await purgeOldMessagesViaPostgrest(supabaseUrl, serviceRoleKey, chatKey, keepLatest, fetchImpl);
      }

      return Response.json({ ok: true, message_id: msg.message_id }, { status: 200 });
    } catch (err) {
      console.error(
        "[telegram-mirror-webhook] Error:",
        sanitizeLogMessage(err, [serviceRoleKey, botToken, webhookSecret]),
      );
      return Response.json({ ok: false, error: "Internal Error" }, { status: 500 });
    }
  };
}

if (typeof Deno !== "undefined" && typeof Deno.serve === "function") {
  Deno.serve(createWebhookHandler());
}
