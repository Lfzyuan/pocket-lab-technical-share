<template><view class="panel home-duty"><view class="section-heading"><text class="block-title">器材借还值班时间</text><button role="button" class="text-btn" @click="$emit('all')">查看全部 ›</button></view><view class="duty-week-heading"><button role="button" class="text-btn" aria-label="前一周" @click="move(-7)">‹</button><text>{{start.slice(5).replace('-','/')}}—{{end.slice(5).replace('-','/')}}</text><button role="button" class="text-btn" aria-label="后一周" @click="move(7)">›</button></view><view class="duty-week-days"><button v-for="day in days" :key="day.date" role="button" :class="['duty-week-day',{selected:selected===day.date,available:day.slots.length,today:day.date===today}]" :aria-pressed="selected===day.date" :disabled="day.date<today" :aria-disabled="day.date<today" @click="chooseDay(day.date)"><text>周{{day.label}}</text><text class="duty-day-number">{{Number(day.date.slice(8))}}</text><text class="duty-day-marker">{{day.date<today?'已结束':day.slots.length?'值班':'未安排'}}</text></button></view><text v-if="loading" class="small duty-week-status" role="status">正在读取真实值班安排…</text><view v-else-if="error" class="error" role="alert">{{error}}<button role="button" class="text-btn" @click="load">重试</button></view><view v-else class="duty-week-detail"><text class="small">{{selected||start}} · {{end<today?'该周已结束':current.slots.length?'可办理领取与归还':'暂无开放值班时段'}}</text><view v-for="slot in current.slots" :key="slot.id" class="duty-week-slot"><text>{{slot.startTime.slice(0,5)}}—{{slot.endTime.slice(0,5)}}</text><text>{{slot.location}}</text></view><text v-if="!current.slots.length" class="small">{{end<today?'已结束的值班时段不可用于领取。':days.some(day=>day.slots.length)?'有值班的日期已标出，点击查看时间。':'本周暂无安排，公布后会在相应日期标出。'}}</text></view></view></template>
<script setup>
import {inject,ref,computed,onMounted,onUnmounted} from 'vue';
import {api as defaultApi} from '../services/api.js';
const api=inject('portalClient',defaultApi);
import {chinaToday,chinaWeekStart,shiftDay,dutyWeek,availableDutySlots,advanceDutyWindow} from '../services/duty-week.mjs';
const props=defineProps({request:{type:Function,default:null}});
defineEmits(['all']);
const clock=ref(Date.now()),today=ref(chinaToday()),start=ref(chinaWeekStart()),selected=ref(today.value),slots=ref([]),loading=ref(false),error=ref('');
const end=computed(()=>shiftDay(start.value,6)),days=computed(()=>dutyWeek(start.value,availableDutySlots(slots.value,clock.value))),current=computed(()=>days.value.find(day=>day.date===selected.value)||days.value[0]);
let generation=0,disposed=false,timer,lastFetch=0,followCurrentWeek=true,selectedManually=false;
function syncClock(){
 const previous=start.value,previousSelection=selected.value;clock.value=Date.now();
 // A manually previewed future week rejoins automatic following once it becomes the current week.
 if(start.value===chinaWeekStart(clock.value))followCurrentWeek=true;
 const state=advanceDutyWindow({start:start.value,selected:selected.value,followCurrentWeek},clock.value);
 if(previous!==state.start||previousSelection!==state.selected)selectedManually=false;
 if(today.value!==state.today&&followCurrentWeek&&!selectedManually)state.selected=state.today;
 today.value=state.today;start.value=state.start;selected.value=state.selected;return previous!==start.value;
}
async function load({quiet=false}={}){
 if(disposed)return;syncClock();const stamp=++generation,from=start.value,to=end.value;lastFetch=clock.value;if(!quiet)loading.value=true;
 try{let page=1,all=[],total;do{const {data}=await (props.request||api.request)('/duty-slots?from='+from+'&to='+to+'&pageSize=100&page='+page);all.push(...data.items);total=data.total;page++;}while(all.length<total&&page<=100);
  if(!disposed&&stamp===generation&&from===start.value&&to===end.value){slots.value=all;error.value='';const available=availableDutySlots(all,Date.now()).filter(slot=>slot.date>=from&&slot.date<=to).sort((a,b)=>a.date.localeCompare(b.date)||a.startTime.localeCompare(b.startTime));if(!selectedManually&&available[0])selected.value=available[0].date;}
 }catch(e){if(!disposed&&stamp===generation)error.value=e.message||'值班安排暂时无法读取';}finally{if(!disposed&&stamp===generation)loading.value=false;}
}
function chooseDay(date){if(date<today.value)return;selected.value=date;selectedManually=true;}
function move(offset){syncClock();start.value=shiftDay(start.value,offset);followCurrentWeek=start.value===chinaWeekStart(clock.value);selected.value='';selectedManually=false;load();}
function visible(){
 // #ifdef H5
 if(typeof document!=='undefined'&&document.hidden)return false;
 // #endif
 return true;
}
function tick(force=false){if(disposed||!visible())return;const moved=syncClock();if(force||moved||clock.value-lastFetch>=30000)load({quiet:slots.value.length>0});}
function resumed(){tick(true);}
onMounted(()=>{load();timer=setInterval(()=>tick(),1000);
 // #ifdef H5
 document.addEventListener('visibilitychange',resumed);window.addEventListener('pageshow',resumed);
 // #endif
});
onUnmounted(()=>{disposed=true;generation++;clearInterval(timer);
 // #ifdef H5
 document.removeEventListener('visibilitychange',resumed);window.removeEventListener('pageshow',resumed);
 // #endif
});
defineExpose({refresh:resumed});
</script><style scoped>.duty-week-heading{display:flex;align-items:center;justify-content:space-between;margin:16px 0 10px;font-size:13px;color:#435675}.duty-week-days{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:5px}.duty-week-day{padding:9px 1px;border:1px solid transparent;border-radius:9px;background:#f6f8fc;color:#63738b;display:flex;flex-direction:column;align-items:center;gap:5px;min-width:0;font-size:11px;line-height:1.5}.duty-day-number{font-size:17px;font-weight:650}.duty-day-marker{font-size:10px;white-space:nowrap}.duty-week-day.available{background:#edf3ff;color:#3159ac}.duty-week-day.selected{border-color:#4568cc;background:#e5edff;color:#253f7a}.duty-week-day.today .duty-day-number{text-decoration:underline;text-underline-offset:4px}.duty-week-detail{min-height:100px;margin-top:18px;display:flex;flex-direction:column;gap:12px}.duty-week-slot{display:flex;justify-content:space-between;flex-wrap:wrap;gap:5px 12px;padding:10px 12px;background:#f4f7fd;border-left:3px solid #547acb;border-radius:5px;font-size:13px;color:#253f65}.duty-week-status{display:block;min-height:100px;margin-top:18px}.duty-week-heading .text-btn{font-size:20px!important;min-width:30px}@media(max-width:600px){.duty-week-days{gap:4px}.duty-week-day{padding:8px 1px}.duty-week-detail{min-height:85px}.duty-day-number{font-size:16px}}.home-duty .section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.home-duty .text-btn{display:inline-flex;align-items:center;justify-content:center;width:auto;min-height:28px;line-height:1.5;margin:0;padding:4px 6px;border:0;background:transparent;font-size:12px;color:#4264ac}.home-duty .text-btn::after,.duty-week-day::after{border:0}.duty-week-days>.duty-week-day{width:100%;margin:0;min-height:78px;line-height:1.4;overflow:visible}.duty-week-heading{gap:12px}.duty-week-heading>.text-btn{width:28px;min-width:28px}.duty-week-heading>text{flex:1;text-align:center}.duty-week-day>text{white-space:nowrap}.duty-week-day .duty-day-marker{font-size:10px}
.duty-week-day[disabled]{opacity:.5;cursor:default}
</style>
