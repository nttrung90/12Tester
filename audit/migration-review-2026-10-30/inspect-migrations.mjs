import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { PGlite } from '../../supabase/tests/node_modules/@electric-sql/pglite/dist/index.js';
import { pgcrypto } from '../../supabase/tests/node_modules/@electric-sql/pglite/dist/contrib/pgcrypto.js';
import { uuid_ossp } from '../../supabase/tests/node_modules/@electric-sql/pglite/dist/contrib/uuid_ossp.js';

// All SQL runs in disposable, in-memory databases. No project credentials are read.
const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const historyPath = 'supabase/migrations_archive/pre_baseline_062/';
const files = readdirSync(new URL(historyPath, root)).filter(p => /^0\d\d_.*\.sql$/.test(p)).sort();
const full = read(historyPath + 'full_schema_singapore.sql');
const contents = files.map(name => ({ name, sql: read(historyPath + name) }));
const groups = {};
const functions = {};
for (const { name, sql } of contents) {
  (groups[name.split('_')[0]] ??= []).push(name);
  for (const fn of new Set([...sql.matchAll(/create\s+(?:or\s+replace\s+)?function\s+(?:public\.)?(\w+)\s*\(/gi)].map(m => m[1].toLowerCase()))) {
    (functions[fn] ??= []).push(name);
  }
}
const result = {
  fileCount: files.length,
  bytes: contents.reduce((n, p) => n + Buffer.byteLength(p.sql), 0),
  lines: contents.reduce((n, p) => n + p.sql.split('\n').length, 0),
  fullSchemaLines: full.split('\n').length,
  duplicatePrefixes: Object.fromEntries(Object.entries(groups).filter(([, paths]) => paths.length > 1)),
  topRepeatedFunctions: Object.entries(functions).sort((a, b) => b[1].length - a[1].length).slice(0, 15).map(([name, migrations]) => ({ name, count: migrations.length, migrations })),
};
function latestFunction(sql, name) {
  const re = new RegExp(`create\\s+(?:or\\s+replace\\s+)?function\\s+public\\.${name}\\s*\\([\\s\\S]*?\\bas\\s+(\\$(?:[a-zA-Z_][a-zA-Z_0-9]*)?\\$)[\\s\\S]*?\\1\\s*;`, 'gi');
  return [...sql.matchAll(re)].at(-1)?.[0];
}
const normalize = sql => sql?.replace(/\r\n/g, '\n').trim();
const normalizeWithoutComments = sql => normalize(sql)?.replace(/--[^\n]*/g, '').replace(/\s+/g, '');
result.latestDefinitionComparisons = ['submit_tester_report', 'admin_get_daily_task_config', 'admin_list_daily_task_policies', 'admin_get_daily_task_policy_history'].map(name => {
  const latest = contents.filter(p => latestFunction(p.sql, name)).at(-1);
  const migrationDefinition = latestFunction(latest?.sql ?? '', name);
  const mirrorDefinition = latestFunction(full, name);
  return {
    name,
    latestMigration: latest?.name,
    exactMatchAfterNewlineNormalization: normalize(migrationDefinition) === normalize(mirrorDefinition),
    matchIgnoringLineCommentsAndWhitespace: normalizeWithoutComments(migrationDefinition) === normalizeWithoutComments(mirrorDefinition),
    migrationRatingJsonFields: (migrationDefinition?.match(/'rating'\s*,/g) ?? []).length,
    mirrorRatingJsonFields: (mirrorDefinition?.match(/'rating'\s*,/g) ?? []).length,
  };
});

async function newDb() {
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
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
  return db;
}
const historyDb = await newDb();
result.sequentialReplay = { engine: 'PGlite with stub Supabase auth schema', applied: [], firstFailure: null };
try {
  for (const { name, sql } of contents) {
    try {
      await historyDb.exec(sql);
      result.sequentialReplay.applied.push(name);
    } catch (error) {
      result.sequentialReplay.firstFailure = { file: name, message: error.message, code: error.code, detail: error.detail, hint: error.hint };
      break;
    }
  }
} finally {
  await historyDb.close();
}
const fullDb = await newDb();
try {
  await fullDb.exec(full);
  result.fullSchemaLoad = { success: true };
} catch (error) {
  result.fullSchemaLoad = { success: false, message: error.message, code: error.code, detail: error.detail, hint: error.hint };
} finally {
  await fullDb.close();
}
writeFileSync(fileURLToPath(new URL('inspection.json', import.meta.url)), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({
  fileCount: result.fileCount,
  lines: result.lines,
  fullSchemaLines: result.fullSchemaLines,
  latestDefinitionComparisons: result.latestDefinitionComparisons,
  replayAppliedCount: result.sequentialReplay.applied.length,
  replayFirstFailure: result.sequentialReplay.firstFailure,
  fullSchemaLoad: result.fullSchemaLoad,
}, null, 2));
