<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElImage, ElInput, ElProgress, ElTag } from 'element-plus'
import { metadata } from '../api/metadata'
import { singleReview } from '../api/single-review'
import { providerNames } from '../types'
import type { MetadataCandidate, MusicProvider } from '../types'
import type { CoverSelection } from '../single-types'
const props = defineProps<{ open: boolean; songId: string; fingerprint: string; keyword: string; candidates: MetadataCandidate[]; providers: MusicProvider[]; enabled: boolean; currentUrl: string | null; currentValue: string | null; selection: CoverSelection | null }>()
const emit = defineEmits<{ close: []; select: [selection: CoverSelection] }>()
const query = ref(''), choices = ref<CoverSelection[]>([]), selected = ref<CoverSelection | null>(null), error = ref(''), warning = ref('')
const loading = ref(false), uploading = ref(false), progress = ref(0), upload = ref<CoverSelection | null>(null), fileInput = ref<HTMLInputElement | null>(null)
let generation = 0, uploadAbort: AbortController | null = null
const selectedKey = computed(() => selected.value ? `${selected.value.kind}:${selected.value.id}` : '')
watch(() => props.open, open => {
  generation++; uploadAbort?.abort(); loading.value = false; uploading.value = false
  if (!open) return
  query.value = props.keyword.slice(0, 200); error.value = ''; warning.value = ''; selected.value = props.selection; upload.value = props.selection?.kind === 'asset' ? props.selection : null
  choices.value = props.candidates.filter(c => c.track.coverUrl).map(c => ({ kind: 'candidate', id: c.id, value: c.track.coverUrl!, url: metadata.candidateCover(c.id), label: `${providerNames[c.track.provider]} · ${c.track.album || c.track.title}` }))
})
async function search() {
  if (!props.enabled || !props.providers.length || !query.value.trim() || loading.value || uploading.value) return
  const token = ++generation; loading.value = true; error.value = ''; warning.value = ''
  try {
    const result = await singleReview.covers(props.songId, query.value.trim(), props.providers)
    if (token !== generation) return
    choices.value = result.items.map(c => ({ kind: 'candidate', id: c.candidateId, value: c.coverUrl, url: metadata.candidateCover(c.candidateId), label: `${providerNames[c.provider]} · ${c.album || c.title}`, width: c.width, height: c.height }))
    warning.value = result.errors.map(e => `${providerNames[e.provider]}：${e.message}`).join('；')
  } catch (failure) { if (token === generation) error.value = failure instanceof Error ? failure.message : '封面搜索失败' }
  finally { if (token === generation) loading.value = false }
}
async function uploadFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file) return
  error.value = ''
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 2_000_000) { error.value = '请选择 2 MB 以内的 JPEG 或 PNG'; return }
  const token = ++generation; uploadAbort?.abort(); uploadAbort = new AbortController(); uploading.value = true; progress.value = 0
  try {
    const asset = await singleReview.upload(props.songId, props.fingerprint, file, value => { if (token === generation) progress.value = value }, uploadAbort.signal)
    if (token !== generation) return
    upload.value = { kind: 'asset', id: asset.id, value: `asset:${asset.id}`, url: singleReview.assetUrl(asset.id), label: '本地上传 · 尚未应用', width: asset.width, height: asset.height }; selected.value = upload.value
  } catch (failure) { if (token === generation) error.value = failure instanceof Error ? failure.message : '上传失败' }
  finally { if (token === generation) uploading.value = false }
}
function close() { generation++; uploadAbort?.abort(); emit('close') }
onBeforeUnmount(() => { generation++; uploadAbort?.abort() })
</script>
<template>
  <ElDialog :model-value="open" title="选择歌曲封面" width="min(800px, calc(100vw - 32px))" append-to-body :close-on-click-modal="false" @close="close">
    <p class="cover-help">选择仅更新审核草稿，主窗口确认后才应用。JPEG / PNG，最大 2 MB、4096 × 4096 像素。</p>
    <div class="cover-existing"><div v-if="currentUrl"><small>当前有效封面</small><ElImage :src="currentUrl" :preview-src-list="[currentUrl]" preview-teleported fit="cover"><template #error><span>封面不可用</span></template></ElImage><ElButton size="small" @click="selected = { kind: 'current', id: 'current', value: currentValue!, url: currentUrl!, label: '保留当前封面' }">保留当前封面</ElButton></div><div v-if="selected"><small>本次选择 · 点击图片看大图</small><ElImage :src="selected.url" :preview-src-list="[selected.url]" preview-teleported fit="contain" /><span>{{ selected.label }}</span></div></div>
    <form class="cover-search" @submit.prevent="search"><ElInput v-model="query" aria-label="封面搜索关键词" placeholder="歌名 歌手 专辑" maxlength="200" :disabled="uploading" /><ElButton native-type="submit" type="primary" :loading="loading" :disabled="!enabled || !providers.length || uploading">搜索更多封面</ElButton></form>
    <p v-if="!enabled" class="cover-help">平台搜索未启用，仍可上传本地封面。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" /><ElAlert v-if="warning" :title="warning" type="warning" :closable="false" />
    <p v-if="loading" role="status" class="cover-help">正在查询真实平台封面…</p><p v-else-if="!choices.length" class="cover-help">暂无封面候选，可修改关键词搜索或上传本地图片。</p>
    <div class="cover-grid"><button v-for="item in choices" :key="item.id" type="button" :aria-pressed="selectedKey === `${item.kind}:${item.id}`" :class="{ chosen: selectedKey === `${item.kind}:${item.id}` }" @click="selected = item"><img :src="item.url" alt="候选封面" loading="lazy" @error="($event.target as HTMLImageElement).style.opacity = '0.2'" /><span>{{ item.label }}</span><small v-if="item.width">{{ item.width }} × {{ item.height }}</small></button></div>
    <div class="upload-row"><input ref="fileInput" type="file" accept="image/jpeg,image/png" aria-label="上传本地封面文件" class="file-input" @change="uploadFile" /><ElButton :loading="uploading" :disabled="loading" @click="fileInput?.click()">{{ upload ? '更换本地图片' : '上传本地图片' }}</ElButton><ElTag v-if="upload" type="info">已上传至受管存储，尚未应用 · {{ upload.width }} × {{ upload.height }}</ElTag></div>
    <ElProgress v-if="uploading" :percentage="progress" /><p v-if="uploading" role="status" class="cover-help">{{ progress === 100 ? '文件已传输，服务器正在校验图片…' : '正在上传文件…' }}</p>
    <template #footer><ElButton @click="close">取消选择</ElButton><ElButton type="primary" :disabled="!selected || loading || uploading" @click="selected && emit('select', selected)">使用此封面</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.cover-help { font-size: 12px; color: var(--sv-text-muted); line-height: 1.8; margin-bottom: 14px; }.cover-search { display: flex; gap: 10px; margin: 18px 0; }.cover-existing { display: flex; gap: 24px; flex-wrap: wrap; padding: 14px; background: var(--sv-surface-muted); border-radius: var(--sv-r-sm); }.cover-existing > div { display: flex; gap: 10px; flex-direction: column; font-size: 12px; max-width: 220px; }.cover-existing .el-image { width: 96px; height: 96px; border-radius: 8px; }.cover-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(115px,1fr)); gap: 12px; max-height: 280px; overflow: auto; margin: 14px 0; }.cover-grid button { text-align: left; background: var(--sv-surface); border: 1px solid var(--sv-line); padding: 6px; border-radius: 9px; cursor: pointer; color: var(--sv-text); }.cover-grid button.chosen { border: 2px solid var(--el-color-primary); background: var(--sv-brand-50); }.cover-grid img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 5px; }.cover-grid span,.cover-grid small { display: block; font-size: 11px; margin-top: 5px; overflow-wrap: anywhere; }.file-input { display: none; }.upload-row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; margin: 16px 0; }@media(max-width: 500px) { .cover-search { flex-direction: column; } }
</style>
