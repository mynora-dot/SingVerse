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
  <ElDialog :model-value="!!songId" title="元数据刮削 · 匹配与审核" width="min(1240px, calc(100vw - 32px))" top="4vh" class="batch-review-dialog" :close-on-click-modal="false" :show-close="!applying" :close-on-press-escape="!applying" @close="close">
    <div class="review-intro"><span class="section-caption">当前歌曲 · 批次审核</span><h3>{{ title }}</h3><p>{{ artists.join('、') || '未知歌手' }}</p><small>搜索仅发送歌名和歌手，不上传媒体文件。候选及字段需要确认后才会写入；相似度不是正确率。</small></div>
    <ElAlert v-if="!enabled && !loading" title="尚未启用元数据平台，请先到元数据刮削页面配置。" type="info" :closable="false"><a href="#/library/songs/metadata">打开刮削设置 ↗</a></ElAlert>
    <ElForm class="match-search" @submit.prevent="search">
      <ElInput v-model="keyword" aria-label="刮削搜索关键词" placeholder="歌名 歌手，可修改关键词重新匹配" maxlength="200" :disabled="applying" />
      <ElButton type="primary" native-type="submit" :loading="loading" :disabled="!enabled || applying || !platforms.length">重新搜索</ElButton>
    </ElForm>
    <div class="platforms"><span>查询平台</span><ElCheckbox v-for="platform in available" :key="platform" :model-value="platforms.includes(platform)" :disabled="loading || applying" @change="value => platforms = value ? [...platforms, platform] : platforms.filter(item => item !== platform)">{{ providerNames[platform] }}</ElCheckbox></div>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <ElAlert v-if="warnings" :title="warnings" type="warning" :closable="false" />
    <p v-if="loading" class="panel-description" role="status">正在查询平台，请稍候…</p>
    <ElEmpty v-else-if="enabled && !candidates.length && !error" description="没有找到候选，请调整关键词或更换平台。" :image-size="72" />
    <div class="review-workspace"><section class="candidate-pane" aria-label="歌曲候选"><div class="pane-title"><h3>匹配候选</h3><span>{{ loading ? '查询中' : `${candidates.length} 条结果` }}</span></div><div class="candidates">
      <label v-for="candidate in candidates" :key="candidate.id" class="candidate" :class="{ selected: choice === candidate.id }">
        <input v-model="choice" type="radio" name="metadata-candidate" @change="autoSelected = false" :value="candidate.id" :disabled="applying" :aria-label="`${candidate.track.title} · ${candidate.track.artists.join('、')} · ${providerNames[candidate.track.provider]}`" />
        <img v-if="candidate.track.coverUrl" :src="metadata.candidateCover(candidate.id)" alt="候选专辑封面" loading="lazy" @error="($event.target as HTMLImageElement).style.visibility = 'hidden'" /><span v-else class="cover-placeholder">♫</span>
        <div class="candidate-copy"><strong>{{ candidate.track.title }}</strong><p>{{ candidate.track.artists.join('、') || '未知歌手' }}</p><small>{{ candidate.track.album || '未知专辑' }} · {{ candidate.track.releaseDate || '发行日期未知' }}</small></div>
        <div class="candidate-score"><ElTag effect="plain">{{ providerNames[candidate.track.provider] }}</ElTag><small>相似度 {{ Math.round(candidate.score * 100) }}%</small></div>
      </label>
    </div></section><section class="preview-pane" aria-label="元数据预览与审核"><div class="pane-title"><h3>{{ selected ? '选中候选与应用预览' : '选择候选以预览元数据' }}</h3></div><div class="preview-scroll"><p v-if="autoSelected && selected" class="panel-description">已自动选中高相似度候选，尚未写入。{{ hasCloseAlternative(candidates, selected) ? '还有接近候选，请核对后确认应用。' : '请核对字段后确认应用。' }}</p><template v-if="selected"><div class="selected-preview"><img v-if="selected.track.coverUrl" :src="metadata.candidateCover(selected.id)" alt="候选封面" /><span v-else class="cover-placeholder">♫</span><div><h3>{{ selected.track.title }}</h3><p>{{ selected.track.artists.join('、') || '未知歌手' }}</p><p>{{ selected.track.album || '未知专辑' }} · {{ selected.track.releaseDate || '日期未知' }}</p><ElTag size="small">{{ providerNames[selected.track.provider] }}</ElTag></div><strong>{{ Math.round(selected.score * 100) }}%<small>相似度</small></strong></div><div class="apply-fields"><h3>将应用的字段</h3><p class="panel-description">只更新选中且候选有值的字段，其余已确认内容保留；重新扫描不会覆盖已应用字段。</p><div><label v-for="(label, field) in fieldNames" :key="field" class="field-row"><ElCheckbox :model-value="fields.includes(field)" :disabled="applying" @change="value => fields = value ? [...fields, field] : fields.filter(item => item !== field)">{{ label }}</ElCheckbox><span>{{ field === 'cover' ? (selected.track.coverUrl ? '候选封面' : '—') : Array.isArray(selected.track[field as keyof typeof selected.track]) ? (selected.track[field as keyof typeof selected.track] as string[]).join('、') || '—' : selected.track[field as keyof typeof selected.track] || '—' }}</span></label></div></div></template><p v-else class="panel-description">从左侧选择候选后，可在这里核对封面、字段及要应用的内容。</p></div></section></div>
    <template #footer><ElButton :disabled="applying" @click="close">关闭</ElButton><ElButton type="primary" :loading="applying" :disabled="!selected || !fields.length || loading || !enabled" @click="apply">确认应用元数据</ElButton></template>
  </ElDialog>
</template>

<style scoped>
:global(.batch-review-dialog.el-dialog){display:flex;flex-direction:column;height:92dvh;max-height:92vh;margin-bottom:0;overflow:hidden;padding:0}
:global(.batch-review-dialog>.el-dialog__header){flex:none;margin:0;padding:16px 24px;border-bottom:1px solid var(--sv-border-subtle)}
:global(.batch-review-dialog>.el-dialog__body){display:flex;flex:1;min-height:0;flex-direction:column;padding:16px 20px;overflow:hidden}
:global(.batch-review-dialog>.el-dialog__footer){flex:none;padding:12px 20px;border-top:1px solid var(--sv-border-subtle)}
.review-intro{flex:none;padding:12px 16px;background:var(--sv-accent-soft);border-radius:9px}.review-intro h3{font-size:16px;margin:3px 0}.review-intro p{margin:2px 0 6px}.review-intro small,.section-caption{font-size:11px;color:var(--sv-text-secondary)}
.match-search{display:flex;gap:10px;margin:14px 0 8px}.match-search :deep(.el-input){flex:1}.platforms{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:10px;font-size:12px}.platforms>span{color:var(--sv-text-secondary)}.platforms .el-checkbox{margin-right:0}
.review-workspace{display:grid;grid-template-columns:minmax(0,.95fr) minmax(0,1.05fr);gap:16px;flex:1;min-height:0}.candidate-pane,.preview-pane{display:flex;flex-direction:column;min-height:0;min-width:0}.pane-title{display:flex;justify-content:space-between;align-items:center;min-height:35px}.pane-title h3{font-size:13px;margin:0}.pane-title span{font-size:11px;color:var(--sv-text-secondary)}
.candidates,.preview-scroll{overflow:auto;min-height:0;scrollbar-gutter:stable}.candidates{display:flex;flex-direction:column;gap:9px;padding:2px}.candidate{display:flex;align-items:center;gap:10px;padding:12px;border:1px solid var(--sv-border-subtle);border-radius:9px;cursor:pointer}.candidate.selected{border-color:var(--el-color-primary);background:var(--sv-accent-soft);box-shadow:inset 0 0 0 1px var(--el-color-primary)}.candidate img,.cover-placeholder{width:58px;height:58px;flex:none;border-radius:7px;object-fit:cover}.cover-placeholder{display:grid;place-items:center;background:var(--sv-surface-3);font-size:24px;color:var(--sv-text-tertiary)}.candidate-copy{flex:1;min-width:0}.candidate-copy strong{font-size:13px}.candidate-copy p,.candidate-copy small,.candidate-score small{display:block;font-size:11px;line-height:1.6;color:var(--sv-text-secondary);margin:4px 0 0;overflow-wrap:anywhere}.candidate-score{text-align:right;flex:none}
.preview-scroll{padding:2px}.selected-preview{display:flex;gap:12px;align-items:flex-start;padding:12px;border:1px solid var(--sv-border-subtle);border-radius:9px}.selected-preview img,.selected-preview .cover-placeholder{width:68px;height:68px}.selected-preview>div{flex:1;min-width:0}.selected-preview h3{font-size:15px;margin:0 0 6px}.selected-preview p{font-size:11px;color:var(--sv-text-secondary);margin:4px 0;overflow-wrap:anywhere}.selected-preview>strong{color:var(--sv-semantic-ok);font-size:20px}.selected-preview>strong small{display:block;color:var(--sv-text-secondary);font-size:10px;text-align:right}.apply-fields{margin-top:16px}.apply-fields h3{font-size:13px}.panel-description{font-size:11px;line-height:1.7;color:var(--sv-text-secondary)}.field-row{display:grid;grid-template-columns:120px minmax(0,1fr);gap:10px;align-items:center;padding:8px;border-bottom:1px solid var(--sv-border-subtle);font-size:12px;overflow-wrap:anywhere}.field-row span{color:var(--sv-text-secondary)}
@media(max-width:850px){:global(.batch-review-dialog>.el-dialog__body){overflow-y:auto}.review-workspace{display:block;flex:none}.candidates{max-height:300px}.preview-scroll{overflow:visible}}
@media(max-width:500px){.candidate{gap:7px;padding:9px}.candidate img,.cover-placeholder{width:44px;height:44px}.candidate-score{max-width:75px}.field-row{grid-template-columns:100px minmax(0,1fr)}}
</style>
