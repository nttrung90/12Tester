import { readdirSync, readFileSync, lstatSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { relative, resolve, sep } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const buildFiles = ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml'];
const scopes = {
  code: ['app/src/main', 'backend', 'supabase/migrations', ...buildFiles],
  android: ['app/src/main', ...buildFiles],
  backend: ['backend'],
  sql: ['supabase/migrations', 'supabase/operations'],
  tests: ['app/src/test', 'app/src/androidTest', 'supabase/tests'],
  docs: ['AGENTS.md', 'GEMINI.md', 'supabase/AGENTS.md', 'docs/AI_NAVIGATION.md'],
  history: ['supabase/migrations_archive', 'audit', 'runs'],
};
const skipDirs = new Set(['node_modules', 'build', '.git', '.gradle', '.kotlin', '.idea', '.tmp', 'coverage', 'dist']);
const readable = /\.(?:kt|kts|java|xml|sql|mjs|cjs|js|ts|tsx|jsx|json|md|txt|sh|ps1|toml|properties|rules|ya?ml|html|css)$/i;
const sensitive = /(?:^\.env(?:\.|$)|^local\.properties$|^google-services\.json$|(?:secret|credential|service[-_]?account).*\.(?:json|properties|toml|ya?ml)$|\.(?:jks|keystore|pem|key|p12|pfx)$)/i;
const skipFiles = /^(?:package-lock\.json|yarn\.lock|pnpm-lock\.yaml|sql-index\.json)$/i;
const usage = 'Usage: node tools/ai-search.mjs [--scope code|android|backend|sql|tests|docs|history] [--files|--list] [--limit 1..500] [--case-sensitive] "literal text"';

function main() {
  let scope = 'code', limit = 60, filesOnly = false, list = false, caseSensitive = false;
  const terms = [];
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--help' || a === '-h') { console.log(usage); return; }
    if (a === '--scope') { scope = args[++i]; continue; }
    if (a === '--limit') { limit = Number(args[++i]); continue; }
    if (a === '--files') { filesOnly = true; continue; }
    if (a === '--list') { list = true; continue; }
    if (a === '--case-sensitive') { caseSensitive = true; continue; }
    if (a === '--') { terms.push(...args.slice(i + 1)); break; }
    if (a.startsWith('--')) throw new Error(`Unknown option: ${a}`);
    terms.push(a);
  }
  const term = terms.join(' ');
  if (!scopes[scope] || !Number.isInteger(limit) || limit < 1 || limit > 500 || (!list && !term) || (list && filesOnly)) throw new Error(usage);
  const found = new Set();
  function walk(local) {
    const full = resolve(root, local);
    const rel = relative(root, full);
    if (rel.startsWith('..') || !rel) throw new Error('Scope path must remain inside the project.');
    const s = lstatSync(full, { throwIfNoEntry: false });
    if (!s || s.isSymbolicLink()) return;
    if (s.isDirectory()) {
      for (const e of readdirSync(full, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
        if (e.isSymbolicLink() || e.name.startsWith('.') || skipDirs.has(e.name) || sensitive.test(e.name)) continue;
        walk(relative(root, resolve(full, e.name)));
      }
    } else if (s.isFile()) {
      const name = full.split(sep).at(-1);
      if (readable.test(name) && !sensitive.test(name) && !skipFiles.test(name)) found.add(relative(root, full).split(sep).join('/'));
    }
  }
  for (const p of scopes[scope]) walk(p);
  if (scope === 'history') for (const e of readdirSync(root, { withFileTypes: true })) {
    if (e.isFile() && /\.(md|txt)$/i.test(e.name)) walk(e.name);
  }
  const candidates = [...found].sort();
  const output = [];
  let readCount = 0, truncated = false;
  const needle = caseSensitive ? term : term.toLowerCase();
  outer: for (const path of candidates) {
    if (list) {
      if (term && !(caseSensitive ? path : path.toLowerCase()).includes(needle)) continue;
      output.push(path);
      if (output.length > limit) { truncated = true; break; }
      continue;
    }
    const content = readFileSync(resolve(root, path), 'utf8');
    readCount++;
    // A text extension alone is not sufficient to treat embedded binary as text.
    if (content.includes('\0')) continue;
    const lines = content.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      if (!(caseSensitive ? lines[i] : lines[i].toLowerCase()).includes(needle)) continue;
      output.push(filesOnly ? path : `${path}:${i + 1}:${lines[i].length > 400 ? lines[i].slice(0, 400) + ' [line shortened]' : lines[i]}`);
      if (output.length > limit) { truncated = true; break outer; }
      if (filesOnly) break;
    }
  }
  for (const line of output.slice(0, limit)) console.log(line);
  console.error(JSON.stringify({ scope, candidateFiles: candidates.length, filesRead: readCount, results: Math.min(output.length, limit), truncated, ...(truncated ? { note: 'More results exist; narrow scope/text or raise --limit.' } : {}) }));
  if (!output.length) process.exitCode = 1;
}
try { main(); } catch (error) { console.error(error.message); process.exitCode = 2; }
