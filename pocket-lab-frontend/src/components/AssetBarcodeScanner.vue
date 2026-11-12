<template>
  <view class="asset-scan">
    <template v-if="!hideControls">
      <button role="button" class="btn secondary" :disabled="disabled || scanning || cameraOpenState || manualOpen" :aria-disabled="disabled || scanning || cameraOpenState || manualOpen" @click="beginScan">{{scanning?'正在识别…':'扫描器材码'}}</button>
      <button role="button" class="text-btn" :disabled="disabled || scanning" @click="showManual">手动输入编号</button>
    </template>
    <!-- #ifdef H5 -->
    <Teleport to="body">
      <view v-if="cameraOpen" class="asset-camera-overlay" role="dialog" aria-modal="true" aria-labelledby="asset-camera-title">
        <view ref="preview" class="asset-camera-preview"></view>
        <view class="asset-camera-shade" aria-hidden="true"></view>
        <view class="asset-camera-header">
          <button ref="closeButton" role="button" tabindex="0" class="asset-camera-close" aria-label="返回表单" @click="closeCamera"><text class="asset-camera-back-icon" aria-hidden="true">‹</text><text>返回</text></button>
          <text id="asset-camera-title" class="asset-camera-title">{{title||'扫描器材码'}}</text>
        </view>
        <text v-if="context" class="asset-camera-context">{{context}}</text>
        <view v-if="continuous&&paused" class="asset-camera-result"><slot name="result"/></view>
        <view v-if="!continuous||!paused" class="asset-camera-target" aria-hidden="true"><view class="asset-camera-line"></view></view>
        <text v-if="!continuous||!paused" class="asset-camera-hint" role="status">{{cameraHint}}</text>
        <button role="button" tabindex="0" v-if="!continuous||!paused" class="asset-camera-manual" @click="showManual">手动输入编号</button>
      </view>
    </Teleport>
    <!-- #endif -->
    <!-- #ifdef H5 -->
    <Teleport to="body">
    <!-- #endif -->
      <view v-if="manualOpen" class="asset-manual-mask" role="dialog" aria-modal="true" aria-label="手动输入资产编号">
        <view class="asset-manual-form">
          <button role="button" tabindex="0" class="text-btn" @click="cancelManual">‹ 返回</button>
          <text class="block-title">{{title||'手动输入资产编号'}}</text><text v-if="context" class="muted">{{context}}</text>
          <view class="field-group"><text>实际资产编号</text><input ref="manualInput" v-model="manualValue" class="field" maxlength="100" placeholder="输入或用扫码枪扫描编号" @confirm="confirmManual"/></view>
          <text v-if="message" class="small asset-scan-message" role="status">{{message}}</text>
          <text v-if="manualError" class="error" role="alert">{{manualError}}</text>
          <button role="button" tabindex="0" class="btn full" :disabled="disabled" :aria-disabled="disabled" @click="confirmManual">{{confirmLabel||'确认编号'}}</button>
          <button role="button" tabindex="0" class="text-btn" :disabled="disabled || scanning" :aria-disabled="disabled || scanning" @click="retryCamera">重新打开摄像头</button>
          <text class="small">请核对当前器材与实际编号后确认。</text>
        </view>
      </view>
    <!-- #ifdef H5 -->
    </Teleport>
    <!-- #endif -->
    <slot v-if="continuous&&!cameraOpenState" name="result"/>
    <text v-if="message&&!manualOpen" class="small asset-scan-message" role="status">{{message}}</text>
  </view>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { normalizeAssetBarcode } from '../services/asset-barcode.mjs';
// #ifdef H5
import {
  BarcodeFormat, BinaryBitmap, BrowserMultiFormatReader, ChecksumException,
  DecodeHintType, FormatException, HybridBinarizer, NotFoundException, RGBLuminanceSource
} from '@zxing/library';
// #endif

const props = defineProps({ continuous: Boolean, paused: Boolean, disabled: Boolean, hideControls: Boolean, title: String, context: String, confirmLabel: String });
const emit = defineEmits(['scanned', 'cancelled']);
const manualOpen = ref(false), manualValue = ref(''), manualError = ref(''), manualInput = ref(null);
const cameraOpenState = ref(false);
let manualFocus, manualOverflow;
const scanning = ref(false);
const message = ref('');
let active = true;
let scanGeneration = 0;

function rejectedBarcodeMessage(value) {
  const text = typeof value === 'string' ? value.trim() : '';
  const printable = text.replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ').replace(/\s+/g, ' ').trim();
  if (!printable) return '已识别到空内容，请扫描包含器材编号的条形码或二维码。';
  const characters = Array.from(printable);
  const preview = characters.slice(0, 36).join('') + (characters.length > 36 ? '…' : '');
  return `已识别到“${preview}”，内容不能作为100字以内的有效器材编号，请检查长度或换行。`;
}

// #ifdef H5
const cameraOpen = ref(false);
const cameraHint = ref('');
const preview = ref(null);
const closeButton = ref(null);
let cameraSession = null;

function createReader(tryHarder = true) {
  const hints = new Map([
    [DecodeHintType.POSSIBLE_FORMATS, [
      BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
      BarcodeFormat.CODABAR, BarcodeFormat.ITF,
      BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
      BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417
    ]]
  ]);
  if (tryHarder) hints.set(DecodeHintType.TRY_HARDER, true);
  return new BrowserMultiFormatReader(hints, 500);
}

function currentSession(session) {
  return active && cameraSession === session && !props.disabled && !document.hidden;
}

function stopCamera(restoreFocus = true) {
  const session = cameraSession;
  cameraSession = null;
  cameraOpen.value = false;
  cameraOpenState.value = false;
  if (!session) return;
  clearTimeout(session.timer);
  session.stream?.getTracks().forEach(track => track.stop());
  session.reader.reset();
  session.canvas.width = 0;
  session.canvas.height = 0;
  if (session.video) {
    session.video.pause();
    session.video.srcObject = null;
    session.video.remove();
  }
  document.body.style.overflow = session.previousOverflow;
  if (restoreFocus && active) {
    nextTick(() => {
      if (!cameraSession && session.previousFocus?.isConnected) session.previousFocus.focus();
    });
  }
}

function closeCamera() {
  stopCamera();
  emit('cancelled');
}

function readVisibleFrame(session) {
  const video = session.video;
  const bounds = video.getBoundingClientRect();
  const scale = Math.max(bounds.width / video.videoWidth, bounds.height / video.videoHeight);
  if (!Number.isFinite(scale) || scale <= 0) return '';
  const width = Math.min(video.videoWidth, Math.max(1, Math.round(bounds.width / scale)));
  const height = Math.min(video.videoHeight, Math.max(1, Math.round(bounds.height / scale)));
  const canvas = session.canvas;
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const context = canvas.getContext('2d', { willReadFrequently: true });
  context.drawImage(video, (video.videoWidth - width) / 2, (video.videoHeight - height) / 2,
    width, height, 0, 0, width, height);
  const pixels = context.getImageData(0, 0, width, height).data;
  const luminances = new Uint8ClampedArray(width * height);
  for (let pixel = 0; pixel < luminances.length; pixel++) {
    const offset = pixel * 4;
    luminances[pixel] = (pixels[offset] + 2 * pixels[offset + 1] + pixels[offset + 2]) >> 2;
  }
  const bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(luminances, width, height)));
  return session.reader.decodeBitmap(bitmap).getText();
}

function readFrame(session) {
  if (!currentSession(session)) return;
  if (props.continuous && props.paused) {
    session.timer = setTimeout(() => readFrame(session), 200);
    return;
  }
  const video = session.video;
  if (video.readyState >= 2 && video.videoWidth > 0) {
    try {
      const value = readVisibleFrame(session);
      const code = normalizeAssetBarcode(value);
      if (code) {
        if (!props.continuous) {
          stopCamera();
          emit('scanned', code);
          return;
        }
        session.misses = 0;
        if (code !== session.lastCode) {
          session.lastCode = code;
          emit('scanned', code);
        }
      } else {
        cameraHint.value = rejectedBarcodeMessage(value);
      }
    } catch (error) {
      if (!(error instanceof NotFoundException || error instanceof ChecksumException || error instanceof FormatException)) {
        stopCamera();
        message.value = '识别已中断，请重新打开摄像头扫码。';
        if (props.continuous && !props.paused) showManual();
        return;
      }
      if (props.continuous && ++session.misses >= 4) session.lastCode = '';
    }
  }
  session.timer = setTimeout(() => readFrame(session), 200);
}

function cameraError(error) {
  if (error?.name === 'NotAllowedError' || error?.name === 'SecurityError') {
    return '请允许此网站使用摄像头，然后重新扫码。';
  }
  if (error?.name === 'NotFoundError') return '没有找到可用摄像头，请检查设备连接。';
  if (error?.name === 'NotReadableError') return '摄像头暂时无法使用，请关闭其他占用摄像头的应用后重试。';
  return '摄像头未能打开，请重试或手动输入编号。';
}

async function openCamera() {
  if (props.disabled || scanning.value || cameraSession) return;
  message.value = '';
  if (!globalThis.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    message.value = '请从安全网址打开页面后使用摄像头扫码。当前可手动输入编号。';
    showManual();
    return;
  }
  const session = {
    reader: createReader(false),
    stream: null,
    video: null,
    timer: null,
    misses: 0,
    lastCode: '',
    canvas: document.createElement('canvas'),
    previousOverflow: document.body.style.overflow,
    previousFocus: document.activeElement
  };
  cameraSession = session;
  cameraOpen.value = true;
  cameraOpenState.value = true;
  cameraHint.value = '正在打开摄像头…';
  document.body.style.overflow = 'hidden';
  try {
    await nextTick();
    if (!currentSession(session)) return;
    const mount = preview.value?.$el || preview.value;
    const button = closeButton.value?.$el || closeButton.value;
    button?.focus();
    const video = document.createElement('video');
    video.muted = true;
    video.autoplay = true;
    video.playsInline = true;
    video.setAttribute('playsinline', '');
    video.setAttribute('webkit-playsinline', '');
    video.setAttribute('aria-label', '实时摄像头画面');
    video.style.cssText = 'display:block;width:100%;height:100%;object-fit:cover';
    session.video = video;
    mount.appendChild(video);
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 1280 },
        height: { ideal: 720 }
      }
    });
    if (!currentSession(session)) {
      stream.getTracks().forEach(track => track.stop());
      return;
    }
    session.stream = stream;
    for (const track of stream.getVideoTracks()) {
      track.addEventListener('ended', () => {
        if (cameraSession === session) {
          stopCamera();
          message.value = '摄像头连接已结束，请重新扫码。';
          if (props.continuous && !props.paused) showManual();
        }
      }, { once: true });
    }
    video.srcObject = stream;
    await video.play();
    if (!currentSession(session)) return;
    cameraHint.value = props.continuous ? '将条形码或二维码放在画面中央。' : props.confirmLabel ? '将当前器材码放在画面中央，识别成功后将'+props.confirmLabel+'。' : '将条形码或二维码放在画面中央，识别后会自动填写编号。';
    readFrame(session);
  } catch (error) {
    if (cameraSession === session) {
      stopCamera();
      message.value = cameraError(error);
      showManual();
    }
  }
}

function pageHidden() {
  if (document.hidden && (cameraSession||manualOpen.value)) { stopCamera(false); closeManual(false); emit('cancelled'); }
}

function pageLeaving() {
  stopCamera(false);
  closeManual(false);
  emit('cancelled');
}

function cameraKeydown(event) {
  if (manualOpen.value) {
    if (event.key === 'Escape') { event.preventDefault(); cancelManual(); }
    if (event.key === 'Tab') {
      const items=[...document.querySelectorAll('.asset-manual-mask [role=button],.asset-manual-mask input')].filter(item=>!item.disabled&&!item.hasAttribute('disabled')&&item.getAttribute('aria-disabled')!=='true'&&item.getClientRects().length);
      const first=items[0],last=items[items.length-1];
      if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
      else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
    }
    return;
  }
  if (!cameraSession) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    closeCamera();
  } else if (event.key === 'Tab') {
    const buttons = [...document.querySelectorAll('.asset-camera-overlay [role=button],.asset-camera-result input')].filter(item=>!item.disabled&&!item.hasAttribute('disabled')&&item.getAttribute('aria-disabled')!=='true'&&item.getClientRects().length);
    const first=buttons[0], last=buttons[buttons.length-1];
    if (event.shiftKey && document.activeElement===first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement===last) { event.preventDefault(); first?.focus(); }
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', pageHidden);
  document.addEventListener('keydown', cameraKeydown);
  window.addEventListener('pagehide', pageLeaving);
});
// #endif

// #ifndef H5
function readBarcode() {
  return new Promise((resolve, reject) => uni.scanCode({
    onlyFromCamera: true,
    scanType: ['barCode', 'qrCode'],
    success: result => resolve(result.result),
    fail: reject
  }));
}
// #endif

watch(() => props.disabled, disabled => {
  if (!disabled) return;
  const wasOpen=manualOpen.value||cameraOpenState.value||scanning.value;
  scanGeneration++;
  closeManual(false);
  // #ifdef H5
  stopCamera(false);
  // #endif
  if(wasOpen)emit('cancelled');
});

onBeforeUnmount(() => {
  active = false;
  scanGeneration++;
  closeManual(false);
  // #ifdef H5
  stopCamera(false);
  document.removeEventListener('visibilitychange', pageHidden);
  document.removeEventListener('keydown', cameraKeydown);
  window.removeEventListener('pagehide', pageLeaving);
  // #endif
});

function closeManual(restoreFocus=true) {
  if (!manualOpen.value) return;
  manualOpen.value=false;
  // #ifdef H5
  document.body.style.overflow=manualOverflow??'';
  if (restoreFocus && active && manualFocus?.isConnected) nextTick(()=>manualFocus.focus());
  // #endif
}
function cancelManual() { closeManual(); emit('cancelled'); }
async function showManual() {
  if (props.disabled) return;
  // #ifdef H5
  manualFocus=cameraSession?.previousFocus||document.activeElement;
  stopCamera(false);
  manualOverflow=document.body.style.overflow;
  document.body.style.overflow='hidden';
  // #endif
  manualValue.value=''; manualError.value=''; manualOpen.value=true;
  await nextTick();
  // #ifdef H5
  const target=manualInput.value?.$el||manualInput.value;
  (target?.querySelector?.('input')||target)?.focus?.();
  // #endif
}
function confirmManual() {
  if (props.disabled) return;
  const code=normalizeAssetBarcode(manualValue.value);
  if (!code) { manualError.value='请填写100字以内、不含换行或控制字符的器材编号。'; return; }
  closeManual(); emit('scanned',code);
}
function retryCamera() {
  if (props.disabled || scanning.value) return;
  closeManual(false);
  // #ifdef H5
  if (manualFocus?.isConnected) manualFocus.focus();
  // #endif
  return beginScan();
}
function beginScan() {
  // #ifdef H5
  return openCamera();
  // #endif
  // #ifndef H5
  return scanOnce();
  // #endif
}
defineExpose({start:beginScan,cancel(){
  scanGeneration++;
  closeManual(false);
  // #ifdef H5
  stopCamera(false);
  // #endif
}});

// #ifndef H5
async function scanOnce() {
  if (props.disabled || scanning.value) return;
  const generation = ++scanGeneration;
  scanning.value = true;
  message.value = '';
  try {
    const result = await readBarcode();
    if (!active || props.disabled || generation !== scanGeneration) return;
    const code = normalizeAssetBarcode(result);
    if (code) emit('scanned', code);
    else message.value = rejectedBarcodeMessage(result);
  } catch (error) {
    if (active && generation === scanGeneration && !/cancel/i.test(error?.errMsg || '')) {
      message.value = '未能识别器材码，请重试或手动输入资产编号。';
      showManual();
    } else if (active && generation === scanGeneration) emit('cancelled');
  } finally {
    if (active) scanning.value = false;
  }
}
// #endif
</script>

<style scoped>
@import '../pages/index/live.css';
@import '../pages/index/restore-v1.css';
@import '../pages/index/college-refinement.css';
.asset-scan {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.asset-scan .btn { margin: 0; }
.asset-scan-message { color: #98440e; }
.asset-camera-overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  width: 100%;
  height: 100%;
  height: 100dvh;
  overflow: hidden;
  touch-action: none;
  overscroll-behavior: none;
  color: #fff;
  background: #000;
}
.asset-camera-preview {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #000;
}
.asset-camera-shade {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(180deg, rgba(0, 0, 0, .62), transparent 28%, transparent 65%, rgba(0, 0, 0, .72));
}
.asset-camera-header {
  position: absolute;
  z-index: 2;
  top: 0;
  left: 0;
  right: 0;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: calc(12px + env(safe-area-inset-top, 0px)) calc(16px + env(safe-area-inset-right, 0px)) 12px calc(16px + env(safe-area-inset-left, 0px));
  box-sizing: border-box;
}
.asset-camera-title {
  font-size: 17px;
  font-weight: 600;
  text-shadow: 0 1px 4px #000;
}
.asset-camera-close {
  display: inline-flex;
  justify-self: start;
  align-items: center;
  gap: 5px;
  min-height: 44px;
  margin: 0;
  padding: 4px 8px 4px 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: #fff;
  font-size: 15px;
  line-height: 24px;
}
.asset-camera-close::after { border: 0; }
.asset-camera-back-icon { font-size: 32px; line-height: 24px; }
.asset-camera-target {
  position: absolute;
  z-index: 1;
  top: 50%;
  left: 50%;
  width: min(82%, 640px);
  height: clamp(56px, 22vh, 180px);
  height: clamp(56px, 22dvh, 180px);
  transform: translate(-50%, -50%);
  border: 2px solid #67e8bb;
  box-sizing: border-box;
  pointer-events: none;
}
.asset-camera-line {
  position: absolute;
  top: 50%;
  left: 4px;
  right: 4px;
  height: 2px;
  background: #67e8bb;
  box-shadow: 0 0 12px #67e8bb;
  animation: asset-camera-sweep 2s ease-in-out infinite alternate;
}
.asset-camera-hint {
  position: absolute;
  z-index: 2;
  right: 0;
  bottom: calc(28px + env(safe-area-inset-bottom, 0px));
  left: 0;
  max-width: 680px;
  margin: 0 auto;
  padding: 0 calc(24px + env(safe-area-inset-right, 0px)) 0 calc(24px + env(safe-area-inset-left, 0px));
  box-sizing: border-box;
  font-size: 15px;
  line-height: 1.6;
  text-align: center;
  text-shadow: 0 1px 4px #000;
}
.asset-camera-result{position:absolute;left:50%;bottom:calc(16px + env(safe-area-inset-bottom,0px));transform:translateX(-50%);z-index:4;width:min(620px,calc(100% - 32px));max-height:calc(100% - 110px);overflow:auto;overscroll-behavior:contain;color:#203553}
@keyframes asset-camera-sweep {
  from { top: 10%; }
  to { top: 90%; }
}
@media (max-height: 420px) {
  .asset-camera-header {
    padding-top: calc(6px + env(safe-area-inset-top, 0px));
    padding-bottom: 6px;
  }
  .asset-camera-title { font-size: 15px; }
  .asset-camera-hint {
    bottom: calc(8px + env(safe-area-inset-bottom, 0px));
    font-size: 13px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .asset-camera-line { animation: none; }
}
.asset-camera-context{position:absolute;top:calc(80px + env(safe-area-inset-top,0px));left:calc(20px + env(safe-area-inset-left,0px));right:calc(20px + env(safe-area-inset-right,0px));text-align:center;color:white;font-size:14px;z-index:2}
.asset-camera-overlay .asset-camera-hint{bottom:calc(80px + env(safe-area-inset-bottom,0px))}
.asset-camera-manual{position:absolute;bottom:calc(20px + env(safe-area-inset-bottom,0px));left:50%;transform:translateX(-50%);background:#ffffff20;color:white;border:1px solid #ffffff70;border-radius:8px;padding:10px 18px;min-height:44px;z-index:3;font-size:16px}
.asset-manual-mask{position:fixed;inset:0;z-index:10000;background:#f4f6f8;overflow:auto;overscroll-behavior:none;padding:calc(24px + env(safe-area-inset-top,0px)) calc(20px + env(safe-area-inset-right,0px)) calc(24px + env(safe-area-inset-bottom,0px)) calc(20px + env(safe-area-inset-left,0px));box-sizing:border-box}
.asset-manual-form{max-width:560px;margin:0 auto;color:#203a52}.asset-manual-form>.block-title{display:block;margin:24px 0 14px}.asset-manual-form .field{font-size:16px}.asset-manual-form>.small{display:block;margin-top:14px}
@media(max-height:400px){.asset-camera-target{height:48px;max-height:48px}.asset-camera-context{top:calc(58px + env(safe-area-inset-top,0px));font-size:12px}.asset-camera-overlay .asset-camera-hint{bottom:calc(64px + env(safe-area-inset-bottom,0px));font-size:12px;line-height:1.4}.asset-camera-manual{bottom:calc(10px + env(safe-area-inset-bottom,0px));padding:7px 16px}}
</style>
