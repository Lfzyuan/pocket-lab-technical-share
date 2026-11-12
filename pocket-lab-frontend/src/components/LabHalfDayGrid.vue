<template>
  <view class="half-day-grid">
    <text v-if="!labId" class="muted">请先选择实验室。</text>
    <text v-else-if="loading" class="muted" role="status">正在读取开放安排…</text>
    <view v-else-if="error" class="error" role="alert">
      {{error}}<button role="button" class="text-btn" @click="load">重试</button>
    </view>
    <template v-else>
      <view class="half-pagination">
        <button role="button" class="btn secondary small" :disabled="page===0"
          :aria-disabled="page===0" @click="page--">上一页</button>
        <text class="half-page-label" aria-live="polite">第 {{page+1}} / {{pages.length}} 页 · {{pageRange}}</text>
        <button role="button" class="btn secondary small" :disabled="page===pages.length-1"
          :aria-disabled="page===pages.length-1" @click="page++">下一页</button>
      </view>
      <view v-if="!viewOnly" class="half-toolbar">
        <button role="checkbox" class="half-action" :aria-checked="groupState(pageCells)"
          :disabled="disabled" :aria-disabled="disabled" @click="toggleGroup(pageCells)" @keydown.space.prevent="toggleGroup(pageCells)">
          全选本页可用
        </button>
        <button v-if="modelValue.length" role="button" class="text-btn" :disabled="disabled" @click="clear">清空已选</button>
        <text class="muted">共 {{participantCount}} 人</text>
      </view>
      <view class="half-row half-head">
        <text>日期</text>
        <template v-for="period in halfDays" :key="period.value">
          <text v-if="viewOnly">{{period.label}}</text>
          <button v-else role="checkbox" class="half-group" :aria-checked="groupState(columnCells(period.value))"
            :aria-label="'本页'+period.label+'整列'" :disabled="disabled" :aria-disabled="disabled"
            @click="toggleGroup(columnCells(period.value))" @keydown.space.prevent="toggleGroup(columnCells(period.value))">{{period.label}}</button>
        </template>
      </view>
      <view v-for="date in pageDates" :key="date" class="half-row">
        <text v-if="viewOnly">{{dateLabel(date)}}</text>
        <button v-else role="checkbox" class="half-group half-date" :aria-checked="groupState(dayCells(date))"
          :aria-label="date+' 整天'" :disabled="disabled" :aria-disabled="disabled"
          @click="toggleGroup(dayCells(date))" @keydown.space.prevent="toggleGroup(dayCells(date))">{{dateLabel(date)}}</button>
        <template v-for="period in halfDays" :key="period.value">
          <text v-if="viewOnly" class="half-cell">{{status(date,period.value)}}</text>
          <button v-else role="button" class="half-cell"
            :class="{picked:isPicked(date,period.value)}" :aria-pressed="isPicked(date,period.value)"
            :aria-label="date+' '+period.label+' '+status(date,period.value)"
            :disabled="cellDisabled(date,period.value)" :aria-disabled="cellDisabled(date,period.value)"
            @click="toggle(date,period.value)">{{status(date,period.value)}}</button>
        </template>
      </view>
      <text class="muted period-note">上午 08:00–13:00 · 下午 13:00–18:00 · 晚上 18:00–22:00</text>
      <text v-if="skippedMessage" class="half-skipped" role="status" :data-skipped="skipped">{{skippedMessage}}</text>
      <text v-if="hint" class="error" role="alert">{{hint}}</text>
    </template>
    <view v-if="!viewOnly&&modelValue.length" class="half-summary">
      <text class="half-summary-title">已选 {{modelValue.length}} 个半天</text>
      <view v-for="group in selectedGroups" :key="group.date" class="half-summary-row">
        <text>{{group.date}}</text>
        <view class="half-summary-slots">
          <button v-for="cell in group.cells" :key="cell.slotId" role="button" class="half-selected"
            :disabled="disabled" :aria-label="cell.date+' '+label(cell.slotId)+' 取消选择'"
            @click="remove(cell)">{{label(cell.slotId)}} ×</button>
        </view>
      </view>
      <text v-if="invalidSelected" class="error" role="alert">
        有 {{invalidSelected}} 个已选时段当前不可用，请取消后重新选择。
      </text>
      <text v-if="selectedRun>7" class="error" role="alert">每次最多连续预约7天，请取消部分时段。</text>
    </view>
  </view>
</template>

<script setup>
import {computed, inject, onBeforeUnmount, ref, watch} from 'vue';
import {api as defaultApi} from '../services/api.js';
import {bookingCellReason, bookingDates, bookingGroupState, bookingPages, longestBookingRun,
  halfDays, slotKey, toggleBookingGroup} from '../services/lab-half-days.mjs';

const props=defineProps({
  modelValue:{type:Array,default:()=>[]},labId:String,disabled:Boolean,viewOnly:Boolean,
  participants:{type:Number,default:1}
});
const emit=defineEmits(['update:modelValue','validity']);
const api=inject('portalClient',defaultApi);
const now=ref(Date.now()),page=ref(0),rows=ref([]),loading=ref(false),error=ref(''),hint=ref('');
const skipped=ref(0),skippedMessage=ref('');
const dates=computed(()=>bookingDates(now.value));
const pages=computed(()=>bookingPages(dates.value));
const pageDates=computed(()=>pages.value[page.value]||pages.value[0]);
const pageRange=computed(()=>{
  const values=pageDates.value;
  return values.length===1?values[0]:values[0]+' 至 '+values.at(-1);
});
const participantCount=computed(()=>Math.max(1,Number(props.participants)||1));
const entries=computed(()=>new Map(rows.value.map(cell=>[slotKey(cell),cell])));
const picked=computed(()=>new Set(props.modelValue.map(slotKey)));
const pageCells=computed(()=>pageDates.value.flatMap(dayCells));
const selectedRun=computed(()=>longestBookingRun(props.modelValue));
const invalidSelected=computed(()=>props.modelValue.filter(cell=>
  !dates.value.includes(cell.date)||bookingCellReason(entries.value.get(slotKey(cell)),participantCount.value,now.value)).length);
const selectionValid=computed(()=>!!props.labId&&!loading.value&&!error.value&&props.modelValue.length>0&&
  !invalidSelected.value&&selectedRun.value<=7);
const selectedGroups=computed(()=>{
  const groups=new Map();
  [...props.modelValue].sort((a,b)=>slotKey(a).localeCompare(slotKey(b))).forEach(cell=>{
    if(!groups.has(cell.date))groups.set(cell.date,[]);
    groups.get(cell.date).push(cell);
  });
  return [...groups].map(([date,cells])=>({date,cells}));
});
let revision=0;
const timer=setInterval(()=>now.value=Date.now(),30000);

function dayCells(date){return halfDays.map(period=>({date,slotId:period.value}));}
function columnCells(slotId){return pageDates.value.map(date=>({date,slotId}));}
function dateLabel(date){return date.slice(5)+(date===dates.value[0]?' 今天':'');}
function label(slotId){return halfDays.find(item=>item.value===slotId)?.label||slotId;}
function isPicked(date,slotId){return picked.value.has(slotKey({date,slotId}));}
function groupState(candidates){
  return bookingGroupState(props.modelValue,candidates,rows.value,participantCount.value,now.value);
}
function reason(date,slotId){
  return bookingCellReason(entries.value.get(slotKey({date,slotId})),participantCount.value,now.value);
}
function cellDisabled(date,slotId){return props.disabled||(!isPicked(date,slotId)&&!!reason(date,slotId));}
function status(date,slotId){
  if(isPicked(date,slotId))return reason(date,slotId)?'已选 · 不可用':'已选';
  const state=reason(date,slotId),cell=entries.value.get(slotKey({date,slotId}));
  if(state==='closed')return '未开放';
  if(state==='started')return '已开始';
  if(state==='capacity')return cell?.available>0?'不足 '+participantCount.value+' 人':'已满';
  return '可约 '+cell.available;
}
function resetFeedback(){hint.value='';skipped.value=0;skippedMessage.value='';}
function remove(cell){
  if(props.disabled)return;
  resetFeedback();
  emit('update:modelValue',props.modelValue.filter(item=>slotKey(item)!==slotKey(cell)));
}
function clear(){
  if(props.disabled)return;
  resetFeedback();
  emit('update:modelValue',[]);
}
function toggle(date,slotId){
  if(props.disabled)return;
  if(isPicked(date,slotId)){remove({date,slotId});return;}
  toggleGroup([{date,slotId}]);
}
function toggleGroup(candidates){
  if(props.disabled)return;
  resetFeedback();
  const result=toggleBookingGroup(props.modelValue,candidates,rows.value,{
    participants:participantCount.value,now:now.value
  });
  skipped.value=result.skipped;
  if(result.skipped){
    const names={closed:'未开放',started:'已开始',capacity:'余量不足'};
    const details=Object.entries(result.reasons).filter(([,count])=>count).map(([key,count])=>names[key]+' '+count);
    skippedMessage.value='已跳过 '+result.skipped+' 个不可用时段（'+details.join('、')+'）。';
  }
  if(result.rejected){hint.value='每次最多连续预约7天，已保留原选择。';return;}
  emit('update:modelValue',result.cells);
}
async function load(){
  const current=++revision;
  rows.value=[];resetFeedback();error.value='';loading.value=false;
  if(!props.labId)return;
  loading.value=true;
  try{
    const {data}=await api.request('/labs/'+encodeURIComponent(props.labId)+
      '/availability?dateFrom='+dates.value[0]+'&endDate='+dates.value.at(-1));
    if(current===revision)rows.value=data.halfDays||[];
  }catch(e){if(current===revision)error.value=e.message||'开放安排暂时无法读取';}
  finally{if(current===revision)loading.value=false;}
}
watch([()=>props.labId,()=>dates.value[0]],(current,previous=[])=>{
  page.value=0;
  if(!props.viewOnly&&current[0]!==previous[0])emit('update:modelValue',[]);
  load();
},{immediate:true});
watch(page,resetFeedback);
watch(selectionValid,value=>{if(!props.viewOnly)emit('validity',value);},{immediate:true});
onBeforeUnmount(()=>{revision++;clearInterval(timer);});
</script>

<style scoped>
.half-day-grid{display:grid;gap:8px;min-width:0}
.half-pagination,.half-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
.half-page-label{font-size:16px;font-weight:500;line-height:1.5;text-align:center;flex:1;min-width:120px;color:var(--lab-ink,#202c43)}
.half-row{display:grid;grid-template-columns:minmax(72px,1fr) repeat(3,minmax(0,1fr));gap:6px;align-items:stretch;text-align:center}
.half-head{font-weight:600;padding-top:4px}
.half-group,.half-action{margin:0;padding:6px;border:1px solid #dce4ef;border-radius:7px;background:#f7f9fc;color:#354d75;font-size:13px;line-height:1.5}
.half-group[aria-checked="true"],.half-action[aria-checked="true"]{border-color:var(--lab-blue,#4264eb);background:#eef1ff;color:var(--lab-blue,#4264eb)}
.half-group[aria-checked="mixed"],.half-action[aria-checked="mixed"]{border-color:var(--lab-blue,#4264eb)}
.half-cell{margin:0;padding:7px 3px;min-height:38px;border:1px solid #dce4ef;border-radius:7px;background:#fff;font-size:13px;color:#354d75;line-height:1.4}
.half-cell[aria-disabled="true"]{color:#768496;background:#f2f4f7}
.half-cell.picked{background:var(--lab-blue,#4264eb);color:#fff;border-color:var(--lab-blue,#4264eb)}
.half-cell::after,.half-group::after,.half-action::after,.half-selected::after{border:none}
.period-note,.half-skipped{font-size:12px;line-height:1.7}
.half-skipped{color:#82652d}
.half-summary{display:grid;gap:8px;margin-top:4px;padding:12px;border:1px solid #dce4ef;border-radius:8px;background:#f8faff}
.half-summary-title{font-weight:600;color:#354d75}
.half-summary-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:13px}
.half-summary-slots{display:flex;gap:6px;flex-wrap:wrap}
.half-selected{margin:0;padding:4px 9px;border:1px solid #ccd5ff;border-radius:6px;background:#eef1ff;color:var(--lab-blue,#4264eb);font-size:12px;line-height:1.5}
@media(max-width:480px){
  .half-row{gap:4px;grid-template-columns:minmax(66px,1fr) repeat(3,minmax(0,1fr))}
  .half-cell,.half-group{font-size:12px}
  .half-row>text{font-size:12px}
  .half-pagination :deep(.btn){padding:6px 9px;font-size:12px;min-height:34px}
  .half-page-label{min-width:100px;font-size:14px}
}
</style>
