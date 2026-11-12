<template>
  <view class="teacher-choice">
    <input class="field teacher-input" :value="text" maxlength="80" :disabled="disabled"
      placeholder="输入老师姓名或工号" aria-label="指导老师姓名或工号"
      role="combobox" :aria-expanded="showSuggestions" aria-autocomplete="list"
      @input="change($event.detail.value)" @focus="focus" @blur="deferClose"/>
    <text v-if="lookupState.phase==='loading'" class="muted teacher-feedback" role="status">正在匹配教师…</text>
    <view v-else-if="lookupState.phase==='error'" class="error teacher-feedback" role="alert">
      教师查询失败，请重试。当前输入尚未匹配。
      <button role="button" class="text-btn" :disabled="disabled" @click="retry">重新查询</button>
    </view>
    <text v-else-if="chosen" class="muted teacher-feedback" role="status">
      已匹配：{{chosen.name}}{{details(chosen)?' · '+details(chosen):''}}
    </text>
    <text v-else-if="historical" class="muted teacher-feedback">保留原记录中的指导老师。修改后需重新匹配教师账号。</text>
    <text v-else-if="lookupState.phase==='ready'" class="teacher-feedback" role="status">
      {{lookupState.data?.exactTotal>1?'存在同名教师，请核对部门和工号后选择。':
        lookupState.items.length?'请核对并选择下方教师。':'未找到匹配教师，请检查姓名或工号。'}}
    </text>
    <view v-if="showSuggestions" class="teacher-suggestions" role="listbox" aria-label="指导老师搜索建议">
      <button v-for="teacher in lookupState.items" :key="teacher.id" role="option"
        class="teacher-option" :disabled="disabled" @mousedown.prevent @click="select(teacher)">
        <text>{{teacher.name}}</text><text class="muted">{{details(teacher)}}</text>
      </button>
      <text v-if="lookupState.data?.total>lookupState.items.length" class="muted teacher-more">
        还有更多匹配教师，请补充姓名或工号。
      </text>
    </view>
  </view>
</template>

<script setup>
import {computed, inject, onBeforeUnmount, ref, watch} from 'vue';
import {api as defaultApi} from '../services/api.js';
import {createDirectoryLookup} from '../services/member-lookup.mjs';

const props=defineProps({
  modelValue:{type:String,default:''}, selectedName:{type:String,default:''},
  required:Boolean, disabled:Boolean
});
const emit=defineEmits(['update:modelValue','validity']);
const api=inject('portalClient',defaultApi);
const text=ref(props.selectedName),chosen=ref(null),edited=ref(false),focused=ref(false);
const lookupState=ref({phase:'idle',items:[],query:'',data:null});
let blurTimer;
const historical=computed(()=>!edited.value&&!!props.selectedName.trim());
const valid=computed(()=>{
  if(chosen.value&&props.modelValue===chosen.value.id&&text.value===chosen.value.name)return true;
  if(!edited.value&&(props.modelValue||historical.value))return true;
  return !props.required&&!text.value.trim()&&!props.modelValue;
});
const showSuggestions=computed(()=>focused.value&&!chosen.value&&lookupState.value.phase==='ready'&&lookupState.value.items.length>0);

const lookup=createDirectoryLookup({
  delay:250,
  search:async keyword=>{
    const suffix='&keyword='+encodeURIComponent(keyword);
    const [matches,exact]=await Promise.all([
      api.request('/users/teachers?pageSize=20&page=1'+suffix),
      api.request('/users/teachers?pageSize=2&page=1&exact=true'+suffix)
    ]);
    if(!Array.isArray(matches.data?.items)||!Array.isArray(exact.data?.items))throw Error('Invalid teacher directory');
    return {...matches.data,exactTotal:exact.data.total,exactItems:exact.data.items};
  },
  onState:state=>{
    lookupState.value=state;
    if(state.phase==='ready'&&state.query===text.value.trim()&&edited.value&&
      state.data.exactTotal===1&&state.data.exactItems.length===1){
      select(state.data.exactItems[0]);
    }
  }
});

function details(teacher){return [teacher.department,teacher.account].filter(Boolean).join(' · ');}
function select(teacher){
  if(props.disabled)return;
  clearTimeout(blurTimer);
  chosen.value=teacher;
  text.value=teacher.name;
  focused.value=false;
  lookup.clear();
  emit('update:modelValue',teacher.id);
}
function change(value){
  if(props.disabled)return;
  edited.value=true;
  chosen.value=null;
  text.value=value;
  focused.value=true;
  emit('update:modelValue','');
  emit('validity',!props.required&&!String(value).trim());
  lookup.lookup(value);
}
function focus(){
  clearTimeout(blurTimer);
  focused.value=true;
  if(edited.value&&!chosen.value&&text.value.trim()&&lookupState.value.phase==='idle')lookup.lookup(text.value);
}
function deferClose(){clearTimeout(blurTimer);blurTimer=setTimeout(()=>focused.value=false,180);}
function retry(){focused.value=true;lookup.lookup(text.value);}
watch([()=>props.modelValue,()=>props.selectedName],()=>{
  if(!edited.value)text.value=props.selectedName||chosen.value?.name||'';
});
watch(valid,value=>emit('validity',value),{immediate:true});
onBeforeUnmount(()=>{clearTimeout(blurTimer);lookup.dispose();});
</script>

<style scoped>
.teacher-choice{position:relative;display:grid;gap:6px;min-width:0}
.teacher-input{width:100%;box-sizing:border-box;margin:0}
.teacher-feedback,.teacher-more{display:block;font-size:12px;line-height:1.7}
.teacher-suggestions{border:1px solid #dce4ef;border-radius:7px;overflow:hidden;background:#fff}
.teacher-option{display:flex;justify-content:space-between;gap:10px;align-items:center;margin:0;width:100%;padding:9px 12px;background:#fff;border:0;border-bottom:1px solid #e8edf5;border-radius:0;text-align:left;font-size:14px;line-height:1.5;color:#243c5f}
.teacher-option:last-of-type{border-bottom:0}
.teacher-option:hover,.teacher-option:focus{background:#eef2ff;color:var(--lab-blue,#4264eb)}
.teacher-option::after{border:none}
.teacher-more{padding:8px 12px}
@media(max-width:600px){.teacher-option{flex-wrap:wrap;gap:2px 8px}}
</style>
