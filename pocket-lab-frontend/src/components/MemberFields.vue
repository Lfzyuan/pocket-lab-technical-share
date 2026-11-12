<template>
  <view class="member-fields">
    <view class="member-self"><text>{{currentUser.name}} · {{currentUser.account}}</text><text class="member-badge">本人 · 已计入</text></view>
    <view v-for="(row,index) in modelValue" :key="index" class="member-card">
      <view class="member-heading"><text>同行人员 {{index+1}}</text><view class="member-heading-actions"><text v-if="row.name||row.account" class="member-badge">{{isSelected(row)?'本院':'手动填写'}}</text><button role="button" class="member-remove" @click="remove(index)">移除</button></view></view>
      <view class="member-input-grid">
        <view><text class="member-label">姓名或学号搜索</text><input :value="row.name" class="field member-input" maxlength="80" placeholder="输入姓名或学号，自动显示建议" role="combobox" :aria-expanded="active===index&&suggestions.length>0" @focus="focus(index,'name')" @input="change(index,'name',$event.detail.value)" @blur="deferClose" @confirm="selectFirst(index)"/></view>
        <view><text class="member-label">学号</text><input :value="row.account" class="field member-input" maxlength="32" placeholder="选择后自动填入，也可手填" role="combobox" :aria-expanded="active===index&&suggestions.length>0" @focus="focus(index,'account')" @input="change(index,'account',$event.detail.value)" @blur="deferClose" @confirm="selectFirst(index)"/></view>
      </view>
      <view v-if="active===index&&suggestions.length" class="member-suggestions" role="listbox" aria-label="同行人员搜索建议">
        <button v-for="person in suggestions" :key="person.id" role="option" class="member-suggestion" @mousedown.prevent @click="select(index,person)"><text>{{person.name}}</text><text class="member-suggestion-meta">{{person.account}}{{person.className?' · '+person.className:''}}</text></button>
      </view>
      <text v-else-if="active===index&&lookupState.phase==='loading'" class="member-note" role="status">正在检索…</text>
      <text v-else-if="active===index&&lookupState.phase==='error'" class="member-note" role="status">暂时无法检索，可手动填写姓名和学号，提交时核对身份。</text>
      <text v-else-if="active===index&&lookupState.phase==='ready'" class="member-note" role="status">暂无匹配人员；外院人员可直接填写姓名和学号。</text>
    </view>
    <button role="button" class="member-add" @click="add">＋ 添加同行人员</button>
  </view>
</template>
<script setup>
import {computed,inject,ref,onUnmounted} from 'vue';
import {api as defaultApi} from '../services/api.js';
import {createMemberLookup} from '../services/member-lookup.mjs';
const props=defineProps({modelValue:{type:Array,default:()=>[]},currentUser:{type:Object,required:true}});
const emit=defineEmits(['update:modelValue']);
const api=inject('portalClient',defaultApi);
const active=ref(-1),lookupState=ref({phase:'idle',items:[],query:''});let blurTimer;
const lookup=createMemberLookup({search:async query=>(await api.request('/users/search?keyword='+encodeURIComponent(query)+'&pageSize=100')).data.items,onState:state=>lookupState.value=state});
const suggestions=computed(()=>lookupState.value.items.filter(person=>person.id!==props.currentUser.id&&!props.modelValue.some((row,index)=>index!==active.value&&row.account===person.account)).slice(0,6));
function close(){clearTimeout(blurTimer);active.value=-1;lookup.clear();}
function focus(index,key){clearTimeout(blurTimer);active.value=index;lookup.lookup(props.modelValue[index][key]);}
function change(index,key,value){emit('update:modelValue',props.modelValue.map((row,n)=>n===index?{...row,[key]:value,selected:null}:row));clearTimeout(blurTimer);active.value=index;lookup.lookup(value);}
function select(index,person){emit('update:modelValue',props.modelValue.map((row,n)=>n===index?{name:person.name,account:person.account,selected:person}:row));close();}
function selectFirst(index){if(active.value===index&&suggestions.value.length)select(index,suggestions.value[0]);}
function deferClose(){clearTimeout(blurTimer);blurTimer=setTimeout(close,180);}
function isSelected(row){return row.selected?.account===row.account&&row.selected?.name===row.name;}
function add(){close();emit('update:modelValue',[...props.modelValue,{name:'',account:''}]);}
function remove(index){close();emit('update:modelValue',props.modelValue.filter((_,n)=>n!==index));}
onUnmounted(()=>{clearTimeout(blurTimer);lookup.dispose();});
</script>
<style scoped>
.member-fields{margin-top:8px;color:#1d304b}.member-self{display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;background:#eff3fa;border:1px solid #dce4f0;padding:10px 12px;border-radius:8px;margin-bottom:12px;font-size:14px}.member-badge{background:#e9eff9;color:#35568c;padding:3px 8px;border-radius:5px;font-size:12px;line-height:20px}.member-card{border:1px solid #d1dbe9;background:white;border-radius:8px;padding:14px;margin-bottom:10px}.member-heading,.member-heading-actions{display:flex;align-items:center;justify-content:space-between;gap:10px}.member-heading{font-size:14px;font-weight:600;margin-bottom:10px}.member-remove{background:transparent;border:0;padding:0;margin:0;color:#9a342c;font-size:13px;line-height:28px}.member-input-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.member-label{display:block;font-size:13px;margin-bottom:6px}.member-input{width:100%;box-sizing:border-box;min-width:0;height:44px;min-height:44px;margin:0;font-size:14px}.member-suggestions{margin-top:6px;border:1px solid #cbd6e8;border-radius:7px;overflow:hidden;box-shadow:0 4px 12px #19355912}.member-suggestion{display:flex;justify-content:space-between;align-items:center;gap:8px;width:100%;margin:0;padding:9px 12px;background:#fff;border:0;border-bottom:1px solid #e8edf5;border-radius:0;font-size:14px;line-height:24px;text-align:left;color:#243c5f}.member-suggestion:last-child{border-bottom:0}.member-suggestion:hover,.member-suggestion:focus{background:#edf3fc}.member-suggestion-meta{font-size:12px;color:#4d627e}.member-note{display:block;font-size:12px;color:#4d627e;line-height:1.8}.member-note{margin-top:7px}.member-add{display:inline-flex;width:auto;margin:0;padding:8px 13px;border:1px solid #becfe5;border-radius:7px;background:#fff;color:#37598e;font-size:14px;line-height:24px}.member-remove::after,.member-add::after,.member-suggestion::after{border:0}
@media(max-width:600px){.member-card{padding:12px}.member-input-grid{grid-template-columns:1fr;gap:10px}.member-suggestion{flex-wrap:wrap;gap:0 8px}.member-self{font-size:13px}}
</style>
