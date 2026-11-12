<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert } from 'element-plus'
import TaskGuide from '../../shared/ui/TaskGuide.vue'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { authRequest } from '../../core/auth/api'
import { base, type Batch, type Node } from './api'
import { failure, profileReady, readiness as evaluate } from './presentation'
import SongsTab from './components/SongsTab.vue'
import BatchesTab from './components/BatchesTab.vue'
import BatchConfirmDialog from './components/BatchConfirmDialog.vue'

type Tab = 'songs' | 'tasks'
const route = useRoute(), router = useRouter()
const tabs: { id: Tab; name: string }[] = [{ id: 'songs', name: '歌曲' }, { id: 'tasks', name: '生成任务' }]
const tab = computed<Tab>(() => tabs.some(item => item.id === route.query.tab) ? route.query.tab as Tab : 'songs')
const songId = computed(() => typeof route.query.song === 'string' ? route.query.song : '')
const node = ref<Node>(), nodeError = ref(''), error = ref('')
const batches = ref<Batch[]>([]), batchPage = ref(0), batchTotal = ref(0), focus = ref(typeof route.query.batch === 'string' ? route.query.batch : '')
const preview = ref<Batch>(), starting = ref(false)
const songsTab = ref<InstanceType<typeof SongsTab>>(), batchesTab = ref<InstanceType<typeof BatchesTab>>()
const tabButtons = ref<HTMLButtonElement[]>([])
const readiness = computed(() => evaluate(node.value, nodeError.value))
const anyReady = computed(() => readiness.value.ready || node.value?.separation?.profiles.some(item => item.ready))
const previewReady = computed(() => profileReady(node.value?.separation, preview.value?.separationProfile || 'LEGACY_DEMUCS', readiness.value.ready))
const activeBatches = computed(() => batches.value.filter(batch => ['PREPARING', 'QUEUED'].includes(batch.state)).length)
let active = true, timer: ReturnType<typeof setTimeout> | undefined, batchRequest = 0, previewGeneration = 0

function setup() { void router.push('/system/compute#audio-models') }
watch(() => route.query.tab, value => { if (value === 'setup') void router.replace('/system/compute#audio-models') }, { immediate: true })
watch(() => route.query.batch, value => { focus.value = typeof value === 'string' ? value : '' })
function navigate(next: Tab, extra: Record<string, string> = {}) {
  const query: Record<string, string> = { ...extra }
  if (next !== 'songs') query.tab = next
  void router.replace({ path: route.path, query })
}
function keyNav(event: KeyboardEvent, index: number) {
  const step = { ArrowRight: 1, ArrowLeft: -1, Home: -index, End: tabs.length - 1 - index }[event.key]
  if (step === undefined) return
  event.preventDefault()
  const next = (index + step + tabs.length) % tabs.length
  navigate(tabs[next]!.id); void nextTick(() => tabButtons.value[next]?.focus())
}
async function loadNode(refresh = false) {
  try { const value = await authRequest<Node>(`${base}/node${refresh ? '?refresh=true' : ''}`, 'GET', undefined, 60000); if (active) { node.value = value; nodeError.value = '' } }
  catch (failed) { if (active) nodeError.value = failure(failed, '无法连接计算节点') }
}
async function loadBatches() {
  const turn = ++batchRequest
  const value = await authRequest<{ items: Batch[]; totalElements: number }>(`${base}/batches?page=${batchPage.value}`)
  if (active && turn === batchRequest) { batches.value = value.items; batchTotal.value = value.totalElements }
}
async function refresh(first = false) {
  const work: Promise<unknown>[] = [loadNode(), loadBatches()]
  if (preview.value?.state === 'PREPARING' && !starting.value) work.push(loadPreview())
  if (!first) work.push(tab.value === 'songs' ? songsTab.value?.refresh() ?? Promise.resolve() : tab.value === 'tasks' ? batchesTab.value?.refresh() ?? Promise.resolve() : Promise.resolve())
  const results = await Promise.allSettled(work)
  if (!active) return
  const rejected = results.find(result => result.status === 'rejected') as PromiseRejectedResult | undefined
  error.value = rejected ? failure(rejected.reason, '读取失败') : ''
}
async function poll(first = false) { await refresh(first); if (active) timer = setTimeout(() => { void poll() }, 4000) }
function changeBatchPage(next: number) { batchPage.value = next; void loadBatches().catch(failed => { if (active) error.value = failure(failed) }) }
function showPreview(value: Batch) { previewGeneration++; preview.value = value }
async function start() {
  if (!active || starting.value || preview.value?.state !== 'PREVIEW') return
  const id = preview.value.id, turn = ++previewGeneration
  starting.value = true; error.value = ''
  try {
    const value = await authRequest<Batch>(`${base}/batches/${encodeURIComponent(id)}/submit`, 'POST')
    if (!active || turn !== previewGeneration) return
    preview.value = undefined; songsTab.value?.clear(); batchPage.value = 0
    await loadBatches(); if (!active) return; focus.value = ''; await nextTick(); if (active) { focus.value = value.id; navigate('tasks') }
  } catch (failed) { if (active) error.value = failure(failed, '无法开始生成') }
  finally { if (active) starting.value = false }
}
async function closePreview() { if (starting.value) return; previewGeneration++; preview.value = undefined; await loadBatches().catch(() => undefined) }
async function loadPreview() {
  const id = preview.value?.id, turn = previewGeneration
  if (!id) return
  const value = await authRequest<Batch>(`${base}/batches/${encodeURIComponent(id)}?page=0`)
  if (active && turn === previewGeneration && preview.value?.id === id) preview.value = value
}
async function cancelPreparation() {
  const id = preview.value?.id
  if (!active || !id || starting.value) return
  const turn = ++previewGeneration
  starting.value = true
  try {
    const value = await authRequest<Batch>(`${base}/batches/${encodeURIComponent(id)}/cancel`, 'POST')
    if (!active || turn !== previewGeneration || preview.value?.id !== id) return
    preview.value = value; await loadBatches()
  } catch (failed) { if (active) error.value = failure(failed, '取消准备失败') }
  finally { if (active) starting.value = false }
}
onMounted(() => { void poll(true) })
onBeforeUnmount(() => { active = false; clearTimeout(timer) })
</script>

<template>
  <section class="audio-processing">
    <PageHeader title="原唱与伴奏">
      <template #actions>
        <button type="button" class="ready-pill" :class="anyReady ? 'is-ready' : 'is-pending'" @click="setup()"><span class="sv-status-dot" :class="anyReady ? 'ok' : 'warn'" aria-hidden="true" />{{ readiness.ready ? readiness.title : anyReady ? '极速档已就绪' : '伴奏生成未就绪' }}</button>
      </template>
      <template #description>
        <p>自带伴奏的歌曲先试听标记；普通立体声可选极速中心抵消或轻量 AI 分离，试听后生成整曲。原始文件保持只读。</p>
        <TaskGuide v-if="tab === 'songs'" label="原唱与伴奏准备流程" :steps="[{ title: '先检查音轨', description: '识别已有原唱与伴奏，试听后标记。' }, { title: '按需要生成', description: '没有伴奏时，先试片段再生成整曲。' }, { title: '核对成果版本', description: '试听处理结果，保留原始媒体。' }]" />
      </template>
    </PageHeader>
    <div class="tabs" role="tablist" aria-label="原唱与伴奏功能">
      <button v-for="(item, index) in tabs" :id="'audio-tab-' + item.id" :key="item.id" ref="tabButtons" type="button" role="tab" class="tab" :class="{ 'is-active': tab === item.id }" :aria-selected="tab === item.id" :aria-controls="'audio-panel-' + item.id" :tabindex="tab === item.id ? 0 : -1" @click="navigate(item.id)" @keydown="keyNav($event, index)">
        {{ item.name }}<span v-if="item.id === 'tasks' && activeBatches" class="sv-count-pill" :aria-label="`${activeBatches} 个进行中`">{{ activeBatches }}</span>
      </button>
    </div>
    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" role="alert" class="page-error" />
    <section :id="'audio-panel-' + tab" role="tabpanel" :aria-labelledby="'audio-tab-' + tab" class="panel">
      <KeepAlive>
        <SongsTab v-if="tab === 'songs'" ref="songsTab" :song-id="songId" :readiness="readiness" :backends="node?.backends || []" :separation="node?.separation" @preview="showPreview" @setup="setup()" @show-all="navigate('songs')" />
        <BatchesTab v-else-if="tab === 'tasks'" ref="batchesTab" :batches="batches" :total="batchTotal" :page="batchPage" :focus="focus" :backends="node?.backends || []" @preview="showPreview" @page="changeBatchPage" @changed="loadBatches" @songs="navigate('songs')" />
      </KeepAlive>
    </section>
    <BatchConfirmDialog :batch="preview" :ready="previewReady" :busy="starting" @start="start" @close="closePreview" @cancel="cancelPreparation" />
  </section>
</template>

<style scoped>
.audio-processing{min-width:0}.ready-pill{display:inline-flex;gap:8px;align-items:center;min-height:40px;padding:0 12px;border:1px solid transparent;border-radius:8px;background:transparent;color:var(--sv-text-primary);font:inherit;font-size:var(--sv-font-meta);cursor:pointer}.ready-pill:hover{background:var(--sv-surface-3)}
.tabs{display:flex;gap:4px;margin:0 0 16px;border-bottom:1px solid var(--sv-border-subtle);overflow-x:auto}.tab{display:inline-flex;gap:8px;align-items:center;min-height:42px;padding:0 16px;border:0;border-bottom:2px solid transparent;margin-bottom:-1px;background:none;color:var(--sv-text-secondary);font:inherit;font-size:var(--sv-font-body);cursor:pointer;white-space:nowrap}.tab:hover{color:var(--sv-text-primary)}.tab.is-active{border-bottom-color:var(--sv-accent-primary);color:var(--sv-accent-primary);font-weight:600}.page-error{margin-bottom:16px}.panel{min-width:0}
@media(max-width:768px){.tab,.ready-pill{min-height:44px}}
@media(max-width:560px){.tab{padding:0 12px}}
</style>
