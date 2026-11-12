<template>
  <view class="field-group">
    <text>代领人</text>
    <input :value="query" class="field" maxlength="80" placeholder="输入姓名、学号或工号"
      role="combobox" :aria-expanded="open&&lookupState.items.length>0"
      @input="search($event.detail.value)" @focus="focus" @blur="deferClose" @confirm="selectFirst"/>
    <view v-if="open&&lookupState.items.length" class="pickup-suggestions" role="listbox" aria-label="代领候选人">
      <button v-for="person in lookupState.items" :key="person.id" role="option" class="pickup-option"
        @mousedown.prevent @click="select(person)">
        <text>{{person.name}}</text><text class="small">{{person.account}}</text>
      </button>
    </view>
    <text v-else-if="open&&lookupState.phase==='loading'" class="small" role="status">正在检索代领候选人…</text>
    <text v-else-if="open&&lookupState.phase==='ready'" class="small" role="status">暂无匹配的代领候选人。</text>
    <text v-else-if="open&&lookupState.phase==='error'" class="small" role="status">暂时无法检索，请稍后重试。</text>
    <text v-if="modelValue" class="small">已选择。申请提交后，由代领人选择领取时间。</text>
  </view>
</template>
<script setup>
import {onUnmounted,ref} from 'vue';
import {api} from '../services/api.js';
import {createMemberLookup} from '../services/member-lookup.mjs';

const props=defineProps({modelValue:{type:String,default:''}});
const emit=defineEmits(['update:modelValue']);
const query=ref(''),open=ref(false),lookupState=ref({phase:'idle',items:[]});
let blurTimer;
const lookup=createMemberLookup({
  search:async keyword=>(await api.request('/users/pickup-candidates?keyword='+encodeURIComponent(keyword)+'&pageSize=8')).data.items,
  onState:state=>lookupState.value=state
});

function search(value){
  clearTimeout(blurTimer);
  query.value=value;
  if(props.modelValue)emit('update:modelValue','');
  open.value=true;
  lookup.lookup(value);
}
function focus(){
  clearTimeout(blurTimer);
  if(!props.modelValue)search(query.value);
}
function close(){
  open.value=false;
  lookup.clear();
}
function deferClose(){
  clearTimeout(blurTimer);
  blurTimer=setTimeout(close,180);
}
function select(person){
  clearTimeout(blurTimer);
  query.value=person.name+' · '+person.account;
  emit('update:modelValue',person.id);
  close();
}
function selectFirst(){
  if(open.value&&lookupState.value.items.length)select(lookupState.value.items[0]);
}
onUnmounted(()=>{clearTimeout(blurTimer);lookup.dispose();});
</script>
<style scoped>
.pickup-suggestions{margin-top:5px;border:1px solid #cbd6e8;border-radius:7px;overflow:hidden}
.pickup-option{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;padding:8px 12px;margin:0;border:0;border-bottom:1px solid #e7eef5;border-radius:0;color:#243c5f;background:#fff;font-size:14px;line-height:24px}
.pickup-option:last-child{border-bottom:0}.pickup-option:hover,.pickup-option:focus{background:#edf3fc}.pickup-option::after{border:0}
</style>
