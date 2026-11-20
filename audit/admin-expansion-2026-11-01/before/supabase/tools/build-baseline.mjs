import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { archive, root, legacyFiles, createDb, replay, catalog, quote, ident } from './baseline-lib.mjs';

// Rebuild only while preparing 062. After deployment, create 063+ instead.
const files = legacyFiles();
if (files.length !== 65) throw new Error(`Expected the 65 reviewed legacy files, found ${files.length}`);
const digest = createHash('sha256').update(files.map(f => f.name + '\n' + f.sql).join('\n')).digest('hex');
const db = await createDb({ legacyGrants: true });
try {
  await replay(db);
  const state = await catalog(db);
  const chunks = [];
  const add = (title, text) => chunks.push(`-- ${title}\n${text.trim()}\n`);
  const qualified = name => `public.${ident(name)}`;
  const role = name => name === 'PUBLIC' ? 'PUBLIC' : ident(name);
  const optionalColumn = c => c.table_name === 'tester_reports' && c.name === 'media_expired';
  const expected = state.columns.map(c => `(${quote(c.table_name)},${quote(c.name)},${quote(c.type)},${c.not_null},${optionalColumn(c)})`).join(',\n        ');
  const tableNames = state.tables.map(t => quote(t.name)).join(',');

  add('Kiểm tra database trước khi thay đổi bất kỳ bảng nghiệp vụ nào.', `
do $preflight$
declare
    v_missing text;
begin
    if to_regclass('auth.users') is null
       or to_regprocedure('auth.uid()') is null
       or not exists(select 1 from pg_roles where rolname='anon')
       or not exists(select 1 from pg_roles where rolname='authenticated')
       or not exists(select 1 from pg_roles where rolname='service_role') then
        raise exception 'BASELINE_062_REQUIRES_SUPABASE_AUTH_AND_ROLES';
    end if;
    if current_setting('crosstest.baseline_062_existing')='true' then
        select string_agg(e.table_name||'.'||e.column_name||' ['||e.expected_type||']', ', ')
        into v_missing
        from (values
        ${expected}
        ) e(table_name,column_name,expected_type,expected_not_null,optional_column)
        left join pg_namespace n on n.nspname='public'
        left join pg_class c on c.relnamespace=n.oid and c.relname=e.table_name and c.relkind='r'
        left join pg_attribute a on a.attrelid=c.oid and a.attname=e.column_name and a.attnum>0 and not a.attisdropped
        where (a.attname is null and not e.optional_column)
            or (a.attname is not null and (format_type(a.atttypid,a.atttypmod)<>e.expected_type
                or a.attnotnull<>e.expected_not_null));
        if v_missing is not null then
            raise exception 'BASELINE_062_SCHEMA_NOT_READY: %', v_missing
                using hint='File nay danh cho database da ap dung 001-061 (cho phep thieu 046), hoac database moi. Khong chay lai cac file archive tren database dang dung.';
        end if;
        if not exists(select 1 from public.daily_task_policies where effective_from=date '-infinity') then
            raise exception 'BASELINE_062_DAILY_TASK_POLICY_BASELINE_MISSING';
        end if;
    elsif exists(select 1 from pg_class where relnamespace='public'::regnamespace and relname in(${tableNames})) then
        raise exception 'BASELINE_062_PARTIAL_SCHEMA'
            using hint='Phat hien schema dang do dang. Giao dich da huy, chua thay doi du lieu.';
    end if;
end;
$preflight$;
  `);

  add('Extensions: không thay secret hoặc cấu hình Media Gateway.', `
create extension if not exists pgcrypto with schema public;
create extension if not exists "uuid-ossp" with schema public;
  `);
  add('Sequences: chỉ tạo khi thiếu, không reset bộ đếm đang dùng.', state.sequences.filter(s => s.deptype !== 'i').map(s =>
    `create sequence if not exists ${qualified(s.name)} as ${s.type} increment by ${s.increment} minvalue ${s.min} maxvalue ${s.max} start with ${s.start} cache ${s.cache}${s.cycle ? ' cycle' : ' no cycle'};`).join('\n'));

  add('Bảng ở trạng thái cuối 061; không có DROP TABLE, TRUNCATE hoặc backfill lịch sử.', state.tables.map(t => {
    const columns = state.columns.filter(c => c.table_name === t.name).map(c => {
      const def = c.identity ? ` generated ${c.identity === 'a' ? 'always' : 'by default'} as identity` : c.default_expr ? ` default ${c.default_expr}` : '';
      return `    ${ident(c.name)} ${c.type}${def}${c.not_null ? ' not null' : ''}`;
    });
    return `create table if not exists ${qualified(t.name)} (\n${columns.join(',\n')}\n);`;
  }).join('\n\n'));
  add('Bù phần schema của 046. Các report cũ giữ nguyên nội dung, thêm media_expired=false.', `
alter table public.tester_reports add column if not exists media_expired boolean not null default false;
  `);
  add('Ownership của serial sequences; không đổi last_value.', state.sequences.filter(s => s.deptype === 'a').map(s =>
    `alter sequence ${qualified(s.name)} owned by ${qualified(s.owned_table)}.${ident(s.owned_column)};`).join('\n'));

  add('Mỗi chữ ký hàm chỉ giữ một định nghĩa cuối; bao gồm đủ 046 và 061.', state.functions.map(f => f.definition.trim().replace(/;?$/, ';')).join('\n\n'));

  add('Ràng buộc: giữ nguyên khi đã có, dừng nếu phát hiện định nghĩa khác.', state.constraints.map(c => `
do $constraint$
declare v_definition text;
begin
    select pg_get_constraintdef(oid,true) into v_definition from pg_constraint
    where conrelid=${quote(qualified(c.table_name))}::regclass and conname=${quote(c.name)};
    if v_definition is null then
        alter table ${qualified(c.table_name)} add constraint ${ident(c.name)} ${c.definition};
    elsif v_definition <> ${quote(c.definition)} then
        raise exception 'BASELINE_062_CONSTRAINT_DRIFT: %.%', ${quote(c.table_name)}, ${quote(c.name)};
    end if;
end;
$constraint$;`.trim()).join('\n\n'));

  add('Indexes: không xây lại index đã tồn tại.', state.indexes.map(i => `${i.definition.replace(/^CREATE (UNIQUE )?INDEX /i, (_, unique = '') => `CREATE ${unique}INDEX IF NOT EXISTS `)};`).join('\n'));

  // Export only configuration rows from a clean replay; never export tenant data.
  const allowedSeeds = new Set(['topup_packages', 'app_configs', 'daily_task_policies', 'daily_task_policy_changes', 'home_stats_policies', 'daily_task_carryover_policies']);
  const seedOrder = ['topup_packages', 'app_configs', 'daily_task_policies', 'daily_task_policy_changes', 'home_stats_policies', 'daily_task_carryover_policies'];
  const rows = {};
  for (const t of state.tables) {
    const data = (await db.query(`select to_jsonb(t) as row from ${qualified(t.name)} t order by to_jsonb(t)::text`)).rows.map(r => r.row);
    if (data.length && !allowedSeeds.has(t.name)) throw new Error(`Review unexpected bootstrap data: ${t.name}`);
    rows[t.name] = data;
  }
  const seedSql = [];
  for (const name of seedOrder) {
    const columns = state.columns.filter(c => c.table_name === name && !c.identity && !c.default_expr?.startsWith('nextval('));
    for (const row of rows[name]) {
      const values = columns.map(c => {
        const value = row[c.name];
        if (name === 'daily_task_policy_changes' && c.name === 'policy_id') return `(select id from public.daily_task_policies where effective_from=date '-infinity')`;
        if (name === 'daily_task_policy_changes' && c.name === 'new_row') return `(select to_jsonb(p) from public.daily_task_policies p where effective_from=date '-infinity')`;
        if (value === null) return 'null';
        if (/timestamp/.test(c.type) && c.default_expr) return 'clock_timestamp()';
        if (typeof value === 'object') return `${quote(JSON.stringify(value))}::${c.type}`;
        if (typeof value === 'boolean' || typeof value === 'number') return String(value);
        return `${quote(value)}::${c.type}`;
      });
      seedSql.push(`insert into ${qualified(name)} (${columns.map(c => ident(c.name)).join(',')}) values (${values.join(',')});`);
    }
  }
  add('Chỉ khởi tạo cấu hình khi database mới. Database đang có dữ liệu không chạy phần này.', `
do $seed$
begin
    if current_setting('crosstest.baseline_062_existing')='false' then
        ${seedSql.join('\n        ')}
    end if;
end;
$seed$;
  `);

  add('Row Level Security theo schema cuối.', state.tables.map(t => `alter table ${qualified(t.name)} ${t.rls ? 'enable' : 'disable'} row level security;\nalter table ${qualified(t.name)} ${t.force_rls ? 'force' : 'no force'} row level security;`).join('\n'));
  add('Policies: chỉ thay các policy do dự án quản lý.', state.policies.map(p => {
    return `drop policy if exists ${ident(p.name)} on ${qualified(p.table_name)};\ncreate policy ${ident(p.name)} on ${qualified(p.table_name)} as ${p.permissive} for ${p.cmd} to ${p.roles.map(r => r === 'public' ? 'PUBLIC' : ident(r)).join(', ')}${p.qual ? ` using (${p.qual})` : ''}${p.with_check ? ` with check (${p.with_check})` : ''};`;
  }).join('\n\n'));
  add('Triggers: cùng các định nghĩa cuối; không kích hoạt backfill dữ liệu.', state.triggers.map(t =>
    `drop trigger if exists ${ident(t.name)} on ${ident(t.schema_name)}.${ident(t.table_name)};\n${t.definition};${t.enabled === 'O' ? '' : `\nalter table ${ident(t.schema_name)}.${ident(t.table_name)} ${t.enabled === 'D' ? 'disable' : t.enabled === 'A' ? 'enable always' : 'enable replica'} trigger ${ident(t.name)};`}`).join('\n\n'));

  const freshAcl = [];
  for (const t of state.tables) freshAcl.push(`revoke all on table ${qualified(t.name)} from PUBLIC, anon, authenticated, service_role;`);
  for (const s of state.sequences) freshAcl.push(`revoke all on sequence ${qualified(s.name)} from PUBLIC, anon, authenticated, service_role;`);
  for (const f of state.functions) freshAcl.push(`revoke all on function ${qualified(f.name)}(${f.types}) from PUBLIC, anon, authenticated, service_role;`);
  for (const a of state.relationGrants) {
    const sql = `grant ${a.privilege} on ${a.kind === 'S' ? 'sequence' : 'table'} ${qualified(a.name)} to ${role(a.role)}${a.grantable ? ' with grant option' : ''};`;
    freshAcl.push(a.privilege === 'MAINTAIN' ? `if current_setting('server_version_num')::integer >= 170000 then execute ${quote(sql)}; end if;` : sql);
  }
  for (const a of state.columnGrants) freshAcl.push(`grant ${a.privilege} (${ident(a.name)}) on ${qualified(a.table_name)} to ${role(a.role)}${a.grantable ? ' with grant option' : ''};`);
  for (const a of state.functionGrants) freshAcl.push(`grant ${a.privilege} on function ${qualified(a.name)}(${a.types}) to ${role(a.role)}${a.grantable ? ' with grant option' : ''};`);
  add('Database mới nhận ACL của chuỗi cũ. ACL hiện có của database đang dùng được giữ nguyên.', `
do $fresh_acl$
begin
    if current_setting('crosstest.baseline_062_existing')='false' then
        ${freshAcl.join('\n        ')}
    end if;
end;
$fresh_acl$;
  `);
  add('Quyền riêng của 046 cho các hàm Media Gateway vừa bổ sung.', `
revoke all on function public.get_media_gateway_secret() from public, anon, authenticated;
grant execute on function public.get_media_gateway_secret() to service_role;
revoke execute on function public.get_report_media_access(uuid) from public, anon;
grant execute on function public.get_report_media_access(uuid) to authenticated, service_role;
grant execute on function public.verify_media_access_grant(uuid,text,text,bigint) to authenticated, service_role;
revoke execute on function public.verify_media_access_grant_internal(uuid,uuid,text,text,bigint) from public, anon, authenticated;
grant execute on function public.verify_media_access_grant_internal(uuid,uuid,text,text,bigint) to service_role;
  `);

  const comments = [];
  for (const t of state.tables) if (t.comment) comments.push(`comment on table ${qualified(t.name)} is ${quote(t.comment)};`);
  for (const c of state.columns) if (c.comment) comments.push(`comment on column ${qualified(c.table_name)}.${ident(c.name)} is ${quote(c.comment)};`);
  for (const f of state.functions) if (f.comment) comments.push(`comment on function ${qualified(f.name)}(${f.types}) is ${quote(f.comment)};`);
  add('Mô tả schema.', comments.join('\n'));

  const jobs = [
    ['campaign_lifecycle_sync_job', '11 * * * *', 'select public.sync_campaign_lifecycle();'],
    ['auto_accept_reports_job', '*/30 * * * *', 'select public.auto_accept_expired_reports();'],
    ['daily_inactivity_penalty_job', '5 17 * * *', 'select public.apply_daily_inactivity_penalty();'],
    ['sync_expired_missions_job', '0 * * * *', 'select public.sync_expired_missions();'],
    ['purge_completed_campaigns_job', '15 3 * * *', 'select public.purge_completed_campaigns(90, false, 100);'],
  ];
  add('Cron: giữ nguyên job đang dùng; chỉ tạo lịch mặc định trên database mới nếu có pg_cron.', `
do $jobs$
begin
    if current_setting('crosstest.baseline_062_existing')='false'
       and exists(select 1 from pg_extension where extname='pg_cron') then
        ${jobs.map(j => `perform cron.schedule(${j.map(quote).join(',')});`).join('\n        ')}
    end if;
end;
$jobs$;
  `);

  const payload = chunks.join('\n');
  if (payload.includes('$baseline_payload$')) throw new Error('Dollar quote collision');
  const header = `-- 12 Tester | Migration 062 | Consolidated baseline 001-061 (65 legacy files)
-- Dành cho database đã chạy tới 061, cho phép chưa chạy 046; cũng hỗ trợ database mới.
-- Giữ dữ liệu, Credit, báo cáo, cấu hình, lịch sử và bộ đếm sequences trên database đang dùng.
-- Không chạy lại các đoạn reset streak, grace days hoặc backfill lịch sử.
-- Có đủ schema/functions của 046. Không đặt hoặc thay secret/URL Media Gateway.
-- Chạy TOÀN BỘ file trong Supabase SQL Editor bằng quyền postgres/database owner.
-- Sau khi thành công, migration tiếp theo là 063_*.sql. Không chạy lại file archive.
-- Rerun 062 sẽ bỏ qua toàn bộ payload, kể cả sau khi đã áp dụng 063+.
-- Source SHA-256 (normalized LF): ${digest}
-- Generated by: node supabase/tools/build-baseline.mjs
`;
  const sql = `${header}
begin;
set local lock_timeout = '15s';
set local search_path = pg_catalog, public, extensions;
select pg_advisory_xact_lock(12062026,62);

create schema if not exists crosstest_migrations;
revoke all on schema crosstest_migrations from public, anon, authenticated, service_role;
create table if not exists crosstest_migrations.baselines (
    version text primary key,
    source_sha256 text not null,
    applied_at timestamptz not null default clock_timestamp(),
    existing_database boolean not null
);
alter table crosstest_migrations.baselines enable row level security;
revoke all on crosstest_migrations.baselines from public, anon, authenticated, service_role;

do $baseline$
declare
    v_existing boolean := to_regclass('public.profiles') is not null;
    v_previous text;
begin
    select source_sha256 into v_previous from crosstest_migrations.baselines where version='062';
    if v_previous is not null then
        if v_previous <> ${quote(digest)} then
            raise exception 'BASELINE_062_SOURCE_CHANGED: create migration 063+ instead';
        end if;
        raise notice 'Baseline 062 da ap dung; bo qua, giu nguyen cac migrations tiep theo.';
        return;
    end if;
    perform set_config('crosstest.baseline_062_existing',v_existing::text,true);
    perform set_config('check_function_bodies','off',true);
    execute $baseline_payload$
${payload}
$baseline_payload$;
    insert into crosstest_migrations.baselines(version,source_sha256,existing_database)
    values ('062',${quote(digest)},v_existing);
    raise notice 'Baseline 062 thanh cong. Migration tiep theo: 063. Du lieu hien co duoc giu nguyen.';
end;
$baseline$;

notify pgrst,'reload schema';
commit;

-- Kết quả: phải có đúng một dòng version=062. Đây không phải lệnh sửa dữ liệu.
select version,applied_at,existing_database from crosstest_migrations.baselines where version='062';
`;
  const output = new URL('supabase/migrations/062_consolidated_baseline.sql', root);
  writeFileSync(output, sql);
  const auditDir = new URL('audit/migration-baseline-062-2026-10-30/', root);
  mkdirSync(auditDir, { recursive: true });
  writeFileSync(new URL('manifest.json', auditDir), JSON.stringify({
    version: '062', sourceSha256: digest, archive, sourceFiles: files.map(f => ({ name: f.name, sha256: createHash('sha256').update(f.sql).digest('hex') })),
    baselineBytes: Buffer.byteLength(sql), baselineLines: sql.split('\n').length,
    tables: state.tables.length, functions: state.functions.length, triggers: state.triggers.length,
    policies: state.policies.length, indexes: state.indexes.length, constraints: state.constraints.length,
  }, null, 2) + '\n');
  console.log(JSON.stringify({ file: output.pathname, bytes: Buffer.byteLength(sql), lines: sql.split('\n').length, tables: state.tables.length, functions: state.functions.length }));
} finally {
  await db.close();
}
