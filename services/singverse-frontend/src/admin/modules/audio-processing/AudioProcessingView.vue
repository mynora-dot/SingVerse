<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElDialog } from 'element-plus'
import PageHeader from '../../shared/ui/PageHeader.vue'
import AudioRolesPanel from '../song-management/components/AudioRolesPanel.vue'
import { authRequest } from '../../core/auth/api'
import { base, importModel, payload, query, type AudioRow, type Batch, type Filter, type Node } from './api'

const route = useRoute()
const tab = ref('songs'), error = ref(''), loading = ref(false), busy = ref(false)
const filter = reactive<Filter>({ title: '', artist: '', sourceId: '', streams: '', state: '', songId: typeof route.query.song === 'string' ? route.query.song : '' })
const applied = ref<Filter>({ ...filter })
const rows = ref<AudioRow[]>([]), total = ref(0), page = ref(0), selected = reactive(new Set<string>()), all = ref(false)
const sources = ref<{ id: string; name: string }[]>([]), node = ref<Node>(), batches = ref<Batch[]>([]), batchPage = ref(0), batchTotal = ref(0)
const preview = ref<Batch>(), current = ref<Batch>(), itemPage = ref(0), editor = ref(''), preferred = ref('')
let active = true, timer: ReturnType<typeof setTimeout> | undefined, sequence = 0
const labels: Record<string, string> = { READY: '已有伴奏', PROCESSING: '处理中', UNPROBED: '待探测', ELIGIBLE: '可生成', INAPPLICABLE: '非单音轨', QUEUED: '排队中', RUNNING: '处理中', SUCCEEDED: '完成', FAILED: '失败', CANCELLED: '已取消', PREVIEW: '待提交', COMPLETED: '已结束', PENDING: '排队中', PROBING: '探测中', SKIPPED: '已跳过', AVAILABLE: '可用', IMAGE_MISSING: '当前镜像未包含', RUNTIME_MISSING: '缺少运行环境', MODEL_MISSING: '缺少模型或固定版本依赖', UNVERIFIED: '待试跑', MODEL_UNSUPPORTED: '当前模型未适配', DOWNLOADING: '下载中', IMPORTING: '导入中', VERIFYING: '校验中', TESTING: '试跑中', IDLE: '未开始' }
const modelId = ref('htdemucs')
const catalog = computed(() => node.value?.models || (node.value ? [node.value.model] : []))
const chosenModel = computed(() => catalog.value.find(model => model.id === modelId.value) || node.value?.model)
const ready = computed(() => !!node.value?.selected && node.value.resourceReady !== false)
const modelBusy = computed(() => ['DOWNLOADING', 'IMPORTING', 'VERIFYING', 'TESTING'].includes(node.value?.operation.state || ''))
const selectedCount = computed(() => all.value ? total.value : selected.size)
function label(value: string) { return labels[value] || value }
function bytes(value: number) { return `${(value / 1024 ** 3).toFixed(1)} GiB` }
function reason(row: AudioRow) { return row.audio.jobs.find(job => job.kind === 'SEPARATE')?.error || '' }
async function act(run: () => Promise<unknown>) {
  busy.value = true; error.value = ''
  try { await run() } catch (failure) { if (active) error.value = failure instanceof Error ? failure.message : '操作失败' }
  finally { if (active) busy.value = false }
}
async function loadRows() {
  const turn = ++sequence; loading.value = true
  try { const value = await authRequest<{ items: AudioRow[]; totalElements: number }>(`${base}/songs?${query(applied.value, page.value)}`, 'GET', undefined, 60000)
    if (active && turn === sequence) { rows.value = value.items; total.value = value.totalElements }
  } finally { if (active && turn === sequence) loading.value = false }
}
async function loadNode(refresh = false) { const value = await authRequest<Node>(`${base}/node${refresh ? '?refresh=true' : ''}`, 'GET', undefined, 60000); if (active) node.value = value }
async function loadBatches() { const value = await authRequest<{ items: Batch[]; totalElements: number }>(`${base}/batches?page=${batchPage.value}`); if (active) { batches.value = value.items; batchTotal.value = value.totalElements } }
async function loadCurrent() { if (!current.value) return; const id = current.value.id, page = itemPage.value; const value = await authRequest<Batch>(`${base}/batches/${id}?page=${page}`); if (active && current.value?.id === id && itemPage.value === page) current.value = value }
async function refresh() {
  const results = await Promise.allSettled([loadNode(), tab.value === 'songs' ? loadRows() : loadBatches(), loadCurrent()])
  if (active) for (const result of results) if (result.status === 'rejected') error.value = result.reason instanceof Error ? result.reason.message : '读取失败'
}
async function poll() { await refresh(); if (active) timer = setTimeout(() => { void poll() }, 4000) }
function search() { applied.value = { ...filter }; page.value = 0; selected.clear(); all.value = false; void act(loadRows) }
function check(id: string, checked: boolean) { all.value = false; checked ? selected.add(id) : selected.delete(id) }
function pageSelect(checked: boolean) { all.value = false; for (const row of rows.value) checked ? selected.add(row.id) : selected.delete(row.id) }
async function makePreview(ids?: string[]) {
  const value = await authRequest<Batch>(`${base}/batches/preview`, 'POST', { ids: ids || [...selected], all: !ids && all.value, filter: payload(applied.value), backend: preferred.value }, 120000)
  if (active) preview.value = value
}
async function start() { if (!preview.value) return; const value = await authRequest<Batch>(`${base}/batches/${preview.value.id}/submit`, 'POST'); if (active) { preview.value = undefined; current.value = value; itemPage.value = 0; tab.value = 'batches'; await loadBatches() } }
async function openBatch(batch: Batch) { current.value = batch; itemPage.value = 0; await loadCurrent() }
async function cancelBatch(id: string) { await authRequest(`${base}/batches/${id}/cancel`, 'POST'); await Promise.all([loadBatches(), loadCurrent()]) }
async function retry(id: string, backend = '') { const value = await authRequest<Batch>(`${base}/batches/${id}/retry`, 'POST', { backend }); if (active) preview.value = value }
async function model(action: string, backend?: string) { await authRequest(`${base}/model/${action}${action === 'download' && modelId.value !== 'htdemucs' ? '?model=' + encodeURIComponent(modelId.value) : ''}`, 'POST', backend ? { backend } : undefined, 60000); await loadNode() }
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file || !node.value) return
  if (file.size > (chosenModel.value?.maxBytes || 0)) { error.value = '模型文件超过所选模型清单的大小限制'; input.value = ''; return }
  await act(async () => { await importModel(file, modelId.value); await loadNode() }); input.value = ''
}
watch(() => route.query.song, value => { filter.songId = typeof value === 'string' ? value : ''; search() })
onMounted(async () => {
  try {
    for (let page = 0; active; page++) {
      const value = await authRequest<{ items: { id: string; name: string }[]; totalElements?: number }>(`/api/v1/media-sources?size=100&page=${page}`)
      if (!active) return
      sources.value.push(...value.items)
      if (value.items.length < 100 || (value.totalElements !== undefined && sources.value.length >= value.totalElements)) break
    }
  }
  catch (failure) { if (active) error.value = failure instanceof Error ? failure.message : '媒体库读取失败' }
  if (active) void poll()
})
onBeforeUnmount(() => { active = false; sequence++; clearTimeout(timer) })
</script>

<template>
  <main class="audio-processing">
    <PageHeader title="原唱与伴奏" description="识别歌曲音轨，批量生成独立伴奏，并管理计算节点的模型。" />
    <nav class="tabs" aria-label="原唱与伴奏功能"><ElButton v-for="item in [{ id: 'songs', name: '歌曲处理' }, { id: 'batches', name: '批量任务' }, { id: 'node', name: '节点与模型' }]" :key="item.id" :type="tab === item.id ? 'primary' : 'default'" :aria-pressed="tab === item.id" @click="tab = item.id; act(refresh)">{{ item.name }}</ElButton></nav>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <section v-if="tab === 'songs'">
      <div v-if="!ready" class="notice">{{ node?.resourceReady === false ? '节点当前内存或磁盘不足，请检查资源预算。' : node ? '当前节点尚未通过模型试跑，暂不能生成伴奏。' : '尚未连接可用计算节点。' }}<ElButton text @click="tab = 'node'">检查节点与模型</ElButton></div>
      <form class="filters" @submit.prevent="search">
        <label>歌曲名称<input v-model="filter.title" placeholder="搜索歌曲名称"></label><label>歌手<input v-model="filter.artist" placeholder="搜索歌手"></label>
        <label>媒体库<select v-model="filter.sourceId"><option value="">全部媒体库</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select></label>
        <label>音轨数量<select v-model="filter.streams"><option value="">全部音轨</option><option value="1">单音轨</option><option value="2">双音轨</option><option value="0">无音轨</option></select></label>
        <label>处理状态<select v-model="filter.state"><option value="">全部状态</option><option v-for="state in ['UNPROBED', 'ELIGIBLE', 'READY', 'PROCESSING', 'INAPPLICABLE', 'FAILED']" :key="state" :value="state">{{ label(state) }}</option></select></label><ElButton native-type="submit" :loading="loading">筛选</ElButton>
        <ElButton v-if="filter.songId" text @click="filter.songId = ''; search()">查看全部歌曲</ElButton>
      </form>
      <div class="toolbar"><span>已选 {{ selectedCount }} 首{{ all ? '（全部筛选结果；清空后可逐首勾选）' : '' }}</span><ElButton :disabled="!total || loading" @click="all = true; selected.clear()">选中全部筛选结果（{{ total }}）</ElButton><ElButton text @click="all = false; selected.clear()">清空选择</ElButton>
        <label>执行方式<select v-model="preferred"><option value="">使用节点设置</option><option v-for="backend in node?.backends.filter(item => item.state === 'AVAILABLE')" :key="backend.id" :value="backend.id">{{ backend.id.toUpperCase() }}</option></select></label>
        <ElButton type="primary" :disabled="busy || !ready || !selectedCount || loading" @click="act(() => makePreview())">批量生成伴奏</ElButton></div>
      <div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="选择本页歌曲" :checked="all || (!!rows.length && rows.every(row => selected.has(row.id)))" @change="pageSelect(($event.target as HTMLInputElement).checked)"></th><th>歌曲</th><th>音轨与角色</th><th>生成状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.id"><td><input type="checkbox" :aria-label="'选择' + row.title" :checked="all || selected.has(row.id)" :disabled="all" @change="check(row.id, ($event.target as HTMLInputElement).checked)"></td><td><strong>{{ row.title || row.relativePath }}</strong><p>{{ row.artists.join('、') }} · {{ row.sourceName }}</p></td><td>{{ row.audio.streams?.length ?? '待探测' }}<p>{{ row.audio.state === 'CONFIRMED' ? `原唱 ${row.audio.originalId || ''}${row.audio.backingId ? ' · 伴奏 ' + row.audio.backingId : ''}` : '角色待确认' }}</p></td><td>{{ label(row.category) }}<p v-if="reason(row)" class="error">{{ reason(row) }}</p></td><td><ElButton :disabled="busy || !ready || !['ELIGIBLE', 'UNPROBED'].includes(row.category)" @click="act(() => makePreview([row.id]))">生成伴奏</ElButton><ElButton text @click="editor = row.id">{{ row.audio.streams?.length === 2 ? '识别 / 纠正' : '查看任务' }}</ElButton></td></tr>
        <tr v-if="!rows.length"><td colspan="5">{{ loading ? '正在读取歌曲…' : '没有符合条件的歌曲' }}</td></tr>
      </tbody></table></div>
      <div class="pagination"><ElButton :disabled="!page || loading" @click="page--; act(loadRows)">上一页</ElButton><span>第 {{ page + 1 }} 页 · 共 {{ total }} 首</span><ElButton :disabled="(page + 1) * 30 >= total || loading" @click="page++; act(loadRows)">下一页</ElButton></div>
    </section>
    <section v-else-if="tab === 'batches'">
      <div v-for="batch in batches" :key="batch.id" class="card"><h2>{{ label(batch.state) }} · {{ batch.total }} 首</h2><p>完成 {{ batch.counts.SUCCEEDED || 0 }} · 跳过 {{ batch.counts.SKIPPED || 0 }} · 失败 {{ batch.counts.FAILED || 0 }} · 取消 {{ batch.counts.CANCELLED || 0 }} · 待处理 {{ (batch.counts.PENDING || 0) + (batch.counts.PROBING || 0) + (batch.counts.PROCESSING || 0) }}</p><ElButton @click="act(() => openBatch(batch))">查看详情</ElButton><ElButton v-if="batch.state === 'PREVIEW'" :disabled="busy" @click="preview = batch">确认提交</ElButton><ElButton v-if="batch.state === 'QUEUED' || batch.state === 'PREVIEW'" :disabled="busy" @click="act(() => cancelBatch(batch.id))">取消批次</ElButton><ElButton v-if="batch.counts.FAILED" :disabled="busy" @click="act(() => retry(batch.id))">重试失败项</ElButton><ElButton v-if="batch.counts.FAILED" :disabled="busy || !node?.backends.some(item => item.id === 'cpu' && item.state === 'AVAILABLE')" @click="act(() => retry(batch.id, 'cpu'))">用CPU重试失败项</ElButton></div>
      <p v-if="!batches.length">暂无批量任务</p><div class="pagination"><ElButton :disabled="!batchPage" @click="batchPage--; act(loadBatches)">上一页</ElButton><span>第 {{ batchPage + 1 }} 页</span><ElButton :disabled="(batchPage + 1) * 20 >= batchTotal" @click="batchPage++; act(loadBatches)">下一页</ElButton></div>
      <section v-if="current" class="card"><h2>批次详情 · {{ label(current.state) }}</h2><p>执行方式：{{ current.backend || '节点设置' }}</p><div v-for="item in current.items" :key="item.song_id" class="task-item"><span>{{ item.title }} · {{ label(item.state === 'PROCESSING' ? (item.job_state || item.state) : item.state) }} <span v-if="item.execution?.backend"> · {{ item.execution.backend.toUpperCase() }}</span> <span v-if="item.error" class="error">{{ item.error }}</span></span><ElButton v-if="['PENDING', 'PROBING', 'PROCESSING'].includes(item.state)" :disabled="busy" text @click="act(async () => { await authRequest(`${base}/batches/${current!.id}/items/${item.song_id}/cancel`, 'POST'); await loadCurrent() })">取消</ElButton></div><div class="pagination"><ElButton :disabled="!itemPage" @click="itemPage--; act(loadCurrent)">上一页</ElButton><span>第 {{ itemPage + 1 }} 页</span><ElButton :disabled="(itemPage + 1) * 50 >= current.total" @click="itemPage++; act(loadCurrent)">下一页</ElButton></div></section>
    </section>
    <section v-else>
      <div class="toolbar"><ElButton :disabled="busy" @click="act(() => loadNode(true))">重新检测环境</ElButton><RouterLink to="/system/compute">性能节点配置</RouterLink></div>
      <template v-if="node">
        <section class="card"><h2>当前计算节点</h2><p>{{ node.environment.system }} · {{ node.environment.architecture }} · {{ node.environment.processor || '处理器信息未提供' }}</p><p v-for="(hardware, index) in node.environment.hardware" :key="index">{{ hardware.board || `PCI设备 ${hardware.vendor}:${hardware.device}` }}</p><p>内存参考值 {{ bytes(node.memoryAvailableBytes) }} · 工作目录可用空间 {{ bytes(node.diskFreeBytes) }}</p><p v-if="node.limits">音频进程内存预算 {{ bytes(node.limits.audioProcessBudgetBytes) }} · 磁盘最小预留 {{ bytes(node.limits.minimumFreeBytes) }}</p><p>运行环境：{{ Object.entries(node.environment.versions).map(([key, value]) => `${key} ${value}`).join(' · ') || '尚未安装兼容的运行环境' }}</p><p>当前执行方式：{{ node.selected?.toUpperCase() || '暂无通过试跑的后端' }}</p><p>下载模型不会安装Python依赖或驱动；请先按设备准备对应的运行环境。</p><details><summary>安装方式</summary><p>在实际计算节点的项目目录执行命令；远程节点需要在远程设备准备环境。容器部署通过重建对应镜像安装。</p><p>Linux x86/ARM64 CPU、Mac：<code>{{ node.instructions.cpu }}</code></p><p>NVIDIA CUDA：<code>{{ node.instructions.cuda }}</code></p><p>兼容AMD ROCm设备：<code>{{ node.instructions.rocm }}</code></p><p>Intel OpenVINO：<code>{{ node.instructions.openvino }}</code></p><p>容器请使用对应后端镜像，配置驱动及设备映射；安装后重新检测并试跑。RK3566 NPU尚无合格转换模型。</p></details></section>
        <section v-if="node.build" class="card"><h2>镜像与部署</h2><p>版本 {{ node.build.version }} · {{ node.build.variant }} · 构建 {{ node.build.revision }}</p><p class="digest">运行环境清单摘要：{{ node.build.runtimeLock }}</p><p>{{ node.deployment?.note }}</p><details v-for="profile in node.deployment?.profiles" :key="profile.backend"><summary>{{ profile.backend.toUpperCase() }} · {{ profile.included ? '当前运行环境已包含' : '需要对应镜像' }}</summary><p>镜像标签：<code>{{ profile.image }}</code></p><p>本地构建并部署：<code>{{ profile.command }}</code></p><p>使用预构建镜像时设置 COMPUTE_IMAGE 为固定版本镜像，并将 --build 替换为 --no-build。请先按部署文档完成设备映射及宿主驱动检查。</p></details><p>更换镜像后需重新检测和试跑；网页不会更改Docker配置或宿主驱动。M2使用macOS原生环境，RKNN适配待验收。</p></section>
        <section v-if="chosenModel" class="card"><h2>分离模型</h2><label>模型版本<select v-model="modelId"><option v-for="item in catalog" :key="item.id" :value="item.id">{{ item.id }} · {{ item.version }}</option></select></label><p>{{ chosenModel.installed ? '固定版本模型权重已校验，运行环境和后端仍需检测与试跑' : '模型权重尚未安装或校验未通过' }}</p><p v-if="!chosenModel.writable">模型目录未配置、不可写或为只读预置目录；网页下载和导入不可用，请在节点配置独立可写模型卷。</p><ElButton :disabled="busy || modelBusy || !chosenModel.writable" type="primary" @click="act(() => model('download'))">下载模型到节点</ElButton><label class="upload">离线导入{{ modelId === 'htdemucs' ? '官方权重' : '官方模型ZIP包' }}<input type="file" :accept="modelId === 'htdemucs' ? '.th' : '.zip'" :disabled="busy || modelBusy || !chosenModel.writable" @change="upload"></label><ElButton v-if="modelBusy" @click="act(() => model('cancel'))">取消模型操作</ElButton><p>模型操作：{{ label(node.operation.state) }} · {{ (node.operation.bytes / 1024 ** 2).toFixed(1) }} MiB</p><p v-if="node.operation.error" class="error">{{ node.operation.error }}</p><details><summary>离线下载与校验</summary><p>仅接受下列固定官方文件；OpenVINO ZIP根目录必须恰好包含XML和BIN两个文件，无需配置文件。</p><template v-if="chosenModel.files"><div v-for="file in chosenModel.files" :key="file.name"><a :href="file.url" target="_blank" rel="noreferrer">下载 {{ file.name }}</a><p class="digest">SHA-256：{{ file.sha256 }}</p></div></template><template v-else><a :href="node.model.downloadUrl" target="_blank" rel="noreferrer">下载 {{ node.model.filename }}</a><p class="digest">SHA-256：{{ node.model.sha256 }}</p></template></details></section>
        <section class="card"><h2>推理后端</h2><p>通过模型试跑后才能启用。试跑验证执行与时间线，真实歌曲音质及长曲稳定性仍需设备验收。</p><div v-for="backend in node.backends" :key="backend.id" class="backend"><div><strong>{{ backend.id.toUpperCase() }} · {{ backend.device?.name || '设备信息未提供' }}</strong><p>{{ label(backend.state) }} · {{ backend.reason }}<span v-if="backend.elapsedMs"> · {{ backend.elapsedMs }} ms</span></p></div><div><ElButton :disabled="busy || modelBusy || !['UNVERIFIED', 'FAILED', 'AVAILABLE'].includes(backend.state)" @click="act(() => model('test', backend.id))">模型试跑</ElButton><ElButton :disabled="busy || backend.state !== 'AVAILABLE'" @click="act(() => model('select', backend.id))">使用此后端</ElButton></div></div><ElButton :disabled="busy" @click="act(() => model('select', 'auto'))">自动选择已验证后端</ElButton></section>
      </template><p v-else>请先连接计算节点，再检查模型和运行环境。</p>
    </section>
    <ElDialog :model-value="!!preview" title="确认批量生成" width="min(600px, 94vw)" @close="preview = undefined"><template v-if="preview"><p>本次快照 {{ preview.total }} 首；后续筛选变化不会改变此批次。</p><p>可生成 {{ preview.categories.ELIGIBLE || 0 }} · 待探测 {{ preview.categories.UNPROBED || 0 }} · 已有伴奏 {{ preview.categories.READY || 0 }} · 处理中 {{ preview.categories.PROCESSING || 0 }} · 不适用 {{ preview.categories.INAPPLICABLE || 0 }}</p><p>已有伴奏、其他分离任务及非单音轨将跳过。待探测歌曲只有确认为单音轨后才会生成。</p><ElButton :disabled="busy || !ready || !(preview.counts.PENDING || 0)" type="primary" @click="act(start)">确认生成</ElButton><ElButton :disabled="busy" @click="preview = undefined">返回</ElButton></template></ElDialog>
    <ElDialog :model-value="!!editor" title="歌曲音轨与任务" width="min(680px, 94vw)" @closed="editor = ''" @close="editor = ''"><AudioRolesPanel v-if="editor" :song-id="editor" :can-process="ready" /></ElDialog>
  </main>
</template>

<style scoped>
.audio-processing{max-width:1440px;margin:0 auto}.tabs,.toolbar,.pagination{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:20px 0}.filters{display:flex;align-items:end;gap:12px;flex-wrap:wrap;margin:20px 0}label{display:flex;flex-direction:column;gap:7px;font-size:13px}input:not([type=checkbox]),select{min-height:38px;padding:8px 10px;border:1px solid var(--sv-border-default);border-radius:8px;background:var(--sv-surface-1);color:var(--sv-text-primary);max-width:100%;box-sizing:border-box}.card{padding:22px;margin:18px 0;border:1px solid var(--sv-border-default);border-radius:12px;background:var(--sv-surface-1)}h2{font-size:17px;margin:0 0 12px}p{font-size:13px;line-height:1.7;margin:8px 0;color:var(--sv-text-secondary)}.notice{padding:12px 16px;background:var(--sv-surface-3);border-radius:8px}.error{color:var(--sv-semantic-danger,#b33)}.table-scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:13px}td,th{text-align:left;padding:14px 12px;border-bottom:1px solid var(--sv-border-subtle)}th{white-space:nowrap}td p{margin:5px 0 0}td:last-child{min-width:200px}.task-item,.backend{display:flex;align-items:center;justify-content:space-between;gap:16px;border-bottom:1px solid var(--sv-border-subtle);padding:14px 0}.upload{display:inline-flex;margin:12px;vertical-align:middle}.digest{overflow-wrap:anywhere}code{overflow-wrap:anywhere}.pagination{justify-content:flex-end}@media(max-width:640px){.filters>label{width:100%}.card{padding:16px}.backend{align-items:start;flex-direction:column}.tabs{gap:4px}.tabs :deep(.el-button){margin:0}.toolbar{align-items:start}.toolbar>label{width:100%}}
</style>
