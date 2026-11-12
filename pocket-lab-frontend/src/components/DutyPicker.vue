<template>
  <view class="duty-picker">
    <button role="button" class="btn secondary duty-picker-summary" :disabled="disabled" :aria-expanded="expanded" @click="expanded=!expanded">
      <text>{{summary}}</text><text>{{expanded?'收起':'选择时间'}}</text>
    </button>
    <view v-if="expanded" class="duty-picker-options">
      <text v-if="loading" class="small" role="status">正在加载值班时间…</text>
      <template v-else-if="!error&&loaded">
        <scroll-view v-if="dates.length" scroll-x class="duty-picker-dates">
          <view class="duty-picker-date-row" role="group" aria-label="领取日期">
            <button v-for="date in dates" :key="date" role="button" class="chip" :class="{selected:activeDate===date}" :disabled="disabled" :aria-pressed="activeDate===date" @click="activeDate=date">{{dateLabel(date)}}</button>
          </view>
        </scroll-view>
        <view v-if="daySlots.length" class="duty-picker-times" role="group" aria-label="当天领取时段">
          <button v-for="slot in daySlots" :key="slot.id" role="button" class="btn secondary duty-picker-time" :class="{selected:modelValue===slot.id}" :disabled="disabled" :aria-pressed="modelValue===slot.id" @click="choose(slot.id)">
            <text>{{timeLabel(slot)}}</text><text class="small">{{slot.location||'值班地点待确认'}}</text>
          </button>
        </view>
        <text v-else class="small" role="status">暂无有效值班时间。</text>
      </template>
      <button role="button" class="text-btn" :disabled="disabled||loading" @click="$emit('refresh')">刷新值班时间</button>
    </view>
    <text v-if="error" class="error duty-picker-message" role="alert">{{error}}</text>
    <text v-else-if="warning" class="error duty-picker-message" role="alert">{{warning}}</text>
  </view>
</template>
<script setup>
import {computed,ref,watch} from 'vue';
const props=defineProps({modelValue:{type:String,default:''},slots:{type:Array,default:()=>[]},savedSlot:Object,loading:Boolean,loaded:Boolean,error:String,warning:String,disabled:Boolean});
const emit=defineEmits(['update:modelValue','refresh']);
const expanded=ref(false),activeDate=ref('');
const dates=computed(()=>[...new Set(props.slots.map(slot=>slot.date))].sort());
const daySlots=computed(()=>props.slots.filter(slot=>slot.date===activeDate.value).sort((a,b)=>a.startTime.localeCompare(b.startTime)));
const chosen=computed(()=>props.slots.find(slot=>slot.id===props.modelValue)||(props.savedSlot?.id===props.modelValue?props.savedSlot:null));
const summary=computed(()=>chosen.value?dateLabel(chosen.value.date)+' '+timeLabel(chosen.value)+(chosen.value.location?' · '+chosen.value.location:''):props.modelValue&&!props.loaded?'已保存的领取时间':'请选择领取日期与时段');
function dateLabel(date){return date.slice(5).replace('-','/')+' 周'+['日','一','二','三','四','五','六'][new Date(date+'T12:00:00+08:00').getUTCDay()];}
function timeLabel(slot){return slot.startTime.slice(0,5)+'—'+slot.endTime.slice(0,5);}
function choose(id){emit('update:modelValue',id);expanded.value=false;}
watch([dates,chosen],()=>{
  const selectedDate=chosen.value?.date;
  if(selectedDate&&dates.value.includes(selectedDate))activeDate.value=selectedDate;
  else if(!dates.value.includes(activeDate.value))activeDate.value=dates.value[0]||'';
},{immediate:true});
</script>
<style scoped>
.duty-picker{min-width:0}.duty-picker-summary{display:flex;width:100%;justify-content:space-between;text-align:left;gap:12px}.duty-picker-summary>text:first-child{min-width:0;overflow-wrap:anywhere}.duty-picker-summary>text:last-child{flex-shrink:0}.duty-picker-options{margin-top:10px;padding:12px;border:1px solid var(--lab-line,#dce4eb);border-radius:8px;background:#fff;max-height:300px;overflow-y:auto}.duty-picker-date-row{display:flex;gap:7px;padding-bottom:8px;white-space:nowrap}.duty-picker-dates{width:100%;margin-bottom:5px}.duty-picker-date-row .chip{flex-shrink:0}.duty-picker-times{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-bottom:12px}.duty-picker-time{flex-direction:column;align-items:flex-start;text-align:left;gap:3px}.duty-picker .duty-picker-time.selected{background:var(--lab-blue,#456b89);border-color:var(--lab-blue,#456b89);color:#fff}.duty-picker .duty-picker-time.selected .small{color:#fff!important}.duty-picker-message{display:block;margin:9px 0 0}.duty-picker-options>.text-btn{min-height:36px}@media(max-width:480px){.duty-picker-times{grid-template-columns:minmax(0,1fr)}.duty-picker-options{max-height:260px}}
</style>
