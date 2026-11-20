#!/usr/bin/env node

/**
 * Notification Campaigns Background Worker Daemon
 *
 * Safe, robust background executor for notification campaigns fanout.
 * Features:
 * - In-flight execution guard prevents overlapping iterations
 * - All setup & teardown protected by try/finally to guarantee inFlight release
 * - Sanitized logging (never logs connection strings, tokens, passwords or URLs)
 * - Completion-based setTimeout scheduling (no interval pileup)
 * - Configurable batch sizes, loop intervals, and SSL modes
 */

import { parseArgs } from 'node:util';

const options = {
  once: { type: 'boolean', default: false },
  interval: { type: 'string', default: '60' },
  batch: { type: 'string', default: '500' },
  campaigns: { type: 'string', default: '10' }
};

const { values } = parseArgs({ options, allowPositionals: true });
const runOnce = values.once;
const intervalMs = Math.max(5000, (parseInt(values.interval, 10) || 60) * 1000);
const batchSize = Math.max(1, parseInt(values.batch, 10) || 500);
const maxCampaigns = Math.max(1, parseInt(values.campaigns, 10) || 10);

let inFlight = false;
let clientPool = null;

export function sanitizeUrl(rawUrl) {
  if (!rawUrl) return '(not configured)';
  try {
    const parsed = new URL(rawUrl);
    return `${parsed.protocol}//${parsed.username ? '***@' : ''}${parsed.host}${parsed.pathname}`;
  } catch {
    return '(redacted)';
  }
}

export function sanitizeError(err) {
  if (!err) return 'Unknown error';
  const msg = typeof err === 'string' ? err : (err.message || String(err));
  return msg
    .replace(/postgres(?:ql)?:\/\/[^\s]+/gi, '[DATABASE_URL_REDACTED]')
    .replace(/password=[^\s]+/gi, 'password=[REDACTED]')
    .replace(/bearer\s+[a-z0-9._~+/-]+/gi, 'Bearer [REDACTED]')
    .replace(/key=[^\s]+/gi, 'key=[REDACTED]');
}

export async function getClientPool() {
  if (clientPool) return clientPool;

  const dbUrl = process.env.DATABASE_URL || process.env.SUPABASE_DB_URL;
  if (!dbUrl) {
    throw new Error('DATABASE_URL or SUPABASE_DB_URL environment variable is required.');
  }

  let pgModule;
  try {
    pgModule = await import('pg');
  } catch (err) {
    throw new Error('pg package not resolved. Ensure npm install has been executed.');
  }

  const { Pool } = pgModule.default || pgModule;
  const sslConfig = process.env.DATABASE_SSL === 'false' ? false : { rejectUnauthorized: true };

  clientPool = new Pool({
    connectionString: dbUrl,
    ssl: sslConfig,
    max: 2,
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 10000
  });

  clientPool.on('error', (err) => {
    console.error('[notification-worker] Pool background error:', sanitizeError(err));
  });

  return clientPool;
}

export function resetClientPoolForTesting() {
  clientPool = null;
}

export async function runIteration(customPool = null) {
  if (inFlight) {
    console.log('[notification-worker] Previous iteration still in progress, skipping tick.');
    return null;
  }

  inFlight = true;
  let client = null;
  try {
    const pool = customPool || await getClientPool();
    client = await pool.connect();

    const res = await client.query(
      'SELECT public.service_process_notification_campaigns($1, $2) as result',
      [batchSize, maxCampaigns]
    );
    const result = res.rows[0]?.result;
    const nowIso = new Date().toISOString();
    console.log(`[notification-worker ${nowIso}] Iteration completed:`, JSON.stringify(result));
    return result;
  } catch (err) {
    const safeMsg = sanitizeError(err);
    console.error('[notification-worker] Iteration error:', safeMsg);
    throw new Error(safeMsg);
  } finally {
    if (client) {
      try { client.release(); } catch {}
    }
    inFlight = false;
  }
}

async function main() {
  const targetUrl = process.env.DATABASE_URL || process.env.SUPABASE_DB_URL;
  if (!targetUrl) {
    console.error('[notification-worker] Fatal: DATABASE_URL or SUPABASE_DB_URL environment variable is required.');
    process.exit(1);
  }
  console.log(`[notification-worker] Starting worker against ${sanitizeUrl(targetUrl)}`);

  if (runOnce) {
    try {
      await runIteration();
      if (clientPool) await clientPool.end();
      process.exit(0);
    } catch (err) {
      console.error('[notification-worker] Fatal failure in runOnce mode:', sanitizeError(err));
      if (clientPool) {
        try { await clientPool.end(); } catch {}
      }
      process.exit(1);
    }
  } else {
    console.log(`[notification-worker] Running continuous daemon (interval: ${intervalMs / 1000}s, batchSize: ${batchSize})...`);

    const loop = async () => {
      try {
        await runIteration();
      } catch (err) {
        // Logged inside runIteration
      } finally {
        setTimeout(loop, intervalMs);
      }
    };

    loop();
  }
}

import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

if (process.argv[1]) {
  try {
    const scriptPath = fileURLToPath(import.meta.url);
    if (resolve(process.argv[1]) === resolve(scriptPath)) {
      main();
    }
  } catch {}
}
