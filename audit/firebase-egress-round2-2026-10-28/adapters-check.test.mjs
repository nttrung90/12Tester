import assert from 'node:assert/strict';
import test from 'node:test';
import {FirebaseStorageAdapter} from '../../backend/media-gateway/adapters/storage-adapter.mjs';
import {SupabaseAuthorityAdapter} from '../../backend/media-gateway/adapters/supabase-authority-adapter.mjs';
const bucketName='cross-testers.firebasestorage.app';
const logger={info(){},warn(){},error(){}};
const firebaseUrl=(bucket=bucketName)=>`https://firebasestorage.googleapis.com/v0/b/${bucket}/o/reports%2Fcampaign%2Ftester%2Ffull.jpg?alt=media&token=revoked_fixture`;
function storageFixture(){
 const calls=[];
 return {calls,client:{bucket(name){calls.push(['bucket',name]);return {file(object){calls.push(['object',object]);return {async exists(){return [true]},async getMetadata(){return [{contentType:'image/jpeg'}]},async download(){return [Buffer.from('fixture')]}}}}}}};
}
test('unconfigured storage adapter cannot depend on a public download token',async t=>{
 let fetches=0;
 t.mock.method(globalThis,'fetch',async()=>{fetches++;return new Response('fixture',{status:200,headers:{'content-type':'image/jpeg'}})});
 let adapter;
 try{adapter=new FirebaseStorageAdapter({bucketName,logger});}catch{return;}
 await adapter.getObject({reportId:'fixture',variant:'full',storagePath:firebaseUrl()});
 assert.equal(fetches,0,'Revoking old Firebase tokens must not break the replacement gateway; require authenticated Storage access');
});
test('authenticated GCS access parses a supported GCS URL into the object name',async()=>{
 const fixture=storageFixture();
 const adapter=new FirebaseStorageAdapter({bucketName,storageClient:fixture.client,logger});
 const result=await adapter.getObject({reportId:'fixture',variant:'full',storagePath:`https://storage.googleapis.com/${bucketName}/reports/campaign/tester/full.jpg`});
 assert.ok(result?.bytes);
 assert.equal(fixture.calls.find(x=>x[0]==='object')?.[1],'reports/campaign/tester/full.jpg');
});
test('storage adapter rejects a foreign bucket instead of substituting the configured bucket',async()=>{
 const fixture=storageFixture();
 const adapter=new FirebaseStorageAdapter({bucketName,storageClient:fixture.client,logger});
 const result=await adapter.getObject({reportId:'fixture',variant:'full',storagePath:firebaseUrl('untrusted-fixture')});
 assert.equal(result,null);
 assert.equal(fixture.calls.length,0,'Reject a foreign bucket before any Storage access');
});
test('real authority adapter recognizes retention expiry without media_expired flag',async()=>{
 const report={media_expired:false,created_at:new Date(Date.now()-31*86400000).toISOString()};
 const supabaseClient={rpc:async()=>({data:false,error:null}),from(){return {select(){return this},eq(){return this},maybeSingle:async()=>({data:report,error:null})}}};
 const adapter=new SupabaseAuthorityAdapter({supabaseClient,logger});
 const result=await adapter.verifyGrant({reportId:'fixture',userId:'tester',variant:'full',expiresAtEpoch:Math.floor(Date.now()/1000)+60,grantToken:'fixture'});
 assert.equal(result.isValid,false);
 assert.equal(result.reason,'MEDIA_EXPIRED','The HTTP layer must receive the correct expiry reason for a 410 response');
});
