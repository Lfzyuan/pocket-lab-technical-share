<template>
 <view class="borrow-records">
  <view class="button-row view-switch" role="group" aria-label="借用记录显示模式">
   <button role="button" :class="['chip',{selected:view==='cards'}]" :aria-pressed="view==='cards'" @click="$emit('update:view','cards')">▦ 卡片</button>
   <button role="button" :class="['chip',{selected:view==='list'}]" :aria-pressed="view==='list'" @click="$emit('update:view','list')">☷ 列表</button>
   <text class="small">每页{{pageSize}}条</text>
  </view>
  <view v-if="records.length" :class="view==='cards'?'loan-cards':'loan-list'">
   <view v-for="record in records" :key="loanRecordKey(record)" :class="[view==='cards'?'loan-card':'loan-list-row',{'loan-history':isHistory(record)}]" :data-record-id="record.id" :data-record-key="loanRecordKey(record)" @click="!busy&&$emit('open',record)">
    <image v-if="view==='cards'&&!isOtherLoan(record)" :src="equipmentImages[record.equipmentId]||'/static/board-blue.png'" mode="aspectFit" class="loan-card-image"/>
    <view class="loan-content">
     <text v-if="isOtherLoan(record)" class="small loan-origin">其他借用</text><view class="loan-heading"><text class="loan-title">{{record.equipmentName||record.name||equipmentNames[record.equipmentId]||record.equipmentId}}</text><text :class="['status',record.status]">{{statusLabels[record.status]||record.status}}</text></view>
     <view class="loan-summary"><text v-if="proxyView" class="loan-meta">替 {{record.user}} 代领</text><text v-else-if="viewerRole!=='student'" class="loan-meta">{{record.user}} · {{record.className||'未填写班级'}} · {{record.quantity}}件</text><text v-else class="loan-meta">借用 {{record.quantity}}件</text><text class="loan-time">申请 {{formatBorrowDate(record.createdAt)}}</text></view>
     <text v-if="record.status==='approved'&&record.pickupDeadlineAt" class="loan-meta">领取截止：{{formatBorrowDate(record.pickupDeadlineAt)}}</text>
     <text v-if="record.pickupMode==='agreed'" class="loan-meta">约定领取：{{record.agreedPickupDate||record.agreedPickupAt?formatBorrowDate(record.agreedPickupDate||record.agreedPickupAt):'另行约定'}}</text>
     <text v-if="record.pickupProxyName&&!proxyView" class="loan-meta">代领人：{{record.pickupProxyName}}</text>
     <text v-if="record.status==='approved'&&record.pickupProxyId&&!record.pickupSlot" class="loan-meta">{{proxyView?'请先选择代领时间':'待代领人选时间'}}</text>
     <text v-if="view==='cards'&&record.purpose" class="loan-purpose" :title="record.purpose">用途：{{record.purpose}}</text>
     <view v-if="!proxyView&&!isOtherLoan(record)&&record.status!=='returning'&&record.dueDate" class="loan-due"><text>预计归还 {{record.dueDate}}</text><text class="loan-renewed">已续借 {{record.renewCount??0}} 次</text></view>
     <text v-else-if="!proxyView&&!isOtherLoan(record)&&view==='cards'&&['pending','approved'].includes(record.status)" class="loan-awaiting">领取后开始计算借期</text>
     <view v-if="!proxyView&&record.status==='returning'&&record.returnDeadlineAt" class="loan-due"><text>交回截止 {{formatBorrowMinute(record.returnDeadlineAt)}}</text></view>
     <view class="loan-actions"><button v-if="!proxyView&&viewerRole==='student'&&record.status==='returning'" role="button" class="btn secondary" @click.stop="$emit('duty')">值班时间</button>
      <button v-if="!proxyView&&!isOtherLoan(record)&&viewerRole==='student'&&record.status==='borrowed'" role="button" class="btn loan-renew" :disabled="busy||!verified||pending||!record.allowedActions?.includes('renew')" :aria-disabled="busy||!verified||pending||!record.allowedActions?.includes('renew')" @click.stop="$emit('renew',record)">{{renewLabel(record)}}</button>
      <button role="button" class="btn secondary" :disabled="busy" :aria-disabled="busy" @click.stop="$emit('open',record)">{{proxyView&&record.allowedActions?.includes('schedule-pickup')?'选择代领时间':'查看与办理'}}</button>
      <button v-if="!proxyView&&!isOtherLoan(record)&&record.equipmentId" role="button" class="btn secondary" @click.stop="$emit('resources',record.equipmentId)">相关资料 ›</button>
     </view>
    </view>
   </view>
  </view>
  <view v-else-if="!loading" class="panel empty">{{proxyView?'暂无符合当前筛选的代领任务。':'暂无符合当前筛选的借用记录。'}}</view>
  <DisplayExample v-if="!proxyView&&!loading&&verified" module="records" :empty="!records.length"/>
 </view>
</template>
<script setup>
import {formatBorrowDate,formatBorrowMinute} from '../services/borrow-dates.mjs';
import DisplayExample from './DisplayExample.vue';
import {renewLabel} from '../services/loan-policy.mjs';
import {isOtherLoan,loanRecordKey} from '../services/borrow-management.mjs';
const props=defineProps({records:{type:Array,default:()=>[]},equipmentNames:{type:Object,default:()=>({})},equipmentImages:{type:Object,default:()=>({})},statusLabels:{type:Object,default:()=>({})},viewerRole:String,proxyView:Boolean,verified:Boolean,busy:Boolean,pending:Boolean,loading:Boolean,view:{type:String,default:'cards'},pageSize:{type:Number,default:5}});
defineEmits(['update:view','open','renew','resources','duty']);
function isHistory(record){return ['returned','cancelled','rejected','closed'].includes(record.status);}
</script>
<style scoped>

@import '../pages/index/live.css';
@import '../pages/index/restore-v1.css';
.loan-card,.loan-list-row{cursor:pointer}
.loan-cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;align-items:start}.loan-card{min-width:0;background:#fff;border:1px solid #d7e1eb;border-radius:12px;overflow:hidden}.loan-card-image{display:block;width:100%;height:160px;background:#edf2f7}.loan-content{min-width:0;padding:16px}.loan-heading{display:flex;align-items:flex-start;justify-content:space-between;flex-wrap:nowrap;gap:6px 10px;margin-bottom:7px}.loan-title{flex:1;min-width:0;font-size:16px;font-weight:700;line-height:1.6;color:#111;overflow-wrap:anywhere}.loan-heading .status{flex-shrink:0}.loan-summary{display:flex;align-items:baseline;flex-wrap:wrap;gap:2px 12px;margin:5px 0}.loan-summary .loan-meta,.loan-summary .loan-time{margin:0}.loan-meta,.loan-time,.loan-awaiting{display:block;font-size:12px;line-height:1.7;color:#425466;margin:4px 0}.loan-purpose{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;font-size:13px;line-height:1.7;color:#111;margin:5px 0}.loan-due{display:flex;align-items:baseline;flex-wrap:wrap;gap:2px 12px;font-size:13px;line-height:1.7;color:#31516b;background:#edf2f7;border-radius:6px;padding:7px 9px;margin:9px 0}.loan-renewed{font-size:12px}.loan-awaiting{color:#60758a;margin:8px 0}.loan-actions{display:flex;align-items:center;flex-wrap:wrap;gap:7px;margin-top:12px}.loan-actions .btn{font-size:13px;padding:8px 12px;min-height:40px;min-width:0}.loan-history{background:#f7f9fb}.loan-history .loan-card-image{filter:grayscale(.6)}.loan-history .loan-title{font-weight:550}.loan-list{border:1px solid #d7e1eb;border-radius:10px;background:#fff;overflow:hidden}.loan-list-row{border-bottom:1px solid #e2e8ef}.loan-list-row:last-child{border-bottom:0}.loan-list-row .loan-content{padding:13px 16px;display:grid;grid-template-columns:minmax(0,1fr) auto;gap:3px 20px;align-items:center}.loan-list-row .loan-summary{grid-column:1;margin:0}.loan-list-row .loan-heading,.loan-list-row .loan-meta,.loan-list-row .loan-purpose,.loan-list-row .loan-time,.loan-list-row .loan-due,.loan-list-row .loan-awaiting{grid-column:1}.loan-list-row .loan-heading{margin-bottom:2px;justify-content:flex-start}.loan-list-row .loan-actions{grid-column:2;grid-row:1/span 3;justify-content:flex-end;max-width:310px;margin-top:0}.loan-list-row .loan-purpose{-webkit-line-clamp:1;margin:0}.loan-list-row .loan-meta,.loan-list-row .loan-time{margin:0}.loan-list-row .loan-due{justify-self:start;padding:3px 7px;margin:3px 0}.loan-list-row .loan-title{font-size:15px}.loan-list-row .loan-awaiting{margin:3px 0}
@media(max-width:1200px){.loan-cards{grid-template-columns:repeat(2,minmax(0,1fr))}.loan-list-row .loan-content{grid-template-columns:minmax(0,1fr)}.loan-list-row .loan-actions{grid-column:1;grid-row:auto;max-width:none;margin-top:7px}}
@media(max-width:760px){.loan-cards{grid-template-columns:1fr;gap:14px}.loan-card-image{height:140px}.loan-content{padding:14px}.loan-title{font-size:15px}.loan-list-row .loan-content{padding:11px 12px;gap:2px}.loan-list-row .loan-title{font-size:14px}.loan-heading{gap:5px 8px}.loan-due{font-size:12px}.loan-actions .btn{min-height:42px;font-size:12px;padding:8px 11px}.loan-list-row .loan-actions{gap:7px;justify-content:flex-end}.loan-meta,.loan-time{font-size:12px}}
</style>
