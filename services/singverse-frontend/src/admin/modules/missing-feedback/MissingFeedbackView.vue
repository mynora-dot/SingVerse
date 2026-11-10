<script setup lang="ts">
import { requestId as newRequestId } from '../../../shared/request-id'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElAlert, ElButton, ElDialog, ElInput, ElMessage, ElPagination, ElTag } from 'element-plus'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { missingFeedback, type FeedbackCreate, type FeedbackStatus, type MissingFeedback } from './api'
import { songs } from '../song-management/api/songs'
import { songArtists, songTitle } from '../metadata/utils'
import type { MediaSong } from '../media-library/types'

const statusNames: Record<FeedbackStatus, string> = { PENDING: '待处理', PROCESSING: '处理中', RESOLVED: '已补齐', CLOSED: '已关闭' }
const query = ref(''), appliedQuery = ref(''), status = ref('ALL'), page = ref(0), total = ref(0)
const items = ref<MissingFeedback[]>([]), loading = ref(false), error = ref(''), opening = ref(false), busy = ref(false)
const createOpen = ref(false), detailOpen = ref(false), dialogError = ref(''), current = ref<MissingFeedback | null>(null)
const creation = reactive<FeedbackCreate>({ requestId: '', title: '', artist: '', versionLabel: '', description: '', submittedBy: '' })
const handling = reactive({ status: 'PENDING' as FeedbackStatus, handlingNote: '', resolvedSongId: null as string | null, songTitle: '', songArtists: [] as string[], songAvailable: false })
const pickerOpen = ref(false), songQuery = ref(''), appliedSongQuery = ref(''), songPage = ref(0), songTotal = ref(0)
const songItems = ref<MediaSong[]>([]), songLoading = ref(false), songError = ref('')
const canSave = computed(() => handling.status !== 'RESOLVED' || (!!handling.resolvedSongId && handling.songAvailable))
let active = true, generation = 0, detailGeneration = 0, songGeneration = 0
function message(e: unknown) { return e instanceof Error ? e.message : '请求失败，请重试' }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
function tagType(value: FeedbackStatus) { return value === 'RESOLVED' ? 'success' : value === 'PROCESSING' ? 'warning' : 'info' }

async function load(reset = false) {
  if (reset) page.value = 0
  const token = ++generation
  loading.value = true; error.value = ''; items.value = []
  try {
    const result = await missingFeedback.list(appliedQuery.value, status.value, page.value)
    if (!active || token !== generation) return
    items.value = result.items; total.value = result.totalElements
    const last = Math.max(0, Math.ceil(total.value / 20) - 1)
    if (page.value > last) { page.value = last; await load() }
  } catch (e) { if (active && token === generation) error.value = message(e) }
  finally { if (active && token === generation) loading.value = false }
}
function search() { appliedQuery.value = query.value.trim(); void load(true) }
function create() {
  if (busy.value || opening.value) return
  Object.assign(creation, { requestId: newRequestId(), title: '', artist: '', versionLabel: '', description: '', submittedBy: '' })
  dialogError.value = ''; createOpen.value = true
}
async function register() {
  if (busy.value || !creation.title.trim()) return
  busy.value = true; dialogError.value = ''
  try {
    await missingFeedback.create({ ...creation })
    if (!active) return
    createOpen.value = false; ElMessage.success('缺歌已登记'); await load(true)
  } catch (e) { if (active) dialogError.value = message(e) }
  finally { if (active) busy.value = false }
}
async function openDetail(id: string) {
  if (busy.value) return
  const token = ++detailGeneration
  opening.value = true; dialogError.value = ''
  try {
    const result = await missingFeedback.get(id)
    if (!active || token !== detailGeneration) return
    current.value = result
    Object.assign(handling, { status: result.status, handlingNote: result.handlingNote, resolvedSongId: result.resolvedSongId,
      songTitle: result.resolvedSongTitle || '', songArtists: result.resolvedSongArtists, songAvailable: result.resolvedSongAvailable })
    detailOpen.value = true
  } catch (e) { if (active && token === detailGeneration) { if (detailOpen.value) dialogError.value = message(e); else error.value = message(e) } }
  finally { if (active && token === detailGeneration) opening.value = false }
}
async function save() {
  if (!current.value || busy.value || !canSave.value) return
  busy.value = true; dialogError.value = ''
  try {
    await missingFeedback.update(current.value.id, { revision: current.value.revision, status: handling.status,
      handlingNote: handling.handlingNote, resolvedSongId: handling.status === 'RESOLVED' ? handling.resolvedSongId : null })
    if (!active) return
    detailOpen.value = false; ElMessage.success('处理结果已保存'); await load()
  } catch (e) { if (active) dialogError.value = message(e) }
  finally { if (active) busy.value = false }
}
function closeCreate(done?: () => void) { if (!busy.value) { createOpen.value = false; done?.() } }
function closeDetail(done?: () => void) { if (!busy.value && !pickerOpen.value) { detailGeneration++; opening.value = false; detailOpen.value = false; done?.() } }
async function loadSongs(reset = false) {
  if (reset) songPage.value = 0
  const token = ++songGeneration
  songLoading.value = true; songError.value = ''; songItems.value = []
  try {
    const result = await songs.list({ q: appliedSongQuery.value, sourceId: '', status: 'ALL', page: songPage.value, size: 20 })
    if (!active || token !== songGeneration) return
    songItems.value = result.items; songTotal.value = result.totalElements
  } catch (e) { if (active && token === songGeneration) songError.value = message(e) }
  finally { if (active && token === songGeneration) songLoading.value = false }
}
function openPicker() {
  if (busy.value) return
  songQuery.value = current.value?.title || ''; appliedSongQuery.value = songQuery.value
  pickerOpen.value = true; void loadSongs(true)
}
function searchSongs() { appliedSongQuery.value = songQuery.value.trim(); void loadSongs(true) }
function choose(song: MediaSong) {
  handling.resolvedSongId = song.id; handling.songTitle = songTitle(song); handling.songArtists = songArtists(song); handling.songAvailable = true
  pickerOpen.value = false; songGeneration++
}
onMounted(() => { void load() })
onBeforeUnmount(() => { active = false; generation++; detailGeneration++; songGeneration++ })
</script>

<template>
  <section class="missing-feedback-page">
    <PageHeader title="把想唱的歌记下来" description="集中登记想唱但曲库缺少的歌曲，跟进补齐结果。">
      <template #actions><ElButton type="primary" :disabled="busy || opening" @click="create">登记缺歌</ElButton></template>
    </PageHeader>
    <form class="feedback-filters" @submit.prevent="search">
      <ElInput v-model="query" placeholder="搜索歌名、歌手或版本" aria-label="搜索缺歌反馈" clearable maxlength="200" @clear="search" />
      <label>处理状态<select v-model="status" aria-label="缺歌处理状态" @change="load(true)"><option value="ALL">全部状态</option><option v-for="(name, key) in statusNames" :key="key" :value="key">{{ name }}</option></select></label>
      <ElButton native-type="submit" :loading="loading">搜索 / 刷新</ElButton>
    </form>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton text @click="load()">重新加载</ElButton></ElAlert>
    <p v-if="opening" class="feedback-note" role="status">正在读取反馈详情…</p>
    <div class="feedback-list-head"><strong>缺歌清单</strong><span v-if="!loading && !error">共 {{ total }} 条</span></div>
    <p v-if="loading" class="feedback-empty" role="status">正在读取缺歌反馈…</p>
    <div v-else-if="!error && !items.length" class="feedback-empty">
      <h2>{{ appliedQuery || status !== 'ALL' ? '没有找到符合条件的反馈' : '还没有缺歌反馈' }}</h2>
      <p>{{ appliedQuery || status !== 'ALL' ? '试试其他关键词或处理状态。' : '把想唱但曲库没有的歌曲记下来，补齐后关联对应歌曲。' }}</p>
      <ElButton v-if="!appliedQuery && status === 'ALL'" @click="create">登记第一首缺歌</ElButton>
    </div>
    <div v-else-if="!error" class="feedback-list">
      <article v-for="item in items" :key="item.id" class="feedback-row">
        <div class="feedback-song"><h2>{{ item.title }}</h2><p>{{ item.artist || '歌手未填写' }}<span v-if="item.versionLabel"> · {{ item.versionLabel }}</span></p><p v-if="item.description" class="feedback-description">{{ item.description }}</p><small>{{ date(item.createdAt) }} · {{ item.submittedBy || '管理员' }}登记</small></div>
        <div class="feedback-result"><ElTag :type="tagType(item.status)" size="small">{{ statusNames[item.status] }}</ElTag><p v-if="item.status === 'RESOLVED'">{{ item.resolvedSongTitle }}<small v-if="!item.resolvedSongAvailable">关联歌曲已下架，历史记录仍保留</small></p><p v-else-if="item.handlingNote">{{ item.handlingNote }}</p></div>
        <ElButton text type="primary" :disabled="opening || busy" @click="openDetail(item.id)">查看 / 处理</ElButton>
      </article>
    </div>
    <ElPagination v-if="total && !error" :current-page="page + 1" :total="total" :page-size="20" :disabled="loading" layout="total, prev, pager, next" @current-change="value => { page = value - 1; load() }" />

    <ElDialog :model-value="createOpen" title="登记缺歌" width="min(600px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="closeCreate">
      <ElAlert v-if="dialogError" :title="dialogError" type="error" :closable="false" />
      <form class="feedback-form" @submit.prevent="register">
        <label>歌曲名称 <span class="feedback-required">必填</span><ElInput v-model="creation.title" aria-label="缺歌名称" maxlength="200" :disabled="busy" /></label>
        <div class="feedback-form-columns"><label>歌手<ElInput v-model="creation.artist" aria-label="缺歌歌手" maxlength="200" :disabled="busy" /></label><label>想要的版本<ElInput v-model="creation.versionLabel" aria-label="缺歌版本" maxlength="120" placeholder="例如：原版 MV、现场版" :disabled="busy" /></label></div>
        <label>补充说明<ElInput v-model="creation.description" type="textarea" :rows="3" maxlength="2000" aria-label="缺歌补充说明" :disabled="busy" /></label>
        <label>登记人<ElInput v-model="creation.submittedBy" aria-label="缺歌登记人" maxlength="80" placeholder="可留空" :disabled="busy" /></label>
      </form>
      <template #footer><ElButton :disabled="busy" @click="closeCreate()">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!creation.title.trim()" @click="register">保存登记</ElButton></template>
    </ElDialog>

    <ElDialog :model-value="detailOpen" title="处理缺歌反馈" width="min(680px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy && !pickerOpen" :show-close="!busy && !pickerOpen" :before-close="closeDetail">
      <template v-if="current">
        <ElAlert v-if="dialogError" :title="dialogError" type="error" :closable="false"><ElButton text :disabled="busy || opening" @click="openDetail(current.id)">重新读取反馈</ElButton></ElAlert>
        <div class="feedback-original"><h2>{{ current.title }}</h2><p>{{ current.artist || '歌手未填写' }}<span v-if="current.versionLabel"> · {{ current.versionLabel }}</span></p><p v-if="current.description" class="feedback-preserve">{{ current.description }}</p><small>{{ current.submittedBy || '管理员' }} · 后台登记 · {{ date(current.createdAt) }}</small></div>
        <form class="feedback-form" @submit.prevent="save">
          <label>处理状态<select v-model="handling.status" aria-label="设置缺歌处理状态" :disabled="busy || opening"><option v-for="(name, key) in statusNames" :key="key" :value="key">{{ name }}</option></select></label>
          <div v-if="handling.status === 'RESOLVED'" class="feedback-associated"><label>关联曲库歌曲 <span class="feedback-required">必选</span></label><div v-if="handling.resolvedSongId"><strong>{{ handling.songTitle }}</strong><p>{{ handling.songArtists.join(' / ') || '未知歌手' }}</p><p v-if="!handling.songAvailable" class="feedback-warning">这首歌已下架，请重新关联可用歌曲。</p></div><p v-else class="feedback-note">补齐后选择曲库中的具体版本，才能标记完成。</p><ElButton :disabled="busy || opening" @click="openPicker">{{ handling.resolvedSongId ? '重新关联歌曲' : '选择已补齐的歌曲' }}</ElButton></div>
          <label>处理备注<ElInput v-model="handling.handlingNote" type="textarea" :rows="4" aria-label="缺歌处理备注" maxlength="2000" :disabled="busy || opening" placeholder="记录查找进度、补齐版本或关闭原因" /></label>
          <p class="feedback-note">最近更新：{{ date(current.updatedAt) }}<span v-if="current.resolvedAt"> · 补齐时间：{{ date(current.resolvedAt) }}</span></p>
        </form>
      </template>
      <template #footer><ElButton :disabled="busy || opening || pickerOpen" @click="closeDetail()">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!canSave || opening || pickerOpen" @click="save">保存处理结果</ElButton></template>
    </ElDialog>

    <ElDialog :model-value="pickerOpen" title="关联已补齐的歌曲" width="min(720px, calc(100vw - 32px))" :close-on-click-modal="false" @close="pickerOpen = false; songGeneration++">
      <form class="feedback-filters" @submit.prevent="searchSongs"><ElInput v-model="songQuery" aria-label="搜索关联歌曲" placeholder="搜索曲库歌名、歌手或文件名" clearable @clear="searchSongs" /><ElButton native-type="submit" :loading="songLoading">搜索歌曲</ElButton></form>
      <ElAlert v-if="songError" :title="songError" type="error" :closable="false"><ElButton text @click="loadSongs()">重新读取歌曲</ElButton></ElAlert>
      <p v-if="songLoading" class="feedback-empty" role="status">正在读取曲库…</p>
      <p v-else-if="!songError && !songItems.length" class="feedback-empty">没有找到歌曲，先将歌曲加入媒体库并扫描，或换个关键词。</p>
      <div v-else-if="!songError" class="feedback-picker-list"><article v-for="song in songItems" :key="song.id" class="feedback-picker-row"><div><strong>{{ songTitle(song) }}</strong><p>{{ songArtists(song).join(' / ') || '未知歌手' }}</p><small>{{ song.sourceName }} · {{ song.relativePath }}</small></div><ElButton :aria-label="`关联歌曲：${songTitle(song)}`" @click="choose(song)">关联此歌</ElButton></article></div>
      <ElPagination v-if="songTotal && !songError" :current-page="songPage + 1" :total="songTotal" :page-size="20" :disabled="songLoading" layout="prev, pager, next" @current-change="value => { songPage = value - 1; loadSongs() }" />
      <template #footer><ElButton @click="pickerOpen = false; songGeneration++">取消选择</ElButton></template>
    </ElDialog>
  </section>
</template>

<style scoped>
.missing-feedback-page{display:grid;gap:16px}.feedback-filters{display:flex;align-items:center;flex-wrap:wrap;gap:12px}.feedback-filters>.el-input{flex:1;min-width:200px;max-width:420px}.feedback-filters label{display:flex;gap:8px;align-items:center;font-size:12px;color:var(--sv-text-secondary)}select{min-height:34px;background:var(--sv-surface-1);color:var(--sv-text-primary);border:1px solid var(--sv-border-default);border-radius:7px;padding:0 10px;font:inherit}.feedback-list-head{display:flex;justify-content:space-between;gap:12px;font-size:14px}.feedback-list-head span,.feedback-note{font-size:12px;color:var(--sv-text-secondary);line-height:1.8}.feedback-list{background:var(--sv-surface-1);border:1px solid var(--sv-border-subtle);border-radius:12px}.feedback-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(140px,.5fr) auto;gap:20px;align-items:center;padding:18px 20px;border-bottom:1px solid var(--sv-border-subtle)}.feedback-row:last-child{border-bottom:0}.feedback-song h2,.feedback-original h2{font-size:16px;font-weight:600;overflow-wrap:anywhere}.feedback-song p,.feedback-result p,.feedback-original p{font-size:12px;line-height:1.8;color:var(--sv-text-secondary);overflow-wrap:anywhere}.feedback-song small,.feedback-result small,.feedback-original small{display:block;margin-top:5px;font-size:11px;color:var(--sv-text-tertiary);overflow-wrap:anywhere}.feedback-description{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.feedback-result{display:grid;gap:6px}.feedback-result p{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.feedback-empty{text-align:center;padding:45px 20px;color:var(--sv-text-secondary);font-size:13px}.feedback-empty h2{font-size:18px;color:var(--sv-text-primary);margin-bottom:8px}.feedback-empty p{line-height:1.8;margin-bottom:16px}.feedback-form{display:grid;gap:16px;margin-top:16px}.feedback-form label{display:grid;gap:7px;font-size:12px}.feedback-form label select{width:100%}.feedback-form-columns{display:grid;grid-template-columns:1fr 1fr;gap:14px}.feedback-required{font-size:11px;color:var(--sv-text-tertiary)}.feedback-original{padding:16px;background:var(--sv-surface-2);border-radius:10px;margin-top:12px}.feedback-original p{margin-top:6px}.feedback-preserve{white-space:pre-wrap}.feedback-associated{display:grid;gap:8px;padding:14px;border:1px solid var(--sv-border-subtle);border-radius:9px}.feedback-associated .el-button{justify-self:start}.feedback-associated strong{font-size:14px}.feedback-associated p{font-size:12px;line-height:1.8;color:var(--sv-text-secondary)}.feedback-associated .feedback-warning{color:var(--sv-semantic-warn)}.feedback-picker-list{margin:14px 0}.feedback-picker-row{display:flex;gap:16px;align-items:center;justify-content:space-between;padding:14px 0;border-bottom:1px solid var(--sv-border-subtle)}.feedback-picker-row>div{min-width:0}.feedback-picker-row strong{font-size:14px;overflow-wrap:anywhere}.feedback-picker-row p,.feedback-picker-row small{font-size:12px;color:var(--sv-text-secondary);overflow-wrap:anywhere;line-height:1.8}.feedback-picker-row small{display:block;font-size:11px}.missing-feedback-page>.el-pagination{justify-content:flex-end}.el-pagination{flex-wrap:wrap}
@media(max-width:700px){.feedback-row{grid-template-columns:minmax(0,1fr) auto;gap:12px;padding:16px}.feedback-result{grid-column:1;grid-row:2}.feedback-row>.el-button{grid-column:2;grid-row:1 / 3}.feedback-form-columns{grid-template-columns:1fr}.feedback-filters>.el-input{max-width:none;min-width:0;flex-basis:100%}}
</style>
