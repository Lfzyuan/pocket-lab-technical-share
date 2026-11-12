<template>
  <!-- #ifdef H5 -->
  <select class="choice-select" :value="modelValue" @change="$emit('update:modelValue',$event.target.value)"><option v-if="placeholder" value="" disabled hidden>{{placeholder}}</option><option v-for="option in options" :key="String(option.value)" :value="option.value">{{option.label}}</option></select>
  <!-- #endif -->
  <!-- #ifndef H5 -->
  <picker :range="options" range-key="label" :value="Math.max(0,options.findIndex(option=>option.value===modelValue))" @change="$emit('update:modelValue',options[Number($event.detail.value)].value)"><view class="choice-select">{{options.find(option=>option.value===modelValue)?.label||placeholder||'请选择'}}</view></picker>
  <!-- #endif -->
</template>
<script setup>defineProps({modelValue:[String,Number],placeholder:String,options:{type:Array,default:()=>[]}});defineEmits(['update:modelValue']);</script>
<style scoped>.choice-select{width:100%;min-height:44px;border:1px solid #becdd9;border-radius:7px;background:#fff;color:#19354a;font-size:14px;padding:10px 12px;box-sizing:border-box;line-height:1.6}.choice-select:focus{outline:2px solid #4685af;outline-offset:1px}</style>
