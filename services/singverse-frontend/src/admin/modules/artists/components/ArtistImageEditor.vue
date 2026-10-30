<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElAlert, ElButton, ElDialog, ElMessage, ElMessageBox } from 'element-plus'
import type { Artist, ImageKind } from '../types'
import { artists } from '../api/artists'
const props = defineProps<{ artist: Artist; kind: ImageKind }>()
const emit = defineEmits<{ close: []; changed: [artist: Artist] }>()
const file = ref<File | null>(null), local = ref(''), error = ref(''), busy = ref(false)
const kindName = computed(() => props.kind === 'portrait' ? '头像' : '背景图')
const path = computed(() => props.kind === 'portrait' ? props.artist.portraitPath : props.artist.backgroundPath)
const version = computed(() => props.kind === 'portrait' ? props.artist.portraitVersion : props.artist.backgroundVersion)
const preview = computed(() => local.value || (path.value ? `${path.value}?v=${version.value}` : ''))
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
  try { const tag = await artists.upload(props.artist.id, props.kind, version.value || '', file.value, controller.signal); if(active) { clear(); emit('changed', tag); ElMessage.success('歌手图片已保存，电视刷新歌手页后可见') } }
  catch(e) { if(active) error.value = e instanceof Error ? e.message : '图片上传失败' }
  finally { if(active) busy.value = false }
}
async function remove() {
  if(busy.value || !version.value) return
  try { await ElMessageBox.confirm('移除后电视将显示默认歌手样式，歌曲关联不受影响。', '移除歌手图片', { type: 'warning', confirmButtonText: '移除图片', cancelButtonText: '保留', autofocus: false }) } catch { return }
  if(!active) return
  busy.value = true; error.value = ''
  try { const tag = await artists.removeImage(props.artist.id,props.kind,version.value!); if(active) { clear();emit('changed',tag);ElMessage.success('图片已移除') } }
  catch(e) { if(active) error.value = e instanceof Error ? e.message : '移除失败' }
  finally { if(active) busy.value = false }
}
onBeforeUnmount(() => { active = false; controller.abort(); clear() })
</script>
<template>
  <ElDialog :model-value="true" :title="`${artist.name} · ${kindName}`" width="min(600px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="emit('close')">
    <div class="cover-preview" :class="{ portrait: kind === 'portrait' }"><img v-if="preview" :src="preview" :alt="`${artist.name}图片预览`" /><div v-else class="cover-placeholder"><span>{{ artist.name.slice(0,2) }}</span></div><div class="cover-caption"><small>{{ kindName }}预览</small><strong>{{ artist.name }}</strong></div></div>
    <p class="cover-help">{{ kind === 'portrait' ? '头像建议使用正方形人物照。' : '背景图建议使用16:9横图。' }}主体放在中间。支持 JPEG / PNG，最多 2MB、4096 × 4096 像素；电视会居中裁切。保存后自动压缩并移除图片附加信息。</p>
    <label class="cover-picker" :class="{ disabled: busy }"><input aria-label="选择歌手图片" type="file" accept="image/jpeg,image/png" :disabled="busy" @change="choose" />{{ file ? '重新选择图片' : '选择图片' }}</label>
    <p v-if="file" class="cover-help">{{ file.name }} · {{ Math.ceil(file.size/1024) }} KB · 尚未保存</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template #footer><ElButton v-if="version" text type="danger" :disabled="busy" @click="remove">移除图片</ElButton><ElButton :disabled="busy" @click="emit('close')">完成</ElButton><ElButton type="primary" :disabled="!file" :loading="busy" @click="save">保存图片</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.cover-preview{position:relative;aspect-ratio:16/9;overflow:hidden;border-radius:16px;background:#191b20}.cover-preview.portrait{aspect-ratio:1;width:min(320px,100%);margin:auto}.cover-preview>img,.cover-placeholder{width:100%;height:100%;object-fit:cover}.cover-placeholder{display:grid;place-items:center;background:linear-gradient(125deg,#4a5d7b,#1a2130)}.cover-placeholder span{font-size:64px;color:#ffffff30;font-weight:700;letter-spacing:12px}.cover-caption{position:absolute;inset:0;display:flex;flex-direction:column;justify-content:flex-end;gap:6px;padding:24px;color:#f5f5f7;background:linear-gradient(transparent 30%,#08090be8)}.cover-caption small{font-size:12px;color:#ddd}.cover-caption strong{font-size:26px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.cover-help{font-size:12px;line-height:1.8;color:var(--sv-text-secondary);margin:16px 0}.cover-picker{display:inline-flex;padding:10px 16px;border:1px solid var(--sv-border-default);border-radius:9px;cursor:pointer;color:var(--sv-text-primary);font-weight:500}.cover-picker:focus-within{outline:2px solid var(--sv-accent-primary);outline-offset:3px}.cover-picker input{position:absolute;width:1px;height:1px;opacity:0}.cover-picker.disabled{opacity:.5;cursor:wait}
</style>
