import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const output = new URL('docs/ai/sql-index.json', root);
const read = path => readFileSync(new URL(path, root));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const supportPaths = ['tools/ai-sql-index.mjs', 'supabase/tools/baseline-lib.mjs', 'supabase/tests/package-lock.json'];
function sources() {
  const names = readdirSync(new URL('supabase/migrations/', root)).filter(n => n.endsWith('.sql')).sort();
  if (!names.length || names.some(n => !/^\d+_.+\.sql$/.test(n))) throw new Error('Migration SQL filenames must have numeric version_name.sql; review source ordering.');
  const versions = names.map(n => BigInt(n.split('_')[0]).toString());
  if (new Set(versions).size !== versions.length) throw new Error('Duplicate migration versions: resolve explicitly, do not guess ordering.');
  if (versions.some((n, i) => i && BigInt(n) <= BigInt(versions[i - 1]))) throw new Error('Lexical and numeric migration order differ; review ordering.');
  return names.map(name => ({ path: 'supabase/migrations/' + name, sha256: sha(read('supabase/migrations/' + name)) }));
}
function supportSources() { return supportPaths.map(path => ({ path, sha256: sha(read(path)) })); }
function loadFresh() {
  let index;
  try { index = JSON.parse(readFileSync(output, 'utf8')); }
  catch { throw new Error('SQL index missing/unreadable. Run: node tools/ai-sql-index.mjs build'); }
  if (index.formatVersion !== 1 || JSON.stringify(index.sources) !== JSON.stringify(sources()) || JSON.stringify(index.supportSources) !== JSON.stringify(supportSources())) {
    throw new Error('STALE_SQL_INDEX: migration files/order/content or generator dependencies changed. Run: node tools/ai-sql-index.mjs build');
  }
  return index;
}
const query = `select n.nspname as schema, p.proname as name,
  oidvectortypes(p.proargtypes) as types,
  pg_get_function_identity_arguments(p.oid) as identity_arguments,
  pg_get_functiondef(p.oid) as definition
  from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and p.prokind='f'
  and not exists(select 1 from pg_depend d where d.classid='pg_proc'::regclass and d.objid=p.oid and d.deptype='e')
  order by n.nspname,p.proname,oidvectortypes(p.proargtypes)`;
async function build() {
  const manifest = sources();
  const support = supportSources();
  // Only this command needs the existing local PGlite dependency. find/check
  // are fast filesystem/hash operations and never execute SQL.
  const { createDb } = await import('../supabase/tools/baseline-lib.mjs');
  const db = await createDb();
  const firstSeen = new Map();
  let functions = new Map();
  try {
    for (const file of manifest) {
      const sql = read(file.path).toString('utf8');
      await db.exec(sql);
      const rows = (await db.query(query)).rows;
      const next = new Map();
      for (const row of rows) {
        const signature = `${row.schema}.${row.name}(${row.types})`;
        const definitionSha256 = sha(row.definition);
        if (!firstSeen.has(signature)) firstSeen.set(signature, file.path);
        const previous = functions.get(signature);
        if (previous?.definitionSha256 === definitionSha256) { next.set(signature, previous); continue; }
        const escapedName = row.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const declaration = new RegExp(`\\b(?:create\\s+(?:or\\s+replace\\s+)?|alter\\s+)function\\s+(?:"?public"?\\.)?"?${escapedName}"?\\s*\\(`, 'i');
        const lineHints = sql.split(/\r?\n/).flatMap((line, i) => declaration.test(line) ? [i + 1] : []);
        next.set(signature, {
          signature, identityArguments: row.identity_arguments,
          firstSeen: firstSeen.get(signature), lastDefinitionChange: file.path,
          lineHints, definitionSha256,
        });
      }
      functions = next;
    }
    if (JSON.stringify(manifest) !== JSON.stringify(sources()) || JSON.stringify(support) !== JSON.stringify(supportSources())) throw new Error('Source changed during build; no index written. Retry on stable source.');
    const index = {
      formatVersion: 1,
      scope: 'Final public non-extension function definitions after replaying current local migrations. Not deployment history, ACL/trigger/DML inventory, or a production snapshot.',
      sources: manifest, supportSources: support,
      functions: [...functions.values()],
    };
    mkdirSync(new URL('docs/ai/', root), { recursive: true });
    writeFileSync(output, JSON.stringify(index, null, 2) + '\n', 'utf8');
    console.log(JSON.stringify({ index: fileURLToPath(output), migrations: manifest.length, functions: functions.size, mode: 'isolated in-memory PGlite; no remote connection' }, null, 2));
  } finally { await db.close(); }
}
async function main() {
  const [command = 'help', ...terms] = process.argv.slice(2);
  if (command === 'build' && !terms.length) return build();
  if (command === 'check' && !terms.length) {
    const index = loadFresh();
    console.log(JSON.stringify({ fresh: true, migrations: index.sources.length, functions: index.functions.length }));
    return;
  }
  if (command === 'find' && terms.join(' ').trim()) {
    const index = loadFresh();
    const needle = terms.join(' ').toLowerCase();
    const matches = index.functions.filter(f => f.signature.toLowerCase().includes(needle));
    console.log(JSON.stringify({ scope: index.scope, totalMatches: matches.length, truncated: matches.length > 40, functions: matches.slice(0, 40) }, null, 2));
    if (!matches.length) process.exitCode = 1;
    return;
  }
  console.log('Usage: node tools/ai-sql-index.mjs build | check | find <function name or signature>');
  if (!['help', '--help', '-h'].includes(command)) process.exitCode = 2;
}
try { await main(); } catch (error) { console.error(error.message); process.exitCode = 2; }
