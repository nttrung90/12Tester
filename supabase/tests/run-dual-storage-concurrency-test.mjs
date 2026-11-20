// Run only against an empty, disposable database already migrated through 093.
// No migration/reset, physical Storage calls, or production fixture cleanup.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const url = process.env.TEST_DATABASE_URL;

export async function runDualStorageConcurrencyTest() {
  if (!url) {
    console.log('[NOT RUN] PostgreSQL multi-connection: TEST_DATABASE_URL is missing.');
    return { status: 'NOT_RUN', reason: 'TEST_DATABASE_URL is missing' };
  }

  const target = new URL(url);
  if (!/^12tester_test_[a-z0-9_]+$/i.test(target.pathname.slice(1)) || process.env.DUAL_STORAGE_TEST_DISPOSABLE !== 'yes') {
    throw new Error('Requires disposable database named 12tester_test_* and DUAL_STORAGE_TEST_DISPOSABLE=yes.');
  }

  const { Pool } = await import('pg');
  const pool = new Pool({ connectionString: url, max: 8, connectionTimeoutMillis: 5000, statement_timeout: 10000 });
  const owner = randomUUID(), user = randomUUID(), camp = randomUUID();
  let prepared = false, usage, hosts;

  const reserve = async (id, size = 100, thumb = false) => {
    const c = await pool.connect();
    try {
      await c.query('begin');
      await c.query("select set_config('request.jwt.claim.sub', $1, true)", [user]);
      const r = (await c.query('select public.request_report_media_reservation($1, $2, $3, $4) r', [camp, id, size, thumb])).rows[0].r;
      await c.query('commit');
      return r;
    } catch (e) {
      await c.query('rollback');
      throw e;
    } finally {
      c.release();
    }
  };

  try {
    const counts = (await pool.query('select (select count(*) from public.profiles)::int users,(select count(*) from storage.objects)::int objects,(select count(*) from public.report_media_uploads)::int media')).rows[0];
    assert.deepEqual(counts, { users: 0, objects: 0, media: 0 }, 'Database must be empty of user/media data.');

    // Verify migration 093 required columns exist
    const cols = (await pool.query("select column_name from information_schema.columns where table_schema='public' and table_name='report_media_uploads' and column_name in ('main_committed','thumbnail_committed')")).rows;
    assert.equal(cols.length, 2, 'Migration 093 columns main_committed and thumbnail_committed must exist.');

    usage = (await pool.query('select * from public.storage_organization_usage where id=1')).rows[0];
    hosts = (await pool.query('select host from public.storage_trusted_hosts')).rows;
    prepared = true;

    await pool.query("insert into auth.users(id,email) values($1,'owner@concurrency.invalid'),($2,'tester@concurrency.invalid')", [owner, user]);
    await pool.query("insert into public.campaigns(id,owner_id,app_name,package_name,status) values($1,$2,'Concurrency','test.concurrency','open')", [camp, owner]);
    await pool.query('insert into public.campaign_members(campaign_id,user_id) values($1,$2)', [camp, user]);
    await pool.query("insert into public.storage_trusted_hosts(host) values('test.invalid') on conflict do nothing");

    // Phase A: Initial small capacity tests (B = 1000, 95% = 950)
    await pool.query("update public.storage_organization_usage set local_project_ref='aaaaaaaaaaaaaaaaaaaa',total_capacity_bytes=1000,used_storage_bytes=800,active_reserved_bytes=0,is_org_metric_valid=true,last_reconciled_at=now(),dispatch_mode='supabase_preferred' where id=1");

    // 1. Concurrent identical reservations coalesce to single reservation
    const same = await Promise.all(Array.from({ length: 5 }, () => reserve('same', 100, false)));
    assert.ok(same.every(r => r.success && r.reservation_id === same[0].reservation_id && r.object_path === same[0].object_path));
    assert.equal(Number((await pool.query('select active_reserved_bytes n from public.storage_organization_usage')).rows[0].n), 100);
    console.log('PASS: five real connections preserve a single reservation and hold.');

    // Clean up identical reservation fixture so it does not leak uncommitted hold into subsequent tests:
    await pool.query("delete from public.report_media_uploads where campaign_id=$1 and user_id=$2 and upload_id='same'", [camp, user]);
    await pool.query("update public.storage_organization_usage set active_reserved_bytes=0 where id=1");

    // 2. Concurrent distinct reservations cannot oversubscribe threshold
    const distinct = await Promise.all(Array.from({ length: 5 }, (_, i) => reserve('threshold-' + i, 100, false)));
    assert.equal(distinct.filter(r => r.provider === 'supabase').length, 1, 'Only one 100 byte hold fits below 950.');
    assert.equal(Number((await pool.query('select active_reserved_bytes n from public.storage_organization_usage')).rows[0].n), 100);
    console.log('PASS: concurrent distinct reservations cannot oversubscribe the threshold.');

    // 3. Observed physical deletion & idempotent acknowledgement
    const targetUpload = (await pool.query("select id, object_path from public.report_media_uploads where provider='supabase' and status<>'deleted'")).rows[0];
    await pool.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"size\":100}')", [targetUpload.object_path, user]);
    await pool.query("update public.report_media_uploads set status='expired' where id=$1", [targetUpload.id]);

    const claims = await Promise.all([1, 2].map(() => pool.query('select public.get_expired_report_media_for_cleanup(1,300,false) r')));
    const ids = claims.flatMap(r => r.rows[0].r.map(m => m.id));
    assert.equal(ids.length, 1, 'Fixture has exactly one eligible Supabase object pair.');
    assert.equal(new Set(ids).size, ids.length);

    const job = claims.flatMap(r => r.rows[0].r)[0];
    const paths = [job.object_path, job.thumbnail_object_path].filter(Boolean);

    // Physical deletion performed by worker:
    await pool.query("delete from storage.objects where bucket_id='report-screenshots' and name=$1", [targetUpload.object_path]);

    const acks = await Promise.all([1, 2].map(() => pool.query('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r', [job.id, job.lease_token, paths])));
    assert.ok(acks.every(r => r.rows[0].r.success));
    assert.equal(acks.filter(r => r.rows[0].r.already_deleted).length, 1);

    const after = (await pool.query('select used_storage_bytes::int u,active_reserved_bytes::int r from public.storage_organization_usage')).rows[0];
    assert.deepEqual(after, { u: 800, r: 0 }, 'Measured U unchanged, R released to 0 after verified physical deletion.');
    console.log('PASS: simultaneous workers claim once and duplicate acknowledgement is idempotent.');

    // Clean up Phase A media records completely for Phase B independence:
    await pool.query("delete from public.report_media_uploads where campaign_id=$1", [camp]);
    await pool.query("update public.storage_organization_usage set active_reserved_bytes=0 where id=1");

    // Phase B: Real R02 Case A (Main committed, thumbnail permission probe rollback -> concurrent application finalize / reconcile -> privileged thumb commit)
    // B = 500,000 bytes so that thumbnail expected capacity (150,000) fits comfortably below threshold.
    await pool.query("update public.storage_organization_usage set total_capacity_bytes=500000,used_storage_bytes=800,active_reserved_bytes=0 where id=1");
    const inFlight = await reserve('r02-pair', 100, true);
    assert.equal(inFlight.provider, 'supabase');
    assert.ok(inFlight.thumbnail_object_path, 'Pair reservation must include thumbnail object path.');

    // Main object commits first:
    await pool.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"size\":100}')", [inFlight.object_path, user]);

    const first = await pool.connect(), second = await pool.connect();
    try {
      // 1. Model Storage permission probe inside savepoint and rollback on `first`:
      await first.query('begin');
      await first.query("select set_config('request.jwt.claim.sub',$1,true)", [user]);
      await first.query('savepoint storage_permission_probe');
      const probeOk = (await first.query("select public._can_insert_report_object('report-screenshots',$1,'{\"contentLength\":50,\"mimetype\":\"image/jpeg\"}') ok", [inFlight.thumbnail_object_path])).rows[0].ok;
      assert.equal(probeOk, true);
      await first.query('rollback to savepoint storage_permission_probe');

      // CRITICAL: `first` transaction remains OPEN! Row lock was released by savepoint rollback.

      // 2. Application finalize on `second`: set user JWT, prepare with trusted URL, finalize with null report/attachment, aggregate R:
      await second.query('begin');
      await second.query("select set_config('request.jwt.claim.sub',$1,true)", [user]);
      const mediaUrl = 'https://test.invalid/storage/v1/object/authenticated/report-screenshots/' + inFlight.object_path;
      const prepData = (await second.query('select public._prepare_report_media($1,null,$2,$3,null) r', [mediaUrl, camp, user])).rows[0].r;
      await second.query('select public._finalize_report_media($1::jsonb,null,null)', [JSON.stringify(prepData)]);
      await second.query("update public.storage_organization_usage set active_reserved_bytes=(select coalesce(sum(reserved_bytes),0) from public.report_media_uploads where provider='supabase' and reserved_bytes>0), updated_at=now() where id=1");
      await second.query('commit');

      // 3. Interleaved reconcile runs on `second` concurrently and finishes WITHOUT blocking:
      await second.query('begin');
      const metric = JSON.stringify([{ project_ref: 'aaaaaaaaaaaaaaaaaaaa', bytes: '100', status: 'SUCCESS', observed_at: new Date().toISOString() }]);
      const recResult = (await second.query('select public.reconcile_storage_organization_usage(500000,100,$1::jsonb,true) r', [metric])).rows[0].r;
      assert.equal(recResult.is_org_metric_valid, true);
      await second.query('commit');

      // Conservative accounting: thumbnail is pending, hold remains active
      const midState = (await pool.query('select used_storage_bytes::int u,active_reserved_bytes::int r from public.storage_organization_usage where id=1')).rows[0];
      assert.ok(midState.r >= 150000, 'Thumbnail hold must be preserved while thumb is in-flight: ' + JSON.stringify(midState));
      assert.ok(midState.u + midState.r >= 150, 'U + R covers expected total live bytes');

      // 4. Privileged commit writes actual thumbnail metadata on `first` and commits:
      await first.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"size\":50}')", [inFlight.thumbnail_object_path, user]);
      await first.query('commit');

      // 5. Conservative accounting guarantees U+R covers actual live bytes even without lock held across rollback:
      const measured = (await pool.query('select used_storage_bytes::int u,active_reserved_bytes::int r,(select sum((metadata->>\'size\')::int) from storage.objects where name in ($1,$2))::int actual from public.storage_organization_usage where id=1', [inFlight.object_path, inFlight.thumbnail_object_path])).rows[0];
      assert.ok(measured.u + measured.r >= measured.actual, 'Conservative hold must cover privileged commit after probe rollback: ' + JSON.stringify(measured));

      // 6. Subsequent reconcile accounts both committed objects and clears hold:
      const ack = (await pool.query('select public.reconcile_storage_organization_usage(500000,150,$1::jsonb,true) r', [JSON.stringify([{ project_ref: 'aaaaaaaaaaaaaaaaaaaa', bytes: '150', status: 'SUCCESS', observed_at: new Date().toISOString() }])])).rows[0].r;
      assert.equal(ack.is_org_metric_valid, true);
      const finalMeasured = (await pool.query('select used_storage_bytes::int u,active_reserved_bytes::int r from public.storage_organization_usage where id=1')).rows[0];
      assert.equal(finalMeasured.u, 150);
      assert.equal(finalMeasured.r, 0);

      // Clean up pair fixture objects and reconcile clean for Phase C (keep U=0, R=0 snapshot, do NOT reset artificial 800 bytes):
      await pool.query("delete from storage.objects where bucket_id='report-screenshots' and name in ($1,$2)", [inFlight.object_path, inFlight.thumbnail_object_path]);
      await pool.query("delete from public.report_media_uploads where id=$1", [inFlight.reservation_id]);
      await pool.query('select public.reconcile_storage_organization_usage(500000,0,$1::jsonb,true)', [JSON.stringify([{ project_ref: 'aaaaaaaaaaaaaaaaaaaa', bytes: '0', status: 'SUCCESS', observed_at: new Date().toISOString() }])]);
      console.log('PASS: Case A (main committed, thumb permission probe rollback -> concurrent application finalize/reconcile -> privileged thumb commit) preserves accounting without holding RLS lock.');
    } finally {
      await first.query('rollback').catch(() => {});
      await second.query('rollback').catch(() => {});
      first.release();
      second.release();
    }

    // Phase C: Real R02 Case B (Cleanup absence ACK => late privileged commit)
    const lateRes = await reserve('r02-absence-late', 100, false);
    assert.equal(lateRes.provider, 'supabase');

    const probeTx = await pool.connect(), workerTx = await pool.connect();
    try {
      // 1. Model permission probe for late upload BEFORE expiry (policy requires reserved/uploaded):
      await probeTx.query('begin');
      await probeTx.query("select set_config('request.jwt.claim.sub',$1,true)", [user]);
      await probeTx.query('savepoint storage_probe');
      assert.equal((await probeTx.query("select public._can_insert_report_object('report-screenshots',$1,'{\"contentLength\":100,\"mimetype\":\"image/jpeg\"}') ok", [lateRes.object_path])).rows[0].ok, true);
      await probeTx.query('rollback to savepoint storage_probe');

      // Keep probeTx OPEN! Now expire the reservation AFTER rollback savepoint:
      await pool.query("update public.report_media_uploads set status='expired' where id=$1", [lateRes.reservation_id]);

      // 2. Worker claims expired media and executes absence ACK (0 files in storage):
      await workerTx.query('begin');
      const claimJob = (await workerTx.query('select public.get_expired_report_media_for_cleanup(1,300,false) r')).rows[0].r[0];
      assert.equal(claimJob.id, lateRes.reservation_id);
      const ackRes = (await workerTx.query('select public.record_report_media_deleted($1::uuid,$2::uuid,$3::text[]) r', [claimJob.id, claimJob.lease_token, [lateRes.object_path]])).rows[0].r;
      assert.equal(ackRes.success, true);
      await workerTx.query('commit');

      // 093 monotonic rule: uncommitted path must retain conservative hold even after absence ACK:
      const holdAfterAck = (await pool.query('select reserved_bytes from public.report_media_uploads where id=$1', [lateRes.reservation_id])).rows[0].reserved_bytes;
      assert.equal(Number(holdAfterAck), 100, 'Conservative hold must NOT be cleared on absence ACK when file was never committed');

      // 3. Late privileged commit lands on probeTx:
      await probeTx.query("insert into storage.objects(bucket_id,name,owner,metadata) values('report-screenshots',$1,$2,'{\"size\":100}')", [lateRes.object_path, user]);
      await probeTx.query('commit');

      // Check coverage immediately after late INSERT, BEFORE reconcile:
      const preRec = (await pool.query('select used_storage_bytes::int u,active_reserved_bytes::int r,(select sum((metadata->>\'size\')::int) from storage.objects where name=$1)::int actual from public.storage_organization_usage where id=1', [lateRes.object_path])).rows[0];
      assert.ok(preRec.u + preRec.r >= preRec.actual, 'Conservative hold must cover late committed bytes before reconcile: ' + JSON.stringify(preRec));

      // 4. Reconcile verifies U+R covers late committed object:
      const recAbsence = (await pool.query('select public.reconcile_storage_organization_usage(500000,100,$1::jsonb,true) r', [JSON.stringify([{ project_ref: 'aaaaaaaaaaaaaaaaaaaa', bytes: '100', status: 'SUCCESS', observed_at: new Date().toISOString() }])])).rows[0].r;
      assert.equal(recAbsence.is_org_metric_valid, true);

      const checkLate = (await pool.query('select used_storage_bytes::int u,active_reserved_bytes::int r,(select sum((metadata->>\'size\')::int) from storage.objects where name=$1)::int actual from public.storage_organization_usage where id=1', [lateRes.object_path])).rows[0];
      assert.ok(checkLate.u + checkLate.r >= checkLate.actual, 'U + R must cover resurrected late write after absence ACK and reconcile: ' + JSON.stringify(checkLate));

      // Clean up late object:
      await pool.query("delete from storage.objects where bucket_id='report-screenshots' and name=$1", [lateRes.object_path]);
      console.log('PASS: Case B (cleanup absence ACK => late privileged commit) preserves conservative hold and prevents quota undercount.');
    } finally {
      await probeTx.query('rollback').catch(() => {});
      await workerTx.query('rollback').catch(() => {});
      probeTx.release();
      workerTx.release();
    }

    return { status: 'PASS' };
  } finally {
    if (prepared) {
      // Target is explicitly disposable; remove only this run's records, restore control config.
      await pool.query("delete from storage.objects where bucket_id='report-screenshots' and name like $1", ['reports/' + camp + '/' + user + '/%']);
      await pool.query('delete from public.report_media_uploads where campaign_id=$1', [camp]);
      await pool.query('delete from public.campaigns where id=$1', [camp]);
      for (const table of ['credit_logs', 'notifications', 'user_daily_tasks', 'daily_test_events_journal', 'user_role_journal']) {
        await pool.query('delete from public.' + table + ' where user_id=any($1::uuid[])', [[owner, user]]);
      }
      await pool.query('delete from public.profiles where id=any($1::uuid[])', [[owner, user]]);
      await pool.query('delete from auth.users where id=any($1::uuid[])', [[owner, user]]);
      // Re-delete user_role_journal after deleting profiles/auth.users to clean up journals created by delete triggers:
      await pool.query('delete from public.user_role_journal where user_id=any($1::uuid[])', [[owner, user]]);
      await pool.query('delete from public.storage_trusted_hosts');
      for (const h of hosts) {
        await pool.query('insert into public.storage_trusted_hosts(host) values($1)', [h.host]);
      }
      const columns = ['total_capacity_bytes', 'used_storage_bytes', 'active_reserved_bytes', 'dispatch_mode', 'last_reconciled_at', 'measured_projects', 'is_org_metric_valid', 'updated_at', 'local_project_ref'];
      await pool.query('update public.storage_organization_usage set ' + columns.map((k, i) => k + '=$' + (i + 1)).join(',') + ' where id=1', columns.map(k => k === 'measured_projects' ? JSON.stringify(usage[k]) : usage[k]));
    }
    await pool.end();
  }
}

if (!url) {
  console.log('[NOT RUN] PostgreSQL multi-connection: TEST_DATABASE_URL is missing.');
} else {
  await runDualStorageConcurrencyTest();
}
