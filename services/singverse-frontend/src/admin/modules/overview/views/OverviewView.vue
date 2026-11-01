<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import { ElButton, ElIcon } from 'element-plus'
import { ArrowRight, Collection, FolderOpened, Headset, MagicStick, Monitor, Plus, RefreshRight, TopRight, Warning } from '@element-plus/icons-vue'
import ServiceHealthCard from '../../system-status/components/ServiceHealthCard.vue'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { auth, authRequest } from '../../../core/auth/api'
import { songs } from '../../song-management/api/songs'
import { media } from '../../media-library/api/media'
import type { MediaSource } from '../../media-library/types'

type Load<T> = { state: 'loading' | 'ready' | 'error'; value?: T; error?: string }
type Tv = { devices: { revoked: boolean }[]; pending: unknown[] }
const totalSongs = reactive<Load<number>>({ state: 'loading' })
const reviewSongs = reactive<Load<number>>({ state: 'loading' })
const sources = reactive<Load<{ total: number; failed: MediaSource[] }>>({ state: 'loading' })
const tv = reactive<Load<{ pending: number; active: number }>>({ state: 'loading' })

function count(page: unknown) {
  const value = (page as { totalElements?: unknown } | undefined)?.totalElements
  if (typeof value !== 'number') throw new Error('响应格式不正确')
  return value
}
async function fill<T>(target: Load<T>, task: () => Promise<T>) {
  target.state = 'loading'
  target.error = undefined
  try { target.value = await task(); target.state = 'ready' }
  catch (error) { target.state = 'error'; target.error = error instanceof Error ? error.message : '读取失败' }
}
function refresh() {
  void fill(totalSongs, async () => count(await songs.list({ sourceId: '', q: '', status: 'ALL', page: 0, size: 1 })))
  void fill(reviewSongs, async () => count(await songs.list({ sourceId: '', q: '', status: 'REVIEW', page: 0, size: 1 })))
  void fill(sources, async () => {
    const page = await media.list(0, 100)
    return { total: count(page), failed: (page.items ?? []).filter(item => !!item.lastScanError) }
  })
  void fill(tv, async () => {
    const data = await authRequest<Tv>('/api/v1/admin/tv-devices')
    if (!Array.isArray(data?.pending) || !Array.isArray(data?.devices)) throw new Error('响应格式不正确')
    return { pending: data.pending.length, active: data.devices.filter(device => !device.revoked).length }
  })
}
onMounted(refresh)

const stats = computed(() => [
  { key: 'songs', label: '歌曲总数', load: totalSongs, to: '/library/songs', icon: Headset },
  { key: 'review', label: '待复核歌曲', load: reviewSongs, to: { path: '/library/songs', query: { status: 'REVIEW' } }, icon: Warning, warn: (reviewSongs.value ?? 0) > 0 },
  { key: 'sources', label: '媒体库', load: { state: sources.state, value: sources.value?.total, error: sources.error }, to: '/library/media', icon: FolderOpened },
  { key: 'tv', label: '已授权电视', load: { state: tv.state, value: tv.value?.active, error: tv.error }, to: '/system/tv-devices', icon: Monitor },
])

type Todo = { key: string; level: 'danger' | 'warn' | 'info'; title: string; detail: string; action: string; to: string | { path: string; query: Record<string, string> } }
const todos = computed(() => {
  const list: Todo[] = []
  if (auth.account?.defaultPassword) list.push({ key: 'password', level: 'danger', title: '仍在使用默认密码', detail: '任何知道默认密码的人都能登录管理中心。', action: '修改密码', to: '/system/parameters' })
  if ((tv.value?.pending ?? 0) > 0) list.push({ key: 'tv', level: 'warn', title: `${tv.value!.pending} 台电视等待授权`, detail: '核对电视上的授权码后批准，申请会在有效期后失效。', action: '去批准', to: '/system/tv-devices' })
  if (sources.state === 'ready' && sources.value!.total === 0) list.push({ key: 'no-source', level: 'info', title: '还没有添加媒体库', detail: '添加本地目录或 WebDAV 并扫描后，歌曲才会出现在曲库中。', action: '添加媒体库', to: { path: '/library/media', query: { add: 'LOCAL' } } })
  if ((sources.value?.failed.length ?? 0) > 0) list.push({ key: 'scan', level: 'warn', title: `${sources.value!.failed.length} 个媒体库最近扫描失败`, detail: sources.value!.failed.map(item => item.name).join('、'), action: '查看媒体库', to: '/library/media' })
  if ((reviewSongs.value ?? 0) > 0) list.push({ key: 'review', level: 'info', title: `${reviewSongs.value} 首歌曲待复核`, detail: '文件名未能完整识别歌名或歌手，复核后才能被准确搜索。', action: '去复核', to: { path: '/library/songs', query: { status: 'REVIEW' } } })
  if (auth.account && !auth.account.mfaEnabled) list.push({ key: 'mfa', level: 'info', title: '未开启二次验证', detail: '开启后登录需额外输入验证器中的动态码。', action: '去开启', to: '/system/parameters' })
  return list
})
const todoLoading = computed(() => [reviewSongs, sources, tv].some(item => item.state === 'loading'))
const todoErrors = computed(() => [reviewSongs.state, sources.state, tv.state].filter(state => state === 'error').length)

const shortcuts = [
  { icon: FolderOpened, title: '扫描媒体库', text: '添加来源或重新扫描', to: '/library/media' },
  { icon: MagicStick, title: '整理元数据', text: '匹配平台信息并审核', to: '/library/songs/metadata' },
  { icon: Collection, title: '管理歌单', text: '手动选歌或 AI 推荐', to: '/library/playlists' },
  { icon: Monitor, title: '电视设备', text: '批准或撤销电视访问', to: '/system/tv-devices' },
]
</script>

<template>
  <section class="overview-page">
    <PageHeader title="概览" description="曲库、来源与设备的当前状态，以及需要你处理的事项。">
      <template #actions>
        <ElButton :icon="RefreshRight" :loading="todoLoading" @click="refresh">刷新</ElButton>
        <RouterLink v-slot="{ navigate }" :to="{ path: '/library/media', query: { add: 'LOCAL' } }" custom><ElButton type="primary" :icon="Plus" @click="navigate">添加媒体库</ElButton></RouterLink>
      </template>
    </PageHeader>

    <div class="overview-stats" aria-label="曲库统计">
      <RouterLink v-for="stat in stats" :key="stat.key" :to="stat.to" class="stat-tile" :class="{ 'is-warn': stat.warn }">
        <span class="stat-label"><ElIcon><component :is="stat.icon" /></ElIcon>{{ stat.label }}</span>
        <strong v-if="stat.load.state === 'ready'" class="stat-value">{{ (stat.load.value ?? 0).toLocaleString('zh-CN') }}</strong>
        <span v-else-if="stat.load.state === 'loading'" class="stat-value is-loading" aria-label="读取中">—</span>
        <span v-else class="stat-error" :title="stat.load.error">读取失败</span>
      </RouterLink>
    </div>

    <div class="overview-columns">
      <section class="sv-panel overview-todos" aria-labelledby="overview-todo-title">
        <div class="sv-panel-head"><h2 id="overview-todo-title">需要处理 <span v-if="todos.length" class="sv-count-pill warn">{{ todos.length }}</span></h2></div>
        <ul v-if="todos.length" class="todo-list">
          <li v-for="todo in todos" :key="todo.key" class="todo-item">
            <span class="sv-status-dot" :class="todo.level === 'info' ? 'busy' : todo.level" aria-hidden="true" />
            <div class="todo-copy"><strong>{{ todo.title }}</strong><p>{{ todo.detail }}</p></div>
            <RouterLink :to="todo.to" class="todo-action">{{ todo.action }}<ElIcon><ArrowRight /></ElIcon></RouterLink>
          </li>
        </ul>
        <p v-else-if="todoLoading" class="todo-empty" role="status">正在检查曲库与设备状态…</p>
        <p v-else-if="!todoErrors" class="todo-empty">暂时没有需要处理的事项。</p>
        <p v-if="todoErrors && !todoLoading" class="todo-note" role="alert">有 {{ todoErrors }} 项状态读取失败，待办可能不完整。<ElButton link type="primary" @click="refresh">重新读取</ElButton></p>
      </section>

      <div class="overview-side">
        <ServiceHealthCard />
        <section class="sv-panel" aria-labelledby="overview-shortcuts-title">
          <div class="sv-panel-head"><h2 id="overview-shortcuts-title">常用操作</h2></div>
          <nav class="shortcut-list" aria-label="常用操作">
            <RouterLink v-for="item in shortcuts" :key="item.to" :to="item.to" class="shortcut-row"><span class="shortcut-icon"><ElIcon><component :is="item.icon" /></ElIcon></span><span><strong>{{ item.title }}</strong><small>{{ item.text }}</small></span><ElIcon class="shortcut-arrow"><ArrowRight /></ElIcon></RouterLink>
            <a class="shortcut-row" href="/party/" target="_blank" rel="noopener"><span class="shortcut-icon"><ElIcon><TopRight /></ElIcon></span><span><strong>亲友点歌页</strong><small>新窗口打开 /party/</small></span><ElIcon class="shortcut-arrow"><ArrowRight /></ElIcon></a>
          </nav>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.overview-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
.stat-tile { display: grid; gap: 6px; padding: 16px 18px; color: var(--sv-text-primary); background: var(--sv-surface-2); border: 1px solid var(--sv-border-subtle); border-radius: var(--sv-radius-lg); box-shadow: var(--sv-shadow-sm); transition: border-color .14s, box-shadow .14s; }
.stat-tile:hover { color: var(--sv-text-primary); border-color: var(--sv-border-default); box-shadow: var(--sv-shadow-md); }
.stat-label { display: flex; gap: 6px; align-items: center; color: var(--sv-text-secondary); font-size: var(--sv-font-meta); }
.stat-label .el-icon { color: var(--sv-text-tertiary); }
.stat-value { font-size: 26px; font-weight: 650; line-height: 1.2; font-variant-numeric: tabular-nums; }
.stat-value.is-loading { color: var(--sv-border-strong); }
.stat-tile.is-warn .stat-value { color: var(--sv-semantic-warn); }
.stat-error { color: var(--sv-semantic-danger); font-size: var(--sv-font-meta); line-height: 31px; }
.overview-columns { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(300px, 1fr); gap: 20px; align-items: start; }
.overview-side { display: grid; gap: 20px; }
.overview-todos h2 { display: flex; gap: 8px; align-items: center; }
.todo-list { margin: 0; padding: 0; list-style: none; }
.todo-item { display: flex; gap: 12px; align-items: flex-start; padding: 14px 20px; border-bottom: 1px solid var(--sv-border-subtle); }
.todo-item:last-child { border-bottom: 0; }
.todo-item .sv-status-dot { margin-top: 7px; }
.todo-copy { flex: 1; min-width: 0; }
.todo-copy strong { font-size: var(--sv-font-body); font-weight: 600; }
.todo-copy p { margin-top: 2px; color: var(--sv-text-secondary); font-size: var(--sv-font-meta); overflow-wrap: anywhere; }
.todo-action { display: inline-flex; flex: none; gap: 4px; align-items: center; padding: 4px 0; font-size: var(--sv-font-meta); font-weight: 600; white-space: nowrap; }
.todo-empty { padding: 28px 20px; color: var(--sv-text-tertiary); font-size: var(--sv-font-meta); text-align: center; }
.todo-note { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 10px 20px; color: var(--sv-semantic-warn); background: var(--sv-semantic-warn-soft); border-top: 1px solid var(--sv-border-subtle); font-size: var(--sv-font-meta); border-radius: 0 0 var(--sv-radius-lg) var(--sv-radius-lg); }
.shortcut-list { display: grid; padding: 6px; }
.shortcut-row { display: flex; gap: 12px; align-items: center; padding: 10px 12px; color: var(--sv-text-primary); border-radius: var(--sv-radius-md); }
.shortcut-row:hover { color: var(--sv-text-primary); background: var(--sv-surface-3); }
.shortcut-row > span:nth-child(2) { flex: 1; min-width: 0; }
.shortcut-row strong { display: block; font-size: var(--sv-font-meta); font-weight: 600; }
.shortcut-row small { display: block; color: var(--sv-text-tertiary); font-size: var(--sv-font-label); }
.shortcut-icon { display: grid; flex: none; place-items: center; width: 32px; height: 32px; color: var(--sv-accent-primary); background: var(--sv-accent-soft); border-radius: var(--sv-radius-md); }
.shortcut-arrow { color: var(--sv-text-tertiary); }
@media (max-width: 1100px) { .overview-columns { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 768px) { .overview-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .stat-value { font-size: 22px; } .todo-item { flex-wrap: wrap; padding: 14px 16px; } .todo-action { margin-left: 20px; } }
</style>
