// Supervisor-owned disposable native PG cluster; never consumes an external DB URL.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync,mkdirSync,readFileSync,readdirSync,writeFileSync,openSync,closeSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve,join} from 'node:path';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
const task=fileURLToPath(new URL('./',import.meta.url));
const workspace=resolve(task,'../..');
const data=resolve(task,'pg-data');
assert.ok(data.toLowerCase().startsWith(resolve(workspace,'audit/immediate-daily-quota-2026-11-12').toLowerCase()+ '\\'));
const bin=resolve(workspace,'audit/tester-bonus-2026-11-11/pg-runtime/node_modules/@embedded-postgres/windows-x64/native/bin');
const ctl=join(bin,'pg_ctl.exe');
const options={host:'127.0.0.1',port:55446,user:'quota_test_admin',database:'postgres',connectionTimeoutMillis:5000,statement_timeout:60000};
function command(name,args){
  const log=join(task,name.replace('.exe','')+'-execution.log');const fd=openSync(log,'a');
  try {execFileSync(join(bin,name),args,{cwd:workspace,windowsHide:true,stdio:['ignore',fd,fd],timeout:45000});return readFileSync(log,'utf8');}
  finally {closeSync(fd);}
}
async function verifiedConnection(database='postgres') {
  const client=new pg.Client({...options,database});await client.connect();
  const row=(await client.query("select current_setting('data_directory') directory,version() version")).rows[0];
  assert.equal(row.directory.replaceAll('\\','/').toLowerCase(),data.replaceAll('\\','/').toLowerCase(),'Refusing to use any cluster outside this task directory');
  return client;
}
if(process.argv.includes('--stop')) {
  const c=await verifiedConnection();await c.end();
  console.log(command('pg_ctl.exe',['-D',data,'-m','fast','-w','-t','25','stop']));
  console.log('Stopped only the verified task-local PostgreSQL cluster.');
} else {
  if(!existsSync(join(data,'PG_VERSION'))) {
    mkdirSync(task,{recursive:true});
    const output=command('initdb.exe',['-D',data,'-U','quota_test_admin','--auth=trust','--encoding=UTF8','--no-locale']);
    writeFileSync(join(task,'postgres-init.log'),output);
    writeFileSync(join(data,'postgresql.auto.conf'),"listen_addresses='127.0.0.1'\nport=55446\nmax_connections=32\nshared_buffers='64MB'\ntimezone='UTC'\n");
  }
  let running;
  try {running=await verifiedConnection();} catch(e) {if(!['ECONNREFUSED','57P03'].includes(e.code))throw e;}
  if(running) await running.end();
  else console.log(command('pg_ctl.exe',['-D',data,'-l',join(task,'postgres-server.log'),'-w','-t','25','start']));
  const admin=await verifiedConnection();
  try {
    const found=await admin.query("select 1 from pg_database where datname='immediate_quota_test'");
    if(!found.rowCount) await admin.query('create database immediate_quota_test');
  } finally {await admin.end();}
  const db=await verifiedConnection('immediate_quota_test');
  try {
    const existing=(await db.query("select to_regclass('auth.users') users")).rows[0].users;
    if(!existing) {
      await db.query(`create extension if not exists pgcrypto;create extension if not exists "uuid-ossp";
        do $$ begin
          if not exists(select 1 from pg_roles where rolname='anon') then create role anon nologin;end if;
          if not exists(select 1 from pg_roles where rolname='authenticated') then create role authenticated nologin;end if;
          if not exists(select 1 from pg_roles where rolname='service_role') then create role service_role nologin bypassrls;end if;
        end $$;
        create schema auth;
        create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',email_confirmed_at timestamptz,
          confirmation_token text,email_change text,email_change_token_new text,recovery_token text);
        create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
        grant usage on schema public,auth to anon,authenticated,service_role;grant execute on function auth.uid() to anon,authenticated,service_role;`);
      const dir=new URL('../../supabase/migrations/',import.meta.url);
      const files=readdirSync(dir).filter(n=>/^\d{3}_.*\.sql$/.test(n)&&Number(n.slice(0,3))<=87).sort();
      for(const file of files) {await db.query(readFileSync(new URL(file,dir),'utf8'));console.log('LOADED',file);}
    }
    const info=(await db.query("select current_setting('data_directory') directory,version() version,current_setting('timezone') timezone")).rows[0];
    writeFileSync(join(task,'native-ready.json'),JSON.stringify({...info,connection:'postgresql://quota_test_admin@127.0.0.1:55446/immediate_quota_test',checkedAt:new Date().toISOString()},null,2)+'\n');
    console.log('Ready:',info.version,'timezone='+info.timezone);
    console.log('TEST_DATABASE_URL=postgresql://quota_test_admin@127.0.0.1:55446/immediate_quota_test');
  } finally {await db.end();}
}
