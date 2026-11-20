import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanupSupabaseStorage, isReportObjectPath } from './cleanup-supabase-storage.mjs';

const now=new Date('2026-11-16T00:00:00Z');
const quiet={info(){},warn(){},error(){}};
const candidate=(extra={})=>({
    id:'11111111-1111-1111-1111-111111111111',
    upload_id:'up-123', provider:'supabase', bucket:'report-screenshots',
    object_path:'reports/camp1/user1/up-123.jpg',
    thumbnail_object_path:'reports/camp1/user1/thumb_up-123.jpg',
    size_bytes:800000, thumbnail_size_bytes:100000,
    server_created_at:new Date(now.getTime()-220*3600000).toISOString(),
    delete_deadline_at:new Date(now.getTime()+20*3600000).toISOString(),
    lease_token:'22222222-2222-2222-2222-222222222222',
    lease_expires_at:new Date(now.getTime()+300000).toISOString(),
    report_id:'report', ...extra,
});
function fixture({rows=[candidate()],remove,info,ack,claim}={}) {
    const calls={claims:[],removed:[],acks:[],from:0};
    const client={
        rpc:async(name,args)=>{
            if(name==='get_expired_report_media_for_cleanup') {
                calls.claims.push(args);
                return claim ? claim(args,calls.claims.length) : {data:rows,error:null};
            }
            assert.equal(name,'record_report_media_deleted');
            calls.acks.push(args);
            return ack || {data:{success:true,bytes_freed:900000},error:null};
        },
        storage:{from:bucket=>{
            assert.equal(bucket,'report-screenshots'); calls.from++;
            return {
                remove:async paths=>{
                    calls.removed.push(...paths);
                    return remove ? remove(paths,calls.removed.length) : {data:paths.map(name=>({name})),error:null};
                }, ...(info ? {info} : {}),
            };
        }},
    };
    return {calls,run:options=>cleanupSupabaseStorage({supabaseClient:client,now,logger:quiet,wait:async()=>{},...options})};
}
test('cleanup rejects foreign bucket and invalid prefix',async()=>{
    const f=fixture();
    await assert.rejects(f.run({bucketName:'foreign'}),/SECURITY ALERT/);
    await assert.rejects(f.run({prefix:'avatars/'}),/SECURITY ALERT/);
    await assert.rejects(f.run({prefix:'reports/../'}),/SECURITY ALERT/);
});
test('delete full and thumbnail, ack exact media UUID and lease with confirmed paths',async()=>{
    const f=fixture(), s=await f.run();
    assert.equal(s.candidates_deleted,1); assert.equal(s.bytes_reclaimed,900000);
    assert.equal(s.errors_count,0); assert.equal(s.sla_violations_count,0);
    assert.deepEqual(f.calls.acks,[{
        p_media_id:candidate().id,p_lease_token:candidate().lease_token,
        p_deleted_paths:[candidate().object_path,candidate().thumbnail_object_path],
    }]);
    assert.equal(f.calls.claims[0].p_dry_run,false);
});
test('retention >=240 hours emits SLA warning',async()=>{
    const f=fixture({rows:[candidate({server_created_at:new Date(now.getTime()-250*3600000).toISOString()})]});
    assert.equal((await f.run()).sla_violations_count,1);
});
test('dry run is read-only and does not claim or count physical deletion',async()=>{
    const f=fixture(),s=await f.run({dryRun:true});
    assert.equal(f.calls.claims[0].p_dry_run,true);
    assert.equal(s.candidates_would_delete,1); assert.equal(s.candidates_deleted,0);
    assert.equal(s.bytes_reclaimed,0); assert.equal(f.calls.from,0); assert.equal(f.calls.acks.length,0);
});
for(const [name,extra] of [
    ['GCS provider',{provider:'gcs'}],['foreign bucket',{bucket:'other'}],
    ['outside prefix',{object_path:'avatars/keep.jpg'}],
    ['traversal',{object_path:'reports/camp/user/../keep.jpg'}],
    ['wrong thumbnail pair',{thumbnail_object_path:'reports/other/user/thumb_other.jpg'}],
    ['missing identity',{id:undefined}],['expired lease',{lease_expires_at:now.toISOString()}],
    ['missing token',{lease_token:undefined}],
]) test('reject '+name+' before any Storage removal',async()=>{
    const f=fixture({rows:[candidate(extra)]}),s=await f.run();
    assert.equal(s.candidates_deleted,0); assert.equal(s.errors_count,1);
    assert.equal(f.calls.removed.length,0); assert.equal(f.calls.acks.length,0);
});
test('partial response cannot acknowledge full pair',async()=>{
    const f=fixture({remove:async(paths,n)=>n===1?{data:[{name:paths[0]}]}:{data:[]},
        info:async()=>({data:{name:'still-present'},error:null})});
    const s=await f.run();
    assert.equal(s.errors_count,1); assert.equal(f.calls.acks.length,0);
});
test('empty remove response requires independent absence confirmation',async()=>{
    const f=fixture({remove:async()=>({data:[]}),info:async()=>({error:{statusCode:'404'}})});
    assert.equal((await f.run()).candidates_deleted,1);
});
test('explicit 404 is idempotent but 403 is not deletion',async()=>{
    const missing=fixture({remove:async()=>({error:{statusCode:'404'}})});
    assert.equal((await missing.run()).candidates_deleted,1);
    const denied=fixture({remove:async()=>({error:{statusCode:'403'}})});
    assert.equal((await denied.run()).candidates_deleted,0); assert.equal(denied.calls.acks.length,0);
});
test('429 retries boundedly, persistent 500 remains unacknowledged',async()=>{
    const retry=fixture({remove:async(paths,n)=>n===1?{error:{statusCode:'429'}}:{data:paths.map(name=>({name}))}});
    assert.equal((await retry.run()).candidates_deleted,1); assert.equal(retry.calls.removed.length,3);
    const down=fixture({remove:async()=>({error:{statusCode:500}})});
    assert.equal((await down.run()).candidates_deleted,0); assert.equal(down.calls.removed.length,3);
});
test('rejected database acknowledgement does not count deletion success',async()=>{
    const f=fixture({ack:{data:{success:false,reason:'LEASE_MISMATCH'}}}),s=await f.run();
    assert.equal(s.candidates_deleted,0); assert.equal(s.errors_count,1);
});
test('drains multiple batches and reports bounded backlog',async()=>{
    const f=fixture({claim:async(args,n)=>({data:n<=2?[candidate({
        id:'11111111-1111-1111-1111-'+String(n).padStart(12,'0')})]:[],error:null})});
    const s=await f.run({batchSize:1,maxBatches:3});
    assert.equal(s.candidates_deleted,2); assert.equal(s.backlog_remaining,false);
    const backlog=fixture({claim:async(args,n)=>({data:[candidate({
        id:'11111111-1111-1111-1111-'+String(n).padStart(12,'0')})]})});
    assert.equal((await backlog.run({batchSize:1,maxBatches:2})).backlog_remaining,true);
});
test('malformed RPC data fails closed',async()=>{
    const f=fixture({claim:async()=>({data:{unexpected:true}})});
    assert.equal((await f.run()).errors_count,1); assert.equal(f.calls.removed.length,0);
});
test('path validator rejects encoded traversal',()=>{
    assert.equal(isReportObjectPath('reports/c/u/%2e%2e.jpg'),false);
});
