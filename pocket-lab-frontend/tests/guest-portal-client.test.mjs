import test from 'node:test';
import assert from 'node:assert/strict';
import {createGuestPortalClient,createPortalContextClient,GUEST_MESSAGE,isGuestPrivatePage} from '../src/services/guest-portal-client.mjs';
import {createGuestClient} from '../src/services/guest-client.mjs';
function fixture(){const calls=[],notices=[];const publicClient=createGuestClient({transport:async options=>{calls.push(options);return {statusCode:200,data:{code:'OK',data:{items:[{id:'public-only'}],total:1,page:1,pageSize:9}}};}});return {calls,notices,client:createGuestPortalClient({publicClient,onDenied:text=>notices.push(text)})};}
test('student-layout guest adapter maps public reads without storage or identity',async()=>{
 const {client,calls}=fixture();const result=await client.catalog('/equipment?page=1&active=true&pageSize=9');assert.equal(result.data.items[0].active,true);
 for(const path of ['/labs','/works?equipmentId=CAT01','/resources?category=单片机','/resources/categories','/wishlist?stage=planned','/announcements','/duty-slots?from=2026-11-02&to=2026-11-08','/regulations'])await client.request(path);
 assert.equal(calls.length,9);assert.equal(calls[0].url,'/api/public/equipment?page=1&pageSize=9');
 for(const call of calls){assert.equal(call.method,'GET');assert.equal(call.withCredentials,false);assert.deepEqual(call.header,{Accept:'application/json'});}
 assert.equal(client.getSession(),null);assert.equal(client.pending(),null);
});
test('guest writes and identity operations prompt before any transport',async()=>{
 const {client,calls,notices}=fixture();
 for(const run of [()=>client.mutate('/favorites',{equipmentId:'CAT01'}),()=>client.request('/labs',{method:'POST'}),()=>client.request('/me'),()=>client.request('/users'),()=>client.request('/resources',{headers:{}}),()=>client.login('account','secret'),()=>client.setSession({user:{id:'forbidden'}}),()=>client.acknowledge()])await assert.rejects(async()=>run(),error=>error.message===GUEST_MESSAGE);
 assert.equal(calls.length,0);assert.equal(notices.length,8);
});
test('private guest pages return no personal records and cannot read arbitrary paths',async()=>{
 const {client,calls}=fixture();for(const path of ['/favorites','/borrow-requests?status=approved','/notifications'])assert.deepEqual((await client.request(path)).data.items,[]);
 for(const path of ['/equipment/../me','/works?mine=true','/resources/%2e%2e','https://example.invalid/equipment','/regulations/management/download','/equipment?userId=private'])await assert.rejects(async()=>client.request(path));
 assert.equal(calls.length,0);assert.equal(isGuestPrivatePage('records'),true);assert.equal(isGuestPrivatePage('works'),false);
});

test('async private results are rejected after switching to guest even if the mode later switches back',async()=>{
 let epoch=0,resolve;const client=createPortalContextClient({getClient:()=>({request:()=>new Promise(done=>resolve=done)}),getContext:()=>epoch});const task=client.request('/borrow-requests/private');epoch++;epoch++;resolve({data:{user:'PRIVATE'}});await assert.rejects(task,error=>error.code==='STALE_CONTEXT');
});
