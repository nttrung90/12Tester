import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
const db = new pg.Client({ host: '127.0.0.1', port: 55439, user: 'bonus_test_admin', database: 'tester_bonus_test', connectionTimeoutMillis: 5000, statement_timeout: 30000 });
await db.connect();
try {
  const { rows: [server] } = await db.query("select current_setting('data_directory') as directory,current_database() as database");
  assert.equal(server.directory.replaceAll('\\','/').toLowerCase(), 'f:/ai/12tester/audit/tester-bonus-2026-11-11/pg-data');
  assert.equal(server.database, 'tester_bonus_test');
  const sql = readFileSync(new URL('../../supabase/migrations/087_campaign_tester_bonus_rounds.sql', import.meta.url), 'utf8');
  writeFileSync(new URL('./runs/native-migration-under-review.sql', import.meta.url), sql);
  await db.query(sql);
  console.log('PASS: Migration 087 applied to isolated native PostgreSQL test database.');
  const { rows } = await db.query("select table_name from information_schema.tables where table_schema='public' and table_name like 'campaign_tester_bonus_%' order by table_name");
  console.log(JSON.stringify(rows));
} finally { await db.end(); }
