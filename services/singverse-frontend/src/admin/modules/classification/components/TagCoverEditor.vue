<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElAlert, ElButton, ElDialog, ElMessage, ElMessageBox } from 'element-plus'
import type { Tag } from '../types'
import { classification } from '../api/classification'
const props = defineProps<{ tag: Tag }>()
const emit = defineEmits<{ close: []; changed: [tag: Tag] }>()
const file = ref<File | null>(null), local = ref(''), error = ref(''), busy = ref(false)
const preview = computed(() => local.value || (props.tag.coverPath ? `${props.tag.coverPath}?v=${props.tag.coverVersion}` : ''))
const controller = new AbortController()
let active = true
function clear() { if(local.value) URL.revokeObjectURL(local.value); local.value = ''; file.value = null }
function choose(event: Event) {
  const input = event.target as HTMLInputElement, next = input.files?.[0]; input.value = ''
  if(!next || busy.value) return
  error.value = ''
  if(!['image/jpeg','image/png'].includes(next.type) || next.size > 2_000_000 || !next.size) { error.value = '请选择 2MB 以内的 JPEG 或 PNG 图片'; return }
  clear(); file.value = next; local.value = URL.createObjectURL(next)
}
async function save() {
  if(!file.value || busy.value) return
  busy.value = true; error.value = ''
  try { const tag = await classification.uploadCover(props.tag.id, props.tag.coverVersion || '', file.value, controller.signal); if(active) { clear(); emit('changed', tag); ElMessage.success('分类封面已保存，电视刷新分类后可见') } }
  catch(e) { if(active) error.value = e instanceof Error ? e.message : '封面上传失败' }
  finally { if(active) busy.value = false }
}
async function remove() {
  if(busy.value || !props.tag.coverVersion) return
  try { await ElMessageBox.confirm('移除后电视将显示默认分类卡片，歌曲分类不受影响。', '移除分类封面', { type: 'warning', confirmButtonText: '移除封面', cancelButtonText: '保留', autofocus: false }) } catch { return }
  if(!active) return
  busy.value = true; error.value = ''
  try { const tag = await classification.removeCover(props.tag.id,props.tag.coverVersion); if(active) { clear();emit('changed',tag);ElMessage.success('封面已移除') } }
  catch(e) { if(active) error.value = e instanceof Error ? e.message : '移除失败' }
  finally { if(active) busy.value = false }
}
onBeforeUnmount(() => { active = false; controller.abort(); clear() })
</script>
<template>
  <ElDialog :model-value="true" :title="`${tag.name} · 分类封面`" width="min(600px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="emit('close')">
    <div class="cover-preview"><img v-if="preview" :src="preview" :alt="`${tag.name}封面预览`" /><div v-else class="cover-placeholder"><span>{{ tag.name.slice(0,2) }}</span></div><div class="cover-caption"><small>分类封面预览</small><strong>{{ tag.name }}</strong></div></div>
    <p class="cover-help">建议使用 16:9 横图，主体放在中间。支持 JPEG / PNG，最多 2MB、4096 × 4096 像素；电视会居中裁切。保存后自动压缩并移除图片附加信息。</p>
    <label class="cover-picker" :class="{ disabled: busy }"><input aria-label="选择分类封面图片" type="file" accept="image/jpeg,image/png" :disabled="busy" @change="choose" />{{ file ? '重新选择图片' : '选择封面图片' }}</label>
    <p v-if="file" class="cover-help">{{ file.name }} · {{ Math.ceil(file.size/1024) }} KB · 尚未保存</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template #footer><ElButton v-if="tag.coverVersion" text type="danger" :disabled="busy" @click="remove">移除封面</ElButton><ElButton :disabled="busy" @click="emit('close')">完成</ElButton><ElButton type="primary" :disabled="!file" :loading="busy" @click="save">保存封面</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.cover-preview{position:relative;aspect-ratio:16/9;overflow:hidden;border-radius:16px;background:#191b20}.cover-preview>img,.cover-placeholder{width:100%;height:100%;object-fit:cover}.cover-placeholder{display:grid;place-items:center;background:linear-gradient(125deg,#4a5d7b,#1a2130)}.cover-placeholder span{font-size:64px;color:#ffffff30;font-weight:700;letter-spacing:12px}.cover-caption{position:absolute;inset:0;display:flex;flex-direction:column;justify-content:flex-end;gap:6px;padding:24px;color:#f5f5f7;background:linear-gradient(transparent 30%,#08090be8)}.cover-caption small{font-size:12px;color:#ddd}.cover-caption strong{font-size:26px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.cover-help{font-size:12px;line-height:1.8;color:var(--sv-text-secondary);margin:16px 0}.cover-picker{display:inline-flex;padding:10px 16px;border:1px solid var(--sv-border-default);border-radius:9px;cursor:pointer;color:var(--sv-text-primary);font-weight:500}.cover-picker:focus-within{outline:2px solid var(--sv-accent-primary);outline-offset:3px}.cover-picker input{position:absolute;width:1px;height:1px;opacity:0}.cover-picker.disabled{opacity:.5;cursor:wait}
</style>
