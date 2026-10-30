<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChatDotRound, Close, ArrowRight } from '@element-plus/icons-vue'
import { assistantSettings, loadAssistantSettings, updateAssistantSettings } from '../assistant-state'
import { assistantPets } from '../pets'
const opened = ref(false), launcher = ref<HTMLButtonElement>(), closeButton = ref<HTMLButtonElement>()
const pet = computed(() => assistantPets.find(p => p.id === assistantSettings.value?.petId) ?? assistantPets[0])
const petMode = computed(() => assistantSettings.value?.displayMode !== 'CHAT')
const position = ref<{ x: number; y: number } | null>(null)
const dragging = ref(false), panelStyle = ref<Record<string, string>>({})
let gesture: { id: number; x: number; y: number; left: number; top: number } | null = null
let suppressClick = false
const launcherStyle = computed(() => position.value ? { left: `${position.value.x}px`, top: `${position.value.y}px`, right: 'auto', bottom: 'auto' } : {})
function clampPosition(x: number, y: number) {
  const box = launcher.value?.getBoundingClientRect()
  return { x: Math.max(8, Math.min(x, window.innerWidth - (box?.width || 100) - 8)), y: Math.max(8, Math.min(y, window.innerHeight - (box?.height || 100) - 8)) }
}
function placePanel() {
  const box = launcher.value?.getBoundingClientRect()
  if (!box) return
  const below = box.top < window.innerHeight / 2
  panelStyle.value = { left: `${Math.max(8, Math.min(box.right - 370, window.innerWidth - Math.min(370, window.innerWidth - 32) - 8))}px`, ...(below ? { top: `${box.bottom + 12}px`, bottom: 'auto', maxHeight: `${Math.max(80, window.innerHeight - box.bottom - 24)}px` } : { bottom: `${window.innerHeight - box.top + 12}px`, top: 'auto', maxHeight: `${Math.max(80, box.top - 24)}px` }) }
}
function startDrag(event: PointerEvent) {
  if (event.button !== 0 || gesture) return
  const box = launcher.value!.getBoundingClientRect()
  suppressClick = false
  gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, left: box.left, top: box.top }
  launcher.value?.setPointerCapture?.(event.pointerId)
}
function moveDrag(event: PointerEvent) {
  if (!gesture || event.pointerId !== gesture.id) return
  const dx = event.clientX - gesture.x, dy = event.clientY - gesture.y
  if (!dragging.value && Math.hypot(dx, dy) < 6) return
  dragging.value = true; suppressClick = true; opened.value = false
  position.value = clampPosition(gesture.left + dx, gesture.top + dy)
}
function endDrag(event: PointerEvent) {
  if (!gesture || event.pointerId !== gesture.id) return
  gesture = null; dragging.value = false
}
function activate(event: MouseEvent) {
  if (suppressClick && event.detail !== 0) { suppressClick = false; return }
  suppressClick = false
  void (opened.value ? close() : open())
}
function resize() {
  if (position.value) position.value = clampPosition(position.value.x, position.value.y)
  if (opened.value) placePanel()
}
async function open() { opened.value = true; await nextTick(); placePanel(); closeButton.value?.focus() }
async function close() { opened.value = false; await nextTick(); launcher.value?.focus() }
watch(() => [assistantSettings.value?.enabled, assistantSettings.value?.displayMode], () => { opened.value = false; void nextTick(resize) })
onMounted(() => { void loadAssistantSettings(); window.addEventListener('pointermove', moveDrag); window.addEventListener('pointerup', endDrag); window.addEventListener('pointercancel', endDrag); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { updateAssistantSettings(null); window.removeEventListener('pointermove', moveDrag); window.removeEventListener('pointerup', endDrag); window.removeEventListener('pointercancel', endDrag); window.removeEventListener('resize', resize) })
</script>
<template>
  <aside v-if="assistantSettings?.enabled" :style="launcherStyle" class="sv-assistant" :class="{ dragging }" aria-label="AI 助手" @keydown.esc.stop="close">
    <section v-if="opened" id="sv-assistant-panel" :style="panelStyle" class="assistant-panel" role="dialog" aria-label="SingVerse AI 助手">
      <header><span class="assistant-panel-icon"><ChatDotRound /></span><div><strong>SingVerse AI 助手</strong><small>你的音乐空间，新的陪伴</small></div><button ref="closeButton" class="assistant-icon-button" aria-label="收起 AI 助手" @click="close"><Close /></button></header>
      <div class="assistant-welcome"><component :is="pet.component" v-if="petMode" still /><span v-else class="assistant-chat-emblem"><ChatDotRound /></span><h2>你好，很高兴见到你</h2><p>这里将成为你的音乐小助手。</p><span class="assistant-planning">能力规划中</span><p class="assistant-boundary">对话与工具能力尚未开放，当前不会调用模型或修改曲库。</p></div>
      <footer><RouterLink to="/system/ai/features/assistant" @click="close">助手设置 <ArrowRight /></RouterLink><span>SingVerse · 相聚，开唱</span></footer>
    </section>
    <button ref="launcher" class="assistant-launcher" :class="{ 'pet-launcher': petMode }" aria-label="打开 AI 助手" :aria-expanded="opened" aria-controls="sv-assistant-panel" title="拖动可移动，点击打开 AI 助手" @pointerdown="startDrag" @lostpointercapture="endDrag" @click="activate"><template v-if="petMode"><component :is="pet.component" /><span>AI 助手</span></template><template v-else><ChatDotRound /><span>AI 助手</span></template></button>
  </aside>
</template>
<style scoped>
.sv-assistant{position:fixed;right:24px;bottom:max(20px,env(safe-area-inset-bottom));z-index:24;font-size:14px}.assistant-launcher{margin-left:auto;display:flex;align-items:center;gap:9px;border:1px solid #25674e;border-radius:999px;padding:14px 20px;background:#35745b;color:white;box-shadow:0 8px 28px #244d3824;cursor:pointer;font-family:inherit;font-size:14px;font-weight:600}.assistant-launcher>svg{width:22px;height:22px}.pet-launcher{flex-direction:column;gap:0;padding:0 8px 7px;border:none;background:transparent;color:var(--sv-text-primary);box-shadow:none}.pet-launcher>span:last-child{font-size:11px;padding:3px 10px;border:1px solid var(--sv-border-default);background:var(--sv-surface-1);border-radius:20px}.assistant-launcher:focus-visible,.assistant-icon-button:focus-visible{outline:3px solid var(--sv-accent-primary);outline-offset:4px}.assistant-launcher{touch-action:none;user-select:none;cursor:grab}.dragging .assistant-launcher{cursor:grabbing}.assistant-panel{position:fixed;width:min(370px,calc(100vw - 32px));max-height:calc(100dvh - 170px);overflow-y:auto;margin-bottom:12px;background:var(--sv-surface-1);border:1px solid var(--sv-border-default);border-radius:20px;box-shadow:0 18px 60px #173d2926}.assistant-panel header{display:flex;align-items:center;gap:10px;padding:18px;border-bottom:1px solid var(--sv-border-subtle)}.assistant-panel header>div{flex:1;min-width:0}.assistant-panel header strong{font-size:14px}.assistant-panel header small{display:block;margin-top:5px;font-size:11px;color:var(--sv-text-secondary)}.assistant-panel-icon{width:34px;height:34px;display:grid;place-items:center;border-radius:10px;background:var(--sv-accent-soft);color:var(--sv-accent-primary)}.assistant-panel-icon svg{width:20px}.assistant-icon-button{display:grid;place-items:center;width:32px;height:32px;border:0;border-radius:8px;background:transparent;color:var(--sv-text-secondary);cursor:pointer}.assistant-icon-button svg{width:18px}.assistant-welcome{text-align:center;padding:30px 24px}.assistant-welcome h2{font-size:19px;margin:16px 0 10px}.assistant-welcome p{font-size:13px;color:var(--sv-text-secondary);line-height:1.8}.assistant-planning{display:inline-block;margin:24px 0 10px;border-radius:20px;background:var(--sv-accent-soft);padding:5px 12px;color:var(--sv-accent-primary);font-size:11px}.assistant-boundary{max-width:260px;margin:auto}.assistant-chat-emblem{display:grid;place-items:center;width:64px;height:64px;margin:4px auto;background:var(--sv-accent-soft);border-radius:20px;color:var(--sv-accent-primary)}.assistant-chat-emblem svg{width:32px}.assistant-panel footer{display:flex;justify-content:space-between;gap:8px;padding:16px 18px;background:var(--sv-surface-2);font-size:11px;color:var(--sv-text-secondary)}.assistant-panel footer a{display:flex;align-items:center;gap:4px;color:var(--sv-accent-primary)}.assistant-panel footer svg{width:12px}@media(max-width:600px){.sv-assistant{right:16px;bottom:max(14px,env(safe-area-inset-bottom))}.assistant-panel{max-height:calc(100dvh - 160px)}.pet-launcher .assistant-pet{width:74px;height:74px}}
</style>
