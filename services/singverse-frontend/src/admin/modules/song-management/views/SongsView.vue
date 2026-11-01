<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElEmpty, ElInput, ElPagination, ElSelect, ElOption, ElSkeleton, ElPopover } from 'element-plus'
import { RefreshRight, Search, FolderOpened, EditPen } from '@element-plus/icons-vue'
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
const columns = ref<SongColumn[]>([...defaultSongColumns]), compact = ref(true)
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

const filtered = computed(() => !!route.query.sourceId || !!route.query.q || (typeof route.query.status === 'string' && route.query.status !== 'ALL') || !!route.query.tagIds || route.query.unclassified === 'true')

// 统计
const reviewCount = computed(() => items.value.filter(needsSongReview).length)
const unclassifiedCount = computed(() => items.value.filter(s => !songTags(s, tags.value).some(tag => tag.active)).length)
const reviewedCount = computed(() => items.value.filter(identityReviewed).length)
const matchedCount = computed(() => items.value.filter(s => !s.result.needsReview && !identityReviewed(s)).length)

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
  <section class="songs-page" :class="{ 'is-selecting': selectionMode }">
    <PageHeader title="全部歌曲" description="媒体库扫描得到的全部歌曲。可筛选、播放、复核文件名识别结果或编辑歌曲信息。">
      <template #actions>
        <div class="page-actions">
          <ElButton text @click="router.push('/library/playlists')">歌单管理</ElButton>
          <ElButton text :icon="EditPen" @click="router.push('/library/songs/metadata')">整理元数据</ElButton>
          <ElButton text :icon="FolderOpened" @click="router.push('/library/media')">管理来源</ElButton>
        </div>
      </template>
    </PageHeader>

    <!-- 筛选与搜索 -->
    <ElCard shadow="never" class="filters-card" aria-label="歌曲搜索与筛选">
      <div class="filters-row">
        <ElInput
          v-model="keyword"
          placeholder="搜索文件名（歌名 / 歌手）"
          aria-label="搜索歌曲文件"
          :prefix-icon="Search"
          maxlength="200"
          clearable
          @keyup.enter="apply()"
          class="search-input"
        />
        <ElButton type="primary" @click="apply()" :loading="loading">搜索</ElButton>
      </div>

      <div class="songs-filter-selects">
        <ElSelect v-model="sourceId" aria-label="歌曲来源" placeholder="全部来源" filterable clearable @change="apply()"><ElOption label="全部来源" value="" /><ElOption v-for="source in sources" :key="source.id" :label="source.name" :value="source.id" /></ElSelect>
        <ElSelect v-model="tagIds" aria-label="分类标签" placeholder="按分类筛选" filterable multiple collapse-tags clearable @change="apply()"><ElOption v-for="tag in tags.filter(t => t.active)" :key="tag.id" :label="tag.name" :value="tag.id" /></ElSelect>
      </div>
      <p v-if="sourcesError" class="filter-load-error">{{ sourcesError }} <ElButton link @click="loadSources">重试来源</ElButton></p>
      <p v-if="classificationError" class="filter-load-error">{{ classificationError }} <ElButton link @click="loadTags">重试分类</ElButton></p>
      <ElButton v-if="filtered || keyword.trim() || sourceId || tagIds.length" class="filter-reset" text @click="reset">重置筛选</ElButton>
    </ElCard>

    <!-- 操作栏 -->
    <div class="songs-toolbar">
      <div class="toolbar-left">
        <h2>{{ filtered ? '筛选结果' : '歌曲列表' }} <span class="count-badge">{{ total }}</span></h2>
        <span v-if="loaded && items.length" class="page-range">第 {{ visibleRange }} 首</span><span class="table-scroll-hint">更多列可横向滚动</span>
      </div>
      <div class="toolbar-right">
        <label class="page-size-selector top-page-size">每页<select v-model.number="pageSize" aria-label="每页歌曲数" :disabled="loading" @change="changeSize"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select>首</label>
        <label class="density-control"><input v-model="compact" type="checkbox">紧凑显示</label>
        <ElPopover placement="bottom-end" :width="245" trigger="click">
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
      <SongLibraryTable class="desktop-song-table" :items="items" :tags="tags" :columns="columns" :compact="compact" :selection-mode="selectionMode" :selected-ids="selectedIds" @play="playing = $event" @review="openReview" @detail="openDetail" @select="toggleSelection" @select-page="selectedIds = $event ? items.map(song => song.id) : []" />
      <div class="songs-grid mobile-song-list"><SongCard v-for="song in items" :key="song.id" :song="song" :tags="tags" :selection-mode="selectionMode" :selected="selectedIds.includes(song.id)" @play="playing = song" @edit="openReview(song)" @click="openDetail(song)" @select="toggleSelection" /></div>
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
.songs-page{min-width:0}.songs-page .page-heading{margin-bottom:14px;gap:12px;align-items:center}.songs-page .page-heading h1{font-size:21px;margin:0;line-height:1.4}.songs-page .page-description{font-size:13px;margin:0}.page-actions{display:flex;gap:4px;flex-wrap:wrap}.page-actions :deep(.el-button){font-size:12px;padding:8px 10px;color:var(--sv-text-secondary)}.page-actions :deep(.el-button){margin:0}.filters-card{margin-bottom:16px;border-radius:12px;box-shadow:none}.filters-card :deep(.el-card__body){padding:12px;display:grid;grid-template-columns:minmax(0,380px) minmax(0,420px) auto;gap:12px 16px;align-items:center;justify-content:start}.filters-row{display:flex;gap:8px;min-width:0}.songs-filter-selects{display:flex;gap:10px;min-width:0}.songs-filter-selects>*{min-width:0;flex:1}.filter-reset{margin:0;justify-self:start;font-size:12px}.filter-load-error{grid-column:1/-1;color:var(--sv-semantic-danger);font-size:12px;margin:0}.songs-toolbar,.toolbar-left,.toolbar-right{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.songs-toolbar{justify-content:space-between;margin-bottom:10px}.toolbar-left h2{font-size:15px;margin:0;display:flex;gap:8px;align-items:center}.table-scroll-hint{display:none;font-size:11px;color:var(--sv-text-tertiary)}.page-range{font-size:12px;color:var(--sv-text-secondary)}.toolbar-right :deep(.el-button){margin:0}.density-control{font-size:12px;color:var(--sv-text-secondary);display:flex;gap:5px;align-items:center;margin-right:4px}.density-control input{accent-color:var(--sv-accent-primary)}.page-summary{display:flex;align-items:center;gap:16px;flex-wrap:wrap;font-size:11px;color:var(--sv-text-secondary);margin-bottom:12px}.page-summary b{font-weight:600;color:var(--sv-text-primary)}.page-summary>span:first-child{padding:2px 6px;border-radius:4px;background:var(--sv-surface-3)}.songs-results{margin-bottom:16px}.mobile-song-list{display:none}.error-alert{margin-bottom:16px}.songs-pagination{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.page-size-selector{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--sv-text-secondary)}.page-size-selector select{min-width:66px}.top-page-size select{min-width:58px;padding:5px 8px}.feature-note{font-size:11px}.batch-toolbar{position:relative;display:flex;align-items:center;justify-content:space-between;padding:14px 20px;background:var(--sv-accent-soft);border:1px solid var(--sv-border-default);border-radius:var(--sv-radius-md);margin:0 0 14px;gap:16px}.batch-info,.batch-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.batch-count{color:var(--sv-accent-primary);font-weight:600;font-size:13px}.batch-actions :deep(.el-button){margin:0}
@media(max-width:1050px){.table-scroll-hint{display:inline}.filters-card :deep(.el-card__body){grid-template-columns:minmax(0,1fr) minmax(0,1fr) auto}.toolbar-right{gap:6px}}
@media(max-width:700px){.songs-page .page-heading h1{font-size:20px;white-space:nowrap}.songs-page .page-heading{align-items:center;flex-wrap:nowrap;gap:8px}.page-actions{gap:0;flex-wrap:nowrap}.page-actions :deep(.el-button){padding:8px 6px;font-size:11px}.filters-card :deep(.el-card__body){grid-template-columns:minmax(0,1fr);padding:12px;gap:12px}.songs-filter-selects{flex-wrap:wrap}.songs-filter-selects>*{flex-basis:120px}.toolbar-left,.toolbar-right{width:100%}.toolbar-right{justify-content:flex-end}.toolbar-right .density-control,.column-settings-button{display:none}.songs-toolbar{gap:12px}.desktop-song-table,.table-scroll-hint{display:none}.mobile-song-list{display:grid;gap:12px}.page-summary{gap:10px}.songs-pagination :deep(.el-pagination__jump){display:none}.songs-pagination :deep(.el-pagination){flex-wrap:wrap;gap:4px}.batch-toolbar{flex-direction:column;align-items:stretch;padding:12px;gap:8px}.batch-actions{justify-content:flex-end}}
</style>
<style>
.column-settings{display:grid;gap:12px;font-size:12px}.column-settings p{margin:0;color:var(--sv-text-secondary);font-size:11px}.column-settings label{display:flex;gap:8px;align-items:center;cursor:pointer}.column-settings input{accent-color:var(--sv-accent-primary);width:15px;height:15px}.column-settings .el-button{justify-self:start;margin:0}
</style>
