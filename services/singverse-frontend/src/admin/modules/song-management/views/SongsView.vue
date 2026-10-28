<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElEmpty, ElInput, ElPagination } from 'element-plus'
import { RefreshRight, Search, FolderOpened } from '@element-plus/icons-vue'
import { classification } from '../../classification/api/classification'
import ClassificationEditor from '../../classification/components/ClassificationEditor.vue'
import type { Tag } from '../../classification/types'
import SingleSongScrapeDialog from '../../metadata/components/SingleSongScrapeDialog.vue'
import { singleReview } from '../../metadata/api/single-review'
import SongVideoDialog from '../components/SongVideoDialog.vue'
import SongCard from '../components/SongCard.vue'
import SongDetailDrawer from '../components/SongDetailDrawer.vue'
import QuickFilters from '../components/QuickFilters.vue'
import { songs } from '../api/songs'
import type { MediaSong, MediaSource } from '../../media-library/types'

const route = useRoute(), router = useRouter()
const items = ref<MediaSong[]>([]), sources = ref<MediaSource[]>([]), total = ref(0), page = ref(1)
const pageSize = ref(20)
const tags = ref<Tag[]>([]), tagIds = ref<string[]>([]), unclassified = ref(false), classificationError = ref(''), editingClasses = ref<string[]>([])
async function loadTags() { try { tags.value = await classification.tags(); classificationError.value = '' } catch (e) { classificationError.value = e instanceof Error ? e.message : '分类词表读取失败' } }
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
function classifySelected() { router.push({ path: '/library/classification', query: selectedIds.value.length ? { songIds: selectedIds.value.join(',') } : {} }) }
function openScrape() { router.push({ path: '/library/songs/metadata', query: selectedIds.value.length ? { songIds: selectedIds.value.join(',') } : {} }) }

function openDetail(song: MediaSong) { detailSong.value = song }
function openReview(song: MediaSong) { detailSong.value = null; scraping.value = song }

const filtered = computed(() => !!sourceId.value || !!keyword.value.trim() || status.value !== 'ALL' || tagIds.value.length > 0 || unclassified.value)

// 统计
const reviewCount = computed(() => items.value.filter(s => s.result.needsReview).length)
const unclassifiedCount = computed(() => items.value.filter(s => !Object.keys(s.classification?.dimensions ?? {}).length).length)
const matchedCount = computed(() => items.value.filter(s => !s.result.needsReview).length)

let active = true, generation = 0, reviewGeneration = 0
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
    const result = await songs.list({ sourceId: typeof route.query.sourceId === 'string' ? route.query.sourceId : '', q: typeof route.query.q === 'string' ? route.query.q : '', status: typeof route.query.status === 'string' ? route.query.status : 'ALL', page: page.value - 1, ...(typeof route.query.tagIds === 'string' && route.query.tagIds ? { tagIds: route.query.tagIds.split(',') } : {}), ...(route.query.unclassified === 'true' ? { unclassified: true } : {}), ...(pageSize.value === 20 ? {} : { size: pageSize.value }) })
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
  if (keyword.value.trim()) query.q = keyword.value.trim()
  if (status.value !== 'ALL' || tagIds.value.length > 0 || unclassified.value) query.status = status.value
  if (tagIds.value.length) query.tagIds = tagIds.value.join(',')
  if (unclassified.value) query.unclassified = 'true'
  if (pageSize.value !== 20) query.size = String(pageSize.value)
  if (nextPage > 1) query.page = String(nextPage)
  const target = router.resolve({ path: '/library/songs', query })
  if (target.fullPath === route.fullPath) { page.value = nextPage; await load() }
  else await router.replace(target.fullPath)
}
async function reset() {
  sourceId.value = ''; keyword.value = ''; status.value = 'ALL'; tagIds.value = []; unclassified.value = false; await apply()
}
watch(() => route.query, query => {
  tagIds.value = typeof query.tagIds === 'string' ? query.tagIds.split(',').filter(Boolean) : []
  unclassified.value = query.unclassified === 'true'
  sourceId.value = typeof query.sourceId === 'string' ? query.sourceId : ''
  keyword.value = typeof query.q === 'string' ? query.q : ''
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
  <section class="songs-page">
    <div class="page-heading">
      <div>
        <h1>歌曲库</h1>
        <p class="page-description">扫描得到的全部歌曲，统一查看、分类与元数据管理。</p>
      </div>
      <div class="page-actions">
        <ElButton @click="router.push('/library/songs/metadata')">刮削元数据</ElButton>
        <ElButton :icon="FolderOpened" @click="router.push('/library/media')">管理来源</ElButton>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div v-if="loaded && total > 0" class="songs-stats">
      <div class="stat-card stat-warning">
        <span class="stat-icon">⚠️</span>
        <div class="stat-content">
          <strong class="stat-value">{{ reviewCount }}</strong>
          <span class="stat-label">待复核</span>
        </div>
      </div>
      <div class="stat-card stat-info">
        <span class="stat-icon">📦</span>
        <div class="stat-content">
          <strong class="stat-value">{{ unclassifiedCount }}</strong>
          <span class="stat-label">未分类</span>
        </div>
      </div>
      <div class="stat-card stat-success">
        <span class="stat-icon">✅</span>
        <div class="stat-content">
          <strong class="stat-value">{{ matchedCount }}</strong>
          <span class="stat-label">完整</span>
        </div>
      </div>
    </div>

    <!-- 筛选与搜索 -->
    <ElCard shadow="never" class="filters-card">
      <div class="filters-row">
        <ElInput
          v-model="keyword"
          placeholder="搜索歌曲或文件名..."
          :prefix-icon="Search"
          maxlength="200"
          clearable
          @keyup.enter="apply()"
          class="search-input"
        />
        <ElButton type="primary" @click="apply()" :loading="loading">搜索</ElButton>
      </div>

      <QuickFilters
        v-model:status="status"
        v-model:unclassified="unclassified"
        v-model:tag-ids="tagIds"
        @update:status="apply()"
        @update:unclassified="apply()"
      />
    </ElCard>

    <!-- 操作栏 -->
    <div class="songs-toolbar">
      <div class="toolbar-left">
        <h2>全部歌曲 <span class="count-badge">{{ total }}</span></h2>
      </div>
      <div class="toolbar-right">
        <ElButton
          v-if="!selectionMode"
          @click="enterSelectionMode"
        >
          批量操作
        </ElButton>
        <ElButton :icon="RefreshRight" :loading="loading" @click="load">刷新</ElButton>
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

    <!-- 歌曲卡片流 -->
    <div v-if="items.length" class="songs-grid">
      <SongCard
        v-for="song in items"
        :key="song.id"
        :song="song"
        :selection-mode="selectionMode"
        :selected="selectedIds.includes(song.id)"
        @play="playing = song"
        @edit="openReview(song)"
        @click="openDetail(song)"
        @select="toggleSelection"
      />
    </div>

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
    <Teleport to="body">
      <div v-if="selectionMode" class="batch-toolbar">
        <div class="batch-info">
          <span class="batch-count">已选 {{ selectedIds.length }} 首</span>
        </div>
        <div class="batch-actions">
          <ElButton v-if="selectedIds.length" type="primary" @click="openScrape">刮削元数据</ElButton>
          <ElButton v-if="selectedIds.length" @click="classifySelected">AI 分类</ElButton>
          <ElButton @click="exitSelectionMode">取消</ElButton>
        </div>
      </div>
    </Teleport>

    <!-- 播放弹窗 -->
    <SongVideoDialog :song="playing" @close="playing = null" />

    <!-- 详情抽屉 -->
    <SongDetailDrawer
      :song="detailSong"
      :visible="!!detailSong"
      @close="detailSong = null"
      @play="playing = detailSong"
      @edit="openReview(detailSong!)"
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
  max-width: 1400px;
}

.page-heading {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.page-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.songs-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-5);
}

.stat-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  background: var(--surface-2);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
}

.stat-card.stat-warning { border-left: 3px solid var(--semantic-warn); }
.stat-card.stat-info { border-left: 3px solid var(--accent-primary); }
.stat-card.stat-success { border-left: 3px solid var(--semantic-ok); }

.stat-icon {
  font-size: 32px;
  line-height: 1;
}

.stat-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-value {
  font-size: 28px;
  font-weight: 680;
  color: var(--text-primary);
  line-height: 1;
  font-family: var(--font-mono);
}

.stat-label {
  font-size: var(--font-meta);
  color: var(--text-secondary);
}

.filters-card {
  margin-bottom: var(--space-5);
}

.filters-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.filters-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-3);
}

.search-input {
  min-width: 0;
}

.songs-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
}

.toolbar-left h2 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.toolbar-right {
  display: flex;
  gap: var(--space-2);
}

.error-alert {
  margin-bottom: var(--space-5);
}

.songs-empty {
  padding: var(--space-10) var(--space-5);
}

.songs-grid {
  display: grid;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
}

.songs-pagination {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-5);
}

.page-size-selector {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-meta);
  color: var(--text-secondary);
}

.page-size-selector select {
  min-width: 80px;
}

.batch-toolbar {
  position: fixed;
  bottom: 0;
  left: var(--sidebar-width);
  right: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-8);
  background: var(--surface-2);
  border-top: 1px solid var(--accent-primary);
  box-shadow: 0 -4px 12px rgb(0 0 0 / 0.32);
  backdrop-filter: blur(12px);
}

.batch-info {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.batch-count {
  font-size: var(--font-body);
  font-weight: 620;
  color: var(--accent-primary);
}

.batch-actions {
  display: flex;
  gap: var(--space-2);
}

@media (max-width: 1200px) {
  .batch-toolbar {
    left: 0;
  }
}

@media (max-width: 768px) {
  .page-heading {
    flex-direction: column;
    align-items: flex-start;
  }

  .songs-stats {
    grid-template-columns: 1fr;
  }

  .filters-row {
    grid-template-columns: 1fr;
  }

  .songs-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar-left,
  .toolbar-right {
    width: 100%;
  }

  .toolbar-right {
    justify-content: space-between;
  }

  .batch-toolbar {
    flex-direction: column;
    gap: var(--space-3);
    align-items: stretch;
  }

  .batch-actions {
    width: 100%;
  }

  .batch-actions .el-button {
    flex: 1;
  }
}
</style>
