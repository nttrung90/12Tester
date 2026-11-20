import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import pg from './node_modules/pg/lib/index.js';
import { runIteration, sanitizeError, sanitizeUrl, getClientPool, resetClientPoolForTesting, resolveTlsConfiguration } from './worker-runner.mjs';

console.log('=== RUNNING WORKER-RUNNER TESTS ===');

// Test 1: URL sanitization
const safeUrl = sanitizeUrl('postgres://admin:supersecret@db.crosstest.local:5432/postgres');
assert.ok(!safeUrl.includes('supersecret'), 'Password must be redacted from sanitized URL');
assert.ok(safeUrl.includes('db.crosstest.local'), 'Host must remain in sanitized URL');

// Test 2: Error sanitization
const safeErr = sanitizeError(new Error('Connection failed to postgres://user:mypassword@localhost:5432/db with key=secret123'));
assert.ok(!safeErr.includes('mypassword'), 'Password must be redacted from error message');
assert.ok(!safeErr.includes('secret123'), 'Key must be redacted from error message');

// Test 3: Connect failure releases inFlight and allows next attempt to recover
let connectAttempts = 0;
const mockPoolRecover = {
  connect: async () => {
    connectAttempts++;
    if (connectAttempts === 1) {
      throw new Error('Connection refused by database');
    }
    return {
      query: async () => ({ rows: [{ result: { success: true, processed: 1 } }] }),
      release: () => {}
    };
  }
};

await assert.rejects(
  async () => { await runIteration(mockPoolRecover); },
  /Connection refused by database/,
  'First attempt should fail'
);

const recoverResult = await runIteration(mockPoolRecover);
assert.equal(recoverResult.processed, 1, 'Second attempt must proceed after connect failure and not hang inFlight');

// Test 4: Query failure releases inFlight and allows next attempt to recover
let queryAttempts = 0;
let releasedCount = 0;
const mockPoolQueryFail = {
  connect: async () => ({
    query: async () => {
      queryAttempts++;
      if (queryAttempts === 1) {
        throw new Error('Lock timeout in statement');
      }
      return { rows: [{ result: { success: true, processed: 2 } }] };
    },
    release: () => { releasedCount++; }
  })
};

await assert.rejects(
  async () => { await runIteration(mockPoolQueryFail); },
  /Lock timeout in statement/
);
assert.equal(releasedCount, 1, 'Client must be released even when query fails');

const queryRecoverResult = await runIteration(mockPoolQueryFail);
assert.equal(queryRecoverResult.processed, 2, 'Second attempt should recover after query failure');
assert.equal(releasedCount, 2, 'Client must be released on successful query');

// Test 5: Overlap prevention with held promise
let unblockCall1;
const heldPromise = new Promise(resolve => { unblockCall1 = resolve; });
let client1Released = false;

const mockPoolOverlap = {
  connect: async () => ({
    query: async () => {
      await heldPromise;
      return { rows: [{ result: { success: true, processed: 99 } }] };
    },
    release: () => { client1Released = true; }
  })
};

// Start call 1 (held in flight)
const call1Promise = runIteration(mockPoolOverlap);

// Concurrently attempt call 2 while call 1 is in-flight
const call2Result = await runIteration(mockPoolOverlap);
assert.equal(call2Result, null, 'Concurrent second iteration must be skipped while first is in-flight');

// Release call 1
unblockCall1();
const call1Result = await call1Promise;
assert.equal(call1Result.processed, 99, 'Call 1 must complete successfully');
assert.equal(client1Released, true, 'Client 1 must be released');

// Now call 3 should succeed since inFlight was released
let client3Released = false;
const mockPool3 = {
  connect: async () => ({
    query: async () => ({ rows: [{ result: { success: true, processed: 100 } }] }),
    release: () => { client3Released = true; }
  })
};
const call3Result = await runIteration(mockPool3);
assert.equal(call3Result.processed, 100, 'Subsequent call after overlap release must run normally');
assert.equal(client3Released, true, 'Client 3 must be released');

// Test 6: CLI '--once' missing URL exit code & sanitized output
const scriptPath = resolve(fileURLToPath(import.meta.url), '../worker-runner.mjs');
const spawnResult = spawnSync(process.execPath, [scriptPath, '--once'], {
  env: { ...process.env, DATABASE_URL: '', SUPABASE_DB_URL: '' }
});

assert.notEqual(spawnResult.status, 0, 'CLI --once without DATABASE_URL must exit with non-zero status code');
const stderrOutput = spawnResult.stderr.toString();
assert.ok(stderrOutput.includes('DATABASE_URL or SUPABASE_DB_URL environment variable is required'), 'CLI must output sanitized missing configuration diagnosis');
assert.ok(!stderrOutput.includes('password='), 'CLI output must not contain sensitive info');

// Test 7: Real Pool TLS Options Smoke Test (Default verified TLS)
const prevDbUrl = process.env.DATABASE_URL;
const prevDbSsl = process.env.DATABASE_SSL;

try {
  process.env.DATABASE_URL = 'postgres://fixture:dummy@127.0.0.1:1/fixture';
  delete process.env.DATABASE_SSL;
  resetClientPoolForTesting();

  const pool = await getClientPool();
  assert.equal(pool.options.ssl?.rejectUnauthorized, true, 'TLS verification must default to true (rejectUnauthorized: true)');
  await pool.end();
  resetClientPoolForTesting();

  // Test 8: Explicit local opt-out (DATABASE_SSL=false)
  process.env.DATABASE_SSL = 'false';
  const poolOptOut = await getClientPool();
  assert.equal(poolOptOut.options.ssl, false, 'DATABASE_SSL=false must disable SSL completely for local development');
  await poolOptOut.end();
  resetClientPoolForTesting();

  // Test 9: Effective pg.Client.connectionParameters for Plain URL (no connect)
  delete process.env.DATABASE_SSL;
  process.env.DATABASE_URL = 'postgres://fixture:placeholder@fixture.invalid:5432/test';
  resetClientPoolForTesting();
  const poolPlain = await getClientPool();
  const clientPlain = new pg.Client(poolPlain.options);
  assert.equal(clientPlain.connectionParameters.ssl !== false, true, 'Plain URL must have effective TLS enabled');
  assert.equal(clientPlain.connectionParameters.ssl?.rejectUnauthorized, true, 'Plain URL must enforce rejectUnauthorized: true');
  assert.equal(!!clientPlain.connectionParameters.ssl?.checkServerIdentity, false, 'Plain URL must enforce hostname verification (no checkServerIdentity override)');
  await poolPlain.end();
  resetClientPoolForTesting();

  // Test 10: Effective pg.Client.connectionParameters for positive URLs (sslmode=require and sslmode=verify-full)
  for (const mode of ['?sslmode=require', '?sslmode=verify-full']) {
    process.env.DATABASE_URL = `postgres://fixture:placeholder@fixture.invalid:5432/test${mode}`;
    resetClientPoolForTesting();
    const poolMode = await getClientPool();
    const clientMode = new pg.Client(poolMode.options);
    assert.equal(clientMode.connectionParameters.ssl !== false, true, `${mode} must have effective TLS enabled`);
    assert.equal(clientMode.connectionParameters.ssl?.rejectUnauthorized, true, `${mode} must enforce rejectUnauthorized: true`);
    assert.equal(!!clientMode.connectionParameters.ssl?.checkServerIdentity, false, `${mode} must enforce hostname verification`);
    await poolMode.end();
    resetClientPoolForTesting();
  }

  // Test 11: Safely reject conflicting weak URL settings
  const weakModes = [
    { mode: '?sslmode=disable', errPattern: /Insecure database TLS configuration.*sslmode=disable/ },
    { mode: '?sslmode=no-verify', errPattern: /Insecure database TLS configuration.*sslmode=no-verify/ },
    { mode: '?ssl=false', errPattern: /Insecure database TLS configuration.*ssl=false/ },
    { mode: '?ssl=0', errPattern: /Insecure database TLS configuration.*ssl=0/ },
    { mode: '?sslmode=verify-ca', errPattern: /Insecure database TLS configuration.*hostname verification/ }
  ];

  for (const { mode, errPattern } of weakModes) {
    process.env.DATABASE_URL = `postgres://fixture:placeholder@fixture.invalid:5432/test${mode}`;
    resetClientPoolForTesting();
    await assert.rejects(
      async () => { await getClientPool(); },
      errPattern,
      `Weak mode ${mode} must be safely rejected with clear descriptive error`
    );
  }

  // Test 12: Hostname verification enforcement & uselibpqcompat handling
  process.env.DATABASE_URL = 'postgres://fixture:placeholder@fixture.invalid:5432/test?uselibpqcompat=true&sslmode=require';
  resetClientPoolForTesting();
  await assert.rejects(
    async () => { await getClientPool(); },
    /uselibpqcompat=true.*weakens verification/,
    'uselibpqcompat=true with sslmode=require must be rejected because it skips hostname verification'
  );

  process.env.DATABASE_URL = 'postgres://fixture:placeholder@fixture.invalid:5432/test?uselibpqcompat=true&sslmode=verify-full';
  resetClientPoolForTesting();
  const poolLibpqFull = await getClientPool();
  const clientLibpqFull = new pg.Client(poolLibpqFull.options);
  assert.equal(clientLibpqFull.connectionParameters.ssl !== false, true);
  assert.equal(clientLibpqFull.connectionParameters.ssl?.rejectUnauthorized, true);
  assert.equal(!!clientLibpqFull.connectionParameters.ssl?.checkServerIdentity, false);
  await poolLibpqFull.end();
  resetClientPoolForTesting();

  // Test 13: Custom CA certificate preservation (sslrootcert) with verified TLS
  const packageJsonPath = resolve(fileURLToPath(import.meta.url), '../package.json');
  process.env.DATABASE_URL = `postgres://fixture:placeholder@fixture.invalid:5432/test?sslrootcert=${packageJsonPath}`;
  resetClientPoolForTesting();
  const poolCa = await getClientPool();
  const clientCa = new pg.Client(poolCa.options);
  assert.equal(clientCa.connectionParameters.ssl?.rejectUnauthorized, true, 'Custom CA must enforce rejectUnauthorized: true');
  assert.ok(typeof clientCa.connectionParameters.ssl?.ca === 'string', 'Custom CA content must be preserved');
  assert.ok(clientCa.connectionParameters.ssl?.ca.includes('@ntt/notification-worker'), 'CA content matches source');
  assert.equal(!!clientCa.connectionParameters.ssl?.checkServerIdentity, false, 'Custom CA must retain hostname verification');
  await poolCa.end();
  resetClientPoolForTesting();

  // Test 14: DATABASE_SSL=false precedence over conflicting URL flags
  process.env.DATABASE_SSL = 'false';
  for (const urlSuffix of ['', '?sslmode=require', '?sslmode=verify-full', '?sslmode=disable', '?ssl=false']) {
    process.env.DATABASE_URL = `postgres://fixture:placeholder@fixture.invalid:5432/test${urlSuffix}`;
    resetClientPoolForTesting();
    const poolOpt = await getClientPool();
    const clientOpt = new pg.Client(poolOpt.options);
    assert.equal(clientOpt.connectionParameters.ssl, false, `DATABASE_SSL=false must force effective ssl=false for url suffix ${urlSuffix}`);
    await poolOpt.end();
    resetClientPoolForTesting();
  }

  // Test 15: Credential and password sanitization on TLS rejection
  delete process.env.DATABASE_SSL;
  process.env.DATABASE_URL = 'postgres://sensitive_user:sensitive_supersecret_password@127.0.0.1:5432/test?sslmode=disable';
  resetClientPoolForTesting();
  try {
    await getClientPool();
    assert.fail('Should have rejected sslmode=disable');
  } catch (err) {
    assert.ok(!err.message.includes('sensitive_supersecret_password'), 'Rejection error must never leak password');
    assert.ok(!err.message.includes('sensitive_user'), 'Rejection error must never leak username');
  }
} finally {
  if (prevDbUrl !== undefined) process.env.DATABASE_URL = prevDbUrl;
  else delete process.env.DATABASE_URL;
  if (prevDbSsl !== undefined) process.env.DATABASE_SSL = prevDbSsl;
  else delete process.env.DATABASE_SSL;
}

console.log('=== ALL WORKER-RUNNER TESTS PASSED ===');
