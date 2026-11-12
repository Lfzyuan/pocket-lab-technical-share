import {reactive,computed,nextTick,watch} from 'vue';
import {api} from './api.js';
import {modulesFor} from './modules.js';
import {GUEST_MESSAGE} from './guest-portal-client.mjs';
import {primaryNavigation} from './navigation.mjs';
import {shanghaiInstant,shanghaiParts} from './other-borrows.mjs';
import {borrowActionBody,isOtherLoan,loanRecordKey} from './borrow-management.mjs';
import {availableDutySlots} from './duty-week.mjs';
import {nextRenewMonths,renewLabel} from './loan-policy.mjs';
const queryString=value=>Object.entries(value).map(([key,item])=>encodeURIComponent(key)+'='+encodeURIComponent(String(item))).join('&');
export const labels={pending:'申请中',approved:'待领取',borrowed:'借用中',returning:'待归还',returned:'已归还',rejected:'已取消',cancelled:'已取消',closed:'异常结单',registered:'待确认',confirmed:'已确认'};
export const roles={student:'学生',reviewer:'老师',admin:'管理员'};
export function usePortal(client=api,{now=Date.now,onGuestDenied=()=>uni.showToast({title:GUEST_MESSAGE,icon:'none',duration:2200})}={}){
  const api=client;
  const initial=api.getSession();
  const s=reactive({user:initial?.user||null,guest:false,verified:false,starting:true,page:'home',busy:false,loading:false,error:'',notice:'',dialog:null,selected:null,
    catalog:{items:[],total:0,page:1,pageSize:9,stale:false,at:null},catalogFilter:{keyword:'',category:''},
    records:{items:[],total:0,page:1,pageSize:5},recordStatus:'all',pickupProxyRecords:false,duties:{items:[],total:0,page:1,pageSize:10},
    users:{items:[],total:0,page:1,pageSize:10},userKeyword:'',userAccountType:'',userEnabled:'',counts:{},pending:api.pending(),
    login:{account:'',password:''},showPassword:false,profile:{className:'',phone:'',email:'',grade:''},
    showRules:false,activeEquipmentBorrow:null,catalogView:'cards',resourceEquipment:'',resourceCategory:'',form:{},checkedIdentity:false,checkedItems:false,agreed:false,pendingChecked:false});
  let generation=0,noticeTimer,disposed=false,pickupClockTimer,pickupRefreshingForm=null,lastPickupFetch=0;
  const role=computed(()=>s.user?.role);
  const isAdmin=computed(()=>s.user?.accountRole==='admin'||s.user?.accountType==='admin'||s.user?.role==='admin');
  const stopLoginWatch=watch(()=>s.login.account,(value,previous)=>{
    if(value!==previous){s.login.password='';s.showPassword=false;}
  },{flush:'sync'});
  const nav=computed(()=>[
    {id:'home',name:'首页',icon:'home'},
    ...(role.value==='admin'?[{id:'wishlist',name:'心愿审批',icon:'check'}]:[]),
    {id:'equipment',name:role.value==='student'?'器材借用':'器材库存',icon:'equipment'},
    ...(role.value==='student'?[{id:'records',name:'我的借用',icon:'records'}]:role.value==='reviewer'?[{id:'review',name:'申请审批',icon:'check'},{id:'handover',name:'发放归还',icon:'transfer'}]:[{id:'records',name:'借用记录',icon:'records'},{id:'handover',name:'发放归还',icon:'transfer'},{id:'users',name:'账号管理',icon:'people'}]),
    ...(!isAdmin.value?[{id:'pickupTasks',name:'代领任务',icon:'people'}]:[]),{id:'duty',name:'值班时间',icon:'clock'},...modulesFor(role.value),{id:'profile',name:'个人资料',icon:'user'},...primaryNavigation(role.value),{id:'rules',name:'使用须知',icon:'info'}
  ]);
  const heading=computed(()=>(primaryNavigation(role.value).find(n=>n.id===s.page)||nav.value.find(n=>n.id===s.page))?.name||'口袋实验室');
  const pagesize=()=>role.value==='student'?5:10;
  const message=e=>e.message||'请求失败，请稍后重试';
  function notice(text){s.notice=text;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>s.notice='',6000);}
  function expired(){if(s.guest)return;generation++;s.user=null;s.showRules=false;s.verified=false;s.dialog=null;s.selected=null;s.catalog.items=[];s.records.items=[];s.users.items=[];s.counts={};s.error='登录已失效，请重新登录';}
  uni.$on('session-expired',expired);
  async function attempt(fn){const guest=s.guest;s.error='';try{return await fn();}catch(e){if(s.guest!==guest)return null;s.error=message(e);if(!e.status||e.status>=500)s.verified=false;s.pending=api.pending();return null;}}
  function canWrite(){
    if(s.guest){onGuestDenied();return false;}
    if(!s.verified){s.error='连接暂不可用，请稍后重试或重新打开页面';return false;}
    if(s.pending){s.error='有一笔提交结果待确认，请先处理上方提示';return false;}
    return !s.busy;
  }
  async function loadCatalog(page=1,{quiet=false}={}){
    const query={page,...(role.value==='student'?{active:true}:{}),pageSize:s.catalogView==='list'?100:role.value==='student'?9:10};
    if(s.catalogFilter.keyword.trim())query.keyword=s.catalogFilter.keyword.trim();
    if(s.catalogFilter.category)query.category=s.catalogFilter.category;
    const current=++generation;if(!quiet)s.loading=true;
    try{const result=await api.catalog('/equipment?'+queryString(query));if(current!==generation||!s.user)return;
      if(page>1&&!result.data.items.length&&result.data.total>0)return loadCatalog(Math.ceil(result.data.total/result.data.pageSize),{quiet});
      Object.assign(s.catalog,result.data,{stale:result.stale,at:result.at});
    }finally{if(current===generation)s.loading=false;}
  }
  async function loadRecords(page=1,{quiet=false}={}){
    if(s.guest){Object.assign(s.records,{items:[],total:0,page:1,pageSize:5});return;}
    const current=++generation;if(!quiet)s.loading=true;
    try{const q={page,pageSize:pagesize()};if(s.pickupProxyRecords)q.pickupProxy=true;if(s.recordStatus==='cancelled')q.statuses='rejected,cancelled';else if(s.recordStatus!=='all')q.status=s.recordStatus;
      const path=s.pickupProxyRecords?'/borrow-requests':'/loan-records';
      if(!s.pickupProxyRecords)q.scope=role.value==='student'?'mine':'management';
      const {data}=await api.request(path+'?'+queryString(q));if(current!==generation||!s.user)return;
      if(page>1&&!data.items.length)return loadRecords(Math.max(1,Math.ceil(data.total/data.pageSize)),{quiet});
      Object.assign(s.records,data);
    }finally{if(current===generation)s.loading=false;}
  }
  async function loadDuties(page=1){
    const guest=s.guest;const {data}=await api.request('/duty-slots?page='+page+'&pageSize=10');if(s.user&&s.guest===guest)Object.assign(s.duties,{...data,items:availableDutySlots(data.items,now())});
  }
  async function loadUsers(page=1){
    const current=++generation;s.loading=true;
    try{const {data}=await api.request('/users?'+queryString({page,pageSize:10,keyword:s.userKeyword.trim(),...(s.userAccountType?{accountType:s.userAccountType}:{}),...(s.userEnabled!==''?{enabled:s.userEnabled}:{})}));
      if(current!==generation||!s.user)return;if(page>1&&!data.items.length)return loadUsers(Math.max(1,Math.ceil(data.total/10)));Object.assign(s.users,data);
    }finally{if(current===generation)s.loading=false;}
  }
  async function loadCounts(){
    if(s.guest){s.counts={};return;}
    const userId=s.user?.id,requestedRole=role.value;if(!userId)return;
    const {data}=await api.request('/loan-records/summary?scope='+(requestedRole==='student'?'mine':'management'));
    if(s.user?.id===userId&&role.value===requestedRole)s.counts=data.counts||data;
  }
  function copyProfile(){Object.assign(s.profile,{className:s.user.className||'',phone:s.user.phone||'',email:s.user.email||'',grade:s.user.grade||''});}
  async function refresh({quiet=false}={}){
    const currentUser=s.user;if(!currentUser)return;
    if(s.guest){if(s.page==='equipment')await attempt(()=>loadCatalog(s.catalog.page,{quiet}));else if(['home','duty'].includes(s.page))await attempt(()=>loadDuties());return;}
    if(!quiet)s.error='';
    try {
      const {data}=await api.request('/me');if(s.user?.id!==currentUser.id||s.user.role!==currentUser.role||data.id!==currentUser.id)return;s.user=data;s.verified=true;
      api.setSession({...api.getSession(),user:data});s.pending=api.pending();
      if(s.page==='equipment')await loadCatalog(s.catalog.page,{quiet});
      else if(['records','review','handover','pickupTasks'].includes(s.page))await loadRecords(s.records.page,{quiet});
      else if(s.page==='duty')await loadDuties(s.duties.page);
      else if(s.page==='users')await loadUsers(s.users.page);
      else if(s.page==='home')await Promise.all([loadCounts(),loadDuties(1)]);
      s.error='';
    }catch(e){s.error=message(e);if(!e.status||e.status>=500)s.verified=false;}
  }
  async function init(){try{if(s.user){copyProfile();await refresh();}}finally{s.starting=false;}}
  async function login(){
    if(s.busy)return;s.busy=true;s.error='';
    try{s.user=await api.login(s.login.account.trim(),s.login.password);s.verified=true;s.page='home';s.showRules=true;s.pending=api.pending();copyProfile();await Promise.all([loadCounts(),loadDuties()]);}
    catch(e){s.error=message(e);if(s.user&&(!e.status||e.status>=500))s.verified=false;}finally{s.login.password='';s.busy=false;}
  }
  async function logout(){if(s.guest){leaveGuest();return;}s.busy=true;try{await api.logout();}catch{}finally{s.user=null;s.showRules=false;s.dialog=null;s.verified=false;s.pending=null;s.error='';s.notice='';s.catalog.items=[];s.records.items=[];s.users.items=[];s.counts={};s.login.password='';s.busy=false;generation++;}}
  function enterGuest(){
    if(s.busy)return;
    generation++;s.guest=true;s.user={name:'游客',role:'student',guest:true};s.verified=true;s.starting=false;
    s.page='home';s.dialog=null;s.selected=null;s.pending=null;s.counts={};s.catalog.items=[];s.records.items=[];s.users.items=[];s.showRules=false;s.error='';s.notice='';s.login.password='';
  }
  function leaveGuest(){
    generation++;s.guest=false;s.user=null;s.verified=false;s.dialog=null;s.selected=null;s.pending=null;s.counts={};s.catalog.items=[];s.records.items=[];s.duties.items=[];s.error='';s.notice='';s.form={};
  }
  async function go(page,{category='',pickupProxy=false,recordStatus='all'}={}){
    if(page==='pickupTasks'&&isAdmin.value||pickupProxy&&isAdmin.value)return;
    if(!nav.value.some(n=>n.id===page))return;generation++;s.loading=false;s.page=page;s.error='';s.dialog=null;s.selected=null;s.activeEquipmentBorrow=null;s.pickupProxyRecords=false;
    if(page==='equipment'){s.catalogFilter.keyword='';s.catalogFilter.category=category;await attempt(()=>loadCatalog());}
    else if(['records','review','handover','pickupTasks'].includes(page)){s.recordStatus=page==='review'?'pending':page==='handover'?'approved':recordStatus;s.pickupProxyRecords=page==='pickupTasks'||page==='records'&&role.value==='student'&&pickupProxy;await attempt(()=>loadRecords());}
    else if(page==='users'){s.userKeyword='';await attempt(()=>loadUsers());}
    else if(page==='duty')await attempt(()=>loadDuties());
    else if(page==='profile')copyProfile();
    else if(['home','personal'].includes(page))await attempt(()=>Promise.all([loadCounts(),loadDuties()]));
    uni.pageScrollTo({scrollTop:0,duration:0});
  }
  async function switchBorrowerView(view,category){
    if(!canWrite()||!s.user?.canManage||s.user.accountRole!=='student')return;
    const currentUser=s.user;s.busy=true;
    try{
      const {data}=await api.request('/me/view',{method:'POST',data:{view}});
      if(s.user?.id!==currentUser.id)return;
      s.user=data;api.setSession({...api.getSession(),user:data});
      await go('equipment',{category});
    }catch(e){s.error=message(e);}finally{s.busy=false;}
  }
  async function openEquipment(e){
    const stamp=++generation,guest=s.guest;s.error='';
    try{const {data}=await api.request('/equipment/'+e.id);if(stamp!==generation||s.guest!==guest||!s.user)return;
      s.selected=data;s.activeEquipmentBorrow=null;s.dialog='equipment';
      if(!guest&&s.user?.role==='student'){try{const result=(await api.request('/borrow-requests?equipmentId='+encodeURIComponent(data.id)+'&pageSize=100')).data;if(stamp===generation&&s.selected?.id===data.id)s.activeEquipmentBorrow=result.items.find(row=>['pending','approved','borrowed','returning'].includes(row.status))||null;}catch{}}
    }catch(error){if(stamp!==generation||s.guest!==guest)return;s.error=message(error);if(!error.status){s.selected=e;s.dialog='equipment';s.verified=false;}}
  }
  async function listPickupSlots(){
    const slots=[];let page=1,total;
    do{const {data}=await api.request('/duty-slots?page='+page+'&pageSize=100');slots.push(...data.items);total=data.total;page++;if(!data.items.length)break;}while(slots.length<total&&page<=100);
    if(slots.length<total)throw Error('值班时间未完整读取，请重新刷新');
    return availableDutySlots(slots,now());
  }
  function selectingPickup(){return s.dialog==='apply'&&s.form.pickupMode!=='agreed'&&!s.form.proxyEnabled||s.dialog==='record'&&s.selected?.allowedActions?.includes('schedule-pickup');}
  function prunePickupSlots(){
    if(disposed||!selectingPickup()||s.busy||s.pending||api.pending())return;
    const form=s.form;
    if(form.slotsLoaded===false||!Array.isArray(form.slots))return;
    const available=availableDutySlots(form.slots,now());
    if(available.length!==form.slots.length)form.slots=available;
    if(form.slotId&&!available.some(slot=>slot.id===form.slotId)){
      form.slotId='';
      form.slotWarning='原值班时段已结束或停用，请重新选择领取时间。';
    }
  }
  function selectPickupSlot(id){
    const form=s.form;
    if(form.slotsLoading||form.slotsLoaded===false||form.slotsError)return;
    if(id&&!form.slots?.some(slot=>slot.id===id))return;
    form.slotId=id;form.slotWarning='';
  }
  async function refreshPickupSlots({force=false}={}){
    if(disposed||!selectingPickup()||s.busy||s.pending||api.pending())return;
    prunePickupSlots();
    const form=s.form,dialog=s.dialog;
    if(pickupRefreshingForm===form||!force&&form.slotsLoaded&&now()-lastPickupFetch<30000)return;
    pickupRefreshingForm=form;form.slotsLoading=true;lastPickupFetch=now();
    try{
      const slots=await listPickupSlots();
      if(!disposed&&s.form===form&&s.dialog===dialog&&selectingPickup()&&!s.pending&&!api.pending()){
        form.slots=slots;form.slotsLoaded=true;form.slotsError='';
        prunePickupSlots();
        if(form.slotId&&slots.some(slot=>slot.id===form.slotId))form.slotWarning='';
      }
    }catch(e){if(!disposed&&s.form===form)form.slotsError=message(e);}
    finally{if(pickupRefreshingForm===form){pickupRefreshingForm=null;form.slotsLoading=false;}}
  }
  async function beginApply(){
    if(!canWrite())return;const stamp=++generation,userId=s.user?.id;s.busy=true;
    try{
      const {data}=await api.request('/equipment/'+s.selected.id);if(stamp!==generation||s.guest||s.user?.id!==userId)return;
      if(!data.active||data.available<1&&!data.requiresApproval)throw new Error('当前器材不可申请，请刷新库存');
      let slots=[],slotsError='';
      try{slots=await listPickupSlots();}catch(e){slotsError=message(e);}
      if(stamp!==generation||s.guest||s.user?.id!==userId)return;
      s.selected=data;s.form={equipmentId:data.id,quantity:1,purpose:'',phone:s.user.phone||'',slotId:'',slots,slotsLoaded:!slotsError,slotsLoading:false,slotsError,slotWarning:'',pickupMode:'duty',proxyEnabled:false,pickupProxyId:''};lastPickupFetch=now();s.agreed=false;s.dialog='apply';
    }catch(e){if(stamp===generation&&!s.guest&&s.user?.id===userId)s.error=message(e);}finally{s.busy=false;}
  }
  function setPickupProxy(enabled){
    const form=s.form;
    if(form.proxyEnabled===enabled)return;
    if(enabled){form.pickupMode='duty';form.slotId='';}
    form.proxyEnabled=enabled;
    form.pickupProxyId='';
  }
  function setPickupMode(value){
    if(!['duty','agreed'].includes(value)||s.form.pickupMode===value)return;
    if(s.form.proxyEnabled)setPickupProxy(false);
    s.form.pickupMode=value;
    s.form.slotId='';
  }
  function editBorrowPhone(value){s.form.phone=value;}
  function selectPickupProxy(id){
    if(s.dialog==='apply'&&s.form.proxyEnabled)s.form.pickupProxyId=id;
  }
  async function submitBorrow(){
    if(!canWrite())return;
    if(!s.agreed){s.error='请先确认借用须知';return;}
    prunePickupSlots();
    const {equipmentId,purpose,phone,slotId,proxyEnabled,pickupProxyId,pickupMode='duty'}=s.form;
    if(purpose.length>500){s.error='借用用途不能超过500字';return;}
    if(!proxyEnabled&&!phone.trim()){s.error='请填写联系电话';return;}
    if(proxyEnabled&&!pickupProxyId){s.error='请选择一名代领人';return;}
    if(pickupMode==='duty'&&!proxyEnabled&&(s.form.slotsLoading||s.form.slotsLoaded===false||s.form.slotsError||!slotId)){s.form.slotWarning='请选择已确认有效的领取值班时间';return;}
    const pickup=pickupMode==='agreed'?{pickupMode:'agreed'}:proxyEnabled?{pickupProxyId}:{slotId};
    s.busy=true;s.error='';
    try{
      const result=await api.mutate('/borrow-requests',{equipmentId,quantity:1,purpose:purpose.trim(),phone:proxyEnabled?null:phone.trim(),...pickup,agreed:true});
      s.pending=null;s.activeEquipmentBorrow=result;s.dialog='equipment';
      try{s.selected=(await api.request('/equipment/'+equipmentId)).data;}catch{s.error='申请已成功提交，器材数量暂未刷新，请稍后刷新核对。';}
      notice(result.pickupProxyId?'申请已提交，等待代领人选择领取时间':result.status==='approved'?'预约成功，已预留器材，请等待老师现场发放':'申请已提交，等待老师审批');
    }catch(e){s.error=message(e);s.pending=api.pending();}finally{s.busy=false;}
  }
  async function openRecord(record){
    if(s.guest){onGuestDenied();return;}
    if(isOtherLoan(record)){s.selected=record;s.error='';s.dialog='other-record';return;}
    const stamp=++generation,userId=s.user?.id;
    s.error='';await attempt(async()=>{
      const {data}=await api.request('/borrow-requests/'+record.id+(s.pickupProxyRecords?'?pickupProxy=true':''));
      if(stamp!==generation||s.guest||s.user?.id!==userId)return;
      s.selected={...data,sourceType:'normal',recordKey:loanRecordKey(record)};const deadline=shanghaiParts(data.pickupDeadlineAt);s.form={reason:'',assetNumber:'',slotId:data.slotId||'',slots:[],slotsLoaded:false,slotsLoading:false,slotsError:'',slotWarning:'',deadlineDate:deadline.date,deadlineTime:deadline.time};s.checkedIdentity=false;s.checkedItems=false;s.dialog='record';
      await nextTick();uni.pageScrollTo({scrollTop:0,duration:0});
      if(data.allowedActions.includes('schedule-pickup'))await loadProxySlots();
    });
  }
  async function loadProxySlots(){return refreshPickupSlots({force:true});}

  const actionNames={approve:'审批通过',reject:'拒绝申请',cancel:'取消领取','extend-pickup':'延长领取时间','schedule-pickup':'确认代领时间',issue:'确认发放','return-request':'申请归还','return-confirm':'确认归还','return-reject':'退回归还验收',renew:'申请续借'};
  async function act(action,{direct=false}={}){
    if(!canWrite())return;
    const selected=s.selected;
    if(!selected?.allowedActions?.includes(action)||s.pickupProxyRecords&&action!=='schedule-pickup'){s.error='当前状态不支持此操作，请刷新记录';return;}
    if(['reject','return-reject'].includes(action)&&!s.form.reason.trim()){s.error='请填写拒绝或退回原因';return;}
    let assetBody;
    if(['issue','return-confirm'].includes(action)){
      try{assetBody=borrowActionBody(selected,action,{assetNumber:s.form.assetNumber});}catch(e){s.error=message(e);return;}
    }
    if(action==='extend-pickup'&&(!shanghaiInstant(s.form.deadlineDate,s.form.deadlineTime)||!s.form.reason.trim())){s.error='请选择新的领取截止时间并填写延期说明';return;}
    if(action==='schedule-pickup')prunePickupSlots();
    if(action==='schedule-pickup'&&(s.form.slotsLoading||s.form.slotsLoaded===false||s.form.slotsError||!s.form.slots?.some(slot=>slot.id===s.form.slotId))){s.form.slotWarning='请选择已确认有效的领取值班时间';return;}
    const actionTitle=action==='cancel'&&selected.status==='pending'?'撤回申请':actionNames[action];
    const confirmed=direct&&['issue','return-confirm'].includes(action)||await new Promise(resolve=>uni.showModal({title:action==='renew'?renewLabel(selected):actionTitle,content:action==='return-request'?'申请后请查看值班时间，并于一周内交回器材与配件；超过一周仍未验收归还将收到站内提醒。申请归还不延长原借期。':action==='cancel'?(selected.status==='pending'?'确定撤回这条申请？':'确定取消领取？已预留的器材将释放。'):action==='renew'?'从本条记录原到期日起延长'+nextRenewMonths(s.selected)+'个自然月；第一次续借2个月，第二次1个月，最多2次。':['issue','return-confirm'].includes(action)?'请确认已现场核对身份、器材和配件'+(action==='issue'?'，并发放1件器材。':'，器材完好并已交回。'):`确认${actionNames[action]}？办理结果由服务器确认。`,success:r=>resolve(r.confirm),fail:()=>resolve(false)}));
    if(!confirmed||!canWrite()||s.selected!==selected)return;
    if(action==='schedule-pickup'){prunePickupSlots();if(s.form.slotsError||!s.form.slotId||!s.form.slots?.some(slot=>slot.id===s.form.slotId)){s.form.slotWarning='请选择尚未结束的领取值班时间';return;}}
    const id=selected.id;const body=assetBody||{version:selected.version};
    if(action==='schedule-pickup')body.slotId=s.form.slotId;
    if(action==='extend-pickup')Object.assign(body,{pickupDeadlineAt:shanghaiInstant(s.form.deadlineDate,s.form.deadlineTime),reason:s.form.reason.trim()});
    if(['reject','return-reject'].includes(action))body.reason=s.form.reason.trim();
    s.busy=true;s.error='';
    try{const result=await api.mutate('/borrow-requests/'+id+'/'+action,body);s.pending=null;s.selected=result;s.form=action==='schedule-pickup'?{...s.form,slotId:result.slotId,slotWarning:''}:{reason:'',assetNumber:''};s.checkedIdentity=false;s.checkedItems=false;
      if(['approve','reject'].includes(action))s.dialog=null;
      notice(action==='return-request'?'归还申请已提交，请查看值班时间并于一周内交回器材和配件':actionTitle+'成功');
      try{await Promise.all([loadRecords(s.records.page),loadCounts()]);}
      catch{s.error=actionTitle+'已保存，记录列表暂未刷新，请稍后刷新核对。';}
    }catch(e){s.error=message(e);s.pending=api.pending();if(e.status===409){try{s.selected=(await api.request('/borrow-requests/'+id+(s.pickupProxyRecords?'?pickupProxy=true':''))).data;await loadRecords(s.records.page);}catch{}}}
    finally{s.busy=false;}
  }
  async function retryPending(){
    if(s.busy||!s.verified)return;s.busy=true;s.error='';
    try{const path=s.pending?.path||'';const result=await api.retry();s.pending=null;s.dialog=null;
      const newCheckout=path==='/other-borrow-requests'&&result.status==='borrowed';
      const destinations={'/assets/import':'inventory','/other-borrow-requests':role.value==='reviewer'?'handover':'records','/regulations':'rules','/wishlist':'wishlist','/lab-bookings':'bookings','/purchase-requests':'purchases','/work-submissions':'submissions','/works':'works','/resources':'resources','/incidents':'incidents','/favorites':'favorites','/stock-subscriptions':'subscriptions','/announcements':'announcements','/notifications':'notifications'};
      await go(path.endsWith('/schedule-pickup')?(isAdmin.value?'records':'pickupTasks'):Object.entries(destinations).find(([prefix])=>path.startsWith(prefix))?.[1]||(role.value==='student'?'records':result.status==='pending'?'review':'handover'),newCheckout?{recordStatus:'borrowed'}:undefined);if(newCheckout)await attempt(()=>loadCounts());notice('服务器已确认上次操作结果');}
    catch(e){s.error=message(e);s.pending=api.pending();}finally{s.busy=false;}
  }
  async function acknowledgePending(){
    if(!s.pendingChecked){s.error=/^\/regulations\/(?:management|usage)\/content$/.test(s.pending?.path||'')?'请先核对该规定已发布的正文，确认上次发布结果后再勾选':'请先查询借用列表和事件，确认上次结果后再勾选';return;}
    const r=await new Promise(resolve=>uni.showModal({title:'清除本机待确认提示',content:'这不会撤销服务器操作。仅在已核对记录或经老师确认后继续。',success:resolve}));
    if(r.confirm){api.acknowledge();s.pending=null;s.pendingChecked=false;}
  }
  async function saveProfile(){
    if(!canWrite())return;s.busy=true;await attempt(async()=>{const {data}=await api.request('/me',{method:'PATCH',data:{className:s.profile.className.trim(),phone:s.profile.phone.trim(),email:s.profile.email.trim(),grade:(s.profile.grade||'').trim()}});s.user=data;api.setSession({...api.getSession(),user:data});notice('个人资料已保存');});s.busy=false;
  }
  function openDuty(){s.form={date:'',startTime:'08:00',endTime:'12:00',location:''};s.error='';s.dialog='duty';}
  async function saveDuty(){
    if(!canWrite())return;
    if(!s.form.date||!s.form.location.trim()){s.error='请选择日期并填写地点';return;}
    s.busy=true;
    try{await api.request('/duty-slots',{method:'POST',data:{date:s.form.date,startTime:s.form.startTime,endTime:s.form.endTime,location:s.form.location.trim()}});s.dialog=null;await loadDuties();notice('值班时间已保存，所有设备可见');}
    catch(e){s.error=message(e)+(e.uncertain?'。请先刷新值班列表核对，避免重复创建':'');}finally{s.busy=false;}
  }
  function openUser(accountType='student'){s.form={account:'',name:'',role:accountType==='teacher'?'teacher':'student',password:'',className:''};s.error='';s.dialog='user';}
  async function saveUser(){
    if(!canWrite())return;
    const teacher=s.form.role==='teacher',account=s.form.account.trim();
    if(!account||!s.form.name.trim()){s.error='请填写'+(teacher?'工号':'账号')+'和姓名';return;}
    if(teacher&&!/^(?:[0-9]{6}|[0-9]{8})$/.test(account)){s.error='教师工号须为6位或8位数字，前导0请保留';return;}
    if([...s.form.password].length<(teacher?6:12)){s.error=teacher?'教师初始密码至少6个字符':'学生和管理员初始密码至少12个字符';return;}
    s.busy=true;
    try{await api.request('/users',{method:'POST',data:{...s.form,account:s.form.account.trim(),name:s.form.name.trim(),className:s.form.className.trim()}});s.form.password='';s.dialog=null;await loadUsers();notice('账号已创建，可使用设置的密码登录');}
    catch(e){s.error=message(e)+(e.uncertain?'。请先查询账号列表，确认创建结果后再操作':'');}finally{s.busy=false;}
  }
  async function toggleUser(user){
    if(!canWrite())return;
    const r=await new Promise(resolve=>uni.showModal({title:user.enabled?'停用账号':'启用账号',content:user.enabled?'停用后该账号现有登录会话立即失效。':'确认允许该账号重新登录？',success:resolve}));
    if(!r.confirm)return;s.busy=true;await attempt(async()=>{await api.request('/users/'+user.id+'/enabled',{method:'PATCH',data:{enabled:!user.enabled}});await loadUsers(s.users.page);notice('账号状态已更新');});s.busy=false;
  }
  function close(){if(s.busy)return;if(s.form.password)s.form.password='';s.dialog=s.dialog==='apply'?'equipment':null;s.error='';}
  function pickupClock(){
    // #ifdef H5
    if(typeof document!=='undefined'&&document.hidden)return;
    // #endif
    prunePickupSlots();refreshPickupSlots();
  }
  function resumePickup(){
    // #ifdef H5
    if(typeof document!=='undefined'&&document.hidden)return;
    // #endif
    prunePickupSlots();refreshPickupSlots({force:true});if(s.user&&s.page==='duty'&&!s.dialog&&!s.busy)attempt(()=>loadDuties(s.duties.page));
  }
  pickupClockTimer=setInterval(pickupClock,1000);pickupClockTimer?.unref?.();
  // #ifdef H5
  if(typeof document!=='undefined')document.addEventListener('visibilitychange',resumePickup);
  if(typeof window!=='undefined')window.addEventListener('pageshow',resumePickup);
  // #endif
  function dispose(){disposed=true;stopLoginWatch();generation++;uni.$off('session-expired',expired);clearTimeout(noticeTimer);clearInterval(pickupClockTimer);
    // #ifdef H5
    if(typeof document!=='undefined')document.removeEventListener('visibilitychange',resumePickup);
    if(typeof window!=='undefined')window.removeEventListener('pageshow',resumePickup);
    // #endif
  }
  return {s,role,isAdmin,nav,heading,labels,roles,init,login,logout,enterGuest,leaveGuest,go,refresh,switchBorrowerView,loadCatalog,loadRecords,loadCounts,loadDuties,loadUsers,attempt,openEquipment,beginApply,setPickupProxy,setPickupMode,selectPickupProxy,selectPickupSlot,editBorrowPhone,submitBorrow,openRecord,loadProxySlots,refreshPickupSlots,prunePickupSlots,act,retryPending,acknowledgePending,saveProfile,openDuty,saveDuty,openUser,saveUser,toggleUser,close,dispose};
}
