import test from 'node:test';
import assert from 'node:assert/strict';
import {createOtherBorrows,validateOtherBorrow,shanghaiInstant} from '../src/services/other-borrows.mjs';

const dates={issuedDate:'2026-01-01',returnedDate:'2026-01-01'};
test('other borrowing sends a named request without catalog, link or caller identity fields',async()=>{
  const calls=[];
  const service=createOtherBorrows({mutate:async(...arguments_)=>{calls.push(arguments_);return {id:'other-1',status:'pending'};}});
  await service.create({
    name:' 示波器 ',model:'',functionDescription:' 双通道 ',purpose:' 课程设计 ',...dates,
    equipmentId:'catalog-id',assetNumber:'invented-code',userId:'different-person',url:'https://example.com',quantity:99
  });
  assert.deepEqual(calls,[['/other-borrow-requests',{name:'示波器',model:'',functionDescription:'双通道',purpose:'课程设计',...dates}]]);
  assert.throws(()=>validateOtherBorrow({name:'',purpose:'用途'}),/器材名称/);
  assert.throws(()=>validateOtherBorrow({...dates,name:'设备',purpose:'用途'.repeat(251)}),/500/);
  assert.equal(validateOtherBorrow({...dates,name:'设备',purpose:'用'.repeat(500),model:'型'.repeat(160)}).model.length,160);
  assert.throws(()=>validateOtherBorrow({...dates,name:'设备',purpose:'用途',model:'型'.repeat(161)}),/型号/);
});

test('own requests and staff review use server filtering and fresh detail',async()=>{
  const calls=[];
  const service=createOtherBorrows({request:async path=>{calls.push(path);return {data:{items:[],total:0}};}});
  await service.list({mine:true,page:2,pageSize:5,status:'approved'});
  await service.list({mine:false,status:'pending',pageSize:10});
  await service.detail('other/1');
  const own=new URL(calls[0],'https://unit.invalid');
  assert.equal(own.pathname,'/other-borrow-requests');
  assert.equal(own.searchParams.get('mine'),'true');assert.equal(own.searchParams.get('page'),'2');
  const staff=new URL(calls[1],'https://unit.invalid');
  assert.equal(staff.searchParams.get('mine'),'false');assert.equal(staff.searchParams.get('status'),'pending');
  assert.equal(calls[2],'/other-borrow-requests/other%2F1');
});

test('approvals require physical availability and handover needs a fresh version with checks',async()=>{
  const calls=[];
  const service=createOtherBorrows({mutate:async(...arguments_)=>{calls.push(arguments_);return {id:'other-1'};}});
  const row={id:'other-1',version:7,allowedActions:['approve','reject','issue','return-confirm']};
  assert.throws(()=>service.action(row,'approve'),/有可借实物/);
  assert.throws(()=>service.action(row,'reject',{reason:' '}),/处理原因/);
  assert.throws(()=>service.action(row,'issue'),/现场核验/);
  await service.action(row,'approve',{physicalAvailable:true,checked:true});
  await service.action(row,'reject',{reason:' 暂无可借设备 '});
  await service.action(row,'issue',{checked:true});
  assert.deepEqual(calls,[
    ['/other-borrow-requests/other-1/approve',{version:7,physicalAvailable:true}],
    ['/other-borrow-requests/other-1/reject',{version:7,reason:'暂无可借设备'}],
    ['/other-borrow-requests/other-1/issue',{version:7,checked:true}]
  ]);
  assert.throws(()=>service.action({...row,allowedActions:[]},'approve',{physicalAvailable:true}),/当前状态/);
});

test('registration validates date-only actual dates and confirms with actor-controlled server fields',async()=>{
 assert.equal(shanghaiInstant('2026-01-01','16:00'),'2026-01-01T08:00:00.000Z');
 assert.equal(shanghaiInstant('2026-02-30','16:00'),'');
 assert.throws(()=>validateOtherBorrow({name:'设备',purpose:'实际使用'}),/实际借出/);
 assert.throws(()=>validateOtherBorrow({name:'设备',purpose:'实际使用',...dates,returnedDate:'2025-01-01'}),/早于/);
 assert.throws(()=>validateOtherBorrow({name:'设备',purpose:'实际使用',...dates,returnedDate:'2099-01-01'}),/今天/);
 const writes=[],service=createOtherBorrows({mutate:async(...args)=>writes.push(args)});
 await service.action({id:'registration',version:3,allowedActions:['confirm']},'confirm',{confirmedBy:'invented',checked:true});
 assert.deepEqual(writes,[['/other-borrow-requests/registration/confirm',{version:3}]]);
});

test('checkout requires a matched teacher and keeps legacy physical-number actions compatible',async()=>{
 const calls=[],service=createOtherBorrows({mutate:async(...args)=>calls.push(args)});
 assert.throws(()=>service.createCheckout({name:'示波器'}),/匹配指导老师/);
 assert.throws(()=>service.createCheckout({name:'示波器',teacherUserId:'__none__'}),/匹配指导老师/);
 await service.createCheckout({name:' 示波器 ',teacherUserId:'teacher-1',expectedReturnMonth:'2027-01',assetNumber:'ignored'});
 const row={id:'checkout',workflowMode:'checkout',version:4,allowedActions:['issue','return-confirm']};
 await service.action(row,'issue',{checked:true,remark:' 无标签 '});
 await service.action(row,'issue',{checked:true,assetNumber:' 00001 '});
 assert.throws(()=>service.action({...row,assetNumber:'00001'},'return-confirm',{checked:true}),/绑定/);
 assert.throws(()=>service.action({...row,assetNumber:'00001'},'return-confirm',{checked:true,assetNumber:'00002'}),/不匹配/);
 await service.action({...row,assetNumber:'00001'},'return-confirm',{checked:true,assetNumber:'00001'});
 assert.deepEqual(calls,[
  ['/other-borrow-requests',{workflowMode:'checkout',name:'示波器',teacherUserId:'teacher-1'}],
  ['/other-borrow-requests/checkout/issue',{version:4,checked:true,remark:'无标签'}],
  ['/other-borrow-requests/checkout/issue',{version:4,checked:true,assetNumber:'00001'}],
  ['/other-borrow-requests/checkout/return-confirm',{version:4,checked:true,assetNumber:'00001'}]
 ]);
});
