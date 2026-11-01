<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDrawer, ElEmpty, ElInput, ElPagination, ElSkeleton, ElTag } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import AudioRolesPanel from '../../song-management/components/AudioRolesPanel.vue'
import { authRequest } from '../../../core/auth/api'
import { base, payload, query, type AudioRow, type Backend, type Batch, type Filter } from '../api'
import { backendName, failure, rolesSummary, type Readiness } from '../presentation'

const props = defineProps<{ songId: string; readiness: Readiness; backends: Backend[] }>()
const emit = defineEmits<{ preview: [batch: Batch]; setup: []; showAll: [] }>()
const pageSize = ref(30)
const states = [
  { value: '', label: '全部' }, { value: 'ELIGIBLE', label: '可生成伴奏' }, { value: 'UNCONFIRMED', label: '待标记原唱' }, { value: 'UNPROBED', label: '待识别' },
  { value: 'READY', label: '已有伴奏' }, { value: 'PROCESSING', label: '生成中' }, { value: 'FAILED', label: '生成失败' }, { value: 'INAPPLICABLE', label: '多音轨/无音轨' },
]
const filter = reactive<Filter>({ title: '', artist: '', sourceId: '', streams: '', state: '', songId: props.songId })
const applied = ref<Filter>({ ...filter })
const rows = ref<AudioRow[]>([]), total = ref(0), page = ref(0), loaded = ref(false), loading = ref(false), busy = ref(false)
const selected = reactive(new Set<string>()), all = ref(false), preferred = ref('')
const sources = ref<{ id: string; name: string }[]>([]), error = ref(''), sourcesError = ref('')
const editor = ref<AudioRow>(), opened = ref('')
let active = true, sequence = 0
const available = computed(() => props.backends.filter(item => item.state === 'AVAILABLE' && item.id !== 'cpu'))
watch(available, items => { if (!items.some(item => item.id === preferred.value)) preferred.value = '' })
const coverErrors = reactive(new Set<string>())
function latest(row: AudioRow) { return row.audio.jobs.find(job => job.kind === 'SEPARATE') }
const taskLabels: Record<string, string> = { QUEUED: '等待生成', RUNNING: '生成中', SUCCEEDED: '已完成', FAILED: '生成失败', CANCELLED: '已取消' }
function resize(size: number) { pageSize.value = size; page.value = 0; void loadRows() }
const selectedCount = computed(() => all.value ? total.value : selected.size)
const pageChecked = computed(() => all.value || (!!rows.value.length && rows.value.every(row => selected.has(row.id))))
const pageMixed = computed(() => !pageChecked.value && rows.value.some(row => selected.has(row.id)))
const filtered = computed(() => Object.entries(applied.value).some(([key, value]) => key !== 'songId' && value !== ''))
const singleTitle = computed(() => rows.value.find(row => row.id === applied.value.songId)?.title || '')

function tracks(row: AudioRow) {
  const streams = row.audio.streams
  if (!streams) return '音轨待识别'
  if (!streams.length) return '没有音轨'
  return `${streams.length} 条音轨`
}
function action(row: AudioRow) {
  const count = row.audio.streams?.length
  if (['ELIGIBLE', 'UNPROBED'].includes(row.category)) return 'generate'
  if (row.category === 'READY') return 'listen'
  if (count === 2) return row.audio.state === 'CONFIRMED' ? 'relabel' : 'label'
  return 'detail'
}
async function loadRows() {
  const turn = ++sequence; loading.value = true
  try {
    const value = await authRequest<{ items: AudioRow[]; totalElements: number }>(`${base}/songs?${query(applied.value, page.value, pageSize.value)}`, 'GET', undefined, 60000)
    if (active && turn === sequence) { total.value = value.totalElements; const last = Math.max(0, Math.ceil(total.value / pageSize.value) - 1); if (page.value > last) { page.value = last; void loadRows(); return } rows.value = value.items; loaded.value = true; error.value = '' }
  } catch (failed) { if (active && turn === sequence) error.value = failure(failed, '读取歌曲失败') }
  finally { if (active && turn === sequence) loading.value = false }
}
function search() { applied.value = { ...filter }; page.value = 0; selected.clear(); all.value = false; void loadRows() }
function reset() { Object.assign(filter, { title: '', artist: '', sourceId: '', streams: '', state: '' }); search() }
function go(next: number) { page.value = next; void loadRows() }
function check(id: string, checked: boolean) { all.value = false; checked ? selected.add(id) : selected.delete(id) }
function pageSelect(checked: boolean) { all.value = false; for (const row of rows.value) checked ? selected.add(row.id) : selected.delete(row.id) }
function clear() { all.value = false; selected.clear() }
async function makePreview(ids?: string[]) {
  busy.value = true; error.value = ''
  try {
    const value = await authRequest<Batch>(`${base}/batches/preview`, 'POST', { ids: ids || [...selected], all: !ids && all.value, filter: payload(applied.value), backend: preferred.value }, 120000)
    if (active) emit('preview', value)
  } catch (failed) { if (active) error.value = failure(failed, '无法准备生成任务') }
  finally { if (active) busy.value = false }
}
async function loadSources() {
  sourcesError.value = ''
  try {
    const found: { id: string; name: string }[] = []
    for (let next = 0; active; next++) {
      const value = await authRequest<{ items: { id: string; name: string }[]; totalElements?: number }>(`/api/v1/media-sources?size=100&page=${next}`)
      found.push(...value.items)
      if (value.items.length < 100 || (value.totalElements !== undefined && found.length >= value.totalElements)) break
    }
    if (active) sources.value = found
  } catch (failed) { if (active) sourcesError.value = failure(failed, '媒体库读取失败') }
}
watch(() => props.songId, value => { filter.songId = value; opened.value = ''; search() })
// 从歌曲详情跳转过来时，直接打开这首歌的音轨面板。
watch(rows, value => {
  const target = applied.value.songId
  if (target && opened.value !== target) { const row = value.find(item => item.id === target); if (row) { opened.value = target; editor.value = row } }
})
onMounted(() => { void loadSources(); void loadRows() })
// KeepAlive 首次挂载也会触发 activated；只在切回页签时刷新。
let activations = 0
onActivated(() => { if (activations++) void loadRows() })
onDeactivated(() => { editor.value = undefined })
onBeforeUnmount(() => { active = false; sequence++ })
defineExpose({ refresh: loadRows, clear })
</script>

<template>
  <div class="songs-tab">
    <ElAlert v-if="!readiness.ready" type="warning" :closable="false" show-icon class="ready-alert" :title="readiness.title">
      <p>{{ readiness.hint || '准备好之后才能生成伴奏。' }}人工标记原唱不受影响。</p>
      <ElButton type="warning" plain size="small" @click="emit('setup')">去准备环境</ElButton>
    </ElAlert>

    <div v-if="applied.songId" class="single-banner" role="status">
      <span>正在查看单首歌曲{{ singleTitle ? `：${singleTitle}` : '' }}</span>
      <ElButton link type="primary" @click="emit('showAll')">查看全部歌曲</ElButton>
    </div>

    <section v-else class="sv-panel filter-panel" aria-label="筛选歌曲">
      <form class="filters" role="search" @submit.prevent="search">
        <ElInput v-model="filter.title" placeholder="搜索歌曲名称" aria-label="歌曲名称" :prefix-icon="Search" clearable maxlength="200" @clear="search" />
        <ElInput v-model="filter.artist" placeholder="搜索歌手" aria-label="歌手" clearable maxlength="200" @clear="search" />
        <select v-model="filter.sourceId" class="native-select" aria-label="媒体库" @change="search"><option value="">全部媒体库</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select>
        <select v-model="filter.streams" class="native-select" aria-label="音轨数量" @change="search"><option value="">任意音轨数</option><option value="1">1 条音轨</option><option value="2">2 条音轨</option><option value="0">没有音轨</option></select>
        <ElButton type="primary" native-type="submit" :loading="loading">搜索</ElButton>
      </form>
      <fieldset class="state-chips">
        <legend>按状态查看</legend>
        <label v-for="item in states" :key="item.value" class="chip" :class="{ 'is-active': filter.state === item.value }"><input v-model="filter.state" type="radio" name="audio-state" :value="item.value" @change="search">{{ item.label }}</label>
        <ElButton v-if="filtered" link type="primary" class="reset" @click="reset">清除筛选</ElButton>
      </fieldset>
      <p v-if="sourcesError" class="inline-error">{{ sourcesError }} <ElButton link @click="loadSources">重试</ElButton></p>
    </section>

    <div class="selection-bar" role="region" aria-label="批量生成伴奏" :class="{ 'has-selection': selectedCount }">
      <label class="page-check"><input type="checkbox" aria-label="选择本页歌曲" :checked="pageChecked" :indeterminate.prop="pageMixed" :disabled="!rows.length" @change="pageSelect(($event.target as HTMLInputElement).checked)"><span>{{ selectedCount ? `已选 ${selectedCount} 首` : `共 ${total} 首` }}</span></label>
      <span v-if="all" class="muted">已选择全部筛选结果</span>
      <ElButton v-else-if="total > rows.length && total > 0" link type="primary" :disabled="loading" @click="all = true; selected.clear()">选择全部 {{ total }} 首</ElButton>
      <ElButton v-if="selectedCount" link @click="clear">清空</ElButton>
      <span class="spacer" />
      <label v-if="available.length > 1" class="device">使用<select v-model="preferred" class="native-select" aria-label="生成伴奏使用的设备"><option value="">自动选择</option><option v-for="backend in available" :key="backend.id" :value="backend.id">{{ backendName(backend.id) }}</option></select></label>
      <ElButton type="primary" :loading="busy" :disabled="busy || !readiness.ready || !selectedCount || loading" @click="makePreview()">为所选歌曲生成伴奏</ElButton>
    </div>

    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" class="list-error"><ElButton link @click="loadRows">重试</ElButton></ElAlert>
    <ElSkeleton v-if="loading && !loaded" :rows="5" animated aria-label="正在读取歌曲" />
    <ElEmpty v-else-if="loaded && !rows.length" :description="filtered ? '没有符合条件的歌曲' : '曲库里还没有歌曲'"><ElButton v-if="filtered" @click="reset">清除筛选</ElButton></ElEmpty>
    <div v-else class="song-list-wrap"><table class="song-list" :aria-busy="loading" aria-label="歌曲"><thead><tr class="song-table-header"><th scope="col">选择</th><th scope="col">歌曲 / 歌手</th><th scope="col">媒体来源 / 文件</th><th scope="col">音轨信息</th><th scope="col">原唱 / 伴奏</th><th scope="col">最近生成任务</th><th scope="col">操作</th></tr></thead><tbody>
      <tr v-for="row in rows" :key="row.id" class="song-row" :class="{ 'is-selected': all || selected.has(row.id) }">
        <td><input type="checkbox" class="row-check" :aria-label="'选择' + row.title" :checked="all || selected.has(row.id)" :disabled="all" @change="check(row.id, ($event.target as HTMLInputElement).checked)"></td>
        <td class="song-main"><span class="row-cover"><img v-if="row.coverAvailable && !coverErrors.has(row.id)" :src="`/api/v1/metadata/songs/${encodeURIComponent(row.id)}/cover`" :alt="`${row.title}的封面`" loading="lazy" decoding="async" @error="coverErrors.add(row.id)"><span v-else role="img" aria-label="暂无歌曲封面">♫</span></span><div class="song-copy">
          <button type="button" class="song-title" :title="row.relativePath" @click="editor = row">{{ row.title || row.relativePath }}</button>
          <p>{{ row.artists.join('、') || '未知歌手' }}</p></div>
        </td>
        <td class="song-location"><strong>{{ row.sourceName }}</strong><p :title="row.relativePath">{{ row.relativePath }}</p></td>
        <td class="song-tracks">{{ tracks(row) }}<p v-for="stream in row.audio.streams" :key="stream.id">{{ stream.codec?.toUpperCase() || '编码待识别' }} · {{ stream.channels ? `${stream.channels} 声道` : '声道待识别' }}</p></td>
        <td class="song-state"><ElTag :type="rolesSummary(row.audio).tone" effect="light" round size="small">{{ rolesSummary(row.audio).tag }}</ElTag><p v-if="rolesSummary(row.audio).tone === 'danger'" class="inline-error">{{ rolesSummary(row.audio).hint }}</p></td>
        <td class="song-task"><template v-if="latest(row)"><span>{{ taskLabels[latest(row)!.state] || latest(row)!.state }}</span><p v-if="latest(row)!.backend">{{ backendName(latest(row)!.backend!) }}</p><p v-if="latest(row)!.fallbackReason">已自动降级到 CPU</p><p v-if="latest(row)!.error" class="task-error" :title="latest(row)!.error">{{ latest(row)!.error }}</p></template><span v-else>暂无生成任务</span></td>
        <td class="song-actions">
          <ElButton v-if="action(row) === 'generate'" size="small" :aria-label="'为' + row.title + '生成伴奏'" :disabled="busy || !readiness.ready" @click="makePreview([row.id])">生成伴奏</ElButton>
          <ElButton v-else-if="action(row) === 'label'" size="small" type="primary" plain @click="editor = row">标记原唱</ElButton>
          <ElButton v-else-if="action(row) === 'relabel'" size="small" @click="editor = row">修改标记</ElButton>
          <ElButton v-else-if="action(row) === 'listen'" size="small" @click="editor = row">试听伴奏</ElButton>
          <ElButton v-else size="small" @click="editor = row">查看详情</ElButton>
        </td>
      </tr></tbody>
    </table></div>
    <div class="pagination">
      <ElPagination :current-page="page + 1" :page-size="pageSize" :total="total" layout="total, sizes, prev, pager, next, jumper" :page-sizes="[20, 30, 50, 100]" @size-change="resize" prev-text="上一页" next-text="下一页" :disabled="loading" @current-change="go($event - 1)" />
    </div>

    <ElDrawer :model-value="!!editor" :title="editor ? (editor.title || editor.relativePath) : ''" size="min(560px, 100vw)" :append-to-body="false" class="roles-drawer" @close="editor = undefined">
      <p v-if="editor" class="drawer-sub">{{ editor.artists.join('、') || '未知歌手' }} · {{ editor.sourceName }}</p>
      <AudioRolesPanel v-if="editor" :song-id="editor.id" :can-process="readiness.ready" @changed="loadRows" @setup="editor = undefined; emit('setup')" />
    </ElDrawer>
  </div>
</template>

<style scoped>
.songs-tab{display:grid;gap:14px;min-width:0}.ready-alert p{margin:2px 0 8px}.single-banner{display:flex;flex-wrap:wrap;gap:8px 12px;align-items:center;padding:10px 16px;border-radius:var(--sv-radius-md);background:var(--sv-accent-soft);color:var(--sv-text-primary);font-size:var(--sv-font-body)}
.filter-panel{padding:14px 16px;display:grid;gap:12px}.filters{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(0,1fr) minmax(0,.9fr) minmax(0,.8fr) auto;gap:10px;align-items:center}.filters :deep(.el-button){margin:0}.native-select{min-height:32px;padding:4px 8px;border:1px solid var(--sv-border-default);border-radius:var(--sv-radius-sm);background:var(--sv-surface-1);color:var(--sv-text-primary);font:inherit;font-size:var(--sv-font-body);min-width:0;max-width:100%}
.state-chips{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:0;padding:0;border:0;min-width:0}.state-chips legend{float:left;margin-right:4px;padding:0;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.chip{position:relative;display:inline-flex;align-items:center;min-height:30px;padding:0 12px;border:1px solid var(--sv-border-default);border-radius:var(--sv-radius-full);font-size:var(--sv-font-meta);color:var(--sv-text-secondary);cursor:pointer;background:var(--sv-surface-1)}.chip input{position:absolute;opacity:0;inset:0;margin:0;cursor:pointer}.chip:hover{border-color:var(--sv-accent-primary);color:var(--sv-text-primary)}.chip.is-active{border-color:var(--sv-accent-primary);background:var(--sv-accent-soft);color:var(--sv-accent-primary);font-weight:600}.chip:focus-within{outline:2px solid var(--sv-accent-primary);outline-offset:2px}.reset{margin-left:4px}
.selection-bar{position:sticky;top:0;z-index:2;display:flex;flex-wrap:wrap;gap:8px 12px;align-items:center;padding:10px 16px;border:1px solid var(--sv-border-subtle);border-radius:var(--sv-radius-md);background:var(--sv-surface-2)}.selection-bar.has-selection{border-color:var(--sv-accent-primary);background:var(--sv-accent-soft)}.selection-bar :deep(.el-button){margin:0}.page-check{display:inline-flex;gap:8px;align-items:center;font-size:var(--sv-font-body);font-weight:600;color:var(--sv-text-primary)}.page-check input,.row-check{width:16px;height:16px;margin:0;accent-color:var(--sv-accent-primary)}.spacer{flex:1}.device{display:inline-flex;gap:6px;align-items:center;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}
.song-list{margin:0;padding:0;list-style:none;border:1px solid var(--sv-border-subtle);border-radius:var(--sv-radius-lg);background:var(--sv-surface-2);overflow:hidden}.song-row{display:grid;grid-template-columns:24px minmax(0,1fr) 110px 150px 120px;gap:12px;align-items:center;padding:12px 16px;border-bottom:1px solid var(--sv-border-subtle)}.song-row:last-child{border-bottom:0}.song-row.is-selected{background:var(--sv-accent-soft)}.song-main{min-width:0}.song-title{display:block;max-width:100%;padding:0;border:0;background:none;color:var(--sv-text-primary);font:inherit;font-size:var(--sv-font-body);font-weight:600;text-align:left;cursor:pointer;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.song-title:hover{color:var(--sv-accent-primary);text-decoration:underline}.song-main p{margin:2px 0 0;font-size:var(--sv-font-meta);color:var(--sv-text-secondary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.song-tracks{font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.song-state{min-width:0}.song-state p{margin:4px 0 0}.song-actions{display:flex;justify-content:flex-end}.song-actions :deep(.el-button){margin:0}
.pagination{display:flex;justify-content:center}.muted{font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.inline-error{margin:0;font-size:var(--sv-font-meta);color:var(--sv-semantic-danger);overflow-wrap:anywhere}.drawer-sub{margin:-8px 0 16px;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}
@media(max-width:960px){.filters{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.filters>:first-child{grid-column:1/-1}.song-row{grid-template-columns:24px minmax(0,1fr) auto}.song-tracks{display:none}.song-state{grid-column:2}.song-actions{grid-column:3;grid-row:1/3}}
@media(max-width:560px){.filters{grid-template-columns:minmax(0,1fr)}.filters :deep(.el-button){width:100%}.song-row{grid-template-columns:24px minmax(0,1fr);padding:12px}.song-state{grid-column:2}.song-actions{grid-column:2;grid-row:auto;justify-content:flex-start}.selection-bar{padding:10px 12px}.selection-bar>.el-button--primary:last-child{width:100%}.spacer{display:none}}
.song-list{width:100%;border-collapse:collapse}.song-table-header th{text-align:left;font-weight:500}.song-table-header{display:grid;grid-template-columns:24px minmax(180px,1.3fr) minmax(140px,1fr) 120px 140px 140px 100px;gap:12px;padding:12px 16px;background:var(--sv-surface-3);font-size:12px;color:var(--sv-text-secondary)}.song-row{grid-template-columns:24px minmax(180px,1.3fr) minmax(140px,1fr) 120px 140px 140px 100px}.song-list-wrap{overflow-x:auto;border:1px solid var(--sv-border-subtle);border-radius:12px}.song-list,.song-table-header{min-width:1050px}.song-list{border:0;border-radius:0}.song-main{display:flex;gap:10px;align-items:center}.song-copy,.song-location,.song-task{min-width:0}.row-cover{display:grid;place-items:center;width:42px;height:42px;flex-shrink:0;border-radius:7px;background:var(--sv-surface-3);color:var(--sv-accent-primary);overflow:hidden}.row-cover img{width:100%;height:100%;object-fit:cover}.song-location,.song-task{font-size:12px;color:var(--sv-text-secondary)}.song-location strong{font-weight:500}.song-location p,.song-task p,.song-tracks p{margin:3px 0 0;font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.task-error{color:var(--sv-semantic-danger)}.pagination{justify-content:flex-end;overflow-x:auto}
@media(max-width:768px){.song-table-header{display:none}.song-list{min-width:0}.song-row{grid-template-columns:24px minmax(0,1fr);gap:10px}.song-main{grid-column:2}.song-location,.song-tracks,.song-state,.song-task,.song-actions{display:block;grid-column:2;grid-row:auto}.song-tracks{display:flex;flex-wrap:wrap;gap:6px}.song-actions{display:flex;justify-content:flex-start}.pagination{justify-content:flex-start}}
</style>
