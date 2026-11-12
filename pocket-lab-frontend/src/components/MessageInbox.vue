<template>
  <view class="inbox">
    <view class="inbox-heading">
      <view class="inbox-title-line">
        <text class="page-title">我的消息</text>
        <text class="inbox-unread">{{unread}} 条未读</text>
      </view>
      <button v-if="!detail" role="button" class="btn secondary small"
        :disabled="blocked" @click="toggleManaging">{{managing?'完成':'管理'}}</button>
    </view>
    <text v-if="error" class="error" role="alert">{{error}}</text>
    <template v-if="detail">
      <button role="button" class="text-btn detail-back" @click="detail=null">‹ 返回消息列表</button>
      <view class="panel inbox-document">
        <text class="card-title">{{detail.title}}</text>
        <text class="muted">{{formatTime(detail.at)}}</text>
        <text class="inbox-body">{{detail.body}}</text>
      </view>
    </template>
    <template v-else>
      <view class="inbox-toolbar">
        <view class="inbox-filters" role="group" aria-label="消息筛选">
          <button v-for="option in filters" :key="option.id" role="button" class="chip"
            :class="{selected:filter===option.id}" :aria-pressed="filter===option.id"
            :disabled="busy||loading" @click="changeFilter(option.id)">{{option.name}}</button>
        </view>
        <view v-if="managing" class="inbox-selection">
          <button role="button" class="text-btn" :disabled="blocked||!rows.length"
            :aria-pressed="allSelected" @click="selectPage">{{allSelected?'取消全选':'全选本页'}}</button>
          <text class="muted">已选择 {{selected.length}} 条</text>
        </view>
      </view>
      <view class="panel inbox-list">
        <text v-if="loading" class="empty" role="status">正在读取消息…</text>
        <text v-else-if="!rows.length" class="empty">{{filter==='unread'?'暂无未读消息':'暂无消息'}}</text>
        <checkbox-group v-else @change="selected=$event.detail.value">
          <view v-for="row in rows" :key="row.id" class="inbox-row"
            :class="{'is-unread':!row.read,'is-selected':managing&&selected.includes(row.id)}">
            <label v-if="managing" class="inbox-check">
              <checkbox :value="row.id" :checked="selected.includes(row.id)" :disabled="blocked"/>
              <text class="inbox-sr">{{'选择 '+row.title}}</text>
            </label>
            <button role="button" class="inbox-subject" :disabled="blocked" @click="open(row)">
              <text v-if="!row.read" class="inbox-dot" aria-label="未读"></text>
              <text class="inbox-title">{{row.title}}</text>
              <text class="inbox-summary">{{row.body}}</text>
              <text class="inbox-time">{{formatTime(row.at)}}</text>
            </button>
          </view>
        </checkbox-group>
      </view>
      <view v-if="managing" class="inbox-actions">
        <text class="small">全选仅作用于当前页</text>
        <view class="button-row">
          <button role="button" class="btn secondary small" :disabled="blocked||!unread"
            @click="act('read-all')">全部已读</button>
          <button role="button" class="btn secondary small" :disabled="blocked||!selected.length"
            @click="deleteSelected">删除所选{{selected.length?'（'+selected.length+'）':''}}</button>
          <button role="button" class="btn secondary small inbox-clear" :disabled="blocked||!hasMessages"
            @click="clearAll">清空全部</button>
        </view>
      </view>
      <ServerPager :page="page" :total="total" :page-size="15" :disabled="loading||busy" @change="load"/>
    </template>
  </view>
</template>

<script setup>
import {computed, inject, onBeforeUnmount, onMounted, ref} from 'vue';
import {api as defaultApi} from '../services/api.js';
import ServerPager from './ServerPager.vue';
const props=defineProps({verified:Boolean});
const emit=defineEmits(['pending','unread']);
const api=inject('portalClient',defaultApi);
const rows=ref([]),page=ref(1),total=ref(0),unread=ref(0),hasMessages=ref(false);
const loading=ref(false),busy=ref(false),error=ref(''),filter=ref('all');
const selected=ref([]),detail=ref(null),managing=ref(false);
const blocked=computed(()=>!props.verified||busy.value||loading.value||!!api.pending());
const allSelected=computed(()=>rows.value.length>0&&selected.value.length===rows.value.length);
const filters=[{id:'all',name:'全部'},{id:'unread',name:'未读'}];
let revision=0,alive=true;

function report(value){unread.value=value;emit('unread',value);}
function formatTime(at){
  const time=new Date(at);
  return Number.isNaN(time.getTime())?'':new Date(time.getTime()+8*3600000).toISOString().slice(0,16).replace('T',' ');
}
function toggleManaging(){
  if(blocked.value)return;
  managing.value=!managing.value;selected.value=[];
}
function selectPage(){
  if(!blocked.value)selected.value=allSelected.value?[]:rows.value.map(row=>row.id);
}
async function load(next=1){
  const current=++revision;
  loading.value=true;error.value='';selected.value=[];
  try{
    const {data}=await api.request('/notifications?pageSize=15&page='+next+(filter.value==='unread'?'&read=false':''));
    if(current!==revision)return;
    if(next>1&&!data.items.length){await load(Math.max(1,Math.ceil(data.total/15)));return;}
    rows.value=data.items;page.value=data.page;total.value=data.total;
    if(filter.value==='all')hasMessages.value=data.total>0;
    report(data.unreadCount);
  }catch(e){if(current===revision)error.value=e.message||'消息暂时无法读取';}
  finally{if(current===revision)loading.value=false;}
}
function changeFilter(next){if(!blocked.value&&next!==filter.value){filter.value=next;load(1);}}
async function mutation(path,body,after){
  if(blocked.value)return;
  busy.value=true;error.value='';
  try{
    const data=await api.mutate(path,body);
    if(!alive)return;
    report(data.unreadCount);
    await after(data);
  }catch(e){if(alive)error.value=e.message||'消息操作失败';}
  finally{if(alive){busy.value=false;emit('pending',api.pending());}}
}
function open(row){
  if(blocked.value)return;
  if(managing.value){
    selected.value=selected.value.includes(row.id)?selected.value.filter(id=>id!==row.id):[...selected.value,row.id];
    return;
  }
  detail.value=row;
  if(!row.read)mutation('/notifications/'+encodeURIComponent(row.id)+'/read',{},async()=>{
    row.read=true;
    if(filter.value==='unread')await load(page.value);
  });
}
function act(action,ids){
  return mutation('/notifications/batch',{action,...(ids?{ids}:{})},async()=>{
    if(action==='delete-all'){hasMessages.value=false;await load(1);}
    else await load(page.value);
  });
}
function deleteSelected(){if(selected.value.length)act('delete-selected',[...selected.value]);}
function clearAll(){
  if(blocked.value)return;
  uni.showModal({title:'清空全部消息',content:'确认删除全部消息，包括未读消息？',confirmText:'清空',success:result=>{
    if(result.confirm&&alive)act('delete-all');
  }});
}
onMounted(()=>load());
onBeforeUnmount(()=>{alive=false;revision++;});
</script>

<style scoped>
.inbox-heading,.inbox-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:16px}
.inbox-heading>.btn{margin:0}
.inbox-title-line{display:flex;align-items:center;gap:18px;flex-wrap:wrap}
.inbox-title-line .page-title{margin:0}
.inbox-unread{font-size:13px;color:var(--lab-muted,#4c5e78);white-space:nowrap}
.inbox-filters,.inbox-selection{display:flex;align-items:center;gap:12px}
.inbox-list{padding:0}.inbox-document{display:grid;gap:12px}.inbox-body{white-space:pre-wrap;overflow-wrap:anywhere}
.inbox-row{display:flex;align-items:center;padding:9px 12px;border-bottom:1px solid var(--lab-line,#e7ebf2);min-width:0;gap:8px}
.inbox-row:last-child{border-bottom:none}.inbox-row.is-unread{background:#f4f7ff}
.inbox-row.is-selected{background:#edf1ff}
.inbox-subject{display:grid;grid-template-columns:8px minmax(110px,1fr) minmax(120px,2fr) 122px;align-items:center;gap:8px;text-align:left;margin:0;width:100%;min-width:0;background:transparent;color:inherit;border:0;line-height:1.4;padding:6px 0;font-size:14px}
.inbox-subject::after{border:none}.inbox-title,.inbox-summary{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.inbox-title{grid-column:2;font-weight:600}
.inbox-dot{grid-column:1;grid-row:1;width:7px;height:7px;background:#c52d41;border-radius:50%}
.inbox-time,.inbox-summary{color:var(--lab-muted,#4c5e78);font-size:12px}
.inbox-actions{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;padding:12px 0}
.inbox-actions>.button-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.inbox-actions .btn{min-width:110px;margin:0}
.inbox-actions .inbox-clear{color:#b63a4a}
.inbox-sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}
@media(max-width:720px){
  .inbox-title-line{gap:14px}
  .inbox-subject{grid-template-columns:7px minmax(0,1fr) auto}
  .inbox-time{grid-column:3;grid-row:1}.inbox-summary{grid-column:2/4}
  .inbox-actions>.button-row{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;width:100%}
  .inbox-actions .btn{min-width:0;width:100%;font-size:12px;padding:8px 4px}
}
</style>
