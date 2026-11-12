<template>
  <view v-if="compact" class="compact-reader">
    <text v-if="loading" role="status">正在读取平台使用须知…</text>
    <text v-else-if="error" role="alert">暂时无法读取平台使用须知。</text>
    <text v-else-if="!body">平台使用须知尚未发布。</text>
    <text v-else class="compact-body" user-select>{{body}}</text>
  </view>
  <view v-else :class="['reader-main', {'reader-workspace': workspace}]">
    <text class="reader-kicker">管理办法与使用须知</text>
    <text class="regulation-reader-title" role="heading" aria-level="1">{{title}}</text>
    <text v-if="content?.updatedAt&&body" class="reader-updated">更新于 {{regulationUpdatedAt(content.updatedAt)}}</text>
    <view v-if="loading" class="reader-status" role="status">正在读取正文…</view>
    <view v-else-if="error" class="reader-status reader-error" role="alert">
      <text>{{error}}</text><button v-if="kind" role="button" class="reader-secondary" @click="load">重新读取</button>
    </view>
    <view v-else-if="!body" class="reader-status reader-empty"><text>正文尚未发布，请等待管理员维护。</text><text>内容发布后可在此完整阅读。</text></view>
    <view v-else class="regulation-text" aria-label="文档正文">
      <text v-for="(paragraph,index) in paragraphs" :key="index" class="regulation-paragraph" user-select>{{paragraph}}</text>
    </view>
    <slot />
  </view>
</template>

<script setup>
import {computed, onBeforeUnmount, ref, watch} from 'vue';
import {guestApi} from '../services/guest-api.js';
import {regulationKind, REGULATION_TITLES, regulationContentPath, readableRegulationBody, regulationParagraphs, regulationUpdatedAt} from '../services/regulation-content.mjs';

const props = defineProps({kind: String, workspace: Boolean, compact: Boolean});
const kind = computed(() => regulationKind(props.kind));
const content = ref(null);
const loading = ref(false);
const error = ref('');
const title = computed(() => content.value?.title || REGULATION_TITLES[kind.value] || '文档阅读');
const body = computed(() => readableRegulationBody(content.value));
const paragraphs = computed(() => regulationParagraphs(body.value));
let revision = 0;

async function load() {
  const requestedKind = kind.value;
  if (!requestedKind) return;
  const stamp = ++revision;
  loading.value = true;
  error.value = '';
  try {
    const result = await guestApi.request(regulationContentPath(requestedKind, {publicRead: true}));
    if (stamp === revision) content.value = result;
  } catch (e) {
    if (stamp === revision) error.value = e.message || '正文暂时无法读取，请稍后重试';
  } finally {
    if (stamp === revision) loading.value = false;
  }
}

watch(kind, value => {
  revision++;
  content.value = null;
  loading.value = false;
  error.value = value ? '' : '文档不存在，请返回选择管理办法或使用须知。';
  if (value) load();
}, {immediate: true, flush: 'sync'});
onBeforeUnmount(() => { revision++; });
</script>

<style scoped>
.compact-reader{line-height:1.85;color:inherit}.compact-body{display:block;white-space:pre-wrap;overflow-wrap:anywhere}
.reader-main{max-width:900px;margin:0 auto;padding:54px max(44px,env(safe-area-inset-right)) 24px max(44px,env(safe-area-inset-left));background:#fff;box-sizing:border-box;min-height:calc(100vh - 65px)}.reader-kicker{display:block;color:#7183a0;font-size:13px;letter-spacing:1px;margin-bottom:18px}.regulation-reader-title{display:block;font-size:30px;font-weight:700;line-height:1.5;overflow-wrap:anywhere;word-break:break-word}.reader-updated{display:block;color:#7a899d;font-size:13px;margin-top:16px}.regulation-text{margin-top:38px;min-width:0}.regulation-paragraph{display:block;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font-size:17px;line-height:2.1;margin-bottom:22px;user-select:text}.reader-status{margin:32px 0;padding:24px;background:#f6f8fc;border:1px solid #e4e9f1;border-radius:10px;line-height:1.9;display:flex;flex-direction:column;align-items:flex-start;gap:16px;font-size:16px}.reader-error{color:#a23a36;line-height:1.8}.reader-secondary{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;min-height:44px;padding:11px 18px;margin:0;background:#fff;border:1px solid #ced8eb;border-radius:7px;color:#345ba2;line-height:1.5;font-size:14px;text-decoration:none}.reader-secondary::after{border:0}
.reader-main.reader-workspace{width:100%;max-width:none;min-height:0;margin:0;padding:32px 40px 20px;color:#203553}
@media(max-width:600px){.reader-main{padding:32px max(22px,env(safe-area-inset-right)) 24px max(22px,env(safe-area-inset-left))}.regulation-reader-title{font-size:clamp(20px,5.5vw,24px);line-height:1.6;text-wrap:balance}.reader-kicker{font-size:12px;margin-bottom:14px}.regulation-text{margin-top:28px}.regulation-paragraph{font-size:17px;line-height:2;margin-bottom:20px}.reader-status{padding:18px}.reader-main.reader-workspace{padding:22px 18px 12px}}
</style>
