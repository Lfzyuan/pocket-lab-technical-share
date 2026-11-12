<template>
<view :class="['brand','university-brand',{'university-brand--compact':compact}]" :aria-label="APP_NAME">
  <view class="university-brand__caption"><text class="university-brand__title">{{APP_NAME}}</text></view>
</view>
</template>
<script setup>
import {APP_NAME} from '../services/brand.mjs';
defineProps({compact:Boolean});
</script>
<style scoped>
.university-brand{display:flex;flex-direction:column;align-items:center;gap:13px;white-space:normal;color:#111;text-align:center}
.university-brand__caption{display:flex;flex-direction:column;align-items:center;gap:6px}
.university-brand__title{display:block;font-size:21px;font-weight:750;line-height:1.5;color:#111;letter-spacing:0;white-space:nowrap}
.university-brand--compact .university-brand__title{font-size:17px}
.university-brand__college{display:block;font-size:13px;font-weight:600;letter-spacing:1px;line-height:1.7}.university-brand__lab{display:block;font-size:19px;font-weight:750;line-height:1.6}
</style>
