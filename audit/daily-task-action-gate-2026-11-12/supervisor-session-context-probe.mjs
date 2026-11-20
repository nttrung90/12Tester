import {createDb} from '../../supabase/tools/baseline-lib.mjs';
const d=await createDb();
const q=async label=>console.log(label,JSON.stringify((await d.query("select current_user,session_user,current_setting('role') role")).rows));
try{
  await q('initial');
  await d.exec('create role probe_auth noinherit;grant authenticated to probe_auth');
  await d.exec('begin;set local session authorization probe_auth;set local role authenticated');
  await q('actor');
  await d.exec('reset session authorization;reset role');await q('reset');
  await d.exec('set session authorization postgres;set role postgres');await q('explicit');
  await d.exec('rollback');await q('rollback');
}catch(e){console.log('PROBE ERROR',e.message);process.exitCode=1;}finally{await d.close();}
