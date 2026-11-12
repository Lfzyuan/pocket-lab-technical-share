<template>
<view class="regulation-reader">
 <view class="reader-topbar"><view class="reader-topbar-inner"><button role="button" class="reader-back" @click="back">‹ 返回</button><text class="reader-brand">口袋实验室</text><button role="button" class="reader-home" @click="home">返回首页</button></view></view>
 <RegulationDocument v-if="kind!==undefined" :kind="kind">
  <view class="reader-footer"><text>口袋实验室</text><button role="button" class="reader-link" @click="home">返回首页</button></view>
 </RegulationDocument>
</view>
</template>
<script setup>
import {ref, watch} from 'vue';
// #ifdef H5
import {useRoute} from 'vue-router';
// #endif
import {onLoad, onUnload} from '@dcloudio/uni-app';
import RegulationDocument from '../../components/RegulationDocument.vue';
import {regulationKind, REGULATION_TITLES} from '../../services/regulation-content.mjs';

const kind = ref(undefined);
let stopRouteWatch;

function home(){uni.reLaunch({url:'/pages/index/index?section=home&guest=1'});}
function back(){if(getCurrentPages().length>1)uni.navigateBack({delta:1,fail:()=>uni.reLaunch({url:'/pages/index/index?section=rules&guest=1'})});else uni.reLaunch({url:'/pages/index/index?section=rules&guest=1'});}
function selectKind(value) {
  const next = regulationKind(value);
  if (next === kind.value) return;
  kind.value = next;
  if (next) uni.setNavigationBarTitle({title: REGULATION_TITLES[next]});
}
// H5 can reuse the page instance when only its query changes.
// #ifdef H5
const route = useRoute();
stopRouteWatch = watch(() => [route.path, route.query.kind], ([path, value]) => {
  if (path === '/pages/regulation/read') selectKind(value);
});
// #endif
onLoad(options => selectKind(options?.kind));
onUnload(() => stopRouteWatch?.());
</script>
<style scoped>
.regulation-reader{min-height:100vh;min-height:100dvh;background:#f6f8fc;color:#203553;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;padding-bottom:env(safe-area-inset-bottom)}.reader-topbar{position:sticky;top:0;z-index:20;background:rgba(255,255,255,.97);border-bottom:1px solid #e1e7f0;padding-top:env(safe-area-inset-top)}.reader-topbar-inner{max-width:1024px;min-height:64px;margin:0 auto;padding:0 max(24px,env(safe-area-inset-right)) 0 max(24px,env(safe-area-inset-left));display:flex;align-items:center;justify-content:space-between;gap:16px;box-sizing:border-box}.reader-topbar button,.reader-link{background:transparent;border:0;color:#345ba2;font-size:14px;padding:10px 0;margin:0;line-height:1.5;min-height:44px}.reader-topbar button::after,.reader-link::after{border:0}.reader-brand{font-size:14px;color:#6b7e99}.reader-footer{border-top:1px solid #e3e8f0;margin-top:48px;padding-top:20px;display:flex;justify-content:space-between;align-items:center;gap:16px;color:#798ba5;font-size:12px}@media(max-width:600px){.reader-topbar-inner{min-height:56px;padding-left:max(18px,env(safe-area-inset-left));padding-right:max(18px,env(safe-area-inset-right));gap:12px}.reader-brand{font-size:12px}.reader-footer{align-items:flex-start;flex-direction:column;gap:8px}}
</style>
