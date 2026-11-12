<template>
  <view class="other-borrow-page">
    <view class="section-top">
      <view><text class="page-title">其他借用</text><text class="muted">历史申请保留原办理流程，新借用请使用登记入口。</text></view>
      <button role="button" class="btn secondary" :disabled="busy||loading" :aria-disabled="busy||loading" @click="reload">刷新申请</button>
    </view>
    <view v-if="error" class="error" role="alert">{{error}}</view>
    <view v-if="notice" class="success" role="status">{{notice}}</view>
    <template v-if="selected">
      <button role="button" class="text-btn detail-back" :disabled="busy" :aria-disabled="busy" @click="selected=null">‹ 返回其他借用</button>
      <view class="panel other-borrow-detail">
        <view class="card-title-row"><text class="detail-title">{{selected.name}}</text><text :class="['status',selected.status]">{{statusLabels[selected.status]}}</text></view>
        <view class="detail-lines">
          <text v-if="selected.model">型号：{{selected.model}}</text>
          <text>申请人：{{selected.user}} · {{selected.userAccount}}</text>
          <text v-if="selected.className">班级：{{selected.className}}</text>
          <text v-if="selected.phone">联系电话：{{selected.phone}}</text>
          <text>数量：{{selected.quantity}}件</text>
          <text>申请日期：{{formatBorrowDate(selected.createdAt)}}</text>
          <text v-if="selected.reviewedByName">审批人：{{selected.reviewedByName}}</text>
          <text v-if="selected.reviewedAt">审批时间：{{formatTime(selected.reviewedAt)}}</text>
          <text v-if="selected.issuedAt">领取日期：{{formatBorrowDate(selected.issuedAt)}}</text>
          <text v-if="selected.returnRequestedAt">申请归还：{{formatBorrowDate(selected.returnRequestedAt)}}</text>
          <text v-if="selected.returnedAt">归还日期：{{formatBorrowDate(selected.returnedAt)}}</text>
        </view>
        <view v-if="selected.functionDescription" class="other-borrow-description"><text class="block-title">功能</text><text>{{selected.functionDescription}}</text></view>
        <view class="other-borrow-description"><text class="block-title">用途</text><text>{{selected.purpose}}</text></view>
        <text v-if="selected.reason" class="record-hint">审批说明：{{selected.reason}}</text>
        <checkbox-group v-if="can('approve')" @change="physicalAvailable=$event.detail.value.includes('available')">
          <label class="check-row"><checkbox value="available" :checked="physicalAvailable"/><text>已确认有可借实物</text></label>
        </checkbox-group>
        <checkbox-group v-if="can('issue')||can('return-confirm')" @change="checked=$event.detail.value.includes('checked')">
          <label class="check-row"><checkbox value="checked" :checked="checked"/><text>{{can('issue')?'已现场核对领取人、器材及配件并完成发放':'已核对交回的器材及配件，确认完好'}}</text></label>
        </checkbox-group>
        <view v-if="can('reject')||can('return-reject')" class="field-group">
          <text>{{can('reject')?'拒绝原因（拒绝时必填）':'退回原因（退回时必填）'}}</text>
          <textarea v-model="reason" class="field textarea" maxlength="500" :placeholder="can('reject')?'例如：目前没有可借实物':'请说明需要补充或核对的事项'"/>
        </view>
        <view class="button-row">
          <button v-for="action in selected.allowedActions" :key="action" role="button"
            :class="['btn',{'secondary':['cancel','reject','return-reject'].includes(action)}]"
            :disabled="blocked||action==='approve'&&!physicalAvailable||['issue','return-confirm'].includes(action)&&!checked" :aria-disabled="blocked||action==='approve'&&!physicalAvailable||['issue','return-confirm'].includes(action)&&!checked"
            @click="perform(action)">{{actionLabels[action]}}</button>
        </view>
        <text v-if="!selected.allowedActions.length" class="muted">当前没有可办理的操作。</text>
        <view v-if="selected.events?.length" class="other-borrow-history">
          <text class="block-title">办理记录</text>
          <view v-for="event in selected.events" :key="event.id" class="event">
            <text>{{actionLabels[event.action]||'提交申请'}} · {{event.actorName}}</text><text class="small">{{formatTime(event.at)}}</text>
            <text v-if="event.reason" class="reason">{{event.reason}}</text>
          </view>
        </view>
      </view>
    </template>
    <template v-else>
      <view class="chips other-borrow-tabs" role="group" aria-label="其他借用栏目">

        <button role="button" :class="['chip',{selected:tab==='mine'}]" :disabled="busy" :aria-disabled="busy" @click="chooseTab('mine')">我的申请</button>
        <template v-if="staff">
          <button role="button" :class="['chip',{selected:tab==='review'}]" :disabled="busy" :aria-disabled="busy" @click="chooseTab('review')">申请审批</button>
          <button role="button" :class="['chip',{selected:tab==='ledger'}]" :disabled="busy" :aria-disabled="busy" @click="chooseTab('ledger')">借用台账</button>
        </template>
      </view>
      <view v-if="tab==='create'" class="panel other-borrow-form">
        <text class="block-title">我想申请</text>
        <text class="muted">请描述希望借用的实物。每次申请1件，提交后等待审批。</text>
        <text class="small">申请人 {{user.name}} · {{user.account}}</text>
        <view class="field-group"><text>器材名称 *</text><input v-model="form.name" class="field" placeholder="例如：示波器、显示器、笔记本电脑" maxlength="160"/></view>
        <view class="field-group"><text>型号（选填）</text><input v-model="form.model" class="field" placeholder="填写已了解或希望借用的型号" maxlength="160"/></view>
        <view class="field-group"><text>功能（选填）</text><textarea v-model="form.functionDescription" class="field textarea" placeholder="描述需要具备的功能或配套要求" maxlength="2000"/></view>
        <view class="field-group"><text>用途 *</text><textarea v-model="form.purpose" class="field textarea" placeholder="例如：课程设计、学科竞赛、课题研究、自主探索，请说明具体任务" maxlength="500"/></view>
        <button role="button" class="btn" :disabled="blocked" :aria-disabled="blocked" @click="submit">{{busy?'正在提交…':'提交借用申请'}}</button>
      </view>
      <template v-else>
        <view v-if="tab!=='review'" class="chips" role="group" aria-label="其他借用状态">
          <button v-for="status in statusOptions" :key="status" role="button" :class="['chip',{selected:statusFilter===status}]" :disabled="busy" :aria-disabled="busy" @click="chooseStatus(status)">{{status?statusLabels[status]:'全部'}}</button>
        </view>
        <text class="small other-borrow-loading" role="status">{{loading?'正在加载申请…':''}}</text>
        <view v-for="row in data.items" :key="row.id" class="panel other-borrow-row" :data-other-borrow-id="row.id">
          <view class="card-title-row"><text class="card-title">{{row.name}}</text><text :class="['status',row.status]">{{statusLabels[row.status]}}</text></view>
          <text v-if="row.model" class="muted">{{row.model}}</text>
          <text v-if="tab!=='mine'" class="small">申请人 {{row.user}} · {{row.userAccount}}<text v-if="row.className"> · {{row.className}}</text></text>
          <text class="other-borrow-purpose">{{row.purpose}}</text>
          <text class="small">申请 {{formatBorrowDate(row.createdAt)}} · {{row.quantity}}件</text>
          <text v-if="row.reason" class="small">审批说明 {{row.reason}}</text>
          <button role="button" class="btn secondary small" :disabled="busy" :aria-disabled="busy" @click="open(row)">查看与办理</button>
        </view>
        <view v-if="ready&&!loading&&!data.items.length" class="panel empty">{{tab==='review'?'暂无待审批的其他借用申请。':'暂无符合条件的其他借用申请。'}}</view>
        <ServerPager v-if="ready" :total="data.total" :page="data.page" :page-size="data.pageSize" :disabled="loading||busy" @change="load"/>
      </template>
    </template>
  </view>
</template>
<script setup>
import {computed,onMounted,onUnmounted,reactive,ref,watch} from 'vue';
import {api} from '../services/api.js';
import {formatBorrowDate} from '../services/borrow-dates.mjs';
import {createOtherBorrows} from '../services/other-borrows.mjs';
import ServerPager from './ServerPager.vue';

const props=defineProps({user:{type:Object,required:true},verified:Boolean,pending:Boolean});
const emit=defineEmits(['pending']);
const service=createOtherBorrows(api);
const staff=computed(()=>['admin','reviewer'].includes(props.user.role));
const tab=ref(staff.value?'review':'mine');
const statusFilter=ref(''),selected=ref(null),busy=ref(false),loading=ref(false),ready=ref(false),error=ref(''),notice=ref('');
const reason=ref(''),physicalAvailable=ref(false),checked=ref(false);
const data=reactive({items:[],total:0,page:1,pageSize:5});
const emptyForm=()=>({name:'',model:'',functionDescription:'',purpose:''});
const form=reactive(emptyForm());
const blocked=computed(()=>busy.value||!props.verified||props.pending||!!api.pending());
const statusLabels={pending:'待审批',approved:'待领取',rejected:'已拒绝',cancelled:'已取消',borrowed:'借用中',returning:'待归还',returned:'已归还'};
const statusOptions=['','pending','approved','borrowed','returning','returned','rejected','cancelled'];
const actionLabels={create:'提交申请',approve:'批准借用',reject:'拒绝申请',cancel:'取消申请',issue:'确认发放','return-request':'申请归还','return-confirm':'验收归还','return-reject':'退回归还'};
let loadRequest=0,disposed=false,timer,noticeTimer;
let waitingForWrite=api.pending()?.path?.startsWith('/other-borrow-requests')||false;

function formatTime(value){
  if(!value)return '—';
  const date=new Date(value);
  return Number.isNaN(date.getTime())?value:date.toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
}
function showNotice(value){
  if(disposed)return;
  notice.value=value;clearTimeout(noticeTimer);
  noticeTimer=setTimeout(()=>notice.value='',6000);
}
function report(failure){
  error.value=failure.message||'请求暂时未完成，请重试';
}
function can(action){return selected.value?.allowedActions?.includes(action);}
function resetChecks(){reason.value='';physicalAvailable.value=false;checked.value=false;}
async function chooseTab(value){
  loadRequest++;loading.value=false;tab.value=value;selected.value=null;statusFilter.value='';data.items=[];data.total=0;ready.value=false;error.value='';
  if(value!=='create')await load(1);
  uni.pageScrollTo({scrollTop:0,duration:0});
}
async function chooseStatus(value){statusFilter.value=value;await load(1);}
async function load(page=1,{quiet=false}={}){
  if(disposed||tab.value==='create')return;
  const request=++loadRequest;
  if(!quiet){loading.value=true;error.value='';}
  try{
    const result=await service.list({
      page,pageSize:tab.value==='mine'?5:10,mine:tab.value==='mine',
      status:tab.value==='review'?'pending':statusFilter.value,workflowMode:'legacy'
    });
    if(disposed||request!==loadRequest)return;
    if(page>1&&!result.items.length)return load(Math.max(1,Math.ceil(result.total/result.pageSize)),{quiet});
    Object.assign(data,result);ready.value=true;
  }catch(failure){if(!disposed&&request===loadRequest)report(failure);}
  finally{if(request===loadRequest)loading.value=false;}
}
async function reload(){
  if(selected.value){await open(selected.value);return;}
  await load(data.page);
}
async function submit(){
  if(blocked.value)return;
  busy.value=true;error.value='';
  try{
    await service.create(form);
    emit('pending',api.pending());if(disposed)return;
    Object.assign(form,emptyForm());tab.value='mine';statusFilter.value='';
    await load(1);showNotice('申请已提交，等待老师或管理员核实。');
  }catch(failure){
    report(failure);waitingForWrite=!!api.pending()?.path?.startsWith('/other-borrow-requests');emit('pending',api.pending());
  }finally{busy.value=false;}
}
async function open(row){
  if(busy.value)return;
  busy.value=true;error.value='';
  try{
    const result=await service.detail(row.id);
    if(disposed)return;
    selected.value=result;resetChecks();uni.pageScrollTo({scrollTop:0,duration:0});
  }
  catch(failure){report(failure);}
  finally{busy.value=false;}
}
const confirmations={
  approve:'已确认有可借实物，批准后请安排领取。',
  reject:'确认拒绝这条借用申请，并将原因告知申请人。',
  cancel:'确认取消这条借用申请。',
  issue:'确认已经现场核对领取人并发放器材及配件。',
  'return-request':'提交归还申请后，请联系老师或管理员交回实物。',
  'return-confirm':'确认已收回器材和配件，完成归还验收。',
  'return-reject':'确认退回本次归还申请，并将原因告知申请人。'
};
async function perform(action){
  if(blocked.value||!can(action))return;
  if(['reject','return-reject'].includes(action)&&!reason.value.trim()){error.value='请填写处理原因';return;}
  const confirmed=await new Promise(resolve=>uni.showModal({
    title:actionLabels[action],content:confirmations[action],success:result=>resolve(result.confirm),fail:()=>resolve(false)
  }));
  if(disposed||!confirmed||blocked.value)return;
  busy.value=true;error.value='';
  try{
    const result=await service.action(selected.value,action,{reason:reason.value,physicalAvailable:physicalAvailable.value,checked:checked.value});
    emit('pending',api.pending());if(disposed)return;
    selected.value=result;resetChecks();await load(data.page);showNotice(actionLabels[action]+'成功');
  }catch(failure){
    report(failure);waitingForWrite=!!api.pending()?.path?.startsWith('/other-borrow-requests');emit('pending',api.pending());
    if(failure.status===409){
      try{selected.value=await service.detail(selected.value.id);resetChecks();await load(data.page);}catch{}
    }
  }finally{busy.value=false;}
}
watch(()=>props.pending,async(value,previous)=>{
  if(waitingForWrite&&previous&&!value){
    waitingForWrite=false;selected.value=null;
    if(tab.value==='create')tab.value='mine';
    await load(1);showNotice('请核对更新后的申请记录，确认上次操作结果。');
  }
});
onMounted(()=>{
  if(tab.value!=='create')load(1);
  timer=setInterval(()=>{
    // #ifdef H5
    if(document.visibilityState!=='visible')return;
    // #endif
    if(!busy.value&&!loading.value&&!selected.value&&!props.pending&&tab.value!=='create')load(data.page,{quiet:true});
  },30000);
});
onUnmounted(()=>{disposed=true;loadRequest++;clearInterval(timer);clearTimeout(noticeTimer);});
defineExpose({reload});
</script>
<style scoped>
@import "../pages/index/live.css";
@import "../pages/index/restore-v1.css";
@import "../pages/index/college-refinement.css";

.other-borrow-form{max-width:850px}
.other-borrow-form>.block-title{font-size:22px}
.other-borrow-form>.muted{margin:12px 0}
.other-borrow-form>.small{display:block;margin-bottom:18px}
.other-borrow-form .field-group>text:first-child{display:block;font-weight:600;margin-bottom:8px}
.other-borrow-form .textarea{height:110px;min-height:110px}
.other-borrow-tabs{margin-bottom:20px}
.other-borrow-loading{display:block;min-height:28px}
.other-borrow-row{padding:20px}
.other-borrow-row>.small{display:block;margin:7px 0}
.other-borrow-row>.btn{margin-top:12px}
.other-borrow-purpose,.other-borrow-description>text{display:block;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}
.other-borrow-purpose{margin:12px 0}
.other-borrow-description{margin:22px 0}
.other-borrow-description>.block-title{margin-bottom:8px}
.other-borrow-history{margin-top:28px}
.other-borrow-detail .button-row{margin:20px 0}
@media(max-width:760px){
  .other-borrow-form>.block-title{font-size:20px}
  .other-borrow-tabs{gap:7px;margin-bottom:14px}
  .other-borrow-tabs>.chip{flex:1 1 auto;text-align:center}
  .other-borrow-row{padding:16px}
  .other-borrow-description{margin:18px 0}
}
</style>
