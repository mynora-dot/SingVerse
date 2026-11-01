<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElCheckbox, ElForm, ElMessage, ElMessageBox, ElPagination, ElProgress, ElTag } from 'element-plus'
import { ArrowLeft, RefreshRight } from '@element-plus/icons-vue'
import { metadata } from '../api/metadata'
import { media, type MediaSource } from '../../media-library/api/media'
import { providerNames, itemStatusNames } from '../types'
import type { MetadataBatch, MetadataItem, MetadataSettings, MusicProvider } from '../types'
import SingleSongScrapeDialog from '../components/SingleSongScrapeDialog.vue'
import BatchSongTable from '../components/BatchSongTable.vue'
const route = useRoute(), router = useRouter()
const config = ref<MetadataSettings>({ enabled: false, providers: [], autoApply: false, threshold: .95, minIntervalSeconds: 3, maxIntervalSeconds: 8 })
const baseline = ref(''), saved = ref(false), busy = ref(false), loading = ref(false), refreshing = ref(false)
const error = ref(''), progressError = ref(''), settingsOpen = ref(true), lastRefresh = ref('')
const batch = ref<MetadataBatch | null>(null), page = ref(1), filter = ref(''), reviewItem = ref<MetadataItem | null>(null)
const keyword = ref(''), appliedKeyword = ref(''), sort = ref('DEFAULT')
const sourceId = ref(''), sources = ref<MediaSource[]>([]), sourcesLoading = ref(false), sourcesError = ref('')
const selectedIds = computed(() => typeof route.query.songIds === 'string' ? [...new Set(route.query.songIds.split(',').filter(Boolean))] : [])
const dirty = computed(() => JSON.stringify(config.value) !== baseline.value)
const activeBatch = computed(() => !!batch.value && ['RUNNING', 'PAUSED'].includes(batch.value.status))
const canStart = computed(() => saved.value && config.value.enabled && !!config.value.providers.length && !dirty.value && !activeBatch.value && !busy.value && !loading.value)
const canStartScope = computed(() => canStart.value && (!sourceId.value || sources.value.some(source => source.id === sourceId.value)))
const selectedSourceName = computed(() => sources.value.find(source => source.id === sourceId.value)?.name ?? '')
const startReason = computed(() => !saved.value ? '请先成功读取并保存平台设置' : activeBatch.value ? '已有运行或暂停的批次，请先完成或终止' : dirty.value ? '请先保存设置' : !config.value.enabled || !config.value.providers.length ? '请先启用并选择平台' : '')
const retryReason = computed(() => batch.value?.status === 'CANCELLED' ? '已终止批次不能重试，请新建批次' : !saved.value || !config.value.enabled ? '请先启用平台并保存' : dirty.value ? '请先保存设置' : '')
const percentage = computed(() => batch.value?.total ? Math.min(100, Math.round(batch.value.completed / batch.value.total * 100)) : 0)
const batchNames: Record<string, string> = { RUNNING: '正在匹配', PAUSED: '已暂停', COMPLETED: '匹配完成', CANCELLED: '已终止' }
const stats = computed(() => batch.value ? [
  { status: '', label: '全部歌曲', value: batch.value.total },
  { status: 'PENDING', label: batch.value.status === 'CANCELLED' ? '终止未处理' : '待处理', value: batch.value.pending },
  { status: 'PROCESSING', label: '处理中', value: batch.value.processing },
  { status: 'REVIEW', label: '待审核', value: batch.value.review },
  { status: 'APPLIED', label: '已应用', value: batch.value.applied },
  { status: 'FAILED', label: '失败', value: batch.value.failed },
  { status: 'SKIPPED_EXISTING', label: '已跳过', value: batch.value.skippedExisting },
] : [])
const settingsSummary = computed(() => `${config.value.enabled ? config.value.providers.map(p => providerNames[p]).join(' / ') || '未选平台' : '平台未启用'} · 自动写入${config.value.autoApply ? '开启' : '关闭'} · 间隔 ${config.value.minIntervalSeconds ?? 3}～${config.value.maxIntervalSeconds ?? 8} 秒`)
let active = true, generation = 0, loadGeneration = 0, timer: ReturnType<typeof setTimeout> | undefined
function schedule() {
  if (timer) clearTimeout(timer)
  if (active && !busy.value && !reviewItem.value && batch.value?.status === 'RUNNING') timer = setTimeout(() => { void refreshBatch() }, progressError.value ? 5000 : 2000)
}
function accept(result: MetadataBatch) { batch.value = result; lastRefresh.value = result.observedAt || new Date().toISOString() }
async function refreshBatch() {
  if (!batch.value) return
  const id = batch.value.id, token = ++generation
  refreshing.value = true
  try {
    const result = await metadata.batch(id, page.value - 1, filter.value, appliedKeyword.value, sort.value)
    if (active && token === generation) {
      accept(result); progressError.value = ''
      const max = Math.max(1, Math.ceil(result.totalElements / 20))
      if (page.value > max) { page.value = max; await refreshBatch() }
    }
  } catch (failure) { if (active && token === generation) progressError.value = failure instanceof Error ? failure.message : '读取进度失败' }
  finally { if (active && token === generation) { refreshing.value = false; schedule() } }
}
async function load() {
  const token = ++loadGeneration; generation++; clearTimeout(timer); loading.value = true; error.value = ''; progressError.value = ''
  const [settings, latest] = await Promise.allSettled([metadata.settings(), typeof route.query.batch === 'string' ? metadata.batch(route.query.batch) : metadata.latest()])
  if (!active || token !== loadGeneration) return
  if (settings.status === 'fulfilled') { config.value = settings.value; baseline.value = JSON.stringify(settings.value); saved.value = true }
  else { saved.value = false; error.value = settings.reason instanceof Error ? settings.reason.message : '设置读取失败' }
  if (latest.status === 'fulfilled') { batch.value = latest.value ?? null; if (latest.value) accept(latest.value); settingsOpen.value = !latest.value }
  else progressError.value = latest.reason instanceof Error ? latest.reason.message : '读取批次失败'
  loading.value = false; schedule()
}
async function loadSources() {
  sourcesLoading.value = true; sourcesError.value = ''
  try {
    const found: MediaSource[] = []
    for (let page = 0; ; page++) {
      const result = await media.list(page, 100)
      if (!active) return
      found.push(...result.items)
      if (found.length >= result.totalElements || !result.items.length) break
    }
    sources.value = found
  } catch (failure) { if (active) sourcesError.value = failure instanceof Error ? failure.message : '读取媒体来源失败' }
  finally { if (active) sourcesLoading.value = false }
}
async function save() {
  if (busy.value || !dirty.value || !saved.value) return
  if (batch.value?.status === 'RUNNING') {
    try { await ElMessageBox.confirm('保存设置将暂停正在运行的批次；已写入结果保留，确认平台后需手动继续。', '保存并暂停批次', { confirmButtonText: '保存并暂停', cancelButtonText: '取消' }) } catch { return }
  }
  await action(async () => {
    const settings = await metadata.saveSettings(config.value)
    config.value = settings; baseline.value = JSON.stringify(settings); saved.value = true
    await refreshBatch()
    ElMessage.success(progressError.value ? '设置已保存；批次状态读取失败，请刷新确认' : batch.value ? `设置已保存，批次${batchNames[batch.value.status]}` : '刮削设置已保存')
  })
}
async function start(all: boolean) {
  if (!canStart.value || (all && !canStartScope.value)) return
  const scopeId = all ? sourceId.value : ''
  const scopeName = selectedSourceName.value
  try { await ElMessageBox.confirm(`${all ? scopeId ? `将仅检查媒体来源「${scopeName}」的歌曲` : '将检查全部媒体来源的歌曲' : `将检查所选 ${selectedIds.value.length} 首歌曲`}。已有有效确认结果的歌曲会跳过并计入整体进度。歌名和歌手将发送至启用平台；${config.value.autoApply ? '排序第一的候选达到有效阈值时自动写入，其余等待审核' : '自动写入关闭，候选均等待人工审核'}。不会修改媒体文件。`, '开始元数据刮削', { confirmButtonText: '开始刮削', cancelButtonText: '取消' }) } catch { return }
  await action(async () => { accept(await (scopeId ? metadata.start(true, [], scopeId) : metadata.start(all, all ? [] : selectedIds.value))); page.value = 1; filter.value = ''; keyword.value = ''; appliedKeyword.value = ''; sort.value = 'DEFAULT'; settingsOpen.value = false; await router.replace({ query: { batch: batch.value!.id } }) })
}
async function action(operation: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; generation++; refreshing.value = false; clearTimeout(timer)
  try { await operation() }
  catch (failure) { if (active) error.value = failure instanceof Error ? failure.message : '操作失败' }
  finally { if (active) { busy.value = false; schedule() } }
}
async function control(kind: 'pause' | 'resume' | 'cancel') {
  if (!batch.value) return
  await action(async () => { accept(await metadata[kind](batch.value!.id)); await refreshBatch(); ElMessage.success(`批次${batchNames[batch.value!.status]}`) })
}
async function retry(itemId: string) {
  if (retryReason.value || !batch.value) return
  if (batch.value.autoApply) { try { await ElMessageBox.confirm('重新匹配不是人工确认，但本批次已启用自动写入；匹配仍遵循本批次快照及当前全局开关/阈值限制。', '重新匹配', { confirmButtonText: '按现有规则匹配', cancelButtonText: '取消' }) } catch { return } }
  await action(async () => { accept(await metadata.retry(batch.value!.id, itemId)); await refreshBatch(); ElMessage.success('已进入等待处理；尚未完成匹配或写入') })
}
async function cancel() {
  try { await ElMessageBox.confirm('终止后，未处理歌曲不再继续，不能恢复或重试本批次；已获取候选保留，已完成的元数据写入不会自动撤销。在途平台请求可能仍需结束。之后可新建批次。', '终止批次', { confirmButtonText: '终止批次', cancelButtonText: '继续处理' }) } catch { return }
  await control('cancel')
}
async function changeFilter() { page.value = 1; await refreshBatch() }
async function selectStatus(status: string) { filter.value = status; await changeFilter() }
async function searchItems() { appliedKeyword.value = keyword.value.trim(); await changeFilter() }
function openReview(item: MetadataItem) { reviewItem.value = item; clearTimeout(timer) }
function closeReview() { reviewItem.value = null; schedule() }
async function manual(item: MetadataItem) { await router.push({ path: '/library/songs', query: { reviewSongId: item.songId, returnBatch: batch.value!.id } }) }
async function reason(item: MetadataItem) { await ElMessageBox.alert(item.error || (item.status === 'SKIPPED_EXISTING' ? '已有确认元数据且文件未变化，本批次保留原结果。' : item.status === 'AUTO_APPLIED' ? '本批次已按自动写入条件完成应用。具体当前字段可在歌曲详情查看。' : '该项已确认或在查询期间获得确认，已有元数据保留。具体字段可在歌曲详情查看。'), item.status === 'FAILED' ? '失败原因' : '处理结果', { confirmButtonText: '知道了' }).catch(() => {}) }
onMounted(() => { void load(); void loadSources() })
watch(() => route.query.batch, id => { if (typeof id === 'string' && id !== batch.value?.id) { page.value = 1; filter.value = ''; appliedKeyword.value = ''; keyword.value = ''; void load() } })
onBeforeUnmount(() => { active = false; generation++; loadGeneration++; clearTimeout(timer) })
</script>

<template>
  <section class="metadata-page management-page">
    <PageHeader title="元数据整理" description="从音乐平台匹配专辑、封面、发行日期等信息，审核候选后再写入曲库。只发送歌名与歌手，不上传媒体文件。"><template #actions><ElButton :icon="ArrowLeft" @click="router.push('/library/songs')">返回全部歌曲</ElButton></template></PageHeader>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <ElAlert v-if="progressError" :title="progressError" type="error" :closable="false" show-icon><ElButton text :disabled="busy" @click="batch ? refreshBatch() : load()">重新读取进度</ElButton></ElAlert>
    <div class="batch-overview" :class="{ 'has-batch': !!batch }">
    <ElCard shadow="never" class="metadata-settings">
      <div class="section-heading"><div><h2>平台与写入设置</h2><p class="config-summary">{{ settingsSummary }}{{ dirty ? ' · 有未保存修改' : '' }}</p></div><div><ElTag :type="saved && config.enabled && !dirty ? 'success' : 'info'">{{ saved && config.enabled && !dirty ? '已启用' : '未启用或待保存' }}</ElTag><ElButton text :aria-expanded="settingsOpen" aria-controls="batch-settings-form" @click="settingsOpen = !settingsOpen">{{ settingsOpen ? '收起配置' : '展开配置' }}</ElButton></div></div>
      <div v-show="settingsOpen" id="batch-settings-form">
      <ElForm @submit.prevent="save">
        <div class="setting-line"><strong>启用元数据刮削</strong><ElCheckbox v-model="config.enabled" :disabled="busy || !saved">将查询发送至所选平台，不上传原始媒体</ElCheckbox></div>
        <div class="setting-line"><strong>刮削平台</strong><div class="settings-checks"><ElCheckbox v-for="(name, platform) in providerNames" :key="platform" :model-value="config.providers.includes(platform)" :disabled="busy || !saved" @change="value => config.providers = value ? [...config.providers, platform as MusicProvider] : config.providers.filter(item => item !== platform)">{{ name }}</ElCheckbox></div></div>
        <div class="setting-line"><strong>自动写入</strong><ElCheckbox v-model="config.autoApply" :disabled="busy || !saved">排序第一的候选达到阈值时自动应用</ElCheckbox></div>
        <div class="setting-line"><strong>自动写入阈值</strong><label class="threshold"><input v-model.number="config.threshold" aria-label="自动写入阈值" type="number" min="0.8" max="1" step="0.01" :disabled="busy || !saved" /><span>{{ Math.round(config.threshold * 100) }}%</span></label></div>
        <div class="setting-line"><strong>批量请求间隔</strong><label class="threshold"><input v-model.number="config.minIntervalSeconds" aria-label="批量最小间隔秒" type="number" min="0" max="300" :disabled="busy || !saved" /><span>至</span><input v-model.number="config.maxIntervalSeconds" aria-label="批量最大间隔秒" type="number" min="0" max="300" :disabled="busy || !saved" /><span>秒</span></label></div>
        <p class="panel-description">每首处理结束后，在范围内随机等待再处理下一首；默认 3～8 秒。平台返回 HTTP 429 后冷却至少 60 秒，失败项可稍后重试。随机间隔不能保证避免平台限流。</p>
        <p class="panel-description">自动写入默认关闭。相似度是匹配参考，不是正确率；仅控制自动写入，不过滤搜索候选。排序第一的候选须达到本批次阈值；当前全局阈值和开关仍会约束旧批次。相似度相同时使用服务端返回的第一条。保存设置会暂停运行中的批次，确认平台后手动继续。</p>
        <div class="settings-footer"><ElButton type="primary" native-type="submit" :loading="busy" :disabled="!saved || !dirty">保存刮削设置</ElButton><span v-if="dirty" class="panel-description">有未保存修改，请先保存再启动批次。</span></div>
      </ElForm>
      </div>
    </ElCard>
    <ElCard shadow="never" class="batch-control">
      <div class="section-heading"><div><h2>{{ batch ? batchNames[batch.status] : '尚未创建刮削批次' }}</h2><p class="panel-description">{{ batch ? '处理完成包含待审核，不代表已全部写入。' : '先配置平台，再选择全库或从歌曲管理勾选歌曲。' }}</p></div><div class="batch-actions"><ElButton v-if="batch?.status === 'RUNNING'" :disabled="busy" @click="control('pause')">暂停</ElButton><span v-if="batch?.status === 'PAUSED'" :title="dirty ? '先保存设置再继续' : !config.enabled ? '先启用平台' : ''"><ElButton :disabled="busy || dirty || !config.enabled || !saved" @click="control('resume')">继续</ElButton></span><ElButton v-if="activeBatch" :disabled="busy" @click="cancel">终止</ElButton><ElButton v-if="batch" :icon="RefreshRight" :loading="refreshing" :disabled="busy" @click="refreshBatch">刷新进度</ElButton></div></div>
      <p v-if="loading" role="status" class="panel-description">正在读取配置与进度…</p>
      <template v-if="batch">
        <ElProgress :percentage="percentage" :status="batch.status === 'COMPLETED' ? 'success' : undefined" />
        <div class="progress-caption"><strong>已处理 {{ batch.completed }} / {{ batch.total }} 首</strong><span>其中已跳过 {{ batch.skippedExisting }} 首 · 待处理 {{ batch.pending }} 首 · 处理中 {{ batch.processing }} 首</span></div>
        <div class="batch-stats"><button v-for="stat in stats" :key="stat.status" type="button" :class="{ selected: filter === stat.status }" :disabled="busy" @click="selectStatus(stat.status)"><strong>{{ stat.value }}</strong><span>{{ stat.label }}</span></button></div>
        <p class="count-explanation">整体总数 = 待处理 + 处理中 + 待审核 + 已应用 + 失败 + 已跳过；已处理 = 待审核 + 已应用 + 失败 + 已跳过。</p>
        <p v-if="batch.status === 'CANCELLED'" class="status-notice">已终止，未处理项保留原状态供追溯，不会继续查询；已经写入的结果保留。</p>
        <p v-if="batch.status === 'PAUSED'" class="status-notice">批次已暂停，在途请求可能仍在结束；确认平台及设置后可继续。</p>
        <div class="refresh-note"><span>本批次自动写入：{{ batch.autoApply ? '开启（仍受当前全局设置限制）' : '关闭' }}</span><span v-if="lastRefresh">最近读取：{{ new Date(lastRefresh).toLocaleTimeString() }}{{ batch.status === 'RUNNING' && !reviewItem ? ' · 每 2 秒自动刷新' : '' }}</span></div>
      </template>
      <div class="batch-start"><div><label class="source-scope">刮削范围 <select v-model="sourceId" aria-label="刮削媒体来源" :disabled="sourcesLoading || busy"><option value="">全部媒体来源</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option></select></label><p class="panel-description">有效确认结果自动跳过；选择媒体来源后，仅处理该来源的歌曲。重新匹配可使用单曲入口。</p><p v-if="sourcesError" class="panel-description" role="alert">媒体来源读取失败：{{ sourcesError }} <ElButton text @click="loadSources">重试</ElButton></p><p v-if="startReason" class="panel-description">{{ startReason }}</p></div><div><span :title="startReason"><ElButton v-if="selectedIds.length && !sourceId" :disabled="!canStart" @click="start(false)">刮削已选（{{ selectedIds.length }}）</ElButton><ElButton type="primary" :disabled="!canStartScope" :loading="busy" @click="start(true)">{{ sourceId ? '刮削所选媒体源' : '刮削全部歌曲' }}</ElButton></span></div></div>
    </ElCard>
    </div>
    <ElCard v-if="batch" shadow="never" class="batch-list-card">
      <div class="section-heading"><h2>批次歌曲 <span class="count-badge">{{ batch.total }}</span></h2><span class="panel-description">当前筛选 {{ batch.totalElements }} 首</span></div>
      <form class="batch-filter" aria-label="批次歌曲筛选" @submit.prevent="searchItems"><label>状态 <select v-model="filter" aria-label="刮削明细状态" :disabled="busy" @change="changeFilter"><option value="">全部状态</option><option value="APPLIED">全部已应用</option><option v-for="(name, state) in itemStatusNames" :key="state" :value="state">{{ name }}</option></select></label><label class="keyword-filter">搜索 <input v-model="keyword" aria-label="搜索批次歌名或歌手" placeholder="输入原始歌名或歌手" maxlength="200" :disabled="busy" /></label><ElButton native-type="submit" :disabled="busy">查询</ElButton><label>排序 <select v-model="sort" aria-label="批次排序" :disabled="busy" @change="changeFilter"><option value="DEFAULT">批次顺序</option><option value="TITLE">原始歌名</option><option value="SCORE_DESC">相似度从高到低</option><option value="UPDATED_DESC">最近处理优先</option></select></label></form>
      <p class="list-help">高相似度：最高分达到本批次阈值，打开审核时自动选中排序最高的候选；接近候选仍提示核对。80% 以上但低于阈值为低相似度，低于 80% 或无候选为无可信候选。审核时的自动选中只用于预览；批次自动写入开启且第一候选达到有效阈值时，会直接应用第一条。</p>
      <p v-if="batch.legacySkipped" class="status-notice">此历史批次有 {{ batch.legacySkipped }} 首跳过项仅保存汇总，计入整体进度；没有原始歌曲明细可供搜索和展示。</p>
      <p v-if="retryReason" class="panel-description">重新匹配 / 重试不可用：{{ retryReason }}</p>
      <BatchSongTable :batch="batch" :busy="busy" :retry-disabled-reason="retryReason" @review="openReview" @retry="retry" @manual="manual" @reason="reason" />
      <ElPagination v-if="batch.totalElements > 20" v-model:current-page="page" :total="batch.totalElements" :page-size="20" layout="total, prev, pager, next, jumper" :disabled="busy" @current-change="refreshBatch" />
    </ElCard>
    <p class="feature-note">进度持久化，重启后暂停。重新匹配只重新查询候选；启用自动写入的批次仍按既有安全条件处理。审核候选使用单曲工作台，确认应用后同步更新批次明细状态。</p>
    <SingleSongScrapeDialog :song-id="reviewItem?.songId ?? null" :title="reviewItem?.title ?? ''" :artists="reviewItem?.artists ?? []" :initial-candidates="reviewItem?.candidates" :batch-id="batch?.id" :item-id="reviewItem?.id" :auto-select-threshold="batch?.threshold" @close="closeReview" @applied="refreshBatch" />
  </section>
</template>

<style scoped>
.metadata-page { min-width: 0; }.metadata-page .page-heading { margin-bottom: 16px; }
.batch-overview { display: flex; flex-direction: column; gap: 16px; margin-bottom: 16px; }.has-batch .batch-control { order: -1; }
.metadata-page :deep(.el-card__body) { padding: 20px; }.metadata-page .section-heading { gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }.metadata-page h2 { font-size: 16px; }
.config-summary { margin: 6px 0 0; font-size: 12px; color: var(--sv-text-secondary); line-height: 1.6; overflow-wrap: anywhere; }.metadata-settings .section-heading { margin-bottom: 0; }#batch-settings-form { padding-top: 12px; }
.setting-line { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; margin: 8px 0; }.setting-line > strong { width: 110px; font-size: 13px; font-weight: 500; }.settings-checks { display: flex; gap: 16px; flex-wrap: wrap; }.settings-checks .el-checkbox { margin-right: 0; }.threshold { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--sv-text-secondary); flex-wrap: wrap; }
.threshold input,.batch-filter input { padding: 7px 10px; color: var(--sv-text-primary); background: var(--sv-surface-2); border: 1px solid var(--sv-border-default); border-radius: var(--sv-radius-sm); font: inherit; }.threshold input { width: 85px; }
.settings-footer,.batch-start { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 14px; }.metadata-settings .panel-description { margin: 8px 0; font-size: 12px; }
.source-scope { display: flex; align-items: center; gap: 10px; font-size: 13px; font-weight: 500; }.source-scope select { min-width: 180px; max-width: min(320px, 60vw); padding: 7px 10px; color: var(--sv-text-primary); background: var(--sv-surface-2); border: 1px solid var(--sv-border-default); border-radius: var(--sv-radius-sm); font: inherit; }
.batch-start { padding-top: 12px; border-top: 1px solid var(--sv-border-subtle); }.batch-start .panel-description { margin: 3px 0; font-size: 12px; }
.progress-caption { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; font-size: 13px; margin: 12px 0; }.progress-caption > span { color: var(--sv-text-secondary); font-size: 12px; }
.batch-stats { display: grid; grid-template-columns: repeat(7,minmax(0,1fr)); gap: 8px; margin: 12px 0; }.batch-stats button { text-align: left; padding: 10px; background: var(--sv-surface-3); border: 1px solid transparent; border-radius: 7px; color: var(--sv-text-primary); cursor: pointer; }.batch-stats button.selected { border-color: var(--el-color-primary); background: var(--sv-accent-soft); }.batch-stats strong { display: block; font-size: 20px; }.batch-stats span { display: block; font-size: 12px; color: var(--sv-text-secondary); margin-top: 4px; }
.count-explanation,.list-help,.refresh-note { font-size: 12px; color: var(--sv-text-secondary); line-height: 1.7; margin: 8px 0; }.refresh-note { display: flex; justify-content: space-between; gap: 10px; flex-wrap: wrap; }.status-notice { background: var(--sv-semantic-warn-soft); color: var(--sv-semantic-warn); border-radius: 6px; padding: 8px 10px; font-size: 12px; margin: 10px 0; line-height: 1.6; }
.batch-filter { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; margin-bottom: 10px; font-size: 13px; }.batch-filter label { display: flex; gap: 8px; align-items: center; }.keyword-filter { flex: 1; min-width: 180px; }.keyword-filter input { flex: 1; width: auto; min-width: 100px; }
.batch-actions { display: flex; gap: 8px; flex-wrap: wrap; }.batch-actions .el-button + .el-button { margin-left: 0; }.batch-list-card { min-width: 0; }.metadata-page :deep(.el-pagination) { margin-top: 16px; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
@media(max-width: 850px) { .batch-stats { grid-template-columns: repeat(4,minmax(0,1fr)); } }
@media(max-width: 600px) { .metadata-page :deep(.el-card__body) { padding: 14px; }.setting-line { gap: 4px; }.setting-line > strong { width: 100%; }.batch-start > div { width: 100%; }.batch-filter label { width: 100%; }.batch-filter select,.batch-filter input { min-width: 0; flex: 1; }.batch-stats { grid-template-columns: repeat(3,minmax(0,1fr)); }.batch-stats strong { font-size: 18px; } }
</style>
