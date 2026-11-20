import { readdirSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createDb, catalog, dataSnapshot, root } from '../../supabase/tools/baseline-lib.mjs';

// Planning evidence only: reads source and executes SQL exclusively in disposable
// in-memory PGlite databases. Never reads credentials or connects to a server.
const read = path => readFileSync(new URL(path, root), 'utf8');
const sha = data => createHash('sha256').update(data).digest('hex');
const names = readdirSync(new URL('supabase/migrations/', root)).filter(n => /^\d+_.*\.sql$/.test(n)).sort();
const archived = readdirSync(new URL('supabase/migrations_archive/pre_baseline_062/', root));
const sourceFiles = names.map(name => {
  const bytes = readFileSync(new URL('supabase/migrations/' + name, root));
  return { name, bytes: bytes.length, sha256: sha(bytes) };
});
const references = [];
function scan(dir) {
  for (const entry of readdirSync(new URL(dir, root), { withFileTypes: true })) {
    if (['node_modules', '.git', 'build'].includes(entry.name)) continue;
    const path = dir + entry.name;
    if (entry.isDirectory()) { scan(path + '/'); continue; }
    if (!/\.(mjs|js|json|md|sh|sql|kt|kts)$/.test(path) || /package-lock\.json$/.test(path)) continue;
    const lines = read(path).split(/\r?\n/);
    for (let index = 0; index < lines.length; index++) {
      if (/migrations\/\d+_|migrations_archive\/|\^.*\\d\{3\}/.test(lines[index])) {
        references.push({ path, line: index + 1, text: lines[index].trim().slice(0, 240) });
      }
    }
  }
}
for (const dir of ['supabase/tests/', 'supabase/tools/', 'supabase/operations/']) scan(dir);

const result = {
  generatedAt: new Date().toISOString(), scope: 'local source and disposable PGlite only; no production access',
  active: { count: names.length, bytes: sourceFiles.reduce((n, f) => n + f.bytes, 0), first: names[0], last: names.at(-1), files: sourceFiles },
  archive: { path: 'supabase/migrations_archive/pre_baseline_062/', numberedMigrations: archived.filter(n => /^\d+_.*\.sql$/.test(n)).length, containsOldFullSchema: archived.includes('full_schema_singapore.sql') },
  references, checks: [],
};
const db = await createDb();
try {
  for (const name of names) await db.exec(read('supabase/migrations/' + name));
  const state = await catalog(db);
  result.checks.push({ name: 'fresh_replay_062_through_090', passed: true, files: names.length, catalogCounts: Object.fromEntries(Object.entries(state).map(([k, v]) => [k, v.length])) });
  const before = JSON.stringify({ catalog: state, data: await dataSnapshot(db) });
  await db.exec(read('supabase/migrations/062_consolidated_baseline.sql'));
  const after = JSON.stringify({ catalog: await catalog(db), data: await dataSnapshot(db) });
  result.checks.push({ name: 'baseline_062_rerun_after_090_preserves_catalog_and_rows', passed: before === after });
} catch (error) {
  result.checks.push({ name: 'fresh_replay_062_through_090', passed: false, code: error.code, message: error.message });
} finally { await db.close(); }

const recent = await createDb();
const recentNames = names.filter(n => Number(n.split('_')[0]) >= 85);
try {
  for (const name of recentNames) await recent.exec(read('supabase/migrations/' + name));
  result.checks.push({ name: 'recent_085_through_090_without_baseline', outcome: 'unexpected_success_requires_review' });
} catch (error) {
  result.checks.push({ name: 'recent_085_through_090_without_baseline', outcome: 'expected_failure', code: error.code, message: error.message });
} finally { await recent.close(); }

const sourceUnchanged = sourceFiles.every(f => sha(readFileSync(new URL('supabase/migrations/' + f.name, root))) === f.sha256);
result.checks.push({ name: 'migration_source_bytes_unchanged_during_inspection', passed: sourceUnchanged });
const out = new URL('audit/migration-retention-plan-2026-11-15/', root);
mkdirSync(out, { recursive: true });
writeFileSync(new URL('inspection.json', out), JSON.stringify(result, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ active: { ...result.active, files: undefined }, archive: result.archive, references: references.length, checks: result.checks }, null, 2));
if (result.checks.some(c => c.passed === false || c.outcome === 'unexpected_success_requires_review')) process.exitCode = 1;
