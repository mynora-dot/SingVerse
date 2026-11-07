<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElButton, ElMessageBox, ElPagination } from 'element-plus'
import { ApiError } from '../../core/auth/api'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { songCache, type CacheEntry, type CachePage, type CachePin, type CachePins, type CacheSettings, type CleanupJob, type CleanupRequest } from './api'

const gib = 1024 ** 3
const result = ref<CachePage | null>(null), settings = ref<CacheSettings | null>(null)
const draft = reactive({ capacityGiB: '' as string | number, policy: 'HYBRID', high: 90 as string | number, low: 75 as string | number })
const page = ref(1), loading = ref(false), busy = ref(false), error = ref(''), notice = ref(''), conflict = ref(false)
const pins = ref<CachePins | null>(null), pinPage = ref(1), pinsLoading = ref(false), pinError = ref('')
const job = ref<CleanupJob | null>(null), jobError = ref('')
let active = true, epoch = 0, pinEpoch = 0, jobEpoch = 0
let pollTimer: ReturnType<typeof setTimeout> | undefined
const jobKey = 'singverse.cache.cleanup'
let currentJobId = ''
const policies = [
  { value: 'HYBRID', label: '混合评分（推荐）', description: '综合真实播放热度、最近播放和近 7 天频次；高水位触发回收，低水位为目标。' },
  { value: 'MANUAL', label: '手动清理', description: '不自动回收；容量不足时停止新增缓存，已有缓存仍可使用。' },
]
const policyValue = (value: string) => value === 'MANUAL' ? 'MANUAL' : 'HYBRID'
const policyLabel = (value: string) => policies.find(p => p.value === value)?.label ?? (['LRU', 'LFU', 'TTL'].includes(value) ? '混合评分（旧策略已迁移）' : '未知策略，请重新读取设置')
const selectedPolicy = computed(() => policies.find(p => p.value === draft.policy) ?? policies[0]!)
const maxBytes = computed(() => Math.round(Number(draft.capacityGiB) * gib))
const dirty = computed(() => !!settings.value && (maxBytes.value !== settings.value.maxBytes || draft.policy !== policyValue(settings.value.policy) || Number(draft.high) !== (settings.value.highWatermarkPercent ?? 90) || Number(draft.low) !== (settings.value.lowWatermarkPercent ?? 75)))
const validation = computed(() => !Number.isSafeInteger(maxBytes.value) || maxBytes.value < 64 * 1024 ** 2 || maxBytes.value > 1024 ** 5
  ? '缓存容量应在 0.0625～1048576 GiB 之间。'
  : !Number.isInteger(Number(draft.high)) || !Number.isInteger(Number(draft.low)) || Number(draft.low) < 1 || Number(draft.high) > 100 || Number(draft.low) >= Number(draft.high)
    ? '水位应为 1～100 的整数，低水位必须小于高水位。' : '')
const usedPercent = computed(() => result.value ? Math.min(100, result.value.usageBytes / result.value.settings.maxBytes * 100) : 0)
const runningJob = computed(() => !!job.value && ['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.value.state))
const jobStates: Record<string, string> = { QUEUED: '等待清理', RUNNING: '正在清理', CANCELLING: '正在取消', SUCCEEDED: '清理完成', PARTIAL: '部分清理完成', BLOCKED: '受保护数据阻止继续清理', FAILED: '清理失败', CANCELLED: '清理已取消', INTERRUPTED: '清理被服务重启中断' }
const reasons: Record<string, string> = { PLAYING: '正在播放', DOWNLOADING: '正在下载', PINNED: '已固定', RECENT_PLAY: '近 24 小时播放保护', PREFETCH: '正在预取', DELETING: '正在删除', BUILDING: '正在接收', CHANGED: '缓存状态已变化', STATISTICS_OR_MANUAL: '统计未就绪或当前采用手动策略' }
const lifecycles: Record<string, string> = { HOT: '热门', WARM: '常用', COLD: '冷门', EVICT: '待回收' }
function protections(entry: CacheEntry) { return (entry.protectionReasons ?? []).map(reason => reasons[reason] ?? reason) }
function deletionBlocked(entry: CacheEntry) { return entry.leased || entry.pinned || entry.state === 'DELETING' || (entry.protectionReasons ?? []).some(reason => ['PLAYING', 'DOWNLOADING', 'PINNED', 'PREFETCH', 'BUILDING', 'DELETING'].includes(reason)) }
function adopt(value: CacheSettings) {
  settings.value = { ...value }
  Object.assign(draft, { capacityGiB: value.maxBytes / gib, policy: policyValue(value.policy), high: value.highWatermarkPercent ?? 90, low: value.lowWatermarkPercent ?? 75 })
  conflict.value = false
}
function failure(value: unknown) { if (active) error.value = value instanceof Error ? value.message : '读取缓存失败，请重试' }
async function load(replaceSettings = false) {
  const seq = ++epoch
  loading.value = true; error.value = ''
  try {
    const data = await songCache.list(page.value - 1, 20)
    if (!active || seq !== epoch) return
    result.value = data
    if (!settings.value || replaceSettings || !dirty.value) adopt(data.settings)
    const last = Math.max(1, Math.ceil(data.totalElements / 20))
    if (page.value > last) { page.value = last; await load() }
  } catch (value) { if (seq === epoch) failure(value) }
  finally { if (active && seq === epoch) loading.value = false }
}
async function loadPins() {
  const seq = ++pinEpoch
  pinsLoading.value = true; pinError.value = ''
  try {
    const value = await songCache.pins(pinPage.value - 1, 20)
    if (!active || seq !== pinEpoch || !value) return
    pins.value = value
    const last = Math.max(1, Math.ceil(value.totalElements / 20))
    if (pinPage.value > last) { pinPage.value = last; await loadPins() }
  } catch (value) { if (active && seq === pinEpoch) pinError.value = value instanceof Error ? value.message : '读取固定歌曲失败' }
  finally { if (active && seq === pinEpoch) pinsLoading.value = false }
}
async function discard() {
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('放弃尚未保存的缓存容量、策略和水位修改？', '有未保存的设置', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning', autofocus: false }); return true }
  catch { return false }
}
async function reloadSettings() { if (busy.value || loading.value || !await discard()) return; await load(true) }
async function save() {
  if (!settings.value || busy.value || loading.value || runningJob.value || !dirty.value || conflict.value) return
  if (validation.value) { error.value = validation.value; return }
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const saved = await songCache.save({ maxBytes: maxBytes.value, policy: draft.policy, ttlHours: settings.value.ttlHours, revision: settings.value.revision, highWatermarkPercent: Number(draft.high), lowWatermarkPercent: Number(draft.low) })
    if (!active) return
    adopt(saved); notice.value = '缓存设置已保存'; await load()
  } catch (value) { failure(value); if (value instanceof ApiError && value.status === 409) conflict.value = true }
  finally { if (active) busy.value = false }
}
function rememberJob(id?: string) { try { if (id) sessionStorage.setItem(jobKey, id); else sessionStorage.removeItem(jobKey) } catch { /* Polling also works when browser storage is unavailable. */ } }
function applyJob(value: CleanupJob) {
  job.value = value; currentJobId = value.id
  clearTimeout(pollTimer)
  if (['QUEUED', 'RUNNING', 'CANCELLING'].includes(value.state)) { rememberJob(value.id); pollTimer = setTimeout(() => { void pollJob(value.id) }, 1000) }
  else { rememberJob(); void load(); void loadPins() }
}
async function pollJob(id = job.value?.id || currentJobId) {
  if (!id || !active) return
  currentJobId = id
  const seq = ++jobEpoch
  clearTimeout(pollTimer); jobError.value = ''
  try { const value = await songCache.job(id); if (active && seq === jobEpoch) applyJob(value) }
  catch (value) { if (active && seq === jobEpoch) jobError.value = value instanceof Error ? value.message : '读取清理进度失败，请重试' }
}
async function startJob(input: CleanupRequest) {
  if (!active || busy.value || runningJob.value) return
  busy.value = true; error.value = ''; notice.value = ''; jobError.value = ''
  const seq = ++jobEpoch
  try { const value = await songCache.cleanup(input); if (active && seq === jobEpoch) applyJob(value) }
  catch (value) { failure(value) }
  finally { if (active) busy.value = false }
}
async function cancelJob() {
  if (!job.value || busy.value || !runningJob.value || job.value.state === 'CANCELLING') return
  busy.value = true; jobError.value = ''; clearTimeout(pollTimer)
  const id = job.value.id, seq = ++jobEpoch
  try { const value = await songCache.cancelJob(id); if (active && seq === jobEpoch) applyJob(value) }
  catch (value) { if (active && seq === jobEpoch) { jobError.value = value instanceof Error ? value.message : '取消失败，请重试'; pollTimer = setTimeout(() => { void pollJob(id) }, 1000) } }
  finally { if (active) busy.value = false }
}
async function remove(entry: CacheEntry) {
  if (busy.value || loading.value || runningJob.value || deletionBlocked(entry)) return
  const recent = entry.protectionReasons?.includes('RECENT_PLAY') ?? false
  try {
    const content = `${entry.target.mode === 'ORIGINAL' ? '删除原片缓存副本？WebDAV 源文件会保留，下次播放将按需重新读取。' : '删除这份兼容缓存？原始歌曲会保留，下次需要时将重新生成。'}${recent ? ' 此歌曲近 24 小时实际播放过：确认后仅对这一份缓存覆盖近期保护。' : ''}`
    await ElMessageBox.confirm(content, `删除“${entry.title || '未命名歌曲'}”的缓存`, { confirmButtonText: recent ? '确认覆盖保护并删除' : '删除缓存', cancelButtonText: '保留', type: 'warning', autofocus: false })
  } catch { return }
  await startJob({ mode: 'DELETE', entryId: entry.id, ...(recent ? { confirmRecent: true } : {}) })
}
async function clear(mode: 'CLEAR' | 'POLICY' = 'CLEAR') {
  if (busy.value || loading.value || runningJob.value || !result.value || result.value.usageBytes <= 0 && result.value.entryCount <= 0) return
  try {
    await ElMessageBox.confirm(mode === 'CLEAR' ? '清理所有未受保护的歌曲缓存？固定、近 24 小时实际播放、正在播放或下载的条目会跳过，原始歌曲和离线兼容版本会保留。' : '按当前混合评分回收到低水位？固定、近期播放和使用中的数据受保护，可能无法达到目标。', mode === 'CLEAR' ? '清理缓存' : '按策略回收', { confirmButtonText: '开始清理', cancelButtonText: '保留', type: 'warning', autofocus: false })
  } catch { return }
  await startJob({ mode })
}
async function togglePin(value: CacheEntry | CachePin) {
  if (!value.songId || busy.value) return
  const pinned = !value.pinned
  try { await ElMessageBox.confirm(pinned ? '固定此歌曲的所有原片及兼容缓存，包括以后产生的变体。固定不会强制下载整曲，空间不足也不会自动取消固定。' : '取消此歌曲所有现有及未来缓存的固定？取消后仍保留实际播放等保护；删除缓存需要另行确认。', pinned ? '固定歌曲缓存' : '取消固定', { confirmButtonText: pinned ? '固定歌曲' : '取消固定', cancelButtonText: '保留', type: 'warning', autofocus: false }) }
  catch { return }
  if (!active || busy.value) return
  busy.value = true; error.value = ''
  try {
    const saved = await songCache.pin(value.songId, pinned, 'pinRevision' in value ? value.pinRevision ?? 0 : 'revision' in value ? value.revision : 0)
    if (!active) return
    for (const entry of result.value?.items ?? []) if (entry.songId === value.songId) { entry.pinned = saved.pinned; entry.pinRevision = saved.revision }
    notice.value = saved.pinned ? '已固定此歌曲的所有缓存' : '已取消此歌曲的固定'
    await Promise.all([load(), loadPins()])
  } catch (value) { failure(value) }
  finally { if (active) busy.value = false }
}
function bytes(value: number) {
  if (!Number.isFinite(value)) return '未知'
  if (value >= gib) return `${(value / gib).toLocaleString('zh-CN', { maximumFractionDigits: 2 })} GiB`
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} MiB`
  return `${(value / 1024).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} KiB`
}
function date(value?: string | null) { const time = value ? new Date(value) : null; return !time || Number.isNaN(time.getTime()) ? '尚无实际播放' : time.toLocaleString('zh-CN', { hour12: false }) }
function score(value?: number) { return Number.isFinite(value) ? value!.toFixed(3) : '—' }
function jobFailure(value: CleanupJob['errors'][number]) { return typeof value === 'string' ? value : value.message || value.code || '缓存清理失败' }
function format(entry: CacheEntry) {
  const mode = entry.target.mode
  if (mode === 'ORIGINAL') {
    const cached = entry.cachedBytes ?? entry.bytes, total = entry.sourceBytes ?? entry.target.sourceBytes ?? 0
    return entry.complete === true || total > 0 && cached >= total ? 'WebDAV 原片 · 已完整缓存' : total > 0 ? `WebDAV 原片 · 已缓存 ${bytes(cached)} / ${bytes(total)}` : 'WebDAV 原片 · 按需缓存'
  }
  const output = mode === 'REMUX' ? '重封装，保留源编码' : mode === 'AUDIO_TRANSCODE' ? entry.mediaType === 'VIDEO' ? '音频转 AAC，保留视频编码' : 'AAC 音频' : entry.mediaType === 'VIDEO' ? `${(entry.target.codec || 'h264').toUpperCase()} · 画质上限 ${entry.target.width || '—'} × ${entry.target.height || '—'}` : 'AAC 音频'
  return `${output}${entry.target.start ? ` · 从 ${entry.target.start} 秒起` : ''}`
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => busy.value ? false : discard())
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load(); void loadPins(); try { const id = sessionStorage.getItem(jobKey); if (id) void pollJob(id) } catch { /* Storage is optional. */ } })
onBeforeUnmount(() => { active = false; epoch++; pinEpoch++; jobEpoch++; clearTimeout(pollTimer); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <section class="song-cache-page">
    <PageHeader title="歌曲缓存" description="NAS 保存 WebDAV 原片缓存与音视频兼容结果，原片按需缓存，队列临近结束时提前读取下一首。">
      <template #actions><ElButton :loading="loading" :disabled="busy" @click="load()">刷新列表</ElButton></template>
    </PageHeader>
    <div v-if="error" role="alert" class="cache-feedback cache-error">{{ error }}<ElButton v-if="conflict" text :disabled="busy || loading" @click="reloadSettings">重新读取设置</ElButton></div>
    <p v-if="notice" role="status" class="cache-feedback cache-success">{{ notice }}</p>
    <template v-if="result">
      <section class="sv-panel cache-storage" aria-label="缓存容量概况">
        <div><span class="cache-eyebrow">服务节点缓存占用</span><p class="cache-storage-number">{{ result.usageReady === false ? '正在计算占用…' : bytes(result.usageBytes) }} <span>/ {{ bytes(result.settings.maxBytes) }}</span></p><progress :value="usedPercent" max="100" aria-label="缓存容量使用比例"/><p class="cache-hint">占用包含正在接收及待清理残留的数据 · {{ result.entryCount }} 份歌曲缓存<span v-if="result.reservedBytes !== undefined"> · 已预留 {{ bytes(result.reservedBytes) }}</span></p><p v-if="result.statisticsReady === false" class="cache-hint" role="status">正在准备播放评分统计，当前尚未就绪。</p></div>
        <div class="cache-storage-policy"><span class="cache-eyebrow">当前策略</span><strong>{{ policyLabel(result!.settings.policy) }}</strong><div class="cache-cleanup-buttons"><ElButton :disabled="busy || loading || runningJob || result.usageBytes <= 0 && result.entryCount <= 0" @click="clear('POLICY')">按策略回收</ElButton><ElButton type="danger" plain :disabled="busy || loading || runningJob || result.usageBytes <= 0 && result.entryCount <= 0" @click="clear('CLEAR')">清理缓存</ElButton></div></div>
      </section>
      <form v-if="settings" class="sv-panel cache-settings" @submit.prevent="save">
        <div class="cache-section-heading"><div><h2>缓存设置</h2><p>自动回收和批量清理保护固定、近 24 小时实际播放、正在播放及下载的缓存。近 3 天播放有软保留加分。</p></div><span v-if="dirty" class="cache-draft">尚未保存</span></div>
        <div class="cache-fields">
          <label>最大缓存容量 <span class="cache-unit">GiB</span><input v-model="draft.capacityGiB" type="number" min="0.0625" max="1048576" step="any" required aria-label="最大缓存容量" :disabled="busy || loading"/><small>按 NAS 可用空间设置，1 GiB = 1024 MiB。</small></label>
          <label>缓存策略<select v-model="draft.policy" aria-label="缓存策略" :disabled="busy || loading"><option v-for="policy in policies" :key="policy.value" :value="policy.value">{{ policy.label }}</option></select><small>{{ selectedPolicy.description }}</small></label>
          <label>高水位 <span class="cache-unit">%</span><input v-model="draft.high" type="number" min="2" max="100" step="1" required aria-label="缓存高水位" :disabled="busy || loading || runningJob"/><small>混合策略达到此占用比例时触发回收，默认 90%。</small></label>
          <label>低水位 <span class="cache-unit">%</span><input v-model="draft.low" type="number" min="1" max="99" step="1" required aria-label="缓存低水位" :disabled="busy || loading || runningJob"/><small>回收目标，默认 75%；受保护数据可能使目标无法达到。</small></label>
        </div>
        <div class="cache-settings-footer"><p v-if="dirty && validation" class="cache-validation" role="alert">{{ validation }}</p><span v-else class="cache-hint">原片与兼容结果共用容量，清理缓存不修改原始歌曲。</span><div><ElButton :disabled="busy || loading" @click="reloadSettings">重新读取设置</ElButton><ElButton type="primary" native-type="submit" :loading="busy" :disabled="loading || runningJob || !dirty || !!validation || conflict">保存设置</ElButton></div></div>
      </form>
      <section v-if="job || jobError" class="sv-panel cache-job" aria-label="缓存清理任务" aria-live="polite">
        <div class="cache-section-heading"><div><h2>{{ job ? jobStates[job.state] || job.state : '读取清理任务' }}</h2><p>清理在后台执行，取消会保留已完成的删除；离开页面不会停止任务。</p></div><ElButton v-if="runningJob" :disabled="busy || job?.state === 'CANCELLING'" @click="cancelJob">取消清理</ElButton></div>
        <p v-if="jobError" role="alert" class="cache-error">{{ jobError }}<ElButton text @click="pollJob()">重试读取进度</ElButton></p>
        <template v-if="job"><p>已清理 {{ job.removed }} 份缓存，释放 {{ bytes(job.freedBytes) }}；跳过 {{ job.skipped }} 份，失败 {{ job.failed }} 份。</p><p>占用 {{ bytes(job.usageBeforeBytes) }} → {{ bytes(job.usageAfterBytes) }} · 目标 {{ bytes(job.targetBytes) }}</p><p v-if="job.spaceInsufficient" role="alert" class="cache-error">清理后空间仍不足，还需 {{ bytes(job.shortfallBytes) }}。请取消不需要的固定、等待使用结束，或增加缓存容量及磁盘空间。</p><p v-else-if="!runningJob && !job.targetReached" class="cache-hint">未达到目标水位，当前仍有可用缓存空间；受保护条目不会被强制删除。</p><ul v-if="Object.keys(job.skippedByReason ?? {}).length" class="cache-job-reasons"><li v-for="(count, reason) in job.skippedByReason" :key="reason">{{ reasons[reason] || reason }}：{{ count }} 份</li></ul><ul v-if="job.errors?.length" class="cache-error"><li v-for="(item, index) in job.errors" :key="index">{{ jobFailure(item) }}</li></ul></template>
      </section>
      <section class="sv-panel cache-pins" aria-label="固定的歌曲缓存">
        <div class="cache-section-heading"><div><h2>固定歌曲 <span>{{ pins?.totalElements ?? 0 }}</span></h2><p>固定作用于同曲全部变体及未来缓存，不强制下载整曲；没有缓存或已下架的固定记录也可在此取消。</p></div><ElButton :disabled="pinsLoading || busy" @click="loadPins">刷新固定</ElButton></div>
        <p v-if="pinError" role="alert" class="cache-error">{{ pinError }}</p><p v-else-if="pinsLoading" role="status">正在读取固定歌曲…</p><p v-else-if="!pins?.items.length" class="cache-hint">尚未固定歌曲。可在下方缓存条目中固定。</p>
        <article v-for="pin in pins?.items ?? []" :key="pin.songId" class="cache-pin-row"><div><strong>{{ pin.title || pin.songId }}</strong><p>{{ pin.artist || '歌曲信息暂不可用' }}<span v-if="pin.hasCache === false"> · 尚无缓存</span></p></div><ElButton :disabled="busy" :aria-label="`取消固定${pin.title || pin.songId}`" @click="togglePin(pin)">取消固定</ElButton></article>
        <ElPagination v-if="pins?.totalElements" :current-page="pinPage" :page-size="20" :total="pins.totalElements" :disabled="busy || pinsLoading" layout="total, prev, pager, next" @current-change="value => { pinPage = value; loadPins() }"/>
      </section>
      <section class="sv-panel cache-list" aria-label="已缓存的音视频">
        <div class="cache-section-heading"><div><h2>已缓存的音视频 <span>{{ result.totalElements }}</span></h2><p>原片可部分缓存；整曲缓存支持跳转复用。不同画质、音轨、字幕或独立生成的片段可能有多份缓存。离线兼容版本仍在歌曲播放设置中管理。</p></div></div>
        <p v-if="loading" class="cache-loading" role="status">正在读取缓存…</p>
        <div v-else-if="!result.items.length" class="cache-empty"><span aria-hidden="true">♫</span><h3>暂无歌曲缓存</h3><p>播放 WebDAV 原片或生成兼容结果后，已缓存内容会显示在这里。</p></div>
        <template v-else>
          <div class="cache-table-heading" aria-hidden="true"><span>歌曲 / 格式</span><span>大小</span><span>命中 / 最近使用</span><span>状态 / 操作</span></div>
          <article v-for="entry in result.items" :key="entry.id" class="cache-row">
            <div class="cache-song"><span class="cache-media-icon" :class="{ audio: entry.mediaType === 'AUDIO' }">{{ entry.mediaType === 'AUDIO' ? '音频' : '视频' }}</span><div><h3>{{ entry.title || '未命名歌曲' }}</h3><p>{{ entry.artist || entry.songId || entry.id }}</p><small>{{ format(entry) }}</small><small v-if="entry.lifecycle">{{ lifecycles[entry.lifecycle] || entry.lifecycle }} · 近 7 天有效 {{ entry.playCount7d ?? 0 }} 次 · 综合 {{ score(entry.score) }}</small><small v-if="entry.H !== undefined" :title="`热度 H=${score(entry.H)}，最近 R=${score(entry.R)}，频次 F=${score(entry.F)}，保留优先级 ${score(entry.priority)}`">H {{ score(entry.H) }} / R {{ score(entry.R) }} / F {{ score(entry.F) }}</small><small v-if="entry.lastPlayedAt">实际播放于 {{ date(entry.lastPlayedAt) }}</small></div></div>
            <div class="cache-size"><span class="mobile-label">大小</span>{{ bytes(entry.bytes) }}<small v-if="(entry.residueBytes ?? 0) > 0" title="损坏或待清理的文件仍占磁盘，不计入可播放缓存字节">另有残留占用 {{ bytes(entry.residueBytes ?? 0) }}</small></div>
            <div class="cache-access"><strong>命中 {{ entry.hits }} 次</strong><time :datetime="entry.lastAccessAt">{{ date(entry.lastAccessAt) }}</time><small :title="date(entry.createdAt)">生成于 {{ date(entry.createdAt) }}</small></div>
            <div class="cache-row-actions"><span :class="{ 'cache-in-use': deletionBlocked(entry) }">{{ protections(entry).join(' · ') || (entry.leased ? '正在使用' : entry.target.mode === 'ORIGINAL' && entry.complete === false ? '部分已缓存' : '可复用') }}</span><span v-if="entry.pinned && !entry.protectionReasons?.includes('PINNED')">已固定</span><ElButton v-if="entry.songId" text :disabled="busy || loading || entry.state === 'DELETING'" :aria-label="`${entry.pinned ? '取消固定' : '固定'}${entry.title || '未命名歌曲'}的缓存`" @click="togglePin(entry)">{{ entry.pinned ? '取消固定' : '固定歌曲' }}</ElButton><ElButton text type="danger" :disabled="deletionBlocked(entry) || busy || loading || runningJob" :aria-label="`删除${entry.title || '未命名歌曲'}的缓存`" @click="remove(entry)">删除缓存</ElButton></div>
          </article>
        </template>
        <ElPagination v-if="result.totalElements" :current-page="page" :page-size="20" :total="result.totalElements" :disabled="busy || loading" layout="total, prev, pager, next" @current-change="value => { page = value; load() }"/>
      </section>
    </template>
    <p v-else-if="loading" role="status" class="cache-loading">正在读取缓存…</p>
    <section v-else class="sv-panel cache-unavailable"><h2>暂时无法读取歌曲缓存</h2><p>请检查服务节点的缓存目录、磁盘空间和读写权限。缓存管理无需连接计算节点。</p><div><ElButton @click="load()">重试</ElButton></div></section>
  </section>
</template>

<style scoped>
.cache-size small{display:block;white-space:normal;font-size:11px;color:var(--sv-text-secondary);line-height:1.6;margin-top:5px}
.cache-job,.cache-pins{padding:22px 24px}.cache-job p,.cache-pin-row p{font-size:12px;line-height:1.8}.cache-pin-row{display:flex;justify-content:space-between;gap:18px;align-items:center;padding:12px 0;border-top:1px solid var(--sv-border-subtle)}.cache-pin-row strong{font-size:13px}.cache-pin-row p{margin:5px 0 0;color:var(--sv-text-secondary)}.cache-pins>.el-pagination{justify-content:flex-end;margin-top:16px}.cache-cleanup-buttons{display:flex;gap:8px;flex-wrap:wrap}.cache-cleanup-buttons .el-button{margin:0}.cache-job-reasons{font-size:12px;line-height:1.8}.cache-row-actions>span{text-align:right;line-height:1.6}

.song-cache-page{display:grid;gap:18px}.cache-feedback{margin:0;padding:12px 16px;border-radius:9px;font-size:13px;line-height:1.7}.cache-error{color:var(--el-color-danger);background:var(--el-color-danger-light-9)}.cache-success{color:var(--el-color-success);background:var(--el-color-success-light-9)}.cache-storage{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:36px;padding:24px}.cache-eyebrow{display:block;color:var(--sv-text-secondary);font-size:12px}.cache-storage-number{margin:8px 0 14px;font-size:30px;font-weight:600;letter-spacing:-.5px}.cache-storage-number span{font-size:15px;font-weight:400;color:var(--sv-text-secondary)}.cache-storage progress{display:block;width:100%;height:7px;border:0;border-radius:8px;background:var(--sv-surface-3);accent-color:var(--sv-accent-primary)}progress::-webkit-progress-bar{background:var(--sv-surface-3);border-radius:8px}progress::-webkit-progress-value{background:var(--sv-accent-primary);border-radius:8px}.cache-hint{font-size:12px;color:var(--sv-text-secondary);line-height:1.8}.cache-storage .cache-hint{margin:10px 0 0}.cache-storage-policy{display:flex;min-width:180px;flex-direction:column;align-items:flex-start;gap:12px}.cache-storage-policy strong{font-size:14px}.cache-storage-policy .el-button{margin-top:auto}.cache-settings,.cache-list{padding:22px 24px}.cache-section-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:20px}.cache-section-heading h2{font-size:16px;margin:0}.cache-section-heading h2 span{color:var(--sv-text-secondary);font-size:12px;margin-left:8px}.cache-section-heading p{font-size:12px;line-height:1.8;color:var(--sv-text-secondary);margin:7px 0 0}.cache-draft{color:var(--sv-accent-primary);font-size:12px;white-space:nowrap}.cache-fields{display:grid;grid-template-columns:minmax(180px,.8fr) minmax(240px,1.1fr) repeat(2,minmax(120px,.5fr));gap:22px}.cache-fields label{position:relative;display:flex;flex-direction:column;gap:9px;font-size:13px}.cache-unit{position:absolute;top:0;right:0;color:var(--sv-text-secondary);font-size:12px}.cache-fields input,.cache-fields select{width:100%;min-height:38px;border:1px solid var(--sv-border-default);border-radius:7px;background:var(--sv-surface-1);color:var(--sv-text-primary);padding:8px 10px;font:inherit}.cache-fields input:focus,.cache-fields select:focus{outline:2px solid var(--sv-accent-primary);outline-offset:1px}.cache-fields small{font-size:12px;color:var(--sv-text-secondary);line-height:1.8}.cache-settings-footer{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-top:20px;padding-top:18px;border-top:1px solid var(--sv-border-subtle)}.cache-settings-footer>div{display:flex;gap:10px;flex:none}.cache-settings-footer .el-button{margin:0}.cache-validation{font-size:12px;color:var(--el-color-danger)}.cache-table-heading,.cache-row{display:grid;grid-template-columns:minmax(240px,1fr) 100px minmax(160px,.55fr) 100px;gap:18px;align-items:center}.cache-table-heading{color:var(--sv-text-secondary);font-size:12px;padding:0 0 12px;border-bottom:1px solid var(--sv-border-subtle)}.cache-row{padding:17px 0;border-bottom:1px solid var(--sv-border-subtle)}.cache-song{display:flex;gap:14px;align-items:center;min-width:0}.cache-media-icon{display:grid;place-items:center;width:46px;height:46px;border-radius:11px;background:var(--sv-surface-3);color:var(--sv-text-secondary);font-size:12px;flex:none}.cache-media-icon.audio{color:var(--sv-accent-primary)}.cache-song>div{min-width:0}.cache-song h3{font-size:14px;overflow-wrap:anywhere;margin:0 0 5px}.cache-song p,.cache-song small{display:block;font-size:12px;color:var(--sv-text-secondary);line-height:1.6;margin:0;overflow-wrap:anywhere}.cache-size{font-size:13px;white-space:nowrap}.mobile-label{display:none}.cache-access{display:flex;flex-direction:column;gap:6px;min-width:0}.cache-access strong{font-size:12px;font-weight:500}.cache-access time,.cache-access small{font-size:11px;color:var(--sv-text-secondary)}.cache-access small{opacity:.8}.cache-row-actions{display:flex;flex-direction:column;align-items:flex-end;gap:6px}.cache-row-actions>span{font-size:12px;color:var(--sv-text-secondary)}.cache-row-actions>span.cache-in-use{color:var(--el-color-warning)}.cache-row-actions .el-button{font-size:12px;margin:0;padding:4px 0;height:auto}.cache-list>.el-pagination{justify-content:flex-end;margin-top:18px}.cache-empty{padding:38px 16px;text-align:center}.cache-empty>span{font-size:36px;color:var(--sv-text-secondary)}.cache-empty h3{font-size:16px;margin:14px 0 10px}.cache-empty p,.cache-loading{color:var(--sv-text-secondary);font-size:13px;line-height:1.8}.cache-unavailable{padding:32px}.cache-unavailable h2{font-size:17px}.cache-unavailable p{color:var(--sv-text-secondary);font-size:13px;line-height:1.8}.cache-unavailable>div{display:flex;align-items:center;gap:18px;margin-top:22px}.cache-unavailable .text-link{font-size:13px}
@media(max-width:1100px){.cache-fields{grid-template-columns:minmax(160px,.7fr) minmax(240px,1fr)}.cache-table-heading,.cache-row{grid-template-columns:minmax(200px,1fr) 85px minmax(140px,.6fr) 90px;gap:12px}.cache-settings-footer{align-items:flex-start}.cache-access small{display:none}}
@media(max-width:760px){.cache-storage{padding:20px;gap:22px}.cache-storage-policy{min-width:145px}.cache-storage-number{font-size:24px}.cache-settings,.cache-list{padding:20px}.cache-table-heading{display:none}.cache-row{grid-template-columns:minmax(0,1fr) auto;gap:14px}.cache-song{grid-column:1 / -1}.cache-size{grid-column:1;font-size:12px}.mobile-label{display:inline;color:var(--sv-text-secondary);margin-right:8px}.cache-access{grid-column:1;grid-row:3;gap:5px}.cache-row-actions{grid-column:2;grid-row:2 / 4;align-self:end}.cache-fields{grid-template-columns:1fr}.cache-settings-footer{flex-direction:column}.cache-settings-footer>div{width:100%;flex-wrap:wrap}.cache-list>.el-pagination{justify-content:center;max-width:100%;overflow:auto}.cache-fields label{max-width:100%}}
@media(max-width:430px){.cache-storage{grid-template-columns:1fr;gap:20px}.cache-storage-policy{flex-direction:row;flex-wrap:wrap;align-items:center;gap:12px}.cache-storage-policy .cache-eyebrow{width:100%}.cache-storage-policy .el-button{margin:0 0 0 auto}.cache-storage-policy strong{font-size:12px}}
</style>
