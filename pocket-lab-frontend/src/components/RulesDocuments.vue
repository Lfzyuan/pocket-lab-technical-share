<template>
<view class="rules-documents">
 <view class="section-top"><view><text class="page-title">管理办法与使用须知</text><text class="muted">阅读全文查看管理员已发布的正文。</text></view></view>
 <view v-if="error" class="error" role="alert">{{error}}</view><view v-if="notice" class="success" role="status">{{notice}}</view><text v-if="loading" class="loading">正在读取文档…</text>
 <view v-if="pendingOperation" class="warning">发布结果尚待确认，编辑内容已保留。请先核对已发布正文，或使用页面上方的原操作凭证重试确认，勿重新发布。</view>
 <view v-for="row in rows" :key="row.kind" :data-regulation-kind="row.kind" :class="['panel regulation-card',{focused:row.kind===initialKind}]">
  <text class="block-title">{{row.kind==='management'?'1.':'2.'}} {{row.title}}</text>
  <text v-if="contentErrors[row.kind]" class="error">{{contentErrors[row.kind]}}</text>
  <template v-else-if="readableRegulationBody(contents[row.kind])"><text class="content-state">正文已发布</text><text v-if="contents[row.kind]?.updatedAt" class="small">正文更新于 {{regulationUpdatedAt(contents[row.kind].updatedAt)}}</text></template>
  <text v-else class="muted">正文尚未发布，请等待管理员维护。</text>
  <view class="button-row">
   <button role="button" class="btn" :disabled="!!busy" :aria-disabled="!!busy" @click="emit('read',row.kind)">阅读全文</button>
   <button v-if="isAdmin&&editorKind!==row.kind" role="button" class="btn secondary" :disabled="!!busy||!canEdit(row.kind)" :aria-disabled="!!busy||!canEdit(row.kind)" @click="edit(row)">编辑正文</button>
  </view>
  <view v-if="editorKind===row.kind&&isAdmin" class="regulation-editor">
   <text class="editor-label">{{row.kind==='management'?'管理办法正文':'使用须知正文'}}</text><text class="small">输入纯文本，保留段落和换行；不解析HTML或Markdown。保存后立即向所有成员和游客发布。</text>
   <textarea v-model="drafts[row.kind]" class="field regulation-body-input" :aria-label="row.kind==='management'?'管理办法正文':'使用须知正文'" :maxlength="-1" :disabled="!!busy||!verified||!!pendingOperation" @input="reviewed[row.kind]=false"/>
   <text class="small">{{(drafts[row.kind]||'').length}} / 100000 字符</text><text v-if="draftError(row.kind)" class="error">{{draftError(row.kind)}}</text>
   <view v-if="conflicts[row.kind]" class="warning"><text>正文已被其他人更新，当前编辑内容仍保留。请读取最新版本并核对后再发布。</text><button role="button" class="btn secondary" :disabled="!!busy" @click="reloadContent(row.kind,true)">读取最新版本</button></view>
   <view v-if="reviewRequired[row.kind]" class="latest-published"><text class="editor-label">最新已发布正文（供核对）</text><text class="latest-body">{{readableRegulationBody(contents[row.kind])||'尚未发布正文'}}</text><checkbox-group @change="reviewed[row.kind]=$event.detail.value.includes('checked')"><label class="check-row"><checkbox value="checked" :checked="!!reviewed[row.kind]" :disabled="!!busy||!verified"/><text>已核对最新正文，采用当前编辑内容发布</text></label></checkbox-group></view>
   <view class="button-row"><button role="button" class="btn" :disabled="!canSave(row.kind)" :aria-disabled="!canSave(row.kind)" @click="save(row.kind)">{{busy===row.kind&&busyAction==='save'?'正在发布…':'保存并发布'}}</button><button role="button" class="btn secondary" :disabled="!!busy" @click="cancelEdit">取消编辑</button><button role="button" class="text-btn" :disabled="!!busy" @click="reloadContent(row.kind)">重新读取已发布正文</button></view><text class="small">取消编辑或发布失败均保留本页草稿；离开页面前请自行保存未发布内容。</text>
  </view>
 </view>

</view>
</template>
<script setup>
import {inject,ref,computed,onMounted,onUnmounted,watch} from 'vue';
import {api as defaultApi} from '../services/api.js';
import {guestApi} from '../services/guest-api.js';
import {REGULATION_CONTENT_KINDS,REGULATION_TITLES,readableRegulationBody,regulationContentPath,regulationWriteBody,validateRegulationBody,regulationUpdatedAt,sameRegulationText} from '../services/regulation-content.mjs';
const api=inject('portalClient',defaultApi),portalGuest=inject('portalGuest',()=>false);
const props=defineProps({user:Object,verified:Boolean,initialKind:String,guest:Boolean,pending:{type:Object,default:null}}),emit=defineEmits(['pending','read']);
const rows=ref(defaultRows()),contents=ref({}),contentErrors=ref({}),loading=ref(false),busy=ref(''),busyAction=ref(''),error=ref(''),notice=ref(''),editorKind=ref(''),drafts=ref({}),versions=ref({}),conflicts=ref({}),reviewRequired=ref({}),reviewed=ref({}),pendingOperation=ref(null);
const isGuest=computed(()=>props.guest||props.user?.guest||portalGuest()),isAdmin=computed(()=>!isGuest.value&&(props.user?.accountRole||props.user?.role)==='admin');
let disposed=false,contextRevision=0,loadRevision=0,operation=0;
function defaultRows(){return REGULATION_CONTENT_KINDS.map(kind=>({kind,title:REGULATION_TITLES[kind]}));}
function captureContext(){return {revision:contextRevision,guest:!!isGuest.value,userId:props.user?.id||null,admin:isAdmin.value,token:isGuest.value?null:defaultApi.getSession()?.accessToken};}
function sameContext(context){return !disposed&&context.revision===contextRevision&&context.guest===!!isGuest.value&&context.userId===(props.user?.id||null)&&context.admin===isAdmin.value&&(context.guest||context.token===defaultApi.getSession()?.accessToken);}
function syncPending(){pendingOperation.value=isGuest.value?null:api.pending();emit('pending',pendingOperation.value);}
function canEdit(kind){return isAdmin.value&&props.verified&&contents.value[kind]?.allowedActions?.includes('edit');}
function draftError(kind){return validateRegulationBody(drafts.value[kind]??'');}
function canSave(kind){return !busy.value&&canEdit(kind)&&!pendingOperation.value&&!draftError(kind)&&!conflicts.value[kind]&&(!reviewRequired.value[kind]||reviewed.value[kind]);}
async function contentRequest(kind){return isGuest.value||!props.verified?guestApi.request(regulationContentPath(kind,{publicRead:true})):(await api.request(regulationContentPath(kind))).data;}
async function load({reconcileConfirmed=false}={}){
 const stamp=++loadRevision,context=captureContext();loading.value=true;error.value='';syncPending();
 const results=await Promise.allSettled(REGULATION_CONTENT_KINDS.map(contentRequest));
 if(stamp!==loadRevision||!sameContext(context))return;
 REGULATION_CONTENT_KINDS.forEach((kind,index)=>{const result=results[index];if(result.status==='fulfilled'){const record=result.value;contents.value[kind]=record;contentErrors.value[kind]='';if(reconcileConfirmed&&Object.hasOwn(drafts.value,kind)&&readableRegulationBody(record)&&sameRegulationText(drafts.value[kind],record.body)){versions.value[kind]=record.version;conflicts.value[kind]=false;reviewRequired.value[kind]=false;reviewed.value[kind]=false;}}else contentErrors.value[kind]=result.reason?.message||'正文暂时无法读取，请稍后重试';});loading.value=false;
}
function edit(row){if(!canEdit(row.kind)||busy.value)return;syncPending();if(!Object.hasOwn(drafts.value,row.kind)){drafts.value[row.kind]=contents.value[row.kind]?.body||'';versions.value[row.kind]=contents.value[row.kind].version;}editorKind.value=row.kind;error.value='';notice.value='';}
function cancelEdit(){if(busy.value)return;editorKind.value='';notice.value='已取消编辑，本页草稿仍保留。';}
async function reloadContent(kind,afterConflict=false){
 if(busy.value)return;const stamp=++operation,context=captureContext();busy.value=kind;busyAction.value='reload';error.value='';
 try{const record=await contentRequest(kind);if(stamp!==operation||!sameContext(context))return;contents.value[kind]=record;contentErrors.value[kind]='';syncPending();if(afterConflict||conflicts.value[kind]){versions.value[kind]=record.version;conflicts.value[kind]=false;reviewRequired.value[kind]=true;reviewed.value[kind]=false;notice.value='已读取最新版本，当前编辑内容保留。核对并勾选确认后才能发布。';}else notice.value='已重新读取已发布正文，当前编辑内容保留。';}
 catch(e){if(stamp===operation&&sameContext(context))error.value=e.message||'正文暂时无法读取';}finally{if(stamp===operation&&sameContext(context)){busy.value='';busyAction.value='';}}
}
async function save(kind){
 syncPending();if(!canSave(kind))return;let body;try{body=regulationWriteBody(drafts.value[kind],versions.value[kind]);}catch(e){error.value=e.message;return;}
 const stamp=++operation,context=captureContext();busy.value=kind;busyAction.value='save';error.value='';notice.value='';
 try{const record=await api.mutate(regulationContentPath(kind),body,{method:'PUT'});if(stamp!==operation||!sameContext(context))return;contents.value[kind]=record;versions.value[kind]=record.version;drafts.value[kind]=record.body;reviewRequired.value[kind]=false;reviewed.value[kind]=false;conflicts.value[kind]=false;editorKind.value='';syncPending();notice.value='正文已保存并发布。';}
 catch(e){if(stamp!==operation||!sameContext(context))return;syncPending();if(e.status===409&&!e.uncertain){conflicts.value[kind]=true;error.value='正文版本已变化，编辑内容已保留，请读取最新版本后核对。';}else error.value=pendingOperation.value?'发布结果待确认，编辑内容已保留，请勿重新提交。':e.message||'发布未完成，编辑内容已保留。';}
 finally{if(stamp===operation&&sameContext(context)){busy.value='';busyAction.value='';}}
}
function resetContext(){contextRevision++;loadRevision++;operation++;rows.value=defaultRows();contents.value={};contentErrors.value={};drafts.value={};versions.value={};conflicts.value={};reviewRequired.value={};reviewed.value={};editorKind.value='';loading.value=false;busy.value='';busyAction.value='';notice.value='';error.value='';pendingOperation.value=null;if(props.user)load();}
watch(()=>[props.user?.id,!!isGuest.value,isAdmin.value],resetContext,{flush:'sync'});
watch(()=>props.pending,(value,previous)=>{pendingOperation.value=isGuest.value?null:api.pending();if(previous&&!value&&!pendingOperation.value&&props.user)load({reconcileConfirmed:true});});
watch(()=>props.verified,value=>{if(value)load();});
onMounted(load);onUnmounted(()=>{disposed=true;contextRevision++;loadRevision++;operation++;});
defineExpose({reload:load});
</script>
<style scoped>
@import "../pages/index/live.css";
@import "../pages/index/restore-v1.css";
.regulation-card{display:flex;flex-direction:column;gap:14px;line-height:1.8}.regulation-card.focused{border-color:#b0c0e8}.regulation-card .block-title{font-size:18px}.regulation-card .button-row{margin:4px 0;flex-wrap:wrap}.regulation-card .muted{font-size:14px}.content-state{color:#227053}.regulation-editor{border-top:1px solid #e0e6ef;padding-top:18px;display:flex;flex-direction:column;gap:12px;min-width:0}.editor-label{font-weight:600;display:block}.regulation-body-input{width:100%;height:380px;min-height:260px;box-sizing:border-box;line-height:1.9;font-size:15px;white-space:pre-wrap;overflow-wrap:anywhere}.latest-published{display:flex;flex-direction:column;gap:12px;padding:16px;background:#f8fafc;border:1px solid #dfe6ef;border-radius:8px}.latest-body{display:block;max-height:260px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}.rules-documents .btn:not(.secondary){color:#fff!important}@media(max-width:600px){.regulation-card .block-title{font-size:16px}.regulation-card .button-row{gap:10px}.regulation-body-input{height:340px;font-size:16px}.latest-published{padding:12px}}
</style>
