<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert } from 'element-plus'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { authRequest } from '../../core/auth/api'
import { base, type Batch, type Node } from './api'
import { failure, readiness as evaluate } from './presentation'
import SongsTab from './components/SongsTab.vue'
import BatchesTab from './components/BatchesTab.vue'
import BatchConfirmDialog from './components/BatchConfirmDialog.vue'

type Tab = 'songs' | 'tasks'
const route = useRoute(), router = useRouter()
const tabs: { id: Tab; name: string }[] = [{ id: 'songs', name: '歌曲' }, { id: 'tasks', name: '生成任务' }]
const tab = computed<Tab>(() => tabs.some(item => item.id === route.query.tab) ? route.query.tab as Tab : 'songs')
const songId = computed(() => typeof route.query.song === 'string' ? route.query.song : '')
const node = ref<Node>(), nodeError = ref(''), error = ref('')
const batches = ref<Batch[]>([]), batchPage = ref(0), batchTotal = ref(0), focus = ref('')
const preview = ref<Batch>(), starting = ref(false)
const songsTab = ref<InstanceType<typeof SongsTab>>(), batchesTab = ref<InstanceType<typeof BatchesTab>>()
const tabButtons = ref<HTMLButtonElement[]>([])
const readiness = computed(() => evaluate(node.value, nodeError.value))
const activeBatches = computed(() => batches.value.filter(batch => batch.state === 'QUEUED').length)
let active = true, timer: ReturnType<typeof setTimeout> | undefined

function setup() { void router.push('/system/compute#audio-models') }
watch(() => route.query.tab, value => { if (value === 'setup') void router.replace('/system/compute#audio-models') }, { immediate: true })
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
  const value = await authRequest<{ items: Batch[]; totalElements: number }>(`${base}/batches?page=${batchPage.value}`)
  if (active) { batches.value = value.items; batchTotal.value = value.totalElements }
}
async function refresh(first = false) {
  const work: Promise<unknown>[] = [loadNode(), loadBatches()]
  if (!first) work.push(tab.value === 'songs' ? songsTab.value?.refresh() ?? Promise.resolve() : tab.value === 'tasks' ? batchesTab.value?.refresh() ?? Promise.resolve() : Promise.resolve())
  const results = await Promise.allSettled(work)
  if (!active) return
  const rejected = results.find(result => result.status === 'rejected') as PromiseRejectedResult | undefined
  error.value = rejected ? failure(rejected.reason, '读取失败') : ''
}
async function poll(first = false) { await refresh(first); if (active) timer = setTimeout(() => { void poll() }, 4000) }
function changeBatchPage(next: number) { batchPage.value = next; void loadBatches().catch(failed => { error.value = failure(failed) }) }
async function start() {
  if (!preview.value) return
  starting.value = true; error.value = ''
  try {
    const value = await authRequest<Batch>(`${base}/batches/${encodeURIComponent(preview.value.id)}/submit`, 'POST')
    if (!active) return
    preview.value = undefined; songsTab.value?.clear(); batchPage.value = 0
    await loadBatches(); focus.value = ''; await nextTick(); focus.value = value.id; navigate('tasks')
  } catch (failed) { if (active) error.value = failure(failed, '无法开始生成') }
  finally { if (active) starting.value = false }
}
async function closePreview() { preview.value = undefined; await loadBatches().catch(() => undefined) }
onMounted(() => { void poll(true) })
onBeforeUnmount(() => { active = false; clearTimeout(timer) })
</script>

<template>
  <main class="audio-processing">
    <PageHeader title="原唱与伴奏" description="让每首歌都能在原唱和伴奏之间切换：自带两条音轨的歌曲标记哪条是原唱，只有一条音轨的歌曲可以用 AI 生成独立伴奏。原始文件不会被修改。">
      <template #actions>
        <button type="button" class="ready-pill" :class="readiness.ready ? 'is-ready' : 'is-pending'" @click="setup()"><span class="sv-status-dot" :class="readiness.ready ? 'ok' : 'warn'" aria-hidden="true" />{{ readiness.ready ? readiness.title : '伴奏生成未就绪' }}</button>
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
        <SongsTab v-if="tab === 'songs'" ref="songsTab" :song-id="songId" :readiness="readiness" :backends="node?.backends || []" @preview="preview = $event" @setup="setup()" @show-all="navigate('songs')" />
        <BatchesTab v-else-if="tab === 'tasks'" ref="batchesTab" :batches="batches" :total="batchTotal" :page="batchPage" :focus="focus" :backends="node?.backends || []" @preview="preview = $event" @page="changeBatchPage" @changed="loadBatches" @songs="navigate('songs')" />
      </KeepAlive>
    </section>
    <BatchConfirmDialog :batch="preview" :ready="readiness.ready" :busy="starting" @start="start" @close="closePreview" />
  </main>
</template>

<style scoped>
.audio-processing{max-width:1280px;margin:0 auto;min-width:0}.ready-pill{display:inline-flex;gap:8px;align-items:center;min-height:32px;padding:0 14px;border:1px solid var(--sv-border-default);border-radius:var(--sv-radius-full);background:var(--sv-surface-1);color:var(--sv-text-primary);font:inherit;font-size:var(--sv-font-meta);cursor:pointer}.ready-pill.is-ready{border-color:var(--sv-semantic-ok);background:var(--sv-semantic-ok-soft)}.ready-pill.is-pending{border-color:var(--sv-semantic-warn);background:var(--sv-semantic-warn-soft)}
.tabs{display:flex;gap:4px;margin:4px 0 16px;border-bottom:1px solid var(--sv-border-subtle);overflow-x:auto}.tab{display:inline-flex;gap:6px;align-items:center;min-height:42px;padding:0 16px;border:0;border-bottom:2px solid transparent;margin-bottom:-1px;background:none;color:var(--sv-text-secondary);font:inherit;font-size:var(--sv-font-body);cursor:pointer;white-space:nowrap}.tab:hover{color:var(--sv-text-primary)}.tab.is-active{border-bottom-color:var(--sv-accent-primary);color:var(--sv-accent-primary);font-weight:600}.page-error{margin-bottom:14px}.panel{min-width:0}
@media(max-width:560px){.tab{padding:0 12px}}
</style>
