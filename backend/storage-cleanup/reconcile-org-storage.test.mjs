import test from 'node:test';
import assert from 'node:assert/strict';
import { reconcileOrganizationStorage, fetchOrganizationProjects, fetchProjectStorageBytes, integerBytes } from './reconcile-org-storage.mjs';
const a='a'.repeat(20),b='b'.repeat(20),c='c'.repeat(20);
const quiet={info(){},warn(){},error(){}};
const metric=(bytes='100')=>({bytes,observed_at:new Date().toISOString(),objects:[]});
function fixture(extra={}) {
    const calls=[];
    return {calls,run:options=>reconcileOrganizationStorage({
        supabaseClient:{rpc:async(name,args)=>(calls.push({name,args}),{data:{success:true,is_org_metric_valid:args.p_is_valid===true,dispatch_mode:args.p_is_valid?'supabase_preferred':'google_overflow'}})},
        totalCapacityBytes:'1000',organizationSlug:'fixture-org',managementToken:'fake-token',
        inventoryFn:async()=>[a,b],fetchProjectFn:async()=>metric(),logger:quiet,
        ...extra,...options,
    })};
}
test('complete org snapshots sum bytes exactly and submit manifest',async()=>{
    const f=fixture(),r=await f.run();
    assert.equal(r.is_metric_valid,true); assert.equal(r.total_used_bytes,'200');
    assert.equal(f.calls[0].args.p_total_capacity_bytes,'1000');
    assert.equal(f.calls[0].args.p_used_storage_bytes,'200');
    assert.equal(f.calls[0].args.p_measured_projects.length,2);
});
test('empty inventory and partial fetch failures invalidate org metric',async()=>{
    const empty=fixture({inventoryFn:async()=>[]});
    assert.equal((await empty.run()).fail_safe_triggered,true);
    assert.equal(empty.calls[0].args.p_is_valid,false);
    const partial=fixture({fetchProjectFn:async({projectRef})=>{
        if(projectRef===b) throw Error('network'); return metric();
    }});
    assert.equal((await partial.run()).is_metric_valid,false);
    assert.equal(partial.calls[0].args.p_is_valid,false);
});
for(const bytes of [-1,NaN,undefined,'-1','1.5',Number.MAX_SAFE_INTEGER+1]) {
    test('invalid byte count '+String(bytes)+' fails safe',async()=>{
        const f=fixture({fetchProjectFn:async()=>({...metric(),bytes})});
        const r=await f.run();
        assert.equal(r.is_metric_valid,false); assert.equal(r.dispatch_mode_result,'google_overflow');
    });
}
test('missing capacity is not replaced by guessed 1 GiB',async()=>{
    const f=fixture({totalCapacityBytes:'invalid'}),r=await f.run();
    assert.equal(r.total_capacity_bytes,null); assert.equal(r.is_metric_valid,false);
    assert.equal(f.calls[0].args.p_total_capacity_bytes,null);
});
test('decimal strings preserve byte precision beyond safe JS integers',async()=>{
    const f=fixture({totalCapacityBytes:'20000000000000000',fetchProjectFn:async()=>metric('9007199254740993')});
    const r=await f.run();
    assert.equal(r.total_used_bytes,'18014398509481986'); assert.equal(r.is_metric_valid,true);
});
test('stale, future or missing manifest snapshots invalidate metrics',async()=>{
    for(const m of [
        {...metric(),observed_at:new Date(Date.now()-31*60000).toISOString()},
        {...metric(),observed_at:new Date(Date.now()+120000).toISOString()},
        {...metric(),objects:undefined},
    ]) assert.equal((await fixture({fetchProjectFn:async()=>m}).run()).is_metric_valid,false);
});
test('scope mismatch and newly added project fail safe',async()=>{
    const f=fixture({projectRefs:[a]});
    assert.equal((await f.run()).is_metric_valid,false);
    let count=0;
    const changed=fixture({inventoryFn:async()=>++count===1?[a,b]:[a,b,c]});
    assert.equal((await changed.run()).is_metric_valid,false);
});
test('dry run does not mutate database',async()=>{
    const f=fixture(),r=await f.run({dryRun:true});
    assert.equal(r.is_metric_valid,true); assert.equal(f.calls.length,0);
});
test('rejected reconcile acknowledgement is a failure',async()=>{
    await assert.rejects(fixture({supabaseClient:{rpc:async()=>({data:{success:false}})}}).run(),/RECONCILE_RPC_REJECTED/);
});
test('inventory API paginates authoritative organization endpoint',async()=>{
    const urls=[];
    const refs=await fetchOrganizationProjects({organizationSlug:'org',managementToken:'fake',fetchFn:async url=>{
        urls.push(url); const offset=Number(new URL(url).searchParams.get('offset'));
        return {ok:true,json:async()=>({projects:[{ref:offset===0?a:b}],pagination:{count:2,limit:1,offset}})};
    }});
    assert.deepEqual(refs,[a,b]); assert.equal(urls.length,2);
    assert.match(urls[0],/\/v1\/organizations\/org\/projects/);
});
test('inventory missing pagination/count cannot be treated complete',async()=>{
    await assert.rejects(fetchOrganizationProjects({organizationSlug:'org',managementToken:'fake',
        fetchFn:async()=>({ok:true,json:async()=>({projects:[{ref:a}]})})}),/INVALID_PROJECT_INVENTORY_RESPONSE/);
});
test('project collector uses documented read-only query and validates schema',async()=>{
    let request;
    const result=await fetchProjectStorageBytes({projectRef:a,managementToken:'fake',fetchFn:async(url,init)=>{
        request={url,init}; return {ok:true,json:async()=>[{...metric(),invalid_objects:0}]};
    }});
    assert.match(request.url,/\/database\/query\/read-only$/);
    assert.equal(request.init.method,'POST');
    assert.match(JSON.parse(request.init.body).query,/from storage.objects/);
    assert.equal(result.bytes,'100');
    await assert.rejects(fetchProjectStorageBytes({projectRef:a,managementToken:'fake',
        fetchFn:async()=>({ok:true,json:async()=>({})})}),/INVALID_STORAGE_SNAPSHOT_RESPONSE/);
});
test('invalid object manifest is rejected',async()=>{
    const f=fixture({fetchProjectFn:async()=>({...metric(),objects:[{bucket_id:'avatar',name:'keep',size_bytes:'1',created_at:new Date().toISOString()}]})});
    assert.equal((await f.run()).is_metric_valid,false);
});
test('integer parser rejects implicit null and unsafe numbers',()=>{
    assert.throws(()=>integerBytes(null)); assert.throws(()=>integerBytes(1.5));
    assert.equal(integerBytes('9007199254740993'),9007199254740993n);
});
test('database acknowledgement invalid metric triggers fail-safe and invalidates summary',async()=>{
    const f=fixture({supabaseClient:{rpc:async()=>({data:{success:true,is_org_metric_valid:false,dispatch_mode:'google_overflow'}})}});
    const r=await f.run();
    assert.equal(r.is_metric_valid,false);
    assert.equal(r.fail_safe_triggered,true);
    assert.equal(r.dispatch_mode_result,'google_overflow');
});
for(const invalidAck of [undefined,null,'false','true',0,1,[],{}]) {
    test('malformed ACK flag '+JSON.stringify(invalidAck)+' triggers fail-safe and invalidates summary',async()=>{
        const f=fixture({supabaseClient:{rpc:async()=>({data:{success:true,is_org_metric_valid:invalidAck,dispatch_mode:'google_overflow'}})}});
        const r=await f.run();
        assert.equal(r.is_metric_valid,false);
        assert.equal(r.fail_safe_triggered,true);
    });
}
