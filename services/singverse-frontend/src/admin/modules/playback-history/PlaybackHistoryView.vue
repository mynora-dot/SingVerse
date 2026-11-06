<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElButton, ElDrawer, ElInput, ElPagination } from 'element-plus'
import PageHeader from '../../shared/ui/PageHeader.vue'
import PlaybackEntryList from './PlaybackEntryList.vue'
import { playbackHistory, type Page, type Period, type PlaybackEntry, type PlaybackSummary, type PopularSong } from './api'
import { date, duration } from './presentation'

const tab = ref<'popular' | 'records'>('popular'), period = ref<Period>('30d'), query = ref(''), appliedQuery = ref(''), page = ref(1)
const popular = ref<PopularSong[]>([]), records = ref<PlaybackEntry[]>([]), total = ref(0), summary = ref<PlaybackSummary | null>(null)
const loading = ref(false), error = ref(''), drawerOpen = ref(false), selectedSong = ref<{ songId: string; title: string } | null>(null)
const detail = ref<Page<PlaybackEntry> | null>(null), detailPage = ref(1), detailLoading = ref(false), detailError = ref('')
let active = true, epoch = 0, detailEpoch = 0
function message(value: unknown) { return value instanceof Error ? value.message : '读取失败，请重试' }
async function load() {
  const seq = ++epoch
  const selectedTab = tab.value
  loading.value = true; error.value = ''
  const filter = { q: appliedQuery.value, period: period.value }
  try {
    const [stats, result] = await Promise.all([
      playbackHistory.summary(filter),
      selectedTab === 'popular' ? playbackHistory.popular({ ...filter, page: page.value - 1, size: 20 }) : playbackHistory.list({ ...filter, page: page.value - 1, size: 20 }),
    ])
    if (!active || seq !== epoch) return
    summary.value = stats; total.value = result.totalElements
    if (selectedTab === 'popular') popular.value = result.items as PopularSong[]
    else records.value = result.items as PlaybackEntry[]
    const last = Math.max(1, Math.ceil(total.value / 20))
    if (page.value > last) { page.value = last; await load() }
  } catch (value) {
    if (active && seq === epoch) { error.value = message(value); summary.value = null; popular.value = []; records.value = []; total.value = 0 }
  } finally { if (active && seq === epoch) loading.value = false }
}
function search() { appliedQuery.value = query.value.trim(); page.value = 1; void load() }
function changePeriod() { page.value = 1; void load() }
function changeTab(value: 'popular' | 'records') { if (tab.value === value) return; tab.value = value; page.value = 1; void load() }
async function loadDetail() {
  if (!selectedSong.value) return
  const seq = ++detailEpoch
  detailLoading.value = true; detailError.value = ''
  try {
    const result = await playbackHistory.list({ songId: selectedSong.value.songId, period: 'all', page: detailPage.value - 1, size: 20 })
    if (!active || seq !== detailEpoch) return
    detail.value = result
    const last = Math.max(1, Math.ceil(result.totalElements / 20))
    if (detailPage.value > last) { detailPage.value = last; await loadDetail() }
  } catch (value) { if (active && seq === detailEpoch) { detailError.value = message(value); detail.value = null } }
  finally { if (active && seq === detailEpoch) detailLoading.value = false }
}
function openHistory(song: { songId: string; title: string }) { selectedSong.value = { songId: song.songId, title: song.title }; detailPage.value = 1; detail.value = null; drawerOpen.value = true; void loadDetail() }
function closeHistory() { drawerOpen.value = false; detailEpoch++; detailLoading.value = false }
onMounted(() => { void load() })
onBeforeUnmount(() => { active = false; epoch++; detailEpoch++ })
</script>

<template>
  <section class="playback-history-page">
    <PageHeader title="播放记录" description="查看热门歌曲和每次播放，点击歌曲可追溯它的全部历史。"><template #actions><ElButton :loading="loading" @click="load">刷新</ElButton></template></PageHeader>
    <form class="history-filters" @submit.prevent="search"><ElInput v-model="query" placeholder="搜索歌曲或歌手" aria-label="搜索播放记录" maxlength="200" clearable @clear="search"/><label>统计时间<select v-model="period" aria-label="播放统计时间" @change="changePeriod"><option value="30d">近 30 天</option><option value="7d">近 7 天</option><option value="all">全部时间</option></select></label><ElButton native-type="submit" :loading="loading">搜索</ElButton></form>
    <div v-if="error" class="history-error" role="alert">{{ error }}<ElButton text type="primary" @click="load">重试</ElButton></div>
    <div class="history-stats" aria-label="当前筛选下的播放统计" :aria-busy="loading">
      <div><span>有效播放</span><strong>{{ summary && !loading ? summary.effectivePlays.toLocaleString('zh-CN') : '—' }}<small v-if="summary && !loading"> 次</small></strong></div>
      <div><span>有效播放歌曲</span><strong>{{ summary && !loading ? summary.playedSongs.toLocaleString('zh-CN') : '—' }}<small v-if="summary && !loading"> 首</small></strong></div>
      <div><span>全部播放记录</span><strong>{{ summary && !loading ? summary.totalPlays.toLocaleString('zh-CN') : '—' }}<small v-if="summary && !loading"> 条</small></strong></div>
      <div><span>累计实际播放</span><strong class="history-time-stat">{{ summary && !loading ? duration(summary.playedMs) : '—' }}</strong></div>
    </div>
    <section class="sv-panel history-results">
      <div class="history-tabs" role="tablist" aria-label="播放记录视图"><button id="history-popular-tab" type="button" role="tab" aria-controls="history-result-panel" :aria-selected="tab === 'popular'" @click="changeTab('popular')">热门排行</button><button id="history-records-tab" type="button" role="tab" aria-controls="history-result-panel" :aria-selected="tab === 'records'" @click="changeTab('records')">播放明细</button></div>
      <div id="history-result-panel" role="tabpanel" :aria-labelledby="tab === 'popular' ? 'history-popular-tab' : 'history-records-tab'" :aria-busy="loading">
        <p v-if="loading" class="history-empty" role="status">正在读取播放记录…</p>
        <template v-else-if="!error">
          <div v-if="!total" class="history-empty"><h2>{{ appliedQuery ? '没有找到匹配的播放记录' : tab === 'popular' ? '暂无热门歌曲' : '暂无播放记录' }}</h2><p>{{ appliedQuery ? '试试其他歌名、歌手或统计时间。' : tab === 'popular' ? '当前时间范围内还没有达到有效播放条件的歌曲，可切换到播放明细查看。' : '这里会按时间展示每次播放的歌曲、设备和结束结果。' }}</p></div>
          <template v-else-if="tab === 'popular'">
            <div class="popular-heading" aria-hidden="true"><span>排名 / 歌曲</span><span>有效播放</span><span>全部 / 播完</span><span>最近播放</span><span>操作</span></div>
            <article v-for="(song, index) in popular" :key="song.songId" class="popular-row"><div class="popular-song"><span class="popular-rank">{{ (page - 1) * 20 + index + 1 }}</span><div><h3>{{ song.title || '未命名歌曲' }}</h3><p>{{ song.artists.join(' / ') || '未知歌手' }}</p><small>{{ [song.versionLabel, song.sourceName].filter(Boolean).join(' · ') || '未记录版本与来源' }}</small></div></div><div class="popular-effective"><strong>{{ song.effectivePlayCount }}</strong><span> 次有效播放</span></div><div class="popular-count"><span>全部 {{ song.playCount }} 次</span><small>自然播完 {{ song.completedCount }} 次</small></div><time :datetime="song.lastPlayedAt">{{ date(song.lastPlayedAt) }}</time><ElButton text type="primary" :aria-label="`查看${song.title}的全部播放历史`" @click="openHistory(song)">查看历史</ElButton></article>
          </template>
          <PlaybackEntryList v-else :items="records" show-history @history="openHistory"/>
        </template>
      </div>
      <div class="history-results-footer"><p>累计实际播放满 30 秒计为有效播放；不足 30 秒的歌曲实际播完也计入。暂停和拖动进度不增加实际播放时长，后台试听不计入热门。</p><ElPagination v-if="total && !error" :current-page="page" :total="total" :page-size="20" :disabled="loading" layout="total, prev, pager, next" @current-change="v => { page = v; load() }"/></div>
    </section>
    <ElDrawer v-model="drawerOpen" :title="`${selectedSong?.title || '歌曲'} · 全部播放历史`" size="min(1000px, 100vw)" @close="closeHistory"><p class="history-detail-intro">展示这首歌曲全部时间的记录，不受主列表搜索和时间筛选影响。</p><p v-if="detailLoading" class="history-empty" role="status">正在读取单曲历史…</p><div v-else-if="detailError" class="history-error" role="alert">{{ detailError }}<ElButton text type="primary" @click="loadDetail">重试</ElButton></div><template v-else-if="detail"><PlaybackEntryList v-if="detail.items.length" :items="detail.items"/><p v-else class="history-empty">这首歌曲暂无播放记录。</p><ElPagination v-if="detail.totalElements" class="history-detail-pagination" :current-page="detailPage" :total="detail.totalElements" :page-size="20" layout="total, prev, pager, next" @current-change="v => { detailPage = v; loadDetail() }"/></template></ElDrawer>
  </section>
</template>

<style scoped>
.playback-history-page{display:grid;gap:18px}.history-filters{display:flex;flex-wrap:wrap;align-items:center;gap:12px}.history-filters>.el-input{max-width:360px}.history-filters label{display:flex;gap:8px;align-items:center;font-size:12px;color:var(--sv-text-secondary)}.history-filters select{padding:7px 10px;border:1px solid var(--sv-border-default);border-radius:7px;font:inherit;color:var(--sv-text-primary);background:var(--sv-surface-1)}.history-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.history-stats>div{display:grid;gap:6px;padding:16px 18px;border:1px solid var(--sv-border-subtle);border-radius:var(--sv-radius-lg);background:var(--sv-surface-2)}.history-stats span{font-size:12px;color:var(--sv-text-secondary)}.history-stats strong{font-size:25px;line-height:1.3;font-weight:650;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.history-stats small{font-size:12px;font-weight:400;color:var(--sv-text-secondary)}.history-stats .history-time-stat{font-size:19px;align-self:center}.history-tabs{display:flex;gap:24px;margin:0 20px;border-bottom:1px solid var(--sv-border-subtle)}.history-tabs button{padding:16px 0 12px;border:0;border-bottom:2px solid transparent;color:var(--sv-text-secondary);background:none;font:inherit;font-size:14px;cursor:pointer}.history-tabs button[aria-selected=true]{color:var(--sv-accent-primary);border-bottom-color:var(--sv-accent-primary);font-weight:600}.popular-heading,.popular-row{display:grid;grid-template-columns:minmax(200px,1.6fr) minmax(100px,.7fr) minmax(110px,.8fr) minmax(150px,1fr) 80px;gap:16px;align-items:center;padding:16px 20px;border-bottom:1px solid var(--sv-border-subtle)}.popular-heading{font-size:12px;color:var(--sv-text-secondary);padding-top:12px;padding-bottom:10px}.popular-song{display:flex;gap:15px;align-items:center;min-width:0}.popular-rank{font-size:18px;min-width:25px;color:var(--sv-text-tertiary);font-variant-numeric:tabular-nums}.popular-song>div{min-width:0;overflow-wrap:anywhere}.popular-row h3{margin:0;font-size:14px;font-weight:600}.popular-row p{font-size:12px;color:var(--sv-text-secondary);margin:4px 0}.popular-row small{font-size:12px;color:var(--sv-text-secondary)}.popular-row time{font-size:12px;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.popular-effective strong{font-size:20px;font-weight:650;font-variant-numeric:tabular-nums}.popular-effective span{font-size:12px;color:var(--sv-text-secondary)}.popular-count{display:grid;gap:4px;font-size:12px}.history-results-footer{padding:14px 20px;display:grid;gap:12px}.history-results-footer p,.history-detail-intro{margin:0;color:var(--sv-text-secondary);font-size:12px;line-height:1.8}.history-results-footer .el-pagination,.history-detail-pagination{justify-content:flex-end;max-width:100%;flex-wrap:wrap}.history-empty{padding:48px 20px;text-align:center;color:var(--sv-text-secondary);font-size:13px;margin:0}.history-empty h2{font-size:17px;font-weight:600;color:var(--sv-text-primary);margin:0 0 8px}.history-empty p{margin:0;line-height:1.8}.history-error{padding:12px 16px;border:1px solid var(--sv-border-subtle);border-radius:8px;background:var(--sv-semantic-danger-soft);color:var(--sv-semantic-danger);font-size:13px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}.history-detail-intro{padding:0 0 16px}.history-detail-pagination{margin-top:20px}
@media(max-width:1150px){.popular-heading{display:none}.popular-row{grid-template-columns:minmax(180px,1.5fr) minmax(120px,1fr) 90px}.popular-song{grid-row:span 2}.popular-effective{grid-column:2}.popular-row>time{grid-column:2;grid-row:2}.popular-row>.el-button{grid-column:3;grid-row:2}.popular-count{grid-column:3;grid-row:1}}
@media(max-width:650px){.history-stats{grid-template-columns:repeat(2,minmax(0,1fr))}.history-filters>.el-input{max-width:none}.popular-row{grid-template-columns:minmax(0,1fr) auto;gap:10px;padding:16px}.popular-song{grid-column:1/-1;grid-row:auto}.popular-effective{grid-column:1;grid-row:auto}.popular-count{grid-column:2;grid-row:2}.popular-row>time{grid-column:1;grid-row:3}.popular-row>.el-button{grid-column:2;grid-row:3}.history-results-footer{padding:14px 16px}.history-stats>div{padding:14px}.history-stats strong{font-size:23px}.history-tabs{margin:0 16px}}
</style>
