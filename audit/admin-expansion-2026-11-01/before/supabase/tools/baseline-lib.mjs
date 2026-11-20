import { readFileSync, readdirSync } from 'node:fs';
import { PGlite } from '../tests/node_modules/@electric-sql/pglite/dist/index.js';
import { pgcrypto } from '../tests/node_modules/@electric-sql/pglite/dist/contrib/pgcrypto.js';
import { uuid_ossp } from '../tests/node_modules/@electric-sql/pglite/dist/contrib/uuid_ossp.js';

export const root = new URL('../../', import.meta.url);
export const archive = 'supabase/migrations_archive/pre_baseline_062/';
export const read = path => readFileSync(new URL(path, root), 'utf8').replaceAll('\r\n', '\n');
export const quote = value => `'${String(value).replaceAll("'", "''")}'`;
export const ident = value => `"${String(value).replaceAll('"', '""')}"`;
export function legacyFiles() {
  let directory = archive;
  try { readdirSync(new URL(directory, root)); } catch { directory = 'supabase/migrations/'; }
  return readdirSync(new URL(directory, root)).filter(name => /^0\d\d_.*\.sql$/.test(name) && Number(name.slice(0, 3)) <= 61)
    .sort().map(name => ({ name, sql: read(directory + name) }));
}
export async function createDb({ legacyGrants = false } = {}) {
  const db = new PGlite({ extensions: { pgcrypto, uuid_ossp } });
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
    create schema auth;
    create table auth.users (
      id uuid primary key, email text, raw_user_meta_data jsonb default '{}',
      email_confirmed_at timestamptz, confirmation_token text,
      email_change text, email_change_token_new text, recovery_token text
    );
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    $$;
    grant usage on schema public, auth to anon, authenticated, service_role;
    grant execute on function auth.uid() to anon, authenticated, service_role;
    ${legacyGrants ? `
      alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
      alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
      alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
    ` : ''}
  `);
  return db;
}
export async function replay(db, { omit046 = false, through = 61 } = {}) {
  for (const file of legacyFiles()) {
    if (Number(file.name.slice(0, 3)) > through || (omit046 && file.name.startsWith('046_'))) continue;
    await db.exec(file.sql);
  }
}
export const queries = {
  tables: `select c.relname as name,c.relrowsecurity as rls,c.relforcerowsecurity as force_rls,
      obj_description(c.oid,'pg_class') as comment
    from pg_class c where c.relnamespace='public'::regnamespace and c.relkind='r' order by c.relname`,
  columns: `select c.relname as table_name,a.attname as name,a.attnum as position,
      format_type(a.atttypid,a.atttypmod) as type,a.attnotnull as not_null,
      a.attidentity as identity,pg_get_expr(d.adbin,d.adrelid) as default_expr,
      col_description(c.oid,a.attnum) as comment
    from pg_class c join pg_attribute a on a.attrelid=c.oid
    left join pg_attrdef d on d.adrelid=c.oid and d.adnum=a.attnum
    where c.relnamespace='public'::regnamespace and c.relkind='r' and a.attnum>0 and not a.attisdropped
    order by c.relname,a.attnum`,
  constraints: `select r.relname as table_name,c.conname as name,c.contype as type,
      pg_get_constraintdef(c.oid,true) as definition,c.convalidated as validated
    from pg_constraint c join pg_class r on r.oid=c.conrelid
    -- PG18 exposes named NOT NULL constraints here. Column attnotnull already
    -- captures those portably; do not emit PG18-only ADD CONSTRAINT ... NOT NULL.
    where r.relnamespace='public'::regnamespace and c.contype in('p','u','c','f','x')
    order by case c.contype when 'p' then 0 when 'u' then 1 when 'c' then 2 else 3 end,r.relname,c.conname`,
  indexes: `select t.relname as table_name,i.relname as name,pg_get_indexdef(i.oid) as definition
    from pg_index x join pg_class i on i.oid=x.indexrelid join pg_class t on t.oid=x.indrelid
    where t.relnamespace='public'::regnamespace
      and not exists(select 1 from pg_constraint c where c.conindid=i.oid)
    order by i.relname`,
  sequences: `select c.relname as name,format_type(s.seqtypid,null) as type,
      s.seqstart::text as start,s.seqincrement::text as increment,s.seqmin::text as min,
      s.seqmax::text as max,s.seqcache::text as cache,s.seqcycle as cycle,
      t.relname as owned_table,a.attname as owned_column,d.deptype
    from pg_sequence s join pg_class c on c.oid=s.seqrelid
    left join pg_depend d on d.classid='pg_class'::regclass and d.objid=c.oid and d.deptype in('a','i')
    left join pg_class t on t.oid=d.refobjid left join pg_attribute a on a.attrelid=t.oid and a.attnum=d.refobjsubid
    where c.relnamespace='public'::regnamespace order by c.relname`,
  functions: `select p.proname as name,pg_get_function_identity_arguments(p.oid) as args,
      oidvectortypes(p.proargtypes) as types,pg_get_functiondef(p.oid) as definition,
      obj_description(p.oid,'pg_proc') as comment
    from pg_proc p where p.pronamespace='public'::regnamespace and p.prokind='f'
      and not exists(select 1 from pg_depend d where d.classid='pg_proc'::regclass and d.objid=p.oid and d.deptype='e')
    order by p.proname,oidvectortypes(p.proargtypes)`,
  triggers: `select n.nspname as schema_name,c.relname as table_name,t.tgname as name,
      pg_get_triggerdef(t.oid,true) as definition,t.tgenabled as enabled
    from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
    where not t.tgisinternal and t.tgfoid in(select oid from pg_proc where pronamespace='public'::regnamespace)
    order by n.nspname,c.relname,t.tgname`,
  policies: `select schemaname as schema_name,tablename as table_name,policyname as name,
      permissive,roles,cmd,qual,with_check from pg_policies where schemaname='public'
    order by tablename,policyname`,
  relationGrants: `select c.relname as name,c.relkind as kind,
      case when x.grantee=0 then 'PUBLIC' else pg_get_userbyid(x.grantee) end as role,
      x.privilege_type as privilege,x.is_grantable as grantable
    from pg_class c cross join lateral aclexplode(coalesce(c.relacl,acldefault(case when c.relkind='S' then 'S'::"char" else 'r'::"char" end,c.relowner))) x
    where c.relnamespace='public'::regnamespace and c.relkind in('r','S')
      and (x.grantee=0 or pg_get_userbyid(x.grantee) in('anon','authenticated','service_role'))
    order by c.relname,role,privilege`,
  columnGrants: `select c.relname as table_name,a.attname as name,
      case when x.grantee=0 then 'PUBLIC' else pg_get_userbyid(x.grantee) end as role,
      x.privilege_type as privilege,x.is_grantable as grantable
    from pg_class c join pg_attribute a on a.attrelid=c.oid cross join lateral aclexplode(a.attacl) x
    where c.relnamespace='public'::regnamespace and c.relkind='r' and a.attnum>0 and not a.attisdropped
      and (x.grantee=0 or pg_get_userbyid(x.grantee) in('anon','authenticated','service_role'))
    order by c.relname,a.attname,role,privilege`,
  functionGrants: `select p.proname as name,oidvectortypes(p.proargtypes) as types,
      case when x.grantee=0 then 'PUBLIC' else pg_get_userbyid(x.grantee) end as role,
      x.privilege_type as privilege,x.is_grantable as grantable
    from pg_proc p cross join lateral aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) x
    where p.pronamespace='public'::regnamespace and p.prokind='f'
      and not exists(select 1 from pg_depend d where d.classid='pg_proc'::regclass and d.objid=p.oid and d.deptype='e')
      and (x.grantee=0 or pg_get_userbyid(x.grantee) in('anon','authenticated','service_role'))
    order by p.proname,oidvectortypes(p.proargtypes),role,privilege`,
};
export async function catalog(db) {
  const result = {};
  // Sequential on one connection, so all reads see the same completed DDL state.
  for (const [key, sql] of Object.entries(queries)) result[key] = (await db.query(sql)).rows;
  return result;
}
export async function dataSnapshot(db, { omitMediaExpired = false } = {}) {
  const tables = (await db.query(queries.tables)).rows;
  const result = {};
  for (const { name } of tables) {
    result[name] = (await db.query(`select ${omitMediaExpired && name === 'tester_reports' ? "to_jsonb(t)-'media_expired'" : 'to_jsonb(t)'} as row from public.${ident(name)} t order by 1::text`)).rows
      .map(r => r.row).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));
  }
  return result;
}
