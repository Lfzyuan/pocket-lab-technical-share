<template>
  <view class="other-borrow-page">
    <button role="button" class="text-btn detail-back" @click="recordId?$emit('close'):$emit('navigate','equipment')">{{recordId?'‹ 返回'+returnLabel:'‹ 返回器材借用'}}</button>
    <view v-if="!recordId" class="section-top">
      <view><text class="page-title">其他借用</text><text class="muted">登记已与老师沟通的借用，提交后在“我的借用”跟进和申请归还。</text></view>
      <button v-if="tab!=='create'&&tab!=='legacy'" role="button" class="btn secondary" :disabled="busy||loading" @click="reload">刷新记录</button>
    </view>
    <view v-if="!recordId" class="chips other-borrow-tabs" role="group" aria-label="其他借用栏目">
      <button v-for="item in tabs" :key="item.id" role="button" :class="['chip',{selected:tab===item.id}]" :disabled="busy" :aria-pressed="tab===item.id" @click="changeTab(item.id)">{{item.label}}</button>
    </view>
    <view v-if="error" class="error" role="alert">{{error}}</view>
    <view v-if="notice" class="success" role="status">{{notice}}</view>
    <button v-if="recordId&&!selected&&!loading&&error" role="button" class="btn secondary" @click="open({id:recordId})">重新读取记录</button>
    <LegacyOtherBorrowPage v-if="tab==='legacy'" :user="user" :verified="verified" :pending="pending" @pending="$emit('pending',$event)"/>
    <view v-else-if="tab==='create'" class="panel other-registration-form">
      <text class="block-title">填写借用登记</text>
      <text class="muted">每条登记1件。请先与老师沟通，提交后计为借用中。</text>
      <view class="field-group"><text>器材名称 *</text><input v-model="form.name" class="field" maxlength="160" placeholder="请输入器材名称"/></view>
      <view class="field-group"><text>老师 *</text><TeacherChoice v-model="form.teacherUserId" required :disabled="blocked"/></view>
      <view class="field-group"><text>用途（选填）</text><textarea v-model="form.purpose" class="field textarea" maxlength="500"/><text class="small">{{form.purpose.length}} / 500</text></view>
      <button role="button" class="btn" :disabled="blocked" :aria-disabled="blocked" @click="submit">{{busy?'正在提交…':'提交借用登记'}}</button>
    </view>
    <template v-else-if="selected">
      <button v-if="!recordId" role="button" class="text-btn detail-back" :disabled="busy" @click="selected=null">‹ 返回借用记录</button>
      <view class="panel registration-row">
        <view class="card-title-row"><text class="detail-title">{{selected.name}}</text><text :class="['status',selected.status]">{{statusLabels[selected.status]}}</text></view>
        <view class="detail-lines">
          <text>借用人：{{selected.user}}</text>
          <text v-if="selected.teacherName">老师：{{selected.teacherName}}</text><text v-if="selected.model">型号：{{selected.model}}</text><text v-if="selected.functionDescription">功能：{{selected.functionDescription}}</text>
          <text v-if="selected.expectedReturnMonth">预计归还：{{selected.expectedReturnMonth}}</text>
          <text v-if="selected.purpose">用途：{{selected.purpose}}</text>
          <text>提交时间：{{formatBorrowMinute(selected.createdAt)}}</text>
          <text v-if="selected.issuedAt">实际发放：{{formatBorrowMinute(selected.issuedAt)}}</text>
          <text v-if="selected.returnedAt">实际归还：{{formatBorrowMinute(selected.returnedAt)}}</text>
          <text v-if="selected.assetNumber">资产编号：{{selected.assetNumber}}</text>
          <text v-if="selected.issueRemark">发放备注：{{selected.issueRemark}}</text>
        </view>
        <view v-if="can('issue')||can('return-confirm')&&selected.assetNumber" class="field-group">
          <text>{{can('issue')?'实物资产编号（无编号可留空）':'归还核对编号'}}</text>
          <input v-model="assetNumber" class="field" maxlength="100" placeholder="扫描或手动输入资产编号"/>
          <AssetBarcodeScanner :key="selected.id+':'+selected.status" :disabled="blocked" @scanned="assetNumber=$event"/>
        </view>
        <view v-if="can('issue')" class="field-group"><text>发放备注（选填）</text><textarea v-model="remark" class="field textarea" maxlength="500"/></view>
        <checkbox-group v-if="can('approve')" @change="physicalAvailable=$event.detail.value.includes('available')"><label class="check-row"><checkbox value="available" :checked="physicalAvailable"/><text>已确认有可借实物</text></label></checkbox-group>
        <view v-if="can('reject')||can('return-reject')" class="field-group"><text>拒绝 / 退回原因</text><textarea v-model="reason" class="field textarea" maxlength="500"/></view>
        <view class="button-row">
          <button v-for="action in selected.allowedActions" :key="action" role="button" :class="['btn',{secondary:['cancel','return-reject'].includes(action)}]" :disabled="blocked" @click="perform(action)">{{actionLabels[action]||action}}</button>
        </view>
        <view v-if="selected.events?.length" class="other-history">
          <text class="block-title">办理记录</text>
          <view v-for="event in selected.events" :key="event.id" class="event"><text>{{actionLabels[event.action]||'提交借用登记'}} · {{statusLabels[event.toStatus]||''}}</text><text class="small">{{formatBorrowMinute(event.at)}}</text><text v-if="event.reason" class="reason">{{event.reason}}</text></view>
        </view>
      </view>
    </template>
    <text v-else-if="recordId&&loading" class="loading">正在读取借用记录…</text>
    <template v-else-if="!recordId">
      <view class="panel registration-filters">
        <view class="search-box"><input v-model="keyword" class="field" placeholder="搜索器材、姓名或账号" @confirm="load(1)"/><button role="button" class="btn secondary" :disabled="loading" @click="load(1)">搜索</button></view>
        <view v-if="tab==='checkout'||tab==='registration'" class="chips">
          <button v-for="item in statusOptions" :key="item.value" role="button" :class="['chip',{selected:status===item.value}]" @click="status=item.value;load(1)">{{item.label}}</button>
        </view>
      </view>
      <text v-if="loading" class="loading">正在加载记录…</text>
      <view v-for="row in data.items" :key="row.id" class="panel registration-row" :data-other-borrow-id="row.id">
        <view class="card-title-row"><text class="card-title">{{row.name}}</text><text :class="['status',row.status]">{{statusLabels[row.status]||row.status}}</text></view>
        <template v-if="tab==='registration'">
          <text v-if="row.model" class="muted">型号：{{row.model}}</text><text class="muted">登记人：{{row.user}} · {{row.userAccount}}</text>
          <text v-if="row.purpose" class="muted">用途：{{row.purpose}}</text><text v-if="row.functionDescription" class="muted">功能：{{row.functionDescription}}</text>
          <text class="small">实际借出：{{formatBorrowDate(row.issuedDate||row.issuedAt)}}</text><text class="small">实际归还：{{formatBorrowDate(row.returnedDate||row.returnedAt)}}</text><text class="small">登记时间：{{formatBorrowMinute(row.createdAt)}}</text>
          <text v-if="row.confirmedAt" class="small">{{row.confirmedByName}} 确认于 {{formatBorrowMinute(row.confirmedAt)}}</text>
          <button v-if="row.allowedActions?.includes('confirm')" role="button" class="btn" :disabled="blocked" @click="confirmRegistration(row)">确认登记</button>
        </template>
        <template v-else>
          <text v-if="staff" class="muted">借用人：{{row.user}}</text><text class="muted">老师：{{row.teacherName||'无'}}</text>
          <text v-if="row.expectedReturnMonth" class="small">预计归还：{{row.expectedReturnMonth}}</text>
          <text class="small">提交时间：{{formatBorrowMinute(row.createdAt)}}</text>
          <button role="button" class="btn secondary small" :disabled="busy" @click="open(row)">查看与办理</button>
        </template>
      </view>
      <view v-if="!loading&&!data.items.length" class="panel empty">暂无符合条件的记录。</view>
      <ServerPager :total="data.total" :page="data.page" :page-size="data.pageSize" :disabled="loading||busy" @change="load"/>
    </template>
  </view>
</template>
<script setup>
import {computed,nextTick,onMounted,onUnmounted,reactive,ref,watch} from 'vue';
import {api} from '../services/api.js';
import {createOtherBorrows} from '../services/other-borrows.mjs';
import {formatBorrowDate,formatBorrowMinute} from '../services/borrow-dates.mjs';
import AssetBarcodeScanner from './AssetBarcodeScanner.vue';
import LegacyOtherBorrowPage from './LegacyOtherBorrowPage.vue';
import ServerPager from './ServerPager.vue';
import TeacherChoice from './TeacherChoice.vue';

const props=defineProps({user:{type:Object,required:true},verified:Boolean,pending:Boolean,recordId:{type:String,default:''},returnLabel:{type:String,default:'我的借用'}});
const emit=defineEmits(['pending','navigate','created','close','changed']);
const service=createOtherBorrows(api);
const staff=computed(()=>['reviewer','admin'].includes(props.user.role));
const tabs=computed(()=>[
  ...(staff.value?[{id:'issue',label:'待发放'},{id:'returns',label:'待归还'}]:[{id:'create',label:'填写登记'}]),
  {id:'checkout',label:staff.value?'借用台账':'我的借用'},{id:'registration',label:'历史补登记'},{id:'legacy',label:'历史申请'}
]);
const tab=ref(props.recordId?'checkout':staff.value?'issue':'create');
const status=ref(''),keyword=ref(''),error=ref(''),notice=ref(''),busy=ref(false),loading=ref(false);
const statusLabels={pending:'申请中',rejected:'已拒绝',approved:'待发放',borrowed:'借用中',returning:'待归还',returned:'已归还',cancelled:'已取消',registered:'待确认',confirmed:'已确认'};
const actionLabels={approve:'批准申请',reject:'拒绝申请',confirm:'确认登记',create:'提交借用登记',issue:'确认发放','return-request':'申请归还','return-confirm':'确认归还','return-confirm-direct':'现场确认归还','return-reject':'退回归还',cancel:'取消借用'};
const statusOptions=computed(()=>['',...(tab.value==='registration'?['registered','confirmed']:['approved','borrowed','returning','returned','cancelled'])].map(value=>({value,label:statusLabels[value]||'全部'})));
const blank=()=>({name:'',teacherUserId:'',purpose:''});
const form=reactive(blank()),data=reactive({items:[],total:0,page:1,pageSize:10});
const selected=ref(null),assetNumber=ref(''),remark=ref(''),reason=ref(''),physicalAvailable=ref(false);
const blocked=computed(()=>busy.value||!props.verified||props.pending||!!api.pending());
let request=0,disposed=false;

function report(e){error.value=e.message||'操作未完成，请重试';emit('pending',api.pending());}
const can=action=>selected.value?.allowedActions?.includes(action);
async function changeTab(value){
  if(busy.value)return;
  request++;tab.value=value;status.value='';keyword.value='';error.value='';notice.value='';selected.value=null;
  Object.assign(data,{items:[],total:0,page:1});await load(1);
}
async function load(page=data.page){
  if(props.recordId||['create','legacy'].includes(tab.value)||disposed)return;
  const revision=++request,mode=tab.value;
  loading.value=true;error.value='';
  try{
    const result=await service.list({page,pageSize:10,mine:!staff.value,workflowMode:mode==='registration'?'registration':'checkout',status:mode==='issue'?'approved':mode==='returns'?'returning':status.value,keyword:keyword.value});
    if(disposed||revision!==request)return;
    if(page>1&&!result.items.length)return load(Math.max(1,Math.ceil(result.total/result.pageSize)));
    Object.assign(data,result);
  }catch(e){if(!disposed&&revision===request)report(e);}finally{if(revision===request)loading.value=false;}
}
async function reload(){if(selected.value)await open(selected.value);else await load();}
async function submit(){
  if(blocked.value)return;
  busy.value=true;error.value='';
  try{
    await service.createCheckout({...form});emit('pending',api.pending());
    if(disposed)return;
    Object.assign(form,blank());emit('created');
  }catch(e){if(!disposed)report(e);}finally{busy.value=false;}
}
async function open(row){
  if(busy.value)return;
  busy.value=true;loading.value=true;error.value='';
  try{
    const fresh=await service.detail(row.id);
    if(disposed)return;
    selected.value=fresh;assetNumber.value='';remark.value='';reason.value='';physicalAvailable.value=false;
    await nextTick();uni.pageScrollTo({scrollTop:0,duration:0});
  }catch(e){if(!disposed)report(e);}finally{busy.value=false;loading.value=false;}
}
const confirmation={approve:'确认批准这条历史申请？',reject:'确认拒绝这条历史申请？',issue:'请确认已核对借用人、器材及配件，并完成发放。','return-request':'确定提交归还申请？请到现场交回器材及配件。','return-confirm':'请确认已收回器材及配件，并完成验收。',cancel:'确定取消这条借用登记？','return-reject':'确定退回这次归还申请？'};
async function perform(action){
  if(blocked.value||!can(action))return;
  const row=selected.value;
  busy.value=true;error.value='';
  try{
    const confirmed=await new Promise(resolve=>uni.showModal({title:actionLabels[action],content:confirmation[action]||'确定办理？',success:result=>resolve(result.confirm),fail:()=>resolve(false)}));
    if(!confirmed||disposed||selected.value!==row||!props.verified||props.pending||api.pending())return;
    const result=await service.action(row,action,{checked:true,assetNumber:assetNumber.value,remark:remark.value,reason:reason.value,physicalAvailable:physicalAvailable.value});
    emit('pending',api.pending());
    if(disposed)return;
    selected.value=result;assetNumber.value='';remark.value='';reason.value='';await load();
    notice.value=actionLabels[action]+'成功。';emit('changed');
  }catch(e){if(!disposed){report(e);if(e.status===409)selected.value=null;}}finally{busy.value=false;}
}
async function confirmRegistration(row){
  if(blocked.value)return;
  busy.value=true;loading.value=true;error.value='';
  try{
    const fresh=await service.detail(row.id);if(disposed)return;
    await service.action(fresh,'confirm');emit('pending',api.pending());
    if(!disposed){await load();notice.value='登记已确认，确认人及时间已记录。';}
  }catch(e){if(!disposed)report(e);}finally{busy.value=false;}
}
watch(()=>props.pending,(value,previous)=>{if(previous&&!value&&!busy.value)reload();});
watch(()=>props.recordId,(value,previous)=>{if(value&&value!==previous)open({id:value});});
onMounted(()=>props.recordId?open({id:props.recordId}):load(1));onUnmounted(()=>{disposed=true;request++;});
defineExpose({reload});
</script>
<style src="../pages/index/live.css"></style>
<style src="../pages/index/restore-v1.css"></style>
<style src="../pages/index/college-refinement.css"></style>
<style scoped>
.other-borrow-tabs{margin:18px 0}.registration-row>.small{display:block;margin:9px 0}.registration-row>.btn{margin-top:14px}.other-registration-form{max-width:800px}.registration-filters .search-box{margin-bottom:12px}.detail-back{margin-bottom:18px}.other-month{min-width:145px}.other-history{margin-top:20px}
</style>
