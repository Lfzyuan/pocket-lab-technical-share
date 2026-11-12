<template>
 <view class="borrow-limit-control">
  <button v-if="userRecord&&admin" role="button" tabindex="0" class="btn secondary small" :disabled="unavailable" :aria-disabled="unavailable" @click="open(userRecord)">未归还上限 {{userRecord.borrowLimit??3}} 件</button>
  <!-- #ifdef H5 -->
  <Teleport to="body">
  <!-- #endif -->
  <view v-if="editing" class="borrow-limit-mask" role="dialog" aria-modal="true" aria-label="调整未归还上限" @click.self="close">
   <view class="borrow-limit-form"><view class="section-heading"><text class="block-title">调整未归还上限</text><button ref="closeButton" role="button" tabindex="0" class="text-btn" :disabled="busy" :aria-disabled="busy" @click="close">取消</button></view><text class="muted">{{editing.name}} · {{editing.account}}</text><text class="small">默认最多未归还3件。此项调整须填写原因并留存操作记录。</text>
    <view class="field-group"><text>未归还上限（1—100件）</text><input v-model="limit" class="field" type="number" inputmode="numeric" :disabled="busy" maxlength="3" aria-label="未归还上限"/></view>
    <view class="field-group"><text>调整原因 *</text><textarea v-model="reason" class="field textarea" maxlength="500" :disabled="busy" placeholder="填写此次特例调整的原因" aria-label="调整原因"/></view>
    <text v-if="error" class="error" role="alert">{{error}}</text><button role="button" tabindex="0" class="btn full" :disabled="unavailable" :aria-disabled="unavailable" @click="save">{{busy?'正在保存…':'保存上限'}}</button>
   </view>
  </view>
  <!-- #ifdef H5 -->
  </Teleport>
  <!-- #endif -->
 </view>
</template>
<script setup>
import {computed,nextTick,onMounted,onUnmounted,ref} from 'vue';
import {api} from '../services/api.js';
import {createBorrowLimit} from '../services/borrow-limit.mjs';
const props=defineProps({userRecord:Object,verified:Boolean,blocked:Boolean,listenEvents:Boolean});const emit=defineEmits(['updated','error','pending']);
const service=createBorrowLimit(api),editing=ref(null),limit=ref('3'),reason=ref(''),error=ref(''),busy=ref(false),closeButton=ref(null);
const admin=computed(()=>api.getSession()?.user?.role==='admin'),unavailable=computed(()=>busy.value||!props.verified||props.blocked||!!api.pending());
let alive=true,previousFocus,previousOverflow;
async function open(user){if(!alive||api.getSession()?.user?.role!=='admin'||unavailable.value||!user?.id)return;editing.value={id:user.id,name:user.name,account:user.account,borrowLimit:user.borrowLimit??3};limit.value=String(editing.value.borrowLimit);reason.value='';error.value='';
 // #ifdef H5
 previousFocus=document.activeElement;previousOverflow=document.body.style.overflow;document.body.style.overflow='hidden';
 // #endif
 await nextTick();
 // #ifdef H5
 if(alive&&editing.value)(closeButton.value?.$el||closeButton.value)?.focus?.();
 // #endif
}
function close(force=false){if(!editing.value||busy.value&&force!==true)return;editing.value=null;
 // #ifdef H5
 document.body.style.overflow=previousOverflow??'';if(alive&&previousFocus?.isConnected)nextTick(()=>previousFocus?.isConnected&&previousFocus.focus());
 // #endif
}
async function save(){if(unavailable.value||!editing.value)return;busy.value=true;error.value='';const target=editing.value;try{const user=await service.save(target,limit.value,reason.value);emit('pending',api.pending());if(!alive)return;busy.value=false;close();emit('updated',user);
 // #ifdef H5
 window.dispatchEvent(new CustomEvent('pocketlab:borrow-limit-updated',{detail:{id:target.id}}));
 // #endif
 }catch(e){emit('pending',api.pending());if(alive){error.value=e.message||'未能保存，请重试';emit('error',error.value);}}finally{busy.value=false;}}
 // #ifdef H5
function requestOpen(event){open(event.detail?.userRecord);}
function keydown(event){if(!editing.value)return;if(event.key==='Escape'){event.preventDefault();close();return;}if(event.key==='Tab'){const buttons=[...document.querySelectorAll('.borrow-limit-mask [role=button],.borrow-limit-mask input,.borrow-limit-mask textarea')].filter(item=>!item.disabled&&item.getAttribute('aria-disabled')!=='true'&&item.getAttribute('disabled')!=='true'&&item.getAttribute('disabled')!==''&&item.getClientRects().length);const first=buttons[0],last=buttons[buttons.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}}
 // #endif
onMounted(()=>{
 // #ifdef H5
 if(props.listenEvents)window.addEventListener('pocketlab:borrow-limit',requestOpen);document.addEventListener('keydown',keydown);
 // #endif
});
onUnmounted(()=>{alive=false;close(true);
 // #ifdef H5
 window.removeEventListener('pocketlab:borrow-limit',requestOpen);document.removeEventListener('keydown',keydown);
 // #endif
});
defineExpose({open});
</script>
<style scoped>
@import '../pages/index/live.css';
@import '../pages/index/restore-v1.css';
@import '../pages/index/college-refinement.css';
.borrow-limit-mask{position:fixed;inset:0;z-index:11000;background:#142b496b;display:flex;align-items:center;justify-content:center;overflow:auto;overscroll-behavior:none;padding:24px;box-sizing:border-box}.borrow-limit-form{width:500px;max-width:100%;max-height:100%;overflow:auto;background:#fff;border:1px solid #d5e1ee;border-radius:12px;padding:24px;color:#172d40}.borrow-limit-form>.small{display:block;margin-top:8px}.borrow-limit-form>.error{display:block}.borrow-limit-form .btn:not([disabled]){color:#fff!important}@media(max-width:760px){.borrow-limit-mask{padding:calc(16px + env(safe-area-inset-top,0px)) calc(16px + env(safe-area-inset-right,0px)) calc(16px + env(safe-area-inset-bottom,0px)) calc(16px + env(safe-area-inset-left,0px))}.borrow-limit-form{padding:18px}.borrow-limit-form input,.borrow-limit-form textarea{font-size:16px!important}}
</style>
