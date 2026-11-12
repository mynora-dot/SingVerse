<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive } from 'vue'
import { ElAlert, ElButton, ElIcon } from 'element-plus'
import { ArrowRight, ChatDotRound, Collection, Cpu, FolderOpened, Headset, MagicStick, Monitor, Plus, RefreshRight, TopRight } from '@element-plus/icons-vue'
import SongCover from '../../song-management/components/SongCover.vue'
import StatBlock from '../../../shared/ui/StatBlock.vue'
import EmptyState from '../../../shared/ui/EmptyState.vue'
import { songTitle, songArtists } from '../../metadata/utils'
import { backgroundTasks, type BackgroundTask } from '../../background-tasks/api'
import { statusNames, progressText } from '../../background-tasks/presentation'
import ServiceHealthCard from '../../system-status/components/ServiceHealthCard.vue'
import { auth } from '../../../core/auth/api'
import { loadOverview, type AdminOverview } from '../api/overview'

type Load<T> = { state: 'loading' | 'ready' | 'error'; value?: T; error?: string }
let active = true
const turns = new WeakMap<object, number>()
const data = reactive<Load<AdminOverview>>({ state: 'loading' })
const tasks = reactive<Load<BackgroundTask[]>>({ state: 'loading' })

async function fill<T>(target: Load<T>, task: () => Promise<T>) {
  const turn = (turns.get(target) ?? 0) + 1
  turns.set(target, turn)
  target.state = 'loading'
  target.error = undefined
  try { const value = await task(); if (active && turns.get(target) === turn) { target.value = value; target.state = 'ready' } }
  catch (error) { if (active && turns.get(target) === turn) { target.state = 'error'; target.error = error instanceof Error ? error.message : '读取失败' } }
}
function refreshOverview() { return fill(data, loadOverview) }
function refreshTasks() { return fill(tasks, async () => (await backgroundTasks.list({ view: 'ACTIVE', type: '', status: '', q: '', page: 0 })).items.slice(0, 3)) }
function refresh() { void refreshOverview(); void refreshTasks() }
function pageVisible() { return document.visibilityState !== 'hidden' }
let timer: ReturnType<typeof setTimeout> | undefined
async function poll() { clearTimeout(timer); if (!active || !pageVisible()) return; await refreshTasks(); if (active && pageVisible()) timer = setTimeout(() => void poll(), 10000) }
function visibility() { clearTimeout(timer); if (pageVisible()) void poll() }
onMounted(() => { refresh(); document.addEventListener('visibilitychange', visibility); timer = setTimeout(() => void poll(), 10000) })
onBeforeUnmount(() => { active = false; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) })

const ov = computed(() => data.value)
function ok(key: keyof AdminOverview) {
  const d = ov.value
  if (!d || data.state === 'loading') return false
  if (key in d.errors) return false
  return d[key] !== null && d[key] !== undefined
}
const fmt = (n: number) => n.toLocaleString('zh-CN')

type Stat = { key: string; label: string; to: string; tone: 'default' | 'highlight' | 'warn'; value: string; hint?: string }
const stats = computed<Stat[]>(() => {
  const loading = data.state === 'loading'
  const lib = ok('library') ? ov.value!.library! : null
  const tv = ok('tv') ? ov.value!.tv! : null
  const cell = (available: boolean, n: number | undefined, hint?: string) => ({
    value: loading ? '—' : available ? fmt(n ?? 0) : '—',
    hint: loading ? undefined : available ? hint : '读取失败',
  })
  return [
    { key: 'total', label: '文件总数', to: '/library/songs?lens=songs', tone: 'default', ...cell(!!lib, lib?.totalSongs, lib ? `正式歌曲 ${fmt(lib.formalSongs)} 首` : undefined) },
    { key: 'formal', label: '正式歌曲', to: '/library/songs?lens=formal', tone: 'default', ...cell(!!lib, lib?.formalSongs, lib ? `${fmt(lib.formalVariants)} 个媒体版本` : undefined) },
    { key: 'review', label: '待复核', to: '/library/songs?status=REVIEW', tone: (lib?.reviewSongs ?? 0) > 0 ? 'warn' : 'default', ...cell(!!lib, lib?.reviewSongs) },
    { key: 'tv', label: '已授权电视', to: '/system/tv-devices', tone: 'default', ...cell(!!tv, tv?.activeDevices, tv && tv.pending > 0 ? `${tv.pending} 台等待批准` : undefined) },
  ]
})

type Todo = { key: string; level: 'danger' | 'warn' | 'info'; title: string; detail: string; action: string; to: string }
const todos = computed<Todo[]>(() => {
  const list: Todo[] = []
  const d = ov.value
  if (auth.account?.defaultPassword) list.push({ key: 'password', level: 'danger', title: '仍在使用默认密码', detail: '任何知道默认密码的人都能登录管理中心。', action: '修改密码', to: '/system/parameters' })
  const tv = ok('tv') ? d!.tv! : null
  if (tv && tv.pending > 0) list.push({ key: 'tv', level: 'warn', title: `${tv.pending} 台电视等待授权`, detail: '核对电视上的六位授权码后批准，申请会在有效期后失效。', action: '去批准', to: '/system/tv-devices' })
  const src = ok('sources') ? d!.sources! : null
  if (src && src.total === 0) list.push({ key: 'no-source', level: 'info', title: '还没有添加媒体库', detail: '添加本地目录或 WebDAV 并扫描后，歌曲才会出现在曲库中。', action: '添加媒体库', to: '/library/media?add=LOCAL' })
  if (src && src.failed.length > 0) list.push({ key: 'scan', level: 'warn', title: `${src.failed.length} 个媒体库最近扫描失败`, detail: src.failed.map(item => item.name).join('、'), action: '查看媒体库', to: '/library/media' })
  const lib = ok('library') ? d!.library! : null
  if (lib && lib.reviewSongs > 0) list.push({ key: 'review', level: 'info', title: `${fmt(lib.reviewSongs)} 首歌曲待复核`, detail: '文件名未能完整识别歌名或歌手，复核后才能被准确搜索。', action: '去复核', to: '/library/songs?status=REVIEW' })
  const rev = ok('review') ? d!.review! : null
  if (rev?.metadataBatch && rev.metadataBatch.review > 0) list.push({ key: 'metadata', level: 'info', title: `${fmt(rev.metadataBatch.review)} 首平台匹配待审核`, detail: '核对歌名、歌手与封面后确认应用，未确认前不会改动曲库。', action: '去审核', to: `/library/songs/metadata?batch=${rev.metadataBatch.id}` })
  if (rev && rev.classification.reviewItems > 0) list.push({ key: 'classification', level: 'info', title: `${fmt(rev.classification.reviewItems)} 首分类建议待确认`, detail: '查看 AI 给出的分类与标签，确认后才会写入歌曲。', action: '去确认', to: rev.classification.latestTaskId ? `/library/classification?task=${rev.classification.latestTaskId}` : '/library/classification' })
  if (rev && rev.missingFeedbackPending > 0) list.push({ key: 'missing', level: 'info', title: `${fmt(rev.missingFeedbackPending)} 条缺歌心愿待处理`, detail: '亲友在点歌页留下的缺歌反馈，可据此补充曲库。', action: '查看心愿', to: '/library/missing-feedback' })
  if (auth.account && !auth.account.mfaEnabled) list.push({ key: 'mfa', level: 'info', title: '未开启二次验证', detail: '开启后登录需额外输入验证器中的动态码。', action: '去开启', to: '/system/parameters' })
  return list
})
const todoLoading = computed(() => data.state === 'loading')
const todoSections = ['library', 'sources', 'review', 'tv'] as const
const todoErrors = computed(() => data.state === 'loading' ? 0 : todoSections.filter(key => !ok(key)).length)

const recentSongs = computed(() => ok('recentSongs') ? (ov.value!.recentSongs ?? []) : null)
const homePlaylists = computed(() => ok('playlists') ? (ov.value!.playlists ?? []) : null)
const readiness = computed(() => ok('readiness') ? ov.value!.readiness! : null)
const playback = computed(() => ok('playback30d') ? ov.value!.playback30d! : null)

const shortcuts = [
  { icon: FolderOpened, title: '扫描媒体库', text: '添加来源或重新扫描', to: '/library/media' },
  { icon: MagicStick, title: '整理元数据', text: '匹配平台信息并审核', to: '/library/songs/metadata' },
  { icon: Monitor, title: '连接电视', text: '批准或撤销电视访问', to: '/system/tv-devices' },
]
</script>

<template>
  <section class="overview-page">
    <h1 class="sr-only">曲库概览</h1>

    <ElAlert v-if="data.state === 'error'" class="overview-error" type="warning" :closable="false" show-icon :title="data.value ? '曲库概览更新失败，正在显示上次读取的结果' : '曲库概览暂时无法读取'">
      <span>{{ data.error }}</span><ElButton link type="primary" @click="refreshOverview">重新读取</ElButton>
    </ElAlert>

    <section class="home-feature" aria-label="我的家庭曲库">
      <div class="feature-copy">
        <h2>我的家庭曲库</h2>
        <p>连接 NAS 上的歌曲，整理好后在电视或手机上开唱</p>
      </div>
      <div class="feature-actions">
        <RouterLink class="solid-link" to="/library/songs?lens=songs">浏览曲库 <ElIcon><ArrowRight /></ElIcon></RouterLink>
        <RouterLink class="secondary-link" to="/library/media?add=LOCAL">添加歌曲 <ElIcon><Plus /></ElIcon></RouterLink>
        <ElButton class="overview-refresh" text :icon="RefreshRight" :loading="todoLoading" aria-label="刷新曲库概览" @click="refresh">刷新</ElButton>
      </div>
    </section>

    <div class="overview-stats" aria-label="曲库统计">
      <StatBlock v-for="stat in stats" :key="stat.key" :label="stat.label" :to="stat.to" :tone="stat.tone" :value="stat.value" :hint="stat.hint" />
    </div>

    <div class="readiness-row" aria-label="计算与 AI 就绪状态">
      <template v-if="readiness">
        <RouterLink v-if="!readiness.compute.configured" class="readiness-chip warn" to="/system/compute"><ElIcon><Cpu /></ElIcon> 计算节点未配置 · 去设置 <ElIcon><ArrowRight /></ElIcon></RouterLink>
        <span v-else class="readiness-chip ok"><ElIcon><Cpu /></ElIcon> 计算节点 · {{ readiness.compute.mode || '已配置' }}</span>
        <RouterLink v-if="!readiness.ai.configured" class="readiness-chip warn" to="/system/ai"><ElIcon><ChatDotRound /></ElIcon> AI 未配置 · 去设置 <ElIcon><ArrowRight /></ElIcon></RouterLink>
        <span v-else class="readiness-chip ok"><ElIcon><ChatDotRound /></ElIcon> AI 已启用 {{ readiness.ai.enabledFeatures.length }} 项能力</span>
      </template>
      <span v-else-if="data.state !== 'loading'" class="readiness-chip muted">节点与 AI 状态读取失败</span>
    </div>

    <div class="home-content">
      <div class="home-main">
        <section aria-labelledby="recent-title">
          <div class="home-section-heading"><div><h2 id="recent-title">最近入库</h2></div><RouterLink to="/library/songs?lens=songs">全部歌曲 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
          <p v-if="data.state === 'loading'" class="content-empty" role="status">正在读取最近入库…</p>
          <div v-else-if="!ok('recentSongs')" class="content-empty"><p>最近入库暂时无法读取</p><ElButton text @click="refreshOverview">重新读取</ElButton></div>
          <div v-else-if="recentSongs && recentSongs.length" class="recent-shelf">
            <RouterLink v-for="song in recentSongs" :key="song.id" :to="`/library/songs?lens=songs&detailSongId=${song.id}`" class="shelf-song">
              <SongCover :song="song" /><strong :title="songTitle(song)">{{ songTitle(song) }}</strong><span>{{ songArtists(song).join('、') || '歌手待补充' }}</span>
            </RouterLink>
          </div>
          <EmptyState v-else title="从第一首喜欢的歌开始" description="连接本地文件夹或 WebDAV，扫描后歌曲就会出现在这里。" :icon="Headset">
            <template #action><RouterLink class="solid-link" to="/library/media">连接歌曲来源 <ElIcon><ArrowRight /></ElIcon></RouterLink></template>
          </EmptyState>
        </section>

        <section class="home-workflow" aria-labelledby="workflow-title">
          <div class="home-section-heading"><div><h2 id="workflow-title">曲库管理</h2></div></div>
          <nav class="workflow-links" aria-label="准备歌曲的步骤">
            <RouterLink v-for="(item, index) in shortcuts" :key="item.to" :to="item.to">
              <span class="workflow-number">0{{ index + 1 }}</span><ElIcon><component :is="item.icon" /></ElIcon><strong>{{ item.title }}</strong><p>{{ item.text }}</p><ElIcon class="workflow-arrow"><ArrowRight /></ElIcon>
            </RouterLink>
          </nav>
        </section>

        <section v-if="homePlaylists && homePlaylists.length" aria-labelledby="collection-title">
          <div class="home-section-heading"><div><h2 id="collection-title">共享歌单</h2></div><RouterLink to="/library/playlists">全部歌单 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
          <div class="home-playlists">
            <RouterLink v-for="p in homePlaylists" :key="p.id" to="/library/playlists"><span class="playlist-mark" aria-hidden="true"><ElIcon><Collection /></ElIcon></span><div><strong>{{ p.name }}</strong><small>{{ p.count }} 首歌曲</small></div><ElIcon><ArrowRight /></ElIcon></RouterLink>
          </div>
        </section>
      </div>

      <aside class="home-aside">
        <section class="todo-panel" aria-labelledby="overview-todo-title">
          <div class="home-section-heading"><h2 id="overview-todo-title">待处理 <span v-if="todos.length" class="sv-count-pill warn">{{ todos.length }}</span></h2></div>
          <ul v-if="todos.length" class="todo-list">
            <li v-for="todo in todos" :key="todo.key" class="todo-item">
              <span class="sv-status-dot" :class="todo.level" />
              <div class="todo-copy"><strong>{{ todo.title }}</strong><p>{{ todo.detail }}</p><RouterLink :to="todo.to" class="todo-action">{{ todo.action }} <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
            </li>
          </ul>
          <p v-else-if="todoLoading" class="content-empty" role="status">正在读取待办…</p>
          <p v-else-if="!todoErrors" class="content-empty">暂无待处理事项</p>
          <p v-if="todoErrors && !todoLoading" class="todo-note" role="alert">有 {{ todoErrors }} 项状态读取失败，待办可能不完整。<ElButton link type="primary" @click="refresh">重新读取</ElButton></p>
        </section>

        <section class="home-tasks" aria-labelledby="home-tasks-title">
          <div class="home-section-heading"><h2 id="home-tasks-title">后台任务</h2><RouterLink to="/system/tasks">任务中心 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
          <p v-if="tasks.state === 'loading' && !tasks.value" class="muted" role="status">正在读取任务…</p>
          <div v-else-if="tasks.state === 'error'" class="muted">暂时无法读取任务 <ElButton link @click="refreshTasks">重试</ElButton></div>
          <p v-else-if="!tasks.value?.length" class="muted">暂时没有进行中的任务。离开页面后，已提交的任务也会继续。</p>
          <RouterLink v-for="task in tasks.value" :key="task.id" :to="'/system/tasks/' + encodeURIComponent(task.id)" class="home-task">
            <span class="sv-status-dot busy" /><div><strong>{{ task.name }}</strong><small>{{ statusNames[task.status] || task.status }} · {{ progressText(task) }}</small></div><ElIcon><ArrowRight /></ElIcon>
          </RouterLink>
        </section>

        <section v-if="playback" class="playback-pulse" aria-label="近 30 天播放">
          <h2>近 30 天播放</h2>
          <p class="pulse-numbers"><strong>{{ fmt(playback.totalPlays) }}</strong> 次播放 · <strong>{{ fmt(playback.effectivePlays) }}</strong> 次有效 · <strong>{{ fmt(playback.playedSongs) }}</strong> 首</p>
          <RouterLink to="/library/playback-history">播放与热门 <ElIcon><ArrowRight /></ElIcon></RouterLink>
        </section>

        <a class="party-invitation" href="/party/" target="_blank" rel="noopener">
          <ElIcon><Monitor /></ElIcon><h3>亲友点歌</h3><p>在手机或电视上开唱，让亲友扫码点歌</p><span>打开亲友点歌页 <ElIcon><TopRight /></ElIcon></span>
        </a>
        <ServiceHealthCard />
      </aside>
    </div>
  </section>
</template>
<style scoped>
.overview-error {
  margin-bottom: 24px;
}

.overview-error :deep(.el-alert__description) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.home-feature {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 16px;
  background: var(--sv-surface-2);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
}

.feature-copy {
  min-width: 0;
}

.feature-copy h2 {
  font-size: 18px;
  font-weight: 500;
}

.feature-copy p {
  font-size: 13px;
  color: var(--sv-text-secondary);
  line-height: 1.7;
  margin-top: 8px;
}

.feature-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.overview-refresh {
  margin: 0;
  min-height: 40px;
}

.feature-actions a,
.solid-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 8px 16px;
  border-radius: var(--sv-radius-md);
  font-size: 13px;
  font-weight: 500;
  transition: background .16s var(--sv-ease-smooth), color .16s var(--sv-ease-smooth), border-color .16s var(--sv-ease-smooth);
}

.solid-link {
  background: var(--sv-accent-primary);
  color: var(--sv-on-accent);
  border: 1px solid transparent;
}

.solid-link:hover {
  color: var(--sv-on-accent);
  background: var(--sv-accent-muted);
}

.secondary-link {
  border: 1px solid var(--sv-border-default);
  background: var(--sv-surface-2);
  color: var(--sv-text-primary);
}

.secondary-link:hover {
  background: var(--sv-surface-3);
  color: var(--sv-text-primary);
}

.overview-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin: 24px 0 16px;
}

.readiness-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-bottom: 32px;
}

.readiness-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  font-size: 12px;
  color: var(--sv-text-secondary);
}

.readiness-chip .el-icon {
  font-size: 16px;
}

.readiness-chip.warn {
  color: var(--sv-semantic-warn);
}

.readiness-chip.muted {
  color: var(--sv-text-tertiary);
}

.home-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 32px;
}

.home-main {
  display: grid;
  gap: 32px;
  min-width: 0;
  align-content: start;
}

.home-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.home-section-heading h2 {
  font-size: 18px;
  font-weight: 500;
}

.home-section-heading a {
  font-size: 12px;
  display: inline-flex;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.recent-shelf {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px 16px;
}

.shelf-song {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 4px;
  color: var(--sv-text-primary);
}

.shelf-song :deep(.song-cover) {
  width: 100%;
  height: auto;
  aspect-ratio: 1;
  border-radius: var(--sv-radius-md);
  margin-bottom: 8px;
}

.shelf-song strong,
.shelf-song>span {
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
}

.shelf-song strong {
  font-size: 14px;
  font-weight: 500;
}

.shelf-song>span {
  font-size: 12px;
  color: var(--sv-text-secondary);
}

.home-aside {
  display: grid;
  min-width: 0;
  gap: 24px;
  align-content: start;
  border-left: 1px solid var(--sv-border-subtle);
  padding-left: 24px;
}

.home-aside .home-section-heading h2 {
  font-size: 16px;
}

.todo-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.todo-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid var(--sv-border-subtle);
}

.todo-item:first-child {
  padding-top: 0;
}

.todo-item .sv-status-dot,
.home-task .sv-status-dot {
  margin-top: 8px;
}

.todo-copy {
  min-width: 0;
}

.todo-copy strong {
  font-size: 13px;
  font-weight: 500;
}

.todo-copy p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin-top: 8px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.todo-action {
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-weight: 500;
}

.todo-note {
  padding-top: 16px;
  font-size: 12px;
  color: var(--sv-semantic-warn);
}

.content-empty {
  padding: 24px 0;
  color: var(--sv-text-secondary);
  font-size: 13px;
}

.workflow-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  background: var(--sv-surface-2);
  overflow: hidden;
}

.workflow-links>a {
  position: relative;
  display: grid;
  justify-items: start;
  align-content: start;
  padding: 24px 20px;
  border-right: 1px solid var(--sv-border-subtle);
  color: var(--sv-text-primary);
  transition: background .16s var(--sv-ease-smooth);
}

.workflow-links>a:hover {
  background: var(--sv-surface-1);
}

.workflow-links>a:last-child {
  border: 0;
}

.workflow-number {
  position: absolute;
  top: 24px;
  right: 20px;
  font: 12px var(--sv-font-mono);
  color: var(--sv-text-tertiary);
}

.workflow-links>a>.el-icon {
  font-size: 18px;
  margin-bottom: 16px;
  color: var(--sv-text-secondary);
}

.workflow-links strong {
  font-size: 13px;
  font-weight: 500;
}

.workflow-links p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin-top: 8px;
}

.workflow-links>a>.workflow-arrow {
  position: absolute;
  right: 16px;
  bottom: 24px;
  font-size: 16px;
  margin: 0;
}

.home-task {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  border-bottom: 1px solid var(--sv-border-subtle);
  padding: 12px 0;
  font-size: 12px;
  color: var(--sv-text-primary);
}

.home-task>div {
  flex: 1;
  min-width: 0;
}

.home-task strong {
  display: block;
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}

.home-task small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--sv-text-secondary);
}

.home-tasks>.muted {
  font-size: 12px;
}

.playback-pulse {
  padding: 20px;
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  background: var(--sv-surface-2);
}

.playback-pulse h2 {
  font-size: 14px;
  font-weight: 500;
}

.pulse-numbers {
  margin-top: 12px;
  font-size: 12px;
  color: var(--sv-text-secondary);
}

.pulse-numbers strong {
  font-size: 18px;
  font-weight: 500;
  color: var(--sv-text-primary);
  font-variant-numeric: tabular-nums;
}

.playback-pulse>a {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  font-size: 12px;
}

.party-invitation {
  padding: 24px;
  background: var(--sv-surface-2);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  color: var(--sv-text-primary);
}

.party-invitation:hover {
  background: var(--sv-surface-1);
  color: var(--sv-text-primary);
}

.party-invitation>.el-icon {
  font-size: 18px;
  color: var(--sv-text-secondary);
}

.party-invitation h3 {
  font-size: 16px;
  font-weight: 500;
  margin: 12px 0 8px;
}

.party-invitation p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  line-height: 1.7;
}

.party-invitation>span {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-top: 16px;
  color: var(--sv-accent-primary);
  font-weight: 500;
}

.home-playlists {
  display: grid;
  gap: 8px;
}

.home-playlists>a {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 16px;
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-md);
  background: var(--sv-surface-2);
  color: var(--sv-text-primary);
}

.playlist-mark {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex: none;
  background: var(--sv-surface-3);
  color: var(--sv-text-secondary);
  border-radius: var(--sv-radius-sm);
}

.playlist-mark .el-icon {
  font-size: 18px;
}

.home-playlists>a>div {
  flex: 1;
  min-width: 0;
}

.home-playlists strong {
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}

.home-playlists small {
  display: block;
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin-top: 4px;
}

@media(min-width:1400px) {
  .recent-shelf {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }
}

@media(max-width:1100px) {
  .home-content {
    grid-template-columns: minmax(0, 1fr) 280px;
    gap: 24px;
  }

  .workflow-links>a {
    padding: 20px 16px;
  }

  .workflow-links>a>.workflow-arrow {
    display: none;
  }

  .home-feature {
    flex-wrap: wrap;
  }
}

@media(max-width:900px) {
  .home-content {
    grid-template-columns: minmax(0, 1fr);
  }

  .home-aside {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    border: 0;
    padding: 0;
  }

  .home-aside .todo-panel {
    grid-row: span 2;
  }

  .home-aside :deep(.health-card) {
    grid-column: 1/-1;
  }

  .recent-shelf {
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 12px;
  }
}

@media(max-width:650px) {
  .overview-refresh,
  .feature-actions a,
  .solid-link {
    min-height: 44px;
  }

  .home-feature {
    gap: 16px;
  }

  .overview-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
    margin: 20px 0 12px;
  }

  .readiness-row {
    gap: 0 16px;
    margin-bottom: 24px;
  }

  .readiness-row a,
  .home-section-heading a,
  .todo-action,
  .playback-pulse>a {
    min-height: 44px;
  }

  .home-section-heading h2 {
    font-size: 18px;
  }

  .recent-shelf {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 24px 12px;
  }

  .home-aside {
    grid-template-columns: minmax(0, 1fr);
  }

  .home-aside .todo-panel {
    grid-row: auto;
  }

  .workflow-links>a {
    padding: 20px 12px;
  }

  .workflow-links strong {
    font-size: 12px;
  }

  .workflow-number {
    right: 12px;
    top: 20px;
  }

  .home-content {
    gap: 32px;
  }
}
</style>
