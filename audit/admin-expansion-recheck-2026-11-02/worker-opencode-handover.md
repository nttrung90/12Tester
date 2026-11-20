# Notification Worker Effective TLS Bug Fix Handover

**Date:** 2026-11-02  
**Scope:** `backend/notification-worker/worker-runner.mjs`, `backend/notification-worker/worker-runner.test.mjs`, `backend/notification-worker/README.md`  
**Model & Variant:** `google/antigravity-gemini-3.8-flash` (`--variant high`)  
**Status:** COMPLETE (Ready for Root Independent Review)

---

## 1. Problem Diagnosis & Evidence

### Root Cause
In `backend/notification-worker/worker-runner.mjs`, the worker configured PostgreSQL connection pools as:
```javascript
new Pool({
  connectionString: dbUrl,
  ssl: sslConfig // e.g. { rejectUnauthorized: true }
})
```
However, the installed `pg` client (`pg/lib/connection-parameters.js`) parses `pool.options.connectionString` upon client construction:
```javascript
if (config.connectionString) {
  config = Object.assign({}, config, parse(config.connectionString))
}
```
`pg-connection-string` parses query parameters from the URL:
- `?sslmode=disable` resulted in `config.ssl = false`, completely disabling TLS.
- `?sslmode=no-verify` resulted in `config.ssl = { rejectUnauthorized: false }`, silently bypassing certificate verification.
- `?ssl=false` resulted in `config.ssl = 'false'`, disabling or breaking TLS options.
- `?sslmode=verify-ca` disabled hostname verification (`checkServerIdentity`).
- When `DATABASE_SSL=false` was set, a URL containing `?sslmode=require` resulted in `config.ssl = {}`, silently re-enabling TLS and overriding the local development opt-out.

Existing tests only asserted `pool.options.ssl`, completely missing the effective `pg.Client.connectionParameters.ssl`.

### Baseline Evidence
- `audit/admin-expansion-recheck-2026-11-02/worker-tls-effective-before.log`:
  Unchanged root probe `worker-tls-effective-probe.mjs` failed with:
  `AssertionError [ERR_ASSERTION]: Default TLS was weakened by ?sslmode=disable`

---

## 2. Implemented Fix

### A. Configuration Resolution & Normalization (`worker-runner.mjs`)
Implemented `resolveTlsConfiguration(rawUrl, databaseSslEnv)`:
1. **Default Mode (`process.env.DATABASE_SSL !== 'false'`)**:
   - Enforces strict verified TLS (`rejectUnauthorized: true` with standard hostname verification).
   - Conflicting weak settings (`sslmode=disable`, `sslmode=no-verify`, `ssl=false`, `ssl=0`, `sslmode=verify-ca`, `sslmode=allow`, `sslmode=prefer`, and insecure `uselibpqcompat=true`) are safely rejected with clear, sanitized error messages.
   - Supported settings (`plain URL`, `sslmode=require`, `sslmode=verify-full`) are normalized by stripping query parameters from the connection URL before passing to `Pool`, preventing `pg` from overwriting `rejectUnauthorized: true` with `{}`.
   - Custom CA certificates (`sslrootcert`), client certificates (`sslcert`), and keys (`sslkey`) are preserved into `ssl: { rejectUnauthorized: true, ca, cert, key }` without disabling verification.
2. **Local Development Opt-Out (`DATABASE_SSL=false`)**:
   - Explicit environment override for local development against unencrypted PostgreSQL instances.
   - Decided and enforced precedence: `DATABASE_SSL=false` overrides conflicting URL flags (e.g., `sslmode=require`, `sslmode=verify-full`). SSL parameters are stripped so `client.connectionParameters.ssl` is reliably `false`.
3. **Credential Sanitization**:
   - Error messages never interpolate passwords, secrets, or raw connection strings. Only sanitized parameter keys/values (e.g. `'sslmode=disable'`) are surfaced.

### B. Documentation (`README.md`)
Updated `backend/notification-worker/README.md` with a dedicated "TLS Precedence and URL Query Handling" section detailing:
- Verified TLS enforcement by default.
- Safe rejection of downgrade query parameters.
- Preservation of custom CA certificates.
- Explicit `DATABASE_SSL=false` precedence for local testing.

### C. Expanded Test Suite (`worker-runner.test.mjs`)
Added comprehensive tests inspecting effective `pg.Client.connectionParameters` without database connection:
- **Test 9:** Effective TLS on `pg.Client` for plain URLs (`tls: true`, `rejectUnauthorized: true`, no `checkServerIdentity` override).
- **Test 10:** Effective TLS on `pg.Client` for `?sslmode=require` and `?sslmode=verify-full`.
- **Test 11:** Safe rejection of conflicting weak modes (`sslmode=disable`, `sslmode=no-verify`, `ssl=false`, `ssl=0`, `sslmode=verify-ca`).
- **Test 12:** Hostname verification enforcement & `uselibpqcompat` security rules.
- **Test 13:** Custom CA certificate (`sslrootcert`) preservation with `rejectUnauthorized: true`.
- **Test 14:** `DATABASE_SSL=false` precedence across plain, `sslmode=require`, `sslmode=verify-full`, `sslmode=disable`, and `ssl=false`.
- **Test 15:** Password & credential sanitization on TLS rejection (never leaks sensitive credentials in error messages).
- Preserved all existing Tests 1 through 8 (recovery, inFlight release, overlap guard, query error, CLI `--once` exit codes, sanitization).

---

## 3. Verification & Results

### Unchanged Root Probe
Executed:
```bash
node audit/admin-expansion-recheck-2026-11-02/worker-tls-effective-probe.mjs
```
Result (captured in `audit/admin-expansion-recheck-2026-11-02/worker-tls-effective-after.log`):
```json
{
  "connectedToDatabase": false,
  "results": [
    {
      "mode": "plain URL",
      "safelyRejected": false,
      "tls": true,
      "verified": true
    },
    {
      "mode": "?sslmode=disable",
      "safelyRejected": true
    },
    {
      "mode": "?sslmode=no-verify",
      "safelyRejected": true
    },
    {
      "mode": "?ssl=false",
      "safelyRejected": true
    },
    {
      "mode": "?sslmode=verify-ca",
      "safelyRejected": true
    },
    {
      "mode": "?sslmode=require",
      "safelyRejected": false,
      "tls": true,
      "verified": true
    },
    {
      "mode": "?sslmode=verify-full",
      "safelyRejected": false,
      "tls": true,
      "verified": true
    }
  ]
}
```
**Outcome:** Exit code 0, all 7 modes pass root assertions. No database connection attempted.

### Worker Runner Test Suite
Executed:
```bash
node --test backend/notification-worker/worker-runner.test.mjs
```
Result (captured in `audit/admin-expansion-recheck-2026-11-02/worker-runner-test-after.log`):
```
=== RUNNING WORKER-RUNNER TESTS ===
[notification-worker] Iteration error: Connection refused by database
[notification-worker 2026-11-01T23:42:16.220Z] Iteration completed: {"success":true,"processed":1}
[notification-worker] Iteration error: Lock timeout in statement
[notification-worker 2026-11-01T23:42:16.221Z] Iteration completed: {"success":true,"processed":2}
[notification-worker] Previous iteration still in progress, skipping tick.
[notification-worker 2026-11-01T23:42:16.222Z] Iteration completed: {"success":true,"processed":99}
[notification-worker 2026-11-01T23:42:16.222Z] Iteration completed: {"success":true,"processed":100}
=== ALL WORKER-RUNNER TESTS PASSED ===
✔ backend\notification-worker\worker-runner.test.mjs (137.8255ms)
ℹ tests 1
ℹ suites 0
ℹ pass 1
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 144.6994
```
**Outcome:** Exit code 0, all 15 tests pass.

---

## 4. Modified Files List
1. `backend/notification-worker/worker-runner.mjs`
2. `backend/notification-worker/worker-runner.test.mjs`
3. `backend/notification-worker/README.md`
4. `audit/admin-expansion-recheck-2026-11-02/worker-runner-test-after.log`
5. `audit/admin-expansion-recheck-2026-11-02/worker-tls-effective-after.log`
6. `audit/admin-expansion-recheck-2026-11-02/worker-opencode-handover.md`

No Android/SQL/migration/package/dependency/quota/model configuration files were modified. All pending changes from other sessions remain preserved. No live DB connections or network probes were performed. No git commits or pushes were made.
