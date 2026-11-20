// Isolated supervisor database. Does not consume TEST_DATABASE_URL or old PG data.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync,mkdirSync,readFileSync,readdirSync,writeFileSync,openSync,closeSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve,join} from 'node:path';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';
const audit=fileURLToPath(new URL('./',import.meta.url));
const root=resolve(audit,'../..'),task=resolve(audit,'supervisor-native'),data=resolve(task,'pg-data');
assert.ok(data.toLowerCase().startsWith(resolve(root,'audit/daily-task-action-gate-2026-11-12').toLowerCase()+'\\'));
const bin=resolve(root,'audit/tester-bonus-2026-11-11/pg-runtime/node_modules/@embedded-postgres/windows-x64/native/bin');
export const options={host:'127.0.0.1',port:55449,user:'action_gate_supervisor',database:'action_gate_supervisor',connectionTimeoutMillis:5000,statement_timeout:20000};
export async function connection(database=options.database){
  const c=new pg.Client({...options,database});await c.connect();
  try{const row=(await c.query("select current_setting('data_directory') directory")).rows[0];
    assert.equal(row.directory.replaceAll('\\','/').toLowerCase(),data.replaceAll('\\','/').toLowerCase(),'Refusing any foreign cluster');return c;
  }catch(e){await c.end();throw e;}
}
function command(name,args){
  mkdirSync(task,{recursive:true});const log=join(task,name+'.log');const fd=openSync(log,'a');
  try{execFileSync(join(bin,name+'.exe'),args,{cwd:root,windowsHide:true,stdio:['ignore',fd,fd],timeout:40000});return readFileSync(log,'utf8');}
  finally{closeSync(fd);}
}
export async function stop(){const c=await connection('postgres');await c.end();command('pg_ctl',['-D',data,'-m','fast','-w','-t','25','stop']);}
export async function start(){
  if(!existsSync(join(data,'PG_VERSION'))){
    command('initdb',['-D',data,'-U',options.user,'--auth=trust','--encoding=UTF8','--no-locale']);
    writeFileSync(join(data,'postgresql.auto.conf'),"listen_addresses='127.0.0.1'\nport=55449\nmax_connections=32\nshared_buffers='64MB'\ntimezone='UTC'\n");
  }
  let active;try{active=await connection('postgres');}catch(e){if(!['ECONNREFUSED','57P03'].includes(e.code))throw e;}
  if(active)await active.end();else command('pg_ctl',['-D',data,'-l',join(task,'server.log'),'-w','-t','25','start']);
  const admin=await connection('postgres');
  try{if(!(await admin.query('select 1 from pg_database where datname=$1',[options.database])).rowCount)await admin.query('create database '+options.database);}
  finally{await admin.end();}
  const db=await connection();
  try{
    if(!(await db.query("select to_regclass('auth.users') users")).rows[0].users){
      await db.query(`create extension pgcrypto;create extension "uuid-ossp";
        create role anon nologin;create role authenticated nologin;create role service_role nologin bypassrls;
        create schema auth;create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',email_confirmed_at timestamptz,
          confirmation_token text,email_change text,email_change_token_new text,recovery_token text);
        create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
        grant usage on schema public,auth to anon,authenticated,service_role;grant execute on function auth.uid() to anon,authenticated,service_role;
        create table public.supervisor_applied_migrations(name text primary key,applied_at timestamptz default clock_timestamp());`);
    }
    const directory=new URL('../../supabase/migrations/',import.meta.url);
    const migrations=readdirSync(directory).filter(n=>/^\d{3}_.*\.sql$/.test(n)).sort();
    for(const m of migrations){
      if((await db.query('select 1 from public.supervisor_applied_migrations where name=$1',[m])).rowCount)continue;
      await db.query('begin');
      try{await db.query(readFileSync(new URL(m,directory),'utf8'));await db.query('insert into public.supervisor_applied_migrations(name) values($1)',[m]);await db.query('commit');console.log('LOADED',m);}
      catch(e){await db.query('rollback');throw e;}
    }
    const info=(await db.query("select current_setting('data_directory') directory,version() version,current_setting('timezone') timezone")).rows[0];
    writeFileSync(join(task,'ready.json'),JSON.stringify({...info,...options,checkedAt:new Date().toISOString()},null,2));
    console.log('READY',JSON.stringify(info));
  }finally{await db.end();}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  if(process.argv.includes('--stop'))await stop();else await start();
}
