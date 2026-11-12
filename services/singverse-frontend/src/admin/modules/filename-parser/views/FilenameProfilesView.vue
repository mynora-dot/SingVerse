<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElSwitch, ElTag } from 'element-plus'
import { ApiError } from '../../../core/auth/api'
import { parserProfiles } from '../api/rules'
import type { ParserProfile, ParserWorkspace, ProfileInput, RulePreview } from '../types'
import type { FormatItem } from '../utils'
import { attemptFor, candidateEntries, copyValue, errorMessage, expressionFor, formatItems } from '../utils'
import RulePreviewPanel from '../components/RulePreviewPanel.vue'

type GalleryItem = FormatItem & { attempt?: RulePreview['attempts'][number] }

const props = defineProps<{ embedded?: boolean; profileId?: string }>()
const emit = defineEmits<{ useProfile: [id: string]; rules: [profileId: string] }>()
const route = useRoute()
const router = useRouter()
const workspace = ref<ParserWorkspace | null>(null)
const selectedId = ref<string | null>('default')
const draft = ref<ProfileInput>({ name: '', entries: [] })
const baseline = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const conflict = ref(false)
const memberError = ref<{ ruleId?: string | null; ruleIndex?: number; field?: string; message: string } | null>(null)
const addId = ref('')
const filename = ref('晴天-周杰伦.mkv')
const parsing = ref(false)
const parseError = ref('')
const preview = ref<RulePreview | null>(null)
const stale = ref(false)
const previewName = ref('')
const detection = ref<RulePreview | null>(null)
const detecting = ref(false)
const detectError = ref('')
const detectionStale = ref(false)
const signature = computed(() => JSON.stringify(draft.value))
const dirty = computed(() => workspace.value !== null && signature.value !== baseline.value)
const editable = computed(() => !!workspace.value && !busy.value && !loading.value)
const available = computed(() => workspace.value?.definitions.filter(d => d.type !== 'BUILTIN' && !draft.value.entries.some(e => e.ruleId === d.id)) ?? [])
const enabledEntries = computed(() => draft.value.entries.filter(entry => entry.enabled).length)
const formatRanks: Record<string, number> = { MATCHED: 0, PARTIAL: 1, AMBIGUOUS: 2, NO_MATCH: 3, DISABLED: 4 }
const gallery = computed(() => formatItems(workspace.value).map(item => ({ ...item, attempt: attemptFor(detection.value, item.id) })).sort((a, b) => {
  if (!detection.value) return 0
  return (formatRanks[a.attempt?.status ?? ''] ?? 9) - (formatRanks[b.attempt?.status ?? ''] ?? 9)
}))
const usableFormats = computed(() => gallery.value.filter(item => item.attempt?.status === 'MATCHED').length)
const formatsHint = computed(() => {
  if (detection.value && usableFormats.value) return `找到 ${usableFormats.value} 种能识别的格式，点一下加进方案；再点一次移出。`
  return '点一下就能加进方案；先做第 1 步检测，能识别的会排在前面。'
})
const saveHint = computed(() => draft.value.entries.length
  ? `已选 ${draft.value.entries.length} 种格式，${enabledEntries.value} 种启用、${draft.value.entries.length - enabledEntries.value} 种停用。修改后保存方案生效。`
  : '还没有选格式；点上面第 2 步的卡片，或在第 4 步手动添加。')
let generation = 0
let detectGeneration = 0
let active = true

function definition(id: string) { return workspace.value?.definitions.find(d => d.id === id) }
function ruleName(id: string) { return definition(id)?.name ?? `失效引用：${id}` }
function invalidate() { generation++; if (preview.value) stale.value = true; parseError.value = '' }
function invalidateDetection() { detectGeneration++; if (detection.value) detectionStale.value = true }
watch(draft, () => { invalidate(); memberError.value = null }, { deep: true, flush: 'sync' })
watch(filename, () => { invalidate(); invalidateDetection() }, { flush: 'sync' })

function formatFlag(item: GalleryItem) {
  if (applied(item)) return '已在方案里'
  if (!detection.value || !item.attempt) return ''
  const status = item.attempt.status
  return status === 'MATCHED' ? '能用' : status === 'PARTIAL' ? '只认出一半' : status === 'AMBIGUOUS' ? '有两种读法' : '识别不了'
}
function applied(item: GalleryItem) { return draft.value.entries.some(entry => entry.ruleId === item.id) }
function formatResult(item: GalleryItem) {
  const captures = item.attempt?.captures
  if (!captures?.artist || !captures.title) return ''
  return `识别为：歌手 ${captures.artist} · 歌名 ${captures.title}`
}
function applyFormat(item: GalleryItem) {
  if (!editable.value) return
  const index = draft.value.entries.findIndex(entry => entry.ruleId === item.id)
  if (index >= 0) { draft.value.entries.splice(index, 1); ElMessage.info(`已移出「${item.name}」，点“保存方案”生效。`); return }
  if (draft.value.entries.length >= 100) return
  draft.value.entries.push({ ruleId: item.id, enabled: true })
  ElMessage.success(`已加入「${item.name}」，点“保存方案”生效。`)
}

function select(profile: ParserProfile) {
  selectedId.value = profile.id
  draft.value = copyValue({ name: profile.name, entries: profile.entries })
  baseline.value = signature.value
  addId.value = ''
  error.value = ''; memberError.value = null
  preview.value = null; stale.value = false
}
async function discard(): Promise<boolean> {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('当前方案有未保存修改，是否放弃这些修改？', '未保存草稿', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' })
    return true
  } catch { return false }
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) { event.preventDefault(); event.returnValue = '' }
}
async function canLeave() { return !busy.value && await discard() }
defineExpose({ canLeave })
onBeforeRouteLeave(() => props.embedded || canLeave())
onBeforeRouteUpdate(async (to, from) => {
  if (props.embedded) return true
  if (to.query.profile !== from.query.profile) return !dirty.value || (!busy.value && await discard())
  return true
})
watch(() => route.query.profile, (id) => {
  if (props.embedded) return
  if (!workspace.value || typeof id !== 'string') return
  const profile = workspace.value.profiles.find(p => p.id === id)
  if (profile) select(profile)
  else error.value = '指定方案不存在，请选择其他方案或加载最新配置。'
})
onBeforeUnmount(() => { active = false; generation++; window.removeEventListener('beforeunload', beforeUnload) })

async function load(preserve = false) {
  loading.value = true; error.value = ''
  invalidate()
  try {
    const response = await parserProfiles.load()
    if (!active) return
    workspace.value = response; conflict.value = false
    if (preserve) {
      const saved = response.profiles.find(p => p.id === selectedId.value)
      if (saved) baseline.value = JSON.stringify({ name: saved.name, entries: saved.entries })
      else { selectedId.value = null; baseline.value = ''; }
      ElMessage.info('已加载最新规则库和修订号，当前草稿已保留，请核对后预览或保存。')
    } else {
      const requested = props.embedded ? props.profileId : typeof route.query.profile === 'string' ? route.query.profile : selectedId.value
      const found = response.profiles.find(p => p.id === requested)
      const fallback = response.profiles.find(p => p.id === 'default') ?? response.profiles[0]
      if (found || fallback) select((found ?? fallback)!)
      if (requested && !found) error.value = '指定方案不存在，已显示默认方案。'
    }
  } catch (failure) { if (active) error.value = errorMessage(failure) }
  finally { if (active) loading.value = false }
}
async function choose(id: string) {
  if (!editable.value || id === selectedId.value) return
  if (props.embedded || route.query.profile === id) {
    if (await discard()) {
      const saved = workspace.value?.profiles.find(p => p.id === id)
      if (saved) select(saved)
    }
  } else await router.replace({ query: { ...route.query, profile: id } })
}
async function create(copy = false) {
  if (!editable.value || (!copy && !await discard())) return
  const current = copyValue(draft.value)
  selectedId.value = null
  draft.value = copy ? { name: `${current.name}（副本）`.slice(0, 100), entries: current.entries } : { name: '', entries: [] }
  baseline.value = ''; addId.value = ''; error.value = ''; preview.value = null
}
function add(ruleId = addId.value) {
  if (!editable.value || !ruleId || draft.value.entries.some(e => e.ruleId === ruleId)) return
  draft.value.entries.push({ ruleId, enabled: true }); addId.value = ''
}
function move(index: number, step: number) {
  if (!editable.value) return
  const target = index + step
  if (target < 0 || target >= draft.value.entries.length) return
  const entries = [...draft.value.entries]
  ;[entries[index], entries[target]] = [entries[target]!, entries[index]!]
  draft.value.entries = entries
}
function handleFailure(failure: unknown): string {
  if (failure instanceof ApiError) {
    conflict.value = failure.status === 409
    if (failure.status === 400) memberError.value = { ...failure.details, message: failure.message }
  }
  return errorMessage(failure)
}
async function save() {
  if (!workspace.value || !editable.value || conflict.value || !dirty.value) return
  if (!draft.value.name.trim()) { error.value = '请填写方案名称。'; return }
  busy.value = true; error.value = ''
  try {
    const input = copyValue(draft.value); input.name = input.name.trim()
    const response = selectedId.value ? await parserProfiles.update(selectedId.value, workspace.value.revision, input) : await parserProfiles.create(workspace.value.revision, input)
    if (!active) return
    workspace.value = response
    const saved = response.profiles.find(p => p.id === response.changedId)!
    select(saved)
    if (!props.embedded) await router.replace({ query: { ...route.query, profile: saved.id } })
    ElMessage.success('解析方案已保存。')
  } catch (failure) { if (active) error.value = handleFailure(failure) }
  finally { if (active) busy.value = false }
}
async function remove() {
  if (!workspace.value || !selectedId.value || selectedId.value === 'default' || !editable.value || !await discard()) return
  busy.value = true; error.value = ''
  try {
    const response = await parserProfiles.remove(selectedId.value, workspace.value.revision)
    if (!active) return
    workspace.value = response
    select(response.profiles.find(p => p.id === 'default')!)
    if (!props.embedded) await router.replace({ query: { ...route.query, profile: 'default' } })
  } catch (failure) { if (active) error.value = handleFailure(failure) }
  finally { if (active) busy.value = false }
}
async function detect() {
  if (!workspace.value || detecting.value || !editable.value || !filename.value.trim()) return
  const token = detectGeneration
  detecting.value = true; detectError.value = ''; detection.value = null; detectionStale.value = false
  try {
    const response = await parserProfiles.preview(filename.value, workspace.value.revision, candidateEntries(workspace.value))
    if (!active || token !== detectGeneration) return
    detection.value = response.preview
  } catch (failure) { if (active && token === detectGeneration) detectError.value = handleFailure(failure) }
  finally { if (active) detecting.value = false }
}
async function parse() {
  if (!workspace.value || parsing.value || !editable.value) return
  const token = generation
  const name = draft.value.name
  parsing.value = true; parseError.value = ''; memberError.value = null
  try {
    const response = await parserProfiles.preview(filename.value, workspace.value.revision, copyValue(draft.value.entries))
    if (!active || token !== generation) return
    preview.value = response.preview; stale.value = false; previewName.value = name || '未命名草稿'
  } catch (failure) { if (active && token === generation) parseError.value = handleFailure(failure) }
  finally { if (active) parsing.value = false }
}
function rowError(id: string, index: number) {
  return memberError.value && (memberError.value.ruleId === id || memberError.value.ruleIndex === index) ? memberError.value.message : ''
}
async function openRules() {
  if (props.embedded && await canLeave()) emit('rules', selectedId.value ?? props.profileId ?? 'default')
}
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
</script>

<template>
  <section class="profiles-page management-page" :class="{ 'profiles-embedded': embedded }">
    <template v-if="!embedded">
      <PageHeader title="文件名识别" description="填一个真实的文件名，挑出能识别的格式并保存，再为歌曲来源选择方案。扫描时用它提取歌名和歌手。" />
    </template>
    <p v-else class="embedded-profile-intro">先试一个文件名，再选择适合的格式；也可以复制方案单独使用。</p>
    <details class="profile-sharing-note"><summary>共享方案与修改范围</summary><p>同一方案可供多个来源使用，保存修改会影响它们之后的扫描。只想调整一个来源时，请先复制方案。已有歌曲不会在这里被重新扫描。</p></details>
    <div v-if="error || conflict || parseError" class="page-feedback"><ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" /><ElButton :disabled="loading || busy" @click="load(!!workspace)">{{ workspace ? '加载最新配置，保留草稿' : '重试加载' }}</ElButton></div>
    <p v-if="loading" class="page-status" role="status">正在读取解析方案…</p>
    <template v-if="workspace">
      <div class="profiles-layout">
        <ElCard shadow="never" class="profiles-rail">
          <div class="rail-head"><h2>我的方案</h2><span class="count-badge">{{ workspace.profiles.length }}</span></div>
          <div class="rail-actions"><ElButton size="small" :disabled="!editable || workspace.profiles.length >= 100" @click="create()">新建方案</ElButton><ElButton size="small" :disabled="!editable || workspace.profiles.length >= 100" @click="create(true)">复制当前方案</ElButton></div>
          <div class="rail-list"><button v-for="profile in workspace.profiles" :key="profile.id" class="profile-choice" :class="{ selected: profile.id === selectedId }" :aria-current="profile.id === selectedId ? 'true' : undefined" :disabled="!editable" @click="choose(profile.id)"><strong>{{ profile.name }} <em v-if="profile.id === 'default'" class="choice-flag">默认</em></strong><small>{{ profile.entries.length }} 种格式</small></button></div>
        </ElCard>
        <div class="profiles-main">
          <ElCard shadow="never" class="profile-detect">
            <div class="step-head"><span class="step-no">1</span><div><h2>你的文件名长这样？</h2><p class="muted">填一个真实的音乐文件名，我们帮你找出能识别的格式。</p></div></div>
            <ElForm class="preview-form" label-position="top" @submit.prevent="detect"><ElFormItem label="媒体文件名"><ElInput v-model="filename" maxlength="1024" aria-label="媒体文件名" placeholder="例如 周杰伦-晴天.mkv" /></ElFormItem><ElButton type="primary" :loading="detecting" :disabled="!editable || !filename.trim()" @click="detect">找出能识别的格式</ElButton></ElForm>
            <p v-if="detecting" class="page-status" role="status">正在检查…</p>
            <ElAlert v-if="detectError" :title="detectError" type="error" :closable="false" role="alert" />
            <ElAlert v-if="detectionStale" title="文件名改了，以下为旧结果，重新点一下就好。" type="warning" :closable="false" role="status" />
            <p v-if="!detection && !detecting && !detectError" class="muted detect-hint">填好文件名点一下，能识别的格式会亮起来。</p>
          </ElCard>
          <ElCard shadow="never" class="profile-formats">
            <div class="step-head"><span class="step-no">2</span><div><h2>常见命名格式</h2><p class="muted">{{ formatsHint }}</p></div></div>
            <div class="format-gallery"><button v-for="item in gallery" :key="item.id" class="format-choice" :class="{ applied: applied(item), usable: item.attempt?.status === 'MATCHED', dim: !!detection && !detectionStale && !!item.attempt && item.attempt.status !== 'MATCHED' }" :aria-current="applied(item) ? 'true' : undefined" :aria-label="`使用格式：${item.name}`" :disabled="!editable || (!applied(item) && draft.entries.length >= 100)" @click="applyFormat(item)"><strong>{{ item.name }}</strong><em v-if="formatFlag(item)" class="format-flag">{{ formatFlag(item) }}</em><small class="format-example">{{ item.example }}</small><small v-if="formatResult(item)" class="format-result">{{ formatResult(item) }}</small></button></div>
            <p v-if="detection && !detectionStale && !usableFormats" class="muted detect-none">没找到能识别的格式，换一个文件名再试；也可以在下面第 4 步里手动添加。</p>
          </ElCard>
          <ElCard shadow="never" class="profile-draft">
            <div class="step-head"><span class="step-no">3</span><div><h2>方案与保存</h2><p class="muted">{{ saveHint }}</p></div></div>
            <div class="editor-head">
              <div class="editor-id"><label class="name-label" for="parser-profile-name">方案名称</label><ElInput id="parser-profile-name" v-model="draft.name" class="name-field" maxlength="100" placeholder="未命名方案" aria-label="方案名称" :disabled="!editable" @keyup.enter="save" /></div>
              <div class="editor-actions"><ElTag :type="dirty ? 'warning' : 'success'" size="small" effect="light" round>{{ dirty ? '未保存草稿' : '已保存方案' }}</ElTag><ElButton type="primary" :loading="busy" :disabled="!editable || !dirty || conflict" @click="save">保存方案</ElButton><ElButton v-if="selectedId && selectedId !== 'default'" type="danger" plain :disabled="!editable" @click="remove">删除方案</ElButton></div>
              <p class="editor-meta">{{ selectedId ? '编辑已保存方案' : '新方案草稿' }}</p>
              <p v-if="selectedId === 'default'" class="muted editor-note">默认方案供旧的歌曲导入流程使用，可以改名或清空，但不能删除。</p>
            </div>
          </ElCard>
          <ElCard shadow="never" class="profile-editor">
            <details class="rules-help profiles-advanced">
              <summary>4．高级配置：顺序、启停与自定义格式</summary>
              <div class="members-head"><h3>执行顺序 <span class="count-badge">{{ draft.entries.length }}</span></h3><ElButton v-if="embedded" text :disabled="!editable" @click="openRules">自定义规则</ElButton><RouterLink v-else class="library-link" :to="{ path: '/library/media/recognition/rules', query: { ...route.query, profile: selectedId ?? undefined } }">自定义规则 →</RouterLink></div>
              <p class="muted preview-hint">按顺序执行，第一条完整命中的格式生效。</p>
              <ol class="entries"><li v-for="(entry, index) in draft.entries" :key="entry.ruleId" class="entry" :class="{ 'entry-disabled': !entry.enabled }"><span class="rule-order">{{ String(index + 1).padStart(2, '0') }}</span><div class="entry-body"><div class="entry-title"><strong>{{ ruleName(entry.ruleId) }}</strong><span v-if="!entry.enabled" class="entry-flag">已停用</span></div><code>{{ expressionFor(definition(entry.ruleId), workspace) }}</code><p v-if="!definition(entry.ruleId)" class="inline-error entry-note">引用已失效，请加载最新配置后明确移除此项或选择其他格式。</p><p v-if="rowError(entry.ruleId, index)" class="inline-error entry-note" role="alert">{{ rowError(entry.ruleId, index) }}</p></div><div class="entry-controls"><ElSwitch v-model="entry.enabled" :aria-label="`启用 ${ruleName(entry.ruleId)}`" :disabled="!editable" size="small" /><ElButton size="small" :disabled="!editable || index === 0" :aria-label="`上移 ${ruleName(entry.ruleId)}`" @click="move(index, -1)">上移</ElButton><ElButton size="small" :disabled="!editable || index === draft.entries.length - 1" :aria-label="`下移 ${ruleName(entry.ruleId)}`" @click="move(index, 1)">下移</ElButton><ElButton size="small" class="remove-btn" :disabled="!editable" :aria-label="`移除 ${ruleName(entry.ruleId)}`" @click="draft.entries.splice(index, 1)">移除</ElButton></div></li></ol>
              <p v-if="!draft.entries.length" class="muted entries-empty">方案里还没有成员；空方案不会调用其他解析器兜底。</p>
              <div class="add-rule"><select v-model="addId" class="native-field" aria-label="添加已有规则" :disabled="!editable || draft.entries.length >= 100"><option value="">选择自定义格式</option><option v-for="rule in available" :key="rule.id" :value="rule.id">{{ rule.name }}</option></select><ElButton :disabled="!editable || !addId || draft.entries.length >= 100" @click="add()">添加到方案</ElButton></div>
              <div class="preview-head"><h3>草稿预览</h3><span v-if="preview" class="preview-scope">预览方案：{{ previewName }}</span></div>
              <p class="muted preview-hint">用当前顺序和最新定义试解析，不保存方案、不扫描或导入歌曲；保存方案不要求测试文本命中。</p>
              <ElButton :loading="parsing" :disabled="!editable || !filename.trim() || conflict" @click="parse">解析当前方案</ElButton>
              <ElAlert v-if="parseError" :title="parseError" type="error" :closable="false" role="alert" />
              <ElAlert v-if="stale" title="输入或方案成员已修改，以下为旧预览，请重新解析。" type="warning" :closable="false" role="status" />
              <RulePreviewPanel :preview="preview" :stale="stale" />
              <div v-if="!preview && !parsing && !parseError" class="preview-placeholder">解析当前方案可查看逐项匹配详情。</div>
            </details>
          </ElCard>
        </div>
      </div>
    </template>
    <div v-if="embedded && workspace" class="embedded-profile-confirm"><p>{{ dirty ? '方案还有未保存修改，请先保存。' : `待使用：${draft.name}` }}</p><ElButton type="primary" :disabled="!editable || dirty || conflict || !selectedId" @click="selectedId && emit('useProfile', selectedId)">使用此方案并返回</ElButton></div>
  </section>
</template>

<style scoped>
.embedded-profile-intro { margin-bottom: 16px; color: var(--sv-text-secondary); font-size: 13px; line-height: 1.8; }
.profile-sharing-note { margin: 0 0 24px; padding: 16px 20px; background: var(--sv-surface-3); border-radius: var(--sv-radius-sm); color: var(--sv-text-secondary); font-size: 12px; line-height: 1.9; }
.profile-sharing-note summary { cursor: pointer; font-weight: 600; }
.profile-sharing-note[open] summary { margin-bottom: 12px; }
.embedded-profile-confirm { position: sticky; bottom: -1px; z-index: 3; display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 20px; padding: 16px 0; background: var(--sv-surface-1); border-top: 1px solid var(--sv-border-subtle); }
.embedded-profile-confirm p { flex: 1; min-width: 140px; font-size: 12px; color: var(--sv-text-secondary); overflow-wrap: anywhere; }
.profiles-page > .page-feedback,
.profiles-page > .page-status {
  margin-bottom: var(--sv-space-5);
}
.page-feedback {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sv-space-3);
  align-items: center;
}
.page-feedback .el-alert {
  flex: 1;
  min-width: 240px;
}
.page-status {
  color: var(--sv-text-tertiary);
  font-size: var(--sv-font-meta);
}
.editor-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px 24px;
  align-items: end;
}
.editor-id { min-width: 0; }
.name-label { display: block; margin-bottom: 12px; font-size: 13px; font-weight: 600; }
.editor-id .name-field { margin-top: 0; }
.name-field :deep(.el-input__wrapper) { min-height: 40px; background: var(--sv-surface-1); }
.name-field :deep(.el-input__inner) { font-size: 14px; font-weight: 500; }
.editor-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.editor-actions :deep(.el-button) { margin-left: 0; }
.editor-meta { grid-column: 1 / -1; font-size: 12px; color: var(--sv-text-secondary); }
.editor-note { grid-column: 1 / -1; margin-top: -8px; font-size: 12px; line-height: 1.8; }
.profiles-advanced { padding: 0; background: transparent; }
.profiles-advanced > summary { font-size: 14px; line-height: 1.8; overflow-wrap: anywhere; }
.members-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin: 24px 0 12px; }
.members-head h3 { display: flex; align-items: center; gap: 12px; font-size: 15px; }
.library-link { font-size: 12px; }
.preview-hint { margin: 0 0 20px; font-size: 12px; line-height: 1.8; }
.entry-controls { flex-wrap: nowrap; gap: 8px; }
.entry-controls :deep(.el-switch) { margin-right: 8px; }
.entry-controls :deep(.el-button) { border-color: var(--sv-border-subtle); background: var(--sv-surface-1); min-height: 32px; }
.add-rule { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; margin: 20px 0 0; padding: 16px; background: var(--sv-surface-3); border-radius: var(--sv-radius-md); }
.add-rule .native-field { min-width: 0; width: 100%; }
.preview-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin: 28px 0 12px; padding-top: 24px; border-top: 1px solid var(--sv-border-subtle); }
.preview-head h3 { font-size: 15px; }
.preview-scope { font-size: 12px; color: var(--sv-text-secondary); overflow-wrap: anywhere; }
.preview-placeholder { margin-top: 16px; padding: 24px 16px; text-align: center; border: 1px dashed var(--sv-border-default); border-radius: var(--sv-radius-md); color: var(--sv-text-secondary); font-size: 12px; }
@media (max-width: 1200px) {
  .editor-head { grid-template-columns: minmax(0, 1fr); align-items: start; }
  .editor-actions { justify-content: flex-start; }
  .entry { grid-template-columns: 30px minmax(0, 1fr); }
  .entry-controls { grid-column: 1 / -1; justify-content: flex-end; margin-top: 8px; padding-top: 12px; border-top: 1px solid var(--sv-border-subtle); }
}
@media (max-width: 600px) {
  .profiles-embedded .embedded-profile-intro,
  .profiles-embedded .rail-head { display: none; }
  .profiles-embedded .rail-actions { margin-top: 0; margin-bottom: 12px; }
  .profiles-embedded .profile-choice { padding: 12px 12px; }
  .profiles-embedded .profile-sharing-note { margin-bottom: 20px; }
  .editor-head { gap: 16px; }
  .editor-actions { gap: 12px; }
  .editor-actions > .el-tag { width: fit-content; }
  .editor-actions > .el-button { flex: 1; }
  .entry { gap: 12px; padding: 16px; }
  .entry-controls { justify-content: flex-start; flex-wrap: wrap; }
  .entry-controls :deep(.el-button) { flex: 1; }
  .add-rule { grid-template-columns: minmax(0, 1fr); padding: 16px; }
}

code {
  display: block;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
/* 当前步骤使用轻量分隔，方案选择仍保留原草稿保护。 */
.profiles-layout{grid-template-columns:208px minmax(0,1fr);gap:24px}.profiles-rail{--el-card-padding:0;border:0;border-radius:0;background:transparent;padding-right:20px;border-right:1px solid var(--sv-border-subtle)}.rail-head h2{font-size:14px;font-weight:500}.rail-actions{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0 16px}.rail-actions .el-button{width:auto;flex:1;margin:0}.profiles-main{gap:0}.profiles-main>.el-card{border:0;border-radius:0;background:transparent;--el-card-padding:0}.profiles-main>.el-card+.el-card{margin-top:0;border-top:1px solid var(--sv-border-subtle);padding-top:20px}.profile-detect,.profile-formats,.profile-draft{padding-bottom:20px}.profile-sharing-note{margin:0 0 16px;padding:0;background:transparent;border-radius:0}.profile-sharing-note summary{display:inline-flex;align-items:center;min-height:40px;font-weight:400;cursor:pointer}.profile-sharing-note p{max-width:900px}.step-head{gap:12px;margin-bottom:16px}.step-head h2{font-size:15px}.step-head p{font-size:12px;margin-top:4px}.step-no{width:28px;height:28px}.format-choice{padding:16px}.add-rule{padding:12px 0;background:transparent;border-radius:0}.editor-head{gap:12px 16px}.name-label{font-size:12px;margin-bottom:8px}.editor-actions{gap:8px}.members-head{margin-top:16px}.profiles-advanced>summary{display:flex;align-items:center;min-height:40px}.library-link{display:inline-flex;align-items:center;min-height:40px}.profile-choice{min-width:0}
@media(max-width:1000px){.profiles-layout{grid-template-columns:minmax(0,1fr)}.profiles-rail{position:static;padding:0 0 16px;border:0;border-bottom:1px solid var(--sv-border-subtle)}.rail-actions{justify-content:flex-start}.rail-actions .el-button{flex:0 1 auto}.rail-list{display:flex;overflow-x:auto;max-height:none;gap:8px;scrollbar-width:thin;padding-bottom:4px}.profile-choice{flex:0 0 auto;max-width:260px;min-width:140px}.profile-choice strong{overflow-wrap:anywhere}.profile-choice small{display:block}}
@media(max-width:768px){.profiles-page .el-button,.profile-sharing-note summary,.profiles-advanced>summary,.library-link,.profile-choice{min-height:44px}.entry-controls :deep(.el-button){min-height:44px}.name-field :deep(.el-input__wrapper){min-height:44px}.profiles-layout{gap:16px}.profiles-main>.el-card+.el-card{padding-top:16px}.profile-sharing-note p{font-size:12px}.rail-list{width:100%;min-width:0}.profile-choice{white-space:normal}.entry-controls :deep(.el-switch){min-height:44px}.add-rule{padding:12px 0}.editor-actions>.el-button{flex:0 1 auto}}
</style>
