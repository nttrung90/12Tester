import {freshNative} from './fresh-native.mjs';
for (const [tag,file] of [
  ['bonus','run-campaign-tester-bonus-concurrency-test.mjs'],
  ['resume','run-owner-auto-pause-concurrency-test.mjs'],
]) {
  const name='immediate_quota_native_'+tag+'_'+Date.now();
  const db=await freshNative(name);await db.end();
  process.env.TEST_DATABASE_URL='postgresql://quota_test_admin@127.0.0.1:55446/'+name;
  console.log('Fenced fresh native regression',tag,name);
  await import('../../supabase/tests/'+file);
}
