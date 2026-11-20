import test from "node:test";
import assert from "node:assert/strict";
import { createWebhookHandler } from "./index.ts";

function makeEnv(overrides = {}) {
  return {
    SUPABASE_URL: "https://demo.supabase.co",
    SUPABASE_SERVICE_ROLE_KEY: "service-role-secret-key",
    TELEGRAM_BOT_TOKEN: "123456:TEST_BOT_TOKEN",
    TELEGRAM_ALLOWED_CHAT_ID: "-100999888",
    TELEGRAM_WEBHOOK_SECRET: "my-webhook-secret-1234",
    TELEGRAM_CHAT_KEY: "community_main",
    ...overrides,
  };
}

test("GET returns healthcheck OK", async () => {
  const handler = createWebhookHandler({ envProvider: () => makeEnv() });
  const res = await handler(new Request("https://demo.supabase.co/functions/v1/telegram-mirror-webhook", {
    method: "GET",
  }));
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.ok, true);
});

test("POST rejects missing or invalid webhook secret token with 401", async () => {
  const handler = createWebhookHandler({ envProvider: () => makeEnv() });
  const res = await handler(new Request("https://demo.supabase.co/functions/v1/telegram-mirror-webhook", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Telegram-Bot-Api-Secret-Token": "wrong-secret" },
    body: JSON.stringify({ update_id: 1 }),
  }));
  assert.equal(res.status, 401);
});

test("POST ignores updates from non-allowed chat IDs", async () => {
  let calledSupabase = false;
  const handler = createWebhookHandler({
    envProvider: () => makeEnv(),
    fetchImpl: async () => {
      calledSupabase = true;
      return new Response("[]", { status: 200 });
    },
  });

  const res = await handler(new Request("https://demo.supabase.co/functions/v1/telegram-mirror-webhook", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Telegram-Bot-Api-Secret-Token": "my-webhook-secret-1234",
    },
    body: JSON.stringify({
      update_id: 2,
      message: {
        message_id: 50,
        date: 1700000000,
        chat: { id: -100111222, title: "Other Group" },
        from: { id: 7, first_name: "Intruder" },
        text: "Spam",
      },
    }),
  }));
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.ignored, "chat_not_allowed");
  assert.equal(calledSupabase, false);
});

test("POST upserts chat, photo thumbnail base64, entities, and triggers purge on message_id % 100 === 0", async () => {
  const requests = [];
  const fakeFetch = async (url, init = {}) => {
    requests.push({ url: String(url), method: init.method || "GET", body: init.body });
    if (String(url).includes("/getFile")) {
      return Response.json({ ok: true, result: { file_path: "photos/thumb.jpg", file_size: 4 } });
    }
    if (String(url).includes("/file/bot")) {
      return new Response(new Uint8Array([1, 2, 3, 4]), { status: 200 });
    }
    if (String(url).includes("/rest/v1/telegram_mirror_chat") && init.method === "PATCH") {
      return Response.json([], { status: 200 });
    }
    if (String(url).includes("/rest/v1/rpc/purge_old_telegram_mirror_messages")) {
      return Response.json(0, { status: 200 });
    }
    return Response.json([], { status: 201 });
  };

  const handler = createWebhookHandler({ envProvider: () => makeEnv(), fetchImpl: fakeFetch });
  const res = await handler(new Request("https://demo.supabase.co/functions/v1/telegram-mirror-webhook", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Telegram-Bot-Api-Secret-Token": "my-webhook-secret-1234",
    },
    body: JSON.stringify({
      update_id: 3,
      message: {
        message_id: 100,
        date: 1700000100,
        chat: { id: -100999888, title: "12Tester Community" },
        from: { id: 42, first_name: "Minh", last_name: "Nguyen" },
        photo: [
          { file_id: "small_id", width: 90, height: 90, file_size: 1200 },
          { file_id: "large_id", width: 800, height: 800, file_size: 45000 },
        ],
        caption: "Check https://play.google.com",
        caption_entities: [{ offset: 6, length: 23, type: "url" }],
      },
    }),
  }));

  assert.equal(res.status, 200);
  const msgPost = requests.find((r) => r.url.includes("/rest/v1/telegram_mirror_messages") && r.method === "POST");
  assert.ok(msgPost);
  const saved = JSON.parse(msgPost.body);
  assert.equal(saved.telegram_message_id, 100);
  assert.equal(saved.content_type, "photo");
  assert.equal(saved.media_thumb_base64, "AQIDBA==");
  assert.equal(saved.sender_name, "Minh Nguyen");
  assert.equal(saved.entities_json.length, 1);

  const purgeCall = requests.find((r) => r.url.includes("purge_old_telegram_mirror_messages"));
  assert.ok(purgeCall);
});

test("POST handles /hide_preview from verified group admin", async () => {
  const requests = [];
  const fakeFetch = async (url, init = {}) => {
    requests.push({ url: String(url), method: init.method || "GET", body: init.body });
    if (String(url).includes("/getChatMember")) {
      return Response.json({ ok: true, result: { status: "administrator" } });
    }
    return Response.json([], { status: 200 });
  };

  const handler = createWebhookHandler({ envProvider: () => makeEnv(), fetchImpl: fakeFetch });
  const res = await handler(new Request("https://demo.supabase.co/functions/v1/telegram-mirror-webhook", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Telegram-Bot-Api-Secret-Token": "my-webhook-secret-1234",
    },
    body: JSON.stringify({
      update_id: 4,
      message: {
        message_id: 101,
        date: 1700000200,
        chat: { id: -100999888, title: "12Tester Community" },
        from: { id: 99, first_name: "Admin" },
        text: "/hide_preview",
        reply_to_message: { message_id: 77, text: "bad" },
      },
    }),
  }));

  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.hidden_message_id, 77);
  const softDeletePatch = requests.find(
    (r) => r.url.includes("/rest/v1/telegram_mirror_messages") && r.method === "PATCH",
  );
  assert.ok(softDeletePatch);
  assert.equal(JSON.parse(softDeletePatch.body).is_deleted, true);
});
