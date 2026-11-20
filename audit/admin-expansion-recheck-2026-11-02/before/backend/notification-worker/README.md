# @ntt/notification-worker

Background execution daemon for notification campaigns fanout and delivery in CrossTest / 12Tester.

## Purpose
When `pg_cron` extension is available in Supabase, notification campaigns are automatically processed every minute via PostgreSQL cron jobs.
In deployment environments where `pg_cron` is not enabled or available, this service worker runs continuously to process due notification campaigns.

## Features
- **In-Flight Guard**: Single concurrency guard prevents execution overlap if a batch takes longer than the tick interval.
- **Completion-Based Scheduling**: Uses `setTimeout` after iteration completion instead of fixed `setInterval`, avoiding worker backlog.
- **Sanitized Logging**: Database passwords, connection strings, and tokens are scrubbed before printing.
- **Strict TLS by Default**: Enforces verified TLS certificates (`rejectUnauthorized: true`). Local non-SSL development can explicitly opt out via `DATABASE_SSL=false`.
- **Configurable Batching**: Allows tuning batch size (recipients per batch) and campaigns per run.

## Setup & Locked Dependencies
Install dependencies with locked lockfile:
```bash
cd backend/notification-worker
npm ci
```

## Environment Variables
- `DATABASE_URL` (or `SUPABASE_DB_URL`): PostgreSQL connection string (`postgres://user:password@host:5432/dbname`).
- `DATABASE_SSL`: Defaults to verified TLS (`rejectUnauthorized: true`). Set explicitly to `'false'` only for local development against unencrypted PostgreSQL instances.

## Execution Examples

### 1. Foreground Execution (Testing)
**Linux / macOS:**
```bash
# Single iteration (runs once and exits)
DATABASE_URL="postgres://worker_user:example_placeholder_password@localhost:5432/crosstest" node worker-runner.mjs --once

# Continuous daemon
DATABASE_URL="postgres://worker_user:example_placeholder_password@localhost:5432/crosstest" node worker-runner.mjs --interval=60 --batch=500
```

**Windows PowerShell:**
```powershell
# Single iteration
$env:DATABASE_URL = "postgres://worker_user:example_placeholder_password@localhost:5432/crosstest"; node worker-runner.mjs --once

# Continuous daemon
$env:DATABASE_URL = "postgres://worker_user:example_placeholder_password@localhost:5432/crosstest"; node worker-runner.mjs --interval=60 --batch=500
```

### 2. PM2 Production Process Manager
Using environment variable prefix:
**Linux / macOS:**
```bash
DATABASE_URL="postgres://worker_user:example_placeholder_password@localhost:5432/crosstest" pm2 start worker-runner.mjs --name "crosstest-notification-worker" -- --interval=60 --batch=500
```

**Windows PowerShell:**
```powershell
$env:DATABASE_URL="postgres://worker_user:example_placeholder_password@localhost:5432/crosstest"
pm2 start worker-runner.mjs --name "crosstest-notification-worker" -- --interval=60 --batch=500
```

Or using a standard PM2 ecosystem file (`ecosystem.config.cjs`):
```javascript
module.exports = {
  apps: [{
    name: 'crosstest-notification-worker',
    script: 'worker-runner.mjs',
    args: '--interval=60 --batch=500',
    env: {
      NODE_ENV: 'production',
      DATABASE_URL: process.env.DATABASE_URL
    }
  }]
};
```
And launch via:
```bash
pm2 start ecosystem.config.cjs
```

### 3. Systemd Service (Linux Daemon)
Create `/etc/systemd/system/crosstest-notification-worker.service`:
```ini
[Unit]
Description=CrossTest Notification Campaigns Fanout Worker
After=network.target

[Service]
Type=simple
User=crosstest
WorkingDirectory=/opt/crosstest/backend/notification-worker
Environment="DATABASE_URL=postgres://worker_user:example_placeholder_password@localhost:5432/crosstest"
Environment="NODE_ENV=production"
ExecStart=/usr/bin/node worker-runner.mjs --interval=60 --batch=500
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now crosstest-notification-worker
```
