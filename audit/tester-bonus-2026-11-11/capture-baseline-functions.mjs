import { mkdirSync, writeFileSync } from 'node:fs';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
const db = new pg.Client({ host: '127.0.0.1', port: 55439, user: 'bonus_test_admin', database: 'tester_bonus_test', connectionTimeoutMillis: 5000 });
await db.connect();
try {
  const names = ['_submit_tester_report_core', '_impl_approve_tester_report', 'auto_accept_expired_reports', '_impl_reject_tester_report', '_impl_admin_delete_campaign', 'delete_campaign', 'sync_campaign_lifecycle', 'cleanup_expired_campaigns', '_campaign_json', 'trg_campaigns_guard_financial_snapshots', 'guard_campaign_financial_snapshots', '_grant_report_tip'];
  const { rows } = await db.query(`select p.proname as name, p.oid, pg_get_function_identity_arguments(p.oid) as arguments, pg_get_functiondef(p.oid) as definition
    from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname=any($1::text[]) order by p.proname,p.oid`, [names]);
  const output = new URL('./baseline-functions/', import.meta.url);
  mkdirSync(output, { recursive: true });
  for (const row of rows) writeFileSync(new URL(`${row.name}_${row.oid}.sql`, output), row.definition, 'utf8');
  const constraints = await db.query(`select conname as name, conrelid::regclass::text as relation, pg_get_constraintdef(oid) as definition
    from pg_constraint where conrelid in ('public.tester_reports'::regclass,'public.campaigns'::regclass,'public.profiles'::regclass) order by relation,name`);
  writeFileSync(new URL('./baseline-constraints.json', import.meta.url), JSON.stringify(constraints.rows, null, 2));
  console.log(JSON.stringify(rows.map(({ name, arguments: args }) => ({ name, arguments: args })), null, 2));
  console.log(JSON.stringify(constraints.rows.filter(row => row.definition.includes('CASCADE') || row.name.includes('status')), null, 2));
} finally { await db.end(); }
