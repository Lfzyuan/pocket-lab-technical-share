<template>
<view class="borrow-management">
  <view class="section-top"><view><text class="page-title">{{mode==='review'?'器材申请审批':mode==='records'?'器材借用记录':'器材领取与归还'}}</text></view><view class="button-row"><button v-if="mode==='handover'" role="button" class="btn" :disabled="blocked||running" @click="openQuickScan">便捷扫码</button><button role="button" class="btn secondary" :disabled="running||loading" @click="load(data.page)">刷新记录</button></view></view>
  <view class="stats-grid management-stats"><button v-for="item in states.slice(0,4)" :key="item.value" role="button" class="stat" :disabled="running" @click="pickStatus(item.value)"><text class="stat-number">{{counts[item.value]??'—'}}</text><text class="muted">{{item.label}}</text></button></view>
  <view class="panel management-filter-panel">
    <view class="management-search"><input v-model="filters.q" class="field" maxlength="100" placeholder="搜索姓名、学号、器材、资产编号或借用单号" @confirm="search"/><button role="button" class="btn" :disabled="running||loading" :aria-disabled="running||loading" @click="search">查询</button><button role="button" class="btn secondary" :disabled="running" @click="reset">重置</button></view>
    <view class="management-filter-grid"><view class="field-group"><text>器材</text><ChoiceField v-model="filters.equipmentId" :options="equipmentOptions" :disabled="running"/></view><view class="field-group"><text>申请开始日期</text><DateTimeField v-model="filters.from" mode="date"/></view><view class="field-group"><text>申请结束日期</text><DateTimeField v-model="filters.to" mode="date"/></view></view>
    <view class="chips management-statuses" role="group" aria-label="多选借用状态"><button role="button" :class="['chip',{selected:!filters.statuses.length}]" :disabled="running" @click="filters.statuses=[]">全部</button><button v-for="item in states" :key="item.value" role="button" :class="['chip',{selected:filters.statuses.includes(item.value)}]" :aria-pressed="filters.statuses.includes(item.value)" :disabled="running" @click="toggleStatus(item.value)">{{item.label}}</button></view>
  </view>
  <view v-if="error" class="error" role="alert">{{error}}</view><view v-if="notice" class="success" role="status">{{notice}}</view>
  <QuickAssetHandover v-if="quickScan" :blocked="blocked" @pending="emit('pending',$event)" @close="quickScan=false" @changed="quickChanged"/>
  <view class="management-bulk"><ChoiceField class="management-bulk-action" v-model="bulkAction" :options="bulkOptions" :disabled="running"/><button role="button" class="btn secondary" :disabled="blocked||running||loading" :aria-disabled="blocked||running||loading" @click="previewBatch">预览全部筛选记录</button><text class="small">共 {{data.total}} 条 · 批量范围包含筛选结果的所有分页</text></view>
  <view v-if="batch" class="panel management-preview" role="region" aria-label="批量办理预览"><text class="block-title">批量{{actionLabels[batch.action]}}</text><text class="muted">筛选结果 {{batch.total}} 条，可办理 {{batch.eligible}} 条，跳过 {{batch.skipped}} 条。</text><text class="muted">{{batch.action==='issue'?'发放时逐件扫描、手输或确认无编号。':batch.action==='return-confirm'?batch.items.filter(item=>item.assetNumber).length+' 条归还须核对已绑定的编号。':''}}每条办理均按预览时版本校验。</text><view v-if="batch.action==='reject'" class="field-group"><text>统一拒绝原因</text><textarea v-model="batchReason" class="field textarea" maxlength="500"/></view><view class="button-row"><button role="button" class="btn" :disabled="blocked||running||!batch.eligible" :aria-disabled="blocked||running||!batch.eligible" @click="confirmBatch">确认批量{{actionLabels[batch.action]}} {{batch.eligible}} 条</button><button role="button" class="btn secondary" :disabled="running" @click="batch=null">取消预览</button></view></view>
  <view v-if="queue.length" class="warning" role="status"><text>正在{{actionLabels[queueAction]}}：已完成 {{completed}} / {{queue.length}} 条。</text><button role="button" class="text-btn" :disabled="busy" @click="cancelQueue">停止后续办理</button></view>
  <text v-if="loading" class="loading">正在加载借用记录…</text>
  <view v-if="extension" class="panel management-extension" role="region" aria-label="延长领取时间">
    <text class="block-title">{{nameOf(extension)}} · 领取截止时间</text><text class="muted">当前：{{extension.pickupDeadlineAt?formatTime(extension.pickupDeadlineAt):'尚未设置'}}。请填写新的截止时间和实际延期原因。</text>
    <view class="time-fields"><DateTimeField v-model="extensionDate" mode="date"/><DateTimeField v-model="extensionTime" mode="time"/></view><textarea v-model="extensionReason" class="field textarea" maxlength="500" placeholder="延期说明（必填）"/>
    <view class="button-row"><button role="button" class="btn" :disabled="blocked||running" @click="saveExtension">保存领取延期</button><button role="button" class="btn secondary" @click="extension=null">取消</button></view>
  </view>
  <view class="management-table" role="table" aria-label="器材借用办理表">
    <view class="management-table-head" role="row"><text role="columnheader">器材 / 型号</text><text role="columnheader">借用人</text><text role="columnheader">领取安排</text><text role="columnheader">状态</text><text role="columnheader">操作</text></view>
    <view v-for="row in data.items" :key="loanRecordKey(row)" class="management-row" role="row" :data-record-id="row.id" :data-record-key="loanRecordKey(row)">
      <view class="management-item" role="cell"><text class="card-title">{{nameOf(row)}}</text><text v-if="modelOf(row)" class="small">{{modelOf(row)}}</text></view>
      <view class="management-person" role="cell"><text>{{row.user}}</text></view>
      <view class="management-pickup" role="cell"><text>{{isOtherLoan(row)?'其他借用':row.pickupMode==='agreed'?'与老师约定':'值班领取'}}</text><text v-if="pickupTime(row)" class="small">{{pickupTime(row)}}</text></view>
      <view class="management-state" role="cell"><text :class="['status',row.status]">{{statusLabel(row.status)}}</text></view>
      <view class="management-row-actions" role="cell"><button v-for="action in rowActions(row)" :key="action" role="button" class="btn management-action" :disabled="blocked||running" :aria-disabled="blocked||running" @click="startSingle(row,action)">{{actionLabels[action]}}</button><button v-if="row.allowedActions?.includes('extend-pickup')" role="button" class="text-btn" :disabled="blocked||running" @click="openExtension(row)">延长领取时间</button><button role="button" class="text-btn" :disabled="running" @click="$emit('open',row)">查看与办理</button></view>
    </view>
  </view>
  <view v-if="!loading&&!data.items.length" class="panel empty">暂无符合条件的记录。</view><ServerPager :total="data.total" :page="data.page" :page-size="data.pageSize" :disabled="loading||running" @change="load"/>
  <view v-if="scanItem" class="panel management-scan" role="region" aria-label="办理资产编号">
    <text class="block-title">{{actionLabels[queueAction]}} · {{nameOf(scanItem)}}</text>
    <text class="muted">借用人 {{scanItem.user}} · 已完成 {{completed}} / {{queue.length}} 条</text>
    <AssetBarcodeScanner :key="loanRecordKey(scanItem)+':'+queueAction+':'+scanItem.version" ref="scanner" :title="actionLabels[queueAction]" :context="nameOf(scanItem)+' · '+scanItem.user" :confirm-label="actionLabels[queueAction]" :disabled="blocked||busy" @scanned="receiveCode"/>
    <view class="button-row">
      <button v-if="queueAction==='issue'" role="button" class="btn secondary" :disabled="blocked||busy" @click="skipAsset">实物无编号，确认发放</button>
      <button role="button" class="text-btn" :disabled="busy" @click="cancelQueue">停止后续办理</button>
    </view>
  </view>
  <view v-if="rejectItem" class="panel management-reject" role="region" aria-label="拒绝申请"><text class="block-title">拒绝 {{nameOf(rejectItem)}} 的申请</text><textarea v-model="singleReason" class="field textarea" maxlength="500" placeholder="请填写拒绝原因"/><view class="button-row"><button role="button" class="btn" :disabled="blocked||running" @click="confirmReject">确认拒绝</button><button role="button" class="btn secondary" @click="rejectItem=null">取消</button></view></view>
</view>
</template>
<script setup>
import {computed,nextTick,onMounted,onUnmounted,reactive,ref,watch} from 'vue';
import {api} from '../services/api.js';
import {formatBorrowDate} from '../services/borrow-dates.mjs';
import {shanghaiInstant,shanghaiParts} from '../services/other-borrows.mjs';
import {borrowActionLabels,borrowFilters,createBorrowManagement,isOtherLoan,loanRecordKey} from '../services/borrow-management.mjs';
import ChoiceField from './ChoiceField.vue';import DateTimeField from './DateTimeField.vue';import ServerPager from './ServerPager.vue';import AssetBarcodeScanner from './AssetBarcodeScanner.vue';
import QuickAssetHandover from './QuickAssetHandover.vue';
const props=defineProps({user:Object,verified:Boolean,pending:Boolean,mode:{type:String,default:'handover'}}),emit=defineEmits(['pending','open','changed']);
const service=createBorrowManagement(api),actionLabels=borrowActionLabels;
const states=[{value:'pending',label:'申请中'},{value:'approved',label:'待领取'},{value:'borrowed',label:'借用中'},{value:'returning',label:'待归还'},{value:'returned',label:'已归还'},{value:'rejected',label:'已拒绝'},{value:'cancelled',label:'已取消'},{value:'closed',label:'异常结单'}];
const defaults=()=>({q:'',equipmentId:'',from:'',to:'',statuses:props.mode==='review'?['pending']:props.mode==='handover'?['approved','borrowed','returning']:[]});
const filters=reactive(defaults()),applied=ref(borrowFilters(filters)),data=reactive({items:[],total:0,page:1,pageSize:10}),counts=reactive({}),equipment=ref([]),error=ref(''),notice=ref(''),loading=ref(false),busy=ref(false);
const bulkAction=ref(props.mode==='review'?'approve':'issue'),batch=ref(null),batchReason=ref(''),rejectItem=ref(null),singleReason=ref('');
const extension=ref(null),extensionDate=ref(''),extensionTime=ref(''),extensionReason=ref('');
const queue=ref([]),queueAction=ref(''),completed=ref(0),scanItem=ref(null),scanner=ref(null);let reason='',disposed=false,revision=0,countRevision=0,operation=0,timer;
const blocked=computed(()=>!props.verified||props.pending||!!api.pending());
const quickScan=ref(false);
const running=computed(()=>quickScan.value||busy.value||!!scanItem.value||queue.value.length>0);
const equipmentOptions=computed(()=>[{value:'',label:'全部器材'},...equipment.value.map(row=>({value:row.id,label:row.name+(row.model?' · '+row.model:'')}))]);
const bulkOptions=computed(()=>Object.entries(actionLabels).map(([value,label])=>({value,label})));
function nameOf(row){return row.equipmentName||row.name||equipment.value.find(item=>item.id===row.equipmentId)?.name||row.equipmentId;}
function modelOf(row){return row.equipmentModel||row.model||equipment.value.find(item=>item.id===row.equipmentId)?.model||'';}
function statusLabel(value){return states.find(item=>item.value===value)?.label||value;}
function formatTime(value){return value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'—';}
function pickupTime(row){if(isOtherLoan(row))return '';if(row.pickupMode==='agreed')return row.agreedPickupDate||row.agreedPickupAt?formatBorrowDate(row.agreedPickupDate||row.agreedPickupAt):'另行约定';const slot=row.pickupSlot;return slot?formatBorrowDate(slot.date):row.pickupProxyId?'待代领人选时段':'';}
function rowActions(row){return Object.keys(actionLabels).filter(action=>row.allowedActions?.includes(action));}
function openQuickScan(){
 if(blocked.value||running.value)return;
 batch.value=null;rejectItem.value=null;extension.value=null;quickScan.value=true;
}
function quickChanged(){Promise.all([load(data.page),loadCounts()]);emit('changed');}
function report(e){error.value=e.message||'办理未完成，请重试';emit('pending',api.pending());}
async function load(page=1){const current=++revision;loading.value=true;try{const result=await service.list(applied.value,page);if(!disposed&&current===revision)Object.assign(data,result);}catch(e){if(current===revision)report(e);}finally{if(current===revision)loading.value=false;}}
async function loadCounts(){const current=++countRevision;try{const query={...applied.value};delete query.statuses;const result=await service.summary(query);if(!disposed&&current===countRevision)Object.assign(counts,result.counts||result);}catch{/* List remains usable when counters cannot refresh. */}}
async function loadEquipment(){try{const all=[];let page=1;for(;;){const {data:result}=await api.request('/equipment?pageSize=100&page='+page++);all.push(...result.items);if(all.length>=result.total||!result.items.length)break;}if(!disposed)equipment.value=all;}catch{/* Detail and server-provided names remain available. */}}
async function search(){if(running.value)return;if(filters.from&&filters.to&&filters.from>filters.to){error.value='开始日期不能晚于结束日期';return;}batch.value=null;error.value='';applied.value=borrowFilters(filters);await Promise.all([load(1),loadCounts()]);}
function reset(){Object.assign(filters,defaults());search();}
function toggleStatus(value){filters.statuses=filters.statuses.includes(value)?filters.statuses.filter(item=>item!==value):[...filters.statuses,value];}
function pickStatus(value){filters.statuses=[value];search();}
async function openExtension(row){
 if(blocked.value||running.value)return;busy.value=true;error.value='';
 try{const fresh=await service.detail(row);if(disposed)return;if(!fresh.allowedActions?.includes('extend-pickup'))throw Error('当前记录不能延期，请刷新');extension.value=fresh;const parts=shanghaiParts(fresh.pickupDeadlineAt);extensionDate.value=parts.date;extensionTime.value=parts.time;extensionReason.value='';await nextTick();uni.pageScrollTo({scrollTop:0,duration:0});}catch(e){report(e);}finally{busy.value=false;}
}
async function saveExtension(){
 if(blocked.value||running.value||!extension.value)return;busy.value=true;error.value='';
 try{await service.execute(extension.value,'extend-pickup',{pickupDeadlineAt:shanghaiInstant(extensionDate.value,extensionTime.value),reason:extensionReason.value});emit('pending',api.pending());if(!disposed){extension.value=null;notice.value='领取截止时间已更新，延期说明已记录。';await load(data.page);}}catch(e){report(e);}finally{busy.value=false;}
}
async function previewBatch(){if(blocked.value||running.value)return;busy.value=true;error.value='';try{batch.value=await service.preview(bulkAction.value,applied.value);batchReason.value='';}catch(e){report(e);}finally{busy.value=false;}}
async function confirmBatch(){if(blocked.value||running.value||!batch.value)return;if(batch.value.action==='reject'&&!batchReason.value.trim()){error.value='请填写拒绝原因';return;}queue.value=batch.value.items.map(row=>({...row}));queueAction.value=batch.value.action;reason=batchReason.value;completed.value=0;batch.value=null;notice.value='';await next();}
async function startSingle(row,action){if(blocked.value||running.value)return;busy.value=true;error.value='';notice.value='';try{const fresh=await service.detail(row);if(disposed)return;if(!fresh.allowedActions?.includes(action))throw Error('记录状态已变化，请刷新后办理');if(action==='reject'){rejectItem.value=fresh;singleReason.value='';return;}queue.value=[fresh];queueAction.value=action;completed.value=0;reason='';}catch(e){report(e);}finally{busy.value=false;}if(queue.value.length)await next();}
async function confirmReject(){if(!singleReason.value.trim()){error.value='请填写拒绝原因';return;}queue.value=[rejectItem.value];queueAction.value='reject';completed.value=0;reason=singleReason.value;rejectItem.value=null;await next();}
async function next(){
  const turn=operation;
  while(!disposed&&turn===operation&&completed.value<queue.value.length){
    if(blocked.value){cancelQueue();return;}
    const item=queue.value[completed.value];
    if(queueAction.value==='issue'||queueAction.value==='return-confirm'&&item.assetNumber){
      scanItem.value=item;await nextTick();
      if(!disposed&&turn===operation)uni.pageScrollTo({selector:'.management-scan',duration:0});
      return;
    }
    if(!await execute(item,undefined,turn))return;
  }
  if(!disposed&&turn===operation&&queue.value.length){notice.value='已完成 '+completed.value+' 条'+actionLabels[queueAction.value]+'。';queue.value=[];await Promise.all([load(data.page),loadCounts()]);emit('changed');}
}
async function execute(item,code,turn){
  if(disposed||turn!==operation||blocked.value)return false;
  busy.value=true;
  try{await service.execute(item,queueAction.value,{assetNumber:code,reason});emit('pending',api.pending());if(disposed||turn!==operation)return false;completed.value++;return true;}
  catch(e){if(!disposed){report(e);notice.value='本次已完成 '+completed.value+' 条，后续办理已暂停。';operation++;queue.value=[];scanItem.value=null;await load(data.page);}return false;}
  finally{busy.value=false;}
}
async function receiveCode(code){
  const item=scanItem.value,turn=operation;
  if(!item||blocked.value||busy.value)return;
  if(queueAction.value==='return-confirm'&&code!==item.assetNumber){error.value='归还资产编号与发放记录不匹配，请重新扫描或手输';return;}
  error.value='';scanItem.value=null;await nextTick();
  if(disposed||turn!==operation||blocked.value)return;
  if(await execute(item,code,turn))await next();
}
async function skipAsset(){
  const item=scanItem.value,turn=operation;
  if(queueAction.value!=='issue'||!item||blocked.value||busy.value)return;
  const confirmed=await new Promise(resolve=>uni.showModal({title:'确认发放',content:'请确认实物无编号，已现场核对领取人、器材及配件，并发放1件。',success:result=>resolve(result.confirm),fail:()=>resolve(false)}));
  if(confirmed&&scanItem.value===item&&operation===turn)await receiveCode();
}
function cancelQueue(){operation++;scanner.value?.cancel();scanItem.value=null;queue.value=[];notice.value='已停止后续办理，已完成 '+completed.value+' 条。';load(data.page);}
watch(()=>props.pending,(value,previous)=>{if(previous&&!value&&!running.value)load(data.page);});
onMounted(async()=>{await Promise.all([load(1),loadCounts(),loadEquipment()]);timer=setInterval(()=>{
  // #ifdef H5
  if(document.hidden)return;
  // #endif
  if(!running.value&&!blocked.value)Promise.all([load(data.page),loadCounts()]);
},30000);});
onUnmounted(()=>{disposed=true;revision++;countRevision++;operation++;clearInterval(timer);scanner.value?.cancel();});
defineExpose({setStatus:pickStatus,refresh:()=>!running.value&&Promise.all([load(data.page),loadCounts()])});
</script>
<style scoped>
@import '../pages/index/live.css';
@import '../pages/index/restore-v1.css';
@import '../pages/index/college-refinement.css';
.management-stats .stat{min-width:0}.management-search{display:flex;gap:10px}.management-search>.field{flex:1;min-width:0}.management-filter-grid{display:grid;grid-template-columns:2fr 1fr 1fr;gap:18px}.management-statuses{margin-bottom:15px}.management-bulk{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:18px 0}.management-bulk>.management-bulk-action{max-width:220px;width:220px}.management-table{background:#fff;border:1px solid #dce4ed;border-radius:10px;overflow:hidden}.management-table-head,.management-row{display:grid;grid-template-columns:minmax(180px,1.7fr) minmax(80px,.7fr) minmax(130px,1fr) minmax(75px,.6fr) minmax(160px,1.2fr);gap:14px;padding:16px;margin:0;align-items:start}.management-table-head{background:#f1f5fa;font-size:12px;font-weight:600;color:#58718f}.management-row{border-bottom:1px solid #e4eaf1;font-size:13px}.management-row:last-child{border:0}.management-row>view{min-width:0}.management-row .small{display:block;overflow-wrap:anywhere;margin-top:6px}.management-row .card-title{font-size:14px}.management-row-actions{display:flex;gap:9px;flex-direction:column;align-items:flex-start}.management-row-actions .btn{width:100%}.borrow-management .management-row-actions .management-action{color:#fff;font-size:12px;min-height:36px;padding:7px 13px}.borrow-management .management-row-actions .management-action[disabled],.borrow-management .management-row-actions .management-action[aria-disabled="true"]{color:#526579;background:#eef1f6;border-color:#dce3ee}.management-extension .time-fields{margin:16px 0}.management-extension .textarea{margin-top:12px}.management-reject{margin-top:20px}.management-reject .textarea{margin-top:15px}
@media(max-width:1100px) and (min-width:761px){.management-table{overflow-x:auto}.management-table-head,.management-row{min-width:850px}}
@media(max-width:760px){.management-stats{grid-template-columns:repeat(4,minmax(0,1fr));gap:6px}.management-stats .stat{padding:12px 5px;text-align:center}.management-stats .stat-number{font-size:23px}.management-stats .muted{font-size:11px}.management-search{flex-wrap:wrap}.management-search>.field{flex-basis:100%}.management-filter-grid{grid-template-columns:1fr 1fr;gap:0 12px}.management-filter-grid>.field-group:first-child{grid-column:1/-1}.management-statuses{gap:6px}.management-filter-panel>.small{display:block}.management-table{background:transparent;border:0;overflow:visible}.management-table-head{display:none}.management-row{grid-template-columns:1fr 1fr;background:white;border:1px solid #dce4ed;border-radius:10px;margin-bottom:12px;padding:16px;gap:13px}.management-row-actions{grid-column:1/-1;flex-direction:row;flex-wrap:wrap;align-items:center}.management-row-actions .btn{width:auto;min-height:44px}.management-bulk .management-bulk-action{max-width:none;flex:1}.management-bulk>.small{flex-basis:100%}.management-preview .button-row{flex-direction:column}.management-preview .btn{width:100%}}
.management-row{padding:14px 16px;gap:14px}.management-row .small{margin-top:3px}.management-row-actions{flex-direction:row;flex-wrap:wrap;gap:7px 12px;align-items:center}.management-row-actions .btn{width:auto}.borrow-management .management-row-actions .text-btn{font-size:12px;min-height:36px;line-height:1.5}.management-item .card-title{line-height:1.55}.management-person,.management-pickup{line-height:1.6}
@media(max-width:1100px) and (min-width:761px){.management-table-head,.management-row{min-width:740px}}
@media(max-width:760px){.borrow-management .stats-grid.management-stats{grid-template-columns:repeat(4,minmax(0,1fr));gap:6px}.borrow-management .management-stats .stat{padding:11px 4px;text-align:center;min-width:0}.borrow-management .management-stats .stat-number{font-size:23px}.borrow-management .management-stats .muted{font-size:11px}.management-row{grid-template-columns:minmax(0,1fr) auto;grid-template-areas:"item state" "person pickup" "actions actions";padding:13px 14px;gap:7px 12px;margin-bottom:10px;align-items:start}.management-item{grid-area:item}.management-person{grid-area:person;font-size:13px}.management-pickup{grid-area:pickup;text-align:right;font-size:12px}.management-state{grid-area:state;justify-self:end}.management-row-actions{grid-area:actions;justify-content:flex-start;align-items:center;border-top:1px solid #e5eaf2;padding-top:7px;margin-top:3px;gap:6px 10px}.management-row .card-title{font-size:14px}.management-item .small,.management-pickup .small{font-size:11px;line-height:1.5;margin-top:2px}.borrow-management .management-row-actions .management-action{min-height:44px;padding:7px 12px}.borrow-management .management-row-actions .text-btn{min-height:44px;font-size:12px}.management-state .status{font-size:11px;padding:3px 7px}.management-filter-panel{padding:16px}.management-filter-grid{gap:0 12px}.management-filter-panel .field-group{margin:12px 0}.management-statuses{gap:5px;margin-bottom:10px}.management-statuses .chip{padding:5px 9px;font-size:11px}.management-filter-panel>.small{font-size:11px;line-height:1.55}.management-bulk{gap:8px;margin:14px 0}.management-bulk>.small{font-size:11px}}
.management-scan{margin-top:18px}.management-scan .button-row{margin-top:12px}
</style>
