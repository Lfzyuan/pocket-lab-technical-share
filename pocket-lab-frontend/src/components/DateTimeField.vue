<template>
  <!-- #ifdef H5 -->
  <component :is="nativeInput" class="date-time-field" :type="mode" :value="modelValue" @input="$emit('update:modelValue',$event.target.value)"/>
  <!-- #endif -->
  <!-- #ifndef H5 -->
  <picker :mode="mode" :value="modelValue" @change="$emit('update:modelValue',$event.detail.value)"><view class="date-time-field">{{modelValue||'请选择'}}</view></picker>
  <!-- #endif -->
</template>
<script setup>const nativeInput='input';defineProps({modelValue:String,mode:{type:String,default:'date'}});defineEmits(['update:modelValue']);</script>
<style scoped>.date-time-field{width:100%;height:44px;min-height:44px;border:1px solid #becdd9;border-radius:7px;background:#fff;color:#19354a;font-size:14px;padding:10px 12px;box-sizing:border-box;font-family:inherit;line-height:1.6}.date-time-field:focus{outline:2px solid #4685af;outline-offset:1px}</style>
