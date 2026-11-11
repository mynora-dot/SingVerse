<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive } from 'vue'
import { ElButton, ElIcon } from 'element-plus'
import { ArrowRight, ChatDotRound, Cpu, FolderOpened, Headset, MagicStick, Monitor, Plus, RefreshRight, TopRight } from '@element-plus/icons-vue'
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
const collected = computed(() => (ok('library') ? ov.value!.library!.totalSongs : 0) > 0)

const shortcuts = [
  { icon: FolderOpened, title: '扫描媒体库', text: '添加来源或重新扫描', to: '/library/media' },
  { icon: MagicStick, title: '整理元数据', text: '匹配平台信息并审核', to: '/library/songs/metadata' },
  { icon: Monitor, title: '连接电视', text: '批准或撤销电视访问', to: '/system/tv-devices' },
]
</script>

<template>
  <section class="overview-page">
    <div class="welcome-heading">
      <div><p class="eyebrow">YOUR HOME, IN TUNE</p><h1>好歌，在家相聚。</h1><p>让每一首收藏，都为下一次欢唱准备好。</p></div>
      <ElButton :icon="RefreshRight" :loading="todoLoading" @click="refresh">刷新空间</ElButton>
    </div>

    <section class="home-feature" :class="{ collected }" aria-label="我的家庭曲库">
      <div class="feature-copy">
        <p class="eyebrow">THE SINGVERSE COLLECTION</p>
        <h2>你的收藏，<br>全家的拿手好歌。</h2>
        <p>从 NAS 到客厅，从一首歌到一场相聚。<br>把喜欢的歌收好，剩下的交给音乐。</p>
        <div class="feature-actions">
          <RouterLink class="solid-link" to="/library/songs?lens=songs">浏览曲库 <ElIcon><ArrowRight /></ElIcon></RouterLink>
          <RouterLink to="/library/media?add=LOCAL">添加歌曲 <ElIcon><Plus /></ElIcon></RouterLink>
        </div>
      </div>
      <div class="collection-art" aria-hidden="true">
        <div class="sleeve sleeve-back"><span>home<br>sessions.</span><small>VOL. 01 / SING TOGETHER</small></div>
        <div class="vinyl-record"><div class="vinyl-label"><span>SingVerse</span><i /></div></div>
        <div class="sleeve sleeve-front"><span class="sleeve-sun" /><span>家的<br>回响</span><small>A COLLECTION OF GOOD TIMES</small></div>
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
          <div class="home-section-heading"><div><p class="eyebrow">FRESH ON THE SHELF</p><h2 id="recent-title">最近入库</h2></div><RouterLink to="/library/songs?lens=songs">全部歌曲 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
          <p v-if="data.state === 'loading'" class="content-empty" role="status">正在翻阅你的收藏…</p>
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
          <div class="home-section-heading"><div><p class="eyebrow">MAKE IT READY</p><h2 id="workflow-title">把喜欢的歌，准备好</h2></div></div>
          <nav class="workflow-links" aria-label="准备歌曲的步骤">
            <RouterLink v-for="(item, index) in shortcuts" :key="item.to" :to="item.to">
              <span class="workflow-number">0{{ index + 1 }}</span><ElIcon><component :is="item.icon" /></ElIcon><strong>{{ item.title }}</strong><p>{{ item.text }}</p><ElIcon class="workflow-arrow"><ArrowRight /></ElIcon>
            </RouterLink>
          </nav>
        </section>

        <section v-if="homePlaylists && homePlaylists.length" aria-labelledby="collection-title">
          <div class="home-section-heading"><div><p class="eyebrow">FOR YOUR NEXT GATHERING</p><h2 id="collection-title">相聚的歌单</h2></div><RouterLink to="/library/playlists">全部歌单 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
          <div class="home-playlists">
            <RouterLink v-for="p in homePlaylists" :key="p.id" to="/library/playlists"><span aria-hidden="true">♫</span><div><strong>{{ p.name }}</strong><small>{{ p.count }} 首歌曲</small></div><ElIcon><ArrowRight /></ElIcon></RouterLink>
          </div>
        </section>
      </div>

      <aside class="home-aside">
        <section class="todo-panel" aria-labelledby="overview-todo-title">
          <div class="home-section-heading"><h2 id="overview-todo-title">需要你看一眼 <span v-if="todos.length" class="sv-count-pill warn">{{ todos.length }}</span></h2></div>
          <ul v-if="todos.length" class="todo-list">
            <li v-for="todo in todos" :key="todo.key" class="todo-item">
              <span class="sv-status-dot" :class="todo.level" />
              <div class="todo-copy"><strong>{{ todo.title }}</strong><p>{{ todo.detail }}</p><RouterLink :to="todo.to" class="todo-action">{{ todo.action }} <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
            </li>
          </ul>
          <p v-else-if="todoLoading" class="content-empty" role="status">正在查看需要处理的事情…</p>
          <p v-else-if="!todoErrors" class="content-empty">一切井然有序，准备好开唱了。</p>
          <p v-if="todoErrors && !todoLoading" class="todo-note" role="alert">有 {{ todoErrors }} 项状态读取失败，待办可能不完整。<ElButton link type="primary" @click="refresh">重新读取</ElButton></p>
        </section>

        <section class="home-tasks" aria-labelledby="home-tasks-title">
          <div class="home-section-heading"><h2 id="home-tasks-title">后台正在忙</h2><RouterLink to="/system/tasks">任务中心 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
          <p v-if="tasks.state === 'loading' && !tasks.value" class="muted" role="status">正在读取任务…</p>
          <div v-else-if="tasks.state === 'error'" class="muted">暂时无法读取任务 <ElButton link @click="refreshTasks">重试</ElButton></div>
          <p v-else-if="!tasks.value?.length" class="muted">暂时没有进行中的任务。离开页面后，已提交的任务也会继续。</p>
          <RouterLink v-for="task in tasks.value" :key="task.id" :to="'/system/tasks/' + encodeURIComponent(task.id)" class="home-task">
            <span class="sv-status-dot busy" /><div><strong>{{ task.name }}</strong><small>{{ statusNames[task.status] || task.status }} · {{ progressText(task) }}</small></div><ElIcon><ArrowRight /></ElIcon>
          </RouterLink>
        </section>

        <section v-if="playback" class="playback-pulse" aria-label="近 30 天播放">
          <p class="eyebrow">LAST 30 DAYS</p>
          <p class="pulse-numbers"><strong>{{ fmt(playback.totalPlays) }}</strong> 次播放 · <strong>{{ fmt(playback.effectivePlays) }}</strong> 次有效 · <strong>{{ fmt(playback.playedSongs) }}</strong> 首</p>
          <RouterLink to="/library/playback-history">播放与热门 <ElIcon><ArrowRight /></ElIcon></RouterLink>
        </section>

        <a class="party-invitation" href="/party/" target="_blank" rel="noopener">
          <ElIcon><Monitor /></ElIcon><h3>好歌，一起唱。</h3><p>在手机或电视上开唱，<br>让亲友扫码来点歌。</p><span>打开亲友点歌页 <ElIcon><TopRight /></ElIcon></span>
        </a>
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
  font: 600 var(--sv-font-h1)/1.4 var(--sv-font-editorial);
  margin-top: 8px;
  letter-spacing: .5px
}

.welcome-heading>div>p:last-child {
  font-size: 13px;
  color: var(--sv-text-secondary);
  margin-top: 8px
}

.welcome-heading>.el-button {
  font-size: 13px;
  background: none
}

.home-feature {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 300px;
  background: var(--sv-accent-soft);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  overflow: hidden
}

.home-feature.collected {
  min-height: 224px
}

.collected .feature-copy {
  padding: 26px 34px
}

.collected .feature-copy h2 {
  font-size: 27px;
  margin-top: 8px
}

.collected .feature-copy>p:not(.eyebrow) {
  display: none
}

.collected .feature-actions {
  margin-top: 18px
}

.collected .collection-art {
  min-height: 224px
}

.collected .sleeve {
  width: 160px
}

.collected .sleeve-front {
  top: 68px
}

.collected .sleeve-back {
  top: 20px
}

.collected .vinyl-record {
  width: 188px;
  top: 24px
}

.feature-copy {
  padding: 34px 40px;
  position: relative;
  z-index: 1
}

.feature-copy h2 {
  font: 600 31px/1.5 var(--sv-font-editorial);
  letter-spacing: 1px;
  margin-top: 14px;
  color: var(--sv-accent-muted)
}

.feature-copy>p:not(.eyebrow) {
  font-size: 13px;
  color: var(--sv-text-secondary);
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
  gap: 10px;
  align-items: center;
  font-size: 13px;
  font-weight: 550
}

.solid-link {
  padding: 10px 18px;
  border-radius: var(--sv-radius-md);
  background: var(--sv-accent-primary);
  color: var(--sv-on-accent)
}

.solid-link:hover {
  color: var(--sv-on-accent);
  background: var(--sv-accent-muted)
}

.collection-art {
  position: relative;
  min-height: 300px;
  overflow: hidden
}

.sleeve {
  position: absolute;
  width: 198px;
  aspect-ratio: 1;
  box-shadow: 3px 10px 22px rgb(36 31 23 / 14%)
}

.sleeve-back {
  background: var(--sv-terracotta);
  top: 38px;
  left: 4%;
  transform: rotate(-13deg);
  padding: 24px;
  color: var(--sv-on-highlight)
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
  background: var(--sv-surface-2);
  top: 104px;
  left: 24%;
  transform: rotate(7deg);
  padding: 20px;
  overflow: hidden
}

.sleeve-front>span:not(.sleeve-sun) {
  position: relative;
  font: 37px/1.3 var(--sv-font-editorial);
  color: var(--sv-accent-primary);
  letter-spacing: 6px
}

.sleeve-sun {
  position: absolute;
  display: block;
  width: 160px;
  height: 160px;
  border-radius: 50%;
  background: repeating-linear-gradient(0deg, var(--sv-accent-primary) 0 2px, transparent 2px 5px);
  opacity: .35;
  right: -34px;
  top: 14px
}

.vinyl-record {
  position: absolute;
  width: 228px;
  aspect-ratio: 1;
  border-radius: 50%;
  background: var(--sv-record-groove), var(--sv-media-stage);
  left: 40%;
  top: 54px;
  box-shadow: 5px 15px 24px rgb(36 31 23 / 22%)
}

.vinyl-label {
  position: absolute;
  inset: 34%;
  border-radius: 50%;
  background: var(--sv-terracotta);
  display: grid;
  place-items: center;
  color: var(--sv-on-highlight);
  font: 9px var(--sv-font-editorial)
}

.vinyl-label i {
  position: absolute;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sv-surface-2);
  top: calc(50% - 3px)
}

.vinyl-label>span {
  transform: translateY(-14px)
}

.overview-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin: 26px 0 18px
}

.readiness-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 30px
}

.readiness-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border-radius: var(--sv-radius-full);
  border: 1px solid var(--sv-border-subtle);
  background: var(--sv-surface-2);
  font-size: 12px;
  color: var(--sv-text-secondary)
}

.readiness-chip .el-icon {
  font-size: 14px
}

.readiness-chip.ok {
  color: var(--sv-semantic-ok);
  background: var(--sv-semantic-ok-soft);
  border-color: transparent
}

.readiness-chip.warn {
  color: var(--sv-semantic-warn);
  background: var(--sv-semantic-warn-soft);
  border-color: transparent
}

.readiness-chip.muted {
  color: var(--sv-text-tertiary)
}

.home-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
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
  font-size: 12px;
  display: flex;
  gap: 7px;
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
  border-radius: var(--sv-radius-md);
  margin-bottom: 6px;
  box-shadow: var(--sv-shadow-sm)
}

.shelf-song strong {
  font-size: 13px;
  font-weight: 550;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden
}

.shelf-song>span {
  font-size: 12px;
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
  font-size: 13px;
  font-weight: 550
}

.todo-copy p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin-top: 6px;
  line-height: 1.8;
  overflow-wrap: anywhere
}

.todo-action {
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-weight: 550
}

.todo-note {
  padding-top: 14px;
  font-size: 12px;
  color: var(--sv-semantic-warn)
}

.content-empty {
  padding: 24px 0;
  color: var(--sv-text-secondary);
  font-size: 13px
}

.workflow-links {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  background: var(--sv-surface-2);
  overflow: hidden
}

.workflow-links>a {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 24px 20px;
  border-right: 1px solid var(--sv-border-subtle);
  color: var(--sv-text-primary)
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
  margin-bottom: 20px;
  color: var(--sv-accent-primary)
}

.workflow-links strong {
  font-size: 13px;
  font-weight: 550
}

.workflow-links p {
  font-size: 11px;
  color: var(--sv-text-secondary);
  margin-top: 6px
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
  font-size: 12px;
  color: var(--sv-text-primary)
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
  font-size: 13px;
  font-weight: 550;
  overflow-wrap: anywhere
}

.home-task small {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--sv-text-secondary)
}

.home-tasks>.muted {
  font-size: 12px
}

.playback-pulse {
  padding: 20px;
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  background: var(--sv-surface-2)
}

.pulse-numbers {
  margin-top: 10px;
  font-size: 12px;
  color: var(--sv-text-secondary)
}

.pulse-numbers strong {
  font-size: 17px;
  font-weight: 600;
  color: var(--sv-text-primary);
  font-variant-numeric: tabular-nums
}

.playback-pulse>a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 12px
}

.party-invitation {
  padding: 24px;
  background: var(--sv-accent-highlight-soft);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  color: var(--sv-text-primary)
}

.party-invitation>.el-icon {
  font-size: 26px;
  color: var(--sv-accent-highlight)
}

.party-invitation h3 {
  font: 600 22px var(--sv-font-editorial);
  margin: 16px 0 10px
}

.party-invitation p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  line-height: 1.8
}

.party-invitation>span {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-top: 18px;
  color: var(--sv-accent-highlight);
  font-weight: 550
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
  border-radius: var(--sv-radius-md);
  background: var(--sv-surface-2);
  color: var(--sv-text-primary)
}

.home-playlists>a>span {
  font-size: 24px;
  background: var(--sv-accent-soft);
  color: var(--sv-accent-primary);
  padding: 4px 15px;
  border-radius: var(--sv-radius-sm)
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
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin-top: 2px
}

@media(min-width:1400px) {
  .recent-shelf {
    grid-template-columns: repeat(6, minmax(0, 1fr))
  }

  .home-content {
    grid-template-columns: minmax(0, 1fr) 330px
  }
}

@media(max-width:1100px) {
  .home-content {
    grid-template-columns: minmax(0, 1fr) 280px;
    gap: 28px
  }

  .home-aside {
    padding-left: 24px
  }

  .overview-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr))
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
    font-size: 28px
  }

  .workflow-links>a {
    padding: 20px 14px
  }

  .workflow-links p {
    max-width: 110px
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
    min-height: 300px
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
    font-size: 26px
  }

  .welcome-heading>div>p:last-child {
    font-size: 12px
  }

  .welcome-heading>.el-button {
    padding: 6px 10px;
    min-height: 32px;
    font-size: 12px;
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
    font-size: 24px
  }

  .collected .collection-art {
    min-height: 0
  }

  .feature-copy {
    padding: 26px
  }

  .feature-copy h2 {
    font-size: 27px
  }

  .feature-copy>p:not(.eyebrow) {
    font-size: 12px
  }

  .collection-art {
    position: absolute;
    width: 170px;
    height: 220px;
    min-height: 0;
    right: -65px;
    bottom: 12px;
    opacity: .2
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
    gap: 12px;
    margin: 20px 0 14px
  }

  .home-section-heading h2 {
    font-size: 17px
  }

  .home-section-heading a {
    font-size: 11px
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

  .workflow-number {
    right: 10px;
    top: 16px
  }

  .home-content {
    gap: 32px
  }

  .home-main {
    gap: 28px
  }
}
</style>
