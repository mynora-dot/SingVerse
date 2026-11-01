<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElInput, ElMessage, ElMessageBox, ElProgress } from 'element-plus'
import { authRequest } from '../../../core/auth/api'
import { embeddedMetadata, embeddedState, type EmbeddedSummary, type EmbeddedDetail } from '../api/embeddedMetadata'
import { media } from '../api/media'
import type { MediaSource, ScanTask } from '../types'
const props = defineProps<{ source: MediaSource | null }>()
const emit = defineEmits<{ close: []; songs: [] }>()
interface ShareJob { id: string; state: string; total_count: number; processed_count: number; part_count: number; deletion_count?: number; error?: string }
const summary = ref<EmbeddedSummary>(), shareJob = ref<ShareJob>(), scanTask = ref<ScanTask>()
const advanced = ref(false), error = ref(''), busy = ref(false), loading = ref(false)
const enabled = ref(false), paused = ref(false), accessGroup = ref(''), origins = ref('')
const sampleId = ref(''), sample = ref<EmbeddedDetail>(), sampleName = ref('')
const files = ref<File[]>([]), importDone = ref(0), importTotal = ref(0), importSkipped = ref(0), importing = ref(false), importFinished = ref(false), stopImport = ref(false)
let generation = 0, timer: ReturnType<typeof setTimeout> | undefined
const shareBase = () => '/api/v1/media-sources/' + encodeURIComponent(props.source!.id) + '/embedded-metadata/share-jobs'
const total = computed(() => Object.values(summary.value?.counts ?? {}).reduce((a, b) => a + b, 0))
const ready = computed(() => Object.entries(summary.value?.counts ?? {}).filter(([state]) => ['COMPLETE', 'EMPTY', 'PARTIAL', 'IMPORTED'].includes(state)).reduce((a, [, count]) => a + count, 0))
const activeShare = computed(() => ['WAITING', 'QUEUED', 'RUNNING'].includes(shareJob.value?.state ?? ''))
const sharePercent = computed(() => shareJob.value?.state === 'SUCCEEDED' ? 100 : Math.min(99, Math.round((shareJob.value?.processed_count ?? 0) * 100 / Math.max(1, shareJob.value?.total_count ?? 0))))
async function load(initial = false) {
  const source = props.source, token = generation
  if (!source) return
  if (initial) loading.value = true
  try {
    const [value, job, task] = await Promise.all([embeddedMetadata.summary(source.id), authRequest<ShareJob>(shareBase() + '/latest'), source.type === 'WEBDAV' ? media.latestTask(source.id) : Promise.resolve(undefined)])
    if (token !== generation) return
    summary.value = value; shareJob.value = job; scanTask.value = task; error.value = ''
    if (initial) { enabled.value = value.enabled; paused.value = value.paused; accessGroup.value = value.accessGroup; origins.value = value.privateOrigins.join('\n') }
    if (sampleId.value) { const result = await embeddedMetadata.detail(sampleId.value); if (token === generation) sample.value = result }
  } catch (e) { if (token === generation) error.value = e instanceof Error ? e.message : '读取状态失败，请重试' }
  finally { if (token === generation) { loading.value = false; clearTimeout(timer); timer = setTimeout(() => void load(), activeShare.value || !!summary.value?.files?.pending || sample.value?.state === 'RUNNING' || sample.value?.state === 'PENDING' || ['QUEUED', 'RUNNING'].includes(scanTask.value?.status ?? '') ? 2000 : 10000) } }
}
watch(() => props.source?.id, () => { generation++; clearTimeout(timer); summary.value = undefined; shareJob.value = undefined; sample.value = undefined; sampleId.value = ''; files.value = []; importDone.value = 0; importTotal.value = 0; importSkipped.value = 0; importFinished.value = false; advanced.value = false; error.value = ''; if (props.source) void load(true) }, { immediate: true })
let importAbort: AbortController | undefined
onBeforeUnmount(() => { generation++; clearTimeout(timer); stopImport.value = true; importAbort?.abort() })
function showAdvanced(event: Event) { advanced.value = (event.target as HTMLDetailsElement).open }
async function toggleEnhance() { enabled.value = true; paused.value = !!summary.value?.enabled && !summary.value?.paused; await save() }
async function scan() {
  if (!props.source || busy.value) return
  busy.value = true
  try { if (props.source.type === 'LOCAL') await media.scan(props.source.id)
    else scanTask.value = await media.createTask(props.source.id)
    clearTimeout(timer); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '扫描失败' }
  finally { busy.value = false }
}
async function save() {
  if (!props.source || busy.value) return
  const source = props.source, token = generation
  busy.value = true; error.value = ''
  try {
    const value = await embeddedMetadata.configure(source.id, { enabled: enabled.value, paused: paused.value, accessGroup: accessGroup.value, privateOrigins: origins.value.split('\n').map(v => v.trim()).filter(Boolean) })
    if (token === generation) { summary.value = value; ElMessage.success('已保存') }
  } catch (e) { if (token === generation) error.value = e instanceof Error ? e.message : '保存失败' }
  finally { busy.value = false }
}
async function trial() {
  if (!props.source || busy.value) return
  busy.value = true; error.value = ''
  try {
    const songs = await media.songs(props.source.id, 0, 1)
    const song = songs.items[0]
    if (!song) throw new Error('请先扫描歌曲')
    sampleId.value = song.id; sampleName.value = song.relativePath
    const cached = await embeddedMetadata.detail(song.id)
    if (cached.state === 'PENDING' || cached.state === 'NOT_WEBDAV') await embeddedMetadata.refresh([song.id])
    sample.value = await embeddedMetadata.detail(song.id)
    clearTimeout(timer); await load()
  } catch (e) { error.value = e instanceof Error ? e.message : '试提取失败' }
  finally { busy.value = false }
}
async function createShare(delta = false) {
  if (!props.source || busy.value) return
  const baseId = delta ? shareJob.value?.id : undefined
  busy.value = true
  try { shareJob.value = await authRequest<ShareJob>(shareBase(), 'POST', { baseId }); clearTimeout(timer); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '创建分享包失败' }
  finally { busy.value = false }
}
async function resumeShare() {
  if (!shareJob.value) return
  try { await authRequest(shareBase() + '/' + shareJob.value.id + '/resume', 'POST'); clearTimeout(timer); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '恢复失败' }
}
async function confirmEmpty() {
  const source = props.source
  if (!source || busy.value) return
  try { await ElMessageBox.confirm('确认下架此来源的全部歌曲？原始媒体不受影响，索引缓存保留 30 天。', '确认空库同步', { confirmButtonText: '全部下架', cancelButtonText: '取消', type: 'warning' }) } catch { return }
  busy.value = true
  try { await embeddedMetadata.confirmEmpty(source.id); clearTimeout(timer); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '下架失败' }
  finally { busy.value = false }
}
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement
  files.value = Array.from(input.files ?? []).sort((a, b) => a.name.localeCompare(b.name)); input.value = ''; importFinished.value = false; importDone.value = 0; void importIndex()
}
async function importIndex() {
  const source = props.source
  if (!source || !files.value.length || importing.value) return
  if (files.value.some(file => file.size > 8 * 1024 ** 3)) { error.value = '分享文件过大，请选择单独的分享分包'; return }
  const selected = [...files.value], token = generation
  importAbort = new AbortController(); importing.value = true; stopImport.value = false; importTotal.value = 0; importSkipped.value = 0; importDone.value = 0; error.value = ''
  try {
    for (const file of selected) {
      if (stopImport.value || token !== generation) break
      const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
      const response = await fetch('/api/v1/media-sources/' + encodeURIComponent(source.id) + '/embedded-metadata/share', { method: 'POST', credentials: 'same-origin', redirect: 'error', headers: { 'Content-Type': 'application/zip', 'X-CSRF-Token': csrf.token }, body: file, signal: AbortSignal.any([importAbort!.signal, AbortSignal.timeout(3600000)]) })
      const result = await response.json()
      if (!response.ok) throw new Error(result.detail || '导入失败')
      if (token !== generation) return
      importTotal.value += result.imported; importSkipped.value += result.skipped; importDone.value++
    }
    importFinished.value = importDone.value === selected.length
    clearTimeout(timer); await load()
  } catch (e) { if (token === generation) error.value = (e instanceof Error ? e.message : '导入失败') + '。已导入的歌曲会保留，重新选择文件即可继续。' }
  finally { importing.value = false }
}
function close() { stopImport.value = true; importAbort?.abort(); emit('close') }
</script>
<template>
  <ElDialog :model-value="!!source" :title="source ? source.name + ' · 整理与分享' : '整理与分享'" width="min(640px, calc(100vw - 24px))" class="library-workflow-dialog" :close-on-click-modal="false" @update:model-value="value => { if (!value) close() }">
    <p class="library-count">{{ loading ? '正在加载…' : '共 ' + total.toLocaleString() + ' 首歌曲' }}</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="true" @close="error = ''" />
    <section class="action-row">
      <div><h3>歌曲信息</h3><p v-if="!total">扫描后，歌曲会出现在曲库中。</p><p v-else-if="source?.type === 'LOCAL'">编辑后自动保存。</p><p v-else-if="summary?.paused">已暂停完善</p><p v-else-if="summary?.enabled">自动完善已开启 · 已处理 {{ ready.toLocaleString() }} 首</p><p v-else>在后台补充歌名、歌手、封面等信息。</p><p v-if="scanTask && ['QUEUED', 'RUNNING'].includes(scanTask.status)" role="status">正在扫描，已发现 {{ scanTask.totalSongs.toLocaleString() }} 首</p></div>
      <ElButton v-if="!total" type="primary" :loading="busy" :disabled="loading || ['QUEUED', 'RUNNING'].includes(scanTask?.status ?? '')" @click="scan">扫描歌曲</ElButton>
      <ElButton v-else-if="source?.type === 'WEBDAV'" :disabled="busy || loading" @click="toggleEnhance">{{ summary?.paused ? '继续完善' : summary?.enabled ? '暂停完善' : '完善歌曲信息' }}</ElButton>
      <ElButton v-else @click="emit('songs')">查看歌曲</ElButton>
    </section>
    <section class="action-section">
      <div class="action-row"><div><h3>分享曲库</h3><p>将已整理的歌曲信息分享给别人。</p></div><ElButton v-if="!shareJob?.id || shareJob.state === 'FAILED'" type="primary" :loading="busy" :disabled="!total || loading" @click="shareJob?.state === 'FAILED' ? resumeShare() : createShare()">{{ shareJob?.state === 'FAILED' ? '重试' : '导出分享包' }}</ElButton><ElButton v-else-if="activeShare" loading disabled>正在准备</ElButton><a v-else-if="shareJob.state === 'SUCCEEDED' && shareJob.part_count" class="primary-action" :href="shareBase() + '/' + shareJob.id + '/download'" download="singverse-share-set.zip">下载分享包</a></div>
      <div v-if="activeShare" class="task-status" role="status"><ElProgress :percentage="sharePercent" :indeterminate="shareJob?.state === 'WAITING'" /><p>正在准备，完成后可下载。关闭窗口也会继续。</p></div>
      <p v-else-if="shareJob?.state === 'FAILED'" class="task-status" role="status">暂时无法准备分享包，请稍后重试。</p>
      <div v-else-if="shareJob?.state === 'SUCCEEDED'" class="ready-status"><p>{{ shareJob.part_count ? '分享包已准备好' : '没有新的歌曲信息需要分享' }}</p><ElButton text :disabled="busy" @click="createShare()">重新导出</ElButton></div>
    </section>
    <section v-if="source?.type === 'WEBDAV'" class="action-section">
      <div class="action-row"><div><h3>导入曲库</h3><p>选择收到的分享包，导入到当前媒体库。</p></div><label class="file-choice" :class="{ disabled: importing }">{{ importing ? '正在导入' : '选择分享包并导入' }}<input aria-label="选择分享包并导入" type="file" multiple accept=".zip,application/zip" :disabled="importing" @change="chooseFiles"></label></div>
      <div v-if="importing" class="task-status" role="status"><ElProgress :percentage="Math.round(importDone * 100 / Math.max(1, files.length))" indeterminate /><p>正在导入，请保持窗口打开。</p></div>
      <div v-else-if="importFinished" class="ready-status" role="status"><p>已导入 {{ importTotal.toLocaleString() }} 首<span v-if="importSkipped">，已有 {{ importSkipped.toLocaleString() }} 首自动跳过</span>。</p><ElButton text @click="emit('songs')">查看歌曲</ElButton></div>
    </section>
    <details class="advanced-options" @toggle="showAdvanced"><summary>高级选项</summary><div v-if="advanced" class="advanced-content">
      <div class="advanced-actions"><ElButton :disabled="busy || ['QUEUED', 'RUNNING'].includes(scanTask?.status ?? '')" @click="scan">重新扫描歌曲</ElButton><ElButton v-if="source?.type === 'WEBDAV'" :disabled="busy || !total || summary?.paused" @click="trial">检查一首歌曲</ElButton><ElButton v-if="shareJob?.state === 'SUCCEEDED'" :disabled="busy || activeShare" @click="createShare(true)">仅分享新增和变化</ElButton></div>
      <p v-if="summary?.files">文件保存：{{ summary.files.pending ? '等待处理 ' + summary.files.pending + ' 首' : '已完成' }}<span v-if="summary.files.failed">，{{ summary.files.failed }} 首暂未保存，后台会重试</span><span v-if="summary.files.attachments">，封面正在后台补齐</span>。</p>
      <p>分享包自动按每包最多 5000 首处理。可直接导入整套 ZIP，无需自行解压或分包。</p>
      <template v-if="source?.type === 'WEBDAV'"><p class="file-path">导入位置：{{ source.location }}</p><p>请先将对方的媒体挂载到自己的 OpenList，并保持相同的文件夹结构。</p><label>共享访问组<ElInput v-model="accessGroup" maxlength="64" /></label><label>私有下载代理（每行一个地址）<ElInput v-model="origins" type="textarea" :rows="2" /></label><ElButton :loading="busy" @click="save">保存高级设置</ElButton><p v-if="summary?.blockedReason">{{ embeddedState(summary.blockedReason) }}</p><p v-if="!summary?.remoteReady">{{ summary?.nodeMessage }}</p><p v-if="summary">今日已处理 {{ summary.budget.files_started }} 首，读取 {{ (summary.budget.bytes_read / 1048576).toFixed(1) }} MiB。</p><ElButton v-if="summary?.canConfirmEmpty" type="danger" :disabled="busy" @click="confirmEmpty">确认空库同步</ElButton></template>
      <div v-if="sample" class="sample-result"><strong>{{ embeddedState(sample.state) }}</strong><p class="file-path">{{ sampleName }}</p><div v-if="sample.result" class="sample-preview"><img v-if="sample.result.cover" :src="'/api/v1/media-songs/' + encodeURIComponent(sampleId) + '/embedded-metadata/cover'" alt="样本内嵌封面"><div><h4>{{ sample.result.tags?.title || (sample.result.fields?.title === 'ABSENT' ? '文件未提供内嵌歌名' : '尚未读取到内嵌歌名') }}</h4><p>{{ sample.result.tags?.artists?.join('、') || '尚未读取到内嵌歌手' }}</p><details v-if="sample.result.lyrics"><summary>查看歌词</summary><pre>{{ sample.result.lyrics.text }}</pre></details></div></div></div>
      <details v-if="shareJob?.state === 'SUCCEEDED' && shareJob.part_count"><summary>单独下载分包</summary><div class="share-parts"><a v-for="part in shareJob.part_count" :key="part" :href="shareBase() + '/' + shareJob.id + '/parts/' + part" :download="'part-' + String(part).padStart(6, '0') + '.zip'">分包 {{ part }}</a></div></details>
    </div></details>
    <template #footer><ElButton @click="close">关闭</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.library-count{margin:0 0 12px;color:var(--sv-text-secondary);font-size:13px}.action-section,.action-row{min-width:0}.action-section,.advanced-options{border-top:1px solid var(--sv-border-subtle)}.action-section>.action-row{border:0}.action-row{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:22px 0}.action-row>div{min-width:0}.action-row>.el-button,.primary-action,.file-choice{flex-shrink:0}.action-section{padding-bottom:4px}h3{font-size:16px;margin:0 0 7px;color:var(--sv-text-primary)}p{font-size:13px;line-height:1.6;color:var(--sv-text-secondary);margin:0}.primary-action,.file-choice{display:inline-flex;align-items:center;justify-content:center;min-height:34px;padding:0 14px;border-radius:var(--el-border-radius-base);font-size:14px;font-weight:500;text-decoration:none;cursor:pointer;position:relative}.primary-action{background:var(--sv-accent-primary);color:white}.primary-action:focus-visible,.file-choice:focus-within{outline:2px solid var(--sv-accent-primary);outline-offset:3px}.file-choice{color:var(--sv-text-primary);background:var(--sv-surface-1);border:1px solid var(--sv-border-subtle);overflow:hidden}.file-choice input{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}.file-choice.disabled{opacity:.6;cursor:default}.task-status{padding-bottom:16px}.task-status p{margin-top:8px}.ready-status{display:flex;align-items:center;justify-content:space-between;gap:12px;padding-bottom:12px}.advanced-options{margin-top:12px;padding-top:16px;font-size:13px;color:var(--sv-text-secondary)}summary{cursor:pointer;min-height:28px;line-height:28px}.advanced-content{padding-top:12px}.advanced-content p{margin:10px 0}.advanced-content label{display:block;margin:16px 0}.advanced-content .el-input{margin-top:7px}.advanced-actions{display:flex;flex-wrap:wrap;gap:8px}.advanced-actions .el-button{margin:0}.file-path{overflow-wrap:anywhere}.sample-result{padding:12px;background:var(--sv-surface-3);border-radius:8px;margin:16px 0}.sample-preview{display:flex;gap:12px;margin-top:12px}.sample-preview img{width:64px;height:64px;border-radius:6px;object-fit:cover}.sample-preview h4{margin:0}.sample-preview pre{white-space:pre-wrap;overflow-wrap:anywhere}.share-parts{display:flex;flex-wrap:wrap;gap:12px;max-height:180px;overflow:auto;margin:12px 0}.share-parts a{color:var(--sv-accent-primary)}@media(max-width:480px){.action-row{align-items:flex-start;gap:12px;flex-wrap:wrap}.action-row>div{flex:1 0 100%}.ready-status{align-items:flex-start;flex-wrap:wrap}}
</style>
<style>.library-workflow-dialog .el-dialog__body{max-height:72vh;overflow:auto}</style>
