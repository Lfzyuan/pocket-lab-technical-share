<template>
  <view v-if="user?.role==='admin'&&active" class="lab-booking-summary" aria-label="实验室预约统计" :aria-busy="loading">
    <view class="lab-summary-heading"><view><text class="block-title">实验室预约</text><text class="muted">预约申请与审批状态；每份申请计一次，点击查看对应记录。</text></view><view class="lab-summary-tools"><text v-if="summary" class="small">共 {{summary.total}} 份预约</text><button role="button" class="btn secondary small" :disabled="loading" :aria-disabled="loading" @click="refresh">刷新实验室统计</button></view></view>
    <view class="stats-grid home-stats lab-summary-stats"><button v-for="item in statuses" :key="item.id" role="button" class="stat" :data-lab-status="item.id" :aria-label="'实验室预约 '+item.name+' '+(summary?.[item.id]??'—')+' 条'" @click="$emit('navigate',item.id)"><text class="stat-number" :data-lab-count="item.id">{{summary?.[item.id]??'—'}}</text><text class="muted">{{item.name}}</text></button></view>
    <text v-if="error" class="error" role="status">{{error}}</text>
  </view>
</template>
<script setup>
import {ref,watch,onMounted,onUnmounted} from 'vue';
import {api} from '../services/api.js';
const props=defineProps({user:Object,active:{type:Boolean,default:true}});
defineEmits(['navigate']);
const statuses=[{id:'pending',name:'申请中'},{id:'approved',name:'已批准'},{id:'rejected',name:'已拒绝'},{id:'cancelled',name:'已取消'}];
const summary=ref(null),loading=ref(false),error=ref('');let alive=true,revision=0,timer;
async function refresh(){if(!alive||props.user?.role!=='admin'||!props.active)return;const turn=++revision;loading.value=true;error.value='';try{const result=(await api.request('/lab-bookings/summary')).data;if(!alive||turn!==revision)return;summary.value=result;}catch(e){if(alive&&turn===revision){summary.value=null;error.value=e.message||'实验室统计暂时无法更新，请重试。';}}finally{if(alive&&turn===revision)loading.value=false;}}
function visibleRefresh(){
 // #ifdef H5
 if(document.hidden)return;
 // #endif
 refresh();
}
watch(()=>[props.user?.id,props.user?.role,props.active],()=>{revision++;summary.value=null;loading.value=false;refresh();});
onMounted(()=>{refresh();timer=setInterval(visibleRefresh,60000);
 // #ifdef H5
 document.addEventListener('visibilitychange',visibleRefresh);
 // #endif
});
onUnmounted(()=>{alive=false;revision++;clearInterval(timer);
 // #ifdef H5
 document.removeEventListener('visibilitychange',visibleRefresh);
 // #endif
});
defineExpose({refresh});
</script>
<style scoped>
@import '../pages/index/live.css';
@import '../pages/index/restore-v1.css';
@import '../pages/index/college-refinement.css';
.lab-booking-summary{min-width:0;margin:26px 0}.lab-summary-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:16px}.lab-summary-heading .block-title{display:block;margin-bottom:7px}.lab-summary-tools{display:flex;align-items:center;gap:12px;flex-wrap:wrap;justify-content:flex-end}.lab-summary-stats .stat{cursor:pointer;color:#172d40;text-align:left}.lab-summary-stats .stat-number{display:block}.lab-summary-stats{margin-bottom:12px}.lab-booking-summary .error{display:block}@media(max-width:760px){.lab-booking-summary{margin:20px 0}.lab-summary-heading{flex-direction:column;gap:12px}.lab-summary-tools{justify-content:flex-start}.lab-summary-stats{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
