# Isolated native PostgreSQL test runtime

- PostgreSQL 17.11, installed only under this task's `pg-runtime/` directory.
- Package: `@embedded-postgres/windows-x64@17.11.0-beta.19`; downloaded from the public npm registry with install scripts disabled. Native executables are in `pg-runtime/node_modules/@embedded-postgres/windows-x64/native/bin/`.
- Maintainer documentation: https://github.com/leinelissen/embedded-postgres . Package binaries export paths: `dist/index.js`.
- Server listens ONLY on `127.0.0.1:55439`; no service/global installation or user account was added.
- Cluster data directory: `F:/AI/12Tester/audit/tester-bonus-2026-11-11/pg-data`.
- Dedicated database: `tester_bonus_test`; role: `bonus_test_admin`. Trust authentication applies only to this isolated loopback fixture server and contains no real credentials/data.
- Test connection: `postgresql://bonus_test_admin@127.0.0.1:55439/tester_bonus_test`.
- Parent bootstrap verified `current_setting('data_directory')` matches the task directory before creating/using this database.
- Native pgcrypto and uuid-ossp extensions, Supabase-like auth fixture schema/roles and all migrations 062 through 086 have loaded successfully. NEW migration 087 is intentionally NOT loaded until complete and reviewed; parent will apply it to this test database.
- Setup evidence: `runs/postgres-init.log`, `runs/postgres-bootstrap.log`, `runs/postgres-server.log`.
- `local-postgres.mjs` uses the existing repository's node-postgres client. Existing schema is not reset/reapplied automatically.

The backend agent should write real multi-connection tests against a migrated dedicated test database. Parent can run them with TEST_DATABASE_URL pointing to the exact connection above after applying the completed bonus migration. Do not treat PGlite serialized operations as evidence of concurrent correctness. If the CLI's loopback restrictions prevent executing native tests, finish the driver and the parent will execute it outside that restriction.

Keep test drivers restricted to explicit local/test targets. Do not read project secrets, use a production URL, migrate remote services or run fixture writes against user data. This server will be stopped after verification; test data may remain in the ignored task directory for inspection.
