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
import fs from 'node:fs';

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

export function resolveTlsConfiguration(rawUrl, databaseSslEnv = process.env.DATABASE_SSL) {
  if (!rawUrl) {
    throw new Error('DATABASE_URL or SUPABASE_DB_URL environment variable is required.');
  }

  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    try {
      parsed = new URL(rawUrl, 'postgres://base');
    } catch {
      if (databaseSslEnv === 'false') {
        return { connectionString: rawUrl, ssl: false };
      }
      return { connectionString: rawUrl, ssl: { rejectUnauthorized: true } };
    }
  }

  const sslModeParam = parsed.searchParams.get('sslmode');
  const sslParam = parsed.searchParams.get('ssl');
  const sslMode = (sslModeParam || '').toLowerCase();
  const sslVal = (sslParam || '').toLowerCase();
  const uselibpqcompat = (parsed.searchParams.get('uselibpqcompat') || '').toLowerCase();

  // Precedence Rule 1: Explicit opt-out for local development via DATABASE_SSL=false
  // Environment variable takes precedence over URL query parameters.
  if (databaseSslEnv === 'false') {
    // Normalize connection URL so pg.Client does not re-enable SSL via connectionString reparsing
    parsed.searchParams.delete('sslmode');
    parsed.searchParams.delete('ssl');
    parsed.searchParams.delete('sslrootcert');
    parsed.searchParams.delete('sslcert');
    parsed.searchParams.delete('sslkey');
    parsed.searchParams.delete('uselibpqcompat');
    return {
      connectionString: parsed.toString(),
      ssl: false
    };
  }

  // Precedence Rule 2: Strict verified TLS by default (DATABASE_SSL !== 'false')
  // Reject conflicting or weak SSL query parameters that weaken certificate or hostname verification
  if (['disable', 'no-verify', 'allow', 'prefer', 'verify-ca'].includes(sslMode)) {
    if (sslMode === 'verify-ca') {
      throw new Error(
        "Insecure database TLS configuration: 'sslmode=verify-ca' disables hostname verification. " +
        "Verified TLS requires both certificate and hostname verification. Use 'sslmode=verify-full' or leave sslmode unspecified."
      );
    }
    if (sslMode === 'disable') {
      throw new Error(
        "Insecure database TLS configuration: Connection URL specifies 'sslmode=disable' which disables TLS. " +
        "Verified TLS is enforced by default. To allow unencrypted connections for local development, set DATABASE_SSL=false."
      );
    }
    throw new Error(
      `Insecure database TLS configuration: Connection URL specifies 'sslmode=${sslModeParam}', which disables or weakens TLS verification. ` +
      "Verified TLS is enforced by default. Use 'sslmode=verify-full' or set DATABASE_SSL=false for local development."
    );
  }

  if (['false', '0', 'no-verify', 'off'].includes(sslVal)) {
    throw new Error(
      `Insecure database TLS configuration: Connection URL specifies 'ssl=${sslParam}', which disables or weakens TLS. ` +
      "Verified TLS is enforced by default. To allow unencrypted connections for local development, set DATABASE_SSL=false."
    );
  }

  if (uselibpqcompat === 'true' && sslMode !== 'verify-full') {
    throw new Error(
      `Insecure database TLS configuration: 'uselibpqcompat=true' with sslmode='${sslModeParam || 'default'}' weakens verification. ` +
      "Use 'sslmode=verify-full' for verified TLS."
    );
  }

  // Build strict verified TLS ssl configuration
  const sslConfig = { rejectUnauthorized: true };

  // Preserve custom CA certificate if specified
  const rootCertPath = parsed.searchParams.get('sslrootcert');
  if (rootCertPath) {
    try {
      sslConfig.ca = fs.readFileSync(rootCertPath, 'utf8');
    } catch (err) {
      throw new Error(`Failed to read SSL root certificate from sslrootcert: ${err.message}`);
    }
    parsed.searchParams.delete('sslrootcert');
  }

  // Preserve client certificate / key if specified
  const certPath = parsed.searchParams.get('sslcert');
  if (certPath) {
    try {
      sslConfig.cert = fs.readFileSync(certPath, 'utf8');
    } catch (err) {
      throw new Error(`Failed to read SSL client certificate from sslcert: ${err.message}`);
    }
    parsed.searchParams.delete('sslcert');
  }

  const keyPath = parsed.searchParams.get('sslkey');
  if (keyPath) {
    try {
      sslConfig.key = fs.readFileSync(keyPath, 'utf8');
    } catch (err) {
      throw new Error(`Failed to read SSL client key from sslkey: ${err.message}`);
    }
    parsed.searchParams.delete('sslkey');
  }

  // Normalize positive SSL query flags so pg.Client does not overwrite rejectUnauthorized: true with {}
  parsed.searchParams.delete('sslmode');
  parsed.searchParams.delete('ssl');
  parsed.searchParams.delete('uselibpqcompat');

  return {
    connectionString: parsed.toString(),
    ssl: sslConfig
  };
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
  const { connectionString, ssl } = resolveTlsConfiguration(dbUrl, process.env.DATABASE_SSL);

  clientPool = new Pool({
    connectionString,
    ssl,
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
