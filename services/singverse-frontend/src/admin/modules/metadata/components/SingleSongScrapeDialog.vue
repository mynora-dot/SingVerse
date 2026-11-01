<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElDialog, ElEmpty, ElInput, ElMessageBox, ElTag } from 'element-plus'
import { Edit, Picture, Search, CircleCheckFilled } from '@element-plus/icons-vue'
import type { MediaSong } from '../../media-library/types'
import type { MetadataCandidate } from '../types'
import { fieldNames, providerNames } from '../types'
import { hasCloseAlternative } from '../candidate-selection'
import { metadata } from '../api/metadata'
import { singleReview } from '../api/single-review'
import { candidateValues, present, useSingleSongScrape } from '../composables/useSingleSongScrape'
import type { CoverSelection, Values, ReviewField } from '../single-types'
import MetadataFieldComparison from './MetadataFieldComparison.vue'
import SingleMetadataEditor from './SingleMetadataEditor.vue'
import SingleCoverPicker from './SingleCoverPicker.vue'
const props = defineProps<{ song?: MediaSong | null; songId?: string | null; initialCandidates?: MetadataCandidate[]; batchId?: string; itemId?: string; autoSelectThreshold?: number }>()
const emit = defineEmits<{ close: []; applied: [] }>()
const state = useSingleSongScrape()
const { review, keyword, providers, available, enabled, loading, searching, applying, error, searchError, warning, candidates,
  selected, draft, fields, edits, cover, result, changed, dirty, autoSelected, preview, canSearch, searched, settingsStatus } = state
const editorOpen = ref(false), coverOpen = ref(false), resultCoverFailed = ref(false)
const platforms = Object.keys(providerNames) as (keyof typeof providerNames)[]
const manualDraft = computed(() => Object.keys(edits.value).length > 0 || (!!cover.value && (cover.value.kind !== 'candidate' || cover.value.id !== selected.value?.id)))
const previewCover = computed(() => preview.value?.cover === review.value?.values.cover ? currentCover.value : cover.value?.url ?? null)
const resultCover = computed(() => {
  if (!result.value?.updated.includes('cover') || !result.value.after.cover) return null
  const value = String(result.value.after.cover)
  return value.startsWith('asset:') ? singleReview.assetUrl(value.slice(6)) : review.value ? metadata.songCover(review.value.song.id) : null
})
const skippedLabels = computed(() => result.value?.skipped.map(f => fieldNames[f]).join('、') ?? '')
const retainedLabels = computed(() => result.value?.retained.map(f => fieldNames[f]).join('、') ?? '')
const currentCover = computed(() => review.value?.values.cover ? metadata.songCover(review.value.song.id) : null)
const fileFormat = computed(() => (review.value?.song.relativePath.split('.').pop() ?? '').toUpperCase())
const fileSize = computed(() => review.value?.song.fileSize == null ? '大小未知' : `${(review.value.song.fileSize / 1024 / 1024).toFixed(1)} MB`)
const coverKeyword = computed(() => draft.value ? [draft.value.title, ...(draft.value.artists as string[] ?? []), draft.value.album].filter(Boolean).join(' ').slice(0, 200) : keyword.value)
const activeSongId = computed(() => props.songId ?? props.song?.id ?? null)
watch(activeSongId, id => { editorOpen.value = false; coverOpen.value = false; if (id) void state.open(id, props.initialCandidates, props.autoSelectThreshold); else state.invalidate() }, { immediate: true })
onBeforeUnmount(state.invalidate)
function display(value: unknown, field = '') {
  if (!present(value)) return '—'
  if (Array.isArray(value)) return value.join('、')
  if (field === 'durationMs') { const seconds = Math.round(Number(value) / 1000); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
  return field === 'cover' ? '已选择封面' : String(value)
}
async function discard(message: string) {
  try { await ElMessageBox.confirm(message, '有尚未应用的修改', {
    customClass: 'discard-message-box',
    confirmButtonText: '放弃修改',
    cancelButtonText: '继续审核',
    confirmButtonClass: 'discard-confirm',
    cancelButtonClass: 'discard-continue',
    closeOnClickModal: false,
    type: 'warning',
  }); return true } catch { return false }
}
async function close() { if (applying.value) return; if (dirty.value && !await discard('关闭将放弃当前审核草稿；已上传但未应用的图片会在过期后清理。')) return; state.invalidate(); emit('close') }
async function search() {
  if (!canSearch.value) return
  if (manualDraft.value && !await discard('重新搜索会清除候选选择、手工草稿和封面选择。')) return
  await state.search()
}
async function clearSelection() {
  if (manualDraft.value && !await discard('取消候选将恢复当前入库信息并放弃尚未应用的草稿。')) return
  state.clearSelection()
}
async function choose(candidate: MetadataCandidate) { if (manualDraft.value) if (!await discard('切换候选会替换已编辑的草稿和封面选择。')) return; state.choose(candidate) }
async function openSettings() {
  if (dirty.value && !await discard('前往设置会离开当前审核并放弃草稿。')) return
  state.invalidate(); emit('close'); window.location.hash = '/library/songs/metadata'
}
async function reload() { if (!activeSongId.value) return; if (dirty.value && !await discard('重新加载会放弃草稿，并读取最新歌曲数据。')) return; await state.open(activeSongId.value, props.initialCandidates, props.autoSelectThreshold) }
function saveEdits(values: Values) { state.saveEdits(values); editorOpen.value = false }
function chooseCover(value: CoverSelection) { state.chooseCover(value); coverOpen.value = false }
async function apply() { resultCoverFailed.value = false; await state.apply(props.batchId && props.itemId ? (_songId, input) => singleReview.applyBatch(props.batchId!, props.itemId!, input) : singleReview.apply); if (result.value) emit('applied') }
</script>
<template>
  <ElDialog :model-value="!!activeSongId" title="元数据刮削 · 匹配与审核" :width="result ? 'min(780px, calc(100vw - 32px))' : 'min(1240px, calc(100vw - 32px))'" top="4vh" :class="['single-scrape-dialog', { 'single-scrape-success': !!result }]" :close-on-click-modal="false" :close-on-press-escape="!applying" :show-close="!applying" :before-close="close">
    <div v-if="result" class="review-success" role="status"><CircleCheckFilled class="success-icon" /><h2>元数据已应用成功</h2><p>本次更新 {{ result.updated.length }} 项，跳过 {{ result.skipped.length }} 项，保留 {{ result.retained.length }} 项。</p><table><thead><tr><th>字段</th><th>应用前</th><th>应用后</th></tr></thead><tbody><tr v-for="field in result.updated" :key="field"><td>{{ fieldNames[field] }}</td><td>{{ display(result.before[field as ReviewField], field) }}</td><td><template v-if="field === 'cover' && resultCover && !resultCoverFailed"><img class="result-cover" :src="resultCover" alt="已实际应用的歌曲封面" @error="resultCoverFailed = true" /><span>封面已应用</span></template><template v-else>{{ field === 'cover' && resultCoverFailed ? '封面已应用，图片暂不可预览' : display(result.after[field as ReviewField], field) }}</template></td></tr></tbody></table><p v-if="result.skipped.length">跳过（无变化或无值）：{{ skippedLabels }}</p><p>保留：{{ retainedLabels || '无' }}。原始媒体文件未修改。</p></div>
    <div v-else class="review-body">
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton text :disabled="applying" @click="reload">重新加载歌曲</ElButton><span>重新加载会提示放弃草稿；网络失败可直接重试应用。</span></ElAlert>
      <p v-if="loading" class="workspace-hint" role="status">正在读取当前歌曲与确认元数据…</p>
      <template v-if="review && draft">
        <div class="song-context"><div class="file-context"><img v-if="currentCover" :src="currentCover" alt="当前有效封面" @error="($event.target as HTMLImageElement).style.visibility = 'hidden'" /><div v-else class="cover-placeholder">♫</div><div><span class="section-caption">当前歌曲 · 入库信息</span><h3>{{ display(review.values.title) }}</h3><p>{{ display(review.values.artists) }}</p><div class="file-badges"><span>{{ fileFormat || '格式未知' }}</span><span>{{ fileSize }}</span><span v-if="review.values.durationMs">平台时长 {{ display(review.values.durationMs, 'durationMs') }}</span></div><p class="file-path" :title="review.song.relativePath">{{ review.song.sourceName }} / {{ review.song.relativePath }}</p></div></div>
          <div class="existing-context"><span class="section-caption">当前有效元数据</span><dl><dt>专辑</dt><dd>{{ display(review.values.album) }}</dd><dt>发行日期</dt><dd>{{ display(review.values.releaseDate) }}</dd><dt>别名</dt><dd>{{ display(review.values.aliases) }}</dd><dt>封面</dt><dd>{{ currentCover ? '已有确认封面' : '未确认' }}</dd></dl><small v-if="review.song.metadata?.stale" class="stale-note">文件属性已变化，旧确认结果已失效，当前使用文件名解析值。</small></div></div>
        <form class="workspace-search" @submit.prevent="search"><div class="platform-select"><span>查询平台</span><div><ElCheckbox v-for="platform in platforms" :key="platform" :model-value="enabled && available.includes(platform) && providers.includes(platform)" :disabled="!enabled || !available.includes(platform) || searching || applying || loading" @change="value => providers = value ? [...providers, platform] : providers.filter(p => p !== platform)">{{ providerNames[platform] }}<small v-if="!enabled || !available.includes(platform)">（未启用）</small></ElCheckbox></div></div><ElInput v-model="keyword" aria-label="单曲搜索关键词" placeholder="歌名 歌手" maxlength="200" :prefix-icon="Search" :disabled="applying" /><ElButton type="primary" native-type="submit" :loading="searching" :disabled="!canSearch">重新搜索</ElButton></form>
        <div v-if="!enabled || !available.length" class="platform-notice"><span>{{ settingsStatus === 'error' ? '无法读取平台配置。' : '平台查询未启用。' }}仍可手工编辑或上传封面。</span><a href="#/library/songs/metadata" @click.prevent="openSettings">打开刮削设置 ↗</a></div>
        <ElAlert v-if="warning" :title="warning" type="warning" :closable="false" />
        <div class="review-workspace"><section class="candidate-pane" aria-label="歌曲候选"><div class="pane-title"><h3>匹配候选</h3><span>{{ searching ? '查询中' : searchError ? '查询失败' : searched ? `${candidates.length} 条结果` : '尚未搜索' }}</span></div>
          <div v-if="searching" class="workspace-state" role="status"><span class="search-pulse">⌕</span><h3>正在搜索匹配结果…</h3><p>正在查询所选平台，请稍候。搜索不会应用元数据。</p></div>
          <div v-else-if="searchError" class="workspace-state"><h3>平台请求失败</h3><p role="alert">{{ searchError }}</p><ElButton @click="search">重试搜索</ElButton><ElButton @click="editorOpen = true">手工编辑</ElButton></div>
          <div v-else-if="!candidates.length" class="workspace-state"><ElEmpty :description="!enabled || !available.length ? '平台查询未就绪' : searched ? '未找到匹配结果' : '尚未搜索歌曲'" :image-size="48" /><p>{{ !enabled || !available.length ? '启用平台后可查询；也可直接完善当前歌曲。' : searched ? '可调整关键词、切换已启用平台，或手工填写。' : '输入歌名和歌手，选择可用平台开始搜索。' }}</p><div class="empty-actions"><ElButton type="primary" plain @click="editorOpen = true">手工编辑元数据</ElButton><ElButton @click="coverOpen = true">选择或上传封面</ElButton></div></div>
          <div v-else class="candidate-scroll"><button v-for="candidate in candidates" :key="candidate.id" type="button" class="review-candidate" :class="{ chosen: selected?.id === candidate.id }" :aria-pressed="selected?.id === candidate.id" :disabled="applying" @click="choose(candidate)"><span class="candidate-radio">{{ selected?.id === candidate.id ? '◉' : '○' }}</span><img v-if="candidate.track.coverUrl" :src="metadata.candidateCover(candidate.id)" alt="候选歌曲封面" loading="lazy" @error="($event.target as HTMLImageElement).style.opacity = '0.2'" /><span v-else class="candidate-no-cover">♫</span><span class="candidate-info"><strong>{{ candidate.track.title }}</strong><span>{{ candidate.track.artists.join('、') || '未知歌手' }}</span><small>{{ candidate.track.album || '未知专辑' }} · {{ candidate.track.releaseDate || '日期未知' }}</small><ElTag size="small" effect="plain">{{ providerNames[candidate.track.provider] }}</ElTag></span><span class="candidate-score"><strong>{{ Math.round(candidate.score * 100) }}%</strong><small>匹配相似度</small></span></button></div>
        </section><section class="preview-pane" aria-label="元数据预览与审核"><div class="pane-title"><h3>{{ selected ? '选中候选与应用预览' : manualDraft ? '手工编辑草稿 · 尚未应用' : '当前入库信息 · 尚未选择候选' }}</h3><div><ElButton v-if="selected" link :disabled="applying" @click="clearSelection">取消候选</ElButton><ElButton link type="primary" :icon="Edit" :disabled="applying" @click="editorOpen = true">编辑元数据</ElButton><ElButton link type="primary" :icon="Picture" :disabled="applying" @click="coverOpen = true">选择封面</ElButton></div></div>
          <div class="preview-scroll"><p v-if="autoSelected && selected" class="selection-hint">已自动选中高相似度候选，仅用于预览，尚未写入。{{ hasCloseAlternative(candidates, selected) ? '存在其他接近候选，请核对版本后确认。' : '核对字段后点击确认应用。' }}</p><p class="selection-hint">{{ selected ? '下方预览仅包含勾选的有效变更，取消勾选会保留当前值。' : manualDraft ? '正在编辑本次草稿，确认应用前不会写入。' : '尚未选择候选，当前显示原始入库信息与有效确认值。' }}</p><div class="selected-preview"><img v-if="previewCover" :src="previewCover" alt="审核草稿封面" @error="($event.target as HTMLImageElement).style.visibility = 'hidden'" /><div v-else class="cover-placeholder">♫</div><div class="preview-copy"><h3>{{ display(preview?.title) }}</h3><p>{{ display(preview?.artists) }}</p><p>{{ display(preview?.album) }} · {{ display(preview?.releaseDate) }}</p><ElTag v-if="selected" size="small">{{ providerNames[selected.track.provider] }}</ElTag><ElTag v-else size="small" type="info">{{ manualDraft ? '手工草稿 · 尚未应用' : '当前有效值' }}</ElTag><small v-if="cover?.kind === 'asset' && changed.includes('cover')" class="upload-note">本地封面已上传，尚未应用</small></div><div v-if="selected" class="preview-score">{{ Math.round(selected.score * 100) }}%<small>相似度</small></div></div>
          <p v-if="Object.values(review.song.metadata?.origins ?? {}).some(origin => origin.kind === 'FILE')" class="selection-hint">歌手可手工订正，保存到 NAS 后优先显示，重新扫描仍会保留；其他内嵌字段继续以文件为准。</p><div class="comparison-title"><strong>将更新的字段</strong><span>共 {{ changed.length }} 项</span></div><MetadataFieldComparison :before="review.values" :draft="draft" :fields="fields" :edits="edits" :disabled="applying" @change="fields = $event" />
          <p class="overwrite-note">勾选“将修改”的字段会覆盖当前确认值；未勾选、无变化及空值均保留现状。</p>
        </div></section></div>
        <SingleMetadataEditor :open="editorOpen" :before="review.values" :draft="draft" :original="selected ? candidateValues(selected) : null" @close="editorOpen = false" @save="saveEdits" @cover="coverOpen = true" />
        <SingleCoverPicker :open="coverOpen" :song-id="review.song.id" :fingerprint="review.fingerprint" :keyword="coverKeyword" :candidates="candidates" :providers="providers" :enabled="enabled" :current-url="currentCover" :current-value="review.values.cover as string | null" :selection="cover" @close="coverOpen = false" @select="chooseCover" />
      </template>
    </div>
    <template #footer><div class="workspace-footer"><p>仅应用已勾选且有效的变更；搜索和上传不会自动写入歌曲，原始文件保持只读。</p><div><ElButton :disabled="applying" @click="close">{{ result ? '关闭' : '取消' }}</ElButton><ElButton v-if="!result" type="primary" :loading="applying" :disabled="!changed.length || loading || searching" @click="apply">确认应用（{{ changed.length }} 项）</ElButton></div></div></template>
  </ElDialog>
</template>
<style scoped>
/* Header/footer are ordinary flex siblings: no overlay positioning or guessed deductions. */
:global(.single-scrape-dialog.el-dialog) {
  display: flex; flex-direction: column; height: 92vh; height: 92dvh;
  max-height: 92vh; max-height: 92dvh; margin-bottom: 0; padding: 0;
  border-radius: var(--sv-radius-md); overflow: hidden; box-sizing: border-box;
}
:global(.single-scrape-dialog.el-dialog > .el-dialog__header) {
  flex: 0 0 auto; position: relative; margin: 0; padding: 16px 52px 14px 20px;
  background: var(--sv-surface-2); border-bottom: 1px solid var(--sv-border-subtle);
}
:global(.single-scrape-dialog.el-dialog > .el-dialog__header .el-dialog__headerbtn) { top: 10px; right: 12px; }
:global(.single-scrape-dialog.el-dialog > .el-dialog__body) {
  flex: 1 1 auto; min-height: 0; min-width: 0; display: flex; flex-direction: column;
  overflow: hidden; padding: 12px 20px;
}
:global(.single-scrape-dialog.el-dialog > .el-dialog__footer) {
  flex: 0 0 auto; position: static; padding: 12px 20px;
  border-top: 1px solid var(--sv-border-subtle); background: var(--sv-surface-2);
}
.review-body { display: flex; flex: 1 1 auto; flex-direction: column; gap: 10px; min-height: 0; min-width: 0; }
.review-body > :not(.review-workspace) { flex-shrink: 0; }
.review-body > .el-alert { max-height: 72px; overflow: auto; padding: 8px 12px; }
.song-context { display: grid; grid-template-columns: 1.15fr 1fr; gap: 18px; padding: 10px 12px; border-radius: 9px; background: linear-gradient(120deg,var(--sv-accent-soft),var(--sv-surface-3)); max-height: 24vh; overflow: auto; }
.file-context { display: flex; gap: 10px; min-width: 0; }.file-context > div:last-child { min-width: 0; }
.file-context img,.cover-placeholder { width: 58px; height: 58px; flex-shrink: 0; border-radius: 8px; object-fit: cover; }
.cover-placeholder { display: grid; place-items: center; font-size: 26px; background: linear-gradient(140deg,var(--sv-accent-soft),var(--sv-accent-primary)); color: white; }
.section-caption { display: block; font-size: 11px; font-weight: 600; margin-bottom: 4px; }
.song-context h3 { font-size: 14px; margin: 0 0 3px; }.song-context p { color: var(--sv-text-secondary); font-size: 11px; margin: 3px 0; }
.file-badges { display: flex; gap: 5px; flex-wrap: wrap; margin: 4px 0; }.file-badges span { background: var(--sv-accent-soft); padding: 1px 5px; border-radius: 4px; font-size: 10px; color: var(--sv-text-secondary); }
.file-path { overflow-wrap: anywhere; }.existing-context dl { display: grid; grid-template-columns: 52px minmax(0,1fr) 52px minmax(0,1fr); gap: 6px; font-size: 11px; margin: 0; }.existing-context dt { color: var(--sv-text-tertiary); }.existing-context dd { margin: 0; overflow-wrap: anywhere; }
.stale-note { display: block; margin-top: 6px; color: var(--el-color-warning); font-size: 11px; }
.workspace-search { display: flex; gap: 10px; align-items: center; min-width: 0; }.workspace-search > .el-input { flex: 1; min-width: 100px; }
.platform-select { display: flex; flex-direction: column; font-size: 10px; color: var(--sv-text-secondary); max-width: 46%; }.platform-select > div { display: flex; flex-wrap: wrap; column-gap: 10px; }.platform-select :deep(.el-checkbox) { margin-right: 0; height: 24px; }.platform-select :deep(.el-checkbox__label) { font-size: 11px; padding-left: 4px; }.platform-select small { font-size: 10px; }
.platform-notice { display: flex; gap: 10px; flex-wrap: wrap; font-size: 11px; padding: 6px 10px; background: var(--sv-surface-3); border-radius: 6px; }.platform-notice a { color: var(--el-color-primary); }
.review-workspace { display: grid; grid-template-columns: minmax(0,.95fr) minmax(0,1.05fr); flex: 1 1 auto; min-height: 0; min-width: 0; gap: 16px; }
.candidate-pane,.preview-pane { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.pane-title { display: flex; flex-shrink: 0; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 6px; padding: 4px 0 8px; }
.pane-title h3 { font-size: 13px; margin: 0; }.pane-title > span,.comparison-title span { font-size: 11px; color: var(--sv-text-secondary); }.pane-title > div { display: flex; gap: 10px; flex-wrap: wrap; }.pane-title .el-button { font-size: 11px; margin: 0; }
.candidate-scroll,.preview-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; padding: 2px 4px 10px 2px; }
.candidate-scroll { display: flex; flex-direction: column; gap: 9px; }.review-candidate { flex-shrink: 0; display: flex; align-items: center; gap: 9px; padding: 12px 10px; border: 1px solid var(--sv-border-subtle); background: var(--sv-surface-2); border-radius: 9px; width: 100%; text-align: left; color: var(--sv-text-primary); cursor: pointer; }
.review-candidate.chosen { border-color: var(--el-color-primary); background: var(--sv-accent-soft); box-shadow: inset 0 0 0 1px var(--el-color-primary); }.review-candidate:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.candidate-radio { color: var(--el-color-primary); font-size: 18px; }.review-candidate img,.candidate-no-cover { width: 58px; height: 58px; object-fit: cover; border-radius: 7px; flex-shrink: 0; }.candidate-no-cover { display: grid; place-items: center; background: var(--sv-surface-3); font-size: 24px; color: var(--sv-text-tertiary); }
.candidate-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; overflow-wrap: anywhere; }.candidate-info strong { font-size: 13px; }.candidate-info > span,.candidate-info small { font-size: 11px; color: var(--sv-text-secondary); }.candidate-info .el-tag { align-self: flex-start; }
.candidate-score { text-align: right; color: var(--el-color-primary); flex-shrink: 0; }.candidate-score strong { font-size: 18px; }.candidate-score small { display: block; font-size: 10px; color: var(--sv-text-tertiary); margin-top: 4px; }
.selected-preview { display: flex; gap: 10px; padding: 12px; border: 1px solid var(--sv-border-subtle); border-radius: 9px; align-items: flex-start; margin: 0 0 12px; }.selected-preview > img { width: 68px; height: 68px; object-fit: cover; border-radius: 8px; }
.preview-copy { flex: 1; min-width: 0; overflow-wrap: anywhere; }.preview-copy h3 { font-size: 15px; margin: 0 0 5px; }.preview-copy p { margin: 3px 0; color: var(--sv-text-secondary); font-size: 11px; }.preview-score { color: var(--sv-semantic-ok); font-size: 21px; font-weight: 600; }.preview-score small { display: block; color: var(--sv-text-tertiary); font-size: 10px; text-align: right; }
.selection-hint,.overwrite-note,.workspace-hint { font-size: 11px; color: var(--sv-text-secondary); line-height: 1.7; margin: 0 0 8px; }.overwrite-note { margin: 10px 0 2px; }
.comparison-title { display: flex; gap: 10px; align-items: center; margin: 8px 0; font-size: 12px; }
.workspace-state { flex: 1 1 auto; min-height: 0; overflow: auto; text-align: center; display: flex; flex-direction: column; align-items: center; justify-content: safe center; border: 1px solid var(--sv-border-subtle); border-radius: 9px; padding: 14px; gap: 10px; }
.workspace-state h3 { font-size: 14px; }.workspace-state p { font-size: 12px; color: var(--sv-text-secondary); line-height: 1.7; margin: 0; }.workspace-state :deep(.el-empty) { padding: 8px; }.workspace-state .el-button + .el-button { margin-left: 0; }.empty-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }.search-pulse { font-size: 38px; color: var(--el-color-primary); }
.workspace-footer { display: flex; gap: 12px; align-items: center; justify-content: space-between; }.workspace-footer p { margin: 0; text-align: left; color: var(--sv-text-secondary); font-size: 11px; line-height: 1.7; max-width: 65%; }.workspace-footer > div { flex-shrink: 0; }.upload-note { display: block; color: var(--el-color-warning); margin-top: 5px; font-size: 11px; }
:global(.single-scrape-success.el-dialog) { width: min(780px,calc(100vw - 32px)); height: auto; }
:global(.single-scrape-success.el-dialog > .el-dialog__body) { overflow: auto; }
.review-success { width: 100%; max-width: 700px; margin: 0 auto; padding: 10px 6px; text-align: center; }.success-icon { color: var(--sv-semantic-ok); width: 42px; height: 42px; }.review-success h2 { font-size: 17px; margin: 10px; }.review-success p { font-size: 12px; color: var(--sv-text-secondary); margin: 10px 0; }.review-success table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; table-layout: fixed; }.review-success th,.review-success td { padding: 8px; border-bottom: 1px solid var(--sv-border-subtle); overflow-wrap: anywhere; }.result-cover { display: block; width: 64px; height: 64px; object-fit: cover; border-radius: 6px; margin-bottom: 4px; }
@media(max-width: 850px), (max-height: 560px) {
  :global(.single-scrape-dialog.el-dialog > .el-dialog__body) { overflow-y: auto; }
  .review-body { flex: 0 0 auto; }.review-workspace { flex: none; min-height: auto; }.candidate-scroll { max-height: 340px; flex: none; }.preview-scroll { flex: none; overflow: visible; }.workspace-state { flex: none; min-height: 180px; }.song-context { max-height: none; }.workspace-search { flex-wrap: wrap; }.platform-select { max-width: none; width: 100%; }
}
@media(max-width: 850px) {
  .review-workspace { grid-template-columns: minmax(0,1fr); }.song-context { grid-template-columns: minmax(0,1fr); gap: 8px; }.candidate-scroll { max-height: 260px; }.workspace-footer p { max-width: 100%; }.workspace-footer { flex-wrap: wrap; gap: 6px; }.workspace-footer > div { margin-left: auto; }
}
@media(max-width: 500px) {
  :global(.single-scrape-dialog.el-dialog > .el-dialog__header) { padding-left: 12px; }
  :global(.single-scrape-dialog.el-dialog > .el-dialog__body),:global(.single-scrape-dialog.el-dialog > .el-dialog__footer) { padding: 10px 12px; }
  .existing-context dl { grid-template-columns: 52px minmax(0,1fr); }.candidate-score strong { font-size: 15px; }.review-candidate { padding: 10px 8px; gap: 7px; }.review-candidate img,.candidate-no-cover { width: 44px; height: 44px; }.selected-preview > img,.selected-preview .cover-placeholder { width: 54px; height: 54px; }.preview-score { font-size: 17px; }
}
</style>
