import test from 'node:test';
import assert from 'node:assert/strict';
import { createDualStorageAdapter, parseReportStorageLocation } from './adapters/storage-adapter.mjs';
import { SupabaseAuthorityAdapter } from './adapters/supabase-authority-adapter.mjs';
import { createMediaGatewayHandler } from './media-gateway-handler.mjs';

const base='https://trusted.supabase.co';
const bucket='cross-testers.firebasestorage.app';
const path='reports/campaign/user/photo.jpg';
const quiet={info(){},warn(){},error(){}};
function fixture() {
    const calls={google:[],supabase:[]};
    const supabaseClient={supabaseUrl:base,storage:{from:b=>({download:async p=>{
        calls.supabase.push([b,p]); return {data:new Blob(['supabase-bytes'],{type:'image/jpeg'})};
    }})}};
    const storageClient={bucket:b=>({file:p=>{
        calls.google.push([b,p]); return {
            exists:async()=>[true],getMetadata:async()=>[{contentType:'image/jpeg'}],download:async()=>[Buffer.from('google-bytes')],
        };
    }})};
    return {calls,adapter:createDualStorageAdapter({supabaseClient,supabaseUrl:base,storageClient,bucketName:bucket,logger:quiet})};
}
for(const location of [
    base+'/storage/v1/object/authenticated/report-screenshots/'+path,
    base+'/storage/v1/object/public/report-screenshots/'+path,
    base+'/storage/v1/object/report-screenshots/'+path,
    '/storage/v1/object/authenticated/report-screenshots/'+path,
    'supabase://report-screenshots/'+path,
]) test('read trusted Supabase location '+location,async()=>{
    const f=fixture(),r=await f.adapter.getObject({storagePath:location});
    assert.equal(r.bytes.toString(),'supabase-bytes');
    assert.deepEqual(f.calls.supabase,[['report-screenshots',path]]); assert.equal(f.calls.google.length,0);
});
for(const location of [
    'https://storage.googleapis.com/'+bucket+'/'+path,
    'https://firebasestorage.googleapis.com/v0/b/'+bucket+'/o/'+encodeURIComponent(path)+'?alt=media&token=fixture',
    'https://'+bucket+'/o/'+encodeURIComponent(path),
]) test('legacy Google URL remains readable '+location,async()=>{
    const f=fixture(),r=await f.adapter.getObject({storagePath:location});
    assert.equal(r.bytes.toString(),'google-bytes'); assert.deepEqual(f.calls.google,[[bucket,path]]);
});
test('Google filename containing supabase does not change provider',async()=>{
    const f=fixture();
    const r=await f.adapter.getObject({storagePath:'https://storage.googleapis.com/'+bucket+'/reports/c/u/supabase.jpg'});
    assert.equal(r.bytes.toString(),'google-bytes'); assert.equal(f.calls.supabase.length,0);
});
for(const bad of [
    'https://foreign.supabase.co/storage/v1/object/authenticated/report-screenshots/'+path,
    'https://trusted.supabase.co.evil.invalid/storage/v1/object/authenticated/report-screenshots/'+path,
    base+'/storage/v1/object/authenticated/avatars/'+path,
    base+'/storage/v1/object/authenticated/report-screenshots/reports/c/u/%252e%252e.jpg',
    'https://storage.googleapis.com/foreign-bucket/'+path,
    'https://untrusted.invalid/reports/c/u/supabase.jpg',
]) test('reject untrusted location before Storage call '+bad,async()=>{
    const f=fixture(); assert.equal(await f.adapter.getObject({storagePath:bad}),null);
    assert.equal(f.calls.google.length+f.calls.supabase.length,0);
});
test('reject provider mismatch from authority metadata',()=>{
    assert.equal(parseReportStorageLocation(base+'/storage/v1/object/authenticated/report-screenshots/'+path,
        {supabaseUrl:base,googleBuckets:[bucket],provider:'gcs'}),null);
});
function response() { return {status:0,headers:{},body:null,writeHead(status,headers){this.status=status;this.headers=headers;},end(body){this.body=body;}}; }
function request(expires=Math.floor(Date.now()/1000)+60) {
    return {method:'GET',headers:{host:'localhost',authorization:'Bearer user-fixture'},
        url:'/v1/media-gateway?report_id=report&grant_token=fixture&expires_at='+expires};
}
function handler(authority,storage) {
    return createMediaGatewayHandler({gatewaySecret:'test-secret-at-least-thirty-two-characters',
        authorityAdapter:{resolveUserFromToken:async()=>({id:'user'}),verifyGrant:async()=>authority},storageAdapter:storage,logger:quiet});
}
test('handler returns Supabase bytes directly without redirect',async()=>{
    const f=fixture(),res=response();
    await handler({isValid:true,provider:'supabase',storagePath:base+'/storage/v1/object/authenticated/report-screenshots/'+path},f.adapter)(request(),res);
    assert.equal(res.status,200); assert.equal(res.body.toString(),'supabase-bytes');
    assert.equal(res.headers.Location,undefined); assert.equal(res.headers['Cache-Control'],'private, no-store');
});
test('handler rejects bytes which arrive after media cutoff',async()=>{
    const res=response();
    await handler({isValid:true,viewExpiresAt:new Date(Date.now()-1).toISOString(),storagePath:path},
        {getObject:async()=>({bytes:Buffer.from('late')})})(request(),res);
    assert.equal(res.status,410); assert.notEqual(res.body?.toString(),'late');
});
test('grant at exact expiry is rejected',async()=>{
    const res=response();
    await handler({isValid:true},{getObject:async()=>{throw Error('must not fetch');}})(request(Math.floor(Date.now()/1000)),res);
    assert.equal(res.status,410);
});
test('missing Bearer fails before authority or Storage',async()=>{
    const res=response(),req=request(); delete req.headers.authorization;
    await handler({isValid:true},{getObject:async()=>{throw Error('must not fetch');}})(req,res);
    assert.equal(res.status,401);
});
test('authority uses individual attachment expiry independently of parent',async()=>{
    const queried=[];
    const adapter=new SupabaseAuthorityAdapter({logger:quiet,supabaseClient:{
        rpc:async()=>({data:true}),from:table=>{
            queried.push(table);
            const q={select(){return q;},eq(){return q;},async maybeSingle(){return {data:{
                storage_provider:'gcs',screenshot_url:'legacy',created_at:new Date().toISOString(),
                view_expires_at:new Date(Date.now()+60000).toISOString(),media_expired:false,
            }}}};return q;
        },
    }});
    const r=await adapter.verifyGrant({reportId:'report',userId:'user',attachmentId:'attachment'});
    assert.equal(r.isValid,true); assert.deepEqual(queried,['tester_report_attachments']);
});
test('authority rejects explicit expiry even with valid grant signature',async()=>{
    const q={select(){return q;},eq(){return q;},async maybeSingle(){return {data:{storage_provider:'supabase',
        created_at:new Date().toISOString(),view_expires_at:new Date(Date.now()-1).toISOString()}};}};
    const adapter=new SupabaseAuthorityAdapter({logger:quiet,supabaseClient:{rpc:async()=>({data:true}),from:()=>q}});
    assert.equal((await adapter.verifyGrant({reportId:'r',userId:'u'})).reason,'MEDIA_EXPIRED');
});

test('authority carries physical Storage cutoff through the final download check',async()=>{
    const actual=new Date(Date.now()+2000).toISOString();
    const q={select(){return q;},eq(){return q;},async maybeSingle(){return {data:{storage_provider:'supabase',
        screenshot_url:base+'/storage/v1/object/authenticated/report-screenshots/'+path,
        created_at:new Date().toISOString(),view_expires_at:new Date(Date.now()+60000).toISOString()}};}};
    const calls=[];
    const adapter=new SupabaseAuthorityAdapter({logger:quiet,supabaseClient:{rpc:async name=>{
        calls.push(name);return {data:name==='_report_media_deadline'?actual:true};},from:()=>q}});
    const result=await adapter.verifyGrant({reportId:'r',userId:'u'});
    assert.equal(result.isValid,true);assert.equal(result.viewExpiresAt,actual);
    assert.deepEqual(calls,['verify_media_access_grant_internal','_report_media_deadline']);
});
