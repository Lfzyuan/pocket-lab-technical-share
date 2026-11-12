import test from 'node:test';import assert from 'node:assert/strict';
import {borrowFilters,borrowActionBody,createBorrowManagement} from '../src/services/borrow-management.mjs';
test('combined management filters are sent to server before pagination',async()=>{
 const calls=[],service=createBorrowManagement({request:async(...args)=>{calls.push(args);return {data:{items:[],eligible:0}};}});
 const filters={q:' 00001234 ',equipmentId:'CAT03',statuses:['approved','returning','approved'],from:'2026-01-01',to:'2026-11-04'};
 await service.list(filters,3,10);const url=new URL(calls[0][0],'https://unit.invalid');
 assert.equal(url.pathname,'/loan-records');assert.equal(url.searchParams.get('scope'),'management');assert.equal(url.searchParams.get('q'),'00001234');assert.equal(url.searchParams.get('statuses'),'approved,returning');assert.equal(url.searchParams.get('page'),'3');
 await service.preview('issue',filters);assert.deepEqual(calls[1],['/loan-records/bulk-preview',{method:'POST',data:{action:'issue',scope:'management',filters:borrowFilters(filters)}}]);
});
test('single and bulk snapshots preserve versions, checks, literal codes and failure boundaries',async()=>{
 const row={id:'loan-1',version:7,requiresAsset:true,assetNumber:'dz26001001',allowedActions:['issue','return-confirm']};
 assert.deepEqual(borrowActionBody(row,'issue',{assetNumber:' 00001234 '}),{version:7,identityChecked:true,quantity:1,assetNumber:'00001234'});
 assert.deepEqual(borrowActionBody(row,'return-confirm',{assetNumber:'dz26001001'}),{version:7,checked:true,assetNumber:'dz26001001'});
 assert.deepEqual(borrowActionBody(row,'issue'),{version:7,identityChecked:true,quantity:1});
 assert.deepEqual(borrowActionBody({...row,requiresAsset:false},'issue',{assetNumber:'0001'}),{version:7,identityChecked:true,quantity:1,assetNumber:'0001'});
 assert.deepEqual(borrowActionBody({...row,assetNumber:null},'return-confirm'),{version:7,checked:true});
 assert.throws(()=>borrowActionBody(row,'return-confirm'),/编号/);
 assert.throws(()=>borrowActionBody(row,'return-confirm',{assetNumber:'DZ26001001'}),/不匹配/);assert.throws(()=>borrowActionBody({...row,allowedActions:[]},'issue',{assetNumber:'CODE'}),/状态/);
 const calls=[],service=createBorrowManagement({mutate:async(...args)=>{calls.push(args);throw Object.assign(Error('版本变化'),{status:409});}});
 await assert.rejects(service.execute(row,'return-confirm',{assetNumber:'dz26001001'}),/版本/);
 assert.equal(calls.length,1);assert.equal(calls[0][1].version,7);
});
test('partial preview is refused instead of silently acting on a truncated selection',async()=>{
 const service=createBorrowManagement({request:async()=>({data:{eligible:2,items:[{id:'one'}]}})});
 await assert.rejects(service.preview('issue',{}),/预览不完整/);
});

test('pickup extension records a concrete deadline, explanation and the selected version',()=>{
 const row={id:'old-approved',version:8,allowedActions:['extend-pickup']};
 assert.throws(()=>borrowActionBody(row,'extend-pickup',{pickupDeadlineAt:'2027-01-01T08:00:00Z'}),/延期说明/);
 assert.deepEqual(borrowActionBody(row,'extend-pickup',{pickupDeadlineAt:'2027-01-01T08:00:00Z',reason:' 已沟通延期 '}),{version:8,pickupDeadlineAt:'2027-01-01T08:00:00Z',reason:'已沟通延期'});
});

test('unified rows route details and physical checks to their original source APIs',async()=>{
 const calls=[],service=createBorrowManagement({
  request:async path=>{calls.push({path});return {data:path.startsWith('/loan-records/summary')?{counts:{borrowed:2},total:2}:{id:'same-id',version:4,allowedActions:['issue','approve','return-confirm']}};},
  mutate:async(path,body)=>{calls.push({path,body});return body;}
 });
 const other={sourceType:'other',recordKey:'other:same-id',workflowMode:'legacy',id:'same-id',version:4,allowedActions:['issue','approve','return-confirm']};
 const fresh=await service.detail(other);assert.equal(fresh.sourceType,'other');assert.equal(fresh.recordKey,'other:same-id');
 await service.execute(other,'issue');
 await service.execute(other,'approve');
 await service.execute({...other,workflowMode:'checkout',assetNumber:'000aBc'},'return-confirm',{assetNumber:'000aBc'});
 const summary=await service.summary({q:'literal'});
 assert.deepEqual(summary.counts,{borrowed:2});
 assert.deepEqual(calls.slice(0,4),[
  {path:'/other-borrow-requests/same-id'},
  {path:'/other-borrow-requests/same-id/issue',body:{version:4,checked:true}},
  {path:'/other-borrow-requests/same-id/approve',body:{version:4,physicalAvailable:true}},
  {path:'/other-borrow-requests/same-id/return-confirm',body:{version:4,checked:true,assetNumber:'000aBc'}}
 ]);
 assert.match(calls[4].path,/^\/loan-records\/summary\?scope=management&q=literal$/);
});
