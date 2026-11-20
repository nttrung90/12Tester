import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { appendFileSync, copyFileSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const search = resolve(root, 'tools/ai-search.mjs');
const sql = resolve(root, 'tools/ai-sql-index.mjs');
function run(script, args, cwd = root) {
  const r = spawnSync(process.execPath, [script, ...args], { cwd, encoding: 'utf8', timeout: 30000, windowsHide: true });
  assert.ifError(r.error);
  return r;
}
function stats(r) { return JSON.parse(r.stderr.trim().split(/\r?\n/).at(-1)); }

test('Default source scope keeps all active migrations and excludes history/dependencies/build/secrets', () => {
  const r = run(search, ['--list', '--limit', '500']);
  assert.equal(r.status, 0, r.stderr);
  const paths = r.stdout.trim().split(/\r?\n/);
  const active = readdirSync(resolve(root, 'supabase/migrations')).filter(n => n.endsWith('.sql')).map(n => 'supabase/migrations/' + n).sort();
  assert.deepEqual(paths.filter(p => p.startsWith('supabase/migrations/')).sort(), active);
  assert.ok(!paths.some(p => /(?:migrations_archive|^audit\/|^runs\/|node_modules|\/build\/|local\.properties|google-services\.json)/.test(p)));
  assert.equal(stats(r).filesRead, 0);
});

test('Explicit history scope can list archived SQL without reading file contents', () => {
  const r = run(search, ['--scope', 'history', '--list', 'supabase/migrations_archive/', '--limit', '500']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(r.stdout.includes('pre_baseline_062/001_init.sql'));
  assert.equal(stats(r).filesRead, 0);
});

test('Android scope finds current code independently of the caller working directory', () => {
  const r = run(search, ['--scope', 'android', '--files', 'dailytaskcard'], resolve(root, 'backend'));
  assert.equal(r.status, 0, r.stderr);
  assert.ok(r.stdout.includes('ui/components/DailyTaskCard.kt'));
  assert.ok(!r.stdout.includes('supabase/'));
});

test('SQL literal search and result limit are explicit', () => {
  const r = run(search, ['--scope', 'sql', 'public.get_daily_task_status(', '--limit', '1']);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout.trim().split(/\r?\n/).length, 1);
  assert.equal(stats(r).truncated, true);
});

test('No result and invalid arguments return distinct failure codes', () => {
  assert.equal(run(search, ['--scope', 'docs', 'unlikely_12tester_token_579318']).status, 1);
  assert.equal(run(search, ['--scope', 'unknown', 'x']).status, 2);
  assert.equal(run(search, ['--limit', '0', 'x']).status, 2);
  assert.equal(run(search, ['--list', '--files']).status, 2);
});

test('RPC locator reports final source and preserves distinct signatures', () => {
  const r = run(sql, ['find', 'get_daily_task_status']);
  assert.equal(r.status, 0, r.stderr);
  const result = JSON.parse(r.stdout);
  assert.equal(result.totalMatches, 1);
  assert.equal(result.functions[0].signature, 'public.get_daily_task_status(uuid, date)');
  const row = result.functions[0];
  const source = readFileSync(resolve(root, row.lastDefinitionChange), 'utf8').split(/\r?\n/);
  assert.ok(row.lineHints.length > 0);
  assert.ok(row.lineHints.some(line => /(?:create|alter).*function\s+(?:public\.)?get_daily_task_status\(/i.test(source[line - 1])));
  const submits = JSON.parse(run(sql, ['find', 'submit_tester_report']).stdout).functions;
  assert.ok(submits.length > 1);
  assert.equal(new Set(submits.map(f => f.signature)).size, submits.length);
  assert.ok(submits.some(f => f.signature.startsWith('public.submit_tester_report_v3(')));
});

test('RPC locator rejects stale content, added/deleted migrations and missing index using isolated copies', () => {
  const index = JSON.parse(readFileSync(resolve(root, 'docs/ai/sql-index.json'), 'utf8'));
  const scratch = resolve(root, '.tmp/ai-navigation');
  mkdirSync(scratch, { recursive: true });
  const fixture = mkdtempSync(join(scratch, 'fixture-'));
  try {
    const paths = [...index.sources, ...index.supportSources].map(f => f.path).concat('docs/ai/sql-index.json');
    for (const p of new Set(paths)) {
      mkdirSync(dirname(resolve(fixture, p)), { recursive: true });
      copyFileSync(resolve(root, p), resolve(fixture, p));
    }
    const script = resolve(fixture, 'tools/ai-sql-index.mjs');
    assert.equal(run(script, ['check'], fixture).status, 0);
    const target = resolve(fixture, index.sources[0].path);
    const original = readFileSync(target);
    appendFileSync(target, '\n-- isolated stale-index test\n');
    let r = run(script, ['find', 'get_daily_task_status'], fixture);
    assert.equal(r.status, 2);
    assert.match(r.stderr, /STALE_SQL_INDEX/);
    writeFileSync(target, original);
    const added = resolve(fixture, 'supabase/migrations/999_ai_fixture_only.sql');
    writeFileSync(added, '-- never applied; temporary copy only\n');
    assert.match(run(script, ['check'], fixture).stderr, /STALE_SQL_INDEX/);
    unlinkSync(added);
    unlinkSync(target);
    assert.match(run(script, ['check'], fixture).stderr, /STALE_SQL_INDEX/);
    writeFileSync(target, original);
    unlinkSync(resolve(fixture, 'docs/ai/sql-index.json'));
    assert.match(run(script, ['check'], fixture).stderr, /index missing\/unreadable/);
  } finally {
    // Validate the absolute recursive-delete target before removing this fixture.
    const rel = relative(scratch, fixture);
    assert.ok(rel && !rel.startsWith('..') && dirname(fixture) === scratch && rel.startsWith('fixture-'));
    rmSync(fixture, { recursive: true, force: true });
  }
});
