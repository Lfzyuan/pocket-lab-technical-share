<template>
  <view class="panel quick-scan">
    <view class="button-row">
      <text class="block-title">便捷扫码</text>
      <button role="button" class="text-btn" :disabled="saving" @click="$emit('close')">退出便捷扫码</button>
    </view>
    <view v-if="notice" class="success" role="status">{{notice}}</view>
    <AssetBarcodeScanner ref="scanner" title="便捷扫码" continuous :paused="paused" :hide-controls="paused"
      :disabled="blocked&&!paused" @scanned="identify" @cancelled="reset">
      <template #result>
        <view v-if="paused" class="quick-result" aria-label="扫码办理">
          <text class="block-title">{{resultTitle}}</text>
          <text v-if="code" class="quick-number">资产编号 {{code}}</text>
          <text v-if="preview?.equipmentName" class="muted">{{preview.equipmentName}}</text>
          <view v-if="error" class="error" role="alert">{{error}}</view>
          <view v-if="blocked" class="warning" role="status">请先核对上次办理结果。</view>
          <text v-if="loading" role="status">正在核对台账…</text>
          <template v-if="preview&&!loading&&!blocked">
            <view v-if="preview.candidates" class="button-row">
              <input v-model="query" class="field quick-search" maxlength="100" aria-label="搜索可发放记录"
                placeholder="搜索借用人或器材" @confirm="lookup(1)"/>
              <button role="button" class="btn secondary small" @click="lookup(1)">查询</button>
            </view>
            <text v-if="preview.reason" class="muted">{{preview.reason}}</text>
            <view v-if="preview.candidates?.items.length" class="quick-candidates">
              <button v-for="row in preview.candidates.items" :key="row.recordKey" role="button"
                :aria-pressed="selected?.recordKey===row.recordKey"
                :class="['btn','secondary',{chosen:selected?.recordKey===row.recordKey}]" :disabled="saving" @click="selected=row">
                {{row.user}} · {{row.userAccount||row.account}} · {{row.equipmentName||row.name}}
              </button>
            </view>
            <ServerPager v-if="preview.candidates" :total="preview.candidates.total" :page="preview.candidates.page"
              :page-size="preview.candidates.pageSize" :disabled="saving" @change="lookup"/>
            <view v-if="selected" class="quick-check">
              <text>{{selected.user}} · {{selected.userAccount||selected.account}}</text>
              <text>{{selected.equipmentName||selected.name}} · 1 件</text>
              <text v-if="selected.pickupProxyName">代领人 {{selected.pickupProxyName}} · {{selected.pickupProxyAccount}}</text>
              <text v-if="preview.action==='issue'&&selected.phone">现场联系电话 {{selected.phone}}</text>
              <text v-if="selected.status==='borrowed'">当前状态 借用中</text>
              <text v-if="selected.status==='returning'">当前状态 待归还</text>
              <text class="muted">{{preview.action==='issue'?'确认已核对领取人、器材和配件，并发放1件。':'确认已收回实物和配件，并完成验收。'}}</text>
            </view>
          </template>
          <view class="button-row">
            <button v-if="selected&&preview?.action" role="button" class="btn" :disabled="blocked||loading||saving"
              @click="confirm">{{saving?'正在确认…':resultTitle}}</button>
            <button v-if="!preview&&error&&!blocked" role="button" class="btn secondary" :disabled="loading||saving"
              @click="lookup(1)">重新核对</button>
            <button role="button" class="btn secondary" :disabled="saving" @click="reset">{{selected?'取消办理':'继续扫码'}}</button>
          </view>
        </view>
      </template>
    </AssetBarcodeScanner>
  </view>
</template>

<script setup>
import {computed,nextTick,onBeforeUnmount,onMounted,ref} from 'vue';
import {api} from '../services/api.js';
import {createBorrowManagement} from '../services/borrow-management.mjs';
import AssetBarcodeScanner from './AssetBarcodeScanner.vue';
import ServerPager from './ServerPager.vue';

const props=defineProps({blocked:Boolean});
const emit=defineEmits(['changed','pending','close']);
const service=createBorrowManagement(api);
const scanner=ref(null),code=ref(''),query=ref(''),preview=ref(null),selected=ref(null);
const loading=ref(false),saving=ref(false),error=ref(''),notice=ref('');
let revision=0,active=true;
const paused=computed(()=>!!code.value);
const resultTitle=computed(()=>preview.value?.action==='issue'?'确认发放':
  preview.value?.action==='return-confirm-direct'?'现场确认归还':
  preview.value?.action==='return-confirm'?'确认归还':'核对资产');

function reset(){
  if(saving.value)return;
  revision++;
  code.value='';query.value='';preview.value=null;selected.value=null;error.value='';loading.value=false;
}
async function identify(value){
  if(!active||paused.value||props.blocked||api.pending())return;
  notice.value='';code.value=value;
  await lookup(1);
}
async function lookup(page){
  if(!code.value||saving.value||props.blocked)return;
  const current=++revision;
  loading.value=true;error.value='';preview.value=null;selected.value=null;
  try{
    const result=await service.scanAsset(code.value,{q:query.value,page});
    if(current!==revision)return;
    preview.value=result;selected.value=result.record;
  }catch(e){
    if(current===revision)error.value=e.message||'无法读取资产，请重试';
  }finally{if(current===revision)loading.value=false;}
}
async function confirm(){
  const row=selected.value,action=preview.value?.action;
  if(!row||!action||loading.value||saving.value||props.blocked||api.pending())return;
  saving.value=true;error.value='';
  try{
    const target=action==='return-confirm-direct'?{...row,allowedActions:[action]}:row;
    await service.execute(target,action,{assetNumber:code.value});
    if(!active)return;
    emit('pending',api.pending());emit('changed');
    notice.value=(action==='issue'?'发放':'归还')+'成功 · '+code.value;
    saving.value=false;
    reset();
  }catch(e){
    if(!active)return;
    emit('pending',api.pending());
    preview.value=null;selected.value=null;
    error.value=e.message||'办理未完成，请重新核对';
  }finally{saving.value=false;}
}
onMounted(async()=>{
  // #ifdef H5
  await nextTick();
  if(active)scanner.value?.start();
  // #endif
});
onBeforeUnmount(()=>{active=false;revision++;});
</script>

<style scoped>
.quick-scan,.quick-result{background:#fff;border:1px solid #dce4f2;border-radius:12px;padding:16px}
.quick-scan{margin-bottom:16px}
.quick-result{display:grid;gap:12px;color:#203553}
.quick-result .block-title{margin:0}
.quick-number{font-weight:650;overflow-wrap:anywhere}
.quick-search{flex:1;min-width:135px}
.quick-candidates,.quick-check{display:grid;gap:8px}
.quick-check{padding:12px;background:#f3f6fa;border-radius:8px}
.quick-candidates .btn{text-align:left;white-space:normal;overflow-wrap:anywhere}
.quick-candidates .chosen{border-color:#4264eb;color:#3553c6}
.quick-result .button-row{flex-wrap:wrap}
</style>
