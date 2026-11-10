<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElDialog, ElEmpty, ElForm, ElFormItem, ElInput, ElMessage, ElTabPane, ElTabs, ElTag } from 'element-plus'
import { Download, FolderOpened, RefreshRight, Search, Setting } from '@element-plus/icons-vue'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { media, type MediaSource } from '../media-library/api/media'
import SourcesDialog from './SourcesDialog.vue'
import TrackPicker from './TrackPicker.vue'
import RecordsPanel from './RecordsPanel.vue'
import { lx, message, platforms, qualities, type Preview, type SearchResult, type Status, type Track } from './api'

const route = useRoute(), router = useRouter()
const status = ref<Status | null>(null), statusError = ref(''), statusLoading = ref(false), sourcesOpen = ref(false), tab = ref(route.query.tab === 'records' || route.query.batch ? 'records' : 'search')
const query = ref(''), platform = ref('all'), result = ref<SearchResult | null>(null), searchTracks = ref<Track[]>([]), searchSelected = ref<string[]>([]), searching = ref(false), searchError = ref('')
const link = ref(''), preview = ref<Preview | null>(null), previewTracks = ref<Track[]>([]), previewSelected = ref<string[]>([]), previewing = ref(false), previewError = ref('')
const confirmOpen = ref(false), chosen = ref<Track[]>([]), quality = ref('auto'), submitting = ref(false), submitError = ref(''), notice = ref(''), records = ref<InstanceType<typeof RecordsPanel>>()
const targetOpen = ref(false), localSources = ref<MediaSource[]>([]), targetId = ref(''), targetError = ref(''), targetLoading = ref(false), targetSaving = ref(false), moreSources = ref(false), sourcePage = ref(0)
let alive = true, statusGeneration = 0, searchGeneration = 0, previewGeneration = 0, targetGeneration = 0, statusPending = false
let statesPending = false
let searched = { query: '', platform: 'all' }, previewedLink = ''
const ready = computed(() => !!status.value?.ready)
const supported = computed(() => Object.keys(status.value?.activeSource?.capabilities || {}))
const supportedLabels = computed(() => supported.value.map(code => platforms[code]).join('、'))
const qualityOptions = computed(() => Object.keys(qualities).filter(q => q === 'auto' || chosen.value.every(t => status.value?.activeSource?.capabilities[t.platform]?.includes(q))))
const selectedTarget = computed(() => localSources.value.find(s => s.id === targetId.value))
async function refreshStatus() { if (statusPending) return; statusPending = true; const token = ++statusGeneration; statusLoading.value = true; try { const value = await lx.status(); if (!alive || token !== statusGeneration) return; status.value = value; statusError.value = ''; if (platform.value !== 'all' && !supported.value.includes(platform.value)) platform.value = 'all' } catch (e) { if (alive && token === statusGeneration) statusError.value = message(e) } finally { statusPending = false; if (alive && token === statusGeneration) statusLoading.value = false } }
async function recordsChanged() {
  void refreshStatus()
  if (statesPending) return
  const ids = [...new Set([...searchTracks.value, ...previewTracks.value].filter(t => t.downloading).map(t => t.id))]
  if (!ids.length) return
  statesPending = true
  try {
    const updated: Track[] = []
    for (let i = 0; i < ids.length && alive; i += 1000) updated.push(...await lx.trackStates(ids.slice(i, i + 1000)))
    if (!alive) return
    const states = new Map(updated.map(t => [t.id, t]))
    for (const rows of [searchTracks, previewTracks]) rows.value = rows.value.map(t => { const next = states.get(t.id); return next ? { ...t, inLibrary: next.inLibrary, downloading: next.downloading, songId: next.songId } : t })
  } catch { /* Records retain the actionable error; a later refresh retries track status. */ }
  finally { statesPending = false }
}
async function search(more = false) {
  if (!ready.value || searching.value || !query.value.trim()) return
  const token = ++searchGeneration; searching.value = true; searchError.value = ''
  if (!more) searched = { query: query.value.trim(), platform: platform.value }
  try { const value = await lx.search(searched.query, searched.platform, more ? (result.value?.page || 1) + 1 : 1); if (!alive || token !== searchGeneration) return; result.value = value; searchTracks.value = more ? merge(searchTracks.value, value.tracks) : value.tracks; if (!more) searchSelected.value = [] }
  catch (e) { if (alive && token === searchGeneration) searchError.value = message(e) }
  finally { if (alive && token === searchGeneration) searching.value = false }
}
async function readLink(more = false) {
  if (!ready.value || previewing.value || !link.value.trim()) return
  const token = ++previewGeneration; previewing.value = true; previewError.value = ''; if (!more) previewedLink = link.value.trim()
  try { const value = await lx.preview(previewedLink, more ? (preview.value?.page || 1) + 1 : 1); if (!alive || token !== previewGeneration) return; if (more && value.tracks.every(t => previewTracks.value.some(old => old.id === t.id))) { previewError.value = '平台未返回新的歌曲，已停止继续读取。当前列表已保留，请在原平台核对完整内容。'; preview.value = { ...value, hasMore: false }; return } preview.value = value; previewTracks.value = more ? merge(previewTracks.value, value.tracks) : value.tracks; if (!more) previewSelected.value = [] }
  catch (e) { if (alive && token === previewGeneration) previewError.value = message(e) }
  finally { if (alive && token === previewGeneration) previewing.value = false }
}
function merge(old: Track[], next: Track[]) { const rows = new Map(old.map(t => [t.id, t])); next.forEach(t => rows.set(t.id, t)); return [...rows.values()] }
function choose(tracks: Track[]) { if (!ready.value || !tracks.length) return; chosen.value = tracks; quality.value = 'auto'; submitError.value = ''; confirmOpen.value = true }
async function download() {
  if (submitting.value || !ready.value) return
  submitting.value = true; submitError.value = ''
  try { const created = await lx.create(chosen.value.map(t => t.id), quality.value, tab.value === 'preview' ? preview.value?.name : undefined); if (!alive) return; confirmOpen.value = false; const ids = new Set(chosen.value.map(t => t.id)); for (const rows of [searchTracks, previewTracks]) rows.value = rows.value.map(t => ids.has(t.id) ? { ...t, downloading: true } : t); searchSelected.value = searchSelected.value.filter(id => !ids.has(id)); previewSelected.value = previewSelected.value.filter(id => !ids.has(id)); notice.value = `已提交 ${chosen.value.length} 首歌曲${created.merged ? `，其中 ${created.merged} 首已在下载队列中` : ''}。可在下载记录查看进度。`; await refreshStatus(); await records.value?.refresh(0) }
  catch (e) { if (alive) submitError.value = message(e) }
  finally { if (alive) submitting.value = false }
}
async function openTarget() { targetOpen.value = true; targetId.value = status.value?.target.id || ''; localSources.value = []; sourcePage.value = 0; targetError.value = ''; await loadTargets() }
async function loadTargets(more = false) { const token = ++targetGeneration; targetLoading.value = true; try { const value = await media.list(more ? sourcePage.value + 1 : 0, 100, { q: '', type: 'LOCAL', profileId: '' }); if (!alive || token !== targetGeneration || !targetOpen.value) return; localSources.value = more ? [...localSources.value, ...value.items] : value.items; sourcePage.value = value.page; moreSources.value = (value.page + 1) * value.size < value.totalElements } catch (e) { if (alive && token === targetGeneration) targetError.value = message(e) } finally { if (alive && token === targetGeneration) targetLoading.value = false } }
async function saveTarget() { const selected = selectedTarget.value; if (!selected || targetSaving.value) return; targetSaving.value = true; targetError.value = ''; try { await media.save({ name: selected.name, type: 'LOCAL', location: selected.location, parserProfileId: selected.parserProfileId, scanEnabled: selected.scanEnabled, scanCron: selected.scanCron || undefined, useForLxDownloads: true }, selected.id); if (!alive) return; targetOpen.value = false; await refreshStatus(); ElMessage.success('下载保存位置已更新') } catch (e) { if (alive) targetError.value = message(e) } finally { if (alive) targetSaving.value = false } }
function addDirectory() { targetOpen.value = false; void router.push('/library/media?add=LOCAL&download=1') }
watch(() => route.query.tab, value => { if (value === 'records') tab.value = 'records' })
watch(() => `${status.value?.activeSource?.id}:${status.value?.target.id}:${status.value?.target.path}:${status.value?.ready}`, (_, old) => { if (!old || old.startsWith('undefined:')) return; searchGeneration++; previewGeneration++; searching.value = false; previewing.value = false; if (!submitting.value) confirmOpen.value = false })
watch(tab, value => { if (value !== 'records' && route.query.batch) { const next = { ...route.query }; delete next.batch; delete next.tab; void router.replace({ query: next }) } })
onMounted(() => void refreshStatus())
onBeforeUnmount(() => { alive = false; statusGeneration++; searchGeneration++; previewGeneration++; targetGeneration++ })
</script>
<template>
  <div class="management-page downloads-page">
    <PageHeader title="找到想唱的下一首" description="从你自己的洛雪音源获取歌曲，保存到 NAS，完成后自动加入曲库。"><template #actions><ElButton :icon="RefreshRight" :loading="statusLoading" @click="refreshStatus">刷新状态</ElButton></template></PageHeader>
    <ElAlert v-if="statusError" :title="statusError" type="error" :closable="false" show-icon role="alert" />
    <section class="setup" aria-label="下载配置">
      <ElCard shadow="never"><div class="setup-title"><FolderOpened /><strong>保存位置</strong><ElTag v-if="status?.target.ready" type="success" size="small">已就绪</ElTag></div><p class="setup-value">{{ status?.target.name || '尚未设置下载目录' }}</p><p class="help path">{{ status?.target.path || '选择一个可写的本地媒体库，所有下载统一保存在这里。' }}</p><ElAlert v-if="status?.target.id && !status.target.ready" :title="status.target.reason" type="warning" :closable="false" show-icon /><ElButton :icon="FolderOpened" @click="openTarget">{{ status?.target.id ? '更改保存位置' : '设置下载目录' }}</ElButton></ElCard>
      <ElCard shadow="never"><div class="setup-title"><Setting /><strong>当前音源</strong><ElTag v-if="status?.activeSource" type="success" size="small">已启用</ElTag></div><p class="setup-value">{{ status?.activeSource?.name || '尚未启用音源' }}</p><p class="help">{{ status?.activeSource ? `${status.activeSource.version} · 支持 ${supportedLabels}` : '上传自己的 .js 脚本或通过 HTTPS 链接导入。' }}</p><ElButton :icon="Setting" @click="sourcesOpen = true">{{ status?.activeSource ? '管理音源' : '导入并启用音源' }}</ElButton></ElCard>
    </section>
    <ElAlert v-if="status && !ready" title="先完成上面的下载配置，即可搜索歌曲或导入歌单、专辑。已有下载记录仍可查看。" type="info" :closable="false" show-icon />
    <ElAlert v-if="notice" :title="notice" type="success" show-icon closable @close="notice = ''" />
    <ElCard shadow="never" class="download-workspace">
      <ElTabs v-model="tab"><ElTabPane label="搜索歌曲" name="search" /><ElTabPane label="导入歌单 / 专辑" name="preview" /><ElTabPane :label="`下载记录${status?.activeBatches ? ` · ${status.activeBatches} 个进行中` : ''}`" name="records" /></ElTabs>
      <section v-show="tab === 'search'" aria-label="搜索歌曲">
        <ElForm class="search-form" @submit.prevent="search()"><ElInput v-model="query" aria-label="歌名或歌手" placeholder="输入歌名或歌手" maxlength="100" clearable :disabled="!ready || searching" :prefix-icon="Search" /><select v-model="platform" class="native-select" aria-label="搜索平台" :disabled="!ready || searching"><option value="all">全部支持的平台</option><option v-for="p in supported" :key="p" :value="p">{{ platforms[p] }}</option></select><ElButton type="primary" native-type="submit" :loading="searching" :disabled="!ready || !query.trim()">搜索</ElButton></ElForm>
        <ElAlert v-if="searchError" :title="searchError" type="error" :closable="false" show-icon role="alert" />
        <ElAlert v-for="failure in result?.errors" :key="failure.platform" :title="`${platforms[failure.platform]}：${failure.message}。可以重试或切换其他平台。`" type="warning" :closable="false" show-icon />
        <p v-for="warning in result?.warnings" :key="warning" class="help">{{ warning }}</p>
        <TrackPicker v-if="searchTracks.length" v-model:selected="searchSelected" :tracks="searchTracks" :disabled="!ready" @download="choose" />
        <ElEmpty v-else-if="result && !searching && !result.errors.length" description="没有找到歌曲，试试更短的歌名或其他平台。" /><div v-else-if="!result" class="welcome"><Download /><h3>把想唱的歌加入曲库</h3><p>搜索歌曲，保留不同平台和版本，选择适合你的那一首。</p></div>
        <div v-if="result?.hasMore" class="more"><ElButton :loading="searching" :disabled="!ready" @click="search(true)">继续读取搜索结果</ElButton><span class="help">已读取 {{ searchTracks.length }} 首</span></div>
      </section>
      <section v-show="tab === 'preview'" aria-label="导入歌单或专辑">
        <ElForm class="link-form" @submit.prevent="readLink()"><ElInput v-model="link" aria-label="歌单或专辑分享链接" placeholder="粘贴网易云、QQ、酷狗、酷我或咪咕的公开分享链接" maxlength="4096" clearable :disabled="!ready || previewing" /><ElButton type="primary" native-type="submit" :loading="previewing" :disabled="!ready || !link.trim()">读取链接</ElButton></ElForm><p class="help">读取后再选择歌曲下载。歌单中的歌曲会加入曲库，共享歌单仍在「歌单管理」中维护。</p>
        <ElAlert v-if="previewError" :title="previewError" type="error" :closable="false" show-icon role="alert" />
        <div v-if="preview" class="preview-heading"><img v-if="preview.coverUrl" :src="preview.coverUrl" alt="" referrerpolicy="no-referrer" loading="lazy" /><div><ElTag effect="plain">{{ platforms[preview.platform] }} · {{ preview.kind === 'album' ? '专辑' : '歌单' }}</ElTag><h3>{{ preview.name }}</h3><p class="help" role="status">已读取 {{ previewTracks.length }} / {{ preview.total }} 首{{ preview.hasMore ? '，可继续读取' : '' }}</p></div></div>
        <ElAlert v-if="preview?.warning" :title="preview.warning" type="warning" :closable="false" show-icon />
        <TrackPicker v-if="previewTracks.length" v-model:selected="previewSelected" :tracks="previewTracks" :disabled="!ready" @download="choose" />
        <ElEmpty v-else-if="preview && !previewing" description="此目录没有可读取的歌曲，请检查公开权限或换一个链接。" />
        <div v-if="preview?.hasMore" class="more"><ElButton :loading="previewing" :disabled="!ready" @click="readLink(true)">继续读取歌曲</ElButton><span class="help">“选择已读取歌曲”只勾选当前已读取的 {{ previewTracks.length }} 首。</span></div>
      </section>
      <RecordsPanel v-show="tab === 'records'" ref="records" :visible="tab === 'records'" :status="status" @changed="recordsChanged" />
    </ElCard>
    <SourcesDialog v-model="sourcesOpen" @changed="refreshStatus" />
    <ElDialog v-model="confirmOpen" title="确认下载" width="min(520px, calc(100vw - 24px))" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting">
      <p>将下载 <strong>{{ chosen.length }}</strong> 首歌曲，完成后自动加入曲库。</p><p class="help path">保存到：{{ status?.target.path }}</p><p class="help">使用音源：{{ status?.activeSource?.name }} · {{ status?.activeSource?.version }}</p><ElForm label-position="top"><ElFormItem label="下载音质"><select v-model="quality" class="native-select" aria-label="下载音质" :disabled="submitting"><option v-for="q in qualityOptions" :key="q" :value="q">{{ qualities[q] }}</option></select></ElFormItem></ElForm><p class="help">自动音质按音源声明选择。指定音质不可用时会明确报错，已有文件会保留。</p><ElAlert v-if="submitError" :title="submitError" type="error" :closable="false" show-icon role="alert" /><template #footer><ElButton :disabled="submitting" @click="confirmOpen = false">返回</ElButton><ElButton type="primary" :loading="submitting" :disabled="!ready" @click="download">开始下载</ElButton></template>
    </ElDialog>
    <ElDialog v-model="targetOpen" title="选择下载保存位置" width="min(600px, calc(100vw - 24px))" :close-on-click-modal="false" :show-close="!targetSaving" @closed="targetGeneration++">
      <p class="help path">原保存位置：{{ status?.target.path || '尚未设置' }}</p><ElAlert v-if="targetError" :title="targetError" type="error" :closable="false" show-icon role="alert" /><ElForm label-position="top"><ElFormItem label="本地媒体库"><select v-model="targetId" class="native-select" aria-label="下载保存媒体库" :disabled="targetSaving || targetLoading"><option value="" disabled>选择本地媒体库</option><option v-for="source in localSources" :key="source.id" :value="source.id">{{ source.name }}</option></select></ElFormItem></ElForm><p v-if="selectedTarget" class="path">新保存位置：{{ selectedTarget.location }}</p><ElButton v-if="moreSources" :loading="targetLoading" @click="loadTargets(true)">读取更多媒体库</ElButton><p class="help">保存前会检查实际写入能力。若原位置仍有未完成任务，请先完成或取消任务，再更换位置。</p><ElButton link type="primary" :disabled="targetSaving" @click="addDirectory">添加新的本地媒体库 ↗</ElButton><template #footer><ElButton :disabled="targetSaving" @click="targetOpen = false">返回</ElButton><ElButton type="primary" :loading="targetSaving" :disabled="!selectedTarget || targetLoading" @click="saveTarget">保存位置</ElButton></template>
    </ElDialog>
  </div>
</template>
<style scoped>
.native-select{height:36px;max-width:100%;padding:0 12px;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);background:var(--el-fill-color-blank);color:var(--el-text-color-regular);font:inherit;font-size:14px}.native-select:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.native-select:disabled{opacity:.6}.downloads-page{display:flex;flex-direction:column;gap:20px}.setup{display:grid;grid-template-columns:1fr 1fr;gap:20px}.setup-title{display:flex;align-items:center;gap:10px}.setup-title svg{width:20px;height:20px;color:var(--el-color-primary)}.setup-value{font-weight:600;font-size:18px;margin:18px 0 8px}.help{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.setup .help{min-height:44px}.path{overflow-wrap:anywhere}.download-workspace{min-height:430px}.search-form{display:grid;grid-template-columns:minmax(200px,1fr) 200px auto;gap:12px;padding:12px 0 20px}.link-form{display:flex;gap:12px;padding-top:12px}.link-form>.el-input{flex:1}.more{display:flex;align-items:center;justify-content:center;gap:12px;flex-wrap:wrap;margin:24px 0 8px}.welcome{min-height:260px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;color:var(--el-text-color-secondary)}.welcome svg{width:40px;height:40px;color:var(--el-color-primary);margin-bottom:8px}.welcome h3{font-size:18px;font-weight:500;color:var(--el-text-color-primary);margin:14px 0 0}.welcome p{font-size:14px;line-height:1.8}.preview-heading{display:flex;gap:20px;margin:24px 0}.preview-heading img{width:88px;height:88px;object-fit:cover;border-radius:10px}.preview-heading h3{margin:8px 0;font-size:19px}.preview-heading .help{margin:0}.el-alert{margin:8px 0}.el-dialog .native-select{width:100%}@media(max-width:700px){.setup{grid-template-columns:1fr;gap:16px}.search-form{grid-template-columns:1fr auto}.search-form>.el-input{grid-column:1/-1}.search-form>.native-select{width:100%}.link-form{flex-direction:column}.downloads-page{gap:16px}.download-workspace:deep(.el-card__body){padding:16px}.download-workspace:deep(.el-tabs__item){padding:0 10px;font-size:14px}.preview-heading{gap:12px}.preview-heading img{width:64px;height:64px}}
</style>
