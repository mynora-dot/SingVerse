<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElDialog, ElImage, ElInput, ElProgress, ElTag } from 'element-plus'
import { metadata } from '../api/metadata'
import { singleReview } from '../api/single-review'
import { coverValue } from '../cover-quality'
import { providerNames } from '../types'
import type { MetadataCandidate, MusicProvider } from '../types'
import type { CoverQuality, CoverSelection } from '../single-types'
import MetadataCover from './MetadataCover.vue'
const props = defineProps<{ open: boolean; songId: string; fingerprint: string; keyword: string; candidates: MetadataCandidate[]; providers: MusicProvider[]; available?: MusicProvider[]; enabled: boolean; currentUrl: string | null; currentValue: string | null; selection: CoverSelection | null }>()
const emit = defineEmits<{ close: []; select: [selection: CoverSelection] }>()
const query = ref(''), choices = ref<CoverSelection[]>([]), selected = ref<CoverSelection | null>(null), error = ref(''), warning = ref('')
const platforms = Object.keys(providerNames) as MusicProvider[]
const selectedProviders = ref<MusicProvider[]>([]), quality = ref<CoverQuality>('ORIGINAL')
const availableProviders = computed(() => props.available ?? props.providers)
const loading = ref(false), uploading = ref(false), progress = ref(0), upload = ref<CoverSelection | null>(null), fileInput = ref<HTMLInputElement | null>(null)
const previewSize = ref<{ width: number; height: number } | null>(null), previewFailed = ref(false), retryKey = ref(0), failedChoices = ref(false)
let generation = 0, uploadAbort: AbortController | null = null
const selectedKey = computed(() => selected.value ? `${selected.value.kind}:${selected.value.id}` : '')
const previewUrl = computed(() => selected.value ? `${selected.value.url}${selected.value.url.includes('?') ? '&' : '?'}retry=${retryKey.value}` : '')
function variant(selection: CoverSelection): CoverSelection {
  if (selection.kind !== 'candidate' || !selection.provider) return selection
  const original = selection.originalValue ?? selection.value
  return { ...selection, originalValue: original, value: coverValue(selection.provider, original, quality.value), quality: quality.value, url: metadata.candidateCover(selection.id, quality.value), width: null, height: null }
}
watch(quality, () => { choices.value = choices.value.map(variant); if (selected.value) selected.value = variant(selected.value) })
watch(previewUrl, () => { previewSize.value = null; previewFailed.value = false })
watch(() => props.open, open => {
  generation++; uploadAbort?.abort(); loading.value = false; uploading.value = false
  if (!open) return
  quality.value = props.selection?.quality ?? 'ORIGINAL'
  selectedProviders.value = [...props.providers]
  query.value = props.keyword.slice(0, 200); error.value = ''; warning.value = ''; selected.value = props.selection; upload.value = props.selection?.kind === 'asset' ? props.selection : null
  previewSize.value = null; previewFailed.value = false; failedChoices.value = false; retryKey.value = 0
  choices.value = props.candidates.filter(c => c.track.coverUrl).map(c => variant({ kind: 'candidate', id: c.id, value: c.track.coverUrl!, originalValue: c.track.coverUrl!, provider: c.track.provider, url: '', label: `${providerNames[c.track.provider]} · ${c.track.album || c.track.title}` }))
})
async function search() {
  const providers = selectedProviders.value.filter(p => availableProviders.value.includes(p))
  if (!props.enabled || !providers.length || !query.value.trim() || loading.value || uploading.value) return
  const token = ++generation; loading.value = true; error.value = ''; warning.value = ''
  try {
    const result = await singleReview.covers(props.songId, query.value.trim(), providers)
    if (token !== generation) return
    choices.value = result.items.map(c => variant({ kind: 'candidate', id: c.candidateId, value: c.coverUrl, originalValue: c.coverUrl, provider: c.provider, url: '', label: `${providerNames[c.provider]} · ${c.album || c.title}` }))
    failedChoices.value = false
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
function readSize(event: Event) { const image = event.target as HTMLImageElement; previewSize.value = { width: image.naturalWidth, height: image.naturalHeight } }
function retryImages() { retryKey.value++; failedChoices.value = false }
function selectCover() { if (selected.value) emit('select', { ...selected.value, ...(previewSize.value ?? {}) }) }
function close() { generation++; uploadAbort?.abort(); emit('close') }
onBeforeUnmount(() => { generation++; uploadAbort?.abort() })
</script>
<template>
  <ElDialog :model-value="open" title="选择歌曲封面" class="song-cover-dialog" width="min(820px, calc(100vw - 32px))" top="4vh" append-to-body :close-on-click-modal="false" @close="close">
    <p class="cover-help">选择仅更新审核草稿，主窗口确认后才应用。默认保留平台原图，预览缩小显示不会改变保存的图片。</p>
    <div class="cover-quality" role="group" aria-label="平台封面画质"><span>图片版本</span><div><button v-for="option in [{ value: 'ORIGINAL', label: '原图' }, { value: 'HIGH', label: '高清' }]" :key="option.value" type="button" :aria-pressed="quality === option.value" @click="quality = option.value as CoverQuality">{{ option.label }}</button></div><small>{{ quality === 'ORIGINAL' ? '保留原始分辨率与文件' : '使用平台提供的高清版本，尺寸因平台而异' }}</small></div>
    <div class="cover-existing"><div v-if="currentUrl"><small>当前有效封面</small><ElImage :src="currentUrl" :preview-src-list="[currentUrl]" preview-teleported fit="cover"><template #error><span>当前封面加载失败</span></template></ElImage><ElButton size="small" @click="selected = { kind: 'current', id: 'current', value: currentValue!, url: currentUrl!, label: '保留当前封面' }">保留当前封面</ElButton></div><div v-if="selected"><small>本次选择 · 点击图片看大图</small><ElImage :key="previewUrl" :src="previewUrl" :preview-src-list="[selected.url]" preview-teleported fit="contain" @load="readSize" @error="previewFailed = true"><template #error><span>封面加载失败</span></template></ElImage><span>{{ selected.label }}<template v-if="selected.kind === 'candidate'"> · {{ quality === 'ORIGINAL' ? '原图' : '高清' }}</template></span><small v-if="previewSize" class="cover-dimensions">{{ previewSize.width }} × {{ previewSize.height }} 像素</small><ElButton v-if="previewFailed" size="small" @click="retryImages">重试封面</ElButton></div></div>
    <form class="cover-search" @submit.prevent="search"><fieldset class="cover-platforms"><legend>查询平台</legend><ElCheckbox v-for="platform in platforms" :key="platform" :model-value="enabled && selectedProviders.includes(platform)" :disabled="!enabled || !availableProviders.includes(platform) || loading || uploading" @change="checked => selectedProviders = checked ? [...selectedProviders, platform] : selectedProviders.filter(p => p !== platform)">{{ providerNames[platform] }}<small v-if="!availableProviders.includes(platform)">（未启用）</small></ElCheckbox></fieldset><div class="cover-query"><ElInput v-model="query" aria-label="封面搜索关键词" placeholder="歌名 歌手 专辑" maxlength="200" :disabled="uploading" /><ElButton native-type="submit" type="primary" :loading="loading" :disabled="!enabled || !selectedProviders.length || !query.trim() || uploading">搜索更多封面</ElButton></div></form>
    <p v-if="!enabled" class="cover-help">平台搜索未启用，仍可上传本地封面。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" /><ElAlert v-if="warning" :title="warning" type="warning" :closable="false" />
    <p v-if="loading" role="status" class="cover-help">正在查询真实平台封面…</p><p v-else-if="!choices.length" class="cover-help">平台未提供封面候选，可修改关键词、切换平台或上传本地图片。</p>
    <p v-if="failedChoices" role="status" class="cover-failure">部分图片加载失败，候选信息仍可核对。<ElButton text @click="retryImages">重试封面</ElButton></p>
    <div class="cover-grid"><button v-for="item in choices" :key="item.id" type="button" :aria-pressed="selectedKey === `${item.kind}:${item.id}`" :class="{ chosen: selectedKey === `${item.kind}:${item.id}` }" @click="selected = item"><MetadataCover :src="item.url" alt="候选封面" :retry-key="retryKey" @failed="failedChoices = true" /><span>{{ item.label }}</span><small>{{ quality === 'ORIGINAL' ? '原图' : '高清' }}</small></button></div>
    <div class="upload-row"><input ref="fileInput" type="file" accept="image/jpeg,image/png" aria-label="上传本地封面文件" class="file-input" @change="uploadFile" /><ElButton :loading="uploading" :disabled="loading" @click="fileInput?.click()">{{ upload ? '更换本地图片' : '上传本地图片' }}</ElButton><ElTag v-if="upload" type="info">已上传，尚未应用 · {{ upload.width }} × {{ upload.height }}</ElTag><small>本地上传：JPEG / PNG，2 MB 以内。</small></div>
    <ElProgress v-if="uploading" :percentage="progress" /><p v-if="uploading" role="status" class="cover-help">{{ progress === 100 ? '文件已传输，服务器正在校验图片…' : '正在上传文件…' }}</p>
    <template #footer><ElButton @click="close">取消选择</ElButton><ElButton type="primary" :disabled="!selected || loading || uploading || previewFailed" @click="selectCover">使用此封面</ElButton></template>
  </ElDialog>
</template>
<style scoped>
:global(.song-cover-dialog.el-dialog) { display: flex; flex-direction: column; max-height: 92dvh; overflow: hidden; margin-bottom: 0; }
:global(.song-cover-dialog > .el-dialog__header),:global(.song-cover-dialog > .el-dialog__footer) { flex-shrink: 0; }
:global(.song-cover-dialog > .el-dialog__body) { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
:global(.song-cover-dialog > .el-dialog__footer) { border-top: 1px solid var(--sv-border-subtle); padding-top: 16px; }
.cover-help { font-size: 12px; color: var(--sv-text-secondary); line-height: 1.8; margin: 0 0 14px; }
.cover-quality { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; font-size: 12px; }
.cover-quality > div { display: flex; border: 1px solid var(--sv-border-default); padding: 3px; border-radius: 8px; gap: 4px; }
.cover-quality button { border: 0; border-radius: 5px; background: transparent; color: var(--sv-text-secondary); padding: 6px 14px; cursor: pointer; font: inherit; }
.cover-quality button[aria-pressed=true] { background: var(--sv-accent-primary); color: var(--sv-text-inverse); }
.cover-quality > small { color: var(--sv-text-secondary); }
.cover-search { margin: 18px 0; }
.cover-query { display: flex; gap: 10px; margin-top: 8px; }
.cover-platforms { padding: 0; margin: 0; border: 0; display: flex; flex-wrap: wrap; gap: 0 12px; }
.cover-platforms legend { color: var(--sv-text-secondary); font-size: 12px; margin-bottom: 4px; }
.cover-platforms :deep(.el-checkbox) { margin: 0; height: 30px; }
.cover-platforms :deep(.el-checkbox__label) { font-size: 12px; }
.cover-platforms small { font-size: 11px; }
.cover-existing { display: flex; gap: 24px; flex-wrap: wrap; padding: 14px; background: var(--sv-surface-3); border-radius: var(--sv-radius-sm); }
.cover-existing:empty { display: none; }
.cover-existing > div { display: flex; gap: 8px; flex-direction: column; font-size: 12px; max-width: 260px; }
.cover-existing .el-image { width: 112px; height: 112px; border-radius: 8px; }
.cover-dimensions { color: var(--sv-text-secondary); font-variant-numeric: tabular-nums; }
.cover-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(120px,1fr)); gap: 12px; margin: 14px 0; }
.cover-grid button { text-align: left; background: var(--sv-surface-2); border: 1px solid var(--sv-border-subtle); padding: 6px; border-radius: 9px; cursor: pointer; color: var(--sv-text-primary); }
.cover-grid button.chosen { border-color: var(--el-color-primary); box-shadow: inset 0 0 0 1px var(--el-color-primary); background: var(--sv-accent-soft); }
.cover-grid :deep(.metadata-cover) { width: 100%; height: auto; aspect-ratio: 1; }
.cover-grid span,.cover-grid small { display: block; font-size: 11px; margin-top: 5px; overflow-wrap: anywhere; }
.cover-failure { color: var(--sv-text-secondary); font-size: 12px; }
.file-input { display: none; }
.upload-row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; margin: 16px 0; }
.upload-row > small { color: var(--sv-text-secondary); font-size: 11px; }
@media(max-width: 500px) { .cover-query { flex-direction: column; }.cover-quality > small { width: 100%; }.cover-existing { gap: 14px; }.cover-existing > div { max-width: 100%; } }
</style>
