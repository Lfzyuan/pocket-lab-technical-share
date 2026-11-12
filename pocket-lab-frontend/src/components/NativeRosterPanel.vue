<template>
  <view class="panel native-roster">
    <view class="roster-filters">
      <input class="field" v-model="keyword" placeholder="搜索姓名、学号或工号" maxlength="100"/>
      <ChoiceField v-model="type" :options="types" @update:model-value="resetFilters"/>
      <ChoiceField v-model="enabled" :options="states"/>
      <ChoiceField v-if="!type||type==='student'" v-model="groupKey" :options="groups"/>
      <ChoiceField v-if="type==='teacher'" v-model="department" :options="departments"/>
    </view>
    <view v-if="group && !loading" class="button-row">
      <button class="btn secondary" :disabled="blocked" @click="manageGroup('disable')">停用该组全部账号</button>
      <button class="btn secondary" :disabled="blocked" @click="manageGroup('delete')">删除该组全部账号</button>
    </view>
    <text v-if="error" class="error" role="alert">{{error}}</text>
    <text v-if="loading" class="muted">正在读取账号…</text>
    <view v-for="user in visibleRows" :key="user.id" class="roster-person">
      <view>
        <text class="card-title">{{user.name}} · {{user.account}}</text>
        <text class="muted">{{typeLabel(user)}} · {{user.enabled?'已启用':'已停用'}}</text>
      </view>
      <view v-if="user.role!=='admin'" class="button-row">
        <button class="btn secondary small" :disabled="blocked" @click="openProfile(user)">编辑资料</button>
        <BorrowLimitControl :user-record="user" :verified="verified" :blocked="blocked"
          @pending="emit('pending',$event)" @error="error=$event" @updated="load"/>
        <button class="btn secondary small" :disabled="blocked" @click="emit('toggle',user)">{{user.enabled?'停用':'启用'}}</button>
      </view>
    </view>
    <text v-if="!loading&&!error&&!filtered.length" class="muted">没有找到账号。</text>
    <ServerPager :total="filtered.length" :page="page" :page-size="15" :disabled="blocked" @change="page=$event"/>
    <view v-if="profile" class="roster-editor">
      <text class="card-title">编辑账号资料</text>
      <label>姓名<input class="field" v-model="profile.name" maxlength="80"/></label>
      <label>{{profile.teacher?'部门':'官方班级'}}<input class="field" v-model="profile.className" maxlength="100"/></label>
      <label>联系电话<input class="field" v-model="profile.phone" maxlength="32"/></label>
      <label>邮箱<input class="field" v-model="profile.email" maxlength="200"/></label>
      <view class="button-row">
        <button class="btn secondary" :disabled="busy" @click="profile=null">取消</button>
        <button class="btn" :disabled="blocked" @click="saveProfile">保存资料</button>
      </view>
    </view>
  </view>
</template>

<script setup>
import {computed, inject, onBeforeUnmount, onMounted, ref, watch} from 'vue';
import {api as defaultApi} from '../services/api.js';
import BorrowLimitControl from './BorrowLimitControl.vue';
import ChoiceField from './ChoiceField.vue';
import ServerPager from './ServerPager.vue';

const props=defineProps({verified:Boolean,locked:Boolean,refreshToken:Number});
const emit=defineEmits(['pending','toggle']);
const api=inject('portalClient',defaultApi);
const users=ref([]),type=ref(''),enabled=ref(''),keyword=ref(''),groupKey=ref(''),department=ref('');
const page=ref(1),profile=ref(null),loading=ref(false),busy=ref(false),error=ref('');
let revision=0,alive=true;
const options=(values)=>values.map(([value,label])=>({value,label}));
const types=options([['','全部账号类型'],['student','学生账号'],['teacher','教师账号'],['admin','管理员账号']]);
const states=options([['','全部状态'],['true','已启用'],['false','已停用']]);
const blocked=computed(()=>!props.verified||props.locked||loading.value||busy.value||!!api.pending());
const student=user=>user.accountType==='student'&&user.role==='student';
const distinct=(key,predicate)=>[...new Set(users.value.filter(predicate).map(user=>user[key]).filter(Boolean))].sort();
const groups=computed(()=>options([['','全部班级与年级'],
  ...distinct('officialClassName',student).map(value=>['class:'+value,value]),
  ...distinct('classCohort',student).map(value=>['year:'+value,value+'级'])]));
const departments=computed(()=>options([['','全部部门'],...distinct('className',user=>user.accountType==='teacher').map(value=>[value,value])]));
const group=computed(()=>groupKey.value?{kind:groupKey.value.split(':',1)[0],value:groupKey.value.slice(groupKey.value.indexOf(':')+1)}:null);
function inGroup(user,selected){return student(user)&&(selected.kind==='class'?user.officialClassName:user.classCohort)===selected.value;}
function resetFilters(){groupKey.value='';department.value='';}
const filtered=computed(()=>{
  const match=keyword.value.trim().toLowerCase();
  return users.value.filter(user=>(!type.value||user.accountType===type.value)
    &&(!enabled.value||String(user.enabled)===enabled.value)
    &&(!match||[user.name,user.account,user.studentNumber].some(value=>value?.toLowerCase().includes(match)))
    &&(!group.value||inGroup(user,group.value))
    &&(!department.value||user.className===department.value));
});
const visibleRows=computed(()=>filtered.value.slice((page.value-1)*15,page.value*15));
watch([type,enabled,keyword,groupKey,department],()=>page.value=1);

function typeLabel(user){
  if(user.role==='admin')return '管理员';
  if(user.accountType==='teacher')return user.className||'未登记部门';
  return [user.officialClassName||'未登记官方班级',user.classCohort?user.classCohort+'级':'年级未识别'].join(' · ');
}
async function readAll(studentOnly=false){
  const result=[];let number=1,total=0;
  do{
    const {data}=await api.request('/users?pageSize=100&page='+number+(studentOnly?'&accountType=student':''));
    if(!alive)return [];
    total=data.total;result.push(...data.items);number++;
    if(!data.items.length&&result.length<total)throw new Error('账号列表已变化，请重试。');
  }while(result.length<total);
  if(result.length!==total)throw new Error('账号列表已变化，请重试。');
  return result;
}
async function load(){
  const current=++revision;loading.value=true;error.value='';
  try{
    const result=await readAll();
    if(current!==revision)return;
    users.value=result;page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/15)));
  }catch(e){if(current===revision)error.value=e.message;}
  finally{if(current===revision)loading.value=false;}
}
function openProfile(user){
  const teacher=user.accountType==='teacher';
  profile.value={id:user.id,teacher,name:user.name,className:(teacher?user.className:user.officialClassName)||'',
    phone:user.phone||'',email:user.email||''};
}
async function action(run){
  if(blocked.value)return;
  busy.value=true;error.value='';
  try{await run();}
  catch(e){if(alive){error.value=e.message||'账号操作失败';uni.showToast({title:error.value,icon:'none'});}}
  finally{if(alive){busy.value=false;emit('pending',api.pending());}}
}
function saveProfile(){
  return action(async()=>{
    const {id,name,className,phone,email}=profile.value;
    if(!name.trim())throw new Error('请填写姓名。');
    await api.mutate('/users/'+encodeURIComponent(id)+'/profile',{name:name.trim(),className:className.trim(),phone:phone.trim(),email:email.trim()},{method:'PATCH'});
    if(!alive)return;
    profile.value=null;await load();
  });
}
function manageGroup(operation){
  return action(async()=>{
    const classGroup=group.value;
    const ids=(await readAll(true)).filter(user=>inGroup(user,classGroup)).map(user=>user.id);
    if(!alive||!ids.length)return;
    const data={ids,classGroup};
    if(operation==='delete'){
      const preview=await api.request('/users/delete-preview',{method:'POST',data});
      if(preview.data.blockers.length)throw new Error('组内有 '+preview.data.blockers.length+' 个账号存在未归还器材。');
    }
    const label=classGroup.value+(classGroup.kind==='year'?'级':'');
    const verb=operation==='delete'?'删除':'停用';
    const confirm=await uni.showModal({title:'批量'+verb+'账号',content:'范围为 '+label+'，共 '+ids.length+' 人。',confirmText:verb});
    if(!alive||!confirm.confirm)return;
    await api.mutate(operation==='delete'?'/users/delete':'/users/disable-cohort',data);
    if(alive)await load();
  });
}

watch(()=>props.refreshToken,load);
onMounted(load);
onBeforeUnmount(()=>{alive=false;revision++;});
</script>

<style scoped>
.native-roster,.roster-filters,.roster-editor{display:grid;gap:10px}
.roster-filters{grid-template-columns:repeat(2,minmax(0,1fr))}
.roster-person{display:grid;gap:8px;padding:9px 0;border-bottom:1px solid #e5ebf2}
.roster-person text{display:block}
.roster-editor{border-top:1px solid #dce6ef;padding-top:10px}
</style>
