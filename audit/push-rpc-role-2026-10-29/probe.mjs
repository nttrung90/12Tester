import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

// Reuse only the existing local test bootstrap and fixtures, without editing it.
const original = readFileSync(new URL('../../supabase/tests/run-push-and-carryover-hardening-test.mjs', import.meta.url), 'utf8');
const marker = '  // R01: C01 Regression Test';
assert.ok(original.includes(marker), 'Fixture marker must exist');
const prefix = original.slice(0, original.indexOf(marker))
  .replace("import { readFileSync } from 'node:fs';", "import { readFileSync, writeFileSync } from 'node:fs';")
  .replace("createRequire(new URL('./package.json', import.meta.url))", "createRequire(new URL('../../supabase/tests/package.json', import.meta.url))");
const suffix = `
  const results = [];
  for (const authenticated of [false, true]) {
    await db.exec('begin');
    const before = await scalar(\`select credits from public.profiles where id='\${owner}'\`);
    await db.exec(\`set local request.jwt.claim.sub='\${owner}';\`);
    if (authenticated) await db.exec('set local role authenticated');
    const role = await scalar("select current_user, session_user, current_setting('role', true) active_role");
    await db.exec('savepoint purchase');
    let outcome;
    try {
      const row = await scalar(\`select public.push_campaign('\${camp}', '\${req}') result\`);
      outcome = { success: row.result.success, replayed: row.result.replayed };
    } catch (error) {
      outcome = { success: false, code: error.code, message: error.message };
      await db.exec('rollback to savepoint purchase');
    }
    await db.exec('reset role');
    const after = await scalar(\`select credits,
      (select count(*)::int from public.campaign_push_receipts where client_request_id='\${req}') receipt_count,
      (select count(*)::int from public.credit_logs where user_id='\${owner}' and action='campaign_push') log_count,
      (select push_order from public.campaigns where id='\${camp}') push_order
      from public.profiles where id='\${owner}'\`);
    results.push({ authenticated, role, before, outcome, after });
    await db.exec('rollback');
  }
  console.log(JSON.stringify(results, null, 2));
  writeFileSync(new URL('./results.json', import.meta.url), JSON.stringify(results, null, 2));
  assert.equal(results[0].outcome.success, true, 'Privileged fixture baseline should succeed');
  assert.equal(results[1].outcome.success, false, 'Current authenticated RPC should reproduce rejection');
  assert.match(results[1].outcome.message, /PUSH_METADATA_READ_ONLY/);
  assert.equal(results[1].after.credits, results[1].before.credits, 'Rejected transaction must roll back money');
  assert.equal(results[1].after.receipt_count, 0);
  assert.equal(results[1].after.log_count, 0);
  assert.equal(results[1].after.push_order, null);
  console.log('REPRODUCED: authenticated RPC is rejected; money/log/receipt/metadata rolled back.');
} finally {
  await db.close();
}
`;
// Run the fixture using its original module imports relative to this audit folder.
const generated = prefix + suffix;
writeFileSync(new URL('./generated-probe.mjs', import.meta.url), generated);
await import('./generated-probe.mjs');
