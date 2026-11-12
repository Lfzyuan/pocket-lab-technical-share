import test from 'node:test';import assert from 'node:assert/strict';import {usePortal} from '../src/services/portal.js';
globalThis.uni={$on(){},$off(){},pageScrollTo(){},getStorageSync(){},getStorageInfoSync(){return {keys:[]};}};
function fixture(fail=false){let stock=2;const user={id:'local-student',role:'student',phone:'13800000000',name:'测试学生'};const equipment={id:'board-1',name:'测试板',available:2,active:true,requiresApproval:false};const client={getSession:()=>({user}),pending:()=>null,request:async path=>path.startsWith('/equipment/')?{data:{...equipment,available:stock}}:path.startsWith('/borrow-requests?')?{data:{items:[],total:0}}:path.startsWith('/duty-slots?')?{data:{items:[{id:'slot-1',date:'2099-01-01',startTime:'08:00',endTime:'12:00'}],total:1}}:Promise.reject(Error(path)),mutate:async()=>{if(fail)throw Error('提交未完成');stock=1;return {id:'loan-1',equipmentId:equipment.id,status:'approved'};}};const portal=usePortal(client);Object.assign(portal.s,{page:'equipment',verified:true});portal.s.catalogFilter.keyword='测试板';portal.s.catalog.page=2;return {portal,equipment};}
test('opening and closing application retains equipment and catalog context',async()=>{const {portal:p,equipment}=fixture();await p.openEquipment(equipment);await p.beginApply();assert.equal(p.s.dialog,'apply');assert.equal(p.s.selected.id,equipment.id);p.close();assert.equal(p.s.dialog,'equipment');assert.equal(p.s.catalogFilter.keyword,'测试板');assert.equal(p.s.catalog.page,2);p.dispose();});
test('successful application remains on detail with refreshed availability',async()=>{const {portal:p,equipment}=fixture();await p.openEquipment(equipment);await p.beginApply();Object.assign(p.s.form,{slotId:'slot-1',purpose:'课程实验',phone:'13800000000'});p.s.agreed=true;await p.submitBorrow();assert.equal(p.s.page,'equipment');assert.equal(p.s.dialog,'equipment');assert.equal(p.s.selected.id,equipment.id);assert.equal(p.s.selected.available,1);assert.equal(p.s.activeEquipmentBorrow.id,'loan-1');assert.equal(p.s.catalog.page,2);p.dispose();});
test('failed application retains entered form and equipment',async()=>{const {portal:p,equipment}=fixture(true);await p.openEquipment(equipment);await p.beginApply();Object.assign(p.s.form,{slotId:'slot-1',purpose:'保留我的用途',phone:'13800000000'});p.s.agreed=true;await p.submitBorrow();assert.equal(p.s.dialog,'apply');assert.equal(p.s.selected.id,equipment.id);assert.equal(p.s.form.purpose,'保留我的用途');assert.match(p.s.error,/提交未完成/);p.dispose();});

test('admin can enter wishlist review while teacher has no review destination',async()=>{
 const {portal:p}=fixture();p.s.user.role='admin';await p.go('wishlist');assert.equal(p.s.page,'wishlist');
 p.s.page='home';p.s.user.role='reviewer';await p.go('wishlist');assert.equal(p.s.page,'home');p.dispose();
});

test('combined cancelled filter requests server-side statuses and preserves pagination totals',async()=>{
 const calls=[];const user={id:'local-student',role:'student'};
 const p=usePortal({getSession:()=>({user}),pending:()=>null,request:async path=>{calls.push(path);return {data:{items:[{id:'rejected-loan',status:'rejected'}],total:6,page:2,pageSize:5}};}});
 p.s.recordStatus='cancelled';await p.loadRecords(2);assert.match(calls[0],/statuses=rejected%2Ccancelled/);assert.ok(!calls[0].includes('&status='));assert.equal(p.s.records.total,6);assert.equal(p.s.records.page,2);assert.equal(p.s.records.items[0].status,'rejected');p.dispose();
});
test('quiet record refresh leaves existing content and loading indicator stable while waiting',async()=>{
 let resolve;const response=new Promise(done=>resolve=done),user={id:'local-student',role:'student'};
 const p=usePortal({getSession:()=>({user}),pending:()=>null,request:()=>response});p.s.records.items=[{id:'old'}];
 const pending=p.loadRecords(1,{quiet:true});assert.equal(p.s.loading,false);assert.equal(p.s.records.items[0].id,'old');
 resolve({data:{items:[{id:'fresh'}],total:1,page:1,pageSize:5}});await pending;assert.equal(p.s.loading,false);assert.equal(p.s.records.items[0].id,'fresh');p.dispose();
});

test('management account can switch to other borrowing without reusing management catalog',async()=>{
 const user={id:'local-manager',role:'reviewer',accountRole:'student',canManage:true};
 let session={user},resolveMe;
 const calls=[];
 const p=usePortal({
  getSession:()=>session,setSession:value=>session=value,pending:()=>null,
  request:(path,options)=>{
   if(path==='/me')return new Promise(resolve=>resolveMe=resolve);
   calls.push({path,options});
   return Promise.resolve({data:{...user,role:options.data.view==='user'?'student':'reviewer'}});
  },
  catalog:async path=>{calls.push({path});return {data:{items:[],total:0,page:1,pageSize:9},stale:false};}
 });
 p.s.verified=true;
 const oldRefresh=p.refresh({quiet:true});
 await p.switchBorrowerView('user','其他器材');
 assert.equal(p.s.user.role,'student');
 assert.equal(session.user.role,'student');
 assert.equal(p.s.page,'equipment');
 assert.equal(p.s.catalogFilter.category,'其他器材');
 assert.ok(calls.some(call=>call.path.includes('active=true')&&call.path.includes('category='+encodeURIComponent('其他器材'))));
 resolveMe({data:user});await oldRefresh;
 assert.equal(p.s.user.role,'student');
 await p.switchBorrowerView('management','其他器材');
 assert.equal(p.s.user.role,'reviewer');
 assert.equal(p.s.catalogFilter.category,'其他器材');
 p.dispose();
});

test('proxy application requires a selected account and sends no borrower-chosen slot',async()=>{
 const calls=[],user={id:'student-a',role:'student',phone:'13800000000'};
 const equipment={id:'CAT01',active:true,available:1,requiresApproval:false};
 const p=usePortal({
  getSession:()=>({user}),pending:()=>null,
  request:async path=>{
   if(path.startsWith('/equipment/'))return {data:equipment};
   if(path.startsWith('/borrow-requests?'))return {data:{items:[],total:0}};
   if(path.startsWith('/duty-slots?'))return {data:{items:[],total:0}};
   throw Error(path);
  },
  mutate:async(path,body)=>{calls.push({path,body});return {id:'proxy-loan',status:'approved',pickupProxyId:body.pickupProxyId};}
 });
 p.s.verified=true;await p.openEquipment(equipment);await p.beginApply();
 Object.assign(p.s.form,{purpose:'课程实验',proxyEnabled:true,slotId:'stale-slot'});p.s.agreed=true;
 await p.submitBorrow();assert.equal(calls.length,0);assert.match(p.s.error,/选择一名代领人/);
 p.s.form.pickupProxyId='classmate-b';await p.submitBorrow();
 assert.equal(calls.length,1);assert.equal(calls[0].body.pickupProxyId,'classmate-b');
 assert.ok(!Object.hasOwn(calls[0].body,'slotId'));assert.match(p.s.notice,/等待代领人选择领取时间/);
 p.dispose();
});

test('proxy tasks use separate server pagination and selected pickup time with current version',async()=>{
 const requests=[],writes=[],user={id:'teacher-b',role:'reviewer',accountRole:'student',accountType:'teacher'};
 const record={id:'proxy-loan',status:'approved',version:2,slotId:null,allowedActions:['schedule-pickup']};
 const slots=[{id:'slot-1',date:'2028-02-01',startTime:'08:00',endTime:'12:00',location:'实验室'}];
 const p=usePortal({
  getSession:()=>({user}),pending:()=>null,
  request:async path=>{
   requests.push(path);
   if(path.startsWith('/borrow-requests?'))return {data:{items:[record],total:1,page:1,pageSize:5}};
   if(path==='/borrow-requests/proxy-loan?pickupProxy=true')return {data:record};
   if(path.startsWith('/duty-slots?'))return {data:{items:slots,total:1}};
   throw Error(path);
  },
  mutate:async(path,body)=>{writes.push({path,body});return {...record,slotId:body.slotId,version:3};}
 });
 p.s.verified=true;await p.go('pickupTasks');
 assert.ok(requests.some(path=>path.includes('pickupProxy=true')));
 await p.openRecord(record);assert.equal(p.s.form.slots.length,1);
 globalThis.uni.showModal=options=>options.success({confirm:true});
 await p.act('schedule-pickup');assert.equal(writes.length,0);
 p.s.form.slotId='slot-1';await p.act('schedule-pickup');
 assert.deepEqual(writes,[{path:'/borrow-requests/proxy-loan/schedule-pickup',body:{version:2,slotId:'slot-1'}}]);
 assert.equal(p.s.selected.version,3);assert.equal(p.s.form.slotId,'slot-1');
 await p.go('records');assert.ok(!requests.at(-1).includes('pickupProxy=true'));
 p.dispose();
});

test('proxy contact is optional while ordinary borrowing still requires a phone',async()=>{
 const calls=[],user={id:'student-a',role:'student',phone:'13800000000'};
 const equipment={id:'CAT01',active:true,available:1,requiresApproval:false};
 const p=usePortal({
  getSession:()=>({user}),pending:()=>null,
  request:async path=>{
   if(path.startsWith('/equipment/'))return {data:equipment};
   if(path.startsWith('/duty-slots?'))return {data:{items:[{id:'slot-1',date:'2099-01-01',startTime:'08:00',endTime:'12:00'}],total:1}};
   if(path.startsWith('/borrow-requests?'))return {data:{items:[],total:0}};
   if(path.endsWith('/contact'))return {data:{phone:''}};
   throw Error(path);
  },
  mutate:async(path,body)=>{calls.push(body);return {id:'optional-contact-loan',status:'approved',pickupProxyId:body.pickupProxyId};}
 });
 p.s.verified=true;await p.openEquipment(equipment);await p.beginApply();
 p.s.form.purpose='课程实验';p.s.agreed=true;p.s.form.slotId='slot-1';
 p.editBorrowPhone('');await p.submitBorrow();assert.equal(calls.length,0);assert.match(p.s.error,/联系电话/);
 p.setPickupProxy(true);p.selectPickupProxy('classmate-b');
 await p.submitBorrow();assert.equal(calls.length,1);assert.equal(calls[0].phone,null);
 assert.equal(calls[0].pickupProxyId,'classmate-b');assert.ok(!Object.hasOwn(calls[0],'slotId'));
 p.dispose();
});

test('selecting a proxy never reads the private phone and preserves the applicant phone for self pickup',async()=>{
 const user={id:'student-a',role:'student',phone:'13800000000'},requests=[];
 const equipment={id:'CAT01',active:true,available:1,requiresApproval:false};
 const p=usePortal({
  getSession:()=>({user}),pending:()=>null,
  request:async path=>{
   requests.push(path);
   if(path.startsWith('/equipment/'))return {data:equipment};
   if(path.startsWith('/duty-slots?')||path.startsWith('/borrow-requests?'))return {data:{items:[],total:0}};
   throw Error(path);
  }
 });
 p.s.verified=true;await p.openEquipment(equipment);await p.beginApply();
 p.editBorrowPhone('13800000001');p.setPickupProxy(true);
 p.selectPickupProxy('teacher-b');assert.equal(p.s.form.pickupProxyId,'teacher-b');
 p.selectPickupProxy('staff-c');assert.equal(p.s.form.pickupProxyId,'staff-c');
 assert.equal(p.s.form.phone,'13800000001');assert.ok(requests.every(path=>!path.startsWith('/users/')));
 p.setPickupProxy(false);assert.equal(p.s.form.phone,'13800000001');assert.equal(p.s.form.pickupProxyId,'');
 p.dispose();
});

test('agreed pickup uses server submission time, accepts an empty purpose and clears proxy payload',async()=>{
 const calls=[],user={id:'student-a',role:'student',phone:'13800000000'},equipment={id:'CAT01',active:true,available:1,requiresApproval:false};
 const p=usePortal({getSession:()=>({user}),pending:()=>null,request:async path=>path.startsWith('/equipment/')?{data:equipment}:path.startsWith('/duty-slots')?{data:{items:[],total:0}}:{data:{items:[],total:0}},mutate:async(path,body)=>{calls.push(body);return {id:'agreed',status:'approved'};}});
 p.s.verified=true;await p.openEquipment(equipment);await p.beginApply();p.setPickupProxy(true);p.s.form.pickupProxyId='classmate';p.setPickupMode('agreed');
 p.s.agreed=true;await p.submitBorrow();
 assert.equal(calls.length,1);assert.equal(calls[0].pickupMode,'agreed');assert.equal(calls[0].purpose,'');assert.ok(!Object.hasOwn(calls[0],'agreedPickupDate'));assert.ok(!Object.hasOwn(calls[0],'agreedPickupAt'));
 assert.ok(!Object.hasOwn(calls[0],'pickupProxyId'));assert.ok(!Object.hasOwn(calls[0],'slotId'));assert.equal(p.s.activeEquipmentBorrow.status,'approved');
 p.dispose();
});

test('user error shows message only and keeps uncertain submission recovery and form values',async()=>{
 const pending={path:'/borrow-requests',body:{slotId:'unchanged-slot'},key:'original-key'};const user={id:'student-a',role:'student'};
 const p=usePortal({getSession:()=>({user}),pending:()=>pending});p.s.form={purpose:'保留用途',slotId:'unchanged-slot'};p.s.verified=true;
 const error=Object.assign(Error('提交结果待确认，请保留本次操作并重试确认'),{status:503,requestId:'private-trace-id',uncertain:true});await p.attempt(async()=>{throw error;});
 assert.equal(p.s.error,error.message);assert.ok(!p.s.error.includes(error.requestId));assert.equal(error.requestId,'private-trace-id');assert.equal(p.s.form.purpose,'保留用途');assert.equal(p.s.pending.key,'original-key');assert.equal(p.s.pending.body.slotId,'unchanged-slot');p.dispose();
});
test('an open pickup form clears expired slots immediately but preserves a pending request snapshot',async()=>{
 let clock=Date.parse('2026-01-01T09:59:59+08:00'),pending=null;const user={id:'student-a',role:'student'};
 const p=usePortal({getSession:()=>({user}),pending:()=>pending},{now:()=>clock});p.s.verified=true;p.s.dialog='apply';p.s.form={pickupMode:'duty',slotId:'ending',slots:[{id:'ending',date:'2026-01-01',startTime:'08:00',endTime:'10:00'},{id:'later',date:'2026-01-01',startTime:'10:00',endTime:'12:00'}]};
 p.prunePickupSlots();assert.equal(p.s.form.slotId,'ending');clock+=1000;p.prunePickupSlots();assert.equal(p.s.form.slotId,'');assert.deepEqual(p.s.form.slots.map(slot=>slot.id),['later']);assert.match(p.s.form.slotWarning,/已结束/);
 p.s.form.slotId='later';pending={path:'/borrow-requests',body:{slotId:'later'},key:'original'};clock=Date.parse('2026-01-01T12:00:00+08:00');p.prunePickupSlots();assert.equal(p.s.form.slotId,'later');assert.equal(pending.body.slotId,'later');p.dispose();
});
test('a late duty refresh cannot refill a closed application',async()=>{
 let resolve;const user={id:'student-a',role:'student'};const p=usePortal({getSession:()=>({user}),pending:()=>null,request:()=>new Promise(done=>resolve=done)},{now:()=>Date.parse('2026-01-01T09:00:00+08:00')});p.s.verified=true;p.s.dialog='apply';p.s.form={pickupMode:'duty',slotId:'',slots:[]};const form=p.s.form,reading=p.refreshPickupSlots({force:true});p.close();resolve({data:{items:[{id:'later',date:'2026-01-01',startTime:'10:00',endTime:'12:00'}],total:1}});await reading;assert.equal(p.s.dialog,'equipment');assert.deepEqual(form.slots,[]);p.dispose();
});


test('guest presentation shares student navigation without an identity or session writes',async()=>{
 const calls=[],prompts=[];const p=usePortal({getSession:()=>null,pending:()=>null,request:async path=>{calls.push(path);return {data:{items:[],total:0,page:1,pageSize:10}};},setSession:()=>{throw Error('must not persist guest');}},{onGuestDenied:()=>prompts.push('guest')});
 p.enterGuest();assert.equal(p.s.guest,true);assert.equal(p.s.user.role,'student');assert.equal(p.s.user.id,undefined);assert.equal(p.s.user.account,undefined);assert.equal(p.s.showRules,false);
 await p.go('records');assert.equal(p.s.page,'records');assert.deepEqual(p.s.records.items,[]);assert.equal(calls.length,0);
 await p.beginApply();await p.saveProfile();assert.equal(prompts.length,2);assert.equal(calls.length,0);
 p.leaveGuest();assert.equal(p.s.user,null);assert.equal(p.s.guest,false);p.dispose();
});
test('late guest equipment response cannot replace a later login context',async()=>{
 let resolve;const p=usePortal({getSession:()=>null,pending:()=>null,request:()=>new Promise(done=>resolve=done)});p.enterGuest();const task=p.openEquipment({id:'public-board'});p.leaveGuest();p.s.user={id:'real-user',role:'student'};resolve({data:{id:'public-board'}});await task;assert.equal(p.s.dialog,null);assert.equal(p.s.selected,null);p.dispose();
});

test('late private record and pickup form cannot reopen after entering guest',async()=>{
 const user={id:'real-student',role:'student'},waiters=[];const p=usePortal({getSession:()=>({user}),pending:()=>null,request:()=>new Promise(done=>waiters.push(done))});p.s.verified=true;
 const record=p.openRecord({id:'private-record'});p.leaveGuest();p.enterGuest();waiters.shift()({data:{id:'private-record',phone:'PRIVATE_PHONE',allowedActions:[]}});await record;assert.equal(p.s.dialog,null);assert.equal(p.s.selected,null);
 p.leaveGuest();p.s.user=user;p.s.verified=true;p.s.selected={id:'board'};const apply=p.beginApply();p.leaveGuest();p.enterGuest();waiters.shift()({data:{id:'board',active:true,available:2}});await apply;assert.equal(p.s.dialog,null);assert.deepEqual(p.s.form,{});p.dispose();
});

test('an in-flight real login cannot be mixed with entering guest',async()=>{
 const user={id:'real-login-user',name:'登录用户',role:'student'};let resolveLogin;const p=usePortal({getSession:()=>null,pending:()=>null,login:()=>new Promise(done=>resolveLogin=done),request:async()=>({data:{items:[],total:0}})});
 const login=p.login();assert.equal(p.s.busy,true);p.enterGuest();assert.equal(p.s.guest,false);assert.equal(p.s.user,null);
 resolveLogin(user);await login;assert.equal(p.s.user.id,user.id);assert.equal(p.s.guest,false);p.dispose();
});


function accountFixture(){const writes=[],reads=[],user={id:'admin-create-test',role:'admin'};const p=usePortal({getSession:()=>({user}),pending:()=>null,request:async(path,options)=>{if(options?.method==='POST'){writes.push(options.data);return {data:{id:'new-user',role:'student',accountType:options.data.role==='teacher'?'teacher':'student',canManage:false}};}reads.push(path);return {data:{items:[{id:'teacher-page-row',account:'001234',accountType:'teacher'}],total:137,page:2,pageSize:10}};}});p.s.verified=true;p.openUser();return {p,writes,reads};}

test('explicit teacher creation accepts six or eight digit IDs with six-character passwords without granting permissions',async()=>{
 for(const account of ['001234','00123456']){const {p,writes}=accountFixture();Object.assign(p.s.form,{role:'teacher',account,name:'测试老师',className:'电子系',password:' Abc12'});await p.saveUser();assert.equal(writes.length,1);assert.equal(writes[0].account,account);assert.equal(writes[0].role,'teacher');assert.equal(writes[0].password,' Abc12');assert.equal(writes[0].className,'电子系');assert.equal(Object.hasOwn(writes[0],'canManage'),false);assert.equal(Object.hasOwn(writes[0],'managementEnabled'),false);p.dispose();}
});

test('teacher creation rejects other ID lengths and passwords below six code points',async()=>{
 for(const form of [{account:'0012345',password:'123456'},{account:'T01234',password:'123456'},{account:'001234',password:'12345'},{account:'001234',password:'😀😀😀'}]){const {p,writes}=accountFixture();Object.assign(p.s.form,{role:'teacher',name:'测试老师',...form});await p.saveUser();assert.equal(writes.length,0);assert.ok(p.s.error);p.dispose();}
});

test('student and admin creation retain twelve-code-point passwords and do not infer teacher from ID',async()=>{
 for(const role of ['student','admin']){const {p,writes}=accountFixture();Object.assign(p.s.form,{role,account:'001234',name:'普通账号',password:'123456'});await p.saveUser();assert.equal(writes.length,0);assert.match(p.s.error,/12/);p.s.form.password='😀😀😀😀😀😀';await p.saveUser();assert.equal(writes.length,0);p.s.form.password='123456789012';await p.saveUser();assert.equal(writes.length,1);assert.equal(writes[0].role,role);p.dispose();}
});

test('user list combines persistent teacher type, keyword and enabled before server pagination',async()=>{
 const {p,reads}=accountFixture();Object.assign(p.s,{userAccountType:'teacher',userKeyword:' 张老师 ',userEnabled:'false'});await p.loadUsers(2);const query=new URL('https://isolated.invalid'+reads.at(-1)).searchParams;assert.equal(query.get('accountType'),'teacher');assert.equal(query.get('keyword'),'张老师');assert.equal(query.get('enabled'),'false');assert.equal(query.get('page'),'2');assert.equal(p.s.users.total,137);assert.equal(p.s.users.page,2);assert.equal(p.s.users.items[0].account,'001234');p.dispose();
});


test('teacher creation shortcut is explicit and generic or click-event callers retain student defaults',()=>{
 const {p}=accountFixture();assert.equal(p.s.form.role,'student');p.openUser('teacher');assert.equal(p.s.form.role,'teacher');assert.equal(p.s.form.password,'');p.openUser();assert.equal(p.s.form.role,'student');p.openUser({type:'click'});assert.equal(p.s.form.role,'student');p.openUser('admin');assert.equal(p.s.form.role,'student');p.dispose();
});

test('saved proxy pickup survives initial loading and failed refresh, then validates a complete list',async()=>{
 let resolveSlots,fail=false;
 const record={id:'saved-proxy',status:'approved',slotId:'saved',version:2,allowedActions:['schedule-pickup']};
 const slots=[{id:'saved',date:'2028-02-01',startTime:'08:00',endTime:'12:00'},{id:'later',date:'2028-02-01',startTime:'13:00',endTime:'15:00'}];
 const p=usePortal({getSession:()=>({user:{id:'proxy',role:'student'}}),pending:()=>null,request:async path=>{
  if(path==='/borrow-requests/saved-proxy')return {data:record};
  if(path.startsWith('/duty-slots?')){
   if(fail)throw Error('值班读取失败');
   return new Promise(resolve=>{resolveSlots=resolve;});
  }
  throw Error(path);
 }},{now:()=>Date.parse('2028-02-01T09:00:00+08:00')});
 p.s.verified=true;
 const opening=p.openRecord(record);await new Promise(setImmediate);
 assert.equal(p.s.form.slotId,'saved');assert.equal(p.s.form.slotsLoaded,false);
 p.prunePickupSlots();assert.equal(p.s.form.slotId,'saved');assert.equal(p.s.form.slotWarning,'');
 resolveSlots({data:{items:slots,total:2}});await opening;
 assert.equal(p.s.form.slotsLoaded,true);assert.equal(p.s.form.slotId,'saved');
 fail=true;await p.refreshPickupSlots({force:true});
 assert.equal(p.s.form.slotId,'saved');assert.match(p.s.form.slotsError,/读取失败/);
 p.selectPickupSlot('later');assert.equal(p.s.form.slotId,'saved');
 fail=false;const refreshing=p.refreshPickupSlots({force:true});await new Promise(setImmediate);
 resolveSlots({data:{items:slots.slice(1),total:1}});await refreshing;
 assert.equal(p.s.form.slotId,'');assert.match(p.s.form.slotWarning,/已结束或停用/);assert.equal(p.s.form.slotsError,'');
 p.selectPickupSlot('later');assert.equal(p.s.form.slotId,'later');assert.equal(p.s.form.slotWarning,'');
 p.dispose();
});

test('account changes synchronously clear the password while same-value assignments keep fresh input',()=>{
 const p=usePortal({getSession:()=>null,pending:()=>null});
 p.s.login.account='001234';p.s.login.password='new-password';p.s.showPassword=true;
 p.s.login.account='001234';assert.equal(p.s.login.password,'new-password');
 p.s.login.account='001235';assert.equal(p.s.login.password,'');assert.equal(p.s.showPassword,false);
 p.s.login.password='second-password';p.s.login.account='';assert.equal(p.s.login.password,'');p.dispose();
});

test('unified personal records include other sources and admin identity cannot navigate into proxy tasks',async()=>{
 const calls=[],user={id:'teacher',role:'reviewer',accountRole:'student',accountType:'teacher'};
 const other={sourceType:'other',recordKey:'other:same-id',id:'same-id',name:'设备',status:'borrowed'};
 const p=usePortal({getSession:()=>({user}),pending:()=>null,request:async path=>{
  calls.push(path);return {data:path.startsWith('/loan-records/summary')?{counts:{borrowed:2},total:2}:{items:[other],total:2,page:2,pageSize:5}};
 }});
 p.s.user.role='student';await p.loadRecords(2);await p.loadCounts();
 assert.match(calls[0],/^\/loan-records\?/);assert.match(calls[0],/scope=mine/);
 assert.equal(p.s.records.total,2);assert.equal(p.s.counts.borrowed,2);
 await p.openRecord(other);assert.equal(p.s.dialog,'other-record');assert.equal(calls.length,2);
 p.s.user={...user,role:'reviewer',accountRole:'admin',accountType:'admin'};p.s.page='home';
 assert.ok(!p.nav.value.some(item=>item.id==='pickupTasks'));
 await p.go('pickupTasks');assert.equal(p.s.page,'home');
 p.s.user=user;assert.ok(p.nav.value.some(item=>item.id==='pickupTasks'));p.dispose();
});

test('a saved pickup remains confirmed when the subsequent unified list cannot refresh',async()=>{
 const record={id:'proxy',status:'approved',slotId:'saved',version:2,allowedActions:['schedule-pickup']};
 const p=usePortal({getSession:()=>({user:{id:'proxy-user',role:'student'}}),pending:()=>null,
  request:async path=>{
   if(path==='/borrow-requests/proxy')return {data:record};
   if(path.startsWith('/duty-slots?'))return {data:{items:[{id:'saved',date:'2028-02-01',startTime:'08:00',endTime:'12:00'}],total:1}};
   if(path.startsWith('/loan-records/summary'))return {data:{counts:{approved:1}}};
   throw Error('列表读取失败');
  },
  mutate:async()=>({...record,version:3})
 },{now:()=>Date.parse('2028-02-01T09:00:00+08:00')});
 globalThis.uni.showModal=options=>options.success({confirm:true});
 p.s.verified=true;await p.openRecord(record);await p.act('schedule-pickup');
 assert.equal(p.s.selected.version,3);assert.equal(p.s.form.slotId,'saved');
 assert.match(p.s.notice,/确认代领时间成功/);assert.match(p.s.error,/已保存.*列表暂未刷新/);p.dispose();
});

test('a summary from the previous view cannot replace current account-view counts',async()=>{
 let resolve;
 const p=usePortal({getSession:()=>({user:{id:'manager',role:'student',accountRole:'student'}}),pending:()=>null,request:()=>new Promise(done=>resolve=done)});
 const reading=p.loadCounts();p.s.user.role='reviewer';p.s.counts={borrowed:8};
 resolve({data:{counts:{borrowed:1}}});await reading;assert.equal(p.s.counts.borrowed,8);p.dispose();
});

test('confirmed other-borrow retry returns to unified records without reopening creation',async()=>{
 let pending={path:'/other-borrow-requests',key:'fixed-operation',body:{workflowMode:'checkout'}};
 const calls=[],writes=[];let retries=0;
 const p=usePortal({getSession:()=>({user:{id:'student',role:'student'}}),pending:()=>pending,
  retry:async()=>{retries++;pending=null;return {id:'other-created',status:'borrowed'};},
  mutate:async(...args)=>{writes.push(args);throw Error('unexpected new mutation');},
  request:async path=>{calls.push(path);return {data:{items:[],total:0,page:1,pageSize:5}};}
 });
 p.s.verified=true;await p.retryPending();
 assert.equal(p.s.page,'records');assert.equal(p.s.dialog,null);
 assert.ok(calls[0].startsWith('/loan-records?'));assert.equal(p.s.pending,null);assert.equal(retries,1);assert.deepEqual(writes,[]);p.dispose();
});
