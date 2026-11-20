import assert from 'node:assert/strict';
// Never connect to any live database; both attempts deliberately lack a URL.
delete process.env.DATABASE_URL;
delete process.env.SUPABASE_DB_URL;
const { runIteration } = await import('../../backend/notification-worker/worker-runner.mjs');
const results = [];
for (let i = 0; i < 2; i++) {
  try { results.push({ attempt: i + 1, result: await runIteration() }); }
  catch (e) { results.push({ attempt: i + 1, error: e.message }); }
}
console.log(JSON.stringify(results, null, 2));
assert.ok(results.every(r => r.error?.includes('environment variable is required')), 'A failed connection setup must release inFlight so the next tick can retry');
