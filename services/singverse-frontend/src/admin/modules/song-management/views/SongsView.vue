<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElEmpty, ElInput, ElPagination, ElSelect, ElOption, ElSkeleton, ElPopover } from 'element-plus'
import { RefreshRight, Search, EditPen, Grid, List, Plus, Filter } from '@element-plus/icons-vue'
import { playback as transcoding } from '../../transcoding/playback'
import { ElMessage, ElMessageBox } from 'element-plus'
import { classification } from '../../classification/api/classification'
import ClassificationEditor from '../../classification/components/ClassificationEditor.vue'
import type { Tag } from '../../classification/types'
import SingleSongScrapeDialog from '../../metadata/components/SingleSongScrapeDialog.vue'
import { singleReview } from '../../metadata/api/single-review'
import SongVideoDialog from '../components/SongVideoDialog.vue'
import SongCard from '../components/SongCard.vue'
import SongLibraryTable from '../components/SongLibraryTable.vue'
import { songColumns, defaultSongColumns, songTags, needsSongReview, identityReviewed, type SongColumn } from '../presentation'
import SongDetailDrawer from '../components/SongDetailDrawer.vue'
import { songs } from '../api/songs'
import type { MediaSong, MediaSource } from '../../media-library/types'

import { embeddedMetadata } from '../../media-library/api/embeddedMetadata'
const refreshingEmbedded = ref(false)
async function refreshEmbedded(deep = false) {
  if (refreshingEmbedded.value || !selectedIds.value.length) return
  const ids = [...selectedIds.value]
  if (deep) try { await ElMessageBox.confirm(`为选中的 ${ids.length} 首歌曲执行深度提取，每首最多读取 128 MiB，仍遵守来源请求间隔与冷却。`, '深度提取内嵌元数据', { confirmButtonText: '加入提取队列', cancelButtonText: '取消' }) } catch { return }
  refreshingEmbedded.value = true
  try { const result = await embeddedMetadata.refresh(ids, deep); ElMessage.success(`已提交 ${result.accepted} 首内嵌元数据提取任务`) }
  catch (e) { ElMessage.error(e instanceof Error ? e.message : '提交失败') }
  finally { refreshingEmbedded.value = false }
}
const route = useRoute(), router = useRouter()
const columns = ref<SongColumn[]>([...defaultSongColumns]), compact = ref(false)
const view = ref<'covers' | 'list'>('covers'), searchMode = ref<'identity' | 'path'>('identity')
const mobileFiltersOpen = ref(false)
const appliedFilterCount = computed(() => Number(!!route.query.sourceId)
  + (typeof route.query.tagIds === 'string' ? route.query.tagIds.split(',').filter(Boolean).length : 0)
  + Number(!!route.query.status && route.query.status !== 'ALL') + Number(route.query.unclassified === 'true'))
const tagsLoaded = ref(false)
const visibleRange = computed(() => items.value.length ? `${(page.value - 1) * pageSize.value + 1}–${(page.value - 1) * pageSize.value + items.value.length}` : '0')
const items = ref<MediaSong[]>([]), sources = ref<MediaSource[]>([]), total = ref(0), page = ref(1)
const pageSize = ref(20)
const tags = ref<Tag[]>([]), tagIds = ref<string[]>([]), unclassified = ref(false), classificationError = ref(''), editingClasses = ref<string[]>([])
async function loadTags() { try { const result = await classification.tags(); if (!active) return; tags.value = result; tagsLoaded.value = true; classificationError.value = '' } catch (e) { classificationError.value = e instanceof Error ? e.message : '分类词表读取失败' } }
async function changeSize() { await apply(1) }
const sourceId = ref(''), keyword = ref(''), status = ref('ALL')
const loading = ref(false), loaded = ref(false), error = ref(''), sourcesError = ref('')
const playing = ref<MediaSong | null>(null)
const detailSong = ref<MediaSong | null>(null), scraping = ref<MediaSong | null>(null)
const selectionMode = ref(false), selectedIds = ref<string[]>([])

// 批量操作模式
function enterSelectionMode() { selectionMode.value = true; selectedIds.value = [] }
function exitSelectionMode() { selectionMode.value = false; selectedIds.value = [] }
function toggleSelection(song: MediaSong, selected: boolean) {
  if (selected) {
    if (!selectedIds.value.includes(song.id)) selectedIds.value.push(song.id)
  } else {
    selectedIds.value = selectedIds.value.filter(id => id !== song.id)
  }
}
async function convertSelected() { try { await transcoding.offline(selectedIds.value, '1080p'); ElMessage.success('已加入兼容版本转换队列') } catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '提交失败') } }
function classifySelected() { router.push({ path: '/library/classification', query: selectedIds.value.length ? { songIds: selectedIds.value.join(',') } : {} }) }
function openScrape() { router.push({ path: '/library/songs/metadata', query: selectedIds.value.length ? { songIds: selectedIds.value.join(',') } : {} }) }

function openDetail(song: MediaSong) { detailSong.value = song }
function openReview(song: MediaSong) { detailSong.value = null; scraping.value = song }

const filtered = computed(() => !!route.query.sourceId || !!route.query.q || !!route.query.search || (typeof route.query.status === 'string' && route.query.status !== 'ALL') || !!route.query.tagIds || route.query.unclassified === 'true')
const returnBatch = computed(() => (typeof route.query.returnBatch === 'string' ? route.query.returnBatch : ''))

// 统计
const reviewCount = computed(() => items.value.filter(needsSongReview).length)
const unclassifiedCount = computed(() => items.value.filter(s => !songTags(s, tags.value).some(tag => tag.active)).length)
const reviewedCount = computed(() => items.value.filter(identityReviewed).length)
const matchedCount = computed(() => items.value.filter(s => !s.result.needsReview && !identityReviewed(s)).length)

let active = true, generation = 0, reviewGeneration = 0, detailGeneration = 0
watch(() => route.query.detailSongId, async id => {
  const token = ++detailGeneration
  if (typeof id !== 'string') return
  try { const context = await singleReview.load(id); if (active && token === detailGeneration) detailSong.value = context.song }
  catch (failure) { if (active && token === detailGeneration) error.value = failure instanceof Error ? failure.message : '读取单曲失败' }
}, { immediate: true })
watch(() => route.query.reviewSongId, async id => {
  const token = ++reviewGeneration
  if (typeof id !== 'string') return
  try { const context = await singleReview.load(id); if (active && token === reviewGeneration) scraping.value = context.song }
  catch (failure) { if (active && token === reviewGeneration) error.value = failure instanceof Error ? failure.message : '读取单曲失败' }
}, { immediate: true })

async function load() {
  const token = ++generation
  loading.value = true; error.value = ''; items.value = []; selectedIds.value = []
  try {
    const result = await songs.list({ sourceId: typeof route.query.sourceId === 'string' ? route.query.sourceId : '', q: typeof route.query.q === 'string' ? route.query.q : '', ...(typeof route.query.search === 'string' && route.query.search ? { search: route.query.search } : {}), status: typeof route.query.status === 'string' ? route.query.status : 'ALL', page: page.value - 1, ...(typeof route.query.tagIds === 'string' && route.query.tagIds ? { tagIds: route.query.tagIds.split(',') } : {}), ...(route.query.unclassified === 'true' ? { unclassified: true } : {}), ...(pageSize.value === 20 ? {} : { size: pageSize.value }) })
    if (!active || token !== generation) return
    items.value = result.items; total.value = result.totalElements; loaded.value = true
  } catch (failure) { if (active && token === generation) error.value = failure instanceof Error ? failure.message : '读取歌曲失败' }
  finally { if (active && token === generation) loading.value = false }
}
async function loadSources() {
  sourcesError.value = ''
  try { const result = await songs.sources(); if (active) sources.value = result }
  catch (failure) { if (active) sourcesError.value = failure instanceof Error ? failure.message : '读取媒体来源失败' }
}
async function apply(nextPage = 1) {
  const query: Record<string, string> = {}
  if (sourceId.value) query.sourceId = sourceId.value
  if (keyword.value.trim()) query[searchMode.value === 'path' ? 'q' : 'search'] = keyword.value.trim()
  if (status.value !== 'ALL' || tagIds.value.length > 0 || unclassified.value) query.status = status.value
  if (tagIds.value.length) query.tagIds = tagIds.value.join(',')
  if (unclassified.value) query.unclassified = 'true'
  if (pageSize.value !== 20) query.size = String(pageSize.value)
  if (nextPage > 1) query.page = String(nextPage)
  if (Object.keys(query).length) query.lens = 'songs'
  const target = router.resolve({ path: '/library/songs', query })
  if (target.fullPath === route.fullPath) { page.value = nextPage; await load() }
  else await router.replace(target.fullPath)
}
function openFormalLens() {
  const query: Record<string, string> = { lens: 'formal' }
  if (searchMode.value === 'identity' && keyword.value.trim()) query.search = keyword.value.trim()
  if (sourceId.value) query.sourceId = sourceId.value
  if (page.value > 1) query.page = String(page.value)
  void router.replace({ path: '/library/songs', query })
}
async function reset() {
  sourceId.value = ''; keyword.value = ''; status.value = 'ALL'; tagIds.value = []; unclassified.value = false; await apply()
}
watch(() => route.query, query => {
  tagIds.value = typeof query.tagIds === 'string' ? query.tagIds.split(',').filter(Boolean) : []
  unclassified.value = query.unclassified === 'true'
  sourceId.value = typeof query.sourceId === 'string' ? query.sourceId : ''
  searchMode.value = typeof query.q === 'string' && !query.search ? 'path' : 'identity'
  keyword.value = typeof query.search === 'string' ? query.search : typeof query.q === 'string' ? query.q : ''
  status.value = typeof query.status === 'string' ? query.status : 'ALL'
  pageSize.value = [20, 50, 100].includes(Number(query.size)) ? Number(query.size) : 20
  const requested = Number(query.page ?? 1)
  page.value = Number.isInteger(requested) && requested > 0 && requested <= 1000001 ? requested : 1
  void load()
}, { immediate: true })
onMounted(() => { void loadSources(); void loadTags() })
onBeforeUnmount(() => { active = false; generation++ })
</script>

<template>
  <section class="songs-page" :class="{ 'is-selecting': selectionMode }">
    <PageHeader title="你的每一首好歌" description="收藏、整理，为下一次开唱做好准备。试听用来检查歌曲与播放效果。">
      <template #actions>
        <div class="page-actions">
          <ElButton text @click="openFormalLens">歌曲资料 · 高级</ElButton>
          <ElButton text :icon="EditPen" @click="router.push('/library/songs/metadata')">整理元数据</ElButton>
          <ElButton type="primary" :icon="Plus" @click="router.push('/library/media')">添加歌曲</ElButton>
        </div>
      </template>
    </PageHeader>

    <div v-if="returnBatch" class="return-batch-bar" role="status">
      <span>正在处理批次中的单曲，核对后可返回批次继续。</span>
      <ElButton text type="primary" @click="router.push({ path: '/library/songs/metadata', query: { batch: returnBatch } })">返回批次</ElButton>
    </div>

    <!-- 筛选与搜索 -->
    <ElCard shadow="never" class="filters-card" aria-label="歌曲搜索与筛选">
      <div class="filters-row">
        <select v-model="searchMode" aria-label="歌曲搜索范围" class="search-mode"><option value="identity">歌曲与歌手</option><option value="path">文件路径</option></select>
        <ElInput
          v-model="keyword"
          :placeholder="searchMode === 'path' ? '搜索文件名或路径' : '搜索歌名、歌手，也可以输入文件名'"
          aria-label="搜索歌曲"
          :prefix-icon="Search"
          maxlength="200"
          clearable
          @keyup.enter="apply()"
          class="search-input"
        />
        <ElButton type="primary" @click="apply()" :loading="loading">搜索</ElButton>
      </div>

      <ElButton class="mobile-filter-toggle" text :icon="Filter" :aria-expanded="mobileFiltersOpen" aria-controls="song-extra-filters" @click="mobileFiltersOpen = !mobileFiltersOpen">{{ mobileFiltersOpen ? '收起筛选' : '来源与分类筛选' }}<span v-if="appliedFilterCount"> · 已选 {{ appliedFilterCount }} 项</span></ElButton>
      <div id="song-extra-filters" class="songs-filter-selects" :class="{ expanded: mobileFiltersOpen }">
        <ElSelect v-model="sourceId" aria-label="歌曲来源" placeholder="全部来源" filterable clearable @change="apply()"><ElOption label="全部来源" value="" /><ElOption v-for="source in sources" :key="source.id" :label="source.name" :value="source.id" /></ElSelect>
        <ElSelect v-model="tagIds" aria-label="分类标签" placeholder="按分类筛选" filterable multiple collapse-tags clearable @change="apply()"><ElOption v-for="tag in tags.filter(t => t.active)" :key="tag.id" :label="tag.name" :value="tag.id" /></ElSelect>
        <ElSelect v-model="status" aria-label="文件名识别状态" @change="apply()"><ElOption label="全部识别状态" value="ALL" /><ElOption label="文件名待复核" value="REVIEW" /><ElOption label="文件名完整" value="MATCHED" /></ElSelect>
      </div>
      <p v-if="sourcesError" class="filter-load-error">{{ sourcesError }} <ElButton link @click="loadSources">重试来源</ElButton></p>
      <p v-if="classificationError" class="filter-load-error">{{ classificationError }} <ElButton link @click="loadTags">重试分类</ElButton></p>
      <ElButton v-if="filtered || keyword.trim() || sourceId || tagIds.length" class="filter-reset" text @click="reset">重置筛选</ElButton>
    </ElCard>

    <!-- 操作栏 -->
    <div class="songs-toolbar">
      <div class="toolbar-left">
        <h2>{{ filtered ? '筛选结果' : '歌曲列表' }} <span class="count-badge">{{ total }}</span></h2>
        <span v-if="loaded && items.length" class="page-range">第 {{ visibleRange }} 首</span><span v-if="view === 'list'" class="table-scroll-hint">更多列可横向滚动</span>
      </div>
      <div class="toolbar-right">
        <label class="page-size-selector top-page-size">每页<select v-model.number="pageSize" aria-label="每页歌曲数" :disabled="loading" @change="changeSize"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select>首</label>
        <div class="view-switch" role="group" aria-label="歌曲显示方式"><button type="button" :aria-pressed="view === 'covers'" aria-label="封面浏览" @click="view = 'covers'"><Grid /></button><button type="button" :aria-pressed="view === 'list'" aria-label="列表整理" @click="view = 'list'"><List /></button></div>
        <label v-if="view === 'list'" class="density-control"><input v-model="compact" type="checkbox">紧凑显示</label>
        <ElPopover v-if="view === 'list'" placement="bottom-end" :width="245" trigger="click">
          <template #reference><ElButton class="column-settings-button">显示列</ElButton></template>
          <div class="column-settings"><strong>自定义表格信息</strong><p>歌曲、歌手和操作始终显示</p><label v-for="column in songColumns" :key="column.id"><input v-model="columns" type="checkbox" :value="column.id">{{ column.label }}</label><ElButton text type="primary" @click="columns = [...defaultSongColumns]">恢复默认列</ElButton></div>
        </ElPopover>
        <ElButton
          v-if="!selectionMode"
          @click="enterSelectionMode"
        >
          批量操作
        </ElButton>
        <ElButton :icon="RefreshRight" :loading="loading" @click="load">刷新</ElButton>
      </div>
    </div>

    <div v-if="loaded && items.length && !loading" class="page-summary" aria-label="当前页歌曲统计"><span>仅本页</span><span><b>{{ reviewCount }}</b> 待复核</span><span><b>{{ matchedCount }}</b> 文件名完整</span><span v-if="reviewedCount"><b>{{ reviewedCount }}</b> 已复核</span><span v-if="tagsLoaded"><b>{{ unclassifiedCount }}</b> 未分类</span></div>


      <div v-if="selectionMode" class="batch-toolbar" role="region" aria-label="歌曲批量操作">
        <div class="batch-info">
          <span class="batch-count">已选 {{ selectedIds.length }} 首 · 本页</span><ElButton text @click="selectedIds = items.map(song => song.id)">全选本页</ElButton><ElButton v-if="selectedIds.length" text @click="selectedIds = []">清空选择</ElButton>
        </div>
        <div class="batch-actions">
          <ElButton :disabled="!selectedIds.length" type="primary" @click="openScrape">刮削元数据</ElButton>
          <ElButton :disabled="!selectedIds.length || refreshingEmbedded" @click="refreshEmbedded()">刷新内嵌元数据</ElButton>
          <ElButton :disabled="!selectedIds.length || refreshingEmbedded" @click="refreshEmbedded(true)">深度提取</ElButton>
          <ElButton :disabled="!selectedIds.length" @click="classifySelected">AI 分类</ElButton>
          <ElButton :disabled="!selectedIds.length" @click="convertSelected">生成兼容版本</ElButton>
          <ElButton @click="exitSelectionMode">取消</ElButton>
        </div>
      </div>

    <!-- 错误提示 -->
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon class="error-alert">
      <ElButton text @click="load">重试</ElButton>
    </ElAlert>

    <!-- 空状态 -->
    <ElEmpty
      v-if="loaded && !total && !error && !loading"
      :description="filtered ? '未找到符合条件的歌曲' : '还没有歌曲，先添加媒体来源并扫描'"
      class="songs-empty"
    >
      <ElButton v-if="filtered" @click="reset">清除筛选</ElButton>
      <ElButton v-else type="primary" @click="router.push('/library/media')">前往添加来源</ElButton>
    </ElEmpty>

    <ElSkeleton v-if="loading && !items.length" :rows="5" animated aria-label="正在加载歌曲" />
    <div v-if="items.length" :aria-busy="loading" class="songs-results">
      <SongLibraryTable v-if="view === 'list'" class="desktop-song-table" :items="items" :tags="tags" :columns="columns" :compact="compact" :selection-mode="selectionMode" :selected-ids="selectedIds" @play="playing = $event" @review="openReview" @detail="openDetail" @select="toggleSelection" @select-page="selectedIds = $event ? items.map(song => song.id) : []" />
      <div v-if="view === 'covers'" class="songs-grid"><SongCard v-for="song in items" :key="song.id" :song="song" :tags="tags" :selection-mode="selectionMode" :selected="selectedIds.includes(song.id)" @play="playing = song" @edit="openReview(song)" @click="openDetail(song)" @select="toggleSelection" /></div>
    </div>
    <ElEmpty v-if="loaded && total > 0 && !items.length && !loading && !error" description="这一页已经没有歌曲"><ElButton @click="apply(1)">返回第一页</ElButton></ElEmpty>

    <!-- 分页 -->
    <div v-if="total > 0" class="songs-pagination">
      <label class="page-size-selector">
        每页
        <select v-model.number="pageSize" @change="changeSize" :disabled="loading">
          <option :value="20">20</option>
          <option :value="50">50</option>
          <option :value="100">100</option>
        </select>
        条
      </label>
      <ElPagination
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next, jumper"
        :disabled="loading"
        @current-change="apply"
      />
    </div>

    <!-- 批量操作工具栏 -->


    <!-- 播放弹窗 -->
    <SongVideoDialog :song="playing" @close="playing = null" />

    <!-- 详情抽屉 -->
    <SongDetailDrawer
      :song="detailSong"
      :tags="tags"
      :visible="!!detailSong"
      @close="detailSong = null"
      @play="playing = detailSong"
      @edit="openReview(detailSong!)"
      @classify="editingClasses = detailSong ? [detailSong.id] : []; detailSong = null"
    />

    <!-- 元数据刮削弹窗 -->
    <SingleSongScrapeDialog :song="scraping" @close="scraping = null" @applied="load" />

    <!-- 分类编辑器 -->
    <ClassificationEditor
      v-if="editingClasses.length"
      :song-ids="editingClasses"
      @close="editingClasses = []"
      @changed="load(); loadTags()"
    />

    <p class="feature-note">列表来自本地目录或 WebDAV 的已保存扫描索引；在媒体库重新扫描后更新。</p>
  </section>
</template>



<style scoped>
.songs-page {
  min-width: 0
}

.page-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap
}

.page-actions :deep(.el-button) {
  margin: 0
}

.filters-card {
  margin-bottom: 26px;
  border-radius: 12px;
  background: var(--sv-surface-1)
}

.filters-card :deep(.el-card__body) {
  padding: 18px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px
}

.filters-row {
  display: flex;
  gap: 8px;
  flex: 1 1 540px;
  min-width: 0
}

.search-mode {
  width: 135px;
  flex: none;
  font-size: 12px;
  padding-left: 10px
}

.search-input {
  min-width: 0;
  flex: 1
}

.songs-filter-selects {
  display: flex;
  gap: 10px;
  flex: 1 1 400px;
  min-width: 0
}

.songs-filter-selects>* {
  min-width: 0;
  flex: 1
}

.mobile-filter-toggle.el-button {
  display: none
}

.filter-reset {
  margin: 0;
  font-size: 12px
}

.filter-load-error {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--sv-semantic-danger)
}

.songs-toolbar,
.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px
}

.songs-toolbar {
  justify-content: space-between;
  margin-bottom: 14px
}

.toolbar-left h2 {
  font-size: 17px
}

.page-range,
.table-scroll-hint {
  font-size: 11px;
  color: var(--sv-text-tertiary)
}

.table-scroll-hint {
  display: none
}

.toolbar-right {
  gap: 8px
}

.toolbar-right :deep(.el-button) {
  margin: 0;
  font-size: 12px
}

.page-size-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--sv-text-secondary)
}

.page-size-selector select {
  height: 34px;
  padding: 0 25px 0 9px;
  font-size: 11px
}

.density-control {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--sv-text-secondary)
}

.density-control input {
  accent-color: var(--sv-accent-primary)
}

.view-switch {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--sv-surface-3);
  border-radius: 7px
}

.view-switch button {
  display: grid;
  place-items: center;
  width: 32px;
  height: 30px;
  padding: 7px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--sv-text-tertiary);
  cursor: pointer
}

.view-switch button[aria-pressed=true] {
  background: var(--sv-surface-2);
  color: var(--sv-accent-primary);
  box-shadow: var(--sv-shadow-sm)
}

.view-switch svg {
  width: 18px;
  height: 18px
}

.column-settings {
  display: grid;
  gap: 10px;
  padding: 6px;
  font-size: 12px
}

.column-settings>p {
  font-size: 11px;
  color: var(--sv-text-secondary)
}

.column-settings label {
  display: flex;
  gap: 8px;
  align-items: center;
  min-height: 30px
}

.column-settings input {
  accent-color: var(--sv-accent-primary)
}

.page-summary {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 20px;
  font-size: 11px;
  color: var(--sv-text-secondary)
}

.page-summary>span:first-child {
  font-size: 10px;
  color: var(--sv-text-tertiary);
  padding: 2px 6px;
  background: var(--sv-surface-3);
  border-radius: 4px
}

.page-summary b {
  font-weight: 600;
  color: var(--sv-text-primary)
}

.songs-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 18px
}

.batch-toolbar {
  position: sticky;
  top: calc(var(--sv-topbar-height) + 8px);
  z-index: 15;
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 14px 20px;
  margin-bottom: 18px;
  background: var(--sv-accent-soft);
  border: 1px solid var(--sv-border-default);
  border-radius: var(--sv-radius-lg);
  box-shadow: var(--sv-shadow-md)
}

.return-batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 16px;
  margin-bottom: 18px;
  font-size: 12px;
  color: var(--sv-text-secondary);
  background: var(--sv-surface-3);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-md)
}

.return-batch-bar :deep(.el-button) {
  margin: 0
}

.batch-info,
.batch-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap
}

.batch-count {
  font-size: 12px;
  font-weight: 600
}

.batch-actions :deep(.el-button),
.batch-info :deep(.el-button) {
  margin: 0;
  font-size: 11px
}

.error-alert {
  margin-bottom: 18px
}

.songs-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-top: 28px
}

.feature-note {
  font-size: 11px
}

.songs-empty {
  background: var(--sv-surface-2);
  border: 1px dashed var(--sv-border-default);
  border-radius: 12px
}

.songs-results {
  min-width: 0
}

@media(min-width:1700px) {
  .songs-grid {
    grid-template-columns: repeat(6, minmax(0, 1fr))
  }
}

@media(max-width:1200px) {
  .songs-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px
  }

  .toolbar-right {
    gap: 6px
  }

  .table-scroll-hint {
    display: inline
  }
}

@media(max-width:900px) {
  .songs-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr))
  }

  .filters-row {
    flex-basis: 100%
  }

  .songs-filter-selects {
    flex-basis: 100%
  }

  .batch-toolbar {
    top: calc(var(--sv-topbar-height) + 8px)
  }

  .songs-toolbar {
    align-items: flex-start
  }

  .toolbar-right {
    justify-content: flex-start
  }

  .top-page-size {
    display: none
  }
}

@media(max-width:550px) {
  .songs-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px
  }

  .filters-card :deep(.el-card__body) {
    padding: 12px;
    gap: 10px
  }

  .filters-row {
    flex-wrap: wrap
  }

  .search-mode {
    width: 124px;
    height: 36px
  }

  .search-input {
    flex-basis: calc(100% - 138px)
  }

  .filters-row>.el-button {
    width: 100%;
    margin: 0
  }

  .songs-filter-selects {
    display: none;
    flex-wrap: wrap;
    gap: 8px
  }

  .songs-filter-selects.expanded {
    display: flex
  }

  .mobile-filter-toggle.el-button {
    display: inline-flex;
    width: 100%;
    justify-content: flex-start;
    padding: 6px 0;
    margin: 0;
    min-height: 36px;
    font-size: 12px
  }

  .songs-filter-selects>:last-child {
    flex-basis: 100%
  }

  .songs-toolbar {
    gap: 12px
  }

  .toolbar-left {
    justify-content: space-between;
    width: 100%
  }

  .toolbar-right {
    width: 100%;
    gap: 6px
  }

  .toolbar-right>.el-button {
    flex: 1
  }

  .density-control {
    display: none
  }

  .page-summary {
    font-size: 10px;
    gap: 8px;
    margin-bottom: 16px
  }

  .batch-toolbar {
    padding: 12px;
    position: relative;
    top: 0
  }

  .batch-actions {
    gap: 6px;
    width: 100%
  }

  .batch-actions>.el-button {
    flex: 1
  }

  .songs-pagination {
    gap: 18px
  }

  .songs-pagination :deep(.el-pagination) {
    font-size: 11px
  }

  .songs-pagination :deep(.el-pagination__jump) {
    display: none
  }
}
</style>
