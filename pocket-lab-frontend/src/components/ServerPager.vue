<template><view class="pager"><text>共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</text><view class="pager-actions"><button class="btn secondary small" :disabled="disabled||page<=1" @click="$emit('change',page-1)">上一页</button><button class="btn secondary small" :disabled="disabled||page>=pages" @click="$emit('change',page+1)">下一页</button></view></view></template>
<script setup>
import {computed} from 'vue';
const props=defineProps({total:{type:Number,default:0},page:{type:Number,default:1},pageSize:{type:Number,default:10},disabled:Boolean});
defineEmits(['change']);const pages=computed(()=>Math.max(1,Math.ceil(props.total/props.pageSize)));
</script>
<style scoped>
.pager{display:flex;align-items:center;justify-content:space-between;gap:15px;flex-wrap:wrap;font-size:13px;color:#506a7d;margin:22px 0}.pager-actions{display:flex;gap:10px}.btn{display:inline-flex;align-items:center;justify-content:center;padding:7px 13px;min-height:36px;line-height:1.5;border:1px solid #b6c9d8;border-radius:7px;background:#fff;color:#245578;font-size:13px;margin:0}.btn::after{border:0}.btn[disabled]{background:#e7edf1;border-color:#d9e2e9;color:#758897}@media(max-width:760px){.pager{justify-content:center}.pager-actions{justify-content:center}}
</style>
