import test from 'node:test';
import assert from 'node:assert/strict';
import {createClient} from '../src/services/client.mjs';
function fixture(transport,options={}){
  const saved=options.saved||new Map();
  const storage={get:key=>saved.get(key),set:(key,value)=>saved.set(key,structuredClone(value)),remove:key=>saved.delete(key),keys:()=>[...saved.keys()]};
  let key=0;
  const client=createClient({storage,transport,randomKey:()=>'test-key-'+(++key),...options});
  if(!client.getSession())client.setSession({accessToken:'opaque-token',user:{id:'user-one'}});
  return {client,saved,storage};
}
const ok=data=>({statusCode:200,data:{code:'OK',data},header:{}});
test('login rejects empty fields before sending and keeps password unchanged',async()=>{
  const requests=[];const {client}=fixture(async req=>{requests.push(req);return ok({accessToken:'new-token',user:{id:'signed-in'}});});
  await assert.rejects(client.login('  ','password'),/请输入账号/);
  await assert.rejects(client.login('student','  '),/请输入密码/);
  assert.equal(requests.length,0);
  await client.login(' student ',' password ');
  assert.deepEqual(requests[0].data,{account:'student',password:' password '});
});
test('login connection and service failures never become pending business operations',async()=>{
  for(const transport of [async()=>{throw Error('offline');},async()=>({statusCode:503,data:'unavailable'})]){
    const {client}=fixture(transport);
    await assert.rejects(client.login('student','password'),e=>!e.uncertain&&/登录服务/.test(e.message)&&!e.message.includes('待确认'));
    assert.equal(client.pending(),null);
  }
});
test('catalog stores ETag, validates 304 and uses cache only after network failure',async()=>{
  const requests=[];let n=0;
  const {client}=fixture(async req=>{requests.push(req);n++;if(n===1)return {...ok({items:[{id:'CAT01'}],total:1}),header:{ETag:'"one"'}};if(n===2)return {statusCode:304,header:{}};throw Error('offline');});
  assert.equal((await client.catalog('/equipment?page=1')).stale,false);
  assert.equal((await client.catalog('/equipment?page=1')).data.total,1);
  assert.equal(requests[1].header['If-None-Match'],'"one"');
  assert.equal((await client.catalog('/equipment?page=1')).stale,true);
});
test('authentication failure clears session/cache and never substitutes stale catalog',async()=>{
  let expired=0,n=0;
  const {client,saved}=fixture(async()=>++n===1?{...ok({items:[],total:0}),header:{ETag:'"one"'}}:{statusCode:401,data:{code:'UNAUTHORIZED',message:'登录失效'}},{onUnauthorized:()=>expired++});
  await client.catalog('/equipment');await assert.rejects(client.catalog('/equipment'),e=>e.status===401);
  assert.equal(expired,1);assert.equal(client.getSession(),null);assert.equal([...saved.keys()].some(k=>k.includes('catalog')),false);
});
test('uncertain mutation survives reload and retries with identical key and payload',async()=>{
  const sent=[];let n=0;
  const transport=async req=>{sent.push(req);if(++n===1)throw Error('connection lost after commit');return ok({id:'request-one',status:'approved'});};
  const {client,saved}=fixture(transport);
  await assert.rejects(client.mutate('/borrow-requests',{equipmentId:'CAT01',quantity:1}),e=>e.uncertain);
  assert.equal(client.pending().key,'test-key-1');
  const second=fixture(transport,{saved}).client;
  assert.equal((await second.retry()).id,'request-one');assert.equal(second.pending(),null);
  assert.equal(sent[0].header['Idempotency-Key'],sent[1].header['Idempotency-Key']);assert.deepEqual(sent[0].data,sent[1].data);
});
test('a pending operation blocks a different write without making a second request',async()=>{
  let requests=0;const {client}=fixture(async()=>{requests++;throw Error('offline');});
  await assert.rejects(client.mutate('/borrow-requests',{equipmentId:'CAT01'}));
  await assert.rejects(client.mutate('/borrow-requests',{equipmentId:'CAT02'}),e=>e.code==='PENDING_OPERATION');assert.equal(requests,1);
});
test('definite 409 discards current pending key while 5xx or malformed success retains it',async()=>{
  const {client}=fixture(async()=>({statusCode:409,data:{code:'STOCK_INSUFFICIENT',message:'库存不足'}}));
  await assert.rejects(client.mutate('/borrow-requests',{equipmentId:'CAT01'}),e=>e.code==='STOCK_INSUFFICIENT');assert.equal(client.pending(),null);
  for(const response of [{statusCode:502,data:'bad gateway'},{statusCode:200,data:'invalid success'}]){
    const {client:c}=fixture(async()=>response);await assert.rejects(c.mutate('/borrow-requests',{}),e=>e.uncertain);assert.ok(c.pending());
  }
});
test('pending credentials and caches are isolated between users',async()=>{
  const {client}=fixture(async()=>{throw Error('offline');});
  await assert.rejects(client.mutate('/borrow-requests',{equipmentId:'CAT01'}));
  client.setSession({accessToken:'other',user:{id:'user-two'}});assert.equal(client.pending(),null);
  await assert.rejects(client.mutate('/borrow-requests',{equipmentId:'CAT02'}));
  client.setSession({accessToken:'first',user:{id:'user-one'}});assert.equal(client.pending().body.equipmentId,'CAT01');
});
test('expired uncertain operations are not automatically resubmitted after 24h',async()=>{
  let clock=1000,calls=0;const {client}=fixture(async()=>{calls++;throw Error('offline');},{now:()=>clock});
  await assert.rejects(client.mutate('/borrow-requests',{}));clock+=86400000;
  await assert.rejects(client.retry(),e=>e.code==='PENDING_EXPIRED');assert.equal(calls,1);
});
test('failed credential persistence prevents a mutation from being sent',async()=>{
  let calls=0;const f=fixture(async()=>{calls++;return ok({});});f.storage.set=()=>{throw Error('quota exceeded');};
  await assert.rejects(f.client.mutate('/borrow-requests',{}),e=>e.code==='STORAGE_ERROR');assert.equal(calls,0);
});
test('PUT and DELETE retries preserve the original method, body and idempotency key after reload',async()=>{
  for(const method of ['PUT','DELETE']){
    const sent=[];const transport=async r=>{sent.push(r);if(sent.length===1)throw Error('timeout');return ok({id:'updated'});};
    const first=fixture(transport);await assert.rejects(first.client.mutate('/announcements/one',method==='PUT'?{version:2,content:'更新',enabled:true}:undefined,{method}));
    const reloaded=fixture(transport,{saved:first.saved}).client;await reloaded.retry();assert.equal(sent[1].method,method);assert.equal(sent[0].header['Idempotency-Key'],sent[1].header['Idempotency-Key']);assert.deepEqual(sent[0].data,sent[1].data);assert.equal(reloaded.pending(),null);
  }
});

test('friendly fallback retains diagnostic fields and uncertain mutation credentials',async()=>{
 const {client}=fixture(async()=>({statusCode:503,data:{code:'SERVER_BUSY',requestId:'internal-request-id'}}));
 await assert.rejects(client.mutate('/borrow-requests',{equipmentId:'CAT01'}),error=>{assert.equal(error.message,'请求暂时未完成，请稍后重试');assert.ok(!error.message.includes('503'));assert.ok(!error.message.includes('internal-request-id'));assert.equal(error.status,503);assert.equal(error.code,'SERVER_BUSY');assert.equal(error.requestId,'internal-request-id');assert.equal(error.uncertain,true);return true;});
 assert.equal(client.pending().key,'test-key-1');assert.deepEqual(client.pending().body,{equipmentId:'CAT01'});
});
