// Supervisor-owned fresh, fenced, disposable local databases.
import assert from 'node:assert/strict';
import {readFileSync,readdirSync} from 'node:fs';
import {connect} from './independent-fixture.mjs';
export async function freshNative(name) {
  assert.match(name,/^immediate_quota_native_[a-z0-9_]+$/);
  const admin=await connect();
  try {
    assert.equal((await admin.query('select 1 from pg_database where datname=$1',[name])).rowCount,0,'Use a new isolated database name');
    await admin.query('create database '+name);
  } finally {await admin.end();}
  const db=await connect(name);
  try {
    await db.query(`create extension pgcrypto;create extension "uuid-ossp";
      create schema auth;
      create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',email_confirmed_at timestamptz,
        confirmation_token text,email_change text,email_change_token_new text,recovery_token text);
      create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
      grant usage on schema public,auth to anon,authenticated,service_role;grant execute on function auth.uid() to anon,authenticated,service_role;`);
    const dir=new URL('../../supabase/migrations/',import.meta.url);
    const files=readdirSync(dir).filter(n=>/^\d{3}_.*\.sql$/.test(n)&&Number(n.slice(0,3))<=88).sort();
    for(const f of files)await db.query(readFileSync(new URL(f,dir),'utf8'));
    return db;
  } catch(e) {await db.end();throw e;}
}
