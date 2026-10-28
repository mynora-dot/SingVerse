<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElDialog, ElEmpty, ElForm, ElInput, ElMessage, ElTag } from 'element-plus'
import { highSimilarityCandidate, hasCloseAlternative } from '../candidate-selection'
import { metadata } from '../api/metadata'
import { providerNames, fieldNames } from '../types'
import type { MetadataCandidate, MusicProvider } from '../types'

const props = defineProps<{ songId: string | null; title: string; artists: string[]; initialCandidates?: MetadataCandidate[]; batchId?: string; itemId?: string; autoSelectThreshold?: number }>()
const emit = defineEmits<{ close: []; applied: [] }>()
const keyword = ref(''), platforms = ref<MusicProvider[]>([]), available = ref<MusicProvider[]>([])
const candidates = ref<MetadataCandidate[]>([]), choice = ref(''), fields = ref(Object.keys(fieldNames))
const enabled = ref(false), loading = ref(false), applying = ref(false), error = ref(''), warnings = ref('')
const selected = computed(() => candidates.value.find(candidate => candidate.id === choice.value))
const selectionThreshold = ref(.95), autoSelected = ref(false)
function selectHigh() {
  const best = highSimilarityCandidate(candidates.value, selectionThreshold.value)
  if (best) { choice.value = best.id; autoSelected.value = true }
}
let generation = 0
watch(() => props.songId, async id => {
  const token = ++generation
  if (!id) return
  keyword.value = [props.title, ...props.artists].filter(Boolean).join(' ').slice(0, 200)
  autoSelected.value = false; error.value = ''; warnings.value = ''; choice.value = ''; candidates.value = props.initialCandidates ?? []; fields.value = Object.keys(fieldNames)
  loading.value = true
  try {
    const config = await metadata.settings()
    if (token !== generation) return
    selectionThreshold.value = props.autoSelectThreshold ?? config.threshold; selectHigh()
    enabled.value = config.enabled; available.value = config.providers; platforms.value = [...config.providers]
    if (config.enabled && !candidates.value.length) { await search(); return }
  } catch (failure) { if (token === generation) error.value = failure instanceof Error ? failure.message : '读取设置失败' }
  finally { if (token === generation) loading.value = false }
}, { immediate: true })
async function search() {
  if (!props.songId || applying.value || !keyword.value.trim()) return
  const token = ++generation
  loading.value = true; autoSelected.value = false; error.value = ''; warnings.value = ''; choice.value = ''; candidates.value = []
  try {
    const result = await metadata.search(props.songId, keyword.value, platforms.value)
    if (token !== generation) return
    candidates.value = result.candidates; selectHigh()
    warnings.value = result.errors.map(item => `${providerNames[item.provider]}：${item.message}`).join('；')
  } catch (failure) { if (token === generation) error.value = failure instanceof Error ? failure.message : '匹配失败' }
  finally { if (token === generation) loading.value = false }
}
async function apply() {
  if (!props.songId || !choice.value || !fields.value.length || applying.value) return
  applying.value = true; error.value = ''
  try {
    if (props.batchId && props.itemId) await metadata.applyItem(props.batchId, props.itemId, choice.value, fields.value)
    else await metadata.apply(props.songId, choice.value, fields.value)
    ElMessage.success('元数据已应用，原始文件未修改'); emit('applied'); emit('close')
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '应用失败' }
  finally { applying.value = false }
}
onBeforeUnmount(() => { generation++ })
function close() { if (!applying.value) { generation++; emit('close') } }
</script>

<template>
  <ElDialog :model-value="!!songId" title="元数据刮削 · 匹配与审核" width="min(980px, calc(100vw - 32px))" :close-on-click-modal="false" :show-close="!applying" :close-on-press-escape="!applying" @close="close">
    <p class="panel-description">搜索会将歌名和歌手发送至已启用平台，不上传媒体文件。选择候选并确认字段后才会写入；相似度不是正确率。</p>
    <ElAlert v-if="!enabled && !loading" title="尚未启用元数据平台，请先到元数据刮削页面配置。" type="info" :closable="false"><a href="#/library/songs/metadata">打开刮削设置 ↗</a></ElAlert>
    <ElForm class="match-search" @submit.prevent="search">
      <ElInput v-model="keyword" aria-label="刮削搜索关键词" placeholder="歌名 歌手，可修改关键词重新匹配" maxlength="200" :disabled="applying" />
      <ElButton type="primary" native-type="submit" :loading="loading" :disabled="!enabled || applying || !platforms.length">重新搜索</ElButton>
    </ElForm>
    <div class="platforms"><ElCheckbox v-for="platform in available" :key="platform" :model-value="platforms.includes(platform)" :disabled="loading || applying" @change="value => platforms = value ? [...platforms, platform] : platforms.filter(item => item !== platform)">{{ providerNames[platform] }}</ElCheckbox></div>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <ElAlert v-if="warnings" :title="warnings" type="warning" :closable="false" />
    <p v-if="loading" class="panel-description" role="status">正在查询平台，请稍候…</p>
    <ElEmpty v-else-if="enabled && !candidates.length && !error" description="没有找到候选，请调整关键词或更换平台。" :image-size="72" />
    <p v-if="autoSelected && selected" class="panel-description">已自动选中高相似度候选，尚未写入。{{ hasCloseAlternative(candidates, selected) ? '还有接近候选，请核对后确认应用。' : '请核对字段后确认应用。' }}</p>
    <div class="candidates">
      <label v-for="candidate in candidates" :key="candidate.id" class="candidate" :class="{ selected: choice === candidate.id }">
        <input v-model="choice" type="radio" name="metadata-candidate" @change="autoSelected = false" :value="candidate.id" :disabled="applying" :aria-label="`${candidate.track.title} · ${candidate.track.artists.join('、')} · ${providerNames[candidate.track.provider]}`" />
        <img v-if="candidate.track.coverUrl" :src="metadata.candidateCover(candidate.id)" alt="候选专辑封面" loading="lazy" @error="($event.target as HTMLImageElement).style.visibility = 'hidden'" />
        <div class="candidate-copy"><strong>{{ candidate.track.title }}</strong><p>{{ candidate.track.artists.join('、') || '未知歌手' }}</p><small>{{ candidate.track.album || '未知专辑' }} · {{ candidate.track.releaseDate || '发行日期未知' }}</small></div>
        <div class="candidate-score"><ElTag effect="plain">{{ providerNames[candidate.track.provider] }}</ElTag><small>相似度 {{ Math.round(candidate.score * 100) }}%</small></div>
      </label>
    </div>
    <div v-if="selected" class="apply-fields"><h3>选择应用字段</h3><p class="panel-description">只更新选中且候选有值的字段，其余已确认内容保留；重新扫描不会覆盖已应用字段。</p><div><ElCheckbox v-for="(label, field) in fieldNames" :key="field" :model-value="fields.includes(field)" :disabled="applying" @change="value => fields = value ? [...fields, field] : fields.filter(item => item !== field)">{{ label }}</ElCheckbox></div></div>
    <template #footer><ElButton :disabled="applying" @click="close">关闭</ElButton><ElButton type="primary" :loading="applying" :disabled="!selected || !fields.length || loading || !enabled" @click="apply">确认应用元数据</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.match-search { display: flex; gap: 10px; margin: 20px 0 12px; }
.platforms { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.platforms .el-checkbox { margin-right: 0; }
.candidates { display: grid; gap: 10px; max-height: 390px; overflow: auto; margin-top: 16px; }
.candidate { display: flex; align-items: center; gap: 14px; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 10px; cursor: pointer; }
.candidate.selected { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.candidate img { width: 56px; height: 56px; object-fit: cover; border-radius: 8px; }
.candidate-copy { flex: 1; min-width: 0; }
.candidate-copy strong { font-size: 14px; }
.candidate-copy p, .candidate-copy small, .candidate-score small { display: block; font-size: 12px; line-height: 1.8; color: var(--el-text-color-secondary); margin-top: 4px; overflow-wrap: anywhere; }
.candidate-score { text-align: right; }
.apply-fields { border-top: 1px solid var(--el-border-color-lighter); margin-top: 20px; padding-top: 18px; }
.apply-fields h3 { font-size: 14px; }
@media (max-width: 600px) { .candidate { flex-wrap: wrap; gap: 10px; } .candidate-score { width: 100%; text-align: left; margin-left: 28px; } }
</style>
