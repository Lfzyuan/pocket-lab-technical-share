import test from 'node:test';import assert from 'node:assert/strict';import {createWishlist,validateWish,reconcileWishPage,wishStageLabel} from '../src/services/wishlist.mjs';
const draft={name:' 六轴机器人 ',category:'机器人',description:' 项目学习 ',model:'',url:''};
test('wish payload excludes caller identity and invented vote counts',()=>{const v=validateWish({...draft,userId:'other-user',supportCount:100});assert.equal(v.name,'六轴机器人');assert.equal(v.description,'项目学习');assert.ok(!('userId' in v));assert.ok(!('supportCount' in v));assert.throws(()=>validateWish({...draft,url:'javascript:alert(1)'}));});
test('wishlist uses independent endpoint, not private purchase records',async()=>{const calls=[];const svc=createWishlist({request:async p=>{calls.push(p);return {data:{items:[],total:0,page:1}};}});await svc.list({category:'机器人',keyword:'六轴'});assert.match(calls[0],/^\/wishlist\?/);assert.ok(!calls[0].includes('purchase-requests'));});
test('support and cancel use authenticated mutation transport',async()=>{const calls=[];const svc=createWishlist({mutate:async(...args)=>{calls.push(args);return {id:'wish-1',supportCount:calls.length===1?1:0,supportedByMe:calls.length===1};}});await svc.support({id:'wish-1',supportedByMe:false});await svc.support({id:'wish-1',supportedByMe:true});assert.equal(calls[0][2].method,'POST');assert.equal(calls[1][2].method,'DELETE');});
test('missing backend and malformed counts never become local successful votes',async()=>{const svc=createWishlist({request:async()=>{throw Object.assign(Error('not found'),{status:404});},mutate:async()=>({id:'wish-1',supportCount:-1,supportedByMe:true})});await assert.rejects(()=>svc.list(),e=>e.status===404);await assert.rejects(()=>svc.support({id:'wish-1'}));});

test('admin review sends current version without inventing stock changes',async()=>{
 const calls=[];const client={mutate:async(...args)=>{calls.push(args);return {id:'wish',status:'approved'};}};
 const service=createWishlist(client);await service.review({id:'wish',version:4},'approve');await service.review({id:'wish',version:5},'reject','已具备相同器材');
 assert.deepEqual(calls[0],['/wishlist/wish/approve',{version:4}]);assert.deepEqual(calls[1],['/wishlist/wish/reject',{version:5,reason:'已具备相同器材'}]);
});

test('wish function and ordered links keep purpose separate and support old single links',()=>{
 const body=validateWish({...draft,function:' 图像处理 ',description:' 课程设计 ',urls:[' https://example.com/a ','','https://example.com/b']});
 assert.equal(body.function,'图像处理');assert.equal(body.description,'课程设计');assert.deepEqual(body.urls,['https://example.com/a','https://example.com/b']);
 assert.deepEqual(validateWish({...draft,url:'https://example.com/legacy'}).urls,['https://example.com/legacy']);
 assert.throws(()=>validateWish({...draft,urls:['javascript:alert(1)']}));assert.throws(()=>validateWish({...draft,urls:'https://example.com'}));
 assert.throws(()=>validateWish({...draft,function:'字'.repeat(2001)}));
});

test('quiet wish refresh preserves unchanged cards, array and support count identities',()=>{
 const first={id:'a',supportCount:1,supportedByMe:false,urls:['https://example.com/a']},second={id:'b',supportCount:2,supportedByMe:false,urls:[]};
 const target={items:[first,second],total:2,page:1};const original=target.items,links=first.urls;
 reconcileWishPage(target,{items:[{...first,urls:[...first.urls]},{...second,supportCount:3}],total:2,page:1});
 assert.equal(target.items,original);assert.equal(target.items[0],first);assert.equal(first.urls,links);assert.equal(target.items[1],second);assert.equal(first.supportCount,1);assert.equal(second.supportCount,3);
});
test('public wishes have exactly blank planned and genuinely stocked labels',()=>{
 assert.equal(wishStageLabel({status:'pending'}),'');assert.equal(wishStageLabel({status:'rejected'}),'');
 assert.equal(wishStageLabel({status:'approved',procurementStage:'none'}),'已列入计划');assert.equal(wishStageLabel({status:'approved',procurementStage:'stocked'}),'已采购并上架');
});
test('stock confirmation references real equipment and current version without stock adjustment',async()=>{
 const calls=[];const service=createWishlist({mutate:async(...args)=>{calls.push(args);return {id:'wish'};}});
 await service.markStocked({id:'wish',version:2},'actual-equipment');assert.deepEqual(calls[0],['/wishlist/wish/stocked',{version:2,equipmentId:'actual-equipment',confirmed:true}]);
 await assert.rejects(()=>service.markStocked({id:'wish',version:2},''));
});
