import assert from 'node:assert/strict';
import { createDb, read } from '../tools/baseline-lib.mjs';

const owner = '11111111-1111-1111-1111-111111111111';
const tester = '22222222-2222-2222-2222-222222222222';
const campaign = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const request = 'cccccccc-cccc-cccc-cccc-cccccccccccc';

for (const schema of ['extensions', 'public']) {
 for (const testerRole of ['admin', 'member']) {
  const db = await createDb();
  try {
    await db.exec(`create schema if not exists ${schema}; create extension pgcrypto with schema ${schema};`);
    await db.exec(read('supabase/migrations/062_consolidated_baseline.sql'));
    await db.exec(`
      insert into auth.users(id,email) values ('${owner}','owner@probe.test'),('${tester}','tester@probe.test');
      update public.profiles set credits=10000 where id='${owner}';
      update public.profiles set role='${testerRole}' where id='${tester}';
      insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number)
        values ('${campaign}','${owner}','com.probe.report','Report probe','open',1);
      select set_config('request.jwt.claim.sub','${tester}',false);
    `);
    const submit = async (content = 'Report probe') => {
      await db.exec('set role authenticated');
      try {
        return (await db.query(`select public.submit_tester_report(
          p_campaign_id => '${campaign}', p_content => $1,
          p_screenshot_url => 'https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F${campaign}%2F${tester}%2F${request}.jpg?alt=media&token=fixture',
          p_image_hash => '0123456789abcdef', p_client_submit_id => '${request}'
        ) as result`, [content])).rows[0].result;
      } finally { await db.exec('reset role'); }
    };
    if (schema === 'extensions') {
      await assert.rejects(submit(), e => e.code === '42883' && e.message.includes('digest'));
      console.log('REPRODUCED: baseline submit fails with 42883 digest when pgcrypto is in extensions');
    }
    if (process.argv.includes('--before-fix')) continue;
    const functionState = async () => (await db.query(`select proowner,proacl,prosrc from pg_proc
      where oid='public.submit_tester_report(uuid,text,text,integer,text,uuid,text,uuid)'::regprocedure`)).rows;
    const before = await functionState();
    await db.exec(read('supabase/migrations/063_report_crypto_search_path.sql'));
    assert.deepEqual(await functionState(), before, 'Owner, ACL and body are preserved');
    await db.exec(read('supabase/migrations/063_report_crypto_search_path.sql'));
    const result = await submit();
    assert.equal(result.success, true);
    const snapshot = (await db.query(`select credits from public.profiles where id='${tester}'`)).rows;
    assert.deepEqual(await submit(), result);
    assert.deepEqual((await db.query(`select credits from public.profiles where id='${tester}'`)).rows, snapshot);
    await assert.rejects(submit('Changed payload'), /CLIENT_SUBMIT_ID_PAYLOAD_MISMATCH/);
    assert.equal((await db.query('select count(*)::int as n from public.tester_reports')).rows[0].n, 1);
    console.log(`PASS: pgcrypto in ${schema}, ${testerRole}: submission, replay, credits, mismatch, ACL and migration rerun`);
  } finally { await db.close(); }
 }
}
