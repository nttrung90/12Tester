import assert from 'node:assert/strict';
import pg from '../../backend/notification-worker/node_modules/pg/lib/index.js';
import { getClientPool, resetClientPoolForTesting } from '../../backend/notification-worker/worker-runner.mjs';

const savedUrl = process.env.DATABASE_URL;
const savedSsl = process.env.DATABASE_SSL;
const results = [];
try {
  for (const mode of ['', '?sslmode=disable', '?sslmode=no-verify', '?ssl=false', '?sslmode=verify-ca', '?sslmode=require', '?sslmode=verify-full']) {
    process.env.DATABASE_URL = `postgres://fixture:placeholder@fixture.invalid:5432/test${mode}`;
    delete process.env.DATABASE_SSL;
    resetClientPoolForTesting();
    let pool;
    try {
      pool = await getClientPool();
      const client = new pg.Client(pool.options);
      const ssl = client.connectionParameters.ssl;
      results.push({ mode: mode || 'plain URL', safelyRejected: false, tls: ssl !== false,
        verified: ssl !== false && ssl?.rejectUnauthorized !== false && !ssl?.checkServerIdentity });
    } catch {
      results.push({ mode: mode || 'plain URL', safelyRejected: true });
    } finally {
      await pool?.end();
    }
  }
  console.log(JSON.stringify({ connectedToDatabase: false, results }, null, 2));
  for (const result of results) {
    assert.ok(result.safelyRejected || (result.tls && result.verified), `Default TLS was weakened by ${result.mode}`);
    if (['plain URL', '?sslmode=require', '?sslmode=verify-full'].includes(result.mode)) {
      assert.equal(result.safelyRejected, false, `A normal verified TLS URL must remain usable: ${result.mode}`);
    }
  }
} finally {
  resetClientPoolForTesting();
  if (savedUrl === undefined) delete process.env.DATABASE_URL; else process.env.DATABASE_URL = savedUrl;
  if (savedSsl === undefined) delete process.env.DATABASE_SSL; else process.env.DATABASE_SSL = savedSsl;
}
