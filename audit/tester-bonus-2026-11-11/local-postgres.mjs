import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';

const expectedDirectory = 'f:/ai/12tester/audit/tester-bonus-2026-11-11/pg-data';
const options = { host: '127.0.0.1', port: 55439, user: 'bonus_test_admin', database: 'postgres', connectionTimeoutMillis: 5000, statement_timeout: 30000 };
const client = new pg.Client(options);
await client.connect();
try {
  const { rows: [server] } = await client.query("select current_setting('data_directory') as directory, version() as version");
  assert.equal(server.directory.replaceAll('\\', '/').toLowerCase(), expectedDirectory, 'Refusing to use a cluster outside the dedicated task directory');
  console.log(server.version);
  if (process.argv.includes('--reset-isolated-task-db')) {
    const active = await client.query("select pid from pg_stat_activity where datname='tester_bonus_test'");
    assert.equal(active.rowCount, 0, 'Refusing reset while task database has active connections');
    await client.query('drop database if exists tester_bonus_test');
    console.log('Reset only the verified isolated tester_bonus_test database.');
  }
  const found = await client.query("select 1 from pg_database where datname='tester_bonus_test'");
  if (!found.rowCount) await client.query('create database tester_bonus_test');
} finally { await client.end(); }

const test = new pg.Client({ ...options, database: 'tester_bonus_test' });
await test.connect();
try {
  const authExists = await test.query("select to_regclass('auth.users') as users");
  if (authExists.rows[0].users) {
    console.log('Dedicated test schema already exists; no migrations reapplied.');
  } else {
    await test.query(`
      create extension if not exists pgcrypto;
      create extension if not exists "uuid-ossp";
      do $$ begin
        if not exists(select 1 from pg_roles where rolname='anon') then create role anon nologin; end if;
        if not exists(select 1 from pg_roles where rolname='authenticated') then create role authenticated nologin; end if;
        if not exists(select 1 from pg_roles where rolname='service_role') then create role service_role nologin bypassrls; end if;
      end $$;
      create schema auth;
      create table auth.users (
        id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
        email_confirmed_at timestamptz, confirmation_token text,
        email_change text, email_change_token_new text, recovery_token text
      );
      create function auth.uid() returns uuid language sql stable as $$
        select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
      $$;
      grant usage on schema public, auth to anon, authenticated, service_role;
      grant execute on function auth.uid() to anon, authenticated, service_role;
    `);
    // Only known pre-feature migrations are loaded here. Never execute an in-progress migration.
    const directory = new URL('../../supabase/migrations/', import.meta.url);
    const names = readdirSync(directory).filter(name => /^\d{3}_.*\.sql$/.test(name) && Number(name.slice(0, 3)) <= 86).sort();
    for (const name of names) {
      await test.query(readFileSync(new URL(name, directory), 'utf8'));
      console.log(`LOADED ${name}`);
    }
  }
  const { rows: [tables] } = await test.query("select count(*)::int as count from information_schema.tables where table_schema='public'");
  console.log(`Dedicated local PostgreSQL ready: ${tables.count} public tables; loopback port 55439.`);
  console.log('TEST_DATABASE_URL=postgresql://bonus_test_admin@127.0.0.1:55439/tester_bonus_test');
} finally { await test.end(); }
