// Independent review probes. Invoke only inside the foreground task-local PG driver,
// after migrations. No server start, environment URL, production access or schema reset.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import pg from '../../supabase/tests/node_modules/pg/lib/index.js';

const expectedDirectory='f:/ai/12tester/audit/daily-task-action-gate-2026-11-12/pg-data';
const day="(clock_timestamp() at time zone 'Asia/Ho_Chi_Minh')::date";
const barrierNamespace=901114;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const normalize=s=>s.replaceAll('\\','/').toLowerCase();
const safeError=e=>({code:e.code??null,message:e.message});
const settle=promise=>promise.then(value=>({ok:true,rows:value.rows}),error=>({ok:false,error:safeError(error)}));

export async function runNativeReviewProbes(config){
  assert.equal(config.host,'127.0.0.1');
  assert.equal(config.port,55444);
  assert.match(config.database,/^action_gate_native_test(?:_[a-z0-9_]+)?$/i);
  const clients=[];
  async function connection(){
    const client=new pg.Client({...config,connectionTimeoutMillis:4000,statement_timeout:12000,query_timeout:16000});
    client.on('error',()=>{}); // Query errors are recorded/asserted below; close errors cannot fabricate PASS.
    await client.connect();clients.push(client);
    const dir=(await client.query('show data_directory')).rows[0].data_directory;
    assert.equal(normalize(dir),expectedDirectory,'Refusing a cluster outside this task');
    return client;
  }
  const root=await connection(), a=await connection(), b=await connection();
  const evidence={at:new Date().toISOString(),dataDirectory:expectedDirectory,database:config.database,
    version:(await root.query('select version() v')).rows[0].v,pids:{root:root.processID,a:a.processID,b:b.processID},cases:[]};
  const one=async(sql,args=[])=>(await root.query(sql,args)).rows[0];
  const status=async uid=>(await one(`select public.get_daily_task_status($1,${day}) s`,[uid])).s;
  const actor=async(client,uid)=>{
    await client.query('begin');
    await client.query("select set_config('request.jwt.claim.sub',$1,true)",[uid]);
    await client.query('set local role authenticated');
  };
  const finish=async(client,outcome)=>{await client.query(outcome.ok?'commit':'rollback');return outcome;};
  let acceptedRemoval=null;
  async function waitForBlock(waiter,blocker){
    const deadline=Date.now()+3500;
    while(Date.now()<deadline){
      const s=await one(`select pid,wait_event_type,wait_event,pg_blocking_pids(pid) blockers
        from pg_stat_activity where pid=$1`,[waiter.processID]);
      if(s?.blockers?.includes(blocker.processID))return s;
      await delay(20);
    }
    throw new Error(`Expected backend ${waiter.processID} to wait for ${blocker.processID}`);
  }
  async function installBarrier(id,key,{remove=false,closed=false}={}){
    // "aaa" fires BEFORE 084's slot guard. Session advisory locks expose the barrier
    // to the observer without relying on another transaction reading uncommitted rows.
    await root.query(`create or replace function public._supervisor_r4_campaign_barrier()
      returns trigger language plpgsql as $body$ begin
        if ${remove?'old':'new'}.id='${id}'::uuid ${closed?"and new.status='closed' and new.paused_reason='owner_deletion_pending'":''} then
          perform pg_advisory_xact_lock(${barrierNamespace},${key});
        end if;
        return ${remove?'old':'new'};
      end $body$`);
    await root.query('drop trigger if exists aaa_supervisor_r4_campaign_barrier on public.campaigns');
    await root.query(`create trigger aaa_supervisor_r4_campaign_barrier before ${remove?'delete':'update'} on public.campaigns
      for each row execute function public._supervisor_r4_campaign_barrier()`);
    await root.query('select pg_advisory_lock($1,$2)',[barrierNamespace,key]);
  }
  async function release(key){await root.query('select pg_advisory_unlock($1,$2)',[barrierNamespace,key]);}
  async function fixture(){
    const uid=randomUUID(),suppliers=[randomUUID(),randomUUID()],camps=[randomUUID(),randomUUID()];
    for(const id of [uid,...suppliers]){
      await root.query('insert into auth.users(id,email) values($1,$2)',[id,`${id}@native-review.invalid`]);
      await root.query(`update public.profiles set role='member',credits=10000,created_at=clock_timestamp()-interval '90 days',
        last_login_date=${day} where id=$1`,[id]);
      await root.query(`insert into public.user_slots(user_id,slot_number,expires_at)
        select $1,n,clock_timestamp()+interval '30 days' from generate_series(2,6)n`,[id]);
    }
    for(let i=0;i<2;i++)await root.query(`insert into public.campaigns(id,owner_id,package_name,app_name,status,slot_number,created_at)
      values($1,$2,$3,'Native reviewer app','open',$4,clock_timestamp()-interval '2 days')`,
      [camps[i],uid,`review.native.p${camps[i].replaceAll('-','')}`,i+1]);
    for(const supplier of suppliers)for(let slot=1;slot<=6;slot++)await root.query(`insert into public.campaigns(owner_id,package_name,app_name,status,slot_number)
      values($1,$2,'Native reviewer supply','open',$3)`,[supplier,`review.supply.p${supplier.replaceAll('-','')}.s${slot}`,slot]);
    await root.query(`insert into public.user_daily_tasks(user_id,task_date,mode,active_app_count,base_required_tests,required_tests,
      effective_required_tests,available_apps,completed_tests,logged_in,is_exempt,met,missed_streak,carry_in_tests,carry_out_tests,finalized_at)
      values($1,${day}-1,'owner',2,6,6,6,12,6,true,false,true,0,0,0,clock_timestamp())`,[uid]);
    await root.query(`insert into public.daily_test_events_journal(source_type,source_id,user_id,campaign_id,campaign_owner_id,
      activity_date,status,is_qualifying,submission_context,reconciliation_status,observed_at)
      select 'checkin',gen_random_uuid(),$1,c.id,c.owner_id,${day},'valid',true,'checkin','verified_checkin',clock_timestamp()
      from public.campaigns c where c.owner_id=any($2::uuid[]) order by c.id limit 6`,[uid,suppliers]);
    const initial=await status(uid);
    assert.equal(initial.history_ready,true);assert.equal(initial.carryover_ready,true);
    assert.equal(initial.carryover_remaining_tests,0);assert.equal(initial.met,true);
    assert.equal(initial.active_app_count,2);assert.equal(initial.base_required_tests,6);assert.equal(initial.completed_tests,6);
    return {uid,suppliers,camps,initial};
  }
  async function prepareCreate(uid){
    await actor(b,uid);
    const quote=(await b.query(`select public.get_business_quote('campaign_create','{"slot_number":3,"target_testers":12}'::jsonb) q`)).rows[0].q;
    assert.ok(quote.quote_token);assert.ok(Number.isInteger(quote.total_cost));
    return {quote,id:randomUUID(),pkg:`review.created.p${randomUUID().replaceAll('-','')}`};
  }
  function create(p){return b.query(`select public.create_campaign_v2(p_app_name=>'Native concurrent create',p_package_name=>$1,
    p_slot_number=>3,p_target_testers=>12,p_client_request_id=>$2,p_expected_total_cost=>$3,p_expected_min_credits=>$4,p_expected_revision=>$5) r`,
    [p.pkg,p.id,p.quote.total_cost,p.quote.min_credits_to_post_app,p.quote.quote_token]);}
  async function scenario(name,key,run){
    const item={name,pass:false};evidence.cases.push(item);
    try{Object.assign(item,await run());item.pass=true;}
    catch(error){item.error=safeError(error);}
    finally{
      await release(key).catch(()=>{});
      // Only cancel our two verified, task-local sessions if cleanup finds an unfinished query.
      for(const client of [a,b]){
        await root.query("select pg_cancel_backend(pid) from pg_stat_activity where pid=$1 and state='active'",[client.processID]).catch(()=>{});
        await client.query('rollback').catch(()=>{});
      }
      await root.query('drop trigger if exists aaa_supervisor_r4_campaign_barrier on public.campaigns');
    }
    console.log(JSON.stringify(item));
  }
  try{
    await root.query("select set_config('test.allow_policy_retroactive_write','true',false)");
    await root.query(`select public.set_daily_task_immediate_quota_policy(${day},true,'Native R4 review fixture')`);
    await root.query("select set_config('test.allow_policy_retroactive_write','false',false)");

    await scenario('direct-pause-before-slot-versus-resume',1101,async()=>{
      const f=await fixture();await installBarrier(f.camps[0],1101);
      await actor(a,f.uid);
      const pa=settle(a.query("update public.campaigns set status='paused',paused_reason='owner_manual' where id=$1 returning id",[f.camps[0]]));
      const atBarrier=await waitForBlock(a,root);
      await actor(b,f.uid);const pb=settle(b.query('select public.resume_campaign($1) r',[f.camps[0]]));
      const competingWait=await waitForBlock(b,a);
      await release(1101);const ar=await finish(a,await pa),br=await finish(b,await pb);
      assert.equal(ar.ok,true,JSON.stringify(ar));assert.equal(br.ok,true,JSON.stringify(br));
      assert.equal(br.rows[0].r.status,'open');
      assert.equal((await one('select status from public.campaigns where id=$1',[f.camps[0]])).status,'open');
      return {initial:f.initial,atBarrier,competingWait,action:ar,resume:br,after:await status(f.uid)};
    });

    for(const refund of [false,true])await scenario(refund?'delete-refund-before-slot-versus-create-v2':'delete-gate-before-physical-delete-versus-create-v2',refund?1103:1102,async()=>{
      const f=await fixture(),key=refund?1103:1102;let mission=null;
      if(refund){
        mission=randomUUID();
        await root.query(`insert into public.missions(id,creator_id,campaign_id,title,description,max_participants,duration_days,
          reward_credits,status,ends_at,reward_mode,escrow_total_credits,escrow_remaining_credits,escrow_refunded)
          values($1,$2,$3,'Native reviewer escrow','Fixture',10,7,50,'open',clock_timestamp()+interval '7 days','once',500,500,false)`,[mission,f.uid,f.camps[0]]);
        await root.query('update public.profiles set credits=credits-500 where id=$1',[f.uid]);
        await root.query(`insert into public.tester_reports(id,campaign_id,user_id,status,content,submission_context,provisional_granted_credits,provisional_clawed)
          values($1,$2,$3,'pending','Native reviewer pending app report','app_test',0,0)`,[randomUUID(),f.camps[0],f.suppliers[0]]);
      }
      const walletBefore=(await one('select credits from public.profiles where id=$1',[f.uid])).credits;
      const p=await prepareCreate(f.uid);
      await installBarrier(f.camps[0],key,{remove:!refund,closed:refund});
      await actor(a,f.uid);
      const pa=settle(a.query('select public.delete_owned_campaign($1) r',[f.camps[0]]));
      const atBarrier=await waitForBlock(a,root);
      const pb=settle(create(p));
      const competingWait=await waitForBlock(b,a);
      // Distinguishes waiting on the early slot lock from the old profile->slot inversion.
      assert.equal(competingWait.wait_event_type,'Lock');
      assert.equal(competingWait.wait_event.toLowerCase(),'advisory','Create must wait on slot BEFORE owner profile');
      await release(key);const ar=await finish(a,await pa),br=await finish(b,await pb);
      assert.equal(ar.ok,true,JSON.stringify(ar));assert.equal(br.ok,true,JSON.stringify(br));
      const removed=ar.rows[0].r,created=br.rows[0].r;
      assert.equal(removed.success,true);assert.equal(removed.status,refund?'pending_removal':'removed');
      assert.equal(created.success,true);assert.equal(created.replayed,false);
      const walletAfter=(await one('select credits from public.profiles where id=$1',[f.uid])).credits;
      assert.equal(walletAfter,walletBefore+(refund?500:0)-created.charged_credits);
      if(refund){
        const m=await one('select status,escrow_remaining_credits,escrow_refunded from public.missions where id=$1',[mission]);
        assert.equal(m.status,'deleted');assert.equal(m.escrow_remaining_credits,0);assert.equal(m.escrow_refunded,true);
        const logs=await one("select count(*)::int n,sum(amount)::int amount from public.credit_logs where user_id=$1 and action='mission_refund'",[f.uid]);
        assert.deepEqual(logs,{n:1,amount:500});
      }
      const after=await status(f.uid);assert.equal(after.active_app_count,2);assert.equal(after.base_required_tests,6);assert.equal(after.completed_tests,6);
      await actor(a,f.uid);const retry=await settle(a.query('select public.delete_owned_campaign($1) r',[f.camps[0]]));await finish(a,retry);
      assert.equal(retry.ok,true,JSON.stringify(retry));assert.equal(retry.rows[0].r.replayed,true);
      assert.equal((await one('select credits from public.profiles where id=$1',[f.uid])).credits,walletAfter);
      if(refund)acceptedRemoval={...f,walletAfter};
      return {initial:f.initial,atBarrier,competingWait,remove:removed,create:created,walletBefore,walletAfter,refund,after,retry:retry.rows[0].r};
    });

    await scenario('accepted-removal-worker-after-new-today-obligation',1104,async()=>{
      assert.ok(acceptedRemoval,'Prerequisite accepted escrow-removal case must succeed');
      const f=acceptedRemoval;
      await root.query(`insert into public.campaigns(owner_id,package_name,app_name,status,slot_number)
        values($1,$2,'New obligation after accepted removal','open',4)`,[f.uid,`review.extra.p${randomUUID().replaceAll('-','')}`]);
      const before=await status(f.uid);assert.equal(before.active_app_count,3);assert.equal(before.completed_tests,6);assert.equal(before.met,false);
      await root.query("update public.tester_reports set status='rejected',reviewed_at=clock_timestamp() where campaign_id=$1 and status='pending'",[f.camps[0]]);
      await a.query('begin');await a.query('set local role service_role');
      const worker=await settle(a.query('select public._process_closing_tester_bonus_rounds() r'));await finish(a,worker);
      assert.equal(worker.ok,true,JSON.stringify(worker));
      assert.equal((await one('select count(*)::int n from public.campaigns where id=$1',[f.camps[0]])).n,0);
      const receipt=await one('select owner_id,status from public.campaign_removal_receipts where campaign_id=$1',[f.camps[0]]);
      assert.ok(receipt);assert.equal(receipt.owner_id,f.uid);
      assert.equal((await one('select credits from public.profiles where id=$1',[f.uid])).credits,f.walletAfter);
      const logs=await one("select count(*)::int n,sum(amount)::int amount from public.credit_logs where user_id=$1 and action='mission_refund'",[f.uid]);
      assert.deepEqual(logs,{n:1,amount:500});
      const after=await status(f.uid);assert.equal(after.active_app_count,3);assert.equal(after.completed_tests,6);assert.equal(after.met,false);
      return {before,worker,receipt,after,wallet:f.walletAfter,refundLogs:logs};
    });

    await scenario('real-finalizer-profile-lock-versus-owner-resume',1105,async()=>{
      const f=await fixture();
      await root.query("update public.campaigns set status='paused',paused_reason='owner_manual' where id=$1",[f.camps[0]]);
      const walletBefore=(await one('select credits from public.profiles where id=$1',[f.uid])).credits;
      await a.query('begin');await a.query('set local role service_role');
      // Actual finalizer retains profile locks until COMMIT, including users whose
      // yesterday ledger was already finalized. No substitute finalizer or status stub.
      const finalized=await settle(a.query(`select public.finalize_daily_tasks_for_date(${day}-1) r`));
      assert.equal(finalized.ok,true,JSON.stringify(finalized));
      await actor(b,f.uid);const pb=settle(b.query('select public.resume_campaign($1) r',[f.camps[0]]));
      const competingWait=await waitForBlock(b,a);
      await a.query('commit');const resumed=await finish(b,await pb);
      assert.equal(resumed.ok,true,JSON.stringify(resumed));assert.equal(resumed.rows[0].r.status,'open');
      const after=await status(f.uid);assert.equal(after.active_app_count,2);assert.equal(after.completed_tests,6);assert.equal(after.met,true);
      assert.equal((await one('select credits from public.profiles where id=$1',[f.uid])).credits,walletBefore);
      return {finalized,competingWait,resumed,after,walletBefore};
    });
    evidence.pass=evidence.cases.length===5&&evidence.cases.every(c=>c.pass);
    return evidence;
  }finally{
    await root.query('select pg_advisory_unlock_all()').catch(()=>{});
    for(const client of [a,b]){
      await root.query("select pg_cancel_backend(pid) from pg_stat_activity where pid=$1 and state='active'",[client.processID]).catch(()=>{});
      await client.query('rollback').catch(()=>{});
    }
    await root.query('drop trigger if exists aaa_supervisor_r4_campaign_barrier on public.campaigns').catch(()=>{});
    await root.query('drop function if exists public._supervisor_r4_campaign_barrier()').catch(()=>{});
    await Promise.allSettled(clients.map(c=>c.end()));
  }
}
