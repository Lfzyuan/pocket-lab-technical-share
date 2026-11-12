<template>
  <view class="panel asset-import" aria-label="导入资产编号">
    <view class="button-row asset-import-heading">
      <text class="block-title">导入资产编号</text>
      <button v-if="!expanded" role="button" class="btn secondary" :disabled="disabled" @click="expanded=true">打开导入</button>
      <button v-else role="button" class="text-btn" :disabled="busy" @click="close">收起导入</button>
    </view>
    <template v-if="expanded">
      <text class="muted">Excel 第一行填写“器材ID”和“资产编号”，每行对应一件实物。器材ID可在资产台账中查看，请将编号列设为文本以保留前导零。</text>
      <text class="small">导入只给已有器材登记编号，不增加库存。</text>
      <!-- #ifdef H5 -->
      <view class="button-row"><button role="button" class="btn secondary" :disabled="blocked" @click="chooseWorkbook">选择 Excel 文件</button><text v-if="fileName" class="small">{{fileName}}</text></view>
      <ChoiceField v-if="sheets.length>1" :model-value="sheetIndex" :options="sheetChoices" :disabled="blocked" @update:modelValue="selectSheet"/>
      <!-- #endif -->
      <view class="field-group"><text>也可从 Excel 复制两列（含表头）</text><textarea :value="pasted" :disabled="blocked" class="field textarea asset-import-text" aria-label="粘贴资产编号两列" maxlength="1000000" @input="editPaste($event.detail.value)"/></view>
      <view class="button-row">
        <button role="button" class="btn secondary" :disabled="blocked||!source" @click="preview">{{busy?'处理中…':'校验预览'}}</button>
        <button role="button" class="btn" :disabled="blocked||!report?.canCommit" @click="commit">确认导入</button>
      </view>
      <view v-if="error" class="error" role="alert">{{error}}</view>
      <view v-if="notice" class="success" role="status">{{notice}}</view>
      <template v-if="report">
        <text class="small" role="status">共 {{report.summary.total}} 行，可导入 {{report.summary.valid}}，已存在 {{report.summary.existing}}，重复 {{report.summary.duplicate}}，无效 {{report.summary.invalid}}</text>
        <view v-for="row in shownRows" :key="row.row" class="asset-import-row">
          <text>第 {{row.row}} 行</text><text>{{row.equipmentId}} · {{row.assetNumber}}</text><text>{{statusLabels[row.status]}} {{row.message}}</text>
        </view>
        <ServerPager :total="report.rows.length" :page="page" :page-size="10" @change="page=$event"/>
      </template>
    </template>
  </view>
</template>
<script setup>
import {computed,inject,onBeforeUnmount,ref} from 'vue';
import {api as defaultApi} from '../services/api.js';
import {assetImportRows,hasAssetHeader,pastedAssetImportRows} from '../services/asset-import.mjs';
import ChoiceField from './ChoiceField.vue';
import ServerPager from './ServerPager.vue';

const props=defineProps({disabled:Boolean});
const emit=defineEmits(['pending']);
const api=inject('portalClient',defaultApi);
const expanded=ref(false),busy=ref(false),source=ref(''),pasted=ref(''),fileName=ref('');
const sheets=ref([]),sheetIndex=ref('0'),report=ref(null),page=ref(1),error=ref(''),notice=ref('');
const blocked=computed(()=>props.disabled||busy.value||!!api.pending());
const statusLabels={valid:'可导入',invalid:'无效',duplicate:'表内重复',existing:'已登记'};
const sheetChoices=computed(()=>sheets.value.map((sheet,index)=>({value:String(index),label:sheet.name})));
const shownRows=computed(()=>report.value?.rows.slice((page.value-1)*10,page.value*10)||[]);
let alive=true;
onBeforeUnmount(()=>{alive=false;});

function invalidate(){report.value=null;page.value=1;error.value='';notice.value='';}
function reset(){source.value='';pasted.value='';fileName.value='';sheets.value=[];sheetIndex.value='0';invalidate();}
function close(){if(!busy.value){expanded.value=false;reset();}}
function editPaste(value){pasted.value=value;source.value=value.trim()?'paste':'';fileName.value='';sheets.value=[];invalidate();}
function selectSheet(value){if(!blocked.value){sheetIndex.value=value;invalidate();}}
// #ifdef H5
function chooseWorkbook(){
  if(blocked.value)return;
  const input=document.createElement('input');input.type='file';input.accept='.xlsx';
  input.addEventListener('change',()=>{const file=input.files?.[0];if(file&&alive&&!blocked.value)readWorkbook(file);},{once:true});
  input.click();
}
async function readWorkbook(file){
  reset();busy.value=true;
  try{
    const readerUrl='/features/xlsx-reader.js?v=asset-import-1';
    const {readXlsx}=await import(/* @vite-ignore */ readerUrl);
    const matching=(await readXlsx(file,{checkIdentifierPrecision:true})).filter(hasAssetHeader);
    if(!alive)return;
    if(!matching.length)throw Error('未找到“器材ID”和“资产编号”两列工作表');
    sheets.value=matching;fileName.value=file.name;source.value='file';
  }catch(e){if(alive)error.value=e.message||'暂时无法读取 Excel 文件';}finally{busy.value=false;}
}
// #endif
async function preview(){
  if(blocked.value||!source.value)return;
  invalidate();busy.value=true;
  try{
    const rows=source.value==='file'?assetImportRows(sheets.value[Number(sheetIndex.value)]):pastedAssetImportRows(pasted.value);
    const {data}=await api.request('/assets/import/preview',{method:'POST',data:{rows}});
    if(alive)report.value=data;
  }catch(e){if(alive)error.value=e.message||'暂时无法预览资产编号';}finally{busy.value=false;}
}
async function commit(){
  if(blocked.value||!report.value?.canCommit)return;
  busy.value=true;error.value='';
  try{
    const result=await api.mutate('/assets/import/commit',{planId:report.value.planId});
    emit('pending',api.pending());
    if(alive){reset();notice.value=`已登记 ${result.added} 个资产编号。`;}
  }catch(e){
    emit('pending',api.pending());
    if(alive){error.value=e.message||'暂时无法导入，请核对上次结果';if(e.status===409)report.value=null;}
  }finally{busy.value=false;}
}
</script>
<style scoped>
.asset-import-heading{justify-content:space-between;align-items:center;gap:12px}
.asset-import .button-row,.asset-import .small{margin-top:10px}
.asset-import-text{min-height:110px}
.asset-import-row{display:grid;grid-template-columns:85px minmax(0,1.1fr) minmax(0,1fr);gap:12px;padding:10px 0;border-top:1px solid #dce4ec;overflow-wrap:anywhere}
@media(max-width:600px){.asset-import-row{grid-template-columns:minmax(0,1fr);gap:4px}}
</style>
