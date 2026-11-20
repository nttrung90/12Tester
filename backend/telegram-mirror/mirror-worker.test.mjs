import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  downloadTelegramThumbnailBase64,
  isAllowedChatId,
  normalizeEntities,
  parseTelegramUpdate
} from './telegram-parser.mjs';
import {
  createMirrorStore,
  resolveTlsConfiguration,
  sanitizeError,
  sanitizeUrl
} from './mirror-store.mjs';
import {
  computeBackoffMs,
  createOffsetStateStore,
  createPollingRunner,
  createWebhookHandler,
  processTelegramUpdate,
  verifyWebhookSecret
} from './mirror-worker.mjs';
import {
  parseDesktopExport,
  runImport
} from './import-desktop-export.mjs';

const ALLOWED_CHAT_ID = '-1001234567890';
const SECRET_TOKEN = 'super-secret-webhook-token-123456';

function createFakeStore() {
  const chats = new Map();
  const messages = new Map();
  const calls = [];

  return {
    chats,
    messages,
    calls,
    async upsertChat(chatMeta) {
      calls.push({ op: 'upsertChat', chatMeta });
      chats.set(chatMeta.chat_key, { ...chats.get(chatMeta.chat_key), ...chatMeta });
    },
    async upsertMessage(record, options = {}) {
      calls.push({ op: 'upsertMessage', record, options });
      const key = `${record.chat_key}:${record.telegram_message_id}`;
      const existing = messages.get(key);
      messages.set(key, {
        ...record,
        is_deleted: Boolean(existing?.is_deleted || record.is_deleted)
      });
    },
    async upsertChatAndMessage(chatMeta, record, options = {}) {
      calls.push({ op: 'upsertChatAndMessage', chatMeta, record, options });
      const prevChat = chats.get(chatMeta.chat_key) || {};
      chats.set(chatMeta.chat_key, {
        ...prevChat,
        ...chatMeta,
        pinned_message_id: chatMeta.pinned_message_id ?? prevChat.pinned_message_id ?? null,
        pinned_preview: chatMeta.pinned_preview ?? prevChat.pinned_preview ?? null,
        pinned_sender_name: chatMeta.pinned_sender_name ?? prevChat.pinned_sender_name ?? null
      });
      const key = `${record.chat_key}:${record.telegram_message_id}`;
      const existing = messages.get(key);
      messages.set(key, {
        ...record,
        media_thumb_base64: record.media_thumb_base64 ?? existing?.media_thumb_base64 ?? null,
        is_deleted: Boolean(existing?.is_deleted || record.is_deleted)
      });
    },
    async softDeleteMessage({ chatKey, telegramMessageId }) {
      calls.push({ op: 'softDeleteMessage', chatKey, telegramMessageId });
      const key = `${chatKey}:${telegramMessageId}`;
      const existing = messages.get(key);
      if (existing) {
        existing.is_deleted = true;
        existing.edited_at = new Date().toISOString();
        return 1;
      }
      return 0;
    },
    async purgeOldMessages({ chatKey, keepLatest }) {
      calls.push({ op: 'purgeOldMessages', chatKey, keepLatest });
      return 0;
    },
    async close() {}
  };
}

test('telegram-parser: filters strictly by TELEGRAM_ALLOWED_CHAT_ID', () => {
  assert.equal(isAllowedChatId(-1001234567890, ALLOWED_CHAT_ID), true);
  assert.equal(isAllowedChatId('-1001234567890', ALLOWED_CHAT_ID), true);
  assert.equal(isAllowedChatId(-1009999999999, ALLOWED_CHAT_ID), false);
  assert.equal(isAllowedChatId(-1001234567890, ''), false);
  assert.equal(isAllowedChatId(-1001234567890, 'not-a-number'), false);

  const foreignUpdate = {
    update_id: 1,
    message: {
      message_id: 50,
      date: 1763553600,
      chat: { id: -1009999999999, title: 'Other Group' },
      from: { id: 10, first_name: 'Eve' },
      text: 'Should be ignored'
    }
  };
  const parsed = parseTelegramUpdate(foreignUpdate, { allowedChatId: ALLOWED_CHAT_ID });
  assert.equal(parsed.kind, 'ignored');
  assert.equal(parsed.reason, 'chat_not_allowed');
});

test('telegram-parser: maps all 12 entity types and skips unsupported/invalid entities', () => {
  const rawEntities = [
    { offset: 0, length: 19, type: 'url' },
    { offset: 20, length: 4, type: 'text_url', url: 'https://12tester.app' },
    { offset: 25, length: 4, type: 'text_url' }, // missing url -> skipped
    { offset: 30, length: 4, type: 'bold' },
    { offset: 35, length: 4, type: 'italic' },
    { offset: 40, length: 4, type: 'code' },
    { offset: 45, length: 4, type: 'pre' },
    { offset: 50, length: 4, type: 'underline' },
    { offset: 55, length: 4, type: 'strikethrough' },
    { offset: 60, length: 4, type: 'spoiler' },
    { offset: 65, length: 5, type: 'mention' },
    { offset: 71, length: 6, type: 'bot_command' },
    { offset: 78, length: 8, type: 'hashtag' },
    { offset: 90, length: 3, type: 'custom_emoji' } // unsupported -> skipped
  ];

  const mapped = normalizeEntities(rawEntities);
  assert.equal(mapped.length, 12);
  assert.deepEqual(mapped.map(e => e.type), [
    'url', 'text_url', 'bold', 'italic', 'code', 'pre',
    'underline', 'strikethrough', 'spoiler', 'mention', 'bot_command', 'hashtag'
  ]);
  assert.equal(mapped[1].url, 'https://12tester.app');
});

test('telegram-parser: parses photo smallest thumbnail, reply preview, pinned message, and edited message', async () => {
  const photoUpdate = {
    update_id: 2,
    message: {
      message_id: 200,
      date: 1763553600,
      chat: { id: -1001234567890, title: '12Tester Group' },
      from: { id: 1001, first_name: 'Alice', last_name: 'Nguyen', is_bot: false },
      caption: 'Check bug',
      caption_entities: [{ offset: 0, length: 5, type: 'bold' }],
      photo: [
        { file_id: 'large_id', width: 1280, height: 960, file_size: 90000 },
        { file_id: 'thumb_small_id', width: 90, height: 67, file_size: 1400 },
        { file_id: 'med_id', width: 320, height: 240, file_size: 12000 }
      ],
      reply_to_message: {
        message_id: 199,
        from: { id: 1002, first_name: 'Bob' },
        text: 'Please share screenshot'
      }
    }
  };

  const parsed = parseTelegramUpdate(photoUpdate, { allowedChatId: ALLOWED_CHAT_ID });
  assert.equal(parsed.kind, 'message');
  assert.equal(parsed.thumbFileId, 'thumb_small_id');
  assert.equal(parsed.record.content_type, 'photo');
  assert.equal(parsed.record.media_width, 1280);
  assert.equal(parsed.record.media_height, 960);
  assert.equal(parsed.record.sender_name, 'Alice Nguyen');
  assert.equal(parsed.record.reply_to_message_id, 199);
  assert.equal(parsed.record.reply_sender_name, 'Bob');
  assert.equal(parsed.record.reply_preview_text, 'Please share screenshot');
  assert.deepEqual(parsed.record.entities_json, [{ offset: 0, length: 5, type: 'bold' }]);

  const pinUpdate = {
    update_id: 3,
    message: {
      message_id: 201,
      date: 1763553660,
      chat: { id: -1001234567890, title: '12Tester Group' },
      from: { id: 1000, first_name: 'Admin' },
      pinned_message: {
        message_id: 200,
        from: { id: 1001, first_name: 'Alice' },
        caption: 'Pinned photo caption',
        photo: [{ file_id: 'x', width: 90, height: 90 }]
      }
    }
  };
  const parsedPin = parseTelegramUpdate(pinUpdate, { allowedChatId: ALLOWED_CHAT_ID });
  assert.equal(parsedPin.record.content_type, 'service');
  assert.equal(parsedPin.chatUpdate.pinned_message_id, 200);
  assert.equal(parsedPin.chatUpdate.pinned_sender_name, 'Alice');
  assert.match(parsedPin.chatUpdate.pinned_preview, /Pinned photo caption/);

  const editUpdate = {
    update_id: 4,
    edited_message: {
      message_id: 199,
      date: 1763553500,
      edit_date: 1763553700,
      chat: { id: -1001234567890, title: '12Tester Group' },
      from: { id: 1002, first_name: 'Bob' },
      text: 'Updated text'
    }
  };
  const parsedEdit = parseTelegramUpdate(editUpdate, { allowedChatId: ALLOWED_CHAT_ID });
  assert.equal(parsedEdit.isEdited, true);
  assert.equal(parsedEdit.record.text_content, 'Updated text');
  assert.equal(parsedEdit.record.edited_at, new Date(1763553700 * 1000).toISOString());
});

test('downloadTelegramThumbnailBase64 downloads tiny image as base64 and enforces maxBytes', async () => {
  const sampleBytes = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x01, 0x02]);
  const mockFetch = async (url) => {
    if (url.includes('/getFile')) {
      return {
        ok: true,
        json: async () => ({ ok: true, result: { file_path: 'photos/thumb.jpg', file_size: sampleBytes.byteLength } })
      };
    }
    return {
      ok: true,
      arrayBuffer: async () => sampleBytes.buffer
    };
  };

  const b64 = await downloadTelegramThumbnailBase64({
    botToken: '123456:TESTTOKEN',
    fileId: 'thumb_small_id',
    fetchImpl: mockFetch,
    maxBytes: 1024
  });
  assert.equal(b64, Buffer.from(sampleBytes).toString('base64'));

  const oversized = await downloadTelegramThumbnailBase64({
    botToken: '123456:TESTTOKEN',
    fileId: 'thumb_small_id',
    fetchImpl: mockFetch,
    maxBytes: 2
  });
  assert.equal(oversized, null);
});

test('processTelegramUpdate: /hide_preview requires admin and soft-deletes target without storing command', async () => {
  const store = createFakeStore();

  // Seed message 300
  await processTelegramUpdate({
    update_id: 10,
    message: {
      message_id: 300,
      date: 1763553600,
      chat: { id: -1001234567890, title: 'Group' },
      from: { id: 55, first_name: 'Spammer' },
      text: 'Bad message'
    }
  }, { store, allowedChatId: ALLOWED_CHAT_ID });
  assert.equal(store.messages.get('community_main:300').is_deleted, false);

  // Non-admin attempts /hide_preview
  const nonAdminRes = await processTelegramUpdate({
    update_id: 11,
    message: {
      message_id: 301,
      date: 1763553610,
      chat: { id: -1001234567890, title: 'Group' },
      from: { id: 77, first_name: 'RegularMember' },
      text: '/hide_preview',
      reply_to_message: { message_id: 300 }
    }
  }, {
    store,
    allowedChatId: ALLOWED_CHAT_ID,
    telegramApi: {
      getChatMember: async () => ({ status: 'member' })
    }
  });
  assert.equal(nonAdminRes.action, 'denied_hide_preview');
  assert.equal(store.messages.get('community_main:300').is_deleted, false);
  assert.equal(store.messages.has('community_main:301'), false);

  // Admin executes /hide_preview
  const adminRes = await processTelegramUpdate({
    update_id: 12,
    message: {
      message_id: 302,
      date: 1763553620,
      chat: { id: -1001234567890, title: 'Group' },
      from: { id: 88, first_name: 'Mod' },
      text: '/hide_preview@my_bot',
      reply_to_message: { message_id: 300 }
    }
  }, {
    store,
    allowedChatId: ALLOWED_CHAT_ID,
    telegramApi: {
      getChatMember: async () => ({ status: 'administrator' })
    }
  });
  assert.equal(adminRes.action, 'hidden');
  assert.equal(adminRes.targetMessageId, 300);
  assert.equal(store.messages.get('community_main:300').is_deleted, true);
  assert.equal(store.messages.has('community_main:302'), false);
});

test('webhook mode: verifies X-Telegram-Bot-Api-Secret-Token via timingSafeEqual and processes updates', async () => {
  assert.equal(verifyWebhookSecret(SECRET_TOKEN, SECRET_TOKEN), true);
  assert.equal(verifyWebhookSecret('wrong-token-same-length-123456789', SECRET_TOKEN), false);
  assert.equal(verifyWebhookSecret('short', SECRET_TOKEN), false);
  assert.equal(verifyWebhookSecret('', SECRET_TOKEN), false);

  const store = createFakeStore();
  const handler = createWebhookHandler({
    store,
    telegramApi: {
      downloadThumbnailBase64: async () => 'dGVzdHRodW1i'
    },
    allowedChatId: ALLOWED_CHAT_ID,
    webhookSecret: SECRET_TOKEN,
    keepLatest: 500,
    purgeEvery: 1
  });

  const server = http.createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));

  try {
    const { port } = server.address();
    const baseUrl = `http://127.0.0.1:${port}`;

    // Healthz
    const healthRes = await fetch(`${baseUrl}/healthz`);
    assert.equal(healthRes.status, 200);
    const healthJson = await healthRes.json();
    assert.equal(healthJson.ok, true);

    // Missing secret -> 401
    const missingSecretRes = await fetch(`${baseUrl}/telegram/webhook`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ update_id: 1 })
    });
    assert.equal(missingSecretRes.status, 401);

    // Wrong secret -> 401
    const wrongSecretRes = await fetch(`${baseUrl}/telegram/webhook`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': 'wrong-secret'
      },
      body: JSON.stringify({ update_id: 1 })
    });
    assert.equal(wrongSecretRes.status, 401);

    // Valid secret + photo update -> 200
    const okRes = await fetch(`${baseUrl}/telegram/webhook`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': SECRET_TOKEN
      },
      body: JSON.stringify({
        update_id: 50,
        message: {
          message_id: 500,
          date: 1763553600,
          chat: { id: -1001234567890, title: '12Tester Group' },
          from: { id: 11, first_name: 'Tester' },
          photo: [{ file_id: 'small_p', width: 90, height: 90 }],
          caption: 'Webhook photo'
        }
      })
    });
    assert.equal(okRes.status, 200);
    const okJson = await okRes.json();
    assert.equal(okJson.action, 'mirrored');
    assert.equal(store.messages.get('community_main:500').media_thumb_base64, 'dGVzdHRodW1i');
    assert.ok(store.calls.some(c => c.op === 'purgeOldMessages'));
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});

test('polling mode: enforces in-flight guard, tracks offset, and applies exponential backoff on errors', async () => {
  const store = createFakeStore();
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tg-mirror-offset-'));
  const offsetFile = path.join(tmpDir, 'offset.json');
  const offsetStore = createOffsetStateStore({ filePath: offsetFile });

  let releasePoll1;
  const poll1Gate = new Promise(r => { releasePoll1 = r; });
  let callIndex = 0;

  const telegramApi = {
    async getUpdates({ offset }) {
      callIndex += 1;
      if (callIndex === 1) {
        await poll1Gate;
        return [
          {
            update_id: 100,
            message: {
              message_id: 701,
              date: 1763553600,
              chat: { id: -1001234567890, title: 'Group' },
              from: { id: 1, first_name: 'A' },
              text: 'First polled'
            }
          }
        ];
      }
      if (callIndex === 2 || callIndex === 3) {
        throw new Error('Network failure');
      }
      assert.equal(offset, 101, 'Subsequent poll must send offset = 101');
      return [];
    }
  };

  const runner = createPollingRunner({
    store,
    telegramApi,
    allowedChatId: ALLOWED_CHAT_ID,
    offsetStore,
    baseBackoffMs: 1000,
    maxBackoffMs: 8000,
    logger: { error: () => {}, warn: () => {} }
  });

  // Start poll 1 (held)
  const p1 = runner.pollOnce();
  // Concurrent poll 2 -> skipped by in-flight guard
  const p2 = await runner.pollOnce();
  assert.equal(p2.skipped, true);
  assert.equal(p2.reason, 'in_flight');

  releasePoll1();
  const r1 = await p1;
  assert.equal(r1.processed, 1);
  assert.equal(r1.nextOffset, 101);
  assert.equal(offsetStore.load(), 101);

  // Error 1 -> backoff 1000ms
  const err1 = await runner.pollOnce();
  assert.equal(err1.consecutiveErrors, 1);
  assert.equal(err1.backoffMs, 1000);

  // Error 2 -> backoff 2000ms
  const err2 = await runner.pollOnce();
  assert.equal(err2.consecutiveErrors, 2);
  assert.equal(err2.backoffMs, 2000);

  // Recovery -> resets backoff to 0
  const ok = await runner.pollOnce();
  assert.equal(ok.backoffMs, 0);
  assert.equal(runner.getState().consecutiveErrors, 0);

  // Verify persisted offset reloads from disk
  const reloadedOffset = createOffsetStateStore({ filePath: offsetFile });
  assert.equal(reloadedOffset.load(), 101);
  assert.equal(computeBackoffMs(5, 2000, 10000), 10000);

  fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('mirror-store: sanitization, strict TLS enforcement, and SQL transaction execution', async () => {
  assert.equal(
    sanitizeUrl('postgres://postgres:secretpw@db.example.com:5432/postgres'),
    'postgres://***@db.example.com:5432/postgres'
  );
  const scrubbed = sanitizeError(
    new Error('Failed postgres://u:pw@host/db with /bot123456:ABCDEF_ghij and my-webhook-secret'),
    ['my-webhook-secret']
  );
  assert.ok(!scrubbed.includes('pw'));
  assert.ok(!scrubbed.includes('123456:ABCDEF_ghij'));
  assert.ok(!scrubbed.includes('my-webhook-secret'));

  assert.throws(
    () => resolveTlsConfiguration('postgres://u:p@host:5432/db?sslmode=disable'),
    /Insecure database TLS configuration/
  );
  assert.throws(
    () => resolveTlsConfiguration('postgres://u:p@host:5432/db?sslmode=verify-ca'),
    /Insecure database TLS configuration/
  );
  const strictCfg = resolveTlsConfiguration('postgres://u:p@host:5432/db?sslmode=require');
  assert.deepEqual(strictCfg.ssl, { rejectUnauthorized: true });
  const localCfg = resolveTlsConfiguration('postgres://u:p@localhost:5432/db?sslmode=require', 'false');
  assert.equal(localCfg.ssl, false);

  const queries = [];
  const mockPool = {
    connect: async () => ({
      query: async (sql, params) => {
        queries.push({ sql, params });
        if (sql.includes('purge_old_telegram_mirror_messages')) {
          return { rows: [{ deleted_count: 7 }] };
        }
        return { rowCount: 1, rows: [] };
      },
      release: () => {}
    })
  };
  const store = createMirrorStore({ pool: mockPool });
  await store.upsertChatAndMessage(
    { chat_key: 'community_main', telegram_chat_id: -1001, title: 'T' },
    {
      chat_key: 'community_main',
      telegram_message_id: 1,
      sender_id: 2,
      sender_name: 'Alice',
      sender_is_bot: false,
      content_type: 'text',
      text_content: 'Hi',
      entities_json: [],
      sent_at: '2026-11-19T12:00:00.000Z'
    }
  );
  assert.equal(queries[0].sql, 'BEGIN');
  assert.match(queries[1].sql, /INSERT INTO public\.telegram_mirror_chat/);
  assert.match(queries[2].sql, /INSERT INTO public\.telegram_mirror_messages/);
  assert.equal(queries[3].sql, 'COMMIT');

  const purged = await store.purgeOldMessages({ chatKey: 'community_main', keepLatest: 250 });
  assert.equal(purged, 7);
});

test('import-desktop-export: imports result.json with entities, replies, pins, and chat validation', async () => {
  const exportFixture = {
    name: '12Tester Community',
    type: 'private_supergroup',
    id: 1234567890,
    messages: [
      {
        id: 10,
        type: 'message',
        date_unixtime: '1763550000',
        from: 'Alice',
        from_id: 'user1001',
        text_entities: [
          { type: 'plain', text: 'Visit ' },
          { type: 'text_link', text: '12Tester', href: 'https://12tester.app' },
          { type: 'plain', text: ' ' },
          { type: 'hashtag', text: '#android' }
        ]
      },
      {
        id: 11,
        type: 'message',
        date_unixtime: '1763550100',
        from: 'Bob',
        from_id: 'user1002',
        reply_to_message_id: 10,
        photo: 'photos/photo_1.jpg',
        width: 800,
        height: 600,
        text_entities: [{ type: 'bold', text: 'Bug screenshot' }]
      },
      {
        id: 12,
        type: 'service',
        date_unixtime: '1763550200',
        actor: 'Admin',
        actor_id: 'user1000',
        action: 'pin_message',
        message_id: 10
      }
    ]
  };

  assert.throws(
    () => parseDesktopExport(exportFixture, { allowedChatId: '-1009999999999' }),
    /does not match TELEGRAM_ALLOWED_CHAT_ID/
  );

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tg-export-'));
  const jsonPath = path.join(tmpDir, 'result.json');
  fs.writeFileSync(jsonPath, JSON.stringify(exportFixture), 'utf8');

  const store = createFakeStore();
  const result = await runImport({
    filePath: jsonPath,
    store,
    allowedChatId: ALLOWED_CHAT_ID,
    limit: 500
  });

  assert.equal(result.count, 3);
  assert.equal(result.chatMeta.telegram_chat_id, -1001234567890);
  assert.equal(result.chatMeta.pinned_message_id, 10);
  assert.equal(result.chatMeta.pinned_sender_name, 'Alice');

  const msg10 = store.messages.get('community_main:10');
  assert.equal(msg10.text_content, 'Visit 12Tester #android');
  assert.deepEqual(msg10.entities_json, [
    { offset: 6, length: 8, type: 'text_url', url: 'https://12tester.app' },
    { offset: 15, length: 8, type: 'hashtag' }
  ]);

  const msg11 = store.messages.get('community_main:11');
  assert.equal(msg11.content_type, 'photo');
  assert.equal(msg11.reply_to_message_id, 10);
  assert.equal(msg11.reply_sender_name, 'Alice');
  assert.match(msg11.reply_preview_text, /Visit 12Tester/);

  fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('CLI fail-closed checks: missing DATABASE_URL, ALLOWED_CHAT_ID, or WEBHOOK_SECRET', () => {
  const workerPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'mirror-worker.mjs');

  const missingDb = spawnSync(process.execPath, [workerPath, '--mode=polling', '--once'], {
    env: { ...process.env, DATABASE_URL: '', SUPABASE_DB_URL: '', TELEGRAM_ALLOWED_CHAT_ID: ALLOWED_CHAT_ID, TELEGRAM_BOT_TOKEN: '123:abc' }
  });
  assert.notEqual(missingDb.status, 0);
  assert.match(missingDb.stderr.toString(), /DATABASE_URL/);

  const missingSecret = spawnSync(process.execPath, [workerPath, '--mode=webhook'], {
    env: { ...process.env, DATABASE_URL: 'postgres://u:p@localhost:5432/db', TELEGRAM_ALLOWED_CHAT_ID: ALLOWED_CHAT_ID, TELEGRAM_WEBHOOK_SECRET: '' }
  });
  assert.notEqual(missingSecret.status, 0);
  assert.match(missingSecret.stderr.toString(), /TELEGRAM_WEBHOOK_SECRET/);
});
