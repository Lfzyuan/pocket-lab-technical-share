import test from 'node:test';
import assert from 'node:assert/strict';
import {createGuestClient,publicAssetURL,publicFileURL} from '../src/services/guest-client.mjs';
const ok=data=>({statusCode:200,data:{code:'OK',data}});

test('guest requests use only anonymous GET public projections and return envelope data',async()=>{
 const calls=[],data={items:[{id:'CAT01',name:'公开器材'}],total:1,page:1,pageSize:9};
 const client=createGuestClient({transport:async request=>{calls.push(request);return ok(data);}});
 assert.deepEqual(await client.request('/public/equipment?page=1&pageSize=9&q=A2'),data);
 assert.equal(calls.length,1);assert.equal(calls[0].url,'/api/public/equipment?page=1&pageSize=9&q=A2');assert.equal(calls[0].method,'GET');
 const header=calls[0].header||{};assert.equal(Object.keys(header).some(key=>/^(authorization|cookie|idempotency-key)$/i.test(key)),false);
 assert.ok(!Object.hasOwn(calls[0],'data'));
});

test('guest client rejects authenticated endpoints, traversal, private filters and write options before transport',async()=>{
 let sends=0;const client=createGuestClient({transport:async()=>{sends++;return ok({});}});
 const invalidPaths=['/me','/users','/borrow-requests','/files/file-1/content','/showcase?mine=true','https://elsewhere.invalid/public/equipment','//elsewhere.invalid/public/equipment','/public/equipment/../users','/public/equipment/%2e%2e','/public/equipment/CAT01%2f..%2fusers','/public/works?mine=true','/public/labs?status=pending','/public/equipment?borrowerId=student-1'];
 for(const path of invalidPaths)await assert.rejects(async()=>client.request(path),undefined,path);
 for(const options of [{method:'POST'},{method:'PATCH'},{method:'DELETE'},{headers:{Authorization:'Bearer forbidden'}},{headers:{}},{data:{name:'forbidden'}}])await assert.rejects(async()=>client.request('/public/equipment',options));
 assert.equal(sends,0);
});

test('guest public data is never reused after network failure or across API bases',async()=>{
 const calls=[];let online=true;
 const first=createGuestClient({baseURL:'/api',transport:async request=>{calls.push(request.url);if(!online)throw Error('offline');return ok({items:[{id:'public-only'}]});}});
 await first.request('/public/equipment');online=false;await assert.rejects(async()=>first.request('/public/equipment'));
 const second=createGuestClient({baseURL:'https://isolated-other.invalid/api',transport:async request=>{calls.push(request.url);throw Error('offline');}});
 await assert.rejects(async()=>second.request('/public/equipment'));
 assert.deepEqual(calls,['/api/public/equipment','/api/public/equipment','https://isolated-other.invalid/api/public/equipment']);
});

test('guest errors keep tracking IDs and server diagnostics out of display messages',async()=>{
 const client=createGuestClient({transport:async()=>({statusCode:503,data:{code:'UNAVAILABLE',message:'公开内容暂时不可用，请稍后重试。',requestId:'TRACE_GUEST_PRIVATE_123',stack:'PRIVATE_STACK'}})});
 await assert.rejects(async()=>client.request('/public/labs'),error=>{assert.ok(error.message);assert.doesNotMatch(error.message,/TRACE_GUEST_PRIVATE_123|PRIVATE_STACK|追踪号/);return true;});
});

test('guest media URLs are limited to static assets and public file projections',()=>{
 assert.equal(publicAssetURL('/static/board-blue.png'),'/static/board-blue.png');assert.equal(publicFileURL('work-cover'),'/api/public/files/work-cover/content');
 assert.equal(publicAssetURL('/api/public/files/work-cover/content','https://isolated-other.invalid/api'),'https://isolated-other.invalid/api/public/files/work-cover/content');
 for(const value of ['https://elsewhere.invalid/private.png','javascript:alert(1)','data:image/png;base64,AA','/api/files/private/content','/static/../private','/static/%2e%2e/private','/static/%5cprivate','/api/public/files/work-cover/content?token=secret'])assert.equal(publicAssetURL(value),'',value);
 assert.equal(publicFileURL('../private'),'');
});
