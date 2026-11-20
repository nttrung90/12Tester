# Private native PostgreSQL for this task

Supervisor initialized and verified PostgreSQL17.11 using existing read-only binaries from prior task. NEW data directory: `F:/AI/12Tester/audit/immediate-daily-quota-2026-11-12/pg-data`, host127.0.0.1 ONLY, port55446, user `quota_test_admin`, DB `immediate_quota_test`. This is fabricated test data, no real credentials. Server timezoneUTC to verify that application business-day code explicitly uses Asia/Ho_Chi_Minh.

`TEST_DATABASE_URL=postgresql://quota_test_admin@127.0.0.1:55446/immediate_quota_test`

Baseline migrations062..087 are loaded. Do not apply a partially written088 or reset this DB while parent tests are active. Root will apply the stable final migration and run independent/concurrency probes, or will coordinate a separate isolated schema/database if agent requires it. Every driver must verify `current_setting('data_directory')` matches THIS task directory before using it. Never connect a production URL or read .env/local.properties/google-services.json/account data.

Root control script `local-postgres.mjs` verifies exact cluster path. At end root stops ONLY this cluster with its `--stop` option. Initial pg_ctl inherited-pipe timeout was corrected by directing child stdio to task-local files; the server was verified and bootstrap completed, as recorded in postgres-bootstrap-resume.log and native-ready.json. Do not start/stop unrelated PostgreSQL services or the previous tester-bonus cluster.
# Final status — 2026-11-12

The supervisor stopped this task's verified private cluster after final acceptance. See `postgres-stop-final.log`. No production database was used. The connection instructions below are historical; start the isolated cluster again before rerunning native probes.

