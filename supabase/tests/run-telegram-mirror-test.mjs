import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { createDb, read } from '../tools/baseline-lib.mjs';

console.log('=== RUNNING TELEGRAM GROUP MIRROR TESTS (MIGRATION 101) ===');

const db = await createDb({ legacyGrants: true });

let passed = 0;
function pass(msg) {
  passed += 1;
  console.log(`PASS [${passed}]: ${msg}`);
}

async function asRole(role, uid, fn) {
  await db.exec('savepoint sp_role');
  if (uid) {
    await db.exec(`select set_config('request.jwt.claim.sub', '${uid}', true);`);
  } else {
    await db.exec(`select set_config('request.jwt.claim.sub', '', true);`);
  }
  await db.exec(`set local role ${role};`);
  try {
    const result = await fn();
    await db.exec('release savepoint sp_role');
    return result;
  } catch (err) {
    await db.exec('rollback to savepoint sp_role');
    throw err;
  } finally {
    try {
      await db.exec(`reset role; select set_config('request.jwt.claim.sub', '', true);`);
    } catch {
      // ignore
    }
  }
}

async function test(name, fn) {
  await db.exec('begin');
  try {
    await fn();
    pass(name);
  } catch (err) {
    console.error(`FAIL: ${name}`, err);
    throw err;
  } finally {
    try {
      await db.exec('rollback');
    } catch {
      // ignore
    }
  }
}

try {
  const migrationFiles = readdirSync(new URL('../../supabase/migrations/', import.meta.url))
    .filter(n => /^\d{3}_.*\.sql$/.test(n) && Number(n.slice(0, 3)) >= 62)
    .sort();

  for (const name of migrationFiles) {
    await db.exec(read('supabase/migrations/' + name));
  }
  pass(`Loaded ${migrationFiles.length} migrations through ${migrationFiles.at(-1)}`);

  const authUserId = '11111111-1111-4111-8111-111111111111';
  await db.exec(`
    insert into auth.users (id, email) values ('${authUserId}', 'user@mirror.test');
    insert into public.profiles (id, email, display_name, role, is_locked)
    values ('${authUserId}', 'user@mirror.test', 'Mirror Tester', 'member', false)
    on conflict (id) do nothing;
  `);

  // -------------------------------------------------------------------------
  // 1. Schema, indexes, SECURITY DEFINER, RLS & ACLs
  // -------------------------------------------------------------------------
  await test('Tables, indexes, SECURITY DEFINER, RLS, and ACLs are enforced', async () => {
    const tablesRes = await db.query(`
      select relname, relrowsecurity
      from pg_class
      where relnamespace = 'public'::regnamespace
        and relname in ('telegram_mirror_chat', 'telegram_mirror_messages')
      order by relname
    `);
    assert.deepEqual(tablesRes.rows, [
      { relname: 'telegram_mirror_chat', relrowsecurity: true },
      { relname: 'telegram_mirror_messages', relrowsecurity: true }
    ]);

    const idxRes = await db.query(`
      select indexname
      from pg_indexes
      where schemaname = 'public'
        and tablename = 'telegram_mirror_messages'
        and indexname in (
          'idx_telegram_mirror_messages_active_feed',
          'idx_telegram_mirror_messages_edited_at',
          'telegram_mirror_messages_chat_msg_unique'
        )
      order by indexname
    `);
    assert.equal(idxRes.rows.length, 3);

    const fnRes = await db.query(`
      select proname, prosecdef, proconfig
      from pg_proc
      where pronamespace = 'public'::regnamespace
        and proname in ('get_telegram_mirror_feed', 'purge_old_telegram_mirror_messages')
      order by proname
    `);
    assert.equal(fnRes.rows.length, 2);
    for (const fn of fnRes.rows) {
      assert.equal(fn.prosecdef, true, `${fn.proname} must be SECURITY DEFINER`);
      assert.ok(
        fn.proconfig?.some(c => c.startsWith('search_path=public')),
        `${fn.proname} must lock search_path`
      );
    }

    const privs = (await db.query(`
      select
        has_table_privilege('anon', 'public.telegram_mirror_chat', 'SELECT') as anon_chat_sel,
        has_table_privilege('anon', 'public.telegram_mirror_chat', 'INSERT') as anon_chat_ins,
        has_table_privilege('authenticated', 'public.telegram_mirror_chat', 'INSERT') as auth_chat_ins,
        has_table_privilege('anon', 'public.telegram_mirror_messages', 'SELECT') as anon_msg_sel,
        has_table_privilege('anon', 'public.telegram_mirror_messages', 'INSERT') as anon_msg_ins,
        has_table_privilege('authenticated', 'public.telegram_mirror_messages', 'UPDATE') as auth_msg_upd,
        has_table_privilege('authenticated', 'public.telegram_mirror_messages', 'DELETE') as auth_msg_del,
        has_function_privilege('anon', 'public.get_telegram_mirror_feed(text, bigint, bigint, timestamptz, integer)', 'EXECUTE') as anon_feed,
        has_function_privilege('authenticated', 'public.get_telegram_mirror_feed(text, bigint, bigint, timestamptz, integer)', 'EXECUTE') as auth_feed,
        has_function_privilege('anon', 'public.purge_old_telegram_mirror_messages(text, integer)', 'EXECUTE') as anon_purge,
        has_function_privilege('authenticated', 'public.purge_old_telegram_mirror_messages(text, integer)', 'EXECUTE') as auth_purge,
        has_function_privilege('service_role', 'public.purge_old_telegram_mirror_messages(text, integer)', 'EXECUTE') as service_purge
    `)).rows[0];

    assert.equal(privs.anon_chat_sel, false);
    assert.equal(privs.anon_chat_ins, false);
    assert.equal(privs.auth_chat_ins, false);
    assert.equal(privs.anon_msg_sel, false);
    assert.equal(privs.anon_msg_ins, false);
    assert.equal(privs.auth_msg_upd, false);
    assert.equal(privs.auth_msg_del, false);
    assert.equal(privs.anon_feed, true, 'anon (Guest) must execute get_telegram_mirror_feed');
    assert.equal(privs.auth_feed, true, 'authenticated must execute get_telegram_mirror_feed');
    assert.equal(privs.anon_purge, false);
    assert.equal(privs.auth_purge, false);
    assert.equal(privs.service_purge, true);

    await assert.rejects(
      () => asRole('anon', null, () => db.query(`insert into public.telegram_mirror_chat (telegram_chat_id, title) values (-1001, 'Hacked')`)),
      /permission denied|row-level security/i
    );
    await assert.rejects(
      () => asRole('authenticated', authUserId, () => db.query(`insert into public.telegram_mirror_messages (telegram_message_id, sender_name, content_type) values (1, 'X', 'text')`)),
      /permission denied|row-level security/i
    );
    await assert.rejects(
      () => asRole('anon', null, () => db.query(`select public.purge_old_telegram_mirror_messages()`)),
      /permission denied/i
    );
    await assert.rejects(
      () => asRole('authenticated', authUserId, () => db.query(`select public.purge_old_telegram_mirror_messages()`)),
      /permission denied/i
    );
  });

  // -------------------------------------------------------------------------
  // 2. Empty chat & uninitialized state
  // -------------------------------------------------------------------------
  await test('Uninitialized chat returns chat=null, messages=[], has_more=false for anon and authenticated', async () => {
    const anonFeed = (await asRole('anon', null, () =>
      db.query(`select public.get_telegram_mirror_feed('community_main') as feed`)
    )).rows[0].feed;

    assert.equal(anonFeed.chat, null);
    assert.deepEqual(anonFeed.messages, []);
    assert.equal(anonFeed.has_more, false);
    assert.match(anonFeed.server_time, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/);

    const authFeed = (await asRole('authenticated', authUserId, () =>
      db.query(`select public.get_telegram_mirror_feed('missing_chat') as feed`)
    )).rows[0].feed;
    assert.equal(authFeed.chat, null);
    assert.deepEqual(authFeed.messages, []);
    assert.equal(authFeed.has_more, false);
  });

  // -------------------------------------------------------------------------
  // 3. JSON contract, initial load, and before-cursor pagination
  // -------------------------------------------------------------------------
  await test('Full JSON contract, soft-delete exclusion, and before_message_id pagination', async () => {
    await asRole('service_role', null, async () => {
      await db.exec(`
        insert into public.telegram_mirror_chat (
          chat_key, telegram_chat_id, title, avatar_url,
          pinned_message_id, pinned_preview, pinned_sender_name, updated_at
        ) values (
          'community_main', -1001234567890, '12Tester Community', null,
          104, 'Welcome testers!', 'Admin', '2026-11-19T12:00:00Z'::timestamptz
        );

        insert into public.telegram_mirror_messages (
          chat_key, telegram_message_id, sender_id, sender_name, sender_is_bot,
          content_type, text_content, entities_json,
          media_width, media_height, media_thumb_base64,
          file_name, file_size,
          reply_to_message_id, reply_sender_name, reply_preview_text,
          sent_at, edited_at, is_deleted
        ) values
          (
            'community_main', 101, 1001, 'Alice', false,
            'text', 'Hello https://12tester.app',
            '[{"offset":6,"length":20,"type":"url"}]'::jsonb,
            0, 0, null, null, 0, null, null, null,
            '2026-11-19T12:01:00Z'::timestamptz, null, false
          ),
          (
            'community_main', 102, 1002, 'Bob', false,
            'photo', 'Screenshot attached',
            '[]'::jsonb,
            800, 600, '/9j/4AAQSkZJRg==', null, 0, 101, 'Alice', 'Hello https://12tester.app',
            '2026-11-19T12:02:00Z'::timestamptz, null, false
          ),
          (
            'community_main', 103, 1003, 'HiddenUser', false,
            'text', 'Hidden spam',
            '[]'::jsonb,
            0, 0, null, null, 0, null, null, null,
            '2026-11-19T12:03:00Z'::timestamptz, '2026-11-19T12:03:30Z'::timestamptz, true
          ),
          (
            'community_main', 104, 1004, 'Admin', false,
            'document', 'Guide PDF',
            '[{"offset":0,"length":5,"type":"bold"}]'::jsonb,
            0, 0, null, 'guide.pdf', 42000, null, null, null,
            '2026-11-19T12:04:00Z'::timestamptz, '2026-11-19T12:04:10Z'::timestamptz, false
          ),
          (
            'community_main', 105, 1005, 'BotHelper', true,
            'text', 'Latest update',
            '[]'::jsonb,
            0, 0, null, null, 0, 104, 'Admin', 'Guide PDF',
            '2026-11-19T12:05:00Z'::timestamptz, null, false
          );
      `);
    });

    // Page 1 (limit 2) as anon
    const page1 = (await asRole('anon', null, () =>
      db.query(`select public.get_telegram_mirror_feed('community_main', null, null, null, 2) as feed`)
    )).rows[0].feed;

    assert.deepEqual(Object.keys(page1).sort(), ['chat', 'has_more', 'messages', 'server_time']);
    assert.deepEqual(Object.keys(page1.chat).sort(), [
      'avatar_url', 'chat_key', 'pinned_message_id', 'pinned_preview',
      'pinned_sender_name', 'telegram_chat_id', 'title', 'updated_at'
    ]);
    assert.equal(page1.chat.title, '12Tester Community');
    assert.equal(page1.chat.telegram_chat_id, -1001234567890);
    assert.equal(page1.chat.pinned_message_id, 104);
    assert.equal(page1.has_more, true);
    assert.deepEqual(page1.messages.map(m => m.telegram_message_id), [105, 104]);

    const msg104 = page1.messages[1];
    assert.deepEqual(Object.keys(msg104).sort(), [
      'content_type', 'edited_at', 'entities_json', 'file_name', 'file_size',
      'is_deleted', 'media_height', 'media_thumb_base64', 'media_width',
      'reply_preview_text', 'reply_sender_name', 'reply_to_message_id',
      'sender_id', 'sender_is_bot', 'sender_name', 'sent_at',
      'telegram_message_id', 'text_content'
    ]);
    assert.equal(msg104.file_name, 'guide.pdf');
    assert.equal(msg104.file_size, 42000);
    assert.match(msg104.sent_at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/);
    assert.match(msg104.edited_at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/);

    // Page 2 (before 104, limit 2) as authenticated -> should skip deleted 103 and return [102, 101] with has_more=false
    const page2 = (await asRole('authenticated', authUserId, () =>
      db.query(`select public.get_telegram_mirror_feed('community_main', 104, null, null, 2) as feed`)
    )).rows[0].feed;

    assert.equal(page2.has_more, false);
    assert.deepEqual(page2.messages.map(m => m.telegram_message_id), [102, 101]);
    assert.equal(page2.messages[0].media_thumb_base64, '/9j/4AAQSkZJRg==');
    assert.equal(page2.messages[0].reply_to_message_id, 101);
    assert.equal(page2.messages[1].edited_at, null);
  });

  // -------------------------------------------------------------------------
  // 4. Delta polling (p_after_message_id + p_since_edited_at including soft-deletes)
  // -------------------------------------------------------------------------
  await test('Delta poll returns new active messages AND edited/soft-deleted messages since watermark', async () => {
    await asRole('service_role', null, async () => {
      await db.exec(`
        insert into public.telegram_mirror_chat (chat_key, telegram_chat_id, title)
        values ('community_main', -100999, 'Delta Group');

        insert into public.telegram_mirror_messages (
          chat_key, telegram_message_id, sender_id, sender_name, content_type, text_content, sent_at, edited_at, is_deleted
        ) values
          ('community_main', 10, 1, 'U1', 'text', 'Msg 10', '2026-11-19T10:00:00Z', null, false),
          ('community_main', 11, 1, 'U1', 'text', 'Msg 11 edited', '2026-11-19T10:01:00Z', '2026-11-19T10:10:00Z', false),
          ('community_main', 12, 1, 'U1', 'text', 'Msg 12 hidden', '2026-11-19T10:02:00Z', '2026-11-19T10:11:00Z', true),
          ('community_main', 13, 1, 'U1', 'text', 'Msg 13 new', '2026-11-19T10:12:00Z', null, false),
          ('community_main', 14, 1, 'U1', 'text', 'Msg 14 new but already deleted', '2026-11-19T10:13:00Z', '2026-11-19T10:13:05Z', true);
      `);
    });

    // Client previously saw up to msg 12 at watermark 2026-11-19T10:05:00Z
    const delta = (await asRole('anon', null, () =>
      db.query(`
        select public.get_telegram_mirror_feed(
          'community_main',
          null,
          12,
          '2026-11-19T10:05:00Z'::timestamptz,
          40
        ) as feed
      `)
    )).rows[0].feed;

    // 14 is included because edited_at > watermark (client ignores if not present, or deletes if present)
    // 13 is included because telegram_message_id > 12 AND is_deleted = false
    // 12 is included because edited_at > watermark (is_deleted = true so client removes it!)
    // 11 is included because edited_at > watermark (edited text!)
    // 10 is NOT included
    assert.deepEqual(delta.messages.map(m => m.telegram_message_id), [14, 13, 12, 11]);
    const msg12 = delta.messages.find(m => m.telegram_message_id === 12);
    assert.equal(msg12.is_deleted, true);
    const msg11 = delta.messages.find(m => m.telegram_message_id === 11);
    assert.equal(msg11.text_content, 'Msg 11 edited');

    // Pure after_message_id poll without since_edited_at skips is_deleted = true rows > after_message_id
    const pureAfter = (await asRole('anon', null, () =>
      db.query(`select public.get_telegram_mirror_feed('community_main', null, 12, null, 40) as feed`)
    )).rows[0].feed;
    assert.deepEqual(pureAfter.messages.map(m => m.telegram_message_id), [13]);
  });

  // -------------------------------------------------------------------------
  // 5. p_limit clamping (1..50) and retention purge_old_telegram_mirror_messages
  // -------------------------------------------------------------------------
  await test('p_limit is clamped to 1..50 and purge_old_telegram_mirror_messages keeps latest N per chat_key', async () => {
    await asRole('service_role', null, async () => {
      await db.exec(`
        insert into public.telegram_mirror_chat (chat_key, telegram_chat_id, title)
        values
          ('community_main', -100111, 'Main Group'),
          ('other_chat', -100222, 'Other Group');

        insert into public.telegram_mirror_messages (
          chat_key, telegram_message_id, sender_id, sender_name, content_type, text_content, sent_at
        )
        select
          'community_main',
          gs,
          100,
          'User',
          'text',
          'Message #' || gs,
          '2026-11-19T12:00:00Z'::timestamptz + (gs || ' seconds')::interval
        from generate_series(1, 65) as gs;

        insert into public.telegram_mirror_messages (
          chat_key, telegram_message_id, sender_id, sender_name, content_type, text_content, sent_at
        ) values
          ('other_chat', 1, 200, 'Other', 'text', 'Keep me', '2026-11-19T12:00:00Z'::timestamptz);
      `);
    });

    // Limit < 1 clamps to 1
    const minFeed = (await asRole('anon', null, () =>
      db.query(`select public.get_telegram_mirror_feed('community_main', null, null, null, -5) as feed`)
    )).rows[0].feed;
    assert.equal(minFeed.messages.length, 1);
    assert.equal(minFeed.messages[0].telegram_message_id, 65);
    assert.equal(minFeed.has_more, true);

    // Limit > 50 clamps to 50
    const maxFeed = (await asRole('anon', null, () =>
      db.query(`select public.get_telegram_mirror_feed('community_main', null, null, null, 200) as feed`)
    )).rows[0].feed;
    assert.equal(maxFeed.messages.length, 50);
    assert.equal(maxFeed.messages[0].telegram_message_id, 65);
    assert.equal(maxFeed.messages[49].telegram_message_id, 16);
    assert.equal(maxFeed.has_more, true);

    // Purge keeping latest 10 for community_main
    const purgedCount = (await asRole('service_role', null, () =>
      db.query(`select public.purge_old_telegram_mirror_messages('community_main', 10) as cnt`)
    )).rows[0].cnt;
    assert.equal(purgedCount, 55);

    const remainingMain = (await asRole('service_role', null, () =>
      db.query(`select telegram_message_id from public.telegram_mirror_messages where chat_key = 'community_main' order by telegram_message_id asc`)
    )).rows.map(r => Number(r.telegram_message_id));
    assert.deepEqual(remainingMain, [56, 57, 58, 59, 60, 61, 62, 63, 64, 65]);

    const remainingOther = (await asRole('service_role', null, () =>
      db.query(`select count(*)::int as cnt from public.telegram_mirror_messages where chat_key = 'other_chat'`)
    )).rows[0].cnt;
    assert.equal(remainingOther, 1, 'Purge must only affect target p_chat_key');
  });

  console.log(`\nALL TELEGRAM MIRROR SQL TESTS PASSED (${passed} checks).`);
} finally {
  await db.close();
}
