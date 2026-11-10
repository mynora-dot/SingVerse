<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive } from 'vue'
import { ElButton, ElIcon } from 'element-plus'
import { ArrowRight, Collection, FolderOpened, Headset, MagicStick, Monitor, Plus, RefreshRight, TopRight, Warning } from '@element-plus/icons-vue'
import SongCover from '../../song-management/components/SongCover.vue'
import { songTitle, songArtists } from '../../metadata/utils'
import { backgroundTasks, type BackgroundTask } from '../../background-tasks/api'
import { statusNames, progressText } from '../../background-tasks/presentation'
import { playlists, type Playlist } from '../../playlists/api'
import type { MediaSong } from '../../media-library/types'
import ServiceHealthCard from '../../system-status/components/ServiceHealthCard.vue'
import { auth, authRequest } from '../../../core/auth/api'
import { songs } from '../../song-management/api/songs'
import { media } from '../../media-library/api/media'
import type { MediaSource } from '../../media-library/types'

type Load<T> = { state: 'loading' | 'ready' | 'error'; value?: T; error?: string }
type Tv = { devices: { revoked: boolean }[]; pending: unknown[] }
let active = true
const turns = new WeakMap<object, number>()
const recent = reactive<Load<MediaSong[]>>({ state: 'loading' })
const collections = reactive<Load<Playlist[]>>({ state: 'loading' })
const tasks = reactive<Load<BackgroundTask[]>>({ state: 'loading' })
const totalSongs = reactive<Load<number>>({ state: 'loading' })
const reviewSongs = reactive<Load<number>>({ state: 'loading' })
const sources = reactive<Load<{ total: number; failed: MediaSource[]; partial: boolean }>>({ state: 'loading' })
const tv = reactive<Load<{ pending: number; active: number }>>({ state: 'loading' })

function count(page: unknown) {
  const value = (page as { totalElements?: unknown } | undefined)?.totalElements
  if (typeof value !== 'number') throw new Error('响应格式不正确')
  return value
}
async function fill<T>(target: Load<T>, task: () => Promise<T>) {
  const turn = (turns.get(target) ?? 0) + 1
  turns.set(target, turn)
  target.state = 'loading'
  target.error = undefined
  try { const value = await task(); if (active && turns.get(target) === turn) { target.value = value; target.state = 'ready' } }
  catch (error) { if (active && turns.get(target) === turn) { target.state = 'error'; target.error = error instanceof Error ? error.message : '读取失败' } }
}
function refreshContent() {
  void fill(recent, async () => (await songs.list({ sourceId: '', q: '', status: 'ALL', page: 0, size: 6 })).items)
  void fill(collections, async () => (await playlists.list()).items.slice(0, 3))
}
function refreshTasks() { return fill(tasks, async () => (await backgroundTasks.list({ view: 'ACTIVE', type: '', status: '', q: '', page: 0 })).items.slice(0, 3)) }
function refresh() {
  refreshContent()
  void refreshTasks()
  void fill(totalSongs, async () => count(await songs.list({ sourceId: '', q: '', status: 'ALL', page: 0, size: 1 })))
  void fill(reviewSongs, async () => count(await songs.list({ sourceId: '', q: '', status: 'REVIEW', page: 0, size: 1 })))
  void fill(sources, async () => {
    const page = await media.list(0, 100)
    return { total: count(page), failed: (page.items ?? []).filter(item => !!item.lastScanError), partial: page.totalElements > (page.items ?? []).length }
  })
  void fill(tv, async () => {
    const data = await authRequest<Tv>('/api/v1/admin/tv-devices')
    if (!Array.isArray(data?.pending) || !Array.isArray(data?.devices)) throw new Error('响应格式不正确')
    return { pending: data.pending.length, active: data.devices.filter(device => !device.revoked).length }
  })
}
function pageVisible() { return document.visibilityState !== 'hidden' }
let timer: ReturnType<typeof setTimeout> | undefined
async function poll() { clearTimeout(timer); if (!active || !pageVisible()) return; await refreshTasks(); if (active && pageVisible()) timer = setTimeout(() => void poll(), 10000) }
function visibility() { clearTimeout(timer); if (pageVisible()) void poll() }
onMounted(() => { refresh(); document.addEventListener('visibilitychange', visibility); timer = setTimeout(() => void poll(), 10000) })
onBeforeUnmount(() => { active = false; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) })

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
  if ((sources.value?.failed.length ?? 0) > 0) list.push({ key: 'scan', level: 'warn', title: `${sources.value!.partial ? '已读取来源中，' : ''}${sources.value!.failed.length} 个媒体库最近扫描失败`, detail: sources.value!.failed.map(item => item.name).join('、'), action: '查看媒体库', to: '/library/media' })
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
    <div class="welcome-heading"><div><p class="eyebrow">YOUR HOME, IN TUNE</p><h1>好歌，在家相聚。</h1><p>让每一首收藏，都为下一次欢唱准备好。</p></div><ElButton :icon="RefreshRight" :loading="todoLoading" @click="refresh">刷新空间</ElButton></div>
    <section class="home-feature" :class="{ collected: (totalSongs.value ?? 0) > 0 }" aria-label="我的家庭曲库">
      <div class="feature-copy"><p class="eyebrow">THE SINGVERSE COLLECTION</p><h2>你的收藏，<br>全家的拿手好歌。</h2><p>从 NAS 到客厅，从一首歌到一场相聚。<br>把喜欢的歌收好，剩下的交给音乐。</p><div class="feature-actions"><RouterLink class="solid-link" to="/library/songs">浏览曲库 <ElIcon><ArrowRight /></ElIcon></RouterLink><RouterLink :to="{ path: '/library/media', query: { add: 'LOCAL' } }">添加歌曲 <ElIcon><Plus /></ElIcon></RouterLink></div></div>
      <div class="collection-art" aria-hidden="true"><div class="sleeve sleeve-back"><span>home<br>sessions.</span><small>VOL. 01 / SING TOGETHER</small></div><div class="vinyl-record"><div class="vinyl-label"><span>SingVerse</span><i /></div></div><div class="sleeve sleeve-front"><span class="sleeve-sun" /><span>家的<br>回响</span><small>A COLLECTION OF GOOD TIMES</small></div></div>
    </section>
    <div class="overview-stats" aria-label="曲库统计"><RouterLink v-for="stat in stats" :key="stat.key" :to="stat.to" class="stat-tile" :class="{ 'is-warn': stat.warn }"><strong v-if="stat.load.state === 'ready'" class="stat-value">{{ (stat.load.value ?? 0).toLocaleString('zh-CN') }}</strong><span v-else-if="stat.load.state === 'loading'" class="stat-value" aria-label="读取中">—</span><span v-else class="stat-error" :title="stat.load.error">读取失败</span><span class="stat-label">{{ stat.label }}<ElIcon><ArrowRight /></ElIcon></span></RouterLink></div>
    <div class="home-content">
      <div class="home-main">
        <section aria-labelledby="recent-title"><div class="home-section-heading"><div><p class="eyebrow">FRESH ON THE SHELF</p><h2 id="recent-title">最近入库</h2></div><RouterLink to="/library/songs">全部歌曲 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
          <div v-if="recent.state === 'error'" class="content-empty"><p>最近入库暂时无法读取</p><ElButton text @click="refreshContent">重新读取</ElButton></div><p v-else-if="recent.state === 'loading'" class="content-empty" role="status">正在翻阅你的收藏…</p>
          <div v-else-if="recent.value?.length" class="recent-shelf"><RouterLink v-for="song in recent.value" :key="song.id" :to="{ path: '/library/songs', query: { detailSongId: song.id } }" class="shelf-song"><SongCover :song="song" /><strong :title="songTitle(song)">{{ songTitle(song) }}</strong><span>{{ songArtists(song).join('、') || '歌手待补充' }}</span></RouterLink></div>
          <div v-else class="first-collection"><span aria-hidden="true">♫</span><div><h3>从第一首喜欢的歌开始</h3><p>连接本地文件夹或 WebDAV，扫描后歌曲就会出现在这里。</p><RouterLink to="/library/media">连接歌曲来源 <ElIcon><ArrowRight /></ElIcon></RouterLink></div></div>
        </section>
        <section class="home-workflow" aria-labelledby="workflow-title"><div class="home-section-heading"><div><p class="eyebrow">MAKE IT READY</p><h2 id="workflow-title">把喜欢的歌，准备好</h2></div></div><nav class="workflow-links" aria-label="准备歌曲的步骤"><RouterLink v-for="(item,index) in shortcuts.slice(0,3)" :key="item.to" :to="item.to"><span class="workflow-number">0{{ index + 1 }}</span><ElIcon><component :is="item.icon" /></ElIcon><strong>{{ item.title }}</strong><p>{{ item.text }}</p><ElIcon class="workflow-arrow"><ArrowRight /></ElIcon></RouterLink></nav></section>
        <section v-if="collections.state === 'ready' && collections.value?.length" aria-labelledby="collection-title"><div class="home-section-heading"><div><p class="eyebrow">FOR YOUR NEXT GATHERING</p><h2 id="collection-title">相聚的歌单</h2></div><RouterLink to="/library/playlists">全部歌单 →</RouterLink></div><div class="home-playlists"><RouterLink v-for="p in collections.value" :key="p.id" to="/library/playlists"><span aria-hidden="true">♫</span><div><strong>{{ p.name }}</strong><small>{{ p.count }} 首歌曲</small></div><ElIcon><ArrowRight /></ElIcon></RouterLink></div></section>
      </div>
      <aside class="home-aside">
        <section class="todo-panel" aria-labelledby="overview-todo-title"><div class="home-section-heading"><h2 id="overview-todo-title">需要你看一眼 <span v-if="todos.length" class="sv-count-pill warn">{{ todos.length }}</span></h2></div><ul v-if="todos.length" class="todo-list"><li v-for="todo in todos" :key="todo.key" class="todo-item"><span class="sv-status-dot" :class="todo.level === 'info' ? 'busy' : todo.level" /><div class="todo-copy"><strong>{{ todo.title }}</strong><p>{{ todo.detail }}</p><RouterLink :to="todo.to" class="todo-action">{{ todo.action }} <ElIcon><ArrowRight /></ElIcon></RouterLink></div></li></ul><p v-else-if="todoLoading" class="content-empty" role="status">正在查看需要处理的事情…</p><p v-else-if="!todoErrors" class="content-empty">一切井然有序，准备好开唱了。</p><p v-if="todoErrors && !todoLoading" class="todo-note" role="alert">有 {{ todoErrors }} 项状态读取失败，待办可能不完整。<ElButton link type="primary" @click="refresh">重新读取</ElButton></p></section>
        <section class="home-tasks" aria-labelledby="home-tasks-title"><div class="home-section-heading"><h2 id="home-tasks-title">后台正在忙</h2><RouterLink to="/system/tasks">任务中心 →</RouterLink></div><p v-if="tasks.state === 'loading' && !tasks.value" class="muted" role="status">正在读取任务…</p><div v-else-if="tasks.state === 'error'" class="muted">暂时无法读取任务 <ElButton link @click="refreshTasks">重试</ElButton></div><p v-else-if="!tasks.value?.length" class="muted">暂时没有进行中的任务。离开页面后，已提交的任务也会继续。</p><RouterLink v-for="task in tasks.value" :key="task.id" :to="'/system/tasks/' + encodeURIComponent(task.id)" class="home-task"><span class="sv-status-dot busy" /><div><strong>{{ task.name }}</strong><small>{{ statusNames[task.status] || task.status }} · {{ progressText(task) }}</small></div><ElIcon><ArrowRight /></ElIcon></RouterLink></section>
        <a class="party-invitation" href="/party/" target="_blank" rel="noopener"><ElIcon><Monitor /></ElIcon><h3>好歌，一起唱。</h3><p>在手机或电视上开唱，<br>让亲友扫码来点歌。</p><span>打开亲友点歌页 <ElIcon><TopRight /></ElIcon></span></a>
        <ServiceHealthCard />
      </aside>
    </div>
  </section>
</template>
<style scoped>
.welcome-heading {
  display: flex;
  gap: 20px;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 30px
}

.welcome-heading h1 {
  font: 32px/1.5 var(--sv-font-editorial);
  margin-top: 8px;
  letter-spacing: 1px
}

.welcome-heading>div>p:last-child {
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin-top: 6px
}

.welcome-heading>.el-button {
  font-size: 12px;
  background: none
}

.home-feature {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 310px;
  background: #e8eddf;
  border-radius: 18px;
  overflow: hidden
}

.home-feature.collected {
  min-height: 228px
}

.collected .feature-copy {
  padding: 24px 32px
}

.collected .feature-copy h2 {
  font-size: 28px;
  margin-top: 8px
}

.collected .feature-copy>p:not(.eyebrow) {
  display: none
}

.collected .feature-actions {
  margin-top: 18px
}

.collected .collection-art {
  min-height: 228px
}

.collected .sleeve {
  width: 162px
}

.collected .sleeve-front {
  top: 70px
}

.collected .sleeve-back {
  top: 20px
}

.collected .vinyl-record {
  width: 190px;
  top: 25px
}

.feature-copy {
  padding: 34px 40px;
  position: relative;
  z-index: 1
}

.feature-copy h2 {
  font: 32px/1.5 var(--sv-font-editorial);
  letter-spacing: 1px;
  margin-top: 15px
}

.feature-copy>p:not(.eyebrow) {
  font-size: 12px;
  color: #566951;
  line-height: 1.9;
  margin-top: 12px
}

.feature-actions {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-top: 24px
}

.feature-actions a {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 12px;
  font-weight: 550
}

.solid-link {
  padding: 10px 18px;
  border-radius: 6px;
  background: var(--sv-accent-primary);
  color: white
}

.solid-link:hover {
  color: white;
  background: var(--sv-accent-muted)
}

.collection-art {
  position: relative;
  min-height: 310px;
  overflow: hidden
}

.sleeve {
  position: absolute;
  width: 200px;
  aspect-ratio: 1;
  box-shadow: 3px 10px 20px #233a2220
}

.sleeve-back {
  background: #c07b5b;
  top: 38px;
  left: 4%;
  transform: rotate(-13deg);
  padding: 24px;
  color: #fff5da
}

.sleeve-back>span {
  font: 31px/.95 var(--sv-font-editorial)
}

.sleeve small {
  position: absolute;
  font-size: 7px;
  bottom: 14px;
  left: 18px;
  letter-spacing: 1.5px
}

.sleeve-front {
  background: #f4edce;
  top: 104px;
  left: 24%;
  transform: rotate(7deg);
  padding: 20px;
  overflow: hidden
}

.sleeve-front>span:not(.sleeve-sun) {
  position: relative;
  font: 37px/1.3 var(--sv-font-editorial);
  color: #34573e;
  letter-spacing: 6px
}

.sleeve-sun {
  position: absolute;
  display: block;
  width: 160px;
  height: 160px;
  border-radius: 50%;
  background: repeating-linear-gradient(0deg, #829875 0 2px, transparent 2px 5px);
  right: -34px;
  top: 14px
}

.vinyl-record {
  position: absolute;
  width: 230px;
  aspect-ratio: 1;
  border-radius: 50%;
  background: repeating-radial-gradient(circle, #223b30 0 1px, #30483a 2px 3px);
  left: 40%;
  top: 54px;
  box-shadow: 5px 15px 22px #233a2230
}

.vinyl-label {
  position: absolute;
  inset: 34%;
  border-radius: 50%;
  background: #c9a16d;
  display: grid;
  place-items: center;
  color: #314b36;
  font: 9px var(--sv-font-editorial)
}

.vinyl-label i {
  position: absolute;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #e7ecdf;
  top: calc(50% - 3px)
}

.vinyl-label>span {
  transform: translateY(-14px)
}

.overview-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  border-bottom: 1px solid var(--sv-border-default);
  padding: 24px 0 26px;
  margin-bottom: 34px
}

.stat-tile {
  display: flex;
  align-items: baseline;
  gap: 15px;
  padding: 0 28px;
  border-right: 1px solid var(--sv-border-default);
  color: var(--sv-text-primary)
}

.stat-tile:first-child {
  padding-left: 0
}

.stat-tile:last-child {
  border: 0
}

.stat-value {
  font-size: 29px;
  font-weight: 500;
  letter-spacing: -1px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap
}

.stat-label {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--sv-text-secondary)
}

.stat-label .el-icon {
  font-size: 13px
}

.stat-tile.is-warn .stat-value {
  color: var(--sv-semantic-warn)
}

.stat-error {
  font-size: 12px;
  color: var(--sv-semantic-danger)
}

.home-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 38px
}

.home-main {
  display: grid;
  gap: 34px;
  align-content: start
}

.home-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px
}

.home-section-heading h2 {
  font-size: 18px;
  margin-top: 4px
}

.home-section-heading a {
  font-size: 11px;
  display: flex;
  gap: 8px;
  align-items: center;
  white-space: nowrap
}

.recent-shelf {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px 18px
}

.shelf-song {
  min-width: 0;
  display: grid;
  gap: 5px;
  color: var(--sv-text-primary)
}

.shelf-song :deep(.song-cover) {
  width: 100%;
  height: auto;
  aspect-ratio: 1;
  border-radius: 9px;
  margin-bottom: 5px;
  box-shadow: 0 5px 15px #283b2910
}

.shelf-song strong {
  font-size: 13px;
  font-weight: 550;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden
}

.shelf-song>span {
  font-size: 11px;
  color: var(--sv-text-secondary);
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden
}

.home-aside {
  display: grid;
  gap: 26px;
  align-content: start;
  border-left: 1px solid var(--sv-border-subtle);
  padding-left: 28px
}

.home-aside .home-section-heading h2 {
  font-size: 15px
}

.todo-list {
  list-style: none;
  padding: 0;
  margin: 0
}

.todo-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 15px 0;
  border-bottom: 1px solid var(--sv-border-subtle)
}

.todo-item:first-child {
  padding-top: 0
}

.todo-item .sv-status-dot {
  margin-top: 7px
}

.todo-copy {
  min-width: 0
}

.todo-copy strong {
  font-size: 12px;
  font-weight: 550
}

.todo-copy p {
  font-size: 11px;
  color: var(--sv-text-secondary);
  margin-top: 5px;
  line-height: 1.8;
  overflow-wrap: anywhere
}

.todo-action {
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-weight: 550
}

.todo-note {
  padding-top: 12px;
  font-size: 12px;
  color: var(--sv-semantic-warn)
}

.content-empty {
  padding: 24px 0;
  color: var(--sv-text-secondary);
  font-size: 12px
}

.first-collection {
  display: flex;
  align-items: center;
  gap: 26px;
  padding: 32px;
  background: var(--sv-surface-2);
  border: 1px dashed var(--sv-border-default);
  border-radius: 12px
}

.first-collection>span {
  font-size: 50px;
  font-family: serif;
  color: #a7b39b
}

.first-collection h3 {
  font-size: 16px
}

.first-collection p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin: 8px 0 14px
}

.first-collection a {
  font-size: 12px
}

.workflow-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--sv-border-subtle);
  border-radius: 12px;
  background: var(--sv-surface-2)
}

.workflow-links>a {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 24px 20px;
  border-right: 1px solid var(--sv-border-subtle)
}

.workflow-links>a:last-child {
  border: 0
}

.workflow-number {
  position: absolute;
  top: 20px;
  right: 18px;
  font: 13px var(--sv-font-editorial);
  color: var(--sv-border-strong)
}

.workflow-links>a>.el-icon {
  font-size: 22px;
  margin-bottom: 22px
}

.workflow-links strong {
  font-size: 13px;
  font-weight: 550
}

.workflow-links p {
  font-size: 10px;
  color: var(--sv-text-secondary);
  margin-top: 5px
}

.workflow-links>a>.workflow-arrow {
  position: absolute;
  right: 16px;
  bottom: 20px;
  font-size: 13px;
  margin: 0
}

.home-task {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  border-bottom: 1px solid var(--sv-border-subtle);
  padding: 12px 0;
  font-size: 12px
}

.home-task .sv-status-dot {
  margin-top: 7px
}

.home-task>div {
  flex: 1;
  min-width: 0
}

.home-task strong {
  display: block;
  font-size: 12px;
  font-weight: 550;
  overflow-wrap: anywhere
}

.home-task small {
  display: block;
  margin-top: 4px;
  font-size: 10px;
  color: var(--sv-text-secondary)
}

.home-tasks>.muted {
  font-size: 11px
}

.party-invitation {
  padding: 24px;
  background: #efeee3;
  border-radius: 12px;
  color: var(--sv-text-primary)
}

.party-invitation>.el-icon {
  font-size: 26px
}

.party-invitation h3 {
  font: 22px var(--sv-font-editorial);
  margin: 16px 0 10px
}

.party-invitation p {
  font-size: 12px;
  color: var(--sv-text-secondary)
}

.party-invitation>span {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  margin-top: 18px
}

.home-playlists {
  display: grid;
  gap: 10px
}

.home-playlists>a {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 14px;
  border: 1px solid var(--sv-border-subtle);
  border-radius: 10px;
  background: var(--sv-surface-2)
}

.home-playlists>a>span {
  font-size: 26px;
  background: var(--sv-accent-soft);
  padding: 2px 15px;
  border-radius: 6px
}

.home-playlists>a>div {
  flex: 1;
  min-width: 0
}

.home-playlists strong {
  font-size: 13px
}

.home-playlists small {
  display: block;
  font-size: 11px;
  color: var(--sv-text-secondary)
}

@media(min-width:1400px) {
  .recent-shelf {
    grid-template-columns: repeat(6, minmax(0, 1fr))
  }

  .home-content {
    grid-template-columns: minmax(0, 1fr) 310px
  }

  .shelf-song :deep(.record-grooves>span) {
    font-size: 18px
  }
}

@media(max-width:1100px) {
  .home-content {
    grid-template-columns: minmax(0, 1fr) 260px;
    gap: 28px
  }

  .home-aside {
    padding-left: 24px
  }

  .stat-tile {
    padding: 0 18px;
    gap: 10px;
    flex-wrap: wrap
  }

  .stat-label {
    gap: 5px
  }

  .sleeve {
    width: 165px
  }

  .sleeve-front {
    top: 124px
  }

  .vinyl-record {
    width: 200px
  }

  .feature-copy {
    padding: 30px
  }

  .feature-copy h2 {
    font-size: 29px
  }

  .workflow-links>a {
    padding: 20px 14px
  }

  .workflow-links p {
    max-width: 100px
  }

  .workflow-links>a>.workflow-arrow {
    display: none
  }
}

@media(max-width:900px) {
  .home-content {
    grid-template-columns: minmax(0, 1fr)
  }

  .home-aside {
    grid-template-columns: 1fr 1fr;
    border: 0;
    padding: 0;
    gap: 24px
  }

  .home-aside .todo-panel {
    grid-row: span 2
  }

  .home-aside :deep(.health-card) {
    grid-column: 1/-1
  }

  .recent-shelf {
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 12px
  }

  .shelf-song strong {
    font-size: 11px
  }

  .shelf-song>span {
    font-size: 10px
  }

  .collection-art {
    min-height: 310px
  }

  .sleeve-back {
    left: -1%
  }

  .sleeve-front {
    left: 18%
  }

  .vinyl-record {
    left: 32%
  }
}

@media(max-width:650px) {
  .welcome-heading {
    margin: 0 0 24px;
    align-items: flex-start
  }

  .welcome-heading h1 {
    font-size: 27px
  }

  .welcome-heading .eyebrow {
    font-size: 8px
  }

  .welcome-heading>div>p:last-child {
    font-size: 11px
  }

  .welcome-heading>.el-button {
    padding: 6px 10px;
    min-height: 30px;
    font-size: 10px;
    margin-top: 3px
  }

  .home-feature {
    grid-template-columns: 1fr;
    min-height: 0
  }

  .home-feature.collected {
    min-height: 0
  }

  .collected .feature-copy {
    padding: 24px
  }

  .collected .feature-copy h2 {
    font-size: 25px
  }

  .collected .collection-art {
    min-height: 0
  }

  .feature-copy {
    padding: 26px
  }

  .feature-copy h2 {
    font-size: 28px
  }

  .feature-copy>.eyebrow {
    font-size: 8px
  }

  .feature-copy>p:not(.eyebrow) {
    font-size: 11px
  }

  .collection-art {
    position: absolute;
    width: 170px;
    height: 220px;
    min-height: 0;
    right: -65px;
    bottom: 12px;
    opacity: .22
  }

  .sleeve {
    width: 145px
  }

  .sleeve-back {
    left: 0;
    top: 6px
  }

  .sleeve-front {
    top: 85px;
    left: 0
  }

  .vinyl-record {
    width: 145px;
    left: 45px;
    top: 50px
  }

  .feature-actions {
    gap: 22px
  }

  .overview-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding: 14px 0;
    gap: 12px 0;
    margin-bottom: 26px
  }

  .stat-tile {
    padding: 8px 16px;
    gap: 10px;
    flex-wrap: nowrap
  }

  .stat-tile:nth-child(odd) {
    padding-left: 0
  }

  .stat-tile:nth-child(even) {
    border-right: 0
  }

  .stat-value {
    font-size: 25px
  }

  .stat-label {
    font-size: 10px;
    gap: 5px
  }

  .home-section-heading h2 {
    font-size: 18px
  }

  .home-section-heading .eyebrow {
    font-size: 8px
  }

  .home-section-heading a {
    font-size: 10px
  }

  .recent-shelf {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 18px 12px
  }

  .home-aside {
    grid-template-columns: 1fr;
    gap: 26px
  }

  .home-aside .todo-panel {
    grid-row: auto
  }

  .workflow-links>a {
    padding: 18px 12px
  }

  .workflow-links strong {
    font-size: 12px
  }

  .workflow-links p {
    font-size: 10px
  }

  .workflow-number {
    right: 10px;
    top: 16px
  }

  .first-collection {
    padding: 24px 20px;
    gap: 18px
  }

  .first-collection h3 {
    font-size: 14px
  }

  .home-content {
    gap: 32px
  }

  .home-main {
    gap: 28px
  }
}
</style>
